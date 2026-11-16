package com.example.watchctl;

import android.content.Context;
import android.util.Log;

import com.xiaomi.xms.wearable.Wearable;
import com.xiaomi.xms.wearable.auth.AuthApi;
import com.xiaomi.xms.wearable.auth.Permission;
import com.xiaomi.xms.wearable.message.MessageApi;
import com.xiaomi.xms.wearable.message.OnMessageReceivedListener;
import com.xiaomi.xms.wearable.node.Node;
import com.xiaomi.xms.wearable.node.NodeApi;
import com.xiaomi.xms.wearable.service.OnServiceConnectionListener;
import com.xiaomi.xms.wearable.service.ServiceApi;
import com.xiaomi.xms.wearable.tasks.OnFailureListener;
import com.xiaomi.xms.wearable.tasks.OnSuccessListener;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 手机侧 @system.interconnect 对端 —— 小米穿戴官方 xms-wearable SDK 真实实现。
 *
 * 通道对应关系（同一条 BLE 通道，靠 包名+签名 一致配对）：
 *   表端 connect.send({data:Object})   ──►  onMessageReceived(byte[])  （UTF-8 JSON）
 *   messageApi.sendMessage(nodeId,[])  ──►  表端 connect.onmessage 取 msg.data
 *
 * 0.1.7 连接稳定性专项（真机「有时连不上、有时能连上」）：
 *   旧版是【纯被动等表端 ping】：listener 只在 listenerRegistered=false 时注册一次，
 *   nodeId 变了不重挂，onServiceConnected 要等下一次 5s 轮询，且从不主动告知表端
 *   「我已就绪」。于是表端 ping 必须恰好撞上手机端 listener 注册好的那个窗口，
 *   撞不上就超时 —— 命中全靠运气。
 *   本版改为【主动 + 自愈】：
 *     1) 记录 registeredNodeId，nodeId 变化才 remove 旧 + add 新（相同 id 且已注册跳过，
 *        避免 0.1.3 踩过的「remove+add 之间漏帧 / you have registered」坑）；
 *     2) onServiceConnected 立即 ensureNode()，不等 5s；服务重连清掉旧登记强制重挂；
 *     3) listener 一就绪 / 节点变化 / 界面打开(poke) 就主动发 cmd='ready' 帧，
 *        表端收到立即回一次 ping 完成双向验证，打破时序窗口；
 *     4) 低频(45s)ready 保活，覆盖「表端页面刚打开但手机端无事件」的窗口；
 *     5) 链路健康可观测：lastFrameAt / readySent / nodeRebind / svcConnected 进状态快照。
 *
 * 注意：ready 是【通告帧】，发送失败绝不影响 listenerRegistered 状态，也绝不计入
 *       指令收发的 frameErr，避免把「表端没在前台」误判成「监听失效」而疯狂重挂。
 */
public class XmsInterconnectLink implements WatchLink {
    private static final String TAG = "WatchCtl-XMS";
    private static final long NODE_POLL_SEC = 5L;
    private static final long READY_KEEPALIVE_MS = 45_000L;

    private final Context ctx;
    private Handler handler;
    private volatile String nodeId = null;
    private volatile String registeredNodeId = null;   // 0.1.7：当前 listener 实际绑定的节点
    private volatile String state = "offline";
    private volatile boolean listenerRegistered = false;
    private volatile String serviceConn = "未连接";
    private volatile String permState = "未查询";
    private volatile String lastError = "";
    private volatile int framesIn = 0, framesOut = 0, frameErr = 0;
    private volatile long lastFrameAt = 0L;
    private volatile long lastReadyAt = 0L;
    private volatile int readySent = 0;
    private volatile int nodeRebind = 0;
    private volatile int svcConnected = 0;
    private final AtomicBoolean started = new AtomicBoolean(false);

    private NodeApi nodeApi;
    private MessageApi messageApi;
    private AuthApi authApi;
    private ServiceApi serviceApi;
    private ScheduledExecutorService scheduler;

    public XmsInterconnectLink(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    private void st(String key, String text) { State.emitOnce(ctx, key, text); }
    private void ev(String text) { State.emit(ctx, text); }

    /**
     * P2 修复：帧原文含用户 prompt 正文（args.text / text / prompt / draft），
     * 原先直接拼进状态行（会被「复制日志」一键带走）和 logcat → 隐私外泄。
     * 这里只保留帧结构元数据，把正文字段值替换为 «N字»。仅用于日志展示，
     * 不修改原始 message/frame 对象，不影响任何指令处理。解析失败则只输出占位，绝不回退输出原文。
     */
    private static String redact(String raw) {
        try {
            JSONObject o = new JSONObject(raw);
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                Object v = o.opt(k);
                if ("text".equals(k) || "prompt".equals(k) || "draft".equals(k)) {
                    if (v instanceof String) o.put(k, "«" + ((String) v).length() + "字»");
                } else if (v instanceof JSONObject) {
                    JSONObject child = (JSONObject) v;
                    java.util.Iterator<String> ci = child.keys();
                    while (ci.hasNext()) {
                        String ck = ci.next();
                        Object cv = child.opt(ck);
                        if (("text".equals(ck) || "prompt".equals(ck) || "draft".equals(ck))
                                && cv instanceof String) {
                            child.put(ck, "«" + ((String) cv).length() + "字»");
                        }
                    }
                }
            }
            return o.toString();
        } catch (Throwable t) {
            return "[帧已脱敏]";
        }
    }

    private final OnMessageReceivedListener messageListener = new OnMessageReceivedListener() {
        @Override public void onMessageReceived(String id, byte[] message) {
            try {
                String raw = new String(message, StandardCharsets.UTF_8);
                framesIn++;
                lastFrameAt = System.currentTimeMillis();
                ev("← 收到表端帧 #" + framesIn + ": " + redact(raw));
                Log.i(TAG, "← 表端帧: " + redact(raw));
                JSONObject frame;
                try { frame = new JSONObject(raw); }
                catch (Exception e) { frame = new JSONObject().put("raw", raw); }
                if (handler != null) handler.onFrame(frame);
                else { frameErr++; ev("帧到达但 handler 未设置"); }
            } catch (Throwable t) {
                frameErr++;
                ev("处理表端帧失败 " + t.getClass().getSimpleName());
            }
        }
    };

    private final OnServiceConnectionListener connListener = new OnServiceConnectionListener() {
        @Override public void onServiceConnected() {
            serviceConn = "已连接";
            svcConnected++;
            st("svc", "小米穿戴服务：已连接 ✓（第 " + svcConnected + " 次）");
            Log.i(TAG, "小米穿戴服务已连接");
            // 服务(重)连后 SDK 侧旧登记已失效，强制重挂；立即重查，不等下一个 5s tick。
            listenerRegistered = false;
            registeredNodeId = null;
            ensureNode();
        }
        @Override public void onServiceDisconnected() {
            serviceConn = "已断开";
            st("svc", "小米穿戴服务：已断开 ✗（检查小米运动健康是否在跑、手表是否配对）");
            Log.w(TAG, "小米穿戴服务断开");
            listenerRegistered = false;
            registeredNodeId = null;
            nodeId = null;
            state = "offline";
        }
    };

    @Override public void setHandler(Handler h) { this.handler = h; }

    @Override public void connect() {
        if (!started.compareAndSet(false, true)) return;
        ev("XMS：启动穿戴节点握手");
        Log.i(TAG, "connect(): 启动小米穿戴节点握手");
        try {
            serviceApi = Wearable.getServiceApi(ctx);
            serviceApi.registerServiceConnectionListener(connListener);
            st("api", "Wearable ServiceApi：获取成功 ✓");
        } catch (Throwable t) {
            lastError = "ServiceApi " + t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
            st("api", "Wearable ServiceApi：失败 ✗ " + lastError);
            Log.w(TAG, "ServiceApi 不可用", t);
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread th = new Thread(r, "watchctl-xms-poll");
            th.setDaemon(true);
            return th;
        });
        scheduler.scheduleWithFixedDelay(this::ensureNode, 0, NODE_POLL_SEC, TimeUnit.SECONDS);
    }

    /** 界面打开时由 CompanionService 调用：立即重查节点并主动通告就绪，加速握手。 */
    public void poke() {
        ev("界面打开：立即探测节点 + 主动通告就绪");
        ensureNode();
        if (listenerRegistered && nodeId != null) announceReady("poke");
    }

    private void ensureNode() {
        try {
            if (nodeApi == null) nodeApi = Wearable.getNodeApi(ctx);
            nodeApi.getConnectedNodes()
                    .addOnSuccessListener(new OnSuccessListener<List<Node>>() {
                        @Override public void onSuccess(List<Node> nodes) {
                            if (nodes == null || nodes.isEmpty()) {
                                setOffline("无已连接手表节点（手表未通过小米运动健康配对 / 蓝牙未连 / 健康App后台被杀）");
                                return;
                            }
                            final String id = nodes.get(0).id;
                            final boolean changed = (id != null && !id.equals(registeredNodeId));
                            nodeId = id;
                            state = "online";
                            st("node", "手表节点：已找到 nodeId=" + id + " ✓");

                            if (changed && registeredNodeId != null) {
                                // 旧节点 listener 收不到新节点帧，必须先移除。remove 的是【旧 id】，
                                // add 的是【新 id】，不存在「同 id remove+add 之间漏帧」或
                                // "you have registered" 的问题（0.1.3 的坑只发生在无条件重挂）。
                                try {
                                    if (messageApi == null) messageApi = Wearable.getMessageApi(ctx);
                                    messageApi.removeListener(registeredNodeId);
                                } catch (Throwable ignore) {}
                                nodeRebind++;
                                st("rebind", "节点变化：nodeId " + registeredNodeId + " → " + id + "，重挂监听");
                                listenerRegistered = false;
                                registeredNodeId = null;
                            }

                            if (!listenerRegistered) {
                                requestPermissions(id);
                                registerListener(id);
                            } else {
                                // 已注册：低频保活通告，覆盖「表端页面刚打开、手机端这侧恰无事件」的窗口。
                                long now = System.currentTimeMillis();
                                if (now - lastReadyAt > READY_KEEPALIVE_MS) announceReady("keepalive");
                            }
                        }
                    })
                    .addOnFailureListener(new OnFailureListener() {
                        @Override public void onFailure(Exception e) {
                            lastError = "getConnectedNodes " + msg(e);
                            st("node", "手表节点：查询失败 ✗ " + lastError);
                            Log.w(TAG, "getConnectedNodes 失败: " + lastError);
                            setOffline("节点查询失败");
                        }
                    });
        } catch (Throwable t) {
            lastError = "getConnectedNodes 异常 " + t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
            st("node", "手表节点：异常 ✗ " + lastError);
            Log.w(TAG, "getConnectedNodes 异常", t);
            setOffline("SDK 不可用");
        }
    }

    private void setOffline(String why) {
        if (!"offline".equals(state)) ev("转离线: " + why);
        st("node", "手表节点：无 ✗ " + why);
        nodeId = null;
        listenerRegistered = false;
        registeredNodeId = null;
        state = "offline";
    }

    private void requestPermissions(String id) {
        try {
            if (authApi == null) authApi = Wearable.getAuthApi(ctx);
            Permission[] perms = new Permission[]{Permission.DEVICE_MANAGER, Permission.NOTIFY};
            authApi.checkPermissions(id, perms)
                    .addOnSuccessListener(new OnSuccessListener<boolean[]>() {
                        @Override public void onSuccess(boolean[] granted) {
                            boolean missing = granted == null || granted.length < perms.length;
                            if (!missing) for (boolean b : granted) if (!b) { missing = true; break; }
                            if (missing) {
                                permState = "待授予（已发起申请）";
                                st("perm", "权限：DEVICE_MANAGER 待授予（已发起申请）");
                                try {
                                    authApi.requestPermission(id, Permission.DEVICE_MANAGER, Permission.NOTIFY);
                                    Log.i(TAG, "已发起 DEVICE_MANAGER/NOTIFY 授权");
                                } catch (Throwable t) {
                                    st("perm", "权限：申请异常 " + t.getClass().getSimpleName());
                                }
                            } else {
                                permState = "已授予";
                                st("perm", "权限：DEVICE_MANAGER/NOTIFY 已授予 ✓");
                            }
                        }
                    })
                    .addOnFailureListener(new OnFailureListener() {
                        @Override public void onFailure(Exception e) {
                            permState = "查询失败";
                            st("perm", "权限：查询失败 ✗ " + msg(e));
                        }
                    });
        } catch (Throwable t) {
            permState = "异常";
            st("perm", "权限：流程异常 " + t.getClass().getSimpleName() + "（不阻断监听）");
        }
    }

    private void registerListener(String id) {
        try {
            if (messageApi == null) messageApi = Wearable.getMessageApi(ctx);
            // 只在「首次 / 服务重连 / nodeId 变化」时调用；相同 id 且已注册时上面的分支不会进来。
            messageApi.addListener(id, messageListener)
                    .addOnSuccessListener(new OnSuccessListener<Void>() {
                        @Override public void onSuccess(Void v) {
                            markRegistered(id, "已注册 ✓ 手机端已就绪");
                        }
                    })
                    .addOnFailureListener(new OnFailureListener() {
                        @Override public void onFailure(Exception e) {
                            String m = msg(e);
                            // "you have registered" / "already registered" 其实表示已登记成功，
                            // 按成功处理（0.1.3 已修：旧版误判成 ✗ 导致反复重挂 + 面板假错误）。
                            if (m != null && (m.contains("registered") || m.contains("already"))) {
                                markRegistered(id, "已注册 ✓（SDK 幂等登记）");
                                return;
                            }
                            listenerRegistered = false;
                            registeredNodeId = null;
                            lastError = "addListener " + m;
                            st("listen", "消息监听：注册失败 ✗ " + lastError);
                            Log.w(TAG, "addListener 失败: " + lastError);
                        }
                    });
        } catch (Throwable t) {
            listenerRegistered = false;
            registeredNodeId = null;
            lastError = "注册监听异常 " + t.getClass().getSimpleName();
            st("listen", "消息监听：异常 ✗ " + lastError);
            Log.w(TAG, "注册监听异常", t);
        }
    }

    /** 登记成功：置位、记录实际绑定节点、清历史错误，并【主动通告表端我已就绪】。 */
    private void markRegistered(String id, String how) {
        listenerRegistered = true;
        registeredNodeId = id;
        lastError = "";
        st("listen", "消息监听：" + how);
        Log.i(TAG, "消息监听" + how + " nodeId=" + id);
        announceReady("listener-ready");
    }

    /**
     * 主动就绪通告：表端 bridge 收到 cmd='ready' 会立即回一次 ping 完成双向验证，
     * 不必等表端自己的 2.5s 探测恰好撞上手机端就绪窗口（连接不稳的主因）。
     * 这是【通告帧】，失败只记 debug，绝不动 listenerRegistered / frameErr。
     */
    private void announceReady(String why) {
        final String id = nodeId;
        if (id == null) return;
        lastReadyAt = System.currentTimeMillis();
        try {
            if (messageApi == null) messageApi = Wearable.getMessageApi(ctx);
            JSONObject f = new JSONObject();
            f.put("cmd", "ready");
            f.put("t", lastReadyAt);
            f.put("why", why);
            byte[] bytes = f.toString().getBytes(StandardCharsets.UTF_8);
            messageApi.sendMessage(id, bytes)
                    .addOnSuccessListener(new OnSuccessListener<Void>() {
                        @Override public void onSuccess(Void v) {
                            readySent++;
                            st("ready", "就绪通告：已发出 ✓（" + why + "，共 " + readySent + " 次）");
                        }
                    })
                    .addOnFailureListener(new OnFailureListener() {
                        @Override public void onFailure(Exception e) {
                            // 表端页面没开/没在前台时收不到很正常，不是监听失效，不改状态。
                            Log.d(TAG, "就绪通告未送达（表端可能未在前台）: " + msg(e));
                        }
                    });
        } catch (Throwable t) {
            Log.d(TAG, "就绪通告异常（忽略）: " + t.getClass().getSimpleName());
        }
    }

    @Override public void send(JSONObject frame) {
        String id = nodeId;
        if (id == null) {
            frameErr++;
            ev("回写跳过：手表节点未连接");
            return;
        }
        try {
            if (messageApi == null) messageApi = Wearable.getMessageApi(ctx);
            byte[] bytes = frame.toString().getBytes(StandardCharsets.UTF_8);
            messageApi.sendMessage(id, bytes)
                    .addOnSuccessListener(new OnSuccessListener<Void>() {
                        @Override public void onSuccess(Void v) {
                            framesOut++;
                            ev("→ 回写表端 #" + framesOut + " 成功 " + redact(frame.toString()));
                        }
                    })
                    .addOnFailureListener(new OnFailureListener() {
                        @Override public void onFailure(Exception e) {
                            frameErr++;
                            ev("→ 回写表端失败 ✗ " + msg(e));
                        }
                    });
        } catch (Throwable t) {
            frameErr++;
            ev("sendMessage 异常 " + t.getClass().getSimpleName());
        }
    }

    /** 界面查询时的当前状态快照（含 0.1.7 链路健康）。 */
    public void reportState() {
        State.emit(ctx, "ServiceApi=" + serviceConn
                + "  node=" + (nodeId == null ? "无" : nodeId)
                + "  监听节点=" + (registeredNodeId == null ? "无" : registeredNodeId)
                + "  listen=" + (listenerRegistered ? "已注册" : "未注册")
                + "  perm=" + permState);
        State.emit(ctx, "收发：收到帧 " + framesIn + " / 回写 " + framesOut + " / 错误 " + frameErr
                + (lastError.isEmpty() ? "" : "  最后错误=" + lastError));
        long ago = lastFrameAt == 0 ? -1 : (System.currentTimeMillis() - lastFrameAt) / 1000;
        State.emit(ctx, "链路健康：最后表端帧=" + (ago < 0 ? "无" : ago + "s前")
                + "  就绪通告=" + readySent + " 次  节点重挂=" + nodeRebind
                + "  服务连接=" + svcConnected + " 次");
    }

    @Override public void disconnect() {
        started.set(false);
        if (scheduler != null) { try { scheduler.shutdownNow(); } catch (Throwable ignore) {} scheduler = null; }
        String id = registeredNodeId != null ? registeredNodeId : nodeId;
        if (id != null && messageApi != null) {
            try { messageApi.removeListener(id); } catch (Throwable ignore) {}
        }
        if (serviceApi != null) {
            try { serviceApi.unregisterServiceConnectionListener(connListener); } catch (Throwable ignore) {}
        }
        nodeId = null;
        registeredNodeId = null;
        listenerRegistered = false;
        state = "offline";
    }

    @Override public String state() { return state; }

    private static String msg(Exception e) { return e == null ? "" : String.valueOf(e.getMessage()); }
}
