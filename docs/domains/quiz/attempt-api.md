# Fan Quiz 응시 API

Bearer Access JWT가 필수다. 호출자는 `@CurrentMemberId`로 식별하며 request의 memberId나 점수는 사용하지 않는다.
USER와 ADMIN 모두 Creator 본인 Quiz에는 신규 응시할 수 없다. 공개 Quiz 목록·상세에는 정답·해설·sourceEvidence를 계속 숨긴다.
응시 저장 성공 후 본인 결과에만 풀이 정보를 공개한다. 응답은 공통 `ApiResponse` 봉투를 사용하며 아래 예시는 `data`다.

## POST /api/creators/{creatorId}/quizzes/{quizId}/attempts

신규 제출과 동일 요청 replay 모두 HTTP 200이다. 모든 문항에 정확히 한 번 답하며 문항·보기 인덱스는 0-based다.

```json
{
  "requestId": "750e8400-e29b-41d4-a716-446655440000",
  "revisionId": 100,
  "answers": [
    {"questionNo": 0, "selectedOptionIndex": 0},
    {"questionNo": 1, "selectedOptionIndex": 1}
  ]
}
```

- 답안을 questionNo 기준으로 정규화하므로 배열 순서만 바뀐 재전송은 같은 요청이다.
- requestId는 Member별 UUID이며 다른 Creator/Quiz/revision/답안에 재사용하면 409다.
- Member별 Quiz 응시는 revision에 관계없이 평생 한 번이다. 다른 UUID로 재응시하면 409다.
- 신규 제출만 현재 게시 상태와 요청 revision 일치를 검사한다. 미게시·게시 취소는 404, 게시 revision 불일치는 409다.
- 게시 취소·revision 교체 후에도 동일 요청은 저장된 결과를 반환한다.
- 서버가 Quiz 행을 잠그고 `QuizRevisionReader.readVerified()`로 원본·revision 무결성을 확인한 후 채점한다.
- 헤더와 문항별 결과 저장은 하나의 짧은 DB 트랜잭션이다. UNIQUE 경쟁 실패는 롤백 후 새 트랜잭션에서 재조회한다.

## GET /api/creators/{creatorId}/quizzes/{quizId}/my-attempt

HTTP 200으로 JWT 호출자 본인의 기존 결과만 반환한다. 별도의 memberId 파라미터는 없다.
게시 취소·revision 교체 후에도 응시 시점 스냅샷을 반환하며 현재 revision을 읽거나 다시 채점하지 않는다.
본인 기록이 없거나 creatorId 경로가 기록과 다르면 404다.

## 결과

POST와 GET은 같은 형식이다. 시각은 UTC Instant다.

```json
{
  "attemptId": 10,
  "requestId": "750e8400-e29b-41d4-a716-446655440000",
  "creatorId": 1,
  "quizId": 2,
  "revisionId": 100,
  "title": "물의 순환",
  "videoId": "dQw4w9WgXcQ",
  "questionCount": 2,
  "correctCount": 2,
  "submittedAt": "2026-10-12T00:00:00Z",
  "revisionSha256": "제출 당시 revision SHA-256",
  "snapshotSha256": "저장된 결과 SHA-256",
  "questions": [
    {
      "questionNo": 0,
      "question": "물이 증발하면 무엇이 되나요?",
      "options": ["구름", "돌"],
      "selectedOptionIndex": 0,
      "correctOptionIndex": 0,
      "correct": true,
      "explanation": "증발한 물은 구름이 됩니다.",
      "sourceEvidence": "물은 증발해 구름이 되고"
    },
    {
      "questionNo": 1,
      "question": "구름은 무엇이 되어 내리나요?",
      "options": ["눈", "비"],
      "selectedOptionIndex": 1,
      "correctOptionIndex": 1,
      "correct": true,
      "explanation": "구름은 비가 되어 내립니다.",
      "sourceEvidence": "구름은 비가 되어 내린다."
    }
  ],
  "reward": {
    "amount": 2,
    "requestId": "750e8400-e29b-41d4-a716-446655440001",
    "status": "NOT_INTEGRATED",
    "paymentStatus": "PENDING"
  }
}
```

`reward.requestId`는 응시 requestId와 별개로 서버가 생성해 영속 저장한 rewardRequestId다.
0점은 `amount=0`, `requestId=null`, `status=NOT_ELIGIBLE`이다. 양수 점수는 `NOT_INTEGRATED`다.
`reward.status`는 제출 당시 저장값이며 스냅샷 hash에 포함돼 바뀌지 않는다. 실제 지급 여부는 `reward.paymentStatus`로 본다.

### Ticket 보상 지급(이슈 #543)

양수 점수 응시는 저장이 커밋된 뒤 저장된 `attemptId`·`memberId`·`creatorId`·`correctCount`·`rewardRequestId`로
Ticket Quiz 보상 엔진에 전달된다(정답 1개당 해당 Creator Ticket 1장). 재전송·재전달에도 같은 UUID를 쓰며 같은 응시를
중복 지급하지 않는다. 전달이 실패해도 응시·점수·결과는 그대로이고 재응시는 허용되지 않는다. 0점은 전달하지 않는다.

`reward.paymentStatus`는 POST·GET 응답 시점에 Ticket 상태를 조회해 채운다.

| paymentStatus | 의미 |
| --- | --- |
| `NOT_ELIGIBLE` | 정답 0개 |
| `PENDING` | 미접수 또는 지급 처리 중(Ticket `RECEIVED`·`EXECUTING`·`ACCEPTED`·`RETRYABLE_FAILURE`) |
| `PAID` | Ticket `APPLIED`. 이것만 지급 완료다 |
| `DELAYED` | Ticket `UNKNOWN`. 지급 여부 확인이 필요하며 자동으로 다시 실행하지 않는다 |
| `UNAVAILABLE` | 지급 상태 조회 실패. 미지급을 뜻하지 않는다 |

**활성화**: 실제 지급과 누락 재전달은 `cking.ticket.quiz-reward.worker-enabled=true`일 때만 동작한다(기본 `false`).
꺼져 있으면 제출 시 접수만 되고(`PENDING`) 지급은 일어나지 않는다. 켜져 있으면 `QuizRewardDeliveryScheduler`가
60초(`cking.quiz.reward-delivery.interval-ms`)마다 양수 점수 응시를 PK 순서로 최대 100건(`cking.quiz.reward-delivery.batch-size`)씩
다시 전달해, 서버 중단·전달 실패·기능 OFF로 남은 요청과 과거 응시를 처리한다. 커서는 메모리에 있어 재시작하면 처음부터
다시 훑는다. Ticket이 실행을 허용한 요청(`RECEIVED`, 무반영이 확인된 `RETRYABLE_FAILURE`)만 실행되며 `UNKNOWN`·실행 중
요청은 자동 복구하지 않는다.

## 오류

| code | HTTP | 조건 |
| --- | --- | --- |
| VALIDATION_FAILED | 400 | UUID/필수 값 오류, 누락·중복·범위 밖 답안 |
| UNAUTHORIZED | 401 | Access JWT 누락·만료·변조 |
| FORBIDDEN | 403 | Creator 본인 Quiz 신규 응시, ADMIN 포함 |
| RESOURCE_NOT_FOUND | 404 | 인증된 Member가 DB에 없음 |
| QUIZ_NOT_FOUND | 404 | 신규 제출의 Quiz 없음·다른 Creator 경로·미게시·게시 취소 |
| QUIZ_ATTEMPT_NOT_FOUND | 404 | 본인 결과 없음·Creator 경로 불일치 |
| QUIZ_ALREADY_ATTEMPTED | 409 | 동일 Quiz에 다른 UUID로 재응시 |
| QUIZ_ATTEMPT_REQUEST_CONFLICT | 409 | 동일 Member UUID의 다른 대상/revision/답안 |
| QUIZ_PUBLISHED_REVISION_CHANGED | 409 | 신규 제출 revision이 현재 게시 revision과 다름 |
| QUIZ_REVISION_INTEGRITY_FAILED | 500 | 원본/revision 또는 저장 결과 무결성 검증 실패 |
| SYSTEM_ERROR | 500 | 재조회로 판정할 수 없는 DB 저장·접근 실패 등 예상하지 못한 오류 |

## 저장 및 해시

V66는 `quiz_attempt`와 `quiz_attempt_answer`를 추가한다. Member/Quiz, Member/requestId,
attempt/questionNo UNIQUE와 Quiz/Creator·Quiz/revision 소속 FK를 적용한다. 보상 UUID도 UNIQUE다.

요청 fingerprint v1은 Member·Creator·Quiz·revision·문항 수·정규화된 문항 번호/선택지의 SHA-256이다.
UUID는 내용 hash에서 제외하고 Member/requestId UNIQUE로 별도 식별한다.
결과 snapshot v1은 호출자·요청 fingerprint·응시 UUID·대상·제목·영상·점수·UTC 시각·revision hash·보상·전체 문항을 포함한다.
각 값은 UTF-8 바이트 길이 접두어로 구분하며 null은 별도로 표시한다. snapshot hash 자체와 생성된 attemptId는 제외한다.
조회/replay는 저장 문항 수·번호·보기·정답 여부·점수·보상·요청 fingerprint 및 snapshot hash를 검증한다.

## 배포 및 검증

- PR #534의 V59 → PR #536의 V60 → #533의 V61~V63 → #535의 V66 순서로 적용한다.
- 선행 migration이 없는 환경에 V66를 먼저 배포하거나 Fan PR을 먼저 병합하지 않는다.
- PR 병합 전 최신 develop과 열린 PR에서 V66 및 API 인덱스 번호 충돌을 재확인한다.
- focused 단위·WebMvc 보안 및 공개 조회 회귀는 로컬 실행 대상이다.
- MySQL UNIQUE 경쟁·rollback·게시 잠금 경쟁·FK·변조 검증은 CI 전용이며 로컬에서는 컴파일까지만 한다.
