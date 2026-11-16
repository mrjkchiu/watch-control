package com.example.watchctl;

import org.json.JSONObject;

/**
 * 手表 ↔ 手机 的传输层抽象。
 * 实现：XmsInterconnectLink（小米 Vela interconnect，需 xms-wearable-lib AAR）
 *      LocalTestLink      （无手表时的进程内回环，用于真机自测）
 */
public interface WatchLink {
    void setHandler(Handler h);
    void connect();
    void disconnect();
    void send(JSONObject frame);
    String state();

    interface Handler {
        void onFrame(JSONObject frame);
    }
}
