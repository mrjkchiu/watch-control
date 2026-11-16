package com.example.watchctl;

import android.content.Context;
import android.content.Intent;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 手机端状态可视化：把 :relay 进程里 SDK 的真实状态通过广播发到 MainActivity 显示。
 * 目的：用户无需 adb / 电脑，打开 companion 就能看到互联卡在哪一步。
 *
 * 说明：CompanionService 运行在 :relay 独立进程，MainActivity 在主进程，
 * 二者靠「带 setPackage 的显式广播」跨进程通信（动态注册的接收器可收到）。
 * 状态去重表只在服务进程内使用，避免 5s 节点轮询刷屏。
 */
public final class State {
    public static final String ACTION = "com.watchctl.STATE";
    public static final String EXTRA_LINE = "line";

    private static final Map<String, String> LAST = new HashMap<>();
    // P1 修复：SimpleDateFormat 非线程安全。send() 会被 relay 的多个线程并发调用，
    // 共享实例并发 format() 会抛 IllegalStateException 或产出错乱时间，且被 send()
    // 的 catch 吞掉 → 状态行静默丢失。用 ThreadLocal 每线程独立实例，根除竞态。
    private static final ThreadLocal<SimpleDateFormat> TS = new ThreadLocal<SimpleDateFormat>() {
        @Override protected SimpleDateFormat initialValue() {
            return new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        }
    };

    private State() {}

    /** 每条都发（帧、错误等事件流）。 */
    public static void emit(Context ctx, String text) {
        send(ctx, text);
    }

    /** 同一 key 内容未变化则不重复发（状态类，配合轮询去抖）。 */
    public static void emitOnce(Context ctx, String key, String text) {
        synchronized (LAST) {
            if (text.equals(LAST.get(key))) return;
            LAST.put(key, text);
        }
        send(ctx, text);
    }

    public static void clear() {
        synchronized (LAST) { LAST.clear(); }
    }

    private static void send(Context ctx, String text) {
        try {
            Intent i = new Intent(ACTION);
            i.setPackage(ctx.getPackageName());   // 显式，避免隐式广播限制与外泄
            i.putExtra(EXTRA_LINE, TS.get().format(new Date()) + "  " + text);
            ctx.sendBroadcast(i);
        } catch (Throwable ignored) {
        }
    }
}
