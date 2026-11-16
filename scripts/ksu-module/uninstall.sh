#!/system/bin/sh
# 模块卸载/停用时由 KernelSU 执行：停掉守护进程与 Node 中继。
pkill -f 'watchctl_relay/daemon.sh' 2>/dev/null || true
pkill -f 'watch-control/relay/src/main.js' 2>/dev/null || true
exit 0
