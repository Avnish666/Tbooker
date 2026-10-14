#!/usr/bin/env python3
"""Disposable open-arrival capacity experiments. Never targets an existing database.

python3 performance/phase12c/run.py --output performance/results/phase12c-RUN
Use --resume to continue completed jobs without overwriting their evidence.
"""
import argparse
import csv
import datetime
import hashlib
import json
import os
from pathlib import Path
import platform
import statistics
import subprocess
import sys
import threading
import time
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'verification'))
from stack import ROOT, Stack, run, wait_for

HERE = Path(__file__).resolve().parent
K6 = 'grafana/k6:1.6.0@sha256:be2a9ceb1b6ffc573277af4157135882a6d6433968b41e858ab02c5fd5847532'
METERS = ['jvm.memory.used?tag=area:heap', 'jvm.gc.pause', 'hikaricp.connections',
          'hikaricp.connections.active', 'hikaricp.connections.pending',
          'kafka.consumer.fetch.manager.records.lag.max', 'booking.outbox.publish.delay']


def write_json(path, value):
    temporary = path.with_suffix(path.suffix + '.tmp')
    temporary.write_text(json.dumps(value, indent=2) + '\n')
    temporary.replace(path)


def meters(s, names):
    values = {}
    for name in names:
        values[name] = {}
        for metric in METERS:
            key, _, tags = metric.partition('?')
            try:
                values[name][metric] = s.metric(name, key, '?' + tags if tags else '')
            except (RuntimeError, ValueError):
                values[name][metric] = {'unavailable': True}
    return values


def db_sample(s):
    return json.loads(s.sql("""SELECT json_build_object('observed_at',clock_timestamp(),
      'pending',(SELECT count(*) FROM tbooker.outbox_events WHERE status<>'PUBLISHED'),
      'published',(SELECT count(*) FROM tbooker.outbox_events WHERE status='PUBLISHED'),
      'bookings',(SELECT count(*) FROM tbooker.bookings),
      'processed',(SELECT count(*) FROM tbooker.processed_events),
      'connections',(SELECT count(*) FROM pg_stat_activity WHERE application_name IN ('app-a','app-b')),
      'active_connections',(SELECT count(*) FROM pg_stat_activity WHERE application_name IN ('app-a','app-b') AND state='active'),
      'lock_waiters',(SELECT count(*) FROM pg_stat_activity WHERE application_name IN ('app-a','app-b') AND wait_event_type='Lock'));"""))


def redis_stats(s):
    info = s.docker('redis', 'redis-cli', 'INFO', 'stats')
    fields = dict(line.split(':', 1) for line in info.splitlines() if ':' in line)
    return {k: int(fields[k]) for k in ['keyspace_hits', 'keyspace_misses', 'total_commands_processed', 'evicted_keys']}


def cpu_stats(s, names):
    result = {}
    for name in names:
        try:
            result[name] = dict((k, int(v)) for k, v in
                (row.split() for row in s.docker(name, 'cat', '/sys/fs/cgroup/cpu.stat').splitlines()))
        except (RuntimeError, ValueError):
            result[name] = {'unavailable': True}
    return result


class Sampler:
    def __init__(self, s, names, generator):
        self.s, self.names, self.generator = s, names, generator
        self.samples = []
        self.stop = threading.Event()
        self.thread = threading.Thread(target=self.collect, daemon=True)

    def collect(self):
        services = self.names + ['nginx', 'postgres', 'redis', 'kafka']
        identities = {self.s.containers[n][:12]: n for n in services}
        while not self.stop.is_set():
            started = time.monotonic()
            sample = {'utc': datetime.datetime.now(datetime.timezone.utc).isoformat()}
            try:
                rows = run('docker', 'stats', '--no-stream', '--format', '{{json .}}',
                           *(self.s.containers[n] for n in services), self.generator)
                sample['resources'] = {}
                for line in rows.splitlines():
                    row = json.loads(line)
                    name = identities.get(row['ID'][:12], 'k6')
                    sample['resources'][name] = {k: row[k] for k in ['CPUPerc', 'MemUsage', 'PIDs']}
                sample['database'] = db_sample(self.s)
                if len(self.samples) % 2 == 0:
                    sample['jvm'] = meters(self.s, self.names)
            except Exception as error:
                sample['sampling_error'] = type(error).__name__
            sample['collection_seconds'] = time.monotonic() - started
            self.samples.append(sample)
            self.stop.wait(max(0, 3 - sample['collection_seconds']))

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *_):
        self.stop.set()
        self.thread.join(timeout=45)
        if self.thread.is_alive():
            raise RuntimeError('Metrics collector did not stop')


def seed(s):
    # The Stack context owns this UUID project; both JVMs must be stopped here.
    assert all(not json.loads(run('docker', 'inspect', '--format', '{{json .State}}', s.containers[n]))['Running']
               for n in ['app-a', 'app-b'])
    s.sql("""TRUNCATE tbooker.outbox_events,tbooker.processed_events,tbooker.booking_notifications,
        tbooker.mock_gateway_receipts,tbooker.payments,tbooker.reservations,tbooker.bookings,
        tbooker.show_seats,tbooker.movie_shows RESTART IDENTITY CASCADE;
        UPDATE tbooker.booking_analytics SET confirmed_count=0;
        INSERT INTO tbooker.movies(title,duration_minutes)
          SELECT 'Capacity movie '||n,120 FROM generate_series(1,9) n
          WHERE NOT EXISTS(SELECT 1 FROM tbooker.movies WHERE title='Capacity movie '||n);
        INSERT INTO tbooker.theatres(name,city)
          SELECT 'Capacity theatre '||n,'Bengaluru' FROM generate_series(1,2) n
          WHERE NOT EXISTS(SELECT 1 FROM tbooker.theatres WHERE name='Capacity theatre '||n);
        INSERT INTO tbooker.screens(theatre_id,name) SELECT id,'Capacity screen' FROM tbooker.theatres
          WHERE name LIKE 'Capacity theatre %' ON CONFLICT DO NOTHING;
        INSERT INTO tbooker.seats(screen_id,seat_number)
          SELECT id,'C'||n FROM tbooker.screens CROSS JOIN generate_series(1,80) n ON CONFLICT DO NOTHING;
        WITH m AS (SELECT array_agg(id ORDER BY id) ids FROM tbooker.movies),
             sc AS (SELECT array_agg(id ORDER BY id) ids FROM tbooker.screens)
        INSERT INTO tbooker.movie_shows(movie_id,screen_id,start_time)
          SELECT m.ids[n%10+1],sc.ids[n%3+1],TIMESTAMPTZ '2030-01-01 18:00:00+00'+n*interval '3 hours'
          FROM m,sc,generate_series(0,99) n ORDER BY n;
        INSERT INTO tbooker.show_seats(show_id,seat_id,screen_id)
          SELECT sh.id,se.id,sh.screen_id FROM tbooker.movie_shows sh JOIN tbooker.seats se
          ON sh.screen_id=se.screen_id WHERE se.seat_number LIKE 'C%';
        INSERT INTO tbooker.users(name,email,role)
          SELECT 'Capacity '||n,'capacity-'||n||'@example.test','USER' FROM generate_series(1,8000) n
          ON CONFLICT DO NOTHING;""")
    s.docker('redis', 'redis-cli', 'FLUSHDB')
    inventory = json.loads(s.sql("""SELECT json_agg(json_build_object('id',ss.id,'show',ss.show_id)
       ORDER BY se.seat_number,ss.show_id) FROM tbooker.show_seats ss JOIN tbooker.seats se ON se.id=ss.seat_id;"""))
    ids = json.loads(s.sql("SELECT json_agg(id ORDER BY id) FROM tbooker.users WHERE email LIKE 'capacity-%';"))
    meta = json.loads(s.sql("""SELECT json_build_object('movies',(SELECT json_agg(id ORDER BY id) FROM tbooker.movies),
       'theatres',(SELECT json_agg(id ORDER BY id) FROM tbooker.theatres),
       'shows',(SELECT json_agg(id ORDER BY id) FROM tbooker.movie_shows));"""))
    assert len(inventory) == len(ids) == 8000 and len(meta['shows']) == 100
    fixtures = s.directory / 'fixture'
    fixtures.mkdir(exist_ok=True)
    (fixtures/'data.json').write_text(json.dumps({'inventory': inventory, 'users': [dict(id=i, token=s.token(i)) for i in ids]}))
    (fixtures/'data.json').chmod(0o600)
    (fixtures/'meta.json').write_text(json.dumps(meta))
    return fixtures


def kafka_lag(s):
    try:
        text = s.docker('kafka', '/opt/kafka/bin/kafka-consumer-groups.sh', '--bootstrap-server', 'localhost:19092',
                        '--describe', '--all-groups', '--offsets')
        rows = [line.split() for line in text.splitlines() if line.startswith(('tbooker-notifications-v1 ', 'tbooker-analytics-v1 '))]
        valid = [row for row in rows if row[5].isdigit()]
        return {'raw': text, 'committed_offset_lag': {group: sum(int(row[5]) for row in valid if row[0] == group)
                for group in ['tbooker-notifications-v1', 'tbooker-analytics-v1']} if len(valid) == 6 else None}
    except RuntimeError:
        return {'unavailable': True}


def event_rows(s, after):
    return json.loads(s.sql(f"""SELECT COALESCE(json_agg(row_to_json(t) ORDER BY booking_id),'[]') FROM (
        SELECT e.aggregate_id booking_id,e.created_at,e.published_at,e.attempts,
        EXTRACT(EPOCH FROM e.published_at-e.created_at)*1000 publication_ms,
        MAX(EXTRACT(EPOCH FROM p.processed_at-e.created_at)*1000) FILTER(WHERE p.consumer_name='booking-notifications-v1') notification_ms,
        MAX(EXTRACT(EPOCH FROM p.processed_at-e.created_at)*1000) FILTER(WHERE p.consumer_name='booking-analytics-v1') analytics_ms
        FROM tbooker.outbox_events e LEFT JOIN tbooker.processed_events p ON p.event_id=e.event_id
        WHERE e.aggregate_id>{after} GROUP BY e.event_id) t;"""))


def percentile(values, p):
    if not values:
        return None
    data = sorted(values); position = (len(data)-1)*p
    lo = int(position); hi = min(lo+1, len(data)-1)
    return data[lo] + (data[hi]-data[lo])*(position-lo)


def event_summary(rows):
    result = {'events': len(rows), 'retry_events': sum(r['attempts'] > 1 for r in rows)}
    for key in ['publication_ms', 'notification_ms', 'analytics_ms']:
        values = [float(r[key]) for r in rows if r[key] is not None]
        result[key] = {'count': len(values), 'p50': percentile(values, .5), 'p95': percentile(values, .95),
                       'p99': percentile(values, .99), 'max': max(values) if values else None}
    return result


def invariant_check(s):
    state = json.loads(s.sql("""SELECT json_build_object(
      'bookings',(SELECT COUNT(*) FROM tbooker.bookings),
      'held',(SELECT COUNT(*) FROM tbooker.reservations WHERE status='HELD'),
      'outbox',(SELECT COUNT(*) FROM tbooker.outbox_events),
      'notifications',(SELECT COUNT(*) FROM tbooker.booking_notifications),
      'markers',(SELECT COUNT(*) FROM tbooker.processed_events),
      'analytics',(SELECT confirmed_count FROM tbooker.booking_analytics WHERE id=1),
      'duplicate_bookings',(SELECT COUNT(*) FROM (SELECT show_seat_id FROM tbooker.bookings GROUP BY show_seat_id HAVING COUNT(*)>1) q),
      'duplicate_holds',(SELECT COUNT(*) FROM (SELECT show_seat_id FROM tbooker.reservations WHERE status='HELD' GROUP BY show_seat_id HAVING COUNT(*)>1) q),
      'inconsistent_inventory',(SELECT COUNT(*) FROM tbooker.show_seats s WHERE (s.status='BOOKED')<>(EXISTS(SELECT 1 FROM tbooker.bookings b WHERE b.show_seat_id=s.id))),
      'unpaid_bookings',(SELECT COUNT(*) FROM tbooker.bookings b LEFT JOIN tbooker.reservations r ON r.booking_id=b.id
        LEFT JOIN tbooker.payments p ON p.id=r.confirmed_payment_id WHERE p.status IS DISTINCT FROM 'SUCCESS'));
    """))
    assert state['duplicate_bookings'] == state['duplicate_holds'] == state['inconsistent_inventory'] == state['unpaid_bookings'] == 0, state
    assert state['bookings'] == state['outbox'] == state['notifications'] == state['analytics'], state
    assert state['markers'] == 2 * state['bookings'], state
    return state


def execute_k6(s, names, fixtures, directory, job, plan, warm=False):
    directory.mkdir()
    mode = ('mixed' if job['workload'] in ['mixed', 'events', 'contention'] else job['workload']) if warm else job['workload']
    seconds = plan['warmup_seconds'] if warm else plan['duration_seconds']
    rate = min(job.get('rate', 5), 50 if mode in ['catalogue', 'availability'] else 5) if warm else job.get('rate', 1)
    name = s.project + '-load'
    network = s.project + '_default'
    command = ['docker', 'create', '--name', name, '--network', network, '--user', f'{os.getuid()}:{os.getgid()}',
        '--cpus', str(plan['generator_cpu_limit']), '--memory', f"{plan['generator_memory_mib']}m", '--cap-drop', 'ALL',
        '-v', f'{HERE / "workloads.js"}:/scripts/workloads.js:ro', '-v', f'{fixtures}:/fixtures:ro',
        '-v', f'{directory.resolve()}:/results', '-e', 'NO_PROXY=nginx,localhost,127.0.0.1', '-e', 'no_proxy=nginx,localhost,127.0.0.1',
        '-e', f'WORKLOAD={mode}', '-e', f'DURATION={seconds}s', '-e', f'RATE={rate}',
        '-e', f'OFFSET={0 if warm else 1000}', '-e', f'RUN_ID={job["id"]}-{"warm" if warm else "measured"}',
        K6, 'run', '--quiet', '--out', 'json=/results/raw.ndjson.gz', '/scripts/workloads.js']
    run(*command)
    fault = []; worker = None; started = time.monotonic()
    def outage():
        time.sleep(plan['kafka_pause_start_seconds'])
        run('docker', 'pause', s.containers['kafka'])
        fault.append({'action': 'paused', 'elapsed_seconds': time.monotonic()-started, 'utc': datetime.datetime.now(datetime.timezone.utc).isoformat()})
        try:
            time.sleep(plan['kafka_pause_duration_seconds'])
        finally:
            run('docker', 'unpause', s.containers['kafka'])
            fault.append({'action': 'unpaused', 'elapsed_seconds': time.monotonic()-started, 'utc': datetime.datetime.now(datetime.timezone.utc).isoformat()})
    try:
        with (directory/'console.log').open('w') as log:
            process = subprocess.Popen(['docker', 'start', '-a', name], stdout=log, stderr=subprocess.STDOUT)
            wait_for(lambda: run('docker', 'inspect', '--format', '{{.State.Running}}', name)=='true', 'k6 started', 15)
            if job.get('fault') and not warm:
                worker = threading.Thread(target=outage, daemon=True); worker.start()
            with Sampler(s, names, name) as collector:
                try:
                    process.wait(timeout=seconds+90)
                except subprocess.TimeoutExpired:
                    run('docker', 'kill', name); process.wait(timeout=15)
                    raise
            if worker: worker.join(timeout=30)
        exit_code = int(run('docker', 'inspect', '--format', '{{.State.ExitCode}}', name))
        summary = json.loads((directory/'summary.json').read_text())
        assert exit_code in (0, 99), f'k6 execution error {exit_code}; inspect retained console.log'
        assert summary['metrics']['fixture_exhaustion']['values']['count'] == 0, 'Insufficient unique fixtures'
        return {'exit_code': exit_code, 'summary': summary, 'samples': collector.samples, 'fault': fault,
                'elapsed_seconds': time.monotonic()-started}
    finally:
        if worker and worker.is_alive(): worker.join(timeout=30)
        subprocess.run(['docker','unpause',s.containers['kafka']],capture_output=True)
        subprocess.run(['docker','rm','-f',name],capture_output=True)


def jobs_for(plan):
    jobs = []
    for workload, rates in plan['rates'].items():
        for index, rate in enumerate(rates):
            order = plan['middle_rate_order'] if index==1 else plan['other_rate_order']
            for ordinal, replicas in enumerate(order):
                jobs.append(dict(id=f'{workload}-r{rate}-n{replicas}-s{ordinal+1}',workload=workload,rate=rate,replicas=replicas))
    for ordinal, replicas in enumerate(plan['contention_order']):
        jobs.append(dict(id=f'contention-n{replicas}-s{ordinal+1}',workload='contention',replicas=replicas))
    for replicas in (1,2):
        jobs.append(dict(id=f'recovery-n{replicas}',workload='events',rate=plan['kafka_recovery_rate'],replicas=replicas,fault=True))
    return jobs


def experiment(s, job, path, plan):
    names = ['app-a'] + (['app-b'] if job['replicas']==2 else [])
    wait_for(s.published, 'drain before reset', 180)
    s.compose('stop','app-a','app-b')
    fixtures = seed(s)
    s.compose('start',*names)
    for name in names: s.ready(name)
    s.upstreams_for(names)
    wait_for(lambda:s.http('/api/shows')['status']==200,'NGINX routed',60)
    path.mkdir()
    # Cache starts empty: the readiness/routing GET above fills one show-list key.
    s.docker('redis','redis-cli','FLUSHDB')
    cold_cache = redis_stats(s)
    warm = execute_k6(s,names,fixtures,path/'warmup',job,plan,warm=True)
    assert warm['summary']['metrics']['unexpected_failures']['values']['rate']==0, 'Warmup HTTP failure'
    assert warm['summary']['metrics']['checks']['values']['rate']==1, 'Warmup contract failure'
    wait_for(s.published,'warmup events drained',120)
    watermark = int(s.sql('SELECT COALESCE(MAX(id),0) FROM tbooker.bookings;'))
    services = names+['postgres','redis','kafka','nginx']
    before = {'jvm':meters(s,names),'redis':redis_stats(s),'cpu':cpu_stats(s,services),'db':db_sample(s)}
    s.sql('SELECT pg_stat_statements_reset();')
    measurement_start = time.monotonic()
    result = execute_k6(s,names,fixtures,path/'measured',job,plan)
    end_db = db_sample(s)
    after = {'redis':redis_stats(s),'cpu':cpu_stats(s,services),'jvm':meters(s,names),'db':end_db}
    sql = json.loads(s.sql("""SELECT json_build_object(
      'calls',(SELECT COALESCE(SUM(calls),0) FROM pg_stat_statements WHERE query NOT ILIKE '%pg_stat_statements%'),
      'top',(SELECT json_agg(row_to_json(t)) FROM (SELECT query,calls,total_exec_time,rows FROM pg_stat_statements
        WHERE query NOT ILIKE '%pg_stat_statements%' ORDER BY total_exec_time DESC LIMIT 15) t));"""))
    sql_window = time.monotonic()-measurement_start
    lag_end = kafka_lag(s)
    drain_start = time.monotonic()
    wait_for(s.published,'all measured events projected',plan['targets']['recovery_drain_seconds'])
    drain_seconds = time.monotonic()-drain_start
    state = invariant_check(s)
    event_data = event_rows(s,watermark)
    write_json(path/'event-timestamps.json',event_data)
    events = event_summary(event_data)
    metrics = result['summary']['metrics']
    if job['workload']=='contention':
        assert state['held']==1 and metrics['successful_holds']['values']['count']==1
        assert state['bookings']==before['db']['bookings']
        assert metrics['http_reqs']['values']['count']-metrics['business_conflicts']['values']['count']==1
    if job['workload'] in ['mixed','events'] and metrics['unexpected_failures']['values']['rate']==0:
        assert state['bookings']-before['db']['bookings']==metrics['confirmed_bookings']['values']['count']
    pipeline_ok = (not event_data or (events['publication_ms']['p95']<=plan['targets']['event_publication_p95_ms']
        and max(events['notification_ms']['p95'],events['analytics_ms']['p95'])<=plan['targets']['event_projection_p95_ms']
        and end_db['pending']<=job['replicas']*plan['targets']['event_pending_end_per_replica']))
    record = dict(job=job, before=before, after=after, sql=sql, sql_window_seconds=sql_window,
                  cold_cache=cold_cache, warmup_exit_code=warm['exit_code'],
                  event_delays=events, drain_seconds=drain_seconds, consumer_lag_at_end=lag_end,
                  final_database=state, pipeline_target_passed=pipeline_ok,
                  target_passed=result['exit_code']==0 and (pipeline_ok or bool(job.get('fault'))), **result)
    write_json(path/'run.json',record)
    return record


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--plan',type=Path,default=HERE/'plan.json')
    parser.add_argument('--resume',action='store_true')
    parser.add_argument('--only',action='append',help='Job ID to run (repeatable); useful for pilot or missing jobs')
    args=parser.parse_args()
    plan=json.loads(args.plan.read_text())
    hashes={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in [args.plan,HERE/'workloads.js',HERE/'run.py']}
    if args.output.exists() and not args.resume: parser.error('Output exists; choose a new directory or explicitly --resume')
    args.output.mkdir(parents=True,exist_ok=True)
    report_path=args.output/'report.json'
    report=json.loads(report_path.read_text()) if args.resume and report_path.exists() else dict(
        phase='12C',started_utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),
        base_commit=run('git','rev-parse','HEAD',cwd=ROOT),plan=plan,script_hashes=hashes,runs=[],sessions=[])
    assert report['plan']==plan and report['script_hashes']==hashes, 'Plan/scripts changed; use a new result directory'
    report['completed']=False
    write_json(report_path,report)
    with Stack(pool=20) as s:
        # Match actual Compose defaults, overriding only the inherited test harness's fast worker settings.
        values=dict(line.split('=',1) for line in s.env_file.read_text().splitlines())
        values.update(RESERVATION_CLEANUP_MS='30000',OUTBOX_POLL_MS='1000',OUTBOX_LEASE='PT30S')
        s.env_file.write_text('\n'.join(f'{k}={v}' for k,v in values.items())+'\n')
        # Recreate both apps to apply those settings before any measured run.
        s.compose('up','-d','--no-build','--wait','--wait-timeout','240','app-a','app-b')
        for name in ['app-a','app-b']: s.containers[name]=s.compose('ps','-q',name)
        image_info=json.loads(run('docker','inspect',*s.containers.values()))
        session=dict(utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),platform=platform.platform(),
            python=platform.python_version(),docker=json.loads(run('docker','info','--format','{{json .}}')),
            cpu_info=run('lscpu','--json'),
            jar_sha256=s.docker('app-a','sha256sum','/app/app.jar').split()[0],
            services={name:dict(image=item['Image'],memory_bytes=item['HostConfig']['Memory'],nano_cpus=item['HostConfig']['NanoCpus'])
                for name,item in zip(s.containers,image_info)},generator_image=K6)
        # Keep only public machine/resource facts, never Docker registry/proxy configuration.
        session['docker']={k:session['docker'][k] for k in ['ServerVersion','NCPU','MemTotal','Architecture','KernelVersion','OperatingSystem']}
        report['sessions'].append(session);write_json(report_path,report)
        done={r['job']['id'] for r in report['runs']}
        for job in jobs_for(plan):
            if job['id'] in done or (args.only and job['id'] not in args.only):continue
            path=args.output/job['id']
            if path.exists():raise RuntimeError('Partial evidence exists; preserve it and select a new output directory: '+str(path))
            print('START '+job['id'],flush=True)
            record=experiment(s,job,path,plan)
            record['session']=len(report['sessions'])-1
            report['runs'].append(record);write_json(report_path,report)
            m=record['summary']['metrics']
            print(json.dumps(dict(job=job['id'],target_passed=record['target_passed'],
                rps=m['http_reqs']['values']['rate'],p95=m['http_req_duration']['values']['p(95)'],
                dropped=m.get('dropped_iterations',{}).get('values',{}).get('count',0),
                events=record['event_delays']['events'],pending=record['after']['db']['pending'])),flush=True)
    report['completed']=len(report['runs'])==len(jobs_for(plan));write_json(report_path,report)
    print('Completed jobs: '+str(len(report['runs'])),flush=True)


if __name__=='__main__':main()
