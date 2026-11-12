# Event 도메인

Event 도메인은 공개 이벤트 조회, 응모 요청 진입점, Event 상태 전이와 마감 오케스트레이션의 경계를 관리한다. 외부 HTTP 요청은 Controller가 받고, 상태 전이는 반드시 `EventCommandService`를 통해서만 수행한다.

## 문서

- [외부 API](api.md): 이벤트 조회, Creator 운영, 수동 마감과 관리자 마감 상태 조회 계약
- [배너 API](banner-api.md): 배너 이미지 업로드, 생성·수정 연결, 응답 필드와 정리 작업(저장 경로는 Creator Space 이미지 접두사 공유)
- [응모 API](entry-api.md): 응모와 내 응모 조회 계약, 결과코드별 HTTP 상태
- [응모 Lua API](lua-api.md): 응모권 차감·멱등성·Stream 발행의 Redis 원자 처리 계약
- [마감 오케스트레이션](closing.md): 자동·수동 마감이 공유하는 Gate, cutoff, Drain, CLOSED 확정 흐름

## 책임 경계

- `EventQueryService`는 삭제·보관되지 않은 공개 Event만 조회와 캐시에 올린다. 상세는 `EventCache`(`cache:event:{eventId}`), 목록은 `EventListCache`(`cache:event:list:{creatorId|ALL}:{displayStatus|ALL}:{page}:{size}`)를 쓴다. 상세 캐시를 채울 때 Creator 소유 회원 ID를 조회해 Event 원본 필드와 함께 저장하며, 이전 버전처럼 소유 회원 ID가 없는 캐시는 miss로 처리한다. 트랜잭션 밖의 호출(응모·상세)은 상세 캐시 적중에 DB 연결을 쓰지 않고, 비었을 때 서버마다 이벤트당 한 요청만 읽기 전용 트랜잭션 하나로 채우고 나머지는 연결 없이 그 결과를 기다리며, 트랜잭션 안의 호출(응모 현황)은 이미 연결을 쥐고 있어 기다리지 않고 자기 트랜잭션에서 바로 읽는다(연결을 쥔 채 기다리면 채우는 요청이 연결을 받지 못해 함께 멈춘다). 둘 다 TTL 5초를 넘지 않고 페이지 안 이벤트의 endAt도 넘지 않는다. 목록·상태별 개수는 Redis 캐시 확인·저장을, 상세는 캐시 확인을 트랜잭션 밖에서 하고 DB를 읽는 부분만 읽기 전용 트랜잭션으로 연다(상세 캐시 저장은 적재 트랜잭션 안). Redis가 응답하지 않을 때 요청마다 DB 연결을 쥔 채 Redis 명령 제한(1초)을 기다리면 서버당 연결 풀 10개가 고갈되기 때문이다(#857). 로그인 조회는 회원 확인을 캐시보다 먼저 한다. 목록은 회원 확인과 공통 잔액을 트랜잭션 하나에서 읽고, 상세는 회원 확인 뒤 캐시를 읽고 두 잔액을 트랜잭션 하나에서 읽는다. 상세 캐시는 적중이 이어지는 동안 만료 전에 요청 밖에서 다시 읽는다(`cking.event.cache.refresh-ahead`, 운영 값 `EVENT_CACHE_REFRESH_AHEAD_ENABLED`, 기본 켬). 서버마다 이벤트당 이전 적재가 끝나고 500ms 뒤 한 번이고, 전용 스레드 2개·대기열 64개라 DB 연결은 최대 2개만 쓴다. 대기열이 차면 버리고 500ms 뒤 적중에서 다시 맡는다. endAt이 지난 이벤트는 다시 읽지 않는다. 정각에 캐시가 만료되는 순간 DB가 RDS 매시 관리 작업으로 멈추면 요청이 적재 하나를 함께 기다리는 것을 막는다([Infra 부하 테스트 3.7](https://github.com/URECA-Cking/Cking-Infra/blob/main/docs/loadtest.md)). `displayStatus`는 읽는 시점의 now로 매번 다시 계산한다. 상태·보관 변경 시 상세는 능동으로 evict하지만 목록은 하지 않는다 — 한 상태 변경이 걸치는 (creator, displayStatus, page) 조합을 역산하는 비용이 크고, TTL 5초의 staleness는 허용 범위다.
- Event 보관은 `PUBLISHED`에서만 가능한 관리자 전용 전역 숨김이다. `archivedAt`·`archivedBy`만 기록하고 원본 Event·Entry·Ledger·Snapshot·Drawing·Winner·Notification은 보존한다. 사용자·Creator 화면, 응모, 사용자 당첨·알림에서는 보관 Event를 존재하지 않는 Event처럼 제외하며, 관리자 보관함에서만 복원할 수 있다. 운영 환경에서는 Event 행 잠금 안에서 `SELECTED` Winner를 현재 읽기로 확인하며, Winner를 새로 저장하는 재추첨 실행도 같은 Event 잠금을 잡는다. 따라서 수령 대기 당첨 생성과 보관은 동시에 확정될 수 없다.
- `EventEntryService`는 공개 Event 캐시에 담긴 소유 회원 ID와 요청 Member를 비교한 뒤 응모 Lua 서비스로 요청을 전달한다. 캐시 적중 시 소유권 검사를 위해 DB를 읽지 않으며, 본인 Event 응모는 Lua 실행 전에 거절한다. Event 조회 성공 후 `requestedAt`을 확정하고 SPEND의 최종 성공·replay·실패 결과를 `EntryAbuseObserver`에 전달한다. Observer는 요청의 `couponType`으로 공용/Creator 전용 `BalanceScope`를 선택하며, 탐지 장애는 응모 응답·원래 예외를 변경하지 않는다. Event 미존재·본인 Event 등 차감 이전 오류와 입력 검증 실패는 관찰하지 않는다. 탐지 정책은 [Abuse 정본](../abuse-detection/README.md)을 따른다.
- `EventClosingService`는 마감 시작만 담당한다. Drain 완료 판정과 CLOSED 전이는 `EventLifecycleScheduler`가 다음 틱에서 처리한다.
- Snapshot 생성은 CLOSED Commit 뒤 `OfficialSnapshotService.createIfAbsent(eventId)`로 요청하며, Snapshot 실패가 Event를 CLOSING으로 되돌리지는 않는다.
