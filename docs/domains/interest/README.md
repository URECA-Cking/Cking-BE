# Interest

회원이 고르는 관심 분야의 분류체계를 버전·해시와 함께 저장한다. 외부 API 계약은 [Interest API](api.md)를 따른다.
추천 도메인은 이 분류체계의 `(taxonomyVersion, interestCode)`로 분야별 추천 후보를 연결한다.

## 책임

- 분류체계 버전과 그 버전의 관심 분야(코드·이름·분류 기준문·노출 순서)를 저장한다.
- 선택 가능한 분야 목록을 조회한다(활성 분류체계의 활성 분야).
- 저장된 분야 행으로 `taxonomyHash`를 다시 계산해 등록된 해시와 맞는지 검증할 수 있다(`InterestTaxonomyVerifier`).
- Cking-LLM이 만든 분야별 추천 후보 묶음을 검증해 새 세대로 원자 교체하고 분야별 현재 포인터를 관리한다(아래 "추천 후보 적재").
- 회원의 관심 분야 조회와 전체 교체 저장을 제공한다(`MemberInterestService`). 같은 요청을 반복해도 최종 상태가 같다.
- 상위 분야 아래의 세부 주제 세트(`subtopic-v1`, 85개)를 별도 버전으로 저장하고 회원의 세부 선택을 상위 선택과 같은 Transaction에서 저장한다.
  세부 선택은 아직 추천 계산에 쓰이지 않는다(후속 이슈).

## 소유 데이터

`interest_taxonomy`, `interest_category` (V41), `v0.2` 시드 17개 분야 (V42), `member_interest` (V43), `interest_recommendation_generation`·`interest_recommendation_candidate`·`interest_recommendation_state` (V44),
`interest_subtopic_set`·`interest_subtopic`·`member_interest_subtopic` (V68). 세부 주제 시드는 85개 확정 전이라 Flyway에 없다(아래 "세부 주제").

| 테이블 | 핵심 제약 |
| --- | --- |
| `interest_taxonomy` | PK `taxonomy_version`, `taxonomy_hash` UNIQUE·소문자 SHA-256 64자 CHECK, 활성 분류체계는 최대 1개(`active_marker` 생성 칼럼 UNIQUE) |
| `interest_category` | PK `(taxonomy_version, interest_code)`, UNIQUE `(taxonomy_version, display_order)`, `display_order > 0`, FK → `interest_taxonomy` |
| `interest_recommendation_generation` | 분야별 후보 묶음 메타데이터(`method`·`model_version`·`input_hash`). `UNIQUE(taxonomy_version, interest_code, input_hash)`(멱등 키), 분류 복합 FK |
| `interest_recommendation_candidate` | 세대별 후보(`creator_id`·`score`·`rank_no`). `UNIQUE(generation_id, rank_no)`, `UNIQUE(generation_id, creator_id)`, `rank_no > 0`, 점수 -1.0~2.0 CHECK |
| `interest_recommendation_state` | PK `(taxonomy_version, interest_code)`, `current_generation_id` UNIQUE, 복합 FK로 **자기 분야의 세대만** 가리킴 |
| `member_interest` | PK `(member_id, taxonomy_version, interest_code)`, FK → `member`, 복합 FK `(taxonomy_version, interest_code)` → `interest_category`, `selected_at` |
| `interest_subtopic_set` | PK `subtopic_version`, `parent_taxonomy_version` → `interest_taxonomy`, `subtopic_hash` UNIQUE·SHA-256 CHECK, 활성 세트는 최대 1개(`active_marker`), `UNIQUE(subtopic_version, parent_taxonomy_version)` |
| `interest_subtopic` | PK `(subtopic_version, subtopic_code)`, UNIQUE `(subtopic_version, display_order)`, 복합 FK `(subtopic_version, parent_taxonomy_version)` → 세트(세트와 같은 상위 버전 강제), 복합 FK `(parent_taxonomy_version, parent_interest_code)` → `interest_category` |
| `member_interest_subtopic` | PK `(member_id, subtopic_version, subtopic_code)`, FK → `member`, 복합 FK → `interest_subtopic`, `selected_at` |

## 불변조건

- 분야 식별자는 `(taxonomyVersion, interestCode)`다. 같은 코드도 버전이 다르면 다른 분야로 본다.
- 등록한 버전의 해시와 분야 행(이름·설명·노출 순서)은 수정하지 않는다. 내용이 바뀌면 새 버전을 등록한다.
- 활성 분류체계는 최대 1개다. 활성이 없으면 목록은 비어 있고 `taxonomyVersion`은 `null`이다.
- 서버가 정하는 선택 상한은 3개(`InterestPolicy.MAX_SELECTION`)이며 0개도 허용한다. 상한은 DB로 강제할 수 없어 저장 서비스가
  회원 행을 `SELECT … FOR UPDATE`로 잠근 채 검증한다. 같은 회원의 동시 저장은 직렬화되어 최종 선택은 한 요청의 목록과 정확히 같다.
- 저장은 전체 교체다. 유지되는 선택은 행을 건드리지 않아 `selected_at`이 바뀌지 않고, 빠지는 선택만 삭제하며 새 선택만 추가한다.
- 고를 수 있는 것은 등록된 **활성** 분류체계의 **활성** 분야뿐이다. 중복·상한 초과·미등록·비활성 코드나 버전은 `VALIDATION_FAILED`다.
- 회원은 한 번에 한 분류체계 버전의 선택만 가진다. 버전이 바뀌는 마이그레이션은 별도 이슈다.
- 분류체계의 정본은 Cking-LLM-Benchmark의 `data/categories_v2.csv`(17개 상위 분야, `taxonomyVersion v0.2`)다. V42가 그 행을 `display_order`(CSV 행 순서)와 함께 시드하고, 해시는 LLM 계약 fixture의 `f77df7a0…8b35`다. 분류 내용이 바뀌면 시드를 고치지 않고 새 버전을 등록한다.

## 세부 주제(subtopic)

상위 17개 분야(`v0.2`)를 교체하지 않고 **별도 축**으로 추가했다. 세부는 초안이라 이름·경계가 바뀔 수 있어, 세부만 새 버전으로 올리면
상위 분류체계·기존 회원 선택·분야 추천 후보에는 영향이 없다. 안정되면 하나의 계층 분류체계로 합치는 것은 별도로 검토한다.

- 정본은 Cking-LLM-Benchmark `data/creator-subtopics.json`(`subtopic-v1-draft`)이며 필드 대응은 `code`→`subtopic_code`, `parent`→`parent_interest_code`,
  `name`, `definition`→`description`이다. 시드할 때 배열 순서를 `display_order`로 보존한다. `include`·`exclude`·`formats`는 저장하지 않는다.
- 해시(`InterestSubtopicHash`)는 [taxonomyHash 규칙](#taxonomyhash-규칙)과 정규화·이스케이프·SHA-256이 같고 행 형태만
  `{"subtopics":[{"code","parent","name","description"}]}`다(순서는 `display_order`). 초안의 해시 `7e06e15a…eb0f6`은
  `src/test/resources/fixtures/taxonomy/subtopic-v1.{canonical.json,sha256.txt}`와 같고 `MemberInterestSubtopicIntegrationTest`가 저장된 행에서 다시 계산해 확인한다.
- 회원 행 잠금(`SELECT … FOR UPDATE`) 하나로 상위·세부 저장을 같은 Transaction에서 직렬화한다. 세부 상한(상위당 3개, `InterestPolicy.MAX_SUBTOPIC_PER_PARENT`)과
  "부모가 이번 선택에 포함" 규칙은 DB로 강제할 수 없어 서비스가 검증한다.
- **시드는 아직 없다.** 정본이 `experimental_not_service_contract` 초안이고 이름·경계·부모가 확정되지 않았다. 마이그레이션은 한 번 적용되면 수정할 수 없고
  회원 선택이 그 코드에 묶이므로, 확정 전에는 세트를 등록하지 않는다. 활성 세트가 없으면 `GET /api/interests`는 `subtopicVersion: null`·`subtopics: []`이고
  세부 선택 저장은 `VALIDATION_FAILED`다. 테스트는 `fixtures/taxonomy/subtopic-v1-seed.sql`(초안 85개)을 직접 적재한다. 확정되면 그 내용으로 별도 마이그레이션을 추가한다.
- 세부 선택의 추천 계산 반영(세부 분류 적재·세부 키 후보·개인화 집계)은 범위 밖이다.

## 추천 후보 적재

`PUT /api/admin/interests/{interestCode}/recommendations`(계약은 [Interest API](api.md#put-apiadmininterestsinterestcoderecommendations)). 구조와 멱등 규칙은
[유사 추천 결과 저장 계약](../creator/similarity-recommendation.md)과 같고 기준축만 `creatorId`에서 `(taxonomyVersion, interestCode)`다.
LLM은 분야별 후보 묶음만 적재하며(분야 조합은 만들지 않는다), 사용자별로 합치는 일은 개인화 조회가 맡는다.

- 전체 묶음을 검증한 뒤 새 세대와 후보를 저장하고 **같은 Transaction에서 분야 포인터를 교체**한다. 과거 세대는 삭제하지 않는다.
- 같은 분야의 동시 적재는 `interest_category` 행을 `SELECT … FOR UPDATE`로 잠가 직렬화한다. **잠금은 다른 읽기보다 먼저** 잡아야
  한다. MySQL REPEATABLE READ에서는 첫 일반 SELECT가 스냅샷을 만들어, 그 뒤에 잠금을 얻으면 기다린 뒤에도 앞선 적재가 커밋한
  세대를 보지 못하고 중복 키 오류가 난다(동시성 테스트로 확인).
- 분류체계는 **등록된 버전**이고 `taxonomyHash`가 등록된 해시와 같아야 한다. 활성 여부는 묻지 않아 신버전 후보를 활성화 전에 적재할 수 있다.
- 분야별 공개 조회 API는 없다. 저장된 후보는 개인화 조회 내부에서만 읽는다.

## 개인화 조회에서의 사용

개인화 추천(`GET /api/me/creator-recommendations`)은 `InterestRecommendationQueryService`로 회원이 고른 분야의 현재 활성 후보를 읽는다.
쿼리는 `member_interest`의 `(taxonomy_version, interest_code)`와 같은 버전의 `interest_recommendation_state`만 연결하므로 다른 분류체계
버전의 후보로 대체하지 않는다. 활성 세대가 없거나 빈 세대인 분야, 선택하지 않은 분야의 후보는 행으로 나오지 않는다. Creator Space가 없는
후보는 SQL에서 걸러내며, 점수·정책 분기는 [개인화 집계](../creator/similarity-recommendation.md#개인화-집계issue-410-442)를 따른다.

## taxonomyHash 규칙

Cking-LLM(Python)과 같은 값이 나와야 하므로 아래 순서를 그대로 따른다. 구현은 `InterestTaxonomyHash`이며
`String.trim()`·`strip()`·Jackson 직렬화를 쓰지 않는다.

1. 각 필드의 `CRLF`·`CR`을 `LF`로 바꾼다.
2. 필드 앞뒤에서 ASCII 공백·탭·LF만 제거한다. NBSP·전각 공백은 제거하지 않고 내부 공백은 보존한다.
3. Unicode NFC로 정규화한다.
4. 행 순서(`display_order` 순)를 유지해 `{"categories":[{"code":…,"name":…,"description":…}]}`를 공백 없는 JSON으로 만든다.
5. JSON 문자열은 `"`·`\`·0x20 미만 제어문자만 이스케이프하고 한글은 이스케이프하지 않는다(Python `json.dumps(ensure_ascii=False, separators=(",", ":"))`와 같다).
6. BOM·끝 줄바꿈 없는 UTF-8 바이트의 SHA-256을 소문자 hex로 낸다.

`active`는 해시에 포함하지 않으며, 비활성 분야도 해시 대상이다. 두 언어의 일치는 Cking-LLM-Benchmark의 공통 fixture(`tests/fixtures/taxonomy/`, 커밋 `b6bef4f`)를
`src/test/resources/fixtures/taxonomy/`에 그대로 복사해 canonical JSON 바이트와 SHA-256으로 검증한다
(`InterestTaxonomyHashFixtureTest`). fixture는 CRLF·CR을 보존해야 하므로 `.gitattributes`에서 `-text`로 고정한다.
그 밖의 단위 테스트 기대 해시는 같은 규칙을 별도로 구현한 Python으로 계산했다.

기대 해시를 다시 만들려면 아래 Python을 쓴다(Cking-LLM의 해시 함수와 같은 규칙이다).

```python
import csv, hashlib, json, unicodedata

def norm(v):
    v = v.replace("\r\n", "\n").replace("\r", "\n").strip(" \t\n")
    return unicodedata.normalize("NFC", v)

def taxonomy_hash(path):  # code,name,description 헤더의 UTF-8 CSV
    rows = csv.DictReader(open(path, encoding="utf-8-sig", newline=""))
    payload = {"categories": [{k: norm(r[k]) for k in ("code", "name", "description")} for r in rows]}
    text = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
    return hashlib.sha256(text.encode("utf-8")).hexdigest()
```
