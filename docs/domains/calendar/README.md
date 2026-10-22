# Calendar 도메인

Calendar 도메인은 크리에이터가 직접 등록하는 일정(`creator_schedule`)을 소유한다. 팬사인회·생일·방송·콘텐츠 공개처럼 크리에이터가 알리는 일정을 다루며, 추첨·응모를 다루는 `Event` 도메인과는 완전히 분리돼 있다.

## 문서

- [Calendar API](api.md): 크리에이터 일정 CRUD와 공개 조회 외부 API 계약

## Event와 분리한 이유

기존 `Event`는 `creatorId`와 `startAt`/`endAt`을 가진 "크리에이터가 여는, 기간이 있는 일정"이라는 점에서 겉보기엔 비슷하지만, 승인(`DRAFT`→`PENDING_APPROVAL`→`SCHEDULED`→`OPEN`→...)·응모·마감·Snapshot·Drawing·Winner로 이어지는 무거운 상태 머신을 가진다. 팬사인회·생일 같은 캘린더 일정에는 이 중 어느 것도 필요 없다. `Event`를 확장해 재사용하면 두 개념이 코드·API·DB에서 뒤섞여 "이벤트"라는 용어가 어떤 걸 가리키는지 혼란을 준다. 그래서 별도의 `CreatorSchedule` 엔티티와 `calendar` 패키지로 분리했다.

Creator Space 탭 구성(`[홈] [콘텐츠] [미션] [이벤트] [팬 활동]`)의 `[이벤트]` 탭은 이 `CreatorSchedule`이 아니라 기존 추첨용 `Event`를 가리킨다. 캘린더를 Space에 노출하는 방법(신규 탭 여부 등)은 이 도메인의 범위가 아니며 별도 이슈에서 다룬다.

## 소유 데이터와 경계

- `creator_schedule`: 크리에이터 일정 원본. `creatorId`, `scheduleType`, `title`, `description`, `startAt`/`endAt`, `timeZone`, `location`, `imageUrl`, `externalUrl`을 가진다. 관리자 승인 절차가 없어 생성 즉시 공개된다.
- 인덱스 `idx_creator_schedule_creator_start(creator_id, start_at, schedule_id)`는 `WHERE creator_id = ? AND start_at < :to AND end_at > :from ORDER BY start_at ASC, schedule_id ASC` 조회를 지원한다. `end_at` 조건은 인덱스만으로 걸러지지 않는 residual condition이라 `creator_id` 동등조건·`start_at` 범위조건·정렬까지만 인덱스로 처리한다. 두 범위 조건을 모두 커버하는 단일 B-tree 인덱스는 만들 수 없어 받아들인 트레이드오프다.

## Creator 판정과 소유권

Creator 여부는 JWT Role이 아니라 `creator.member_id` 존재 여부로 판단한다(`CreatorRepository.findByMemberId()`). 호출자 Member 자체가 없으면 `RESOURCE_NOT_FOUND`, Member는 있지만 Creator가 아니면 `FORBIDDEN`이다(`CreatorEventService.requireCreator()`와 같은 원칙). 일정 소유권은 호출자 Creator의 `creatorId`와 일정의 `creatorId`가 같은지로 검증하며, 존재 자체가 없으면 `RESOURCE_NOT_FOUND`, 존재하지만 다른 Creator 소유면 `FORBIDDEN`으로 구분한다(`CreatorEventService.requireOwnership()`과 같은 원칙).

## timeZone 검증과 정규화

`timeZone`은 `ZoneId.of(String)`가 아니라 `ZoneId.getAvailableZoneIds()`(IANA Time Zone Database 지역명 레지스트리)로 먼저 검증한다. `ZoneId.of()`만 쓰면 `"+09:00"`, `"EST"` 같은 고정 오프셋·구식 축약형도 통과시켜 버리기 때문이다(`"UTC"`, `"GMT"`는 레지스트리에도 포함된 정식 지역명이라 그대로 허용한다). 검증을 통과한 값은 `ZoneId#getId()`로 정규화해 저장한다(`ScheduleTimeZones`).

## 하드 삭제 정책

`Event`는 Snapshot·Drawing·Winner로 이어지는 하류 감사 이력이 있어 소프트 삭제(`deletedAt`)를 쓰지만, `CreatorSchedule`에는 그런 하류 이력이 없다. 그래서 소프트 삭제 상태를 추가하지 않고 하드 삭제한다. 향후 취소된 일정을 팬에게 계속 노출해야 하는 요구가 생기면 삭제와 별도로 `CANCELLED` 상태를 추가한다.

## 기간 조회 계약

페이지 번호 대신 `from`/`to`(UTC Instant) 기간으로 조회한다(`ScheduleQueryRange`). `from < to`이고 `to - from`이 365일을 넘지 않아야 하며, 위반하면 `VALIDATION_FAILED`다. 조회 조건은 `startAt < to AND endAt > from`으로, 조회 기간과 조금이라도 겹치는 일정을 모두 반환하고 `startAt ASC, scheduleId ASC`로 정렬한다.

## 개인 캘린더(MemberCalendarEntry)

사용자가 여러 크리에이터의 일정 중 관심 있는 일정만 개별적으로 담아, 크리에이터와 무관하게 통합 조회하는 기능이다(이슈 #319).

- `member_calendar_entry`: `memberId`, `scheduleId`만 가지며 일정 내용을 복사하지 않는다. `CreatorSchedule`을 그대로 참조만 하므로 크리에이터가 일정을 수정하면 개인 캘린더에도 최신 값이 그대로 보인다.
- `uk_member_calendar_entry_member_schedule(member_id, schedule_id)`가 중복 담기를 막는 유니크 제약이자 멱등성의 근거다. 담기(`PUT`)·제거(`DELETE`)는 모두 멱등하다 — 이미 담긴 일정을 다시 담거나 없는 일정을 제거해도 성공으로 처리한다.
- `CreatorSchedule`은 하드 삭제되므로, 참조가 끊긴 행이 남지 않도록 `schedule_id` FK에 `ON DELETE CASCADE`를 둔다. 별도 Service 호출 없이 DB가 삭제를 전파한다.
- 조회는 `member_calendar_entry ⋈ creator_schedule ⋈ creator`를 단일 JOIN Projection으로 수행해 크리에이터 이름을 포함한다(`MemberCalendarEntryRepository.findSchedulesByMemberIdAndRange`). per-entry로 반복 조회하지 않는다.

## 검증 범위(caveat)

- Creator Space에 캘린더를 노출하는 UI 연동(신규 탭 등)은 `CreatorSpace`/`CreatorSpaceTemplate` 소유자와 별도로 조율한다.
