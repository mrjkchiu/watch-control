#!/system/bin/sh
# 在 KernelSU 的 root shell 里跑：
#   su -c 'sh /sdcard/probe.sh'
#
# 已按 K90 Max 第一轮真机结果预置（可用同名环境变量覆盖）：
#   WB_PKG=com.tencent.workbuddy.app   WorkBuddy 官方包（实测已装、Flutter）
#   DSH_PKG=io.github.ccch1mneyyy.dsh_app   dsh-go 包名（实测）
#   DSHGO_PKG=io.github.ccch1mneyyy.dsh_app dsh.open 深链 -p 用
#
# 目的：
#   1) 判定 dsh-go 本机引擎是否真的在跑（第一轮结果：没跑，3080 未监听）
#   2) 找 dsh.baseUrl / dsh.cookie（或 dsh.token）
#   3) 判定 WorkBuddy 到底有没有「接收纯文本指令」的入口（第一轮：无 text/plain）
#   4) 导出 WorkBuddy 完整组件/intent-filter/deeplink 摘要，供定位真实入口
#   5) 确认 ZygiskNext / LSPosed 是否真注入（第一轮：lspd + zn-zygisk-companion 在跑）
#   6) 确认 interconnect 依赖的小米运动健康是否安装
#
# 全量 dumpsys 会写到 /sdcard/watchctl-*.txt，回贴结果时一并给出，便于离线分析。

WB_PKG="${WB_PKG:-com.tencent.workbuddy.app}"
DSH_PKG="${DSH_PKG:-io.github.ccch1mneyyy.dsh_app}"
DSHGO_PKG="${DSHGO_PKG:-io.github.ccch1mneyyy.dsh_app}"
DSH_PORT="${DSH_PORT:-3080}"
OUT_DIR="${OUT_DIR:-/sdcard}"

echo "==================== 0. 身份 ===================="
id
echo "KernelSU: $(ksud -V 2>/dev/null || echo 'ksud 不在 PATH（用 KernelSU Manager 里看）')"
echo "配置: WB_PKG=$WB_PKG DSH_PKG=$DSH_PKG DSHGO_PKG=$DSHGO_PKG DSH_PORT=$DSH_PORT"

echo
echo "==================== 1. 引擎进程（排除内核线程误匹配）===================="
# 第一轮把 kworker/R-tdshp_hist_read 当成 dsh 进程了：那是内核线程，名字里恰好带 dsh。
( ps -A 2>/dev/null || ps ) | grep -iE 'proot|node|dsh|termux' \
  | grep -v grep | grep -viE 'kworker|rescuer_thread|\[|irq/' | head -25
echo "(若只看到内核线程或无输出 → 引擎未启动；dsh web 在 PRoot 内跑 node，应有 proot/node 用户态进程)"

echo
echo "==================== 2. loopback 监听 ===================="
if command -v ss >/dev/null 2>&1; then
  ss -ltnp 2>/dev/null | grep -E '127\.0\.0\.1|LISTEN' | head -25
else
  echo '(ss 不可用，/proc/net/tcp 十六进制端口)'
  head -20 /proc/net/tcp
fi

echo
echo "==================== 3. 相关包名 ===================="
pm list packages 2>/dev/null | grep -iE 'dsh|workbuddy|codebuddy|termux|kernelsu|xiaomi|miui.health|huami|zepp|wear' | sort
echo "--- interconnect 依赖：小米运动健康 / 穿戴（缺了表端 BLE 通道会断）---"
pm list packages 2>/dev/null | grep -iE 'xiaomi.hm.health|xiaomi.wearable|miui.health|huami|zepp|xms' || \
  echo "  ⚠ 未匹配到常见的小米运动健康包名，请用 'pm list packages | grep -iE xiaomi|mi|health' 人工确认"

echo
echo "==================== 4. dsh web 启动 URL / token ===================="
# 正则收窄：第一轮 'token=' 把 IWindowContainerToken / launchCookies 全抓进来了。
( logcat -d -t 6000 2>/dev/null || true ) \
  | grep -iE 'dsh[ _-]?web|dsh-go|/dsh-go/v1|127\.0\.0\.1:[0-9][0-9]*|launch[_-]?token|dsh-auth-' \
  | grep -viE 'IWindowContainerToken|WindowContainerToken|RecentsModel|TransitionCallback|TaskInfo\{' \
  | tail -20
echo "(若为空：先在 dsh-go App 里启动【本机引擎】，再重跑本脚本；或看引擎页实时日志)"

echo
echo "==================== 5. 已持久化的登录 cookie ===================="
if [ -d "/data/data/$DSH_PKG" ]; then
  grep -R -l 'dsh-auth-' "/data/data/$DSH_PKG" 2>/dev/null | head -10
  echo "(cookie 形如 dsh-auth-<...>=v1.<payload>.<sig>；填到 config.json 的 dsh.cookie)"
else
  echo "  ✗ /data/data/$DSH_PKG 不存在或不可读，用 DSH_PKG= 覆盖为实际 dsh-go 包名"
fi
echo "(签名密钥在 PRoot 内 \$DSH_HOME/.credentials.yaml；cookie 绑定 host:port，默认 30 天)"

echo
echo "==================== 6. WorkBuddy 入口探测（本轮重点）===================="
echo "目标包名: $WB_PKG"
WB_APK="$(pm path "$WB_PKG" 2>/dev/null | sed -n 's/^package://p' | head -1)"
WB_DUMP="$OUT_DIR/watchctl-wb-dumpsys.txt"
WB_RESOLVE="$OUT_DIR/watchctl-wb-resolve.txt"

if [ -z "$WB_APK" ]; then
  echo "  ✗ 未找到该包。用 'pm list packages | grep -i workbuddy' 确认渠道包名，再用 WB_PKG= 覆盖"
else
  echo "  ✓ 已安装: $WB_APK"

  # --- 6a 对多种 MIME 逐个 resolve-activity，明确区分「真组件 / 系统选择器 / 无匹配」---
  echo "--- 6a resolve-activity：不同 MIME 下系统实际解析到谁 ---"
  echo "  注意：返回 ResolverActivity ≠ 无组件。包内有【多个】匹配组件时系统也返回选择器，"
  echo "        此时用 -n 指定 6b 列出的具体组件即可直达（sendComponent 就填它）。"
  : > "$WB_RESOLVE"
  for MIME in text/plain text/* application/json application/octet-stream '*/*'; do
    RAW="$(cmd package resolve-activity --brief -a android.intent.action.SEND -t "$MIME" -p "$WB_PKG" 2>/dev/null)"
    # resolve-activity 的组件行有前导空格（如 "    com.xxx/.Yyy"），必须先允许再 trim。
    COMP="$(printf '%s\n' "$RAW" | grep -E '^[[:space:]]*[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+' | tail -1 | sed 's/^[[:space:]]*//')"
    case "$COMP" in
      *intentresolver*|*ResolverActivity*)
        VERDICT="· 多个候选或无默认 → 看 6b 的组件清单，用 -n 指定" ;;
      '')
        VERDICT="✗ 真无匹配" ;;
      "$WB_PKG"/*)
        VERDICT="✓ 唯一/默认组件 → 可填 sendComponent" ;;
      *)
        VERDICT="? 解析到第三方组件 $COMP，需人工确认" ;;
    esac
    printf '  %-28s %-46s %s\n' "$MIME" "${COMP:-<空>}" "$VERDICT"
    printf '%s\t%s\t%s\n' "$MIME" "${COMP:-<空>}" "$VERDICT" >> "$WB_RESOLVE"
  done
  echo "  (结果已存 $WB_RESOLVE)"

  # --- 6b 完整 dumpsys：看 ACTION_SEND 的 MIME、组件名、deeplink scheme ---
  echo "--- 6b WorkBuddy 声明的 ACTION_SEND / EXPORTED 组件 / deeplink ---"
  dumpsys package "$WB_PKG" > "$WB_DUMP" 2>/dev/null || true
  echo "  完整 dumpsys → $WB_DUMP（回贴时一并给出）"
  echo "  ACTION_SEND 声明的 MIME 类型（去重）:"
  grep -iE '^\s*StaticType:|^\s*Type:' "$WB_DUMP" 2>/dev/null | sed 's/^/    /' | sort -u | head -40
  echo "  声明 SEND + text/plain 的组件（sendComponent 候选，用 -n 直达）:"
  # dumpsys 结构：组件行（hash pkg/.Class filter hash）后跟 Action/StaticType 行。
  # 逐行跟踪所属组件，命中 SEND 过滤器里的 text/plain 时记录该组件。
  CANDIDATES="$(awk '
    /^[[:space:]]*[0-9a-f]+ [A-Za-z0-9_.]+\/[A-Za-z0-9_.$]+ filter/ {
      comp=$2; in_send=0; next
    }
    /Action: "android.intent.action.SEND"/ { in_send=1; next }
    /Action: "/ { in_send=0 }
    /StaticType: "text\/plain"/ && in_send && comp != "" { print comp }
  ' "$WB_DUMP" 2>/dev/null | sort -u)"
  if [ -n "$CANDIDATES" ]; then
    printf '%s\n' "$CANDIDATES" | sed 's/^/    ✓ /'
    # 首选 ShareAddToChat（名字即"内容进会话"）；没有则取列表第一个。
    if printf '%s\n' "$CANDIDATES" | grep -q '/\.ShareAddToChat$'; then
      FIRST="$(printf '%s\n' "$CANDIDATES" | grep '/\.ShareAddToChat$' | head -1)"
    else
      FIRST="$(printf '%s\n' "$CANDIDATES" | head -1)"
    fi
    echo "  → intent 通道可用（第二轮真机已实证此路径存在）。sendComponent 建议填: $FIRST"
    echo "  → 一键实测（观察文本落在哪：新任务输入区 / 文件预览 / 无反应）:"
    echo "    am start -a android.intent.action.SEND -t text/plain \\"
    echo "      --es android.intent.extra.TEXT '手表测试指令' -n $FIRST"
    echo "    或跑 scripts/wb-ingress-test.sh 自动遍历全部候选"
  else
    echo "    ✗ 无任何组件声明 SEND+text/plain → 只收文件流(EXTRA_STREAM)，"
    echo "      发纯文本指令的 intent 通道不可用；需走无障碍服务或官方 IM 远程指令通道"
  fi
  echo "  全部 SEND 组件（含只收文件的，供参考）:"
  awk '/^[[:space:]]*[0-9a-f]+ [A-Za-z0-9_.]+\/[A-Za-z0-9_.$]+ filter/ { comp=$2 }
       /Action: "android.intent.action.SEND/ { if (comp!="") { print comp; comp="" } }' \
    "$WB_DUMP" 2>/dev/null | sort -u | sed 's/^/    /' | head -10 || true
  echo "  deeplink / scheme（若有，可能是更好的指令入口）:"
  grep -iE '^\s*Scheme:|android.intent.action.VIEW' "$WB_DUMP" 2>/dev/null \
    | sort -u | head -20 | sed 's/^/    /' || echo "    (未见)"
  echo "  exported Activity 数（粗略）:"
  grep -ciE '^\s*[0-9a-f]{8} .*Activity' "$WB_DUMP" 2>/dev/null | sed 's/^/    /'

  # --- 6c Flutter 判定 ---
  echo "--- 6c Flutter 判定 ---"
  if command -v unzip >/dev/null 2>&1; then
    FLUTTER_HIT="$(unzip -l "$WB_APK" 2>/dev/null | grep -iE 'libflutter\.so|libapp\.so|flutter_assets' | head -5)"
  else
    FLUTTER_HIT="$(grep -a -o -E 'libflutter\.so|libapp\.so|flutter_assets' "$WB_APK" 2>/dev/null | sort -u | head -5)"
  fi
  if [ -n "$FLUTTER_HIT" ]; then
    echo "  ✓ 实证 Flutter（第一轮已确认）："
    echo "$FLUTTER_HIT" | sed 's/^/    /'
    echo "  → Xposed 的 EditText View 树注入失效；Flutter 语义树要走【标准无障碍服务】"
    echo "    (AccessibilityNodeInfo + ACTION_SET_TEXT)，不是 LSPosed 的 byViewTree。"
  else
    echo "  · 未检出 Flutter 特征（或 unzip/grep 不可用，需人工确认）。"
  fi

  # --- 6d 无障碍服务现状：Flutter 下最可能走通的注入路径 ---
  echo "--- 6d 无障碍服务现状（Flutter 下的推荐入口）---"
  echo "  enabled_accessibility_services: $(settings get secure enabled_accessibility_services 2>/dev/null || echo '读不到')"
  echo "  accessibility_enabled: $(settings get secure accessibility_enabled 2>/dev/null || echo '读不到')"
  echo "  → root 下可用 'settings put secure enabled_accessibility_services <pkg>/<Service>' 免手点启用；"
  echo "    自写无障碍服务读 Flutter 语义树填输入框 + 点发送，比 LSPosed hook 更靠谱。"
fi

echo
echo "==================== 7. dsh-go 深链可用性 ===================="
if [ -n "$DSHGO_PKG" ]; then
  dumpsys package "$DSHGO_PKG" 2>/dev/null | grep -A 8 -iE 'dshgo|Scheme:' | head -30 || \
    echo "  (未见 dshgo:// scheme 声明；dsh.open 是否可用需真机点按验证)"
else
  echo "未指定 DSHGO_PKG（可选，用于 dsh.open 时 -p 指定包）"
fi

echo
echo "==================== 8. 引擎 HTTP 可达性 ===================="
PORT_HEX="$(printf '%04X' "$DSH_PORT" 2>/dev/null || echo 0C08)"
PORT_LISTEN="no"
if command -v ss >/dev/null 2>&1; then
  ss -ltn 2>/dev/null | grep -q ":$DSH_PORT " && PORT_LISTEN="yes"
else
  awk -v p=":$PORT_HEX" 'FNR>1 && $4=="0A" && index($2,p)>0 {f=1} END{exit !f}' \
    /proc/net/tcp /proc/net/tcp6 2>/dev/null && PORT_LISTEN="yes"
fi
if command -v curl >/dev/null 2>&1; then
  curl -s -m 2 -o /dev/null -w "  GET / → HTTP %{http_code}\n" "http://127.0.0.1:$DSH_PORT/" || echo "  (curl 失败)"
else
  echo "  (root shell 无 curl；端口判定改走 ss / /proc/net/tcp，端口 $DSH_PORT = 0x$PORT_HEX)"
fi
if [ "$PORT_LISTEN" = "yes" ]; then
  echo "  ✓ $DSH_PORT 正在监听 → 引擎已启动，relay 的 dsh.* 命令可通（返回 401/403 也算通）"
else
  echo "  ✗ $DSH_PORT 未监听 → 【引擎未启动】，这是当前第一阻塞项。"
  echo "    先在 dsh-go App 里启动本机引擎，再重跑本脚本；否则 dsh.list/dsh.prompt 全部失败。"
fi

echo
echo "==================== 9. ZygiskNext / LSPosed 状态 ===================="
echo "--- KSU 模块列表（看 enabled，不要被 mount=false 误导：Zygisk 模块不挂 system）---"
ksud module list 2>/dev/null | grep -iE '"id"|"name"|"enabled"|"version"|zygote' | head -40 \
  || echo '(ksud 不在 PATH，去 KernelSU Manager 看模块页)'
echo "--- LSPosed 管理器 ---"
pm list packages 2>/dev/null | grep -iE 'lsposed|xposed|zygisk' | sort || echo '(未找到)'
echo "--- 真注入实证进程（比模块列表更硬）---"
( ps -A 2>/dev/null || ps ) | grep -iE 'lspd|zn-zygisk|zn-nsdaemon|zygisk' | grep -v grep | head -10
echo "--- 判读 ---"
echo "  * 出现 lspd / zn-zygisk-companion64 zygisk_lsposed → ZygiskNext + LSPosed 已真注入 zygote。"
echo "  * mount=false 对 Zygisk 类模块属正常（靠 zygote 注入，不改 system 分区）。"
echo "  * Xposed 只 hook Java/Kotlin：动不了 dsh-go 的 Flutter/Dart 层与 PRoot 内 Node 引擎；"
echo "    对 WorkBuddy 会话 UI（Flutter）同样无效，只有广播接收与通知回读这两半可用。"
echo "  * AOSP 层 root UID 的 am start 已豁免 BAL；HyperOS 3 OEM 层仍按方案 §4.6 手动放行。"

echo
echo "==================== 10. 本轮结论汇总 ===================="
echo "  引擎是否启动:        $([ "$PORT_LISTEN" = yes ] && echo '是' || echo '否 ← 先启动 dsh-go 本机引擎')"
echo "  WorkBuddy 包名:      $WB_PKG"
echo "  WorkBuddy 形态:      Flutter（第一轮实证）"
echo "  dsh-go 包名:         $DSH_PKG"
echo "  sendComponent:       见 6b 候选清单；优先 .ShareAddToChat（文本进会话），备选 .MainActivity"
echo "  intent 文本通道:     第二轮已实证存在（4 组件声明 SEND+text/plain）；行为以 wb-ingress-test.sh 实测为准"
echo "  L1.5 广播/回读:      可用（真注入已确认）；回读结果用，注入走 -n 直达即可"
echo "  全量 dumpsys:        $WB_DUMP"

echo
echo "==================== 填表 ===================="
cat <<EOF
把结果填进 relay/config.json（config.example.json 已预置包名与 sendComponent，直接 cp 即可）：
  dsh.baseUrl            http://127.0.0.1:$DSH_PORT（引擎启动后才通；以启动日志为准）
  dsh.cookie             第 5 段结果；没有就填 dsh.token 让中继自己登录
  workbuddy.packageName  $WB_PKG（实测）
  workbuddy.sendComponent  默认 $WB_PKG/.ShareAddToChat；实测行为不对再换 .MainActivity / .SharePreviewFile
  dshgo.packageName      $DSHGO_PKG（实测，用于 dsh.open 深链）
  relayToken             自己设一个随机串，companion 请求要带 X-Relay-Token

【重要 · 第二轮真机结论（推翻第一轮"text/plain 不可用"的误判）】
  * 完整 dumpsys 实证：.ShareAddToChat / .ShareSaveToLibrary / .MainActivity / .SharePreviewFile
    四个组件都声明了 SEND + text/plain（还有 text/html、text/markdown）。
  * 6a 的 resolve 返回 ResolverActivity 是因为【包内多个候选】，不是【没有】——
    用 -n 指定组件直达即可，sendComponent 就填它。
  * 组件语义（按名字判断，行为以实测为准）：
      .ShareAddToChat     内容 → 会话（首选，正是"发指令"入口）
      .ShareSaveToLibrary 内容 → 资料库
      .SharePreviewFile   内容 → 文件预览
      .MainActivity       内容 → 主界面分享流
  * 官方 OpenSDK（workbuddy-opensdk://share）存在，可把文本【预填到任务输入区】，
    但强制要求开放平台注册 appId/kid + SDK 接入，且不自动发送（需人点发送）；
    是长期最稳的合规路径，短期不如 -n 直达直接。
  * 实测脚本：scripts/wb-ingress-test.sh（遍历全部候选组件 + deeplink，一次定结论）。

【可选 · L1.5 加固层】ZygiskNext + LSPosed 已真注入：
  workbuddy.mode             "intent"（默认，零依赖）→ 改成 "broadcast"
  workbuddy.broadcast.action  与模块设置页一致，如 com.watchctl.SEND
  workbuddy.broadcast.token   ≥8 字符随机串（也是 /result 的 X-Hook-Token）
  lsposed 作用域           必须勾 $WB_PKG（arrays.xml 已预置）
EOF
