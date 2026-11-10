<div align="center">
  <img src="./docs/assets/readme/cking-logo.png" alt="CKing 로고" width="430" />

  <br />

  <h3>크리에이터와 팬을 잇는 팬 참여형 이벤트 플랫폼</h3>
  <p>좋아하는 크리에이터를 발견하고, 미션으로 응모권을 획득하며,<br />이벤트 응모부터 검증 가능한 추첨과 당첨 결과 확인까지 하나의 흐름으로 연결합니다.</p>

  <br />

  <p><a href="https://cking.co.kr"><img alt="서비스 종료" src="https://img.shields.io/badge/SERVICE_ENDED-cking.co.kr-6B7280?style=for-the-badge&logo=googlechrome&logoColor=white" /></a></p>
  <p><a href="https://github.com/URECA-Cking/Cking-FE"><img alt="프론트엔드 저장소" src="https://img.shields.io/badge/FRONTEND-Repository-282C3B?style=for-the-badge&logo=github&logoColor=white" /></a></p>
  <p><a href="https://github.com/URECA-Cking/Cking-Infra"><img alt="인프라 저장소" src="https://img.shields.io/badge/INFRASTRUCTURE-Repository-282C3B?style=for-the-badge&logo=github&logoColor=white" /></a></p>
  <p><a href="https://youtu.be/WEKKLaadHcM"><img alt="서비스 시연 영상" src="https://img.shields.io/badge/DEMO-YouTube-D62D52?style=for-the-badge&logo=youtube&logoColor=white" /></a></p>
  <p><sub>서비스는 종료되었습니다. 시연은 DEMO 영상에서 볼 수 있습니다.</sub></p>
</div>

---

<h2 align="center">프로젝트 소개</h2>

<div align="center">
  <p>CKing은 SNS 곳곳에 흩어진 크리에이터 콘텐츠와 이벤트를 한곳에서 탐색하고,<br />팬의 참여를 미션·응모권·추첨으로 연결하는 플랫폼입니다.</p>

  <p>크리에이터는 전용 Space에서 콘텐츠와 일정을 운영하고 이벤트를 개최하며,<br />팬은 관심 있는 크리에이터를 도킹하고 미션을 수행해 이벤트에 참여합니다.</p>

  <br />

  <img src="./docs/assets/readme/cking-name-meaning.png" alt="Creator + King, Creator + Docking을 표현한 CKing 캐릭터" width="650" />
</div>

<br />

## 서비스 한눈에 보기

<p>
  <img src="./docs/assets/readme/service-overview.png" alt="CKing 서비스 소개 및 AI 확장 기능 개요" width="980" />
</p>

<br />

## 시스템 아키텍처

<p>
  <img src="./docs/assets/readme/system-architecture.png" alt="CKing 시스템 아키텍처" width="1000" />
</p>

- **실시간 처리:** Spring Boot API와 Redis Lua로 응모권 적립·차감, 중복 요청 검증, 이벤트 Gate 검사를 원자적으로 처리합니다.
- **비동기 영속화:** Redis Streams의 EARN·SPEND 이벤트를 Consumer가 처리하고, DB 커밋 후 XACK하여 실패 시 재처리할 수 있도록 설계했습니다.

- **운영 및 확장:** 마감·Snapshot·추첨·정합성 복구를 도메인별로 분리하고, Python 기반 추천 배치 및 비동기 AI 처리 파이프라인을 연동했습니다.

<br />

## 인프라 아키텍처

<p>
  <img src="./docs/assets/readme/infrastructure-architecture.png" alt="CKing AWS 인프라 아키텍처" width="1000" />
</p>

- **서비스 운영:** ALB·WAF 뒤에 두 가용 영역의 앱 서버(Auto Scaling 2\~4대)를 두고, RDS MySQL은 다른 가용 영역에 대기 DB(다중 AZ)를, ElastiCache(Valkey)는 복제 노드를 둡니다.
- **배포:** GitHub Actions가 앱 이미지를 GHCR에 올리고 앱 서버를 교체하며, 프론트엔드는 S3·CloudFront로 배포합니다.

- **관측:** 서버마다 Alloy가 지표·로그를 Prometheus·Loki로 보내고, Grafana·CloudWatch 경보는 Slack과 AI 진단 Lambda로 전달됩니다.

<sub>인프라 코드와 설계: [Cking-Infra](https://github.com/URECA-Cking/Cking-Infra)</sub>

<br />

## 개발 환경 및 기술 스택

| 구분 | 사용 기술 |
|:---|:---|
| **Frontend** | ![React](https://img.shields.io/badge/React-20232A?style=flat-square&logo=react&logoColor=white) ![Vite](https://img.shields.io/badge/Vite-646CFF?style=flat-square&logo=vite&logoColor=white) ![Tailwind CSS](https://img.shields.io/badge/Tailwind_CSS-0F766E?style=flat-square&logo=tailwindcss&logoColor=white) ![PWA](https://img.shields.io/badge/PWA-6048A5?style=flat-square&logo=pwa&logoColor=white) |
| **Backend** | ![Java 21](https://img.shields.io/badge/Java_21-27354A?style=flat-square&logo=openjdk&logoColor=white) ![Spring Boot 4.1.1](https://img.shields.io/badge/Spring_Boot_4.1.1-6DB33F?style=flat-square&logo=springboot&logoColor=white) ![Spring Security](https://img.shields.io/badge/Spring_Security-438A28?style=flat-square&logo=springsecurity&logoColor=white) ![Spring Data JPA](https://img.shields.io/badge/Spring_Data_JPA-315D32?style=flat-square&logo=spring&logoColor=white) ![OAuth2](https://img.shields.io/badge/OAuth2-49576C?style=flat-square&logoColor=white) ![JWT](https://img.shields.io/badge/JWT-405A79?style=flat-square&logo=jsonwebtokens&logoColor=white) |
| **Data / Messaging** | ![MySQL 8.4](https://img.shields.io/badge/MySQL_8.4-4479A1?style=flat-square&logo=mysql&logoColor=white) ![Redis 7.2](https://img.shields.io/badge/Redis_7.2-DC382D?style=flat-square&logo=redis&logoColor=white) ![Redis Lua](https://img.shields.io/badge/Redis_Lua-B91C46?style=flat-square&logo=redis&logoColor=white) ![Redis Streams](https://img.shields.io/badge/Redis_Streams-BA3040?style=flat-square&logo=redis&logoColor=white) ![Flyway](https://img.shields.io/badge/Flyway-CC0200?style=flat-square&logo=flyway&logoColor=white) |
| **AI / Recommendation** | ![Google Gemini](https://img.shields.io/badge/Google_Gemini-7061AD?style=flat-square&logo=googlegemini&logoColor=white) ![OpenAI](https://img.shields.io/badge/OpenAI-232F36?style=flat-square&logo=openai&logoColor=white) ![Python](https://img.shields.io/badge/Python-3776AB?style=flat-square&logo=python&logoColor=white) ![BGE-M3](https://img.shields.io/badge/BGE-M3-A2519C?style=flat-square&logoColor=white) |
| **Infrastructure** | ![AWS](https://img.shields.io/badge/AWS-232F3E?style=flat-square&logo=amazonaws&logoColor=white) ![Docker](https://img.shields.io/badge/Docker-2496ED?style=flat-square&logo=docker&logoColor=white) ![Terraform](https://img.shields.io/badge/Terraform-7B42BC?style=flat-square&logo=terraform&logoColor=white) ![Amazon SQS](https://img.shields.io/badge/Amazon_SQS-FF4F8B?style=flat-square&logo=amazonsqs&logoColor=white) ![AWS Lambda](https://img.shields.io/badge/AWS_Lambda-FF9900?style=flat-square&logo=awslambda&logoColor=white) ![S3](https://img.shields.io/badge/S3-B8523E?style=flat-square&logo=amazons3&logoColor=white) ![CloudFront](https://img.shields.io/badge/CloudFront-D87723?style=flat-square&logo=amazonaws&logoColor=white) |
| **Monitoring** | ![Prometheus](https://img.shields.io/badge/Prometheus-E6522C?style=flat-square&logo=prometheus&logoColor=white) ![Grafana](https://img.shields.io/badge/Grafana-F46800?style=flat-square&logo=grafana&logoColor=white) ![Loki](https://img.shields.io/badge/Loki-49576C?style=flat-square&logo=grafana&logoColor=white) ![Grafana Alloy](https://img.shields.io/badge/Grafana_Alloy-F46800?style=flat-square&logo=grafana&logoColor=white) ![CloudWatch](https://img.shields.io/badge/CloudWatch-BF3F6E?style=flat-square&logo=amazoncloudwatch&logoColor=white) |
| **Development / Quality** | ![Gradle](https://img.shields.io/badge/Gradle-02303A?style=flat-square&logo=gradle&logoColor=white) ![Node.js 22](https://img.shields.io/badge/Node.js_22-339933?style=flat-square&logo=nodedotjs&logoColor=white) ![JUnit 6](https://img.shields.io/badge/JUnit_6-25A162?style=flat-square&logo=junit5&logoColor=white) ![Testcontainers](https://img.shields.io/badge/Testcontainers-1783CC?style=flat-square&logo=testcontainers&logoColor=white) ![k6](https://img.shields.io/badge/k6-7D64EE?style=flat-square&logo=k6&logoColor=white) ![Playwright](https://img.shields.io/badge/Playwright-258A56?style=flat-square&logo=playwright&logoColor=white) |
| **CI / CD** | ![GitHub Actions](https://img.shields.io/badge/GitHub_Actions-2088FF?style=flat-square&logo=githubactions&logoColor=white) ![GHCR](https://img.shields.io/badge/GHCR-262E3A?style=flat-square&logo=github&logoColor=white) ![Dependabot](https://img.shields.io/badge/Dependabot-1F6FEB?style=flat-square&logo=dependabot&logoColor=white) |

<sub>운영 데이터는 RDS MySQL과 ElastiCache(Valkey), 배포는 EC2·ALB/WAF·CloudFront 등을 사용합니다. Swagger UI, S3Mock, Docker Compose 및 Spring Actuator/Micrometer를 개발·운영에 활용합니다.</sub>

<br />

## 서비스 전체 동작 흐름

<p>
  <img src="./docs/assets/readme/service-flow.png" alt="팬·크리에이터·관리자의 서비스 전체 동작 흐름" width="1000" />
</p>

| 구분 | 주요 흐름 |
|:---:|---|
| **팬** | 크리에이터 탐색·도킹 → 미션 수행 및 응모권 획득 → 이벤트 응모·당첨 확인 |
| **크리에이터** | Creator Space에서 콘텐츠·일정·미션·퀴즈 운영 → 이벤트 개설·승인 요청 → 진행 현황 및 결과 확인 |
| **관리자** | 크리에이터·이벤트 심사 및 운영 → 마감·Snapshot·추첨·결과 공개 관리 |

<br />

## 핵심 기능 및 설계

<p>
  <img src="./docs/assets/readme/core-features-ui.png" alt="응모권 획득, 이벤트 응모, 당첨 결과 알림 UI" width="900" />
</p>

### 1. 응모권 획득 — 중복 없는 적립과 장애 복구

- **두 겹의 중복 방지:** `requestId`와 요청 fingerprint로 재전송을 판별하고, 사용자·미션·보상 기간 단위 Guard로 서로 다른 요청 ID의 중복 보상도 차단합니다.
- **Redis Lua 원자성:** Guard 확인, Redis 잔액 증가, EARN Stream 발행을 하나의 Lua 실행에 묶습니다. XADD가 실패하면 변경한 잔액과 예약 상태를 되돌립니다.
- **DB 확정 및 재처리:** Consumer가 완료 이력·Ledger·잔액을 하나의 트랜잭션으로 저장하고 커밋 후 XACK합니다. 실패 메시지는 PEL 회수·재처리 및 Dead Stream으로 추적합니다.

<sub>관련 문서: [Ticket](docs/domains/ticket/README.md) · [Mission](docs/domains/mission/README.md) · [Stream](docs/domains/stream/README.md)</sub>

<br />

### 2. 이벤트 응모 — 동시 요청에서도 잔액 정합성 유지

- **원자적 접수:** Redis Lua에서 이벤트 Gate·종료 시각·응모권 잔액을 검증하고, 차감 및 SPEND Stream 발행을 한 번에 처리합니다.
- **안전한 재시도:** 동일한 `requestId`와 동일한 요청은 기존 결과로 처리해 추가 차감을 방지하고, 요청 내용이 달라지면 충돌로 거절합니다.
- **비동기 이력 반영:** Consumer가 응모 내역·차감 Ledger·DB 잔액을 함께 확정하고, 마감 시에는 cutoff 이전에 승인된 응모의 반영 완료를 기다립니다.

<sub>관련 문서: [Event](docs/domains/event/README.md) · [Ticket](docs/domains/ticket/README.md)</sub>

<br />

### 3. 이벤트 마감·추첨 — 입력과 결과를 검증 가능한 형태로 확정

- **마감 경계:** `OPEN → CLOSING → CLOSED` 전이에서 Gate 차단과 Stream cutoff를 확정하고, Drain 완료 후 공식 Snapshot을 생성합니다.
- **재현 가능한 추첨:** 후보·상품을 고정한 Snapshot과 Seed를 저장합니다. 응모권 비례 가중 추첨에는 Fenwick Tree를 사용하며, 당첨자는 비복원 방식으로 선정합니다.
- **무결성 및 공개:** Snapshot·Input·Result Hash로 입력과 결과를 검증하고, 결과 공개와 당첨 알림을 같은 트랜잭션으로 처리합니다. 재추첨은 기존 Snapshot과 새로운 Seed를 사용합니다.

<sub>관련 문서: [Snapshot](docs/domains/snapshot/README.md) · [Drawing](docs/domains/drawing/README.md) · [Redraw](docs/domains/redraw/README.md)</sub>

<br />

### 핵심 기능 검증 결과

| 항목 | 측정 결과 |
|:---:|:---|
| 부하 테스트 환경 | 운영 환경, 크리에이터 5명·회원 10,000명 |
| 응모 API 접수 | 앱 서버 t4g.small 2대에서 초당 578건까지 p95 44ms 이하, 실패 0(DB 반영 한계 초당 약 300\~325건) |
| 목표 부하(초당 80건) 지속 | p95 23.03ms(c6g.large 2대, 복제 확인 포함) |
| 데이터 정합성 | 음수 잔액·중복 차감·마감 후 신규 응모·Redis/DB 잔액 불일치 0건 |
| 가중 추첨 비교 | 후보 9,500명·당첨자 950명·1인 응모권 100장(p50): 티켓 배열 770.14ms → Fenwick Tree 5.62ms |

<sub>관련 문서: [부하 테스트 결과](https://github.com/URECA-Cking/Cking-Infra/blob/main/docs/loadtest.md) · [부하 테스트 가이드](docs/operations/loadtest.md) · [추첨 벤치마크](docs/domains/drawing/weighted-random-results.md)</sub>

<br />

## 추가 기능 및 서비스 확장

<p>
  <img src="./docs/assets/readme/extended-features-ui.png" alt="맞춤 추천, 댓글 필터링, YouTube 구독 인증, AI 퀴즈 UI" width="1000" />
</p>

| 기능 | 주요 설계 및 구현 |
|---|---|
| **맞춤 크리에이터 추천** | Creator 소개를 BGE-M3로 임베딩하고 GPT 분야 태그를 조합해 후보를 생성·저장합니다. 조회 시 관심사·도킹 신호 등을 반영하며, 후보 생성과 실시간 조회를 분리했습니다. |
| **AI 댓글 필터링** | 댓글 저장 후 비동기로 규칙 검사·문맥 분류를 실행합니다. 개인정보·욕설·스팸 등을 차단하고, 수정 중 재판정 우회 방지 및 실패 재시도·관리자 조회를 제공합니다. |
| **YouTube 구독 인증 미션** | 업로드한 구독 화면을 VLM으로 분석하고 서버 정책으로 최종 승인 여부를 결정합니다. Processing Claim·고정 보상 요청 ID·Recovery를 통해 중복 지급을 방지합니다. |
| **AI 퀴즈 미션** | 영상 원문 추출·문항 생성(Gemini), 7가지 기준의 GPT Judge 평가, Creator 승인·게시, 팬 응시·서버 채점·보상까지 연결합니다. |
| **Creator Space** | 프로필·도킹·게시물·댓글·일정·미션·이벤트를 크리에이터별 공간에서 통합 운영합니다. |
| **비정상 행동 탐지** | Redis Lua 기반 최근 행동 집계로 반복 요청·잔액 부족·빠른 적립 후 사용 등 패턴을 탐지하고, MySQL Evidence와 관리자 검토 이력을 관리합니다. |

<sub>관련 문서: [추천](docs/domains/creator/similarity-recommendation.md) · [댓글](docs/domains/post/README.md) · [구독 인증](docs/domains/subscription-verification/README.md) · [AI Quiz](docs/domains/quiz/README.md) · [이상 탐지](docs/domains/abuse-detection/README.md)</sub>

<br />

## 프로젝트 구조

```text
CKing-BE/
├── src/main/java/kr/co/cking/
│   ├── abuse/                  # 비정상 행동 탐지
│   ├── auth/                   # 인증 · OAuth2 · JWT
│   ├── calendar/               # 일정 · 개인 캘린더
│   ├── common/                 # 공통 보안 · 설정 · 응답 · 저장소
│   ├── creator/                # Creator 신청 · 자격 · Space
│   ├── drawing/                # 가중 추첨
│   ├── event/                  # 이벤트 · 응모 · 마감
│   ├── follow/                 # 도킹 · 팔로우
│   ├── interest/               # 관심 분야
│   ├── member/                 # 회원 · 프로필
│   ├── mission/                # 미션 · 완료 판정
│   ├── notification/           # 알림
│   ├── post/                   # 게시물 · 댓글
│   ├── quiz/                   # AI Quiz 생성 · 평가 · 응시
│   ├── recommendation/         # 추천 후보 공개 자격 검사
│   ├── redraw/                 # 재추첨 요청 · 실행
│   ├── sanction/               # 댓글 제재
│   ├── snapshot/               # 공식 추첨 후보 확정
│   ├── stream/                 # Stream Consumer · 재처리
│   ├── subscriptionverification/  # 구독 인증
│   ├── support/                # 지원 기능
│   ├── ticket/                 # 응모권 · Ledger
│   ├── upload/                 # 이미지 업로드
│   └── winner/                 # 당첨 결과 · 수령 관리
├── src/main/resources/
│   ├── db/migration/           # Flyway
│   ├── scripts/                # Redis Lua
│   └── application-*.yml       # 환경별 설정
├── src/test/                   # 단위 · 통합 테스트
├── docs/                       # API · 설계 · 운영 문서
└── .github/workflows/          # CI/CD
```

도메인은 주로 `presentation → application → domain → repository` 계층으로 구성되며, 스케줄러·외부 AI 연동은 필요에 따라 별도 패키지로 분리합니다.

<br />

## API 명세

전체 API 목록은 [API 인덱스](docs/api-index.md), 공통 응답·오류 규약은 [공통 API 규약](docs/common/api.md)에서 확인할 수 있습니다.

<br />

## 로컬 실행 및 개발 가이드

### 1. 사전 준비

- Git, JDK 21, Docker Desktop 설치
- IDE 및 터미널에서 `git --version`, `java -version`, `docker compose version` 확인

### 2. 프로젝트 준비

```bash
git clone https://github.com/URECA-Cking/Cking-BE.git
cd Cking-BE
git switch develop
docker compose up -d
```

`docker-compose.yml`은 MySQL 8.4(3306), Redis 7.2(6379), S3Mock(9090)을 실행합니다. 로컬 DB는 `cking` 데이터베이스/계정을 사용하고, S3Mock 버킷은 `cking-local-uploads`입니다. 로컬 개발용 값은 반드시 운영 자격 증명과 분리합니다.

### 3. 애플리케이션 실행

먼저 `openssl rand -base64 32` 등으로 `JWT_SECRET`을 생성합니다. 실제 시크릿 값을 Git 저장소·로그·PR에 넣지 않습니다.

**macOS / Linux**

```bash
export JWT_SECRET='...'
./gradlew bootRun
```

**Windows PowerShell**

```powershell
$env:JWT_SECRET='...'
.\gradlew.bat bootRun
```

선택 기능을 사용할 때만 관련 환경변수를 추가합니다.

| 기능 | 주요 환경변수 |
|---|---|
| Google·Kakao 로그인 | `SPRING_PROFILES_ACTIVE=local,oauth`, `OAUTH_GOOGLE_CLIENT_ID`, `OAUTH_GOOGLE_CLIENT_SECRET`, `OAUTH_KAKAO_CLIENT_ID`, `OAUTH_KAKAO_CLIENT_SECRET` |
| YouTube 채널 연결 | Google·Kakao 로그인 설정(`oauth` 프로필)에 더해 `OAUTH_YOUTUBE_CLIENT_ID`, `OAUTH_YOUTUBE_CLIENT_SECRET`, `OAUTH_YOUTUBE_API_KEY` |
| AI 퀴즈 생성 | `QUIZ_GENERATION_ENABLED=true`, `GEMINI_API_KEY` |
| 구독 인증 제출 | `SUBSCRIPTION_VERIFICATION_SUBMISSION_ENABLED=true`, `SUBSCRIPTION_GEMINI_API_KEY` |

`QUIZ_GENERATION_ENABLED`가 꺼져 있으면(기본값) 퀴즈 생성 요청은 503으로 거절되고, 켠 상태에서 `GEMINI_API_KEY`가 없으면 Provider 오류가 발생합니다. 모델·타임아웃·재시도 설정은 [AI Quiz](docs/domains/quiz/README.md)와 [구독 인증](docs/domains/subscription-verification/README.md) 문서를 참고하세요.

### 4. API 확인 및 테스트

- 로컬 Swagger UI: <http://localhost:8080/swagger-ui/index.html>

```bash
./gradlew test          # macOS / Linux
.\gradlew.bat test       # Windows PowerShell
```

테스트 프로필에서는 HikariCP 최대 연결 수를 3으로 제한합니다. 통합 테스트에 필요한 MySQL·Redis 컨테이너가 실행 중인지 확인하세요.

### 5. 종료 및 데이터 정리

```bash
docker compose down       # 컨테이너만 종료
docker compose down -v    # 로컬 DB·S3Mock 볼륨까지 삭제
```

**주의:** `-v`는 로컬 DB 데이터와 S3Mock 저장 데이터를 삭제합니다.

### 6. 코드 구조 및 협업

도메인별로 `presentation / application / domain / repository` 계층을 사용하며, 상태 변경은 소유 도메인의 서비스를 통해 수행합니다. API·설계 문서는 [문서 인덱스](docs/README.md), 커밋·브랜치·PR 규칙은 [CONTRIBUTING.md](https://github.com/URECA-Cking/.github/blob/main/CONTRIBUTING.md)를 참고하세요.

작업은 `develop`에서 `<타입>/<이슈번호>-<요약>` 브랜치를 생성하고, 리뷰 후 `develop`에 Squash and merge하는 절차를 따릅니다. `main`과 `develop`에는 직접 push하지 않습니다.

<br />

## 팀원

| 이름 | 담당 영역 | 핵심 기여 |
|:---:|---|---|
| 권혁준 (팀장) | 인증·인가, 운영 백오피스, 이상 행동 탐지, AI 구독 인증 미션 | OAuth2/JWT 인증과 Creator·Event 심사·운영 기능, Redis Lua 기반 이상 행동 탐지와 구독 인증 VLM 벤치마크 |
| 김태연 | 미션 도메인, AI 퀴즈 미션, 리워드 시스템 | Gemini Grounding·GPT Judge 기반 퀴즈 생성·품질 평가 파이프라인, 퀴즈 수정·게시·응시·채점과 Redis 응모권 보상 연동 |
| 이성집 | 이벤트 응모 프로세스, 데이터 정합성, AI 맞춤형 추천 | Redis Streams 기반 응모권 영속화와 Gate·Cutoff·Drain 마감 처리, 잔액 정합성 복구와 임베딩 기반 크리에이터 추천 API |
| 장근창 | 인프라·배포, 관측·장애 대응, 이미지 업로드 파이프라인, 응모 경로 안정화 | AWS 인프라 구축(Terraform)과 배포 자동화, 관측·경보·장애 대응 체계와 부하·장애 주입 검증, S3·SQS·Lambda 이미지 처리 파이프라인, 응모 Redis·Stream 결함 수정과 처리량 개선 |
| 정문구 | 미션 도메인, 공용 리워드, 크리에이터 스페이스 | 출석·좋아요 미션 검증과 Redis Lua·Stream 기반 공용 응모권 발급, Creator 일정·개인 캘린더 API와 소유권·시간대 검증 |
| 정윤희 | 이벤트 마감·추첨·당첨 운영 시스템, 인증·인가, 이상 행동 탐지, AI 구독 인증 미션 | OAuth2/JWT 인증과 Snapshot·재현 검증·재추첨·당첨 결과 공개 흐름, Redis Lua 기반 이상 행동 탐지와 Gemini VLM 구독 인증·보상 |
| 정자비 | 응모권 적립 시스템, AI 댓글 필터링, 콘텐츠 모더레이션 | Redis Lua 기반 응모권 적립·차감 원자 처리와 멱등성·중복 방지, Creator Space·커뮤니티·신고·제재 기능과 AI 댓글 필터링·비동기 판정 연동 |
| 조성원 | 추첨 알고리즘, AI 맞춤형 추천, 데이터 파이프라인 | Seed 기반 가중 추첨 엔진과 재현성·성능 검증·실패 복구, AI 크리에이터 추천·콘텐츠 태깅과 배치 자동화·API 연동 |
