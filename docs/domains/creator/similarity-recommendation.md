# Creator 유사 추천 결과 저장 계약

Issue #393에서 Cking-LLM이 오프라인으로 만든 단일 크리에이터 후보 묶음을 검증·보존하고 공개 조회하는 계약이다. 사용자 조회 요청에서는 BGE-M3, GPT 또는 다른 모델 API를 호출하지 않는다.

## LLM 전달 단위

한 요청은 한 원본 `creatorId`의 완결된 후보 묶음이다. 생성 세대의 `method`, `modelVersion`, `inputHash`는 최상위에 전달한다. 후보가 있으면 각 후보도 `creatorId`, `similarCreatorId`, `score`, `rank`, `method`, `modelVersion`, `inputHash`를 전달하며 최상위 메타데이터와 같아야 한다. 기존 후보별 메타데이터만 보내는 비어 있지 않은 payload도 호환한다.

- 원본과 모든 후보 Creator는 승인 완료되어 `creator` 행이 존재해야 한다.
- 자기 자신과 중복 `similarCreatorId`는 허용하지 않는다.
- `rank`는 1부터 끊김 없이 이어진다.
- 점수는 소수점 8자리 이하이며 `-1.0~2.0`이다. 점수 내림차순, 동점이면 `similarCreatorId` 오름차순이어야 한다.
- 한 묶음의 최상위 및 후보별 `method`, `modelVersion`, `inputHash`는 모두 같아야 한다.
- `inputHash`는 Cking-LLM 계약의 lowercase SHA-256 64자리다.
- 빈 후보 묶음도 최상위 생성 메타데이터가 있으면 정상 세대로 적재한다. 이 세대를 활성화해 이전 추천이 계속 공개되지 않게 한다.

하나라도 위반하면 묶음 전체를 `INVALID_RECOMMENDATION_RESULT` 또는 `RESOURCE_NOT_FOUND`로 거부하며 기존 활성 결과는 유지한다.

## 저장 모델과 원자 교체

DB 정본은 `V40__add_creator_similarity_recommendation.sql`이다.

- `creator_similarity_generation`: 원본 Creator와 `method`, `modelVersion`, `inputHash`, 생성 시각을 보존한다.
- `creator_similarity_candidate`: 세대에 속한 후보와 점수·순위를 보존한다.
- `creator_similarity_state`: 원본 Creator별 현재 공개 `generationId` 한 개를 가리킨다.

적재 서비스는 원본 `creator` 행을 `FOR UPDATE`로 잠근 뒤 전체 계약을 먼저 검증한다. 새 generation과 모든 candidate를 저장한 다음 같은 DB Transaction에서 state 포인터를 바꾼다. 따라서 동시 공개 조회는 커밋 전의 완성 세대 또는 커밋 후의 완성 세대만 보며 부분 후보를 보지 않는다. 과거 generation과 candidate는 삭제하지 않는다.

빈 정상 결과도 candidate 행이 없는 generation으로 저장하고 state 포인터를 그 세대로 교체한다. 공개 조회는 생성 메타데이터와 빈 `candidates`를 반환한다.

## 멱등성과 오래된 결과

멱등 키는 `(creatorId, inputHash)`다.

- 현재 활성 세대와 같은 `inputHash`·동일 payload 재전송은 새 행을 만들지 않고 `applied=false`로 기존 generation을 반환한다.
- 현재 `inputHash`는 같지만 메타데이터·후보·점수·순위가 다르면 `RECOMMENDATION_INPUT_CONFLICT`다.
- 한 번 저장됐으나 새 세대로 교체된 과거 `inputHash`를 다시 보내면 `STALE_RECOMMENDATION_INPUT`이다. 과거 결과를 재활성화하지 않는다.
- 처음 보는 `inputHash`는 새 세대로 저장한다. 전달 계약에 생성 시각·단조 증가 버전이 없으므로, 한 번도 저장되지 않은 두 해시 사이의 시간 순서는 BE가 추론하지 않는다.

## 외부 API와 조회 경계

- 적재: `PUT /api/admin/creators/{creatorId}/similar`
- 공개 조회: `GET /api/creators/{creatorId}/similar?size=5`

적재는 배치용 추천 적재 API Key(`X-Cking-Recommendation-Key`) 또는 ADMIN JWT로 인증한다. JWT는 Security 인가와 Application의 DB ADMIN 재검증을 모두 통과해야 하고, 적재 서비스(`CreatorSimilarityResultService.replace`)는 인증 방식과 회원 ID에 의존하지 않는다. 공개 조회의 기본 `size`는 5, 범위는 1~20이다. 활성 결과가 없으면 메타데이터 없이 `candidates: []`를 반환한다. 상세 Request·Response와 오류는 [Creator API](api.md#creator-유사-추천-결과-api이슈-393)를 따른다.

## 요구사항 추적

| Issue #393 완료 조건 | 구현·검증 |
| --- | --- |
| LLM 계약·저장 단위·갱신·멱등 문서화 | 이 문서와 Creator API 문서 |
| Flyway 제약·인덱스 | V40 migration의 세대·후보·현재 포인터, unique·FK·check |
| 전체 묶음 검증 후 원자 교체 | `CreatorSimilarityResultService`, 실제 MySQL 통합 테스트 |
| 공개 조회와 size 검증 | `CreatorSimilarityControllerTest` |
| 자기 자신·중복·없는 Creator·오래된 hash·빈 정상 결과 | Service·Controller 단위 테스트와 통합 테스트 |
| API 인덱스·Creator API·DB·RTM 갱신 | `docs/api-index.md`, `api.md`, V40, `management/rtm.csv` |
| 모델 API 장애와 조회 분리 | 조회 서비스는 DB Repository만 의존하며 모델 클라이언트를 주입하지 않음 |

## 팔로우 기반 개인화 집계(Issue #410)

`FOLLOW_PERSONALIZED_V1`은 사용자가 팔로우한 모든 Creator를 seed로 삼아 각 seed의 현재 활성 후보를
합친다. 요청 중 모델 API를 호출하지 않으며, `creator_similarity_state`가 가리키는 저장 결과만 읽는다.

### 집계식과 정렬

- M2와 M4의 raw score 범위는 직접 비교하지 않는다. 후보의 세대별 `rank`만 사용한다.
- seed `s`에서 후보 `c`의 기여도는 `round(1 / (60 + rank(s, c)), 8)`이다.
- 후보의 `aggregateScore`는 모든 seed 기여도의 합이다. 여러 seed에 나온 동일 후보는 하나로 합친다.
- `aggregateScore DESC`, 동점이면 `creatorId ASC`로 정렬한다.
- `seedCreatorIds`는 해당 후보에 기여한 seed ID를 중복 없이 오름차순으로 반환한다.
- 이미 팔로우한 Creator와 호출자 본인의 Creator는 집계 전에 제외한다.

정책 상수 60, 소수점 8자리 HALF_UP 반올림, 정렬 조건 중 하나라도 바꾸면 새 `policyVersion`을 사용한다.
활성 후보가 없는 seed는 기여하지 않는다. 팔로우가 없거나 모든 seed의 활성 후보가 비었거나 필터링 뒤
후보가 없으면 인기순 fallback 없이 빈 목록을 반환한다.

Creator Space가 없어 추천 카드를 만들 수 없는 후보는 집계 전에 제외하며, `size`는 카드 반환이 가능한 후보를
기준으로 적용한다. 여러 seed의 후보는 하나의 bulk query로 읽고, 선택된 Creator와 Space도 각각 bulk query로
읽는다. 조회 경로에는 BGE-M3, GPT 등 모델 클라이언트 의존성이 없다.

## Issue #410 요구사항 추적

| 완료 조건 | 구현·검증 |
| --- | --- |
| 비교 가능한 집계식·tie-breaker·정책 버전 | `FollowBasedCreatorRecommendationPolicy`, 고정 fixture 테스트 |
| 활성 추천 후보 N+1 없는 조회 | `findActiveCandidatesBySeedCreatorIds` bulk query |
| 본인·기팔로우·중복 제외와 size 정렬 | 집계 정책·Query Service 단위 테스트 |
| JWT API와 size 검증 | `CreatorRecommendationController`, MVC·Security 테스트 |
| Creator·Space 카드와 seed 근거 | bulk profile 조회와 Response DTO |
| 빈/부분 seed와 모델 장애 독립 | 저장 Repository만 사용하는 Query Service와 빈 결과 테스트 |
