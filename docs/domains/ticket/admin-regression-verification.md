# 관리자 API·보정 회귀 검증 (TC-07, #573)

## 검증 경계

관리자 잔액 조회는 Redis와 MySQL의 관측값을 그대로 보여준다. 두 저장소의 조회 시점은 같지 않으며, 조회가 Redis 키를 적재하거나 잔액을 바꾸면 안 된다. 수동 보정은 미반영 메시지가 없고 maintenance lock을 획득한 경우에만 DB 잔액을 Redis에 기록하고 `COMPENSATE` Ledger를 추가한다. 외부 계약은 [Ticket API](api.md)를 따른다.

| 요구사항 | 검증 위치 |
| --- | --- |
| Creator·공용 잔액, 일치·불일치, Redis 키·DB 행 미존재 | `TicketAdminRegressionIntegrationTest`, `TicketAdminServiceTest` |
| Redis 연결·값 형식 오류 | `TicketAdminServiceTest`, `TicketAdminControllerTest` |
| Creator·공용 Ledger 조회와 cursor | `TicketAdminRegressionIntegrationTest`, `TicketAdminControllerTest`, `TicketAdminServiceTest` |
| ADMIN 외 접근 거부 | `TicketAdminRegressionIntegrationTest`, `SecurityConfigTest`, `TicketAdminServiceTest` |
| 미반영 Stream의 PEL·미전달 메시지·Dead Stream 검사 | `UnappliedBalanceMessageCheckerTest` |
| 미반영 메시지에 따른 보정 거부와 락 충돌 | `TicketAdminRegressionIntegrationTest`, `TicketCompensationServiceTest`, `CommonTicketCompensationServiceTest` |
| 정상 보정 후 Redis 잔액·`COMPENSATE` Ledger | `TicketAdminRegressionIntegrationTest`, `TicketCompensationServiceIntegrationTest`, `CommonTicketCompensationServiceIntegrationTest` |
| 적립·차감 중 보정 락과 기존 응모권 경로 | `TicketEarnServiceImplTest`, `CommonTicketMaintenanceIntegrationTest`, `EntrySpendServiceIntegrationTest` 및 Ticket 도메인 전체 테스트 |

`TicketAdminRegressionIntegrationTest`는 실제 Spring Security, MySQL, Redis를 연결한다. Redis 연결 장애는 공유 테스트 Redis를 중단하지 않고 `TicketAdminServiceTest`에서 `RedisConnectionFailureException`으로 검증한다. Stream의 미전달·PEL 분류는 전용 테스트 Stream 키를 사용하는 `UnappliedBalanceMessageCheckerTest`가 검증한다.

## 실행

로컬 MySQL·Redis 컨테이너가 실행 중인 상태에서 다음 명령을 사용한다.

```powershell
.\gradlew.bat test --tests 'kr.co.cking.ticket.*' --tests 'kr.co.cking.stream.application.UnappliedBalanceMessageCheckerTest' --tests 'kr.co.cking.event.application.service.EntrySpendServiceIntegrationTest'
```
