# Winner API

## 공개 Winner 조회

### `GET /api/events/{eventId}/winners`

- 권한: `PUBLIC`
- Path Variable: `eventId` (`Long`, 양수, 필수)
- Event 존재를 먼저 확인한 뒤 해당 Event의 `PUBLIC`·`COMPLETED` Drawing에 속한 Winner만
  `drawNo ASC`, `rankInDrawing ASC` 순으로 반환한다. 따라서 공개된 INITIAL과 공개된 REDRAW
  결과를 함께 조회하고, PRIVATE Drawing의 결과는 포함하지 않는다.
- 각 Winner는 `drawNo`와 `drawType`으로 INITIAL·REDRAW 이력을 구분한다. 운영 상태는 공개하지 않는다.
- 응답을 만들 때만 이름과 전화번호를 마스킹한다. Winner와 Member의 원본 데이터는 바꾸지 않으며,
  관리자 결과 조회 및 당첨자 본인 조회의 원본 개인정보 정책과 분리한다.

#### 조회 경계

공개 범위(`PUBLIC`·`COMPLETED`)와 회차·순위 정렬을 한 번의 DB 조회에서 보장하기 위해 Winner
Repository가 Drawing을 읽기 전용으로 join한다. 이 경로는 Entity가 아닌 Projection만 반환하고
Drawing 상태를 변경하지 않는다. Drawing 상태 변경은 Drawing 도메인의 Service만 수행한다.

```json
{
  "code": "SUCCESS",
  "data": {
    "eventId": 10,
    "winners": [
      {
        "winnerId": 100,
        "drawingId": 20,
        "drawNo": 0,
        "drawType": "INITIAL",
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
| `RESOURCE_NOT_FOUND` | 요청한 Event가 존재하지 않음 |
| `SYSTEM_ERROR` | Winner가 가리키는 Member가 없는 내부 데이터 정합성 오류. 상세는 외부에 노출하지 않음 |

## 내 Winner 조회

### `GET /api/me/winners`

- 권한: `USER`
- Query Parameter: `userId` (`Long`, 양수, 필수). 인증 미도입 단계의 호출자 식별자다.
- 먼저 Member 존재를 검증한 뒤, `memberId = userId`인 Winner만 반환한다. 따라서 다른 사용자의
  Winner는 조회 결과에 포함되지 않는다.
- `PUBLIC`·`COMPLETED` Drawing에 속한 Winner의 불변 데이터와 1:1 WinnerManagement의 현재 상태를
  함께 반환한다. PRIVATE 또는 미완료 Drawing 결과는 당첨자 본인에게도 노출하지 않는다. 공개된
  INITIAL·REDRAW Winner를 `drawNo ASC`, `rankInDrawing ASC` 순으로 반환한다.
- 목록이 비어 있으면 빈 배열을 정상 반환한다.

```json
{
  "code": "SUCCESS",
  "data": [
    {
      "winnerId": 100,
      "eventId": 10,
      "drawingId": 20,
      "drawNo": 0,
      "drawType": "INITIAL",
      "rankInDrawing": 1,
      "appliedTicketCount": 3,
      "winnerCreatedAt": "2026-09-19T10:00:00Z",
      "winnerManagementId": 300,
      "winnerManagementStatus": "SELECTED",
      "winnerManagementCreatedAt": "2026-09-19T10:00:00Z",
      "winnerManagementUpdatedAt": "2026-09-19T10:00:00Z"
    }
  ],
  "message": null
}
```

| 코드 | 조건 |
| --- | --- |
| `VALIDATION_FAILED` | userId가 누락·0 이하이거나 형식이 올바르지 않음 |
| `RESOURCE_NOT_FOUND` | userId에 해당하는 Member가 존재하지 않음 |
