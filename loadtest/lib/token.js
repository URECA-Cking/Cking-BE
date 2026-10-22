import crypto from 'k6/crypto';
import encoding from 'k6/encoding';

// 앱(JwtAccessTokenIssuer)과 같은 HS256 Access Token을 만든다. 로그인은 부하 테스트 범위 밖이다.
// 비밀값은 실행 환경변수로만 받고 출력하지 않는다.
export function tokenFactory(secretBase64, ttlSeconds) {
  const key = encoding.b64decode(secretBase64, 'std');
  if (key.byteLength < 32) {
    throw new Error('JWT_SECRET은 Base64로 디코딩해 32바이트 이상이어야 합니다.');
  }
  const header = encoding.b64encode(JSON.stringify({ alg: 'HS256', typ: 'JWT' }), 'rawurl');
  const issuedAt = Math.floor(Date.now() / 1000);

  return function token(memberId, role = 'USER') {
    const payload = encoding.b64encode(JSON.stringify({
      iss: 'cking', sub: String(memberId), role, iat: issuedAt, exp: issuedAt + ttlSeconds,
    }), 'rawurl');
    const data = `${header}.${payload}`;
    return `${data}.${crypto.hmac('sha256', key, data, 'base64rawurl')}`;
  };
}
