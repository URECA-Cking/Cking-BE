#!/usr/bin/env bash
# 사용법: scripts/check-deploy-order.sh <지금 배포된 이미지 태그> <이번 커밋 SHA>
# 이번 커밋이 지금 배포된 커밋보다 오래됐으면 "skip", 아니면 "deploy"로 시작하는 한 줄을 출력한다(#694).
# GitHub Actions는 같은 동시 실행 그룹 안의 순서를 보장하지 않아, 옛 커밋의 실행이 새 커밋을 배포한 뒤에 돌 수 있다.
# 지금 배포된 태그를 모르면(서버가 응답하지 않음, 첫 배포) 배포한다. 망가진 서버를 고치는 배포는 나가야 한다.
# 태그는 알지만 비교에 실패하면 검사 없이 배포하지 않도록 실패로 끝낸다. 실행은 사라지지 않으므로 다시 돌리면 된다.
# GITHUB_REPOSITORY와 gh 인증(GH_TOKEN)이 필요하다.
set -euo pipefail

DEPLOYED_TAG="${1:-}"
SHA="${2:?이번 커밋 SHA를 지정한다}"

if [[ ! "$DEPLOYED_TAG" =~ ^[0-9]{14}-[0-9a-f]{7}$ ]]; then
  echo "deploy 지금 배포된 태그를 알 수 없다(${DEPLOYED_TAG:-없음})"
  exit 0
fi

DEPLOYED_SHA="${DEPLOYED_TAG##*-}"
if ! STATUS=$(gh api "repos/$GITHUB_REPOSITORY/compare/$DEPLOYED_SHA...$SHA" --jq .status); then
  echo "배포된 커밋 $DEPLOYED_SHA와 비교하지 못했다" >&2
  exit 1
fi

# compare A...B의 behind는 B가 A보다 뒤(A의 조상)라는 뜻이다.
if [ "$STATUS" = behind ]; then
  echo "skip 이번 커밋 ${SHA:0:7}은 지금 배포된 $DEPLOYED_SHA보다 오래됐다"
else
  echo "deploy 지금 배포된 $DEPLOYED_SHA와 비교 결과 $STATUS"
fi
