# Creator Quiz Judge API

[공통 API](../../common/api.md)의 응답 봉투와 [Quiz API](api.md)의 인증 규칙을 따른다. Bearer JWT 필수이며 Creator 자격·Quiz/Job 소유권은 Service가 검사한다. Public/Fan 응답에는 Judge 정보를 넣지 않는다.

## POST /api/creator/quizzes/{quizId}/revisions/{revisionId}/judge-evaluations

저장된 revision 평가를 비동기로 요청한다. 응답은 **202**와 Job 상태이며 replay도 202다. Quiz 본문·모델·prompt는 요청받지 않는다.

```json
{"requestId":"550e8400-e29b-41d4-a716-446655440000"}
```

- UUID requestId 필수. 키는 Creator별로 유일하다. 같은 대상은 stale 정리 후 기존 Job을 반환하며 다시 dispatch하지 않는다. 다른 Quiz/revision으로 재사용하면 409다.
- revision당 series 하나, 최초 포함 최대 2 attempts. FAILED/UNKNOWN 이후 새 requestId로만 명시적 재평가한다. UNKNOWN은 Provider가 이미 완료했을 수도 있어 비용이 중복될 수 있다.
- SUCCEEDED의 fail/uncertain도 완료된 평가이므로 재평가하지 않는다. 승인된 revision도 재평가하지 않는다.
- Creator당 진행 중 Judge Job은 하나다. 다른 requestId로 활성 Job이 있으면 409다.
- 기능 OFF/API key 미설정은 신규 접수만 503으로 막는다. 동일 requestId replay와 조회는 가능하다. 설정 fingerprint 변경은 새 attempt를 만들거나 소모하지 않고 409다.

## GET /api/creator/quizzes/{quizId}/revisions/{revisionId}/judge-evaluations/{evaluationId}

`evaluationId`는 **Job ID**, `resultId`는 **불변 평가 결과 ID**다. 승인 기록은 resultId와 결과 SHA를 고정한다. 본인 Job만 조회하며, 요청과 조회 때 오래된 Job을 정리한다.

PENDING 예시(응답 `data`):

```json
{
  "evaluationId":40,"quizId":1,"revisionId":100,"status":"PENDING",
  "attempt":1,"maxAttempts":2,"failureCode":null,"resultId":null,"aggregation":null,
  "requestedAt":"2026-10-12T00:00:00Z","startedAt":null,"completedAt":null,"questions":[]
}
```

| 상태 | 의미 |
| --- | --- |
| PENDING | 접수됨, Worker 시작 전 |
| RUNNING | 입력 재검증·Provider 실행·완료 처리 중 |
| SUCCEEDED | 유효한 평가 저장. 모든 판정이 pass라는 뜻은 아님 |
| FAILED | 기술적 실패. 안전한 failureCode가 있음 |
| UNKNOWN | timeout·interrupt·stale RUNNING으로 결과 미확인. 늦은 결과를 저장하지 않음 |

SUCCEEDED에서는 문항 번호 오름차순으로 모든 문항을 반환한다. 각 문항의 `items`는 다음 7개다.

```json
{
  "questionNo":0,
  "items":{
    "textAnswerCorrect":{"verdict":"pass","reason":"원문과 정답이 일치합니다."},
    "uniqueAnswer":{"verdict":"pass","reason":"정답이 하나입니다."},
    "evidenceSupportsAnswer":{"verdict":"pass","reason":"근거가 정답을 뒷받침합니다."},
    "questionClarity":{"verdict":"pass","reason":"질문이 명확합니다."},
    "koreanQuality":{"verdict":"pass","reason":"한국어가 자연스럽습니다."},
    "distractorQuality":{"verdict":"pass","reason":"오답 보기가 구분됩니다."},
    "textFaithfulness":{"verdict":"pass","reason":"원문 밖의 주장을 추가하지 않았습니다."}
  }
}
```

verdict는 `pass` / `fail` / `uncertain`. 집계는 ALL_PASS(모두 pass), HAS_FAIL(fail 포함), UNCERTAIN_ONLY(fail 없이 uncertain 포함)다. 마지막 두 결과는 SUCCEEDED여도 승인 불가다. reason은 최대 200자 이내 원문이며 Creator 화면에서 text로 표시한다. 설정·저장 snapshot의 한도가 더 커도 이 정책 상한을 넘길 수 없다.

GET은 Provider 실패를 HTTP 오류로 바꾸지 않고 **200 + Job 상태/failureCode**를 반환한다. 사용량·비용은 DB 실행 기록이며 이 API에는 없다. API key·Provider 원문·입력 본문·정답·해설을 포함하지 않는다. 정답·해설은 기존 Creator revision 상세에서 확인한다.

| failureCode | 분류 |
| --- | --- |
| PROVIDER_TIMEOUT / EXECUTION_TIMEOUT / EXECUTION_INTERRUPTED / STALE_RUNNING | UNKNOWN |
| PROVIDER_ERROR / PROVIDER_REJECTED / PROVIDER_REFUSAL / INCOMPLETE_RESPONSE / INVALID_RESPONSE | FAILED |
| NOT_CONFIGURED / SETTINGS_MISMATCH / INTEGRITY_FAILED / DISPATCH_REJECTED / STALE_PENDING / INTERNAL_ERROR | FAILED |

## HTTP 오류

| 코드 | HTTP | 조건 |
| --- | --- | --- |
| UNAUTHORIZED | 401 | JWT 없음·유효하지 않음 |
| VALIDATION_FAILED | 400 | UUID 누락·잘못된 형식, ID 규칙 위반 |
| FORBIDDEN | 403 | Creator가 아님 또는 다른 Creator의 Quiz/Job |
| RESOURCE_NOT_FOUND / QUIZ_NOT_FOUND / QUIZ_REVISION_NOT_FOUND / QUIZ_JUDGE_EVALUATION_NOT_FOUND | 404 | Member·Quiz·revision·경로에 속한 Job 없음 |
| QUIZ_JUDGE_REQUEST_CONFLICT | 409 | 같은 requestId로 다른 대상 요청 |
| QUIZ_JUDGE_IN_PROGRESS | 409 | Creator의 활성 Judge Job 존재 |
| QUIZ_JUDGE_ATTEMPT_LIMIT_REACHED | 409 | 최초 포함 2 attempts 소진 |
| QUIZ_JUDGE_EVALUATION_COMPLETED / QUIZ_JUDGE_REVISION_APPROVED | 409 | 완료된 평가 또는 승인된 revision 재평가 |
| QUIZ_JUDGE_SETTINGS_MISMATCH | 409 | 최초 series와 현재 설정 불일치 |
| QUIZ_JUDGE_DISABLED / QUIZ_JUDGE_NOT_CONFIGURED | 503 | 신규 접수에 필요한 기능·Provider 설정 없음 |
| QUIZ_REVISION_INTEGRITY_FAILED / QUIZ_JUDGE_EVALUATION_INTEGRITY_FAILED | 500 | 저장 내용·평가 무결성 검증 실패 |

실행 설정·stale·비용·한계는 [Quiz README](README.md#judge-평가와-승인-이슈-529)를 따른다. Judge 통과는 자동 승인·게시가 아니다.

## 마감된 Quiz

마감된 Quiz에는 신규 평가를 접수하지 않는다. 기능 활성화·Provider 설정 검사를 통과한 요청은 접수 잠금 안에서 `QUIZ_CLOSED`(409)로 거절한다. 같은 requestId의 기존 작업 replay와 결과 조회는 유지한다. 진행 중이던 평가 기록은 삭제하지 않는다.
