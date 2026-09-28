-- V18 이전에 이미 승인된 Creator에게 Creator Space를 채워 넣는다 (이슈 #270)
--
-- V18 이후에는 승인 트랜잭션이 Space를 함께 만들지만, 그 전에 승인된 Creator에게는
-- Space가 없다. 활성 템플릿(active_marker = 1)의 값을 복사해 승인 경로와 같은 방식으로
-- 만든다(CreatorSpace.fromTemplate, CreatorSpaceSlugRule).
--
-- - Space가 없는 Creator만 대상으로 하므로 다시 실행해도 결과가 같다.
-- - 활성 템플릿이 없거나, 활성 템플릿의 slug_rule이 CreatorSpaceSlugRule.REGEX 형식이 아니거나,
--   치환한 slug가 100자를 넘으면 아무것도 넣지 않는다 — 배포를 막지 않고, 대상 Creator는
--   Space 없이 남는다. 이 경우의 후속 조치는 docs/domains/creator/README.md를 따른다.
-- - slug_rule 형식이 맞으면 slug 끝 숫자열이 creator_id라 Creator끼리 slug가 겹치지 않는다.
--   REGEXP_LIKE의 'c'는 컬럼 collation(ai_ci)과 무관하게 대소문자를 구분하게 한다.
INSERT INTO creator_space (
    creator_id, intro_text, profile_image_url, banner_image_url, slug,
    home_tab_enabled, missions_tab_enabled, posts_tab_enabled, events_tab_enabled, created_at
)
SELECT c.creator_id,
       t.intro_text,
       t.profile_image_url,
       t.banner_image_url,
       REPLACE(t.slug_rule, '{creatorId}', CAST(c.creator_id AS CHAR)),
       t.home_tab_enabled,
       t.missions_tab_enabled,
       t.posts_tab_enabled,
       t.events_tab_enabled,
       UTC_TIMESTAMP(6)
FROM creator c
JOIN creator_space_template t ON t.active_marker = 1
WHERE REGEXP_LIKE(t.slug_rule, '^([a-z0-9-]*[a-z-])?[{]creatorId[}]$', 'c')
  AND CHAR_LENGTH(REPLACE(t.slug_rule, '{creatorId}', CAST(c.creator_id AS CHAR))) <= 100
  AND NOT EXISTS (SELECT 1 FROM creator_space s WHERE s.creator_id = c.creator_id);
