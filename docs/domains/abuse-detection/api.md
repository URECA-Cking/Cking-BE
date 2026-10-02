# 비정상 행동 탐지 관리자 API

모든 API는 [공통 API 규약](../../common/api.md)의 응답 봉투를 사용한다. Bearer Access JWT와 `ADMIN` 역할이 필수이고, Controller는 `@CurrentMemberId Long memberId`를 Application의 관리자 업무 식별자로 전달한다. Spring Security의 `/api/admin/**` 인가 뒤에도 Application이 관리자 권한을 검증한다.

## Detection 상태와 공통 응답 필드

상태는 `DETECTED`, `CONFIRMED`, `FALSE_POSITIVE`다. 응답 시각은 UTC RFC 3339다. 목록 item과 상세는 다음 필드를 가진다. 목록의 `evidence`는 전체를 반환하지 않고 `evidenceSummary`만 반환하며 상세만 전체 Evidence를 반환한다.

```json
{
  "detectionId": 1,
  "memberId": 7,
  "abuseType": "DUPLICATE_MISSION_BURST",
  "status": "DETECTED",
  "detectedAt": "2026-10-01T00:00:00Z",
  "reviewedAt": null,
  "reviewedBy": null,
  "evidenceSummary": {"matchedRules":["RULE-01"],"signals":["REQUEST_ID_ROTATION"]}
}
```

`evidenceSummary`는 `matchedRules`, `signals`, `scope.type`과 scope 식별자(eventId/creatorId/missionId/periodKey/balanceScope 중 존재 값)만 담는다. `balanceScope`는 `{ "type":"COMMON", "creatorId":null }` 또는 `{ "type":"CREATOR", "creatorId":10 }` 형태다. request body, access token, authorization header, 개인정보는 어떤 응답에도 포함하지 않는다.

## GET /api/admin/abuse-detections

Query는 선택 `memberId`(양수 Long), `abuseType`, `status`, `page`, `size`다. `page` 기본 0, `size` 기본 20·최대 100이다. `abuseType`은 `MISSION_REQUEST_BURST`, `DUPLICATE_MISSION_BURST`, `ENTRY_REQUEST_BURST`, `INSUFFICIENT_BALANCE_BURST`, `RAPID_EARN_AND_SPEND`, `FAILURE_BURST` 중 하나다. `REQUEST_ID_ROTATION`은 Signal이므로 filter 값이 될 수 없다.

`detectedAt DESC, detectionId DESC`로 정렬하고 공통 Page 형식(`items`, `page`, `size`, `totalElements`, `totalPages`, `hasNext`)을 반환한다. 범위를 벗어난 page/size, 알 수 없는 enum, 0 이하 memberId는 `VALIDATION_FAILED`다.

## GET /api/admin/abuse-detections/{detectionId}

양수 `detectionId`의 Detection 상세를 반환한다. 목록 item 필드에 아래 `evidence` 전체를 추가한다.

```json
{
  "policyVersion":"ABUSE_V1",
  "scope":{"type":"USER_EVENT","eventId":20,"balanceScope":{"type":"COMMON","creatorId":null}},
  "window":{"windowMs":10000},
  "features":{"entryRequestCount":14},
  "thresholds":{"entryRequestCount":8},
  "signals":[],
  "matchedRules":[]
}
```

없으면 `RESOURCE_NOT_FOUND`다.

Composite Rule을 충족한 상세 Evidence에는 `matchedRules` 외에 선택 필드 `supportingEvidence`가 포함된다. Rule 이름별 배열의 각 원소는 보조 `abuseType` 또는 `signal` 하나, 독립적인 `scope`, `window`, `features`, `thresholds`를 담는다. 예를 들어 `RULE-02`의 Rotation 근거는 Mission burst의 USER scope/window에 합치지 않고 Rotation의 BUSINESS_KEY scope/window로 반환한다. 기존 Detection처럼 보조 근거가 없는 JSON에는 이 필드가 없을 수 있다. 목록의 `evidenceSummary`에는 이 배열을 포함하지 않는다.

## PATCH /api/admin/abuse-detections/{detectionId}/review

```json
{ "status": "CONFIRMED" }
```

요청 status는 `AbuseReviewDecision`의 `CONFIRMED` 또는 `FALSE_POSITIVE`만 허용한다. `DETECTED`를 요청하거나 값이 없거나 다른 값이면 `VALIDATION_FAILED`다. Application과 Repository Port도 이 별도 타입을 사용하므로 `DETECTED`를 검토 목표로 전달할 수 없다. 성공하면 변경된 상세를 반환하고 `reviewedAt`은 현재 UTC, `reviewedBy`는 인증된 ADMIN memberId로 기록한다.

검토 전이는 행을 먼저 읽고 저장하지 않는다. 아래 조건부 UPDATE로 `DETECTED` 상태만 하나의 Transaction에서 전이한다.

```sql
UPDATE abuse_detection
SET status = :targetStatus, reviewed_at = :now, reviewed_by = :adminId
WHERE id = :detectionId AND status = 'DETECTED'
```

영향 행이 1이면 변경된 상세를 반환한다. 0이면 현재 row를 다시 읽어 없는 경우 `RESOURCE_NOT_FOUND`, 이미 같은 결과인 경우 상태·검토자를 바꾸지 않는 멱등 반환, 반대 결과인 경우 `INVALID_STATE`로 처리한다. 따라서 동시에 서로 다른 판정을 요청하면 하나만 성공하고, 같은 판정의 동시 재요청은 하나가 전이한 뒤 나머지가 같은 결과를 반환한다. 관리자 업무 권한 부족은 `FORBIDDEN`이다.
