# Post Comment API

모든 성공·실패 응답은 [공통 API 규약](../../common/api.md)의 응답 봉투를 사용하며, 아래 Response 예시는 `data` 값이다. 권한 규칙은 [Post](README.md#댓글-권한)를 따른다.

## 인증

- 작성·수정·삭제·신고는 Bearer Access JWT가 필수다(없으면 `UNAUTHORIZED` 401). 호출자는 `@CurrentMemberId`로 식별한다.
- 목록 조회는 인증이 필요 없다. 로그인했다면 Access JWT를 함께 보내야 팔로워 공개 게시글의 댓글을 볼 수 있다.
- 경로의 `creatorId`·`postId`·`commentId`가 서로 맞지 않으면(다른 Creator의 게시글, 다른 게시글의 댓글) `RESOURCE_NOT_FOUND`(404)다.

## 댓글 응답

| 필드 | 설명 |
| --- | --- |
| `commentId`, `postId` | 댓글·게시글 ID |
| `authorMemberId`, `authorName` | 작성자 |
| `writtenByCreator` | 게시글을 작성한 Creator 본인이 단 댓글이면 true |
| `content` | 본문 (1~500자). **필터링된 댓글(`filtered`)이면 `null`** |
| `filtered` | 필터가 차단(BLOCK)했거나 관리자가 숨겨 원문을 가린 댓글이면 true. **작성자 본인에게는 항상 false**다 |
| `revealable` | `filtered`일 때 원문 보기를 누를 수 있는지. 개인정보로 막힌 댓글과 관리자가 숨긴 댓글은 false다. `filtered`가 false면 항상 false다 |
| `createdAt`, `updatedAt` | UTC Instant |

## 필터링

댓글은 저장한 뒤 별도 필터 서비스가 비동기로 판정한다(이슈 #460). 판정 상태와 결과는 `creator_post_comment`의 `filter_*` 컬럼에 저장하며, 판정 사유(`filter_reasons`)는 어떤 응답에도 담지 않는다.

- 판정받은 적 없는 댓글(`PENDING`·`FAILED`)과 통과(`PASS`)한 댓글은 평소처럼 원문을 내려준다. 가려지는 기준은 가장 최근 판정이 `BLOCK`인지다.
- 차단(`BLOCK`)된 댓글은 **작성자 본인이 아닌 조회자**(비로그인, 다른 사용자, 게시글 작성 Creator 포함)에게 `content`를 비우고 `filtered: true`로 내려준다. 원문은 아래 `/original` 요청으로만 받는다.
- 작성자 본인에게는 필터링 여부를 알리지 않고 원문을 그대로 내려준다(`filtered: false`).
- 개인정보 규칙(`privacy:*`)으로 막힌 댓글은 `revealable: false`이며 누구에게도 원문을 내려주지 않는다.
- **관리자가 숨긴 댓글**(이슈 #499)은 필터 판정과 별개로 `admin_hidden_at`에 저장한다. 작성자 본인이 아닌 조회자에게 `content`를 비우고 `filtered: true`, `revealable: false`로 내려주며 `/original`도 `COMMENT_NOT_REVEALABLE`(403)이다. 작성자 본인에게는 가려졌는지 알리지 않고 원문을 그대로 내려준다. 숨김은 필터 재판정이나 댓글 본문 수정으로 풀리지 않고, 되돌리는 API도 없다.
- 댓글 본문을 수정하면 다시 판정한다. 판정이 끝날 때까지(또는 재판정이 실패해도) 이전 판정이 `BLOCK`이면 계속 가려지고, 이전 판정이 `PASS`이거나 판정받은 적이 없으면 원문으로 보인다. 재판정 결과가 이전 판정을 덮어쓴다.
- `cking.comment-filter.enabled`가 꺼져 있으면(기본값) 새 판정을 하지 않는다. 판정받은 적 없는 댓글은 원문으로 보이고, 필터가 켜져 있을 때 이미 `BLOCK`된 댓글은 필터를 꺼도 계속 가려진다(수정한 댓글의 이전 `BLOCK`도 마찬가지다).

```json
{
  "commentId": 500,
  "postId": 100,
  "authorMemberId": 7,
  "authorName": "팬",
  "writtenByCreator": false,
  "content": "응원해요",
  "filtered": false,
  "revealable": false,
  "createdAt": "2026-09-29T01:00:00Z",
  "updatedAt": "2026-09-29T01:00:00Z"
}
```

필터링되어 원문이 가려진 댓글은 다음과 같다.

```json
{
  "commentId": 501,
  "postId": 100,
  "authorMemberId": 8,
  "authorName": "팬2",
  "writtenByCreator": false,
  "content": null,
  "filtered": true,
  "revealable": true,
  "createdAt": "2026-09-29T01:05:00Z",
  "updatedAt": "2026-09-29T01:05:00Z"
}
```

## GET /api/creators/{creatorId}/posts/{postId}/comments

댓글을 작성 순(`createdAt` 오름차순, tie-breaker `commentId` 오름차순)으로 조회한다. `page`(기본 0), `size`(기본 20, 최대 100)는 공통 페이지네이션을 따른다.

| 오류 코드 | HTTP | 조건 |
| --- | --- | --- |
| `POST_FOLLOWERS_ONLY` | 403 | 팔로워 공개 게시글이고 팔로워·작성 Creator 본인이 아님 (비로그인 포함) |
| `RESOURCE_NOT_FOUND` | 404 | Creator 또는 게시글 없음 |

## GET /api/creators/{creatorId}/posts/{postId}/comments/{commentId}/original

필터링되어 목록에서 원문이 가려진 댓글의 원문을 돌려준다. 게시글을 볼 수 있는 사람이면 누구나 요청할 수 있고 로그인은 필요 없다(목록 조회와 같은 규칙). 응답은 `{ "commentId": 501, "content": "..." }`.

| 오류 코드 | HTTP | 조건 |
| --- | --- | --- |
| `POST_FOLLOWERS_ONLY` | 403 | 볼 수 없는 팔로워 공개 게시글 |
| `COMMENT_NOT_REVEALABLE` | 403 | 개인정보 규칙으로 막힌 댓글 |
| `RESOURCE_NOT_FOUND` | 404 | 댓글·게시글 없음, 다른 게시글의 댓글, **필터링되지 않은 댓글**, **조회자가 작성자 본인인 댓글**(본인에게는 필터링된 댓글로 보이지 않는다) |

## POST /api/creators/{creatorId}/posts/{postId}/comments

댓글을 작성한다. 응답 `201`과 댓글.

```json
{ "content": "응원해요" }
```

| 오류 코드 | HTTP | 조건 |
| --- | --- | --- |
| `VALIDATION_FAILED` | 400 | 본문이 비었거나 500자 초과 |
| `POST_FOLLOWERS_ONLY` | 403 | 볼 수 없는 팔로워 공개 게시글 |
| `COMMENT_FOLLOWERS_ONLY` | 403 | 팔로워도 작성 Creator 본인도 아님 (전체 공개 게시글 포함) |
| `RESOURCE_NOT_FOUND` | 404 | Member·Creator·게시글 없음 |

## PATCH /api/creators/{creatorId}/posts/{postId}/comments/{commentId}

본인 댓글의 본문을 수정한다. 요청 형식은 작성과 같다. 응답은 댓글.

| 오류 코드 | HTTP | 조건 |
| --- | --- | --- |
| `VALIDATION_FAILED` | 400 | 본문이 비었거나 500자 초과 |
| `POST_FOLLOWERS_ONLY` | 403 | 볼 수 없는 팔로워 공개 게시글 |
| `FORBIDDEN` | 403 | 다른 사람의 댓글 (게시글 작성 Creator 포함) |
| `COMMENT_FOLLOWERS_ONLY` | 403 | 팔로우를 끊은 작성자 |
| `RESOURCE_NOT_FOUND` | 404 | 댓글·게시글 없음 |

## DELETE /api/creators/{creatorId}/posts/{postId}/comments/{commentId}

댓글을 삭제한다. 댓글 작성자 본인과 게시글 작성 Creator 본인만 가능하며, 공개 범위·팔로우 여부는 보지 않는다. 응답 `204`.

| 오류 코드 | HTTP | 조건 |
| --- | --- | --- |
| `FORBIDDEN` | 403 | 작성자도 게시글 작성 Creator도 아님 |
| `RESOURCE_NOT_FOUND` | 404 | 댓글·게시글 없음 |

## POST /api/creators/{creatorId}/posts/{postId}/comments/{commentId}/reports

댓글을 신고한다(이슈 #485). 게시글을 볼 수 있는 로그인 사용자가 할 수 있으며, 전체 공개 게시글은 팔로우하지 않아도 신고할 수 있다. 본인이 쓴 댓글은 신고할 수 없다. 신고는 댓글의 노출 상태와 필터 판정을 바꾸지 않는다. 응답 `201`과 접수된 신고(신고자 정보는 담지 않는다).

```json
{ "reason": "OTHER", "detail": "위협으로 느껴집니다" }
```

| 필드 | 설명 |
| --- | --- |
| `reason` | 필수. `ABUSE`(욕설·혐오), `SPAM`(스팸·홍보), `PRIVACY`(개인정보 노출), `OTHER`(기타) |
| `detail` | `reason`이 `OTHER`일 때만 필수이고 1~200자다(앞뒤 공백은 제거). 그 밖의 사유에서는 보내지 않는다 |

```json
{ "reportId": 900, "commentId": 500, "reason": "OTHER", "createdAt": "2026-10-07T00:00:00Z" }
```

- 같은 사용자가 같은 댓글을 다시 신고하면 새로 저장하지 않고 **처음 접수된 신고를 그대로** 돌려준다(요청한 `reason`이 달라도 기존 값이다). 이때도 `201`이다.
- 같은 신고자가 1시간 안에 새로 접수할 수 있는 신고는 20건이다. 넘으면 `COMMENT_REPORT_LIMIT_EXCEEDED`(429)다. 이미 신고한 댓글의 재신고는 한도에 걸리지 않는다.
- 댓글(게시글 삭제 포함)이 삭제되면 그 신고도 함께 삭제된다.
- 접수된 신고는 처리 상태 `PENDING`으로 시작한다. 관리자가 [숨김·반려](#post-apiadmincomment-reportscommentidhide)로 처리한다(이슈 #499).
- 이미 가려진 댓글(필터가 가장 최근에 차단(BLOCK)했거나 관리자가 숨김)에는 **새 신고를 받지 않고** `COMMENT_REPORT_ALREADY_FILTERED`(409)다. 가려지기 전에 같은 신고자가 접수한 신고를 다시 보내면 기존 신고를 그대로 돌려준다.

| 오류 코드 | HTTP | 조건 |
| --- | --- | --- |
| `VALIDATION_FAILED` | 400 | `reason` 누락·알 수 없는 값, `OTHER`인데 `detail`이 없거나 200자 초과, `OTHER`가 아닌데 `detail`이 있음 |
| `UNAUTHORIZED` | 401 | Access JWT 없음 |
| `POST_FOLLOWERS_ONLY` | 403 | 볼 수 없는 팔로워 공개 게시글 |
| `COMMENT_REPORT_OWN_COMMENT` | 403 | 본인이 쓴 댓글 |
| `RESOURCE_NOT_FOUND` | 404 | Member·Creator·게시글·댓글 없음, 다른 게시글의 댓글 |
| `COMMENT_REPORT_ALREADY_FILTERED` | 409 | 이미 필터가 차단했거나 관리자가 숨긴 댓글(새 신고) |
| `COMMENT_REPORT_LIMIT_EXCEEDED` | 429 | 신고자별 반복 신고 한도 초과 |

## GET /api/admin/comment-reports

관리자가 신고된 댓글을 댓글별로 모아 조회한다. 가장 최근에 신고된 순서(`latestReportedAt` 내림차순, tie-breaker `commentId` 내림차순)이고 `page`(기본 0), `size`(기본 20, 최대 100)는 공통 페이지네이션을 따른다. Bearer Access JWT와 `ADMIN` 역할이 필수다. 신고자 정보는 담지 않는다.

`status`(선택)로 댓글을 처리 상태별로 거른다(이슈 #499). 생략하면 모든 신고된 댓글이다. 알 수 없는 값은 `VALIDATION_FAILED`(400)다.

| `status` | 거르는 댓글 |
| --- | --- |
| `PENDING` | 접수(처리 전) 신고가 하나라도 남은 댓글 |
| `RESOLVED` | 접수 신고가 모두 처리됐고 관리자가 숨긴 댓글 |
| `REJECTED` | 접수 신고가 모두 처리됐고 숨기지 않은 댓글(반려) |

```json
{
  "items": [
    {
      "commentId": 500,
      "postId": 100,
      "creatorId": 1,
      "authorMemberId": 7,
      "content": "신고된 댓글",
      "blocked": false,
      "hiddenByAdmin": false,
      "status": "PENDING",
      "reportCount": 3,
      "pendingReportCount": 2,
      "reasonCounts": { "ABUSE": 2, "SPAM": 1 },
      "latestReportedAt": "2026-10-07T00:00:00Z"
    }
  ],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1, "hasNext": false
}
```

- `content`는 필터 차단 여부와 관계없이 원문이다(관리자 전용). `blocked`는 필터가 가장 최근에 차단(BLOCK)한 댓글이면 true다.
- `reasonCounts`에는 신고가 있는 사유만 담는다. 처리 완료·반려된 신고도 센다.
- `hiddenByAdmin`은 관리자가 숨긴 댓글이면 true다. `blocked`(필터 차단)와 별개다.
- 댓글 단위 `status`는 `pendingReportCount`가 0보다 크면 `PENDING`, 0이면 숨겼는지에 따라 `RESOLVED`(숨김)와 `REJECTED`(반려)다. `reportCount`는 처리 여부와 관계없는 전체 신고 수다.

| 오류 코드 | HTTP | 조건 |
| --- | --- | --- |
| `VALIDATION_FAILED` | 400 | `page`·`size` 범위 오류, 알 수 없는 `status` |
| `UNAUTHORIZED` | 401 | Access JWT 없음 |
| `FORBIDDEN` | 403 | ADMIN이 아님 |

## POST /api/admin/comment-reports/{commentId}/hide

관리자가 신고된 댓글을 숨기고 그 댓글의 **접수(PENDING) 신고를 모두 `RESOLVED`로** 바꾼다(이슈 #499). Bearer Access JWT와 `ADMIN` 역할이 필수이고 요청 본문은 없다. 응답 `200`.

```json
{ "commentId": 500, "status": "RESOLVED", "handledReportCount": 3 }
```

- 숨김은 필터 판정과 별개로 저장하며(`admin_hidden_at`, `admin_hidden_by`) 되돌리지 않는다. 숨긴 댓글은 사용자 목록에서 가려지고 원문 보기도 허용하지 않는다([필터링](#필터링)).
- `handledReportCount`는 이번 요청으로 처리한 접수 신고 수다. **이미 숨긴 댓글을 다시 숨기면 아무것도 바꾸지 않고 성공**하며(멱등) 0이다.
- 신고는 있지만 접수 신고가 모두 이미 반려된 댓글(숨기지도 않음)은 서로 모순되는 조치라 `COMMENT_REPORT_ALREADY_HANDLED`(409)다. 반려 뒤 새 신고가 접수되면(숨기기 전까지는 새 신고를 받는다) 다시 숨길 수 있다.
- 잠금 순서는 게시글 공유 잠금 → 댓글 쓰기 잠금이며 같은 댓글에 동시에 들어오는 신고 접수와 직렬화된다. 숨김이 끝난 뒤에는 새 신고를 받지 않으므로 숨긴 댓글에 접수 신고가 남지 않는다.

| 오류 코드 | HTTP | 조건 |
| --- | --- | --- |
| `UNAUTHORIZED` | 401 | Access JWT 없음 |
| `FORBIDDEN` | 403 | ADMIN이 아님 |
| `RESOURCE_NOT_FOUND` | 404 | 없는 댓글, 신고가 하나도 없는 댓글 |
| `COMMENT_REPORT_ALREADY_HANDLED` | 409 | 접수 신고가 모두 반려된 댓글을 숨기려 함 |

## POST /api/admin/comment-reports/{commentId}/reject

관리자가 신고를 받아들이지 않고 그 댓글의 **접수(PENDING) 신고를 모두 `REJECTED`로** 바꾼다(이슈 #499). 댓글은 그대로 보인다. Bearer Access JWT와 `ADMIN` 역할이 필수이고 요청 본문은 없다. 응답 `200`.

```json
{ "commentId": 500, "status": "REJECTED", "handledReportCount": 2 }
```

- **접수 신고가 모두 이미 반려된 댓글을 다시 반려하면 아무것도 바꾸지 않고 성공**하며(멱등) `handledReportCount`는 0이다.
- 이미 숨긴 댓글은 서로 모순되는 조치라 `COMMENT_REPORT_ALREADY_HANDLED`(409)다.
- 반려한 뒤 같은 댓글에 새로 접수된 신고는 다시 `PENDING`이 되고, 이전에 반려한 신고는 그대로 둔다.

| 오류 코드 | HTTP | 조건 |
| --- | --- | --- |
| `UNAUTHORIZED` | 401 | Access JWT 없음 |
| `FORBIDDEN` | 403 | ADMIN이 아님 |
| `RESOURCE_NOT_FOUND` | 404 | 없는 댓글, 신고가 하나도 없는 댓글 |
| `COMMENT_REPORT_ALREADY_HANDLED` | 409 | 이미 숨긴 댓글 |
