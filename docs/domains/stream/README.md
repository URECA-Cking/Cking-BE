# Ticket Stream 처리

EARN과 SPEND는 Redis Stream으로 비동기 전달되고, Consumer는 DB 반영이 성공한 뒤에만 XACK한다. DB 반영이 실패하면 ACK하지 않아 메시지는 PEL에 남으며, at-least-once 전달을 전제로 Ledger 서비스가 requestId 기준 중복 반영을 막는다.

## Consumer

| Stream | Consumer Group | DB 반영 | 특수 처리 |
| --- | --- | --- | --- |
| `stream:ticket-earned` | `cg:ticket-earn` | EARN Ledger와 DB Balance | 없음 |
| `stream:ticket-deducted` | `cg:ticket-history` | SPEND Ledger와 DB Balance | `EVENT_ENTRY_CLOSED` barrier는 DB 반영 없이 ACK |

SPEND barrier도 ACK해야 Event 마감의 cutoff Drain 판정이 진행될 수 있다. Consumer가 DB 반영을 마친 뒤 XACK하는 순서를 바꾸면 DB에 기록되지 않은 응모가 영구히 사라질 수 있으므로 바꾸지 않는다.

## PEL 회수

EARN·SPEND PEL 회수 스케줄러는 기본 30초마다 idle 60초 이상인 Pending 메시지를 최대 100건씩 찾아 `XCLAIM`으로 전용 recovery consumer에게 가져온다. 회수 메시지는 기존 Listener의 `process()`를 다시 사용한다. 재시도 횟수는 별도 카운터가 아니라 Redis `totalDeliveryCount`를 사용하며 기본 최대값은 5다.

## Dead Stream과 replay

delivery count가 최대값을 초과하면 원본 payload, source Stream ID, requestId, 실패 이유와 재시도 횟수를 `dead_stream_message`에 보존한 뒤 PEL에서 XACK한다. 같은 source Stream ID와 유형은 기존 행의 재시도 정보만 갱신한다.

운영자는 원인 확인 뒤 `DeadStreamReplayService.replay(deadStreamMessageId, resolvedBy)`를 호출한다. 서비스는 보존 payload를 같은 EARN 또는 SPEND Ledger 서비스에 재적용하고 성공한 경우에만 메시지를 RESOLVED로 표시한다. replay는 Ledger의 requestId 멱등성에 의존하므로 중복 클릭도 이중 반영하지 않는다.

미해결 SPEND Dead Stream 메시지가 마감 Event의 cutoff 이하에 있으면 Event는 CLOSED로 전이할 수 없다.
