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
        if (t instanceof ToolFix.BadToolCall) {
            /* 被 max_tokens 截断的工具参数不是模型"不会用工具"，
               而是客户端给的长度不够 —— 按请求问题处理，不写模型画像。 */
            return ((ToolFix.BadToolCall) t).truncated ? REQUEST : MODEL_BAD;
        }
        if (t instanceof Throttled) return ACCOUNT;
        /* 上游返回了空内容：不是账号坏了，也不是请求不合法；换一个候选往往就有内容。 */
        if (t instanceof EmptyUpstream) return TRANSIENT;
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

        /* 模型接入：只认明确的"没有这个模型/没权限"。
           刻意不收 "does not support" / "unsupported"：那多半是请求用了模型不支持的参数，
           属于请求问题，换号也没用。 */
        boolean modelWord = msg.contains("model") || msg.contains("模型");
        if (modelWord && (msg.contains("not found") || msg.contains("not exist")
                || msg.contains("service info not found") || msg.contains("invalid model")
                || msg.contains("no access") || msg.contains("不存在"))) {
            return MODEL_ACCESS;
        }

        /* 渠道校验必须先于 InvalidRequest：WorkBuddy 现在用 HTTP 400 + code 11128
           "Illegal API invocation from an unapproved channel" 表达"这个号/这次调用
           没通过渠道校验"。它是账号侧问题（换号有意义），不是请求不合法；
           以前会归成 REQUEST，纯调用直接失败、轮换整轮中止，一个号被拦就连累
           所有别的渠道。 */
        if (msg.contains("unapproved channel") || msg.contains("illegal api invocation")
                || msg.contains("channel verification") || msg.contains("未通过渠道校验")
                || (msg.contains("11128") && (msg.contains("conversation")
                        || msg.contains("channel") || msg.contains("渠道")))) {
            return ACCOUNT;
        }

        /* WorkBuddy 11133 / ext 1210 "rejected by the model provider"：
           上游的**模型侧**故障话术（zh 原文「模型提供方错误」），不是我们的请求不合法。
           真机实证（2026-10-25）：同一份最小请求体 glm-5.1 必回 11133、
           hy3 正常 —— 换模型能过、换号没用（两个号背靠背都 11133）。
           旧版它落到下面的 InvalidRequest 分支变成 REQUEST：点名时不换号直接死、
           轮换时 stopRotation 中止整轮 —— 一个上游坏模型把整个池子陪葬。
           必须排在 InvalidRequest 之前：它就是以 HTTP 400 InvalidRequest 的形状进来的。 */
        if (msg.contains("rejected by the model provider")
                || msg.contains("模型提供方错误") || msg.contains("模型无法处理")
                || (msg.contains("11133") && msg.contains("model"))) {
            return MODEL_BAD;
        }

        /* InvalidRequest 必须先于网络关键词判断：HTTP 400 "request body is not json"
           是请求问题，不是断流；以前会被 "not json" 误判成 TRANSIENT 然后换号重试。 */
        if (t instanceof InvalidRequest) return REQUEST;

        /* 网络/断流类：这些不是账号坏了，也不该写 lastError 让账号"不可用 30 分钟"。 */
        if (t instanceof java.io.EOFException || t instanceof java.net.SocketException
                || t instanceof java.net.UnknownHostException
                || t instanceof javax.net.ssl.SSLException) {
            return TRANSIENT;
        }

        /* 401/402/403 一定是账号侧（429 会被 Throttled 捕获，走不到这里）。
           用边界匹配，避免业务码里的 40123 被当成 HTTP 401。 */
        if (ACCOUNT_STATUS.matcher(msg).find()
                || msg.contains("unauthorized") || msg.contains("invalid api key")
                || msg.contains("authentication") || msg.contains("permission denied")) {
            return ACCOUNT;
        }

        if (msg.contains("stream ended before") || msg.contains("empty upstream response")
                || msg.contains("非 json") || msg.contains("not json")
                || msg.contains("ssl") || msg.contains("handshake")
                || msg.contains("certificate") || msg.contains("unexpected eof")
                || msg.contains("eofexception")
                || msg.contains("connection reset") || msg.contains("broken pipe")) {
            return TRANSIENT;
        }
        if (Detect.isTransient(t) || Detect.isTransient(msg)) return TRANSIENT;
        return UNKNOWN;
    }

    /** HTTP 401/402/403 的边界匹配。 */
    private static final java.util.regex.Pattern ACCOUNT_STATUS =
            java.util.regex.Pattern.compile("\\b40[123]\\b");

    /** 纯调用（点名模型）时，这个失败是否应该立刻停止换号重试。 */
    boolean stopDirect() {
        return this == MODEL_BAD || this == REQUEST;
    }

    /**
     * 轮换模式（auto*）时，这个失败是否应该把整个模型从本轮候选里排除。
     *
     * 只有 MODEL_BAD（模型产出坏了）才按模型排除。MODEL_ACCESS 可能只是
     * "这个账号没有该模型/没权限"，同模型的另一个账号可能完全正常，
     * 所以只跳过当前候选，不封杀整个模型。
     */
    boolean skipModelInRotation() {
        return this == MODEL_BAD;
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
