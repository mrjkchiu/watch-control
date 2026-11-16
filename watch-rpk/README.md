# 表端 Vela 快应用（`watch-rpk/`）

适用 REDMI Watch 6（2.07″ / **432×514 竖向圆角矩形**，非纯圆屏）。这是 Xiaomi Vela JS 应用，必须用小米官方 **`aiot-toolkit`** 打包成 `.rpk`，命令行即可完成，**不需要 AIoT IDE**。

> ⚠️ **两个必须记住的坑**：
> ① Vela 手表包**只能**用 `aiot-toolkit`。用手机快应用的 `hap-toolkit` 会产出缺 `config-watch.json`/`manifest-watch.json`、`@system.interconnect` 被判「未识别 native 模块」的包，手表点开即闪退。
> ② **只能用 `aiot release --enable-jsc`** 出包，**绝不能用 `aiot build`**。`aiot build` 出的是明文 `.js` debug 包，真机 AIOTJS 引擎只加载 `app.jsc` 字节码，找不到就框架初始化失败 → `Abort App` → `arm_memfault PANIC` → 整机重启。AstroBox 安装末尾会自动拉起应用，于是表现为「**安装过程中手环重启、显示安装失败**」。对照能正常用的「腕记」rpk：代码产物全是 `.jsc`、体积反而更大——**体积不是原因，明文 js 才是**。`build.sh` 已内置打包后校验，残留 `.js` 直接拒绝交付。

当前产物：`/workspace/交付/watch-0.1.26.rpk`（`versionName 0.1.26` / `versionCode 27`）。交付恒 2 文件：`watch-0.1.26.rpk` + `companion-0.1.21.apk`。

## 打包

```bash
cd watch-rpk
bash build.sh          # 自动 versionCode+1 / versionName 末段+1
                       # → aiot release --enable-jsc（PRODUCTION + 字节码）
                       # → dist/com.example.watchctl.release.<版本>.rpk
                       # → 校验包内只含 .jsc、无 .js 残留
                       # → deliver.sh 整理到 /workspace/交付/watch-<版本>.rpk
```

`build.sh` 每次自动升版本号（手表会缓存同版本旧包，不升会覆盖不了）。

## 与官方模板对齐的结构（Vela 引擎硬性要求）

```
src/manifest.json          package/name/icon/version/deviceTypeList/features/config(designWidth:480)/router
src/config-watch.json      Vela 设备专属配置，必须存在（内容 {} 即可），aiot 据此生成 manifest-watch.json
src/app.ux                 应用生命周期
src/common/logo.png        应用图标：/common/logo.png，128×128 不透明 RGB（绝对路径）
src/common/bridge.js       长期连接状态机（心跳/重连/入队补发）
src/pages/index/index.ux   主界面：状态大读数 + 预设/自定义双模式 + 内置输入面板 + 工具折叠区
src/pages/input/index.ux   独立输入页（拼音输入法，不兼容也不影响主应用打开）
src/i18n/*.json            国际化资源（可为空 {}）
sign/debug/                private.pem + certificate.pem（签名材料，不入库）
```

manifest 关键点：`icon` 用**绝对路径** `/common/logo.png`；`designWidth` 必须是**数字** 480；`deviceTypeList: ["watch"]`；`features` 声明 `system.router` 与 `system.interconnect`。

## 连接：事实驱动 + ping 握手

依据小米官方 interconnect 文档、官方 `interconnect_dev_test_demo` 与 REDMI Watch 6 真机实测：

- **连接由系统自动建立**——应用内不用关心连接的创建和销毁，`instance()` 无参、返回单例。
- **唯一权威判据 = 端到端事实**：`send` + `onmessage`。`open()` 后每 2.5s 发 `cmd:'ping'` 探测帧，手机端 companion **本地直回 `pong`**（`handleWatchFrame` 对 `cmd=ping` 直接 `link.send()`，不经指令引擎），表端 `onmessage` 收到回执即 `markConnected('收到回执')`。
- `diagnosis()` 在本固件**不是函数**，永不调用；`onopen` 只记事实、不作判据（单例下可能早已触发、不再补发）。
- 长期状态机（`bridge.js`）：`FAST_MS=2500` / `HEARTBEAT_MS=9000` / `DEAD_AFTER=30000` / `KICK_MIN_GAP=1000`；超时分档 `SEND_TIMEOUT=12000`（快命令）/ `SLOW_TIMEOUT=30000`（`wb.prompt`/`dsh.prompt` 等慢命令）。在线按 9s 心跳，`now-lastSeen>30s` 切回 2.5s 探测（**不判死、不重建 `instance()`**）；`onopen/onclose/onerror` 与收到 `cmd='ready'` 都触发立即握手；`pong/ready/hello` 作为控制帧直接返回、不写结果行。
- 断线期间发的指令入队、连上后 `flush()` 补发；手机端按 `watchctl_id` 幂等去重，不会重复执行。`bridge.started`/`bridge.health` 供 UI 判断，所以「连接中/重连中」也能点发送，指令不丢。
- `send` 双形态自适应（回调 `{data,success,fail}` 与 Promise `.then/.catch`，`fail` 两参/单参兼容），`data` 严格传 Object。失败码：`1001` 手机未装 App / `1006` 连接断开 / `204` 链接超时 / `1000` 未知——全部采集显示，把 `1006/204` 判为「手机端尚未就绪」，不再误导去查蓝牙配对。

> **「已连接」≠「能发指令」**：ping/pong 是 companion 本地直回、不经指令引擎。0.1.8 起指令在**同一 APK 内本地直调**（`ModuleBridge` → `CommandProcessor` → `ResultSink`），不再有跨进程广播，所以「模块无响应」这类断点已不存在。若仍超时，看手机面板「当前链路」+「指令引擎」+「运行日志」。

## UI 遵循 Vela/REDMI Watch 6 实战避坑

- `flex-direction: column` 竖排，**不用** `position: absolute`、不用行内并排按钮、不用 `flex:1`；可点元素全宽单按钮竖排。
- 每个 `text` **显式 `height`**（Vela 忽略 `line-height`）；背景用 `background-color`；颜色用 6 位 `#RRGGBB`。
- 条件渲染用 Vela 编译器原生 `if="{{flag}}"`，`flag` 一律是 `private` 里的**纯 boolean**，不用动态 class / `style` 插值 / `{{!flag}}` 取反；两个互斥分支各写死一个变量。
- 方形屏 432×514，内容整体垂直居中；设计原则见 [../.impeccable.md](../.impeccable.md)（克制·精确·可信，反假绿）。

## 签名同源（关键）

- 共享证书：`watchctl.keystore.p12` + `sign/debug/{private.pem,certificate.pem}`（同一张）。证书 SHA-256 `8637ca4ee7cb4ccc7219f3d5cd5888b7e6547f0038de3132e33ddeb1ef39d12c`。
- **改动任一端签名后，两端（rpk / companion）必须一起重打包**，否则 `@system.interconnect` 报签名不一致。rpk 的 `package` = `com.example.watchctl` 必须与 companion `applicationId` 一致。
- 签名材料不入库，见根 `.gitignore`；换设备需单独拷贝。

## 部署到真机（顺序很重要）

1. `bash build.sh` 生成新版本 rpk。
2. **先卸载手表上的旧「腕控」再安装**（Vela 的 `force-stop` 不清状态）。
3. 安装后**等待 9~12 秒**再操作（启动动画期间点击被吞）。
4. 手机端：装小米运动健康并与手表蓝牙配对 → 装 companion（唯一 1 个 APK）并**打开一次** → KernelSU 给 `com.example.watchctl` 授权 root。**不用装第二个模块、不用勾 LSPosed 作用域**。
5. 顺序不再致命：手机端 listener 一就绪就主动发 `ready`，表端收到立即握手；表端也会持续心跳、掉线自动重连，先开手表也能自己连上。
6. 打开应用 → 点「连接手机」→ 状态变绿「● 已连接」。轻点「当前指令」切换预设，点「发到 DSH」/「发到 WorkBuddy」发送；自定义指令用内置面板拼字（草稿上限 200 字）。5 个低频工具在「更多工具」里（默认收起、分组自解释）。

## 帧格式与链路

表端发 `{"id":"w-...","cmd":"dsh.prompt","args":{"text":"..."}}`；手机回 `{"id":"...","ok":true,"text":"一行摘要"}`。手机侧 companion 已接入小米官方 `xms-wearable` SDK（`MessageApi.addListener/sendMessage`，UTF-8 JSON 字节）。链路：

```
手表 rpk --@system.interconnect(BLE, 包名+签名校验)--> 【唯一 APK】companion(:relay, xms-wearable SDK)
        --ModuleBridge 线程池·本应用内本地直调（无广播/无跨进程）--> 内置指令引擎
           ├─ dsh.prompt --> DshClient --> dsh-go 127.0.0.1:3080 session/prompt
           └─ wb.prompt  --> WbIngress --> root `am start SEND -n .ShareAddToChat`（预填 WorkBuddy）
        --ResultSink 回调--> deliverToWatch --> 回表（cmd/target/stage 分别提示）
```

`dsh.prompt` 写 dsh-go 引擎会话；`wb.prompt` 走 root `am start` 预填 WorkBuddy（AOSP 豁免 ROOT/SHELL UID，不受 Android 16 后台启动限制）。**dsh-go 引擎与 WorkBuddy 是两个不同东西**，两条通道各自如实回执。
