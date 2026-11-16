package com.example.watchctl;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.watchctl.hook.CommandProcessor;
import com.watchctl.hook.Config;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 指令引擎门面（0.1.8 单 APK 合并）。
 *
 * 旧版把指令用 com.watchctl.CMD 显式广播发给【独立的 LSPosed 模块 APK】，再等模块用
 * com.watchctl.RESULT 广播回结果——中间隔着一个可能被回收/冻结的进程、两次广播投递、
 * goAsync 线程池和动态 receiver，是「发指令超时 / 模块=无响应」一类问题的根源。
 *
 * 现在指令处理类（CommandProcessor / DshClient / WbIngress / Config）已并入本 APK，
 * 这里在线程池里【本地直调】CommandProcessor.handle(...)，结果经 ResultSink 直接回调
 * 给 CompanionService.deliverToWatch()。全程不跨进程、不经广播、不依赖 LSPosed。
 *
 * sendCommand 必须走线程池：DshClient 要请求 127.0.0.1:3080，WbIngress 要跑 root shell，
 * 都不能占着 BLE 收帧线程（原模块的 CommandReceiver 也是 goAsync + 线程池）。
 */
public class ModuleBridge {
    private static final String TAG = "WatchCtl-Companion";

    private static final ExecutorService EXEC = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "watchctl-cmd");
        t.setDaemon(true);
        return t;
    });

    private final Context ctx;
    private final String authToken;
    private final CommandProcessor.ResultSink sink;
    private final Handler main = new Handler(Looper.getMainLooper());

    public ModuleBridge(Context ctx, String authToken, CommandProcessor.ResultSink sink) {
        this.ctx = ctx.getApplicationContext();
        this.authToken = authToken;
        this.sink = sink;
    }

    private Intent base(String cmd, String id, JSONObject args) {
        Intent i = new Intent(Config.DEFAULT_CMD_ACTION);
        i.putExtra("watchctl_token", authToken);
        i.putExtra("watchctl_cmd", cmd);
        i.putExtra("watchctl_args", args == null ? "{}" : args.toString());
        i.putExtra("watchctl_id", id == null ? "" : id);
        return i;
    }

    /** 本地直调执行一条指令；结果由 sink 切回主线程后回调。 */
    public void sendCommand(String id, String cmd, JSONObject args) {
        final Intent work = base(cmd, id, args);
        EXEC.execute(() -> {
            try {
                CommandProcessor.handle(ctx, work, forward());
            } catch (Throwable t) {
                Log.e(TAG, "本地指令执行异常 cmd=" + cmd, t);
                JSONObject err = new JSONObject();
                try {
                    JSONObject d = new JSONObject();
                    d.put("error", "engine-error");
                    d.put("message", String.valueOf(t.getMessage()));
                    err.put("cmd", cmd);
                    err.put("data", d);
                } catch (Throwable ignored) {}
                deliver(id, false, err);
            }
        });
    }

    /** 指令引擎自检：cmd=module.ping 纯本地秒回，不碰 dsh-go、不拉起 WorkBuddy。 */
    public void sendProbe(String id) {
        final Intent work = base("module.ping", id, new JSONObject());
        EXEC.execute(() -> {
            try {
                CommandProcessor.handle(ctx, work, forward());
            } catch (Throwable t) {
                Log.e(TAG, "指令引擎自检异常", t);
            }
        });
    }

    /** 写配置：直接写本应用 SharedPreferences，不再发 com.watchctl.SET 广播。 */
    public void setConfig(String key, String value) {
        if (key == null || value == null) return;
        ctx.getSharedPreferences(Config.PREF_NAME, Context.MODE_PRIVATE)
                .edit().putString(key, value).apply();
        Log.i(TAG, "已写入配置 " + key);
    }

    /** 把 CommandProcessor 的结果出口接到 companion，并保证回到主线程再操作 WatchLink/State。 */
    private CommandProcessor.ResultSink forward() {
        return (id, ok, data) -> deliver(id, ok, data);
    }

    private void deliver(String id, boolean ok, JSONObject data) {
        if (sink == null) return;
        main.post(() -> {
            try {
                sink.emit(id, ok, data);
            } catch (Throwable t) {
                Log.e(TAG, "结果回调失败", t);
            }
        });
    }
}
