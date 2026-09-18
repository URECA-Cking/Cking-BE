# Drawing 도메인

## 책임

- 공식 추첨과 재추첨의 실행 상태 및 확정 입력·출력을 저장한다.
- 추첨 결과를 순위가 있는 Winner로 저장한다.
- Winner별 후속 운영 상태를 WinnerManagement로 관리한다.

Drawing 도메인은 Event, Snapshot, Member, Seed 등 다른 도메인의 Entity를 직접 참조하지 않고 `Long` 식별자만 저장한다. 다른 도메인의 상태 변경은 해당 도메인의 Service를 통해 수행한다.

## 관리자 INITIAL Drawing 요청 API

### `POST /api/admin/events/{eventId}/drawings`

관리자 권한과 Event·Snapshot 조건을 검증한 뒤 INITIAL Drawing을 끝까지 실행한다. Seed 생성,
엔진 호출, Winner·WinnerManagement 저장, Drawing 완료, Event 상태 전이를 하나의 Transaction으로
처리한다.

- Request Body: `{ "userId": 1 }` (`Long`, 양수, 필수)
- 성공: `200 OK`, 공통 `ApiResponse`의 `data`에 `drawingId`, `eventId`, `status`,
  `winnerCount`를 반환한다.
- 요청자는 존재하는 `ADMIN` Member여야 한다.
- Event는 삭제되지 않은 `CLOSED` 상태여야 하며, 공식 Snapshot Hash 검증을 통과해야 한다.
- 완료된 INITIAL Drawing 재요청은 기존 결과를 반환한다. `READY` 또는 `RUNNING`이면 동시 명령으로
  거부하고, `FAILED`는 별도 Retry 계약을 사용한다.

| 코드 | 조건 |
| --- | --- |
| `VALIDATION_FAILED` | eventId 또는 userId가 누락·0 이하이거나 형식이 올바르지 않음 |
| `RESOURCE_NOT_FOUND` | 요청한 Member 또는 Event가 존재하지 않음 |
| `FORBIDDEN` | 요청한 Member가 ADMIN이 아님 |
| `INVALID_STATE` | Event가 삭제됐거나 CLOSED 상태가 아님 |
| `CONCURRENT_COMMAND` | 동일 Event의 INITIAL Drawing이 READY 또는 RUNNING임 |
| `SNAPSHOT_NOT_FOUND` | 공식 Snapshot이 없음 |
| `SNAPSHOT_HASH_MISMATCH` | 공식 Snapshot의 Hash 또는 집계값이 일치하지 않음 |

## Drawing 공개 Service 계약

### `DrawingPublicationService.publish(Long drawingId, Long adminId)`

FR-P2-044·통합 API 명세 v2.5 No.35. INITIAL Drawing의 결과를 공개하고 Event를
`DRAW_COMPLETED → PUBLISHED`로 전이한다. 관리자 공개 API는
`docs/domains/drawing/api.md`에 정의하며, Controller는 이 메서드를 호출해 상태 전이를
위임한다. 이번 구현 범위는 INITIAL 공개만이며, REDRAW Drawing 공개(FR-P4-115, Event가 이미
`PUBLISHED`인 경우)는 제외한다.

- 입력: `drawingId`(`Long`, 양수), `adminId`(`Long`, 양수). 외부 호출을 받은 Controller가
  전달하며, 이 메서드는 `MemberQueryService.validateAdmin(adminId)`로 관리자 권한을 검증한다.
- 반환: 불변 결과 `DrawingPublicationResult(drawingId, eventId, visibility, publishedAt, outcome)`.
  영속 상태의 `Drawing` Entity를 그대로 반환하지 않는다 — 도메인 간 참조는 Entity가 아닌
  ID·DTO를 쓴다는 원칙(README "9. 코드 구조")에 따라, 호출자가 이 도메인의 Entity에 직접
  의존하거나 같은 Transaction에서 상태를 바꿀 여지를 없앤다. `outcome`(`PublicationOutcome`)은
  이번 호출에서 실제 `PRIVATE → PUBLIC` 전이가 일어났는지(`PUBLISHED`) 아니면 이미 공개된
  상태의 멱등 재요청인지(`ALREADY_PUBLISHED`)를 구분한다. `visibility`만으로는 최초 공개와
  멱등 재요청을 구분할 수 없다(둘 다 `PUBLIC` + 기존 `publishedAt`을 반환). 호출자(시스템4)는
  **`outcome == PUBLISHED`일 때만** 신규 Winner Notification을 생성해야 한다(FR-P4-132·
  FR-P4-133) — `ALREADY_PUBLISHED`에서도 매번 Notification 생성을 시도하면, DB unique
  제약(`uk_notification_winner_type`)이 최종 중복 저장은 막아도 그 제약 위반 예외가 멱등
  성공이어야 할 호출 전체를 실패시킬 수 있다.
- **중요**: 이 메서드가 `EventCommandService.publish(eventId)`까지 이미 호출해 Event
  전이를 완료한다. 호출자는 이 메서드가 반환된 뒤 Event 전이를 별도로 다시 호출하면 안
  된다 — 재호출하면 두 번째 호출이 이미 `PUBLISHED`인 Event에 대해 `INVALID_STATE`로
  실패해 호출자의 Transaction 전체가 Rollback된다.
- 공개 조건: 대상 Drawing이 `drawType = INITIAL`, `status = COMPLETED`여야 한다(`visibility`는
  `PRIVATE`이면 새로 공개, `PUBLIC`이면 멱등 재요청). `status != COMPLETED`는 `visibility`와
  무관하게 항상 `DRAWING_NOT_COMPLETED`로 거부한다 — `status`가 `COMPLETED`가 아닌데
  `visibility`만 `PUBLIC`인 데이터 불일치를 멱등 성공으로 위장하지 않기 위해서다. 새로 공개하는
  경우 Event.status가 `DRAW_COMPLETED`여야 한다.
- Drawing `PRIVATE → PUBLIC` 전이와 `EventCommandService.publish(eventId)`를 한 DB Tx로
  묶어, 하나라도 실패하면 전체 Rollback한다(부분 반영 금지). 동시 공개 요청은 Drawing·Event
  행을 모두 잠근 뒤 조회해 직렬화하며, 뒤에 도착한 요청은 잠금 해제 후 갱신된 Drawing·Event
  상태를 다시 읽어 멱등 성공으로 처리한다. Drawing만 잠그고 Event를 일반 조회로 읽으면
  MySQL REPEATABLE READ의 트랜잭션 스냅샷 때문에 Drawing은 최신인데 Event는 낡은 값을
  보는 불일치가 생길 수 있어, Event도 같은 방식(행 잠금)으로 조회한다.
- 멱등 재요청: Drawing이 이미 `PUBLIC`이고 Event가 이미 `PUBLISHED`면 상태를 바꾸지 않고
  기존 결과를 그대로 반환한다. Drawing만 `PUBLIC`이고 Event가 `PUBLISHED`가 아니면(정상
  흐름에서는 발생하지 않아야 하는 불일치) `INVALID_STATE`로 실패해, 이 불일치를 성공으로
  위장하지 않는다.
- 당첨자 Notification 생성(FR-P4-114)은 이 메서드의 책임이 아니다. 호출자(시스템4의
  PublicationService)가 이 메서드와 Notification 생성을 자신의 Transaction 경계 안에서
  함께 처리해야 FR-P4-114의 원자성 요구를 만족한다.

`DrawingPublicationService`가 Drawing 공개와 Event 전이를 모두 완료하므로, Controller 또는
호출자는 반환 후 `EventCommandService.publish()`를 다시 호출하면 안 된다. 당첨자 Notification
생성은 이 API 범위가 아니며 별도 호출자가 `PublicationOutcome.PUBLISHED`일 때만 처리한다.

| 코드 | 조건 |
| --- | --- |
| `RESOURCE_NOT_FOUND` | 요청한 Member 또는 Event가 존재하지 않음 |
| `FORBIDDEN` | 요청한 Member가 ADMIN이 아님 |
| `DRAWING_NOT_FOUND` | Drawing이 존재하지 않음 |
| `DRAWING_NOT_COMPLETED` | Drawing.status가 COMPLETED가 아님 |
| `DRAWING_TYPE_NOT_SUPPORTED` | Drawing.drawType이 INITIAL이 아님(REDRAW는 이번 구현 범위 제외) |
| `INVALID_STATE` | Event.status가 기대 상태(DRAW_COMPLETED 또는 멱등 재요청 시 PUBLISHED)가 아님 |

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

### Seed

- `DrawSeed`는 `draw_seed.seed_value`를 32byte 바이너리로 저장하고 `DrawingSeed` 값 객체로 복원한다.
- `DrawingSeedService.createForInitial()`은 신규 Seed를 저장하고 `seedId`와 `DrawingSeed`를 함께 반환한다.
- `DrawingSeedService.reuseForRetry(seedId)`는 기존 행을 조회해 재사용하며 신규 Seed 행을 만들지 않는다.
- `DrawingSeedService.createForRedraw(previousSeedId)`는 이전 Drawing의 Seed와 다른 값을 생성해 신규 행으로 저장한다.
- REDRAW 자체의 Retry는 `createForRedraw`가 아니라 `reuseForRetry`를 사용한다.
- Seed 생성·저장은 `MANDATORY` 전파 속성으로 Drawing 실행 트랜잭션에만 참여한다. 호출자 트랜잭션이 없으면 실행을 거부하며, Drawing·Engine·Winner·Event 전이 실패 시 함께 Rollback한다.
- 애플리케이션 서비스는 반환된 `seedId`를 `Drawing`에, `DrawingSeed`를 `DrawInput`에 전달한다.

### 엔진 조립

`WeightedV1DrawingEngine`은 Spring에 의존하지 않는 순수 도메인 구현체로 유지한다.
`DrawingEngineConfig`가 현재 지원 버전인 `WEIGHTED_V1` 구현체를 `DrawingEngine` Bean으로 등록하며,
애플리케이션 서비스는 인터페이스를 생성자 주입받는다. 알고리즘이 추가되면 application 계층에서
`algorithmVersion`별 Resolver 또는 Registry로 확장한다.

### Drawing

- Event당 INITIAL Drawing은 `drawNo = 0`, `drawType = INITIAL`이다.
- INITIAL Drawing은 `originalDrawingId`와 `redrawRequestId`를 갖지 않는다.
- 최초 상태는 `READY`, 공개 상태는 `PRIVATE`, 시도 횟수는 0이다.
- `(eventId, drawNo)`와 `seedId`는 각각 유일하다.
- INITIAL Drawing의 `snapshotId`, `eventId`, `drawMethod`, `algorithmVersion`, `winnerCount`는 `VerifiedSnapshot`으로만 생성할 수 있는 하나의 `DrawingSnapshotContract`에서 가져온다. `VerifiedSnapshot`은 public 생성자를 제공하지 않으며 Snapshot 무결성 검증 경로에서만 생성한다.
- `snapshotId`, `eventId`, `drawMethod`, `algorithmVersion` 일치는 DB 복합 FK로도 강제한다. REDRAW의 `winnerCount`는 결원 수이므로 Snapshot 원본 당첨자 수와 다를 수 있다.
- 동시 명령 감지를 위해 `version`을 낙관적 락 필드로 사용한다.

상태는 `READY`, `RUNNING`, `FAILED`, `COMPLETED`를 사용하고 공개 상태는 `PRIVATE`, `PUBLIC`을 사용한다. INITIAL 실행은 `READY → RUNNING → COMPLETED`로 전이한다.

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
- `DrawingRepository.findByEventIdAndDrawNoForUpdate(eventId, drawNo)`: 실행 명령에서 최신 Drawing을 잠금 조회
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

## 관리자 Drawing 조회 API

상세 요청·응답과 오류 계약은 [관리자 Drawing 조회 API](admin-query-api.md)를 참고한다.
