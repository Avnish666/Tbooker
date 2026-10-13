import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter, Rate } from 'k6/metrics';

const fixture = JSON.parse(open(__ENV.FIXTURE_PATH || '/fixtures/data.json'));
const workload = __ENV.WORKLOAD || 'catalogue';
const base = __ENV.BASE_URL || 'http://nginx:8080';
const unexpected = new Rate('unexpected_failures');
const conflicts = new Counter('business_conflicts');
const confirmed = new Counter('confirmed_bookings');
export const options = {
  vus: Number(__ENV.VUS || 20), duration: __ENV.DURATION || '30s', gracefulStop: '30s',
  thresholds: { unexpected_failures: ['rate==0'], checks: ['rate==1'] },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'min', 'max'],
};
// 409 is an expected business response only where `expect` below explicitly allows it.
http.setResponseCallback(http.expectedStatuses(200, 201, 204, 409));
function expect(response, codes) {
  const ok = codes.includes(response.status);
  unexpected.add(!ok);
  check(response, { 'expected HTTP status': () => ok });
  if (response.status === 409) conflicts.add(1);
  return ok;
}
function catalogue() {
  const r = http.get(`${base}/api/shows`, { tags: { name: 'GET /api/shows' } });
  if (expect(r, [200])) check(r, { '100 catalogue DTOs': x => x.json().length === 100 });
}
export default function () {
  if (workload === 'catalogue') { catalogue(); return; }
  const user = fixture.users[(__VU - 1) % fixture.users.length];
  const headers = { Authorization: `Bearer ${user.token}`, 'Content-Type': 'application/json' };
  const iteration = exec.scenario.iterationInTest;
  const seat = workload === 'contention' ? fixture.seats[0] : fixture.seats[iteration];
  if (!seat) { unexpected.add(true); throw new Error('Fixture seat inventory exhausted'); }
  if (workload === 'mixed') for (let i = 0; i < 8; i++) catalogue();
  const hold = http.post(`${base}/api/shows/${fixture.show}/reservations`, JSON.stringify({ showSeatId: seat }),
    { headers, tags: { name: 'POST /api/shows/:id/reservations' } });
  if (!expect(hold, workload === 'contention' ? [201, 409] : [201])) return;
  if (workload === 'contention') return;
  const reservation = hold.json().id;
  const paid = http.post(`${base}/api/reservations/${reservation}/payments`, JSON.stringify({ method: 'UPI' }),
    { headers: { ...headers, 'Idempotency-Key': `bench-${__ENV.RUN_ID}-${iteration}` },
      tags: { name: 'POST /api/reservations/:id/payments' } });
  if (expect(paid, [201])) {
    const ok = paid.json().confirmationStatus === 'CONFIRMED';
    check(paid, { 'payment confirms booking': () => ok });
    unexpected.add(!ok);
    if (ok) confirmed.add(1);
  }
}
export function handleSummary(data) {
  return { [__ENV.SUMMARY_PATH || '/results/summary.json']: JSON.stringify(data, null, 2) };
}
