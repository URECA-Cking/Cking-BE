# Notification 도메인

## 책임

- 결과 공개로 생성된 사용자별 인앱 알림을 보관한다.
- `WinnerNotificationService`는 공개 유스케이스 Transaction에 참여해 최초 추첨 Winner의
  `INITIAL_WINNER` Notification을 생성한다.
- 사용자는 자신의 알림만 최신순으로 조회한다.
- Event·Drawing의 상태를 변경하지 않고, 조회 시 ID로 관련 정보를 조합한다.

## 영속성 모델

- Notification은 Member, Event, Drawing, Winner를 ID로 참조한다.
- Notification의 `(winner_id, member_id, event_id, drawing_id)`는 Winner의 같은 계보를 복합 FK로
  참조한다. 따라서 개별 ID가 존재하더라도 서로 다른 Winner·Member·Event·Drawing을 섞어 저장할 수 없다.
- `type`은 `INITIAL_WINNER` 또는 `REDRAW_WINNER`다.
- `(winner_id, type)`은 유일하므로 같은 당첨 결과에 같은 종류의 알림이 중복 생성되지 않는다.
- 목록 정렬은 `created_at DESC, id DESC`다.
- 목록 조회 인덱스는 `(member_id, created_at DESC, id DESC)`다.

## Repository 계약

- `NotificationRepository.findByMemberId(memberId, pageable)`은 요청 Member의 행만 반환한다.
- Event·Drawing 조회는 Notification Query Service가 ID 묶음으로 조회해 응답을 조합한다.

API 계약은 [api.md](api.md)에 정의한다. DB 구조의 정본은 `src/main/resources/db/migration/`이다.
