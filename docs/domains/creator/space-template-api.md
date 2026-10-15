# Creator Space Template 관리자 API

관리자가 관리하는 기본 크리에이터 스페이스 템플릿 API의 상세 계약이다. 모든 성공·실패 응답은 [공통 API 규약](../../common/api.md)의 응답 봉투를 사용하며, 아래 Response 예시는 `data` 값이다.

## 범위와 권한

- 모든 API는 Bearer Access JWT와 `ADMIN` 역할이 필수이며, Controller는 `@CurrentMemberId`를 기존 관리자 업무 식별자로 전달한다.
- 이 API는 템플릿 자체의 CRUD·활성화와, 템플릿에 넣을 기본 이미지 업로드만 다룬다. Creator 승인 시점에 활성 템플릿 값을 새 Creator 스페이스로 복사하는 동작은 [creator/README.md](README.md#creator-space-자동-생성이슈-270)를 참고한다 — 이 문서의 범위가 아니다.
- 활성 템플릿을 바꿔도 이미 생성된 Creator 스페이스는 변경되지 않는다. 활성 템플릿은 향후 스페이스 생성 시점에만 참조된다.
- 응답 시각은 UTC RFC 3339 형식(예: `2026-09-23T00:30:00Z`)이다.

## 템플릿 필드

| 필드 | 설명 |
| --- | --- |
| `introText` | 기본 소개 문구 (최대 500자) |
| `profileImageUrl` | 기본 프로필 이미지 URL (최대 500자) |
| `bannerImageUrl` | 기본 배너 이미지 URL (최대 500자) |
| `slugRule` | 공유 URL slug 생성 규칙 문자열 (최대 92자). 소문자·숫자·하이픈(`[a-z0-9-]`)으로 된 접두사 뒤 **맨 끝에** `{creatorId}`를 정확히 한 번 둬야 하고, 접두사가 있으면 마지막 글자는 숫자가 아니어야 한다(정규식 `^(?:[a-z0-9-]*[a-z-])?\{creatorId\}$`, 예: `creator-{creatorId}`). 그래야 slug 끝 숫자열이 곧 creatorId가 돼, 템플릿을 바꿔도 서로 다른 Creator의 slug가 겹치지 않는다. 실제 slug는 Creator 스페이스 생성 시점([creator/README.md](README.md#creator-space-자동-생성이슈-270))에 이 규칙의 `{creatorId}`를 실제 creatorId로 치환해 만들어지며, 템플릿 자체는 규칙만 보관한다. |

## 활성 템플릿 유일성

- 활성 템플릿은 항상 하나뿐이다. DB `creator_space_template.active_marker` UNIQUE 제약이 최종 안전망이며, 활성 템플릿만 이 컬럼에 `1`을 갖고 나머지는 `NULL`이다.
- 활성화 API는 대상 템플릿을 활성화하면서 기존에 활성이던 템플릿을 함께 비활성화한다. 두 동작은 하나의 트랜잭션으로 처리되며, 기존 템플릿의 비활성화를 먼저 flush한 뒤 새 템플릿을 활성화한다(순서를 지키지 않으면 Hibernate가 새 템플릿의 `active_marker=1` UPDATE를 먼저 내보내 UNIQUE 제약을 순간적으로 위반할 수 있다).
- 동시 활성화 요청은 advisory lock으로 직렬화된다. 락을 즉시 얻지 못한 요청은 `CONCURRENT_COMMAND`다. 락 key는 템플릿별이 아니라 전역이므로, 관리자 검증과 대상 템플릿 존재 여부 확인은 락을 잡기 전에 끝낸다 — 그렇지 않으면 없는 templateId·비관리자 같은 잘못된 요청이 락부터 잡아 무관한 다른 템플릿의 정상 활성화 요청까지 `CONCURRENT_COMMAND`로 거부될 수 있다.
- 락을 잡기 전에 실행한 조회(관리자 검증)가 MySQL REPEATABLE READ 트랜잭션의 첫 SELECT라 consistent-read 스냅샷을 그 시점에 고정해버린다. 그래서 락 안에서 "누가 활성 상태인지" 판단하는 조회는 일반 SELECT가 아니라 `FOR UPDATE`(`findByIdForActivation`/`findByActiveMarkerForActivation`)로 한다 — 그래야 락을 기다리는 동안 다른 트랜잭션이 커밋한 최신 활성화 상태를 놓치지 않는다. 일반 SELECT를 썼다면 오래된 스냅샷 기준으로 "아무도 활성 아님"을 보고 이미 활성인 다른 템플릿을 비활성화하지 않은 채 지나쳐 UNIQUE 제약을 위반했을 것이다.

## POST /api/admin/creator-space-templates/profile-images

## POST /api/admin/creator-space-templates/banner-images

템플릿의 기본 프로필 이미지·기본 배너를 파일로 올린다(이슈 #593). `multipart/form-data`의 `image` part에 실제 JPEG/PNG 5MB 이하 이미지를 보낸다. 프로필은 최소 200×200px, 배너는 최소 600×200px이고 가로세로 비율은 강제하지 않는다. 공통 정규화 후 긴 변 2048px 이하의 JPEG로 저장하며, 정책은 [Creator Space 프로필 사진·배너 업로드](space-api.md#post-apicreatorspaceprofile-images)와 같다. 응답 `201`.

```json
{ "imageUrl": "https://cdn.cking.co.kr/creator-space-profile-images/template/banner/uuid.jpg" }
```

- 응답은 key가 아니라 공개 CDN URL이다. 이 API는 템플릿에 이미지를 **연결하지 않는다.** 관리자는 응답의 `imageUrl`을 생성·수정 API의 `profileImageUrl`·`bannerImageUrl`에 넣어 저장한다. 템플릿 생성·수정·활성화 계약과 DB는 바뀌지 않으며, URL을 직접 입력하는 방식도 그대로 쓸 수 있다.
- 저장 위치는 Creator Space 이미지와 같은 `creator-space-profile-images/` 접두사 아래 `template/profile/`·`template/banner/`다. 같은 CDN 경로를 쓰고, Creator의 숫자 `creatorId` 하위 경로와 겹치지 않는다.
- Creator 소유 기록(`creator_space_profile_image`)은 남기지 않으므로 Creator 이미지 정리 작업이 이 객체를 지우지 않는다. 대신 업로드 기록(`creator_space_template_image`: key, 종류, 상태, 생성 시각)을 저장소에 올리기 전에 남기고, 올린 뒤 완료로 표시한다. 올리다 실패한 기록은 정리 대상으로 남는다.
- 템플릿 이미지 정리 작업(`CreatorSpaceTemplateImageCleanupScheduler`, 기본 10분 간격, `cking.creator.space-template-image.cleanup-interval-ms`)이 **올린 지 24시간이 지났고 어떤 템플릿·Space의 URL에도 들어 있지 않은** 업로드만 삭제 대기로 선점하고, 삭제 직전에 참조를 다시 확인한 뒤 객체를 지운다. 참조 여부는 템플릿의 `profileImageUrl`·`bannerImageUrl`과 Space의 `profileImageUrl`·`defaultProfileImageUrl`·`bannerImageUrl`·`defaultBannerImageUrl`에 업로드 key가 포함되는지로 본다(CDN 주소가 바뀌어도 판단이 유지되도록 URL 전체 비교가 아니다).
- 템플릿 값은 Creator 승인 시점에 Space로 URL이 복사되어 기본 이미지·기본 배너로 쓰이므로, 템플릿에서 빠졌어도 Space가 쓰는 이미지는 지우지 않는다. 더는 어디에서도 참조되지 않게 되면 다음 정리 때 삭제된다. 올린 뒤 24시간 안에 템플릿에 저장하지 않으면 삭제될 수 있다. 정리 작업이 도입되기 전(업로드 기록이 없는 시기)에 올린 이미지는 정리 대상이 아니다.
- **삭제와 템플릿 저장의 경합**: 저장소 객체 삭제는 DB 트랜잭션에 묶을 수 없어서, "참조를 확인한 뒤 삭제"만으로는 그 사이에 같은 URL을 템플릿에 저장하는 요청을 막지 못한다. 그래서 상태 전이로 직렬화한다. 업로드 기록의 상태는 `UPLOADING → UPLOADED → DELETE_PENDING → DELETING → DELETED`이고, 정리 작업은 저장소 객체를 지우기 전에 `DELETE_PENDING → DELETING`을 조건부 UPDATE로 먼저 확정한다. 템플릿 생성·수정은 같은 트랜잭션에서 저장하려는 URL에 든 템플릿 이미지를 `UPLOADED`로 고정(pin)한다. 이 UPDATE가 잡은 행 잠금이 저장 트랜잭션이 끝날 때까지 유지되므로 둘 중 하나만 성공한다: 고정이 먼저면 삭제 확정이 0건이라 정리 작업이 저장소를 지우지 않고, 삭제 확정이 먼저면 템플릿 저장이 거절된다.
- 정리 작업이 이미 삭제를 확정(`DELETING`)했거나 지운(`DELETED`) 이미지의 URL을 템플릿에 저장하려 하면 `INVALID_SPACE_TEMPLATE_IMAGE`(400)로 거절한다. 지운 뒤에도 기록을 `DELETED`로 남기는 것은 나중에 옛 URL을 다시 붙여 넣는 저장을 거절하기 위해서다. 업로드 기록이 없는 URL(외부 URL, 정리 작업 도입 이전 이미지)은 추적하지 않으므로 그대로 저장된다. 삭제를 확정한 뒤 저장소 삭제가 실패하면 다음 주기에 삭제만 재시도한다.
- **한계**: 템플릿 값을 Space로 복사하는 Creator 승인 경로는 이 고정을 하지 않는다. 템플릿에서 이미지를 빼는 요청, 그 이미지를 읽는 Creator 승인, 정리 작업의 삭제 확정이 같은 순간에 겹치고 이미지가 24시간 이상 된 경우에만 Space가 삭제된 이미지를 가리킬 수 있다.
- 관리자만 호출할 수 있고, 이미지 처리 요청이 몰리면 `IMAGE_PROCESSING_BUSY`(503)다.

## POST /api/admin/creator-space-templates

```json
{
  "introText": "크리에이터와 함께하는 공간이에요",
  "profileImageUrl": "https://cdn.cking.co.kr/default/profile.png",
  "bannerImageUrl": "https://cdn.cking.co.kr/default/banner.png",
  "slugRule": "creator-{creatorId}"
}
```

성공은 201이며 생성된 템플릿을 반환한다. 새로 만든 템플릿은 항상 비활성 상태(`active: false`)다.

```json
{
  "templateId": 1,
  "introText": "크리에이터와 함께하는 공간이에요",
  "profileImageUrl": "https://cdn.cking.co.kr/default/profile.png",
  "bannerImageUrl": "https://cdn.cking.co.kr/default/banner.png",
  "slugRule": "creator-{creatorId}",
  "active": false,
  "createdBy": 1,
  "updatedBy": 1,
  "createdAt": "2026-09-23T00:30:00Z",
  "updatedAt": "2026-09-23T00:30:00Z"
}
```

## GET /api/admin/creator-space-templates

Query: `page`, `size`. 기본 정렬은 `createdAt DESC, templateId DESC`다.

```json
{
  "items": [{ "templateId": 1, "introText": "...", "active": false, "...": "..." }],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1,
  "hasNext": false
}
```

## GET /api/admin/creator-space-templates/{templateId}

Query parameter는 없다. 응답은 생성 API와 같은 형식이다.

## PATCH /api/admin/creator-space-templates/{templateId}

요청 본문은 생성 API와 동일한 필드를 모두 포함하며, 전체 필드를 새 값으로 교체한다(부분 patch 아님). 활성 상태와 관계없이 수정할 수 있다. 성공 응답은 생성 API와 같은 형식이다.

## POST /api/admin/creator-space-templates/{templateId}/activate

대상 템플릿을 활성화한다. 이미 활성인 템플릿을 다시 활성화하면 상태 변화 없이 그대로 반환한다. 성공 응답은 생성 API와 같은 형식이며 `active: true`다.

## 오류

- 없는 Member 또는 템플릿은 `RESOURCE_NOT_FOUND`다.
- 관리자가 아닌 호출자는 `FORBIDDEN`이다.
- 유효하지 않은 요청 필드(빈 문자열, 길이 초과, 누락, `slugRule`이 위 형식이 아님 — `{creatorId}`가 없거나 두 번 이상, 맨 끝이 아님, 바로 앞이 숫자, 대문자·밑줄 등 허용되지 않은 문자)는 `VALIDATION_FAILED`다.
- 동시 활성화 요청 경합은 `CONCURRENT_COMMAND`다.
- 템플릿 생성·수정에서 `profileImageUrl`·`bannerImageUrl`이 이미 정리되었거나 정리가 확정된 템플릿 이미지를 가리키면 `INVALID_SPACE_TEMPLATE_IMAGE`(400)다. 이미지를 다시 올려 새 URL로 저장한다.
- 이미지 업로드에서 `image` part가 없으면 `VALIDATION_FAILED`(400), 파일이 이미지 형식·크기·해상도 규칙을 어기면 `INVALID_SPACE_TEMPLATE_IMAGE`(400), 파일 5MB 또는 요청 6MB를 넘으면 `UPLOAD_TOO_LARGE`(413)다.
