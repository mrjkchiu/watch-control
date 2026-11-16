# wb-a11y · WorkBuddy 自动发送无障碍服务

把「手表 → relay → `am start ... .ShareAddToChat`」这条链路从**半无人值守**（文本进输入框、要手点发送）升级为**全无人值守**（自动点发送）。

> 背景：A1 真机实测确认 `.ShareAddToChat` 的 `SEND + text/plain` intent 能把文本**预填进 WorkBuddy 会话输入框**，但需手动点一次「发送」。本服务即补上这一下点击。

## 它做什么 / 不做什么

- **做**：仅在 relay 发来 `com.watchctl.a11y.ARM` 广播（带 `id`）后的 **6 秒时间窗**内，于 `com.tencent.workbuddy.app` 窗口中自动点击一次文本含「发送」的可点击节点，然后回 `com.watchctl.a11y.DONE`。
- **不做**：不读输入框内容、不上传任何数据、不在未武装时点击、不监听 WorkBuddy 以外的任何 App。
- 设计为「平时完全静默」，不会对用户手动打开的分享页误伤。

## 构建

需要本机 Android SDK（Android Studio 或命令行 `sdkmanager` + `cmdline-tools`）。AGP 版本见 `app/build.gradle`，按你 SDK 调整：

```sh
cd wb-a11y
./gradlew :app:assembleDebug      # 出 app/build/outputs/apk/debug/app-debug.apk
```

> 若你不想装 Android SDK，见仓库 `relay/src/android.js` 里的 **uiautomator 兜底**（`autoSendMethod: "uiautomator"`）——纯 shell、无需构建 APK，也能自动点发送，只是依赖设备上 `uiautomator` 二进制且稍慢。

## 安装 + 授权（需 root / KernelSU）

```sh
adb install app/build/outputs/apk/debug/app-debug.apk
su -c 'sh /data/local/tmp/enable-a11y.sh'   # 免手点开启无障碍（见 enable-a11y.sh）
```

不想用脚本也可手动：系统设置 → 更多设置 → 无障碍 → 找到「WB 自动发送（手表遥控）」→ 开启。

## 与 relay 配合

relay 的 `config.json` 打开 `workbuddy.autoSend: true`（默认 `method: "a11y-service"`）后，`sendText` 会：

1. 先 `am broadcast -a com.watchctl.a11y.ARM --es id <requestId>` 武装服务；
2. 再 `am start ... .ShareAddToChat` 预填文本；
3. 服务在窗口出现「发送」时自动点击，回 `DONE`。

广播 action 与服务端常量一致：`com.watchctl.a11y.ARM` / `com.watchctl.a11y.DONE`，包名 `com.watchctl.wbautosend`（改包名时同步改 `app/build.gradle` 的 `namespace/applicationId` 与 `android.js` 的 `armAction`）。
