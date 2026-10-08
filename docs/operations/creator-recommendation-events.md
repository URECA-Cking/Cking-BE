# Creator 추천 행동 운영

API와 FE 전송 계약은 [추천 행동 수집](../domains/creator/recommendation-events.md)을 따른다.

## 저장과 귀속

- Flyway V51은 request, card, source, event_receipt, interaction, conversion 테이블을 추가한다.
- request 생성은 노출이 아니다. card는 실제 응답 순위를 보존하며 source는 당시 후보 생성 세대별 메타데이터다.
- receipt는 승인한 모든 eventId의 내용을 보존한다. interaction은 요청/카드/타입의 최초 수신만 저장한다.
- last-click은 receipt의 실제 승인 클릭 중 가장 최근 것을 사용한다. 새 ID의 재클릭은 귀속 시각만 갱신하고 고유 클릭 수를 늘리지 않는다. 같은 ID 재시도는 어느 시각도 갱신하지 않는다.
- 순위/정책은 request/card 조인으로 읽는다. 정책이 다른 응답은 다른 요청 ID로 분리되므로 fallback과 개인화를 섞지 않는다.
- conversion은 실제 팔로우 전환 시각과 마지막 클릭, `LAST_CLICK_V1`, 적용 window 초를 보존한다.
- 수집 시 회원 행을 잠근 뒤 시각을 읽는다. 집계 기준은 서버가 처리 승인을 시작한 시각이며 네트워크 도착 시각이나 클라이언트 시각이 아니다.
- 동일 마이크로초의 last-click은 eventId 내림차순으로 결정한다. 임의 도착 순서로 바뀌지 않는다.

## 실패 격리와 관측

추천 조회 트랜잭션이 끝난 후 스냅샷을 `REQUIRES_NEW`로 저장한다. 실패하면 추천 카드와 정상 HTTP 응답은 유지하고
`recommendationRequestId: null`을 반환한다. 스냅샷과 카드/source는 원자적이라 일부만 저장되지 않는다.

팔로우/언팔로우는 회원 행 잠금으로 직렬화한다. 실제 신규 관계 생성에만 `CreatorFollowCreated`를 발행하고,
팔로우 커밋 후 리스너가 인스턴스별 단일 worker/대기 100건 큐로 전환 기록을 넘긴다. worker는 별도 DB 트랜잭션을 쓴다.
원래 팔로우 연결이 반납되기 전에 기록용 연결을 기다리는 방식은 연결 풀을 고갈시킬 수 있어 큐를 사용한다.
커밋 롤백이면 리스너는 실행되지 않는다. 큐 거절/DB 오류는 팔로우 응답을 실패시키지 않는다.

**보장 범위**:

- 200으로 승인한 수집 배치는 receipt/interaction을 DB에 커밋했다. 응답 유실 재시도는 멱등하다.
- 추천 스냅샷 성공과 각 전환 저장 트랜잭션은 원자적이며 DB UNIQUE가 중복 집계를 막는다.
- 팔로우 분석은 best effort다. 팔로우 커밋 직후 프로세스 종료, 큐 포화, DB 오류, 종료 대기 10초 초과 시 전환이 유실될 수 있다.
  영속 outbox/자동 재처리는 이번 범위에 없다. 반복 PUT은 신규 전환이 아니므로 유실 복원 수단으로 쓰지 않는다.
- 운영자가 확실한 원본 전환 시각을 확보한 경우에만 `recordFollow(memberId, creatorId, originalFollowedAt)`로
  재처리할 수 있다. 동일 요청/Creator UNIQUE가 중복을 막고 원래 시각으로 last-click을 다시 판단한다.
  시각/원본 증거 없이 현재 팔로우 상태만으로 이력을 추정해 만들지 않는다.
- 수집 API 저장 오류는 5xx로 전파한다. 유효 기간 안에서 동일 ID/내용으로 재시도한다.

WARN은 `Recommendation tracking failed: phase=..., error=예외클래스`다. 예외 원문/SQL/JWT/소개를 별도 분석 로그에 넣지 않는다.
Micrometer `cking.recommendation.tracking` counter는 `phase=snapshot|collect|follow|follow_dispatch|cleanup`,
`outcome=success|failure|rejected`를 사용한다. collect의 업무 검증 실패는 rejected다.
follow success는 기록 처리 성공이며, 클릭이 없어 실제 저장한 전환이 0건일 수도 있다.
Prometheus `cking_recommendation_tracking_total`의 failure 증가와 follow_dispatch failure를 경보 대상으로 삼는다.
DB 저장이 성공했어도 FE 전송 실패, 스냅샷 null, 분석 유실 때문에 실제 모든 행동을 대표하지 않는다.

## 기간별 집계

[실행 가능한 MySQL SQL](creator-recommendation-metrics.sql)은 UTC 반개구간 `[from, to)`를 받는다.
조회 발급 수를 노출 분모로 쓰지 않는다. 카드 단위는 `(requestId, creatorId)`다.

| 결과 | 정의 |
| --- | --- |
| impressions | 기간 내 최초 승인 IMPRESSION 카드 수 |
| exposed_clicks | 기간 내 노출과 기간 내 CLICK이 모두 있는 카드 수 |
| clicks | 기간 내 CLICK 카드 수 (노출 미수신도 포함) |
| clicks_without_impression | 기간 내 CLICK이 있으나 현재 DB에 IMPRESSION이 전혀 없는 카드 수 |
| attributed_follows | 기간 내 추천 귀속 신규 팔로우 수 (노출 미수신 클릭 귀속 포함) |
| exposed_click_follows | 기간 내 노출/클릭/전환이 모두 있는 카드 수 |
| ctr | exposed_clicks / impressions, 분모 0이면 0 |
| click_conversion_rate | exposed_click_follows / exposed_clicks, 분모 0이면 0 |

미노출 클릭은 CTR/클릭 대비 전환율 분모와 분자에서 제외하고 별도 수로 확인한다.
attributed_follows는 독립적인 기간 전환 지표이므로 위 전환율 분자와 항상 같지는 않다.
기간 밖에서 노출/클릭하고 기간 안에서 팔로우한 경우 attributed_follows에만 포함될 수 있다.
지연 전송 노출이 승인되면 동일 카드는 노출/클릭 교집합에 들어갈 수 있어 미확정 기간의 수치는 바뀐다.
혼합 모델 분석은 source 테이블을 이용하되 source 조인으로 카드가 여러 행이 되므로 먼저 카드 단위로 집계한다.

## 보존과 삭제

기본 90일이며 request 생성 시각을 기준으로 request 이하 전체를 삭제한다. 만료는 수집 차단이고 보존 기간과 다르다.
시간별 스케줄러는 한 번에 오래된 request 최대 500개를 삭제한다. 배포 인스턴스가 여럿이어도 DELETE/종속 CASCADE는 멱등하다.
적체가 500건/시간보다 많으면 주기를 줄여 운영한다. 스케줄러 OFF 시 같은 `cleanUp()`을 운영 job에서 호출할 수 있다.
회원 hard delete는 request FK CASCADE를 통해 해당 회원의 카드/source/receipt/interaction/conversion을 삭제한다.
Creator hard delete도 card 이하를 삭제한다. 익명화하거나 분석 식별자를 영구 보존하지 않는다.
기존 Member/Creator 삭제 기능의 다른 업무 FK 처리는 기존 계약에 따르며, 새 삭제 API를 추가하지 않는다.

| 설정/환경 변수 | 기본값 |
| --- | --- |
| `cking.recommendation.tracking.request-ttl` / `RECOMMENDATION_TRACKING_REQUEST_TTL` | PT24H |
| `cking.recommendation.tracking.attribution-window` / `RECOMMENDATION_TRACKING_ATTRIBUTION_WINDOW` | PT24H |
| `cking.recommendation.tracking.retention` / `RECOMMENDATION_TRACKING_RETENTION` | P90D |
| `cking.recommendation.tracking.cleanup-enabled` / `RECOMMENDATION_TRACKING_CLEANUP_ENABLED` | true |
| `cking.recommendation.tracking.cleanup-interval-ms` / `RECOMMENDATION_TRACKING_CLEANUP_INTERVAL_MS` | 3600000 |

window는 1초 이상 정수 초다. TTL은 양수, retention은 TTL + attribution-window 이상이어야 하며 위반 시 기동을 거부한다.
분석 트랜잭션 timeout은 5초다. 풀 연결 대기는 별도 DataSource 설정의 영향을 받으므로 장애 시 지연은 생길 수 있다.
수집 최대 50건은 HTTP DTO와 Application에서 모두 검증한다.

## 검증

`RecommendationTrackingIntegrationTest`는 실제 MySQL/Flyway에서 고유 회원 fixture와 전용 시계를 사용한다.
수집 원자성/중복/만료, 선도착 클릭, last-click/24시간 경계/재팔로우/동시 요청, 롤백/기록 실패/보존/삭제를 검증한다.
`RecommendationEventsControllerTest`는 JWT 요구/입력 제한/오류 응답, `RecommendationTrackingObserverTest`는 큐 포화와 실패 지표를 검증한다.
실제 추천 bulk query의 모델/세대 정보는 기존 추천 MySQL 통합 테스트에서 검증한다.
`RecommendationTrackingHttpIntegrationTest`는 실제 HTTP 서버와 서명 JWT로 추천→노출/클릭→팔로우 및 DB 귀속을 검증한다.
집계 SQL도 실제 DB에서 노출/미노출 클릭, 정책 분리, 0 분모를 확인한다.
통합 테스트는 운영 DB가 아닌 전용 DB/Redis에서 실행한다. fixture 외 전체 테이블 삭제는 사용하지 않는다.

2026-10-08 검증: 별도 MySQL 8.4/Redis 7.2 환경에서 빈 DB에 V51까지 적용했다.
추천/팔로우/행동 수집/추천 API Key 관련 15개 클래스, 104개 테스트가 실패·오류·skip 없이 통과했고 `bootJar`도 성공했다.
이는 해당 기능과 관련 회귀 테스트 실행 결과이며 저장소 전체 테스트 실행 결과는 아니다.
