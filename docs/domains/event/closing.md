# Event 마감 오케스트레이션

## 진입점과 주기

자동·수동 마감은 모두 `EventClosingService.startClosing(eventId)`를 사용한다. 자동 경로는 `EventLifecycleScheduler`가 기본 10초 간격으로 실행하며, 순서는 예약 시작, 종료된 OPEN Event의 마감 시작, CLOSING Event의 Drain 완료 확인이다.

수동 마감은 Creator 소유권 또는 관리자 권한을 확인한 뒤 같은 서비스를 호출하고 202 Accepted와 현재 `CLOSING` 또는 `CLOSED` 상태만 반환한다. 이미 CLOSING/CLOSED인 Event는 새 barrier를 만들지 않고 현재 상태를 반환한다.

## 상태와 Stream 경계

```text
OPEN
  -> Gate 차단 및 EVENT_ENTRY_CLOSED barrier 발행
  -> cutoffStreamId 저장
  -> CLOSING
  -> cutoff 이하 응모가 Drain됨
  -> CLOSED
  -> OfficialSnapshotService.createIfAbsent(eventId)
```

마감 시작 전 Event 상태가 OPEN이 아니면 `INVALID_STATE`다. Gate·barrier·cutoff는 `EventCutoffBarrier`가 처리하고, 상태 전이는 `EventCommandService.startClosing()`과 `completeClosing()`만 수행한다.

`EventCutoffBarrier`는 새 cutoff를 확정할 때 실시간 응모 현황(FR-P2-045~050) 집계 키(`event:entry-total`,
`event:entrants`)에도 24시간 만료를 건다 - 마감 이후에는 신규 응모가 없어 조회가 DB 집계로 넘어가면
충분하기 때문이다. 자세한 내용은 [응모 Lua API](lua-api.md#실시간-응모-현황-집계-fr-p2-045050) 참고.

## Drain 완료 조건

`EventDrainChecker.isDrained(eventId, cutoffStreamId)`는 다음을 모두 만족할 때만 true다.

1. `cg:ticket-history`의 마지막 전달 Stream ID가 cutoff 이상이다.
2. cutoff 이하 PEL을 페이지 단위로 끝까지 검사했을 때 해당 Event의 미ACK SPEND 메시지가 없다.
3. cutoff 이하의 미해결 SPEND Dead Stream 메시지가 없다.

Drain은 요청 스레드에서 기다리지 않는다. 조건이 아직 충족되지 않거나 Redis/DB 조회가 실패하면 Event는 CLOSING으로 남고 다음 10초 틱에서 재확인한다. 재기동 뒤에도 CLOSING Event와 저장된 cutoff를 다시 찾아 같은 방식으로 재개한다.

**FR-11b(Drain 재시도 간격·최대 대기시간, 시스템2 자율 결정 항목)**: 재시도 간격은 별도로 두지 않고 10초 tick 자체를 재시도 간격으로 쓴다. 연속 미완료 30틱(약 5분)마다 WARN을, 180틱(약 30분)부터는 같은 주기로 ERROR를 남겨 "정상 지연"과 "사실상 멈춘 상태"를 로그 레벨로 구분한다. 어느 경우에도 CLOSED로 강제 전환하지 않는다 — 이 시점부터는 로그를 보고 Dead Stream 확인·수동 replay 등 운영자 개입이 필요하다는 뜻이다. 30분 임계값은 실측 부하 테스트(NFR-06) 전의 잠정값이며, `cking.event.drain-warn-every-ticks`/`cking.event.drain-error-after-ticks` 프로퍼티로 배포 없이 재조정할 수 있다(tick 간격 `cking.event.lifecycle-interval-ms`도 동일 패턴). ERROR 로그의 경과 시간은 "30분" 고정 문구가 아니라 `틱 수 × lifecycle-interval-ms`로 계산하므로, tick 간격을 바꿔도 로그 문구는 실제 경과 시간과 어긋나지 않는다.

## Snapshot 호출

Drain이 끝나면 먼저 CLOSED를 Commit한 뒤 Snapshot 생성을 요청한다. Snapshot 생성 실패는 로그로 남지만 CLOSED 상태를 되돌리지 않는다. Snapshot 서비스가 CLOSED 상태와 기존 Snapshot을 다시 검증해 멱등성을 보장한다.
