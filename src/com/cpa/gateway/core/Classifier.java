package com.cpa.gateway.core;

import java.util.Locale;

/**
 * 轻量请求分类器（跑在手机上，不上大模型）。
 *
 * 以前只看客户端填的虚拟模型名（auto / auto-code），
 * 但客户端往往只填一个 `auto` —— 那就得**看请求内容**自己判断。
 *
 * 判错的代价不大：分类只影响"往哪个池子偏"，后面还有打分和兜底。
 */
final class Classifier {

    static final int CHAT = 0;
    static final int CODE = 1;
    static final int LONG = 2;

    private Classifier() {
    }

    /** 一次分类的结果。 */
    static final class Kind {
        final int kind;
        final String why;

        Kind(int kind, String why) {
            this.kind = kind;
            this.why = why;
        }

        boolean code() {
            return kind == CODE;
        }

        boolean longInput() {
            return kind == LONG;
        }

        String label() {
            return kind == CODE ? "code" : kind == LONG ? "long" : "chat";
        }
    }

    /*
     * 代码信号：分级，不再"命中一个就判 code"。
     *
     * 原来的判据是 `for (w : CODE_WORDS) if (contains(w)) return CODE` ——
     * 数组里 22 个词等权，命中任意一个就立刻返回。问题是这些词的**证据强度**差很多：
     *
     *   强信号（几乎只在编程语境出现）：代码块 ```、堆栈、stacktrace、
     *           refactor、compile、debug、docker、sql、单元测试、正则、重构、编译
     *   弱信号（日常中文也常用）：接口、异常、算法、报错、函数、脚本
     *
     * "这个接口有问题"、"有点异常"、"算法推荐" —— 这些都会被误判成 code，
     * 然后请求被推到 auto-code 的池子里，而那个池子的模型对普通对话未必更好。
     *
     * 改成加权累加：强信号 +2，弱信号 +1，累计 ≥ 2 才判 code。
     * 也就是"一个强信号"或"两个独立弱信号"才算数。
     */
    private static final String[] STRONG_CODE = {
            "```", "堆栈", "stacktrace", "refactor", "compile", "debug",
            "docker", "sql", "单元测试", "正则", "重构", "编译", "代码块",
            /*
             * "代码"归强信号，不当弱信号。
             *
             * 离线对照过："帮我写一段代码" 只命中"代码"一个词 ——
             * 如果它算弱信号（1 分），这句会掉回 chat 池，
             * 而它明明就是在要代码。这个词在日常对话里几乎不出现，
             * 只要出现基本就是在说编程。
             */
            "代码",
    };

    private static final String[] WEAK_CODE = {
            "函数", "报错", "异常", "调试", "接口", "算法", "脚本",
            "bug", "git ",
    };

    /** 判为 code 需要累计的证据分。 */
    private static final int CODE_EVIDENCE = 2;

    /**
     * 长文本阈值（字符数）。
     *
     * 这个值没法用 usage.tsv 校准：那一列的 tokIn 不可靠 ——
     * 实测 p50 = 69553、max = 300614，远超任何模型的上下文窗口，
     * 说明某些上游报回来的不是"这次请求的输入 token"，可能是累计值或含缓存。
     * **在不可靠数据上调参，等于用一个错误换另一个错误。**
     *
     * 所以保留 6000，但把理由写清楚，并且注明它只是"往长文池偏"而非硬门槛
     * （判错代价不大：后面还有打分和兜底）。
     */
    private static final int LONG_CHARS = 6000;

    static Kind of(Proto.Req req) {
        if (req == null) return new Kind(CHAT, "空请求");

        String text = flatten(req);
        int len = text.length();

        // 1) 代码证据加权累加
        String lower = text.toLowerCase(Locale.US);
        int evidence = 0;
        StringBuilder hits = new StringBuilder();
        for (String w : STRONG_CODE) {
            if (lower.contains(w)) {
                evidence += 2;
                if (hits.length() > 0) hits.append('/');
                hits.append(w.trim());
            }
        }
        for (String w : WEAK_CODE) {
            if (lower.contains(w)) {
                evidence += 1;
                if (hits.length() > 0) hits.append('/');
                hits.append(w.trim());
            }
        }
        if (evidence >= CODE_EVIDENCE) {
            return new Kind(CODE, "代码证据 " + evidence + "（" + hits + "）");
        }
        // 2) 长输入
        if (len >= LONG_CHARS) return new Kind(LONG, len + " 字符");
        // 3) 默认聊天
        return new Kind(CHAT, len + " 字符");
    }

    /** 请求里有没有图片。图片走不了不支持视觉的通道，会在选路时被跳过。 */
    static boolean hasImage(Proto.Req req) {
        return imageCount(req) > 0;
    }

    /** 图片张数。 */
    static int imageCount(Proto.Req req) {
        if (req == null) return 0;
        int n = 0;
        for (Proto.Msg m : req.messages) {
            if (m == null || m.parts == null) continue;
            for (Proto.Part p : m.parts) {
                if (p != null && Proto.Part.IMAGE.equals(p.type)) n++;
            }
        }
        return n;
    }

    /**
     * 粗估这条请求要吃掉多少 token。
     *
     * 手机端不跑分词器，用"ASCII 4 字符 ≈ 1 token、非 ASCII 1 字符 ≈ 1 token"分开算，
     * 图片按每张 1200 计。用途只有两个：上下文长度过滤、TPM/TPD 预检，
     * 所以宁可略保守（估大一点），别让明显装不下的请求白跑一次上游。
     */
    static int estimateTokens(Proto.Req req) {
        if (req == null) return 0;
        long ascii = 0, wide = 0;
        if (req.system != null) {
            for (int i = 0; i < req.system.length(); i++) {
                if (req.system.charAt(i) < 128) ascii++; else wide++;
            }
        }
        for (Proto.Msg m : req.messages) {
            if (m == null || m.parts == null) continue;
            for (Proto.Part p : m.parts) {
                if (p == null || p.text == null) continue;
                for (int i = 0; i < p.text.length(); i++) {
                    if (p.text.charAt(i) < 128) ascii++; else wide++;
                }
            }
        }
        long tokens = ascii / 4 + wide;
        tokens += (long) imageCount(req) * 1200L;
        tokens += (long) req.messages.size() * 8L + 16L;   // JSON 结构本身的开销
        return (int) Math.min(tokens, Integer.MAX_VALUE);
    }

    /** 把所有消息和 system 拼起来（截断，别为了分类把内存吃满）。 */
    private static String flatten(Proto.Req req) {
        StringBuilder sb = new StringBuilder();
        if (req.system != null) sb.append(req.system);
        for (Proto.Msg m : req.messages) {
            for (Proto.Part p : m.parts) {
                if (Proto.Part.TEXT.equals(p.type) && p.text != null) sb.append(p.text);
                if (sb.length() > 40000) return sb.toString();
            }
        }
        return sb.toString();
    }
}
