// #434 격리 실행 전용: Flyway + normal-user.bootstrap.sql 이후에 실행한다.
import { createHmac } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const directory = fileURLToPath(new URL('.', import.meta.url));
const secret = Buffer.from(process.env.JWT_SECRET || '', 'base64');
if (secret.length < 32) throw new Error('격리 앱과 동일한 256비트 이상 JWT_SECRET이 필요합니다.');
const redisDb = process.env.NORMAL_K6_ISOLATED_REDIS_DB;
if (!/^(?:[1-9]|1[0-5])$/.test(redisDb || '')) {
  throw new Error('격리 Redis DB 1~15를 명시적으로 확인해야 합니다.');
}
const fixtureTag = process.env.NORMAL_K6_FIXTURE_TAG;
if (!/^[a-z0-9][a-z0-9-]{0,31}$/.test(fixtureTag || '')) {
  throw new Error('반복 측정마다 다른 NORMAL_K6_FIXTURE_TAG가 필요합니다.');
}

const fixturePath = `${directory}/normal-user.${fixtureTag}.fixtures.local.json`;
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
  return execFileSync('redis-cli', ['-h', '127.0.0.1', '-p', '6379', '-n', redisDb, '--raw', ...args],
    { encoding: 'utf8' }).trim();
}

const bootstrapStreams = new Set(['stream:ticket-earned', 'stream:common-ticket-earned',
  'stream:ticket-deducted']);
const existingKeys = redis('KEYS', '*').split('\n').filter(Boolean);
if (existingKeys.some((key) => !bootstrapStreams.has(key) || redis('XLEN', key) !== '0')) {
  throw new Error(`Redis DB ${redisDb}에 시나리오 외 데이터가 있어 중단합니다.`);
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
console.log(`합성 JWT/Redis fixture 준비 완료: k6/normal-user.${fixtureTag}.fixtures.local.json`);
