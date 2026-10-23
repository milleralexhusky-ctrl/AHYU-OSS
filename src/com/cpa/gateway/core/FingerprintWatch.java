package com.cpa.gateway.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 风控指纹对抗的**失效监测**（4.54.0）。
 *
 * <h2>为什么需要它</h2>
 *
 * {@link RiskPrompt} 的零宽混淆（U+200C）能挡住 WorkBuddy 的内容指纹 11128，
 * 靠的是"上游按字节特征匹配"这个前提。这个前提**随时可能不成立**：
 * 上游只要在判据前先做一次 Unicode 归一化（NFKC 会直接吃掉 U+200C），
 * 混淆就整体失效，而失效的表现**和修复前一模一样**——还是 11128。
 *
 * <p>没有监测时，这件事是不可发现的：用户报"Claude Code 又不行了"，
 * 排查的人看到 11128，会先去怀疑账号、额度、UA，
 * 而真正的原因（对抗失效）没有任何一条日志指出来。
 * 4.53.3 的 changelog 把排查入口写成"日志里搜「已混淆 N 处」对不上请求数"——
 * 那是**人肉对账**，实际没人会做。这个类把它变成机器判定。
 *
 * <h2>判据：同账号对照（这是全部要点）</h2>
 *
 * "混淆过的请求被 channel_soft 拦"同时兼容两种解释，必须区分：
 *
 * <table border="1">
 *   <tr><th>成因</th><th>同账号的表现</th></tr>
 *   <tr><td>对抗失效</td><td>中性请求**能过**，混淆请求被拦</td></tr>
 *   <tr><td>账号被上游标记（真机实测存在的形态：国内号常年 11128）</td>
 *       <td>中性请求**也**过不去 —— 报"对抗失效"就是假警报</td></tr>
 * </table>
 *
 * <p>所以报警需要同时满足三条（缺任何一条只计数或静默）：
 * <ol>
 *   <li>被拦的这份 body 里**确实带着**我们插进去的零宽混淆
 *       （{@link #carriesNeutralization}，从真正要发出去的 body 现查，不靠计数推断）；
 *       账号级的那种 11128（conversation id missing / request illegal）
 *       根本走不到这里——{@code Http.softChannel} 只认"unapproved channel / 请重新发送"
 *       这一族措辞，两种成因的文案判据本来就分得开；</li>
 *   <li>**同一个账号**最近有过中性请求成功（{@code lastNeutralOk}）——
 *       它没被整体标记，被拦只能是内容的原因；</li>
 *   <li>那次中性成功**晚于**最近一次"混淆请求成功"（{@code lastNeutralizeOk}）——
 *       若刚才还有混淆请求通过，说明对抗此刻有效，这一单被拦是抖动，不算失效。</li>
 * </ol>
 *
 * <p>13:44 的真机实测抓到过只用第 1 条的版本在**被标记账号**上打假警报
 * （整条 curl 最终 200，告警却来自第一个候选）——后两条就是为它加的。
 * 代价是"账号只跑 Claude Code 流量"时可能漏报：中性通过无从记录。
 * 这是刻意的取舍——假警报的代价是真失效时没人信，漏报的代价只是排查慢一点。
 */
public final class FingerprintWatch {

    private FingerprintWatch() {
    }

    /** 发往 WorkBuddy 的出站尝试次数（不是请求数：一次请求可能重试/换形状多次）。 */
    private static final AtomicLong SENT = new AtomicLong();
    /** 其中确实混淆过指纹的次数。 */
    private static final AtomicLong NEUTRALIZED = new AtomicLong();
    /** 请求级内容指纹风控（channel_soft）拦截次数。 */
    private static final AtomicLong BLOCKED = new AtomicLong();
    /** 其中最要紧的一种：**同账号对照成立**的失效确证。 */
    private static final AtomicLong BLOCKED_WHILE_NEUTRALIZED = new AtomicLong();

    /** 报警节流：首次必报，之后每 {@link #REALERT_EVERY} 次确证再报一次。 */
    private static final long REALERT_EVERY = 20L;

    /** "中性请求能过"的时效：超过就不算数（账号状态会变，旧证据不能永久豁免）。 */
    private static final long NEUTRAL_OK_WINDOW_MS = 24 * 3_600_000L;

    /**
     * 每个账号最近一次中性成功 / 混淆成功的时刻（毫秒）。
     * 0 表示没有发生过。key 用账号显示名（进程内稳定，重启清零可接受——
     * 重启后各账号会重新被真实流量教育）。
     */
    private static final ConcurrentHashMap<String, long[]> ACCOUNTS = new ConcurrentHashMap<>();

    /**
     * 自检期间抑制告警日志。
     *
     * <p>为什么必须抑制：自检会**故意**造一次完整的失效对照来验证判据。
     * 不抑制的话，每跑一次自检（App 启动、设置页、导出诊断包）就往 logs.txt
     * 里写一条 ERROR 级的"对抗很可能已失效" —— 看起来像真故障，
     * 而这恰恰是这个类要防的那种假警报。
     */
    private static volatile boolean alertsMuted = false;

    static void muteAlerts() {
        alertsMuted = true;
    }

    static void unmuteAlerts() {
        alertsMuted = false;
    }

    /**
     * 出站记一笔。
     *
     * @param hits 本次 {@link WorkBuddy#deFingerprint} 实际混淆的指纹条数（0 = 这份请求不带指纹）
     */
    public static void noteSent(int hits) {
        SENT.incrementAndGet();
        if (hits > 0) NEUTRALIZED.incrementAndGet();
    }

    /**
     * 一次成功交付。按 body 里有没有混淆痕迹分成"混淆成功"与"中性成功"两类记账。
     *
     * <p>混淆请求能过本身就是"对抗此刻有效"的直接证据（{@code lastNeutralizeOk}），
     * 这是判据第 3 条的来源。
     */
    public static void noteOk(String provider, JSONObject body) {
        if (provider == null) return;
        long now = System.currentTimeMillis();
        long[] st = ACCOUNTS.computeIfAbsent(provider, k -> new long[2]);
        synchronized (st) {
            if (carriesNeutralization(body)) st[0] = now;   // 混淆请求成功
            else st[1] = now;                                // 中性请求成功
        }
    }

    /**
     * 这份 body 里有没有我们插进去的零宽混淆痕迹。
     *
     * <p>只看 system 消息 —— {@link WorkBuddy#deFingerprint} 也只动 system，
     * 别处出现的零宽字符不是我们的手笔，不能拿来当证据。
     */
    static boolean carriesNeutralization(JSONObject body) {
        if (body == null) return false;
        String z = RiskPrompt.zwnj();
        try {
            JSONArray msgs = body.optJSONArray("messages");
            for (int i = 0; msgs != null && i < msgs.length(); i++) {
                JSONObject m = msgs.optJSONObject(i);
                if (m == null || !"system".equals(Proto.optString(m, "role", ""))) continue;
                Object content = m.opt("content");
                if (content instanceof String) {
                    if (((String) content).contains(z)) return true;
                } else if (content instanceof JSONArray) {
                    JSONArray blocks = (JSONArray) content;
                    for (int k = 0; k < blocks.length(); k++) {
                        JSONObject b = blocks.optJSONObject(k);
                        if (b != null && Proto.optString(b, "text", "").contains(z)) return true;
                    }
                }
            }
        } catch (Throwable ignored) {
            /* 监测本身绝不能把请求带下水：查不出来就当"没混淆过"，不报警。 */
        }
        return false;
    }

    /**
     * 一次请求级内容指纹风控（{@code channel_soft}）拦截。
     *
     * <p>调用点只有一个：{@link WorkBuddy.Up#chat} 里重发用尽、确认放行不了的那一刻。
     * 不在每次重发时都记，否则一次请求会被记成 3 次拦截，比例全失真。
     *
     * @param body 最后一次真正构造出来的请求体（用来现查混淆痕迹）
     */
    public static void noteBlocked(String provider, JSONObject body) {
        BLOCKED.incrementAndGet();
        if (!carriesNeutralization(body)) return;   // 中性内容也被拦 → 账号级问题，不是对抗失效
        long now = System.currentTimeMillis();
        long[] st = provider == null ? null : ACCOUNTS.get(provider);
        long neutralizeOk = 0, neutralOk = 0;
        if (st != null) {
            synchronized (st) {
                neutralizeOk = st[0];
                neutralOk = st[1];
            }
        }
        if (neutralOk == 0 || now - neutralOk > NEUTRAL_OK_WINDOW_MS) {
            /* 这个账号连中性请求都最近没过（或从没记录过成功）：
               被拦无法归因到内容 → 大概率是账号整体被标记，不报。 */
            return;
        }
        if (neutralizeOk >= neutralOk) {
            /* 中性成功之后混淆请求还通过过 —— 对抗此刻是有效的，这单被拦算抖动。 */
            return;
        }
        long n = BLOCKED_WHILE_NEUTRALIZED.incrementAndGet();
        if (alertsMuted) return;   // 自检造的是刻意的对照，不能冒充真故障（见 muteAlerts）
        if (n == 1L || n % REALERT_EVERY == 0L) {
            Log.e("workbuddy：账号 " + provider + " 的中性请求能过、已混淆的指纹请求被内容风控拦下"
                    + "（同账号对照成立，累计确证 " + n + " 次）—— "
                    + "零宽混淆对抗很可能已失效（上游可能改成先做 Unicode 归一化，"
                    + "NFKC 会吃掉 U+200C）。Claude Code 会重新表现为 100% 被拦。"
                    + "请核对 RiskPrompt 的判据是否还成立，别只当成账号问题排查。");
        }
    }

    /** 同账号对照成立的失效确证次数（自检与界面用）。 */
    public static long blockedWhileNeutralized() {
        return BLOCKED_WHILE_NEUTRALIZED.get();
    }

    /**
     * 一行人话，供诊断包与调试端点使用。
     *
     * <p>健康时形如：
     * {@code 指纹对抗：出站尝试 42 · 已混淆 7 · 内容风控拦截 0（失效确证 0）}。
     * 最后一项非 0 是对抗失效的确证（判据见类注释，含同账号对照）。
     */
    public static String summary() {
        long sent = SENT.get();
        long neut = NEUTRALIZED.get();
        long blocked = BLOCKED.get();
        long bad = BLOCKED_WHILE_NEUTRALIZED.get();
        return "指纹对抗：出站尝试 " + sent + " · 已混淆 " + neut
                + " · 内容风控拦截 " + blocked + "（失效确证 " + bad + "）"
                + (bad > 0 ? " ⚠ 对抗可能已失效" : "");
    }

    /**
     * 自检用：把当前状态存成快照，跑完 {@link #restore(Object)}。
     *
     * <p>为什么不用"跑完清零"：诊断包的生成顺序是 **先写 summary、后跑自检**
     * （见 {@link Diagnostics#build()}）。清零的话，用户为了取证导出一次诊断包，
     * 反而把这次取证要用到的证据抹掉了。快照/还原则让自检**只读不写**生产状态。
     *
     * <p>账号表也要快照（否则自检造的账号状态会留在生产里），
     * 且必须**深拷贝** value：{@code HashMap} 的浅拷贝让两边共用同一个 {@code long[]}，
     * 还原时写回去的还是被自检改过的那个数组 —— 等于没还原。
     */
    static Object snapshotFullForTest() {
        java.util.Map<String, long[]> copy = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, long[]> e : ACCOUNTS.entrySet()) {
            long[] v;
            synchronized (e.getValue()) {
                v = e.getValue().clone();
            }
            copy.put(e.getKey(), v);
        }
        return new Object[]{new long[][]{{SENT.get(), NEUTRALIZED.get(), BLOCKED.get(),
                BLOCKED_WHILE_NEUTRALIZED.get()},
                {alertsMuted ? 1L : 0L}}, copy};
    }

    @SuppressWarnings("unchecked")
    static void restore(Object snap) {
        if (!(snap instanceof Object[])) return;
        Object[] pair = (Object[]) snap;
        if (pair.length < 2 || !(pair[0] instanceof long[][])) return;
        long[][] c = (long[][]) pair[0];
        SENT.set(c[0][0]);
        NEUTRALIZED.set(c[0][1]);
        BLOCKED.set(c[0][2]);
        BLOCKED_WHILE_NEUTRALIZED.set(c[0][3]);
        alertsMuted = c[1][0] != 0L;
        ACCOUNTS.clear();
        ACCOUNTS.putAll((java.util.Map<String, long[]>) pair[1]);
    }

}
