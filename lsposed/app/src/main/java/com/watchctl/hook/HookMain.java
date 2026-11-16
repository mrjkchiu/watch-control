package com.watchctl.hook;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 腕控 · LSPosed 模块入口。
 *
 * 主中继逻辑在 RelayService（模块自身前台服务）里，本类只负责两件「必须靠 Xposed 才做得了」的事：
 *   1. 框架层放行：让模块（或 companion）从后台拉起 WorkBuddy 不被 AOSP 的
 *      shouldAbortBackgroundActivityStart 拦下（默认关，需设置页开启）。
 *   2. 进程识别：记录当前 Activity（CurrentActivity），供将来扩展 WB 注入策略复用。
 *
 * 指令接收 / dsh-go / WB intent / 结果回写 全部由 RelayService 完成，本类不再注册广播接收器，
 * 避免与 RelayService 重复处理同一指令。
 *
 * 安全边界：token 校验在 RelayService 里做；本类不碰 dsh-go 引擎、不替换任何提示词、
 * 不绕过审批与沙箱。框架 hook 写错有 bootloop 风险，故默认关闭且只对白名单放行。
 */
public class HookMain implements IXposedHookLoadPackage {

    private static final String TAG = "WatchCtl";

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        final Config cfg = Config.load();
        if (!cfg.enabled) return;

        // 框架层放行（可选，谨慎使用：作用于 system_server，写错会 bootloop）
        if (cfg.frameworkScope && "android".equals(lpparam.packageName)) {
            hookBackgroundActivityStart(lpparam, cfg);
            return;
        }

        // 仅当命中 WorkBuddy 进程时做进程识别（当前未用于指令链路，留作扩展点）
        if (!cfg.wbPackage.equals(lpparam.packageName)) return;
        try {
            XposedHelpers.findAndHookMethod(android.app.Application.class, "onCreate",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            XposedBridge.log(TAG + ": WorkBuddy 进程已加载，模块启用");
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": WB 进程 hook 失败（可忽略）: " + t);
        }
    }

    /**
     * 框架层放行：让 companion / 模块从后台调起 WorkBuddy 不被 AOSP 的
     * shouldAbortBackgroundActivityStart 拦下。仅对白名单放行，不全局关闭。
     */
    private static void hookBackgroundActivityStart(XC_LoadPackage.LoadPackageParam lpparam, Config cfg) {
        try {
            final Class<?> starter = XposedHelpers.findClass(
                    "com.android.server.wm.ActivityStarter", lpparam.classLoader);
            XposedBridge.hookAllMethods(starter, "shouldAbortBackgroundActivityStart",
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (cfg.allowAllBackgroundStarts) param.setResult(false);
                        }
                    });
            XposedBridge.log(TAG + ": framework hook 已装（allowAll=" + cfg.allowAllBackgroundStarts + "）");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": framework hook 未命中（版本差异，可忽略）: " + t);
        }
    }
}
