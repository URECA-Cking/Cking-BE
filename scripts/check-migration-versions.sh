#!/usr/bin/env bash
# 사용법: scripts/check-migration-versions.sh <비교 기준 커밋> [검사할 커밋, 기본 HEAD]
# 기준 커밋 이후 새로 추가된 Flyway 마이그레이션 버전이 기준 커밋의 최대 버전보다 큰지 검사한다.
set -euo pipefail

BASE="${1:?비교 기준 커밋을 지정한다}"
TARGET="${2:-HEAD}"
DIR=src/main/resources/db/migration

version_of() {
  basename "$1" | sed -nE 's/^V([0-9]+)__.*/\1/p'
}

base_max=$(git ls-tree --name-only "$BASE" "$DIR/" | while read -r f; do version_of "$f"; done | sort -n | tail -1)
added=$(git diff --name-only --no-renames --diff-filter=A "$BASE"..."$TARGET" -- "$DIR")

if [ -z "$added" ]; then
  echo "새로 추가된 마이그레이션이 없다."
  exit 0
fi

failed=0
for file in $added; do
  version=$(version_of "$file")
  if [ -z "$version" ]; then
    echo "::error file=$file::버전 형식(V숫자__설명.sql)이 아니다."
    failed=1
  elif [ "$version" -le "${base_max:-0}" ]; then
    echo "::error file=$file::V$version 은 기준 브랜치의 최대 버전 V$base_max 보다 커야 한다. V$((base_max + 1)) 이상으로 바꾼다."
    failed=1
  else
    echo "확인: $file (V$version > V$base_max)"
  fi
done

exit "$failed"
