package com.watchctl.hook;

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

    private final String token;
    private String cookie = "";
    private long cookieAt = 0;
    private static final long COOKIE_TTL = 24L * 3600 * 1000;

    public DshClient(String token) {
        this.token = token == null ? "" : token;
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
            if (m != null) { cookie = m; cookieAt = System.currentTimeMillis(); }
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

    public void login() throws Exception {
        // 0.1.2：不再因「token 为空」硬抛。真机上很多本机 dsh-go 引擎对 loopback 免鉴权
        // （launch token 只作启动参数），或已带着有效 cookie。旧逻辑在 hasDshToken:false 时
        // 直接抛 dsh-no-token，会让 session/prompt 明明可写却被拒。这里改为「尽力拿 token」：
        // 有 token 就走 /?token= 登录（与已验证成功的路径完全一致），无 token 则访问根路径
        // 尝试取 cookie；取不到也不抛，继续让 POST 由引擎本人决定（401/403 再明确报错）。
        String path = token.isEmpty() ? "/" : "/?token=" + java.net.URLEncoder.encode(token, "UTF-8");
        Resp r = raw("GET", path, null, false);
        // 登录成功是 200 或 303
        if (!(r.code >= 200 && r.code < 400)) {
            throw new RelayException("dsh-login", "登录失败 HTTP " + r.code);
        }
        // cookie 为空不再判死：无 token + 本地引擎免鉴权时属正常，交给后续 POST 验证。
        Log.i(TAG, "dsh 登录流程完成 token=" + (!token.isEmpty()) + " cookie=" + (!cookie.isEmpty()));
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
        if ((r.code == 401 || r.code == 403) && !token.isEmpty()) {
            cookie = "";
            login();
            r = raw("POST", "/api/" + endpoint, env.toString(), false);
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
