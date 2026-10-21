# Post API

모든 성공·실패 응답은 [공통 API 규약](../../common/api.md)의 응답 봉투를 사용하며, 아래 Response 예시는 `data` 값이다.

## 인증

- `/api/creator/posts/**`(작성·수정·삭제·이미지 업로드)는 Bearer Access JWT가 필수다. 호출자는 `@CurrentMemberId`로 식별한다. 없는 Member는 `RESOURCE_NOT_FOUND`(404), Creator가 아니면 `FORBIDDEN`(403)이다.
- Creator는 공개 활동명 설정을 마친 뒤에만 게시글·게시글 이미지를 작성하거나 게시글을 수정할 수 있다. 기존 이관 Creator가
  활동명을 설정하지 않았으면 `ACTIVITY_NAME_SETUP_REQUIRED`(409)를 반환한다. 기존 게시글 삭제는 활동명 설정 전에도
  허용해 기존 데이터를 정리할 수 있다.
- `GET /api/creators/{creatorId}/posts/**`(공개 조회)는 인증이 필요 없다. 로그인했다면 Access JWT를 함께 보내야 팔로워 공개 게시글을 볼 수 있다.

## 게시글 응답

| 필드 | 설명 |
| --- | --- |
| `postId`, `creatorId` | 게시글·작성 Creator ID |
| `visibility` | `PUBLIC` 또는 `FOLLOWERS` |
| `locked` | 팔로워 공개 게시글을 볼 권한이 없으면 true. 이때 `content`는 null, `images`는 빈 배열 |
| `content` | 본문 (최대 2000자, 이미지만 있는 게시글은 빈 문자열) |
| `imageCount` | 잠금 여부와 무관한 이미지 수 |
| `likeCount` | 게시글 좋아요 수. 잠긴 게시글도 포함한다 |
| `liked` | 조회자가 좋아요했는지. 비로그인은 false |
| `commentCount` | 댓글 수. 조회자에게 보이는 댓글만 센다. 필터 차단·관리자 숨김 댓글은 스페이스 주인 Creator는 모두 세고, 그 외 조회자는 본인이 쓴 것만 센다(이슈 #721, [댓글 목록](comment-api.md#필터링)과 같은 기준). 댓글은 하드 삭제라 삭제된 댓글은 세지 않는다 |
| `images[]` | 표시 순서대로 `imageKey`와 `url`(유효 시간 10분). 주소 발급에 실패한 이미지는 `url`이 null이며 다시 조회하면 된다 |
| `createdAt`, `updatedAt` | UTC Instant |

```json
{
  "postId": 100,
  "creatorId": 1,
  "visibility": "FOLLOWERS",
  "locked": false,
  "content": "오늘 촬영 비하인드",
  "imageCount": 1,
  "images": [
    { "imageKey": "post-images/1/0d5c…e2.jpg", "url": "https://…" }
  ],
  "likeCount": 12,
  "liked": true,
  "commentCount": 3,
  "createdAt": "2026-09-29T01:00:00Z",
  "updatedAt": "2026-09-29T01:00:00Z"
}
```

## POST /api/creator/posts/images

`multipart/form-data`의 `image` 파트로 이미지 1장(JPEG/PNG, 최대 5MB, 최소 200×200)을 올린다. 여러 장은 FE가 병렬로 호출한다. 응답 `201`.

```json
{ "imageKey": "post-images/1/0d5c…e2.jpg" }
```

24시간 안에 게시글에 연결되지 않은 이미지는 삭제된다.

| 오류 코드 | HTTP | 조건 |
| --- | --- | --- |
| `VALIDATION_FAILED` | 400 | `image` 파트 없음 |
| `INVALID_POST_IMAGE` | 400 | 지원하지 않거나 손상된 이미지, 크기·해상도 위반 |
| `UPLOAD_TOO_LARGE` | 413 | 파일 5MB 또는 요청 6MB 초과 |
| `IMAGE_PROCESSING_BUSY` | 503 | 이미지 처리 요청이 몰림. 잠시 후 다시 시도한다 |

## POST /api/creator/posts

게시글을 작성한다. 응답 `201`과 게시글.

```json
{
  "content": "오늘 촬영 비하인드",
  "visibility": "FOLLOWERS",
  "imageKeys": ["post-images/1/0d5c…e2.jpg"]
}
```

- `visibility`는 필수다. `content`(최대 2000자)나 `imageKeys` 중 하나는 있어야 한다.
- `imageKeys`는 최대 5개, 중복·빈 값 불가. 배열 순서가 표시 순서다.

| 오류 코드 | HTTP | 조건 |
| --- | --- | --- |
| `VALIDATION_FAILED` | 400 | 위 입력 규칙 위반 |
| `POST_IMAGE_UNAVAILABLE` | 400 | 본인이 올리지 않았거나, 이미 다른 게시글에 쓰였거나, 삭제 대상이 된 이미지 key 포함 |

## PATCH /api/creator/posts/{postId}

본인 게시글을 수정한다. 부분 수정이 아니라 요청 형식과 규칙이 작성과 같고 모든 필드를 교체한다. 기존 이미지를 유지하려면 그 key를 다시 넣는다. 목록에서 빠진 이미지는 삭제된다. 응답은 게시글.

| 오류 코드 | HTTP | 조건 |
| --- | --- | --- |
| `VALIDATION_FAILED` | 400 | 입력 규칙 위반 |
| `POST_IMAGE_UNAVAILABLE` | 400 | 새로 추가한 key를 쓸 수 없음 |
| `FORBIDDEN` | 403 | 다른 Creator의 게시글 |
| `RESOURCE_NOT_FOUND` | 404 | 게시글 없음 |

## DELETE /api/creator/posts/{postId}

본인 게시글을 삭제한다. 연결된 이미지도 삭제된다. 응답 `204`. 오류는 수정의 `FORBIDDEN`·`RESOURCE_NOT_FOUND`와 같다.

## GET /api/creators/{creatorId}/posts

게시글을 최신 작성 순(`createdAt` 내림차순, tie-breaker `postId` 내림차순)으로 조회한다. `page`(기본 0), `size`(기본 20, 최대 100)는 공통 페이지네이션을 따른다. 팔로워 공개 게시글은 권한이 없으면 잠금 상태로 포함한다. 없는 Creator는 `RESOURCE_NOT_FOUND`다.

## GET /api/creators/{creatorId}/posts/{postId}

게시글 상세를 조회한다.

| 오류 코드 | HTTP | 조건 |
| --- | --- | --- |
| `POST_FOLLOWERS_ONLY` | 403 | 팔로워 공개 게시글이고 팔로워·작성 Creator 본인이 아님 (비로그인 포함) |
| `RESOURCE_NOT_FOUND` | 404 | Creator 또는 게시글 없음, 다른 Creator의 게시글 |

## 댓글 공개 이름

일반 Member는 닉네임을 표시하고, Creator가 어느 Creator Space에 작성하든 Creator의 현재 공개 활동명·Space 사진을
표시한다. `writtenByCreator`는 게시글 소유 Creator 본인 댓글만 true다. 댓글은 이름을 snapshot하지 않으므로 활동명 변경 후
기존 Creator 댓글도 새 활동명으로 조회된다. 활동명 미설정 Creator는 댓글 작성·수정을 할 수 없다.

## 게시글 좋아요

`POST /api/creators/{creatorId}/posts/{postId}/likes`(좋아요), `DELETE` 같은 경로(취소). 둘 다 Bearer Access JWT가 필수이며 호출자는 `@CurrentMemberId`로 식별한다. 회원은 게시글마다 한 번만 좋아요할 수 있고(`creator_post_like`의 `(post_id, member_id)` PK), 둘 다 멱등하다.

- 좋아요: 이미 좋아요한 게시글에 다시 요청해도 성공한다. 팔로워 공개 게시글은 팔로워와 작성 Creator 본인만 좋아요할 수 있다. 작성 Creator 본인도 좋아요할 수 있지만 미션 보상은 받지 않는다.
- 취소: 좋아요하지 않은 게시글에 요청해도 성공한다. 공개 범위와 팔로우를 보지 않으므로 팔로우를 끊은 뒤에도 자기 좋아요를 취소할 수 있다. 이미 지급한 미션 응모권은 회수하지 않는다.
- 응답은 `200`이고 요청 뒤의 상태를 담는다.

```json
{
  "postId": 100,
  "liked": true,
  "likeCount": 13,
  "missionAwarded": true
}
```

- `missionAwarded`: 이번 좋아요로 해당 Creator의 LIKE 미션 응모권 적립이 새로 접수되면 true다. 취소 응답은 항상 false다. 보상 규칙은 [Mission API](../mission/api.md#게시글-좋아요-보상)를 따른다. 이전 날에 누른 좋아요를 유지한 채 다시 보내는 중복 요청은 새 좋아요가 아니므로 `missionAwarded`가 false다. 미션 보상이 실패하거나 대상이 아니어도 좋아요는 성공하며 `missionAwarded`만 false다.

| 오류 코드 | HTTP | 조건 |
| --- | --- | --- |
| `POST_FOLLOWERS_ONLY` | 403 | (좋아요) 팔로워 공개 게시글이고 팔로워·작성 Creator 본인이 아님 |
| `RESOURCE_NOT_FOUND` | 404 | Member·Creator·게시글 없음, 다른 Creator의 게시글 |
