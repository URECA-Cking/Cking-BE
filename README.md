# Cking Backend

크리에이터와 팬을 잇는 이벤트 응모·추첨 플랫폼 **Cking**의 백엔드입니다.

**API 문서 (Swagger UI)**

| 환경 | 주소 |
|---|---|
| 로컬 | http://localhost:8080/swagger-ui/index.html |
| 개발 서버 | http://{개발 서버 주소}:8080/swagger-ui/index.html |

## 목차

* [기술 스택](#기술-스택)
* [1. 사전 준비](#1-사전-준비)
* [2. 처음 프로젝트를 받는 경우](#2-처음-프로젝트를-받는-경우)
* [3. 이미 프로젝트를 받은 경우](#3-이미-프로젝트를-받은-경우)
* [4. Docker 실행](#4-docker-실행)
* [5. 애플리케이션 실행](#5-애플리케이션-실행)
* [6. 테스트 실행](#6-테스트-실행)
* [7. Docker 종료](#7-docker-종료)
* [8. 작업 흐름](#8-작업-흐름)
* [9. 코드 구조](#9-코드-구조)

---

## 기술 스택

* Java 21
* Spring Boot 4.1.1
* Gradle
* MySQL 8.4
* Redis 7.2
* Flyway
* Docker Compose

---

# 1. 사전 준비

로컬 실행 전에 아래 프로그램이 필요합니다.

* Git
* JDK 21
* Docker Desktop

설치 확인:

### Windows

```powershell
git --version
java -version
docker --version
docker compose version
```

### macOS

```bash
git --version
java -version
docker --version
docker compose version
```

Java 버전은 **21**이어야 합니다.

---

# 2. 처음 프로젝트를 받는 경우

## Windows

```powershell
git clone https://github.com/URECA-Cking/Cking-BE.git
cd Cking-BE

git switch develop

docker compose up -d

.\gradlew.bat bootRun
```

## macOS

```bash
git clone https://github.com/URECA-Cking/Cking-BE.git
cd Cking-BE

git switch develop

docker compose up -d

./gradlew bootRun
```

Docker Compose 실행 시 아래 환경이 자동으로 생성됩니다.

| 항목         | 값         |
| ---------- | --------- |
| MySQL Host | localhost |
| MySQL Port | 3306      |
| Database   | cking     |
| Username   | cking     |
| Password   | cking     |
| Redis Host | localhost |
| Redis Port | 6379      |

별도의 MySQL Database 생성이나 Redis 설정은 필요하지 않습니다.

---

# 3. 이미 프로젝트를 받은 경우

작업 시작 전에 최신 `develop`을 반영합니다.

```bash
git switch develop
git pull origin develop
```

이후 작업 브랜치를 생성합니다.

```bash
git switch -c feat/이슈번호-작업내용
```

예:

```bash
git switch -c feat/12-event-create
```

브랜치 이름 규칙은 [CONTRIBUTING.md](https://github.com/URECA-Cking/.github/blob/main/CONTRIBUTING.md)를 참고합니다.

---

# 4. Docker 실행

MySQL과 Redis를 실행합니다.

### Windows / macOS 공통

```bash
docker compose up -d
```

실행 상태 확인:

```bash
docker compose ps
```

또는:

```bash
docker ps
```

정상적으로 실행되면 다음 컨테이너가 표시됩니다.

```text
cking-mysql
cking-redis
```

MySQL은 `3306`, Redis는 `6379` 포트를 사용합니다.

이미 다른 프로그램이나 Docker Container가 해당 포트를 사용 중이면 실행에 실패할 수 있습니다.

---

# 5. 애플리케이션 실행

## Windows

```powershell
.\gradlew.bat bootRun
```

## macOS

```bash
./gradlew bootRun
```

실행을 종료하려면:

```text
Ctrl + C
```

---

# 6. 테스트 실행

MySQL과 Redis Container가 실행 중이어야 합니다.

## Windows

```powershell
.\gradlew.bat test
```

## macOS

```bash
./gradlew test
```

테스트 태스크는 `src/test/resources/application-test.yml`을 추가로 읽어 HikariCP의 최대 연결 수를 3으로 제한합니다. 이 설정은 테스트 실행에만 적용되며, 로컬·개발·운영 애플리케이션의 연결 풀 설정에는 영향을 주지 않습니다.

---

# 7. Docker 종료

Container만 종료:

```bash
docker compose down
```

DB 데이터까지 완전히 삭제:

```bash
docker compose down -v
```

> `-v`를 사용하면 로컬 MySQL 데이터가 모두 삭제됩니다.

---

# 8. 작업 흐름

기본 개발 흐름은 다음과 같습니다.

```text
Issue 생성
↓
develop 최신화
↓
작업 브랜치 생성
↓
개발
↓
Commit
↓
Push
↓
Pull Request → develop
↓
Review
↓
Squash and Merge
```

작업 완료 후:

```bash
git add .
git commit -m "feat: 작업 내용"
git push -u origin 현재브랜치명
```

PR의 Base Branch는 `develop`으로 지정합니다.

`main`, `develop` 브랜치에는 직접 Push하지 않습니다.

자세한 브랜치·커밋·PR 규칙은 [CONTRIBUTING.md](https://github.com/URECA-Cking/.github/blob/main/CONTRIBUTING.md)를 참고합니다.

---

# 9. 코드 구조

최상위 패키지는 도메인 단위로 나눕니다.

```text
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

각 도메인 내부는 기본적으로 다음 구조를 사용합니다.

```text
presentation
application
domain
repository
```

주기 실행이 필요한 도메인은 `scheduler`를 추가합니다.

그 아래 세분화는 파일이 늘어났을 때 담당자가 나눕니다.

### 코드 구조 원칙

* 다른 도메인의 Entity·Repository를 통해 상태를 직접 변경하지 않습니다.
* 도메인 간 상태 변경은 해당 도메인의 Service를 통해 수행합니다.
* 도메인 간 참조는 Entity 객체가 아닌 ID를 사용합니다.
* 조회 최적화가 필요한 경우 QueryRepository·Projection을 사용할 수 있습니다.
* Event 상태 변경은 `EventCommandService`를 통해서만 수행합니다.

공통 응답과 예외 처리는 `common` 패키지에서 관리합니다.

도메인별 ErrorCode는 공통 `ErrorCode` 인터페이스를 구현한 enum으로 정의합니다.
