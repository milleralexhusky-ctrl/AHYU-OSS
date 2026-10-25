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

    /**
     * 判定阈值：坏样本占比超过它就算 BAD，好样本占比超过它才算 NATIVE。
     *
     * 为什么不能只看"最近一次"：旧实现里 note() 一次成功就把模型从 toolBad 挪走，
     * 于是一个 9 次打嘴炮、1 次蒙对的模型会变成 NATIVE，之后被优先派去干工具活 ——
     * 而它下一次大概率还是把调用写在正文里。
     */
    private static final double BAD_RATIO = 0.25;
    private static final double NATIVE_RATIO = 0.75;
    /** 少于这么多次观测就仍然当未知：1 次成功说明不了"会调工具"。 */
    private static final int MIN_OBS = 2;

    static int state(Config.Provider p, String model) {
        if (p == null || model == null || model.isEmpty()) return UNKNOWN;
        int[] c;
        synchronized (p) {
            c = p.toolStats.get(model);
        }
        if (c != null) {
            int good = c[0], bad = c[1];
            int n = good + bad;
            if (n >= MIN_OBS) {
                double badRatio = (double) bad / n;
                if (badRatio >= BAD_RATIO) return BAD;
                if ((double) good / n >= NATIVE_RATIO) return NATIVE;
                /* 中间地带（例如 3 好 2 坏）：既不该当 native 派活，也不该当 bad 封杀 */
                return UNKNOWN;
            }
        }
        /* 计数还没攒够：退回旧的布尔名单（旧配置里存的就是这个） */
        if (p.toolNative.contains(model)) return NATIVE;
        if (p.toolBad.contains(model)) return BAD;
        return UNKNOWN;
    }

    /** 学到一条结果。 */
    static void note(Config.Provider p, String model, boolean nativeOk) {
        if (p == null || model == null || model.isEmpty()) return;
        boolean changed = false;
        synchronized (p) {
            /* 先攒计数：这份证据不会被后来的单次结果覆盖掉 */
            int[] c = p.toolStats.get(model);
            if (c == null) {
                c = new int[]{0, 0};
                p.toolStats.put(model, c);
            }
            if (nativeOk) c[0]++; else c[1]++;
            changed = true;
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

    /**
     * 每个账号最近一次工具探活的时间。
     *
     * 没有这个节流时，一轮轮换会对同一个账号的多个候选各起一次探活；
     * 上游看到的是"同一个号在几秒内发来一串 get_weather 请求"，
     * 既浪费额度，也容易被风控当成异常活动。30 秒内只探一次足够。
     */
    private static final java.util.Map<String, Long> LAST_PROBE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 探活**失败**后的退避（4.58.0）。
     *
     * <p>为什么必须有：探活失败时不写任何结论（`state` 仍是 UNKNOWN），
     * 于是下一个带工具的请求又会对**同一个坏模型**重探一次 —— 30 秒一次，永不停止。
     * 真机日志里 `WorkBuddy 1/glm-5.1` 一个坏模型被探了 **114 次**（其中 106 次 HTTP 500），
     * 每次都是一次真实上游调用，全部落空。
     *
     * <p>失败次数翻倍退避（1 → 2 → 4 → 8 … 分钟，封顶 30 分钟），
     * 探活**成功**（无论最后结论是 native / bad / 没下结论）就清零 ——
     * 那时模型是可用的，没有理由继续躲着它。
     *
     * <p>值：{失败次数, 下次允许探活的时刻}。
     */
    private static final java.util.Map<String, long[]> PROBE_BACKOFF =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long PROBE_BACKOFF_BASE_MS = 60_000L;
    private static final long PROBE_BACKOFF_MAX_MS = 30 * 60_000L;

    /** 这次探活失败：翻倍退避。 */
    private static void noteProbeFailed(String key) {
        long[] st = PROBE_BACKOFF.computeIfAbsent(key, k -> new long[]{0, 0});
        synchronized (st) {
            st[0]++;
            long wait = PROBE_BACKOFF_BASE_MS << Math.min(st[0] - 1, 5);   // 1,2,4,8,16,32 分钟
            if (wait > PROBE_BACKOFF_MAX_MS) wait = PROBE_BACKOFF_MAX_MS;
            st[1] = System.currentTimeMillis() + wait;
        }
    }

    /** 这次探活拿到了可用响应：清零退避。 */
    private static void noteProbeReachable(String key) {
        PROBE_BACKOFF.remove(key);
    }

    /** 包可见：自检用，看这个 key 还要多久才允许再探（0 = 现在就可以）。 */
    static long probeBackoffRemainingMs(String key) {
        long[] st = PROBE_BACKOFF.get(key);
        if (st == null) return 0L;
        synchronized (st) {
            long left = st[1] - System.currentTimeMillis();
            return left > 0 ? left : 0L;
        }
    }

    /** 包可见：自检用，直接记一次失败（不真的发请求）。 */
    static void noteProbeFailedForTest(String key) {
        noteProbeFailed(key);
    }

    /** 包可见：自检用，清掉一个 key 的退避。 */
    static void forgetProbeBackoff(String key) {
        PROBE_BACKOFF.remove(key);
    }

    /** 还没记录过就后台探一次；不阻塞当前请求。 */
    static void probeAsync(final Config.Provider p, final String model) {
        if (state(p, model) != UNKNOWN) return;
        long now = System.currentTimeMillis();
        Long last = LAST_PROBE.get(p.name);
        if (last != null && now - last < 30_000L) return;
        /* 账号正在额度/凭证回避里：探活也只会再撞一次同样的墙，
           而且这次调用本身还算在用户账号上。等回避过去再说。 */
        if (Router.accountAvoided(p.name, now)) return;
        String key = p.name + "/" + model;
        long[] backoff = PROBE_BACKOFF.get(key);
        if (backoff != null) {
            synchronized (backoff) {
                if (backoff[1] > now) return;
            }
        }
        if (!PROBING.add(key)) return;
        LAST_PROBE.put(p.name, now);
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
                    noteProbeFailed(key);
                    Log.w("工具探活失败（" + p.name + "/" + model + "）：HTTP " + resp.code);
                    return;
                }
                noteProbeReachable(key);
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
                noteProbeFailed(key);
                Log.w("工具探活异常（" + model + "）：" + e);
            } finally {
                PROBING.remove(key);
            }
        });
    }
}
