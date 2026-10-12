# YouTube 구독 인증 관측

구독 인증은 기존 제출·VLM 판정·보상 흐름을 바꾸지 않고, 확정된 결과를 Abuse Detection에 관측으로만 전달한다. `cking.abuse.enabled`와 `cking.abuse.youtube-subscription.enabled`가 모두 true일 때만 Redis Feature와 Detection 저장을 수행한다. 기본값은 false다.

## 관측 시점과 범위

| 시점 | result classification | result code |
| --- | --- | --- |
| PENDING 신규 저장 완료 | `NEW_SUCCESS` | `SUBMISSION_CREATED` |
| 같은 requestId 재전송 | `REPLAY` | `SUBMISSION_REPLAY` |
| 검증된 제출의 정책 거절 | `BUSINESS_FAILURE` | 기존 오류 코드 |
| 저장·처리의 예기치 못한 오류 | `SYSTEM_FAILURE` | `SYSTEM_ERROR` |
| 다른 회원의 정규화 이미지 재사용 | `BUSINESS_FAILURE` | `IMAGE_REUSE` |
| VLM 서버 판정 완료 | `NEW_SUCCESS` 또는 `BUSINESS_FAILURE` | `APPROVED` / `REJECTED`, `RETRY_REQUIRED` |

Member·Creator·Mission 확인 전 오류와 형식/이미지 validation 오류는 관측하지 않는다. replay와 system failure는 어떤 Feature도 증가시키지 않는다. 이미지 재사용은 기존 감사 기록이 `DIFFERENT_MEMBER`인 경우만 관측하며, 이미지 hash·Object key·URL·VLM 원문은 Observation, Redis, Evidence, 로그에 넣지 않는다.

`YOUTUBE_SUBMISSION_BURST`는 `SUBMISSION_CREATED`만 센다. requestId rotation은 실제 제출 요청 관측만 사용하며, 이미지 재사용·VLM 판정에서 파생한 관측은 포함하지 않는다. 음성 판정 연속 횟수는 `REJECTED`·`RETRY_REQUIRED`에서 증가하고 실제 VLM `APPROVED`에서만 초기화한다. 각 Rule은 자신을 발생시킨 관측 시점에서만 평가한다.

모든 구독 인증은 `YOUTUBE_SUBSCRIPTION` action을 사용하며 ONCE Business Key는 `MISSION:CREATOR:ONCE:{memberId}:{creatorId}:{missionId}`다. `periodKey`는 null이고 Ticket EARN의 UTC 날짜와 독립적이다.

## Rule과 Evidence

| AbuseType | scope | feature |
| --- | --- | --- |
| `YOUTUBE_SUBMISSION_BURST` | user | 제출 sliding count |
| `YOUTUBE_REQUEST_ID_ROTATION` | business key | distinct requestId count |
| `YOUTUBE_IMAGE_REUSE` | business key | 다른 회원 이미지 재사용 count |
| `YOUTUBE_NEGATIVE_DECISION_BURST` | business key | `REJECTED`/`RETRY_REQUIRED` count 또는 연속 count |

각 Rule은 독립 Detection과 cooldown을 가진다. Evidence는 scope, window, 측정값, threshold만 보관한다. 이미지 재사용 Evidence의 count는 현재 회원·Creator·Mission ONCE key에서 관측된 다른 회원 재사용 횟수이며, 이미지 또는 다른 회원 식별자는 보관하지 않는다.

Redis Lua는 구독 인증 Feature를 기존 Feature와 같은 실행 시각으로 원자 갱신한다. key에는 원문 Business Key 대신 SHA-256 hash만 사용한다. `YOUTUBE_SUBSCRIPTION` 전용 key는 `youtube-submission:{memberId}`, `youtube-image-reuse:{businessKeyHash}`, `youtube-negative-decision:{businessKeyHash}`, `youtube-negative-sequence:{businessKeyHash}`다.

## 설정

```text
cking.abuse.youtube-subscription.enabled
cking.abuse.youtube-subscription.submission-burst.{window,threshold}
cking.abuse.youtube-subscription.request-id-rotation.{window,distinct-threshold}
cking.abuse.youtube-subscription.image-reuse.{window,threshold}
cking.abuse.youtube-subscription.negative-decision-burst.{window,threshold,consecutive-threshold}
```

활성화할 때는 Calibration으로 숫자를 확정한다. 이 기능은 자동 차단·제출 거부·보상 취소를 하지 않는다. Feature Store, Rule, cooldown, Detection 저장 장애는 `SubscriptionVerificationAbuseObserver`와 `AbuseObservationExecutor`가 격리하므로 원래 제출·판정·보상 결과를 바꾸지 않는다.
