# Creator Space slug 정책

Creator Space slug의 생성·변경 규칙이다(이슈 #270, #290). 코드의 정본은 `CreatorSpaceSlugRule`(자동 slug)과 `CreatorSpaceCustomSlug`(커스텀 slug)이며, 이 문서와 다르면 코드와 함께 이 문서를 고친다. API 계약은 [space-api.md](space-api.md)를 따른다.

## 개요

| 구분 | 언제 | 누가 | 예 |
| --- | --- | --- | --- |
| 자동 slug | Creator 승인 시 | 시스템(활성 템플릿의 `slugRule`) | `creator-42` |
| 커스텀 slug | 승인 이후 언제든 | Creator 본인 | `iu-official` |

- 공유 링크는 slug 기반(`/space/{slug}`)이며 `GET /api/creator-spaces/{slug}`로 연다.
- 앱 안 이동과 공유 미션의 기록은 바뀌지 않는 `creatorId`를 기준으로 한다. slug는 바뀔 수 있으므로 업무 기록의 키로 쓰지 않는다.

## 커스텀 slug 규칙

### 형식

- 3~30자
- 소문자 영문(`a-z`), 숫자(`0-9`), 하이픈(`-`), 밑줄(`_`)만 쓴다.
- 처음과 끝은 소문자나 숫자여야 한다. `-iu`, `iu_`는 안 된다.
- 정규식: `^[a-z0-9][a-z0-9_-]{1,28}[a-z0-9]$`
- 대문자는 자동으로 바꾸지 않고 거부한다. 입력 화면에서 소문자로 바꿔 보낸다.
- 조회(`GET /api/creator-spaces/{slug}`)는 대소문자를 구분하지 않는다. 아래 collation 때문에 `IU-Official`로 요청해도 `iu-official`이 열린다. 저장되는 slug는 항상 소문자라 중복 판단에는 영향이 없다.

소문자 ASCII로 제한하는 이유: `creator_space.slug` 컬럼의 collation(`utf8mb4_0900_ai_ci`)이 대소문자·악센트·전각 문자를 같은 값으로 비교한다. 허용하면 `IU`와 `iu`, `iu１`과 `iu1`이 화면에서는 달라 보여도 DB에서는 같은 slug로 취급된다.

위반하면 `VALIDATION_FAILED`(400)다.

### 예약어

서비스 경로·운영 용어와 겹쳐 사용자를 혼동시킬 수 있는 값은 쓸 수 없다. 위반하면 `RESERVED_SLUG`(400)다.

| 분류 | 예약어 |
| --- | --- |
| 운영·계정 | `admin`, `administrator`, `staff`, `manager`, `system`, `root`, `official`, `cking` |
| 인증·설정 | `api`, `auth`, `login`, `logout`, `signup`, `oauth`, `me`, `my`, `settings`, `help`, `support`, `notice` |
| 서비스 경로 | `creator`, `creators`, `space`, `spaces`, `mission`, `missions`, `event`, `events`, `ticket`, `tickets`, `winner`, `winners`, `notification`, `notifications` |
| 기타 | `null`, `undefined` |

예약어는 정확히 일치할 때만 막는다. `iu-official`처럼 예약어를 포함하는 것은 허용한다. 목록을 바꾸려면 `CreatorSpaceCustomSlug.RESERVED`와 이 표를 함께 고친다.

### 중복

- 다른 Space가 쓰고 있는 slug는 쓸 수 없다. `SLUG_ALREADY_TAKEN`(409)다.
- 먼저 조회로 걸러내고, 두 Creator가 동시에 같은 slug를 요청하면 `creator_space.slug` UNIQUE 제약(`uk_creator_space_slug`)이 하나만 통과시킨다. 나머지는 같은 `SLUG_ALREADY_TAKEN`을 받는다.
- 지금 쓰고 있는 slug와 같은 값으로 바꾸면 아무것도 바꾸지 않고 성공한다.
- 자동 slug 형식(`creator-43`)도 커스텀 slug로 쓸 수 있다. 아직 승인되지 않은 Creator의 자동 slug를 먼저 가져간 경우는 아래 "자동 slug 충돌"로 처리한다.

### 변경 간격

- **마지막 변경 후 14일이 지나야 다시 바꿀 수 있다.** 14일째 되는 시각부터 바로 바꿀 수 있다. 어기면 `SLUG_CHANGE_TOO_SOON`(409)다.
- **첫 변경은 제한하지 않는다.** 승인 때 받은 자동 slug(`creator-42`)에서 원하는 이름으로 처음 바꾸는 것은 바로 된다.
- 지금 slug와 같은 값으로 요청하면 변경으로 치지 않으므로 14일 기간도 다시 시작하지 않는다.
- 마지막 변경 시각은 `creator_space.slug_changed_at`(UTC, V21)에 저장한다. 다음에 바꿀 수 있는 시각은 Space 응답의 `slugChangeableAt`으로 내려주며, 한 번도 바꾸지 않았으면 `null`이다. 화면에서 "N일 후 변경 가능"을 보여줄 때 쓴다.
- 같은 Creator의 프로필 수정과 slug 변경은 `creator_space` 행 잠금으로 직렬화한다. 동시에 서로 다른 slug 변경 요청이 와도 첫 변경이 커밋된 뒤 다음 요청이 14일 제한을 다시 확인한다.

제한을 두는 이유:

- slug를 바꿀 때마다 이미 퍼진 공유 링크가 깨진다. 공유 미션으로 퍼진 링크가 자주 끊기지 않게 한다.
- 인기 있는 이름을 여러 번 바꿔 가며 선점하는 것을 어렵게 한다.

### 이전 slug 예약과 예전 링크(이슈 #301)

- slug를 바꾸면 예전 slug로 된 링크는 더 이상 열리지 않는다(404). 예전 slug를 새 주소로 넘겨주는 redirect는 MVP 범위가 아니다.
- **버린 이전 slug는 14일 동안 예약된다.** 예약 기간에는 다른 Creator가 그 slug로 바꿀 수 없다(`SLUG_ALREADY_TAKEN`). 기존 공유 링크가 곧바로 다른 Creator의 Space를 여는 것을 막기 위해서다.
- 예약 기간이 끝나면 누구나 쓸 수 있다. 이때 예전 링크는 새 주인의 Space를 연다.
- 예약은 `creator_space_slug_reservation`(V24)에 slug·예약한 Creator·만료 시각으로 저장한다. 만료는 조회 시점에 만료 시각과 비교해 판단하며, 만료된 행은 같은 slug를 다시 예약하거나 쓸 때 덮어쓰거나 지운다(정리 배치 없음).
- 승인 시 자동 slug도 예약 중인 slug를 사용 중으로 보고 대체 slug를 쓴다(아래 "자동 slug 충돌").

변경 간격(14일)과 예약 기간(14일)은 별도 정책이다. 변경 간격은 "한 Creator가 얼마나 자주 바꿀 수 있는지", 예약 기간은 "버린 slug를 다른 Creator가 언제부터 가져갈 수 있는지"를 정한다. 기간 값은 각각 `CreatorSpaceCustomSlug.CHANGE_INTERVAL`, `RESERVATION_PERIOD`다.

### 되돌리기

- **본인이 버린 slug로는 예약 기간 안에 언제든 되돌릴 수 있다.** 14일 변경 간격을 받지 않는다. 이미 쓰던 값이라 형식·예약어 검사도 다시 하지 않는다. 30자를 넘는 긴 자동 slug(관리자가 긴 템플릿 규칙을 쓴 경우)로도 되돌릴 수 있도록, 요청 단계에서는 빈 값과 컬럼 길이(100자)만 검사하고 커스텀 slug 형식은 새 slug일 때만 서비스에서 검사한다.
- 되돌려도 마지막 변경 시각(`slug_changed_at`)은 바뀌지 않는다. 되돌리기를 이용해 새 slug로 바꾸는 14일 제한을 우회할 수 없다.
  - 예: 9/1 `creator-42` → `iu-official`, 9/4 `creator-42`로 되돌림(가능), 9/5 `iu-2026`으로 변경(거절, 9/15부터 가능)
- 되돌리면서 버린 slug(위 예의 `iu-official`)도 14일 예약된다. 그 사이 본인은 다시 그 slug로 돌아갈 수 있다.

### 동시 요청

- slug를 버리는 쪽은 "Space slug 변경"과 "예약 추가"를 한 트랜잭션으로 커밋한다. 가져가려는 쪽은 "그 slug를 쓰는 Space가 있는지"를 먼저, "예약이 있는지"를 나중에 확인한다. 첫 확인이 상대 커밋 전이면 아직 사용 중으로 보이고, 커밋 후면 두 번째 확인에서 예약이 보이므로 버려지는 순간에 끼어들어 가져갈 수 없다.
- 두 Creator가 같은 빈 slug를 동시에 요청하면 `uk_creator_space_slug` UNIQUE 제약이 하나만 통과시킨다.

## 자동 slug

- 승인 시 활성 템플릿의 `slugRule`에서 `{creatorId}`를 실제 creatorId로 바꿔 만든다. 규칙 형식은 [README.md](README.md#slug-생성과-slugrule-검증2단-방어)를 따른다.
- 자동 slug끼리는 끝 숫자열이 creatorId라서 겹치지 않는다.

### 자동 slug 충돌

다른 Creator가 커스텀 slug로 자동 slug를 먼저 가져갔거나, 버린 slug로 예약해 두었을 수 있다. 예: 42번이 slug를 `creator-43`으로 바꾼 뒤 43번이 승인됨.

- 이때 `-2`, `-3`처럼 번호를 붙여 비어 있는 값을 쓴다(`creator-43-2`). 승인은 slug 충돌로 실패하지 않는다.
- 번호는 100까지 시도하며, 모두 쓰이고 있거나 100자를 넘으면 `SLUG_ALREADY_TAKEN`으로 승인을 롤백한다. 현실적으로 일어나지 않는 경우다.
- 비어 있는지 확인한 뒤 저장하기 전에 같은 값을 누가 커스텀 slug로 가져가면 UNIQUE 제약 위반으로 승인이 실패한다. 드문 경합이며, 관리자가 승인을 다시 시도하면 다음 번호로 만들어진다.

## 오류 요약

| 코드 | HTTP | 상황 |
| --- | --- | --- |
| `VALIDATION_FAILED` | 400 | 형식 위반 |
| `RESERVED_SLUG` | 400 | 예약어 |
| `SLUG_ALREADY_TAKEN` | 409 | 다른 Space가 사용 중이거나 다른 Creator가 예약 중 |
| `SLUG_CHANGE_TOO_SOON` | 409 | 마지막 변경 후 14일이 지나지 않음 |
| `RESOURCE_NOT_FOUND` | 404 | 없는 slug로 조회 |
