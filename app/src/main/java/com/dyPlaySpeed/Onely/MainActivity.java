package com.dyPlaySpeed.Onely;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

/**
 * 倍速设置 UI。
 *
 * API 102 下模块 App 不再被框架注入，也不能再用 MODE_WORLD_READABLE /
 * 直接写模块数据目录的方式与 hook 进程共享配置；
 * 统一通过 libxposed service 的 RemotePreferences 读写。
 */
public class MainActivity extends Activity {

    private EditText speedEdit;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        speedEdit = findViewById(R.id.editTextText);
        Button button = findViewById(R.id.button);

        SharedPreferences prefs = ModuleApplication.getRemotePreferences();
        if (prefs == null) {
            Toast.makeText(this,
                    "Xposed 服务未连接：请确认 LSPosed 已启用本模块并重启本应用",
                    Toast.LENGTH_LONG).show();
        } else {
            speedEdit.setText(String.valueOf(
                    prefs.getFloat(PrefsConstants.KEY_SPEED, PrefsConstants.DEFAULT_SPEED)));
        }

        button.setOnClickListener(v -> {
            SharedPreferences p = ModuleApplication.getRemotePreferences();
            if (p == null) {
                Toast.makeText(this, "Xposed 服务未连接，设置未保存", Toast.LENGTH_LONG).show();
                return;
            }
            try {
                float speed = Float.parseFloat(speedEdit.getText().toString());
                if (speed <= 0) throw new NumberFormatException("speed must be positive");
                // RemotePreferences 的 commit 会同步到框架数据库，hook 进程下一次读取生效
                p.edit().putFloat(PrefsConstants.KEY_SPEED, speed).commit();
                Toast.makeText(this, "设置成功，下一个视频生效", Toast.LENGTH_LONG).show();
            } catch (NumberFormatException ignored) {
                Toast.makeText(this, "请输入合法的数字（如 1.5）", Toast.LENGTH_LONG).show();
            }
        });
    }
}
