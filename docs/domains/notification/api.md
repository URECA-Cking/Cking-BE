# Notification API

## 응모권 지급 완료 알림 (#717, FE #360)

- `COMMON_TICKET_REWARDED` / `CREATOR_TICKET_REWARDED`, 제목은 `응모권 지급 완료`다.
- 커밋된 양수 EARN 원장의 보상 종류·실제 수량을 본문에 표시한다. 예: `출석체크 보상으로 공용 응모권 2장이 지급됐어요.`
- `event`·`drawing`은 없으며 기존 NON_NULL 직렬화 정책을 따른다. 기존 조회·읽음·숨김 API를 쓰고 별도 이동은 없다.
- 원장별 V90 마지막 기본값 전환 이후 INSERT만 대상이다. 이전 원장은 소급하지 않고 재시작 시 경계를 재설정하지 않는다.
- 기본 비활성이고 개발(`application-dev.yml`)·운영(`application-prod.yml`) 프로필에서 켠다. 운영에서 끌 때는 Parameter Store `/cking/prod/TICKET_REWARD_NOTIFICATION_ENABLED`를 `false`로 두고 재배포한다(`deploy.sh`가 값이 있을 때만 넘긴다). 비활성 중 쌓인 대상은 활성화 후 처리한다.
- 기본 30초(dev는 2초, `cking.notification.ticket-reward.interval-ms`) 간격으로 원장별 최대 20건(설정 상한 100건), 실패는 60초 뒤 재시도한다. 완료 이력은 조회에서 제외한다. 즉시 전달·실시간 배지 갱신을 보장하지 않는다.
- 공용 Scheduler를 지연시키지 않도록 전용 실행 풀(`ticketRewardNotificationTaskScheduler`, 스레드 1개)에서 실행한다. 기능이 꺼져 있으면 실행 풀을 만들지 않는다(#732).
- 지급 데이터는 append-only이며 알림 상태·재시도 시각·알림 참조만 별도 트랜잭션에서 변경한다. 기존 지급·ACK와 분리한다.

V89 사용 PR #719를 먼저 반영한 뒤 V90을 배포한다. MySQL 부분 DDL 실패 시 알림 작업을 끄고 실패 문장·`SHOW CREATE TABLE`·두 기본값을 확인한다. V90은 완료된 원자적 DDL을 메타데이터로 확인해 건너뛴다. 운영자는 부분 스키마가 해당 DDL의 기대 상태와 일치하는지 확인한 뒤 실패 이력을 수동 `repair`하고 원본 V90을 재실행한다. 기대 상태와 다르면 먼저 수동 조정한다. 앱의 자동 repair, 표식 초기화, 지급 데이터 재작성은 금지한다.

모든 성공·실패 응답은 [공통 API 규약](../../common/api.md)의 응답 봉투를 사용한다. 아래 예시는 `data` 값이다.

## GET /api/me/notifications

Bearer Access JWT가 필수이며 호출자는 `@CurrentMemberId`로 식별한다. Query는 `page`(기본 0), `size`(기본 20, 최대 100)다.

요청 Member가 존재해야 하며, 해당 Member의 **숨기지 않았고 보관 Event에 속하지 않은** Notification만 반환한다(숨긴 알림과 보관 Event 알림은 `items`와 `totalElements`에서 빠진다). 정렬은 `createdAt DESC, notificationId DESC`다. `readAt`이 `null`이면 읽지 않음이며, 값이 있으면 읽음이다.

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

- `type`은 최초 추첨 당첨 알림 `INITIAL_WINNER`, 재추첨 당첨 알림 `REDRAW_WINNER`, 최초 추첨 결과 공개 시 후보였으나 당첨되지 못한 미당첨자의 결과 발표 알림 `INITIAL_NON_WINNER`(제목 "이벤트 결과 발표"), 댓글 작성 제한 시작 알림 `COMMENT_RESTRICTION_STARTED`, 제한이 모두 풀렸을 때의 `COMMENT_RESTRICTION_RELEASED`다.
- **`event`와 `drawing`은 제재 알림에서 `null`이다.** 당첨·미당첨 알림에서는 항상 채워진다. 클라이언트는 둘 다 null 허용으로 처리해야 하며, 제재 알림은 이동할 이벤트가 없다. 제재 알림의 `title`·`body`에 제한 안내와 해제 시각(UTC, 분 단위)이 들어 있다. 실제 종료 시각에 초 이하가 있으면 다음 분으로 올려 표기하므로, 안내 시각 이후에는 항상 제한이 풀려 있다. 알림 목록 응답 형태와 읽음 처리 API는 알림 종류와 상관없이 같다.

```json
{
  "notificationId": 2,
  "event": null,
  "drawing": null,
  "type": "COMMENT_RESTRICTION_STARTED",
  "title": "스페이스 댓글 작성 제한 안내",
  "body": "홍길동의 스페이스에서 신고된 댓글이 관리자에 의해 숨김 처리되어 경고 1회가 기록되었습니다. 2026-10-14 00:00 UTC까지 이 스페이스에서 댓글을 작성하거나 수정할 수 없습니다.",
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
- 숨긴 알림과 보관 Event 알림은 `NOTIFICATION_NOT_FOUND`다. 소유자 검증이 먼저라 타인의 알림은 숨겼더라도 `FORBIDDEN`이다.
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
- 숨긴 알림과 보관 Event 알림은 대상이 아니라서 `readCount`에 세지 않고 읽음 시각도 기록하지 않는다.
- 기존 `notification.read_at`을 사용하며 DB 변경은 없다.

## PATCH /api/me/notifications/{notificationId}/hide

Bearer Access JWT가 필수다. 사용자가 자신의 알림을 알림함에서 숨기며 요청 본문은 없다. 경로의 `notificationId`는 양수여야 한다. 알림을 **삭제하지 않고** `hiddenAt`만 기록한다. 숨긴 알림을 다시 보이게 하는 API는 없다.

```json
{
  "notificationId": 10,
  "hiddenAt": "2026-10-16T01:00:00Z"
}
```

- 숨긴 알림은 목록 조회, 개별 읽음, 전체 읽음에서 제외된다. 읽음 상태는 바꾸지 않는다.
- 최초 요청만 UTC `hiddenAt`을 저장하며, 이미 숨긴 알림에 다시 요청해도 오류 없이 기존 값을 반환한다(멱등).
- 존재하지 않는 Member는 `RESOURCE_NOT_FOUND`, 알림은 `NOTIFICATION_NOT_FOUND`를 반환한다.
- 타 사용자의 알림은 `FORBIDDEN`을 반환한다.
- 알림 행을 비관적 쓰기 잠금으로 조회해 동시 요청에도 하나의 `hiddenAt`을 보장하고, 전체 읽음 UPDATE와도 직렬화된다.
- `notification.hidden_at`을 사용한다(`V79`).
