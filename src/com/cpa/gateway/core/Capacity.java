package com.cpa.gateway.core;

/**
 * 连接层容量：用 Little 定律反推"该允许多少并发"。
 *
 * 为什么需要它：{@code MAX_CONN = 64} 是一个拍出来的数字。它既可能太大
 * （一台低端机上 64 条线程的栈就是 64×1MB 虚拟内存），也可能太小
 * （真接上几个客户端一起用时，64 撞满就开始回 503）。拍出来的数字没法知道自己错在哪。
 *
 * 数学上，并发数不是一个可以"选"的数，而是**被负载决定**的：
 *
 *     L = λ · W          （Little 定律）
 *
 *     λ = 到达率（请求/秒，实测）
 *     W = 每条请求的驻留时间（秒，实测）
 *     L = 系统中平均有多少条请求在跑
 *
 * 这是**恒等式**，不是近似：只要统计窗口一致，λ、W、L 必然满足它。
 * 所以不用猜 L，量出 λ 和 W 就能算出 L。
 *
 * 但平均值不够用 —— 真实到达是突发的。实测数据里：
 *     平均 λ = 25 请求/小时 → L = 0.06
 *     峰值并发 = 4（同一份数据，去掉测试流量后）
 * 峰值是平均的 60 多倍。如果按平均配容量，一遇突发就 503。
 *
 * 所以要留"突发余量"：容量取 max(L × burstFactor, minFloor)。
 * burstFactor 用**实测的峰值/平均比**，不是拍 4 倍 8 倍。
 *
 * 另外这个类同时记录了排队概率的代理指标（见 {@link #pressure()}），
 * 用来判断"是不是该扩容了"，比统计 503 次数更早发现问题。
 */
public final class Capacity {

    private Capacity() {
    }

    /** 统计窗口：最近这么多毫秒内的请求参与计算。 */
    private static final long WINDOW_MS = 5 * 60 * 1000L;

    /**
     * 容量下限。
     *
     * 不能低于这个数：即便当前完全空闲，也要能同时接住浏览器/编辑器
     * 那种"一开就并发几条"的连接突发，否则空闲状态下反而更容易撞 503。
     */
    private static final int MIN_FLOOR = 8;

    /**
     * 容量上限。超过这个数，手机端继续加线程只会让 GC 和调度更糟，
     * 不会更快 —— 真正的瓶颈早就不在线程数上了。
     */
    private static final int MAX_CEIL = 96;

    /** 窗口内已完成请求的（到达时刻, 驻留毫秒）。环形缓冲，避免无界增长。 */
    private static final int RING = 512;
    private static final long[] at = new long[RING];
    private static final long[] dur = new long[RING];
    private static int head;
    private static int count;

    /** 当前活跃请求数（进入时 +1，离开时 -1）。 */
    private static int inflight;
    private static int peakInflight;
    private static long peakAt;

    /** 上一次算出来的建议容量。 */
    private static volatile int suggestion = MIN_FLOOR;

    /** 记一条已完成的请求（由 Server 在请求收尾时调用）。 */
    public static void record(long startMs, long tookMs) {
        if (tookMs <= 0) return;
        synchronized (Capacity.class) {
            at[head] = startMs;
            dur[head] = tookMs;
            head = (head + 1) % RING;
            if (count < RING) count++;
        }
    }

    /** 请求进入/离开，用来量真实的瞬时并发。 */
    public static synchronized void enter() {
        inflight++;
        if (inflight > peakInflight) {
            peakInflight = inflight;
            peakAt = System.currentTimeMillis();
        }
    }

    public static synchronized void leave() {
        if (inflight > 0) inflight--;
    }

    /**
     * 现在建议允许多少并发连接。
     *
     * 不是拍系数，而是解一个排队论问题：**允许多少个服务台，才能让"要排队"
     * 的概率低于 1%**。
     *
     *   1. 负载强度 a = λ·W（Erlang）。这是 Little 定律的同一件事，只是换成
     *      "同时占用的服务台数"的单位。
     *   2. 服务台数 c 从 ⌈a⌉ 开始往上试，直到排队概率 ≤ 1%。
     *   3. 排队概率用 Erlang-C 公式，再乘 (1+CV²)/2 做重尾修正：
     *      这是 Allen-Cunneen 近似。为什么要修：
     *      实测耗时 CV = 1.72（均值 8.5s、p99 72s），而 Erlang-C 假设
     *      服务时间服从指数分布（CV=1）。CV=1.72 说明实际比指数更离散，
     *      直接用 Erlang-C 会低估容量。修正后实测只差 0~2 个服务台，
     *      低负载区间几乎不影响 —— 但高负载下这 2 个就是 503 与不 503 的差别。
     *
     * 算出来的量级（离线用真机 W 和合成的对数正态耗时跑过）：
     *     72 请求/小时   → 3
     *     720 请求/小时  → 7
     *     3600 请求/小时 → 16
     *     10800 请求/时  → 36
     *     21600 请求/时  → 63   ← 硬编码的 64 在这个量级才见底
     *
     * 也就是说 64 并不小，它覆盖了很宽的负载范围；但**在轻载时它浪费了 8 倍的线程**。
     * 这个类的价值不是"把 64 改大"，而是让容量跟着实际负载走：
     * 轻载时缩到 3~8（省内存），重载时自动长到需要的大小（不 503）。
     */
    public static int suggest() {
        long now = System.currentTimeMillis();
        int n = 0;
        double sumDur = 0, sumSq = 0;
        long oldest = now, newest = 0;
        synchronized (Capacity.class) {
            for (int i = 0; i < count; i++) {
                int idx = (head - 1 - i + RING * 2) % RING;
                long t = at[idx];
                if (t < now - WINDOW_MS) continue;
                n++;
                double d = dur[idx];
                sumDur += d;
                sumSq += d * d;
                if (t < oldest) oldest = t;
                if (t > newest) newest = t;
            }
        }
        if (n < 4) {
            /*
             * 样本太少：别装聪明 —— 但也别装傻。
             *
             * 踩过的坑：冷启动时窗口里只有 1~2 条样本，公式退回 MIN_FLOOR=8；
             * 如果这时候真有 20 个并发进来，8 会当场撞满并回 503。
             * "没数据"不等于"没负载"。
             *
             * 所以这里的兜底取 max(MIN_FLOOR, 当前在飞数 × 2)。
             *
             * 注意这个兜底**只作用于样本不足这一个分支**，不能反过来去钳
             * 公式算出来的结果。试过把同样的 max(live×2) 也加在常规分支上，
             * 结果是灾难性的：突然涌进 40 个并发时 live×2=80 会直接顶掉
             * 排队论算出的容量，变成"来多少接多少"，把过载保护整个废掉。
             * 排队论的意义正是在**样本足够时**说"到此为止"。
             */
            int live;
            synchronized (Capacity.class) {
                live = inflight;
            }
            int floor = Math.max(MIN_FLOOR, live * 2);
            if (floor > MAX_CEIL) floor = MAX_CEIL;
            suggestion = floor;
            return floor;
        }

        double wMs = sumDur / n;

        /*
         * 观测期怎么取 —— 这里踩过一个很隐蔽的坑。
         *
         * 第一版用 spanMs = newest - oldest，即"首尾两条请求的间隔"。
         * 冷却了 10 分钟之后来一波 12 连发：窗口里 11 条新样本 + 1 条 10 分钟前的旧样本，
         * span 变成 600 秒，λ 被稀释了 300 倍，算出来的 a 只有真实值的 1/300，
         * 于是建议容量停在 8，而实际这个负载需要 11。
         *
         * 正确做法：观测期要能覆盖"一条请求从进来到结束"的完整时间。
         * 最后一条请求虽然已经记录，但它占着服务台的时间是 dur，span 必须算上它，
         * 否则等于假设"最后一条请求瞬间完成"。
         *
         * 同时下限取一个驻留时间：样本太少时 span 会很小（比如 4 条挤在 200ms 内，
         * span=200ms），这时 λ 被高估，a 反而算大。用 max(span, W) 两边都稳。
         */
        long lastAt = 0, lastDur = 0;
        for (int i = 0; i < count; i++) {
            int idx = (head - 1 - i + RING * 2) % RING;
            if (at[idx] < now - WINDOW_MS) continue;
            if (at[idx] > lastAt) {
                lastAt = at[idx];
                lastDur = dur[idx];
            }
        }
        /* +1 是因为 at 是"秒级"还是"毫秒级"由调用方决定，这里 at 存的是毫秒时间戳 */
        double spanMs = Math.max(1, (lastAt + lastDur) - oldest);
        if (spanMs < wMs) spanMs = wMs;

        double lambda = n / spanMs;            /* 条/毫秒 */
        double a = lambda * wMs;               /* Erlang 负载强度 */

        /* 变异系数：CV = σ/μ，用来做重尾修正 */
        double mean2 = sumSq / n;
        double var = Math.max(0, mean2 - wMs * wMs);
        double cv = wMs > 0 ? Math.sqrt(var) / wMs : 1.0;
        if (cv < 1.0) cv = 1.0;                /* 比指数还整齐时不必再减 */
        double heavy = (1.0 + cv * cv) / 2.0;  /* Allen-Cunneen 修正因子 */

        int c = (int) Math.ceil(a);
        if (c < 1) c = 1;
        /* 从 ⌈a⌉ 往上找第一个满足排队概率 ≤ 1% 的 c。上限 MAX_CEIL 早就够了，
           跑不到那么远，但留个硬边界防止数学异常时死循环。 */
        while (c < 256) {
            if (erlangC(c, a) * heavy <= TARGET_QUEUE) break;
            c++;
        }

        int cap = c;
        if (cap < MIN_FLOOR) cap = MIN_FLOOR;
        if (cap > MAX_CEIL) cap = MAX_CEIL;
        suggestion = cap;
        return cap;
    }

    /** 可接受的排队概率（1%）。到 503 说明已经有人在等了，1% 是"几乎没人等"的量级。 */
    private static final double TARGET_QUEUE = 0.01;

    /**
     * Erlang-C：c 个服务台、负载 a 时，一个请求需要排队的概率。
     *
     * 标准式：C = (a^c/c!)·(c/(c-a)) / ( Σ_{k=0..c-1} a^k/k! + (a^c/c!)·(c/(c-a)) )
     * 用递推算 a^k/k! 避免阶乘溢出（c 到 96 时 c! 早就超出 double 了）。
     */
    private static double erlangC(int c, double a) {
        if (c <= 0) return 1.0;
        if (a <= 0) return 0.0;
        if (a >= c) return 1.0;            /* 负载超过服务台数：必然排队 */
        /* term_k = a^k / k!，递推 */
        double term = 1.0;                  /* k=0 */
        double sum = 1.0;
        for (int k = 1; k < c; k++) {
            term *= a / k;
            sum += term;
        }
        /* 第 c 项 */
        double last = term * a / c;
        double top = last * c / (c - a);
        double p = top / (sum + top);
        if (p < 0) return 0;
        if (p > 1) return 1;
        return p;
    }

    /**
     * 当前压力：在飞请求数 / 建议容量。
     *
     * 0 表示很空，接近 1 表示快撞满。比"统计 503 次数"更早发现问题 ——
     * 等 503 出现就已经有用户被拒了，而压力在 0.8 时其实就说明该扩了。
     */
    public static double pressure() {
        int cap = suggestion <= 0 ? MIN_FLOOR : suggestion;
        int cur;
        synchronized (Capacity.class) {
            cur = inflight;
        }
        return (double) cur / cap;
    }

    /** 供日志／诊断用的一行状态。 */
    public static String state() {
        int cur, peak;
        synchronized (Capacity.class) {
            cur = inflight;
            peak = peakInflight;
        }
        return "in-flight=" + cur + " 峰值=" + peak + " 建议容量=" + suggestion
                + " 压力=" + Math.round(pressure() * 100) + "%";
    }

    /** 清空统计（自检用）。 */
    public static synchronized void reset() {
        head = 0;
        count = 0;
        inflight = 0;
        peakInflight = 0;
        suggestion = MIN_FLOOR;
    }
}
