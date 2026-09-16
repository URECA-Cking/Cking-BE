# Creator Event 운영 API

Creator의 Event 관리와 관리자 심사 API 계약이다. 모든 성공·실패 응답은 [공통 API 규약](../../common/api.md)의 응답 봉투를 사용하며, 아래 Response 예시는 `data` 값이다.

## 권한과 상태 전이

- Creator는 승인된 `creator` 레코드가 존재해야 하며, 자신의 Event만 관리할 수 있다.
- 관리자 목록·승인·거절은 `member.role == ADMIN`만 가능하다.
- Event 상태는 Repository로 직접 변경하지 않는다. 아래 전이는 `EventCommandService`를 사용한다.

```text
DRAFT --requestApproval--> PENDING_APPROVAL --approve--> SCHEDULED
                                      └--reject--> REJECTED --changeToDraft--> DRAFT
```

## GET /api/creator/events

Query: `userId`, `page`, `size`. 요청 Member에 연결된 Creator가 소유하고 `deletedAt IS NULL`인 Event만 반환한다. 기본 정렬은 `createdAt DESC, eventId DESC`다.

```json
{
  "items": [{
    "eventId": 1,
    "title": "팬미팅 이벤트",
    "startAt": "2026-09-20T09:00:00Z",
    "endAt": "2026-09-21T09:00:00Z",
    "winnerCount": 3,
    "drawMethod": "WEIGHTED",
    "status": "DRAFT",
    "createdAt": "2026-09-16T00:20:00Z"
  }],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1,
  "hasNext": false
}
```

## POST /api/creator/events

```json
{
  "userId": 1,
  "requestId": "550e8400-e29b-41d4-a716-446655440000",
  "title": "이벤트 제목",
  "description": "이벤트 설명",
  "startAt": "2026-09-20T09:00:00Z",
  "endAt": "2026-09-21T09:00:00Z",
  "winnerCount": 3,
  "drawMethod": "WEIGHTED"
}
```

- `creatorId`는 userId의 승인 Creator에서 서버가 결정한다.
- 생성 상태는 DRAFT다. 성공은 201이며 응답은 `{ "eventId": 1, "status": "DRAFT" }`다.
- `requestId`는 필수 UUID다. 같은 requestId와 같은 본문은 기존 생성 결과를 반환하고, 다른 본문은 `IDEMPOTENCY_CONFLICT`다.
- `userId`, `title`, `startAt`, `endAt`, `winnerCount`, `drawMethod`는 필수다. title은 blank 불가, description은 null 허용, `startAt < endAt`, winnerCount는 1 이상이며 drawMethod는 MVP에서 WEIGHTED만 허용한다.
- 시간은 ISO-8601로 받고 서버에서 UTC 기준으로 처리한다.

## PATCH /api/creator/events/{eventId}

요청 본문은 생성 API에서 `requestId`를 제외한 동일 필수 필드를 사용한다. DRAFT 또는 REJECTED 상태에서만 수정할 수 있으며, REJECTED 수정은 `EventCommandService.changeToDraft(eventId)`로 DRAFT로 전이한다.

성공은 200이고 응답은 `{ "eventId": 1, "status": "DRAFT" }`다. 별도 멱등 키는 사용하지 않으며 같은 값을 재적용해도 같은 최종 상태가 된다.

## DELETE /api/creator/events/{eventId}

Query: `userId`. DRAFT 또는 REJECTED만 삭제할 수 있으며, 물리 삭제 대신 `deletedAt`을 기록한다. 성공은 204 No Content다.

## POST /api/creator/events/{eventId}/approval-request

```json
{ "userId": 1 }
```

EventApprovalRequest를 새 차수로 생성한 뒤 `EventCommandService.requestApproval(eventId)`로 DRAFT에서 PENDING_APPROVAL로 전이한다. 성공은 200이며 응답은 `{ "eventId": 1, "status": "PENDING_APPROVAL" }`다.

## GET /api/admin/events/pending

Query: `userId`, `page`, `size`. 관리자만 호출할 수 있으며 현재 PENDING_APPROVAL Event와 승인 요청을 반환한다. 기본 정렬은 `requestedAt ASC, approvalRequestId ASC`다.

```json
{
  "items": [{
    "eventId": 1,
    "creatorId": 5,
    "creatorName": "IVE",
    "title": "팬미팅 이벤트",
    "startAt": "2026-09-20T09:00:00Z",
    "endAt": "2026-09-21T09:00:00Z",
    "winnerCount": 3,
    "drawMethod": "WEIGHTED",
    "status": "PENDING_APPROVAL",
    "approvalRound": 1,
    "requestedAt": "2026-09-16T00:40:00Z"
  }],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1,
  "hasNext": false
}
```

## POST /api/admin/events/{eventId}/approve

```json
{ "userId": 1 }
```

관리자만 `PENDING_APPROVAL` Event를 `EventCommandService.approve(eventId)`로 SCHEDULED로 전이할 수 있다. `endAt <= now`면 `INVALID_STATE`다. 현재 PENDING 승인 요청을 APPROVED로 기록하고 심사자·심사 시각을 저장한다. 성공은 200이며 응답은 `{ "eventId": 1, "status": "SCHEDULED" }`다.

## POST /api/admin/events/{eventId}/reject

```json
{ "userId": 1, "rejectReason": "거절 사유" }
```

거절 사유는 null·blank·trim 후 빈 문자열을 허용하지 않는다. 현재 PENDING 승인 요청을 REJECTED로 기록한 뒤 `EventCommandService.reject(eventId, reason)`로 Event를 REJECTED로 전이한다. 성공은 200이며 응답은 `{ "eventId": 1, "status": "REJECTED" }`다.

## 오류와 검증

- 없는 Member 또는 Event는 `RESOURCE_NOT_FOUND`, 권한·소유권 위반은 `FORBIDDEN`이다.
- 허용되지 않은 상태의 수정·삭제·심사·승인 요청은 `INVALID_STATE`다.
- 승인·거절처럼 Event 행 잠금으로 직렬화되는 상충 명령은 `EventCommandService`가 잠금을 획득하며, 선행 명령이 상태를 바꾼 뒤 후행 명령이 `INVALID_STATE`가 된다.
- Event 생성에서 동일 requestId의 UNIQUE 충돌 후 기존 Event를 읽어 복구할 수 없으면 Event 전용 오류 `CONCURRENT_COMMAND`다.
- Event 생성의 requestId 충돌은 `IDEMPOTENCY_CONFLICT`다.
- `event.request_id`는 UUID 저장과 생성 멱등성을 위해 UNIQUE 제약을 가진다.
