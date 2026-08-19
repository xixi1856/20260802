import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  vus: 10,
  duration: '30s',
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<300'],
  },
};

export default function () {
  const response = http.get(`${__ENV.BASE_URL || 'http://localhost:8080'}/actuator/health`);
  check(response, { 'health is 200': (value) => value.status === 200 });
  sleep(1);
}
