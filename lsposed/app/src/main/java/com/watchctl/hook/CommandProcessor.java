package com.watchctl.hook;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 指令处理核心（0.1.1：从 RelayService.CmdReceiver 抽出，供静态 CommandReceiver 调用）。
 *
 * 之前 CmdReceiver 是 RelayService.onCreate() 里【动态注册】的：模块进程没起来 / 被
 * HyperOS 省电回收后，com.watchctl.CMD 广播无人接收，表端发指令必然 8s 超时「手机没回应」。
 * 现在由 Manifest 静态注册 CommandReceiver 接收（显式广播可唤醒进程），本类只负责纯逻辑，
 * 与 Service 生命周期彻底解耦。
 *
 * 链路：companion --(CMD, setPackage 模块)--> CommandReceiver --goAsync--> 本类
 *       --(RESULT, setPackage companion)--> companion.ResultReceiver --> BLE 回表
 *
 * 0.1.2 → 0.1.3 方向性纠错（用户真机实测：「WorkBuddy 里完全没有」）：
 *   0.1.2 犯了一个没证据的臆断——把「dsh-go 引擎会话」当成了「WorkBuddy」，
 *   于是把 wb.prompt 与 dsh.prompt 合并，都走引擎 session/prompt，再用
 *   「已写入 WorkBuddy 会话（引擎）」包装成成功。这是第 4 次同类假成功：
 *   dsh-go（127.0.0.1:3080 Node 引擎）与 WorkBuddy（com.tencent.workbuddy.app,
 *   腾讯 Flutter App）是【两个完全不同的东西】，写引擎会话根本不会出现在 WorkBuddy。
 *
 *   0.1.3 把两条通道彻底拆开，各自如实回执、互不冒充：
 *     · dsh.prompt  → DshClient session/prompt → dsh-go 引擎会话（真机已验证可写入）
 *                     target=dsh-go，delivered=true，文案「已写入 dsh-go 引擎会话」
 *     · wb.prompt   → WbIngress.send() → root shell `am start ... .ShareAddToChat`
 *                     （前期 scripts/wb-a1.sh A1 真机验收：文本预填进 WorkBuddy 输入框）
 *                     target=WorkBuddy，launched=true 但 delivered=false / stage=prefill
 *                     —— 预填 ≠ 执行，绝不再替 WorkBuddy 宣布「已送达/已写入」。
 *   两条通道的 ok/delivered/target/via/stage 都按各自事实填，不共用文案。
 */
public final class CommandProcessor {
    private static final String TAG = "WatchCtl";
    public static final String SET_ACTION = "com.watchctl.SET";

    // ── 0.1.4 指令幂等去重 ──────────────────────────────────────────────────
    // 0.1.13 表端引入「断线自动重连 + 指令排队补发」后，同一个 watchctl_id 的帧可能
    // 因「补发」「send 失败重发」「广播被系统重投」而到达模块两次。只读命令重复无害，
    // 但 wb.prompt 会真的 root am start 拉起 WorkBuddy、dsh.prompt 会真的写引擎会话，
    // 重复执行会实实在在产生两次动作。故对【有副作用】的命令按 id 幂等。
    // 有界(64)+有 TTL(2min)：既覆盖重连补发窗口，又不会无限增长；进程内静态表在
    // CommandReceiver 线程池并发下用 synchronized 保护。
    private static final int DEDUP_MAX = 64;
    private static final long DEDUP_TTL_MS = 120_000L;
    private static final Set<String> SIDE_EFFECT_CMDS = new HashSet<>(Arrays.asList(
            "dsh.prompt", "wb.prompt", "dsh.new", "dsh.cancel", "dsh.open"));
    // 保序 Map，便于淘汰最早的一条
    private static final LinkedHashMap<String, Long> SEEN = new LinkedHashMap<>();

    /** @return true=首次执行；false=重复（应回「已忽略」而非再执行一次）。 */
    private static synchronized boolean claimOnce(String id, String cmd) {
        if (id == null || id.isEmpty() || !SIDE_EFFECT_CMDS.contains(cmd)) return true;
        long now = System.currentTimeMillis();
        // 清掉过期条目（保序，遇到未过期即停）
        Iterator<Map.Entry<String, Long>> it = SEEN.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue() > DEDUP_TTL_MS) it.remove();
            else break;
        }
        if (SEEN.containsKey(id)) return false;
        SEEN.put(id, now);
        while (SEEN.size() > DEDUP_MAX) {
            String oldest = SEEN.keySet().iterator().next();
            SEEN.remove(oldest);
        }
        return true;
    }

    private CommandProcessor() {}

    /** 处理一条指令/配置广播，并把结果回写给 companion。 */
    public static void handle(Context appCtx, Intent intent) {
        Config cfg = Config.loadFromContext(appCtx);   // 每次读，SET 写入后立即生效
        String action = intent.getAction();
        if (SET_ACTION.equals(action)) {
            handleSet(appCtx, cfg, intent);
            return;
        }
        String token = intent.getStringExtra("watchctl_token");
        String cmd = intent.getStringExtra("watchctl_cmd");
        String argsJson = intent.getStringExtra("watchctl_args");
        String id = intent.getStringExtra("watchctl_id");

        if (token == null || !token.equals(cfg.authToken)) {
            Log.w(TAG, "丢弃无/错 token 的指令 cmd=" + cmd);
            // 仍回一条明确失败，让表端显示「token 不一致」而不是干等超时
            JSONObject err = new JSONObject();
            try {
                err.put("error", "token-mismatch");
                err.put("message", "模块与 companion 的 token 不一致（模块=" + cfg.authToken + "）");
            } catch (Throwable ignored) {}
            sendResult(appCtx, cfg, id, false, err);
            return;
        }
        if (cmd == null || cmd.trim().isEmpty()) {
            sendResult(appCtx, cfg, id, false, errObj("bad-args", "空 cmd"));
            return;
        }
        // 0.1.4 幂等：命中重复只回「已忽略」，绝不再次执行（尤其不再拉起 WorkBuddy）。
        // 回 ok=true + duplicate=true，让 companion/表端即时收口，而不是干等到超时。
        if (!claimOnce(id, cmd)) {
            Log.i(TAG, "重复指令已忽略（幂等）id=" + id + " cmd=" + cmd);
            JSONObject d = new JSONObject();
            try {
                d.put("duplicate", true);
                d.put("text", "重复指令已忽略（不会重复执行）");
            } catch (Throwable ignored) {}
            sendResult(appCtx, cfg, id, true, wrap(cmd, d));
            return;
        }
        JSONObject args = new JSONObject();
        try {
            if (argsJson != null && !argsJson.isEmpty()) args = new JSONObject(argsJson);
        } catch (Throwable ignored) { /* 用空 args 继续 */ }
        try {
            JSONObject result = dispatch(appCtx, cfg, cmd, args);
            sendResult(appCtx, cfg, id, true, result);
        } catch (Throwable t) {
            JSONObject err = new JSONObject();
            try {
                err.put("error", t instanceof RelayException ? ((RelayException) t).code : "error");
                err.put("message", String.valueOf(t.getMessage()));
            } catch (Throwable ignored) {}
            sendResult(appCtx, cfg, id, false, err);
            Log.e(TAG, "指令失败: " + cmd, t);
        }
    }

    private static JSONObject errObj(String code, String msg) {
        JSONObject o = new JSONObject();
        try { o.put("error", code); o.put("message", msg); } catch (Throwable ignored) {}
        return o;
    }

    private static void handleSet(Context ctx, Config cfg, Intent intent) {
        SharedPreferences.Editor e = ctx.getSharedPreferences(Config.PREF_NAME, Context.MODE_PRIVATE).edit();
        String v;
        if ((v = intent.getStringExtra("dsh_token")) != null) e.putString("dsh_token", v);
        if ((v = intent.getStringExtra("auth_token")) != null) e.putString("auth_token", v);
        if ((v = intent.getStringExtra("wb_package")) != null) e.putString("wb_package", v);
        if ((v = intent.getStringExtra("cmd_action")) != null) e.putString("cmd_action", v);
        if ((v = intent.getStringExtra("result_action")) != null) e.putString("result_action", v);
        e.putBoolean("enabled", true);
        e.apply();
        Log.i(TAG, "已写入配置 (dsh_token set=" + (intent.getStringExtra("dsh_token") != null) + ")");
        // 只有带 id 才回执（避免 companion 的 SET 探测污染表端结果行）
        String id = intent.getStringExtra("watchctl_id");
        if (id != null && !id.isEmpty()) {
            sendResult(ctx, cfg, id, true, wrap("set", new JSONObject()));
        }
    }

    private static JSONObject dispatch(Context ctx, Config cfg, String cmd, JSONObject args) throws Exception {
        // 模块在线探测：不碰网络、不拉起任何 Activity，纯本地秒回，供 companion 状态面板自证这一跳。
        // 字段一律自解释，避免把「框架 hook 开关」误读成「模块是否可用」。
        if ("module.ping".equals(cmd)) {
            JSONObject d = new JSONObject();
            d.put("pong", true);
            d.put("cmdChannel", "静态接收器(不依赖LSPosed激活)");
            // 0.1.3：两条通道如实分列，不再用一句 promptVia 把两者混为一谈
            d.put("dshVia", "DshClient → 引擎 session/prompt (127.0.0.1:3080)");
            d.put("wbVia", "root am start → .ShareAddToChat (预填, 需点发送)");
            // WorkBuddy 通道能力自证：root 是否可用 / WB 是否装了 / 分享组件是否在。
            // 不臆断、不假设——都是模块在真机上跑 su 得到的原始事实。
            try {
                WbIngress.Probe pr = WbIngress.probe();
                d.put("wbRoot", pr.suAvailable);
                d.put("wbInstalled", pr.wbInstalled);
                d.put("wbShareComponent", pr.shareComponent);
            } catch (Throwable t) {
                d.put("wbRoot", false);
                d.put("wbProbeError", t.getClass().getSimpleName());
            }
            d.put("frameworkHook", cfg.enabled);   // 仅后台拉起界面的放行开关，与指令链路无关
            d.put("wbPackage", cfg.wbPackage);
            d.put("hasDshToken", !cfg.dshToken.isEmpty());
            d.put("android", android.os.Build.VERSION.RELEASE);
            return wrap(cmd, d);
        }
        DshClient dsh = new DshClient(cfg.dshToken);
        switch (cmd) {
            case "status": {
                JSONObject d = new JSONObject();
                boolean dshOk = false;
                int sessions = 0;
                try { sessions = dsh.listSessions().length(); dshOk = true; }
                catch (Throwable ignored) { /* 离线不致命 */ }
                d.put("dsh", dshOk ? "在线" : "离线");
                d.put("sessions", sessions);
                d.put("dshTokenSet", !cfg.dshToken.isEmpty());
                d.put("workbuddy", "已配置(" + cfg.wbPackage + ")");
                return wrap(cmd, d);
            }
            case "dsh.list": {
                JSONArray rows = dsh.listSessions();
                JSONArray titles = new JSONArray();
                for (int i = 0; i < Math.min(rows.length(), 5); i++) {
                    JSONObject r = rows.optJSONObject(i);
                    titles.put(r == null ? "?" :
                            r.optString("title", r.optString("name",
                                    r.optString("sessionId", r.optString("id", "?")))));
                }
                JSONObject d = new JSONObject();
                d.put("count", rows.length());
                d.put("titles", titles);
                return wrap(cmd, d);
            }
            case "dsh.new": {
                JSONObject req = new JSONObject();
                if (args.has("workspaceId")) req.put("workspaceId", args.getString("workspaceId"));
                if (args.has("cwd")) req.put("cwd", args.getString("cwd"));
                Object v = dsh.callValue("session/create", new JSONObject().put("request", req));
                JSONObject d = new JSONObject();
                d.put("sessionId", v instanceof JSONObject ? ((JSONObject) v).optString("sessionId") : null);
                return wrap(cmd, d);
            }
            // 0.1.3：两个发送入口彻底拆成两条【不同目标、不同实现】的通道，互不冒充。
            case "dsh.prompt": {
                return submitPrompt(dsh, cmd, args);   // → dsh-go 引擎会话（真机已验证可写入）
            }
            case "wb.prompt": {
                // → WorkBuddy App：root shell am start 预填输入框（A1 真机验收入口）。
                // 与 DshClient / 引擎 session/prompt 无关，不再并进同一条通道。
                return wrap(cmd, WbIngress.send(args.optString("text")));
            }
            case "dsh.cancel": {
                String sid = args.optString("sessionId", "");
                if (sid.isEmpty()) sid = dsh.firstOrCreateSessionId();
                dsh.call("session/cancel", new JSONObject().put("request", new JSONObject().put("sessionId", sid)));
                JSONObject d = new JSONObject();
                d.put("sessionId", sid);
                d.put("text", "已中断当前会话");
                return wrap(cmd, d);
            }
            case "dsh.open": {
                String page = args.optString("page", "");
                if (page.isEmpty()) throw new RelayException("bad-args", "page 为空");
                Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("dshgo://open/" + page));
                i.setPackage(cfg.wbPackage);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                JSONObject d = new JSONObject();
                d.put("page", page);
                d.put("text", "已请求打开引擎页（后台启动可能被系统拦截，请到手机确认）");
                return wrap(cmd, d);
            }
            default:
                throw new RelayException("unknown-command", "未知命令: " + cmd);
        }
    }

    /**
     * 把一段文本写进【dsh-go 引擎会话】：POST /api/session/prompt（0.1.3 起仅服务 dsh.prompt）。
     * 只有引擎明确返回 ok 才算写入（否则抛异常 → 上层回 ok=false）。
     * 注意：这【不是】WorkBuddy——0.1.2 曾把这里写成「已写入 WorkBuddy 会话」，
     * 是把引擎会话冒充成 WorkBuddy 的假成功。0.1.3 文案与 target 一律如实标注 dsh-go。
     */
    private static JSONObject submitPrompt(DshClient dsh, String cmd, JSONObject args) throws Exception {
        String text = args.optString("text");
        if (text.isEmpty()) throw new RelayException("bad-args", "text 为空");
        String sid = args.optString("sessionId", "");
        if (sid.isEmpty()) sid = dsh.firstOrCreateSessionId();
        JSONObject req = new JSONObject();
        req.put("requestId", UUID.randomUUID().toString());
        req.put("sessionId", sid);
        req.put("mode", "queue");
        JSONArray content = new JSONArray();
        content.put(new JSONObject().put("type", "text").put("text", text));
        req.put("content", content);
        // dsh.call 在引擎 HTTP 非 200 / result.ok=false 时抛异常 → 外层回 ok=false。
        dsh.call("session/prompt", new JSONObject().put("request", req));
        JSONObject d = new JSONObject();
        d.put("target", "dsh-go");
        d.put("delivered", true);       // 引擎明确返回 ok = 文本已入会话队列
        d.put("via", "引擎 session/prompt (127.0.0.1:3080)");
        d.put("sessionId", sid);
        d.put("bytes", text.length());
        d.put("text", "已写入 dsh-go 引擎会话 · " + text.length() + " 字");
        return wrap(cmd, d);
    }

    private static JSONObject wrap(String cmd, JSONObject data) {
        JSONObject o = new JSONObject();
        try { o.put("cmd", cmd); o.put("data", data); } catch (Throwable ignored) {}
        return o;
    }

    /**
     * 结果回写：显式定向 companion 包 + 允许送达 stopped 包 + 前台队列加速。
     * 旧版不限定包名发隐式广播，在 Android 8+/HyperOS 上跨应用投递容易被拦，
     * companion 收不到 → 表端等不到回执 → 超时。
     */
    private static void sendResult(Context ctx, Config cfg, String id, boolean ok, JSONObject data) {
        try {
            Intent out = new Intent(cfg.resultAction);
            out.setPackage(Config.COMPANION_PACKAGE);
            out.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            out.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            out.putExtra("watchctl_id", id == null ? "" : id);
            out.putExtra("watchctl_ok", ok);
            out.putExtra("watchctl_data", data.toString());
            ctx.sendBroadcast(out);
            Log.i(TAG, "回执 → companion id=" + id + " ok=" + ok);
        } catch (Throwable t) {
            Log.e(TAG, "回写结果失败", t);
        }
    }
}
