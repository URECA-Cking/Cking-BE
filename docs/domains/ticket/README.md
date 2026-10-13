# Ticket 도메인

Ticket 도메인은 Creator별 사용자 응모권 잔액과 append-only Ledger를 관리한다. Redis 잔액은 빠른 처리용 사본이고, DB Balance와 Ledger가 운영 보정의 기준이다.

## 문서

- [외부 API](api.md): 잔액·Ledger 이력 조회 및 관리자 보정 API 계약
- [EARN Lua 원자 처리](lua-api.md): 미션 적립의 멱등성·일일 중복 방지·Stream 발행 계약, Quiz 보상 Lua

## EARN과 Stream 반영

미션 계층은 일일 보상에는 같은 JVM의 `TicketEarnService.earn(EarnCommand)`를, 평생 1회 보상에는 `TicketOnceEarnService.earn(EarnCommand)`를 호출한다. Lua가 Redis 잔액 증가와 `stream:ticket-earned` 발행을 처리하고, Consumer가 EARN Ledger와 DB Balance를 저장한다. 외부 HTTP 내부 호출은 사용하지 않는다.

### 평생 1회 보상 ONCE EARN 목표 계약

기존 `TicketEarnService`의 일일 경로는 25시간 TTL을 사용하므로 평생 1회 보상의 재시도 계약으로 사용할 수 없다. SHARE와 `YOUTUBE_SUBSCRIPTION` 승인 보상은 별도 `TicketOnceEarnService`를 사용한다. 상세 호출·상태 계약은 [YouTube 구독 인증 정본](../subscription-verification/README.md#ticket-once-적립의-영구-멱등성)을 따른다.

- Ticket 도메인은 모든 EARN의 `request_id`를 `ticket_earn_request` durable request로 Redis 실행 전에 전역 선점한다. DAILY·ONCE가 같은 requestId를 서로 다른 Redis namespace에서 각각 처리하지 않도록 `request_id` UNIQUE와 `PENDING → ACCEPTED`를 기록하며, 정책까지 포함한 요청 내용이 달라지면 `REQUEST_ID_CONFLICT`로 차단한다.
- 평생 1회 보상은 추가로 `ticket_once_earn_request` durable request를 소유한다. `request_id`와 `(member_id, creator_id, mission_id)`를 각각 UNIQUE로 두고 `PENDING → ACCEPTED`를 기록한다. `periodKey`는 최초 claim 값으로 보존하되, 서버 파생값이므로 request payload fingerprint에는 포함하지 않는다.
- ONCE Lua는 `idem:mission-once:{requestId}`와 `mission:earn-guard:{userId}:{missionType}:{creatorId}:once`를 TTL 없이 선점한 뒤 Redis Balance 증가와 기존 EARN Stream 발행을 원자 처리한다.
- ONCE key는 TTL을 설정하거나 성공 후 삭제하지 않는다. 모든 재호출은 Redis 실행 전에 durable request를 조회하고, 이미 DB 조회를 통과한 동시 실행은 영구 Redis key가 방어한다.
- 기존 EARN Stream Consumer, `mission_completion`, Ledger와 DB Balance 반영은 재사용한다. DB Consumer의 `request_id` UNIQUE는 Redis Balance 중복 증가 방어를 대신하지 않는다.
- `TicketOnceEarnService`는 전역 request claim과 ONCE durable request를 Redis 실행 전에 먼저 claim하고, 둘 다 `PENDING`이면 ONCE Lua를 실행한다. Redis 수락 뒤에는 별도 짧은 Transaction으로 `ACCEPTED`를 기록한다. 따라서 Redis Guard가 유실된 뒤 다른 `requestId`가 와도 DB Business Key가 Redis 잔액 증가 전에 차단한다. Stream에는 정책을 함께 기록하며, DB 완료 키도 DAILY는 UTC 날짜·ONCE는 고정값으로 분리한다.

## Quiz 보상 EARN(이슈 #533)

Fan Quiz 응시(attempt) 보상은 Mission과 다른 적립 원인이다. 정답 1개당 해당 Creator 응모권 1장이며, 가짜 Mission 완료를 만들지 않고 `ticket_quiz_reward_request`를 원장 원인(`ticket_ledger.quiz_reward_request_id`)으로 남긴다. Quiz 도메인은 아래 Ticket 내부 계약만 호출하고 Ticket 테이블·Redis 키를 직접 다루지 않는다. 공개 API는 없다.

- **입력**: `QuizRewardCommand(attemptId, memberId, creatorId, correctCount, rewardRequestId)`. 서버가 채점한 값이며 `rewardRequestId`는 attempt마다 한 번 만들어 재시도·복구에서도 바꾸지 않는다. `correctCount=0`이면 호출하지 않고, Ticket도 0 이하를 거부한다.
- **접수** `TicketQuizRewardService.admit(command)`: 전역 `ticket_earn_request`(reward_policy=`QUIZ`)와 Quiz 요청(`RECEIVED`)을 한 Transaction에서 만든다. 같은 rewardRequestId·같은 입력은 `REPLAYED`, 다른 입력이나 다른 원인이 쓴 UUID는 `REQUEST_ID_CONFLICT`, 같은 attempt의 다른 rewardRequestId는 `ATTEMPT_CONFLICT`다.
- **실행** `QuizRewardEarnWorker.run(rewardRequestId)`: 접수가 커밋된 뒤 따로 호출한다. 둘 다 Transaction 밖에서만 호출할 수 있다. 실행권(`ticket_quiz_reward_execution`의 execution token)을 DB에 먼저 커밋한 뒤 `quiz-ticket-earn.lua`를 한 번 부르고, 별도 Transaction에서 결과를 기록한다. 실행권은 `RECEIVED`이거나 직전 실행이 무반영으로 확인된 `RETRYABLE_FAILURE`일 때만 발급하므로 실행 중·수락·UNKNOWN·반영 완료 요청은 다시 실행하지 않는다.
- **반영**: Lua가 기존 `stream:ticket-earned`에 `sourceType=QUIZ`, `payloadVersion=1` 메시지를 발행하고, 기존 Consumer·PEL 재처리·Dead replay가 `TicketEarnEventDispatcher`로 `QuizRewardLedgerService`에 넘긴다. 메시지와 저장된 요청·fingerprint를 대조하고, 실행권이 이 요청에 저장된 실행이며 무반영 기록이 아닐 때만(`StoredExecutionQuizRewardExecutionPolicy`) 원장·DB 잔액·`APPLIED`를 한 Transaction에 남긴다. 같은 요청은 원장 한 줄이다.
- **상태 조회** `TicketQuizRewardService.findStatus(rewardRequestId)`: 요청 상태만 돌려준다. `APPLIED`여야 원장·잔액 반영이 끝난 것이다. 실행권·fingerprint는 노출하지 않는다.

| 상태 | 의미 |
| --- | --- |
| `RECEIVED` | 접수만 했다 |
| `EXECUTING` | 실행권을 발급했고 Redis 결과를 아직 기록하지 않았다 |
| `ACCEPTED` | Redis가 적립·발행했다. 원장 반영 전이다 |
| `RETRYABLE_FAILURE` | Redis를 바꾸지 않은 것이 확인됐다. 새 실행권으로 다시 실행할 수 있다 |
| `UNKNOWN` | 적립 여부를 확정할 수 없다. 자동으로 다시 실행하지 않는다. 그 실행권의 메시지가 오면 반영된다 |
| `APPLIED` | 원장·DB 잔액에 반영했다. 최종 상태다 |

**기본 비활성**: `cking.ticket.quiz-reward.worker-enabled`의 기본값은 `false`이며, 꺼져 있으면 Worker는 아무것도 바꾸지 않는다. 호출 주체는 Fan Quiz 응시(`QuizRewardDelivery`, 이슈 #543)이며, 응시 저장 커밋 뒤 전달과 `QuizRewardDeliveryScheduler`의 누락 재전달을 같은 설정으로 켠다([응시 API](../quiz/attempt-api.md#ticket-보상-지급이슈-543)).

**장애 시 차단**: Redis 응답 유실·형식 오류는 `UNKNOWN`으로 두고 재실행하지 않는다. 결과 기록이 실패하면 요청이 `EXECUTING`으로 남아 새 실행권이 발급되지 않는다. Redis 잔액 키가 없으면 같은 회원·Creator에 원장으로 확정되지 않은 Quiz 지급(다른 요청의 `EXECUTING`·`ACCEPTED`·`UNKNOWN`, 무반영이 확인되지 않은 과거 실행)이 없을 때만 `TicketBalanceKeyLoader`로 적재하고, 아니면 Lua를 부르지 않고 `RETRYABLE_FAILURE`로 기록한다. 메시지가 오지 않는 `UNKNOWN`·멈춘 `EXECUTING`의 복구와 Redis 유실 복구는 아직 없으며 운영자 확인이 필요하다.

## 정합성 검증과 수동 보정

관리자는 `GET /api/admin/tickets/balance`로 CREATOR·COMMON 응모권의 Redis와 MySQL 잔액을 직접 관측할 수 있다. 키·행 미존재와 조회 실패, 차이 계산의 계약은 [Ticket API](api.md#get-apiadminticketsbalance)를 따른다. 조회는 보정을 수행하지 않는다.

`TicketBalanceReconciliationScheduler`는 기본 5분마다 DB Balance와 Redis `ticket:balance:{creatorId}:{userId}`를 비교한다. 최초 불일치는 비동기 반영 지연일 수 있으므로 info로 기록하고, 같은 조합이 2회 연속 불일치할 때만 운영자 확인이 필요한 warning을 남긴다. Redis 키가 없는데 DB 잔액이 0보다 크면 키 유실로 보고 즉시 warning을 남긴다(DB 행은 EARN이 Redis 적립에 성공한 뒤에야 생기므로 정상 상태가 아니다). Redis 통신 장애면 이번 주기를 중단하며 자동 보정하지 않는다.

### 키 유실 시 자동 적재(FR-P2-056, 이슈 #275)

SPEND가 `BALANCE_NOT_LOADED`를 받으면(Redis 잔액 키 부재 - 한 번도 받은 적 없는 사용자와 키가 유실된 사용자 모두 포함) `TicketBalanceKeyLoader`가 그 요청 안에서 DB 잔액으로 즉시 적재를 시도하고, 성공하면 같은 요청을 1회만 재실행한다. 적재는 보정 락(`TicketMaintenanceLock`)을 잡고 해당 `(memberId, creatorId)`에 미반영 SPEND·EARN 메시지가 없을 때만 이뤄지며, 락 토큰 확인과 `SET NX`를 원자 처리해 검사 도중 락이 만료돼도 소유권 없이 쓰지 않는다. 다음 중 하나라도 해당하면 적재하지 않고 기존처럼 `BALANCE_NOT_LOADED`(503)로 남아 `TicketCompensationService.resyncRedisToDb`(수동 보정)가 필요하다: 락을 못 잡음(다른 보정 진행 중), 미반영 메시지가 있음, 검사 도중 락 만료, Redis·DB 저장소 오류(이 경우도 응모를 500으로 키우지 않고 503으로 남긴다).

### 수동 보정의 안전장치

SPEND·EARN은 Redis 잔액을 먼저 바꾸고 Consumer가 DB에 나중에 반영하므로, 미반영 메시지가 남은 채 DB 값으로 덮어쓰면 응모권이 되돌아가거나(SPEND) 적립분이 사라진다(EARN). 그래서 수동 보정(`TicketCompensationService.resyncRedisToDb()`)은 다음 순서로 동작한다.

1. `ticket:maint:{creatorId}:{userId}` maintenance lock을 token으로 획득한다(lease 60초, 연장 없음). 이미 잡혀 있으면 `CONCURRENT_COMMAND`(409)로 거부한다.
2. 해당 `(memberId, creatorId)`의 미반영 메시지가 하나라도 있으면 `INVALID_STATE`(409)로 거부한다. 확인 대상은 SPEND·EARN 두 Stream의 PEL, Consumer Group이 아직 읽지 않은 메시지, 미해결 Dead Stream이다. Dead Stream은 EARN 행에 `event_id`가 없어 `payload`의 `userId`·`creatorId`로 판별한다. Consumer가 DB에 커밋했지만 XACK하기 전인 메시지도 PEL에 남아 거부될 수 있으며, 잠시 뒤 다시 시도하면 된다.
3. DB 트랜잭션에서 잔액 행을 잠그고 `COMPENSATE` Ledger를 남긴 뒤, lock을 아직 소유한 경우에만 Redis를 덮어쓴다. lock이 만료됐으면 덮어쓰지 않고 트랜잭션을 롤백한다.
4. 성공·실패와 무관하게 token이 같을 때만 lock을 해제한다.

SPEND·EARN Lua도 같은 lock을 확인해 lock이 걸린 동안 새 차감·적립을 `BALANCE_MAINTENANCE`(HTTP 503)로 거부하므로([lua-api.md](lua-api.md) 참고, issue #172), 1~4번과 함께 조회부터 덮어쓰기까지 사이에 들어오는 새 SPEND·EARN도 막는다. lock 키는 `TicketRedisKeys.maintenance(creatorId, userId)`를 Lua와 보정 서비스가 함께 쓴다.

지속 불일치가 운영자 확인으로 확정됐을 때만 `TicketCompensationService.resyncRedisToDb()`를 호출한다. 이 서비스는 기존 Ledger를 수정하지 않고 `COMPENSATE` Ledger를 append-only로 남긴 뒤 Redis를 DB 값으로 재동기화한다. Redis 키가 없으면 DB 값을 쓰되 기준이 없으므로 delta 0의 감사 Ledger를 남긴다.

### 호출 진입점(#207)

`resyncRedisToDb()`는 서비스 메서드라 직접 호출할 외부 API가 없었다(#178 Dead Stream 관리자 API와 같은 상황). `POST /api/admin/tickets/resync`는 Bearer Access JWT의 `@CurrentMemberId`를 관리자 업무 식별자로 사용하고, Body의 대상 `memberId`, `creatorId`, `reason`을 `TicketAdminService.resync()`에 전달한다. Security의 ADMIN 1차 인가 뒤에도 Service의 ADMIN 검증을 유지한다. 대상 Balance가 없으면 `RESOURCE_NOT_FOUND`(404), lock 충돌은 `CONCURRENT_COMMAND`(409), 미반영 메시지가 있으면 `INVALID_STATE`(409)를 그대로 응답하며, 성공하면 재동기화 후 현재 잔액을 반환한다.

## 공용 응모권(크리에이터 무관, 이슈 #219)

사용자는 크리에이터별 응모권과 별개로 아무 크리에이터에게나 쓸 수 있는 공용 응모권을 가진다. `user_ticket_balance`/`ticket_ledger`가 `creator_id NOT NULL`이라 이 개념을 담을 수 없어, 크리에이터 축만 뺀 병렬 테이블·경로로 분리했다.

- **저장**: `user_common_ticket_balance`(PK `member_id`만), `common_ticket_ledger`.
- **EARN**: `CommonTicketEarnService`/`CommonTicketEarnServiceImpl`이 `ticket_earn_request`의 전역 requestId claim 뒤 `common-ticket-earn.lua`로 `TicketEarnService`와 동일한 원자성(멱등성 확인 → 중복 적립 가드 → Balance 증가 → Stream 발행)을 재현한다. 따라서 Common·Creator EARN 사이에서도 같은 requestId는 `REQUEST_ID_CONFLICT`로 차단한다. Redis 키에 `creatorId` 자리가 없다(`ticket:balance:common:{userId}`, `mission:earn-guard:common:{userId}:{missionType}:{yyyymmdd}`, `idem:common-mission:{requestId}`).
- **Stream**: `stream:common-ticket-earned`를 `cg:common-ticket-earn` Consumer Group으로 소비한다(`CommonEarnStreamListener`/`CommonEarnStreamConfig`). `CommonMissionEarnLedgerService`가 `common_mission_completion` → `common_ticket_ledger` → `user_common_ticket_balance` 반영을 한 트랜잭션으로 묶는다(`TicketEarnLedgerService`와 동일 계약).
- **PEL 회수·Dead Stream 이관**(이슈 #244): 반영에 실패해 PEL에 남은 메시지는 `CommonEarnStreamPelRecoveryScheduler`가 XCLAIM으로 회수해 `CommonEarnStreamListener.process()`로 재처리한다(consumer `common-earn-pel-recovery`, 기본 30초 주기·idle 60초 이상). 최대 재시도(기본 5회, `common-earn-pel-max-retry`)를 넘기면 `EarnStreamPelRecoveryScheduler`(크리에이터 EARN)와 동일하게 `dead_stream_message`(`stream_type = COMMON_EARN`)로 옮기고 ACK한다 - 영구히 PEL에 남기지 않는다. 절대 성공할 수 없는 메시지가 XPENDING을 계속 채우던 이전 구조(더 긴 간격 재시도·페이지네이션)는 이관이 생기면서 필요 없어져 제거했다.
- **관리자 조회·replay**: `GET /api/admin/dead-streams`, `POST /api/admin/dead-streams/{id}/replay`(`DeadStreamAdminController`)를 EARN/SPEND와 동일하게 그대로 쓴다 - `streamType`이 `COMMON_EARN`으로 나온다. `DeadStreamReplayService`가 `CommonMissionEarnLedgerService.apply()`로 재적용한다(requestId 멱등이라 반복 replay해도 안전).
- **조회**: `GET /api/tickets/common`, `GET /api/tickets/common/history`(`CommonTicketQueryController`) — `GET /api/creators/{creatorId}/tickets`류와 동일한 커서 페이지네이션.
- **정합성 검증·수동 보정(이슈 #256)**: `TicketBalanceReconciliationScheduler`가 같은 5분 주기에 `user_common_ticket_balance` vs `ticket:balance:common:{userId}`도 비교한다(연속 2주기 불일치·키 유실 판정 기준은 크리에이터 잔액과 동일). 복구는 `CommonTicketCompensationService.resyncRedisToDb(memberId, reason)`이 하며, 크리에이터 축이 없다는 점만 빼면 `TicketCompensationService`와 계약이 같다: `TicketMaintenanceLock.acquireCommon(memberId)`으로 `ticket:maint:common:{userId}` lock을 잡고(이미 잡혀 있으면 `CONCURRENT_COMMAND`), `UnappliedBalanceMessageChecker.existsCommon(memberId)`로 공용 SPEND·EARN Stream과 공용 Dead Stream의 미반영 메시지를 확인한 뒤(있으면 `INVALID_STATE`) `COMPENSATE` Ledger를 append하고 lock을 쥔 동안만 Redis를 덮어쓴다. `common-ticket-earn.lua`와 `entry-spend.lua`(`couponType=COMMON`) 양쪽 모두 이 lock 키를 `EXISTS`로 확인해 걸려 있는 동안 새 공용 EARN·SPEND를 `BALANCE_MAINTENANCE`(HTTP 503)로 거부한다 — [응모 Lua API](../event/lua-api.md#coupontype-이슈-243) 참고.
- **호출 진입점**: `POST /api/admin/tickets/common/resync`(`TicketAdminController.resyncCommon`)가 위 보정을 트리거한다. 요청·응답 필드와 오류 코드는 [외부 API](api.md#post-apiadminticketscommonresync) 참고.
- **호출 측**: 공용 미션 완료는 `kr.co.cking.mission.application.CommonMissionCompletionService`가 담당한다([Mission 도메인](../mission/README.md#공용-미션크리에이터-무관-이슈-219) 참고). 응모(SPEND)에서 공용 응모권으로 크리에이터 이벤트에 응모하는 경로는 이슈 #243으로 구현됐다 — 요청의 `couponType=COMMON`이면 `EntrySpendServiceImpl`이 크리에이터 잔액 대신 이 문서의 공용 잔액을 검증·차감하고, `TicketSpendLedgerService`가 `common_ticket_ledger`/`user_common_ticket_balance`에 반영한다. 자세한 계약은 [응모 API](../event/entry-api.md), [응모 Lua API](../event/lua-api.md#coupontype-이슈-243) 참고.
