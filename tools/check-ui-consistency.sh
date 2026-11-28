#!/usr/bin/env bash
# UI 一致性门禁 —— 规则说明见 docs/ui-design-system.md 的“UI 一致性门禁”章节
#
# 用法：bash tools/check-ui-consistency.sh
# 退出码：0 = 通过（warn 不影响退出码），1 = 有 error 级违规。
#
# 阶段说明（P0 只把 R1–R3 设为 error）：
#   P0 已完成：R1 Oil 主题单源、R2 色值单一来源、R3 命名色清零
#   P1 目标（现为 warn）：R4 字号、R5 字重、R6 圆角/间距魔法数
#   P2 目标（现为 warn）：R7 Toast → Snackbar
set -uo pipefail

cd "$(dirname "$0")/.." || exit 2
SRC="app/src/main"

red=$'\033[31m'; yellow=$'\033[33m'; green=$'\033[32m'; bold=$'\033[1m'; reset=$'\033[0m'
fail=0

# check <规则名> <正则> <豁免正则> <error=1|warn=0>
check() {
  local name="$1" re="$2" excl="$3" hard="$4" out n
  out=$(grep -rnE "$re" --include='*.kt' "$SRC" 2>/dev/null | grep -vE "$excl" || true)
  if [ -z "$out" ]; then
    printf '%s✔%s %s\n' "$green" "$reset" "$name"
    return
  fi
  n=$(printf '%s\n' "$out" | wc -l | tr -d ' ')
  if [ "$hard" = "1" ]; then
    printf '%s✘%s %s —— %s 处\n' "$red" "$reset" "$name" "$n"
    printf '%s\n' "$out" | sed 's/^/     /'
    fail=1
  else
    printf '%s!%s %s —— %s 处（该阶段目标，暂不阻断）\n' "$yellow" "$reset" "$name" "$n"
  fi
}

printf '%sUI 一致性门禁%s（源码目录 %s）\n' "$bold" "$reset" "$SRC"

# R1：全站只允许 AppTheme 建立 Oil UI 主题（否则 CompositionLocal 会被局部覆盖）
check "R1 Oil 主题只在 ui/theme/AppTheme.kt 里创建" \
  '^[[:space:]]*(OilThemeProvider|MiuixTheme|MaterialTheme)\(' \
  'ui/theme/AppTheme.kt' 1

# R2：色值只能住在 LedgerPalette（CategoryStore 的分类调色板 P1 再迁）
check "R2 不写 Color(0x…) 字面量" \
  'Color\(0x' \
  'ui/theme/|data/CategoryStore.kt|ui/LedgerTheme.kt|com/kyant/backdrop/catalog/' 1

# R3：命名色（Color.White 等）会直接吃掉深色模式
check "R3 不写 Color.White/Black 等命名色" \
  'Color\.(White|Black|DarkGray|LightGray|Gray|Red|Green|Blue|Yellow|Cyan|Magenta)\b' \
  'ui/theme/|ui/AiScanIcon.kt|com/kyant/backdrop/catalog/' 1

# R4：字号必须来自 textStyles.xxx，不许就地写 sp
check "R4 字号只走 OilTypography" \
  'fontSize[[:space:]]*=[[:space:]]*[0-9]+(\.[0-9]+)?\.sp' \
  'ui/theme/' 0

# R5：字重同理
check "R5 字重只走 OilTypography" \
  'fontWeight[[:space:]]*=[[:space:]]*FontWeight\.' \
  'ui/theme/' 0

# R6：圆角/间距魔法数（Canvas 绘制除外，暂按行号人工复核）
# 只统计「非合法阶梯值」的 RoundedCornerShape 字面量；合法阶梯 = 4/8/12/16/24/32/50。
# 需 PCRE lookahead，故该条单独走 grep -P（见下方 r6 专用实现）。
r6_re='RoundedCornerShape\((?!4\.dp|8\.dp|12\.dp|16\.dp|24\.dp|32\.dp|50\.dp)[0-9]+\.dp'
r6_out=$(grep -rnP "$r6_re" --include='*.kt' "$SRC" 2>/dev/null | grep -vE 'ui/theme/' || true)
if [ -z "$r6_out" ]; then
  printf '%s✔%s %s\n' "$green" "$reset" "R6 圆角只用合法阶梯值（4/8/12/16/24/32/50）"
else
  n=$(printf '%s\n' "$r6_out" | wc -l | tr -d ' ')
  printf '%s!%s %s —— %s 处（该阶段目标，暂不阻断）\n' "$yellow" "$reset" "R6 圆角只用合法阶梯值（4/8/12/16/24/32/50）" "$n"
fi

# R7：Toast 迁移到 Snackbar
check "R7 不再新增 Toast.makeText" \
  'Toast\.makeText' \
  'zzz-never-matches' 0

if [ "$fail" = "1" ]; then
  printf '\n%s门禁未通过%s：修掉上面标 ✘ 的项，或按 docs/ui-design-system.md 的“UI 一致性门禁”章节加豁免。\n' "$red" "$reset"
  exit 1
fi
printf '\n%s门禁通过%s（warn 项按阶段推进）\n' "$green" "$reset"
exit 0
