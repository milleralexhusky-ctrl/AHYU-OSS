package com.cpa.gateway.core;

/**
 * 结果质量判定（4.35.0）。
 *
 * 目的很朴素：让**日常真实流量自己变成样本**，不用再构造测试用例。
 *
 * 判据刻意只取**结构上可机械判断**的几条，不碰语义：
 *   1. 空输出          —— 一个字都没有
 *   2. 被截断          —— stopReason=length，回答到一半被切断
 *   3. 明显跑题        —— 输出与请求内容零重叠（近似判定）
 *   4. 用户立刻重发    —— 客户端在 60 秒内又发了同一条
 *
 * 为什么不做"答得好不好"的判断：那需要品味，写不进代码，而且判错比不判更糟。
 * 这四条的共同点是**判错了也不会误导**——空就是空，截断就是截断。
 *
 * 评分：
 *   OK     —— 有内容、没截断、用户没立刻重发
 *   EMPTY  —— 空输出
 *   CUT    —— 被截断
 *   OFF    —— 跑题
 *   RETRY  —— 用户立刻重发（说明用户认为不行）
 */
final class Quality {

    static final String OK = "ok";
    static final String EMPTY = "empty";
    static final String CUT = "cut";
    static final String OFF = "off";
    static final String RETRY = "retry";

    private Quality() {
    }

    /**
     * 判一次结果。返回上面五个常量之一。
     *
     * @param reply   这次给客户端的正文（可为空）
     * @param asked   这次请求的正文（用来判跑题，可为空）
     * @param stop    结束原因
     * @param retried 客户端是不是立刻重发了同一条（由 Repeats 提供）
     */
    static String judge(String reply, String asked, String stop, boolean retried) {
        return judge(reply, asked, stop, retried, false);
    }

    /**
     * @param sawToolCall 这次有没有吐出结构化工具调用。
     *        有调用时正文为空是**正常**的（模型直接给调用，不需要解释），
     *        不能判成空回复 —— 否则所有纯工具调用都会被误标成失败。
     */
    static String judge(String reply, String asked, String stop, boolean retried,
                        boolean sawToolCall) {
        String r = reply == null ? "" : reply.trim();

        /* 工具调用本身就算有产出，不参与"空"和"跑题"的判定 */
        if (sawToolCall) return retried ? RETRY : OK;

        /* 1) 空输出：最确定的失败，优先判 */
        if (r.isEmpty()) return EMPTY;

        /* 2) 被截断：上游明说了 length，这是硬证据 */
        if ("length".equals(stop)) return CUT;

        /* 3) 跑题：只在请求有明显内容时才判，避免短问句误判。
             判法很粗——看输出里有没有出现请求中的"关键片段"；
             一只字都不沾边，就认为跑题。宁可漏判，不要误伤正常回答。 */
        if (asked != null && asked.trim().length() >= 8 && r.length() >= 8) {
            if (zeroOverlap(r, asked)) return OFF;
        }

        /* 4) 用户立刻重发：这是最真实的信号——用户觉得没用 */
        if (retried) return RETRY;

        return OK;
    }

    /**
     * 输出和请求是否"零重叠"。
     *
     * 做法：从请求里抽取若干长度 2 的词片段（中英文都适用），
     * 看输出里有没有任何一个出现过。**一个都没有**才判跑题。
     *
     * 为什么不更严格：正常回答也常常不复述原词（比如问"17乘23"答"391"）。
     * 所以真正的判据不是"重叠多少"，而是"输出里有没有任何和请求沾边的字"。
     * 只有完全不沾边才算跑题——那通常意味着模型在自说自话，
     * 正是我们要抓的"问 A 答 B"。
     */
    private static boolean zeroOverlap(String reply, String asked) {
        String a = normalize(asked);
        if (a.length() < 8) return false;
        String r = normalize(reply);
        if (r.isEmpty()) return false;

        /* 抽片段：步长 3 取长度 2 的窗口，覆盖足够但不爆炸 */
        int hits = 0;
        int sampled = 0;
        for (int i = 0; i + 2 <= a.length(); i += 3) {
            String piece = a.substring(i, i + 2);
            if (piece.trim().length() < 2) continue;   // 空白/标点跳过
            sampled++;
            if (r.contains(piece)) hits++;
            if (hits >= 2) return false;               // 有两个片段沾边就不算跑题
            if (sampled >= 12) break;                  // 只看前 12 个片段，够判断
        }
        return sampled >= 4 && hits == 0;
    }

    /** 去掉空白和常见标点，只留下实字，避免标点巧合造成假重叠。 */
    private static String normalize(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }
}
