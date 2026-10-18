package com.cpa.gateway.core;

import android.content.Context;

/** Process-wide singleton that owns the configuration and the running proxy server. */
public final class Gateway {

    private static volatile Config config;
    private static Server server;

    /**
     * 网关状态的**唯一**权威表述。
     *
     * 所有"在不在跑 / 要不要自动拉起 / 界面显示什么"的问题都从这里取答案，
     * 不再各自去看 server != null、Server.running、按钮文案……
     * 写入只发生在 start()/stop() 和标记失败的那一处，见 {@link #state(GatewayState)}。
     */
    private static volatile GatewayState state = GatewayState.STOPPED_IDLE;
    private static android.content.Context appCtx;

    private Gateway() {}

    public static synchronized void init(Context ctx) {
        appCtx = ctx.getApplicationContext();
        // 上游连接池：默认每 host 只留 5 条，多账号同 host（如 3 个 WorkBuddy 账号）
        // 并发一高就排队；提到 16 保吞吐。必须在第一次建连接前设置。
        try {
            if (System.getProperty("http.maxConnections") == null) {
                System.setProperty("http.maxConnections", "16");
            }
        } catch (Throwable ignored) {
        }
        if (com.cpa.gateway.GlassBackground.photoBitmap == null) {
            // 原来在主线程同步解码 358KB 背景大图，服务启动时卡一下；挪后台，
            // 解出来之前 GlassBackground 会画渐变兜底（draw 里已判空）。
            // 这里刻意不再预热底纹：后台服务状态（没有 Activity）根本用不到它，
            // 常驻那 3.5MB 纯属白占（实测后台 PSS 11.2MB 里它占三分之一）。
            // 底纹改由 UI 第一次挂载时同步备好——预置底纹 14ms，不会让首帧慢。
        }
        if (config != null && config.providers.isEmpty()) {
            // UI 与 Service 各持一份内存配置时，谁先启动都可能读到旧值；磁盘才是权威
            try {
                Config disk = Config.load(ctx.getApplicationContext());
                if (!disk.providers.isEmpty()) {
                    config.providers.clear();
                    config.providers.addAll(disk.providers);
                    Log.i("初始化时从磁盘补回 " + disk.providers.size() + " 个账号");
                }
            } catch (Throwable ignored) {
            }
        }
        /* 自检：纯进程内计算、不联网、不耗额度，结果只写一行日志。
           目的不是"启动时做体检"，而是让"请求有没有被静默削薄"这类问题
           在每次启动都留下痕迹 —— 它们不报错，只在模型表现上反映出来。
           放后台线程：解析几个样例请求虽然只有毫秒级，也没必要占启动。 */
        try {
            com.cpa.gateway.core.Threads.io(com.cpa.gateway.core.Selftest::runAndLog);
        } catch (Throwable t) {
            Log.w("自检未启动：" + t);
        }
        /* 账号短标签固化：客户端模型名的前缀来自这里（WB1/kimi-k3-1），
           必须在任何对外列表生成之前定下来，并且**只定一次**。
           老配置升级上来时这一步会把标签按当时的顺序写死，与旧行为一致，
           客户端不用改；此后停用/启用/新增账号都不会让已有标签漂移。 */
        try {
            if (config != null && com.cpa.gateway.core.ShortName.ensureLabels(config.providers)) {
                Log.i("账号短标签已固化并落盘（此后不再随启用状态变化）");
                config.saveQuick(appCtx);
            }
        } catch (Throwable t) {
            Log.w("账号短标签固化失败（本次沿用即时计算）：" + t);
        }
        // 价格表（估算成本用）：后台加载一次，别挡启动
        try {
            com.cpa.gateway.core.Prices.ensureLoaded();
        } catch (Throwable ignored) {
        }
        // 模型能力表：和价格表同一个文件、同一套匹配规则，启动时一起加载。
        // 失败不影响任何功能——查不到就不显示能力标签。
        try {
            com.cpa.gateway.core.ModelCaps.ensureLoaded();
        } catch (Throwable ignored) {
        }
        /*
         * 历史选路样本：从 usage.tsv 聚合出各 渠道/模型 的成功率/耗时/成本种子。
         * 放在价格表(ModelCaps/Prices)之后 —— 它算成本要用 Prices.costUsd；
         * Prices 自己也是异步加载的，这里拿到 null 就按"成本未知"处理，不影响其它两项。
         */
        try {
            com.cpa.gateway.core.Stats.ensureLoaded();
        } catch (Throwable ignored) {
        }
        if (config == null) {
            try {
                config = Config.load(ctx);
            } catch (Throwable t) {
                // never fall back to a fresh default and save it: that wipes real accounts
                Log.e("配置读取失败，保持内存中的配置不落盘：" + t);
                if (config == null) config = new Config();
            }
        }
        schedulePlaintextMigration(ctx.getApplicationContext());
    }

    /** 明文迁移只跑一次的标记（init 可能被 UI 与 Service 各调一次）。 */
    private static volatile boolean migrationScheduled = false;

    /**
     * 启动自愈：把磁盘上还躺着的明文敏感字段迁回密文。
     *
     * 为什么放在这里、这么放：
     *   - **在 Config 加载完成之后**：上面那块 `config = Config.load(ctx)` 跑完
     *     才轮到它，不会和读配置抢同一个文件；
     *   - **后台线程**：Android Keystore 首次生成密钥可能慢，绝不能占主线程；
     *   - **未就绪不污染 lastSealFailed**：配置文件不存在/读不出时，
     *     {@link Secret#migratePlaintextOnStartup} 直接返回 NOT_READY 而不置位，
     *     避免把一次时序问题误记成一次真实的加密失败。
     *
     * 只调一次：init 会被 MainActivity 和 GatewayService 各调一次，
     * 重复跑虽然是幂等的，但没必要做两遍 Keystore 操作。
     */
    private static void schedulePlaintextMigration(final android.content.Context ctx) {
        if (ctx == null || migrationScheduled) return;
        migrationScheduled = true;
        try {
            // 走 IO 池而不是 BG：BG 是**单线程**、专门串行"配置合并写"这类小活，
            // Keystore 首次生成密钥可能慢，占住它会把配置落盘一起拖住。
            Threads.io(new Runnable() {
                @Override
                public void run() {
                    try {
                        java.io.File f = new java.io.File(ctx.getFilesDir(), "gateway.json");
                        Secret.migratePlaintextOnStartup(f);
                    } catch (Throwable t) {
                        Log.w("启动自愈线程异常（不影响启动）：" + t);
                    }
                }
            });
        } catch (Throwable t) {
            Log.w("启动自愈调度失败（不影响启动）：" + t);
        }
    }

    /** Persists the current configuration; used when an upstream rotates its own credentials. */
    public static android.content.Context context() {
        return appCtx;
    }

    public static synchronized void persist() {
        Config c = config;
        if (c != null && appCtx != null) c.save(appCtx);
    }

    private static java.util.concurrent.ScheduledFuture<?> persistTask;

    /**
     * 热路径用：1.5 秒内的多次调用合并成一次磁盘写，且不在请求线程上写。
     *
     * 原来 token 一刷新、探活一结束就同步全量写盘（还带 .bak + 时间戳备份轮转），
     * 聊天请求线程会被磁盘 IO 拖住；现在防抖 + 后台写。用户显式保存继续走 persist()。
     */
    public static void persistSoon() {
        synchronized (Gateway.class) {
            if (persistTask != null) persistTask.cancel(false);
            persistTask = Threads.BG.schedule(new Runnable() {
                @Override
                public void run() {
                    persistQuick();
                }
            }, 1500, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 退出前刷盘：把 persistSoon() 攒着还没写的配置立刻落盘。
     * 不调的话，退出前 1.5 秒内的 token 刷新会丢（下次启动多一次刷新请求，不丢账号）。
     */
    public static void flushPersist() {
        synchronized (Gateway.class) {
            if (persistTask != null) {
                persistTask.cancel(false);
                persistTask = null;
            }
        }
        persistQuick();
    }

    /** 合并写的实际落盘：只写文件，不做备份轮转（备份留给用户显式保存）。 */
    private static void persistQuick() {
        Config c;
        android.content.Context ctx;
        synchronized (Gateway.class) {
            persistTask = null;
            c = config;
            ctx = appCtx;
        }
        if (c != null && ctx != null) c.saveQuick(ctx);
    }

    public static Config config() {
        Config c = config;
        if (c == null) {
            c = new Config();
            config = c;
        }
        return c;
    }

    public static synchronized void save(Context ctx) {
        save(ctx, false);
    }

    public static synchronized void save(Context ctx, boolean allowEmptyProviders) {
        Config c = config;
        if (c != null) c.save(ctx, allowEmptyProviders);
    }

    /**
     * Re-reads the file and merges it into the live object, so every holder (UI + server) sees the
     * change. Replacing the instance would leave the running server pointing at stale providers.
     */
    /** 账号身份：优先用 id，没有就用"类型/名字"。 */
    private static String keyOf(Config.Provider p) {
        return (p.id != null && !p.id.isEmpty()) ? p.id : (p.type + "/" + p.name);
    }

    /**
     * 同步强写：登录、导入这类"必须马上落盘"的操作走它。
     *
     * 之前它们用 persistSoon()（防抖 1.5 秒），而 MainActivity 一回到前台就会 reload()，
     * 那时磁盘上还是旧配置——**刚登录进去的账号会被磁盘版本冲掉**（真机上就是这么丢的 WorkBuddy）。
     */
    public static synchronized void saveNow(Context ctx) {
        Config c = config();
        if (c == null || ctx == null) return;
        try {
            c.save(ctx.getApplicationContext());
            Log.i("配置已强写落盘：账号 " + c.providers.size() + " 个");
        } catch (Throwable t) {
            Log.w("强写失败：" + t);
        }
    }

    /**
     * 从磁盘合并（**取并集**，不再"用磁盘覆盖内存"）。
     *
     * 原来的实现是 `cur.providers.clear(); cur.providers.addAll(fresh.providers)`——
     * 只要磁盘比内存旧，内存里刚加的账号就没了。现在：磁盘有的补进来，内存有的留着；
     * 合并后如果内存比磁盘更全，顺手写回磁盘。
     */
    public static synchronized Config reload(Context ctx) {
        Config fresh;
        try {
            fresh = Config.load(ctx.getApplicationContext());
        } catch (Throwable t) {
            Log.w("reload 跳过（读取失败）：" + t);
            return config();
        }
        Config cur = config();
        int before = cur.providers.size();
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (Config.Provider p : cur.providers) keys.add(keyOf(p));
        int added = 0;
        for (Config.Provider p : fresh.providers) {
            if (keys.add(keyOf(p))) {
                cur.providers.add(p);
                added++;
            }
        }
        cur.port = fresh.port;
        cur.apiKey = fresh.apiKey;
        cur.lanAccess = fresh.lanAccess;
        cur.keepAlive = fresh.keepAlive;
        Log.i("reload：内存 " + before + " → " + cur.providers.size()
                + " 个账号（磁盘 " + fresh.providers.size() + "，合并补入 " + added + "）");
        if (cur.providers.size() > fresh.providers.size()) {
            try {
                cur.save(ctx.getApplicationContext());
                Log.i("reload：内存比磁盘更全，已写回磁盘");
            } catch (Throwable t) {
                Log.w("reload 写回失败：" + t);
            }
        }
        return cur;
    }

    public static synchronized void replace(Context ctx, Config c) {
        config = c;
        c.save(ctx);
    }

    /**
     * 用备份里的配置整体替换当前配置，并把网关按新配置重启一遍。
     *
     * 为什么不直接 replace()：
     *   1. 备份里的敏感字段是「Secret 密文（本机 Keystore 密钥）」或「明文」两种之一 ——
     *      解出来的是 JSON，必须先落盘再让 Config.load() 走一遍正常加载路径（那里会
     *      Secret.open 解密、并补齐缺省字段）。直接把 JSONObject 当成内存 Config 用，
     *      会绕过所有字段级容错，是导入类功能最常见的翻车点。
     *   2. 旧配置必须留一条后路：覆盖前先另存 gateway.json.bak。用户"导错文件"
     *      的概率远高于"备份文件损坏"，可回滚比严格的校验更救命。
     *   3. 端口/局域网开关这些在备份里也可能变了，所以要 stop() → start()，
     *      不能只换 config 对象。
     *
     * 调用方必须在后台线程：这里有磁盘 IO 和 Keystore 调用。
     */
    public static synchronized void replaceConfigFromBackup(Context ctx, org.json.JSONObject restored)
            throws Exception {
        if (restored == null) throw new IllegalArgumentException("备份内容为空");
        /* 1) 旧配置另存一份（失败不阻断导入，但要记日志：宁可丢备份也不要丢用户的当前配置） */
        try {
            java.io.File cur = Config.file(ctx);
            if (cur.exists() && cur.length() > 0) {
                java.io.File bak = new java.io.File(cur.getParentFile(), "gateway.json.before-import.bak");
                byte[] data = Config.readAllPublic(cur);
                java.io.FileOutputStream os = new java.io.FileOutputStream(bak);
                try {
                    os.write(data);
                    os.flush();
                } finally {
                    os.close();
                }
                Log.i("导入前已备份当前配置到 " + bak.getName());
            }
        } catch (Throwable t) {
            Log.w("导入前备份当前配置失败（继续导入）：" + t);
        }

        /* 2) 校验能解析，避免把一份坏 JSON 写进去把用户锁在门外 */
        Config parsed = Config.fromJson(Secret.open(new org.json.JSONObject(restored.toString())));
        if (parsed == null) throw new IllegalStateException("备份内容无法解析为配置");

        /* 3) 停网关 → 落盘（走正常 save，让 Secret 重新用本机密钥加密）→ 重新加载 → 起网关 */
        boolean wasRunning = isRunning();
        try {
            stop();
        } catch (Throwable t) {
            Log.w("导入时停止网关失败（继续）：" + t);
        }

        config = parsed;
        config.save(ctx, true);          // allowEmptyProviders=true：备份里可以没有账号

        Config reloaded = Config.load(ctx);
        if (reloaded != null) config = reloaded;

        if (wasRunning) {
            try {
                start(ctx);
                Log.i("导入完成，网关已按新配置重新启动");
            } catch (Throwable t) {
                Log.e("导入完成但网关重启失败：" + t);
                throw new IllegalStateException("配置已恢复，但网关启动失败：" + t.getMessage());
            }
        } else {
            Log.i("导入完成（网关原本未运行，未自动启动）");
        }
    }

    /**
     * 对外是否可服务。
     *
     * 语义没变（调用点二十余处，一行都不用改），但实现从"看 server 字段"
     * 变成"看唯一状态"——两者不一致这件事从此不成立了。
     */
    public static boolean isRunning() {
        return state.isServing();
    }

    /** 当前状态（只读）。想判断要不要自动拉起用 {@link GatewayState#shouldAutoStart()}。 */
    public static GatewayState state() {
        return state;
    }

    /**
     * 唯一的写入点。
     *
     * 之所以集中到一处：以前 userStopped / instance / server 各自被改，
     * 能组出"用户已停止但网关在跑"这种不可能的状态。
     */
    private static synchronized void state(GatewayState next) {
        GatewayState prev = state;
        if (prev == next) return;
        state = next;
        Log.i("网关状态：" + prev + " → " + next);
    }

    /** 用户主动停止（保活不许拉回来）。 */
    public static synchronized void markStoppedByUser() {
        state(GatewayState.STOPPED_BY_USER);
    }

    /** 非用户原因停下（意外掉线等，保活应该拉回来）。 */
    public static synchronized void markStoppedIdle() {
        if (state == GatewayState.STOPPED_BY_USER) return;   /* 用户意图优先 */
        state(GatewayState.STOPPED_IDLE);
    }

    /** 当前运行的 Server 实例（core 包内做"进程内探测"要用）。 */
    static Server currentServer() {
        return server;
    }

    /** 最近一次真流量请求的时间（毫秒）。跨包访问 Server 的包级成员要走这里。 */
    public static long lastClientRequestMs() {
        return Server.lastClientRequestMs();
    }

    /** 当前活跃连接数（判断"忙不忙"，用于决定 CPU 锁要不要留）。 */
    public static synchronized int activeConnections() {
        Server s = server;
        return s == null ? 0 : s.activeCount();
    }

    /** 进程内健康快照（不建 socket）：看门狗的绝大多数轮次用这个就够。 */
    public static synchronized boolean selfHealthy() {
        Server s = server;
        return s != null && s.healthy();
    }

    public static synchronized int runningPort() {
        return server == null ? 0 : server.port();
    }

    public static synchronized void start(Context ctx) throws Exception {
        if (isRunning()) return;
        state(GatewayState.STARTING);
        try {
            server = new Server(config());
            server.start();
            state(GatewayState.RUNNING);
        } catch (Exception e) {
            /* 启动失败要留下痕迹：以前只是 server 字段指向一个没跑起来的实例，
               从外部看和"没启动"一模一样，看门狗和保活都不知道该不该重试。 */
            server = null;
            state(GatewayState.FAILED);
            throw e;
        }
    }

    /** 停止网关（程序原因：重启、销毁、看门狗）。状态归到 STOPPED_IDLE，保活可再拉。 */
    public static synchronized void stop() {
        stop(false);
    }

    /** 用户主动停止。状态归到 STOPPED_BY_USER，保活**不许**再拉。 */
    public static synchronized void stopByUser() {
        stop(true);
    }

    private static synchronized void stop(boolean byUser) {
        /*
         * 状态先落定，再停实例。
         *
         * 即使 server 已经是 null 也要改状态：用户点停止时网关可能本来就没跑，
         * 但"用户意图"必须被记住，否则保活过一会儿又把它拉起来（4.30.19 的坑）。
         */
        if (byUser) state(GatewayState.STOPPED_BY_USER);
        else if (state != GatewayState.STOPPED_BY_USER) state(GatewayState.STOPPED_IDLE);

        Server s = server;
        if (s == null) return;
        /*
         * 顺序很关键：先把字段置空，再停实例。
         *
         * 反过来的话（原来是先 s.stop() 再 server = null），中间那一段里 server 仍指向
         * 一个已经关掉监听的实例：selfHealthy() 看到 server != null 会去问它，它答 false，
         * 看门狗于是再记一次失败、再触发一次重启；而 toggleGateway() 那条路径还会
         * 单独再调一次 Gateway.stop()，两次 stop 撞在一起。
         *
         * 把字段先摘掉，任何并发进来看到的都是"没有网关"，语义干净：
         * isRunning() == false、selfHealthy() == false、start() 会真正重建。
         */
        server = null;
        s.stop();
    }

    public static String hostForClients() {
        Config c = config();
        return c.lanAccess ? "0.0.0.0" : "127.0.0.1";
    }
}
