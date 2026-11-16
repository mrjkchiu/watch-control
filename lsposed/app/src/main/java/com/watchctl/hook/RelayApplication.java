package com.watchctl.hook;

import android.app.Application;

/** 模块自身 App 的 Application：一启动就把主中继 Service 拉起（前台常驻）。 */
public class RelayApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        try {
            RelayService.start(this);
        } catch (Throwable t) {
            android.util.Log.e("WatchCtl", "启动中继失败", t);
        }
    }
}
