package com.cpa.gateway.core;

/**
 * 竞速输家的"真取消"。
 *
 * 原来输家只在"有数据到达并写到 DeferredWriter"时才抛 RaceLost；如果输家卡在建连
 * 或上游静默不吐数据，线程就堵在 readLine 里直到 300 秒读超时——RACE_POOL 被占满、
 * 上游额度也被白耗。
 *
 * 现在 winner 决出后，主线程直接掐掉输家的上游连接：输家线程立刻从 readLine 里
 * 抛出来。被掐的输家不记失败（Handle.cancelled 会告诉外层把它当 RaceLost 处理）。
 *
 * 用法：竞速的每路线程 bind(id) 拿 Handle → 传给 Http.stream；
 * winner 决出后调 cancelLosers(winnerId)；每路 finally 里 unbind(id)。
 * 非竞速线程调 current() 拿到 null，Http.stream 里按"无取消"处理。
 */
final class RaceCancels {

    /** 一路的可掐句柄。 */
    static final class Handle {
        volatile java.net.HttpURLConnection conn;
        volatile boolean cancelled;

        void cancel() {
            cancelled = true;
            java.net.HttpURLConnection c = conn;
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * 句柄表按"竞速批次"隔离。
     *
     * 原来只有一张全局表、key 是"账号/模型"：两场并发竞速（RACE_GATE 允许 16 场）里
     * 同一路的名字会互相覆盖——A 场的句柄被 B 场顶掉，赢家决出时可能掐错别人正在跑的流，
     * 输家线程还可能永远漏掉。现在每场竞速开一个 bucket，取消只在自己那场里生效。
     */
    private static final java.util.concurrent.ConcurrentHashMap<Object,
            java.util.concurrent.ConcurrentHashMap<String, Handle>> MAP =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final ThreadLocal<Handle> LOCAL = new ThreadLocal<>();

    /**
     * 已结束的竞速批次。
     *
     * 原来 endRace 只是从 MAP 里移除 bucket，但迟到的 racer 线程若在此之后才调 bind()，
     * table() 会**重新创建**一整张表并放进 MAP——那张表再也没人删，且它的句柄永远等不到取消。
     * 这里显式记下已死的 bucket，迟到的 bind 直接返回一次性句柄、不进 MAP。
     */
    private static final java.util.Set<Object> DEAD =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<Object, Boolean>());

    /** 开一场竞速（返回 bucket key，传给 bind / cancelLosers / close）。 */
    static Object beginRace() {
        Object bucket = new Object();
        MAP.put(bucket, new java.util.concurrent.ConcurrentHashMap<>());
        return bucket;
    }

    /** 一场竞速结束：丢掉它所有的句柄（含还没 unbind 的输家），并记为已结束。 */
    static void endRace(Object bucket) {
        if (bucket == null) return;
        MAP.remove(bucket);
        DEAD.add(bucket);
        // DEAD 只是防止"迟到 bind 重建表"，不需要长期保留；竞速结束即无新 bind 进入。
        // 用一个有界清理：超过 256 个已死批次时清掉最早的（并发竞速上限 16，正常远达不到）。
        if (DEAD.size() > 256) {
            java.util.Iterator<Object> it = DEAD.iterator();
            if (it.hasNext()) { it.next(); it.remove(); }
        }
    }

    static Handle bind(Object bucket, String racerId) {
        Handle h = new Handle();
        if (bucket != null && DEAD.contains(bucket)) {
            // 批次已结束：只给调用方一个句柄（让 finally 里的 unbind 不炸），不建表、不进 MAP
            LOCAL.set(h);
            return h;
        }
        java.util.concurrent.ConcurrentHashMap<String, Handle> m = table(bucket);
        m.put(racerId, h);
        LOCAL.set(h);
        return h;
    }

    private static java.util.concurrent.ConcurrentHashMap<String, Handle> table(Object bucket) {
        if (bucket == null) return new java.util.concurrent.ConcurrentHashMap<>();
        java.util.concurrent.ConcurrentHashMap<String, Handle> m = MAP.get(bucket);
        if (m == null) {
            m = new java.util.concurrent.ConcurrentHashMap<>();
            java.util.concurrent.ConcurrentHashMap<String, Handle> prev = MAP.putIfAbsent(bucket, m);
            if (prev != null) m = prev;
        }
        return m;
    }

    /** 当前线程的竞速句柄；非竞速线程返回 null。 */
    static Handle current() {
        return LOCAL.get();
    }

    static void unbind(Object bucket, String racerId) {
        java.util.concurrent.ConcurrentHashMap<String, Handle> m = bucket == null ? null : MAP.get(bucket);
        if (m != null) m.remove(racerId);
        LOCAL.remove();
    }

    /** 掐掉这一场里所有的路（超时无胜出时用：没有赢家，全都要收）。 */
    static void cancelAll(Object bucket) {
        java.util.concurrent.ConcurrentHashMap<String, Handle> m = bucket == null ? null : MAP.get(bucket);
        if (m == null) return;
        for (java.util.Map.Entry<String, Handle> e : m.entrySet()) {
            e.getValue().cancel();
        }
    }

    /** 掐掉 winner 之外的所有路。 */
    static void cancelLosers(Object bucket, String winnerId) {
        java.util.concurrent.ConcurrentHashMap<String, Handle> m = bucket == null ? null : MAP.get(bucket);
        if (m == null) return;
        for (java.util.Map.Entry<String, Handle> e : m.entrySet()) {
            if (!e.getKey().equals(winnerId)) e.getValue().cancel();
        }
    }

    private RaceCancels() {
    }
}
