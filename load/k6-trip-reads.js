import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { authParams, BASE_URL, currentUser, loginAllUsers, users } from './lib/common.js';

const rate = Number(__ENV.RATE || 10);
const duration = __ENV.DURATION || '30s';

export const options = {
  scenarios: {
    tripReads: {
      executor: 'constant-arrival-rate',
      rate,
      timeUnit: '1s',
      duration,
      preAllocatedVUs: Math.max(20, rate * 2),
      maxVUs: Math.max(100, rate * 4),
    },
  },
  thresholds: {
    'http_req_failed{endpoint:trip-history}': ['rate<0.01'],
    'http_req_failed{endpoint:trip-detail}': ['rate<0.01'],
    'http_req_failed{endpoint:trip-track}': ['rate<0.01'],
    'http_req_duration{endpoint:trip-history}': ['p(95)<500', 'p(99)<1000'],
    'http_req_duration{endpoint:trip-detail}': ['p(95)<500', 'p(99)<1000'],
    'http_req_duration{endpoint:trip-track}': ['p(95)<800', 'p(99)<1500'],
    checks: ['rate>0.99'],
    dropped_iterations: ['count==0'],
  },
};

export function setup() {
  return loginAllUsers();
}

export default function (data) {
  const userIndex = exec.scenario.iterationInTest % users.length;
  const user = currentUser(userIndex);
  const history = http.get(`${BASE_URL}/trips?page=0&size=20`, authParams('trip-history', data, userIndex));
  const detail = http.get(`${BASE_URL}/trips/${user.tripId}`, authParams('trip-detail', data, userIndex));
  const track = http.get(
    `${BASE_URL}/trips/${user.tripId}/track-points?size=1000`,
    authParams('trip-track', data, userIndex),
  );

  check(history, {
    'history is owned and paged': (response) => response.status === 200
      && Array.isArray(response.json('items'))
      && response.json('total') >= 1,
  });
  check(detail, {
    'trip detail is returned': (response) => response.status === 200 && response.json('id') === user.tripId,
  });
  check(track, {
    'track is ordered and paged': (response) => response.status === 200 && Array.isArray(response.json('points')),
  });
}
