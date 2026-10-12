# AI Quiz

Creator가 YouTube 영상으로 만든 AI Quiz를 저장하고, Creator가 검토·승인한 revision만 게시해 공개한다(이슈 #521). 외부 API 계약은 [AI Quiz API](api.md), 전체 파이프라인(Grounding·생성·Judge·제출) 설계는 [서비스 연결 설계](integration-design.md)를 따른다.

## 책임

- 검증된 AI 생성 결과(`GeneratedQuiz`)를 Quiz와 불변 revision으로 저장 (`QuizDraftService`, 외부 API 없음)
- Creator 본인 Quiz 조회, revision 승인·거절, 게시·게시 취소
- 게시된 revision의 공개 조회 (정답·해설·근거 제외)
- YouTube URL 기반 Quiz 생성 요청 접수와 비동기 생성 작업 (이슈 #525)

AI 생성(`quiz.generation`)은 결과만 반환하며 이 도메인이 저장 전에 같은 `QuizGenerationValidator`로 다시 검증한다. Member·Creator는 읽기만 한다. YouTube URL 기반 생성 요청은 비동기 작업으로 Grounding → Quiz 생성 → 저장을 연결한다(아래 "Quiz 생성 작업"). GPT Judge, 풀이 제출·채점, 응모권 지급, 게시 후 수정은 아직 구현하지 않았다.

## 소유 데이터

V55의 `quiz`, `quiz_revision`, `quiz_question`, `quiz_option`, `quiz_revision_decision`과 V56의 `quiz_generation`(생성 작업)을 소유한다.

- **quiz**: Creator 소유. `quiz_id`는 revision이 바뀌어도 그대로다. 원본 YouTube `videoId`, 생성 입력 `content_text`와 그 SHA-256은 생성 후 바꾸지 않는다.
- **quiz_revision·quiz_question·quiz_option**: INSERT 후 바꾸지 않는다. 수정은 새 revision(`revision_no` + 1)으로 만든다. `question_no`·`option_no`는 0부터의 표시 순서이고 `correct_option_index`는 0-based `option_no`다.
- **quiz_revision_decision**: revision 하나에 Creator 결정 하나(`UNIQUE(revision_id)`). 바꾸지 않는다. 승인은 Judge 평가 근거(`judge_evaluation_id`·`judge_evaluation_sha256`)가 있어야 DB에 들어간다.

어떤 행도 삭제하지 않는다. 이후 풀이 기록은 `quiz_id`·`revision_id`를 참조하고 `(member_id, quiz_id)`로 1회 제출을 보장하면 되며, 수정·게시 취소가 풀이·응모권 기록을 지우지 않는다.

### 길이 상한

Java `String.length()` 기준이며 빈 값은 허용하지 않는다. 넘으면 저장하지 않는다(`QUIZ_DRAFT_INVALID`).

| 값 | 상한 |
| --- | --- |
| 제목(Creator 지정) | 100 |
| 질문 | 500 |
| 보기 | 200 |
| 해설 | 1,000 |
| sourceEvidence | 1,000 |
| promptVersion | 50 |

원본 텍스트는 기존 Validator의 50,000자 제한을 따르고 `MEDIUMTEXT`에 저장한다. 문항·보기 수와 그 밖의 생성 결과 규칙도 기존 Validator를 따른다.

## 상태

revision의 검토 상태와 Quiz의 공개 상태는 별개다.

| revision 검토 상태 | 의미 |
| --- | --- |
| `PENDING_REVIEW` | 결정 기록 없음 |
| `APPROVED` | Creator 승인 (Judge 평가 근거 포함) |
| `REJECTED` | Creator 거절 (Judge 평가 불필요) |

| Quiz 공개 상태 | `published_revision_id` |
| --- | --- |
| `NOT_PUBLISHED` | 없음. 한 번도 게시하지 않음 |
| `PUBLISHED` | 공개 중인 revision |
| `UNPUBLISHED` | 없음. 게시 취소 |

`ck_quiz_publication`이 "PUBLISHED ⇔ 게시 revision 있음"을 강제하고, `fk_quiz_published_revision`이 게시 revision이 같은 Quiz의 revision인지 확인한다. 게시 revision은 최신 revision과 따로 관리하므로, 새 revision이 승인·게시될 때까지 기존 게시 revision이 공개된다.

| 명령 | 허용 조건 | 같은 결과가 이미 있을 때 |
| --- | --- | --- |
| 승인 | 결정 없음, 저장 내용이 revision 해시와 같음, 승인 정책 통과 | 이미 승인: 기존 결정 반환. 이미 거절: `QUIZ_REVISION_ALREADY_DECIDED` |
| 거절 | 결정 없음 | 이미 거절: 기존 결정 반환. 이미 승인: `QUIZ_REVISION_ALREADY_DECIDED` |
| 게시 | 최신 revision, 승인됨, 저장 내용이 revision 해시와 같음, 승인 정책의 재검증 통과 | 같은 revision 게시 중: 현재 상태 반환 |
| 게시 취소 | 언제나(Judge 상태와 무관) | 게시 중이 아님: 현재 상태 반환 |

게시 취소 후에는 승인 근거가 여전히 유효한 최신 revision을 재승인 없이 다시 게시할 수 있다. 결정은 되돌리지 않는다.

`published_at`은 현재(게시 취소 상태라면 마지막) 게시가 시작된 시각이고, `unpublished_at`은 그 게시가 취소된 시각이다. 게시하면 `published_at`을 새로 기록하고 `unpublished_at`을 지우므로 `PUBLISHED`일 때 `unpublished_at`은 항상 비어 있다.

## Judge 미연동 승인·게시 차단

승인과 게시는 `QuizApprovalPolicy`를 통과해야 한다. GPT Judge 평가를 아직 저장하지 않으므로 운영 구현은 항상 거부하는 `JudgeNotIntegratedQuizApprovalPolicy` 하나뿐이며, 승인·게시는 `QUIZ_JUDGE_EVALUATION_REQUIRED`(409)다.

- 설정 플래그, Profile, 운영 Fake, 관리자 승인처럼 이 정책을 우회하는 경로를 두지 않는다.
- 테스트용 정책(`FakeQuizApprovalPolicy`)은 `src/test`에만 있다.
- 거절과 게시 취소는 이 정책을 거치지 않는다.
- Judge 연동 시 평가 저장소를 검증하는 구현으로 교체하고 deny-all 구현을 지운다. 평가는 revision 해시와 원본 텍스트 해시에 고정해야 한다. 이때 `judge_evaluation_id`에 FK를 추가한다.

## revision 해시

`revision_sha256`은 아래 값을 순서대로 이어 붙인 UTF-8 바이트의 SHA-256(소문자 hex)이다(`QuizRevisionFingerprint`, 버전 `quiz-revision-v1`). 각 값은 `<UTF-8 바이트 길이>:<값>`으로 적고 구분자는 없다. 정수는 10진수 문자열이고, 문자열은 정규화하지 않는다.

1. `quiz-revision-v1`
2. 원본 텍스트 SHA-256 (`content_text`의 UTF-8 바이트, 소문자 hex)
3. 제목, promptVersion, 문항 수
4. 문항마다 순서대로: 질문, 보기 수, 보기(순서대로), 정답 index, 해설, sourceEvidence

승인과 게시 직전에 원본 텍스트 해시와 저장된 문항·보기로 다시 계산해 생성 시 값과 다르면 `QUIZ_REVISION_INTEGRITY_FAILED`(500)로 막는다. 해시에 들어가지 않는 저장 번호도 함께 확인한다. 문항 번호는 0부터 문항 수-1까지, 문항마다 보기 번호는 0부터 이어져야 하고, 보기 수는 revision의 보기 수와 같아야 한다. `QuizRevisionFingerprintTest`가 이 규칙을 별도로 구현해 계산한 고정값으로 검증한다. 규칙을 바꾸면 버전을 올리고 기존 revision은 기존 규칙으로 검증한다.

## 동시성

모든 변경 명령은 Quiz 행을 쓰기 잠금(`findByIdForUpdate`)으로 읽어 같은 Quiz의 명령을 직렬화한다. 잠금 순서는 Quiz → revision → 결정이다. 명령 Transaction은 READ COMMITTED라서, 잠금을 기다린 요청도 앞 요청이 커밋한 결정·게시 상태를 읽고 멱등 응답이나 409로 끝난다. 결정 중복은 `UNIQUE(revision_id)`가, 게시 상태와 포인터 불일치는 CHECK가 마지막으로 막는다.

## 영상 Grounding (이슈 #524)

`VideoGrounder.ground(videoId)`가 YouTube 영상을 Gemini Interactions API(`POST /v1beta/interactions`)로 분석해 Quiz 생성 입력(contentText)과 근거(facts)를 돌려준다. 생성 요청·비동기 작업과의 연결은 #525에서 한다.

- 입력: 11자 videoId만 받고 `https://www.youtube.com/watch?v=<videoId>`로 다시 만들어 보낸다. 사용자 URL을 그대로 보내지 않는다.
- 요청: 영상과 prompt(`GroundingPrompt`, 버전 `quiz-grounding-v1`), `response_format`(JSON schema), `store: false`. `store: false`는 Interaction 객체를 나중에 조회할 수 있게 저장하지 않도록 요청할 뿐 Provider의 모든 데이터 보관을 끈다는 뜻이 아니다.
- 결과: contentText(검증 후 원문 그대로), facts(사실, evidenceType `SPEECH`·`VISUAL`·`UNKNOWN`, 근거, 시작·끝 시각), promptVersion, model. promptVersion과 model은 응답이 아니라 실행 설정에서 정한다. facts는 저장하지 않는다.
- 검증(`VideoGroundingValidator`): contentText는 공백 불가, 50,000자 이하(Java `String.length()`). facts는 비어도 되지만 각 사실과 근거는 공백 불가. 시각은 시작·끝이 모두 null이거나 모두 `MM:SS`/`MM:SS.s`(분 2자리 이상, 초 00~59)이고 시작이 끝보다 늦을 수 없다. 영상 길이는 알 수 없어 검사하지 않는다.
- 오류: 429·5xx만 최대 시도 횟수(기본 3)까지 지수 대기(기본 1초부터 두 배, 상한 4초) 후 재시도한다. 시간 초과는 재시도하지 않고 `QuizProviderTimeoutException`이다. 나머지는 `VideoGroundingException`으로, 그 밖의 4xx는 `REJECTED`, 재시도 소진·네트워크 오류는 `PROVIDER_ERROR`, 미완료(`incomplete` 등)·형식·계약 위반은 `INVALID_RESPONSE`, API key 미설정은 `NOT_CONFIGURED`(호출하지 않음)다. 예외는 고정 문구만 쓰고 cause를 붙이지 않으며, 로그에는 HTTP 상태만 남긴다.
- 설정: `cking.quiz.grounding.*`(model, base-url, connect/read timeout, max-attempts, retry-backoff, retry-backoff-max, max-output-tokens). Quiz 생성 설정(`cking.quiz.gemini`)과 분리하고 API key는 같은 `GEMINI_API_KEY`를 쓰므로 quota와 비용이 합산된다. 출력 token 상한은 비워 두면 요청에 넣지 않는다.
- Interactions API는 Beta, YouTube URL 입력은 preview이며 공개 영상만 가능하다. 모델·timeout 기본값은 실제 호출로 확인한 뒤 조정한다.

## Quiz 생성 작업 (이슈 #525)

`POST /api/creator/quizzes/generations`가 YouTube URL에서 videoId만 꺼내 생성 작업(`quiz_generation`)을 저장하고, 커밋 뒤 전용 Executor에서 `VideoGrounder` → 기존 `QuizGenerator`(문항 3·5개, 보기 4개) → `QuizDraftService`로 처리한다. API 계약은 [AI Quiz API](api.md#post-apicreatorquizzesgenerations)를 따른다.

- 상태: PENDING → RUNNING → SUCCEEDED / FAILED / UNKNOWN. 허용 전이는 PENDING → RUNNING·FAILED, RUNNING → SUCCEEDED·FAILED·UNKNOWN이고, 모두 현재 상태를 조건으로 건 UPDATE나 행 잠금으로 한다. CHECK가 상태와 결과(quizId·revisionId)·실패 코드·시각의 조합을 강제한다.
- 멱등성: `UNIQUE(creator_id, request_id)`와 요청 지문(videoId·제목·문항 수·보기 수의 SHA-256). 고유 제약 충돌 뒤에는 새 Transaction에서 다시 읽어 같은 요청이면 기존 작업을, 아니면 409를 돌려준다.
- 진행 중 작업 제한: PENDING·RUNNING일 때만 `creator_id`가 되는 생성 컬럼 `active_creator_id`의 UNIQUE로 Creator당 하나만 허용한다. 동시 요청도 DB가 막는다.
- Transaction 경계: 접수 Service는 Transaction을 열지 않고, 작업 저장·점유·실패 기록이 각각 짧은 Transaction이다(`QuizGenerationStore`). Grounding과 Quiz 생성 Provider 호출은 Transaction 밖이다. Quiz 저장과 SUCCEEDED 전환은 작업 행을 잠근 한 Transaction이라 함께 커밋되거나 함께 롤백된다.
- Executor: `quizGenerationExecutor`(pool 2, queue 10, AbortPolicy). 요청 thread에서 Provider를 호출하지 않도록 CallerRunsPolicy를 쓰지 않는다. 제출이 거절되면 PENDING → FAILED(`DISPATCH_REJECTED`)다.
- 실패: 시간 초과는 UNKNOWN(`PROVIDER_TIMEOUT`), 그 밖의 Provider·생성·저장 실패는 FAILED이며 Quiz 행을 남기지 않는다. 어느 경우에도 자동으로 다시 호출하지 않는다.
- 오래된 작업: 별도 Scheduler 없이 생성 요청과 상태 조회 때 해당 Creator의 작업을 정리한다. 접수 후 `stale-pending`(기본 10분)이 지난 PENDING은 FAILED(`STALE_PENDING`), 시작 후 `stale-running`(기본 15분)이 지난 RUNNING은 UNKNOWN(`STALE_RUNNING`)이다. 늦게 끝난 Worker는 작업 행을 잠근 뒤 RUNNING이 아니면 결과를 저장하지 않는다.
- 설정: `cking.quiz.generation.*`(enabled 기본 false, stale-pending, stale-running, executor.pool-size·queue-capacity·await-termination-seconds). 꺼져 있으면 새 요청은 503이고 기존 작업 조회는 된다. Executor 크기와 기준 시간은 운영 확인 전 초기값이다.
- 로그에는 작업 ID·상태·실패 코드만 남기고 contentText·Provider 오류 원문·API key는 남기지 않는다.
