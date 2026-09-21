# 작업 규칙

- 작업 시작 전 저장소 루트의 `AGENTS.md`를 반드시 읽고 모든 규칙을 따른다.
- `AGENTS.md`와 이 파일의 규칙이 충돌하면 `AGENTS.md`를 우선한다.

<!-- Claude Code 사용자가 팀 합의에 맞춰 이 파일을 작성·관리합니다. -->

# Ticle (Cking-BE) 작업 컨텍스트

SSOT 원본 7종(취합v1.5.4 / 통합 API 명세 v2.5 / DB 스키마 찐 최종 / 통합 RTM / 통합 작업단위 관리표 / 스프린트 트래커 / Convention)과 레포 문서(README.md, docs/README.md, CONTRIBUTING.md)를 전수 대조해 정리한 요약본. 판단이 갈리면 항상 원본을 다시 읽는다.

---

## 작업 원칙 — 리뷰·의견은 검증 후 반영

코드 리뷰 코멘트, 팀원 의견, **사용자(성집) 본인의 지시·의견**을 포함해 어떤 피드백도 무조건 수용하지 않는다.
- 반영 전에 **기술적으로 맞는지, 이 문서·SSOT 원본·실제 코드와 충돌하지 않는지** 직접 확인한다 (관련 코드·명세를 읽고, 필요하면 테스트로 검증)
- 틀렸거나 명세와 어긋나거나 더 나은 대안이 있으면 **수행 전에 근거(파일·명세 절·재현 결과)를 들어 반론**하고 확인받는다
- 맞는 지적이면 그대로 반영하되, "좋은 지적입니다" 같은 형식적 동의 없이 무엇을 왜 바꿨는지만 적는다
- 판단이 불확실하면 추측으로 진행하지 말고 무엇이 불확실한지 밝히고 묻는다
- PR 올리기 전에 재검토를 거쳐 어떤 테스트를 해도 문제가 없을 시 PR을 올린다
---

## 0. 프로젝트 개요

LG유플러스 유레카 4기 백엔드 부트캠프 4조 최종 프로젝트. 팀명 **cking**, 레포 `Cking-BE`. 크리에이터와 팬을 잇는 **이벤트 응모·추첨 플랫폼**. Redis(Lua 원자처리·캐시) + Redis Stream(비동기 반영) + MySQL(영속화) 기반 고동시성 백엔드.

**기술 스택**: Java 21 · Spring Boot 4.1.1 · Gradle · MySQL 8.4 · Redis 7.2 · Flyway · Docker Compose

**전체 서비스 흐름**
```
크리에이터 권한 승인 → 이벤트 생성·승인 요청 → 관리자 승인 → SCHEDULED
→ 자동 시작 스케줄러 → OPEN
→ 가상 사용자 선택 → 크리에이터 선택 → 출석·좋아요 미션 → 응모권 적립
→ 이벤트 조회 → 응모권 수량 선택 → Redis 원자 검증 → 차감 + Stream 발행
→ Consumer가 응모·이력 비동기 DB 저장
→ 마감 시작 → Gate 차단 + cutoffStreamId 확정 → CLOSING → Drain → CLOSED
→ Snapshot 생성 → Hash → Seed → INITIAL 추첨
→ Winner 저장 + Drawing COMPLETED + resultHash + DRAW_COMPLETED (동일 Tx)
→ 관리자 확인 → 결과 공개 → PUBLISHED → 당첨자 인앱 알림
→ 포기·자격상실 → RedrawRequest → 관리자 승인 → 재추첨 or 후보부족 기록 → 공개
```

---

## 1. 시스템 책임 경계 (취합 §1)

시스템 1~4는 **별도 서버가 아니라 하나의 Spring Boot 애플리케이션 내부 기능 모듈**이다. 시스템 간 연동은 **서비스 메서드 직접 호출만** 허용 — Internal HTTP API는 0개(API 명세 §15).

| 시스템 | 담당자 | 책임 |
|---|---|---|
| 시스템1 사용자·응모권 | 태연, 문구 | 가상 사용자 식별, 출석·좋아요 미션 조회/검증, 미션별 중복보상 방지, 응모권 적립 **요청**, 잔액·이력 조회. **Redis Balance를 직접 변경하지 않음** |
| 시스템2 이벤트·응모 | **성집(B트랙)**, 자비(A트랙) | 이벤트 조회, 응모 처리(Lua), 잔액검증·차감, 멱등 결과저장, Stream 발행/소비, Entry·Ledger·Balance 저장, 자동시작/자동마감 감지, Gate 차단, cutoff 확정, Drain, 정합성 검증, Dead Stream, `OPEN→CLOSING→CLOSED` 전이, CLOSED 후 Snapshot 서비스 호출 |
| 시스템3 Snapshot·추첨 | 윤희, 성원 | Snapshot 생성·단일성·멱등·무결성·Hash, Seed, INITIAL/REDRAW 실행, Drawing·Winner 저장, 중복실행 방지, 재현 검증, 공개상태 변경, `CLOSED→DRAW_COMPLETED` 요청, Snapshot 누락복구 스케줄러. **Redis Stream 내부상태를 직접 확인하지 않음** |
| 시스템4 운영·공개 | 혁준, 근창 | Creator 권한 신청/승인/거절, Event 생성·승인·거절 운영, 수동마감 요청 검증, 결과 관리자조회·공개요청, 개인정보 마스킹, Winner 후속상태, 인앱알림, RedrawRequest 전 과정, 재현검증 요청, `DRAW_COMPLETED→PUBLISHED` 요청. **응모·추첨 알고리즘에 직접 개입 안 함** |

**연동 원칙**: 서비스 메서드는 Retry·Scheduler·동시실행으로 중복 호출될 수 있으므로 상태변경은 멱등하게. **호출 사실 자체를 데이터 확정 근거로 쓰지 않고, DB에 저장된 Event 상태를 모듈 간 계약 기준으로 사용**한다(시스템3은 호출받아도 `status == CLOSED`를 다시 검증).

---

## 2. 내 담당 범위 (성집 / rien00, 시스템2 B트랙 = API·영속화)

**담당 작업단위**: T2-01, T2-02, T2-04, T2-05, T2-06, T2-07, T2-08 (T2-03은 자비)
**담당 요구사항**: FR-P2-001~005, 007, 009~026, 037~041, 044 (RTM 기준 31건 중 FR-P2-042는 자비에게 이관)

**범위 밖**: 응모 Lua 스크립트 본체(T2-03 / FR-P2-027~036, 자비), EARN 중복방지 Lua(FR-P2-006, 자비), 시딩 스크립트(FR-P2-042, 자비), Lua 부하테스트(FR-P2-043, 자비), 시스템1·3·4 전체.

### 작업단위 상세 (통합 작업단위 관리표)

| ID | 결과물 | 선행 | 요구사항 | 우선순위 |
|---|---|---|---|---|
| T2-01 | 이벤트 조회 API·Redis Key 초기 적재·EARN 연동·테스트 | T1-01, T1-04, T4-01 | FR-P2-001~008 | P0 |
| T2-02 | 응모 Controller·요청 검증·응답 매핑 | T2-01 | FR-P2-009~011 | P0 |
| T2-03 | *(자비)* Gate/잔액/멱등 Lua·동시성 테스트 | T2-02 | FR-P2-027~036 | P0 |
| T2-04 | Consumer·DB 반영·XACK·awaitDrain 테스트 (DB Commit 후 XACK) | T1-04, T2-03 | FR-P2-012~016 | P0 |
| T2-05 | XCLAIM·Dead Stream 저장·replay 테스트 | T2-04 | FR-P2-017~020 | P1 |
| T2-06 | 정합성 배치·COMPENSATE·재동기화 테스트 | T2-04 | FR-P2-021~022 | P1 |
| T2-07 | Gate·Barrier·cutoff·Drain·CLOSED 전이 테스트 (시스템3 Snapshot 호출) | T2-03, T2-04 | FR-P2-023~026 | P0 |
| T2-08 | closing-status·시딩·운영/부하 테스트 | T2-05, T2-06, T2-07 | FR-P2-037~044 | P1 |

### 현재 스프린트 — 성집 Sprint 1 (2026-09-16 → 09-22, 진행 중, 완료율 0%)
목표: 파트2 B트랙 핵심 흐름 구현. 세부 항목:
- T2-01a 이벤트 목록/상세 조회 API + `cache:event` / T2-01b 잔액조회 API / T2-01c `TicketEarnService.earn()` + 중복적립방지
- T2-02 `POST /api/events/{eventId}/entries`, ticketCount 1~100 사전검증, Lua 반환코드 10종 → HTTP 매핑(스텁), 테스트
- T2-04a EARN/Entry Consumer 등록 + 멱등 insert / T2-04b XACK + `pendingCount` 내부 API
- T2-05 실패 메시지 PEL 확인, 재기동 후 PEL 이어받기, 장기 미처리 → dead stream 재발행, 운영자 수동 replay
- T2-06 Redis 잔액 vs DB 잔액(+이력 합계) 5분 주기 비교, 불일치 알림/로그, 배치 지속 스케줄 확인
- T2-07a `EventLifecycleScheduler`(10초 tick, 자동마감 대상 조회) / T2-07b `EventClosingService`(startClosing/getClosingStatus, Snapshot 생성 연동)
- T2-08 수동마감 권한/상태 재검증, 추첨결과 공개 API(FR-P2-044), CommonErrorCode 리네임 반영 확인, 비기능 검증

**다른 팀원 Sprint 1 (전부 2026-09-16 시작)**: 자비 T2-03(→09-17), 태연 T1-01(→09-17), 문구 T1-02(→09-17), 근창 개발서버 구축(→09-17), 윤희·성원 마감연동/Snapshot/INITIAL Drawing(→09-23), 혁준 Creator/Event 운영·공개·Redraw(→09-23). 계획됨: 태연 Sprint2 T1-04(09-17~19), 문구 Sprint2 T1-03(09-17~18).

---

## 3. 공통 용어·데이터 기준 (취합 §2)

**식별자**: `userId`, `creatorId`, `eventId`, `requestId`, `drawingId`, `snapshotId`, `redrawRequestId`. API의 `userId`는 DB `member_id`에 대응. DB `BIGINT` PK/FK 대응 식별자는 Java `Long` / JSON number (문서 예시의 `"user-001"`은 샘플 표기일 뿐).

**requestId**: UUID 기반. 네트워크 오류·버튼 연타·새로고침으로 재전송되면 **같은 requestId 사용**. 같은 requestId에 다른 payload면 멱등성 충돌.

**응모권**: 스코프는 `userId + creatorId` (Event 단위 아님). 같은 크리에이터의 여러 Event가 같은 잔액을 공유한다. **보유 상한 없음, 만료 없음.** Ledger는 append-only(`EARN`/`SPEND`/`COMPENSATE`), DB Balance는 Ledger 기반 검증가능 Projection. Redis와 DB를 한 Tx로 묶지 않는다.

**Redis Key 미존재 정책**: 미존재를 정상으로 해석 금지. Gate Key 없음 → `GATE_NOT_LOADED`(OPEN으로 간주 금지), Balance Key 없음 → `BALANCE_NOT_LOADED`(0으로 간주 금지). DB 기준으로 복구 후 재처리.

**시간**: 업무 시각은 서버 시각으로 판정(Lua는 Redis TIME 등). **클라이언트 시각 사용 금지.** DB는 UTC 저장, 화면에서만 변환. 응모 가능 기간은 `startAt <= now < endAt` (`now == endAt`부터 신규 응모 불가). `endAt`(신규응모 종료 업무시각) ≠ `closedAt`(Drain까지 끝나 CLOSED 확정된 실제시각).

### Redis 키 정리
| 용도 | 키 | 비고 |
|---|---|---|
| 응모권 잔액 | `ticket:balance:{creatorId}:{userId}` | Event 미포함 |
| 이벤트 Gate 상태 | `event:status:{eventId}` | 수동마감 시 DB `event.status`와 함께 갱신 |
| 응모 멱등 | `idem:{request_key}` (RTM 표기는 `idem:{requestId}`) | **TTL 1시간**, 최종 안전망은 `event_entry.request_id UNIQUE` |
| 미션 멱등 | `idem:mission:{requestId}` | **TTL 24시간**, 성능 최적화용 캐시(정합성은 DB UNIQUE) |
| 미션 중복적립 가드 | `mission:earn-guard:{userId}:{missionType}:{creatorId}:{yyyymmdd}` | `SETNX`, KST 기준 |
| 조회 캐시 | `cache:event` (`event:list:...`, `event:detail:{eventId}`) | **TTL 5초**, 절대 `endAt` 초과 금지 |
| EARN Stream | `stream:ticket-earned` | Consumer Group `cg:ticket-earn` |
| Entry Stream | `stream:ticket-deducted` | Consumer Group `cg:ticket-history` |

> MVP에서 두 Consumer Group 모두 concurrency = 1 고정(병렬 필요 시 이벤트별 Stream 분리 재검토).
> 차감 명령: 취합 §5.3은 "ticketCount만큼 차감"으로만 기술, RTM FR-P2-032는 `DECR` 표기. 의미상 `DECRBY ticketCount`가 맞으나 문서 표기가 갈리므로 Lua 구현 시 자비와 최종 확인.

---

## 4. Event 생명주기 (취합 §3, API §11, DB #8)

```
DRAFT → PENDING_APPROVAL → SCHEDULED → OPEN → CLOSING → CLOSED → DRAW_COMPLETED → PUBLISHED
PENDING_APPROVAL → REJECTED → DRAFT
```

| 상태 | 의미 |
|---|---|
| `DRAFT` | 크리에이터가 작성·수정 중 |
| `PENDING_APPROVAL` | 관리자 승인 대기 |
| `REJECTED` | 승인 거절됨 |
| `SCHEDULED` | 승인됐으나 시작 전 |
| `OPEN` | 신규 응모 가능 |
| `CLOSING` | 신규응모 차단 + 기존 승인응모 DB 확정 중 |
| `CLOSED` | 마감 이전 정상응모 전부 DB 확정, Snapshot 생성 가능 |
| `DRAW_COMPLETED` | 최초 공식 추첨 및 Winner 저장 완료 |
| `PUBLISHED` | 최초 공식 추첨 결과 공개 완료 |

**추첨 실행 상태는 `Event.status`에 두지 않는다** — `DRAWING`/`DRAWN` 상태 없음. Drawing 상태(`READY→RUNNING→COMPLETED/FAILED`)는 별도이며 추첨 중에도 Event는 `CLOSED` 유지.

### EventCommandService (§3.5~3.6) — 상태 변경 단일 진입점
```java
requestApproval(eventId); approve(eventId); reject(eventId, reason); changeToDraft(eventId); open(eventId);
startClosing(eventId, cutoffStreamId);  // OPEN 확인 → cutoffStreamId 기록 → OPEN→CLOSING
completeClosing(eventId);               // CLOSING 확인 → CLOSED → closedAt 기록
completeDrawing(eventId);               // CLOSED → DRAW_COMPLETED (INITIAL 결과확정과 동일 Tx)
publish(eventId);                       // DRAW_COMPLETED → PUBLISHED
```
- `event.setStatus(...)` 직접 호출 금지, Repository로 status 직접 변경 금지
- 책임: 현재 상태 조회 → 허용 전이 검증 → 동시 변경 방지 → 상태 변경 → 관련 시각·필드 기록. **Drain/차감/Snapshot/추첨 로직은 담당하지 않음**
- `awaitDrain()`을 수행하지 않는다 — Drain은 시스템2 마감 오케스트레이션 책임
- REDRAW 완료·공개 시 `completeDrawing`/`publish`를 호출하지 않는다(Event는 PUBLISHED 유지, Drawing.visibility만 변경)

### 생성·수정·삭제·승인 (§3.3~3.4)
- 생성 직후 `DRAFT`. 수정 가능 상태는 `DRAFT`, `REJECTED`(수정 시 `REJECTED → DRAFT`). `PENDING_APPROVAL`에서 수정 불가, `OPEN` 이후 응모·추첨 조건 변경 불가
- 삭제는 Soft Delete(`deletedAt`), 가능 상태는 `DRAFT`·`REJECTED`. 삭제 이벤트는 목록·상세·응모 대상에서 제외
- `endAt`이 이미 지난 이벤트는 승인 불가. 승인 시 `startAt`이 이미 지났어도 `OPEN`으로 건너뛰지 않고 `SCHEDULED`를 거친 뒤 자동시작 스케줄러가 `OPEN`으로 전환

### 자동 시작 스케줄러 (§3.7, FR-33)
별도 컴포넌트가 아니라 **마감 스캐너와 동일한 `EventLifecycleScheduler`로 통합**. 한 tick에서 (1) `status == SCHEDULED AND startAt <= now < endAt` → `open(eventId)` 먼저, (2) 그다음 마감 대상 조회·처리. 마감이 Redis 원자처리로 더 오래 걸릴 수 있으므로 순서 고정. 중복·다중 인스턴스 실행에도 `SCHEDULED → OPEN`은 **1건만** 성공해야 함.

### 목록·상세 조회 (§3.9, API §5.1~5.2)
`event.status`가 시간 계산보다 우선한다.

| Event.status | 시간 조건 | displayStatus |
|---|---|---|
| `SCHEDULED` | 무관 | `UPCOMING` (예정) |
| `OPEN` | `startAt <= now < endAt` | `IN_PROGRESS` (진행 중) |
| `OPEN` | `now >= endAt` | `CLOSED` |
| `CLOSING` / `CLOSED` / `DRAW_COMPLETED` / `PUBLISHED` | 무관 | `CLOSED` (마감) |

- `NEEDS_CONFIRMATION`은 사용하지 않는다. `deletedAt IS NULL`만 조회
- 상세 제공 항목: 응모 기간, 현재 상태, 크리에이터 정보, 당첨 인원, 추첨 방식, **현재 보유 응모권 수**
- 보유 응모권 수는 클라이언트가 `eventId`만 전달 → 서버가 Event의 `creatorId`를 resolve → `ticket:balance:{creatorId}:{userId}` 조회. 클라이언트는 `creatorId`를 보내지 않는다

---

## 5. 미션·응모권 적립 (취합 §4)

- **가상 사용자**: 회원가입·로그인 없음, 사전 생성된 사용자 선택. 최소 정보 `userId, name, phone, email`
- **출석 미션**: `userId + creatorId + 날짜` 기준 하루 1회, 성공 시 해당 크리에이터 전용 응모권 1장. `periodKey`는 KST `yyyyMMdd`(Redis 가드 키) / `YYYY-MM-DD`(API 바디) — 레이어별 표기 차이일 뿐 상충 아님
- **좋아요 미션**: `userId + creatorId + periodKey` 기준 **하루 1장**. 같은 크리에이터의 여러 콘텐츠에 좋아요해도 추가 지급 없음, `contentId`는 판정 기준에 미포함. 좋아요 취소해도 회수 안 함, 재좋아요도 추가 지급 없음. 1차 MVP는 Mock 검증
- **미션 멱등성**: 동일 `requestId` 재전달 → 추가 지급 없이 기존 성공 결과 반환. 동일 `requestId` + 다른 Payload → `IDEMPOTENCY_CONFLICT`. **새 `requestId`라도 동일 Business Key면 중복 지급 안 함**

### EARN 연동 (§4.5) — 서비스 메서드 호출로 확정, HTTP 논의 폐기
```java
TicketEarnService.earn(EarnCommand)
```
`EarnCommand` 필드: `requestId`(UUID), `userId`, `creatorId`, `missionType`, `missionId`, `periodKey`, `missionKey`, `amount`
Business Key: `userId + creatorId + missionId + periodKey`

처리: Lua 하나에서 **중복 적립 검증 → Redis Balance 증가 → 멱등 결과 저장 → EARN Stream 발행**을 원자 실행 → Consumer가 `Mission Completion` + `EARN Ledger` + `DB Balance`를 **동일 DB Tx**로 저장 → **Commit 이후에만 XACK**.

**성공 기준 = Redis Balance 증가 + `stream:ticket-earned` XADD 완료** (DB Consumer 반영은 기다리지 않음 — SPEND와 동일 패턴).

결과 코드 6종 및 미션완료 API에서의 HTTP 매핑(API §4.2):

| 결과코드 | HTTP | 의미 |
|---|---|---|
| `EARN_ACCEPTED` | 202 | 최초 성공 |
| `ALREADY_PROCESSED` | 200 | 동일 requestId 재요청, 기존 결과 재사용 |
| `DUPLICATE_MISSION` | 409 | 새 requestId + 동일 Business Key |
| `REQUEST_ID_CONFLICT` | 409 | 동일 requestId + 다른 요청 내용 |
| `EARN_PROCESSING_FAILED` | 503 | 명확한 Redis 오류 |
| `EARN_STATUS_UNKNOWN` | 504 | 타임아웃 등 처리 여부 불명확 |

> EARN 자체는 Service Method이므로 별도 외부 HTTP Status를 정의하지 않고, 미션 완료 API가 위 표로 매핑한다.
> 타임아웃 시 시스템1은 **새 requestId를 만들지 않고 동일 `requestId + missionKey`로 재시도**한다.
> 보류: `/api/internal/**` 접근제어(구 FR-35) — 서비스 메서드 호출로 정리되면 HTTP 엔드포인트 자체가 사라지므로 별도 접근제어를 만들지 않는 방향. 공통 코드 작업 시점에 확정(블로커 아님).

---

## 6. 응모 처리 (취합 §5, API §5.3~5.4)

**요청**: `eventId`, `userId`, `requestId`, `ticketCount`
- `ticketCount >= 1`. 컨트롤러 사전검증에서 "1 이상 정수" + **방어용 상한 100장** 확인 (보유 상한이 아니라 비정상 입력 방어값, 최종 검증은 Lua가 실제 잔액 기준)
- `requestId`는 클라이언트 생성 UUID, B트랙은 **형식만** 저비용 사전검증 (실제 중복 판정은 Lua)
- 같은 Event에 여러 번 응모 가능(응모마다 다른 requestId). 최종 가중치는 그 Event에 사용한 응모권 총합

**응모 가능 조건 (§5.2)**: `Event.status == OPEN`, `startAt <= now < endAt`, 삭제되지 않음, `userId` 유효, `Event.creatorId == Ticket creatorId`, `ticketCount >= 1`, 보유 응모권 >= ticketCount, Gate == OPEN

**Lua 원자 처리 (§5.3)** — 순서가 v1.5.4에서 변경됨(§18 수정 이력):
```
requestId 멱등성 확인   ← 가장 먼저
→ Event Gate 확인
→ 시각 확인
→ request fingerprint 확인
→ Balance 확인
→ ticketCount만큼 차감
→ 성공 결과 저장
→ Entry Stream XADD (stream:ticket-deducted)
```
- **멱등 확인이 최우선**: 동일 requestId + 동일 payload가 이미 성공했으면 현재 Gate 상태·마감 시각과 무관하게 `DUPLICATE_REPLAY`로 기존 성공 결과 반환. 동일 requestId + 다른 payload면 `IDEMPOTENCY_CONFLICT`
- 신규 요청만 Gate → 마감 시각 → 잔액 → 차감 → Stream 발행 → 멱등 결과 저장 순서로 처리
- 마감 판단 근거(FR-P2-031): **`event.status == OPEN && now < endAt` 두 조건 AND** — 자연마감(배치 지연 틈)은 시간 비교가, 수동마감은 status 비교가 막는다

**결과 코드 10종 (§5.4)**
```
SUCCESS, DUPLICATE_REPLAY, EVENT_NOT_OPEN, EVENT_CLOSED,
INSUFFICIENT_BALANCE, INVALID_TICKET_COUNT, IDEMPOTENCY_CONFLICT,
GATE_NOT_LOADED, BALANCE_NOT_LOADED, SYSTEM_ERROR
```
- `DUPLICATE_REPLAY`는 **실패가 아니다** — 기존 성공 결과 반환
- `EVENT_NOT_OPEN`(OPEN 이전) ≠ `EVENT_CLOSED`(게이트 CLOSE 이후)
- HTTP 매핑은 공통 기준(400/403/404/409/500/503)에 맞춰 1:1로 하되 **구체 매핑값은 문서에서 지정하지 않음 → 구현 시 확정**
- `CREATOR_MISMATCH`는 삭제됨(서버가 eventId에서 단일 creatorId를 resolve하므로 비교 대상이 없는 죽은 코드였음)

**응답 스키마 (API §5.3)**: `SUCCESS`와 `DUPLICATE_REPLAY`는 **동일한 성공 Response 스키마**를 쓴다. 필드는 `code`, `requestId`, `eventId`, `accepted` 4개이며 **`entryId`는 반환하지 않는다**. `accepted = true`는 요청이 수락됐다는 뜻이지 `event_entry` 저장 완료가 아니다 — 실제 `entryId`와 DB 반영분은 `GET /api/events/{eventId}/entries/me`로 조회.

**Stream Consumer (§5.5~5.7)**: `Entry INSERT` + `SPEND Ledger INSERT` + `DB Balance UPDATE`를 동일 Tx → Commit 후에만 XACK. at-least-once 전제이므로 `event_entry.request_id UNIQUE`로 방어하되, **UNIQUE 위반이라고 무조건 XACK하지 않고** 기존 데이터가 실제 동일 요청인지 확인한 경우에만 정상 중복으로 판단. DB Balance는 unsafe read-modify-write 금지 — 원자 UPDATE 또는 적절한 Lock 사용, Deadlock 시 재시도하되 정상 Commit 전엔 XACK 안 함.

> v3 트래커의 `application_history.request_key`는 `event_entry`의 **구 명칭**(v2.1에서 개명). 실제 대상은 `event_entry.request_id UNIQUE`이며 별도 테이블은 필요 없다.

---

## 7. 이벤트 마감 (취합 §6) — 내 핵심 작업

### CLOSED 상태 계약 (§6.1) — 5개 조건
```
1. 신규 응모가 차단되었다
2. 마감 기준점까지 승인된 정상 응모가 모두 DB에 영속화되었다
3. 해당 마감 범위의 미처리 응모가 존재하지 않는다
4. 해당 마감 범위의 미해결 Dead Stream 데이터가 존재하지 않는다
5. Snapshot을 생성해도 안전하다
```

### 마감 트리거 (§6.2)
- **자동**: `status == OPEN AND endAt <= now` 탐색, 배치 주기 **10초 확정** (`EventLifecycleScheduler` tick 후반부)
- **수동**: 시스템4가 권한·조건 검증 후 시스템2 마감 서비스 호출
- **비동기 처리**: Gate 차단까지만 동기 → `202 Accepted` 즉시 응답 → Drain은 백그라운드
- 자동·수동이 동시에 발생해도 실제 마감 작업은 **하나만** 시작
- 시스템4의 마감완료 폴링: **`GET /admin/events/{id}/closing-status`** (관리자 화면용). `CLOSING`/`CLOSED` 이분값만 반환하고 진행률·미처리건수는 반환하지 않음. 내부 구현은 시스템4 모듈이 시스템2 서비스 메서드를 **인프로세스 직접 호출**(HTTP 아님)해 `event.status`를 매핑

### 마감 경계 원자성·cutoff (§6.3, FR-18)
`cutoffStreamId` = 마감 기준 시점까지 정상 승인되어 Drain 대상에 포함해야 하는 **마지막 Stream 경계**.
```
이미 cutoff 존재?
├─ YES → 기존 cutoff 반환
└─ NO  → 신규 응모 Gate 차단 → cutoffStreamId 결정 → Redis 보존 → 최초 cutoff 반환
```
- **barrier 방식**: Gate를 CLOSED로 바꾼 뒤 동일 Stream에 `EVENT_ENTRY_CLOSED` barrier 이벤트를 XADD하고, 그 barrier의 Stream ID를 `cutoffStreamId`로 저장
- 차단 내부 API 응답 필드 최소: `alreadyClosed`, `gateWasMissing`, `cutoffStreamId`
- 마감 재시도 시 **새 cutoff를 만들지 않고 최초 cutoff 재사용**. DB에 저장된 `cutoffStreamId`가 마감 복구의 영속 기준

### 마감 Transaction 분리 (§6.4~6.5)
```
[Redis 원자 처리]  Gate 차단 + 최초 cutoffStreamId 확정·보존
       ↓
[짧은 DB Tx 1]     status == OPEN 확인 → cutoffStreamId 저장 → OPEN→CLOSING → COMMIT
       ↓
[Tx 밖]            awaitDrain(eventId, cutoffStreamId)
       ↓
[짧은 DB Tx 2]     status == CLOSING 확인 → CLOSING→CLOSED → closedAt 기록 → COMMIT
```
- **마감 전체를 하나의 긴 Tx로 묶지 않는다.** `awaitDrain()` 동안 DB Row Lock·Connection 장시간 점유 금지
- Redis Gate가 이미 닫혔는데 DB Tx가 실패해도 **Gate를 자동 재개방하지 않는다**
- DB `event.status` 갱신과 Redis Gate 키(`event:status:{eventId}`) 갱신은 한 요청 흐름 안에서 모두 일어나되, **하나의 원자 Tx로 묶으라는 뜻은 아니다**(위 분리 순서 그대로)

### awaitDrain (§6.6)
해당 Event의 **cutoff 범위**에 속한 모든 정상 응모가 DB에 반영됐는지 확인. 최소 확인 대상: Consumer 처리 지연, cutoff 범위 PEL, cutoff 범위 미처리 메시지, cutoff 범위 미해결 Dead Stream, cutoff 이하 정상응모의 DB 영속화 여부.
- **`XPENDING == 0` 하나만으로 완료 판단 금지**
- 공용 Stream일 때 **Stream 전체 `lag == 0`만으로 특정 Event의 Drain 완료 판단 금지**
- 이벤트별 `pendingCount` 반환 **내부 API로 구현**하며 시스템2 내부 구현으로 유지. 시스템3은 직접 호출하지 않고 `awaitDrain()` 내부에 흡수 (API가 사라지는 게 아님)

### Drain 성공·실패 (§6.7~6.8)
성공 조건: `status == CLOSING` AND cutoff 범위 정상승인 응모 전부 DB 반영 AND 미처리/미ACK 없음 AND 미해결 Dead Stream 없음 → `completeClosing(eventId)`.

실패 시: **`CLOSING` 유지** (오류 기록 → 재시도 → 필요 시 운영자 알림). **강제 CLOSED 전환 절대 금지.** 서버 재기동 시 `CLOSING` Event를 탐색해 DB 저장 `cutoffStreamId`로 Drain 재개. DB에 cutoff 저장 전 실패한 시도는 Redis 보존 cutoff로 `CLOSING` 진입부터 재시도. **마감 중 닫힌 Gate를 자동 재개방하지 않는다.**

> ⚠️ **임의 결정 금지**: Drain 재시도 간격·최대 대기시간 구체값은 "시스템2가 자율 결정"이라고만 되어 있고 실제 값은 미정(FR-11b). 구현 전 확정 필요.

### CLOSED ↔ Snapshot 분리 (§7.5, §7.1)
`CLOSING → CLOSED` Commit과 Snapshot 생성을 **하나의 긴 Tx로 묶지 않는다**. Commit 성공 후 `snapshotService.createOfficialSnapshot(eventId)` 호출. **Snapshot 생성에 실패해도 이미 확정된 CLOSED를 되돌리지 않는다** — 시스템3의 Snapshot 누락 복구 스케줄러(MVP 약 1분 주기, `CLOSED && Snapshot 없음` 탐색)가 보완.

---

## 8. Snapshot·추첨·재추첨 (취합 §7~§12) — 범위 밖이지만 계약 이해용

- **Snapshot**: `Event.status == CLOSED`에서만 생성(`OPEN`/`CLOSING` 불가). `UNIQUE(event_id)`로 Event당 1개, 재추첨도 최초 Snapshot 재사용. 사용자별 응모권 합산(`ticketCount <= 0` 제외), **`userId ASC` 등 고정 순서로 정렬**해 조회 순서가 Hash·결과에 영향을 주지 않게 한다. 생성 후 불변
- **Snapshot Hash**: `eventId, userId, ticketCount, winnerCount, drawMethod, algorithmVersion`을 정규화 → SHA-256 등. 추첨 직전 재조회·재계산해 **저장 Hash == 재계산 Hash일 때만 추첨**, 불일치 시 중단
- **Seed**: Drawing마다 결정적 재현용 Seed. 동일 Drawing Retry는 Seed 변경 안 함, 새 REDRAW Drawing만 새 Seed
- **DrawingEngine**: `DrawOutput draw(DrawInput)` — DB·Redis 등 외부 의존 없는 순수 로직. `DrawInput(eventId, snapshotId, snapshotHash, seed, algorithmVersion, winnerCount, candidates)`, `DrawCandidate(userId, ticketCount)`
- **알고리즘**: MVP `drawMethod = WEIGHTED`, `algorithmVersion = WEIGHTED_V1`. 응모권 수 비례 확률, 동일 사용자 중복 당첨 금지, 당첨 후 후보 제거, 정확히 `winnerCount`명, `winnerCount > candidateCount`면 추첨 불가, 동일 Snapshot+Seed+AlgorithmVersion → 동일 결과
- **INITIAL 결과 확정 Tx**: Winner 전체 저장 + `Drawing.status = COMPLETED` + `resultHash` 저장 + `completeDrawing(eventId)`(`CLOSED→DRAW_COMPLETED`)를 **동일 DB Tx**. 하나라도 실패하면 전체 Rollback. 일부 Winner만 저장된 채 COMPLETED 금지. REDRAW에는 적용 안 함
- **Retry**: 새 Drawing 생성 안 함, 기존 Snapshot/Seed/AlgorithmVersion/winnerCount/DrawingId 재사용. 시스템 오류 등 일시 실패만 Retry 가능하고, 후보 부족·Snapshot Hash 불일치 등 **입력·검증 조건 미충족은 동일 조건 Retry 금지**
- **재현 검증**: 현재 데이터가 아니라 **실행 당시 확정된 입력** 기준. 입력 = Snapshot, Snapshot Hash, Seed, Algorithm Version, Exclusion List, winnerCount. 검증 대상 = Snapshot Hash, Input Hash, Winner, Rank, Result Hash. 결과 `VERIFIED`/`VERIFICATION_FAILED`, 이력 append-only, 검증 중 Event·Snapshot·Drawing·Winner 변경 없음. **관리자 수동 실행**이며 `status = COMPLETED`인 Drawing만 대상(INITIAL/REDRAW 무관, READY/RUNNING/FAILED 제외)
- **공개**: Drawing 완료 직후는 `COMPLETED + PRIVATE`. INITIAL 공개는 `Drawing.visibility PRIVATE→PUBLIC` **과** `Event.status DRAW_COMPLETED→PUBLISHED` **둘 다** 반영돼야 완료로 인정. REDRAW 공개는 visibility만 바꾸고 Event는 PUBLISHED 유지
- **개인정보**: 일반 사용자·Creator에게는 마스킹만(`권혁준 → 권*준`, `010-1234-5678 → 010-****-5678`). 원문은 관리자와 당첨자 본인만. **DB에는 원본 저장, 응답 단계에서 마스킹**
- **인앱 알림**: MVP는 SMS/Email/Push 미연동. Drawing이 `PRIVATE → PUBLIC`이 된 **이후에만** 생성, 중복 공개 요청에도 중복 생성 금지. `readAt == null`이면 미읽음. 본인 알림만 조회
- **Winner 후속 상태**(`WinnerManagement`): `SELECTED → RECEIVED/DECLINED/DISQUALIFIED`. 당첨자는 DECLINED만, 관리자는 RECEIVED·DISQUALIFIED. 세 상태는 종결이며 SELECTED로 되돌리지 않음. Winner 자체는 불변
- **재추첨**: `DECLINED`/`DISQUALIFIED` 결원만 대상, 자동 재추첨 없음. `vacancyCount`는 **서버가 계산**(클라이언트 값 불신), 관리자가 임의로 늘리거나 후보 부족을 이유로 줄일 수 없음. 하나의 결원은 진행 중인 요청 하나만 점유. 승인상태(`REQUESTED/APPROVED/REJECTED`)와 실행상태(`PENDING/EXECUTED/INSUFFICIENT_CANDIDATES/FAILED`)를 분리. `RedrawRequest 1 : 0..1 REDRAW Drawing` (`UNIQUE(redraw_request_id)`)
- **재추첨 후보** = 원본 Snapshot 후보 − 해당 Event에서 **한 번이라도** Winner가 된 모든 사용자(DECLINED·DISQUALIFIED 포함)
- **후보 부족**: `candidateCount < vacancyCount`면 실행하지 않고 `executionStatus = INSUFFICIENT_CANDIDATES`. **부분 재추첨 없음**, REDRAW Drawing 미생성, 동일 조건 재실행 없음, 부족 인원은 최종 공석, 해당 이벤트 재추첨 절차 종료. 기술 장애 `FAILED`와 구분

---

## 9. Redis Stream 장애·정합성·캐시 (취합 §13, API §9)

**Stream 장애 처리**: at-least-once 전제 → `DB UNIQUE` + Consumer 멱등으로 중복 방지, **DB Commit 이후에만 XACK**. PEL 장기 미처리는 **`XCLAIM`으로 소유권 회수**해 재처리하고 서버 재기동 후에도 이어받는다. 최대 재시도 후 Dead Stream 이동, **제한된 횟수 재시도 후 수동 replay**로 처리. 원본 payload와 원본 Stream ID 보존.

Dead Stream 관리 항목: 원본 Stream ID, 원본 Payload, `requestId`, `eventId`, `userId`, 실패 원인, 재시도 횟수, 마지막 실패 시각, 해결 상태(`UNRESOLVED`/`RESOLVED`). **해당 Event의 cutoff 범위에 `UNRESOLVED`가 있으면 CLOSED로 바꿀 수 없다.**

중복 기준은 **A안 확정**: `UNIQUE(source_stream_id, stream_type)` — 동일 원본 메시지는 한 row로 유지하고 재시도 시 `retry_count` 증가 + `last_failed_at` 갱신. 재시도 상세 이력이 필요해지면 별도 이력 테이블로 분리.

**Redis·DB 정합성**: Redis Balance vs DB Balance·Ledger 합계를 **5분 주기** 비교. 정상적인 Redis→Stream→DB 비동기 지연을 **즉시 오류로 판단하지 않고**, 일정 시간 이상 지속되거나 반복 검사에도 사라지지 않는 불일치만 이상으로 판단. 불일치 시 로그·운영 알림·원인 기록. **최종 복구 기준은 DB Ledger.** 보정은 기존 Ledger 수정·삭제 없이 `COMPENSATE` Ledger를 append-only 기록(보정량/보정 사유/처리 전 값/처리 후 값/처리 시각) 후 Redis Balance를 DB 기준값으로 재동기화.

**캐시**: 이벤트 목록·상세만 캐시. TTL 5초, 절대 `endAt` 초과 금지. Event 상태 변경 시 관련 캐시 무효화. **캐시의 Event 상태를 응모 승인 여부의 최종 판단 근거로 쓰지 않는다.**

---

## 10. API 계약 (통합 API 명세 v2.5, 2026-09-16)

**External REST API 48개** (Master Table 49행 중 No.9 `TicketEarnService.earn()`은 Service Method라 제외). Internal HTTP API **0개**.
구성: 공통 4 / 시스템1 4 / 시스템2 4 / 시스템3 7 / 시스템4 29

### 시스템2 External API (내 담당 4개)
| Method | Endpoint | 기능 | 멱등성 |
|---|---|---|---|
| GET | `/api/events` | Event 목록 (`status`, `creatorId`, `page`, `size`) | 불필요 |
| GET | `/api/events/{eventId}?userId=` | Event 상세 (+`myTicketBalance`) | 불필요 |
| POST | `/api/events/{eventId}/entries` | 응모 | `requestId` |
| GET | `/api/events/{eventId}/entries/me` | 내 응모 조회 (cursor) | 불필요 |

연관: `POST /api/events/{eventId}/close`(수동 마감, 202, CREATOR 본인/ADMIN 전체), `GET /api/admin/events/{eventId}/closing-status`(ADMIN, `CLOSING`/`CLOSED`만). **`cutoffStreamId`·`pendingCount`·Stream lag·진행률은 외부에 절대 노출하지 않는다.**

### 공통 사용자 식별 (§1.4)
인증 시스템이 없으므로 **USER·CREATOR·ADMIN 모두 `userId`를 요청에 직접 전달**한다. GET/DELETE는 Query Parameter, POST/PATCH는 Request Body, `/api/me/**`도 동일. ADMIN API는 `userId`의 Member 존재 확인 + `member.role == ADMIN` 검증 → 없으면 `RESOURCE_NOT_FOUND`, ADMIN이 아니면 `FORBIDDEN`. 관리자 심사 API는 검증된 `userId`를 `reviewedBy`에 기록.

### 페이지네이션 (§1.7) — 데이터 성격별로 나눔
**Page 방식** (Event 목록, Creator 신청 목록, Event 승인대기, Creator의 내 Event 목록): `page`는 0부터, `size` 기본 20 / 최대 100. 응답 `{items, page, size, totalElements, totalPages, hasNext}`. **PK를 2차 정렬(tie-breaker)로 반드시 포함**:
- Event 목록 / Creator의 내 Event 목록 → `createdAt DESC, eventId DESC`
- 내 Creator 신청 → `requestedAt DESC, applicationId DESC`
- 관리자 Creator 신청 목록 → `requestedAt ASC, applicationId ASC`
- 관리자 Event 승인대기 → `requestedAt ASC, approvalRequestId ASC`

**Cursor 방식** (Event Entry 이력, Ticket Ledger 이력): 정렬 `createdAt DESC, id DESC`, cursor 기준은 단일 시간값이 아닌 **`createdAt + id` 조합**. 응답 `{items, nextCursor, hasNext}`, 마지막 페이지는 `nextCursor: null, hasNext: false`.

### 명령 API 공통 오류 (§8)
| 코드 | HTTP | 조건 |
|---|---|---|
| `VALIDATION_FAILED` | 400 | 필수값 누락, null/blank 위반, 숫자 범위, enum 위반, 시간 형식·범위, UUID 형식 위반 |
| `FORBIDDEN` | 403 | 리소스는 있으나 권한 없음 |
| `RESOURCE_NOT_FOUND` | 404 | 대상 리소스 없음 |
| `INVALID_STATE` | 409 | 현재 상태에서 해당 명령 불가 |
| `IDEMPOTENCY_CONFLICT` | 409 | 동일 `requestId`/`idempotencyKey`에 다른 요청 내용 |
| `CONCURRENT_COMMAND` | 409 | 동일 리소스에 충돌하는 명령 동시 처리 |
| `SYSTEM_ERROR` | 500 | 예상치 못한 내부 오류 |

`UNAUTHORIZED`는 사용하지 않는다(로그인 없는 MVP). 여러 도메인 공통 오류는 공통 코드, 특정 도메인에서만 의미 있는 오류는 도메인 전용 코드(`INSUFFICIENT_BALANCE`, `INSUFFICIENT_CANDIDATES`, `EVENT_NOT_OPEN` 등). 이미 성공한 요청 재전송 시 새 작업 없이 기존 성공 결과 반환.

### 내부 처리 계약 (§9)
```java
eventGateService.close(eventId);
entryStreamService.appendCloseBarrier(eventId);   // Gate 차단 → Barrier → Barrier Stream ID = cutoffStreamId
```
EARN Consumer: `MissionCompletion INSERT → EARN ticket_ledger INSERT → user_ticket_balance UPDATE → COMMIT → XACK`
SPEND Consumer: `event_entry INSERT → SPEND ticket_ledger INSERT → user_ticket_balance UPDATE → COMMIT → XACK`

### P0/P1 (§12)
**P0 없음, P1 없음.** P2만 2건: `completedToday` 유지 여부, 화면용 Response 최적화.

---

## 11. DB 스키마 (DB 스키마 찐 최종, 2026-09-15) — 25개 테이블

정본은 마크다운이 아니라 **`src/main/resources/db/migration/`의 Flyway 마이그레이션**이다. 아래는 계약 요약.

| # | 테이블 | 핵심 제약 / 메모 |
|---|---|---|
| 1 | `member` | `member_id` PK, `role` ENUM(`USER`,`ADMIN`). Creator 여부는 role이 아니라 `creator` 레코드 존재로 판단 |
| 2 | `creator` | `UNIQUE(member_id)` — Member 1명당 Creator 최대 1 |
| 3 | `creator_application` | `status`(PENDING/APPROVED/REJECTED), `reviewed_by`, `reject_reason`. 승인 시 `creator` 생성 |
| 4 | `mission` | `UNIQUE(creator_id, type)`, type ENUM(`ATTENDANCE`,`LIKE`), `reward_amount` CHECK > 0 |
| 5 | `mission_completion` | **`UNIQUE(member_id, creator_id, mission_id, period_key)`**, `request_id` UNIQUE. 교차검증: `MissionCompletion.creator_id = Mission.creator_id` |
| 6 | `user_ticket_balance` | `PK(member_id, creator_id)`, `balance` CHECK >= 0 |
| 7 | `ticket_ledger` | append-only. `type`(`EARN`/`SPEND`/`COMPENSATE`), `request_id` UNIQUE, `balance_before/after`. EARN→`mission_completion_id` NOT NULL, SPEND→`event_entry_id` NOT NULL, COMPENSATE→둘 다 NULL 가능 |
| 8 | `event` | `status`, `cutoff_stream_id`, `closed_at`, `published_at`, `deleted_at`, **`request_id` UNIQUE NOT NULL**(생성 멱등), `created_by` |
| 9 | `event_approval_request` | `UNIQUE(event_id, approval_round)` — 차수별 이력 보존(UPDATE로 덮어쓰지 않음) |
| 10 | `event_entry` | **`request_id` UNIQUE NOT NULL**, `used_ticket_count` CHECK >= 1. `creator_id` 미저장 → `EventEntry→Event→Creator` 경로로 판단 |
| 11 | `draw_snapshot` | **`UNIQUE(event_id)`**, `snapshot_hash`, `verification_status`(UNVERIFIED/VERIFIED/INVALID) |
| 12 | `draw_snapshot_candidate` | `UNIQUE(snapshot_id, member_id)`, `ticket_count` CHECK > 0. Hash 계산 시 `member_id ASC` 고정 정규화 |
| 13 | `draw_seed` | `seed_value VARBINARY(255)`. Seed Commitment는 MVP 미사용 |
| 14 | `drawing` | `UNIQUE(event_id, draw_no)`, `UNIQUE(seed_id)`, `UNIQUE(redraw_request_id)`. INITIAL은 `draw_no=0`·original/redraw_request NULL, REDRAW는 `draw_no>0`·둘 다 NOT NULL. `version` 낙관적 락(DEFAULT 0) |
| 15 | `draw_attempt_history` | `UNIQUE(drawing_id, attempt_no)`, status(STARTED/FAILED/SUCCEEDED) |
| 16 | `winner` | `UNIQUE(drawing_id, rank_in_drawing)`, **`UNIQUE(event_id, member_id)`** → 같은 Event에서 재당첨 불가 |
| 17 | `winner_management` | `UNIQUE(winner_id)`, status(SELECTED/RECEIVED/DECLINED/DISQUALIFIED) |
| 18 | `winner_status_history` | append-only 상태 변경 이력 |
| 19 | `redraw_request` | `idempotency_key` UNIQUE, `vacancy_count` CHECK > 0, `status` + `execution_status` 분리 |
| 20 | `redraw_request_vacancy` | `UNIQUE(redraw_request_id, winner_id)`. 동일 Winner 결원의 동시 점유 방지는 **Service Tx에서 `SELECT ... FOR UPDATE`로 보장**(MySQL 부분 UNIQUE 불가) |
| 21 | `redraw_exclusion` | `UNIQUE(drawing_id, member_id)`, 제외 사유(`ALREADY_WINNER`/`DECLINED`/`DISQUALIFIED`) |
| 22 | `redraw_execution_history` | append-only 실행 상태·실패 이력 |
| 23 | `draw_verification_history` | status(VERIFIED/VERIFICATION_FAILED) + 4개 matched 플래그 |
| 24 | `notification` | **`UNIQUE(winner_id, type)`** → 알림 중복 생성 방지. type(INITIAL_WINNER/REDRAW_WINNER), `read_at` |
| 25 | `dead_stream_message` | **`UNIQUE(source_stream_id, stream_type)`**, `payload` JSON(원본 보존), `retry_count`, `resolution_status` DEFAULT `UNRESOLVED` |

### 반드시 Service에서 검증할 교차 정합성 (FK/UNIQUE로 못 잡음)
```
MissionCompletion.creator_id = Mission.creator_id
EARN Ledger.member_id / creator_id = MissionCompletion.member_id / creator_id
SPEND Ledger.member_id = EventEntry.member_id
SPEND Ledger.creator_id = EventEntry.event.creator_id     ← 타 Creator 응모권 오용 방지 핵심
Drawing.snapshot.event_id = Drawing.event_id
REDRAW: originalDrawing.event_id = redrawDrawing.event_id / draw_type=INITIAL / draw_no=0
REDRAW: snapshot_id·draw_method·algorithm_version = INITIAL과 동일 (Seed만 새로)
RedrawRequest.vacancy_count = 실제 Vacancy row 수 (+ 실행 직전 재검증)
동일 Winner 결원은 진행 중 Request 하나만 점유
Winner.event_id = Winner.drawing.event_id
RedrawExclusion.member_id 는 해당 Event의 과거 Winner여야 함
Notification 의 member/event/drawing/winner 는 동일 Winner 계보
```

> ⚠️ **임의 결정 금지**: MySQL 시각 컬럼 타입(문서에는 `DATETIME`으로만 표기, 정밀도·타임존 처리 미확정). `title`/`description`/`rejectReason` 최대 길이도 문서에서 확정되지 않음(임의 지정 금지, 별도 확정 항목).

---

## 12. 동시성·정합성 검증 시나리오 (취합 §14) — 35개, 내 범위 굵게

1. 동일 출석 미션 동시 요청 시 중복 적립 0건
2. 동일 콘텐츠 좋아요 동시 요청 시 중복 적립 0건
3. **동일 requestId 동시 재전송 시 중복 차감 0건**
4. **동일 requestId 동시 재전송 시 Entry 중복 생성 0건**
5. **보유량 초과 동시 응모에도 Redis Balance 음수 0건**
6. **요청 ticketCount만큼 정확히 차감**
7. **Stream 재전달 시 Entry 중복 생성 0건**
8. **Stream 재전달 시 SPEND Ledger 중복 생성 0건**
9. **Stream 재전달 시 DB Balance 중복 차감 0건**
10. **Consumer 동시 처리 시 DB Balance 갱신 유실 0건**
11. **응모·마감 동시 발생 시 Gate 차단 이전 정상 승인분만 최종 포함**
12. **Gate 차단 이후 신규 응모 승인 0건**
13. **자동·수동 마감 동시 요청 시 `OPEN→CLOSING` 1건**
14. **Drain 완료 후 `CLOSING→CLOSED` 1건**
15. **마감 전 승인 응모가 전부 DB 반영되기 전 CLOSED 전이 0건**
16. **Drain 실패·타임아웃 시 CLOSED 전이 0건**
17. **CLOSING 중 서버 재기동 후 기존 cutoffStreamId로 Drain 재개 가능**
18. **CLOSED 이후 Snapshot 생성 시 마감 이전 정상 응모 누락 0건**
19. 정상 호출 + 누락 복구 스케줄러 동시 실행에도 공식 Snapshot 1건
20. 동일 Event Snapshot 동시 생성 요청 시 1건
21. Snapshot 생성 후 Candidate 변경 시 Hash 검증 실패
22. 동일 Snapshot+Seed+AlgorithmVersion 재현 시 Winner·Rank 동일
23. 공식 추첨 중복 실행 시 INITIAL Drawing 1건
24. 다중 인스턴스 동시 추첨에도 INITIAL Drawing 1건
25. 동일 Drawing Retry 시 새 Seed 생성 0건
26. 동일 RedrawRequest 동시 처리 시 REDRAW Drawing 1건
27. 기존 Winner의 재추첨 재당첨 0건
28. 동일 공개 요청 중복 실행 시 알림 중복 생성 0건
29. **Event 상태 변경 동시 요청에도 허용되지 않은 전이 0건**
30. **정상적인 Redis→DB 비동기 지연을 정합성 오류로 오탐하지 않음**
31. **동일 SCHEDULED Event 중복·동시 처리에도 `SCHEDULED→OPEN` 1건**
32. **Gate 차단·cutoff 확정 후 DB `OPEN→CLOSING` 실패해도 재시도 시 최초 cutoff 재사용**
33. INITIAL 결과확정 4개 작업 중 하나라도 실패하면 전체 Rollback
34. 후보 수 < vacancyCount면 REDRAW Drawing 0건 + `INSUFFICIENT_CANDIDATES`
35. **지속 불일치 보정 시 기존 Ledger 수정 없이 COMPENSATE append-only + Redis 재동기화**

### 비기능 요구사항 (§15)
불변식: `Redis Balance >= 0`, 동일 미션 Business Key 보상 <= 1회, 동일 requestId SPEND <= 1회, Event당 공식 Snapshot = 1, Event당 INITIAL Drawing = 1, RedrawRequest당 REDRAW Drawing <= 1, 후보 부족 시 REDRAW Drawing = 0, Winner 수 <= winnerCount, 동일 Drawing 내 동일 userId Winner <= 1, 기존 Winner 재당첨 = 0.
감사 가능성: Snapshot·Hash·Drawing Input·Seed·AlgorithmVersion·Winner·Rank·ResultHash·실행이력·Retry이력·Verification이력·RedrawRequest이력·WinnerManagement이력·executionStatus 이력·COMPENSATE 이력.
성능 검증: 동시 요청 수, TPS, p95, p99, 오류율, Redis Balance 음수 여부, 중복 Entry/Ledger 여부. **Lua 동시성 부하 테스트(NFR-06/FR-P2-043)는 A트랙(자비) 담당**이며 구현 진행 후로 의도적으로 미뤄진 상태. 그 전제조건인 더미 유저·응모권 시딩 스크립트(FR-P2-042)도 **자비가 맡기로 확정**(RTM 역할은 성집으로 남아 있음).

---

## 13. RTM 현황 (통합 RTM, 216건)

- 파트별: FR-P1 34건(1조) / **FR-P2 44건(2조)** / FR-P3 89건(3조) / FR-P4 49건(4조)
- 담당자별: 윤희 58 / 혁준 49 / **성집 31** / 성원 28 / 태연 22 / 문구 12 / 자비 12 / 윤희·성원 공동 3 / 성집·자비 공동 1
- 진행상태: **전 항목 "시작 전"** (2026-09-17 export 시점)
- 우선순위: 1차 확정 209 / 확인 필요 5 / 1차 신설 1 / 신설 1

### 미확정·신설 항목 (우선순위 != "1차 확정")
| ID | 역할 | 상태 | 내용 |
|---|---|---|---|
| FR-P2-042 | 성집 | 1차 신설 | 시연·검증용 더미 유저·응모권 시딩 스크립트 (**자비에게 이관 확정**) |
| FR-P2-043 | 자비 | 확인 필요 | Lua 동시성 정합성 실측 부하 테스트 (코드리뷰 대체 불가) |
| FR-P3-045 | 성원 | 신설 | `UNIFORM_V1`(동일 확률) 추첨 지원 |
| FR-P3-046 | 성원 | 확인 필요 | Seed Commitment로 마감 전 Seed 조작 사후 검증 |
| FR-P3-079 | 윤희 | 확인 필요 | 추첨 결과 저장 + Outbox Event 저장 동일 Tx |
| FR-P3-080 | 성원 | 확인 필요 | Outbox → Kafka 전달 |
| FR-P3-081 | 성원 | 확인 필요 | 공개 결과 조회용 Read Model |

> FR-P3-045/046/079~081은 **취합v1.5.4에 계약이 없다**(작업단위 관리표 비고에도 "현재 1.5.4에는 Kafka/Outbox 계약 없음", "UNIFORM_V1·Seed Commitment는 1.5.4와 추가 합의 필요"로 기록). T3-07(Kafka Outbox)은 우선순위 P2.
> `확인 필요 = Yes` 플래그가 붙은 행은 FR-P4-097(DRAFT 이벤트 생성), FR-P4-112(개인정보 원문 조회 권한) 2건 — 둘 다 혁준 담당이며 우선순위 자체는 1차 확정.

---

## 14. 레포 문서 구조와 정본 우선순위

### 1순위 — 레포 `docs/` (코드와 함께 버전관리, 구현 시 1차 참조)
- `docs/README.md` (인덱스)
- `docs/api-index.md` — 전체 API 인덱스(External 48개 + 내부 Service 호출)
- `docs/common/api.md` — 응답 형식·사용자 식별·멱등성·오류코드 배치 원칙
- `docs/management/work-items.csv`, `docs/management/rtm.csv` — Notion export 커밋본
- `docs/domains/<도메인>/README.md`·`api.md` — 담당자가 **처음 필요해질 때** 추가. 책임·소유 데이터·상태 전이·불변조건·외부 API 계약처럼 코드만으로 파악하기 어려운 정보를 적는다. **빈 문서·디렉터리 미리 만들지 않음, 300줄 초과 시 관심사별 분리**
  - 현재 존재: `domains/snapshot/README.md`(공식 후보 확정, 멱등 생성, Snapshot Hash 정규화 계약)

### 실제 정본 (문서가 아니라 코드)
- **DB 스키마 → `src/main/resources/db/migration/`** (Flyway)
- **공통 오류 코드 → `src/main/java/kr/co/cking/common/exception/CommonErrorCode.java`**
- **도메인 오류 코드 → 각 도메인의 `*ErrorCode.java`**
- **API 목록 → `docs/api-index.md`**

### 2순위 — 외부 SSOT 원본 (기획 합의 배경이 필요할 때만)
원본 zip: `/Users/castlehouse/Desktop/융합/문서/*.zip` (2026-09-17 재export, 5종만 — 스프린트 트래커·Convention은 이번 배치에 없음)
압축 해제본: `/Users/castlehouse/Desktop/융합/문서/_extracted/`

| 문서 | 날짜 | `_extracted/` 하위 경로 |
|---|---|---|
| 취합 v1.5.4 | 2026/09/17 (09:17 13:40 기준 최종 수정) | `취합v1 5 4 24518857e9a482628b9a8139b3d59c38.md` |
| 통합 API 명세 v2.5 | 2026/09/16 (작성 09-14, 최종수정 09-17 15:28) | `통합 API 명세 v2 5 3db18857e9a48048b984c34b512422cf.md` |
| DB 스키마 찐 최종 | 2026/09/15 (최종수정 09-16 15:34) | `DB 스키마 찐 최종 3db18857e9a4804a8991d22196297c2c.md` |
| 통합 RTM | — | `통합 RTM/통합 RTM csv 3dc18857e9a4804db7d6ce87dd8e48bb_all.csv` |
| 통합 작업단위 관리표 | — | `통합 작업 단위 관리표/통합 작업단위 관리표 csv 3dc18857e9a4802fbe6fd0022ab3a90c_all.csv` (신규: 빈 `스프린트 트래커 (1)` 컬럼 추가) |

> **2026-09-17 재export 대조 결과**: 취합/API명세/DB스키마/RTM/작업단위표 5종 모두 재확인함. RTM은 여전히 216건, 확인필요·신설 7건 동일(§13). 작업단위표는 26행(25개 T-ID) 동일, 담당자·선행작업·우선순위 전부 변동 없음. 실질 변경은 두 건뿐이고 둘 다 이미 위 §5·§18에 반영돼 있었음: (1) 취합 §4.2/4.4/4.5에 09-17 13:40 보강분(가드 TTL 25h·`REQUEST_ID_CONFLICT`·Lua 순서), (2) API명세 09-17 15:28 §4.2에서 클라이언트 `periodKey` 입력 제거 확정. 그 외 날짜 스탬프만 갱신됨.
> **스프린트 트래커·Convention 미포함**: 이번 5종 zip 배치에는 스프린트 트래커와 Convention이 없다. §2의 스프린트 날짜(성집 Sprint 1: 09-16→09-22 등)는 이전 배치 기준이며 이번 재export로 재확인되지 않았다 — 최신 스프린트 상태 확인이 필요하면 원본 재수령 필요.
> zip 해제는 **`ditto -x -k <zip> _extracted`**. macOS 기본 `unzip`은 `-O UTF-8`을 지원하지 않아 한글 파일명이 깨진다.
> `docs/management/*.csv`는 같은 내용의 커밋 스냅샷 — 코드 작업 중엔 레포본 우선, 최신 확정 확인이 필요할 때만 위 원본 대조. `docs/` 파일이 실제 커밋됐는지는 매번 `git status`로 재확인.

---

## 15. 코드 구조 (README "9. 코드 구조" — **최우선 참조**)

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

도메인 내부 기본 레이어: `presentation` / `application` / `domain` / `repository`. 주기 실행이 필요한 도메인은 `scheduler` 추가. 그 아래 세분화는 파일이 늘어났을 때 담당자가 판단(예: `event.application.dto`, `event.application.config`). event·ticket은 PR #52에서 이 구조로 전환 완료.

**코드 구조 원칙**
- 다른 도메인의 Entity·Repository를 통해 상태를 **직접 변경하지 않는다**
- 도메인 간 상태 변경은 **해당 도메인의 Service를 통해** 수행한다
- 도메인 간 참조는 Entity 객체가 아닌 **ID**를 사용한다
- 조회 최적화가 필요하면 QueryRepository·Projection을 쓸 수 있다
- **Event 상태 변경은 `EventCommandService`를 통해서만** 수행한다
- 공통 응답·예외는 `common` 패키지에서 관리한다
- 도메인별 ErrorCode는 공통 `ErrorCode` 인터페이스를 구현한 enum으로 정의한다

---

## 16. 협업 규칙 (README "10. 협업 규칙" + CONTRIBUTING.md — **최우선 참조**)

### 브랜치
| 브랜치 | 의미 | 배포 |
|---|---|---|
| `main` | 릴리스된 상태 | 운영 서버(예정) |
| `develop` | 개발 통합 | 머지 시 개발 서버 자동 배포 |
| `<타입>/<이슈번호>-<요약>` | 작업 브랜치 | 없음 |

- 접두어는 커밋 타입과 같은 세트(`feat/12-apply-ticket`, `chore/5-setup-ci`). 이슈 라벨과 꼭 같을 필요는 없다 — `task` 이슈여도 문서면 `docs/`, 설정이면 `chore/`
- **`main`·`develop` 직접 push 금지, force push 금지.** PR로만 반영
- 평소 PR은 `develop`으로. `develop → main`은 릴리스할 때만, 머지 후 버전 태그

### 작업 흐름
1. 이슈 생성 + **본인을 담당자로 지정** (작업 단위 = 이슈 1개)
2. `develop`에서 작업 브랜치 분기
3. PR을 `develop`으로. **제목 = 이슈 제목과 동일**, 본문에 `Closes #12`
4. 리뷰어 1명 이상 approve → **Squash and merge** → 브랜치 삭제
- 이슈 1개당 PR 1개. 따로 머지하면 깨지는 작업만 묶고, **묶은 이유를 PR 본문에 적는다**

### 커밋 메시지
`<타입>: <한국어 한 줄 요약>` (예: `feat: 응모권 적립 API 추가`)

| 타입 | 용도 |
|---|---|
| feat | 기능 추가 |
| fix | 버그 수정 |
| refactor | 동작 변화 없는 구조 개선 |
| docs | 문서 |
| test | 테스트 |
| ci | CI/CD 워크플로우, 배포 스크립트 |
| chore | 빌드·설정·의존성 등 나머지 |

- 한 커밋은 **하나의 관심사만**. 본문에는 "무엇을"보다 **"왜"**를 남긴다
- **표에 없는 타입은 즉흥적으로 만들지 않는다** — 팀에 말하고 표에 추가

### 이슈 템플릿
| 템플릿 | 기준 |
|---|---|
| ✨ 기능(`feature`) | 명세에 있는 기능을 새로 만든다 (응모 API, Lua 스크립트) |
| 🐛 버그(`bug`) | 있던 기능이 의도대로 동작하지 않는다 |
| 🛠 작업(`task`) | 그 외 — 설정·인프라·문서·리팩터링 |

> **팀 결정(2026-09-16): 앞으로 이슈 생성 시 TASK 템플릿/라벨은 쓰지 않는다.** 새 이슈는 `feature` 또는 `bug`로만. 과거 `[TASK]` 이슈·커밋 태그는 소급 변경하지 않는다.
> PR 템플릿 구조: `개요` / `변경 사항` / `관련 이슈` / `테스트(체크박스)` / `체크리스트`(커밋 규칙 준수·리뷰어 지정·시크릿 미포함·문서 갱신). Blank issue는 비활성화되어 있다.

### 리뷰
PR 작성 시 **리뷰어를 직접 지정**(Reviewers). **24시간 안에** 리뷰, 늦어지면 팀 채널에 알림. 수정 요청에는 **이유를 적고**, 취향 차이는 `nit:` 접두어로 참고 의견임을 표시.

### 하지 말 것
- `.env`, API 키, 토큰 등 **시크릿 커밋**
- `main`·`develop` 직접 push, force push
- **커밋/PR/이슈에 Claude 흔적(Co-Authored-By, Generated with Claude Code 등) 남기기 — 절대 금지**

> `컨벤션.zip`(Convention.md)은 팀 초기 드래프트다. 칸반 상태(시작 전/개발 중/테스트 중/리뷰 중/완료)와 이슈 트래커 상태(발견/진행 중/보류 중/해결)는 유효하지만, `style` 타입 존재·`git checkout main && git pull` 안내 등 일부가 위 확정 규칙(CONTRIBUTING.md)과 다르다. **충돌 시 위 내용이 정본.**

---

## 17. 로컬 실행

```bash
git switch develop && git pull origin develop
git switch -c feat/이슈번호-작업내용
docker compose up -d        # cking-mysql(3306), cking-redis(6379) / db=cking user=cking pw=cking
./gradlew bootRun           # Windows: .\gradlew.bat bootRun
./gradlew test              # MySQL·Redis 컨테이너 실행 중이어야 함
docker compose down         # -v 붙이면 로컬 MySQL 데이터까지 전부 삭제
```
별도 MySQL DB 생성이나 Redis 설정은 필요 없다.

---

## 18. 구현 전 반드시 확인할 미확정 항목

1. **Drain 재시도 간격·최대 대기시간** — 취합 §6.8 FR-11b는 "시스템2가 자율 결정"이라고만 함. 구체값 미정
2. **MySQL 시각 컬럼 타입** — DB 문서는 `DATETIME` 표기만, 정밀도·타임존 처리 미확정
3. **응모 결과코드 10종 ↔ HTTP Status 구체 매핑값** — 취합 §5.4는 "구현 시 1:1 매핑, 구체값은 지정하지 않음"
4. **`title`/`description`/`rejectReason` 최대 길이** — API 명세가 "임의로 지정하지 않고 추후 확정"으로 명시
5. **`dead_stream_message.stream_type` ENUM 실제 값 표기** — "구현 시 코드 컨벤션에 맞춰 결정"
6. **차감 명령 `DECR` vs `DECRBY`** — 취합 §5.3은 "ticketCount만큼 차감", RTM FR-P2-032는 `DECR` 표기. Lua 담당(자비)과 확인
7. **`/api/internal/**` 접근제어** — 서비스 메서드 호출로 정리되면 엔드포인트 자체가 사라지므로 불필요 방향. 공통 코드 작업 시점 확정(블로커 아님)
8. **`RedrawRequest.reason`의 blank 허용 여부** — API 명세가 "현재 문서에서 확정되지 않음"으로 명시
