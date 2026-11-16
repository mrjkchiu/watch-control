package com.watchctl.hook;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 指令引擎配置（0.1.8 单 APK 合并后）。
 *
 * 手机端已合并为【一个 APK】：companion 同时承担 BLE 互联与指令执行，
 * 本类由 ModuleBridge 在 :relay 进程内本地直调，不再有跨进程广播/Xposed。
 * 因此去掉了原模块里给 HookMain 用的 XSharedPreferences.load()——
 * 那是唯一一处 Xposed 依赖，合并后无对象可读，已删除。
 *
 * 字段与手表→companion 的指令协议对应：
 *   auth_token  ←→ 每条指令带的 watchctl_token（同进程内仍保留校验，防伪造帧）
 *   dsh_token   ←→ dsh-go 引擎启动 token（每次引擎重启都变，仅作首次换 cookie 用）
 *   dsh_cookie  ←→ dsh-go 会话 cookie（bearer credential，签名密钥在
 *                  $DSH_HOME/.credentials.yaml，跨 dsh web 重启有效 30 天；
 *                  这才是应持久化的长期凭据，见 packages/dsh_client connection.dart）
 *   wb_package              ←→ WorkBuddy 包名（module.ping / status 状态自证用；dsh.open 已删除）
 *   cmd_action / result_action ←→ 兼容保留的广播 action（本地直调不使用）
 */
public class Config {
    public static final String PREF_NAME = "watchctl";
    /** 本应用包名：兼容路径里结果广播仍需显式定向到自己（同应用内）。 */
    public static final String COMPANION_PACKAGE = "com.example.watchctl";
    public static final String DEFAULT_WB_PACKAGE = "com.tencent.workbuddy.app";
    public static final String DEFAULT_CMD_ACTION = "com.watchctl.CMD";
    public static final String DEFAULT_RESULT_ACTION = "com.watchctl.RESULT";
    public static final String DEFAULT_AUTH_TOKEN = "watchctl-dev"; // 开发期默认值，正式用请改

    public boolean enabled = false;
    public boolean frameworkScope = false;          // 是否 hook system_server（默认关）
    public boolean allowAllBackgroundStarts = false; // 危险：全局放行后台启动，默认关
    public String authToken = DEFAULT_AUTH_TOKEN;
    public String dshToken = "";
    public String dshCookie = "";   // dsh-auth-<hash>=v1.<payload>.<sig>，跨引擎重启 30 天有效
    public String wbPackage = DEFAULT_WB_PACKAGE;
    public String cmdAction = DEFAULT_CMD_ACTION;
    public String resultAction = DEFAULT_RESULT_ACTION;

    /** 在 :relay 进程内本地读取本应用 SharedPreferences；每次指令都重读，配置写入后立即生效。 */
    public static Config loadFromContext(Context ctx) {
        Config c = new Config();
        SharedPreferences p = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        c.enabled = p.getBoolean("enabled", false);
        c.authToken = p.getString("auth_token", p.getString("token", DEFAULT_AUTH_TOKEN));
        c.dshToken = p.getString("dsh_token", "");
        c.dshCookie = p.getString("dsh_cookie", "");
        c.wbPackage = p.getString("wb_package", DEFAULT_WB_PACKAGE);
        c.cmdAction = p.getString("cmd_action", DEFAULT_CMD_ACTION);
        c.resultAction = p.getString("result_action", DEFAULT_RESULT_ACTION);
        return c;
    }
}
