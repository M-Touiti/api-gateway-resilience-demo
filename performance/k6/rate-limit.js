// Drives a route well above its limit, then counts what was admitted and what was rejected.
//   MODE=user            one JWT user on ROUTE (users | payments)
//   MODE=ip              one client on the public auth route, keyed by source IP
//   MODE=ip-spoofed-xff  same, with a different X-Forwarded-For value on every request
import http from 'k6/http';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';
import { BASE_URLS, TREND_STATS, pick, signJwt, summaryOutput } from './lib.js';

const MODE = __ENV.MODE || 'user';
const ROUTE = __ENV.ROUTE || 'users';
const PATHS = { users: '/api/v1/users/me', payments: '/api/v1/payments' };

const allowed = new Counter('requests_allowed');
const limited = new Counter('requests_limited');
const other = new Counter('requests_other');
const allowedLatency = new Trend('latency_allowed', true);
const limitedLatency = new Trend('latency_limited', true);

export const options = {
  discardResponseBodies: true,
  summaryTrendStats: TREND_STATS,
  scenarios: {
    flood: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 200),
      timeUnit: '1s',
      duration: __ENV.DURATION || '60s',
      preAllocatedVUs: 50,
      maxVUs: 400,
    },
  },
};

export function setup() {
  return { token: signJwt(`flood-${Date.now()}@test.com`) };
}

function send(data, i) {
  const base = pick(BASE_URLS, i);
  if (MODE === 'user') {
    return http.get(`${base}${PATHS[ROUTE]}`, {
      headers: { Authorization: `Bearer ${data.token}` },
      timeout: '10s',
    });
  }
  const headers = { 'Content-Type': 'application/json' };
  if (MODE === 'ip-spoofed-xff') {
    headers['X-Forwarded-For'] = `10.${(i >> 16) & 255}.${(i >> 8) & 255}.${i & 255}`;
  }
  return http.post(`${base}/api/v1/auth/login`, '{"email":"a@test.com","password":"x"}', {
    headers,
    timeout: '10s',
  });
}

export default function (data) {
  const res = send(data, exec.scenario.iterationInTest);
  if (res.status === 200) {
    allowed.add(1);
    allowedLatency.add(res.timings.duration);
  } else if (res.status === 429) {
    limited.add(1);
    limitedLatency.add(res.timings.duration);
  } else {
    other.add(1);
  }
}

export const handleSummary = summaryOutput;
