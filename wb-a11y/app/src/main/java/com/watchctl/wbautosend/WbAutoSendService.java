package com.watchctl.wbautosend;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * 自动点「发送」无障碍服务。
 *
 * 工作流程（与 relay 配合）：
 *   1. relay 在发 `am start ... .ShareAddToChat` 之前，先发一条广播
 *      `com.watchctl.a11y.ARM`（带 --es id <requestId>）武装本服务。
 *   2. 服务进入 6 秒时间窗（armed），期间只盯着 com.tencent.workbuddy.app 的窗口。
 *   3. 一旦窗口里出现可点击、文本含「发送」的节点，就 performAction(CLICK) 一次，
 *      立刻解除武装并回广播 `com.watchctl.a11y.DONE`（带 --es id）。
 *   4. 时间窗耗尽自动解除武装，绝不误点用户手动打开的分享页。
 *
 * 安全边界：
 *   - 不读输入框内容、不上传任何数据，只做「点一次发送」。
 *   - 仅当 relay 主动武装后才动作，无 armed 状态完全静默。
 *   - 只监听 WorkBuddy 包（manifest 的 packageNames 已限定）。
 */
public final class WbAutoSendService extends AccessibilityService {
    private static final String TAG = "WbAutoSend";

    static final String WB_PKG = "com.tencent.workbuddy.app";
    static final String ACTION_ARM = "com.watchctl.a11y.ARM";
    static final String ACTION_DONE = "com.watchctl.a11y.DONE";
    static final String EXTRA_ID = "id";
    static final long ARMED_TTL_MS = 6_000;

    private volatile long armedUntil = 0L;
    private volatile String armedId = "";

    private final BroadcastReceiver armReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_ARM.equals(intent.getAction())) {
                armedId = intent.getStringExtra(EXTRA_ID);
                if (armedId == null) armedId = "";
                armedUntil = SystemClock.elapsedRealtime() + ARMED_TTL_MS;
                Log.i(TAG, "armed id=" + armedId);
            }
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
        info.packageNames = new String[]{WB_PKG};
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 100;
        info.flags = AccessibilityServiceInfo.DEFAULT;
        setServiceInfo(info);

        IntentFilter filter = new IntentFilter(ACTION_ARM);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(armReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(armReceiver, filter);
        }
        Log.i(TAG, "connected; watching " + WB_PKG);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (SystemClock.elapsedRealtime() > armedUntil) {
            return; // 未武装或已过期，静默
        }
        if (!WB_PKG.equals(event.getPackageName())) {
            return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return;
        }
        try {
            clickSend(root);
        } finally {
            root.recycle();
        }
    }

    /** 找「可点击且文本含『发送』」的节点，点一次后立刻解除武装。 */
    @SuppressLint("DefaultLocale")
    private void clickSend(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> candidates =
                root.findAccessibilityNodeInfosByText("发送");
        for (AccessibilityNodeInfo node : candidates) {
            if (node.isClickable() && node.isVisibleToUser()) {
                boolean ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                if (ok) {
                    Log.i(TAG, "clicked 发送 id=" + armedId);
                    armedUntil = 0L; // 点完立即 disarm，避免重复点击
                    Intent done = new Intent(ACTION_DONE);
                    done.setPackage(getPackageName());
                    done.putExtra(EXTRA_ID, armedId);
                    sendBroadcast(done);
                }
                node.recycle();
                if (ok) return;
            } else {
                node.recycle();
            }
        }
    }

    @Override
    public void onInterrupt() {
        Log.i(TAG, "interrupted");
    }

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(armReceiver);
        } catch (IllegalArgumentException ignore) {
            // 未注册过
        }
        super.onDestroy();
    }
}
