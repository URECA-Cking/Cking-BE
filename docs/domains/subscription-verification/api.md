# YouTube 구독 인증 API

모든 응답은 [공통 API 규약](../../common/api.md)의 봉투를 사용한다. 식별자는 Java `Long`, JSON number다. Creator 관리 및 Verification API는 Bearer Access JWT가 필수이며 호출자는 `@CurrentMemberId Long memberId`로만 식별한다. Request에서 호출자 `userId`를 받지 않는다.

## 채널 API

### GET /api/creators/{creatorId}/youtube-channel

- 권한: PUBLIC
- 역할: Creator가 설정한 구독 인증 대상 채널을 조회한다.
- `creatorId`: 양수 Long

```json
{
  "creatorId": 5,
  "channelName": "예상치 못한 필름",
  "channelHandle": "@unexpectedfilm",
  "channelUrl": "https://www.youtube.com/@unexpectedfilm",
  "updatedAt": "2026-09-28T03:00:00Z"
}
```

채널 미설정은 `CREATOR_CHANNEL_NOT_CONFIGURED`다.

### GET /api/creator/youtube-channel

- 권한: CREATOR
- 역할: 인증 사용자가 소유한 Creator의 채널 설정을 조회한다.
- Creator 여부는 JWT role이 아니라 `creator.member_id`로 검증한다.
- 응답은 공개 조회와 같다.

### PUT /api/creator/youtube-channel

- 권한: CREATOR
- 역할: 채널을 최초 설정하거나 전체 필드를 새 값으로 교체한다.
- 상태 기반 멱등 명령이며 삭제 API는 제공하지 않는다.

```json
{
  "channelName": "예상치 못한 필름",
  "channelHandle": "@UnexpectedFilm"
}
```

- `channelName`: 필수, trim 후 blank 불가, 최대 100자
- `channelHandle`: 필수, 정규화 후 `@` prefix를 포함하며 최대 100자. 본문은 Unicode 문자·숫자와 `.`, `_`, `-`만 허용
- `channelUrl`은 받지 않고 정규화 handle로 서버가 만든다.
- 최초 설정이면 HTTP 201, 수정 또는 동일 값 재적용이면 HTTP 200을 반환한다.
- 최초 설정 Transaction에서 `YOUTUBE_SUBSCRIPTION` Mission이 없으면 `rewardAmount=1`, 상시 활성으로 생성한다.
- `PENDING`/`PROCESSING` Verification이 존재하면 다른 값으로 수정할 수 없다. 현재 설정과 같은 정규화 값의 PUT 재요청은 기존 설정을 반환한다.

## 이미지 제출

### POST /api/creators/{creatorId}/missions/{missionId}/subscription-verifications

- 권한: USER
- Content-Type: `multipart/form-data`
- 기능 플래그가 꺼져 있으면 파일 처리 전에 `VERIFICATION_UNAVAILABLE`을 반환한다.

Form:

```text
requestId: UUID
image: JPEG 또는 PNG 한 장
```

처리 순서:

1. 기능 플래그를 확인하고 인증 Member, Creator, Mission과 채널 설정을 사전 검증한다.
2. `mission.creatorId == creatorId`, `type == YOUTUBE_SUBSCRIPTION`, 활성 기간을 사전 검증한다.
3. 이미지를 검증·정규화하고 정규화 bytes의 SHA-256과 fingerprint를 계산한다. 이때 DB Transaction을 열지 않는다.
4. 동일 `requestId`를 조회한다. 같은 fingerprint면 기존 결과를 반환하고, 다르면 충돌로 종료한다.
5. 기존 `APPROVED`, 활성 요청, cooldown·일일 제출 수를 사전 검증한다.
6. 서버 UUID Object Key로 Private Object Storage에 정규화 JPEG를 저장한다.
7. 짧은 DB Transaction에서 Creator 단위 잠금을 획득한 뒤 채널·Mission 활성 상태·requestId·승인·활성 요청·제출 제한을 모두 다시 검증한다.
8. 잠금 안에서 읽은 채널명·handle, 서버 생성 `rewardRequestId`, 생성 시각의 UTC 날짜(`yyyy-MM-dd`)인 `rewardPeriodKey`를 동결해 `PENDING` Verification을 저장하고 비동기 처리 이벤트를 발행한다.
9. Transaction이 실패하거나 잠금 후 기존 멱등 요청을 발견하면 이번 요청이 업로드한 Object를 best-effort delete한다. 기존 멱등 요청이면 그 결과를 반환한다.
10. DB commit 후 listener가 비동기 처리를 시작하며 HTTP 202를 반환한다.

이미지 처리나 Object Storage 호출 중에는 DB 잠금·Transaction을 유지하지 않는다. 사전 검증은 불필요한 업로드를 줄이는 최적화이며, 정합성 판정은 7단계의 잠금 후 재검증 결과가 최종이다.

```json
{
  "verificationId": 123,
  "status": "VERIFYING",
  "rewarded": false,
  "submittedAt": "2026-09-28T03:00:00Z"
}
```

같은 `requestId`와 같은 fingerprint 재요청은 기존 Verification을 반환하며 HTTP 200이다. 같은 `requestId`로 다른 요청 내용을 보내면 `IDEMPOTENCY_CONFLICT`다.

## 인증 상태 조회

### GET /api/subscription-verifications/{verificationId}

- 권한: USER
- 본인의 Verification만 조회할 수 있다.
- 다른 사용자의 Verification은 `FORBIDDEN`이다.

```json
{
  "verificationId": 123,
  "status": "VERIFYING",
  "rewarded": false,
  "reasonCode": null,
  "submittedAt": "2026-09-28T03:00:00Z",
  "processedAt": null
}
```

### GET /api/creators/{creatorId}/missions/{missionId}/subscription-verifications/me/latest

- 권한: USER
- 역할: 현재 사용자의 해당 Creator·Mission 최신 Verification을 조회한다.
- 조회 결과가 없으면 `VERIFICATION_NOT_FOUND`다.
- 정렬은 `createdAt DESC, verificationId DESC`다.
- 응답은 단건 상태 조회와 같다.

## 재제출과 응답 상태

| 기존 내부 상태 | 새 requestId 제출 | 공개 상태 |
| --- | --- | --- |
| `PENDING`, `PROCESSING` | 불가 | `VERIFYING` |
| `APPROVED`, reward `NOT_REQUESTED` 또는 `PENDING` | 불가 | `VERIFYING` |
| `APPROVED`, reward `ACCEPTED` | 불가 | `VERIFIED` |
| `APPROVED`, reward `RETRY_REQUIRED` | 불가 | `TEMPORARY_ERROR` |
| `REJECTED` | 가능 | `REJECTED` |
| `RETRY_REQUIRED` | 가능 | `RETRY_REQUIRED` |
| `FAILED` | 가능 | `TEMPORARY_ERROR` |

동일 사용자·Mission은 제출 사이 30초 cooldown과 UTC 하루 최대 5회를 적용한다. 활성 요청이 있거나 이미 승인됐다면 횟수와 무관하게 신규 제출을 차단한다.

## SecurityConfig

다음 경로는 `/api/** permitAll` fallback보다 먼저 `authenticated()`로 등록한다.

```text
/api/creator/youtube-channel
/api/creators/*/missions/*/subscription-verifications
/api/creators/*/missions/*/subscription-verifications/me/latest
/api/subscription-verifications/*
```

공개 채널 조회는 GET `/api/creators/{creatorId}/youtube-channel`만 `permitAll`이다. Controller의 `@CurrentMemberId`만을 유일한 보안 경계로 사용하지 않는다.

## 오류

구현 시 업무 오류는 `SubscriptionVerificationErrorCode`, 공통 오류는 `CommonErrorCode`에 둔다.

| 코드 | HTTP | 의미 |
| --- | ---: | --- |
| `VALIDATION_FAILED` | 400 | 식별자·UUID·multipart·필드 길이·이미지 요청 형식 오류 |
| `INVALID_VERIFICATION_IMAGE` | 400 | 지원하지 않거나 손상된 이미지, 크기·해상도 위반 |
| `UNAUTHORIZED` | 401 | Access JWT 인증 실패 |
| `FORBIDDEN` | 403 | Creator 소유권 없음 또는 타인의 Verification 접근 |
| `RESOURCE_NOT_FOUND` | 404 | Member·Creator·Mission 없음 |
| `VERIFICATION_NOT_FOUND` | 404 | Verification 또는 최신 이력 없음 |
| `CREATOR_CHANNEL_NOT_CONFIGURED` | 409 | 인증 대상 YouTube 채널 미설정 |
| `INVALID_VERIFICATION_MISSION` | 409 | Mission 소유 Creator 불일치 또는 구독 인증 유형 아님 |
| `MISSION_INACTIVE` | 409 | Mission 활성 기간 아님 |
| `VERIFICATION_IN_PROGRESS` | 409 | 처리 중인 인증 존재 |
| `VERIFICATION_ALREADY_APPROVED` | 409 | 이미 ONCE 인증 완료 |
| `IDEMPOTENCY_CONFLICT` | 409 | 동일 requestId의 요청 내용 불일치 |
| `INVALID_STATE` | 409 | 처리 중 Verification이 있는 상태에서 채널 수정 등 허용되지 않는 상태 |
| `CHANNEL_HANDLE_CONFLICT` | 409 | 다른 Creator가 같은 정규화 handle 사용 |
| `VERIFICATION_SUBMISSION_LIMIT_EXCEEDED` | 429 | cooldown 또는 UTC 일일 제출 한도 초과 |
| `VERIFICATION_UNAVAILABLE` | 503 | 제출 기능 플래그 비활성 또는 일시 이용 불가 |
| `SYSTEM_ERROR` | 500 | 예상하지 못한 서버 오류 |

VLM 분석의 timeout·429·5xx는 제출 HTTP 요청의 오류가 아니라 비동기 `FAILED` 및 공개 `TEMPORARY_ERROR`로 기록한다.
