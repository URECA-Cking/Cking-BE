#!/usr/bin/env bash
# 운영 앱 서버 교체가 실패했을 때 이전 버전으로 되돌린다.
# 사용법: prod-recover.sh <오토스케일링 그룹 이름> <교체 ID> <교체 시작 시각(UTC)> <이전 태그>
# 교체를 취소해도 이미 바뀐 서버는 되돌아가지 않는다. 교체 시작 뒤에 뜬 서버가 남아 있으면
# 이전 태그로 다시 교체해 전체를 이전 버전으로 맞춘다. 그것도 실패하면 두 버전이 섞인 상태로 실패한다.
# AWS 조회는 출력을 먼저 변수에 받아 실패하면 바로 멈춘다. 조회 실패를 "바뀐 서버 없음"으로 잘못 보지 않기 위해서다.
set -Eeuo pipefail
trap 'echo "복구 중 AWS 명령이 실패했다. 두 버전이 섞였을 수 있어 사람이 확인해야 한다" >&2' ERR

ASG="${1:?사용법: prod-recover.sh <그룹 이름> <교체 ID> <교체 시작 시각> <이전 태그>}"
REFRESH_ID="${2:-}"
STARTED="${3:-}"
PREVIOUS="${4:?이전 태그가 없다}"

refresh_status() {
  aws autoscaling describe-instance-refreshes --auto-scaling-group-name "$ASG" \
    --instance-refresh-ids "$REFRESH_ID" --query 'InstanceRefreshes[0].Status' --output text
}

# 시간 초과로 끝난 경우 교체가 아직 진행 중이다. 취소하고 끝날 때까지 기다린다.
# 취소는 시작 대기 중인 서버가 정리되어야 끝나므로 시작 대기 상한(900초)보다 길게 15분까지 기다린다.
if [ -n "$REFRESH_ID" ]; then
  status=$(refresh_status)
  case "$status" in
    Pending | InProgress) aws autoscaling cancel-instance-refresh --auto-scaling-group-name "$ASG" > /dev/null ;;
  esac
  for _ in $(seq 1 90); do
    status=$(refresh_status)
    case "$status" in
      Pending | InProgress | Cancelling) sleep 10 ;;
      *) break ;;
    esac
  done
  case "$status" in
    Pending | InProgress | Cancelling)
      echo "교체 취소가 15분 안에 끝나지 않았다($status). 사람이 확인해야 한다" >&2
      exit 1 ;;
  esac
  echo "교체 상태: $status"
fi

aws ssm put-parameter --name /cking/prod/RELEASE_TAG --type String --value "$PREVIOUS" --overwrite
echo "배포 태그를 $PREVIOUS로 되돌렸다"

if [ -z "$STARTED" ]; then
  echo "교체가 시작되지 않아 서버는 그대로다"
  exit 0
fi

started_epoch=$(date -u -d "$STARTED" +%s)
in_service_out=$(aws autoscaling describe-auto-scaling-groups --auto-scaling-group-names "$ASG" \
  --query "AutoScalingGroups[0].Instances[?LifecycleState=='InService'].InstanceId" --output text)
read -r -a in_service <<< "$in_service_out"
newer=()
if [ "${#in_service[@]}" -gt 0 ]; then
  launched_out=$(aws ec2 describe-instances --instance-ids "${in_service[@]}" \
    --query 'Reservations[].Instances[].[InstanceId,LaunchTime]' --output text)
  while read -r id launched; do
    [ -n "$id" ] || continue
    if [ "$(date -u -d "$launched" +%s)" -ge "$started_epoch" ]; then
      newer+=("$id")
    fi
  done <<< "$launched_out"
fi

if [ "${#newer[@]}" -eq 0 ]; then
  echo "새 버전으로 바뀐 서버가 없다. 모든 서버가 $PREVIOUS이다"
  exit 0
fi

echo "새 버전 서버 ${#newer[@]}대가 남아 있다(${newer[*]}). $PREVIOUS로 다시 교체해 전체를 맞춘다"
if ! "$(dirname "$0")/prod-refresh.sh" "$ASG"; then
  echo "이전 버전으로 다시 교체하지 못했다. 두 버전이 섞여 있을 수 있어 사람이 확인해야 한다" >&2
  exit 1
fi
