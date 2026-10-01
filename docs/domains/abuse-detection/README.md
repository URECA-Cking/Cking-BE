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
creatorId?, eventId?, missionId?, periodKey?, ticketScope?, balanceScope?,
businessKey?, requestedAt, observedAt
```

`ticketScope`는 요청의 `couponType` 또는 Mission 보상 경로에서 도출한 `CREATOR`/`COMMON`이다. Balance Scope는 `CREATOR:{creatorId}` 또는 `COMMON`이며, `userId + balanceScope`가 하나의 잔액 풀이다.

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

`REQUEST_ID_ROTATION`은 이 key별 **rotation sliding window** 안의 distinct `requestId`가 threshold 이상인 **Signal**이다. 단독 Detection row를 만들지 않으며 Entry에는 적용하지 않는다. business key는 Redis key에 넣지 않고 SHA-256 hex hash만 쓴다. 원래 구조화 key는 Evidence에 기록한다.

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
| `cooldown:{abuseType}:{scopeHash}` | STRING | primary Window × 2 |

Abuse 전용 Lua는 `ZADD → ZREMRANGEBYSCORE → ZCARD → EXPIRE`를 원자 수행해 count를 반환한다. rotation은 `requestId`를 member로 쓰므로 같은 requestId 재시도는 distinct count를 늘리지 않고, 새 requestId는 해당 window에서만 집계된다. sequence 갱신과 cooldown은 필요한 key를 한 호출에서 원자적으로 처리한다. cooldown은 `SET NX EX`; 획득한 요청만 DB를 insert하며 insert 실패 시 key를 best-effort 삭제한다.

`ticket-earn.lua`, `common-ticket-earn.lua`, `entry-spend.lua`는 수정하지 않는다. 이들은 멱등성·잔액·Stream의 기존 책임만 가지며 Abuse는 별도 script와 key 공간을 사용한다.

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
  "scope":{"type":"BUSINESS_KEY","creatorId":10,"missionId":3,"periodKey":"2026-10-01","ticketScope":"CREATOR"},
  "window":{"windowMs":10000},
  "features":{"duplicateMissionFailureCount":8,"distinctRequestIdCountPerBusinessKey":6},
  "thresholds":{"duplicateMissionFailureCount":5,"distinctRequestIdCountPerBusinessKey":3},
  "signals":["REQUEST_ID_ROTATION"], "matchedRules":["RULE-01"]
}
```

Detection insert 때만 `[ABUSE_DETECTED]` 로그(detectionId, userId, abuseType, matchedRules, detectedAt)를 남긴다. Observation은 원 업무의 성공 결과 또는 `BusinessException`을 확정한 뒤 실행하고 scalar context만 전달한다. Detection 저장은 원 업무 Transaction을 suspend하는 독립 Transaction(`REQUIRES_NEW` 또는 동등한 `TransactionTemplate`)에서 수행한다. 독립 Transaction의 시작·flush·commit 오류까지 Observation 호출 경계에서 catch해 WARN/ERROR로 남기고, 원래 Mission/Entry 결과와 예외를 그대로 반환한다. 따라서 Redis·Detection DB 장애는 성공·업무 실패 어느 경로에서도 원 응답을 바꾸지 않는 Fail Open이다.

## 설정과 Calibration

`cking.abuse.enabled=false`가 기본이다. true이면 모든 rule의 window/threshold, insufficient·failure의 consecutive threshold, rotation의 window/distinct threshold, rapid의 maxDelay/window/threshold, failure distinct-type threshold가 필수다. 누락 값·0 이하 값은 시작 시 validation 실패하며 코드 default를 두지 않는다.

Initial threshold는 k6로 산출한다. rotation을 포함한 각 count rule에 1/5/10/30/60초 window 후보의 `normalMax`와 `abuseMin`을 기록하고, 처음 `normalMax < abuseMin`인 최소 window와 `N=normalMax+1`을 채택한다. 후보가 모두 겹치면 calibration 실패이며 임의 수치를 설정하지 않는다. 운영 중에는 CONFIRMED/FALSE_POSITIVE/미탐을 근거로 설정만 조정한다.

k6는 `normal-user.js`, `mission-request-burst.js`, `duplicate-mission-burst.js`, `entry-request-burst.js`, `insufficient-balance-burst.js`, `request-id-rotation.js`, `rapid-earn-and-spend.js`, `failure-burst.js`로 분리한다. 정상군은 1회 완료, 2~3회 클릭, 같은 requestId 재시도, 정상 다중 응모, 부족 1~2회와 EARN 뒤 재응모, 즉시 EARN-SPEND, Creator 순차 수행을 포함한다. 비정상군은 각 rule을 넘기는 자동 반복을 만든다.

## v1 완료 기준

7개 행동 모델(그중 rotation은 signal), 4개 API 연결, Redis feature/Lua, 6개 Detection rule·5개 composite rule, scope cooldown, migration/Evidence, 관리자 API, 정상·비정상 k6와 calibration, 그리고 성공·업무 실패 각각에서 Redis/독립 Detection Transaction 장애에도 기존 처리 결과가 유지됨을 자동화 테스트로 검증하면 완료다. 관리자 동시 검토에서 같은 판정은 멱등 반환되고 상반된 판정은 정확히 하나만 성공하는 테스트도 포함한다. 구현 단계에서 위 범위, 분류, scope, key/TTL, Lua 분리, cooldown, 상태, Fail Open 정책은 다시 결정하지 않는다. 숫자 threshold만 Calibration 산출물이다.
