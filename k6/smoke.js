// 배포 후 개발 서버의 주요 경로를 검증한다.
import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const FRONTEND_ORIGIN = __ENV.FRONTEND_ORIGIN || 'https://dev.cking.co.kr';
const EXPECTED_IMAGE_TAG = __ENV.EXPECTED_IMAGE_TAG || '';

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: {
    checks: ['rate==1.00'],
  },
};

export default function () {
  health();
  version();
  eventList();
  authRequired();
  notFound();
  docsProtected();
  corsAllowed();
  oauthLogin('google', 'accounts.google.com');
  oauthLogin('kakao', 'kauth.kakao.com');
}

function health() {
  const res = http.get(`${BASE_URL}/actuator/health`, { tags: { name: 'health' } });
  check(res, {
    'health 200': (r) => r.status === 200,
    'health UP': (r) => r.json('status') === 'UP',
  });
}

function version() {
  const res = http.get(`${BASE_URL}/actuator/info`, { tags: { name: 'info' } });
  check(res, { 'info 200': (r) => r.status === 200 });
  if (EXPECTED_IMAGE_TAG) {
    check(res, { '배포한 이미지 태그': (r) => r.json('image.tag') === EXPECTED_IMAGE_TAG });
  }
}

function eventList() {
  const res = http.get(`${BASE_URL}/api/events?page=0&size=1`, { tags: { name: 'events' } });
  check(res, {
    'events 200': (r) => r.status === 200,
    'events SUCCESS': (r) => r.json('code') === 'SUCCESS',
  });
}

function authRequired() {
  const res = http.get(`${BASE_URL}/api/me`, { tags: { name: 'auth-required' } });
  check(res, {
    'me 401': (r) => r.status === 401,
    'me UNAUTHORIZED': (r) => r.json('code') === 'UNAUTHORIZED',
  });
}

function notFound() {
  const res = http.get(`${BASE_URL}/api/does-not-exist`, { tags: { name: 'not-found' } });
  check(res, {
    'not found 404': (r) => r.status === 404,
    'not found RESOURCE_NOT_FOUND': (r) => r.json('code') === 'RESOURCE_NOT_FOUND',
  });
}

function docsProtected() {
  const res = http.get(`${BASE_URL}/swagger-ui/index.html`, { tags: { name: 'swagger' } });
  check(res, {
    'swagger 401': (r) => r.status === 401,
    'swagger Basic 인증': (r) => (r.headers['Www-Authenticate'] || '').startsWith('Basic'),
  });
}

function corsAllowed() {
  const res = http.get(`${BASE_URL}/api/events?page=0&size=1`, {
    headers: { Origin: FRONTEND_ORIGIN },
    tags: { name: 'cors' },
  });
  check(res, {
    'cors 200': (r) => r.status === 200,
    'cors 허용 오리진': (r) => r.headers['Access-Control-Allow-Origin'] === FRONTEND_ORIGIN,
    'cors credentials': (r) => r.headers['Access-Control-Allow-Credentials'] === 'true',
  });
}

function oauthLogin(provider, host) {
  const res = http.get(`${BASE_URL}/oauth2/authorization/${provider}`, {
    redirects: 0,
    tags: { name: `oauth-${provider}` },
  });
  const location = res.headers['Location'] || '';
  const match = location.match(/[?&]redirect_uri=([^&]*)/);
  const redirectUri = match ? decodeURIComponent(match[1]) : '';
  check(res, {
    [`${provider} 302`]: (r) => r.status === 302,
    [`${provider} 로그인 페이지로 이동`]: () => location.startsWith(`https://${host}/`),
    [`${provider} redirect_uri`]: () => redirectUri === `${BASE_URL}/login/oauth2/code/${provider}`,
  });
}
