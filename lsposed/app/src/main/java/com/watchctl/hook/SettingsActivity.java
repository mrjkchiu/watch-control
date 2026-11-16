package com.watchctl.hook;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 极简设置页：把 Config 需要的字段写进模块自己的 SharedPreferences。
 * 主中继（静态 CommandReceiver → CommandProcessor）通过 loadFromContext() 读取。
 *
 * 0.1.2 起指令一律走 dsh-go 引擎 session/prompt（HTTP 127.0.0.1:3080），不再用
 * 后台 ACTION_SEND 拉 WorkBuddy 界面，故不再有「WB 分享 Activity」这一项。
 * 「enabled」只是可选的 LSPosed 框架 hook 开关（放行后台拉界面），与指令写入无关：
 * 静态接收器不依赖 LSPosed 激活，enabled=false 时指令照样送达。
 * dsh_token 这类运行期变更也可直接由 companion 发 com.watchctl.SET 广播写入。
 */
public class SettingsActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        // ⚠️ 不能用 MODE_WORLD_READABLE：Android 7.0+ 会直接抛 SecurityException，导致本设置页一打开就崩。
        // 改用 MODE_PRIVATE，并在保存后把 pref 文件显式置为可全局读，供 XSharedPreferences（跑在别的进程）读取。
        final SharedPreferences sp = getSharedPreferences("watchctl", MODE_PRIVATE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);

        TextView tip = new TextView(this);
        tip.setText("指令经引擎 session/prompt 写入 WorkBuddy；\n"
                + "无需配置分享 Activity，也无需 LSPosed 激活/作用域。\n"
                + "（仅需保证引擎在 127.0.0.1:3080 可写）");
        tip.setTextSize(14);
        root.addView(tip);

        final EditText auth = field(root, "指令鉴权 token（companion 同值，≥8 字符）",
                sp.getString("auth_token", Config.DEFAULT_AUTH_TOKEN));
        final EditText dsh = field(root, "dsh-go 引擎 token（引擎免鉴权可留空）",
                sp.getString("dsh_token", ""));
        final EditText pkg = field(root, "WorkBuddy 包名",
                sp.getString("wb_package", Config.DEFAULT_WB_PACKAGE));

        Button save = new Button(this);
        save.setText("保存配置");
        save.setOnClickListener(v -> {
            SharedPreferences.Editor e = sp.edit()
                .putBoolean("enabled", true)
                .putString("auth_token", auth.getText().toString().trim())
                .putString("dsh_token", dsh.getText().toString().trim())
                .putString("wb_package", pkg.getText().toString().trim());
            e.apply();
            // 让 XSharedPreferences（跑在 WorkBuddy / 系统进程）能读到私有 pref
            try {
                java.io.File f = new java.io.File(getFilesDir().getParentFile(), "shared_prefs/watchctl.xml");
                f.setReadable(true, false);
            } catch (Throwable ignored) { }
            Toast.makeText(this, "已保存（指令链路无需 LSPosed 激活）", Toast.LENGTH_LONG).show();
        });
        root.addView(save);
        setContentView(root);
    }

    private EditText field(LinearLayout root, String label, String value) {
        TextView tv = new TextView(this);
        tv.setText(label);
        root.addView(tv);
        EditText et = new EditText(this);
        et.setText(value);
        root.addView(et);
        return et;
    }
}
