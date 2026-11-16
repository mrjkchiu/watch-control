#!/system/bin/sh
# 由 service.sh 异步拉起，等系统启动完成后常驻守护 Node 中继。
# 崩溃自动重启；日志写到 /data/local/tmp/watch-control/relay.log。
MODDIR=${0%/*}

RELAY_DIR="${RELAY_DIR:-/data/local/tmp/watch-control/relay}"
NODE_BIN="${NODE_BIN:-/data/data/com.termux/files/usr/bin/node}"
LOG_DIR=/data/local/tmp/watch-control
LOG="$LOG_DIR/relay.log"

mkdir -p "$LOG_DIR" 2>/dev/null || exit 1

# 等 boot 完成、文件系统与 Termux 环境就绪
until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 2; done
sleep 3

if [ ! -x "$NODE_BIN" ]; then
  echo "[daemon] 找不到 Node: $NODE_BIN （Termux 里 pkg install nodejs，或设 NODE_BIN）" >> "$LOG"
  exit 1
fi
if [ ! -f "$RELAY_DIR/src/main.js" ]; then
  echo "[daemon] 找不到中继: $RELAY_DIR" >> "$LOG"
  exit 1
fi

echo "[daemon] starting relay $(date)" >> "$LOG"
cd "$RELAY_DIR" || exit 1
while true; do
  "$NODE_BIN" src/main.js >> "$LOG" 2>&1
  echo "[daemon] relay exited ($?), restart in 5s $(date)" >> "$LOG"
  sleep 5
done
