# Ticket Stream 처리

EARN과 SPEND는 Redis Stream으로 비동기 전달되고, Consumer는 DB 반영이 성공한 뒤에만 XACK한다. DB 반영이 실패하면 ACK하지 않아 메시지는 PEL에 남으며, at-least-once 전달을 전제로 Ledger 서비스가 requestId 기준 중복 반영을 막는다.

```
Lua(원자) ─XADD→ Stream ─Listener→ LedgerService.apply(Tx) ─commit 후→ XACK
                             └ 실패: ACK 안 함 → PEL → 회수 스케줄러(XCLAIM) ─초과→ dead_stream_message
```

## Stream과 Consumer

| Stream | Consumer Group | Consumer | DB 반영 | 특수 처리 |
| --- | --- | --- | --- | --- |
| `stream:ticket-earned` | `cg:ticket-earn` | `earn-consumer-1` | EARN Ledger, Mission Completion, DB Balance | 없음 |
| `stream:ticket-deducted` | `cg:ticket-history` | `spend-consumer-1` | EventEntry, SPEND Ledger, DB Balance | `EVENT_ENTRY_CLOSED` barrier는 DB 반영 없이 ACK |

- 두 그룹 모두 concurrency 1로 고정한다(MVP). 병렬 처리가 필요하면 이벤트별 Stream 분리를 먼저 재검토한다.
- 그룹은 기동 시 `XGROUP CREATE ... MKSTREAM`(시작 오프셋 0)으로 만들고, 이미 있으면(`BUSYGROUP`) 무시한다. Listener는 `lastConsumed`(신규 메시지)만 읽는다.
- Listener의 `process()`가 "DB 반영 → XACK"의 유일한 경로이며 회수 스케줄러도 이 메서드를 재사용한다.
- 키·그룹명·재시도 값은 `cking.ticket.*`(EARN), `cking.entry.*`(SPEND) 프로퍼티로 바꿀 수 있다.

## 불변식: DB Commit 후에만 XACK

`LedgerService.apply()`가 `@Transactional`로 끝나 예외 없이 반환한 뒤에만 XACK한다. 순서를 바꾸면 DB에 기록되지 않은 응모·적립이 영구히 사라질 수 있다. SPEND barrier도 ACK해야 마감 cutoff Drain 판정이 진행된다.

## 멱등 처리 (UNIQUE 위반을 무조건 ACK하지 않는다)

`apply()`는 먼저 `requestId`로 기존 행(`mission_completion`, `event_entry`)을 조회한다.

- 있고 내용(SPEND: eventId·userId·ticketCount, EARN: payload fingerprint)이 같으면 정상 재전달이다. 재반영 없이 반환하므로 ACK된다.
- 있지만 내용이 다르면 `IllegalStateException`을 던진다. `process()`가 catch해 ACK하지 않고 PEL에 남긴다.
- 동시 재전달로 UNIQUE(`uk_entry_request` 등)가 충돌하면 그 자리에서 복구하지 않고 롤백한다. 재전달 때 새 트랜잭션에서 위 조회로 판정한다.
- SPEND는 Event의 `creatorId`와 Stream의 `creatorId`가 다르면 신규·재전달 구분 없이 예외로 거부한다.
- Balance는 `SELECT ... FOR UPDATE`(`findByMemberIdAndCreatorIdForUpdate`)로 잠근 뒤 갱신한다.

## PEL 회수와 재기동 후 이어받기

EARN·SPEND 스케줄러는 기본 30초마다 idle 60초 이상인 Pending을 최대 100건 조회해 `XCLAIM`으로 전용 consumer(`earn-pel-recovery`, `spend-pel-recovery`)에게 가져와 `process()`로 재처리한다.

- 회수 조건은 consumer가 아니라 idle 시간이다. 서버가 죽어 PEL에 남은 메시지는 재기동 후 첫 스케줄에서 회수된다.
- Listener는 신규 메시지만 읽으므로 과거 PEL은 이 스케줄러만 처리한다.
- 재시도 횟수는 별도 카운터 없이 Redis `totalDeliveryCount`(XPENDING)를 쓴다. XCLAIM마다 증가하고 기본 최대값은 5다(`earn-pel-max-retry`, `spend-pel-max-retry`).

## Dead Stream과 replay

`totalDeliveryCount`가 최대값을 초과하면 `dead_stream_message`에 저장하고 PEL에서 XACK한다.

- 보존 항목: source Stream ID, 원본 payload(JSON), requestId, memberId, eventId(SPEND만), 실패 이유, `retry_count`, `UNRESOLVED`.
- `UNIQUE(source_stream_id, stream_type)`(A안)이므로 같은 원본은 한 행만 두고, 다시 들어오면 `retry_count`·실패 이유·`last_failed_at`만 갱신한다.

운영자는 원인 확인 뒤 `DeadStreamReplayService.replay(deadStreamMessageId, resolvedBy)`를 호출한다. 보존 payload를 같은 EARN·SPEND Ledger 서비스에 재적용하고 성공한 경우에만 `RESOLVED`로 표시한다. Ledger가 requestId 멱등이라 중복 replay도 이중 반영하지 않는다. `replay()`를 호출하는 코드(API·스케줄러)는 현재 없어 서비스 메서드로만 존재한다.

## 마감 Drain과의 관계

`EventDrainChecker.isDrained(eventId, cutoffStreamId)`는 다음이 모두 참일 때만 true다.

1. `cg:ticket-history`의 `lastDeliveredId`가 cutoff 이상이다.
2. cutoff 이하 PEL에 해당 Event의 SPEND 메시지가 없다. 공용 Stream이므로 payload의 eventId까지 확인한다.
3. cutoff 이하 `source_stream_id`를 가진 해당 Event의 `UNRESOLVED` SPEND Dead Stream이 없다.

Dead Stream으로 옮겨 ACK된 메시지는 PEL에서 사라지므로 3번을 따로 본다. 하나라도 거짓이면 Event는 CLOSING에 머문다. Dead Stream 판정은 SPEND만 대상이다. 마감 절차 전체는 [closing.md](../event/closing.md)를 본다.
