# Cking Backend

크리에이터와 팬을 잇는 이벤트 응모·추첨 플랫폼의 백엔드입니다.

## 기술 스택

Java 21 · Spring Boot 4.1.1 · MySQL 8.4 · Redis 7.2 · Flyway · Gradle

## 로컬 실행

MySQL과 Redis를 컨테이너로 띄운 뒤 앱을 실행합니다.

```bash
docker compose up -d
```

```bash
./gradlew bootRun
```

MySQL 3306, Redis 6379를 씁니다. 다른 프로젝트 컨테이너가 이미 그 포트를 쓰고 있으면
`docker ps`로 확인하고 내려주세요.

## 테스트

```bash
./gradlew test
```

DB 연결이 필요한 테스트가 있어 컨테이너가 떠 있어야 통과합니다.

## 코드 구조

최상위 패키지는 도메인 단위로 나눕니다.

```
kr.co.cking
├── common/         공통 응답·예외·설정
├── member/         가상 사용자
├── creator/        Creator, 권한 신청·승인
├── mission/        출석·좋아요 미션
├── ticket/         응모권 Balance·Ledger, 적립·차감
├── event/          Event 생명주기, 응모, 마감
├── snapshot/       공식 Snapshot 생성·검증
├── drawing/        추첨 실행, 재현 검증, 공개
├── winner/         당첨 결과와 운영 상태
├── redraw/         재추첨 요청과 실행 이력
├── notification/   인앱 알림
├── stream/         Redis Stream Consumer, Dead Stream
└── support/        마스킹 등 도메인에 속하지 않는 보조 기능
```

각 도메인 내부는 `presentation` / `application` / `domain` / `repository`로 두고,
주기 실행이 있는 도메인은 `scheduler`를 추가합니다. 그 아래 세분화는 파일이 늘어났을 때
담당자가 나눕니다.

- 다른 도메인의 Entity·Repository로 상태를 직접 변경하지 않습니다. 도메인 간 변경은
  해당 도메인의 서비스를 통해 수행합니다.
- 조회는 필요하면 조회 전용 QueryRepository·Projection을 사용할 수 있습니다.
  서비스를 연쇄 호출해 N+1이 생기는 것보다 JOIN 한 번이 나은 경우가 있습니다.
  다만 다른 도메인의 Entity를 그대로 받지 않고 필요한 필드만 담은 Projection으로 받습니다.
- 도메인 간 참조는 ID로 합니다. (`@ManyToOne Member` 대신 `Long memberId`)
- Event 상태 변경은 `EventCommandService`로만 합니다.

공통 응답 봉투와 예외 처리는 `common` 패키지에 있습니다. 도메인별 에러 코드는 각 도메인에서
`ErrorCode`를 구현한 enum으로 정의합니다.

## 협업 규칙

브랜치·커밋·PR 규칙은 [CONTRIBUTING.md](https://github.com/URECA-Cking/.github/blob/main/CONTRIBUTING.md)를 참고하세요.
