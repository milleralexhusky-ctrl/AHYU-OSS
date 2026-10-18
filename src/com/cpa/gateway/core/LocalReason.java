package com.cpa.gateway.core;

/**
 * 「这件事是我们自己造成的」——与上游无关的原因。
 *
 * 为什么需要它：以前这类原因只能靠**读中文文案**认回来。
 * 比如 Server 抛 `new Throttled(429, ..., "并发已满")`，Detect 再
 * `w.contains("并发已满")` 认出它是本地原因。同一条错误被翻译成中文、
 * 再被反解回原因 —— 改一个字（"并发已满" → "当前并发已满"）判断就静默失效，
 * 而编译器不会提醒。
 *
 * 现在：原因用枚举传，文案随便改。判断读字段，展示读 message。
 *
 * 注意与 {@link Throttled#kind} 的区别：
 *   kind  说的是**上游**为什么拒（rate / quota / auth）
 *   Local 说的是**我们**为什么没发出去（本地忙 / 熔断 / 网关没起…）
 * 两者可以同时存在：本地并发满了，就根本没有上游响应。
 */
enum LocalReason {
    /** 该账号并发到顶（maxConcurrent）。 */
    BUSY,
    /** 命中了本地限额（今日配额等），要等一段时间。 */
    QUOTA,
    /** 网关本身没在运行。 */
    GATEWAY_DOWN,
    /** 熔断器 OPEN，主动跳过。 */
    CIRCUIT_OPEN,
    /** 刚失败过，暂避期内不重试。 */
    COOLDOWN,
    /** 探测超时：我们自己不等了，不是上游的问题。 */
    PROBE_TIMEOUT,
    /** 其它本地原因。 */
    OTHER;

    /** 是不是"我们自己造成的"——这类失败绝不该给账号或模型扣坏标记。 */
    boolean isLocal() {
        return true;
    }
}
