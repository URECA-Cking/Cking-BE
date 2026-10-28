// 배포 후 서버의 주요 경로를 검증한다. 운영은 API 문서를 끄므로 DOCS_DISABLED=1로 문서가 없는지 확인한다.
// 운영 새 서버는 ALB에 붙기 전에 이 파일로 자기 자신을 검사한다(BASE_URL=http://localhost:8080).
// 이때 PUBLIC_BASE_URL에 공개 주소를 주면 ALB가 붙이는 X-Forwarded-* 헤더를 흉내 내 같은 기대값으로 검사한다.
// GATE_SECRET이 있으면 운영 API 앞 비밀 쿠키 게이트(WAF)를 통과하는 헤더를 붙인다.
import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const FRONTEND_ORIGIN = __ENV.FRONTEND_ORIGIN || 'https://dev.cking.co.kr';
const EXPECTED_IMAGE_TAG = __ENV.EXPECTED_IMAGE_TAG || '';
const DOCS_DISABLED = __ENV.DOCS_DISABLED === '1';
const PUBLIC_BASE_URL = __ENV.PUBLIC_BASE_URL || BASE_URL;
const GATE_HEADERS = __ENV.GATE_SECRET ? { 'x-cking-gate': __ENV.GATE_SECRET } : {};
const FORWARDED_HEADERS = PUBLIC_BASE_URL === BASE_URL ? {} : {
  'X-Forwarded-Proto': 'https',
  'X-Forwarded-Host': PUBLIC_BASE_URL.replace(/^https?:\/\//, ''),
  'X-Forwarded-Port': '443',
};

function params(extra = {}) {
  return { ...extra, headers: { ...GATE_HEADERS, ...FORWARDED_HEADERS, ...(extra.headers || {}) } };
}

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: {
    checks: ['rate==1.00'],
  },
};

export default function () {
  version();
  eventList();
  authRequired();
  notFound();
  if (DOCS_DISABLED) {
    docsDisabled();
  } else {
    docsProtected();
  }
  corsAllowed();
  oauthLogin('google', 'accounts.google.com');
  oauthLogin('kakao', 'kauth.kakao.com');
}

function version() {
  if (!EXPECTED_IMAGE_TAG) {
    return;
  }
  const res = http.get(`${BASE_URL}/api/events?page=0&size=1`, params({ tags: { name: 'version' } }));
  check(res, { '배포한 이미지 태그': (r) => r.headers['X-Image-Tag'] === EXPECTED_IMAGE_TAG });
}

function eventList() {
  const res = http.get(`${BASE_URL}/api/events?page=0&size=1`, params({ tags: { name: 'events' } }));
  check(res, {
    'events 200': (r) => r.status === 200,
    'events SUCCESS': (r) => r.json('code') === 'SUCCESS',
  });
}

function authRequired() {
  const res = http.get(`${BASE_URL}/api/me`, params({
    responseCallback: http.expectedStatuses(401),
    tags: { name: 'auth-required' },
  }));
  check(res, {
    'me 401': (r) => r.status === 401,
    'me UNAUTHORIZED': (r) => r.json('code') === 'UNAUTHORIZED',
  });
}

function notFound() {
  const res = http.get(`${BASE_URL}/api/does-not-exist`, params({
    responseCallback: http.expectedStatuses(404),
    tags: { name: 'not-found' },
  }));
  check(res, {
    'not found 404': (r) => r.status === 404,
    'not found RESOURCE_NOT_FOUND': (r) => r.json('code') === 'RESOURCE_NOT_FOUND',
  });
}

function docsProtected() {
  const res = http.get(`${BASE_URL}/swagger-ui/index.html`, params({
    responseCallback: http.expectedStatuses(401),
    tags: { name: 'swagger' },
  }));
  check(res, {
    'swagger 401': (r) => r.status === 401,
    'swagger Basic 인증': (r) => (r.headers['Www-Authenticate'] || '').startsWith('Basic'),
  });
}

function docsDisabled() {
  const res = http.get(`${BASE_URL}/swagger-ui/index.html`, params({
    responseCallback: http.expectedStatuses(404),
    tags: { name: 'swagger' },
  }));
  check(res, { 'swagger 404': (r) => r.status === 404 });
}

function corsAllowed() {
  const res = http.get(`${BASE_URL}/api/events?page=0&size=1`, params({
    headers: { Origin: FRONTEND_ORIGIN },
    tags: { name: 'cors' },
  }));
  check(res, {
    'cors 200': (r) => r.status === 200,
    'cors 허용 오리진': (r) => r.headers['Access-Control-Allow-Origin'] === FRONTEND_ORIGIN,
    'cors credentials': (r) => r.headers['Access-Control-Allow-Credentials'] === 'true',
  });
}

function oauthLogin(provider, host) {
  const res = http.get(`${BASE_URL}/oauth2/authorization/${provider}`, params({
    redirects: 0,
    tags: { name: `oauth-${provider}` },
  }));
  const location = res.headers['Location'] || '';
  const match = location.match(/[?&]redirect_uri=([^&]*)/);
  const redirectUri = match ? decodeURIComponent(match[1]) : '';
  check(res, {
    [`${provider} 302`]: (r) => r.status === 302,
    [`${provider} 로그인 페이지로 이동`]: () => location.startsWith(`https://${host}/`),
    [`${provider} redirect_uri`]: () => redirectUri === `${PUBLIC_BASE_URL}/login/oauth2/code/${provider}`,
  });
}
