package com.example.watchctl;

import android.util.Log;

import org.json.JSONObject;

/**
 * 无手表时的进程内回环链路：仅用于真机自测。
 * 用 `adb shell am broadcast -a com.watchctl.TEST_FRAME --es frame '{...}'`
 * 即可把一帧注入 CompanionService，走完整  companion→内置引擎→结果→回写 链路。
 *
 * W3：本链路【没有真实手表】。state() 以前返回 "online"，会让状态面板把「XMS SDK
 * 初始化失败后的自测回退」误显示成「已就绪/已连接」——假绿，与「面板不会骗我」冲突。
 * 现改为恒 "offline"，并让 log 明说这是自测回退；真实链路只有 XmsInterconnectLink。
 */
public class LocalTestLink implements WatchLink {
    private static final String TAG = "WatchCtl-LocalLink";
    private Handler handler;

    @Override public void setHandler(Handler h) { this.handler = h; }

    @Override public void connect() {
        Log.i(TAG, "LocalTestLink 已启动（自测回退 · 无真实手表 · 状态恒 offline）");
    }

    @Override public void disconnect() { }

    @Override public void send(JSONObject frame) {
        // 没有真实手表，回写只在 logcat 可见
        Log.i(TAG, "→(回写手表) " + frame);
    }

    @Override public String state() { return "offline"; }
}
