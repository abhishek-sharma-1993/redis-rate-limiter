// End-to-end load test against the sample app (HTTP -> interceptor -> Redis).
// Run: k6 run benchmarks/k6/load-test.js
import http from 'k6/http';
import { check } from 'k6';

export const options = {
  scenarios: {
    many_users: {
      executor: 'constant-arrival-rate',
      rate: 2000, timeUnit: '1s', duration: '60s',
      preAllocatedVUs: 200, maxVUs: 500,
    },
  },
  thresholds: {
    'http_req_duration{expected_response:true}': ['p(99)<20'],
  },
};

const BASE = __ENV.BASE_URL || 'http://localhost:8080';

export default function () {
  const merchant = Math.floor(Math.random() * 1000);
  const res = http.get(`${BASE}/api/merchants/${merchant}/orders`);
  check(res, {
    'status is 200 or 429': (r) => r.status === 200 || r.status === 429,
    'has rate limit headers': (r) => r.headers['X-Ratelimit-Limit'] !== undefined,
  });
}
