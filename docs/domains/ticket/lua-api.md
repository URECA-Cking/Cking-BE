# EARN Lua 원자 처리 (ticket-earn.lua)

미션 완료(EARN) 요청의 멱등성 확인·중복 적립 가드·Balance 증가·Stream 발행을 하나의 Redis Lua 스크립트에서 원자적으로 처리한다(FR-P2-006/008).

스크립트: `src/main/resources/scripts/ticket-earn.lua`
Java 연동: `kr.co.cking.ticket.application` (`TicketEarnService`/`TicketEarnServiceImpl`, `TicketRedisKeys`)

## Redis 키

| 키 | 타입 | TTL | 용도 |
| --- | --- | --- | --- |
| `idem:mission:{requestId}` | STRING(JSON) | 25시간 | requestId 기준 결과 재현. `{fingerprint, status, guardKey, result?}` |
| `mission:earn-guard:{userId}:{missionType}:{creatorId}:{yyyyMMdd}` | STRING | 25시간 | 하루 1회 중복 적립 방지(FR-P2-006). 값은 `requestId:fingerprint` |
| `ticket:maint:{creatorId}:{userId}` | STRING | 보정 서비스 관리 | `TicketCompensationService.resyncRedisToDb()`가 해당 조합을 보정하는 동안 존재. Lua는 `EXISTS`만 확인한다(issue #172) |

두 키 모두 25시간으로 통일했다(issue #125) — 서로 다른 TTL로 두면 한쪽만 만료된 비대칭 상태가 생겨 처리를 복잡하게 만든다.

Guard는 `requestId:fingerprint`만 저장한다. 신규 idem은 `fingerprint`, `status`, `guardKey`를 저장하고 완료 시 `result`를 추가한다. `guardKey`는 PROCESSING 예약 시 실제로 SET NX한 Guard 키 문자열이며, `COMPLETED`로 확정된 뒤에도 지우지 않고 그대로 남긴다 — 재조회 로직은 안 쓰지만 어떤 Guard로 확정됐는지 추적할 수 있다. `periodKey` 자체는 저장하지 않는다.

## TTL 만료 후 재요청 정책

idem과 일일 Guard는 모두 25시간 후 만료된다. 두 기록이 모두 만료된 뒤 동일 `requestId`가 들어오면 Redis 멱등성 replay 대상으로는 조회되지 않으며, 해당 호출은 신규 EARN 요청으로 처리한다. 영구 DB에 requestId별 replay 결과를 보존하지 않는다. 신규 요청의 미션 활성 상태 검증은 호출 계층(System1)이 수행하고, EARN은 일반적인 일일 Guard 조건으로 중복 적립을 판정한다.

## 처리 순서

```
idem:mission:{requestId} 조회
├─ 없음                              → PROCESSING 레코드 SET NX (fingerprint, guardKey 저장)
├─ 있고 fingerprint 다름              → REQUEST_ID_CONFLICT
├─ 있고 fingerprint 같음 + COMPLETED → ALREADY_PROCESSED (저장된 result 재현)
├─ 있고 fingerprint 같음 + PROCESSING, 저장된 Guard가 이 requestId:fingerprint → ALREADY_PROCESSED (self-heal, issue #148)
├─ 있고 fingerprint 같음 + PROCESSING, Guard 불일치/부재               → EARN_STATUS_UNKNOWN
└─ 있고 status 필드 자체가 없음        → ALREADY_PROCESSED (legacy 호환, 아래 참고)

(신규 요청만) 수동 보정 락 확인 (issue #172) → BALANCE_MAINTENANCE 또는 계속 진행 → 일일 Guard 검사·선점 → INCRBY → XADD → idem을 COMPLETED로 확정
```

## fingerprint는 periodKey를 제외한다 (issue #125)

`periodKey`는 클라이언트 입력이 아니라 System1이 서버 UTC 기준으로 매 호출마다 새로 계산하는 파생값이다. fingerprint에 포함시키면 같은 `requestId`가 자정을 넘겨 재시도될 때 서버가 다시 계산한 오늘 날짜 때문에 fingerprint가 최초 요청과 달라져 `REQUEST_ID_CONFLICT`로 오판된다. 그래서 fingerprint는 `userId, creatorId, missionType, missionId, missionKey, amount`만으로 계산하고, `periodKey`는 fingerprint가 아니라 일일 Guard 키와 `MissionCompletion` 저장값에만 쓴다.

신규 요청의 Guard는 이번 호출의 periodKey로 주소 지정한다. `COMPLETED` idem 재시도는 Guard를 보지 않고, `PROCESSING` idem 재시도는 저장된 `guardKey`로 원래 Guard를 확인한다. 하지만 idem이 애초에 없는 Guard-only 상태(아래 참고)에서 자정을 넘겨 재시도하면, 원래 Guard 키를 찾을 방법이 없어 새 요청처럼 처리되어 이중 지급으로 이어질 수 있다.

**채택하지 않은 대안: 최초 periodKey 자체를 replay 레코드에 저장.** PROCESSING 복구에는 periodKey보다 실제 Redis 키인 `guardKey`만 저장하면 충분하다. Guard-only 상태는 idem이 없으므로 어느 값을 저장해도 해결되지 않는다.

## PROCESSING → COMPLETED 2단계와 실패 시 정리

idem은 실제 지급 전에 `PROCESSING`으로 먼저 선점되고, `INCRBY`+`XADD` 성공 후 `COMPLETED`로 확정된다. 실패 시점에 따라 처리가 다르다:

- **Guard 선점 실패, `INCRBY` 실패, `XADD` 실패** — 아직 아무것도 반영되지 않았거나(Guard 선점 실패, INCRBY 실패) 보상까지 끝난 경우(XADD 실패 후 Balance 원복)라 idem·Guard를 모두 정리하고 재시도를 처음부터 받는다. Guard 선점은 `redis.pcall`로 감싸 진짜 Redis 오류(OOM 등)에도 idem PROCESSING이 25시간 잠기지 않도록 한다.
- **`COMPLETED` 확정 자체가 실패** — Balance/Stream은 이미 반영된 뒤라 되돌릴 수 없다. PROCESSING idem에 남긴 원래 `guardKey`에서 `requestId:fingerprint`를 확인해 `ALREADY_PROCESSED`로 복구하고, idem을 `COMPLETED`로 self-heal한다(issue #148). Guard가 없거나 다른 값이면 `EARN_STATUS_UNKNOWN`/`UNAVAILABLE`로 신규 지급을 막는다.

`EARN_STATUS_UNKNOWN`은 이 PROCESSING 분기에서 Lua가 **일반 반환값**으로 직접 내보낸다(`redis.error_reply`가 아님). 기존에는 `TicketEarnServiceImpl`이 `QueryTimeoutException`을 잡았을 때만 매핑하던 코드인데, Lua가 정상 실행 중 판단해서 반환하는 경우가 추가된 것뿐이며 `EarnResultCode` enum에는 새 값을 추가하지 않는다.

Guard 자체가 idem보다 먼저 만료되는 경우는 없다 — 같은 `EX` 값(25h)으로 idem을 먼저 SET하고 그 다음에 Guard를 SET하므로, idem이 살아있는 동안 Guard가 이미 만료돼 있을 수 없다. "PROCESSING SET 직후 ~ Guard SET 사이에 죽는 경우"도 Lua 스크립트 실행 자체가 원자적이라(다른 클라이언트가 그 사이 상태를 관측할 수 없음) 이 코드베이스가 전제하는 실행 모델에서는 나오지 않는다. 즉 idem이 `PROCESSING`으로 관측됐다면 Guard도 반드시 존재하며, Guard 값이 이 requestId:fingerprint와 다르다는 것은 다른 요청이 그 사이 같은 Guard를 선점했다는 뜻이지 "복구 불가능한 잔여 범위"가 아니다.

복구 경로가 `{'ALREADY_PROCESSED'}` 코드만 반환하고 원래 `streamId`·적립후잔액은 재현하지 못한다는 점은 남아있는 한계다 — Guard 값에는 `requestId:fingerprint`만 있어 그 두 값을 복원할 방법이 없다. `EarnResult`가 지금은 `EarnResultCode`만 담는 bare record라 문제되지 않지만, 이후 `EarnResult`가 streamId·잔액을 노출하도록 확장되면 이 self-heal 경로부터 재검토해야 한다.

## Legacy idem 호환과 배포 시 유의사항 (issue #125)

이 2단계 구조 도입 전(`status` 필드 없이 `{fingerprint, result}`만 있던 시절)에 저장된 idem 레코드가 배포 시점에 아직 살아있을 수 있다. 그 legacy `fingerprint`는 옛 공식(periodKey 포함)으로 계산돼 있어 새 공식과 비교하면 항상 불일치한다 — 그래서 **`status` 필드가 아예 없는 레코드는 fingerprint를 비교하지 않고 존재 자체를 완료된 성공으로 신뢰**한다(`ticket-earn.lua`, `TicketEarnServiceImpl.findExisting()` 동일 분기).

- **적용 기간: 최대 24시간(구 idem TTL)부터 배포 시점까지.** 배포 직전에 생성된 legacy idem이 가장 오래 남아 있고, 그 뒤로는 legacy 레코드가 하나도 없으므로 이 호환 분기 자체가 죽은 코드가 된다 - 별도로 걷어낼 필요는 없지만 배포 후 24시간이 지나면 실질적 의미가 없어진다는 뜻이다.
- 이 완화의 트레이드오프: 그 기간 동안 같은 requestId가 실제로 다른 내용으로 재사용되면 그 다른 내용은 무시되고 예전 결과가 재현된다(FR-P1-020이 요구하는 `REQUEST_ID_CONFLICT` 판정을 이 legacy 경로에서만 건너뛴다). 잔액을 건드리지 않는 replay이므로 **이중 지급 위험은 없다** — FR-P1-017이 이 Redis 멱등키를 "성능용 캐시"로, DB `request_id` UNIQUE를 "최종 안전망"으로 명시한 것과 일치하는 선택이다.

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
| `EARN_STATUS_UNKNOWN` | idem이 PROCESSING이고 Guard로도 성사 여부를 확인 못함 — Lua가 직접 반환. Redis 타임아웃 등 Java 예외 매핑 시에도 동일 코드 사용. (Guard가 이 requestId:fingerprint와 일치하면 `ALREADY_PROCESSED`로 복구되므로 이 코드는 안 나온다, issue #148) |
| `EARN_PROCESSING_FAILED` | 스크립트 실행 자체가 예외를 던졌을 때 Java가 매핑(스크립트가 직접 반환하는 코드 아님) |
| `BALANCE_MAINTENANCE` | 수동 보정 락(`ticket:maint:{creatorId}:{userId}`)이 걸린 신규 요청(issue #172, HTTP 503, `MissionErrorCode.BALANCE_MAINTENANCE`, SPEND의 `BALANCE_MAINTENANCE`와 동일 계약) |

## 수동 보정 락 (issue #172)

`TicketCompensationService.resyncRedisToDb()`가 DB 잔액을 Redis에 덮어써 재동기화하는 동안 EARN이 같은 `(creatorId, userId)`에 끼어들면, 보정 직후 잔액이 다시 어긋날 수 있다. idem 재현 분기(위 처리 순서의 위쪽 5개 분기)를 모두 통과한 **신규 요청만** `ticket:maint:{creatorId}:{userId}` 존재 여부를 확인하고, 있으면 PROCESSING idem 예약조차 만들지 않고 `{ 'BALANCE_MAINTENANCE' }`를 반환한다(HTTP 503) - SPEND와 동일한 코드·HTTP 상태를 쓰기로 합의했다.

이 확인은 **일일 Guard 검사보다 먼저** 실행된다(SPEND가 idem·guard 재현 직후, Balance 확인 이전에 락을 보는 것과 같은 자리). 그래서 보정 중에 새 `requestId` + 같은 Business Key(같은 미션·같은 날)로 요청이 오면, 원래라면 `DUPLICATE_MISSION`(409)이 될 요청도 `BALANCE_MAINTENANCE`(503)로 먼저 걸린다. 재시도하면 락 해제 후 실제 판정(`DUPLICATE_MISSION` 또는 성공)으로 수렴하므로 정합성 문제는 아니다.

락 키를 실제로 SET/DEL하고 보정 전 미반영 Stream·PEL·Dead Stream 메시지를 확인하는 `TicketCompensationService` 쪽 로직(`TicketMaintenanceLock`, issue #174/PR #176)은 이 변경에 포함되지 않았다 - 별도 PR에서 진행하며, 두 PR이 모두 머지된 뒤 이슈 #172를 닫는다. `TicketRedisKeys.maintenance(creatorId, userId)`가 두 PR이 공유하는 키 빌더다.

## `findExisting()` — read-only 조회 Contract (issue #125)

`TicketEarnService.findExisting(EarnCommand)`는 `earn()`을 실행하지 않고 idem만 조회한다. 미션 완료 계층이 활성 상태 검증보다 먼저 호출해, 기존 처리된 requestId면 신규 지급으로 진행하지 않도록 한다.

| 상태 | 의미 |
| --- | --- |
| `ALREADY_PROCESSED` | 완료된 기존 요청(정상 COMPLETED 또는 legacy) |
| `REQUEST_ID_CONFLICT` | 동일 requestId·다른 fingerprint |
| `NOT_FOUND` | idem 기록 없음 |
| `UNAVAILABLE` | Redis 조회 실패, 또는 idem이 PROCESSING이고 Guard로도 성사 여부를 확인 못함(신규 지급 금지 판단은 같지만 원인이 다름) — Guard가 이 requestId:fingerprint와 일치하면 `ALREADY_PROCESSED`로 복구된다(issue #148) |

조회와 실제 `earn()` 호출 사이의 경쟁 상태는 허용한다 — 최종 판정은 `earn()` Lua의 원자적 처리가 보장하므로 TOCTOU가 중복 지급으로 이어지지 않는다.

## 테스트

파일: `src/test/java/kr/co/cking/ticket/application/TicketEarnServiceImplTest.java` (31개), `TicketEarnServiceImplErrorMappingTest.java` (2개)

- 수동 보정 락이 걸려 있으면 `BALANCE_MAINTENANCE`를 반환하고 잔액·idem·guard를 건드리지 않는지, 이미 완료된 요청의 replay는 락과 무관하게 재현되는지, 락이 풀린 뒤 같은 requestId로 재시도하면 EARN_ACCEPTED로 처리되는지 (issue #172)
- periodKey만 자정 경계로 달라진 재시도가 `earn()`에서는 `REQUEST_ID_CONFLICT`가 아니라 `ALREADY_PROCESSED`인지
- **`findExisting()`도 periodKey만 다른 재시도에 `ALREADY_PROCESSED`를 반환하는지** — 이슈 #125가 실제로 고치는 지점(미션 active 검증 전 호출)은 `earn()`이 아니라 `findExisting()`이므로 별도로 직접 검증한다
- periodKey와 amount가 함께 다르면 여전히 `REQUEST_ID_CONFLICT`인지
- idem·Guard가 25시간 TTL로 함께 생성되는지
- idem이 PROCESSING이고 **Guard도 없으면** `earn()`/`findExisting()` 모두 신규 지급으로 진행하지 않는지
- idem이 PROCESSING이어도 **저장된 Guard가 이 requestId:fingerprint와 일치하면** `earn()`/`findExisting()` 모두 `ALREADY_PROCESSED`로 복구하고, 자정 이후 재시도에도 원래 Guard를 조회하는지(issue #148)
- idem이 PROCESSING이고 **Guard가 다른 요청의 값**이면(다른 requestId) 여전히 신규 지급을 막는지
- **`earn()`이 PROCESSING 예약 시 실제로 사용한 Guard 키를 idem의 `guardKey` 필드에 기록하는지** — idem JSON을 직접 심어 읽기만 검증하는 다른 테스트들과 달리, 이 테스트는 정상 `earn()` 실행 결과를 직접 읽어 Lua의 저장 로직 자체를 검증한다
- `status` 필드 없는 legacy 레코드가 fingerprint 불일치와 무관하게 `ALREADY_PROCESSED`로 재현되는지
