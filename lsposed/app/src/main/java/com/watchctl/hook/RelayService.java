package com.watchctl.hook;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * 腕控 · 模块侧前台保活服务（0.1.1 起只负责保活，不再承担指令接收）。
 *
 * 指令接收已迁移到 Manifest 静态注册的 CommandReceiver（见该类注释）：
 * 动态接收器依赖本服务常驻，而 Android 12+ 后台启动 FGS 受限 + HyperOS 省电冻结，
 * 会导致 com.watchctl.CMD 广播无人接收，表端发指令超时。
 *
 * 本服务现在的唯一作用是：模块被打开/被拉起时给个前台通知，尽量提高进程优先级。
 * 即使它启动失败（后台 FGS 限制抛异常），命令链路也照样能工作——因为不再依赖它。
 */
public class RelayService extends Service {
    private static final String TAG = "WatchCtl";
    private static final String CHANNEL_ID = "watchctl_relay";

    public static void start(Context ctx) {
        try {
            Intent i = new Intent(ctx, RelayService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Throwable t) {
            // Android 12+ 后台启动前台服务会抛 ForegroundServiceStartNotAllowedException，
            // 属预期内、且已不影响命令链路；只记日志，绝不让 Application.onCreate 崩掉。
            Log.w(TAG, "保活服务未能启动（不影响指令链路）: " + t.getClass().getSimpleName());
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            createChannel();
            Notification n = new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("腕控中继")
                    .setContentText("指令由静态接收器处理，本服务仅保活")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .build();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(1, n);
            }
            Log.i(TAG, "RelayService 保活已启动（指令由 CommandReceiver 处理）");
        } catch (Throwable t) {
            Log.w(TAG, "前台通知失败（不影响指令链路）: " + t.getClass().getSimpleName());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "腕控中继", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }
}
