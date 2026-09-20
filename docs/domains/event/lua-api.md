# 응모 Lua 원자 처리 (entry-spend.lua)

응모(Entry) 요청의 Gate 확인·멱등성 확인·잔액 확인·차감·Stream 발행을 하나의 Redis Lua 스크립트에서 원자적으로 처리한다. Redis Lua는 다른 클라이언트 명령과의 interleaving에 원자적이므로(단일 스레드 실행), 이 검증·차감 사이에 다른 요청이 끼어들 수 없다(FR-P2-030).

스크립트: `src/main/resources/scripts/entry-spend.lua`
Java 연동: `kr.co.cking.event.application` (`EntrySpendService`/`EntrySpendServiceImpl`, `EntryLuaConfig`, `EntryRedisKeys`)

## Redis 키

| 키 | 타입 | 용도 |
| --- | --- | --- |
| `event:status:{eventId}` | STRING | `OPEN`이면 응모 허용, 그 외 값이면 차단. `EventGateLoader`가 `open()` 커밋 직후(및 스케줄러 틱마다 키가 없을 때) `OPEN`으로 적재하고, `EventCutoffBarrier`가 마감 시작 시 `CLOSED`로 갱신 |
| `event:endat:{eventId}` | STRING | 마감 시각(epoch millis). `open()` 시점에 `EventGateLoader`가 `status`보다 먼저 적재, 불변. 키가 없을 때만 쓰므로 `CLOSED` Gate를 다시 열지 않음. 진행 중 OPEN 이벤트의 유실 키는 `EventLifecycleScheduler`가 DB 기준으로 복원 |
| `event:cutoff:{eventId}` | STRING | 시스템2가 확정한 마감 barrier Stream ID. 재시도 시 같은 값을 재사용 |
| `ticket:balance:{creatorId}:{userId}` | STRING(integer) | 응모권 잔액 |
| `idem:{requestId}` | STRING(JSON) | `{fingerprint, result}`. TTL 1시간(FR-P2-033) |
| `entry:spend-guard:{requestId}` | STRING(JSON) | `{fingerprint}`. idem 저장 실패에 대비한 2차 멱등성 백스톱(issue #106, ticket-earn.lua의 mission:earn-guard와 동일 원칙). DECRBY 이전에 한 번만 기록되고 다시 갱신되지 않는다. TTL은 이벤트 종료 시각까지 |

Gate 키 구조는 `데이터 구조.md` §2 확정 스키마를 따른다(Hash가 아니라 String 2개로 분리).

`event:status`는 Redis Gate 값이고 DB Event 상태가 아니다. DB는 시스템2가
`OPEN → CLOSING → CLOSED`로 전이하며, 외부 수동 마감 API는 시스템4의 권한·요청 상태 검증 뒤
`EventClosingService.startClosing(eventId)`만 호출한다. cutoff와 Stream·PEL·Drain 정보는 외부 응답에
포함하지 않는다.

## 처리 순서

```
ticketCount 검증
→ 멱등성 확인 (idem:{requestId})
→ guard 확인 (entry:spend-guard:{requestId})
→ Gate 확인 (event:status)
→ 시각 확인 (event:endat, Redis 서버 시각 기준)
→ Balance 확인
→ guard 선점 (한 번만, 이후 다시 갱신 안 함)
→ 차감(DECRBY) + Stream 발행(XADD) + 멱등 결과 저장
```

**멱등성 확인이 Gate/시각 확인보다 먼저 실행된다.** idem 키가 남아 있는 기존 성공 요청은 응답 타임아웃 등으로 재시도되더라도, 이벤트 마감 뒤 Gate/시각 상태와 무관하게 `DUPLICATE_REPLAY`를 반환한다. idem 저장 실패 시에는 Guard가 이벤트 진행 중 재차감을 막고, 이벤트 종료 뒤에는 기존 Gate/시각 검증을 따른다. idem·Guard가 모두 없는 신규 요청만 Gate → 시각 → Balance를 검증한다.

> 이 순서는 원래 `취합v1.5.4 §5.3`에 명시된 순서(Gate → 시각 → 멱등성)에서 변경된 것이다. [issue #29](https://github.com/URECA-Cking/Cking-BE/issues/29)에서 "재시도가 마감 경계와 겹치면 이미 성공한 요청이 실패로 오인된다"는 문제가 발견됐고, [issue #36](https://github.com/URECA-Cking/Cking-BE/issues/36)에서 지금 순서로 변경하기로 결정했다. 외부 스펙 문서(`취합v1.5.4`)는 아직 이 변경을 반영하지 않은 상태이니, 문서와 충돌하면 이 파일과 실제 코드를 따른다.

## DECRBY·XADD 실패 시 보상

Redis Lua는 명령 하나가 에러를 던져도 그 전에 실행된 쓰기를 자동으로 되돌리지 않는다.

- `DECRBY` 자체가 실패할 수 있다 — Balance 값이 정수가 아니게 손상되고 그 값이 요청 수량보다 큰 경우(예: `"10.5"`, ticketCount 2), `tonumber("10.5")`는 Lua에서 유효한 숫자(10.5)로 파싱되어 `INSUFFICIENT_BALANCE` 검증(`10.5 < 2`)은 통과하지만, Redis의 `DECRBY`는 정수 문자열만 허용하므로 여기서 실제로 실패한다. `DECRBY`는 `redis.pcall`로 감싸 실패 시 guard 예약(`entry:spend-guard`)을 해제한 뒤 에러를 반환한다 — 실제 차감이 전혀 없었으므로 잔액 보상은 필요 없다.
- `DECRBY` 이후 `XADD`가 실패하면(예: 스트림 키 타입 충돌) 잔액만 깎이고 멱등 결과는 저장되지 않아, 이후에도 DB UNIQUE 안전망이 적용되지 않는 채로 잔액이 샐 수 있다. `XADD`도 `redis.pcall`로 감싸서 실패를 감지하면 `INCRBY`로 잔액을 보상하고 guard 예약도 해제한다.

두 경우 모두 `redis.error_reply`로 에러를 반환하며, 호출측 Java에서 `SYSTEM_ERROR`로 매핑한다.

## idem 저장 실패 시 guard 백스톱 (issue #106)

`SET idemKey`는 `DECRBY`/`XADD`가 모두 끝난 뒤 스크립트의 가장 마지막에 실행된다. 이 마지막 쓰기 하나만 실패해도 잔액은 이미 깎이고 Stream도 이미 발행된 뒤라, 결과를 기록할 방법이 없으면 재시도가 새 요청으로 처리되어 잔액이 다시 깎일 수 있다.

`ticket-earn.lua`(EARN, PR #63)가 이미 겪고 해결한 문제와 동일한 형태다. EARN은 Balance 증가 이전에 guard 키를 `SET NX`로 딱 한 번만 선점하고 그 뒤로는 다시 갱신하지 않으며, guard 히트 시 결과 payload 재구성 없이 `{'ALREADY_PROCESSED'}`만 반환한다 — idem 저장은 `pcall`로 감싸 실패해도 무시한다("idem은 정합성 백스톱이 아니라 성능 최적화용 캐시"). SPEND도 동일하게 적용한다:

- `DECRBY` 이전에 `entry:spend-guard:{requestId}`에 `{fingerprint}`만 담아 딱 한 번 기록하고, 이후 다시 갱신하지 않는다.
- 멱등성 확인 단계에서 `idem` 키가 없으면(신규 요청 또는 idem 저장 실패로 인한 재시도) 이어서 `guard` 키를 확인한다.
  - guard의 `fingerprint`가 다르면 → `IDEMPOTENCY_CONFLICT`
  - guard의 `fingerprint`가 같으면 → `{'DUPLICATE_REPLAY'}` (streamId/balance 없이 code만)

guard 히트 시 결과 payload를 재구성하지 않아도 되는 이유는 SPEND 성공 응답 스키마가 `code`·`requestId`·`eventId`·`accepted` 4개뿐이고 `streamId`/`balance`는 애초에 반환하지 않기 때문이다(취합/API명세 확정 사항, `EntrySpendResult.streamId()`/`.balance()`를 읽는 내부 소비자도 현재 없음). 이 덕분에 "guard 예약 SET과 idem SET이 모두 실패해 결과를 재현할 수 없는" 잔여 상태 자체가 이 순서에서는 발생하지 않는다 — guard는 한 번 쓰이면 그 값이 영원히 고정이라, 있으면 무조건 "이미 접수됨"이고 없으면 "신규 요청"인 두 상태만 존재한다.

guard의 만료 시각은 idemTtl이 아니라 `event:endat`(이벤트 종료 시각)에 `PEXPIREAT`로 맞춘다. 이벤트가 idemTtl(1시간)보다 오래 열려 있어도 guard가 만료되지 않아 재차감을 이벤트 진행 기간 내내 막는다 — 이벤트 종료 이후에는 Gate/시각 검증이 어차피 모든 신규 요청을 차단하므로 그 이상 유지할 필요는 없다. (EARN의 guard가 idem보다 긴 TTL을 쓰는 건 미션 중복 방지라는 별개의 도메인 이유지만, SPEND의 guard는 idem 저장 실패에 대한 순수 기술적 백스톱이라는 별도 근거로 이벤트 종료 시각까지 유지한다.)

## 결과 코드 (FR-P2-036, 10종)

| 코드 | 의미 |
| --- | --- |
| `SUCCESS` | 차감 성공. `{streamId, 차감후잔액}` 포함 |
| `DUPLICATE_REPLAY` | 동일 requestId·동일 payload 재시도. idem 히트 시 `{streamId, balance}` 포함 재반환, guard 히트 시(idem 저장 실패 후 재시도) code만 반환 |
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

검증하는 케이스(19개):

- 10종 결과 코드 각각 (Gate 없음/닫힘, 마감, ticketCount 상하한, Balance 없음/부족, 성공, XADD 실패)
- 성공 시 idem TTL이 1시간(3590~3600초)인지, Stream에 실제로 올바른 필드(eventId/userId/creatorId/requestId/ticketCount)가 들어갔는지
- 이벤트 마감 후 재시도해도 `DUPLICATE_REPLAY`/`IDEMPOTENCY_CONFLICT`가 유지되는지 (issue #36)
- 동시 요청 20개가 같은 requestId로 들어와도 차감·XADD가 정확히 1번만 일어나는지 (FR-P2-030/043, 코드 리뷰로 대체 불가 항목)
- Balance가 정수가 아니면 DECRBY 실패 시 guard가 남지 않고, 보정 후 재시도가 성공하는지 (issue #106)
- XADD 실패 시 guard 예약도 함께 해제되는지 (issue #106)
- idem 키가 유실돼도 guard가 DUPLICATE_REPLAY로 막고(streamId/balance 없이) 잔액을 다시 깎지 않는지 (issue #106)
- idem 없이 guard만 있으면 DUPLICATE_REPLAY를, guard의 fingerprint가 다르면 IDEMPOTENCY_CONFLICT를 반환하는지 (issue #106)
- endAt이 idemTtl보다 먼 이벤트에서도 guard TTL이 idemTtl을 넘겨 endAt까지 유지되는지 (issue #106)
