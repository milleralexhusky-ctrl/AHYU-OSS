package com.cpa.gateway.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Canonical, provider-agnostic representation of a chat request and its streamed response.
 * Every protocol front-end (OpenAI / Anthropic / Gemini) parses into this, and every upstream
 * renders from this. That is what makes N protocols x M providers work without N*M code.
 */
public final class Proto {

    private Proto() {}

    public static final class Part {
        public static final String TEXT = "text";
        public static final String THINKING = "thinking";
        public static final String IMAGE = "image";
        public static final String TOOL_CALL = "toolCall";
        public static final String TOOL_RESULT = "toolResult";

        public String type = TEXT;
        public String text;
        public String mimeType;
        public String dataBase64;
        public String toolCallId;
        public String toolName;
        public String argsJson;
        /**
         * Anthropic 思维链块的签名；以及 redacted_thinking 的加密数据。
         *
         * 为什么必须留：开了 extended thinking 之后，带工具的对话**必须**把上一轮的
         * thinking 块原样带回去（含 signature），否则上游直接 400。丢掉它等于
         * "第二轮开始就不能用推理了"，是实打实的降智。
         */
        public String signature;
        /** redacted_thinking：内容被加密、只能原样透传的那种块。 */
        public String dataEncrypted;

        public static Part text(String s) {
            Part p = new Part();
            p.type = TEXT;
            p.text = s == null ? "" : s;
            return p;
        }

        public static Part thinking(String s) {
            Part p = new Part();
            p.type = THINKING;
            p.text = s == null ? "" : s;
            return p;
        }

        /** 带签名的思维链块（原样回传上游时用）。 */
        public static Part thinkingSigned(String s, String signature) {
            Part p = thinking(s);
            p.signature = signature;
            return p;
        }

        /** redacted_thinking：内容加密，只能整块原样带回去。 */
        public static Part redactedThinking(String dataEncrypted) {
            Part p = new Part();
            p.type = THINKING;
            p.dataEncrypted = dataEncrypted;
            p.text = "";
            return p;
        }

        public static Part image(String mime, String b64) {
            Part p = new Part();
            p.type = IMAGE;
            p.mimeType = mime;
            p.dataBase64 = b64;
            return p;
        }

        public static Part toolCall(String id, String name, String args) {
            Part p = new Part();
            p.type = TOOL_CALL;
            p.toolCallId = id;
            p.toolName = name;
            p.argsJson = args == null ? "{}" : args;
            return p;
        }

        public static Part toolResult(String id, String name, String text) {
            Part p = new Part();
            p.type = TOOL_RESULT;
            p.toolCallId = id;
            p.toolName = name;
            p.text = text == null ? "" : text;
            return p;
        }
    }

    public static final class Msg {
        public String role = "user";
        public final List<Part> parts = new ArrayList<>();

        public Msg() {}

        public Msg(String role) {
            this.role = role;
        }

        public Msg add(Part p) {
            parts.add(p);
            return this;
        }

        public StringBuilder textBuffer() {
            StringBuilder sb = new StringBuilder();
            for (Part p : parts) {
                if (Part.TEXT.equals(p.type) || Part.TOOL_RESULT.equals(p.type)) {
                    sb.append(p.text == null ? "" : p.text);
                }
            }
            return sb;
        }

        public boolean hasOnlyText() {
            for (Part p : parts) {
                if (!Part.TEXT.equals(p.type)) return false;
            }
            return true;
        }
    }

    public static final class Tool {
        public String name;
        public String description;
        public JSONObject schema;

        public Tool(String name, String description, JSONObject schema) {
            this.name = name;
            this.description = description;
            this.schema = schema;
        }
    }

    public static final class Req {
        public String model;
        public final List<Msg> messages = new ArrayList<>();
        public String system;
        public Integer maxTokens;
        public Double temperature;
        public Double topP;
        /**
         * top-k 采样（4.50.1）。
         *
         * 三套协议里的名字不同：OpenAI 兼容端点的 `top_k`、Anthropic 的 `top_k`、
         * Gemini 的 `generationConfig.topK`，语义是同一件事。以前只在同协议时
         * 原样透传，跨协议直接丢；现在收进 canonical，Anthropic ↔ Gemini 以及
         * 客户端显式发 `top_k` 的路径都能翻译过去。
         *
         * 不往 OpenAI 上游跨协议翻译：OpenAI 官方接口不认 `top_k`，
         * 硬塞会让本来正常的请求 400。
         */
        public Integer topK;
        public final List<String> stop = new ArrayList<>();
        public final List<Tool> tools = new ArrayList<>();
        public boolean stream;
        /**
         * 响应保真模式（4.50.0）：同协议、无工具时，把上游响应原样回给客户端。
         *
         * 只在客户端要的东西 canonical 表达不了时才打开（`n>1` 多候选、
         * `logprobs`/`top_logprobs` token 概率、音频输出）。打开后上游拿到的
         * 还是同一份请求，只是响应不再被拆成"一段正文 + 一次 finish"——
         * 多 choice、logprobs、system_fingerprint 这些字段不再丢。
         *
         * 只对通用 OpenAI 兼容上游生效；别的渠道照旧走 canonical，
         * 所以这个开关不会改变任何现有渠道的行为。
         */
        public boolean rawResponse;
        /**
         * 重复请求计数的"本次请求"快照（4.37.0）。
         *
         * 一个客户端请求可能被同一个网关请求对象用于多个候选（换号重试），
         * 计数只能在入口记一次，否则一次请求会被算成 2~4 次重发。
         */
        public boolean repeatRecorded;
        public int repeatCount;

        /* ------------------------------------------------------------------
         * 能力相关参数的透传（"别把模型用傻"）
         *
         * 这些字段以前**完全没被解析**，等于客户端发什么、网关都当没看见：
         *   tool_choice      强制/指定工具调用 —— 丢掉后 agent 类客户端拿不到工具调用，
         *                    表现为"模型突然不会用工具了"
         *   response_format  JSON 模式 / 结构化输出 —— 丢掉后返回散文，解析必炸
         *   thinking         推理开关与预算（Anthropic）/ 推理档位（OpenAI）
         *                    —— 丢掉后推理模型不开思考，这是最直接的"降智"
         *
         * 设计取舍：这三个的形状在各家之间差别很大（Anthropic 的 tool_choice 是
         * {"type":"any"}，OpenAI 是 "required"），所以**原样存 Object**，
         * 由 XParams 在出口处按目标协议翻译，而不是在入口就归一化 ——
         * 归一化必然丢信息，而丢信息正是这里要解决的问题。
         * ------------------------------------------------------------------ */
        /** 原样保留的 tool_choice（String 或 JSONObject，视来源协议而定）。 */
        public Object toolChoice;
        /** 原样保留的 response_format / responseMimeType+schema。 */
        public Object responseFormat;
        /** Anthropic thinking / OpenAI reasoning / Gemini thinkingConfig，原样保留。 */
        public Object thinkingParams;
        /** OpenAI reasoning_effort（low / medium / high / minimal）。 */
        public String reasoningEffort;
        public Boolean parallelToolCalls;
        public Double presencePenalty;
        public Double frequencyPenalty;
        public Integer seed;
        /** 请求来自哪套协议（openai / anthropic / gemini），出口翻译时要用。 */
        public String protocol = "";
        /**
         * 入口协议里**没有单独建模**的顶层字段（top_k / logprobs / service_tier /
         * metadata / safetySettings …）。
         *
         * 同协议转发时原样并回出站 body，避免"网关把不认识的参数悄悄丢掉"。
         * 跨协议不合并——字段语义不同，硬塞过去只会 400。
         */
        public final JSONObject extraBody = new JSONObject();
        /** Gemini generationConfig 里没有单独建模的字段（topK / seed / candidateCount…）。 */
        public final JSONObject extraGenerationConfig = new JSONObject();
        /** 被丢弃的能力参数名（非空时由 Server 记一条可见日志，不静默丢）。 */
        public final List<String> droppedParams = new ArrayList<>();
        /** Internal: gateway-side liveness checks that must not leave traces in the user's account. */
        public boolean ephemeral;

        public String systemText() {
            return system == null ? "" : system;
        }
    }

    public static final class Usage {
        public int input;
        public int output;
        public int cached;

        public int total() {
            return input + output;
        }
    }

    public interface Sink {
        void text(String delta);

        void thinking(String delta);

        void toolCall(String id, String name, String argsJson);

        void finish(String stopReason, Usage usage);

        /** 这次有没有真吐出结构化 tool_calls（用来学工具画像）。 */
        default boolean sawToolCall() {
            return false;
        }

        /** 到目前累积的正文（用来判断是不是把调用写在正文里）。 */
        default String seenText() {
            return "";
        }

        /**
         * 上游返回的响应 ID（4.36.0）。
         *
         * 为什么要它：上游报错时给的那个 requestId 是排查的**唯一键**——
         * 之前查 11128 就是靠发现"五次回包的 requestId 一模一样"，
         * 才断定上游根本不看我们发的东西。但那个 ID 以前只活在日志里，
         * 重启就没了，每次都得重新抓包。
         *
         * 现在各渠道解析到这个 ID 就上报一次，落进 usage.tsv。
         * 不强制实现：有些渠道压根不返回 ID。
         */
        default void responseId(String id) {
        }

        /* ---------------------------------------------------- 响应保真（4.50.0）
         *
         * 下面三个回调只有"同协议原始透传"才会用到。默认实现是空操作，
         * 所以现有的渲染器、Collector、tap 包装器都不受影响。
         * 走 canonical 的渠道永远不会调用它们；OpenAiUp 在 rawResponse
         * 打开时才会调。 */

        /** 一个上游 SSE data 块（已解析成 JSON），原样交给客户端。 */
        default void rawChunk(JSONObject chunk) {
        }

        /** 非流式上游的完整响应体。 */
        default void rawBody(JSONObject body) {
        }

        /** 流结束标记（对应上游的 `data: [DONE]`）。 */
        default void rawDone() {
        }
    }

    public interface Upstream {
        void chat(String model, Req req, Sink sink) throws Exception;
    }

    public static JSONObject obj() {
        return new JSONObject();
    }

    public static String optString(JSONObject o, String key, String def) {
        if (o == null) return def;
        Object v = o.opt(key);
        if (v == null || v == JSONObject.NULL) return def;
        return String.valueOf(v);
    }

    public static Integer optInt(JSONObject o, String key) {
        if (o == null || !o.has(key)) return null;
        Object v = o.opt(key);
        if (v == null || v == JSONObject.NULL) return null;
        if (v instanceof Number) return Integer.valueOf(((Number) v).intValue());
        try {
            return Integer.valueOf(String.valueOf(v).trim());
        } catch (Exception e) {
            return null;
        }
    }

    public static Long optLong(JSONObject o, String key) {
        if (o == null || !o.has(key)) return null;
        Object v = o.opt(key);
        if (v == null || v == JSONObject.NULL) return null;
        if (v instanceof Number) return Long.valueOf(((Number) v).longValue());
        try {
            return Long.valueOf(String.valueOf(v).trim());
        } catch (Exception e) {
            return null;
        }
    }

    public static Double optDouble(JSONObject o, String key) {
        if (o == null || !o.has(key)) return null;
        Object v = o.opt(key);
        if (v == null || v == JSONObject.NULL) return null;
        if (v instanceof Number) return Double.valueOf(((Number) v).doubleValue());
        try {
            return Double.valueOf(String.valueOf(v).trim());
        } catch (Exception e) {
            return null;
        }
    }

    /** 三态布尔：字段不存在返回 null（与"显式 false"区分开，透传时不能把 null 变成 false）。 */
    public static Boolean optBool(JSONObject o, String key) {
        if (o == null || !o.has(key)) return null;
        Object v = o.opt(key);
        if (v == null || v == JSONObject.NULL) return null;
        if (v instanceof Boolean) return (Boolean) v;
        String s = String.valueOf(v).trim();
        if ("true".equalsIgnoreCase(s)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(s)) return Boolean.FALSE;
        return null;
    }

    public static void parseOpenAiContent(Object content, List<Part> out) {
        if (content == null || content == JSONObject.NULL) return;
        if (content instanceof String) {
            String s = (String) content;
            if (!s.isEmpty()) out.add(Part.text(s));
            return;
        }
        if (!(content instanceof JSONArray)) return;
        JSONArray arr = (JSONArray) content;
        for (int i = 0; i < arr.length(); i++) {
            Object item = arr.opt(i);
            if (item instanceof String) {
                out.add(Part.text((String) item));
                continue;
            }
            if (!(item instanceof JSONObject)) continue;
            JSONObject o = (JSONObject) item;
            String type = optString(o, "type", "");
            if ("text".equals(type) || type.isEmpty()) {
                out.add(Part.text(optString(o, "text", "")));
            } else if ("image_url".equals(type) || "image".equals(type)) {
                /* 图片块有三种真实存在的形状，缺一个就会有客户端"图发了但模型看不到"：
                     1. OpenAI:    {"type":"image_url","image_url":{"url":"..."}}
                     2. OpenAI 简写: {"type":"image","url":"..."}
                     3. Anthropic: {"type":"image","source":{"type":"url"|"base64", ...}}
                   以前只处理前两种的半边 —— Anthropic 形状（source 包一层）两个分支都取不到值，
                   取到空串后 addDataUrl 直接返回，于是**图片被静默丢掉**，模型只能看文字瞎答。
                   这类丢图不会报错，用户只会觉得"图片模型变傻了"，是最难查的一种降智。 */
                JSONObject src = o.optJSONObject("image_url");
                JSONObject anthSrc = o.optJSONObject("source");
                if (anthSrc != null) {
                    String st = optString(anthSrc, "type", "");
                    if ("url".equals(st) || anthSrc.has("url")) {
                        addDataUrl(optString(anthSrc, "url", ""), out);
                    } else {
                        /* base64 形状：media_type + data */
                        String mime = optString(anthSrc, "media_type", "image/png");
                        String data = optString(anthSrc, "data", "");
                        if (!data.isEmpty()) {
                            out.add(Part.image(mime == null || mime.isEmpty() ? "image/png" : mime, data));
                        }
                    }
                } else {
                    String url = src != null ? optString(src, "url", "") : optString(o, "url", "");
                    addDataUrl(url, out);
                }
            } else if ("input_text".equals(type)) {
                out.add(Part.text(optString(o, "text", "")));
            } else if ("input_image".equals(type)) {
                addDataUrl(optString(o, "image_url", ""), out);
            } else if ("tool_result".equals(type)) {
                StringBuilder sb = new StringBuilder();
                Object c = o.opt("content");
                if (c instanceof String) sb.append((String) c);
                else if (c instanceof JSONArray) {
                    JSONArray ca = (JSONArray) c;
                    for (int k = 0; k < ca.length(); k++) {
                        Object ci = ca.opt(k);
                        if (ci instanceof JSONObject) sb.append(optString((JSONObject) ci, "text", ""));
                        else if (ci != null) sb.append(String.valueOf(ci));
                    }
                }
                out.add(Part.toolResult(optString(o, "tool_use_id", optString(o, "tool_call_id", "")), "", sb.toString()));
            } else if ("thinking".equals(type)) {
                String sig = optString(o, "signature", "");
                Part tp = Part.thinking(optString(o, "thinking", optString(o, "text", "")));
                if (!sig.isEmpty()) tp.signature = sig;
                out.add(tp);
            } else if ("redacted_thinking".equals(type)) {
                out.add(Part.redactedThinking(optString(o, "data", "")));
            }
        }
    }

    public static void addDataUrl(String url, List<Part> out) {
        if (url == null || url.isEmpty()) return;
        int comma = url.indexOf(',');
        if (url.startsWith("data:") && comma > 0) {
            String head = url.substring(5, comma);
            String mime = head;
            int semi = head.indexOf(';');
            if (semi >= 0) mime = head.substring(0, semi);
            out.add(Part.image(mime.isEmpty() ? "image/png" : mime, url.substring(comma + 1)));
        } else {
            out.add(Part.image("url:" + url, ""));
        }
    }

    public static String canonicalStop(String raw, boolean hasToolCalls) {
        if (hasToolCalls) return "tool_calls";
        if (raw == null) return "stop";
        String r = raw.toLowerCase();
        if (r.contains("tool")) return "tool_calls";
        if (r.contains("length") || r.contains("max_tokens") || r.contains("max output")) return "length";
        return "stop";
    }
}
