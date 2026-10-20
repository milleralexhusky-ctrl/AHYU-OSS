package com.cpa.gateway.core;

import java.util.Locale;

/**
 * 一次上游失败到底该怪谁（4.50.2）。
 *
 * 以前所有失败都走同一条路：记这个「账号/模型」失败 → 换下一个**账号**。
 * 但实际有三类完全不同的东西：
 *
 *   · 坏号：凭证失效、额度耗尽、限流、渠道校验 —— 换号有用；
 *   · 坏模型：工具参数不是合法 JSON、输出结构坏了 —— 换号没用，换模型才有用；
 *   · 坏请求：消息序列不合法、参数上游不认 —— 换号换模型都没用，应该直接告诉客户端。
 *
 * 把三者混在一起会同时产生两种错误行为：
 *   1) 一个模型一直吐坏工具参数，网关把同一模型的 N 个账号全记成"不可用"；
 *   2) 纯调用（客户端点名模型）时，网关却按"轮换"的直觉去换模型，违背客户端意图。
 *
 * 现在把判断集中到这里，让调用方按 {@link #stopDirect()} / {@link #skipModelInRotation()}
 * 决定"换号、换模型、还是直接报错"。
 */
enum FailScope {
    /** 账号/凭据/额度/限流/渠道校验：换号有意义。 */
    ACCOUNT,
    /** 模型行为坏了：换号没用，换模型。 */
    MODEL_BAD,
    /** 这个账号没有/不支持该模型：轮换时换模型；纯调用可以换号再确认一次。 */
    MODEL_ACCESS,
    /** 请求本身不合法：换谁都没用。 */
    REQUEST,
    /** 网络/超时/5xx：换号重试，但不要把模型判死。 */
    TRANSIENT,
    /** 归不了类：按旧的保守口径处理（记账号失败）。 */
    UNKNOWN;

    static FailScope of(Throwable t) {
        if (t == null) return UNKNOWN;
        if (t instanceof ToolFix.BadToolCall) return MODEL_BAD;
        if (t instanceof Throttled) return ACCOUNT;
        if (t instanceof ClientGone || t instanceof RaceLost) return UNKNOWN;

        String msg = t.getMessage() == null ? "" : t.getMessage().toLowerCase(Locale.US);

        /* 模型行为：工具参数坏了是模型产出的问题，不是账号的问题。 */
        if (msg.contains("工具参数不是合法") || msg.contains("bad tool call")
                || (msg.contains("tool") && msg.contains("json")
                    && (msg.contains("invalid") || msg.contains("parse") || msg.contains("malformed")))
                || (msg.contains("function") && msg.contains("arguments")
                    && (msg.contains("invalid") || msg.contains("parse")))) {
            return MODEL_BAD;
        }

        /* 模型接入：账号没有这个模型 / 不支持这个模型。 */
        boolean modelWord = msg.contains("model") || msg.contains("模型");
        if (modelWord && (msg.contains("not found") || msg.contains("not exist")
                || msg.contains("not support") || msg.contains("unsupported")
                || msg.contains("service info not found") || msg.contains("invalid model")
                || msg.contains("no access") || msg.contains("does not support")
                || msg.contains("不存在") || msg.contains("不支持") || msg.contains("不可用"))) {
            return MODEL_ACCESS;
        }

        if (t instanceof InvalidRequest) return REQUEST;
        if (Detect.isTransient(t) || Detect.isTransient(msg)) return TRANSIENT;

        /* 401/402/403 一定是账号侧（429 会被 Throttled 捕获，走不到这里）。 */
        if (msg.contains("401") || msg.contains("402") || msg.contains("403")
                || msg.contains("unauthorized") || msg.contains("invalid api key")
                || msg.contains("authentication") || msg.contains("permission denied")) {
            return ACCOUNT;
        }
        return UNKNOWN;
    }

    /** 纯调用（点名模型）时，这个失败是否应该立刻停止换号重试。 */
    boolean stopDirect() {
        return this == MODEL_BAD || this == REQUEST;
    }

    /** 轮换模式（auto*）时，这个失败是否应该把整个模型从本轮候选里排除。 */
    boolean skipModelInRotation() {
        return this == MODEL_BAD || this == MODEL_ACCESS;
    }

    /** 轮换模式时，这个失败是否应该直接结束整轮选路（换个模型也没用）。 */
    boolean stopRotation() {
        return this == REQUEST;
    }

    boolean modelScoped() {
        return this == MODEL_BAD || this == MODEL_ACCESS;
    }

    /** 写进 usage.tsv / 错误响应的类别键。 */
    String kind() {
        switch (this) {
            case MODEL_BAD:
            case MODEL_ACCESS:
                return "model";
            case REQUEST:
                return "badreq";
            case ACCOUNT:
                return "account";
            case TRANSIENT:
                return "transient";
            default:
                return "other";
        }
    }

    /** OpenAI 风格错误里的 type。 */
    String clientType() {
        switch (this) {
            case MODEL_BAD:
            case MODEL_ACCESS:
                return "model_error";
            case REQUEST:
                return "invalid_request_error";
            case ACCOUNT:
                return "account_error";
            case TRANSIENT:
                return "upstream_error";
            default:
                return "upstream_error";
        }
    }
}
