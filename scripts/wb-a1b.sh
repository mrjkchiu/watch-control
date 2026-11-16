#!/system/bin/sh
# wb-a1b.sh —— 验证「intent + 无障碍自动点发送」全无人值守闭环（A1 的升级版）
#
# 前置（一次性）：
#   - 已在 /workspace/watch-control/wb-a11y 构建并安装 APK（见 wb-a11y/README.md）
#   - 已跑 enable-a11y.sh 开启无障碍（或手动在系统设置开启）
#
# 本脚本复刻 relay 的 autoSend(a11y-service) 流程：
#   1) am broadcast -a com.watchctl.a11y.ARM --es id <id>   武装服务（6 秒窗口）
#   2) am start ... ShareAddToChat 预填文本
#   3) 服务在窗口出现「发送」时自动点一次
# 跑完只需盯屏幕确认：文本进输入框后【应自动发送，无需手点】。
#
# 若尚未装 wb-a11y APK，本脚本会用 uiautomator 兜底（见下方 AUTO 分支）。

WB_PKG="com.tencent.workbuddy.app"
ARM_ACTION="com.watchctl.a11y.ARM"
ID="wb-a1b-$(date +%s)"
TEXT="${TEST_TEXT:-A1B 全无人值守验证：请回复你收到的编号}"
DUMP="/sdcard/wb_a11y_dump.xml"

echo "目标包: $WB_PKG"

# 检测 wb-a11y 服务是否已启用
A11Y_ON=$(settings get secure enabled_accessibility_services 2>/dev/null | grep -c "com.watchctl.wbautosend")
if [ "$A11Y_ON" -ge 1 ]; then
  echo "→ 检测到 wb-a11y 服务已启用，走 a11y-service 路径"
  AUTO="a11y"
else
  if command -v uiautomator >/dev/null 2>&1; then
    echo "→ 未启用 wb-a11y，改用 uiautomator 兜底（需设备上 uiautomator 二进制）"
    AUTO="ui"
  else
    echo "✗ 既没启用 wb-a11y，也没有 uiautomator，无法自动点发送。"
    echo "  请先构建并启用 wb-a11y（见 wb-a11y/README.md），或在 config 里保持 autoSend.enabled=false 仅半无人值守。"
    exit 1
  fi
fi

# 清状态
echo "→ am force-stop $WB_PKG（清状态）"
am force-stop "$WB_PKG"
sleep 1

if [ "$AUTO" = "a11y" ]; then
  echo "→ 武装: am broadcast -a $ARM_ACTION --es id $ID"
  am broadcast -a "$ARM_ACTION" --es id "$ID" >/dev/null 2>&1
fi

echo "================================================================"
echo "[A1B] ShareAddToChat（intent 预填）+ 自动点发送"
echo "================================================================"
am start -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT "$TEXT" \
  -n "$WB_PKG/.ShareAddToChat" 2>&1 | head -5

if [ "$AUTO" = "ui" ]; then
  echo "→ uiautomator 兜底：轮询 dump 找「发送」并 input tap"
  for i in 1 2 3 4; do
    sleep 1
    uiautomator dump "$DUMP" >/dev/null 2>&1
    XML=$(cat "$DUMP" 2>/dev/null)
    NODE=$(echo "$XML" | grep -o '<node[^>]*发送[^>]*bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"[^>]*>')
    if [ -n "$NODE" ]; then
      B=$(echo "$NODE" | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"')
      X1=$(echo "$B" | grep -o '\[[0-9]*' | head -1 | tr -d '[')
      Y1=$(echo "$B" | grep -o ',[0-9]*\]\[' | head -1 | tr -d ',[]')
      X2=$(echo "$B" | grep -o '\[[0-9]*,' | tail -1 | tr -d '[,')
      Y2=$(echo "$B" | grep -o '[0-9]*\]$' | tr -d ']')
      CX=$(( (X1 + X2) / 2 )); CY=$(( (Y1 + Y2) / 2 ))
      echo "→ input tap $CX $CY"
      input tap "$CX" "$CY"
      break
    fi
  done
fi

echo
echo "→ 观察 8 秒：文本进输入框后是否【自动发送】？"
sleep 8
echo "done"
