# Issue #529 구현·검증 목록

요구사항 정본은 [Issue #529](https://github.com/URECA-Cking/Cking-BE/issues/529), API 계약은 [Judge API](judge-api.md)다. 이 목록은 구현과 테스트 위치를 연결하며 실제 Provider 성공이나 CI 실행 완료를 뜻하지 않는다.

코드는 `src/main/java/kr/co/cking/quiz/`, 테스트는 `src/test/java/kr/co/cking/quiz/` 아래다. 표의 파일·클래스 이름은 해당 패키지에서 찾는다. `구현됨 / CI 대기`는 정적 구현과 테스트 작성은 확인했지만 MySQL 런타임 증거가 필요하다는 뜻이다.

| # | 완료 조건 | 구현 | 검증 테스트 | 상태 |
| --- | --- | --- | --- | --- |
| 1 | Responses strict schema·문항·7개 항목·reason·refusal/incomplete 구분 | `judge/openai/OpenAiQuizJudge`, Parser·Validator·Prompt | OpenAiQuizJudgeTest, QuizJudgeResponseParserTest, QuizJudgeEvaluationValidatorTest | 구현됨, focused 검증 |
| 2 | 검증된 저장 입력만 전달·무결성 실패 시 0 호출 | QuizRevisionReader, Store.loadInput, Worker | QuizRevisionReaderTest, QuizJudgeEvaluationStoreTest, QuizJudgeWorkerTest | 구현됨, focused 검증 |
| 3 | series·Job·attempt·result identity/hash 연결 | domain Judge Entity, V58, Store | QuizJudgeEvaluationStoreTest, QuizJudgeEvaluationIntegrationTest | 구현됨 / CI 대기 |
| 4 | SUCCEEDED와 집계 분리·fail/uncertain 보존 | JudgeAggregation, Worker·Store | QuizJudgeWorkerTest, QuizJudgeWorkerIntegrationTest | 구현됨 / CI 대기 |
| 5 | timeout·중단 UNKNOWN·자동 재호출 없음·늦은 결과 차단 | Adapter·Worker, Store.completeRunning | OpenAiQuizJudgeTest, QuizJudgeWorkerTest, StoreTest, WorkerIntegrationTest | 구현됨 / CI 대기 |
| 6 | requestId replay·활성 Job 1개·잠금 attempt 할당 | Service·Store, V58 UNIQUE | ServiceTest, StoreTest, EvaluationIntegrationTest | 구현됨 / CI 대기 |
| 7 | 요청·조회 시 stale PENDING/ RUNNING 정리 | Service.refreshed, Store.expireStale | ServiceTest, EvaluationIntegrationTest, WorkerIntegrationTest | 구현됨 / CI 대기 |
| 8 | 기술적 재평가만·최대 2회·완료/승인 재평가 금지 | Store.create, QuizJudgeSeries | StoreTest, EvaluationIntegrationTest | 구현됨 / CI 대기 |
| 9 | 모든 항목 pass만 승인·무결성/기준 미달 차단 | StoredJudgeQuizApprovalPolicy, ResultReader | StoredJudgeQuizApprovalPolicyTest, CreatorQuizCommandServiceTest | 구현됨, focused 검증 |
| 10 | 운영 deny-all 교체·승인 result ID/hash 고정 | ApprovalPolicy, CreatorQuizCommandService, Decision | QuizApprovalPolicyBeanTest, CreatorQuizCommandServiceTest, ApprovalFlowIntegrationTest | 구현됨 / CI 대기 |
| 11 | 고정 result ID/hash·저장 정책 검증·운영 설정 독립 | Policy.requireStillApproved, ResultReader | StoredJudgeQuizApprovalPolicyTest, ApprovalFlowIntegrationTest | 구현됨 / CI 대기 |
| 12 | 명시적 승인·재요청/재게시/거절/취소 유지 | CreatorQuizCommandService, Quiz | CreatorQuizCommandServiceTest, QuizTest, ApprovalFlowIntegrationTest | 구현됨 / CI 대기 |
| 13 | feature OFF 신규만 차단·기존 근거 사용 | EvaluationService, 운영 Policy | ServiceTest, WorkerIntegrationTest, ApprovalFlowIntegrationTest | 구현됨 / CI 대기 |
| 14 | Provider 호출 중 TX 없음·기존 draft 보존 | Worker의 Store 호출과 Provider 호출 분리 | WorkerTest, WorkerIntegrationTest, QuizJudgeFlowIntegrationTest | 구현됨 / CI 대기 |
| 15 | 401/403/404·공개 DTO 비노출 | CreatorQuizJudgeController, SecurityConfig, Service | CreatorQuizJudgeControllerTest, SecurityConfigTest, ServiceTest, PublicQuizControllerTest | 구현됨, focused 검증 |
| 16 | usage·추정 비용·민감 원문 비노출 | QuizJudgeExecution, Adapter, Job/JobCall | OpenAiQuizJudgeTest, StoreTest, WorkerTest, WorkerIntegrationTest | 구현됨 / CI 대기 |
| 17 | startup 검증·초기 설정 명시 | OpenAiJudgeProperties, EvaluationProperties·Configuration | OpenAiJudgeConfigurationTest, QuizJudgeEvaluationConfigurationTest, README | 구현됨, focused 검증 |
| 18 | Fake/Mock·DB 제약·TX·경쟁 검증 | focused 테스트 및 CI 전용 테스트 | EvaluationIntegrationTest, WorkerIntegrationTest, ApprovalFlowIntegrationTest, QuizJudgeFlowIntegrationTest | focused 검증 / CI 대기 |
| 19 | migration 번호 확인·decision→result FK | V58, composite FK | ApprovalFlowIntegrationTest, EvaluationIntegrationTest | 번호 충돌 해소 / CI·배포 검증 대기 |
| 20 | API·README·인덱스·설계 상태 갱신 | api.md, judge-api.md, README.md, integration-design.md, 공통 인덱스 | 상대 링크·길이·whitespace 검사 | 문서 갱신 |
| 21 | 생성→평가→승인→게시 Fake 연결 | GenerationService → EvaluationService → CommandService | QuizJudgeFlowIntegrationTest (모든 Provider Fake) | 작성·컴파일 / CI 대기 |

## 로컬 검증 경계

Judge 계약/Adapter Mock HTTP, Evaluation Service·Store·Worker·Dispatcher·승인 정책, 직접 관련 Quiz 단위·WebMvc/security 테스트만 클래스 이름을 명시해 실행한다. `compileTestJava`는 CI 전용 테스트도 컴파일하지만 실행하지 않는다.

`--tests '*Quiz*'` 같은 넓은 패턴, 전체 `test`, MySQL/DB 및 `@SpringBootTest` 테스트는 로컬에서 실행하지 않는다. 실제 OpenAI/Gemini·Benchmark Pilot 호출도 금지한다.

## CI와 운영 확인

### Migration 선행 조건

PR #527은 develop에 병합됐으며 `V57__add_member_profile_image.sql`을 사용한다. #529의 Judge migration은 최신 develop의 최대 버전 V57 다음인 `V58__add_quiz_judge.sql`로 옮겨 번호 충돌을 해소했다. Member migration과 Judge SQL 내용은 변경하지 않았다.

병합 전 최신 develop의 migration 번호 중복을 다시 확인한다. 배포 전에는 기존 DB에 V57까지 적용됐는지와 Judge V57이 따로 적용된 이력이 없는지 확인한 뒤 V58을 적용한다. MySQL CI의 migration·FK·CHECK·UNIQUE 검증과 기존 배포 DB 적용 이력 확인이 끝나기 전까지 완료 조건 19는 **CI/배포 검증 대기**다.

### MySQL 검증

- EvaluationIntegrationTest: V58, FK/UNIQUE/CHECK, attempt·replay 경쟁, rollback, stale/완료 저장 경쟁.
- WorkerIntegrationTest: 실제 Executor·DB 경계, deadline 잠금 대기, late execution record, timeout·설정 변경·기능 OFF.
- ApprovalFlowIntegrationTest: 실제 결과 기반 승인·게시·재게시, 다른 revision FK, 운영 모델·pricing 변경과 OFF.
- QuizJudgeFlowIntegrationTest: Fake Grounding·Generator·Judge로 생성부터 공개 조회까지 연결, Provider 호출 TX 비활성·중복 호출 없음.
- QuizProductionPolicyIntegrationTest: 운영 Bean, 평가 없음/기준 미달 차단, 기본 저장 제약.

이 테스트들은 CI 결과 확인 전까지 PASS로 표시하지 않는다. 실제 Provider E2E는 별도의 운영 검증이며 Fake/Mock 통과로 대체하지 않는다. 기능 활성화 전 접근 권한·quota·비용·최대 입력 지연·평가 품질을 확인한다.
