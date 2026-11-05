# 관리자 필터 차단 댓글 API

필터가 자동으로 차단한 댓글과 그 사유를 관리자가 보는 API 계약이다(이슈 #809). 이미 차단된 댓글은 새 신고를 받지 않으므로, 신고 없이 차단된 댓글은 [신고 목록](comment-api.md#get-apiadmincomment-reports)에 나오지 않는다. 판정 흐름은 [Post](README.md#필터-판정-흐름)를 따른다.

## GET /api/admin/filtered-comments

가장 최근 판정이 `BLOCK`인 댓글을 신고 여부와 상관없이 조회한다. 최근 판정 순(`filteredAt` 내림차순, tie-breaker `commentId` 내림차순)이고 `page`(기본 0), `size`(기본 20, 최대 100)는 공통 페이지네이션을 따른다. Bearer Access JWT와 `ADMIN` 역할이 필수다.

```json
{
  "items": [
    {
      "commentId": 500,
      "postId": 100,
      "postPreview": "오늘 라이브 공지입니다",
      "creatorId": 1,
      "creatorName": "활동명",
      "authorMemberId": 7,
      "authorName": "닉네임",
      "content": "차단된 댓글",
      "filterReasons": ["spam:link"],
      "filterStatus": "DONE",
      "filteredAt": "2026-11-05T00:00:00Z"
    }
  ],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1, "hasNext": false
}
```

| 필드 | 설명 |
| --- | --- |
| `postPreview` | 게시글 본문 앞 50자. 게시글에 제목이 없어 대신 내려준다 |
| `creatorName` | 스페이스 Creator의 활동명. Creator를 찾지 못하면 `null` |
| `authorName` | Creator 계정의 댓글은 활동명, 그 밖의 회원은 닉네임이고 닉네임이 없으면 `닉네임 미설정`이다. 회원을 찾지 못하면 `null` |
| `content` | 차단 여부와 관계없는 현재 원문(관리자 전용) |
| `filterReasons` | 가장 최근 `BLOCK` 판정의 사유 |
| `filterStatus` | `DONE`, `PENDING`, `FAILED` |
| `filteredAt` | 가장 최근 판정 시각 |

- `filterStatus`가 `DONE`이 아니면 본문을 고쳐 재판정이 끝나지 않은 댓글이다. `content`는 새 본문이고 `filterReasons`와 `filteredAt`은 이전 본문의 판정이다.
- `filterReasons`는 저장값 그대로가 아니라 판정 로그와 같은 규칙으로 정리한 값이다. 유형(`classifier`, `model`, `profanity`, `privacy`, `spam`)이 허용 목록에 없으면 `invalid`이고, `유형:이름`의 이름은 영문 소문자와 밑줄뿐일 때만 남기고 아니면 유형만 남긴다.
- 사유는 어떤 규칙이나 모델이 막았는지를 나타내는 코드다. 댓글의 어느 단어가 걸렸는지는 담지 않는다.
- 규칙·모델 버전은 담지 않는다. 판정 로그와 DB에서 `commentId`로 확인한다.
- 관리자가 숨긴 댓글도 필터가 차단했다면 이 목록에 나온다.

| 오류 코드 | HTTP | 조건 |
| --- | --- | --- |
| `VALIDATION_FAILED` | 400 | `page`·`size` 범위 오류 |
| `UNAUTHORIZED` | 401 | Access JWT 없음 |
| `FORBIDDEN` | 403 | ADMIN이 아님 |
