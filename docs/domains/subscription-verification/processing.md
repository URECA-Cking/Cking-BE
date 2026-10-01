# YouTube 구독 인증 비동기 처리

이 문서는 [README.md](README.md)의 Verification을 실제로 판정하고 보상하는 비동기 경계를 정의한다. Production VLM은 벤치마크 결과에 따라 Gemini 3.5 Flash-Lite(`gemini-3.5-flash-lite`)로 선정했다. Provider 호출 상세는 Gemini Adapter가 담당하고, Application은 이 문서의 Provider 독립 Port와 판정 계약에만 의존한다.

## Trigger와 Transaction

이미지 제출 HTTP Transaction 안에서 외부 VLM을 호출하지 않는다.

```text
PENDING commit
→ @TransactionalEventListener(AFTER_COMMIT)
→ @Async
→ processor.process(verificationId)
```

S3 ObjectCreated Event를 주 Trigger로 사용하지 않는다. S3 이벤트가 Verification commit보다 먼저 도착할 수 있고 Object Key는 업무 식별자가 아니기 때문이다. 향후 SQS를 도입한다면 S3 이벤트가 아니라 commit된 `verificationId` 업무 이벤트 또는 Outbox를 전달한다.

AFTER_COMMIT 이벤트는 프로세스 종료 시 유실될 수 있으므로 Recovery Scheduler가 최종 전달 보장을 담당한다.

## 원자적 Processing Claim

스케줄러나 AFTER_COMMIT listener는 DB 상태를 먼저 바꾸지 않고 bounded executor에 `verificationId` 작업을 제출한다. Executor에서 실제 실행을 시작한 Worker가 UUID `processingToken`과 lease를 만들어 다음 조건부 UPDATE로 처리 권한을 선점한다.

```sql
UPDATE subscription_verification
SET status = 'PROCESSING',
    processing_token = :processingToken,
    processing_started_at = :now,
    processing_lease_until = :leaseUntil,
    attempt_count = attempt_count + 1,
    updated_at = :now
WHERE verification_id = :id
  AND (
      status = 'PENDING'
      OR (status = 'PROCESSING' AND processing_lease_until <= :now)
  );
```

- affected row 1: 처리 권한 획득
- affected row 0: 다른 Worker가 선점했거나 더 이상 처리 대상이 아님

무중단 배포 중 두 인스턴스가 같은 후보를 조회해 각각 작업을 제출해도 한 Worker만 affected row 1을 얻는다. Executor가 작업을 거절하거나 프로세스가 실행 전에 종료되면 아직 claim하지 않은 행은 `PENDING`으로 남아 다음 Recovery 대상이 된다.

`SubscriptionVerificationProcessingClaimService.claim()`은 `REQUIRES_NEW` Transaction에서 이 조건부 UPDATE와 선점 결과 조회만 수행한다. 호출자 Transaction이 있더라도 선점 Transaction을 독립적으로 commit한 뒤 동결된 이미지 Object Key·대상 채널 정보와 fencing token을 반환한다. Worker는 반환 이후에만 Object Storage와 VLM을 호출한다. 선점 실패는 예외가 아니라 빈 결과이며 해당 작업을 조용히 종료한다.

선점 Transaction을 commit한 뒤 Object Storage `get`과 VLM 호출을 수행한다. 외부 호출 중 DB Transaction을 유지하지 않는다. 판정 저장은 반드시 다음 fencing 조건을 포함한다.

```sql
WHERE verification_id = :id
  AND status = 'PROCESSING'
  AND processing_token = :processingToken
  AND processing_lease_until > :processedAt
```

lease가 만료된 순간부터 기존 Worker는 아직 새 Worker가 재선점하지 않았더라도 결과를 저장할 수 없다. 새 Worker가 새 token으로 재선점한 뒤의 이전 Worker 응답도 저장되지 않는다. `processingToken`은 상태 판정용 임시 fencing token이며 외부 API에 노출하지 않는다.

`SubscriptionVerificationProcessingCompletionService.complete()`도 짧은 `REQUIRES_NEW` Transaction에서 위 fencing 조건을 포함한 조건부 UPDATE를 실행한다. affected row가 0이면 소유권을 잃었거나 이미 종료된 작업이므로 결과를 저장하지 않는다. 이 경우 오래된 Worker는 Ticket 보상도 호출하지 않는다. `APPROVED`는 `approvedGuard=1`, `rewardStatus=PENDING`으로 함께 전환하고, 나머지 결과는 안정적인 reason code를 필수로 저장한다.

Worker는 Object Storage에서 정규화 이미지를 읽고 Vision Port를 호출한 뒤 서버 판정 정책으로 결과를 결정한다. 재시도 가능한 Provider·Object Storage·일시적 처리 오류는 `PROCESSING`과 현재 lease를 유지해 Recovery가 새 token으로 재선점하게 한다. `NON_RETRYABLE` Provider 오류만 현재 fencing token으로 즉시 `FAILED`를 저장한다. 판정 저장 자체가 실패하거나 lease가 만료되어 affected row가 0이면 보상을 시작하지 않는다.

## 전용 Async Executor와 호출 제한

구독 인증은 Spring 기본 Async executor를 사용하지 않고 `subscriptionVerificationExecutor`라는 bounded `ThreadPoolTaskExecutor`를 별도 Bean으로 구성해 `@Async("subscriptionVerificationExecutor")`에서 이름을 명시한다.

- core/max pool size, queue capacity, VLM 동시 호출 수를 설정값으로 분리한다. 기본값은 core/max=2, queue=20, Provider 동시 호출=2이며 `maxPoolSize`와 Provider 동시 호출 수가 일치하지 않으면 애플리케이션을 시작하지 않는다.
- queue는 무제한으로 두지 않는다.
- 거절 정책은 요청 thread에서 VLM을 실행하는 `CallerRunsPolicy`가 아닌 `AbortPolicy`를 사용한다.
- queue 거절은 `subscription_verification.executor.rejected` metric과 경고 로그를 남기고, Claim 전이 전이므로 행과 Object를 `PENDING`으로 유지해 Recovery가 재제출한다. 거절 예외는 AFTER_COMMIT listener와 HTTP 요청까지 전파하지 않는다.
- Recovery batch size는 executor 수용량을 고려해 제한하며 `nextAttemptAt` 이전 행은 제출하지 않는다.
- 애플리케이션 종료 시 30초 bounded graceful shutdown을 사용하되, 미완료 작업의 최종 복구는 DB 상태와 Recovery가 담당한다.

Executor 크기는 rate limiter가 아니다. Worker는 별도 semaphore로 인스턴스별 Provider 동시 호출을 제한한다. 다중 인스턴스의 합산 제한이 필요한 Provider라면 Redis 등 공유 저장소 기반 limiter를 사용하거나 최대 replica 수를 반영해 인스턴스별 한도를 나눈다.

`processingLeaseUntil`은 선택 모델의 connect/read timeout, 한 처리 시도 안의 retry·backoff 최대 시간과 안전 여유보다 길어야 한다. 기본 90초는 현재 connect 2초 + read 30초 + 최대 2회 시도와 1초 backoff보다 길다. Provider rate limit 변경 시 `SUBSCRIPTION_VERIFICATION_EXECUTOR_MAX_POOL_SIZE`와 `SUBSCRIPTION_VERIFICATION_PROVIDER_MAX_CONCURRENT_CALLS`를 함께 같은 값으로 조정한다. 제한 없는 기본값이나 모델 최대 처리 시간보다 짧은 lease를 사용하지 않는다.

## Vision 분석 Port

Application은 `VisionAnalysisPort`를 통해 이미지를 분석한다. Gemini HTTP Client·전용 Request/Response DTO는 Adapter 내부에만 둔다.

```text
VisionAnalysisRequest
- normalizedJpegBytes
- targetChannelName
- targetChannelHandle

VisionAnalysisResult
- platform: YOUTUBE | OTHER | UNKNOWN
- observedChannelName
- observedChannelHandle
- subscriptionState: SUBSCRIBED | NOT_SUBSCRIBED | UNKNOWN
- evidenceSufficient
- confidence: 0.0..1.0
```

Request의 이미지는 공통 이미지 정규화가 만든 JPEG bytes이며 Object Key가 아니다. 대상 채널명과 handle은 Verification 생성 시 동결한 값을 사용한다. Request와 이미지 bytes는 방어적으로 복사하고, 대상 handle은 채널 설정과 같은 규칙으로 정규화한다.

Provider Adapter는 원문 문자열을 위 enum으로 변환한다. timeout·429·5xx 등은 `RETRYABLE`, 인증 실패·잘못된 요청 등은 `NON_RETRYABLE` 기술 오류로 분류한다. `RETRYABLE`은 처리 시도 상한 안에서 backoff 후 재시도하고 상한을 소진하면 `FAILED`로 종료한다. `NON_RETRYABLE`은 같은 입력으로 성공할 수 없으므로 재시도하지 않고 즉시 `FAILED`로 종료한다. 파싱 실패와 Provider 장애를 사용자의 인증 실패인 `REJECTED`로 바꾸지 않는다.

## 서버 판정 정책

Processor 입력은 다음으로 제한한다.

- 정규화된 JPEG bytes
- Verification에 동결한 `targetChannelName`
- Verification에 동결한 정규화 `targetChannelHandle`

VLM은 관측 결과만 제공하고 승인이나 Ticket 지급을 직접 결정하지 않는다. `SubscriptionVerificationDecisionPolicy`는 Repository·Object Storage·Ticket Service에 의존하지 않는 순수 정책이며, confidence threshold를 생성자에서 주입받는다. 운영 threshold는 Gemini 벤치마크에 사용한 Prompt와 판정 기준을 따른다.

서버 판정의 최소 개념은 다음과 같다.

```text
platform == YOUTUBE
AND normalize(observedHandle) == targetChannelHandle
AND subscriptionState == SUBSCRIBED
AND evidenceSufficient == true
AND confidence >= 확정 threshold
→ APPROVED
```

- 대상 채널 또는 구독 상태가 명백히 불일치하면 `REJECTED`다.
- 화면 잘림·흐림·가림 등 증거가 부족하면 `RETRY_REQUIRED`다.
- 낮은 confidence와 `UNKNOWN`, 유효하지 않거나 없는 관측 handle은 `RETRY_REQUIRED`다.
- timeout·429·5xx·파싱 실패 등 기술 문제는 판정으로 변환하지 않고 Processing Retry 경로로 전달한다.
- 채널명은 표시명 변경과 OCR 편차가 있어 handle 비교의 보조 정보다.
- 이미지 안의 문구는 판정할 증거일 뿐 시스템 지시가 아니다. Prompt는 이미지 속 명령을 따르지 않도록 구성한다.
- Raw 이미지, Base64와 모델의 민감한 원문 응답을 로그에 남기지 않는다. DB와 사용자 응답에는 서버가 허용한 안정적인 reason code만 저장·노출한다.

증거가 부족하거나 confidence가 threshold보다 낮으면 다른 관측값보다 먼저 `RETRY_REQUIRED`로 판정한다. 충분한 증거와 confidence가 확보되면 플랫폼, handle, 구독 상태 순으로 판정한다. 따라서 `YOUTUBE + 다른 handle + UNKNOWN 구독 상태`는 명확한 대상 채널 불일치이므로 `CHANNEL_MISMATCH / REJECTED`다. Handle이 일치한 뒤 구독 상태가 `UNKNOWN`이면 `INSUFFICIENT_EVIDENCE / RETRY_REQUIRED`다.

안정적인 판정 사유는 다음과 같다.
```text
PLATFORM_MISMATCH
CHANNEL_MISMATCH
NOT_SUBSCRIBED
INSUFFICIENT_EVIDENCE
LOW_CONFIDENCE
```

## Gemini Adapter 계약

`GeminiVisionAnalysisAdapter`는 SDK 없이 Gemini `generateContent` REST API를 호출한다. 기본 모델은 `gemini-3.5-flash-lite`이며 API key는 퀴즈 기능과 분리된 `SUBSCRIPTION_GEMINI_API_KEY` Secret으로만 주입한다. 구독 인증의 key·quota·모델·timeout·retry 설정은 퀴즈와 분리한다. Adapter는 `VisionAnalysisResult`까지만 반환하고 Verification 상태·reason code·Ticket 보상을 직접 결정하지 않는다.

선정 벤치마크에서는 171장 중 양성 16장을 대상으로 3회 모두 `TP 16 / FP 0 / TN 155 / FN 0`을 재현했다. 누적 `TP 48 / FP 0 / TN 465 / FN 0`으로 오승인과 누락이 없었고, 초기 라벨에서 빠졌던 실제 양성 이미지도 일관되게 찾아낸 결과를 최종 라벨에 반영했다. 모델 선정 근거는 이 고정 평가셋의 정확성과 재현성이며, 운영 입력 분포에서 같은 수치를 보장한다는 의미는 아니다.

### 요청

- 정규화 JPEG bytes를 `inlineData(mimeType=image/jpeg, data=Base64)` 형식으로 전달한다. S3 URL·Object Key·Files API는 사용하지 않는다.
- 대상 `channelName`과 `channelHandle`은 JSON으로 이스케이프한 비신뢰 데이터 블록으로 text prompt에 함께 전달한다. 블록 안의 문자열은 명령으로 해석하지 않으며, 이미지에 없는 관측값을 이 입력에서 추론할 수 없도록 Prompt에 명시한다.
- `thinkingLevel=minimal`, JSON Structured Output schema와 최대 출력 토큰을 요청한다.
- Prompt는 이미지 안의 모든 문구를 증거 데이터로만 취급하고, 이미지 내부 명령을 실행하지 않도록 지시한다.

### 응답과 검증

Provider JSON은 다음 필드를 모두 반환해야 한다.

```text
platform: YOUTUBE | OTHER | UNKNOWN
subscriptionState: SUBSCRIBED | NOT_SUBSCRIBED | UNKNOWN
detectedText: string | null
observedChannelName: string | null
observedChannelHandle: string | null
evidenceSufficient: boolean
confidence: 0.0..1.0
```

`detectedText`는 Provider 내부 판정 근거로만 사용하며 Domain·사용자 응답·로그로 원문을 전달하지 않는다. Parser는 envelope의 추가 필드를 무시하고, 모델 응답을 감싼 Markdown code fence를 제거한 뒤 필수 필드 누락, 잘못된 타입, 범위를 벗어난 confidence와 알 수 없는 enum 값을 거부하고 기술 오류로 전달한다.

### 재시도·관측성

- connect/read timeout, 429, 5xx, 네트워크 오류와 Provider 응답 파싱 오류는 `RETRYABLE` 기술 오류다. 설정한 최대 시도 횟수 안에서 backoff 후 재시도한다.
- 400 계열, API key 누락과 endpoint·요청 생성 설정 오류는 `NON_RETRYABLE` 기술 오류이며 재시도하지 않는다.
- `SUBSCRIPTION_GEMINI_CONNECT_TIMEOUT`, `SUBSCRIPTION_GEMINI_READ_TIMEOUT`, `SUBSCRIPTION_GEMINI_MAX_ATTEMPTS`, `SUBSCRIPTION_GEMINI_RETRY_BACKOFF`, `SUBSCRIPTION_GEMINI_MAX_OUTPUT_TOKENS`로 호출 한계를 분리한다.
- 이미지 bytes, Base64, API key, VLM Raw 응답은 로그에 남기지 않는다. 각 호출 시도마다 model, HTTP status, 실제 latency, usage token 수, 시도 횟수를 한 번만 기록한다.

## 보상 처리

판정 Transaction은 Verification을 `APPROVED`, `rewardStatus=PENDING`으로 저장한다. 그 뒤 Verification 생성 시 동결한 `rewardRequestId`와 `rewardPeriodKey`로 `TicketOnceEarnService.earn()`을 호출한다. 기존 25시간 TTL의 일일 `TicketEarnService`는 사용하지 않는다.

Worker가 선점할 때 반환하는 Claim에는 `memberId`, `creatorId`, `missionId`, `rewardRequestId`, `rewardPeriodKey`를 함께 동결해 전달한다. 보상 Command는 `rewardPolicy=ONCE`, `missionType=YOUTUBE_SUBSCRIPTION`, `missionKey=youtube_subscription:{creatorId}`, `amount=1`로 고정한다. 현재 채널 설정이나 현재 UTC 날짜를 다시 조회·계산하지 않는다.

- `EARN_ACCEPTED`, `ALREADY_PROCESSED`: `rewardStatus=ACCEPTED`
- 그 밖의 결과·예외: `APPROVED + PENDING`을 유지해 Recovery 대상으로 남긴다. SUB-14가 backoff를 예약할 때 `RETRY_REQUIRED`와 `nextAttemptAt`을 사용한다.

Ticket 호출과 `rewardStatus=ACCEPTED` 저장은 별도의 짧은 Transaction 경계다. Ticket이 수락된 뒤 상태 저장이 실패해도 예외를 Worker 밖으로 전파하지 않고, Recovery가 동일 Command를 다시 호출해 `ALREADY_PROCESSED`로 수렴시킨다.

Verification 승인과 Ticket Stream/DB 반영은 하나의 DB Transaction으로 묶을 수 없다. Ticket ONCE durable request와 비만료 Redis idempotency/guard가 Redis Balance 중복 증가를 막고, Recovery가 동일 `rewardRequestId`와 `rewardPeriodKey`를 재사용해 수렴시킨다. Recovery 시각이 UTC 자정을 넘더라도 `periodKey`를 다시 계산하지 않는다. 보상에는 terminal `FAILED`를 두지 않는다. 공개 `VERIFIED`는 `APPROVED + ACCEPTED`일 때만 반환하며 `APPROVED + RETRY_REQUIRED`는 복구 중인 `TEMPORARY_ERROR`다.

## Recovery Scheduler

Scheduler는 기본 1분 주기와 batch 20건으로 다음 대상을 조회한다. 보상 호출 지연이 Event 마감 등 다른 정기 작업을 막지 않도록 `subscriptionVerificationRecoveryTaskScheduler` 전용 단일 스레드에서 실행한다. 설정으로 비활성화할 수 있으며 batch 크기는 전용 Executor의 max pool과 queue 용량 합계를 넘을 수 없다.

1. 기준 시간보다 오래된 `PENDING`: 다시 처리 이벤트를 발행한다.
2. `processingLeaseUntil`이 지난 `PROCESSING`: 새 processing token으로 조건부 재선점한다.
3. `APPROVED`이며 reward가 `PENDING`/`RETRY_REQUIRED`: 같은 `rewardRequestId`와 저장된 `rewardPeriodKey`로 Ticket ONCE 적립을 재시도한다.
4. Ticket ONCE durable request가 `PENDING`: 같은 requestId로 Redis 수락 여부를 재확인하고 `ACCEPTED`로 수렴시킨다.

`PENDING`은 생성 후 1분이 지난 건부터 제출한다. Processing Claim은 기본 최대 3회이며 Claim 조건 자체에 `attemptCount < maxProcessingAttempts`를 포함한다. 마지막 Claim의 lease가 만료되면 조건부 UPDATE로 `FAILED`, `PROCESSING_ATTEMPTS_EXHAUSTED`를 저장하고 `activeGuard`를 해제한다. 이 상태의 사용자 공개 결과는 `TEMPORARY_ERROR`다.

보상 Recovery는 `reward_attempt_count`를 처리 횟수와 분리해 관리한다. 실패하면 `RETRY_REQUIRED`와 `nextAttemptAt`을 조건부 저장하고 기본 1분, 2분, 4분 순서의 지수 backoff를 적용하되 최대 1시간으로 제한한다. 보상에는 시도 상한이나 terminal 실패를 두지 않는다.

Recovery는 생성/갱신 시각과 PK 기반 tie-breaker, 다중 인스턴스 중복 선점을 고려한다. 같은 Verification을 여러 실행이 발견해도 Processing Claim, 시도 상한 종료, 보상 backoff의 조건부 UPDATE와 Ticket ONCE의 DB Business Key·비만료 Redis key로 결과가 하나로 수렴해야 한다. 한 건의 실패는 같은 batch의 다른 건을 중단하지 않는다.

`subscription_verification.recovery.processing.attempted`는 bounded executor에 전달을 시도한 횟수다. 실제 queue 수락 건수가 아니며, queue 거절은 `subscription_verification.executor.rejected`에서 별도로 집계한다.

## 이미지 보관 만료

S3 Lifecycle은 `subscription-verifications/` prefix의 Object를 30일 후 삭제한다. 정상 분석과 Recovery는 보관 기간 안에 끝나야 한다. 이미지 만료 뒤에도 DB 상태, Hash, 판정 결과와 보상 이력은 유지한다.

사용자 API는 이미지 자체를 노출하지 않는다. 관리자 수동 심사 기능을 나중에 추가한다면 별도 권한, 접근 감사, `imageExpiresAt`, 짧은 presigned URL TTL과 수동 상태 전이 계약을 먼저 정의한다.
