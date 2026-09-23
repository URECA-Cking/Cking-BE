# Drawing 재현 검증 API

## 검증 계약

완료된 Drawing의 검증은 다음 세 책임을 순서대로 수행한다.

1. **저장 결과 무결성 검증**
   - Drawing이 참조한 당시 Snapshot을 현재 Event/응모 데이터와 무관하게 조회한다.
   - Snapshot Hash와 집계값을 다시 계산한다.
   - 원본 Seed, Algorithm Version, Exclusion List, `winnerCount`, 후보 명단으로 원본
     Input Payload/Hash를 다시 계산한다. 상품 Snapshot이 있으면 상품 구성과
     `prizeAlgorithmVersion`을 포함하는 V2 계약을 사용하고, 레거시 상품 없는 Snapshot은 V1을 유지한다.
   - 저장 Winner로 Result Payload/Hash를 다시 계산한다. V2에서는 Winner의 `snapshotPrizeId`와
     상품 식별자·표시명·우선순위가 공식 Snapshot 상품과 일치하는지도 확인한다.
2. **원본 Seed 결정적 재현 검증**
   - 원본 Seed와 당시 확정 입력으로 추첨 엔진을 다시 실행한다.
   - 재실행 Winner와 Rank가 저장 Winner와 일치하는지 확인한다.
   - 상품 Snapshot이 있으면 원본 Seed에서 상품 배정용 Seed를 다시 파생해 당첨자별 상품도 재현한다.
   - 재실행 Result Payload/Hash와 상품 배정이 저장 결과와 일치하는지 확인한다.
3. **새 Seed 독립 재실행 인원 수 검증**
   - 원본과 다른 새 Seed를 메모리에서 생성한다. 별도 `draw_seed` 행은 만들지 않는다.
   - 당시 Snapshot과 조건으로 추첨 엔진을 실행하고, 상품 Snapshot이 있으면 같은 새 Seed에서
     상품 배정용 Seed를 파생해 모든 당첨자에게 상품을 배정할 수 있는지도 검증한다.
   - 결과가 정확히 `winnerCount`명인지, 사용자 중복이 없는지, 모두 후보에 포함되고 제외 명단에는
     없는지, Rank가 `1..winnerCount`로 연속인지 검증한다.
   - 기존 Winner와 독립 재실행 Winner의 동일성 또는 순위 일치는 검사하지 않는다. 같은 사용자가
     우연히 다시 선정돼도 실패가 아니다.

원본 Seed 결정적 재현은 같은 Winner와 Rank를 만들어야 한다. 새 Seed 독립 재실행은 보조 검증이므로
당첨자 구성은 달라질 수 있지만 보존된 `winnerCount`와 후보 계약은 동일하게 만족해야 한다.

검증은 Event, Snapshot, Drawing, Winner를 변경하지 않는다. 결과만
`draw_verification_history`에 append-only로 저장한다.

## `POST /api/admin/drawings/{drawingId}/verify`

- 권한: `ADMIN`
- 호출자 식별: Access JWT의 `@CurrentMemberId`; Request Body `userId`는 받지 않는다.
- 대상: `COMPLETED` Drawing
- 검증 불일치는 HTTP 오류로 변환하지 않고 `200 OK`의 `status=VERIFICATION_FAILED`로 기록·반환한다.
- 리소스 없음, 권한 없음, 미완료 Drawing은 공통 오류 응답을 반환하며 검증 이력을 만들지 않는다.

성공 응답 예시:

```json
{
  "code": "SUCCESS",
  "data": {
    "verificationId": 100,
    "drawingId": 20,
    "status": "VERIFIED",
    "verificationMode": "DETERMINISTIC_AND_CARDINALITY_REPLAY",
    "expectedWinnerCount": 10,
    "actualWinnerCount": 10,
    "winnerCountMatched": true,
    "winnersUnique": true,
    "candidatesMatched": true,
    "exclusionsMatched": true,
    "ranksMatched": true,
    "snapshotHashMatched": true,
    "inputHashMatched": true,
    "resultHashMatched": true,
    "algorithmMatched": true,
    "failureCode": null,
    "failureMessage": null,
    "verifiedBy": 1,
    "verifiedAt": "2026-09-18T00:00:00Z"
  },
  "message": null
}
```

`VERIFICATION_FAILED`에서는 실행 단계에 따라 아직 계산하지 못한 재실행 Boolean과
`actualWinnerCount`가 `null`일 수 있다. 마이그레이션 전에 생성된 기존 이력은
`verificationMode=LEGACY_INTEGRITY`로 표시되며 `expectedWinnerCount`도 `null`일 수 있다.
신규 이력만 `DETERMINISTIC_AND_CARDINALITY_REPLAY`로 기록된다. `failureCode`는 다음 중 하나다.

| 코드 | 조건 |
| --- | --- |
| `SNAPSHOT_INTEGRITY_FAILED` | 당시 Snapshot이 없거나 Hash/집계값이 변조됨 |
| `DRAWING_CONTRACT_MISMATCH` | Drawing과 Snapshot의 Event/방식/후보·상품 알고리즘 버전/인원 조건이 다름 |
| `STORED_INPUT_INTEGRITY_FAILED` | 원본 Input Payload/Hash가 보존 입력과 다름 |
| `STORED_RESULT_INTEGRITY_FAILED` | Winner, 배정 상품 또는 Result Payload/Hash가 보존 결과와 다름 |
| `DETERMINISTIC_REPLAY_MISMATCH` | 원본 Seed 재실행의 Winner, Rank, 상품 배정 또는 Result Hash가 저장 결과와 다름 |
| `INSUFFICIENT_CANDIDATES` | 제외 명단 반영 후 후보 수가 `winnerCount`보다 적음 |
| `UNSUPPORTED_ALGORITHM_VERSION` | 저장된 알고리즘 버전을 현재 서버가 지원하지 않음 |
| `REPLAY_RESULT_INVALID` | 재실행 결과의 인원·중복·후보·제외·Rank 계약이 틀림 |
| `VERIFICATION_EXECUTION_FAILED` | 위 항목으로 분류되지 않은 검증 실행 실패 |

## `GET /api/admin/drawings/{drawingId}/verification-history`

- 권한: `ADMIN`
- 호출자 식별: Access JWT의 `@CurrentMemberId`; query parameter `userId`는 받지 않는다.
- Page query: `page` 기본 0, `size` 기본 20·최대 100
- 정렬: `verifiedAt DESC`, 동일 시각이면 `id DESC`
- 응답: 공통 Page 형식의 `items`에 POST 응답과 같은 검증 결과를 반환한다.

## 공통 오류

| 코드 | 조건 |
| --- | --- |
| `VALIDATION_FAILED` | 식별자, Body 또는 Page 범위가 올바르지 않음 |
| `RESOURCE_NOT_FOUND` | 요청 Member가 존재하지 않음 |
| `FORBIDDEN` | 요청 Member가 ADMIN이 아님 |
| `DRAWING_NOT_FOUND` | Drawing이 존재하지 않음 |
| `DRAWING_NOT_COMPLETED` | Drawing이 `COMPLETED` 상태가 아님 |

## 영속성

`V7__extend_drawing_verification_history.sql`은 기존 무결성 Boolean에 다음 증거를 추가한다.

- 검증 모드와 독립 재실행 Seed
- 기대/실제 당첨 인원 수
- 인원 수, 중복, 후보 포함, 제외 명단, Rank 검증 결과

V7 이전 기존 행은 실제 수행 범위를 보존하기 위해 `verificationMode=LEGACY_INTEGRITY`로
backfill한다. V7 이후 생성되는 이력은 애플리케이션에서
`DETERMINISTIC_AND_CARDINALITY_REPLAY`를 명시한다.

독립 재실행 Seed는 이력에만 보존하며 운영 Drawing의 `seed_id`나 결과에는 영향을 주지 않는다.
