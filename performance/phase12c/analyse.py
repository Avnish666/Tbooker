#!/usr/bin/env python3
"""Generate tables/charts from retained measurements; never invent absent metrics."""
import argparse
from collections import defaultdict
import datetime
import gzip
import json
import math
import os
from pathlib import Path
import re
import statistics
# Keep plotting caches writable in restricted cloud checkouts.
os.environ.setdefault('MPLCONFIGDIR', '/tmp/tbooker-matplotlib')
os.environ.setdefault('XDG_CACHE_HOME', '/tmp/tbooker-plot-cache')
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

FACTORS={'catalogue':1,'availability':1,'mixed':9,'events':3,'contention':None}
BASELINES={'catalogue':600,'availability':150,'mixed':15,'events':30,'contention':None}
COLORS={1:'#2563eb',2:'#e56b2f'}


def mean(values):
    valid=[v for v in values if v is not None]
    return statistics.mean(valid) if valid else None


def value(metrics,key,field):
    return metrics.get(key,{}).get('values',{}).get(field)


def meter(raw,key,stat):
    return next((x['value'] for x in raw.get(key,{}).get('measurements',[]) if x['statistic']==stat),None)


def memory_mib(text):
    match=re.match(r'([\d.]+)([a-zA-Z]+)',text.split('/')[0].strip())
    if not match:return None
    size,unit=match.groups()
    scale={'B':1,'kB':1000,'KB':1000,'KiB':1024,'MB':1e6,'MiB':2**20,'GB':1e9,'GiB':2**30}
    return float(size)*scale[unit]/2**20 if unit in scale else None


def summarise(run):
    job=run['job'];metrics=run['summary']['metrics'];samples=run['samples']
    requests=value(metrics,'http_reqs','count')
    redis={key:run['after']['redis'][key]-run['before']['redis'][key] for key in ['keyspace_hits','keyspace_misses']}
    redis_n=sum(redis.values())
    row=dict(id=job['id'],workload=job['workload'],replicas=job['replicas'],offered_iterations_s=job.get('rate'),
       offered_http_s=job.get('rate',0)*FACTORS.get(job['workload'],1) if FACTORS.get(job['workload']) else None,
       recovery=bool(job.get('fault')),target_passed=run['target_passed'],http_target_passed=run['exit_code']==0,
       pipeline_target_passed=run['pipeline_target_passed'],requests=requests,
       achieved_rps=value(metrics,'http_reqs','rate'),p50_ms=value(metrics,'http_req_duration','med'),
       p95_ms=value(metrics,'http_req_duration','p(95)'),p99_ms=value(metrics,'http_req_duration','p(99)'),
       http_error_rate=value(metrics,'http_req_failed','rate'),unexpected_error_rate=value(metrics,'unexpected_failures','rate'),
       checks_pass_rate=value(metrics,'checks','rate'),
       sampled_active_vus_max=value(metrics,'vus','max'),
       completed_iterations=value(metrics,'iterations','count'),
       iteration_p95_ms=value(metrics,'iteration_duration','p(95)'),
       conflicts=value(metrics,'business_conflicts','count') or 0,dropped_iterations=value(metrics,'dropped_iterations','count') or 0,
       bookings=run['final_database']['bookings']-run['before']['db']['bookings'],sql_calls=run['sql']['calls'],
       sql_calls_per_request=run['sql']['calls']/requests,redis=redis,
       cache_hit_percent=100*redis['keyspace_hits']/redis_n if redis_n else None,
       event_delays=run['event_delays'],pending_end=run['after']['db']['pending'],drain_seconds=run['drain_seconds'],
       committed_consumer_lag=run['consumer_lag_at_end'].get('committed_offset_lag'),
       sampling_errors=sum('sampling_error' in s for s in samples))
    row['conflict_rate']=row['conflicts']/requests
    row['resource_means']={}
    for name in ['app-a','app-b','postgres','redis','kafka','nginx','k6']:
        observed=[s['resources'][name] for s in samples if name in s.get('resources',{})]
        if not observed:continue
        row['resource_means'][name]={'cpu_percent':mean([float(x['CPUPerc'].rstrip('%')) for x in observed]),
            'memory_max_mib':max(memory_mib(x['MemUsage']) for x in observed)}
    row['db_connections_max']=max((s.get('database',{}).get('connections',0) for s in samples),default=None)
    row['db_active_max']=max((s.get('database',{}).get('active_connections',0) for s in samples),default=None)
    row['db_lock_waiters_max']=max((s.get('database',{}).get('lock_waiters',0) for s in samples),default=None)
    row['outbox_peak_sampled']=max([run['after']['db']['pending']]+[s.get('database',{}).get('pending',0) for s in samples])
    db_samples=[s['database'] for s in samples if 'database' in s]+[run['after']['db']]
    row['unprocessed_marker_backlog_peak_sampled']=max(2*s['bookings']-s['processed'] for s in db_samples)
    row['unprocessed_marker_backlog_end']=2*run['after']['db']['bookings']-run['after']['db']['processed']
    jvm_samples=[s['jvm'] for s in samples if 'jvm' in s]+[run['after']['jvm']]
    names=['app-a']+(['app-b'] if job['replicas']==2 else [])
    row['app_memory_peak_mib']=max((sum(memory_mib(s['resources'][n]['MemUsage']) for n in names)
        for s in samples if all(n in s.get('resources',{}) for n in names)),default=None)
    for metric,stat,key in [('jvm.memory.used?tag=area:heap','VALUE','heap_max_mib'),
                            ('hikaricp.connections.active','VALUE','pool_active_max'),
                            ('hikaricp.connections.pending','VALUE','pool_pending_max'),
                            ('kafka.consumer.fetch.manager.records.lag.max','VALUE','fetch_lag_aggregate_max')]:
        points=[]
        for s in jvm_samples:
            values=[meter(s.get(n,{}),metric,stat) for n in names]
            if all(v is not None for v in values):points.append(sum(values))
        row[key]=max(points)/(2**20 if key=='heap_max_mib' else 1) if points else None
    row['gc']={}
    for name in names:
        before=run['before']['jvm'][name];after=run['after']['jvm'][name]
        values={}
        for stat,label in [('COUNT','pauses'),('TOTAL_TIME','pause_seconds')]:
            a=meter(before,'jvm.gc.pause',stat);b=meter(after,'jvm.gc.pause',stat)
            values[label]=b-a if a is not None and b is not None else None
        values['max_pause_seconds_after']=meter(after,'jvm.gc.pause','MAX')
        row['gc'][name]=values
    row['cpu_counter_delta']={}
    for name,before in run['before']['cpu'].items():
        after=run['after']['cpu'][name]
        if before.get('unavailable') or after.get('unavailable'):continue
        row['cpu_counter_delta'][name]={k:after[k]-v for k,v in before.items() if k in after}
    start=run['before']['db'].get('observed_at');end=run['after']['db'].get('observed_at')
    row['publication_observation_seconds']=(datetime.datetime.fromisoformat(end)-datetime.datetime.fromisoformat(start)).total_seconds() if start and end else None
    row['publications_in_window']=run['after']['db']['published']-run['before']['db']['published']
    row['publications_s_observed']=row['publications_in_window']/row['publication_observation_seconds'] if row['publication_observation_seconds'] else None
    row['route_latencies']={k:metrics[k]['values'] for k in metrics if k.startswith('duration_')}
    return row


def recovery_evidence(run, directory):
    """Validate fault evidence and describe the affected cohort, without an SLA claim."""
    actions=run['fault']
    assert [a['action'] for a in actions]==['paused','unpaused'], 'Broker fault did not execute'
    start,end=[datetime.datetime.fromisoformat(a['utc']) for a in actions]
    paused=(end-start).total_seconds()
    assert paused>=10, 'Broker pause was shorter than planned'
    events=json.loads((directory/run['job']['id']/'event-timestamps.json').read_text())
    cohort=[e for e in events if start<=datetime.datetime.fromisoformat(e['created_at'])<end]
    assert cohort, 'No booking events committed during the broker pause'
    delays={}
    for key in ['publication_ms','notification_ms','analytics_ms']:
        assert all(e[key] is not None for e in cohort), 'Affected event never completed'
        last=max(datetime.datetime.fromisoformat(e['created_at'])+datetime.timedelta(milliseconds=float(e[key])) for e in cohort)
        delays[key]=(last-end).total_seconds()
    return dict(pause_seconds=paused,bookings_committed_during_pause=len(cohort),
                affected_cohort_last_timestamp_seconds_after_unpause=delays,
                note='Timestamp observations, not projection commit latency; later arrivals excluded.')


def drop_timing(row,directory):
    if not row['dropped_iterations']:return None
    first=None;points=[]
    with gzip.open(directory/row['id']/'measured/raw.ndjson.gz','rt') as stream:
        for line in stream:
            if '"type":"Point"' not in line:continue
            if first is None:
                first=datetime.datetime.fromisoformat(json.loads(line)['data']['time'])
            if '"metric":"dropped_iterations"' in line:
                data=json.loads(line)['data']
                points.append(((datetime.datetime.fromisoformat(data['time'])-first).total_seconds(),data['value']))
    assert sum(n for _,n in points)==row['dropped_iterations'], 'Raw drop count differs from summary'
    buckets=defaultdict(int)
    for elapsed,count in points:buckets[math.floor(elapsed/5)*5]+=count
    return dict(first_drop_seconds=points[0][0],last_drop_seconds=points[-1][0],
                five_second_buckets=dict(sorted(buckets.items())),
                note='Elapsed from first retained k6 metric point; approximate ramp position, not a new measurement window.')


def unexpected_statuses(row,directory):
    if not row['unexpected_error_rate']:return []
    counts=defaultdict(int)
    with gzip.open(directory/row['id']/'measured/raw.ndjson.gz','rt') as stream:
        for line in stream:
            if '"type":"Point"' not in line or '"metric":"http_reqs"' not in line:continue
            data=json.loads(line)['data'];tags=data['tags']
            if tags['status'] not in (['201','409'] if row['workload']=='contention' else ['200','201']):
                counts[(tags['status'],tags['name'])]+=data['value']
    return [dict(status=status,route=route,count=count) for (status,route),count in sorted(counts.items())]


def comparisons(rows):
    groups=defaultdict(list)
    for r in rows:
        if not r['recovery'] and r['offered_iterations_s']==BASELINES[r['workload']]:
            groups[(r['workload'],r['replicas'])].append(r)
    result=[]
    for workload in FACTORS:
        a,b=groups[(workload,1)],groups[(workload,2)]
        if not a or not b:continue
        ar,br=[mean([r['achieved_rps'] for r in rs]) for rs in (a,b)]
        ap,bp=[mean([r['p95_ms'] for r in rs]) for rs in (a,b)]
        result.append(dict(workload=workload,rps_change_percent=(br/ar-1)*100,
            p95_reduction_percent=(1-bp/ap)*100,throughput_ratio=br/ar,
            throughput_ratio_per_app_cpu_budget=br/ar/2,
            note='Open-arrival rates cap throughput below saturation; ratio is not maximum-capacity scaling.'))
    return result


def capacity_points(rows):
    result=[]
    for workload in ['catalogue','availability','mixed','events']:
        for replicas in (1,2):
            group=[r for r in rows if r['workload']==workload and r['replicas']==replicas and not r['recovery']]
            by_rate=defaultdict(list)
            for r in group:by_rate[r['offered_iterations_s']].append(r)
            passing=[rate for rate,rs in by_rate.items() if all(r['target_passed'] for r in rs)]
            failed=[rate for rate,rs in by_rate.items() if not all(r['target_passed'] for r in rs)]
            result.append(dict(workload=workload,replicas=replicas,
                highest_all_repetitions_passing_iter_s=max(passing) if passing else None,
                failed_tested_iter_s=sorted(failed),
                highest_tested_rate_passed=bool(by_rate) and max(by_rate) in passing,
                note='30-second tested points only; failures may be non-monotonic. No exact sustained maximum inferred.'))
    return result


def fmt(value,digits=2):
    return 'N/A' if value is None else f'{value:.{digits}f}'


def table(headers,rows):
    return '\n'.join(['| '+' | '.join(headers)+' |','| '+' | '.join(['---']*len(headers))+' |']+
                     ['| '+' | '.join(str(v) for v in row)+' |' for row in rows])+'\n'


def save_figure(fig,directory,name):
    fig.savefig(directory/(name+'.png'))
    svg=directory/(name+'.svg')
    fig.savefig(svg)
    # Matplotlib emits trailing spaces in SVG path coordinates; newlines still
    # separate the coordinates after whitespace normalization.
    svg.write_text('\n'.join(line.rstrip() for line in svg.read_text().splitlines())+'\n')
    plt.close(fig)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory',type=Path)
    parser.add_argument('--no-charts',action='store_true',help='Generate lightweight tables/JSON while a run is active')
    parser.add_argument('--require-complete',action='store_true',help='Reject incomplete matrices when publishing final analysis')
    args=parser.parse_args();out=args.directory
    report=json.loads((out/'report.json').read_text())
    if args.require_complete:assert report['completed'], 'Planned matrix is incomplete'
    rows=[summarise(r) for r in report['runs']]
    diagnostics_path=out/'cache-diagnostics.json'
    diagnostics=json.loads(diagnostics_path.read_text())['runs'] if diagnostics_path.exists() else {}
    for raw,row in zip(report['runs'],rows):
        if row['recovery']:row['recovery_evidence']=recovery_evidence(raw,out)
        diagnostic=diagnostics.get(row['id'])
        row['cache_error_counts']=({operation:sum(e['count'] for e in diagnostic['errors'] if e['operation']==operation)
            for operation in ['get','put','evict','clear']} if diagnostic is not None else None)
        row['drop_timing']=drop_timing(row,out)
        row['unexpected_statuses']=unexpected_statuses(row,out)
    scaling=comparisons(rows);capacity=capacity_points(rows)
    (out/'analysis.json').write_text(json.dumps({'completed':report['completed'],'runs':rows,
        'scaling_comparisons':scaling,'capacity_points':capacity},indent=2)+'\n')
    baseline=defaultdict(list)
    for r in rows:
        if not r['recovery'] and r['offered_iterations_s']==BASELINES[r['workload']]:
            baseline[(r['workload'],r['replicas'])].append(r)
    text=['# Phase 12C measured tables\n',
          'Arithmetic means of per-run measurements; latency percentiles are not pooled. Counts are sums.\n',
          table(['Workload','JVMs','Runs','HTTP/s','p50 ms','p95 ms','p99 ms','Unexpected %','409s','Drops','Target passes'],[
            [w,n,len(rs),fmt(mean([r['achieved_rps'] for r in rs])),fmt(mean([r['p50_ms'] for r in rs])),
             fmt(mean([r['p95_ms'] for r in rs])),fmt(mean([r['p99_ms'] for r in rs])),
             fmt(mean([r['unexpected_error_rate']*100 for r in rs]),4),sum(r['conflicts'] for r in rs),
             sum(r['dropped_iterations'] for r in rs),f"{sum(r['target_passed'] for r in rs)}/{len(rs)}"]
            for (w,n),rs in baseline.items()])]
    text+=['\n## Every tested operating point\n',table(['Job','Offered iter/s','Actual HTTP/s','p50','p95','p99','Unexpected %','409 %','Max sampled VUs','Drops','Pass'],[
        [r['id'],r['offered_iterations_s'] or '100 VUs',fmt(r['achieved_rps']),fmt(r['p50_ms']),fmt(r['p95_ms']),fmt(r['p99_ms']),
         fmt(r['unexpected_error_rate']*100,4),fmt(r['conflict_rate']*100),r['sampled_active_vus_max'],r['dropped_iterations'],r['target_passed']] for r in rows])]
    text+=['\n## Cache, SQL, and sampled resources\n',table(['Job','SQL calls','SQL/HTTP','Cache hit %','App CPU %','PG CPU %','k6 CPU %','Heap MiB','Pool active/pending','DB locks'],[
        [r['id'],r['sql_calls'],fmt(r['sql_calls_per_request'],3),fmt(r['cache_hit_percent'],3),
         fmt(sum(v['cpu_percent'] for n,v in r['resource_means'].items() if n.startswith('app-'))),
         fmt(r['resource_means'].get('postgres',{}).get('cpu_percent')),fmt(r['resource_means'].get('k6',{}).get('cpu_percent')),
         fmt(r['heap_max_mib']),f"{fmt(r['pool_active_max'],0)}/{fmt(r['pool_pending_max'],0)}",r['db_lock_waiters_max']] for r in rows])]
    text+=['\n## Event publication and projections\n',table(['Job','Events','Published/s observed','Publish p95 ms','Notification p95 ms','Analytics p95 ms','Peak outbox','End outbox','Peak missing markers','Drain s'],[
        [r['id'],r['event_delays']['events'],fmt(r['publications_s_observed']),fmt(r['event_delays']['publication_ms']['p95']),
         fmt(r['event_delays']['notification_ms']['p95']),fmt(r['event_delays']['analytics_ms']['p95']),
         r['outbox_peak_sampled'],r['pending_end'],r['unprocessed_marker_backlog_peak_sampled'],fmt(r['drain_seconds'])] for r in rows if r['event_delays']['events']])]
    text+=['\n## JVM and infrastructure measurements\n',
        'Memory is the maximum sampled sum across active JVMs. GC values are counter deltas over the observation window. CPU 100% represents one logical core.\n',
        table(['Job','App RAM MiB','GC pauses','GC pause s','DB connections','Redis CPU %','Kafka CPU %','NGINX CPU %','k6 RAM MiB','Consumer committed lag'],[
            [r['id'],fmt(r['app_memory_peak_mib']),
             fmt(sum(v['pauses'] for v in r['gc'].values()),0) if all(v['pauses'] is not None for v in r['gc'].values()) else 'N/A',
             fmt(sum(v['pause_seconds'] for v in r['gc'].values()),3) if all(v['pause_seconds'] is not None for v in r['gc'].values()) else 'N/A',
             r['db_connections_max'],*(fmt(r['resource_means'].get(n,{}).get('cpu_percent')) for n in ['redis','kafka','nginx']),
             fmt(r['resource_means'].get('k6',{}).get('memory_max_mib')),r['committed_consumer_lag'] if r['committed_consumer_lag'] is not None else 'N/A'] for r in rows])]
    text+=['\n## One versus two JVMs at the middle offered rate\n',
        'A fixed offered rate caps measured throughput even when spare capacity exists. These ratios do not establish linear or maximum-capacity scaling.\n',
        table(['Workload','RPS change %','p95 reduction %','RPS ratio','Ratio / doubled app CPU budget'],[
            [r['workload'],fmt(r['rps_change_percent']),fmt(r['p95_reduction_percent']),fmt(r['throughput_ratio'],3),fmt(r['throughput_ratio_per_app_cpu_budget'],3)] for r in scaling])]
    text+=['\n## Tested capacity points\n',table(['Workload','JVMs','Highest rate passing every repetition (iter/s)','Failed tested rates (iter/s)','Highest offered rate passed'],[
        [r['workload'],r['replicas'],r['highest_all_repetitions_passing_iter_s'] or 'none',r['failed_tested_iter_s'],r['highest_tested_rate_passed']] for r in capacity]),
        '\nA passing 30-second operating point is not a long-term sustainable maximum.\n']
    text+=['\n## Route latency within write workloads\n',
        'Means of per-run route p95 values at the middle rate. These are separate from the all-HTTP percentile and include client-observed HTTP time only.\n',
        table(['Workload','JVMs','Catalogue p95 ms','Availability p95 ms','Hold p95 ms','Payment p95 ms','Confirmation p95 ms'],[
            [w,n,*(fmt(mean([r['route_latencies'].get('duration_'+route,{}).get('p(95)') for r in rs]))
                for route in ['catalogue','availability','hold','payment','confirmation'])]
            for (w,n),rs in baseline.items() if w in ['mixed','events']])]
    (out/'tables.md').write_text('\n'.join(text))
    if args.no_charts:
        print(f'Generated tables/JSON for {len(rows)} runs; completed={report["completed"]}')
        return
    plt.rcParams.update({'font.size':9,'axes.spines.top':False,'axes.spines.right':False,'figure.dpi':140})
    for metric,label,filename in [('achieved_rps','Achieved HTTP requests/s','throughput'),('p95_ms','HTTP p95 (ms)','latency')]:
        fig,axes=plt.subplots(2,2,figsize=(11,7),constrained_layout=True)
        for ax,w in zip(axes.flat,['catalogue','availability','mixed','events']):
            for n in (1,2):
                by_rate=defaultdict(list)
                for r in rows:
                    if r['workload']==w and r['replicas']==n and not r['recovery']:by_rate[r['offered_http_s']].append(r[metric])
                xs=sorted(by_rate);ys=[mean(by_rate[x]) for x in xs]
                ax.plot(xs,ys,'o-',color=COLORS[n],label=f'{n} JVM(s)')
                for r in rows:
                    if r['workload']==w and r['replicas']==n and not r['recovery'] and not r['target_passed']:
                        ax.scatter(r['offered_http_s'],r[metric],marker='x',color=COLORS[n],s=65)
            if metric=='p95_ms':ax.axhline({'catalogue':100,'availability':250,'mixed':1000,'events':1000}[w],color='gray',ls='--',lw=1)
            ax.set(title=w,xlabel='Offered HTTP requests/s (if all steps complete)',ylabel=label);ax.grid(alpha=.2);ax.legend()
        fig.suptitle('Phase 12C — '+label+'; x marks a failed acceptance target')
        save_figure(fig,out,filename)
    fig,axes=plt.subplots(1,2,figsize=(11,4),constrained_layout=True)
    for n in (1,2):
        es=[r for r in rows if r['workload']=='events' and r['replicas']==n and not r['recovery']]
        by_rate=defaultdict(list)
        for r in es:by_rate[r['offered_iterations_s']].append(r)
        xs=sorted(by_rate)
        axes[0].plot(xs,[mean([r['event_delays']['publication_ms']['p95'] for r in by_rate[x]]) for x in xs],
                     'o-',color=COLORS[n],label=f'{n} JVM(s): publication')
        axes[0].plot(xs,[mean([r['event_delays']['analytics_ms']['p95'] for r in by_rate[x]]) for x in xs],
                     's--',color=COLORS[n],label=f'{n} JVM(s): analytics marker')
        for raw in report['runs']:
            if raw['job'].get('fault') and raw['job']['replicas']==n:
                points=[s for s in raw['samples'] if 'database' in s]
                if points:
                    t0=datetime.datetime.fromisoformat(points[0]['utc'])
                    axes[1].plot([(datetime.datetime.fromisoformat(s['utc'])-t0).total_seconds() for s in points],
                         [s['database']['pending'] for s in points],'o-',color=COLORS[n],label=f'{n} JVM(s)')
    axes[0].axhline(2000,color='gray',ls=':');axes[0].set(xlabel='Offered bookings/s',ylabel='p95 delay (ms)',title='Database timestamp delays')
    axes[1].set(xlabel='Seconds since first resource sample',ylabel='Unpublished events',title='Kafka pause recovery (sampled)')
    for ax in axes:ax.grid(alpha=.2);ax.legend()
    save_figure(fig,out,'kafka')
    fig,ax=plt.subplots(figsize=(11,4),constrained_layout=True)
    labels=[];x=0
    for w in ['catalogue','availability','contention','mixed','events']:
        for n in (1,2):
            group=baseline[(w,n)]
            if not group:continue
            ys=[mean([r[key] for r in group]) for key in ['p50_ms','p95_ms','p99_ms']]
            ax.plot([x,x,x],ys,'-',color=COLORS[n],alpha=.6)
            for marker,y in zip(['o','s','^'],ys):ax.scatter(x,y,marker=marker,color=COLORS[n])
            labels.append(f'{w}\n{n} JVM(s)');x+=1
    ax.set_xticks(range(len(labels)),labels);ax.set_yscale('log');ax.set(ylabel='HTTP latency ms (log scale)',title='Middle-rate latency: circle p50, square p95, triangle p99; mean per-run percentiles')
    ax.grid(axis='y',alpha=.2);save_figure(fig,out,'percentiles')
    print(f'Generated analysis of {len(rows)} runs; full planned matrix completed={report["completed"]}')


if __name__=='__main__':main()
