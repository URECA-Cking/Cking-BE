# Creator Space API

Creator Space 홈·프로필 조회와 Creator 본인의 수정 API 상세 계약이다(이슈 #286). 모든 성공·실패 응답은 [공통 API 규약](../../common/api.md)의 응답 봉투를 사용하며, 아래 Response 예시는 `data` 값이다.

## 범위와 권한

- Space는 Creator 승인 시 활성 기본 템플릿을 복사해 만들어진다([README.md](README.md#creator-space-자동-생성이슈-270)). 이 API는 만들어진 Space의 조회·수정만 다룬다.
- 공개 조회는 인증 없이 호출할 수 있다.
- 본인 API(`/api/creator/space`)는 Bearer Access JWT가 필수다. Controller는 `@CurrentMemberId`로 받은 memberId를 넘기고, Application이 그 memberId의 Creator를 찾는다. 경로에 대상 ID가 없으므로 다른 Creator의 Space는 조회·수정할 수 없다.
- 수정은 Space에만 반영된다. 템플릿과 다른 Creator의 Space는 바뀌지 않는다.

## 응답 필드

| 필드 | 설명 |
| --- | --- |
| `creatorId` | Creator ID |
| `creatorName` | Creator 이름 |
| `slug` | Space 식별 문자열. 승인 시 템플릿의 `slugRule`로 만들어지며 이 API로는 수정할 수 없다. 공유 URL을 만들 때 사용한다 |
| `introText` | 소개 문구 |
| `profileImageUrl`, `bannerImageUrl` | 프로필·배너 이미지 URL |
| `homeTabEnabled`, `missionsTabEnabled`, `postsTabEnabled`, `eventsTabEnabled` | 홈·미션·게시물·이벤트 탭 노출 여부. 화면 노출만 제어하며, 탭을 꺼도 해당 기능 API는 막지 않는다 |

```json
{
  "creatorId": 42,
  "creatorName": "크리에이터",
  "slug": "creator-42",
  "introText": "크리에이터와 함께하는 공간이에요",
  "profileImageUrl": "https://cdn.cking.co.kr/default/profile.png",
  "bannerImageUrl": "https://cdn.cking.co.kr/default/banner.png",
  "homeTabEnabled": true,
  "missionsTabEnabled": true,
  "postsTabEnabled": true,
  "eventsTabEnabled": true
}
```

## GET /api/creators/{creatorId}/space

Creator Space를 조회한다. 인증이 필요 없다. 응답은 위 응답 필드와 같다.

## GET /api/creator/space

호출자 본인의 Creator Space를 조회한다. 응답은 위 응답 필드와 같다.

## PATCH /api/creator/space

호출자 본인의 Creator Space 홈·프로필을 수정한다. 모든 필드를 한 번에 교체하므로 전부 필수다. `slug`는 받지 않는다. 응답은 수정된 Space이며 위 응답 필드와 같다.

```json
{
  "introText": "새 소개 문구",
  "profileImageUrl": "https://cdn.cking.co.kr/creator/42/profile.png",
  "bannerImageUrl": "https://cdn.cking.co.kr/creator/42/banner.png",
  "homeTabEnabled": true,
  "missionsTabEnabled": false,
  "postsTabEnabled": true,
  "eventsTabEnabled": true
}
```

| 필드 | 제약 |
| --- | --- |
| `introText`, `profileImageUrl`, `bannerImageUrl` | 필수, 공백 불가, 최대 500자 |
| `homeTabEnabled`, `missionsTabEnabled`, `postsTabEnabled`, `eventsTabEnabled` | 필수 |

## 오류

- 공개 조회에서 Creator 또는 Space가 없으면 `RESOURCE_NOT_FOUND`(404)다.
- 본인 API에서 JWT가 없거나 유효하지 않으면 `UNAUTHORIZED`(401)다.
- 본인 API 호출자가 Creator가 아니면 `FORBIDDEN`(403)이다.
- 본인 API 호출자가 Creator지만 Space가 없으면 `RESOURCE_NOT_FOUND`(404)다. V19 백필이 건너뛰어진 기존 Creator가 해당한다([README.md](README.md#기존-승인-creator-백필v19)).
- 수정 요청 필드가 누락되거나 공백·길이 초과면 `VALIDATION_FAILED`(400)다.
