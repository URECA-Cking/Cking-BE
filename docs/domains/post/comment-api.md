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
| `content` | 본문 (1~500자) |
| `createdAt`, `updatedAt` | UTC Instant |

```json
{
  "commentId": 500,
  "postId": 100,
  "authorMemberId": 7,
  "authorName": "팬",
  "writtenByCreator": false,
  "content": "응원해요",
  "createdAt": "2026-09-29T01:00:00Z",
  "updatedAt": "2026-09-29T01:00:00Z"
}
```

## GET /api/creators/{creatorId}/posts/{postId}/comments

댓글을 작성 순(`createdAt` 오름차순, tie-breaker `commentId` 오름차순)으로 조회한다. `page`(기본 0), `size`(기본 20, 최대 100)는 공통 페이지네이션을 따른다.

| 오류 코드 | HTTP | 조건 |
| --- | --- | --- |
| `POST_FOLLOWERS_ONLY` | 403 | 팔로워 공개 게시글이고 팔로워·작성 Creator 본인이 아님 (비로그인 포함) |
| `RESOURCE_NOT_FOUND` | 404 | Creator 또는 게시글 없음 |

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
