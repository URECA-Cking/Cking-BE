# Ticket 도메인

Ticket 도메인은 Creator별 사용자 응모권 잔액과 append-only Ledger를 관리한다. Redis 잔액은 빠른 처리용 사본이고, DB Balance와 Ledger가 운영 보정의 기준이다.

## 문서

- [외부 조회 API](api.md): 잔액과 Ledger 이력 조회 계약
- [EARN Lua 원자 처리](lua-api.md): 미션 적립의 멱등성·일일 중복 방지·Stream 발행 계약

## EARN과 Stream 반영

미션 계층은 같은 JVM의 `TicketEarnService.earn(EarnCommand)`를 호출한다. Lua가 Redis 잔액 증가와 `stream:ticket-earned` 발행을 처리하고, Consumer가 EARN Ledger와 DB Balance를 저장한다. 외부 HTTP 내부 호출은 사용하지 않는다.

## 정합성 검증과 수동 보정

`TicketBalanceReconciliationScheduler`는 기본 5분마다 DB Balance와 Redis `ticket:balance:{creatorId}:{userId}`를 비교한다. 최초 불일치는 비동기 반영 지연일 수 있으므로 info로 기록하고, 같은 조합이 2회 연속 불일치할 때만 운영자 확인이 필요한 warning을 남긴다. Redis 키가 없는데 DB 잔액이 0보다 크면 키 유실로 보고 즉시 warning을 남긴다(DB 행은 EARN이 Redis 적립에 성공한 뒤에야 생기므로 정상 상태가 아니며, 이 유저는 SPEND에서 `BALANCE_NOT_LOADED`를 받는다). 복구는 `TicketCompensationService.resyncRedisToDb`로 한다. Redis 통신 장애면 이번 주기를 중단하며 자동 보정하지 않는다.

### 수동 보정의 안전장치

SPEND·EARN은 Redis 잔액을 먼저 바꾸고 Consumer가 DB에 나중에 반영하므로, 미반영 메시지가 남은 채 DB 값으로 덮어쓰면 응모권이 되돌아가거나(SPEND) 적립분이 사라진다(EARN). 그래서 수동 보정(`TicketCompensationService.resyncRedisToDb()`)은 다음 순서로 동작한다.

1. `ticket:maint:{creatorId}:{userId}` maintenance lock을 token으로 획득한다(lease 60초, 연장 없음). 이미 잡혀 있으면 `CONCURRENT_COMMAND`(409)로 거부한다.
2. 해당 `(memberId, creatorId)`의 미반영 메시지가 하나라도 있으면 `INVALID_STATE`(409)로 거부한다. 확인 대상은 SPEND·EARN 두 Stream의 PEL, Consumer Group이 아직 읽지 않은 메시지, 미해결 Dead Stream이다. Dead Stream은 EARN 행에 `event_id`가 없어 `payload`의 `userId`·`creatorId`로 판별한다. Consumer가 DB에 커밋했지만 XACK하기 전인 메시지도 PEL에 남아 거부될 수 있으며, 잠시 뒤 다시 시도하면 된다.
3. DB 트랜잭션에서 잔액 행을 잠그고 `COMPENSATE` Ledger를 남긴 뒤, lock을 아직 소유한 경우에만 Redis를 덮어쓴다. lock이 만료됐으면 덮어쓰지 않고 트랜잭션을 롤백한다.
4. 성공·실패와 무관하게 token이 같을 때만 lock을 해제한다.

SPEND·EARN Lua도 같은 lock을 확인해 lock이 걸린 동안 새 차감·적립을 `BALANCE_MAINTENANCE`(HTTP 503)로 거부하므로([lua-api.md](lua-api.md) 참고, issue #172), 1~4번과 함께 조회부터 덮어쓰기까지 사이에 들어오는 새 SPEND·EARN도 막는다. lock 키는 `TicketRedisKeys.maintenance(creatorId, userId)`를 Lua와 보정 서비스가 함께 쓴다.

지속 불일치가 운영자 확인으로 확정됐을 때만 `TicketCompensationService.resyncRedisToDb()`를 호출한다. 이 서비스는 기존 Ledger를 수정하지 않고 `COMPENSATE` Ledger를 append-only로 남긴 뒤 Redis를 DB 값으로 재동기화한다. Redis 키가 없으면 DB 값을 쓰되 기준이 없으므로 delta 0의 감사 Ledger를 남긴다.

### 호출 진입점(#207)

`resyncRedisToDb()`는 서비스 메서드라 직접 호출할 외부 API가 없었다(#178 Dead Stream 관리자 API와 같은 상황). `POST /api/admin/tickets/resync`(`userId`(ADMIN), `memberId`, `creatorId`, `reason`)가 `TicketAdminService.resync()`를 통해 이 서비스를 호출한다. ADMIN 검증 후 대상 Balance가 없으면 `RESOURCE_NOT_FOUND`(404), lock 충돌은 `CONCURRENT_COMMAND`(409), 미반영 메시지가 있으면 `INVALID_STATE`(409)를 그대로 응답하며, 성공하면 재동기화 후 현재 잔액을 반환한다.

## 공용 응모권(크리에이터 무관, 이슈 #219)

사용자는 크리에이터별 응모권과 별개로 아무 크리에이터에게나 쓸 수 있는 공용 응모권을 가진다. `user_ticket_balance`/`ticket_ledger`가 `creator_id NOT NULL`이라 이 개념을 담을 수 없어, 크리에이터 축만 뺀 병렬 테이블·경로로 분리했다.

- **저장**: `user_common_ticket_balance`(PK `member_id`만), `common_ticket_ledger`.
- **EARN**: `CommonTicketEarnService`/`CommonTicketEarnServiceImpl`이 `common-ticket-earn.lua`로 `TicketEarnService`와 동일한 원자성(멱등성 확인 → 중복 적립 가드 → Balance 증가 → Stream 발행)을 재현한다. Redis 키에 `creatorId` 자리가 없다(`ticket:balance:common:{userId}`, `mission:earn-guard:common:{userId}:{missionType}:{yyyymmdd}`, `idem:common-mission:{requestId}`).
- **Stream**: `stream:common-ticket-earned`를 `cg:common-ticket-earn` Consumer Group으로 소비한다(`CommonEarnStreamListener`/`CommonEarnStreamConfig`). `CommonMissionEarnLedgerService`가 `common_mission_completion` → `common_ticket_ledger` → `user_common_ticket_balance` 반영을 한 트랜잭션으로 묶는다(`TicketEarnLedgerService`와 동일 계약).
- **PEL 회수**: 반영에 실패해 PEL에 남은 메시지는 `CommonEarnStreamPelRecoveryScheduler`가 XCLAIM으로 회수해 `CommonEarnStreamListener.process()`로 재처리한다(consumer `common-earn-pel-recovery`, 기본 30초 주기·idle 60초 이상).
  - 공용 EARN은 Dead Stream 이관을 아직 지원하지 않는다. 최대 재시도(기본 5회, `common-earn-pel-max-retry`)를 넘긴 메시지도 버리지 않고 PEL에 보존한 채 더 긴 간격(기본 10분, `common-earn-pel-over-limit-idle-ms`)으로 계속 재처리한다. 장시간 DB 장애로 한도를 넘긴 일시적 실패도 복구 후 반영되며, requestId 멱등이라 중복 반영은 없다.
  - 한도 초과 메시지는 간격이 지나기 전에는 XCLAIM하지 않는다(XCLAIM이 전달 횟수를 올리고 idle을 리셋하기 때문). 한도 초과 후 첫 재처리 때 WARN을 한 번 남긴다.
  - XPENDING은 페이지(기본 100건, 최대 10페이지)를 넘겨가며 본다. 한도 초과 메시지가 앞쪽에 쌓여도 뒤쪽 정상 메시지가 회수된다.
  - 절대 성공할 수 없는 메시지(fingerprint 불일치 등)는 PEL에 남아 간격마다 재시도·실패 로그가 반복된다. 확인은 `XPENDING stream:common-ticket-earned cg:common-ticket-earn`으로 한다.
- **조회**: `GET /api/tickets/common`, `GET /api/tickets/common/history`(`CommonTicketQueryController`) — `GET /api/creators/{creatorId}/tickets`류와 동일한 커서 페이지네이션.
- **알려진 제약(미해결)**: 공용 EARN의 Dead Stream 이관·replay(위 PEL 회수의 영구 실패 메시지 정리)와 잔액 수동 보정(`TicketCompensationService`/`ticket:maint:` maintenance lock 대응)이 아직 없다. `BALANCE_MAINTENANCE`도 아직 반환되지 않는다 — 후속 이슈로 남긴다.
- **호출 측**: 공용 미션 완료는 `kr.co.cking.mission.application.CommonMissionCompletionService`가 담당한다([Mission 도메인](../mission/README.md#공용-미션크리에이터-무관-이슈-219) 참고). 응모(SPEND)에서 공용 응모권을 크리에이터 이벤트에 쓰는 검증 로직은 이 이슈 범위 밖이며 별도(2조) 이슈로 진행한다.
