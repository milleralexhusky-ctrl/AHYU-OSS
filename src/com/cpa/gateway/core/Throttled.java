package com.cpa.gateway.core;

import java.io.IOException;

/**
 * 上游限流（429 / 503 / 529），且上游给了（或我们需要假定一个）Retry-After。
 *
 * 这和"账号失效"必须分开对待：限流只是"现在别来"，按上游说的退避几秒就行；
 * 如果按普通失败记进熔断，一个本来健康的账号会被冷却好几分钟（还会指数退避到 10 分钟），
 * 客户端那边表现为"明明换个号就能过，却一直转圈"。
 *
 * 499/无 Retry-After 的 429 也走这里，只是用默认退避（20 秒）。
 */
final class Throttled extends IOException {

    final int code;
    final long retryAfterMs;
    /** "rate"=限流、"quota"=额度/计费耗尽、"auth"=凭证失效（重试没意义）。 */
    final String kind;
    /**
     * 上游**明说的**恢复时刻（epoch 毫秒），0 = 上游没说。
     *
     * 为什么要单独留一份：我们的默认退避是拍脑袋的固定值（429 → 5 分钟），
     * 而上游经常直接告诉你"将在 2026-10-16 01:24:11 重置"——那可能是两个半小时。
     * 按固定 5 分钟退避，这段时间里每 5 分钟就会再撞一次墙、白跑一个来回；
     * 而用户在界面上也看不到"到底什么时候能好"，只能反复试。
     * 有了这个值，退避能一次对齐到点，界面也能直接说清"限流到几点"。
     */
    final long resetAtMs;

    Throttled(int code, long retryAfterMs, String kind, String message) {
        this(code, retryAfterMs, 0L, kind, message);
    }

    Throttled(int code, long retryAfterMs, long resetAtMs, String kind, String message) {
        super(message);
        this.code = code;
        this.kind = kind;
        /* 在**构造函数里**解析"上游说的恢复时刻"，而不是只在某一个出口解析。
           原因：构造 Throttled 的地方不止一处 —— 除了 Http.upstreamError，
           各家原生上游（WorkBuddy 的 code 6004、Kimi、Trae…）也会自己 new 一个。
           实测就踩到了：WorkBuddy 那条 429 的正文里明明写着"将在 2026-10-16 01:24:11 重置"，
           但它走的是自己的构造路径，于是仍然按默认 5 分钟退避，界面上也显示成"约 5 分钟后恢复"。
           放在这里，任何上游构造的限流都会自动带上正确的时刻。 */
        long reset = resetAtMs;
        if (reset <= 0L && message != null && !message.isEmpty()) {
            reset = Http.parseResetAtMs(message);
        }
        long back = Math.max(0L, retryAfterMs);
        if (reset > 0L) {
            long diff = reset - System.currentTimeMillis();
            if (diff > back) back = diff;          // 上游说的比默认退避更远 → 按上游的来
        }
        this.resetAtMs = reset;
        this.retryAfterMs = back;
    }
}
