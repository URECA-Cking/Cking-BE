# 응모 Lua 원자 처리 (entry-spend.lua)

응모(Entry) 요청의 Gate 확인·멱등성 확인·잔액 확인·차감·Stream 발행을 하나의 Redis Lua 스크립트에서 원자적으로 처리한다. Redis Lua는 다른 클라이언트 명령과의 interleaving에 원자적이므로(단일 스레드 실행), 이 검증·차감 사이에 다른 요청이 끼어들 수 없다(FR-P2-030).

스크립트: `src/main/resources/scripts/entry-spend.lua`
Java 연동: `kr.co.cking.event.application` (`EntrySpendService`/`EntrySpendServiceImpl`, `EntryLuaConfig`, `EntryRedisKeys`)

## Redis 키

| 키 | 타입 | 용도 |
| --- | --- | --- |
| `event:status:{eventId}` | STRING | `OPEN`이면 응모 허용, 그 외 값이면 차단. `EventGateLoader`가 `open()` 커밋 직후(및 스케줄러 틱마다 키가 없을 때) `OPEN`으로 적재하고, `EventCutoffBarrier`가 마감 시작 시 `CLOSED`로 갱신 |
| `event:endat:{eventId}` | STRING | 마감 시각(epoch millis). `open()` 시점에 `EventGateLoader`가 `status`보다 먼저 적재, 불변. 키가 없을 때만 쓰고, `event:cutoff`가 있으면(마감 barrier 실행 후) 적재를 건너뛰어 stale한 DB 조회값으로 `CLOSED` Gate를 다시 열지 않음(`event-gate-load.lua`). 진행 중 OPEN 이벤트의 유실 키는 `EventLifecycleScheduler`가 DB 기준으로 복원 |
| `event:cutoff:{eventId}` | STRING | 시스템2가 확정한 마감 barrier Stream ID. 재시도 시 같은 값을 재사용 |
| `ticket:balance:{creatorId}:{userId}` | STRING(integer) | 크리에이터 전용 응모권 잔액. `couponType=COMMON`이면 대신 `ticket:balance:common:{userId}`를 쓴다(이슈 #243) |
| `idem:{requestId}` | STRING(JSON) | `{fingerprint, result}`. TTL 1시간(FR-P2-033) |
| `entry:spend-guard:{requestId}` | STRING(JSON) | `{fingerprint}`. idem 저장 실패에 대비한 2차 멱등성 백스톱(issue #106, ticket-earn.lua의 mission:earn-guard와 동일 원칙). DECRBY 이전에 한 번만 기록되고 다시 갱신되지 않는다. TTL은 이벤트 종료 시각까지 |
| `ticket:maint:{creatorId}:{userId}` | STRING | `TicketCompensationService.resyncRedisToDb()`가 해당 조합을 보정하는 동안 존재. Lua는 `EXISTS`만 확인하고 값·TTL은 보정 서비스 쪽 책임이다(issue #172). `couponType=COMMON`이면 `ticket:maint:common:{userId}`를 대신 확인한다 — 공용 보정 기능 자체는 아직 없어 항상 미존재(EXISTS=false) |
| `event:entry-total:{eventId}` | STRING(integer) | 실시간 응모 현황(FR-P2-045~050) 누적 사용 응모권 수. `event-gate-load.lua`가 Gate 최초 적재 시 DB 집계로 초기화하고, 이 스크립트가 신규 SUCCESS 경로에서만 증가시킨다 |
| `event:entrants:{eventId}` | HASH(userId → 사용 응모권 수) | 실시간 응모 현황 참여자별 집계. `HLEN`이 참여자 수다. 초기화·증가 시점은 위와 같다 |

Gate 복원은 10초 틱(`cking.event.lifecycle-interval-ms`)마다 OPEN 이벤트 전체를 조회한다(#149). 10초는 `fixedDelay`(이전 실행 종료 후 대기)라서 Redis가 정상일 때 Gate 유실 복원을 재시도하는 기본 간격이다. 실제 `GATE_NOT_LOADED`(503) 지속 시간은 틱 실행 시간과 Redis 장애 기간만큼 10초를 초과할 수 있다.
- 전체 조회는 OPEN 이벤트 100개 이하를 전제한다. 100개를 넘거나 틱 실행 시간이 주기의 절반(5초)을 넘으면 Slice 페이징을 도입한다.
- `GATE_NOT_LOADED` 발생 시 응모 경로에서 즉시 적재·재시도하지 않는다. 백그라운드 복원만 쓴다(Redis 장애 시 DB 부하 집중 방지).

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
→ 수동 보정 락 확인 (ticket:maint:{creatorId}:{userId}, issue #172)
→ Gate 확인 (event:status)
→ 시각 확인 (event:endat, Redis 서버 시각 기준)
→ Balance 확인
→ guard 선점 (한 번만, 이후 다시 갱신 안 함)
→ 차감(DECRBY) + Stream 발행(XADD) + 실시간 응모 현황 집계 증가 + 멱등 결과 저장
```

**멱등성 확인이 Gate/시각 확인보다 먼저 실행된다.** idem 키가 남아 있는 기존 성공 요청은 응답 타임아웃 등으로 재시도되더라도, 이벤트 마감 뒤 Gate/시각 상태와 무관하게 `DUPLICATE_REPLAY`를 반환한다. idem 저장 실패 시에는 Guard가 이벤트 진행 중 재차감을 막고, 이벤트 종료 뒤에는 기존 Gate/시각 검증을 따른다. idem·Guard가 모두 없는 신규 요청만 Gate → 시각 → Balance를 검증한다.

> 이 순서는 원래 `취합v1.5.4 §5.3`에 명시된 순서(Gate → 시각 → 멱등성)에서 변경된 것이다. [issue #29](https://github.com/URECA-Cking/Cking-BE/issues/29)에서 "재시도가 마감 경계와 겹치면 이미 성공한 요청이 실패로 오인된다"는 문제가 발견됐고, [issue #36](https://github.com/URECA-Cking/Cking-BE/issues/36)에서 지금 순서로 변경하기로 결정했다. 외부 스펙 문서(`취합v1.5.4`)는 아직 이 변경을 반영하지 않은 상태이니, 문서와 충돌하면 이 파일과 실제 코드를 따른다.

## 수동 보정 락 (issue #172)

`TicketCompensationService.resyncRedisToDb()`는 DB 잔액을 Redis에 덮어써 재동기화한다. 이 보정과 SPEND가 같은 `(creatorId, userId)`를 동시에 건드리면, 보정 직후 Stream Consumer가 밀려 있던 SPEND를 DB에 반영하거나 조회~덮어쓰기 사이 새 SPEND가 끼어들어 Redis·DB가 다시 어긋날 수 있다.

idem·guard 재현 분기(이미 끝난 요청의 replay)를 통과한 **신규 차감 요청만** `ticket:maint:{creatorId}:{userId}` 존재 여부를 확인한다. 락이 있으면 `{ 'BALANCE_MAINTENANCE' }`를 반환한다(HTTP 503) - SPEND/EARN이 동일한 코드·HTTP 상태를 쓰기로 합의했다. 락이 풀린 뒤 같은 요청으로 재시도하면 정상 처리된다.

락을 실제로 SET/DEL하고 보정 전 미반영 Stream·PEL·Dead Stream 메시지를 확인하는 쪽은 `TicketCompensationService`/`TicketMaintenanceLock`이다(issue #174). `TicketRedisKeys.maintenance(creatorId, userId)`가 Lua와 보정 서비스가 공유하는 키 빌더다.

## DECRBY·XADD 실패 시 보상

Redis Lua는 명령 하나가 에러를 던져도 그 전에 실행된 쓰기를 자동으로 되돌리지 않는다.

- `DECRBY` 자체가 실패할 수 있다 — Balance 값이 정수가 아니게 손상되고 그 값이 요청 수량보다 큰 경우(예: `"10.5"`, ticketCount 2), `tonumber("10.5")`는 Lua에서 유효한 숫자(10.5)로 파싱되어 `INSUFFICIENT_BALANCE` 검증(`10.5 < 2`)은 통과하지만, Redis의 `DECRBY`는 정수 문자열만 허용하므로 여기서 실제로 실패한다. `DECRBY`는 `redis.pcall`로 감싸 실패 시 guard 예약(`entry:spend-guard`)을 해제한 뒤 에러를 반환한다 — 실제 차감이 전혀 없었으므로 잔액 보상은 필요 없다.
- `DECRBY` 이후 `XADD`가 실패하면(예: 스트림 키 타입 충돌) 잔액만 깎이고 멱등 결과는 저장되지 않아, 이후에도 DB UNIQUE 안전망이 적용되지 않는 채로 잔액이 샐 수 있다. `XADD`도 `redis.pcall`로 감싸서 실패를 감지하면 `INCRBY`로 잔액을 보상하고 guard 예약도 해제한다.

두 경우 모두 `redis.error_reply`로 에러를 반환하며, 호출측 Java에서 `SYSTEM_ERROR`로 매핑한다.

## 실시간 응모 현황 집계 (FR-P2-045~050)

`event:entry-total:{eventId}`·`event:entrants:{eventId}`는 `GET /api/events/{eventId}/entry-status`(참여자 수·누적 사용 응모권 수 조회, 표시 전용 - 응모 승인·추첨 근거 아님)가 읽는 집계 키다. "키 없음 = 0 금지" 원칙을 그대로 적용해, 집계 키가 없으면 0으로 증가시키지 않고 조회 쪽이 DB `event_entry` 집계로 대체한다(`EntryStatusQueryService`).

- **초기화**: `event-gate-load.lua`가 Gate가 처음 열릴 때(`event:status` 키가 아직 없을 때)만 DB 집계값으로 두 키를 채운다. Gate가 이미 있으면(정상 운영 중 대부분의 호출) 집계에 손대지 않는다 - `EventGateLoader`가 Redis `EXISTS` 사전 확인으로 매 틱 DB를 조회하지 않게 하고, Lua가 KEYS 존재 여부를 다시 확인하므로 그 사이 경합에도 이중 초기화는 없다. 이때 `event:entry-total`·`event:entrants`는 **서로 독립적으로 존재 여부를 확인**하고 각자 없을 때만 채운다 - `event:status`만 부분적으로 유실되고(Redis eviction 등) 집계 키는 살아 있는 경우, DB 스냅샷으로 덮어쓰면 그사이 Redis에는 반영됐지만 Consumer가 아직 DB에 옮기지 못한 증가분이 사라지기 때문이다.
- **증가**: `entry-spend.lua`의 신규 SUCCESS 경로(XADD 성공 직후, idem 저장 전)에서만 `INCRBY`/`HINCRBY`로 증가한다. `DUPLICATE_REPLAY`(idem·guard 경로)와 모든 실패 코드는 반영하지 않으므로 재전송에도 이중 집계가 없다. 집계 실패가 이미 확정된 응모 결과를 바꾸면 안 되므로 `pcall`로 격리한다.
- **만료**: `event-close-barrier.lua`가 새 cutoff를 확정할 때만 두 키에 24시간 만료(`PEXPIRE`)를 건다. 마감 이후 신규 증가가 없으므로 CLOSED 이후 조회는 DB로 넘어가면 충분하고, 키가 영구히 남지 않는다. 기존 cutoff를 그대로 반환하는 재시도 경로에는 적용하지 않는다(이미 걸려 있음).
- **조회 출처**: Event 상태가 `OPEN`/`CLOSING`이고 집계 키가 있으면 Redis(`realtime=true`), 그 외는 DB `event_entry` 집계(`realtime=false`, CLOSED 이후는 Drain이 끝난 확정값).

## idem 저장 실패 시 guard 백스톱 (issue #106)

`SET idemKey`는 `DECRBY`/`XADD`가 모두 끝난 뒤 스크립트의 가장 마지막에 실행된다. 이 마지막 쓰기 하나만 실패해도 잔액은 이미 깎이고 Stream도 이미 발행된 뒤라, 결과를 기록할 방법이 없으면 재시도가 새 요청으로 처리되어 잔액이 다시 깎일 수 있다.

`ticket-earn.lua`(EARN, PR #63)가 이미 겪고 해결한 문제와 동일한 형태다. EARN은 Balance 증가 이전에 guard 키를 `SET NX`로 딱 한 번만 선점하고 그 뒤로는 다시 갱신하지 않으며, guard 히트 시 결과 payload 재구성 없이 `{'ALREADY_PROCESSED'}`만 반환한다 — idem 저장은 `pcall`로 감싸 실패해도 무시한다("idem은 정합성 백스톱이 아니라 성능 최적화용 캐시"). SPEND도 동일하게 적용한다:

- `DECRBY` 이전에 `entry:spend-guard:{requestId}`에 `{fingerprint}`만 담아 딱 한 번 기록하고, 이후 다시 갱신하지 않는다.
- 멱등성 확인 단계에서 `idem` 키가 없으면(신규 요청 또는 idem 저장 실패로 인한 재시도) 이어서 `guard` 키를 확인한다.
  - guard의 `fingerprint`가 다르면 → `IDEMPOTENCY_CONFLICT`
  - guard의 `fingerprint`가 같으면 → `{'DUPLICATE_REPLAY'}` (streamId/balance 없이 code만)

guard 히트 시 결과 payload를 재구성하지 않아도 되는 이유는 SPEND 성공 응답 스키마가 `code`·`requestId`·`eventId`·`accepted` 4개뿐이고 `streamId`/`balance`는 애초에 반환하지 않기 때문이다(취합/API명세 확정 사항, `EntrySpendResult.streamId()`/`.balance()`를 읽는 내부 소비자도 현재 없음). 이 덕분에 "guard 예약 SET과 idem SET이 모두 실패해 결과를 재현할 수 없는" 잔여 상태 자체가 이 순서에서는 발생하지 않는다 — guard는 한 번 쓰이면 그 값이 영원히 고정이라, 있으면 무조건 "이미 접수됨"이고 없으면 "신규 요청"인 두 상태만 존재한다.

guard의 만료 시각은 idemTtl이 아니라 `event:endat`(이벤트 종료 시각)에 `PEXPIREAT`로 맞춘다. 이벤트가 idemTtl(1시간)보다 오래 열려 있어도 guard가 만료되지 않아 재차감을 이벤트 진행 기간 내내 막는다 — 이벤트 종료 이후에는 Gate/시각 검증이 어차피 모든 신규 요청을 차단하므로 그 이상 유지할 필요는 없다. (EARN의 guard가 idem보다 긴 TTL을 쓰는 건 미션 중복 방지라는 별개의 도메인 이유지만, SPEND의 guard는 idem 저장 실패에 대한 순수 기술적 백스톱이라는 별도 근거로 이벤트 종료 시각까지 유지한다.)

## 결과 코드 (FR-P2-036, 기존 10종 + BALANCE_MAINTENANCE)

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
| `BALANCE_MAINTENANCE` | 수동 보정 락(`ticket:maint:{creatorId}:{userId}`)이 걸려 있음(issue #172, HTTP 503, `EntryErrorCode.BALANCE_MAINTENANCE`) |

`fingerprint`는 클라이언트가 보내지 않는다. `EntrySpendServiceImpl`이 `eventId+userId+ticketCount`를 SHA-256으로 해시해서 계산한다(FR-P2-029). `couponType=COMMON`이면 해시 전 payload 끝에 `:COMMON`을 붙인다 — `CREATOR` fingerprint는 배포 전과 동일한 값을 유지해 그 시점 진행 중이던 요청의 재시도가 `IDEMPOTENCY_CONFLICT`로 깨지지 않게 한다(이슈 #243).

## couponType (이슈 #243)

응모 요청의 `couponType`(`CREATOR`|`COMMON`, 생략 시 `CREATOR`)이 어느 잔액·보정 락 키를 검증·차감할지 정한다 — 이벤트가 아니라 요청의 속성이다. `EntrySpendServiceImpl`이 이미 키를 골라서 넘기므로 **Lua 안에는 쿠폰 종류별 분기가 없다**. ARGV로 받은 `couponType`은 XADD Stream 필드에 그대로 실려 Consumer(`SpendCommand`)가 어느 Ledger·잔액에 반영할지 결정한다. 필드가 없는 배포 전 메시지·Dead Stream 원본은 `CREATOR`로 해석한다.

## Redis 타임아웃과 SYSTEM_ERROR

SPEND는 EARN과 달리 `QueryTimeoutException`을 따로 구분하지 않는다. `DataAccessException`을 모두 `SYSTEM_ERROR`(HTTP 500)로 반환한다. 결과 코드 10종에 "처리 여부 불명" 코드가 없고, 새 코드를 만들면 명세(취합 v1.5.4 §5.4) 계약 위반이기 때문이다. (수동 보정 락은 "처리 여부 불명"이 아니라 Lua가 명확히 판단해 반환하는 별개의 상태라 `BALANCE_MAINTENANCE`로 예외적으로 추가했다 - 위 "수동 보정 락" 절 참고.)

타임아웃이면 Lua가 이미 차감·XADD·idem 저장까지 끝냈을 수 있다. 클라이언트는 `SYSTEM_ERROR`를 받으면 **새 `requestId`를 만들지 않고 동일 `requestId`와 동일 payload로 재시도**한다. 재시도는 이중 차감 없이 안전하지만, **최종 결과가 성공이라는 보장은 없다.**

- 이미 처리됐다면 `DUPLICATE_REPLAY`가 반환되고 재차감은 없다. idem이 남아 있으면 기존 성공 결과(streamId·잔액)를 함께 반환하고, guard만 남았다면 결과 코드만 반환한다.
- 처리되지 않았다면 idem·guard가 없으므로 신규 요청과 같게 Gate → 시각 → 잔액을 검증한다. 그 사이 이벤트가 마감됐거나 잔액이 부족하면 `EVENT_CLOSED`·`INSUFFICIENT_BALANCE` 등이 반환되고, Redis 장애가 계속되면 다시 `SYSTEM_ERROR`가 된다.

## 통합 테스트

파일: `src/test/java/kr/co/cking/event/application/service/EntrySpendServiceIntegrationTest.java`

`@SpringBootTest` + `StringRedisTemplate`로 로컬 docker Redis/MySQL(`docker compose up -d`)에 직접 붙어서 검증한다. Testcontainers는 쓰지 않는다. 실행 전 컨테이너가 떠 있어야 한다.

- `@Value("${cking.entry.stream-key:...}")`로 stream 키를 테스트 전용(`stream:ticket-deducted:test`)으로 오버라이드해서, 테스트가 실제 운영 `stream:ticket-deducted`를 절대 건드리지 않는다.
- 매 테스트 전후로 그 테스트가 쓴 키만 `delete`한다(FLUSHALL 사용 안 함).

검증하는 케이스(26개):

- 10종 결과 코드 각각 (Gate 없음/닫힘, 마감, ticketCount 상하한, Balance 없음/부족, 성공, XADD 실패)
- 수동 보정 락이 걸려 있으면 `BALANCE_MAINTENANCE`를 반환하고 잔액·idem·guard를 건드리지 않는지, 이미 완료된 요청의 replay는 락과 무관하게 재현되는지, 락이 풀린 뒤 같은 requestId로 재시도하면 SUCCESS로 처리되는지 (issue #172)
- 성공 시 idem TTL이 1시간(3590~3600초)인지, Stream에 실제로 올바른 필드(eventId/userId/creatorId/requestId/ticketCount)가 들어갔는지
- 이벤트 마감 후 재시도해도 `DUPLICATE_REPLAY`/`IDEMPOTENCY_CONFLICT`가 유지되는지 (issue #36)
- 동시 요청 20개가 같은 requestId로 들어와도 차감·XADD가 정확히 1번만 일어나는지 (FR-P2-030/043, 코드 리뷰로 대체 불가 항목)
- Balance가 정수가 아니면 DECRBY 실패 시 guard가 남지 않고, 보정 후 재시도가 성공하는지 (issue #106)
- XADD 실패 시 guard 예약도 함께 해제되는지 (issue #106)
- idem 키가 유실돼도 guard가 DUPLICATE_REPLAY로 막고(streamId/balance 없이) 잔액을 다시 깎지 않는지 (issue #106)
- idem 없이 guard만 있으면 DUPLICATE_REPLAY를, guard의 fingerprint가 다르면 IDEMPOTENCY_CONFLICT를 반환하는지 (issue #106)
- endAt이 idemTtl보다 먼 이벤트에서도 guard TTL이 idemTtl을 넘겨 endAt까지 유지되는지 (issue #106)
- 실시간 응모 현황 집계 키가 있으면 SUCCESS 시 증가하는지, 없으면 새로 만들지 않는지, DUPLICATE_REPLAY는 다시 증가시키지 않는지 (FR-P2-045~050)

Gate 최초 적재 시 DB 집계로 두 키를 초기화하는지는 `EventGateLoaderAggregateIntegrationTest`, 마감 barrier의 만료 설정은 `EventCutoffBarrierTest`, 조회 시 Redis/DB 선택 분기는 `EntryStatusQueryServiceIntegrationTest`가 각각 검증한다.

## NFR-06 부하 테스트

`EntrySpendLoadTest`(`@Tag("load")`)가 두 시나리오를 나눠 측정한다. 기본 `./gradlew test`에는 포함하지 않고 `./gradlew loadTest`로 따로 돌린다 — 매 커밋마다 돌릴 상관관계 테스트가 아니라 필요할 때 확인하는 측정용이다.

**처리량 시나리오**: 서로 다른 사용자 300명이 각자 자기 Balance에 1장씩 응모한다. 요청끼리 같은 Redis 키·DB 행을 다투지 않으므로 Lua 원자성이 시험받는 구간이 아니라 순수 처리량 측정이다.

**경합 시나리오**: 한 사용자가 보유한 잔액(150)보다 많은 요청(300건)을 동시에 던져 같은 Balance 키를 다투게 한다. 취합v1.5.4 §14 시나리오 5·6(보유량 초과 동시 응모에도 Redis Balance 음수 0건, 요청 수량만큼 정확히 차감)에 해당하며, 여기서 Lua의 검증·차감 원자성이 실제로 시험된다. 잔액만큼만 `SUCCESS`이고 나머지는 전부 `INSUFFICIENT_BALANCE`여야 하며, Redis 잔액은 정확히 `0`에서 멈춰야 한다.

식별자는 하드코딩하지 않고 auto-increment가 배정한 값을 쓴다. 로컬 공유 DB의 카운터가 어디까지 올라가 있든 충돌하지 않고, 정리도 이 테스트가 만든 ID로만 한다.

로컬 실측(2026-09-22):

| 시나리오 | 동시 요청 | 결과 | TPS | p50 | p95 | p99 |
| --- | --- | --- | --- | --- | --- | --- |
| 처리량 | 300 | SUCCESS 300 | 6000 | 26ms | 44ms | 47ms |
| 경합 | 300 | SUCCESS 150 / INSUFFICIENT_BALANCE 150 | 10345 | 14ms | 22ms | 22ms |

참고용 1회 측정치이며 SLA로 확정한 값은 아니다.
