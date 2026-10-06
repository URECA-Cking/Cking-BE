# Interest

회원이 고르는 관심 분야의 분류체계를 버전·해시와 함께 저장한다. 외부 API 계약은 [Interest API](api.md)를 따른다.
추천 도메인은 이 분류체계의 `(taxonomyVersion, interestCode)`로 분야별 추천 후보를 연결한다.

## 책임

- 분류체계 버전과 그 버전의 관심 분야(코드·이름·분류 기준문·노출 순서)를 저장한다.
- 선택 가능한 분야 목록을 조회한다(활성 분류체계의 활성 분야).
- 저장된 분야 행으로 `taxonomyHash`를 다시 계산해 등록된 해시와 맞는지 검증할 수 있다(`InterestTaxonomyVerifier`).
- Cking-LLM이 만든 분야별 추천 후보 묶음을 검증해 새 세대로 원자 교체하고 분야별 현재 포인터를 관리한다(아래 "추천 후보 적재").
- 회원의 관심 분야 조회와 전체 교체 저장을 제공한다(`MemberInterestService`). 같은 요청을 반복해도 최종 상태가 같다.

## 소유 데이터

`interest_taxonomy`, `interest_category` (V41), `v0.2` 시드 17개 분야 (V42), `member_interest` (V43), `interest_recommendation_generation`·`interest_recommendation_candidate`·`interest_recommendation_state` (V44).

| 테이블 | 핵심 제약 |
| --- | --- |
| `interest_taxonomy` | PK `taxonomy_version`, `taxonomy_hash` UNIQUE·소문자 SHA-256 64자 CHECK, 활성 분류체계는 최대 1개(`active_marker` 생성 칼럼 UNIQUE) |
| `interest_category` | PK `(taxonomy_version, interest_code)`, UNIQUE `(taxonomy_version, display_order)`, `display_order > 0`, FK → `interest_taxonomy` |
| `interest_recommendation_generation` | 분야별 후보 묶음 메타데이터(`method`·`model_version`·`input_hash`). `UNIQUE(taxonomy_version, interest_code, input_hash)`(멱등 키), 분류 복합 FK |
| `interest_recommendation_candidate` | 세대별 후보(`creator_id`·`score`·`rank_no`). `UNIQUE(generation_id, rank_no)`, `UNIQUE(generation_id, creator_id)`, `rank_no > 0`, 점수 -1.0~2.0 CHECK |
| `interest_recommendation_state` | PK `(taxonomy_version, interest_code)`, `current_generation_id` UNIQUE, 복합 FK로 **자기 분야의 세대만** 가리킴 |
| `member_interest` | PK `(member_id, taxonomy_version, interest_code)`, FK → `member`, 복합 FK `(taxonomy_version, interest_code)` → `interest_category`, `selected_at` |

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
