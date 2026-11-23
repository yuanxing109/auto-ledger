#!/usr/bin/env bash
# verify_line_refs.sh —— 校验 Markdown 报告里每个 "文件.kt:行号" 引用在源码中真实存在
# 用法: bash scripts/verify_line_refs.sh <报告.md> [源码根目录]
# 输出: 每条引用展开成源码真实行；失败项标 MISSING-FILE / OUT-OF-RANGE / BLANK-LINE
# 退出码: 0 = 全部通过；1 = 存在失败引用
set -uo pipefail

REPORT="${1:?用法: verify_line_refs.sh <报告.md> [源码根目录]}"
SRC_ROOT="${2:-.}"
SRC_ROOT="${SRC_ROOT%/}"

total=0
failed=0

extract_refs() {
  grep -oE '[A-Za-z0-9_./-]+\.(kt|java|kts):[0-9]+(-[0-9]+)?' "$REPORT" | sort -u
}

while IFS= read -r ref; do
  [ -z "$ref" ] && continue
  base="$(basename "${ref%%:*}")"
  rest="${ref#*:}"
  start="${rest%%-*}"
  end="${rest#*-}"
  [ "$end" = "$rest" ] && end="$start"

  target="$(find "$SRC_ROOT" -type f -name "$base" 2>/dev/null | head -1)"
  total=$((total+1))

  if [ -z "$target" ]; then
    echo "MISSING-FILE   $ref   找不到同名源文件 $base"
    failed=$((failed+1))
    continue
  fi

  total_lines="$(wc -l < "$target" | tr -d ' ')"
  if [ "$start" -gt "$total_lines" ] || [ "$end" -gt "$total_lines" ]; then
    echo "OUT-OF-RANGE   $ref   $target 只有 $total_lines 行"
    failed=$((failed+1))
    continue
  fi

  body="$(awk -v a="$start" -v b="$end" 'NR>=a && NR<=b {printf "%d: %s\n", NR, $0}' "$target")"
  if ! printf '%s\n' "$body" | grep -qE '^[0-9]+:[[:space:]]*[^[:space:]]'; then
    echo "BLANK-LINE    $ref   $target:$start 指向空行"
    failed=$((failed+1))
    continue
  fi

  echo "OK             $ref   -> $target"
  printf '%s\n' "$body" | sed 's/^/                 /'
done < <(extract_refs)

echo "----"
echo "共检查 $total 条引用，失败 $failed 条"
[ "$failed" -eq 0 ]
