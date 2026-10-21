# 실제 저장 태그 추천 (#736)

기존 추천 화면은 아래 INTEREST/FOLLOW 경로를 사용한다. 과거 개인화/인기순 API는 호환용으로 남지만 이 화면의 후보를 보충하는 데 사용하지 않는다. 조회는 회원 관심 분야를 저장하거나 모델 태그를 새로 부여하지 않는다.
자격은 현재 공개 확인 태그, 순위는 배치 임베딩이다. 실제 분류의 의미 정확도 보증은 아니다.

## 공개 API (JWT 필수)

- `GET /api/me/creator-recommendations/tag-catalog`: `{taxonomyVersion,taxonomyHash,tags,maxParents:3,maxTopics:9}`.
  tags는 `{type:PARENT|TOPIC,code,name,description}` 배열이다. 기존 `/api/interests`의 버전과 구분한다.
- `GET /api/me/creator-recommendations/by-tags`: query `taxonomyVersion`, `parentCodes`, `topicCodes`, `size`(기본 10, 1~20).
  코드는 반복 query 또는 쉼표 구분이다. 선택이 있으면 카탈로그 응답의 버전을 필수로 보낸다.

### 기존 추천 화면의 두 영역

같은 `/by-tags`에 `source`를 보낸다. 기본값 `EXPLICIT`는 기존 명시 코드 검색이며, `INTEREST`/`FOLLOW`에는 명시 코드·taxonomyVersion을 함께 보내지 않는다. size 상한은 동일하다.

- `source=INTEREST`: 회원이 가입/분야 편집에서 저장한 관심 분야 코드만 읽는다. 승인 콘텐츠 taxonomy의 PARENT에 존재하는 동일 코드만 연결한다. 두 taxonomy 버전은 독립이며 이름 유사도/추측 매핑으로 확장하지 않는다. 정확 일치한 현재 확인 태그 후보 안에서 임베딩 점수로 정렬한다. 분야 편집 저장 후 재조회에도 같은 규칙이다.
- `source=FOLLOW`: 전체 팔로우 중 현재 소개와 확인 태그가 유효한 seed를 읽는다. seed의 실제 부모/세부 코드 합집합과 후보 태그를 OR 비교한다. 같은 세부는 동일 태그, 같은 부모의 서로 다른 세부는 관련 태그로 취급한다. 서로 다른 부모 사이의 유사 관계는 임의로 만들지 않는다. 3개 부모/9개 세부 제한은 EXPLICIT 검색 선택 제한이며 여러 팔로우의 합집합에는 적용하지 않는다.
- 결과 policyVersion은 `SAVED_TAG_INTEREST_V1`/`SAVED_TAG_FOLLOW_V1`. 각 항목에 실제 기여 `interestCodes`/`seedCreatorIds`를 제공한다. INTEREST에 팔로우를 섞거나 빈 관심 분야를 FOLLOW/인기순으로 대체하지 않는다.
- 두 경로 모두 본인·전체 기팔로우, 소개 없음, 미확인/과거 확인, 잘못된 taxonomy/config를 제외한 뒤 Top-N. 20 요청에 2 적격이면 2명, 0이면 빈 목록. 순위 미생성 후보만 있으면 PENDING이다. 확인 없는 seed를 소개 임베딩만으로 추측하지 않는다.
- 아직 추천 추적 snapshot을 만들지 않으므로 requestId=null. FE 기존 카드/분야 편집/두 영역 디자인은 유지하며 새 태그 탭을 추가하지 않는다.

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
- 점수는 Creator별 `(inputRevision, confirmationId)`와 taxonomy/configHash로 검증한다.
  소개·확인이 바뀐 후보나 아직 점수가 없는 후보만 제외하고, 유효한 나머지 후보는 READY로 반환한다.
  선택 태그에 현재 적격 후보가 있지만 유효한 점수가 하나도 없으면 PENDING/빈 목록이다.
  적격 후보 자체가 없으면 READY/빈 목록이다. FE는 PENDING을 순위 갱신 안내로 처리하며 다른 후보로 대체하지 않는다.
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
V91 singleton 행 잠금·트랜잭션으로 점수 JSON과 V92 후보별 점수를 함께 원자 교체한다.
내부 적재는 전체 snapshotHash 검증을 유지하며, 공개 조회는 V92의 후보별 revision·확인 ID·taxonomy/configHash를 비교한다.
V92 점수의 applicationSequence도 V91 현재 번호와 같아야 한다. 롤링 배포 중 구버전이 V91만 갱신하면
신버전은 이전 V92 점수를 숨기며 새 배치 적재 전까지 PENDING으로 처리한다.
중복·누락·미등록 쌍·점수 범위/정밀도 오류는 INVALID_CONTENT_TAG를 유지하고 message와 로그에 고정 사유 코드를 남긴다.
저장 확인 tags가 중복·비문자열 등으로 손상되었으면 해당 Creator만 snapshot·검색에서 제외한다.

## 운영 경계

BE를 먼저 배포하고 LLM #82 별도 명령/opt-in 작업자를 활성화한다. 확인만 바뀌어도 일일 순회에서 재조회한다.
카탈로그는 승인 taxonomy만 읽는다. 공개 검색은 선택 태그의 확인 후보를 SQL에서 선별하고,
V92 `(tag_type, tag_code, creator_id)` 인덱스의 해당 점수를 읽는다. 전체 snapshot SHA-256 생성이나 V91 점수 JSON 읽기/파싱을 하지 않는다.
후보별 최신 소개 hash 검증은 선별한 행에만 수행한다. 짧은 캐시로 변경 직후 과거 확인을 노출하지 않는다.
배치 inputs/apply는 전체 snapshot·점수 JSON 계약을 유지한다. 원문 사본은 DB에 추가하지 않는다.
태그별 후보가 많은 경우에는 여전히 후보 수에 비례하므로 규모 확대 전 조회 지연·packet 크기와 실행 계획을 측정한다.
V92 배포 직후 기존 V91 JSON은 자동 이관하지 않는다. 작업자를 멈추고 LLM saved-tags 상태의 current.json을 보관 후
상태 경로에서 제거한 뒤 apply를 실행한다. 서버 lastApplicationSequence보다 큰 번호로 새 전체 snapshot을 적재해야 한다.
배치 전까지 점수가 없는 선택은 PENDING이며, 새 후보 추가·한 후보의 소개/확인 변경이 다른 후보 점수를 무효화하지 않는다.
