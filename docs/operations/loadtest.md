# 부하 테스트 실행

`loadtest/` 스크립트로 응모 → DB 반영 → 마감 → 추첨의 기준선을 잰다. 환경은 Cking-Infra `envs/loadtest`(테스트 앱 서버, RDS, k6 서버)와 운영이며 개발 서버에는 실행하지 않는다(`lib/api.js`가 `BASE_URL`을 부하 테스트 주소와 확인한 운영 주소로 제한한다). 운영에서 달라지는 점은 [운영에서 실행](#운영에서-실행)에 있다.

서버 이름: **테스트 앱 서버**(`loadtest-cking-app`, 앱·Redis), **k6 서버**(`loadtest-cking-k6`), **테스트 RDS**(`loadtest-cking-mysql`). 개발 서버(`dev-cking-app`)와는 별개다.

시나리오 순서: 예열 → 응모 한계 측정 → 응모 지속 측정 → 마감 직전 몰림 → 추첨 → 동시 요청. 정합성 검증은 시나리오가 끝난 뒤의 확인이고, 장애 복구 실험은 기준선 뒤 별도로 한다.

## 구성

| 파일 | 실행 위치 | 단계 | 내용 |
| --- | --- | --- | --- |
| `seed.sql` | 테스트 앱 서버 | 준비 | 회원·크리에이터·응모용 이벤트·시작 잔액(같은 금액의 원장 행)·관리자 회원·동시 요청용 회원 |
| `seed-closing.sql` | 테스트 앱 서버 | 마감 직전 몰림 전 | 정한 분 수 뒤에 끝나는 마감 측정 이벤트 |
| `warmup.js` | k6 서버 | 예열 | 회원 × 크리에이터 조합마다 응모 1번. Redis 잔액 적재. 측정값은 쓰지 않는다 |
| `entry-limit.js` | k6 서버 | 응모 한계 측정 | 도착률을 단계마다 올려 한계를 찾는다 |
| `entry-sustain.js` | k6 서버 | 응모 지속 측정 | SLO 처리량으로 유지. SLO를 합격선(thresholds)으로 판정 |
| `closing-burst.js` | k6 서버 | 마감 직전 몰림 | 종료 시각 전후로 일정한 도착률 |
| `draw.js` | k6 서버 | 추첨 | 마감된 이벤트로 추첨 실행 → 재현 검증 |
| `race.js` | k6 서버 | 동시 요청 | 같은 requestId 동시 재전송, 잔액보다 많은 동시 응모 |
| `verify.sh` | 테스트 앱 서버 | 검증 | 처리 완료, Redis·DB 잔액, 잔액·원장, 마감 경계, Snapshot 생성 시간, 동시 요청 회원 잔액 |
| `lib/token.js`, `lib/api.js` | | | Access Token 생성(HS256, 메모리에서만), 응모 요청·결과 기록 |

응모권은 시작 잔액으로만 준다. 적립 경로(게시글 좋아요)는 측정 대상이 아니다.

## 규모와 시작 잔액

시나리오는 요청 번호로 회원·크리에이터 조합을 순서대로 돌려 쓴다. 한 번의 k6 실행에서 조합당 사용량은 `ceil(요청 수 ÷ 조합 수)`를 넘지 않고, 요청 번호는 실행마다 0부터 다시 센다.

```text
조합 수        = MEMBERS × CREATORS
조합당 사용량  = 1(예열) + ceil(응모 한계 측정 요청 수 ÷ 조합 수) + ceil(응모 지속 측정 요청 수 ÷ 조합 수)
시작 잔액      = 조합당 사용량 + 1
마감 이벤트 크리에이터 = 위 값 + ceil(마감 직전 몰림 요청 수 ÷ MEMBERS)  (마감 이벤트는 크리에이터 하나만 쓴다)
요청 수        = 도착률 × 시간(초)
```

동시 요청용 회원(`@race_members`, 이름 `lt-race-N`)은 위 계산과 별개이며 크리에이터 1의 잔액 `@race_balance`만 가진다. `race.js`는 라운드마다 회원 한 명을 쓰고 같은 요청 라운드 `ROUNDS`번, 잔액 초과 라운드 `ROUNDS`번을 돈다.

```text
@race_members = ROUNDS × 2
@race_balance - 1 < CONCURRENCY   (예열 1건 뒤 남은 잔액보다 동시 요청이 많아야 잔액 초과가 된다)
```

시작 잔액이 모자라면 응답이 `INSUFFICIENT_BALANCE`로 바뀌어 Stream·DB 반영 없이 바로 거절된다. 측정이 거절 속도를 재게 되므로 실행 전에 계산한다.

## 순서

환경 생성·앱 배포·수집기는 Cking-Infra README의 "부하 테스트 환경"을 따른다. 아래 명령의 `<커밋>`은 측정에 쓰는 이 저장소의 커밋이며 기록에 남긴다.

### 1. 스크립트 받기 (테스트 앱 서버, k6 서버)

```bash
curl -fsSL https://codeload.github.com/URECA-Cking/Cking-BE/tar.gz/<커밋> | tar -xz
cd Cking-BE-<커밋>/loadtest
```

### 2. 시딩 (테스트 앱 서버)

앱이 기동해 Flyway 마이그레이션을 마친 뒤 실행한다. `seed.sql`은 막 만든 DB인지(회원은 앱이 dev·prod 프로필로 기동하며 만든 관리자 초기 계정 하나뿐, 크리에이터·이벤트·잔액·원장 0건) 먼저 확인하고, 아니면 `seed_target_is_fresh_loadtest_db` 위반으로 멈춘다. 확인을 통과하면 회원 ID가 겹치는 관리자 초기 계정을 한 트랜잭션으로 지운다. 비밀번호는 환경변수로만 넘긴다.

```bash
P=/cking/loadtest; R=ap-northeast-2   # 운영은 P=/cking/prod
DB_HOST=$(aws ssm get-parameter --region $R --name $P/DB_HOST --query Parameter.Value --output text)
DB_USER=$(aws ssm get-parameter --region $R --name $P/DB_USERNAME --with-decryption --query Parameter.Value --output text)
MYSQL_PWD=$(aws ssm get-parameter --region $R --name $P/DB_PASSWORD --with-decryption --query Parameter.Value --output text); export MYSQL_PWD
docker run --rm -i -e MYSQL_PWD --network host -v "$PWD:/lt:ro" mysql:8.4 \
  mysql -h "$DB_HOST" -u "$DB_USER" cking -e "SET @creators = 5, @members = 10000, @start_balance = 100, @entry_hours = 24, @race_members = 40, @race_balance = 11; source /lt/seed.sql"
```

출력의 `admin_member_id`는 추첨에, `race_first_member_id`는 동시 요청에 쓴다. 이벤트 게이트는 앱 스케줄러가 10초 안에 Redis에 올린다.

### 3. k6 실행 준비 (k6 서버)

```bash
source /etc/profile.d/k6.sh
P=/cking/loadtest   # 운영은 P=/cking/prod
export BASE_URL=https://loadtest-api.cking.co.kr CREATORS=5 MEMBERS=10000   # 운영은 BASE_URL=https://api.cking.co.kr CONFIRM_PROD=api.cking.co.kr
export K6_PROMETHEUS_RW_TREND_STATS="p(50),p(95),p(99),max"
JWT_SECRET="$(aws ssm get-parameter --region ap-northeast-2 --name $P/JWT_SECRET --with-decryption --query Parameter.Value --output text)"
export JWT_SECRET
mkdir -p results
```

모든 실행은 `-o experimental-prometheus-rw --tag testid=<실행 이름> --log-format raw --console-output results/<실행 이름>.log`를 붙인다. 결과 지표가 관측 서버로 가고, 요청별 기록이 파일에 남는다.

### 4. 시나리오

| 단계 | 명령(공통 옵션 생략) | 비고 |
| --- | --- | --- |
| 예열 | `k6 run warmup.js` | 응답 코드가 `SUCCESS`인지, 대시보드에 지표가 잡히는지 확인. 지표가 안정되는 시간과 흔들림 폭을 기록 |
| 응모 한계 측정 | 넓게: `START_RATE=50 STEP_RATE=50 STEPS=20 HOLD=1m k6 run entry-limit.js`(1,000까지. 500에서 무너지면 그 자리에서 멈춘다) → 좁게: 무너진 구간을 작은 간격·`HOLD=3m`으로 | 계획의 한계 판단 기준(p95 급증, 실패율, 자원 포화) 직전 단계가 한계. 1,000에서도 버티면 "1,000 이상"으로 기록 |
| SLO v0 | | 응모 한계 측정 결과로 확정. DB 반영 지연·마감 시간·처리량은 팀 확인 |
| 응모 지속 측정 | `RATE=<SLO 처리량> DURATION=<예열 + 관찰 구간> SLO_P95_MS=1000 SLO_FAIL_RATE=0.001 k6 run entry-sustain.js` | 관찰 구간 최소 15분(잔액 대조 주기 5분 × 3) |
| 마감 직전 몰림 | 테스트 앱 서버에서 `seed-closing.sql` 실행(`SET @closing_creator = 1, @closing_minutes = 5;`) → 출력의 `closing_event_id`, `end_at_ms`로 `CLOSING_EVENT_ID=… END_AT_MS=… RATE=… DURATION=… k6 run closing-burst.js` | 종료 시각 전에 시작해 종료 뒤까지 이어지게 DURATION을 정한다 |
| 추첨 | 이벤트가 CLOSED가 된 뒤 `CLOSING_EVENT_ID=… ADMIN_MEMBER_ID=… k6 run draw.js` | `verify.sh <이벤트 ID>`의 status로 CLOSED 확인 |
| 동시 요청 | `RACE_FIRST_MEMBER_ID=… ROUNDS=20 CONCURRENCY=50 k6 run race.js` | 회원마다 예열 1건 뒤, 라운드마다 모든 VU가 같은 시각에 보낸다(`ROUND_GAP_MS`, 기본 1초) |

k6가 목표 도착률을 못 맞추면(`dropped_iterations`) 실행이 멈춘다. 부하 발생기 한계이므로 k6 서버 크기를 올린다.

### 5. 정합성 검증

테스트 앱 서버(root). 동시 요청까지 모든 시나리오가 끝난 뒤 실행한다. Redis·DB 잔액은 키 전체를 비교하므로, 잔액 키가 적재되지 않은 조합(예열·동시 요청을 건너뛴 경우)은 실패로 나온다:

```bash
bash verify.sh <마감 이벤트 ID>
```

k6 서버, 이벤트별 성공 건수(테스트 앱 서버 출력의 `entries`와 같아야 한다):

```bash
python3 -c 'import json,sys,collections
c=collections.Counter()
for f in sys.argv[1:]:
    for l in open(f):
        if l.startswith("{"):
            d=json.loads(l)
            if d.get("kind")=="entry" and d["code"]=="SUCCESS": c[d["eventId"]]+=1
print(dict(sorted(c.items())))' results/*.log
```

k6 서버, 마감 뒤 성공 0건(k6와 서버 시계 차이를 1초 허용):

```bash
python3 -c 'import json,sys
end=int(sys.argv[2]); n=0
for l in open(sys.argv[1]):
    if l.startswith("{"):
        d=json.loads(l)
        if d.get("code")=="SUCCESS" and d["sentAt"]>end+1000: n+=1
print("마감 뒤 성공", n)' results/<마감 직전 몰림 실행 이름>.log <end_at_ms>
```

k6 서버, 동시 요청 라운드별 판정(같은 요청 = 성공 1건·나머지 `DUPLICATE_REPLAY`, 잔액 초과 = 성공 `@race_balance - 1`건·나머지 `INSUFFICIENT_BALANCE`). 송신 간격은 라운드 안에서 가장 이른 요청과 늦은 요청의 차이다:

```bash
python3 -c 'import json,sys,collections
bal=int(sys.argv[2]); r=collections.defaultdict(list); bad=0
for l in open(sys.argv[1]):
    if l.startswith("{"):
        d=json.loads(l)
        if d.get("phase") in ("race_same","race_over"): r[(int(d["round"]),d["phase"])].append(d)
for (n,p),ds in sorted(r.items()):
    c=collections.Counter(d["code"] for d in ds)
    want,rest=(1,"DUPLICATE_REPLAY") if p=="race_same" else (bal-1,"INSUFFICIENT_BALANCE")
    ok=c["SUCCESS"]==want and c["SUCCESS"]+c[rest]==len(ds)
    bad+=not ok
    spread=max(d["sentAt"] for d in ds)-min(d["sentAt"] for d in ds)
    print("PASS" if ok else "FAIL",p,"round",n,dict(c),"송신 간격",spread,"ms")
print("실패 라운드",bad)' results/<동시 요청 실행 이름>.log <@race_balance>
```

DB 쪽은 `verify.sh`의 6번이 같은 요청 라운드 회원(앞쪽 절반)이 `@race_balance - 2`, 잔액 초과 라운드 회원(뒤쪽 절반)이 0인지 판정한다. 기대값은 시딩 원장과 회원 수에서 구한다. 음수 잔액과 원장 일치는 3·4번이 모든 회원에 대해 확인한다.

### 6. 반복과 정리

- 같은 시나리오를 3번 돌려 중앙값을 쓴다. 추첨은 실행마다 1번이라 백분위수를 낼 수 없으므로 3회 측정값과 최대를 그대로 적는다. 실행 사이에는 데이터를 처음 상태로 되돌린다: 앱 정지 → Redis `FLUSHALL` → DB `DROP DATABASE cking; CREATE DATABASE cking;` → 앱 배포(Flyway) → 2번 시딩. 운영은 [운영 데이터 되돌리기](#운영-데이터-되돌리기)
- 기록: 이 저장소 커밋, Infra 커밋, 시딩·시나리오 변수, k6 요약, 해당 시간대 대시보드, `verify.sh` 출력, 인스턴스 사용 시간
- 끝나면 Cking-Infra `envs/loadtest`에서 `terraform destroy`, Parameter Store 비밀값 삭제. 운영은 `envs/prod`에서 `k6_enabled = false`로 k6 서버만 지운다

## 운영에서 실행

시딩·응모가 운영 데이터에 그대로 남는다. 실행 사이와 테스트가 끝난 뒤에는 [운영 데이터 되돌리기](#운영-데이터-되돌리기)로 처음 상태로 돌린다.

| 항목 | 부하 테스트 환경 | 운영 |
| --- | --- | --- |
| 시딩·검증 위치 | 테스트 앱 서버 | 운영 앱 서버 한 대(`prod-cking-app-<인스턴스 ID>`) |
| k6 서버 | `loadtest-cking-k6` | `prod-cking-k6`(Cking-Infra `envs/prod`에서 `k6_enabled = true`로 만든다) |
| 비밀값 경로 `P` | `/cking/loadtest` | `/cking/prod` |
| DB 계정(`$P/DB_USERNAME`) | RDS 마스터 | 앱 계정 `cking_app` |
| Redis | 앱 서버의 Redis 컨테이너 | ElastiCache(TLS, 앱 사용자 `$P/REDIS_USERNAME`). `verify.sh`가 `valkey-cli`로 접속한다 |
| k6 대상 | `BASE_URL=https://loadtest-api.cking.co.kr` | `BASE_URL=https://api.cking.co.kr CONFIRM_PROD=api.cking.co.kr` |
| 검증 | `bash verify.sh <마감 이벤트 ID>` | `PARAM_PATH=/cking/prod bash verify.sh <마감 이벤트 ID>` |

- k6 서버는 운영 프라이빗 앱 서브넷에 있어 요청이 NAT IP로 나간다. 운영 WAF는 NAT IP를 허용 목록으로 두므로 k6 요청은 IP당 요청 제한과 관리형 규칙을 건너뛰고, 측정값에 WAF 판정 시간이 들어가지 않는다

### 운영 데이터 되돌리기

k6 실행이 끝난 뒤, 모든 앱 서버의 앱을 멈추고 Redis·DB를 비운 다음 각 서버에서 앱을 다시 띄운다. 앱이 도는 중에 비우면 일정 작업(`EventLifecycleScheduler`, 10초 간격)이 남은 DB로 Redis Gate·집계를 다시 채우고, 새로 뜬 앱은 이미 있는 값을 덮어쓰지 않아(`event-gate-load.lua`의 `NX`) 다음 측정에 남는다. 앱 서버 그룹은 ALB 상태 검사로 서버를 판정하므로, 앱을 멈추기 전에 그룹의 자동 교체를 멈춘다.

테스트가 끝난 뒤에는 `JWT_SECRET`도 바꾼다. k6 서버가 이 값으로 어느 회원의 Access Token이든 서명할 수 있었다. 앱은 `deploy.sh`가 기동할 때 읽은 값으로 검증하므로, 앱을 다시 띄워야 새 키가 쓰인다. 진행 중에는 ALB 대상이 비정상이 되어 운영 경보가 울린다.

각 단계는 앞 단계가 성공했을 때만 진행한다. 5단계가 `초기화 완료`를 출력하지 않으면 6단계로 가지 않고, 원인을 고친 뒤 5단계를 다시 실행한다(다시 실행해도 결과가 같다).

1. (테스트 뒤에만) k6 서버, "3. k6 실행 준비"의 셸(`JWT_SECRET`이 이전 키)에서 확인용 토큰을 만든다. 토큰은 셸 변수에만 둔다:

   ```bash
   cat > token-check.js <<'EOF'
   import { tokenFactory } from './lib/token.js';
   export default function () { console.log(tokenFactory(__ENV.JWT_SECRET, 3600)(1)); }
   EOF
   OLD_TOKEN=$(k6 run -q --log-format raw token-check.js 2>&1 | grep '^ey')
   ```

2. Mac, 앱 서버 그룹의 자동 교체·확장을 멈추고 서버 목록을 본다:

   ```bash
   R=ap-northeast-2; ASG=prod-cking-app
   aws autoscaling suspend-processes --region $R --auto-scaling-group-name $ASG
   aws autoscaling describe-auto-scaling-groups --region $R --auto-scaling-group-names $ASG \
     --query 'AutoScalingGroups[0].Instances[].InstanceId' --output text
   ```

3. 앱 서버마다(root) 앱을 멈추고 멈췄는지 본다(`false`):

   ```bash
   docker stop cking-app
   docker inspect -f '{{.State.Running}}' cking-app
   ```

4. (테스트 뒤에만) Mac, 새 키를 넣는다. 값은 화면에 나오지 않는다:

   ```bash
   aws ssm put-parameter --region ap-northeast-2 --name /cking/prod/JWT_SECRET --type SecureString --overwrite \
     --value "$(openssl rand -base64 48)"
   ```

5. 앱 서버 한 대(root), Redis를 비우고 비워졌는지 확인한 뒤 DB를 비운다. 한 스크립트로 실행해 어느 명령이든 실패하면 그 자리에서 멈춘다. `valkey-cli`는 인증 실패(`NOAUTH`)·권한 없음(`NOPERM`)에도 종료 코드 0을 돌려주므로 응답이 `OK`인지 직접 본다:

   ```bash
   bash -euo pipefail <<'EOF'
   P=/cking/prod; R=ap-northeast-2
   param() { aws ssm get-parameter --region $R --name "$P/$1" --with-decryption --query Parameter.Value --output text; }
   DB_HOST=$(param DB_HOST); DB_USER=$(param DB_USERNAME); MYSQL_PWD=$(param DB_PASSWORD); export MYSQL_PWD
   REDIS_HOST=$(param REDIS_HOST); REDIS_USER=$(param REDIS_USERNAME); REDISCLI_AUTH=$(param REDIS_PASSWORD); export REDISCLI_AUTH
   vk() {
     docker run --rm -e REDISCLI_AUTH -v /etc/ssl/certs/ca-certificates.crt:/ca.crt:ro valkey/valkey:7.2 \
       valkey-cli --tls --cacert /ca.crt -h "$REDIS_HOST" --user "$REDIS_USER" "$@"
   }
   out=$(vk FLUSHALL); [[ "$out" == OK ]] || { echo "Redis 초기화 실패: $out" >&2; exit 1; }
   out=$(vk DBSIZE); [[ "$out" == 0 ]] || { echo "Redis에 키가 남음: $out" >&2; exit 1; }
   docker run --rm -e MYSQL_PWD --network host mysql:8.4 \
     mysql -h "$DB_HOST" -u "$DB_USER" -e "DROP DATABASE IF EXISTS cking; CREATE DATABASE cking;"
   echo "초기화 완료"
   EOF
   ```

6. 앱 서버마다(root) 한 대씩 앱을 다시 띄운다. `deploy.sh`가 Parameter Store를 다시 읽고 `/readyz`가 UP이 될 때까지 기다린다. 첫 서버의 앱이 Flyway로 스키마를, 관리자 초기 계정을 만들고, 다음 서버는 그 뒤에 띄운다. `deploy.sh`가 실패하면 다음 서버로 넘어가지 않는다:

   ```bash
   PARAM_PATH=/cking/prod/ /opt/cking/deploy.sh "$(aws ssm get-parameter --region ap-northeast-2 --name /cking/prod/RELEASE_TAG --query Parameter.Value --output text)"
   ```

7. Mac, ALB 대상이 모두 `healthy`가 된 뒤 자동 교체·확장을 다시 켠다. 비정상 대상이 남은 채로 켜면 그룹이 그 서버를 교체한다:

   ```bash
   TG=$(aws elbv2 describe-target-groups --region $R --names prod-cking-app-tg --query 'TargetGroups[0].TargetGroupArn' --output text)
   aws elbv2 describe-target-health --region $R --target-group-arn "$TG" --query 'TargetHealthDescriptions[].TargetHealth.State' --output text
   aws autoscaling resume-processes --region $R --auto-scaling-group-name $ASG
   ```

8. 확인. 앱 서버 한 대에서 5번처럼 DB 변수를 읽고 `SELECT (SELECT COUNT(*) FROM member), (SELECT COUNT(*) FROM admin_account), (SELECT COUNT(*) FROM creator), (SELECT COUNT(*) FROM event)`가 `1 1 0 0`인지 본다(`seed.sql`의 새 DB 조건). 테스트 뒤에는 k6 서버에서 이전 키 토큰은 401, 새 키 토큰은 401이 아닌지 본다. 새 키 토큰은 401이 키 때문인지 가르는 대조군이다:

   ```bash
   curl -s -o /dev/null -w '이전 키 %{http_code}\n' -H "Authorization: Bearer $OLD_TOKEN" https://api.cking.co.kr/api/me
   JWT_SECRET="$(aws ssm get-parameter --region ap-northeast-2 --name /cking/prod/JWT_SECRET --with-decryption --query Parameter.Value --output text)"
   export JWT_SECRET
   NEW_TOKEN=$(k6 run -q --log-format raw token-check.js 2>&1 | grep '^ey')
   curl -s -o /dev/null -w '새 키 %{http_code}\n' -H "Authorization: Bearer $NEW_TOKEN" https://api.cking.co.kr/api/me
   unset OLD_TOKEN NEW_TOKEN; rm token-check.js
   ```
