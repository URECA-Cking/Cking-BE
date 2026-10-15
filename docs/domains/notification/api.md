# Notification API

모든 성공·실패 응답은 [공통 API 규약](../../common/api.md)의 응답 봉투를 사용한다. 아래 예시는 `data` 값이다.

## GET /api/me/notifications

Bearer Access JWT가 필수이며 호출자는 `@CurrentMemberId`로 식별한다. Query는 `page`(기본 0), `size`(기본 20, 최대 100)다.

요청 Member가 존재해야 하며, 해당 Member의 Notification만 반환한다. 정렬은 `createdAt DESC, notificationId DESC`다. `readAt`이 `null`이면 읽지 않음이며, 값이 있으면 읽음이다.

```json
{
  "items": [{
    "notificationId": 1,
    "event": { "eventId": 10, "title": "팬미팅 이벤트" },
    "drawing": { "drawingId": 20, "drawNo": 0, "drawType": "INITIAL" },
    "type": "INITIAL_WINNER",
    "title": "당첨 안내",
    "body": "축하합니다.",
    "createdAt": "2026-09-17T00:00:00Z",
    "readAt": null
  }],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1,
  "hasNext": false
}
```

- `type`은 최초 추첨 당첨 알림 `INITIAL_WINNER`, 재추첨 당첨 알림 `REDRAW_WINNER`, 댓글 작성 제한 시작 알림 `COMMENT_RESTRICTION_STARTED`, 제한이 모두 풀렸을 때의 `COMMENT_RESTRICTION_RELEASED`다.
- **`event`와 `drawing`은 제재 알림에서 `null`이다.** 당첨 알림에서는 항상 채워진다. 클라이언트는 둘 다 null 허용으로 처리해야 하며, 제재 알림은 이동할 이벤트가 없다. 제재 알림의 `title`·`body`에 제한 안내와 해제 시각(UTC, 분 단위)이 들어 있다. 실제 종료 시각에 초 이하가 있으면 다음 분으로 올려 표기하므로, 안내 시각 이후에는 항상 제한이 풀려 있다. 알림 목록 응답 형태와 읽음 처리 API는 알림 종류와 상관없이 같다.

```json
{
  "notificationId": 2,
  "event": null,
  "drawing": null,
  "type": "COMMENT_RESTRICTION_STARTED",
  "title": "댓글 작성 제한 안내",
  "body": "신고된 댓글이 관리자에 의해 숨김 처리되어 경고 1회가 기록되었습니다. 2026-10-14 00:00 UTC까지 댓글을 작성하거나 수정할 수 없습니다.",
  "createdAt": "2026-10-13T00:00:00Z",
  "readAt": null
}
```
- 존재하지 않는 Member는 `RESOURCE_NOT_FOUND`를 반환한다.

## PATCH /api/me/notifications/{notificationId}/read

Bearer Access JWT가 필수다. 사용자가 자신의 인앱 알림을 읽음 처리하며 요청 본문은 없다. 경로의 `notificationId`는 양수여야 한다.

```json
{
  "notificationId": 10,
  "readAt": "2026-09-17T01:00:00Z"
}
```

- 존재하지 않는 Member는 `RESOURCE_NOT_FOUND`, 알림은 `NOTIFICATION_NOT_FOUND`를 반환한다.
- 타 사용자의 알림은 `FORBIDDEN`을 반환한다.
- 최초 요청만 UTC `readAt`을 저장하며, 이후 요청은 기존 값을 반환한다.
- 알림 행을 비관적 쓰기 잠금으로 조회해 동시 요청에도 하나의 `readAt`을 보장한다.

## PATCH /api/me/notifications/read

Bearer Access JWT가 필수이며 호출자는 `@CurrentMemberId`로 식별한다. 요청 본문과 페이지 매개변수는 없다. 현재 사용자의 `readAt`이 `null`인 알림 전체를 읽음 처리한다. 목록 조회의 `page`·`size`와 관계없이 처리한다.

```json
{
  "readCount": 3
}
```

- `readCount`는 이번 요청에서 새로 읽음 처리된 알림 수다. 미확인 알림이 없거나 같은 요청을 반복하면 `0`이다.
- 이미 읽은 알림의 최초 `readAt`은 변경하지 않는다. 개별 읽음 API와 함께 호출해도 행의 최초 읽음 시각을 유지한다.
- 다른 사용자의 알림은 변경하지 않는다. 존재하지 않는 Member는 `RESOURCE_NOT_FOUND`를 반환한다.
- 기존 `notification.read_at`을 사용하며 DB 변경은 없다.
