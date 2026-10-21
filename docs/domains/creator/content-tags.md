# Creator AI 콘텐츠 태그

정본 소개는 `creator_space.intro_text`다. 자동 분류는 콘텐츠 설명 보조 자료이며, 본인 확인·사람 정답·관리자 검수와 구분한다.
세부 태그는 추천 점수와 회원 관심사를 변경하지 않는다. FE 구현은 이 범위에 포함하지 않는다.

## 상태와 저장

V84는 자동 결과, 본인 확인 이력, 현재 포인터, 승인 taxonomy/config 및 내구성 작업을 별도로 만든다.
기존 V68 회원 선택 taxonomy를 조회하거나 상위 코드 집합과 비교하지 않는다. 콘텐츠 snapshot 자체의
코드·부모 관계·버전·해시만 검증하며 초안 85개를 운영 시드로 승격하지 않는다.
초기 `creator_content_taxonomy`는 **비어 있다**. 운영 승인·등록 없이는 입력/적재/확인은 409,
공개는 `disabled`와 빈 배열이다. 테스트의 합성 taxonomy 승인과 운영 승인은 별개다.

Space의 `content_revision`은 정규화한 소개가 달라질 때만 증가한다. 프로필 이미지나 canonical 동등 문자열만
바뀌면 증가하지 않는다. Space 생성/프로필 수정 Service가 `CreatorContentOutbox`로 같은 DB 트랜잭션에 작업을 기록한다.
`Propagation.MANDATORY`로 트랜잭션 외 발행을 차단하며 DB trigger/SUPER 권한은 필요하지 않다.
롤백하면 소개와 작업이 함께 롤백되고, 모델 호출은 저장 요청에 포함되지 않는다.

자동 결과는 `(creatorId,inputRevision,configHash)`당 하나다. 멱등 키/자동 revision은 64자리 hex다.
동일 Creator·키·payload는 소개/설정이 바뀐 뒤에도 `idempotent`다. 이 응답은 현재 포인터나 본인 확인을
변경하지 않는다. 동일 키의 다른 payload 또는 같은 입력·설정의 다른 키는 409다.
Space 행 잠금 안에서 현재 revision/hash 및 활성 설정을 검증하고 이력과 현재 포인터를 기록한다.
본인 확인도 같은 잠금으로 검사한다. 소개 수정에 뒤처진 **새 적재**/확인은 409다.
공개 GET은 Space/taxonomy 잠금 없이 읽기 전용 REPEATABLE READ snapshot으로 조회한다.
전체 보정은 입력 형식·정렬을 먼저 검사하고 Creator마다 별도 트랜잭션으로 잠금을 해제한다.
중간 실패 전에 완료된 Creator는 유지되며 전체 요청 재시도로 나머지를 보정할 수 있다.

- 최초: `processing`. 자동 성공/빈 결과: `unconfirmed`/`unclassified`.
- 본인 확인: `creator_confirmed`. 근거와 내부 출처를 제외한 코드만 공개한다.
- 소개 수정/활성 설정 변경: 현재 자동 결과와 달라지면 `reanalysis_required`로 비공개 전환한다.
- 같은 입력의 성공 재적재는 본인 확인을 해제하지 않는다. 동일 소개·동일 taxonomy에서 새 설정의
  자동 결과가 도착해도 기존 본인 확인 태그를 보존한다. 과거 이력은 삭제하지 않는다.
- 공급자/네트워크 실패는 빈 결과 적재 요청이 아니다. 내 조회 `processing` 필드에서 작업 상태·시도 수·
  저장/시작/완료/재시도 시각을 확인한다. `FAILED`는 수동 재등록 대상이다.

## 인증과 API

공통 ApiResponse 봉투를 사용한다. 숫자 ID는 JSON number다. 입력에서 사용자 ID를 받지 않는다.

| 메서드·경로 | 인증 | 응답/행동 |
| --- | --- | --- |
| GET `/api/internal/content-tags/inputs` | 전용 opaque Bearer | 전체 일관 snapshot, 최초/변경 포함 |
| PUT `/api/internal/content-tags/creators/{id}/automatic` | 전용 Bearer | 자동 분류 멱등 적재 |
| PUT `/api/internal/content-tags/jobs/reconcile` | 전용 Bearer | 전체 성공 후 현재 PENDING/FAILED 세대 완료 |
| PUT `/api/internal/content-tags/jobs/claim` | 전용 Bearer | lease 작업 하나 또는 빈 객체 |
| PUT `/api/internal/content-tags/jobs/{id}/ack` | 전용 Bearer | 세대·토큰·만료 검증 후 완료/재시도 |
| PUT `/api/internal/content-tags/jobs/{id}/renew` | 전용 Bearer | 동일 세대 lease 900초 연장 |
| PUT `/api/internal/content-tags/jobs/{id}/retry` | 전용 Bearer | RUNNING 외 작업 수동 재등록 |
| GET `/api/creator/content-tags` | Member JWT + Creator 소유권 | 내 자동 결과/확인/처리 상태 |
| PUT `/api/creator/content-tags/confirmation` | Member JWT + Creator 소유권 | 현재 자동 revision 확인 또는 허용 코드로 수정 |
| GET `/api/creators/{id}/content-tags` | 공개 | 유효한 본인 확인 코드만 반환 |

전용 토큰은 SHA-256 hex로 `cking.content-tags.token-hash`에 설정한다. 일반 ADMIN JWT나 추천 적재 키로는
내부 콘텐츠 API를 호출할 수 없으며, 콘텐츠 토큰도 일반 관리자/본인 API 권한을 갖지 않는다.
토큰과 원문은 로그/알림에 남기지 않는다. 잘못된/미설정 토큰은 401이다.

## 공용 wire 계약 content-tags-v1

CRLF/CR→LF, Unicode NFC, trim 없이 UTF-8 바이트 SHA-256을 사용한다. null은 빈 문자열이다.
공용 fixture `src/test/resources/fixtures/content-tags-v1.json`은 LLM `fixtures/content-tags-v1.json`과 같다.
로컬 분류 record.input_hash는 JSON 문자열의 digest이며 wire inputHash와 구별한다.
JSON 제어문자 escape의 hex는 소문자를 사용한다. 유효한 surrogate pair/이모지는 UTF-8로 보존하고
짝 없는 surrogate는 해시 생성 시 거부한다. 공용 fixture는 제어문자·이모지·NFC·비정상 surrogate를 포함한다.

입력 응답: `contractVersion`, `taxonomyApproved`, `taxonomyHash`, `generationConfigHash`, `items`, `nextCursor:null`.
LLM은 generationConfigHash가 자기 fingerprint와 같은지 확인한 뒤에만 공급자를 호출한다.
각 입력: `creatorId`, `introduction`, `inputRevision`, `inputHash`. 각 행을 한 번 정규화한 결과로 본문과 해시를 만든다.
현재는 전체 일관 snapshot 하나를 반환한다. 페이지별 시점이 섞이는 단순 cursor paging은 도입하지 않는다.
규모 확대 시 별도 snapshot 식별자를 가진 페이지 계약이 필요하다.

적재 요청: `creatorId`, `inputRevision`, `inputHash`, `automaticRevision`, `idempotencyKey`,
`createdAt`(ISO UTC), `origin:automatic`, `creatorConfirmed:false`, `provenance`, `record`.
provenance는 LLM ContentWorker.fingerprint와 동일하며 contract/model/prompt/schema/policy/taxonomy/criteria를
버전·해시로 묶는다. 승인 configHash는 provenance의 키 정렬 canonical JSON SHA-256이다.
record는 분류 원본 후보·선택 결과·taxonomy snapshot·출처를 보존한다. snapshot/criteria와 provenance를
대조하고, 상위 최대 3개·상위당 세부 최대 3개·중복·부모 연결·원문 연속 근거를 검증한다.

적재 응답: `{status:applied|idempotent, automaticRevision}`.
본인 확인 요청: `{automaticRevision,inputHash,parents:[상위 코드],topics:[세부 코드]}`.
자유 코드·부모 불일치·중복·상한 위반은 400 `INVALID_CONTENT_TAG`다.
본인이 아닌 Creator는 자기 결과만 조회/수정할 수 있어 타인 자동 revision을 확인하면 409다.
Creator가 아닌 회원은 403, 없는 Space는 404다.

오래된 입력/실행은 409 `CONTENT_INPUT_CONFLICT`, 멱등 payload 충돌은 `CONTENT_PAYLOAD_CONFLICT`,
미승인 taxonomy는 `CONTENT_TAXONOMY_UNAVAILABLE`, 오래된 lease는 `CONTENT_LEASE_CONFLICT`다.
409는 같은 오래된 payload를 반복하지 않고 입력/작업을 다시 수집한다. 401/403은 설정 복구 후 재실행한다.
네트워크/5xx는 저장한 동일 payload·키로 재시도한다.

## 승인·배포·복구

1. 운영 승인된 taxonomy의 버전·canonical BE hash·criteriaHash·snapshot과 코드 부모 관계를 검토한다.
2. 선택 모델에 대한 LLM fingerprint의 configHash를 산출한다. 버전·hash를 기존 실행에서 추론하지 않는다.
3. DBA가 `creator_content_taxonomy`에 비활성 행을 등록한다. `parent_codes`, `topic_parents`,
   `taxonomy_snapshot`과 모든 hash를 동일 검토본에서 생성한다. 서비스도 snapshot과 hash를 재검증한다.
4. 작업자를 중지하고 단일 DB 트랜잭션에서 기존 active를 해제하고 승인 행을 approved/active로 전환한다.
   configHash는 실행 순서 장벽이다. 오래된 설정은 409가 된다. 새 설정으로 재시작하고 일일 보정을 실행한다.
5. token-hash는 환경변수 `CKING_CONTENT_TAG_TOKEN_HASH`로 넣고(개발은 `/cking/dev/` 필수 값, 운영은 `/cking/prod/`에 등록된 경우에만), LLM에는 평문 전용 토큰을
   `CKING_CONTENT_TAG_ACCESS_TOKEN` 시크릿으로 전달한다. 추천 키와 별도로 회전한다.

설정/코드 변경 시 LLM 캐시·payload·application ledger를 임의 삭제하지 않는다.
BE DB를 복원하면 LLM ledger와 결과 포인터를 대조하고 새로운 적용 번호로 보정한다.
롤백은 작업자 중지→이전 설정 복귀이며 자동/확인 이력을 지우지 않는다.

작업 claim은 `FOR UPDATE SKIP LOCKED`로 하나를 선점한다. lease 900초, LLM은 60초마다 연장한다.
수정 커밋은 generation을 증가시키고 최신 작업을 PENDING으로 남긴다. 이전 토큰/세대의 ack/renew는
이를 소비하지 못한다. 프로세스 재시작/중복 전달은 lease 만료 후 재전달한다. 최대 5회 시도 후
FAILED 및 오류 로그로 알리고 `/retry`로 수동 재등록한다. claim은 소진 작업을 FAILED로 전환한 뒤
같은 요청에서 다음 후보를 계속 선점한다. backoff는 60초부터 최대 1시간이다.
`completed_at`은 성공 완료 때만 기록하고 retry/FAILED ack와 수동 재등록에서는 NULL로 초기화한다.

LLM의 정기/수동/이벤트 실행은 같은 파일 잠금·캐시·체크포인트를 쓴다. 잠금 충돌 전에 작업을 claim하지 않는다.
일일 전체 스캔은 유실 보정이며 추천/태그가 모두 성공해야 이벤트를 완료한다.
목표 지연은 변경→시작 p95 2분, 변경→완료 p95 15분이다. `creator_content_job`의 committed_at,
claimed_at, completed_at, attempt_count, next_retry_at, status로 지연/재시도/최종 실패를 집계한다.
긴 실행 중 연속 수정 시 현재 작업이 supersede되고 다음 polling에서 최신 소개를 처리한다.

## 검증

서비스/MVC/공용 hash fixture 테스트와 LLM 소유 실제 HTTP/MySQL E2E를 실행한다.
BE 기본 test source set의 `ContentTagReviewIntegrationTest`는 apply/코드·근거 검증/confirm/ack/
reconcile 및 실제 동시 트랜잭션의 조회·보정 잠금 범위를 검사하므로 BE CI에서도 실행된다.
전역 claim 반복은 Repository 후보를 제어하는 단위 테스트로 검증한다. DB lease/retry 회귀는
자기 fixture만 대상으로 하며 기존 PENDING 작업을 선점하거나 큐를 일괄 삭제하지 않는다.
E2E 명령: LLM 저장소에서 `python -m src.recommendation.e2e.runner --suite content --be-dir ../Cking-BE`.
전용 MySQL 8.4·Redis 7.2, 임시 자격 증명, loopback HTTP, 합성 provider와 격리 테스트 taxonomy만 사용한다.
결과는 LLM results/recommendation/e2e/<run>/report.json에 저장한다.
이 검증은 실제 모델 의미 품질·사람 정답·독립 holdout·운영 taxonomy 승인·운영 systemd 배포 검증이 아니다.

## 실제 검증 결과 (2026-10-19)

BE 코드 `e39f86e`, LLM 코드 `ebb338d`로 실제 HTTP/MySQL E2E **15/15** 통과 및 컨테이너 정리를 확인했다.
[LLM 공개 검증 집계](https://github.com/URECA-Cking/Cking-LLM-Benchmark/blob/f2b4622/data/summaries/content-tags-http-e2e.json)에 실행 ID, 코드 SHA, 시나리오별 결과를 보존한다.
BE 관련 회귀 77개와 bootJar, Python 회귀 518개가 통과했다. 외부 모델 호출은 0이다.
기존 프로필 API의 공백 불가 계약을 유지하며 빈 소개 분류는 레거시 DB fixture로 검증했다.
전체 BE suite·운영 systemd 배포·실제 모델 의미 품질·사람 검수·독립 holdout은 수행하지 않았다.
