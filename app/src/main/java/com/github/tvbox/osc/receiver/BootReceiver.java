package com.github.tvbox.osc.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.github.tvbox.osc.ui.activity.HomeActivity;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.orhanobut.hawk.Hawk;

/**
 * 二次开发新增：开机自启动接收器。
 *
 * 监听系统开机完成广播（含部分国产盒子的 QUICKBOOT_POWERON），
 * 若用户在设置中开启了"开机自启"（{@link HawkConfig#BOOT_AUTO_START}，默认关闭），
 * 则拉起主界面 {@link HomeActivity}。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();
        if (action == null) return;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)
                && !"com.htc.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }
        boolean enabled;
        try {
            // Hawk 在 App.onCreate 中初始化；Application 先于 receiver 创建，通常已可用。
            if (!Hawk.isBuilt()) return;
            enabled = Hawk.get(HawkConfig.BOOT_AUTO_START, false);
        } catch (Throwable th) {
            LOG.e("BootReceiver read config failed: " + th.getMessage());
            return;
        }
        if (!enabled) return;

        try {
            Intent home = new Intent(context, HomeActivity.class);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(home);
            LOG.i("BootReceiver: auto-start HomeActivity");
        } catch (Throwable th) {
            LOG.e("BootReceiver start HomeActivity failed: " + th.getMessage());
        }
    }
}
