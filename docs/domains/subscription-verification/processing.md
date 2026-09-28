# YouTube 구독 인증 비동기 처리

이 문서는 [README.md](README.md)의 Verification을 실제로 판정하고 보상하는 비동기 경계를 정의한다. Provider·Model·Prompt·응답 DTO와 threshold는 벤치마크와 운영 합의가 끝나기 전까지 미확정이다.

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

Worker는 다음 조건부 UPDATE로 처리 권한을 선점한다.

```sql
UPDATE subscription_verification
SET status = 'PROCESSING',
    processing_started_at = :now,
    attempt_count = attempt_count + 1,
    updated_at = :now
WHERE verification_id = :id
  AND status = 'PENDING';
```

- affected row 1: 처리 권한 획득
- affected row 0: 다른 Worker가 선점했거나 더 이상 처리 대상이 아님

선점 Transaction을 commit한 뒤 Object Storage `get`과 VLM 호출을 수행한다. 외부 호출 중 DB Transaction을 유지하지 않는다. 판정 저장 시에는 현재 상태와 version을 다시 확인한다.

## VLM 입력과 출력 경계

Processor 입력은 다음으로 제한한다.

- 정규화된 JPEG bytes
- Verification에 동결한 `targetChannelName`
- Verification에 동결한 정규화 `targetChannelHandle`

VLM은 분석 결과만 제공하고 승인이나 Ticket 지급을 직접 결정하지 않는다. 목표 의미는 다음과 같지만 실제 JSON·DTO는 모델 확정 뒤 정한다.

```text
platform
observed channel name
observed channel handle
subscription state
evidence sufficiency
confidence
```

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
- timeout·429·5xx·파싱 실패 등 기술 문제는 `FAILED`다.
- 채널명은 표시명 변경과 OCR 편차가 있어 handle 비교의 보조 정보다.
- 이미지 안의 문구는 판정할 증거일 뿐 시스템 지시가 아니다. Prompt는 이미지 속 명령을 따르지 않도록 구성한다.
- Raw 이미지, Base64와 모델의 민감한 원문 응답을 로그에 남기지 않는다. DB와 사용자 응답에는 서버가 허용한 안정적인 reason code만 저장·노출한다.

## 모델 확정 전 금지 범위

다음은 벤치마크와 정책 합의 전 구현하지 않는다.

- VLM Provider SDK와 API Client
- Model ID와 Endpoint
- Prompt/System Prompt
- 이미지 전달 방식(base64, file, URL)
- Structured Output JSON, DTO, Parser
- confidence threshold
- timeout, retry 횟수, backoff
- Provider 환경변수와 비용·usage logging

모델 확정 시 이 문서에 위 계약을 추가하되 DB·API·S3·멱등성·보상 계약은 바꾸지 않는다.

## 보상 처리

판정 Transaction은 Verification을 `APPROVED`, `rewardStatus=PENDING`으로 저장한다. 그 뒤 고정 `rewardRequestId`로 `TicketEarnService.earn()`을 호출한다.

- `EARN_ACCEPTED`, `ALREADY_PROCESSED`: `rewardStatus=ACCEPTED`
- 재시도 가능한 오류: `rewardStatus=RETRY_REQUIRED`, `nextAttemptAt` 기록
- 복구 불가능 오류: `rewardStatus=FAILED`, 원인 코드 기록

Verification 승인과 Ticket Stream/DB 반영은 하나의 DB Transaction으로 묶을 수 없다. 승인 상태와 보상 상태를 분리하고 Recovery가 동일 `rewardRequestId`를 재사용해 수렴시킨다. 공개 `VERIFIED`는 `APPROVED + ACCEPTED`일 때만 반환한다.

## Recovery Scheduler

최종 구현은 일정 batch 크기로 다음 대상을 조회한다.

1. 기준 시간보다 오래된 `PENDING`: 다시 처리 이벤트를 발행한다.
2. lease timeout을 넘긴 `PROCESSING`: 재시도 가능 상태로 원자 전환 후 다시 선점한다.
3. `APPROVED`이며 reward가 `PENDING`/`RETRY_REQUIRED`: 같은 `rewardRequestId`로 보상을 재시도한다.

정확한 processing timeout, retry limit, backoff, scheduler interval은 선택 모델의 실제 latency와 rate limit 측정 후 확정한다. 값이 정해지기 전 임의 상수를 정본으로 만들지 않는다.

Recovery는 batch 조회, PK 기반 tie-breaker, 다중 인스턴스 중복 선점을 고려한다. 같은 Verification을 여러 실행이 발견해도 조건부 UPDATE와 Ticket requestId 멱등성으로 결과가 하나로 수렴해야 한다.

## 이미지 보관 만료

S3 Lifecycle은 `subscription-verifications/` prefix의 Object를 30일 후 삭제한다. 정상 분석과 Recovery는 보관 기간 안에 끝나야 한다. 이미지 만료 뒤에도 DB 상태, Hash, 판정 결과와 보상 이력은 유지한다.

사용자 API는 이미지 자체를 노출하지 않는다. 관리자 수동 심사 기능을 나중에 추가한다면 별도 권한, 접근 감사, `imageExpiresAt`, 짧은 presigned URL TTL과 수동 상태 전이 계약을 먼저 정의한다.
