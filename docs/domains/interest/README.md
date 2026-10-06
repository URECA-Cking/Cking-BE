# Interest

회원이 고르는 관심 분야의 분류체계를 버전·해시와 함께 저장한다. 외부 API 계약은 [Interest API](api.md)를 따른다.
추천 도메인은 이 분류체계의 `(taxonomyVersion, interestCode)`로 분야별 추천 후보를 연결한다.

## 책임

- 분류체계 버전과 그 버전의 관심 분야(코드·이름·분류 기준문·노출 순서)를 저장한다.
- 선택 가능한 분야 목록을 조회한다(활성 분류체계의 활성 분야).
- 저장된 분야 행으로 `taxonomyHash`를 다시 계산해 등록된 해시와 맞는지 검증할 수 있다(`InterestTaxonomyVerifier`).
- 회원 선택 저장·조회는 별도 이슈(#430)에서 이 도메인에 추가한다.

## 소유 데이터

`interest_taxonomy`, `interest_category` (V41).

| 테이블 | 핵심 제약 |
| --- | --- |
| `interest_taxonomy` | PK `taxonomy_version`, `taxonomy_hash` UNIQUE·소문자 SHA-256 64자 CHECK, 활성 분류체계는 최대 1개(`active_marker` 생성 칼럼 UNIQUE) |
| `interest_category` | PK `(taxonomy_version, interest_code)`, UNIQUE `(taxonomy_version, display_order)`, `display_order > 0`, FK → `interest_taxonomy` |

## 불변조건

- 분야 식별자는 `(taxonomyVersion, interestCode)`다. 같은 코드도 버전이 다르면 다른 분야로 본다.
- 등록한 버전의 해시와 분야 행(이름·설명·노출 순서)은 수정하지 않는다. 내용이 바뀌면 새 버전을 등록한다.
- 활성 분류체계는 최대 1개다. 활성이 없으면 목록은 비어 있고 `taxonomyVersion`은 `null`이다.
- 서버가 정하는 선택 상한은 3개(`InterestPolicy.MAX_SELECTION`)이며 0개도 허용한다.
- 분류체계의 정본은 Cking-LLM의 분류 CSV(`categories_v2.csv`, 17개 상위 분야, `taxonomyVersion v0.2`)다. 17개 시드는 LLM 파일이 병합된 뒤 별도 마이그레이션으로 넣는다.

## taxonomyHash 규칙

Cking-LLM(Python)과 같은 값이 나와야 하므로 아래 순서를 그대로 따른다. 구현은 `InterestTaxonomyHash`이며
`String.trim()`·`strip()`·Jackson 직렬화를 쓰지 않는다.

1. 각 필드의 `CRLF`·`CR`을 `LF`로 바꾼다.
2. 필드 앞뒤에서 ASCII 공백·탭·LF만 제거한다. NBSP·전각 공백은 제거하지 않고 내부 공백은 보존한다.
3. Unicode NFC로 정규화한다.
4. 행 순서(`display_order` 순)를 유지해 `{"categories":[{"code":…,"name":…,"description":…}]}`를 공백 없는 JSON으로 만든다.
5. JSON 문자열은 `"`·`\`·0x20 미만 제어문자만 이스케이프하고 한글은 이스케이프하지 않는다(Python `json.dumps(ensure_ascii=False, separators=(",", ":"))`와 같다).
6. BOM·끝 줄바꿈 없는 UTF-8 바이트의 SHA-256을 소문자 hex로 낸다.

`active`는 해시에 포함하지 않으며, 비활성 분야도 해시 대상이다. 단위 테스트의 기대 해시는 같은 규칙을 별도로 구현한 Python으로
계산했다(한글·앞뒤 공백·탭·CRLF·NFC·이스케이프·NBSP 사례). Cking-LLM이 제공하는 fixture가 병합되면 같은 사례를 대조한다.

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
