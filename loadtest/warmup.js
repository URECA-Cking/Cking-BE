// 예열: 회원 × 크리에이터 조합마다 응모를 한 번씩 보내 Redis 잔액을 DB에서 채운다.
// 첫 요청은 잔액을 적재하고 다시 실행해 느리므로 측정값에서 뺀다.
import exec from 'k6/execution';
import { CREATORS, MEMBERS, enter, entryEventOf, pairOf, positiveInt } from './lib/api.js';

const PAIRS = CREATORS * MEMBERS;

export const options = {
  scenarios: {
    warmup: {
      executor: 'shared-iterations',
      vus: positiveInt('VUS', 20),
      iterations: PAIRS,
      maxDuration: __ENV.MAX_DURATION || '30m',
    },
  },
};

export default function () {
  const { memberId, creatorId } = pairOf(exec.scenario.iterationInTest);
  enter(memberId, entryEventOf(creatorId), { phase: 'warmup' });
}
