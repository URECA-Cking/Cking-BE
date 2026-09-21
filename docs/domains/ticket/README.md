# Ticket 도메인

Ticket 도메인은 Creator별 사용자 응모권 잔액과 append-only Ledger를 관리한다. Redis 잔액은 빠른 처리용 사본이고, DB Balance와 Ledger가 운영 보정의 기준이다.

## 문서

- [외부 조회 API](api.md): 잔액과 Ledger 이력 조회 계약
- [EARN Lua 원자 처리](lua-api.md): 미션 적립의 멱등성·일일 중복 방지·Stream 발행 계약

## EARN과 Stream 반영

미션 계층은 같은 JVM의 `TicketEarnService.earn(EarnCommand)`를 호출한다. Lua가 Redis 잔액 증가와 `stream:ticket-earned` 발행을 처리하고, Consumer가 EARN Ledger와 DB Balance를 저장한다. 외부 HTTP 내부 호출은 사용하지 않는다.

## 정합성 검증과 수동 보정

`TicketBalanceReconciliationScheduler`는 기본 5분마다 DB Balance와 Redis `ticket:balance:{creatorId}:{userId}`를 비교한다. 최초 불일치는 비동기 반영 지연일 수 있으므로 info로 기록하고, 같은 조합이 2회 연속 불일치할 때만 운영자 확인이 필요한 warning을 남긴다. Redis 통신 장애면 이번 주기를 중단하며 자동 보정하지 않는다.

지속 불일치가 운영자 확인으로 확정됐을 때만 `TicketCompensationService.resyncRedisToDb()`를 호출한다. 이 서비스는 기존 Ledger를 수정하지 않고 `COMPENSATE` Ledger를 append-only로 남긴 뒤 Redis를 DB 값으로 재동기화한다. Redis 키가 없으면 DB 값을 쓰되 기준이 없으므로 delta 0의 감사 Ledger를 남긴다.

### 보정 전 안전장치

SPEND·EARN은 Redis 잔액을 먼저 바꾸고 Consumer가 DB에 나중에 반영하므로, 미반영 메시지가 남은 채 DB 값으로 덮어쓰면 응모권이 되돌아가거나(SPEND) 적립분이 사라진다(EARN). 그래서 `resyncRedisToDb()`는 다음 순서로 동작한다.

1. `ticket:maint:{creatorId}:{userId}` maintenance lock을 token으로 획득한다(lease 60초, 연장 없음). 이미 잡혀 있으면 `CONCURRENT_COMMAND`(409)로 거부한다.
2. 해당 `(memberId, creatorId)`의 미반영 메시지가 하나라도 있으면 `INVALID_STATE`(409)로 거부한다. 확인 대상은 SPEND·EARN 두 Stream의 PEL, Consumer Group이 아직 읽지 않은 메시지, 미해결 Dead Stream이다. Dead Stream은 EARN 행에 `event_id`가 없어 `payload`의 `userId`·`creatorId`로 판별한다. Consumer가 DB에 커밋했지만 XACK하기 전인 메시지도 PEL에 남아 거부될 수 있으며, 잠시 뒤 다시 시도하면 된다.
3. DB 트랜잭션에서 잔액 행을 잠그고 `COMPENSATE` Ledger를 남긴 뒤, lock을 아직 소유한 경우에만 Redis를 덮어쓴다. lock이 만료됐으면 덮어쓰지 않고 트랜잭션을 롤백한다.
4. 성공·실패와 무관하게 token이 같을 때만 lock을 해제한다.

SPEND·EARN Lua가 lock을 확인해 새 요청을 막는 부분(`BALANCE_MAINTENANCE` 결과코드)은 #172에서 처리한다. 그 전에는 1~4번이 보정끼리의 동시 실행과 lock 만료만 막고, 조회와 덮어쓰기 사이에 들어오는 새 SPEND·EARN까지는 막지 못한다.
