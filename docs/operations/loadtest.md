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

- 같은 시나리오를 3번 돌려 중앙값을 쓴다. 추첨은 실행마다 1번이라 백분위수를 낼 수 없으므로 3회 측정값과 최대를 그대로 적는다. 실행 사이에는 데이터를 처음 상태로 되돌린다: 앱 정지 → Redis `FLUSHALL` → DB `DROP DATABASE cking; CREATE DATABASE cking;` → 앱 배포(Flyway) → 2번 시딩
- 기록: 이 저장소 커밋, Infra 커밋, 시딩·시나리오 변수, k6 요약, 해당 시간대 대시보드, `verify.sh` 출력, 인스턴스 사용 시간
- 끝나면 Cking-Infra `envs/loadtest`에서 `terraform destroy`, Parameter Store 비밀값 삭제. 운영은 `envs/prod`에서 `k6_enabled = false`로 k6 서버만 지운다

## 운영에서 실행

시딩·응모가 운영 데이터에 그대로 남는다. 실행 사이 되돌리기와 테스트 뒤 데이터 초기화는 운영 초기화 절차로 한다.

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
- 테스트가 끝나면 데이터 초기화와 함께 `/cking/prod/JWT_SECRET`을 새 값으로 바꾼다. k6 서버가 이 값으로 어느 회원의 Access Token이든 서명할 수 있었다
