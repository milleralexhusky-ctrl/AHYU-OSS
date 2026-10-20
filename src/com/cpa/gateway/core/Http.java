package com.cpa.gateway.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 上游 HTTP 统一出口。
 *
 * 合并了原来 Upstreams 里 7 个"长得一样"的 helper（get / postForm / postJson /
 * postJsonAllowingError / postRawAllowingError / postStream…），行为保持一致，
 * 只是换了个地方实现，调用方一行不用改。
 *
 * 关键优化——连接复用（低延迟）：
 *   Android 的 HttpURLConnection 自带按 host 的连接池，但原来每个方法 finally 里都
 *   调 conn.disconnect()，等于每次请求都重新走 TCP + TLS 握手（移动网络下几百毫秒）。
 *   现在：body 完整读完的 → 只关流、不 disconnect，连接回池给下次复用；
 *   中途抛异常（没读完，会污染池里的连接）→ 才 disconnect 丢弃。
 *   竞速输家（RaceLost 从 sink 里抛出来掐连接）走的正是"中途抛异常"这条，
 *   语义和原来 finally-disconnect 完全一致。
 */
public final class Http {

    private Http() {
    }

    /** 行式流回调；抛异常 = 这一路不要了（竞速输家、客户端断开），连接会被丢弃不断复用。 */
    public interface LineSink {
        void line(String s) throws Exception;
    }

    /** 一次性请求的结果：HTTP 状态码 + 完整 body 文本。 */
    public static final class Resp {
        public final int code;
        public final String body;

        Resp(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    private static String shorten(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').trim();
        return t.length() > 400 ? t.substring(0, 400) + "..." : t;
    }

    private static void applyHeaders(HttpURLConnection conn, Map<String, String> headers) {
        if (headers == null) return;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getValue() != null && !e.getValue().isEmpty()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }
        }
    }

    /** 分类：凭证失效 / 额度耗尽 / 渠道校验 / 普通限流。 */
    static String kindOf(int code, String body) {
        if (code == 401 || code == 403) return "auth";
        if (code == 402 || quota(body)) return "quota";
        /* 渠道校验（WorkBuddy 的 11128 "channel verification"）：账号/会话被上游标记，
           退避没有意义、换号才有用 —— 单列一类，别混进"限流"。 */
        if (channel(body)) return softChannel(body) ? "channel_soft" : "channel";
        return "rate";
    }

    /**
     * 渠道校验里的"请求级风控"：上游明说"请重新发送"的那一种。
     *
     * 和账号被标记（`conversation id missing` / `request illegal`）区分：
     * 后者换号/重新登录才有用；前者等一两秒原样重发就可能过，
     * 不该把账号按 6 小时不可用处理。
     */
    static boolean softChannel(String body) {
        if (body == null) return false;
        String b = body.length() > 4000 ? body.substring(0, 4000) : body;
        b = b.toLowerCase(java.util.Locale.US);
        return b.contains("unapproved channel")
                || (b.contains("illegal api invocation")
                    && (b.contains("send it again") || b.contains("resend")
                        || b.contains("重新发送") || b.contains("重新傳送")));
    }

    /**
     * 正文是不是"渠道校验"这类拒绝（不看 HTTP 码，只看上游原话）。
     *
     * 2026-10 起 WorkBuddy 国际网关换了措辞：HTTP 400 + code 11128
     * `Illegal API invocation from an unapproved channel`，displayMsg 是
     * "Request blocked. Please send it again"。旧关键词（channel verification /
     * request illegal）不再出现，所以以前这条会被当成普通 400 请求错误 ——
     * 既不换号，也不重试，直接把上游原文丢给客户端。
     */
    static boolean channel(String body) {
        if (body == null) return false;
        String b = body.length() > 4000 ? body.substring(0, 4000) : body;
        b = b.toLowerCase(java.util.Locale.US);
        return b.contains("channel verification") || b.contains("可能不是官方客户端")
                || b.contains("unapproved channel") || b.contains("illegal api invocation")
                || b.contains("未通过渠道校验") || b.contains("沒通過渠道校驗")
                || (b.contains("11128") && (b.contains("request illegal")
                        || b.contains("channel") || b.contains("渠道")));
    }

    /* ================================================== 失败原因分类（凭证健康度） */

    /**
     * 失败大类：
     *   AUTH     凭证失效，重试和等都没用 → 唯一出路是重新登录 / 换号
     *   QUOTA    额度耗尽 → 等额度恢复或换号
     *   RATE     限流 → 等一会儿就好
     *   NETWORK  网络 / 超时 / DNS / TLS → 换代理或稍后再试，账号本身没问题
     *   UPSTREAM 上游 5xx → 对方故障，不是账号问题
     *   REQUEST  请求本身有问题（400/404/422…）→ 我们的锅，别怪账号
     *   UNKNOWN  归不了类
     *
     * 为什么值得单独做一层：以前 UI 上只有一句「最近调用失败：<上游原文>」，
     * 而 L1 缓存失效、L2 冷却这类中间层会把原始异常包装好几层，原文常是一串
     * "Throttled: auth: HTTP 401 ..."。用户看到的第一反应是"我的账号是不是封了"，
     * 但真正的处置动作（重登 vs 等一会儿 vs 查网络）完全不同。分类之后，
     * 界面可以直接说"凭证已失效，请重新登录"，而不是让用户自己读英文错误码。
     */
    public enum FailureKind { AUTH, QUOTA, RATE, NETWORK, UPSTREAM, REQUEST, UNKNOWN }

    /** 把一次失败（HTTP 码 / 异常）归类。code<=0 表示没有拿到 HTTP 响应。 */
    public static FailureKind classifyFailure(int code, Throwable t) {
        String msg = t == null ? "" : String.valueOf(t.getMessage());
        FailureKind byText = classifyText(msg);
        if (code == 401 || code == 403) return FailureKind.AUTH;
        if (code == 402) return FailureKind.QUOTA;
        if (code == 429 || throttled(code)) return FailureKind.RATE;
        if (code >= 500) return FailureKind.UPSTREAM;
        if (code >= 400) return FailureKind.REQUEST;
        if (code > 0) return FailureKind.UNKNOWN;
        /* 没有 HTTP 码：优先信异常文本，其次看 Throttled 自带的 kind */
        if (t instanceof Throttled) {
            String k = ((Throttled) t).kind;
            if ("auth".equals(k)) return FailureKind.AUTH;
            if ("quota".equals(k)) return FailureKind.QUOTA;
            if ("rate".equals(k)) return FailureKind.RATE;
        }
        return byText;
    }

    /**
     * 只靠错误文本归类（拿不到 HTTP 码时用）。
     *
     * 判断顺序不能反：先看"明确是账号问题"的关键词，再看网络关键词。
     * 因为脱敏后的文本里可能同时出现两者（"401 Unauthorized (连接已重置)"）。
     */
    static FailureKind classifyText(String msg) {
        if (msg == null || msg.isEmpty()) return FailureKind.UNKNOWN;
        String s = msg.toLowerCase(java.util.Locale.US);
        /* 凭证：各家措辞差异很大，逐家列出比正则更可控 */
        if (s.contains("invalid api key") || s.contains("invalid_api_key")
                || s.contains("incorrect api key") || s.contains("unauthorized")
                || s.contains("invalid token") || s.contains("token expired")
                || s.contains("token 过期") || s.contains("凭证")
                || s.contains("登录已失效") || s.contains("未授权")
                || s.contains("re-authorize") || s.contains("reauthorize")
                || s.contains("authentication") && s.contains("fail")
                || s.contains("permission denied") || s.contains("insufficient permission")
                || s.contains("account suspended") || s.contains("账号已被")
                || s.contains("no access to") || s.contains("access denied")) {
            return FailureKind.AUTH;
        }
        /* 额度：402 之外的常见文案 */
        if (s.contains("insufficient balance") || s.contains("insufficient_quota")
                || s.contains("insufficient quota") || s.contains("quota exceeded")
                || s.contains("exceeded your current quota") || s.contains("额度")
                || s.contains("余额不足") || s.contains("balance") && s.contains("low")
                || s.contains("out of credits") || s.contains("no credits")
                || s.contains("credits exhausted") || s.contains("billing")
                || s.contains("payment required")) {
            return FailureKind.QUOTA;
        }
        /* 限流 */
        if (s.contains("rate limit") || s.contains("ratelimit") || s.contains("too many request")
                || s.contains("限流") || s.contains("请求过于频繁")) {
            return FailureKind.RATE;
        }
        /* 网络：这一类比什么都常见，但恰恰是"账号没问题"的一类 */
        if (s.contains("unknownhost") || s.contains("no address associated")
                || s.contains("connection refused") || s.contains("failed to connect")
                || s.contains("econnrefused") || s.contains("econnreset")
                || s.contains("connection reset") || s.contains("socket")
                || s.contains("timed out") || s.contains("timeout")
                || s.contains("ehostunreach") || s.contains("enetunreach")
                || s.contains("network is unreachable") || s.contains("sslhandshake")
                || s.contains("certificate") || s.contains("pkix")
                || s.contains("connection closed") || s.contains("连接")
                || s.contains("超时") || s.contains("网络")) {
            return FailureKind.NETWORK;
        }
        /* 上游 5xx / 网关错误 */
        if (s.contains("bad gateway") || s.contains("service unavailable")
                || s.contains("internal server error") || s.contains("gateway timeout")
                || s.contains("502") || s.contains("503") || s.contains("504")
                || s.contains("服务器错误") || s.contains("上游")) {
            return FailureKind.UPSTREAM;
        }
        return FailureKind.UNKNOWN;
    }

    /** 给界面用的一句人话（可直接显示给用户，含"该做什么"）。 */
    public static String failureAdvice(String rawMessage, Throwable t) {
        FailureKind k = classifyFailure(-1, t != null ? t
                : new IllegalStateException(rawMessage == null ? "" : rawMessage));
        switch (k) {
            case AUTH:
                return "凭证已失效，需要重新登录或换账号";
            case QUOTA:
                return "额度已耗尽，等恢复或换账号";
            case RATE:
                return "被上游限流，稍等片刻会自动恢复";
            case NETWORK:
                return "网络或代理不通，账号本身没问题";
            case UPSTREAM:
                return "上游服务故障，稍后再试";
            case REQUEST:
                return "请求被上游拒绝，可能模型名或参数不对";
            default:
                return "调用失败，详情见日志";
        }
    }

    /**
     * 上游没给 Retry-After 时的默认退避（抄 Cline / zcode 实测的保守值）：
     *   429 限流 → 5 分钟
     *   402 计费 → 1 小时
     *   401/403 凭证 → 30 分钟（重试也没用，等重新登录）
     *   其它 → 20 秒
     */
    static long defaultBackoffMs(int code) {
        if (code == 429) return 300_000L;
        if (code == 402) return 3_600_000L;
        if (code == 401 || code == 403) return 1_800_000L;
        return 20_000L;
    }

    /**
     * 把我们发出去的上游请求返回的 4xx 归类成"该退避"的错误。
     *
     * 429/402/401/403 都属于"等一会儿 / 换个号 / 去重登"，**不该当普通失败打进熔断**：
     * 一个健康账号被限流三次就被我们自己冷却，是纯粹的自我伤害；401 也要走 30 分钟
     * 鉴权退避而不是无限重试。其它 4xx 仍按普通异常抛出（那多半是请求本身的问题）。
     */
    static Exception upstreamError(int code, String body) {
        if (code == 429 || code == 402 || code == 401 || code == 403
                || throttled(code) || quota(body) || channel(body)) {
            long resetAt = parseResetAtMs(body);
            long backoff = defaultBackoffMs(code);
            /* 上游明说了恢复时刻，就按它退避（前提是比默认退避更远）。
               例子：某中转站回 429 并写明"将在 2026-10-16 01:24:11 重置"，
               默认 5 分钟的退避会让网关在这两个半小时里每 5 分钟白撞一次。 */
            if (resetAt > System.currentTimeMillis() + backoff) {
                backoff = resetAt - System.currentTimeMillis();
            }
            return new Throttled(code, backoff, resetAt, kindOf(code, body),
                    "HTTP " + code + " " + shorten(body));
        }
        return new IllegalStateException("上游 HTTP " + code + " " + shorten(body));
    }

    /**
     * 从上游的错误正文里找"什么时候恢复"。
     *
     * 认两种写法（都是实测见过的）：
     *   1. 绝对时刻：`将在 2026-10-16 01:24:11 UTC+8 重置`
     *      —— 带 UTC±N 就按它算，没带就按本机时区（国内的中转站基本都是本机时区）。
     *   2. 相对时长：`try again in 20s` / `retry after 60 seconds` / `reset after 5 minutes`
     *
     * @return epoch 毫秒；认不出来返回 0（调用方会退回默认退避）
     */
    public static long parseResetAtMs(String body) {
        if (body == null || body.isEmpty()) return 0L;
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(\\d{4})-(\\d{2})-(\\d{2})[ T]+(\\d{2}):(\\d{2}):(\\d{2})")
                    .matcher(body);
            if (m.find()) {
                int y = Integer.parseInt(m.group(1));
                int mo = Integer.parseInt(m.group(2));
                int d = Integer.parseInt(m.group(3));
                int h = Integer.parseInt(m.group(4));
                int mi = Integer.parseInt(m.group(5));
                int se = Integer.parseInt(m.group(6));
                java.util.TimeZone tz = java.util.TimeZone.getDefault();
                /* 正文里若写了 UTC±N，按那个偏移算（否则用本机时区） */
                java.util.regex.Matcher z = java.util.regex.Pattern
                        .compile("UTC\\s*([+-])\\s*(\\d{1,2})(?::?(\\d{2}))?",
                                java.util.regex.Pattern.CASE_INSENSITIVE)
                        .matcher(body);
                if (z.find()) {
                    int offMin = Integer.parseInt(z.group(2)) * 60
                            + (z.group(3) == null ? 0 : Integer.parseInt(z.group(3)));
                    if ("-".equals(z.group(1))) offMin = -offMin;
                    tz = java.util.TimeZone.getTimeZone(java.util.TimeZone
                            .getTimeZone("UTC").getID());
                    // 把"UTC+N 的墙上时间"换算成 UTC 时刻
                    java.util.Calendar c = java.util.Calendar.getInstance(
                            java.util.TimeZone.getTimeZone("UTC"));
                    c.clear();
                    c.set(y, mo - 1, d, h, mi, se);
                    return c.getTimeInMillis() - (long) offMin * 60_000L;
                }
                java.util.Calendar c = java.util.Calendar.getInstance(tz);
                c.clear();
                c.set(y, mo - 1, d, h, mi, se);
                return c.getTimeInMillis();
            }
            /* 相对时长：英文与中文都认一下 */
            java.util.regex.Matcher r = java.util.regex.Pattern
                    .compile("(?:in|after)\\s*(\\d+)\\s*(second|sec|s\\b|minute|min|m\\b|hour|h\\b)",
                            java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(body);
            if (r.find()) {
                long n = Long.parseLong(r.group(1));
                String unit = r.group(2).toLowerCase(java.util.Locale.US);
                long mul = unit.startsWith("h") ? 3_600_000L
                        : unit.startsWith("m") ? 60_000L : 1_000L;
                return System.currentTimeMillis() + n * mul;
            }
        } catch (Throwable t) {
            Log.w("解析上游恢复时间失败（退回默认退避）：" + t);
        }
        return 0L;
    }

    /** 200 里的错误体：形如 {"error": ...}（去空白后以此开头）。 */
    static boolean looksLikeErrorBody(String body) {
        if (body == null) return false;
        String t = body.trim();
        return t.startsWith("{\"error\"") || t.startsWith("[{\"error\"");
    }

    /** 错误体里点名 insufficient_quota 的，是"额度用尽"，不是普通限流。 */
    static boolean quota(String body) {
        return body != null && body.toLowerCase(java.util.Locale.US).contains("insufficient_quota");
    }

    /** 429 / 503 / 529 是"上游忙"，不是"账号坏了"。 */
    static boolean throttled(int code) {
        return code == 429 || code == 503 || code == 529;
    }

    /**
     * 解析 Retry-After（秒数或 HTTP 日期），夹在 1 秒 ~ 10 分钟之间。
     * 拿不准就用 fallback（默认 20 秒）——上游没给信息时，短的退避比长的安全。
     */
    static long retryAfterMs(HttpURLConnection conn, long fallbackMs) {
        try {
            String v = conn.getHeaderField("Retry-After");
            if (v == null || v.trim().isEmpty()) return fallbackMs;
            v = v.trim();
            long sec;
            try {
                sec = Long.parseLong(v);
            } catch (Exception notSeconds) {
                java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(
                        "EEE, dd MMM yyyy HH:mm:ss z", java.util.Locale.US);
                long at = f.parse(v).getTime();
                sec = Math.max(0, (at - System.currentTimeMillis()) / 1000);
            }
            return Math.max(1000, Math.min(600_000, sec * 1000));
        } catch (Throwable t) {
            return fallbackMs;
        }
    }

    /** 二进制响应（音频等）：不能先转成 UTF-8 文本。 */
    public static final class Bin {
        public final int code;
        public final String contentType;
        public final byte[] body;

        Bin(int code, String contentType, byte[] body) {
            this.code = code;
            this.contentType = contentType;
            this.body = body;
        }
    }

    public static Bin callBinary(String method, String url, Map<String, String> headers,
                                 byte[] payload, String contentType,
                                 int connectMs, int readMs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        boolean clean = false;
        try {
            conn.setRequestMethod(method);
            conn.setConnectTimeout(connectMs);
            conn.setReadTimeout(readMs);
            conn.setRequestProperty("User-Agent", "CLIProxyAPI-Android/1.0");
            if (contentType != null) conn.setRequestProperty("Content-Type", contentType);
            applyHeaders(conn, headers);
            if (payload != null) {
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(payload.length);
                OutputStream os = conn.getOutputStream();
                os.write(payload);
                os.flush();
                os.close();
            }
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            byte[] body = in == null ? new byte[0] : readBytes(in);
            closeQuietly(in);
            clean = true;
            return new Bin(code, conn.getContentType(), body);
        } finally {
            if (!clean) {
                try {
                    conn.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** 单个上游响应体的上限。音频/二进制也够用；再大就断开，别把手机堆吃穿。 */
    private static final int MAX_RESP_BYTES = 32 * 1024 * 1024;

    private static byte[] readBytes(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        int total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > MAX_RESP_BYTES) {
                throw new IOException("upstream response too large (> " + MAX_RESP_BYTES + " bytes)");
            }
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private static String readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        int total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > MAX_RESP_BYTES) {
                throw new IOException("upstream response too large (> " + MAX_RESP_BYTES + " bytes)");
            }
            bos.write(buf, 0, n);
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void closeQuietly(InputStream in) {
        if (in == null) return;
        try {
            in.close();
        } catch (Exception ignored) {
        }
    }

    /**
     * 一次性请求（GET / POST），body 全读完 → 连接干净，可回池复用。
     *
     * @param payload  null 表示无 body（GET）
     * @param failOnError true: code>=400 抛异常；false: 把 code 和 body 都返回
     *                  （OAuth 轮询、签到业务码这类"4xx 也是正常回答"的场景用）
     */
    public static Resp call(String method, String url, Map<String, String> headers,
                            byte[] payload, String contentType,
                            int connectMs, int readMs, boolean failOnError) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        boolean clean = false;   // body 完整读完 → 连接可复用；否则丢弃
        try {
            conn.setRequestMethod(method);
            conn.setConnectTimeout(connectMs);
            conn.setReadTimeout(readMs);
            conn.setRequestProperty("User-Agent", "CLIProxyAPI-Android/1.0");
            if (contentType != null) conn.setRequestProperty("Content-Type", contentType);
            applyHeaders(conn, headers);
            if (payload != null) {
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(payload.length);
                OutputStream os = conn.getOutputStream();
                os.write(payload);
                os.flush();
                os.close();
            }
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String body = in == null ? "" : readAll(in);
            closeQuietly(in);
            if (failOnError && code >= 400) {
                // quota(body)：有些上游把"额度耗尽"放在 400 的响应体里（insufficient_quota），
                // 只看状态码会把它当普通失败记进熔断——而它本质是"现在别来"。
                // upstreamError() 一直有这个检查，主路径漏了，这里补齐。
                if (throttled(code) || code == 401 || code == 402 || code == 403 || quota(body)) {
                    throw new Throttled(code, retryAfterMs(conn, defaultBackoffMs(code)),
                            kindOf(code, body), "HTTP " + code + " " + shorten(body));
                }
                // 4xx（除上面几类）多半是客户端请求本身的问题：同一个坏请求换哪个账号都一样，
                // 不该按"这个通道坏了"记失败。用 InvalidRequest 单独标记，让上层区别对待。
                if (code >= 400 && code < 500) {
                    throw new InvalidRequest(code, "HTTP " + code + " " + shorten(body));
                }
                throw new IOException("HTTP " + code + " " + shorten(body));
            }
            // 有的上游"出问题"也给 200：错误信息在 body 里（Grok 的版本过旧就是这个形态），
            // 只看状态码会把它当成功、然后给客户端一个空响应。
            if (code == 200 && looksLikeErrorBody(body)) {
                throw new IOException("上游 200 但返回错误体：" + shorten(body));
            }
            clean = true;
            return new Resp(code, body);
        } finally {
            // clean 才回池；没读完就 disconnect，避免半截数据污染池里的连接
            if (!clean) {
                try {
                    conn.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * 流式 POST（SSE），逐行回调。
     * 读到 EOF 正常结束 → 连接回池；sink 中途抛异常（竞速输家等）→ 丢弃连接。
     * 4xx 时把错误体读完再抛（和原来 postStream 行为一致）。
     *
     * @param firstTokenMs 首字节看门狗：这么久还没吐出第一行（或判完错），就掐线抛异常。
     *                     0 表示不设。原来读超时 300 秒，一个 hang 住的上游能把
     *                     failover 拖到分钟级；现在 45 秒没首字节就换下一个候选。
     * @param cancel       竞速取消句柄（可 null）：winner 决出后会被从外部掐线。
     */
    public static void stream(String url, Map<String, String> headers, byte[] payload,
                              int connectMs, int readMs, long firstTokenMs, LineSink sink,
                              RaceCancels.Handle cancel) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        if (cancel != null) {
            cancel.conn = conn;
            if (cancel.cancelled) {
                // 还没建连就已经判输了：直接掐掉，按 RaceLost 处理（不记失败）
                try {
                    conn.disconnect();
                } catch (Exception ignored) {
                }
                throw new RaceLost("race already lost");
            }
        }
        boolean clean = false;
        final java.util.concurrent.atomic.AtomicBoolean firstArrived =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.ScheduledFuture<?> watchdog = null;
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(connectMs);
            conn.setReadTimeout(readMs);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "text/event-stream, application/json");
            conn.setRequestProperty("User-Agent", "CLIProxyAPI-Android/1.0");
            applyHeaders(conn, headers);
            if (firstTokenMs > 0) {
                watchdog = Threads.BG.schedule(() -> {
                    if (!firstArrived.get()) {
                        try {
                            conn.disconnect();
                        } catch (Exception ignored) {
                        }
                    }
                }, firstTokenMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
            conn.setFixedLengthStreamingMode(payload.length);
            OutputStream os = conn.getOutputStream();
            os.write(payload);
            os.flush();
            os.close();
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (code >= 400) {
                String errBody = in == null ? "" : readAll(in);
                closeQuietly(in);
                firstArrived.set(true);   // 错也是"有回音"，别让看门狗多事
                clean = true;   // 错误体读完了，连接干净
                if (throttled(code) || code == 401 || code == 402 || code == 403
                        || quota(errBody) || channel(errBody)) {
                    throw new Throttled(code, retryAfterMs(conn, defaultBackoffMs(code)),
                            kindOf(code, errBody), "upstream HTTP " + code + " " + shorten(errBody));
                }
                if (code >= 400 && code < 500) {
                    throw new InvalidRequest(code, "upstream HTTP " + code + " " + shorten(errBody));
                }
                throw new IOException("upstream HTTP " + code + " " + shorten(errBody));
            }
            if (in == null) throw new IOException("empty upstream response");
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 16384);
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    if (firstArrived.compareAndSet(false, true) && watchdog != null) {
                        watchdog.cancel(false);   // 首字节到了，看门狗下班
                    }
                    sink.line(line);
                }
            } finally {
                try {
                    r.close();
                } catch (Exception ignored) {
                }
            }
            clean = true;   // 读到 EOF，连接干净
        } finally {
            if (watchdog != null) watchdog.cancel(false);
            if (!clean) {
                try {
                    conn.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /* ------------------------------------------------------- 常用快捷方法 */

    public static String get(String url, Map<String, String> headers) throws Exception {
        return call("GET", url, headers, null, null, 20000, 60000, true).body;
    }

    public static String postForm(String url, Map<String, String> headers, String form) throws Exception {
        byte[] payload = form.getBytes(StandardCharsets.UTF_8);
        return call("POST", url, headers, payload,
                "application/x-www-form-urlencoded", 20000, 60000, true).body;
    }

    /** 4xx 也返回 body（OAuth 轮询、签到业务码用）。 */
    public static String postFormAllowingError(String url, Map<String, String> headers, String form)
            throws Exception {
        byte[] payload = form.getBytes(StandardCharsets.UTF_8);
        return call("POST", url, headers, payload,
                "application/x-www-form-urlencoded", 20000, 60000, false).body;
    }

    public static String postJson(String url, Map<String, String> headers, byte[] jsonBytes)
            throws Exception {
        return call("POST", url, headers, jsonBytes, "application/json", 20000, 60000, true).body;
    }

    /** 4xx 也返回 {code, body}（调用方自己按业务码判断）。 */
    public static Resp postJsonAllowingError(String url, Map<String, String> headers, byte[] jsonBytes)
            throws Exception {
        return call("POST", url, headers, jsonBytes, "application/json", 20000, 60000, false);
    }
}
