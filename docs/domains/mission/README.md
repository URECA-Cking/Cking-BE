# Mission 도메인

Mission 도메인은 크리에이터별 미션 정의(`mission`)와 완료 판정을 담당한다. 응모권 적립 자체는 Ticket 도메인(`TicketEarnService`)의 책임이며, 이 도메인은 완료 여부만 판정하고 EARN을 요청한다.

## 문서

- [미션 완료 API](api.md): `POST .../missions/{missionId}/complete` 계약

## 소유 데이터와 경계

- `mission`: 크리에이터당 유형별 미션 정의(`uk_mission_creator_type`). `activeFrom`/`activeTo`, `rewardAmount`를 가진다.
- `mission_completion`: 완료 이력. 이 도메인이 직접 쓰지 않는다 — [Ticket EARN Lua API](../ticket/lua-api.md)가 처리한 EARN 결과를 EARN Stream Consumer가 비동기로 반영한다(취합v1.5.4 §4.5). `MissionCompletionService`가 `mission_completion`을 먼저 써버리면 Consumer가 재전달로 오판해 Ledger·Balance 반영을 건너뛰므로, 이 서비스는 Redis/Stream 계층 밖의 어떤 영속 상태도 직접 만들지 않는다.

## 활성 판정

`Mission.isActiveAt(Instant now)`는 `activeFrom <= now < activeTo`를 계약으로 한다 — `Event`의 `startAt <= now < endAt`(취합v1.5.4 §2.5)와 동일하게 `activeTo`는 배제(exclusive)다. `activeFrom`/`activeTo`가 비어 있으면 상시 활성이다.

## 종료 후 재시도(Issue #125)

미션이 종료된 뒤에도, 종료 전에 성공했던 `requestId`가 재전송되면 활성 검증보다 먼저 `TicketEarnService#findExisting()`으로 기존 요청을 조회한다. 기존 성공 기록이 있으면 `MISSION_INACTIVE`가 아니라 원래의 EARN 결과를 반환해야 한다(FR-P1-018 멱등 재요청 계약). 조회 결과가 `NOT_FOUND`(진짜 신규 요청)일 때만 활성 검증 후 `earn()`을 호출한다. 자세한 순서는 [api.md](api.md#종료-후-동일-requestid-재시도)를 참고한다.

## 중복 방지 키의 레이어(임의 통합 금지)

이름이 비슷한 키가 서로 다른 목적으로 여러 개 존재한다. RTM이 각각을 별도 레이어로 명시하고 있어(FR-P1-015 비고), 임의로 하나로 합치지 않는다.

1. **RTM 판정 개념** `userId+creatorId+periodKey`: "이 사용자가 이 크리에이터에게서 오늘 이미 보상을 받았는가"를 가리키는 개념적 기준(FR-P1-015)일 뿐, Mission 코드가 이 조합으로 직접 조회·판정하지는 않는다.
2. **DB Business Key** `userId+creatorId+missionId+periodKey` (FR-P1-015/FR-P2-008): `mission_completion`의 `uk_completion_business` UNIQUE 제약(`member_id, creator_id, mission_id, period_key`)으로 구현된 최종 안전망이다. Mission이 아니라 EARN Stream Consumer가 `mission_completion`을 삽입할 때 DB가 강제한다.
3. **EARN replay fingerprint** `userId+creatorId+missionType+missionId+missionKey+amount` — **`periodKey`는 포함하지 않는다.** `TicketEarnServiceImpl#computeFingerprint()`가 계산하며, 동일 `requestId` 재요청이 내용까지 같은지(`REQUEST_ID_CONFLICT` 판정)에 쓰인다. `periodKey`를 뺀 이유는 자정을 넘겨 재시도해도 같은 요청으로 인정하기 위해서다(이전에 `missionKey`에 `periodKey`가 섞여 들어가 이 의도가 깨졌던 버그를 수정한 적이 있다). **2번(DB Business Key)과 혼동하지 않는다** — 필드 구성도 다르고(`missionType`/`missionKey`/`amount` 포함, `periodKey` 제외) 목적도 다르다(2번은 하루 중복 방지, 3번은 요청 내용 일치 확인).
4. **Redis EARN Guard 키** `mission:earn-guard:{userId}:{missionType}:{creatorId}:{yyyymmdd}` (FR-P2-006): `missionId`가 아니라 `missionType`을 쓴다. 좋아요는 `contentId`를 판정 기준에 포함하지 않으므로("어떤 콘텐츠인지"가 아니라 "좋아요 활동을 오늘 했는지") 이 키 자체가 콘텐츠 단위가 아니라 유형 단위로 설계돼 있다. 1차 MVP는 크리에이터당 유형별 미션이 하나뿐(`uk_mission_creator_type`)이라 결과적으로 `missionId` 기준과 같지만, 여러 미션이 같은 유형을 가질 수 있게 되면 달라질 수 있는 별개 개념이다.

**`MissionCompletionService`는 이 중 어느 것으로도 중복을 직접 판정하지 않는다.** `Mission.isActiveAt(now)`로 활성 여부만 확인하고, 중복 판정은 전부 Ticket EARN(3·4번, Redis 기반 실시간 차단)과 DB 제약(2번, Consumer 삽입 시점의 최종 안전망)에 위임한다 — `EarnCommand`에 필요한 값을 실어 보낼 뿐, Guard 키나 fingerprint를 직접 계산하지 않는다.

## 미션 유형에 무관한 공통 골격

`MissionController`/`MissionCompletionService`는 `MissionType`(`ATTENDANCE`, `LIKE`)을 분기하지 않는다. `mission.getType()`을 그대로 `EarnCommand`에 실어 보낼 뿐이라, 좋아요 미션도 출석과 같은 코드 경로로 판정·지급된다. 좋아요 취소는 별도 API가 없다 — Mock 검증(버튼 클릭 = 완료, FR-P1-011) 방식이라 취소 시 서버에 알리지 않으며, 이미 지급된 응모권은 회수하지 않는다(FR-P1-012). 같은 날 재좋아요는 위 4번 Guard 키가 자연히 차단한다(FR-P1-013).

### 검증 범위(caveat)

- **`rewardAmount`는 코드가 강제하지 않는다.** `Mission` 생성자는 `rewardAmount > 0`만 검증하고 값을 1로 고정하지 않는다([Mission.java](../../../src/main/java/kr/co/cking/mission/Mission.java)). "출석/좋아요 완료 시 응모권 1장"(FR-P1-007/010)은 Mission row를 만드는 쪽(Creator)이 1로 설정한다는 **데이터 규약**이며, 이를 만드는 프로덕션 경로(Admin/Creator API)가 아직 없어 코드로 검증할 방법도 없다. ATTENDANCE도 동일하다 — 좋아요 미션만의 문제가 아니다.
- **LIKE의 중복 적립 차단은 유닛 테스트로만 확인했다.** `MissionCompletionServiceTest`의 좋아요 관련 테스트는 `TicketEarnService`를 mock으로 대체해, "Redis가 `DUPLICATE_MISSION`을 반환하면 Mission이 이를 올바르게 변환하는가"만 검증한다. "실제 Redis 위에서 좋아요 두 번째 요청이 진짜로 차단되는가"를 확인하는 실제 Redis/MySQL 기반 통합 테스트(`MissionCompletionConcurrencyIntegrationTest`)는 현재 `ATTENDANCE`로만 작성돼 있고 LIKE 버전은 없다. Guard 키·Lua 스크립트가 미션 타입을 분기하지 않아 결과가 같을 것으로 보이지만, LIKE로 직접 실행해 확인한 적은 없다.
