"""Private, disposable Compose stack shared by the Phase 11 tests and benchmarks.

Never attaches to an existing deployment or reads the developer's .env. All SQL,
container kills, broker pauses and volume removal are restricted to a fresh UUID
Compose project created by this process. Temporary credentials stay outside Git.
"""
import base64
import contextlib
import hashlib
import hmac
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]


def run(*args, **kwargs):
    result = subprocess.run(args, text=True, capture_output=True, **kwargs)
    if result.returncode:
        # Commands include no credential values. Keep diagnostics bounded.
        raise RuntimeError(f"Command failed ({result.returncode}): {args[0:3]}: {result.stderr[-2000:]}")
    return result.stdout.strip()


def wait_for(predicate, label, seconds=60):
    deadline = time.monotonic() + seconds
    last = None
    while time.monotonic() < deadline:
        try:
            value = predicate()
            if value:
                return value
        except (OSError, RuntimeError, ValueError, AssertionError) as error:
            last = error
        time.sleep(0.15)
    raise AssertionError(f"Timeout waiting for {label}; last observation: {last}")


class Stack:
    def __init__(self, pool=20, app_restart='no'):
        self.project = 'tb11-' + uuid.uuid4().hex[:12]
        self.work = tempfile.TemporaryDirectory(prefix=self.project + '-')
        self.directory = Path(self.work.name)
        self.key = secrets.token_bytes(32)
        with socket.socket() as sock:
            sock.bind(('127.0.0.1', 0))
            self.port = sock.getsockname()[1]
        self.base = f'http://127.0.0.1:{self.port}'
        self.env = {k: v for k, v in os.environ.items() if not k.startswith(
            ('SPRING_', 'DB_', 'JWT_', 'REDIS_', 'KAFKA_', 'MESSAGING_', 'OUTBOX_', 'BOOKING_', 'PAYMENTS_',
             'LB_', 'COMPOSE_', 'CATALOGUE_', 'CACHE_', 'RESERVATION_'))}
        self.env['BUILDX_CONFIG'] = str(self.directory / 'buildx')
        config = dict(DB_NAME='tbooker_phase11', DB_USERNAME='phase11', DB_PASSWORD=secrets.token_hex(24),
                      JWT_SECRET=base64.b64encode(self.key).decode(), LB_PORT=str(self.port),
                      DB_POOL_SIZE=str(pool), SPRING_PROFILES_ACTIVE='dev', RESERVATION_CLEANUP_MS='1000',
                      OUTBOX_POLL_MS='500', OUTBOX_LEASE='PT8S')
        self.env_file = self.directory / 'stack.env'
        self.env_file.write_text('\n'.join(f'{k}={v}' for k, v in config.items()) + '\n')
        self.env_file.chmod(0o600)
        self.upstreams = self.directory / 'upstreams.conf'
        self.upstreams.write_text((ROOT / 'deploy/nginx/upstreams.conf').read_text())
        override = {'services': {'nginx': {'volumes': [f'{self.upstreams}:/etc/nginx/tbooker-upstreams.conf:ro']}}}
        for name in ('app-a', 'app-b'):
            override['services'][name] = {'restart': app_restart, 'environment': {'PAYMENTS_MOCK_CARD_OUTCOME': 'PENDING'}}
        self.override = self.directory / 'override.json'
        self.override.write_text(json.dumps(override))
        self.prefix = ['docker', 'compose', '--project-directory', str(ROOT), '--env-file', str(self.env_file),
                       '-p', self.project, '-f', str(ROOT / 'compose.scaled.yaml'), '-f', str(self.override)]
        self.containers = {}
        self.started = False

    def compose(self, *args):
        return run(*self.prefix, *args, env=self.env)

    def __enter__(self):
        try:
            self.started = True
            self.compose('build')
            self.compose('up', '-d', '--wait', '--wait-timeout', '240')
            for name in ('app-a', 'app-b', 'nginx', 'postgres', 'redis', 'kafka'):
                self.containers[name] = self.compose('ps', '-q', name)
                assert self.containers[name]
            self.sql('CREATE EXTENSION IF NOT EXISTS pg_stat_statements;')
            self.show = int(self.sql('SELECT MIN(id) FROM tbooker.movie_shows;'))
            self.users = json.loads(self.sql("""WITH added AS (
                INSERT INTO tbooker.users(name,email,role) SELECT 'Test '||n,'phase11-'||n||'@example.test','USER'
                FROM generate_series(1,100) n RETURNING id) SELECT json_agg(id ORDER BY id) FROM added;"""))
            return self
        except BaseException:
            try:
                Path('/tmp/' + self.project + '-startup.log').write_text(self.compose('logs', '--no-color', '--tail', '120'))
                print('Startup diagnostics: /tmp/' + self.project + '-startup.log', flush=True)
            finally:
                self.close()
            raise

    def __exit__(self, *_):
        self.close()

    def close(self):
        if self.started:
            # A paused broker must be resumed before project-owned teardown.
            for cid in self.containers.values():
                subprocess.run(['docker','unpause',cid], capture_output=True)
            self.compose('down', '--volumes', '--remove-orphans', '--timeout', '10')
        self.work.cleanup()

    def docker(self, name, *args, **kwargs):
        return run('docker', 'exec', '-i', self.containers[name], *args, **kwargs)

    def sql(self, statement):
        return self.docker('postgres', 'psql', '-XqAt', '-v', 'ON_ERROR_STOP=1', '-U', 'phase11', '-d',
                           'tbooker_phase11', input=statement)

    @contextlib.contextmanager
    def locked(self, statement):
        process = subprocess.Popen(['docker','exec','-i',self.containers['postgres'],'psql','-XqAt',
                    '-v','ON_ERROR_STOP=1','-U','phase11','-d','tbooker_phase11'],
                    text=True, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            process.stdin.write("BEGIN; " + statement + "; SELECT 'LOCKED';\n")
            process.stdin.flush()
            while True:
                line = process.stdout.readline().strip()
                if line == 'LOCKED': break
                if process.poll() is not None: raise RuntimeError('Failed to acquire test coordinator lock')
            yield
        finally:
            if process.poll() is None:
                process.stdin.write('ROLLBACK;\n'); process.stdin.close()
                process.wait(timeout=10)
            if process.poll() is None: process.kill()

    def token(self, user, role='USER'):
        def encode(value):
            return base64.urlsafe_b64encode(json.dumps(value,separators=(',',':')).encode()).rstrip(b'=')
        now = int(time.time())
        data = encode({'alg':'HS256','typ':'JWT'}) + b'.' + encode(dict(iss='tbooker',aud='tbooker-api',
                    sub=str(user),iat=now,nbf=now,exp=now+900,role=role,jti=uuid.uuid4().hex))
        return (data+b'.'+base64.urlsafe_b64encode(hmac.new(self.key,data,hashlib.sha256).digest()).rstrip(b'=')).decode()

    def http(self, path, method='GET', data=None, user=None, token=None, headers=None, timeout=25):
        hdr = dict(headers or {})
        if user is not None or token: hdr['Authorization'] = 'Bearer '+(token or self.token(user))
        payload = None if data is None else json.dumps(data).encode()
        if payload is not None: hdr['Content-Type'] = 'application/json'
        req = urllib.request.Request(self.base+path, data=payload, headers=hdr, method=method)
        start = time.monotonic()
        try: response = urllib.request.urlopen(req, timeout=timeout)
        except urllib.error.HTTPError as response_error: response = response_error
        with response:
            content = response.read().decode()
            try: body = json.loads(content)
            except ValueError: body = content
            return dict(status=response.status,body=body,upstream=response.headers.get('X-Tbooker-Upstream',''),
                        correlation_id=response.headers.get('X-Request-ID',''),
                        seconds=time.monotonic()-start)

    def seat(self):
        number = 'T'+uuid.uuid4().hex[:16]
        return int(self.sql(f"""WITH physical AS (INSERT INTO tbooker.seats(screen_id,seat_number)
            SELECT screen_id,'{number}' FROM tbooker.movie_shows WHERE id={self.show} RETURNING id,screen_id)
            INSERT INTO tbooker.show_seats(show_id,seat_id,screen_id) SELECT {self.show},id,screen_id FROM physical RETURNING id;"""))

    def hold(self, user, seat):
        response = self.http(f'/api/shows/{self.show}/reservations','POST',{'showSeatId':seat},user)
        assert response['status']==201, response
        return response['body']['id']

    def pay(self, user, reservation, method='UPI', key=None):
        return self.http(f'/api/reservations/{reservation}/payments','POST',{'method':method},user,
                         headers={'Idempotency-Key':key or uuid.uuid4().hex})

    def confirm(self, user, reservation):
        return self.http(f'/api/reservations/{reservation}/confirmation','POST',user=user)

    def health(self, name):
        state=json.loads(run('docker','inspect','--format','{{json .State}}',self.containers[name]))
        return state['Running'] and state.get('Health',{}).get('Status')=='healthy'

    def ready(self, name):
        return wait_for(lambda:self.health(name), name+' ready',120)

    def metric(self, name, metric, tags=''):
        token = self.token(self.users[0],'ADMIN')
        path = '/actuator/metrics/'+metric+tags
        # Token on stdin; scripts/results never retain it.
        command = 'read -r token; wget -Y off -q -O - --header="Authorization: Bearer $token" '+"'http://localhost:8080"+path+"'"
        return json.loads(self.docker(name,'sh','-c',command,input=token+'\n'))

    def upstreams_for(self, names):
        self.upstreams.write_text(''.join(f'server {name}:8080 resolve max_fails=1 fail_timeout=5s;\n' for name in names))
        self.docker('nginx','nginx','-t')
        self.docker('nginx','nginx','-s','reload')

    def published(self):
        return self.sql("""SELECT (SELECT COUNT(*) FROM tbooker.outbox_events WHERE status<>'PUBLISHED')=0
            AND (SELECT COUNT(*) FROM tbooker.booking_notifications)=(SELECT COUNT(*) FROM tbooker.bookings)
            AND (SELECT confirmed_count FROM tbooker.booking_analytics WHERE id=1)=(SELECT COUNT(*) FROM tbooker.bookings);""")=='t'
