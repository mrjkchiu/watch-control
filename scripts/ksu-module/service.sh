#!/system/bin/sh
# KernelSU 在 late_start 服务阶段执行本脚本。
# 这里只负责异步拉起守护进程后立刻退出，绝不阻塞启动流程。
MODDIR=${0%/*}
nohup sh "$MODDIR/daemon.sh" >/dev/null 2>&1 &
exit 0
