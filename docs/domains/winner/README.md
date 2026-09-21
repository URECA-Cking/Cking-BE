# Winner 도메인

Winner는 추첨 결과의 불변 원본이고, WinnerManagement가 운영 상태를 소유한다. 상태가 바뀌어도
Winner 원본과 추첨 순위·응모권 수는 수정하지 않는다.

## 상태 전이

| 현재 상태 | 수행 주체 | 다음 상태 | 조건 |
| --- | --- | --- | --- |
| `SELECTED` | 당첨자 본인 | `DECLINED` | 본인 확인 |
| `SELECTED` | 관리자 | `RECEIVED` | 관리자 역할 확인 |
| `SELECTED` | 관리자 | `DISQUALIFIED` | 관리자 역할 확인, 자격 박탈 사유 필수 |

`RECEIVED`, `DECLINED`, `DISQUALIFIED`는 종결 상태다. 종결 상태의 재변경과 `SELECTED`로의
역방향 전이는 허용하지 않는다.

## 상태 이력과 동시성

- 상태 전이와 `WinnerStatusHistory` 저장은 하나의 Transaction에서 수행한다.
- 이력에는 전이 후 상태, 변경 주체, 변경 시각을 저장한다. 자격 박탈은 감사 가능성을 위해 공백을
  제외한 1~500자 사유를 함께 저장한다.
- 동일 Winner의 상태 명령은 WinnerManagement 행을 비관적 쓰기 잠금으로 조회해 직렬화한다.
- `DECLINED`와 `DISQUALIFIED`는 Redraw 결원 계산 대상이며, 자격 박탈 API가 Redraw를 자동 생성하지는 않는다.
