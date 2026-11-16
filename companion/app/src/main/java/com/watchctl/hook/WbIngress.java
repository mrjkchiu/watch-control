package com.watchctl.hook;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * WorkBuddy 真实文本入口（0.1.3）。
 *
 * 【为什么会有这个类】0.1.2 把 wb.prompt 并进了 dsh-go 引擎的 session/prompt，
 * 那是方向性错误：dsh-go 引擎(127.0.0.1:3080, Node) 与 WorkBuddy
 * (com.tencent.workbuddy.app, 腾讯 Flutter App) 是两个完全不同的东西。
 * 用户真机实测「dsh 写入成功、WorkBuddy 里完全没有」正是两者不同的铁证。
 *
 * 【真机验收过的唯一入口】：
 *   su -c 'am start -a android.intent.action.SEND -t text/plain \
 *          --es android.intent.extra.TEXT "<文本>" \
 *          -n com.tencent.workbuddy.app/.ShareAddToChat'
 *   把文本【预填进 WorkBuddy 会话输入框】，需手点一次「发送」才会执行。
 *
 * AOSP ActivityStarter.shouldAbortBackgroundActivityStart
 * 豁免 ROOT_UID / SHELL_UID / SYSTEM_UID，所以【root shell 执行的 am start】
 * 不受 Android 10+ / Android 16 后台启动 Activity 限制。这跟 0.1.2 删掉的
 * 「App 进程内 ctx.startActivity()」是两码事——被拦的是后者，不是这条入口。
 *
 * 【诚实的分级，绝不再造「假成功」】
 *   launched=true  = root am start 命令被系统接受（Activity 启动命令已发出）
 *   delivered      = 恒 false：代码无法自证「文本已进输入框」、更无法自证「已执行」
 *   stage=prefill  = 文本应预填在输入框，需在手机上点「发送」才执行
 * 回执带 am 原始输出片段，用户/面板可核对，不替 WorkBuddy 宣布成功。
 *
 * 【前置条件，可自证】本 App（:relay 进程）要有 KernelSU root 授权才能跑 su。
 * probe() 用 `id -u` 实测，面板如实显示 suAvailable，不靠猜。
 */
public final class WbIngress {
    private static final String TAG = "WatchCtl-WB";
    public static final String WB_PACKAGE = "com.tencent.workbuddy.app";
    public static final String SHARE_COMPONENT = ".ShareAddToChat";
    private static final int TEXT_MAX = 500;
    private static final int SU_TIMEOUT_SEC = 8;

    private WbIngress() {}

    static final class ShellOut {
        int code = -1;
        String text = "";
        boolean suStarted = false;   // su 进程能启动并跑完（不代表授权成功，看 id -u）
        boolean timedOut = false;
    }

    /** 能力探测：一次 su 调用拿到 root 是否可用 / WorkBuddy 是否安装 / 分享组件是否声明。 */
    static final class Probe {
        boolean suAvailable;
        boolean wbInstalled;
        boolean shareComponent;
        String detail = "";
    }

    /** 执行 root shell 脚本，带超时；用户文本绝不拼进脚本（由 shQuote 单独转义）。 */
    private static ShellOut runSu(String script) {
        ShellOut o = new ShellOut();
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
                        if (sb.length() < 8192) sb.append(line).append('\n');
                    }
                } catch (Throwable ignored) {}
            }, "watchctl-su-read");
            reader.setDaemon(true);
            reader.start();
            boolean done = p.waitFor(SU_TIMEOUT_SEC, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                o.timedOut = true;
                o.text = "su 超时(" + SU_TIMEOUT_SEC + "s)：可能 KernelSU 正在弹授权，或本 App 未获 root";
                Log.w(TAG, o.text);
                return o;
            }
            reader.join(500);
            o.code = p.exitValue();
            o.text = sb.toString().trim();
            o.suStarted = true;
        } catch (java.io.IOException e) {
            o.text = "su 不可用（本 App 未获 KernelSU root 授权？）：" + e.getMessage();
            Log.w(TAG, o.text);
        } catch (Throwable t) {
            o.text = "su 异常：" + t.getClass().getSimpleName() + " " + t.getMessage();
            Log.w(TAG, o.text);
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignored) {}
        }
        return o;
    }

    /**
     * 能力自证：不假设、不臆断。返回的三项都是真机跑出来的事实。
     * 供 module.ping 与 companion 状态面板显示，用户一看就知道 WorkBuddy 这条链卡在哪。
     */
    static Probe probe() {
        Probe pr = new Probe();
        String script = "id -u; echo __WC1__; pm path " + WB_PACKAGE
                + "; echo __WC2__; dumpsys package " + WB_PACKAGE
                + " | grep -c ShareAddToChat";
        ShellOut o = runSu(script);
        pr.detail = o.text.replace("\n", " | ");
        if (o.timedOut || !o.suStarted) return pr;   // su 起不来 → 三项全 false，如实
        String[] parts = o.text.split("__WC1__|__WC2__");
        if (parts.length >= 1) pr.suAvailable = parts[0].trim().equals("0");
        if (parts.length >= 2) pr.wbInstalled = parts[1].contains("package:");
        if (parts.length >= 3) {
            try { pr.shareComponent = Integer.parseInt(parts[2].trim().split("\\s+")[0]) > 0; }
            catch (Throwable ignored) { pr.shareComponent = parts[2].contains("ShareAddToChat"); }
        }
        return pr;
    }

    /**
     * 向 WorkBuddy 发一段文本：root shell am start（A1 已验收的入口）。
     * 返回 JSONObject：launched / stage / via / amOut / text；delivered 恒 false。
     * 失败时会抛 RelayException（由 CommandProcessor 统一回 ok=false 带原因）。
     */
    static org.json.JSONObject send(String rawText) throws Exception {
        String text = clean(rawText);
        if (text.isEmpty()) throw new RelayException("bad-args", "text 为空");

        // 先自证 root 与组件，避免「命令发出去了其实没授权」这种静默假成功
        Probe pr = probe();
        if (!pr.suAvailable) {
            throw new RelayException("wb-no-root",
                    "本 App 未获 root：请在 KernelSU 里给「腕控伴侣」(com.example.watchctl) 授予 root 后重试"
                            + (pr.detail.isEmpty() ? "" : "；探测=" + pr.detail));
        }
        if (!pr.wbInstalled) {
            throw new RelayException("wb-not-installed",
                    "未检测到 WorkBuddy(" + WB_PACKAGE + ")：pm path 无结果");
        }

        // 真机验收过的 intent；文本用 POSIX 单引号转义防注入
        String script = "am start -a android.intent.action.SEND -t text/plain"
                + " --es android.intent.extra.TEXT " + shQuote(text)
                + " -n " + WB_PACKAGE + "/" + SHARE_COMPONENT;
        ShellOut o = runSu(script);
        String amOut = clip(o.text, 240);

        boolean launched = o.suStarted && !o.timedOut
                && (o.text.contains("Starting: Intent") || o.text.contains("Starting: Intent { "))
                && !o.text.contains("Error")
                && !o.text.contains("Exception");
        if (!launched) {
            String why = o.timedOut ? "am start 超时" : ("am start 未成功：" + amOut);
            throw new RelayException("wb-am-start-failed", why);
        }

        org.json.JSONObject d = new org.json.JSONObject();
        d.put("target", "WorkBuddy");
        d.put("delivered", false);        // 关键：预填 ≠ 执行，绝不冒充送达
        d.put("launched", true);
        d.put("stage", "prefill");
        d.put("via", "root am start → .ShareAddToChat");
        d.put("shareComponent", pr.shareComponent);
        d.put("amOut", amOut);
        d.put("bytes", text.length());
        d.put("text", "已发到 WorkBuddy 输入框（预填）· 需在手机上点「发送」才会执行");
        Log.i(TAG, "WorkBuddy am start launched; amOut=" + amOut);
        return d;
    }

    /** 去掉换行/控制字符并限长，避免 am 参数被拆或注入。 */
    private static String clean(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length() && b.length() < TEXT_MAX; i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') { b.append(' '); continue; }
            if (c < 0x20 || c == 0x7f) continue;
            b.append(c);
        }
        return b.toString().trim();
    }

    /** POSIX sh 单引号转义：' → '\''。base 全是普通字符，杜绝 shell 注入。 */
    private static String shQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static String clip(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
