# Notification 도메인

## 책임

- 결과 공개로 생성된 당첨 알림과 제재(댓글 작성 제한, 크리에이터의 스페이스 차단 포함) 시작·해제 알림(이슈 #572, #579) 등 사용자별 인앱 알림을 보관한다.
- `WinnerNotificationService`는 공개 유스케이스 Transaction에 참여해 최초 추첨 Winner의
  `INITIAL_WINNER`, 재추첨 Winner의 `REDRAW_WINNER`, 최초 추첨 미당첨자의 `INITIAL_NON_WINNER`(이슈 #605) Notification을 생성한다.
- 사용자는 자신의 알림만 최신순으로 조회한다.
- `SanctionNotificationService`는 제재 도메인이 호출하는 Transaction에 참여해 제재 알림을 생성한다. 알림은 제재 저장과 함께 커밋되거나 롤백된다.
- Event·Drawing의 상태를 변경하지 않고, 조회 시 ID로 관련 정보를 조합한다.

## 영속성 모델

- Notification은 Member, Event, Drawing, Winner를 ID로 참조한다.
- Notification의 `(winner_id, member_id, event_id, drawing_id)`는 Winner의 같은 계보를 복합 FK로
  참조한다. 따라서 개별 ID가 존재하더라도 서로 다른 Winner·Member·Event·Drawing을 섞어 저장할 수 없다.
- `type`은 당첨 알림 `INITIAL_WINNER`·`REDRAW_WINNER`, 미당첨 알림 `INITIAL_NON_WINNER`, 제재 알림 `COMMENT_RESTRICTION_STARTED`(댓글 작성 제한 시작)·`COMMENT_RESTRICTION_RELEASED`(제한이 모두 풀림)다.
- 제재 알림은 이벤트·추첨·당첨과 무관하므로 `event_id`·`drawing_id`·`winner_id`가 NULL이고 대신 `sanction_id`로 `member_sanction`을 가리킨다(`V71`). 세 컬럼은 **모두 NULL이거나 모두 NOT NULL**이어야 한다(`ck_notification_winner_lineage_all_or_none`). MySQL은 복합 FK의 컬럼 중 하나라도 NULL이면 그 FK를 검사하지 않으므로, 이 CHECK가 없으면 `winner_id`만 채운 행이 계보 보장을 우회한다.
- 미당첨 알림은 최초 추첨(INITIAL) 공개에서만 만든다. 대상은 Drawing이 사용한 Snapshot 후보 중 해당 Drawing의 Winner가 아닌 Member이며, 재추첨 공개에는 만들지 않는다. 응모자 규모에 비례하므로 엔티티를 적재하지 않고 `NotificationRepository.insertNonWinnerNotifications`의 단일 `INSERT ... SELECT`로 호출자 트랜잭션 안에서 저장한다. 이 알림은 `winner_id`가 NULL이고 `event_id`·`drawing_id`는 채운다(`V78`).
- `event_id`·`drawing_id`·`winner_id`의 조합은 유형별 CHECK(`ck_notification_lineage_by_type`, `V78`)가 강제한다: 제재 알림은 세 값 모두 NULL, 당첨 알림은 모두 NOT NULL, `INITIAL_NON_WINNER`는 `winner_id`만 NULL이다. 예전 "모두 NULL 또는 모두 NOT NULL" CHECK(`ck_notification_winner_lineage_all_or_none`)는 교체되었다.
- `(event_id, member_id, type)`은 유일하므로 같은 이벤트에서 한 Member에게 같은 종류의 결과 알림이 중복 생성되지 않는다(미당첨 알림의 최종 멱등 방어). Winner가 `(event_id, member_id)` 유일이라 기존 당첨 알림 데이터는 이 키를 이미 만족한다.
- `(winner_id, type)`은 유일하므로 같은 당첨 결과에 같은 종류의 알림이 중복 생성되지 않는다.
- `(sanction_id, type)`도 유일하므로 같은 제재에 같은 종류의 알림이 중복 생성되지 않는다. 두 유니크는 NULL인 컬럼의 행을 제외하므로 서로 영향을 주지 않는다.
- 목록 정렬은 `created_at DESC, id DESC`다.
- 목록 조회 인덱스는 `(member_id, created_at DESC, id DESC)`다.

## Repository 계약

- `NotificationRepository.findByMemberId(memberId, pageable)`은 요청 Member의 행만 반환한다.
- 전체 읽음 처리는 페이지 조회를 거치지 않고 `(member_id, read_at IS NULL)` 조건의 UPDATE로 미확인 행만 변경한다. 이미 읽은 알림의 최초 읽음 시각은 유지한다.
- Event·Drawing 조회는 Notification Query Service가 ID 묶음으로 조회해 응답을 조합한다. ID가 NULL인 제재 알림은 조회 대상에서 빼며, 응답의 `event`·`drawing`은 `null`이다. ID가 있는데 대상이 없으면(당첨 알림) 서버 정합성 오류다.

API 계약은 [api.md](api.md)에 정의한다. DB 구조의 정본은 `src/main/resources/db/migration/`이다.
