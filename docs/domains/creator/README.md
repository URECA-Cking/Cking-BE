# Creator 도메인

Creator 도메인은 Creator 권한 신청·심사(`creator_application`), 승인된 Creator(`creator`), 승인된 Creator 전용 Creator Space(`creator_space`)를 소유한다.

## 문서

- [Creator 신청 API](api.md): 신청·심사 외부 API 계약
- [Creator Space API](space-api.md): 공개 Creator 목록, Space 공개 조회(creatorId·slug), Creator 본인 조회·수정·커스텀 slug 변경 외부 API 계약
- [Creator Space slug 정책](space-slug-policy.md): 자동·커스텀 slug 형식, 예약어, 중복, 자동 slug 충돌 처리
- [Creator 유사 추천 결과](similarity-recommendation.md): Cking-LLM 후보 묶음 검증, 세대 보존, 원자 교체와 공개 조회 계약

## 공개 활동명(이슈 #520)

`creator.name`은 승인 당시 Member의 OAuth 원본 이름을 보관하는 내부 값이며 공개 응답에 사용하지 않는다. 공개 `creatorName`은
`creator.activity_name`의 활동명이다. 필드명은 유지해 기존 FE 계약을 깨지 않는다.

- 활동명은 Member 닉네임과 같은 완성형 한글·ASCII 영문·숫자·`_`·`-` 1~20자, NFC·소문자 정규화 규칙을 쓰지만
  Creator 안에서만 중복되지 않는 별도 namespace다.
- 최초 Creator 승인 시 Member 닉네임을 후보로 쓴다. 충돌하면 20자 안에서 `-2`, `-3`을 붙여 승인은 실패하지 않는다.
- 기존 Creator는 V59에서 `creator-{creatorId}` 임시 활동명으로 이관되고 `activityNameConfigured=false`가 된다. 이
  상태에서는 공개 활동 주체를 확정할 수 없으므로 댓글 작성·수정, 게시글·Event 생성·수정, Event 승인 요청과 이벤트 응모를
  `ACTIVITY_NAME_SETUP_REQUIRED`로 막는다. 기존 콘텐츠와 내 Event 목록 조회, 기존 게시글·DRAFT·REJECTED Event 삭제는
  데이터 확인·정리를 위해 허용한다. Space 편집에서 최초 활동명을 설정한 뒤 모든 공개 활동을 이용할 수 있다.
- 최초 설정 후 실제 변경은 14일 간격이며, 바로 이전 활동명은 14일 예약돼 즉시 되돌릴 수 있다.
- Space·Event·Calendar·Follow·추천·유사 추천과 Space 소유 Creator 댓글은 조회 시점의 현재 활동명을 사용한다. 콘텐츠에 이름을
  snapshot하지 않으므로 활동명을 바꾸면 기존 콘텐츠에도 새 이름이 표시된다.

## Creator Space 자동 생성(이슈 #270, #754)

관리자가 Creator 신청을 승인하면(`POST /api/admin/creator-applications/{id}/approve`), `CreatorApplicationService.approveLocked()`가 신청 승인 → Creator 생성 → 기본 Mission 초기화에 이어 `CreatorSpaceService.createIfAbsent(creatorId)`를 같은 트랜잭션에서 호출해 Creator Space를 만든다. 관리자가 미리 준비할 것은 없다. 승인 API 자체의 요청·응답 계약은 [api.md](api.md#post-apiadmincreator-applicationsidapprove)를 따른다.

### 기본값

| 값 | 기본값 |
| --- | --- |
| 소개 문구 | `CreatorSpace.DEFAULT_INTRO_TEXT` ("크리에이터와 함께하는 공간이에요") |
| 프로필·배너 이미지 | 없음(`null`). 이미지가 없으면 FE가 기본 이미지를 보여 준다 |
| slug | `creator-{creatorId}` |

- 승인 때 회원이 올려 둔 프로필 사진이 있으면 그 파일을 Space용 파일로 복사해 프로필 사진으로 연결한다. 복사하지 못하면 승인을 막지 않고 이미지 없이 만든다.
- 기본 이미지(업로드 연결을 풀 때 돌아가는 값)도 없음이라, 업로드한 사진·배너를 해제하면 이미지 없음이 된다.
- 이슈 #754 전에는 관리자가 활성화한 Creator Space 템플릿 값을 복사했다. 그때 만든 Space는 복사한 이미지 URL을 그대로 갖는다. 템플릿 테이블과 템플릿 이미지 업로드 기록은 V94에서 삭제했고, 템플릿용으로 올린 저장소 객체(`creator-space-profile-images/template/`)는 더 이상 정리하지 않는다.

### slug 생성

`CreatorSpaceSlugRule`로 `creator-{creatorId}`의 `{creatorId}`를 실제 creatorId로 바꾼다. slug 끝 숫자열이 곧 creatorId(앞자리 0 없는 양수)라서 서로 다른 Creator의 자동 slug는 같을 수 없다. 소문자 ASCII로 쓰는 이유는 `slug` 컬럼 collation(`utf8mb4_0900_ai_ci`)이 대소문자·전각 숫자 등을 같은 값으로 비교하기 때문이다.

### 트랜잭션 경계

Creator Space 생성은 승인 트랜잭션에 참여한다(기본 전파, `REQUIRES_NEW` 아님). 대체 slug까지 모두 쓰이고 있는 등으로 Space 생성이 실패하면 승인 트랜잭션 전체를 롤백한다 — Creator 생성과 신청 승인 상태 전이도 함께 되돌아가 "Creator는 승인됐는데 Space가 없는" 상태를 만들지 않는다. 이 정책은 기본 Mission 초기화 실패가 승인을 함께 롤백하는 기존 규칙([mission/README.md](../mission/README.md#검증-범위caveat))과 동일한 원칙을 따른다.

### 멱등성

`CreatorSpaceService.createIfAbsent(creatorId)`는 먼저 `creator_space.creator_id`로 기존 Space를 조회하고, 있으면 새로 만들지 않고 그대로 반환한다. `creator.member_id` UNIQUE 제약상 같은 Member의 승인은 전체 시스템에서 한 번만 성공하므로(재승인 시도는 `requirePending()`이 `INVALID_STATE`로 막는다) 현재 호출 경로에서 동시 중복 생성 경합은 없지만, `creator_space.creator_id` UNIQUE 제약(`uk_creator_space_creator`)이 최종 안전망이다.

### Space가 없는 기존 Creator 백필

V18 이전에 승인된 Creator는 `V19__backfill_creator_space.sql`이 배포 시 그때의 활성 템플릿 값으로 채웠다. 활성 템플릿이 없어 건너뛰어진 Creator는 Space 없이 남아 있을 수 있다.

- 이런 Creator가 남았으면 [수동 백필 SQL](manual-space-backfill.sql)을 운영자가 한 번 직접 실행한다. 승인 경로와 같은 기본값을 쓰고, 이미 Space가 있는 Creator는 건너뛴다.
- 자동 slug가 이미 쓰이고 있으면 승인 경로처럼 `-2`부터 `-100`까지 비어 있는 대체 slug를 찾는다. 비어 있는 후보가 없는 Creator는 건너뛰므로 실행 뒤 누락 여부를 확인한다.
- V19 파일은 체크섬을 유지해야 하므로 수정하거나 재실행하지 않는다.

## 탭 노출 설정 제거(이슈 #290)

당시 템플릿과 Space에 있던 탭(홈·미션·게시물·이벤트) on/off 값은 V20에서 삭제했다. 탭은 항상 노출하며, 내용이 없으면 화면에서 "현재 열려있는 게 없습니다"를 보여준다. 탭 값을 읽어 기능을 막는 코드는 원래 없었으므로 기능 동작은 바뀌지 않는다.

## 커스텀 slug(이슈 #290)

Creator는 승인 때 받은 자동 slug를 인스타 아이디처럼 원하는 값으로 바꿀 수 있다(`PATCH /api/creator/space/slug`). 공유 링크는 slug로 연다(`GET /api/creator-spaces/{slug}`). 형식·예약어·중복 규칙과 변경 시 예전 링크 처리는 [space-slug-policy.md](space-slug-policy.md)가 정본이다.

- 위 "slug 생성"의 보장(끝 숫자열 = creatorId라 겹치지 않음)은 자동 slug끼리만 성립한다. 커스텀 slug가 아직 승인되지 않은 Creator의 자동 slug를 먼저 가져갔을 수 있으므로, `CreatorSpaceService`는 승인 시 자동 slug가 이미 쓰이고 있거나 다른 Creator가 예약 중이면(이슈 #301) `-2`, `-3`을 붙인 대체 slug를 쓴다. 승인은 slug 충돌로 실패하지 않는다.
- slug는 바뀔 수 있으므로 다른 도메인(공유 미션 등)은 slug가 아니라 `creatorId`로 기록한다.
