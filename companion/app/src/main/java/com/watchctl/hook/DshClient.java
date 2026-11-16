package com.watchctl.hook;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * dsh-go 本机引擎客户端（127.0.0.1:3080）。
 * 协议与 relay/src/dsh.js 完全一致：
 *   - GET /?token=<launch token> → 303 + Set-Cookie: dsh-auth-...（登录）
 *   - POST /api/<endpoint> 带 client-request 信封；Host 由 URL 自动为 127.0.0.1:3080
 *   - 业务错误是 HTTP 200 + result.ok:false
 * 仅用 Android 自带 java.net + org.json，无外部依赖（Xposed compileOnly 之外）。
 */
public class DshClient {
    private static final String TAG = "WatchCtl";
    private static final String BASE = "http://127.0.0.1:3080";

    private final Context ctx;
    private String token;
    private String cookie = "";
    private long cookieAt = 0;

    /** 把自愈关键步骤投到手机端界面日志（人话、无明文）。ctx 为空（测试）时静默。 */
    private void ui(String s) {
        if (ctx != null) com.example.watchctl.State.emit(ctx, s);
    }
    // dsh-go cookie 官方有效期 30 天，签名密钥跨引擎重启稳定；这里留 20 天保守值，
    // 到期只是触发一次自愈重取，不影响 30 天窗口内的免维护。
    private static final long COOKIE_TTL = 20L * 24 * 3600 * 1000;

    /**
     * @param ctx      用于把拿到的 cookie 持久化到 SharedPreferences（可为 null，测试用）
     * @param token    dsh_token（launch token，易变，仅首次换 cookie 用）
     * @param savedCookie 上次持久化的 dsh_cookie（ bearer credential，跨引擎重启有效）
     */
    public DshClient(Context ctx, String token, String savedCookie) {
        this.ctx = ctx;
        this.token = token == null ? "" : token;
        this.cookie = savedCookie == null ? "" : savedCookie;
        // 已有持久化 cookie 视为新鲜，交给首次探活决定真伪，不额外触发登录
        this.cookieAt = this.cookie.isEmpty() ? 0 : System.currentTimeMillis();
    }

    /** 兼容旧调用（无持久化）——保留以免其它路径编译失败。 */
    public DshClient(String token) {
        this(null, token, "");
    }

    /** 把有效 cookie 落盘，dsh-go 更新/重启后仍可复用（30 天窗口内）。 */
    private void persistCookie(String c) {
        if (ctx == null || c == null || c.isEmpty()) return;
        try {
            SharedPreferences.Editor e = ctx.getSharedPreferences(Config.PREF_NAME, Context.MODE_PRIVATE).edit();
            e.putString("dsh_cookie", c);
            e.apply();
            Log.i(TAG, "dsh cookie 已持久化，长度=" + c.length());
        } catch (Throwable t) {
            Log.w(TAG, "dsh cookie 持久化失败：" + t.getClass().getSimpleName());
        }
    }

    private static class Resp {
        int code;
        String body;
        Resp(int code, String body) { this.code = code; this.body = body; }
    }

    private Resp raw(String method, String path, String body, boolean follow) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + path).openConnection();
        c.setRequestMethod(method);
        c.setInstanceFollowRedirects(follow);
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Accept", "application/json");
        if (!cookie.isEmpty()) c.setRequestProperty("Cookie", cookie);
        if (body != null) {
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setDoOutput(true);
        }
        if (body != null) {
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = c.getResponseCode();
        // 登录响应（200/303）里取 dsh-auth- cookie
        String sc = c.getHeaderField("Set-Cookie");
        if (sc != null && code >= 200 && code < 400) {
            String m = matchDshCookie(sc);
            if (m != null) {
                cookie = m;
                cookieAt = System.currentTimeMillis();
                persistCookie(cookie); // dsh-go 官方要求：cookie 是 bearer credential，拿到即持久化
            }
        }
        StringBuilder sb = new StringBuilder();
        InputStream is = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
        if (is != null) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
        }
        c.disconnect();
        return new Resp(code, sb.toString());
    }

    private static String matchDshCookie(String sc) {
        int i = sc.indexOf("dsh-auth-");
        if (i < 0) return null;
        int end = sc.indexOf(';', i);
        return end < 0 ? sc.substring(i) : sc.substring(i, end);
    }

    /**
     * 0.1.14 自愈：cookie 失效/缺失时才调用。先用 root 读 WebView cookie jar（零中断），
     * 读不到再用 capture 脚本自起引擎抓当前 token。拿到的凭据立即持久化。
     * 恒不抛异常、拿不到就维持原样，由后续 HTTP 结果定成败。
     */
    private void selfHeal() {
        ui("DSH 会话失效，正在自动重新获取凭据…");
        try {
            DshSelfHeal.Result h = DshSelfHeal.recover(ctx);
            if (h.cookie != null && !h.cookie.isEmpty()) {
                cookie = h.cookie;
                cookieAt = System.currentTimeMillis();
                persistCookie(cookie);
                Log.i(TAG, "dsh 自愈注入 cookie（" + h.via + "）");
                ui("✓ DSH 已自动找回会话凭据（来源：" + healViaText(h.via) + "）");
            } else if (h.token != null && !h.token.isEmpty()) {
                token = h.token;
                Log.i(TAG, "dsh 自愈更新 token（" + h.via + "）");
                ui("✓ DSH 已自动找回启动 Token（来源：" + healViaText(h.via) + "）");
            } else {
                ui("✗ DSH 自动获取失败，可在「DSH 凭据设置」里手动填入");
            }
        } catch (Throwable t) {
            Log.w(TAG, "dsh 自愈异常（忽略，走原流程）: " + t.getClass().getSimpleName());
            ui("✗ DSH 自动获取异常，可手动填入凭据");
        }
    }

    /** 把内部 via 代号翻成人话。 */
    private static String healViaText(String via) {
        if (via == null) return "自动";
        if (via.contains("webview")) return "应用内凭据";
        if (via.contains("memscan")) return "引擎内存扫描";
        if (via.contains("capture")) return "临时启动引擎抓取";
        return "自动";
    }

    /** 用当前 cookie 探活：200 即会话有效。失败返回 false 且不改状态。 */
    private boolean probeCookie() {
        if (cookie.isEmpty()) return false;
        try {
            Resp r = raw("GET", "/", null, false);
            if (r.code == 200) return true;
            Log.i(TAG, "dsh cookie 探活 HTTP " + r.code + " → 需重取");
        } catch (Throwable t) {
            Log.i(TAG, "dsh cookie 探活异常 → 需重取：" + t.getClass().getSimpleName());
        }
        return false;
    }

    /**
     * 0.1.14：会话cookie是长期凭据（官方 30 天跨重启），优先复用；仅当探活失败
     * 才自愈重取（WebView cookie 或 capture token）。重置 cookie 后走标准 /?token= 登录。
     */
    public void login() throws Exception {
        // 1) 已有 cookie（本次持久化加载或上次会话）先探活，200 直接用——这是常态，
        //    dsh-go 更新/重启后只要 DSH_HOME 不变，cookie 依旧有效，全程无需 token。
        if (probeCookie()) {
            Log.i(TAG, "dsh 复用持久化 cookie 登录成功");
            return;
        }

        // 2) cookie 无效/缺失 → 自愈（读 WebView cookie 或抓新 token）。
        selfHeal();

        // 3) 自愈拿到 cookie 的，再探活一次；成功即完成。
        if (probeCookie()) {
            Log.i(TAG, "dsh 自愈 cookie 探活成功");
            return;
        }

        // 4) 仍无效 → 用 token（可能是自愈 capture 抓到的新 token）走标准登录换 cookie。
        cookie = "";
        String path = token.isEmpty() ? "/" : "/?token=" + java.net.URLEncoder.encode(token, "UTF-8");
        Resp r = raw("GET", path, null, false);
        if (!(r.code >= 200 && r.code < 400)) {
            throw new RelayException("dsh-login", "登录失败 HTTP " + r.code + "（已尝试复用 cookie 与自愈重取，请确认 dsh-go 本机引擎正在运行）");
        }
        Log.i(TAG, "dsh 登录完成 token=" + (!token.isEmpty()) + " cookie=" + (!cookie.isEmpty()));
    }

    /** 发送一次 RPC，返回 result 对象（含 ok / value / error）。 */
    public JSONObject call(String endpoint, JSONObject args) throws Exception {
        if (cookie.isEmpty() || System.currentTimeMillis() - cookieAt > COOKIE_TTL) login();
        JSONObject env = new JSONObject();
        env.put("type", "client-request");
        env.put("rpcId", UUID.randomUUID().toString());
        env.put("method", endpoint);
        JSONObject payload = new JSONObject();
        payload.put("args", args == null ? new JSONObject() : args);
        env.put("payload", payload);

        Resp r = raw("POST", "/api/" + endpoint, env.toString(), false);
        // 0.1.14：401/403 = 服务端明确拒绝了当前 cookie（DSH_HOME 变更或真过期）。
        // 清掉失效 cookie 再 login：login 会走自愈重新获取有效凭据并持久化。
        if (r.code == 401 || r.code == 403) {
            cookie = "";
            try {
                login();
                r = raw("POST", "/api/" + endpoint, env.toString(), false);
            } catch (Throwable re) {
                throw new RelayException("dsh-relogin", "重登失败（dsh-go 凭据可能已变更）：" + re.getMessage());
            }
        }
        if (r.code != 200) throw new RelayException("dsh-http", "引擎返回 HTTP " + r.code);
        JSONObject body = new JSONObject(r.body);
        if (!"server-response".equals(body.optString("type"))) {
            throw new RelayException("dsh-protocol", "引擎响应缺少 server-response");
        }
        JSONObject result = body.optJSONObject("result");
        if (result == null || !result.optBoolean("ok", false)) {
            String msg = "引擎 ok:false";
            if (result != null && result.optJSONObject("error") != null) {
                msg = result.getJSONObject("error").optString("message", msg);
            }
            throw new RelayException("dsh-rpc", msg);
        }
        return result;
    }

    /** 等价于 call，但返回 result.value（可能是 JSONObject 或 JSONArray）。 */
    public Object callValue(String endpoint, JSONObject args) throws Exception {
        return call(endpoint, args).opt("value");
    }

    public JSONArray listSessions() throws Exception {
        Object v = callValue("session/list", new JSONObject().put("_request", new JSONObject()));
        if (v instanceof JSONArray) return (JSONArray) v;
        if (v instanceof JSONObject) {
            JSONObject o = (JSONObject) v;
            for (String k : new String[]{"sessions", "items", "rows"}) {
                if (o.optJSONArray(k) != null) return o.getJSONArray(k);
            }
        }
        return new JSONArray();
    }

    public String firstOrCreateSessionId() throws Exception {
        JSONArray a = listSessions();
        if (a.length() > 0) {
            JSONObject r = a.optJSONObject(0);
            if (r != null) {
                String s = r.optString("sessionId", r.optString("id", ""));
                if (!s.isEmpty()) return s;
            }
        }
        Object c = callValue("session/create", new JSONObject().put("request", new JSONObject()));
        if (c instanceof JSONObject) return ((JSONObject) c).optString("sessionId", "");
        return "";
    }
}
