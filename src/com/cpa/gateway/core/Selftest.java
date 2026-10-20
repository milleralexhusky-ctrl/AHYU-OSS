package com.cpa.gateway.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 内置自检：把"网关有没有偷偷把请求削薄"变成可重复的断言。
 *
 * 为什么需要这个东西
 * ------------------
 * 排查"同一个账号直连上游正常、走网关就变笨"时，靠的是一次性的手工验证：
 * 临时搭一个假上游、抓包、逐字段比对。那次的结论是 5 处静默丢字段
 * （tool_choice / response_format / reasoning / URL 图 / Anthropic 形状的图片块），
 * 外加若干一致性缺陷（前缀把换号关掉、非聊天模型混进列表、账号标签漂移）。
 *
 * 这些问题的共同点是：**不报错**。丢了字段，请求照样 200，只是模型表现变差；
 * 所以它们不会在真机测试里暴露，只能靠"主动比对客户端发的和网关转发的"。
 * 手工做过一次的东西就该固化成回归测试，否则下次改协议、加上游还会再踩。
 *
 * 设计取舍
 * --------
 * · **纯进程内、零网络、零额度**。只调用渲染函数（把 canonical 请求渲染成各家 body），
 *   不碰任何真实上游。所以可以在启动时跑，也可以被用户随时点一下。
 * · **不依赖真实账号**。所有断言都用构造出来的样例请求与假账号，
 *   不会因为"今天某个号被限流"而误报。
 * · **失败要能看懂**。每条断言失败时给出期望值与实际值，而不是只说"失败"。
 * · 只覆盖**纯函数**部分。需要真实网络的行为（图片下载、上游探活）不在范围内，
 *   那些仍然要靠假上游抓包，已经在文档里记录了方法。
 */
public final class Selftest {

    private Selftest() {
    }

    /** 一条检查的结果。 */
    public static final class Result {
        public final String name;
        public final boolean ok;
        public final String detail;

        Result(String name, boolean ok, String detail) {
            this.name = name;
            this.ok = ok;
            this.detail = detail;
        }
    }

    /** 跑全部检查。任何一项抛异常都会被记成该项失败，不会影响其它项。 */
    public static List<Result> runAll() {
        List<Result> out = new ArrayList<>();
        add(out, "能力参数透传（OpenAI→OpenAI）", Selftest::checkOpenAiPassthrough);
        add(out, "能力参数透传（Anthropic→Anthropic）", Selftest::checkAnthropicPassthrough);
        add(out, "跨协议翻译 tool_choice（Anthropic→OpenAI）", Selftest::checkCrossToolChoice);
        add(out, "图片形状：OpenAI 与 Anthropic 两种入口都能解析", Selftest::checkImageShapes);
        add(out, "思维链块保真（type/signature 不丢）", Selftest::checkThinkingFidelity);
        add(out, "Anthropic 思考块带必填的 signature", Selftest::checkThinkingSignature);
        add(out, "只有思考时的 content 不是 null", Selftest::checkReasoningOnlyContent);
        add(out, "Gemini generationConfig（思考预算 / 结构化输出）", Selftest::checkGeminiConfig);
        add(out, "限流恢复时刻解析", Selftest::checkResetParsing);
        add(out, "非聊天模型判定", Selftest::checkNonChat);
        add(out, "账号标签稳定（停用不改名）", Selftest::checkLabelStability);
        add(out, "账号前缀识别（换号时能剥掉）", Selftest::checkPrefixStripping);
        add(out, "工具参数修复（撇号不被吃掉）", Selftest::checkToolFixApostrophe);
        add(out, "图片能力按模型判定（不再只看账号）", Selftest::checkModelVision);
        add(out, "限流快速失败只认上游明说的时刻", Selftest::checkConfirmedThrottle);
        add(out, "用量成功只认正常交付的响应", Selftest::checkUsageOutcome);
        add(out, "检测失败归类（业务码里的数字不算 HTTP 状态码）", Selftest::checkDetectClassify);
        add(out, "检测采样跳过非聊天模型", Selftest::checkDetectSampling);
        add(out, "渠道校验重试只认会话标识类错误", Selftest::checkConvRetry);
        add(out, "国际版身份重发与开关", Selftest::checkIntlFallback);
        add(out, "图片 URL 拒绝回环/内网（SSRF）", Selftest::checkImageUrlGuard);
        add(out, "重复请求只按客户端请求计一次", Selftest::checkRepeatCount);
        add(out, "流式解析回放（OpenAI/Anthropic/Gemini）", Selftest::checkStreamReplay);
        add(out, "HTTP 请求回放（chunked 中文/多请求对齐）", Selftest::checkHttpRequestReplay);
        return out;
    }

    /** 跑一遍并把结果写到日志：全过只打一行，有失败就把失败项逐条打出来。 */
    public static List<Result> runAndLog() {
        List<Result> all = runAll();
        int failed = 0;
        StringBuilder bad = new StringBuilder();
        for (Result r : all) {
            if (!r.ok) {
                failed++;
                if (bad.length() > 0) bad.append("；");
                bad.append(r.name).append(" → ").append(r.detail);
            }
        }
        if (failed == 0) {
            Log.i("自检：全部 " + all.size() + " 项通过（能力参数 / 图片 / 思维链 / 标签 / 限流解析）");
        } else {
            Log.e("自检：有 " + failed + "/" + all.size() + " 项失败 —— " + bad);
        }
        return all;
    }

    /* ------------------------------------------------------------------ 框架 */

    private interface Check {
        /** 返回 null 表示通过，否则返回失败原因。 */
        String run() throws Exception;
    }

    private static void add(List<Result> out, String name, Check c) {
        String err;
        try {
            err = c.run();
        } catch (Throwable t) {
            err = "抛出异常：" + t;
        }
        out.add(new Result(name, err == null, err == null ? "通过" : err));
    }

    /**
     * JSON 序列化后的文本，斜杠已还原。
     *
     * 为什么需要：Android 的 `JSONObject.toString()` 会把 `/` 转义成 `\/`
     * （配置里看到过 `https:\/\/anyrouter.top` 就是它干的）。
     * 直接拿含 URL 的字面量去 `contains` 会永远匹配不上 ——
     * 自检第一版就因此报了一次假失败（"图片 URL 丢了"），
     * 而实际上图片是好的。断言必须对序列化细节免疫。
     */
    private static String flat(JSONObject o) {
        return o == null ? "" : o.toString().replace("\\/", "/");
    }

    private static String expect(boolean cond, String what, Object want, Object got) {
        if (cond) return null;
        return what + "：期望 " + want + "，实际 " + got;
    }

    private static String expectHas(JSONObject o, String key) {
        if (o == null) return "渲染结果为空";
        if (!o.has(key)) return "字段 " + key + " 丢失（渲染后的 body 里没有它）";
        return null;
    }

    /* -------------------------------------------------------------- 各项检查 */

    /** OpenAI 客户端 → OpenAI 兼容上游：能力参数必须原样到达。 */
    private static String checkOpenAiPassthrough() throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", "test-model");
        body.put("tool_choice", "required");
        body.put("response_format", new JSONObject().put("type", "json_object"));
        body.put("reasoning_effort", "high");
        body.put("parallel_tool_calls", false);
        body.put("tools", new JSONArray().put(new JSONObject()
                .put("type", "function")
                .put("function", new JSONObject().put("name", "f")
                        .put("parameters", new JSONObject().put("type", "object")))));
        body.put("messages", new JSONArray().put(new JSONObject()
                .put("role", "user").put("content", "hi")));

        Proto.Req req = Fronts.OpenAiFront.parse(body);
        JSONObject out = Upstreams.openAiBody("test-model", req, false);
        String err;
        if ((err = expectHas(out, "tool_choice")) != null) return err;
        if ((err = expectHas(out, "response_format")) != null) return err;
        if ((err = expectHas(out, "reasoning_effort")) != null) return err;
        if ((err = expectHas(out, "parallel_tool_calls")) != null) return err;
        if ((err = expect("required".equals(out.optString("tool_choice", "")),
                "tool_choice 取值", "required", out.opt("tool_choice"))) != null) return err;
        return expect("high".equals(out.optString("reasoning_effort", "")),
                "reasoning_effort 取值", "high", out.opt("reasoning_effort"));
    }

    /** Anthropic 客户端 → Anthropic 上游：tool_choice 与 thinking 必须原样到达。 */
    private static String checkAnthropicPassthrough() throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", "test-model");
        body.put("max_tokens", 100);
        body.put("tool_choice", new JSONObject().put("type", "tool").put("name", "f"));
        body.put("thinking", new JSONObject().put("type", "enabled").put("budget_tokens", 4000));
        body.put("tools", new JSONArray().put(new JSONObject()
                .put("name", "f").put("input_schema", new JSONObject().put("type", "object"))));
        body.put("messages", new JSONArray().put(new JSONObject()
                .put("role", "user").put("content", "hi")));

        Proto.Req req = Fronts.AnthropicFront.parse(body);
        JSONObject out = Upstreams.anthropicBody("test-model", req);
        String err;
        if ((err = expectHas(out, "tool_choice")) != null) return err;
        if ((err = expectHas(out, "thinking")) != null) return err;
        JSONObject think = out.optJSONObject("thinking");
        if (think == null) return "thinking 不是对象：" + out.opt("thinking");
        return expect(think.optInt("budget_tokens", 0) == 4000,
                "thinking.budget_tokens", 4000, think.optInt("budget_tokens", 0));
    }

    /** Anthropic 客户端点名工具 → OpenAI 上游时应翻成 "required"。 */
    private static String checkCrossToolChoice() throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", "test-model");
        body.put("max_tokens", 100);
        body.put("tool_choice", new JSONObject().put("type", "any"));
        body.put("tools", new JSONArray().put(new JSONObject()
                .put("name", "f").put("input_schema", new JSONObject().put("type", "object"))));
        body.put("messages", new JSONArray().put(new JSONObject()
                .put("role", "user").put("content", "hi")));

        Proto.Req req = Fronts.AnthropicFront.parse(body);
        JSONObject out = Upstreams.openAiBody("test-model", req, false);
        String err = expectHas(out, "tool_choice");
        if (err != null) return err;
        return expect("required".equals(out.optString("tool_choice", "")),
                "{type:any} 应翻成 required", "required", out.opt("tool_choice"));
    }

    /**
     * 图片有两种真实存在的客户端写法，任何一个解析不出来都会导致"图发了但模型看不到"：
     *   OpenAI：{"type":"image_url","image_url":{"url":"data:..."}}
     *   Anthropic：{"type":"image","source":{"type":"url"|"base64",...}}
     * 后者曾经被整段漏掉（两个取值分支都取不到），这条断言就是防它回归。
     */
    private static String checkImageShapes() throws Exception {
        String dataUrl = "data:image/png;base64,iVBORw0KGgo=";

        JSONObject oaBody = new JSONObject().put("model", "m").put("messages", new JSONArray()
                .put(new JSONObject().put("role", "user").put("content", new JSONArray()
                        .put(new JSONObject().put("type", "text").put("text", "看图"))
                        .put(new JSONObject().put("type", "image_url")
                                .put("image_url", new JSONObject().put("url", dataUrl))))));
        Proto.Req oa = Fronts.OpenAiFront.parse(oaBody);
        if (countImages(oa) != 1) return "OpenAI 形状的图片没解析出来（图片数=" + countImages(oa) + "）";
        /* 反向也要对：OpenAI 形状的 data URL 渲染给 OpenAI 上游时应仍是 image_url */
        JSONObject oaOut = Upstreams.openAiBody("m", oa, false);
        String oaS = flat(oaOut);
        if (!oaS.contains("image_url")) return "OpenAI 上游 body 里丢了 image_url（图片被丢了）";
        if (!oaS.contains("data:image/png;base64,iVBORw0KGgo=")) return "OpenAI 上游 body 里丢了图片数据";

        JSONObject anBody = new JSONObject().put("model", "m").put("max_tokens", 100)
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user")
                        .put("content", new JSONArray()
                                .put(new JSONObject().put("type", "text").put("text", "看图"))
                                .put(new JSONObject().put("type", "image")
                                        .put("source", new JSONObject()
                                                .put("type", "url")
                                                .put("url", "https://example.com/a.png"))))));
        Proto.Req an = Fronts.AnthropicFront.parse(anBody);
        if (countImages(an) != 1) return "Anthropic 形状（source.url）的图片没解析出来（图片数=" + countImages(an) + "）";

        /* 解析出来还不够：发给 Anthropic 上游时必须仍是 image 块，不能被丢掉 */
        JSONObject rendered = Upstreams.anthropicBody("m", an);
        String s = flat(rendered);
        if (!s.contains("\"image\"")) return "Anthropic 上游 body 里没有 image 块（图片被丢了）";
        if (!s.contains("example.com/a.png")) return "Anthropic 上游 body 里丢了图片 URL";
        return null;
    }

    /** 思维链块必须原样回传：类型还是 thinking，signature 不能掉。 */
    private static String checkThinkingFidelity() throws Exception {
        JSONObject anBody = new JSONObject().put("model", "m").put("max_tokens", 100)
                .put("messages", new JSONArray()
                        .put(new JSONObject().put("role", "user").put("content", "hi"))
                        .put(new JSONObject().put("role", "assistant").put("content", new JSONArray()
                                .put(new JSONObject().put("type", "thinking")
                                        .put("thinking", "让我想想")
                                        .put("signature", "SIG-ABC")))));
        Proto.Req req = Fronts.AnthropicFront.parse(anBody);
        JSONObject out = Upstreams.anthropicBody("m", req);
        String s = flat(out);
        if (!s.contains("\"thinking\"")) return "思维链块被降级成了普通 text（协议要求原样回传）";
        if (!s.contains("SIG-ABC")) return "思维链的 signature 丢了（多轮带工具时上游会 400）";
        return null;
    }

    /**
     * Anthropic 协议的思考块必须带 `signature`（官方 schema 里是必填）。
     *
     * 为什么值得单列一条：思考块**只有推理模型才会产生**，所以这个字段缺失时，
     * 表现是"就那几个推理模型用不了"（kimi-k3 / kimi-k3-1 现象），
     * 而普通模型完全正常 —— 极容易误判成"模型本身有问题"。
     * 用官方 SDK 的客户端（Claude Code 这类）解析缺字段的块会直接报错。
     *
     * 非流式与流式两条路都要查：前者看响应体，后者看有没有 signature_delta。
     */
    private static String checkThinkingSignature() throws Exception {
        Fronts.Collector c = new Fronts.Collector();
        c.thinking("先想一下");
        c.text("答案");
        c.finish("end_turn", null);
        String body = Fronts.AnthropicFront.render("m", c);
        if (body == null || !body.contains("signature")) {
            return "非流式响应的 thinking 块缺少 signature（Anthropic 必填，官方 SDK 解析会失败）";
        }
        if (body.contains("\"thinking\":\"先想一下\"") && body.contains("\"thinking\":\"先想一下\",\"signature\"") == false) {
            /* 顺序无关，这里只在明显缺字段时兜底报错 */
        }
        java.io.StringWriter sw = new java.io.StringWriter();
        java.io.BufferedWriter bw = new java.io.BufferedWriter(sw);
        Fronts.AnthropicFront.Streamer st = new Fronts.AnthropicFront.Streamer(bw, "m");
        st.thinking("先想一下");
        st.text("答案");
        st.finish("end_turn", null);
        bw.flush();
        String sse = sw.toString();
        if (!sse.contains("signature_delta")) {
            return "流式思考块没有 signature_delta（客户端拼出的最终块仍然缺 signature）";
        }
        return null;
    }

    /**
     * 推理模型"只有思考、没有正文"时，`content` 不能是 null。
     *
     * kimi-k3 / kimi-k3-1 这类模型只产出思考内容时，以前会发出
     * `"content": null, "reasoning_content": "…"` —— 严格类型的客户端会把它
     * 判成非法响应（运行失败），而实际上模型是好的。规范里 null 只用于
     * "这一轮在调工具"，这里两种情形要分开。
     */
    private static String checkReasoningOnlyContent() throws Exception {
        Fronts.Collector onlyThinking = new Fronts.Collector();
        onlyThinking.thinking("只想不做");
        onlyThinking.finish("stop", null);
        String body = Fronts.OpenAiFront.render("m", onlyThinking);
        String flat = body == null ? "" : body.replace("\\/", "/");
        if (flat.contains("\"content\":null") || flat.contains("\"content\": null")) {
            return "只有思考时 content 被写成了 null（严格客户端会判为非法响应）";
        }
        if (!flat.contains("\"reasoning_content\"")) {
            return "思考内容没有出现在 reasoning_content 里";
        }
        /* 有工具调用时仍应是 null（规范如此），不能被这条改动带偏 */
        Fronts.Collector withTool = new Fronts.Collector();
        withTool.toolCall("call_1", "f", "{}");
        withTool.finish("tool_use", null);
        String t = Fronts.OpenAiFront.render("m", withTool);
        String tf = t == null ? "" : t.replace("\\/", "/");
        if (!tf.contains("\"content\":null") && !tf.contains("\"content\": null")) {
            return "有工具调用时 content 应为 null（规范要求），现在不是";
        }
        return null;
    }

    /** Gemini 的 thinkingConfig 与结构化输出。 */
    private static String checkGeminiConfig() throws Exception {
        JSONObject gen = new JSONObject();
        gen.put("thinkingConfig", new JSONObject().put("thinkingBudget", 2048));
        gen.put("responseMimeType", "application/json");
        JSONObject body = new JSONObject().put("model", "m").put("generationConfig", gen)
                .put("contents", new JSONArray().put(new JSONObject().put("role", "user")
                        .put("parts", new JSONArray().put(new JSONObject().put("text", "hi")))));
        Proto.Req req = Fronts.GeminiFront.parse("m", body);
        JSONObject out = Upstreams.geminiGenerationConfig(req);
        String err;
        if ((err = expectHas(out, "thinkingConfig")) != null) return err;
        if ((err = expectHas(out, "responseMimeType")) != null) return err;
        return expect("application/json".equals(out.optString("responseMimeType", "")),
                "responseMimeType", "application/json", out.opt("responseMimeType"));
    }

    /** 从上游错误正文里解析恢复时刻（实测见过的那几种写法）。 */
    private static String checkResetParsing() throws Exception {
        long real = Http.parseResetAtMs(
                "您的使用量已超出频率限制，将在 2026-10-16 01:24:11 UTC+8 重置，您也可以切换其他模型继续使用。");
        if (real <= 0) return "没认出中文+UTC+8 的绝对时刻（实测上游就是这么写的）";

        /* 换算校验：UTC+8 的 01:24:11 = UTC 的 17:24:11 */
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        String got = f.format(new java.util.Date(real));
        if (!"2026-10-15 17:24:11".equals(got)) {
            return "UTC+8 换算错了：期望 UTC 2026-10-15 17:24:11，实际 " + got;
        }
        if (Http.parseResetAtMs("429 Too Many Requests") != 0) return "没有时间信息时应当返回 0";
        long rel = Http.parseResetAtMs("Rate limited. Please try again in 20s");
        if (rel <= 0) return "没认出英文相对时长（in 20s）";
        return null;
    }

    /** 生图/生视频/补全类模型必须被判定为非聊天（它们不该出现在对外列表里）。 */
    private static String checkNonChat() {
        String[] nonChat = {"kling-video-v3", "gpt-image-2.5-sunburst", "codewise-completions",
                "qwen-image-3.0-pro", "Hy-Image-3.5-preview", "minimax-video-h3"};
        for (String m : nonChat) {
            if (!Router.nonChat(m)) return "漏判：" + m + " 应当被认定为非聊天模型";
        }
        String[] chat = {"kimi-k3-1", "deepseek-chat", "claude-sonnet-4", "gpt-5", "glm-5.3",
                "doubao-think", "hy3", "Kimi-k2.6"};
        for (String m : chat) {
            if (Router.nonChat(m)) return "误判：" + m + " 是聊天模型，不该被过滤掉";
        }
        return null;
    }

    /**
     * 账号标签必须稳定：停用一个账号后，其它账号的标签不能变。
     * 这是"客户端模型名静默指向另一个账号"那个坑的回归测试。
     */
    private static String checkLabelStability() {
        List<Config.Provider> ps = new ArrayList<>();
        String[] names = {"WorkBuddy 1", "WorkBuddy 2", "WorkBuddy 3", "WorkBuddy 4"};
        for (int i = 0; i < names.length; i++) {
            Config.Provider p = new Config.Provider();
            p.id = "wb" + (i + 1);
            p.name = names[i];
            p.type = Config.TYPE_WORKBUDDY;
            p.enabled = true;
            ps.add(p);
        }
        ShortName.ensureLabels(ps);
        for (int i = 0; i < ps.size(); i++) {
            String want = "WB" + (i + 1);
            if (!want.equals(ps.get(i).label)) {
                return "首次分配不正确：" + ps.get(i).name + " 期望 " + want + "，实际 " + ps.get(i).label;
            }
        }
        /* 停用第一个，再跑一次 ensureLabels：后面的标签必须原封不动 */
        ps.get(0).enabled = false;
        ShortName.ensureLabels(ps);
        for (int i = 1; i < ps.size(); i++) {
            String want = "WB" + (i + 1);
            if (!want.equals(ps.get(i).label)) {
                return "停用第一个账号后 " + ps.get(i).name + " 改名了：期望 " + want + "，实际 " + ps.get(i).label;
            }
        }
        return null;
    }

    /** 客户端写 WB1/kimi-k3-1 时，换号兜底到别的账号必须能识别并剥掉 WB1 前缀。 */
    private static String checkPrefixStripping() {
        List<Config.Provider> ps = new ArrayList<>();
        for (int i = 1; i <= 2; i++) {
            Config.Provider p = new Config.Provider();
            p.id = "wb" + i;
            p.name = "WorkBuddy " + i;
            p.type = Config.TYPE_WORKBUDDY;
            p.enabled = true;
            ps.add(p);
        }
        ShortName.ensureLabels(ps);
        if (!ShortName.isKnownPrefix(ps, "WB1")) return "没认出自家的标签前缀 WB1";
        if (!ShortName.isKnownPrefix(ps, "WorkBuddy 2")) return "没认出自家的账号全名";
        if (!ShortName.isKnownPrefix(ps, "wb2")) return "没认出自家的账号 id";
        if (ShortName.isKnownPrefix(ps, "kimi-k3-1")) return "把模型名误认成账号前缀（会把模型名切坏）";
        return null;
    }

    /**
     * 工具参数里的撇号不能在"修复"时被吃掉。
     *
     * 回归：ToolFix.tidy() 曾经 s.replace('\'', '"') 一把梭，字符串内容里的撇号
     * （it's / don't / shell 命令里的 'x'）会被换成双引号，把字符串从中间截断，
     * 整个 JSON 报废 → tool_call 被整体丢弃，模型表现成"不会调工具"。
     * 而 write_file/shell 这类工具的参数几乎必带撇号，命中率很高。
     *
     * 这里把最常见的三种形状钉死：双引号 JSON 带撇号、单引号 JSON、shell 命令参数。
     */
    private static String checkToolFixApostrophe() throws Exception {
        String[][] cases = {
                {"{\"text\": \"it's fine\"}", "text", "it's fine"},
                {"{'city': '北京'}", "city", "北京"},
                {"{\"cmd\": \"grep 'foo' /etc/hosts\"}", "cmd", "grep 'foo' /etc/hosts"},
                {"{'name': 'O'Neil'}", "name", "O'Neil"},
        };
        for (String[] c : cases) {
            String fixed = ToolFix.repairArgs(c[0]);
            if (fixed == null) {
                return "参数修没了（raw=" + c[0] + "）——这次 tool_call 会被整体丢弃";
            }
            JSONObject o = new JSONObject(fixed);
            String v = o.optString(c[1], "\u0000MISSING");
            if (!c[2].equals(v)) {
                return "字段 " + c[1] + " 期望 [" + c[2] + "]，修复后变成 [" + v + "]（raw=" + c[0] + "）";
            }
        }
        return null;
    }

    /**
     * 图片能力必须按**模型**判定，不能只看账号级开关。
     *
     * 改之前 Router.candidates() 只用 p.vision（账号级），于是"账号整体标了支持图片"
     * 会把一个根本不吃图的模型也放进候选，图发出去必然失败 —— 而且没有任何线索指向
     * "选错了模型"。现在多了一层能力表判定。
     *
     * 断言只做**自洽**校验（拿 ModelCaps 实际返回的能力去对），不写死某个模型的能力值：
     * 能力表来自内置补充 + LiteLLM 数据，且以后可能被覆盖，写死具体模型会让这条
     * 断言在数据更新后假失败。
     */
    private static String checkModelVision() throws Exception {
        Config.Provider p = new Config.Provider();
        p.name = "selftest";
        p.type = Config.TYPE_OPENAI;
        p.vision = true;

        // 1) 账号级关掉图片 → 无论什么模型都不许进候选
        Config.Provider off = new Config.Provider();
        off.name = "selftest-off";
        off.type = Config.TYPE_OPENAI;
        off.vision = false;
        if (Router.modelSeesImages(off, "kimi-k3")) {
            return "账号的「支持图片」已关，但 modelSeesImages 仍返回 true（用户设置被无视）";
        }

        // 2) 能力表里查不到的模型 → 不拦（与旧行为一致，避免本地表缺一条就误杀）
        if (!Router.modelSeesImages(p, "definitely-not-a-real-model-zz9")) {
            return "能力表查不到的模型被拦下了（本地表缺一条不该把路堵死）";
        }

        // 3) 自洽校验：拿能力表里"确实有、且不带图片能力"的模型，要求被拦
        String[] probes = {"hy3", "space-bunny", "summary", "deep-model", "codewise-jump",
                "kimi-k2", "deepseek-chat", "hunyuan-large"};
        boolean foundBad = false;
        for (String m : probes) {
            ModelCaps.Caps caps = ModelCaps.lookup(m);
            if (caps == null || !caps.any()) continue;      // 表里没有 → 跳过
            if (caps.has(ModelCaps.VISION)) continue;       // 表里说有图片 → 不是这条用例
            foundBad = true;
            if (Router.modelSeesImages(p, m)) {
                return "模型 " + m + " 能力表标注为不支持图片，modelSeesImages 却返回 true"
                        + "（带图请求仍会选中它并失败）";
            }
        }
        if (!foundBad) return "没找到任何「能力表有记录但不支持图片」的模型，这条断言失去意义";

        // 4) 反向：能力表里有、且支持图片的模型不能被误拦
        String[] good = {"kimi-k3", "glm-5", "step-3.5-flash", "gpt-5-codex", "doubao"};
        boolean foundGood = false;
        for (String m : good) {
            ModelCaps.Caps caps = ModelCaps.lookup(m);
            if (caps == null || !caps.any()) continue;
            if (!caps.has(ModelCaps.VISION)) continue;
            foundGood = true;
            if (!Router.modelSeesImages(p, m)) {
                return "模型 " + m + " 能力表标注支持图片，modelSeesImages 却返回 false（误杀）";
            }
        }
        if (!foundGood) return "没找到任何「能力表有记录且支持图片」的模型，反向断言失去意义";
        return null;
    }

    /**
     * 快速失败必须**只认上游明说的恢复时刻**，不能拿我们自己估的退避当依据。
     *
     * 背景：两个候选都被上游限流、且上游在正文里写明"将在 05:00:11 重置"时，
     * 在到点前重试是纯粹白跑（实测客户端每 ~10 秒一次、每次撞满两个账号，持续几小时）。
     * 所以这种情形改成直接快速失败。但"429 了先躲 5 分钟"是我们拍的值，
     * 按它拒绝可能把一个其实已经恢复的账号挡在门外 —— 这条断言就是把这两者钉开。
     */
    private static String checkConfirmedThrottle() {
        String key = "selftest-throttle/probe";
        long far = System.currentTimeMillis() + 3600_000L;

        Router.recordThrottled(key, 60_000L, "rate", true, far);
        if (Router.confirmedThrottleUntil(key) <= 0L) {
            return "上游明写了恢复时刻，却没被记成「确认限流」（快速失败会失效）";
        }

        Router.recordThrottled(key, 60_000L, "rate", false, 0L);
        if (Router.confirmedThrottleUntil(key) != 0L) {
            return "上游没给时刻时，我们估的退避被当成了「确认限流」（会把还能用的账号挡在门外）";
        }

        Router.recordThrottled(key, 1000L, "rate", true, System.currentTimeMillis() - 1000L);
        if (Router.confirmedThrottleUntil(key) != 0L) {
            return "恢复时刻已经过去，却仍被当成限流中";
        }
        return null;
    }

    /** 同一连接上先失败再成功不能把「失败」带到下一次；HTTP 200 流内错误不算成功。 */
    private static String checkUsageOutcome() {
        if (Server.usageSuccess(true, true, true)) return "HTTP 200 但流内出错，被错误记为成功";
        if (Server.usageSuccess(false, true, false)) return "处理抛异常，被错误记为成功";
        if (Server.usageSuccess(true, false, false)) return "没找到上游，被错误记为成功";
        if (!Server.usageSuccess(true, true, false)) return "正常响应被错误记为失败";
        return null;
    }

    private static int countImages(Proto.Req req) {
        int n = 0;
        if (req == null) return 0;
        for (Proto.Msg m : req.messages) {
            for (Proto.Part p : m.parts) {
                if (Proto.Part.IMAGE.equals(p.type)) n++;
            }
        }
        return n;
    }

    /**
     * 失败归类不能"整段 contains"。
     *
     * 回归的是 4.29.6 的真机现象：WorkBuddy 一条 HTTP429 + 业务码 14018，
     * 因为 "14018" 里含子串 "401"，弹窗把它显示成"鉴权失效（账号要重新登录）"。
     */
    private static String checkDetectClassify() {
        String rate = "upstream HTTP429 {\"error\":{\"data\":{\"code\":14018,"
                + "\"msg\":\"too many requests, please slow down\"}}}";
        if (!"被限流（稍后自动恢复）".equals(Detect.classify(rate))) {
            return "14018 里的 401 又被当成鉴权了：" + Detect.classify(rate);
        }
        String expired = "upstream HTTP429 {\"error\":{\"data\":{\"code\":14018,"
                + "\"msg\":\"Credential has expired, please sign in again\"}}}";
        if (!"鉴权失效（账号要重新登录）".equals(Detect.classify(expired))) {
            return "上游明说凭据过期却没判鉴权：" + Detect.classify(expired);
        }
        String credits = "upstream HTTP429 {\"error\":{\"data\":{\"code\":14018,\"msg\":\"Credits exhausted. "
                + "Please visit the link below to purchase add-on packs and get more credits\"}}}";
        if (!"额度 / 余额不足（充值或换号，等不会自己好）".equals(Detect.classify(credits))) {
            return "积分耗尽却被当成限流：" + Detect.classify(credits);
        }
        if (Detect.isTransient(credits)) return "额度耗尽不是暂时失败，不该让客户端一直撞";
        String paid = "upstream HTTP401 {\"error\":{\"code\":\"PAID_MODEL_AUTH_REQUIRED\","
                + "\"message\":\"You need to sign in to use this model.\"}}";
        if (!"该模型需要登录 / 付费账号".equals(Detect.classify(paid))) {
            return "付费模型被说成账号要重新登录：" + Detect.classify(paid);
        }
        if (!"鉴权失效（账号要重新登录）".equals(
                Detect.classify("upstream HTTP401 {\"error\":\"invalid token\"}"))) {
            return "真 401 被漏判";
        }
        String channel = "upstream HTTP400 {\"code\":11128,\"msg\":\"conversation id missing\","
                + "\"displayTips\":{\"en\":\"This call failed channel verification, possibly because it was "
                + "not sent from an official client.\"}}";
        if (!"渠道校验拒绝（上游要求官方客户端；已试过国际版身份，仍被拒就换号或重新登录）".equals(Detect.classify(channel))) {
            return "渠道校验没被单独归类：" + Detect.classify(channel);
        }
        String illegal = "upstream HTTP400 {\"code\":11128,\"msg\":\"request illegal\","
                + "\"displayTips\":{\"en\":\"This call failed channel verification\"}}";
        if (!"渠道校验拒绝（上游要求官方客户端；已试过国际版身份，仍被拒就换号或重新登录）".equals(Detect.classify(illegal))) {
            return "request illegal 变体没归到渠道校验：" + Detect.classify(illegal);
        }
        String conv = "upstream HTTP400 {\"code\":11128,\"msg\":\"conversation id missing\"}";
        if (!"渠道校验拒绝（上游要求官方客户端；已试过国际版身份，仍被拒就换号或重新登录）".equals(Detect.classify(conv))) {
            return "11128+conversation id missing 应归渠道校验：" + Detect.classify(conv);
        }
        if (!"上游拒绝这个请求（参数 / 能力不匹配）".equals(
                Detect.classify("upstream HTTP400 {\"code\":11128,\"msg\":\"first message is not system prompt\"}"))) {
            return "11128 其它形态仍是参数类：" + Detect.classify("upstream HTTP400 {\"code\":11128,\"msg\":\"first message is not system prompt\"}");
        }
        if (!Detect.isTransient(rate)) return "限流没算成暂时失败";
        if (Detect.isTransient(conv)) return "conversation id missing 被算成暂时失败";
        if (!Detect.isTransient("并发已满")) return "本地原因（并发已满）不该给模型扣坏标记";
        /*
         * 本地原因现在由 LocalReason 表达，文案可以随便改。
         * 这里用一句**故意和旧文案不同**的文字，验证判断走的是枚举而不是字面量：
         * 如果哪天有人把 Throttled.local 删了、只剩下中文匹配，这条会失败。
         */
        if (!Detect.isTransient(new Throttled(429, 5000L, 0L, "local",
                LocalReason.BUSY, "当前并发已到上限，请稍候"))) {
            return "本地原因（LocalReason.BUSY）没有按枚举判定，仍在依赖中文文案";
        }
        if (!Detect.isTransient(new Throttled(429, 60000L, 0L, "local",
                LocalReason.QUOTA, "今天的额度用完了"))) {
            return "本地原因（LocalReason.QUOTA）没有按枚举判定";
        }
        /* 反例：普通异常（没有 local）仍应走文本匹配，不能一律当暂时失败。 */
        if (Detect.isTransient(new IllegalStateException("conversation id missing"))) {
            return "没有 local 标记的异常被误判成暂时失败";
        }
        if (Detect.isTransient("upstream HTTP403 {\"msg\":\"invalid token\"}")) {
            return "鉴权失效不该只算暂时（否则模型被反复探测，掩盖了账号要重新登录这件事）";
        }
        return null;
    }

    /** 采样不能抽到生视频这类必然失败的模型。 */
    private static String checkDetectSampling() {
        List<String> models = new ArrayList<>();
        models.add("default-model");
        models.add("hy4-preview");
        models.add("minimax-video-h3");
        List<String> picked = Detect.pickModels(models, 2);
        if (picked.size() != 2) return "样本数不对：" + picked.size();
        for (String m : picked) {
            if (Router.nonChat(m)) return "抽样抽到了非聊天模型：" + m;
        }
        List<String> onlyJunk = new ArrayList<>();
        onlyJunk.add("kling-video-v3");
        onlyJunk.add("seedream-4");
        if (Detect.pickModels(onlyJunk, 2).isEmpty()) return "整个目录都是非聊天模型时不该抽空";
        if (!Detect.pickModels(new ArrayList<>(), 2).isEmpty()) return "空目录应当没有样本";
        return null;
    }

    /** 渠道校验重试：形状写入 + 只认"会话标识"这一类错误。 */
    /**
     * 状态机语义：几条"绝不允许"的组合必须不成立。
     *
     * 这些断言守的是 4.30.18–4.30.21 三轮修复的结论——那时靠散落的布尔与中文文案
     * 表达状态，出现过"用户已停止但网关在跑"这种矛盾组合。收成枚举后，
     * 这类组合应该在类型层面就不存在；这条检查防止有人再把独立的布尔加回来。
     */
    private static String checkGatewayState() {
        if (GatewayState.STOPPED_BY_USER.shouldAutoStart()) {
            return "用户主动停止的状态仍允许自动拉起，停止会被保活任务反复推翻";
        }
        if (!GatewayState.STOPPED_IDLE.shouldAutoStart()) {
            return "非用户原因停止的状态不允许自动拉起，意外掉线后不会自愈";
        }
        if (!GatewayState.FAILED.shouldAutoStart()) {
            return "启动失败后不允许自动重试";
        }
        if (GatewayState.RUNNING.shouldAutoStart() || GatewayState.STARTING.shouldAutoStart()) {
            return "运行中/启动中的状态允许自动拉起，会造成重复实例";
        }
        if (!GatewayState.RUNNING.isServing() || GatewayState.STARTING.isServing()) {
            return "isServing 语义错了：只有 RUNNING 才算在服务";
        }
        for (GatewayState st : GatewayState.values()) {
            if (st.label() == null || st.label().isEmpty()) {
                return "状态 " + st + " 没有界面文案";
            }
        }
        return null;
    }

    private static String checkConvRetry() {
        if (!WorkBuddy.looksLikeConvIdError(new IllegalStateException(
                "upstream HTTP 400 {\"code\":11128,\"msg\":\"conversation id missing\"}"))) {
            return "conversation id missing 没被认出来";
        }
        if (!WorkBuddy.looksLikeConvIdError(new IllegalStateException(
                "HTTP 400 channel verification failed"))) {
            return "channel verification 没被认出来";
        }
        if (WorkBuddy.looksLikeConvIdError(new IllegalStateException(
                "upstream HTTP 400 {\"code\":11103,\"msg\":\"Backend [maas] is not supported\"}"))) {
            return "11103（非聊天后端）不该触发渠道校验重试";
        }
        /* 第二轮实验后 shape1..4 是 UA 形状（第十一节的 A/B 已证明改形状治不好
           渠道校验，这些代码只保证"不再重试"的上限逻辑别被破坏）。 */
        org.json.JSONObject body = new org.json.JSONObject();
        java.util.Map<String, String> h = new java.util.HashMap<>();
        WorkBuddy.applyConvShape(1, body, h, "wb-1", null);
        if (!"WorkBuddy/5.5.4 WorkBuddy AI/5.5.4 CLI/2.137.1".equals(h.get("User-Agent"))) {
            return "shape1 没写官方 global 三段式 UA";
        }
        WorkBuddy.applyConvShape(2, body, h, "wb-2", null);
        if (!"WorkBuddy/5.5.4 WorkBuddy/5.5.4 CLI/2.137.1".equals(h.get("User-Agent"))) {
            return "shape2 没写官方 cn 三段式 UA";
        }
        WorkBuddy.applyConvShape(4, body, h, "wb-4", null);
        if (!"wb-4".equals(body.optString("conversationId")) || !"wb-4".equals(h.get("X-Session-ID"))) {
            return "shape4 没写 conversationId / X-Session-ID";
        }
        /* 国际版身份重发：只切换"身份相关"的头，鉴权仍用账号自己的。 */
        WorkBuddy.Account gacc = new WorkBuddy.Account();
        gacc.realm = WorkBuddy.REALM_CN;
        gacc.accessToken = "tok";
        gacc.uid = "uid1";
        gacc.deviceToken = "dev1";
        java.util.Map<String, String> cn = WorkBuddy.headers(gacc, "https://www.codebuddy.cn", null);
        java.util.Map<String, String> gl = WorkBuddy.headers(gacc, WorkBuddy.REALM_GLOBAL,
                "https://www.workbuddy.ai", null);
        if (!"https://www.codebuddy.cn".equals(cn.get("Origin"))
                || !"zh-CN".equals(cn.get("Accept-Language"))) {
            return "国内版身份头不对：" + cn.get("Origin") + " / " + cn.get("Accept-Language");
        }
        if (!"https://www.workbuddy.ai".equals(gl.get("Origin"))
                || !"en-US".equals(gl.get("Accept-Language"))) {
            return "国际版身份没切 Origin / Accept-Language";
        }
        if (!"Bearer tok".equals(gl.get("Authorization")) || !"uid1".equals(gl.get("X-User-Id"))
                || !"dev1".equals(gl.get("X-Device-Token"))) {
            return "国际版身份把账号鉴权字段改掉了（只应切身份相关头）";
        }
        if (WorkBuddy.REALM_GLOBAL.equals(cn.get("User-Agent")) || cn.get("User-Agent").isEmpty()) {
            return "国内版身份没有 UA";
        }
        return null;
    }

    /** 国际版重发的开关与跳过条件。 */
    private static String checkIntlFallback() {
        Config.Provider p = new Config.Provider();
        p.name = "WB-selftest";
        WorkBuddy.Account cn = new WorkBuddy.Account();
        cn.realm = WorkBuddy.REALM_CN;
        if (!WorkBuddy.intlFallbackFor(p, cn)) return "国内号默认应该允许国际版身份重发一次";
        WorkBuddy.Account gl = new WorkBuddy.Account();
        gl.realm = WorkBuddy.REALM_GLOBAL;
        if (WorkBuddy.intlFallbackFor(p, gl)) return "国际版号不该再按国际版身份重发（等于打两遍）";
        try {
            p.options.put("intl_fallback", "false");
        } catch (Exception ignored) {
        }
        if (WorkBuddy.intlFallbackFor(p, cn)) return "intl_fallback=false 没关掉重发";
        return checkOfficialBody();
    }

    /**
     * 4.36.7：出站 body 必须带官方那两个内容派生字段（wb2api 1.6.3 mn.java:793 / tl1.java:59）。
     * 这是"渠道校验"里唯一还没被形状实验覆盖过的变量，所以单独钉一条自检。
     */
    private static String checkOfficialBody() {
        try {
        org.json.JSONArray msgs = new org.json.JSONArray();
        msgs.put(new org.json.JSONObject().put("role", "system").put("content", "sys"));
        msgs.put(new org.json.JSONObject().put("role", "user").put("content", "hi"));
        String cid = WorkBuddy.officialConversationId(msgs);
        // 官方形态：d- 前缀 + 8 字节 base64url（无填充 = 11 字符）→ 共 13
        if (cid.length() != 13 || !cid.startsWith("d-")) {
            return "conversation_id 形态不对（应 d- + 11 字符 = 13）：" + cid;
        }
        String cid2 = WorkBuddy.officialConversationId(msgs);
        if (!cid.equals(cid2)) return "conversation_id 不稳定（同一内容应得同一值）";
        // 换掉 system 内容必须变，否则说明没把 system 拼进去
        msgs.optJSONObject(0).put("content", "sys2");
        if (cid.equals(WorkBuddy.officialConversationId(msgs))) {
            return "conversation_id 没跟着 system 内容变（拼串漏了 system）";
        }
        String ck = WorkBuddy.officialCacheKey("uid1", "deepseek-v4.1-flash");
        // wb2a- + 12 字节 base64url（无填充 = 16 字符）→ 共 21
        if (!ck.startsWith("wb2a-") || ck.length() != 21) {
            return "prompt_cache_key 形态不对（应 wb2a- + 16 字符 = 21）：" + ck;
        }
        return null;
        } catch (Throwable t) {
            return "body 派生字段自检异常：" + t;
        }
    }

    /**
     * 图片 URL 只允许公网 http/https。
     *
     * 用 IP 字面量，不依赖 DNS：这类地址不需要真的发起连接，纯字符串→地址解析即可判定。
     */
    private static String checkImageUrlGuard() {
        String[] blocked = {
                "http://127.0.0.1/x.png",
                "http://localhost/x.png",
                "http://10.0.0.1/x.png",
                "http://172.16.0.1/x.png",
                "http://192.168.1.1/x.png",
                "http://169.254.169.254/latest/meta-data",
                "http://[::1]/x.png",
                "file:///etc/passwd",
                "data:image/png;base64,AAAA",
        };
        for (String u : blocked) {
            if (Upstreams.isPublicHttpUrl(u)) return "没有拦下内网/非 http 地址：" + u;
        }
        if (!Upstreams.isPublicHttpUrl("http://8.8.8.8/x.png")) {
            return "公网 IP 被误拦：http://8.8.8.8/x.png";
        }
        return null;
    }

    /** 重复请求计数：同一个 Req 多候选重试只记一次；内容相同的新 Req 才递增。 */
    private static String checkRepeatCount() {
        /* 每次自检用独立指纹：自检在启动时可能跑两遍，固定内容会从 4 开始计数。 */
        String nonce = "repeat-selftest-" + System.nanoTime();
        Proto.Req a = repeatSample(nonce);
        if (Repeats.note(a) != 1) return "第一次 note 应为 1";
        if (Repeats.note(a) != 1) return "同一个 Req 被重复计数（换号重试会虚增次数）";
        Proto.Req b = repeatSample(nonce);
        if (Repeats.note(b) != 2) return "内容相同的新请求应计 2";
        if (Repeats.check(b) != null) return "第 2 次不该拦";
        Proto.Req c = repeatSample(nonce);
        if (Repeats.note(c) != 3) return "第三次应为 3";
        if (!"3".equals(Repeats.check(c))) return "第 3 次应返回次数 3";
        return null;
    }

    private static Proto.Req repeatSample(String nonce) {
        Proto.Req req = new Proto.Req();
        req.model = "selftest-repeat";
        req.system = nonce;
        Proto.Msg m = new Proto.Msg();
        m.role = "user";
        m.parts.add(Proto.Part.text("同一条内容"));
        req.messages.add(m);
        return req;
    }

    /**
     * HTTP 请求解析回放：chunked 正文里的中文不能被块边界切坏，trailer 要读干净，
     * 同一连接上的下一个请求不能错位。
     *
     * 这三条都是 4.37.0 修过的真实缺陷；用固定字节回放，防止以后改解析时回归。
     */
    private static String checkHttpRequestReplay() {
        try {
            String body = "{\"q\":\"你好\"}";
            byte[] bb = body.getBytes("UTF-8");
            int cut = body.substring(0, body.indexOf('你')).getBytes("UTF-8").length + 1;
            java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
            raw.write(("POST /v1/chat/completions HTTP/1.1\r\n"
                    + "Host: selftest\r\n"
                    + "Transfer-Encoding: chunked\r\n"
                    + "Connection: keep-alive\r\n\r\n").getBytes("UTF-8"));
            chunk(raw, bb, 0, cut);
            chunk(raw, bb, cut, bb.length - cut);
            raw.write(("0\r\nX-Trailer: 1\r\n\r\n").getBytes("UTF-8"));
            raw.write(("GET /health HTTP/1.1\r\nHost: selftest\r\n\r\n").getBytes("UTF-8"));

            /* 必须复用同一个 BufferedInputStream：真实 Server 也是这么做的。
               如果这里传裸流，readRequest 会新包一层，第一次调用结束时把预读到的
               第二个请求一起丢掉——那是测试写法的问题，不是解析器的问题。 */
            java.io.BufferedInputStream in = new java.io.BufferedInputStream(
                    new java.io.ByteArrayInputStream(raw.toByteArray()), 8192);
            Server.Req first = Server.readRequest(in);
            if (first == null) return "第一个请求没解析出来";
            if (!body.equals(first.body)) return "chunked 中文被切坏：" + first.body;
            if (!"/v1/chat/completions".equals(first.path)) return "第一个请求路径不对：" + first.path;
            Server.Req second = Server.readRequest(in);
            if (second == null) return "trailer 读完后第二个请求丢了（连接错位）";
            if (!"/health".equals(second.path)) return "第二个请求路径错位：" + second.path;

            String big = "POST /x HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n"
                    + "900000\r\n";
            try {
                Server.readRequest(new java.io.ByteArrayInputStream(big.getBytes("UTF-8")));
                return "超大 chunk 没有被拒绝";
            } catch (java.io.IOException expected) {
                // 正确：MAX_BODY_BYTES 之外应当拒绝
            }
            return null;
        } catch (Throwable t) {
            return "HTTP 请求回放异常：" + t;
        }
    }

    private static void chunk(java.io.ByteArrayOutputStream out, byte[] data, int off, int len)
            throws Exception {
        out.write((Integer.toHexString(len) + "\r\n").getBytes("UTF-8"));
        out.write(data, off, len);
        out.write("\r\n".getBytes("UTF-8"));
    }

    /* ------------------------------------------------------- 流式解析回放（4.39.0） */

    /**
     * 用本地假上游回放三种 SSE，断言解析器真的把内容/工具/responseId/usage 交出来。
     *
     * 为什么值得：以前改协议只能"再发一次真实请求看看"，既烧额度又不可重复。
     * 这里把一段固定的 SSE 帧喂给真实的解析函数（openAiStream / anthropicStream /
     * GeminiUp.chat），不碰外网，只连 127.0.0.1；失败时能直接指出是哪一种协议、
     * 哪一个字段不对。
     */
    private static String checkStreamReplay() {
        final String openAi = "data: {\"id\":\"chatcmpl-test\",\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\n"
                + "data: {\"id\":\"chatcmpl-test\",\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n\n"
                + "data: {\"id\":\"chatcmpl-test\",\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                + "\"id\":\"call_1\",\"function\":{\"name\":\"bash\",\"arguments\":\"{\\\"cmd\\\":\\\"ls\\\"}\"}}]}}]}\n\n"
                + "data: {\"id\":\"chatcmpl-test\",\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}],"
                + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}\n\n"
                + "data: [DONE]\n\n";
        try {
            final Cap cap = new Cap();
            replay(openAi, url -> Upstreams.openAiStream(url,
                    new java.util.HashMap<String, String>(), new org.json.JSONObject(), cap));
            if (!"你好".equals(cap.text.toString())) return "OpenAI 正文不对：" + cap.text;
            if (!"bash".equals(cap.toolName)) return "OpenAI 工具名没解析出来";
            if (!cap.toolArgs.contains("cmd")) return "OpenAI 工具参数没解析出来：" + cap.toolArgs;
            if (!"chatcmpl-test".equals(cap.rid)) return "OpenAI responseId 没上报：" + cap.rid;
            if (!"tool_calls".equals(cap.stop)) return "OpenAI stop 不对：" + cap.stop;
            if (cap.in != 10 || cap.out != 5) return "OpenAI usage 不对：" + cap.in + "/" + cap.out;
        } catch (Throwable t) {
            return "OpenAI 回放异常：" + t;
        }

        final String anthropic = "event: message_start\n"
                + "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_test\","
                + "\"usage\":{\"input_tokens\":7}}}\n\n"
                + "event: content_block_delta\n"
                + "data: {\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"你好\"}}\n\n"
                + "event: message_delta\n"
                + "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},"
                + "\"usage\":{\"output_tokens\":3}}\n\n";
        try {
            final Cap cap = new Cap();
            replay(anthropic, url -> Upstreams.anthropicStream(url,
                    new java.util.HashMap<String, String>(), new org.json.JSONObject(), cap));
            if (!"你好".equals(cap.text.toString())) return "Anthropic 正文不对：" + cap.text;
            if (!"msg_test".equals(cap.rid)) return "Anthropic responseId 没上报：" + cap.rid;
            if (!"stop".equals(cap.stop)) return "Anthropic stop 不对：" + cap.stop;
            if (cap.in != 7 || cap.out != 3) return "Anthropic usage 不对：" + cap.in + "/" + cap.out;
        } catch (Throwable t) {
            return "Anthropic 回放异常：" + t;
        }

        final String gemini = "data: {\"responseId\":\"gem_test\",\"usageMetadata\":"
                + "{\"promptTokenCount\":4,\"candidatesTokenCount\":2},"
                + "\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"你\"}]}}]}\n\n"
                + "data: {\"responseId\":\"gem_test\",\"candidates\":[{\"content\":"
                + "{\"parts\":[{\"text\":\"好\"}]},\"finishReason\":\"STOP\"}]}\n\n";
        try {
            final Cap cap = new Cap();
            final Config.Provider p = new Config.Provider();
            p.name = "selftest-gemini";
            p.type = Config.TYPE_GEMINI;
            p.apiKey = "";
            final Proto.Req req = new Proto.Req();
            req.model = "m";
            Proto.Msg m = new Proto.Msg();
            m.role = "user";
            m.parts.add(Proto.Part.text("hi"));
            req.messages.add(m);
            replay(gemini, url -> {
                p.baseUrl = url;
                new Upstreams.GeminiUp(p).chat("m", req, cap);
            });
            if (!"你好".equals(cap.text.toString())) return "Gemini 正文不对：" + cap.text;
            if (!"gem_test".equals(cap.rid)) return "Gemini responseId 没上报：" + cap.rid;
            if (!"stop".equals(cap.stop)) return "Gemini stop 不对：" + cap.stop;
            if (cap.in != 4 || cap.out != 2) return "Gemini usage 不对：" + cap.in + "/" + cap.out;
        } catch (Throwable t) {
            return "Gemini 回放异常：" + t;
        }
        return null;
    }

    /** 回放回调允许抛异常。 */
    private interface ReplayCall {
        void run(String url) throws Exception;
    }

    /** 起一个只服务一次的本地 HTTP 假上游，把固定 SSE 交给真实解析函数。 */
    private static void replay(String sse, ReplayCall call) throws Exception {
        java.net.ServerSocket server = new java.net.ServerSocket(
                0, 1, java.net.InetAddress.getByName("127.0.0.1"));
        final int port = server.getLocalPort();
        Thread t = new Thread(() -> {
            try {
                java.net.Socket s = server.accept();
                try {
                    java.io.BufferedReader in = new java.io.BufferedReader(
                            new java.io.InputStreamReader(s.getInputStream(), "UTF-8"));
                    String line;
                    while ((line = in.readLine()) != null && !line.isEmpty()) {
                        // 读掉请求头；body 很小，不读也不会阻塞
                    }
                    byte[] body = sse.getBytes("UTF-8");
                    String head = "HTTP/1.1 200 OK\r\n"
                            + "Content-Type: text/event-stream; charset=utf-8\r\n"
                            + "Connection: close\r\n"
                            + "Content-Length: " + body.length + "\r\n\r\n";
                    java.io.OutputStream out = s.getOutputStream();
                    out.write(head.getBytes("UTF-8"));
                    out.write(body);
                    out.flush();
                } finally {
                    s.close();
                }
            } catch (Throwable ignored) {
            }
        }, "selftest-upstream");
        t.setDaemon(true);
        t.start();
        try {
            call.run("http://127.0.0.1:" + port + "/v1/chat/completions");
        } finally {
            try {
                server.close();
            } catch (Throwable ignored) {
            }
            try {
                t.join(2000);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 收集 Sink 回调，供回放断言。 */
    private static final class Cap implements Proto.Sink {
        final StringBuilder text = new StringBuilder();
        String toolName = "";
        String toolArgs = "";
        String rid = "";
        String stop = "";
        int in;
        int out;

        @Override
        public void text(String delta) {
            if (delta != null) text.append(delta);
        }

        @Override
        public void thinking(String delta) {
        }

        @Override
        public void toolCall(String id, String name, String argsJson) {
            toolName = name == null ? "" : name;
            toolArgs = argsJson == null ? "" : argsJson;
        }

        @Override
        public void finish(String stopReason, Proto.Usage usage) {
            stop = stopReason == null ? "" : stopReason;
            if (usage != null) {
                in = usage.input;
                out = usage.output;
            }
        }

        @Override
        public void responseId(String id) {
            if (rid.isEmpty() && id != null) rid = id;
        }
    }
}
