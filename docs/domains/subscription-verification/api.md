# YouTube 구독 인증 API

모든 응답은 [공통 API 규약](../../common/api.md)의 봉투를 사용한다. 식별자는 Java `Long`, JSON number다. Creator 관리 및 Verification API는 Bearer Access JWT가 필수이며 호출자는 `@CurrentMemberId Long memberId`로만 식별한다. Request에서 호출자 `userId`를 받지 않는다.

## 채널 API

### GET /api/creators/{creatorId}/youtube-channel

- 권한: PUBLIC
- 역할: OAuth로 소유권을 확인한 구독 인증 대상 채널만 조회한다.
- `creatorId`: 양수 Long

```json
{
  "creatorId": 5,
  "channelName": "예상치 못한 필름",
  "channelHandle": "@unexpectedfilm",
  "channelUrl": "https://www.youtube.com/channel/UCabcdefghijklmnopqrstuv",
  "youtubeChannelId": "UCabcdefghijklmnopqrstuv",
  "verificationStatus": "VERIFIED",
  "verifiedAt": "2026-11-04T03:00:00Z",
  "updatedAt": "2026-09-28T03:00:00Z"
}
```

채널 미설정은 `CREATOR_CHANNEL_NOT_CONFIGURED`, 미검증 채널은 `CHANNEL_NOT_VERIFIED`다.

### GET /api/creator/youtube-channel

- 권한: CREATOR
- 역할: 인증 사용자가 소유한 Creator의 채널 설정을 조회한다.
- Creator 여부는 JWT role이 아니라 `creator.member_id`로 검증한다.
- 미검증 설정도 조회한다. 응답에는 `youtubeChannelId`·`verificationStatus`·`verifiedAt`이 포함된다.

### PUT /api/creator/youtube-channel

- 권한: CREATOR
- 역할: 채널 표시 정보를 수동으로 입력한다. 이 요청만으로 소유권이 확인되거나 구독 인증 미션이 노출되지는 않는다.
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
- 최초 설정 Transaction에서 `YOUTUBE_SUBSCRIPTION` Mission이 없으면 `rewardAmount=1`, 상시 활성으로 준비한다. 검증 전에는 목록에서 숨기고 신규 이미지 제출도 막는다.
- `PENDING`/`PROCESSING` Verification이 존재하면 다른 값으로 수정할 수 없다. 현재 설정과 같은 정규화 값의 PUT 재요청은 기존 설정을 반환한다.
- 이미 `VERIFIED`인 채널을 다른 값으로 바꾸는 PUT은 `CHANNEL_CONNECTION_REQUIRED`다. 새로운 OAuth 연결을 완료해야 한다.
- 수동 handle은 유일한 소유권 식별자가 아니다. 다른 Creator와 동일 handle을 입력할 수 있지만 인증된 YouTube 채널 ID는 DB에서 유일해야 한다.

### POST /api/creator/youtube-channel/connections

- 권한: CREATOR, Bearer Access JWT 필수. `memberId`에서 Creator 소유권을 확인한다.
- 본문: `{ "channelHandle": "@unexpectedfilm" }` (기존 설정 여부와 무관하게 사용 가능)
- 응답 `data`: `{ "authorizationUrl": "https://accounts.google.com/o/oauth2/v2/auth?..." }`
- state별 이름의 `cking_youtube_connection_<state>` HttpOnly·SameSite=Lax 쿠키를 함께 설정한다. 여러 탭에서 연결을 시작해도 각 콜백이 자신의 쿠키를 사용한다. FE는 `credentials: 'include'`로 호출한 뒤 반환된 URL로 브라우저를 이동시킨다.
- 인가 요청은 `youtube.readonly` scope, PKCE S256, 난수 state를 사용한다. Redis에 시작 `memberId`·`creatorId`·입력 handle·verifier를 10분간 보관한다.
- Google Console에 등록할 개발 콜백은 `https://dev-api.cking.co.kr/login/oauth2/code/youtube`다. `youtube`는 로그인 Provider가 아닌 별도 채널 연결 식별자다.

### GET /login/oauth2/code/youtube

- 권한: PUBLIC (Google 브라우저 리다이렉트). 일반 Google·Kakao 로그인 처리기로 전달하지 않는다.
- `state`와 브라우저 쿠키로 Redis 요청을 원자적으로 한 번 꺼낸다. 만료·재사용·쿠키 불일치·동의 취소는 연결하지 않는다.
- 인가 코드를 토큰으로 교환한 뒤 `channels.list(forHandle=<입력 handle>)`의 단일 채널 ID가 `channels.list(mine=true)`의 소유 채널 ID와 일치할 때만 검증한다. `snippet.customUrl`은 현재 handle로 간주하지 않는다.
- 여러 소유 채널이 있어도 입력 handle에 정확히 대응하는 ID만 사용하며, handle 조회가 0개·다중 결과이거나 ID가 소유 목록에 없으면 실패한다. 대리 운영 권한이 `mine=true`에 나오지 않으면 연결할 수 없다.
- Google 액세스 토큰은 이 조회에만 사용하고 저장하지 않는다. `access_type=online`으로 refresh token도 요청하지 않는다. 외부 호출이 끝난 뒤 짧은 DB 트랜잭션에서 Creator 잠금, 활성 Verification·채널 ID 중복 검사를 거쳐 저장한다.
- 기존 `PENDING`·`PROCESSING` 인증이 있으면 기존 채널과 같은 handle로 첫 연결을 시도해도 `in_progress`다. 처리 완료 뒤 다시 연결한다.
- 잠금 대기 초과 등 예상치 못한 서버 오류도 `unavailable`로 FE에 복귀하며 서버 로그에 오류를 남긴다.
- 완료 후 FE `/my-page?youtubeConnection=<결과>`로 이동한다. 개발 FE는 `https://dev.cking.co.kr/my-page`, 운영 FE는 `https://cking.co.kr/my-page`다. 결과는 `success`(연결 완료), `cancelled`(동의 취소), `invalid`(상태 만료·재사용 등), `mismatch`(handle과 소유 채널 불일치), `conflict`(다른 Creator에 이미 연결), `in_progress`(기존 인증 처리 중), `unavailable`(Provider·Redis 장애) 중 하나이며 토큰·인가 코드·채널 ID는 URL에 넣지 않는다.

### GET /api/creator/youtube-channel/connection

- 권한: CREATOR, Bearer Access JWT 필수.
- 응답 `data`: `{ "verificationStatus": "NOT_CONNECTED|UNVERIFIED|VERIFIED|REVOKED", "channel": <기존 채널 응답 또는 null> }`
- 실패·취소 후에도 저장 상태는 기존 값 그대로다. 재인증은 다시 POST 시작 후 Google 콜백을 완료한다.

## 이미지 제출

### POST /api/creators/{creatorId}/missions/{missionId}/subscription-verifications

- 권한: USER
- Content-Type: `multipart/form-data`
- 기능 플래그가 꺼져 있으면 파일 처리 전에 `VERIFICATION_UNAVAILABLE`을 반환한다.
- 사용자는 `GET /api/creators/{creatorId}/missions`에서 `YOUTUBE_SUBSCRIPTION` 유형과 제출에 필요한 `missionId`를 조회한다.

Form:

```text
requestId: UUID
image: JPEG 또는 PNG 한 장
```

처리 순서:

1. 기능 플래그를 확인하고 인증 Member, Creator, Mission과 `VERIFIED` 채널을 사전 검증한다.
2. `mission.creatorId == creatorId`, `type == YOUTUBE_SUBSCRIPTION`, 활성 기간을 사전 검증한다.
3. 이미지를 검증·정규화하고 정규화 bytes의 SHA-256과 fingerprint를 계산한다. 이때 DB Transaction을 열지 않는다.
4. 동일 `requestId`를 조회한다. 같은 fingerprint면 기존 결과를 반환하고, 다르면 충돌로 종료한다.
5. 기존 `APPROVED`, 활성 요청, cooldown·일일 제출 수를 사전 검증한다.
6. 서버 UUID Object Key로 Private Object Storage에 정규화 JPEG를 저장한다.
7. 짧은 DB Transaction에서 Creator 단위 잠금을 획득한 뒤 채널 `VERIFIED` 상태·Mission 활성 상태·requestId·승인·활성 요청·제출 제한을 모두 다시 검증한다.
8. 잠금 안에서 읽은 채널명·handle, 서버 생성 `rewardRequestId`, 생성 시각의 UTC 날짜(`yyyy-MM-dd`)인 `rewardPeriodKey`를 동결해 `PENDING` Verification을 저장하고 비동기 처리 이벤트를 발행한다.
9. Transaction이 실패하거나 잠금 후 기존 멱등 요청을 발견하면 이번 요청이 업로드한 Object를 best-effort delete한다. 기존 멱등 요청이면 그 결과를 반환한다.
10. DB commit 후 listener가 비동기 처리를 시작하며 HTTP 202를 반환한다.

VLM이 승인 후보를 반환하면 보상 전에 YouTube Data API `channels.list(forHandle)`로 현재 handle이 저장된 검증 채널 ID를 가리키는지 재확인한다. ID가 달라졌거나 handle이 더 이상 존재하지 않으면 `CHANNEL_MISMATCH`로 거절한다. 외부 조회 장애는 처리 lease의 재시도 경로를 따른다. 배포 전 접수된 미검증 채널 요청은 기존 판정 흐름으로 끝낸다.

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

## 이미지 재사용 탐지 운영 조회

### GET /api/admin/subscription-verifications/{verificationId}/image-reuse

- 권한: ADMIN
- 역할: 해당 Verification의 정규화 이미지 SHA-256 exact match 탐지 감사 결과를 조회한다.
- `verificationId`는 양수 Long이며, Verification이 없으면 `VERIFICATION_NOT_FOUND`다.
- 이미지 bytes, Object key, hash는 응답에 포함하지 않는다.
- 이 조회와 탐지 기록은 VLM 판정·상태 전이·Ticket 지급을 변경하지 않는다.

```json
{
  "verificationId": 123,
  "matchedVerificationId": 100,
  "reuseType": "DIFFERENT_MEMBER",
  "detectedAt": "2026-09-29T03:00:00Z"
}
```

`reuseType`은 `FIRST_USE`, `SAME_MEMBER_SAME_CREATOR`, `SAME_MEMBER_DIFFERENT_CREATOR`, `DIFFERENT_MEMBER` 중 하나다. `FIRST_USE`의 `matchedVerificationId`는 `null`이다.

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
/api/creator/youtube-channel/connection
/api/creator/youtube-channel/connections
/api/creators/*/missions/*/subscription-verifications
/api/creators/*/missions/*/subscription-verifications/me/latest
/api/subscription-verifications/*
```

관리자 재사용 탐지 조회는 `/api/admin/**`의 `hasRole("ADMIN")` 정책을 적용한다. 공개 채널 조회는 GET `/api/creators/{creatorId}/youtube-channel`만 `permitAll`이다. Controller의 `@CurrentMemberId`만을 유일한 보안 경계로 사용하지 않는다.

## 오류

구현 시 업무 오류는 `SubscriptionVerificationErrorCode`, 공통 오류는 `CommonErrorCode`에 둔다.

| 코드 | HTTP | 의미 |
| --- | ---: | --- |
| `VALIDATION_FAILED` | 400 | 식별자·UUID·multipart·필드 길이·이미지 요청 형식 오류 |
| `INVALID_VERIFICATION_IMAGE` | 400 | 지원하지 않거나 손상된 이미지, 크기·해상도 위반 |
| `UPLOAD_TOO_LARGE` | 413 | 파일 5MB 또는 요청 6MB 초과 |
| `UNAUTHORIZED` | 401 | Access JWT 인증 실패 |
| `FORBIDDEN` | 403 | Creator 소유권 없음 또는 타인의 Verification 접근 |
| `RESOURCE_NOT_FOUND` | 404 | Member·Creator·Mission 없음 |
| `VERIFICATION_NOT_FOUND` | 404 | Verification 또는 최신 이력 없음 |
| `CREATOR_CHANNEL_NOT_CONFIGURED` | 409 | 인증 대상 YouTube 채널 미설정 |
| `CHANNEL_NOT_VERIFIED` | 409 | 채널 소유권 미확인으로 신규 구독 인증 불가 |
| `CHANNEL_CONNECTION_REQUIRED` | 409 | 검증된 채널을 수동 PUT으로 변경하려고 함 |
| `CHANNEL_ALREADY_CONNECTED` | 409 | 채널 ID가 다른 Creator에 연결됨 |
| `CHANNEL_CONNECTION_INVALID` | 400 | OAuth 상태 만료·재사용·브라우저 불일치 등 |
| `CHANNEL_CONNECTION_MISMATCH` | 400 | 입력 handle과 소유 채널 ID 불일치 |
| `CHANNEL_CONNECTION_UNAVAILABLE` | 503 | Google 또는 Redis 연결을 이용할 수 없음 |
| `INVALID_VERIFICATION_MISSION` | 409 | Mission 소유 Creator 불일치 또는 구독 인증 유형 아님 |
| `MISSION_INACTIVE` | 409 | Mission 활성 기간 아님 |
| `VERIFICATION_IN_PROGRESS` | 409 | 처리 중인 인증 존재 |
| `VERIFICATION_ALREADY_APPROVED` | 409 | 이미 ONCE 인증 완료 |
| `IDEMPOTENCY_CONFLICT` | 409 | 동일 requestId의 요청 내용 불일치 |
| `INVALID_STATE` | 409 | 처리 중 Verification이 있는 상태에서 채널 수정 등 허용되지 않는 상태 |
| `VERIFICATION_SUBMISSION_LIMIT_EXCEEDED` | 429 | cooldown 또는 UTC 일일 제출 한도 초과 |
| `VERIFICATION_UNAVAILABLE` | 503 | 제출 기능 플래그 비활성 또는 일시 이용 불가 |
| `IMAGE_PROCESSING_BUSY` | 503 | 이미지 처리 요청이 몰림. 잠시 후 다시 시도한다 |
| `SYSTEM_ERROR` | 500 | 예상하지 못한 서버 오류 |

VLM 분석의 timeout·429·5xx는 제출 HTTP 요청의 오류가 아니라 비동기 `FAILED` 및 공개 `TEMPORARY_ERROR`로 기록한다.
