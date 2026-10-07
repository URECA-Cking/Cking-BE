-- 판정하지 못한 댓글의 재필터링 (이슈 #477)
--
-- 스케줄러가 filter_status가 PENDING·FAILED인 댓글을 다시 필터에 제출한다. 제출하기 전에 시도 횟수를 올리고
-- 다음 시도 가능 시각을 조건부 UPDATE로 먼저 확보하므로, 큐에서 처리 중인 댓글을 다음 주기에 또 고르지 않고
-- 필터 서비스가 응답하지 않아도 횟수가 쌓여 상한에서 멈춘다.
--
-- filter_attempts        : 스케줄러가 재제출한 횟수. 처음 판정(작성·수정 직후)은 세지 않는다. 본문을 수정하면 0으로 돌아간다.
-- filter_next_attempt_at : 다음 재시도가 가능한 시각. NULL이면 아직 재시도한 적이 없는 것이며, 이 경우 updated_at이 대기 시간을
--                          지났을 때부터 대상이 된다. 그래서 기존 PENDING·FAILED 댓글도 백필 없이 재시도 대상이 된다.
--
-- 조회 대상(PENDING·FAILED)은 전체 댓글 중 소수라 filter_status 인덱스(V46)로 충분하며 인덱스를 더하지 않는다.
ALTER TABLE creator_post_comment
    ADD COLUMN filter_attempts        INT         NOT NULL DEFAULT 0,
    ADD COLUMN filter_next_attempt_at DATETIME(6) NULL;
