package com.cpa.gateway.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 重复请求检测。
 *
 * 起因是一个真实故障：某个客户端卡在"重试 → 又失败 → 再重试"里，每 6 秒把**完全相同**的
 * 对话重发一次，连着刷了几百次。网关这边看到的只是"同一个账号、同一个模型、一直被调用"，
 * 于是模型把同一段答案反复吐出来，用户看到的就是"一直在重复同一句话"。
 *
 * 光靠限流治不了：那些请求都成功了，不算失败、不进熔断，只是白白烧额度，
 * 而客户端那边永远收不到它想要的东西，就一直刷下去。
 *
 * 这里的判断很简单：**同一个模型 + 同样的请求内容**在短时间内反复出现 → 判定为卡死重试，
 * 直接明确报错回去（带上出现次数），而不是继续转发。客户端要么自己停下，要么用户能一眼
 * 看到"原来是在原地打转"，而不是对着一段复读的答案发呆。
 *
 * 三个刻意的取舍：
 *
 *   1. **要连续才算**。中间夹了别的请求就清零——正常的多轮对话每轮内容都不同，
 *      偶尔问两遍同样的话也不该被拦。
 *   2. **只在第 N 次才拦**。前两次照常转发（用户可能是真心想问两遍），
 *      第三次才开始报错，并且报错里带上次数。
 *   3. **计数覆盖所有推理请求**（直连模型也要拿到 quality=retry 信号），
 *      但"主动拦截"仍只在虚拟模型入口做——直连的流式响应可能已经写出头，
 *      这时再回头报错只会把流写坏。探活/竞速等 ephemeral 请求不参与计数。
 */
public final class Repeats {

    /** 多久之内的相同请求算"同一轮重试"。 */
    private static final long WINDOW_MS = 60_000L;
    /** 第几次开始拦（前两次放行）。 */
    private static final int ALLOW_TIMES = 2;
    /** 最多记多少条指纹，防止内存无上限。 */
    private static final int MAX_KEYS = 64;

    private static final class Rec {
        long lastAt;
        int count;
        String model;
    }

    /** 按"内容指纹"记录出现情况；accessOrder 让最久没碰的自动淘汰。 */
    private static final Map<String, Rec> SEEN =
            new LinkedHashMap<String, Rec>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Rec> eldest) {
                    return size() > MAX_KEYS;
                }
            };

    private Repeats() {
    }

    /**
     * 看一眼这个请求是不是在原地打转。
     *
     * @return null 表示放行；非 null 是给用户看的原因（含出现次数）
     */
    public static String check(Proto.Req req) {
        int n = note(req);
        return n > ALLOW_TIMES ? String.valueOf(n) : null;
    }

    /**
     * 只记录、不拦截（4.37.0）。所有推理入口在 serve() 里各记一次，
     * 直连模型的质量标签才拿得到真实的"用户又发了一遍"信号。
     *
     * 同一个 Req 对象被多次调用（换号重试）时不会重复计数：计数在第一次
     * note 时就写进 Req.repeatCount，后续直接复用。
     */
    static int note(Proto.Req req) {
        if (req == null || req.ephemeral) return 0;
        if (req.repeatRecorded) return req.repeatCount;
        String fp = fingerprint(req);
        if (fp == null) return 0;

        long now = System.currentTimeMillis();
        synchronized (SEEN) {
            // 过期的清掉，免得"隔了一小时又问一遍"被当成重试
            java.util.Iterator<Map.Entry<String, Rec>> it = SEEN.entrySet().iterator();
            while (it.hasNext()) {
                if (now - it.next().getValue().lastAt > WINDOW_MS) it.remove();
            }
            Rec e = SEEN.get(fp);
            if (e == null) {
                e = new Rec();
                e.model = req.model;
                SEEN.put(fp, e);
            }
            e.lastAt = now;
            e.count++;
            req.repeatRecorded = true;
            req.repeatCount = e.count;
            return e.count;
        }
    }

    /** 内容指纹。取整段对话的哈希，所以"一模一样重发"才会命中。 */
    private static String fingerprint(Proto.Req req) {
        StringBuilder sb = new StringBuilder();
        if (req.system != null) sb.append(req.system);
        for (Proto.Msg m : req.messages) {
            if (m == null || m.parts == null) continue;
            sb.append('\u0001');
            for (Proto.Part p : m.parts) {
                if (p == null) continue;
                sb.append(p.type).append('\u0002');
                if (p.text != null) sb.append(p.text);
                if (p.toolName != null) sb.append(p.toolName);
                if (p.toolCallId != null) sb.append(p.toolCallId);
                if (p.dataBase64 != null) sb.append(p.dataBase64.length());
                sb.append('\u0003');
            }
        }
        // 工具定义也进去：同一段话配不同工具集，是不同请求
        for (Proto.Tool t : req.tools) {
            if (t == null) continue;
            sb.append('\u0004').append(t.name);
        }
        if (req.maxTokens != null) sb.append("/max").append(req.maxTokens);
        if (sb.length() == 0) return null;
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(sb.toString().getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) hex.append(String.format("%02x", d[i]));
            return hex.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 诊断用：现在记着几条指纹。 */
    public static int tracked() {
        synchronized (SEEN) {
            return SEEN.size();
        }
    }

    /**
     * 手动清掉一条指纹。
     *
     * 注意**不要**在"请求成功"时调用它：卡死重试的请求每一次都是成功的（模型照样给答案），
     * 在成功时清零会让计数永远到不了阈值，检测直接失效 —— 这是第一版的 bug。
     * 现在只按时间窗自然过期（见 WINDOW_MS），并只在下面这种场合主动清：
     * 用户换了内容（指纹不同，自然不命中），所以其实很少需要调用。
     */
    public static void clear(Proto.Req req) {
        if (req == null) return;
        String fp = fingerprint(req);
        if (fp == null) return;
        synchronized (SEEN) {
            SEEN.remove(fp);
        }
    }
}
