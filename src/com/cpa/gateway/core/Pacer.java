package com.cpa.gateway.core;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 出站节流：同一个账号的两次上游请求之间留一个最小间隔（见 {@link #MIN_GAP_MS}）。
 *
 * <h2>为什么需要</h2>
 *
 * 网关有重试、换号、竞速三条路，它们都可能让**同一个账号**在很短的时间里连续收到
 * 好几个请求：
 *
 * <ul>
 *   <li>一个请求失败 → 立刻换下一个候选；</li>
 *   <li>{@code auto*} 轮换一次最多试 3~4 个候选；</li>
 *   <li>两个会话同时说话，各自的请求可能落到同一个账号。</li>
 * </ul>
 *
 * 人手动用官方客户端不会在一秒内发出三次请求。这种"机器式连发"是最容易被
 * 上游识别成自动化的特征之一 —— 而一旦被识别，账号被限流、被标记、功能被收走
 * 都是可能的后果。
 *
 * <h2>为什么是"按账号"而不是"按连接/按请求"</h2>
 *
 * 被识别的是**账号**在平台侧的行为，不是我们这边的连接。换连接、换线程都没用，
 * 只有真正把发往同一个账号的请求在时间上摊开才有意义。不同账号之间互不影响 ——
 * 竞速（每路来自不同账号）因此完全不会被拖慢。
 *
 * <h2>代价</h2>
 *
 * 只在上游"刚刚才收过这个账号的请求"时才等待。正常一轮对话里，用户打字的时间
 * 远大于这个间隔，所以体感无变化；真正会等的只有重试和并发——那正是要压的。
 *
 * <p>这个类**不改变任何重试/选路语义**，只加等待。失败了也不抛：节流本身
 * 绝不能把请求带下水（宁可少等，也不能因为节流出错而让请求失败）。
 */
final class Pacer {

    private Pacer() {
    }

    /**
     * 同一个账号两次出站之间的最小间隔。
     *
     * <p>350ms 的依据：真人用官方客户端连续发两条消息，间隔通常在秒级；
     * 而我们的重试间隔原本是 0（失败立刻换号）。取 350ms 足够把"连发"摊成
     * "有间隔的几次"，又不会让一次重试循环多等太久（3 次重试最多多 1 秒）。
     */
    private static final long MIN_GAP_MS = 350L;

    /** 抖动上限：固定间隔本身也是一种特征，加一点随机让它不整齐。 */
    private static final long JITTER_MS = 250L;

    /** 账号 → 下一次允许发送的时刻（毫秒）。只存一个 long，不需要对象。 */
    private static final ConcurrentHashMap<String, long[]> NEXT_AT = new ConcurrentHashMap<>();

    /**
     * 发往这个账号之前调用；必要时等待。
     *
     * @param account 账号标识（用 {@code Provider.name}，同一账号的模型共用一个槽）
     */
    static void await(String account) {
        if (account == null || account.isEmpty()) return;
        try {
            long wait;
            long[] slot = NEXT_AT.computeIfAbsent(account, k -> new long[1]);
            synchronized (slot) {
                long now = System.currentTimeMillis();
                wait = slot[0] - now;
                if (wait < 0) wait = 0;
                long gap = MIN_GAP_MS + (JITTER_MS > 0
                        ? ThreadLocalRandom.current().nextLong(JITTER_MS + 1) : 0L);
                /* 预约下一次：从现在或上一次预约的更晚者开始算。 */
                slot[0] = (wait > 0 ? slot[0] : now) + gap;
            }
            if (wait > 0) {
                Thread.sleep(wait);
            }
        } catch (InterruptedException e) {
            /* 被打断（关网关/取消请求）就到此为止，不要把中断状态吞掉。 */
            Thread.currentThread().interrupt();
        } catch (Throwable ignored) {
            /* 节流是锦上添花：任何异常都不能让请求失败。 */
        }
    }

    /** 自检用：清掉某个账号的预约，避免测试之间的残留互相影响。 */
    static void resetForTest(String account) {
        if (account != null) NEXT_AT.remove(account);
    }

    static long minGapMsForTest() {
        return MIN_GAP_MS;
    }
}
