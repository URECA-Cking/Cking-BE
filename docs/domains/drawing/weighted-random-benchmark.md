# 가중치 비복원 추첨 비교 (#503)

## 목적과 실행 방식

기준 운영 코드는 develop `295749f1233ac4f54d261a2b829f8e7a1a520c42`의
`WEIGHTED_V1`이다. 비교 범위는 응모자 선정이며 상품 배정, DB, Redis, HTTP는 제외한다.
운영 엔진과 난수 규칙은 변경하지 않는다.

JMH 플러그인과 의존성을 복구하는 대신 JDK 21 전용 실행 도구를 선택했다.
`System.nanoTime()`과 `com.sun.management.ThreadMXBean`으로 시간과 현재 스레드의
할당 바이트를 기록한다. 새 외부 의존성이 없으며 일반 CI에 무거운 측정을 추가하지 않는다.
이 선택은 로컬 알고리즘 비교를 위한 것으로 JMH의 통계적 통제 수준을 제공하지 않는다.

- 비교 구현/실행기: `src/benchmark/java/kr/co/cking/drawing/domain/engine/`
- 결과 일치/상한/기록 테스트: `WeightedComparisonTest`
- 명시적 실행: `weightedRandomBenchmark` (`test`, `check`에서 실행하지 않음)
- 일반 테스트는 작은 비교 검증을 위해 benchmark 소스를 컴파일하지만 전체 측정은 실행하지 않는다.
- `bootJar`에는 benchmark 클래스와 추가 벤치마크 라이브러리가 포함되지 않는다.

## 동일성 계약

1. 운영 `DrawInput`을 재사용해 검증하고 `memberId ASC`로 정렬한다.
2. 운영 엔진처럼 제외 회원을 제거한다. 이후 합계를 `Math.addExact`로 검증한다.
3. 각 실행에서 같은 256비트 Seed로 새 `DeterministicRandom`을 생성한다.
4. 당첨 순위마다 `nextLong(현재 남은 총 가중치)`를 한 번 호출한다.
   난수기 내부의 편향 제거용 재호출도 같은 구현을 사용한다.
5. 해당 누적 구간의 회원을 선정하고 그 회원의 전체 가중치를 제거한다.
   티켓 배열은 해당 회원의 모든 티켓을 안정적으로 압축 제거한다.
6. `rank`, `memberId`, `appliedTicketCount`와 알고리즘 버전까지 운영 결과와 비교한다.
   각 시나리오는 측정 전에 결과 일치를 검증하며 불일치 시 실행을 실패시킨다.

Fenwick 비교는 같은 패키지의 benchmark source set에서 **운영 `WeightedCandidatePool`을
직접 호출**한다. 운영 코드를 복사하거나 접근 제한자를 변경하지 않는다.

| 방식 | 구성 | 당첨 1명 선정 및 제거 | 풀 공간 |
|---|---|---|---|
| 티켓 배열 | O(T) | 선택 O(1), 모든 티켓 검사/압축 O(T) | O(T) |
| 누적합 선형 | O(N) | 선형 탐색 + 선택 위치 이후 누적합 갱신 O(N) | O(N) |
| 누적합 이진 | O(N) | 탐색 O(log N) + 선택 위치 이후 누적합 갱신 O(N) | O(N) |
| 운영 Fenwick | O(N) | 탐색 및 전체 회원 가중치 제거 O(log N) | O(N) |

N은 제외 후 후보 수, T는 총 가중치다. 누적합 구현은 제거된 위치를 가중치 0으로 남겨
정렬을 보존한다. 이진 탐색은 누적합이 선택값보다 **큰** 최초 위치를 찾는다.
탐색만 따로 떼어 누적합 갱신 비용을 숨기지 않는다.

## 시나리오와 자원 상한

입력 후보 수 1,000/10,000명에 다음 조합을 적용해 총 20개 시나리오를 만든다.

- 20의 배수 회원 5%를 제외: 실제 후보 수 950/9,500명.
- 후보 순서를 `java.util.Random(503)`으로 한 번 섞어 모든 방식에 같은 입력 제공.
  이는 입력 생성용이며 선정 난수는 항상 운영 `DeterministicRandom`이다.
- 당첨자 수: 1명, 실제 후보 수의 1%/10%를 정수 내림한 값.
- 가중치: 전원 1, 전원 100, 10% 회원 1,000/나머지 1.
  고가중치 회원은 `memberId % 10 == 1`로 정해 제외 대상과 겹치지 않는다.
- 추가 스트레스: 전원 1,000,000, 당첨자 10% (두 후보 규모).
  N이 같아도 T가 커질 때 티켓 배열의 한계를 확인한다.

티켓 배열 기본 상한은 1,000,000개, 배열 추정 메모리 8 MiB다.
배열 생성 전에 티켓 개수, Java 배열 길이, 바이트 상한 순서로 검사한다.
추정 바이트는 `align8(24 + T * 4)`이며 int 배열 헤더를 보수적으로 잡았다.
초과하면 모든 단계에 `SKIPPED`와 `MAX_TICKETS`, `JAVA_ARRAY_LIMIT`,
`MAX_TICKET_BYTES` 중 사유를 기록하고 나머지 방식은 계속 측정한다.
기본 시나리오에서는 큰 편향 입력 3건과 백만 가중치 입력 2건이 티켓 상한을 초과한다.

이 바이트 상한은 **티켓 배열 자체의 추정치**다. 후보 객체, 정렬, 결과, JVM 전체 메모리
상한과 같지 않다. 실제 연산의 할당량은 별도 계측한다. 상한을 올릴 때 힙 여유도 고려한다.

## 측정 단위

각 시나리오/방식/단계마다 기본 5회 워밍업 후 20회 기록한다.
매 반복은 새 입력 또는 새 풀을 사용한다. 각 단계는 독립적인 실행으로 측정한다.

| 단계 | 계측 대상 | 계측 밖 준비 |
|---|---|---|
| NORMALIZE | DrawInput 검증/정렬, 제외, long 합계 검증 | 같은 비정렬 원본 입력 생성 |
| BUILD | 해당 풀 구성과 티켓 상한 검사 | 정규화 |
| SELECT | 새 난수기 초기화, K명 선정/제거/누적합 갱신, DrawOutput 생성/검증 | 정규화와 새 풀 구성 |
| TOTAL | NORMALIZE → BUILD → SELECT 전체 파이프라인 | 원본 입력 생성 |

TOTAL은 세 단계 측정값의 합이 아니라 별도의 전체 실행 측정값이다.
비교의 공통 전처리에는 운영 엔진 외부의 `DrawInput` 정렬과 비교 도구의 총합 검증도 포함된다.
따라서 운영 `draw()` 단독 시간이나 서비스 응답시간으로 해석하지 않는다.

- p50/p95: 개별 실행 지연시간(ns)의 nearest-rank 백분위수.
- 처리량: `1e9 / 평균 지연시간(ns)`. SELECT/TOTAL의 1 op는 K명 전체 추첨이다.
  병렬 요청 처리량이나 초당 당첨자 수가 아니다.
- 할당량: 측정 스레드의 누적 할당 바이트 차이, 평균 bytes/op.
  살아 있는 힙/RSS/최대 메모리가 아니며 다른 스레드 할당은 제외한다.
  지원되지 않는 JVM에서는 `-1`을 기록한다.
- 결과는 volatile sink에 보관한다. 입력 생성, CSV 기록과 단계별 사전 준비는 타이머 밖이다.
- JVM은 `-Xms512m -Xmx512m -XX:+UseG1GC`, 단일 측정 스레드다.

## 재실행

저장소 루트에서 JDK 21을 사용한다. 출력 디렉터리는 새 이름을 사용해야 하며,
이미 존재하면 원본 결과를 덮어쓰지 않고 실패한다. Git revision은 실행 대상 소스를 가리킨다.

```powershell
$env:GRADLE_USER_HOME='C:\Users\ASUS\Desktop\Cking\tmp\gradle-home'
.\gradlew.bat test --no-daemon --tests 'kr.co.cking.drawing.domain.*'

$benchmarkRevision = git rev-parse HEAD
1..3 | ForEach-Object {
    .\gradlew.bat weightedRandomBenchmark --no-daemon `
        "-PbenchmarkOutput=build/weighted-random-fork-$_" `
        "-PbenchmarkRevision=$benchmarkRevision" `
        '-PbenchmarkCpu=AMD Ryzen 7 5800H with Radeon Graphics'
    if ($LASTEXITCODE -ne 0) { throw '벤치마크 실패' }
}
```

```bash
./gradlew test --no-daemon --tests 'kr.co.cking.drawing.domain.*'
for fork in 1 2 3; do
  ./gradlew weightedRandomBenchmark --no-daemon \
    "-PbenchmarkOutput=build/weighted-random-fork-$fork" \
    "-PbenchmarkRevision=$(git rev-parse HEAD)" \
    '-PbenchmarkCpu=실행 장비의 CPU 모델' || exit 1
done
```

CPU를 생략하면 `PROCESSOR_IDENTIFIER`(없으면 unknown)를 기록한다.
조정 옵션은 `benchmarkWarmups`, `benchmarkIterations`, `benchmarkSeed`,
`benchmarkMaxTickets`, `benchmarkMaxTicketBytes`다. 워밍업/반복은 양수여야 한다.
진단용으로 횟수를 낮춘 결과는 본 측정과 구분한다.

각 fork 출력은 다음 파일을 포함한다.

- `environment.txt`: 시각, 소스 revision, JDK/VM, OS, CPU/논리 코어, 실제 JVM 옵션/최대 힙,
  Seed, 워밍업/반복, 티켓 상한, 할당량 지원 여부, 집계 정의.
- `samples.csv`: 제외되지 않은 실행의 모든 개별 시간/할당량과 입력 차원.
- `summary.csv`: 단계별 p50/p95, 처리량, 평균 할당량 또는 제외 사유.

## 결과 및 해석

실측 결과와 운영 선택 근거는 [측정 결과](weighted-random-results.md)에 정리한다.

## 한계

- 단일 개발 장비의 짧은 반복 측정이다. CPU 주파수, OS 스케줄링, 백그라운드 부하,
  JIT/GC에 영향을 받는다. CPU affinity/주파수는 고정하지 않았으며 환경 파일에 장비를 기록한다.
- 새 JVM 3개로 반복하되 방법/단계 순서는 고정이다. 앞선 코드 실행의 JIT 이득과 준비 작업의
  GC 영향이 남을 수 있다. 단계별 수치와 전체 수치가 더해지지 않는 것도 이 때문이다.
- p95의 표본 수는 fork당 20개다. 유의한 차이 검정/신뢰구간/서비스 SLO를 제공하지 않는다.
- 공통 HMAC 난수/입출력 검증 비용이 작은 N/K의 자료구조 차이를 가릴 수 있다.
- 비교 구현은 명시한 선형/이진 누적합과 안정적 티켓 압축 방식이다. 모든 가능한 최적화를
  대표하지 않는다. 운영 교체 판단에는 더 긴 워밍업/반복과 대상 서버 재측정이 필요하다.
- `WEIGHTED_V1`의 결과/난수 규칙 변경이 필요하면 별도 버전과 Issue로 분리한다.
