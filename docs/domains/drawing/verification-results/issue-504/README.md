# 이슈 #504 실행 결과

2026-10-08 22:03 KST에 시작한 실제 대량 실행 결과다. Java/OS/Seed/클래스 해시는
[environment.txt](environment.txt)에 기록했다. 실행 전 고정한 기준은
[검증 설계](../../weighted-distribution-verification.md)를 따른다.
운영 엔진 기준 커밋은 `295749f1233ac4f54d261a2b829f8e7a1a520c42`다.
실행한 검증 도구 소스는 `eaee4d7`에 보관했다.

## 실행과 검증

Windows 11 amd64, Oracle JDK `21.0.10+8-LTS-217`, Gradle Wrapper `9.7.1`,
시뮬레이션 최대 힙 1 GiB에서 다음을 실행했다. 로컬 Gradle 캐시를 재사용하기 위해
`GRADLE_USER_HOME`을 작업공간의 `tmp/gradle-home`으로 지정했다.

```powershell
.\gradlew.bat test --tests 'kr.co.cking.drawing.domain.engine.*' --tests 'kr.co.cking.drawing.simulation.*' weightedDistributionSimulation bootJar --offline --no-daemon
```

- 관련 테스트 43개 통과(기존 엔진 계약 27개, 새 시뮬레이션 검증 16개).
- 시나리오별 200,000회, 총 1,200,000회 추첨에서 매 반복 계약 위반 없음.
- 서로 다른 Seed 1,200,000개 사용, 중복 Seed 없음.
- 사전 정의한 69개 확률 검정 모두 PASS. family alpha는 0.001.
- `bootJar` 성공. 운영 JAR 엔트리에 시뮬레이션 패키지가 없는 것을 확인했다.
- 전체 애플리케이션/DB 통합 테스트는 이 실행에 포함하지 않았다.

## 관측 요약

| 시나리오 | 검정 수 | 최대 절대 오차 | 판정 |
|---|---:|---:|---|
| equal | 4 | 0.001120 | PASS |
| asymmetric | 3 | 0.001140 | PASS |
| rare | 3 | 0.000125 | PASS |
| large-total | 3 | 0.001330 | PASS |
| multi-two | 12 | 0.003260 | PASS |
| multi-three-excluded | 44 | 0.009346 | PASS |

최대 절대 오차에는 조건부 확률 행도 포함한다. 조건부 행은 실제 prefix 횟수를 분모로
사용하므로 200,000보다 표본이 적고, 행마다 허용 범위가 다르다. 오차 숫자만으로
시나리오 간 품질을 비교하지 않는다. 각 행의 분모·허용 범위는 [checks.csv](checks.csv)에 있다.

1:3:6에서 1명 선정한 실제 비율은 회원 순서대로 `0.10110`, `0.29886`, `0.60004`다.
2명 선정한 포함 비율은 `0.29408`, `0.78280`, `0.92312`이며, 각각 독립 열거 기준
`41/140`, `47/60`, `97/105`와 비교하여 통과했다.

희귀 후보 1번은 기대 횟수 0.02에 대해 실제 0회였다. 이 결과만으로 해당 후보가
정확한 희귀 확률로 선택된다고 결론 내리지 않는다. 이번 표본의 검정력 한계다.
상품 배정, 모든 입력의 공정성, 암호학적 안전성은 이번 결과로 증명하지 않는다.

## 원본 파일

- [checks.csv](checks.csv): 모든 확률 검정 행과 관측값, 오차, 판정.
- [orders.csv](orders.csv): 가능한 전체 순서의 기준 확률과 실제 빈도.
- [scenarios.csv](scenarios.csv): 실행한 후보/제외/당첨자 수/반복 수.
- [environment.txt](environment.txt): 실행 환경과 클래스 바이트코드 식별.

아래 SHA-256은 UTF-8/LF CSV 파일의 값이다. 재실행 시 같은 코드·Seed 집합이면
세 CSV는 재현되며, 환경 파일의 시각 등은 달라진다.

```text
checks.csv    c60124102bac5bb0f215ff16b36320460875528918918d8fb29758a3bfdaff55
orders.csv    fccfa1495bc2704b0f762d8ad77bde738c33c826bcf0b7f0ef88ec24d07fc249
scenarios.csv 248f5d177bf69034ec40bff3a0913c6034dd38253ca22dd5e560534876999bc5
```
