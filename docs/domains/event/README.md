# Event 도메인

Event 도메인은 공개 이벤트 조회, 응모 요청 진입점, Event 상태 전이와 마감 오케스트레이션의 경계를 관리한다. 외부 HTTP 요청은 Controller가 받고, 상태 전이는 반드시 `EventCommandService`를 통해서만 수행한다.

## 문서

- [외부 API](api.md): 이벤트 조회, 응모, Creator 운영, 수동 마감과 관리자 마감 상태 조회 계약
- [응모 Lua API](lua-api.md): 응모권 차감·멱등성·Stream 발행의 Redis 원자 처리 계약
- [마감 오케스트레이션](closing.md): 자동·수동 마감이 공유하는 Gate, cutoff, Drain, CLOSED 확정 흐름

## 책임 경계

- `EventQueryService`는 공개 Event 조회와 조회 캐시를 담당하며, 명령 처리용 조회는 캐시를 거치지 않는다.
- `EventEntryService`는 공개 Event에서 Creator를 해석한 뒤 응모 Lua 서비스로 요청을 전달한다.
- `EventClosingService`는 마감 시작만 담당한다. Drain 완료 판정과 CLOSED 전이는 `EventLifecycleScheduler`가 다음 틱에서 처리한다.
- Snapshot 생성은 CLOSED Commit 뒤 `OfficialSnapshotService.createIfAbsent(eventId)`로 요청하며, Snapshot 실패가 Event를 CLOSING으로 되돌리지는 않는다.
