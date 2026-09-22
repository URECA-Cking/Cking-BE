# 추첨 알고리즘 계약

후보 선정과 상품 배정은 서로 다른 순수 엔진과 알고리즘 버전을 사용한다. Event의
`drawMethod`와 `prizeAlgorithmVersion`을 독립적으로 선택하고 Snapshot에서 불변 입력으로 확정한다.

| `drawMethod` | 후보 알고리즘 | 상품 알고리즘 | 의미 |
|---|---|---|---|
| `UNIFORM` | `UNIFORM_V1` | `PRIZE_UNIFORM_V1` | 후보·상품 모두 균등 |
| `UNIFORM` | `UNIFORM_V1` | `PRIZE_WEIGHTED_V1` | 후보 균등, 상품 가중 |
| `WEIGHTED` | `WEIGHTED_V1` | `PRIZE_UNIFORM_V1` | 응모권 수 기반 후보, 상품 균등 |
| `WEIGHTED` | `WEIGHTED_V1` | `PRIZE_WEIGHTED_V1` | 응모권 수 기반 후보, 상품 가중 |

## 후보 선정

- `UNIFORM_V1`은 응모권 수를 확률에 사용하지 않고, 제외 대상을 빼고 각 후보를 동일한 확률로
  복원 없이 선정한다. Winner의 `appliedTicketCount`는 감사 정보로 그대로 보존한다.
- `WEIGHTED_V1`은 `CandidateValue.ticketCount`를 정수 가중치로 사용하고 Fenwick Tree로
  누적 구간 선택과 당첨 후보 제거를 `O(log candidateCount)`에 처리한다.
- 두 방식 모두 후보를 `memberId ASC`로 정규화하고 `winnerCount`만큼 중복 없이 선정한다.

## 상품 배정

- `PRIZE_UNIFORM_V1`은 재고가 남은 각 상품 등급을 동일한 확률로 선택한다. `weight`는 양의 정수로
  저장하지만 선택 확률에는 사용하지 않는다.
- `PRIZE_WEIGHTED_V1`은 재고가 남은 상품의 `weight`를 누적 정수 구간으로 사용한다.
- 입력은 `priority ASC, prizeKey ASC`, 당첨자는 `rank ASC`로 정규화하고 배정 후 재고를 1개
  차감한다. 소진된 상품은 이후 선택에서 제외한다.
- 상품 가중치를 후보 선정 엔진에 전달하지 않는다.

### REDRAW 상품 승계

REDRAW는 `vacancyCount`만큼 전체 후보 풀에서 새 Winner를 선정하지만, 상품을 다시 추첨하지 않는다.
`redraw_request_vacancy`에 고정한 결원 Winner의 상품을 해당 행의 생성 순서대로 새 Winner의 `rank ASC`에
승계한다. 따라서 REDRAW의 상품 풀은 Snapshot 전체 재고가 아니라 고정 결원의 상품별 수량이며, 이 풀을
V2 Input Hash에 기록한다.

## 결정성과 검증

상품 배정은 Drawing Seed에 `CKING_PRIZE_ALLOCATION_V1` 도메인을 더해 SHA-256으로 파생한
Seed를 사용한다. 같은 Snapshot, Seed, 후보·상품 알고리즘 버전, 제외 대상,
`winnerCount`는 항상 같은 Winner, Rank, 상품 배정을 만든다. 선택된 두 알고리즘 버전과
상품 설정·배정 결과는 Snapshot/Input/Result Hash에 포함한다.
