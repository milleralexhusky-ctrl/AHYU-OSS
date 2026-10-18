/**
 * 运行状态的只读快照：给界面上的动态层当参数用。
 *
 * <p>为什么单独开一个类：渲染层不该直接摸 {@link Capacity} 的推导逻辑
 * （那里有排队论、有窗口统计），它只想知道"现在有多忙、忙到什么程度"。
 *
 * <p>这里给出的都是**归一化后的无量纲量**（0..1）。
 * 原始数值直接喂给几何会被一个 3000ms 的尖峰整个甩飞，
 * 所以压缩与映射在这一层做完，渲染层只拿形状。
 *
 * <p>全部只读、无副作用，任意线程可调。
 */
package com.cpa.gateway.core;

public final class Activity {

    /** 最近一次读到的活动快照。渲染层每帧读它。 */
    public static final class Snapshot {
        /** 当前并发占用，归一化 0..1。 */
        public double load;
        /** 最近一次请求耗时，对数压缩后 0..1。 */
        public double latency;
        /** 请求密度 0..1。 */
        public double throughput;
        /** 综合压力 0..1，load 与 throughput 加权。 */
        public double pressure;
        /** 是否有任何活动。 */
        public boolean busy;
    }

    /** 请求密度的统计窗口：10 秒足够看出"在忙"，又不至于被一次突发长久拖住。 */
    private static final long RATE_WINDOW_MS = 10_000L;

    /** 每秒 20 条以上就算"满"，再快图形也不再展开。 */
    private static final double RPS_FULL = 20.0;

    private static final Snapshot S = new Snapshot();

    private Activity() {}

    public static Snapshot snapshot() {
        int live = Capacity.inflight();
        int cap = Capacity.suggest();
        if (cap < 1) cap = 1;

        /* load：并发占用比，用 sqrt 压缩。
           线性时 0..8 全挤在 0.08 以下，图形几乎不动，突发一来又猛跳；
           开方后低负载段也能看出变化。 */
        double load = Math.min(1.0, Math.sqrt((double) live / cap));

        /* throughput：请求密度，对数压缩。
           空闲时几秒一条、忙时几十条每秒，线性刻度下空闲期全长一样。 */
        double rps = Capacity.recentCount(RATE_WINDOW_MS) / (RATE_WINDOW_MS / 1000.0);
        double throughput = Math.min(1.0, Math.log1p(rps) / Math.log1p(RPS_FULL));

        /* latency：耗时，对数压缩。
           50ms 以下都像"瞬间"，2000ms 以上都像"卡死"，中间才可感。 */
        long lastMs = Capacity.lastDurationMs();
        double latency = lastMs <= 0 ? 0
                : Math.min(1.0, Math.log1p(lastMs / 50.0) / Math.log1p(2000.0 / 50.0));

        S.load = load;
        S.latency = latency;
        S.throughput = throughput;
        S.pressure = Math.min(1.0, load * 0.6 + throughput * 0.4);
        S.busy = live > 0 || rps > 0.01;
        return S;
    }
}
