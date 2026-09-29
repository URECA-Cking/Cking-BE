# Post API

모든 성공·실패 응답은 [공통 API 규약](../../common/api.md)의 응답 봉투를 사용하며, 아래 Response 예시는 `data` 값이다.

## 인증

- `/api/creator/posts/**`(작성·수정·삭제·이미지 업로드)는 Bearer Access JWT가 필수다. 호출자는 `@CurrentMemberId`로 식별한다. 없는 Member는 `RESOURCE_NOT_FOUND`(404), Creator가 아니면 `FORBIDDEN`(403)이다.
- `GET /api/creators/{creatorId}/posts/**`(공개 조회)는 인증이 필요 없다. 로그인했다면 Access JWT를 함께 보내야 팔로워 공개 게시글을 볼 수 있다.

## 게시글 응답

| 필드 | 설명 |
| --- | --- |
| `postId`, `creatorId` | 게시글·작성 Creator ID |
| `visibility` | `PUBLIC` 또는 `FOLLOWERS` |
| `locked` | 팔로워 공개 게시글을 볼 권한이 없으면 true. 이때 `content`는 null, `images`는 빈 배열 |
| `content` | 본문 (최대 2000자, 이미지만 있는 게시글은 빈 문자열) |
| `imageCount` | 잠금 여부와 무관한 이미지 수 |
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
