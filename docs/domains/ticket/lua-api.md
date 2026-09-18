# EARN Lua 원자 처리 (ticket-earn.lua)

미션 완료(EARN) 요청의 멱등성 확인·중복 적립 가드·Balance 증가·Stream 발행을 하나의 Redis Lua 스크립트에서 원자적으로 처리한다(FR-P2-006/008).

스크립트: `src/main/resources/scripts/ticket-earn.lua`
Java 연동: `kr.co.cking.ticket.application` (`TicketEarnService`/`TicketEarnServiceImpl`, `TicketRedisKeys`)

## Redis 키

| 키 | 타입 | TTL | 용도 |
| --- | --- | --- | --- |
| `idem:mission:{requestId}` | STRING(JSON) | 25시간 | requestId 기준 결과 재현. `{fingerprint, status, result?}` |
| `mission:earn-guard:{userId}:{missionType}:{creatorId}:{yyyyMMdd}` | STRING | 25시간 | 하루 1회 중복 적립 방지(FR-P2-006). 값은 `requestId:fingerprint` |

두 키 모두 25시간으로 통일했다(issue #125) — 서로 다른 TTL로 두면 한쪽만 만료된 비대칭 상태가 생겨 처리를 복잡하게 만든다.

Guard는 `requestId:fingerprint`만 저장하며, 원본 요청 payload나 `periodKey`를 별도 필드로 보존하지 않는다. idem도 신규 형식에서는 `fingerprint`, `status`, 완료 시 `result`만 저장한다. `periodKey`는 일일 Guard 키와 Stream의 `MissionCompletion` 저장값에만 사용한다.

## TTL 만료 후 재요청 정책

idem과 일일 Guard는 모두 25시간 후 만료된다. 두 기록이 모두 만료된 뒤 동일 `requestId`가 들어오면 Redis 멱등성 replay 대상으로는 조회되지 않으며, 해당 호출은 신규 EARN 요청으로 처리한다. 영구 DB에 requestId별 replay 결과를 보존하지 않는다. 신규 요청의 미션 활성 상태 검증은 호출 계층(System1)이 수행하고, EARN은 일반적인 일일 Guard 조건으로 중복 적립을 판정한다.

## 처리 순서

```
idem:mission:{requestId} 조회
├─ 없음                              → PROCESSING 레코드 SET NX (fingerprint만 저장)
├─ 있고 fingerprint 다름              → REQUEST_ID_CONFLICT
├─ 있고 fingerprint 같음 + COMPLETED → ALREADY_PROCESSED (저장된 result 재현)
├─ 있고 fingerprint 같음 + PROCESSING → EARN_STATUS_UNKNOWN
└─ 있고 status 필드 자체가 없음        → ALREADY_PROCESSED (legacy 호환, 아래 참고)

(신규 요청만) 일일 Guard 검사·선점 → INCRBY → XADD → idem을 COMPLETED로 확정
```

## fingerprint는 periodKey를 제외한다 (issue #125)

`periodKey`는 클라이언트 입력이 아니라 System1이 서버 UTC 기준으로 매 호출마다 새로 계산하는 파생값이다. fingerprint에 포함시키면 같은 `requestId`가 자정을 넘겨 재시도될 때 서버가 다시 계산한 오늘 날짜 때문에 fingerprint가 최초 요청과 달라져 `REQUEST_ID_CONFLICT`로 오판된다. 그래서 fingerprint는 `userId, creatorId, missionType, missionId, missionKey, amount`만으로 계산하고, `periodKey`는 fingerprint가 아니라 일일 Guard 키와 `MissionCompletion` 저장값에만 쓴다.

Guard(2단계)는 여전히 이번 호출의 periodKey로 주소 지정된다. **이게 안전하려면 idem이 실제로 존재해야 한다** — idem이 있으면 동일 requestId 재시도는 항상 1단계에서 끝나 Guard까지 도달하지 않으므로 문제가 없다. 하지만 idem이 애초에 없는 상태(아래 "Guard-only" 참고)에서 자정을 넘겨 재시도가 오면, Java가 그 호출 시점의(오늘) periodKey로 Guard 키를 새로 구성하기 때문에 원래 요청이 쓰던(어제) Guard 키와는 **완전히 다른 키**가 되어 원래 기록을 전혀 보지 못한다. 이 경우는 새 요청처럼 처리되어 이중 지급으로 이어질 수 있다 — 아래 "Legacy idem 호환과 배포 시 유의사항"에서 이 시나리오와 필요한 배포 전제조건을 다룬다.

## PROCESSING → COMPLETED 2단계와 실패 시 정리

idem은 실제 지급 전에 `PROCESSING`으로 먼저 선점되고, `INCRBY`+`XADD` 성공 후 `COMPLETED`로 확정된다. 실패 시점에 따라 처리가 다르다:

- **Guard 선점 실패, `INCRBY` 실패, `XADD` 실패** — 아직 아무것도 반영되지 않았거나(Guard 선점 실패, INCRBY 실패) 보상까지 끝난 경우(XADD 실패 후 Balance 원복)라 idem·Guard를 모두 정리하고 재시도를 처음부터 받는다. Guard 선점은 `redis.pcall`로 감싸 진짜 Redis 오류(OOM 등)에도 idem PROCESSING이 25시간 잠기지 않도록 한다.
- **`COMPLETED` 확정 자체가 실패** — Balance/Stream은 이미 반영된 뒤라 되돌릴 수 없다. idem은 `PROCESSING`인 채로 남고, 같은 requestId 재시도는 `EARN_STATUS_UNKNOWN`으로 막힌다(신규 지급 절대 금지). 그 요청의 실제 성사 여부 복구는 이 스크립트 범위 밖이며, 별도 운영 절차가 필요하다.

`EARN_STATUS_UNKNOWN`은 이 PROCESSING 분기에서 Lua가 **일반 반환값**으로 직접 내보낸다(`redis.error_reply`가 아님). 기존에는 `TicketEarnServiceImpl`이 `QueryTimeoutException`을 잡았을 때만 매핑하던 코드인데, Lua가 정상 실행 중 판단해서 반환하는 경우가 추가된 것뿐이며 `EarnResultCode` enum에는 새 값을 추가하지 않는다.

## Legacy idem 호환과 배포 시 유의사항 (issue #125)

이 2단계 구조 도입 전(`status` 필드 없이 `{fingerprint, result}`만 있던 시절)에 저장된 idem 레코드가 배포 시점에 아직 살아있을 수 있다. 그 legacy `fingerprint`는 옛 공식(periodKey 포함)으로 계산돼 있어 새 공식과 비교하면 항상 불일치한다 — 그래서 **`status` 필드가 아예 없는 레코드는 fingerprint를 비교하지 않고 존재 자체를 완료된 성공으로 신뢰**한다(`ticket-earn.lua`, `TicketEarnServiceImpl.findExisting()` 동일 분기).

- 이 완화의 트레이드오프: 그 legacy idem의 남은 TTL 동안 같은 requestId가 실제로 다른 내용으로 재사용되면 그 다른 내용은 무시되고 예전 결과가 재현된다. 잔액을 건드리지 않는 replay이므로 **이중 지급 위험은 없다** — FR-P1-017이 이 Redis 멱등키를 "성능용 캐시"로, DB `request_id` UNIQUE를 "최종 안전망"으로 명시한 것과 일치하는 선택이다.

### ⚠️ Guard-only 상태(구버전에서 idem 저장이 실패한 경우) — 실제 이중 지급 위험, 배포 전제조건 필수

구버전 스크립트도 idem 저장 실패에 대비해 Guard를 2차 방어선으로 뒀지만(PR #63), 그 결과 **idem은 한 번도 만들어지지 않고 Guard만 존재하는 요청이 이미 운영 데이터에 있을 수 있다.** 이 Guard는 구 25시간 TTL 동안(=idem 만료를 기다릴 필요 없이 처음부터) 유지된다.

이 상태에서 **자정을 넘겨** 같은 requestId로 재시도가 오면:

1. idem이 없으므로 1단계를 통과해 "신규 요청"으로 진행한다.
2. Java가 이번 호출 시점의(오늘) periodKey로 Guard 키(`mission:earn-guard:...:{오늘}`)를 구성한다 — 원래 성공 기록이 있는 어제 날짜 Guard 키(`...:{어제}`)와는 **전혀 다른 Redis 키**다.
3. 오늘 날짜 Guard 키는 아무도 안 써본 새 키이므로 `SET NX`가 그대로 성공한다.
4. `INCRBY`+`XADD`가 정상 실행되어 **동일 미션에 실제로 이중 지급이 발생한다.**

같은 날짜 재시도라면 Guard 키가 동일해 `SET NX`가 막히고 `REQUEST_ID_CONFLICT`로 안전하게 끝나지만(옛 fingerprint 형식과 불일치하기 때문), **자정을 넘긴 재시도만 이 경로를 뚫는다.** Guard 값에는 형식 버전이나 최초 periodKey가 저장돼 있지 않아 코드만으로는 "이게 legacy Guard인지"를 판별할 방법이 없다. 그렇다고 Guard 불일치를 영구적으로 `ALREADY_PROCESSED`로 완화하면, 배포가 끝난 뒤에도 영구적으로 "다른 내용 재요청 감지"(FR-P1-020)가 무력화되므로 이 방법은 채택하지 않는다.

**따라서 기존 Redis EARN 데이터를 유지한 채 배포하려면 다음 중 하나를 배포 전제조건으로 반드시 확정해야 한다:**

- 마지막 구버전 EARN 처리 시각으로부터 25시간(구 Guard TTL)이 지난 뒤에 새 코드를 배포한다 — 그 시점엔 모든 legacy Guard가 자연 소멸해 있으므로 새 코드는 자기가 만든 신규 형식 Guard만 보게 된다.
- 또는 별도 마이그레이션·만료 정책을 확정한 뒤 진행한다. **`mission:earn-guard:*` 패턴을 영향 분석 없이 일괄 삭제하는 것은 금지한다** — 이 패턴은 legacy(idem 없이 남은) Guard뿐 아니라 오늘 날짜의 정상적인 일일 중복 방지 Guard까지 함께 지워버려서, 이미 오늘 미션을 완료한 사용자가 삭제 직후 재요청하면 다시 지급받을 수 있는 새로운 이중 지급 창을 만든다.

**기존 Redis EARN 데이터가 없는 환경(로컬/신규 배포)**은 이 항목 전체가 해당 없다.

## 결과 코드

| 코드 | 의미 |
| --- | --- |
| `EARN_ACCEPTED` | 적립 성공. `{streamId, 적립후잔액}` 포함 |
| `ALREADY_PROCESSED` | 동일 requestId·동일 fingerprint 재시도(또는 legacy 레코드). 기존 성공 결과 재반환 |
| `REQUEST_ID_CONFLICT` | 동일 requestId·다른 fingerprint (idem 또는 Guard 기준) |
| `DUPLICATE_MISSION` | 다른 requestId로 같은 미션(같은 날) 재요청 |
| `EARN_STATUS_UNKNOWN` | idem이 PROCESSING(이전 시도 미확정) — Lua가 직접 반환. Redis 타임아웃 등 Java 예외 매핑 시에도 동일 코드 사용 |
| `EARN_PROCESSING_FAILED` | 스크립트 실행 자체가 예외를 던졌을 때 Java가 매핑(스크립트가 직접 반환하는 코드 아님) |

## `findExisting()` — read-only 조회 Contract (issue #125)

`TicketEarnService.findExisting(EarnCommand)`는 `earn()`을 실행하지 않고 idem만 조회한다. 미션 완료 계층이 활성 상태 검증보다 먼저 호출해, 기존 처리된 requestId면 신규 지급으로 진행하지 않도록 한다.

| 상태 | 의미 |
| --- | --- |
| `ALREADY_PROCESSED` | 완료된 기존 요청(정상 COMPLETED 또는 legacy) |
| `REQUEST_ID_CONFLICT` | 동일 requestId·다른 fingerprint |
| `NOT_FOUND` | idem 기록 없음 |
| `UNAVAILABLE` | Redis 조회 실패, 또는 idem이 PROCESSING(신규 지급 금지 판단은 같지만 원인이 다름) |

조회와 실제 `earn()` 호출 사이의 경쟁 상태는 허용한다 — 최종 판정은 `earn()` Lua의 원자적 처리가 보장하므로 TOCTOU가 중복 지급으로 이어지지 않는다.

## 테스트

파일: `src/test/java/kr/co/cking/ticket/application/TicketEarnServiceImplTest.java` (22개), `TicketEarnServiceImplErrorMappingTest.java` (2개)

- periodKey만 자정 경계로 달라진 재시도가 `earn()`에서는 `REQUEST_ID_CONFLICT`가 아니라 `ALREADY_PROCESSED`인지
- **`findExisting()`도 periodKey만 다른 재시도에 `ALREADY_PROCESSED`를 반환하는지** — 이슈 #125가 실제로 고치는 지점(미션 active 검증 전 호출)은 `earn()`이 아니라 `findExisting()`이므로 별도로 직접 검증한다
- periodKey와 amount가 함께 다르면 여전히 `REQUEST_ID_CONFLICT`인지
- idem·Guard가 25시간 TTL로 함께 생성되는지
- idem이 PROCESSING으로 남으면 `earn()`/`findExisting()` 모두 신규 지급으로 진행하지 않는지
- `status` 필드 없는 legacy 레코드가 fingerprint 불일치와 무관하게 `ALREADY_PROCESSED`로 재현되는지
