# Snapshot 도메인

## 책임

- `CLOSED` Event의 공식 추첨 대상 명단을 확정한다.
- Event별 응모 내역을 회원 단위로 합산한다.
- 추첨 조건과 후보 목록을 정규화하여 Snapshot Hash를 생성한다.
- Event 하나에 공식 Snapshot 하나만 존재하도록 보장한다.

Snapshot은 마감 트랜잭션이 Commit된 뒤 별도 트랜잭션에서 생성한다. 생성된 Snapshot과 Candidate는 수정하거나 다시 생성하지 않는다.

## 내부 서비스 계약

### `OfficialSnapshotService.createIfAbsent(Long eventId)`

1. Event 행을 잠가 동일 Event의 동시 생성을 직렬화한다.
2. 이미 공식 Snapshot이 있으면 Event의 현재 상태와 관계없이 기존 결과를 반환한다.
3. Snapshot이 없으면 Event 상태가 `CLOSED`인지 검증한다.
4. `event_entry`를 `member_id`로 묶어 `used_ticket_count`를 합산한다.
5. 합계가 0보다 큰 후보만 `memberId ASC`로 정렬한다.
6. Snapshot Hash를 생성한다.
7. Snapshot과 Candidate를 한 트랜잭션으로 저장한다.

DB의 `UNIQUE(draw_snapshot.event_id)`는 애플리케이션 잠금 외의 최종 중복 방어선이다.

오류 코드는 다음과 같다.

| 코드 | 조건 |
| --- | --- |
| `EVENT_NOT_FOUND` | Event가 존재하지 않음 |
| `EVENT_NOT_CLOSED` | 공식 Snapshot이 없고 Event가 `CLOSED`가 아님 |

## Snapshot Hash 계약

Hash 알고리즘은 SHA-256이고 결과는 64자리 lowercase hex 문자열이다. 정규화 문자열은 UTF-8로 인코딩하며 줄바꿈은 LF(`\n`)만 사용한다. 마지막 Candidate 행 뒤에도 LF를 포함한다.

숫자는 부호 없는 10진수 문자열로 표현하며 0 채우기를 하지 않는다. Candidate는 입력 순서와 관계없이 `memberId ASC`로 정렬하고 동일 `memberId`를 중복해서 포함하지 않는다.

정규화 형식은 다음과 같다.

```text
CKING_SNAPSHOT_V1
eventId={eventId}
winnerCount={winnerCount}
drawMethod={drawMethod}
algorithmVersion={algorithmVersion}
candidates
{memberId},{ticketCount}
{memberId},{ticketCount}
```

후보가 없으면 `candidates\n`에서 끝난다. MVP의 `algorithmVersion`은 `WEIGHTED_V1`이다.

예시는 다음과 같다.

```text
CKING_SNAPSHOT_V1
eventId=10
winnerCount=2
drawMethod=WEIGHTED
algorithmVersion=WEIGHTED_V1
candidates
1,3
2,7
```

위 문자열의 SHA-256은 `a3a997ca2bed6ff1ad71484b6d13cc7a07dec9b0260c5bb040c55ddcb87ec281`이다.

추첨 직전 무결성 검증도 `SnapshotHashGenerator`와 동일한 계약을 사용해야 한다.
