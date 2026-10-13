#!/usr/bin/env python3
"""Phase 12B recovery checks, exclusively on fresh runner-owned volumes.

Run: python3 verification/deployment.py --output /tmp/deployment.json
Requires Docker Compose and a source build; run Maven verification separately.
Never attaches to the developer stack or reads its .env. Teardown removes only
the UUID-named resources created by Stack in this process.
"""
import argparse
from collections import Counter
import datetime
import json
from pathlib import Path
import secrets
import time
import traceback
import uuid

from stack import Stack, run, wait_for


class DeploymentChecks:
    def __init__(self, stack):
        self.s = stack

    def migration_history(self):
        return self.s.sql("""SELECT json_agg(row_to_json(h) ORDER BY installed_rank)
            FROM public.flyway_schema_history h;""")

    def readiness(self):
        for name in ('app-a', 'app-b'):
            self.s.ready(name)
        wait_for(lambda: self.s.http('/actuator/health/readiness')['status'] == 200,
                 'readiness through NGINX', 60)

    def preserved(self):
        self.readiness()
        assert self.migration_history() == self.history, 'Migration history changed on restart'
        response = self.s.http(f'/api/bookings/{self.booking_id}', token=self.token)
        assert response['status'] == 200 and response['body'] == self.booking, 'Booking changed on restart'
        assert self.s.sql(f"SELECT status FROM tbooker.show_seats WHERE id={self.seat};") == 'BOOKED'
        assert self.s.sql(f"SELECT COUNT(*) FROM tbooker.bookings WHERE show_seat_id={self.seat};") == '1'
        assert self.s.sql(f"SELECT COUNT(*) FROM tbooker.payments WHERE reservation_id={self.reservation};") == '1'

    def clean_start_and_migrations(self):
        self.history = self.migration_history()
        rows = json.loads(self.history)
        assert [r['version'] for r in rows if r['version']] == [str(n) for n in range(1, 8)]
        assert all(r['success'] for r in rows)
        assert sum(r['script'] == 'R__sample_catalogue.sql' for r in rows) == 1
        assert self.s.sql('SELECT COUNT(*) FROM tbooker.movie_shows;') == '1'
        assert self.s.sql('SELECT COUNT(*) FROM tbooker.show_seats;') == '8'
        assert all(self.s.health(name) for name in self.s.containers)
        assert self.s.http('/nginx-health')['status'] == 200
        config = json.loads(self.s.compose('config', '--format', 'json'))
        assert config['networks']['default']['internal']
        for name, service in config['services'].items():
            assert service['restart'] == 'unless-stopped'
            if name != 'nginx':
                assert not service.get('ports'), name + ' exposed a host port'
        return {'healthy_services': 6, 'versions': list(range(1, 8)),
                'sample_migrations': 1, 'internal_network': True, 'host_ports': ['nginx']}

    def jwt_and_paid_booking(self):
        email, password = uuid.uuid4().hex + '@example.test', secrets.token_hex(20)
        assert self.s.http('/api/auth/register', 'POST',
                           dict(name='Deployment check', email=email, password=password))['status'] == 201
        login = self.s.http('/api/auth/login', 'POST', dict(email=email, password=password))
        assert login['status'] == 200, 'Login failed'
        self.token = login['body']['accessToken']
        assert self.s.http('/api/bookings')['status'] == 401
        assert self.s.http('/api/bookings', token='invalid')['status'] == 401
        assert self.s.http('/actuator/metrics', token=self.token)['status'] == 403
        calls = [self.s.http('/api/bookings', token=self.token) for _ in range(20)]
        assert all(r['status'] == 200 for r in calls)
        assert len({r['upstream'] for r in calls}) == 2
        self.seat = self.s.seat()
        hold = self.s.http(f'/api/shows/{self.s.show}/reservations', 'POST',
                           {'showSeatId': self.seat}, token=self.token)
        assert hold['status'] == 201
        self.reservation = hold['body']['id']
        self.key = uuid.uuid4().hex
        payment = self.s.http(f'/api/reservations/{self.reservation}/payments', 'POST',
                              {'method': 'UPI'}, token=self.token, headers={'Idempotency-Key': self.key})
        assert payment['status'] == 201 and payment['body']['confirmationStatus'] == 'CONFIRMED'
        self.booking_id = payment['body']['bookingId']
        self.booking = self.s.http(f'/api/bookings/{self.booking_id}', token=self.token)['body']
        assert self.s.http(f'/api/bookings/{self.booking_id}', user=self.s.users[0])['status'] == 404
        self.preserved()
        return {'upstreams': dict(Counter(r['upstream'] for r in calls)),
                'registration': 201, 'login': 200, 'booking': self.booking_id,
                'missing_token': 401, 'invalid_token': 401, 'foreign_booking': 404}

    def restart_applications(self):
        self.s.compose('restart', 'app-a', 'app-b')
        self.preserved()
        return {'both_restarted': True, 'booking_preserved': True, 'migrations_unchanged': True}

    def restart_one_backend(self):
        self.s.compose('restart', 'app-b')
        assert self.s.health('app-a')
        self.preserved()
        calls = [self.s.http('/api/bookings', token=self.token) for _ in range(20)]
        assert all(r['status'] == 200 for r in calls)
        assert len({r['upstream'] for r in calls}) == 2
        return {'survivor_healthy': True, 'rejoined_upstreams': 2, 'booking_preserved': True}

    def postgres_restart(self):
        self.s.compose('stop', 'postgres')
        try:
            # Pool timeouts can exceed NGINX's 20s read timeout during DB outage.
            status = self.s.http('/actuator/health/readiness', timeout=45)['status']
            assert status in (503, 504), status
        finally:
            self.s.compose('start', 'postgres')
        self.s.ready('postgres')
        self.preserved()
        return {'outage_readiness_http': status, 'pools_reconnected': True, 'booking_preserved': True}

    def redis_outage_and_backend_restart(self):
        self.s.http('/api/shows')
        run('docker', 'pause', self.s.containers['redis'])
        try:
            assert self.s.http('/api/shows')['status'] == 200
            # `up` reconciles dependencies and Docker cannot start a paused
            # container. Restart only the backend, as the recovery runbook says.
            self.s.compose('restart', 'app-b')
            self.preserved()
            hold = self.s.hold(self.s.users[0], self.s.seat())
            assert self.s.pay(self.s.users[0], hold)['body']['confirmationStatus'] == 'CONFIRMED'
        finally:
            run('docker', 'unpause', self.s.containers['redis'])
        self.s.ready('redis')
        return {'catalogue_fallback': 200, 'backend_restarted_during_outage': True, 'paid_booking': True}

    def kafka_outage_and_backend_restart(self):
        wait_for(self.s.published, 'prior events drained', 90)
        run('docker', 'pause', self.s.containers['kafka'])
        try:
            self.s.compose('restart', 'app-b')
            self.preserved()
            assert self.s.http('/actuator/health/messaging')['status'] == 503
            hold = self.s.hold(self.s.users[0], self.s.seat())
            assert self.s.pay(self.s.users[0], hold)['body']['confirmationStatus'] == 'CONFIRMED'
            assert int(self.s.sql("SELECT COUNT(*) FROM tbooker.outbox_events WHERE status<>'PUBLISHED';")) > 0
        finally:
            run('docker', 'unpause', self.s.containers['kafka'])
        self.s.ready('kafka')
        wait_for(self.s.published, 'outbox and projections recovered', 120)
        return {'backend_restarted_during_outage': True, 'paid_booking': True, 'backlog_recovered': True}

    def recreate_stack_with_existing_volumes(self):
        before = {name: self.s.containers[name] for name in self.s.containers}
        volume_ids = self.s.compose('volumes', '-q')
        self.s.compose('down', '--timeout', '35')  # Intentionally retains volumes.
        self.s.compose('up', '-d', '--no-build', '--wait', '--wait-timeout', '240')
        for name in self.s.containers:
            self.s.containers[name] = self.s.compose('ps', '-q', name)
            assert self.s.containers[name] != before[name]
        assert self.s.compose('volumes', '-q') == volume_ids
        self.preserved()
        retry = self.s.http(f'/api/reservations/{self.reservation}/payments', 'POST',
                            {'method': 'UPI'}, token=self.token, headers={'Idempotency-Key': self.key})
        assert retry['status'] == 200 and retry['body']['bookingId'] == self.booking_id
        wait_for(self.s.published, 'projections after stack recreation', 120)
        # Exact private values never appear in diagnostics, even on assertion failure.
        logs = self.s.compose('logs', '--no-color')
        values = dict(line.split('=', 1) for line in self.s.env_file.read_text().splitlines())
        assert all(value not in logs for value in [values['DB_PASSWORD'], values['JWT_SECRET'], self.token]), \
            'A sensitive value appeared in container logs'
        return {'recreated_containers': 6, 'volumes_retained': True, 'booking_preserved': True,
                'payment_retry': 200, 'migrations_unchanged': True, 'secrets_absent_from_logs': True}


CASES = ['clean_start_and_migrations', 'jwt_and_paid_booking', 'restart_applications',
         'restart_one_backend', 'postgres_restart', 'redis_outage_and_backend_restart',
         'kafka_outage_and_backend_restart', 'recreate_stack_with_existing_volumes']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        parser.error('Choose a new output path; previous evidence is preserved.')
    results = []
    try:
        with Stack(app_restart='unless-stopped') as stack:
            checks = DeploymentChecks(stack)
            for case in CASES:
                started = time.monotonic()
                try:
                    details = getattr(checks, case)()
                    record = dict(case=case, status='passed', details=details)
                except Exception as error:
                    record = dict(case=case, status='failed', error=str(error), traceback=traceback.format_exc())
                record['seconds'] = round(time.monotonic() - started, 3)
                results.append(record)
                print(case + ': ' + record['status'], flush=True)
                if record['status'] == 'failed':
                    break  # Later persistence checks depend on the committed fixture.
    except Exception as error:
        results.append(dict(case='stack_lifecycle', status='failed', error=str(error)))
    report = dict(phase='12B', utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                  passed=sum(r['status'] == 'passed' for r in results),
                  failed=sum(r['status'] == 'failed' for r in results),
                  skipped=len([name for name in CASES if name not in {r['case'] for r in results}]), tests=results)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({k: report[k] for k in ['passed', 'failed', 'skipped']}), flush=True)
    raise SystemExit(1 if report['failed'] else 0)


if __name__ == '__main__':
    main()
