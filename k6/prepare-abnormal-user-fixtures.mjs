// #441: 명시한 빈 격리 Redis DB와 전용 SQL fixture를 확인한 뒤 로컬 JWT·Gate·Balance를 적재한다.
import { createHmac } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const directory = fileURLToPath(new URL('.', import.meta.url));
const secret = Buffer.from(process.env.JWT_SECRET || '', 'base64');
if (secret.length < 32) throw new Error('격리 앱과 동일한 256비트 이상 JWT_SECRET이 필요합니다.');
const redisDb = process.env.ABNORMAL_K6_ISOLATED_REDIS_DB || '';
const tag = process.env.ABNORMAL_K6_FIXTURE_TAG || '';
if (!/^(?:[1-9]|1[0-5])$/.test(redisDb) ||
    !/^[a-z0-9-]*$/.test(tag) || process.env.ABNORMAL_K6_PREPARE !== 'true') {
  throw new Error('격리 Redis DB(1~15), 영문 소문자 tag, ABNORMAL_K6_PREPARE=true를 확인하세요.');
}

const fixtureName = `abnormal-user${tag ? `-${tag}` : ''}.fixtures.local.json`;
const fixturePath = `${directory}/${fixtureName}`;
if (existsSync(fixturePath)) {
  throw new Error('기존 local fixture 파일을 덮어쓰지 않습니다. 새 격리 환경을 준비하세요.');
}
const example = JSON.parse(readFileSync(`${directory}/abnormal-user.fixtures.example.json`, 'utf8'));
const now = Math.floor(Date.now() / 1000);
function token(memberId) {
  const header = Buffer.from(JSON.stringify({ alg: 'HS256', typ: 'JWT' })).toString('base64url');
  const payload = Buffer.from(JSON.stringify({ iss: 'cking', sub: String(memberId), role: 'USER',
    iat: now, nbf: now, exp: now + 3600 })).toString('base64url');
  const data = `${header}.${payload}`;
  return `${data}.${createHmac('sha256', secret).update(data).digest('base64url')}`;
}

function redis(...args) {
  return execFileSync('redis-cli', ['-h', '127.0.0.1', '-p', '6379', '-n', redisDb, '--raw', ...args],
    { encoding: 'utf8' }).trim();
}

const bootstrapStreams = new Set(['stream:ticket-earned', 'stream:common-ticket-earned',
  'stream:ticket-deducted']);
const existingKeys = redis('KEYS', '*').split('\n').filter(Boolean);
if (existingKeys.some((key) => !bootstrapStreams.has(key) || redis('XLEN', key) !== '0')) {
  throw new Error(`Redis DB ${redisDb}에 초기 빈 Stream 외 데이터가 있습니다. 기존 데이터를 지우지 말고 별도 환경을 준비하세요.`);
}

const keys = [
  ['event:status:411', 'OPEN'],
  ['event:endat:411', String(Date.now() + 3600000)],
  ['event:entry-total:411', '0'],
  ...[111, 112, 114, 115, 116, 117].map((memberId) =>
    [`ticket:balance:211:${memberId}`, '0']),
  ['ticket:balance:211:113', '12'],
];
for (const [key] of keys) {
  if (redis('EXISTS', key) !== '0') throw new Error(`격리 Redis 키가 이미 존재합니다: ${key}`);
}
for (const [key, value] of keys) {
  if (redis('SET', key, value, 'NX') !== 'OK') throw new Error(`격리 Redis 키 적재 실패: ${key}`);
}
for (const name of ['missionRequestBurst', 'duplicateMissionBurst', 'entryRequestBurst',
  'insufficientBalanceBurst', 'requestIdRotation', 'rapidEarnAndSpend', 'failureBurst']) {
  example[name].token = token(example[name].memberId);
}
writeFileSync(fixturePath, JSON.stringify(example, null, 2),
  { encoding: 'utf8', mode: 0o600, flag: 'wx' });
console.log(`합성 JWT/Redis fixture 준비 완료: k6/${fixtureName}`);
