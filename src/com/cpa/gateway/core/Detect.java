package com.cpa.gateway.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * One-tap channel check, sized for a phone holding ~20 accounts and a few hundred models.
 *
 * The work runs inside the gateway and publishes a small snapshot the UI can poll, instead of one
 * long HTTP request that cannot show anything before the slowest channel answers. Phase 1 refreshes
 * every account's model catalogue in parallel; phase 2 probes a few models per account in parallel
 * with a hard per-request timeout. Failures feed the same badModels list the background sweep uses,
 * so "listed but unusable" models stay hidden consistently.
 */
public final class Detect {

    private Detect() {}

    static final class Progress {
        volatile boolean running = false;
        volatile boolean cancel = false;
        volatile boolean cancelled = false;
        volatile String phase = "idle";
        volatile int total = 0;
        /** 这一轮每账号抽了几个模型当样本（界面要如实说明这是抽样）。 */
        volatile int per = 0;
        // 计数必须是原子的：探测是 8 路并发，而 volatile int 的 ++ 是
        // "读-改-写"三步、不具原子性，并发下会丢更新——真机上表现为
        // 进度数字偏小、永远追不上 total，看起来像卡住或失败。
        final java.util.concurrent.atomic.AtomicInteger done =
                new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger passed =
                new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger failed =
                new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger hidden =
                new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger refreshed =
                new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger refreshFailed =
                new java.util.concurrent.atomic.AtomicInteger();
        volatile long startedAt = 0L;
        volatile long finishedAt = 0L;
        volatile String current = "";
        final List<JSONObject> accounts = Collections.synchronizedList(new ArrayList<JSONObject>());
    }

    /** 防重入闸门（CAS 原子置位）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean RUNNING =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private static final Progress P = new Progress();

    public static boolean running() {
        return P.running;
    }

    public static JSONObject stop() {
        if (P.running) {
            P.cancel = true;
            Log.i("一键检测：收到停止请求");
        }
        return status();
    }

    public static JSONObject status() {
        JSONObject out = new JSONObject();
        try {
            out.put("running", P.running);
            out.put("cancelled", P.cancelled);
            out.put("phase", P.phase);
            out.put("perAccount", P.per);
            out.put("total", P.total);
            out.put("done", P.done.get());
            out.put("passed", P.passed.get());
            out.put("failed", P.failed.get());
            out.put("hidden", P.hidden.get());
            out.put("refreshed", P.refreshed.get());
            out.put("refreshFailed", P.refreshFailed.get());
            out.put("current", P.current);
            out.put("startedAt", P.startedAt);
            out.put("finishedAt", P.finishedAt);
            out.put("elapsedMs", (P.finishedAt > 0 ? P.finishedAt : System.currentTimeMillis())
                    - (P.startedAt > 0 ? P.startedAt : System.currentTimeMillis()));
            JSONArray rows = new JSONArray();
            synchronized (P.accounts) {
                for (JSONObject row : P.accounts) {
                    synchronized (row) {
                        rows.put(new JSONObject(row.toString()));
                    }
                }
            }
            out.put("accounts", rows);
        } catch (Exception e) {
            try {
                out.put("error", String.valueOf(e));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /**
     * 后台自动检测：距上次检测超过设定间隔才执行，由保活任务定期调用。
     * 未启用或正在检测时直接返回，不重复触发。
     */
    public static void autoIfDue() {
        try {
            com.cpa.gateway.core.Config cfg = com.cpa.gateway.core.Gateway.config();
            if (cfg == null || !cfg.autoDetect) return;
            if (P.running) return;
            long now = System.currentTimeMillis() / 1000L;
            if (cfg.lastAutoDetect > 0 && now - cfg.lastAutoDetect < cfg.autoDetectInterval) return;
            if (cfg.providers.isEmpty()) return;
            com.cpa.gateway.core.Log.i("自动检测：开始后台巡检模型");
            // 每账号探测数 40 → 2：和用户手点的"一键接入检测"保持一致。
            //
            // 40 是"把每个账号的模型全测一遍"的量级——12 个账号就是 274 次探测，
            // 按 8 并发算要跑 70 秒以上，还会大量触发上游限流（免费池尤其明显）。
            // 更麻烦的是它和手动检测共用同一套状态：后台巡检正在跑时用户手点会被
            // RUNNING 挡掉，界面拿到的仍是后台那轮的中间快照——看起来就是
            // "点了没反应"或"数字对不上"（真机反馈确认过）。
            // 巡检的目的是"发现账号整体不通"，2 个模型足够判断，没必要全量。
            JSONObject r = start(2, 6);
            if (r != null && r.optBoolean("ok")) {
                // 真的启动了才推进时间戳并落盘。原来是先记后退：这次启动若被用户
                // 那一瞬间手点的"一键检测"挡掉（P.running），时间戳照样前进整个
                // 周期，自动巡检就静默跳过一整轮，谁也不知情。
                cfg.lastAutoDetect = now;
                com.cpa.gateway.core.Gateway.persistSoon();
                com.cpa.gateway.core.Log.i("自动检测：已启动");
            } else {
                com.cpa.gateway.core.Log.i("自动检测：这次没启动（可能正在运行），不推进计时");
            }
        } catch (Throwable t) {
            com.cpa.gateway.core.Log.w("自动检测启动失败：" + t);
        }
    }

    /** Starts a sweep; returns the first snapshot. A second call while running is a no-op. */
    public static JSONObject start(int perAccount, int concurrency) {
        JSONObject out = new JSONObject();
        // CAS 防重入：原来"先查 P.running 再置 true"之间能被并发挤进两轮检测（双倍烧额度）
        if (!RUNNING.compareAndSet(false, true)) {
            try {
                out.put("ok", false);
                out.put("error", "already running");
            } catch (Exception ignored) {
            }
            return out;
        }
        final Config cfg = Gateway.config();
        final List<Config.Provider> targets = new ArrayList<>();
        for (Config.Provider p : cfg.providers) {
            if (p.enabled) targets.add(p);
        }
        // perAccount <= 0 表示「全量」：每个账号把它目录里的模型都测一遍。
        //
        // 为什么需要这个档：日常巡检/手点都只抽 2 个（够判断"这个账号通不通"，
        // 也省额度），代价是**没被抽中的模型永远不会被重新判定**——旧的坏
        // 标记只能靠 `p.clearBad()` 被主动清掉的路径来消，而那条路只在
        // "探测到这个模型且它通过"时才走得到。于是坏名单只增不减。
        //
        // 全量档是用来**一次性对账**的：把所有标记清掉、逐个重测，让名单
        // 回到跟上游真实状态一致。上限 400 只是防御性的（正常账号几十个），
        // 真正的节流交给并发数（8）和探测超时（8s）。
        final boolean full = perAccount <= 0;
        final int per = full ? 400 : Math.max(1, Math.min(40, perAccount));
        final int conc = Math.max(1, Math.min(8, concurrency));

        synchronized (P.accounts) {
            P.accounts.clear();
        }
        P.cancel = false;
        P.cancelled = false;
        // **必须在这里把 running 置 true**：原来只置了 phase/total 等，
        // 全工程只有 sweep 的 finally 里出现过 P.running = false，**从没有人设 true**。
        // 于是 status().running 恒为 false，界面轮询第一轮就以为"已经结束"直接 break，
        // 弹窗永远停在初始快照上——真机表现就是"完成 · 用时 0.0s · 通过 0 / 失败 0"，
        // 而实际上检测正在后台正常跑（日志里能看到 16 秒后确实出了结果）。
        P.running = true;
        // 开跑之前先把过期的坏标记放出来。
        //
        // 为什么必须在这一刻做：这一轮的探测结果要跟 badModels 做**差集**
        // 才有意义（"哪些还没被测到"）。如果里面躺着一批几个月前留下的
        // 陈旧标记，它们既不会被测到、也永远不会被清掉，界面上的"坏"
        // 数字就只增不减——这正是 4.29.0 上"313 个模型 51 个坏、但日
        // 常能跑的模型和被标坏的根本不重叠"的来源。
        //
        // 放在这里而不是放到每个 Provider 上：过期是**全局的时间事实**，
        // 跟这一轮抽查的是哪两个模型无关，算一遍就够。
        int released = 0;
        for (Config.Provider p0 : targets) {
            if (full) {
                // 全量重测＝重新对账：先把旧标记全部作废，再由这一轮的结果重建。
                // 不清的话，"这一轮没测到但其实是好的"会继续挂着旧标记，白跑一趟。
                released += p0.badModels.size();
                p0.badModels.clear();
                p0.badSince.clear();
                p0.badReason.clear();
            } else {
                released += p0.expireStaleBad();
            }
        }
        if (released > 0) {
            if (full) {
                Log.i("检测：全量重测，先作废 " + released + " 个旧标记，逐个重建");
            } else {
                Log.i("检测：先放掉 " + released + " 个过期的坏标记（旧标记最多活 "
                        + (Config.Provider.BAD_TTL_SECONDS / 3600) + " 小时）");
            }
            Gateway.persistSoon();
        }

        P.phase = "refresh";
        P.total = targets.size();
        P.per = per;
        P.done.set(0);
        P.passed.set(0);
        P.failed.set(0);
        P.hidden.set(0);
        P.refreshed.set(0);
        P.refreshFailed.set(0);
        P.startedAt = System.currentTimeMillis();
        P.finishedAt = 0L;
        P.current = "";

        for (Config.Provider p : targets) {
            JSONObject row = new JSONObject();
            try {
                row.put("name", p.name);
                row.put("type", p.type);
                row.put("models", p.models.size());
                row.put("refresh", "waiting");
                row.put("refreshError", "");
                row.put("catalogOnly", p.catalogOnly);
                row.put("ok", 0);
                row.put("fail", 0);
                row.put("probes", new JSONArray());
            } catch (Exception ignored) {
            }
            P.accounts.add(row);
        }

        // 检测本体起自己的线程：以前走共享 IO 池，池子忙的时候它连"开始"都要排队，
        // 于是界面上一直停在最开始那句"刷新模型目录 · 0/2"。
        Thread t = new Thread(() -> Detect.sweep(targets, per, conc), "cpa-detect");
        t.setDaemon(true);
        t.start();

        try {
            out.put("ok", true);
            out.put("accounts", targets.size());
            out.put("perAccount", per);
            out.put("concurrency", conc);
        } catch (Exception ignored) {
        }
        Log.i("一键检测启动：账号 " + targets.size() + " 个 ·每账号测 " + per + " 个模型 ·并发 " + conc);
        file("# " + (System.currentTimeMillis() / 1000) + "\t一键检测启动\t账号 " + targets.size()
                + "\t每账号 " + per + " 个模型\t并发 " + conc);
        return out;
    }

    /**
     * 单个探测的超时上限（毫秒）。
     *
     * 为什么必须封顶：probeDirect 走 serve() -> Upstreams.postStream，那条路是给
     * **真实对话**设计的——读超时 300 秒、首 token 看门狗 90 秒（推理模型思考久，
     * 对聊天是合理的）。但探活只需要判断"能不能通"，等 90 秒毫无意义。
     *
     * 真机实测（12 账号 × 2 模型）：整轮 28.5 秒，其中**一个**探测就占了 16.5 秒
     * （Loomy 的 loomy-default），其余都在 0.5~3.8 秒。也就是说慢的不是检测本身，
     * 而是没有上限的个别探测在拖累整轮。
     *
     * 15 秒的依据：实测正常的探测最慢 3.8 秒（含首字节 + 一点生成）。
     * 一开始定的是 8 秒（约两倍余量），但真机上有账号在冷启动 / 建连稍慢时
     * 偶尔擦线，改成 15 秒留足余量；再长就又回到"个别慢探测拖累整轮"的老问题。
     * 超时的探测判为失败并标注"超时"，用户能看出这和"上游报错"不是一回事。
     */
    private static final long PROBE_TIMEOUT_MS = 15000L;

    /**
     * 带超时的探测：超时就放弃等待，把这条判为失败（原因写"探测超时"），
     * **不等**那个后台线程——它自己会随上游响应或读超时结束，不会再拖累整轮。
     */
    /**
     * 探测超时用的共享守护线程池。
     *
     * 原来每次探测都 newSingleThreadExecutor（24 个探测 = 24 个线程池），
     * 建池/销毁本身就有开销，还会留下大量短命线程。
     */
    private static final java.util.concurrent.ExecutorService PROBE_TIMERS =
            java.util.concurrent.Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "cpa-probe-timer");
                t.setDaemon(true);
                return t;
            });

    private static String probeWithTimeout(final Config.Provider p, final String model) {
        java.util.concurrent.Future<String> f;
        try {
            f = PROBE_TIMERS.submit(new java.util.concurrent.Callable<String>() {
                @Override
                public String call() {
                    try {
                        return Server.probeDirect(p, model);
                    } catch (Throwable t) {
                        return Upstreams.shortenPublic(String.valueOf(t));
                    }
                }
            });
        } catch (Throwable t) {
            return Upstreams.shortenPublic(String.valueOf(t));
        }
        try {
            return f.get(PROBE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException te) {
            // **不**调 cancel(true)：中断会沿着调用链把异常信息变成
            // "interrupted" / "thread interrupted"，而那不是上游的问题
            // （真机上曾因此给 3 个账号扣上假故障）。
            // 这里只是"不再等它"，线程会自己随上游响应或 HTTP 读超时结束。
            return "探测超时（超过 " + (PROBE_TIMEOUT_MS / 1000) + " 秒没有响应）";
        } catch (Throwable t) {
            return Upstreams.shortenPublic(String.valueOf(t));
        }
    }

    /* ------------------------------------------------- 探测原文落盘（4.29.7） */

    /**
     * 每轮检测把每个探测的**完整** why 原文追加到 filesDir/detect.log。
     *
     * 为什么必须落盘：弹窗只显示几十个字符，而 core/Log 只是内存里 500 行环形
     * 缓冲、进程一重启就没了——于是"这个分类判得对不对"事后只能靠一张截图倒推。
     * 4.29.7 修 classify() 的误判时就卡在这里：WorkBuddy 那条 HTTP429 后面到底
     * 写了什么，只有截图里的 60 个字符，剩下的永远找不回来了。
     *
     * 上限 512KB，超了就把旧文件改名成 detect.log.old（只留一份），
     * 不做日志轮转系统——这是给排查用的，不是给长期归档用的。
     */
    static final String DETECT_LOG = "detect.log";
    private static final Object FILE_LOCK = new Object();
    private static final long FILE_MAX = 512L * 1024L;

    /** 追加一行；任何异常都吞掉——记日志绝不能影响检测本身。 */
    static void file(String line) {
        try {
            android.content.Context ctx = Gateway.context();
            if (ctx == null) return;
            synchronized (FILE_LOCK) {
                java.io.File f = new java.io.File(ctx.getFilesDir(), DETECT_LOG);
                if (f.length() > FILE_MAX) {
                    java.io.File old = new java.io.File(ctx.getFilesDir(), DETECT_LOG + ".old");
                    if (old.exists()) old.delete();
                    f.renameTo(old);
                }
                java.io.FileOutputStream out = new java.io.FileOutputStream(f, true);
                try {
                    out.write((line + "\n").getBytes("UTF-8"));
                } finally {
                    out.close();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 一探一行：换行 / 制表符压平，并且给长度封顶（原文再长也不允许把文件撑爆）。 */
    private static String flat(String s, int max) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }

    private static void sweep(List<Config.Provider> targets, int per, int conc) {
        // 探测跑在专用池里（8 线程），不再借 SWEEP、也不经过本机 HTTP
        ExecutorService pool = Threads.PROBE;
        try {
            /* ------------------------- phase 1: catalogues（后台跑，不挡探测） */
            // 目录刷新是一个上游请求，慢的时候十几秒；以前它完不成就不开始测模型，
            // 用户看到的就是"刷新模型目录 0/2 ·已用 14s"。现在它自己跑，探测立刻开始。
            Log.i("检测：阶段1开始 ·" + targets.size() + " 个账号刷目录 · IO 活跃 "
                    + Threads.ioActive() + " · SWEEP 活跃 " + Threads.sweepActive());
            List<Future<?>> catalogues = new ArrayList<>();
            // 目录刷新走 IO 池：它只是"顺手更新一下模型清单"，不该占着探测用的 SWEEP 线程
            java.util.concurrent.ExecutorService cataloguePool = Threads.CATALOG;
            for (int i = 0; i < targets.size(); i++) {
                final Config.Provider p = targets.get(i);
                final JSONObject row = P.accounts.get(i);
                catalogues.add(cataloguePool.submit(new Runnable() {
                    @Override
                    public void run() {
                        if (P.cancel) return;
                        /* 仅目录账号：目录已经存过，没必要每轮再拉一遍（省一次上游往返）。 */
                        if (p.catalogOnly) {
                            try {
                                synchronized (row) {
                                    row.put("refresh", "ok");
                                    row.put("models", p.models.size());
                                }
                            } catch (Exception ignored) {
                            }
                            P.refreshed.incrementAndGet();
                            return;
                        }
                        P.current = p.name + " \u00b7 \u6a21\u578b\u76ee\u5f55";
                        long c0 = System.currentTimeMillis();
                        Log.i("检测：开始刷目录 " + p.name);
                        try {
                            List<String> models = Upstreams.fetchModels(p);
                            Log.i("检测：刷目录完成 " + p.name + " ·" + models.size() + " 个模型 ·"
                                    + (System.currentTimeMillis() - c0) + "ms");
                            if (!models.isEmpty()) p.setModels(models);
                            synchronized (row) {
                                row.put("refresh", "ok");
                                row.put("models", p.models.size());
                            }
                            P.refreshed.incrementAndGet();
                        } catch (Exception e) {
                            synchronized (row) {
                                try {
                                    row.put("refresh", "fail");
                                    row.put("refreshError", Upstreams.shortenPublic(String.valueOf(e.getMessage())));
                                } catch (Exception ignored) {
                                }
                            }
                            P.refreshFailed.incrementAndGet();
                            file((System.currentTimeMillis() / 1000) + "\t" + flat(p.name, 40)
                                    + "\t(目录刷新)\tfail\t" + (System.currentTimeMillis() - c0) + "ms\t"
                                    + flat("目录刷新失败：" + e, 900));
                        }
                    }
                }));
            }

            /* ---------------------------------- phase 2: probes（不等目录刷新） */
            P.phase = "probe";
            Log.i("检测：阶段2开始（探测）· 池活跃 IO " + Threads.ioActive()
                    + " · SWEEP " + Threads.sweepActive());
            List<Object[]> jobs = new ArrayList<>();
            for (int i = 0; i < targets.size(); i++) {
                Config.Provider p = targets.get(i);
                JSONObject row = P.accounts.get(i);
                /* 用目录快照：phase 1 可能正在替换 p.models，探测这一轮沿用当前目录就够。
                   但**快照为空时必须等它一下**：4.29.7 真机上刚重启的账号目录还没落地，
                   抽到 0 个样本，界面显示"✅ 0/0 可用"——看着像通过，其实一个都没测。 */
                List<String> snapshot = new ArrayList<>(p.models);
                if (snapshot.isEmpty() && i < catalogues.size()) {
                    try {
                        catalogues.get(i).get(12, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (Throwable ignored) {
                    }
                    snapshot = new ArrayList<>(p.models);
                }
                List<String> picked = p.catalogOnly ? new ArrayList<String>() : pickModels(snapshot, per);
                file("# 快照\t" + flat(p.name, 40) + "\t目录 " + snapshot.size()
                        + " 个\t样本 " + picked.size() + " 个");
                for (String model : picked) {
                    jobs.add(new Object[]{p, row, model});
                }
            }
            P.total = targets.size() + jobs.size();
            P.done.set(targets.size());

            List<Future<?>> futures = new ArrayList<>();
            // 提交前用信号量限流：PROBE 池是 SynchronousQueue（不排队），
            // 直接 submit 超过并发数会被**拒绝**抛异常。这里先抢到许可再提交，
            // 保证池里永远有空位，既不拒绝、也不需要 CallerRunsPolicy。
            //
            // 为什么不能用 CallerRunsPolicy：被拒任务会由**提交线程**同步执行，
            // 而提交线程就是这个 for 循环——一旦池满，循环会被某个探测阻塞住，
            // 后续任务全部排队等它。真机上表现为检测卡住、同一批账号两次结果不一致。
            final java.util.concurrent.Semaphore probeSlots =
                    new java.util.concurrent.Semaphore(Math.max(1, conc));
            for (Object[] job : jobs) {
                final Config.Provider p = (Config.Provider) job[0];
                final JSONObject row = (JSONObject) job[1];
                final String model = (String) job[2];
                try {
                    probeSlots.acquire();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                Runnable task = new Runnable() {
                    @Override
                    public void run() {
                      try {                       // 保证任何出口都释放信号量许可
                        if (P.cancel) {
                            synchronized (row) {
                                try {
                                    row.put("skipped", row.optInt("skipped", 0) + 1);
                                } catch (Exception ignored) {
                                }
                            }
                            P.done.incrementAndGet();
                            return;
                        }
                        P.current = p.name + " \u00b7 " + model;
                        long t0 = System.currentTimeMillis();
                        Log.i("检测：探测开始 " + p.name + " \u00b7 " + model);
                        String why = probeWithTimeout(p, model);
                        long ms = System.currentTimeMillis() - t0;
                        boolean bad = why != null && !why.isEmpty();
                        Log.i("检测：探测完成 " + p.name + " ·" + model + " ·"
                                + (bad ? ("失败 " + Upstreams.shortenPublic(why)) : "通过") + " ·" + ms + "ms");
                        file((System.currentTimeMillis() / 1000) + "\t" + flat(p.name, 40) + "\t"
                                + flat(model, 60) + "\t" + (bad ? "fail" : "ok") + "\t" + ms + "ms\t"
                                + (bad ? (classify(why) + " | " + flat(why, 900)) : ""));
                        try {
                            JSONObject probe = new JSONObject();
                            probe.put("model", model);
                            probe.put("ok", !bad);
                            probe.put("ms", ms);
                            if (bad) {
                                probe.put("why", why);
                                probe.put("kind", classify(why));
                            }
                            synchronized (row) {
                                row.getJSONArray("probes").put(probe);
                                row.put("ok", row.optInt("ok", 0) + (bad ? 0 : 1));
                                row.put("fail", row.optInt("fail", 0) + (bad ? 1 : 0));
                            }
                        } catch (Exception ignored) {
                        }
                        synchronized (p.badModels) {
                            if (!bad) {
                                // 探测通过 → 撤掉标记（顺带把原因和时间一起清掉，
                                // 不留半截数据）
                                if (p.clearBad(model)) {
                                    Log.i("检测：模型恢复，撤掉坏标记 " + p.name + " ·" + model);
                                }
                            } else if (isTransient(why)) {
                                // 暂时失败（限流 / 超时 / 连不上）：**不拉黑**，
                                // 只是这一轮不算通过。拉黑会让这个模型从界面上消失，
                                // 而它下一分钟可能就恢复正常了——这正是
                                // "账号一会好一会坏、模型说用不了"的来源。
                                //
                                // 但它**也不能什么痕迹都不留**：如果它本来就在坏
                                // 名单里，这一次的不确定应该让它的时限**往后顺延**
                                // （说明"至少现在还说不准"），而不是让它按原时限
                                // 到期被放出来、被当成好的用。
                                if (p.badModels.contains(model)) {
                                    p.markBad(model, classify(why) + "（暂时失败，尚未确认恢复）");
                                }
                                Log.i("检测：暂时失败，不隐藏模型 " + p.name + " ·" + model
                                        + " · " + classify(why));
                            } else {
                                // 真的坏：记名字 + 时间 + 人话原因。
                                // 原来这里只 add 一个名字，不记原因也不记时间，
                                // 于是界面上只能显示"坏"，说不清为什么、也说不清
                                // 是什么时候判的——用户没法判断是该等它还该绕开。
                                boolean fresh = p.markBad(model, classify(why));
                                if (fresh) {
                                    P.hidden.incrementAndGet();
                                    Log.i("检测：判定不可用 " + p.name + " ·" + model
                                            + " · " + classify(why));
                                }
                            }
                        }
                        if (bad) P.failed.incrementAndGet();
                        else P.passed.incrementAndGet();
                        P.done.incrementAndGet();
                      } finally {
                        probeSlots.release();
                      }
                    }
                };
                Future<?> f = null;
                for (int attempt = 0; attempt < 40 && f == null; attempt++) {
                    try {
                        f = pool.submit(task);
                    } catch (java.util.concurrent.RejectedExecutionException ree) {
                        /* PROBE 池是 SynchronousQueue：8 个线程刚好都在收尾时会有一次拒绝。
                           原来这一次拒绝会把整个提交循环打断（异常一路冒到 sweep 外层），
                           后面的 job 再也不提交——真机上表现为"每账号抽 2 个"变成只测前几个账号，
                           而且每轮测到的账号还不一样。这里退一步重试，宁可慢一点，绝不丢 job。 */
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException ie2) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
                if (f == null) {
                    file("# 提交失败\t" + flat(p.name, 40) + "\t" + flat(model, 60));
                    P.done.incrementAndGet();
                    probeSlots.release();
                    continue;
                }
                futures.add(f);
            }
            file("# 提交完成\tjob " + jobs.size() + " 个\t已提交 " + futures.size() + " 个");
            // 收尾：等探测任务结束。
            //
            // 关键改动：**不再 cancel(true)**。
            // 每个探测自己已经有 PROBE_TIMEOUT_MS 上限（probeWithTimeout 负责），
            // 所以这里的等待只需要"给它们跑完的时间"，不需要再设一道 deadline
            // 去取消——原来那道 30 秒 deadline 会在慢探测上触发 f.cancel(true)，
            // 中断线程后异常信息变成 "interrupted"，又回到了假故障的老问题；
            // 而且被 cancel 的探测不计数，出现 done(26) < total(36) 这种半截结果。
            // 现在：每个探测最多 15 秒，这里按"总时长 / 并发"给足余量即可。
            long waitMs = 15_000L + (long) Math.ceil(jobs.size() / (double) Math.max(1, conc)) * 16_000L;
            long deadline = System.currentTimeMillis() + waitMs;
            for (Future<?> f : futures) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) break;          // 到点就不再等，让它们自己在后台收尾
                try {
                    f.get(left, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (Exception ignored) {
                }
            }
            // 目录刷新**不等**：它只是顺手更新模型清单，跟"哪些模型能用"无关。
            // 实测（4.16.6）：探测 12 秒就全跑完了，却因为等目录刷新把整轮拖到 70 秒开外。
            // 它在后台跑完自己会 setModels，下一轮检测生效。
        } catch (Throwable t) {
            file("# 异常\t" + flat(String.valueOf(t), 500));
            Log.e("一键检测异常：" + t);
        } finally {
            // SWEEP 是常驻共享池，不 shutdown
            Gateway.persistSoon();
            P.cancelled = P.cancel;
            P.phase = P.cancel ? "cancelled" : "done";
            P.running = false;
            RUNNING.set(false);
            P.current = "";
            P.finishedAt = System.currentTimeMillis();
            file("# " + (P.finishedAt / 1000) + "\t一键检测结束\t" + (P.cancel ? "已停止" : "完成")
                    + "\t通过 " + P.passed.get() + "\t失败 " + P.failed.get()
                    + "\t新隐藏 " + P.hidden.get() + "\t用时 " + (P.finishedAt - P.startedAt) + "ms");
            Log.i("一键检测结束：" + (P.cancel ? "已停止" : "完成")
                    + " ·实测 " + P.passed.get() + " 通过 / " + P.failed.get() + " 失败"
                    + " ·新隐藏 " + P.hidden.get() + " 个 ·用时 " + (P.finishedAt - P.startedAt) + "ms");
        }
    }

    /**
     * 把上游返回的长串错误归类成一句人话。
     *
     * 用户点"一键接入并检测"要看的是"渠道通不通、账号有没有效"，
     * 原来的失败原因是一整段原始 JSON，得自己从里面刨。这里按最常见的几种归好类。
     *
     * 4.29.7 修的是"整段 contains"惹的祸：
     *   · `w.contains("401")` 会命中业务码 **14018** 里的 "401"，于是
     *     WorkBuddy 一条 HTTP429 + code14018 被显示成"鉴权失效（账号要重新登录）"——
     *     把用户往重新登录上带，而真实状态码是 429。
     *   · 同理 "403" / "404" / "400" / "502" / "503" / "504" 都可能命中别的数字。
     * 现在：状态码只按带边界的 HTTP 写法取（statusOf），鉴权类措辞只在
     * **上游自己那句 msg / message** 上匹配（messageOf），业务码单独按 code 精确比对。
     */
    /**
     * 这个失败是**暂时的**吗？暂时的失败不该把模型拉黑。
     *
     * 为什么需要：原来任何失败都会进 p.badModels（并"隐藏"该模型），包括
     * 429 限流和超时。于是免费池账号（OVHcloud / Kilo 这类）会出现
     * "一会儿能用、一会儿用不了、再一会儿又好了"的反复横跳——
     * 因为上游限流本来就是波动的，而我们把每次波动都当成了永久缺陷。
     *
     * 真机实测：同一批 12 个账号连跑三轮，失败数是 2 / 0 / 1，
     * 变动的全是免费池的 429（"API rate limit exceeded"）。
     * 账号本身没问题，是上游当时在限流。
     *
     * 分类规则要和 classify() 的措辞保持一致（同一个来源，改一处要改两处）。
     */
    static boolean isTransient(String why) {
        if (why == null || why.isEmpty()) return false;
        String w = why.toLowerCase(java.util.Locale.US);
        String msg = messageOf(w);
        int status = statusOf(w);
        /* 额度用完了不会自己好 —— 必须先于状态码判断，否则 429 会被当成"限流，稍后恢复"。 */
        if (CREDITS_OUT.matcher(msg).find()) return false;
        if (w.contains("paid_model") || w.contains("paid model")) return false;
        /* 状态码只看真的那一个：HTTP429 / "status":429 这种带边界的写法。
           原来直接 w.contains("429")，业务码里出现 "429" 也会被当成限流
           （"502" / "503" / "504" 同理，见 classify() 的注释）。 */
        if (status == 408 || status == 425 || status == 429 || status >= 500) return true;
        /* 上游当时忙 / 网络抖动 / 我们等不及 —— 都会自己好 */
        if (msg.contains("rate limit") || msg.contains("too many")
                || msg.contains("timeout") || msg.contains("timed out")
                || w.contains("探测超时")
                || msg.contains("overload") || msg.contains("temporarily")
                || msg.contains("connection") || msg.contains("econnrefused")
                || msg.contains("unreachable")) {
            return true;
        }
        /* 探测超时是用"不再等它"实现的，中断会沿着调用链把异常信息变成
           "interrupted" / "thread interrupted"。这**不是上游的问题**，是我们自己
           主动打断的——必须算临时失败，否则每次探测超时都会给账号扣一个假故障。
           （真机上就是这么来的：3 个账号显示"不可用"，错误全是 thread interrupted） */
        if (w.contains("interrupt")) return true;
        /* 风控 / 静默拒答是一段时间的状态，会自己解除 */
        if (w.contains("没有返回任何内容") || w.contains("风控")) return true;
        /* 本地原因：并发已满、本地限额、网关没跑、熔断回避…… 这些跟上游、跟账号
           都没关系，同样不该给模型盖"坏"章（4.29.7 补）。 */
        return w.contains("并发已满") || w.contains("本地限额") || w.contains("网关未运行")
                || w.contains("熔断") || w.contains("暂避");
    }

    /** 从错误文本里取**真实**的 HTTP 状态码；取不到返回 -1。 */
    private static final java.util.regex.Pattern HTTP_STATUS = java.util.regex.Pattern.compile(
            "(?:\\bhttp\\s*/?\\s*|[\"']?status[\"']?\\s*[:=]\\s*[\"']?)(\\d{3})\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /** 上游的业务码（"code":14018 / 'code':11103）；取不到返回 Integer.MIN_VALUE。 */
    private static final java.util.regex.Pattern BIZ_CODE = java.util.regex.Pattern.compile(
            "[\"']?code[\"']?\\s*[:=]\\s*[\"']?(-?\\d+)");

    /**
     * "额度 /积分用完了"的说法。
     *
     * 4.29.8 真机拿到 WB3 的原文后才定下来：`code 14018` 配的是
     * "Credits exhausted. Please visit the link below to purchase add-on packs" ——
     * 它挂着 HTTP 429，但**不是限流**，等一会儿不会好，得充值/换号。
     * 只按状态码判会显示成"被限流（稍后自动恢复）"，把人晾在那里白等。
     */
    private static final java.util.regex.Pattern CREDITS_OUT = java.util.regex.Pattern.compile(
            "credits?[ _-]?(?:are |is |has )?(?:exhausted|insufficient|empty|used up|out)"
                    + "|no credits?|out of credits?"
                    + "|insufficient (?:credits?|balance|quota|funds)"
                    + "|quota (?:exceeded|used up)|exceeded your quota"
                    + "|额度|积分|余额不足|余额耗尽|积分用完");

    /** 上游自己那段话（msg / message / error_msg）。 */
    private static final java.util.regex.Pattern MESSAGE = java.util.regex.Pattern.compile(
            "[\"']?(?:msg|message|error_msg|errorMessage)[\"']?\\s*[:=]\\s*[\"']([^\"']*)");

    /**
     * 鉴权类措辞：只在"上游自己那句话"上匹配，绝不在整段 JSON 上匹配。
     *
     * 措辞刻意收紧（只有 credential / token / session 明确说失效才算），
     * 就是为了不把 "Credits exhausted"（额度）当成 "Credential expired"（凭据）。
     */
    private static final java.util.regex.Pattern AUTH_WORDS = java.util.regex.Pattern.compile(
            "unauthor"
                    + "|invalid[_ ]?(?:grant|token|credential|session|api[ _]?key)"
                    + "|credential[s]?[ _]?(?:has |is )?(?:expired|invalid|missing)"
                    + "|token[ _]?(?:has )?(?:expired|invalid)"
                    + "|session[ _]?(?:has )?(?:expired|invalid)"
                    + "|sign[ _-]?in|please log ?in|not logged in"
                    + "|重新登录|未登录|鉴权|授权失败|登录已过期|登录状态已失效");

    static int statusOf(String lower) {
        try {
            java.util.regex.Matcher m = HTTP_STATUS.matcher(lower);
            if (m.find()) return Integer.parseInt(m.group(1));
        } catch (Throwable ignored) {
        }
        return -1;
    }

    static int bizCodeOf(String lower) {
        try {
            java.util.regex.Matcher m = BIZ_CODE.matcher(lower);
            if (m.find()) return Integer.parseInt(m.group(1));
        } catch (Throwable ignored) {
        }
        return Integer.MIN_VALUE;
    }

    /** 上游那句话；取到的还是"套了一层"的原始报文就退回整段。 */
    static String messageOf(String lower) {
        try {
            java.util.regex.Matcher m = MESSAGE.matcher(lower);
            if (m.find()) {
                String v = m.group(1);
                if (!v.contains("{") && !v.contains("upstream http")) return v;
            }
        } catch (Throwable ignored) {
        }
        return lower;
    }

    static String classify(String why) {
        if (why == null) return "";
        String w = why.toLowerCase(java.util.Locale.US);
        String msg = messageOf(w);
        int status = statusOf(w);
        int code = bizCodeOf(w);
        if (w.contains("探测超时") || msg.contains("timeout") || msg.contains("timed out")
                || status == 408 || status == 504) {
            return "超时（上游没在限定时间内回）";
        }
        /* 额度耗尽排在鉴权/限流前面：它是"账上没钱了"，不是"你没登录"、也不是"上游忙"。 */
        if (CREDITS_OUT.matcher(msg).find()) return "额度 / 余额不足（充值或换号，等不会自己好）";
        /* 免费池里点到付费模型：上游回 PAID_MODEL_AUTH_REQUIRED + "You need to sign in to use this model."
           —— 这个账号没有可登录的凭据，说"账号要重新登录"是错的，会让人白折腾。 */
        if (w.contains("paid_model") || w.contains("paid model")) return "该模型需要登录 / 付费账号";
        if (status == 401 || status == 403 || AUTH_WORDS.matcher(msg).find()) {
            return "鉴权失效（账号要重新登录）";
        }
        if (status == 402 || msg.contains("insufficient") || msg.contains("quota")
                || msg.contains("balance") || msg.contains("no credit")) {
            return "额度 / 余额不足";
        }
        if (status == 429 || msg.contains("rate limit") || msg.contains("too many")
                || msg.contains("频繁")) {
            return "被限流（稍后自动恢复）";
        }
        /* 渠道校验：4.29.10 把 5 种"body 会话字段"和 5 种"UA 身份"形状都试过，
           全部被拒，而且同一请求换形状后上游回的 requestId 一模一样——说明这道校验
           不看我们发的东西，是账号/链路一侧被标记了。如实说出来，省得下次再从字段名试起。 */
        if ((code == 11128 || w.contains("conversation id missing"))
                && (w.contains("channel verification") || w.contains("official client")
                    || w.contains("request illegal") || w.contains("conversation id missing"))) {
            return "渠道校验拒绝（上游要求官方客户端，改请求形状无效）";
        }
        if (code == 11103 || msg.contains("not supported") || msg.contains("unsupported")) {
            return "该模型上游不支持";
        }
        if (status == 404 || code == 404 || msg.contains("model not found")
                || msg.contains("no such model") || msg.contains("service info not found")) {
            return "模型不存在（目录里的名字上游不认）";
        }
        if (status == 400 || status == 422 || code == 11128) {
            return "上游拒绝这个请求（参数 / 能力不匹配）";
        }
        if (msg.contains("connection") || msg.contains("econnrefused") || msg.contains("unreachable")) {
            return "连不上上游（网络 / 域名）";
        }
        /* 豆包的"没有返回任何内容"是它自己的风控 / 静默拒答（不是超时、也不是鉴权失效）。
           归到"被风控"而不是笼统的"失败"，用户才知道这事等一会儿可能就好了。 */
        if (w.contains("没有返回任何内容") || w.contains("风控")) {
            return "上游风控（可能稍后恢复）";
        }
        return "失败";
    }

    private static void waitAll(List<Future<?>> futures) {
        for (Future<?> f : futures) {
            try {
                f.get(180, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 一个账号抽几个模型当样本。
     *
     * 4.29.7：抽样只在**聊天模型**里做。原来直接从原始目录里等距抽，会抽到
     * 生图 / 生视频 / 补全类（"Backend [maas] is not supported" 这类必然失败的名字），
     * 白白占掉一个样本名额，还会把一个好账号显示成 1/2。
     * 判据和对外列表过滤（Server.allModels）共用 Router.nonChat，免得出现
     * "列表里不推荐、探测又拿它当代表"这种自相矛盾。
     */
    static List<String> pickModels(List<String> models, int per) {
        List<String> clean = new ArrayList<>();
        for (String m : models) {
            if (m == null || m.isEmpty() || "*".equals(m) || clean.contains(m)) continue;
            clean.add(m);
        }
        List<String> pool = new ArrayList<>();
        for (String m : clean) {
            if (!Router.nonChat(m)) pool.add(m);
        }
        /* 兜底：万一整个目录都是非聊天模型（免费池的小目录出现过），不能因为没样本
           就把账号显示成"没测"——退回原列表，宁可测出"不支持"也不要漏判账号。 */
        if (pool.isEmpty()) pool = clean;
        if (pool.size() <= per) return new ArrayList<>(pool);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < per; i++) {
            int idx = (int) Math.round((double) i * (pool.size() - 1) / (per - 1));
            String m = pool.get(idx);
            if (!out.contains(m)) out.add(m);
        }
        return out;
    }
}
