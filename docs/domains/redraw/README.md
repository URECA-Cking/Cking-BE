# Redraw 도메인

## 책임

- `DECLINED` 또는 `DISQUALIFIED` Winner로 생긴 결원을 재추첨 요청으로 고정한다.
- 요청 생성 시 최초 `INITIAL` Drawing과 실제 결원을 서버에서 결정한다.
- 같은 결원이 둘 이상의 진행 중·Retry 대기·실행 완료 요청에 포함되지 않도록 보장한다.

이 도메인은 Event·Drawing·Winner Entity를 직접 수정하지 않는다. 생성 전 상태 조회는 각 도메인의
조회 경계를 통해 수행하며, 이후 승인·실행은 별도 API가 책임진다.

## 요청 상태

| 상태 | 의미 | 결원 점유 |
| --- | --- | --- |
| `REQUESTED` | 관리자 검토 대기 | 점유함 |
| `APPROVED` | 실행 가능 | 점유함 |
| `REJECTED` | 요청 거절 | 점유하지 않음 |

실행 상태는 `PENDING`, `EXECUTED`, `INSUFFICIENT_CANDIDATES`, `FAILED`이며, 생성 시에는 항상
`PENDING`이다. `REQUESTED` 또는 `APPROVED`이면서 `executionStatus = PENDING`인 요청은 결원을 임시 점유한다.
보존된 **Retry 가능한** `FAILED` REDRAW Drawing이 있는 요청은 기존 Drawing Retry가 끝날 때까지, `EXECUTED`
요청은 영구히 결원을 점유하므로 해당 Winner는 새 요청의 결원 후보에서 제외한다. `NON_RETRYABLE_FAILURE` 등으로
종결된 FAILED Drawing은 후속 REDRAW의 결원 점유와 입력 확정을 막지 않는다. Drawing을 만들기 전 입력 검증에 실패한
요청은 `PENDING`으로 남아 재실행할 수 있으므로 역시 점유를 유지한다. `REJECTED`,
`INSUFFICIENT_CANDIDATES` 요청의 결원만 다시 사용할 수 있다.

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

- 실행자는 존재하는 `ADMIN` Member여야 하며, 최초 실행은 `APPROVED`와 `PENDING` 조합에서 시작한다.
- 장시간 실행 Transaction을 열기 전에 고정 `vacancyCount`와 `redraw_request_vacancy` 행 수를
  다시 비교한다. 불일치하면 시스템3 실행을 호출하지 않고 `INVALID_STATE`로 거부한다.
- 시스템4는 상태 검증·이력 기록만 수행하고 시스템3 `RedrawDrawingExecutionService`에 전체 재추첨을 위임한다.
  DrawingEngine·Snapshot 저장소·Seed를 직접 다루지 않는다.
- 시스템3은 `COMPLETED` 원본 INITIAL Drawing만 사용하며, Event 행을 잠근 뒤 최신 Drawing의 `drawNo`와 Seed를 기준으로
  다음 회차와 새 Seed를 만들고 REDRAW Drawing을 생성해 같은 Event의 서로 다른 요청도 회차 충돌 없이 직렬화한다. 원본 INITIAL Snapshot과
  `drawMethod`·`algorithmVersion`을 재사용하고 Event의 모든 기존 Winner를 후보에서 제외한다. 후보가 부족하면
  Drawing을 생성하지 않는다. 후보는 전체 후보 풀에서 다시 선정하되, 상품 Snapshot이 있으면 `redraw_request_vacancy`에
  고정된 결원 Winner의 상품만 결원 확정 순서대로 새 Winner의 rank에 승계한다. 전체 Snapshot 상품 풀을 다시 배정하지 않으며,
  승계 상품별 수량은 V2 Input Hash에 포함하고 새 Winner에 보존한다.
- 시스템3은 새 REDRAW의 Drawing·Seed·제외 명단·Input Hash/Payload·첫 Attempt를 짧은 준비
  Transaction으로 먼저 확정한다. 이후 엔진 또는 결과 저장이 실패하면 준비 데이터는 유지하고 Drawing과
  RedrawRequest를 `FAILED`로 변경해 기존 `drawingId`와 `seedId`로 Retry할 수 있게 한다.
- 같은 Event에 `RUNNING` 또는 Retry 가능한 `FAILED` REDRAW Drawing이 있으면 다른 RedrawRequest는
  Snapshot·제외 명단·Seed를 새로 확정하지 않고 `CONCURRENT_COMMAND`로 거부한다. 이는 저장된 제외 명단을
  재사용하는 Retry보다 후속 REDRAW Winner가 먼저 확정되는 것을 막는다. 해당 Drawing이 성공 또는 비재시도 실패로
  종결된 뒤 같은 실행 API로 다시 요청하면 최신 Winner를 반영해 준비한다.
- Retry는 `POST /api/admin/drawings/{drawingId}/retry`를 사용한다. 기존 Snapshot·Seed·알고리즘·결원 수와
  제외 명단을 재사용하며, 상품이 있으면 최초 실행과 동일한 고정 결원 상품을 rank 순서로 승계한다.
- 실행 종료 시 `EXECUTED`, `INSUFFICIENT_CANDIDATES`, `FAILED` 중 하나와 `completedAt`을 기록하고
  `redraw_execution_history`에 결과를 남긴다. 시스템3의 Drawing·Seed·입력 보존 뒤 발생한 RuntimeException과
  BusinessException은 실행 Transaction을 먼저 Rollback한 뒤 별도 `REQUIRES_NEW` Transaction에서 `FAILED`와
  이력을 확정한다. Drawing 생성 전의 Snapshot 검증·동시 실행·입력 확정 오류는 API 오류로 반환하고 요청은
  `PENDING`으로 남긴다. 실패·후보 부족은 결원 수를 변경하지 않는다.

DB 구조와 unique·foreign key 제약의 정본은 `src/main/resources/db/migration/`이다.
