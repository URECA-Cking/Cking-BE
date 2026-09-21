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
