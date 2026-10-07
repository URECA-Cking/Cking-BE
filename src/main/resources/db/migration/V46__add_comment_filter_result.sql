-- Creator Space 게시글 댓글의 필터 판정 결과 (이슈 #460)
--
-- 댓글을 저장한 뒤 별도 필터 서비스가 비동기로 판정하고, 그 결과를 댓글 행에 덮어쓴다. 판정 이력은 남기지 않는다.
--
-- filter_status
--   PENDING : 아직 판정하지 않았다. 새 댓글과 본문을 수정한 댓글이 이 상태로 시작한다.
--   DONE    : 판정을 마쳤다. filter_action이 채워진다.
--   FAILED  : 필터 서비스 장애·타임아웃으로 판정하지 못했다. 댓글은 통과 상태로 보이고 나중에 다시 판정한다.
-- filter_action        : PASS 또는 BLOCK. 판정 전과 실패 상태에서는 NULL이다.
-- filter_reasons       : 판정 근거를 쉼표로 이은 값(예: spam:link,profanity). privacy:* 사유는 "필터링한 댓글 보기" 대상에서 뺀다.
-- filter_rule_version  : 판정에 쓴 규칙 버전.
-- filter_model_version : 판정에 쓴 모델 버전.
-- filtered_at          : 판정을 마친 시각.
--
-- 이 마이그레이션 이전의 댓글은 필터를 거치지 않았으므로 DONE/PASS로 채운다. 버전과 판정 시각은 NULL로 두어
-- 필터 도입 전 댓글과 구분하고, 재필터링 작업이 이 댓글들을 한꺼번에 처리하지 않게 한다.
-- 백필 뒤에 filter_status의 DEFAULT를 지워, 값을 빠뜨린 새 댓글이 조용히 DONE이 되지 않게 한다.
--
-- idx_creator_post_comment_filter_status는 재필터링 작업의 미판정·실패 댓글 조회를 지원한다.
--   WHERE filter_status IN ('PENDING', 'FAILED') ORDER BY created_at
ALTER TABLE creator_post_comment
    ADD COLUMN filter_status        VARCHAR(20)  NOT NULL DEFAULT 'DONE',
    ADD COLUMN filter_action        VARCHAR(10)  NULL,
    ADD COLUMN filter_reasons       VARCHAR(500) NULL,
    ADD COLUMN filter_rule_version  VARCHAR(50)  NULL,
    ADD COLUMN filter_model_version VARCHAR(100) NULL,
    ADD COLUMN filtered_at          DATETIME(6)  NULL;

UPDATE creator_post_comment SET filter_action = 'PASS';

ALTER TABLE creator_post_comment
    ALTER COLUMN filter_status DROP DEFAULT,
    ADD INDEX idx_creator_post_comment_filter_status (filter_status, created_at);
