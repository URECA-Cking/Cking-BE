#!/usr/bin/env bash
# 부하 테스트 뒤 정합성 검증. 테스트 앱 서버(loadtest-cking-app)에서 root로 실행하며 읽기만 한다.
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
sql "SELECT creator_id, member_id, balance FROM user_ticket_balance ORDER BY creator_id, member_id" |
  sort > "$workdir/db"
# 측정이 끝난 뒤라 KEYS로 한 번에 읽는다.
redis EVAL "local r = {} for _, k in ipairs(redis.call('KEYS', ARGV[1])) do r[#r + 1] = k .. ' ' .. redis.call('GET', k) end return r" \
  0 'ticket:balance:[0-9]*' | awk '{split($1, k, ":"); print k[3] "\t" k[4] "\t" $2}' | sort > "$workdir/redis"
loaded=$(wc -l < "$workdir/redis")
mismatch=$(join -j1 <(awk '{print $1":"$2"\t"$3}' "$workdir/db" | sort) \
  <(awk '{print $1":"$2"\t"$3}' "$workdir/redis" | sort) | awk '$2 != $3' | wc -l)
[[ "$mismatch" == "0" ]] && status=PASS || status=FAIL
result "Redis·DB 잔액" "$status" "Redis 적재 조합=$loaded 불일치=$mismatch"
negative=$(awk '$3 < 0' "$workdir/redis" | wc -l)
[[ "$negative" == "0" ]] && status=PASS || status=FAIL
result "Redis 음수 잔액" "$status" "음수=$negative"

echo "== 4. 잔액 = 원장 합계"
ledger=$(sql "SELECT COUNT(*) FROM user_ticket_balance b
  JOIN (SELECT member_id, creator_id, SUM(delta_amount) AS total FROM ticket_ledger GROUP BY member_id, creator_id) l
    USING (member_id, creator_id)
  WHERE b.balance <> l.total")
[[ "$ledger" == "0" ]] && status=PASS || status=FAIL
result "잔액·원장" "$status" "불일치=$ledger"

if [[ -n "$CLOSING_EVENT_ID" ]]; then
  echo "== 5. 마감 경계 (event_id=$CLOSING_EVENT_ID)"
  sql "SELECT status, end_at, cutoff_stream_id, closed_at,
         TIMESTAMPDIFF(MICROSECOND, FROM_UNIXTIME(SUBSTRING_INDEX(cutoff_stream_id, '-', 1) / 1000), closed_at) / 1000
       FROM event WHERE event_id = $CLOSING_EVENT_ID" |
    awk -F'\t' '{printf "      status=%s end_at=%s cutoff=%s closed_at=%s 마감시작→CLOSED=%sms\n", $1, $2, $3, $4, $5}'
  snapshot=$(sql "SELECT s.candidate_count, s.total_ticket_count, e.members, e.tickets
    FROM draw_snapshot s
    JOIN (SELECT event_id, COUNT(DISTINCT member_id) AS members, SUM(used_ticket_count) AS tickets
          FROM event_entry WHERE event_id = $CLOSING_EVENT_ID GROUP BY event_id) e USING (event_id)
    WHERE s.event_id = $CLOSING_EVENT_ID")
  read -r candidates snapshot_tickets entry_members entry_tickets <<< "${snapshot:-x x x x}"
  if [[ "$candidates" == "$entry_members" && "$snapshot_tickets" == "$entry_tickets" ]]; then status=PASS; else status=FAIL; fi
  result "Snapshot·응모" "$status" "후보=$candidates/응모 회원=$entry_members 응모권=$snapshot_tickets/$entry_tickets"
  sql "SELECT TIMESTAMPDIFF(MICROSECOND, e.closed_at, s.created_at) / 1000
       FROM event e JOIN draw_snapshot s USING (event_id) WHERE e.event_id = $CLOSING_EVENT_ID" |
    awk '{printf "      CLOSED→Snapshot 생성=%sms\n", $1}'
fi

echo "== 6. 동시 요청 회원 잔액 (race.js 실행 시. 같은 요청 라운드 = 시작 잔액 - 2, 잔액 초과 라운드 = 0)"
sql "SELECT b.balance, COUNT(*) FROM user_ticket_balance b JOIN member m USING (member_id)
     WHERE m.name LIKE 'lt-race-%' GROUP BY b.balance ORDER BY b.balance" |
  awk '{printf "      balance=%s 회원=%s\n", $1, $2}'

echo "== 결과: 실패 $failures건"
exit $(( failures > 0 ))
