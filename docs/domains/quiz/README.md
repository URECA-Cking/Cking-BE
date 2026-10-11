# AI Quiz

Creator가 YouTube 영상으로 만든 AI Quiz를 저장하고, Creator가 검토·승인한 revision만 게시해 공개한다(이슈 #521). 외부 API 계약은 [AI Quiz API](api.md), 전체 파이프라인(Grounding·생성·Judge·제출) 설계는 [서비스 연결 설계](integration-design.md)를 따른다.

## 책임

- 검증된 AI 생성 결과(`GeneratedQuiz`)를 Quiz와 불변 revision으로 저장 (`QuizDraftService`, 외부 API 없음)
- Creator 본인 Quiz 조회, revision 승인·거절, 게시·게시 취소
- 게시된 revision의 공개 조회 (정답·해설·근거 제외)

AI 생성(`quiz.generation`)은 결과만 반환하며 이 도메인이 저장 전에 같은 `QuizGenerationValidator`로 다시 검증한다. Member·Creator는 읽기만 한다. YouTube URL 처리, Grounding, Quiz 생성 실행, GPT Judge, 풀이 제출·채점, 응모권 지급, 게시 후 수정은 아직 구현하지 않았다.

## 소유 데이터

V55의 `quiz`, `quiz_revision`, `quiz_question`, `quiz_option`, `quiz_revision_decision`을 소유한다.

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
