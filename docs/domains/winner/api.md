# Winner API

## 공개 Winner 조회

### `GET /api/events/{eventId}/winners`

- 권한: `PUBLIC`
- Path Variable: `eventId` (`Long`, 양수, 필수)
- Event 존재를 먼저 확인한 뒤 해당 Event의 `PUBLIC`·`COMPLETED` Drawing에 속한 Winner만
  `drawNo ASC`, `rankInDrawing ASC` 순으로 반환한다. 따라서 공개된 INITIAL과 공개된 REDRAW
  결과를 함께 조회하고, PRIVATE Drawing의 결과는 포함하지 않는다.
- 응답을 만들 때만 이름과 전화번호를 마스킹한다. Winner와 Member의 원본 데이터는 바꾸지 않으며,
  관리자 결과 조회 및 당첨자 본인 조회의 원본 개인정보 정책과 분리한다.

```json
{
  "code": "SUCCESS",
  "data": {
    "eventId": 10,
    "winners": [
      {
        "winnerId": 100,
        "drawingId": 20,
        "name": "권*준",
        "phone": "010-****-5678",
        "rankInDrawing": 1
      }
    ]
  },
  "message": null
}
```

| 코드 | 조건 |
| --- | --- |
| `VALIDATION_FAILED` | eventId가 누락·0 이하이거나 형식이 올바르지 않음 |
| `RESOURCE_NOT_FOUND` | 요청한 Event 또는 Winner가 가리키는 Member가 존재하지 않음 |
