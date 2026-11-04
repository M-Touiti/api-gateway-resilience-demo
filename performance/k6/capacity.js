// Open-model load at a fixed arrival rate. Requests keep arriving when the system
// slows down, so latency is not understated by coordinated omission.
//
// Traffic is spread over USERS synthetic users, so the per-user rate limiter
// (50 req/s) stays out of the way unless RATE / USERS approaches it.
import http from 'k6/http';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';
import { BASE_URLS, TREND_STATS, pick, signUsers, summaryOutput } from './lib.js';

const RATE = Number(__ENV.RATE || 100);
const USERS = Number(__ENV.USERS || 400);
// Optional low-rate probe that skips the gateway, to tell gateway stalls from wider ones.
const CONTROL_URL = __ENV.CONTROL_URL;

const ok = new Counter('responses_200');
const shed = new Counter('responses_503');
const other = new Counter('responses_other');
const controlLatency = new Trend('control_latency', true);

const scenarios = {
  load: {
    executor: 'constant-arrival-rate',
    rate: RATE,
    timeUnit: '1s',
    duration: __ENV.DURATION || '60s',
    preAllocatedVUs: Math.max(50, Math.ceil(RATE / 10)),
    // Each VU costs memory, so this is capped. Past the cap, k6 reports dropped iterations.
    maxVUs: Math.min(Math.max(500, RATE * 2), 3000),
  },
};
if (CONTROL_URL) {
  scenarios.control = {
    executor: 'constant-arrival-rate',
    exec: 'control',
    rate: 50,
    timeUnit: '1s',
    duration: __ENV.DURATION || '60s',
    preAllocatedVUs: 20,
    maxVUs: 200,
  };
}

export const options = {
  discardResponseBodies: true,
  summaryTrendStats: TREND_STATS,
  scenarios,
  // Makes k6 report the latency of successful requests on its own, separate from fast 503s.
  thresholds: { 'http_req_duration{expected_response:true}': ['max>=0'] },
};

export function setup() {
  return { tokens: signUsers(USERS) };
}

export default function (data) {
  const i = exec.scenario.iterationInTest;
  const base = pick(BASE_URLS, i);
  const res = http.get(`${base}/api/v1/users/me`, {
    headers: { Authorization: `Bearer ${pick(data.tokens, i)}` },
    timeout: '10s',
  });
  if (res.status === 200) ok.add(1);
  else if (res.status === 503) shed.add(1);
  else other.add(1);
}

export function control() {
  const res = http.get(`${CONTROL_URL}/api/v1/users/me`, {
    headers: { 'X-User-Email': 'control@test.com', 'X-User-Role': 'USER' },
    timeout: '10s',
    tags: { route: 'control' },
  });
  controlLatency.add(res.timings.duration);
}

export const handleSummary = summaryOutput;
