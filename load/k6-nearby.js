import http from 'k6/http';
import { check } from 'k6';
import { authParams, BASE_URL, loginAllUsers } from './lib/common.js';

const maxRate = Number(__ENV.MAX_RATE || 100);

export const options = {
  scenarios: {
    nearby: {
      executor: 'ramping-arrival-rate',
      startRate: Math.max(1, Math.round(maxRate * 0.1)),
      timeUnit: '1s',
      preAllocatedVUs: 40,
      maxVUs: 300,
      stages: [
        { target: Math.round(maxRate * 0.2), duration: '30s' },
        { target: Math.round(maxRate * 0.5), duration: '45s' },
        { target: maxRate, duration: '60s' },
        { target: 0, duration: '15s' },
      ],
      exec: 'nearby',
    },
  },
  thresholds: {
    'http_req_failed{endpoint:nearby-issues}': ['rate<0.01'],
    'http_req_duration{endpoint:nearby-issues}': ['p(95)<300', 'p(99)<800'],
    checks: ['rate>0.99'],
    dropped_iterations: ['count==0'],
  },
};

export function setup() {
  return loginAllUsers();
}

export function nearby(data) {
  const longitude = 116.397128 + (Math.random() - 0.5) * 0.01;
  const latitude = 39.916527 + (Math.random() - 0.5) * 0.01;
  const response = http.get(
    `${BASE_URL}/accessibility-issues?longitude=${longitude}&latitude=${latitude}&radiusMeters=500&limit=20`,
    authParams('nearby-issues', data),
  );
  check(response, { 'nearby returned 200': (value) => value.status === 200 });
}
