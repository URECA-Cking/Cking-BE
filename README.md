<div align="center">
  <img src="./docs/assets/readme/cking-logo.png" alt="CKing 로고" width="430" />

  <br />

  <h3>크리에이터와 팬을 잇는 팬 참여형 이벤트 플랫폼</h3>
  <p>좋아하는 크리에이터를 발견하고, 미션으로 응모권을 획득하며,<br />이벤트 응모부터 검증 가능한 추첨과 당첨 결과 확인까지 하나의 흐름으로 연결합니다.</p>

  <br />

  <p><a href="https://cking.co.kr"><img alt="서비스 바로가기" src="https://img.shields.io/badge/LIVE_SERVICE-cking.co.kr-E84383?style=for-the-badge&logo=googlechrome&logoColor=white" /></a></p>
  <p><a href="https://github.com/URECA-Cking/Cking-FE"><img alt="프론트엔드 저장소" src="https://img.shields.io/badge/FRONTEND-Repository-282C3B?style=for-the-badge&logo=github&logoColor=white" /></a></p>
  <p><a href="https://github.com/URECA-Cking/Cking-Infra"><img alt="인프라 저장소" src="https://img.shields.io/badge/INFRASTRUCTURE-Repository-282C3B?style=for-the-badge&logo=github&logoColor=white" /></a></p>
  <p><a href="https://youtu.be/WEKKLaadHcM"><img alt="서비스 시연 영상" src="https://img.shields.io/badge/DEMO-YouTube-D62D52?style=for-the-badge&logo=youtube&logoColor=white" /></a></p>
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

- **서비스 운영:** AWS ALB/WAF 및 다중 애플리케이션 인스턴스, RDS MySQL, ElastiCache(Valkey)를 활용합니다.
- **배포:** GitHub Actions 기반 CI/CD와 컨테이너 이미지 관리, S3·CloudFront 기반 프론트엔드 배포를 구성했습니다.

- **관측:** Prometheus·Grafana·Loki·CloudWatch 기반 지표·로그 수집과 알림을 연계했습니다.

<br />

## 개발 환경 및 기술 스택

| 구분 | 사용 기술 |
|:---|:---|
| **Frontend** | ![React](https://img.shields.io/badge/React-20232A?style=flat-square&logo=react&logoColor=white) ![Vite](https://img.shields.io/badge/Vite-646CFF?style=flat-square&logo=vite&logoColor=white) ![Tailwind CSS](https://img.shields.io/badge/Tailwind_CSS-0F766E?style=flat-square&logo=tailwindcss&logoColor=white) ![PWA](https://img.shields.io/badge/PWA-6048A5?style=flat-square&logo=pwa&logoColor=white) |
| **Backend** | ![Java 21](https://img.shields.io/badge/Java_21-27354A?style=flat-square&logo=openjdk&logoColor=white) ![Spring Boot 4.1.1](https://img.shields.io/badge/Spring_Boot_4.1.1-6DB33F?style=flat-square&logo=springboot&logoColor=white) ![Spring Security](https://img.shields.io/badge/Spring_Security-438A28?style=flat-square&logo=springsecurity&logoColor=white) ![Spring Data JPA](https://img.shields.io/badge/Spring_Data_JPA-315D32?style=flat-square&logo=spring&logoColor=white) ![OAuth2](https://img.shields.io/badge/OAuth2-49576C?style=flat-square&logoColor=white) ![JWT](https://img.shields.io/badge/JWT-405A79?style=flat-square&logo=jsonwebtokens&logoColor=white) |
| **Data / Messaging** | ![MySQL 8.4](https://img.shields.io/badge/MySQL_8.4-4479A1?style=flat-square&logo=mysql&logoColor=white) ![Redis 7.2](https://img.shields.io/badge/Redis_7.2-DC382D?style=flat-square&logo=redis&logoColor=white) ![Redis Lua](https://img.shields.io/badge/Redis_Lua-B91C46?style=flat-square&logo=redis&logoColor=white) ![Redis Streams](https://img.shields.io/badge/Redis_Streams-BA3040?style=flat-square&logo=redis&logoColor=white) ![Flyway](https://img.shields.io/badge/Flyway-CC0200?style=flat-square&logo=flyway&logoColor=white) |
| **AI / Recommendation** | ![Google Gemini](https://img.shields.io/badge/Google_Gemini-7061AD?style=flat-square&logo=googlegemini&logoColor=white) ![OpenAI](https://img.shields.io/badge/OpenAI-232F36?style=flat-square&logo=openai&logoColor=white) ![Python](https://img.shields.io/badge/Python-3776AB?style=flat-square&logo=python&logoColor=white) ![BGE-M3](https://img.shields.io/badge/BGE-M3-A2519C?style=flat-square&logoColor=white) |
| **Infrastructure** | ![AWS](https://img.shields.io/badge/AWS-232F3E?style=flat-square&logo=amazonaws&logoColor=white) ![Docker](https://img.shields.io/badge/Docker-2496ED?style=flat-square&logo=docker&logoColor=white) ![Terraform](https://img.shields.io/badge/Terraform-7B42BC?style=flat-square&logo=terraform&logoColor=white) ![S3](https://img.shields.io/badge/S3-B8523E?style=flat-square&logo=amazons3&logoColor=white) ![CloudFront](https://img.shields.io/badge/CloudFront-D87723?style=flat-square&logo=amazonaws&logoColor=white) |
| **Monitoring** | ![Prometheus](https://img.shields.io/badge/Prometheus-E6522C?style=flat-square&logo=prometheus&logoColor=white) ![Grafana](https://img.shields.io/badge/Grafana-F46800?style=flat-square&logo=grafana&logoColor=white) ![Loki](https://img.shields.io/badge/Loki-49576C?style=flat-square&logo=grafana&logoColor=white) ![CloudWatch](https://img.shields.io/badge/CloudWatch-BF3F6E?style=flat-square&logo=amazoncloudwatch&logoColor=white) |
| **Development / Quality** | ![Gradle](https://img.shields.io/badge/Gradle-02303A?style=flat-square&logo=gradle&logoColor=white) ![Node.js 22](https://img.shields.io/badge/Node.js_22-339933?style=flat-square&logo=nodedotjs&logoColor=white) ![JUnit 5](https://img.shields.io/badge/JUnit_5-25A162?style=flat-square&logo=junit5&logoColor=white) ![Testcontainers](https://img.shields.io/badge/Testcontainers-1783CC?style=flat-square&logo=testcontainers&logoColor=white) ![k6](https://img.shields.io/badge/k6-7D64EE?style=flat-square&logo=k6&logoColor=white) ![Playwright](https://img.shields.io/badge/Playwright-258A56?style=flat-square&logo=playwright&logoColor=white) |
| **CI / CD** | ![GitHub Actions](https://img.shields.io/badge/GitHub_Actions-2088FF?style=flat-square&logo=githubactions&logoColor=white) ![GHCR](https://img.shields.io/badge/GHCR-262E3A?style=flat-square&logo=github&logoColor=white) ![Dependabot](https://img.shields.io/badge/Dependabot-1F6FEB?style=flat-square&logo=dependabot&logoColor=white) |

<sub>운영 데이터는 RDS MySQL과 ElastiCache(Valkey), 배포는 EC2·ALB/WAF·CloudFront 등을 사용합니다. Swagger UI, S3Mock, Docker Compose 및 Spring Actuator/Micrometer를 개발·운영에 활용합니다.</sub>

<br />

## 서비스 전체 동작 흐름

<p>
  <img src="./docs/assets/readme/service-flow.png" alt="팬·크리에이터·관리자의 서비스 전체 동작 흐름" width="1000" />
</p>

관리자는 크리에이터 전환 신청과 이벤트를 심사하고, 크리에이터는 Creator Space에서 게시물·일정·미션·퀴즈·이벤트를 운영합니다. 팬은 관심 있는 크리에이터를 도킹하고 미션으로 응모권을 획득해 승인된 이벤트에 응모합니다. 마감 시 Redis Gate와 Stream cutoff로 신규 접수를 차단하고 처리 중인 응모의 DB 반영(Drain)을 기다린 뒤 공식 Snapshot을 확정합니다. 이후 저장된 Seed로 가중 추첨을 실행하고 Hash로 입력과 결과를 검증해 당첨 결과를 공개하고 알림을 전달합니다.

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

### 2. 이벤트 응모 — 동시 요청에서도 잔액 정합성 유지

- **원자적 접수:** Redis Lua에서 이벤트 Gate·종료 시각·응모권 잔액을 검증하고, 차감 및 SPEND Stream 발행을 한 번에 처리합니다.
- **안전한 재시도:** 동일한 `requestId`와 동일한 요청은 기존 결과로 처리해 추가 차감을 방지하고, 요청 내용이 달라지면 충돌로 거절합니다.
- **비동기 이력 반영:** Consumer가 응모 내역·차감 Ledger·DB 잔액을 함께 확정하고, 마감 시에는 cutoff 이전에 승인된 응모의 반영 완료를 기다립니다.

<sub>관련 문서: [Event](docs/domains/event/README.md) · [Ticket](docs/domains/ticket/README.md)</sub>

### 3. 이벤트 마감·추첨 — 입력과 결과를 검증 가능한 형태로 확정

- **마감 경계:** `OPEN → CLOSING → CLOSED` 전이에서 Gate 차단과 Stream cutoff를 확정하고, Drain 완료 후 공식 Snapshot을 생성합니다.
- **재현 가능한 추첨:** 후보·상품을 고정한 Snapshot과 Seed를 저장합니다. 응모권 비례 가중 추첨에는 Fenwick Tree를 사용하며, 당첨자는 비복원 방식으로 선정합니다.
- **무결성 및 공개:** Snapshot·Input·Result Hash로 입력과 결과를 검증하고, 결과 공개와 당첨 알림을 같은 트랜잭션으로 처리합니다. 재추첨은 기존 Snapshot과 새로운 Seed를 사용합니다.

<sub>관련 문서: [Snapshot](docs/domains/snapshot/README.md) · [Drawing](docs/domains/drawing/README.md) · [Redraw](docs/domains/redraw/README.md)</sub>

### 핵심 기능 검증 결과

| 항목 | 발표 당시 측정 결과 |
|:---:|:---:|
| 응모 부하 조건 | 동시 요청자 50명 |
| 처리량 | 최대 578 TPS |
| 응답 지연 | p95 23ms |
| 오류율 | 0.0% |
| 데이터 정합성 | 음수 잔액·중복 차감·마감 후 신규 응모·Redis/DB 잔액 불일치 0건 |
| 가중 추첨 비교 | 후보 10,000명·당첨자 950명 조건: 티켓 배열 770.14ms → Fenwick Tree 5.62ms |

<sub>관련 문서: [부하 테스트 가이드](docs/operations/loadtest.md) · [추첨 벤치마크](docs/domains/drawing/weighted-random-benchmark.md)</sub>

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
│   ├── auth/                   # OAuth2 · JWT 인증
│   ├── member/                 # 회원 및 프로필
│   ├── creator/                # 크리에이터 신청 · 자격 · Space
│   ├── follow/ · interest/     # 팔로우 · 관심 분야
│   ├── post/ · calendar/       # 게시물 · 댓글 · 일정
│   ├── mission/ · ticket/      # 미션 · 응모권 · 원장
│   ├── event/ · stream/        # 이벤트 · Redis Stream 연동
│   ├── snapshot/ · drawing/    # Snapshot · 추첨
│   ├── redraw/ · winner/       # 재추첨 · 당첨 관리
│   ├── quiz/                   # AI Quiz 생성 · 심사 · 응시
│   ├── subscriptionverification/  # YouTube 구독 인증
│   ├── recommendation/ · abuse/   # 추천 · 비정상 행동 탐지
│   ├── notification/ · sanction/  # 알림 · 제재
│   ├── upload/                 # 이미지 업로드 파이프라인
│   ├── common/                 # 공통 설정 · 보안 · 저장소 · 예외
│   └── support/                # 지원 기능
├── src/main/resources/
│   ├── db/migration/           # Flyway DB 마이그레이션
│   ├── scripts/                # Redis Lua 스크립트
│   └── application-*.yml       # 환경별 설정
├── src/test/                   # 단위 · 통합 테스트
├── docs/                       # 도메인 API · 설계 · 운영 문서
└── .github/workflows/          # CI/CD
```

주요 도메인은 `presentation → application → domain → repository` 계층을 기준으로 구성되며, 스케줄러·외부 AI 연동 등은 필요에 따라 별도 패키지로 분리합니다.

<br />

## 도메인별 API 명세

도메인별 대표 엔드포인트를 확인할 수 있습니다. 전체 목록과 요청·응답·오류 코드는 [API 인덱스](docs/api-index.md) 및 각 상세 문서를 참고하세요.

<details>
<summary><strong>Auth</strong> — 로그인 · 토큰</summary>
<p><code>POST /api/auth/token</code> · Login Code → Access JWT<br /><code>POST /api/auth/refresh</code> · Refresh Token 갱신<br /><code>POST /api/auth/logout</code> · 로그아웃</p>
<p><a href="docs/domains/auth/api.md">상세 API 문서</a></p>
</details>
<details>
<summary><strong>Member</strong> — 회원 · 프로필</summary>
<p><code>GET /api/me</code> · 내 회원 정보<br /><code>PUT /api/me/profile</code> · 프로필 변경</p>
<p><a href="docs/domains/member/api.md">상세 API 문서</a></p>
</details>
<details>
<summary><strong>Creator 신청</strong> — 신청 · 심사 · 보완</summary>
<p><code>POST /api/creator/applications</code> · 전환 신청<br /><code>POST /api/creator/applications/{id}/resubmit</code> · 보완 재제출<br /><code>POST /api/admin/creator-applications/{id}/request-revision</code> · 관리자 보완 요청</p>
<p><a href="docs/domains/creator/application-review-api.md">상세 API 문서</a></p>
</details>
<details>
<summary><strong>Creator 자격</strong> — 정지 · 해제 · 박탈</summary>
<p><code>GET /api/creator/me/status</code> · 현재 자격 조회<br /><code>POST /api/admin/creators/{creatorId}/suspend</code> · 자격 정지<br /><code>POST /api/admin/creators/{creatorId}/reactivate</code> · 정지 해제<br /><code>POST /api/admin/creators/{creatorId}/revoke</code> · 자격 박탈</p>
<p><a href="docs/domains/creator/status-api.md">상세 API 문서</a></p>
</details>
<details>
<summary><strong>Creator Space</strong> — 공개 공간 · 관리</summary>
<p><code>GET /api/creators/{creatorId}/space</code> · Space 조회<br /><code>PATCH /api/creator/space</code> · 내 Space 수정</p>
<p><a href="docs/domains/creator/space-api.md">상세 API 문서</a></p>
</details>
<details>
<summary><strong>Follow</strong> — 도킹 · 팔로우</summary>
<p><code>PUT /api/creators/{creatorId}/follow</code> · 도킹<br /><code>GET /api/me/follows</code> · 내 도킹 목록</p>
<p><a href="docs/domains/follow/api.md">상세 API 문서</a></p>
</details>
<details>
<summary><strong>Interest · Recommendation</strong> — 관심 분야 · 개인화 추천</summary>
<p><code>GET /api/interests</code> · 관심 분야 목록<br /><code>GET /api/me/creator-recommendations</code> · 개인화 Creator 추천<br /><code>GET /api/creators/{creatorId}/similar</code> · 유사 Creator 추천</p>
<p><a href="docs/domains/creator/similarity-recommendation.md">상세 API 문서</a></p>
</details>
<details>
<summary><strong>Post · Comment</strong> — 콘텐츠 · 댓글 · 필터</summary>
<p><code>POST /api/creator/posts</code> · 게시물 작성<br /><code>POST /api/creators/{creatorId}/posts/{postId}/comments</code> · 댓글 작성<br /><code>GET /api/admin/filtered-comments</code> · 필터 차단 댓글 조회</p>
<p><a href="docs/domains/post/comment-api.md">상세 API 문서</a></p>
</details>
<details>
<summary><strong>Calendar</strong> — Creator · 개인 일정</summary>
<p><code>POST /api/creator/calendar/schedules</code> · Creator 일정 등록<br /><code>GET /api/me/calendar/schedules</code> · 내 캘린더 조회</p>
<p><a href="docs/domains/calendar/api.md">상세 API 문서</a></p>
</details>
<details>
<summary><strong>Mission</strong> — 미션 · 웰컴 퀘스트</summary>
<p><code>GET /api/missions</code> · 공용 미션 목록<br /><code>POST /api/missions/{missionId}/complete</code> · 공용 미션 완료<br /><code>POST /api/creators/{creatorId}/missions/{missionId}/complete</code> · Creator 미션 완료</p>
<p><a href="docs/domains/mission/api.md">상세 API 문서</a></p>
</details>
<details>
<summary><strong>Ticket</strong> — 응모권 · 이력 · 보정</summary>
<p><code>GET /api/me/tickets</code> · 내 응모권 요약<br /><code>GET /api/creators/{creatorId}/tickets/history</code> · 전용 원장 조회<br /><code>POST /api/admin/tickets/resync</code> · 관리자 잔액 보정</p>
<p><a href="docs/domains/ticket/README.md">상세 API 문서</a></p>
</details>
<details>
<summary><strong>Event · Entry</strong> — 이벤트 · 응모</summary>
<p><code>GET /api/events</code> · 이벤트 목록<br /><code>POST /api/creator/events</code> · 이벤트 개설<br /><code>POST /api/events/{eventId}/entries</code> · 이벤트 응모<br /><code>GET /api/events/{eventId}/entries/me</code> · 내 응모 내역</p>
<p><a href="docs/domains/event/api.md">상세 API 문서</a></p>
</details>
<details>
<summary><strong>Event 승인</strong> — 관리자 이벤트 심사</summary>
<p><code>GET /api/admin/events/pending</code> · 승인 대기 목록<br /><code>POST /api/admin/events/{eventId}/approve</code> · 이벤트 승인</p>
<p><a href="docs/domains/event/api.md">상세 API 문서</a></p>
</details>
<details>
<summary><strong>Close · Snapshot</strong> — 마감 · 공식 입력 확정</summary>
<p><code>POST /api/events/{eventId}/close</code> · 마감 요청<br /><code>GET /api/admin/events/{eventId}/closing-status</code> · 마감 상태 조회<br /><code>GET /api/admin/events/{eventId}/snapshot</code> · Snapshot 조회</p>
<p><a href="docs/domains/snapshot/README.md">상세 API 문서</a></p>
</details>
<details>
<summary><strong>Drawing · Redraw</strong> — 추첨 · 결과 검증 · 재추첨</summary>
<p><code>POST /api/admin/events/{eventId}/drawings</code> · 최초 추첨<br /><code>POST /api/admin/drawings/{drawingId}/verify</code> · 추첨 재현 검증<br /><code>POST /api/admin/events/{eventId}/redraw-requests</code> · 재추첨 요청</p>
<p><a href="docs/domains/drawing/api.md">상세 API 문서</a></p>
</details>
<details>
<summary><strong>Winner</strong> — 당첨 · 수령 관리</summary>
<p><code>GET /api/events/{eventId}/winners</code> · 이벤트 당첨 결과<br /><code>GET /api/me/winners</code> · 내 당첨 결과<br /><code>POST /api/me/winners/{winnerId}/decline</code> · 당첨 포기</p>
<p><a href="docs/domains/winner/api.md">상세 API 문서</a></p>
</details>
<details>
<summary><strong>YouTube Subscription Verification</strong> — 채널 소유권 · 구독 인증</summary>
<p><code>POST /api/creator/youtube-channel/connections</code> · 채널 인증 시작<br /><code>POST /api/creators/{creatorId}/missions/{missionId}/subscription-verifications</code> · 구독 인증 제출<br /><code>GET /api/subscription-verifications/{verificationId}</code> · 검증 결과 조회</p>
<p><a href="docs/domains/subscription-verification/api.md">상세 API 문서</a></p>
</details>
<details>
<summary><strong>AI Quiz</strong> — 생성 · 승인 · 공개</summary>
<p><code>POST /api/creator/quizzes/generations</code> · AI Quiz 생성<br /><code>POST /api/creator/quizzes/{quizId}/publish</code> · Quiz 게시<br /><code>GET /api/creators/{creatorId}/quizzes/{quizId}</code> · 공개 Quiz 조회</p>
<p><a href="docs/domains/quiz/api.md">상세 API 문서</a></p>
</details>
<details>
<summary><strong>Notification</strong> — 알림 조회 · 읽음</summary>
<p><code>GET /api/me/notifications</code> · 내 알림 목록<br /><code>PATCH /api/me/notifications/{notificationId}/read</code> · 알림 읽음</p>
<p><a href="docs/domains/notification/README.md">상세 API 문서</a></p>
</details>
<details>
<summary><strong>Sanction</strong> — 댓글 제재 · 차단 관리</summary>
<p><code>POST /api/admin/sanctions</code> · 댓글 제재<br /><code>GET /api/admin/comment-blocks</code> · 스페이스 차단 목록</p>
<p><a href="docs/domains/sanction/api.md">상세 API 문서</a></p>
</details>
<details>
<summary><strong>Abuse Detection</strong> — 비정상 행동 · 관리자 검토</summary>
<p><code>GET /api/admin/abuse-detections</code> · 탐지 목록<br /><code>PATCH /api/admin/abuse-detections/{detectionId}/review</code> · 관리자 검토</p>
<p><a href="docs/domains/abuse-detection/api.md">상세 API 문서</a></p>
</details>
<details>
<summary><strong>Image Upload</strong> — 사전 서명 · 업로드 결과</summary>
<p><code>POST /api/uploads</code> · 업로드 예약<br /><code>GET /api/uploads/{uploadId}</code> · 업로드 상태</p>
<p><a href="docs/common/image-upload-pipeline.md">상세 API 문서</a></p>
</details>
<details>
<summary><strong>Stream</strong> — Dead Stream 관리</summary>
<p><code>GET /api/admin/dead-streams</code> · Dead Stream 목록<br /><code>POST /api/admin/dead-streams/{id}/replay</code> · 수동 재처리</p>
<p><a href="docs/domains/stream/api.md">상세 API 문서</a></p>
</details>

<br />

## 베타 테스트 및 트러블슈팅

<details>
<summary><strong>베타 테스트 결과 및 피드백</strong></summary>

2026년 베타 테스트 발표 자료 기준 주요 지표입니다.

| 항목 | 결과 |
|---|---:|
| 고유 사용자 | 116명 |
| 세션 | 205회 |
| 모바일 세션 비율 | 85% |
| 세션당 페이지 수 | 11.82 |
| LCP | 1.32초 |
| INP | 192ms |
| CLS | 0.05 |
| JavaScript 오류 | 201세션 중 1세션 |

- **로그인 보안 피드백:** 로그인 코드 발급 브라우저와 교환 브라우저를 바인딩하지 않았던 문제를 전용 쿠키와 Lua 검증으로 개선했습니다.
- **캘린더 UX 피드백:** 날짜만 있는 일정을 표현할 수 있도록 종일 일정 입력·표시를 추가했습니다.

</details>
<details>
<summary><strong>트러블슈팅 01 — 조기 마감 이벤트가 다시 응모를 받던 문제</strong></summary>

- **문제:** 조기 마감 직후 Redis 접수 상태 키가 유실되면, 복원 작업이 DB의 이전 `OPEN` 상태를 읽어 이벤트 접수를 다시 열 수 있었습니다.
- **원인:** Redis Gate 차단과 DB의 `CLOSING` 반영 사이의 시간차 및 키 복원의 비원자적 확인·적재가 경합했습니다.

- **해결:** 복원 검증·적재를 Lua 원자 처리로 변경하고 cutoff 존재 여부로 마감 중인 이벤트의 재개방을 방지했습니다. cutoff 유실 시에도 마감 상태를 재확인해 차단합니다.
- **검증:** 마감·복원 경합을 통합 테스트 4건으로 재현하고, cutoff가 유지된 상황에서 재개방되지 않음을 확인했습니다.

</details>
<details>
<summary><strong>트러블슈팅 02 — 매 정각 응모 응답이 지연되던 문제</strong></summary>

- **문제:** 정각마다 응모 응답이 최대 1.5~3초 지연되고, 1초 내 DB 반영률은 91.9%에 머물렀습니다.
- **원인:** 버스터블 애플리케이션 인스턴스에서 정각 CPU steal(빼앗긴 CPU 시간)이 최대 79%로 관찰되었습니다.

- **해결:** 서버를 `c6g.large`로 변경하고 1초 단위 지표로 재측정하여 서버 종류의 영향을 분리했습니다.
- **결과:** CPU steal 79% → 0%, 정각 응답 지연 1.5~3초 → 0.25~1초, 1초 내 DB 반영률 91.9% → 99.78%로 개선되었습니다.

</details>

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
| YouTube 채널 연결 | `OAUTH_YOUTUBE_CLIENT_ID`, `OAUTH_YOUTUBE_CLIENT_SECRET`, `OAUTH_YOUTUBE_API_KEY` |
| AI 퀴즈 생성 | `GEMINI_API_KEY` |
| 구독 인증 제출 | `SUBSCRIPTION_VERIFICATION_SUBMISSION_ENABLED=true`, `SUBSCRIPTION_GEMINI_API_KEY` |

`GEMINI_API_KEY` 없이도 서버는 시작할 수 있지만, AI 퀴즈 생성 호출은 Provider 오류가 발생합니다. 모델·타임아웃·재시도 설정은 [AI Quiz](docs/domains/quiz/README.md)와 [구독 인증](docs/domains/subscription-verification/README.md) 문서를 참고하세요.

### 4. API 확인 및 테스트

- 로컬 Swagger UI: <http://localhost:8080/swagger-ui/index.html>
- 개발 Swagger UI: <https://dev-api.cking.co.kr/swagger-ui/index.html>

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
| 권혁준 (팀장) | | |
| 김태연 | | |
| 이성집 | | |
| 장근창 | | |
| 정문구 | | |
| 정윤희 | | |
| 정자비 | | |
| 조성원 | | |
