package com.cpa.gateway.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Protocol front-ends. Each one parses its own request shape into the canonical form and renders
 * upstream output back into that protocol, so one upstream serves OpenAI, Anthropic and Gemini clients.
 */
public final class Fronts {

    private Fronts() {}

    /**
     * 工具参数最后一道闸：坏参数绝不流到客户端。
     * 所有上游（WorkBuddy / Trae / Codex / Antigravity / Anthropic / Gemini /
     * OpenAI 兼容）的 tool_call，最终都经过这四个口子：
     * 非流式的 Collector + 流式的三个 Streamer。
     * 修不好 → 返回 null，调用方直接丢掉这次 tool_call（只记日志，不炸整轮）。
     */
    static String fixToolArgs(String argsJson) {
        String fixed = ToolFix.repairArgs(argsJson);
        if (fixed == null) {
            Log.w("工具参数修不好，已丢弃这次 tool_call："
                    + Upstreams.shortenPublic(argsJson));
        }
        return fixed;
    }

    /** 把入口里未建模的顶层字段复制到 req.extraBody（同协议出口再并回去）。 */
    private static void copyExtra(JSONObject src, JSONObject dst, String... known) {
        if (src == null || dst == null) return;
        java.util.Set<String> skip = new java.util.HashSet<>(
                java.util.Arrays.asList(known));
        java.util.Iterator<String> it = src.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (skip.contains(k)) continue;
            try {
                dst.put(k, src.opt(k));
            } catch (Exception ignored) {
            }
        }
    }

    /* ------------------------------------------------------------- collector */

    /** Non-streaming sink: accumulates the canonical stream so it can be emitted as one JSON body. */
    public static final class Collector implements Proto.Sink {
        private final Persona.Stripper stripper = new Persona.Stripper();
        public final StringBuilder text = new StringBuilder();
        public final StringBuilder thinking = new StringBuilder();
        public final List<Proto.Part> toolCalls = new ArrayList<>();
        public final Proto.Usage usage = new Proto.Usage();
        public String stop = "stop";

        @Override
        public boolean sawToolCall() {
            return !toolCalls.isEmpty();
        }

        @Override
        public String seenText() {
            return text.toString();
        }

        /**
         * 把已累积的正文换掉（4.32.0）。
         *
         * 只给工具补偿用：模型把 tool_call 写在正文里时，那段 JSON 已经进了 text，
         * 转成真正的调用之后必须把它抹掉 —— 否则客户端会同时收到
         * "一段带 JSON 的解释 + 一个调用"，内容重复，而且模型下一轮读到自己那份
         * 正文会以为是它已经说过的话，容易开始自问自答。
         */
        public void replaceText(String s) {
            text.setLength(0);
            if (s != null && !s.isEmpty()) text.append(s);
        }

        @Override
        public void text(String delta) {
            delta = stripper.clean(delta);              // 去掉"我是 XX 模型"
            text.append(delta == null ? "" : delta);
        }

        @Override
        public void thinking(String delta) {
            thinking.append(delta == null ? "" : delta);
        }

        @Override
        public void toolCall(String id, String name, String argsJson) {
            String fixed = fixToolArgs(argsJson);
            if (fixed == null) return;   // 修不好就丢掉，不进列表
            toolCalls.add(Proto.Part.toolCall(id, name, fixed));
        }

        @Override
        public void finish(String stopReason, Proto.Usage u) {
            stop = stopReason == null ? "stop" : stopReason;
            if (u != null) {
                usage.input = u.input;
                usage.output = u.output;
                usage.cached = u.cached;
            }
        }
    }

    /** Same lock the heartbeat uses, so an idle ping can never split an event in half. */
    static void sse(BufferedWriter w, String data) throws Exception {
        synchronized (w) {
            w.write("data: ");
            w.write(data);
            w.write("\n\n");
            w.flush();
        }
    }

    static void sseEvent(BufferedWriter w, String event, String data) throws Exception {
        synchronized (w) {
            w.write("event: ");
            w.write(event);
            w.write("\n");
            w.write("data: ");
            w.write(data);
            w.write("\n\n");
            w.flush();
        }
    }

    static String newId(String prefix) {
        return prefix + Long.toHexString(System.currentTimeMillis()) + Integer.toHexString((int) (Math.random() * 0xffff));
    }

    /* ------------------------------------------------------ OpenAI front-end */

    public static final class OpenAiFront {

        public static Proto.Req parse(JSONObject body) throws Exception {
            Proto.Req req = new Proto.Req();
            req.model = Proto.optString(body, "model", "");
            req.stream = body.optBoolean("stream", false);
            req.protocol = "openai";
            Integer mt = Proto.optInt(body, "max_tokens");
            if (mt == null) mt = Proto.optInt(body, "max_completion_tokens");
            if (mt == null) mt = Proto.optInt(body, "max_output_tokens");
            req.maxTokens = mt;
            req.temperature = Proto.optDouble(body, "temperature");
            req.topP = Proto.optDouble(body, "top_p");
            /* 能力参数：原样留着，出口按目标协议翻译（见 XParams）。
               以前这几个字段连读都没读，是"同一账号走网关就变笨"的主因之一。 */
            Object tcRaw = body.opt("tool_choice");
            if (tcRaw instanceof String || tcRaw instanceof JSONObject) req.toolChoice = tcRaw;
            Object rf = body.opt("response_format");
            if (rf instanceof JSONObject || rf instanceof String) req.responseFormat = rf;
            Object rea = body.opt("reasoning");
            if (rea instanceof JSONObject) req.thinkingParams = rea;
            String eff = Proto.optString(body, "reasoning_effort", "");
            if (!eff.isEmpty()) req.reasoningEffort = eff;
            Boolean ptc = Proto.optBool(body, "parallel_tool_calls");
            req.parallelToolCalls = ptc;
            req.presencePenalty = Proto.optDouble(body, "presence_penalty");
            req.frequencyPenalty = Proto.optDouble(body, "frequency_penalty");
            req.seed = Proto.optInt(body, "seed");
            Object stop = body.opt("stop");
            if (stop instanceof String) req.stop.add((String) stop);
            else if (stop instanceof JSONArray) {
                JSONArray a = (JSONArray) stop;
                for (int i = 0; i < a.length(); i++) if (a.opt(i) != null) req.stop.add(String.valueOf(a.opt(i)));
            }
            StringBuilder sys = new StringBuilder();
            JSONArray msgs = body.optJSONArray("messages");
            if (msgs != null) {
                for (int i = 0; i < msgs.length(); i++) {
                    JSONObject m = msgs.optJSONObject(i);
                    if (m == null) continue;
                    String role = Proto.optString(m, "role", "user");
                    if ("system".equals(role) || "developer".equals(role)) {
                        if (sys.length() > 0) sys.append("\n\n");
                        sys.append(contentToText(m.opt("content")));
                        continue;
                    }
                    Proto.Msg cm = new Proto.Msg(role);
                    Proto.parseOpenAiContent(m.opt("content"), cm.parts);
                    JSONArray tc = m.optJSONArray("tool_calls");
                    if (tc != null) {
                        for (int k = 0; k < tc.length(); k++) {
                            JSONObject t = tc.optJSONObject(k);
                            if (t == null) continue;
                            JSONObject fn = t.optJSONObject("function");
                            cm.add(Proto.Part.toolCall(
                                    Proto.optString(t, "id", ""),
                                    fn == null ? "" : Proto.optString(fn, "name", ""),
                                    fn == null ? "{}" : Proto.optString(fn, "arguments", "{}")));
                        }
                    }
                    /* tool 消息必须保留 tool_call_id —— 无论正文是不是空的。
                       原来只在 parts 为空时才补一个 toolResult，content 非空时
                       id 直接就丢了，上游于是报：
                         An assistant message with 'tool_calls' must be followed by
                         tool messages responding to each 'tool_call_id'.
                         Unexpected tool_call_id:
                       表现是整轮空回复（Trae 上很典型）。 */
                    if ("tool".equals(role)) {
                        String callId = Proto.optString(m, "tool_call_id", "");
                        String toolName = Proto.optString(m, "name", "");
                        String toolBody = contentToText(m.opt("content"));
                        /* 清掉普通 text，改成一个带 id 的 toolResult */
                        cm.parts.clear();
                        cm.add(Proto.Part.toolResult(callId, toolName, toolBody));
                    }
                    if (cm.parts.isEmpty()) cm.add(Proto.Part.text(""));
                    req.messages.add(cm);
                }
            }
            req.system = sys.toString();
            JSONArray tools = body.optJSONArray("tools");
            if (tools != null) {
                for (int i = 0; i < tools.length(); i++) {
                    JSONObject t = tools.optJSONObject(i);
                    if (t == null) continue;
                    JSONObject fn = t.optJSONObject("function");
                    if (fn == null) continue;
                    req.tools.add(new Proto.Tool(
                            Proto.optString(fn, "name", ""),
                            Proto.optString(fn, "description", ""),
                            fn.optJSONObject("parameters")));
                }
            }
            copyExtra(body, req.extraBody,
                    "model", "messages", "stream", "max_tokens", "max_completion_tokens",
                    "max_output_tokens", "temperature", "top_p", "tool_choice",
                    "response_format", "reasoning", "reasoning_effort",
                    "parallel_tool_calls", "presence_penalty", "frequency_penalty",
                    "seed", "stop", "tools", "stream_options");
            return req;
        }

        public static String render(String model, Collector c) throws Exception {
            JSONObject out = new JSONObject();
            out.put("id", newId("chatcmpl-"));
            out.put("object", "chat.completion");
            out.put("created", System.currentTimeMillis() / 1000);
            out.put("model", model);
            JSONObject msg = new JSONObject();
            msg.put("role", "assistant");
            /* content 什么时候可以是 null：
               规范上只有"这一轮是在调工具"时才是 null（OpenAI 明确允许）。
               但如果既没有正文、又没有工具调用 —— 只有一个推理模型的思考内容 ——
               再发 null 就会被严格类型的客户端判成非法响应（"运行失败"），
               而模型是好的、思考内容也在 reasoning_content 里。
               这类"只有思考没有正文"的响应**只有推理模型会产生**，
               所以症状会表现为"就那几个 k3 / k3-1 用不了"。

               所以：有正文给正文；没正文但有工具调用给 null（规范如此）；
               两者都没有时给空串 —— 空串到处都能解析，客户端至少不会崩。 */
            String textNow = c.text.toString();
            if (!textNow.isEmpty()) {
                msg.put("content", textNow);
            } else if (!c.toolCalls.isEmpty()) {
                msg.put("content", JSONObject.NULL);
            } else {
                msg.put("content", "");
            }
            if (c.thinking.length() > 0) msg.put("reasoning_content", c.thinking.toString());
            if (!c.toolCalls.isEmpty()) {
                JSONArray tcs = new JSONArray();
                for (int i = 0; i < c.toolCalls.size(); i++) {
                    Proto.Part t = c.toolCalls.get(i);
                    JSONObject fn = new JSONObject();
                    fn.put("name", t.toolName);
                    fn.put("arguments", t.argsJson);
                    tcs.put(new JSONObject().put("id", t.toolCallId).put("type", "function").put("function", fn));
                }
                msg.put("tool_calls", tcs);
            }
            JSONObject choice = new JSONObject();
            choice.put("index", 0);
            choice.put("message", msg);
            choice.put("finish_reason", c.toolCalls.isEmpty() ? ("length".equals(c.stop) ? "length" : "stop") : "tool_calls");
            out.put("choices", new JSONArray().put(choice));
            out.put("usage", usage(c, true));
            return out.toString();
        }

        public static JSONObject usage(Collector c, boolean openAi) throws Exception {
            JSONObject u = new JSONObject();
            if (openAi) {
                u.put("prompt_tokens", c.usage.input);
                u.put("completion_tokens", c.usage.output);
                u.put("total_tokens", c.usage.total());
                JSONObject pd = new JSONObject();
                pd.put("cached_tokens", c.usage.cached);
                u.put("prompt_tokens_details", pd);
            } else {
                u.put("input_tokens", c.usage.input);
                u.put("output_tokens", c.usage.output);
            }
            return u;
        }

        /** Streaming emitter: chat.completion.chunk events terminated by [DONE]. */
        public static final class Streamer implements Proto.Sink {
            private final BufferedWriter w;
            private final String model;
            private final Persona.Stripper stripper = new Persona.Stripper();
            private final StringBuilder seen = new StringBuilder();
            private final String id = newId("chatcmpl-");
            private final long created = System.currentTimeMillis() / 1000;
            private boolean roleSent;
            private int toolIndex;
            private Proto.Usage usage = new Proto.Usage();
            private String stop = "stop";
            private final boolean[] toolStarted = {false};

            public Streamer(BufferedWriter w, String model) {
                this.w = w;
                this.model = model;
            }

            /**
             * 是否已经往客户端写过任何东西。
             *
             * 用来决定"还能不能换号重试"：一旦有分片出去，客户端已经在按这个流解析，
             * 中途换一个上游把另一份内容接上去只会拼出前后不一致的回复。
             * roleSent 是第一个 chunk 的标记，拿它当"已提交"的判据最保守。
             */
            public boolean committed() {
                return roleSent;
            }

            private void chunk(JSONObject delta, String finishReason) throws Exception {
                JSONObject o = new JSONObject();
                o.put("id", id);
                o.put("object", "chat.completion.chunk");
                o.put("created", created);
                o.put("model", model);
                JSONObject choice = new JSONObject();
                choice.put("index", 0);
                choice.put("delta", delta);
                choice.put("finish_reason", finishReason == null ? JSONObject.NULL : finishReason);
                o.put("choices", new JSONArray().put(choice));
                sse(w, o.toString());
            }

            private JSONObject baseDelta() throws Exception {
                JSONObject d = new JSONObject();
                if (!roleSent) {
                    try {
                        d.put("role", "assistant");
                    } catch (Exception ignored) {
                    }
                    roleSent = true;
                }
                return d;
            }

            @Override
            public boolean sawToolCall() {
                return toolStarted[0];
            }

            @Override
            public String seenText() {
                return seen.toString();
            }

            @Override
            public void text(String delta) {
                try {
                    if (delta != null && seen.length() < 4000) seen.append(delta);   // 原样留一份，判嘴炮用
                    delta = stripper.clean(delta);          // 去掉"我是 XX 模型"
                    if (delta == null || delta.isEmpty()) return;
                    JSONObject d = baseDelta();
                    d.put("content", delta);
                    chunk(d, null);
                } catch (Exception ignored) {
                }
            }

            @Override
            public void thinking(String delta) {
                try {
                    JSONObject d = baseDelta();
                    d.put("reasoning_content", delta);
                    chunk(d, null);
                } catch (Exception ignored) {
                }
            }

            @Override
            public void toolCall(String callId, String name, String argsJson) {
                String fixed = fixToolArgs(argsJson);
                if (fixed == null) return;
                try {
                    JSONObject d = baseDelta();
                    JSONObject fn = new JSONObject();
                    fn.put("name", name);
                    fn.put("arguments", fixed);
                    JSONObject tc = new JSONObject();
                    tc.put("index", toolIndex++);
                    tc.put("id", callId);
                    tc.put("type", "function");
                    tc.put("function", fn);
                    d.put("tool_calls", new JSONArray().put(tc));
                    toolStarted[0] = true;
                    chunk(d, null);
                } catch (Exception ignored) {
                }
            }

            @Override
            public void finish(String stopReason, Proto.Usage u) {
                try {
                    stop = stopReason;
                    if (u != null) usage = u;
                    String fr = "length".equals(stop) ? "length" : (toolStarted[0] ? "tool_calls" : "stop");
                    chunk(new JSONObject(), fr);
                    JSONObject tail = new JSONObject();
                    tail.put("id", id);
                    tail.put("object", "chat.completion.chunk");
                    tail.put("created", created);
                    tail.put("model", model);
                    tail.put("choices", new JSONArray());
                    Collector fake = new Collector();
                    fake.usage.input = usage.input;
                    fake.usage.output = usage.output;
                    fake.usage.cached = usage.cached;
                    tail.put("usage", usage(fake, true));
                    sse(w, tail.toString());
                    w.write("data: [DONE]\n\n");
                    w.flush();
                } catch (Exception ignored) {
                }
            }
        }

        static String contentToText(Object content) {
            if (content == null || content == JSONObject.NULL) return "";
            if (content instanceof String) return (String) content;
            if (content instanceof JSONArray) {
                StringBuilder sb = new StringBuilder();
                JSONArray a = (JSONArray) content;
                for (int i = 0; i < a.length(); i++) {
                    Object o = a.opt(i);
                    if (o instanceof JSONObject) sb.append(Proto.optString((JSONObject) o, "text", ""));
                    else if (o != null) sb.append(o);
                }
                return sb.toString();
            }
            return String.valueOf(content);
        }
    }

    /* ----------------------------------------------------- Ollama front-end */

    /**
     * Ollama 原生协议（4.43.0）。
     *
     * 为什么需要：本地模型生态里大量客户端（Open WebUI、Ollama SDK、各种桌面壳）
     * 只会说 `/api/chat` + NDJSON。它们不是不接受 OpenAI，而是**默认**按 Ollama
     * 的协议连接；不给这个入口，用户就得改客户端或加一层转换。
     *
     * 做法：把 Ollama 请求翻译成 OpenAI body，复用已经稳定的
     * {@link OpenAiFront#parse}。工具调用 id 在 Ollama 历史里可能缺失，
     * 这里按顺序补齐，保证 RequestCheck 的配对校验能过。
     */
    public static final class OllamaFront {

        private OllamaFront() {
        }

        public static Proto.Req parse(JSONObject body) throws Exception {
            return OpenAiFront.parse(toOpenAiBody(body));
        }

        public static JSONObject toOpenAiBody(JSONObject body) throws Exception {
            JSONObject out = new JSONObject();
            out.put("model", Proto.optString(body, "model", ""));
            /* Ollama 的 stream 默认是 true（不是 OpenAI 的 false）。 */
            out.put("stream", body.optBoolean("stream", true));

            JSONObject opts = body.optJSONObject("options");
            if (opts != null) {
                if (opts.has("temperature")) out.put("temperature", opts.opt("temperature"));
                if (opts.has("top_p")) out.put("top_p", opts.opt("top_p"));
                if (opts.has("num_predict")) out.put("max_tokens", opts.opt("num_predict"));
                Object stop = opts.opt("stop");
                if (stop != null) out.put("stop", stop);
            }

            JSONArray msgs = body.optJSONArray("messages");
            JSONArray outMsgs = new JSONArray();
            java.util.Map<String, java.util.ArrayDeque<String>> pendingIds =
                    new java.util.HashMap<>();
            int seq = 0;
            if (msgs != null) {
                for (int i = 0; i < msgs.length(); i++) {
                    JSONObject m = msgs.optJSONObject(i);
                    if (m == null) continue;
                    String role = Proto.optString(m, "role", "user");
                    JSONObject om = new JSONObject().put("role", role);

                    Object content = m.opt("content");
                    String text = content instanceof String ? (String) content : "";
                    JSONArray images = m.optJSONArray("images");
                    if (images != null && images.length() > 0) {
                        JSONArray parts = new JSONArray();
                        if (!text.isEmpty()) {
                            parts.put(new JSONObject().put("type", "text").put("text", text));
                        }
                        for (int k = 0; k < images.length(); k++) {
                            String b64 = images.optString(k, "");
                            if (b64.isEmpty()) continue;
                            String mime = b64.startsWith("/9j/") ? "image/jpeg" : "image/png";
                            parts.put(new JSONObject()
                                    .put("type", "image_url")
                                    .put("image_url", new JSONObject()
                                            .put("url", "data:" + mime + ";base64," + b64)));
                        }
                        om.put("content", parts);
                    } else if (content != null) {
                        om.put("content", content);
                    }

                    JSONArray tcs = m.optJSONArray("tool_calls");
                    if (tcs != null && tcs.length() > 0) {
                        JSONArray otcs = new JSONArray();
                        for (int k = 0; k < tcs.length(); k++) {
                            JSONObject tc = tcs.optJSONObject(k);
                            if (tc == null) continue;
                            JSONObject fn = tc.optJSONObject("function");
                            String name = fn == null ? "" : Proto.optString(fn, "name", "");
                            Object args = fn == null ? null : fn.opt("arguments");
                            String argsStr = args instanceof String ? (String) args
                                    : (args == null ? "{}" : args.toString());
                            String id = Proto.optString(tc, "id", "");
                            if (id.isEmpty()) id = "call_" + (seq++);
                            java.util.ArrayDeque<String> q = pendingIds.get(name);
                            if (q == null) {
                                q = new java.util.ArrayDeque<>();
                                pendingIds.put(name, q);
                            }
                            q.addLast(id);
                            otcs.put(new JSONObject()
                                    .put("id", id)
                                    .put("type", "function")
                                    .put("function", new JSONObject()
                                            .put("name", name)
                                            .put("arguments", argsStr)));
                        }
                        om.put("tool_calls", otcs);
                        if (!om.has("content")) om.put("content", JSONObject.NULL);
                    }

                    if ("tool".equals(role)) {
                        String id = Proto.optString(m, "tool_call_id", "");
                        String name = Proto.optString(m, "tool_name",
                                Proto.optString(m, "name", ""));
                        if (id.isEmpty()) {
                            java.util.ArrayDeque<String> q = pendingIds.get(name);
                            if (q != null) id = q.pollFirst();
                            if (id == null) id = "";
                        }
                        om.put("tool_call_id", id);
                        if (!name.isEmpty()) om.put("name", name);
                    }
                    outMsgs.put(om);
                }
            }
            out.put("messages", outMsgs);
            JSONArray tools = body.optJSONArray("tools");
            if (tools != null) out.put("tools", tools);
            return out;
        }

        /** 非流式响应：把 Collector 渲染成 Ollama 的 /api/chat 形状。 */
        public static JSONObject render(String model, Collector c) throws Exception {
            JSONObject out = new JSONObject();
            out.put("model", model);
            out.put("created_at", java.time.Instant.now().toString());
            JSONObject msg = new JSONObject();
            msg.put("role", "assistant");
            msg.put("content", c.text == null ? "" : c.text.toString());
            if (c.thinking != null && c.thinking.length() > 0) {
                msg.put("thinking", c.thinking.toString());
            }
            JSONArray calls = toolCallsJson(c.toolCalls);
            if (calls.length() > 0) msg.put("tool_calls", calls);
            out.put("message", msg);
            out.put("done", true);
            out.put("done_reason", doneReason(c.stop));
            out.put("prompt_eval_count", c.usage.input);
            out.put("eval_count", c.usage.output);
            return out;
        }

        /** 非流式 /api/generate 响应形状。 */
        public static JSONObject renderGenerate(String model, Collector c) throws Exception {
            JSONObject out = new JSONObject();
            out.put("model", model);
            out.put("created_at", java.time.Instant.now().toString());
            out.put("response", c.text == null ? "" : c.text.toString());
            out.put("done", true);
            out.put("done_reason", doneReason(c.stop));
            out.put("prompt_eval_count", c.usage.input);
            out.put("eval_count", c.usage.output);
            return out;
        }

        static JSONArray toolCallsJson(List<Proto.Part> calls) throws Exception {
            JSONArray arr = new JSONArray();
            if (calls == null) return arr;
            for (Proto.Part p : calls) {
                if (p == null) continue;
                JSONObject fn = new JSONObject();
                fn.put("name", p.toolName == null ? "" : p.toolName);
                try {
                    fn.put("arguments", new JSONObject(p.argsJson == null ? "{}" : p.argsJson));
                } catch (Exception badArgs) {
                    fn.put("arguments", new JSONObject());
                }
                arr.put(new JSONObject().put("function", fn));
            }
            return arr;
        }

        static String doneReason(String canonical) {
            return "length".equals(canonical) ? "length" : "stop";
        }
    }

    /** 流式：每个 delta 写一行 NDJSON，最后一行 done=true。 */
    public static final class OllamaStreamer implements Proto.Sink {
        private final BufferedWriter w;
        private final String model;
        private final boolean generate;
        private final String createdAt = java.time.Instant.now().toString();
        private final List<Proto.Part> calls = new ArrayList<>();
        private Proto.Usage usage = new Proto.Usage();
        private boolean done;

        public OllamaStreamer(BufferedWriter w, String model) {
            this(w, model, false);
        }

        public OllamaStreamer(BufferedWriter w, String model, boolean generate) {
            this.w = w;
            this.model = model;
            this.generate = generate;
        }

        @Override
        public void text(String delta) {
            if (delta == null || delta.isEmpty()) return;
            if (generate) {
                writeGenerate(delta, false, null);
                return;
            }
            try {
                writeMessage(new JSONObject().put("role", "assistant").put("content", delta), false, null);
            } catch (Throwable ignored) {
            }
        }

        @Override
        public void thinking(String delta) {
            if (delta == null || delta.isEmpty() || generate) return;
            try {
                writeMessage(new JSONObject().put("role", "assistant").put("thinking", delta), false, null);
            } catch (Throwable ignored) {
            }
        }

        @Override
        public void toolCall(String id, String name, String argsJson) {
            calls.add(Proto.Part.toolCall(id, name, argsJson));
        }

        @Override
        public void finish(String stopReason, Proto.Usage u) {
            if (u != null) usage = u;
            if (done) return;
            done = true;
            try {
                if (generate) {
                    writeGenerate("", true, stopReason);
                    return;
                }
                JSONObject msg = new JSONObject();
                msg.put("role", "assistant");
                msg.put("content", "");
                JSONArray arr = OllamaFront.toolCallsJson(calls);
                if (arr.length() > 0) msg.put("tool_calls", arr);
                writeMessage(msg, true, stopReason);
            } catch (Throwable ignored) {
            }
        }

        /** 流内错误：Ollama 客户端认 {"error":"..."} 这一行。 */
        public void error(String message) {
            if (done) return;
            done = true;
            try {
                JSONObject o = new JSONObject();
                o.put("error", message == null ? "upstream error" : message);
                w.write(o.toString());
                w.write("\n");
                w.flush();
            } catch (Throwable ignored) {
            }
        }

        private void writeMessage(JSONObject message, boolean doneFlag, String stopReason) {
            try {
                JSONObject o = new JSONObject();
                o.put("model", model);
                o.put("created_at", createdAt);
                o.put("message", message);
                o.put("done", doneFlag);
                if (doneFlag) {
                    o.put("done_reason", OllamaFront.doneReason(Proto.canonicalStop(stopReason, !calls.isEmpty())));
                    o.put("prompt_eval_count", usage.input);
                    o.put("eval_count", usage.output);
                }
                w.write(o.toString());
                w.write("\n");
                w.flush();
            } catch (Throwable ignored) {
            }
        }

        private void writeGenerate(String response, boolean doneFlag, String stopReason) {
            try {
                JSONObject o = new JSONObject();
                o.put("model", model);
                o.put("created_at", createdAt);
                o.put("response", response == null ? "" : response);
                o.put("done", doneFlag);
                if (doneFlag) {
                    o.put("done_reason", OllamaFront.doneReason(Proto.canonicalStop(stopReason, false)));
                    o.put("prompt_eval_count", usage.input);
                    o.put("eval_count", usage.output);
                }
                w.write(o.toString());
                w.write("\n");
                w.flush();
            } catch (Throwable ignored) {
            }
        }
    }

    /* --------------------------------------------------- Anthropic front-end */

    public static final class AnthropicFront {

        public static Proto.Req parse(JSONObject body) throws Exception {
            Proto.Req req = new Proto.Req();
            req.model = Proto.optString(body, "model", "");
            req.stream = body.optBoolean("stream", false);
            req.protocol = "anthropic";
            req.maxTokens = Proto.optInt(body, "max_tokens");
            req.temperature = Proto.optDouble(body, "temperature");
            req.topP = Proto.optDouble(body, "top_p");
            /* tool_choice 与 thinking：Anthropic 侧这两个直接决定"能不能用工具""要不要思考"。
               thinking 不传的后果最明显 —— 客户端明明开了 extended thinking，
               走网关却完全不思考，用户看到的就是"同一个模型变笨了"。 */
            Object atc = body.opt("tool_choice");
            if (atc instanceof JSONObject || atc instanceof String) req.toolChoice = atc;
            Object ath = body.opt("thinking");
            if (ath instanceof JSONObject) req.thinkingParams = ath;
            Object sys = body.opt("system");
            req.system = OpenAiFront.contentToText(sys);
            JSONArray stops = body.optJSONArray("stop_sequences");
            if (stops != null) for (int i = 0; i < stops.length(); i++) req.stop.add(String.valueOf(stops.opt(i)));
            JSONArray msgs = body.optJSONArray("messages");
            if (msgs != null) {
                for (int i = 0; i < msgs.length(); i++) {
                    JSONObject m = msgs.optJSONObject(i);
                    if (m == null) continue;
                    Proto.Msg cm = new Proto.Msg(Proto.optString(m, "role", "user"));
                    Object content = m.opt("content");
                    if (content instanceof String) {
                        cm.add(Proto.Part.text((String) content));
                    } else {
                        Proto.parseOpenAiContent(content, cm.parts);
                        JSONArray blocks = content instanceof JSONArray ? (JSONArray) content : null;
                        if (blocks != null) {
                            for (int k = 0; k < blocks.length(); k++) {
                                JSONObject b = blocks.optJSONObject(k);
                                if (b == null) continue;
                                String type = Proto.optString(b, "type", "");
                                if ("tool_use".equals(type)) {
                                    JSONObject input = b.optJSONObject("input");
                                    cm.add(Proto.Part.toolCall(Proto.optString(b, "id", ""), Proto.optString(b, "name", ""),
                                            input == null ? "{}" : input.toString()));
                                }
                            }
                            // drop the plain-text duplicates produced by the generic parser for tool_use blocks
                            List<Proto.Part> keep = new ArrayList<>();
                            for (int k = 0; k < cm.parts.size(); k++) {
                                Proto.Part p = cm.parts.get(k);
                                boolean isToolUseText = false;
                                if (Proto.Part.TEXT.equals(p.type) && (p.text == null || p.text.isEmpty())) isToolUseText = true;
                                if (!isToolUseText) keep.add(p);
                            }
                            cm.parts.clear();
                            cm.parts.addAll(keep);
                        }
                    }
                    if (cm.parts.isEmpty()) cm.add(Proto.Part.text(""));
                    req.messages.add(cm);
                }
            }
            JSONArray tools = body.optJSONArray("tools");
            if (tools != null) {
                for (int i = 0; i < tools.length(); i++) {
                    JSONObject t = tools.optJSONObject(i);
                    if (t == null) continue;
                    req.tools.add(new Proto.Tool(Proto.optString(t, "name", ""), Proto.optString(t, "description", ""),
                            t.optJSONObject("input_schema")));
                }
            }
            copyExtra(body, req.extraBody,
                    "model", "messages", "stream", "max_tokens", "temperature", "top_p",
                    "tool_choice", "thinking", "system", "stop_sequences", "tools");
            return req;
        }

        static String stopReason(String canonical) {
            if ("length".equals(canonical)) return "max_tokens";
            if ("tool_calls".equals(canonical)) return "tool_use";
            return "end_turn";
        }

        public static String render(String model, Collector c) throws Exception {
            JSONObject out = new JSONObject();
            out.put("id", newId("msg_"));
            out.put("type", "message");
            out.put("role", "assistant");
            out.put("model", model);
            JSONArray content = new JSONArray();
            if (c.thinking.length() > 0) {
                /* signature 在 Anthropic 协议里是**必填**字段。
                   以前这里只发 thinking，没有 signature —— 用官方 SDK 的客户端
                   （Claude Code 这类）解析时会因为字段缺失直接报错，
                   表现就是"模型运行失败"。

                   为什么以前没暴露：思考块**只有推理模型才会产生**。
                   普通模型走不到这一行，所以只有 kimi-k3 / kimi-k3-1 这类
                   "先思考后作答"的模型会踩到 —— 现象就是"就这两个模型用不了"。

                   这里发空串而不是伪造签名：空串能通过类型校验（SDK 里是 string），
                   而伪造的签名会被真正的上游拒绝。网关这条路的上游并不校验签名，
                   所以空串是安全且够用的。 */
                content.put(new JSONObject().put("type", "thinking")
                        .put("thinking", c.thinking.toString())
                        .put("signature", ""));
            }
            if (c.text.length() > 0) {
                content.put(new JSONObject().put("type", "text").put("text", c.text.toString()));
            }
            for (Proto.Part t : c.toolCalls) {
                Object input;
                try {
                    input = new JSONObject(t.argsJson == null || t.argsJson.isEmpty() ? "{}" : t.argsJson);
                } catch (Exception e) {
                    input = new JSONObject();
                }
                content.put(new JSONObject().put("type", "tool_use").put("id", t.toolCallId).put("name", t.toolName).put("input", input));
            }
            out.put("content", content);
            out.put("stop_reason", stopReason(c.stop));
            out.put("stop_sequence", JSONObject.NULL);
            JSONObject u = new JSONObject();
            u.put("input_tokens", c.usage.input);
            u.put("output_tokens", c.usage.output);
            u.put("cache_read_input_tokens", c.usage.cached);
            out.put("usage", u);
            return out.toString();
        }

        public static final class Streamer implements Proto.Sink {
            private final BufferedWriter w;
            private final String model;
            private final String id = newId("msg_");
            private int index = -1;
            private String openType;
            private Proto.Usage usage = new Proto.Usage();
            private String stop = "stop";
            private final StringBuilder toolArgs = new StringBuilder();
            private String toolName = "";
            private String toolId = "";

            public Streamer(BufferedWriter w, String model) {
                this.w = w;
                this.model = model;
            }

            public void start() throws Exception {
                JSONObject msg = new JSONObject();
                msg.put("id", id);
                msg.put("type", "message");
                msg.put("role", "assistant");
                msg.put("model", model);
                msg.put("content", new JSONArray());
                msg.put("stop_reason", JSONObject.NULL);
                msg.put("stop_sequence", JSONObject.NULL);
                JSONObject u = new JSONObject();
                u.put("input_tokens", 0);
                u.put("output_tokens", 0);
                msg.put("usage", u);
                JSONObject ev = new JSONObject();
                ev.put("type", "message_start");
                ev.put("message", msg);
                sseEvent(w, "message_start", ev.toString());
            }

            private void openBlock(String type, JSONObject extra) throws Exception {
                closeBlockIfOpen();
                index++;
                JSONObject block = new JSONObject();
                block.put("type", type);
                if (extra != null) {
                    java.util.Iterator<String> it = extra.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        block.put(k, extra.opt(k));
                    }
                }
                JSONObject ev = new JSONObject();
                ev.put("type", "content_block_start");
                ev.put("index", index);
                ev.put("content_block", block);
                sseEvent(w, "content_block_start", ev.toString());
                openType = type;
            }

            private void closeBlockIfOpen() throws Exception {
                if (openType != null) {
                    /* 关闭思考块前补一个 signature_delta（空签名）。
                       官方流里签名是这个时机下发的，客户端在 content_block_stop 时
                       才把签名并进完整块；缺了它，最终块仍然没有 signature，
                       一样过不了校验。 */
                    if ("thinking".equals(openType)) {
                        delta("signature_delta", new JSONObject()
                                .put("type", "signature_delta").put("signature", ""));
                    }
                    JSONObject ev = new JSONObject();
                    ev.put("type", "content_block_stop");
                    ev.put("index", index);
                    sseEvent(w, "content_block_stop", ev.toString());
                    openType = null;
                }
            }

            private void delta(String type, JSONObject d) throws Exception {
                JSONObject ev = new JSONObject();
                ev.put("type", "content_block_delta");
                ev.put("index", index);
                ev.put("delta", d);
                sseEvent(w, "content_block_delta", ev.toString());
            }

            @Override
            public void text(String d) {
                try {
                    if (!"text".equals(openType)) openBlock("text", new JSONObject().put("text", ""));
                    delta("text_delta", new JSONObject().put("type", "text_delta").put("text", d));
                } catch (Exception ignored) {
                }
            }

            @Override
            public void thinking(String d) {
                try {
                    if (!"thinking".equals(openType)) {
                        /* 起始块就带上 signature：非流式那条必须带，流式这里也一并给上，
                           免得客户端按"起始块 + signature_delta"两处取值时拿到 undefined。 */
                        openBlock("thinking", new JSONObject().put("thinking", "").put("signature", ""));
                    }
                    delta("thinking_delta", new JSONObject().put("type", "thinking_delta").put("thinking", d));
                } catch (Exception ignored) {
                }
            }

            @Override
            public void toolCall(String callId, String name, String argsJson) {
                String fixed = fixToolArgs(argsJson);
                if (fixed == null) return;
                try {
                    toolId = callId;
                    toolName = name;
                    JSONObject extra = new JSONObject();
                    extra.put("id", callId);
                    extra.put("name", name);
                    extra.put("input", new JSONObject());
                    openBlock("tool_use", extra);
                    JSONObject d = new JSONObject();
                    d.put("type", "input_json_delta");
                    d.put("partial_json", fixed);
                    delta("input_json_delta", d);
                } catch (Exception ignored) {
                }
            }

            @Override
            public void finish(String stopReason, Proto.Usage u) {
                try {
                    stop = stopReason;
                    if (u != null) usage = u;
                    closeBlockIfOpen();
                    JSONObject md = new JSONObject();
                    md.put("type", "message_delta");
                    JSONObject d = new JSONObject();
                    d.put("stop_reason", stopReason(stop));
                    d.put("stop_sequence", JSONObject.NULL);
                    md.put("delta", d);
                    JSONObject uu = new JSONObject();
                    uu.put("input_tokens", usage.input);
                    uu.put("output_tokens", usage.output);
                    md.put("usage", uu);
                    sseEvent(w, "message_delta", md.toString());
                    sseEvent(w, "message_stop", new JSONObject().put("type", "message_stop").toString());
                } catch (Exception ignored) {
                }
            }
        }
    }

    /* ------------------------------------------------------ Gemini front-end */

    public static final class GeminiFront {

        public static Proto.Req parse(String model, JSONObject body) throws Exception {
            Proto.Req req = new Proto.Req();
            req.model = model;
            req.stream = false;
            req.protocol = "gemini";
            JSONArray contents = body.optJSONArray("contents");
            if (contents != null) {
                for (int i = 0; i < contents.length(); i++) {
                    JSONObject c = contents.optJSONObject(i);
                    if (c == null) continue;
                    String role = Proto.optString(c, "role", "user");
                    Proto.Msg cm = new Proto.Msg("model".equals(role) ? "assistant" : role);
                    JSONArray parts = c.optJSONArray("parts");
                    if (parts != null) {
                        for (int k = 0; k < parts.length(); k++) {
                            JSONObject p = parts.optJSONObject(k);
                            if (p == null) continue;
                            String t = Proto.optString(p, "text", "");
                            JSONObject inline = p.optJSONObject("inline_data");
                            if (inline == null) inline = p.optJSONObject("inlineData");
                            JSONObject fc = p.optJSONObject("functionCall");
                            JSONObject fr = p.optJSONObject("functionResponse");
                            if (inline != null) {
                                cm.add(Proto.Part.image(Proto.optString(inline, "mime_type", Proto.optString(inline, "mimeType", "image/png")),
                                        Proto.optString(inline, "data", "")));
                            } else if (fc != null) {
                                JSONObject args = fc.optJSONObject("args");
                                /* 新版 Gemini 的 functionCall 可能带 id；带上它，下一轮
                                   functionResponse 才能按 id 配对（没有就沿用本地生成的）。 */
                                String fcId = Proto.optString(fc, "id", "call_" + i + "_" + k);
                                cm.add(Proto.Part.toolCall(fcId, Proto.optString(fc, "name", ""),
                                        args == null ? "{}" : args.toString()));
                            } else if (fr != null) {
                                Object resp = fr.opt("response");
                                /* 以前这里固定写空 id，导致工具结果无法和调用配对；
                                   有些客户端/上游会带 id，能读到就保留。 */
                                String frId = Proto.optString(fr, "id",
                                        Proto.optString(fr, "tool_call_id", ""));
                                cm.add(Proto.Part.toolResult(frId, Proto.optString(fr, "name", ""),
                                        resp == null ? "" : String.valueOf(resp)));
                            } else if (!t.isEmpty()) {
                                cm.add(Proto.Part.text(t));
                            }
                        }
                    }
                    if (cm.parts.isEmpty()) cm.add(Proto.Part.text(""));
                    req.messages.add(cm);
                }
            }
            JSONObject sys = body.optJSONObject("systemInstruction");
            if (sys == null) sys = body.optJSONObject("system_instruction");
            if (sys != null) {
                JSONArray parts = sys.optJSONArray("parts");
                StringBuilder sb = new StringBuilder();
                if (parts != null) {
                    for (int i = 0; i < parts.length(); i++) {
                        JSONObject p = parts.optJSONObject(i);
                        if (p != null) sb.append(Proto.optString(p, "text", ""));
                    }
                } else {
                    sb.append(Proto.optString(sys, "text", ""));
                }
                req.system = sb.toString();
            }
            JSONObject gen = body.optJSONObject("generationConfig");
            if (gen == null) gen = body.optJSONObject("generation_config");
            if (gen != null) {
                req.maxTokens = Proto.optInt(gen, "maxOutputTokens");
                if (req.maxTokens == null) req.maxTokens = Proto.optInt(gen, "max_output_tokens");
                req.temperature = Proto.optDouble(gen, "temperature");
                req.topP = Proto.optDouble(gen, "topP");
                JSONArray stops = gen.optJSONArray("stopSequences");
                if (stops != null) for (int i = 0; i < stops.length(); i++) req.stop.add(String.valueOf(stops.opt(i)));
                /* 2.5 系列的思考预算与结构化输出都挂在这里，以前整段被忽略：
                   thinkingConfig 丢掉 = 模型不思考（降智），
                   responseMimeType 丢掉 = JSON 输出失效。 */
                JSONObject tcfg = gen.optJSONObject("thinkingConfig");
                if (tcfg != null) req.thinkingParams = tcfg;
                String mime = Proto.optString(gen, "responseMimeType", "");
                JSONObject schema = gen.optJSONObject("responseSchema");
                if (!mime.isEmpty() || schema != null) {
                    JSONObject rf = new JSONObject();
                    if (!mime.isEmpty()) rf.put("responseMimeType", mime);
                    if (schema != null) rf.put("responseSchema", schema);
                    req.responseFormat = rf;
                }
            }
            JSONObject toolCfg = body.optJSONObject("toolConfig");
            if (toolCfg != null) {
                Object fc = toolCfg.opt("functionCallingConfig");
                if (fc instanceof JSONObject) req.toolChoice = fc;
            }
            JSONArray tools = body.optJSONArray("tools");
            if (tools != null) {
                for (int i = 0; i < tools.length(); i++) {
                    JSONObject t = tools.optJSONObject(i);
                    if (t == null) continue;
                    JSONArray decls = t.optJSONArray("functionDeclarations");
                    if (decls == null) continue;
                    for (int k = 0; k < decls.length(); k++) {
                        JSONObject d = decls.optJSONObject(k);
                        if (d == null) continue;
                        req.tools.add(new Proto.Tool(Proto.optString(d, "name", ""), Proto.optString(d, "description", ""),
                                d.optJSONObject("parameters")));
                    }
                }
            }
            copyExtra(body, req.extraBody,
                    "contents", "systemInstruction", "system_instruction",
                    "generationConfig", "generation_config", "tools", "toolConfig");
            if (gen != null) {
                copyExtra(gen, req.extraGenerationConfig,
                        "maxOutputTokens", "max_output_tokens", "temperature", "topP",
                        "stopSequences", "thinkingConfig", "responseMimeType",
                        "responseSchema");
            }
            return req;
        }

        static String finishReason(String canonical) {
            if ("length".equals(canonical)) return "MAX_TOKENS";
            if ("tool_calls".equals(canonical)) return "STOP";
            return "STOP";
        }

        public static String render(String model, Collector c) throws Exception {
            JSONObject out = new JSONObject();
            JSONObject cand = new JSONObject();
            JSONObject content = new JSONObject();
            JSONArray parts = new JSONArray();
            if (c.thinking.length() > 0) parts.put(new JSONObject().put("text", c.thinking.toString()).put("thought", true));
            if (c.text.length() > 0) parts.put(new JSONObject().put("text", c.text.toString()));
            for (Proto.Part t : c.toolCalls) {
                Object args;
                try {
                    args = new JSONObject(t.argsJson == null || t.argsJson.isEmpty() ? "{}" : t.argsJson);
                } catch (Exception e) {
                    args = new JSONObject();
                }
                parts.put(new JSONObject().put("functionCall", new JSONObject().put("name", t.toolName).put("args", args)));
            }
            content.put("parts", parts);
            content.put("role", "model");
            cand.put("content", content);
            cand.put("finishReason", finishReason(c.stop));
            cand.put("index", 0);
            out.put("candidates", new JSONArray().put(cand));
            out.put("model", model);
            JSONObject um = new JSONObject();
            um.put("promptTokenCount", c.usage.input);
            um.put("candidatesTokenCount", c.usage.output);
            um.put("totalTokenCount", c.usage.total());
            out.put("usageMetadata", um);
            return out.toString();
        }

        public static final class Streamer implements Proto.Sink {
            private final BufferedWriter w;
            private final String model;
            private Proto.Usage usage = new Proto.Usage();
            private String stop = "stop";

            public Streamer(BufferedWriter w, String model) {
                this.w = w;
                this.model = model;
            }

            private void emit(JSONArray parts, boolean last) throws Exception {
                JSONObject out = new JSONObject();
                JSONObject cand = new JSONObject();
                if (parts.length() > 0) {
                    JSONObject content = new JSONObject();
                    content.put("parts", parts);
                    content.put("role", "model");
                    cand.put("content", content);
                }
                cand.put("index", 0);
                if (last) cand.put("finishReason", finishReason(stop));
                out.put("candidates", new JSONArray().put(cand));
                out.put("model", model);
                if (last) {
                    JSONObject um = new JSONObject();
                    um.put("promptTokenCount", usage.input);
                    um.put("candidatesTokenCount", usage.output);
                    um.put("totalTokenCount", usage.total());
                    out.put("usageMetadata", um);
                }
                sse(w, out.toString());
            }

            @Override
            public void text(String d) {
                try {
                    emit(new JSONArray().put(new JSONObject().put("text", d)), false);
                } catch (Exception ignored) {
                }
            }

            @Override
            public void thinking(String d) {
                try {
                    emit(new JSONArray().put(new JSONObject().put("text", d).put("thought", true)), false);
                } catch (Exception ignored) {
                }
            }

            @Override
            public void toolCall(String callId, String name, String argsJson) {
                String fixed = fixToolArgs(argsJson);
                if (fixed == null) return;
                try {
                    Object args;
                    try {
                        args = new JSONObject(fixed);
                    } catch (Exception e) {
                        args = new JSONObject();
                    }
                    emit(new JSONArray().put(new JSONObject().put("functionCall",
                            new JSONObject().put("name", name).put("args", args))), false);
                } catch (Exception ignored) {
                }
            }

            @Override
            public void finish(String stopReason, Proto.Usage u) {
                try {
                    stop = stopReason;
                    if (u != null) usage = u;
                    emit(new JSONArray(), true);
                } catch (Exception ignored) {
                }
            }
        }
    }
}
