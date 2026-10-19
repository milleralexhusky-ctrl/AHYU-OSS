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
        /* 短摘要就够用：只需要区分"是不是同一串"，不需要抗碰撞到密码学级别 */
        long h = 1469598103934665603L;      // FNV-1a 64
        for (int i = 0; i < raw.length(); i++) {
            h ^= raw.charAt(i);
            h *= 1099511628211L;
        }
        return "cv_" + Long.toHexString(h);
    }
}
