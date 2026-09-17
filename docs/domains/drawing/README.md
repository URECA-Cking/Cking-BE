# Drawing 도메인

## WEIGHTED_V1 추첨 계약

`WEIGHTED_V1`은 응모권 수를 정수 가중치로 사용하여 복원 없이 당첨자를 선정한다.

1. 후보를 `memberId ASC`로 정규화한다.
2. 제외 대상의 가중치를 추첨 후보군에 포함하지 않는다.
3. 현재 가중치 합계가 `T`이면 결정적 난수 생성기로 `[0, T)`의 정수를 하나 생성한다.
4. 해당 정수가 포함된 누적 가중치 구간의 후보를 당첨자로 선정한다.
5. 선정된 후보의 가중치를 0으로 갱신하여 이후 순위에서 제외한다.
6. `winnerCount`만큼 반복하며 선정 순서를 Rank로 사용한다.

가중치 정규화에 부동소수점을 사용하지 않는다. 후보 정렬 기준과 난수 소비 횟수를 유지하므로 같은
Snapshot, Seed, Algorithm Version, Exclusion List, winnerCount는 항상 같은 Winner와 Rank를 만든다.

## 자료구조

누적 가중치의 구간 탐색과 당첨 후보 제거에는 Fenwick Tree를 사용한다.

- 후보군 구성: `O(candidateCount)`
- 당첨자 1명 선택 및 제거: `O(log candidateCount)`
- 전체 추첨: `O(candidateCount + winnerCount × log candidateCount)`
- 추가 공간: `O(candidateCount)`

단일 추첨에 적합한 선형 누적 탐색은 당첨자를 여러 명 선정할 때 매 순위마다 합계 계산과 후보 탐색을
반복한다. 정적 분포에서 유리한 누적합 이분 탐색, Hopscotch, Alias 방식은 당첨자 제거 후 분포가 매번
변하는 복원 없는 추첨에서 갱신 또는 재구성이 필요하므로 사용하지 않는다.
