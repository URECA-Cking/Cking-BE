#!/usr/bin/env bash
# 부하 테스트 뒤 정합성 검증. 테스트 앱 서버(loadtest-cking-app)에서 root로 실행하며 읽기만 한다.
# 동시 요청(race.js)까지 모든 시나리오가 끝난 뒤 실행한다.
# 사용법: verify.sh [마감 측정 이벤트 ID]
# k6 쪽 대조(성공 건수, 마감 뒤 성공 0건)는 docs/operations/loadtest.md의 명령으로 k6 서버에서 한다.
set -euo pipefail

CLOSING_EVENT_ID="${1:-}"
REGION="ap-northeast-2"
PARAM_PATH="/cking/loadtest"
MYSQL_IMAGE="mysql:8.4"

param() {
  aws ssm get-parameter --region "$REGION" --name "$PARAM_PATH/$1" --with-decryption \
    --query 'Parameter.Value' --output text
}
DB_HOST=$(param DB_HOST)
DB_USER=$(param DB_USERNAME)
# 비밀번호는 환경변수로만 mysql에 넘긴다. 명령줄·출력에 남기지 않는다.
MYSQL_PWD=$(param DB_PASSWORD)
export MYSQL_PWD

sql() {
  docker run --rm -i -e MYSQL_PWD --network host "$MYSQL_IMAGE" \
    mysql -h "$DB_HOST" -u "$DB_USER" -N -B cking -e "SET time_zone = '+00:00'; $1"
}
redis() {
  docker exec -i cking-redis redis-cli "$@"
}

failures=0
result() {
  if [[ "$2" == "PASS" ]]; then echo "PASS  $1  $3"; else echo "FAIL  $1  $3"; failures=$((failures + 1)); fi
}

echo "== 1. 처리 완료 (lag·pending 0, 미해결 Dead Stream 0)"
for stream in stream:ticket-deducted stream:ticket-earned stream:common-ticket-earned; do
  info=$(redis XINFO GROUPS "$stream" | paste - - )
  lag=$(awk '$1 == "lag" {print $2}' <<< "$info")
  pending=$(awk '$1 == "pending" {print $2}' <<< "$info")
  if [[ "$lag" == "0" && "$pending" == "0" ]]; then status=PASS; else status=FAIL; fi
  result "$stream" "$status" "lag=$lag pending=$pending"
done
dead=$(sql "SELECT COUNT(*) FROM dead_stream_message WHERE resolved_at IS NULL")
[[ "$dead" == "0" ]] && status=PASS || status=FAIL
result "dead_stream_message" "$status" "미해결=$dead"

echo "== 2. 이벤트별 DB 반영 건수 (k6 성공 건수와 대조)"
sql "SELECT event_id, COUNT(*), SUM(used_ticket_count) FROM event_entry GROUP BY event_id ORDER BY event_id" |
  awk '{printf "      event_id=%s entries=%s tickets=%s\n", $1, $2, $3}'

echo "== 3. Redis 잔액 = DB 잔액, 음수 0"
workdir=$(mktemp -d)
trap 'rm -rf "$workdir"' EXIT
sql "SELECT creator_id, member_id, balance FROM user_ticket_balance" |
  awk '{print $1 ":" $2, $3}' | LC_ALL=C sort > "$workdir/db"
# 측정이 끝난 뒤라 KEYS로 한 번에 읽는다.
redis EVAL "local r = {} for _, k in ipairs(redis.call('KEYS', ARGV[1])) do r[#r + 1] = k .. ' ' .. redis.call('GET', k) end return r" \
  0 'ticket:balance:[0-9]*' | awk 'NF == 2 {split($1, k, ":"); print k[3] ":" k[4], $2}' | LC_ALL=C sort > "$workdir/redis"
# 한쪽에만 있는 키도 불일치다. 공통 키만 비교하면 Redis 키가 빠져도 통과한다.
db_only=$(LC_ALL=C join -v1 "$workdir/db" "$workdir/redis" | awk 'END {print NR}')
redis_only=$(LC_ALL=C join -v2 "$workdir/db" "$workdir/redis" | awk 'END {print NR}')
mismatch=$(LC_ALL=C join "$workdir/db" "$workdir/redis" | awk '$2 != $3 {n++} END {print n + 0}')
if [[ "$db_only" == "0" && "$redis_only" == "0" && "$mismatch" == "0" ]]; then status=PASS; else status=FAIL; fi
result "Redis·DB 잔액" "$status" "DB=$(awk 'END {print NR}' "$workdir/db") Redis=$(awk 'END {print NR}' "$workdir/redis") DB에만=$db_only Redis에만=$redis_only 값 불일치=$mismatch"
negative=$(awk '$2 < 0 {n++} END {print n + 0}' "$workdir/redis")
[[ "$negative" == "0" ]] && status=PASS || status=FAIL
result "Redis 음수 잔액" "$status" "음수=$negative"

echo "== 4. 잔액 = 원장 합계"
ledger=$(sql "SELECT COUNT(*) FROM user_ticket_balance b
  LEFT JOIN (SELECT member_id, creator_id, SUM(delta_amount) AS total FROM ticket_ledger GROUP BY member_id, creator_id) l
    USING (member_id, creator_id)
  WHERE l.total IS NULL OR b.balance <> l.total")
[[ "$ledger" == "0" ]] && status=PASS || status=FAIL
result "잔액·원장" "$status" "불일치(원장 없음 포함)=$ledger"

if [[ -n "$CLOSING_EVENT_ID" ]]; then
  echo "== 5. 마감 경계 (event_id=$CLOSING_EVENT_ID)"
  event_row=$(sql "SELECT status, end_at, cutoff_stream_id, closed_at,
         TIMESTAMPDIFF(MICROSECOND, FROM_UNIXTIME(SUBSTRING_INDEX(cutoff_stream_id, '-', 1) / 1000), closed_at) / 1000
       FROM event WHERE event_id = $CLOSING_EVENT_ID")
  awk -F'\t' '{printf "      status=%s end_at=%s cutoff=%s closed_at=%s 마감시작→CLOSED=%sms\n", $1, $2, $3, $4, $5}' <<< "$event_row"
  event_status=$(cut -f1 <<< "$event_row")
  [[ "$event_status" == "CLOSED" ]] && status=PASS || status=FAIL
  result "마감 상태" "$status" "status=${event_status:-이벤트 없음}"
  snapshot=$(sql "SELECT s.candidate_count, s.total_ticket_count, e.members, e.tickets,
         TIMESTAMPDIFF(MICROSECOND, ev.closed_at, s.created_at) / 1000
    FROM draw_snapshot s
    JOIN event ev USING (event_id)
    JOIN (SELECT event_id, COUNT(DISTINCT member_id) AS members, SUM(used_ticket_count) AS tickets
          FROM event_entry WHERE event_id = $CLOSING_EVENT_ID GROUP BY event_id) e USING (event_id)
    WHERE s.event_id = $CLOSING_EVENT_ID")
  if [[ -z "$snapshot" ]]; then
    result "Snapshot·응모" FAIL "Snapshot 또는 응모 없음"
  else
    read -r candidates snapshot_tickets entry_members entry_tickets snapshot_ms <<< "$snapshot"
    if [[ "$candidates" == "$entry_members" && "$snapshot_tickets" == "$entry_tickets" ]]; then status=PASS; else status=FAIL; fi
    result "Snapshot·응모" "$status" "후보=$candidates/응모 회원=$entry_members 응모권=$snapshot_tickets/$entry_tickets"
    echo "      CLOSED→Snapshot 생성=${snapshot_ms}ms"
  fi
fi

# 기대값은 시딩에서 구한다. race.js는 앞쪽 절반 회원으로 같은 요청 라운드, 뒤쪽 절반으로 잔액 초과 라운드를 돈다.
echo "== 6. 동시 요청 회원 잔액 (같은 요청 라운드 = 시작 잔액 - 2, 잔액 초과 라운드 = 0)"
race=$(sql "SELECT r.cnt, s.start, COUNT(b.member_id),
       COALESCE(SUM(b.balance <> IF(b.member_id < r.first + r.cnt DIV 2, s.start - 2, 0)), 0)
  FROM (SELECT MIN(member_id) AS first, COUNT(*) AS cnt FROM member WHERE name LIKE 'lt-race-%') r
  CROSS JOIN (SELECT MAX(l.delta_amount) AS start FROM ticket_ledger l JOIN member m USING (member_id)
              WHERE m.name LIKE 'lt-race-%' AND l.type = 'COMPENSATE') s
  LEFT JOIN user_ticket_balance b
    ON b.creator_id = 1 AND b.member_id BETWEEN r.first AND r.first + r.cnt - 1
  GROUP BY r.cnt, s.start")
read -r race_members race_start race_rows race_bad <<< "$race"
if [[ "$race_members" != "0" && "$race_rows" == "$race_members" && "$race_bad" == "0" ]]; then status=PASS; else status=FAIL; fi
result "동시 요청 잔액" "$status" "회원=$race_members 시작 잔액=$race_start 잔액 행=$race_rows 기대값과 다름=$race_bad"

echo "== 결과: 실패 $failures건"
exit $(( failures > 0 ))
