# 手机侧 companion APK（腕控伴侣）

手机端**唯一的一个 APK**：接收 REDMI Watch 6（Vela）经 BLE `@system.interconnect` 发来的 JSON 指令，**在本应用内直接执行**（dsh-go 引擎 / WorkBuddy 两条通道），并把结果回写给手表。0.1.8 起不再需要第二个「主中继」模块，也不依赖 LSPosed。

> 完整可编译 Android 工程（AGP 8.5.2 / Gradle 8.10 / compileSdk 34 / minSdk 29）。
> 已接入**小米官方 `xms-wearable-lib_1.4_release.aar`**（来自官方 `interconnect_dev_test_demo`），不再是反射占位。
> 旧版走 `POST 127.0.0.1:8787` 的 Node relay 已退役；0.1.8 起「companion → 广播 → 独立模块」链路也一并退役，改为**单 APK 本地直调**。
>
> **✅ 当前版本 companion 0.1.21 / code22（第二十三次修正：DSH 凭据手动入口 + 运行日志人话化，2026-11-16）**：交付**恒 2 文件** `watch-0.1.26.rpk` + `companion-0.1.21.apk`（同包名同签名 `8637ca4e…d12c`）。本轮只动显示层 + 一个写入入口，链路/帧协议/签名/引擎一行未改：① 新增「DSH 凭据设置」折叠面板（Token/Cookie 手填 + 保存 / 清除），清除=写空值触发自愈重取（用于测试）、自动抓不到时手填；② 复用 `COMPANION_SET` 通道在 `:relay` 进程写 SP、下条指令生效，`CompanionService` 增 `dsh_cookie` 分支 + 状态即时回报，只报「是否配置」不报明文；③ 运行日志加「人话 / 原文」切换，默认人话——隐藏快照/nodeId/原始帧噪声、技术行翻人话、出错才醒目，原始行仍供 parseState 与「复制日志」排障；④ `DshClient` 把自愈步骤投到界面日志（无明文）便于真机验证。**表端 rpk 0.1.26 未改、无需重装**；手机端需重装，root 授权对象仍是 `com.example.watchctl`、无需重授。效果预览 `/workspace/companion-ui-preview.html`。
>
> **✅ 上一版 companion 0.1.20 / code21（第二十二次修正：全系统代码审查健壮性/安全专项，2026-11-16）**：本轮修 5 项，均不动链路/帧协议/签名：① **P1** `State.java` 静态 `SimpleDateFormat` → `ThreadLocal`（并发 format 抛异常被吞→静默丢状态行）；② **P2** `DshSelfHeal.su()` 超时加 `killTree` 递归清理（避免 capture 起的引擎残留占 3080）；③ **P2** 内存扫描逐候选验证加 60s 总预算（最坏 1000s 阻塞指令线程）；④ **P2** `XmsInterconnectLink` 原始帧正文（含 prompt 文本）加 `redact()` 脱敏（正文替为 `«N字»`，防"复制日志"外泄，仅影响日志不动帧处理）。⑤ 配套表端 `bridge.js` 的 P1 竞态修复见 `watch-rpk/README.md`。两端都重编重装，root 授权对象仍是 `com.example.watchctl`、无需重授。
>
> 0.1.13~0.1.19 的 dsh-go cookie 持久化与内置内存扫描自愈演进见主 `README.md` 第二十一次修正及下方历史；本端自 0.1.14 起的核心能力（持久化 cookie + 零外部依赖的 App 内置内存扫描自愈）在 0.1.20 完整保留。
>
> **⌨️ 第十五次修正（2026-11-15）——本端零改动**：表端 rpk 升至 **0.1.20/code21**，把 8 键英文翻页键盘换成 MIT 开源中文拼音输入法（`NEORUAA/Vela_input_method`）。**手机端 `companion-0.1.12.apk` 内容与 0.1.12 完全相同、无需重装、无需重新授权 root、无录音权限**；下方第十四次安全专项描述仍为当前 APK 的真实内容。指令帧格式（`dsh.prompt`/`wb.prompt` 等）未变。
>
> **🛡️ 当前版本 companion 0.1.12 / code13（第十四次修正：dev-expert 复审后的安全 / 健壮性专项，2026-11-15）**；配套表端 rpk **0.1.20 / code21**（第十五次修正，本端不变）。交付**恒 2 文件**见 `/workspace/交付/`：`watch-0.1.20.rpk` + `companion-0.1.12.apk`，同包名同签名 `8637ca4e…d12c`。第十四次当时两端都重编重装；第十五次**只需重装表端**（root 授权对象不变、无需重授，无录音权限）。
> - **安全（第一优先）**：① 调试广播后门收紧——`CompanionService` 的 `CommandReceiver` 对 `TEST_FRAME` 只放行只读命令（`status`/`dsh.list`/`module.ping`/`ping`），`COMPANION_SET` 与 `set`/`SET` 帧禁止写 `auth_token`（保留 adb 设 `dsh_token` 排障）。此前 receiver 以 `RECEIVER_EXPORTED` 注册、又由 `ModuleBridge.base()` 自动补本 App 自有 token，等于同机任意 App 发一条隐式广播即可绕过校验执行 `wb.prompt`(root am start)/改写 token；② **token 脱敏**：删掉「指令引擎已就绪 token=明文」状态行与 `CommandProcessor` 的 `token-mismatch` 回执里的 `cfg.authToken` 明文（`复制日志` 会一键复制全部日志外发）。
> - **删假功能 `dsh.open`**：`CommandProcessor` 的 case、`SIDE_EFFECT_CMDS` 幂等名单条目、`CompanionService.summarize` 兜底、`Config` 注释全部删除。`dshgo://` scheme 与 `setPackage(cfg.wbPackage=WorkBuddy 包名)` 错配、真机必然 `ActivityNotFoundException` —— 去掉「看起来能用实际不能用」。表端按钮同轮删除。
> - **健壮性**：A4/W6 `onDestroy` 的 `unregisterReceiver` 加 try/catch、`COMPANION_SET`/`set` 分支 `bridge` 空保护；W3 `LocalTestLink.state()` 恒返回 `offline`（此前 XMS SDK 初始化失败回退它却让面板显示「已就绪/已连接」的**假绿**）；A3 `versionName()` 兜底与 `activity_main.xml` 占位不再硬编码 `0.1.11`（读不到显示「版本未知」）；A5 日志仅在用户原本贴底时才自动滚动。
> - **帧协议 / 签名 / `@system.interconnect` 单例 / 两条独立通道 / 幂等去重 / ping-pong / ready / 断线补发 全部未动**；`dsh.prompt` / `wb.prompt` 如实回执不变。`apksigner` 验签同源 `8637ca4e…d12c`。
> - **明确不动**：W2(`DshClient` cookie 静态化) 属审查独立 P2 性能项、带并发复杂度，本轮保留现状。
>
> **本端在 0.1.18 轮次零改动**（历史陈述，留档）：表端「更多工具」的分组 / 文案 / 配色重做全部在 `watch-rpk/src/pages/index/index.ux`，手机端 APK **内容与上一版完全相同、无需重装、无需重新授权 root、无需录音权限**。当时表端仍通过 `dsh.cancel` / `dsh.open` / `status` / `dsh.list` 调用既有指令引擎，帧格式未变（`dsh.open` 已于 0.1.12 删除）。
> **⌨️ 0.1.11 本轮改动：手机端不再参与「自定义指令的输入」**
> - **删除 0.1.10 整套语音 / 输入功能**：`VoiceAsr.java`、`AndroidManifest` 的 `RECORD_AUDIO` 与 `RecognitionService` query、`CompanionService` 的语音帧分支 / `ACTION_VOICE_TEXT` 广播 / `startVoiceAsr` / `sendVoiceFrame`、`MainActivity` 的「自定义指令 · 手机端输入」折叠区与 `requestMicIfNeeded()`、`activity_main.xml` 的 compose 块、`bg_input.xml`。
> - **原因（真机 + 主次）**：0.1.10 把识别放在手机 `:relay` 服务里，真机被 ROM 拦成「识别中断」；且「手机打字 → 回传手表 → 再从手表发回手机」逻辑绕圈，把主控权交回手机。用户明确「目的是用手表端控制，不要搞错主次」。手表才是发起端，手机只被动执行指令。表端点 `<input>` 也不弹系统输入法（Vela 三方 rpk 无此能力），0.1.15 的草稿框连兜底都是假的。
> - **本端回到纯状态面板**：`MainActivity` 只做「重新查询状态 / 自检引擎 / 复制日志 / 重启服务」四件事 + 状态大读数 + 5 行链路读数 + 运行日志（沿用 0.1.9 impeccable 设计）。自定义指令全部在**表端内置输入面板**本地拼字 → 原 `send()` 发送；「输入 ≠ 发送」由手表端两个发送键显式确认。
> - **链路一行未动**：`CommandProcessor` / `ModuleBridge` / `XmsInterconnectLink` / `WbIngress` / `State` / `ResultReceiver` / 指令幂等去重 / ping-pong / ready 通告 / 断线补发全部保持原样；`dsh.prompt` / `wb.prompt` 两条独立通道与如实回执不变。`aapt dump badging` 确认 versionCode=12 / versionName=0.1.11，权限只剩 FGS / FGS_DATA_SYNC / POST_NOTIFICATIONS / INTERNET；二进制 Manifest 无语音声明、dex 里语音相关字符串 0 处；`apksigner` 验签同源 `8637ca4e…d12c`。
> - **⚠️ 两端都要重装**（`watch-0.1.16.rpk` + `companion-0.1.11.apk`）；KernelSU root 授权对象仍是 `com.example.watchctl`，**0.1.8 已授权则无需重做**；这次**不需要授予录音权限**。
>
> **上一版 companion 0.1.10 / code11（自定义指令 + 语音输入，2026-11-15）已整体作废**，详见主 `README.md` 第十二次修正说明。
> **0.1.9 / code10（UI 全面美化与去噪）**：配套表端 rpk 0.1.14/code15，详见下方第十次修正说明。
> - **合并内容**：`CommandProcessor` / `DshClient` / `WbIngress` / `RelayException` / `Config` 从原 `lsposed/` 模块搬进本 APK（保留 `com.watchctl.hook` Java 包名，Android 单 APK 可含多个包）；删掉 `Config` 唯一一处 `XSharedPreferences.load()`，砍掉 `HookMain` / 保活 Service / 模块设置页。全模块只有这两个类碰 Xposed API，且与指令链路无关。
> - **链路改造**：`ModuleBridge` 不再发 `com.watchctl.CMD` / 等 `com.watchctl.RESULT` 广播，改为线程池内**本地直调** `CommandProcessor.handle(..., ResultSink)`，结果经 `onEngineResult()` 直接回表。跨进程广播整跳删除，「模块被回收 / 广播没送达 / 发指令超时」这类问题不复存在。
> - **配置**：补 `INTERNET` 权限 + `usesCleartextTraffic`（引擎 `127.0.0.1:3080`）、`<queries>` 加 `com.tencent.workbuddy.app`；`0.1.8/code9` → **`0.1.9/code10`**（Manifest 引用 `@style/Theme.WatchCtl` 与 `@drawable/ic_watchctl`）。
> - **root 授权对象变更（唯一需要你做的一次性动作）**：KernelSU 里给 **`com.example.watchctl`（腕控伴侣，本 App）** 授权 root，不再是已废弃的 `com.watchctl.hook`。旧的模块 APK 可卸载。
> - **0.1.7 连接稳定性改动本版完整继承**：`XmsInterconnectLink` 记录 `registeredNodeId` 只在 nodeId 变化才重挂监听；`onServiceConnected()` 立即探节点；注册成功/节点变化/`poke()`/45s 保活时主动发 `cmd='ready'`；状态面板加链路健康行。`CompanionService` 的 `State.clear()`、`pong` 带 `t`、透传 `duplicate` 均保留。
> - **指令幂等去重**随引擎一起搬入（`CommandProcessor` 按 `watchctl_id`，只对有副作用命令，64 条/120s），配套表端「断线入队、连上补发」，不会重复执行 WorkBuddy 或重复写引擎。
> - **🎨 0.1.9 UI 全面重做（impeccable，只改显示层，`CompanionService` / `XmsInterconnectLink` / 广播协议一行未动）**：`res/` 从**完全为空**补齐为 `values/colors.xml`（tinted neutral + 语义色）、`values/themes.xml`（`Theme.WatchCtl`，替换系统默认 Theme.Material 与 `ic_dialog_info`）、`drawable/ic_watchctl.xml`（自有矢量图标）、3 个按钮/日志背景、`layout/activity_main.xml`（XML 布局替换纯代码搭 UI）。`MainActivity.java` 重写为：首屏「当前链路大状态 + 5 行链路读数（前台服务 / 手表节点 / 权限·监听 / 指令引擎 / 收发帧）+ 运行日志」；24 行常驻 GUIDE 收进「使用说明与排障」折叠（默认收起）；主操作 1 个 + 次级 3 个用 `weight` 等分，不再横向溢出；日志 `SpannableString` 语义着色（✓绿 / ✗红 / ⚠琥珀，**符号 + 颜色双重编码**）、时间戳弱化、「全部 / 仅异常」筛选、新日志自动滚到最新；「复制日志」只带运行日志。**所有读数从 `State` 广播的真实文本行解析，解析不到显示「—」，「已连接」只在真收到表端帧回执时给出，绝不编造状态**。

## 架构（0.1.8 单 APK）

```
REDMI Watch 6 (Vela .rpk)
   │  @system.interconnect (BLE, 包名+签名校验)
   ▼
【唯一 1 个 APK】腕控伴侣 com.example.watchctl（前台服务 :relay，xms-wearable SDK）
   │  MessageApi.addListener 收帧 ──► CompanionService.handleWatchFrame
   │      · ping → 本地直回 pong（只验证 BLE）
   │      · 指令 → ModuleBridge 线程池 ──► 本应用内本地直调（无广播/无跨进程）
   ▼
内置指令引擎（同 APK 同进程：CommandProcessor / DshClient / WbIngress / Config）
   ├─ dsh.*  → 127.0.0.1:3080 dsh-go 引擎（session/prompt）
   └─ wb.*   → [root su] am start SEND -n .ShareAddToChat（预填 WorkBuddy 输入框）
   │  ResultSink 回调 ──► onEngineResult ──► deliverToWatch
   ▼
MessageApi.sendMessage 把 {id, ok, text, cmd, target, stage} 回写手表
```

> 兼容：仍保留 `com.watchctl.RESULT` 广播接收器，仅供 `adb shell am broadcast` 自测；主路径不经过它。

## 硬性约束（与手表 rpk 配对）

1. **`applicationId` == rpk `manifest.json.package`**：都用 `com.example.watchctl`。
2. **同一张证书签名**：`signingConfigs.watchctl` 指向 `keys/watchctl.keystore.p12`，SHA-256 `8637ca4e…d12c`，与 rpk `sign/debug/` 一致。签名不符 interconnect 直接拒连。
3. **手机装「小米运动健康」并与手表蓝牙配对**——SDK 的 `getConnectedNodes()` 依赖该服务；未装/未配对时节点为空，无法收发。
4. companion 必须被**打开过一次**以拉起前台服务（通知栏显示「腕控伴侣」）。
5. 首次消息通信需 `DEVICE_MANAGER` 权限；SDK 首次授权默认授予，`XmsInterconnectLink` 已做查询/申请。

> 说明：`interconnect.instance()` **无参**，靠「包名 + 签名」自动配对，不存在 `instance({package,fingerprint})` 或 `FINGERPRINT` 字段（旧文档写错，已更正）。

## 官方 SDK 接入（0.1.5 起）

`app/libs/xms-wearable-lib_1.4_release.aar`，`build.gradle` 用 `implementation fileTree(dir:'libs', include:['*.aar'])` 引入；`AndroidManifest.xml` 声明 `<queries>`（`com.xiaomi.wearable` / `com.mi.health`）满足 Android 11+ 包可见性。

`XmsInterconnectLink` 用的真实 API（均以 `javap` 校准）：

| 用途 | API |
| --- | --- |
| 取 SDK 门面 | `Wearable.getNodeApi / getMessageApi / getAuthApi / getServiceApi(Context)` |
| 找手表节点（不需权限） | `NodeApi.getConnectedNodes()` → `Task<List<Node>>`，取 `node.id` |
| 收表端帧 | `MessageApi.addListener(nodeId, OnMessageReceivedListener)`；`onMessageReceived(nodeId, byte[])`，字节为 **UTF-8 JSON** |
| 回写表端 | `MessageApi.sendMessage(nodeId, byte[])` |
| 权限 | `AuthApi.checkPermissions / requestPermission`，`Permission.DEVICE_MANAGER / NOTIFY` |
| 服务状态 | `ServiceApi.registerServiceConnectionListener` |

> ⚠️ 官方 SDK 里**没有** `com.xiaomi.xms.wearable.Interconnect` 这个类——早期反射版本正是猜错了类名，导致真机 `ClassNotFound` → 回退 `LocalTestLink` → 手机端从未成为合格对端。
> 节点握手每 5s 轮询、监听每 60s 重挂（SDK 服务重启后监听会失效，社区实战经验）。

## 广播协议（companion ↔ 模块）

| 方向 | Action | Extras |
| --- | --- | --- |
| companion → 模块 | `com.watchctl.CMD` | `watchctl_token` `watchctl_cmd` `watchctl_args`(JSON 串) `watchctl_id` |
| 模块 → companion | `com.watchctl.RESULT` | `watchctl_id` `watchctl_ok`(bool) `watchctl_data`(JSON 串) |
| 写入配置 | `com.watchctl.SET` | `dsh_token` 等（**0.1.12 起 `auth_token` 不接受任何帧/广播写入**，见下方安全说明） |
| 在线探测 | `com.watchctl.CMD` + `cmd=module.ping` | 回 `probe-` 前缀 id，只更新状态面板，不回手表 |

`watchctl_token` 默认 `watchctl-dev`，正式用请改复杂值并与模块 `auth_token` 对齐。

> **⚠️ 0.1.8 单 APK 后的现实**：上表的 `CMD`/`SET`/`RESULT` 是**早期「companion + 独立模块」两 APK 架构**的协议留档。`ModuleBridge` 现在是**线程池内本地直调** `CommandProcessor.handle(..., ResultSink)`，不再发 `CMD` 广播；`com.watchctl.RESULT` 只保留一个动态 receiver 供 adb 自测；`com.watchctl.SET` 兼容路径已无调用点（Manifest 无静态 receiver）。
> **0.1.12 调试后门安全收紧**：本服务为兼容 adb 排障，RESULT / 命令接收器仍以 `RECEIVER_EXPORTED` 动态注册，但 `CommandReceiver` 对 `TEST_FRAME` **只放行只读命令**（`status`/`dsh.list`/`module.ping`/`ping`）、对 `COMPANION_SET` **禁止写 `auth_token`**（只放行 `dsh_token`）。`ModuleBridge.base()` 会用本 App 自有 token 自动补 `watchctl_token`，若不设白名单，同机任意 App 发一条隐式广播就能绕过校验执行 `wb.prompt`/`dsh.prompt` 或篡改 token。adb 自测（只读查询 + 设 dsh_token）不受影响。

**0.1.4 起两条广播都是「显式定向」**：companion 用 `setClassName(com.watchctl.hook, CommandReceiver)`
+ `FLAG_INCLUDE_STOPPED_PACKAGES` + `FLAG_RECEIVER_FOREGROUND`；模块回执 `setPackage(com.example.watchctl)`。
不再用不限定包名的隐式广播（Android 8+/HyperOS 会拦跨应用隐式投递）。模块侧 0.1.1 起由
**Manifest 静态注册的 `CommandReceiver`** 接收（`exported=true`），显式广播可唤醒被回收的进程，
不再依赖 `RelayService` 常驻——这是「连接成功但发指令超时」的根因修复。

## 关键文件

| 文件 | 职责 |
| --- | --- |
| `CompanionService` | 前台服务：注册 RESULT/命令接收、把帧转引擎、回写手表；`registerReceiver` 做了 API33 兼容；**0.1.12 调试后门只读白名单 + 禁止写 auth_token + token 脱敏 + 注销保护** |
| `ModuleBridge` | 线程池内本地直调 `CommandProcessor.handle(..., ResultSink)`；`base()` 自动补本地 token |
| `WatchLink` | 手表传输层抽象 |
| `XmsInterconnectLink` | **官方 xms-wearable SDK 真实实现**（节点握手/消息收发/权限/重挂监听）；每个节点通过 `State` 上报到界面 |
| `LocalTestLink` | 无手表时进程内回环，仅 adb 自测（SDK 初始化异常才回退）；**0.1.12 `state()` 恒 `offline`，不再假绿** |
| `State` | 把 `:relay` 进程内 SDK 状态经显式广播（`com.watchctl.STATE`）发到主进程界面，去重防刷屏 |
| `MainActivity` | **实时状态面板**（0.1.2）：显示服务/穿戴服务/节点/权限/监听/收发帧，含「复制全部状态」，无需 adb |

## 0.1.2：前台服务解耦 + 状态可视化

- **修复**：Android 14(`targetSdk34`) 对 `foregroundServiceType="connectedDevice"` 的前台服务强制校验蓝牙运行时权限，未授予时 `startForeground` 抛 `SecurityException`；旧版把它与 `createLink()/connect()` 放在同一个 `try`，一处失败 → 服务半残、SDK 监听从未注册。已把**前台服务、广播注册、互联链路三步各自独立 try/catch**——前台服务失败也照常注册监听。
- **状态面板**：`adb logcat` 需要设备连上电脑，真机常常抓不到。现把 SDK 全链路状态直接显示在 App 界面：打开 companion 即拉服务并自动查询快照；「重新查询状态」「重启服务」「复制全部状态」三键。

## 0.1.3：真机日志推翻「1006 = 蓝牙通道断」，改判时序竞态并修 3 个 bug

**结论更正**：0.1.2 曾把 `1006` 判为「系统/蓝牙层、代码改不了」。2026-11-14 手机端状态面板日志证伪：
```
15:55:30  消息监听：已注册 ✓ / 权限：DEVICE_MANAGER 已授予 ✓
15:56:41  ← 收到表端帧 #1: {"cmd":"ping"}
15:56:41  → 回写表端 #1 成功 {"cmd":"ping","text":"pong"}
15:56:51  收发：收到帧 1 / 回写 1 / 错误 0
```
表端 `ping` 到达手机、`pong` 也回写成功，**说明手表↔手机互联通道本来就是通的**，1~3 的蓝牙/配对/保活无需再折腾。
`1006` 真实成因是**时序竞态**：手机端冷启动到「消息监听已注册」耗时约 31s（15:54:59 → 15:55:30，卡在 `DEVICE_MANAGER` 授权弹窗），而表端 0.1.8 握手窗口只有 13s 且 `onerror(1006)` 一触发就 `settleFail` 停掉全部 ping——表端在手机端就绪前点连接，必然被永久钉在「1006 断开」。

- **表端（bridge.js，0.1.9）**：握手窗口 13s → 60s 持续发 ping 直到收到回执；`onerror(1006/204/1000)` 只记事实、不再判死（仅 `1001` 快速失败）；超时结论改判「手机端尚未就绪」而非误导去查蓝牙配对。
- **手机端（XmsInterconnectLink）**：去掉每 60s 强制 `removeListener` + `addListener` 重挂，它撞出 `addListener you have registered` 假 ✗（15:56:30 实证）并会在两次调用间漏帧；SDK 返回 `registered/already` 一律按「已注册成功」处理；登记成功后清空 `lastError`，状态面板不再残留误导性的「最后错误」。
- **前台服务（CompanionService / Manifest）**：0.1.2 去掉 type 后，Android 14 反而抛 `MissingForegroundServiceTypeException`。现显式指定 `foregroundServiceType="dataSync"` + 三参 `startForeground(..., FOREGROUND_SERVICE_TYPE_DATA_SYNC)`，只需 normal 权限 `FOREGROUND_SERVICE_DATA_SYNC`，不触碰 `connectedDevice` 的蓝牙运行时权限校验。仍保留独立 try/catch，失败不阻断互联。

- **1006 定义**（官方）：「连接断开」，不是「对端未安装」（那是 `1001`）。但**它不等于蓝牙通道断了**——只要手机端面板显示 `listen=已注册` 且「收到帧 / 回写」有计数，通道就是通的。看面板判断卡在哪一侧：
  - 「ServiceApi 失败 ✗」「手表节点：无 ✗」「权限未授予」→ 手机侧未就绪（小米运动健康未装/未配对/后台被杀/权限未授予）；
  - 全 ✓ 且收发有计数 → 通道已通，`1006` 只是表端握手撞上手机端冷启动/授权窗口，回表端重连一次即可（0.1.9 起会自动等到就绪）。

## 0.1.4：修「连接成功但发指令超时」

**这是与 1006 完全不同的第二段链路**。表端「已连接」只由 ping/pong 判定，而
`CompanionService.handleWatchFrame` 对 `cmd=ping` 是 `link.send(pong); return;` **本地直回**，
压根不经 LSPosed 模块。真实指令走：

```
表端 → BLE → companion.bridge.sendCommand() → [广播 CMD] → 模块.CommandReceiver
     → CommandProcessor.dispatch() → dsh-go / WorkBuddy → [广播 RESULT] → companion.ResultReceiver
     → deliverToWatch() → link.send() → BLE → 表端
```

**根因**：模块 0.1.0 的 `CmdReceiver` 是 `RelayService.onCreate()` 里**动态注册**的。
`RelayService` 靠 `RelayApplication` 用 `startForegroundService()` 拉起，而
**Android 12+ / HyperOS 3(Android 16)** 后台启动 FGS 受限、模块进程又常被省电冻结 →
动态接收器不存在 → `com.watchctl.CMD` 广播无人接收 → 表端 8s 后「超时，手机没回应」。
状态面板那句「模块桥：OK」只是 `new ModuleBridge()` 成功（构造函数为空），是**假信号**。

- **模块 0.1.1**：新增 Manifest 静态注册 `CommandReceiver`（`exported=true`，监听 `CMD`/`SET`），
  `goAsync()` + 线程池处理，不依赖任何 Service 常驻；指令逻辑抽到 `CommandProcessor`，
  `RelayService` 降级为纯前台保活，去掉动态接收器避免一条指令被处理两次。
  结果广播改为 `setPackage(com.example.watchctl)` 显式定向 + `FLAG_INCLUDE_STOPPED_PACKAGES`。
  顺带补 `<queries>`、`usesCleartextTraffic`（dsh-go 是 `127.0.0.1:3080` 明文 HTTP）、
  `FOREGROUND_SERVICE_DATA_SYNC`、三参 `startForeground`。
- **companion 0.1.4**：广播加 `FLAG_INCLUDE_STOPPED_PACKAGES`（模块装完常未打开、处于 stopped
  状态，默认广播不送达）+ `FLAG_RECEIVER_FOREGROUND`；结果接收器用 `RECEIVER_EXPORTED`
  （API33+ 按版本分支，minSdk29 不崩）；新增 **模块在线探测**（`cmd=module.ping`，模块纯本地
  秒回，4s 超时），状态快照增加「模块=在线 ✓ / 无响应 ✗」，界面加「探测模块」按钮；
  删掉假的「模块桥：OK」和过时的 1006 蓝牙引导。
- **表端 0.1.10**：发指令超时 8s → 12s；超时文案由「超时，手机没回应」改为
  **「超时·模块未回执」**并提示去看手机面板「模块=」一行。

> ⚠️ **以下 0.1.4 判读为历史留档**：当时手机端还是「companion + 独立模块」两个 APK。
> **0.1.8 单 APK 合并后，跨进程广播这一跳已被删除**，不再存在「模块没收到广播 / 模块=无响应」；
> 状态面板改看「**指令引擎=**」，按钮改为「**自检引擎**」。若 WorkBuddy 通道失败，只需确认
> KernelSU 给本 App `com.example.watchctl` 授权了 root，再点「自检引擎」看 `root=可用 ✓`。
> **0.1.9 界面重做后**：顶部是「当前链路」大状态与「链路读数」5 行（其中「指令引擎」对应旧「模块=」），
> 底部是「运行日志」；按钮为「重新查询状态」（主）+「自检引擎 / 复制日志 / 重启服务」（次），
> 「使用说明与排障」默认折叠。旧「状态快照」那种逐行刷屏的形式已被结构化读数取代。

**判读（当时，仅留档）**：表端超时后看手机状态面板的「模块=」——
`在线 ✓` 说明模块这一跳通；`无响应 ✗` 说明模块没收到广播，当时要装/更新 module、给「腕控·主中继」开自启动与后台无限制、打开过一次模块 App，然后点「探测模块」复测。

> 注（留档）：模块在线探测与 LSPosed 是否激活无关；`wb.prompt` 走 root `am start` 需要 root 授权（当时授权给 `com.watchctl.hook`，现改为本 App `com.example.watchctl`）。

## 0.1.5（已被 0.1.6 推翻，仅作教训留档）：修「WorkBuddy 里没指令」的错误方案

**真机现象**：链路②全通（`模块=在线 ✓`、`← 收到表端帧`、`→ 模块`、`← 模块结果 ok=true`、
`← 回写手表 "已发送到 WB"`），但 WorkBuddy 里没有这条指令；用户同时确认「发到 DSH」能写入成功。

**根因**：`wb.prompt` 旧实现是 `WbSender.sendText()` → 从**静态广播接收器的后台线程**
`ctx.startActivity(ACTION_SEND)` 拉 WorkBuddy 分享界面。Android 10+（Android 16/HyperOS 3 尤甚）
的后台启动 Activity 限制**静默丢弃且不抛异常** → `startActivity` 正常返回 → `CommandProcessor`
无条件 `ok=true` → companion `summarize()` 写死「已发送到 WB」→ 表端 ✓。`ok=true` 只证明广播被处理，
**不证明文本送达 WorkBuddy**。而 `dsh.prompt` 走 HTTP `session/prompt`（纯网络、不受后台限制）故成功。

**修复**：
- **module 0.1.2**：`wb.prompt` 与 `dsh.prompt` 统一走 `submitPrompt()` → 引擎 `session/prompt`
  （真机已验证的写入通道）；只有引擎返回 ok 才回 `ok=true`，失败抛异常回 `ok=false`；回执带
  `delivered`/`via`/`sessionId`/`text`。删除 `WbSender` 及 `wb_send_component` 死配置。
  `DshClient.login()` 不再因「token 为空」硬抛（本机引擎可免鉴权）。`module.ping` 回执去掉含糊的
  `enabled`，改语义明确的 `cmdChannel`/`promptVia`/`frameworkHook`。
- **companion 0.1.5**：`summarize()` 优先用模块如实的 `text`，不再写死「已发送到 WB」；
  结果日志增加「送达=✓/✗」；清掉「去 LSPosed 勾作用域」的误导引导（静态接收器不依赖其激活）。
- **表端 0.1.11**：成功时提示「指令经引擎 session/prompt 写入 WorkBuddy 会话」。

**⚠️ 上面这套「统一走引擎」的修复是错的**：它把 dsh-go 引擎会话当成了 WorkBuddy，两者根本不是同一个东西。用户真机实测 `delivered=✓ 引擎 session/prompt` 后 **WorkBuddy 里依然完全没有**。正确方案见下 0.1.6。

## 0.1.6：两条通道拆开，`wb.prompt` 恢复 WorkBuddy 真实入口

**用户确认**：「WorkBuddy 里完全没有」+「两个按钮都要能发」。

**根因**：0.1.2 把「App 进程内 `ctx.startActivity()` 被 Android 16 后台启动限制拦」错误外推成「`am start` 整条入口不可用」，转而把 `wb.prompt` 并进 dsh-go 引擎 `session/prompt`。但本方案前期真机调研（`方案.md §4.9/§4.10`、`scripts/wb-a1.sh`）早已证明：**root shell 执行的 `am start ... -n com.tencent.workbuddy.app/.ShareAddToChat` 豁免后台启动限制，能把文本预填进 WorkBuddy 输入框**。dsh-go 引擎与 WorkBuddy 是两个不同东西，写引擎会话不会出现在 WorkBuddy。

**修复**：
- **module 0.1.3**：新增 `WbIngress`。`dsh.prompt` 保留 `DshClient`→引擎 `session/prompt`（`target=dsh-go`/`delivered=true`）；`wb.prompt` → root `su -c 'am start SEND ... .ShareAddToChat'`（`target=WorkBuddy`/`stage=prefill`/`launched=true` 但 **`delivered` 恒 false**）。严格 `shQuote` 转义防注入、捕获 `am` 输出判成功；无 root/无组件 → `ok=false` 带明确原因（`wb-no-root`/`wb-not-installed`）。`WbIngress.probe()` 自证 `wbRoot/wbInstalled/wbShareComponent`，并入 `module.ping` 回执。
- **companion 0.1.6**：`deliverToWatch()` 透传 `cmd/target/stage` 给表端；`summarize()` 与结果面板按 target 分列（`dsh-go=已写入会话` / `WorkBuddy=预填(需点发送)`），不再写死「送达=✓」；`onProbeResult` 解析并显示 root/组件自证；GUIDE 改为两条通道真实说明。
- **表端 0.1.12**：按 `cmd/target/stage` 分别提示，不再一律「已完成」。

**判读（两条通道判据不同）**：
- 发到 DSH → 表端「已写入 dsh-go 引擎会话」＝引擎收下；✗ 多为 3080 不可写或需 `dsh_token`。
- 发到 WorkBuddy → 表端「已发到 WorkBuddy 输入框（预填）· 需在手机点『发送』」＝`am start` 发出，**不是已执行**；`delivered=false` 是如实标注，不是 bug。先点「自检引擎」看 `root=可用 ✓ / 不可用 ✗`；`✗` 就是本 App 没拿到 KernelSU root，去给 `com.example.watchctl` 授权。

## 编译 / 安装

```
ANDROID_HOME=/opt/android-sdk ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/watchctl-companion-debug.apk
# 装小米运动健康并与手表蓝牙配对
# 【0.1.8 起手机端只需这 1 个 APK】不再装/更新 module APK、不用勾 LSPosed 作用域
# KernelSU 里给【本 App】「腕控伴侣」(com.example.watchctl) 授予 root（原 com.watchctl.hook 已废弃）
# 打开「腕控伴侣」一次（拉起前台服务）：0.1.9 新界面顶部看「当前链路」与「链路读数 / 指令引擎」，
#   点「自检引擎」看「运行日志」里的 wbRoot 回执；「使用说明与排障」默认折叠
# ⚠️ 0.1.9 / 0.1.14 两端界面都已重做：表端必须卸载旧「腕控」后装 watch-0.1.14.rpk（不再是「不用重装」）
# 发到 DSH：确保 dsh-go 引擎在 127.0.0.1:3080 可写（session/prompt）
# 发到 WorkBuddy：装了 com.tencent.workbuddy.app；分享会把文本预填进输入框，需点「发送」
```

## 验证

1. **建议先打开 companion 让手机端完成冷启动授权（约 30s）；0.1.8 起顺序不再致命**——手机端 listener 一就绪就主动发 `ready`，表端收到立即握手，且表端持续心跳 + 掉线自动重连，不用反复手点。表端持续发 `ping` 帧、companion **本地直回 `pong`**（`handleWatchFrame` 对 `cmd=ping` 直接 `link.send()`，不经指令引擎）→ 显示「已连接 · 双向通道已验证」；此后两端常驻维护链路（表端 9s 心跳 / 30s 判掉线，手机端 45s ready 保活 + 节点变化重挂监听）。「诊断／能力」看事实清单，**不再是 0/1001/204 诊断码**（本固件无 `diagnosis()`）。0.1.9 新面板：顶部「当前链路」大状态 +「链路读数」5 行（前台服务 / 手表节点 / 权限·监听 / 指令引擎 / 收发帧），底部「运行日志」可按「仅异常」筛选、✓绿/✗红语义着色并自动滚到最新；`链路健康：` 原始行仍在日志里可查，点「复制日志」即可整段发我排障。
2. 手机 `adb logcat -s WatchCtl-XMS WatchCtl-Companion` 应看到「手环节点已连接」「消息监听已注册」「← 表端帧」。
3. 无手表全链路自测：
   ```
   adb shell am broadcast -a com.watchctl.TEST_FRAME --es frame '{"id":"t1","cmd":"status"}'
   ```
   应看到 `→ 指令引擎 cmd=status` 与结果回写日志。**0.1.12 起后门只放行只读命令**（`status`/`dsh.list`/`module.ping`/`ping`）；若发 `wb.prompt`/`dsh.prompt`/`set` 等副作用或写配置帧，会在状态行看到「TEST_FRAME 拒绝非只读命令」——这是安全收紧后的预期行为，不是故障。设引擎 token 用 `am broadcast -a com.watchctl.COMPANION_SET --es dsh_token '...'`（写 `auth_token` 同样被拒）。
