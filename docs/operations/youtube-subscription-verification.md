# YouTube 구독 인증 운영 검증

이 문서는 자동 E2E와 실제 Gemini·S3 개발 환경 smoke를 구분해 구독 인증 기능을 활성화하는 절차를 정의한다. 도메인 계약은 [구독 인증 README](../domains/subscription-verification/README.md)와 [비동기 처리 계약](../domains/subscription-verification/processing.md)을 따른다.

## 자동 E2E

`YoutubeSubscriptionVerificationE2EIntegrationTest`는 외부 모델의 비용·장애·비결정성을 제외하기 위해 `VisionAnalysisPort`만 결정론적 테스트 구현으로 교체한다. 나머지는 실제 Spring Bean, MySQL, Redis Lua, EARN Stream Consumer를 사용한다.

검증 범위:

- Creator 채널 최초 설정과 `YOUTUBE_SUBSCRIPTION` 미션 자동 생성
- PNG 입력의 JPEG 정규화·SHA-256·Object Storage 저장
- `PENDING` commit 뒤 AFTER_COMMIT 비동기 처리
- Processing Claim·fencing과 서버 승인 판정
- Ticket ONCE 적립, Redis Balance, Stream Consumer의 Ledger·DB Balance·MissionCompletion 반영
- 사용자 공개 상태 `VERIFIED`
- 동일 `requestId` 재전송 멱등성과 exact-hash 이미지 재사용 감사 기록
- 재시도 가능한 Provider 오류 뒤 lease 만료·Recovery 재선점·최종 보상 수렴

로컬 MySQL·Redis를 실행한 뒤 이 테스트만 실행한다.

```bash
./gradlew test --no-daemon \
  --tests 'kr.co.cking.subscriptionverification.application.YoutubeSubscriptionVerificationE2EIntegrationTest'
```

## 개발 환경 활성화 전 점검

- `CKING_STORAGE_TYPE=s3`, S3 bucket·region과 EC2 IAM Role 권한을 확인한다.
- S3 `subscription-verifications/` prefix에 30일 Lifecycle 만료 규칙을 적용한다.
- Parameter Store의 `SUBSCRIPTION_VERIFICATION_SUBMISSION_ENABLED`는 smoke 전까지 `false`로 유지하고, 검증할 배포에서만 `true`로 변경한다. `false`일 때는 Gemini Key가 없어도 배포·기동할 수 있다.
- 제출 기능을 `true`로 바꾸기 전에 `SUBSCRIPTION_GEMINI_API_KEY`를 Secret으로 주입하고 구독 인증 모델이 `gemini-3.5-flash-lite`인지 확인한다. `true`인데 Key가 없거나 blank이면 애플리케이션 기동이 실패한다.
- Gemini connect/read timeout과 최대 시도 시간이 Processing lease보다 짧은지 확인한다.
- Executor max pool과 Provider 동시 호출 수를 같은 값으로 설정하고 queue를 bounded 상태로 유지한다.
- Recovery Scheduler와 `subscription_verification.executor.rejected` 등 관련 metric·로그를 확인한다.
- 자동 E2E와 관련 통합 테스트가 통과하기 전에는 제출 기능 플래그를 켜지 않는다.

## 실제 Gemini·S3 opt-in smoke

실제 Provider smoke는 API 비용과 외부 상태 때문에 CI에서 자동 실행하지 않는다. 개발 환경에서 승인된 테스트 계정과 민감정보가 없는 전용 스크린샷으로 다음을 한 번 검증한다.

1. 제출 기능 플래그를 끈 상태에서 POST가 `503 VERIFICATION_UNAVAILABLE`인지 확인한다.
2. `SUBSCRIPTION_VERIFICATION_SUBMISSION_ENABLED=true`와 `SUBSCRIPTION_GEMINI_API_KEY`를 함께 주입해 배포한다.
3. Creator 채널을 설정하고 자동 생성된 구독 미션을 조회한다.
4. 구독 상태와 대상 handle이 명확한 테스트 이미지를 한 장 제출한다.
5. 응답의 `verificationId`로 상태를 polling해 `VERIFIED`까지 전이되는지 확인한다.
6. S3 Object가 private이고 key가 `subscription-verifications/{yyyy}/{MM}/{uuid}/image.jpg`인지 확인한다.
7. Ticket Balance, `mission_completion`, `ticket_ledger`가 각각 한 번만 증가했는지 확인한다.
8. 같은 `requestId`를 다시 보내 기존 Verification이 반환되고 보상이 늘지 않는지 확인한다.
9. Provider·Executor 장애를 모의해 `PENDING`/`PROCESSING` Recovery와 metric을 확인한다.

이미지 bytes·Base64·API key·Provider 원문 응답은 로그나 Issue·PR에 남기지 않는다. smoke가 끝난 뒤에만 기능 플래그를 유지하고, 장애 시에는 조회 API는 열어둔 채 신규 제출 플래그만 다시 끈다.
