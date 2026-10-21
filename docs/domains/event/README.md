# Event 도메인

Event 도메인은 공개 이벤트 조회, 응모 요청 진입점, Event 상태 전이와 마감 오케스트레이션의 경계를 관리한다. 외부 HTTP 요청은 Controller가 받고, 상태 전이는 반드시 `EventCommandService`를 통해서만 수행한다.

## 문서

- [외부 API](api.md): 이벤트 조회, Creator 운영, 수동 마감과 관리자 마감 상태 조회 계약
- [응모 API](entry-api.md): 응모와 내 응모 조회 계약, 결과코드별 HTTP 상태
- [응모 Lua API](lua-api.md): 응모권 차감·멱등성·Stream 발행의 Redis 원자 처리 계약
- [마감 오케스트레이션](closing.md): 자동·수동 마감이 공유하는 Gate, cutoff, Drain, CLOSED 확정 흐름

## 책임 경계

- `EventQueryService`는 삭제·보관되지 않은 공개 Event만 조회와 캐시에 올린다. 상세는 `EventCache`(`cache:event:{eventId}`), 목록은 `EventListCache`(`cache:event:list:{creatorId|ALL}:{displayStatus|ALL}:{page}:{size}`)를 쓴다. 둘 다 TTL 5초를 넘지 않고 페이지 안 이벤트의 endAt도 넘지 않으며, 캐시는 원본 필드만 저장하고 `displayStatus`는 읽는 시점의 now로 매번 다시 계산한다. 상태·보관 변경 시 상세는 능동으로 evict하지만 목록은 하지 않는다 — 한 상태 변경이 걸치는 (creator, displayStatus, page) 조합을 역산하는 비용이 크고, TTL 5초의 staleness는 허용 범위다.
- Event 보관은 `PUBLISHED`에서만 가능한 관리자 전용 전역 숨김이다. `archivedAt`·`archivedBy`만 기록하고 원본 Event·Entry·Ledger·Snapshot·Drawing·Winner·Notification은 보존한다. 사용자·Creator 화면, 응모, 사용자 당첨·알림에서는 보관 Event를 존재하지 않는 Event처럼 제외하며, 관리자 보관함에서만 복원할 수 있다. 운영 환경에서는 Event 행 잠금 안에서 `SELECTED` Winner를 현재 읽기로 확인하며, Winner를 새로 저장하는 재추첨 실행도 같은 Event 잠금을 잡는다. 따라서 수령 대기 당첨 생성과 보관은 동시에 확정될 수 없다.
- `EventEntryService`는 공개 Event에서 Creator를 해석하고 요청 Member가 소유자인지 검증한 뒤 응모 Lua 서비스로 요청을 전달한다. 본인 Event 응모는 Lua 실행 전에 거절한다. Event 조회 성공 후 `requestedAt`을 확정하고 SPEND의 최종 성공·replay·실패 결과를 `EntryAbuseObserver`에 전달한다. Observer는 요청의 `couponType`으로 공용/Creator 전용 `BalanceScope`를 선택하며, 탐지 장애는 응모 응답·원래 예외를 변경하지 않는다. Event 미존재·본인 Event 등 차감 이전 오류와 입력 검증 실패는 관찰하지 않는다. 탐지 정책은 [Abuse 정본](../abuse-detection/README.md)을 따른다.
- `EventClosingService`는 마감 시작만 담당한다. Drain 완료 판정과 CLOSED 전이는 `EventLifecycleScheduler`가 다음 틱에서 처리한다.
- Snapshot 생성은 CLOSED Commit 뒤 `OfficialSnapshotService.createIfAbsent(eventId)`로 요청하며, Snapshot 실패가 Event를 CLOSING으로 되돌리지는 않는다.
