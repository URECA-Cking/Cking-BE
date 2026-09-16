# 응모 Lua 원자 처리 (entry-spend.lua)

응모(Entry) 요청의 Gate 확인·멱등성 확인·잔액 확인·차감·Stream 발행을 하나의 Redis Lua 스크립트에서 원자적으로 처리한다. Redis Lua는 다른 클라이언트 명령과의 interleaving에 원자적이므로(단일 스레드 실행), 이 검증·차감 사이에 다른 요청이 끼어들 수 없다(FR-P2-030).

스크립트: `src/main/resources/scripts/entry-spend.lua`
Java 연동: `kr.co.cking.event.application` (`EntrySpendService`/`EntrySpendServiceImpl`, `EntryLuaConfig`, `EntryRedisKeys`)

## Redis 키

| 키 | 타입 | 용도 |
| --- | --- | --- |
| `event:status:{eventId}` | STRING | `OPEN`/`CLOSED`. 자동배치 또는 백오피스 수동마감 API가 갱신 |
| `event:endat:{eventId}` | STRING | 마감 시각(epoch millis). 이벤트 생성 시 1회 세팅, 불변 |
| `ticket:balance:{creatorId}:{userId}` | STRING(integer) | 응모권 잔액 |
| `idem:{requestId}` | STRING(JSON) | `{fingerprint, result}`. TTL 1시간(FR-P2-033) |

Gate 키 구조는 `데이터 구조.md` §2 확정 스키마를 따른다(Hash가 아니라 String 2개로 분리).

## 처리 순서

```
ticketCount 검증
→ 멱등성 확인 (idem:{requestId})
→ Gate 확인 (event:status)
→ 시각 확인 (event:endat, Redis 서버 시각 기준)
→ Balance 확인
→ 차감(DECRBY) + Stream 발행(XADD) + 멱등 결과 저장
```

**멱등성 확인이 Gate/시각 확인보다 먼저 실행된다.** 이미 성공 처리된 요청이 응답 타임아웃 등으로 재시도됐을 때, 그 사이 이벤트가 마감됐더라도 Gate/시각 상태와 무관하게 기존 성공 결과(`DUPLICATE_REPLAY`)를 그대로 재현해야 하기 때문이다. 멱등키가 없는 신규 요청만 기존과 동일하게 Gate → 시각 → Balance를 검증한다.

> 이 순서는 원래 `취합v1.5.4 §5.3`에 명시된 순서(Gate → 시각 → 멱등성)에서 변경된 것이다. [issue #29](https://github.com/URECA-Cking/Cking-BE/issues/29)에서 "재시도가 마감 경계와 겹치면 이미 성공한 요청이 실패로 오인된다"는 문제가 발견됐고, [issue #36](https://github.com/URECA-Cking/Cking-BE/issues/36)에서 지금 순서로 변경하기로 결정했다. 외부 스펙 문서(`취합v1.5.4`)는 아직 이 변경을 반영하지 않은 상태이니, 문서와 충돌하면 이 파일과 실제 코드를 따른다.

## XADD 실패 시 보상

Redis Lua는 명령 하나가 에러를 던져도 그 전에 실행된 쓰기를 자동으로 되돌리지 않는다. `DECRBY` 이후 `XADD`가 실패하면(예: 스트림 키 타입 충돌) 잔액만 깎이고 멱등 결과는 저장되지 않아, 이후에도 DB UNIQUE 안전망이 적용되지 않는 채로 잔액이 샐 수 있다. 그래서 `XADD`는 `redis.pcall`로 감싸서 실패를 감지하면 `INCRBY`로 잔액을 보상한 뒤 `redis.error_reply`로 에러를 반환한다(호출측 Java에서 `SYSTEM_ERROR`로 매핑).

## 결과 코드 (FR-P2-036, 10종)

| 코드 | 의미 |
| --- | --- |
| `SUCCESS` | 차감 성공. `{streamId, 차감후잔액}` 포함 |
| `DUPLICATE_REPLAY` | 동일 requestId·동일 payload 재시도. 기존 성공 결과 재반환 |
| `IDEMPOTENCY_CONFLICT` | 동일 requestId·다른 payload |
| `GATE_NOT_LOADED` | Gate 키 자체가 없음 (없음 = OPEN으로 간주 금지) |
| `EVENT_NOT_OPEN` | `event:status` != `OPEN` |
| `EVENT_CLOSED` | `now >= endAt` |
| `BALANCE_NOT_LOADED` | Balance 키 자체가 없음 (없음 = 0 취급 금지) |
| `INSUFFICIENT_BALANCE` | 보유 응모권 < 요청 수량 |
| `INVALID_TICKET_COUNT` | ticketCount가 1 미만이거나 100 초과 |
| `SYSTEM_ERROR` | 스크립트 실행 자체가 예외를 던졌을 때 Java가 매핑(스크립트가 직접 반환하는 코드 아님) |

`fingerprint`는 클라이언트가 보내지 않는다. `EntrySpendServiceImpl`이 `eventId+userId+ticketCount`를 SHA-256으로 해시해서 계산한다(FR-P2-029).

## 통합 테스트

파일: `src/test/java/kr/co/cking/event/application/service/EntrySpendServiceIntegrationTest.java`

`@SpringBootTest` + `StringRedisTemplate`로 로컬 docker Redis/MySQL(`docker compose up -d`)에 직접 붙어서 검증한다. Testcontainers는 쓰지 않는다. 실행 전 컨테이너가 떠 있어야 한다.

- `@Value("${cking.entry.stream-key:...}")`로 stream 키를 테스트 전용(`stream:ticket-deducted:test`)으로 오버라이드해서, 테스트가 실제 운영 `stream:ticket-deducted`를 절대 건드리지 않는다.
- 매 테스트 전후로 그 테스트가 쓴 키만 `delete`한다(FLUSHALL 사용 안 함).

검증하는 케이스(14개):

- 10종 결과 코드 각각 (Gate 없음/닫힘, 마감, ticketCount 상하한, Balance 없음/부족, 성공, XADD 실패)
- 성공 시 idem TTL이 1시간(3590~3600초)인지, Stream에 실제로 올바른 필드(eventId/userId/creatorId/requestId/ticketCount)가 들어갔는지
- 이벤트 마감 후 재시도해도 `DUPLICATE_REPLAY`/`IDEMPOTENCY_CONFLICT`가 유지되는지 (issue #36)
- 동시 요청 20개가 같은 requestId로 들어와도 차감·XADD가 정확히 1번만 일어나는지 (FR-P2-030/043, 코드 리뷰로 대체 불가 항목)
