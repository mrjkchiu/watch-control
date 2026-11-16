package com.example.watchctl;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 腕控 · 互联控制台（0.1.9 UI 全面重做，impeccable：distill / typography / color / spatial / ux-writing）。
 *
 * 只改显示层。链路代码 / CompanionService / XmsInterconnectLink / 广播协议一行未动：
 *   - 仍动态注册 State.ACTION，收 ":relay" 进程发来的 "HH:mm:ss  + 文本" 行；
 *   - 仍发 ACTION_STATE_QUERY / ACTION_MODULE_PROBE，仍 startForegroundService 拉起服务；
 *   - 仍保留 120 行上限。
 *
 * 本轮新增（纯显示层，全部基于真实状态行解析，绝不编造状态）：
 *   1. 顶部「当前链路」大状态 + 5 行链路读数：从 reportState() 的真实文本行解析出
 *      link / 前台服务 / node / listen / perm / 指令引擎 / 收发帧，解析不到就显示「—」；
 *      "已连接" 只在真收到表端帧回执(pong/回写成功)时才给出，否则如实显示"已就绪/检测中/未连接"。
 *   2. 说明书收进折叠区，默认收起（渐进式披露），首屏让位给状态。
 *   3. 按钮 weight 等分，不再横向溢出。
 *   4. 日志语义着色（✓绿/✗红/警告琥珀，符号+颜色双重编码）、时间戳弱化、
 *      "全部 / 仅异常" 筛选、新日志自动滚到最新（不会把状态读数顶出视野）。
 *   5. "复制日志" 只复制运行日志，不再把整篇说明塞进剪贴板。
 *
 * 0.1.16 主次修正：删除 0.1.15 那个「自定义指令 · 手机端输入」折叠区与录音权限申请。
 *   手机是被控端，不参与指令输入；自定义指令全部在手表端内置输入面板本地完成。
 *   本 Activity 回到「纯状态面板」，只做查询 / 自检 / 复制日志 / 重启服务四件事。
 */
public class MainActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private BroadcastReceiver receiver;

    private TextView tvState, tvStateSub, tvVer;
    private View heroBar;
    private TextView tvFg, tvNode, tvListen, tvEngine, tvFrames;
    private TextView tvGuideToggle, tvGuideBody, tvFilter, tvVerbosity;
    private ScrollView logScroll;
    private TextView tvLog;

    // 凭据手动入口（纯新增显示层）
    private TextView credToggle, credStatus, credSave, credClearToken, credClearCookie;
    private View credBody;
    private EditText credToken, credCookie;
    private boolean credOpen = false;

    // 运行日志显示模式：true=人话（过滤+润色），false=原文（完整技术行，排障用）
    private boolean humanLog = true;

    // 最近 120 行原始日志（含 "HH:mm:ss  " 前缀），用于重绘与复制
    private final ArrayList<String> logLines = new ArrayList<>();
    private boolean onlyIssues = false;
    private boolean guideOpen = false;
    private boolean autoScrollPending = false;

    // 各项链路读数（来自 State 行解析；null = 尚未拿到，显示「—」）
    private static final int ST_UNKNOWN = 0, ST_OK = 1, ST_BAD = 2;
    private int fgState = ST_UNKNOWN;      private String fgText = "";
    private int nodeState = ST_UNKNOWN;    private String nodeText = "";
    private int listenState = ST_UNKNOWN;  private String listenText = "";
    private int engineState = ST_UNKNOWN;  private String engineText = "";
    private String framesText = "";
    private String healthText = "";
    private boolean linkOnline = false;
    private boolean linkSeen = false;
    private boolean frameEchoSeen = false;   // 真收到过表端帧/pong 回写 = 双向已验证
    private boolean anyBad = false;

    // 颜色（从 colors.xml 读取，保持与资源一致；语义色与 ✓/✗ 符号双重编码）
    private int cInk, cInk2, cInk3, cOk, cBad, cWarn, cIdle, cAccent;

    // 解析用正则（都是 reportState / XmsInterconnectLink.reportState / State.emit 的真实文案）
    private static final Pattern P_LINK     = Pattern.compile("link=(\\w+)");
    private static final Pattern P_NODE     = Pattern.compile("node=(\\S+)");
    private static final Pattern P_NODE_ID  = Pattern.compile("手表节点：已找到 nodeId=(\\d+)");
    private static final Pattern P_FRAMES   = Pattern.compile("收发：收到帧 (\\d+) / 回写 (\\d+) / 错误 (\\d+)");
    private static final Pattern P_HEALTH   = Pattern.compile("链路健康：最后表端帧=(\\S+)");
    private static final Pattern P_LISTEN   = Pattern.compile("listen=(\\S+)");
    private static final Pattern P_PERM     = Pattern.compile("perm=(\\S+)");

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        cInk    = getColorCompat(R.color.ink);
        cInk2   = getColorCompat(R.color.ink_2);
        cInk3   = getColorCompat(R.color.ink_3);
        cOk     = getColorCompat(R.color.ok);
        cBad    = getColorCompat(R.color.bad);
        cWarn   = getColorCompat(R.color.warn);
        cIdle   = getColorCompat(R.color.idle);
        cAccent = getColorCompat(R.color.accent);

        tvState = findViewById(R.id.tv_state);
        tvStateSub = findViewById(R.id.tv_state_sub);
        tvVer = findViewById(R.id.tv_ver);
        heroBar = findViewById(R.id.hero_bar);
        tvFg = findViewById(R.id.tv_m_fg);
        tvNode = findViewById(R.id.tv_m_node);
        tvListen = findViewById(R.id.tv_m_listen);
        tvEngine = findViewById(R.id.tv_m_engine);
        tvFrames = findViewById(R.id.tv_m_frames);
        tvGuideToggle = findViewById(R.id.tv_guide_toggle);
        tvGuideBody = findViewById(R.id.tv_guide_body);
        tvFilter = findViewById(R.id.tv_filter);
        tvVerbosity = findViewById(R.id.tv_verbosity);
        logScroll = findViewById(R.id.log_scroll);
        tvLog = findViewById(R.id.tv_log);

        credToggle = findViewById(R.id.cred_toggle);
        credBody = findViewById(R.id.cred_body);
        credStatus = findViewById(R.id.cred_status);
        credToken = findViewById(R.id.cred_token);
        credCookie = findViewById(R.id.cred_cookie);
        credSave = findViewById(R.id.cred_save);
        credClearToken = findViewById(R.id.cred_clear_token);
        credClearCookie = findViewById(R.id.cred_clear_cookie);

        // 版本号从系统 PackageManager 读真实值，绝不硬编码/漂移（AGP 8 默认不生成 BuildConfig）
        try { tvVer.setText(versionName() + " · 互联控制台"); } catch (Throwable ignore) {}

        // 主操作：重新查询状态（动词+宾语，具体）
        findViewById(R.id.btn_refresh).setOnClickListener(v -> requestState());
        // 三个次级操作
        findViewById(R.id.btn_probe).setOnClickListener(v -> probeEngine());
        findViewById(R.id.btn_copy).setOnClickListener(v -> copyLog());
        findViewById(R.id.btn_restart).setOnClickListener(v -> {
            startServiceSafely();
            toast("已请求重启服务");
        });

        // 说明书渐进式披露
        tvGuideToggle.setOnClickListener(v -> {
            guideOpen = !guideOpen;
            tvGuideBody.setVisibility(guideOpen ? View.VISIBLE : View.GONE);
            tvGuideToggle.setText(guideOpen ? "使用说明与排障    ▴ 收起" : "使用说明与排障    ▾ 展开");
        });

        // 日志筛选：全部 / 仅异常
        tvFilter.setOnClickListener(v -> {
            onlyIssues = !onlyIssues;
            tvFilter.setText(onlyIssues ? "仅异常" : "全部");
            tvFilter.setTextColor(onlyIssues ? cAccent : cInk2);
            renderLog(false);
        });

        // 日志显示模式：人话 / 原文
        tvVerbosity.setOnClickListener(v -> {
            humanLog = !humanLog;
            tvVerbosity.setText(humanLog ? "人话" : "原文");
            tvVerbosity.setTextColor(humanLog ? cAccent : cInk2);
            renderLog(false);
        });

        // 凭据面板渐进式披露
        credToggle.setOnClickListener(v -> {
            credOpen = !credOpen;
            credBody.setVisibility(credOpen ? View.VISIBLE : View.GONE);
            credToggle.setText(credOpen
                    ? "DSH 凭据设置（手动 / 自愈测试）    ▴ 收起"
                    : "DSH 凭据设置（手动 / 自愈测试）    ▾ 展开");
        });

        // 保存凭据：复用 COMPANION_SET 通道写本应用 SP（在 :relay 进程内写，下条指令即生效）
        credSave.setOnClickListener(v -> {
            String token = textOf(credToken);
            String cookie = textOf(credCookie);
            if (token.isEmpty() && cookie.isEmpty()) {
                toast("两个框都为空，未改动（清空请点对应「清除」按钮）");
                return;
            }
            Intent i = new Intent(CompanionService.ACTION_COMPANION_SET);
            i.setPackage(getPackageName());
            if (!token.isEmpty()) i.putExtra("dsh_token", token);
            if (!cookie.isEmpty()) i.putExtra("dsh_cookie", cookie);
            sendBroadcast(i);
            credToken.setText("");
            credCookie.setText("");   // 明文不留在输入框
            append("✓ 凭据已保存，下一条指令起生效（明文不回显）");
            toast("已保存，下一条指令起生效");
        });

        // 清除 Token：写空值 → 下次发指令触发自愈重扫（测试用）
        credClearToken.setOnClickListener(v -> {
            Intent i = new Intent(CompanionService.ACTION_COMPANION_SET);
            i.setPackage(getPackageName());
            i.putExtra("dsh_token", "");
            sendBroadcast(i);
            append("✓ 已清除 Token，下次发指令会触发自愈重新获取");
            toast("已清除 Token");
        });

        // 清除 Cookie：写空值 → 下次发指令触发自愈重新登录（测试用）
        credClearCookie.setOnClickListener(v -> {
            Intent i = new Intent(CompanionService.ACTION_COMPANION_SET);
            i.setPackage(getPackageName());
            i.putExtra("dsh_cookie", "");
            sendBroadcast(i);
            append("✓ 已清除 Cookie，下次发指令会触发自愈重新获取");
            toast("已清除 Cookie");
        });

        // 动态注册状态广播（显式广播，setPackage 已限定本应用）
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                String line = i.getStringExtra(State.EXTRA_LINE);
                if (line != null) append(line);
            }
        };
        IntentFilter f = new IntentFilter(State.ACTION);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, f);
        }

        append("界面已打开，正在拉取服务状态…");
        startServiceSafely();
        ui.postDelayed(this::requestState, 600);
    }

    private int getColorCompat(int res) {
        if (Build.VERSION.SDK_INT >= 23) return getColor(res);
        //noinspection deprecation
        return getResources().getColor(res);
    }

    /** 从系统读真实 versionName（AGP 8 默认不生成 BuildConfig，故不用 BuildConfig.VERSION_NAME）。
     *  A3：兜底绝不硬编码具体版本号——升版后旧兜底会显示成过期版本，造成误判；读不到就如实说「版本未知」。 */
    private String versionName() {
        try {
            String v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return (v == null || v.isEmpty()) ? "版本未知" : v;
        } catch (Throwable t) {
            return "版本未知";
        }
    }

    private void startServiceSafely() {
        try {
            startForegroundService(new Intent(this, CompanionService.class));
        } catch (Throwable t) {
            append("启动服务异常: " + t.getClass().getSimpleName() + " " + t.getMessage());
        }
    }

    private void requestState() {
        try {
            Intent i = new Intent(CompanionService.ACTION_STATE_QUERY);
            i.setPackage(getPackageName());
            sendBroadcast(i);
        } catch (Throwable t) {
            append("状态查询失败: " + t.getClass().getSimpleName());
        }
    }

    private void probeEngine() {
        try {
            Intent i = new Intent(CompanionService.ACTION_MODULE_PROBE);
            i.setPackage(getPackageName());
            sendBroadcast(i);
            toast("已发自检，看「指令引擎」一行与运行日志");
        } catch (Throwable t) {
            append("引擎自检失败: " + t.getClass().getSimpleName());
        }
    }

    /** 复制：只带运行日志 + 一行设备/版本头，不再把整篇 GUIDE 塞进剪贴板。 */
    private void copyLog() {
        StringBuilder sb = new StringBuilder();
        sb.append("腕控 companion ").append(versionName())
          .append(" · 运行日志（").append(logLines.size()).append(" 行）\n");
        for (String l : logLines) sb.append(l).append('\n');
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("watchctl-log", sb.toString()));
            toast("已复制 " + logLines.size() + " 行运行日志，可直接发我排障");
        }
    }

    private void append(String line) {
        ui.post(() -> {
            // A5：只在用户原本就贴在底部时才跟随新日志滚动。
            // 用户手动往上翻看历史时，新日志不再把视图强行拉回底部（读日志排障是核心用途）。
            // 判断必须在 setText 重建内容之前，基于当前滚动位置计算。
            boolean follow = isLogAtBottom();
            logLines.add(line);
            if (logLines.size() > 120) logLines.remove(0);
            parseState(line);
            renderHero();
            renderLog(follow);
        });
    }

    /** 日志是否贴在底部：内容底部 - (当前滚动 + 可视高度) 在容差内即视为贴底。 */
    private boolean isLogAtBottom() {
        android.view.View child = logScroll.getChildAt(0);
        if (child == null) return true;   // 首屏还没内容，跟随到底
        int diff = child.getBottom() - (logScroll.getScrollY() + logScroll.getHeight());
        return diff <= 24;                // 24px 容差，避免差一两像素就判定为「离开底部」
    }

    /**
     * 从真实状态行解析链路读数。全部是 contains / 正则匹配 State 广播里的原始文本，
     * 不臆断、不假设：解析不到的项保持「—」，"已连接" 只在 frameEchoSeen 时给出。
     */
    private void parseState(String line) {
        // 凭据配置状态（只显示是否已配置，绝不含明文）
        if (line.contains("凭据状态：")) {
            renderCredStatus(line);
        }
        // link=online / offline
        Matcher ml = P_LINK.matcher(line);
        if (ml.find()) {
            linkSeen = true;
            linkOnline = "online".equalsIgnoreCase(ml.group(1));
        }
        // 前台服务：OK（dataSync） / 失败
        if (line.contains("前台服务：OK") || line.contains("前台服务=OK")) {
            fgState = ST_OK; fgText = "✓ dataSync";
        } else if (line.contains("前台服务") && (line.contains("失败") || line.contains("✗"))) {
            fgState = ST_BAD; fgText = "✗ 失败"; anyBad = true;
        }
        // node= / 手表节点：已找到 nodeId=
        Matcher mn = P_NODE.matcher(line);
        if (mn.find() && !"无".equals(mn.group(1)) && !"null".equals(mn.group(1))) {
            nodeState = ST_OK; nodeText = "✓ " + mn.group(1);
        } else {
            Matcher mn2 = P_NODE_ID.matcher(line);
            if (mn2.find()) { nodeState = ST_OK; nodeText = "✓ " + mn2.group(1); }
        }
        // listen= / perm= → 合并成一行「权限 / 监听」
        Matcher mli = P_LISTEN.matcher(line);
        Matcher mp = P_PERM.matcher(line);
        String listen = mli.find() ? mli.group(1) : "";
        String perm = mp.find() ? mp.group(1) : "";
        if (!listen.isEmpty() || !perm.isEmpty()) {
            boolean okL = "已注册".equals(listen);
            boolean okP = perm.contains("已授予");
            boolean badL = listen.contains("未注册") || listen.contains("失败") || perm.contains("denied") || perm.contains("待授予");
            listenState = (okL && okP) ? ST_OK : (badL ? ST_BAD : ST_UNKNOWN);
            listenText = (okL && okP) ? "✓ 已就绪" : (badL ? "✗ 未就绪" : listen + " " + perm);
            if (badL) anyBad = true;
        }
        // 指令引擎：在线 ✓ / 已就绪 / 失败 / 无响应
        if (line.contains("指令引擎=") || line.contains("指令引擎：") || line.contains("指令引擎自检：")) {
            if (line.contains("在线") || line.contains("已就绪")) {
                engineState = ST_OK; engineText = "✓ 在线";
            } else if (line.contains("失败") || line.contains("无响应") || line.contains("✗")) {
                engineState = ST_BAD; engineText = "✗ 异常"; anyBad = true;
            }
        }
        // 收发帧 + 链路健康
        Matcher mf = P_FRAMES.matcher(line);
        if (mf.find()) {
            framesText = mf.group(1) + " / 回写 " + mf.group(2);
        }
        Matcher mh = P_HEALTH.matcher(line);
        if (mh.find()) {
            healthText = mh.group(1);
        }
        // 双向已验证的真实判据：收到表端 ping 回执 / 回写表端成功
        if (line.contains("收到表端 ping") || line.contains("本地回执 pong")
                || (line.contains("回写表端") && line.contains("成功"))
                || line.contains("← 收到表端帧")) {
            frameEchoSeen = true;
        }
        // 任何明确的错误行
        if (line.contains("✗") || line.contains("失败") || line.contains("异常")
                || line.contains("超时") || line.contains("无响应") || line.contains("permission denied")) {
            anyBad = true;
        }
    }

    /** 顶部大状态：绝不编造。只有真收到回执才给「已连接」。 */
    private void renderHero() {
        String state;
        String sub;
        int color;
        if (frameEchoSeen && linkOnline) {
            state = "已连接";
            sub = "手表 ↔ 手机双向通道已验证" + (healthText.isEmpty() ? "" : " · 最近帧 " + healthText + "前");
            color = cOk;
        } else if (linkOnline && listenState == ST_OK) {
            state = "已就绪";
            sub = "手机端已就绪，等待表端帧";
            color = cAccent;
        } else if (linkSeen && !linkOnline) {
            state = "未连接";
            sub = "穿戴链路 offline · 等待手机端就绪";
            color = cBad;
        } else if (anyBad) {
            state = "未就绪";
            sub = "有环节失败，看「运行日志」里带 ✗ 的行";
            color = cBad;
        } else {
            state = "检测中";
            sub = "正在拉取服务状态…";
            color = cIdle;
        }
        tvState.setText(state);
        tvState.setTextColor(color);
        tvStateSub.setText(sub);
        heroBar.setBackgroundColor(color);

        setReading(tvFg, fgState, fgText);
        setReading(tvNode, nodeState, nodeText);
        setReading(tvListen, listenState, listenText);
        setReading(tvEngine, engineState, engineText);
        // 收发帧是中性事实，用主文字色，不用语义色（避免误导成"通过/失败"）
        tvFrames.setText(framesText.isEmpty() ? "—" : framesText);
        tvFrames.setTextColor(framesText.isEmpty() ? cIdle : cInk);
    }

    private void setReading(TextView v, int st, String text) {
        if (st == ST_OK) { v.setText(text); v.setTextColor(cOk); }
        else if (st == ST_BAD) { v.setText(text); v.setTextColor(cBad); }
        else { v.setText(text.isEmpty() ? "—" : text); v.setTextColor(cIdle); }
    }

    /**
     * 重绘日志：语义着色 + 时间戳弱化 + 筛选 + 自动滚到底。
     * 颜色只作辅助：✓/✗/「失败」等文字符号仍在，色盲用户也能读（不靠颜色单独传达）。
     */
    private void renderLog(boolean follow) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (String line : logLines) {
            if (onlyIssues && !isIssue(line)) continue;
            // 人话模式：把技术行翻成人话；返回 null = 纯噪声，隐藏。
            // 只作用于显示，logLines 原始行不动 —— parseState 读数、复制排障都基于原文。
            String shown = line;
            if (humanLog) {
                shown = humanize(line);
                if (shown == null) continue;
            }
            int start = sb.length();
            sb.append(shown).append('\n');
            int end = sb.length();
            int c = colorFor(humanLog ? shown : line);
            sb.setSpan(new ForegroundColorSpan(c), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            // 时间戳 "HH:mm:ss" 前 8 字符弱化（等宽对齐的前提下增加层次）
            int tsLen = Math.min(8, shown.length());
            if (tsLen > 0 && shown.substring(0, tsLen).matches("\\d{2}:\\d{2}:\\d{2}")) {
                sb.setSpan(new ForegroundColorSpan(cInk3), start, start + tsLen,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        tvLog.setText(sb);
        if (follow) {
            // post 到下一帧，等 TextView 完成测量再滚；只滚日志 ScrollView，不打扰上方状态读数
            if (!autoScrollPending) {
                autoScrollPending = true;
                logScroll.post(() -> {
                    autoScrollPending = false;
                    logScroll.fullScroll(View.FOCUS_DOWN);
                });
            }
        }
    }

    private boolean isIssue(String line) {
        return line.contains("✗") || line.contains("失败") || line.contains("异常")
                || line.contains("超时") || line.contains("无响应") || line.contains("错误")
                || line.contains("预填") || line.contains("需点发送") || line.contains("⚠")
                || line.contains("denied") || line.contains("待授予");
    }

    /**
     * 运行日志「人话化」：把 reportState / XmsInterconnectLink 的技术行翻成常人能懂的短句。
     * 返回 null = 纯内部噪声（快照分隔、计数、ping/pong、原始 JSON 帧…），人话模式下隐藏。
     * 只用于显示层；原始行仍留在 logLines 供 parseState 与「复制日志」使用，零功能影响。
     * 顺序很重要：先隐藏噪声，再翻译保留项，最后兜底原样返回（绝不误删未知行）。
     */
    private String humanize(String line) {
        // 时间戳前缀（"HH:mm:ss  "）保留，正文翻译
        String ts = "";
        String body = line;
        if (line.length() >= 10 && line.substring(0, 8).matches("\\d{2}:\\d{2}:\\d{2}")) {
            ts = line.substring(0, 10);
            body = line.substring(10);
        }

        // ── 1) 纯噪声：隐藏 ──────────────────────────────
        if (body.startsWith("──")) return null;                        // ── 快照 ── 分隔线
        if (body.contains("link=") && body.contains("链路启动=")) return null; // 快照聚合行
        if (body.startsWith("ServiceApi=")) return null;             // 节点快照行
        if (body.startsWith("收发：收到帧")) return null;              // 收发计数（顶部已有读数）
        if (body.startsWith("链路健康：")) return null;                // 链路健康计数
        if (body.startsWith("服务进程启动")) return null;
        if (body.startsWith("广播接收器：OK")) return null;
        if (body.startsWith("互联链路：已启动")) return null;
        if (body.startsWith("Wearable ServiceApi：获取成功")) return null;
        if (body.startsWith("就绪通告：")) return null;
        if (body.startsWith("节点变化：")) return null;
        if (body.contains("本地回执 pong") && body.contains("ping")) return null;
        if (body.startsWith("→ 指令引擎 cmd=")) return null;          // 下发内部行
        if (body.startsWith("← 回写手表") && body.contains("{")) return null; // 原始 JSON 帧
        if (body.startsWith("界面已打开，正在拉取服务状态")) return null;
        if (body.startsWith("界面打开：立即探测节点")) return null;
        if (body.startsWith("  ├ 发到 DSH")) return null;              // 自检能力明细
        if (body.startsWith("  └ 发到 WorkBuddy")) return null;
        if (body.startsWith("已写入配置（本应用内）")) return null;
        if (body.startsWith("COMPANION_SET 已更新")) return null;
        if (body.startsWith("凭据状态：")) return null;               // 凭据状态在面板显示，日志不重复
        if (body.startsWith("引擎自检失败:")
                || body.startsWith("状态查询失败:")
                || body.startsWith("启动服务异常:")) return null;

        // ── 2) 技术行 → 人话 ─────────────────────────────
        String human = null;
        if (body.contains("前台服务：OK") || body.contains("前台服务=OK")) human = "后台服务已就绪";
        else if (body.startsWith("指令引擎：已就绪")) human = "指令引擎已就绪";
        else if (body.startsWith("小米穿戴服务：已连接")) human = "已连上小米穿戴服务";
        else if (body.startsWith("小米穿戴服务：已断开")) human = "与小米穿戴断开（检查小米运动健康是否在运行）";
        else if (body.startsWith("XMS：启动穿戴节点握手")) human = "正在连接手表…";
        else if (body.startsWith("手表节点：已找到")) human = "已找到手表";
        else if (body.startsWith("手表节点：无") || body.startsWith("转离线")) human = "未找到手表（确认已配对、蓝牙已开）";
        else if (body.startsWith("手表节点：查询失败")) human = "查找手表失败（检查蓝牙 / 小米运动健康）";
        else if (body.startsWith("权限：") && body.contains("已授予")) human = "手表权限已就绪";
        else if (body.startsWith("权限：") && (body.contains("待授予") || body.contains("已发起申请"))) human = "正在申请手表权限，请在手表上确认";
        else if (body.startsWith("消息监听：已注册")) human = "手机已就绪，随时接收指令";
        else if (body.startsWith("消息监听：注册失败") || body.startsWith("消息监听：异常")) human = "消息监听失败，点「重启服务」重试";
        else if (body.startsWith("指令引擎自检：在线") || body.startsWith("指令引擎自检：")) {
            if (body.contains("在线") || body.contains("✓")) human = "自检通过，两条通道可用";
            else if (body.contains("失败") || body.contains("无响应") || body.contains("异常")) human = "自检未通过，点「重启服务」重试";
        }
        else if (body.contains("WorkBuddy 通道需 root")) human = "发到 WorkBuddy 需要 root 授权（KernelSU 里给本 App 授权）";
        else if (body.contains("A1 真机结论") || body.contains("预填进输入框")) human = "发到 WorkBuddy 只把文字放进输入框，需在手机上点「发送」";
        else if (body.startsWith("← 收到表端帧") || body.startsWith("→ 回写表端")) {
            // 从帧内容里判断目标通道，给一句人话
            if (body.contains("wb.prompt") || body.contains("WorkBuddy")) human = "收到/回执手表指令：发往 WorkBuddy";
            else if (body.contains("dsh.prompt") || body.contains("dsh-go")) human = "收到/回执手表指令：发往 DSH";
            else if (body.contains("ping") || body.contains("ready")) human = "手表握手成功";
            else human = "与手表之间收发了一条指令";
        }
        else if (body.startsWith("← 指令引擎结果")) {
            if (body.contains("重复指令已忽略")) human = "· 重复指令已忽略";
            else if (body.contains("WorkBuddy") || body.contains("预填")) human = "✓ 已送到 WorkBuddy 输入框（需点发送）";
            else if (body.contains("已写入会话") || body.contains("delivered=true")) human = "✓ 已写入 DSH 会话";
            else if (body.contains("未送达")) human = "✗ 指令未送达（看引擎错误原因）";
        }
        else if (body.startsWith("收到表端 ping")) human = "手表握手成功";
        else if (body.startsWith("回写跳过")) human = "手表暂时未连，结果稍后补发";
        else if (body.startsWith("已拒绝") || body.contains("拒绝非只读")) return null;

        if (human != null) return ts + human;
        // ── 3) 兜底：未知行原样保留（含所有 ✗/失败/异常行，绝不误删错误）──
        return line;
    }

    private int colorFor(String line) {
        if (line.contains("──")) return cInk3;                       // 快照分隔标题
        if (line.contains("✗") || line.contains("失败") || line.contains("异常")
                || line.contains("超时") || line.contains("无响应") || line.contains("错误")
                || line.contains("denied")) return cBad;
        if (line.contains("预填") || line.contains("需点发送") || line.contains("⚠")
                || line.contains("待授予") || line.contains("未送达")) return cWarn;
        if (line.contains("✓") || line.contains("已就绪") || line.contains("已连接")
                || line.contains("成功") || line.contains("在线") || line.contains("已注册")
                || line.contains("已授予") || line.contains("已找到")) return cOk;
        if (line.contains("← 收到表端帧") || line.contains("→ 回写") || line.contains("→ 指令引擎")) return cInk;
        return cInk2;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private String textOf(EditText e) { return e.getText() == null ? "" : e.getText().toString().trim(); }

    /** 凭据状态行（由 :relay 进程读真实 SP 后广播，避免主进程 SP 缓存读到旧值）。 */
    private void renderCredStatus(String line) {
        if (credStatus == null) return;
        boolean hasToken = line.contains("Token=已配置");
        boolean hasCookie = line.contains("Cookie=已配置");
        String t = (hasToken ? "● Token 已配置" : "○ Token 未配置")
                + "     " + (hasCookie ? "● Cookie 已配置" : "○ Cookie 未配置");
        credStatus.setText(t);
        // 两者都有=绿；至少一个有=琥珀；都没有=中性灰（未配置是常态，自愈会补，不算错误）
        credStatus.setTextColor(hasToken && hasCookie ? cOk
                : (hasToken || hasCookie ? cWarn : cInk3));
    }

    @Override protected void onResume() {
        super.onResume();
        ui.postDelayed(this::requestState, 300);
    }

    @Override protected void onDestroy() {
        if (receiver != null) { try { unregisterReceiver(receiver); } catch (Throwable ignore) {} }
        super.onDestroy();
    }
}
