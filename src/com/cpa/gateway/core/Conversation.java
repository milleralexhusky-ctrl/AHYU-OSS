package com.cpa.gateway.core;

/**
 * 会话标识（4.36.0）。
 *
 * 目的：把同一段对话的多跳请求串起来。多轮工具循环里，客户端会连续发
 * 好几个请求（第 1 轮要到调用，第 2 轮带工具结果再问……），
 * 但它们在 usage.tsv 里原来是完全独立的行，无法知道属于同一轮任务。
 *
 * 做法：对「system + 全部消息内容」取哈希。同一轮对话里，前一跳是后一跳的
 * 前缀，所以每跳的指纹不同但也不重复——用它当 id 能把一跳一跳串起来。
 *
 * 注意：这里**不追求跨会话稳定**。用户改一个字就是新会话，这是对的——
 * 我们要的是"这一串请求属于同一次连续交互"，不是"同一个用户"。
 */
final class Conversation {

    private Conversation() {
    }

    static String id(Proto.Req req) {
        if (req == null) return "";
        StringBuilder sb = new StringBuilder();
        if (req.system != null) sb.append(req.system);
        for (Proto.Msg m : req.messages) {
            if (m == null || m.parts == null) continue;
            sb.append('\u0001').append(m.role == null ? "" : m.role);
            for (Proto.Part p : m.parts) {
                if (p == null) continue;
                sb.append('\u0002').append(p.type);
                if (p.text != null) sb.append(p.text);
                if (p.toolName != null) sb.append(p.toolName);
                if (p.toolCallId != null) sb.append(p.toolCallId);
            }
        }
        String raw = sb.toString();
        return "cv_" + fnv(raw);
    }

    /**
     * 会话指纹：**跨轮次稳定**，用于选路亲和（4.51.5）。
     *
     * 为什么不能直接用 {@link #id}：id 哈希的是 system + **全部**消息，每多一轮
     * 就变一个值。它适合把"一跳一跳"串起来（usage.tsv 里区分同一次连续交互），
     * 但拿它做"下一轮还走上一个候选"的键会永远命不中。
     *
     * 这里只哈希 **system + 第一条 user 消息**：对话开头在后续轮次里不变，
     * 所以同一段会话的每一轮都得到同一个键。代价是"换个问题但 system 相同"
     * 会撞进同一个会话 —— 那正是我们想要的：同一个 agent 会话本来就该复用缓存。
     *
     * 和 WorkBuddy 的 `officialConversationId` 是同一个思路（官方客户端也按
     * "system + 首条 user"派生），所以这里派生出的会话边界与上游一致，
     * 不会造成"网关认为是一段、上游认为是两段"的缓存错配。
     *
     * 拿不到可哈希内容时返回空串，调用方据此跳过亲和。
     */
    static String session(Proto.Req req) {
        if (req == null) return "";
        StringBuilder sb = new StringBuilder();
        if (req.system != null) sb.append(req.system);
        /* 客户端自带的会话提示优先：Claude Code / Anthropic SDK 会发
           prompt_cache_key、OpenAI 会发 metadata.user_id 或 user。
           这些是客户端明确声明"这是同一个会话"，比我们自己猜可靠。 */
        String hint = Proto.optString(req.extraBody, "prompt_cache_key", "");
        if (hint.isEmpty()) hint = Proto.optString(req.extraBody, "user", "");
        if (hint.isEmpty()) {
            org.json.JSONObject md = req.extraBody.optJSONObject("metadata");
            if (md != null) hint = Proto.optString(md, "user_id", "");
        }
        if (!hint.isEmpty()) sb.append('\u0003').append(hint);
        boolean gotUser = false;
        for (Proto.Msg m : req.messages) {
            if (m == null || m.parts == null) continue;
            if (!"user".equals(m.role)) continue;
            for (Proto.Part p : m.parts) {
                if (p == null) continue;
                sb.append('\u0002').append(p.type);
                if (p.text != null) sb.append(p.text);
            }
            gotUser = true;
            break;                      // 只要第一条 user
        }
        if (sb.length() == 0) return "";
        if (!gotUser && hint.isEmpty()) return "";   // 只有 system 不足以区分会话
        return "ss_" + fnv(sb.toString());
    }

    /** FNV-1a 64：只需要区分"是不是同一串"，不需要抗碰撞到密码学级别。 */
    private static String fnv(String raw) {
        long h = 1469598103934665603L;
        for (int i = 0; i < raw.length(); i++) {
            h ^= raw.charAt(i);
            h *= 1099511628211L;
        }
        return Long.toHexString(h);
    }
}
