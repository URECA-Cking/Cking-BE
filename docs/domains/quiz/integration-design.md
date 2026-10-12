# AI Quiz 서비스 연결 설계

## 목적과 범위

[Issue #514](https://github.com/URECA-Cking/Cking-BE/issues/514)의 초기 설계 기록과 이후 구현 상태를 함께 관리한다.
기존 `QuizGenerator`와 Gemini Adapter를 재사용하고, Benchmark는 품질 기준과 참고 구현으로 활용한다.
Python 파이프라인의 운영 서비스화 또는 전체 Java 이식을 전제로 하지 않는다.
이 문서는 설계·구현 경계를 설명하며 실제 Provider E2E 성공을 주장하지 않는다.

- **CONFIRMED**: 기준 코드·정본 문서에서 확인된 사실.
- **PROPOSED**: 이번 문서가 권장하는 설계. 구현 완료나 팀 합의를 뜻하지 않는다.
- **DECISION REQUIRED**: 구현 전에 팀 합의가 필요한 정책.

현재 구현된 endpoint·DTO·상태·DB 계약은 [Quiz API](api.md), [Judge API](judge-api.md), [Quiz README](README.md)가 정본이다. 아래 #514 초기 제안은 당시 기록이며 현재 계약을 대신하지 않는다.

## 현재 연결 상태 (#521·#524·#525·#529)

**CONFIRMED**: BE에서 Gemini YouTube Grounding → 기존 Gemini QuizGenerator → 불변 draft/revision 저장을 비동기 생성 Job으로 연결했다. Creator는 저장된 revision에 별도 Judge Job을 요청한다. Python Benchmark를 런타임에서 호출하지 않는다.

```text
Creator 생성 요청 → Grounding → QuizGenerator → 구조 검증 → draft 저장
Creator 평가 요청 → OpenAI Pointwise Judge → 결과·실행 기록 저장
Creator 명시적 승인 → 저장된 평가/해시 검증 → 게시 → Fan 조회
```

- 인증·소유권·Job·DB·승인·게시 책임은 BE에 있다. Judge는 contentText 기준이며 원본 영상을 재검증하거나 자동 승인하지 않는다.
- 서비스 생성은 3·5문항 / 보기 4개이며 정답 index는 0-based다. Core의 일반 입력 범위와 서비스 정책을 구분한다.
- V55는 Quiz/revision/Creator 결정, V56은 생성 Job, V58은 revision별 Judge series·attempt Job·HTTP 기록·불변 result/item을 저장한다.
- requestId는 Creator별 UUID다. Judge는 revision당 최초 포함 2 attempts, Creator당 활성 Job 1개를 DB 제약과 행 잠금으로 제한한다. 평가와 생성 Executor는 분리한다.
- SUCCEEDED는 유효한 평가 저장이며 fail/uncertain도 포함한다. 7개 항목이 모두 pass인 ALL_PASS만 명시적 승인 근거로 사용할 수 있다.
- 승인 시 immutable result ID/hash를 고정하고 게시·재게시 시 같은 결과를 저장된 정책 버전으로 검증한다. 모델·prompt·pricing 변경과 feature OFF는 기존 유효 근거를 무효화하지 않는다.
- Provider 호출은 DB Transaction 밖이다. timeout·interrupt·stale RUNNING은 UNKNOWN이며 늦은 결과는 폐기한다. 관측한 실행·부분 비용은 terminal 상태를 바꾸지 않고 기록할 수 있다.

**CONFIRMED**: 기본 기능 OFF, OpenAI 모델·token·timeout·Executor 값은 운영 검증 전 초기값이다. 모델 접근 권한·최대 입력 latency·실제 비용·평가 품질과 실제 Provider E2E는 아직 확인하지 않았다. Fake 연결 및 MySQL 제약 검증은 CI 전용이며 로컬 통합 테스트 실행 결과로 주장하지 않는다.

**DECISION REQUIRED**: 영상 이용 권한 확인, 운영 기능 활성화, 실제 Provider 검증 및 비용·quota 운영 기준, Ticket 지급·Outbox 연동. Fan 제출·서버 채점·본인 결과는 [응시 API](attempt-api.md)에서 정의한다. 새로운 수정/재생성 API와 관리자 override는 현재 범위 밖이다.

## #514 당시 구현과 근거

**CONFIRMED (당시)**: 초기 설계의 BE 기준은 develop `3f151c392ba2496ff3eeb20c54c796631606db71`이다. 이후 항목은 이 시점의 제안·미결정 기록으로 보존하며, 현재 상태는 위 절과 구현 정본을 우선한다.
[PR #291](https://github.com/URECA-Cking/Cking-BE/pull/291)과
[PR #300](https://github.com/URECA-Cking/Cking-BE/pull/300)은 develop에 병합됐다.

| 영역 | 현재 사실과 근거 |
| --- | --- |
| 생성 Core | [QuizGenerator](../../../src/main/java/kr/co/cking/quiz/generation/QuizGenerator.java), 입력·결과 record, Parser, Validator, Fake 구현 존재 |
| Gemini | [GeminiQuizGenerator](../../../src/main/java/kr/co/cking/quiz/generation/gemini/GeminiQuizGenerator.java)가 입력 검사 → prompt → generateContent → Parser → Validator → promptVersion 확인을 수행 |
| 서비스 | `quiz/generation` 밖 Quiz Controller·Service·Entity·Repository는 기준 tree에서 확인되지 않음. Quiz API 인덱스 항목과 관련 migration도 확인되지 않음 |
| 인증·소유권 | Access JWT와 `@CurrentMemberId` 존재. Creator는 `creator.member_id`로 회원과 연결됨. 기존 Post·YouTube 채널 서비스에서 Creator 및 소유권 검사 패턴 확인 |
| 테스트 | `src/test/java/kr/co/cking/quiz/generation/`에 Core 및 Gemini mock 테스트 존재. 이번 문서 작업에서 실행하지 않음 |

**CONFIRMED**: Benchmark 참고 기준은 `bedbd014f3d8d068dd110a60b43d0a35b2acce19`이다.
[결과 계약](https://github.com/URECA-Cking/Cking-Quiz-Benchmark/blob/bedbd014f3d8d068dd110a60b43d0a35b2acce19/docs/results-format.md)과
[선택 기록](https://github.com/URECA-Cking/Cking-Quiz-Benchmark/blob/bedbd014f3d8d068dd110a60b43d0a35b2acce19/docs/selection.md)에
Grounding eligibility, Production Quiz, GPT Pointwise Judge, aggregation, Creator 결정, 로컬 발행을 기록한다.
Local Publication은 파일 기록이며 실제 Cking DB 저장·서비스 게시가 아니다.
Grounding machine pass는 구조·원천·해시 계약 통과이며 영상 사실성 보장이 아니다.
Judge는 정확한 `contentText` 기준이고 영상을 직접 검증하지 않는다.

**CONFIRMED**: 이번 서비스 연결의 실제 Provider E2E 성공 근거는 확보하지 않았다.
Benchmark fake 통합 검증과 기존 Pilot 실험은 BE 운영 설정의 Provider E2E 검증을 대체하지 않는다.

## 책임 경계

아래 서비스 배치는 **PROPOSED**이며 Grounding·Judge 실행 위치는 **DECISION REQUIRED**다.

| 담당 | 책임 |
| --- | --- |
| BE Application | 인증된 actor의 Creator·리소스 소유권, 생성 작업, draft·revision, 승인·게시·조회 및 DB 정합성 |
| 기존 QuizGenerator | 확보된 contentText와 생성 조건으로 검수 전 GeneratedQuiz 반환. 인증·DB·게시를 담당하지 않음(CONFIRMED) |
| 입력 확보 계층 | 콘텐츠 이용 권한·영상 식별·원문 확보. Grounding 도입 시 evidence 산출 및 eligibility 계약 적용 |
| GPT Judge | 도입 시 text-relative 평가와 실행 상태 제공. 승인·게시 결정을 대신하지 않음 |
| Creator | 검토한 revision에 대해 명시적으로 승인 또는 거절 |
| Benchmark | 품질 기준·실험 근거·실패와 provenance 정책 참고. 서비스 DB의 정본으로 사용하지 않음 |

Gemini Quiz를 BE와 Python 양쪽에서 같은 작업에 호출하지 않는다(PROPOSED).
다른 도메인 Entity·Repository로 상태를 직접 변경하지 않는 기존 규칙을 따른다(CONFIRMED).

## 생성 계약과 정책 차이

**CONFIRMED**: [QuizGenerationInput](../../../src/main/java/kr/co/cking/quiz/generation/QuizGenerationInput.java)은
`contentText`, `questionCount`, `optionCount`만 받는다. YouTube URL 확보는 호출자 책임이다.
[GeneratedQuiz](../../../src/main/java/kr/co/cking/quiz/generation/GeneratedQuiz.java)는 문항 목록과 `promptVersion`을 반환한다.
각 문항은 `question`, `options`, `correctOptionIndex`, `explanation`, `sourceEvidence`다.

| 항목 | BE 현재(CONFIRMED) | Benchmark Production(CONFIRMED) |
| --- | --- | --- |
| 문항·보기 | 요청 문항 1~10, 보기 2~6 | 3문항·4보기 |
| 입력 길이 | Java String.length() 기준 최대 50,000 | 별도 production 최대 크기 정책 미정 |
| 정답 | 0-based, 보기 범위 검사 | 0-based, 표시할 때만 +1 |
| prompt | `quiz-mcq-v1` | `production-quiz-v1` |
| 버전 누락 | Parser가 V1 기본값 적용; Gemini 생성기가 기대 버전 비교 | output gate가 정확한 버전 요구 |
| schema | 문항·보기 minItems/maxItems 요청 | runtime output gate로 실제 개수 확인 |
| 모델·추론 | 기본 gemini-3.8-flash; 현재 request에 thinking/output cap 설정 없음 | gemini-3.8-flash, medium, 8192 |
| HTTP | generateContent; 기본 최대 2 attempts, 429/5xx 계열 retry, timeout retry 없음 | 별도 Python transport와 명시적 runtime guard |
| 저장·추적 | 생성 결과 DTO; usage/cost·operation fingerprint는 이 DTO에 없음 | snapshot·attempt·fingerprint·hash·usage/cost 기록 |

근거: [Validator](../../../src/main/java/kr/co/cking/quiz/generation/QuizGenerationValidator.java),
[Parser](../../../src/main/java/kr/co/cking/quiz/generation/QuizResponseParser.java),
[RestClient](../../../src/main/java/kr/co/cking/quiz/generation/gemini/GeminiRestClient.java),
[Properties](../../../src/main/java/kr/co/cking/quiz/generation/gemini/GeminiQuizProperties.java).

**CONFIRMED**: 입력 오류(`QuizInputException`), JSON 오류(`QuizResponseParseException`),
결과 계약 오류(`QuizGenerationValidationException`), Provider 오류·timeout을 구분한다.
문자열 근거 포함과 구조 검사 통과는 의미적 정답 정확성 또는 영상 사실성을 보장하지 않는다.

**PROPOSED**: 기존 Parser·Validator를 서비스 경계에서 재사용한다. 검증 실패 결과를 게시 가능한 draft로 취급하지 않는다.
**DECISION REQUIRED**: 서비스 문항·보기 수, prompt 변경, 추론 설정, retry·timeout·비용 상한과 실패 보존 범위.
Benchmark의 실험 설정을 BE 운영 정책으로 자동 적용하지 않는다.

## 서비스 흐름과 상태 전이

다음은 **PROPOSED** 흐름이며 아래 상태 표현은 설명용이다. Java enum이나 API 명세가 아니다.

```text
Creator 인증·소유권 확인 → 입력 확보 → 생성 작업 접수 → QuizGenerator
→ Parser·Validator → draft 저장 → Creator 승인·거절 → 게시 → Fan 조회
```

| 상태 경계(PROPOSED) | 조건 |
| --- | --- |
| 요청 → 진행 | 권한·입력·중복 요청 검증 후 작업 식별자를 확정 |
| 진행 → 성공 | 검증된 출력과 입력 revision이 일치하면 draft를 저장 |
| 진행 → 실패 | 입력·Provider·parse·validation 실패를 구분; 승인·게시로 전이하지 않음 |
| 진행 → 결과 미확인 | timeout·중단은 요청 도달 여부가 불확실할 수 있음. 성공·실패로 단정하거나 자동 재호출하지 않음 |
| draft → 승인·거절 | 소유 Creator가 해당 revision을 명시적으로 검토 |
| 승인 → 게시 | 승인 대상 revision과 게시 payload가 정확히 일치 |
| 승인 후 수정 | 새 revision은 재승인 필요. 기존 승인으로 바뀐 내용을 게시하지 않음 |
| 게시 후 수정 | 검토 중 revision과 현재 공개 revision을 분리하거나 공개를 해제하는 정책 필요(DECISION REQUIRED) |

**PROPOSED**: Fan 목록·상세는 게시된 revision만 조회한다. draft·거절·승인만 된 상태는 노출하지 않는다.
정답·해설·evidence의 풀이 전 공개 시점과 Fan 접근 권한은 **DECISION REQUIRED**다.
거절 후 재생성은 자동 실행하지 않고 새 명시적 작업으로 다룬다(PROPOSED).
endpoint·HTTP 상태·동기/비동기 응답·취소·복구 방식은 **DECISION REQUIRED**다.

## 보안·멱등성·트랜잭션

**CONFIRMED**: [공통 API](../../common/api.md), [Auth 설계](../auth/README.md), [Auth API](../auth/api.md)를 따른다.
새 인증 API는 `@CurrentMemberId Long memberId`로 actor를 받고 body/query의 actor ID를 신뢰하지 않는다.
Creator 여부는 JWT role이 아니라 Creator 관계로 판단한다. Application/Domain은 JWT·SecurityContext에 직접 의존하지 않는다.
[SecurityConfig](../../../src/main/java/kr/co/cking/common/config/SecurityConfig.java)에 `/api/**` permitAll fallback이 있으므로
새 Quiz 쓰기·비공개 조회 경로를 명시적으로 보호해야 한다. 서비스에서도 리소스 소유권을 검사한다(PROPOSED).

**PROPOSED**:

- actor에서 Creator를 조회하고 대상 콘텐츠·Quiz의 creatorId와 대조한다. 임의 creatorId 입력으로 권한을 얻지 못한다.
- 사용자 입력 URL만으로 영상 소유권·이용 권한을 인정하지 않는다. 외부 입력 확보 시 허용 출처·URL 검증 정책을 정한다.
- 동일 actor·요청 키의 재전송은 같은 작업을 반환하고 payload가 다르면 거부한다. Provider 호출 전에 DB 고유 제약 등으로 작업을 원자적으로 점유한다.
- 현재 작업 상태를 조회할 수 있게 하고 중단된 요청을 무조건 재호출하지 않는다. 고아 작업 복구와 Provider retry 횟수는 팀 결정 후 구현한다.
- 작업 접수, 결과 저장, 승인, 게시를 짧은 transaction으로 분리한다. Provider 응답 대기 중 DB 잠금을 유지하지 않는다.
- 결과 저장 시 입력 revision을 재확인하고, 승인·게시 시 revision 또는 잠금으로 동시 변경을 거부한다.
- 정확한 contentText hash, 원천 식별자, 생성 조건·promptVersion, 생성 작업 ID, 출력 revision, 승인 actor·시각·대상 revision을 추적한다.
- videoId, 서비스 콘텐츠 ID, 생성 작업 ID는 서로 다른 역할로 관리한다. Benchmark runId를 서비스 PK로 그대로 사용하지 않는다.
- API key·헤더·Provider 오류 원문을 사용자 응답이나 일반 로그에 남기지 않는다.

**DECISION REQUIRED**: 서비스 ID 타입·테이블/FK, 요청 키 위치·보존 기간, hash 정규화 규칙,
실패 payload 보관·접근권한, usage/cost 수집 범위, DB locking 방식, transaction 실패 뒤 복구 정책.
새 API는 공통 응답 봉투·숫자형 업무 ID·오류 코드 배치 규칙을 따른다(CONFIRMED).

## 선택 기능과 결정 필요 사항

| 항목(DECISION REQUIRED) | 합의할 내용 |
| --- | --- |
| YouTube Grounding | MVP 포함 여부, 실행 주체, 영상 권한·입력 확보, eligibility·timestamp·실패 정책 |
| GPT Judge | 실시간 사용 여부, 모델·비용·timeout, 실패/uncertain/manual 승인 정책, 집계와 override 요구사항 |
| 비동기 생성 | 응답 대기 허용 시간, 작업 조회, executor/queue·복구 책임. 기존 async 구성 존재만으로 방식 확정하지 않음 |
| 미션·보상 | Quiz 풀이를 미션으로 연결할지, 정답 제출·중복 보상 계약. 현재 MissionType에는 Quiz가 없음 |

Grounding·Judge는 Benchmark에 구현됐다는 이유만으로 서비스 MVP의 필수 기능으로 확정하지 않는다.
Judge를 도입해도 text-relative 평가라는 한계와 Creator 최종 판단을 유지한다(PROPOSED).

## Fake 기반 테스트 완료 조건

아래는 후속 구현의 **PROPOSED** 완료 조건이며 이번 문서에서 테스트를 실행했다는 뜻이 아니다.

- FakeQuizGenerator로 입력 → draft → 승인 → 게시 → Fan 조회를 연결하고 Provider 호출 없이 검증한다.
- 무인증·다른 Creator·다른 소유 콘텐츠의 생성·검토·승인·게시·비공개 조회를 거부한다.
- 같은 요청과 동시 요청은 생성 호출·draft를 중복 만들지 않고, 같은 키의 다른 입력은 거부한다.
- 입력·Provider·timeout·파싱·결과 검증 실패 시 승인·게시 가능 결과가 남지 않는다.
- 거절·미승인·다른 revision으로 게시할 수 없고, 승인과 수정의 동시 실행도 gate를 우회하지 못한다.
- 수정한 revision은 재승인을 요구하고 Fan은 미게시 내용을 조회할 수 없다.
- 결과 저장 rollback·작업 중단은 상태로 구분하며 확인 없이 Provider를 다시 호출하지 않는다.
- 기존 Core/Adapter 테스트를 유지하고 사용 시 Grounding·Judge 오류도 fake로 검증한다.

## 후속 Issue 분리

모두 **PROPOSED** 후보다. 팀 결정과 기존 작업 중복을 확인한 뒤 생성한다.

| 순서·후보 | 선행 조건 | 완료 조건·예상 영역 |
| --- | --- | --- |
| 1. Quiz draft 저장·승인·게시·조회 | #514 정책 합의 | domain/application/presentation/repository와 Flyway, API 정본. Fake draft와 권한·revision·게시 gate 검증 |
| 2. 기존 생성기와 서비스 작업 연결 | 1 및 생성 정책 합의 | QuizGenerator 주입, 요청 점유·상태·실패 저장. Parser/Adapter 중복 구현 없이 fake 전체 흐름 검증 |
| 선택. YouTube Grounding 연결 | 도입·실행 위치·입력 권한 합의 | 입력 확보·eligibility·원천 추적과 fake 실패 경로 |
| 선택. GPT Judge 연결 | 도입 정책 및 Quiz revision 계약 | 평가·집계·Creator 자료 연결. 원결과 보존과 text-relative 범위 검증 |

가장 먼저 위 결정표를 팀과 합의한다. 독립적으로 검증 가능한 관심사별로 Issue를 나누며,
분리하면 정상 동작하지 않는 변경은 하나로 묶고 근거를 PR에 기록한다.
구현 PR은 조직 CONTRIBUTING에 따라 develop 대상으로 올리고 Issue 연결·리뷰 승인을 거친다.

## 추가 근거

- [Creator Entity](../../../src/main/java/kr/co/cking/creator/domain/Creator.java), [Creator Repository](../../../src/main/java/kr/co/cking/creator/repository/CreatorRepository.java)
- [Post Service](../../../src/main/java/kr/co/cking/post/application/CreatorPostService.java), [Post API](../post/api.md)
- [Creator YouTube 채널 Service](../../../src/main/java/kr/co/cking/subscriptionverification/application/CreatorYoutubeChannelService.java)
- [DB 정본](../../../src/main/resources/db/migration/), [Core 테스트](../../../src/test/java/kr/co/cking/quiz/generation/)
- [조직 협업 규칙](https://github.com/URECA-Cking/.github/blob/main/CONTRIBUTING.md)

## Fan 응시 구현 (#535)

**CONFIRMED**: JWT 본인 제출·서버 채점·불변 결과 조회는 [응시 API](attempt-api.md)를 따른다. 공개 조회에는 정답·해설·근거를 포함하지 않는다.
**DECISION REQUIRED**: 실제 Ticket 지급·Outbox 연동은 후속 범위이며 저장된 rewardRequestId를 재사용해야 한다.
