# Snapshot 도메인

## 책임

- `CLOSED` Event의 공식 추첨 대상 명단을 확정한다.
- Event별 응모 내역을 회원 단위로 합산한다.
- 추첨 조건과 후보 목록을 정규화하여 Snapshot Hash를 생성한다.
- Event 하나에 공식 Snapshot 하나만 존재하도록 보장한다.

Snapshot은 마감 트랜잭션이 Commit된 뒤 별도 트랜잭션에서 생성한다. 생성된 Snapshot과 Candidate는 수정하거나 다시 생성하지 않는다.

## 내부 서비스 계약

### `OfficialSnapshotService.createIfAbsent(Long eventId)`

1. Event 행을 잠가 동일 Event의 동시 생성을 직렬화한다.
2. 이미 공식 Snapshot이 있으면 Event의 현재 상태와 관계없이 기존 결과를 반환한다.
3. Snapshot이 없으면 Event 상태가 `CLOSED`인지 검증한다.
4. `event_entry`를 `member_id`로 묶어 `used_ticket_count`를 합산한다.
5. 합계가 0보다 큰 후보만 `memberId ASC`로 정렬한다.
6. Snapshot Hash를 생성한다.
7. Event 상품 설정을 `priority ASC, prizeKey ASC`로 조회한다.
8. Snapshot, Candidate, 상품 등급을 한 트랜잭션으로 저장한다.

상품 등급의 식별자, 표시명, priority, 확률 가중치, 수량과 Event에서 선택한 상품 알고리즘 버전은 공식
Snapshot 이후 변경하지 않는다.

DB의 `UNIQUE(draw_snapshot.event_id)`는 애플리케이션 잠금 외의 최종 중복 방어선이다.

오류 코드는 다음과 같다.

| 코드 | 조건 |
| --- | --- |
| `EVENT_NOT_FOUND` | Event가 존재하지 않음 |
| `EVENT_NOT_CLOSED` | 공식 Snapshot이 없고 Event가 `CLOSED`가 아님 |
| `SNAPSHOT_NOT_FOUND` | 추첨에 사용할 공식 Snapshot이 없음 |
| `SNAPSHOT_HASH_MISMATCH` | 저장된 Hash와 재계산한 Hash 또는 집계값이 일치하지 않음 |

### Snapshot 누락 복구

정상 마감 경로는 `CLOSED` Commit 이후 `OfficialSnapshotService.createIfAbsent(eventId)`를 호출한다.
이 호출이 실패해도 Event를 이전 상태로 되돌리지 않으며, `SnapshotRecoveryScheduler`가 누락을 복구한다.

- `CLOSED`이고 `closedAt`이 복구 유예 시간 이상 지난 Event만 조회한다.
- 공식 Snapshot이 없는 Event를 `closedAt ASC, eventId ASC` 순서로 한정된 개수만 조회한다.
- 실패는 `snapshot_recovery_failure`에 기록하고 다음 시도 시각을 1분부터 지수적으로 늦춘다. 최대
  대기 시간은 1시간이며, 성공하면 실패 기록을 제거한다. 반복 실패 Event가 배치를 독점하지 않도록
  다음 시도 시각이 지나지 않은 Event는 조회에서 제외한다.
- 대상별로 `OfficialSnapshotService.createIfAbsent(eventId)`를 호출해 기존 잠금·멱등 계약을 재사용한다.
- 한 Event의 복구 실패는 업무 오류·입력 오류·시스템 오류로 구분해 `eventId`, 오류 코드와 함께 로그를
  남기고 다음 Event 처리를 계속한다.
- 기본 실행 주기와 유예 시간은 각각 1분이며, 한 번에 최대 100건을 처리한다.

설정은 `cking.snapshot.recovery.interval-ms`, `cking.snapshot.recovery.grace-period`,
`cking.snapshot.recovery.batch-size`로 조정한다. `grace-period`는 1초 이상 1시간 이하여야 한다. Scheduler의
최초 실행도 설정한 주기만큼 지연해 정상 마감 직후의 Snapshot 생성 경로와 불필요하게 경합하지 않는다.
복구 작업은 전용 단일 스레드 Scheduler에서 실행하므로 다른 도메인의 정기 작업을 지연시키지 않는다.

### `SnapshotIntegrityService.verifyForDrawing(Long eventId)`

1. Event의 공식 Snapshot을 조회한다.
2. Candidate를 `memberId ASC`로 조회해 Entity가 아닌 불변 값으로 변환한다.
3. 공식 Snapshot 생성 시 사용한 정규화 규칙으로 Hash를 재계산한다.
4. Candidate 수와 전체 응모권 수도 저장된 집계값과 비교한다.
5. 모두 일치하면 불변 `VerifiedSnapshot`을 반환한다.
6. 불일치하면 `SNAPSHOT_HASH_MISMATCH`로 중단하고 추첨 입력을 반환하지 않는다.

Drawing 모듈은 Snapshot Entity나 Repository를 직접 사용하지 않고 이 서비스만 호출한다. 이 검증은 read-only이며 `verification_status`와 `verified_at` 기록은 추첨 검증 담당 범위에서 처리한다.

## 관리자 조회 API

### `GET /api/admin/events/{eventId}/snapshot`

- 권한: `ADMIN`
- 사용자 식별: 필수 query parameter `userId` (`Long`)
- 처리 순서: Member 존재 여부와 `ADMIN` 권한을 확인한 뒤 Event의 공식 Snapshot을 조회한다.
- 조회는 read-only이며 Snapshot과 Candidate를 변경하지 않는다.
- Candidate는 `userId ASC` 순서로 반환한다.

응답 `data`는 다음 필드를 포함한다.

```json
{
  "snapshotId": 20,
  "eventId": 10,
  "winnerCount": 2,
  "drawMethod": "WEIGHTED",
  "algorithmVersion": "WEIGHTED_V1",
  "prizeAlgorithmVersion": "PRIZE_WEIGHTED_V1",
  "candidateCount": 2,
  "totalTicketCount": 10,
  "snapshotHash": "a3a997ca2bed6ff1ad71484b6d13cc7a07dec9b0260c5bb040c55ddcb87ec281",
  "createdAt": "2026-09-16T00:00:00Z",
  "candidates": [
    { "userId": 1, "ticketCount": 3 },
    { "userId": 2, "ticketCount": 7 }
  ],
  "prizes": [
    { "snapshotPrizeId": 1, "prizeKey": "FIRST", "displayName": "1등 상품", "priority": 1, "weight": 5, "quantity": 1 },
    { "snapshotPrizeId": 2, "prizeKey": "SECOND", "displayName": "2등 상품", "priority": 2, "weight": 95, "quantity": 1 }
  ]
}
```

| 코드 | 조건 |
| --- | --- |
| `VALIDATION_FAILED` | 식별자 누락·타입 불일치·양수 제약 위반 |
| `RESOURCE_NOT_FOUND` | 요청한 Member가 존재하지 않음 |
| `FORBIDDEN` | 요청한 Member가 `ADMIN`이 아님 |
| `SNAPSHOT_NOT_FOUND` | Event의 공식 Snapshot이 존재하지 않음 |

## Snapshot Hash 계약

Hash 알고리즘은 SHA-256이고 결과는 64자리 lowercase hex 문자열이다. 정규화 문자열은 UTF-8로 인코딩하며 줄바꿈은 LF(`\n`)만 사용한다. 마지막 Candidate 행 뒤에도 LF를 포함한다.

숫자는 부호 없는 10진수 문자열로 표현하며 0 채우기를 하지 않는다. Candidate는 입력 순서와 관계없이 `memberId ASC`로 정렬하고 동일 `memberId`를 중복해서 포함하지 않는다.

정규화 형식은 다음과 같다.

```text
CKING_SNAPSHOT_V1
eventId={eventId}
winnerCount={winnerCount}
drawMethod={drawMethod}
algorithmVersion={algorithmVersion}
candidates
{memberId},{ticketCount}
{memberId},{ticketCount}
```

후보가 없으면 `candidates\n`에서 끝난다. `drawMethod=UNIFORM`은 `UNIFORM_V1`,
`drawMethod=WEIGHTED`는 `WEIGHTED_V1`으로 확정한다.

예시는 다음과 같다.

```text
CKING_SNAPSHOT_V1
eventId=10
winnerCount=2
drawMethod=WEIGHTED
algorithmVersion=WEIGHTED_V1
candidates
1,3
2,7
```

위 문자열의 SHA-256은 `a3a997ca2bed6ff1ad71484b6d13cc7a07dec9b0260c5bb040c55ddcb87ec281`이다.

추첨 직전 무결성 검증도 `SnapshotHashGenerator`와 동일한 계약을 사용한다.

## 상품 포함 Snapshot Hash V2

기존 `CKING_SNAPSHOT_V1`은 변경하지 않는다. 상품 설정이 있는 Event는 `CKING_SNAPSHOT_V2`를 사용한다.
상품 문자열은 UTF-8 URL-safe Base64 without padding으로 인코딩하고 상품은
`priority ASC, prizeKey ASC`로 정렬한다.

```text
CKING_SNAPSHOT_V2
eventId={eventId}
winnerCount={winnerCount}
drawMethod={drawMethod}
algorithmVersion={algorithmVersion}
prizeAlgorithmVersion={prizeAlgorithmVersion}
candidates
{memberId},{ticketCount}
prizes
{base64(prizeKey)},{base64(displayName)},{priority},{weight},{quantity}
```

추첨 직전에는 Candidate와 `draw_snapshot_prize`를 함께 읽어 V2 Hash를 재계산한다. 상품 식별자,
표시명, priority, 가중치, 수량, 상품 알고리즘 버전 중 하나라도 바뀌면 추첨을 중단한다.
