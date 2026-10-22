# Follow API

모든 성공·실패 응답은 [공통 API 규약](../../common/api.md)의 응답 봉투를 사용하며, 아래 Response 예시는 `data` 값이다.

## 인증

- 모든 API는 Bearer Access JWT가 필수다. 호출자는 `@CurrentMemberId`로 식별하며 `userId`를 받지 않는다. 토큰이 없으면 `UNAUTHORIZED`(401)다.
- 존재하지 않는 Creator는 공통 `RESOURCE_NOT_FOUND`(404)로 응답한다.
- `creatorId`가 양수가 아니면 `VALIDATION_FAILED`(400)다.

## PUT /api/creators/{creatorId}/follow

크리에이터를 팔로우한다. 멱등하며, 이미 팔로우 중이어도 성공한다.
실제 신규 팔로우 커밋 이후 동일 회원/Creator의 직전 24시간 마지막 추천 클릭에 전환을 귀속한다.
클릭 없음/기간 밖/반복 PUT은 전환을 생성하지 않으며, 재팔로우도 동일 추천 요청/Creator에는 최대 1건이다.
신규 관계와 영속 이벤트 원본을 함께 커밋한다. 원본 저장 실패는 팔로우도 롤백한다.
전환 분석은 별도 비동기 트랜잭션으로 실패를 격리하며, 알림/큐/전환 저장 실패 시 보존 중인 원본을 주기적으로 재처리한다.
정확한 귀속·실패·재처리 계약은 [추천 행동 수집](../creator/recommendation-events.md)과 [운영 문서](../../operations/creator-recommendation-events.md)를 따른다.

```json
{
  "creatorId": 1,
  "following": true
}
```

| 오류 코드 | HTTP | 조건 |
| --- | --- | --- |
| `SELF_FOLLOW_NOT_ALLOWED` | 400 | 본인 Creator를 팔로우 |
| `RESOURCE_NOT_FOUND` | 404 | Creator 없음 |

## DELETE /api/creators/{creatorId}/follow

크리에이터를 언팔로우한다. 멱등하며, 팔로우하지 않은 상태여도 성공한다.

```json
{
  "creatorId": 1,
  "following": false
}
```

| 오류 코드 | HTTP | 조건 |
| --- | --- | --- |
| `RESOURCE_NOT_FOUND` | 404 | Creator 없음 |

## GET /api/creators/{creatorId}/follow

호출자가 크리에이터를 팔로우 중인지 조회한다. Response 형식은 팔로우 API와 같다.

| 오류 코드 | HTTP | 조건 |
| --- | --- | --- |
| `RESOURCE_NOT_FOUND` | 404 | Creator 없음 |

## GET /api/me/follows

내가 팔로우한 크리에이터를 최근 팔로우 순(`followedAt` 내림차순, tie-breaker 팔로우 ID 내림차순)으로 조회한다. `page`(기본 0), `size`(기본 20, 최대 100)는 공통 페이지네이션을 따른다.

`creatorName`은 Creator의 현재 공개 활동명이며 OAuth 원본 이름을 노출하지 않는다.

```json
{
  "items": [
    {
      "creatorId": 1,
      "creatorName": "크리에이터",
      "followedAt": "2026-09-29T01:00:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1,
  "hasNext": false
}
```

## 팔로우 기반 Creator 추천

`GET /api/me/creator-recommendations`는 이 도메인의 팔로우 목록을 seed로 사용하지만, 집계 정책과 응답
계약은 [Creator API](../creator/api.md#get-apimecreator-recommendations)를 따른다. 팔로우 관계는 읽기만
하며 추천 요청 중 추가·삭제하지 않는다.

## PUT /api/creators/{creatorId}/posts/read

- Bearer Access JWT 필수. `creatorId`는 양수인 Creator ID이며 요청 본문과 호출자 `userId`는 없다.
- 현재 팔로우 중인 Creator의 스페이스 또는 글을 실제로 열었을 때 호출한다. 존재하지 않는 Creator는 `RESOURCE_NOT_FOUND`(404), 팔로우하지 않은 Creator는 `FOLLOW_REQUIRED`(403)이다. 자기 Creator도 팔로우할 수 없으므로 읽음 기록 대상이 아니다.
- 서버 DB UTC 시각으로 회원·Creator의 `lastReadAt`을 기록한다. 저장 값은 이전 값보다 작아지지 않는다. 반복 호출은 행을 늘리지 않고 읽음 상태를 유지하거나 시각을 앞으로만 옮긴다. 클라이언트 시각·요청 순서는 사용하지 않는다.
- 언팔로우하면 읽음 기록도 삭제된다. 재팔로우하면 아래 미기록 기준을 다시 적용한다.

```json
{
  "code": "SUCCESS",
  "data": { "creatorId": 1, "lastReadAt": "2026-10-20T05:30:00Z" },
  "message": null
}
```

| 오류 코드 | HTTP | 조건 |
| --- | --- | --- |
| `UNAUTHORIZED` | 401 | Access JWT 없음·무효 |
| `VALIDATION_FAILED` | 400 | `creatorId`가 양수가 아님 |
| `RESOURCE_NOT_FOUND` | 404 | Creator 없음 |
| `FOLLOW_REQUIRED` | 403 | 현재 팔로우하지 않음 |

## GET /api/me/follows/post-news

- Bearer Access JWT 필수. 요청 파라미터와 본문, 호출자 `userId`는 없다.
- 현재 팔로우 중인 모든 Creator를 한 번에 반환한다. `items`는 `creatorId` 오름차순이며 팔로우가 없으면 빈 배열이다.
- `latestPostCreatedAt`은 현재 회원에게 본문을 공개할 수 있는 게시글의 최신 작성 시각이며, 글이 없으면 `null`이다. 현재 공개 범위에서 `PUBLIC`과 `FOLLOWERS`는 팔로워에게 보인다. 삭제된 글은 DB에 없으며 비공개·권한 밖 글은 제외한다.
- `hasUnreadPosts`는 볼 수 있는 글 가운데 `createdAt > 기준 시각`인 글이 있으면 `true`다. `lastReadAt`이 없으면 **현재 팔로우의 시작 시각(`creator_follow.created_at`)**을 기준으로 한다. 배포 전 글이나 재팔로우 이전 글이 일괄 NEW가 되지 않는다. 같은 시각은 읽은 것으로 취급한다.
- 자기 Creator는 팔로우할 수 없으므로 결과에 나타나지 않으며, 자기 글로 본인에게 NEW를 표시하지 않는다.
- 조회는 팔로우·게시글 인덱스를 이용한 단일 DB 질의다. 팔로우 수만큼 HTTP 요청하지 않는다.

```json
{
  "code": "SUCCESS",
  "data": {
    "items": [
      { "creatorId": 1, "hasUnreadPosts": true, "latestPostCreatedAt": "2026-10-20T05:20:00Z" },
      { "creatorId": 2, "hasUnreadPosts": false, "latestPostCreatedAt": null }
    ]
  },
  "message": null
}
```

| 오류 코드 | HTTP | 조건 |
| --- | --- | --- |
| `UNAUTHORIZED` | 401 | Access JWT 없음·무효 |
