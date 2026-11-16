# 表端 Vela 快应用（`watch-rpk/`）

适用 REDMI Watch 6（2.07″ / 432×514）。这是 **Xiaomi Vela JS 应用**，必须用小米官方 **`aiot-toolkit`** 打包成 `.rpk`，命令行即可完成，**不需要 AIoT IDE**。

> ⚠️ **历史教训一**：早期误用手机快应用的 `hap-toolkit` 打包，产出的 rpk 缺 Vela 引擎必需的 `config-watch.json` / `manifest-watch.json`，且 `@system.interconnect` 被判「未识别的 native 模块」，导致手表端点开即闪退。Vela 手表包**只能**用 `aiot-toolkit`。
>
> 🚨 **历史教训二（最重要，0.1.24 才定位）**：只能用 **`aiot release --enable-jsc`** 出包，**绝不能用 `aiot build`**。`aiot build` 产出的是 DEVELOPMENT 明文 `.js` debug 包，而真机 AIOTJS 引擎只加载 `app.jsc` 字节码：找不到就报 `Failed to open app.jsc` → `init custom app inst error` → `Abort App` → `arm_memfault PANIC` → 整机重启。AstroBox 安装末尾会自动拉起应用，于是表现为「安装过程中手环重启、显示安装失败」。对照能正常用的「腕记」rpk：9 个代码产物全是 `.jsc`，体积 786.8KB 反而更大——**体积不是原因，明文 js 才是**。`build.sh` 已内置打包后校验，残留 `.js` 直接拒绝交付。

当前产物：`/workspace/交付/watch-0.1.26.rpk`（`versionName 0.1.26` / `versionCode 27`，`build.sh` 每次自动 +1）。交付恒 2 文件：`watch-0.1.26.rpk` + `companion-0.1.20.apk`。

> **✅ 0.1.26（第二十二次修正：UI 居中 + 代码审查健壮性专项）**：① 表端 UI 按红米 Watch 6 **真实方形屏 432×514**（非圆屏）重做，`index.ux` 内容整体垂直居中、收紧上间距（按钮尺寸未缩、保住 0.1.21 放大的可点击尺寸），只改 `<style>`；② `bridge.js` `markConnected` 加 `running` 守卫，修「`close()` 后残留回执把 UI 假复活成已连接却发不出指令」的 P1 竞态。两端都重编重装（配套 companion 0.1.20）。
>
> **🎨 0.1.25（表端居中版 UI，已被 0.1.26 继承）**：`index.ux` 内容整体垂直居中（`justify-content:center` + 收紧 margin），方形屏首屏完整放下。纯显示层，template/JS/按钮 onclick 未动。
>
> **✅ 0.1.24（第十九次修正：改用 jsc 字节码 release 包）**：拿到真机崩溃日志 + 腕记 rpk 逐字节对比后确定根因（见上方历史教训二）。`build.sh` 改为 `aiot release --enable-jsc`，产物仅 `app.jsc` / `pages/index/index.jsc` / `pages/input/index.jsc`；移除 `simulationVersion`；`designWidth: 480` 保留。release/debug/companion 同一张证书（`8637ca4e…d12c`）。用小米官方 Vela 模拟器 headless 实测：安装成功、注册「腕控 0.1.24」、启动到前台、页面渲染完成、崩溃标志 0、`Failed to open app.jsc` 0。此前 0.1.20~0.1.23 改输入页/权限/体积均未奏效，因为方向错了。
>
> **🔧 0.1.23（第十八次修正：拼音输入法拆到独立页面 · 方向有误，见 0.1.24）**：0.1.20~0.1.22 输入法引擎（623KB）全内联进主页面 + 多声明 `system.vibrator`/`system.device` 两个越权接口，真机连续三版「装上就重启」。对齐米坛能跑的应用与官方 README 的正确集成方式：① 新增独立页 `pages/input` 承载 `<input-method>`，主页面 `index.js` 降到 **83KB**（比能装的 0.1.19 还轻），启动不再加载输入法；② manifest 权限收敛为 `router`/`interconnect`/`file`，组件里两个越权模块改为空实现、包内彻底清除；③ 草稿经 `app.ux` 的 `$def.shared` 跨页共享，发送仍在主页面复用已建立的 bridge。编译产物已核验：主页面无输入法引擎、无越权模块、包内 system 模块仅 file/interconnect/router。**输入页不兼容也不影响主应用打开（预设指令照常可发）**。只重装表端，签名同源。
>
> **🐛 0.1.22（第十七次修正：修复拼音版真机装上就重启 · 未奏效，见 0.1.23）**：0.1.20 拼音组件对 `@system.vibrator`/`@system.device`/`@system.file` 用静态 `import`，被编译成模块顶层 `require`；固件若缺该原生模块，页面脚本加载即抛异常 → 闪退重启。三处改为**惰性 + try/catch 动态 require**，有则用、无则降级，与 `bridge.js` 加载互联模块的稳健范式一致。编译产物已核验：3 处 `$app_require$1` 均在 `try{}` 内，不再顶层裸加载。拼音功能在支持设备上不变。**只重装表端**，手机端 APK 不变、签名同源。
>
> **🔘 0.1.21（第十六次修正：按钮放大 + 加间距）**：真机反馈按钮偏小不好按、按钮间没空隙。`index.ux` 全体按钮高度 +8~12px（主发送键 54→64、连接键 52→62、工具键 44→54、次键 38→50）、字号 +2~3px、间距 `margin-top` +4~6px；居中/圆角/色板与全部逻辑不变。**只重装表端**，手机端 APK 不变、签名同源。
>
> **⌨️ 0.1.20（第十五次修正：集成开源中文拼音输入法）**：0.1.19 的 8 键英文翻页键盘真机被证明太慢、打不出中文，整体删除。改用 MIT 开源组件 [`NEORUAA/Vela_input_method`](https://github.com/NEORUAA/Vela_input_method)（`src/components/InputMethod/`，原样纳入 + LICENSE），圆屏 QWERTY 全键盘支持中文拼音整词/候选选字、英文、数字标点。`index.ux` 通过 `complete`（选中文字）/`delete`（退格）事件把内容写入草稿；键盘默认收起，点草稿行弹出。`manifest.json` 增 `system.file`/`system.vibrator`/`system.device` 声明。**本次只重装表端**，手机端 APK 不变。rpk ~348KB（含词库/图片）。真机键盘布局与输入手感待确认。
>
> **🛡️ 0.1.19（第十四次修正：dev-expert 复审后的健壮性专项，只动 `bridge.js` 纯逻辑 + `index.ux` 显示层，帧协议/状态机结构未改）**：用户「再看看两个软件还有没有什么可优化的地方」→ 通用审查 A 组 7 项 + `@skill:dev-expert` 复审补 6 项，用户「确认」后两端一起落地。**两端都要重装**（`watch-0.1.19.rpk` + `companion-0.1.12.apk`）。
> - **`bridge.js`**：A1 异步 send 失败入队补发时撤销该帧 `pending` 超时裁决，让补发成为唯一路径（消除「先 ✗ 超时、连上后补发又 ✓ 成功」抖动与「未自动重发」文案自相矛盾）；A2 `rawSend` 同步抛异常不再触发 `extraFail` 入队，避免同一帧被 `unshift` + `push` 双重入队；A6 `diagnose()` 由 12+ 字段塞单行（表端 13/14px 单行读不全）精简为**两行各 3 项**（状态行：连接状态/回执/待发；提示行：send/失败码/可选能力数）；W4 `trimQueue()` 队列满静默 `shift()` 丢单改为明确提示丢弃条数；**W1 超时预算对齐**：慢命令(`wb.prompt` 2 次 su、`dsh.prompt` 多次 HTTP)12s→**30s** `SLOW_TIMEOUT`，快命令仍 12s，覆盖手机端最坏执行时间，避免表端在手机仍执行时提前宣布超时（超时文案改「手机端可能仍在执行」）。
> - **`index.ux`**：草稿限长 **200 字**（对齐手机端 `WbIngress` 500 截断，保证任何通道都不触发静默截断）、草稿行显示 `N/200 字` 总字数，去掉「核对后点发送」这类看不全全文却暗示能核对的误导文案（改「发送用完整草稿」）；**删除假功能「! 打开引擎页」按钮与 `openEngine()`**（`dshgo://` scheme 与 WB 包名错配、真机拉不起来，手机端 `dsh.open` case 同轮删除）；超时提示改「手机端可能仍在执行，先看面板再决定是否重发」。
> - **`package.json` / `build.sh`**：「companion + LSPosed 中继」「与 LSPosed 模块共享证书」过时文案改为单 APK 表述。
> - **未动**：ping/pong/ready 帧、心跳重连、断线入队补发、两条独立通道、`@system.interconnect` 单例、纯 boolean `if` / 静态按钮 / 响应式字段绑定等已实证模板机制、签名。
> - 配套手机端 `companion-0.1.12`：安全后门收紧 + token 脱敏 + 删 `dsh.open` + 健壮性修复，详见 [companion/README.md](../companion/README.md) 与主 `README.md` 第十四次修正。
>
> **🎛️ 0.1.18（第十三次修正：「更多工具」区补齐主界面设计语言）**：
> - **6 个工具都是真实功能**（逐条对齐手机端 `CommandProcessor.dispatch`）：本地不发帧的「查看连接诊断」（互联能力 / 失败码 / 心跳重连统计）与「断开连接 · 可再连」（停状态机省电）；只读查询「查看引擎状态」(`status` → dsh-go 在线 / 会话数)、「列出会话」(`dsh.list`)；有副作用「! 中断当前会话」(`dsh.cancel` → `session/cancel`)、「! 打开引擎页 · 需手机确认」(`dsh.open`)。**「引擎页」手机端把 `dshgo://` scheme 定向给 WorkBuddy 包名，真机大概率拉不起来，属历史遗留，本轮如实标注「需手机确认」、不冒充可用**。**（0.1.19 第十四次修正已删除「打开引擎页」这个假功能，余 5 个工具）**。
> - **之前没保持设计语言的硬证据**：`.btn` 文字色 `#AAB8C2` 不在主界面色板内（0.1.14 杂色残留）；6 键无分组、无 `.label`、全是两字缩写（「诊断／能力」带斜杠）；破坏性操作与只读查询同色同权重；`.btn` 底 `#1D2832` 与折叠入口 `.btn-more` 的 `#172029` 也不成一体。
> - **改法**：3 组 `.label`（连接 · 本地操作 / dsh-go 引擎 · 查询 / 会影响状态 · 谨慎操作）+ 自解释短句文案（把说明融进控件，不加说明文字墙）；`.btn-tool`（既有 `#172029` + `#E6EDF2`）与 `.btn-caution`（既有琥珀 `#E0A458` + `! ` 文字符号 + 分组标题三重编码）分级；删 `.btn` 与色板外杂色，`400px / 44px / 10px / align-self:center` 统一，每元素显式 `height`。渐进式披露（默认折叠、自定义模式整块隐藏）、纯 boolean `if`、静态按钮 + 纯方法名 `onclick` 全部保持。
> - **链路零改动**：6 个方法名 / 帧格式 / 两条指令通道 / `bridge.js` 状态机一字未动；仅把 `bridge.js` 中「点「诊断／能力」看链路健康」这句用户可见提示同步为「查看连接诊断」（2 处文案，逻辑未动）。
> - **⚠️ 只重装表端** `watch-0.1.18.rpk`；手机端 `companion-0.1.11.apk` 未改动、无需重装、无需录音权限。
>
> **⌨️ 0.1.16（第十二次修正：自定义指令改为表端内置输入面板 + 居中修复）**：
> - **0.1.15 的语音 / 输入框方案整体作废**：Vela 三方 rpk **没有可向它弹出的系统输入法**（你点 `<input>` 不弹键盘，官方 samples 的「输入法」是要拷进工程的应用内组件），也没有 ASR/STT；真机上「在手机上说出」被 ROM 拦成「识别中断」。且把输入放到手机再回传手表，主次颠倒 —— **手表才是发起端**，手机只被动执行。本项目不再提供任何语音输入，改为手表本地闭环。
> - **`index.ux` 自定义模式 = 内置输入面板**：草稿 `<text>` 行实时显示（超长截尾、发送用完整 `draft`）；常用词块 3 页 × 6（点一下追加，中文靠词块组合）；字符键盘 6 组 × 8（字母 / 数字 / 标点，默认折叠，点「换一组字符」翻页）；退格 / 清空；核对后两个主发送键「发到 DSH」「发到 WorkBuddy」复用既有两条独立通道，只把 `text` 换成草稿。**输入 ≠ 发送**，草稿为空不发送、不冒充成功，切到自定义模式时折叠工具整块收起。
> - **只用已实证的模板机制**：静态按钮 + `value="{{w0..w5/k0..k7}}"` 响应式绑定（编译产物已验证 getter 全部生成，翻页能刷新按钮文字）+ 纯方法名 `onclick`（`addW0`…`addK7`）+ 纯 boolean `if`；**不用 `for`、不用事件传参、不用 `<input type="text">`、不用动态 class/style**。每个元素显式 `height`，Vela CSS 子集。
> - **居中修复**：`.page` 加 `align-items: center`，按钮 / 读数加 `align-self: center` 双保险，按钮内文字 `text-align: center` —— 按钮整体与标题都居中（此前定宽 400px 元素在 448px 内容区里靠左）。字符键盘 `.krow` 用 `flex-direction: row` + `flex-wrap` + 固定 46px 键位，不用 `flex:1`。编译产物已确认 `alignItems`/`alignSelf`/`flexDirection: "row"` 均进入 rpk。
> - **`bridge.js` 回归纯指令状态机**：删除全部跨端语音帧、`voiceWait`、`onVoice`、`requestVoice()`；`ping/pong/ready`、心跳重连、断线入队补发、指令回执分流、两条指令通道**一字未动**。
> - **🎨 遵循 `.impeccable.md`**：深色工业仪表、状态大读数常驻、模式切换与字符键盘渐进式披露、单一琥珀强调（只有两个发送键实底）、语义色 + 文字符号双重编码；新样式只复用既有色板，无新色 / 卡片 / 阴影 / 常驻说明。
> - **⚠️ 两端都要重装**：`watch-0.1.16.rpk` + `companion-0.1.11.apk`；不再需要录音权限。
>
> **🎨 0.1.14（UI 全面美化与去噪，impeccable）**：`index.ux` **只改显示层**，`bridge.js` 长期状态机与两条指令通道**一行未动**。从「11 个按钮平铺 + 4 行常驻文字 + 5 色杂配」重做为与手机端同一套深色工业仪表风：状态大读数带语义色（已连接绿 / 连接中·重连中琥珀 / 未连接红）、当前指令点击即切换（省掉「切换指令」按钮）、两个发送按钮为唯一主操作（琥珀实底）、6 个低频调试工具（断开 / 诊断／能力 / 状态 / 会话 / 中断 / 引擎页）收进「更多工具」折叠、常驻说明书删除（运行时如实提示仅在有内容时显示）。折叠用 Vela 编译器原生 `if` 指令 + 纯 boolean 变量（`stateOk/stateWarn/stateBad/showMore/moreClosed/hasHint`），**不用动态 class / 动态 style / `!` 表达式**，规避模板兼容风险。
> **⚠️ 这次表端必须重装**：界面已变，`watch-0.1.13.rpk` 不再是当前产物。先卸载手表旧「腕控」再装 `watch-0.1.14.rpk`；手机端同时覆盖装 `companion-0.1.9.apk`。**0.1.8 单 APK 合并时「表端不用重装」只是当时的结论，现已作废**。
> **0.1.13（连接稳定性专项）**：`bridge.js` 从「一次性 60s 握手、连上后停 ping」重写为**长期链路状态机**——连上后 9s 心跳、30s 无回执判掉线并自动切回 2.5s 高频探测，`onopen/onclose/onerror` 与手机端 `ready` 主动通告都会立即触发握手；`pong/ready/hello` 控制帧不污染结果行。**断线期间发的指令会入队，连上后自动补发**（内置指令引擎按 `id` 幂等去重，不会重复执行）。`index.ux` 新增 `relink`（重连中）状态与「断开」按钮，只要已启动就允许发送。下面「0.1.9 一次性 60s 窗口」的描述是**被 0.1.13 覆盖的旧模型**，保留作演进留档。
> **0.1.12**：**dsh-go 引擎 ≠ WorkBuddy**。两个发送入口是两条独立通道、各自如实回执：`发到 DSH`(`dsh.prompt`) → 引擎 `session/prompt` 写入 dsh-go 会话；`发到 WorkBuddy`(`wb.prompt`) → 内置 root shell `am start .ShareAddToChat` 把文本预填进 WorkBuddy 输入框（需点发送）。表端按 companion 透传的 `cmd/target/stage` 分别提示，不再一律「已完成」。0.1.2 曾把两者合并、把引擎会话冒充 WorkBuddy，已纠正。

## 打包

```bash
cd watch-rpk
bash build.sh          # 自动 versionCode+1 / versionName 末段+1
                       # → aiot release --enable-jsc（PRODUCTION + 字节码）
                       # → dist/com.example.watchctl.release.<版本>.rpk
                       # → 校验包内只含 .jsc、无 .js 残留
                       # → deliver.sh 整理到 /workspace/交付/watch-<版本>.rpk
```

`build.sh` 每次自动升版本号（手表会缓存同版本旧包，不升会覆盖不了）。**不要用 `npx aiot build`**：它出的是明文 `.js` debug 包，真机会崩（见顶部历史教训二）。

## 与官方模板对齐的结构（Vela 引擎硬性要求）

```
src/manifest.json          package/name/icon/version/deviceTypeList/features/config(designWidth:480)/router
src/config-watch.json      Vela 设备专属配置，必须存在（内容 {} 即可），aiot 据此生成 manifest-watch.json
src/app.ux                 应用生命周期
src/common/logo.png        应用图标：/common/logo.png，128×128 不透明 RGB（绝对路径）
src/common/bridge.js       事实驱动 + ping 握手的连接状态机（见下）
src/pages/index/index.ux   主界面（0.1.19）：状态大读数 + 预设/自定义双模式 + 内置输入面板(草稿限长) + 5 个分组式工具折叠区
src/i18n/*.json            国际化资源（可为空 {}）
sign/debug/                private.pem + certificate.pem（共享证书）
```

manifest 关键点：`icon` 用**绝对路径** `/common/logo.png`；`designWidth` 必须是**数字** 480；`deviceTypeList: ["watch"]`；`features` 声明 `system.router` 与 `system.interconnect`。

## 连接：事实驱动 + ping 握手（0.1.8）

依据小米官方 [interconnect 文档](https://iot.mi.com/vela/quickapp/zh/features/network/interconnect.html)、官方 `interconnect_dev_test_demo`，以及 **REDMI Watch 6 / Vela 4 逐版真机实测**：

- **连接由系统自动建立**——「应用内不用关心连接的创建和销毁」，`instance()` 无参、返回单例。
- **逐版真机关键事实**：
  - `diagnosis()` 在本固件**不是函数**（0.1.5 调用即 `TypeError: ... is not a function`），**永不调用**；
  - 0.1.6 把 `onopen` 当唯一判据 → 13s 超时。原因：自动建连 + 单例模型下 `onopen` 可能早已触发过、不再补发；且「能力存在」≠「调用形态/返回数值语义与假设一致」；
  - 0.1.7 报「探测帧发不出」→ `send` 的 `success` 一次都没触发，而代码**把 `fail` 的 code 吞了**、超时结论只看 `sendOk`，于是把「send 被底层拒绝」误报成「发不出」；
  - 0.1.9 手机端日志证实 `ping` 到达手机、`pong` 回写成功（收 1/写 1/错 0），**通道本来就是通的**。`1006` 真因是时序竞态：手机端冷启动到「监听已注册」约需 31s（`DEVICE_MANAGER` 授权弹窗），而 0.1.8 握手窗口仅 13s 且 `onerror(1006)` 一触发就判死停发 ping → 表端必被钉在「断开」。
- **唯一权威判据 = 端到端事实**：`send` + `onmessage` 是所有固件都保证的基础 API。`open()` 后每 2.5s 持续发 `cmd:'ping'` 探测帧（**不再 5 次就停**），手机端 companion **本地直回 `pong`**（`handleWatchFrame` 对 `cmd=ping` 直接 `link.send()`，不经指令引擎），表端 `onmessage` 收到回执即 `markConnected('收到回执')`。
- **`send` 双形态自适应**：官方 demo 明确 `send` 同时支持「回调」（`{data, success, fail}`）与「Promise」（`send({data}).then().catch()`），且 `fail` 参数既有 `(res, code)` 两参、也有单参 `{data, code}`。故 ping 交替用两种形态发、并对返回的 thenable 一律接 `.then/.catch`（去重），**不再赌固件实现哪一种**；`data` 严格传 Object（官方规定）。
- **失败码完整采集并按官方含义显示**：`fail`/`onerror` 的 code 全部记录，`1001` 手机端 App 未安装 / `1006` 连接断开 / `204` 链接超时 / `1000` 未知错误。`getApkStatus`/`getReadyState` 兼容同步 / 回调 / Promise 三形态、原样显示原始值，`onopen/onclose/onerror` 与 send 调用/成功/失败次数一并记录。
- **60s 窗口 + `onerror` 不判死**：`onerror(1006/204/1000)` 只记事实、继续探测（仅 `1001` 手机未装 App 快速失败），给手机端冷启动/授权留足时间；一旦收到回执立即连上，正常几秒即可。60s 仍无回执才按官方错误码给分层结论，且把 1006/204 判为「手机端尚未就绪」而**不再误导去查蓝牙配对**。「诊断／能力」显示完整事实清单（能力 + 失败码 + 计数 + `ready/apk/state`），永不调用不存在的接口、永不报错。

> ⚠️ **上面 0.1.4~0.1.12 是「一次性握手」模型的演进留档，0.1.13 起已整体改为长期状态机**：
> `FAST_MS=2500` / `HEARTBEAT_MS=9000` / `DEAD_AFTER=30000` / `KICK_MIN_GAP=1000`；**0.1.19 起超时分档**：`SEND_TIMEOUT=12000`（快命令）/ `SLOW_TIMEOUT=30000`（`wb.prompt`/`dsh.prompt`/`dsh.new`/`dsh.cancel`/`dsh.list`/`status` 等慢命令，`budgetFor(cmd)` 选取，覆盖手机端 2 次 su 或多次 HTTP 的最坏耗时）。
> `tick()` 常驻：在线时按 9s 发心跳，一旦 `now - lastSeen > 30s` 就 `onLinkLost()` 切回 2.5s 高频探测（**不判死、不重建 `instance()`**）；`onopen/onclose/onerror` 与收到 `cmd='ready'` 都触发 `kickPing()` 立即握手。`send()` 在 `running && state!=='online'` 时把帧入队并 `kickPing()`，连上后 `flush()` 补发；`pong/ready/hello` 作为控制帧直接返回、不写结果行。`bridge.started` / `bridge.health` 供 UI 判断「已启动」与「链路健康」，所以「连接中/重连中」也能点发送，指令不会丢。

> 注：官方 demo 顶层就 `import interconnect` 且 `onReady` 直接 `instance()`，所以"启动期碰互联会崩"是错误假设——真根因一直是打包工具链。本项目仍保留"点按钮才 require"的保守做法，但不再作为防崩手段。

版本演进：`0.1.4` 换对工具链（能打开）→ `0.1.5` 接真 SDK + 修无限「正在连接」→ `0.1.6` 去掉对 `diagnosis` 的依赖 → `0.1.7` ping 握手判连 → `0.1.8` send 双形态 + 暴露官方失败码 → `0.1.9` 修 1006 时序竞态（60s 窗口、onerror 不判死、持续 ping）→ `0.1.10` 修「连接成功但发指令超时」（超时 8s→12s，文案指向模块未回执）→ `0.1.11` 文案如实化 → `0.1.12` 拆分两条独立指令通道、恢复 WorkBuddy 真实入口 → **`0.1.13` 连接稳定性专项（长期状态机：持续心跳 + 断线检测 + 自动重连 + 指令排队补发）** → **`0.1.14` UI 全面美化与去噪** → `0.1.16` 表端内置输入面板 + 主次修正 + 居中修复 → `0.1.18`「更多工具」补齐设计语言 → **`0.1.19` dev-expert 复审健壮性专项（超时分档 / 队列去重 / 诊断精简 / 草稿限长 / 删 dsh.open）**。

> **⚠️ 「已连接」≠「能发指令」**：ping/pong 是 companion 本地直回，**不经指令引擎**；真实指令还要交给内置引擎执行。
> **0.1.8 单 APK 合并后**，指令在**同一个 APK 内本地直调**（`ModuleBridge` 线程池 → `CommandProcessor` → `ResultSink` 回调），
> 不再有「广播 `CMD` → 另一个进程 → 广播 `RESULT`」这一跳，因此「模块=无响应 / 超时·模块未回执」这类断点已不复存在。
> 若仍超时，看手机端 0.1.9 新面板的**「当前链路」大状态 +「链路读数」里的「指令引擎」一行 +「运行日志」**，而不是去查蓝牙配对。详见 [companion/README.md](../companion/README.md)。

## UI 遵循 Vela/REDMI Watch 6 实战避坑

- `flex-direction: column` 竖排，**不用** `position: absolute`、不用行内并排按钮、不用 `flex:1`；
- 可点元素用 `<input type="button">`，**全宽单按钮**竖排；`<text onclick>` 也能做「点击切换指令」这类轻量控件；
- 每个 `text` **显式 `height`**；背景用 `background-color`；颜色用 6 位 `#RRGGBB`。
- **0.1.14 起的条件渲染**：用 Vela 编译器原生 `if="{{flag}}"`（`@aiot-toolkit/parser` 的 `IfTranslate.js`/`CiTranslate.js` 实证支持），`flag` 一律是 `private` 里的**纯 boolean 变量**，不用动态 class、不用 `style` 插值、不用 `{{!flag}}` 取反表达式——两个互斥分支各自写死一个变量（`showMore` / `moreClosed`），把模板兼容风险降到最低。

## 签名同源（关键）

- 共享证书：`watchctl.keystore.p12` + `sign/debug/{private.pem,certificate.pem}`（同一张）。
- 证书 SHA-256：`8637ca4ee7cb4ccc7219f3d5cd5888b7e6547f0038de3132e33ddeb1ef39d12c`（rpk 与两个 APK 已核对一致）。
- **改动任一端签名后两端（rpk / companion）必须一起重打包**，否则 `@system.interconnect` 报签名不一致。0.1.8 起手机端只有 companion 一个 APK，不再有第三个包要对齐。
- rpk 的 `package` = `com.example.watchctl` 必须与 companion `applicationId` 一致。

## 部署到真机（顺序很重要）

1. `bash build.sh` 生成新版本 rpk。
2. **先卸载手表上的旧「腕控」再安装**（Vela 的 `force-stop` 不清状态）。**0.1.14 界面已变，必须重装，不能沿用 0.1.13**。
3. 安装后**等待 9~12 秒**再操作（启动动画期间点击被吞）。
4. 手机端：装 **小米运动健康** 并与手表蓝牙配对 → 装 **companion 0.1.9（唯一 1 个 APK，界面已重做）**并**打开一次**（启动前台服务）→ KernelSU 给本 App `com.example.watchctl` 授权 root（WorkBuddy 通道需要；若 0.1.8 已授权则无需重做）。**不用装第二个模块、不用勾 LSPosed 作用域**。
5. **建议（0.1.13 起不再是硬性前提）**：先打开 companion 状态面板，让手机端完成冷启动授权（约 30s）并注册消息监听。**0.1.13 起顺序不再致命**——手机端 listener 一就绪就主动发 `ready`，表端收到立即握手；表端也会持续心跳探测、掉线自动重连，所以先开手表也能自己连上，不用反复手点。
6. 打开应用 → 首屏点「连接手机」→ 状态大读数变绿「● 已连接」（收到 `pong` 或 `ready`）。此后表端**常驻维护链路**：在线 9s 心跳、30s 无回执显示「● 重连中」并自动切回 2.5s 探测，恢复后立即补发排队中的指令。轻点「当前指令」文字即可切换预设，然后点「发到 DSH」/「发到 WorkBuddy」两个主按钮；切「自定义指令」用内置面板拼字（草稿上限 200 字、草稿行显示总字数）。**查看连接诊断 / 断开连接 / 查看引擎状态 / 列出会话 / ! 中断当前会话**这 5 个低频工具在「更多工具」里（默认收起、分组自解释）；「查看连接诊断」看精简后的链路速览（连接状态 / 回执 / 待发 / send / 失败码 / 可选能力数，分两行单行可读），不会报错。「引擎页」假功能已于 0.1.19 删除。

## 帧格式与链路

表端发 `{"id":"w-...","cmd":"dsh.prompt","args":{"text":"..."}}`；手机回 `{"id":"...","ok":true,"text":"一行摘要"}`。手机侧 companion 已接入**小米官方 `xms-wearable` SDK**（`MessageApi.addListener/sendMessage`，UTF-8 JSON 字节），不再是反射占位。链路：

```
手表 rpk --@system.interconnect(BLE, 包名+签名校验)--> 【唯一 APK】companion(:relay, xms-wearable SDK)
        --ModuleBridge 线程池·本应用内本地直调（0.1.8 起无广播/无跨进程）--> 内置指令引擎
           ├─ dsh.prompt --> DshClient --> dsh-go 127.0.0.1:3080 session/prompt（写入 dsh-go 会话）
           └─ wb.prompt  --> WbIngress --> root `am start SEND -n .ShareAddToChat`
                             （预填进 WorkBuddy 输入框，需点发送）
        --ResultSink 回调--> deliverToWatch --> 回表（cmd/target/stage 分别提示）
```

> 0.1.12 起两个入口是**两条独立通道**：`dsh.prompt` 写 dsh-go 引擎会话；`wb.prompt` 走 root `am start` 预填 WorkBuddy（AOSP 豁免 ROOT/SHELL UID，不受 Android 16 后台启动限制）。**dsh-go 引擎与 WorkBuddy 是两个不同东西**——0.1.2 把两者混为一谈、用「已写入 WorkBuddy 会话（引擎）」造假成功，已纠正。
```
