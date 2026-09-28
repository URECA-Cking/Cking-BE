# Creator Space API

Creator Space 조회, Creator 본인의 홈·프로필 수정과 커스텀 slug 변경 API 상세 계약이다(이슈 #286, #290). 모든 성공·실패 응답은 [공통 API 규약](../../common/api.md)의 응답 봉투를 사용하며, 아래 Response 예시는 `data` 값이다.

## 범위와 권한

- Space는 Creator 승인 시 활성 기본 템플릿을 복사해 만들어진다([README.md](README.md#creator-space-자동-생성이슈-270)). 이 API는 만들어진 Space의 조회·수정만 다룬다.
- 공개 조회(`GET /api/creators/{creatorId}/space`, `GET /api/creator-spaces/{slug}`)는 인증 없이 호출할 수 있다.
- 본인 API(`/api/creator/space`, `/api/creator/space/slug`)는 Bearer Access JWT가 필수다. Controller는 `@CurrentMemberId`로 받은 memberId를 넘기고, Application이 그 memberId의 Creator를 찾는다. 경로에 대상 ID가 없으므로 다른 Creator의 Space는 조회·수정할 수 없다.
- 공유 URL은 프론트엔드의 `/space/{slug}` 형식이다. slug는 이 공개 조회의 탐색에만 사용하고, 응답의 `creatorId`를 앱 내부 식별과 Creator별 업무 데이터·미션 기록 식별자로 사용한다. slug를 업무 기록에 저장하지 않는다.
- 수정은 Space에만 반영된다. 템플릿과 다른 Creator의 Space는 바뀌지 않는다.
- 탭(홈·미션·게시물·이벤트) 노출 설정은 없다(이슈 #290). 탭은 항상 노출하며, 내용이 없으면 화면에서 "현재 열려있는 게 없습니다"를 보여준다.
- slug 형식·예약어·중복 규칙은 [space-slug-policy.md](space-slug-policy.md)를 따른다.

## 응답 필드

| 필드 | 설명 |
| --- | --- |
| `creatorId` | Creator ID. 앱 안 이동과 업무 기록의 기준이다 |
| `creatorName` | Creator 이름 |
| `slug` | 공유 링크(`/space/{slug}`)에 쓰는 식별 문자열. 승인 시 자동으로 만들어지고 Creator가 바꿀 수 있다 |
| `introText` | 소개 문구 |
| `profileImageUrl`, `bannerImageUrl` | 프로필·배너 이미지 URL |
| `slugChangeableAt` | **본인 API 응답에만 있다.** slug를 다시 바꿀 수 있는 시각(UTC RFC 3339). 한 번도 바꾸지 않았으면 `null`이며 바로 바꿀 수 있다. slug 변경 이력이라 공개 조회 응답에는 담지 않는다 |

공개 조회 응답:

```json
{
  "creatorId": 42,
  "creatorName": "크리에이터",
  "slug": "iu-official",
  "introText": "크리에이터와 함께하는 공간이에요",
  "profileImageUrl": "https://cdn.cking.co.kr/default/profile.png",
  "bannerImageUrl": "https://cdn.cking.co.kr/default/banner.png"
}
```

본인 API 응답(`GET`·`PATCH /api/creator/space`, `PATCH /api/creator/space/slug`)은 위 필드에 `slugChangeableAt`이 더해진다.

```json
{
  "creatorId": 42,
  "creatorName": "크리에이터",
  "slug": "iu-official",
  "introText": "크리에이터와 함께하는 공간이에요",
  "profileImageUrl": "https://cdn.cking.co.kr/default/profile.png",
  "bannerImageUrl": "https://cdn.cking.co.kr/default/banner.png",
  "slugChangeableAt": "2026-10-12T03:00:00Z"
}
```

## GET /api/creators/{creatorId}/space

creatorId로 Creator Space를 조회한다. 인증이 필요 없다. 응답은 위 공개 조회 응답이다.

## GET /api/creator-spaces/{slug}

공유 URL의 slug로 Creator Space를 조회한다. 공유 링크를 열 때 쓰며 인증이 필요 없다. 응답은 위 공개 조회 응답이다. 클라이언트는 `/space/{slug}`에서 추출한 slug를 이 API에 전달하고, 받은 `creatorId`를 이후 앱 내부 식별과 Creator별 미션·기록 요청에 사용한다. slug를 바꾸면 예전 slug로는 조회되지 않으며, 대소문자는 구분하지 않는다([space-slug-policy.md](space-slug-policy.md#형식)).

## GET /api/creator/space

호출자 본인의 Creator Space를 조회한다. 응답은 위 본인 API 응답이다.

## PATCH /api/creator/space

호출자 본인의 Creator Space 홈·프로필을 수정한다. 모든 필드를 한 번에 교체하므로 전부 필수다. slug는 아래 slug 변경 API로 바꾼다. 응답은 수정된 Space이며 위 본인 API 응답이다.

```json
{
  "introText": "새 소개 문구",
  "profileImageUrl": "https://cdn.cking.co.kr/creator/42/profile.png",
  "bannerImageUrl": "https://cdn.cking.co.kr/creator/42/banner.png"
}
```

| 필드 | 제약 |
| --- | --- |
| `introText`, `profileImageUrl`, `bannerImageUrl` | 필수, 공백 불가, 최대 500자 |

## PATCH /api/creator/space/slug

호출자 본인의 Creator Space slug를 커스텀 slug로 바꾼다. 응답은 바뀐 Space이며 위 본인 API 응답이다. 지금 slug와 같은 값이면 아무것도 바꾸지 않고 성공한다. 마지막 변경 후 14일이 지나야 다시 바꿀 수 있으며, 첫 변경(자동 slug → 커스텀)은 바로 된다.

```json
{
  "slug": "iu-official"
}
```

| 필드 | 제약 |
| --- | --- |
| `slug` | 필수, 3~30자 소문자·숫자·하이픈·밑줄, 처음과 끝은 소문자나 숫자. 예약어·중복 불가([space-slug-policy.md](space-slug-policy.md)) |

## 오류

- 공개 조회에서 Creator·Space·slug가 없으면 `RESOURCE_NOT_FOUND`(404)다.
- 본인 API에서 JWT가 없거나 유효하지 않으면 `UNAUTHORIZED`(401)다.
- 본인 API 호출자가 Creator가 아니면 `FORBIDDEN`(403)이다.
- 본인 API 호출자가 Creator지만 Space가 없으면 `RESOURCE_NOT_FOUND`(404)다. V19 백필이 건너뛰어진 기존 Creator가 해당한다([README.md](README.md#기존-승인-creator-백필v19)).
- 요청 필드가 누락되거나 공백·길이 초과·slug 형식 위반이면 `VALIDATION_FAILED`(400)다.
- slug가 예약어면 `RESERVED_SLUG`(400)다.
- slug를 다른 Space가 쓰고 있으면 `SLUG_ALREADY_TAKEN`(409)다.
- 마지막 slug 변경 후 14일이 지나지 않았으면 `SLUG_CHANGE_TOO_SOON`(409)이다. 다시 바꿀 수 있는 시각은 조회 응답의 `slugChangeableAt`으로 확인한다.
