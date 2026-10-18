package com.cpa.gateway.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Daily health check for API-key channels (relay stations and third-party platforms).
 *
 * A station that cannot list its models for several days in a row is almost certainly gone; keeping
 * it around only makes the account list and the model list look alive when they are not. After
 * {@link #RETIRE_AFTER_DAYS} consecutive failures the channel is switched off (never deleted, so the
 * key is still there if the station comes back) and the reason is written to lastError.
 */
public final class RelayWatch {

    private RelayWatch() {}

    public static final int RETIRE_AFTER_DAYS = 3;

    public static final class Result {
        public String provider = "";
        public String state = "";
        public int streak = 0;
    }

    /** Probes every API-key channel once. Returns a short report for the caller to show. */
    public static List<Result> runOnce() {
        List<Result> providers = new ArrayList<>();
        List<Config.Provider> all = new ArrayList<>(Gateway.config().providers);
        // 并行探活：原来串行，离线站多的时候一轮能跑十几分钟，把保活心跳一起拖住。
        // 并发 4（都是中转站/第三方平台，同时对太多家不礼貌）。
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "cpa-relaywatch");
            t.setDaemon(true);
            return t;
        });
        java.util.List<Result> report = java.util.Collections.synchronizedList(new ArrayList<>());
        java.util.List<java.util.concurrent.Future<?>> jobs = new ArrayList<>();
        for (Config.Provider p : all) {
            if (!isApiChannel(p)) continue;
            jobs.add(pool.submit(() -> report.add(probeOne(p))));
        }
        for (java.util.concurrent.Future<?> f : jobs) {
            try {
                f.get(90, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Throwable ignored) {
            }
        }
        pool.shutdownNow();
        Gateway.persistSoon();
        return report;
    }

    /** 单个渠道探活（并行执行体）。 */
    private static Result probeOne(Config.Provider p) {
        Result r = new Result();
        r.provider = p.name;
        boolean alive;
        String reason = "";
        try {
            List<String> models = Upstreams.fetchModels(p);
            alive = models != null;
            if (!alive) reason = "没有返回模型列表";
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage());
            boolean unreachable = msg.contains("Connection refused")
                    || msg.contains("Failed to connect")
                    || msg.contains("UnknownHost")
                    || msg.contains("No address associated")
                    || msg.contains("timed out")
                    || msg.contains("timeout")
                    || msg.contains("EHOSTUNREACH")
                    || msg.contains("SSLHandshake")
                    || msg.contains("no models");
            if (!unreachable) {
                alive = true;
                Log.i("中转站可连通但返回格式不同，按存活处理：" + p.name + " (" + msg + ")");
            } else {
                alive = false;
                reason = Upstreams.shortenPublic(msg);
            }
        }
        if (alive) {
            if (p.failStreak != 0) {
                p.failStreak = 0;
                Log.i("中转站探活恢复：" + p.name);
            }
            boolean reEnable = !p.enabled && p.lastError != null && p.lastError.startsWith("自动停用");
            if (reEnable) {
                p.enabled = true;
                p.lastError = "";
                p.lastErrorAt = 0;
                Log.i("中转站已恢复，自动重新启用：" + p.name);
            }
            r.state = reEnable ? "恢复并重新启用" : "正常";
        } else {
            p.failStreak = p.failStreak + 1;
            p.lastError = "探活失败(" + p.failStreak + "/" + RETIRE_AFTER_DAYS + ")：" + reason;
            p.lastErrorAt = System.currentTimeMillis() / 1000;
            if (p.failStreak >= RETIRE_AFTER_DAYS) {
                p.enabled = false;
                p.lastError = "自动停用：连续 " + p.failStreak + " 天探活失败（" + reason + "）";
                r.state = "已自动停用";
                Log.w("中转站连续失败，自动停用：" + p.name);
            } else {
                r.state = "失败 " + p.failStreak + "/" + RETIRE_AFTER_DAYS;
            }
        }
        r.streak = p.failStreak;
        return r;
    }

    /** Only API-key style channels have a base URL we can probe; account logins have their own flows. */
    static boolean isApiChannel(Config.Provider p) {
        if (p == null || p.baseUrl == null || p.baseUrl.isEmpty()) return false;
        if (Config.TYPE_WORKBUDDY.equals(p.type) || Config.TYPE_TRAE.equals(p.type)
                || Config.TYPE_LOOMY.equals(p.type) || Config.TYPE_CODEX.equals(p.type)
                || Config.TYPE_CLAUDE.equals(p.type) || Config.TYPE_ANTIGRAVITY.equals(p.type)
                || Config.TYPE_KIMI.equals(p.type) || Config.TYPE_DEEPSEEK.equals(p.type)
                || Config.TYPE_DOUBAO.equals(p.type) || Config.TYPE_XAI.equals(p.type)
                || Config.TYPE_CLINE.equals(p.type) || Config.TYPE_KILO.equals(p.type)
                || Config.TYPE_OVH.equals(p.type)) return false;
        return true;
    }

    /** Runs at most once per day; called from the keep-alive job. */
    public static void runDailyIfDue() {
        Config cfg = Gateway.config();
        long now = System.currentTimeMillis() / 1000;
        if (now - cfg.lastRelayCheck < 24 * 3600) return;
        cfg.lastRelayCheck = now;
        Log.i("开始每日中转站探活");
        List<Result> results = runOnce();
        int retired = 0;
        for (Result r : results) if ("已自动停用".equals(r.state)) retired++;
        Log.i("每日探活完成：检查 " + results.size() + " 条，自动停用 " + retired + " 条");
    }
}
