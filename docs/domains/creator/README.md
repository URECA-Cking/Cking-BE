# Creator 도메인

Creator 도메인은 Creator 권한 신청·심사(`creator_application`), 승인된 Creator(`creator`), 관리자 기본 Creator Space 템플릿(`creator_space_template`), 승인된 Creator 전용 Creator Space(`creator_space`)를 소유한다.

## 문서

- [Creator 신청 API](api.md): 신청·심사 외부 API 계약
- [Creator Space Template 관리자 API](space-template-api.md): 기본 템플릿 CRUD·활성화 외부 API 계약
- [Creator Space API](space-api.md): 공개 Creator 목록, Space 공개 조회(creatorId·slug), Creator 본인 조회·수정·커스텀 slug 변경 외부 API 계약
- [Creator Space slug 정책](space-slug-policy.md): 자동·커스텀 slug 형식, 예약어, 중복, 자동 slug 충돌 처리
- [Creator 유사 추천 결과](similarity-recommendation.md): Cking-LLM 후보 묶음 검증, 세대 보존, 원자 교체와 공개 조회 계약

## Creator Space 자동 생성(이슈 #270)

관리자가 Creator 신청을 승인하면(`POST /api/admin/creator-applications/{id}/approve`), `CreatorApplicationService.approveLocked()`가 신청 승인 → Creator 생성 → 기본 Mission 초기화에 이어 `CreatorSpaceService.createFromActiveTemplateIfAbsent(creatorId)`를 같은 트랜잭션에서 호출해 Creator Space를 만든다. 이 승인 API 자체의 요청·응답 계약은 바뀌지 않았고([api.md](api.md#post-apiadmincreator-applicationsidapprove) 참고), 승인 성공 시 내부적으로 수행하는 일만 늘었다.

### 템플릿 값 복사, 참조 아님

`CreatorSpace.fromTemplate(creatorId, template, slug)`는 활성 `CreatorSpaceTemplate`의 필드 값을 그대로 복사해 새 `CreatorSpace`를 만든다. 두 Entity는 서로 참조하지 않으므로, 생성 이후 템플릿이 수정·비활성화·재활성화돼도 이미 만든 Space는 영향받지 않는다([space-template-api.md](space-template-api.md#범위와-권한)의 계약과 동일).

### slug 생성과 slugRule 검증(2단 방어)

템플릿의 `slugRule`(예: `creator-{creatorId}`)에서 `{creatorId}` 자리표시자를 실제 `creatorId`로 치환해 Space의 `slug`를 만든다. `{creatorId}`가 없거나 두 번 이상 있으면 모든(또는 일부) Creator가 같은 slug를 가지려 해 `creator_space.slug` UNIQUE 제약(`uk_creator_space_slug`)을 위반하고, 치환 결과가 `creator_space.slug` 컬럼(VARCHAR(100))보다 길면 컬럼 길이 초과로 실패한다.

자리표시자가 한 번뿐이어도 템플릿을 바꾸면 충돌할 수 있다. `creator-{creatorId}0`으로 id=1의 slug `creator-10`을 만든 뒤 규칙을 `creator-{creatorId}`로 바꾸면 id=10도 `creator-10`이 된다. 그래서 `slugRule`은 `CreatorSpaceSlugRule` 형식만 허용한다: 소문자·숫자·하이픈(`[a-z0-9-]`) 접두사 뒤 **맨 끝에** `{creatorId}`가 한 번 오고, 접두사가 있으면 마지막 글자는 숫자가 아니다. 이러면 slug 끝 숫자열이 곧 creatorId(앞자리 0 없는 양수)라서, 규칙이 몇 번 바뀌어도 서로 다른 Creator의 slug는 같을 수 없다. 소문자 ASCII로 제한하는 이유는 `slug` 컬럼 collation(`utf8mb4_0900_ai_ci`)이 대소문자·전각 숫자 등을 같은 값으로 비교하기 때문이다. 이 규칙과 길이를 두 지점에서 검사한다.

1. **입력 시점**([space-template-api.md](space-template-api.md#템플릿-필드)): `CreatorSpaceTemplateRequest`의 `slugRule`은 위 형식이어야 하고 최대 92자다(`{creatorId}`는 11자, creatorId는 `Long`이라 최대 19자리 숫자로 치환될 수 있어 최대 8자가 늘어남 — `92 + 8 = 100`). 새로 생성·수정하는 템플릿만 검증한다.
2. **사용 시점**(`CreatorSpaceService`): 이 Bean Validation이 생기거나 강화되기 전에 저장돼 활성 상태로 남아 있을 수 있는 템플릿을 대비해, 활성 템플릿을 읽어 slug를 만들기 직전에 같은 규칙(`CreatorSpaceSlugRule` 형식, 치환 결과 100자 이내)을 다시 검증한다. 위반하면 `CreatorErrorCode.INVALID_ACTIVE_SPACE_TEMPLATE`(409)을 던져 DB 제약·컬럼 길이 오류 대신 명확한 원인으로 승인을 실패시킨다.

### 트랜잭션 경계와 활성 템플릿 없음·무효 정책

Creator Space 생성은 승인 트랜잭션에 참여한다(기본 전파, `REQUIRES_NEW` 아님). 활성 템플릿이 없으면 `CreatorErrorCode.NO_ACTIVE_SPACE_TEMPLATE`(409), 활성 템플릿은 있지만 `slugRule`이 위(2단 방어) 규칙을 위반하면 `CreatorErrorCode.INVALID_ACTIVE_SPACE_TEMPLATE`(409)을 던지고, 두 예외 모두 승인 트랜잭션 전체를 롤백한다 — Creator 생성과 신청 승인 상태 전이도 함께 되돌아가 "Creator는 승인됐는데 Space가 없는" 상태를 만들지 않는다. 관리자는 기본 템플릿을 먼저 활성화(하고 필요하면 `slugRule`을 고쳐)해야 Creator 승인을 진행할 수 있다. 이 정책은 기본 Mission 초기화 실패가 승인을 함께 롤백하는 기존 규칙([mission/README.md](../mission/README.md#검증-범위caveat))과 동일한 원칙을 따른다.

### 멱등성

`CreatorSpaceService.createFromActiveTemplateIfAbsent(creatorId)`는 먼저 `creator_space.creator_id`로 기존 Space를 조회하고, 있으면 새로 만들지 않고 그대로 반환한다. `creator.member_id` UNIQUE 제약상 같은 Member의 승인은 전체 시스템에서 한 번만 성공하므로(재승인 시도는 `requirePending()`이 `INVALID_STATE`로 막는다) 현재 호출 경로에서 동시 중복 생성 경합은 없지만, `creator_space.creator_id` UNIQUE 제약(`uk_creator_space_creator`)이 최종 안전망이다.

### 기존 승인 Creator 백필(V19)

V18 이전에 승인된 Creator에게는 Space가 없으므로, `V19__backfill_creator_space.sql`이 배포 시 한 번 활성 템플릿 값으로 Space를 채운다. Space가 없는 Creator만 대상으로 해 다시 실행해도 결과가 같다. V20 이후 수동 백필은 현재 승인 경로처럼 slug 선점 시 대체 slug도 찾는다.

- 활성 템플릿이 없거나, 활성 템플릿의 `slugRule`이 위 형식이 아니거나, 치환 결과가 100자를 넘으면 배포를 막지 않고 아무것도 넣지 않는다. 이때 기존 Creator는 Space 없이 남는다. 템플릿을 활성화하거나 고친 뒤 아래 SQL을 운영자가 한 번 직접 실행해 채운다. 이미 Space가 있는 Creator는 건너뛴다.
- V19 배포 마이그레이션은 단일 INSERT라 조건을 만족하는 Creator 전체에 한 번에 들어가거나, 하나도 들어가지 않는다. V20 이후 수동 백필은 비어 있는 slug 후보가 없는 Creator를 건너뛸 수 있으므로 실행 뒤 누락 여부를 확인한다.
- V20에서 탭 컬럼을 삭제했으므로 V19 파일의 SQL은 현재 스키마에서 그대로 실행할 수 없다. 수동 백필에는 [현재 스키마용 SQL](manual-space-backfill.sql)을 쓴다. 이미 사용 중인 자동 slug는 승인 경로처럼 `-2`부터 `-100`까지 비어 있는 대체 slug를 찾는다. 실행 후 Space가 없는 Creator가 남았는지 확인하고, 남았다면 활성 템플릿과 slug 후보를 점검한다.

## 탭 노출 설정 제거(이슈 #290)

템플릿과 Space에 있던 탭(홈·미션·게시물·이벤트) on/off 값은 V20에서 삭제했다. 탭은 항상 노출하며, 내용이 없으면 화면에서 "현재 열려있는 게 없습니다"를 보여준다. 탭 값을 읽어 기능을 막는 코드는 원래 없었으므로 기능 동작은 바뀌지 않는다.

## 커스텀 slug(이슈 #290)

Creator는 승인 때 받은 자동 slug를 인스타 아이디처럼 원하는 값으로 바꿀 수 있다(`PATCH /api/creator/space/slug`). 공유 링크는 slug로 연다(`GET /api/creator-spaces/{slug}`). 형식·예약어·중복 규칙과 변경 시 예전 링크 처리는 [space-slug-policy.md](space-slug-policy.md)가 정본이다.

- 위 "slug 생성과 slugRule 검증"의 보장(끝 숫자열 = creatorId라 겹치지 않음)은 자동 slug끼리만 성립한다. 커스텀 slug가 아직 승인되지 않은 Creator의 자동 slug를 먼저 가져갔을 수 있으므로, `CreatorSpaceService`는 승인 시 자동 slug가 이미 쓰이고 있거나 다른 Creator가 예약 중이면(이슈 #301) `-2`, `-3`을 붙인 대체 slug를 쓴다. 승인은 slug 충돌로 실패하지 않는다.
- slug는 바뀔 수 있으므로 다른 도메인(공유 미션 등)은 slug가 아니라 `creatorId`로 기록한다.
