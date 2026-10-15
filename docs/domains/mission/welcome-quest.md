# 웰컴 퀘스트 API 계약 (#626)

이 문서는 웰컴 퀘스트의 **목표 외부 API 계약**이다. 현재 구현된 API를 설명하지 않는다. BE 구현과 FE 연동은 이 계약을 기준으로 진행한다. 모든 응답은 [공통 API 규약](../../common/api.md)의 `code`/`data`/`message` 봉투를 사용한다.

## 범위와 책임

- Bearer Access JWT가 필수다. 호출자는 `@CurrentMemberId`로 식별하며 요청에 `userId`를 받지 않는다. 토큰 오류는 `UNAUTHORIZED`(401), JWT의 Member가 없으면 `RESOURCE_NOT_FOUND`(404)다.
- 웰컴 퀘스트는 공용 응모권 1장씩을 `SIGNUP`, `FOLLOW`, `QUIZ`, `BONUS` 네 보상으로 지급한다. 각 보상은 회원당 평생 한 번이다.
- `GET /api/missions`의 공용 미션 목록에는 웰컴 항목을 추가하지 않는다. 기존 목록을 출석 카드가 출석 항목으로 해석하므로, 웰컴 상태와 지급은 아래 전용 경로에서만 노출한다. 기존 ATTENDANCE 완료·일일 지급 조건도 바꾸지 않는다.
- 보상 지급 판정은 Mission이 소유하고, 공용 응모권 적립과 원장·잔액은 Ticket이 소유한다. 팔로우 여부는 Follow 도메인의 조회 계약으로 확인하며, 팔로우 관계를 이 API가 변경하지 않는다.

## 대상과 상태

- **D1 신규 대상**: 기능이 처음 활성화된 고정 UTC 시각 `welcomeQuestActivatedAt` 이상에 생성된 Member만 대상이다(`member.createdAt >= welcomeQuestActivatedAt`, 두 값 모두 UTC로 비교). 활성화 시각은 배포 시 확정·보존하고 재배포·재시작 후에도 바꾸지 않으며, 그 이전 회원에게 소급 지급하지 않는다. FE는 가입 시각을 추측하지 않고 `eligible`을 따른다.
- 가입 트랜잭션에서는 보상을 지급하지 않는다. 퀘스트가 처음 표시되면 FE가 `SIGNUP` 지급을 별도로 자동 요청한다(D2). 이 요청의 실패는 가입 성공을 취소하지 않는다.
- `FOLLOW`의 신규 지급 조건은 **지급 시점**에 현재 팔로우 중인 Creator가 한 명 이상인 것이다. 온보딩에서 이미 팔로우했어도 바로 충족한다(D3). 지급 후 언팔로우해도 지급 결과는 유지한다.
- `QUIZ`는 FE가 고정된 단일 튜토리얼 문항·보기를 제공한다. 이 API는 문항 메타데이터를 반환하지 않고, 신규 지급 시 요청의 `answer`가 JSON boolean `true`인지 확인한다. 다른 값은 정답으로 인정하지 않는다. 이는 답안 값 확인이며 실제 화면 노출·풀이 과정의 서버 검증은 아니다. FE 문항의 정답이 바뀌면 이 계약과 FE를 함께 변경해야 한다.
- `BONUS`는 세 단계와 독립이다. 대상 회원이면 즉시 받을 수 있고, 한 번 지급되면 `CLAIMED`다.
- `completed`는 `SIGNUP`·`FOLLOW`·`QUIZ` 세 단계가 모두 지급 승인된 상태다(D4). `BONUS` 수령 여부는 `completed` 계산에 넣지 않는다. 세 단계가 끝나면 FE는 퀘스트를 종료하고, 미수령 보너스 상자는 출석 카드에 계속 표시한다. 보너스 지급 경로는 퀘스트 종료 후에도 사용할 수 있다.
- `CLAIMED`는 Ticket의 공용 EARN이 지급을 **승인**한 상태를 뜻한다. 원장·DB 잔액 반영 완료를 뜻하지 않는다. 상태 조회는 Redis/Stream 비동기 반영 지연 때문에 이미 승인된 보상을 `CLAIMABLE`로 되돌리지 않아야 한다.

| 대상 | `CLAIMED` | `CLAIMABLE` | `NOT_MET` |
| --- | --- | --- | --- |
| `SIGNUP` | 지급 승인됨 | 대상이며 미지급 | 사용 안 함 |
| `FOLLOW` | 지급 승인됨 | 팔로우 중이며 미지급 | 현재 팔로우 0명이며 미지급 |
| `QUIZ` | 지급 승인됨 | 대상이며 미지급 | 사용 안 함 |
| `BONUS` | 지급 승인됨 | 대상이며 미지급 | 사용 안 함 |

## GET /api/me/welcome-quest

현재 회원의 대상 여부와 네 보상의 상태를 조회한다. Body와 query parameter는 없으며, 조회만으로 지급·완료 기록을 만들지 않는다.

대상 회원의 예시(`FOLLOW`가 미충족이어도 독립 보너스는 받을 수 있다):

```json
{
  "code": "SUCCESS",
  "data": {
    "eligible": true,
    "completed": false,
    "items": [
      { "step": "SIGNUP", "rewardAmount": 1, "status": "CLAIMABLE" },
      { "step": "FOLLOW", "rewardAmount": 1, "status": "NOT_MET" },
      { "step": "QUIZ", "rewardAmount": 1, "status": "CLAIMABLE" }
    ],
    "bonus": { "step": "BONUS", "rewardAmount": 1, "status": "CLAIMABLE" }
  },
  "message": null
}
```

비대상 회원은 `eligible=false`, `completed=false`, `items=[]`, `bonus=null`을 반환한다. 비대상 여부를 조회 자체의 오류로 만들지 않는다. 대상 회원의 `items`는 항상 `SIGNUP`, `FOLLOW`, `QUIZ` 순서로 세 항목을 반환한다. `bonus`는 같은 `step`/`rewardAmount`/`status` 구조의 객체이며 `step`은 항상 `BONUS`다. `rewardAmount`는 현재 계약에서 모두 `1`이다.

## POST /api/me/welcome-quest/{step}/claim

- Path `step`: `SIGNUP|FOLLOW|QUIZ|BONUS`. 그 밖의 값은 `VALIDATION_FAILED`(400)다.
- Body `requestId`: 필수 UUID 문자열. 회원·단계·보상량·QUIZ 답안의 요청 내용을 묶는 멱등성 키다. 새 지급 시도마다 새 UUID를 만들되, **같은 시도의 재요청에는 같은 UUID를 재사용**한다.
- Body `answer`: `QUIZ`에만 필수인 JSON boolean이다. 다른 단계에서 보내면 `VALIDATION_FAILED`(400)로 거부한다. `QUIZ`의 `false`는 형식 오류가 아닌 오답이다.
- 오답·조건 미충족처럼 EARN이 시작되지 않은 요청을 수정해 다시 제출할 때는 새 UUID를 사용한다. EARN 장애로 승인 여부가 불명확한 요청은 아래 재시도 규칙에 따라 원래 UUID와 Body를 유지한다.
- `SIGNUP`은 FE가 퀘스트 첫 표시 때 자동 요청하고, `FOLLOW`는 팔로우 수가 1 이상이면 별도 완료 조작 없이 받을 수 있다. 서버는 요청 당시 조건을 다시 확인한다.

일반 단계 요청:

```json
{ "requestId": "550e8400-e29b-41d4-a716-446655440000" }
```

QUIZ 요청:

```json
{ "requestId": "550e8400-e29b-41d4-a716-446655440000", "answer": true }
```

성공 응답의 `data`는 `{ "step": "SIGNUP", "rewardAmount": 1, "status": "CLAIMED" }` 형식이다. `step`은 요청한 단계다.

| HTTP | `code` | 의미 |
| --- | --- | --- |
| 202 | `EARN_ACCEPTED` | 이 `requestId`로 공용 응모권 EARN을 새로 승인함. 원장·DB 잔액 반영은 비동기 |
| 200 | `ALREADY_PROCESSED` | 동일 `requestId`와 동일 요청 내용의 승인 결과를 다시 반환함 |

### 지급·재시도 순서

1. 같은 `requestId`의 기존 승인 결과를 신규 대상·조건 판정보다 먼저 확인한다. 일치하면 `ALREADY_PROCESSED`를 반환한다. 다른 요청 내용이나 다른 EARN 용도에 이미 쓴 UUID면 `REQUEST_ID_CONFLICT`다.
2. 신규 요청이면 대상 회원, 단계별 조건, 같은 회원·단계의 기존 지급 여부를 검증한다. 이미 다른 `requestId`로 지급 승인됐다면 다시 지급하지 않는다.
3. 공용 응모권 EARN의 전역 `requestId` 선점과 Redis Balance 증가·Stream 발행 원자성을 재사용한다. 동시에 **회원·단계의 평생 1회 Business Key를 영속적으로 보장**해야 한다. 기존 공용 ATTENDANCE의 UTC 일일 Guard와 `common_mission_completion` 일일 UNIQUE만으로 평생 1회를 보장할 수 없으므로, 후속 BE 구현에서 웰컴 전용 영속 지급 원인·원장 연결과 EARN 경계를 마련한다. 원장·잔액 반영은 기존 공용 Ticket 소비·재처리 경계를 따른다.
4. 동일 `requestId` 재시도는 최초 판정한 회원·단계·보상량·답안에 묶인다. 재시도 사이에 팔로우를 취소하거나 기능 상태가 달라져도 이미 승인된 지급은 취소하지 않는다. 승인 여부가 확인되지 않으면 **새 UUID로 재발급하지 않는다.**

| 오류 코드 | HTTP | 조건·재시도 |
| --- | --- | --- |
| `VALIDATION_FAILED` | 400 | 잘못된 `step`·UUID·Body 형식, QUIZ 외 `answer` 포함, QUIZ `answer` 누락 |
| `WELCOME_QUEST_NOT_ELIGIBLE` | 403 | 기능 활성화 이전 가입 회원의 신규 지급 요청 |
| `WELCOME_QUEST_CONDITION_NOT_MET` | 409 | `FOLLOW` 신규 지급 시 현재 팔로우가 0명 |
| `WELCOME_QUEST_WRONG_ANSWER` | 409 | `QUIZ`의 `answer=false` |
| `WELCOME_QUEST_ALREADY_CLAIMED` | 409 | 같은 회원·단계를 다른 `requestId`로 이미 지급 승인함 |
| `REQUEST_ID_CONFLICT` | 409 | 같은 UUID의 요청 내용·EARN 용도가 다름 |
| `EARN_PROCESSING_FAILED` | 503 | 적립 처리 실패. 같은 `requestId`로 재시도 |
| `EARN_STATUS_UNKNOWN` | 504 | 지급 승인 여부 불명. 같은 `requestId`로만 확인·재시도하고 새 UUID로 지급하지 않음 |
| `BALANCE_MAINTENANCE` | 503 | 공용 잔액 보정 중. 잠시 후 같은 `requestId`로 재시도 |

세 EARN 장애 응답 후에도 클라이언트는 같은 `requestId`와 같은 Body를 보존한다. 재시도 시 기존 승인이 확인되면 `200 ALREADY_PROCESSED`, 아직 승인되지 않았고 새로 승인되면 `202 EARN_ACCEPTED`, 여전히 판단할 수 없으면 같은 장애 코드를 반환한다. `EARN_STATUS_UNKNOWN`은 서버도 확정 전까지 새 지급을 실행하지 않는다. 오류를 받은 직후 상태 조회가 `CLAIMABLE`이어도 새 UUID로 다시 요청하지 않는다.

## 공용 잔액 반영

`202 EARN_ACCEPTED`와 `GET /api/me/welcome-quest`의 `CLAIMED`는 Redis EARN·Stream 발행 승인 기준이다. `GET /api/tickets/common`은 DB 잔액 조회이므로 Consumer가 원장과 DB Balance를 반영할 때까지 기존 값이 보일 수 있다. FE는 `202` 직후 잔액 증가를 확정값으로 표시하지 않고, 잔액 API를 다시 조회해 반영을 확인한다. 표시 지연은 지급 실패나 재지급 사유가 아니다.
