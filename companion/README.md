# 手机侧 companion APK（腕控伴侣）

手机端**唯一的一个 APK**（`com.example.watchctl`，当前 `0.1.21`/`code22`）：接收 REDMI Watch 6（Vela）经 BLE `@system.interconnect` 发来的 JSON 指令，在**本应用内直接执行**（dsh-go 引擎 / WorkBuddy 两条通道），并把结果回写给手表。手机端不需要第二个模块，也不依赖 LSPosed。

> 完整可编译 Android 工程（AGP 8.5.2 / Gradle 8.10 / compileSdk 34 / minSdk 29），已接入小米官方 `xms-wearable-lib_1.4_release.aar`（来自官方 `interconnect_dev_test_demo`，放在 `app/libs/`）。
> 交付恒 2 文件：`watch-0.1.26.rpk` + `companion-0.1.21.apk`，同包名同签名 `8637ca4e…d12c`。

## 架构

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
   ├─ dsh.* → 127.0.0.1:3080 dsh-go 引擎（session/prompt，持久化 cookie + 自愈）
   └─ wb.*  → [root su] am start SEND -n .ShareAddToChat（预填 WorkBuddy 输入框）
   │  ResultSink 回调 ──► onEngineResult ──► deliverToWatch
   ▼
MessageApi.sendMessage 把 {id, ok, text, cmd, target, stage} 回写手表
```

兼容：仍保留 `com.watchctl.RESULT` 广播接收器，仅供 `adb shell am broadcast` 只读自测；主路径不经过它。

## 硬性约束（与手表 rpk 配对）

1. **`applicationId` == rpk `manifest.json.package`**：都用 `com.example.watchctl`。
2. **同一张证书签名**：`signingConfigs.watchctl` 指向 `keys/watchctl.keystore.p12`，SHA-256 `8637ca4e…d12c`，与 rpk `sign/debug/` 一致。签名不符 interconnect 直接拒连。签名材料不入库，见根 `.gitignore`。
3. **手机装「小米运动健康」并与手表蓝牙配对**——SDK 的 `getConnectedNodes()` 依赖该服务；未装/未配对时节点为空，无法收发。
4. companion 必须被**打开过一次**以拉起前台服务（通知栏显示「腕控伴侣」）。
5. **KernelSU 给本 App 授权 root**：WorkBuddy 通道与 dsh 凭据自愈都要 root。授权一次即可，升级不用重授。
6. 首次消息通信需 `DEVICE_MANAGER` 权限；SDK 首次授权默认授予，`XmsInterconnectLink` 已做查询/申请。

> `interconnect.instance()` **无参**，靠「包名 + 签名」自动配对，不存在 `instance({package,fingerprint})` 或 `FINGERPRINT` 字段。

## 官方 SDK 接入

`app/libs/xms-wearable-lib_1.4_release.aar`，`build.gradle` 用 `implementation fileTree(dir:'libs', include:['*.aar'])` 引入；`AndroidManifest.xml` 声明 `<queries>`（`com.xiaomi.wearable` / `com.mi.health`）满足 Android 11+ 包可见性。`XmsInterconnectLink` 用的真实 API（均以 `javap` 校准）：

| 用途 | API |
| --- | --- |
| 取 SDK 门面 | `Wearable.getNodeApi / getMessageApi / getAuthApi / getServiceApi(Context)` |
| 找手表节点（不需权限） | `NodeApi.getConnectedNodes()` → `Task<List<Node>>`，取 `node.id` |
| 收表端帧 | `MessageApi.addListener(nodeId, OnMessageReceivedListener)`；`onMessageReceived(nodeId, byte[])`，字节为 UTF-8 JSON |
| 回写表端 | `MessageApi.sendMessage(nodeId, byte[])` |
| 权限 | `AuthApi.checkPermissions / requestPermission`，`Permission.DEVICE_MANAGER / NOTIFY` |
| 服务状态 | `ServiceApi.registerServiceConnectionListener` |

> 官方 SDK 里**没有** `com.xiaomi.xms.wearable.Interconnect` 这个类——早期反射版本猜错类名，导致真机 `ClassNotFound` → 回退 `LocalTestLink` → 手机端从未成为合格对端。节点登记只在 nodeId 变化时重挂监听，注册成功 / 节点变化 / 界面打开 / 45s 保活时主动发 `cmd='ready'` 帧。

## DSH 凭据与自愈

dsh-go 的长期凭据是 30 天有效的 cookie；launch token 每次引擎进程启动都会变。`Config` 持久化 `dsh_token` / `dsh_cookie`：

- **探活**：`DshClient` 优先用持久化 cookie 请求，200 即用，覆盖绝大多数 dsh-go 更新场景；401/403 才触发自愈。
- **自愈**（`DshSelfHeal`，零外部依赖、不需要 Termux/Python）：① 只读 dsh-go WebView cookie jar 拿 cookie；② 读不到则用 App 内置 `assets/dsh_capture.sh` 以 root 自起引擎抓 token 换 cookie；拿到立即 `persistCookie` 落盘。
- **健壮性**：su 超时 `killTree` 递归清理、内存扫描逐候选验证有 60s 总预算、日志脱敏（帧正文替为 `«N字»`）、`SimpleDateFormat` 用 `ThreadLocal`、自愈步骤以人话投到界面日志（无明文）。
- **手动入口**：「DSH 凭据设置」折叠面板可 Token/Cookie 手填 + 保存 / 清除，供自动抓不到时兜底与测试自愈；清除 = 写空值，下条指令触发重取。复用现有 `COMPANION_SET` 写入通道，在 `:relay` 进程写 SP，下条指令即生效。状态只报「是否配置」，绝不显示明文。

## 关键文件

| 文件 | 职责 |
| --- | --- |
| `CompanionService` | 前台服务：注册接收器、把帧转引擎、回写手表；接收 `COMPANION_SET` 写 `dsh_token`/`dsh_cookie`；只读调试白名单 + token 脱敏 + 注销保护 |
| `ModuleBridge` | 线程池内本地直调 `CommandProcessor.handle(..., ResultSink)`；`base()` 自动补本地 token |
| `XmsInterconnectLink` | 官方 xms-wearable SDK 真实实现（节点握手/消息收发/权限/重挂监听/ready 通告）；帧正文 `redact()` 脱敏；每个节点通过 `State` 上报界面 |
| `LocalTestLink` | 无手表时进程内回环，仅 adb 自测；`state()` 恒 `offline`，不假绿 |
| `State` | 把 `:relay` 进程内 SDK 状态经显式广播（`com.watchctl.STATE`）发到主进程界面，去重防刷屏；`SimpleDateFormat` 用 `ThreadLocal` |
| `MainActivity` | 实时状态面板 + 日志「人话/原文」切换（`humanize()`）+ DSH 凭据折叠面板；所有读数从 `State` 真实文本行解析，解析不到显示「—」，绝不编造状态 |
| `CommandProcessor` | 内置指令引擎：命令白名单、`watchctl_id` 幂等去重、两条通道分派、如实回执 |
| `hook/DshClient` | dsh-go 会话 + cookie 探活/重登/持久化 + 自愈接线 |
| `hook/DshSelfHeal` | 零外部依赖的内存扫描/ cookie 抓取自愈 |
| `hook/WbIngress` | root shell `am start -n .ShareAddToChat` 预填 WorkBuddy，`stage=prefill`/`delivered=false` |

## 编译 / 安装

```bash
ANDROID_HOME=/opt/android-sdk ./gradlew assembleRelease
# 或调试：./gradlew assembleDebug
adb install -r app/build/outputs/apk/release/app-release.apk
# 装小米运动健康并与手表蓝牙配对
# KernelSU 里给【本 App】「腕控伴侣」(com.example.watchctl) 授予 root
# 打开「腕控伴侣」一次（拉起前台服务）
# 发到 DSH：确保 dsh-go 引擎 127.0.0.1:3080 可写
# 发到 WorkBuddy：装 com.tencent.workbuddy.app；分享预填输入框，需点「发送」
```

`deliver.sh` 只取 release 包整理到 `/workspace/交付/companion-<版本>.apk`。**换设备 clone 后需单独拷贝 `keys/` 与 `watch-rpk/sign/`**（签名材料不入库），否则无法同签名打包。

## 排障与验证

- 连接出问题时打开手机面板：顶部「当前链路」大状态 + 5 行链路读数；`1006` 不等于蓝牙断了——只要面板 `listen=已注册` 且收发有计数，通道就是通的，`1006` 只是表端握手撞上手机端冷启动/授权窗口，0.1.13 起表端会自动等到就绪并重连。
- 自检：点「自检引擎」看运行日志里 `root` / WorkBuddy 分享组件自证；手机 `adb logcat -s WatchCtl-XMS WatchCtl-Companion` 看「手环节点已连接 / 消息监听已注册 / ← 表端帧」。
- 无手表自测（只放行只读命令）：
  ```
  adb shell am broadcast -a com.watchctl.TEST_FRAME --es frame '{"id":"t1","cmd":"status"}'
  ```
  写副作用命令或 `wb.prompt`/`dsh.prompt` 会被「TEST_FRAME 拒绝非只读命令」挡下，这是安全收紧后的预期行为。设引擎 token 用 `am broadcast -a com.watchctl.COMPANION_SET --es dsh_token '...'`。
- 交付验证：`apksigner` 验签同源 `8637ca4e…d12c`；dex 内验 `ThreadLocal` / `killTree` / `redact` 等关键实现。
