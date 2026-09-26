# 응모 API

이벤트 응모와 내 응모 내역 조회 계약이다. 응모의 Event 개방 여부·잔액·멱등성·차감·Stream 발행은 `entry-spend.lua`가 원자적으로 처리하며, Redis 계약은 [응모 Lua API](lua-api.md)에 있다.

## POST /api/events/{eventId}/entries

```json
{
  "requestId": "550e8400-e29b-41d4-a716-446655440000",
  "ticketCount": 3,
  "couponType": "COMMON"
}
```

Bearer Access JWT가 필수이며 호출자는 `@CurrentMemberId`로 식별한다. UUID 형식 `requestId`, 1~100 범위의 정수 `ticketCount`가 필수다. `couponType`(`CREATOR`|
`COMMON`)은 선택이며 생략하면 `CREATOR`다 — **이벤트가 아니라 이 요청**이 어떤 응모권을 쓸지
정한다(이슈 #243). `CREATOR`는 그 이벤트 크리에이터 전용 잔액을, `COMMON`은 크리에이터 무관 공용
잔액(#219/#224)을 검증·차감한다. Controller는 형식과 범위만 검증하고, Event 개방 여부·잔액·
멱등성·차감·Stream 발행은 `entry-spend.lua`가 원자적으로 처리한다. 자세한 Redis 계약은
[응모 Lua API](lua-api.md#coupontype-이슈-243)를 따른다.

같은 `requestId`와 동일한 요청은 기존 성공 결과를 재현한다. 같은 `requestId`에 다른 요청 본문을
보내면 `IDEMPOTENCY_CONFLICT`다. 성공 응답 `data`는 `requestId`, `eventId`, `accepted: true`를
포함한다. Lua 결과의 실패 코드는 HTTP 상태와 함께 공통 응답 봉투로 반환한다.

### 결과코드와 HTTP 상태

Lua 결과코드 10종 + `BALANCE_MAINTENANCE`(issue #172) 중 실패 9종은 `EntryErrorCode`가 HTTP 상태로 매핑하고, 성공 2종(`SUCCESS`, `DUPLICATE_REPLAY`)은 Controller가 HTTP 200으로 응답한다. 취합 v1.5.4 §5.4가 "구체 매핑값은 구현 시 확정"으로 남긴 값이며, 아래 표가 확정본이다. 응답 본문은 공통 응답 봉투를 따른다.

| 결과코드 | HTTP | 의미 | 클라이언트 처리 |
| --- | --- | --- | --- |
| `SUCCESS` | 200 | 이번 요청에서 차감과 Stream 발행 완료 | - |
| `DUPLICATE_REPLAY` | 200 | 동일 `requestId`·동일 payload 재시도. 재차감 없음. 실패가 아니며 HTTP 응답은 `SUCCESS`와 같은 스키마(idem/guard 내부 경로 구분은 외부에 노출하지 않음) | - |
| `INVALID_TICKET_COUNT` | 400 | Lua가 방어적으로 거른 잘못된 응모 수량 | 요청 수정 |
| `EVENT_NOT_OPEN` | 409 | Gate 값이 `OPEN`이 아님. 마감 barrier가 Gate를 `CLOSED`로 바꾼 뒤(마감 진행 중·이후) 발생한다 | 재시도 무의미 |
| `EVENT_CLOSED` | 409 | Gate는 `OPEN`이지만 `endAt`을 지남(마감 배치가 Gate를 닫기 전) | 재시도 무의미 |
| `INSUFFICIENT_BALANCE` | 409 | 응모권 잔액 부족 | 잔액 확인 |
| `IDEMPOTENCY_CONFLICT` | 409 | 동일 `requestId`에 다른 요청 내용 | 새 `requestId` |
| `GATE_NOT_LOADED` | 503 | Gate 키 미적재(OPEN 전이 전이거나 Redis 유실). OPEN으로 간주하지 않는다 | 잠시 후 재시도 |
| `BALANCE_NOT_LOADED` | 503 | 잔액 키 미적재. 0으로 간주하지 않는다 | 잠시 후 재시도 |
| `BALANCE_MAINTENANCE` | 503 | 수동 보정(`TicketCompensationService.resyncRedisToDb()`) 락이 걸려 있음(issue #172) | 잠시 후 동일 `requestId`로 재시도 |
| `SYSTEM_ERROR` | 500 | 내부 오류. Redis 타임아웃도 포함 | 동일 `requestId`로 재시도 |

1~100 범위를 벗어난 `ticketCount`, UUID 형식이 아닌 `requestId`, `CREATOR`/`COMMON`이 아닌 `couponType`은 Lua에 도달하기 전에 Controller가 공통 `VALIDATION_FAILED`(400)로 거절한다.

`GATE_NOT_LOADED`·`BALANCE_NOT_LOADED`·`BALANCE_MAINTENANCE`·`SYSTEM_ERROR`는 동일 `requestId`로 재시도해도 이중 차감이 없다. 재시도 결과와 타임아웃 처리는 [응모 Lua API](lua-api.md#redis-타임아웃과-system_error)를 따른다.

## GET /api/events/{eventId}/entries/me

USER가 자신의 Event 응모 내역을 조회한다. Bearer Access JWT가 필수이며 호출자는 `@CurrentMemberId`로 식별한다. Query는 선택 `cursor`, `size`를 사용한다.
`size` 기본값은 20이고 허용 범위는 1~100이다. 응모 내역은 `appliedAt DESC, entryId DESC`로 정렬하며,
다음 페이지 커서는 마지막 항목의 `(appliedAt, entryId)`를 URL-safe Base64로 인코딩한다.

```json
{
  "items": [{
    "entryId": 10,
    "usedTicketCount": 3,
    "couponType": "COMMON",
    "appliedAt": "2026-09-18T02:00:00Z"
  }],
  "nextCursor": null,
  "hasNext": false
}
```

`couponType`은 응모에 쓴 응모권 종류(`CREATOR` | `COMMON`)다. `event_entry`에는 종류 컬럼이 없으므로 그 응모의 SPEND Ledger가 `common_ticket_ledger`에 있으면 `COMMON`, 아니면 `CREATOR`로 판별한다(Consumer가 Entry와 SPEND Ledger를 같은 Tx로 저장한다).

조회 조건은 `event_entry.member_id = 인증된 memberId`와 `event_entry.event_id = eventId`를 모두 사용한다. 없는 Member 또는
존재하지 않거나 삭제된 Event는 `RESOURCE_NOT_FOUND`, 식별자·size 범위·cursor 형식 오류는
`VALIDATION_FAILED`다.

## GET /api/events/{eventId}/entry-status

Bearer Access JWT가 필수이며 호출자는 `@CurrentMemberId`로 식별한다. 실시간 응모 현황(FR-P2-045~051)을
조회하며, 폴링 조회용이다. **당첨 확률·`cutoffStreamId`·`pendingCount`·Stream lag는 반환하지 않는다** - 응모
승인이나 추첨 근거로 쓰지 않는다.

```json
{
  "eventId": 5,
  "participantCount": 1284,
  "totalTicketCount": 5321,
  "myTicketCount": 7,
  "realtime": true
}
```

| 필드 | 설명 |
| --- | --- |
| `participantCount` | 1장 이상 응모한 고유 사용자 수 |
| `totalTicketCount` | 누적 사용 응모권 수 |
| `myTicketCount` | 인증된 사용자의 사용 응모권 수 |
| `realtime` | `true`면 Redis 집계(응모 수락 기준), `false`면 DB `event_entry` 집계(Consumer 반영 기준, CLOSED 이후는 Drain이 끝난 확정값) |

`event.status`가 `OPEN`/`CLOSING`이고 집계 키(`event:entry-total`)가 있으면 Redis를, 그 외에는 DB
집계를 쓴다(자세한 조건은 [응모 Lua API](lua-api.md#실시간-응모-현황-집계-fr-p2-045050) 참고).
`realtime=true`일 때 `myTicketCount`는 수락 기준이라 DB 기준인 `/entries/me` 합계보다 일시적으로 클
수 있다. 없거나 삭제·비공개 상태인 Event는 Event 전용 `EVENT_NOT_FOUND`(`GET /api/events/{eventId}`와
동일 규칙), 인증된 사용자가 존재하지 않으면 공통 `RESOURCE_NOT_FOUND`다.
