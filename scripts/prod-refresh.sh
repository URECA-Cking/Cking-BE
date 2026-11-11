#!/usr/bin/env bash
# 운영 앱 서버 교체를 시작하고 끝날 때까지 기다린다. 성공하면 0, 실패하거나 시간이 넘으면 1.
# 사용법: prod-refresh.sh <오토스케일링 그룹 이름>
# 새 서버를 먼저 띄우고 정상이 된 뒤 옛 서버를 뺀다(Infra 앱 서버 그룹 설정과 같은 값).
# 새 서버는 ALB에 붙기 전에 스스로 스모크 테스트를 통과해야 하며, 실패하면 버려진다. 그룹은 버려진 자리에 새 서버를 다시 띄우므로
# 교체 상태만 보면 상한까지 실패를 알 수 없다. 이번 교체에서 띄운 서버가 그룹 크기의 두 배만큼 버려지면(종료되면) 실패로 판정한다.
set -euo pipefail

ASG="${1:?사용법: prod-refresh.sh <오토스케일링 그룹 이름>}"

# 교체 시작 시각 이후에 뜬 서버는 이번 배포 태그로 뜬 서버다. 실패했을 때 되돌릴 서버를 찾는 기준이다(prod-recover.sh).
started=$(date -u +%Y-%m-%dT%H:%M:%SZ)
desired=$(aws autoscaling describe-auto-scaling-groups --auto-scaling-group-names "$ASG" \
  --query 'AutoScalingGroups[0].DesiredCapacity' --output text)
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
      abandoned=$(aws ec2 describe-instances \
        --filters "Name=tag:aws:autoscaling:groupName,Values=$ASG" "Name=instance-state-name,Values=shutting-down,terminated" \
        --output json | jq --arg since "$started" '[.Reservations[].Instances[] | select(.LaunchTime >= $since)] | length')
      if [ "$abandoned" -ge $((desired * 2)) ]; then
        echo "이번 교체에서 띄운 서버 ${abandoned}대가 시작 대기에서 버려졌다(그룹 ${desired}대의 두 배). 실패로 판정한다"
        exit 1
      fi
      sleep 30 ;;
    *)
      aws autoscaling describe-instance-refreshes --auto-scaling-group-name "$ASG" \
        --instance-refresh-ids "$refresh_id" --query 'InstanceRefreshes[0].[Status,StatusReason]' --output text
      exit 1 ;;
  esac
done
echo "교체 결과 확인 시간 초과 (30분)"
exit 1
