#!/usr/bin/env python3
"""Run real two-JVM/NGINX correctness and failure tests in a fresh disposable stack.
Usage: python3 verification/multi_instance.py --output /tmp/phase11-e2e.json
Requires Docker Compose; the image builds from source. Run Maven verification
separately first. No developer database is touched.
"""
import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
import datetime
import json
from pathlib import Path
import threading
import time
import traceback
import uuid
from stack import Stack, run, wait_for


def concurrent(count, function):
    barrier = threading.Barrier(count + 1, timeout=15)
    def call(index):
        barrier.wait()
        return function(index)
    executor = ThreadPoolExecutor(max_workers=count)
    futures = [executor.submit(call, index) for index in range(count)]
    barrier.wait()
    return executor, futures


def collect(executor, futures):
    try: return [future.result(timeout=30) for future in futures]
    finally: executor.shutdown(wait=True, cancel_futures=True)


def paid_hold(s, user, seat):
    reservation = s.hold(user, seat)
    s.sql(f"""CREATE FUNCTION tbooker.test_reject_booking() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN IF NEW.show_seat_id={seat} THEN RAISE EXCEPTION 'test allocation failure' USING ERRCODE='40001'; END IF; RETURN NEW; END $$;
        CREATE TRIGGER test_reject_booking BEFORE INSERT ON tbooker.bookings FOR EACH ROW EXECUTE FUNCTION tbooker.test_reject_booking();""")
    try:
        payment = s.pay(user, reservation)
        assert payment['status']==201 and payment['body']['status']=='SUCCESS', payment
        assert payment['body']['confirmationStatus']=='PENDING', payment
    finally: s.sql('DROP TRIGGER test_reject_booking ON tbooker.bookings; DROP FUNCTION tbooker.test_reject_booking();')
    return reservation


def routing_auth_metrics(s):
    email = uuid.uuid4().hex+'@example.test'
    password = uuid.uuid4().hex
    assert s.http('/api/auth/register','POST',dict(name='Multi JVM',email=email,password=password))['status']==201
    login = s.http('/api/auth/login','POST',dict(email=email,password=password))
    assert login['status']==200
    token = login['body']['accessToken']
    calls = [s.http('/api/bookings',token=token) for _ in range(20)]
    assert all(x['status']==200 for x in calls)
    assert len({x['upstream'] for x in calls})==2
    assert s.http('/actuator/metrics')['status']==401
    assert s.http('/actuator/metrics',user=s.users[0])['status']==403
    for name in ('app-a','app-b'):
        metric = s.metric(name,'jvm.memory.used')
        tags = {x['tag']:x['values'] for x in metric['availableTags']}
        assert tags['instance']==[name], tags
    published_ports = json.loads(run('docker','inspect',*s.containers.values()))
    for item in published_ports:
        if item['Id'] != s.containers['nginx']:
            assert not any(item['NetworkSettings']['Ports'].values()), item['Name']
    return {'upstreams':dict(Counter(x['upstream'] for x in calls)), 'internal_host_ports':0}


def hundred_contenders(s):
    seat = s.seat()
    executor = None
    try:
        with s.locked(f'SELECT id FROM tbooker.show_seats WHERE id={seat} FOR UPDATE'):
            executor, futures = concurrent(100, lambda i:s.http(f'/api/shows/{s.show}/reservations','POST',
                                                {'showSeatId':seat},s.users[i]))
            def waiters():
                data = json.loads(s.sql("""SELECT COALESCE(json_object_agg(application_name,n),'{}') FROM
                    (SELECT application_name,COUNT(*) n FROM pg_stat_activity
                     WHERE application_name IN ('app-a','app-b') AND wait_event_type='Lock'
                     AND query ILIKE '%show_seats%FOR UPDATE%' GROUP BY application_name) q;"""))
                return data if sum(data.values())==100 and len(data)==2 else False
            observed = wait_for(waiters,'100 actual PostgreSQL lock waiters across both JVMs',12)
        responses = collect(executor, futures); executor = None
    finally:
        if executor: executor.shutdown(wait=True,cancel_futures=True)
    counts = Counter(r['status'] for r in responses)
    assert counts=={201:1,409:99}, counts
    assert len({r['upstream'] for r in responses})==2
    winner = next(i for i,r in enumerate(responses) if r['status']==201)
    reservation = responses[winner]['body']['id']
    user = s.users[winner]
    assert s.sql(f"SELECT COUNT(*) FROM tbooker.reservations WHERE show_seat_id={seat} AND status='HELD';")=='1'
    assert s.pay(user,reservation)['body']['confirmationStatus']=='CONFIRMED'
    confirmations = collect(*concurrent(20,lambda _:s.confirm(user,reservation)))
    assert all(r['status']==200 for r in confirmations)
    assert len({r['body']['id'] for r in confirmations})==1
    assert s.sql(f'SELECT COUNT(*) FROM tbooker.bookings WHERE show_seat_id={seat};')=='1'
    assert s.sql(f'SELECT status FROM tbooker.show_seats WHERE id={seat};')=='BOOKED'
    return {'requests':100,'statuses':dict(counts),'database_waiters':observed,
            'upstreams':dict(Counter(r['upstream'] for r in responses)),'repeat_confirmations':20,'bookings':1}


def expiration_cleanup_and_reclaim(s):
    seat = s.seat(); owner=s.users[0]
    reservation=s.hold(owner,seat)
    s.sql(f"UPDATE tbooker.reservations SET created_at=clock_timestamp()-interval '6 minutes',expires_at=clock_timestamp()-interval '1 minute' WHERE id={reservation};")
    assert s.http(f'/api/reservations/{reservation}',user=owner)['body']['status']=='EXPIRED'
    other=s.hold(s.users[1],seat)
    assert s.confirm(owner,reservation)['status']==409
    assert s.sql(f"SELECT COUNT(*) FROM tbooker.reservations WHERE show_seat_id={seat} AND status='HELD';")=='1'
    s.sql(f"UPDATE tbooker.reservations SET created_at=clock_timestamp()-interval '6 minutes',expires_at=clock_timestamp()-interval '1 minute' WHERE id={other};")
    wait_for(lambda:s.sql(f'SELECT status FROM tbooker.show_seats WHERE id={seat};')=='AVAILABLE','scheduled expiry')
    assert s.sql(f"SELECT COUNT(*) FROM tbooker.reservations WHERE show_seat_id={seat} AND status='EXPIRED';")=='2'
    return {'expired_history_rows':2,'inventory':'AVAILABLE'}


def cancel_then_other_owner(s):
    seat=s.seat(); first=s.hold(s.users[0],seat)
    assert s.http(f'/api/reservations/{first}','DELETE',user=s.users[0])['status']==204
    second=s.hold(s.users[1],seat)
    assert s.http(f'/api/reservations/{first}','DELETE',user=s.users[0])['status']==204
    assert s.confirm(s.users[0],second)['status']==404
    assert s.pay(s.users[1],second)['body']['confirmationStatus']=='CONFIRMED'
    assert s.sql(f'SELECT user_id FROM tbooker.bookings WHERE show_seat_id={seat};')==str(s.users[1])
    return {'old_cancel_retry':204,'foreign_confirmation':404,'new_owner_booked':True}


def confirmation_waits_past_expiry(s):
    seat=s.seat(); user=s.users[0]; reservation=paid_hold(s,user,seat)
    s.sql(f"UPDATE tbooker.reservations SET expires_at=clock_timestamp()+interval '2 seconds' WHERE id={reservation};")
    executor=None
    try:
        with s.locked(f'SELECT id FROM tbooker.show_seats WHERE id={seat} FOR UPDATE'):
            executor,futures=concurrent(1,lambda _:s.confirm(user,reservation))
            wait_for(lambda:int(s.sql("SELECT COUNT(*) FROM pg_stat_activity WHERE application_name IN ('app-a','app-b') AND wait_event_type='Lock' AND query ILIKE '%show_seats%FOR UPDATE%';"))>=1,'confirmation blocked')
            wait_for(lambda:s.sql(f'SELECT expires_at<=clock_timestamp() FROM tbooker.reservations WHERE id={reservation};')=='t','deadline')
        response=collect(executor,futures)[0];executor=None
    finally:
        if executor:executor.shutdown(wait=True,cancel_futures=True)
    assert response['status']==409,response
    assert s.sql(f'SELECT COUNT(*) FROM tbooker.bookings WHERE show_seat_id={seat};')=='0'
    wait_for(lambda:s.sql(f'SELECT status FROM tbooker.show_seats WHERE id={seat};')=='AVAILABLE','cleanup after failed confirmation')
    return {'confirmation':409,'bookings':0}


def confirmation_commits_after_deadline(s):
    seat=s.seat(); user=s.users[0]; reservation=paid_hold(s,user,seat)
    s.sql(f"""CREATE FUNCTION tbooker.test_slow_booking() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
        IF NEW.show_seat_id={seat} THEN PERFORM pg_sleep(4); END IF; RETURN NEW; END $$;
        CREATE TRIGGER test_slow_booking AFTER INSERT ON tbooker.bookings FOR EACH ROW EXECUTE FUNCTION tbooker.test_slow_booking();
        UPDATE tbooker.reservations SET expires_at=clock_timestamp()+interval '2 seconds' WHERE id={reservation};""")
    try: response=s.confirm(user,reservation)
    finally:s.sql('DROP TRIGGER test_slow_booking ON tbooker.bookings; DROP FUNCTION tbooker.test_slow_booking();')
    assert response['status']==200,response
    assert s.sql(f"SELECT b.created_at<r.expires_at AND r.expires_at<clock_timestamp() AND r.status='CONFIRMED' FROM tbooker.reservations r JOIN tbooker.bookings b ON b.id=r.booking_id WHERE r.id={reservation};")=='t'
    time.sleep(1.2)
    assert s.sql(f'SELECT status FROM tbooker.show_seats WHERE id={seat};')=='BOOKED'
    return {'confirmed_before_deadline':True,'committed_after_deadline':True,'cleanup_did_not_release':True}


def concurrent_payment_results(s):
    seat=s.seat(); user=s.users[0]; reservation=s.hold(user,seat);key=uuid.uuid4().hex
    initial=s.pay(user,reservation,'CARD',key)
    assert initial['status']==201 and initial['body']['status']=='PENDING',initial
    s.sql(f"UPDATE tbooker.mock_gateway_receipts SET status='SUCCESS',version=version+1 WHERE request_id=(SELECT gateway_request_id FROM tbooker.payments WHERE reservation_id={reservation});")
    results=collect(*concurrent(20,lambda _:s.pay(user,reservation,'CARD',key)))
    assert all(r['status']==200 and r['body']['confirmationStatus']=='CONFIRMED' for r in results)
    assert len({r['upstream'] for r in results})==2
    assert s.sql(f'SELECT COUNT(*) FROM tbooker.payments WHERE reservation_id={reservation};')=='1'
    assert s.sql(f'SELECT COUNT(*) FROM tbooker.bookings WHERE show_seat_id={seat};')=='1'
    return {'concurrent_verified_result_retries':20,'payments':1,'bookings':1}


def redis_outage(s):
    s.http('/api/shows')
    run('docker','pause',s.containers['redis'])
    try:
        calls=collect(*concurrent(12,lambda _:s.http('/api/shows')))
        assert all(r['status']==200 for r in calls)
        assert s.http('/actuator/health/readiness')['status']==200
        seat=s.seat();reservation=s.hold(s.users[0],seat)
        assert s.pay(s.users[0],reservation)['body']['confirmationStatus']=='CONFIRMED'
        assert s.sql(f'SELECT status FROM tbooker.show_seats WHERE id={seat};')=='BOOKED'
    finally:run('docker','unpause',s.containers['redis'])
    return {'fallback_requests':12,'booking_confirmed':True}


def kafka_outage(s):
    wait_for(s.published,'prior events drained')
    run('docker','pause',s.containers['kafka'])
    try:
        seat=s.seat();reservation=s.hold(s.users[0],seat)
        assert s.pay(s.users[0],reservation)['body']['confirmationStatus']=='CONFIRMED'
        assert s.http('/actuator/health/readiness')['status']==200
        assert s.http('/actuator/health/messaging')['status']==503
        assert s.sql(f"SELECT COUNT(*) FROM tbooker.outbox_events e JOIN tbooker.bookings b ON b.id=e.aggregate_id WHERE b.show_seat_id={seat} AND e.status<>'PUBLISHED';")=='1'
        time.sleep(5.5)
        assert s.sql(f'SELECT COUNT(*) FROM tbooker.bookings WHERE show_seat_id={seat};')=='1'
    finally:run('docker','unpause',s.containers['kafka'])
    wait_for(s.published,'Kafka publication and projections recover',90)
    return {'booking_committed_during_outage':True,'backlog_recovered':True}


def backend_crash_restart_active_hold(s):
    seat=s.seat();reservation=s.hold(s.users[0],seat)
    payment_hold=s.hold(s.users[1],s.seat());payment_key=uuid.uuid4().hex
    since=datetime.datetime.now(datetime.timezone.utc).isoformat()
    run('docker','kill','--signal','KILL',s.containers['app-b'])
    try:
        stopped=[s.http('/api/shows') for _ in range(20)]
        # Connecting to a killed peer can time out (504) or be refused (502).
        # With retries disabled, that request fails; subsequent traffic uses A.
        allowed=(200,502,503,504)
        assert all(r['status'] in allowed for r in stopped), dict(Counter(r['status'] for r in stopped))
        assert all(r['status']==200 for r in stopped[-3:]), stopped[-3:]
        assert all(r['seconds']<25 for r in stopped)
        time.sleep(5.2)  # Exercise another passive probe after fail_timeout.
        writes=[s.pay(s.users[1],payment_hold,key=payment_key) for _ in range(6)]
        assert all(r['status'] in (200,201,502,503,504) for r in writes), writes
        assert any(r['status'] in (200,201) for r in writes)
        assert all(',' not in r['upstream'] for r in stopped+writes), 'NGINX replayed a request'
        assert s.sql(f'SELECT COUNT(*) FROM tbooker.payments WHERE reservation_id={payment_hold};')=='1'
        assert s.sql(f'SELECT COUNT(*) FROM tbooker.bookings b JOIN tbooker.reservations r ON r.booking_id=b.id WHERE r.id={payment_hold};')=='1'
        logs=s.compose('logs','--no-color','--since',since,'nginx')
        evidence=[line for line in logs.splitlines() if 'upstream timed out' in line or 'connect() failed' in line
                  or 'prematurely closed' in line or 'no live upstreams' in line]
        if any(r['status']>=500 for r in stopped+writes): assert evidence, logs[-3000:]
        s.compose('start','app-b')
        restarting=[];deadline=time.monotonic()+120
        while time.monotonic()<deadline:
            restarting.append(s.http('/api/shows'))
            if s.health('app-b'):break
            time.sleep(.15)
        assert s.health('app-b')
        assert all(r['status'] in allowed for r in restarting), dict(Counter(r['status'] for r in restarting))
        assert all(',' not in r['upstream'] for r in restarting)
        wait_for(lambda:len({s.http('/api/shows')['upstream'] for _ in range(12)})==2,'both backends rejoin')
        assert s.http(f'/api/reservations/{reservation}',user=s.users[0])['body']['status']=='HELD'
        assert s.pay(s.users[0],reservation)['body']['confirmationStatus']=='CONFIRMED'
    finally:s.compose('start','app-b');s.ready('app-b')
    return {'while_stopped':dict(Counter(r['status'] for r in stopped)),
            'payment_writes':dict(Counter(r['status'] for r in writes)),
            'while_restarting':dict(Counter(r['status'] for r in restarting)),
            'nginx_failure_evidence':evidence,'automatic_replays':0,'active_hold_preserved':True}


def payment_interrupted_without_proxy_replay(s):
    seat=s.seat();user=s.users[0];reservation=s.hold(user,seat);key=uuid.uuid4().hex
    # Stop a real write AFTER dispatch, before its first durable payment insert.
    s.sql(f"""CREATE FUNCTION tbooker.test_block_payment() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
        IF NEW.reservation_id={reservation} THEN PERFORM pg_advisory_xact_lock(910012); END IF;
        RETURN NEW; END $$;
        CREATE TRIGGER test_block_payment BEFORE INSERT ON tbooker.payments FOR EACH ROW EXECUTE FUNCTION tbooker.test_block_payment();""")
    victim=None;executor=None
    try:
        with s.locked('SELECT pg_advisory_xact_lock(910012)'):
            executor,futures=concurrent(1,lambda _:s.pay(user,reservation,key=key))
            victim=wait_for(lambda:s.sql("SELECT application_name FROM pg_stat_activity WHERE wait_event='advisory' AND application_name IN ('app-a','app-b') AND query ILIKE '%payments%' LIMIT 1;"),'payment dispatched and blocked before insert')
            run('docker','kill','--signal','KILL',s.containers[victim])
            response=collect(executor,futures)[0];executor=None
            assert response['status'] in (502,504),response
            assert response['upstream'] and ',' not in response['upstream'],response
        # Releasing the lock lets PostgreSQL notice the lost writer and roll back.
        wait_for(lambda:s.sql("SELECT COUNT(*) FROM pg_stat_activity WHERE query ILIKE '%insert into tbooker.payments%' AND application_name IN ('app-a','app-b');")=='0','dead payment transaction released')
        assert s.sql(f'SELECT COUNT(*) FROM tbooker.payments WHERE reservation_id={reservation};')=='0'
        assert s.sql(f'SELECT COUNT(*) FROM tbooker.bookings WHERE show_seat_id={seat};')=='0'
    finally:
        if executor:executor.shutdown(wait=True,cancel_futures=True)
        s.sql('DROP TRIGGER test_block_payment ON tbooker.payments; DROP FUNCTION tbooker.test_block_payment();')
        if victim:s.compose('start',victim);s.ready(victim)
    wait_for(lambda:all(s.http('/api/shows')['status']==200 for _ in range(4)),'routing after restart')
    retry=s.pay(user,reservation,key=key)
    assert retry['status']==201 and retry['body']['confirmationStatus']=='CONFIRMED',retry
    repeated=s.pay(user,reservation,key=key)
    assert repeated['status']==200 and repeated['body']['id']==retry['body']['id'],repeated
    return {'killed_during_payment':victim,'failed_post_status':response['status'],
            'upstream_attempt':response['upstream'],'payments_before_explicit_retry':0,
            'explicit_retry_status':retry['status'],'same_key_repeat_status':repeated['status']}


def publisher_crash_after_ack(s):
    wait_for(s.published,'prior projections drained')
    seat=s.seat();user=s.users[0];reservation=s.hold(user,seat)
    s.sql(f"""CREATE FUNCTION tbooker.test_block_ack() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
        IF NEW.status='PUBLISHED' AND (NEW.payload->>'showSeatId')::bigint={seat} THEN
        PERFORM pg_advisory_xact_lock(910011); END IF; RETURN NEW; END $$;
        CREATE TRIGGER test_block_ack BEFORE UPDATE ON tbooker.outbox_events FOR EACH ROW EXECUTE FUNCTION tbooker.test_block_ack();""")
    victim=None
    try:
        with s.locked('SELECT pg_advisory_xact_lock(910011)'):
            assert s.pay(user,reservation)['body']['confirmationStatus']=='CONFIRMED'
            victim=wait_for(lambda:s.sql("SELECT application_name FROM pg_stat_activity WHERE wait_event='advisory' AND query ILIKE '%outbox_events%PUBLISHED%' LIMIT 1;"),'publisher blocked AFTER broker ack')
            assert victim in ('app-a','app-b'),victim
            run('docker','kill','--signal','KILL',s.containers[victim])
        survivor='app-b' if victim=='app-a' else 'app-a'
        wait_for(s.published,'survivor reclaims acknowledged event',90)
        assert s.sql(f"SELECT attempts>=2 FROM tbooker.outbox_events e JOIN tbooker.bookings b ON b.id=e.aggregate_id WHERE b.show_seat_id={seat};")=='t'
        assert s.sql(f'SELECT COUNT(*) FROM tbooker.booking_notifications n JOIN tbooker.bookings b ON b.id=n.booking_id WHERE b.show_seat_id={seat};')=='1'
        assert s.sql(f'SELECT COUNT(*) FROM tbooker.processed_events p JOIN tbooker.bookings b ON b.id=p.booking_id WHERE b.show_seat_id={seat};')=='2'
    finally:
        s.sql('DROP TRIGGER test_block_ack ON tbooker.outbox_events; DROP FUNCTION tbooker.test_block_ack();')
        if victim:s.compose('start',victim);s.ready(victim)
    return {'killed_after_ack':victim,'recovering_jvm':survivor,'notifications':1,'markers':2,'actual_SIGKILL':True}


def consumer_group_members(s):
    output={};observed={}
    hosts={json.loads(run('docker','inspect',s.containers[name]))[0]['NetworkSettings']['Networks'][s.project+'_default']['IPAddress']
           for name in ('app-a','app-b')}
    for group in ('tbooker-notifications-v1','tbooker-analytics-v1'):
        def members():
            text=s.docker('kafka','/opt/kafka/bin/kafka-consumer-groups.sh','--bootstrap-server','localhost:19092',
                          '--describe','--group',group,'--members','--verbose')
            observed[group]=text
            rows=[line.split() for line in text.splitlines() if line.startswith(group+' ')]
            if len(rows)!=2 or {row[2].lstrip('/') for row in rows}!=hosts:return False
            if sorted(int(row[4]) for row in rows)!=[1,2]:return False
            # Kafka 3.9 omits the single topic name in --members assignment output.
            # --offsets identifies the actual topic, partition, consumer and host.
            offsets=s.docker('kafka','/opt/kafka/bin/kafka-consumer-groups.sh','--bootstrap-server','localhost:19092',
                          '--describe','--group',group,'--offsets')
            assigned=[line.split() for line in offsets.splitlines() if line.startswith(group+' ')]
            if len(assigned)!=3 or {row[1] for row in assigned}!={'tbooker.booking-confirmed.v1'}:return False
            if {int(row[2]) for row in assigned}!={0,1,2}:return False
            if {row[6] for row in assigned}!={row[1] for row in rows}:return False
            return {'members':len(rows),'hosts':sorted(hosts),'partitions':[0,1,2],
                    'member_output':text,'offset_output':offsets}
        try:output[group]=wait_for(members,'two JVMs assigned all three topic partitions',90)
        except AssertionError as error:raise AssertionError(observed) from error
    wait_for(s.published,'both independent projections caught up')
    return output


CASES=[routing_auth_metrics,hundred_contenders,expiration_cleanup_and_reclaim,cancel_then_other_owner,
       confirmation_waits_past_expiry,confirmation_commits_after_deadline,concurrent_payment_results,
       redis_outage,kafka_outage,backend_crash_restart_active_hold,payment_interrupted_without_proxy_replay,
       publisher_crash_after_ack,consumer_group_members]


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--case',action='append',choices=[f.__name__ for f in CASES])
    args=parser.parse_args()
    if args.output.exists():parser.error('Choose a new output path; previous evidence is preserved.')
    results=[]
    with Stack(pool=60) as s:
        print('Fresh two-JVM stack ready; all traffic tests use NGINX.',flush=True)
        for case in CASES:
            if args.case and case.__name__ not in args.case:continue
            start=time.monotonic()
            try:
                # Each case starts with both real JVMs available, even after a failed case.
                for name in ('app-a','app-b'):
                    if not s.health(name):
                        s.compose('start',name)
                        s.ready(name)
                details=case(s)
                record=dict(case=case.__name__,status='passed',details=details)
            except Exception as error:
                record=dict(case=case.__name__,status='failed',error=str(error),traceback=traceback.format_exc())
            record['seconds']=round(time.monotonic()-start,3);results.append(record)
            print(case.__name__+': '+record['status'],flush=True)
            if record['status']=='failed':print(record['error'][:1200],flush=True)
        report=dict(phase=11,utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                    passed=sum(r['status']=='passed' for r in results),failed=sum(r['status']=='failed' for r in results),
                    skipped=0,selected_cases=args.case,tests=results,pool_per_instance=60)
        args.output.parent.mkdir(parents=True,exist_ok=True)
        args.output.write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({k:report[k] for k in ['passed','failed','skipped']}),flush=True)
    raise SystemExit(1 if report['failed'] else 0)


if __name__=='__main__':main()
