# Abuse Detection Calibration 및 Fail Open 검증 (#448)

## Calibration 입력과 판정

- 정상군·비정상군 각 시나리오를 최소 3회, 매번 신규 격리 MySQL 스키마·Redis DB·fixture로 실행한다. 기존 측정 환경을 초기화하거나 재사용하지 않는다.
- 각 실행은 k6 기대 응답을 모두 통과해야 한다. `analyze-normal-user.mjs` 및 `analyze-abnormal-user.mjs`의 JSON 결과와 고유 `runId`를 기록한다. 로그에 JWT나 실제 사용자 데이터를 남기지 않는다.
- `k6/calibrate-abuse.mjs`는 여러 정상 실행의 Window별 최댓값 `normalMax`와 비정상 실행의 Window별 최솟값 `abuseMin`을 비교한다. `normalMax < abuseMin`인 가장 짧은 Window에 한해서 `normalMax + 1`을 후보로 출력한다. 모든 Window가 겹치면 후보는 `null`이다.
- 이 출력은 `HTTP_ONLY_NOT_OPERATIONAL`이다. HTTP 완료 시각은 Redis Lua 실행 시각과 다르다. `rapidEarnSpendPair`는 `maxDelay`를 적용하지 않은 상한이다. 후보값을 곧바로 `cking.abuse.*` 운영 설정으로 사용하지 않는다.
- 실제 적용 전에는 선택 Window에서 Redis Feature 및 저장된 Detection Evidence의 scope, window, count, threshold, matchedRules를 확인하고 정상군 오탐·비정상군 미탐 여부를 대조한다. `maxDelay`도 실제 Redis 시각 차이를 기준으로 별도 결정한다. 근거가 부족하거나 분리되지 않는 Rule은 비활성 상태를 유지하고 추가 측정한다.

입력 Manifest는 아래 형태다. 각 배열에는 서로 다른 runId·격리 환경의 3개 이상이 필요하다. `logPath`는 k6 `--console-output` 파일 경로다. 비정상군 key는 `missionRequestBurst`, `duplicateMissionBurst`, `entryRequestBurst`, `insufficientBalanceBurst`, `requestIdRotation`, `rapidEarnAndSpend`, `failureBurst` 전부를 포함한다.

```json
{
  "normalRuns": [{"runId": "normal-01", "logPath": "/tmp/normal-01.log"}],
  "abnormalRuns": {"missionRequestBurst": [{"runId": "mission-01", "logPath": "/tmp/mission-01.log"}]}
}
```

위 JSON은 구조 설명용이며 실행 가능 샘플이 아니다. 실제 파일은 7개 비정상군의 로그를 각 3회 이상 담아야 한다. 실행:

```bash
node --test k6/calibrate-abuse.test.mjs
node k6/calibrate-abuse.mjs /tmp/cking-abuse-calibration-manifest.json
```

## 2026-10-06 격리 측정 결과

#434 정상군과 #441 비정상군의 기존 완결 로그에 더해, 신규 MySQL 스키마·Redis DB를 실행마다 따로 준비해 정상군 2회(`cking_abuse_448_n2/n3`, Redis DB 1/2)와 비정상군 2회(`cking_abuse_448_a2/a3`, Redis DB 3/4, 각 7개 시나리오)를 추가했다. 정상군은 3회 모두 15요청/30체크, 비정상군은 3회 모두 시나리오별 기대 HTTP·업무 결과를 통과했다. 실제 사용자나 기존 `cking` 스키마는 사용하지 않았다.

세 회차의 `normalMax`/`abuseMin`은 아래와 같다. 모든 측정 항목에서 1/5/10/30/60초 Window 값이 같아 최초 분리 Window는 1초다. 아래 숫자는 **HTTP 완료 시각 기준**이다.

| 측정 항목 | normalMax | abuseMin | 1초 후보 threshold |
| --- | ---: | ---: | ---: |
| Mission 요청 | 2 | 8 | 3 |
| 중복 Mission 실패 | 1 | 7 | 2 |
| Entry 요청 | 3 | 8 | 4 |
| 부족 잔액 실패 / 연속 | 2 / 2 | 8 / 8 | 3 / 3 |
| requestId rotation | 2 | 8 | 3 |
| 빠른 EARN-SPEND pair | 1 | 2 | 2 |
| 업무 실패 / 연속 | 2 / 2 | 8 / 8 | 3 / 3 |
| 실패 유형 distinct | 1 | 3 | 2 |

EARN→SPEND HTTP 간격은 정상군 첫 측정 22/7ms, 추가 측정 13/7ms·9/8ms였다. 이 구간만으로 오탐 없는 `maxDelay`를 구분할 수 없어 값은 산출하지 않는다.

실제 서버 대조에는 **운영 설정이 아닌 시험용** 1초 Window/위 후보 threshold/`maxDelay=1초`를 별도 격리 앱(`cking_abuse_448_server_n/a`, Redis DB 5/6)에만 주입했다. 정상군 15요청은 모두 원 응답을 유지했고 Detection은 0건이었다. 비정상군 7개 시나리오도 원 응답을 유지했고, MySQL Detection에서 1초 Window와 위 threshold를 보존한 `MISSION_REQUEST_BURST` 4건, `DUPLICATE_MISSION_BURST` 4건, `ENTRY_REQUEST_BURST` 3건, `INSUFFICIENT_BALANCE_BURST` 2건, `RAPID_EARN_AND_SPEND` 1건, `FAILURE_BURST` 5건을 확인했다. Evidence는 Scope별로 실제 측정값 3/2/4/3/2/3 등과 `matchedRules`를 보존했다. Rotation은 독립 Detection이 아닌 Composite 근거다. 이 대조에서 `maxDelay=1초`는 두 pair를 포착하고 정상군 Detection을 만들지 않은 **초기 시험 후보**일 뿐이다.

이 결과는 합성 행동 모델에서의 초기 후보 검증이지 실제 사용자 분포를 대표하지 않는다. 운영 `cking.abuse.enabled`는 기본 `false`를 유지한다. 운영 활성화 전 실제 사용자 표본/관리자 오탐 검토와 서버 시각 기준 `maxDelay` 재평가가 필요하다.

`AbuseObservationEndToEndIntegrationTest`는 실제 Mission/Entry → Observer → Redis Feature/Rule/Cooldown → MySQL Detection 연결에 더해 Redis Feature 및 Detection 저장 장애가 원 업무의 성공·업무 실패를 바꾸지 않는지 검증한다. Detection 저장은 독립 Transaction이고, 실패한 Cooldown Lease 해제는 best-effort다. Infrastructure Adapter 자체가 오류를 정상값으로 숨기지 않는 계약은 별도 Adapter 테스트에서 검증한다. 테스트값은 운영 Calibration 결과가 아니다.
