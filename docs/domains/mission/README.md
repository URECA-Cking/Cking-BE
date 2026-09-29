# Mission 도메인

Mission 도메인은 크리에이터별 미션 정의(`mission`)와 공용 미션 정의(`common_mission`)의 완료 판정을 담당한다. 응모권 적립 자체는 Ticket 도메인(`TicketEarnService`/`CommonTicketEarnService`)의 책임이며, 이 도메인은 완료 여부만 판정하고 EARN을 요청한다.

## 문서

- [미션 완료 API](api.md): `POST .../missions/{missionId}/complete` 계약, 공용 미션 API 포함

## 소유 데이터와 경계

- `mission`: 크리에이터당 유형별 미션 정의(`uk_mission_creator_type`). 현재 크리에이터별 기본 미션은 LIKE·SHARE이며, ATTENDANCE는 공용 미션으로 분리되어 있다. `activeFrom`/`activeTo`, `rewardAmount`를 가진다.
- `mission_completion`: 완료 이력. 이 도메인이 직접 쓰지 않는다 — [Ticket EARN Lua API](../ticket/lua-api.md)가 처리한 EARN 결과를 EARN Stream Consumer가 비동기로 반영한다(취합v1.5.4 §4.5). `MissionCompletionService`가 `mission_completion`을 먼저 써버리면 Consumer가 재전달로 오판해 Ledger·Balance 반영을 건너뛰므로, 이 서비스는 Redis/Stream 계층 밖의 어떤 영속 상태도 직접 만들지 않는다.

## 활성 판정

`Mission.isActiveAt(Instant now)`는 `activeFrom <= now < activeTo`를 계약으로 한다 — `Event`의 `startAt <= now < endAt`(취합v1.5.4 §2.5)와 동일하게 `activeTo`는 배제(exclusive)다. `activeFrom`/`activeTo`가 비어 있으면 상시 활성이다.

## 종료 후 재시도(Issue #125)

일반 완료 API가 신규 완료를 지원하는 `LIKE`는, 미션 종료 전에 성공했던 `requestId`가 재전송되면 활성 검증보다 먼저 `TicketEarnService#findExisting()`으로 기존 요청을 조회한다. 기존 성공 기록이 있으면 `MISSION_INACTIVE`가 아니라 원래의 EARN 결과를 반환해야 한다(FR-P1-018 멱등 재요청 계약). 레거시 Creator `ATTENDANCE`도 기존 성공 `requestId` replay만 허용하며 신규 완료는 공용 출석 경로로 전환한다. `YOUTUBE_SUBSCRIPTION`은 EARN replay 조회보다 먼저 별도 인증 경계로 차단한다. 자세한 순서는 [api.md](api.md#종료-후-동일-requestid-재시도)를 참고한다.

## 중복 방지 키의 레이어(임의 통합 금지)

이름이 비슷한 키가 서로 다른 목적으로 여러 개 존재한다. RTM이 각각을 별도 레이어로 명시하고 있어(FR-P1-015 비고), 임의로 하나로 합치지 않는다.

1. **RTM 판정 개념** `userId+creatorId+periodKey`: "이 사용자가 이 크리에이터에게서 오늘 이미 보상을 받았는가"를 가리키는 개념적 기준(FR-P1-015)일 뿐, Mission 코드가 이 조합으로 직접 조회·판정하지는 않는다.
2. **ONCE durable Business Key** `userId+creatorId+missionId`: SHARE는 `ticket_once_earn_request.uk_ticket_once_earn_business`를 Redis 실행 전에 선점한다. Redis Guard 유실 뒤 다른 `requestId`가 오더라도 Redis Balance를 증가시키기 전에 여기서 차단한다. `mission_completion.uk_completion_business`의 SHARE `completionKey=ONCE`는 Consumer 단계의 완료 이력 멱등성 안전망이며 Redis Balance 중복 증가 방어를 대신하지 않는다.
3. **EARN replay fingerprint** `userId+creatorId+missionType+missionId+missionKey+amount` — **`periodKey`는 포함하지 않는다.** `TicketEarnServiceImpl#computeFingerprint()`가 계산하며, 동일 `requestId` 재요청이 내용까지 같은지(`REQUEST_ID_CONFLICT` 판정)에 쓰인다. `periodKey`를 뺀 이유는 자정을 넘겨 재시도해도 같은 요청으로 인정하기 위해서다(이전에 `missionKey`에 `periodKey`가 섞여 들어가 이 의도가 깨졌던 버그를 수정한 적이 있다). **2번(DB Business Key)과 혼동하지 않는다** — 필드 구성도 다르고(`missionType`/`missionKey`/`amount` 포함, `periodKey` 제외) 목적도 다르다(2번은 하루 중복 방지, 3번은 요청 내용 일치 확인).
4. **Redis EARN Guard 키** `mission:earn-guard:{userId}:{missionType}:{creatorId}:{scope}`: LIKE의 `scope`는 `yyyymmdd`라 25시간 뒤 만료되고, SHARE의 `scope`는 `once`라 만료 없이 보존된다. SHARE Guard는 durable claim을 통과한 동시 실행의 Redis 1차 방어선이고, 평생 보상의 영속 보장은 2번 durable Business Key가 맡는다. `missionId`가 아니라 `missionType`을 쓴다. 1차 MVP는 크리에이터당 유형별 미션이 하나뿐(`uk_mission_creator_type`)이라 결과적으로 `missionId` 기준과 같지만, 여러 미션이 같은 유형을 가질 수 있게 되면 달라질 수 있는 별개 개념이다.

**중복 판정은 경로별로 나뉜다.** `MissionCompletionService`는 Creator별 LIKE 신규 요청을 Ticket EARN의 Redis Guard와 DB 제약에 위임하고, Creator ATTENDANCE 신규 요청은 공용 경로로 전환되어 EARN 전에 차단한다. `CommonMissionCompletionService`는 공용 EARN Guard에 더해 같은 사용자·UTC periodKey의 기존 Creator ATTENDANCE 완료 기록을 조회해 서비스 전체 하루 1회 정책을 적용한다. 두 서비스 모두 Guard 키나 fingerprint를 직접 계산하지 않고 EARN 계약에 필요한 값만 전달한다.

## 크리에이터별 미션 완료 경계

`MissionType`은 `ATTENDANCE`, `LIKE`, `SHARE`, `YOUTUBE_SUBSCRIPTION`을 지원한다.

- `LIKE`는 일반 완료 API와 Creator별 EARN 경로를 사용한다. 좋아요 취소는 별도 API가 없다. Mock 검증(버튼 클릭 = 완료, FR-P1-011) 방식이라 취소 시 서버에 알리지 않고 이미 지급된 응모권도 회수하지 않는다(FR-P1-012). 같은 날 재좋아요는 Redis EARN Guard가 차단한다(FR-P1-013).
- `SHARE` 보상은 일반 완료 API가 아닌 `POST /api/creators/{creatorId}/missions/share/complete`가 담당한다. 실제 외부 SNS 공유 성공 여부는 서버가 검증하지 않으며, **클라이언트의 공유 버튼 클릭 후 완료 요청을 공유 완료로 간주하는 Mock 방식**이다. 공유 취소·실패 시 보상은 회수하지 않는다. 대상 Creator와 그 Creator 소속 SHARE 미션을 검증하고, 기본 보상 1장을 `TicketOnceEarnService`로 요청한다. 동일 사용자·Creator·미션은 `ticket_once_earn_request` durable Business Key로 평생 한 번만 적립하며, EARN Stream Consumer가 `MissionCompletion`을 기록한다.
- `ATTENDANCE` 신규 완료는 `CommonMissionController`/`CommonMissionCompletionService`와 공용 EARN 경로에서 처리한다. 과거 Creator ATTENDANCE의 기존 성공 `requestId` replay는 허용하지만, 신규 요청은 `MissionCompletionService`가 거부한다.
- `YOUTUBE_SUBSCRIPTION`은 이미지 인증이 선행되어야 한다. 일반 `POST .../complete` 경로에서는 `TicketEarnService.findExisting()`이나 `earn()`을 호출하기 전에 `MISSION_REQUIRES_VERIFICATION`으로 차단한다. 따라서 클라이언트가 일반 완료 API로 인증과 보상 경계를 우회할 수 없다. 이미지 제출·판정·보상은 [YouTube 구독 인증 정본](../subscription-verification/README.md)을 따른다.

### 검증 범위(caveat)

- **기본 미션 생성 규약**: `MissionInitializationService.initializeDefaultMissions()`는 Creator 승인 트랜잭션에 참여해 크리에이터별 LIKE·SHARE를 각각 `rewardAmount=1`, `activeFrom=null`, `activeTo=null`로 생성한다. 출석은 `V16__add_common_ticket.sql`이 공용 ATTENDANCE를 시딩하므로 Creator 승인 시 생성하지 않고, `YOUTUBE_SUBSCRIPTION`도 기본 미션으로 자동 생성하지 않는다. 이미 존재하는 유형은 건너뛰므로 재호출해도 안전하다. 이번 배포 전 기존 Creator의 SHARE 누락분은 `V27__seed_share_mission.sql`이 멱등 시딩하며, 이후 수동 복구가 필요하면 `MissionBackfillRunner`가 별도 `REQUIRES_NEW` 트랜잭션으로 채운다. `Mission` 생성자는 일반 규칙으로 `rewardAmount > 0`만 강제하며, 기본값 1은 이 초기화 서비스의 정책이다.
- **완료 경로별 중복 적립 검증**: `MissionCompletionServiceTest`와 `MissionCompletionConcurrencyIntegrationTest`는 LIKE의 EARN 결과·동시 요청을 검증한다. `CreatorSpaceShareMissionCompletionServiceTest`는 SHARE의 대상 Creator·미션 소유·UTC Business Key·`requestId` 재요청을 검증한다. `CreatorSpaceShareE2EIntegrationTest`는 공개 slug 조회부터 SHARE 보상, Stream Consumer DB 반영, slug 변경까지의 통합 흐름을 검증한다. 공용 ATTENDANCE의 단일 적립은 `CommonMissionCompletionServiceIntegrationTest`에서 별도로 검증한다.

## 공용 미션(크리에이터 무관, 이슈 #219)

사용자는 크리에이터별 응모권과 별개로, 아무 크리에이터에게나 사용할 수 있는 **공용 응모권**을 가진다. "공용"은 크리에이터가 아니다 — `mission`/`mission_completion`은 `creator_id`가 NOT NULL이라 이 개념을 담을 수 없어, `CommonMission`/`CommonMissionCompletion`(`common_mission`/`common_mission_completion` 테이블)으로 완전히 분리했다.

- **판정 로직은 크리에이터별 미션과 완전히 동일하다.** `CommonMissionCompletionService.complete()`는 `MissionCompletionService.complete()`와 순서(기존 requestId 조회 → 활성 검증 → EARN 호출)가 같고, `MissionCompleteCommand`/`MissionCompleteOutcome`/`MissionErrorCode`도 원래 creatorId를 안 담는 범용 타입이라 그대로 재사용한다.
- **API 경로에 creatorId가 없다** — `GET /api/missions`, `POST /api/missions/{missionId}/complete`.
- **적립은 별도의 공용 EARN 경로를 탄다.** `CommonTicketEarnService`/`common-ticket-earn.lua`/`stream:common-ticket-earned`가 `TicketEarnService`/`ticket-earn.lua`/`stream:ticket-earned`와 같은 원자성·멱등성을 크리에이터 축 없이 재현한다. 자세한 계약은 [Ticket 도메인](../ticket/README.md#공용-응모권-크리에이터-무관-이슈-219)을 참고한다.
- **공용 미션은 유형당 하나뿐이라(`uk_common_mission_type`) 생성 계기(Creator 승인 같은)가 없다.** `V16__add_common_ticket.sql`이 기본 출석 미션(reward 1, 상시 활성)을 직접 시딩한다 — 이슈 #185의 크리에이터별 기본 미션 초기화와 동일한 정책이지만 코드가 아니라 데이터로 고정했다.
- **PEL 회수·Dead Stream 이관·수동 replay가 크리에이터 EARN과 동일하게 갖춰져 있다**([Ticket 도메인](../ticket/README.md#공용-응모권-크리에이터-무관-이슈-219) 참고).
- **알려진 제약(미해결)**: 공용 잔액 수동 보정(`TicketCompensationService` 대응)은 아직 없다. 후속 이슈로 남긴다.

### 출석 전환과 배포 전제

공용 ATTENDANCE는 사용자당 서비스 전체에서 서버 UTC 기준 하루 한 번만 적립한다. 공용 출석 처리 전에 `mission_completion`과 `mission`을 조인해 같은 사용자·같은 UTC `periodKey`의 기존 Creator ATTENDANCE 완료 기록을 확인한다. Creator가 다른 경우도 같은 날의 보상으로 간주하며, LIKE 완료 기록은 제외한다. 기존 Creator 응모권과 완료 이력은 삭제·회수·이관하지 않는다.

기존 Creator ATTENDANCE 신규 요청은 새 `MissionCompletionService`에서 EARN 전에 차단된다. 따라서 모든 인스턴스가 이 코드로 전환된 안정 상태에서는 Common과 Creator 사이에 공유 Redis Guard가 필요하지 않다. 다만 구버전 인스턴스가 Creator ATTENDANCE를 계속 처리하는 혼합 배포 구간에는 DB 완료 반영 전 지연으로 교차 중복이 가능하므로, **구버전 Creator 출석 신규 지급 경로를 먼저 차단한 뒤 공용 출석을 활성화해야 한다.** 이 배포 전제는 코드만으로 해결되지 않는다.

전환 순서는 다음과 같다.

1. 라우팅·feature flag 등 운영 계층에서 모든 구버전 인스턴스의 Creator ATTENDANCE 신규 요청을 먼저 차단한다. 차단 전에 이미 시작된 요청이 모두 응답·실패 처리될 때까지 구버전 인스턴스를 quiesce하고, 더 이상 구버전 경로가 새 EARN을 게시할 수 없음을 확인한다. 기존 성공 `requestId` replay는 기존 계약대로 허용하되, 새 EARN을 만들지 않도록 한다.
2. 구버전 경로가 quiesce된 뒤 `stream:ticket-earned` 마지막 ID를 cutover 경계로 기록하고, 그 이전에 승인된 Creator ATTENDANCE 메시지를 drain한다. `cg:ticket-earn`의 last-delivered ID가 경계까지 도달하도록 Consumer가 아직 읽지 않은 메시지도 처리하게 하고, PEL은 Consumer와 PEL 회수 스케줄러가 처리하도록 기다린다. 재처리 실패로 `dead_stream_message`(`stream_type = EARN`)로 이동한 메시지는 관리자 Dead Stream replay로 먼저 반영한다.
3. 경계 이전의 해당 메시지들이 `mission_completion`/Ledger/Balance에 반영됐고, Creator ATTENDANCE에 해당하는 미해결 PEL 및 `UNRESOLVED` EARN Dead Stream이 남아 있지 않은지 확인한다. 메시지를 삭제하거나 기존 보상·완료 이력을 회수하지 않는다.
4. 위 확인이 끝난 뒤에만 공용 ATTENDANCE 경로를 활성화한다. 이 순서를 생략하면 Creator EARN이 Redis 잔액·Stream에는 먼저 승인됐지만 DB 완료 이력은 아직 없는 순간에 공용 조회가 통과해 같은 UTC 날짜에 두 보상이 승인될 수 있다.

공용 응모권을 Creator 이벤트에서 사용하는 SPEND 기능은 이 문서 범위가 아니며 [Issue #243](https://github.com/URECA-Cking/Cking-BE/issues/243)에서 별도로 진행한다.
