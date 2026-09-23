# Ticket 조회 API

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
