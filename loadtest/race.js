// 동시 요청: 같은 requestId를 여러 VU가 동시에 보내면 한 번만 차감되는지(같은 요청 라운드),
// 잔액보다 많은 응모가 동시에 오면 잔액만큼만 성공하는지(잔액 초과 라운드) 본다.
// 라운드마다 회원 한 명을 쓰고, 모든 VU가 같은 시각까지 기다렸다가 함께 보낸다.
import exec from 'k6/execution';
import { fail, sleep } from 'k6';
import { enter, entryEventOf, positiveInt, uuid } from './lib/api.js';
import { tokenFactory } from './lib/token.js';

const FIRST = positiveInt('RACE_FIRST_MEMBER_ID');
const ROUNDS = positiveInt('ROUNDS', 20);
const CONCURRENCY = positiveInt('CONCURRENCY', 50);
const ROUND_GAP_MS = positiveInt('ROUND_GAP_MS', 1000);
const EVENT_ID = entryEventOf(1);

const sign = tokenFactory(__ENV.JWT_SECRET || '', 3600);
const tokens = Array.from({ length: ROUNDS * 2 }, (_, i) => sign(FIRST + i));

export const options = {
  scenarios: {
    race: { executor: 'per-vu-iterations', vus: CONCURRENCY, iterations: ROUNDS * 2, maxDuration: '10m' },
  },
};

// 잔액 키가 없는 회원에게 요청이 몰리면 적재 락을 못 잡은 요청이 거절된다. 회원마다 1건씩 먼저 보낸다.
export function setup() {
  for (let i = 0; i < ROUNDS * 2; i += 1) {
    const { code } = enter(FIRST + i, EVENT_ID, { phase: 'race_warm' }, { token: tokens[i] });
    if (code !== 'SUCCESS') {
      fail(`동시 요청 예열 실패: memberId=${FIRST + i} code=${code}`);
    }
  }
  return { startAt: Date.now() + 2000, sameRequestIds: Array.from({ length: ROUNDS }, () => uuid()) };
}

export default function (data) {
  const round = exec.vu.iterationInScenario;
  const wait = data.startAt + round * ROUND_GAP_MS - Date.now();
  if (wait > 0) {
    sleep(wait / 1000);
  }
  const same = round < ROUNDS;
  const options = same ? { requestId: data.sameRequestIds[round], token: tokens[round] } : { token: tokens[round] };
  enter(FIRST + round, EVENT_ID, { phase: same ? 'race_same' : 'race_over', round: String(round) }, options);
}
