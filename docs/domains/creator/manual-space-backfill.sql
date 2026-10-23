-- V94 이후 스키마에서 Space가 없는 기존 Creator를 수동으로 채운다. 승인 경로와 같은 기본값(기본 소개 문구, 이미지 없음, creator-{creatorId})을 쓴다.
-- V19 Flyway 파일은 체크섬을 유지해야 하므로 수정하거나 재실행하지 않는다.
-- 소개 문구는 CreatorSpace.DEFAULT_INTRO_TEXT와 같아야 한다.
-- 자동 slug가 선점됐으면 승인 경로와 같이 -2부터 -100까지 찾는다.
INSERT INTO creator_space (
    creator_id, intro_text, profile_image_url, profile_image_key, default_profile_image_url,
    banner_image_url, banner_image_key, default_banner_image_url, slug, created_at
)
WITH RECURSIVE suffixes (n) AS (
    SELECT 1
    UNION ALL
    SELECT n + 1 FROM suffixes WHERE n < 100
), missing AS (
    SELECT c.creator_id, CONCAT('creator-', CAST(c.creator_id AS CHAR)) AS base_slug
    FROM creator c
    WHERE NOT EXISTS (SELECT 1 FROM creator_space s WHERE s.creator_id = c.creator_id)
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
SELECT creator_id, '크리에이터와 함께하는 공간이에요', NULL, NULL, NULL,
       NULL, NULL, NULL, candidate_slug, UTC_TIMESTAMP(6)
FROM available
WHERE choice_rank = 1;
