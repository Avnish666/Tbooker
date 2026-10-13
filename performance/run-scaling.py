#!/usr/bin/env python3
"""ABBA comparison of one/two JVMs through the SAME NGINX/shared infrastructure.
Build/test the JAR first. Uses only fresh disposable Compose volumes and credentials.
Reports measured results, Docker CPU/memory samples, JVM/pool snapshots, and SQL calls.
"""
import argparse
from collections import defaultdict
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
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'verification'))
from stack import ROOT, Stack, run, wait_for

METRICS=['http.server.requests','jvm.memory.used?tag=area:heap','jvm.gc.pause',
         'hikaricp.connections','hikaricp.connections.active','hikaricp.connections.idle',
         'hikaricp.connections.pending','hikaricp.connections.max',
         'booking.outbox.pending','kafka.consumer.fetch.manager.records.lag.max']


def snapshots(s, names):
    result={}
    for name in names:
        result[name]={}
        for metric in METRICS:
            try:
                key,_,query=metric.partition('?')
                result[name][metric]=s.metric(name,key,('?'+query) if query else '')
            except (RuntimeError,ValueError) as error:
                result[name][metric]={'unavailable':type(error).__name__}
    return result


class Sampler:
    def __init__(self,s,names):
        self.s=s;self.names=names;self.samples=[];self.stop=threading.Event()
        self.thread=threading.Thread(target=self.collect,daemon=True)
    def collect(self):
        services=self.names+['nginx','postgres','redis','kafka']
        while not self.stop.is_set():
            start=time.monotonic()
            try:
                rows=[json.loads(line) for line in run('docker','stats','--no-stream','--format','{{json .}}',
                        *(self.s.containers[x] for x in services)).splitlines()]
                by_id={self.s.containers[name][:12]:name for name in services}
                stats={by_id[row['ID'][:12]]:{'cpu_percent':row['CPUPerc'],'memory':row['MemUsage'],
                       'pids':row['PIDs']} for row in rows}
                pool=snapshots(self.s,self.names) if len(self.samples)%4==0 else None
                locks=json.loads(self.s.sql("""SELECT COALESCE(json_object_agg(application_name,n),'{}') FROM
                    (SELECT application_name,COUNT(*) n FROM pg_stat_activity WHERE wait_event_type='Lock'
                     AND application_name IN ('app-a','app-b') GROUP BY application_name) q;"""))
                self.samples.append(dict(time_seconds=time.time(),resources=stats,metrics=pool,postgres_lock_waiters=locks))
            except Exception as error:self.samples.append({'sampling_error':type(error).__name__})
            self.stop.wait(max(0,2-(time.monotonic()-start)))
    def __enter__(self):self.thread.start();return self
    def __exit__(self,*_):self.stop.set();self.thread.join(timeout=30)


def seed(s):
    # Apps are stopped, and only this fresh project's database is reachable here.
    s.sql("""TRUNCATE tbooker.outbox_events,tbooker.processed_events,tbooker.booking_notifications,
          tbooker.mock_gateway_receipts,tbooker.payments,tbooker.reservations,tbooker.bookings RESTART IDENTITY CASCADE;
          UPDATE tbooker.show_seats SET status='AVAILABLE'; UPDATE tbooker.booking_analytics SET confirmed_count=0;
          INSERT INTO tbooker.movie_shows(movie_id,screen_id,start_time)
          SELECT movie_id,screen_id,start_time+n*interval '3 hours'
          FROM (SELECT * FROM tbooker.movie_shows ORDER BY id LIMIT 1) first CROSS JOIN generate_series(1,99) n
          ON CONFLICT(screen_id,start_time) DO NOTHING;
          INSERT INTO tbooker.seats(screen_id,seat_number)
          SELECT (SELECT screen_id FROM tbooker.movie_shows ORDER BY id LIMIT 1),'B'||n FROM generate_series(1,12000) n
          ON CONFLICT(screen_id,seat_number) DO NOTHING;
          INSERT INTO tbooker.show_seats(show_id,seat_id,screen_id)
          SELECT (SELECT MIN(id) FROM tbooker.movie_shows),id,screen_id FROM tbooker.seats WHERE seat_number LIKE 'B%'
          ON CONFLICT(show_id,seat_id) DO NOTHING;""")
    seats=json.loads(s.sql("SELECT json_agg(s.id ORDER BY s.id) FROM tbooker.show_seats s JOIN tbooker.seats p ON p.id=s.seat_id WHERE p.seat_number LIKE 'B%';"))
    s.docker('redis','redis-cli','FLUSHDB')
    return seats


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--duration',default='30s');p.add_argument('--warmup',default='10s')
    p.add_argument('--read-vus',type=int,default=20);p.add_argument('--mixed-vus',type=int,default=20)
    p.add_argument('--contention-vus',type=int,default=100)
    p.add_argument('--workload',action='append',choices=['catalogue','mixed','contention'])
    p.add_argument('--order',default='1,2,2,1')
    args=p.parse_args()
    if args.output.exists():p.error('Output exists; preserve previous evidence and choose another path.')
    order=[int(x) for x in args.order.split(',')]
    if any(x not in (1,2) for x in order):p.error('Order supports only one or two replicas.')
    records=[]
    report=dict(utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),jar_sha256=hashlib.sha256(
        (ROOT/'target/Tbooker-0.0.1-SNAPSHOT.jar').read_bytes()).hexdigest(),order=order,duration=args.duration,
        catalogue_warmup=args.warmup,host=platform.platform(),cpu_count=os.cpu_count(),runs=records,
        pool_per_instance=20,app_cpu_quota=1,app_memory_limit_mib=768,heap_max_mib=384,
        cleanup_interval_ms=1000,outbox_poll_ms=500,outbox_lease_seconds=8,
        notes=['Both configurations use NGINX; B is stopped for one-instance runs.',
               'Each extra JVM adds one CPU quota, memory and 20 pool connections.',
               'All runs reset the same database and Redis state; only catalogue reads warm up.',
               'Contention mostly measures rejection after the first successful hold.',
               'Periodic monitoring contributes small additional load; samples are not continuous peaks.'])
    def save():
        args.output.parent.mkdir(parents=True,exist_ok=True)
        args.output.write_text(json.dumps(report,indent=2)+'\n')
    with Stack(pool=20) as s:
        report['images']={name:json.loads(run('docker','inspect',cid))[0]['Image'] for name,cid in s.containers.items()}
        network=next(iter(json.loads(run('docker','inspect',s.containers['nginx']))[0]['NetworkSettings']['Networks']))
        for workload in args.workload or ['catalogue','mixed','contention']:
            for replicas in order:
                index=len(records)+1;names=['app-a']+(['app-b'] if replicas==2 else [])
                print(f'Run {index}: {workload}, {replicas} JVM(s)',flush=True)
                wait_for(s.published,'drained outbox before reset',120)
                s.compose('stop','app-a','app-b')
                # Existing consumer offsets remain beyond the now-drained old events.
                seats=seed(s)
                s.compose('start',*names)
                for name in names:s.ready(name)
                s.upstreams_for(names)
                wait_for(lambda:s.http('/api/shows')['status']==200,'NGINX after reconfiguration')
                fixtures=s.directory/f'fixture-{index}.json'
                fixtures.write_text(json.dumps(dict(show=s.show,seats=seats,
                    users=[dict(id=user,token=s.token(user)) for user in s.users])))
                fixtures.chmod(0o600)
                def k6(kind,mode,duration,vus):
                    directory=s.directory/f'{index}-{kind}';directory.mkdir()
                    command=['docker','run','--rm','--network',network,'--user',f'{os.getuid()}:{os.getgid()}',
                        '-v',f'{ROOT / "performance/scaling.js"}:/scripts/scaling.js:ro',
                        '-v',f'{fixtures}:/fixtures/data.json:ro','-v',f'{directory}:/results',
                        '-e','NO_PROXY=nginx,localhost,127.0.0.1','-e','no_proxy=nginx,localhost,127.0.0.1',
                        '-e','BASE_URL=http://nginx:8080','-e',f'WORKLOAD={mode}','-e',f'DURATION={duration}',
                        '-e',f'VUS={vus}','-e',f'RUN_ID={index}', 'grafana/k6:1.6.0','run','--quiet','/scripts/scaling.js']
                    result=subprocess.run(command,text=True,capture_output=True)
                    data=json.loads((directory/'summary.json').read_text()) if (directory/'summary.json').exists() else None
                    return result.returncode,data,result.stderr[-1000:]
                code,_,stderr=k6('warmup','catalogue',args.warmup,args.read_vus)
                if code:raise RuntimeError('Catalogue warmup failed: '+stderr)
                before=snapshots(s,names)
                s.sql('SELECT pg_stat_statements_reset();')
                vus=getattr(args,{'catalogue':'read_vus','mixed':'mixed_vus','contention':'contention_vus'}[workload])
                with Sampler(s,names) as sampler:code,data,stderr=k6('measured',workload,args.duration,vus)
                after=snapshots(s,names)
                sql_calls=int(s.sql("SELECT COALESCE(SUM(calls),0)::bigint FROM pg_stat_statements WHERE query NOT ILIKE '%pg_stat_statements%';"))
                state=json.loads(s.sql("""SELECT json_build_object('bookings',(SELECT COUNT(*) FROM tbooker.bookings),
                     'held',(SELECT COUNT(*) FROM tbooker.reservations WHERE status='HELD'),
                     'duplicate_bookings',(SELECT COUNT(*) FROM (SELECT show_seat_id FROM tbooker.bookings GROUP BY show_seat_id HAVING COUNT(*)>1) q));"""))
                wait_for(s.published,'projections after benchmark',120)
                record=dict(index=index,workload=workload,replicas=replicas,vus=vus,k6_exit=code,
                    summary=data,metrics_before=before,metrics_after=after,samples=sampler.samples,sql_calls=sql_calls,final_database=state)
                records.append(record);save()
                assert data is not None and code==0, f'k6 failed: {stderr}'
                assert state['duplicate_bookings']==0,state
                if workload=='mixed':assert state['bookings']==data['metrics']['confirmed_bookings']['values']['count']
                if workload=='contention':
                    assert state['held']==1 and state['bookings']==0
                    assert data['metrics']['http_reqs']['values']['count']-data['metrics']['business_conflicts']['values']['count']==1
                metrics=data['metrics']
                print(json.dumps(dict(rps=metrics['http_reqs']['values']['rate'],p95_ms=metrics['http_req_duration']['values']['p(95)'],
                    unexpected_error_rate=metrics['unexpected_failures']['values']['rate'],sql_calls=sql_calls)),flush=True)
    report['completed']=True;save()


if __name__=='__main__':main()
