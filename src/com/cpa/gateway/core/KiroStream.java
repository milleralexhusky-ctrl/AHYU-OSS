package com.cpa.gateway.core;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Kiro（Amazon Q Developer / CodeWhisperer）上游流的解析。
 *
 * <h2>这不是 SSE</h2>
 *
 * 上游回的是 AWS EventStream（`application/vnd.amazon.eventstream`）：
 * 每帧 = prelude + headers + payload，payload 里是一个 JSON 文本事件。
 * **本解析器不解二进制帧**：实测字节流里 payload 的 JSON 是连续可见 UTF-8，
 * 中间的结构字节不含 `{"content":` 这类起始串，所以按文本扫起始串、
 * 再用括号配对找 JSON 结尾就够（参考实现 jwadow/kiro-gateway 也是这么做的，
 * 见其 parsers.py 的 AwsEventStreamParser —— 那里也没有 CRC/prelude 解析）。
 * 这样做的代价是：payload 里若真出现同名字段会被误当事件；收益是不必实现
 * 一整套帧协议，且对分帧不敏感。
 *
 * <p>本文件是**独立实现**：参考实现是 AGPL-3.0，只借它的协议知识（HTTP 形状、
 * 字段名），代码不抄。
 *
 * <h2>为什么要处理"跨 chunk 半截 JSON"</h2>
 *
 * 网络分片会把一个 JSON 事件切成两半。所以不是"每来一块解析一块"，而是
 * 攒在 buffer 里、只在括号配平后才取走；配不平就留着等下一块。
 *
 * <h2>增量语义</h2>
 *
 * 上游的 `content` 事件给的是**累计全文**而不是增量（每帧重复整段，
 * 且逐帧变长），所以这里要和上一次比较、只吐新增部分；完全相同就丢弃
 * （上游会把同一段重发多次）。工具调用同理：`{"name":…,"input":…}` 起一个，
 * 后续 `{"input":…}` 是参数分片，`{"stop":true}` 收尾。
 */
final class KiroStream {

    /** 起始串 → 事件类型。按串长降序试，避免 `{"input":` 抢匹配到更长的串。 */
    private static final String[][] PATTERNS = {
            {"{\"content\":", "content"},
            {"{\"name\":", "tool_start"},
            {"{\"input\":", "tool_input"},
            {"{\"stop\":", "tool_stop"},
            {"{\"followupPrompt\":", "followup"},
            {"{\"usage\":", "usage"},
            {"{\"contextUsagePercentage\":", "context_usage"},
    };

    private final StringBuilder buf = new StringBuilder();
    /** 上一次 content 事件的全文，用来算增量。 */
    private String lastContent = "";
    /** 正在组装中的工具调用参数。 */
    private String pendingToolId;
    private String pendingToolName;
    private final StringBuilder pendingArgs = new StringBuilder();

    /**
     * 喂一块原始字节，返回这次能解析出的事件。
     *
     * @param chunk 上游来的原始字节（可能是半截 JSON，也可能一块里含多帧）
     */
    List<Event> feed(byte[] chunk) {
        List<Event> out = new ArrayList<>();
        if (chunk == null || chunk.length == 0) return out;
        buf.append(new String(chunk, java.nio.charset.StandardCharsets.UTF_8));
        drain(out);
        return out;
    }

    /** 流结束时把攒着的半个 JSON 丢掉（不是完整事件，留着也解析不出来）。 */
    void end() {
        buf.setLength(0);
    }

    private void drain(List<Event> out) {
        while (true) {
            int[] hit = findEarliest();
            if (hit == null) {
                /* 一条起始串都没有：buffer 里全是结构字节/无关内容，清掉免得无限增长。
                   留一点尾巴：起始串可能正好被切成两半（比如末尾是 `{"conte`）。 */
                if (buf.length() > 64) buf.delete(0, buf.length() - 64);
                return;
            }
            int start = hit[0];
            String type = PATTERNS[hit[1]][1];
            int end = matchBrace(buf, start);
            if (end < 0) {
                /* JSON 还没收完：把起始串之前的内容丢掉，留着等下一块。 */
                if (start > 0) buf.delete(0, start);
                return;
            }
            String json = buf.substring(start, end + 1);
            buf.delete(0, end + 1);
            try {
                handle(type, new JSONObject(json), out);
            } catch (Exception e) {
                /* 单个事件坏了不该毁掉整条流：丢掉它继续。 */
                Log.w("kiro 流事件解析失败（已跳过）：" + Upstreams.shortenPublic(json));
            }
        }
    }

    /** 找最早的起始串，返回 {位置, PATTERNS 下标}；没有则 null。 */
    private int[] findEarliest() {
        int bestPos = -1;
        int bestIdx = -1;
        for (int i = 0; i < PATTERNS.length; i++) {
            int p = buf.indexOf(PATTERNS[i][0]);
            if (p >= 0 && (bestPos < 0 || p < bestPos)) {
                bestPos = p;
                bestIdx = i;
            }
        }
        return bestPos < 0 ? null : new int[]{bestPos, bestIdx};
    }

    /**
     * 从 `{` 起做括号配对，返回配对 `}` 的下标；没收完返回 -1。
     *
     * <p>必须认字符串与转义：`{"content":"他说 {\"a\":1}"}` 里的花括号
     * 不算层级，否则会提前收尾切出坏 JSON。
     */
    private static int matchBrace(CharSequence s, int start) {
        if (start >= s.length() || s.charAt(start) != '{') return -1;
        int depth = 0;
        boolean inStr = false;
        boolean esc = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (esc) {
                esc = false;
                continue;
            }
            if (inStr) {
                if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') inStr = true;
            else if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private void handle(String type, JSONObject o, List<Event> out) {
        switch (type) {
            case "content": {
                /* followupPrompt 是上游的"要不要继续"提示，不是正文，跳过。 */
                if (o.optBoolean("followupPrompt", false)) return;
                String full = o.optString("content", "");
                if (full.isEmpty() || full.equals(lastContent)) return;
                /* 给的是累计全文：只吐新增部分。若不是以旧文开头（上游重来一段），
                   就整段当新增 —— 宁可重复也不能丢内容。 */
                String delta = full.startsWith(lastContent) ? full.substring(lastContent.length()) : full;
                lastContent = full;
                if (!delta.isEmpty()) out.add(Event.text(delta));
                return;
            }
            case "tool_start": {
                flushTool(out);
                pendingToolId = o.optString("toolUseId", "");
                pendingToolName = o.optString("name", "");
                pendingArgs.setLength(0);
                String init = argString(o.opt("input"));
                pendingArgs.append(init);
                if (o.optBoolean("stop", false)) flushTool(out);
                return;
            }
            case "tool_input": {
                if (pendingToolName != null) pendingArgs.append(argString(o.opt("input")));
                return;
            }
            case "tool_stop": {
                if (o.optBoolean("stop", false)) flushTool(out);
                return;
            }
            case "usage": {
                out.add(Event.usage(o.optInt("usage", 0)));
                return;
            }
            default:
                /* context_usage / followup：网关不需要，静默丢弃。 */
        }
    }

    /** 把 input 字段归一成字符串：可能是 JSON 对象（序列化），也可能是字符串（原样）。 */
    private static String argString(Object input) {
        if (input == null) return "";
        if (input instanceof JSONObject) {
            JSONObject jo = (JSONObject) input;
            return jo.length() == 0 ? "" : jo.toString();
        }
        String s = String.valueOf(input);
        return "null".equals(s) ? "" : s;
    }

    private void flushTool(List<Event> out) {
        if (pendingToolName == null) return;
        String args = pendingArgs.toString().trim();
        /* 参数不是合法 JSON 时给个空对象：Sink 那边会按"坏工具参数"记画像，
           比丢一个空字符串进去更容易诊断。 */
        if (args.isEmpty() || !args.startsWith("{")) args = "{}";
        out.add(Event.tool(pendingToolId, pendingToolName, args));
        pendingToolId = null;
        pendingToolName = null;
        pendingArgs.setLength(0);
    }

    /** 一个解析出来的事件。 */
    static final class Event {
        final String kind;      // text / tool / usage
        final String text;
        final String toolId;
        final String toolName;
        final String toolArgs;
        final int credits;

        private Event(String kind, String text, String id, String name, String args, int credits) {
            this.kind = kind;
            this.text = text;
            this.toolId = id;
            this.toolName = name;
            this.toolArgs = args;
            this.credits = credits;
        }

        static Event text(String s) {
            return new Event("text", s, null, null, null, 0);
        }

        static Event tool(String id, String name, String args) {
            return new Event("tool", null, id, null == name ? "" : name, args, 0);
        }

        static Event usage(int credits) {
            return new Event("usage", null, null, null, null, credits);
        }
    }
}
