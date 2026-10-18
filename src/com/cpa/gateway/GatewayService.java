package com.cpa.gateway;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.net.InetSocketAddress;
import java.net.Socket;

import com.cpa.gateway.core.Config;
import com.cpa.gateway.core.Gateway;
import com.cpa.gateway.core.Log;

/** Keeps the local gateway alive while the phone is in the user's pocket. */
public class GatewayService extends Service {

    public static final String ACTION_START = "com.cpa.gateway.START";
    public static final String ACTION_STOP = "com.cpa.gateway.STOP";
    private static final String CHANNEL_ID = "cpa_gateway";
    private static final int NOTIF_ID = 8321;
    /** 账号告警：跟"运行状态"分开一条通知，别把状态通知刷新掉。 */
    private static final String CHANNEL_ALERT = "cpa_account_alert";
    private static final int ALERT_NOTIF_ID = 8322;
    /** 电池白名单引导通知（与账号告警分开，别互相覆盖）。 */
    private static final int BATTERY_NOTIF_ID = 8323;
    private String lastAlertFingerprint = "";
    /** WakeLock 超时：极端情况下（服务被系统掐掉、锁没来得及释放）不至于一直钉着 CPU。 */
    private static final long WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L;

    private PowerManager.WakeLock wakeLock;
    /** 给 Server 回调用：有请求进来时把锁（重新）拿上。 */
    private static volatile GatewayService instance;

    /**
     * 服务实例是否还活着。
     *
     * onDestroy 的第一行会把 instance 置 null，所以它是"旧实例已销毁"的可靠信号——
     * 重启流程靠轮询它来替代以前那个硬睡的 400ms 常量。
     */
    public static boolean hasInstance() {
        return instance != null;
    }

    /**
     * 空闲多久就把 CPU 锁还回系统。
     *
     * 网关常驻要"随时能响应"，但一直钉着 PARTIAL_WAKE_LOCK 会让整机无法进入深度睡眠——
     * 这是能效维度上的真实成本（官方版那条锁已经连续持有了 8 小时 45 分）。
     * 空闲 2 分钟就把锁放掉；**有请求到达时系统会唤醒进程**（TCP 唤醒），我们立刻把锁拿回来，
     * 所以只影响"待机后第一个请求"的那点唤醒延迟。
     */
    private static final long IDLE_RELEASE_MS = 2 * 60 * 1000L;
    private volatile boolean watching;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        instance = this;
        ensureBatteryWhitelist();
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            /*
             * 用 stopByUser 而不是 stop()：前者把状态置为 STOPPED_BY_USER，
             * 保活任务据此不再自动拉起。这就是 4.30.19 里那个 Config.userStopped
             * 的作用，现在由状态机表达，不再需要一个独立的布尔字段
             * （也就不会出现 userStopped=true 而网关仍在跑的矛盾组合）。
             */
            Gateway.stopByUser();
            KeepAliveJobService.cancel(this);
            notifyStatus("已停止");
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        // startForeground 不经过 notifyStatus，这里必须手动同步去重状态：
        // 否则紧随其后的第一次 notifyStatus("运行中") 是唯一的纠正机会，
        // 一旦被 ROM 吞掉，通知就永远停在「正在启动」（见 notifyStatus 的注释）。
        lastNotifText = "正在启动";
        startForeground(NOTIF_ID, buildNotification("正在启动"));
        Gateway.init(this);
        /*
         * 不需要再清 Config.userStopped 了：Gateway.start() 会把状态推到
         * STARTING → RUNNING，而"用户主动停止"这个意图由 STOPPED_BY_USER 表达，
         * 一旦进入 STARTING 就自然失效。少一次磁盘写、也少一处不一致的来源。
         */
        try {
            Gateway.start(this);
            Log.i("service started, port " + Gateway.config().port);
            // 立刻刷一次：覆盖大多数情况，且这一条路径只走一次、没有刷屏可防。
            notifyStatus(runningText(), true);
            // 再延时补一次：系统对**刚由 startForeground() 提交**的前台通知有
            // 一小段节流窗口，此期间同 id 的普通 notify() 会被直接丢弃——
            // 所以上面那次在冷启动时经常是白调的（真机实测：服务 T+2s 就绪、
            // 通知直到 T+46s 看门狗那轮才翻成「运行中」）。延时到窗口之外再发一次，
            // 常见情况下通知栏几秒内就对齐，而不是等满一个看门狗周期。
            // 设 3 秒是"够用且不敏感"的值：窗口时长随 ROM 状态波动，追最小值反而脆；
            // 万一这次也落在窗口里，看门狗 45 秒的兜底原样还在，不比之前差。
            com.cpa.gateway.core.Threads.BG.schedule(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (watching) notifyStatus(runningText(), true);
                    } catch (Throwable t) {
                        Log.w("延时刷新通知失败（不影响服务）：" + t);
                    }
                }
            }, 3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            // 启动失败：别拿 WakeLock、别开看门狗（原来失败也照拿不误，空耗电），
            // 只留一条失败通知，等用户手动重试
            Log.e("failed to start gateway: " + e);
            notifyStatus("启动失败：" + e.getMessage());
            return START_NOT_STICKY;
        }
        acquireWakeLock();
        startWatchdog();
        checkAccountAlerts();
        refreshCreditsInBackground();
        // 调试包专用：启动即打开"上游请求体抓取"，并把结果写进日志。
        // 为什么不做成需要 API Key 的调试端点：要抓的是**用户真实客户端**发出的请求
        // （带完整工具调用与多轮历史），而客户端用的是真实 Key——换测试 Key 就跑不起来了。
        // 发布包（Debug.ON=false）整块跳过。
        if (com.cpa.gateway.core.Debug.ON) {
            com.cpa.gateway.core.Upstreams.captureOn(true);
            Log.i("调试包：已打开上游请求体抓取（/v1/debug/capture 可回读）");
        }
        if (Gateway.config().keepAlive) KeepAliveJobService.schedule(this);
        return START_STICKY;
    }

    /**
     * Keeps the CPU available so a backgrounded phone cannot starve the accept loop, and re-arms
     * the listener if a health probe starts failing.
     */
    /**
     * 申请"忽略电池优化"。
     *
     * 真机上查到的实情：com.ahyu 以前**不在**系统白名单里，被省电策略当作后台进程对待
     * （`SmartPower: com.ahyu died->background`），于是出现"服务在跑、端口在听，
     * 但本机连接却超时、推理卡死"这种看起来很诡异的故障。
     * 有权限就直接弹系统确认框（用户点一下"允许"即可），以后开服务都不会再被限制。
     */
    /**
     * 申请忽略电池优化。
     *
     * 真机上查到的实情：com.ahyu 以前**不在**系统白名单里，被省电策略当作后台进程对待
     * （`SmartPower: com.ahyu died->background`），服务在跑、端口在听，但连接会超时。
     *
     * 关键修正：以前无论服务当前是不是前台都直接 startActivity 弹授权页。
     * 而 Android 10+ 对**后台进程启动 Activity** 有硬限制——开机自启、job 拉起、
     * 任务被移除后重启，这些场景本服务都在后台，弹框会被系统静默拦掉，
     * 异常又被这里 catch 吃掉，用户永远等不到那个授权框。
     *
     * 现在分两条路：
     *   ① 服务在前台 → 直接弹系统授权页（用户点一下"允许"即可）
     *   ② 服务在后台 → **不弹**，改为发一条通知，引导用户点进来手动授权
     *      （Android 推荐做法：后台要用户做事，就先给通知）
     */
    private void ensureBatteryWhitelist() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) return;

            // 服务是否处于前台：这是"能不能直接弹 Activity"的判据
            boolean foreground;
            try {
                android.app.ActivityManager am =
                        (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
                android.app.ActivityManager.RunningAppProcessInfo info =
                        new android.app.ActivityManager.RunningAppProcessInfo();
                android.app.ActivityManager.getMyMemoryState(info);
                foreground = info.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE;
            } catch (Throwable t) {
                foreground = false;   // 拿不准就按后台处理（后台路线只会少弹一次，不会崩）
            }

            if (foreground) {
                Log.i("当前未被允许后台运行：正在请求忽略电池优化（否则省电策略会限制网关）");
                android.content.Intent it = new android.content.Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                it.setData(android.net.Uri.parse("package:" + getPackageName()));
                it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(it);
                return;
            }

            Log.i("当前未被允许后台运行，但服务在后台：发通知引导用户手动授权");
            notifyBatteryPrompt();
        } catch (Throwable t) {
            Log.w("请求电池白名单失败：" + t);
        }
    }

    /** 后台路线：发一条可点的通知，把用户带回设置页（而不是静默失败）。 */
    private void notifyBatteryPrompt() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            Intent open = new Intent(this, SettingsActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(this, 3, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(this, CHANNEL_ALERT)
                    : new Notification.Builder(this);
            b.setContentTitle("建议允许后台运行")
                    .setContentText("点这里去开启「忽略电池优化」，否则网关可能被省电策略限制")
                    .setSmallIcon(R.drawable.ic_notify)
                    .setAutoCancel(true)
                    .setContentIntent(pi);
            nm.notify(BATTERY_NOTIF_ID, b.build());
        } catch (Throwable t) {
            Log.w("电池白名单通知发送失败：" + t);
        }
    }

    private void acquireWakeLock() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm == null) return;
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cpa:gateway");
                wakeLock.setReferenceCounted(false);
                Log.i("wake lock 已持有");
            }
            // 已持有也重新 acquire：带超时的 acquire 会顺延到期时间（相当于续期）
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
        } catch (Throwable t) {
            Log.w("wake lock 失败：" + t);
        }
    }

    /** Server 每处理一个真请求都会调这里：把 CPU 锁立刻拿回来（空闲时被放掉过也无所谓）。 */
    public static void poke() {
        GatewayService s = instance;
        if (s == null) return;
        try {
            boolean wasHeld = s.wakeLock != null && s.wakeLock.isHeld();
            s.acquireWakeLock();
            if (!wasHeld && !resumedLogged) {
                resumedLogged = true;
                Log.i("有请求进来，CPU 锁已重新拿上");
            }
        } catch (Throwable t) {
            Log.w("唤醒 CPU 锁失败：" + t);
        }
    }

    private static volatile boolean resumedLogged;

    /** 空闲够久、又没有活跃连接，就把锁还回系统（下次请求会自动拿回）。 */
    private void releaseWakeLockIfIdle() {
        try {
            if (wakeLock == null || !wakeLock.isHeld()) return;
            if (com.cpa.gateway.core.Gateway.activeConnections() > 0) return;
            long idle = System.currentTimeMillis() - com.cpa.gateway.core.Gateway.lastClientRequestMs();
            if (idle < IDLE_RELEASE_MS) return;
            wakeLock.release();
            Log.i("空闲 " + (idle / 1000) + " 秒（阈值 " + (IDLE_RELEASE_MS / 1000)
                    + " 秒），释放 CPU 锁（有请求会自动拿回）");
        } catch (Throwable t) {
            Log.w("释放 wake lock 失败：" + t);
        }
    }

    /** 健康探测周期：45 秒。 */
    private static final long PROBE_INTERVAL_MS = 45 * 1000L;

    /** 连续失败几次就判定网关已"假活"。 */
    private static final int PROBE_FAILS_TO_RESTART = 2;

    /** 重启冷却：这段时间内不再重启，避免探测抖动变成连环重启。 */
    private static final long RESTART_COOLDOWN_MS = 5 * 60 * 1000L;

    /**
     * 看门狗：探测失败到阈值就无条件"先停后起"。
     *
     * 关键在"无条件"：原来只在 !Gateway.isRunning() 时重启，而 isRunning() 只是
     * Server 里的一个 boolean——accept 线程卡死、连接池被占满、监听还在但请求永远
     * 等不到响应时，它照样是 true。于是日志里只留下"健康探测失败 N 次"，网关却一直
     * 挂着：客户端能连上、能建连接，却等不到任何响应。现在先 stop()（关监听、冲掉
     * 卡住的连接池）再 start()，不看那个 boolean；5 分钟冷却防止把刚起来的实例
     * 又立刻打下去。
     */
    private void startWatchdog() {
        if (watching) return;
        watching = true;
        // 看门狗扔进共享 IO 池，不再单独起线程
        com.cpa.gateway.core.Threads.io(() -> {
            int failures = 0;
            int round = 0;
            long lastRestart = 0;
            while (watching) {
                try {
                    Thread.sleep(PROBE_INTERVAL_MS);
                } catch (InterruptedException e) {
                    return;
                }
                if (!watching) return;

                // force=true：看门狗本来就每轮巡检状态，顺便保证通知文案跟得上。
                // 不加这个的话，一旦启动时那次更新被 ROM 吞掉，
                // 去重会让后面每一轮都跳过 —— 永远显示「正在启动」。
                notifyStatus(runningText(), true);
                checkAccountAlerts();
                // CPU 锁的续期/释放必须每轮都跑：
                // 原来放在探活成功之后，而探活成功会 continue —— 那段代码永远走不到，
                // 锁于是只能靠 10 分钟超时自然失效（既没续期、也没"空闲释放"）。
                if (wakeLock != null && wakeLock.isHeld()) {
                    long idle = System.currentTimeMillis()
                            - com.cpa.gateway.core.Gateway.lastClientRequestMs();
                    if (idle < IDLE_RELEASE_MS) {
                        try {
                            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);   // 忙：续期
                        } catch (Throwable ignored) {
                        }
                    }
                }
                releaseWakeLockIfIdle();

                // 绝大多数轮次只看进程内状态（零网络、零新线程）；每 4 轮（约 3 分钟）
                // 才真连一次自己的 /health 做端到端确认。
                round++;
                boolean endToEnd = round % 4 == 0;
                boolean ok = endToEnd ? probe(Gateway.config().port) : Gateway.selfHealthy();
                if (ok) {
                    failures = 0;
                    continue;
                }
                Log.w("健康探测失败（" + (endToEnd ? "端到端回环" : "进程内状态") + "）");
                failures++;
                Log.w("健康探测失败 " + failures + " 次");
                if (failures < PROBE_FAILS_TO_RESTART) continue;
                failures = 0;
                long now = System.currentTimeMillis();
                if (now - lastRestart < RESTART_COOLDOWN_MS) {
                    Log.w("探测连续失败，但距上次重启不足 " + (RESTART_COOLDOWN_MS / 60000)
                            + " 分钟，本次跳过（冷却中）");
                    continue;
                }
                lastRestart = now;
                Log.w("健康探测连续失败，无条件重启网关（先停后起）");
                try {
                    Gateway.stop();
                    Gateway.start(this);
                    Log.i("网关已重新拉起，端口 " + Gateway.runningPort());
                    notifyStatus(runningText(), true);   // 重启是状态跃迁，强制刷一次
                } catch (Exception e) {
                    Log.e("重启网关失败：" + e);
                    notifyStatus("重启失败：" + e.getMessage());
                }
            }
        });
    }

    /**
     * 账号告警：不可用账号集合变了才推一条事件通知，全好了再把通知撤掉。
     *
     * 之前账号掉线只体现在主页那一行小字里（"暂不可用 1"），用户往往等到客户端报错才发现。
     * 这里按"集合指纹"去重，避免看门狗每 45 秒都刷一条通知。
     */
    /**
     * 启动后顺带刷新一次积分（延迟 3 秒、后台执行，不拖慢启动）。
     *
     * 为什么放在这里：积分只有在查询成功时才会更新，而查询失败会保留旧值。
     * 用户开网关时顺手同步一次，省得积分一直停在旧数字上。
     * 全部失败也不影响任何事——原有数值不会被抹掉。
     */
    private void refreshCreditsInBackground() {
        final java.util.List<com.cpa.gateway.core.Config.Provider> targets = new java.util.ArrayList<>();
        for (com.cpa.gateway.core.Config.Provider p : Gateway.config().providers) {
            if (!p.enabled) continue;
            if (Config.TYPE_WORKBUDDY.equals(p.type)
                    || Config.TYPE_TRAE.equals(p.type)
                    || Config.TYPE_LOOMY.equals(p.type)) {
                targets.add(p);
            }
        }
        if (targets.isEmpty()) return;
        try {
            com.cpa.gateway.core.Threads.BG.schedule(() -> {
                boolean changed = false;
                for (com.cpa.gateway.core.Config.Provider p : targets) {
                    try {
                        double v;
                        if (Config.TYPE_WORKBUDDY.equals(p.type)) {
                            v = com.cpa.gateway.core.WbTasks.credits(p);
                        } else if (Config.TYPE_TRAE.equals(p.type)) {
                            v = com.cpa.gateway.core.Trae.credits(p);
                        } else {
                            v = com.cpa.gateway.core.Loomy.points(p);
                        }
                        if (v >= 0) {
                            p.credits = v;
                            p.creditsAt = System.currentTimeMillis() / 1000;
                            changed = true;
                        }
                    } catch (Throwable t) {
                        Log.w("启动积分刷新失败（保留原值）：" + p.name + " · " + t);
                    }
                }
                if (changed) {
                    Gateway.persistSoon();
                    Log.i("启动积分刷新完成");
                }
            }, 3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Throwable t) {
            Log.w("启动积分刷新调度失败：" + t);
        }
    }

    private void checkAccountAlerts() {
        try {
            java.util.List<String> bad = new java.util.ArrayList<>();
            for (com.cpa.gateway.core.Config.Provider p : Gateway.config().providers) {
                if (MainActivity.unusableReason(p) != null) bad.add(p.id + "|" + p.name);
            }
            java.util.Collections.sort(bad);
            String fingerprint = android.text.TextUtils.join(",", bad);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (bad.isEmpty()) {
                // 这里不能因为"指纹没变"就提前 return：进程重启后指纹本来就是空的，
                // 那样恢复健康时挂着的提醒会撤不掉。cancel 成本是零，每次都做。
                if (!lastAlertFingerprint.isEmpty()) {
                    Log.i("账号告警：全部账号恢复正常，已撤掉提醒");
                }
                lastAlertFingerprint = "";
                nm.cancel(ALERT_NOTIF_ID);
                return;
            }
            if (fingerprint.equals(lastAlertFingerprint)) return;
            lastAlertFingerprint = fingerprint;
            createAlertChannel(nm);
            PendingIntent open = PendingIntent.getActivity(this, 2,
                    new Intent(this, UnusableActivity.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(this, CHANNEL_ALERT)
                    : new Notification.Builder(this);
            b.setContentTitle(bad.size() + " 个账号需要处理")
                    .setContentText("登录可能过期或上游调不通，点这里查看并重新登录")
                    .setSmallIcon(R.drawable.ic_notify)
                    .setAutoCancel(true)
                    .setContentIntent(open);
            nm.notify(ALERT_NOTIF_ID, b.build());
            Log.w("账号告警：" + bad.size() + " 个账号不可用");
        } catch (Throwable t) {
            Log.w("账号告警检查失败：" + t);
        }
    }

    private void createAlertChannel(NotificationManager nm) {
        if (nm.getNotificationChannel(CHANNEL_ALERT) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ALERT, "账号告警",
                NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("账号掉线、登录过期时的提醒");
        nm.createNotificationChannel(ch);
    }

    private boolean probe(int port) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress("127.0.0.1", port), 4000);
            s.setSoTimeout(6000);
            s.getOutputStream().write(("GET /health HTTP/1.0\r\nHost: 127.0.0.1\r\nConnection: close\r\n"
                    + "Authorization: Bearer " + Gateway.config().apiKey + "\r\n\r\n").getBytes("UTF-8"));
            s.getOutputStream().flush();
            // drain the whole response; closing early makes the server log a pointless Connection reset
            java.io.InputStream in = s.getInputStream();
            byte[] buf = new byte[1024];
            int total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > 256 * 1024) break;
            }
            return total > 0;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onDestroy() {
        /*
         * 只有"我还登记在案"的实例才有资格收尾。
         *
         * 为什么要这个判断：onStartCommand 每次都会写 instance = this，而旧实例的
         * onDestroy() 常常在新实例已经建好之后才被系统调用（划掉任务、stopSelf
         * 与 START 抢占、ROM 延迟回收都会这样）。原来这里无条件是三件事一起做：
         *   instance = null;          → 把**新实例**的登记抹掉，hasInstance() 从此说谎
         *   Gateway.flushPersist();
         *   Gateway.stop();           → 把**新实例**刚绑好的监听关掉
         * 于是新实例起来了、看门狗 45 秒后发现 unhealthy、重新 start、又被下一个
         * onDestroy 关掉 …… 客户端看到的就是"不停重连"。
         *
         * 现在：instance != this 说明已经有更新的实例接管，旧实例只管释放自己的
         * 资源（wakeLock、watching），不碰全局的 Gateway 和 instance。
         */
        final boolean owner = (instance == this);
        if (owner) instance = null;
        watching = false;
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Throwable ignored) {
            }
        }
        if (owner) {
            Gateway.flushPersist();
            Gateway.stop();
            Log.i("service stopped");
        } else {
            Log.i("旧服务实例退出（已有新实例接管，不动网关）");
        }
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (Gateway.config().keepAlive) {
            Log.w("任务被移除，尝试继续保活");
            // 这是保活路径上**唯一**没有兜底的一处：以前这里裸调 startForegroundService，
            // 而在"用户从最近任务划掉"这个场景下，Android 12+ 拒绝后台起前台服务是常态
            // （ForegroundServiceStartNotAllowedException）。异常从这里冒出去会直接打到系统，
            // 表现为进程崩溃——比"没保住"更糟。
            // 现在：捕获后降级到那个一次性加急任务（它有资格在配额内拉起来）。
            try {
                Intent restart = new Intent(this, GatewayService.class).setAction(ACTION_START);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(restart);
                else startService(restart);
            } catch (Throwable t) {
                Log.w("任务移除后直接拉起被拒（" + t.getClass().getSimpleName()
                        + "），改为登记加急任务兜底");
                try {
                    KeepAliveJobService.schedule(this);
                    KeepAliveJobService.scheduleExpeditedPull(this);
                } catch (Throwable t2) {
                    Log.w("兜底登记保活任务失败：" + t2);
                }
            }
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** What the notification should say right now. */
    /** Deliberately one short word: the notification must stay readable at a glance. */
    private String runningText() {
        /* 通知文案统一从状态取，和磁贴、指示灯用同一个词源。 */
        return Gateway.state().label();
    }

    /** Re-posts the foreground notification so it shows the live state instead of "starting". */
    private volatile String lastNotifText = null;

    /**
     * 通知只在内容变化时更新：原来 startWatchdog() 每分钟无条件 rebuild 一次通知，
     * 哪怕文字完全一样——纯属浪费，还会让通知栏闪一下。
     */
    private void notifyStatus(String text) {
        notifyStatus(text, false);
    }

    /**
     * @param force true 时跳过去重，无条件重发一次。
     *
     * 为什么需要 force：startForeground() 提交通知时**不走**这个方法，
     * 所以 lastNotifText 那时还是 null / 旧值。启动成功后的第一次
     * notifyStatus("运行中") 就成了**唯一一次**把「正在启动」改掉的机会；
     * 只要那一次 notify() 没生效（同 id 紧挨着 startForeground 提交、
     * 又用的是自定义 RemoteViews，部分 ROM 会吞掉这次更新），
     * 看门狗后续每分钟的调用都会被去重拦住 —— 通知就永远停在「正在启动」，
     * 而服务其实早就跑起来了（真机上就是这么卡住的）。
     *
     * 所以看门狗那一路要用 force=true：它本来就在做“状态对不对”的巡检，
     * 顺带保证通知文案跟得上，不受去重影响。
     */
    private void notifyStatus(String text, boolean force) {
        if (!force && text != null && text.equals(lastNotifText)) return;
        lastNotifText = text;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
        } catch (Throwable t) {
            Log.w("更新通知失败：" + t);
        }
    }

    /** Left/right split row; colours follow the system's light or dark notification style. */
    private android.widget.RemoteViews stateRow(String state) {
        android.widget.RemoteViews rv = new android.widget.RemoteViews(getPackageName(), R.layout.notif_row);
        boolean night;
        try {
            int mode = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            night = mode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            night = false;
        }
        rv.setTextViewText(R.id.notif_title, "CLIProxy");
        rv.setTextViewText(R.id.notif_state, state);
        rv.setImageViewResource(R.id.notif_dot, dotFor(state));
        rv.setTextColor(R.id.notif_title, night ? 0xFFF2F4F8 : 0xFF16181D);
        rv.setTextColor(R.id.notif_state, night ? 0xFF9AA5B4 : 0xFF5F6368);
        return rv;
    }

    /**
     * 指示灯：绿=在服务、琥珀=启动中、红=失败、灰=其它。
     *
     * 以前这里靠 {@code s.contains("运行中")} 从**中文文案**反推状态：
     * 状态先被编码成一句话，再靠子串猜回颜色。改一个字（"运行中"→"正在运行"）
     * 灯就变灰，而编译器不会提醒。
     *
     * 现在优先读 {@link GatewayState}；文本只用于**非状态类**的提示
     * （例如"重启失败：..." 这种临时消息，它们不对应某个稳定状态）。
     */
    private int dotFor(String text) {
        com.cpa.gateway.core.GatewayState st = Gateway.state();
        if (st != null && text != null && text.equals(st.label())) {
            switch (st) {
                case RUNNING:
                    return R.drawable.notif_dot_green;
                case STARTING:
                    return R.drawable.notif_dot_amber;
                case FAILED:
                    return R.drawable.notif_dot_red;
                default:
                    return R.drawable.notif_dot_gray;
            }
        }
        /* 临时消息（"重启失败：xxx" / "启动失败：xxx"）没有对应的稳定状态，按词判。 */
        String s = text == null ? "" : text;
        if (s.contains("失败") || s.contains("错误")) return R.drawable.notif_dot_red;
        if (s.contains("启动") || s.contains("正在")) return R.drawable.notif_dot_amber;
        return R.drawable.notif_dot_gray;
    }

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "网关运行状态", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("CLIProxy 多供应商网关");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(String subtitle) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, GatewayService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        // "CLIProxy" on the left, the state pinned to the right: only a custom layout can do that
        String state = subtitle == null ? "" : subtitle;
        b.setContentTitle("CLIProxy")
                .setContentText(state)
                .setSmallIcon(R.drawable.ic_notify)
                .setStyle(new Notification.DecoratedCustomViewStyle())
                .setCustomContentView(stateRow(subtitle == null ? "" : subtitle))
                .setCustomBigContentView(stateRow(subtitle == null ? "" : subtitle))
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "停止", stop).build());
        return b.build();
    }
}
