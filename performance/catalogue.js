import http from 'k6/http';
import { check } from 'k6';

// Closed workload: each virtual user makes the next read after the previous response.
export const options = {
  vus: Number(__ENV.VUS || 10),
  duration: __ENV.DURATION || '30s',
  thresholds: { http_req_failed: ['rate==0'], checks: ['rate==1'] },
};

export default function () {
  const response = http.get(`${__ENV.BASE_URL || 'http://localhost:8080'}/api/shows`);
  check(response, {
    'HTTP 200': (r) => r.status === 200,
    'catalogue DTO array': (r) => {
      try {
        const body = r.json();
        return Array.isArray(body) && body.length === Number(__ENV.SHOW_COUNT || 100)
          && body[0].movieTitle !== undefined && body[0].startTime.endsWith('Z');
      } catch (_) { return false; }
    },
  });
}

export function handleSummary(data) {
  return { [__ENV.SUMMARY_PATH || 'summary.json']: JSON.stringify(data, null, 2) };
}
