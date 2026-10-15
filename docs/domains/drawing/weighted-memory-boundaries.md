# 가중 추첨 메모리 및 경계값 검증

Issue #505. 기준 구현은 develop `295749f1233ac4f54d261a2b829f8e7a1a520c42`의
`WeightedV1DrawingEngine` / `WeightedCandidatePool`이다. 운영 알고리즘과 Seed 규약은 변경하지 않는다.

## 지원 범위와 오류 계약

- 후보별 `ticketCount`는 양수 `long`이다. `0`, 음수, `Long.MIN_VALUE`는
  `CandidateValue` 생성 시 `IllegalArgumentException`으로 거부한다.
- 제외 후보를 제거한 **유효 후보**의 가중치 합계는 `Long.MAX_VALUE`까지 허용한다.
  전체 입력 합계가 넘쳐도 제외 후 유효 합계가 범위 안이면 정상 추첨한다.
- 유효 합계가 `Long.MAX_VALUE`를 넘으면 `Math.addExact`가 감지하고 후보 풀 생성이
  `IllegalArgumentException`으로 실패한다. 메시지는 `long 범위`를 포함하며 원인은
  `ArithmeticException`이다. BigInteger 총합 지원은 운영 범위에 포함하지 않는다.
- 풀의 선택 기준은 `0 <= selectedWeight < 현재 totalWeight`다. 음수, 합계와 같은 값,
  완전히 소진된 풀의 선택은 `IllegalArgumentException`으로 거부한다.
- 당첨자는 복원 없이 선정하고 `rank`는 1부터 연속한다. 제외 후 후보 수보다 큰
  `winnerCount`는 `IllegalArgumentException`으로 거부한다.
- 동일 입력/Seed는 동일 결과를 반환한다. 가중치를 배수로 바꾼 입력의 동일 Seed 결과까지
  같다고 가정하지 않는다. 난수 범위와 rejection 횟수가 달라질 수 있다.

## 경계값 및 회귀 검증

기존 `DrawingEngineTest`, `WeightedCandidatePoolTest`의 최소 가중치, 음수, 중복 후보,
오버플로, 최대 총합, Seed 결정성 테스트를 유지한다. 추가된 `WeightedDrawingBoundaryTest`는:

1. 후보 수 1/3/7/8/9/17에서 합계가 정확히 `Long.MAX_VALUE`인 풀을 끝까지 소진한다.
   매 제거 단계마다 이전 제거 순서를 새 풀에 재생해 남아 있는 **모든 구간의 첫 값과 끝 값**을
   확인한다. 작은/수십억/매우 큰 가중치가 섞인 불균등 후보도 같은 검증을 수행한다.
2. BigInteger 누적 선형 기준 구현과 선택 후보, 잔여 총합, 중복 방지, 당첨자 수를 비교한다.
   기준 구현은 Fenwick Tree를 사용하지 않고 실제 후보 목록에서 선택된 후보를 제거한다.
3. 총합 `2,147,483,648` / `9,000,000,000` / `Long.MAX_VALUE`, 후보 64명,
   당첨 1/7/64명, Seed 4개를 조합해 순위, 원본 가중치, `WEIGHTED_V1`, 결과 해시를 비교한다.
4. 제외 전 합계 오버플로/제외 후 최대 합계의 성공과, 제외 후에도 최대값+1인 입력의 거부를 검증한다.

데이터 분할과 기준 합계 계산은 테스트에서만 BigInteger를 사용하고 `longValueExact`로 변환한다.
기존 Hash fixture, Seed, 상품 조합 테스트도 함께 실행한다.

## 재현 명령

JDK 21, 저장소 루트에서 실행한다. DB/Redis/외부 API는 필요 없다.

```powershell
.\gradlew.bat test --no-daemon --console=plain --tests 'kr.co.cking.drawing.domain.*' --tests 'kr.co.cking.snapshot.domain.*' --tests 'kr.co.cking.drawing.application.DrawingSeedPolicyTest'
.\gradlew.bat drawingMemoryTest --no-daemon --console=plain
```

macOS/Linux에서는 `.\gradlew.bat` 대신 `./gradlew`를 사용한다. 기본 `test`는
`drawing-memory` 태그를 제외하고, 메모리 측정은 `drawingMemoryTest`로만 실행한다.
이 태스크는 실행마다 다시 측정하며 테스트 결과를 캐시에서 가져오지 않는다.
기본 `test` 또는 일반 `loadTest`의 힙 설정은 변경하지 않는다.

## 할당량 측정 방법

- `WeightedDrawingMemoryTest`는 `com.sun.management.ThreadMXBean.getThreadAllocatedBytes`의
  현재 스레드 누적 바이트 차이를 측정한다. 지원하지 않는 JVM은 명시적으로 실패한다.
- 테스트 worker의 초기/최대 힙은 모두 **128MiB**, worker 1개, JUnit 병렬 실행은 꺼 둔다.
- 시나리오와 측정 범위마다 워밍업 20회, 5회 실행을 묶은 표본 7개를 수집하고
  실행당 평균 바이트의 중앙값을 사용한다. 결과는 volatile sink에 보관한다.
- `pool`: 준비된 후보 목록으로 `WeightedCandidatePool`을 생성한다.
- `draw`: 준비된 `DrawInput`으로 `WeightedV1DrawingEngine.draw`를 실행한다.
  필터링, 풀 구성, 난수, 후보 제거, Winner/DrawOutput 생성과 검증을 포함한다.
- 후보 값 객체 생성과 DrawInput 정렬/검증, 기준 구현, 결과 assertion, 리포트 기록은
  측정 구간 밖이다. pool과 draw는 서로 독립적인 실행이며 두 수치를 합산하지 않는다.
- 가중치 축은 후보 10,000명/당첨 100명을 고정하고 합계만 10,000 → 10조 → 최대 long으로 늘린다.
  후보 수 축은 후보당 10억/당첨 100명을 고정하고 후보 1,000 → 10,000 → 100,000명으로 늘린다.
- 같은 후보 수의 중앙값 차이는 `max(64KiB, 최소 할당량의 5%)` 이하여야 한다.
  후보 수 증가에서는 추가 후보당 8~128바이트의 증가를 허용해 선형 할당 회귀를 확인한다.
  이는 JVM별 절대 메모리 SLA가 아니라 큰 구조 변경을 감지하는 여유 있는 기준이다.
- GC 전후 사용 힙, 최대 생존 객체 크기, RSS, DB에서 후보를 읽는 비용 또는 운영 요청 전체의
  메모리를 측정한 결과는 아니다. 프로파일 수치는 실행 JVM/JIT/객체 정렬 설정에 따라 달라진다.

생성 산출물:

- `build/reports/drawing-memory/allocation.csv`: 축/범위/입력/중앙값 및 표본 7개.
- `build/reports/drawing-memory/environment.txt`: UTC 실행 시각, JDK/VM/OS, 힙/JVM 인자, 측정 설정.
- `build/reports/tests/drawingMemoryTest/index.html`: 테스트 결과.

## 측정 결과

2026-10-08 22:51 KST (`2026-10-08T13:51:41Z`), Windows 11 amd64,
Oracle JDK `21.0.10+8-LTS-217`, Java HotSpot 64-Bit Server VM.
최대 힙은 `134,217,728`바이트, JVM 인자는 `-Xms128m -Xmx128m -ea`와
JUnit 병렬 실행 비활성화다. 아래는 실행당 할당 바이트의 중앙값이다.
원본 표본 7개도 [CSV](weighted-memory-results.csv)에 보존한다.

| 축 | 후보 수 | 당첨 수 | 총 가중치 | pool B/op | draw B/op |
| --- | ---: | ---: | ---: | ---: | ---: |
| 가중치 | 10,000 | 100 | 10,000 | 160,112 | 294,112 |
| 가중치 | 10,000 | 100 | 10,000,000,000,000 | 160,112 | 294,112 |
| 가중치 | 10,000 | 100 | 9,223,372,036,854,775,807 | 160,112 | 288,240 |
| 후보 수 | 1,000 | 100 | 1,000,000,000,000 | 16,112 | 46,512 |
| 후보 수 | 10,000 | 100 | 10,000,000,000,000 | 160,112 | 288,240 |
| 후보 수 | 100,000 | 100 | 100,000,000,000,000 | 1,600,112 | 2,547,040 |

고정 후보 수에서 총 가중치를 키워도 pool 할당은 동일하며 draw의 차이는 5,872바이트다.
후보 수가 10배씩 증가할 때 pool의 추가 할당량은 추가 후보당 정확히 16바이트였다.
draw도 추가 후보당 약 25~27바이트로 증가했다. 이 실행의 관측 범위에서 할당량은
티켓 합계에 비례하지 않고 후보 수에 따라 증가한다. 워밍업 도중 JIT 최적화로 같은 입력의
할당량도 줄어들 수 있으므로 단일 표본이나 JVM 간 절대값 일치를 요구하지 않는다.

검증 결과: 순수 Drawing/Snapshot 도메인 및 Seed 정책 테스트 **113개**, 별도 메모리 검증
**1개** 통과(실패/오류/건너뜀 0). 기존 Seed 고정 난수열, Hash fixture, 네 가지 후보/상품
조합을 포함한다. 전체 애플리케이션 통합 테스트는 로컬 MySQL 인증 오류로 중단되어
통과 결과에 포함하지 않는다.

## 운영 경로 점검

`DrawInput`은 후보 수 기준으로 중복 검사/정렬한다. `WeightedV1DrawingEngine`은 후보를 한 번
필터링하고 당첨자 수만큼 반복한다. `WeightedCandidatePool`은 후보 참조 목록과
`new long[candidates.size() + 1]`만 만들고, 후보 수만큼 Tree를 구성한다.
각 선택/제거는 Tree 인덱스를 따라 `O(log N)`에 처리한다. `DeterministicRandom`은 고정 크기
HMAC 블록을 생성하고 난수 편향을 없애는 rejection을 수행한다. 티켓 수만큼 반복하지 않는다.

풀 구성/추첨은 `O(N + K log N)`, 추가 공간은 `O(N + K)`다. 입력 정렬은 별도로 `O(N log N)`이다.
티켓 합계 길이의 배열이나 티켓별 객체를 만드는 운영 경로는 없다. 거대한 티켓 배열을 만들거나
OOM을 의도적으로 일으키는 비교 구현은 테스트에도 추가하지 않는다. 후보 수 자체가 JVM의 배열
용량/힙을 넘는 입력의 지원을 보장하는 검증은 아니다.
