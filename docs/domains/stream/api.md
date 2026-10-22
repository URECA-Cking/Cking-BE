# Stream API

Dead Stream으로 이동한 메시지를 운영자가 확인하고 수동 replay하는 관리자 API다. 사용자 식별, 오류 코드, 응답 봉투는 [공통 API 규약](../../common/api.md)을 따른다. replay의 처리 방식과 멱등성은 [Stream 도메인](README.md#dead-stream과-replay)에 있다.

두 API는 Bearer Access JWT가 필수이며 Controller가 `@CurrentMemberId`로 관리자 업무 식별자를 전달한다. `/api/admin/**`의 Security 1차 인가에서 토큰 없음은 `UNAUTHORIZED`, USER 역할은 `FORBIDDEN`이다. 이후 Service도 요청 Member 존재와 `role == ADMIN`을 검증해 Member가 없으면 `RESOURCE_NOT_FOUND`, ADMIN이 아니면 `FORBIDDEN`이다.

## GET /api/admin/dead-streams

Query는 선택 `status`, `page`, `size`다. `status`는 `UNRESOLVED`(기본) 또는 `RESOLVED`다. `page`는 0부터 시작하고 `size` 기본값은 20, 허용 범위는 1~100이다. 값이 범위를 벗어나거나 `status`가 그 외이면 `VALIDATION_FAILED`다.

처리 대기 큐처럼 오래된 메시지부터 `createdAt ASC, id ASC`로 반환하며, 응답은 공통 Page 형식(`items`, `page`, `size`, `totalElements`, `totalPages`, `hasNext`)이다.

```json
{
  "id": 5,
  "streamType": "SPEND",
  "sourceStreamId": "1700000000000-0",
  "requestId": "550e8400-e29b-41d4-a716-446655440000",
  "eventId": 3,
  "memberId": 7,
  "failureReason": "PEL 최대 재시도 초과",
  "retryCount": 6,
  "lastFailedAt": "2026-09-21T00:00:00Z",
  "resolutionStatus": "UNRESOLVED",
  "resolvedBy": null,
  "resolvedAt": null,
  "createdAt": "2026-09-21T00:00:00Z"
}
```

원본 `payload`는 반환하지 않는다. `streamType`은 `EARN`/`SPEND`/`COMMON_EARN`(이슈 #244) 중 하나다. EARN·COMMON_EARN 메시지는 이벤트와 무관해 `eventId`가 `null`이다. 해당 이벤트의 cutoff 범위에 `UNRESOLVED` SPEND 메시지가 있으면 그 이벤트는 `CLOSED`로 전이할 수 없으므로, `eventId`로 마감이 막힌 원인을 찾는다.

## POST /api/admin/dead-streams/{id}/replay

Request Body는 없다. 보존한 원본 payload를 EARN·SPEND·공용 EARN Ledger 서비스에 다시 적용하고 `RESOLVED`로 표시하며, 처리자(`resolvedBy`)는 Access JWT의 인증된 관리자다. 응답은 위 목록 항목과 같은 형태의 처리된 메시지다.

- 대상이 없으면 `RESOURCE_NOT_FOUND`다.
- 이미 `RESOLVED`인 메시지는 다시 적용하지 않고 현재 상태를 그대로 반환한다(상태 기반 멱등). `resolvedBy`·`resolvedAt`도 바뀌지 않는다.
- 재적용 중 오류가 나면 `SYSTEM_ERROR`이고 트랜잭션이 롤백되어 메시지는 `UNRESOLVED`로 남는다. 원인을 고친 뒤 다시 호출한다.
