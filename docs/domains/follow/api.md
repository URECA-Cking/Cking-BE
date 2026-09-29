# Follow API

모든 성공·실패 응답은 [공통 API 규약](../../common/api.md)의 응답 봉투를 사용하며, 아래 Response 예시는 `data` 값이다.

## 인증

- 모든 API는 Bearer Access JWT가 필수다. 호출자는 `@CurrentMemberId`로 식별하며 `userId`를 받지 않는다. 토큰이 없으면 `UNAUTHORIZED`(401)다.
- 존재하지 않는 Creator는 공통 `RESOURCE_NOT_FOUND`(404)로 응답한다.
- `creatorId`가 양수가 아니면 `VALIDATION_FAILED`(400)다.

## PUT /api/creators/{creatorId}/follow

크리에이터를 팔로우한다. 멱등하며, 이미 팔로우 중이어도 성공한다.

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
