-- V20 이후 스키마에서 Space가 없는 기존 Creator를 수동으로 채운다.
-- V19 Flyway 파일은 체크섬을 유지해야 하므로 수정하거나 재실행하지 않는다.
-- 활성 템플릿의 slug가 선점됐으면 승인 경로와 같이 -2부터 -100까지 찾는다.
INSERT INTO creator_space (creator_id, intro_text, profile_image_url, banner_image_url, slug, created_at)
WITH RECURSIVE suffixes (n) AS (
    SELECT 1
    UNION ALL
    SELECT n + 1 FROM suffixes WHERE n < 100
), missing AS (
    SELECT c.creator_id, t.intro_text, t.profile_image_url, t.banner_image_url,
           REPLACE(t.slug_rule, '{creatorId}', CAST(c.creator_id AS CHAR)) AS base_slug
    FROM creator c
    JOIN creator_space_template t ON t.active_marker = 1
    WHERE REGEXP_LIKE(t.slug_rule, '^([a-z0-9-]*[a-z-])?[{]creatorId[}]$', 'c')
      AND CHAR_LENGTH(REPLACE(t.slug_rule, '{creatorId}', CAST(c.creator_id AS CHAR))) <= 100
      AND NOT EXISTS (SELECT 1 FROM creator_space s WHERE s.creator_id = c.creator_id)
), candidates AS (
    SELECT m.*, suffixes.n,
           CASE WHEN suffixes.n = 1 THEN m.base_slug
                ELSE CONCAT(m.base_slug, '-', suffixes.n) END AS candidate_slug
    FROM missing m CROSS JOIN suffixes
), available AS (
    SELECT candidates.*,
           ROW_NUMBER() OVER (PARTITION BY creator_id ORDER BY n) AS choice_rank
    FROM candidates
    WHERE CHAR_LENGTH(candidate_slug) <= 100
      AND NOT EXISTS (SELECT 1 FROM creator_space s WHERE s.slug = candidates.candidate_slug)
)
SELECT creator_id, intro_text, profile_image_url, banner_image_url, candidate_slug, UTC_TIMESTAMP(6)
FROM available
WHERE choice_rank = 1;
