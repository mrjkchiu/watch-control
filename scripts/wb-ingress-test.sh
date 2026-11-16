#!/system/bin/sh
# wb-ingress-test.sh — WorkBuddy 文本入口 A/B 实测脚本（root 下跑）
#
# 用法：
#   adb push wb-ingress-test.sh /data/local/tmp/
#   su -c 'sh /data/local/tmp/wb-ingress-test.sh'
#
# 它会依次尝试 6 种"把文本送进 WorkBuddy"的方式，每种发出后停顿 8 秒，
# 你盯着手机屏幕记录：文本落在了哪里（会话输入区 / 新会话 / 资料库 / 文件预览 / 选择器 / 无反应）。
# 测完把观察结果回贴，我据此把 relay 的 sendComponent 固化。
#
# 依据：第二轮真机 dumpsys 实证 4 组件均声明 SEND+text/plain。

WB_PKG="${WB_PKG:-com.tencent.workbuddy.app}"
TEXT="${TEST_TEXT:-手表入口测试：请回复收到了哪一条（编号即可）}"
PAUSE="${PAUSE:-8}"

say() { echo; echo "================================================================"; echo "$@"; echo "================================================================"; }
fire() { # $1=编号 $2=说明；shift 2 后为 am 参数
  NO="$1"; DESC="$2"; shift 2
  say "[$NO] $DESC"
  am "$@" 2>&1 | head -5
  echo "→ 观察手机 $PAUSE 秒……（记下：落在哪 / 是否需再点发送）"
  sleep "$PAUSE"
}

echo "目标包: $WB_PKG"
pm path "$WB_PKG" >/dev/null 2>&1 || { echo "✗ 包不存在，退出"; exit 1; }
# 先把 WorkBuddy 拉回一个已知状态
am force-stop "$WB_PKG"
sleep 1

# ---------- A 组：SEND + text/plain + EXTRA_TEXT，逐组件 -n 直达 ----------
fire "A1" "ShareAddToChat（首选：名字即'内容进会话'）" \
  start -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT "【A1】$TEXT" \
  -n "$WB_PKG/.ShareAddToChat"

fire "A2" "MainActivity（主界面分享流）" \
  start -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT "【A2】$TEXT" \
  -n "$WB_PKG/.MainActivity"

fire "A3" "SharePreviewFile（文件预览，预期最差，作对照）" \
  start -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT "【A3】$TEXT" \
  -n "$WB_PKG/.SharePreviewFile"

# ---------- B 组：text/markdown（有些 App 对 md 走"任务/文档"流）----------
fire "B1" "ShareAddToChat + text/markdown" \
  start -a android.intent.action.SEND -t text/markdown \
  --es android.intent.extra.TEXT "【B1】$TEXT" \
  -n "$WB_PKG/.ShareAddToChat"

# ---------- C 组：deeplink（官方 OpenSDK / 自有 scheme，行为未知）----------
fire "C1" "workbuddy:// 主 scheme（无路径，看是否仅打开首页）" \
  start -a android.intent.action.VIEW -d "workbuddy://home" -p "$WB_PKG"

fire "C2" "workbuddy-opensdk://share 空参（预期被拒或报错，验证它是否检查注册）" \
  start -a android.intent.action.VIEW -d "workbuddy-opensdk://share" -p "$WB_PKG"

say "全部发完。请回报每条编号对应的实际表现，例如："
echo "  A1=进了会话输入区(需手点发送)  A2=弹了选择器  A3=打开文件预览"
echo "  B1=同A1  C1=只到首页  C2=无反应/报错页"
echo
echo "判读速查："
echo "  * A1/A2 任一能把文本落到会话输入区 → intent 通道成立，sendComponent 填该组件；"
echo "    再补一个无障碍服务点'发送'即可全无人值守。"
echo "  * 全部只进'资料库/预览' → 退回 OpenSDK 注册接入 或 无障碍填框方案。"
echo "  * C2 若提示未注册/验签失败 → 证实 OpenSDK 强制注册，与官方文档一致。"
