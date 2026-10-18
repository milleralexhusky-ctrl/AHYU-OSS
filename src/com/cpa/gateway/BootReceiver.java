package com.cpa.gateway;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.cpa.gateway.core.Config;
import com.cpa.gateway.core.Log;

/** Optional auto-start of the gateway after reboot. */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            Config cfg = Config.load(context);

            // 两个开关的语义要分开（设置页写得很清楚）：
            //   autostart = "开机自动启动"      → 开机那一刻是否主动拉起
            //   keepAlive = "被系统杀掉后自动拉起" → 之后要不要维持运行
            //
            // 以前这里 `if (!cfg.autostart) return;` 直接返回，于是关掉"开机自启"时，
            // **连保活也一起失效了**：重启后 JobScheduler 里的任务全没了
            // （schedule() 的所有调用点都要求 App 被启动过），
            // 网关要等用户手动打开一次 App 才会重新登记保活。
            //
            // 现在：autostart 关着也照样登记保活任务（只要 keepAlive 是开的）。
            // job 到点会走 onStartJob 里的 shouldRun 判定，行为与运行时一致。
            if (!cfg.autostart) {
                if (cfg.keepAlive) {
                    Log.i("未开启开机自启：仍登记保活任务（keepAlive 打开）");
                    KeepAliveJobService.schedule(context);
                } else {
                    Log.i("未开启开机自启且未开启保活：不做任何动作");
                }
                return;
            }

            Intent svc = new Intent(context, GatewayService.class).setAction(GatewayService.ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(svc);
            else context.startService(svc);
            Log.i("开机自启：已请求拉起网关服务");
        } catch (Throwable t) {
            // Android 12+ 在部分 ROM 上会拒绝从 BOOT_COMPLETED 直接起前台服务
            // （ForegroundServiceStartNotAllowedException）。以前这里只打一行 w 日志就完了，
            // 开机自启等于静默失效，用户完全不知道。
            // 现在降级：立刻登记那个（加急的）周期保活任务，让它在配额内把网关拉回来。
            Log.w("开机自启直接拉起失败，改为登记保活任务兜底：" + t);
            try {
                KeepAliveJobService.schedule(context);          // 周期巡检
                KeepAliveJobService.scheduleExpeditedPull(context);   // 一次性加急，负责真正拉起来
            } catch (Throwable t2) {
                Log.w("兜底登记保活任务也失败：" + t2);
            }
        }
    }
}
