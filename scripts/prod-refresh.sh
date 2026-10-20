#!/usr/bin/env bash
# 운영 앱 서버 교체를 시작하고 끝날 때까지 기다린다. 성공하면 0, 실패하거나 시간이 넘으면 1.
# 사용법: prod-refresh.sh <오토스케일링 그룹 이름>
# 새 서버를 먼저 띄우고 정상이 된 뒤 옛 서버를 뺀다(Infra 앱 서버 그룹 설정과 같은 값).
# 새 서버는 ALB에 붙기 전에 스스로 스모크 테스트를 통과해야 하며, 실패하면 버려지고 교체가 실패한다.
set -euo pipefail

ASG="${1:?사용법: prod-refresh.sh <오토스케일링 그룹 이름>}"

# 교체 시작 시각 이후에 뜬 서버는 이번 배포 태그로 뜬 서버다. 실패했을 때 되돌릴 서버를 찾는 기준이다(prod-recover.sh).
started=$(date -u +%Y-%m-%dT%H:%M:%SZ)
refresh_id=$(aws autoscaling start-instance-refresh --auto-scaling-group-name "$ASG" \
  --preferences '{"MinHealthyPercentage":100,"MaxHealthyPercentage":200}' \
  --query InstanceRefreshId --output text)
echo "교체 시작: $refresh_id ($started)"
if [ -n "${GITHUB_OUTPUT:-}" ]; then
  echo "id=$refresh_id" >> "$GITHUB_OUTPUT"
  echo "started=$started" >> "$GITHUB_OUTPUT"
fi

# 한 번에 모두 교체된다. 시작 대기 상한 900초 + 투입 대기 300초 = 20분에 여유를 두어 30분까지 기다린다.
for _ in $(seq 1 60); do
  status=$(aws autoscaling describe-instance-refreshes --auto-scaling-group-name "$ASG" \
    --instance-refresh-ids "$refresh_id" --query 'InstanceRefreshes[0].Status' --output text)
  case "$status" in
    Successful)
      echo "교체 완료"
      exit 0 ;;
    Pending | InProgress)
      sleep 30 ;;
    *)
      aws autoscaling describe-instance-refreshes --auto-scaling-group-name "$ASG" \
        --instance-refresh-ids "$refresh_id" --query 'InstanceRefreshes[0].[Status,StatusReason]' --output text
      exit 1 ;;
  esac
done
echo "교체 결과 확인 시간 초과 (30분)"
exit 1
