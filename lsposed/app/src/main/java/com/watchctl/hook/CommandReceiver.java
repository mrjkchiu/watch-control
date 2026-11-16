package com.watchctl.hook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 0.1.1 关键修复：指令接收改为【Manifest 静态注册】。
 *
 * 旧版把 CmdReceiver 动态注册在 RelayService.onCreate() 里，而 RelayService 靠
 * RelayApplication 用 startForegroundService 拉起。在 Android 12+ / HyperOS 3(Android 16)
 * 上后台启动前台服务受限、模块进程又常被省电冻结，结果就是：
 *   · 模块进程没起来 → 动态接收器不存在 → com.watchctl.CMD 广播无人接收
 *   · 表端发指令 → companion 发出广播后石沉大海 → 8s 后「超时，手机没回应」
 * 而 ping/pong 是 companion 本地直回的，所以「连接成功」完全掩盖了这个断点。
 *
 * 静态接收器 + companion 侧 setPackage 显式广播：即使模块进程被回收，系统也会为这条
 * 显式广播重新拉起模块进程并回调 onReceive，不依赖任何 Service 常驻。
 *
 * 用 goAsync() 把处理挪到线程池：dsh.* 与 wb.prompt 都要走 127.0.0.1:3080
 * 的 session/prompt 网络请求（0.1.2 起 wb.prompt 不再后台 startActivity），
 * 都不能在广播主线程（约 10s ANR 上限）里做。
 */
public class CommandReceiver extends BroadcastReceiver {
    private static final String TAG = "WatchCtl";
    private static final ExecutorService EXEC = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "watchctl-cmd");
        t.setDaemon(true);
        return t;
    });

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null) return;
        final Context app = ctx.getApplicationContext();
        // 复制一份：Intent 对象在 goAsync 异步期间不应被复用/回收
        final Intent work = new Intent(intent);
        final PendingResult pending = goAsync();
        EXEC.execute(() -> {
            try {
                CommandProcessor.handle(app, work);
            } catch (Throwable t) {
                Log.e(TAG, "指令广播处理异常", t);
            } finally {
                pending.finish();
            }
        });
    }
}
