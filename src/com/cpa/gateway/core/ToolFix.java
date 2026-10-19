package com.cpa.gateway.core;

import org.json.JSONObject;

/**
 * 工具调用参数的"修 + 验"。
 *
 * 弱模型（尤其是那些纯聊天/嘴炮模型）发回来的 tool_call 经常是这样的：
 *   · 空的                    → 参数没有，应该给 {@code {}}
 *   · ```json …``` 围栏包着   · 前面还有一句"好的，我来调用工具：{…}"
 *   · 两个 JSON 粘在一起      · 被 max_tokens 截断，右括号没了
 *   · 单引号、尾逗号          · 干脆写成 get_weather(city=北京) 这种函数写法
 *
 * 这些东西**绝不能流到客户端**——客户端解析不了就整轮对话崩掉。
 * 能修就修，修不好由调用方决定是换账号重试还是降级成纯文本。
 */
final class ToolFix {

    private ToolFix() {
    }

    /** 参数坏到修不了。上层会把它当成"这个账号这轮不行"。 */
    static final class BadToolCall extends Exception {
        BadToolCall(String name, String raw) {
            super("工具参数不是合法 JSON（" + name + "）：" + Upstreams.shortenPublic(raw));
        }
    }

    /**
     * 尽力把参数修成合法 JSON 对象。
     *
     * @return 合法 JSON 对象的字符串；修不了返回 null
     */
    static String repairArgs(String raw) {
        if (raw == null) return "{}";
        String s = raw.trim();
        if (s.isEmpty()) return "{}";

        s = stripFences(s);
        String block = firstBalancedObject(s);

        // 逐个候选：能解析就**重新序列化成严格 JSON**再返回。
        // 关键：org.json 解析很宽容（{city: 北京} 也认），但发给客户端的必须是严格 JSON，
        // 所以不能"能解析就算过"，必须吐序列化后的结果。
        String[] candidates = {
                block,
                block == null ? null : tidy(block),
                block == null ? null : closeTruncated(tidy(block)),
                tidy(s),
                closeTruncated(tidy(s)),
        };
        for (String c : candidates) {
            String strict = canonical(c);
            if (strict != null) return strict;
        }
        return fnCallToJson(s);            // 函数写法兜底
    }

    /** 名字缺失时，从参数里捞（有些模型把 name 塞进参数）。 */
    static String nameFromArgs(String args) {
        if (args == null) return "";
        String block = firstBalancedObject(stripFences(args));
        if (block == null) return "";
        try {
            JSONObject o = new JSONObject(block);
            for (String k : new String[]{"name", "tool", "tool_name", "function", "action"}) {
                String v = Proto.optString(o, k, "");
                if (!v.isEmpty() && v.matches("[A-Za-z_][A-Za-z0-9_\\-]{0,60}")) return v;
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /* ------------------------------------------------------------- 内部工具 */

    /** 能解析就返回**重新序列化**后的严格 JSON；否则 null。 */
    private static String canonical(String s) {
        if (s == null || s.isEmpty() || s.charAt(0) != '{') return null;
        try {
            return new JSONObject(s).toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 去掉 ```json 围栏。 */
    private static String stripFences(String s) {
        String t = s;
        int fence = t.indexOf("```");
        if (fence < 0) return t.trim();
        int start = t.indexOf('\n', fence);
        if (start < 0) return t.trim();
        int end = t.indexOf("```", start);
        String inner = end < 0 ? t.substring(start) : t.substring(start, end);
        return inner.trim();
    }

    /** 同 firstBalancedObject，供 {@link ToolCallText} 复用（正文提取要走同一套配平逻辑）。 */
    static String firstObjectPublic(String s) {
        return firstBalancedObject(s);
    }

    /** 从一堆废话里取出第一个括号配平的 {…}。 */
    private static String firstBalancedObject(String s) {
        int start = s.indexOf('{');
        if (start < 0) return null;
        int depth = 0;
        boolean inStr = false;
        char quote = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    inStr = false;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                inStr = true;
                quote = c;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) return s.substring(start, i + 1);
            }
        }
        return null;                 // 没配平 → 说明被截断了
    }

    /**
     * 把单引号分隔的字符串翻成 JSON 双引号字符串、去尾逗号。
     *
     * 为什么不能 s.replace('\'', '"') 一把梭：
     *   字符串**内容**里的撇号（it's / don't / 'hello' 引用）会被一起换成双引号，
     *   直接把字符串从中间截断，整个 JSON 报废（实机验证：{"text":"it's fine"} 一 tidy
     *   就再也解析不了）。而工具参数里出现英文撇号非常常见——write_file/shell 命令的
     *   参数几乎必带。结果是这类 tool_call 被整体丢弃，模型表现成"不会调工具"。
     *
     * 这里改成单次扫描、只在"确实处于字符串分隔位置"的单引号才翻成双引号：
     *   · 双引号字符串里的内容原样保留（含 '）；
     *   · 单引号字符串里的 ' 若是 \\' 转义 → 翻成 \\"；若是孤立撇号 → 转义成 \\"。
     *     至于"这个撇号到底算不算结束符"，用向右看一个非空白字符的启发式：
     *     后面是 , } ] : 之一才算结束符，否则按内容处理。
     */
    private static String tidy(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        boolean inStr = false;          // 当前是否在某条字符串里
        char quote = 0;                 // 当前字符串的分隔符（'"' 或 '\''）
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == '\\' && i + 1 < s.length()) {
                    char n = s.charAt(i + 1);
                    if (quote == '\'' && n == '\'') {
                        // 单引号字符串里的 \' → 翻成双引号字符串后改成 \"
                        sb.append("\\\"");
                    } else {
                        sb.append(c).append(n);
                    }
                    i++;
                    continue;
                }
                if (c == '"') {
                    if (quote == '"') {
                        inStr = false;          // 双引号字符串结束
                        sb.append(c);
                    } else {
                        sb.append("\\\"");      // 单引号字符串里的 " → 转义
                    }
                    continue;
                }
                if (c == '\'') {
                    if (quote == '\'') {
                        if (looksLikeStringEnd(s, i)) {
                            inStr = false;
                            sb.append('"');     // 结束分隔符：' → "
                        } else {
                            sb.append(c);       // 内容撇号（it's）原样保留：
                                                // 双引号字符串里的 ' 不需要转义
                        }
                    } else {
                        sb.append(c);           // 双引号字符串里的 ' 原样保留
                    }
                    continue;
                }
                sb.append(c);
                continue;
            }
            // 不在字符串里：'{' 或 ',' 后面的 ' 是键/值的起始分隔符
            if (c == '\'') {
                inStr = true;
                quote = '\'';
                sb.append('"');
            } else if (c == '"') {
                inStr = true;
                quote = '"';
                sb.append(c);
            } else {
                sb.append(c);
            }
        }
        String t = sb.toString();
        t = t.replaceAll(",\\s*([}\\]])", "$1");       // {"a":1,} → {"a":1}
        t = t.replaceAll("(\\{|,)\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*:", "$1\"$2\":");
        return t;
    }

    /**
     * 判断位置 i 的单引号是不是"字符串结束符"：向右看第一个非空白字符，
     * 是 , } ] : 之一才像结束符；否则按字符串内容里的撇号处理。
     */
    private static boolean looksLikeStringEnd(String s, int i) {
        for (int j = i + 1; j < s.length(); j++) {
            char n = s.charAt(j);
            if (n == ' ' || n == '\t' || n == '\n' || n == '\r') continue;
            return n == ',' || n == '}' || n == ']' || n == ':';
        }
        return true;    // 后面什么都没有了，当作结束符（截断场景交给 closeTruncated）
    }

    /** 被 max_tokens 截断时，尽量把 JSON 补完整。 */
    private static String closeTruncated(String s) {
        if (s == null || s.isEmpty()) return null;
        StringBuilder sb = new StringBuilder(s.trim());
        // 先去掉末尾半截的键值（"city": "北 → 去掉）
        int lastComma = sb.lastIndexOf(",");
        int lastColon = sb.lastIndexOf(":");
        if (lastColon > lastComma && lastColon > 0) {
            String tail = sb.substring(lastColon + 1).trim();
            if (tail.startsWith("\"") && (tail.length() < 2 || !tail.substring(1).contains("\""))) {
                sb.setLength(sb.length() - tail.length());
                sb.append("\"\"");
            }
        }
        int need = 0;
        boolean inStr = false;
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (inStr) {
                if (c == '\\') i++;
                else if (c == '"') inStr = false;
            } else {
                if (c == '"') inStr = true;
                else if (c == '{' || c == '[') need++;
                else if (c == '}' || c == ']') need--;
            }
        }
        if (inStr) sb.append('"');
        for (int i = 0; i < need; i++) sb.append('}');
        if (need < 0) return null;
        return sb.toString();
    }

    /** get_weather(city=北京, unit=c) → {"city":"北京","unit":"c"} */
    private static String fnCallToJson(String s) {
        String t = s.trim();
        int open = t.indexOf('(');
        int close = t.lastIndexOf(')');
        if (open <= 0 || close <= open) return null;
        String head = t.substring(0, open).trim();
        if (!head.matches("[A-Za-z_][A-Za-z0-9_\\-\\.]{0,60}")) return null;
        String inner = t.substring(open + 1, close).trim();
        try {
            JSONObject o = new JSONObject();
            if (!inner.isEmpty()) {
                for (String pair : inner.split("[,，]")) {
                    int eq = pair.indexOf('=');
                    if (eq < 0) eq = pair.indexOf(':');
                    if (eq <= 0) continue;
                    String k = pair.substring(0, eq).trim().replaceAll("^[\"']|[\"']$", "");
                    String v = pair.substring(eq + 1).trim().replaceAll("^[\"']|[\"']$", "");
                    if (k.isEmpty()) continue;
                    o.put(k, v);
                }
            }
            return o.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
