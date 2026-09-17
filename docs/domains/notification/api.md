# Notification API

## PATCH /api/me/notifications/{notificationId}/read

사용자가 자신의 인앱 알림을 읽음 처리한다.

### 요청

```json
{
  "userId": 1
}
```

### 성공 응답

```json
{
  "code": "SUCCESS",
  "data": {
    "notificationId": 10,
    "readAt": "2026-09-17T01:00:00Z"
  },
  "message": null
}
```

### 처리 규칙

- `userId`에 해당하는 Member가 없으면 `RESOURCE_NOT_FOUND`를 반환한다.
- Notification이 없으면 `NOTIFICATION_NOT_FOUND`를 반환한다.
- 요청 사용자가 Notification의 소유자가 아니면 `FORBIDDEN`을 반환한다.
- 최초 요청만 `readAt`에 UTC 시각을 저장한다.
- 이미 읽은 Notification은 기존 `readAt`을 그대로 반환한다.
- Notification 행을 비관적 쓰기 잠금으로 조회해 동시 요청도 하나의 `readAt`으로 일관되게 처리한다.

### 오류 코드

| HTTP | code | 조건 |
| --- | --- | --- |
| 400 | `VALIDATION_FAILED` | `userId` 누락 |
| 403 | `FORBIDDEN` | 타 사용자의 Notification |
| 404 | `RESOURCE_NOT_FOUND` | 존재하지 않는 Member |
| 404 | `NOTIFICATION_NOT_FOUND` | 존재하지 않는 Notification |
