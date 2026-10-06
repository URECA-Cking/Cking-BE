# Post Comment API

모든 성공·실패 응답은 [공통 API 규약](../../common/api.md)의 응답 봉투를 사용하며, 아래 Response 예시는 `data` 값이다. 권한 규칙은 [Post](README.md#댓글-권한)를 따른다.

## 인증

- 작성·수정·삭제는 Bearer Access JWT가 필수다(없으면 `UNAUTHORIZED` 401). 호출자는 `@CurrentMemberId`로 식별한다.
- 목록 조회는 인증이 필요 없다. 로그인했다면 Access JWT를 함께 보내야 팔로워 공개 게시글의 댓글을 볼 수 있다.
- 경로의 `creatorId`·`postId`·`commentId`가 서로 맞지 않으면(다른 Creator의 게시글, 다른 게시글의 댓글) `RESOURCE_NOT_FOUND`(404)다.

## 댓글 응답

| 필드 | 설명 |
| --- | --- |
| `commentId`, `postId` | 댓글·게시글 ID |
| `authorMemberId`, `authorName` | 작성자 |
| `writtenByCreator` | 게시글을 작성한 Creator 본인이 단 댓글이면 true |
| `content` | 본문 (1~500자). **필터링된 댓글(`filtered`)이면 `null`** |
| `filtered` | 필터가 차단(BLOCK)해 원문을 가린 댓글이면 true. **작성자 본인에게는 항상 false**다 |
| `revealable` | `filtered`일 때 원문 보기를 누를 수 있는지. 개인정보로 막힌 댓글은 false다. `filtered`가 false면 항상 false다 |
| `createdAt`, `updatedAt` | UTC Instant |

## 필터링

댓글은 저장한 뒤 별도 필터 서비스가 비동기로 판정한다(이슈 #460). 판정 상태와 결과는 `creator_post_comment`의 `filter_*` 컬럼에 저장하며, 판정 사유(`filter_reasons`)는 어떤 응답에도 담지 않는다.

- 판정 전(`PENDING`)이거나 필터 장애로 실패(`FAILED`)한 댓글, 통과(`PASS`)한 댓글은 평소처럼 원문을 내려준다.
- 차단(`BLOCK`)된 댓글은 **작성자 본인이 아닌 조회자**(비로그인, 다른 사용자, 게시글 작성 Creator 포함)에게 `content`를 비우고 `filtered: true`로 내려준다. 원문은 아래 `/original` 요청으로만 받는다.
- 작성자 본인에게는 필터링 여부를 알리지 않고 원문을 그대로 내려준다(`filtered: false`).
- 개인정보 규칙(`privacy:*`)으로 막힌 댓글은 `revealable: false`이며 누구에게도 원문을 내려주지 않는다.
- 댓글 본문을 수정하면 이전 판정을 지우고 다시 판정한다.
- `cking.comment-filter.enabled`가 꺼져 있으면(기본값) 판정하지 않으며 댓글은 모두 원문으로 보인다.

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
