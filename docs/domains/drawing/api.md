# Drawing API

## 관리자 Drawing 공개

### `POST /api/admin/drawings/{drawingId}/publish`

완료된 INITIAL Drawing 결과를 공개한다. Controller는 요청 형식만 처리하고, 관리자 검증과
공개 상태 전이·동시성 제어는 시스템3 `DrawingPublicationService`에 위임한다.

#### 요청

- Path Variable: `drawingId` (`Long`, 양수, 필수)
- Body: 관리자 `userId` (`Long`, 양수, 필수)

```json
{ "userId": 1 }
```

#### 성공 응답

`200 OK`와 공통 `ApiResponse`를 반환한다. 최초 공개와 이미 공개된 Drawing의 재요청 모두
현재 공개 상태를 같은 형식으로 반환한다.

```json
{
  "code": "SUCCESS",
  "data": {
    "drawingId": 10,
    "eventId": 20,
    "visibility": "PUBLIC",
    "publishedAt": "2026-09-18T12:00:00Z"
  },
  "message": null
}
```

#### 처리 계약

- 요청자는 존재하는 `ADMIN` Member여야 한다.
- Drawing은 `INITIAL`, `COMPLETED` 상태여야 한다.
- `PRIVATE` Drawing은 `PUBLIC`으로 전이하고 연결 Event를 `DRAW_COMPLETED → PUBLISHED`로 전이한다.
- 이미 `PUBLIC`인 Drawing은 연결 Event도 `PUBLISHED`인 경우에만 상태 변경 없이 성공한다.
- Drawing과 Event 행을 모두 비관적으로 잠가 동시 요청을 직렬화한다.
- Controller는 시스템3 `DrawingPublicationService.publish(drawingId, userId)`를 한 번만 호출한다.

| 코드 | 조건 |
| --- | --- |
| `VALIDATION_FAILED` | drawingId 또는 userId가 누락·0 이하이거나 형식이 올바르지 않음 |
| `RESOURCE_NOT_FOUND` | 요청한 Member가 존재하지 않음 |
| `FORBIDDEN` | 요청한 Member가 ADMIN이 아님 |
| `DRAWING_NOT_FOUND` | Drawing이 존재하지 않음 |
| `DRAWING_NOT_COMPLETED` | Drawing.status가 COMPLETED가 아님 |
| `DRAWING_TYPE_NOT_SUPPORTED` | Drawing.drawType이 INITIAL이 아님 |
| `INVALID_STATE` | Event 상태가 기대 상태가 아니거나 Drawing·Event 공개 상태가 불일치함 |
