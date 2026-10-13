# Sanction API

모든 성공·실패 응답은 [공통 API 규약](../../common/api.md)의 응답 봉투를 사용하며, 아래 Response 예시는 `data` 값이다. 정책과 데이터는 [Sanction](README.md)을 따른다.

## 인증

세 API 모두 Bearer Access JWT와 `ADMIN` 역할이 필수다(없으면 `UNAUTHORIZED` 401, ADMIN이 아니면 `FORBIDDEN` 403). 호출자는 `@CurrentMemberId`로 식별한다.

## 제재 응답

| 필드 | 설명 |
| --- | --- |
| `sanctionId`, `memberId` | 제재 ID, 대상 회원 |
| `scope`, `type` | `PLATFORM`, `COMMENT_RESTRICTION` |
| `source` | `AUTOMATIC`(관리자의 댓글 숨김으로 생긴 경고 겸 제한) 또는 `MANUAL` |
| `causeCommentId` | 자동 경고의 원인 댓글. 수동 제한은 `null` |
| `reason` | 수동 제한의 사유. 자동 경고는 `null` |
| `startedAt`, `endsAt` | 제한 구간(UTC). `startedAt <= now < endsAt` 동안 유효 |
| `releasedAt`, `releasedBy` | 관리자가 해제한 시각과 관리자. 해제하지 않았으면 `null` |
| `createdBy` | 제한을 만든 관리자(자동 경고는 댓글을 숨긴 관리자) |
| `active` | 응답 시점에 지금 유효한 제한인지 |

## GET /api/admin/sanctions

제재를 시작 시각 최신순으로 페이지 조회한다. 자동 경고와 수동 제한을 모두 포함한다.

| Query | 설명 |
| --- | --- |
| `memberId` | 한 회원의 제재만 |
| `active` | `true`면 지금 유효한 제한만(기본 `false`) |
| `page`, `size` | 기본 0, 20. `size` 최대 100 |

응답은 [공통 페이지 형식](../../common/api.md#페이지네이션)의 `data`이며 `items`는 제재 응답이다.

```json
{
  "items": [{
    "sanctionId": 900, "memberId": 20, "scope": "PLATFORM", "type": "COMMENT_RESTRICTION",
    "source": "AUTOMATIC", "causeCommentId": 500, "reason": null,
    "startedAt": "2026-10-13T00:00:00Z", "endsAt": "2026-10-14T00:00:00Z",
    "releasedAt": null, "createdBy": 1, "releasedBy": null, "active": true
  }],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1, "hasNext": false
}
```

| 오류 코드 | HTTP | 조건 |
| --- | --- | --- |
| `VALIDATION_FAILED` | 400 | `size`·`page` 범위 오류 |

## POST /api/admin/sanctions

관리자가 회원에게 **지금부터** 일정 기간 댓글 작성·수정을 제한한다. 응답 `201`과 제재.

```json
{ "memberId": 20, "days": 5, "reason": "반복 욕설" }
```

- `days`는 1~365, `reason`은 1~200자(앞뒤 공백은 제거).
- 경고 횟수에 세지 않는다. 이미 제한 중이어도 새 제한을 따로 기록하며, 작성 가드는 종료 시각이 가장 늦은 제한을 쓴다.

| 오류 코드 | HTTP | 조건 |
| --- | --- | --- |
| `VALIDATION_FAILED` | 400 | `memberId`·`days`·`reason` 누락 또는 범위 오류 |
| `RESOURCE_NOT_FOUND` | 404 | 대상 회원 없음 |

## POST /api/admin/sanctions/{sanctionId}/release

유효한 제한을 지금 해제한다. 요청 본문은 없고 응답 `200`과 제재.

- 해제 시각부터 제한이 풀린다. **이미 끝났거나 해제된 제한을 다시 해제하면 아무것도 바꾸지 않고 성공**한다(멱등).
- 자동 경고는 해제해도 경고 횟수 집계에 남는다.

| 오류 코드 | HTTP | 조건 |
| --- | --- | --- |
| `RESOURCE_NOT_FOUND` | 404 | 없는 제재 |

## 다른 API에 미치는 영향

| API | 변화 |
| --- | --- |
| `POST`·`PATCH /api/creators/{creatorId}/posts/{postId}/comments` | 댓글 작성 제한 중이면 `COMMENT_RESTRICTED`(403). 메시지에 해제 시각(UTC)이 들어간다. 삭제는 막지 않는다 |
| `POST /api/admin/comment-reports/{commentId}/hide` | 처음 숨길 때 작성자에게 경고가 쌓이고 제한이 생긴다. 이미 숨긴 댓글을 다시 숨기면 쌓이지 않는다 |

| 오류 코드 | HTTP | 조건 |
| --- | --- | --- |
| `COMMENT_RESTRICTED` | 403 | 댓글 작성·수정 시 유효한 댓글 작성 제한이 있음 |
