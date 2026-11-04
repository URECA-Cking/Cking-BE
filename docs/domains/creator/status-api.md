# Creator 자격 상태 관리 API (#800)

공통 인증·성공/오류 봉투는 [공통 API](../../common/api.md)를 따른다. `creator` 행의 존재는 유지하고 활동 가능 여부를 별도 `status`로 판단한다. 기존 Creator는 마이그레이션 시 모두 `ACTIVE`다.

## 상태와 전이

| 현재 | 허용 명령 | 다음 |
| --- | --- | --- |
| `ACTIVE` | 정지, 박탈 | `SUSPENDED`, `REVOKED` |
| `SUSPENDED` | 정지 해제, 박탈 | `ACTIVE`, `REVOKED` |
| `REVOKED` | 없음 | 최종 상태 |

동일 상태 재전이는 `INVALID_CREATOR_STATUS_TRANSITION`(409)이다. 상태 변경과 감사 이력 INSERT는 한 MySQL 트랜잭션이며, 이력에는 이전·새 상태, 사유 코드, 관리자 전용 상세 사유, Creator에게 보여 줄 공개 안내, 관리자 ID, 요청 UUID, 처리 시각을 보존한다. 상세 사유는 본인·공개 응답에 포함하지 않는다.

`reasonCode`: `POLICY_VIOLATION`, `ABUSE`, `INACTIVITY`, `REVIEW_COMPLETED`, `OTHER`. `detailReason`은 공백이 아닌 1000자 이하, `publicNotice`는 공백이 아닌 500자 이하 문자열이다. 정지 해제 이력에도 공개 안내를 기록하지만 현재 `ACTIVE` 응답의 `publicNotice`는 `null`이다.

## 관리자 조회

모든 관리자 경로는 ADMIN JWT가 필요하고 서버에서도 ADMIN 역할을 다시 확인한다.

- `GET /api/admin/creators?status=ACTIVE&page=0&size=20`: 상태는 선택 사항. 생략하면 전체, 지정하면 DB에서 필터링한다. `creatorId ASC` 정렬. 페이지 크기 1~100.
- `GET /api/admin/creators/{creatorId}`: 단건 자격 상세.
- `GET /api/admin/creators/{creatorId}/status-history?page=0&size=20`: 변경 이력 `id DESC`, 1~100건.

목록 `data`는 `{ "items": [...], "page": 0, "size": 20, "totalElements": 1, "totalPages": 1, "hasNext": false }`이다. 항목에는 `creatorId`, `memberId`, `activityName`, `status`, `canOperate`, `publicNotice`, `createdAt`을 반환한다. 이력 항목에는 `id`, `creatorId`, `previousStatus`, `status`, `reasonCode`, `detailReason`, `publicNotice`, `adminId`, `requestId`, `createdAt`을 반환한다.

## 관리자 변경

- `POST /api/admin/creators/{creatorId}/suspend`: `ACTIVE → SUSPENDED`
- `POST /api/admin/creators/{creatorId}/reactivate`: `SUSPENDED → ACTIVE`
- `POST /api/admin/creators/{creatorId}/revoke`: `ACTIVE/SUSPENDED → REVOKED`

세 명령의 본문은 같다.

```json
{
  "requestId": "550e8400-e29b-41d4-a716-446655440000",
  "reasonCode": "POLICY_VIOLATION",
  "detailReason": "운영자에게만 보이는 사유",
  "publicNotice": "크리에이터 활동이 일시 제한되었습니다."
}
```

성공 `data`는 `creatorId`, `previousStatus`, `status`, `publicNotice`, `requestId`, `changedAt`을 반환한다. 같은 UUID·대상·명령·본문·관리자를 다시 보내면 기존 결과를 반환하며 이력을 추가하지 않는다. 같은 UUID에 다른 값은 `CREATOR_STATUS_REQUEST_CONFLICT`(409). 대상 없음은 `RESOURCE_NOT_FOUND`(404), 관리자 권한 없음은 `FORBIDDEN`(403). `cking.creator.status-management.enabled=false`이면 변경 명령만 `CREATOR_STATUS_MANAGEMENT_DISABLED`(503)로 막는다.

## Creator 본인 조회와 기존 기능

`GET /api/creator/me/status`는 Access JWT가 필요하다. `data`는 `creatorId`, `status`, `canOperate`, `publicNotice`만 반환한다. Creator가 아니면 `FORBIDDEN`(403). `GET /api/me`의 `creator`는 자격 상태와 무관하게 행 존재 여부이므로 정지·박탈 후에도 `true`이며, `creatorStatus`와 `creatorCanOperate`로 활동 가능 여부를 구분한다. 일반 회원의 두 필드는 각각 `null`, `false`다.

정지·박탈 시 기존 Space·게시글·캘린더·Quiz·Event와 로그인·팬 기능은 남긴다. 새 Creator 콘텐츠 저장, Event 승인 요청·관리자 승인, 새 Creator 미션/Quiz 보상 접수와 추천 목록 노출은 막는다. 팬에게 보여 주는 새 미션 목록은 비운다. 이미 접수된 보상 요청의 동일 UUID 재전송과 Stream/Ledger 후속 반영은 기존 멱등 규칙대로 처리한다. 기존 Event의 자동 마감·추첨·알림도 유지한다.

Creator 자격 행의 MySQL 쓰기 잠금은 콘텐츠 최종 저장과 관리자 상태 변경을 직렬화한다. 이미지 업로드·OAuth 외부 호출은 잠금을 길게 잡지 않고 최종 연결·DB 저장에서 다시 검사한다. 새 보상 접수와 자격 변경은 Creator별 MySQL advisory lock으로 직렬화한다. 같은 서버에서 대기하는 보상 요청은 로컬 잠금으로 먼저 줄 세워 커넥션 풀을 점유하지 않는다. ONCE 보상은 별도 트랜잭션으로 요청 행을 저장하므로 보상 처리 중 Creator 행 쓰기 잠금을 유지하지 않는다. MySQL과 Redis의 단일 원자 커밋은 제공하지 않으며, 장애 시 기존 requestId 멱등 처리와 Stream 복구 규칙을 따른다.
