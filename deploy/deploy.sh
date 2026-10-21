#!/usr/bin/env bash
# 앱 서버 배포 스크립트. 개발은 SSM Run Command가, 운영은 서버 첫 부팅 스크립트가 root로 실행한다.
# 사용법: deploy.sh <이미지 태그>
#         PARAM_PATH=/cking/loadtest/ deploy.sh <이미지 태그>  (읽을 Parameter Store 경로, 기본 /cking/dev/)
#         PARAM_PATH=/cking/prod/ deploy.sh <이미지 태그>      (운영: 운영 배포 버킷과 운영 값 목록을 쓴다)
#
# 수동 롤백: 이전 태그를 인자로 주면 그 버전으로 되돌아간다.
set -euo pipefail

TAG="${1:?사용법: deploy.sh <이미지 태그>}"
REGION="ap-northeast-2"
PARAM_PATH="${PARAM_PATH:-/cking/dev/}"
APP_DIR="/opt/cking"
HEALTH_URL="http://localhost:8080/readyz"
HEALTH_TIMEOUT=90   # 초. 수동 배포 때 기동에 30초 안팎 걸렸고, RDS 연결 지연을 감안해 3배로 둔다.

# 환경마다 배포 버킷과 Parameter Store 값 목록이 다르다. 필수 값은 하나라도 없으면 실패하고,
# 선택 값은 등록된 것만 넘긴다(기능을 켜는 값과 그 기능의 키).
case "$PARAM_PATH" in
  /cking/prod/)
    BUCKET="prod-cking-deploy-551372961758-ap-northeast-2-an"
    REQUIRED=(DB_HOST DB_USERNAME DB_PASSWORD REDIS_HOST REDIS_USERNAME REDIS_PASSWORD
              JWT_SECRET OAUTH_GOOGLE_CLIENT_ID OAUTH_GOOGLE_CLIENT_SECRET
              OAUTH_KAKAO_CLIENT_ID OAUTH_KAKAO_CLIENT_SECRET
              ADMIN_ACCOUNT_LOGIN_ID ADMIN_ACCOUNT_PASSWORD)
    OPTIONAL=(CKING_CONTENT_TAG_TOKEN_HASH CKING_RECOMMENDATION_API_KEY_HASHES
              SUBSCRIPTION_VERIFICATION_SUBMISSION_ENABLED SUBSCRIPTION_GEMINI_API_KEY
              QUIZ_GENERATION_ENABLED QUIZ_JUDGE_ENABLED GEMINI_API_KEY OPENAI_API_KEY
              COMMENT_FILTER_ENABLED COMMENT_FILTER_BASE_URL)
    ;;
  *)
    BUCKET="dev-cking-deploy-551372961758"
    REQUIRED=(DB_HOST DB_USERNAME DB_PASSWORD DOCS_USERNAME DOCS_PASSWORD
              JWT_SECRET OAUTH_GOOGLE_CLIENT_ID OAUTH_GOOGLE_CLIENT_SECRET
              OAUTH_KAKAO_CLIENT_ID OAUTH_KAKAO_CLIENT_SECRET
              SUBSCRIPTION_GEMINI_API_KEY ADMIN_ACCOUNT_LOGIN_ID ADMIN_ACCOUNT_PASSWORD
              GEMINI_API_KEY OPENAI_API_KEY)
    OPTIONAL=()
    # 필터 Lambda와 추천 배치 서버는 개발에만 있다. 같은 이미지·compose·dev 프로필을 쓰는 부하 테스트는
    # 필터와 지급 완료 알림을 끄고, 추천 배치 인증 해시를 넘기지 않아 키 인증이 꺼진다.
    if [ "$PARAM_PATH" = /cking/dev/ ]; then
      REQUIRED+=(COMMENT_FILTER_BASE_URL CKING_RECOMMENDATION_API_KEY_HASHES CKING_CONTENT_TAG_TOKEN_HASH)
    else
      export COMMENT_FILTER_ENABLED=false
      export TICKET_REWARD_NOTIFICATION_ENABLED=false
    fi
    ;;
esac

cd "$APP_DIR"

echo "[1/4] 배포 파일 내려받기: s3://$BUCKET/releases/$TAG/"
aws s3 cp "s3://$BUCKET/releases/$TAG/docker-compose.yml" ./docker-compose.yml --region "$REGION"

echo "[2/4] Parameter Store 읽기: $PARAM_PATH"
# 값은 환경변수로만 넘기고 파일·로그에 남기지 않는다.
for name in "${REQUIRED[@]}"; do
  value=$(aws ssm get-parameter --region "$REGION" --name "${PARAM_PATH}${name}" \
    --with-decryption --query 'Parameter.Value' --output text)
  export "$name=$value"
done
# get-parameters는 없는 이름을 오류 없이 InvalidParameters로 돌려준다. 한 번에 10개까지 받는다.
for ((i = 0; i < ${#OPTIONAL[@]}; i += 10)); do
  names=()
  for name in "${OPTIONAL[@]:i:10}"; do names+=("${PARAM_PATH}${name}"); done
  # 권한 오류 같은 실패는 여기서 멈추도록 출력을 먼저 받는다.
  found=$(aws ssm get-parameters --region "$REGION" --names "${names[@]}" --with-decryption \
    --query 'Parameters[].[Name,Value]' --output text)
  while IFS=$'\t' read -r full value; do
    [[ "$full" == "$PARAM_PATH"* ]] || continue
    export "${full#"$PARAM_PATH"}=$value"
  done <<< "$found"
done

export IMAGE_TAG="$TAG"

# Stream 소비자 이름을 서버마다 다르게 한다. 인스턴스 ID는 서버를 새로 만들 때만 바뀐다.
# 메타데이터를 못 읽으면 앱이 컨테이너 호스트 이름을 쓴다(배포마다 바뀌므로 경고만 남긴다).
imds_token=$(curl -fsS --connect-timeout 2 --max-time 5 -X PUT http://169.254.169.254/latest/api/token \
  -H "X-aws-ec2-metadata-token-ttl-seconds: 60") || imds_token=""
CKING_CONSUMER_ID=$(curl -fsS --connect-timeout 2 --max-time 5 -H "X-aws-ec2-metadata-token: $imds_token" \
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
