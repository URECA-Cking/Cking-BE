# DrawingEngine JMH 성능 테스트

## 목적

`DrawingEngine`의 처리량, 실행 시간 분포, 메모리 할당량을 일반 단위 테스트와 분리해 측정한다.
단위 테스트는 결과와 불변조건을 검증하고, 이 벤치마크는 JVM 워밍업과 fork를 통제한 상태에서 성능 특성을 관찰한다.

벽시계 시간에 대한 합격 기준을 일반 `test` 태스크에 두지 않는다. 실행 장비와 JVM 환경이 달라지면 결과도 달라지므로 수치는 같은 환경에서 얻은 기준선과 비교한다.

## 측정 시나리오

| 파라미터 | 값 |
| --- | --- |
| 후보 수 `candidateCount` | 1,000 / 10,000 / 100,000 |
| 당첨자 수 `winnerCount` | 1 / 10 / 100 |
| 조합 수 | 9 |
| Seed | 고정된 256-bit fixture |
| Candidate 가중치 | memberId를 기준으로 1~100 반복 |
| 제외 대상 | 없음 |

후보 목록 생성과 `DrawInput` 생성은 `@Setup(Level.Trial)`에서 수행한다. 따라서 측정 구간에는 `DrawingEngine.draw()`의 후보 필터링, 가중치 자료구조 생성, 결정적 난수 생성 및 비복원 당첨자 선정 비용만 포함된다.

## 측정 지표

- `drawThroughput`: 초당 완료한 추첨 횟수인 `ops/s`
- `drawLatency`: 표본 실행 시간인 `ms/op`; JSON 결과의 `scorePercentiles["95.0"]`가 p95
- `gc.alloc.rate.norm`: 추첨 1회당 할당량인 `B/op`
- `gc.alloc.rate`: 초당 할당량인 `MB/sec`
- `gc.count`, `gc.time`: 측정 중 GC 횟수와 소요 시간

GC profiler의 메모리 값은 추첨 1회가 새로 할당한 양을 뜻한다. 프로세스의 최대 RSS나 JVM 최대 힙 사용량과 동일한 지표는 아니다.

## 전체 벤치마크

```powershell
$env:GRADLE_USER_HOME='C:\Users\ASUS\.gradle'
.\gradlew.bat jmh --no-daemon
```

기본 설정은 다음과 같다.

- 워밍업: 3회, 회당 1초
- 측정: 5회, 회당 1초
- fork: 2회
- 스레드: 1개
- 힙: 초기 1GB, 최대 1GB
- profiler: JMH `gc`

결과 파일:

- 사람이 읽는 로그: `build/reports/jmh/human.txt`
- 구조화 결과: `build/reports/jmh/results.json`

## Smoke 실행

벤치마크 코드와 profiler 연결만 빠르게 검증할 때 사용한다. 후보 1,000명, 당첨자 1명 조합만 워밍업 1회와 측정 1회로 실행하므로 성능 기준선으로 사용하지 않는다.

```powershell
$env:GRADLE_USER_HOME='C:\Users\ASUS\.gradle'
.\gradlew.bat jmhSmoke --no-daemon
```

결과 파일:

- 사람이 읽는 로그: `build/reports/jmh/smoke-human.txt`
- 구조화 결과: `build/reports/jmh/smoke-results.json`

## 결과 비교 원칙

1. 동일한 JDK, CPU 전원 정책, 가용 코어, 힙 크기에서 실행한다.
2. 백그라운드 부하를 줄이고 전체 벤치마크 결과끼리 비교한다.
3. 처리량과 p95를 함께 확인해 평균값만으로 긴 지연을 숨기지 않는다.
4. `B/op`와 할당률을 함께 확인해 처리량 증가가 과도한 객체 할당을 동반하는지 확인한다.
5. 결과 수치를 코드에 고정된 성공 기준으로 넣지 않고 기준선 파일 또는 성능 보고서에서 비교한다.
