# YouTube 구독 인증 도메인

이 문서는 `YOUTUBE_SUBSCRIPTION` 미션의 목표 업무 계약이다. 현재 구현된 이미지 전처리 범위와 앞으로 구현할 채널 설정, 제출, 비동기 판정, 보상 경계를 함께 정의한다. 외부 API의 상세 형식은 [api.md](api.md), VLM 의존 처리와 복구 계약은 [processing.md](processing.md)를 따른다.

이미지 exact-hash 재사용 탐지 정책과 운영 조회 계약은 [image-reuse.md](image-reuse.md)를 따른다.

## 책임과 경계

구독 인증 도메인은 다음을 소유한다.

- Creator의 YouTube 인증 대상 채널 설정
- 인증 이미지 제출과 정규화된 이미지의 Private Object Storage 저장
- `SubscriptionVerification` 생명주기와 ONCE 인증 불변조건
- 제출 멱등성, 이미지 exact-hash 재사용 탐지, 재제출 제한
- VLM 판정 결과를 서버 정책으로 해석한 최종 인증 상태
- 승인 후 Creator 전용 응모권 보상 요청 및 재시도 상태

다음 책임은 소유하지 않는다.

- Mission 정의 저장: Mission 도메인의 내부 Service를 호출한다.
- Ticket Balance·Ledger·Completion 변경: Ticket 도메인의 ONCE 적립 Service만 호출한다.
- 이미지 저장 구현: 공통 Object Storage port를 사용한다.
- YouTube OAuth, YouTube Data API, 채널 소유권 검증
- 관리자 수동 심사, pHash 기반 유사 이미지 판정

다른 도메인의 Entity·Repository를 직접 변경하지 않는다.

구현은 기존 모듈형 모놀리스 구조를 따른다.

```text
kr.co.cking.subscriptionverification
├── presentation    외부 API와 @CurrentMemberId 경계
├── application     제출 orchestration, 조회, 비동기 처리와 복구
├── domain          Verification·채널 상태와 ErrorCode
└── repository      영속·잠금·Recovery 조회
```

Mission 생성은 `mission.application`의 내부 Service, Ticket 보상은 `TicketOnceEarnService`, 이미지 저장은 공통 Object Storage port를 통해 호출한다.

## 핵심 흐름

```text
Creator가 YouTube 채널 최초 설정
→ YOUTUBE_SUBSCRIPTION Mission이 없으면 같은 Transaction에서 생성

사용자 이미지 제출
→ 동기 검증·정규화·SHA-256
→ 멱등성·재제출 규칙 검증
→ Private Object Storage 저장
→ Verification PENDING 저장·commit
→ 202 Accepted

AFTER_COMMIT 비동기 처리
→ PROCESSING 원자적 선점
→ VLM 분석
→ 서버 정책 판정
→ APPROVED / REJECTED / RETRY_REQUIRED / FAILED

APPROVED
→ 고정 rewardRequestId로 TicketOnceEarnService.earn()
→ Creator 전용 응모권 1장
```

## Creator YouTube 채널

`creator_youtube_channel`은 Creator당 하나다.

| 필드 | 계약 |
| --- | --- |
| `creator_id` | PK/FK, Java `Long` |
| `channel_name` | 표시명, 최대 100자 |
| `channel_handle` | 정규화된 비교 식별자, 최대 100자, UNIQUE |
| `channel_url` | 정규화된 handle로 서버가 만든 URL, 최대 500자 |
| `created_at`, `updated_at` | UTC Instant |

채널명은 동일성의 최종 기준이 아니다. handle은 trim 후 앞의 `@`를 하나만 두고 `Locale.ROOT` 기준으로 소문자화한다. handle 본문은 Unicode 문자·숫자와 `.`, `_`, `-`만 허용하며 공백·URL 구분자는 거부한다. 같은 정규화 handle을 여러 Creator가 등록할 수 없다. MVP는 Creator가 등록한 값을 인증 대상으로 사용할 뿐 실제 채널 소유권은 검증하지 않는다.

Creator 본인만 채널을 생성·수정한다. Creator는 JWT role이 아니므로 인증된 `memberId`로 `creator.member_id`를 조회해 소유권을 확인한다. 삭제 API는 제공하지 않는다.

### 채널 설정과 미션 생성

최초 채널 설정 Transaction에서 Mission 도메인의 내부 Service를 호출한다.

```text
type = YOUTUBE_SUBSCRIPTION
rewardAmount = 1
activeFrom = null
activeTo = null
```

`mission`의 `UNIQUE(creator_id, type)`이 최종 중복 방어선이다. 채널 수정은 기존 Mission을 유지하고 새 Mission을 만들지 않는다. Creator 승인 시 기본 미션 생성은 계속 `LIKE`만 담당하며 `YOUTUBE_SUBSCRIPTION`을 추가하지 않는다.

### 채널 수정과 제출 직렬화

채널 수정과 Verification 신규 제출은 동일한 Creator 또는 채널 행 잠금을 사용한다.

- `PENDING` 또는 `PROCESSING` Verification이 있으면 채널 수정은 `INVALID_STATE`다.
- 정규화 결과가 현재 설정과 같은 PUT 재요청은 실제 수정이 아니므로 기존 설정을 그대로 반환한다.
- 제출 시 채널명과 정규화 handle을 Verification에 복사해 판정 입력을 동결한다.
- Processor와 Recovery는 현재 채널 설정이 아니라 Verification의 동결 값을 사용한다.
- 과거 `APPROVED`가 있어도 채널 수정은 허용하며 보상을 회수하지 않는다.

이미지 CPU 처리, Object Storage 및 VLM 호출 중에는 이 잠금이나 DB Transaction을 유지하지 않는다. 제출 저장의 짧은 Transaction에서만 Creator 단위 잠금을 획득하고 채널·멱등성·활성 요청을 다시 검증한다.

## SubscriptionVerification

주요 필드는 다음과 같다. 실제 DB 구조는 구현 시 추가하는 Flyway migration이 최종 정본이다.

| 필드 | 계약 |
| --- | --- |
| 식별자 | `verification_id`, `member_id`, `creator_id`, `mission_id`: `BIGINT`/`Long` |
| 제출 멱등성 | `request_id` UUID UNIQUE, `request_fingerprint` SHA-256 |
| 채널 동결 | `target_channel_name`, `target_channel_handle` |
| 이미지 | `image_object_key`, 정규화 이미지 기준 `image_sha256`, `normalization_version` |
| 처리 | `status`, `reason_code`, `attempt_count`, `processing_token`, `processing_started_at`, `processing_lease_until`, `next_attempt_at`, `processed_at` |
| 보상 | 서버 생성 `reward_request_id` UUID, 생성 시 UTC 날짜로 동결한 `reward_period_key`, `reward_status`(`NOT_REQUESTED`, `PENDING`, `ACCEPTED`, `RETRY_REQUIRED`), `reward_attempt_count` |
| 동시성 | `active_guard`, `approved_guard`, 낙관적 잠금 `version` |
| 감사 시각 | `created_at`, `updated_at` |

### 내부 상태

```text
PENDING → PROCESSING
PROCESSING → APPROVED | REJECTED | RETRY_REQUIRED | FAILED
```

| 상태 | 의미 |
| --- | --- |
| `PENDING` | 제출 저장 완료, 분석 미시작 |
| `PROCESSING` | 분석 Worker가 원자적으로 선점 |
| `APPROVED` | 서버 판정 정책 충족 |
| `REJECTED` | 분석 가능하지만 대상·구독 조건 불충족 |
| `RETRY_REQUIRED` | 이미지가 흐리거나 정보가 잘려 증거 불충분 |
| `FAILED` | 재시도 가능한 기술 오류가 처리 시도 상한을 소진했거나 비재시도 기술 오류가 발생함 |

사용자 공개 상태는 내부 처리 상세를 숨긴다.

```text
PENDING, PROCESSING                      → VERIFYING
APPROVED + reward NOT_REQUESTED, PENDING → VERIFYING
APPROVED + reward ACCEPTED               → VERIFIED
APPROVED + reward RETRY_REQUIRED         → TEMPORARY_ERROR
REJECTED                                → REJECTED
RETRY_REQUIRED                          → RETRY_REQUIRED
FAILED                                  → TEMPORARY_ERROR
```

## 불변조건과 DB 방어

한 사용자·Creator·Mission에 활성 요청과 승인 기록은 각각 최대 하나다.

```text
UNIQUE(member_id, creator_id, mission_id, active_guard)
UNIQUE(member_id, creator_id, mission_id, approved_guard)
```

- `PENDING`/`PROCESSING`: `active_guard=1`, 그 외 `NULL`
- `APPROVED`: `approved_guard=1`, 그 외 `NULL`
- MySQL UNIQUE가 여러 `NULL`을 허용하는 성질로 과거 종료 이력은 보존한다.
- 처리·보상 Recovery는 상태, lease/다음 시도 시각과 식별자를 포함한 전용 복합 Index를 사용한다.
- 이미지 Hash와 사용자별 이력 조회에도 별도 Index를 둔다.

ONCE 인증 완료의 정본은 `APPROVED` Verification이다. 보상의 영구 멱등성은 아래 Ticket ONCE 적립 계약이 별도로 보장한다. Ticket의 UTC 일일 Guard와 25시간 `idem:mission:{requestId}`를 ONCE 업무키로 재사용하지 않는다.

## 제출 멱등성과 재제출

`requestFingerprint`는 다음 canonical 문자열의 UTF-8 SHA-256 lowercase hex다.

```text
{memberId}:{creatorId}:{missionId}:{imageSha256}
```

`requestId`는 fingerprint에 넣지 않는다.

- 같은 `requestId`와 같은 fingerprint: 기존 Verification 반환, 재업로드·재분석 금지
- 같은 `requestId`와 다른 fingerprint: `IDEMPOTENCY_CONFLICT`
- `PENDING`, `PROCESSING`, `APPROVED`: 새 제출 금지
- `REJECTED`, `RETRY_REQUIRED`, `FAILED`: 새 `requestId`로 재제출 가능
- 같은 사용자·Mission 기준 30초 cooldown, UTC 하루 최대 5회

이미지 SHA-256 exact match는 재사용 여부와 관련 이력을 기록·조회하는 신호다. Hash 일치만으로 즉시 거절하거나 사용자를 차단하지 않는다. pHash/dHash는 MVP 범위가 아니다.

## 이미지와 Object Storage

이미지 검증·정규화·Hash는 HTTP 요청 안에서 동기 처리한다. `SubscriptionImageProcessor`는 [공통 이미지 규칙](../../common/image.md)(JPEG/PNG, 최대 5MB, 최대 20MP, EXIF 방향 보정, metadata 제거, canonical JPEG `JPEG_V1`)에 구독 인증 정책(최소 480×480, 긴 변 2048px 이하)을 적용하고, 잘못된 이미지는 `INVALID_VERIFICATION_IMAGE`로 변환한다. `imageSha256`은 정규화된 bytes 기준이다.

공통 Object Storage port는 호출자가 전체 key를 지정하는 `put/get/delete/presignedGetUrl`을 제공하고 저장소 구현은 bucket 설정만 안다. `put`은 기존 key 덮어쓰기를 허용하지 않는 조건부 쓰기이며 `objectKey`, 크기, `eTag`를 반환한다. `presignedGetUrl`은 만료 시간을 필수로 받고 공통 저장소가 최대 허용 시간을 제한한다. 구독 인증은 다음 key를 UTC `Clock`과 서버 UUID로 생성한다.

```text
subscription-verifications/{yyyy}/{MM}/{uuid}/image.jpg
```

Content-Type은 `image/jpeg`이며 Private 저장과 Block Public Access를 전제로 한다. S3 upload 실패 시 Verification을 만들지 않는다. Upload 후 DB 저장 실패 시 object를 best-effort delete하고 삭제 실패를 운영 로그에 남긴다.

Object는 S3 Lifecycle로 30일 후 삭제한다. DB 상태·Hash·판정·보상 기록은 계속 보존한다. 사용자 API는 이미지 URL을 반환하지 않는다. 관리자 이미지 조회·수동 심사는 MVP 범위가 아니며 presigned URL도 현재 도메인 흐름에서는 사용하지 않는다.

## 보상 경계

Verification 생성 시 `rewardRequestId`와 `rewardPeriodKey`를 서버가 한 번 확정해 함께 저장한다. `rewardPeriodKey`는 Verification 생성 시각을 UTC로 변환한 `yyyy-MM-dd`이며, 기존 Stream·`mission_completion` 포맷 호환용일 뿐 ONCE 업무키는 아니다. `APPROVED`가 되면 `rewardStatus=PENDING`으로 두고 `TicketOnceEarnService.earn()`에 Mission의 `rewardAmount=1`, 저장된 `rewardRequestId`, 저장된 `rewardPeriodKey`를 전달한다.

보상 실행 시각이나 Recovery 시각으로 `periodKey`를 다시 계산하지 않는다. 자정을 넘어 재시도하더라도 최초에 저장한 값을 그대로 사용한다. 따라서 같은 `rewardRequestId`의 durable request payload fingerprint와 EARN Stream payload는 모든 시도에서 동일해야 한다.

### Ticket ONCE 적립의 영구 멱등성

기존 `TicketEarnService`의 idempotency와 일일 Guard는 모두 25시간 TTL이므로 구독 보상에 사용하지 않는다. Ticket 도메인은 별도 `ticket_once_earn_request` durable request와 ONCE Lua 경로를 제공한다.

```text
UNIQUE(request_id)
UNIQUE(member_id, creator_id, mission_id)
status = PENDING | ACCEPTED
payload_fingerprint
```

1. 같은 `rewardRequestId`의 durable request를 조회·생성하고 `memberId`, `creatorId`, `missionId`, `missionType`, `amount`의 payload 일치를 검증한다. 저장된 `rewardPeriodKey`는 Stream 호환을 위해 재사용하지만 서버 파생값이므로 fingerprint에는 포함하지 않는다.
2. `(memberId, creatorId, missionId)` UNIQUE로 다른 requestId의 평생 중복 보상을 차단한다.
3. 신규/PENDING이면 ONCE Lua를 실행한다. Lua는 `idem:mission-once:{requestId}`와 `mission:earn-guard:{userId}:{missionType}:{creatorId}:once`를 TTL 없이 선점하고 Balance 증가·기존 EARN Stream 발행을 원자 처리한다.
4. Redis 수락 결과를 별도 짧은 Transaction에서 DB durable request의 `ACCEPTED`로 기록한다.
5. ONCE Redis key는 TTL을 설정하거나 성공 후 삭제하지 않는다. DB 조회를 이미 통과한 동시 실행이 뒤늦게 도착해도 영구 key가 재증가를 막는다.
6. Redis 수락 후 DB 상태 갱신 전에 프로세스가 종료돼도 같은 requestId의 비만료 Redis key가 재증가를 막고, 재호출이 DB를 `ACCEPTED`로 수렴시킨다.

기존 EARN Stream Consumer와 `mission_completion`·Ledger·Balance DB 반영은 재사용한다. DB `request_id` UNIQUE는 Consumer 멱등성이고, Redis Balance 중복 증가를 막는 위 ONCE 계약을 대체하지 않는다. 기존 일일 `TicketEarnService`와 `ticket-earn.lua`의 TTL 계약은 변경하지 않는다.

### Verification 보상 상태

```text
NOT_REQUESTED → PENDING
PENDING → ACCEPTED | RETRY_REQUIRED
RETRY_REQUIRED → ACCEPTED | RETRY_REQUIRED
```

- `EARN_ACCEPTED`, `ALREADY_PROCESSED`: `ACCEPTED`
- timeout, Redis 장애, 결과 불명 등: `RETRY_REQUIRED`

보상에는 terminal `FAILED`를 두지 않는다. 승인된 사용자가 보상 없이 영구 종료되지 않도록 Recovery가 같은 `rewardRequestId`와 `rewardPeriodKey`로 `ACCEPTED`까지 재시도한다. 반복 실패는 지수 backoff와 운영 알림을 적용하되 상태는 복구 가능하게 유지한다. Verification 코드가 `mission_completion`, `ticket_ledger`, `user_ticket_balance`를 직접 변경해서는 안 된다.

Recovery 기본값은 1분 주기, batch 20건, 오래된 `PENDING` 유예 1분, Processing Claim 최대 3회다. 세 번째 Claim의 lease까지 만료되면 `PROCESSING_ATTEMPTS_EXHAUSTED`로 `FAILED` 처리하고 사용자에게는 `TEMPORARY_ERROR`를 노출한다. 보상 실패는 1분부터 최대 1시간까지 지수 backoff하며 `reward_attempt_count`를 별도로 증가시킨다.

## 기능 플래그

Processor와 Recovery가 완성되기 전에는 제출만 닫는다.

```yaml
cking:
  verification:
    youtube-subscription:
      submission-enabled: ${SUBSCRIPTION_VERIFICATION_SUBMISSION_ENABLED:false}
```

설정 누락 시 기본값은 `false`다. POST는 이미지 처리·S3 저장 전에 `503 VERIFICATION_UNAVAILABLE`로 종료하고 GET 상태 조회는 항상 허용한다. 플래그가 꺼진 환경(로컬·테스트)은 `SUBSCRIPTION_GEMINI_API_KEY` 없이도 기동할 수 있다. 켜진 환경은 Gemini Key가 반드시 필요하며, 누락되거나 blank이면 애플리케이션이 기동에 실패한다.

개발 서버는 플래그를 `application-dev.yml`에서 관리하고(기본 `false`), Gemini Key는 기능 상태와 관계없이 Parameter Store에서 항상 주입한다. VLM Client, 판정 정책, Processor, Ticket ONCE 적립, Recovery, Reward retry, E2E 및 운영 환경변수 검증이 끝난 뒤 `application-dev.yml`의 값을 `true`로 바꾸는 PR로 활성화한다.

## 구현 금지 사항

- 일반 Mission `/complete`로 `YOUTUBE_SUBSCRIPTION`을 완료하지 않는다.
- Verification 승인 전에 Ticket을 지급하지 않는다.
- Ticket Balance·Ledger·MissionCompletion을 직접 변경하지 않는다.
- 기존 25시간 TTL 일일 EARN 경로를 구독 보상 Retry에 사용하지 않는다.
- 외부 호출 중 DB Transaction을 유지하지 않는다.
- 재시도마다 새 `rewardRequestId`를 만들지 않는다.
- 재시도마다 `rewardPeriodKey`를 현재 날짜로 다시 계산하지 않는다.
- 이미지를 public으로 저장하거나 bytes/Base64를 로그에 남기지 않는다.
- Application의 Vision Port·판정 정책에 Gemini SDK·전용 DTO를 노출하지 않는다.

## 구현 순서

모델 선정 전에는 다음 순서로 구현한다.

1. Creator 채널·Verification Flyway migration과 Entity·Repository·상태 enum
2. Mission 멱등 provisioning 내부 Service
3. 채널 조회·설정과 Creator 단위 잠금
4. 공통 Object Storage port 연동
5. Ticket ONCE durable request·비만료 Redis guard와 `TicketOnceEarnService`
6. 제출 orchestration, 멱등성·guard·제출 제한과 upload 보상 삭제
7. 본인 상태·최신 상태 조회 API와 Security matcher
8. AFTER_COMMIT 이벤트 경계와 Recovery용 조회 계약

선정 모델은 Gemini 3.5 Flash-Lite(`gemini-3.5-flash-lite`)다. [processing.md](processing.md)의 Provider 독립 Vision Port와 서버 판정 계약을 기준으로 Gemini Adapter, Processing Claim, Reward retry, Recovery Scheduler와 E2E를 구현한다. 단위·Repository·Controller 테스트 외에 동일 requestId 및 동시 제출, 채널 수정과 제출 경쟁, DB 저장 실패 후 Object 삭제, 중복 보상 방지를 통합 테스트한다.
