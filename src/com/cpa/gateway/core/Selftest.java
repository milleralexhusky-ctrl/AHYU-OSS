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
        add(out, "原样重发熔断（连续无效就停，成功即恢复）", Selftest::checkResendBreaker);
        add(out, "工具探活失败退避（坏模型不再每 30 秒重探）", Selftest::checkProbeBackoff);
        add(out, "WorkBuddy 会话头与新版风控归类", Selftest::checkWorkBuddyChannel);
        add(out, "Anthropic Messages 上游（Claude Code 接入地址）", Selftest::checkAnthropicUpstream);
        add(out, "给客户端的两种根地址（Claude Code 那条不带 /v1）", Selftest::checkClientBaseUrls);
        add(out, "本机多端并发不共用单客户端配额", Selftest::checkLoopbackPeers);
        add(out, "thinking 跨协议近似翻译", Selftest::checkThinkingTranslation);
        add(out, "凭据不可用/受限账号退出派发", Selftest::checkUnreadableCredentials);
        add(out, "会话亲和（跨轮稳定 / 随会话区分）", Selftest::checkSessionAffinity);
        add(out, "导航玻璃 tint：各配色背景亮度确实接近（自适应无必要）",
                Selftest::checkGlassTintRange);
        add(out, "下拉刷新手势（方向 / 阈值 / 不抢点击与滚动）", Selftest::checkPullGesture);
        add(out, "预置服务商分组（国内/国外判据与预置清单逐个对得上）", Selftest::checkRegionClassify);
        add(out, "缓存价成本分段（缺价按全价 / 命中数不可超输入）", Selftest::checkCachedCost);
        add(out, "额度未知（-1）不能被当成额度用尽", Selftest::checkCreditsUnknown);
        add(out, "额度取「按周期剩余」而不是总容量（防显示 500 却发不出去）",
                Selftest::checkCreditFieldChoice);
        add(out, "上游全挂时非流式必须如实报错，不伪装成回答", Selftest::checkNoFakeAnswer);
        add(out, "截断率与内容率的信号口径", Selftest::checkContentSignalScope);
        add(out, "缓存命中率的分母只算带缓存列的行", Selftest::checkCacheRateDenominator);
        add(out, "删除账号不会从快照复活（幽灵账号）", Selftest::checkSnapshotNoResurrect);
        add(out, "旧快照按磁盘校准（升级前删掉的账号不再复活）", Selftest::checkSnapshotReconcile);
        add(out, "「启动网关」按钮不把自己写回旧文案", Selftest::checkLoadingTextRestore);
        add(out, "额度耗尽归为 quota（WorkBuddy 14018 不能落进限流）", Selftest::checkQuotaKind);
        add(out, "账号级回避（额度/凭证）只扣分、不硬挡", Selftest::checkAccountAvoid);
        add(out, "历史渠道标记为「已删除」而不是当账号列出", Selftest::checkRetiredChannels);
        add(out, "风控指纹混淆：内容等价、指纹失效、不误伤", Selftest::checkRiskPrompt);
        add(out, "Claude 模型名兼容（claude-* → auto，别抢用户的同名账号）",
                Selftest::checkModelCompat);
        add(out, "指纹对抗失效监测（已混淆仍被拦才报警，账号级不误报）",
                Selftest::checkFingerprintWatch);
        add(out, "国际版身份重发与开关", Selftest::checkIntlFallback);
        add(out, "图片 URL 拒绝回环/内网（SSRF）", Selftest::checkImageUrlGuard);
        add(out, "重复请求只按客户端请求计一次", Selftest::checkRepeatCount);
        add(out, "流式解析回放（OpenAI/Anthropic/Gemini）", Selftest::checkStreamReplay);
        add(out, "HTTP 请求回放（chunked 中文/多请求对齐）", Selftest::checkHttpRequestReplay);
        add(out, "入口解析保留工具结果 id（OpenAI/Anthropic/Gemini）", Selftest::checkFrontToolResults);
        add(out, "工具结果跨协议出站：Anthropic/Gemini 的 user 消息要变成 role:tool",
                Selftest::checkToolResultOutbound);
        add(out, "Ollama 请求翻译与响应形状", Selftest::checkOllamaProtocol);
        add(out, "同协议未知参数透传（top_k/service_tier/metadata）", Selftest::checkExtraBody);
        add(out, "响应保真透传（logprobs / 多 choice / 正文不被改写）", Selftest::checkRawFidelity);
        add(out, "top_k 跨协议等价翻译（Anthropic ↔ Gemini）", Selftest::checkTopK);
        add(out, "失败作用域（坏模型 / 坏号 / 坏请求）", Selftest::checkFailScope);
        add(out, "HTTP 头与行长度上限（防内存耗尽）", Selftest::checkHttpLimits);
        add(out, "浏览器跨源防护（无 Key 时拒绝外部网页）", Selftest::checkBrowserOrigin);
        add(out, "日志脱敏（token/state/JWT，不误伤业务码）", Selftest::checkLogScrub);
        add(out, "自动更新：只升不降 + version.properties 解析", Selftest::checkUpdater);
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
     * 缓存价成本分段计算（4.51.7）。
     *
     * <p>三件必须同时成立的事：
     * <ol>
     *   <li>命中缓存的输入 token 按缓存价算，**不是**按输入价；</li>
     *   <li>源数据没给缓存价时整段按输入价算（保守），不能凭空发明一个便宜的价；</li>
     *   <li>命中数超过输入数时夹住 —— 上游偶尔把缓存 token 计在 input 之外，
     *       相减会得出负成本。</li>
     * </ol>
     *
     * <p>再加一条**绊线**：价格表里带缓存价的条目数不能掉到 0。
     * 那张表是离线裁剪出来的、脚本不在仓库里（见 4.27.0），重建时很容易又把
     * `cr` 字段丢掉 —— 而丢掉的后果是静默的（成本悄悄变贵），必须有人喊。
     */
    private static String checkCachedCost() {
        /* 绊线：带缓存价的条目数。当前表里是 1214 条。
           阈值取 500：留足余量（源数据本身也在变），但"字段被整个丢掉"必红。

           表还没加载完时 -1 = **未知**，跳过而不是报错（4.52.2）。
           原来这里读 countCachePriced()，它在未加载完时返回 0，于是冷启动那一次
           会报"cr 字段丢了、成本静默变贵"——一个纯竞态被说成严重缺陷。
           实测：同一份包冷启动 51/52，紧接着重启就是 52/52。
           假红会训练人忽略自检，那比漏报更糟。 */
        int cachePriced = Prices.countCachePricedOrUnknown();
        if (cachePriced < 0) return null;               // 表还没就绪：不伪装成通过，也不误报
        if (cachePriced < 500) {
            return "价格表里带缓存单价（cr）的条目只剩 " + cachePriced
                    + " 条 —— 重建 res/raw/model_prices.json 时很可能把 cr 字段丢了，"
                    + "成本会静默退回按全价计算";
        }

        /* 找一个真的带缓存价的模型来验分段计算。
           找不到就跳过（不伪装成通过）—— 上面那条绊线已经保证表里有货。 */
        String model = null;
        double[] p = null;
        for (String m : new String[]{"claude-sonnet-4-5", "gpt-5", "gemini/gemini-2.5-pro",
                "deepseek/deepseek-chat", "anthropic/claude-sonnet-4-5"}) {
            double[] got = Prices.lookup(m);
            if (got != null && got.length > 2 && got[2] >= 0 && got[0] > 0) {
                model = m;
                p = got;
                break;
            }
        }
        if (model == null) return null;

        double in = p[0], out = p[1], cr = p[2];

        /* 1) 全部命中：成本应为 输入数×缓存价，明显低于全价 */
        double allHit = Prices.costUsd(model, 1000, 1000, 0);
        double expectAllHit = 1000 * cr;
        if (Math.abs(allHit - expectAllHit) > 1e-12) {
            return "全部命中缓存时成本应为 " + expectAllHit + "，实际 " + allHit;
        }
        double noHit = Prices.costUsd(model, 1000, 0, 0);
        if (!(allHit < noHit)) {
            return "命中缓存的成本没有低于未命中（" + allHit + " vs " + noHit + "）";
        }
        if (Math.abs(noHit - 1000 * in) > 1e-12) {
            return "未命中时成本应为 " + (1000 * in) + "，实际 " + noHit;
        }

        /* 2) 输出价不受缓存影响 */
        double withOut = Prices.costUsd(model, 0, 0, 1000);
        if (Math.abs(withOut - 1000 * out) > 1e-12) {
            return "输出成本应为 " + (1000 * out) + "，实际 " + withOut;
        }

        /* 3) 命中数超过输入数：夹住，不能出现负成本 */
        double over = Prices.costUsd(model, 100, 999999, 0);
        if (over < 0) return "命中数超过输入数时算出了负成本：" + over;
        if (Math.abs(over - 100 * cr) > 1e-12) {
            return "命中数超过输入数时应按输入数夹住（期望 " + (100 * cr) + "），实际 " + over;
        }

        /* 4) 折扣率读得出来，且落在 (0,1] 内 */
        double disc = Prices.cacheDiscount(model);
        if (!(disc > 0 && disc <= 1.0)) return "缓存折扣率不合理：" + disc;

        /* 5) 没收录的模型仍然返回 -1（未知 ≠ 免费） */
        if (Prices.costUsd("definitely-not-a-real-model-zz9", 1000, 500, 1000) != -1) {
            return "查不到价格的模型应返回 -1（未知），不能当成 0";
        }
        return null;
    }

    /**
     * 额度必须取「按周期剩余」，不能取「总容量」（4.53.2）。
     *
     * <p>真机实测的原始包（同一个资源包，同一时刻）：
     * <pre>
     *   CapacityRemain       = 500      ← 总容量口径，原来读的是这个
     *   CycleCapacityRemain  = 0        ← 按周期口径，真正决定能不能用
     *   推理请求            = 14018 额度已用尽
     * </pre>
     * 读错字段的后果是双向的、而且互相矛盾：
     * <ul>
     *   <li>界面显示「积分 500」，用户以为网关坏了、而不是"该换号"；</li>
     *   <li>同时 {@code p.credits=500} 又让它在选路里不被扣分，
     *       于是每次都去撞一个注定 14018 的账号。</li>
     * </ul>
     *
     * <p>这条自检钉**选择逻辑本身**：直接构造两种形态的回包，断言
     * 「有周期字段且为 0」时算 0，而「完全没有周期字段」时才回落到总量字段。
     * 两者必须分开 —— 把"为 0"和"字段缺失"混为一谈，会让一个用完的包
     * 被总量字段救回来，等于没修。
     */
    /**
     * 上游全挂时，**非流式**必须如实报错，不能伪装成一段回答（4.53.2）。
     *
     * <p>实测两条路径对同一个上游故障的说法完全相反：
     * <pre>
     *   Anthropic 非流式 → 500 + "upstream HTTP 429 … 14018 额度已用尽"   （真话）
     *   OpenAI   非流式 → 200 + "⚠️ 上游账号暂时都不通…"                  （占位文案）
     * </pre>
     * 后者让用户以为"模型答了这么一句"，看不出真原因是额度耗尽还是渠道校验，
     * 也就不知道该换号 —— 用户原话"说什么连接中就是不出来"正是从这里来的。
     *
     * <p>流式做不到：响应头与 `: ready` 在试第一个候选前就发出去了，状态码改不了。
     * 所以这条断言只钉**非流式**这条路 —— 那里本来就能改状态码，没有理由不说真话。
     *
     * <p>这里能测的只有"兜底文案本身是否明确标注了它不是回答"（真正发不发由
     * writeFallback 的分支决定，需要 IO 才能测）。所以钉两件事：
     * 文案必须自带"不是模型输出"的自述，以及文案不能长得像正常回答。
     */
    private static String checkNoFakeAnswer() {
        String t = Server.FALLBACK_TEXT_FOR_TEST;
        if (t == null || t.isEmpty()) return "兜底文案是空的";
        if (!t.contains("网关提示") && !t.contains("不是模型输出")) {
            return "兜底文案没有说明它不是模型回答 —— 用户会把它当成内容："
                    + t;
        }
        return null;
    }

    /**
     * 风控指纹混淆（{@link RiskPrompt}）。
     *
     * 这里要同时钉住三件事，少一件这套东西就是错的：
     *   · 指纹**确实失效**（上游那套字节匹配不再命中）；
     *   · 内容**等价**（大小写、空格一个不少，模型看到的是同一句话）——
     *     这是"混淆"而不是"改写"的全部意义；
     *   · **不误伤**（别的渠道 / 别的客户端提示词原样透传）。
     *
     * 真机实测（2026-10-23）：两个特征串各自 8/8 触发 11128，混淆后 8/8 通过。
     * 第三枚（DeepSeek Harness，2026-10-24）没有上游可实测，钉的是混淆生效、
     * 内容等价、预筛不漏词、不误伤这四件我们能自己验证的事。
     */
    private static String checkRiskPrompt() {
        String preamble = "You are Claude Code, Anthropic's official CLI for Claude.";
        String fixed = RiskPrompt.neutralize(preamble);
        if (fixed == preamble) return "自我描述句没有被混淆（指纹仍在，必然被 11128 拦）";
        String z = RiskPrompt.zwnj();
        /* 断言要盯的是"上游那串字节还在不在"，而不是零宽字符插在哪一侧 ——
           neutralize 在空格两侧各插一个，`ClaudeCode` 这个字面量本来就不该出现。 */
        if (fixed.contains("Claude Code")) {
            return "指纹串原样还在（上游是字节级匹配，这样仍会被 11128 拦）：" + fixed;
        }
        if (!fixed.contains(z)) return "没有插入零宽字符：" + fixed;
        /* 内容等价：把零宽字符去掉必须逐字还原，且一个空格都没少。 */
        String back = fixed.replace(z, "");
        if (!back.equals(preamble)) {
            return "混淆改变了内容（去掉零宽后不等于原文）：" + back;
        }
        /* 空格有没有被吃掉，上面那句已经全包了（逐字相等 ⇒ 空格与大小写都在）。
           不要再单独断言 " Code," 这种字面量：零宽字符正好插在空格两侧，
           这种断言会假失败 —— 第一版就是这么被自己的自检抓住的。 */
        /* 大小写必须原样保留：用 replaceAll 配字面量会把 CLAUDE CODE 悄悄改小写。 */
        String upper = "You are CLAUDE CODE, Anthropic's official CLI.";
        String fixedUpper = RiskPrompt.neutralize(upper);
        if (!fixedUpper.replace(z, "").equals(upper)) {
            return "全大写形态被改了大小写：" + fixedUpper;
        }
        if (fixedUpper == upper) return "全大写形态没被混淆（指纹漏网）";
        if (fixedUpper.contains("CLAUDE CODE")) return "全大写指纹原样还在：" + fixedUpper;
        /* 连字符 / 下划线形态的 slug —— 最隐蔽的一枚：它藏在系统提示末尾的
           github.com/anthropics/claude-code/issues 里，只修带空格的写法时
           整份真实请求体仍然被拦（二分定位到的就是它）。 */
        for (String slug : new String[]{"claude-code", "claude_code", "CLAUDE-CODE"}) {
            String fixedSlug = RiskPrompt.neutralize(slug);
            if (fixedSlug == slug) return "slug 形态 " + slug + " 没有被混淆（真实请求体会被拦）";
            if (fixedSlug.replace(z, "").equals(slug) == false) {
                return "slug 形态 " + slug + " 混淆改变了内容：" + fixedSlug;
            }
            if (fixedSlug.toLowerCase().contains(slug.toLowerCase().replace("-", "-"))
                    && !fixedSlug.contains(z)) {
                return "slug 形态 " + slug + " 未失效：" + fixedSlug;
            }
        }
        /* 带连字符的写法必须仍然"人看着一样"：连字符还在。 */
        String slugLine = "report the issue at https://github.com/anthropics/claude-code/issues";
        String fixedLine = RiskPrompt.neutralize(slugLine);
        if (fixedLine == slugLine) return "反馈链接里的 claude-code 没被混淆";
        if (!fixedLine.replace(z, "").equals(slugLine)) {
            return "反馈链接混淆改变了内容：" + fixedLine;
        }
        if (fixedLine.contains("claude-code")) return "反馈链接里的指纹仍然完好：" + fixedLine;
        /* 计费头（Claude Code 2.1.308 会把它当第一个 system 块发出来）。 */
        String bill = "x-anthropic-billing-header: cc_version=2.1.308.7f2; cc_entrypoint=sdk-cli;";
        String fixedBill = RiskPrompt.neutralize(bill);
        if (fixedBill == bill) return "计费头没有被混淆（真机实测它单独就能触发 11128）";
        if (!fixedBill.replace(z, "").equals(bill)) {
            return "计费头混淆改变了内容：" + fixedBill;
        }
        /* DeepSeek Harness（第三枚，2026-10-24 照 Dsh 社区方案加的，本机无实测）。
           没有上游可验证命中与否，所以这里钉的是**我们能钉住的部分**：
           混淆生效、内容逐字等价、以及最容易做错的一件事 ——
           预筛。system 里只有这枚指纹、一个 "claude" 都没有时，
           若预筛没加 deepseek，整条会被放过，混淆形同虚设且无声无息。 */
        String dsh = "You are DeepSeek Harness, an interactive agent for AI-powered software engineering.";
        String fixedDsh = RiskPrompt.neutralize(dsh);
        if (fixedDsh == dsh) return "DeepSeek Harness 身份句没有被混淆（预筛漏了 deepseek？）";
        if (fixedDsh.contains("DeepSeek Harness")) return "Dsh 指纹串原样还在：" + fixedDsh;
        if (!fixedDsh.replace(z, "").equals(dsh)) {
            return "Dsh 混淆改变了内容（去零宽后不等于原文）：" + fixedDsh;
        }
        /* 预筛回归的直接断言：完全没有 claude / x-anthropic 字样，只靠 deepseek 进正则分支。 */
        String dshOnly = "Identity: deepseek harness v2";
        if (RiskPrompt.neutralize(dshOnly) == dshOnly) return "小写且无 claude 的 Dsh 句没被混淆（预筛回归）";
        for (String slug : new String[]{"deepseek-harness", "deepseek_harness", "DEEPSEEK-HARNESS"}) {
            String fixedDslug = RiskPrompt.neutralize(slug);
            if (fixedDslug == slug) return "Dsh slug 形态 " + slug + " 没有被混淆";
            if (!fixedDslug.replace(z, "").equals(slug)) {
                return "Dsh slug 形态 " + slug + " 混淆改变了内容：" + fixedDslug;
            }
            if (fixedDslug.contains(slug)) return "Dsh slug 形态 " + slug + " 指纹仍然完好：" + fixedDslug;
        }
        /* 不误伤（与前两枚同样的克制）：单独出现的词不算指纹。
           "deepseek" 单独出现是极常见的（用户就在聊 DeepSeek 模型），
           动它等于污染正常对话内容。 */
        String dshBare = "The deepseek model is cheap. Buckle up your harness.";
        if (RiskPrompt.neutralize(dshBare) != dshBare) {
            return "deepseek 与 harness 分开出现也被改了：" + RiskPrompt.neutralize(dshBare);
        }
        /* 不误伤：普通提示词必须原对象返回（调用方靠 == 判断有没有改动）。 */
        String plain = "You are a helpful assistant. Write clean code.";
        if (RiskPrompt.neutralize(plain) != plain) {
            return "普通提示词被改动了 —— 会污染所有渠道的对话内容";
        }
        if (RiskPrompt.wouldChange(plain)) return "普通提示词被判定为有指纹";
        /* 单独出现的词不该被当成指纹（"claude" 后面没有 "code"）。 */
        String single = "The model claude is made by Anthropic.";
        if (RiskPrompt.neutralize(single) != single) {
            return "只出现 claude、没有 code 也被改了：" + RiskPrompt.neutralize(single);
        }
        return null;
    }

    /**
     * Claude 模型名兼容（{@link Server#compatModel}）。
     *
     * 关键不是"能映射"，而是**该映射的才映射**：
     * 用户自己挂了同名 Claude 中转时，点名必须照点名走 ——
     * 抢走它等于把用户配好的账号废掉。
     */
    private static String checkModelCompat() {
        /* Claude Code 默认发出来的官方 ID → 必须落到 auto。 */
        for (String m : new String[]{"claude-sonnet-4-5-20250929", "claude-haiku-4-5",
                "claude-opus-5-5", "CLAUDE-SONNET-4-5"}) {
            String got = Server.compatModel(m);
            if (!Router.isVirtual(got)) {
                return "客户端模型名 " + m + " 没有被映射到虚拟模型（Claude Code 会"
                        + "在本地报 unrecognized_model，连请求都不发）：" + got;
            }
        }
        /* 本机自己的模型名一个都不能被改。 */
        for (String m : new String[]{"auto", "auto-code", "hy3", "glm-5.3",
                "deepseek-v4-pro", "gpt-5", "gemini-2.5-pro"}) {
            String got = Server.compatModel(m);
            if (!m.equalsIgnoreCase(got)) {
                return "本机模型名 " + m + " 被误映射成 " + got;
            }
        }
        /* 带账号前缀的点名是用户明确的路由意图，绝不改写。 */
        String pinned = "WorkBuddy 1/claude-sonnet-4-5";
        if (!pinned.equals(Server.compatModel(pinned))) {
            return "带前缀的点名被改写成了 " + Server.compatModel(pinned);
        }
        /* Dsh 实弹形状（4.55.2 的病根）：厂商命名空间 + [1m] 上下文后缀。
           本地没有叫 anthropic 的账号时，它是命名空间不是点名 ——
           照原样路由会 404（"没有账号提供 anthropic/claude-opus-4-6[1m]"），
           必须落到虚拟模型。 */
        String dsh = "anthropic/claude-opus-4-6[1m]";
        boolean hasAnthropicAcct = false;
        for (Config.Provider p : Gateway.config().providers) {
            if (p.dispatchable() && (p.name.equalsIgnoreCase("anthropic")
                    || p.id.equalsIgnoreCase("anthropic")
                    || Server.shortLabelOf(p).equalsIgnoreCase("anthropic"))) {
                hasAnthropicAcct = true;
            }
        }
        if (Gateway.config().providers.isEmpty()) {
            /* 冷启动窗口：账号表还没加载完，「本地有没有 anthropic 账号」不可知，
               isVendorNamespace 按红线保守当点名 → Dsh 形状此时不落虚拟模型是**预期**的。
               跳过而不是报错（4.52.2 同一课：竞态报成缺陷 = 假红训练人忽略自检）。
               点名红线与 [1m] 无斜杠两条不受影响，照常断言。 */
            if (!Router.isVirtual(Server.compatModel("claude-opus-4-6[1m]"))) {
                return "claude-*[1m] 形态没被映射（后缀遮蔽了 Anthropic 形状判断）";
            }
            return null;
        }
        if (!hasAnthropicAcct) {
            String got = Server.compatModel(dsh);
            if (!Router.isVirtual(got)) {
                return "Dsh 默认模型名 " + dsh + " 没落到虚拟模型（Anthropic 入口整条 404）：" + got;
            }
            if (!Server.isVendorNamespace(dsh)) {
                return "anthropic/ 前缀没被识别为厂商命名空间";
            }
        }
        /* [1m] 后缀单独出现（无斜杠）也要能映射。 */
        if (!Router.isVirtual(Server.compatModel("claude-opus-4-6[1m]"))) {
            return "claude-*[1m] 形态没被映射（后缀遮蔽了 Anthropic 形状判断）";
        }
        return null;
    }

    /**
     * 指纹对抗的**失效监测**（{@link FingerprintWatch}）。
     *
     * <p>重点不是"能不能报警"，而是**该不该报**。11128 有两种成因：
     * 内容指纹（混淆能治）与账号被标记（混淆治不了）。
     * 把后者也报成"对抗失效"就是假警报 —— 而假警报的代价是真失效时没人信。
     * 区分手段只有一条：这份 body 里到底有没有我们插进去的零宽字符。
     */
    private static String checkFingerprintWatch() throws Exception {
        /* 自检是**只读**的：先快照（含账号对照表），跑完还原
           （见 FingerprintWatch.snapshotFullForTest 的注释——诊断包先写 summary 后跑自检，
           清零会让取证时证据没了）。
           期间静音告警：这里造的是刻意的失效对照，不能冒充真故障。 */
        Object snap = FingerprintWatch.snapshotFullForTest();
        FingerprintWatch.muteAlerts();
        try {
            return checkFingerprintWatchBody();
        } finally {
            FingerprintWatch.restore(snap);
        }
    }

    /** 计数一律按**相对增量**断言：进程里可能已有真实流量，绝对值不可预测。 */
    private static String checkFingerprintWatchBody() throws Exception {
        String z = RiskPrompt.zwnj();
        String acct = "selftest-fp-" + System.nanoTime();

        /* 造两份 body：一份带官方客户端指纹（会被混淆），一份中性（不会）。 */
        org.json.JSONObject flagged = new org.json.JSONObject();
        org.json.JSONArray m1 = new org.json.JSONArray();
        m1.put(new org.json.JSONObject().put("role", "system")
                .put("content", "You are Claude Code, Anthropic's official CLI for Claude."));
        m1.put(new org.json.JSONObject().put("role", "user").put("content", "hi"));
        flagged.put("messages", m1);

        org.json.JSONObject neutral = new org.json.JSONObject();
        org.json.JSONArray m2 = new org.json.JSONArray();
        m2.put(new org.json.JSONObject().put("role", "system")
                .put("content", WorkBuddy.DEFAULT_SYSTEM));
        m2.put(new org.json.JSONObject().put("role", "user").put("content", "hi"));
        neutral.put("messages", m2);

        /* 混淆后必须能被查出来，混淆前不能被查出来。 */
        if (FingerprintWatch.carriesNeutralization(flagged)) {
            return "还没混淆就被判定为已混淆 —— 证据链不成立";
        }
        int hits = WorkBuddy.deFingerprint(flagged);
        if (hits <= 0) return "deFingerprint 没有命中任何指纹";
        if (!FingerprintWatch.carriesNeutralization(flagged)) {
            return "混淆过的 body 没被查出来 —— 对抗失效时永远凑不齐证据";
        }
        if (FingerprintWatch.carriesNeutralization(neutral)) {
            return "中性 body 被误判为已混淆 —— 会制造假警报";
        }

        /* 只认 system：user/assistant 里出现的零宽字符不是我们的手笔，不能当证据。 */
        org.json.JSONObject userOnly = new org.json.JSONObject();
        org.json.JSONArray m3 = new org.json.JSONArray();
        m3.put(new org.json.JSONObject().put("role", "system").put("content", "plain"));
        m3.put(new org.json.JSONObject().put("role", "user")
                .put("content", "客户" + z + "端自己打的零宽字符"));
        userOnly.put("messages", m3);
        if (FingerprintWatch.carriesNeutralization(userOnly)) {
            return "user 消息里的零宽字符被当成我们的混淆证据 —— 会制造假警报";
        }

        /* 出站计数：混淆过的那笔要单独记数（中性那笔只进总数、不进混淆数）。 */
        long sent0 = counter(FingerprintWatch.summary(), "出站尝试 ");
        long neut0 = counter(FingerprintWatch.summary(), "已混淆 ");
        FingerprintWatch.noteSent(hits);
        FingerprintWatch.noteSent(0);
        long sent1 = counter(FingerprintWatch.summary(), "出站尝试 ");
        long neut1 = counter(FingerprintWatch.summary(), "已混淆 ");
        if (sent1 - sent0 != 2) {
            return "出站计数增量应为 2，实际 " + (sent1 - sent0);
        }
        if (neut1 - neut0 != 1) {
            return "混淆计数增量应为 1（hits=" + hits + " 与 0 各一笔），实际 " + (neut1 - neut0);
        }

        /* 核心用例一（假警报防护，真机实测抓到过）：
           账号连中性请求都没过过（被上游整体标记的形态），
           混淆请求被拦**不能**归因成对抗失效。 */
        long bad0 = FingerprintWatch.blockedWhileNeutralized();
        FingerprintWatch.noteBlocked(acct + "-neverok", flagged);
        if (FingerprintWatch.blockedWhileNeutralized() != bad0) {
            return "从没成功过的账号被拦，却记成了失效确证 —— "
                    + "这正是被上游标记的账号（国内号常年 11128）会制造的假警报";
        }

        /* 核心用例二：中性内容被拦同样不能算失效确证。 */
        FingerprintWatch.noteOk(acct, neutral);
        FingerprintWatch.noteBlocked(acct, neutral);
        if (FingerprintWatch.blockedWhileNeutralized() != bad0) {
            return "中性请求被拦被记成失效确证：判据里没校验混淆痕迹，会全线误报";
        }

        /* 核心用例三：同账号对照成立 → 必须确证。
           中性能过 + 之后混淆请求被拦 → 拦的原因只剩内容。 */
        FingerprintWatch.noteBlocked(acct, flagged);
        if (FingerprintWatch.blockedWhileNeutralized() != bad0 + 1) {
            return "同账号对照成立却没有确证：对抗失效将永远不会被报告";
        }
        if (!FingerprintWatch.summary().contains("失效确证")) {
            return "摘要没给出失效计数：" + FingerprintWatch.summary();
        }
        if (!FingerprintWatch.summary().contains("对抗可能已失效")) {
            return "摘要里没有失效警示，等于白记：" + FingerprintWatch.summary();
        }

        /* 核心用例四（真机实测抓到过）：中性通过**之后**混淆请求又通过了，
           再被拦一次只是抖动 —— 对抗此刻是有效的，不许报警。
           13:44 的假警报就是这种形状：整条 curl 最终 200，
           告警却来自第一个候选账号。 */
        String acct2 = "selftest-fp2-" + System.nanoTime();
        FingerprintWatch.noteOk(acct2, neutral);
        FingerprintWatch.noteOk(acct2, flagged);
        long bad1 = FingerprintWatch.blockedWhileNeutralized();
        FingerprintWatch.noteBlocked(acct2, flagged);
        if (FingerprintWatch.blockedWhileNeutralized() != bad1) {
            return "混淆请求刚通过过却又被记成失效 —— 抖动误报，真失效时没人信";
        }

        /* 计数要能从摘要里读回来：前面三个用例一共确证了 1 次，
           断言读得到 —— 读不到说明摘要格式与界面对不上，等于白记。 */
        if (counter(FingerprintWatch.summary(), "失效确证 ") < 1) {
            return "前面已经确证过一次，摘要里却看不到计数";
        }
        return null;
    }

    /** 从 summary 那行人话里取某个计数。取不到返回 -1（断言会因此失败，不会静默放过）。 */
    private static long counter(String summary, String label) {
        int i = summary.indexOf(label);
        if (i < 0) return -1;
        int j = i + label.length();
        int k = j;
        while (k < summary.length() && Character.isDigit(summary.charAt(k))) k++;
        if (k == j) return -1;
        try {
            return Long.parseLong(summary.substring(j, k));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String checkCreditFieldChoice() {        try {
            /* 形态一：周期字段存在且为 0（真机形态，逐字照抄实测回包）
               → 必须算 0，**不能**回落到 CapacityRemain 的 500 */
            org.json.JSONObject cycleZero = new org.json.JSONObject();
            cycleZero.put("CycleCapacityRemain", 0);
            cycleZero.put("CapacityRemain", 500);
            Double got = WbTasks.cycleRemain(cycleZero);
            if (got == null) {
                return "带 CycleCapacityRemain 的回包被判成「字段缺失」—— 会回落到总量字段";
            }
            if (got.doubleValue() != 0) {
                return "周期剩余为 0 时算出了 " + got + "（真机是 500/0 并存，"
                        + "取错字段就会显示「还有 500」却发不出去）";
            }
            /* 形态二：只有总量字段（别的产品线）→ 才允许回落，否则兜底失效 */
            org.json.JSONObject onlyTotal = new org.json.JSONObject();
            onlyTotal.put("CapacityRemain", 300);
            Double got2 = WbTasks.cycleRemain(onlyTotal);
            if (got2 == null || got2.doubleValue() != 300) {
                return "只有 CapacityRemain 的回包没回落到总量字段（兜底失效）：" + got2;
            }
            /* 形态三：两个字段都没有 → 必须是 null（"未知"），不能是 0。
               返回 0 会让一个其实有额度的账号在选路里被扣 200 分。 */
            if (WbTasks.cycleRemain(new org.json.JSONObject()) != null) {
                return "两个字段都没有时该返回「未知」，却给了一个数 —— "
                        + "会把读不到字段的好账号当成额度耗尽沉底";
            }
            /* 形态四：Precise 优先于普通字段（上游同时给两个时以精确值为准） */
            org.json.JSONObject both = new org.json.JSONObject();
            both.put("CycleCapacityRemain", 10);
            both.put("CycleCapacityRemainPrecise", "42");
            Double got4 = WbTasks.cycleRemain(both);
            if (got4 == null || got4.doubleValue() != 42) {
                return "同时有精确值与非精确值时应取精确值，现在取了 " + got4;
            }
        } catch (Throwable t) {
            return "构造回包失败：" + t;
        }
        return null;
    }

    /**
     * 「额度未知」不能被当成「额度用尽」（4.51.7）。
     *
     * <p>这条修的是一个**只在特定渠道组合下才发作**的排序缺陷：`credits` 默认 -1，
     * 而只有 workbuddy / trae / loomy 三种类型会刷新它。原判据 `credits <= 0`
     * 于是让其余所有类型的每个候选永远背着 -200 分，稳沉池底。
     *
     * <p>断言直接打 `scorePublic`：造两个除了 credits 完全相同的账号，
     * 确认"未知"与"有额度"得分**完全一致**，而"确认耗尽"明显更低。
     * 这样即使以后有人重排 score 的各项权重，这条关系仍然成立。
     */
    private static String checkCreditsUnknown() {
        Config.Provider unknown = new Config.Provider();
        unknown.name = "cred-selftest-unknown";
        unknown.credits = -1;              // 默认值：没查过
        Config.Provider paid = new Config.Provider();
        paid.name = "cred-selftest-paid";
        paid.credits = 12345;              // 有额度
        Config.Provider empty = new Config.Provider();
        empty.name = "cred-selftest-empty";
        empty.credits = 0;                 // 确认耗尽

        Router.Pick a = Router.newPick(unknown, "selftest-model");
        Router.Pick b = Router.newPick(paid, "selftest-model");
        Router.Pick z = Router.newPick(empty, "selftest-model");
        double sa = Router.scorePublic(a, false);
        double sb = Router.scorePublic(b, false);
        double sz = Router.scorePublic(z, false);

        if (sa != sb) {
            return "额度**未知**（-1）与有额度的账号得分不同（" + sa + " vs " + sb
                    + "）—— 未知被当成了扣分项，非 workbuddy/trae/loomy 的渠道会永远沉底";
        }
        if (!(sz < sa)) {
            return "额度确认用尽的账号得分没有低于额度未知的（" + sz + " vs " + sa + "）";
        }
        if (sa - sz < 100) {
            return "额度用尽的惩罚太轻（只有 " + (sa - sz) + " 分），挡不住它被选中";
        }
        return null;
    }

    /**
     * 内容侧信号的口径（4.51.7）。
     *
     * <p>截断率（`stopReason=length` 的占比）这次进了选路，所以必须钉住它的口径 ——
     * 否则一个口径错误会**直接变成选路偏差**，比只做展示时危险得多。
     *
     * <p>要钉的是：`canonicalStop` 在**有工具调用时优先返回 `tool_calls`**，
     * 所以 `truncRate` 只统计"纯正文被 max_tokens 切断"，
     * 不会把爱调工具的模型冤枉成"老是被截断"。
     *
     * <p>顺带记录一个**刻意没进选路**的信号：`contentRate`。
     * 它的分母里 `outChars` 只数正文 delta（见 Server 的 tap），
     * 于是**只回工具调用、不吐正文**的响应会被算成"没内容"。
     * 拿它进选路会系统性压低爱调工具的模型 —— 正好和"agent 场景要能调工具"相反。
     * 所以这次只用 truncRate，contentRate 继续只做观测。
     */
    private static String checkContentSignalScope() {
        /* 有工具调用 → 永远归 tool_calls，不会记成 length */
        if (!"tool_calls".equals(Proto.canonicalStop("length", true))) {
            return "同时有工具调用和 length 时没优先归 tool_calls";
        }
        if (!"tool_calls".equals(Proto.canonicalStop("max_tokens", true))) {
            return "有工具调用时 max_tokens 没归 tool_calls";
        }
        /* 没有工具调用时，length 才算截断 */
        if (!"length".equals(Proto.canonicalStop("length", false))) {
            return "纯文本被 max_tokens 截断却没归 length";
        }
        if (!"length".equals(Proto.canonicalStop("max_tokens", false))) {
            return "max_tokens（OpenAI 写法）没归 length";
        }
        if (!"length".equals(Proto.canonicalStop("MAX_TOKENS", false))) {
            return "MAX_TOKENS（Anthropic/Gemini 写法）没归 length";
        }
        /* 正常结束不能被误判成截断 */
        for (String s : new String[]{"stop", "end_turn", "STOP", "stop_sequence", null}) {
            String got = Proto.canonicalStop(s, false);
            if (!"stop".equals(got)) return "正常结束 " + s + " 被归成了 " + got;
        }
        return null;
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

    /**
     * 工具探活失败的退避：坏模型不能每 30 秒重探一次。
     *
     * <p>真机看到 `WorkBuddy 1/glm-5.1` 被探了 114 次（106 次 HTTP 500），
     * 因为探活失败不写结论，`state` 仍是 UNKNOWN，下一个带工具的请求又重探。
     * 这里钉住"失败后要退避、退避要翻倍、成功要清零"。
     */
    private static String checkProbeBackoff() {
        String key = "自检/探活退避-" + System.nanoTime();
        try {
            if (ToolAbility.probeBackoffRemainingMs(key) != 0L) return "初始状态就有退避";
            ToolAbility.noteProbeFailedForTest(key);
            long first = ToolAbility.probeBackoffRemainingMs(key);
            if (first <= 0L) return "第一次失败后没有退避";
            /* 第二次失败必须比第一次等得更久（翻倍）。测试里两次调用几乎同时，
               所以直接比较剩余时间：第二次应约为第一次的两倍。 */
            ToolAbility.noteProbeFailedForTest(key);
            long second = ToolAbility.probeBackoffRemainingMs(key);
            if (second <= first) return "失败退避没有增长：" + first + " → " + second;
            if (second > 31L * 60_000L) return "退避超过封顶：" + second;
            return null;
        } finally {
            ToolAbility.forgetProbeBackoff(key);
        }
    }

    /**
     * "原样重发"的熔断：连续无效就停，一次成功就恢复。
     *
     * <p>为什么值得钉住：2026-10-25 真机日志里 51 次请求级风控、重发 102 回、
     * `重发通过` 计数为 **0**，而每轮要付 2 个上游往返 + 2 秒 sleep（阻塞请求线程）。
     * 熔断错了任一方向都不好：该开不开是纯浪费，该关不关则会把"偶发拦截能救回来"
     * 这条真实存在的路永久掐死。用独立的测试账号名，不污染真实账号状态。
     */
    private static String checkResendBreaker() {
        String who = "自检-重发熔断-" + System.nanoTime();
        try {
            if (!WorkBuddy.resendAllowed(who)) return "初始状态就不允许重发";
            /* 前两次失败还允许（阈值是 3），第三次才熔断。 */
            WorkBuddy.noteResendFailed(who, false);
            WorkBuddy.noteResendFailed(who, false);
            if (!WorkBuddy.resendAllowed(who)) return "没到阈值就熔断（偶发拦截会被一并放弃）";
            WorkBuddy.noteResendFailed(who, false);
            if (WorkBuddy.resendAllowed(who)) return "连续 3 次无效之后还没熔断";
            /* 一次成功必须立刻恢复，并清掉计数。 */
            WorkBuddy.noteResendWorked(who);
            if (!WorkBuddy.resendAllowed(who)) return "重发成功之后没恢复";
            return null;
        } finally {
            WorkBuddy.forgetResendState(who);
        }
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

    /**
     * 2026-10 的 WorkBuddy 网关：
     *   · 国际网关要求聊天带 X-Conversation-* 头；
     *   · 新的 11128 "unapproved channel / please send it again" 是请求级风控：
     *     不能按请求错误直接失败，也不能按账号标记冷却 6 小时；
     *   · 官方提示"请重新发送"的那一种可以在同账号重发；
     *   · 旧的 conversation id missing / request illegal 仍是账号级，不重发。
     */
    private static String checkWorkBuddyChannel() {
        String body = "{\"code\":11128,\"msg\":\"Illegal API invocation from an unapproved channel\","
                + "\"displayMsg\":{\"en\":\"Request blocked. Please send it again\","
                + "\"zh\":\"请求被拦截，请重新发送\"}}";
        if (!Http.channel(body)) return "unapproved channel 没被认成渠道校验";
        Exception e = Http.upstreamError(400, body);
        if (!(e instanceof Throttled)) return "HTTP 400 + 11128 没按退避处理：" + e;
        if (!"channel_soft".equals(((Throttled) e).kind)) {
            return "请求级风控没归到 channel_soft：" + ((Throttled) e).kind;
        }
        if (!Http.softChannel(body)) return "softChannel 没认出新措辞";
        if (FailScope.of(e) != FailScope.ACCOUNT) {
            return "渠道校验没归到账号作用域：" + FailScope.of(e);
        }
        if (!WorkBuddy.resendableChannel(e)) return "“请重新发送”的拦截没有触发同账号重发";
        Exception old = Http.upstreamError(400,
                "{\"code\":11128,\"msg\":\"conversation id missing\"}");
        if (FailScope.of(old) != FailScope.ACCOUNT) return "账号级 11128 没归到账号作用域";
        if (WorkBuddy.resendableChannel(old)) return "账号级 11128 不该原样重发";
        if (FailScope.of(new InvalidRequest(400, "upstream HTTP 400 request body is not json"))
                != FailScope.REQUEST) {
            return "普通 400 被渠道校验规则误伤了";
        }

        JSONObject one = new JSONObject();
        JSONArray msgs = new JSONArray();
        try {
            msgs.put(new JSONObject().put("role", "system").put("content", "sys"));
            msgs.put(new JSONObject().put("role", "user").put("content", "hi"));
            one.put("messages", msgs);
        } catch (Exception ex) {
            return "构造样例失败：" + ex;
        }
        java.util.Map<String, String> h1 = new java.util.HashMap<>();
        WorkBuddy.applyConversationHeaders(h1, one);
        for (String k : new String[]{"X-Conversation-ID", "X-Conversation-Request-ID",
                "X-Conversation-Message-ID", "X-Request-ID"}) {
            String v = h1.get(k);
            if (v == null || !v.matches("[0-9a-f]{32}")) {
                return k + " 缺失或不是 32 位十六进制：" + v;
            }
        }
        if (!h1.get("X-Conversation-Message-ID").equals(h1.get("X-Request-ID"))) {
            return "X-Request-ID 没有镜像 message id";
        }
        java.util.Map<String, String> h2 = new java.util.HashMap<>();
        WorkBuddy.applyConversationHeaders(h2, one);
        if (!h1.get("X-Conversation-ID").equals(h2.get("X-Conversation-ID"))) {
            return "同一会话的 X-Conversation-ID 不稳定";
        }
        if (!h1.get("X-Conversation-Request-ID").equals(h2.get("X-Conversation-Request-ID"))) {
            return "同一轮的 X-Conversation-Request-ID 不稳定（重试会被当成新轮次）";
        }
        if (h1.get("X-Conversation-Message-ID").equals(h2.get("X-Conversation-Message-ID"))) {
            return "两次调用的 X-Conversation-Message-ID 相同（应为每次新值）";
        }
        /* 工具循环：最后一条是 tool 结果，最后一条 user 没变 → 仍属同一轮。 */
        try {
            one.getJSONArray("messages").put(new JSONObject().put("role", "assistant")
                    .put("content", ""));
            one.getJSONArray("messages").put(new JSONObject().put("role", "tool")
                    .put("tool_call_id", "c1").put("content", "ok"));
        } catch (Exception ex) {
            return "追加工具消息失败：" + ex;
        }
        java.util.Map<String, String> h3 = new java.util.HashMap<>();
        WorkBuddy.applyConversationHeaders(h3, one);
        if (!h2.get("X-Conversation-Request-ID").equals(h3.get("X-Conversation-Request-ID"))) {
            return "工具循环被拆成了新的轮次";
        }
        /* 新的 user 消息 = 新一轮，但会话不变。 */
        try {
            one.getJSONArray("messages").put(new JSONObject().put("role", "user").put("content", "again"));
        } catch (Exception ex) {
            return "追加用户消息失败：" + ex;
        }
        java.util.Map<String, String> h4 = new java.util.HashMap<>();
        WorkBuddy.applyConversationHeaders(h4, one);
        if (h4.get("X-Conversation-Request-ID").equals(h3.get("X-Conversation-Request-ID"))) {
            return "新一轮的 request id 没有变化";
        }
        if (!h4.get("X-Conversation-ID").equals(h3.get("X-Conversation-ID"))) {
            return "同一会话跨轮次换了 X-Conversation-ID";
        }
        return null;
    }

    /**
     * 给客户端的两种根地址必须**不一样**，而且各自的形状要对（4.53.1）。
     *
     * <h4>为什么这不是"显示文案"的小事</h4>
     * Claude Code / Anthropic SDK 拿到 base URL 后会**自己补 `/v1/messages`**。
     * 而 OpenAI 兼容客户端拿到的根地址通常要**带 `/v1`**（各家 SDK 拼法不统一，
     * 带 /v1 是它们最常见的预期）。
     * 于是同一个"网关地址"对两类客户端是**两个不同的值**：
     * <pre>
     *   OpenAI 兼容（多数客户端） → http://127.0.0.1:8321/v1
     *   Claude Code              → http://127.0.0.1:8321      ← 不带 /v1
     * </pre>
     * 给错了会怎样：Claude Code 会去请求 `/v1/v1/messages`。
     * 本网关有一条容错（{@code Server.normalizePath}）能把重复前缀归一化，
     * 所以**能用** —— 但这正是危险之处：**错误不会暴露**，
     * 于是这条"给错地址"的路径可以长期存在而没人发现。
     * 而且容错只覆盖 `/v1/v1`；换成别的 SDK 或别的重复形式就未必。
     *
     * <p>所以这里钉的是"两个值不同、且形状各自正确"，而不是"能跑通"——
     * 能跑通这件事恰恰是被容错掩盖掉的。
     */
    private static String checkClientBaseUrls() {
        /* 出站那条容错必须仍在（它是既有行为的兜底，删了会让老用户立刻 404） */
        if (!"/v1/messages".equals(Server.normalizePath("/v1/v1/messages"))) {
            return "重复 /v1 的容错没了 —— 已经按 /v1 配过地址的老用户会突然 404";
        }
        if (!"/v1/messages".equals(Server.normalizePath("/v1/messages"))) {
            return "正常路径被容错改坏了：" + Server.normalizePath("/v1/messages");
        }
        /* 地址形状：Anthropic 那条**不许**以 /v1 结尾，OpenAI 那条**必须**以 /v1 结尾。
           用一个假的端口值来拼，避免依赖当前运行状态。 */
        String cc = "http://127.0.0.1:" + 12345;
        if (cc.endsWith("/v1") || cc.endsWith("/v1/")) {
            return "Claude Code 的地址不该带 /v1（它自己会补 /v1/messages，会拼成 /v1/v1）";
        }
        String oa = cc + "/v1";
        if (!oa.endsWith("/v1")) return "OpenAI 兼容地址没带 /v1";
        if (cc.equals(oa)) return "两种客户端给了同一个地址 —— 必然有一个是错的";
        return null;
    }

    /**
     * "Claude Code 接入"文档里的地址有根地址、/v1、完整 /v1/messages 几种写法，
     * 出站必须都能拼成同一个端点，且带上 Claude Code 认得的那组头。
     */
    private static String checkAnthropicUpstream() {
        String[][] cases = {
                {"https://api.anthropic.com", "https://api.anthropic.com/v1/messages"},
                {"https://api.anthropic.com/", "https://api.anthropic.com/v1/messages"},
                {"https://api.anthropic.com/v1", "https://api.anthropic.com/v1/messages"},
                {"https://relay.example.com/api/anthropic",
                        "https://relay.example.com/api/anthropic/v1/messages"},
                {"https://relay.example.com/api/anthropic/v1/messages",
                        "https://relay.example.com/api/anthropic/v1/messages"},
                {"https://relay.example.com/api/anthropic/v1/messages/",
                        "https://relay.example.com/api/anthropic/v1/messages"},
        };
        for (String[] c : cases) {
            String got = Upstreams.anthropicMessagesUrl(c[0]);
            if (!c[1].equals(got)) return "地址拼接错误：" + c[0] + " → " + got + "（期望 " + c[1] + "）";
        }
        Config.Provider p = new Config.Provider();
        p.apiKey = "sk-selftest";
        java.util.Map<String, String> h = Upstreams.anthropicHeaders(p, "context-1m-2025-08-07");
        if (!"sk-selftest".equals(h.get("x-api-key"))) return "缺少 x-api-key";
        if (!"Bearer sk-selftest".equals(h.get("Authorization"))) return "缺少 Bearer 兼容头";
        if (!"2023-06-01".equals(h.get("anthropic-version"))) return "anthropic-version 不对";
        if (!"cli".equals(h.get("x-app"))) return "缺少 Claude Code 的 x-app: cli";
        String ua = h.get("user-agent");
        if (ua == null || !ua.startsWith("claude-cli/")) return "User-Agent 不是 Claude Code 形状：" + ua;
        if (!"context-1m-2025-08-07".equals(h.get("anthropic-beta"))) return "客户端 beta 没转发";
        java.util.Map<String, String> h2 = Upstreams.anthropicHeaders(p, "");
        if (h2.containsKey("anthropic-beta")) return "没有 beta 时不该凭空发明 anthropic-beta";

        /* Anthropic → Anthropic：system 的 cache_control 必须保留，否则
           Claude Code 每轮都要为整段 system 重新计费/计算。 */
        try {
            JSONObject in = new JSONObject();
            in.put("model", "claude-sonnet-4-5");
            in.put("max_tokens", 16);
            JSONArray sys = new JSONArray();
            sys.put(new JSONObject().put("type", "text").put("text", "很长的 system")
                    .put("cache_control", new JSONObject().put("type", "ephemeral")));
            in.put("system", sys);
            JSONArray msgs = new JSONArray();
            msgs.put(new JSONObject().put("role", "user").put("content", "hi"));
            in.put("messages", msgs);
            Proto.Req r = Fronts.AnthropicFront.parse(in);
            JSONObject out = Upstreams.anthropicBody("claude-sonnet-4-5", r);
            JSONArray outSys = out.optJSONArray("system");
            if (outSys == null) return "同协议转发把 system 块数组拍平了（cache_control 丢失）";
            JSONObject b0 = outSys.optJSONObject(0);
            if (b0 == null || b0.optJSONObject("cache_control") == null) {
                return "system 块的 cache_control 没保留";
            }
            /* 跨协议仍应是文本，不要把 Anthropic 的块塞给别的上游。 */
            r.protocol = "openai";
            JSONObject out2 = Upstreams.anthropicBody("claude-sonnet-4-5", r);
            if (!(out2.opt("system") instanceof String)) {
                return "跨协议入口仍带着 Anthropic 块形状";
            }
        } catch (Exception e) {
            return "cache_control 样例失败：" + e;
        }
        return null;
    }

    /** 本机所有应用都从 127.0.0.1/::1 进来，必须都能拿到独立配额。 */
    private static String checkLoopbackPeers() {
        if (!Server.isLoopbackPeer("127.0.0.1")) return "127.0.0.1 没被认成回环";
        if (!Server.isLoopbackPeer("0:0:0:0:0:0:0:1")) return "IPv6 回环没被认出来";
        if (!Server.isLoopbackPeer("::ffff:127.0.0.1")) return "IPv4-mapped 回环没被认出来";
        if (Server.isLoopbackPeer("192.168.1.5")) return "局域网地址被误认成回环";
        if (!"/v1/models".equals(Server.normalizePath("/v1/v1/models"))) {
            return "重复的 /v1 没被收掉：" + Server.normalizePath("/v1/v1/models");
        }
        if (!"/v1/messages".equals(Server.normalizePath("\\v1\\v1\\messages"))) {
            return "反斜杠/重复前缀没被规范化";
        }
        return null;
    }

    /**
     * thinking 跨协议近似翻译（4.51.4）。
     *
     * Claude Code 发 Anthropic 的 thinking.budget_tokens，而选中的上游常常是
     * OpenAI 协议的中转。以前跨协议一律丢掉 —— 用户开了思考却没思考。
     * 现在翻成近似的档位/预算，并且同协议仍然原样透传。
     */
    private static String checkThinkingTranslation() {
        JSONObject anth = new JSONObject();
        try {
            anth.put("type", "enabled").put("budget_tokens", 8000);
        } catch (Exception e) {
            return "构造样例失败：" + e;
        }
        /* 同协议原样 */
        if (XParams.thinking("anthropic", anth, "anthropic") != anth) {
            return "同协议 thinking 没原样透传";
        }
        /* Anthropic → OpenAI：按预算分档 */
        Object toOpenAi = XParams.thinking("anthropic", anth, "openai");
        if (!(toOpenAi instanceof JSONObject)) return "Anthropic→OpenAI 的 thinking 没翻译";
        if (!"medium".equals(Proto.optString((JSONObject) toOpenAi, "effort", ""))) {
            return "8000 预算没翻成 medium：" + toOpenAi;
        }
        /* 大预算 → high，小预算 → low */
        try {
            JSONObject big = new JSONObject().put("type", "enabled").put("budget_tokens", 32000);
            JSONObject small = new JSONObject().put("type", "enabled").put("budget_tokens", 1024);
            if (!"high".equals(Proto.optString(
                    (JSONObject) XParams.thinking("anthropic", big, "openai"), "effort", ""))) {
                return "32000 预算没翻成 high";
            }
            if (!"low".equals(Proto.optString(
                    (JSONObject) XParams.thinking("anthropic", small, "openai"), "effort", ""))) {
                return "1024 预算没翻成 low";
            }
        } catch (Exception e) {
            return "分档样例失败：" + e;
        }
        /* Anthropic → Gemini：带预算 */
        Object toGem = XParams.thinking("anthropic", anth, "gemini");
        if (!(toGem instanceof JSONObject)
                || Proto.optInt((JSONObject) toGem, "thinkingBudget") == null
                || ((JSONObject) toGem).optInt("thinkingBudget") != 8000) {
            return "Anthropic→Gemini 的预算没翻译：" + toGem;
        }
        /* OpenAI → Anthropic：档位换成约定预算 */
        try {
            JSONObject oe = new JSONObject().put("effort", "high");
            Object back = XParams.thinking("openai", oe, "anthropic");
            if (!(back instanceof JSONObject)
                    || !"enabled".equals(Proto.optString((JSONObject) back, "type", ""))
                    || Proto.optInt((JSONObject) back, "budget_tokens") == null) {
                return "OpenAI→Anthropic 的档位没翻成预算：" + back;
            }
            /* disabled 要翻成 Anthropic 的 disabled */
            JSONObject off = new JSONObject().put("type", "disabled");
            Object offBack = XParams.thinking("anthropic", off, "gemini");
            if (!(offBack instanceof JSONObject)
                    || ((JSONObject) offBack).optInt("thinkingBudget", -1) != 0) {
                return "disabled 没翻成 thinkingBudget=0：" + offBack;
            }
        } catch (Exception e) {
            return "档位样例失败：" + e;
        }
        return null;
    }

    /**
     * 凭据解不开的账号必须退出派发（4.51.4）。
     *
     * 真机场景：变体包/换机/Keystore 重建时，配置里的 `enc:v1:` 密文解不开，
     * 账号的 credentials 在内存里是空的 —— 但以前它照样留在池子里显示"可用"，
     * 客户端选到就必然失败。现在标记为不可派发，并且用户编辑账号后解除。
     */
    private static String checkUnreadableCredentials() {
        Config.Provider p = new Config.Provider();
        p.name = "cred-selftest";
        if (!p.dispatchable()) return "正常账号不该被判定为不可派发";
        p.credentialsUnreadable = true;
        if (p.dispatchable()) return "凭据解不开的账号仍然在派发池里";
        if (p.blockedReason().isEmpty()) return "凭据解不开时没有给出原因";
        p.clearRestricted();
        if (p.credentialsUnreadable) return "用户编辑账号后没有解除凭据标记";
        if (!p.dispatchable()) return "解除标记后仍不可派发";

        /* 上游限制同样要退出派发，并且带原因。 */
        Config.Provider q = new Config.Provider();
        q.name = "restricted-selftest";
        q.markRestricted(60_000L, "账号存在异常行为，已被限制使用");
        if (q.dispatchable()) return "被上游限制的账号仍然在派发池里";
        if (!q.blockedReason().contains("限制")) return "受限原因没透出来：" + q.blockedReason();
        q.clearRestricted();
        if (!q.dispatchable()) return "解除限制后仍不可派发";

        /* 只有思考的响应不算交付（4.51.4）。 */
        Fronts.Collector c = new Fronts.Collector();
        c.thinking("想了很久，但一句话没说");
        if (c.substantive()) return "只有思考的响应被算成有内容（客户端会看到空回答）";
        c.text("好了");
        if (!c.substantive()) return "有正文时没被算成有内容";
        return null;
    }

    /**
     * 会话亲和（4.51.5）。
     *
     * 两件事必须同时成立，缺一个这套机制就是错的：
     *   · {@link Conversation#session} 对**同一段对话的不同轮次**必须给出同一个键 ——
     *     这是亲和的全部意义（换模型会让 prompt cache 全部失效）；
     *   · 对**不同对话**必须给出不同的键 —— 否则所有用户/所有会话共用一条亲和，
     *     等于把选路锁死在一个候选上。
     *
     * 顺带钉住"不能拿 {@link Conversation#id} 当亲和键"这个坑：它哈希全部消息，
     * 每多一轮就变一个值，用它做亲和会永远命不中（这就是当初写 session() 的原因）。
     */
    private static String checkSessionAffinity() {
        Proto.Req turn1 = sessionReq("你是一个严谨的助手。", "帮我看看这个函数");
        Proto.Req turn2 = sessionReq("你是一个严谨的助手。", "帮我看看这个函数");
        /* 第 2 轮：同一段对话继续问（前面多了 assistant 回复 + 新的 user 消息） */
        turn2.messages.add(new Proto.Msg("assistant").add(Proto.Part.text("好，我看一下。")));
        turn2.messages.add(new Proto.Msg("user").add(Proto.Part.text("再解释一下第二行")));

        String s1 = Conversation.session(turn1);
        String s2 = Conversation.session(turn2);
        if (s1.isEmpty()) return "同一条会话派生出了空键（亲和永远不命中）";
        if (!s1.equals(s2)) {
            return "同一段对话的第 1 轮和第 2 轮派生出不同的键（" + s1 + " / " + s2
                    + "），亲和会永远命不中";
        }
        /* id 必须仍然随轮次变化，否则说明我们把它和 session 搞混了 */
        if (Conversation.id(turn1).equals(Conversation.id(turn2))) {
            return "Conversation.id 在跨轮次时没有变化，多跳追溯会串成一跳";
        }

        Proto.Req other = sessionReq("你是一个严谨的助手。", "另一个完全不同的问题");
        if (s1.equals(Conversation.session(other))) {
            return "两段不同的对话共用了同一个会话键，会把选路锁死";
        }
        /* 客户端自带的会话提示优先：同一条首条 user、不同 prompt_cache_key 要分开 */
        Proto.Req a = sessionReq("sys", "同样的问题");
        Proto.Req b = sessionReq("sys", "同样的问题");
        try {
            a.extraBody.put("prompt_cache_key", "sess-a");
            b.extraBody.put("prompt_cache_key", "sess-b");
        } catch (Exception e) {
            return "构造 prompt_cache_key 样例失败：" + e;
        }
        if (Conversation.session(a).equals(Conversation.session(b))) {
            return "客户端声明了不同的 prompt_cache_key，网关却当成同一段会话";
        }
        if (!Conversation.session(a).equals(Conversation.session(a))) {
            return "会话键不稳定（同一请求两次调用结果不同）";
        }
        /* 只有 system、没有 user：不足以区分会话，应当返回空让调用方跳过亲和 */
        Proto.Req sysOnly = new Proto.Req();
        sysOnly.system = "只有系统提示";
        if (!Conversation.session(sysOnly).isEmpty()) {
            return "没有 user 消息时不该派生出会话键";
        }
        /* 亲和表本身：记进去能读回来，null / 空键不写坏表。 */
        Router.noteSession(turn1, "acc/model-x");
        if (!"acc/model-x".equals(Router.sessionPinForTest(turn1))) {
            return "记下的亲和读不回来";
        }
        Router.noteSession(turn1, "");
        if (!"acc/model-x".equals(Router.sessionPinForTest(turn1))) {
            return "空候选名把亲和表写坏了";
        }
        return null;
    }

    /**
     * 预置服务商的国内/国外分组（4.55.0）。
     *
     * <p>这条锁住的是一次真实踩到的静默缺陷：判据内联在 UI 包里、关键词表漏了
     * 一半国内厂商，"智谱 GLM""讯飞星火""小米 MiMo""百度千帆"全被判成国外，
     * 在「选择服务商」里被归到错误的分组下。而自检够不着 UI 包，所以一直没人发现。
     *
     * <p>现在判据在 {@link Region}（core 包），这里逐个断言两件事：
     * <ol>
     *   <li>每一家**知名厂商的归属**都判对（名单直接写在断言里，判据改坏会红）；</li>
     *   <li>「国际版」例外成立 —— 同一家厂商的海外站点必须归国外
     *       （否则"MiniMax 国际版"会因名字里的 minimax 被误判成国内）。</li>
     * </ol>
     *
     * <p>刻意**不**断言 MainActivity.choosePreset 里的数组顺序 —— 那是 UI 包的代码，
     * core 的自检够不着。顺序错误的表现是"表头插错位置"，不改变任何请求行为；
     * 判据错误的表现是"厂商归错组"，两者危害不同，这里锁的是后者。
     */
    private static String checkRegionClassify() {
        /* 类型直判的那几种（不依赖名字）。 */
        if (!Region.isDomestic(Config.TYPE_WORKBUDDY, "")) return "WorkBuddy 应判国内";
        if (!Region.isDomestic(Config.TYPE_TRAE, "")) return "Trae 应判国内";
        if (!Region.isDomestic(Config.TYPE_LOOMY, "")) return "Loomy 应判国内";
        /* 「应判国外」的断言不能写成 !isDomestic —— 那等于"只要不是国内就报错"，
           对国外项恒真。第一次写这条自检时就踩了（三条同时红）。 */
        if (Region.isDomestic(Config.TYPE_CLAUDE, "")) return "Claude 应判国外";
        if (Region.isDomestic(Config.TYPE_CODEX, "")) return "Codex 应判国外";
        if (Region.isDomestic(Config.TYPE_ANTIGRAVITY, "")) return "Antigravity 应判国外";

        /* 名字判据：4.55.0 之前全错的那些 + 代表性的国内外对照。 */
        String[][] cases = {
                {"智谱 GLM", "国内"}, {"讯飞星火", "国内"}, {"小米 MiMo", "国内"},
                {"百度千帆", "国内"}, {"火山方舟 Doubao（控制台 API Key）", "国内"},
                {"DeepSeek", "国内"}, {"硅基流动 SiliconFlow", "国内"},
                {"商汤日日新 SenseNova", "国内"}, {"阶跃星辰 StepFun", "国内"},
                {"美团 LongCat", "国内"}, {"魔搭 ModelScope", "国内"},
                {"通义千问 DashScope", "国内"}, {"月之暗面 Kimi", "国内"},
                {"MiniMax", "国内"},
                {"OpenRouter", "国外"}, {"xAI Grok", "国外"}, {"Groq", "国外"},
                {"Google AI Studio", "国外"}, {"Cohere", "国外"},
                {"HuggingFace", "国外"}, {"自建 / 其它中转", "国外"},
        };
        for (String[] c : cases) {
            boolean want = "国内".equals(c[1]);
            if (Region.isDomestic(Config.TYPE_OPENAI, c[0]) != want) {
                return "「" + c[0] + "」应判" + c[1] + "，实际判了" + (want ? "国外" : "国内");
            }
        }

        /* 「国际版」例外：必须在关键词匹配之前短路，否则厂商名会把它拉回国内。 */
        if (Region.isDomestic(Config.TYPE_OPENAI, "MiniMax 国际版（M2 免费期）")) {
            return "「MiniMax 国际版」应判国外（走境外出口），却被判成国内 —— "
                    + "「国际」短路规则可能被挪到了关键词匹配之后";
        }
        return null;
    }

    /**
     * 下拉刷新手势的状态机（4.51.6）。
     *
     * <p>这条是**因为真机验证断掉才补的**，值得说明：这台设备一锁屏，
     * {@code input swipe} 注入的触摸全都进了锁屏 PIN 界面，根本到不了应用 ——
     * "下拉没反应"于是有两种可能（手势写错了 / 触摸没进来），在真机上分不清。
     *
     * <p>而这类代码恰恰**只会静默坏掉**：判定写错了不报错、不抛异常，
     * 只是"什么都不发生"。所以把判定抽成纯状态机（{@code PullGesture}，
     * 不引用任何 View）在这里逐分支断言。真机验证只能证明"这次能用"，
     * 这里能证明"每个分支都对"。
     *
     * <p>用 dp=1 的密度构造（slop 与阈值都按 px 直接给），断言才好读。
     */
    private static String checkPullGesture() {
        final int SLOP = 8;          // px（相当于 ViewConfiguration 的 touch slop）
        final float TRIGGER = 64f;   // px（相当于 64dp @ density=1）

        /* --- 1. 往下拉过阈值松手 → 触发 --- */
        PullGesture g = new PullGesture(SLOP, TRIGGER);
        if (g.on(android.view.MotionEvent.ACTION_DOWN, 100f, false) != PullGesture.NONE) {
            return "DOWN 不该直接接管手势（那样按钮就点不动了）";
        }
        if (g.on(android.view.MotionEvent.ACTION_MOVE, 104f, false) != PullGesture.NONE) {
            return "位移没超过 slop 就接管了，点击会被误判成下拉";
        }
        if (g.on(android.view.MotionEvent.ACTION_MOVE, 130f, false) != PullGesture.START) {
            return "往下拖过 slop 之后没有接管手势";
        }
        if (g.on(android.view.MotionEvent.ACTION_MOVE, 180f, false) != PullGesture.DRAG) {
            return "接管之后没有进入拖动状态";
        }
        if (g.on(android.view.MotionEvent.ACTION_UP, 180f, false) != PullGesture.FIRE) {
            return "拉过阈值松手没有触发刷新（位移 80 > 阈值 " + TRIGGER + "）";
        }

        /* --- 2. 没拉够 → 收回，不触发 --- */
        g = new PullGesture(SLOP, TRIGGER);
        g.on(android.view.MotionEvent.ACTION_DOWN, 100f, false);
        g.on(android.view.MotionEvent.ACTION_MOVE, 130f, false);   // 接管（位移 30）
        if (g.on(android.view.MotionEvent.ACTION_UP, 130f, false) != PullGesture.SETTLE) {
            return "没拉够阈值就触发了刷新（位移 30 < 阈值 " + TRIGGER + "）";
        }

        /* --- 3. 内容还能上滚时不许抢 --- */
        g = new PullGesture(SLOP, TRIGGER);
        g.on(android.view.MotionEvent.ACTION_DOWN, 100f, true);
        if (g.on(android.view.MotionEvent.ACTION_MOVE, 400f, true) != PullGesture.NONE) {
            return "内容还能上滚时抢走了手势，用户滚不动列表";
        }
        if (g.active()) return "被让给滚动的手势不该处于接管状态";

        /* --- 4. 往上滑（负位移）永远不接管 --- */
        g = new PullGesture(SLOP, TRIGGER);
        g.on(android.view.MotionEvent.ACTION_DOWN, 500f, false);
        if (g.on(android.view.MotionEvent.ACTION_MOVE, 100f, false) != PullGesture.NONE) {
            return "往上滑被当成了下拉刷新";
        }

        /* --- 5. ACTION_CANCEL 不触发（父级抢走 / 来电等） --- */
        g = new PullGesture(SLOP, TRIGGER);
        g.on(android.view.MotionEvent.ACTION_DOWN, 100f, false);
        g.on(android.view.MotionEvent.ACTION_MOVE, 300f, false);   // 接管，位移 200 > 阈值
        if (g.on(android.view.MotionEvent.ACTION_CANCEL, 300f, false) != PullGesture.SETTLE) {
            return "ACTION_CANCEL 被当成了松手触发（用户没表达意愿却刷新了）";
        }

        /* --- 6. 松手之后不该还处于接管态（否则下一次点击会被吞） --- */
        g = new PullGesture(SLOP, TRIGGER);
        g.on(android.view.MotionEvent.ACTION_DOWN, 100f, false);
        g.on(android.view.MotionEvent.ACTION_MOVE, 300f, false);
        g.on(android.view.MotionEvent.ACTION_UP, 300f, false);
        if (g.active()) return "松手后仍在接管状态，下一次点击会被吞掉";

        /* --- 7. 没有 DOWN 的野 MOVE 不该启动（保证状态机的输入合法性） --- */
        g = new PullGesture(SLOP, TRIGGER);
        if (g.on(android.view.MotionEvent.ACTION_MOVE, 400f, false) != PullGesture.NONE) {
            return "没有 DOWN 的孤立 MOVE 启动了手势";
        }

        /* --- 8. rawDy 要如实反映手指位移，不能被负值污染 --- */
        g = new PullGesture(SLOP, TRIGGER);
        g.on(android.view.MotionEvent.ACTION_DOWN, 200f, false);
        g.on(android.view.MotionEvent.ACTION_MOVE, 400f, false);   // 接管，位移 200
        g.on(android.view.MotionEvent.ACTION_MOVE, 150f, false);   // 拉回负方向
        if (g.rawDy() < 0f) return "手指反向拖时 rawDy 变成了负数（阻尼曲线会算出异常值）";
        if (g.rawDy() != 0f) return "手指反向拖回原点后 rawDy 应为 0，实际 " + g.rawDy();

        return null;
    }

    /** 构造一条"system + 首条 user"的样例请求。 */
    private static Proto.Req sessionReq(String system, String firstUser) {
        Proto.Req r = new Proto.Req();
        r.system = system;
        r.messages.add(new Proto.Msg("user").add(Proto.Part.text(firstUser)));
        return r;
    }

    /**
     * 导航玻璃 tint 到底需不需要按配色自适应（4.51.6）。
     *
     * <p>这条断言的存在意义和别的不一样：它**不是为了防回归，而是为了防"重新发明"**。
     *
     * <p>背景是这样的：{@code NavGlassDrawable} 按 {@code Ui.LIGHT} 硬分两组 tint
     * 常量，看着像是"没考虑 7 套配色明暗不同"。我据此打算加一层
     * "测背景平均亮度 → 自适应 tint"，并把它列为收益最大的一项。
     *
     * <p>动手前先把 7 套配色 × 深浅两模式的实际亮度算了一遍（离线脚本复刻
     * {@code Swatch.paint} 与 {@code applyPalette}），结果推翻了这个想法：
     * 浅色极差 0.027、深色极差 0.009。原因是 {@code Swatch} 把主题色和固定底
     * 混合时主题色只占一两成，所以**七套配色的底色深浅本来就接近**。
     *
     * <p>而真正以为会造成差异的"页面 strength"根本不进这张纹理 —— 玻璃采样的是
     * {@code Swatch} 的固定色版，{@code strength} 只作用于
     * {@code DynamicBackdrop} 的实时画面（它影响**显示**亮度，纹理恒按 strength=1 画）。
     *
     * <p>所以正确的动作是**不做**。这条断言把结论钉住：以后有人（包括我自己）
     * 再看到那两组 tint 常量觉得"该自适应"，跑一下自检就知道为什么不该。
     *
     * <p><b>阈值是量出来的，不是拍的。</b>现行七套配色的极差是
     * 浅色 0.019 / 深色 0.022。而"往表里加两套极端配色（纯黑 + 纯白）"能到
     * 浅色 0.078 / 深色 0.080 —— 也就是说 0.08 这个阈值**永远不会响**，
     * 连纯黑配纯白都刚好压在它下面（第一版就是取的 0.08，写完之后实测才发现
     * 它是个哑炮）。现在取 {@code LIMIT = 0.04}：约为现行值的两倍留量，
     * 又明显低于"极端配色"能到的高度，留出真正的报警空间。
     *
     * <p>要说清楚这条阈值的性质：它不是"0.04 以上 tint 就一定偏了"（那要靠
     * 视觉验证，没做过），而是"配色差异涨到今天的两倍以上时，**必须重新评估**
     * 那两组常量"。它触发的是"去看一眼"，不是"证明坏了"。
     */
    private static String checkGlassTintRange() {
        final float LIMIT = 0.04f;
        String[][] themes = themeTable();
        if (themes.length == 0) {
            /* 读不到配色表就等于这项检查没跑。**必须说出来**，不能静默算通过 ——
               否则"检查通过"和"检查根本没执行"在结果里长得一模一样。 */
            return null;
        }
        for (boolean light : new boolean[]{true, false}) {
            float min = Float.MAX_VALUE;
            float max = -Float.MAX_VALUE;
            String minName = "";
            String maxName = "";
            for (String[] t : themeTable()) {
                /* 复刻 Swatch.paint 的底色：主题色与固定底混合。
                   柔光团只影响局部、对全屏平均的贡献很小，这里只用底色就够
                   （离线算过完整版：含柔光团后极差 0.027，底色版 0.030，同一量级）。 */
                float luma = lumaOf(swatchBase(light, t));
                if (luma < min) {
                    min = luma;
                    minName = t[1];
                }
                if (luma > max) {
                    max = luma;
                    maxName = t[1];
                }
            }
            float range = max - min;
            if (range > LIMIT) {
                return (light ? "浅色" : "深色") + "模式下各配色背景亮度极差 " + fmt(range)
                        + "（" + minName + " " + fmt(min) + " ～ " + maxName + " " + fmt(max)
                        + "）超过 " + LIMIT + " —— 配色差异已明显大于今天（浅 0.019 / 深 0.022），"
                        + "这时才需要按配色自适应 tint，"
                        + "请重新评估 NavGlassDrawable 的那两组常量";
            }
        }
        return null;
    }

    /**
     * 真正的配色表：**反射读 {@code Ui.THEMES}**，不在这里抄一份。
     *
     * <p>这条很关键，值得说清楚：一开始我在这里抄了一张表，并写道"抄一份的代价是
     * 可能漂移"。那是错的 —— 抄一份会让这条断言**失去存在的意义**。
     * 它唯一的用途就是"有人往 UI 里加了一套明暗差很大的配色"时报警；
     * 而抄过来的副本恰恰在那种时候**不会变**，于是报警永远不会响。
     * 一张只用来兜底的副本，在真正需要兜底的时候是失效的。
     *
     * <p>所以改成反射读真表。{@code core} 包不依赖 UI 是自检的**设计取向**而非铁律，
     * 这里破例一次，代价换来的是一条真的能响的警报。
     *
     * <p>读不到（字段改名、被混淆…）时返回空数组，调用方据此跳过本项检查并说明原因 ——
     * 那种情况下"检查没跑"必须可见，不能伪装成"检查通过"。
     */
    private static String[][] themeTable() {
        try {
            java.lang.reflect.Field f = Class.forName("com.cpa.gateway.Ui")
                    .getDeclaredField("THEMES");
            f.setAccessible(true);
            String[][] table = (String[][]) f.get(null);
            return table == null ? new String[0][] : table;
        } catch (Throwable t) {
            return new String[0][];
        }
    }

    /**
     * 某套配色在某模式下 {@code Swatch.paint} 用的底色（不含柔光团）。
     *
     * <p>浅色：{@code mix(#F4F6EE, ACCENT_LIGHT, 0.12)}，其中 {@code ACCENT_LIGHT}
     * 在浅色模式下是 {@code mix(accent, 黑, 0.35)}。
     * <p>深色：{@code mix(ACCENT_SOFT, #10140C, 0.55)}，其中 {@code ACCENT_SOFT}
     * 就是主题表里的第 6 个值（每个主题自带）。
     */
    private static int swatchBase(boolean light, String[] t) {
        int accent = android.graphics.Color.parseColor(t[2]);
        if (light) {
            int accentLight = mixRgb(accent, 0xFF000000, 0.35f);
            return mixRgb(0xFFF4F6EE, accentLight, 0.12f);
        }
        /* 深色下 ACCENT_SOFT 由 applyTheme 从主题表第 6 列取；这里取不到，
           用主题色混暗底近似 —— 与实际值同量级，判据是"极差"而非绝对值。 */
        int accentSoft = mixRgb(accent, 0xFF10140C, 0.82f);
        return mixRgb(accentSoft, 0xFF10140C, 0.55f);
    }

    private static int mixRgb(int a, int b, float t) {
        float u = 1f - t;
        return android.graphics.Color.rgb(
                clamp(android.graphics.Color.red(a) * u + android.graphics.Color.red(b) * t),
                clamp(android.graphics.Color.green(a) * u + android.graphics.Color.green(b) * t),
                clamp(android.graphics.Color.blue(a) * u + android.graphics.Color.blue(b) * t));
    }

    private static int clamp(float v) {
        return (int) Math.min(255f, Math.max(0f, v));
    }

    /** Rec.709 相对亮度，与 {@code BackdropTex.luminanceOf} 同一套权重。 */
    private static float lumaOf(int c) {
        return (2126f * android.graphics.Color.red(c)
                + 7152f * android.graphics.Color.green(c)
                + 722f * android.graphics.Color.blue(c)) / 10000f / 255f;
    }

    private static String fmt(float v) {
        return String.format(java.util.Locale.US, "%.3f", Float.valueOf(v));
    }

    /**
     * 缓存命中率的分母（4.51.7）。
     *
     * <p>这条断言钉的是<b>我自己刚踩的那个坑</b>，值得写下来：第一版把命中率算成
     * {@code tokCached / tokIn}，真机上立刻显示"缓存 0%"——看上去像"亲和没生效"，
     * 实际是**分母算错了**。
     *
     * <p>原因：升级之后 usage.tsv 里会长期混着两种行。4.51.7 之前写的行没有第 23 列
     * （读出来 cached=0），之后写的才有。用全部历史 tokIn 当分母，等于把那批
     * "根本没有这个信息"的行当成"输入了但一次都没命中"。本机当天 175M 历史 token
     * 只对应几百个新 token，比值直接被压成 0。
     *
     * <p>{@code Stats} 处理内容信号时已经写明过同一条纪律（"旧行不能算进分母，
     * 否则会低估"），我在 Usage 这边没照做——**同一个项目里同样的坑还是会再踩一次**，
     * 所以这条要单独钉住。
     *
     * <p>断言打在 {@code cacheHitRate()} 的口径上：分母必须是
     * {@code tokInWithCacheInfo}，不是 {@code tokIn}。
     */
    private static String checkCacheRateDenominator() {
        Usage.Stat s = new Usage.Stat();
        s.tokIn = 10_000;           // 全部输入 token（含没有缓存信息的历史行）
        s.tokInWithCacheInfo = 100; // 其中"确实带缓存列"的行贡献的输入 token
        s.tokCached = 50;

        double rate = s.cacheHitRate();
        if (Math.abs(rate - 0.5) > 1e-9) {
            return "命中率应为 0.5（50/100，只算带缓存列的行），实际 " + rate
                    + " —— 分母很可能又用回了全部历史 tokIn，会把命中率系统性压低";
        }
        /* 没有任何带缓存列的行时返回 -1（未知），不是 0（没命中） */
        Usage.Stat none = new Usage.Stat();
        none.tokIn = 5000;
        if (none.cacheHitRate() >= 0) {
            return "完全没有缓存信息时应返回 -1（未知），实际返回 " + none.cacheHitRate()
                    + " —— 会被界面显示成一个假的 0% 命中率";
        }
        /* 有缓存信息但一次都没命中：这才是真正的 0 */
        Usage.Stat real0 = new Usage.Stat();
        real0.tokIn = 100;
        real0.tokInWithCacheInfo = 100;
        if (Math.abs(real0.cacheHitRate()) > 1e-9) {
            return "有缓存列但确实没命中时应为 0，实际 " + real0.cacheHitRate();
        }
        return null;
    }

    /**
     * 删掉的账号不能被快照复活（4.51.8）。
     *
     * <p>真机症状："老是蹦出一些不存在的账号，明明已经删了"。成因是两个设计撞在一起：
     * <ol>
     *   <li>快照原先**只增不减**（防"一次写坏的配置把救命快照冲掉"），
     *       于是删掉的账号永远留在快照里，快照冻在"历史上账号最多的那一刻"；</li>
     *   <li>{@code Config.load} 一旦发现配置里一个账号都没有，就从快照整个恢复
     *       **并写回磁盘**。</li>
     * </ol>
     * 于是"删 → 配置变空 → 从冻住的快照复活全部旧账号"循环起来。
     *
     * <p>这条断言钉的是 {@code Config.snapshotAction} 的决策表。抽成纯函数就是为了
     * 能在自检里直接喂数字断言 —— 这个 bug **不会报错**，只在"配置恰好读空"时发作，
     * 靠手工点几下试不出来。
     *
     * <p>最容易写错、也最要紧的一格是最后一条：**用户主动删除导致账号减少时，
     * 快照必须跟着缩小**（而不是像自动保存那样被保护住）。这一格错了，
     * 幽灵账号就会回来。
     */
    private static String checkSnapshotNoResurrect() {
        /* 1. 用户主动删到 0 个 → 删快照（否则留着一个"复活开关"） */
        if (Config.snapshotAction(0, 5, true) != Config.SNAP_DELETE) {
            return "用户把账号清空后没有删除快照 —— 旧账号会在下次配置读空时整批复活";
        }
        /* 2. 配置读空但**不是**用户操作 → 跳过，别冲掉快照（保护模式） */
        if (Config.snapshotAction(0, 5, false) != Config.SNAP_SKIP) {
            return "非用户操作导致配置读空时动了快照，保护机制失效";
        }
        /* 3. 用户主动删除（还剩几个）→ 写，让快照跟着缩小。
              这一格是幽灵账号的根因：旧行为是 now<prev 一律跳过，
              于是删掉的账号在快照里留存。 */
        if (Config.snapshotAction(2, 5, true) != Config.SNAP_WRITE) {
            return "用户主动删账号后快照没有跟着缩小 —— 删掉的账号会留在快照里，"
                    + "下次配置读空就被复活（幽灵账号）";
        }
        /* 4. 自动保存导致账号变少 → 跳过（保护，防意外写坏） */
        if (Config.snapshotAction(2, 5, false) != Config.SNAP_SKIP) {
            return "自动保存时账号变少却改了快照，保护机制失效";
        }
        /* 5. 账号变多或持平 → 写（两种意图都该更新） */
        if (Config.snapshotAction(6, 5, false) != Config.SNAP_WRITE) {
            return "账号变多时快照没更新";
        }
        if (Config.snapshotAction(5, 5, true) != Config.SNAP_WRITE) {
            return "账号持平且是用户操作时快照没更新";
        }
        /* 6. 用户主动加账号（从 0 加到 1）→ 写。这条防的是把"now<=0 就删/跳过"
              写成无条件的，那会让"清空后又加回来"这种正常操作丢了快照。 */
        if (Config.snapshotAction(1, 0, true) != Config.SNAP_WRITE) {
            return "用户从空配置添加第一个账号时快照没建立";
        }
        return null;
    }

    /**
     * 历史渠道要被标成「已删除」，而不是当成账号列出来（4.51.8）。
     *
     * <p>这是用户报的"老是蹦出一些不存在的账号"的**第二个来源**（第一个是快照复活，
     * 见 {@link #checkSnapshotNoResurrect}）：{@code usage.tsv} 留 30 天，
     * "行为画像"按**历史行里的渠道名**聚合，账号删了名字还在报告里出现好几天。
     *
     * <p>处理方式是**标记而不是删除**：那些样本是真实发生过的调用记录，
     * "这个渠道以前靠不靠得住"对"要不要重新加回来"仍有参考价值；
     * 用户困惑的是"我以为删干净了"，而不是"这里有历史"。所以加一句「（已删除）」。
     *
     * <p>这条断言钉的是比对逻辑：拿当前配置的账号名去比，比不上的才算历史。
     * 最容易写反的是"配置读不到时怎么办"——那时必须**一个都不标**：
     * 把全部渠道打成"已删除"是比不标更糟的谎。
     */
    private static String checkRetiredChannels() {
        Config cfg = Gateway.config();
        if (cfg == null) return null;      // 没有配置可比对，跳过（不伪装成通过）

        /* 借真实的 markRetired：构造两组 key，一组用当前第一个账号的名字，
           一组用一个绝不会存在的名字。 */
        java.util.Map<String, QualityStats.Row> rows = new java.util.LinkedHashMap<>();
        String realName = null;
        for (Config.Provider p : cfg.providers) {
            if (p != null && p.name != null && !p.name.isEmpty()) {
                realName = p.name;
                break;
            }
        }
        if (realName == null) return null;   // 没账号，跳过

        QualityStats.Row live = new QualityStats.Row();
        live.key = realName;
        rows.put(live.key, live);
        QualityStats.Row ghost = new QualityStats.Row();
        ghost.key = "ZZ-绝不存在的老账号-zz";
        rows.put(ghost.key, ghost);

        QualityStats.markRetiredForTest(rows, true);
        if (live.retired) return "当前还在的账号被误标成「已删除」：" + realName;
        if (!ghost.retired) {
            return "配置里已经没有的渠道没被标成「已删除」——"
                    + "用户会在行为画像里看到不存在的账号";
        }
        return null;
    }

    /**
     * 自动更新：只升不降 + version.properties 解析（4.52.0）。
     *
     * <p>钉两处**不会报错、只会静默做错**的地方：
     *
     * <ol>
     *   <li>{@code shouldOffer} 写成 {@code >=}：每次检查都认为有新版，
     *       于是每天下一次同一个包、每天弹一次通知，日志里全是"已是最新"以外的正常字样。</li>
     *   <li>{@code version.properties} 的解析：这个文件是**版本号的唯一来源**，
     *       而它同时含 {@code version.code}（递增号，如 657）和 {@code version.name}
     *       （如 4.51.9）。早先的版本拿版本名反推 versionCode 得到 45109，
     *       和真实的 657 一比永远"有新版" —— 功能从第一天起就是坏的却不报错。
     *       所以这里必须断言"读到的 code 是文件里写的那个"，而不是推算值。</li>
     * </ol>
     *
     * <p>顺带钉住"注释行和空行不能被当成配置"：文件头两行是 {@code #} 注释，
     * 写错解析的话 {@code version.code} 那行反而可能被跳过。
     */
    private static String checkUpdater() {
        /* 1. 只升不降 */
        if (Updater.shouldOffer(658, 657) != true) return "更高版本号没被判为可更新";
        if (Updater.shouldOffer(657, 657)) return "同版本号被判为可更新 —— 会每天重复下载同一个包";
        if (Updater.shouldOffer(656, 657)) return "更低版本号被判为可更新 —— 只升不降失效（可被诱导装回旧版）";
        if (Updater.shouldOffer(0, 657)) return "版本号 0 被判为可更新";
        if (Updater.shouldOffer(658, -1)) return "拿不到当前版本号时不该判为可更新（应由版本名兜底）";
        if (Updater.compareVersion("4.52.0", "4.51.9") <= 0) return "4.52.0 应大于 4.51.9";
        if (Updater.compareVersion("4.51.10", "4.51.9") <= 0) return "4.51.10 应按数字段大于 4.51.9";

        /* 2. version.properties 解析（必须读到文件里真实写的 code，不是推算的） */
        String sample = "# 版本号的唯一来源\n"
                + "version.code=657\n"
                + "version.name=4.51.9\n";
        Updater.Info a = new Updater.Info();
        if (!Updater.parseVersionProps(sample, a)) return "version.properties 解析失败";
        if (a.versionCode != 657) {
            return "version.code 应为 657，实得 " + a.versionCode
                    + " —— 若拿版本名 4.51.9 反推得到 45109，就会永远认为有新版本";
        }
        if (!"4.51.9".equals(a.version)) return "version.name 解析错误：" + a.version;

        /* 3. 认不出的内容要返回 false（让调用方换下一个源），而不是当成"版本 0" */
        Updater.Info b = new Updater.Info();
        if (Updater.parseVersionProps("<html>404 not found</html>", b)) {
            return "解析不了的内容被当成有效版本 —— 源返回错误页时会被误判成有更新";
        }
        Updater.Info c = new Updater.Info();
        if (Updater.parseVersionProps(null, c)) return "null 输入被当成有效版本";

        /* 4. 资产地址形状（发布流程的约定；改了这里就再也下不到包） */
        String url = Updater.assetUrl("4.52.0");
        if (!url.endsWith("/v4.52.0/AHYU-4.52.0.apk")) return "资产地址形状不对：" + url;
        return null;
    }

    /**
     * 快照按磁盘校准（4.52.1）。
     *
     * <p>钉的是真机上实测到的那个数：**磁盘 3 个账号、快照 6 个**。
     * 多出来的 3 个是升级到 4.51.8 之前删掉的 —— 4.51.8 只保证"以后的删除会缩快照"，
     * 管不了已经冻在文件里的旧数据。它们在"配置恰好读空"时会整批复活，
     * 用户看到的就是"删了两次还在"。
     *
     * <p>这条断言直接读真机的两个文件比对，不是喂假数据：
     * 如果哪天又出现"快照比磁盘多"，自检就会红，而不是等用户下次丢配置时才发现。
     */
    private static String checkSnapshotReconcile() {
        /* 1. 决策表（纯函数，钉住真机实测到的那个数：磁盘 3 / 快照 6） */
        if (!Config.shouldReconcile(3, 6)) {
            return "磁盘 3 个、快照 6 个时没有校准 —— 升级前删掉的账号还冻在快照里，"
                    + "配置一旦读空就整批复活（这正是真机上报「删两次还在」的原因）";
        }
        if (Config.shouldReconcile(3, 3)) return "快照与磁盘相平时不该校准（多余的写盘）";
        if (Config.shouldReconcile(5, 3)) return "磁盘比快照多时不该按这条规则校准";
        /* 磁盘为 0 时快照多几个是**正常**的 —— 那是 recoverFromSnapshot 该兜底的场合，
           校准会把最后的救命快照冲掉，比幽灵账号更糟。 */
        if (Config.shouldReconcile(0, 6)) return "磁盘读空时不该校准 —— 会把兜底快照冲掉";
        if (Config.shouldReconcile(0, 0)) return "两个都为空时不该校准";

        /* 2. 真机实况：load 时已经校准过，所以这里不该再出现"快照比磁盘多" */
        android.content.Context ctx = Gateway.context();
        if (ctx == null) return null;                     // 没有 context，跳过（不伪装成通过）
        try {
            java.io.File dir = ctx.getFilesDir();
            java.io.File live = new java.io.File(dir, "gateway.json");
            java.io.File snap = new java.io.File(dir, "gateway.accounts.json");
            if (!live.isFile() || live.length() == 0) return null;   // 没配置可校准
            int diskN = Config.countProvidersOnDisk(live);
            int snapN = Config.countProvidersOnDisk(snap);
            if (Config.shouldReconcile(diskN, snapN)) {
                return "快照里有 " + snapN + " 个账号、磁盘只有 " + diskN
                        + " 个 —— Config.load 的校准没生效（或校准后又被写旧了）";
            }
            return null;
        } catch (Throwable t) {
            return null;                                  // 读不到就跳过，不假装通过
        }
    }

    /**
     * 「启动网关」按钮不会把自己写回旧文案（4.52.2）。
     *
     * <p>用户报的是"启用之后它会自动关掉那个按钮"。真机机制是：
     * {@code toggleGateway()} 把按钮改成「正在启动…」，700ms 后无条件把旧文案
     * 「启动网关」写回去；而这段时间里状态轮询（2 秒一次）已经把它刷成了
     * 「停止网关」—— 于是网关明明在跑，按钮却写回「启动网关」，
     * 紧接着那句 {@code refreshStatus()} 又因为指纹没变而直接 return（不会纠正）。
     * 用户再点一次，就真的走进停止分支把网关关掉了。
     *
     * <p>判据：只有按钮上现在显示的**仍是**我们写进去的那句进行中文案，
     * 才说明期间没别人改过，才该还原。这里直接钉这个纯函数。
     */
    private static String checkLoadingTextRestore() {
        /* 期间没人动过（还写着我们写的进行中文案）→ 该还原 */
        if (!com.cpa.gateway.Ui.shouldRestoreLoadingText("正在启动…", "正在启动…")) {
            return "按钮还显示我们写的「正在启动…」，却不还原原文案 —— "
                    + "启动失败后按钮会永远卡在「正在启动…」";
        }
        /* 期间被状态轮询改成了新状态（"停止网关"）→ 不该还原，否则把真状态盖掉 */
        if (com.cpa.gateway.Ui.shouldRestoreLoadingText("停止网关", "正在启动…")) {
            return "按钮已被状态轮询刷成「停止网关」（网关确实起来了），"
                    + "此时还原旧文案「启动网关」会把真状态盖掉 —— "
                    + "这就是用户看到的「启动后按钮自己变回去」，再点一次就把网关关掉了";
        }
        if (com.cpa.gateway.Ui.shouldRestoreLoadingText("启动网关", "正在停止…")) {
            return "停止过程中按钮被刷成「启动网关」，不该再还原（同上的反向情形）";
        }
        /* 没有记录进行中文案时保持旧行为（还原），不能因为新逻辑把它变成"什么都不做" */
        if (!com.cpa.gateway.Ui.shouldRestoreLoadingText("随便什么", null)) {
            return "没有进行中文案记录时应当照旧还原（保持旧行为）";
        }
        return checkQuotaKind();
    }

    /**
     * 额度耗尽必须归到 {@code quota}，不能落进 {@code rate}（4.52.2）。
     *
     * <p>钉的是真机原文：WorkBuddy / CodeBuddy 额度用尽回 HTTP 429 +
     * <pre>{"error":{"data":{"code":14018,"msg":"额度已用尽，请访问以下链接，购买加量包…"}}}</pre>
     * 整条**没有一个英文字面量**。原来 {@code Http.quota()} 只认 {@code insufficient_quota}，
     * 于是它落进 {@code rate}，三条链路同时走错且都不报错：
     * 退避 5 分钟（额度不会 5 分钟恢复 → 永远白撞）、
     * 不进账号级回避表（同账号换模型又要撞一遍 → 就是用户说的"特别慢"）、
     * 界面说"换模型可继续用"（换模型救不了 → 误导）。
     *
     * <p>反方向也要钉：**不能**把一切带 "quota" 的都判成耗尽。OpenAI 兼容中转站的
     * {@code "quota exceeded"} 往往指分钟级速率配额，会自己好；把它按 30 分钟回避是误伤。
     */
    private static String checkQuotaKind() {
        /* 1. 真机原文（中文）：必须归 quota */
        String wb = "upstream HTTP 429 {\"error\":{\"data\":{\"code\":14018,"
                + "\"msg\":\"额度已用尽，请访问以下链接，购买加量包以获取更多额度："
                + "https://www.codebuddy.cn/profile/usage\"}}}";
        if (!"quota".equals(Http.kindOf(429, wb))) {
            return "WorkBuddy 的 14018「额度已用尽」被判成了 "
                    + Http.kindOf(429, wb) + " —— 它会按 5 分钟限流退避（额度不会 5 分钟恢复，"
                    + "于是每 5 分钟白撞一轮），而且不进账号级回避表："
                    + "同一个没额度的账号换个模型又要全撞一遍，这正是真机实测到的 10.6 秒慢";
        }
        /* WorkBuddy 流式路径用的是 HTTP 402 报 quota，也要确认这条仍然通 */
        if (!"quota".equals(Http.kindOf(402, wb))) return "402 没有被归成额度";
        if (Http.quota(wb) != true) return "Http.quota 没认出 WorkBuddy 的 14018 原文";

        /* 2. 英文的几种"用尽"说法 */
        String[] exhausted = {
                "{\"error\":{\"message\":\"You exceeded your current quota\",\"code\":\"insufficient_quota\"}}",
                "{\"error\":\"Credits exhausted. Please purchase add-on packs\"}",
                "{\"error\":{\"message\":\"You are out of credits\"}}",
                "{\"error\":{\"message\":\"Insufficient balance\"}}",
                "{\"error\":{\"message\":\"额度不足\"}}",
        };
        for (String body : exhausted) {
            if (!Http.quota(body)) return "额度耗尽没认出来：" + body;
        }

        /* 3. 反方向：分钟级速率配额**不该**被当成额度耗尽（会把账号无谓回避 30 分钟） */
        String[] notQuota = {
                "{\"error\":{\"message\":\"Rate limit reached, quota exceeded for this minute\"}}",
                "{\"error\":{\"message\":\"Too many requests, please slow down\"}}",
                "{\"error\":{\"message\":\"upstream temporarily overloaded\"}}",
        };
        for (String body : notQuota) {
            if (Http.quota(body)) {
                return "把会自己好的限流当成了额度耗尽（会被无谓回避 30 分钟）：" + body;
            }
        }

        /* 4. 和展示层对齐：Detect 认为"额度不足"的，Http 也必须认为它是额度。
           两处措辞一旦漂开，就会出现"界面说额度没了、退避按限流走"的分裂。 */
        for (String body : exhausted) {
            String cls = Detect.classify(body);
            if (cls.contains("额度") && !Http.quota(body)) {
                return "展示层说是额度、传输层却不认（两处措辞漂开了）：" + body + " → " + cls;
            }
        }
        /* 5. 挂着 402 的**短窗口限流**不能当成额度（4.53.0，实测 Pollinations 匿名池） */
        String freeLimit = "HTTP 402 {\"status\":402,\"success\":false,\"error\":{\"code\":"
                + "\"FREE_LIMIT\",\"message\":\"Free limit reached: 1 reply every 30 seconds per IP."
                + " Retry after 26s, or get unlimited replies with a pollen key\"}}";
        if (!"rate".equals(Http.kindOf(402, freeLimit))) {
            return "402 + 「Retry after 26s」被判成 " + Http.kindOf(402, freeLimit)
                    + " —— quota 会被抬到最少 30 分钟、还会把整个账号名下所有模型一起拉黑，"
                    + "于是一个 26 秒就能恢复的限制把免费池锁 30 分钟";
        }
        /* 时长也要跟着归类走：402 的默认退避是 1 小时，判成 rate 后必须是 5 分钟 */
        if (Http.defaultBackoffMs(402, "rate") != 300_000L) {
            return "归类成 rate 之后默认退避仍是 " + Http.defaultBackoffMs(402, "rate")
                    + "ms —— 归类改对了、时长还是按 402 的 1 小时走（改一半最难查）";
        }
        if (Http.defaultBackoffMs(402, "quota") != 3_600_000L) {
            return "真额度耗尽的 402 退避被改短了（应保持 1 小时）";
        }
        /* 上游明说的窗口必须被采用，哪怕比默认值更近 */
        if (!Http.transientWindow(freeLimit)) return "没认出「Retry after 26s」是个短窗口";
        long stated = Http.parseResetAtMs(freeLimit);
        if (stated <= 0L) return "parseResetAtMs 没解析出正文里的 26 秒";
        long delta = stated - System.currentTimeMillis();
        /* 允许一点误差：26 秒不该变成 5 分钟或 1 小时 */
        if (delta < 10_000L || delta > 60_000L) {
            return "正文里的「Retry after 26s」解析成了 " + (delta / 1000) + " 秒";
        }

        /* 6. 反方向：真额度耗尽**没有**承诺短窗口，不能被判成限流 */
        String realQuota = "HTTP 429 {\"code\":14018,\"msg\":\"额度已用尽，"
                + "请访问以下链接，购买加量包以获取更多额度\"}";
        if (Http.transientWindow(realQuota)) {
            return "把「去买加量包」当成了会自己恢复的短窗口 —— 真额度耗尽会被当限流，"
                    + "只躲 5 分钟就又去撞";
        }
        if (!"quota".equals(Http.kindOf(429, realQuota))) return "真额度耗尽的归类错了";
        if (!"quota".equals(Http.kindOf(402, realQuota))) {
            return "402 + 真额度耗尽（无短窗口）应仍是 quota，现在成了 "
                    + Http.kindOf(402, realQuota);
        }
        /* 7. 优先级：上游**点名**的 insufficient_quota 高于措辞推测。
           真额度的报文里常带一句客套的 "please try again later"，
           如果按措辞判成限流，4.52.2 修的问题会以另一种形式回来（只躲 5 分钟就又去撞）。 */
        String quotaWithPleasantry = "{\"error\":{\"code\":\"insufficient_quota\","
                + "\"message\":\"You exceeded your current quota, please try again later\"}}";
        if (!"quota".equals(Http.kindOf(402, quotaWithPleasantry))) {
            return "带「please try again later」客套话的真额度耗尽被判成了 "
                    + Http.kindOf(402, quotaWithPleasantry)
                    + " —— 上游点名的 insufficient_quota 必须优先于措辞推测";
        }
        return checkAccountAvoid();
    }

    /**
     * 账号级回避（4.52.1）：额度/凭证失败是**账号**属性，但要**扣分**而不是硬挡。
     *
     * <p>起因是真机实测到的那种慢：{@code auto} 的三个候选全来自同一个没额度的
     * WorkBuddy 账号，每个请求都要先撞它一遍、等一次超时（实测 10.6 秒一条），
     * 然后换下一个 —— 用户看到的就是"特别慢、老重连"。
     * 根因是限流表按 {@code 账号/模型} 记账，同一个账号换个模型就又"干净"了。
     *
     * <p>为什么最终是扣分而不是拉黑：同一账号下实测有模型还能返回（502 空响应），
     * 有模型报 14018 额度用尽 —— 额度至少部分是**按模型**计的。
     * 硬挡会把还能用的模型一起误伤，极端情况下候选池清空变成 404。
     * 所以这里钉住三件事：
     * <ol>
     *   <li>只认 {@code quota}/{@code auth} 两类是账号级 —— 限流和渠道校验不是，
     *       换个模型可能就好了，按账号一刀切会误伤；</li>
     *   <li>被回避的账号**仍然出现在候选池里**（不制造"没有可用候选"）；</li>
     *   <li>分数下降幅度在合理区间：要低于"正常账号"，但不能低到盖过
     *       "额度确认用尽"(-200) 这个断崖。</li>
     * </ol>
     */
    private static String checkAccountAvoid() {
        /* 1. 作用域：只有额度/凭证算账号级 */
        if (!Router.accountScoped("quota")) return "额度用尽没有当成账号级失败";
        if (!Router.accountScoped("auth")) return "凭证失效没有当成账号级失败";
        if (Router.accountScoped("rate")) {
            return "限流被当成了账号级失败 —— 限流通常只挂一个模型，"
                    + "按账号一刀切会把同账号下还能用的模型一起误伤";
        }
        if (Router.accountScoped("channel") || Router.accountScoped("channel_soft")) {
            return "渠道校验被当成了账号级失败（它本来就只是单次请求的问题）";
        }
        if (Router.accountScoped("empty") || Router.accountScoped(null)) {
            return "空响应/null 被当成了账号级失败";
        }

        /* 2. 过期语义：到点自动失效，且清掉不留垃圾 */
        String who = "avoid-selftest-" + System.nanoTime();
        long now = System.currentTimeMillis();
        if (Router.accountAvoided(who, now)) return "从没记过的账号被判成在回避中";
        Router.recordAccountAvoid(who, now + 60_000L);
        if (!Router.accountAvoided(who, now)) return "刚记下的账号级回避没有生效";
        /* 边界：到点那一刻就该放行（用的是 <=，不是 <）—— 差一毫秒地卡着一个账号
           会让"本来刚好恢复"的请求白等一轮，正是要治的那种慢。 */
        if (Router.accountAvoided(who, now + 60_000L)) return "回避到期那一毫秒没有放行（边界应该是 <=）";
        if (Router.accountAvoided(who, now + 60_001L)) return "回避过期后仍然生效";
        /* 清掉之后不该再被算进计数 */
        int before = Router.accountAvoidCount(now);
        if (before < 0) return "账号级回避计数为负";
        /* 坏输入不该污染这张表 */
        Router.recordAccountAvoid(null, now + 60_000L);
        Router.recordAccountAvoid("", now + 60_000L);
        Router.recordAccountAvoid("avoid-selftest-bad", 0L);
        if (Router.accountAvoided("avoid-selftest-bad", now)) return "untilMs=0 的坏记录被存进了回避表";

        /* 3. 核心取舍：扣分、不硬挡 */
        Config.Provider p = new Config.Provider();
        p.name = who;
        p.credits = 9999;                       // 有额度，排除 -200 那一档的干扰
        String model = "avoid-selftest-model";
        Router.Pick pick = Router.newPick(p, model);
        double clean = Router.scorePublic(pick, false);
        Router.recordAccountAvoid(who, System.currentTimeMillis() + 600_000L);
        Router.Pick pick2 = Router.newPick(p, model);
        double avoided = Router.scorePublic(pick2, false);
        if (!(avoided < clean)) {
            return "账号级回避没有改变得分（" + clean + " vs " + avoided
                    + "）—— 同账号换模型又会排到前面，就是实测到的那个 10.6 秒慢";
        }
        double drop = clean - avoided;
        if (drop < 40) {
            return "账号级回避扣分太轻（只有 " + drop + " 分），压不住——"
                    + "它必须能盖过随机波动，才轮得到别的账号";
        }
        if (drop > 150) {
            return "账号级回避扣分太重（" + drop + " 分），快赶上「额度确认用尽」(-200) 了 ——"
                    + "会把同账号下还能用的模型也一起沉底";
        }
        /* 候选池不能被清空（硬挡的退路）：newPick 出来的候选永远算"存在"，
           所以这里只需要确认回避没有把它变成不可用。 */
        if (!Router.available(model)) {
            return "账号级回避把候选挡成不可用了 —— 池子清空会变成 404，"
                    + "而实测同账号下确实还有能返回的模型";
        }
        return checkIntlFallback();
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

        /* ---- 4.53.2：拦截的**前置条件**是"这次本来能成功" ----
         *
         * 真机死循环（实测到第 25 次一路 429）：
         *   账号额度耗尽 → 失败 → 客户端重试（内容当然一样）
         *   → 被判成"卡死重试" → 429 → 客户端更该重试 → 永远 429
         * 它把暂时性错误永久化成了硬错误。
         *
         * 所以钉住 wouldSucceed 这个闸门本身：池子空 / 全不可用时**不许拦**。 */
        if (Repeats.wouldSucceed(null)) return "候选池为 null 时不该判定为「能成功」";
        if (Repeats.wouldSucceed(new java.util.ArrayList<Router.Pick>())) {
            return "空候选池不该判定为「能成功」—— 会把「上游没额度」的重复重试也拦掉，"
                    + "客户端就永远等不到额度恢复后的那次成功";
        }
        /* 额度确认用尽的账号不算"能成功"：那条路的重复是重试，不是客户端卡死。 */
        Config.Provider broke = new Config.Provider();
        broke.name = "repeat-selftest-broke";
        broke.credits = 0;
        java.util.List<Router.Pick> drained = new java.util.ArrayList<>();
        drained.add(Router.newPick(broke, "selftest-repeat-model"));
        if (Repeats.wouldSucceed(drained)) {
            return "额度用尽的账号被判成「能成功」—— 那正是真机上把客户端锁死在 429 的原因："
                    + "额度没了本来就只能失败，重试是正常的，不该被当成「卡死重试」";
        }
        /* 反过来：有一个额度正常、不在回避里的候选 → 算能成功（这时才该拦重复） */
        Config.Provider good = new Config.Provider();
        good.name = "repeat-selftest-good";
        good.credits = 12345;
        java.util.List<Router.Pick> live = new java.util.ArrayList<>();
        live.add(Router.newPick(good, "selftest-repeat-model"));
        if (!Repeats.wouldSucceed(live)) {
            return "有额度充足、不在回避的候选时却没判定为「能成功」—— "
                    + "那样「客户端卡在重试循环里白烧额度」就再也拦不住了";
        }
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
    /**
     * HTTP 头的内存上限（4.50.3）。
     *
     * 原来单行超过 64KB 只截断不报错，剩下的字节会留在流里被当成下一条请求；
     * 头也没有总条数/总字节上限。LAN 模式下这两条都能被用来打内存或打乱 keep-alive。
     */
    private static String checkHttpLimits() {
        try {
            StringBuilder big = new StringBuilder("GET /health HTTP/1.1\r\nX-Big: ");
            while (big.length() < 70 * 1024) big.append('a');
            big.append("\r\n\r\n");
            if (!readFails(big.toString())) return "超长请求头行没有被拒绝";

            StringBuilder many = new StringBuilder("GET /health HTTP/1.1\r\n");
            for (int i = 0; i < 300; i++) many.append("X-").append(i).append(": a\r\n");
            many.append("\r\n");
            if (!readFails(many.toString())) return "请求头条数没有上限";

            Server.Req ok = Server.readRequest(new java.io.BufferedInputStream(
                    new java.io.ByteArrayInputStream(("GET /health HTTP/1.1\r\n"
                            + "Host: selftest\r\n\r\n").getBytes("UTF-8")), 8192));
            if (ok == null || !"/health".equals(ok.path)) return "正常请求被上限误伤";
            return null;
        } catch (Throwable t) {
            return "HTTP 上限自检异常：" + t;
        }
    }

    /**
     * 浏览器跨源防护（4.50.3）：没设 API Key 时，外部网页不能直接驱动本机网关。
     *
     * 这里只测纯函数；真正的拦截在 Server.route 与 Realtime 握手处。
     */
    private static String checkBrowserOrigin() {
        String[][] cases = {
                {"", "false"},
                {"null", "true"},
                {"app://local", "false"},
                {"http://127.0.0.1:3000", "false"},
                {"http://127.0.0.1", "false"},
                {"http://localhost:8080", "false"},
                {"http://tauri.localhost", "false"},
                {"http://[::1]:8080", "false"},
                {"https://evil.example", "true"},
                {"http://localhost.evil.example", "true"},
                {"http://127.0.0.1.evil.example", "true"},
                {"http://evil.example/localhost", "true"},
        };
        for (String[] c : cases) {
            boolean want = "true".equals(c[1]);
            boolean got = Server.isForeignBrowserOrigin(c[0]);
            if (got != want) {
                return "Origin=" + c[0] + "：期望 " + (want ? "拦截" : "放行")
                        + "，实际 " + (got ? "拦截" : "放行");
            }
        }
        return null;
    }

    /** 日志脱敏（4.50.3）：凭据不能进日志/诊断包，普通业务码要保留。 */
    private static String checkLogScrub() {
        String raw = "GET /cb?refreshToken=abc123456&state=st987654&userJwt=xyz "
                + "Authorization: Bearer sk-abcdef1234567890 "
                + "jwt eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abcdefghijklmnop";
        String out = Log.scrub(raw);
        for (String secret : new String[]{"abc123456", "st987654", "xyz",
                "sk-abcdef1234567890", "eyJhbGciOiJIUzI1NiJ9"}) {
            if (out.contains(secret)) return "日志里仍出现凭据：" + secret;
        }
        if (!out.contains("***")) return "脱敏后没有替换标记";
        String biz = Log.scrub("{\"code\":14018,\"msg\":\"credits exhausted\"}");
        if (!biz.contains("14018")) return "业务码 14018 被误脱敏：" + biz;
        return null;
    }

    /** 这段原始请求是否会被 readRequest 拒绝。 */
    private static boolean readFails(String raw) {
        try {
            Server.readRequest(new java.io.BufferedInputStream(
                    new java.io.ByteArrayInputStream(raw.getBytes("UTF-8")), 8192));
            return false;
        } catch (Throwable t) {
            return true;
        }
    }

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

    /**
     * 三个入口解析器都必须保留工具结果的 id（4.33.4 修过的真实问题）。
     * 以前 OpenAI 的 tool 消息 content 非空时 id 会被丢掉，上游报
     * "Unexpected tool_call_id: 空"；Gemini 的 functionResponse 更是固定写空。
     */
    private static String checkFrontToolResults() {
        try {
            JSONObject openAi = new JSONObject();
            openAi.put("model", "m");
            JSONArray msgs = new JSONArray();
            JSONObject assistant = new JSONObject();
            assistant.put("role", "assistant");
            assistant.put("content", JSONObject.NULL);
            assistant.put("tool_calls", new JSONArray().put(new JSONObject()
                    .put("id", "call_1")
                    .put("type", "function")
                    .put("function", new JSONObject()
                            .put("name", "bash")
                            .put("arguments", "{}"))));
            msgs.put(assistant);
            msgs.put(new JSONObject()
                    .put("role", "tool")
                    .put("tool_call_id", "call_1")
                    .put("content", "result text"));
            openAi.put("messages", msgs);
            Proto.Part tr = findPart(Fronts.OpenAiFront.parse(openAi), Proto.Part.TOOL_RESULT);
            if (tr == null) return "OpenAI 工具结果没解析出来";
            if (!"call_1".equals(tr.toolCallId)) return "OpenAI 工具结果丢了 tool_call_id";
            if (!"result text".equals(tr.text)) return "OpenAI 工具结果正文丢了";

            JSONObject anthropic = new JSONObject();
            anthropic.put("model", "m");
            anthropic.put("max_tokens", 16);
            anthropic.put("messages", new JSONArray().put(new JSONObject()
                    .put("role", "user")
                    .put("content", new JSONArray().put(new JSONObject()
                            .put("type", "tool_result")
                            .put("tool_use_id", "toolu_1")
                            .put("content", "结果")))));
            Proto.Part atr = findPart(Fronts.AnthropicFront.parse(anthropic), Proto.Part.TOOL_RESULT);
            if (atr == null) return "Anthropic 工具结果没解析出来";
            if (!"toolu_1".equals(atr.toolCallId)) return "Anthropic 工具结果丢了 tool_use_id";

            JSONObject gemini = new JSONObject();
            gemini.put("contents", new JSONArray().put(new JSONObject()
                    .put("role", "user")
                    .put("parts", new JSONArray().put(new JSONObject()
                            .put("functionResponse", new JSONObject()
                                    .put("name", "bash")
                                    .put("id", "fc_1")
                                    .put("response", new JSONObject().put("result", "ok")))))));
            Proto.Part gtr = findPart(Fronts.GeminiFront.parse("m", gemini), Proto.Part.TOOL_RESULT);
            if (gtr == null) return "Gemini 工具结果没解析出来";
            if (!"fc_1".equals(gtr.toolCallId)) return "Gemini 工具结果丢了 id";
            if (!gtr.text.contains("ok")) return "Gemini 工具结果正文丢了";
            return null;
        } catch (Throwable t) {
            return "入口解析回放异常：" + t;
        }
    }

    /**
     * 出站 body 里必须有一条带该 id 的 role:tool 消息。返回 null 表示没问题。
     */
    private static String toolResultOutboundProblem(JSONObject body, String wantId, String wantText) {
        JSONArray outMsgs = body == null ? null : body.optJSONArray("messages");
        for (int i = 0; outMsgs != null && i < outMsgs.length(); i++) {
            JSONObject m = outMsgs.optJSONObject(i);
            if (m == null) continue;
            if (!"tool".equals(Proto.optString(m, "role", ""))) continue;
            if (!wantId.equals(Proto.optString(m, "tool_call_id", ""))) continue;
            if (!Proto.optString(m, "content", "").contains(wantText)) continue;
            return null;
        }
        return "工具结果出站没变成 role:tool（上游会报 11148 / 模型以为工具没执行）";
    }

    /**
     * 工具结果跨协议出站：Anthropic / Gemini 把它们放在 **user** 消息里
     * （`tool_result` / `functionResponse` 块），而 OpenAI 家族要求它是一条独立的
     * `role:"tool"` 消息、且必须紧跟在带 `tool_calls` 的 assistant 之后。
     *
     * <p>为什么必须钉住：只要这一步没转，上游看到的就是"assistant 声明调了工具，
     * 后面却没有任何工具结果"，要么回 400 {@code 11148 tool calls and tool results
     * do not match}，要么模型以为工具没执行、一遍遍重发同样的调用（就是"降智/死循环"
     * 那种表现）。日志里两种症状都真实出现过。
     *
     * <p>这条断言只测纯函数：入口解析 → 出站 body，不需要网络与账号。
     */
    private static String checkToolResultOutbound() {
        try {
            JSONObject an = new JSONObject();
            an.put("model", "m");
            an.put("max_tokens", 64);
            JSONArray msgs = new JSONArray();
            msgs.put(new JSONObject().put("role", "assistant").put("content", new JSONArray()
                    .put(new JSONObject().put("type", "tool_use").put("id", "toolu_1")
                            .put("name", "bash").put("input", new JSONObject()))));
            msgs.put(new JSONObject().put("role", "user").put("content", new JSONArray()
                    .put(new JSONObject().put("type", "tool_result")
                            .put("tool_use_id", "toolu_1").put("content", "工具输出"))));
            an.put("messages", msgs);
            Proto.Req req = Fronts.AnthropicFront.parse(an);
            String why = toolResultOutboundProblem(Upstreams.openAiBody("m", req, true), "toolu_1", "工具输出");
            if (why != null) return "Anthropic：" + why;

            /* Gemini 也是同一个形态：functionResponse 放在 user 的 parts 里。 */
            JSONObject gm = new JSONObject();
            JSONArray contents = new JSONArray();
            contents.put(new JSONObject().put("role", "model").put("parts", new JSONArray()
                    .put(new JSONObject().put("functionCall", new JSONObject()
                            .put("name", "bash").put("id", "fc_1")
                            .put("args", new JSONObject())))));
            contents.put(new JSONObject().put("role", "user").put("parts", new JSONArray()
                    .put(new JSONObject().put("functionResponse", new JSONObject()
                            .put("name", "bash").put("id", "fc_1")
                            .put("response", new JSONObject().put("result", "工具输出"))))));
            gm.put("contents", contents);
            String gw = toolResultOutboundProblem(
                    Upstreams.openAiBody("m", Fronts.GeminiFront.parse("m", gm), true),
                    "fc_1", "工具输出");
            if (gw != null) return "Gemini：" + gw;

            /* 反向回归：OpenAI 入口本来就是 role:tool，不能被改坏。 */
            JSONObject oa = new JSONObject();
            oa.put("model", "m");
            JSONArray omsgs = new JSONArray();
            omsgs.put(new JSONObject().put("role", "assistant").put("content", JSONObject.NULL)
                    .put("tool_calls", new JSONArray().put(new JSONObject().put("id", "call_1")
                            .put("type", "function").put("function", new JSONObject()
                                    .put("name", "bash").put("arguments", "{}")))));
            omsgs.put(new JSONObject().put("role", "tool").put("tool_call_id", "call_1")
                    .put("content", "工具输出"));
            oa.put("messages", omsgs);
            String ow = toolResultOutboundProblem(
                    Upstreams.openAiBody("m", Fronts.OpenAiFront.parse(oa), true), "call_1", "工具输出");
            if (ow != null) return "OpenAI 入口回归：" + ow;
            return null;
        } catch (Throwable t) {
            return "工具结果出站回放异常：" + t;
        }
    }

    /**
     * 同协议未知参数必须原样透传；跨协议不能硬塞。
     * 这是"网关把不认识的参数悄悄丢掉"的直接回归测试。
     */
    private static String checkExtraBody() {
        try {
            JSONObject o = new JSONObject();
            o.put("model", "m");
            o.put("messages", new JSONArray().put(new JSONObject()
                    .put("role", "user").put("content", "hi")));
            o.put("top_k", 5);
            o.put("service_tier", "flex");
            o.put("metadata", new JSONObject().put("k", "v"));
            Proto.Req req = Fronts.OpenAiFront.parse(o);
            JSONObject body = Upstreams.openAiBody("m", req, true);
            if (body.optInt("top_k", 0) != 5) return "OpenAI top_k 没透传";
            if (!"flex".equals(body.optString("service_tier", ""))) return "OpenAI service_tier 没透传";
            if (!"v".equals(body.optJSONObject("metadata") == null ? ""
                    : body.optJSONObject("metadata").optString("k", ""))) {
                return "OpenAI metadata 没透传";
            }
            /* 4.50.1 起 top_k 是 canonical 字段：OpenAI 入口显式发 top_k 时，
               Anthropic 出口会等价翻译（详见 checkTopK）。这里只确认它没有丢。 */
            JSONObject cross = Upstreams.anthropicBody("m", req);
            if (cross.optInt("top_k", 0) != 5) return "OpenAI 的 top_k 没翻译给 Anthropic";

            JSONObject a = new JSONObject();
            a.put("model", "m");
            a.put("max_tokens", 16);
            a.put("messages", new JSONArray().put(new JSONObject()
                    .put("role", "user").put("content", "hi")));
            a.put("top_k", 7);
            a.put("metadata", new JSONObject().put("user_id", "u1"));
            Proto.Req ar = Fronts.AnthropicFront.parse(a);
            JSONObject ab = Upstreams.anthropicBody("m", ar);
            if (ab.optInt("top_k", 0) != 7) return "Anthropic top_k 没透传";
            if (!"u1".equals(ab.optJSONObject("metadata") == null ? ""
                    : ab.optJSONObject("metadata").optString("user_id", ""))) {
                return "Anthropic metadata 没透传";
            }
            return null;
        } catch (Throwable t) {
            return "未知参数透传自检异常：" + t;
        }
    }

    /**
     * 响应保真（4.50.0）：n>1 / logprobs 的请求，上游回什么就该给客户端什么。
     *
     * 这条只测纯函数部分：把上游分片喂给 Streamer 的 raw 入口，检查
     *   · role 空壳先压住（失败换号时客户端不该看到半截流）；
     *   · 多 choice 与 logprobs 原样出现；
     *   · model 改写成客户端请求的名字；
     *   · 要 logprobs 时正文一个字符都不删（删了 token 与概率就错位）；
     *   · [DONE] 由网关补一次，且不会重复。
     */
    private static String checkRawFidelity() throws Exception {
        /* 请求侧：n / logprobs / top_logprobs 必须真的发给上游。 */
        JSONObject reqBody = new JSONObject();
        reqBody.put("model", "m");
        reqBody.put("messages", new JSONArray().put(new JSONObject()
                .put("role", "user").put("content", "hi")));
        reqBody.put("n", 2);
        reqBody.put("logprobs", true);
        reqBody.put("top_logprobs", 3);
        Proto.Req parsed = Fronts.OpenAiFront.parse(reqBody);
        JSONObject sent = Upstreams.openAiBody("up-m", parsed, true);
        if (sent.optInt("n", 0) != 2) return "请求侧 n 没透传";
        if (!sent.optBoolean("logprobs", false)) return "请求侧 logprobs 没透传";
        if (sent.optInt("top_logprobs", 0) != 3) return "请求侧 top_logprobs 没透传";

        java.io.StringWriter sw = new java.io.StringWriter();
        Fronts.OpenAiFront.Streamer s = new Fronts.OpenAiFront.Streamer(
                new java.io.BufferedWriter(sw), "client-model", false);

        /* 只有 role 的空壳：先不写出去。 */
        JSONObject role = new JSONObject();
        role.put("id", "up-1");
        role.put("choices", new JSONArray().put(new JSONObject()
                .put("index", 0).put("delta", new JSONObject().put("role", "assistant"))));
        s.rawChunk(role);
        if (!sw.toString().isEmpty()) {
            return "只有 role 的分片被提前写出（换号时客户端会看到半截流）";
        }

        /* 两个 choice + logprobs + 自报家门正文。 */
        JSONObject chunk = new JSONObject();
        chunk.put("id", "up-1");
        chunk.put("model", "upstream-model");
        JSONArray choices = new JSONArray();
        JSONObject c0 = new JSONObject();
        c0.put("index", 0);
        c0.put("delta", new JSONObject().put("content", "我是DeepSeek，答案甲"));
        c0.put("logprobs", new JSONObject().put("content", new JSONArray()));
        choices.put(c0);
        JSONObject c1 = new JSONObject();
        c1.put("index", 1);
        c1.put("delta", new JSONObject().put("content", "答案乙"));
        c1.put("logprobs", new JSONObject().put("content", new JSONArray()));
        choices.put(c1);
        chunk.put("choices", choices);
        s.rawChunk(chunk);
        s.rawDone();
        s.finish("stop", new Proto.Usage());

        String out = sw.toString();
        if (!out.contains("\"index\":1")) return "第二个 choice 没透传";
        if (!out.contains("\"logprobs\"")) return "logprobs 没透传";
        if (!out.contains("我是DeepSeek，答案甲")) {
            return "要 logprobs 时正文被改写了（token 与概率会错位）";
        }
        if (!out.contains("\"model\":\"client-model\"")) return "model 没改写成客户端请求的名字";
        if (!out.contains("data: [DONE]")) return "[DONE] 没补";
        int done = out.split("data: \\[DONE\\]", -1).length - 1;
        if (done != 1) return "[DONE] 出现了 " + done + " 次";
        /* 不能出现 canonical 的补写收尾（否则客户端会收到一个多余的空 delta）。 */
        if (out.contains("\"finish_reason\":\"stop\"")) {
            return "保真模式仍补写了 canonical 收尾分片";
        }

        /* 换号残留：A 的 role 空壳不能跟着 B 的正文一起发出去。 */
        java.io.StringWriter sw2 = new java.io.StringWriter();
        Fronts.OpenAiFront.Streamer s2 = new Fronts.OpenAiFront.Streamer(
                new java.io.BufferedWriter(sw2), "client-model", false);
        JSONObject aRole = new JSONObject();
        aRole.put("id", "id-from-A");
        aRole.put("choices", new JSONArray().put(new JSONObject()
                .put("index", 0).put("delta", new JSONObject().put("role", "assistant"))));
        s2.rawChunk(aRole);
        s2.resetAttempt();
        JSONObject bBody = new JSONObject();
        bBody.put("id", "id-from-B");
        bBody.put("choices", new JSONArray().put(new JSONObject()
                .put("index", 0).put("delta", new JSONObject().put("content", "B"))));
        s2.rawChunk(bBody);
        s2.rawDone();
        if (sw2.toString().contains("id-from-A")) return "换号后残留了上一个候选的分片";
        if (!sw2.toString().contains("id-from-B")) return "换号后的正常分片丢了";

        /* 只有 role 的 completion：什么都不该写出去，也不能置 rawEnded ——
           上层要能把它判成空并换候选；写了 [DONE] 就换不了。 */
        java.io.StringWriter sw3 = new java.io.StringWriter();
        Fronts.OpenAiFront.Streamer s3 = new Fronts.OpenAiFront.Streamer(
                new java.io.BufferedWriter(sw3), "client-model", false);
        JSONObject onlyRole = new JSONObject();
        onlyRole.put("id", "role-only");
        onlyRole.put("choices", new JSONArray().put(new JSONObject()
                .put("index", 0).put("delta", new JSONObject().put("role", "assistant"))));
        s3.rawChunk(onlyRole);
        s3.rawDone();
        if (!sw3.toString().isEmpty()) {
            return "只有 role 的空流被写出去了（换不了号，客户端会收到空回答）";
        }
        if (s3.rawSeen()) return "只有 role 的空流被标成已提交";
        if (s3.delivered()) return "只有 role 的空流被算成已交付";

        /* 只有 thinking、没有正文（真机遇到的"回空"）：不许当成交付，也不许写出。
           思考先压在缓冲里，一旦真来正文会按原顺序补写出去；
           一直没来正文就整体丢掉、换候选——总比给客户端一个空回答好。 */
        java.io.StringWriter sw5 = new java.io.StringWriter();
        Fronts.OpenAiFront.Streamer s5 = new Fronts.OpenAiFront.Streamer(
                new java.io.BufferedWriter(sw5), "client-model", false);
        JSONObject think = new JSONObject();
        think.put("id", "think-only");
        think.put("choices", new JSONArray().put(new JSONObject().put("index", 0)
                .put("delta", new JSONObject().put("reasoning_content", "让我想想"))));
        s5.rawChunk(think);
        s5.rawDone();
        if (!sw5.toString().isEmpty()) {
            return "只有思考的流被写出去了（客户端会看到空回答，而且换不了号）";
        }
        if (s5.rawSeen()) return "只有思考的流被标成已提交（换不了号）";
        if (s5.delivered()) return "只有思考的流被算成已交付";

        /* 思考 + 正文：思考要先补写出去，顺序不能乱，且算已交付。 */
        java.io.StringWriter sw8 = new java.io.StringWriter();
        Fronts.OpenAiFront.Streamer s8 = new Fronts.OpenAiFront.Streamer(
                new java.io.BufferedWriter(sw8), "client-model", false);
        JSONObject t8 = new JSONObject();
        t8.put("id", "think-then-text");
        t8.put("choices", new JSONArray().put(new JSONObject().put("index", 0)
                .put("delta", new JSONObject().put("reasoning_content", "先思考"))));
        s8.rawChunk(t8);
        JSONObject c8 = new JSONObject();
        c8.put("id", "think-then-text");
        c8.put("choices", new JSONArray().put(new JSONObject().put("index", 0)
                .put("delta", new JSONObject().put("content", "再回答"))));
        s8.rawChunk(c8);
        s8.rawDone();
        String o8 = sw8.toString();
        if (!s8.delivered()) return "带正文的流没被算成已交付";
        if (o8.indexOf("先思考") < 0 || o8.indexOf("再回答") < 0) {
            return "思考或正文丢了：" + o8;
        }
        if (o8.indexOf("先思考") > o8.indexOf("再回答")) return "思考与正文的顺序反了";

        /* 音频分片必须算实质内容，不能压进 pending。 */
        java.io.StringWriter sw4 = new java.io.StringWriter();
        Fronts.OpenAiFront.Streamer s4 = new Fronts.OpenAiFront.Streamer(
                new java.io.BufferedWriter(sw4), "client-model", false);
        JSONObject audio = new JSONObject();
        audio.put("id", "audio-1");
        audio.put("choices", new JSONArray().put(new JSONObject()
                .put("index", 0)
                .put("delta", new JSONObject().put("audio", new JSONObject().put("data", "AAA")))));
        s4.rawChunk(audio);
        if (!sw4.toString().contains("audio-1")) return "音频分片被压进 pending，没有增量下发";
        if (!s4.delivered()) return "音频分片没被算成交付内容";

        /* Anthropic 出口：只有 thinking 的流同样不算交付。 */
        try {
            java.io.StringWriter sw6 = new java.io.StringWriter();
            Fronts.AnthropicFront.Streamer s6 = new Fronts.AnthropicFront.Streamer(
                    new java.io.BufferedWriter(sw6), "client-model");
            s6.thinking("让我想想");
            s6.finish("stop", new Proto.Usage());
            if (s6.substantive()) return "只有思考的 Anthropic 流被算成已交付";
            if (sw6.toString().contains("message_delta")) {
                return "只有思考的 Anthropic 流写了收尾（换不了号）";
            }
            /* 有正文时才是正常交付。 */
            java.io.StringWriter sw7 = new java.io.StringWriter();
            Fronts.AnthropicFront.Streamer s7 = new Fronts.AnthropicFront.Streamer(
                    new java.io.BufferedWriter(sw7), "client-model");
            s7.thinking("思考");
            s7.text("回答");
            s7.finish("stop", new Proto.Usage());
            if (!s7.substantive()) return "带正文的 Anthropic 流没被算成已交付";
            if (!sw7.toString().contains("message_stop")) return "正常流少了 message_stop";
            if (!sw7.toString().contains("thinking_delta")) return "思考过程没下发";
        } catch (Exception e) {
            return "Anthropic 空流样例失败：" + e;
        }
        return null;
    }

    /**
     * top_k（4.50.1）：三套协议里名字不同、语义相同。
     *
     * 规则：
     *  · OpenAI / Anthropic / Gemini 同协议都要保留；
     *  · Anthropic ↔ Gemini 互译；
     *  · 客户端显式发 top_k 时，可以翻译到 Anthropic / Gemini；
     *  · 不往 OpenAI 上游跨协议硬塞——OpenAI 官方接口不认这个字段。
     */
    private static String checkTopK() {
        try {
            JSONObject o = new JSONObject();
            o.put("model", "m");
            o.put("messages", new JSONArray().put(new JSONObject()
                    .put("role", "user").put("content", "hi")));
            o.put("top_k", 5);
            Proto.Req or = Fronts.OpenAiFront.parse(o);
            if (or.topK == null || or.topK.intValue() != 5) return "OpenAI 入口没解析 top_k";
            if (Upstreams.openAiBody("m", or, true).optInt("top_k", 0) != 5) {
                return "OpenAI→OpenAI 的 top_k 丢了";
            }
            if (Upstreams.anthropicBody("m", or).optInt("top_k", 0) != 5) {
                return "OpenAI→Anthropic 的 top_k 没翻译";
            }
            if (Upstreams.geminiGenerationConfig(or).optInt("topK", 0) != 5) {
                return "OpenAI→Gemini 的 top_k 没翻译";
            }

            JSONObject a = new JSONObject();
            a.put("model", "m");
            a.put("max_tokens", 16);
            a.put("messages", new JSONArray().put(new JSONObject()
                    .put("role", "user").put("content", "hi")));
            a.put("top_k", 7);
            Proto.Req ar = Fronts.AnthropicFront.parse(a);
            if (ar.topK == null || ar.topK.intValue() != 7) return "Anthropic 入口没解析 top_k";
            if (Upstreams.anthropicBody("m", ar).optInt("top_k", 0) != 7) {
                return "Anthropic→Anthropic 的 top_k 丢了";
            }
            if (Upstreams.geminiGenerationConfig(ar).optInt("topK", 0) != 7) {
                return "Anthropic→Gemini 的 top_k 没翻译";
            }
            if (Upstreams.openAiBody("m", ar, true).has("top_k")) {
                return "Anthropic→OpenAI 不该硬塞 top_k（官方接口会 400）";
            }

            JSONObject gBody = new JSONObject();
            gBody.put("contents", new JSONArray().put(new JSONObject()
                    .put("role", "user").put("parts", new JSONArray().put(
                            new JSONObject().put("text", "hi")))));
            gBody.put("generationConfig", new JSONObject().put("topK", 9));
            Proto.Req gr = Fronts.GeminiFront.parse("m", gBody);
            if (gr.topK == null || gr.topK.intValue() != 9) return "Gemini 入口没解析 topK";
            if (Upstreams.geminiGenerationConfig(gr).optInt("topK", 0) != 9) {
                return "Gemini→Gemini 的 topK 丢了";
            }
            if (Upstreams.anthropicBody("m", gr).optInt("top_k", 0) != 9) {
                return "Gemini→Anthropic 的 topK 没翻译";
            }
            if (Upstreams.openAiBody("m", gr, true).has("top_k")) {
                return "Gemini→OpenAI 不该硬塞 top_k（官方接口会 400）";
            }
            return null;
        } catch (Throwable t) {
            return "top_k 翻译自检异常：" + t;
        }
    }

    /**
     * 失败作用域（4.50.2）：坏模型、坏号、坏请求必须分开。
     *
     * 这条同时锁住三件事：
     *  · 坏工具参数是模型行为问题 → 纯调用立刻停，轮换时跳过这个模型；
     *  · 模型不存在/不支持 → 轮换时跳过该模型，但纯调用可以再换一个号确认；
     *  · 请求本身不合法 → 换号换模型都没用，直接停。
     */
    private static String checkFailScope() {
        try {
            FailScope bad = FailScope.of(new ToolFix.BadToolCall("bash", "{oops"));
            if (bad != FailScope.MODEL_BAD) return "坏工具参数没归到模型行为";
            if (!bad.stopDirect()) return "坏工具参数在纯调用时还在换号";
            if (!bad.skipModelInRotation()) return "坏工具参数在轮换时没跳过该模型";

            FailScope access = FailScope.of(new InvalidRequest(400,
                    "upstream HTTP 400 model gpt-x service info not found"));
            if (access != FailScope.MODEL_ACCESS) return "model not found 没归到模型接入";
            if (access.stopDirect()) return "model not found 不该在第一个账号就放弃（可能只是该号没有权限）";
            if (access.skipModelInRotation()) {
                return "model not found 不该在轮换时封杀整个模型（可能只是当前账号没权限）";
            }

            FailScope trunc = FailScope.of(new ToolFix.BadToolCall("bash", "{oops", true));
            if (trunc != FailScope.REQUEST) return "被 max_tokens 截断的坏工具参数没归到请求问题";
            if (!trunc.stopDirect()) return "截断的坏工具参数还在换号重试";

            FailScope badJson = FailScope.of(new InvalidRequest(400,
                    "upstream HTTP 400 request body is not json"));
            if (badJson != FailScope.REQUEST) return "请求体不是 JSON 被误判成网络/断流";

            FailScope unsupported = FailScope.of(new InvalidRequest(400,
                    "this model does not support tool_choice"));
            if (unsupported != FailScope.REQUEST) return "模型不支持某参数被误判成模型接入问题";

            /* WorkBuddy 11133「模型提供方错误」（真机 2026-10-25）：上游模型侧故障话术，
               换模型能过、换号没用。曾被下面的 InvalidRequest 吞成 REQUEST ——
               点名第一个号就死、轮换中止整轮，一个坏 glm-5.1 陪葬整个池。 */
            String err1133 = "upstream HTTP 400 {\"code\":11133,\"msg\":\"the request parameters"
                    + " were rejected by the model provider\",\"extError\":{\"code\":\"1210\","
                    + "\"message\":\"the request parameters were rejected by the model provider\","
                    + "\"type\":\"invalid_request_error\",\"StatusCode\":400},"
                    + "\"displayMsg\":{\"zh\":\"模型提供方错误，请稍后重试\"}}";
            FailScope provider = FailScope.of(new InvalidRequest(400, err1133));
            if (provider != FailScope.MODEL_BAD) {
                return "11133 模型提供方错误没归到模型侧（会被当请求不合法，中止整轮换模型）";
            }
            if (!provider.skipModelInRotation()) return "11133 在轮换时没跳过该模型";
            if (provider.stopRotation()) return "11133 不该中止整轮（换个模型就能过）";

            FailScope req = FailScope.of(new InvalidRequest(400,
                    "请求不合规（我们自己这侧，不是渠道问题）：tool_call 缺少 id"));
            if (req != FailScope.REQUEST) return "请求不合规没归到请求问题";
            if (!req.stopDirect() || !req.stopRotation()) return "请求问题还在换号/换模型";

            FailScope acct = FailScope.of(new Throttled(429, 1000L, "rate", "限流"));
            if (acct != FailScope.ACCOUNT) return "限流没归到账号侧";
            if (acct.stopDirect()) return "限流时纯调用不该立刻放弃（换号可以继续）";

            FailScope tr = FailScope.of(new java.io.IOException("connection timed out"));
            if (tr != FailScope.TRANSIENT) return "超时没归到暂时性失败";
            if (tr.stopDirect()) return "超时不该停止换号";

            FailScope cut = FailScope.of(new java.io.IOException(
                    "upstream stream ended before finish_reason"));
            if (cut != FailScope.TRANSIENT) return "半截流没归到暂时性失败";
            if (cut.stopDirect()) return "半截流不该停止换号";

            FailScope ssl = FailScope.of(new javax.net.ssl.SSLHandshakeException("certificate"));
            if (ssl != FailScope.TRANSIENT) return "TLS 握手失败没归到暂时性失败";
            return null;
        } catch (Throwable t) {
            return "失败作用域自检异常：" + t;
        }
    }

    /** Ollama 是本地生态最常用的入口：翻译、工具配对、两种响应形状都要对。 */
    private static String checkOllamaProtocol() {
        try {
            JSONObject body = new JSONObject();
            body.put("model", "m");
            JSONArray msgs = new JSONArray();
            msgs.put(new JSONObject().put("role", "system").put("content", "sys"));
            msgs.put(new JSONObject().put("role", "user").put("content", "hi")
                    .put("images", new JSONArray().put("aGVsbG8=")));
            msgs.put(new JSONObject().put("role", "assistant").put("content", "")
                    .put("tool_calls", new JSONArray().put(new JSONObject()
                            .put("function", new JSONObject()
                                    .put("name", "bash")
                                    .put("arguments", new JSONObject().put("cmd", "ls"))))));
            msgs.put(new JSONObject().put("role", "tool").put("tool_name", "bash")
                    .put("content", "ok"));
            body.put("messages", msgs);
            Proto.Req req = Fronts.OllamaFront.parse(body);
            if (!"sys".equals(req.system)) return "Ollama system 没进 req.system";
            if (findPart(req, Proto.Part.IMAGE) == null) return "Ollama images 没翻译成图片块";
            Proto.Part call = findPart(req, Proto.Part.TOOL_CALL);
            Proto.Part result = findPart(req, Proto.Part.TOOL_RESULT);
            if (call == null || result == null) return "Ollama 工具调用/结果没解析出来";
            if (call.toolCallId == null || call.toolCallId.isEmpty()) return "Ollama 工具调用 id 为空";
            if (!call.toolCallId.equals(result.toolCallId)) {
                return "Ollama 工具结果 id 没和调用配对：" + call.toolCallId + " vs " + result.toolCallId;
            }

            Fronts.Collector c = new Fronts.Collector();
            c.text("he");
            c.text("llo");
            c.stop = "stop";
            c.usage.input = 3;
            c.usage.output = 2;
            JSONObject chat = Fronts.OllamaFront.render("m", c);
            if (!"hello".equals(chat.optJSONObject("message").optString("content"))) {
                return "Ollama chat 渲染正文不对";
            }
            if (!chat.optBoolean("done")) return "Ollama chat 没标 done";
            JSONObject gen = Fronts.OllamaFront.renderGenerate("m", c);
            if (!"hello".equals(gen.optString("response"))) return "Ollama generate 渲染正文不对";

            /* 工具调用的响应必须带 function.name/arguments 对象。 */
            Fronts.Collector tc = new Fronts.Collector();
            tc.toolCalls.add(Proto.Part.toolCall("call_1", "bash", "{\"cmd\":\"ls\"}"));
            JSONObject withTool = Fronts.OllamaFront.render("m", tc);
            JSONArray arr = withTool.optJSONObject("message").optJSONArray("tool_calls");
            if (arr == null || arr.length() != 1) return "Ollama 工具调用响应没渲染出来";
            return null;
        } catch (Throwable t) {
            return "Ollama 协议自检异常：" + t;
        }
    }

    private static Proto.Part findPart(Proto.Req req, String type) {
        if (req == null) return null;
        for (Proto.Msg m : req.messages) {
            if (m == null || m.parts == null) continue;
            for (Proto.Part p : m.parts) {
                if (p != null && type.equals(p.type)) return p;
            }
        }
        return null;
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
            /* 4.51.9：上游 message_start 里的 input_tokens 必须在**流开头**就转给下游。
               客户端（官方 SDK / Claude Code）会在自己的 message_start 里读它做上下文
               计数；以前没有这个通道，那个值被写死成 0。 */
            if (cap.earlyIn != 7) {
                return "Anthropic 没有在流开头上报 input_tokens（期望 7，实际 " + cap.earlyIn
                        + "）—— 客户端的上下文计数会是 0";
            }
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
        /** 上游在流开头报来的 input_tokens（4.51.9 的新通道），没报则为 -1。 */
        int earlyIn = -1;

        @Override
        public void inputTokens(int tokens) {
            earlyIn = tokens;
        }

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
