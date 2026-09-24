# Creator 도메인

Creator 도메인은 Creator 권한 신청·심사(`creator_application`), 승인된 Creator(`creator`), 관리자 기본 Creator Space 템플릿(`creator_space_template`), 승인된 Creator 전용 Creator Space(`creator_space`)를 소유한다.

## 문서

- [Creator 신청 API](api.md): 신청·심사 외부 API 계약
- [Creator Space Template 관리자 API](space-template-api.md): 기본 템플릿 CRUD·활성화 외부 API 계약

## Creator Space 자동 생성(이슈 #270)

관리자가 Creator 신청을 승인하면(`POST /api/admin/creator-applications/{id}/approve`), `CreatorApplicationService.approveLocked()`가 신청 승인 → Creator 생성 → 기본 Mission 초기화에 이어 `CreatorSpaceService.createFromActiveTemplateIfAbsent(creatorId)`를 같은 트랜잭션에서 호출해 Creator Space를 만든다. 이 승인 API 자체의 요청·응답 계약은 바뀌지 않았고([api.md](api.md#post-apiadmincreator-applicationsidapprove) 참고), 승인 성공 시 내부적으로 수행하는 일만 늘었다.

### 템플릿 값 복사, 참조 아님

`CreatorSpace.fromTemplate(creatorId, template, slug)`는 활성 `CreatorSpaceTemplate`의 필드 값을 그대로 복사해 새 `CreatorSpace`를 만든다. 두 Entity는 서로 참조하지 않으므로, 생성 이후 템플릿이 수정·비활성화·재활성화돼도 이미 만든 Space는 영향받지 않는다([space-template-api.md](space-template-api.md#범위와-권한)의 계약과 동일).

### slug 생성과 slugRule 검증(2단 방어)

템플릿의 `slugRule`(예: `creator-{creatorId}`)에서 `{creatorId}` 자리표시자를 실제 `creatorId`로 치환해 Space의 `slug`를 만든다. `{creatorId}`가 없거나 두 번 이상 있으면 모든(또는 일부) Creator가 같은 slug를 가지려 해 `creator_space.slug` UNIQUE 제약(`uk_creator_space_slug`)을 위반하고, 치환 결과가 `creator_space.slug` 컬럼(VARCHAR(100))보다 길면 컬럼 길이 초과로 실패한다. 이 두 가지를 두 지점에서 막는다.

1. **입력 시점**([space-template-api.md](space-template-api.md#템플릿-필드)): `CreatorSpaceTemplateRequest`의 `slugRule`은 `{creatorId}`를 정확히 한 번 포함해야 하고 최대 92자다(`{creatorId}`는 11자, creatorId는 `Long`이라 최대 19자리 숫자로 치환될 수 있어 최대 8자가 늘어남 — `92 + 8 = 100`). 새로 생성·수정하는 템플릿만 검증한다.
2. **사용 시점**(`CreatorSpaceService`): 이 Bean Validation이 생기기 전에 저장돼 활성 상태로 남아 있을 수 있는 템플릿을 대비해, 활성 템플릿을 읽어 slug를 만들기 직전에 같은 규칙(자리표시자 정확히 1회, 치환 결과 100자 이내)을 다시 검증한다. 위반하면 `CreatorErrorCode.INVALID_ACTIVE_SPACE_TEMPLATE`(409)을 던져 DB 제약·컬럼 길이 오류 대신 명확한 원인으로 승인을 실패시킨다.

### 트랜잭션 경계와 활성 템플릿 없음·무효 정책

Creator Space 생성은 승인 트랜잭션에 참여한다(기본 전파, `REQUIRES_NEW` 아님). 활성 템플릿이 없으면 `CreatorErrorCode.NO_ACTIVE_SPACE_TEMPLATE`(409), 활성 템플릿은 있지만 `slugRule`이 위(2단 방어) 규칙을 위반하면 `CreatorErrorCode.INVALID_ACTIVE_SPACE_TEMPLATE`(409)을 던지고, 두 예외 모두 승인 트랜잭션 전체를 롤백한다 — Creator 생성과 신청 승인 상태 전이도 함께 되돌아가 "Creator는 승인됐는데 Space가 없는" 상태를 만들지 않는다. 관리자는 기본 템플릿을 먼저 활성화(하고 필요하면 `slugRule`을 고쳐)해야 Creator 승인을 진행할 수 있다. 이 정책은 기본 Mission 초기화 실패가 승인을 함께 롤백하는 기존 규칙([mission/README.md](../mission/README.md#검증-범위caveat))과 동일한 원칙을 따른다.

### 멱등성

`CreatorSpaceService.createFromActiveTemplateIfAbsent(creatorId)`는 먼저 `creator_space.creator_id`로 기존 Space를 조회하고, 있으면 새로 만들지 않고 그대로 반환한다. `creator.member_id` UNIQUE 제약상 같은 Member의 승인은 전체 시스템에서 한 번만 성공하므로(재승인 시도는 `requirePending()`이 `INVALID_STATE`로 막는다) 현재 호출 경로에서 동시 중복 생성 경합은 없지만, `creator_space.creator_id` UNIQUE 제약(`uk_creator_space_creator`)이 최종 안전망이다.
