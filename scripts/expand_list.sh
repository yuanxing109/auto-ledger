#!/usr/bin/env bash
# expand_list.sh —— 把引用清单里的 "路径/文件.kt:行号" 逐条用 awk 展开成源码真实行
# 用法: bash scripts/expand_list.sh <refs.txt> <out.txt> [源码根目录]
set -uo pipefail
REFS="${1:?refs.txt}"; OUT="${2:?out.txt}"; SRC_ROOT="${3:-app/src}"
mkdir -p "\$(dirname "$OUT")"
: > "$OUT"
count=0
while IFS= read -r ref; do
  [ -z "$ref" ] && continue
  case "$ref" in \#*) continue ;; esac
  count=$((count+1))
  base="${ref%%:*}"; rest="${ref#*:}"
  start="${rest%%-*}"; end="${rest#*-}"
  if [ "$end" = "$rest" ]; then end="$start"; fi
  target="$(find "$SRC_ROOT" -type f -name "$(basename "$base")" 2>/dev/null | head -1)"
  if [ -z "$target" ]; then echo "MISSING-FILE  $ref" >> "$OUT"; continue; fi
  lines="$(wc -l < "$target" | tr -d ' ')"
  if [ "$start" -gt "$lines" ] || [ "$end" -gt "$lines" ]; then
    echo "OUT-OF-RANGE  $ref  (文件共 $lines 行)" >> "$OUT"; continue
  fi
  echo "===== $ref -> $target =====" >> "$OUT"
  awk -v a="$start" -v b="$end" 'NR>=a && NR<=b {printf "%d: %s\n", NR, $0}' "$target" >> "$OUT"
done < "$REFS"
echo "展开 $count 条引用 -> $OUT"
