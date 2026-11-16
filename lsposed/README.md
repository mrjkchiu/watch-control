# 腕控 · LSPosed 加固模块（L1.5）— ⚠️ 已于 0.1.8 合并进 companion，本目录停止交付

> **⚠️⚠️ 0.1.8 单 APK 合并（2026-11-15）：本目录已废弃，不再是交付产物，以下全部为历史留档。**
> - 手机端**只剩一个 APK**：`companion-0.1.9.apk`（`com.example.watchctl`）。指令处理类
>   `CommandProcessor` / `DshClient` / `WbIngress` / `RelayException` / `Config` 已搬进 companion，
>   改为**本应用内本地直调**（`ModuleBridge` → `CommandProcessor.handle(..., ResultSink)`），
>   不再发 `com.watchctl.CMD` / 等 `com.watchctl.RESULT` 广播。
> - **不要**再安装本目录编译出的 `module-*.apk`、**不要**勾 LSPosed 作用域、**不要**给 `com.watchctl.hook` 授权 root。
>   KernelSU root 授权对象已改为 **`com.example.watchctl`（腕控伴侣，本 App）**。
> - 交付恒 **2 文件**（当前）：`watch-0.1.14.rpk` + `companion-0.1.9.apk`；**0.1.9 / 0.1.14 两端界面都已重做，两端都要重装**（0.1.8 时「表端不用重装」的结论已作废）。
> - 保留本目录只为记录逐版排障过程。下面所有「当前状态 / 静态接收器 / 探测模块 / 装 module APK」的描述
>   都是合并前的历史实现，**不要照着操作**；当前实现见 [../README.md](../README.md) 与 [../companion/README.md](../companion/README.md)。

---

> 你的手机已装 **ZygiskNext + LSPosed**，第一轮真机 probe 也实证 `lspd` / `zn-zygisk-companion64` 在跑（真注入）。（历史）
> 本目录把这条能力落地成一个 LSPosed 模块骨架 + relay 侧的配套通道，作为 **L1.5 加固层**。（历史）

> ⚠️ **历史状态（module 0.1.4，2026-11-15，合并前最后一版）**：本目录当时是**完整可编译的成品 APK 源码**（`module-0.1.4.apk`），不再是「骨架」。
> - **0.1.4（连接稳定性专项）**：新增**指令幂等去重**，配套表端 0.1.13 的「断线入队、连上自动补发」。`CommandProcessor` 维护 `SIDE_EFFECT_CMDS`（`dsh.prompt` / `wb.prompt` / `dsh.new` / `dsh.cancel` / `dsh.open`）与进程内 `SEEN`（`LinkedHashMap`，`DEDUP_MAX=64` / `DEDUP_TTL_MS=120000`），`claimOnce(id, cmd)` **只对有副作用命令**按 `watchctl_id` 去重；命中重复直接回 `ok=true` / `duplicate=true` / `text="重复指令已忽略（不会重复执行）"`，**不会重复写引擎、也不会重复拉起 WorkBuddy**。只读命令（`status` / `dsh.list` / `module.ping`）不去重，companion 面板识别 `duplicate` 后不会误判「未送达」。
> - 两条指令通道（`dsh.prompt` → 引擎 `session/prompt`；`wb.prompt` → root `am start .ShareAddToChat`）与静态接收器机制**均未改动**，仅在其前面加了一层去重闸门。
> - 指令接收 = **Manifest 静态注册 `CommandReceiver`**（显式广播可唤醒进程，**不依赖 LSPosed 激活、无需勾选作用域**，也不依赖 Service 常驻）；指令逻辑在 `CommandProcessor`。
> - **⚠️ dsh-go 引擎 ≠ WorkBuddy**。0.1.3 起两条通道拆开、各自如实回执：
>   - `dsh.prompt` → `submitPrompt()` → 引擎 `session/prompt`（`target=dsh-go`，引擎 ok → `delivered=true`）。
>   - `wb.prompt` → `WbIngress.send()` → **root `su -c 'am start SEND -n .ShareAddToChat'`**（前期 `scripts/wb-a1.sh` A1 真机验收入口；AOSP 豁免 ROOT/SHELL UID）。文本**预填进 WorkBuddy 输入框**，`target=WorkBuddy`/`stage=prefill`/`launched=true` 但 **`delivered` 恒 false**（预填 ≠ 执行，绝不冒充送达）。`WbIngress.probe()` 自证 `wbRoot/wbInstalled/wbShareComponent` 并入 `module.ping`。
>   - 0.1.2 曾把两者合并、用「已写入 WorkBuddy 会话（引擎）」造假成功，用户真机实测「WorkBuddy 里完全没有」→ 已推翻。`WbSender`（App 进程内后台 `startActivity`）确实被 Android 16 拦，但**root shell 的 `am start` 不受限**，这是两回事。
> - **WorkBuddy 通道前置**：KernelSU 里给 `com.watchctl.hook` 授予 root（否则 `wb.prompt` 回 `wb-no-root`）。这与 LSPosed 激活/作用域无关。
> - `HookMain` 的框架放行 hook（`enabled` / `frameworkScope`）只是**可选的后台拉界面开关，与指令链路无关**。
> - 下方「两轮真机 probe / L1.5 加固层 / 文本注入策略 / `/result` 回灌」等均为**早期方案留档**；但其中「`.ShareAddToChat` 预填文本」的 A1 结论 0.1.3 已复用（改由模块 root shell 直接 `am start`）。

> ⚠ **【历史留档】2026-11-14 两轮真机结果（见 [方案.md §4.10](../方案.md)）**：
> - **第一轮**：`resolve-activity` 落到系统 `ResolverActivity`、`assets/flutter_assets/` 实证 Flutter → 当时判定 `byIntent(text/plain)` 与 View 树注入都不可用。
> - **第二轮更正**：完整 dumpsys 证明 `.ShareAddToChat` 等 4 个组件都声明了 `SEND + text/plain`，第一轮的 `ResolverActivity` 是「多候选」导致，`-n` 指定组件即直达。**`intent` 主路复活**（已在 relay `config` 预填 `sendComponent`）。
> - 因此对 **WorkBuddy 的文本入口**，本模块已不再是必需的——它用 `-n` intent 即可，无需 LSPosed。**本模块退化为可选增强件**：只剩「进程内收广播」（在你想绕开系统选择器/后台限制时）与「通知结果结构化回灌 `/result`」两半价值。
> - **前述结论已被 0.1.2 取代**：`ACTION_SEND` 从后台广播线程发起会被 Android 16 静默拦截，现改为引擎 `session/prompt`。

凡是我不能确定的内部实现都留了接口和注释，没有硬编码猜测。

---

## 1. 它到底能解决什么（先把边界说死）

Xposed/LSPosed 的机制是往 **ART（Java/Kotlin）** 里注入 hook。所以能力边界非常明确：

| 层 | 技术栈 | LSPosed 能否 hook | 结论 |
| --- | --- | --- | --- |
| Android framework / system_server | Java | ✅ | 可放行 AOSP 后台启动限制 |
| 小米运动健康 / SystemUI | Java/Kotlin | ✅ | 可拦截媒体键、保活 BLE、读通知 |
| WorkBuddy 的 Java 壳、通知、Intent、四大组件 | Java | ✅ | 可静默收指令、读结果通知 |
| **WorkBuddy 会话 UI（Android 端实证 Flutter）** | Dart VM | ❌ | **hook 不到**；View 树失效，只能走通知 / 标准无障碍服务 |
| **dsh-go 的 UI 与 `dsh_client`** | Flutter/Dart | ❌ | **完全帮不上** |
| **dsh 引擎 `dsh web`** | Node.js（PRoot 内） | ❌ | 完全帮不上 |

> **关于 Flutter 的证据（第一轮真机已实证）**：官方 iOS 包（`com.tencent.workbuddy.app` v2.9.0）内嵌 `Flutter.framework` / `libapp`；**K90 Max 第一轮 probe 在 Android apk 内检出 `assets/flutter_assets/…`**，不再是推断。因此 `Ingress` 的 `auto` / `accessibility`（View 树找 `EditText`）**确定失效**——这部分判断不变。但 **`intent` 通道在第二轮被完整 dumpsys 复活**（详见 [方案 §4.10](../方案.md)）：`-n` 指定 `.ShareAddToChat` 即为 `SEND + text/plain` 直达。`scripts/probe.sh` 第 6c 段负责 Flutter 检测，第 6b 段导出完整 dumpsys 判定 `text/plain`。
>
> **另一条兜底**：WorkBuddy 官方支持企业微信 / QQ / 飞书 / 钉钉移动端远程下发指令（OpenClaw 技能体系）。若 LSPosed 注入始终不稳，可把手表指令先送到这些 IM 通道，绕开会话 UI 注入这一难题——代价是异步、非实时。

**一句话（按 0.1.3 现状修订）**：本模块现在是**指令主中继**——静态 `CommandReceiver` 收广播，两条通道分流：dsh-go 半侧走 `127.0.0.1:3080` HTTP/RPC（`DshClient`），WorkBuddy 半侧走 **root `am start .ShareAddToChat` 预填**（`WbIngress`，复用早期 A1 真机验收的入口，但改由模块 root shell 直接发、不经 Node relay）。LSPosed 框架 hook（`HookMain`）已退化为**可选**的后台拉界面放行开关，与指令链路无关；真正的前置是 KernelSU 给模块 root 授权。

三个痛点里，**只有第 2 条仍然成立**：

1. ~~免选择器、后台可发（作为文本注入入口）~~ → 这一跳已由 relay 的 `-n` intent 通道解决（见 §4.10），不再需要本模块。本模块的广播通道如今只是「绕开系统选择器/后台限制」的**可选**替代，而非必需。
2. **结果结构化回读（仍成立）**：hook WorkBuddy 发出的 Notification，把文本 POST 到 relay `/result`，走 SSE → companion → 表端，而不是只靠「通知被小米运动健康镜像」这一条兜底。通知是 Java 层，不受 Flutter 影响。
3. **框架层放行（可选，仍可用）**：hook `ActivityStarter.shouldAbortBackgroundActivityStart` 对白名单放行，补上 AOSP 一层。但 **HyperOS 3 的「自启动/关联启动/后台弹出界面」在 `com.miui.server` 里、各版本不同，本模块刻意不 hook**（风险 >> 收益），那部分仍按 [方案 §4.6](../方案.md) 手动放行或用 `CompanionDeviceManager` 关联拿豁免。

> 结论：**L1.5 如今只是可选增强件**。若目标是「手表发指令给 WorkBuddy」，主路是 relay 的 `-n` intent 通道（[方案 §4.10](../方案.md)）；本模块仅当你想要「进程内广播投递」或「结构化结果回表」时才值得编译，否则不必装。

> 保持不变的安全边界：不改 `@deepseek-ai/*`、不替换提示词、不绕过审批与沙箱。模块只是把用户自己从手表发出的文本送进 App、把结果读回来。

---

## 2. 与 relay 的协议契约

relay 侧（`config.json`）：

```json
"workbuddy": {
  "packageName": "com.tencent.workbuddy.app",
  "mode": "broadcast",
  "broadcast": {
    "action": "com.watchctl.SEND",
    "receiver": "",
    "token": "<≥8 字符随机串>"
  }
}
```

`packageName` 与 LSPosed 作用域（`arrays.xml`）、模块设置页三处**必须都是 `com.tencent.workbuddy.app`**（K90 Max 实测的腾讯官方包），已全部预置。注意别和 `com.example.watchctl`（自写 companion / 表端 rpk 包名）混淆。

- relay 执行 `am broadcast -a <action> -p com.tencent.workbuddy.app --es watchctl_token <token> --es watchctl_text <指令> --es watchctl_id <uuid>`（全部数组参数，不拼 shell）。`receiver` 留空即用 `-p` 限定包名；只有确认了 WorkBuddy 内某个静态导出 Receiver 才需要填 `com.tencent.workbuddy.app/<类>`。
- 模块 **校验 token 相等**才处理，防止任何第三方 App 借通道发消息。
- 模块拿到结果后 `POST http://127.0.0.1:8787/result`，头 `X-Hook-Token: <同一个 token>`，body `{text, source, id?}`；relay 校验后推进 SSE `/events`。
- `mode` 默认 `intent`（零依赖，不需要本模块）；**只在装好并启用模块后才切到 `broadcast`**。两个 token 是同一个值，在模块设置页和 `config.json` 各填一次。

`probe.sh`（第 2 版）第 6a 段对多种 MIME 逐一 `resolve-activity` 并区分「真组件 / 系统 `ResolverActivity` / 无匹配」（**第一轮就是在这里发现 `text/plain` 解析到系统选择器**），6b 导出完整 dumpsys、检查 `text/plain` 与 deeplink，6c 检查 Flutter 引擎，6d 读无障碍服务现状；第 8 段判 3080 是否监听；第 9 段确认 ZygiskNext / LSPosed 真注入。

---

## 3. 部署步骤（需真机）

1. **编译**：`cd lsposed`，用 Android Studio / `./gradlew assembleDebug` 生成 `app-debug.apk`（需 Android SDK 34、网络能取到 `de.robv.android.xposed:api:82`）。
2. **安装**模块 APK，打开「腕控 · WorkBuddy 桥」设置页：
   - WorkBuddy 包名 **已预填 `com.tencent.workbuddy.app`**，无需再查（留空也会自动回落默认值）
   - action `com.watchctl.SEND`
   - token（与 relay `config.json` 完全一致，≥8 字符）
   - 结果回灌地址（默认 `http://127.0.0.1:8787/result`）
3. **LSPosed 管理器** → 模块 → 启用 → **作用域勾选 `com.tencent.workbuddy.app`**（`arrays.xml` 已预置为推荐项；`android` 系统框架仅在确需框架放行时勾）→ **强制停止 WorkBuddy / 重启手机**。
4. ~~定位注入点~~ **（第二轮真机后已取消，别再做）**：WorkBuddy 的文本入口已由 relay `-n` intent 通道解决，本模块无需再找 hook 点。`Ingress.java` 的四策略（`auto` / `intent` / `accessibility` / `reflect`）仅作历史留档——其中 View 树策略因 Flutter 确定失效，`reflect` 需闭源逆向；这些都**不是**推荐路径。若确实需要「送文本进 WorkBuddy」，用 relay 的 intent 通道（[方案 §4.10](../方案.md)），不要在模块里找 hook 点。
5. **回读验证**：`curl -N http://127.0.0.1:8787/events`（带 companion 的 `X-Relay-Token`），在 WorkBuddy 里产生一条结果，应看到 `type: result` 帧；再让模块 POST `/result` 验证 `X-Hook-Token` 链路。
6. relay 切到 `mode: broadcast`，重启中继，从表端发 `wb.prompt` 验证闭环。

---

## 4. 文件清单

| 文件 | 作用 |
| --- | --- |
| `app/src/main/assets/xposed_init` | 模块入口类声明 `com.watchctl.hook.HookMain` |
| `.../HookMain.java` | 入口：装 Activity 跟踪、注册广播 receiver、（可选）框架放行 hook |
| `.../Config.java` | `XSharedPreferences` 配置读取 |
| `.../Ingress.java` | 指令注入：auto / intent / accessibility / reflect 四策略 |
| `.../CurrentActivity.java` | hook `Activity.onResume` 记录前台 Activity |
| `.../ResultEgress.java` | hook 通知 → POST `/result` 结构化回读 |
| `.../SettingsActivity.java` | 极简设置页（写配置） |
| `AndroidManifest.xml` / `arrays.xml` | Xposed 模块声明、作用域、设置页 |
| `build.gradle` / `settings.gradle` | 编译配置（Xposed API 仅 compileOnly） |

---

## 5. 风险与已知不确定

| 项 | 说明 |
| --- | --- |
| **文本注入不再是本模块职责（第二轮真机）** | `ACTION_SEND` 经完整 dumpsys 实证有 4 个 `SEND + text/plain` 组件，`-n` intent 直达由 relay 解决；本模块的四策略 ingress 仅留档，View 树策略因 Flutter 仍失效 |
| WorkBuddy 内部实现未知 | 闭源；App 更新后可能失效 |
| **Flutter 会话 UI（Android 端实证）** | Dart 层 hook 不到；要读语义树须用标准 `AccessibilityService`，不是 LSPosed |
| 包名填错 | LSPosed 作用域 / 模块设置页 / relay 三处必须都是 `com.tencent.workbuddy.app`；填成 companion 包名会静默失效 |
| `sendComponent` 填成选择器 | 第一轮 `resolve-activity` 返回 `com.android.intentresolver/.ResolverActivity`，**绝不能填**；`probe.sh` 6a 已能自动识别 |
| 框架 hook 写错会 bootloop | `frameworkScope` / `allowAllBackgroundStarts` 默认**全关**；KernelSU 与 LSPosed 都有安全模式可救援 |
| 全局放行危险 | `allowAllBackgroundStarts=true` 等于关闭 AOSP 后台启动限制，仅为调试，勿长期开 |
| 动态 receiver 的隐式广播限制 | **0.1.1 已修**：改为 Manifest 静态注册 `CommandReceiver`（`exported=true`），companion 侧 `setClassName` 显式广播 + `FLAG_INCLUDE_STOPPED_PACKAGES`，不再依赖 `RelayService` 常驻 |
| token 泄露 | 只在本机 loopback 与模块私有 SharedPreferences，不外发；不要填进日志 |

## 6. 0.1.1：静态接收器改造（修「连接成功但发指令超时」）

旧版 `CmdReceiver` 动态注册在 `RelayService.onCreate()` 里，服务靠 `startForegroundService()` 拉起。
在 **Android 12+ / HyperOS 3(Android 16)** 上后台启动 FGS 受限、模块进程又常被省电冻结 → 动态接收器
不存在 → `com.watchctl.CMD` 广播无人接收 → 表端发指令 8s「超时，手机没回应」。而 ping/pong 是
companion 本地直回的，所以「已连接」完全掩盖了这个断点。

| 文件 | 0.1.1 变化 |
| --- | --- |
| `CommandReceiver.java`（新） | Manifest 静态注册、`exported=true`，`goAsync()` + 线程池，监听 `CMD`/`SET` |
| `CommandProcessor.java`（新） | 指令处理逻辑（含 `module.ping` 在线探测、token 校验、结果回写显式定向 companion） |
| `RelayService.java` | 降级为纯前台保活，**去掉动态接收器**，避免一条指令被处理两次；三参 `startForeground` |
| `AndroidManifest.xml` | 静态 `<receiver>`、`<queries>`（companion / WorkBuddy）、`usesCleartextTraffic`（dsh-go 明文 HTTP）、`FOREGROUND_SERVICE_DATA_SYNC` |
| `Config.java` | 加 `COMPANION_PACKAGE` 常量，结果广播 `setPackage` 定向 |

**要点**：静态接收器是普通 Android 组件，**与 LSPosed 是否激活无关**——显式广播即可唤醒被回收的进程。
LSPosed 作用域只影响「从后台拉起 WorkBuddy」的放行 hook（默认关）。因此模块在线探测
（`cmd=module.ping`）在模块未注入时也能通过，可用来区分「模块没收到广播」与「WorkBuddy 拉起失败」。
（历史留档，0.1.8 起已废弃）当时建议给「腕控·主中继」开自启动/后台无限制，并至少打开过一次模块 App（避免处于 force-stop 状态）。**现在完全不需要**：指令引擎已内置在 companion 单 APK 内、本地直调，没有第二个进程要保活。
