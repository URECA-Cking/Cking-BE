#!/usr/bin/env bash
# 개발 서버 배포 스크립트. SSM Run Command가 root로 실행한다.
# 사용법: deploy.sh <이미지 태그>
#
# 수동 롤백: 이전 태그를 인자로 주면 그 버전으로 되돌아간다.
set -euo pipefail

TAG="${1:?사용법: deploy.sh <이미지 태그>}"
REGION="ap-northeast-2"
BUCKET="dev-cking-deploy-551372961758"
PARAM_PATH="/cking/dev/"
APP_DIR="/opt/cking"
HEALTH_URL="http://localhost:8080/readyz"
HEALTH_TIMEOUT=90   # 초. 수동 배포 때 기동에 30초 안팎 걸렸고, RDS 연결 지연을 감안해 3배로 둔다.

cd "$APP_DIR"

echo "[1/4] 배포 파일 내려받기: s3://$BUCKET/releases/$TAG/"
aws s3 cp "s3://$BUCKET/releases/$TAG/docker-compose.yml" ./docker-compose.yml --region "$REGION"

echo "[2/4] Parameter Store 읽기: $PARAM_PATH"
# 필요한 값을 이름으로 지정해 하나라도 없으면 여기서 실패한다.
# 값은 환경변수로만 넘기고 파일·로그에 남기지 않는다.
for name in DB_HOST DB_USERNAME DB_PASSWORD DOCS_USERNAME DOCS_PASSWORD \
            JWT_SECRET OAUTH_GOOGLE_CLIENT_ID OAUTH_GOOGLE_CLIENT_SECRET \
            OAUTH_KAKAO_CLIENT_ID OAUTH_KAKAO_CLIENT_SECRET \
            SUBSCRIPTION_GEMINI_API_KEY ADMIN_ACCOUNT_LOGIN_ID ADMIN_ACCOUNT_PASSWORD; do
  value=$(aws ssm get-parameter --region "$REGION" --name "${PARAM_PATH}${name}" \
    --with-decryption --query 'Parameter.Value' --output text)
  export "$name=$value"
done

# 선택 값: 추천 적재 배치 API Key 해시. 파라미터가 아직 등록되지 않은 경우(ParameterNotFound)에만 빈 값으로 두고
# 키 인증만 비활성화한 채 배포를 계속한다. 권한 부족·KMS 복호화 실패·네트워크 오류 같은 다른 조회 실패는
# 키 인증이 조용히 꺼진 채 배포가 성공으로 남지 않도록 배포를 실패시킨다.
ssm_err=$(mktemp)
trap 'rm -f "$ssm_err"' EXIT
if recommendation_key_hashes=$(aws ssm get-parameter --region "$REGION" \
    --name "${PARAM_PATH}CKING_RECOMMENDATION_API_KEY_HASHES" \
    --with-decryption --query 'Parameter.Value' --output text 2>"$ssm_err"); then
  :
elif grep -q 'ParameterNotFound' "$ssm_err"; then
  recommendation_key_hashes=""
  echo "추천 적재 API Key 해시가 등록되지 않아 키 인증은 비활성화된 채 배포합니다."
else
  echo "추천 적재 API Key 해시 조회 실패(미등록이 아닌 오류):" >&2
  cat "$ssm_err" >&2
  exit 1
fi
export CKING_RECOMMENDATION_API_KEY_HASHES="$recommendation_key_hashes"

export IMAGE_TAG="$TAG"

echo "[3/4] 이미지 pull 및 기동: $IMAGE_TAG"
# app만 pull한다. redis는 떠다니는 태그라 전체 pull 시 패치 버전이 조용히 바뀔 수 있다.
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
