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

최상위 패키지는 요구사항 명세서의 시스템 1~4로 나눕니다.

```
kr.co.cking
├── common/     공통 응답·예외·설정
├── user/       시스템 1 — 가상 사용자, 미션
├── event/      시스템 2 — 이벤트, 응모, 응모권, 마감
├── drawing/    시스템 3 — Snapshot, 추첨, Winner
└── admin/      시스템 4 — 승인, 결과 공개, 재추첨
```

- 다른 모듈의 Repository·Entity를 직접 쓰지 않습니다. 서비스를 통해 호출합니다.
- 모듈 간 참조는 ID로 합니다. (`@ManyToOne Member` 대신 `Long memberId`)
- Event 상태 변경은 `EventCommandService`로만 합니다.

공통 응답 봉투와 예외 처리는 `common` 패키지에 있습니다. 모듈별 에러 코드는 각 모듈에서
`ErrorCode`를 구현한 enum으로 정의합니다.

## 협업 규칙

브랜치·커밋·PR 규칙은 [CONTRIBUTING.md](https://github.com/URECA-Cking/.github/blob/main/CONTRIBUTING.md)를 참고하세요.
