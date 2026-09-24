import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';

const baseUrl = __ENV.BASE_URL || 'http://127.0.0.1:8080/api/v1';
const issueId = __ENV.ISSUE_ID;
const mode = __ENV.MODE || 'different-users';
const requestCount = Number(__ENV.REQUEST_COUNT || 100);

const users = new SharedArray('verification users', () => JSON.parse(open('./data/users.local.json')));
const accepted = new Counter('verification_accepted');
const unexpected = new Counter('verification_unexpected');

export const options = {
  scenarios: {
    concurrentVerification: {
      executor: 'per-vu-iterations',
      vus: requestCount,
      iterations: 1,
      maxDuration: '30s',
    },
  },
  thresholds: {
    verification_accepted: [`count==${requestCount}`],
    verification_unexpected: ['count==0'],
  },
};

export function setup() {
  if (!issueId) {
    fail('ISSUE_ID is required');
  }
  if (mode !== 'same-user' && users.length < requestCount) {
    fail(`MODE=${mode} requires ${requestCount} users but found ${users.length}`);
  }

  const tokens = [];
  const loginUsers = mode === 'same-user' ? users.slice(0, 1) : users.slice(0, requestCount);
  for (const user of loginUsers) {
    const response = http.post(
      `${baseUrl}/auth/login`,
      JSON.stringify({ email: user.email, password: user.password }),
      { headers: { 'Content-Type': 'application/json' }, tags: { endpoint: 'setup-login' } },
    );
    if (response.status !== 200 || !response.json('accessToken')) {
      fail(`Login failed for ${user.email} with HTTP ${response.status}`);
    }
    tokens.push(response.json('accessToken'));
  }
  return { tokens };
}

export default function (data) {
  const index = __VU - 1;
  const riskLevel = selectRiskLevel(index);
  const token = mode === 'same-user' ? data.tokens[0] : data.tokens[index];
  const response = http.post(
    `${baseUrl}/accessibility-issues/${issueId}/verifications`,
    JSON.stringify({ decision: 'CONFIRM', riskLevel, note: `CONCURRENCY_${mode}_${index}` }),
    {
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      tags: { endpoint: 'verification', mode },
    },
  );

  if (response.status === 204) {
    accepted.add(1);
  } else {
    unexpected.add(1);
  }
  check(response, { 'verification returned 204': (value) => value.status === 204 });
}

function selectRiskLevel(index) {
  if (mode === 'tie-high-low') {
    return index < requestCount / 2 ? 'HIGH' : 'LOW';
  }
  if (mode === 'different-users') {
    if (index < 40) return 'HIGH';
    if (index < 70) return 'MEDIUM';
    return 'LOW';
  }
  return 'HIGH';
}
