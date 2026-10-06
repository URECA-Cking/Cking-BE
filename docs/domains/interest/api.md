# Interest API

공통 응답 봉투와 오류는 [공통 API 규약](../../common/api.md)을 따른다. 아래 예시는 `data`만 적는다.

## GET /api/interests

회원이 고를 수 있는 관심 분야 목록이다. 활성 분류체계의 활성 분야를 `displayOrder` 순으로 반환하며 인증 없이 조회한다.
조회 중 외부 모델을 호출하지 않는다.

```json
{
  "taxonomyVersion": "v0.2",
  "maxSelection": 3,
  "items": [
    { "interestCode": "FITNESS", "name": "운동·건강", "displayOrder": 1 },
    { "interestCode": "FOOD", "name": "요리·푸드", "displayOrder": 2 }
  ]
}
```

- `taxonomyVersion`은 관심 분야 저장 요청에 그대로 쓴다. `maxSelection`은 서버가 정한 선택 상한이다.
- 분류 기준문(`description`)은 화면 노출용이 아니라 해시·추천 계산용이라 응답에 포함하지 않는다.
- 활성 분류체계가 없으면 정상 200으로 `taxonomyVersion: null`, `items: []`를 반환한다.
- **응답 형식은 FE 협의 후 확정한다.**

## GET /api/me/interests

Bearer Access JWT가 필수이며 호출자는 `@CurrentMemberId`로 식별한다. 선택을 분류체계의 `displayOrder` 순으로 반환한다.

```json
{ "taxonomyVersion": "v0.2", "interestCodes": ["FITNESS", "TRAVEL"] }
```

- 선택이 없으면 `interestCodes: []`와 현재 활성 분류체계 버전을 반환한다(활성 분류체계도 없으면 `taxonomyVersion: null`).
- JWT가 없거나 유효하지 않으면 `UNAUTHORIZED`다.

## PUT /api/me/interests

기존 선택 **전체를 교체**한다. 같은 요청을 반복해도 최종 상태가 같다(멱등).

```json
{ "taxonomyVersion": "v0.2", "interestCodes": ["FITNESS", "TRAVEL"] }
```

응답은 GET과 같다. `interestCodes`는 0~3개이며 빈 배열은 전체 해제다.

| 상황 | 응답 |
| --- | --- |
| `interestCodes` 4개 이상, 중복, null·blank 코드, `taxonomyVersion` 누락 | `VALIDATION_FAILED`(400) |
| 등록되지 않았거나 활성이 아닌 `taxonomyVersion`, 그 버전에 없거나 비활성인 코드 | `VALIDATION_FAILED`(400) |
| JWT 없음·무효 | `UNAUTHORIZED`(401) |
| 회원이 존재하지 않음 | `RESOURCE_NOT_FOUND`(404) |

- 검증에 실패하면 기존 선택은 그대로다.
- 유지되는 선택은 `selected_at`을 바꾸지 않는다. 같은 회원의 동시 저장은 직렬화된다.

## FE 연동 가이드

관심 분야 선택(`/api/interests`, `/api/me/interests`)과 개인화 추천([`/api/me/creator-recommendations`](../creator/api.md#get-apimecreator-recommendations))을
화면에 붙일 때 오류가 나기 쉬운 지점이다.

**관심 분야 선택**

- 저장 요청의 `taxonomyVersion`은 **`GET /api/interests` 응답 값**을 보낸다. `GET /api/me/interests`의 버전은 과거에 저장한 선택의 버전이라
  분류체계가 바뀌면 활성 버전과 달라 저장이 400이 된다. 활성 분류체계가 없으면(`taxonomyVersion: null`) 저장할 수 없다.
- 400 `VALIDATION_FAILED`는 4개 이상, 중복, 없는 코드, 비활성 버전을 구분하지 않고 필드별 상세도 없다. 최대 선택 수는 응답의 `maxSelection`으로
  FE가 미리 막는다. 선택 해제는 빈 배열 PUT이다.
- `/api/interests`는 인증이 필요 없지만 유효하지 않거나 만료된 Access JWT를 붙여 보내면 401이 될 수 있다. 기존 401 → refresh → 재시도 흐름이 처리한다.

**개인화 추천**

- `items: []`는 오류가 아니라 정상이다. 관심 분야·팔로우가 모두 없거나, 유효한 후보가 없거나, LLM 배치가 아직 적재하지 않은 경우이며 인기순 fallback은
  없다. 빈 화면 처리가 필요하다.
- `policyVersion`은 `HYBRID_PERSONALIZED_V1`(관심 분야+팔로우), `INTEREST_PERSONALIZED_V1`(관심 분야만), `FOLLOW_PERSONALIZED_V2`(팔로우만 또는 신호 없음) 중
  하나다. 화면 분기에는 쓰지 않아도 되고, 모르는 값이 와도 `items`를 그대로 그리면 된다.
- `aggregateScore`는 정렬용이라 화면에 노출하지 않는다. 서로 다른 `policyVersion`의 점수는 비교할 수 없다. `interestCodes`·`seedCreatorIds`는 "추천 이유"
  표시에 쓸 수 있으며 빈 배열일 수 있다.
- 본인, 이미 팔로우한 Creator, Creator Space가 없는 Creator는 나오지 않는다. `size`는 1~20이고 범위를 벗어나면 400이다.
- 반영 시점: 관심 분야 저장과 팔로우 변경은 다음 조회부터 바로 반영된다(조회 시 계산). 후보 목록 자체와 Creator 소개글 변경은 LLM 배치가 새로
  적재한 뒤 반영된다.

## PUT /api/admin/interests/{interestCode}/recommendations

Cking-LLM 배치가 한 관심 분야의 완결된 후보 묶음을 적재한다. 인증은 추천 적재 API Key 또는 ADMIN JWT이며 규칙은
[Creator API의 유사 추천 적재](../creator/api.md#put-apiadmincreatorscreatoridsimilar)와 같다(`X-Cking-Recommendation-Key`가 있으면
키만 판단, 비었거나 틀리면 401. 키가 없으면 ADMIN JWT와 DB ADMIN 재검증, 인증 없음 401, ADMIN 아님 403).

```json
{
  "taxonomyVersion": "v0.2",
  "taxonomyHash": "<64자리 lowercase SHA-256>",
  "interestCode": "SPORTS",
  "method": "INTEREST_M3_V1",
  "modelVersion": "BAAI/bge-m3@deepinfra-v1",
  "inputHash": "<64자리 lowercase SHA-256>",
  "candidates": [
    {
      "interestCode": "SPORTS",
      "creatorId": 20,
      "score": 0.83451234,
      "rank": 1,
      "method": "INTEREST_M3_V1",
      "modelVersion": "BAAI/bge-m3@deepinfra-v1",
      "inputHash": "<최상위와 동일>"
    }
  ]
}
```

- Path·최상위·후보별 `interestCode`, `method`(최대 20자), `modelVersion`(최대 255자), `inputHash`는 모두 같아야 한다.
- 후보는 0~100건이다. `rank`는 1부터 끊김 없이 이어지고 점수 내림차순, 동점은 `creatorId` 오름차순이다. `score`는 `-1.0~2.0`이며 소수점 8자리 이하이고, 한 묶음에서 `creatorId`는 중복될 수 없다.
- `taxonomyVersion`은 등록된 버전이고 `taxonomyHash`는 그 버전에 등록된 해시와 같아야 한다.
- 빈 후보도 최상위 메타데이터가 있으면 정상 세대로 저장·활성화해 이전 추천을 비운다.

```json
{
  "taxonomyVersion": "v0.2",
  "interestCode": "SPORTS",
  "generationId": 100,
  "inputHash": "<64자리>",
  "candidateCount": 20,
  "applied": true
}
```

신규 세대는 `applied=true`, 현재 활성 세대와 완전히 같은 payload 재전송은 기존 `generationId`와 `applied=false`다.

| 상황 | 코드 | HTTP |
| --- | --- | --- |
| 키 헤더가 비었거나 무효, JWT 없음·무효 | `UNAUTHORIZED` | 401 |
| JWT가 ADMIN이 아님 | `FORBIDDEN` | 403 |
| 필드 형식·길이 위반(해시 형식, 점수 범위·자릿수, `rank` 범위, 빈 값) | `VALIDATION_FAILED` | 400 |
| 등록되지 않은 `taxonomyVersion`, `taxonomyHash` 불일치, 메타데이터 불일치, 정렬·`rank`·중복 위반 | `INVALID_RECOMMENDATION_RESULT` | 400 |
| 그 버전에 없는 `interestCode`, 존재하지 않는 후보 Creator | `RESOURCE_NOT_FOUND` | 404 |
| 현재 활성 세대와 같은 `inputHash`에 다른 payload | `RECOMMENDATION_INPUT_CONFLICT` | 409 |
| 이미 교체된 과거 `inputHash` | `STALE_RECOMMENDATION_INPUT` | 409 |

- 하나라도 위반하면 묶음 전체를 거부하고 기존 활성 결과는 유지한다. 한 번 교체된 과거 `inputHash`는 재활성화하지 않는다.
- 멱등 키는 `(taxonomyVersion, interestCode, inputHash)`, 현재 포인터 기준은 `(taxonomyVersion, interestCode)`다.
