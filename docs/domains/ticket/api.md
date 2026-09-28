# Ticket API

모든 응답은 [공통 API 규약](../../common/api.md)의 봉투를 사용한다. 모든 API는 Bearer Access JWT가
필수이며, 호출자는 `@CurrentMemberId`로 식별한다. query `userId`는 받지 않는다.

## GET /api/creators/{creatorId}/tickets

Creator별 요청 사용자의 현재 응모권 잔액을 조회한다.

```json
{
  "userId": 1,
  "creatorId": 2,
  "balance": 5,
  "updatedAt": "2026-09-18T00:00:00Z"
}
```

## GET /api/creators/{creatorId}/tickets/history

Query: 선택 `cursor`, 선택 `size`. `size` 기본값은 20이고 허용 범위는 1~100이다. Ledger는 `createdAt DESC, ledgerId DESC` 순서로 조회하며, 다음 페이지 cursor는 마지막 항목의 `(createdAt, ledgerId)`를 URL-safe Base64로 인코딩한다.

```json
{
  "userId": 1,
  "creatorId": 2,
  "items": [{
    "ledgerId": 10,
    "deltaAmount": -3,
    "type": "SPEND",
    "missionId": null,
    "eventId": 5,
    "reason": null,
    "requestId": "550e8400-e29b-41d4-a716-446655440000",
    "createdAt": "2026-09-18T00:00:00Z"
  }],
  "nextCursor": null,
  "hasNext": false
}
```

cursor 형식이 올바르지 않거나 `size`가 범위를 벗어나면 `VALIDATION_FAILED`다.

## POST /api/admin/tickets/common/resync

- 권한: `ADMIN`
- 정합성 배치(`TicketBalanceReconciliationScheduler`)가 공용 응모권의 지속 불일치나 Redis 키 유실을 알렸을 때, 운영자가 대상 사용자의 공용 잔액을 DB 기준으로 수동 재동기화하는 API다(이슈 #256, `TicketAdminController.resyncCommon` → `TicketAdminService.resyncCommon` → `CommonTicketCompensationService.resyncRedisToDb`).
- 호출자 식별은 Access JWT의 `@CurrentMemberId`이며, Body의 `memberId`는 보정 대상이지 호출자가 아니다. Spring Security의 `ADMIN` 1차 인가 뒤에도 Service에서 호출자의 `member.role == ADMIN`을 재검증한다.
- `ticket:maint:common:{memberId}` maintenance lock을 획득한 뒤 공용 SPEND·EARN Stream의 PEL·미처리 메시지·미해결 공용 Dead Stream을 확인하고, 없을 때만 `COMPENSATE` Ledger를 append한 뒤 Redis를 DB 값으로 덮어쓴다. lock이 걸린 동안에는 `common-ticket-earn.lua`·`entry-spend.lua`(`couponType=COMMON`)가 신규 공용 EARN·SPEND를 `BALANCE_MAINTENANCE`(HTTP 503)로 거부한다.
- 크리에이터별 잔액용 `POST /api/admin/tickets/resync`와 계약은 같되 `creatorId`가 없다. 기존 `/resync` 응답 계약을 바꾸지 않으려고 별도 엔드포인트로 분리했다.

```json
{ "memberId": 1, "reason": "정합성 배치 지속 불일치 확인 후 수동 보정" }
```

성공 시 재동기화 후 현재 공용 잔액을 반환한다.

```json
{
  "code": "SUCCESS",
  "data": {
    "userId": 1,
    "balance": 10,
    "updatedAt": "2026-09-28T00:00:00Z"
  },
  "message": null
}
```

| 코드 | 조건 |
| --- | --- |
| `VALIDATION_FAILED` | `memberId`가 누락·0 이하이거나 `reason`이 공백이거나 500자를 초과함 |
| `RESOURCE_NOT_FOUND` | 호출자 Member가 존재하지 않거나(JWT), 대상 `memberId`의 공용 Balance(`user_common_ticket_balance`) 행이 없음 |
| `FORBIDDEN` | 호출자의 역할이 ADMIN이 아님 |
| `CONCURRENT_COMMAND` | 동일 `memberId`의 공용 보정이 이미 진행 중(maintenance lock 충돌) |
| `INVALID_STATE` | 대상 `memberId`의 공용 SPEND·EARN Stream에 미반영 메시지가 있거나 미해결 공용 Dead Stream이 있어 보정할 수 없음 |

## GET /api/tickets/common

크리에이터 무관 공용 응모권 잔액을 조회한다(이슈 #219).

```json
{
  "userId": 1,
  "balance": 5,
  "updatedAt": "2026-09-18T00:00:00Z"
}
```

## GET /api/tickets/common/history

Query: 선택 `cursor`, 선택 `size`(기본 20, 1~100). 형식은 `GET /api/creators/{creatorId}/tickets/history`와 같되 `creatorId`가 없다.

```json
{
  "userId": 1,
  "items": [{
    "ledgerId": 10,
    "deltaAmount": 1,
    "type": "EARN",
    "missionId": 100,
    "eventId": null,
    "reason": null,
    "requestId": "550e8400-e29b-41d4-a716-446655440000",
    "createdAt": "2026-09-18T00:00:00Z"
  }],
  "nextCursor": null,
  "hasNext": false
}
```
