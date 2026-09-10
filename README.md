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

## 협업 규칙

브랜치·커밋·PR 규칙은 [CONTRIBUTING.md](https://github.com/URECA-Cking/.github/blob/main/CONTRIBUTING.md)를 참고하세요.
