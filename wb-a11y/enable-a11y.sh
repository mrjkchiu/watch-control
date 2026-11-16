#!/system/bin/sh
# enable-a11y.sh —— 安装后免手点授权开启无障碍服务（需 root / KernelSU）
#
# 用法（手机 root shell）：
#   su -c 'sh /data/local/tmp/enable-a11y.sh'
#
# 前提：先把 wb-a11y 的 debug APK 装好（见 README.md 的构建步骤）。
#   adb install app/build/outputs/apk/debug/app-debug.apk
#   或 su -c 'pm install /path/to/app-debug.apk'
#
# 本脚本做的事：
#   1. 把本服务追加进系统已启用的无障碍服务列表（不清掉原有服务）；
#   2. 打开总开关 accessibility_enabled=1。
# 之后无需再进系统设置手动勾选。要关就把它从列表移除或关总开关。

PKG="com.watchctl.wbautosend"
SVC="$PKG/.WbAutoSendService"
COLON=":"

# 读取当前已启用的无障碍服务列表
CURRENT=$(settings get secure enabled_accessibility_services 2>/dev/null)
echo "当前已启用: ${CURRENT:-（空）}"

# 已启用则跳过追加
case ",${CURRENT}," in
  *,${SVC},*) echo "→ 已在列表中，无需追加";;
  *)
    if [ -z "$CURRENT" ]; then
      NEW="$SVC"
    else
      NEW="${CURRENT}${COLON}${SVC}"
    fi
    settings put secure enabled_accessibility_services "$NEW"
    echo "→ 已追加: $NEW"
    ;;
esac

settings put secure accessibility_enabled 1
echo "→ accessibility_enabled=1"

sleep 1
echo
echo "验证（应能看到本服务，且 enabled_id 含它）："
settings get secure enabled_accessibility_services
echo
echo "若列表里出现本服务、且手机顶部没弹异常，授权即生效。"
echo "回到手表发一条指令验证：文本进输入框后应自动点发送。"
