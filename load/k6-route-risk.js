import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { authParams, BASE_URL, loginAllUsers } from './lib/common.js';

const rate = Number(__ENV.RATE || 20);
const duration = __ENV.DURATION || '30s';
const corridorMeters = Number(__ENV.CORRIDOR_METERS || 20);
const pointCount = Number(__ENV.POINT_COUNT || 20);
const routeVariants = Number(__ENV.ROUTE_VARIANTS || 1009);
const routeStatus200 = new Counter('route_status_200');
const routeStatus429 = new Counter('route_status_429');
const routeStatusOther = new Counter('route_status_other');

if (pointCount < 2 || pointCount > 500) {
  throw new Error('POINT_COUNT must be between 2 and 500');
}
if (routeVariants < 1) {
  throw new Error('ROUTE_VARIANTS must be positive');
}

export const options = {
  scenarios: {
    routeRisk: {
      executor: 'constant-arrival-rate',
      rate,
      timeUnit: '1s',
      duration,
      preAllocatedVUs: Math.max(20, rate * 2),
      maxVUs: Math.max(100, rate * 4),
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
  const variant = exec.scenario.iterationInTest % routeVariants;
  const shift = routeVariants === 1 ? 0 : (variant / (routeVariants - 1) - 0.5) * 0.001;
  const points = [];
  for (let index = 0; index < pointCount; index += 1) {
    const progress = index / (pointCount - 1);
    points.push({
      longitude: 116.392 + progress * 0.01045 + shift,
      latitude: 39.912 + progress * 0.00855 - shift,
    });
  }

  const response = http.post(
    `${BASE_URL}/accessibility-issues/route-risk-assessments`,
    JSON.stringify({ points, corridorMeters }),
    authParams('route-risk', data),
  );
  if (response.status === 200) {
    routeStatus200.add(1);
  } else if (response.status === 429) {
    routeStatus429.add(1);
  } else {
    routeStatusOther.add(1);
  }
  check(response, { 'route risk returned 200': (value) => value.status === 200 });
}
