package com.cpa.gateway.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Concrete upstream channels. Each one renders a canonical request into its own wire format. */
public final class Upstreams {

    private Upstreams() {}

    public static Proto.Upstream create(Config.Provider p) {
        if (Config.TYPE_ANTHROPIC.equals(p.type)) return new AnthropicUp(p);
        if (Config.TYPE_GEMINI.equals(p.type)) return new GeminiUp(p);
        if (Config.TYPE_WORKBUDDY.equals(p.type)) return new WorkBuddy.Up(p);
        if (Config.TYPE_CODEX.equals(p.type)) return new Codex.Up(p);
        if (Config.TYPE_CLAUDE.equals(p.type)) return new Claude.Up(p);
        if (Config.TYPE_LOOMY.equals(p.type)) return new Loomy.Up(p);
        if (Config.TYPE_TRAE.equals(p.type)) return new Trae.Up(p);
        if (Config.TYPE_ANTIGRAVITY.equals(p.type)) return new Antigravity.Up(p);
        if (Config.TYPE_KIMI.equals(p.type)) return new Kimi.Up(p);
        if (Config.TYPE_DEEPSEEK.equals(p.type)) return new DeepSeek.Up(p);
        if (Config.TYPE_DOUBAO.equals(p.type)) return new Doubao.Up(p);
        if (Config.TYPE_XAI.equals(p.type)) return new Xai.Up(p);
        if (Config.TYPE_KILO.equals(p.type)) return new Kilo.Up(p);
        if (Config.TYPE_OVH.equals(p.type)) return new Ovh.Up(p);
        if (Config.TYPE_CLINE.equals(p.type)) return new Cline.Up(p);
        return new OpenAiUp(p);
    }

    /* ------------------------------------------------------------- http utils */

    interface LineSink {
        void line(String s) throws Exception;
    }

    /** Raw-line capture used when bringing up a new vendor (see /v1/debug/raw-stream). */
    static final java.util.List<String> RAW = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private static volatile int rawLimit = 0;

    public static void startCapture(int lines) {
        RAW.clear();
        rawLimit = lines;
    }

    public static java.util.List<String> stopCapture() {
        rawLimit = 0;
        java.util.List<String> copy = new java.util.ArrayList<>(RAW);
        RAW.clear();
        return copy;
    }

    /**
     * 最近一次发往上游的**完整请求体**（含 URL），用于排查"网关是否改了请求"。
     *
     * 为什么需要：用户反馈"同一个模型，走本网关会降智，官方客户端不会"。
     * 这类问题只能靠**逐字段对比最后发出去的请求**才能定位——
     * 光看代码会漏掉拼接顺序、字段缺失、注入等细节。
     *
     * 只保留最近 N 条（环形），内存有界；只读记录，不改任何请求内容。
     */
    private static final int CAPTURE_MAX = 12;
    static final java.util.List<String> CAPTURED =
            java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
    private static volatile boolean CAPTURE_ON = false;

    public static void captureOn(boolean on) {
        CAPTURE_ON = on;
        if (!on) CAPTURED.clear();
    }

    public static boolean captureEnabled() {
        return CAPTURE_ON;
    }

    public static java.util.List<String> capturedSnapshot() {
        synchronized (CAPTURED) {
            return new java.util.ArrayList<String>(CAPTURED);
        }
    }

    static void capture(String url, JSONObject body) {
        if (!CAPTURE_ON) return;
        try {
            // 记录里**不能有换行**：logcat 会把带 \n 的一条日志拆成多条，
            // 分段打印时就会丢内容（真机上就是这么丢了 body 开头）。
            // 用 " |BODY| " 做分隔符。
            String rec = "URL " + url + " |BODY| " + body.toString();
            synchronized (CAPTURED) {
                CAPTURED.add(rec);
                while (CAPTURED.size() > CAPTURE_MAX) CAPTURED.remove(0);
            }
            // 同时写日志：调试端点的回读要 API Key，而要从设备外取到明文 Key 很麻烦
            // （配置里是密文）。写进 logcat 就能直接读——抓取只在调试包打开，不影响发布包。
            // 分段打：logcat 单行约 4KB，一条长 body 会被截断成"看不全"。
            Log.i("capture URL " + url);
            int step = 1500;
            for (int off = 0; off < rec.length(); off += step) {
                Log.i("capture[" + (off / step) + "] " + rec.substring(off, Math.min(rec.length(), off + step)));
            }
        } catch (Throwable ignored) {
        }
    }

    static void postStream(String url, Map<String, String> headers, JSONObject body, LineSink sink) throws Exception {
        capture(url, body);
        // 走统一 Http 层：连接复用 + 首字节看门狗 + 竞速真取消。
        // 建连超时 20s→10s：移动网络下 10 秒连不上基本就是上游挂了，别拖。
        // 首 token 看门狗分档：竞速 30 秒（拼的就是首字速度，30 秒没一个候选吐字
        // 不如直接判负）；普通请求 90 秒——推理模型思考久，45 秒会误杀正常流，
        // 90 秒比原来的 300 秒还是快 3 倍。
        RaceCancels.Handle cancel = RaceCancels.current();
        long firstTokenMs = cancel != null ? 30000L : 90000L;
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        Http.stream(url, headers, payload, 10000, 300000, firstTokenMs, line -> {
            if (rawLimit > 0 && RAW.size() < rawLimit) RAW.add(shorten(line));
            sink.line(line);
        }, cancel);
    }

    static String shorten(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').trim();
        return t.length() > 400 ? t.substring(0, 400) + "..." : t;
    }

    static String trimSlash(String s) {
        String b = s == null ? "" : s.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }

    /** Normalises an OpenAI-compatible base so that "/chat/completions" always resolves. */
    static String openAiBase(String base) {
        String b = trimSlash(base);
        if (b.isEmpty()) return "";
        if (b.endsWith("/chat/completions")) b = b.substring(0, b.length() - "/chat/completions".length());
        if (b.endsWith("/v1")) return b;
        return b + "/v1";
    }

    static String url(String base, String path) {
        String b = trimSlash(base);
        String p = path;
        while (p.startsWith("/")) p = p.substring(1);
        if (b.endsWith("/v1") && (p.startsWith("v1/") || p.equals("v1"))) b = b.substring(0, b.length() - 3);
        if (b.endsWith("/v1beta") && p.startsWith("v1beta/")) b = b.substring(0, b.length() - 7);
        return b + "/" + p;
    }

    static JSONObject sseData(String line) {
        if (line == null) return null;
        String t = line.trim();
        if (t.isEmpty() || t.startsWith(":")) return null;
        if (!t.startsWith("data:")) return null;
        String data = t.substring(5).trim();
        if (data.isEmpty() || "[DONE]".equals(data)) return null;
        try {
            return new JSONObject(data);
        } catch (Exception e) {
            return null;
        }
    }

    static void applyUsage(Proto.Usage u, JSONObject usage, String inKey, String outKey, String cacheKey) {
        if (usage == null) return;
        Integer i = Proto.optInt(usage, inKey);
        if (i != null) u.input = i;
        Integer o = Proto.optInt(usage, outKey);
        if (o != null) u.output = o;
        if (cacheKey != null) {
            Integer c = Proto.optInt(usage, cacheKey);
            if (c != null) u.cached = c;
        }
    }


    /* ------------------------------------------------------------ model listing */

    /** Pulls the upstream model list so the UI can offer one-tap configuration. */
    public static List<String> fetchModels(String type, String baseUrl, String apiKey) throws Exception {
        // relay stations differ: some list models under /v1/models, some under /models
        Exception firstFailure = null;
        for (String candidate : modelUrlCandidates(type, baseUrl)) {
            try {
                return fetchModelsFrom(type, candidate, apiKey);
            } catch (Exception e) {
                if (firstFailure == null) firstFailure = e;
            }
        }
        throw firstFailure == null ? new IOException("no model endpoint") : firstFailure;
    }

    static List<String> modelUrlCandidates(String type, String baseUrl) {
        List<String> urls = new ArrayList<>();
        if (Config.TYPE_ANTHROPIC.equals(type)) {
            urls.add(url(baseUrl, "v1/models"));
            return urls;
        }
        if (Config.TYPE_GEMINI.equals(type)) {
            String b = trimSlash(baseUrl);
            if (b.isEmpty()) b = "https://generativelanguage.googleapis.com";
            urls.add(url(b, "v1beta/models"));
            return urls;
        }
        String b = trimSlash(baseUrl);
        urls.add(url(openAiBase(b), "models"));
        if (!b.isEmpty()) {
            urls.add(url(b, "models"));
            int marker = b.lastIndexOf("/openai");
            if (marker > 0) urls.add(b.substring(0, marker) + "/models");
        }
        return urls;
    }

    static List<String> fetchModelsFrom(String type, String url, String apiKey) throws Exception {
        List<String> out = new ArrayList<>();
        String url2 = url;
        Map<String, String> headers = new HashMap<>();
        if (Config.TYPE_ANTHROPIC.equals(type)) {
            if (apiKey != null && !apiKey.isEmpty()) {
                headers.put("x-api-key", apiKey);
                headers.put("anthropic-version", "2023-06-01");
            }
        } else if (Config.TYPE_GEMINI.equals(type)) {
            if (apiKey != null && !apiKey.isEmpty()) url2 = url2 + "?key=" + apiKey;
        } else {
            if (apiKey != null && !apiKey.isEmpty()) headers.put("Authorization", "Bearer " + apiKey);
        }
        url = url2;
        String body = get(url, headers);
        // three shapes in the wild: {data:[…]}, {models:[…]} and a bare top-level array
        JSONArray arr = null;
        String trimmed = body == null ? "" : body.trim();
        if (trimmed.startsWith("[")) {
            arr = new JSONArray(trimmed);
        } else {
            JSONObject o = new JSONObject(trimmed);
            arr = o.optJSONArray("data");
            if (arr == null) arr = o.optJSONArray("models");
        }
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                Object item = arr.opt(i);
                String id;
                if (item instanceof JSONObject) {
                    JSONObject m = (JSONObject) item;
                    id = Proto.optString(m, "id", Proto.optString(m, "name",
                            Proto.optString(m, "model", "")));
                } else {
                    id = String.valueOf(item);
                }
                if (id.startsWith("models/")) id = id.substring("models/".length());
                if (!id.isEmpty() && !"null".equals(id) && !out.contains(id)) out.add(id);
            }
        }
        if (out.isEmpty()) throw new IOException("upstream returned no models");
        return out;
    }


    /** Provider-aware model listing: WorkBuddy uses its own credential blob. */
    public static List<String> fetchModels(Config.Provider p) throws Exception {
        if (Config.TYPE_KILO.equals(p.type)) return Kilo.fetchModels();
        if (Config.TYPE_OVH.equals(p.type)) return Ovh.fetchModels();
        if (Config.TYPE_WORKBUDDY.equals(p.type)) return WorkBuddy.fetchModels(p);
        if (Config.TYPE_CODEX.equals(p.type)) return Codex.fetchModels(p);
        if (Config.TYPE_CLAUDE.equals(p.type)) return Claude.fetchModels(p);
        if (Config.TYPE_LOOMY.equals(p.type)) return Loomy.fetchModels(p);
        if (Config.TYPE_KIMI.equals(p.type)) {
            try {
                java.util.List<String> km = Kimi.fetchModels(p);
                if (!km.isEmpty()) return km;
            } catch (Exception e) {
                Log.w("kimi 模型刷新失败，用本地清单：" + e);
            }
            return new ArrayList<>(p.models);
        }
        if (Config.TYPE_DEEPSEEK.equals(p.type)) return DeepSeek.fetchModels(p);
        if (Config.TYPE_DOUBAO.equals(p.type)) return Doubao.fetchModels(p);
        if (Config.TYPE_CLINE.equals(p.type)) {
            List<String> cm = Cline.fetchModels();
            return !cm.isEmpty() ? cm : new ArrayList<>(p.models);
        }
        if (Config.TYPE_XAI.equals(p.type)) return Xai.fetchModels(p);
        if (Config.TYPE_ANTIGRAVITY.equals(p.type)) return new ArrayList<>(p.models);
        if (Config.TYPE_TRAE.equals(p.type)) {
            // SOLO 池的 config_name 目录来自上游 get_detail_param（先拉真实目录，失败才退回已有清单）
            List<String> fetched = Trae.fetchModels(p);
            if (!fetched.isEmpty()) return fetched;
            return new ArrayList<>(p.models);
        }
        return fetchModels(p.type, p.baseUrl, p.apiKey);
    }

    static String get(String url, Map<String, String> headers) throws Exception {
        return Http.get(url, headers);
    }

    /**
     * 某个能力参数没能带到上游时记一条日志。
     *
     * 为什么必须记：这几个参数**丢掉的后果是"模型变笨"而不是报错**，
     * 完全静默的话，用户永远只能得出"这个网关不行"的结论，
     * 而实际上只是跨协议这一条路不支持某个字段。日志里带上源协议，
     * 一眼就能看出是"Anthropic 的 thinking 发给了 OpenAI 上游"这类情况。
     *
     * 只在每个请求第一次出现时写一行，不刷屏（同一字段名去重）。
     */
    static void note(String field, Proto.Req req) {
        if (req == null || field == null) return;
        if (!req.droppedParams.contains(field)) {
            req.droppedParams.add(field);
            Log.i("能力参数未透传到上游（跨协议无等价字段）：" + field + "，来源协议=" + req.protocol);
        }
    }

    /** Builds an OpenAI chat-completions body from a canonical request (shared by several upstreams). */
    /**
     * 同协议出口把入口里未建模的字段并回去（不覆盖已经明确构造的字段）。
     * 跨协议不合并：字段语义不同，硬塞只会 400。
     */
    private static void mergeExtra(JSONObject dst, JSONObject extra) {
        if (dst == null || extra == null) return;
        java.util.Iterator<String> it = extra.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (dst.has(k)) continue;
            try {
                dst.put(k, extra.opt(k));
            } catch (Exception ignored) {
            }
        }
    }

    static JSONObject openAiBody(String model, Proto.Req req, boolean stream) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", model);
        JSONArray msgs = new JSONArray();
        if (!req.systemText().isEmpty()) {
            msgs.put(new JSONObject().put("role", "system").put("content", req.systemText()));
        }
        for (Proto.Msg m : req.messages) appendMessages(msgs, m);
        body.put("messages", msgs);
        if (req.maxTokens != null) body.put("max_tokens", req.maxTokens.intValue());
        if (req.temperature != null) body.put("temperature", req.temperature.doubleValue());
        if (req.topP != null) body.put("top_p", req.topP.doubleValue());
        /* 只回给 OpenAI 入口的 top_k：OpenAI 官方接口不认这个字段，
           跨协议硬塞会把本来正常的请求变成 400。 */
        if (req.topK != null && "openai".equals(req.protocol)) {
            body.put("top_k", req.topK.intValue());
        }
        if (!req.stop.isEmpty()) body.put("stop", new JSONArray(req.stop));
        if (!req.tools.isEmpty()) {
            JSONArray tools = new JSONArray();
            for (Proto.Tool t : req.tools) {
                JSONObject fn = new JSONObject();
                fn.put("name", t.name);
                fn.put("description", t.description == null ? "" : t.description);
                fn.put("parameters", t.schema == null ? new JSONObject().put("type", "object") : t.schema);
                tools.put(new JSONObject().put("type", "function").put("function", fn));
            }
            body.put("tools", tools);
            /* tool_choice 只在有工具时才有意义；Anthropic 客户端发来的形状在这里翻成 OpenAI 形状。
               翻不出来（比如 "none" 之外的无对应语义）就不传，并由 note() 记一条日志。 */
            Object tc = XParams.toolChoice(req.protocol, req.toolChoice, "openai");
            if (tc != null) body.put("tool_choice", tc);
            else if (req.toolChoice != null) note("tool_choice", req);
            if (req.parallelToolCalls != null) body.put("parallel_tool_calls", req.parallelToolCalls.booleanValue());
        }
        /* 结构化输出：客户端要 JSON 就必须让它拿到 JSON，否则解析必炸。
           response_format 是 OpenAI 家族的原生字段，同协议时原样透传。 */
        Object rf = XParams.responseFormat(req.protocol, req.responseFormat, "openai");
        if (rf != null) body.put("response_format", rf);
        else if (req.responseFormat != null) note("response_format", req);
        /* 推理档位：o 系列 / GPT-5 靠它决定想多久。不传等于按上游默认（常常偏低）。 */
        if (req.reasoningEffort != null && !req.reasoningEffort.isEmpty()) {
            body.put("reasoning_effort", req.reasoningEffort);
        }
        Object think = XParams.thinking(req.protocol, req.thinkingParams, "openai");
        if (think != null) body.put("reasoning", think);
        else if (req.thinkingParams != null && req.reasoningEffort == null) {
            /* 跨协议且没有 effort 可用：不猜档位（见 XParams），只记日志。 */
            note("thinking", req);
        }
        if (req.presencePenalty != null) body.put("presence_penalty", req.presencePenalty.doubleValue());
        if (req.frequencyPenalty != null) body.put("frequency_penalty", req.frequencyPenalty.doubleValue());
        if (req.seed != null) body.put("seed", req.seed.intValue());
        body.put("stream", stream);
        if (stream) {
            // 不加这个，OpenAI 兼容上游按标准不会在流式里回 usage chunk，
            // 用量统计的后两列就永远是 0（最终 chunk 的 choices 为空、usage 独立出现）。
            try {
                body.put("stream_options", new JSONObject().put("include_usage", true));
            } catch (Exception ignored) {
            }
        }
        if ("openai".equals(req.protocol)) mergeExtra(body, req.extraBody);
        return body;
    }

    /** Form-encoded POST (used by the WorkBuddy token endpoint). */
    /**
     * 与 postForm 相同，但 4xx 也返回响应体。
     * OAuth 设备码流程里 "authorization_pending" / "slow_down" 都是以 HTTP 400 上报的正常状态，
     * 用 postForm 会被当成异常抛出，导致轮询刚开始就中断。
     */
    static String postFormAllowingError(String url, Map<String, String> headers, String form) throws Exception {
        return Http.postFormAllowingError(url, headers, form);
    }

    static String postForm(String url, Map<String, String> headers, String form) throws Exception {
        return Http.postForm(url, headers, form);
    }
    /**
     * 与 postJsonAllowingError 相同，但直接发送任意 JSON 文本（用于上报体是数组的场景）。
     * 行为事件上报 /v2/report 的 body 是 [{...}]，用 JSONObject 包装会被上游判为格式错误。
     * 实现已收进统一 Http 层，这里只做 {http, body} 信封组装。
     */
    /** 把 Http 层的 {code, body} 组装成原来 allowingError 系列返回的 {http, body} 信封。 */
    private static JSONObject jsonResp(Http.Resp r) throws Exception {
        JSONObject out = new JSONObject();
        out.put("http", r.code);
        JSONObject parsed;
        try {
            parsed = new JSONObject(r.body);
        } catch (Exception e) {
            parsed = new JSONObject();
            parsed.put("msg", shorten(r.body));
        }
        out.put("body", parsed);
        return out;
    }

    public static JSONObject postRawAllowingError(String url, Map<String, String> headers, String rawJson) throws Exception {
        byte[] payload = (rawJson == null ? "{}" : rawJson).getBytes(StandardCharsets.UTF_8);
        return jsonResp(Http.postJsonAllowingError(url, headers, payload));
    }

    public static JSONObject postJsonAllowingError(String url, Map<String, String> headers, JSONObject body) throws Exception {
        byte[] payload = (body == null ? "{}" : body.toString()).getBytes(StandardCharsets.UTF_8);
        return jsonResp(Http.postJsonAllowingError(url, headers, payload));
    }
    /** JSON POST returning the raw body (used by the WorkBuddy login flow). */
    public static String postJson(String url, Map<String, String> headers, JSONObject body) throws Exception {
        byte[] payload = (body == null ? "{}" : body.toString()).getBytes(StandardCharsets.UTF_8);
        return Http.postJson(url, headers, payload);
    }

    public static String shortenPublic(String s) {
        return shorten(s);
    }


    /** GET with explicit headers (used by the WorkBuddy config endpoint). */
    public static String getWithHeaders(String url, Map<String, String> headers) throws Exception {
        return get(url, headers);
    }

    /* --------------------------------------------------- OpenAI-compatible up */

    static final class OpenAiUp implements Proto.Upstream {
        private final Config.Provider p;

        OpenAiUp(Config.Provider p) {
            this.p = p;
        }

        @Override
        public void chat(String model, Proto.Req req, Proto.Sink sink) throws Exception {
            String endpoint = url(openAiBase(p.baseUrl), "chat/completions");
            Map<String, String> headers = new HashMap<>();
            if (!p.apiKey.isEmpty()) headers.put("Authorization", "Bearer " + p.apiKey);
            headers.putAll(webSessionHeaders(p));
            /* 响应保真（4.50.0）：同样是 OpenAI 形状，客户端要 n>1 / logprobs / 音频时
               不再把响应拆成 canonical 的"一段正文"，而是原样回传。请求体还是同一份，
               选路、限额、换号、用量统计都在外层照常工作。 */
            if (rawOpenAiIfWanted(req, endpoint, headers, model, sink)) return;
            openAiStream(endpoint, headers, openAiBody(model, req, true), sink);
        }
    }

    /**
     * 已经是 OpenAI Chat 形状的渠道共用入口：需要保真就原样透传，返回 true。
     *
     * 4.50.0 只接了通用 OpenAI 兼容渠道；4.50.1 把同样用
     * `openAiBody` + `openAiStream` 的 xAI / Kilo / OVH / Loomy 也接上。
     * 它们的请求体本来就走同一套渲染，响应也是 OpenAI SSE，接进来没有额外假设。
     */
    static boolean rawOpenAiIfWanted(Proto.Req req, String endpoint,
                                     Map<String, String> headers, String model,
                                     Proto.Sink sink) throws Exception {
        if (!req.rawResponse || !"openai".equals(req.protocol)) return false;
        if (req.stream) {
            rawOpenAiStream(endpoint, headers, openAiBody(model, req, true), sink);
        } else {
            rawOpenAiCall(endpoint, headers, openAiBody(model, req, false),
                    req.model == null || req.model.isEmpty() ? model : req.model, sink);
        }
        return true;
    }

    /* ---------------------------------------------------- 响应保真（4.50.0） */

    /**
     * 同协议流式原样透传。
     *
     * 与 {@link #openAiStream} 的区别只在"解读"：这里不把 delta 拆成 canonical 的
     * text/thinking/toolCall，而是把每个 data 块交给客户端。多 choice、logprobs、
     * system_fingerprint、音频分片这些字段因此完整保留。
     *
     * 仍然做的三件事（缺了就是后退）：
     *  · 上游 200 里包着的 error 照样识别并抛 Throttled；
     *  · 没等到 finish_reason / [DONE] 就断流 → 抛 IOException，让上层决定换号；
     *  · usage / responseId 照样上报，用量统计不断。
     */
    static void rawOpenAiStream(String endpoint, Map<String, String> headers,
                                JSONObject body, Proto.Sink sink) throws Exception {
        final Proto.Usage usage = new Proto.Usage();
        final String[] finish = {""};
        final boolean[] doneSeen = {false};
        final boolean[] doneSent = {false};
        final boolean[] idReported = {false};
        postStream(endpoint, headers, body, line -> {
            String raw = line == null ? "" : line.trim();
            if (raw.startsWith("{") && raw.contains("\"error\"")) {
                try {
                    JSONObject j = new JSONObject(raw);
                    if (j.opt("error") != null) raiseUpstreamBodyError(raw);
                } catch (org.json.JSONException notJson) {
                    // 不是 JSON 就当普通行
                }
            }
            if (raw.equals("data: [DONE]") || raw.equals("data:[DONE]")) {
                doneSeen[0] = true;
                if (!doneSent[0]) {
                    doneSent[0] = true;
                    sink.rawDone();
                }
                return;
            }
            JSONObject chunk = sseData(line);
            if (chunk == null) return;
            if (chunk.opt("error") != null) raiseUpstreamBodyError(chunk.toString());
            if (!idReported[0]) {
                String rid = Proto.optString(chunk, "id", "");
                if (!rid.isEmpty()) {
                    idReported[0] = true;
                    sink.responseId(rid);
                }
            }
            JSONObject u = chunk.optJSONObject("usage");
            if (u != null) {
                applyUsage(usage, u, "prompt_tokens", "completion_tokens", "cached_tokens");
                JSONObject details = u.optJSONObject("prompt_tokens_details");
                if (details != null) {
                    Integer c = Proto.optInt(details, "cached_tokens");
                    if (c != null) usage.cached = c;
                }
            }
            JSONArray choices = chunk.optJSONArray("choices");
            if (choices != null) {
                for (int i = 0; i < choices.length(); i++) {
                    JSONObject c = choices.optJSONObject(i);
                    String fr = c == null ? "" : Proto.optString(c, "finish_reason", "");
                    if (!fr.isEmpty() && !"null".equals(fr)) {
                        finish[0] = fr;
                        doneSeen[0] = true;
                    }
                }
            }
            sink.rawChunk(chunk);
        });
        if (!doneSeen[0]) {
            Log.w("上游流在 finish_reason/[DONE] 之前结束（保真模式），判定为连接中断");
            throw new java.io.IOException("upstream stream ended before finish_reason");
        }
        if (!doneSent[0]) {
            doneSent[0] = true;
            sink.rawDone();
        }
        sink.finish(Proto.canonicalStop(finish[0], false), usage);
    }

    /** 同协议非流式原样透传：一次完整响应，字段一个不动。 */
    static void rawOpenAiCall(String endpoint, Map<String, String> headers,
                              JSONObject body, String clientModel, Proto.Sink sink) throws Exception {
        /* 不用 postJson 的 60 秒读超时：非流式的长回答/推理本来就可能超过一分钟，
           超时会被上层当成通道故障换号，等于把一次正常请求白打掉。 */
        String resp = Http.call("POST", endpoint, headers,
                body.toString().getBytes(StandardCharsets.UTF_8), "application/json",
                10000, 300000, true).body;
        JSONObject j;
        try {
            j = new JSONObject(resp);
        } catch (Exception e) {
            throw new IllegalStateException("上游返回非 JSON：" + shorten(resp));
        }
        if (j.opt("error") != null) raiseUpstreamBodyError(resp);
        /* 与流式路径一致：客户端认的是它请求的名字（可能带账号前缀），
           不是上游内部的实际模型名。 */
        try {
            j.put("model", clientModel);
        } catch (Exception ignored) {
        }
        String rid = Proto.optString(j, "id", "");
        if (!rid.isEmpty()) sink.responseId(rid);
        Proto.Usage usage = new Proto.Usage();
        JSONObject u = j.optJSONObject("usage");
        if (u != null) {
            applyUsage(usage, u, "prompt_tokens", "completion_tokens", "cached_tokens");
            JSONObject details = u.optJSONObject("prompt_tokens_details");
            if (details != null) {
                Integer c = Proto.optInt(details, "cached_tokens");
                if (c != null) usage.cached = c;
            }
        }
        String finish = "";
        JSONArray choices = j.optJSONArray("choices");
        if (choices != null && choices.length() > 0) {
            JSONObject c0 = choices.optJSONObject(0);
            finish = c0 == null ? "" : Proto.optString(c0, "finish_reason", "");
        }
        sink.rawBody(j);
        sink.finish(Proto.canonicalStop(finish, false), usage);
    }

    /**
     * Relay stations that were added through the in-app browser login are authenticated by their own
     * session cookie (and possibly a token we scraped from local storage).
     */
    static Map<String, String> webSessionHeaders(Config.Provider p) {
        Map<String, String> out = new HashMap<>();
        if (p.credentials == null || p.credentials.isEmpty()) return out;
        try {
            JSONObject c = new JSONObject(p.credentials);
            String cookies = Proto.optString(c, "cookies", "");
            if (!cookies.isEmpty()) out.put("Cookie", cookies);
            String token = Proto.optString(c, "token", "");
            if (!token.isEmpty() && p.apiKey.isEmpty()) out.put("Authorization", "Bearer " + token);
        } catch (Exception ignored) {
        }
        return out;
    }

    /**
     * 200 响应体里包着的错误，到底是"限流/额度用尽"还是"通道真坏了"。
     *
     * 为什么需要：免费池（Kilo / OVH 这类）最常见的死法就是 HTTP 200 + 错误体，
     * 文案形形色色——额度耗尽、预算打满、速率超限、并发上限。这些以前一律抛
     * IllegalStateException，被上层记成**普通失败**，3 次就进熔断把通道误杀；
     * 而它们本质是"现在别来"，应该按限流处理（只退避、不计失败）。
     *
     * 返回非 null 表示应当按限流抛出（值是 kind："quota" 或 "rate"）；返回 null 表示照旧当普通错误。
     */
    static String throttleKindFromBody(String body) {
        if (body == null || body.isEmpty()) return null;
        String b = body.toLowerCase(java.util.Locale.US);
        // 额度/计费类：退避长一些
        String[] quotaWords = {
                "insufficient_quota", "quota exceeded", "quota_exceeded", "exceeded your quota",
                "out of credits", "no credits", "insufficient balance", "budget", "billing",
                "payment required", "free tier limit", "usage limit", "daily limit",
                "额度", "余额不足", "配额"
        };
        for (String w : quotaWords) {
            if (b.contains(w)) return "quota";
        }
        // 限流/并发类：退避短一些
        String[] rateWords = {
                "rate limit", "rate_limit", "ratelimit", "too many requests", "slow down",
                "concurrency", "temporarily unavailable", "try again later", "overloaded",
                "capacity", "限流", "请求过于频繁", "并发"
        };
        for (String w : rateWords) {
            if (b.contains(w)) return "rate";
        }
        return null;
    }

    /**
     * Anthropic 的 error.type 是**结构化**的，比文本匹配可靠，优先用它。
     *
     * 官方取值：rate_limit_error / overloaded_error / insufficient_quota /
     * api_error / authentication_error / invalid_request_error ...
     * 返回非 null 表示该按限流处理。
     */
    static String throttleKindFromAnthropicType(String type) {
        if (type == null || type.isEmpty()) return null;
        String t = type.toLowerCase(java.util.Locale.US);
        if (t.contains("quota") || t.contains("billing") || t.contains("payment")) return "quota";
        if (t.contains("rate_limit") || t.contains("overloaded") || t.contains("too_many")
                || t.contains("capacity")) return "rate";
        return null;
    }

    /** 按 kind 抛对应的限流异常；不是限流就抛普通异常。 */    /** 按 kind 抛对应的限流异常；不是限流就抛普通异常。 */
    private static void raiseUpstreamBodyError(String raw) throws Exception {
        String kind = throttleKindFromBody(raw);
        if (kind != null) {
            int code = "quota".equals(kind) ? 402 : 429;
            throw new Throttled(code, Http.defaultBackoffMs(code), kind, "上游额度/限流：" + shorten(raw));
        }
        throw new IllegalStateException("上游返回错误：" + shorten(raw));
    }

    /** Shared OpenAI-style SSE reader: OpenAI-compatible channels and Loomy both use it. */
    static void openAiStream(String endpoint, Map<String, String> headers, JSONObject body, Proto.Sink sink) throws Exception {
        final TreeMap<Integer, JSONObject> toolAcc = new TreeMap<>();
        final Proto.Usage usage = new Proto.Usage();
        final String[] finish = {""};
        final boolean[] wrote = {false};
        final boolean[] idReported = {false};      // 内容是否已经流给客户端（决定坏 tool_call 能不能重来）
        final boolean[] doneSeen = {false};         // 上游有没有明确结束（[DONE] 或 finish_reason）

        postStream(endpoint, headers, body, line -> {
            // HTTP 200 也可能是错误。两种形态都要挡：
            //   a) 裸 JSON 错误体（没有 SSE 前缀）：Grok 的 "version outdated" 就是这个样子；
            //   b) 错误塞在 SSE 的第一个 data 块里。
            String raw = line == null ? "" : line.trim();
            if (raw.equals("data: [DONE]") || raw.equals("data:[DONE]")) {
                doneSeen[0] = true;
            }
            if (raw.startsWith("{") && raw.contains("\"error\"")) {
                try {
                    JSONObject j = new JSONObject(raw);
                    if (j.opt("error") != null) {
                        raiseUpstreamBodyError(raw);   // 额度/限流走 Throttled，别当通道故障
                    }
                } catch (org.json.JSONException notJson) {
                    // 不是 JSON 就当普通行
                }
            }
            JSONObject chunk = sseData(line);
            if (chunk == null) return;
            if (chunk.opt("error") != null) {
                raiseUpstreamBodyError(chunk.toString());
            }
            /* 4.36.0：把上游的响应 id 报上去（OpenAI 兼容流每个 chunk 都带它）。
               只报第一个非空的，后面重复的不必再传。 */
            if (!idReported[0]) {
                String rid = Proto.optString(chunk, "id", "");
                if (!rid.isEmpty()) {
                    idReported[0] = true;
                    sink.responseId(rid);
                }
            }
            JSONObject u = chunk.optJSONObject("usage");
            if (u != null) {
                applyUsage(usage, u, "prompt_tokens", "completion_tokens", "cached_tokens");
                JSONObject details = u.optJSONObject("prompt_tokens_details");
                if (details != null) {
                    Integer c = Proto.optInt(details, "cached_tokens");
                    if (c != null) usage.cached = c;
                }
            }
            JSONArray choices = chunk.optJSONArray("choices");
            if (choices == null || choices.length() == 0) return;
            JSONObject c0 = choices.optJSONObject(0);
            if (c0 == null) return;
            JSONObject d = c0.optJSONObject("delta");
            if (d != null) {
                String content = Proto.optString(d, "content", "");
                if (!content.isEmpty()) {
                    wrote[0] = true;
                    sink.text(content);
                }
                String rc = Proto.optString(d, "reasoning_content", Proto.optString(d, "reasoning", ""));
                if (!rc.isEmpty()) sink.thinking(rc);
                JSONArray tcs = d.optJSONArray("tool_calls");
                if (tcs != null) {
                    for (int i = 0; i < tcs.length(); i++) {
                        JSONObject tc = tcs.optJSONObject(i);
                        if (tc == null) continue;
                        Integer idxObj = Proto.optInt(tc, "index");
                        int idx = idxObj == null ? 0 : idxObj.intValue();
                        JSONObject acc = toolAcc.get(idx);
                        if (acc == null) {
                            acc = new JSONObject();
                            acc.put("id", "");
                            acc.put("name", "");
                            acc.put("args", "");
                            toolAcc.put(idx, acc);
                        }
                        String id = Proto.optString(tc, "id", "");
                        if (!id.isEmpty()) acc.put("id", id);
                        JSONObject fn = tc.optJSONObject("function");
                        if (fn != null) {
                            String name = Proto.optString(fn, "name", "");
                            if (!name.isEmpty()) acc.put("name", name);
                            String args = Proto.optString(fn, "arguments", "");
                            if (!args.isEmpty()) acc.put("args", Proto.optString(acc, "args", "") + args);
                        }
                    }
                }
            }
            String fr = Proto.optString(c0, "finish_reason", "");
            if (!fr.isEmpty() && !"null".equals(fr)) {
                finish[0] = fr;
                doneSeen[0] = true;
            }
        });

        /* 没有 finish_reason、也没有 [DONE]：上游是被掐断的。
           以前这里会当成正常完成，客户端收到一段没有结束标记的半截回答；
           现在抛出去，让上层决定"没出内容就换号 / 出了内容就发错误帧"。 */
        if (!doneSeen[0]) {
            Log.w("上游流在 finish_reason/[DONE] 之前结束，判定为连接中断");
            throw new java.io.IOException("upstream stream ended before finish_reason");
        }

        for (JSONObject acc : toolAcc.values()) {
            String id = Proto.optString(acc, "id", "");
            if (id.isEmpty()) id = "call_" + Math.abs(System.nanoTime() % 1000000);
            String name = Proto.optString(acc, "name", "");
            String raw = Proto.optString(acc, "args", "");

            // 坏参数绝不放给客户端——客户端解析不了就整轮崩
            String fixed = ToolFix.repairArgs(raw);
            if (fixed == null) {
                if (wrote[0]) {
                    // 内容已经出去了，这一轮重来不了：丢掉这个 tool_call，只记日志
                    Log.w("工具参数坏且已开始输出，丢弃这次 tool_call（" + name + "）："
                            + shortenPublic(raw));
                    continue;
                }
                throw new ToolFix.BadToolCall(name, raw);   // 还没输出 → 让上层换账号重试
            }
            if (name.isEmpty()) name = ToolFix.nameFromArgs(fixed);
            sink.toolCall(id, name, fixed);
        }
        sink.finish(Proto.canonicalStop(finish[0], !toolAcc.isEmpty()), usage);
    }

    /** One canonical message can hold several tool results; each must become its own tool message. */
    static void appendMessages(JSONArray msgs, Proto.Msg m) throws Exception {
        if ("tool".equals(m.role)) {
            JSONArray results = new JSONArray();
            for (Proto.Part part : m.parts) {
                if (Proto.Part.TOOL_RESULT.equals(part.type)) results.put(part);
            }
            if (results.length() > 1) {
                for (int i = 0; i < results.length(); i++) {
                    Proto.Msg single = new Proto.Msg("tool");
                    Proto.Part part = (Proto.Part) results.opt(i);
                    single.add(part);
                    msgs.put(openAiMessage(single));
                }
                return;
            }
        }
        msgs.put(openAiMessage(m));
    }

    /**
     * Tool calls and their results must pair up exactly, otherwise upstreams reject the request
     * (WorkBuddy answers code 11148 "tool calls and tool results do not match").
     * Orphan tool messages are dropped and dangling tool_calls are stripped.
     */
    /** 把消息序列压成一行"形状"：s=system u=user A=assistant t=tool，(n)=该条带几个 tool_calls。 */
    static String shapeOf(JSONArray msgs) {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; msgs != null && k < msgs.length(); k++) {
            JSONObject mk = msgs.optJSONObject(k);
            if (mk == null) continue;
            String rk = Proto.optString(mk, "role", "?");
            if (sb.length() > 0) sb.append(',');
            if ("assistant".equals(rk)) {
                sb.append('A');
                JSONArray ck = mk.optJSONArray("tool_calls");
                if (ck != null && ck.length() > 0) sb.append('(').append(ck.length()).append(')');
            } else if ("tool".equals(rk)) {
                sb.append('t');
            } else {
                sb.append(rk.isEmpty() ? "?" : rk.substring(0, 1));
            }
        }
        return sb.toString();
    }

    static void normalizeToolPairs(JSONArray msgs) {
        try {
            // 第 0 遍：把**空的 tool_call_id 按顺序回填**。
            //
            // 实测（抓到的真实请求）：客户端（代鱼）执行完工具后，返回的 tool 消息里
            // tool_call_id 字段是空字符串 ""：
            //   assistant.tool_calls: id=call_00_GIgyc..., call_01_nMeA3...
            //   tool.tool_call_id:    ""            ← 全空
            // 而结果是**按调用顺序、紧挨着**放在对应 assistant 后面的。
            //
            // 原来的逻辑看到 id 为空就直接判"孤立"并删除：
            //   删 → 模型看不到任何结果 → 一遍遍重复同样的调用（历史无限膨胀）
            //   不删 → 上游校验不过，回 400 {"code":11148,"tool calls and tool results do not match"}
            // 两条路都坏。真正的修法是让 id 真的对上：按顺序回填。
            //
            // 只回填"原本为空"的，不覆盖客户端已经给好的 id。
            for (int i = 0; i < msgs.length(); i++) {
                JSONObject m = msgs.optJSONObject(i);
                if (m == null || !"assistant".equals(Proto.optString(m, "role", ""))) continue;
                JSONArray calls = m.optJSONArray("tool_calls");
                if (calls == null || calls.length() == 0) continue;

                int next = 0;
                for (int k = i + 1; k < msgs.length() && next < calls.length(); k++) {
                    JSONObject t = msgs.optJSONObject(k);
                    if (t == null) continue;
                    String role = Proto.optString(t, "role", "");
                    if ("assistant".equals(role)) break;      // 进入下一轮
                    if (!"tool".equals(role)) continue;       // user 等消息跳过
                    if (!Proto.optString(t, "tool_call_id", "").isEmpty()) continue;  // 已有 id，不动
                    JSONObject call = calls.optJSONObject(next++);
                    String cid = call == null ? "" : Proto.optString(call, "id", "");
                    if (cid.isEmpty()) continue;
                    t.put("tool_call_id", cid);
                    Log.i("工具配对整理：按顺序回填空 tool_call_id → " + cid);
                }
            }
            for (int i = msgs.length() - 1; i >= 0; i--) {
                JSONObject m = msgs.optJSONObject(i);
                if (m == null) continue;
                String role = Proto.optString(m, "role", "");
                if (!"tool".equals(role)) continue;
                String id = Proto.optString(m, "tool_call_id", "");
                boolean matched = false;
                if (!id.isEmpty()) {
                    for (int k = i - 1; k >= 0; k--) {
                        JSONObject prev = msgs.optJSONObject(k);
                        if (prev == null) continue;
                        if (!"assistant".equals(Proto.optString(prev, "role", ""))) {
                            if (!"tool".equals(Proto.optString(prev, "role", ""))) break;
                            continue;
                        }
                        JSONArray calls = prev.optJSONArray("tool_calls");
                        for (int c = 0; calls != null && c < calls.length(); c++) {
                            JSONObject call = calls.optJSONObject(c);
                            if (call != null && id.equals(Proto.optString(call, "id", ""))) {
                                matched = true;
                                break;
                            }
                        }
                        break;
                    }
                }
                if (!matched) {
                    // **不删**。
                    //
                    // 原文是 msgs.remove(i)，理由是这个 tool 结果找不到对应的 tool_call。
                    // 但实测（WB 整理前/后序列对比）证明它误伤得极其严重：
                    //   整理前 s,u,A(4),t,t,t,t,A(4),t,t,t,t,...
                    //   整理后 s,u,A,A,A,A,A,A,...
                    // 客户端明明把每个 A(n) 的 n 个结果都带齐了，却被整批删光。
                    // 模型于是永远看不到任何工具返回，只能一遍遍重发同样的调用——
                    // 这就是"不断重复调用工具、历史无限膨胀、看起来降智"的真正原因。
                    //
                    // 匹配失败多半只是 id 形态对不上（客户端和上游各自生成过 id），
                    // 不代表这条结果该丢。保留原样交给上游判断，比替上游做删除安全得多。
                    Log.w("工具配对整理：tool 结果没匹配上 tool_call（保留，不删）id=" + id);
                }
            }
            // 第二遍：检查 assistant 的 tool_calls 是否都有对应结果。
            // 这里先打印**诊断信息**（每条消息的角色 + tool_calls 数量），
            // 用来确认客户端发来的历史到底是什么结构——之前的判断都建立在
            // 被截断的抓取片段上，不够可靠。

            for (int i = 0; i < msgs.length(); i++) {
                JSONObject m = msgs.optJSONObject(i);
                if (m == null || !"assistant".equals(Proto.optString(m, "role", ""))) continue;
                JSONArray calls = m.optJSONArray("tool_calls");
                if (calls == null || calls.length() == 0) continue;
                boolean complete = true;
                for (int c = 0; c < calls.length() && complete; c++) {
                    JSONObject call = calls.optJSONObject(c);
                    String id = call == null ? "" : Proto.optString(call, "id", "");
                    boolean found = false;
                    for (int k = i + 1; k < msgs.length(); k++) {
                        JSONObject next = msgs.optJSONObject(k);
                        if (next == null) continue;
                        String nextRole = Proto.optString(next, "role", "");
                        if ("assistant".equals(nextRole)) break;
                        if ("tool".equals(nextRole) && id.equals(Proto.optString(next, "tool_call_id", ""))) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) complete = false;
                }
                if (!complete) {
                    // 同样不删 tool_calls。理由同上：删掉之后模型就"忘记自己调过工具"，
                    // 只会继续重复调用。保留原样，让上游去判断能否接受。
                    Log.w("工具配对整理：assistant 的 tool_calls 有缺结果的（保留，不删）");
                }
            }
        } catch (Exception e) {
            Log.w("工具调用配对整理失败：" + e);
        }
    }

    static JSONObject openAiMessage(Proto.Msg m) throws Exception {
            JSONObject o = new JSONObject();
            String role = m.role;
            if ("tool".equals(role)) {
                o.put("role", "tool");
                o.put("content", m.textBuffer().toString());
                String id = "";
                for (Proto.Part part : m.parts) {
                    if (Proto.Part.TOOL_RESULT.equals(part.type)) id = part.toolCallId == null ? "" : part.toolCallId;
                }
                o.put("tool_call_id", id);
                return o;
            }
            o.put("role", role);
            List<Proto.Part> images = new ArrayList<>();
            JSONArray toolCalls = new JSONArray();
            for (Proto.Part part : m.parts) {
                if (Proto.Part.IMAGE.equals(part.type)) images.add(part);
                else if (Proto.Part.TOOL_CALL.equals(part.type)) {
                    JSONObject fn = new JSONObject();
                    fn.put("name", part.toolName == null ? "" : part.toolName);
                    fn.put("arguments", part.argsJson == null ? "{}" : part.argsJson);
                    toolCalls.put(new JSONObject()
                            .put("id", part.toolCallId == null ? "" : part.toolCallId)
                            .put("type", "function")
                            .put("function", fn));
                }
            }
            if (!images.isEmpty()) {
                JSONArray content = new JSONArray();
                String text = m.textBuffer().toString();
                if (!text.isEmpty()) content.put(new JSONObject().put("type", "text").put("text", text));
                for (Proto.Part img : images) {
                    String u = imageUrl(img);
                    if (u != null) content.put(new JSONObject().put("type", "image_url").put("image_url", new JSONObject().put("url", u)));
                }
                o.put("content", content);
            } else {
                o.put("content", m.textBuffer().toString());
            }
            if (toolCalls.length() > 0) o.put("tool_calls", toolCalls);
            return o;
        }

    /** 内联图片的体积上限：超过就不下（手机内存 + 上游普遍限制都在这个量级）。 */
    private static final int IMAGE_FETCH_MAX_BYTES = 8 * 1024 * 1024;
    /** 下载图片的超时（毫秒）：这是请求链路中间的一步，不能让客户端干等太久。 */
    private static final int IMAGE_FETCH_TIMEOUT_MS = 15000;

    /**
     * 把一个图片 URL 下下来并转成 base64，返回 {mime, base64}；失败返回 null。
     *
     * 为什么需要：Gemini 这类上游只收内联 base64，而 OpenAI 系客户端习惯用 URL 传图。
     * 不做这一步，图就被丢了（旧行为），模型看不到图 —— 属于最隐蔽的一种降智。
     *
     * 几道必要的闸（都不是可选的）：
     *   · 只允许 http/https：防止 file: / content: 之类的本地协议被当成图片来源；
     *   · 体积上限 8MB：避免一张大图把所有请求内存拖垮；
     *   · 超时 15 秒：下载失败不能把整个请求卡死；
     *   · 只接受 image/* 的 Content-Type：防止被当成任意内容的搬运通道。
     * 失败一律返回 null，由调用方决定怎么处理（目前是记日志 + 本次请求不带这张图）。
     */
    /**
     * 只允许指向公网地址的 http/https URL。
     *
     * 为什么必须查：图片 URL 来自客户端请求体。以前只判断了 "http://"，
     * 于是任何能调本机网关的应用都能让网关去请求 127.0.0.1、局域网设备的
     * 管理页或云元数据地址——盲探测，甚至把返回的 image/* 内联给上游。
     *
     * 这里解析域名并检查**每一个**解析结果；重定向由 openImageConnection()
     * 逐跳再查一遍（HttpURLConnection 的自动跟随会让第一次检查形同虚设）。
     * 残余风险：DNS rebinding 在"检查"和"连接"之间做手脚，本机场景下
     * 这个窗口可接受；要彻底堵住得把连接固定到已校验的 IP。
     */
    static boolean isPublicHttpUrl(String raw) {
        if (raw == null) return false;
        try {
            java.net.URI uri = new java.net.URI(raw.trim());
            String scheme = uri.getScheme();
            if (scheme == null) return false;
            scheme = scheme.toLowerCase(java.util.Locale.US);
            if (!"http".equals(scheme) && !"https".equals(scheme)) return false;
            if (uri.getUserInfo() != null) return false;   // http://user@host 这种不该出现在图片 URL 里
            String host = uri.getHost();
            if (host == null || host.isEmpty()) return false;
            java.net.InetAddress[] addrs = java.net.InetAddress.getAllByName(host);
            if (addrs == null || addrs.length == 0) return false;
            for (java.net.InetAddress a : addrs) {
                if (a == null || isBlockedAddress(a)) return false;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isBlockedAddress(java.net.InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
                || a.isSiteLocalAddress() || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int b0 = b[0] & 0xff;
            int b1 = b[1] & 0xff;
            if (b0 == 0) return true;                         // 0.0.0.0/8
            if (b0 == 100 && b1 >= 64 && b1 <= 127) return true;  // 100.64/10 CGNAT
            if ((b0 & 0xf0) == 0xf0) return true;             // 240/4 保留
            return false;
        }
        if (b.length == 16) {
            if ((b[0] & 0xfe) == 0xfc) return true;           // fc00::/7 唯一本地地址
            boolean mapped = true;
            for (int i = 0; i < 10; i++) if (b[i] != 0) { mapped = false; break; }
            if (mapped && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
                /* ::ffff:a.b.c.d：按 IPv4 再判一次，否则 isLoopback 之类对映射地址不生效 */
                int b0 = b[12] & 0xff, b1 = b[13] & 0xff;
                return b0 == 0 || b0 == 127 || b0 == 10
                        || (b0 == 192 && b1 == 168) || (b0 == 172 && b1 >= 16 && b1 <= 31)
                        || (b0 == 169 && b1 == 254)
                        || (b0 == 100 && b1 >= 64 && b1 <= 127)
                        || (b0 & 0xf0) == 0xf0;
            }
        }
        return false;
    }

    /** 建立图片连接：不自动跟随重定向，每一跳都重新做公网校验，最多 3 跳。 */
    private static java.net.HttpURLConnection openImageConnection(String url) {
        String current = url;
        for (int hop = 0; hop <= 3; hop++) {
            if (!isPublicHttpUrl(current)) {
                Log.w("图片重定向目标不是公网地址，已停止：" + Upstreams.shortenPublic(current));
                return null;
            }
            try {
                java.net.HttpURLConnection c =
                        (java.net.HttpURLConnection) new java.net.URL(current).openConnection();
                c.setConnectTimeout(IMAGE_FETCH_TIMEOUT_MS);
                c.setReadTimeout(IMAGE_FETCH_TIMEOUT_MS);
                c.setInstanceFollowRedirects(false);
                c.setRequestProperty("Accept", "image/*");
                int code = c.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = c.getHeaderField("Location");
                    c.disconnect();
                    if (loc == null || loc.isEmpty()) return null;
                    current = new java.net.URL(new java.net.URL(current), loc).toString();
                    continue;
                }
                return c;
            } catch (Throwable t) {
                Log.w("图片下载连接失败：" + Upstreams.shortenPublic(current) + " · " + t);
                return null;
            }
        }
        Log.w("图片重定向超过 3 跳，已放弃：" + Upstreams.shortenPublic(url));
        return null;
    }

    static String[] fetchImageAsBase64(String url) {
        if (url == null) return null;
        String u = url.trim();
        if (!isPublicHttpUrl(u)) {
            Log.w("图片 URL 不是公网 http/https，已跳过（防 SSRF）：" + Upstreams.shortenPublic(u));
            return null;
        }
        java.net.HttpURLConnection conn = null;
        try {
            conn = openImageConnection(u);
            if (conn == null) return null;
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                Log.w("图片下载失败 HTTP " + code + "：" + Upstreams.shortenPublic(u));
                return null;
            }
            String ctype = conn.getContentType();
            if (ctype == null || !ctype.toLowerCase(java.util.Locale.US).startsWith("image/")) {
                Log.w("图片 URL 返回的不是图片（Content-Type=" + ctype + "），已跳过："
                        + Upstreams.shortenPublic(u));
                return null;
            }
            String mime = ctype;
            int semi = mime.indexOf(';');
            if (semi > 0) mime = mime.substring(0, semi).trim();
            java.io.InputStream in = conn.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            try {
                byte[] buf = new byte[8192];
                int n;
                int total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > IMAGE_FETCH_MAX_BYTES) {
                        Log.w("图片超过 " + (IMAGE_FETCH_MAX_BYTES / 1024 / 1024) + "MB，已放弃："
                                + Upstreams.shortenPublic(u));
                        return null;
                    }
                    bos.write(buf, 0, n);
                }
            } finally {
                in.close();
            }
            byte[] data = bos.toByteArray();
            if (data.length == 0) return null;
            String b64 = android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP);
            Log.i("已把 URL 图下载并内联：" + (data.length / 1024) + "KB " + mime);
            return new String[]{mime, b64};
        } catch (Throwable t) {
            Log.w("图片下载异常，已跳过：" + Upstreams.shortenPublic(u) + " —— " + t);
            return null;
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    static String imageUrl(Proto.Part img) {
        if (img.dataBase64 == null || img.dataBase64.isEmpty()) return null;
        if (img.mimeType != null && img.mimeType.startsWith("url:")) return img.mimeType.substring(4);
        String mime = (img.mimeType == null || img.mimeType.isEmpty()) ? "image/png" : img.mimeType;
        return "data:" + mime + ";base64," + img.dataBase64;
    }

    /* -------------------------------------------------------------- Anthropic */

    static final class AnthropicUp implements Proto.Upstream {
        private final Config.Provider p;

        AnthropicUp(Config.Provider p) {
            this.p = p;
        }

        @Override
        public void chat(String model, Proto.Req req, Proto.Sink sink) throws Exception {
            JSONObject body = anthropicBody(model, req);
            Map<String, String> headers = new HashMap<>();
            if (!p.apiKey.isEmpty()) {
                headers.put("x-api-key", p.apiKey);
                headers.put("Authorization", "Bearer " + p.apiKey);
            }
            headers.put("anthropic-version", "2023-06-01");
            anthropicStream(url(p.baseUrl, "v1/messages"), headers, body, sink);
        }
    }

    /** Shared Anthropic-shaped body builder (API key and OAuth channels both use it). */
    static JSONObject anthropicBody(String model, Proto.Req req) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("max_tokens", req.maxTokens == null ? 8192 : req.maxTokens.intValue());
        body.put("stream", true);
        if (!req.systemText().isEmpty()) body.put("system", req.systemText());
        if (req.temperature != null) body.put("temperature", req.temperature.doubleValue());
        if (req.topP != null) body.put("top_p", req.topP.doubleValue());
        /* top_k 在 Anthropic 里是原生字段：OpenAI 入口的 top_k / Gemini 的 topK
           都能等价翻译过来。 */
        if (req.topK != null) body.put("top_k", req.topK.intValue());
        if (!req.stop.isEmpty()) body.put("stop_sequences", new JSONArray(req.stop));
        JSONArray msgs = new JSONArray();
        for (Proto.Msg m : req.messages) {
            String role = "assistant".equals(m.role) ? "assistant" : "user";
            JSONArray content = new JSONArray();
            for (Proto.Part part : m.parts) {
                if (Proto.Part.TEXT.equals(part.type)) {
                    content.put(new JSONObject().put("type", "text").put("text", part.text == null ? "" : part.text));
                } else if (Proto.Part.IMAGE.equals(part.type)) {
                    /* 以前这里是 `if (url:) continue;` —— 把"用 URL 传的图"整张丢掉。
                       上游完全看不到图，模型只能凭文字瞎答，用户看到的就是"图片版模型变傻了"。
                       Anthropic 原生支持 URL 图源（source.type = "url"），照原样发出去即可。 */
                    JSONObject source;
                    if (part.mimeType != null && part.mimeType.startsWith("url:")) {
                        source = new JSONObject()
                                .put("type", "url")
                                .put("url", part.mimeType.substring(4));
                    } else {
                        source = new JSONObject()
                                .put("type", "base64")
                                .put("media_type", part.mimeType == null || part.mimeType.isEmpty() ? "image/png" : part.mimeType)
                                .put("data", part.dataBase64 == null ? "" : part.dataBase64);
                    }
                    content.put(new JSONObject().put("type", "image").put("source", source));
                } else if (Proto.Part.TOOL_CALL.equals(part.type)) {
                    Object input;
                    try {
                        input = new JSONObject(part.argsJson == null ? "{}" : part.argsJson);
                    } catch (Exception e) {
                        input = new JSONObject();
                    }
                    content.put(new JSONObject()
                            .put("type", "tool_use")
                            .put("id", part.toolCallId == null ? "" : part.toolCallId)
                            .put("name", part.toolName == null ? "" : part.toolName)
                            .put("input", input));
                } else if (Proto.Part.TOOL_RESULT.equals(part.type)) {
                    JSONArray rc = new JSONArray();
                    rc.put(new JSONObject().put("type", "text").put("text", part.text == null ? "" : part.text));
                    content.put(new JSONObject()
                            .put("type", "tool_result")
                            .put("tool_use_id", part.toolCallId == null ? "" : part.toolCallId)
                            .put("content", rc));
                } else if (Proto.Part.THINKING.equals(part.type)) {
                    /* 思维链块必须**原样**回传：类型还是 thinking，signature / data 一个都不能少。
                       以前这里把它降级成 {"type":"text"}，等于把签名抹掉 ——
                       开了 extended thinking 又带工具的对话，第二轮往上就会 400，
                       用户只看到"多轮对话突然用不了"。 */
                    if (part.dataEncrypted != null && !part.dataEncrypted.isEmpty()) {
                        content.put(new JSONObject()
                                .put("type", "redacted_thinking")
                                .put("data", part.dataEncrypted));
                    } else {
                        JSONObject tb = new JSONObject()
                                .put("type", "thinking")
                                .put("thinking", part.text == null ? "" : part.text);
                        if (part.signature != null && !part.signature.isEmpty()) {
                            tb.put("signature", part.signature);
                        }
                        content.put(tb);
                    }
                }
            }
            if (content.length() == 0) continue;
            msgs.put(new JSONObject().put("role", role).put("content", content));
        }
        body.put("messages", msgs);
        if (!req.tools.isEmpty()) {
            JSONArray tools = new JSONArray();
            for (Proto.Tool t : req.tools) {
                tools.put(new JSONObject()
                        .put("name", t.name)
                        .put("description", t.description == null ? "" : t.description)
                        .put("input_schema", t.schema == null ? new JSONObject().put("type", "object") : t.schema));
            }
            body.put("tools", tools);
            Object tc = XParams.toolChoice(req.protocol, req.toolChoice, "anthropic");
            if (tc != null) body.put("tool_choice", tc);
            else if (req.toolChoice != null) note("tool_choice", req);
        }
        /* thinking：开了 extended thinking 的客户端，走网关也必须继续思考。
           不传的话模型不推理，难题上的表现就是"明显变笨"，而且没有任何报错。
           注意 Anthropic 要求开启 thinking 时 temperature 必须为 1（或不传），
           客户端一般会自己遵守；这里不做纠正，免得把客户端的有意设置改掉。 */
        Object th = XParams.thinking(req.protocol, req.thinkingParams, "anthropic");
        if (th != null) body.put("thinking", th);
        else if (req.thinkingParams != null) note("thinking", req);
        if ("anthropic".equals(req.protocol)) mergeExtra(body, req.extraBody);
        return body;
    }

    /** Shared Anthropic SSE reader. */
    static void anthropicStream(String endpoint, Map<String, String> headers, JSONObject body, Proto.Sink sink) throws Exception {
        final Proto.Usage usage = new Proto.Usage();
        final String[] finish = {""};
        final Map<Integer, String[]> blockInfo = new LinkedHashMap<>();
        /* 4.37.0：上游 response id。Anthropic 只在 message_start 里给一次
           （message.id，形如 msg_...），后面没有重复，所以要在这里就报上去。 */
        final boolean[] idReported = {false};
        final boolean[] stopSeen = {false};

        postStream(endpoint, headers, body, line -> {
            JSONObject ev = sseData(line);
            if (ev == null) return;
            String type = Proto.optString(ev, "type", "");
            if ("message_stop".equals(type)) {
                stopSeen[0] = true;
            } else if ("message_start".equals(type)) {
                JSONObject msg = ev.optJSONObject("message");
                if (msg != null) {
                    applyUsage(usage, msg.optJSONObject("usage"), "input_tokens", "output_tokens", "cache_read_input_tokens");
                    if (!idReported[0]) {
                        String rid = Proto.optString(msg, "id", "");
                        if (!rid.isEmpty()) {
                            idReported[0] = true;
                            sink.responseId(rid);
                        }
                    }
                }
            } else if ("content_block_start".equals(type)) {
                int idx = Proto.optInt(ev, "index") == null ? 0 : Proto.optInt(ev, "index").intValue();
                JSONObject cb = ev.optJSONObject("content_block");
                if (cb != null && "tool_use".equals(Proto.optString(cb, "type", ""))) {
                    blockInfo.put(idx, new String[]{Proto.optString(cb, "id", ""), Proto.optString(cb, "name", "")});
                }
            } else if ("content_block_delta".equals(type)) {
                int idx = Proto.optInt(ev, "index") == null ? 0 : Proto.optInt(ev, "index").intValue();
                JSONObject d = ev.optJSONObject("delta");
                if (d == null) return;
                String dt = Proto.optString(d, "type", "");
                if ("text_delta".equals(dt)) {
                    String t = Proto.optString(d, "text", "");
                    if (!t.isEmpty()) sink.text(t);
                } else if ("thinking_delta".equals(dt)) {
                    String t = Proto.optString(d, "thinking", Proto.optString(d, "text", ""));
                    if (!t.isEmpty()) sink.thinking(t);
                } else if ("input_json_delta".equals(dt)) {
                    String partial = Proto.optString(d, "partial_json", "");
                    String[] info = blockInfo.get(idx);
                    if (info == null) info = new String[]{"call_" + idx, "tool", ""};
                    String prev = (info.length > 2 && info[2] != null) ? info[2] : "";
                    blockInfo.put(idx, new String[]{info[0], info[1], prev + partial});
                }
            } else if ("error".equals(type)) {
                // mid-stream 的限流同样不能当故障记：Kimi / Claude 这类通道在上游吃紧时
                // 会在流中途回 error 事件，如果裸抛 IllegalStateException，就会被记成普通失败，
                // 3 次就进熔断把健康账号误杀——与 200 错误体那条是同一个坑。
                JSONObject err = ev.optJSONObject("error");
                String errType = err == null ? "" : Proto.optString(err, "type", "");
                String errMsg = err == null ? Upstreams.shorten(ev.toString())
                        : Proto.optString(err, "message", err.toString());
                String kind = throttleKindFromAnthropicType(errType);
                if (kind == null) kind = throttleKindFromBody(errMsg);   // 兜底：文本匹配
                if (kind != null) {
                    int code = "quota".equals(kind) ? 402 : 429;
                    throw new Throttled(code, Http.defaultBackoffMs(code), kind,
                            "Anthropic 额度/限流（" + errType + "）：" + errMsg);
                }
                throw new IllegalStateException("Anthropic 错误：" + errMsg);
            } else if ("message_delta".equals(type)) {
                JSONObject d = ev.optJSONObject("delta");
                if (d != null) {
                    String sr = Proto.optString(d, "stop_reason", "");
                    if (!sr.isEmpty()) finish[0] = sr;
                }
                applyUsage(usage, ev.optJSONObject("usage"), "input_tokens", "output_tokens", "cache_read_input_tokens");
            }
        });

        if (!stopSeen[0] && finish[0].isEmpty()) {
            Log.w("Anthropic 流在 message_stop 之前结束，判定为连接中断");
            throw new java.io.IOException("anthropic stream ended before message_stop");
        }

        boolean anyTool = false;
        for (Map.Entry<Integer, String[]> e : blockInfo.entrySet()) {
            String[] info = e.getValue();
            String id = (info[0] == null || info[0].isEmpty())
                    ? "call_" + Math.abs(System.nanoTime() % 1000000) : info[0];
            String name = info[1] == null ? "" : info[1];
            String raw = (info.length > 2 && info[2] != null && !info[2].isEmpty()) ? info[2] : "{}";
            String fixed = ToolFix.repairArgs(raw);
            if (fixed == null) fixed = raw;
            if (name.isEmpty()) name = ToolFix.nameFromArgs(fixed);
            anyTool = true;
            sink.toolCall(id, name, fixed);
        }
        sink.finish(Proto.canonicalStop(finish[0], anyTool), usage);
    }

    /**
     * Gemini 的 `generationConfig` 构造（抽出来是为了让自检能覆盖它）。
     *
     * 这里有两个"模型聪不聪明"的直接开关，以前整段被忽略：
     *   · thinkingConfig —— 2.5 系列的思考预算，丢掉等于不思考；
     *   · responseMimeType / responseSchema —— 结构化输出，丢掉等于 JSON 模式失效。
     * 同协议时原样带上；跨协议无等价字段时不猜，只记一条日志（见 note）。
     */
    static JSONObject geminiGenerationConfig(Proto.Req req) throws Exception {
        JSONObject gen = new JSONObject();
        if (req.maxTokens != null) gen.put("maxOutputTokens", req.maxTokens.intValue());
        if (req.temperature != null) gen.put("temperature", req.temperature.doubleValue());
        if (req.topP != null) gen.put("topP", req.topP.doubleValue());
        if (req.topK != null) gen.put("topK", req.topK.intValue());
        if (!req.stop.isEmpty()) gen.put("stopSequences", new JSONArray(req.stop));
        Object tcfg = XParams.thinking(req.protocol, req.thinkingParams, "gemini");
        if (tcfg instanceof JSONObject) gen.put("thinkingConfig", tcfg);
        else if (req.thinkingParams != null) note("thinkingConfig", req);
        Object rf = XParams.responseFormat(req.protocol, req.responseFormat, "gemini");
        if (rf instanceof JSONObject) {
            JSONObject r = (JSONObject) rf;
            String mime = Proto.optString(r, "responseMimeType", "");
            if (!mime.isEmpty()) gen.put("responseMimeType", mime);
            JSONObject schema = r.optJSONObject("responseSchema");
            if (schema != null) gen.put("responseSchema", schema);
        } else if (req.responseFormat != null) {
            note("responseMimeType", req);
        }
        return gen;
    }

    /* ----------------------------------------------------------------- Gemini */

    static final class GeminiUp implements Proto.Upstream {
        private final Config.Provider p;

        GeminiUp(Config.Provider p) {
            this.p = p;
        }

        @Override
        public void chat(String model, Proto.Req req, Proto.Sink sink) throws Exception {
            JSONObject body = new JSONObject();
            JSONArray contents = new JSONArray();
            for (Proto.Msg m : req.messages) {
                JSONArray parts = new JSONArray();
                for (Proto.Part part : m.parts) {
                    if (Proto.Part.TEXT.equals(part.type)) {
                        parts.put(new JSONObject().put("text", part.text == null ? "" : part.text));
                    } else if (Proto.Part.IMAGE.equals(part.type)) {
                        /* Gemini 只接受内联 base64（inline_data），没有"URL 图源"这种字段。
                           以前这里遇到 URL 图直接 `continue` —— 图被整张丢掉，模型只能凭文字瞎答，
                           用户看到的就是"图片版模型变傻了"，而且没有任何提示。
                           现在改为把图下下来再内联；下不动就明确记一条日志，不再静默。 */
                        String mime = part.mimeType;
                        String data = part.dataBase64;
                        if (mime != null && mime.startsWith("url:")) {
                            String url = mime.substring(4);
                            String[] got = fetchImageAsBase64(url);
                            if (got == null) {
                                Log.w("图片按 URL 传给 Gemini 上游，但下载失败，本次请求不含这张图："
                                        + Upstreams.shortenPublic(url));
                                continue;
                            }
                            mime = got[0];
                            data = got[1];
                        }
                        JSONObject inline = new JSONObject()
                                .put("mime_type", mime == null || mime.isEmpty() ? "image/png" : mime)
                                .put("data", data == null ? "" : data);
                        parts.put(new JSONObject().put("inline_data", inline));
                    } else if (Proto.Part.TOOL_CALL.equals(part.type)) {
                        Object args;
                        try {
                            args = new JSONObject(part.argsJson == null ? "{}" : part.argsJson);
                        } catch (Exception e) {
                            args = new JSONObject();
                        }
                        parts.put(new JSONObject().put("functionCall", new JSONObject()
                                .put("name", part.toolName == null ? "" : part.toolName)
                                .put("args", args)));
                    } else if (Proto.Part.TOOL_RESULT.equals(part.type)) {
                        JSONObject response = new JSONObject();
                        response.put("result", part.text == null ? "" : part.text);
                        parts.put(new JSONObject().put("functionResponse", new JSONObject()
                                .put("name", part.toolName == null || part.toolName.isEmpty() ? "tool" : part.toolName)
                                .put("response", response)));
                    } else if (Proto.Part.THINKING.equals(part.type)) {
                        /* 思维链在 Gemini 里是带 thought:true 的 part，不是普通 text。
                           降级成普通 text 会让上游把"思考内容"当成正式回答的一部分，
                           多轮再传回去时语义就错了。 */
                        parts.put(new JSONObject()
                                .put("text", part.text == null ? "" : part.text)
                                .put("thought", true));
                    }
                }
                if (parts.length() == 0) continue;
                String role = "assistant".equals(m.role) ? "model" : "user";
                contents.put(new JSONObject().put("role", role).put("parts", parts));
            }
            body.put("contents", contents);
            if (!req.systemText().isEmpty()) {
                body.put("systemInstruction", new JSONObject().put("parts",
                        new JSONArray().put(new JSONObject().put("text", req.systemText()))));
            }
            JSONObject gen = geminiGenerationConfig(req);
            if (gen.length() > 0) body.put("generationConfig", gen);
            /* toolConfig.functionCallingConfig 是 Gemini 的 tool_choice 等价物。 */
            Object tchoice = XParams.toolChoice(req.protocol, req.toolChoice, "gemini");
            if (tchoice instanceof JSONObject) {
                body.put("toolConfig", new JSONObject().put("functionCallingConfig", tchoice));
            } else if (req.toolChoice != null) {
                note("toolConfig", req);
            }
            if (!req.tools.isEmpty()) {
                JSONArray declarations = new JSONArray();
                for (Proto.Tool t : req.tools) {
                    declarations.put(new JSONObject()
                            .put("name", t.name)
                            .put("description", t.description == null ? "" : t.description)
                            .put("parameters", t.schema == null ? new JSONObject().put("type", "object") : t.schema));
                }
                body.put("tools", new JSONArray().put(new JSONObject().put("functionDeclarations", declarations)));
            }
            if ("gemini".equals(req.protocol)) {
                mergeExtra(body, req.extraBody);
                JSONObject eg = body.optJSONObject("generationConfig");
                if (eg == null) eg = new JSONObject();
                mergeExtra(eg, req.extraGenerationConfig);
                if (eg.length() > 0) body.put("generationConfig", eg);
            }

            String base = trimSlash(p.baseUrl);
            if (base.isEmpty()) base = "https://generativelanguage.googleapis.com";
            String endpoint = url(base, "v1beta/models/" + model + ":streamGenerateContent") + "?alt=sse";
            if (!p.apiKey.isEmpty() && !endpoint.contains("key=")) endpoint = endpoint + "&key=" + p.apiKey;

            Map<String, String> headers = new HashMap<>();
            if (!p.apiKey.isEmpty()) headers.put("x-goog-api-key", p.apiKey);

            final Proto.Usage usage = new Proto.Usage();
            final String[] finish = {""};
            final List<String[]> calls = new ArrayList<>();
            /* 4.37.0：Gemini 的 SSE 每个块可能带 responseId（新版 API 才有），
               老版本没有这个字段就照旧留空，不算错误。 */
            final boolean[] idReported = {false};
            final boolean[] finished = {false};

            postStream(endpoint, headers, body, line -> {
                JSONObject ev = sseData(line);
                if (ev == null) return;
                if (!idReported[0]) {
                    String rid = Proto.optString(ev, "responseId",
                            Proto.optString(ev, "response_id", ""));
                    if (!rid.isEmpty()) {
                        idReported[0] = true;
                        sink.responseId(rid);
                    }
                }
                JSONObject um = ev.optJSONObject("usageMetadata");
                if (um != null) {
                    applyUsage(usage, um, "promptTokenCount", "candidatesTokenCount", "cachedContentTokenCount");
                    Integer t = Proto.optInt(um, "totalTokenCount");
                    if (t != null && usage.input == 0 && usage.output == 0) usage.output = t.intValue();
                }
                JSONArray candidates = ev.optJSONArray("candidates");
                if (candidates == null || candidates.length() == 0) return;
                JSONObject c0 = candidates.optJSONObject(0);
                if (c0 == null) return;
                JSONObject content = c0.optJSONObject("content");
                if (content != null) {
                    JSONArray parts = content.optJSONArray("parts");
                    if (parts != null) {
                        for (int i = 0; i < parts.length(); i++) {
                            JSONObject part = parts.optJSONObject(i);
                            if (part == null) continue;
                            JSONObject fc = part.optJSONObject("functionCall");
                            if (fc != null) {
                                String name = Proto.optString(fc, "name", "");
                                JSONObject args = fc.optJSONObject("args");
                                calls.add(new String[]{"call_" + calls.size(), name, args == null ? "{}" : args.toString()});
                                continue;
                            }
                            String t = Proto.optString(part, "text", "");
                            if (t.isEmpty()) continue;
                            if (part.optBoolean("thought", false)) sink.thinking(t);
                            else sink.text(t);
                        }
                    }
                }
                String fr = Proto.optString(c0, "finishReason", "");
                if (!fr.isEmpty() && !"FINISH_REASON_UNSPECIFIED".equals(fr)) {
                    finish[0] = fr;
                    finished[0] = true;
                }
            });

            if (!finished[0]) {
                Log.w("Gemini 流在 finishReason 之前结束，判定为连接中断");
                throw new java.io.IOException("gemini stream ended before finishReason");
            }

            for (String[] c : calls) sink.toolCall(c[0], c[1], c[2]);
            sink.finish(Proto.canonicalStop(finish[0], !calls.isEmpty()), usage);
        }
    }
}
