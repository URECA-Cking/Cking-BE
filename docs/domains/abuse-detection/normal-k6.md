# 정상 사용자 k6 검증 (#434)

목적은 Abuse Detection v1 임계치 보정에 쓸 **정상군 상한**을 측정하는 것이다. 이 문서는 수치를 확정하지 않는다. 서버가 Redis Lua 실행 시각에 Feature를 기록하므로 k6의 HTTP 완료 시각으로 계산한 상한은 근사치다. 최종 보정은 저장된 Detection Evidence 및 서버 Feature 계측과 대조한다.

## 실행 전제

- 실제 사용자 계정·운영 데이터에는 실행하지 않는다. 최초 측정에는 빈 `cking_abuse_k6_434` MySQL 스키마와 **비어 있는 Redis DB 13**을 사용했다. 반복 측정에는 매번 새로운 격리 스키마·Redis DB를 준비한다.
- 앱을 격리 MySQL 스키마·Redis DB·별도 포트로 기동해 Flyway를 적용한 뒤 `k6/normal-user.bootstrap.sql`을 해당 격리 스키마에 한 번 실행한다. SQL 내부에는 `USE`가 없으며 `mysql` 명령의 DB 인자를 따른다.
- 동일한 로컬 전용 `JWT_SECRET`으로 앱과 `k6/prepare-normal-user-fixtures.mjs`를 실행한다. `NORMAL_K6_ISOLATED_REDIS_DB`(1~15)와 실행마다 고유한 `NORMAL_K6_FIXTURE_TAG`가 필수다. 준비 스크립트는 합성 Member 5명의 JWT와 Redis Gate·Balance를 만들고 Git에서 제외되는 `k6/normal-user.<tag>.fixtures.local.json`을 생성한다. 이미 존재하는 Redis 키나 fixture 파일은 덮어쓰지 않는다. 실제 JWT를 콘솔·문서·Git에 넣지 않는다.
- 다섯 Member는 서로 다르며, 테스트 전에 해당 날짜의 LIKE/공용 ATTENDANCE 완료 이력이 없어야 한다. 시나리오는 한 번 실행할 때 상태가 바뀌므로 재실행에는 새 격리 fixture가 필요하다. 기존 `cking` 스키마 또는 다른 Redis DB를 지우지 않는다.
- LIKE는 활성 Creator Mission이고 보상이 Creator 응모권 1장 이상이어야 한다. 공용 ATTENDANCE는 공용 응모권 1장 이상을 지급해야 한다. SHARE/구독 미션은 이 정상군 스크립트의 완료 대상이 아니다.
- `multiEntry`의 선택된 Balance Scope에는 최소 2장이 미리 있어야 한다. `insufficientThenEarn`의 Creator 잔액은 0이고 Redis Balance key는 로드돼 있어야 한다. 두 Event는 OPEN, Gate는 로드된 상태여야 한다. 각 Event의 Creator ID와 fixture의 `creatorId`가 일치해야 한다.
- `creatorSequence.first`와 `second`는 서로 다른 Creator의 활성 LIKE Mission이다. `commonEarnSpend`는 공용 ATTENDANCE 완료 후 공개 Event에 COMMON 응모한다.
- 정상군에서는 Detection 기록 여부와 상관없이 업무 응답이 기대한 그대로여야 한다. 이 스크립트는 요청을 차단하거나 Abuse 설정·Threshold를 변경하지 않는다.

## 실행

처음 실행할 때만 빈 격리 스키마를 만든다. 아래 `mysql` 계정은 로컬 `docker-compose.yml`의 개발용 계정이다. 기존 스키마가 이미 있다면 덮어쓰지 말고 상태를 확인한다.

```bash
docker compose up -d mysql redis
mysql -h 127.0.0.1 -u root -p -e 'CREATE DATABASE cking_abuse_k6_434 CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci; GRANT ALL PRIVILEGES ON cking_abuse_k6_434.* TO "cking"@"%";'
export JWT_SECRET=<로컬에서_생성한_256비트_이상_Base64_값>
./gradlew bootRun --args='--spring.profiles.active=local --spring.datasource.url=jdbc:mysql://localhost:3306/cking_abuse_k6_434?characterEncoding=UTF-8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true --spring.data.redis.database=13 --server.port=18080 --management.server.port=18081 --cking.scheduling.enabled=false'
```

앱이 시작돼 Flyway를 적용한 뒤 **다른 터미널**에서 아래를 실행한다. Redis DB에 앱 기동 시 생성한 빈 Stream 이외의 키가 있으면 준비 스크립트가 중단한다. 해당 DB의 기존 키를 임의로 삭제하지 않는다.

```bash
mysql -h 127.0.0.1 -u cking -p cking_abuse_k6_434 < k6/normal-user.bootstrap.sql
# JWT_SECRET은 앱 실행에 사용한 로컬 전용 값과 동일하게 설정한다.
export NORMAL_K6_ISOLATED_REDIS_DB=13
export NORMAL_K6_FIXTURE_TAG=first
node k6/prepare-normal-user-fixtures.mjs
export BASE_URL=http://127.0.0.1:18080
export NORMAL_FIXTURES_FILE=./normal-user.first.fixtures.local.json
export NORMAL_K6_ALLOW_MUTATION=true
export NORMAL_K6_CONFIRM_TARGET="$BASE_URL"
k6 run --console-output=/tmp/cking-normal-user-k6.log k6/normal-user.js
node --test k6/analyze-normal-user.test.mjs
node k6/analyze-normal-user.mjs /tmp/cking-normal-user-k6.log
```

`BASE_URL`은 격리 앱의 실제 포트로 바꾼다(이번 측정에서는 `http://127.0.0.1:18080`). `BASE_URL`과 `NORMAL_K6_CONFIRM_TARGET`이 정확히 일치하지 않거나 명시적 변경 허용 플래그가 없으면 실행을 중단한다. 기본 1 VU·1 iteration이며, 정상 시나리오를 임의로 반복 실행하지 않는다. `NORMAL_CLICK_PAUSE_SECONDS`는 클릭 사이 휴지 시간(기본 0.2초)이고, EARN 직후 SPEND에는 휴지를 넣지 않는다.

`--console-output` 파일에 이전 실행이 남아 있으면 분석기는 마지막 `click-first`부터 `common-spend`까지 완결된 15건만 사용한다. 마지막 실행이 중단됐으면 수치 대신 오류를 낸다.

## 시나리오와 기대 응답

| 시나리오 | 요청 순서 | 기대 결과 |
| --- | --- | --- |
| 1회 완료·2~3회 클릭 | LIKE 완료 → 같은 requestId 두 번 재전송 → 새 requestId 중복 시도 | `EARN_ACCEPTED` → `ALREADY_PROCESSED` ×2 → `DUPLICATE_MISSION` |
| 정상 다중 응모 | 같은 Event에 별도 requestId로 1장씩 두 번 응모 | `SUCCESS` ×2 |
| 부족·회복·즉시 사용 | 0장으로 응모 두 번 → LIKE 완료 → 즉시 1장 응모 → 같은 requestId 재전송 | `INSUFFICIENT_BALANCE` ×2 → `EARN_ACCEPTED` → `SUCCESS` → `DUPLICATE_REPLAY` |
| Creator 순차 수행 | 서로 다른 Creator의 LIKE를 차례로 완료 | `EARN_ACCEPTED` ×2 |
| 공용 EARN·SPEND | 공용 ATTENDANCE 완료 → COMMON 응모 | `EARN_ACCEPTED` → `SUCCESS` |

예상과 다른 HTTP status 또는 업무 code가 하나라도 나오면 후속 요청을 중단하고 그 실행을 Calibration 자료에서 제외한다. 서버 장애나 테스트 데이터 오염을 정상 사용자 행동으로 산입하지 않는다.

## 관찰값과 보정 기록

`--console-output`에는 요청별 `requestedAtMs`/`observedAtMs`, 합성 memberId, action, Creator/Event/Mission ID, Balance Scope, requestId, 업무 code가 JSON 행으로 남는다. JWT·요청 본문·이름·이메일은 출력하지 않는다. 로그도 합성 ID를 포함하므로 공유 범위를 제한한다.

분석 스크립트는 replay를 제외한 요청에 대해 1/5/10/30/60초 Window별 Mission 요청, 중복 Mission 실패, Event 응모, 잔액 부족, 업무 실패, distinct requestId rotation, 빠른 EARN-SPEND pair 및 실패 유형 수의 `normalMax`를 Scope별로 계산한다. 잔액 부족·전체 실패의 연속 횟수도 **각 Window마다** Lua `recordSequence()`와 같이 이전 실패가 Window 밖이면 1로 재시작하고, 성공하면 해당 sequence를 초기화한다. EARN→SPEND 간 HTTP 완료 시각 차이도 기록한다. Pair 수는 아직 `maxDelay` 정책을 적용하지 않은 상한이므로 최종 설정값과 대조한다. 동일 requestId replay는 별도 Observation이지만 Redis Feature 집계에서는 제외된다. UTC 자정 근처에는 DAILY Business Key의 서버 period와 클라이언트 근사 날짜가 달라질 수 있으므로 실행하지 않는다.

결과를 기록할 때는 실행 날짜·격리 환경·fixture 버전(비밀값 제외)·k6 성공 여부·Window별 `normalMax`·EARN→SPEND 지연·서버 Evidence와의 차이를 함께 남긴다. `abuseMin`과 비교해 `normalMax < abuseMin`인 최초 Window가 나오기 전에는 운영 Threshold를 정하지 않는다.

### 2026-10-06 로컬 격리 측정

- `cking_abuse_k6_434` 신규 스키마(Flyway V42), 별도 앱 포트 18080, 실행 전 비어 있던 Redis DB 13, 합성 Member 5명·Creator 2명·Event 1개를 사용했다. 기존 `cking` 스키마와 Redis DB 0/14/15는 변경하지 않았다.
- `cking.abuse.enabled=false` 기본 설정에서 업무 API 결과만 측정했다. 따라서 아래 값은 **HTTP 완료 시각 기준 근사치**이고 서버의 실제 Redis Feature/Evidence 측정값은 아니다.
- k6 요청 15건, 응답 체크 30/30 통과. 신규 성공·replay·업무 실패의 예상 status/code가 모두 일치했다.

| Window | Mission 요청 | 중복 Mission | Entry 요청 | 잔액 부족 | 업무 실패 | requestId rotation | EARN-SPEND pair | 실패 유형 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1초 | 2 | 1 | 3 | 2 | 2 | 2 | 1 | 1 |
| 5초 | 2 | 1 | 3 | 2 | 2 | 2 | 1 | 1 |
| 10초 | 2 | 1 | 3 | 2 | 2 | 2 | 1 | 1 |
| 30초 | 2 | 1 | 3 | 2 | 2 | 2 | 1 | 1 |
| 60초 | 2 | 1 | 3 | 2 | 2 | 2 | 1 | 1 |

| Window | 잔액 부족 연속 상한 | 전체 업무 실패 연속 상한 |
| --- | ---: | ---: |
| 1초 | 2 | 2 |
| 5초 | 2 | 2 |
| 10초 | 2 | 2 |
| 30초 | 2 | 2 |
| 60초 | 2 | 2 |

EARN→SPEND HTTP 완료 간격은 22ms(CREATOR), 7ms(COMMON)였다. pair 수는 아직 `maxDelay` 필터를 적용하지 않은 값이다. 이 1회 측정만으로 운영 Threshold를 채택하지 않으며, 향후 비정상군의 `abuseMin`과 서버 시각 기준 Feature를 함께 비교한다.
