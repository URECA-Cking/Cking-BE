# WEIGHTED_V1 확률 분포 검증

이슈 [#504](https://github.com/URECA-Cking/Cking-BE/issues/504)의 검증 도구다.
운영 기준 코드는 `295749f1233ac4f54d261a2b829f8e7a1a520c42`이며,
`src/simulation/java/kr/co/cking/drawing/simulation/`에서 실제 `WeightedV1DrawingEngine`을 호출한다.
DB, Redis, Spring Context 없이 실행하며 시뮬레이션 클래스는 운영 JAR에 포함하지 않는다.

## 실행 전에 고정한 설계

| 시나리오 ID | 유효 후보 가중치 | 당첨자 수 | 확인 대상 |
|---|---|---|---|
| `equal` | 1, 1, 1, 1 | 1 | 균등 확률 |
| `asymmetric` | 1, 3, 6 | 1 | 0.1, 0.3, 0.6 |
| `rare` | 1, 999999, 9000000 | 1 | 강한 편향, 기대 빈도 5 미만 |
| `large-total` | u, 3u, 6u (`u=Long.MAX_VALUE/10`) | 1 | 합 `Long.MAX_VALUE-7`, 64비트 누적·난수 범위 |
| `multi-two` | 1, 3, 6 | 2 | 첫 순위, 모든 앞선 순서에 대한 조건부 확률, 포함 확률 |
| `multi-three-excluded` | 1, 2, 3, 4 | 3 | 두 당첨자를 제외한 3순위 조건부 확률, 제외 계약 |

`large-total`의 후보는 3명이며 가중치는 `u, 3u, 6u`다. 합은
`u + 3u + 6u = 10u = 9223372036854775800 = Long.MAX_VALUE - 7`이다.
`SimulationScenario.of(id, winnerCount, weights...)`의 두 번째 인수 `1`은 당첨자 수이며 가중치에 포함되지 않는다.

후보 ID는 표의 순서대로 1부터 부여한다. 마지막 시나리오에는 가중치 `Long.MAX_VALUE`인
5번 회원을 추가하고 제외한다. 제외 후보는 이론 확률의 분모에도 들어가지 않는다.
총합은 제외 후 `long` 정수 덧셈으로 계산하고, 확률 비율·순서 확률 합산만 `double`을 쓴다.

- 일반 CI: 각 시나리오 10,000회, 총 60,000회.
- 대량 명령: 각 시나리오 200,000회, 총 1,200,000회.
- 두 실행 모두 마스터 Seed는 `50`을 32회 반복한 64자리 hex다.
- family 유의수준 `alpha=0.001`, 사전 검정 개수 `m=69`, 행별 `a=alpha/m`.
- 같은 고정 집합을 다시 실행하며 실패를 피하기 위한 Seed 교체, 표본 수 변경, 자동 재시도는 하지 않는다.

위 상수와 시나리오는 최초 결과 확인 전에 소스에 고정했다. CI와 대량 실행은 같은 시나리오와
판정식을 쓰며 표본 수만 다르다. 고정 집합이므로 CI 실행마다 통계적 우연으로 결과가 바뀌지 않는다.
검정 개수는 관측 전에 가능한 prefix와 후보로부터 계산한다. 조건부 표본 수는 해당 prefix가 실제
발생한 횟수다. 해당 횟수를 조건으로 다음 선택은 이론 조건부 확률의 Bernoulli 표본으로 취급한다.

## Seed 생성과 표본

`SimulationSeeds`는 아래 문자열을 UTF-8/LF(마지막 LF 포함)로 인코딩한 SHA-256을
추첨 Seed 32바이트로 사용한다. 반복 번호는 0부터 시작하는 10진수다.

```text
CKING_WEIGHTED_SIMULATION_V1
<master seed lowercase hex>
<scenario id>
<iteration>
```

각 추첨마다 새로운 `DeterministicRandom`이 만들어진다. 실행 전체의 Seed를 Set으로 확인하여
중복을 발견하면 집계를 중단한다. 동일 Seed 재실행 결과를 추가 독립 표본으로 세지 않는다.
SHA-256 파생 Seed와 운영 HMAC 난수의 출력은 독립적인 난수 표본처럼 분석한다는 모델 가정을 둔다.
이 실행 자체가 독립성이나 암호학적 안전성을 증명하지는 않는다.

## 독립 기준값과 매 반복 계약

`WithoutReplacementReference`는 운영 Fenwick Tree와 운영 난수 없이 작은 후보 집합의
서로 다른 당첨 순서를 전부 열거한다. 순서 `i1,...,ik`의 확률은 다음과 같다.

```text
P(i1,...,ik) = product_r w[ir] / (W - sum_{s<r} w[is])
P(member i included) = sum_{orders containing i} P(order)
```

포함 확률에 `winnerCount * w_i / W`를 쓰지 않는다. 예를 들어 1:3:6에서 2명 선정 시
포함 확률은 각각 `41/140`, `47/60`, `97/105`다. 손으로 구한 여섯 순서의 분수와 비교하는
별도 테스트로 열거 기준값을 확인한다. 열거 비용 때문에 도구 입력은 유효 후보 최대 8명으로 제한한다.

검정 행은 1명 시나리오의 첫 순위 13개, `multi-two`의 첫 순위·조건부·포함 12개,
`multi-three-excluded`의 첫 순위·조건부·포함 44개로 총 69개다.
모든 반복에서 알고리즘 버전, 요청 당첨자 수, 순위 `1..k`, 회원 중복 없음,
제외·미등록 회원 미선정, 원래 `appliedTicketCount` 보존을 확인한다. 계약 위반은 즉시 실패한다.

## 판정식과 희귀 후보

후보의 이론 확률을 `p`, 유효 표본 수를 `n`, 관측 횟수를 `x`라고 할 때 다음을 사용한다.
Bernstein 부등식은 [UC Berkeley 강의 노트의 Theorem 8.5](https://math.berkeley.edu/~linlin/qasc/live_notes_0407.pdf#page=121)를 따른다.

```text
v = n*p*(1-p)
L = log(2/a)
t = sqrt(2*v*L) + 2*L/3
PASS iff abs(x - n*p) <= t
tail_bound = min(1, 2*exp(-abs(x-n*p)^2 / (2*(v+abs(x-n*p)/3))))
```

`t`는 위 부등식에서 양측 꼬리 상한을 `a` 이하로 만드는 보수적인 허용 편차다.
CSV의 `acceptance_lower/upper`는 `[max(0,p-t/n), min(1,p+t/n)]`으로 귀무가설 아래
관측 비율의 허용 범위를 나타낸다. 관측 확률의 신뢰구간이나 정확한 p-value가 아니다.
`bernstein_tail_bound`는 관측 편차의 양측 꼬리 확률 상한이며, `PASS` 판정은 위의 고정 `t`를 따른다.
Bonferroni 보정은 행들이 서로 독립일 필요 없이 전체 오탐 상한을 제어한다.
단, 그 확률 해석은 앞서 설명한 난수 표본 모델 아래에서만 성립한다.

카이제곱 검정과 정규 근사를 사용하지 않아 최소 기대 빈도 조건을 요구하지 않는다.
`low_expected_count=true`는 `n*p<5`인 행이다. 희귀 후보 1번의 대량 실행 기대 횟수는 0.02이며,
0회 관측도 정상 범위다. 이 표본 수로 희귀 후보의 상대 확률 정확도나 영구적인 미선정을
구별할 검정력은 부족하다. 큰 절대 편향은 검출하지만 희귀 후보의 미세한 편향은 결론을 내리지 않는다.
조건부 표본이 0이면 `INSUFFICIENT`로 실패하고, 이론 확률 0/1은 빈도가 정확히 일치해야 통과한다.

## 실행 명령

JDK 21과 저장소 Gradle Wrapper를 사용한다. MySQL·Redis 없이 실행할 수 있다.

```powershell
# Windows: 일반 CI 회귀와 도구 단위 테스트
.\gradlew.bat test --tests 'kr.co.cking.drawing.simulation.*'

# Windows: 대량 시뮬레이션
.\gradlew.bat weightedDistributionSimulation
```

```bash
# macOS/Linux
./gradlew test --tests 'kr.co.cking.drawing.simulation.*'
./gradlew weightedDistributionSimulation
```

일반 `./gradlew test`에도 회귀 테스트가 포함된다. 기존 CI 워크플로우는 그대로 이를 실행한다.
`weightedDistributionSimulation`은 별도 JavaExec이므로 일반 `test`/`build`에서 대량 실행하지 않는다.
통계 판정 실패는 CSV를 저장한 뒤 비정상 종료한다. 매 반복의 계약 위반·중복 Seed는 즉시 예외로 종료한다.

결과는 `build/reports/weighted-distribution/`에 저장한다.

| 파일 | 내용 |
|---|---|
| `checks.csv` | 이론 확률·기대 횟수, 유효 표본 수, 관측 횟수·비율, 절대 오차, 허용 범위, 꼬리 상한, 보정 유의수준, 판정 |
| `orders.csv` | 가능한 모든 당첨 순서, 독립 열거 확률, 실제 횟수(미관측 0 포함) |
| `scenarios.csv` | 후보 ID·가중치·제외 ID·당첨자 수·실행 횟수 |
| `environment.txt` | UTC 시작 시각, 마스터 Seed, 기준, Java·OS·메모리, 실행 클래스 SHA-256, 결과 |

CSV는 UTF-8, LF, 소수점 `.`으로 저장한다. `orders.csv`의 전체 순서 빈도로 각 검정 행의
분모와 관측 빈도를 다시 계산할 수 있다. Seed 생성 규칙으로 개별 추첨도 재현할 수 있다.
Java/OS 정보와 클래스 해시는 환경 및 엔진 바이트코드 변경을 구분하는 근거다.

## 저장한 결과와 해석

실행 결과는 [검증 결과 디렉터리](verification-results/issue-504/)에 보관한다.
상세 관측값과 실행 환경은 해당 CSV 및 `environment.txt`를 따른다.
이번 검증은 지정한 후보군·Seed 집합에서의 응모자 선정 확률 검증이다.
통계적 통과는 모든 입력의 공정성, 상품 배정 확률, 암호학적 안전성의 완전한 증명이 아니다.
