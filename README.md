# 腕控：REDMI Watch 6 实时控制 dsh-go 与 WorkBuddy

用手表经蓝牙向手机下发指令，控制手机上的 **dsh-go 引擎**与 **WorkBuddy**。手机端只有**一个 App**，装好打开一次即可。

> **当前版本（2026-11-16）**：`companion-0.1.21.apk` + `watch-0.1.26.rpk`，交付**恒 2 文件**（见 `/workspace/交付/`）。两端同包名 `com.example.watchctl`、同签名 `8637ca4e…d12c`（签名材料不入库，见 `.gitignore`）。源目录为 `companion/`（手机端）与 `watch-rpk/`（表端），以下说明以这两处源码为准。

## 一句话结论

手表没有 WiFi、Vela 快应用没有 `fetch`/WebSocket/BLE GATT，**唯一真双向实时的路**是：

```
Watch 6 (.rpk 快应用)  ── @system.interconnect（BLE JSON）──
手机端【唯一 1 个 APK：腕控伴侣 com.example.watchctl】（前台服务 :relay，小米官方 xms-wearable SDK）
   │  ping → 本地直回 pong（只验证 BLE 通道）
   │  真实指令 → ModuleBridge 线程池 → 本应用内本地直调（无广播、无跨进程）
   ▼
内置指令引擎 CommandProcessor / DshClient / WbIngress（同 APK、同进程）
   ├─ dsh.prompt → DshClient → dsh-go 127.0.0.1:3080 session/prompt（写入 dsh-go 会话）
   │      └─ dsh.list / dsh.new / dsh.cancel / status
   └─ wb.prompt  → WbIngress → [root su] am start SEND -n .ShareAddToChat
          └─ 文本预填进 WorkBuddy 输入框（需在手机点「发送」才执行，不代表已送达）
   结果 → ResultSink 回调 → deliverToWatch → BLE 原路回表（cmd/target/stage/delivered/via/text 如实）
```

两条指令通道**各自如实回执**，绝不冒充「已送达」：
- **发到 DSH**（`dsh.prompt`）：引擎返回 ok 才 `ok=true`、`delivered=true`。
- **发到 WorkBuddy**（`wb.prompt`）：root `am start` 被系统接受即 `ok=true`/`launched=true`，但 `delivered=false`、`stage=prefill`——文本只是预填进输入框，需在手机上点一次「发送」。

**dsh-go 引擎 ≠ WorkBuddy**：dsh-go 是 `127.0.0.1:3080` 的本地 Node 引擎，WorkBuddy 是 `com.tencent.workbuddy.app`（腾讯官方 App）。写引擎会话不会出现在 WorkBuddy，两者始终分开。

## 需要你做的一次性准备

1. 手机装**小米运动健康**并与手表蓝牙配对——互联 SDK 依赖它的服务。
2. 装 `companion-0.1.21.apk` 并**打开一次**（拉起前台服务，通知栏出现「腕控伴侣」）。
3. **KernelSU** 给本 App `com.example.watchctl` 授权 **root**（WorkBuddy 通道要 root shell `am start`，dsh 凭据自愈也要 root）。授权一次即可，后续升级不用重授。
4. 在 dsh-go App 里启动**本机引擎**（`127.0.0.1:3080` 可写），否则所有 `dsh.*` 命令失败。
5. 手表用米坛工具（表盘自定义工具 / AstroBox V2 / BandBurg）或「设置 → 我的设备 → 连接新手机」蓝牙安装 `watch-0.1.26.rpk`；装完连一次小米运动健康。**先卸载手表上旧「腕控」再装**（Vela 的 `force-stop` 不清状态），装完等 9~12 秒再操作。

> **不需要** LSPosed、不需要勾作用域、不需要第二个模块。ZygiskNext + LSPosed 与本方案无关——Xposed 只能 hook Java/Kotlin，改不动 dsh-go 的 Flutter/Dart 层与 PRoot 里的 Node 引擎。

## 怎么用

打开手表端「腕控」→ 首屏点「连接手机」→ 状态变绿「● 已连接」（收到 `pong` 或 `ready`）。此后表端常驻维护链路：在线 9s 心跳、30s 无回执显示「● 重连中」并自动切回 2.5s 探测，恢复后立即补发排队中的指令。

- 轻点「当前指令」文字切换预设，点「发到 DSH」或「发到 WorkBuddy」发送。
- 切「自定义指令」用表端内置面板拼字（草稿上限 200 字）；**输入 ≠ 发送**，草稿为空不发送。
- 「更多工具」（默认收起）：查看连接诊断 / 断开连接·可再连 / 查看引擎状态（`status`）/ 列出会话（`dsh.list`）/ ! 中断当前会话（`dsh.cancel`）。

手机端 companion 是一个**状态面板**：顶部「当前链路」大状态 + 5 行链路读数（前台服务 / 手表节点 / 权限·监听 / 指令引擎 / 收发帧）+ 运行日志。「已连接」只在真收到表端帧回执时给出，绝不编造。连接出问题打开它，看卡在哪一步、点「复制日志」发我排障。

## DSH 凭据与自愈

dsh-go 的长期凭据是 **30 天有效的 cookie**（绑定 `127.0.0.1:3080`）；launch token 每次引擎进程启动都会变。companion 优先用持久化的 cookie 探活（200 即用，覆盖绝大多数 dsh-go 更新场景），401 才自愈：

1. 先只读 dsh-go WebView 的 cookie jar（零中断）；
2. 读不到再用 App **内置** `assets/dsh_capture.sh` 以 root 自起一个引擎抓 token 换 cookie。

全程**零外部依赖**——不需要 Termux、不需要 Python，脚本随 APK 内置在 App 私有目录。抓到 cookie 立即落盘，30 天窗口内不用再管。

手机端「DSH 凭据设置」折叠面板（默认收起）：Token/Cookie 手填 + 保存 / 清除，仅供自动抓不到时兜底与测试自愈。状态只显示「是否已配置」，**不显示明文**；清除 = 写空值，下次发指令触发自愈重取。日志有「人话 / 原文」切换，默认人话；「复制日志」始终复制完整技术原文供排障。

## 命令集

以源码为准：表端 `watch-rpk/src/pages/index/index.ux`，手机端 `companion/app/src/main/java/com/watchctl/hook/CommandProcessor.java`。未知命令一律拒绝。

| `cmd` | 动作 |
| --- | --- |
| `status` | 引擎在线 / 会话数 / WorkBuddy 配置情况 |
| `dsh.list` | 最近会话标题 |
| `dsh.new` | `session/create` 新建会话 |
| `dsh.prompt` | → dsh-go `session/prompt`（自动选最近会话，没有就新建）；`target=dsh-go`，引擎 ok → `delivered=true` |
| `dsh.cancel` | `session/cancel` 中断当前回合 |
| `wb.prompt` | → root `am start -n .ShareAddToChat` 把文本预填进 WorkBuddy 输入框；`stage=prefill`、`launched=true` 但 `delivered=false`（需在手机点「发送」） |

> `dsh.open` 已删除（`dshgo://` scheme 与 WorkBuddy 包名错配，真机拉不起来）。`dsh.*` 前提是引擎 3080 可写；`wb.prompt` 前提是本 App 有 root 且装了 WorkBuddy（手机端「自检引擎」可自证）。

## 安全边界

- 全程只走公开接口：不改 dsh-go 安装目录、不替换官方提示词、不绕过审批与沙箱。手表只是一个输入/显示终端。
- dsh cookie 等价于登录凭证，只存本机、不上传；手机界面不显示明文，日志脱敏（`«N字»`），调试广播只放行只读命令、禁止写 `auth_token`。
- 指令幂等去重（按 `watchctl_id`，有副作用命令，64 条 / 120s），断线补发不会重复执行。
- 签名材料（`keys/`、`watch-rpk/sign/`）不入库，见 `.gitignore`；换设备需单独拷贝，否则无法做同签名打包。

## 工程结构

| 路径 | 内容 |
| --- | --- |
| `companion/` | 手机端 Android 单 APK 完整工程（AGP 8.5.2 / Gradle 8.10 / compileSdk 34 / minSdk 29，含官方 `xms-wearable-lib_1.4_release.aar`）。详见 [companion/README.md](./companion/README.md) |
| `watch-rpk/` | 表端 Vela 快应用完整源码 + `build.sh`。详见 [watch-rpk/README.md](./watch-rpk/README.md) |
| `deliver.sh` | 把两端最新 release 产物整理到 `/workspace/交付/`，只取 release、恒 2 文件 |
| `.impeccable.md` | 两端 UI 的设计上下文与原则（克制·精确·可信的反假绿工程仪表） |

技术原理（协议、Vela 避坑、HyperOS 适配、风险）见 [方案.md](./方案.md)。
