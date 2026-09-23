# Winner API

## 공개 Winner 조회

### `GET /api/events/{eventId}/winners`

- 권한: `PUBLIC`
- Path Variable: `eventId` (`Long`, 양수, 필수)
- Event 존재를 먼저 확인한 뒤 해당 Event의 `PUBLIC`·`COMPLETED` Drawing에 속한 Winner만
  `drawNo ASC`, `rankInDrawing ASC` 순으로 반환한다. 따라서 공개된 INITIAL과 공개된 REDRAW
  결과를 함께 조회하고, PRIVATE Drawing의 결과는 포함하지 않는다.
- 각 Winner는 `drawNo`와 `drawType`으로 INITIAL·REDRAW 이력을 구분한다. 운영 상태는 공개하지 않는다.
- 각 Winner에 Snapshot에서 확정되어 저장된 `prizeKey`, `prizeDisplayName`, `prizePriority`를 함께 반환한다.
  상품 추첨 도입 전 생성된 레거시 Winner는 해당 필드가 `null`일 수 있다.
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
        "rankInDrawing": 1,
        "prizeKey": "FIRST",
        "prizeDisplayName": "1등 상품",
        "prizePriority": 1
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
- Bearer Access JWT가 필수이며 호출자는 `@CurrentMemberId`로 식별한다.
- 먼저 Member 존재를 검증한 뒤, `memberId = authenticated memberId`인 Winner만 반환한다. 따라서 다른 사용자의
  Winner는 조회 결과에 포함되지 않는다.
- `PUBLIC`·`COMPLETED` Drawing에 속한 Winner의 불변 데이터와 1:1 WinnerManagement의 현재 상태를
  함께 반환한다. PRIVATE 또는 미완료 Drawing 결과는 당첨자 본인에게도 노출하지 않는다. 공개된
  INITIAL·REDRAW Winner를 `drawNo ASC`, `rankInDrawing ASC`, `eventId ASC` 순으로 반환한다.
  서로 다른 Event에서 회차와 순위가 같아도 `eventId`로 순서를 고정한다.
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
| `RESOURCE_NOT_FOUND` | 인증된 memberId에 해당하는 Member가 존재하지 않음 |

## 당첨 포기

### `POST /api/me/winners/{winnerId}/decline`

- 권한: `USER`
- Path Variable: `winnerId` (`Long`, 양수, 필수)
- Bearer Access JWT가 필수이며 호출자는 `@CurrentMemberId`로 식별한다. 요청 본문은 없다.
- Member 존재를 검증한 뒤, Winner 존재와 본인 소유 여부를 검증한다. Winner 원본은 변경하지 않는다.
- WinnerManagement를 쓰기 잠금으로 조회해 동일 Winner의 동시 상태 변경을 직렬화한다. `SELECTED`일 때만
  `DECLINED`로 전이할 수 있으며 `DECLINED`는 종결 상태다. 이미 종결된 Winner의 재변경은 허용하지 않는다.
- 상태 전이와 `WinnerStatusHistory`의 상태·변경 주체·변경 시각 저장은 하나의 Transaction으로 처리한다.
- 별도 멱등 키는 사용하지 않는다. 같은 Winner의 재요청은 현재 상태가 `SELECTED`가 아니므로 실패한다.

성공 시 `200 OK`와 공통 성공 응답을 반환한다.

```json
{
  "code": "SUCCESS",
  "data": null,
  "message": null
}
```

| 코드 | 조건 |
| --- | --- |
| `VALIDATION_FAILED` | winnerId가 누락·0 이하이거나 형식이 올바르지 않음 |
| `RESOURCE_NOT_FOUND` | 인증된 memberId에 해당하는 Member가 존재하지 않음 |
| `WINNER_NOT_FOUND` | winnerId에 해당하는 Winner가 존재하지 않음 |
| `WINNER_MANAGEMENT_NOT_FOUND` | Winner에 연결된 WinnerManagement가 존재하지 않음 |
| `FORBIDDEN` | 요청 Member가 Winner의 소유자가 아님 |
| `INVALID_STATE` | WinnerManagement 상태가 SELECTED가 아니어서 포기할 수 없음 |

## 관리자 수령 완료

### `POST /api/admin/winners/{winnerId}/receive`

- 권한: `ADMIN`
- Path Variable: `winnerId` (`Long`, 양수, 필수)
- Request Body의 `userId`는 인증 미도입 단계의 호출자 식별자이며 `Long` 양수여야 한다.
- 먼저 Member 존재와 `ADMIN` 역할을 검증한 뒤 Winner 존재 여부를 검증한다. Winner 원본은 변경하지 않는다.
- WinnerManagement를 쓰기 잠금으로 조회해 동일 Winner의 동시 상태 변경을 직렬화한다. `SELECTED`일 때만
  `RECEIVED`로 전이할 수 있으며 `RECEIVED`는 종결 상태다. 이미 종결된 Winner의 재변경은 허용하지 않는다.
- 상태 전이와 `WinnerStatusHistory`의 상태·변경 주체·변경 시각 저장은 하나의 Transaction으로 처리한다.
- 별도 멱등 키는 사용하지 않는다. 같은 Winner의 재요청은 현재 상태가 `SELECTED`가 아니므로 실패한다.

```json
{ "userId": 1 }
```

성공 시 `200 OK`와 공통 성공 응답을 반환한다.

```json
{
  "code": "SUCCESS",
  "data": null,
  "message": null
}
```

| 코드 | 조건 |
| --- | --- |
| `VALIDATION_FAILED` | winnerId 또는 userId가 누락·0 이하이거나 형식이 올바르지 않음 |
| `RESOURCE_NOT_FOUND` | userId에 해당하는 Member가 존재하지 않음 |
| `FORBIDDEN` | 요청 Member의 역할이 ADMIN이 아님 |
| `WINNER_NOT_FOUND` | winnerId에 해당하는 Winner가 존재하지 않음 |
| `WINNER_MANAGEMENT_NOT_FOUND` | Winner에 연결된 WinnerManagement가 존재하지 않음 |
| `INVALID_STATE` | WinnerManagement 상태가 SELECTED가 아니어서 수령 완료할 수 없음 |

## 관리자 자격 박탈

### `POST /api/admin/winners/{winnerId}/disqualify`

- 권한: `ADMIN`
- Path Variable: `winnerId` (`Long`, 양수, 필수)
- Request Body의 `userId`는 인증 미도입 단계의 호출자 식별자이며 `Long` 양수여야 한다.
- `reason`은 공백을 제외한 1~500자의 필수 자격 박탈 사유다. 저장 시 앞뒤 공백을 제거한다.
- 먼저 Member 존재와 `ADMIN` 역할을 검증한 뒤 Winner 존재 여부를 검증한다. Winner 원본은 변경하지 않는다.
- WinnerManagement를 쓰기 잠금으로 조회해 동일 Winner의 동시 상태 변경을 직렬화한다. `SELECTED`일 때만
  `DISQUALIFIED`로 전이할 수 있으며 `DISQUALIFIED`는 종결 상태다. 이미 종결된 Winner의 재변경은 허용하지 않는다.
- 상태 전이와 `WinnerStatusHistory`의 상태·변경 주체·변경 사유·변경 시각 저장은 하나의 Transaction으로 처리한다.
  `DISQUALIFIED`는 이후 Redraw의 결원 계산 대상이며, 이 API가 Redraw를 자동 생성하지는 않는다.
- 별도 멱등 키는 사용하지 않는다. 같은 Winner의 재요청은 현재 상태가 `SELECTED`가 아니므로 실패한다.

```json
{ "userId": 1, "reason": "이벤트 참여 조건을 충족하지 않았습니다." }
```

성공 시 `200 OK`와 공통 성공 응답을 반환한다.

```json
{
  "code": "SUCCESS",
  "data": null,
  "message": null
}
```

| 코드 | 조건 |
| --- | --- |
| `VALIDATION_FAILED` | winnerId 또는 userId가 누락·0 이하이거나 reason이 공백이거나 500자를 초과함 |
| `RESOURCE_NOT_FOUND` | userId에 해당하는 Member가 존재하지 않음 |
| `FORBIDDEN` | 요청 Member의 역할이 ADMIN이 아님 |
| `WINNER_NOT_FOUND` | winnerId에 해당하는 Winner가 존재하지 않음 |
| `WINNER_MANAGEMENT_NOT_FOUND` | Winner에 연결된 WinnerManagement가 존재하지 않음 |
| `INVALID_STATE` | WinnerManagement 상태가 SELECTED가 아니어서 자격 박탈할 수 없음 |

## Winner 상태 이력 조회

### `GET /api/winners/{winnerId}/history`

- 권한: `USER` 또는 `ADMIN`
- Path Variable: `winnerId` (`Long`, 양수, 필수)
- Query Parameter: `userId` (`Long`, 양수, 필수). 인증 미도입 단계의 호출자 식별자다.
- Member 존재와 역할을 검증한 뒤 Winner 존재 여부를 확인한다. `USER`는 본인 소유 Winner만 조회할 수 있고,
  `ADMIN`은 모든 Winner를 조회할 수 있다. 다른 사용자의 Winner 조회는 `FORBIDDEN`이다.
- WinnerManagement에 연결된 변경 이력을 `changedAt ASC`, `historyId ASC`으로 반환한다. 이력이 없으면 빈 배열을
  정상 반환한다.

```json
{
  "code": "SUCCESS",
  "data": [
    {
      "historyId": 900,
      "beforeStatus": "SELECTED",
      "afterStatus": "DISQUALIFIED",
      "reason": "이벤트 참여 조건을 충족하지 않았습니다.",
      "changedBy": 1,
      "changedAt": "2026-09-21T01:00:00Z"
    }
  ],
  "message": null
}
```

| 코드 | 조건 |
| --- | --- |
| `VALIDATION_FAILED` | winnerId 또는 userId가 누락·0 이하이거나 형식이 올바르지 않음 |
| `RESOURCE_NOT_FOUND` | userId에 해당하는 Member가 존재하지 않음 |
| `WINNER_NOT_FOUND` | winnerId에 해당하는 Winner가 존재하지 않음 |
| `WINNER_MANAGEMENT_NOT_FOUND` | Winner에 연결된 WinnerManagement가 존재하지 않음 |
| `FORBIDDEN` | USER 요청 Member가 Winner의 소유자가 아님 |
