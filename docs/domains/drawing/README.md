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

FR-P2-044·FR-P4-115·API 인덱스 내부 No.58. Drawing 유형에 맞게 결과를 공개한다. INITIAL은
Event를 `DRAW_COMPLETED → PUBLISHED`로 전이하고, REDRAW는 이미 `PUBLISHED`인 Event를 유지한다. 관리자 공개 API는
`docs/domains/drawing/api.md`에 정의하며, Controller는 시스템4 `PublicationService`에 공개
유스케이스를 위임하고, `PublicationService`가 이 메서드를 호출한다.

- 입력: `drawingId`(`Long`, 양수), `adminId`(`Long`, 양수). 외부 호출을 받은 Controller가
  `PublicationService`에 전달하며, 이 메서드는 `MemberQueryService.validateAdmin(adminId)`로
  관리자 권한을 검증한다.
- 반환: 불변 결과 `DrawingPublicationResult(drawingId, eventId, drawingType, visibility, publishedAt, outcome)`.
  `drawingType`은 호출자(시스템4)가 최초 공개된 Drawing의 Winner에게 `INITIAL_WINNER` 또는
  `REDRAW_WINNER` Notification을 생성할 때 사용하는 내부 계약 값이다. 외부 REST 응답에는 노출하지 않는다.
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
- 공개 조건: 대상 Drawing은 `status = COMPLETED`여야 한다(`visibility`는 `PRIVATE`이면 새로 공개,
  `PUBLIC`이면 멱등 재요청). `status != COMPLETED`는 `visibility`와
  무관하게 항상 `DRAWING_NOT_COMPLETED`로 거부한다 — `status`가 `COMPLETED`가 아닌데
  `visibility`만 `PUBLIC`인 데이터 불일치를 멱등 성공으로 위장하지 않기 위해서다. INITIAL을 새로 공개하는
  경우 Event.status가 `DRAW_COMPLETED`여야 하고, REDRAW는 새 공개와 재요청 모두 Event.status가
  `PUBLISHED`여야 한다.
- INITIAL은 Drawing `PRIVATE → PUBLIC` 전이와 `EventCommandService.publish(eventId)`를 한 DB Tx로
  묶어, 하나라도 실패하면 전체 Rollback한다(부분 반영 금지). REDRAW는 Drawing만 `PRIVATE → PUBLIC`으로
  전이하며 `EventCommandService.publish(eventId)`를 호출하지 않는다. 동시 공개 요청은 Drawing·Event
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

`DrawingPublicationService`가 Drawing 공개와 Event 전이를 모두 완료하므로, `PublicationService`는
반환 후 `EventCommandService.publish()`를 다시 호출하면 안 된다. 당첨자 Notification 생성은
`PublicationService`가 `PublicationOutcome.PUBLISHED`일 때만 처리한다.

| 코드 | 조건 |
| --- | --- |
| `RESOURCE_NOT_FOUND` | 요청한 Member 또는 Event가 존재하지 않음 |
| `FORBIDDEN` | 요청한 Member가 ADMIN이 아님 |
| `DRAWING_NOT_FOUND` | Drawing이 존재하지 않음 |
| `DRAWING_NOT_COMPLETED` | Drawing.status가 COMPLETED가 아님 |
| `INVALID_STATE` | Event.status가 기대 상태(DRAW_COMPLETED 또는 멱등 재요청 시 PUBLISHED)가 아님 |

## 추첨 엔진 계약

`DrawingEngine`과 `PrizeAllocationEngine`의 알고리즘, 선택 조합, 결정성 및 재고 규칙은
[추첨 알고리즘 계약](algorithms.md)을 정본으로 한다.

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

각 구현체는 Spring에 의존하지 않는 순수 도메인 엔진으로 유지한다. `DrawingEngineConfig`는
후보와 상품 알고리즘을 각각 Registry에 등록하고 Resolver를 어플리케이션 계층에 주입한다.

### Drawing

- Event당 INITIAL Drawing은 `drawNo = 0`, `drawType = INITIAL`이다.
- INITIAL Drawing은 `originalDrawingId`와 `redrawRequestId`를 갖지 않는다.
- 최초 상태는 `READY`, 공개 상태는 `PRIVATE`, 시도 횟수는 0이다.
- `(eventId, drawNo)`와 `seedId`는 각각 유일하다.
- INITIAL Drawing의 `snapshotId`, `eventId`, `drawMethod`, `algorithmVersion`, `winnerCount`는 `VerifiedSnapshot`으로만 생성할 수 있는 하나의 `DrawingSnapshotContract`에서 가져온다. `VerifiedSnapshot`은 public 생성자를 제공하지 않으며 Snapshot 무결성 검증 경로에서만 생성한다.
- `snapshotId`, `eventId`, `drawMethod`, `algorithmVersion` 일치는 DB 복합 FK로도 강제한다. REDRAW의 `winnerCount`는 결원 수이므로 Snapshot 원본 당첨자 수와 다를 수 있다.
- `prizeAlgorithmVersion`도 Snapshot에서 Drawing으로 복제하고 DB 복합 FK로 일치를 강제한다.
- 동시 명령 감지를 위해 `version`을 낙관적 락 필드로 사용한다.

상태는 `READY`, `RUNNING`, `FAILED`, `COMPLETED`를 사용하고 공개 상태는 `PRIVATE`, `PUBLIC`을 사용한다. INITIAL 실행은 `READY → RUNNING → COMPLETED`로 전이한다.

### Winner

- Event, Drawing, Member를 각각 ID로 참조한다.
- Winner의 `eventId`는 연결된 Drawing의 `eventId`와 일치해야 하며 DB 복합 FK로도 강제한다.
- Drawing 안에서 `rankInDrawing`은 중복될 수 없다.
- 상품이 설정된 V2 추첨의 Winner는 `snapshotPrizeId`, `prizeKey`, `prizeDisplayName`, `prizePriority`를 함께 저장한다.
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

## 상품 포함 Hash V2 계약

기존 `CKING_DRAW_INPUT_V1`과 `CKING_DRAW_RESULT_V1`은 변경하지 않는다. 상품 Snapshot이 있는 추첨은
`CKING_DRAW_INPUT_V2`와 `CKING_DRAW_RESULT_V2`를 사용한다.

- Input V2는 V1 입력에 `prizeAlgorithmVersion`과 상품별 `prizeKey`, 표시명, priority, weight, quantity를 추가한다.
- Result V2는 Winner의 rank, memberId, appliedTicketCount에 배정 `prizeKey`를 추가한다.
- 문자열 필드는 UTF-8 bytes를 URL-safe Base64 without padding으로 정규화하고, 상품은 `priority ASC, prizeKey ASC`로 정렬한다.
- 상품 설정 변경은 Input Hash를, 당첨자별 상품 변경은 Result Hash를 반드시 변경한다.
- Candidate 추첨, 상품 배정, Winner·WinnerManagement 저장, Drawing 완료, Event 전이는 하나의 Transaction이다. 어느 단계든 실패하면 상품이 일부 Winner에만 저장되는 상태를 남기지 않는다.

## 관리자 Drawing 조회 API

상세 요청·응답과 오류 계약은 [관리자 Drawing 조회 API](admin-query-api.md)를 참고한다.

## Drawing 재현 검증 API

원본 Seed 결정적 재현 검증과 새 Seed 기반 독립 재실행의 당첨 인원 수 검증 계약은
[Drawing 재현 검증 API](verification-api.md)를 참고한다.
