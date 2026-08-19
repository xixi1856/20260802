import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { authParams, BASE_URL, currentUser, loginAllUsers } from './lib/common.js';

const duration = __ENV.DURATION || '90s';

export const options = {
  scenarios: {
    nearby: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.NEARBY_RATE || 60),
      timeUnit: '1s',
      duration,
      preAllocatedVUs: 40,
      maxVUs: 200,
      exec: 'nearbyIssues',
    },
    routeRisk: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.ROUTE_RATE || 10),
      timeUnit: '1s',
      duration,
      preAllocatedVUs: 20,
      maxVUs: 100,
      exec: 'routeRisk',
    },
    tracks: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.TRACK_RATE || 10),
      timeUnit: '1s',
      duration,
      preAllocatedVUs: 20,
      maxVUs: 100,
      exec: 'trackPoints',
    },
    reports: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.REPORT_RATE || 5),
      timeUnit: '1s',
      duration,
      preAllocatedVUs: 10,
      maxVUs: 50,
      exec: 'createIssue',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{endpoint:nearby-issues}': ['p(95)<300'],
    'http_req_duration{endpoint:route-risk}': ['p(95)<800'],
    'http_req_duration{endpoint:track-points}': ['p(95)<500'],
    'http_req_duration{endpoint:create-issue}': ['p(95)<500'],
    checks: ['rate>0.99'],
    dropped_iterations: ['count==0'],
  },
};

export function setup() {
  return loginAllUsers();
}

export function nearbyIssues(data) {
  const longitude = 116.397128 + (Math.random() - 0.5) * 0.01;
  const latitude = 39.916527 + (Math.random() - 0.5) * 0.01;
  const response = http.get(
    `${BASE_URL}/accessibility-issues?longitude=${longitude}&latitude=${latitude}&radiusMeters=500&limit=20`,
    authParams('nearby-issues', data),
  );
  check(response, { 'nearby returned 200': (value) => value.status === 200 });
}

export function routeRisk(data) {
  const points = [];
  for (let index = 0; index < 20; index += 1) {
    points.push({
      longitude: 116.392 + index * 0.00055,
      latitude: 39.912 + index * 0.00045,
    });
  }
  const response = http.post(
    `${BASE_URL}/accessibility-issues/route-risk-assessments`,
    JSON.stringify({ points, corridorMeters: 30 }),
    authParams('route-risk', data),
  );
  check(response, { 'route risk returned 200': (value) => value.status === 200 });
}

export function trackPoints(data) {
  const user = currentUser();
  const iteration = exec.scenario.iterationInTest;
  const points = [];
  const requestTime = Date.now();
  for (let index = 0; index < 10; index += 1) {
    points.push({
      recordedAt: new Date(requestTime - (10 - index) * 1000 + (iteration % 1000)).toISOString(),
      longitude: 116.397128 + ((iteration + index) % 1000) * 0.000001,
      latitude: 39.916527 + ((iteration + index) % 1000) * 0.000001,
      accuracyMeters: 8.5,
      speedMetersPerSecond: 1.1,
    });
  }
  const response = http.post(
    `${BASE_URL}/trips/${user.tripId}/track-points`,
    JSON.stringify({ points }),
    authParams('track-points', data),
  );
  check(response, { 'mixed track append returned 202': (value) => value.status === 202 });
}

export function createIssue(data) {
  const response = http.post(
    `${BASE_URL}/accessibility-issues`,
    JSON.stringify({
      type: 'LONG_TERM_OCCUPATION',
      description: `LOAD_MIX_${__VU}_${__ITER}`,
      severity: 3,
      longitude: 116.397128 + (Math.random() - 0.5) * 0.02,
      latitude: 39.916527 + (Math.random() - 0.5) * 0.02,
    }),
    authParams('create-issue', data),
  );
  check(response, { 'mixed issue create returned 201': (value) => value.status === 201 });
}
