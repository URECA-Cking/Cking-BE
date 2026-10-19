# Creator Space API

Creator Space 조회, Creator 본인의 홈·프로필 수정과 커스텀 slug 변경 API 상세 계약이다(이슈 #286, #290). 모든 성공·실패 응답은 [공통 API 규약](../../common/api.md)의 응답 봉투를 사용하며, 아래 Response 예시는 `data` 값이다.

## 범위와 권한

- Space는 Creator 승인 시 활성 기본 템플릿을 복사해 만들어진다([README.md](README.md#creator-space-자동-생성이슈-270)). 이 API는 만들어진 Space의 조회·수정만 다룬다.
- 공개 조회(`GET /api/creators`, `GET /api/creators/{creatorId}/space`, `GET /api/creator-spaces/{slug}`)는 인증 없이 호출할 수 있다.
- 본인 API(`/api/creator/space`, `/api/creator/space/slug`, `/api/creator/space/profile-images`, `/api/creator/space/profile-image`, `/api/creator/space/banner-images`, `/api/creator/space/banner-image`, `/api/creator/profile`)는 Bearer Access JWT가 필수다. Controller는 `@CurrentMemberId`로 받은 memberId를 넘기고, Application이 그 memberId의 Creator를 찾는다. 경로에 대상 ID가 없으므로 다른 Creator의 Space는 조회·수정할 수 없다.
- 공유 URL은 프론트엔드의 `/space/{slug}` 형식이다. slug는 이 공개 조회의 탐색에만 사용하고, 응답의 `creatorId`를 앱 내부 식별과 Creator별 업무 데이터·미션 기록 식별자로 사용한다. slug를 업무 기록에 저장하지 않는다.
- 수정은 Space에만 반영된다. 템플릿과 다른 Creator의 Space는 바뀌지 않는다.
- 승인된 Creator의 공개 이름·사진은 활동명과 Space 프로필 사진으로 단일화한다. Member 프로필 수정 API는 Creator의
  공개 표시를 바꾸지 않으며, Creator는 Space 편집에서만 공개 프로필을 관리한다.
- 탭(홈·미션·게시물·이벤트) 노출 설정은 없다(이슈 #290). 탭은 항상 노출하며, 내용이 없으면 화면에서 "현재 열려있는 게 없습니다"를 보여준다.
- slug 형식·예약어·중복 규칙은 [space-slug-policy.md](space-slug-policy.md)를 따른다.

## 응답 필드

| 필드 | 설명 |
| --- | --- |
| `creatorId` | Creator ID. 앱 안 이동과 업무 기록의 기준이다 |
| `creatorName` | Creator 공개 활동명. OAuth 원본 이름은 노출하지 않는다 |
| `slug` | 공유 링크(`/space/{slug}`)에 쓰는 식별 문자열. 승인 시 자동으로 만들어지고 Creator가 바꿀 수 있다 |
| `introText` | 소개 문구 |
| `profileImageUrl`, `bannerImageUrl` | 프로필·배너 이미지 URL |
| `followerCount` | **단건 공개 조회(`GET /api/creators/{creatorId}/space`, `GET /api/creator-spaces/{slug}`)에만 있다.** 이 Creator를 팔로우한 건수(0 이상). `creator_follow`를 요청 시점에 센다(`idx_creator_follow_creator` 사용). 목록(`GET /api/creators`)과 본인 API 응답에는 담지 않는다 |
| `slugChangeableAt` | **본인 API 응답에만 있다.** slug를 다시 바꿀 수 있는 시각(UTC RFC 3339). 한 번도 바꾸지 않았으면 `null`이며 바로 바꿀 수 있다. slug 변경 이력이라 공개 조회 응답에는 담지 않는다 |

단건 공개 조회 응답:

```json
{
  "creatorId": 42,
  "creatorName": "크리에이터",
  "slug": "iu-official",
  "introText": "크리에이터와 함께하는 공간이에요",
  "profileImageUrl": "https://cdn.cking.co.kr/default/profile.png",
  "bannerImageUrl": "https://cdn.cking.co.kr/default/banner.png",
  "followerCount": 1280
}
```

목록(`GET /api/creators`) 항목은 `followerCount` 없이 위 필드에 `openEventCount`를 더해 담는다. 항목마다 집계가 늘어나는 것을 피하기 위해서이며, 목록에 팔로워 수가 필요하면 별도 이슈에서 일괄 집계로 다룬다.

본인 API 응답(`GET`·`PATCH /api/creator/space`, `PATCH /api/creator/space/slug`)은 공통 프로필 필드(`creatorId`, `creatorName`, `slug`, `introText`, `profileImageUrl`, `bannerImageUrl`)와 `slugChangeableAt`을 담는다. `followerCount`는 담지 않는다.

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

## GET /api/creators

공개 Space가 있는 Creator 목록을 페이지로 조회한다(이슈 #352). 인증이 필요 없다. 이벤트 유무·팔로우 여부와 관계없이 Creator와 Space가 모두 있는 항목을 반환하며, Space가 없는 Creator는 포함하지 않는다. 클라이언트는 이 목록으로 탐색·관심 크리에이터 선택 화면을 구성한다.

| 쿼리 | 기본값 | 제약 |
| --- | --- | --- |
| `page` | 0 | 0 이상 |
| `size` | 20 | 1~100 |
| `keyword` | 없음 | 최대 50자. Creator 이름에 포함된 항목만 반환한다(대소문자 무시). 없거나 공백이면 전체를 반환한다. 앞뒤 공백은 무시하고 `%`·`_`는 문자 그대로 찾는다 |

정렬은 Creator 이름 오름차순이며, 이름이 같으면 `creatorId` 오름차순이다. 항상 같은 순서를 보장하므로 페이지를 순회해도 항목이 누락되거나 중복되지 않는다(순회 중 Creator가 추가·삭제되면 경계 항목이 이동할 수 있다). 응답은 [공통 API 규약](../../common/api.md)의 `PageResponse`이며 각 `items` 항목은 위 공개 조회 응답과 같은 필드에 `openEventCount`를 더한 것이다. `openEventCount`는 해당 Creator의 삭제되지 않은 Event 중 표시 상태가 `IN_PROGRESS`(`OPEN`이면서 `endAt` 전)인 개수(0 이상 정수)로, `GET /api/events?creatorId={id}&status=IN_PROGRESS`의 `totalElements`와 같다. 진행 중 Event가 없으면 필드를 빼지 않고 `0`을 준다. 한 페이지의 Creator 전체를 `GROUP BY creator_id` 한 번으로 집계하므로 쿼리 수가 `size`에 비례하지 않는다. 결과가 없으면 `items`가 빈 배열이고 `totalElements`가 0이다.

```json
{
  "items": [
    {
      "creatorId": 42,
      "creatorName": "크리에이터",
      "slug": "iu-official",
      "introText": "크리에이터와 함께하는 공간이에요",
      "profileImageUrl": "https://cdn.cking.co.kr/default/profile.png",
      "bannerImageUrl": "https://cdn.cking.co.kr/default/banner.png",
      "openEventCount": 2
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1,
  "hasNext": false
}
```

## GET /api/creators/{creatorId}/space

creatorId로 Creator Space를 조회한다. 인증이 필요 없다. 응답은 위 공개 조회 응답이다.

## GET /api/creator-spaces/{slug}

공유 URL의 slug로 Creator Space를 조회한다. 공유 링크를 열 때 쓰며 인증이 필요 없다. 응답은 위 공개 조회 응답이다. 클라이언트는 `/space/{slug}`에서 추출한 slug를 이 API에 전달하고, 받은 `creatorId`를 이후 앱 내부 식별과 Creator별 미션·기록 요청에 사용한다. slug를 바꾸면 예전 slug로는 조회되지 않으며, 대소문자는 구분하지 않는다([space-slug-policy.md](space-slug-policy.md#형식)).

## GET /api/creator/space

호출자 본인의 Creator Space를 조회한다. 응답은 위 본인 API 응답이다.

## PATCH /api/creator/space

호출자 본인의 Creator Space 홈·프로필을 수정한다. 기본 필드는 모두 교체하며 slug는 아래 slug 변경 API로 바꾼다. `profileImageKey`와 `bannerImageKey`는 생략하면 현재 이미지를 유지하고, 본인이 업로드한 키를 보내면 교체하며, `null`을 명시하면 Space의 기본 이미지로 복귀한다. 응답은 수정된 Space이며 위 본인 API 응답이다.

```json
{
  "introText": "새 소개 문구",
  "profileImageUrl": "https://cdn.cking.co.kr/creator/42/profile.png",
  "bannerImageUrl": "https://cdn.cking.co.kr/creator/42/banner.png",
  "profileImageKey": "creator-space-profile-images/42/uuid.jpg",
  "bannerImageKey": "creator-space-profile-images/42/banner/uuid.jpg"
}
```

| 필드 | 제약 |
| --- | --- |
| `introText`, `profileImageUrl`, `bannerImageUrl` | 필수, 공백 불가, 최대 500자 |
| `profileImageKey` | 선택, 최대 200자. 생략하면 유지, 업로드 완료한 본인 키면 교체, `null`이면 기본 이미지 복귀 |
| `bannerImageKey` | 선택, 최대 200자. 생략하면 유지, 배너로 업로드 완료한 본인 키면 교체, `null`이면 기본 배너 복귀 |

기존 URL 기반 수정은 기존 Space 호환을 위해 유지한다. 새 파일 업로드는 아래 두 API 또는 위 PATCH의 `profileImageKey`를 사용하며, 업로드된 이미지가 연결되면 공개 응답의 `profileImageUrl`은 CDN URL로 갱신된다. 연결된 이미지가 있으면 PATCH의 `profileImageUrl` 입력은 무시하고 현재 연결을 유지한다. 배너도 같다. 업로드한 배너가 연결되면 공개 응답의 `bannerImageUrl`은 CDN URL로 갱신되고, 연결된 배너가 있으면 PATCH의 `bannerImageUrl` 입력은 무시한다. 기본 배너로 복귀하면 업로드 전의 기본 배너(Space 생성·기존 URL 수정 시점의 값)로 돌아간다. 응답의 `bannerImageUrl`을 그대로 다시 보내도 기본 배너가 그 값으로 바뀌지 않는다.

## POST /api/creator/space/profile-images

Creator Space 전용 프로필 사진을 업로드한다. `multipart/form-data`의 `image` part에 실제 JPEG/PNG 5MB 이하, 최소 200×200px 이미지를 보낸다. 공통 정규화 후 JPEG로 저장하며, 24시간 안에 연결하지 않으면 정리 대상이 된다. 공개 주소는 `creator-space-profile-images/*` CloudFront behavior가 연결된 개발 CDN을 사용한다.

```json
{ "imageKey": "creator-space-profile-images/42/uuid.jpg" }
```

## PUT /api/creator/space/profile-image

본인이 업로드한 `imageKey`를 현재 Creator Space 프로필 사진으로 연결한다. 현재 연결된 것과 같은 key의 재요청은 멱등 성공한다. 다른 Creator의 키, 현재 연결된 것과 다른 이미 연결·삭제 대기·존재하지 않는 키는 `INVALID_SPACE_PROFILE_IMAGE`(400)다. 이전 연결 이미지는 삭제 대기로 전환되어 정리 작업이 회수한다.

```json
{ "imageKey": "creator-space-profile-images/42/uuid.jpg" }
```

## POST /api/creator/space/banner-images

Creator Space 배너 이미지를 업로드한다(이슈 #590). `multipart/form-data`의 `image` part에 실제 JPEG/PNG 5MB 이하, 최소 600×200px 이미지를 보낸다. 가로세로 비율은 강제하지 않는다. 공통 정규화 후 긴 변 2048px 이하의 JPEG로 저장하며, 24시간 안에 연결하지 않으면 정리 대상이 된다. 응답 `201`.

프로필 사진과 같은 저장소·같은 CDN·같은 정리 작업을 쓴다. 키는 프로필 사진과 같은 `creator-space-profile-images/` 접두사 아래 `banner/` 하위에 만들어지므로 별도 CDN 설정이 필요 없다. 같은 테이블에 종류(`image_kind`)로 구분해 기록하며, 프로필 사진으로 올린 키를 배너로(또는 반대로) 연결할 수 없다.

```json
{ "imageKey": "creator-space-profile-images/42/banner/uuid.jpg" }
```

## PUT /api/creator/space/banner-image

본인이 **배너로** 업로드한 `imageKey`를 현재 Creator Space 배너로 연결한다. 현재 연결된 것과 같은 key의 재요청은 멱등 성공한다. 다른 Creator의 키, 프로필 사진으로 올린 키, 업로드가 끝나지 않았거나 이미 연결·삭제 대기인 키, 존재하지 않는 키는 `INVALID_SPACE_BANNER_IMAGE`(400)다. 이전 연결 배너는 삭제 대기로 전환되어 정리 작업이 회수한다. 응답은 위 본인 API 응답이다.

```json
{ "imageKey": "creator-space-profile-images/42/banner/uuid.jpg" }
```

## GET /api/creator/profile

Creator 본인의 활동명 설정 상태를 조회한다. 활동명은 Space 편집 화면에서 이 API와 `PUT`을 사용해 따로 관리한다.

```json
{
  "creatorId": 42,
  "activityName": "cking_creator",
  "activityNameConfigured": true,
  "activityNameChangeableAt": "2026-10-26T00:00:00Z",
  "restorableActivityName": "old_creator"
}
```

## PUT /api/creator/profile

Creator 본인의 활동명을 전체 교체한다. 형식·정규화는 Member 닉네임과 같고, Creator 활동명 namespace 안에서만 중복 불가다.
최초 설정은 즉시 가능하며, 이후 실제 변경은 14일 간격이다. 유효한 이전 활동명으로의 복귀는 예약 기간 안에 즉시 가능하다.

```json
{
  "activityName": "cking_creator"
}
```

## PATCH /api/creator/space/slug

호출자 본인의 Creator Space slug를 커스텀 slug로 바꾼다. 응답은 바뀐 Space이며 위 본인 API 응답이다. 지금 slug와 같은 값이면 아무것도 바꾸지 않고 성공한다. 마지막 변경 후 14일이 지나야 다시 바꿀 수 있으며, 첫 변경(자동 slug → 커스텀)은 바로 된다. 버린 이전 slug는 14일 동안 예약돼 다른 Creator가 쓸 수 없고, 본인은 그 기간 안에 14일 제한 없이 되돌릴 수 있다([space-slug-policy.md](space-slug-policy.md#되돌리기)).

```json
{
  "slug": "iu-official"
}
```

| 필드 | 제약 |
| --- | --- |
| `slug` | 필수, 최대 100자. 새 slug는 3~30자 소문자·숫자·하이픈·밑줄, 처음과 끝은 소문자나 숫자이며 예약어·중복 불가. 본인이 버린 이전 slug로 되돌릴 때는 형식 검사를 하지 않는다([space-slug-policy.md](space-slug-policy.md)) |

## Creator Space 공유 미션 완료

공유 링크에서 얻은 `creatorId`로 공유 완료를 보상 처리할 때는 Mission API의 `POST /api/creators/{creatorId}/missions/share/complete`를 사용한다. Bearer Access JWT와 UUID `requestId`가 필요하며, 서버가 해당 Creator의 SHARE 미션을 검증한 뒤 Creator 전용 응모권 적립을 요청한다. 이 API는 공유 버튼 클릭을 완료로 간주하는 Mock 방식이고, 사용자·Creator·SHARE 미션 기준으로 평생 한 번만 보상한다. 상세 요청·응답·오류 계약은 [Mission API](../mission/api.md#post-apicreatorscreatoridmissionssharecomplete)를 따른다.

## 오류

- 공개 조회에서 Creator·Space·slug가 없으면 `RESOURCE_NOT_FOUND`(404)다. 목록 조회는 결과가 없어도 404가 아니라 빈 `items`로 성공한다.
- 목록 조회의 `page`·`size`·`keyword`가 제약을 어기거나 숫자가 아니면 `VALIDATION_FAILED`(400)다.
- 본인 API에서 JWT가 없거나 유효하지 않으면 `UNAUTHORIZED`(401)다.
- 본인 API 호출자가 Creator가 아니면 `FORBIDDEN`(403)이다.
- 본인 API 호출자가 Creator지만 Space가 없으면 `RESOURCE_NOT_FOUND`(404)다. V19 백필이 건너뛰어진 기존 Creator가 해당한다([README.md](README.md#기존-승인-creator-백필v19)).
- 요청 필드가 누락되거나 공백·길이 초과·slug 형식 위반이면 `VALIDATION_FAILED`(400)다.
- 업로드 파일이 이미지 형식·크기·해상도 규칙을 어기거나, 연결하려는 키가 본인의 업로드 완료 이미지가 아니면 `INVALID_SPACE_PROFILE_IMAGE`(400)이다.
- 배너 업로드 파일이 이미지 형식·크기·해상도 규칙을 어기거나, 연결하려는 키가 본인이 배너로 올린 업로드 완료 이미지가 아니면 `INVALID_SPACE_BANNER_IMAGE`(400)이다.
- `PATCH /api/creator/space`의 `profileImageUrl`·`bannerImageUrl`이 새로 적용되는데 이미 정리되었거나 정리가 확정된 관리자 템플릿 이미지의 URL이면 `INVALID_SPACE_TEMPLATE_IMAGE`(400)다. 현재 URL과 같은 값을 다시 보내거나 업로드한 이미지가 연결돼 URL 입력이 무시되는 경우는 해당하지 않는다([Space Template API](space-template-api.md#post-apiadmincreator-space-templatesprofile-images)).
- slug가 예약어면 `RESERVED_SLUG`(400)다.
- slug를 다른 Space가 쓰고 있거나 다른 Creator가 예약 중이면 `SLUG_ALREADY_TAKEN`(409)다.
- 마지막 slug 변경 후 14일이 지나지 않았으면 `SLUG_CHANGE_TOO_SOON`(409)이다. 다시 바꿀 수 있는 시각은 조회 응답의 `slugChangeableAt`으로 확인한다.
- 활동명 형식이 맞지 않으면 `VALIDATION_FAILED`(400), 다른 Creator가 사용·예약 중이면 `ACTIVITY_NAME_ALREADY_TAKEN`(409),
  변경 간격 안이면 `ACTIVITY_NAME_CHANGE_TOO_SOON`(409)이다.
- 활동명 설정 화면이 배포되기 전 호환 기간에는 기존 이관 Creator도 게시글·Event를 생성할 수 있다. 작성 전 설정 강제는
  FE 연동 완료 후 별도 릴리스에서 활성화한다.

## AI 콘텐츠 태그와 소개 수정

[콘텐츠 태그 계약](content-tags.md)의 content_revision은 정규화한 introText 변경 시에만 증가한다.
같은 저장 트랜잭션에서 DB outbox trigger가 작업을 남기고, 저장 요청은 모델 처리를 기다리지 않는다.
변경된 소개의 과거 본인 확인 태그는 이력으로 보존하며 공개 조회에서 제외한다.
