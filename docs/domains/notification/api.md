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

- `type`은 최초 추첨 당첨 알림인 `INITIAL_WINNER` 또는 재추첨 당첨 알림인 `REDRAW_WINNER`다.
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
