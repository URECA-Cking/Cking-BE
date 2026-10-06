# 비정상 행동 k6 검증 (#441)

목적은 정상 사용자 [#434 측정](normal-k6.md)의 `normalMax`와 비교할 비정상군 표본을 확보하는 것이다. 스크립트는 실제 Mission·Event Entry API를 호출하지만 **운영 threshold를 정하거나 서비스에 주입하지 않는다**. HTTP 완료 시각은 Redis Lua 실행 시각의 근사치다. 후속 Calibration은 여러 번의 비정상 실행에서 얻은 최솟값(`abuseMin`)과 실제 Redis Feature/Evidence를 대조한다.

## 실행 전제

- 운영 서버·실제 사용자·공유 DB에는 실행하지 않는다. 새 빈 MySQL 스키마 `cking_abuse_k6_441`, 빈 Redis DB(예: 12), 별도 앱/Actuator 포트를 사용한다. 해당 DB에 기존 키가 있으면 삭제하지 말고 다른 빈 DB를 찾는다.
- `k6/abnormal-user.bootstrap.sql`은 대상 스키마를 `USE`로 고정했다. Flyway를 적용한 뒤 그 스키마에서 **한 번만** 실행한다. 합성 Member 7명·Creator 2명·Mission 3개(LIKE, SHARE, 비활성 LIKE)·OPEN Event 1개를 준비한다.
- 같은 로컬 전용 `JWT_SECRET`으로 앱과 `k6/prepare-abnormal-user-fixtures.mjs`를 실행한다. 준비 스크립트는 비어 있는 Redis DB 또는 앱이 생성한 길이 0의 세 Stream만 허용하며, Gate·Balance와 합성 JWT를 별도 `*.local.json`에 저장한다. 이미 존재하는 키/파일을 덮어쓰지 않는다.
- 일곱 시나리오는 서로 다른 Member를 쓴다. 한 번 실행한 시나리오는 Mission 완료·Balance·Entry 상태가 바뀌므로 같은 fixture로 재실행하지 않는다. 재측정에는 새 격리 스키마·Redis DB·fixture가 필요하다.
- UTC 자정 전후에는 DAILY Mission Business Key의 서버 날짜와 클라이언트 추정 날짜가 달라질 수 있으므로 실행하지 않는다.
- 이 이슈의 측정 앱은 `cking.abuse.enabled=false`로 기동한다. 업무 결과만 확인하며 Detection 생성 여부를 주장하지 않는다. 실제 Feature/Evidence 및 threshold Calibration은 후속 이슈에서 검증한다.

## 실행 절차

아래 DB 생성·GRANT는 `docker-compose.yml`의 로컬 개발 계정을 전제로 한다. 대상 이름이 이미 있으면 실행하지 않는다.

```bash
docker compose up -d mysql redis
mysql -h 127.0.0.1 -u root -p -e 'CREATE DATABASE cking_abuse_k6_441 CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci; GRANT ALL PRIVILEGES ON cking_abuse_k6_441.* TO "cking"@"%";'
export JWT_SECRET=<로컬_전용_256비트_이상_Base64_값>
./gradlew bootRun --args='--spring.profiles.active=local --spring.datasource.url=jdbc:mysql://localhost:3306/cking_abuse_k6_441?characterEncoding=UTF-8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true --spring.data.redis.database=12 --server.port=18084 --management.server.port=18085 --cking.scheduling.enabled=false'
```

앱이 Flyway를 완료한 뒤 다른 터미널에서 실행한다. `JWT_SECRET`은 앱과 동일한 **로컬 전용 값**을 사용하고 콘솔·문서·Git에 출력하지 않는다. 이미 사용 중인 Redis DB 12라면 다른 빈 번호를 선택하고 앱/준비 스크립트 양쪽에 같은 번호를 지정한다.

```bash
mysql -h 127.0.0.1 -u cking -p cking_abuse_k6_441 < k6/abnormal-user.bootstrap.sql
export ABNORMAL_K6_ISOLATED_REDIS_DB=12
export ABNORMAL_K6_PREPARE=true
node k6/prepare-abnormal-user-fixtures.mjs
export BASE_URL=http://127.0.0.1:18084
export ABNORMAL_FIXTURES_FILE=./abnormal-user.fixtures.local.json
export ABNORMAL_K6_ALLOW_MUTATION=true
export ABNORMAL_K6_CONFIRM_TARGET="$BASE_URL"
for scenario in mission-request-burst duplicate-mission-burst entry-request-burst insufficient-balance-burst request-id-rotation rapid-earn-and-spend failure-burst; do
  k6 run --console-output="/tmp/cking-441-${scenario}.log" "k6/${scenario}.js"
done
node --test k6/analyze-abnormal-user.test.mjs
node k6/analyze-abnormal-user.mjs missionRequestBurst /tmp/cking-441-mission-request-burst.log
```

`open()`은 k6 스크립트(`k6/`) 기준으로 경로를 해석하므로 `ABNORMAL_FIXTURES_FILE`은 위와 같이 `./abnormal-user.fixtures.local.json`이다. `BASE_URL`은 `localhost`/`127.0.0.1`만 허용하며 명시적 변경 승인 플래그와 확인 URL이 일치하지 않으면 시작하지 않는다. 기본은 시나리오당 1 VU·1 iteration이고 업무 응답이 다르면 즉시 그 실행을 중단한다. 고정 시나리오인 빠른 pair(4요청)·복합 실패(10요청)를 제외한 다섯 시나리오는 `ABNORMAL_REQUEST_COUNT=3..12`(기본 8)로 요청 수를 조정할 수 있다. 로그 파일도 합성 ID·requestId를 포함하므로 공유 범위를 제한한다.

## 시나리오와 기대 응답

| 스크립트 | 행동 | 기대 응답 |
| --- | --- | --- |
| `mission-request-burst.js` | 같은 LIKE를 새 requestId로 8회 시도 | 첫 `EARN_ACCEPTED`, 이후 `DUPLICATE_MISSION` 7회 |
| `duplicate-mission-burst.js` | 동일 Business Key 중복 완료 7회 | 첫 `EARN_ACCEPTED`, 이후 `DUPLICATE_MISSION` 7회 |
| `entry-request-burst.js` | 사전 잔액 12장에서 같은 Event에 8회 응모 | `SUCCESS` 8회 |
| `insufficient-balance-burst.js` | 0장으로 같은 Event에 8회 응모 | `INSUFFICIENT_BALANCE` 8회 |
| `request-id-rotation.js` | 같은 LIKE Business Key에 서로 다른 requestId 8개 사용 | 첫 `EARN_ACCEPTED`, 이후 `DUPLICATE_MISSION` 7회. Rotation은 독립 Detection이 아닌 Signal |
| `rapid-earn-and-spend.js` | 같은 Creator BalanceScope에서 LIKE EARN→SPEND, SHARE EARN→SPEND | `EARN_ACCEPTED`·`SUCCESS` 두 쌍 |
| `failure-burst.js` | LIKE 보상을 받아 즉시 소비한 후 중복 Mission 3회·잔액 부족 3회·비활성 Mission 2회 | 선행 `EARN_ACCEPTED`·`SUCCESS`, 이후 `DUPLICATE_MISSION`·`INSUFFICIENT_BALANCE`·`MISSION_INACTIVE` |

모든 requestId는 서버에 보내는 실제 UUID이며 시도마다 새 값이다. `ALREADY_PROCESSED` replay로 비정상 카운트를 부풀리지 않는다. 사용자를 차단하지 않으며 기존 Ticket EARN·Entry SPEND Lua는 수정하지 않는다.

실제 서버의 `MissionResultClassifier`·`EntryResultClassifier`에서 `EARN_ACCEPTED`/`SUCCESS`는 `NEW_SUCCESS`, `DUPLICATE_MISSION`/`INSUFFICIENT_BALANCE`/`MISSION_INACTIVE`는 `BUSINESS_FAILURE`다. 두 Classifier의 관련 단위 테스트를 실행해 이 매핑이 유지되는지 확인했다. k6 자체는 응답 코드만 관찰하므로 개별 Observation 객체가 기록됐다고 주장하지 않는다(`#428` 통합 검증 범위).

## 관찰과 실제 실행 결과

각 `--console-output` 파일에서 `node k6/analyze-abnormal-user.mjs <scenario> <log> [requestCount]`를 실행한다. 분석기는 단일 완결 실행·기대 응답 일치를 요구하고, 1/5/10/30/60초 Window별 요청·업무 실패·연속 실패·Rotation distinct requestId·EARN-SPEND pair를 합성 Scope별로 집계한다. Window 밖의 이전 실패는 Lua `recordSequence()`와 같이 연속 횟수를 1로 재시작한다. 출력의 `observedMaxByWindowMs`는 **한 번 실행한 HTTP 기준 최댓값**이지 여러 비정상군 실행의 `abuseMin`이 아니다.

2026-10-06 로컬 검증은 별도 MySQL 8.4 스키마 `cking_abuse_k6_441b`(Flyway V44)와 실행 전 비어 있던 Redis 7.2 DB 11, 앱 포트 18084, 합성 Member 7명을 사용했다. 첫 fixture에서 복합 실패 시나리오의 선행 EARN을 소비하지 않아 기대한 잔액 부족 대신 `SUCCESS`가 반환된 것을 확인해, 선행 SPEND를 추가하고 **새 격리 데이터**에서 7개를 다시 실행했다. 재실행은 총 54요청·108개 HTTP/업무 코드 체크가 모두 통과했다. 기존 `cking` 스키마와 Redis DB 0/13/14/15는 변경하지 않았다.

| 시나리오의 주요 측정값 | 1초 | 5초 | 10초 | 30초 | 60초 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Mission 요청 수 | 8 | 8 | 8 | 8 | 8 |
| 동일 Mission 중복 실패 수 | 7 | 7 | 7 | 7 | 7 |
| Event 응모 요청 수 | 8 | 8 | 8 | 8 | 8 |
| 잔액 부족 수·연속 횟수 | 8 | 8 | 8 | 8 | 8 |
| Business Key별 distinct requestId | 8 | 8 | 8 | 8 | 8 |
| 같은 BalanceScope의 EARN-SPEND pair | 2 | 2 | 2 | 2 | 2 |
| 업무 실패 수·연속 횟수 | 8 | 8 | 8 | 8 | 8 |

복합 실패의 distinct 업무 실패 유형은 3개였다. 빠른 pair의 HTTP 완료 간격은 11ms·10ms였다. #434 정상군의 관찰 상한보다 큰 값이 나왔지만, 이는 단회 HTTP 근사치이므로 아직 운영 Window·Threshold·`maxDelay`를 결정하지 않는다. 후속 Calibration에서 반복 측정과 Redis Lua 시각·Detection Evidence를 함께 비교한다.
