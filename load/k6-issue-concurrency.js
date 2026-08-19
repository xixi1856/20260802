import http from 'k6/http';
import { check } from 'k6';
import { authParams, BASE_URL } from './lib/common.js';

const rate = Number(__ENV.RATE || 20);
const duration = __ENV.DURATION || '30s';

export const options = {
  scenarios: {
    concurrentAggregation: {
      executor: 'constant-arrival-rate',
      rate,
      timeUnit: '1s',
      duration,
      preAllocatedVUs: 30,
      maxVUs: 200,
      exec: 'createIssue',
    },
  },
  thresholds: {
    'http_req_failed{endpoint:create-issue}': ['rate<0.01'],
    'http_req_duration{endpoint:create-issue}': ['p(95)<500', 'p(99)<1000'],
    checks: ['rate>0.99'],
    dropped_iterations: ['count==0'],
  },
};

export function createIssue() {
  const response = http.post(
    `${BASE_URL}/accessibility-issues`,
    JSON.stringify({
      type: 'TACTILE_PAVING_DAMAGED',
      description: `LOAD_CONCURRENCY_${__VU}_${__ITER}`,
      severity: 4,
      longitude: 121.473701,
      latitude: 31.230416,
    }),
    authParams('create-issue'),
  );
  check(response, { 'issue create returned 201': (value) => value.status === 201 });
}
