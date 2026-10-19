#!/usr/bin/env bash
# 사용법: scripts/check-migration-compat.sh <대상 브랜치 커밋> [검사할 커밋, 기본 HEAD]
# 검사할 커밋의 마이그레이션을 적용한 DB에서 대상 브랜치 코드가 기동하는지 확인한다.
# 롤링 배포 중에는 옛 버전 서버가 새 마이그레이션이 적용된 DB로 요청을 받는다. 옛 엔티티가 매핑한 컬럼을
# 지우거나 바꾸면 Hibernate validate(ddl-auto)가 기동을 실패시키므로, 그 상황을 미리 만들어 본다.
# 로컬 compose의 MySQL·Redis를 쓰며, 그 DB에 검사할 커밋의 마이그레이션이 적용된다.
set -euo pipefail

BASE="${1:?대상 브랜치 커밋을 지정한다}"
TARGET="${2:-HEAD}"
DIR=src/main/resources/db/migration
CONTEXT_TEST=kr.co.cking.CkingBackendApplicationTests

if git diff --quiet "$BASE" "$TARGET" -- "$DIR"; then
  echo "바뀐 마이그레이션이 없다."
  exit 0
fi

workdir=$(mktemp -d)
cleanup() {
  git worktree remove --force "$workdir/target" 2>/dev/null || true
  git worktree remove --force "$workdir/base" 2>/dev/null || true
  rm -rf "$workdir"
}
trap cleanup EXIT

git worktree add --quiet --detach "$workdir/target" "$TARGET"
git worktree add --quiet --detach "$workdir/base" "$BASE"

# 이 단계는 마이그레이션 적용만 한다. 검사할 커밋 자체의 엔티티 검증은 test 작업이 맡는다.
echo "== 검사할 커밋의 마이그레이션을 적용한다"
(cd "$workdir/target" && SPRING_JPA_HIBERNATE_DDL_AUTO=none ./gradlew test --tests "$CONTEXT_TEST" --quiet)

# 이미 떠 있는 옛 서버는 마이그레이션을 다시 실행하지 않으므로 Flyway를 끄고 기동한다.
echo "== 대상 브랜치 코드가 새 DB 구조에서 기동하는지 확인한다"
if ! (cd "$workdir/base" && SPRING_FLYWAY_ENABLED=false ./gradlew test --tests "$CONTEXT_TEST" --quiet); then
  grep -ho "Schema validation: [^&]*" "$workdir"/base/build/test-results/test/*.xml 2>/dev/null | sort -u || true
  echo "::error::대상 브랜치 코드가 이 마이그레이션을 적용한 DB에서 기동하지 못한다. 옛 버전이 쓰는 컬럼·테이블을 지우거나 바꾸면 배포를 두 번으로 나눈다: docs/operations/rolling-deploy.md"
  exit 1
fi
echo "대상 브랜치 코드가 새 DB 구조에서 기동한다."
