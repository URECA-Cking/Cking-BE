-- UTC [from, to). 읽기 전용 분석 연결에서 실행한다.
SET @from = '2026-10-08 00:00:00.000000';
SET @to = '2026-10-09 00:00:00.000000';

WITH cards AS (
    SELECT r.policy_version,
           COALESCE(i.received_at >= @from AND i.received_at < @to, 0) AS exposed,
           COALESCE(c.received_at >= @from AND c.received_at < @to, 0) AS clicked,
           i.request_id IS NULL AS no_impression,
           COALESCE(f.followed_at >= @from AND f.followed_at < @to, 0) AS converted
    FROM creator_recommendation_request r
    JOIN creator_recommendation_card card ON card.request_id = r.request_id
    LEFT JOIN creator_recommendation_interaction i
      ON i.request_id = card.request_id AND i.creator_id = card.creator_id AND i.event_type = 'IMPRESSION'
    LEFT JOIN creator_recommendation_interaction c
      ON c.request_id = card.request_id AND c.creator_id = card.creator_id AND c.event_type = 'CLICK'
    LEFT JOIN creator_recommendation_conversion f
      ON f.request_id = card.request_id AND f.creator_id = card.creator_id
), totals AS (
    SELECT policy_version,
           SUM(exposed) AS impressions,
           SUM(exposed AND clicked) AS exposed_clicks,
           SUM(clicked) AS clicks,
           SUM(clicked AND no_impression) AS clicks_without_impression,
           SUM(converted) AS attributed_follows,
           SUM(exposed AND clicked AND converted) AS exposed_click_follows
    FROM cards GROUP BY policy_version
)
SELECT *, COALESCE(exposed_clicks / NULLIF(impressions, 0), 0) AS ctr,
          COALESCE(exposed_click_follows / NULLIF(exposed_clicks, 0), 0) AS click_conversion_rate
FROM totals ORDER BY policy_version;
