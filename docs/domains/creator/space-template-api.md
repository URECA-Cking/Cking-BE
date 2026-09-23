# Creator Space Template 관리자 API

관리자가 관리하는 기본 크리에이터 스페이스 템플릿 API의 상세 계약이다. 모든 성공·실패 응답은 [공통 API 규약](../../common/api.md)의 응답 봉투를 사용하며, 아래 Response 예시는 `data` 값이다.

## 범위와 권한

- 모든 API는 Bearer Access JWT와 `ADMIN` 역할이 필수이며, Controller는 `@CurrentMemberId`를 기존 관리자 업무 식별자로 전달한다.
- 이 API는 템플릿 자체의 CRUD와 활성화만 다룬다. Creator 승인 시점에 활성 템플릿 값을 새 Creator 스페이스로 복사하는 동작은 후속 이슈에서 구현하며, 이 문서의 범위가 아니다.
- 활성 템플릿을 바꿔도 이미 생성된 Creator 스페이스는 변경되지 않는다. 활성 템플릿은 향후 스페이스 생성 시점에만 참조된다.
- 응답 시각은 UTC RFC 3339 형식(예: `2026-09-23T00:30:00Z`)이다.

## 템플릿 필드

| 필드 | 설명 |
| --- | --- |
| `introText` | 기본 소개 문구 (최대 500자) |
| `profileImageUrl` | 기본 프로필 이미지 URL (최대 500자) |
| `bannerImageUrl` | 기본 배너 이미지 URL (최대 500자) |
| `slugRule` | 공유 URL slug 생성 규칙 문자열 (최대 100자). 실제 slug는 Creator 스페이스 생성 시점(후속 이슈)에 이 규칙으로부터 만들어지며, 템플릿 자체는 규칙만 보관한다. |
| `homeTabEnabled`, `missionsTabEnabled`, `postsTabEnabled`, `eventsTabEnabled` | 새 스페이스에 노출할 홈·미션·게시물·이벤트 탭 여부 |

## 활성 템플릿 유일성

- 활성 템플릿은 항상 하나뿐이다. DB `creator_space_template.active_marker` UNIQUE 제약이 최종 안전망이며, 활성 템플릿만 이 컬럼에 `1`을 갖고 나머지는 `NULL`이다.
- 활성화 API는 대상 템플릿을 활성화하면서 기존에 활성이던 템플릿을 함께 비활성화한다. 두 동작은 하나의 트랜잭션으로 처리되며, 기존 템플릿의 비활성화를 먼저 flush한 뒤 새 템플릿을 활성화한다(순서를 지키지 않으면 Hibernate가 새 템플릿의 `active_marker=1` UPDATE를 먼저 내보내 UNIQUE 제약을 순간적으로 위반할 수 있다).
- 동시 활성화 요청은 advisory lock으로 직렬화된다. 락을 즉시 얻지 못한 요청은 `CONCURRENT_COMMAND`다. 락 key는 템플릿별이 아니라 전역이므로, 관리자 검증과 대상 템플릿 존재 여부 확인은 락을 잡기 전에 끝낸다 — 그렇지 않으면 없는 templateId·비관리자 같은 잘못된 요청이 락부터 잡아 무관한 다른 템플릿의 정상 활성화 요청까지 `CONCURRENT_COMMAND`로 거부될 수 있다.
- 락을 잡기 전에 실행한 조회(관리자 검증)가 MySQL REPEATABLE READ 트랜잭션의 첫 SELECT라 consistent-read 스냅샷을 그 시점에 고정해버린다. 그래서 락 안에서 "누가 활성 상태인지" 판단하는 조회는 일반 SELECT가 아니라 `FOR UPDATE`(`findByIdForActivation`/`findByActiveMarkerForActivation`)로 한다 — 그래야 락을 기다리는 동안 다른 트랜잭션이 커밋한 최신 활성화 상태를 놓치지 않는다. 일반 SELECT를 썼다면 오래된 스냅샷 기준으로 "아무도 활성 아님"을 보고 이미 활성인 다른 템플릿을 비활성화하지 않은 채 지나쳐 UNIQUE 제약을 위반했을 것이다.

## POST /api/admin/creator-space-templates

```json
{
  "introText": "크리에이터와 함께하는 공간이에요",
  "profileImageUrl": "https://cdn.cking.co.kr/default/profile.png",
  "bannerImageUrl": "https://cdn.cking.co.kr/default/banner.png",
  "slugRule": "creator-{creatorId}",
  "homeTabEnabled": true,
  "missionsTabEnabled": true,
  "postsTabEnabled": true,
  "eventsTabEnabled": true
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
  "homeTabEnabled": true,
  "missionsTabEnabled": true,
  "postsTabEnabled": true,
  "eventsTabEnabled": true,
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
- 유효하지 않은 요청 필드(빈 문자열, 길이 초과, 누락)는 `VALIDATION_FAILED`다.
- 동시 활성화 요청 경합은 `CONCURRENT_COMMAND`다.
