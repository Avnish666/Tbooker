import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';
import { Counter, Rate, Trend } from 'k6/metrics';

const users = new SharedArray('users', () => JSON.parse(open('/fixtures/data.json')).users);
const inventory = new SharedArray('inventory', () => JSON.parse(open('/fixtures/data.json')).inventory);
const meta = JSON.parse(open('/fixtures/meta.json'));
const base = 'http://nginx:8080';
const kind = __ENV.WORKLOAD;
const offset = Number(__ENV.OFFSET || 1000);
const duration = __ENV.DURATION || '30s';
const rate = Number(__ENV.RATE || 10);
const failures = new Rate('unexpected_failures');
const conflicts = new Counter('business_conflicts');
const holds = new Counter('successful_holds');
const bookings = new Counter('confirmed_bookings');
const fixtureFailures = new Counter('fixture_exhaustion');
const durations = Object.fromEntries(['catalogue', 'availability', 'hold', 'payment', 'confirmation']
  .map(k => [k, new Trend(`duration_${k}`, true)]));
const targets = { catalogue: [100, 250], availability: [250, 500], contention: [2500, 5000], mixed: [1000, 2000], events: [1000, 2000] };
export const options = {
  scenarios: { load: kind === 'contention'
    ? { executor: 'constant-vus', vus: 100, duration, gracefulStop: '10s' }
    : { executor: 'constant-arrival-rate', rate, timeUnit: '1s', duration,
        preAllocatedVUs: Number(__ENV.PREALLOCATED || 40), maxVUs: 200, gracefulStop: '10s' } },
  thresholds: {
    unexpected_failures: ['rate<0.001'], checks: ['rate==1'], fixture_exhaustion: ['count==0'],
    ...(kind === 'contention' ? {} : { dropped_iterations: ['count==0'] }),
    http_req_duration: [`p(95)<=${targets[kind][0]}`, `p(99)<=${targets[kind][1]}`],
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'min', 'max'],
  systemTags: ['status', 'method', 'name', 'scenario', 'expected_response'],
};
http.setResponseCallback(kind === 'contention' ? http.expectedStatuses(201, 409) : http.expectedStatuses(200, 201));
function observed(r, expected, type) {
  failures.add(!expected.includes(r.status));
  conflicts.add(r.status === 409 ? 1 : 0);
  durations[type].add(r.timings.duration);
  return check(r, { 'expected HTTP status': x => expected.includes(x.status) });
}
function catalogue(i) {
  const n = Math.floor(i / 6);
  const paths = [ '/api/movies', `/api/movies/${meta.movies[n % meta.movies.length]}`,
    '/api/theatres', `/api/theatres/${meta.theatres[n % meta.theatres.length]}`,
    '/api/shows', `/api/shows/${meta.shows[n % meta.shows.length]}` ];
  const names = ['movies', 'movie', 'theatres', 'theatre', 'shows', 'show'];
  const index = i % 6;
  const r = http.get(base + paths[index], { tags: { name: names[index] }, timeout: '10s' });
  if (observed(r, [200], 'catalogue')) {
    const body = r.json();
    check(body, { 'catalogue DTO shape/count': x => index % 2 === 0
      ? Array.isArray(x) && x.length === [10, 3, 100][index / 2] : x.id > 0 });
  }
}
function availability(show, heldSeat) {
  const r = http.get(`${base}/api/shows/${show}/seats`, { tags: { name: 'seat availability' }, timeout: '10s' });
  if (observed(r, [200], 'availability')) {
    const seats = r.json();
    check(seats, { '80 seats for requested show': x => x.length === 80 && x.every(s => s.showId === show),
      'fresh hold visible': x => !heldSeat || x.some(s => s.id === heldSeat && s.status === 'HELD') });
  }
}
export default function () {
  const iteration = exec.scenario.iterationInTest;
  fixtureFailures.add(0);
  if (kind === 'catalogue') { catalogue(iteration); return; }
  if (kind === 'availability') { availability(meta.shows[iteration % meta.shows.length]); return; }
  const index = offset + (kind === 'contention' ? 0 : iteration);
  const seat = inventory[index];
  const user = users[kind === 'contention' ? __VU - 1 : index];
  if (!seat || !user) { fixtureFailures.add(1); throw new Error('Unique fixture exhausted'); }
  const headers = { Authorization: `Bearer ${user.token}`, 'Content-Type': 'application/json' };
  if (kind === 'mixed') {
    for (let n = 0; n < 4; n++) catalogue(iteration * 4 + n);
    availability(seat.show);
  }
  const hold = http.post(`${base}/api/shows/${seat.show}/reservations`, JSON.stringify({ showSeatId: seat.id }),
    { headers, tags: { name: 'create reservation' }, timeout: '10s' });
  if (!observed(hold, kind === 'contention' ? [201, 409] : [201], 'hold')) return;
  if (hold.status === 201) holds.add(1);
  if (kind === 'contention') return;
  const reservation = hold.json().id;
  if (kind === 'mixed') availability(seat.show, seat.id);
  const payment = http.post(`${base}/api/reservations/${reservation}/payments`, JSON.stringify({ method: 'UPI' }),
    { headers: { ...headers, 'Idempotency-Key': `${__ENV.RUN_ID}-${iteration}` },
      tags: { name: 'initiate payment' }, timeout: '10s' });
  if (!observed(payment, [201], 'payment')) return;
  const paid = payment.json();
  if (!check(paid, { 'verified payment confirms booking': x => x.status === 'SUCCESS' && x.confirmationStatus === 'CONFIRMED' && x.bookingId > 0 })) return;
  const confirmation = http.post(`${base}/api/reservations/${reservation}/confirmation`, null,
    { headers, tags: { name: 'confirm reservation' }, timeout: '10s' });
  if (observed(confirmation, [200], 'confirmation') &&
      check(confirmation, { 'confirmation recovers same booking': r => r.json().id === paid.bookingId })) bookings.add(1);
}
export function handleSummary(data) {
  return { '/results/summary.json': JSON.stringify(data, null, 2) };
}
