# AI Quiz

Creator가 YouTube 영상으로 만든 AI Quiz를 저장하고, Creator가 검토·승인한 revision만 게시해 공개한다(이슈 #521). 외부 API 계약은 [AI Quiz API](api.md), 전체 파이프라인(Grounding·생성·Judge·제출) 설계는 [서비스 연결 설계](integration-design.md)를 따른다.

## 책임

- 검증된 AI 생성 결과(`GeneratedQuiz`)를 Quiz와 불변 revision으로 저장 (`QuizDraftService`, 외부 API 없음)
- Creator 본인 Quiz 조회, revision 승인·거절, 게시·게시 취소
- 게시된 revision의 공개 조회 (정답·해설·근거 제외)
- YouTube URL 기반 Quiz 생성 요청 접수와 비동기 생성 작업 (이슈 #525)

AI 생성(`quiz.generation`)은 결과만 반환하며 저장 전에 같은 `QuizGenerationValidator`로 다시 검증한다. Member·Creator는 읽기만 한다. YouTube 생성 작업은 Grounding → Quiz 생성 → draft 저장을 연결한다. Creator가 별도로 GPT Judge 평가를 요청하고, 유효한 ALL_PASS 평가를 근거로 명시적으로 승인한 뒤 게시한다. Fan 응시·보상은 응시 API, Creator 문항 선택·수정·추가·책임 승인은 [문항 구성 API](composition-api.md)를 따른다.

## 소유 데이터

V55의 Quiz·revision·문항·보기·결정, V56의 `quiz_generation`, V58의 Judge series·Job·JobCall·result·resultItem을 소유한다. V73이 revision 계보·승인 정책·게시 고정 컬럼과 제약을 더한다([Creator 편집 기반](#creator-편집-revision과-책임-승인-기반-이슈-581)). V77이 생성 작업에 원문 검토 컬럼과 제약을 더한다([원문 검토 데이터 기반](#원문-검토-데이터-기반-이슈-602)).

- **quiz**: Creator 소유. `quiz_id`는 revision이 바뀌어도 그대로다. 원본 YouTube `videoId`, 생성 입력 `content_text`와 그 SHA-256은 생성 후 바꾸지 않는다.
- **quiz_revision·quiz_question·quiz_option**: INSERT 후 바꾸지 않는다. 수정은 새 revision(`revision_no` + 1)으로 만든다. `question_no`·`option_no`는 0부터의 표시 순서이고 `correct_option_index`는 0-based `option_no`다.
- **quiz_revision_decision**: revision 하나에 Creator 결정 하나(`UNIQUE(revision_id)`). 바꾸지 않는다. 승인은 정책(`approval_policy`)과 승인한 revision 해시를 고정한다. 기존 승인(`LEGACY_JUDGE_V1`)은 Judge 평가 근거(`judge_evaluation_id`·`judge_evaluation_sha256`)가 있어야 DB에 들어간다.

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
| 게시 | 게시한 적 있으면 고정 revision만(아니면 `QUIZ_CONTENT_LOCKED`), 최신 revision, 승인됨, 저장 내용이 revision 해시와 같음, 승인 정책의 재검증 통과 | 같은 revision 게시 중: 현재 상태 반환 |
| 게시 취소 | 언제나(Judge 상태와 무관) | 게시 중이 아님: 현재 상태 반환 |

게시 취소 후에는 처음 게시해 고정한 revision을, 승인 근거가 여전히 유효하면 재승인 없이 다시 게시할 수 있다. 다른 revision은 게시할 수 없다. 결정은 되돌리지 않는다.

`published_at`은 현재(게시 취소 상태라면 마지막) 게시가 시작된 시각이고, `unpublished_at`은 그 게시가 취소된 시각이다. 게시하면 `published_at`을 새로 기록하고 `unpublished_at`을 지우므로 `PUBLISHED`일 때 `unpublished_at`은 항상 비어 있다.

## Judge 평가와 승인 (이슈 #529)

운영 `StoredJudgeQuizApprovalPolicy`와 `QuizJudgeResultReader`가 저장 평가를 검증한다. 생성 완료만으로 평가·승인·게시되지 않는다. API는 [Judge API](judge-api.md), 요구사항과 검증 범위는 [검증 목록](judge-verification.md)을 따른다.

- Creator가 revision 평가를 요청하면 Job 접수 → commit → Judge 전용 Executor → 조건부 claim → 입력·설정 재검증 → OpenAI Responses 호출 → 결과 저장 순서로 실행한다. Provider 호출 중 DB Transaction은 없다.
- Job의 `SUCCEEDED`는 유효한 평가 저장을 뜻한다. 판정 집계는 `ALL_PASS` / `HAS_FAIL` / `UNCERTAIN_ONLY`이며 fail·uncertain도 SUCCEEDED로 보존하지만 기존 Judge 승인에서는 통과시키지 않는다. Creator 책임 승인은 원본 판정을 보존한 채 별도 정책으로 수행한다.
- 각 문항의 `textAnswerCorrect`, `uniqueAnswer`, `evidenceSupportsAnswer`, `questionClarity`, `koreanQuality`, `distractorQuality`, `textFaithfulness`가 모두 pass일 때만 Creator가 승인할 수 있다. reason은 공백 불가, 최대 200자(`String.length()`)이며 설정·저장 snapshot으로 늘릴 수 없다. 원문을 자르지 않는다.
- 승인 직전 Job SUCCEEDED, Quiz/revision/series 소속, 원문·revision·input·settings·result hash, 문항 번호·7개 항목·집계를 재검증한다. 승인 기록에는 immutable **result ID**와 SHA를 고정한다. Job ID와 다르다.
- 게시·재게시에는 승인 기록의 정확한 result ID/hash와 저장된 policyVersion을 검증한다. 현재 모델·prompt·pricing 설정 변경으로 과거 근거를 무효화하지 않는다. 이미 승인된 결정 재요청과 같은 revision 게시 중인 요청은 기존 결정을/상태를 반환하는 기존 동작을 유지한다.
- V58의 `(revision_id, judge_evaluation_id) → result(revision_id, result_id)` composite FK가 다른 revision 근거 연결을 막는다. 거절·게시 취소는 평가 없이 가능하다.

### 평가 멱등성과 실패 처리

- series는 revision당 하나이고 최초 입력·설정 identity와 attempt 수를 유지한다. 최초 포함 **최대 2 attempts**이며 series 잠금 안에서 할당한다. 설정 변경으로 한도를 초기화하지 않는다.
- `UNIQUE(creator_id, request_id)`로 같은 대상 replay는 기존 Job을 stale 정리 후 다시 읽는다. 다른 대상이면 409다. 새 접수만 dispatch한다. Creator당 활성(PENDING/RUNNING) Judge Job은 generated nullable column의 UNIQUE로 하나만 허용한다.
- 기술적 FAILED/UNKNOWN만 새 UUID로 명시적 재평가할 수 있다. semantic fail/uncertain 완료와 승인된 revision은 재평가하지 않는다. 기존 series와 현재 settings fingerprint가 다르면 접수 전에 409이며 attempt를 쓰지 않는다. 실행 직전에도 다시 비교한다.
- PENDING → RUNNING·FAILED, RUNNING → SUCCEEDED·FAILED·UNKNOWN. timeout·interrupt는 UNKNOWN, 그 밖의 Provider·형식·저장 실패는 FAILED다. 자동 logical retry·Scheduler·재시작 replay는 없다.
- 요청·조회 때 stale PENDING(기본 15분)은 FAILED, stale RUNNING(기본 5분)은 UNKNOWN으로 정리한다. 늦은 Worker는 terminal 상태를 바꾸거나 결과를 저장하지 않고 관측 실행 기록만 추가할 수 있다.
- Worker의 같은 monotonic deadline을 Store에 전달한다. Quiz → series → Job 잠금과 검증 후 첫 result INSERT 전에 deadline/interrupt를 재검사한다. 초과·중단은 결과 없이 UNKNOWN과 실행 기록을 같은 Transaction에 저장한다.

### 설정·실행 기록과 한계

`cking.quiz.judge.enabled` 기본값은 **false**다. OFF는 신규 평가·technical attempt만 막는다. 기존 조회·replay·유효 평가에 따른 승인·게시·거절·게시 취소는 가능하다. `OPENAI_API_KEY`가 없어도 기동하지만 신규 요청은 호출 전에 503이다.

| 설정 | 기본값(운영 검증 전 초기값) |
| --- | --- |
| 모델 / reasoning | gpt-6.1-sol / medium, fallback 없음 |
| 출력 한도 | 3문항 8192 / 5문항 12288 |
| connect / read / 전체 deadline | 2초 / 60초 / 150초 |
| HTTP retry | 429·5xx만 최대 1회, 기본 1초 대기; 유효한 Retry-After(초 또는 HTTP 날짜)가 5초를 넘으면 retry 안 함. 없거나 잘못된 값은 기본 대기 |
| Executor | 별도 pool 1 / queue 4 / AbortPolicy / shutdown 대기 30초 |

strict JSON Schema, `store:false`, tools/background/이전 대화 연결 없음. timeout·기타 4xx·refusal·incomplete·schema 실패는 HTTP retry하지 않는다. `store:false`가 Provider의 모든 보관을 끈다는 뜻은 아니다.

JobCall은 HTTP 요청마다 response ID/model과 nullable usage(input/output/total/cached/reasoning tokens)를 저장한다. reasoning tokens는 output에 포함되므로 다시 합산하지 않는다. 단가·버전 미설정은 비용 null, 일부 요청만 계산한 금액은 `costComplete=false`다. API key·Provider 오류/전체 응답 원문은 저장·로그·API에 남기지 않는다.

Judge는 contentText 기준 평가이며 원본 영상의 사실성을 검증하지 않는다. 모델 접근 권한·quota·실제 비용·최대 입력 latency·평가 품질과 실제 Provider E2E는 미검증이다. DB 저장 자체가 실패하면 실행·비용 기록도 남지 않을 수 있으며 stale 정리는 그 기록을 복원하지 않는다. DB 제약·잠금·전체 Fake 연결은 CI MySQL 테스트에서 확인한다.

## revision 해시

AI 생성 revision의 `revision_sha256`은 아래 값을 순서대로 이어 붙인 UTF-8 바이트의 SHA-256(소문자 hex)이다(`QuizRevisionFingerprint`, 버전 `quiz-revision-v1`, `hash_version`에 기록). 각 값은 `<UTF-8 바이트 길이>:<값>`으로 적고 구분자는 없다. 정수는 10진수 문자열이고, 문자열은 정규화하지 않는다.

1. `quiz-revision-v1`
2. 원본 텍스트 SHA-256 (`content_text`의 UTF-8 바이트, 소문자 hex)
3. 제목, promptVersion, 문항 수
4. 문항마다 순서대로: 질문, 보기 수, 보기(순서대로), 정답 index, 해설, sourceEvidence

승인과 게시 직전에 원본 텍스트 해시와 저장된 문항·보기로 다시 계산해 생성 시 값과 다르면 `QUIZ_REVISION_INTEGRITY_FAILED`(500)로 막는다. 해시에 들어가지 않는 저장 번호도 함께 확인한다. 문항 번호는 0부터 문항 수-1까지, 문항마다 보기 번호는 0부터 이어져야 하고, 보기 수는 revision의 보기 수와 같아야 한다. `QuizRevisionFingerprintTest`가 이 규칙을 별도로 구현해 계산한 고정값으로 검증한다. 규칙을 바꾸면 버전을 올리고 기존 revision은 기존 규칙으로 검증한다.

Creator가 구성한 revision은 `quiz-revision-v2`다. 같은 길이 접두어 방식으로 다음을 이어 붙인다. v1 행의 해시는 다시 계산하거나 저장하지 않는다.

1. `quiz-revision-v2`
2. 원본 텍스트 SHA-256, 제목, promptVersion
3. 부모 revision ID, 부모 revision 해시, 문항 수
4. 문항마다 순서대로: 변경 유형, 원본 문항 ID, 직전 문항 ID(없으면 빈 문자열), 질문, 보기 수, 보기(순서대로), 정답 index, 해설, sourceEvidence

문항 선택·순서·내용·정답·근거·계보 중 하나라도 바뀌면 해시가 달라진다. v2 revision은 해시와 함께 계보도 검증한다([Creator 편집 기반](#creator-편집-revision과-책임-승인-기반-이슈-581)).

## Creator 편집 revision과 책임 승인 기반 (이슈 #581)

Creator가 AI 문항을 선택·수정·추가한 최종 집합을 책임 승인하기 위한 데이터 기반이다. [문항 구성·책임 승인 API](composition-api.md)는 #582에서 이 기반을 재사용한다. 기존 Judge 승인 의미와 공개 API는 유지한다.

- **revision 계보**: `origin`이 `AI_GENERATED`(v1, 부모 없음) 또는 `CREATOR_COMPOSED`(v2, 부모 revision ID·해시, 편집 Member·시각, 1~5문항·4지선다)다. `fk_quiz_revision_parent`가 같은 Quiz의 부모를, `fk_quiz_revision_parent_hash`가 부모의 실제 해시를 강제한다. AI 원본 revision·문항·Judge 결과는 바꾸지 않는다.
- **문항 계보**: 구성 revision의 문항은 `change_type`(`AI_UNCHANGED`·`CREATOR_EDITED`·`CREATOR_ADDED`)과 원본 AI 문항·부모 revision의 직전 문항 참조를 가진다. 변경 유형은 Creator 주장이 아니라 원본과 질문·보기·정답·해설·근거를 비교해 정한다(같으면 `AI_UNCHANGED`). 수정 문항은 Judge 재평가 전(NOT_REVALIDATED), 추가 문항은 평가 대상 아님(NOT_EVALUATED)이며 원본 Judge 결과를 현재 결과로 재사용하지 않는다. 직전 문항이 부모 revision에 속하는지, 원본이 같은 Quiz의 AI 원본 문항인지, 변경 유형이 내용과 맞는지는 `QuizRevisionReader`가 승인·게시·응시 전에 검증한다.
- **빈 근거**: Creator가 수정·추가한 문항의 sourceEvidence는 선택이며 null·공백이면 빈 문자열로 저장한다(근거 없음). 부모 근거를 상속하지 않는다. `AI_UNCHANGED`는 원본 근거를 그대로 둔다. `quiz_question`·`quiz_attempt_answer`의 NOT NULL은 유지하며, 응시 snapshot 해시는 빈 문자열(`0:`)과 null(`-1:`)을 구분한다.
- **승인 정책**: `LEGACY_JUDGE_V1`은 기존 승인 API와 `StoredJudgeQuizApprovalPolicy`로 ALL_PASS·고정 result ID/hash를 검증한다. `CREATOR_REVIEW_V1`은 Judge 근거 없이(`judge_evaluation_*` NULL) 책임 확인(`responsibility_confirmed`)과 최종 revision 해시를 고정한다. 둘 다 `approved_revision_sha256`을 기록하며 `fk_quiz_revision_decision_revision_hash`가 그 revision의 실제 해시인지 강제한다. `ck_quiz_revision_decision_judge`는 같은 이름으로 정책별 조건이 됐고, V58의 result FK는 그대로다. 기존 Judge 정책은 다른 정책의 승인이나 해시가 다른 승인으로 게시하지 않는다.
- **게시 고정**: 첫 게시 때 `content_locked`와 `locked_revision_id`·`locked_revision_sha256`을 같은 Transaction에서 기록하고 게시 취소로 지우지 않는다. `ck_quiz_lock_*`가 "게시 중 ⇒ 고정 revision = 게시 revision", "게시 취소 ⇒ 잠김", "미게시 ⇒ 고정 revision 없음, `published_at`이 없으면 잠기지 않음"을, FK가 같은 Quiz의 실제 revision 해시를 강제한다.

V73 적용 순서와 기존 데이터:

1. revision·문항·결정·Quiz에 컬럼을 더한다. 기존 revision은 `AI_GENERATED`·`quiz-revision-v1`이고 `revision_sha256`은 바꾸지 않는다.
2. 기존 승인 행에 실제 revision 해시와 `LEGACY_JUDGE_V1`을 채운 뒤 승인 CHECK를 정책별 조건으로 바꾼다.
3. 미게시(`NOT_PUBLISHED`)가 아니거나 `published_at`이 있는 Quiz를 잠그고 상태는 바꾸지 않는다. V55가 허용한 `NOT_PUBLISHED`·`published_at` 있음 행은 게시 revision을 확인할 수 없어 고정하지 않는다. `published_at`이 없는 미게시 Quiz는 잠그지 않는다. `PUBLISHED`는 현재 게시 revision으로 고정한다. `UNPUBLISHED`는 게시 사실이 저장 기록으로 직접 확인될 때만 고정한다. 그 revision으로 제출된 Fan 응시가 있고(응시는 그 revision이 게시 중일 때만 저장된다) 그 Quiz의 revision이 그것 하나뿐이어서 다른 revision이 먼저 게시됐을 수 없을 때다. revision 수·승인 여부만으로 고정하거나 최신 승인 revision으로 추정하지 않는다.
4. 고정 revision을 확인하지 못한 Quiz는 잠금만 남아 재게시가 `QUIZ_CONTENT_LOCKED`로 막힌다. 배포 후 `SELECT quiz_id FROM quiz WHERE content_locked = TRUE AND locked_revision_id IS NULL`로 확인 대상을 조회한다. 공개 포인터·응시 snapshot은 바꾸지 않는다.
5. 정책별 CHECK는 nullable 값에 `IS NOT NULL`을 함께 건다. MySQL CHECK는 FALSE만 거부하고 NULL 비교 결과(UNKNOWN)는 통과시키기 때문이다.

MySQL DDL은 문장마다 암묵적으로 커밋되므로 V73은 원자적으로 적용되지 않고, 실패해도 자동으로 rollback되지 않는다. 중간에 실패하면 앞 문장의 컬럼·제약·UPDATE가 남고 Flyway 이력에 실패로 기록돼 이후 migrate가 거부된다. `flyway repair`는 실패 이력만 지우며 수동으로 적용한 SQL을 성공 이력으로 등록하지 않는다. 기본 복구 절차:

1. 실패한 문장과 이미 적용된 DDL·UPDATE를 확인한다.
2. 부분 적용된 변경을 적용 전 상태로 되돌린다. 남은 컬럼·제약·데이터를 보고 되돌릴 수 있는지 먼저 확인한다.
3. `flyway repair`를 실행한다.
4. `flyway migrate`를 다시 실행해 V73 전체를 적용한다.

이관 결과와 실패 시 부분 적용은 `QuizCreatorRevisionMigrationTest`(별도 MySQL 컨테이너, Docker 필요)가 확인한다.

## 동시성

모든 변경 명령은 Quiz 행을 쓰기 잠금(`findByIdForUpdate`)으로 읽어 같은 Quiz의 명령을 직렬화한다. 잠금 순서는 Quiz → revision → 결정이다. 명령 Transaction은 READ COMMITTED라서, 잠금을 기다린 요청도 앞 요청이 커밋한 결정·게시 상태를 읽고 멱등 응답이나 409로 끝난다. 결정 중복은 `UNIQUE(revision_id)`가, 게시 상태와 포인터 불일치는 CHECK가 마지막으로 막는다.

## 영상 Grounding (이슈 #524)

`VideoGrounder.ground(videoId)`가 YouTube 영상을 Gemini Interactions API(`POST /v1beta/interactions`)로 분석해 Quiz 생성 입력(contentText)과 근거(facts)를 돌려준다. #525 생성 Worker가 이를 호출한다.

- 입력: 11자 videoId만 받고 `https://www.youtube.com/watch?v=<videoId>`로 다시 만들어 보낸다. 사용자 URL을 그대로 보내지 않는다.
- 요청: 영상과 prompt(`GroundingPrompt`, 버전 `quiz-grounding-v1`), `response_format`(JSON schema), `store: false`. `store: false`는 Interaction 객체를 나중에 조회할 수 있게 저장하지 않도록 요청할 뿐 Provider의 모든 데이터 보관을 끈다는 뜻이 아니다.
- 결과: contentText(검증 후 원문 그대로), facts(사실, evidenceType `SPEECH`·`VISUAL`·`UNKNOWN`, 근거, 시작·끝 시각), promptVersion, model. promptVersion과 model은 응답이 아니라 실행 설정에서 정한다. facts는 저장하지 않는다.
- 검증(`VideoGroundingValidator`): contentText는 공백 불가, 50,000자 이하(Java `String.length()`). facts는 비어도 되지만 각 사실과 근거는 공백 불가. 시각은 시작·끝이 모두 null이거나 모두 `MM:SS`/`MM:SS.s`(분 2자리 이상, 초 00~59)이고 시작이 끝보다 늦을 수 없다. 영상 길이는 알 수 없어 검사하지 않는다.
- 오류: 429·5xx만 최대 시도 횟수(기본 3)까지 지수 대기(기본 1초부터 두 배, 상한 4초) 후 재시도한다. 시간 초과는 재시도하지 않고 `QuizProviderTimeoutException`이다. 나머지는 `VideoGroundingException`으로, 그 밖의 4xx는 `REJECTED`, 재시도 소진·네트워크 오류는 `PROVIDER_ERROR`, 미완료(`incomplete` 등)·형식·계약 위반은 `INVALID_RESPONSE`, API key 미설정은 `NOT_CONFIGURED`(호출하지 않음)다. 예외는 고정 문구만 쓰고 cause를 붙이지 않으며, 실패 원인은 [Provider 호출 실패 로그](#ai-provider-호출-실패-로그-이슈-606)로 남긴다.
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
- 오래된 작업: 별도 Scheduler 없이 생성 요청과 상태 조회 때 해당 Creator의 작업을 정리한다. 접수 후 `stale-pending`(기본 10분)이 지난 PENDING은 FAILED(`STALE_PENDING`), 시작 후 `stale-running`(기본 15분)이 지난 RUNNING은 UNKNOWN(`STALE_RUNNING`)이다. 늦게 끝난 Worker는 작업 행을 잠근 뒤 RUNNING이 아니면 결과를 저장하지 않는다. 원문 검토 작업의 단계별 규칙은 [원문 검토 흐름](#원문-검토-흐름-이슈-604)을 따른다.
- 설정: `cking.quiz.generation.*`(enabled 기본 false, content-review.enabled 기본 false, stale-pending, stale-running, executor.pool-size·queue-capacity·await-termination-seconds). 꺼져 있으면 새 요청은 503이고 기존 작업 조회는 된다. Executor 크기와 기준 시간은 운영 확인 전 초기값이다.
- 로그에는 작업 ID·상태·실패 코드만 남기고 contentText·Provider 오류 원문·API key는 남기지 않는다.

### 원문 검토 데이터 기반 (이슈 #602)

Quiz 생성 전에 Creator가 Grounding 원문을 검토·확정하는 흐름을 위해 V77이 `quiz_generation`에 컬럼과 CHECK를 더한다. 이 단계에서는 저장 구조만 있고 생성 흐름은 바뀌지 않는다(모든 작업의 `review_stage`가 NULL).

- 상태는 기존 5종을 그대로 쓰고 세부 단계는 `review_stage`(GROUNDING → AWAITING_REVIEW → GENERATING)로 구분한다. NULL은 검토 없이 Grounding과 생성을 이어서 하는 기존 작업이다. 검토 대기 중에도 status가 RUNNING이라 `active_creator_id`가 Creator당 진행·검토 대기 작업을 하나로 계속 제한한다.
- AWAITING_REVIEW 이후에는 AI 원문(`ai_content_text`)·facts(JSON 배열, 최대 200개)·Grounding 모델·prompt 버전이 함께 있다. GENERATING에는 Creator 확정 원문(정규화 없이 저장)·SHA-256(`QuizRevisionFingerprint.contentTextSha256`과 같은 방식)·확정자·확정 시각이 함께 있다. `generation_started_at`은 GENERATING에서만 쓴다. facts 직렬화 크기(512KB)는 서버가 검증한다.
- 단계별 status: GROUNDING은 SUCCEEDED가 될 수 없고, AWAITING_REVIEW는 RUNNING·FAILED(취소 `CANCELLED`)·UNKNOWN, GENERATING은 PENDING이 될 수 없으며 SUCCEEDED면 생성 시작 시각이 있다.
- 이전 버전 서버의 오래된 작업 정리(PENDING → FAILED, RUNNING → UNKNOWN)는 `review_stage`를 보지 않으므로 CHECK가 어느 단계에서도 이 전이를 막지 않는다. 막으면 이전 버전으로 되돌렸을 때 정리 UPDATE가 실패해 해당 Creator의 생성 요청이 막힌다.
- 실패 코드 `CANCELLED`는 이전 버전으로 되돌려도 읽을 수 있도록 기록 기능보다 먼저 추가했다.
- 기존 Quiz의 `content_text`와 revision·Judge·게시·응시 데이터는 바꾸지 않는다. 컬럼·FK·CHECK는 한 ALTER 문장으로 추가하며, 기존 데이터 보존과 CHECK 반례는 `QuizGenerationContentReviewMigrationTest`(별도 MySQL 컨테이너, Docker 필요)가 확인한다.

### 원문 검토 흐름 (이슈 #604)

`cking.quiz.generation.content-review.enabled`(기본 false)를 켜면 새 작업이 Grounding 뒤 Creator 원문 확정을 기다린 다음 확정 원문으로 Quiz를 만든다. 작업을 만들 때의 값으로 흐름이 정해지며, 꺼져 있으면 기존 흐름과 코드 경로가 그대로다. 확정·취소 HTTP API는 후속 작업이다.

| from (status / 단계) | 사건 | to |
| --- | --- | --- |
| PENDING / GROUNDING | Worker 시작(기존 claim) | RUNNING / GROUNDING |
| RUNNING / GROUNDING | Grounding 성공 | RUNNING / AWAITING_REVIEW, AI 원문·facts·모델·prompt 버전 저장 |
| RUNNING / GROUNDING | Grounding 실패·시간 초과 | 기존 실패 코드 그대로 |
| RUNNING / AWAITING_REVIEW | 원문 확정 | RUNNING / GENERATING, 확정 원문·SHA-256·확정자·시각 기록 후 커밋, 그다음 생성 단계 제출 |
| RUNNING / AWAITING_REVIEW | 취소 | FAILED(`CANCELLED`), AI 원문은 남긴다 |
| RUNNING / GENERATING | 제출 거절 | FAILED(`DISPATCH_REJECTED`), 생성 시작 전일 때만 |
| RUNNING / GENERATING | 생성 시작 | `generation_started_at` 기록, NULL일 때만이라 한 Worker만 진행 |
| RUNNING / GENERATING | 생성·저장 성공 | SUCCEEDED |
| RUNNING / GENERATING | 생성 실패·시간 초과 | 기존 실패 코드 그대로 |

- 단계 전이는 현재 status·단계를 조건으로 건 UPDATE이고, 확정·취소는 작업 행을 잠근 뒤 바꾼다. 그래서 같은 작업의 확정·취소가 동시에 와도 하나만 성공한다. Worker는 자기 단계일 때만 실패를 기록하므로 검토 대기 작업을 끝내지 않는다.
- 원문 확정: 비어 있지 않은 50,000자 이하 원문을 정규화 없이 저장하고, 확정 후에는 바꿀 수 없다. 같은 원문 재확정은 다시 제출하지 않고 현재 상태를 돌려주며, 다른 원문은 `QUIZ_GENERATION_CONTENT_CONFLICT`(409), 검토 대기가 아니면 `QUIZ_GENERATION_NOT_AWAITING_REVIEW`(409)다. 생성 Provider를 호출하므로 생성 기능이 꺼져 있으면 새 확정은 503이다. 취소는 기능 상태와 관계없이 된다.
- 생성 입력은 확정 원문뿐이다. 저장할 때 생성 입력의 해시가 확정 원문 해시와 다르면 저장하지 않으므로, 저장한 Quiz의 `content_text`·`content_text_sha256`은 확정 원문과 같다. sourceEvidence 원문 포함 검증과 Judge 입력도 확정 원문 기준이다.
- facts가 200개를 넘거나 JSON 직렬화가 512KB를 넘으면 `GROUNDING_INVALID`로 끝내고 원문을 저장하지 않는다.
- 오래된 작업 정리: 검토 대기는 자동으로 끝내지 않는다. 생성 단계는 확정 후 `stale-pending`까지 시작하지 않았으면 FAILED(`STALE_PENDING`, Provider 미호출), 시작 후 `stale-running`이 지나면 UNKNOWN(`STALE_RUNNING`)이다. 재시작이나 제출 유실로 실행되지 않은 생성 단계도 이 규칙으로 정리돼 Creator 슬롯이 영구히 막히지 않는다. 정리된 뒤 늦게 실행된 Worker는 생성 시작을 점유하지 못해 Provider를 부르지 않는다.

## AI Provider 호출 실패 로그 (이슈 #606)

OpenAI Judge(`quiz_judge`), Gemini Grounding(`quiz_grounding`), Gemini Quiz 생성(`quiz_generation`)의 실패한 요청마다 공통 `AiProviderCallLog`가 WARN 한 줄을 남긴다. 구독 인증 Gemini 호출은 아직 적용하지 않았다. 성공한 호출은 남기지 않고, 재시도·실패 분류·예외·DB 기록은 바꾸지 않는다. 기존 로그 문장도 그대로 둔다.

```
event=ai_provider_call provider=openai operation=quiz_judge model=gpt-6.1-sol status=429 errorCode=insufficient_quota errorType=insufficient_quota latencyMs=231 attempt=2 retry=false requestId=req_123
```

- `status`: HTTP 상태. 응답을 받지 못했으면 `-`이고 `errorType`이 `timeout`·`network`·`interrupted`, `errorCode`가 예외 클래스 이름이다. 정상 상태로 받았지만 Provider 응답 envelope를 해석하지 못한 경우(JSON이 아님, 완료 상태가 아님, 출력 텍스트·후보 없음)는 `status=200 errorType=invalid_response`다. 그 안의 Quiz·평가·contentText 해석·검증 실패와 OpenAI Judge가 보고한 refusal·incomplete·failed 상태는 이 로그 대상이 아니다. Gemini Grounding은 `incomplete`를 포함해 완료 상태가 아닌 응답을 envelope 해석 실패로 남긴다.
- `errorCode`·`errorType`: OpenAI는 오류 본문의 `error.code`·`error.type`이라 429의 `insufficient_quota`(크레딧 부족)와 `rate_limit_exceeded`(속도 제한)를 구분한다. Gemini는 `details`의 `ErrorInfo.reason`과 `error.status`(예: `RESOURCE_EXHAUSTED`)다.
- `retry`: 이 실패를 재시도 대상으로 판단했는지다. 실제로 다음 요청을 보냈다는 뜻은 아니다. Judge는 재시도 대기 뒤 전체 실행 시간이 남지 않으면 다시 요청하지 않으므로, 실제 요청 횟수는 같은 호출의 `attempt` 값이나 `quiz_judge_job_call` 행 수로 확인한다. `requestId`는 OpenAI 응답 헤더 `x-request-id`가 있을 때만 남긴다.
- 영문·숫자·`_`·`-`·`.`로 된 64자 이하 값(요청 ID는 128자)만 남기고 나머지는 `-`다. 오류 본문이 비었거나 JSON이 아니거나 16KB를 넘으면 기본 진단 정보만 남긴다.
- 응답 원문, 자유 형식 오류 메시지(`error.message`), Gemini `details` 원문, 요청 본문, API key·Authorization, contentText·Quiz 내용은 남기지 않는다. 로그를 만들다 난 오류는 호출자에게 던지지 않는다.
- 업무 식별자(jobId·generationId)는 Provider 호출 계층에서 알 수 없어 남기지 않는다. 같은 Executor thread의 Worker 로그(작업 ID·실패 코드)로 이어서 본다.
- 구독 인증 Gemini 호출(`subscription_verification`)은 담당자와 범위를 협의한 뒤 같은 형식으로 맞춘다.

## Fan 응시와 결과

[Fan 응시 API](attempt-api.md)는 Member별 Quiz 평생 1회 제출을 저장하고 서버에서 채점한다.
동일 정규화 요청은 게시 취소·revision 교체 후에도 같은 결과를 반환한다. 전체 풀이·revision hash·snapshot hash를 보존하고 본인 조회에서 검증한다.
양수 점수에는 고정 rewardRequestId와 NOT_INTEGRATED, 0점에는 UUID 없는 NOT_ELIGIBLE을 기록한다. Ticket 지급·Outbox는 구현하지 않는다.
