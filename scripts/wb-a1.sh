#!/system/bin/sh
# wb-a1.sh — 单独验证 A1：ShareAddToChat + SEND + text/plain + EXTRA_TEXT（intent 主路首选）
#
# 用法（在手机 root shell 里）：
#   adb push wb-a1.sh /data/local/tmp/
#   su -c 'sh /data/local/tmp/wb-a1.sh'
#
# 也可以不 push，直接复制下面的内联命令到 su -c 里执行：
#   su -c 'am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "A1 手表入口测试" -n com.tencent.workbuddy.app/.ShareAddToChat'
#
# 依据：第二轮完整 dumpsys 实证 .ShareAddToChat 声明了 SEND + text/plain 过滤器，
#       且名字即"把内容加到会话"，是 intent 主路的第一候选。

WB_PKG="com.tencent.workbuddy.app"
TEXT="${TEST_TEXT:-A1 手表入口测试：请回复你收到了哪一条（编号即可）}"

echo "目标包: $WB_PKG"
pm path "$WB_PKG" >/dev/null 2>&1 || { echo "✗ 包不存在，退出"; exit 1; }

# 先把 WorkBuddy 拉回干净状态，排除残留 Activity 干扰判读
echo "→ am force-stop $WB_PKG（清状态）"
am force-stop "$WB_PKG"
sleep 1

echo
echo "================================================================"
echo "[A1] ShareAddToChat：SEND + text/plain + EXTRA_TEXT  -n 直达"
echo "================================================================"
am start -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT "$TEXT" \
  -n "$WB_PKG/.ShareAddToChat" 2>&1 | head -5

echo
echo "→ 现在盯住手机屏幕，观察 8 秒，记录："
echo "    1) 文本落在【会话输入框】还是【新会话】还是【资料库/文件预览】还是【系统选择器】？"
echo "    2) 是否需要你再点一次【发送】按钮？"
echo
echo "判读："
echo "  * 文本进了会话输入框（哪怕要手点发送）→ intent 主路成立，relay 的 sendComponent 就填这个组件。"
echo "  * 若弹了系统选择器 / 打开的是文件预览 → A1 不达标，回贴现象，我再给你 A2 / 无障碍方案。"
sleep 8
echo "done"
