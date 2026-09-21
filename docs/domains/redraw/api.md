# Redraw API

## 관리자 RedrawRequest 생성

### `POST /api/admin/events/{eventId}/redraw-requests`

관리자가 공개된 Event의 미처리 결원을 서버 계산으로 고정한 RedrawRequest를 생성한다.

#### 요청

- Path Variable: `eventId` (`Long`, 양수, 필수)
- Body: `userId` (`Long`, 양수), `reason` (공백 제거 후 1~500자), `idempotencyKey` (UUID 표준 문자열)

```json
{
  "userId": 1,
  "reason": "당첨자 포기에 따른 재추첨이 필요합니다.",
  "idempotencyKey": "d2719c4a-1f9b-4dc4-a656-9a4bb37d8e70"
}
```

`vacancyCount`와 `originalDrawingId`는 요청 필드가 아니다. 서버가 Event의 최초 INITIAL Drawing과
`DECLINED`·`DISQUALIFIED` 상태의 미점유 Winner를 결정한다.

#### 성공 응답

새 요청은 `201 Created`, 동일 본문의 멱등 재요청은 `200 OK`로 현재 요청 정보를 반환한다.

```json
{
  "code": "SUCCESS",
  "data": {
    "redrawRequestId": 10,
    "eventId": 20,
    "originalDrawingId": 30,
    "vacancyCount": 2,
    "status": "REQUESTED",
    "executionStatus": "PENDING"
  },
  "message": null
}
```

#### 오류

| 코드 | 조건 |
| --- | --- |
| `VALIDATION_FAILED` | 경로·본문의 필수값, 사유 길이 또는 idempotencyKey 형식이 올바르지 않음 |
| `RESOURCE_NOT_FOUND` | 요청 Member 또는 Event가 존재하지 않음 |
| `FORBIDDEN` | 요청 Member가 ADMIN이 아님 |
| `INVALID_STATE` | Event가 PUBLISHED가 아니거나 INITIAL Drawing이 없음 |
| `NO_REDRAW_VACANCY` | 미점유 DECLINED·DISQUALIFIED 결원이 없음 |
| `IDEMPOTENCY_CONFLICT` | 기존 idempotencyKey에 다른 요청 본문이 전달됨 |
| `CONCURRENT_COMMAND` | 저장 충돌 후 동일 idempotencyKey의 기존 요청을 확인할 수 없음 |
