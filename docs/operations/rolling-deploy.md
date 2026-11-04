# 롤링 배포와 마이그레이션 호환

앱 서버가 여러 대면 배포는 서버를 한 대씩 바꾼다. 그동안 옛 버전 서버와 새 버전 서버가 같은 DB를 함께 쓴다. 새 버전이 기동하며 마이그레이션을 적용한 뒤에도 옛 버전 서버가 요청을 받으므로, 마이그레이션은 바로 전 버전 코드에서도 동작해야 한다.

## 마이그레이션 규칙

| 변경 | 옛 버전 코드 | 배포 |
| --- | --- | --- |
| 테이블 추가, NULL 허용 컬럼 추가, 기본값 있는 컬럼 추가, 인덱스 추가 | 모르는 구조가 생길 뿐이라 영향 없음 | 한 번 |
| 컬럼·테이블 삭제, 이름 변경 | 엔티티가 매핑한 컬럼이 없어 조회·기동 실패 | 두 번 |
| 기본값 없는 NOT NULL 추가 | 그 컬럼을 채우지 않는 INSERT 실패 | 두 번 |
| 타입 변경 | 값 해석이 달라짐 | 두 번 |

두 번으로 나누는 순서:

1. 첫 배포: 코드에서 그 컬럼을 쓰지 않게 바꾼다. 컬럼은 남긴다. 새 코드가 INSERT에서 빼는 NOT NULL 컬럼이면 기본값을 함께 추가한다.
2. 다음 배포: 모든 서버가 첫 배포 버전이 된 뒤 컬럼을 지운다.

함께 도는 버전은 항상 바로 이웃한 두 버전이다. 컬럼을 지우는 시점에는 함께 도는 두 버전 모두 그 컬럼을 쓰지 않는다.

예: `V20__drop_creator_space_tab_settings.sql`은 `creator_space`의 탭 설정 컬럼(`NOT NULL`, 기본값 없음)을 지웠고, 같은 배포에서 엔티티 필드도 지웠다. 서버가 한 대라 문제가 없었지만 여러 대였다면 옛 버전의 `CreatorSpace` 매핑(`home_tab_enabled` 등)이 지워진 컬럼을 조회해 실패했다. 두 번으로 나누면 첫 배포에서 엔티티 필드를 지우고 컬럼에 기본값을 추가하고, 다음 배포에서 컬럼을 지운다.

## CI 검사

PR이 마이그레이션을 바꾸면 `migration-compat` 작업이 `scripts/check-migration-compat.sh`를 실행한다.

1. PR의 마이그레이션까지 적용한 DB를 만든다.
2. 대상 브랜치 코드를 Flyway 없이 그 DB에 붙여 기동한다. 이미 떠 있는 옛 서버는 마이그레이션을 다시 실행하지 않는다.
3. 엔티티가 매핑한 컬럼·테이블이 없거나 타입이 다르면 Hibernate `ddl-auto: validate`가 기동을 실패시키고, 검사가 실패한다.

잡지 못하는 경우는 리뷰에서 본다.

- 기본값 없는 NOT NULL 추가: validate는 컬럼의 존재와 타입만 본다. 옛 코드의 INSERT 실패는 기동으로 드러나지 않는다
- 엔티티를 거치지 않는 SQL(JdbcTemplate·네이티브 쿼리)이 쓰는 컬럼

로컬 실행(compose의 MySQL·Redis를 쓰고, 그 DB에 PR의 마이그레이션이 적용된다):

```bash
scripts/check-migration-compat.sh origin/develop HEAD
```

## Creator 신청서·보완 심사 #799

V98은 기존 INSERT와 호환되도록 신청서 컬럼을 nullable로, 제출 버전은 기본값 0으로 추가한다. 신규 테이블은 구 서버가 사용하지 않는다. 새 코드가 모든 서버에 반영되기 전에는 관리자 보완 요청 API를 호출하지 않는다. 구 서버의 `CreatorApplicationStatus`는 `NEEDS_REVISION`을 모르므로, 혼합 운영 중 이 상태를 기록하면 구 서버가 해당 신청을 읽을 때 실패할 수 있다. FE 신청서·보완·관리자 화면은 BE 전체 교체 후 노출한다. 본문 없는 구 FE 신청은 레거시 PENDING으로 접수되지만 신청서 없이 승인할 수 없다. 새 FE에서 같은 회원이 신청서를 제출하면 기존 PENDING에 연결하므로 별도 관리자 보완 요청은 필요하지 않다. 구 FE 호환 접수 중단은 별도 릴리스에서 수행한다. `NEEDS_REVISION` 데이터가 생긴 뒤 구 코드로 롤백하려면 먼저 이 상태를 해석할 수 있는 호환 버전을 배포해야 한다.

## 여러 서버의 동시 기동

Flyway는 마이그레이션 전에 MySQL 이름 잠금(`GET_LOCK`)을 잡는다(flyway-mysql 12.4.0 `MySQLNamedLockTemplate`). 서버 여러 대가 동시에 기동해도 마이그레이션은 한 서버만 적용하고, 나머지는 잠금이 풀린 뒤 적용할 것이 없음을 확인하고 기동한다.

## 종료

Spring Boot 4.1.1은 `server.shutdown` 기본값이 `graceful`이다. 종료 신호를 받으면 단계 번호가 큰 것부터 차례로 멈추고, 단계마다 최대 30초(`spring.lifecycle.timeout-per-shutdown-phase`) 기다린다.

| 단계 | 단계 번호 | 기다리는 것 |
| --- | --- | --- |
| 웹 요청 마무리 (`WebServerGracefulShutdownLifecycle`) | 2147482623 | 처리 중인 HTTP 요청 |
| 작업 큐 정리 (`ExecutorConfigurationSupport`) | 1073741823 | 구독 인증·퀴즈 생성·퀴즈 채점·댓글 필터 Executor의 남은 작업(`await-termination-seconds: 30`), 추천 기록 Executor(10초) |

- 작업 자체는 종료 신호 뒤에도 각 스레드에서 계속 돌고, 기다리는 확인만 단계 순서를 따른다. 보통은 둘 중 오래 걸리는 쪽만큼 걸리고, 최악은 30초 + 30초 = 60초다
- 같은 단계의 Executor는 동시에 기다린다. Spring Framework 7.0.9에서 Executor 2개에 5초 작업을 하나씩 넣고 종료하면 5,017ms 걸렸다
- 컨테이너 강제 종료까지의 시간은 `docker-compose.dev.yml`·`docker-compose.prod.yml`의 `stop_grace_period: 90s`다. 최악 60초에 연결 종료 등 나머지 종료 단계의 여유 30초를 더했다. 여유는 측정한 값이 아니며, 종료가 잘리는 로그(`Graceful shutdown aborted with one or more requests still active`, `Timed out while waiting for executor … to terminate`)가 보이거나 컨테이너가 강제 종료(종료 코드 137)되면 늘린다
- 값은 최대치라 앱이 일찍 끝나면 그때 종료된다. 늘려서 생기는 비용은 앱이 멈춰 끝나지 않을 때 배포가 그만큼 늦어지는 것이다
