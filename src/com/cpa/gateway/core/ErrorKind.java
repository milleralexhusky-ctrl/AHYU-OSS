package com.cpa.gateway.core;

/**
 * 错误归类（4.36.1）。
 *
 * 为什么需要：`usage.tsv` 的失败只记了 0，不知道**为什么**失败。
 * 而不同错误的正确应对完全相反：
 *
 *   · 限流     → 等一会儿，换号也能过
 *   · 额度耗尽 → 换号，等没用
 *   · 鉴权失效 → 重新登录，重试没用
 *   · 渠道校验 → 账号被标记，改请求形状也不会好（实测过）
 *   · 请求非法 → 是**我们**发错了，换号更没用
 *   · 空响应   → 上游给了回应但没内容，可能是偶发
 *
 * 把这几类混成"失败"，就没法知道该重试、该换号、还是该改代码。
 *
 * 判据直接复用现有实现，不再造第二套：
 *   Throttled.kind（rate/quota/auth/channel）、LocalReason、InvalidRequest。
 */
final class ErrorKind {

    static final String NONE = "";          // 没出错
    static final String RATE = "rate";      // 限流
    static final String QUOTA = "quota";    // 额度/余额
    static final String AUTH = "auth";      // 凭证失效
    static final String CHANNEL = "channel";// 渠道校验（11128，改形状无效）
    static final String BADREQ = "badreq";  // 请求非法（我们自己的问题）
    static final String MODEL = "model";    // 模型行为/接入问题（不是账号问题）
    static final String TIMEOUT = "timeout";
    static final String NET = "net";        // 连不上
    static final String EMPTY = "empty";    // 上游回了但没内容
    static final String OTHER = "other";

    private ErrorKind() {
    }

    /** 从一个异常归类。没有异常但结果为空时传 EMPTY。 */
    static String of(Throwable t) {
        if (t == null) return NONE;

        /* 模型行为/接入问题：既不是"号坏了"，也不是"请求非法"。
           放在 InvalidRequest 之前：上游 400 里带 model not found 时要归到模型侧。 */
        FailScope scope = FailScope.of(t);
        if (scope == FailScope.MODEL_BAD || scope == FailScope.MODEL_ACCESS) return MODEL;

        /* InvalidRequest = 我们发错了，和渠道无关 */
        if (t instanceof InvalidRequest) return BADREQ;

        if (t instanceof Throttled) {
            Throttled th = (Throttled) t;
            /* 本地原因（并发满/熔断/本地限额）不算渠道问题 */
            if (th.local != null) {
                if (th.local == LocalReason.QUOTA) return QUOTA;
                if (th.local == LocalReason.BUSY) return RATE;
                return OTHER;
            }
            if (th.kind == null) return OTHER;
            switch (th.kind) {
                case "rate": return RATE;
                case "quota": return QUOTA;
                case "auth": return AUTH;
                case "channel": return CHANNEL;
                default: return OTHER;
            }
        }

        String msg = t.getMessage() == null ? "" : t.getMessage().toLowerCase(java.util.Locale.US);
        if (msg.contains("timeout") || msg.contains("timed out") || msg.contains("超时")) return TIMEOUT;
        if (msg.contains("connect") || msg.contains("unreachable")
                || msg.contains("unknownhost") || msg.contains("econnrefused")) return NET;
        return OTHER;
    }

    /** 供报告显示：英文键 → 中文说明。 */
    static String label(String kind) {
        if (kind == null) return "";
        switch (kind) {
            case RATE: return "限流";
            case QUOTA: return "额度";
            case AUTH: return "鉴权";
            case CHANNEL: return "渠道校验";
            case BADREQ: return "请求非法";
            case MODEL: return "模型问题";
            case TIMEOUT: return "超时";
            case NET: return "连不上";
            case EMPTY: return "空响应";
            case OTHER: return "其他";
            default: return "";
        }
    }
}
