# KernelSU 开机自启模块

> 你的设备是 **HyperOS 3 + KernelSU**，所以保活走 KSU 模块（`service.sh`），**不是 Magisk 的 `/data/adb/service.d/`**。KSU 改内核、Magisk 改 ramdisk，两者不要混用。

## 目录

| 文件 | 作用 |
| --- | --- |
| `module.prop` | 模块元数据，KSU 与 Magisk 模块格式兼容 |
| `service.sh` | late_start 阶段执行；只异步拉起 `daemon.sh` 后立即退出，不阻塞开机 |
| `daemon.sh` | 等 `sys.boot_completed=1` → 启动 Node 中继 → 崩溃自动重启 |
| `uninstall.sh` | 停掉守护进程与中继 |

## 前置

1. Termux 里装 Node：`pkg install nodejs`（默认路径 `/data/data/com.termux/files/usr/bin/node`）。
2. 把本仓库拷到手机：`/data/local/tmp/watch-control/`。
3. 先用 `scripts/probe.sh` 填好 `relay/config.json`，并在 Termux 里用 `scripts/start-relay.sh` 手动跑通一次。
4. 若 Node 不在默认路径，编辑 `daemon.sh` 里的 `NODE_BIN`；若中继不在默认路径，改 `RELAY_DIR`。

## 安装

把本目录打成 zip（`module.prop` 必须在 zip 根目录）：

```sh
cd scripts/ksu-module
zip -r /sdcard/watchctl_relay.zip module.prop service.sh daemon.sh uninstall.sh
```

KernelSU Manager → 模块 → 从本地安装 → 选 `watchctl_relay.zip` → **重启**。

验证：

```sh
su -c 'ksud module list'                    # 应能看到 watchctl_relay
su -c 'ps -A | grep -E "daemon.sh|main.js"'
su -c 'curl -s http://127.0.0.1:8787/ping'  # {"ok":true,...}
su -c 'tail -30 /data/local/tmp/watch-control/relay.log'
```

## 为什么用 service.sh 而不是 boot-completed.sh

不同 KSU 版本对 `boot-completed.sh` 的支持不一致；`service.sh` 是 KSU 启动时序里稳定执行的脚本（post-fs-data → service.d → 模块 `service.sh`）。为避免脚本阻塞开机，`service.sh` 只负责后台拉起 `daemon.sh`，由 `daemon.sh` 自己轮询 `sys.boot_completed` 后再启动服务。

## HyperOS 3 配套放行

模块只能保证"进程被拉起"，**BLE 通道不断**还需要：

- 小米运动健康、companion、Termux：自启动允许 + 省电策略「无限制」+ 最近任务锁定
- companion / WorkBuddy / dsh-go：后台弹出界面、关联启动（链式启动）放行
- 小米运动健康：通知使用权（结果才能镜像到手表）
- KernelSU Manager：给 companion / Termux 的 App Profile 授权 root；对敏感 App 可开 umount modules 隐藏挂载

## 救援

模块写错导致 bootloop：开机动画前**连续短按音量下 3 次以上**进入 KSU 内置安全模式（所有模块禁用），再到 Manager 卸载本模块。`ksud module list` 可确认模块是否生效。

> 你已装 **ZygiskNext + LSPosed(Zygisk 版)**，所以 L1.5 加固层（`lsposed/`）的前置条件已满足，可直接在 WorkBuddy 作用域启用。本模块（relay 保活）与 LSPosed 模块是两回事：前者是 KSU 的 `service.sh` 脚本，后者是 Zygisk 注入的 Xposed 模块，各自独立、互不依赖。L1 主线用不到 LSPosed。
