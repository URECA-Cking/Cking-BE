-- Creator Space 탭 노출 설정 제거 (이슈 #290)
--
-- 탭은 항상 노출하고, 내용이 없으면 화면에서 "현재 열려있는 게 없습니다"를 보여주기로 했다.
-- 탭 on/off 값을 읽는 기능 코드는 없었으므로 컬럼만 삭제한다.
ALTER TABLE creator_space_template
    DROP COLUMN home_tab_enabled,
    DROP COLUMN missions_tab_enabled,
    DROP COLUMN posts_tab_enabled,
    DROP COLUMN events_tab_enabled;

ALTER TABLE creator_space
    DROP COLUMN home_tab_enabled,
    DROP COLUMN missions_tab_enabled,
    DROP COLUMN posts_tab_enabled,
    DROP COLUMN events_tab_enabled;
