package com.cpa.gateway.core;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 工具能力画像：native（真会调）/ bad（打嘴炮）/ unknown。
 *
 * 两条学习途径，**优先第一条**：
 *   1. 从真实流量里学——带 tools 的请求回来后，模型真吐了结构化 tool_calls 就是 native；
 *      把调用写在正文里（"我来调用 get_weather…"）而没吐结构化调用，就是打嘴炮。
 *   2. 遇到还没记录的候选，后台补一次探活（走自己的网关，和 Detect 一个路数），
 *      不占客户端这次请求的延迟。
 *
 * 画像存进账号配置，重启不丢。
 */
final class ToolAbility {

    static final int UNKNOWN = 0;
    static final int NATIVE = 1;
    static final int BAD = 2;

    private ToolAbility() {
    }

    static int state(Config.Provider p, String model) {
        if (p == null || model == null || model.isEmpty()) return UNKNOWN;
        if (p.toolNative.contains(model)) return NATIVE;
        if (p.toolBad.contains(model)) return BAD;
        return UNKNOWN;
    }

    /** 学到一条结果。 */
    static void note(Config.Provider p, String model, boolean nativeOk) {
        if (p == null || model == null || model.isEmpty()) return;
        boolean changed = false;
        synchronized (p) {
            if (nativeOk) {
                changed = p.toolBad.remove(model) || p.toolNative.add(model);
                if (changed) Log.i("工具画像：" + p.name + "/" + model + " → native（真会调）");
            } else {
                changed = p.toolNative.remove(model) || p.toolBad.add(model);
                if (changed) Log.i("工具画像：" + p.name + "/" + model + " → 打嘴炮（写在正文里）");
            }
        }
        if (changed) Gateway.persistSoon();
    }

    /**
     * 正文里像不像一个工具调用。
     *
     * 弱模型会把调用写进普通文本里，客户端拿到这种东西完全没法执行——
     * 这就是"打嘴炮"。
     */
    static boolean looksLikeToolCall(String text) {
        if (text == null || text.length() < 8) return false;
        String t = text;
        // 另一些模型用 Anthropic 风格的 <invoke name=...>...</invoke> 或
        // function_call / invoke 写法，也算"想调工具"
        for (String marker : new String[]{"<tool_call>", "</tool_call>", "<function_call>",
                "</function_call>", "function_call", "\"invoke\"", "<invoke",
                "tool_calls", "\"parameters\"", "\"tool_name\"", "\"arguments\":", "action_input"}) {
            if (t.contains(marker)) return true;
        }
        return false;
    }

    /** 正在探的，别重复开线程。 */
    private static final java.util.Set<String> PROBING =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 还没记录过就后台探一次；不阻塞当前请求。 */
    static void probeAsync(final Config.Provider p, final String model) {
        if (state(p, model) != UNKNOWN) return;
        if (!PROBING.add(p.name + "/" + model)) return;
        Threads.io(() -> {
            try {
                Config cfg = Gateway.config();
                JSONObject body = new JSONObject();
                // 走自己的网关：和 Detect 探活同一个路数，省得重写一遍上游适配
                body.put("model", p.name + "/" + model);
                JSONArray msgs = new JSONArray();
                msgs.put(new JSONObject().put("role", "user")
                        .put("content", "北京天气怎么样？必须用 get_weather 工具查，不要自己编。"));
                body.put("messages", msgs);
                JSONArray tools = new JSONArray();
                JSONObject schema = new JSONObject();
                schema.put("type", "object");
                JSONObject props = new JSONObject();
                props.put("city", new JSONObject().put("type", "string").put("description", "城市名"));
                schema.put("properties", props);
                schema.put("required", new JSONArray().put("city"));
                tools.put(new JSONObject().put("type", "function").put("function",
                        new JSONObject().put("name", "get_weather")
                                .put("description", "查询某个城市的天气").put("parameters", schema)));
                body.put("tools", tools);
                body.put("max_tokens", 96);
                body.put("_ephemeral", true);

                byte[] payload = body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                java.util.Map<String, String> headers = new java.util.HashMap<>();
                headers.put("Authorization", "Bearer " + cfg.apiKey);
                headers.put("X-CPA-Ephemeral", "1");
                // 回环探活用统一 Http 层（连接复用；超时保持原来的 8s/90s）
                Http.Resp resp = Http.call("POST",
                        "http://127.0.0.1:" + cfg.port + "/v1/chat/completions",
                        headers, payload, "application/json", 8000, 90000, false);
                if (resp.code != 200) {
                    Log.w("工具探活失败（" + p.name + "/" + model + "）：HTTP " + resp.code);
                    return;
                }
                JSONObject o = new JSONObject(resp.body);
                JSONArray choices = o.optJSONArray("choices");
                JSONObject msg = choices == null || choices.length() == 0 ? null
                        : choices.optJSONObject(0).optJSONObject("message");
                boolean nativeOk = false;
                if (msg != null) {
                    JSONArray calls = msg.optJSONArray("tool_calls");
                    if (calls != null && calls.length() > 0) {
                        JSONObject fn = calls.optJSONObject(0).optJSONObject("function");
                        String args = fn == null ? "" : Proto.optString(fn, "arguments", "");
                        nativeOk = ToolFix.repairArgs(args) != null;
                    } else if (!looksLikeToolCall(Proto.optString(msg, "content", ""))) {
                        return;     // 模型只是没用工具，说明不了能力问题，不下结论
                    }
                }
                note(p, model, nativeOk);
            } catch (Exception e) {
                Log.w("工具探活异常（" + model + "）：" + e);
            } finally {
                PROBING.remove(p.name + "/" + model);
            }
        });
    }
}
