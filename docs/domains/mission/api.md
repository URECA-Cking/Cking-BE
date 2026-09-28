# Mission API

모든 응답은 [공통 API 규약](../../common/api.md)의 봉투를 사용하며, 아래 Response 예시는 `data` 값이다.

## GET /api/creators/{creatorId}/missions

Bearer Access JWT가 필수다. 호출자는 `@CurrentMemberId`로 식별하며 query `userId`를 받지 않는다.
존재하지 않는 사용자나 Creator는 공통 `RESOURCE_NOT_FOUND`로 응답한다.

해당 Creator의 활성 LIKE 미션만 반환한다. 출석은 Creator와 무관한 공용 미션이므로 `GET /api/missions`에서 조회한다. 활성 구간은 기존 `Mission.isActiveAt(now)` 기준인 `activeFrom <= now < activeTo`이며, 시작 또는 종료 시각이 null이면 그 경계는 제한하지 않는다.

각 항목은 `missionId`, `type`, `rewardAmount`, `activeFrom`, `activeTo`, `completedToday`를 포함한다. `activeFrom`과 `activeTo`는 UTC Instant이며 nullable이다.

`completedToday`는 요청 사용자·Creator·미션과 서버 UTC 오늘의 `periodKey`(`yyyy-MM-dd`)가 일치하는 MissionCompletion이 있는지 나타낸다. 이 API는 완료 기록을 생성하거나 변경하지 않는다.

```json
{
  "code": "SUCCESS",
  "data": [
    {
      "missionId": 100,
      "type": "LIKE",
      "rewardAmount": 1,
      "activeFrom": null,
      "activeTo": null,
      "completedToday": false
    }
  ],
  "message": null
}
```

## POST /api/creators/{creatorId}/missions/{missionId}/complete

- 권한: `USER`
- Bearer Access JWT가 필수이며, 호출자는 `@CurrentMemberId`로 식별한다.
- Path Variable `creatorId`: 필수, 양수 Long.
- Path Variable `missionId`: 필수, 양수 Long.
- 신규 완료 지원 유형: `LIKE`.
- 레거시 Creator `ATTENDANCE`는 기존 성공 `requestId` replay만 허용하며, 신규 출석 완료는 공용 API를 사용한다.
- `YOUTUBE_SUBSCRIPTION`은 이미지 인증이 필요한 유형이므로 이 API에서 완료할 수 없다. 제출·상태 조회 계약은 [YouTube 구독 인증 API](../subscription-verification/api.md)를 따른다.

```json
{
  "requestId": "550e8400-e29b-41d4-a716-446655440000"
}
```

- `requestId`: 필수 UUID. 멱등성 키다.

## 응답

성공은 EARN 처리 결과에 따라 HTTP 상태와 `code`가 다르다.

| HTTP | `code` | 의미 |
| --- | --- | --- |
| 202 | `EARN_ACCEPTED` | 이번 요청으로 새로 적립됨 |
| 200 | `ALREADY_PROCESSED` | 동일 `requestId`의 기존 성공 결과(재시도) |

```json
{
  "missionId": 100,
  "rewardAmount": 1,
  "completedAt": "2026-09-16T10:00:00Z"
}
```

`completedAt`은 이 응답을 만든 시각이다 — `ALREADY_PROCESSED` 재시도 응답에서는 최초로 실제 완료된 시각이 아니다(EARN 조회 결과가 원본 완료 시각을 담고 있지 않기 때문).

## 처리 순서

1. `userId`로 Member 존재를 확인한다. 없으면 `RESOURCE_NOT_FOUND`.
2. `creatorId`+`missionId`로 Mission을 조회한다. 없으면 `MISSION_NOT_FOUND`.
3. `YOUTUBE_SUBSCRIPTION`이면 `MISSION_REQUIRES_VERIFICATION`으로 차단한다. 이 경우 Ticket EARN 조회나 적립을 호출하지 않는다.
4. `TicketEarnService#findExisting()`으로 동일 `requestId`의 기존 처리 결과를 조회한다(활성 검증보다 먼저 — 아래 "종료 후 동일 requestId 재시도" 참고).
5. 기존 결과가 없고 Mission 유형이 `LIKE`가 아니면 `MISSION_NOT_FOUND`로 신규 완료를 차단한다. Creator별 ATTENDANCE 신규 완료는 공용 API를 사용한다.
6. `Mission.isActiveAt(now)`로 LIKE 미션의 활성 여부를 검증한다. 비활성이면 `MISSION_INACTIVE`.
7. `TicketEarnService#earn()`을 호출해 Redis Lua가 멱등성·일일 중복 적립 가드·Balance 증가·Stream 발행을 원자적으로 처리한다. 자세한 Redis 계약은 [EARN Lua API](../ticket/lua-api.md)를 따른다.

## 일일 중복 적립 기준

업무일 경계는 서버 UTC 기준이다. `periodKey`는 요청 시각을 UTC로 변환한 `yyyy-MM-dd` 날짜이며, Creator별 LIKE는 크리에이터·미션 유형·사용자·`periodKey` 조합으로 하루 한 번만 적립을 허용한다. 공용 ATTENDANCE는 공용 EARN 가드와 기존 Creator ATTENDANCE 완료 이력 교차 조회를 통해 사용자당 서비스 전체에서 하루 한 번만 적립한다. LIKE 완료 기록은 공용 출석 중복 판정에 포함하지 않는다.

## 종료 후 동일 requestId 재시도

`LIKE` 미션이 종료된 뒤 종료 전에 성공했던 `requestId`가 재전송되면, 활성 검증보다 먼저 수행하는 `findExisting()` 조회가 기존 성공 기록을 찾아 `MISSION_INACTIVE` 대신 원래의 EARN 결과(`ALREADY_PROCESSED`)를 반환한다(Issue #125, FR-P1-018). 레거시 Creator `ATTENDANCE`도 기존 성공 `requestId` replay는 반환하지만 신규 요청은 `MISSION_NOT_FOUND`로 차단한다. `YOUTUBE_SUBSCRIPTION`은 일반 완료 API의 멱등 조회 대상이 아니며 그보다 먼저 `MISSION_REQUIRES_VERIFICATION`으로 차단한다.

## 오류 코드

| 코드 | HTTP | 의미 |
| --- | --- | --- |
| `VALIDATION_FAILED` | 400 | 요청 형식 또는 `creatorId`·`missionId` 범위 오류 |
| `RESOURCE_NOT_FOUND` | 404 | 존재하지 않는 Member |
| `MISSION_NOT_FOUND` | 404 | 존재하지 않는 Mission |
| `MISSION_INACTIVE` | 409 | 활성 기간이 아닌 미션에 대한 신규 요청 |
| `MISSION_REQUIRES_VERIFICATION` | 409 | 별도 이미지 인증이 필요한 미션을 일반 완료 API로 요청함 |
| `DUPLICATE_MISSION` | 409 | 같은 `periodKey`에 이미 완료함 |
| `REQUEST_ID_CONFLICT` | 409 | 동일 `requestId`로 다른 요청 내용이 전달됨 |
| `EARN_PROCESSING_FAILED` | 503 | 응모권 적립 처리 실패(재시도 필요) |
| `EARN_STATUS_UNKNOWN` | 504 | 적립 처리 결과 확인 불가(동일 요청으로 재시도 필요) |
| `BALANCE_MAINTENANCE` | 503 | 수동 보정(`TicketCompensationService.resyncRedisToDb()`) 락이 걸려 있음(잠시 후 동일 요청으로 재시도, issue #172) |

## 공용 미션 API(크리에이터 무관, 이슈 #219)

경로에 `creatorId`가 없는 것만 다르고, 그 외 요청·응답 형식과 처리 순서는 위 크리에이터별 API와 동일하다. 공용 미션은 현재 `ATTENDANCE` 한 유형뿐이며 유형당 하나뿐이다(`uk_common_mission_type`).

### GET /api/missions

Bearer Access JWT가 필수이며 호출자는 `@CurrentMemberId`로 식별한다. 응답 형식은 위 API와 같다(`type`은 `CommonMissionType`).

### 공용 출석과 기존 Creator 출석의 전환 규칙

공용 ATTENDANCE 신규 요청은 공용 requestId replay를 먼저 확인한 뒤, 활성 검증을 통과하면 같은 사용자·같은 UTC `periodKey`의 기존 Creator ATTENDANCE 완료 기록을 조회한다. 기록이 있으면 `DUPLICATE_MISSION`으로 종료하고 `CommonTicketEarnService#earn()`을 호출하지 않는다. 기존 Creator 응모권과 완료 이력은 보존한다.

새 코드가 배포된 뒤에는 Creator ATTENDANCE 신규 요청이 `MissionCompletionService`에서 EARN 전에 차단되므로 안정 상태에서 별도 공유 Redis Guard는 사용하지 않는다. 단, 구버전 인스턴스가 남아 있는 혼합 배포 중에는 기존 EARN의 Redis 성공과 DB Consumer 반영 사이에 교차 중복이 가능하므로, **모든 구버전 인스턴스의 Creator 출석 신규 지급 경로를 차단한 뒤 공용 출석을 활성화해야 한다.**

차단 전에 이미 시작된 구버전 Creator ATTENDANCE 요청이 모두 응답·실패 처리될 때까지 구버전 인스턴스를 quiesce하고, 더 이상 구버전 경로가 새 EARN을 게시할 수 없음을 확인해야 한다. 그 뒤 `stream:ticket-earned` 마지막 ID를 cutover 경계로 기록하고, 경계 이전에 이미 승인된 Creator ATTENDANCE EARN을 전환 전에 정리한다. `cg:ticket-earn`의 last-delivered ID가 경계까지 도달하도록 아직 읽지 않은 메시지와 PEL을 Consumer·PEL 회수 경로로 drain하고, `dead_stream_message`의 경계 이전 미해결 `EARN` 메시지는 관리자 replay로 DB 반영을 완료한 뒤, Creator ATTENDANCE에 해당하는 미해결 PEL·Dead Stream이 없는지 확인한다. 이 확인 없이 공용 출석을 켜면 Redis에서 먼저 승인된 기존 EARN이 아직 `mission_completion`에 반영되지 않아 DB 교차 조회를 통과할 수 있다. 기존 성공 보상·완료 이력은 삭제하거나 회수하지 않는다.

### POST /api/missions/{missionId}/complete

- 권한: `USER`
- Bearer Access JWT가 필수이며, 호출자는 `@CurrentMemberId`로 식별한다.

```json
{
  "requestId": "550e8400-e29b-41d4-a716-446655440000"
}
```

처리 순서는 위와 동일하되 3~5단계가 `CommonTicketEarnService#findExisting()`/`#earn()`을 사용하고, Redis 키에 `creatorId` 축이 없다(`ticket:balance:common:{userId}`, `mission:earn-guard:common:{userId}:{missionType}:{yyyymmdd}`).

**`BALANCE_MAINTENANCE`는 아직 발생하지 않는다** — 공용 응모권의 수동 보정(`TicketCompensationService` 대응) 기능이 아직 없다(이슈 #219 범위 밖, 후속 작업).
