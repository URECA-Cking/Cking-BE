# 실제 저장 태그 추천 (#736)

기존 개인화 추천·인기순 fallback과 별도다. 회원 관심 분야를 저장하거나 모델 태그를 새로 부여하지 않는다.
자격은 현재 공개 확인 태그, 순위는 배치 임베딩이다. 실제 분류의 의미 정확도 보증은 아니다.

## 공개 API (JWT 필수)

- `GET /api/me/creator-recommendations/tag-catalog`: `{taxonomyVersion,taxonomyHash,tags,maxParents:3,maxTopics:9}`.
  tags는 `{type:PARENT|TOPIC,code,name,description}` 배열이다. 기존 `/api/interests`의 버전과 구분한다.
- `GET /api/me/creator-recommendations/by-tags`: query `taxonomyVersion`, `parentCodes`, `topicCodes`, `size`(기본 10, 1~20).
  코드는 반복 query 또는 쉼표 구분이다. 선택이 있으면 카탈로그 응답의 버전을 필수로 보낸다.

```text
?taxonomyVersion=approved-content-v1&parentCodes=FOOD&topicCodes=FOOD_BAKING&size=20
```

```json
{
  "policyVersion":"SAVED_TAG_M2_V1",
  "rankingStatus":"READY",
  "recommendationRequestId":null,
  "items":[{
    "creatorId":21,"creatorName":"베이킹 채널","introText":"빵을 만듭니다",
    "profileImageUrl":"https://example.com/profile.png","slug":"baking","score":0.81,
    "matchedTags":[{"type":"TOPIC","code":"FOOD_BAKING","name":"베이킹","description":"빵 만들기"}]
  }]
}
```

- 여러 선택은 OR이며 상위/세부는 정확한 코드와 종류를 비교한다. 부모만 같다고 형제 세부를 포함하지 않는다.
- 상위 최대 3개·세부 최대 9개, 중복·미등록 코드·다른 버전·잘못된 size는 400 `INVALID_CONTENT_TAG`다.
- 빈 선택은 READY/빈 목록이다. 활성·승인 콘텐츠 taxonomy가 없으면 선택 검색·카탈로그는 409
  `CONTENT_TAXONOMY_UNAVAILABLE`다. 없는/잘못된 JWT는 401이다.
- 후보는 현재 소개 revision/hash, 자동 configHash, 본인 확인 taxonomyHash와 일치해야 한다.
  미확정 자동 후보·이전 소개의 확인·비활성/미승인 taxonomy는 제외한다.
- 소개 null/공백, Space 없음, 본인·기팔로우를 제외한다. 2명만 맞으면 2명, 없으면 빈 목록이다.
- matchedTags는 실제 저장 태그와 선택의 교집합이다. 개인화의 interestCodes와 다른 필드다.
- 자격 검증 뒤 OR 최대 코사인 점수 DESC, creatorId ASC로 정렬하고 size를 적용한다.
  전역 Top-20을 먼저 잘라 관련 후보를 누락하지 않는다. 인기순/팔로우/다른 태그 보충은 없다.
- 현재 태그 snapshot과 점수가 다르면 PENDING/빈 목록이다. 일치 후보가 아예 없으면 READY/빈 목록이다.
  FE는 PENDING을 순위 갱신 안내로 처리하며 다른 후보로 대체하지 않는다.
- 이 API는 행동 추적 snapshot을 아직 만들지 않아 requestId는 항상 null이다. FE는 노출·클릭을 수집하지 않고
  다른 개인화 응답 ID를 재사용하지 않는다. 기존 개인화 추적은 그대로다.
- 콘텐츠 snapshot이 상위 표시명을 따로 제공하지 않으므로 상위 name은 코드 자체다. 상위 설명은 자식 세부 정의를
  합치며 자식이 없으면 코드다. 세부 이름·설명은 승인 snapshot 그대로다.

## 내부 배치 계약 saved-tags-v1

기존 CONTENT_TAG_BATCH 전용 Bearer(`CKING_CONTENT_TAG_ACCESS_TOKEN`)만 허용한다.
JWT·추천 API Key를 확대하지 않는다. 소개 원문 외부 전송은 별도 승인 대상이다.

### GET /api/internal/content-tags/saved-recommendations/inputs

REPEATABLE READ 전체 snapshot이며 MVP에서 페이지를 나누지 않는다.

```json
{
  "contractVersion":"saved-tags-v1","taxonomyVersion":"approved-content-v1",
  "taxonomyHash":"64자리","generationConfigHash":"64자리",
  "tags":[{"type":"TOPIC","code":"FOOD_BAKING","name":"베이킹","description":"빵 만들기"}],
  "items":[{"creatorId":21,"introduction":"빵 만들기","inputRevision":1,"confirmationId":30,"parents":[],"topics":["FOOD_BAKING"]}],
  "snapshotHash":"64자리","lastApplicationSequence":0
}
```

예시는 wire 구조다. 실제 부모/세부 확인 규칙은 기존 본인 확인 계약을 따른다.
items는 creatorId ASC, parents/topics는 코드 ASC, tags는 type/code ASC다. 소개 정규화는
CRLF/CR→LF, NFC, trim 없는 content-tags-v1이다. 공백 소개는 제외한다.
snapshotHash는 snapshotHash/lastApplicationSequence를 제외한 응답 전체의 compact JSON SHA-256이다.
키 정렬, UTF-8, ensure_ascii=False, BOM·마지막 개행 없음이다.
확인 ID·태그·소개 revision·설정·taxonomy 변경을 모두 반영한다. 이름·이미지는 조회 시 읽는다.

### PUT /api/internal/content-tags/saved-recommendations/rankings

```json
{
  "applicationSequence":1,"snapshotHash":"64자리","method":"SAVED_TAG_M2_V1",
  "modelVersion":"BAAI/bge-m3@deepinfra-v1",
  "scores":[{"type":"TOPIC","code":"FOOD_BAKING","creatorId":21,"score":0.81}]
}
```

현재 전체 적격 태그/Creator 쌍을 정확히 한 번 보낸다. 빈 snapshot은 scores=[]다.
score는 -1~1, 소수점 8자리 이하다. 미등록 쌍·중복·누락은 400이다.
응답은 `{applied:true|false,applicationSequence,snapshotHash}`다.
같은 적용 번호·payload는 false, 같은 번호의 다른 payload는 409 CONTENT_PAYLOAD_CONFLICT다.
더 작은 번호 또는 변경 snapshot은 409 CONTENT_INPUT_CONFLICT다. 더 큰 번호의 A→B→A 복귀는 허용한다.
V91 singleton 행 잠금·트랜잭션으로 전체 점수를 원자 교체한다. 과거 점수는 조회 hash 검증으로 숨긴다.

## 운영 경계

BE를 먼저 배포하고 LLM #82 별도 명령/opt-in 작업자를 활성화한다. 확인만 바뀌어도 일일 순회에서 재조회한다.
전체 확인 후보 PK 조인과 JSON 파싱·전체 점수 JSON을 쓰는 MVP다. 원문 사본은 DB에 추가하지 않는다.
규모 확대 전 응답/DB packet 크기와 조회 지연을 측정하고 태그별 inverted index·분할 저장을 검토한다.
후보 하나의 태그가 바뀌어도 전체 순위가 PENDING이며 기존 개인화는 계속 동작한다.
