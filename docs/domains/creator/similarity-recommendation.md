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

DB 정본은 `V40__add_creator_similarity_recommendation.sql`과 실행 번호를 도입한 `V46__add_recommendation_application_sequence.sql`이다.

- `creator_similarity_generation`: 원본 Creator와 `applicationSequence`, `method`, `modelVersion`, `inputHash`, 생성 시각을 보존한다.
- `creator_similarity_candidate`: 세대에 속한 후보와 점수·순위를 보존한다.
- `creator_similarity_state`: 원본 Creator별 현재 공개 `generationId` 한 개를 가리킨다.

적재 서비스는 원본 `creator` 행을 `FOR UPDATE`로 잠근 뒤 전체 계약을 먼저 검증한다. 새 generation과 모든 candidate를 저장한 다음 같은 DB Transaction에서 state 포인터를 바꾼다. 따라서 동시 공개 조회는 커밋 전의 완성 세대 또는 커밋 후의 완성 세대만 보며 부분 후보를 보지 않는다. 과거 generation과 candidate는 삭제하지 않는다.

빈 정상 결과도 candidate 행이 없는 generation으로 저장하고 state 포인터를 그 세대로 교체한다. 공개 조회는 생성 메타데이터와 빈 `candidates`를 반환한다.

## 적용 실행 순서 계약 (#473, LLM #52)

- 두 적재 API는 최상위 `applicationSequence`를 필수 JSON 정수로 받는다. 허용 범위는 1~9223372036854775807이다. `inputHash`는 내용 지문이며 실행 번호를 섞지 않는다.
- LLM 배치 조정자는 BE 대상 환경별로 하나의 증가 번호를 영속 발급하고, 한 실행의 모든 Creator·관심 분야 요청에 같은 번호를 쓴다. 동시 실행은 번호 발급을 직렬화해야 한다. 시각·임의 UUID로 순서를 추론하지 않는다.
- 멱등 키는 유사 추천 `(creatorId, applicationSequence)`, 관심 분야 `(taxonomyVersion, interestCode, applicationSequence)`다. 현재 대상 번호와 같고 내용 지문·메타데이터·후보·점수·순위가 같으면 기존 generationId와 `applied=false`를 반환한다. 같은 번호의 다른 payload는 `RECOMMENDATION_INPUT_CONFLICT`(409)다.
- 대상의 현재 번호보다 작으면, 한 번도 저장되지 않았던 실행도 `STALE_RECOMMENDATION_INPUT`(409)로 거부한다. 오래된 요청은 payload 충돌보다 순서 검증을 우선한다. 더 큰 번호는 같은 과거 inputHash라도 새 generation으로 원자 교체한다. A(1)→B(2)→A(3)가 가능하며 이후 B(2)는 거부된다.
- 대상 Creator 또는 관심 분야 행 잠금으로 동시 요청을 직렬화하고 DB 유일 제약으로 같은 실행의 중복 generation을 막는다. 후보 저장·포인터 교체는 한 트랜잭션이다. 빈 후보도 같은 순서·멱등 규칙으로 적용한다. 실패 시 기존 포인터와 후보를 유지한다.
- 순서 차단은 **대상별**이다. 전역 원자 전환이나 전역 실행 fence는 제공하지 않는다. A 복귀가 아직 적용되지 않은 대상에는 지연 B가 적용될 수 있으며, 복귀 A의 더 큰 번호가 그 대상을 최종 교체한다. 새 실행은 삭제·누락된 대상을 포함해 이전 대상에 빈 묶음까지 전달해야 한다.
- 부분 실패 복구는 기존 실행 번호·payload를 유지하여 미완료 대상을 재전송한다. 더 최신 실행이 시작됐다면 과거 실행을 중단하고 최신 실행을 모든 대상에 완료한다. 409를 성공 처리하거나 무조건 재시도하지 않는다. 동일 번호 payload 충돌은 산출물 오류를 조사하고, 의도적인 새 적용은 더 큰 번호를 발급한다.
- 전환 시 구형 배치를 먼저 중지하고 BE 마이그레이션과 필수 필드 클라이언트를 함께 배포한다. 필드 누락·0·음수는 `VALIDATION_FAILED`(400), 내부 Command 위반은 `INVALID_RECOMMENDATION_RESULT`다. V46은 기존 이력을 `-generation_id`로 예약하므로 첫 새 실행은 1부터 시작 가능하다. 기존 양수 번호를 사용한 환경에서는 재설치·체크포인트 초기화로 번호를 재사용하지 않는다. 환경 전환·DB 복원 후에는 대상 환경의 마지막 발급/적용 최대 번호보다 크게 발급한다.
- ADMIN JWT 재검증, API Key 우선 판단과 401/403 계약은 유지한다. 이 계약은 사용자 승인으로 확정한 BE/LLM 연동 규격이며 LLM #52의 클라이언트 반영이 필요하다.

## #473 검증 기록 (2026-10-07)

- 전용 MySQL 8.4(`cking_473`, 13306)와 Redis 7.2(16379)에서 검증했다. 테스트는 롤백 또는 고유 fixture만 사용하며 공용 테이블 전체를 삭제하지 않는다.
- `compileJava`, `compileTestJava`, `bootJar` 성공. 추천·인증·추첨 관련 선택 테스트 222건 성공, 실패·오류·skip 0건.
- 선택 범위: `*CreatorSimilarity*Test`, `*InterestRecommendation*Test`, `*RecommendationApplicationSequenceIntegrationTest`, `*RecommendationApiKey*Test`, `*CreatorRecommendationIntegrationTest`, `*PersonalizedRecommendationInterestIntegrationTest`, `kr.co.cking.drawing.domain.*`, `kr.co.cking.drawing.application.*ServiceTest`.
- A→B→새 실행 A, 동일 실행 재시도·payload 충돌, 미적용 과거 실행 차단, B 일부 적용 후 A 복귀, 동일/서로 다른 실행 동시 적재, 빈 세대, 후보 저장 실패 후 포인터 보존·재개를 검증했다.
- V46 신규 마이그레이션 성공. 별도 격리 스키마에서 각 추천 종류의 기존 이력 2건을 음수 번호로 보존하고, 같은 내용 지문을 새 양수 번호로 재적재해 3건이 유지됨을 확인했다.
- ADMIN 권한 회수 재검증, API Key 우선 인증과 401/403 회귀 테스트 통과. 실제 Python HTTP 배치 E2E는 LLM #53 범위다.

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

## 개인화 집계(Issue #410, #442)

회원이 고른 관심 분야의 현재 활성 후보([관심 분야 추천 적재](../interest/README.md#추천-후보-적재))와 팔로우한 모든 Creator를 seed로 삼은
현재 활성 후보를 합쳐 개인화 목록을 만든다. 요청 중 모델 API를 호출하지 않으며 `interest_recommendation_state`·
`creator_similarity_state`가 가리키는 저장 결과만 읽는다. 정책의 정본은 Cking-LLM-Benchmark의
`docs/reference/hybrid-personalized-contract.md`와 공용 fixture(`fixtures/hybrid_personalized_v1.json`, 커밋 `c23f4db`)이며,
이 구현은 그 fixture 18개 case 전체와 일치해야 한다(`PersonalizedCreatorRecommendationPolicyFixtureTest`).

### 유효 source와 점수

- M2/M3/M4 raw score는 직접 비교하지 않는다. 후보의 세대별 원래 `rank`만 쓴다(제외·재정렬 뒤에도 rank를 새로 붙이지 않는다).
- **유효 source**: 선택한 관심 분야 또는 팔로우 seed 하나가 현재 활성 세대를 갖고 비어 있지 않으며, 본인·이미 팔로우한 Creator·
  Creator Space 없는 Creator를 제외한 뒤에도 후보가 1명 이상 남아야 유효하다. 세대 없음·빈 세대·전부 제외된 source는 평균 분모에서 빠진다.
  기여도가 0으로 반올림되어도 후보가 있는 source는 유효하다.
- source별 후보 기여도는 `round8(1 / (60 + rank))`이다(`60 + rank`는 long으로 더한다).
- 같은 그룹(관심 분야 / 팔로우) 안에서 후보별 기여도를 합하고 그 그룹의 **유효 source 수로 나눈 평균**을 `round8`한다.
  후보가 나오지 않은 유효 source도 분모에 들어간다.
- 두 그룹이 모두 유효하면 `round8(0.5 × 관심 평균 + 0.5 × 팔로우 평균)`이며 후보가 한 그룹에만 있으면 다른 쪽 평균은 0이다.
  한 그룹만 유효하면 그 그룹 평균을 그대로 점수로 쓴다(0.5를 곱하지 않는다).
- 반올림은 기여도·그룹 평균·최종 합 세 번 모두 소수점 8자리 HALF_UP이다. 끝에서 한 번만 반올림하면 결과가 달라진다.
- 최종 점수 내림차순, 동점이면 `creatorId` 오름차순이다. `interestCodes`는 ASCII 사전순, `seedCreatorIds`는 숫자 오름차순이다.

### 정책 분기

| 유효 관심 분야 | 유효 팔로우 | `policyVersion` |
| --- | --- | --- |
| 있음 | 있음 | `HYBRID_PERSONALIZED_V1` |
| 있음 | 없음 | `INTEREST_PERSONALIZED_V1` |
| 없음 | 있음 | `FOLLOW_PERSONALIZED_V2` |
| 없음 | 없음 | `FOLLOW_PERSONALIZED_V2`(빈 목록) |

정책 상수(60, 8자리, HALF_UP, 가중치 0.5/0.5)나 정렬 조건을 바꾸면 새 `policyVersion`과 fixture를 정의한다.

### 팔로우 단독 정책 변경(#410 → #442)

#410의 `FOLLOW_PERSONALIZED_V1`은 팔로우 seed의 기여도를 **합산**했다. 관심 분야와 섞는 정책이 모두 그룹 **평균**을 쓰므로
성원의 계약에 따라 팔로우만 있는 경우도 유효 seed 평균(`FOLLOW_PERSONALIZED_V2`)으로 통일했다. 모든 후보 점수가 같은 수로
나뉘므로 순서는 거의 같고(반올림 뒤 동점은 `creatorId` 순) `policyVersion`과 점수 값이 달라진다. V1은 더 이상 반환하지 않는다.

Creator Space가 없어 추천 카드를 만들 수 없는 후보는 집계 전에 제외하며(조회 SQL이 걸러낸다), `size`는 집계·정렬 뒤에 적용한다.
관심 분야 후보, 팔로우 seed 후보, 선택된 Creator와 Space는 각각 bulk query 하나씩으로 읽는다. 조회 경로에는 BGE-M3, GPT 등
모델 클라이언트 의존성이 없다.

## 요구사항 추적

| 완료 조건 | 구현·검증 |
| --- | --- |
| 비교 가능한 집계식·tie-breaker·정책 버전 | `PersonalizedCreatorRecommendationPolicy`, LLM 공용 fixture 18개 case 일치 테스트 |
| 활성 추천 후보 N+1 없는 조회 | `findActiveCandidatesBySeedCreatorIds`, `findActiveCandidatesByMemberId` bulk query |
| 본인·기팔로우·Space 없음·중복 제외와 size 정렬 | 집계 정책·Query Service 단위 테스트, MySQL 통합 테스트 |
| JWT API와 size 검증 | `CreatorRecommendationController`, MVC·Security 테스트 |
| Creator·Space 카드와 `interestCodes`·seed 근거 | bulk profile 조회와 Response DTO |
| 빈/부분 source와 모델 장애 독립 | 저장 Repository만 사용하는 Query Service와 빈 결과 테스트 |
