# Creator 신청 API

Creator 신청·심사 API의 상세 계약이다. 모든 성공·실패 응답은 [공통 API 규약](../../common/api.md)의 응답 봉투를 사용하며, 아래 Response 예시는 `data` 값이다.

## 상태와 권한

- 신청 상태는 `PENDING`, `APPROVED`, `REJECTED`다.
- Creator 여부는 `Member.role`이 아니라 승인된 `creator` 레코드 존재 여부로 판단한다.
- 신청자 조회는 본인만, 관리자 목록·승인·거절은 `member.role == ADMIN`만 가능하다.
- 승인·거절은 PENDING 신청만 처리한다. 심사 시 `reviewedBy`, `reviewedAt`을 기록한다.
- 응답 시각은 UTC RFC 3339 형식(예: `2026-09-16T00:30:00Z`)이다.

## POST /api/creator/applications

Bearer Access JWT가 필수이며 호출자는 `@CurrentMemberId`로 식별한다. 요청 본문은 없다.

| 상황 | HTTP | `data` |
| --- | --- | --- |
| 신규 신청 | 201 | `{ "applicationId": 1, "status": "PENDING" }` |
| 기존 PENDING 재요청 | 200 | 기존 신청과 같은 형식 |

- 이미 Creator인 Member는 `INVALID_STATE`다.
- 기존 PENDING 신청은 새로 만들지 않고 기존 결과를 반환한다.
- REJECTED 신청 뒤에는 새 신청을 만들 수 있다.
- 같은 사용자의 동시 신청 충돌은 `CONCURRENT_COMMAND`다.

## GET /api/creator/applications/me

Bearer Access JWT가 필수이며 호출자는 `@CurrentMemberId`로 식별한다. Query: `page`, `size`

본인의 신청 이력만 반환하며 기본 정렬은 `requestedAt DESC, applicationId DESC`다.

```json
{
  "items": [{
    "applicationId": 1,
    "status": "PENDING",
    "requestedAt": "2026-09-16T00:30:00Z",
    "reviewedAt": null,
    "rejectReason": null
  }],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1,
  "hasNext": false
}
```

심사 전 `reviewedAt`은 null이며, `rejectReason`은 REJECTED가 아닐 때 null일 수 있다.

## GET /api/admin/creator-applications

Bearer Access JWT와 ADMIN 역할이 필수이며 Controller는 `@CurrentMemberId`를 기존 관리자 업무 식별자로 전달한다. Query는 `page`, `size`다. 기본 정렬은 `requestedAt ASC, applicationId ASC`다.

```json
{
  "items": [{
    "applicationId": 1,
    "applicantUserId": 10,
    "applicantName": "홍길동",
    "status": "PENDING",
    "requestedAt": "2026-09-16T00:30:00Z",
    "reviewedBy": null,
    "reviewedAt": null,
    "rejectReason": null
  }],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1,
  "hasNext": false
}
```

## POST /api/admin/creator-applications/{id}/approve

Request Body는 없다. 인증된 관리자만 PENDING 신청을 승인할 수 있다. 승인, Creator 생성, 기본 미션(ATTENDANCE·LIKE) 초기화, Creator Space 생성은 하나의 DB transaction으로 처리한다. 미션 초기화 또는 Creator Space 생성이 실패하면 승인과 Creator 생성도 함께 롤백되며, Member당 Creator는 하나만 존재해야 한다. Creator Space 생성 계약(활성 템플릿 값 복사, slug 생성, 활성 템플릿 없음 정책)은 [creator/README.md](README.md#creator-space-자동-생성이슈-270)를 따른다.

```json
{ "applicationId": 1, "status": "APPROVED" }
```

성공은 200이다. 대상 또는 요청 관리자가 없으면 `RESOURCE_NOT_FOUND`, 관리자가 아니면 `FORBIDDEN`, PENDING이 아니면 `INVALID_STATE`, 승인·거절 경합은 `CONCURRENT_COMMAND`, 활성화된 기본 Creator Space 템플릿이 없으면 `NO_ACTIVE_SPACE_TEMPLATE`, 활성 템플릿은 있지만 `slugRule`이 무효하면(자리표시자 없음·중복, 치환 결과 길이 초과) `INVALID_ACTIVE_SPACE_TEMPLATE`다.

## POST /api/admin/creator-applications/{id}/reject

```json
{ "rejectReason": "거절 사유" }
```

- `rejectReason`은 필수이며 null, blank, trim 후 빈 문자열을 허용하지 않는다.
- 심사자와 심사 시각, 거절 사유를 기록한다.
- 성공은 200이며 응답은 `{ "applicationId": 1, "status": "REJECTED" }`다.
- 권한·상태·경합 오류는 승인 API와 같다. 유효하지 않은 거절 사유는 `VALIDATION_FAILED`다.

## 오류와 DB 제약

- 없는 Member 또는 신청은 `RESOURCE_NOT_FOUND`다.
- 신청별 상태 변경은 동시 명령으로 충돌할 수 있으며 `CONCURRENT_COMMAND`를 사용한다.
- 신청은 `memberId`, 심사는 신청 ID를 키로 한 MySQL advisory lock을 트랜잭션 완료까지 보유한다. 대기하지 못한 동시 명령은 `CONCURRENT_COMMAND`다. 이 정책은 별도 DB 스키마 변경을 요구하지 않는다.
- `creator.member_id`의 UNIQUE 제약이 Member당 Creator 하나를 최종 보장한다.
- `creator_application.reject_reason`은 최대 500자 저장 컬럼이다.

# Creator 유사 추천 결과 API(이슈 #393)

Cking-LLM이 오프라인으로 생성한 후보 묶음 적재와 공개 조회 계약이다. 저장 모델·원자 교체·멱등성 정본은 [유사 추천 결과 저장 계약](similarity-recommendation.md)을 따른다.

## PUT /api/admin/creators/{creatorId}/similar

인증은 둘 중 하나다. Path와 body 최상위 및 각 후보의 `creatorId`는 모두 같아야 한다.

- **배치(추천 적재 API Key)**: `X-Cking-Recommendation-Key` 헤더만 보낸다. 헤더가 있으면 JWT로 되돌아가지 않고 키만 판단한다. 키가 비었거나 틀리면 유효한 JWT를 같이 보내도 `UNAUTHORIZED`(401)다. 유효한 키는 `RECOMMENDATION_WRITE` 권한을 갖고, 이 권한은 추천 적재 엔드포인트에서만 인정한다(다른 `/api/admin/**`는 키로 접근할 수 없다).
- **수동 운영(ADMIN JWT)**: 키 헤더 없이 Bearer Access JWT와 ADMIN 역할이 필요하다. Security 1차 인가 뒤에도 Application에서 DB의 ADMIN 역할을 다시 확인한다(`RecommendationWriteAuthorizer`). 인증이 없으면 401, ADMIN이 아니면 `FORBIDDEN`(403)이다.

키는 서버 설정 `cking.recommendation.api-key-hashes`(환경변수 `CKING_RECOMMENDATION_API_KEY_HASHES`)에 SHA-256 hex 해시로만 둔다. 쉼표로 여러 개를 두어 키 교체 중 새 키와 이전 키를 함께 허용한다. 비어 있으면 키 인증은 항상 실패하며, 서버 시작 시 경고 로그를 남긴다.

- 키는 추측할 수 없는 무작위 값(32바이트 이상)이어야 한다. 서버는 해시만 받으므로 강도를 검사할 수 없고, 키 인증 실패에 횟수 제한은 없다. 생성·해시 예: `KEY=$(openssl rand -hex 32)`, `printf %s "$KEY" | shasum -a 256`. 원문은 배치 Secret에만 두고 서버에는 해시만 등록한다.
- 키 인증 실패는 키 값 없이 `메서드 경로 remote`만 WARN 로그로 남긴다.
- 배포: dev 서버는 Parameter Store `/cking/dev/CKING_RECOMMENDATION_API_KEY_HASHES`를 `deploy.sh`가 읽어 컨테이너 환경변수로 전달한다. 파라미터가 아직 없으면(`ParameterNotFound`) 빈 값으로 두고 키 인증만 비활성화한 채 배포하며, 권한·복호화·네트워크 오류 같은 다른 조회 실패는 배포를 실패시킨다.

```json
{
  "creatorId": 10,
  "method": "M4",
  "modelVersion": "BAAI/bge-m3@deepinfra-v1+gpt-5.4-nano-2026-03-17@creator-category-v1",
  "inputHash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "candidates": [{
    "creatorId": 10,
    "similarCreatorId": 20,
    "score": 1.08341234,
    "rank": 1,
    "method": "M4",
    "modelVersion": "BAAI/bge-m3@deepinfra-v1+gpt-5.4-nano-2026-03-17@creator-category-v1",
    "inputHash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  }]
}
```

- 후보는 0~100건이다. 원본·후보 Creator가 모두 존재해야 하고 자기 자신과 중복 후보는 허용하지 않는다.
- `score`는 `-1.0~2.0`, 소수점 8자리 이하다.
- `rank`는 1부터 연속이며 점수 내림차순, 동점 `similarCreatorId` 오름차순이다.
- 최상위 `method`(최대 20자), `modelVersion`(최대 255자), lowercase SHA-256 `inputHash`는 생성 세대의 메타데이터다.
- 후보가 있으면 기존 후보별 메타데이터만 보내는 계약도 허용한다. 최상위 메타데이터를 함께 보내면 모든 후보 값과 같아야 한다.
- 후보가 비어 있으면 최상위 생성 메타데이터가 필수다. 빈 세대를 활성화해 기존 공개 추천을 비운다.
- 전체 검증 뒤 새 세대와 후보를 저장하고 현재 포인터를 같은 Transaction에서 교체한다.

빈 정상 결과는 다음처럼 전달한다.

```json
{
  "creatorId": 10,
  "method": "M4",
  "modelVersion": "BAAI/bge-m3@deepinfra-v1+gpt-5.4-nano-2026-03-17@creator-category-v1",
  "inputHash": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
  "candidates": []
}
```

```json
{
  "creatorId": 10,
  "generationId": 100,
  "inputHash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "candidateCount": 1,
  "applied": true
}
```

신규 세대 적용은 `applied=true`다. 현재와 완전히 같은 payload 재전송은 기존 `generationId`와 `applied=false`를 반환한다. 같은 현재 hash에 다른 payload는 `RECOMMENDATION_INPUT_CONFLICT`, 이미 교체된 과거 hash는 `STALE_RECOMMENDATION_INPUT`이다. 묶음 형식·정렬 위반은 `INVALID_RECOMMENDATION_RESULT`, 없는 원본·후보·관리자(JWT 호출자)는 `RESOURCE_NOT_FOUND`, 관리자가 아니면 `FORBIDDEN`이다.

## GET /api/creators/{creatorId}/similar

인증 없이 조회한다. Query `size` 기본값은 5, 범위는 1~20이다. 저장된 현재 세대만 읽으며 조회 중 BGE-M3·GPT API를 호출하지 않는다.

```json
{
  "creatorId": 10,
  "method": "M4",
  "modelVersion": "BAAI/bge-m3@deepinfra-v1+gpt-5.4-nano-2026-03-17@creator-category-v1",
  "inputHash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "generatedAt": "2026-10-02T00:00:00Z",
  "candidates": [{
    "similarCreatorId": 20,
    "name": "추천 크리에이터",
    "score": 1.08341234,
    "rank": 1
  }]
}
```

활성 결과가 없으면 정상 200으로 `{ "creatorId": 10, "candidates": [] }`를 반환한다. 빈 결과 세대가 활성화된 경우에는 해당 세대의 `method`, `modelVersion`, `inputHash`, `generatedAt`과 빈 `candidates`를 반환한다. 존재하지 않는 Creator는 `RESOURCE_NOT_FOUND`, 범위를 벗어난 `size`는 `VALIDATION_FAILED`다. 관심사·인기순 대체 추천은 수행하지 않는다.

## GET /api/me/creator-recommendations

Bearer Access JWT가 필수이며 호출자는 `@CurrentMemberId`로 식별한다. Query `size` 기본값은 10,
범위는 1~20이다. 회원이 고른 관심 분야의 현재 활성 추천 후보와 팔로우한 Creator들의 현재 활성 유사 추천 저장 결과를
입력 신호에 따라 `HYBRID_PERSONALIZED_V1`·`INTEREST_PERSONALIZED_V1`·`FOLLOW_PERSONALIZED_V2` 중 하나로 합쳐 반환하고, 합친 결과가 비면 팔로워 수 순 인기 Creator(`POPULAR_FALLBACK_V1`)로 대체한다.
집계식과 정책 분기의 정본은 [개인화 집계](similarity-recommendation.md#개인화-집계issue-410-442)를 따른다.

```json
{
  "policyVersion": "HYBRID_PERSONALIZED_V1",
  "items": [{
    "creatorId": 20,
    "creatorName": "추천 크리에이터",
    "introText": "Creator Space 소개",
    "profileImageUrl": "https://example.com/profile.png",
    "aggregateScore": 0.01626124,
    "interestCodes": ["FOOD"],
    "seedCreatorIds": [2]
  }]
}
```

| 유효한 관심 분야 source | 유효한 팔로우 seed | `policyVersion` | 점수 |
| --- | --- | --- | --- |
| 1개 이상 | 1개 이상 | `HYBRID_PERSONALIZED_V1` | 0.5 × 관심 평균 + 0.5 × 팔로우 평균 |
| 1개 이상 | 0개 | `INTEREST_PERSONALIZED_V1` | 관심 평균 |
| 0개 | 1개 이상 | `FOLLOW_PERSONALIZED_V2` | 팔로우 평균 |
| 0개 | 0개 | `POPULAR_FALLBACK_V1` | 팔로워 수(인기순 대체, 아래 참고) |

- `interestCodes`는 이 후보의 점수에 기여한 관심 분야(ASCII 사전순), `seedCreatorIds`는 기여한 팔로우 seed(숫자 오름차순)이며
  기여하지 않은 쪽은 빈 배열이다.
- `aggregateScore`는 확률이나 절대 관련도가 아니라 해당 `policyVersion` 안에서만 의미 있는 정렬 점수다. 서로 다른
  `policyVersion`의 점수는 비교할 수 없다. M2/M3/M4 raw score가 아니라 rank 기반 RRF 기여도로 만든다.
- 이미 팔로우한 Creator와 호출자 본인의 Creator, Creator Space가 없는 Creator는 반환하지 않는다.
- 관심 분야는 회원이 선택한 분류체계 버전의 후보만 쓰고 다른 버전의 후보로 대체하지 않는다.
- 유효한 source가 없어 개인화 결과가 비면 `POPULAR_FALLBACK_V1`로 팔로워 수 내림차순(같으면 `creatorId` 오름차순) Creator를 같은 형식으로 반환한다.
  Creator Space가 없는 Creator, 본인, 이미 팔로우한 Creator는 제외하고 `size`를 적용한다. `aggregateScore`는 팔로워 수이고 `interestCodes`·`seedCreatorIds`는
  빈 배열이다. 개인화 결과가 하나라도 있으면 인기순을 섞지 않는다. 조회할 Creator가 없으면 `items: []`다. 요청 중 BGE-M3·GPT 호출은 수행하지 않는다.
- **변경 이력(#442)**: 팔로우만 있는 회원의 응답은 이전 `FOLLOW_PERSONALIZED_V1`(seed 기여도 합산)에서 `FOLLOW_PERSONALIZED_V2`(유효
  seed 평균)로 바뀌었다. 추천 순서는 거의 같고 `policyVersion`과 점수 값이 달라진다. `FOLLOW_PERSONALIZED_V1`은 더 이상 반환하지 않는다.
- **변경 이력(인기순 fallback)**: 개인화 결과가 비는 회원의 응답은 이전에는 `FOLLOW_PERSONALIZED_V2`와 `items: []`였으나 `POPULAR_FALLBACK_V1`과 인기 Creator 목록으로 바뀐다.
- 범위를 벗어난 `size`는 `VALIDATION_FAILED`, JWT가 없거나 유효하지 않으면 `UNAUTHORIZED`다.
