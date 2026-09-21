# 더미 데이터와 Event·Ticket 운영 검증

## 더미 데이터 시딩

`DummyDataSeeder`는 `local`과 `seed` 프로필이 동시에 활성화될 때만 실행된다. 다른 환경에서 `seed`만 켜는 실수를 막기 위한 조건이다.

- 더미 USER 15명과 더미 Creator 3명을 find-or-create한다.
- 모든 USER·Creator 조합의 `UserTicketBalance`를 없을 때만 초기 5장으로 생성한다.
- Redis 잔액은 새 초기값이 아니라 현재 DB Balance를 기준으로 다시 기록한다. 시딩 재실행이 실제 사용 후 DB 잔액과 Redis 잔액을 어긋나게 만들지 않기 위해서다.
- DB 시딩은 하나의 Transaction이고, Redis의 토큰 기반 락(`seed:dummy-data:lock`, 60초)을 사용해 동시 실행으로 인한 중복 더미 Member 생성을 막는다. 락은 Transaction 완료 뒤 compare-and-delete로만 해제한다.

로컬 시딩은 아래처럼 `local,seed` 프로필을 함께 지정해 실행한다.

```bash
./gradlew bootRun --args='--spring.profiles.active=local,seed'
```

## 운영 검증 경계

- Event 마감: Gate와 cutoff 이후, 해당 Event의 cutoff 이하 PEL·미해결 SPEND Dead Stream이 모두 없어야 CLOSED가 된다. 자세한 절차는 [마감 오케스트레이션](../domains/event/closing.md)을 따른다.
- Stream 장애: Consumer 실패는 PEL에 남고, PEL 회수와 Dead Stream replay의 결과를 확인한다. 자세한 절차는 [Ticket Stream 처리](../domains/stream/README.md)를 따른다.
- 잔액 불일치: 정합성 배치는 감지·경고만 수행한다. Redis를 자동으로 덮어쓰지 않으며, 운영자 확인 뒤에만 COMPENSATE Ledger와 수동 재동기화를 수행한다.

## 검증 실행

구현 계약 회귀는 아래 테스트로 확인한다.

```bash
./gradlew test --tests kr.co.cking.common.seed.DummyDataSeederTest \
  --tests kr.co.cking.event.application.service.EventDrainCheckerTest \
  --tests kr.co.cking.event.scheduler.EventLifecycleSchedulerTest \
  --tests kr.co.cking.stream.presentation.EarnStreamConsumerIntegrationTest \
  --tests kr.co.cking.stream.presentation.SpendStreamConsumerIntegrationTest \
  --tests kr.co.cking.stream.scheduler.EarnStreamPelRecoverySchedulerIntegrationTest \
  --tests kr.co.cking.stream.scheduler.SpendStreamPelRecoverySchedulerIntegrationTest \
  --tests kr.co.cking.stream.application.DeadStreamReplayServiceIntegrationTest \
  --tests kr.co.cking.ticket.scheduler.TicketBalanceReconciliationSchedulerTest
```

현재 저장소에는 DrawingEngine JMH 측정 문서는 있지만, Event·Ticket Stream 부하 시험의 실행 절차와 측정 결과는 별도 문서화돼 있지 않다. 위 테스트는 기능 계약 검증일 뿐 부하 증명은 아니다. 부하 검증을 추가할 때는 시나리오, 입력 규모, Redis·DB 최종 일치 여부, 미해결 PEL·Dead Stream 개수를 함께 기록한다.
