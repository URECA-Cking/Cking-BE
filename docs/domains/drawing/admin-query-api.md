+# 관리자 Drawing 조회 API

### `GET /api/admin/drawings/{drawingId}`

- 권한: `ADMIN`
- 호출자 식별: Access JWT의 `@CurrentMemberId` (`Long`); query parameter `userId`는 받지 않는다.
- 처리 순서: Spring Security가 `ADMIN`을 먼저 인가한 뒤 요청 Member의 존재와 업무 권한을 확인하고 Drawing을 조회한다.
- 조회는 read-only이며 Drawing, Winner, Event 상태를 변경하지 않는다.

응답 `data`는 다음 필드를 포함한다.

```json
{
  "drawingId": 20,
  "eventId": 10,
  "snapshotId": 30,
  "drawNo": 0,
  "drawType": "INITIAL",
  "status": "COMPLETED",
  "visibility": "PRIVATE",
  "drawMethod": "WEIGHTED",
  "algorithmVersion": "WEIGHTED_V1",
  "prizeAlgorithmVersion": "PRIZE_WEIGHTED_V1",
  "winnerCount": 2,
  "requestedBy": 1,
  "createdAt": "2026-09-17T00:00:00Z",
  "firstStartedAt": "2026-09-17T00:01:00Z",
  "completedAt": "2026-09-17T00:02:00Z",
  "publishedAt": null
}
```

### `GET /api/admin/drawings/{drawingId}/result`

- 권한: `ADMIN`
- 호출자 식별: Access JWT의 `@CurrentMemberId` (`Long`); query parameter `userId`는 받지 않는다.
- 처리 순서: Spring Security가 `ADMIN`을 먼저 인가한 뒤 요청 Member의 존재와 업무 권한을 확인하고, `COMPLETED` Drawing의 결과만 조회한다.
- Winner는 `rankInDrawing ASC` 순서로 반환한다.
- Winner의 사용자 정보는 Member 도메인 조회 계약을 통해 일괄 조회하며, Drawing 도메인은 Member Entity나 Repository를 직접 참조하지 않는다.

응답 `data`는 다음 필드를 포함한다.

```json
{
  "drawingId": 20,
  "winners": [
    {
      "winnerId": 100,
      "eventId": 10,
      "drawingId": 20,
      "userId": 2,
      "name": "홍길동",
      "phone": "010-0000-0002",
      "email": "hong@example.com",
      "rankInDrawing": 1,
      "appliedTicketCount": 7,
      "prizeKey": "FIRST",
      "prizeDisplayName": "1등 상품",
      "prizePriority": 1,
      "createdAt": "2026-09-17T00:02:00Z"
    }
  ]
}
```

두 API의 오류 코드는 다음과 같다.

| 코드 | 조건 |
| --- | --- |
| `VALIDATION_FAILED` | `drawingId`가 누락·0 이하이거나 형식이 올바르지 않음 |
| `RESOURCE_NOT_FOUND` | 요청한 Member가 존재하지 않음 |
| `FORBIDDEN` | 요청한 Member가 `ADMIN`이 아님 |
| `DRAWING_NOT_FOUND` | 요청한 Drawing이 존재하지 않음 |
| `DRAWING_NOT_COMPLETED` | 결과 조회 대상 Drawing의 상태가 `COMPLETED`가 아님 |

### `GET /api/events/{eventId}/winners`

`PUBLIC`이면서 `COMPLETED`인 INITIAL Drawing의 당첨 결과만 공개한다. 응답은 `eventId`, `drawingId`와
`rankInDrawing ASC` Winner 목록을 포함하며, 각 Winner에 `winnerId`, `userId`, `rankInDrawing`,
`prizeKey`, `prizeDisplayName`, `prizePriority`를 반환한다. PRIVATE Drawing은 `DRAWING_NOT_FOUND`로
처리해 비공개 결과 존재 여부를 노출하지 않는다.
