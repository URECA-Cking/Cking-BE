-- 크리에이터의 스페이스 댓글 작성 차단 (이슈 #579)
--
-- 크리에이터가 자기 Creator Space 게시글에서 특정 회원의 댓글 작성·수정을 막는다. 플랫폼 전체 제한(#569)과 같은
-- member_sanction 테이블에 scope=SPACE 행으로 기록하고, 어느 크리에이터의 스페이스인지를 space_creator_id로 가리킨다.
-- 차단은 source=MANUAL이라 경고 횟수에 세지 않고, 플랫폼 제재 단계(1일/3일/7일)에도 영향을 주지 않는다. created_by는
-- 차단을 건 크리에이터의 member_id다.
--
-- ck_member_sanction_space_creator
--   SPACE 범위 제재는 반드시 스페이스 주인(space_creator_id)이 있고, PLATFORM 범위 제재는 없어야 한다. 한쪽만 채워진 행을
--   막아 플랫폼 제한 조회(scope=PLATFORM)와 스페이스 차단 조회가 서로의 행을 읽지 않게 한다.
-- fk_member_sanction_space_creator
--   제재 기록은 삭제하지 않으므로 CASCADE를 걸지 않는다.
-- idx_member_sanction_space_member_ends
--   댓글 작성·수정마다 하는 "이 스페이스에서 이 회원이 지금 차단됐는가" 조회(space_creator_id, member_id, ends_at 범위).
-- idx_member_sanction_space_started
--   크리에이터의 차단 목록(space_creator_id별 최신순).
ALTER TABLE member_sanction
    ADD COLUMN space_creator_id BIGINT NULL AFTER member_id,
    ADD CONSTRAINT fk_member_sanction_space_creator FOREIGN KEY (space_creator_id) REFERENCES creator (creator_id),
    ADD CONSTRAINT ck_member_sanction_space_creator CHECK ((scope = 'SPACE') = (space_creator_id IS NOT NULL)),
    ADD INDEX idx_member_sanction_space_member_ends (space_creator_id, member_id, ends_at),
    ADD INDEX idx_member_sanction_space_started (space_creator_id, started_at, sanction_id);
