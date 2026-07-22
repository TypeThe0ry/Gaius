#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"

MAX_GIT_BLOB_BYTES=$((100 * 1024 * 1024))
failed=0

if ! command -v git-lfs >/dev/null 2>&1 && ! git lfs version >/dev/null 2>&1; then
  echo "error: Git LFS is required; install it before cloning or committing a release" >&2
  exit 1
fi

required_lfs_paths=(
  port/web/dist/classes.js
  port/web/dist/Gaius.html
  port/web/dist/singleplayer-server.js
  port/web/smoke/platform-smoke-v4.js
)

for path in "${required_lfs_paths[@]}"; do
  filter=$(git check-attr filter -- "$path" | awk '{print $3}')
  if [[ "$filter" != "lfs" ]]; then
    echo "error: required release file is not covered by Git LFS: $path" >&2
    failed=1
  fi
done

while IFS= read -r -d '' path; do
  [[ -f "$path" ]] || continue
  if [[ "$(uname -s)" == "Darwin" ]]; then
    size=$(stat -f '%z' "$path")
  else
    size=$(stat -c '%s' "$path")
  fi
  (( size >= MAX_GIT_BLOB_BYTES )) || continue

  filter=$(git check-attr filter -- "$path" | awk '{print $3}')
  if [[ "$filter" != "lfs" ]]; then
    echo "error: tracked file is at least 100 MiB but is not covered by Git LFS: $path ($size bytes)" >&2
    failed=1
  fi
done < <(git ls-files -z)

oversized=$(git ls-tree -r -l HEAD \
  | awk -v limit="$MAX_GIT_BLOB_BYTES" '$4 != "-" && $4 >= limit { print }')
if [[ -n "$oversized" ]]; then
  echo "error: HEAD contains ordinary Git blobs at least 100 MiB:" >&2
  echo "$oversized" >&2
  failed=1
fi

if ! git lfs fsck --pointers HEAD; then
  failed=1
fi

if (( failed != 0 )); then
  exit 1
fi

echo "Git LFS policy check passed"
