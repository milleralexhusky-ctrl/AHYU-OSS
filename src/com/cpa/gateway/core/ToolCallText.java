package com.cpa.gateway.core;

import org.json.JSONObject;

/**
 * 从**正文**里把工具调用捞出来（4.32.0）。
 *
 * 为什么需要它：弱模型（以及一部分中转站的降级实现）不会吐结构化 tool_calls，
 * 而是把调用写成普通文本：
 *
 *     <tool_call>{"name":"get_weather","arguments":{"city":"北京"}}</tool_call>
 *     {"tool_name":"search","parameters":{"q":"..."}}
 *     get_weather(city="北京")
 *
 * 客户端拿到这种东西完全没法执行 —— 这就是 ToolAbility 里说的"打嘴炮"。
 * 以前这种请求只能被判成 BAD 然后换个模型重来，模型这份"想调工具"的意图被浪费了。
 *
 * 现在：识别 → 提取 → 交给 {@link ToolFix} 修参数 → 变成一个真正的工具调用。
 * 也就是说，一个不会原生调工具的模型，通过这一层就**能用了**。
 *
 * 判据刻意保守：宁可不换，也不要换错。换错的代价是客户端拿到一个它没要求的调用，
 * 整轮对话直接崩；而漏换的代价只是维持现状（当成纯文本，和 4.32.0 之前一样）。
 */
final class ToolCallText {

    private ToolCallText() {
    }

    /** 一次提取的结果。 */
    static final class Hit {
        final String name;
        final String args;

        Hit(String name, String args) {
            this.name = name;
            this.args = args;
        }
    }

    /**
     * 尝试从正文里提取一个工具调用。
     *
     * @param text     模型输出的正文
     * @param allowed  客户端声明的工具名集合；为空表示不限制
     * @return 提取到且**名字在 allowed 里**才返回；否则 null
     */
    static Hit extract(String text, java.util.Set<String> allowed) {
        if (text == null || text.isEmpty()) return null;
        Hit h = fromJsonBlock(text, allowed);
        if (h != null) return h;
        h = fromTagged(text, allowed);
        if (h != null) return h;
        return fromFnSyntax(text, allowed);
    }

    /* ------------------------------------------------------------ 各形态解析 */

    /**
     * 形如 {"name":"x","arguments":{...}} / {"tool_name":"x","parameters":{...}}
     *
     * 复用 ToolFix 的括号配平，所以外面套着废话、围栏、或 JSON 被截断都能处理。
     */
    private static Hit fromJsonBlock(String text, java.util.Set<String> allowed) {
        String block = ToolFix.firstObjectPublic(text);
        if (block == null) return null;
        String name;
        String rawArgs = null;
        try {
            JSONObject o = new JSONObject(block);
            name = protoString(o, "name", "tool", "tool_name");
            for (String k : new String[]{"arguments", "parameters", "args", "action_input"}) {
                Object v = o.opt(k);
                if (v instanceof JSONObject) {
                    rawArgs = v.toString();
                    break;
                }
                if (v instanceof String && !((String) v).isEmpty()) {
                    rawArgs = (String) v;
                    break;
                }
            }
            if (name.isEmpty()) {
                JSONObject fn = o.optJSONObject("function");
                if (fn != null) {
                    name = protoString(fn, "name", "tool", "tool_name");
                    Object v = fn.opt("arguments");
                    if (v instanceof JSONObject) rawArgs = v.toString();
                    else if (v instanceof String) rawArgs = (String) v;
                }
            }
        } catch (Exception notJson) {
            return null;
        }
        if (name.isEmpty()) name = ToolFix.nameFromArgs(block);
        if (!acceptable(name, allowed)) return null;
        return new Hit(name, fixArgs(rawArgs, block));
    }

    /**
     * 形如 <tool_call>…</tool_call> / <invoke name="x">…</invoke>
     */
    private static Hit fromTagged(String text, java.util.Set<String> allowed) {
        String[] tags = {"tool_call", "function_call", "invoke"};
        for (String tag : tags) {
            String open = "<" + tag;
            int a = text.indexOf(open);
            if (a < 0) continue;
            int innerStart = text.indexOf('>', a);
            if (innerStart < 0) continue;
            String name = "";
            String attr = text.substring(a, innerStart);
            int nq = attr.indexOf("name=");
            if (nq >= 0) name = unquote(attr.substring(nq + 5).trim());

            String close = "</" + tag + ">";
            int b = text.indexOf(close, innerStart);
            String body = b < 0 ? text.substring(innerStart + 1) : text.substring(innerStart + 1, b);
            body = body.trim();
            if (body.isEmpty()) continue;

            Hit h = fromJsonBlock(body, allowed);
            if (h != null) return h;
            String args = fixArgs(body, body);
            if (!name.isEmpty() && acceptable(name, allowed)) return new Hit(name, args);
        }
        return null;
    }

    /**
     * 形如 get_weather(city="北京", unit="c") 的函数写法。
     *
     * 这种最容易被误判（正文里提到一个函数名不等于在调用它），所以额外要求名字在
     * allowed 里，且括号内确实有参数形态。没有 allowed 时**不认**这一种。
     */
    private static Hit fromFnSyntax(String text, java.util.Set<String> allowed) {
        if (allowed == null || allowed.isEmpty()) return null;
        for (String want : allowed) {
            if (want == null || want.isEmpty()) continue;
            int i = text.indexOf(want + "(");
            if (i < 0) continue;
            int close = text.indexOf(')', i);
            if (close < 0) continue;
            String inside = text.substring(i + want.length() + 1, close);
            if (!inside.contains("=") && !inside.contains(":")) continue;
            return new Hit(want, fnArgsToJson(inside));
        }
        return null;
    }

    /* ---------------------------------------------------------------- 辅助 */

    private static boolean acceptable(String name, java.util.Set<String> allowed) {
        if (name == null || name.isEmpty()) return false;
        if (!name.matches("[A-Za-z_][A-Za-z0-9_\\-]{0,60}")) return false;
        return allowed == null || allowed.isEmpty() || allowed.contains(name);
    }

    private static String fixArgs(String rawArgs, String wholeBlock) {
        String source = rawArgs != null && !rawArgs.isEmpty() ? rawArgs : wholeBlock;
        String fixed = ToolFix.repairArgs(source);
        return fixed == null ? "{}" : fixed;
    }

    private static String protoString(JSONObject o, String k1, String k2, String k3) {
        for (String k : new String[]{k1, k2, k3}) {
            String v = Proto.optString(o, k, "");
            if (!v.isEmpty()) return v;
        }
        return "";
    }

    private static String unquote(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.startsWith("\"") || t.startsWith("\'")) t = t.substring(1);
        int end = t.indexOf('"');
        int end2 = t.indexOf('\'');
        if (end < 0 || (end2 >= 0 && end2 < end)) end = end2;
        if (end > 0) t = t.substring(0, end);
        return t.trim();
    }

    /** city="北京", unit=c  →  {"city":"北京","unit":"c"} */
    private static String fnArgsToJson(String inside) {
        JSONObject o = new JSONObject();
        for (String part : splitTop(inside)) {
            int eq = part.indexOf('=');
            if (eq < 0) eq = part.indexOf(':');
            if (eq <= 0) continue;
            String k = part.substring(0, eq).trim();
            String v = unquote(part.substring(eq + 1).trim());
            if (k.isEmpty()) continue;
            try {
                o.put(k, v);
            } catch (Exception ignored) {
            }
        }
        return o.toString();
    }

    /** 按逗号切，但不切引号／括号里面的。 */
    private static java.util.List<String> splitTop(String s) {
        java.util.List<String> out = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                cur.append(c);
                if (c == quote) quote = 0;
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                cur.append(c);
                continue;
            }
            if (c == '(' || c == '[' || c == '{') depth++;
            if (c == ')' || c == ']' || c == '}') depth--;
            if (c == ',' && depth == 0) {
                out.add(cur.toString());
                cur.setLength(0);
                continue;
            }
            cur.append(c);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }
}
