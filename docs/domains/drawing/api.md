# Drawing 실행·재시도·공개 API

관리자 Drawing 명령 API의 요청·응답·오류 계약을 정의한다. 조회와 재현 검증은 각각의 전용 문서에서
관리한다.

| 구분 | API | 문서 |
| --- | --- | --- |
| 실행 | `POST /api/admin/events/{eventId}/drawings` | 이 문서 |
| 조회·결과 조회 | `GET /api/admin/events/{eventId}/drawings/initial`, `GET /api/admin/drawings/{drawingId}`, `GET /api/admin/drawings/{drawingId}/result` | [관리자 Drawing 조회 API](admin-query-api.md) |
| 재현 검증 | `POST /api/admin/drawings/{drawingId}/verify`, `GET /api/admin/drawings/{drawingId}/verification-history` | [Drawing 재현 검증 API](verification-api.md) |
| 재시도·공개 | `POST /api/admin/drawings/{drawingId}/retry`, `POST /api/admin/drawings/{drawingId}/publish` | 이 문서 |

## 관리자 INITIAL Drawing 실행

### `POST /api/admin/events/{eventId}/drawings`

관리자 권한과 Event·Snapshot 조건을 검증한 뒤 INITIAL Drawing을 끝까지 실행한다. Seed 생성,
엔진 호출, Winner·WinnerManagement 저장, Drawing 완료, Event 상태 전이를 하나의 Transaction으로
처리한다.

#### 요청

- Path Variable: `eventId` (`Long`, 양수, 필수)
- 호출자 식별: Access JWT의 `@CurrentMemberId` (`Long`); Request Body `userId`는 받지 않는다.

#### 성공 응답

`200 OK`와 공통 `ApiResponse`를 반환한다. `data`에는 `drawingId`, `eventId`, `status`,
`winnerCount`를 포함한다.

#### 처리 계약

- 요청자는 존재하는 `ADMIN` Member여야 한다.
- Event는 삭제되지 않은 `CLOSED` 상태여야 하며, 공식 Snapshot Hash 검증을 통과해야 한다.
- 완료된 INITIAL Drawing 재요청은 기존 결과를 반환한다. `READY` 또는 `RUNNING`이면 동시 명령으로
  거부하고, `FAILED`는 아래 Retry 계약을 사용한다.

| 코드 | 조건 |
| --- | --- |
| `VALIDATION_FAILED` | eventId가 누락·0 이하이거나 형식이 올바르지 않음 |
| `RESOURCE_NOT_FOUND` | 요청한 Member 또는 Event가 존재하지 않음 |
| `FORBIDDEN` | 요청한 Member가 ADMIN이 아님 |
| `INVALID_STATE` | Event가 삭제됐거나 CLOSED 상태가 아님 |
| `CONCURRENT_COMMAND` | 동일 Event의 INITIAL Drawing이 READY 또는 RUNNING임 |
| `SNAPSHOT_NOT_FOUND` | 공식 Snapshot이 없음 |
| `SNAPSHOT_HASH_MISMATCH` | 공식 Snapshot의 Hash 또는 집계값이 일치하지 않음 |

## 관리자 Drawing Retry

### `POST /api/admin/drawings/{drawingId}/retry`

실패한 INITIAL 또는 REDRAW Drawing을 새 Drawing이나 Seed를 만들지 않고 저장된 확정 입력으로 다시 실행한다.

#### 요청

- Path Variable: `drawingId` (`Long`, 양수, 필수)
- 호출자 식별: Access JWT의 `@CurrentMemberId` (`Long`); Request Body `userId`는 받지 않는다.

#### 성공 응답

```json
{
  "code": "SUCCESS",
  "data": {
    "drawingId": 10,
    "eventId": 20,
    "drawType": "INITIAL",
    "status": "COMPLETED",
    "attemptCount": 2,
    "winnerCount": 3
  },
  "message": null
}
```

#### 처리 계약

- 요청자는 존재하는 `ADMIN` Member여야 하고 Drawing은 `FAILED` 상태여야 한다.
- 기존 `drawingId`, `snapshotId`, `seedId`, 후보·상품 알고리즘 버전, `winnerCount`를 재사용한다.
- REDRAW Retry는 저장된 제외 명단과 RedrawRequest가 고정한 결원 상품을 재사용하며 상품을 다시 추첨하지 않는다.
- Drawing 행을 비관적으로 잠근 뒤 상태를 재검증하여 동시 Retry 중 하나만 `RUNNING`으로 전이한다.
- Snapshot 무결성, 보존된 입력 Hash, 후보 수를 엔진 호출 전에 검증한다. 같은 입력으로 해결되지 않는 실패는
  `NON_RETRYABLE_FAILURE`로 차단한다.
- 실행 시작 상태와 Attempt 이력을 먼저 확정하고, Winner·WinnerManagement·Result Hash·Drawing 완료와
  Event 또는 RedrawRequest 후속 상태는 별도 단일 Transaction으로 저장한다.
- 결과 Transaction 실패 시 부분 결과를 Rollback하고 별도 Transaction에서 Drawing `FAILED`와 실패 단계,
  코드, 메시지, 종료 시각을 보존한다. REDRAW는 RedrawRequest `FAILED`와 실행 이력도 함께 보존한다.
- 설정 시간보다 오래 `RUNNING`인 Attempt는 복구기가 `SERVER_INTERRUPTED`로 종결한 뒤 같은 Retry 경로로 실행한다.

| 코드 | 조건 |
| --- | --- |
| `VALIDATION_FAILED` | drawingId가 누락·0 이하이거나 형식이 올바르지 않음 |
| `RESOURCE_NOT_FOUND` | 요청한 Member가 존재하지 않음 |
| `FORBIDDEN` | 요청한 Member가 ADMIN이 아님 |
| `DRAWING_NOT_FOUND` | Drawing이 존재하지 않음 |
| `INVALID_STATE` | Drawing이 FAILED가 아니거나 지원하지 않는 상태임 |
| `CONCURRENT_COMMAND` | 다른 Retry 또는 복구가 같은 Drawing을 RUNNING으로 전이함 |
| `NON_RETRYABLE_FAILURE` | Snapshot·보존 입력 불일치 또는 후보 부족처럼 같은 입력으로 해결할 수 없음 |

## 관리자 Drawing 공개

### `POST /api/admin/drawings/{drawingId}/publish`

완료된 INITIAL 또는 REDRAW Drawing 결과를 공개한다. Controller는 요청 형식만 처리하고 시스템4
`PublicationService`에 공개 유스케이스를 위임한다. 관리자 검증과 공개 상태 전이·동시성 제어는
`PublicationService`가 호출하는 시스템3 `DrawingPublicationService`가 담당한다.

#### 요청

- Path Variable: `drawingId` (`Long`, 양수, 필수)
- 호출자 식별: Access JWT의 `@CurrentMemberId` (`Long`); Request Body `userId`는 받지 않는다.

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
- Drawing은 `COMPLETED` 상태여야 한다.
- `INITIAL`의 `PRIVATE` Drawing은 `PUBLIC`으로 전이하고 연결 Event를 `DRAW_COMPLETED → PUBLISHED`로 전이한다.
- `REDRAW`의 `PRIVATE` Drawing은 Event가 이미 `PUBLISHED`인 경우에만 `PUBLIC`으로 전이한다. 이때
  `EventCommandService.publish()`를 호출하지 않으며 Event는 `PUBLISHED`를 유지한다.
- 최초 공개에서는 해당 Drawing 유형에 맞는 Winner Notification(`INITIAL_WINNER` 또는 `REDRAW_WINNER`)을 생성한다.
  Drawing 공개, 필요한 Event 전이, Notification 생성은 `PublicationService`의 하나의 DB Transaction으로 처리하므로
  어느 단계든 실패하면 모두 Rollback한다.
- 이미 `PUBLIC`인 Drawing은 연결 Event도 `PUBLISHED`인 경우에만 상태 변경 없이 성공한다.
- Drawing과 Event 행을 모두 비관적으로 잠가 동시 요청을 직렬화한다.
- Controller는 JWT에서 얻은 memberId로 시스템4 `PublicationService.publish(drawingId, memberId)`를 한 번만 호출한다.

| 코드 | 조건 |
| --- | --- |
| `VALIDATION_FAILED` | drawingId가 누락·0 이하이거나 형식이 올바르지 않음 |
| `RESOURCE_NOT_FOUND` | 요청한 Member가 존재하지 않음 |
| `FORBIDDEN` | 요청한 Member가 ADMIN이 아님 |
| `DRAWING_NOT_FOUND` | Drawing이 존재하지 않음 |
| `DRAWING_NOT_COMPLETED` | Drawing.status가 COMPLETED가 아님 |
| `INVALID_STATE` | Event 상태가 기대 상태가 아니거나 Drawing·Event 공개 상태가 불일치함 |
