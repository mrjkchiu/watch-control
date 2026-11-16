package com.watchctl.hook;

import android.content.Context;
import android.content.SharedPreferences;
import de.robv.android.xposed.XSharedPreferences;

/**
 * 模块配置。主中继（RelayService）在模块自身进程里用 loadFromContext() 读；
 * Xposed hook（HookMain，跑在 WorkBuddy/系统进程）用 load() 跨进程读（需 pref 可世界读）。
 *
 * 字段与手表→companion→模块 的指令协议一一对应：
 *   auth_token  ←→ 每条指令广播带的 watchctl_token（鉴权，防第三方伪造）
 *   dsh_token   ←→ dsh-go 引擎启动 token（模块内置字段，引擎重启后需更新）
 *   wb_package              ←→ WorkBuddy 包名（dsh.open 引擎页时使用）
 *   cmd_action / result_action ←→ 收指令 / 回结果的广播 action
 * 0.1.2 起指令走引擎 session/prompt，不再有「分享到对话」Activity 配置（wb_send_component 已废弃）。
 */
public class Config {
    public static final String PREF_NAME = "watchctl";
    /** companion 包名：结果广播必须显式定向到这里，跨应用隐式广播在 Android 8+/HyperOS 会被拦。 */
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
    public String wbPackage = DEFAULT_WB_PACKAGE;
    public String cmdAction = DEFAULT_CMD_ACTION;
    public String resultAction = DEFAULT_RESULT_ACTION;

    /** 模块自身进程（RelayService/Application）使用，读自己的 SharedPreferences。 */
    public static Config loadFromContext(Context ctx) {
        Config c = new Config();
        SharedPreferences p = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        c.enabled = p.getBoolean("enabled", false);
        c.authToken = p.getString("auth_token", p.getString("token", DEFAULT_AUTH_TOKEN));
        c.dshToken = p.getString("dsh_token", "");
        c.wbPackage = p.getString("wb_package", DEFAULT_WB_PACKAGE);
        c.cmdAction = p.getString("cmd_action", DEFAULT_CMD_ACTION);
        c.resultAction = p.getString("result_action", DEFAULT_RESULT_ACTION);
        return c;
    }

    /** Xposed hook 跨进程读取（跑在别的 App 里时需要模块 pref 可世界读）。 */
    public static Config load() {
        Config c = new Config();
        XSharedPreferences p = new XSharedPreferences("com.watchctl.hook", PREF_NAME);
        p.makeWorldReadable();
        try { p.reload(); } catch (Throwable ignored) {}
        c.enabled = p.getBoolean("enabled", false);
        c.authToken = p.getString("auth_token", p.getString("token", DEFAULT_AUTH_TOKEN));
        c.dshToken = p.getString("dsh_token", "");
        c.wbPackage = p.getString("wb_package", DEFAULT_WB_PACKAGE);
        c.cmdAction = p.getString("cmd_action", DEFAULT_CMD_ACTION);
        c.resultAction = p.getString("result_action", DEFAULT_RESULT_ACTION);
        return c;
    }
}
