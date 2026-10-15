# Creator Quiz 원문 검토 API (이슈 #615)

[공통 API](../../common/api.md)의 응답 봉투와 [Quiz API](api.md)의 인증 규칙을 따른다. Bearer JWT 필수이며 Creator 자격·작업 소유권은 Service가 검사한다.

`cking.quiz.generation.content-review.enabled`가 켜진 상태에서 만든 작업은 Grounding 뒤 Creator 원문 확정을 기다린다. 단계 전이·정리 규칙은 [Quiz README](README.md#원문-검토-흐름-이슈-604)를 따른다. 생성 요청·작업 상태 조회는 [Quiz API](api.md#post-apicreatorquizzesgenerations)를 따른다. 세 API 모두 로그인한 Creator 본인 작업만 다룬다(다른 Creator의 작업은 403, 없는 작업은 404, JWT 없음은 401). 확정 전 수정 중인 원문은 서버에 저장하지 않으므로 FE가 브라우저에 임시 저장한다.

## GET /api/creator/quizzes/generations/current

본인의 진행 중(PENDING·RUNNING) 작업 하나를 조회한다. 조회 전에 오래된 작업을 정리한다. 새로고침·다른 기기에서 진행 중 작업을 복구하거나 `QUIZ_GENERATION_IN_PROGRESS`(409) 뒤 진행 중 작업을 찾을 때 쓴다. 응답은 생성 작업 필드에 `content`를 더한 것이며, 원문 검토 대기(`reviewStage=AWAITING_REVIEW`)일 때만 `content`가 있고 그 밖에는 `null`이다. 진행 중 작업이 없으면 `data`를 생략한다(`{"code": "SUCCESS"}`).

```json
{ "generationId": 1, "status": "RUNNING", "videoId": "dQw4w9WgXcQ", "title": "물의 순환",
  "questionCount": 3, "optionCount": 4, "quizId": null, "revisionId": null, "failureCode": null,
  "requestedAt": "2026-10-12T00:00:00Z", "startedAt": "2026-10-12T00:00:01Z", "completedAt": null,
  "reviewStage": "AWAITING_REVIEW", "contentEdited": null, "contentConfirmedAt": null,
  "content": {
    "aiContentText": "물은 증발해 구름이 된다. …",
    "facts": [{ "fact": "물은 증발한다.", "evidenceType": "SPEECH", "evidence": "물이 증발합니다",
                "timestampStart": "00:10", "timestampEnd": "00:12.5" }],
    "groundingModel": "gemini-3.8-flash", "groundingPromptVersion": "quiz-grounding-v1", "maxLength": 50000 } }
```

- `facts`는 AI가 원본 원문과 함께 만든 근거라 실제 영상과 다를 수 있다. `evidenceType`은 `SPEECH`·`VISUAL`·`UNKNOWN`, 시각은 `MM:SS(.f)`이며 없으면 `null`이다.
- `maxLength`는 확정할 수 있는 원문의 최대 길이(UTF-16 code unit 기준 문자 수)다.

## POST /api/creator/quizzes/generations/{generationId}/content-confirmation

검토한 원문을 확정하고 그 원문으로 Quiz 생성을 시작한다. 응답 `202`와 생성 작업(`reviewStage=GENERATING`). 생성 결과는 생성 작업 조회 API로 확인한다.

```json
{ "contentText": "물은 증발해 구름이 된다. …(Creator가 고친 원문)" }
```

- `contentText`는 공백만으로 이루어질 수 없고 50,000자 이하다. 앞뒤 공백·줄바꿈을 포함해 정규화하지 않고 그대로 저장하며, 이 원문이 생성 입력과 Quiz 원문(`contentText`)이 된다. AI 원문을 고치지 않고 그대로 보내도 된다.
- 확정 후에는 원문을 바꿀 수 없다. 같은 원문 재요청은 다시 생성하지 않고 현재 작업 상태를 202로 돌려준다(멱등). 다른 원문은 409다.
- 같은 작업의 확정·취소·재요청이 동시에 와도 작업 행을 잠근 뒤 하나만 반영한다. 생성 대기열이 가득 차 제출이 거절되면 작업은 `FAILED`(`DISPATCH_REJECTED`)로 끝나고 진행 중 작업 제한이 풀린다.

| 오류 코드 | HTTP | 조건 |
| --- | --- | --- |
| `VALIDATION_FAILED` | 400 | `contentText` 누락·공백뿐·50,000자 초과, 잘못된 ID |
| `FORBIDDEN` / `RESOURCE_NOT_FOUND` | 403/404 | Creator가 아니거나 다른 Creator의 작업 / Member 없음 |
| `QUIZ_GENERATION_NOT_FOUND` | 404 | 작업 없음 |
| `QUIZ_GENERATION_CONTENT_CONFLICT` | 409 | 다른 원문으로 이미 확정한 작업 |
| `QUIZ_GENERATION_NOT_AWAITING_REVIEW` | 409 | Grounding 중·취소·실패·기존 흐름 작업 등 검토 대기가 아님 |
| `QUIZ_GENERATION_DISABLED` | 503 | 생성 기능이 꺼져 있을 때 새 확정(같은 원문 재요청은 허용) |

## POST /api/creator/quizzes/generations/{generationId}/cancel

원문 검토 대기 작업을 취소한다. 응답 `200`과 생성 작업(`status=FAILED`, `failureCode=CANCELLED`). 진행 중 작업 제한이 풀려 새 생성을 요청할 수 있다. 저장한 AI 원문은 남는다. 이미 취소한 작업은 현재 상태를 200으로 돌려준다(멱등). 생성 기능이 꺼져 있어도 취소할 수 있다.

| 오류 코드 | HTTP | 조건 |
| --- | --- | --- |
| `VALIDATION_FAILED` | 400 | 잘못된 ID |
| `FORBIDDEN` / `RESOURCE_NOT_FOUND` | 403/404 | Creator가 아니거나 다른 Creator의 작업 / Member 없음 |
| `QUIZ_GENERATION_NOT_FOUND` | 404 | 작업 없음 |
| `QUIZ_GENERATION_NOT_AWAITING_REVIEW` | 409 | Grounding 중·생성 단계·실패·기존 흐름 작업 등 검토 대기가 아님 |
