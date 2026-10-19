#!/usr/bin/env bash
# 개발 서버 배포 스크립트. SSM Run Command가 root로 실행한다.
# 사용법: deploy.sh <이미지 태그>
#         PARAM_PATH=/cking/loadtest/ deploy.sh <이미지 태그>  (읽을 Parameter Store 경로, 기본 /cking/dev/)
#
# 수동 롤백: 이전 태그를 인자로 주면 그 버전으로 되돌아간다.
set -euo pipefail

TAG="${1:?사용법: deploy.sh <이미지 태그>}"
REGION="ap-northeast-2"
BUCKET="dev-cking-deploy-551372961758"
PARAM_PATH="${PARAM_PATH:-/cking/dev/}"
APP_DIR="/opt/cking"
HEALTH_URL="http://localhost:8080/readyz"
HEALTH_TIMEOUT=90   # 초. 수동 배포 때 기동에 30초 안팎 걸렸고, RDS 연결 지연을 감안해 3배로 둔다.

cd "$APP_DIR"

echo "[1/4] 배포 파일 내려받기: s3://$BUCKET/releases/$TAG/"
aws s3 cp "s3://$BUCKET/releases/$TAG/docker-compose.yml" ./docker-compose.yml --region "$REGION"

echo "[2/4] Parameter Store 읽기: $PARAM_PATH"
# 필요한 값을 이름으로 지정해 하나라도 없으면 여기서 실패한다.
# 값을 Parameter Store에 등록한 기능만 목록에 넣는다. 기능을 켜고 끄는 것은 application-dev.yml이 맡는다.
# 값은 환경변수로만 넘기고 파일·로그에 남기지 않는다.
for name in DB_HOST DB_USERNAME DB_PASSWORD DOCS_USERNAME DOCS_PASSWORD \
            JWT_SECRET OAUTH_GOOGLE_CLIENT_ID OAUTH_GOOGLE_CLIENT_SECRET \
            OAUTH_KAKAO_CLIENT_ID OAUTH_KAKAO_CLIENT_SECRET \
            SUBSCRIPTION_GEMINI_API_KEY ADMIN_ACCOUNT_LOGIN_ID ADMIN_ACCOUNT_PASSWORD \
            GEMINI_API_KEY OPENAI_API_KEY; do
  value=$(aws ssm get-parameter --region "$REGION" --name "${PARAM_PATH}${name}" \
    --with-decryption --query 'Parameter.Value' --output text)
  export "$name=$value"
done

export IMAGE_TAG="$TAG"

# Stream 소비자 이름을 서버마다 다르게 한다. 인스턴스 ID는 서버를 새로 만들 때만 바뀐다.
# 메타데이터를 못 읽으면 앱이 컨테이너 호스트 이름을 쓴다(배포마다 바뀌므로 경고만 남긴다).
imds_token=$(curl -fsS -X PUT http://169.254.169.254/latest/api/token \
  -H "X-aws-ec2-metadata-token-ttl-seconds: 60") || imds_token=""
CKING_CONSUMER_ID=$(curl -fsS -H "X-aws-ec2-metadata-token: $imds_token" \
  http://169.254.169.254/latest/meta-data/instance-id) || CKING_CONSUMER_ID=""
[ -n "$CKING_CONSUMER_ID" ] || echo "경고: 인스턴스 ID를 읽지 못해 Stream 소비자 이름에 호스트 이름을 쓴다"
export CKING_CONSUMER_ID

echo "[3/4] 이미지 pull 및 기동: $IMAGE_TAG"
# app만 pull한다. redis는 digest로 고정돼 있어, 고정값이 바뀌면 up -d가 그 이미지를 받아 컨테이너를 다시 만든다.
docker compose pull app
docker compose up -d

echo "[4/4] 헬스체크 (최대 ${HEALTH_TIMEOUT}초)"
for ((elapsed = 0; elapsed < HEALTH_TIMEOUT; elapsed += 3)); do
  if curl -fsS "$HEALTH_URL" 2>/dev/null | jq -e '.status == "UP"' > /dev/null; then
    echo "배포 완료: $IMAGE_TAG"
    # 7일 넘은 미사용 이미지를 정리한다. 최근 배포본은 남으므로 그 범위의 롤백은 pull 없이 된다.
    docker image prune -af --filter "until=168h" > /dev/null
    exit 0
  fi
  sleep 3
done

echo "헬스체크 실패: ${HEALTH_TIMEOUT}초 안에 UP 응답 없음" >&2
docker compose ps
docker compose logs --tail=100 app
exit 1
