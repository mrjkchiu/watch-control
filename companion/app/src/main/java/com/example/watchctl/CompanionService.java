package com.example.watchctl;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

/**
 * 腕控 · companion 中继服务（常驻，:relay 进程）。
 *
 * 0.1.8 单 APK 合并：手机端只剩这一个 App，BLE 互联与指令执行都在本 APK 内。
 *
 *   手表(BLE interconnect)
 *        │  JSON 帧 {id, cmd, args}
 *        ▼
 *   WatchLink.onFrame ──► handleWatchFrame
 *        │  ModuleBridge（线程池，本地直调，不再发 com.watchctl.CMD 广播）
 *        ▼
 *   内置指令引擎 CommandProcessor（DshClient / WbIngress / Config，同进程）
 *        │  ResultSink 回调（不再发 com.watchctl.RESULT 广播）
 *        ▼
 *   onEngineResult ──► deliverToWatch ──► WatchLink.send 回手表
 *
 * 结果仍兼容收 com.watchctl.RESULT 广播（供 adb 自测），两条路径共用 onEngineResult。
 *
 * 0.1.16 主次修正：手机端只被动执行指令，不参与「自定义指令的输入」。
 *   0.1.15 曾在本服务里跑系统 SpeechRecognizer（跨端语音帧）并提供
 *   「手机打字回传手表草稿框」的输入区 —— 真机被 ROM 拦成「识别中断」，且
 *   「手机输入 → 回传手表 → 再从手表发回手机」逻辑绕圈，把主控权交回了手机。
 *   本版彻底删除：语音识别、录音权限、识别服务 query、手机端输入区与全部语音帧。
 *   自定义指令改为表端内置输入面板本地拼字 → 原 send() 发送，手机端链路一行未动。
 *
 * 关键容错（0.1.2 修）：前台服务与 SDK 初始化彻底解耦。
 *   Android 14(targetSdk34) 对 connectedDevice 类型前台服务强制校验蓝牙运行时权限，
 *   未授予时 startForeground 抛 SecurityException；旧版把它和 createLink()/connect()
 *   放在同一个 try 里，一处失败 → 整个服务半残、SDK 监听从未注册 → 表端 send 报 1006。
 *   现在每一步独立 try/catch，前台服务失败也照常注册 SDK 监听，并把结果上报到界面。
 *
 * 调试（无手表，0.1.12 起收紧 S1）：
 *   am broadcast -a com.watchctl.TEST_FRAME --es frame '{"cmd":"module.ping"}'
 *   am broadcast -a com.watchctl.COMPANION_SET --es dsh_token '...'
 *   am broadcast -a com.watchctl.STATE_QUERY   （界面快照）
 *   am broadcast -a com.watchctl.MODULE_PROBE （引擎自检）
 *
 * S1 安全收紧：调试接收器为兼容 adb 仍以 RECEIVER_EXPORTED 注册，但 CommandReceiver 只放行
 * 【只读命令】(status/dsh.list/module.ping/ping)，且禁止任何广播改写 auth_token。
 * 因为 ModuleBridge.base() 会用本 App 自有 authToken 自动补 token，若不设白名单，
 * 同机任意 App 发一条隐式广播即可绕过校验执行 wb.prompt(root am start)/dsh.prompt 或改 token。
 * 副作用命令与写配置帧一律挡在广播后门外；adb 排障（只读查询 + 设 dsh_token）不受影响。
 */
public class CompanionService extends Service {
    private static final String TAG = "WatchCtl-Companion";
    private static final String CHANNEL_ID = "watchctl_companion";
    public static final String ACTION_TEST_FRAME = "com.watchctl.TEST_FRAME";
    public static final String ACTION_COMPANION_SET = "com.watchctl.COMPANION_SET";
    public static final String ACTION_STATE_QUERY = "com.watchctl.STATE_QUERY";
    public static final String ACTION_MODULE_PROBE = "com.watchctl.MODULE_PROBE";

    /** 探测回执 id 前缀：这类回执只更新状态面板，不回写手表，避免污染表端结果行。 */
    private static final String PROBE_PREFIX = "probe-";
    private static final long PROBE_TIMEOUT_MS = 4000L;

    private ModuleBridge bridge;
    private WatchLink link;
    private ResultReceiver resultReceiver;
    private CommandReceiver commandReceiver;
    private volatile boolean foregroundOk = false;
    private volatile boolean linkStarted = false;
    private volatile String moduleState = "未探测";
    private volatile String pendingProbeId = null;
    private final Handler probeHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        State.emit(this, "服务进程启动（:relay）");
        initRelay();
    }

    private void initRelay() {
        // 0.1.7：服务(重)建后清掉 State.emitOnce 去重表，确保状态面板重新上报全量状态，
        // 不会因上次进程残留的缓存而漏掉「已连接 / 已注册 / 就绪通告」这类关键行。
        State.clear();
        // 1) 前台服务：单独 try/catch，失败也要继续往下注册 SDK。
        // Android 14(targetSdk34) 起，两参 startForeground 且 manifest 未声明 type 会抛
        // MissingForegroundServiceTypeException（0.1.2 日志实证）。这里显式指定 dataSync
        // 类型：只需 normal 权限 FOREGROUND_SERVICE_DATA_SYNC，不触碰 connectedDevice 的
        // 蓝牙运行时权限校验，也不受 shortService 的 3 分钟超时限制。
        try {
            createChannel();
            Notification n = new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("腕控伴侣")
                    .setContentText("监听手表指令（单 APK 内置引擎）")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .build();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(1, n);
            }
            foregroundOk = true;
            State.emit(this, "前台服务：OK（dataSync）");
        } catch (Throwable t) {
            foregroundOk = false;
            Log.e(TAG, "startForeground 失败（不阻断 SDK 初始化）", t);
            State.emit(this, "前台服务：失败 " + t.getClass().getSimpleName()
                    + "（已跳过，不影响互联）");
        }

        // 2) 内置指令引擎 + 广播接收器：各自独立容错。
        try {
            String auth = getSharedPreferences("watchctl", MODE_PRIVATE)
                    .getString("auth_token", "watchctl-dev");
            bridge = new ModuleBridge(this, auth, this::onEngineResult);
            // S2：绝不把 authToken 明文写进状态行——「复制日志」会把全部日志一键发去排障，
            // 换成真实 token 后即随之外泄。这里只如实说明「是否已配置」，不含任何凭证值。
            State.emit(this, "指令引擎：已就绪（单 APK 内置 · 本地直调 · "
                    + (auth == null || auth.isEmpty() ? "未设置鉴权" : "已配置鉴权(不显示)") + "）");
        } catch (Throwable t) {
            State.emit(this, "指令引擎：失败 " + t.getClass().getSimpleName());
        }
        try {
            resultReceiver = new ResultReceiver();
            // 兼容路径：仍允许收 com.watchctl.RESULT（adb 自测/外部显式广播）。
            // API33+ 必须 RECEIVER_EXPORTED；三参重载仅 API33+ 存在，minSdk29 走两参分支。
            IntentFilter rf = new IntentFilter("com.watchctl.RESULT");
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(resultReceiver, rf, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(resultReceiver, rf);
            }
            commandReceiver = new CommandReceiver();
            IntentFilter cf = new IntentFilter();
            cf.addAction(ACTION_TEST_FRAME);
            cf.addAction(ACTION_COMPANION_SET);
            cf.addAction(ACTION_STATE_QUERY);
            cf.addAction(ACTION_MODULE_PROBE);
            registerReceiverCompat(commandReceiver, cf);
            State.emit(this, "广播接收器：OK");
        } catch (Throwable t) {
            State.emit(this, "广播接收器：失败 " + t.getClass().getSimpleName());
        }

        // 3) 互联链路：最关键，绝不能被前面任何一步失败牵连。
        try {
            link = createLink();
            link.setHandler(this::handleWatchFrame);
            link.connect();
            linkStarted = true;
            State.emit(this, "互联链路：已启动 link=" + link.state());
            Log.i(TAG, "companion 启动，link=" + link.state());
        } catch (Throwable t) {
            linkStarted = false;
            Log.e(TAG, "互联链路启动失败", t);
            State.emit(this, "互联链路：启动失败 " + t.getClass().getSimpleName()
                    + " " + String.valueOf(t.getMessage()));
        }
        reportState("状态快照");
        probeModule();   // 服务一启动就自检内置指令引擎（本地直调，不再有「另一进程」要探测）
    }

    /**
     * 指令引擎自检：本地直调一条 module.ping（不碰 dsh-go / WorkBuddy，秒回）。
     * 单 APK 后已没有「另一个进程有没有被广播唤醒」这一跳，自检只用于确认内置引擎可运行，
     * 并自证 root / WorkBuddy 安装 / 分享组件等外部条件。
     */
    private void probeModule() {
        if (bridge == null) {
            moduleState = "引擎未就绪 ✗";
            State.emit(this, "指令引擎自检：失败 ✗ 未就绪");
            return;
        }
        final String id = PROBE_PREFIX + System.currentTimeMillis();
        pendingProbeId = id;
        moduleState = "自检中…";
        State.emit(this, "指令引擎自检：已本地发出 module.ping …");
        try {
            bridge.sendProbe(id);
        } catch (Throwable t) {
            moduleState = "执行异常 ✗ " + t.getClass().getSimpleName();
            State.emit(this, "指令引擎自检：执行异常 ✗ " + t.getClass().getSimpleName());
            return;
        }
        probeHandler.removeCallbacksAndMessages(null);
        probeHandler.postDelayed(() -> {
            if (!id.equals(pendingProbeId)) return;   // 已收到回执
            pendingProbeId = null;
            moduleState = "无响应 ✗";
            State.emit(this, "指令引擎自检：无响应 ✗（本地直调，正常不会超时）");
            State.emit(this, "  → 点「重启服务」重试；若反复出现，复制全部状态给我看日志");
        }, PROBE_TIMEOUT_MS);
    }

    private void onProbeResult(boolean ok, String data) {
        if (pendingProbeId == null) return;
        pendingProbeId = null;
        probeHandler.removeCallbacksAndMessages(null);
        moduleState = ok ? "在线 ✓" : "回执异常 ✗";
        State.emit(this, "指令引擎自检：" + moduleState + "  " + (data == null ? "" : data));
        // 解析自检回执里的 WorkBuddy 通道能力自证，如实显示，不再用一句
        // 「指令经引擎写入 WorkBuddy」把两条通道混为一谈（那是 0.1.2 假成功的说法）。
        JSONObject d = null;
        try {
            JSONObject payload = new JSONObject(data == null ? "{}" : data);
            d = payload.optJSONObject("data");
        } catch (Throwable ignored) {}
        if (ok && d != null) {
            State.emit(this, "  ├ 发到 DSH → " + d.optString("dshVia", "引擎 session/prompt"));
            State.emit(this, "  └ 发到 WorkBuddy → " + d.optString("wbVia", "root am start 预填")
                    + "  ｜ root=" + (d.optBoolean("wbRoot", false) ? "可用 ✓" : "不可用 ✗")
                    + " 装了WB=" + (d.optBoolean("wbInstalled", false) ? "✓" : "✗")
                    + " 分享组件=" + (d.optBoolean("wbShareComponent", false) ? "✓" : "✗"));
            if (!d.optBoolean("wbRoot", false)) {
                State.emit(this, "  ⚠ WorkBuddy 通道需 root：KernelSU 里给「腕控伴侣」"
                        + "(com.example.watchctl，即本 App) 授权 root，再点「自检引擎」复测");
            }
            State.emit(this, "  ⚠ A1 真机结论：分享只把文本【预填进输入框】，需在手机点「发送」才执行");
        }
    }

    private WatchLink createLink() {
        try {
            return new XmsInterconnectLink(this);
        } catch (Throwable t) {
            State.emit(this, "XMS SDK 初始化异常，回退 LocalTestLink（仅自测用）");
            Log.w(TAG, "XMS SDK 初始化异常，回退 LocalTestLink", t);
            return new LocalTestLink();
        }
    }

    /** 上报一份当前状态快照（界面打开时也能看到，而不是只有历史事件）。 */
    private void reportState(String tag) {
        State.emit(this, "── " + tag + " ──");
        // 凭据配置状态（只报是否已配置，绝不报明文；在 :relay 进程读真实 SP，避免主进程缓存旧值）
        try {
            android.content.SharedPreferences sp = getSharedPreferences("watchctl", MODE_PRIVATE);
            boolean hasToken = !sp.getString("dsh_token", "").isEmpty();
            boolean hasCookie = !sp.getString("dsh_cookie", "").isEmpty();
            State.emit(this, "凭据状态：Token=" + (hasToken ? "已配置" : "未配置")
                    + "  Cookie=" + (hasCookie ? "已配置" : "未配置"));
        } catch (Throwable ignored) {}
        State.emit(this, "前台服务=" + (foregroundOk ? "OK" : "失败")
                + "  链路启动=" + (linkStarted ? "OK" : "失败")
                + "  link=" + (link != null ? link.state() : "null")
                + "  指令引擎=" + moduleState);
        if (link instanceof XmsInterconnectLink) {
            ((XmsInterconnectLink) link).reportState();
        }
    }

    /** 兼容 minSdk29：带 flags 的 3 参 registerReceiver 仅 API33+ 才有。 */
    private void registerReceiverCompat(BroadcastReceiver r, IntentFilter f) {
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(r, f, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(r, f);
        }
    }

    /**
     * S1：调试后门(TEST_FRAME)只放行只读命令。这些命令经 handleWatchFrame 走本地引擎，
     * 只查询引擎状态 / 会话 / 自检 / 握手，不执行 wb.prompt(root am start)、dsh.prompt、
     * dsh.cancel 等副作用指令，也不写任何配置。命令白名单是硬编码 equals，无通配、无前缀匹配。
     */
    private static boolean isReadonlyTestCmd(String cmd) {
        return "ping".equals(cmd) || "status".equals(cmd)
                || "dsh.list".equals(cmd) || "module.ping".equals(cmd);
    }

    /** 核心：把手表帧交给内置指令引擎（本地直调）。 */
    void handleWatchFrame(JSONObject frame) {
        try {
            String id = frame.optString("id", "w-" + System.currentTimeMillis());
            String cmd = frame.optString("cmd");
            JSONObject args = frame.optJSONObject("args");
            if (args == null) args = new JSONObject();

            if ("set".equals(cmd)) {
                // S1：auth_token 是本地鉴权凭证，不接受任何来自帧/广播的写入
                //（表端本就没有 set UI，此分支是历史遗留）。仅保留 dsh_token 更新。
                if (bridge == null) { State.emit(this, "指令引擎未就绪，丢弃 set 帧"); return; }
                if (args.has("auth_token")) {
                    State.emit(this, "已拒绝 set 帧写 auth_token（鉴权不接受外部改写）");
                }
                if (args.has("dsh_token")) bridge.setConfig("dsh_token", args.getString("dsh_token"));
                State.emit(this, "已写入配置（本应用内）");
                Log.i(TAG, "已写入配置（本应用内）");
                return;
            }
            // ping 握手：本地直回 pong，不经指令引擎。
            if ("ping".equals(cmd)) {
                JSONObject pong = new JSONObject();
                pong.put("id", id);
                pong.put("ok", true);
                pong.put("cmd", "ping");
                pong.put("text", "pong");
                pong.put("t", System.currentTimeMillis());   // 0.1.7：带时间戳，表端可核对回执新鲜度
                State.emit(this, "收到表端 ping → 本地回执 pong");
                Log.i(TAG, "ping → 本地回执 pong id=" + id);
                link.send(pong);
                return;
            }
            if (cmd == null || cmd.isEmpty()) {
                State.emit(this, "空 cmd，丢弃帧");
                return;
            }
            if (bridge == null) { State.emit(this, "指令引擎未就绪，丢弃 cmd=" + cmd); return; }
            bridge.sendCommand(id, cmd, args);
            State.emit(this, "→ 指令引擎 cmd=" + cmd + " id=" + id);
            Log.i(TAG, "→ 指令引擎 cmd=" + cmd + " id=" + id);
        } catch (Throwable t) {
            State.emit(this, "处理手表帧失败 " + t.getClass().getSimpleName());
            Log.e(TAG, "处理手表帧失败", t);
        }
    }

    /** 收到指令引擎结果 → 回写手表。 */
    void deliverToWatch(String id, boolean ok, JSONObject data) {
        try {
            String text = summarize(data, ok);
            JSONObject out = new JSONObject();
            out.put("id", id == null ? "" : id);
            out.put("ok", ok);
            out.put("text", text);
            // 0.1.6：把 cmd/target/stage 透传表端，表端才能把「发到 DSH」与
            // 「发到 WorkBuddy(预填)」分开提示，而不是一律显示「已完成」。
            out.put("cmd", data.optString("cmd", ""));
            JSONObject inner = data.optJSONObject("data");
            if (inner != null) {
                out.put("target", inner.optString("target", ""));
                out.put("stage", inner.optString("stage", ""));
                // 指令引擎幂等去重命中时透传，表端如实提示「重复指令已忽略」。
                if (inner.optBoolean("duplicate", false)) out.put("duplicate", true);
            }
            State.emit(this, "← 回写手表 " + out);
            Log.i(TAG, "← 手表 id=" + id + " " + out);
            link.send(out);
        } catch (Throwable t) {
            State.emit(this, "回写手表失败 " + t.getClass().getSimpleName());
            Log.e(TAG, "回写手表失败", t);
        }
    }

    private String summarize(JSONObject data, boolean ok) {
        try {
            if (!ok) {
                return "✗ " + data.optString("message", data.optString("error", "失败"));
            }
            String cmd = data.optString("cmd", "");
            JSONObject d = data.optJSONObject("data");
            if (d == null) d = new JSONObject();
            // 指令引擎回执里带如实的 text / delivered / via，优先采用，
            // 不再由中间层写死「已发送到 WB」这种与事实无关的文案（旧版假成功的一环）。
            String text = d.optString("text", "");
            if (!text.isEmpty()) return text;
            switch (cmd) {
                case "status":
                    return "DSH:" + d.optString("dsh", "?") + " 会话:" + d.optInt("sessions", 0)
                            + " WB:" + d.optString("workbuddy", "?");
                case "dsh.list":  return "会话数:" + d.optInt("count", 0);
                case "dsh.new":   return "新会话:" + d.optString("sessionId", "?");
                case "dsh.prompt": return "已写入 dsh-go 引擎会话";
                case "dsh.cancel": return "已中断 DSH";
                case "wb.prompt":
                    // 兜底（回执无 text 字段时）：wb 走 root am start 预填，
                    // 与引擎 session/prompt 无关。按 launched/stage 如实，绝不冒充送达。
                    return d.optBoolean("launched", false)
                            ? "已发到 WorkBuddy 输入框（预填）· 需点「发送」执行"
                            : "✗ WorkBuddy 入口未启动（看手机面板 root/组件）";
                default:          return d.toString();
            }
        } catch (Throwable t) {
            return data.toString();
        }
    }

    /**
     * 0.1.8：本地直调结果（ModuleBridge.ResultSink）与兼容广播结果汇到同一个入口，
     * 两条指令通道的如实判定逻辑只保留一份，避免文案/判定分叉。
     */
    void onEngineResult(String id, boolean ok, JSONObject payload) {
        JSONObject p = payload == null ? new JSONObject() : payload;
        // 自检回执只更新状态面板，不回写手表（表端没有对应 pending，会污染结果行）。
        if (id != null && id.startsWith(PROBE_PREFIX)) {
            onProbeResult(ok, p.toString());
            return;
        }
        JSONObject d = p.optJSONObject("data");
        String target = d == null ? "" : d.optString("target", "");
        String via = d == null ? "" : d.optString("via", "");
        boolean delivered = d != null && d.optBoolean("delivered", false);
        String stage = d == null ? "" : d.optString("stage", "");
        boolean duplicate = d != null && d.optBoolean("duplicate", false);
        // 两条通道各自如实标注，ok=true / delivered 不再混为一谈：
        //   dsh.prompt → 引擎会话（delivered=true 表示引擎已收下）
        //   wb.prompt  → WorkBuddy 预填（delivered 恒 false；launched 只证明 am start 发出）
        // duplicate=内置指令引擎幂等去重命中，如实标「已忽略」，不误判成「未送达」。
        String verdict;
        if (duplicate) {
            verdict = "  重复指令已忽略(幂等去重)";
        } else if ("WorkBuddy".equals(target) || "prefill".equals(stage)) {
            verdict = "  WorkBuddy=预填(需点发送) " + via;
        } else if (delivered) {
            verdict = "  dsh-go=已写入会话 " + via;
        } else {
            verdict = "  ✗ 未送达 " + (via.isEmpty() ? "（看引擎错误原因）" : via);
        }
        State.emit(this, "← 指令引擎结果 id=" + id + " ok=" + ok + verdict);
        try {
            deliverToWatch(id, ok, p);
        } catch (Throwable t) {
            Log.e(TAG, "解析结果失败", t);
        }
    }

    /** 兼容路径：仍允许外部用 com.watchctl.RESULT 显式广播做 adb 自测。 */
    class ResultReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            String id = i.getStringExtra("watchctl_id");
            boolean ok = i.getBooleanExtra("watchctl_ok", false);
            String ds = i.getStringExtra("watchctl_data");
            JSONObject payload;
            try { payload = new JSONObject(ds == null ? "{}" : ds); }
            catch (Throwable t) { payload = new JSONObject(); }
            onEngineResult(id, ok, payload);
        }
    }

    class CommandReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (ACTION_STATE_QUERY.equals(a)) {
                reportState("界面查询快照");
            } else if (ACTION_MODULE_PROBE.equals(a)) {
                probeModule();
                reportState("引擎自检后快照");
            } else if (ACTION_TEST_FRAME.equals(a)) {
                // S1：只读白名单。有副作用/写配置的帧一律挡在广播后门外，
                // 防止同机任意 App 借本服务自有 authToken 绕过校验执行 wb.prompt/dsh.prompt。
                String f = i.getStringExtra("frame");
                try {
                    JSONObject frame = new JSONObject(f == null ? "{}" : f);
                    String cmd = frame.optString("cmd", "");
                    if (!isReadonlyTestCmd(cmd)) {
                        State.emit(c, "TEST_FRAME 拒绝非只读命令 cmd=" + (cmd.isEmpty() ? "(空)" : cmd)
                                + "（后门仅放行 status/dsh.list/module.ping/ping）");
                        return;
                    }
                    handleWatchFrame(frame);
                } catch (Throwable t) {
                    State.emit(c, "TEST_FRAME 解析失败 " + t.getClass().getSimpleName());
                }
            } else if (ACTION_COMPANION_SET.equals(a)) {
                // S1：只允许经 adb 更新 dsh_token（引擎 token 常变，排障刚需）；
                // auth_token 一律拒绝广播改写，避免鉴权凭证被外部篡改。
                if (bridge == null) { State.emit(c, "指令引擎未就绪，忽略 COMPANION_SET"); return; }
                if (i.hasExtra("auth_token")) {
                    State.emit(c, "COMPANION_SET 已拒绝写 auth_token（凭证不接受广播改写）");
                }
                if (i.hasExtra("dsh_token")) {
                    bridge.setConfig("dsh_token", i.getStringExtra("dsh_token"));
                    State.emit(c, "COMPANION_SET 已更新 dsh_token");
                }
                // 0.1.21：手动设置/清除长期凭据。写空值 = 清除，下次发指令触发自愈重取。
                if (i.hasExtra("dsh_cookie")) {
                    bridge.setConfig("dsh_cookie", i.getStringExtra("dsh_cookie"));
                    State.emit(c, "COMPANION_SET 已更新 dsh_cookie");
                }
                // 写完后立即回一份状态，让界面凭据读数从「是否配置」即时刷新
                reportState("凭据已更新");
            }
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "腕控伴侣", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        reportState("界面打开");
        // 0.1.7：界面每次打开都立即重查节点 + 主动发一次 ready 通告。
        // 表端只要开着就会立刻完成握手，不必再赌「ping 恰好撞上就绪窗口」。
        // poke 只触发探测/通告，不重置已注册监听，安全可反复调用。
        if (link instanceof XmsInterconnectLink) {
            try { ((XmsInterconnectLink) link).poke(); }
            catch (Throwable t) { Log.w(TAG, "poke 失败（不阻断）", t); }
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        // A4/W6：注册可能失败（如 initRelay 早退），注销时对称加保护，避免抛 IllegalArgumentException。
        if (resultReceiver != null) {
            try { unregisterReceiver(resultReceiver); }
            catch (Throwable t) { Log.w(TAG, "注销 resultReceiver 失败（忽略）", t); }
        }
        if (commandReceiver != null) {
            try { unregisterReceiver(commandReceiver); }
            catch (Throwable t) { Log.w(TAG, "注销 commandReceiver 失败（忽略）", t); }
        }
        if (link != null) link.disconnect();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
