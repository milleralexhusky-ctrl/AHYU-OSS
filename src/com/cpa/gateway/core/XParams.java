package com.cpa.gateway.core;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 能力参数的跨协议翻译：tool_choice、response_format、thinking/reasoning。
 *
 * 为什么单独一个类：这三组参数的形状在各家之间完全不同，而它们是**"模型聪不聪明"
 * 的直接开关**。以前它们根本没被解析（见 Proto.Req 里的说明），等于网关把所有
 * 请求都当成"最朴素的聊天"转发出去 —— 用同一个账号，直连上游一切正常，
 * 走网关就明显变笨，且没有任何报错提示。这里负责把它们按目标协议正确重建。
 *
 * 翻译原则（按优先级）：
 *   1. **同协议优先原样透传**。OpenAI 客户端配 OpenAI 兼容上游时，tool_choice /
 *      response_format / reasoning 原封不动送过去 —— 不经过任何"理解"，也最不容易出错。
 *   2. **跨协议只做有确定映射的翻译**。tool_choice 三种语义（自动 / 必须 / 指定某个）
 *      在两边能一一对应，就翻；thinking 的预算与档位之间没有可靠映射，
 *      **不猜**，跳过并记一条日志（宁可不加，也不要瞎加一个档位把模型带偏）。
 *   3. **拿不准就不传**。多传一个上游不认识的字段可能直接 400，
 *      少传只是回到"没有这个能力"，前者会让用户以为整个网关坏了。
 */
public final class XParams {

    private XParams() {
    }

    /* ============================================================ tool_choice */

    /**
     * 把来源协议的 tool_choice 翻译成目标协议的形状。
     *
     * @return 目标协议的 tool_choice 值；无法翻译时返回 null（调用方不传该字段）
     */
    public static Object toolChoice(String from, Object raw, String to) throws Exception {
        if (raw == null) return null;
        if (from == null) from = "";
        if (to == null) to = "";
        if (from.equals(to)) return raw;                 // 同协议：原样

        if ("openai".equals(from) && "anthropic".equals(to)) return toAnthropicToolChoice(raw);
        if ("anthropic".equals(from) && "openai".equals(to)) return toOpenAiToolChoice(raw);
        if ("gemini".equals(from)) return null;          // Gemini 的 toolConfig 形状差异大，不硬翻
        return null;
    }

    /**
     * OpenAI → Anthropic。
     *   "auto"      → {"type":"auto"}
     *   "none"      → 无对应语义（Anthropic 没有"禁止调用"），返回 null 表示不传
     *   "required"  → {"type":"any"}
     *   {"function":{"name":"x"}} → {"type":"tool","name":"x"}
     */
    static Object toAnthropicToolChoice(Object raw) throws Exception {
        if (raw instanceof String) {
            String v = ((String) raw).trim().toLowerCase(java.util.Locale.US);
            if ("auto".equals(v)) return obj("type", "auto");
            if ("required".equals(v) || "any".equals(v)) return obj("type", "any");
            /* "none"：Anthropic 没有等价物。不翻译，也不能假装成 auto ——
               那会让本该"这轮别用工具"的请求变成"可以随便用"。 */
            return null;
        }
        if (raw instanceof JSONObject) {
            JSONObject o = (JSONObject) raw;
            JSONObject fn = o.optJSONObject("function");
            String name = fn == null ? "" : Proto.optString(fn, "name", "");
            if (name.isEmpty()) return null;
            return new JSONObject().put("type", "tool").put("name", name);
        }
        return null;
    }

    /**
     * Anthropic → OpenAI。
     *   {"type":"auto"} → "auto"
     *   {"type":"any"}  → "required"
     *   {"type":"tool","name":"x"} → {"type":"function","function":{"name":"x"}}
     *   {"type":"none"} → "none"
     */
    static Object toOpenAiToolChoice(Object raw) throws Exception {
        if (raw instanceof String) {
            String v = ((String) raw).trim().toLowerCase(java.util.Locale.US);
            return ("auto".equals(v) || "required".equals(v) || "none".equals(v)) ? v : null;
        }
        if (raw instanceof JSONObject) {
            JSONObject o = (JSONObject) raw;
            String t = Proto.optString(o, "type", "");
            if ("auto".equals(t)) return "auto";
            if ("any".equals(t)) return "required";
            if ("none".equals(t)) return "none";
            if ("tool".equals(t)) {
                String name = Proto.optString(o, "name", "");
                if (name.isEmpty()) return null;
                return new JSONObject().put("type", "function")
                        .put("function", new JSONObject().put("name", name));
            }
        }
        return null;
    }

    /* ======================================================= response_format */

    /**
     * 把 response_format 翻到目标协议。
     *
     * OpenAI 的 JSON 模式（{"type":"json_object"} / json_schema）在 Anthropic 侧
     * **没有等价开关** —— Anthropic 靠提示词或工具来强制结构。所以这里：
     *   · 同协议 → 原样透传；
     *   · 跨到 Anthropic → 返回 null，并由调用方记一条日志（而不是假装成功）。
     * 之所以不做"自动加一段'请输出 JSON'的提示词"：那会改变请求语义、
     * 且可能和客户端自己的提示词打架，属于自作聪明。
     */
    public static Object responseFormat(String from, Object raw, String to) {
        if (raw == null) return null;
        if (from != null && from.equals(to)) return raw;
        return null;
    }

    /* ============================================================== thinking */

    /**
     * 推理相关参数翻到目标协议。
     *
     * 三家的形状毫无可比性，所以只在**同协议**时透传：
     *   Anthropic  {"type":"enabled","budget_tokens":8000}
     *   OpenAI     reasoning_effort: "high"（本类只处理 reasoning 对象）
     *   Gemini     {"thinkingBudget": 8000}
     *
     * 跨协议不猜：budget_tokens 与 reasoning_effort 之间没有可靠的换算关系，
     * 硬翻只会给上游一个错误的档位。返回 null 时调用方会记日志。
     */
    public static Object thinking(String from, Object raw, String to) {
        if (raw == null) return null;
        if (from != null && from.equals(to)) return raw;
        return null;
    }

    /* ================================================================= 工具 */

    private static JSONObject obj(String k, Object v) {
        JSONObject o = new JSONObject();
        try {
            o.put(k, v);
        } catch (Exception ignored) {
        }
        return o;
    }

    /** 供日志：把任意值压成短文本。 */
    public static String brief(Object v) {
        if (v == null) return "null";
        String s = String.valueOf(v);
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }

    /** 数组形式的 JSON 便捷构造（给上游拼 body 用）。 */
    public static JSONArray arr(Object... items) {
        JSONArray a = new JSONArray();
        for (Object it : items) if (it != null) a.put(it);
        return a;
    }
}
