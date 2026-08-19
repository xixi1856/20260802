import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { authParams, BASE_URL, currentUser, loginAllUsers, users } from './lib/common.js';

const rate = Number(__ENV.RATE || 20);
const duration = __ENV.DURATION || '60s';
const batchSize = Number(__ENV.BATCH_SIZE || 20);

export const options = {
  scenarios: {
    trackWrites: {
      executor: 'constant-arrival-rate',
      rate,
      timeUnit: '1s',
      duration,
      preAllocatedVUs: 40,
      maxVUs: 200,
      exec: 'appendTrackPoints',
    },
  },
  thresholds: {
    'http_req_failed{endpoint:track-points}': ['rate<0.01'],
    'http_req_duration{endpoint:track-points}': ['p(95)<500', 'p(99)<1000'],
    checks: ['rate>0.99'],
    dropped_iterations: ['count==0'],
  },
};

export function setup() {
  return loginAllUsers();
}

export function appendTrackPoints(data) {
  const iteration = exec.scenario.iterationInTest;
  const userIndex = iteration % users.length;
  const user = currentUser(userIndex);
  const points = [];
  const requestTime = Date.now();

  for (let index = 0; index < batchSize; index += 1) {
    points.push({
      // Keep points inside the API's accepted window. The small per-iteration
      // offset avoids identical timestamps when requests land together.
      recordedAt: new Date(
        requestTime - (batchSize - index) * 1000 + (iteration % 1000),
      ).toISOString(),
      longitude: 116.397128 + ((iteration + index) % 1000) * 0.000001,
      latitude: 39.916527 + ((iteration + index) % 1000) * 0.000001,
      accuracyMeters: 8.5,
      speedMetersPerSecond: 1.1,
    });
  }

  const response = http.post(
    `${BASE_URL}/trips/${user.tripId}/track-points`,
    JSON.stringify({ points }),
    authParams('track-points', data, userIndex),
  );
  check(response, {
    'track append returned 202': (value) => value.status === 202,
    'track append inserted full batch': (value) => value.status === 202 && value.json('inserted') === batchSize,
  });
}
