# Ticket Stream 처리

EARN과 SPEND(크리에이터), 공용 EARN(COMMON_EARN, 이슈 #244)은 Redis Stream으로 비동기 전달되고, Consumer는 DB 반영이 성공한 뒤에만 XACK한다. DB 반영이 실패하면 ACK하지 않아 메시지는 PEL에 남으며, at-least-once 전달을 전제로 Ledger 서비스가 requestId 기준 중복 반영을 막는다.

```
Lua(원자) ─XADD→ Stream ─Listener→ LedgerService.apply(Tx) ─commit 후→ XACK
                             └ 실패: ACK 안 함 → PEL → 회수 스케줄러(XCLAIM) ─초과→ dead_stream_message
```

## Stream과 Consumer

| Stream | Consumer Group | Consumer | DB 반영 | 특수 처리 |
| --- | --- | --- | --- | --- |
| `stream:ticket-earned` | `cg:ticket-earn` | `earn-<서버 ID>` | EARN Ledger, Mission Completion, DB Balance | 없음 |
| `stream:ticket-deducted` | `cg:ticket-history` | `spend-<서버 ID>-<번호>` (서버당 `cking.stream.spend.consumer-count`개, 기본 4) | EventEntry, SPEND Ledger, DB Balance | `EVENT_ENTRY_CLOSED` barrier는 DB 반영 없이 ACK |
| `stream:common-ticket-earned` | `cg:common-ticket-earn` | `common-earn-<서버 ID>` | 공용 EARN Ledger, Common Mission Completion 또는 1회 지급 원인, 공용 Balance | 없음(크리에이터 축이 없어 barrier 대상 아님) |

공용 EARN 메시지에 `sourceType=ONCE`가 있으면 `CommonEarnEventDispatcher`가 영속 1회 지급 원인과 내용을 대조해 원장·잔액을 반영한다. 기존 출석 메시지는 `sourceType`이 없어 `CommonMissionEarnLedgerService`로 간다. 두 경로 모두 반영 성공 후에만 XACK하며 PEL 회수와 Dead Stream replay에서 같은 분기를 사용한다.

- 적립 두 그룹은 서버당 Listener 하나가, 응모 그룹은 서버당 여러 개(기본 4)가 읽는다. 서버가 여러 대면 같은 그룹을 서버 수 × 서버당 Listener 수만큼 나눠 읽는다. 같은 그룹 안의 메시지는 `request_id` 멱등과 Balance 행 잠금으로 처리 순서가 바뀌어도 같은 결과가 되고, Drain은 그룹 단위 `lastDeliveredId`와 PEL로 판정해 소비자 수와 무관하다. 그래서 여러 서버를 위해 이벤트별 Stream으로 나눌 필요는 없다.
- 응모 그룹을 서버당 여러 개로 읽는 이유(#672): 메시지 한 건 반영(약 10ms)은 대부분 DB 왕복 대기라 Listener 하나로는 서버당 초당 약 100건에서 멈춘다(부하 테스트 1차, 그동안 서버 CPU 74%·RDS CPU 38% 이하). 여러 개가 기다림을 겹친다. 서버 1대만 남아도 마감 몰림(초당 150)을 받도록 4개로 두고, 그 위는 RDS가 한계다. 같은 회원의 응모가 동시에 반영돼도 잔액 행 잠금(`FOR UPDATE`)으로 차례대로 반영된다. 한 번에 읽는 수(`cking.stream.spend.batch-size`, 기본 10)를 제한해 밀린 메시지를 Listener 하나가 모두 가져가지 않게 한다. 10건 처리(약 100ms)는 PEL 회수 기준 60초보다 훨씬 짧다.
- DB 연결: 서버당 Hikari 기본 10개에 응모 Listener 4개와 API 사용분이 든다. 전체 연결은 서버 수 × 10이고 RDS 최대 연결 안에 있어야 한다.
- 그룹 사이의 순서는 서버 수와 관계없이 보장되지 않는다. EARN이 DB에 반영되기 전에 SPEND가 오면 Balance 부족으로 실패해 ACK되지 않고, PEL 재처리에서 EARN 반영 뒤 성공한다(`TicketCrossStreamOrderIntegrationTest`).
- Consumer 이름은 서버마다 다르다(`StreamConsumerNames`). `<서버 ID>`는 `cking.stream.consumer-id`(배포 스크립트가 EC2 인스턴스 ID를 `CKING_CONSUMER_ID`로 넘김)이고, 없으면 호스트 이름이다. 같은 이름을 여러 서버가 쓰면 PEL을 서버별로 구분할 수 없고, 소비자 하나를 지우면 모든 서버의 PEL 기록이 함께 지워진다.
- 서버가 교체되면 옛 이름이 그룹에 남는다. `StreamConsumerCleanupScheduler`가 10분마다 대기 메시지가 0개이고 1시간(`cking.stream.consumer-cleanup.idle`) 이상 읽기 시도가 없는 소비자를 지운다. `XGROUP DELCONSUMER`는 그 소비자의 PEL 기록도 지우므로 확인과 삭제를 Lua 한 번(`stream-consumer-cleanup.lua`)으로 묶는다. 살아 있는 Listener는 메시지가 없어도 2초마다 읽기를 시도하고, idle은 읽기 시도부터 세므로 2초 안팎으로 유지된다(Redis 7.2.16 확인). 재처리 소비자(`*-pel-recovery`)는 서버들이 함께 쓰는 이름이라 정리 대상에서 뺀다.
- XACK만으로는 메시지가 Stream에 남아 응모·적립이 쌓이는 만큼 Redis 메모리가 는다(#673). `StreamTrimScheduler`가 30초(`cking.stream.trim.interval-ms`)마다 세 Stream에서 모든 그룹이 처리를 끝낸 앞쪽 메시지를 지운다(`stream-trim.lua`).
  - 기준: 그룹마다 PEL이 있으면 PEL의 가장 오래된 ID, 없으면 `last-delivered-id`. 가장 뒤처진 그룹의 기준보다 작은 ID만 `XTRIM MINID ~`로 지운다. 그룹이 없으면 지우지 않는다(그룹을 0부터 다시 만들면 처음부터 읽는다).
  - PEL 메시지는 `EventDrainChecker`·`UnappliedBalanceMessageChecker`가 ID로 원본을 다시 읽으므로 남긴다. ACK된 메시지를 다시 읽는 코드는 없다. Dead Stream replay는 DB에 보존한 payload를 쓴다.
  - 기준은 줄어들지 않는다(새 메시지는 더 큰 ID, PEL에서는 빠지기만 함). 여러 서버가 동시에 실행해도 결과가 같다.
  - `last-delivered-id` 앞만 지우므로 그룹의 `lag`는 계속 계산된다(Redis 7.2.16 확인). `XLEN`은 누적 건수가 아니라 남은 건수가 된다.
  - 주기 근거: `~`는 한 번에 최대 10,000건(기본 LIMIT)을 지워 초당 약 333건까지 따라간다. DB 반영 처리량(부하 테스트 1차 초당 100~110)보다 크다.
- 그룹은 기동 시 `XGROUP CREATE ... MKSTREAM`(시작 오프셋 0)으로 만들고, 이미 있으면(`BUSYGROUP`) 무시한다. Listener는 `lastConsumed`(신규 메시지)만 읽는다.
- Listener는 `XREADGROUP BLOCK`(2초)으로 기다리며 읽는다. BLOCK 명령은 공유 연결을 막지 않도록 전용 연결을 쓰므로, Lettuce 연결 풀(`spring.data.redis.lettuce.pool`, #698)에서 빌려 다시 쓴다. 풀이 없으면 읽을 때마다 연결을 새로 연다(측정: 소비자 7개가 쉬는 20초 동안 새 연결 74개 → 풀 적용 후 4개, 1,000건 처리 중 93개 → 0개).
- BLOCK 읽기는 Stream 읽기 전용 연결(`StreamReadConnectionFactory`, #743)로 한다. 공용 연결의 명령 제한 시간(`spring.data.redis.timeout`, 1초)은 BLOCK 대기(2초)보다 짧아 그 연결로 읽으면 메시지가 없을 때 1초에 `QueryTimeoutException`으로 끝난다(로컬 Redis 7.2 확인). 전용 연결은 접속·TLS·인증·풀 설정을 공용 연결에서 가져오고 명령 제한 시간만 `cking.stream.read-command-timeout`(6초, 부하 테스트 1차 BLOCK 읽기 최대 3.94초)으로 둔다. 풀도 같은 설정으로 따로 가진다.
- Listener의 `process()`가 "DB 반영 → XACK"의 유일한 경로이며 회수 스케줄러도 이 메서드를 재사용한다.
- 키·그룹명·재시도 값은 `cking.ticket.*`(EARN·공용 EARN), `cking.entry.*`(SPEND) 프로퍼티로 바꿀 수 있다.

## 불변식: DB Commit 후에만 XACK

`LedgerService.apply()`가 `@Transactional`로 끝나 예외 없이 반환한 뒤에만 XACK한다. 순서를 바꾸면 DB에 기록되지 않은 응모·적립이 영구히 사라질 수 있다. SPEND barrier도 ACK해야 마감 cutoff Drain 판정이 진행된다.

## 멱등 처리 (UNIQUE 위반을 무조건 ACK하지 않는다)

`apply()`는 먼저 `requestId`로 기존 행(`mission_completion`, `event_entry`)을 조회한다.

- 있고 내용(SPEND: eventId·userId·ticketCount, EARN: payload fingerprint)이 같으면 정상 재전달이다. 재반영 없이 반환하므로 ACK된다.
- 있지만 내용이 다르면 `IllegalStateException`을 던진다. `process()`가 catch해 ACK하지 않고 PEL에 남긴다.
- 동시 재전달로 UNIQUE(`uk_entry_request` 등)가 충돌하면 그 자리에서 복구하지 않고 롤백한다. 재전달 때 새 트랜잭션에서 위 조회로 판정한다.
- SPEND는 Event의 `creatorId`와 Stream의 `creatorId`가 다르면 신규·재전달 구분 없이 예외로 거부한다.
- SPEND는 반영한 메시지 ID를 `event_entry.source_stream_id`에 저장한다. 같은 ID가 다시 오면 PEL 재전달이다. 다른 ID로 오면 응모 Lua가 guard 만료 뒤의 재시도를 다시 차감한 것이므로(#673) `SpendRedeductionRefunder`가 Redis 잔액·실시간 집계를 되돌리고 같은 Lua(`entry-spend-refund.lua`)에서 XACK한다. 되돌리기와 ACK를 나누면 ACK 실패 후 재전달 때 두 번 되돌린다. Lua는 뒤 명령이 실패해도 앞의 쓰기를 되돌리지 않으므로 검사를 먼저 끝내고 쓰며, XACK는 맨 마지막에 한다. 메시지가 PEL에 없으면(PEL 회수로 두 소비자가 같은 메시지를 처리한 경우) 되돌리지 않는다. 잔액 값이 정수가 아니면 아무것도 쓰지 않고 오류로 PEL에 남긴다. 실시간 집계는 표시용이라 상태가 맞지 않으면 줄이지 않고 `entry-total`을 지워 DB 집계로 넘긴다. 수동 보정 락이 있으면 PEL에 남긴다. 규칙과 한계는 [Lua API](../event/lua-api.md)의 guard 절에 있다.
- SPEND 메시지의 `couponType`(`CREATOR`|`COMMON`)이 어느 잔액·Ledger에 반영할지 정한다(이슈 #243) — `COMMON`이면 `common_ticket_ledger`/`user_common_ticket_balance`(#219/#224)에, 그 외는 기존 크리에이터 전용 Balance·Ledger에 반영한다. `couponType` 필드가 없는 배포 전 메시지·Dead Stream 원본은 `CREATOR`로 해석한다. `creatorId` 필드는 couponType과 무관하게 항상 Event에서 resolve된 값이 실린다 - 위 교차검증용이지, 어느 잔액을 차감했는지의 근거가 아니다.
- Balance는 `SELECT ... FOR UPDATE`(`findByMemberIdAndCreatorIdForUpdate`)로 잠근 뒤 갱신한다.

## PEL 회수와 재기동 후 이어받기

EARN·SPEND·공용 EARN 스케줄러는 기본 30초마다 idle 60초 이상인 Pending을 최대 100건 조회해 `XCLAIM`으로 전용 consumer(`earn-pel-recovery`, `spend-pel-recovery`, `common-earn-pel-recovery`)에게 가져와 `process()`로 재처리한다.

- 회수 조건은 consumer가 아니라 idle 시간이다. 서버가 죽어 PEL에 남은 메시지는 재기동 후 첫 스케줄에서 회수된다.
- Listener는 신규 메시지만 읽으므로 과거 PEL은 이 스케줄러만 처리한다.
- 재시도 횟수는 별도 카운터 없이 Redis `totalDeliveryCount`(XPENDING)를 쓴다. XCLAIM마다 증가하고 기본 최대값은 5다(`earn-pel-max-retry`, `spend-pel-max-retry`).

## Dead Stream과 replay

`totalDeliveryCount`가 최대값을 초과하면 `dead_stream_message`에 저장하고 PEL에서 XACK한다.

- 보존 항목: source Stream ID, 원본 payload(JSON), requestId, memberId, eventId(SPEND만), 실패 이유, `retry_count`, `UNRESOLVED`.
- `UNIQUE(source_stream_id, stream_type)`(A안)이므로 같은 원본은 한 행만 두고, 다시 들어오면 `retry_count`·실패 이유·`last_failed_at`만 갱신한다.
- `stream_type`은 `EARN`/`SPEND`/`COMMON_EARN` 세 값이다(이슈 #244). 공용 EARN은 `eventId`가 없다(미션 기반이라 이벤트와 무관).

운영자는 원인 확인 뒤 [관리자 API](api.md)로 목록을 조회하고 replay한다(내부적으로 `DeadStreamReplayService.replay(deadStreamMessageId, resolvedBy)`를 호출한다). 보존 payload를 같은 EARN·SPEND·공용 EARN Ledger 서비스에 재적용하고 성공한 경우에만 `RESOLVED`로 표시한다. Ledger가 requestId 멱등이라 중복 replay도 이중 반영하지 않는다.

> ⚠️ **replay 순서: EARN(공용 포함)을 SPEND보다 먼저 한다.** 같은 사용자의 EARN이 Dead
> Stream에 UNRESOLVED로 남아 있으면 DB Balance 행이 아직 없거나 부족한 상태다. 그 사용자의
> SPEND를 먼저 replay하면 잔액 차감 대상이 없어 같은 이유로 다시 실패한다(PR #250 리뷰,
> 문구). 크리에이터 EARN/SPEND·공용 EARN/SPEND 모두 같은 제약이다.
>
> **COMMON_EARN이 오래 UNRESOLVED로 남으면 그 사용자가 관련된 이벤트의 CLOSING이 안
> 끝날 수 있다.** Redis 공용 잔액은 이미 올라간 상태라 사용자는 정상적으로 SPEND에 성공하지만,
> DB 반영이 막혀 있던 COMMON_EARN 때문에 그 SPEND의 DB Balance 차감도 실패해 SPEND까지
> Dead Stream으로 이관된다. 그 SPEND가 속한 이벤트는 아래 Drain 조건 3번에 걸려 CLOSED로
> 전이하지 못한다 — COMMON_EARN을 먼저 해소해야 막힌 이벤트가 풀린다.
>
> 장시간 DB 장애로 `min-idle × max-retry`(기본 설정 기준 약 5~7.5분)를 넘긴 메시지는
> 이관 이후 자동으로 재시도되지 않는다. 장애 복구 후에는 `UNRESOLVED` 목록을 확인하고
> 위 순서로 수동 replay해야 한다.

## 마감 Drain과의 관계

`EventDrainChecker.isDrained(eventId, cutoffStreamId)`는 다음이 모두 참일 때만 true다.

1. `cg:ticket-history`의 `lastDeliveredId`가 cutoff 이상이다.
2. cutoff 이하 PEL에 해당 Event의 SPEND 메시지가 없다. 공용 Stream이므로 payload의 eventId까지 확인한다.
3. cutoff 이하 `source_stream_id`를 가진 해당 Event의 `UNRESOLVED` SPEND Dead Stream이 없다.

Dead Stream으로 옮겨 ACK된 메시지는 PEL에서 사라지므로 3번을 따로 본다. 하나라도 거짓이면 Event는 CLOSING에 머문다. Dead Stream 판정은 SPEND만 대상이다. 마감 절차 전체는 [closing.md](../event/closing.md)를 본다.
