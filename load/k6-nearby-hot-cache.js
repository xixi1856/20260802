import http from 'k6/http';
import { check, fail } from 'k6';
import { BASE_URL, users } from './lib/common.js';

const rate = Number(__ENV.RATE || 200);
const duration = __ENV.DURATION || '30s';

export const options = {
  scenarios: {
    repeatedNearby: {
      executor: 'constant-arrival-rate',
      rate,
      timeUnit: '1s',
      duration,
      preAllocatedVUs: 30,
      maxVUs: 200,
      exec: 'nearby',
    },
  },
  thresholds: {
    'http_req_failed{endpoint:nearby-hot-cache}': ['rate<0.01'],
    'http_req_duration{endpoint:nearby-hot-cache}': ['p(95)<100', 'p(99)<300'],
    checks: ['rate>0.99'],
    dropped_iterations: ['count==0'],
  },
};

export function setup() {
  const user = users[0];
  const response = http.post(
    `${BASE_URL}/auth/login`,
    JSON.stringify({ email: user.email, password: user.password }),
    { headers: { 'Content-Type': 'application/json' }, tags: { endpoint: 'setup-login' } },
  );
  if (response.status !== 200 || !response.json('accessToken')) {
    fail(`Setup login failed with HTTP ${response.status}`);
  }
  return { accessToken: response.json('accessToken') };
}

export function nearby(data) {
  const response = http.get(
    `${BASE_URL}/accessibility-issues?longitude=116.397128&latitude=39.916527&radiusMeters=500&limit=20`,
    {
      headers: { Authorization: `Bearer ${data.accessToken}` },
      tags: { endpoint: 'nearby-hot-cache' },
    },
  );
  check(response, { 'cached nearby returned 200': (value) => value.status === 200 });
}
