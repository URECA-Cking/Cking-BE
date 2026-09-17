# Drawing 도메인

## 책임

- 공식 추첨과 재추첨의 실행 상태 및 확정 입력·출력을 저장한다.
- 추첨 결과를 순위가 있는 Winner로 저장한다.
- Winner별 후속 운영 상태를 WinnerManagement로 관리한다.

Drawing 도메인은 Event, Snapshot, Member, Seed 등 다른 도메인의 Entity를 직접 참조하지 않고 `Long` 식별자만 저장한다. 다른 도메인의 상태 변경은 해당 도메인의 Service를 통해 수행한다.

## 관리자 INITIAL Drawing 요청 API

### `POST /api/admin/events/{eventId}/drawings`

현재 이 API는 INITIAL Drawing 실행 전의 관리자 권한·Event·Snapshot 조건을 검증한다. 실제
`Drawing` 생성, Seed 연결, 엔진 실행, Winner 저장은 실행 오케스트레이션에서 수행한다.

- Request Body: `{ "userId": 1 }` (`Long`, 양수, 필수)
- 성공: `200 OK`, 공통 `ApiResponse`의 `data`에 `eventId`, `snapshotId`, `winnerCount`,
  `drawMethod`, `algorithmVersion`, `candidateCount`를 반환한다.
- 요청자는 존재하는 `ADMIN` Member여야 한다.
- Event는 삭제되지 않은 `CLOSED` 상태여야 하며, 공식 Snapshot Hash 검증을 통과해야 한다.

| 코드 | 조건 |
| --- | --- |
| `VALIDATION_FAILED` | eventId 또는 userId가 누락·0 이하이거나 형식이 올바르지 않음 |
| `RESOURCE_NOT_FOUND` | 요청한 Member 또는 Event가 존재하지 않음 |
| `FORBIDDEN` | 요청한 Member가 ADMIN이 아님 |
| `INVALID_STATE` | Event가 삭제됐거나 CLOSED 상태가 아님 |
| `SNAPSHOT_NOT_FOUND` | 공식 Snapshot이 없음 |
| `SNAPSHOT_HASH_MISMATCH` | 공식 Snapshot의 Hash 또는 집계값이 일치하지 않음 |

## 추첨 엔진 계약

`DrawingEngine`은 `DrawInput`을 받아 `DrawOutput`을 반환하는 순수 추첨 엔진 인터페이스다.
`WeightedV1DrawingEngine`이 `WEIGHTED_V1` 알고리즘을 구현하며, 새로운 알고리즘은 별도 구현체로 추가한다.
`DrawOutput`은 당첨자의 `memberId`와 `rank`가 각각 중복되지 않도록 검증한다.

### WEIGHTED_V1

`WEIGHTED_V1`은 응모권 수를 정수 가중치로 사용하여 복원 없이 당첨자를 선정한다.

1. 후보를 `memberId ASC`로 정규화한다.
2. 제외 대상의 가중치를 추첨 후보군에 포함하지 않는다.
3. 현재 가중치 합계가 `T`이면 결정적 난수 생성기로 `[0, T)`의 정수를 하나 생성한다.
4. 해당 정수가 포함된 누적 가중치 구간의 후보를 당첨자로 선정한다.
5. 선정된 후보의 가중치를 0으로 갱신하여 이후 순위에서 제외한다.
6. `winnerCount`만큼 반복하며 선정 순서를 Rank로 사용한다.

가중치 정규화에 부동소수점을 사용하지 않는다. 후보 정렬 기준과 난수 소비 횟수를 유지하므로 같은
Snapshot, Seed, Algorithm Version, Exclusion List, winnerCount는 항상 같은 Winner와 Rank를 만든다.

### 자료구조

누적 가중치의 구간 탐색과 당첨 후보 제거에는 Fenwick Tree를 사용한다.

- 후보군 구성: `O(candidateCount)`
- 당첨자 1명 선택 및 제거: `O(log candidateCount)`
- 전체 추첨: `O(candidateCount + winnerCount × log candidateCount)`
- 추가 공간: `O(candidateCount)`

단일 추첨에 적합한 선형 누적 탐색은 당첨자를 여러 명 선정할 때 매 순위마다 합계 계산과 후보 탐색을
반복한다. 정적 분포에서 유리한 누적합 이분 탐색, Hopscotch, Alias 방식은 당첨자 제거 후 분포가 매번
변하는 복원 없는 추첨에서 갱신 또는 재구성이 필요하므로 사용하지 않는다.

## 영속성 모델

### Drawing

- Event당 INITIAL Drawing은 `drawNo = 0`, `drawType = INITIAL`이다.
- INITIAL Drawing은 `originalDrawingId`와 `redrawRequestId`를 갖지 않는다.
- 최초 상태는 `READY`, 공개 상태는 `PRIVATE`, 시도 횟수는 0이다.
- `(eventId, drawNo)`와 `seedId`는 각각 유일하다.
- INITIAL Drawing의 `snapshotId`, `eventId`, `drawMethod`, `algorithmVersion`, `winnerCount`는 `VerifiedSnapshot`으로만 생성할 수 있는 하나의 `DrawingSnapshotContract`에서 가져온다. `VerifiedSnapshot`은 public 생성자를 제공하지 않으며 Snapshot 무결성 검증 경로에서만 생성한다.
- `snapshotId`, `eventId`, `drawMethod`, `algorithmVersion` 일치는 DB 복합 FK로도 강제한다. REDRAW의 `winnerCount`는 결원 수이므로 Snapshot 원본 당첨자 수와 다를 수 있다.
- 동시 명령 감지를 위해 `version`을 낙관적 락 필드로 사용한다.

상태는 `READY`, `RUNNING`, `FAILED`, `COMPLETED`를 사용하고 공개 상태는 `PRIVATE`, `PUBLIC`을 사용한다. 상태 전이 메서드는 실행 오케스트레이션 작업에서 추가한다.

### Winner

- Event, Drawing, Member를 각각 ID로 참조한다.
- Winner의 `eventId`는 연결된 Drawing의 `eventId`와 일치해야 하며 DB 복합 FK로도 강제한다.
- Drawing 안에서 `rankInDrawing`은 중복될 수 없다.
- 동일 Event에서 같은 Member가 다시 Winner가 될 수 없다.
- Drawing 결과 조회는 `rankInDrawing ASC`를 사용한다.

### WinnerManagement

- Winner 하나에 WinnerManagement 하나만 존재한다.
- 초기 상태는 `SELECTED`다.
- 후속 상태는 `RECEIVED`, `DECLINED`, `DISQUALIFIED`다.

## Repository 계약

- `DrawingRepository.findByEventIdAndDrawNo(eventId, drawNo)`: Event의 특정 차수 Drawing 조회
- `DrawingRepository.existsByEventIdAndDrawNo(eventId, drawNo)`: 중복 생성 사전 확인
- `WinnerRepository.findAllByDrawingIdOrderByRankInDrawingAsc(drawingId)`: 추첨 결과 순위 조회
- `WinnerRepository.existsByEventIdAndMemberId(eventId, memberId)`: Event 내 중복 당첨 확인
- `WinnerManagementRepository.findByWinnerId(winnerId)`: Winner 운영 상태 조회

DB 구조와 제약조건의 정본은 `src/main/resources/db/migration/`이다.

## DrawInput Hash 계약

`inputHash`는 확정된 추첨 입력 전체를 식별한다. Hash 알고리즘은 SHA-256이고 결과는 64자리
lowercase hex 문자열이다. 정규화 문자열은 UTF-8로 인코딩하며 줄바꿈은 LF(`\n`)만 사용한다.
마지막 행 뒤에도 LF를 포함하고 숫자는 0 채우기 없는 10진수 문자열로 표현한다.

Candidate는 `memberId ASC`, 제외 대상은 `memberId ASC`로 정렬한다. 두 목록이 비어 있어도 섹션
헤더를 생략하지 않는다.

```text
CKING_DRAW_INPUT_V1
eventId={eventId}
snapshotId={snapshotId}
snapshotHash={snapshotHash}
seed={seed}
algorithmVersion={algorithmVersion}
winnerCount={winnerCount}
candidates
{memberId},{ticketCount}
excludedMemberIds
{memberId}
```

예시는 다음과 같다.

```text
CKING_DRAW_INPUT_V1
eventId=10
snapshotId=20
snapshotHash=abababababababababababababababababababababababababababababababab
seed=0101010101010101010101010101010101010101010101010101010101010101
algorithmVersion=WEIGHTED_V1
winnerCount=2
candidates
1,3
2,7
excludedMemberIds
3
4
```

위 문자열의 SHA-256은
`d09cdf4de6c7e737db35653f1d4ebca18aca5d52546cfa2d08c26f58a94cd969`이다.

## Result Hash 계약

`resultHash`는 결과가 어떤 입력에서 생성됐는지 함께 검증할 수 있도록 `inputHash`를 포함한다.
Winner는 전달 순서와 관계없이 `rank ASC`로 정렬한다. Winner 행의 필드 순서는 `rank`, `memberId`,
`appliedTicketCount`이며 중복 rank 또는 중복 memberId는 허용하지 않는다.

```text
CKING_DRAW_RESULT_V1
inputHash={inputHash}
algorithmVersion={algorithmVersion}
winners
{rank},{memberId},{appliedTicketCount}
```

Winner가 없으면 `winners\n`에서 끝난다. `resultHash` 자신은 정규화 대상에 포함하지 않는다.

예시는 다음과 같다.

```text
CKING_DRAW_RESULT_V1
inputHash=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
algorithmVersion=WEIGHTED_V1
winners
1,1,3
2,2,7
```

위 문자열의 SHA-256은
`22afbb6396cb7df1e9772d386d76ef3646b3c14f97dfd51bfe078d8116ecc657`이다.

정규화와 Hash 생성은 외부 저장소나 현재 시각에 의존하지 않는다. `drawing.input_hash`,
`drawing.result_hash`, Winner와 상태 전이를 저장하는 트랜잭션은 추첨 실행 오케스트레이션의 책임이다.
