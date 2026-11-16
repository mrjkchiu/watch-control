# 腕控：REDMI Watch 6 实时控制 dsh-go 与 WorkBuddy

设备组合：**REDMI Watch 6（Vela / HyperOS）** + **REDMI K90 Max（HyperOS 3，KernelSU root，已装 ZygiskNext + LSPosed）** + 手机上的 **dsh-go** 与 **WorkBuddy**。

> **WorkBuddy 包名（实测确认）：`com.tencent.workbuddy.app`**（腾讯官方包）。已预置进 `relay/config.example.json`、`relay/src/config.js` 默认值、LSPosed 作用域、模块设置页与 `probe.sh`，无需再查包名，跑 `probe.sh` 只需补 cookie / sendComponent / token。
> 工程里的 `com.example.watchctl` 是**你自写 companion + 表端 rpk** 的包名（interconnect 要求两者同源同签名），**不是** WorkBuddy 包名，别混淆。
> **真机 probe 已完成两轮（2026-11-14），务必先读 [方案.md §4.10](./方案.md)（最新）**：
> - **第一轮**：dsh-go 引擎当时没启动（3080 未监听）→ 当前第一阻塞项；`resolve-activity` 落到系统 `ResolverActivity`；apk 检出 `assets/flutter_assets/` → Flutter 实证。
> - **第二轮（更正）**：完整 dumpsys 证明 `.ShareAddToChat` / `.MainActivity` / `.SharePreviewFile` / `.ShareSaveToLibrary` **4 个组件都声明了 `SEND + text/plain`**，第一轮的 `ResolverActivity` 是「多候选」导致，`-n` 指定组件即直达。**`intent` 主路复活**，`config.example.json` 已预填 `sendComponent=com.tencent.workbuddy.app/.ShareAddToChat`，待 `scripts/wb-ingress-test.sh` 验收落地行为。
> - 好消息：`lspd` + `zn-zygisk-companion64` 在跑，ZygiskNext/LSPosed 是真注入；dsh-go 包名 `io.github.ccch1mneyyy.dsh_app` 已拿到。
> - **dsh-go 半侧与结果回表不受影响**；WorkBuddy 文本入口从「没有路」变为「主路已铺好待验收」，仍保留了官方 OpenSDK、无障碍补点、官方 IM 远程指令等备选通道。

详细推理、协议与风险见 [方案.md](./方案.md)。

> **⚠️ 当前实现（2026-11-15 更新）：Node relay / HTTP 8787 架构已废弃；0.1.8 起手机端进一步合并为【单 APK】**。早期「companion → 显式广播 → 独立 LSPosed 模块」链路已退役：指令处理类已并入 companion，改为**本应用内本地直调**。下方「阶段 0/1、wb-ingress-test、ACTION_SEND 调研、relay/ 章节、L1.5 加固层」以及所有提到「LSPosed 模块主中继 / 三端 / 装 module APK / 勾作用域」的描述均为**早期方案留档**，以本块与 `watch-rpk/` `companion/` **两端**源码为准。
> 交付目录恒 **2 个文件**：`watch-0.1.26.rpk` / `companion-0.1.21.apk`（同包名同签名 `8637ca4e…d12c`，见 `/workspace/交付/`）。独立的 `module-*.apk` **不再交付**。
>
> **✅ 第二十三次修正（companion 0.1.21/code22，2026-11-16）——手机端加 DSH 凭据手动入口 + 运行日志人话化**：用户要求给手机端加 token/cookie 手动输入/清除入口（用途：测试自愈 + 自动抓不到时手填），并把运行日志里无用信息去掉、技术黑话润色成常人能懂的话，UI 走同一套。**只动显示层 + 一个写入入口，链路 / 广播协议 / 指令引擎 / dsh 自愈一行未改**：① `activity_main.xml` 新增「DSH 凭据设置」折叠面板（Token/Cookie 输入框 + 保存 / 清除 Token / 清除 Cookie），`MainActivity` 收起为默认、点开才显示；② 保存/清除复用现有 `COMPANION_SET` 通道写本应用 SP（在 `:relay` 进程内写、下条指令即生效），`CompanionService` 增 `dsh_cookie` 写入分支 + 状态即时回报；③ 状态只读「是否配置」绝不显示明文；④ 清除=写空值，下次发指令触发自愈重取（测试用）；⑤ 运行日志新增「人话 / 原文」切换，默认人话——`MainActivity.humanize()` 把快照行/nodeId/原始 JSON 帧等纯噪声隐藏、`link=online` 之类翻成人话短句、出错才醒目，原始行仍留在内存供 parseState 读数与「复制日志」排障；⑥ `DshClient` 把自愈关键步骤以人话投到界面（方便真机验证自愈，无明文）。表端 rpk **0.1.26 未改、无需重装**；手机端需重装，root 授权对象仍是 `com.example.watchctl`、无需重授。效果预览见 `/workspace/companion-ui-preview.html`。
>
> **✅ 第二十二次修正（companion 0.1.20/code21 + rpk 0.1.26/code27，2026-11-16）——全系统代码审查后的健壮性/安全专项**：用户要求全系统代码/资料/排版审查后优化。经审查并逐条复核，修 5 项，均不动链路/帧协议/签名：① **P1** `State.java` 静态 `SimpleDateFormat` 非线程安全（多线程并发 format 抛异常被吞→静默丢状态行），改 `ThreadLocal`；② **P1** `bridge.js` `markConnected` 无 `running` 守卫（`close()` 后残留回执把 UI 假复活成"已连接"却发不出指令），加守卫；③ **P2** `DshSelfHeal.su()` 超时只杀 su 本体、留下 capture 起的引擎占 3080，加 `killTree` 递归清理；④ **P2** `scanTokenFromMemory` 逐候选验证无总预算（最坏 1000s 阻塞），加 60s 上限；⑤ **P2** `XmsInterconnectLink` 原始帧正文（含 prompt 文本）明文进状态行/日志→被"复制日志"带走，加 `redact()` 脱敏（正文替为 `«N字»`，仅影响日志不动帧处理）。两端都重编、都要重装。
>
> **✅ 第二十一次修正（companion 0.1.14/code15，2026-11-16）——真正根因：companion 从没持久化 cookie，只存了易变的 launch token**。第二十次的「只读 WebView cookie 自愈」真机仍 401，查明两处硬伤：① WebView cookie 在 SQLite 里 name/value **分列**存储，旧正则 `dsh-auth-xxx=v1.xxx` 连写匹配不到；② dsh-go 的 logcat/磁盘日志里根本没有明文 token（只进进程内存，磁盘被 `LogRedactor` 完整脱敏），跨进程读不到新 token。查 `packages/dsh_client/lib/src/connection.dart` 源码原文确认官方契约：**cookie 签名密钥固定在 `$DSH_HOME/.credentials.yaml`，跨 `dsh web` 重启有效 30 天；官方要求「持久化 cookie（bearer credential），只在 401 时重登」**。据此重构：`Config` 新增 `dsh_cookie` 持久化字段；`DshClient` 优先用持久化 cookie 探活（200 即用，覆盖绝大多数 dsh-go 更新场景，无需 token），401 才自愈；`DshSelfHeal` 两级取凭据——先用修正后的正则读 WebView cookie（零中断），读不到再用内置 `assets/dsh_capture.sh` 以 root 自起一个 dsh web 引擎抓明文 token 换 cookie（保底，用户已知悉短暂引擎重启）。拿到 cookie 立即落盘，30 天窗口内 dsh-go 再更新也不用管。签名同源不变，手表端 rpk 无需改动。
>
> **⚠️ 第二十次修正（companion 0.1.13/code14，已被上面的第二十一次取代，留档）——**：用户真机反馈输入法已能用，但 dsh-go 更新/重装后手表提示「登录失败」、手机端没反应。查证 dsh-go 源码确认根因：**launch token 每次引擎进程启动都重新随机生成**（`dsh web: http://127.0.0.1:3080/?token=` 只打印一次，磁盘日志被 `LogRedactor` 完整脱敏、跨进程读不到），所以 companion 里存的旧 token 在 dsh-go 重启后立即 401。修复：`DshClient.login()` 登录前先用 root **只读** dsh-go 的 WebView cookie jar（`dsh-auth-` cookie 绑定 `127.0.0.1:3080`、30 天有效，是真正的长期凭据）或残留 token，拿到 cookie 直接注入会话、拿到新 token 走标准登录；401/403 重登也走自愈，不再被 `token 非空` 条件挡住。全程不重启引擎、不打断 dsh-go、不改任何 dsh-go 状态，纯只读。签名同源不变，手表端 rpk 无需改动。
>
> **✅ 第十九次修正（rpk 0.1.24/code25，2026-11-15）——真正根因：一直交付的是明文 debug 包，真机引擎只认 `.jsc` 字节码**。此前 0.1.20~0.1.23 连改输入页拆分、权限收敛、体积精简都没用，因为方向错了。这次拿到用户提供的真机崩溃日志 + 能正常使用的「腕记」rpk 逐字节对比，结论确定：
> - **崩溃链路**（真机日志实测）：`executeInstall ... install finished`（安装其实成功了）→ AstroBox 安装流程末尾自动拉起应用 → `[AIOTJS] load app.jsc.` → 我们包里只有明文 `.js`，引擎报 `init custom app inst error. TypeError: not a function` / `aiot.getGlobalStyle call failed` / `cannot read property 'style' of undefined` → `Abort App` → `arm_memfault: PANIC!!! Memory Management Fault` → **整机 REBOOT** → 安装流程中断，AstroBox 显示「安装失败」。所以用户看到的是「**安装过程中手环就重启、没装进去**」。
> - **为什么腕记可以**：腕记 786.8KB 比我们的 350KB 大得多却能装能用，因为它 9 个代码产物全是 **`.jsc` 预编译字节码**（`app.jsc` 等），引擎直接加载、无需现场编译；我们从 0.1.1 起一直用 `aiot build` 出的是 **DEVELOPMENT 明文 `.js` debug 包**（输入页单文件 565KB 未压缩），真机 AIOTJS 引擎（QuickJS `malloc limit` 仅 8MB）不提供降级兜底，明文编译即把框架带崩。体积从来不是主因。
> - **修复**：`build.sh` 永久改用 **`aiot release --enable-jsc`**（PRODUCTION + 字节码编译），产物只有 `app.jsc` / `pages/index/index.jsc` / `pages/input/index.jsc`，无任何 `.js`；并加了打包后强制校验，一旦残留明文 `.js` 直接拒绝交付。同时移除腕记没有的 `simulationVersion` 字段。`designWidth: 480` 保持不变（已确认的 UI 基线，改成腕记的 212 会打乱全部按钮尺寸）。release 与 debug 及 companion 是同一张证书，互联签名校验不受影响。
> - **验证**：用小米官方 Vela 模拟器（`/root/.vela/sdk`，NuttX 12.3.0 arm miwear，headless `-no-window`）实测三组对照——腕记(jsc)：安装成功、启动到前台、`Failed to open app.jsc` = 0；旧 debug 包：`Failed to open ... app.jsc` = 1（靠 `isJsRpk=1` 兜底，模拟器引擎比真机固件新，有兜底所以不复现 reboot）；**最终交付包 0.1.24：安装成功、注册为「腕控 0.1.24」、`app created` / `Start App loop` / 页面渲染 19 条样式 / 到前台、崩溃标志 0、`app.jsc` 加载失败 0**。
> - **只重装表端**：手机端 `companion-0.1.12.apk` 未改，签名同源不变。
>
> **🔧 第十八次修正（rpk 0.1.23/code24，2026-11-15）——拼音输入法拆到独立页面，主应用回到能装的轻量结构**（方向有误，真正根因见第十九次）：0.1.20~0.1.22 把 623KB 输入法引擎 + 118 个资源全内联进唯一主页面，并多声明了 `system.vibrator`/`system.device` 两个越权接口，真机连续三版「装上就重启」。查证米坛能跑的同款应用（DaysMatter）与组件官方 README 后，对齐正确集成方式：① 输入法移到独立页 `pages/input`，主页面 `index.js` 从 623KB 降到 **83KB**（比能装的 0.1.19 的 116KB 还轻），主应用启动不再加载输入法；② manifest 权限收敛为官方要求的 `router`/`interconnect`/`file` 三项，包内彻底清除两个越权系统模块；③ 草稿经 `app.ux` 的 `$def.shared` 跨页共享，发送仍走主页面已建立的 bridge。编译产物已核验：主页面无输入法引擎、无越权模块。**即使输入页在某固件上不兼容，主应用也能正常打开、预设指令照常可发**（这是前三版做不到的兜底）。**本次只重装表端**；手机端 `companion-0.1.12.apk` 未改，签名同源不变。
>
> **🐛 第十七次修正（rpk 0.1.22/code23，2026-11-15）——修复拼音版真机「装上就重启」（未奏效，见第十八次）**：0.1.20 集成拼音组件时静态 `import` 了 `@system.vibrator` / `@system.device` / `@system.file`，编译器将其提升为模块顶层 `require`；REDMI Watch 6 三方 rpk 环境若缺其中任一原生模块，页面脚本在加载阶段即抛异常 → 应用启动闪退重启。0.1.21 仅改按钮样式、未触及此隐患，故首次真机装机（0.1.20/0.1.21 均未装过）暴露该问题。修复：三处静态 import 全部改为**惰性 + try/catch 动态 require**（固件有则用、无则降级为空实现/空候选），与 `bridge.js` 加载 `@system.interconnect` 的既有稳健范式一致；拼音功能在支持的设备上完全不变。manifest 仍保留 3 个 feature 声明（支持时启用）。**本次只重装表端**：手机端 `companion-0.1.12.apk` 未改，签名同源不变。
>
> **🔘 第十六次修正（rpk 0.1.21/code22，2026-11-15）——表端按钮放大 + 增加间距**：用户真机反馈「按钮有点小不好按、按钮之间要有空隙」。把 `index.ux` 全体按钮尺寸统一调大——高度 +8~12px（主发送键 54→64、连接键 52→62、工具键 44→54、次键 38→50）、字号 +2~3px、按钮间距 `margin-top` +4~6px；圆角/色板/居中设计语言与全部逻辑（互联状态机、两条指令通道、输入法回调）一字未动。**本次只重装表端**：手机端 `companion-0.1.12.apk` 未改，签名同源 `8637ca4e…d12c` 不变、root 授权不变。
>
> **⌨️ 第十五次修正（rpk 0.1.20/code21，2026-11-15）——表端自定义输入改用开源中文拼音输入法**：用户真机反馈 0.1.19 的「8 键英文翻页键盘」只能英文、要翻页找字母、严重拖慢输入。查证米坛社区（同款 REDMI Watch 5/6 的 DaysMatter 已内置 26 键拼音）与 GitHub 开源组件后，集成 MIT 许可的 **`NEORUAA/Vela_input_method`**（原样引入 `watch-rpk/src/components/InputMethod/`，仅新增 LICENSE 保留版权）。圆屏 QWERTY 全键盘支持**中文拼音整词 + 候选选字、英文、数字标点**；经 `complete`/`delete` 事件把选中文字写入草稿，仍走 dsh/WB 两条独立通道如实回执。**本次只重装表端**：手机端 `companion-0.1.12.apk` 未改。
> - **调研出处**：AstroBox / 米坛是安装与资源社区（非输入法本身）；关键线索来自米坛 [DaysMatter 倒数日快应用](https://www.bandbbs.cn/threads/16010/post-1033422)（REDMI Watch 5/6 已跑通 26 键拼音）与开源组件 [`NEORUAA/Vela_input_method`](https://github.com/NEORUAA/Vela_input_method)（MIT，2026-11 仍更新）。这纠正了此前「手表三方 rpk 无法中文输入」的误判——系统输入法确实不向三方开放，但**应用内自绘输入法组件**可行。
> - **集成范围**：`manifest.json` 增声明 `system.file`/`system.vibrator`/`system.device`；组件整目录原样纳入（118 文件 + LICENSE，含全部词库/图片，不精简以免真机缺资源）；旧词块 + 8 键键盘代码全删。rpk 由 ~20KB 增至 ~348KB（含词库与键盘图）。
> - **需真机确认**：圆屏键盘固定 480×321，键盘弹出后页面总高可能超出 480 圆屏——组件作者同款示例（480×480 页面）可正常显示，但本应用元素更多，滚动/按键可达性需真机验；词库读自我的 `system.file`（无网络依赖）。
>
> **🛡️ 第十四次修正（rpk 0.1.19/code20 + companion 0.1.12/code13，2026-11-15）——dev-expert 复审后的健壮性 / 安全专项**：用户要求「再看看两个软件还有没有什么可优化的地方」，先由通用审查出 A 组 7 项，再调用 `@skill:dev-expert` 复审补出 6 项漏报（含 3 个安全问题），用户「确认」后一次性落地。**本轮两端都重编、都要重装**；链路本身无「打不开/连不上/发不出」级阻断问题，改的全是健壮性 / 安全 / 信息可读性 / 仓库卫生。
> - **安全（第一优先）**：① `CompanionService` 调试广播后门收紧——`TEST_FRAME` 只放行只读命令（`status`/`dsh.list`/`module.ping`/`ping`），`COMPANION_SET` 与 `set`/`SET` 帧一律禁止写 `auth_token`（保留 adb 设 `dsh_token` 排障）。此前 `RECEIVER_EXPORTED` + `ModuleBridge.base()` 自动补本 App 自有 token，等于同机任意 App 发一条广播就能绕过校验执行 `wb.prompt`(root am start)/改写 token；② **token 脱敏**：删掉「指令引擎已就绪 token=明文」状态行与 `token-mismatch` 回执里的 `cfg.authToken` 明文（`copyLog` 会一键复制全部日志发去排障）。
> - **删假功能 `dsh.open`**：表端按钮 / 方法 / 分组、手机端 `CommandProcessor` case 与幂等名单、`CompanionService.summarize`、命令集表与链路图**一并删除**。`dshgo://` scheme 与 `setPackage(WorkBuddy 包名)` 错配、真机必然拉不起来 —— 去掉「看起来能用实际不能用」，正好接上第十三次修正末尾「下一版去掉即可」；**命令集里的「page 走固定白名单」表述同步删除**（实现里从未有白名单，只有判空，属文档与实现不符）。
> - **表端 `bridge.js`（纯逻辑，帧协议/状态机结构未动）**：A1 异步 send 失败入队补发时撤销该帧超时裁决，消除「先 ✗ 超时、连上后补发又 ✓ 成功」抖动；A2 同步抛异常不再双重入队；A6 `diagnose()` 由 12+ 字段塞单行精简为两行各 3 项；W4 队列满静默丢单改为明确提示；**W1 超时预算对齐**——慢命令(`wb.prompt` 2 次 su / `dsh.prompt` 多次 HTTP)预算 12s→30s，覆盖手机端最坏执行时间，避免表端在手机仍执行时提前宣布超时。
> - **表端 `index.ux`**：草稿限长 200 字（对齐手机端 `WbIngress` 500 截断点，保证任何通道都不触发静默截断）并在草稿行显示总字数，去掉「核对后点发送」这类看不全全文却暗示能核对的误导文案；删 `dsh.open` 按钮；超时提示改「手机端可能仍在执行，先看面板再决定是否重发」。
> - **手机端健壮性**：A4/W6 `onDestroy` 的 `unregisterReceiver` 加 try/catch、`COMPANION_SET`/`set` 分支 `bridge` 空保护；W3 `LocalTestLink.state()` 恒返回 `offline`（此前 XMS SDK 初始化失败回退它却显示「已就绪/已连接」的**假绿**）；A3 版本兜底与布局占位不再硬编码 `0.1.11`（读不到显示「版本未知」）；A5 日志仅在用户原本贴底时才自动滚动（不再打断往上翻历史）。
> - **仓库卫生**：`package.json` 描述与 `build.sh` 签名注释里「LSPosed 中继 / 与 LSPosed 模块共享证书」过时文案改为单 APK 表述。
> - **明确不动**：W2(`DshClient` cookie 静态化)、`anyBad` 不复位、废弃留档目录(`lsposed/`/`wb-a11y/`/`scripts/ksu-module/`/`relay/`)、自动连接/自动发送、手机端输入或语音、签名 / 帧协议 / `@system.interconnect` 单例逻辑、两条通道合并 —— 前十几轮踩过的坑，不动最稳。
> - **⚠️ 两端都要重装（第十四次当时；第十五次只需重装表端，见顶部）**：`watch-0.1.19.rpk` + `companion-0.1.12.apk`（文件名以 `/workspace/交付/` 为准）；root 授权对象仍是 `com.example.watchctl`、无需重授，无录音权限。
>
> **🎛️ 第十三次修正（rpk 0.1.18 / code19，2026-11-15）——「更多工具」区补齐主界面设计语言**：用户问「更多工具里都是什么功能、干什么用的，且里面并没有保持主界面的美化设计语言」。
> - **先把 6 个工具查实**（对齐手机端 `CommandProcessor.dispatch` 与 `bridge.js`，都有真实实现、不是摆设）：「查看连接诊断」= 本地采集互联能力 / 失败码 / 心跳·重连·队列统计写进状态行，**不发帧**；「断开连接 · 可再连」= 本地停表端状态机省电，**不发帧**；「查看引擎状态」= 发 `status`，手机查 dsh-go 在线 / 会话数 / token，回 `DSH:在线 会话:N`；「列出会话」= 发 `dsh.list`，回 `会话数:N`；「! 中断当前会话」= 发 `dsh.cancel` → `session/cancel`（有副作用，在幂等去重名单里）；「! 打开引擎页 · 需手机确认」= 发 `dsh.open` → 手机 `am start dshgo://open/engine`。
> - **如实说明一处历史遗留**：`dsh.open` 把 `dshgo://` scheme 用 `setPackage(cfg.wbPackage)` 定向给 WorkBuddy 包名，scheme 与包名不匹配，手机端代码自己也注了「后台启动可能被系统拦截」——该按钮真机大概率拉不起来，属排障期遗留。**本轮保留但如实标注「需手机确认」，不冒充可用**；要彻底删掉下一版去掉即可。**（第十四次修正已按此删除 `dsh.open` 全链路，见上方「第十四次修正」）**。
> - **🎨 设计不一致的硬证据**：工具区 `.btn` 用的文字色 `#AAB8C2` **不在主界面色板内**（0.1.14「5 色杂配」没清干净的一处残留）；6 个按钮无分组、无 `.label` section 标题、全是两字缩写（「诊断／能力」还带斜杠），破坏性操作与只读查询长得一模一样 —— 正是「看不懂」的根因。
> - **改法（只改显示层）**：按「连接 · 本地操作 / dsh-go 引擎 · 查询 / 会影响状态 · 谨慎操作」分 3 组，每组一个 `.label`；按钮文案改自解释短句；只读项 `.btn-tool`（既有 `#172029` + `#E6EDF2`），有副作用项 `.btn-caution`（既有琥珀 `#E0A458` + `! ` 文字符号 + 分组标题三重编码）；删掉 `.btn` 与色板外杂色，尺寸 / 圆角 / 居中统一 44px / 10px / `align-self:center`。**6 个 onclick 方法、帧格式、两条指令通道、`bridge.js` 状态机一字未动**。
> - **一处文案同步**：`bridge.js` 里「点「诊断／能力」看链路健康」改名后指向失效，改为「查看连接诊断」；**只改 2 处用户可见提示 / 注释文案**，状态机 / 帧 / 回调 / 逻辑全部未动。
> - **⚠️ 本次只需重装表端**：`watch-0.1.18.rpk`。手机端仍是 `companion-0.1.11.apk`，**内容与上一版完全相同、无需重装、无需重新授权 root、无需录音权限**。
>
> **⌨️ 第十二次修正（companion 0.1.11 / rpk 0.1.16，2026-11-15）——自定义指令改为「表端内置输入面板」，主次摆正 + 居中修复**：用户指出上一版四个问题：① 手表端点「语音输入」真机提示「识别中断」；② 「在手机端输指令 → 回传手表 → 再从手表发回手机」是绕圈，「如果我不用手表语音发指令我干嘛不直接用手机呢，仔细思考下我们的目的是用手表端控制不要搞错主次」；③ 实测「手表端点击输入框并没有弹出输入法」；④ 表端按钮与按钮标题有的没居中。
> - **⚠️ 第十一次修正（手机端 ASR 架构）整体作废**：真机证明手机端 `SpeechRecognizer` 跑在 `:relay` 前台服务里被 ROM 拦成「识别中断」；更根本的是**主次反了**——目的是「手表作为发起端，独立完成输入 → 核对 → 发送」，手机只是被动执行指令的**被控端**，把识别与输入放到手机等于把主控权交回手机。Vela 三方 rpk 也**没有可向它弹出的系统输入法**（点 `<input>` 不弹键盘是必然的；官方 samples 的「输入法」是要拷进工程的**应用内组件**，不是系统能力），0.1.15 的草稿框连兜底都是假的。
> - **平台硬限制，如实不画饼**：Vela 4 三方 rpk 无 ASR/STT 接口、`@system.record` 多数小米穿戴不支持录音、小爱同学不向 rpk 开放转写；REDMI Watch 6 的「语音转文字」是**系统级**（微信回复、长按侧键），不进 rpk。**「表端纯语音转文字」在三方 rpk 里做不到**，本项目不再提供任何语音输入，也不再绕手机。
> - **表端 rpk 0.1.16/code17：内置输入面板，全程本地闭环**：`index.ux` 自定义模式改为「常用词块（3 页 × 6，点一下追加，中文靠词块组合）+ 字符键盘（6 组 × 8，字母/数字/标点，默认折叠、可翻组）+ 退格 / 清空」，草稿 `<text>` 行实时显示（超长截尾但发送用完整原文），核对后点「发到 DSH」「发到 WorkBuddy」。**只用已实证的机制**：静态按钮 + `value="{{字段}}"` 响应式绑定（编译产物已验证 `w0..w5`/`k0..k7` getter 全部生成，翻页能刷新）+ 纯方法名 `onclick` + 纯 boolean `if`，**不用 `for` / 不用事件传参 / 不用系统输入法** —— 真机确定能跑，零新增权限、零依赖、零手机参与。
> - **表端 `bridge.js` 回归纯指令状态机**：删除全部跨端语音帧、`voiceWait`、语音回调与 `requestVoice()`；`ping/pong/ready`、心跳重连、断线入队补发、指令回执分流**一字未动**。自定义草稿仍经原 `send()` 走既有两条独立通道，`dsh.prompt` / `wb.prompt` 帧格式与结果回执完全不变。
> - **手机端 companion 0.1.11/code12：删除 ASR 与回传输入区**：删掉 `VoiceAsr.java`、`RECORD_AUDIO` 权限、`RecognitionService` query、`CompanionService` 的语音分支与 `ACTION_VOICE_TEXT` 广播、`MainActivity` 折叠输入区与 `activity_main.xml` 的 compose 块、`bg_input.xml`。**手机端回到「纯状态面板」，只被动执行指令**；`CommandProcessor` / `ModuleBridge` / `XmsInterconnectLink` / `WbIngress` / 幂等去重全部未动。二进制 Manifest 与 dex 已验证语音残留为 0。
> - **🎨 居中修复 + 设计语言延续**：`.page` 加 `align-items: center`，所有按钮/读数加 `align-self: center` 双保险，按钮内文字 `text-align: center` —— 按钮本身与标题都水平居中（此前定宽 400px 元素在 448px 内容区里靠左）。字符键盘默认折叠属渐进式披露，新样式只取既有色板，无新色、无卡片、无阴影、不加常驻说明；符合 [.impeccable.md](./.impeccable.md)。
> - **⚠️ 两端都要重装**：`watch-0.1.16.rpk` + `companion-0.1.11.apk`（文件名以 `/workspace/交付/` 为准）。这次**不再需要授予录音权限**，也没有识别服务 / 后台麦克风的平台风险。
>
> **🎨 第十次修正（companion 0.1.9 / rpk 0.1.14，2026-11-15）——双端 UI 全面美化与去噪（impeccable）**：用户要求「调用界面 UI 设计 skill 全面美化与去除没必要的元素与内容」，并追加「手表端的顺便也改了」。skill 是 Web 前端方法论，但 `distill`（去噪）/ `typography` / `color` / `spatial` / `ux-writing` / `critique` 原则完整迁移到 Android 原生 Java UI 与 Vela `.ux`；**链路代码、服务、广播协议、帧格式一行未动**。设计上下文见 [.impeccable.md](./.impeccable.md)。
> - **手机端 companion 0.1.9/code10**：`res/` 从「完全为空」补齐为自有主题 `Theme.WatchCtl`（深色工业仪表）、tinted neutral 配色、自有矢量图标 `ic_watchctl`、XML 布局 `activity_main.xml`。首屏改为「当前链路大状态 + 5 行链路读数 + 运行日志」，24 行常驻说明书收进「使用说明与排障」折叠（默认收起）；按钮 `weight` 等分不再横向溢出；日志 ✓绿/✗红/警告琥珀语义着色（符号 + 颜色双重编码）、时间戳弱化、「全部/仅异常」筛选、新日志自动滚到最新；「复制日志」不再把整篇说明塞进剪贴板。**所有状态从 `State` 广播的真实文本行解析，绝不编造「已连接」**。
> - **表端 rpk 0.1.14/code15**：`index.ux` 从「11 个按钮平铺 + 4 行常驻文字 + 5 色杂配」重做为同一套深色仪表风——状态大读数带语义色、当前指令点击即切换（省掉一个按钮）、两个发送按钮为唯一主操作、7 个低频调试工具收进「更多工具」折叠；常驻说明书删除，运行时如实提示仅在有内容时显示。用 Vela 编译器原生 `if` 指令 + 纯 boolean 变量实现（不用动态 class/style，规避模板兼容风险），`bridge.js` 长期状态机与两条指令通道完全未动。
> - **⚠️ 与上一轮不同：这次两端都要重装**（表端界面已变，不再是「不用重装」）。先卸载手表旧「腕控」再装 `watch-0.1.14.rpk`，手机端覆盖装 `companion-0.1.9.apk`；KernelSU root 授权对象仍是 `com.example.watchctl`，**无需重新授权**。
> **⚠️ dsh-go 引擎 ≠ WorkBuddy（务必分清）**：dsh-go = `127.0.0.1:3080` 的 Node 引擎（`session/prompt` 写入即执行）；WorkBuddy = `com.tencent.workbuddy.app`，腾讯 Flutter App，无对外下发指令的 API。0.1.2 曾把两者混为一谈、把两条指令合并到引擎通道，用户真机实测「WorkBuddy 里完全没有」→ 是第 4 次假成功。**0.1.3 起两条通道彻底拆开、各自如实回执**：
> - **发到 DSH** (`dsh.prompt`) → 模块 `DshClient` → 引擎 `session/prompt` → dsh-go 会话（`delivered=true`，引擎明确返回 ok）。
> - **发到 WorkBuddy** (`wb.prompt`) → 模块 `WbIngress` → **root shell `am start ... -n com.tencent.workbuddy.app/.ShareAddToChat`**（前期 `scripts/wb-a1.sh` A1 真机验收入口；AOSP 豁免 ROOT/SHELL UID，不受 Android 16 后台启动限制）→ 文本**预填进 WorkBuddy 输入框**。`launched=true` 但 **`delivered` 恒 false / `stage=prefill`**，需在手机上点一次「发送」才执行——**回执绝不冒充「已送达/已执行」**。前置：KernelSU 给**本 App `com.example.watchctl`** 授权 root（0.1.8 单 APK 合并后已不再是 `com.watchctl.hook`）；`module.ping` 会自证 `wbRoot/wbInstalled/wbShareComponent`。
> 逐版真机排查与修复见 [companion/README.md](./companion/README.md) 与 [方案.md](./方案.md)。
>
> **📦 0.1.8 单 APK 合并（2026-11-15）——手机端只剩一个软件**：用户提出「为什么手机端是两个软件、还要分别启动」。查实拆分只是历史包袱：早期 WorkBuddy 靠 Xposed 进程内注入，LSPosed 模块按规范必须独立 APK；后来链路改成静态广播接收器 + root `am start`，该模块**早就不是 Xposed 模块**（10 个类里只有 `HookMain`/`Config` 碰 Xposed API，且与指令链路无关），拆两个已无技术必要性。本轮合并：
> - 把 `CommandProcessor` / `DshClient` / `WbIngress` / `RelayException` / `Config` 搬进 companion APK（保留 `com.watchctl.hook` Java 包名，Android 单 APK 可含多个包），删掉 `Config` 里唯一的 `XSharedPreferences.load()`，砍掉 `HookMain` / 保活 Service / 模块设置页。
> - `ModuleBridge` 从「发 `com.watchctl.CMD` 给另一个 APK + 等 `com.watchctl.RESULT` 广播」改为**线程池内本地直调** `CommandProcessor.handle(..., ResultSink)`，结果直接回调 `CompanionService.deliverToWatch()`。**跨进程广播这一整跳被删除**，也就不存在「模块被回收 / 广播没送达 / 模块=无响应 / 发指令超时」这类问题。
> - 两条指令通道、指令幂等去重、连接状态机、表端协议**全部保留不变**；表端 rpk 当时仍是 `0.1.13`、不用重装（**仅为 0.1.8 当时的结论**；0.1.14 起表端 UI 已重做，**需重装**，见上第十次修正）。
> - 配套：companion 升 `0.1.8/code9`，补 `INTERNET` 权限与 `usesCleartextTraffic`（引擎 `127.0.0.1:3080`）、WorkBuddy `<queries>`；状态面板「模块=」改为「指令引擎=」、「探测模块」改为「自检引擎」。
> - **唯一需要你做的一次性动作**：KernelSU 里把 root 授权对象从已废弃的 `com.watchctl.hook` 改成 **`com.example.watchctl`（腕控伴侣，本 App）**；旧的模块 APK 可卸载。装 1 个 APK、打开 1 次即可，不用勾 LSPosed 作用域。
>
> **🔗 连接稳定性专项（上一轮：rpk 0.1.13 / companion 0.1.7，2026-11-15；0.1.8 单 APK 合并已完整继承）**：用户确认「指令已能传到 WorkBuddy，但连接时好时坏、有时连不上有时又能连上」。诊断结论是**连接层状态机竞态，不是蓝牙通道问题，也不是指令送达问题**——旧表端是「一次性 60s 握手，连上后停 ping」，无心跳、无断线检测、掉线不自动重连；手机端纯被动等 ping，且 nodeId 变化不重挂监听。三端配套改为**长期自维护链路**：
> - **表端 `bridge.js`**：依官方「`instance()` 单例、连接由系统自动建立/重连、无 `close()`」，不重建物理连接，改长期状态机——未连上 2.5s 高频探测、连上后 9s 低频心跳、30s 无回执判掉线并自动切回探测；`onopen/onclose/onerror` 触发立即握手；收到手机端 `ready` 主动通告立即握手；`pong/ready/hello` 控制帧不污染结果行。
> - **表端 `index.ux`**：新增 `relink`（重连中）状态与「断开」按钮；只要已启动就允许发送，断线期间指令**入队、连上后自动补发**；超时文案不再承诺「自动重发」。
> - **手机端 `XmsInterconnectLink`**：记录 `registeredNodeId`，nodeId 变化才 `removeListener(旧)+addListener(新)`；服务重连清掉旧登记强制重挂；`onServiceConnected` 立即探节点（不再等 5s 轮询）；注册成功/节点变化/界面打开/45s 保活时**主动发 `cmd='ready'` 帧**，表端无需等下一轮 ping；状态面板加链路健康行（最近收帧/ready 发出/节点重挂次数）。
> - **内置指令引擎 `CommandProcessor`**：配套自动重连做**指令幂等去重**（按 `watchctl_id`，只对有副作用命令去重，最多 64 条 / 120s TTL），重复帧回 `duplicate=true` +「重复指令已忽略（不会重复执行）」，防止补发导致重复执行 WorkBuddy 或重复写引擎。
> - 配套：`CompanionService` 服务重建时 `State.clear()` 修状态不刷新、pong 带时间戳、透传 `duplicate` 且面板不误判「未送达」；`MainActivity` 引导文案同步为「自动维护，不用反复手点」。

## 一句话结论

手表没有 WiFi、Vela 快应用没有 `fetch`/WebSocket/BLE GATT，**唯一真双向实时的路**是：

```
Watch 6 (.rpk 快应用)  ── @system.interconnect（BLE JSON）──
手机端【唯一 1 个 APK：腕控伴侣 com.example.watchctl】（前台服务 :relay，同包名同签名）
   │  ping/pong：本地直回（只验证 BLE 通道）
   │  真实指令：ModuleBridge 线程池 → 本应用内本地直调（无广播、无跨进程）
   ▼
内置指令引擎 CommandProcessor / DshClient / WbIngress（同 APK、同进程，0.1.8 起）
   ├─→ dsh.prompt → DshClient → dsh-go 127.0.0.1:3080 session/prompt（写入 dsh-go 会话）
   │      └─ dsh.list / dsh.new / dsh.cancel / status
   │        （第十四次修正已删除假功能 dsh.open：dshgo:// scheme 与 WB 包名错配）
   ├─→ wb.prompt  → WbIngress → [root su] am start SEND -n .ShareAddToChat
   │      └─ 文本预填进 WorkBuddy 输入框（需点「发送」执行；不代表已送达）
   └─→ ResultSink 回调 → deliverToWatch → BLE 原路回表
        （带 cmd/target/stage/delivered/via/text，两条通道各自如实）
```

> **0.1.3 纠错**：0.1.2 把「App 进程内 `ctx.startActivity()` 被 Android 16 后台启动限制拦」错误外推成「`am start` 整条入口不可用」，转而把 `wb.prompt` 并进 dsh-go 引擎 `session/prompt`，并用「已写入 WorkBuddy 会话（引擎）」包装成假成功。实际 **WorkBuddy 与 dsh-go 引擎是两个不同东西**，写引擎会话不会出现在 WorkBuddy。前期真机调研（`方案.md §4.9/§4.10`、`scripts/wb-a1.sh`）早已证明：**由 root shell 执行 `am start` 豁免后台启动限制**、`.ShareAddToChat` 能把文本预填进 WorkBuddy 输入框。0.1.3 据此恢复该入口并如实分级。

`root` 在这里的不可替代作用：给 **本 App `com.example.watchctl`（腕控伴侣）** 授权，让内置 `WbIngress` 能用 root shell 执行 `am start`（绕 Android 16 后台启动限制）；以及读引擎 cookie、装自签名 companion。KernelSU 授权对象**不再是已废弃的 `com.watchctl.hook`**。

**ZygiskNext + LSPosed（你已装）与本方案无关，不需要启用**：0.1.8 起指令处理已并入 companion 单 APK，走本地直调 + root `am start`，**完全不依赖 Xposed 注入、不用勾作用域、不用装第二个模块**。`lsposed/` 目录只保留为早期方案留档。Xposed 也动不了 dsh-go 的 Flutter/Dart 层与 PRoot 里的 Node 引擎，dsh-go 那一半始终走 HTTP/RPC。详见 [方案.md §4.7](./方案.md)。

## 目录

| 路径 | 内容 | 能否在沙箱验证 |
| --- | --- | --- |
| `方案.md` | 完整技术方案、三端约束、L1/L1.5/L2 链路、命令集、协议细节、风险，**§4.9 第一轮结果 + §4.10 第二轮更正（intent 主路复活、OpenSDK 发现）** | 已核对源码 + 真机 probe |
| `relay/` | **手机端中继**（零依赖 Node，HTTP/SSE + DSH RPC + WorkBuddy intent/broadcast 双通道 + `/result` 回灌 + 引擎离线提示） | ✅ **24/24 测试通过** + 真实服务冒烟 |
| `watch-rpk/` | 表端 Vela 快应用源码骨架（`manifest.json` / `app.ux` / `index.ux` / `bridge.js`） | 需 AIoT IDE 编译 + 真机 |
| `companion/` | Android companion 要点、HyperOS 3 放行清单、Kotlin 骨架 | 需 Android SDK 编译 + 真机 |
| `lsposed/` | **L1.5 加固模块**（Java 源码骨架：广播 receiver / 注入 / 结果回读 / 框架放行） | ✅ 6 个 Java 文件 stub 编译通过；出 APK 与 hook 定位需真机 |
| `scripts/probe.sh` | root 自查：端口 / cookie / **包名确认（已默认 `com.tencent.workbuddy.app`）** / `resolve-activity` 拿 ACTION_SEND 组件 / **Flutter 判定** / ZygiskNext+LSPosed 状态 | 需真机 |
| `scripts/start-relay.sh` | Termux 里手动启动中继（调试用） | — |
| `scripts/ksu-module/` | **KernelSU 开机自启模块**（`service.sh` + `daemon.sh`） | 需真机 |

## 阶段 0：先验证手机侧（已按第一轮真机结果修订）

```sh
# 0) 【当前第一阻塞项】先在 dsh-go App 里启动「本机引擎」
#    第一轮 probe：3080 未监听、无 proot/node 进程 → 引擎当时没跑

# 1) 把仓库拷到手机
adb push watch-control /data/local/tmp/

# 2) root 自查（第 2 版 probe：WB_PKG / DSH_PKG / DSHGO_PKG 均已预置实测值）
su -c 'sh /data/local/tmp/watch-control/scripts/probe.sh'
#    重点看：第 8 段 3080 是否监听、第 5 段 cookie、第 6a 段 WorkBuddy 有无文本入口、
#            6d 无障碍服务现状；回贴 /sdcard/watchctl-wb-dumpsys.txt

# 3) 按结果填配置（config.example.json 已预置两个包名，cp 后只需补 cookie）
cd /data/local/tmp/watch-control/relay
cp config.example.json config.json
#    补：dsh.cookie（或 dsh.token）、relayToken（可选）
#    sendComponent 已预填 com.tencent.workbuddy.app/.ShareAddToChat（第二轮 dumpsys 实证）；
#    若 wb-ingress-test.sh 验收发现它把文本当文件处理，改试 .SharePreviewFile 或 .MainActivity

# 4) 起服务（Termux 里 pkg install nodejs）
node src/main.js

# 5) 验证 dsh-go 半侧
curl http://127.0.0.1:8787/ping
curl -H 'X-Relay-Token: <你的token>' -H 'Content-Type: application/json' \
     -d '{"cmd":"status"}' http://127.0.0.1:8787/command
curl -H 'X-Relay-Token: <你的token>' -H 'Content-Type: application/json' \
     -d '{"cmd":"dsh.list"}' http://127.0.0.1:8787/command
```

> **【早期方案·留档，但入口本身已复活】** 下面这段是早期 Node relay + `ACTION_SEND`/`.ShareAddToChat` + 无障碍补点的调研。**relay/Node、`intent`/`broadcast` 双模式、`POST /result` 已废弃**；但其中「`-n .ShareAddToChat` 把文本预填进 WorkBuddy 输入框」这个**真机 A1 验收结论，0.1.3 已复用**（改由模块 root shell 直接 `am start`，不经 Node relay）。见本页顶部「当前实现」与命令表。

~~**`wb.prompt` 已可试（intent 主路，第二轮复活）**：`config` 已预填 `sendComponent=.ShareAddToChat`。先跑 `scripts/wb-ingress-test.sh` 一轮验收——确认文本是**预填进输入区**还是**直接发出**：前者若想要"全无人值守"，再配 §4.10 通道 C（无障碍点发送）或官方 OpenSDK（通道 B）。**dsh-go 半侧和结果回表不受影响**，引擎链路照常。~~

## 阶段 1：表端与 companion

1. `watch-rpk/manifest.json` 的 `package` 与 `companion` 的 `applicationId` 改成同一个值，用同一张证书签名，填好 `FINGERPRINT`。
2. AIoT IDE 打开 `watch-rpk/` → 模拟器跑 UI → 打包 `.rpk`（每次 +1 `versionCode`）。
3. 米坛工具（表盘自定义工具 / AstroBox V2 / BandBurg）→ 手表「设置 → 我的设备 → 连接新手机」→ 蓝牙一键安装；装完连一次小米运动健康。
4. 编译安装 companion，按 `companion/README.md` 的清单把 HyperOS 3 的自启动/后台弹出界面/关联启动/省电策略全部放行。
5. 表端点「状态」验证往返，再逐条验证 `dsh.prompt` / `dsh.cancel` / `dsh.list` / `wb.prompt`（`dsh.open` 已删除）。
6. 把 `scripts/ksu-module/` 打成 zip 在 KernelSU Manager 里安装并重启，确认 `su -c 'curl -s http://127.0.0.1:8787/ping'` 开机后自动可通。

## 命令集

| `cmd` | 动作 |
| --- | --- |
| `status` | 引擎在线 / 会话数 / WorkBuddy 是否配置 |
| `dsh.list` | 最近 5 个会话标题 |
| `dsh.new` | `session/create` 新建会话 |
| `dsh.prompt` | → dsh-go 引擎 `session/prompt`（自动选最近会话，没有就新建）；`target=dsh-go`，引擎返回 ok → `delivered=true` |
| `dsh.cancel` | `session/cancel` 中断当前回合 |
| `wb.prompt` | **0.1.3**：→ root `am start ... -n .ShareAddToChat` 把文本预填进 WorkBuddy 输入框；`target=WorkBuddy`、`stage=prefill`、`launched=true` 但 `delivered` 恒 false（需在手机点「发送」）。与引擎 `session/prompt` 无关 |

> **当前命令集以 `companion/app/src/main/java/com/watchctl/hook/CommandProcessor.java` 为准（0.1.8 已并入单 APK）**（`status` / `dsh.list` / `dsh.new` / `dsh.prompt` / `dsh.cancel` / `wb.prompt` / `module.ping`），未知命令一律拒绝。**`dsh.open` 已于 0.1.12 删除**（假功能，见第十四次修正）；此前文档写的「page 走固定白名单」在实现里从未存在（只有判空），一并更正。**两条通道各自如实回执**：`dsh.prompt` 引擎返回 ok 才 `ok=true`（`delivered=true`）；`wb.prompt` 只在 root `am start` 被系统接受时 `ok=true`（`launched=true`），但 `delivered=false`/`stage=prefill`，绝不冒充送达；失败均 `ok=false` 带原因。回执带 `cmd`/`target`/`stage`/`delivered`/`via`/`text`，由 companion 透传表端。下方 relay/Node、`intent`/`broadcast` 双模式、`POST /result` 等属于**已废弃的早期方案留档**。**`dsh.*` 前提是引擎在 127.0.0.1:3080 可写；`wb.prompt` 前提是本 App 有 KernelSU root 且装了 WorkBuddy**（`module.ping` 自检自证）。

## HyperOS 3 + KernelSU + ZygiskNext/LSPosed 的关键适配

1. **`am start` 在 root shell 合法**：AOSP `shouldAbortBackgroundActivityStart` 明确豁免 `ROOT_UID` / `SHELL_UID`，所以 KernelSU 的 root 进程调起 WorkBuddy 在原生层没问题；但 **HyperOS 3 的「后台弹出界面 / 关联启动」是 OEM 层拦截，仍要手动放行**。
2. **保活走 KSU 模块**：`service.sh` 异步拉 `daemon.sh`（等 `boot_completed` 后起 Node 并守护），不是 Magisk 的 `service.d`；KSU 与 Magisk 不要混用。KSU 的 App Profile 可只给 companion/Termux 授权 root、对敏感 App 隐藏模块挂载。
3. **ZygiskNext + LSPosed 已真注入（第一轮 probe 实证 `lspd` / `zn-zygisk-companion64`）**：但它对 WorkBuddy 的价值只剩「收广播 + 通知结果回读」两半——**文本注入那一半因 Flutter（View 树）证伪**，而且第二轮已证明文本入口用 `-n` intent 直达即可、根本不需要它。它动不了 dsh-go 的 Flutter/Dart 层与 PRoot 内 Node 引擎。`mount=false` 对 Zygisk 模块属正常。框架放行 hook 默认全关，OEM 层拦截仍按方案 §4.6 手动放行。

## 验证状态

| 项目 | 结果 |
| --- | --- |
| `relay/` 单元测试 | **24/24 通过**（cookie 登录、RPC 信封、401 重登、参数形状、注入边界、命令白名单、SSE、鉴权、配置防护、broadcast 通道、`/result` 回灌、双 token 隔离、实测包名默认值 + 环境变量覆盖、**引擎离线 `dsh-engine-down` 与原包名默认值**） |
| `relay/` 真实服务 | **冒烟通过**：intent 与 broadcast 两模式起服务、`/ping` `/status` `/result` SSE、`wb.prompt` 通道分流 |
| `lsposed/` Java 骨架 | **6 个 Java 文件用最小 stub 编译通过**（11 个 class）；出 APK 与 hook 点定位需真机；**文本注入策略已证伪（Flutter），模块只保留可选回读价值，见 §4.10** |
| dsh-go 协议 | 逐行核对 v1.0.16 源码（`connection.dart` / `carrier.dart` / `session_api.dart` / `page_links.dart`） |
| **真机 probe（两轮）** | **已跑**：拿到 `com.tencent.workbuddy.app`、`io.github.ccch1mneyyy.dsh_app`、**引擎未启动（第一轮）**、**Flutter 实证**、**ZygiskNext/LSPosed 真注入**；**第二轮用完整 dumpsys 推翻了"无 text/plain"，4 个 SEND+text/plain 组件实证，`intent` 主路复活** |
| WorkBuddy 包名 | **`com.tencent.workbuddy.app` 已实测确认并预置到 4 处配置**（relay ×2、LSPosed 作用域、模块设置页 + probe） |
| WorkBuddy 应用形态 | **Android 端 `assets/flutter_assets/` 真机实证 Flutter**（不再是 iOS 外推） |
| Watch 6 / Vela / interconnect | 依据小米规格、Vela 文档与米坛社区实测记录；**小米运动健康包名第一轮未匹配到，待确认** |
| `.rpk` / companion APK（单 APK 架构 · 恒 2 文件） | **已在沙箱编译、验签同源、dex/rpk 内容验证**（0.1.20/code21 + 0.1.12/code13）；第十五次集成拼音输入法已验证：features 齐全、`complete`/`delete` 回调与组件进包、旧键盘清零、图片/词库路径编译后重写为绝对路径且文件齐备；真机键盘布局与输入手感仍需实机确认 |
| 端到端延迟 | 未在真机测量，方案里给的是预期值 |

## 安全边界

- Relay 只绑 `127.0.0.1`，`config.js` 会拒绝非 loopback；可选 `X-Relay-Token`。
- dsh cookie 等价于登录凭证，只存本机、不上传；`/status` 不返回任何凭据。
- **0.1.12 调试后门收紧（第十四次修正）**：`TEST_FRAME` 广播只放行只读命令；`auth_token` 不接受任何帧/广播写入；状态行与错误回执**不含 token 明文**（`复制日志` 会整段外发）。`ModuleBridge.base()` 仍用本 App 自有 token 自动补校验值（本地直调设计），因此白名单是必要防线。
- 全程只走公开接口：不改 `@deepseek-ai/*` 安装目录、不替换官方提示词、不绕过审批与沙箱。手表只是一个输入/显示终端。

## 下一步（按依赖顺序）

1. **【先做】启动 dsh-go 本机引擎**，重跑第 2 版 `probe.sh`，确认第 8 段 `✓ 3080 正在监听`。引擎不通则所有 `dsh.*` 命令失败。
2. **验收 WorkBuddy 的 `intent` 主路**：跑 `scripts/wb-ingress-test.sh`，确认 `-n .ShareAddToChat` 的 `SEND text/plain` 把文本送进会话输入区还是直接发出。这是当前唯一需要真机验收的一跳，见 [方案.md §4.10](./方案.md)。
3. **要不要继续把 `.rpk` 与 companion 做成完整可编译工程**（需 AIoT IDE / Android SDK 与真机）；同时确认小米运动健康的实际包名（第一轮未匹配到）。
4. **手表上具体想控制哪些动作**——目前 7 个命令是通用集；审批、引擎启停、子代理切换等要另做。
5. **L1.5 模块暂缓**：文本注入因 Flutter 证伪、且 `-n` intent 已能直达，模块只剩「结果结构化回表」的可选价值；**不必再花时间编译出 APK 去定位注入点**，除非后面改走官方 OpenSDK 且需要进程内触发。

> L0 兜底仍然成立：只想先"抬腕发指令"，用手表原生音乐键 + 通知回显就能跑，不必写表端应用。
