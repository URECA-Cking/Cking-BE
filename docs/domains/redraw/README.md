# Redraw 도메인

## 책임

- `DECLINED` 또는 `DISQUALIFIED` Winner로 생긴 결원을 재추첨 요청으로 고정한다.
- 요청 생성 시 최초 `INITIAL` Drawing과 실제 결원을 서버에서 결정한다.
- 같은 결원이 둘 이상의 진행 중 요청에 포함되지 않도록 보장한다.

이 도메인은 Event·Drawing·Winner Entity를 직접 수정하지 않는다. 생성 전 상태 조회는 각 도메인의
조회 경계를 통해 수행하며, 이후 승인·실행은 별도 API가 책임진다.

## 요청 상태

| 상태 | 의미 | 결원 점유 |
| --- | --- | --- |
| `REQUESTED` | 관리자 검토 대기 | 점유함 |
| `APPROVED` | 실행 가능 | 점유함 |
| `REJECTED` | 요청 거절 | 점유하지 않음 |

실행 상태는 `PENDING`, `EXECUTED`, `INSUFFICIENT_CANDIDATES`, `FAILED`이며, 생성 시에는 항상
`PENDING`이다. 진행 중 요청은 `REQUESTED` 또는 `APPROVED`이면서 `executionStatus = PENDING`인
요청이다. 이 요청에 연결된 Winner는 새 요청의 결원 후보에서 제외한다.

## 생성 불변조건

- 요청자는 존재하는 `ADMIN` Member여야 한다.
- Event는 삭제되지 않은 `PUBLISHED` 상태여야 한다.
- Event의 `drawNo = 0`, `drawType = INITIAL` Drawing이 원본 Drawing이다. 클라이언트는 원본
  Drawing ID나 결원 수를 전달하지 않는다.
- 원본 INITIAL Drawing의 Winner 중 현재 운영 상태가 `DECLINED` 또는 `DISQUALIFIED`인 Winner만
  결원 후보가 된다.
- 후보가 하나도 없으면 요청을 만들지 않는다.
- 생성 시점에 선정한 Winner ID 목록을 `redraw_request_vacancy`에 저장한다. 이후 Winner 상태가
  바뀌어도 요청의 결원 수와 대상은 변하지 않는다.

## 심사 상태 전이

- 존재하는 `ADMIN` Member만 심사할 수 있다.
- 승인·거절은 `REQUESTED` 상태의 요청에만 가능하다. 승인하면 `APPROVED`가 되고
  `executionStatus`는 `PENDING`으로 유지한다. 거절하면 `REJECTED`가 된다.
- 승인·거절 모두 `reviewedBy`와 `reviewedAt`을 저장한다. 거절은 공백 제거 후 1~500자의
  `rejectReason`을 반드시 저장하고, 승인은 `rejectReason`을 비워 둔다.
- 심사 명령은 대상 `RedrawRequest` 행을 비관적 쓰기 잠금으로 조회한다. 동시에 도착한 승인·거절은
  한 명령만 상태 전이를 완료하며, 나머지는 잠금 해제 후 `INVALID_STATE`로 거부한다.

## 동시성과 멱등성

- 생성 명령은 Event 행을 비관적 쓰기 잠금으로 조회한 뒤 결원·점유를 계산한다. 같은 Event의
  요청 생성은 직렬화되어 결원 중복 점유가 발생하지 않는다.
- `idempotencyKey`는 1~100자의 UUID 표준 문자열이며 전역 unique다.
- 같은 키로 같은 `userId`, `eventId`, 정규화한 `reason`을 재요청하면 기존 RedrawRequest를 반환한다.
  하나라도 다르면 `IDEMPOTENCY_CONFLICT`로 거부한다.
- 서로 다른 Event에서 같은 키가 동시에 들어와 unique 제약이 충돌하면 저장 후 기존 요청을 다시
  조회한다. 기존 요청을 찾지 못하면 `CONCURRENT_COMMAND`, 본문이 다르면 `IDEMPOTENCY_CONFLICT`를
  반환한다.
- 심사 명령은 멱등 API가 아니다. 최초 심사만 상태를 변경하고, 이후의 재시도와 반대 심사 명령은
  `INVALID_STATE`를 반환한다.

## 실행 계약

- 실행자는 존재하는 `ADMIN` Member여야 하며, `APPROVED`와 `PENDING` 조합의 요청만 한 번 실행할 수 있다.
- 실행 명령은 RedrawRequest 행을 비관적 쓰기 잠금으로 읽고, 고정 `vacancyCount`와
  `redraw_request_vacancy` 행 수를 다시 비교한다. 잠금 조회 직후 `APPROVED`와 `PENDING` 조합을
  검증하며, 불일치하면 시스템3 실행을 호출하지 않고 `INVALID_STATE`로 거부한다.
- 시스템4는 상태 검증·이력 기록만 수행하고 시스템3 `RedrawDrawingExecutionService`에 전체 재추첨을 위임한다.
  DrawingEngine·Snapshot 저장소·Seed를 직접 다루지 않는다.
- 시스템3은 원본 INITIAL Snapshot과 `drawMethod`·`algorithmVersion`을 재사용하고 Event의 모든 기존 Winner를
후보에서 제외한다. 후보가 부족하면 Drawing을 생성하지 않는다. 상품 Snapshot이 있으면 INITIAL과 같은
V2 Input/Result Hash와 상품 배정 알고리즘을 사용하고, 배정 상품 정보를 새 Winner에 보존한다.
- 실행 종료 시 `EXECUTED`, `INSUFFICIENT_CANDIDATES`, `FAILED` 중 하나를 기록하고
  `redraw_execution_history`에 결과를 남긴다. 시스템3 실행의 기술적 실패는 실행 Transaction을 먼저
  Rollback한 뒤 별도 `REQUIRES_NEW` Transaction에서 `FAILED`와 이력을 확정한다. 실패·후보 부족은
  결원 수를 변경하지 않는다.

DB 구조와 unique·foreign key 제약의 정본은 `src/main/resources/db/migration/`이다.
