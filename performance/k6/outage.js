// Steady traffic on two independent routes while the orchestrator kills and restarts a
// dependency. The circuit breaker state is sampled every second from the actuator.
import http from 'k6/http';
import exec from 'k6/execution';
import { Gauge } from 'k6/metrics';
import { BASE_URLS, TREND_STATS, pick, signUsers, summaryOutput } from './lib.js';

const DURATION = __ENV.DURATION || '180s';
const STATE_CODES = { CLOSED: 0, OPEN: 1, HALF_OPEN: 2 };

const userBreaker = new Gauge('cb_user_service');
const paymentBreaker = new Gauge('cb_payment_service');

function traffic(exec_, rate) {
  return {
    executor: 'constant-arrival-rate',
    exec: exec_,
    rate,
    timeUnit: '1s',
    duration: DURATION,
    preAllocatedVUs: 100,
    maxVUs: 1500,
  };
}

export const options = {
  discardResponseBodies: true,
  summaryTrendStats: TREND_STATS,
  scenarios: {
    users: traffic('users', Number(__ENV.USERS_RATE || 200)),
    payments: traffic('payments', Number(__ENV.PAYMENTS_RATE || 50)),
    breaker_poll: {
      executor: 'constant-arrival-rate',
      exec: 'pollBreakers',
      rate: 1,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 2,
    },
  },
};

export function setup() {
  return { tokens: signUsers(200) };
}

function call(data, path, route) {
  const i = exec.scenario.iterationInTest;
  http.get(`${pick(BASE_URLS, i)}${path}`, {
    headers: { Authorization: `Bearer ${pick(data.tokens, i)}` },
    timeout: '15s',
    tags: { route },
  });
}

export function users(data) {
  call(data, '/api/v1/users/me', 'users');
}

export function payments(data) {
  call(data, '/api/v1/payments', 'payments');
}

export function pollBreakers() {
  const res = http.get(`${BASE_URLS[0]}/actuator/circuitbreakers`, {
    responseType: 'text',
    timeout: '5s',
    tags: { route: 'actuator' },
  });
  if (res.status !== 200) return;
  const breakers = res.json('circuitBreakers');
  userBreaker.add(STATE_CODES[breakers['user-service-cb'].state] ?? -1);
  paymentBreaker.add(STATE_CODES[breakers['payment-service-cb'].state] ?? -1);
}

export const handleSummary = summaryOutput;
