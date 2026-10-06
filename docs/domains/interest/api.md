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
