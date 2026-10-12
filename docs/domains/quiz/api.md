# AI Quiz API

모든 성공·실패 응답은 [공통 API 규약](../../common/api.md)의 응답 봉투를 사용하며, 아래 Response 예시는 `data` 값이다. 상태 전이와 승인 조건은 [AI Quiz](README.md)를 따른다.

## 인증

- `/api/creator/quizzes/**`는 Bearer Access JWT가 필수다. 호출자는 `@CurrentMemberId`로 식별한다. 없는 Member는 `RESOURCE_NOT_FOUND`(404), Creator가 아니거나 다른 Creator의 Quiz면 `FORBIDDEN`(403)이다.
- `GET /api/creators/{creatorId}/quizzes/**`(공개 조회)는 인증이 필요 없다.

## Judge 미연동 상태

GPT Judge 평가가 연동되기 전까지 승인과 게시는 항상 `QUIZ_JUDGE_EVALUATION_REQUIRED`(409)다. 따라서 현재 공개 조회에 나오는 Quiz는 없다. 거절·게시 취소·조회는 사용할 수 있다.

## 공통 값

| 필드 | 설명 |
| --- | --- |
| `publicationStatus` | `NOT_PUBLISHED`, `PUBLISHED`, `UNPUBLISHED` |
| `reviewStatus` | revision 검토 상태. `PENDING_REVIEW`, `APPROVED`, `REJECTED` |
| `questionNo`, `optionNo` | 0부터 시작하는 표시 순서 |
| `correctOptionIndex` | 0-based. 정답 보기의 `optionNo`와 같다(Creator API에만 있다) |
| `publishedAt` | 현재(또는 마지막) 게시가 시작된 시각. 재게시·게시 revision 변경 때 갱신된다. 게시한 적 없으면 null |
| `unpublishedAt` | 그 게시가 취소된 시각. `PUBLISHED`이면 항상 null이고, 재게시하면 지워진다 |
| 시각 | UTC Instant |

## GET /api/creator/quizzes

내 Quiz를 최근 생성 순(`createdAt` 내림차순, tie-breaker `quizId` 내림차순)으로 조회한다. `page`(기본 0), `size`(기본 20, 최대 100). 제목과 검토 상태는 최신 revision 기준이다.

```json
{
  "items": [
    {
      "quizId": 1,
      "title": "물의 순환",
      "publicationStatus": "NOT_PUBLISHED",
      "publishedRevisionId": null,
      "latestRevisionId": 100,
      "latestRevisionNo": 1,
      "latestReviewStatus": "PENDING_REVIEW",
      "publishedAt": null,
      "createdAt": "2026-10-12T00:00:00Z",
      "updatedAt": "2026-10-12T00:00:00Z"
    }
  ],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1, "hasNext": false
}
```

## GET /api/creator/quizzes/{quizId}

내 Quiz 상세. `revisions`는 revision 번호 오름차순이다.

```json
{
  "quizId": 1,
  "creatorId": 10,
  "videoId": "dQw4w9WgXcQ",
  "publicationStatus": "PUBLISHED",
  "publishedRevisionId": 100,
  "publishedAt": "2026-10-12T01:00:00Z",
  "unpublishedAt": null,
  "createdAt": "2026-10-12T00:00:00Z",
  "updatedAt": "2026-10-12T01:00:00Z",
  "revisions": [
    { "revisionId": 100, "revisionNo": 1, "title": "물의 순환", "reviewStatus": "APPROVED",
      "decidedAt": "2026-10-12T00:30:00Z", "createdAt": "2026-10-12T00:00:00Z" }
  ]
}
```

| 오류 코드 | HTTP | 조건 |
| --- | --- | --- |
| `FORBIDDEN` | 403 | Creator가 아니거나 다른 Creator의 Quiz |
| `RESOURCE_NOT_FOUND` | 404 | Member 없음 |
| `QUIZ_NOT_FOUND` | 404 | Quiz 없음 |

## GET /api/creator/quizzes/{quizId}/revisions/{revisionId}

검토용 revision 상세. 정답·해설·근거와 원본 텍스트를 포함한다. `latest`는 최신 revision 여부, `published`는 현재 게시 중인 revision 여부다. `decisionReason`은 거절 사유다.

```json
{
  "quizId": 1,
  "revisionId": 100,
  "revisionNo": 1,
  "latest": true,
  "published": false,
  "title": "물의 순환",
  "videoId": "dQw4w9WgXcQ",
  "promptVersion": "quiz-mcq-v1",
  "contentText": "물은 증발해 구름이 되고, 구름은 비가 되어 내린다.",
  "reviewStatus": "PENDING_REVIEW",
  "decisionReason": null,
  "decidedAt": null,
  "createdAt": "2026-10-12T00:00:00Z",
  "questions": [
    {
      "questionNo": 0,
      "question": "물이 증발하면 무엇이 되나요?",
      "options": [ { "optionNo": 0, "text": "구름" }, { "optionNo": 1, "text": "돌" } ],
      "correctOptionIndex": 0,
      "explanation": "증발한 물은 구름이 됩니다.",
      "sourceEvidence": "물은 증발해 구름이 되고"
    }
  ]
}
```

오류는 Quiz 상세와 같고, Quiz에 속하지 않은 revision은 `QUIZ_REVISION_NOT_FOUND`(404)다.

## POST /api/creator/quizzes/{quizId}/revisions/{revisionId}/approve

revision을 승인한다. 본문은 없다. 저장 내용을 revision 해시로 검증한 뒤 Judge 평가 근거를 확인해 기록한다. 이미 승인한 revision은 기존 결정을 반환한다.

```json
{ "quizId": 1, "revisionId": 100, "reviewStatus": "APPROVED", "reason": null, "decidedAt": "2026-10-12T00:30:00Z" }
```

| 오류 코드 | HTTP | 조건 |
| --- | --- | --- |
| `QUIZ_JUDGE_EVALUATION_REQUIRED` | 409 | Judge 평가 검증을 통과하지 못함. Judge 연동 전에는 항상 |
| `QUIZ_REVISION_ALREADY_DECIDED` | 409 | 이미 거절한 revision |
| `QUIZ_REVISION_INTEGRITY_FAILED` | 500 | 저장 내용이 revision 해시와 다름 |
| `FORBIDDEN` / `RESOURCE_NOT_FOUND` / `QUIZ_NOT_FOUND` / `QUIZ_REVISION_NOT_FOUND` | 403/404 | 위와 같음 |

## POST /api/creator/quizzes/{quizId}/revisions/{revisionId}/reject

revision을 거절한다. Judge 평가가 없어도 된다. 본문은 생략할 수 있다. 공백뿐인 사유는 저장하지 않는다. 이미 거절한 revision은 기존 결정(처음 사유)을 반환한다.

```json
{ "reason": "정답이 둘입니다." }
```

| 오류 코드 | HTTP | 조건 |
| --- | --- | --- |
| `VALIDATION_FAILED` | 400 | 사유가 500자 초과 |
| `QUIZ_REVISION_ALREADY_DECIDED` | 409 | 이미 승인한 revision |
| `FORBIDDEN` / `RESOURCE_NOT_FOUND` / `QUIZ_NOT_FOUND` / `QUIZ_REVISION_NOT_FOUND` | 403/404 | 위와 같음 |

## POST /api/creator/quizzes/{quizId}/publish

승인된 최신 revision을 게시한다. 다른 revision을 게시 중이면 게시 revision을 바꾼다. 게시 직전에 저장 내용과 Judge 승인 근거를 다시 검증한다. 같은 revision을 이미 게시 중이면 현재 상태를 반환한다.

```json
{ "revisionId": 100 }
```

```json
{ "quizId": 1, "publicationStatus": "PUBLISHED", "publishedRevisionId": 100,
  "publishedAt": "2026-10-12T01:00:00Z", "unpublishedAt": null }
```

| 오류 코드 | HTTP | 조건 |
| --- | --- | --- |
| `VALIDATION_FAILED` | 400 | `revisionId` 없음 |
| `QUIZ_REVISION_NOT_LATEST` | 409 | 최신 revision이 아님 |
| `QUIZ_REVISION_NOT_APPROVED` | 409 | 검토 대기 또는 거절된 revision |
| `QUIZ_JUDGE_EVALUATION_REQUIRED` | 409 | 승인 근거가 유효하지 않음. Judge 연동 전에는 항상 |
| `QUIZ_REVISION_INTEGRITY_FAILED` | 500 | 저장 내용이 revision 해시와 다름 |
| `FORBIDDEN` / `RESOURCE_NOT_FOUND` / `QUIZ_NOT_FOUND` / `QUIZ_REVISION_NOT_FOUND` | 403/404 | 위와 같음 |

## POST /api/creator/quizzes/{quizId}/unpublish

게시를 취소한다. 본문은 없다. Judge 상태와 무관하며 revision·결정 기록은 그대로 남는다. 게시 중이 아니면 현재 상태를 반환한다. 응답은 게시와 같은 형식이다. 오류는 Quiz 상세와 같다.

## POST /api/creator/quizzes/generations

YouTube 영상으로 Quiz 생성을 요청한다. 응답 `202`와 생성 작업. 생성은 비동기로 진행되며 아래 상태 조회 API로 확인한다. 성공하면 만들어진 Quiz와 revision을 Quiz 상세·revision 상세 API로 조회한다.

```json
{ "requestId": "550e8400-e29b-41d4-a716-446655440000", "youtubeUrl": "https://youtu.be/dQw4w9WgXcQ",
  "title": "물의 순환", "questionCount": 3 }
```

- `requestId`(UUID)는 필수다. 같은 requestId와 같은 입력(videoId·제목·문항 수)은 기존 작업을 반환한다. URL 표현이 달라도 videoId가 같으면 같은 입력이다. 입력이 다르면 `QUIZ_GENERATION_REQUEST_CONFLICT`다. 기능이 꺼져 있어도 기존 작업 재요청은 같은 응답이다.
- `youtubeUrl`은 https의 `youtube.com`·`www.youtube.com`·`m.youtube.com`의 `/watch?v=<id>`·`/shorts/<id>`, `youtu.be/<id>`만 받는다. videoId는 11자이며 userinfo·443이 아닌 port·`v` 중복·퍼센트 인코딩된 videoId는 거부한다. 그 밖의 query와 fragment는 무시한다. 서버는 이 URL을 요청하지 않고 videoId만 Grounding에 넘긴다.
- `title`은 공백일 수 없고 100자 이하다. `questionCount`는 3 또는 5이며 생략하면 3이다. 보기 수는 4로 고정한다.
- Creator당 진행 중(PENDING·RUNNING) 작업은 하나다. 요청 전에 오래된 작업을 정리한다.

응답(이하 생성 작업):

```json
{ "generationId": 1, "status": "PENDING", "videoId": "dQw4w9WgXcQ", "title": "물의 순환",
  "questionCount": 3, "optionCount": 4, "quizId": null, "revisionId": null, "failureCode": null,
  "requestedAt": "2026-10-12T00:00:00Z", "startedAt": null, "completedAt": null }
```

| 오류 코드 | HTTP | 조건 |
| --- | --- | --- |
| `VALIDATION_FAILED` | 400 | requestId·URL·제목·문항 수 규칙 위반 |
| `FORBIDDEN` / `RESOURCE_NOT_FOUND` | 403/404 | Creator가 아님 / Member 없음 |
| `QUIZ_GENERATION_REQUEST_CONFLICT` | 409 | 같은 requestId로 다른 입력의 작업이 있음 |
| `QUIZ_GENERATION_IN_PROGRESS` | 409 | 진행 중인 생성 작업이 있음 |
| `QUIZ_GENERATION_DISABLED` | 503 | 생성 기능이 꺼져 있음(`cking.quiz.generation.enabled`) |

## GET /api/creator/quizzes/generations/{generationId}

본인 생성 작업을 조회한다. 응답은 생성 작업이며, 조회하면서 오래된 작업을 정리한다. 원본 텍스트·생성 문항·Provider 오류 원문은 담지 않는다.

| `status` | 의미 |
| --- | --- |
| `PENDING` | 접수됨, Worker 시작 전 |
| `RUNNING` | Grounding·생성 진행 중 |
| `SUCCEEDED` | 저장 완료. `quizId`·`revisionId`가 있다 |
| `FAILED` | 실패. `failureCode`가 있다 |
| `UNKNOWN` | 시간 초과 등으로 결과를 알 수 없다. `failureCode`가 있고 이후 결과를 저장하지 않는다 |

`failureCode`: `GROUNDING_REJECTED`(비공개 영상 등 Provider 거부), `GROUNDING_INVALID`, `GENERATION_INVALID`, `DRAFT_INVALID`(저장 길이 상한 등), `PROVIDER_ERROR`, `PROVIDER_TIMEOUT`(UNKNOWN), `DISPATCH_REJECTED`(생성 대기열이 가득 참), `STALE_PENDING`, `STALE_RUNNING`(UNKNOWN), `INTERNAL_ERROR`. 실패해도 자동으로 다시 시도하지 않으므로 새 requestId로 다시 요청한다.

| 오류 코드 | HTTP | 조건 |
| --- | --- | --- |
| `FORBIDDEN` | 403 | Creator가 아니거나 다른 Creator의 작업 |
| `RESOURCE_NOT_FOUND` | 404 | Member 없음 |
| `QUIZ_GENERATION_NOT_FOUND` | 404 | 작업 없음 |

## GET /api/creators/{creatorId}/quizzes

게시 중인 Quiz를 최근 게시 순(`publishedAt` 내림차순, tie-breaker `quizId` 내림차순)으로 조회한다. `page`(기본 0), `size`(기본 20, 최대 100). 미게시·게시 취소된 Quiz는 포함하지 않는다. 없는 Creator는 `RESOURCE_NOT_FOUND`다.

```json
{
  "items": [
    { "quizId": 1, "revisionId": 100, "title": "물의 순환", "questionCount": 2, "publishedAt": "2026-10-12T01:00:00Z" }
  ],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1, "hasNext": false
}
```

## GET /api/creators/{creatorId}/quizzes/{quizId}

게시 중인 revision의 문항과 보기를 조회한다. 정답 index, 해설, sourceEvidence, 원본 텍스트, promptVersion, 해시, 검토 정보는 응답 필드에 없다.

```json
{
  "quizId": 1,
  "creatorId": 10,
  "revisionId": 100,
  "videoId": "dQw4w9WgXcQ",
  "title": "물의 순환",
  "publishedAt": "2026-10-12T01:00:00Z",
  "questions": [
    { "questionNo": 0, "question": "물이 증발하면 무엇이 되나요?",
      "options": [ { "optionNo": 0, "text": "구름" }, { "optionNo": 1, "text": "돌" } ] }
  ]
}
```

| 오류 코드 | HTTP | 조건 |
| --- | --- | --- |
| `RESOURCE_NOT_FOUND` | 404 | Creator 없음 |
| `QUIZ_NOT_FOUND` | 404 | Quiz 없음, 미게시·게시 취소, 다른 Creator의 Quiz |
