package com.watchctl.hook;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.concurrent.TimeUnit;

/**
 * dsh-go 本机引擎凭据自愈（companion 0.1.14）。
 *
 * 【dsh-go 官方契约，packages/dsh_client/lib/src/connection.dart 原文】
 *   - launch token：每次引擎进程启动都重新随机生成，只打印一次到 stdout，
 *     磁盘日志 dsh.log 里被 LogRedactor 完整脱敏 → 跨进程读不到。
 *   - session cookie（dsh-auth-<hash>=v1.<payload>.<sig>）：签名密钥固定在
 *     $DSH_HOME/.credentials.yaml，跨 dsh web 重启有效 30 天。官方明确要求
 *     "Persist the cookie (bearer credential) and re-login only on 401"。
 *   所以正解是持久化 cookie，而不是每次抓易变的 token。
 *
 * 【自愈两级策略（都不改 dsh-go 数据目录，无副作用）】
 *   1) WebView cookie jar：dsh-go 用 webview_flutter 加载 ?token= 换到 cookie 后，
 *      cookie 落在其私有目录的 Cookies SQLite（name/value 分列）。用 strings
 *      分别提取 dsh-auth- 名与 v1. 值再拼成完整 cookie header。命中即返回——
 *      这是最优先、零中断的一条路。
 *   2) capture 脚本（保底）：若 WebView 里没有 cookie（如用户没打开过 dsh-go
 *      本机 Web 界面），用 root 执行内置的 dsh_capture.sh——它自己起一个
 *      dsh web 引擎进程、把明文 token（引擎自己打印到 stdout，不经过 App 的
 *      脱敏层）写到临时文件。companion 拿到 token 后走标准 GET /?token= 换
 *      cookie。这条路会短暂重启引擎，但能确定性拿到凭据。
 *
 * 恒不抛异常：拿不到就返回 ok()==false，由上层如实报错，绝不假成功。
 */
public final class DshSelfHeal {
    private static final String TAG = "WatchCtl-Heal";
    private static final String PKG = "io.github.ccch1mneyyy.dsh_app";
    private static final String CAPTURE_ASSET = "dsh_capture.sh";
    // 脚本释放到 App 私有 files 目录（/data/local/tmp 在 HyperOS 上 App 进程无写权限，
    // 会 FileNotFoundException）。App 对自己 files 目录绝对可写；root 执行时也能读。
    // 0.1.18：内存扫描回归纯 toybox shell（零外部依赖，单 APK 装了即用）。
    // 0.1.15/0.1.16 失败根因是端口反查逐个 readlink（fork 几千次）导致超时，已改批量 ls -l；
    // 抓候选改 tr -c + grep 整行精确 43 位（避免 grep -oE 把真 token 打碎）。
    // 两点均已用 busybox(toybox 同系) 与真机诊断输出双重验证。绝不依赖 Termux/Python。
    private static final String MEMSCAN_ASSET = "dsh_memscan.sh";
    private static final int SU_TIMEOUT_SEC = 8;
    private static final int CAPTURE_TIMEOUT_SEC = 40; // 起引擎需数秒
    private static final int MEMSCAN_TIMEOUT_SEC = 120; // 批量反查秒级 + 扫堆，留足余量

    private DshSelfHeal() {}

    /** 自愈结果：cookie 与 token 二选一（cookie 优先）；via 记录来源便于日志核对。 */
    public static final class Result {
        public final String cookie;
        public final String token;
        public final String via;
        Result(String cookie, String token, String via) {
            this.cookie = cookie; this.token = token; this.via = via;
        }
        public boolean ok() {
            return (cookie != null && !cookie.isEmpty()) || (token != null && !token.isEmpty());
        }
    }

    /**
     * 尝试恢复 dsh-go 凭据。恒不抛异常——拿不到就返回 ok()==false 的结果，
     * 让上层如实报错，而不是把自愈失败伪装成「已连接」。
     */
    public static Result recover(Context ctx) {
        // 策略 1：WebView cookie jar（只读、零中断）。
        String cookie = readWebViewCookie();
        if (cookie != null && !cookie.isEmpty()) {
            Log.i(TAG, "自愈命中 WebView dsh-auth cookie，长度=" + cookie.length());
            return new Result(cookie, null, "webview-cookie");
        }
        // 策略 2（0.1.15）：扫 dsh-go 进程内存抓 launch token（只读 /proc，无副作用）。
        // 这是用户真机已验证可行的取法——App 内置后无需 Termux/外部脚本。
        String token = scanTokenFromMemory(ctx);
        if (token != null && !token.isEmpty()) {
            Log.i(TAG, "自愈命中内存扫描 token，长度=" + token.length());
            return new Result(null, token, "memscan-token");
        }
        // 策略 3：capture 脚本自起引擎抓 token（有短暂中断，最后兜底）。
        token = captureToken(ctx);
        if (token != null && !token.isEmpty()) {
            Log.i(TAG, "自愈命中 capture token，长度=" + token.length());
            return new Result(null, token, "capture-token");
        }
        Log.w(TAG, "自愈未命中任何凭据：dsh-go 未运行，或 cookie/memscan/capture 均失败");
        return new Result(null, null, "none");
    }

    /**
     * 从 dsh-go WebView 的 Cookies SQLite 里读 dsh-auth cookie。
     * Android WebView cookie 库路径随版本不同；SQLite 里 name 与 value 是分列
     * 存储的 TEXT，strings 会把它们作为独立字符串输出，所以分别用正则提取
     * 最后的 dsh-auth-<hash> 名和 v1.<...> 值再拼成 "name=value"。
     */
    private static String readWebViewCookie() {
        String script =
            "for db in /data/user/0/" + PKG + "/app_webview/Default/Cookies " +
            "          /data/user/0/" + PKG + "/app_webview/Cookies " +
            "          /data/data/" + PKG + "/app_webview/Default/Cookies " +
            "          /data/data/" + PKG + "/app_webview/Cookies; do " +
            "  [ -f \"$db\" ] || continue; " +
            "  n=$(strings \"$db\" 2>/dev/null | grep -oE 'dsh-auth-[A-Za-z0-9_-]+' | tail -1); " +
            "  v=$(strings \"$db\" 2>/dev/null | grep -oE 'v1\\.[A-Za-z0-9._~%+/=-]{12,}' | tail -1); " +
            "  [ -n \"$n\" ] && [ -n \"$v\" ] && { echo \"$n=$v\"; break; }; " +
            "done";
        String out = su(script, SU_TIMEOUT_SEC);
        return clean(out);
    }

    /**
     * 释放内置的 capture 脚本到 /data/local/tmp 并以 root 执行。
     * 脚本自己起一个 dsh web 引擎进程、把明文 token 写到临时文件后打印
     * "TOKEN=xxx"。这里只负责取出 token；换 cookie 由 DshClient 走标准登录完成。
     */
    private static String captureToken(Context ctx) {
        try {
            byte[] script = readAsset(ctx, CAPTURE_ASSET);
            if (script == null || script.length == 0) {
                Log.w(TAG, "capture 脚本读取失败");
                return "";
            }
            // 0.1.16：同样释放到 App 私有 files 目录，避免 /data/local/tmp 写权限问题
            File f = new File(ctx.getFilesDir(), CAPTURE_ASSET);
            try (FileOutputStream fo = new FileOutputStream(f)) {
                fo.write(script);
            }
            String out = su("sh " + f.getAbsolutePath(), CAPTURE_TIMEOUT_SEC);
            if (out == null || out.isEmpty()) return "";
            for (String line : out.split("\\r?\\n")) {
                String s = line.trim();
                if (s.startsWith("TOKEN=")) return clean(s.substring(6));
            }
        } catch (Throwable t) {
            Log.w(TAG, "capture 异常：" + t.getClass().getSimpleName());
        }
        return "";
    }

    /**
     * 0.1.18：释放内置的 dsh_memscan.sh 到 App 私有 files 目录，以 root 用系统 sh 执行
     * （纯 toybox，零外部依赖）。脚本输出候选 token（每行一个 43 位串），其中绝大多数是
     * 内存里的 43 位干扰串（CSS 变量、SVG path、hash）——必须逐个 GET /?token= 用服务端
     * 303 验证，命中的才是真 token。HTTP 验证放 Java 层（不依赖 toybox 的 curl/wget）。
     * 恒不抛异常。
     */
    private static String scanTokenFromMemory(Context ctx) {
        try {
            byte[] script = readAsset(ctx, MEMSCAN_ASSET);
            if (script == null || script.length == 0) {
                Log.w(TAG, "memscan 脚本读取失败");
                return "";
            }
            File f = new File(ctx.getFilesDir(), MEMSCAN_ASSET);
            try (FileOutputStream fo = new FileOutputStream(f)) {
                fo.write(script);
            }
            String out = su("sh " + f.getAbsolutePath(), MEMSCAN_TIMEOUT_SEC);
            if (out == null || out.isEmpty()) {
                Log.w(TAG, "memscan 无输出（脚本未执行 / 无 root / 超时？）");
                return "";
            }
            int tried = 0;
            // P2 修复：逐个 verifyToken 每个最多 2.5s，若无总预算、dsh-go 卡住时
            // 400 个候选最坏阻塞 1000s。设 60s 总上限，超时如实放弃，不长时间占指令线程。
            final long deadline = System.currentTimeMillis() + 60000L;
            for (String line : out.split("\\r?\\n")) {
                if (System.currentTimeMillis() > deadline) {
                    Log.w(TAG, "memscan 验证达 60s 总预算，停止（tried=" + tried + "）");
                    break;
                }
                String tok = line.trim();
                if (!tok.matches("[A-Za-z0-9_-]{43}")) continue;
                if (++tried > 400) break;
                if (verifyToken(tok)) {
                    Log.i(TAG, "memscan 验证命中真 token（第 " + tried + " 个候选）");
                    return tok;
                }
            }
            Log.w(TAG, "memscan 扫到候选但逐一验证均未通过，tried=" + tried);
        } catch (Throwable t) {
            Log.w(TAG, "memscan 异常：" + t.getClass().getSimpleName());
        }
        return "";
    }

    /** GET /?token=<tok>，返回 true 当且仅当服务端 2xx/3xx（303=登录成功换到 cookie）。 */
    private static boolean verifyToken(String tok) {
        HttpURLConnection c = null;
        try {
            String url = "http://127.0.0.1:3080/?token=" + URLEncoder.encode(tok, "UTF-8");
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(2500);
            c.setReadTimeout(2500);
            c.setRequestMethod("GET");
            int code = c.getResponseCode();
            return code == 303 || (code >= 200 && code < 300);
        } catch (Throwable t) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static byte[] readAsset(Context ctx, String name) {
        try (InputStream in = ctx.getAssets().open(name)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 只读地跑一条 su 查询；失败/无 root/超时均返回空串，绝不阻塞用户。 */
    private static String su(String script, int timeoutSec) {
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", script)
                    .redirectErrorStream(true).start();
            final Process fp = p;
            final StringBuilder sb = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(fp.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (sb.length() < 32768) sb.append(line).append('\n');
                    }
                } catch (Throwable ignored) {}
            }, "watchctl-heal-su");
            reader.setDaemon(true);
            reader.start();
            boolean done = p.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!done) {
                Log.w(TAG, "自愈 su 查询超时(" + timeoutSec + "s)，清理子进程组");
                killTree(p);
                return "";
            }
            reader.join(400);
            return sb.toString();
        } catch (Throwable t) {
            return "";
        } finally {
            if (p != null) p.destroyForcibly();
        }
    }

    /**
     * P2 修复：su 超时后只 destroyForcibly() 杀 su 本体，它 fork 出的子进程
     * （capture 起的 dsh web 引擎）会残留占用 3080 端口，下次自愈/登录受影响。
     * 先 pkill -P 递归杀su的子进程，再杀 su 本身，避免引擎残留。
     * 恒不抛异常。
     */
    private static void killTree(Process p) {
        try {
            // Process.pid() 需 Java 9+；Android 兼容用反射取 java.lang.Process 的 pid
            java.lang.reflect.Field f = p.getClass().getDeclaredField("pid");
            f.setAccessible(true);
            int pid = f.getInt(p);
            if (pid > 0) {
                // 递归结束 su 的后代（含 capture 起的引擎），再结束 su 本体。
                // 用 2>/dev/null 抑制进程已退出的报错；pkill -P 只杀直接子进程，
                // 循环两轮覆盖「子进程的子进程」。不误杀无关进程。
                new ProcessBuilder("su", "-c",
                        "pkill -P " + pid + " 2>/dev/null; "
                        + "for c in $(pgrep -P " + pid + " 2>/dev/null); do pkill -P $c 2>/dev/null; done")
                        .redirectErrorStream(true).start().waitFor(2, TimeUnit.SECONDS);
            }
        } catch (Throwable ignored) {
        } finally {
            p.destroyForcibly();
        }
    }

    /** 取第一行有效输出并去掉空白/换行；过滤 su 报错噪声。 */
    private static String clean(String raw) {
        if (raw == null) return "";
        for (String line : raw.split("\\r?\\n")) {
            String s = line.trim();
            if (s.isEmpty()) continue;
            if (s.startsWith("/system/bin/sh:") || s.toLowerCase().contains("permission denied")) continue;
            return s;
        }
        return "";
    }
}
