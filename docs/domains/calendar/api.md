# Calendar API

모든 성공·실패 응답은 [공통 API 규약](../../common/api.md)의 응답 봉투를 사용하며, 아래 Response 예시는 `data` 값이다.

## 인증

- `POST`/`PATCH`/`DELETE`/`GET /api/creator/calendar/schedules`(내 일정)는 Bearer Access JWT가 필수다. 호출자는 `@CurrentMemberId`로 식별하며 query `userId`를 받지 않는다.
- `GET /api/creators/{creatorId}/calendar/schedules`(공개 조회)는 인증이 필요 없다.
- 존재하지 않는 Member·Creator·Schedule은 공통 `RESOURCE_NOT_FOUND`로 응답한다.

## 일정 필드

| 필드 | 설명 |
| --- | --- |
| `scheduleType` | `BIRTHDAY`, `FAN_SIGN`, `BROADCAST`, `CONTENT_RELEASE`, `OTHER` 중 하나 |
| `title` | 일정 제목 (최대 100자) |
| `description` | 설명 (최대 1000자, nullable) |
| `startAt`/`endAt` | UTC Instant. `startAt < endAt`이어야 한다 |
| `timeZone` | IANA Time Zone Database 지역명 (최대 50자). 아래 "timeZone 검증" 참고 |
| `location` | 장소 (최대 200자, nullable) |
| `imageUrl`/`externalUrl` | 이미지·외부 링크 URL (각 최대 500자, nullable) |

## timeZone 검증

`ZoneId.getAvailableZoneIds()`에 포함된 지역명만 허용한다. `"+09:00"` 같은 고정 오프셋이나 `"EST"` 같은 구식 축약형은 거부한다(`"UTC"`, `"GMT"`는 레지스트리에 포함된 정식 지역명이라 허용한다). 대소문자를 구분해 일치하는 값만 허용하므로, 검증을 통과한 값은 요청과 동일한 표기로 저장·응답된다.

## POST /api/creator/calendar/schedules

인증된 Creator 본인의 일정을 생성한다. 관리자 승인이 없어 생성 즉시 공개된다.

```json
{
  "scheduleType": "FAN_SIGN",
  "title": "서울 팬사인회",
  "description": "선착순 100명",
  "startAt": "2026-10-10T05:00:00Z",
  "endAt": "2026-10-10T07:00:00Z",
  "timeZone": "Asia/Seoul",
  "location": "서울 성수동",
  "imageUrl": "https://cdn.cking.co.kr/schedule/1.png",
  "externalUrl": "https://example.com/notice"
}
```

성공은 201이며 생성된 일정을 반환한다.

```json
{
  "scheduleId": 100,
  "creatorId": 5,
  "scheduleType": "FAN_SIGN",
  "title": "서울 팬사인회",
  "description": "선착순 100명",
  "startAt": "2026-10-10T05:00:00Z",
  "endAt": "2026-10-10T07:00:00Z",
  "timeZone": "Asia/Seoul",
  "location": "서울 성수동",
  "imageUrl": "https://cdn.cking.co.kr/schedule/1.png",
  "externalUrl": "https://example.com/notice"
}
```

## PATCH /api/creator/calendar/schedules/{scheduleId}

인증된 Creator 본인 소유 일정만 수정할 수 있다. **부분 수정이 아니며, 수정 가능한 모든 필드를 새 값으로 교체한다.** 요청 본문은 생성 API와 동일한 필드를 모두 포함해야 하고, 누락된 필드는 기존 값을 유지하는 것이 아니라 `VALIDATION_FAILED`다. 성공 응답은 생성 API와 같은 형식이다.

## DELETE /api/creator/calendar/schedules/{scheduleId}

인증된 Creator 본인 소유 일정만 삭제할 수 있다. 성공은 204이며 본문이 없다. `CreatorSchedule`은 하드 삭제한다(근거는 [README](README.md#하드-삭제-정책) 참고).

## GET /api/creator/calendar/schedules

인증된 Creator 본인의 일정 중 `from`~`to`와 겹치는 일정을 조회한다.

## GET /api/creators/{creatorId}/calendar/schedules

인증 없이 조회할 수 있다. 특정 Creator의 일정 중 `from`~`to`와 겹치는 일정을 조회한다.

## GET /api/creators/{creatorId}/calendar/schedules/{scheduleId}

인증 없이 조회할 수 있다. `scheduleId`가 해당 `creatorId` 소유가 아니면(다른 Creator 소유거나 존재하지 않으면) `RESOURCE_NOT_FOUND`다.

## 기간 조회 계약(GET 목록 공통)

- Query parameter: `from`, `to` (모두 필수, ISO-8601 UTC Instant, 예: `2026-10-01T00:00:00Z`)
- 조회 조건은 `startAt < to AND endAt > from`이다. 즉 조회 기간과 조금이라도 겹치는 일정을 모두 반환한다.
- 정렬은 `startAt ASC, scheduleId ASC`다.
- 페이지네이션 대신 기간으로 조회하며, `to - from`이 365일을 넘으면 `VALIDATION_FAILED`다.

```json
[
  {
    "scheduleId": 100,
    "creatorId": 5,
    "scheduleType": "FAN_SIGN",
    "title": "서울 팬사인회",
    "description": "선착순 100명",
    "startAt": "2026-10-10T05:00:00Z",
    "endAt": "2026-10-10T07:00:00Z",
    "timeZone": "Asia/Seoul",
    "location": "서울 성수동",
    "imageUrl": "https://cdn.cking.co.kr/schedule/1.png",
    "externalUrl": "https://example.com/notice"
  }
]
```

## 오류

- 없는 Member, Creator 또는 Schedule은 `RESOURCE_NOT_FOUND`다.
- Creator가 아니거나(`creator.member_id` 없음) 다른 Creator의 일정을 수정·삭제하려는 요청은 `FORBIDDEN`이다.
- 유효하지 않은 요청은 `VALIDATION_FAILED`다.
  - 빈 제목, 필드 길이 초과
  - `startAt >= endAt`
  - 지원하지 않는 `scheduleType`
  - `timeZone`이 null/blank이거나 IANA Zone ID가 아니거나 길이 초과
  - `from >= to`이거나 조회 범위가 365일을 초과
  - `scheduleId`·`creatorId`가 0 이하이거나 형식이 올바르지 않음
