# 추천 후보 자격 계약 (BE #738 / LLM #85)

기존 M2/M3/M4 모델과 개인화 RRF 점수·정렬은 유지한다. 후보 자격을 먼저 검사하고 `size`를 적용한다.
관련 후보만 0~N개 반환하며 빈 결과를 인기순·전체 목록·다른 분야로 보충하지 않는다.

## 적재 근거

두 추천 적재 API의 최상위에 `eligibility`를 추가한다. 기존 인증·경로·실행 번호 계약은 같다.

```json
{
  "policyVersion": "RELATED_V1",
  "taxonomyVersion": "v0.2",
  "taxonomyHash": "<등록된 분류체계의 lowercase SHA-256>",
  "profiles": [
    {"creatorId": 20, "introductionHash": "<소개글 SHA-256>", "tags": ["FOOD"]}
  ]
}
```

- `profiles`는 후보 전체이며 유사 추천은 seed도 포함한다. 빈 분야 세대는 `[]`, 빈 유사 세대는 seed 한 건이다.
- ID는 양의 정수이며 오름차순·중복 없음, 각 `tags`는 등록된 활성 분야 코드의 사전순·중복 없는 **0~3개**다. 3개를 채우지 않는다.
- 분류체계는 등록된 버전·해시여야 한다. 분야 적재는 요청의 버전·해시와도 일치해야 한다.
- 소개 지문은 CRLF/CR을 LF로 바꾸고 Python `str.strip()` 범위의 양끝 Unicode 공백을 제거한 UTF-8 SHA-256이다.
  NFC 변환은 하지 않는다. Java는 `isWhitespace || isSpaceChar || U+0085`로 같은 범위를 처리한다.
- 분야 후보는 해당 `interestCode`를 실제 분류 태그에 포함해야 한다. 유사 후보는 seed와 공통 분류 태그가 있어야 한다.
- 새 적재 시 ID 순으로 Space를 잠그고 현재 소개 지문을 검사한다. 빈 소개·누락 Space·지문 불일치·무관 태그는 400 `INVALID_RECOMMENDATION_RESULT`다.
- 근거는 `V93`의 `recommendation_eligibility(source_kind, generation_id, proof)`에 세대와 같은 트랜잭션으로 보존한다.
  소개 원문은 추가 저장하지 않는다. 같은 실행의 근거 변경·삭제도 409 충돌이다. 동일 재전송은 멱등이다.

분류 태그는 **배치가 판단한 자격 근거**다. 사용자 관심 분야·추천 이유를 Creator의 확정 태그로 저장하지 않는다.
BE는 모델을 재호출하거나 사람이 태그를 확인하도록 강제하지 않는다. 의미 분류의 정확도 자체는 모델 평가 대상이다.

## 조회·전환

- 조회마다 현재 소개의 지문을 확인한다. 소개 수정·공백화·Space 삭제 시 이전 근거의 후보를 즉시 제외한다.
- 관심 분야는 선택한 분야의 유효 후보만 사용한다. 선택 관심 분야가 있으면 팔로우 후보도 그중 하나의 태그와 일치해야 한다.
- 공통 태그가 있는 유사 후보만 공개하며, 자격 검사 후 요청 수를 적용한다. 인기순 보충은 없다.
- 자격 검사 뒤 기존 유효 source 평균·반올림·동점 처리를 적용한다. 양쪽 모두 무효면 `FOLLOW_PERSONALIZED_V2`, `items: []`다.
- 구 payload의 저장·멱등 계약은 호환한다. 단, `eligibility` 없는 구 세대는 공개하지 않는다.
- 배포는 **BE → 새 정책으로 배치 재생성·적재 → FE** 순서다. 재적재 전에는 추천이 비어 있을 수 있다.
  운영 데이터 외부 전송·유료 모델 실행은 별도 승인 범위이며, 이 PR 테스트는 합성 모델만 사용한다.

검증: `RecommendationEligibilityIntegrationTest`, 기존 추천·적재·HTTP 회귀 테스트와 LLM 저장소의 `--suite small-pool` 실제 HTTP/DB E2E.
