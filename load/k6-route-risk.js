import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { authParams, BASE_URL, loginAllUsers } from './lib/common.js';

const rate = Number(__ENV.RATE || 10);
const duration = __ENV.DURATION || '30s';
const corridorMeters = Number(__ENV.CORRIDOR_METERS || 20);

export const options = {
  scenarios: {
    routeRisk: {
      executor: 'constant-arrival-rate',
      rate,
      timeUnit: '1s',
      duration,
      preAllocatedVUs: 20,
      maxVUs: 100,
      exec: 'assessRoute',
    },
  },
  thresholds: {
    'http_req_failed{endpoint:route-risk}': ['rate<0.01'],
    'http_req_duration{endpoint:route-risk}': ['p(95)<800', 'p(99)<1500'],
    checks: ['rate>0.99'],
    dropped_iterations: ['count==0'],
  },
};

export function setup() {
  return loginAllUsers();
}

export function assessRoute(data) {
  const shift = ((exec.scenario.iterationInTest % 11) - 5) * 0.00002;
  const points = [];
  for (let index = 0; index < 20; index += 1) {
    points.push({
      longitude: 116.392 + index * 0.00055 + shift,
      latitude: 39.912 + index * 0.00045 - shift,
    });
  }

  const response = http.post(
    `${BASE_URL}/accessibility-issues/route-risk-assessments`,
    JSON.stringify({ points, corridorMeters }),
    authParams('route-risk', data),
  );
  check(response, { 'route risk returned 200': (value) => value.status === 200 });
}
