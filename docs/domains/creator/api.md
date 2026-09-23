# Creator 신청 API

Creator 신청·심사 API의 상세 계약이다. 모든 성공·실패 응답은 [공통 API 규약](../../common/api.md)의 응답 봉투를 사용하며, 아래 Response 예시는 `data` 값이다.

## 상태와 권한

- 신청 상태는 `PENDING`, `APPROVED`, `REJECTED`다.
- Creator 여부는 `Member.role`이 아니라 승인된 `creator` 레코드 존재 여부로 판단한다.
- 신청자 조회는 본인만, 관리자 목록·승인·거절은 `member.role == ADMIN`만 가능하다.
- 승인·거절은 PENDING 신청만 처리한다. 심사 시 `reviewedBy`, `reviewedAt`을 기록한다.
- 응답 시각은 UTC RFC 3339 형식(예: `2026-09-16T00:30:00Z`)이다.

## POST /api/creator/applications

Bearer Access JWT가 필수이며 호출자는 `@CurrentMemberId`로 식별한다. 요청 본문은 없다.

| 상황 | HTTP | `data` |
| --- | --- | --- |
| 신규 신청 | 201 | `{ "applicationId": 1, "status": "PENDING" }` |
| 기존 PENDING 재요청 | 200 | 기존 신청과 같은 형식 |

- 이미 Creator인 Member는 `INVALID_STATE`다.
- 기존 PENDING 신청은 새로 만들지 않고 기존 결과를 반환한다.
- REJECTED 신청 뒤에는 새 신청을 만들 수 있다.
- 같은 사용자의 동시 신청 충돌은 `CONCURRENT_COMMAND`다.

## GET /api/creator/applications/me

Bearer Access JWT가 필수이며 호출자는 `@CurrentMemberId`로 식별한다. Query: `page`, `size`

본인의 신청 이력만 반환하며 기본 정렬은 `requestedAt DESC, applicationId DESC`다.

```json
{
  "items": [{
    "applicationId": 1,
    "status": "PENDING",
    "requestedAt": "2026-09-16T00:30:00Z",
    "reviewedAt": null,
    "rejectReason": null
  }],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1,
  "hasNext": false
}
```

심사 전 `reviewedAt`은 null이며, `rejectReason`은 REJECTED가 아닐 때 null일 수 있다.

## GET /api/admin/creator-applications

Query: `userId`, `page`, `size`. `userId`는 관리자 식별자다. 기본 정렬은 `requestedAt ASC, applicationId ASC`다.

```json
{
  "items": [{
    "applicationId": 1,
    "applicantUserId": 10,
    "applicantName": "홍길동",
    "status": "PENDING",
    "requestedAt": "2026-09-16T00:30:00Z",
    "reviewedBy": null,
    "reviewedAt": null,
    "rejectReason": null
  }],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1,
  "hasNext": false
}
```

## POST /api/admin/creator-applications/{id}/approve

```json
{ "userId": 1 }
```

관리자만 PENDING 신청을 승인할 수 있다. 승인, Creator 생성, 기본 미션(ATTENDANCE·LIKE) 초기화는 하나의 DB transaction으로 처리한다. 미션 초기화가 실패하면 승인과 Creator 생성도 함께 롤백되며, Member당 Creator는 하나만 존재해야 한다.

```json
{ "applicationId": 1, "status": "APPROVED" }
```

성공은 200이다. 대상 또는 요청 관리자가 없으면 `RESOURCE_NOT_FOUND`, 관리자가 아니면 `FORBIDDEN`, PENDING이 아니면 `INVALID_STATE`, 승인·거절 경합은 `CONCURRENT_COMMAND`다.

## POST /api/admin/creator-applications/{id}/reject

```json
{ "userId": 1, "rejectReason": "거절 사유" }
```

- `rejectReason`은 필수이며 null, blank, trim 후 빈 문자열을 허용하지 않는다.
- 심사자와 심사 시각, 거절 사유를 기록한다.
- 성공은 200이며 응답은 `{ "applicationId": 1, "status": "REJECTED" }`다.
- 권한·상태·경합 오류는 승인 API와 같다. 유효하지 않은 거절 사유는 `VALIDATION_FAILED`다.

## 오류와 DB 제약

- 없는 Member 또는 신청은 `RESOURCE_NOT_FOUND`다.
- 신청별 상태 변경은 동시 명령으로 충돌할 수 있으며 `CONCURRENT_COMMAND`를 사용한다.
- 신청은 `memberId`, 심사는 신청 ID를 키로 한 MySQL advisory lock을 트랜잭션 완료까지 보유한다. 대기하지 못한 동시 명령은 `CONCURRENT_COMMAND`다. 이 정책은 별도 DB 스키마 변경을 요구하지 않는다.
- `creator.member_id`의 UNIQUE 제약이 Member당 Creator 하나를 최종 보장한다.
- `creator_application.reject_reason`은 최대 500자 저장 컬럼이다.
