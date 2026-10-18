package com.cpa.gateway.core;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地限额预检 + 并发槽。
 *
 * 对应 cctapi 的 PassQuotaCheck / TryAcquireDeploymentSlot / RecordUsage 三件事，
 * 但落在手机上：窗口计数放内存，当日用量写进配置（进程被系统回收后还在）。
 *
 * 三条与上游一致、也最容易踩错的语义：
 *
 *   1. **0 ＝ 不限制**。不是"零配额"。想真正限制必须配正数。
 *   2. **先预检、后记账**。预检只读不写（要发出去的请求才有资格占额度），
 *      成功之后才 RecordUsage；否则失败的请求也会把额度吃掉。
 *   3. **预检不通过不等于失败**。调用方按"限流"处理（{@link Throttled}），
 *      不记失败、不进熔断——否则一个配了限额的账号会被自己的限额打进冷却。
 */
public final class Limits {

    /** 每个通道一份运行时窗口。 */
    private static final class Win {
        long minuteStart;    // 本分钟起点（毫秒）
        int minuteRequests;
        long minuteTokens;
        int inFlight;        // 正在跑的上游请求数
    }

    private static final ConcurrentHashMap<String, Win> WINS = new ConcurrentHashMap<>();

    /** 不限额、不计数时的空槽。 */
    private static final Slot EMPTY = new Slot(null, null);

    private Limits() {
    }

    private static String key(Config.Provider p) {
        if (p.id != null && !p.id.isEmpty()) return p.id;
        return p.name == null ? "" : p.name;
    }

    private static Win win(Config.Provider p) {
        String k = key(p);
        Win w = WINS.get(k);
        if (w != null) return w;
        Win created = new Win();
        Win prev = WINS.putIfAbsent(k, created);
        return prev != null ? prev : created;
    }

    /** 今天的日期数字，形如 20261012。 */
    public static int today() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        return c.get(java.util.Calendar.YEAR) * 10000
                + (c.get(java.util.Calendar.MONTH) + 1) * 100
                + c.get(java.util.Calendar.DAY_OF_MONTH);
    }

    /**
     * 推窗口：跨分钟清分钟计数，跨天清当日计数。
     * 必须持有 w 的监视器时调用。返回 true 表示跨天了（调用方负责落盘）。
     */
    private static boolean roll(Config.Provider p, Win w) {
        long now = System.currentTimeMillis();
        long minuteStart = now - (now % 60_000L);
        if (w.minuteStart != minuteStart) {
            w.minuteStart = minuteStart;
            w.minuteRequests = 0;
            w.minuteTokens = 0;
        }
        int today = today();
        if (p.quotaDay != today) {
            p.quotaDay = today;
            p.dayRequests = 0;
            p.dayTokens = 0L;
            return true;
        }
        return false;
    }

    /**
     * 发请求前的四维预检。返回 null 表示放行，否则是"这次该跳过这个通道"的原因。
     * 预检不改任何计数——真正的记账在 {@link #record}。
     */
    public static String explainBlocked(Config.Provider p, int estTokens) {
        if (p == null) return null;
        boolean anyLimit = p.rpm > 0 || p.rpd > 0 || p.tpm > 0 || p.tpd > 0;
        if (!anyLimit) {
            // 没配限额也要推窗口，免得跨天后第一次记账用的是昨天的数
            Win w = win(p);
            synchronized (w) {
                if (roll(p, w)) Gateway.persistSoon();
            }
            return null;
        }
        Win w = win(p);
        synchronized (w) {
            if (roll(p, w)) Gateway.persistSoon();
            if (p.rpm > 0 && w.minuteRequests + 1 > p.rpm) {
                return "本分钟请求已达上限 " + w.minuteRequests + "/" + p.rpm;
            }
            if (p.rpd > 0 && p.dayRequests + 1 > p.rpd) {
                return "今日请求已达上限 " + p.dayRequests + "/" + p.rpd;
            }
            if (p.tpm > 0 && w.minuteTokens + estTokens > p.tpm) {
                return "本分钟 token 已达上限（" + w.minuteTokens + "+" + estTokens + "/" + p.tpm + "）";
            }
            if (p.tpd > 0 && p.dayTokens + estTokens > p.tpd) {
                return "今日 token 已达上限（" + p.dayTokens + "+" + estTokens + "/" + p.tpd + "）";
            }
            return null;
        }
    }

    /** 并发槽。release 幂等：重复调用只生效一次。 */
    public static final class Slot {
        private final Config.Provider provider;
        private final Win win;
        private boolean released;

        private Slot(Config.Provider provider, Win win) {
            this.provider = provider;
            this.win = win;
        }

        public void release() {
            if (released) return;
            released = true;
            if (win == null) return;
            synchronized (win) {
                if (win.inFlight > 0) win.inFlight--;
            }
        }
    }

    /**
     * 占一个并发槽。返回 null 表示这个通道并发已满，本轮跳过它。
     * maxConcurrent 为 0 时不计数，永远拿得到槽。
     */
    public static Slot acquire(Config.Provider p) {
        if (p == null || p.maxConcurrent <= 0) return EMPTY;
        Win w = win(p);
        synchronized (w) {
            if (roll(p, w)) Gateway.persistSoon();
            if (w.inFlight >= p.maxConcurrent) return null;
            w.inFlight++;
            return new Slot(p, w);
        }
    }

    /** 记一次成功用量。tokens 为 0 时只计请求数。 */
    public static void record(Config.Provider p, long tokens) {
        if (p == null) return;
        Win w = win(p);
        long use = tokens < 0 ? 0 : tokens;
        synchronized (w) {
            roll(p, w);
            w.minuteRequests++;
            w.minuteTokens += use;
            p.dayRequests++;
            p.dayTokens += use;
        }
        Gateway.persistSoon();
    }

    /** 诊断用一行：某通道此刻的窗口占用。 */
    public static String describe(Config.Provider p) {
        if (p == null) return "";
        Win w = win(p);
        String r;
        synchronized (w) {
            roll(p, w);
            StringBuilder sb = new StringBuilder(p.name);
            sb.append(" 本分钟 ").append(w.minuteRequests);
            if (p.rpm > 0) sb.append("/").append(p.rpm);
            sb.append(" 次 · 今日 ").append(p.dayRequests);
            if (p.rpd > 0) sb.append("/").append(p.rpd);
            sb.append(" 次 · 并发 ").append(w.inFlight);
            if (p.maxConcurrent > 0) sb.append("/").append(p.maxConcurrent);
            r = sb.toString();
        }
        return r;
    }

    /** 诊断用一行：本分钟/今日 token 占用。 */
    public static String describeTokens(Config.Provider p) {
        if (p == null) return "";
        Win w = win(p);
        synchronized (w) {
            roll(p, w);
            return p.name + " token：本分钟 " + w.minuteTokens
                    + (p.tpm > 0 ? "/" + p.tpm : "")
                    + " · 今日 " + p.dayTokens
                    + (p.tpd > 0 ? "/" + p.tpd : "");
        }
    }
}
