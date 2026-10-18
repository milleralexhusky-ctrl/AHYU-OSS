package com.cpa.gateway;

import com.cpa.gateway.core.Threads;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.cpa.gateway.core.Config;
import com.cpa.gateway.core.Gateway;
import com.cpa.gateway.core.Log;

/**
 * System-level safety net: a persisted periodic job that brings the gateway back if the phone
 * reclaimed the background process (which really happens under heavy load on aggressive ROMs).
 */
public class KeepAliveJobService extends JobService {

    private static final int JOB_ID = 4711;
    /** 一次性加急拉活任务的 id（与周期任务分开，因为 expedited 和 periodic 互斥）。 */
    private static final int PULL_JOB_ID = 4712;
    private static final long INTERVAL_MS = 15 * 60 * 1000L;

    public static void schedule(Context ctx) {
        try {
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            if (js.getPendingJob(JOB_ID) != null) return;
            // 注意：周期任务**不能**标 expedited——真机实测会抛
            //   IllegalArgumentException: An expedited job cannot have a time delay
            // 所以这里只做"普通周期巡检"；真正需要起前台服务时，
            // 由 onStartJob 去登记那个一次性的加急 job（见 scheduleExpeditedPull）。
            JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(ctx, KeepAliveJobService.class))
                    .setPersisted(true)
                    .setPeriodic(INTERVAL_MS)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                    .build();
            js.schedule(job);
            Log.i("已登记保活任务（每 15 分钟检查一次）");
        } catch (Throwable t) {
            Log.w("登记保活任务失败：" + t);
        }
    }

    /**
     * 登记一个**一次性加急任务**，用来合法地拉起前台服务。
     *
     * 为什么单独开一个 job：Android 12+ 禁止后台直接 startForegroundService
     * （抛 ForegroundServiceStartNotAllowedException），只有加急作业在配额内被允许。
     * 但加急和 setPeriodic 互斥（真机实测 IllegalArgumentException），
     * 所以周期任务只负责"发现该拉活"，拉活本身交给这个一次性加急任务。
     *
     * 一次性任务可以带 setExpedited，不受"不能有 time delay"的限制。
     */
    public static void scheduleExpeditedPull(Context ctx) {
        if (Build.VERSION.SDK_INT < 31) return;   // 31 以下不需要（也不支持）
        try {
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            if (js.getPendingJob(PULL_JOB_ID) != null) return;   // 已有一个在排队，别叠
            JobInfo job = new JobInfo.Builder(PULL_JOB_ID, new ComponentName(ctx, KeepAliveJobService.class))
                    .setExpedited(true)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                    .build();
            int r = js.schedule(job);
            Log.i("已登记一次性加急拉活任务（结果 " + r + "）");
        } catch (Throwable t) {
            Log.w("登记加急拉活任务失败（" + t + "）：仍会由周期任务重试");
        }
    }

    public static void cancel(Context ctx) {
        try {
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js != null) js.cancel(JOB_ID);
        } catch (Throwable ignored) {
        }
    }

    private void dailyRelayCheck() {
        Threads.io(com.cpa.gateway.core.RelayWatch::runDailyIfDue);
    }

    /** 后台自动巡检模型：到点才跑，跑完把调不通的模型写进 badModels。 */
    private void autoModelCheck() {
        Threads.io(com.cpa.gateway.core.Detect::autoIfDue);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        dailyRelayCheck();
        autoModelCheck();
        try {
            Config cfg = Config.load(this);
            /*
             * 三个条件缺一不可：
             *   keepAlive    —— 用户在设置里开着保活；
             *   !userStopped —— 用户**没有主动**按过停止。这一条是关键：
             *                   以前只看 !Gateway.isRunning()，分不清"掉了"和"用户自己关的"，
             *                   于是用户点停止、十几分钟后又被拉起来（表现为"停止无效"）；
             *                   反过来 App 刚打开时被积压的任务先跑，抢在用户之前把网关起来
             *                   （表现为"第一次按启动无效、按第二次才成功"）。
             *   !isRunning   —— 确实没在跑，才需要拉。
             */
            boolean shouldRun = cfg.keepAlive && !cfg.userStopped && !Gateway.isRunning();
            boolean expeditedRun = params != null && params.getJobId() == PULL_JOB_ID;
            if (shouldRun) {
                Log.w("保活任务：检测到网关未运行，重新拉起"
                        + (expeditedRun ? "（加急任务）" : ""));
                Intent svc = new Intent(this, GatewayService.class).setAction(GatewayService.ACTION_START);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc);
                else startService(svc);
            } else if (cfg.userStopped) {
                Log.i("保活任务：用户已主动停止网关，跳过自动拉起");
            }
            // 周期任务里如果发现网关没跑，光靠它自己起不来（Android 12+ 禁止后台起 FGS），
            // 所以顺手登记那个一次性加急任务——它才有资格拉起来。
            if (shouldRun && !expeditedRun) {
                scheduleExpeditedPull(this);
            }
        } catch (Throwable t) {
            // 重点：Android 12+ 从这里抛出来的是 ForegroundServiceStartNotAllowedException，
            // 它意味着"这次没拉起来"，而不是"任务本身坏了"。以前两种都只打一行 w 日志，
            // 用户看不到任何区别。这里把被系统拒绝的情况明确标出来，并提示去开自启动。
            String name = t.getClass().getSimpleName();
            boolean rejected = name.contains("ForegroundServiceStartNotAllowed")
                    || name.contains("BackgroundServiceStartNotAllowed")
                    || name.contains("IllegalStateException");
            if (rejected) {
                Log.e("保活任务被系统拒绝拉起前台服务（" + name + "）："
                        + "需要在系统设置里允许本应用「自启动 / 后台运行」，否则保活无法生效");
            } else {
                Log.w("保活任务执行失败：" + t);
            }
        }
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // 一次性加急任务不需要重排（它本来就是"跑一次就完"）
        if (params != null && params.getJobId() == PULL_JOB_ID) return false;
        // 任务被系统提前掐掉时只要求重排期，不重跑巡检：
        // onStartJob 里已经触发过一次（dailyRelayCheck / autoModelCheck 都有 ifDue 幂等），
        // 这里再跑一次纯属重复。原来两边都跑，高峰期会叠出双份探活。
        return true;
    }
}
