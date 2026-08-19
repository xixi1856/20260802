import http from 'k6/http';
import { check, fail } from 'k6';
import { SharedArray } from 'k6/data';

export const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8080/api/v1';

export const users = new SharedArray('load-test users', () => {
  return JSON.parse(open('../data/users.local.json'));
});

let accessToken;

export function currentUser() {
  return users[(__VU - 1) % users.length];
}

export function authParams(endpoint) {
  if (!accessToken) {
    const user = currentUser();
    const response = http.post(
      `${BASE_URL}/auth/login`,
      JSON.stringify({ email: user.email, password: user.password }),
      {
        headers: { 'Content-Type': 'application/json' },
        tags: { endpoint: 'login' },
      },
    );

    const loginSucceeded = check(response, {
      'login returned 200': (value) => value.status === 200,
      'login returned access token': (value) => Boolean(value.json('accessToken')),
    });
    if (!loginSucceeded) {
      fail(`Login failed with HTTP ${response.status}`);
    }
    accessToken = response.json('accessToken');
  }

  return {
    headers: {
      Authorization: `Bearer ${accessToken}`,
      'Content-Type': 'application/json',
    },
    tags: { endpoint },
  };
}
