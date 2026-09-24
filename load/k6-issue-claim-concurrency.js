import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter } from 'k6/metrics';

const baseUrl = __ENV.BASE_URL || 'http://127.0.0.1:8080/api/v1';
const issueId = __ENV.ISSUE_ID;
const version = Number(__ENV.ISSUE_VERSION || 0);
const requestCount = Number(__ENV.REQUEST_COUNT || 100);
const succeeded = new Counter('claim_succeeded');
const conflicted = new Counter('claim_conflicted');
const unexpected = new Counter('claim_unexpected');

export const options = {
  scenarios: {
    concurrentClaim: {
      executor: 'per-vu-iterations',
      vus: requestCount,
      iterations: 1,
      maxDuration: '30s',
    },
  },
  thresholds: {
    claim_succeeded: ['count==1'],
    claim_conflicted: [`count==${requestCount - 1}`],
    claim_unexpected: ['count==0'],
  },
};

export function setup() {
  if (!issueId || !__ENV.ADMIN_EMAIL || !__ENV.ADMIN_PASSWORD) {
    fail('ISSUE_ID, ADMIN_EMAIL, and ADMIN_PASSWORD are required');
  }
  const response = http.post(
    `${baseUrl}/auth/login`,
    JSON.stringify({ email: __ENV.ADMIN_EMAIL, password: __ENV.ADMIN_PASSWORD }),
    { headers: { 'Content-Type': 'application/json' }, tags: { endpoint: 'setup-login' } },
  );
  if (response.status !== 200 || !response.json('accessToken')) {
    fail(`Admin login failed with HTTP ${response.status}`);
  }
  return { token: response.json('accessToken') };
}

export default function (data) {
  const response = http.post(
    `${baseUrl}/accessibility-issues/${issueId}/transitions`,
    JSON.stringify({ targetStatus: 'PROCESSING', reason: `CONCURRENT_CLAIM_${__VU}`, version }),
    {
      headers: { Authorization: `Bearer ${data.token}`, 'Content-Type': 'application/json' },
      tags: { endpoint: 'issue-claim' },
      responseCallback: http.expectedStatuses(200, 409),
    },
  );

  if (response.status === 200) {
    succeeded.add(1);
  } else if (response.status === 409) {
    conflicted.add(1);
  } else {
    unexpected.add(1);
  }
  check(response, { 'claim returned 200 or 409': (value) => value.status === 200 || value.status === 409 });
}
