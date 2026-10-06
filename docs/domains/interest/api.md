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
