# Event 배너 API

Creator가 Event에 배너 이미지를 올리고 생성·수정 요청에 연결하는 계약이다(이슈 #748). 응답 봉투와 오류 형식은 [공통 API 규약](../../common/api.md)을, Event 생성·수정의 나머지 필드는 [Event API](api.md)를 따른다.

## 흐름

1. `POST /api/creator/events/banner-images`로 이미지를 올리고 `imageKey`를 받는다.
2. `POST /api/creator/events` 또는 `PATCH /api/creator/events/{eventId}`의 `bannerImageKey`에 그 키를 넣는다.
3. 조회 응답의 `bannerImageUrl`(만료되지 않는 CDN 주소)로 배너를 보여 준다. 배너가 없으면 `null`이고 화면이 대체 이미지를 고른다.

## POST /api/creator/events/banner-images

권한: 인증된 Creator. 호출자 식별자는 Access JWT에서 추출한다. `multipart/form-data`의 `image` part에 실제 JPEG/PNG 5MB 이하, 최소 600×200px 이미지를 보낸다. 가로세로 비율은 강제하지 않는다. [공통 정규화](../../common/image.md) 후 긴 변 2048px 이하의 JPEG로 저장한다. 원본을 읽기 전에 서버 전체 이미지 처리 입장 한도를 거치며, 넘치면 `IMAGE_PROCESSING_BUSY`(503)다. 응답 `201`.

```json
{ "imageKey": "creator-space-profile-images/42/event-banner/uuid.jpg" }
```

- 이 API는 Event에 배너를 연결하지 않는다. 24시간 안에 생성·수정으로 연결하지 않으면 정리 대상이 된다.
- `image` part가 없으면 `VALIDATION_FAILED`, 이미지 규칙 위반·손상 이미지는 `INVALID_EVENT_BANNER_IMAGE`(400), Creator가 아니면 `FORBIDDEN`이다.

### 저장 위치

키는 `creator-space-profile-images/{creatorId}/event-banner/{UUID}.jpg`다. CDN 공개(버킷 정책)와 서버 쓰기·삭제 권한이 이미 열린 Creator Space 이미지 접두사를 그대로 써서 인프라 변경 없이 동작한다. 새 접두사(예: `event-banner-images/`)로 옮기려면 Cking-Infra의 dev·prod 버킷 정책과 앱 서버 역할의 업로드 경로 목록을 함께 바꿔야 한다.

경로는 같지만 업로드 기록(`event_banner_image`)과 정리 작업은 Creator Space 이미지와 별개다. Space 이미지 정리는 자기 테이블에 기록된 키만 지우므로 Event 배너를 건드리지 않고, Space 키(`{creatorId}/…`, `{creatorId}/banner/…`, `template/…`)와 하위 경로도 겹치지 않는다. 공개 주소의 기준(`cking.event.banner-image.public-base-url`)은 Space 이미지 주소 설정을 그대로 따른다.

## 생성·수정 연결

| 요청 | `bannerImageKey` | 동작 |
| --- | --- | --- |
| `POST /api/creator/events` | 생략·`null` | 배너 없이 만든다 |
| `POST /api/creator/events` | 키 | 배너를 연결해 만든다 |
| `PATCH /api/creator/events/{eventId}` | 생략 | 현재 배너를 유지한다 |
| `PATCH /api/creator/events/{eventId}` | 키 | 배너를 교체한다. 현재와 같은 키면 그대로 둔다 |
| `PATCH /api/creator/events/{eventId}` | `null` | 배너를 뗀다 |

- 연결할 수 있는 키는 호출자 Creator가 올려 업로드가 끝났고 아직 어디에도 연결되지 않은 키뿐이다. 다른 Creator의 키, 이미 연결·삭제 대기인 키, 없는 키, 빈 문자열은 `INVALID_EVENT_BANNER_IMAGE`(400)다. 최대 200자.
- 수정 가능 상태는 Event 수정 규칙과 같다(DRAFT·REJECTED, REJECTED는 DRAFT로 돌아간다). 그 밖의 상태는 `INVALID_STATE`다.
- 연결은 Event 저장·수정과 같은 트랜잭션에서 처리한다. 어느 한쪽이 실패하면 둘 다 반영되지 않는다. 생성은 Event를 먼저 저장해 `requestId` 충돌을 확정한 뒤 배너를 연결하므로, 같은 `requestId` 동시 요청의 뒤쪽은 기존 Event 재사용 경로를 탄다.
- 같은 `requestId` 재시도의 본문 비교에 `bannerImageKey`도 포함한다. 같은 키면 기존 Event를 돌려주고(다시 연결하지 않는다), 다른 키면 `IDEMPOTENCY_CONFLICT`다.
- 교체·제거로 풀린 이전 배너와 DRAFT·REJECTED Event 삭제(`DELETE /api/creator/events/{eventId}`) 시의 배너는 삭제 대기로 바뀌어 정리 작업이 회수한다.

## 응답 필드

`bannerImageUrl`은 배너가 없으면 `null`이다.

| API | 필드 |
| --- | --- |
| `GET /api/events`, `GET /api/events/{eventId}` | `bannerImageUrl` |
| `GET /api/creator/events` | `bannerImageKey`, `bannerImageUrl` (수정 화면이 현재 배너를 비교·유지할 때 키를 쓴다) |
| `GET /api/admin/events/pending`, `GET /api/admin/events` | `bannerImageUrl` |
| `GET /api/admin/events/{eventId}/operation` | `event.bannerImageUrl` |

공개 상세 캐시(`cache:event:{eventId}`)와 목록 캐시도 이 값을 담는다. 배포 전에 저장된 캐시 항목에는 값이 없어 `null`로 읽히며 TTL 5초 안에 교체된다.

## 정리

`EventBannerImageCleanupScheduler`(기본 10분 간격, `cking.event.banner-image.cleanup-interval-ms`)가 올린 지 24시간이 지난 미연결 업로드를 삭제 대기로 선점하고, 삭제 대기로 바뀐 지 10분이 지난 객체를 지운 뒤 기록을 삭제한다. 10분 유예는 직전까지 응답·캐시에 남아 있던 주소가 바로 깨지지 않게 하기 위해서다. 객체 삭제에 실패하면 기록을 남기고 다음 실행에서 다시 시도한다.

업로드 기록 상태: `UPLOADING` → `UPLOADED` → `LINKED` → `DELETE_PENDING` → (삭제). 미연결 업로드는 `UPLOADING`·`UPLOADED`에서 바로 `DELETE_PENDING`이 된다.
