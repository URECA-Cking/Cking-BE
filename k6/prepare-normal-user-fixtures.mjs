// #434 격리 실행 전용: Flyway + normal-user.bootstrap.sql 이후에 실행한다.
import { createHmac } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const directory = fileURLToPath(new URL('.', import.meta.url));
const secret = Buffer.from(process.env.JWT_SECRET || '', 'base64');
if (secret.length < 32) throw new Error('격리 앱과 동일한 256비트 이상 JWT_SECRET이 필요합니다.');
if (process.env.NORMAL_K6_ISOLATED_REDIS_DB !== '13') {
  throw new Error('격리 Redis DB 13을 명시적으로 확인해야 합니다.');
}

const fixturePath = `${directory}/normal-user.fixtures.local.json`;
const example = JSON.parse(readFileSync(`${directory}/normal-user.fixtures.example.json`, 'utf8'));
const now = Math.floor(Date.now() / 1000);
function token(memberId) {
  const header = Buffer.from(JSON.stringify({ alg: 'HS256', typ: 'JWT' })).toString('base64url');
  const payload = Buffer.from(JSON.stringify({ iss: 'cking', sub: String(memberId), role: 'USER',
    iat: now, nbf: now, exp: now + 3600 })).toString('base64url');
  const data = `${header}.${payload}`;
  const signature = createHmac('sha256', secret).update(data).digest('base64url');
  return `${data}.${signature}`;
}

function redis(...args) {
  return execFileSync('redis-cli', ['-h', '127.0.0.1', '-p', '6379', '-n', '13', '--raw', ...args],
    { encoding: 'utf8' }).trim();
}

if (redis('DBSIZE') !== '0') {
  throw new Error('Redis DB 13이 비어 있지 않습니다. 다른 데이터가 있을 수 있으므로 중단합니다.');
}

const keys = [
  ['event:status:401', 'OPEN'],
  ['event:endat:401', String(Date.now() + 3600000)],
  ['event:entry-total:401', '0'],
  ['ticket:balance:201:101', '0'],
  ['ticket:balance:201:102', '2'],
  ['ticket:balance:201:103', '0'],
  ['ticket:balance:201:104', '0'],
  ['ticket:balance:202:104', '0'],
  ['ticket:balance:common:105', '0'],
];
for (const [key] of keys) {
  if (redis('EXISTS', key) !== '0') throw new Error(`격리 Redis 키가 이미 존재합니다: ${key}`);
}
for (const [key, value] of keys) {
  if (redis('SET', key, value, 'NX') !== 'OK') throw new Error(`격리 Redis 키 적재 실패: ${key}`);
}
for (const name of ['clickReplay', 'multiEntry', 'insufficientThenEarn', 'creatorSequence', 'commonEarnSpend']) {
  example[name].token = token(example[name].memberId);
}
writeFileSync(fixturePath, JSON.stringify(example, null, 2), { encoding: 'utf8', mode: 0o600, flag: 'wx' });
console.log('합성 JWT/Redis fixture 준비 완료: k6/normal-user.fixtures.local.json');
