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

적재는 Bearer Access JWT의 ADMIN 역할과 Application의 관리자 검증을 모두 통과해야 한다. 공개 조회의 기본 `size`는 5, 범위는 1~20이다. 활성 결과가 없으면 메타데이터 없이 `candidates: []`를 반환한다. 상세 Request·Response와 오류는 [Creator API](api.md#creator-유사-추천-결과-api이슈-393)를 따른다.

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
