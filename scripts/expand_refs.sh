#!/usr/bin/env bash
# expand_refs.sh —— 把报告指定行区间内的 "文件.kt:行号" 引用逐条用 awk 展开成源码真实行
# 用法: bash scripts/expand_refs.sh <报告.md> <起> <止> <源码根目录>
set -uo pipefail
REPORT="${1:?报告路径}"; A="${2:?起行}"; B="${3:?止行}"; SRC_ROOT="${4:-app/src/main}"
sed -n "${A},${B}p" "$REPORT" \
  | grep -oE '[A-Za-z0-9_./-]+\.(kt|java|kts):[0-9]+(-[0-9]+)?' \
  | sort -u > /tmp/dsh_refs.txt
while IFS= read -r ref; do
  [ -z "$ref" ] && continue
  base="${ref%%:*}"; rest="${ref#*:}"
  start="${rest%%-*}"; end="${rest#*-}"
  [ "$end" = "$rest" ] && end="$start"
  target="$(find "$SRC_ROOT" -type f -name "$(basename "$base")" 2>/dev/null | head -1)"
  if [ -z "$target" ]; then echo "MISSING-FILE  $ref"; continue; fi
  lines="$(wc -l < "$target" | tr -d ' ')"
  if [ "$start" -gt "$lines" ] || [ "$end" -gt "$lines" ]; then
    echo "OUT-OF-RANGE  $ref  (文件共 $lines 行)"; continue
  fi
  echo "===== $ref -> $target ====="
  awk -v a="$start" -v b="$end" 'NR>=a && NR<=b {printf "%d: %s\n", NR, $0}' "$target"
done < /tmp/dsh_refs.txt
