# Drawing 도메인

## 책임

- 공식 추첨과 재추첨의 실행 상태 및 확정 입력·출력을 저장한다.
- 추첨 결과를 순위가 있는 Winner로 저장한다.
- Winner별 후속 운영 상태를 WinnerManagement로 관리한다.

Drawing 도메인은 Event, Snapshot, Member, Seed 등 다른 도메인의 Entity를 직접 참조하지 않고 `Long` 식별자만 저장한다. 다른 도메인의 상태 변경은 해당 도메인의 Service를 통해 수행한다.

## 영속성 모델

### Drawing

- Event당 INITIAL Drawing은 `drawNo = 0`, `drawType = INITIAL`이다.
- INITIAL Drawing은 `originalDrawingId`와 `redrawRequestId`를 갖지 않는다.
- 최초 상태는 `READY`, 공개 상태는 `PRIVATE`, 시도 횟수는 0이다.
- `(eventId, drawNo)`와 `seedId`는 각각 유일하다.
- INITIAL Drawing의 `snapshotId`, `eventId`, `drawMethod`, `algorithmVersion`, `winnerCount`는 `VerifiedSnapshot`으로만 생성할 수 있는 하나의 `DrawingSnapshotContract`에서 가져온다.
- `snapshotId`, `eventId`, `drawMethod`, `algorithmVersion` 일치는 DB 복합 FK로도 강제한다. REDRAW의 `winnerCount`는 결원 수이므로 Snapshot 원본 당첨자 수와 다를 수 있다.
- 동시 명령 감지를 위해 `version`을 낙관적 락 필드로 사용한다.

상태는 `READY`, `RUNNING`, `FAILED`, `COMPLETED`를 사용하고 공개 상태는 `PRIVATE`, `PUBLIC`을 사용한다. 상태 전이 메서드는 실행 오케스트레이션 작업에서 추가한다.

### Winner

- Event, Drawing, Member를 각각 ID로 참조한다.
- Winner의 `eventId`는 연결된 Drawing의 `eventId`와 일치해야 하며 DB 복합 FK로도 강제한다.
- Drawing 안에서 `rankInDrawing`은 중복될 수 없다.
- 동일 Event에서 같은 Member가 다시 Winner가 될 수 없다.
- Drawing 결과 조회는 `rankInDrawing ASC`를 사용한다.

### WinnerManagement

- Winner 하나에 WinnerManagement 하나만 존재한다.
- 초기 상태는 `SELECTED`다.
- 후속 상태는 `RECEIVED`, `DECLINED`, `DISQUALIFIED`다.

## Repository 계약

- `DrawingRepository.findByEventIdAndDrawNo(eventId, drawNo)`: Event의 특정 차수 Drawing 조회
- `DrawingRepository.existsByEventIdAndDrawNo(eventId, drawNo)`: 중복 생성 사전 확인
- `WinnerRepository.findAllByDrawingIdOrderByRankInDrawingAsc(drawingId)`: 추첨 결과 순위 조회
- `WinnerRepository.existsByEventIdAndMemberId(eventId, memberId)`: Event 내 중복 당첨 확인
- `WinnerManagementRepository.findByWinnerId(winnerId)`: Winner 운영 상태 조회

DB 구조와 제약조건의 정본은 `src/main/resources/db/migration/`이다.
