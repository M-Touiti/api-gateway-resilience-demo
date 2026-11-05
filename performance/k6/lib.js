import crypto from 'k6/crypto';
import encoding from 'k6/encoding';

const SECRET = __ENV.JWT_SECRET || 'my-very-secret-key-that-is-at-least-256-bits-long-for-hs256';

// Comma-separated gateway base URLs. Requests are spread round-robin across them.
export const BASE_URLS = (__ENV.BASE_URLS || 'http://gateway-service:8080').split(',');

export const TREND_STATS = ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'];

export function signJwt(email, role = 'USER') {
  const now = Math.floor(Date.now() / 1000);
  const header = encoding.b64encode(JSON.stringify({ alg: 'HS256', typ: 'JWT' }), 'rawurl');
  const payload = encoding.b64encode(
    JSON.stringify({ sub: email, role, iat: now, exp: now + 86400 }),
    'rawurl',
  );
  const signature = crypto.hmac('sha256', SECRET, `${header}.${payload}`, 'base64rawurl');
  return `${header}.${payload}.${signature}`;
}

// One token per synthetic user, so rate-limit buckets stay independent.
export function signUsers(count, prefix = 'load-user') {
  return Array.from({ length: count }, (_, i) => signJwt(`${prefix}-${i}@test.com`));
}

export function pick(list, index) {
  return list[index % list.length];
}

export function summaryOutput(data) {
  return { [`/results/${__ENV.RESULT_NAME}.summary.json`]: JSON.stringify(data, null, 2) };
}
