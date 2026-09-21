# 응모 API

이벤트 응모와 내 응모 내역 조회 계약이다. 응모의 Event 개방 여부·잔액·멱등성·차감·Stream 발행은 `entry-spend.lua`가 원자적으로 처리하며, Redis 계약은 [응모 Lua API](lua-api.md)에 있다.

## POST /api/events/{eventId}/entries

```json
{
  "userId": 1,
  "requestId": "550e8400-e29b-41d4-a716-446655440000",
  "ticketCount": 3
}
```

`userId`, UUID 형식 `requestId`, 1~100 범위의 정수 `ticketCount`가 필수다. Controller는 형식과
범위만 검증하고, Event 개방 여부·잔액·멱등성·차감·Stream 발행은 `entry-spend.lua`가 원자적으로
처리한다. 자세한 Redis 계약은 [응모 Lua API](lua-api.md)를 따른다.

같은 `requestId`와 동일한 요청은 기존 성공 결과를 재현한다. 같은 `requestId`에 다른 요청 본문을
보내면 `IDEMPOTENCY_CONFLICT`다. 성공 응답 `data`는 `requestId`, `eventId`, `accepted: true`를
포함한다. Lua 결과의 실패 코드는 HTTP 상태와 함께 공통 응답 봉투로 반환한다.

### 결과코드와 HTTP 상태

Lua 결과코드 10종은 `EntryErrorCode`가 HTTP 상태로 매핑한다. 취합 v1.5.4 §5.4가 "구체 매핑값은 구현 시 확정"으로 남긴 값이며, 아래 표가 확정본이다. 응답 본문은 공통 응답 봉투를 따른다.

| 결과코드 | HTTP | 의미 | 클라이언트 처리 |
| --- | --- | --- | --- |
| `SUCCESS` | 200 | 이번 요청에서 차감과 Stream 발행 완료 | - |
| `DUPLICATE_REPLAY` | 200 | 동일 `requestId`의 기존 성공 결과 재사용. 실패가 아니며 `SUCCESS`와 같은 응답 스키마 | - |
| `INVALID_TICKET_COUNT` | 400 | Lua가 방어적으로 거른 잘못된 응모 수량 | 요청 수정 |
| `EVENT_NOT_OPEN` | 409 | OPEN 이전 등 응모 가능한 상태가 아님 | 시작 후 재시도 |
| `EVENT_CLOSED` | 409 | 마감 이후. `EVENT_NOT_OPEN`과 구분한다 | 재시도 무의미 |
| `INSUFFICIENT_BALANCE` | 409 | 응모권 잔액 부족 | 잔액 확인 |
| `IDEMPOTENCY_CONFLICT` | 409 | 동일 `requestId`에 다른 요청 내용 | 새 `requestId` |
| `GATE_NOT_LOADED` | 503 | Gate 키 미적재. OPEN으로 간주하지 않는다 | 잠시 후 재시도 |
| `BALANCE_NOT_LOADED` | 503 | 잔액 키 미적재. 0으로 간주하지 않는다 | 잠시 후 재시도 |
| `SYSTEM_ERROR` | 500 | 내부 오류. Redis 타임아웃도 포함 | 동일 `requestId`로 재시도 |

1~100 범위를 벗어난 `ticketCount`나 UUID 형식이 아닌 `requestId`는 Lua에 도달하기 전에 Controller가 공통 `VALIDATION_FAILED`(400)로 거절한다.

`GATE_NOT_LOADED`·`BALANCE_NOT_LOADED`·`SYSTEM_ERROR`는 동일 `requestId`로 재시도해도 이중 차감이 없다. 재시도 결과와 타임아웃 처리는 [응모 Lua API](lua-api.md#redis-타임아웃과-system_error)를 따른다.

## GET /api/events/{eventId}/entries/me

USER가 자신의 Event 응모 내역을 조회한다. Query는 필수 `userId`, 선택 `cursor`, `size`를 사용한다.
`size` 기본값은 20이고 허용 범위는 1~100이다. 응모 내역은 `appliedAt DESC, entryId DESC`로 정렬하며,
다음 페이지 커서는 마지막 항목의 `(appliedAt, entryId)`를 URL-safe Base64로 인코딩한다.

```json
{
  "items": [{
    "entryId": 10,
    "usedTicketCount": 3,
    "appliedAt": "2026-09-18T02:00:00Z"
  }],
  "nextCursor": null,
  "hasNext": false
}
```

조회 조건은 `event_entry.member_id = userId`와 `event_entry.event_id = eventId`를 모두 사용한다. 없는 Member 또는
존재하지 않거나 삭제된 Event는 `RESOURCE_NOT_FOUND`, 식별자·size 범위·cursor 형식 오류는
`VALIDATION_FAILED`다.
