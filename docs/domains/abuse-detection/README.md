# 비정상 행동 탐지 v1

Mission 완료와 Event 응모의 여러 요청에 걸친 패턴을 탐지해 근거를 남긴다. 개별 요청의 허용 여부는 기존 Mission·Entry 책임이며, v1은 자동 차단·Rate Limit·점수제·IP/Device/인증 실패·Creator Farming을 하지 않는다.

## 범위와 경계

관찰 대상은 인증된 사용자의 다음 POST 네 개뿐이다.

| actionType | API | resource 확인 |
| --- | --- | --- |
| `MISSION_COMPLETE` | `POST /api/creators/{creatorId}/missions/{missionId}/complete` | Member·Creator Mission |
| `MISSION_COMPLETE` | `POST /api/creators/{creatorId}/missions/share/complete` | Member·Creator·SHARE Mission |
| `MISSION_COMPLETE` | `POST /api/missions/{missionId}/complete` | Member·Common Mission |
| `EVENT_ENTRY` | `POST /api/events/{eventId}/entries` | Event |

관찰은 인증·Controller validation·대상 resource 확인이 모두 성공한 뒤 시작한다. 따라서 인증/인가 실패, 형식·범위 validation 실패, Member·Mission·Event 미존재는 Observation을 만들지 않는다. 조회 API와 구독 인증 제출은 v1 범위 밖이다.

각 Use Case는 resource 확인 직후 `requestedAt`을 기록하고, 비즈니스 결과가 확정된 직후 `observedAt`을 기록한다. 두 값은 서버 UTC `Instant`다. Mission/Entry는 context와 최종 결과만 Abuse 모듈에 넘긴다. Redis 조작, rule 판정, cooldown, DB 저장은 이 모듈의 책임이며 HTTP Filter로 모든 요청을 가로채지 않는다.

`AbuseObservationEvent`는 다음 필드를 가진다. `observationId`는 요청 시도마다 서버가 새로 만든 UUID이며 ZSET member로 사용한다. 같은 `requestId`의 재전송도 서로 다른 Observation이다.

```text
observationId, userId, actionType, requestId, resultCode, resultClass,
creatorId?, eventId?, missionId?, periodKey?, balanceScope,
businessKey?, requestedAt, observedAt
```

`balanceScope`는 요청의 `couponType` 또는 Mission 보상 경로에서 도출한다. 표현은 `CREATOR:{creatorId}` 또는 `COMMON`이며, `userId + balanceScope`가 하나의 잔액 풀이다. `COMMON`은 creatorId를 갖지 않고 `CREATOR`는 양수 creatorId를 필수로 갖는다.

## 결과 분류

| class | 코드 | 처리 |
| --- | --- | --- |
| `NEW_SUCCESS` | `EARN_ACCEPTED`, `SUCCESS` | request/성공 sequence와 EARN-SPEND에 사용 |
| `REPLAY` | `ALREADY_PROCESSED`, `DUPLICATE_REPLAY` | 모든 request burst, failure, EARN-SPEND 집계에서 제외 |
| `BUSINESS_FAILURE` | `DUPLICATE_MISSION`, `REQUEST_ID_CONFLICT`, `MISSION_INACTIVE`, `INSUFFICIENT_BALANCE`, `EVENT_NOT_OPEN`, `EVENT_CLOSED`, `IDEMPOTENCY_CONFLICT` | 해당 rule의 feature에만 사용 |
| `SYSTEM_FAILURE` | `GATE_NOT_LOADED`, `BALANCE_NOT_LOADED`, `BALANCE_MAINTENANCE`, `SYSTEM_ERROR`, `EARN_PROCESSING_FAILED`, `EARN_STATUS_UNKNOWN` | 사용자 탐지에서 완전히 제외 |

`MISSION_NOT_FOUND`, `MISSION_REQUIRES_VERIFICATION`, `RESOURCE_NOT_FOUND`, `VALIDATION_FAILED`, `INVALID_TICKET_COUNT`는 v1 business failure가 아니다.

## Business Key와 Signal

Mission만 다음 business key를 생성한다. 신규 Mission Type은 reward policy와 key를 이 문서에 추가하기 전까지 `REQUEST_ID_ROTATION` 대상이 아니다.

| Mission | key |
| --- | --- |
| Creator LIKE DAILY | `MISSION:CREATOR:DAILY:{userId}:{creatorId}:{missionId}:{yyyy-MM-dd}` |
| Creator SHARE ONCE | `MISSION:CREATOR:ONCE:{userId}:{creatorId}:{missionId}` |
| Common ATTENDANCE DAILY | `MISSION:COMMON:DAILY:{userId}:{missionId}:{yyyy-MM-dd}` |

`REQUEST_ID_ROTATION`은 이 key별 **rotation sliding window** 안의 distinct `requestId`가 threshold 이상인 **Signal**이다. 단독 Detection row를 만들지 않으며 Entry에는 적용하지 않는다. business key는 Redis key에 넣지 않고 SHA-256 hex hash만 쓴다. Evidence에는 userId가 포함된 원문 key 문자열을 중복 저장하지 않고 creatorId·missionId·periodKey 등 구조화된 Scope만 기록한다. `periodKey`는 DAILY Mission의 audit context이며 Business Key에도 포함된다. SHARE ONCE는 `periodKey=null`이고 Business Key에도 날짜를 포함하지 않는다.

`MissionBusinessKeyFactory`의 `periodAt`에는 해당 Mission이 EARN `periodKey`를 확정할 때 사용한 시각을 전달한다. 요청 시작 시각과 EARN 시각이 UTC 자정을 사이에 두고 달라도 DAILY 키가 실제 보상 기간과 일치해야 한다. SHARE가 Ticket EARN에 전달하는 날짜는 이 Abuse ONCE 키·Observation `periodKey`에 사용하지 않는다. Event 응모의 `BalanceScope`는 요청에서 확정한 `couponType`으로 선택하며, CREATOR일 때만 Event 소유 Creator의 잔액을 사용한다.

## Detection과 Feature

| abuse type | scope | feature / 판정 |
| --- | --- | --- |
| `MISSION_REQUEST_BURST` | user | `MISSION_COMPLETE` 중 replay/system이 아닌 요청의 sliding count ≥ N |
| `DUPLICATE_MISSION_BURST` | business key | `DUPLICATE_MISSION` sliding count ≥ N |
| `ENTRY_REQUEST_BURST` | user + event | `EVENT_ENTRY` 중 replay/system이 아닌 요청의 sliding count ≥ N |
| `INSUFFICIENT_BALANCE_BURST` | user + balance scope | 부족 잔액 sliding count ≥ N 또는 연속 count ≥ N |
| `REQUEST_ID_ROTATION` | mission business key | rotation window 안의 distinct requestId ≥ N; Signal only |
| `RAPID_EARN_AND_SPEND` | user + balance scope | 같은 scope의 `EARN_ACCEPTED` 뒤 `SUCCESS`가 D 이내인 pair sliding count ≥ N |
| `FAILURE_BURST` | user | business failure sliding count ≥ N 또는 연속 count ≥ N |

같은 Balance Scope의 새 EARN 또는 Entry 성공은 insufficient 연속 실패를 끊는다. 새 성공은 전체 failure 연속 상태를 끊고, replay/system failure는 연속 상태를 늘리거나 끊지 않는다. rapid pair는 하나의 최근 EARN을 한 번의 SPEND에만 연결한 후 EARN 상태를 지운다. 단일 pair는 Detection하지 않는다.

Composite rule은 별도 row가 아니라 기존 Detection Evidence를 강화한다: `RULE-01` duplicate+rotation, `RULE-02` mission burst+rotation, `RULE-03` entry burst+(insufficient 또는 failure), `RULE-04` rapid pair+(mission 또는 entry burst), `RULE-05` failure burst+distinct failure type ≥ M. 동시에 충족한 모든 rule을 기록한다.

## Redis와 Lua

Redis는 실시간 feature와 cooldown만 보관하고 Observation이나 최종 Detection을 영속화하지 않는다. 모든 key prefix는 `abuse:v1:`이다.

| key | type | TTL |
| --- | --- | --- |
| `mission-request:{userId}`, `duplicate-mission:{businessKeyHash}`, `entry-request:{userId}:{eventId}`, `insufficient:{userId}:{balanceScope}`, `rapid-earn-spend:{userId}:{balanceScope}`, `failure:{userId}` | ZSET, score=timestamp ms/member=observationId | 각 Window + 60초 |
| `request-id:{businessKeyHash}` | ZSET, score=observedAt epoch ms/member=requestId | rotation window + 60초 |
| `last-earn:{userId}:{balanceScope}` | STRING, observedAt | rapid maxDelay |
| `failure-type:{userId}` | HASH, field=실패 코드·value=마지막 관찰 epoch ms | failure window + 60초 |
| `failure-sequence:{userId}` | HASH, fields=`count`, `lastObservedAtEpochMs`; 전체 업무 실패 연속 상태 | failure window + 60초 |
| `insufficient-sequence:{userId}:{balanceScope}` | HASH, fields=`count`, `lastObservedAtEpochMs`; 해당 잔액 범위의 부족 잔액 연속 상태 | insufficient window + 60초 |
| `cooldown:{abuseType}:{scopeHash}` | STRING | primary Window × 2 |

`scripts/abuse-sliding-window-count.lua`는 `KEYS[1]` ZSET과 `observedAt epoch ms`, `member`, `window ms`, `window + 60초` TTL 초를 받아 `ZADD → ZREMRANGEBYSCORE → ZCARD → EXPIRE`를 원자 수행해 count를 반환한다. 일반 window 호출자는 `observationId`, rotation 호출자는 `requestId`를 member로 전달한다. rotation은 같은 requestId를 member로 쓰므로 재시도는 distinct count를 늘리지 않고, 새 requestId는 해당 window에서만 집계된다. `RedisAbuseFeatureStore`는 `abuse-feature-store-record.lua` 한 번으로 이 Window, 연속 상태, 최근 EARN과 실패 유형을 함께 갱신하고 그 직후 값을 Snapshot으로 반환한다. 연속 상태는 각각 `failure-sequence`와 `insufficient-sequence`에 분리해 갱신한다. 두 HASH의 `count`는 현재 연속 횟수이고 `lastObservedAtEpochMs`는 마지막 집계 시각이다. `failure-type` HASH는 각 실패 코드의 마지막 관찰 시각을 보관하고 현재 failure window 밖 field를 제거해 distinct type count를 계산한다. 해당 Rule의 관찰·성공으로 상태가 갱신될 때마다 각 Rule의 TTL을 다시 설정하며, 전체 성공은 `failure-sequence`를, 같은 Balance Scope의 EARN 또는 Entry 성공은 해당 `insufficient-sequence`만 제거한다. `REPLAY`와 `SYSTEM_FAILURE`는 Feature Store가 어떤 Redis key도 갱신하지 않는다. cooldown은 필요한 key를 한 호출에서 원자적으로 처리한다. cooldown은 `SET NX EX`; 획득한 요청만 DB를 insert하며 insert 실패 시 key를 best-effort 삭제한다.

### Scope Hash 공통 계약

Redis Key에는 Business Key와 Cooldown Scope의 원문을 넣지 않는다. 각 canonical value를 UTF-8 바이트로 인코딩한 뒤 SHA-256 digest의 lowercase hex(64자)로 변환한다. `AbuseScopeHash.fromCanonicalValue()`가 이 변환과 null·blank 차단의 단일 구현이며, Mission Business Key Hash와 DetectionResult의 Cooldown Scope Hash는 모두 이 계약을 사용한다. 같은 canonical value는 항상 같은 hash를, 서로 다른 canonical value는 서로 다른 hash를 사용한다.

`ticket-earn.lua`, `common-ticket-earn.lua`, `entry-spend.lua`는 수정하지 않는다. 이들은 멱등성·잔액·Stream의 기존 책임만 가지며 Abuse는 별도 script와 key 공간을 사용한다.

## 공통 Domain과 Port 계약

공통 코드는 `abuse.domain`, `abuse.application.model`, `abuse.application.port`에 둔다. Detection row를 만드는 유형은 `AbuseType`, 단독 row를 만들지 않는 보조 신호는 `AbuseSignal`로 분리한다. `AbuseObservationEvent`는 원 업무가 확정한 scalar context만 전달하고, `AbuseFeatureSnapshot`은 Feature Store의 원자 갱신 직후 값을 불변 Map으로 전달한다.

`AbuseFeatureStore.record(observation, windowPolicy)`는 Feature를 갱신하고 Snapshot을 반환할 뿐 Threshold 비교나 Detection 생성을 하지 않는다. `AbuseCooldownStore`는 UUID 소유 Token이 포함된 `CooldownLease`를 반환하며, 해제는 저장된 Token이 일치할 때만 성공해야 한다. `AbuseDetectionRepository.reviewIfDetected()`는 `DETECTED` 조건부 UPDATE의 영향 행 수를 반환한다. Port 구현체는 장애를 정상 결과로 숨기지 않으며 최종 Fail Open은 Observation 호출 경계가 담당한다.

기본 요청 Rule은 `AbuseFeatureSnapshot`과 활성 정책 임계치를 비교해 `AbuseRuleMatch` 후보를 반환한다. 후보에는 탐지 유형과 구조화된 Evidence를 담되 Cooldown scope hash와 DB 저장은 후속 Evaluator/Processor가 맡는다. `REQUEST_ID_ROTATION`은 Mission에서만 별도 `signalEvidence`로 반환하며 단독 Detection 후보를 만들지 않는다. Rotation Evidence는 자신의 Business Key scope, rotation window, distinct requestId 측정값·임계치를 보존한다. Burst Evidence의 primary window·scope에 Rotation 값을 합치지 않으며, 후속 Composite Rule이 별도 Signal 근거를 전달받아 처리한다.

`FailureBurstRuleEvaluator`는 현재 결과가 `BUSINESS_FAILURE`일 때만 판정한다. `INSUFFICIENT_BALANCE_BURST`는 Event Entry의 `INSUFFICIENT_BALANCE`에만 적용하며 `USER_BALANCE_SCOPE`별 count·연속 count를 비교한다. `FAILURE_BURST`는 Mission·Entry의 모든 업무 실패를 `USER` 범위에서 비교한다. 각 Evidence에는 충족 여부와 관계없이 두 count의 측정값·설정 임계치를 함께 보존하고, Failure Evidence에는 후속 `RULE-05`가 사용할 distinct failure type 측정값·임계치도 남긴다. Replay·시스템 실패·정상 성공은 이전 집계값이 높더라도 이 두 Rule의 새 후보를 만들지 않는다.

`AbuseDetection`은 Adapter에 독립적인 순수 Aggregate다. 신규 객체의 상태는 `DETECTED`이고, 단일 객체의 검토 전이는 `AbuseReviewDecision.CONFIRMED` 또는 `FALSE_POSITIVE`로 한 번만 가능하다. Repository Port도 상태 enum 대신 `AbuseReviewDecision`만 받아 `DETECTED → DETECTED`와 검토 정보 기록을 타입 수준에서 차단한다. 실제 관리자 동시 전이의 최종 방어선은 Repository의 조건부 UPDATE다.

Evidence Scope의 필수 구성은 타입별로 고정한다. `USER`는 추가 업무 식별자를 갖지 않고, `BUSINESS_KEY`는 `missionId`와 `balanceScope`, `USER_EVENT`는 `eventId`, `USER_BALANCE_SCOPE`는 `balanceScope`가 필수다. Creator 전용 Balance Scope는 creatorId가 일치해야 한다. Event는 Creator 소유이면서 공용 응모권을 사용할 수 있으므로 `USER_EVENT`의 creatorId와 `COMMON` Balance Scope 조합은 허용한다. Feature 측정값은 0 이상, 실제 적용한 Threshold는 양수만 허용한다.

## Detection 저장과 운영

최종 Detection은 MySQL `abuse_detection`에만 저장한다.

```text
id BIGINT PK; member_id BIGINT NOT NULL; abuse_type VARCHAR(64) NOT NULL;
status VARCHAR(32) NOT NULL; detected_at DATETIME(6) NOT NULL;
reviewed_at DATETIME(6) NULL; reviewed_by BIGINT NULL; evidence JSON NOT NULL
```

FK는 `member_id → member`, `reviewed_by → member`다. index는 `(member_id, detected_at)`, `(status, detected_at)`, `(abuse_type, detected_at)`다. status는 `DETECTED`, `CONFIRMED`, `FALSE_POSITIVE`만 허용한다. `REQUEST_ID_ROTATION`은 row가 없다.

Evidence는 request body, token, authorization header, 개인정보를 담지 않는다. 아래 필수 필드와 해당 rule에 필요한 ID·집계값만 보관한다.

```json
{
  "policyVersion":"ABUSE_V1",
  "scope":{"type":"BUSINESS_KEY","creatorId":10,"missionId":3,"periodKey":"2026-10-01","balanceScope":{"type":"CREATOR","creatorId":10}},
  "window":{"windowMs":10000},
  "features":{"duplicateMissionFailureCount":8,"distinctRequestIdCountPerBusinessKey":6},
  "thresholds":{"duplicateMissionFailureCount":5,"distinctRequestIdCountPerBusinessKey":3},
  "signals":["REQUEST_ID_ROTATION"], "matchedRules":["RULE-01"]
}
```

Detection insert 때만 `[ABUSE_DETECTED]` 로그(detectionId, userId, abuseType, matchedRules, detectedAt)를 남긴다. Observation은 원 업무의 성공 결과 또는 `BusinessException`을 확정한 뒤 실행하고 scalar context만 전달한다. Detection 저장은 원 업무 Transaction을 suspend하는 독립 Transaction(`REQUIRES_NEW` 또는 동등한 `TransactionTemplate`)에서 수행한다. 독립 Transaction의 시작·flush·commit 오류까지 Observation 호출 경계에서 catch해 WARN/ERROR로 남기고, 원래 Mission/Entry 결과와 예외를 그대로 반환한다. 따라서 Redis·Detection DB 장애는 성공·업무 실패 어느 경로에서도 원 응답을 바꾸지 않는 Fail Open이다.

## 설정과 Calibration

`cking.abuse.enabled=false`가 기본이다. false이면 하위 설정 없이 기동하고 Observation은 Port를 호출하지 않는 no-op이어야 한다. true이면 모든 rule의 window/threshold, insufficient·failure의 consecutive threshold, rotation의 window/distinct threshold, rapid의 maxDelay/window/threshold, failure distinct-type threshold가 필수다. 누락 값·0 이하 값은 시작 시 validation 실패하며 코드 default를 두지 않는다.

설정 key는 아래 구조로 고정한다. Duration은 ISO-8601 형식이며 실제 숫자는 Calibration 뒤 환경변수로 주입한다.

```text
cking.abuse.mission-request-burst.{window,threshold}
cking.abuse.duplicate-mission-burst.{window,threshold}
cking.abuse.entry-request-burst.{window,threshold}
cking.abuse.insufficient-balance-burst.{window,threshold,consecutive-threshold}
cking.abuse.request-id-rotation.{window,distinct-threshold}
cking.abuse.rapid-earn-and-spend.{max-delay,window,threshold}
cking.abuse.failure-burst.{window,threshold,consecutive-threshold,distinct-type-threshold}
```

정책 버전은 `ABUSE_V1`, Feature TTL padding은 60초, Cooldown TTL은 해당 AbuseType primary window의 두 배로 고정하며 Calibration 설정으로 노출하지 않는다.

Initial threshold는 k6로 산출한다. rotation을 포함한 각 count rule에 1/5/10/30/60초 window 후보의 `normalMax`와 `abuseMin`을 기록하고, 처음 `normalMax < abuseMin`인 최소 window와 `N=normalMax+1`을 채택한다. 후보가 모두 겹치면 calibration 실패이며 임의 수치를 설정하지 않는다. 운영 중에는 CONFIRMED/FALSE_POSITIVE/미탐을 근거로 설정만 조정한다.

k6는 `normal-user.js`, `mission-request-burst.js`, `duplicate-mission-burst.js`, `entry-request-burst.js`, `insufficient-balance-burst.js`, `request-id-rotation.js`, `rapid-earn-and-spend.js`, `failure-burst.js`로 분리한다. 정상군은 1회 완료, 2~3회 클릭, 같은 requestId 재시도, 정상 다중 응모, 부족 1~2회와 EARN 뒤 재응모, 즉시 EARN-SPEND, Creator 순차 수행을 포함한다. 비정상군은 각 rule을 넘기는 자동 반복을 만든다.

## v1 완료 기준

7개 행동 모델(그중 rotation은 signal), 4개 API 연결, Redis feature/Lua, 6개 Detection rule·5개 composite rule, scope cooldown, migration/Evidence, 관리자 API, 정상·비정상 k6와 calibration, 그리고 성공·업무 실패 각각에서 Redis/독립 Detection Transaction 장애에도 기존 처리 결과가 유지됨을 자동화 테스트로 검증하면 완료다. 관리자 동시 검토에서 같은 판정은 멱등 반환되고 상반된 판정은 정확히 하나만 성공하는 테스트도 포함한다. 구현 단계에서 위 범위, 분류, scope, key/TTL, Lua 분리, cooldown, 상태, Fail Open 정책은 다시 결정하지 않는다. 숫자 threshold만 Calibration 산출물이다.
