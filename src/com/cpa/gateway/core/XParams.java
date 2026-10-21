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
     * 同协议原样透传：
     *   Anthropic  {"type":"enabled","budget_tokens":8000}
     *   OpenAI     reasoning_effort: "high"
     *   Gemini     {"thinkingBudget": 8000}
     *
     * 跨协议做**受限的**近似翻译，而不是直接丢掉。
     *
     * 为什么改：Claude Code 这类客户端会发
     * `{"thinking":{"type":"enabled","budget_tokens":8000}}`，而它选中的上游
     * 很可能是 OpenAI 协议的中转。以前跨协议一律返回 null，字段被静默丢掉 ——
     * 用户明明开了思考，走网关却完全不思考，表现就是"同一个模型变笨了"。
     *
     * 翻译规则（只翻"开没开、大概多深"，不追求数值等价）：
     *   Anthropic/OpenAI → OpenAI：reasoning_effort = low / medium / high
     *   Anthropic/OpenAI → Gemini：thinkingConfig.thinkingBudget（Anthropic 有预算就用它，
     *                              OpenAI 的档位映射到约定预算 1024 / 4096 / 16384）
     *   任何 → Anthropic、且来源带预算：{type:enabled, budget_tokens}
     *   任何 → Anthropic、来源只有档位：同样按约定预算换算
     *   Anthropic 的 {"type":"disabled"}：翻成"不带上游"（OpenAI 无对应字段，
     *   返回一个空对象由调用方决定不带），Gemini 返回 thinkingBudget=0。
     *
     * 返回 null 表示"没有可翻译的东西"，调用方照旧记一条日志。
     */
    public static Object thinking(String from, Object raw, String to) {
        if (raw == null) return null;
        if (from != null && from.equals(to)) return raw;
        try {
            return approximate(from, raw, to);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 跨协议近似的实现（同协议在调用方之前就返回了）。 */
    private static Object approximate(String from, Object raw, String to) {
        boolean disabled = isThinkingDisabled(raw);
        Integer budget = budgetOf(raw);
        String effort = effortOf(raw);

        if ("openai".equals(to)) {
            /* OpenAI 兼容接口只认 reasoning_effort（部分中转认 reasoning 对象）。 */
            if (disabled) return null;                 // 不带上游 = 默认不开
            if (effort != null) return effortObject(effort);
            if (budget != null) return effortObject(effortForBudget(budget.intValue()));
            /* 只说了 enabled、没有预算：给 medium，好过完全不思考。 */
            return isThinkingEnabled(raw) ? effortObject("medium") : null;
        }
        if ("gemini".equals(to)) {
            if (disabled) return geminiBudget(0);
            if (budget != null) return geminiBudget(budget.intValue());
            if (effort != null) return geminiBudget(budgetForEffort(effort));
            return isThinkingEnabled(raw) ? geminiBudget(4096) : null;
        }
        if ("anthropic".equals(to)) {
            if (disabled) return obj("type", "disabled");
            if (budget != null) return anthropicEnabled(budget.intValue());
            if (effort != null) return anthropicEnabled(budgetForEffort(effort));
            return isThinkingEnabled(raw) ? anthropicEnabled(4096) : null;
        }
        return null;
    }

    /** Gemini 的思考预算形状：{"thinkingBudget":N}。 */
    private static JSONObject geminiBudget(int n) {
        JSONObject o = new JSONObject();
        try {
            o.put("thinkingBudget", n);
        } catch (Exception ignored) {
        }
        return o;
    }

    /** Anthropic 的开启形状：{"type":"enabled","budget_tokens":N}。 */
    private static JSONObject anthropicEnabled(int budget) {
        JSONObject o = obj("type", "enabled");
        try {
            o.put("budget_tokens", budget);
        } catch (Exception ignored) {
        }
        return o;
    }

    /** OpenAI 的 reasoning 对象形状：{"effort":"high"}。 */
    private static JSONObject effortObject(String effort) {
        return obj("effort", effort);
    }

    /** 这段思考预算是哪一档。分界点按"够不够用"取整，不追求精确换算。 */
    private static String effortForBudget(int budget) {
        if (budget <= 0) return "minimal";
        if (budget <= 2048) return "low";
        if (budget <= 8192) return "medium";
        return "high";
    }

    /** 档位 → 约定预算（Anthropic / Gemini 需要一个具体数字）。 */
    private static int budgetForEffort(String effort) {
        if (effort == null) return 4096;
        String e = effort.toLowerCase(java.util.Locale.US);
        if (e.contains("minimal")) return 512;
        if (e.contains("low")) return 1024;
        if (e.contains("high")) return 16384;
        return 4096;   // medium 与未知档位
    }

    private static boolean isThinkingEnabled(Object raw) {
        if (raw instanceof Boolean) return ((Boolean) raw).booleanValue();
        if (!(raw instanceof JSONObject)) return false;
        JSONObject o = (JSONObject) raw;
        String type = Proto.optString(o, "type", "");
        if ("enabled".equalsIgnoreCase(type) || "adaptive".equalsIgnoreCase(type)) return true;
        if ("disabled".equalsIgnoreCase(type) || "off".equalsIgnoreCase(type)) return false;
        return o.opt("enabled") instanceof Boolean && o.optBoolean("enabled", false);
    }

    private static boolean isThinkingDisabled(Object raw) {
        if (raw instanceof Boolean) return !((Boolean) raw).booleanValue();
        if (raw instanceof JSONObject) {
            String type = Proto.optString((JSONObject) raw, "type", "");
            if ("disabled".equalsIgnoreCase(type) || "off".equalsIgnoreCase(type)) return true;
            JSONObject o = (JSONObject) raw;
            if (o.has("enabled") && !o.optBoolean("enabled", true)) return true;
        }
        return false;
    }

    /** Anthropic 的 budget_tokens / Gemini 的 thinkingBudget。 */
    private static Integer budgetOf(Object raw) {
        if (!(raw instanceof JSONObject)) return null;
        JSONObject o = (JSONObject) raw;
        Integer b = Proto.optInt(o, "budget_tokens");
        if (b == null) b = Proto.optInt(o, "budgetTokens");
        if (b == null) b = Proto.optInt(o, "thinkingBudget");
        return b != null && b.intValue() > 0 ? b : null;
    }

    /** OpenAI 的 reasoning_effort / reasoning.effort。 */
    private static String effortOf(Object raw) {
        if (!(raw instanceof JSONObject)) return null;
        JSONObject o = (JSONObject) raw;
        String e = Proto.optString(o, "reasoning_effort", "");
        if (e.isEmpty()) e = Proto.optString(o, "effort", "");
        if (e.isEmpty()) {
            JSONObject r = o.optJSONObject("reasoning");
            if (r != null) e = Proto.optString(r, "effort", "");
        }
        return e.isEmpty() ? null : e;
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
