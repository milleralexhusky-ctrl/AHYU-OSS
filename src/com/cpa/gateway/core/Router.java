package com.cpa.gateway.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 虚拟模型 + 打分选路 + 自动换号 + 冷却。
 *
 * 客户端只写一个名字（auto / auto-code / auto-long），网关自己决定走哪个账号的哪个模型；
 * 失败了自动换下一个，客户端无感。
 *
 * 打分公式（在 cctapi 那套上按我们**实际有的数据**裁过——没有的数据就不装）：
 *
 *     score = 100
 *           - (priority - 1) * 5          优先级
 *           + 成功率 * 30
 *           - 错误率 * 50
 *           - 200   额度用尽
 *           - 100   冷却中
 *           - 50    刚报过错
 *           + 40    工具能力匹配（留给问题三，暂时恒为 0）
 */
public final class Router {

    private Router() {
    }

    /* ------------------------------------------------------------ 虚拟模型 */

    /** 三个内置虚拟模型。 */
    public static final String AUTO = "auto";
    public static final String AUTO_CODE = "auto-code";
    public static final String AUTO_LONG = "auto-long";
    public static final String AUTO_RACE = "auto-race";

    private static final String[] VIRTUALS = {AUTO, AUTO_CODE, AUTO_LONG, AUTO_RACE};

    public static boolean isVirtual(String model) {
        if (model == null) return false;
        for (String v : VIRTUALS) if (v.equalsIgnoreCase(model)) return true;
        return false;
    }

    public static String[] virtuals() {
        return VIRTUALS.clone();
    }

    /** 明显不是聊天模型的，别进候选池。 */
    private static final String[] JUNK = {
            "image", "tts", "embed", "rerank", "whisper", "video", "audio",
            "moderation", "completion-", "-taco-", "codewise-", "auto-review", "seedream"
    };

    private static boolean junk(String model) {
        String m = model.toLowerCase(Locale.US);
        for (String j : JUNK) if (m.contains(j)) return true;
        return false;
    }

    /**
     * 这个模型名看起来是"非聊天"的（生图 / 生视频 / 补全 / 重写 / 向量 / 语音）。
     *
     * 对外公开，给 {@link Server#allModels} 过滤模型列表用 —— 判据和内部选路完全一致，
     * 避免出现"选路时当垃圾排除、列表里又热情推荐"这种自相矛盾。
     *
     * 实测这些模型在本网关里选了必然失败（kling-video-v3 / gpt-image-2.5-sunburst 返回
     * "Backend [maas] is not supported"；qwen-image-3.0-pro / Hy-Image-3.5-preview 返回
     * "该模型暂未开放"；codewise-completions 返回模型不存在）。它们仍然**可以手动点名调用**
     * （过滤只作用于对外列表），只是不再主动列出来让客户端去踩。
     */
    public static boolean nonChat(String model) {
        return model != null && junk(model);
    }

    /**
     * 这个账号的这个模型能不能吃图。
     *
     * 两层判断，缺一不可：
     *   · 账号级 {@link Config.Provider#vision} 是**用户的明确意愿**（默认 true，
     *     已知不吃图的账号由用户关掉）；
     *   · 模型级来自 {@link ModelCaps} 的能力表（例如 deepseek-chat 就不带 VISION）。
     *
     * 为什么必须补上模型级：原来只看账号级时，"账号里有能看图的模型"和"账号整体标了
     * vision=true"会给出一个**错的候选** —— 一个根本不吃图的模型被选中，请求发出去
     * 必然失败。客户端那侧看到的是"图我发了，模型却说没看到图"，而且没有任何报错
     * 线索指向"选错模型"，排查方向很容易被带偏。
     *
     * 查不到能力时返回 true（不拦）：能力表是本地推断，宁可让上游去判，
     * 也不要因为本地表少一条就把本来能用的模型误杀 —— 那正好和旧行为一致。
     */
    static boolean modelSeesImages(Config.Provider p, String model) {
        if (p != null && !p.vision) return false;
        try {
            ModelCaps.Caps caps = ModelCaps.lookup(model);
            if (caps != null && caps.any()) return caps.has(ModelCaps.VISION);
        } catch (Throwable ignored) {
        }
        return true;
    }

    /** auto-code 偏好的模型名特征。 */
    private static final String[] CODE_HINT = {
            "claude", "gpt-5", "deepseek", "kimi-k", "glm-5", "qwen", "code", "minimax-m"
    };

    /** auto-long 偏好的模型名特征。 */
    private static final String[] LONG_HINT = {
            "gemini", "kimi", "claude", "glm-5", "qwen", "deepseek"
    };

    private static boolean hint(String model, String[] hints) {
        String m = model.toLowerCase(Locale.US);
        for (String h : hints) if (m.contains(h)) return true;
        return false;
    }

    /* -------------------------------------------------------------- 运行时数据 */

    /** 一个"账号/模型"的运行表现。 */
    static final class Stat {
        int ok;
        int fail;
        int streak;              // 连续失败次数
        long cooldownUntil;      // 毫秒时间戳；> now 表示熔断 OPEN
        long lastFailAt;
        long latencyEwma;        // 指数平滑的耗时，给"快"排序用
        int openCount;           // 进过几次 OPEN（指数退避用）
        long probingAt;          // > 0 表示有一个请求正在做 HALF_OPEN 试探
        long throttleUntil;      // 上游限流（429/503/529）的短暂回避，不计入熔断
        /**
         * 上游**自己写明**的恢复时刻（epoch 毫秒），0 = 上游没说、只是我们估的。
         *
         * 为什么和 throttleUntil 分开存：throttleUntil 里混着两种性质完全不同的值 ——
         * "上游说 05:00 恢复"（事实）和"429 了先躲 5 分钟"（我们的猜测）。
         * 只有前者适合用来**直接拒绝**请求：按猜的值拒绝，可能把一个其实已经好的
         * 账号挡在门外；按上游明说的时刻拒绝，只是省掉一次注定 429 的往返。
         */
        long throttleResetAt;

        /**
         * 连续被限流的次数（成功一次就清零），用来做限流退避的指数递增。
         *
         * 原来每次 429 都从同一个基线开始（比如 60 秒），于是一个持续被限流的账号
         * 会以固定节奏反复撞墙：撞完等 60 秒，撞完又等 60 秒。
         * 而限流往往不会那么快解除（尤其是免费池），固定间隔的代价是
         * 持续浪费往返 + 日志刷屏。
         */
        int throttleStreak;
    }

    /**
     * 运行时统计表的容量上限。
     *
     * key 是"账号/模型"，11 个账号 × 各自几十个模型就能到几百条，
     * 而这张表以前**只增不减**——长期运行会一直涨（每个 entry 还带 9 个字段）。
     * 2048 对单机网关足够宽裕，同时把内存封住。
     */
    private static final int STATS_MAX = 2048;

    private static final Map<String, Stat> STATS =
            Collections.synchronizedMap(new LinkedHashMap<String, Stat>());

    private static Stat stat(String key) {
        synchronized (STATS) {
            Stat s = STATS.get(key);
            if (s == null) {
                if (STATS.size() >= STATS_MAX) evictLocked();
                s = new Stat();
                STATS.put(key, s);
            }
            return s;
        }
    }

    /**
     * 淘汰（调用方必须已持有 STATS 锁）。
     *
     * 分两轮：先清"完全干净"的条目（没熔断、没冷却、没探针、没失败计数），
     * 这些丢了也不影响任何判断；不够再按插入顺序（LinkedHashMap 最老的在前）继续清。
     *
     * 为什么要分两轮：直接清最老的会把**正在熔断中**的账号状态抹掉，
     * 那等于让一个刚被熔断的账号立刻重新参与调度——比不淘汰还糟。
     */
    private static void evictLocked() {
        long now = System.currentTimeMillis();
        int target = STATS_MAX / 8;     // 一次腾出 1/8，别每次插入都淘汰
        if (target < 1) target = 1;
        int removed = 0;
        // 第一轮：只清干净的
        java.util.Iterator<java.util.Map.Entry<String, Stat>> it = STATS.entrySet().iterator();
        while (it.hasNext() && removed < target) {
            Stat s = it.next().getValue();
            boolean busy = s.cooldownUntil > now || s.probingAt > 0 || s.throttleUntil > now
                    || s.streak > 0 || s.fail > 0;
            if (!busy) {
                it.remove();
                removed++;
            }
        }
        // 第二轮：还不够就按最老的清（此时剩下的都带状态，只能按时间让位）
        if (removed < target) {
            it = STATS.entrySet().iterator();
            while (it.hasNext() && removed < target) {
                it.next();
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            Log.i("路由统计表已达上限 " + STATS_MAX + "，淘汰 " + removed + " 条（保留熔断/冷却中的条目）");
        }
    }

    /** 连续失败几次就熔断（OPEN）。 */
    private static final int FAIL_STREAK_LIMIT = 3;
    /** 熔断冷却：第一次 60 秒，之后指数退避，上限 10 分钟。 */
    private static final long COOLDOWN_BASE_MS = 60_000L;
    private static final long COOLDOWN_MAX_MS = 10 * 60_000L;
    /** 半开试探的宽限期：试探请求超过这个时间没回音就作废，免得账号被永久排除。 */
    private static final long PROBE_GRACE_MS = 90_000L;

    /** 指数退避：60s → 120s → 240s → 480s → 600s（上限）。 */
    private static long backoff(int openCount) {
        long ms = COOLDOWN_BASE_MS;
        for (int i = 0; i < openCount && ms < COOLDOWN_MAX_MS; i++) ms *= 2;
        return Math.min(ms, COOLDOWN_MAX_MS);
    }

    /**
     * 记一次"上游限流"：短暂回避这个账号/模型，但**不动**失败计数与熔断状态。
     *
     * 429 的语义是"现在别来"，按上游给的 Retry-After 退避就行；把它当失败记，
     * 会把健康账号打进 60 秒→10 分钟的熔断，比限流本身还伤。
     */
    public static void recordThrottled(String key, long retryAfterMs, String kind) {
        recordThrottled(key, retryAfterMs, kind, false);
    }

    /**
     * 同上，但可以声明"这个时长来自上游明确给出的恢复时刻"。
     *
     * 为什么要区分：默认退避是我们拍脑袋的值（429→5 分钟），用一个上限截断它是合理的
     * ——万一上游给了个离谱的 Retry-After，不能让账号被钉死太久。
     * 但上游**直接写明**"将在 01:24:11 重置"时不一样：那是事实，不是猜测。
     * 原先这里统一截断在 1 小时，于是实测中一个"限流到 01:24"（约 106 分钟）的账号
     * 只被回避 60 分钟，之后又去撞一次墙 —— 上游明说的话被我们自己的上限吃掉了。
     * 所以：来自上游时刻表的最多认到 6 小时，其余仍按 1 小时截断。
     */
    public static void recordThrottled(String key, long retryAfterMs, String kind,
                                       boolean fromUpstreamClock) {
        recordThrottled(key, retryAfterMs, kind, fromUpstreamClock, 0L);
    }

    /**
     * @param resetAtMs 上游明写的恢复时刻（epoch 毫秒），0 = 上游没说。
     *                  只有它是真的（>0）时才会被记下来供 {@link #confirmedThrottleUntil} 使用。
     */
    public static void recordThrottled(String key, long retryAfterMs, String kind,
                                       boolean fromUpstreamClock, long resetAtMs) {
        Stat s = stat(key);
        long cap = fromUpstreamClock ? 6 * 3_600_000L : 3_600_000L;
        long base = Math.max(1000, Math.min(retryAfterMs, cap));

        /*
         * 指数递增：连续被限流时把暂避时长按 2^streak 放大，封顶 8 倍。
         *
         * 为什么用指数而不是线性：如果限流的原因是"配额在刷新周期内被耗尽"，
         * 那么在第 n 次撞墙时，剩余等待时间的分布并不随 n 变短 ——
         * 线性递增会持续以较高频率重复无效尝试，指数则让无效尝试的次数
         * 按几何级数收敛（总次数从 O(T/base) 降到 O(log T)）。
         *
         * 封顶 8 倍（配 60 秒基线 = 最长 8 分钟）：再长就会让一个其实已经恢复
         * 的账号被无谓挡在门外，而免费池的限额通常是分钟级刷新的。
         */
        int streak;
        synchronized (STATS) {
            streak = s.throttleStreak;
        }
        long ms = base;
        if (!fromUpstreamClock && streak > 0) {
            int shift = Math.min(streak, 3);
            ms = Math.min(base << shift, cap);
        }
        /* fromUpstreamClock 时 ms 就是 base：上游明说恢复时刻，那是事实，不做放大 */
        if (fromUpstreamClock && retryAfterMs > cap) {
            Log.w("上游给的恢复时刻超过 " + (cap / 3_600_000L) + " 小时，按上限回避 → " + key);
        }
        if ("quota".equals(kind)) {
            ms = Math.max(ms, 30 * 60_000L);
            Log.w("上游额度耗尽：暂避 " + (ms / 60000) + " 分钟 → " + key);
        } else if ("auth".equals(kind)) {
            ms = Math.max(ms, 30 * 60_000L);
            Log.w("上游凭证失效：暂避 " + (ms / 60000) + " 分钟（需要重新登录）→ " + key);
        } else if ("local".equals(kind)) {
            Log.w("本地限额：暂避 " + (ms / 1000) + " 秒（不计失败、不进熔断）→ " + key);
        } else {
            Log.w("上游限流：暂避 " + (ms / 1000) + " 秒（不计失败、不进熔断）→ " + key);
        }
        // 字段读写必须在 STATS 锁里：stat() 只保证"取/建对象"原子，
        // 返回后光秃秃地改字段，多线程并发时会丢更新。
        synchronized (STATS) {
            s.throttleUntil = System.currentTimeMillis() + ms;
            s.throttleResetAt = (fromUpstreamClock && resetAtMs > 0L) ? resetAtMs : 0L;
            if (fromUpstreamClock) {
                s.throttleStreak = 0;
            } else if (s.throttleStreak < 100) {
                s.throttleStreak++;
            }
            // 半开试探若遇到 429，放掉 probingAt 标记，等 throttleUntil 到点后还能继续试探
            if (s.probingAt > 0) s.probingAt = 0;
        }
    }

    /**
     * 这个"账号/模型"被上游**明确告知**要等到什么时候；没被告知返回 0。
     *
     * 只认上游自己写的时刻，不认我们估的退避 —— 见 {@link Stat#throttleResetAt}。
     */
    public static long confirmedThrottleUntil(String key) {
        if (key == null || key.isEmpty()) return 0L;
        Stat s = STATS.get(key);
        if (s == null) return 0L;
        long at;
        synchronized (STATS) {
            at = s.throttleResetAt;
        }
        return at > System.currentTimeMillis() ? at : 0L;
    }

    public static void recordOk(String key, long ms) {
        Stat s = stat(key);
        synchronized (STATS) {
            s.ok++;
            if (s.openCount > 0 || s.probingAt > 0) {
                Log.i("熔断恢复 CLOSED：" + key + "（半开试探成功）");
            }
            s.streak = 0;
            s.cooldownUntil = 0;
            s.openCount = 0;
            s.probingAt = 0;
            /* 成功一次就重置限流退避计数：说明这个账号现在能用了 */
            s.throttleStreak = 0;
            // **不**清 throttleUntil：上游说过"限流，等 60 秒"，一次成功不代表那个回避该撤销。
            // 这条正是"免费池反复撞 429"的成因——一个请求碰巧成功（可能是别的模型、
            // 也可能只是抢到了配额），就把回避抹掉，下一个立刻又撞上去。
            // throttleUntil 是"到点自动失效"的绝对时间，留着它会自然过期，不需要这里清。
            s.latencyEwma = s.latencyEwma == 0 ? ms : (s.latencyEwma * 3 + ms) / 4;
        }
    }

    /**
     * 记一次失败，并按三态决定要不要熔断：
     *   CLOSED（正常）→ 连续失败到阈值 → OPEN（指数退避冷却）
     *   OPEN → 冷却到点 → HALF_OPEN（只放一个试探请求）
     *   HALF_OPEN 再失败 → 直接回 OPEN，冷却翻倍（不浪费更多请求去撞墙）
     */
    public static void recordFail(String key, String why) {
        Stat s = stat(key);
        // 整段在锁里：s.streak++ 与 s.streak >= FAIL_STREAK_LIMIT 的判定必须是一个原子单元，
        // 否则并发下两个失败可能只加一次、或同时过阈值导致重复开熔断。
        synchronized (STATS) {
            s.fail++;
            s.streak++;
            s.lastFailAt = System.currentTimeMillis();
            boolean wasProbing = s.probingAt > 0;
            s.probingAt = 0;
            if (s.streak >= FAIL_STREAK_LIMIT || wasProbing) {
                long cool = backoff(s.openCount);
                s.openCount++;
                s.cooldownUntil = System.currentTimeMillis() + cool;
                Log.w((wasProbing ? "半开试探失败，回到 OPEN：" : "熔断 OPEN：连续失败 ")
                        + s.streak + " 次，冷却 " + (cool / 1000) + " 秒（第 " + s.openCount + " 次）→ " + key);
            }
        }
    }

    /**
     * 作废一次半开试探（不计成功、也不计失败）。
     *
     * 为什么需要：`candidates()` 在 HALF_OPEN 时会给 key 打上 `probingAt` 标记，
     * 而清除它的地方以前只有 `recordOk` / `recordFail` 两个。
     * 但一次试探完全可能走到**既非成功也非失败**的出口——
     * 客户端断开（ClientGone）、请求本身非法（InvalidRequest）、竞速落败（RaceLost）、
     * 被掐线的输家。这些路径都不记成功也不记失败，于是 `probingAt` 永远留着，
     * 该 key 之后每次都被 90 秒宽限期挡住，**退避计数永不归零**（半开自锁）。
     *
     * 这类路径应当调用本方法：把试探标记放掉，让这个 key 下一轮能重新参与。
     * 注意只放标记，**不动熔断状态**——本次试探没有结论，冷却该继续就继续。
     */
    /**
     * 真正发起请求前，若该候选处于 HALF_OPEN（openCount > 0），打上 probingAt 标记。
     *
     * 为什么不在 candidates() 遍历时打：candidates() 会遍历池里所有候选，还会被
     * describe() 或 raceCandidates() 多次调用；如果在枚举列表时就把每个 HALF_OPEN
     * 候选都设上 probingAt，那些根本没被选去发请求的候选也会被误锁 90 秒。
     */
    public static void markProbingIfHalfOpen(String key) {
        if (key == null || key.isEmpty()) return;
        synchronized (STATS) {
            Stat s = STATS.get(key);
            if (s != null && s.openCount > 0) {
                s.probingAt = System.currentTimeMillis();
            }
        }
    }

    public static void abortProbe(String key) {
        if (key == null || key.isEmpty()) return;
        Stat s;
        synchronized (STATS) {
            s = STATS.get(key);
            if (s == null) return;
            if (s.probingAt > 0) {
                s.probingAt = 0;
                Log.i("半开试探作废（未取得结论，不影响熔断状态）：" + key);
            }
        }
    }

    /* ---------------------------------------------------------------- 打分 */

    /** 候选：一个具体的账号 + 模型。 */
    public static final class Pick {
        public final Config.Provider provider;
        public final String model;      // 上游真实模型名
        public double score;

        Pick(Config.Provider provider, String model) {
            this.provider = provider;
            this.model = model;
        }

        public String key() {
            return provider.name + "/" + model;
        }
    }

    /*
     * 先验分：一上来没有任何调用数据，光靠"成功率 0.5"会全部并列，
     * 结果永远挑排在最前面那个（可能是个 preview 模型）。
     * 这里按模型名推一点底分，**真实数据攒起来后会被盖过**，不影响自适应。
     *
     * ------------------------------------------------------------------
     * 为什么不再用一张写死的型号表
     *
     * 原来这里是一串 `new String[]{"glm-5", "deepseek-v4", "claude", ...}`，
     * 命中 +12、`preview/-exp` 命中 -8。真机配置实测（379 个模型）：
     *   · 命中名单 149 个（39%）
     *   · **没命中 230 个（60%）** —— 拿不到任何先验，跟同批次的新模型无从区分
     *   · 名单还漏了 glm-4.6 / deepseek-v3-1 / hunyuan-3b 这些**正在用的**型号，
     *     却收了 glm-5 —— 说明它是在某个时点手抄的，新模型上线没人回来补。
     *     这类过时名单不会报错，只会让新模型默默永远排最后。
     *
     * 现在改成**从名字里解析有序量**，不依赖任何型号清单：
     *   1. 版本号：v3 / v3.1 / v4 → 越高越新（v4.1 > v4 > v3.2 > v3）
     *   2. 尺寸档：pro/max/ultra > 标准 > flash/lite/mini/turbo
     *   3. 实验标记：preview / exp / beta / alpha / rc / nightly 打折
     *   4. 纯别名（auto / default / fast-model）不加不减
     * 这四条都是**名字里本来就有**的信息，新模型一上线就自动适用，
     * 不需要谁回来改代码。
     * ------------------------------------------------------------------
     */

    /** 先验分上限。比一次成功率的满额(30)小，确保真实数据能压过它。 */
    private static final double PRIOR_MAX = 12.0;

    /** 从名字里抠出最高的版本号（"deepseek-v4.1-flash" → 4.1）。 */
    private static double versionOf(String m) {
        java.util.regex.Matcher mt = java.util.regex.Pattern
                .compile("(?:^|[^a-z0-9])v(\\d+(?:\\.\\d+)?)(?:[^0-9]|$)")
                .matcher(m);
        double best = 0;
        while (mt.find()) {
            try {
                double v = Double.parseDouble(mt.group(1));
                if (v > best) best = v;
            } catch (NumberFormatException ignored) {
            }
        }
        return best;
    }

    /** 尺寸档：-1 = 轻量，0 = 未知/标准，+1 = 重型。 */
    private static int sizeTierOf(String m) {
        for (String h : new String[]{"ultra", "opus", "pro", "max", "large"}) {
            if (m.contains(h)) return 1;
        }
        for (String h : new String[]{"flash", "lite", "mini", "small", "nano", "turbo"}) {
            if (m.contains(h)) return -1;
        }
        return 0;
    }

    /** 实验/不稳定标记。 */
    private static boolean isExperimental(String m) {
        for (String h : new String[]{"preview", "-exp", "_exp", "beta", "alpha",
                "nightly", "-rc", "_rc", "dev", "experimental"}) {
            if (m.contains(h)) return true;
        }
        return false;
    }

    /**
     * 纯路由别名 / 非对话模型：名字本身不带"对话能力"信息，不给先验。
     *
     * 除了 auto、default 这类路由别名，真机配置里还混着一批**根本不做对话**的
     * 内部模型：completion（补全）、subagent / explore（Trae 的子代理）、
     * file_search、browser_use、computer_use、summary。它们出现在列表里是为了
     * 让客户端能点名调用，但如果按名字推先验，`explore_sub_agent_v2` 里的 `v2`
     * 会被当成"低版本"一路扣到 -8，比 preview 模型还低 —— 那不是我们想表达的意思。
     *
     * 注意：调用方已经把 `_` 统一换成 `-`（见 prior 里那行 replace），
     * 所以这里一律用连字符形式匹配。
     */
    private static boolean isAlias(String m) {
        for (String h : new String[]{"auto", "default", "fast-model", "balanced-model",
                "deep-model", "completion", "subagent", "sub-agent", "explore",
                "file-search", "browser-use", "computer-use", "embed", "rerank",
                "whisper", "tts"}) {
            if (m.contains(h)) return true;
        }
        /* `summary` 单列：它是个独立词，避免误伤名字里含 summary 的正式模型 */
        return m.startsWith("summary");
    }

    private static double prior(String model) {
        if (model == null) return 0;
        String m = model.toLowerCase(Locale.US).replace('_', '-');
        if (isAlias(m)) return 0;

        /*
         * 版本项：0..1 的饱和曲线。3.0 → 约 0.5，4.0 → 约 0.8，5.0 → 约 0.95。
         * 用饱和而不是线性，是因为"v9 比 v8 好"这件事在真实能力上会递减；
         * 线性会让版本号大的模型把分差拉得过大。
         */
        double ver = versionOf(m);
        double verScore = ver <= 0 ? 0.5 : (1.0 - 1.0 / (1.0 + (ver - 2.5) * 1.1));

        double v = 0;
        v += verScore * PRIOR_MAX * 0.55;          /* 约 0 .. 6.6 */
        v += sizeTierOf(m) * PRIOR_MAX * 0.22;     /* 约 -2.6 / 0 / +2.6 */
        if (isExperimental(m)) v -= PRIOR_MAX * 0.45;  /* 约 -5.4 */

        /* 夹到 [-PRIOR_MAX, PRIOR_MAX]，别让负面项把分数拉到离谱的地方 */
        if (v > PRIOR_MAX) v = PRIOR_MAX;
        if (v < -PRIOR_MAX) v = -PRIOR_MAX;
        return v;
    }

    /** 供直连路径排序用（与虚拟模型同一套打分，保持行为一致）。 */
    public static double scorePublic(Pick c, boolean needsTools) {
        return score(c, needsTools);
    }

    /** 供直连路径构造候选用。 */
    public static Pick newPick(Config.Provider p, String model) {
        return new Pick(p, model);
    }

    /**
     * 贝叶斯平滑常数：把"进程内计数"和"历史种子"合成一个成功率时用的伪样本数。
     *
     * 为什么需要它：进程内可能只有 1 次成功（rate=1.0），历史 200 笔是 0.6。
     * 直接取进程内值会让刚重启的网关被单次运气带着走。用
     *     r̂ = (ok + K·hist) / (n + K)
     * 让历史在样本少时说话、在样本多时退位，K 就是"历史相当于多少次观测"。
     */
    private static final double HIST_K = 4.0;

    /**
     * 打分。
     *
     * 相比原来"只有运行时成功率"的版本，这里多吃了三份证据，都来自 usage.tsv 的历史种子
     * （{@link Stats}）：
     *   · 历史成功率  —— 重启后不用从零学；
     *   · 历史耗时    —— 用于"快"的排序（原来 latencyEwma 只在 race 里用）；
     *   · 历史单次成本 —— 价格表 × 实际 token，贵的通道在同分时往后排。
     *
     * 权重的取舍：延迟和成本都**封顶**，理由是它们是"同等质量下选谁"的次要因素，
     * 不能压过成功率——一个贵 2 倍但从不断线的账号，比一个便宜但时不时 429 的更该被选。
     */
    private static double score(Pick c, boolean needsTools) {
        Stat s = stat(c.key());
        long now = System.currentTimeMillis();
        int ok, fail;
        long cooldownUntil, lastFailAt;
        synchronized (STATS) {
            ok = s.ok;
            fail = s.fail;
            cooldownUntil = s.cooldownUntil;
            lastFailAt = s.lastFailAt;
        }
        int n = ok + fail;
        Stats.Seed seed = Stats.get(c.provider.name, c.model);
        double hist = seed == null ? 0.5 : seed.ok;
        /* n 小的时候历史权重大，n 大了历史自动退位 */
        double rate = (ok + HIST_K * hist) / (n + HIST_K);
        if (n == 0 && seed == null) rate = 0.5;
        double err = 1.0 - rate;
        double v = 100
                + rate * 30
                - err * 50
                + prior(c.model);

        /*
         * 延迟：以 4 秒为参考，越快越高分，上限 ±6 分（别让它压过成功率）。
         *
         * 权重按"这份耗时估计有多可信"打折：只有 2 笔样本算出来的"1726ms"不比
         * 137 笔算出来的"5118ms"更值得信 —— 真机数据里正是 2 笔样本的组合靠这个
         * 排到了第一。可信度 = w/(w+HIST_K)，5 笔时约 0.55，200 笔时约 0.98。
         * 运行时统计（latencyEwma）没有历史权重可言，但它至少来自本进程真实调用，
         * 按"1 份可信"处理。
         */
        long ms = seed != null && seed.ms > 0 ? Math.round(seed.ms) : s.latencyEwma;
        if (ms > 0) {
            double trust = seed != null ? seed.w / (seed.w + HIST_K) : 0.5;
            double fast = 4_000.0 / (4_000.0 + ms);   // ms=200 → 0.95，ms=4000 → 0.5
            v += (fast - 0.5) * 12 * trust;           // 最多 -6 .. +5.4
        }

        /* 成本：只在价格表能查到时有意义；-6 .. 0 分，最贵的不加不减，便宜的加分 */
        if (seed != null && seed.cost > 0) {
            double costScore = 1.0 / (1.0 + seed.cost * 400.0);  // $0.0005 → 0.83，$0.01 → 0.20
            v += costScore * 6 - 3;                              // -1.02 .. +1.98
        }

        if (c.provider.credits <= 0) v -= 200;                       /* 额度用尽 */
        if (cooldownUntil > now) v -= 100;                           /* 冷却中 */
        if (lastFailAt > 0 && now - lastFailAt < 120_000) v -= 50;   /* 刚报过错 */
        /* 工具能力：实测会调的加分，打嘴炮的狠狠扣（后面还有硬门禁兜底） */
        if (needsTools) {
            int st = ToolAbility.state(c.provider, c.model);
            if (st == ToolAbility.NATIVE) v += 40;
            else if (st == ToolAbility.BAD) v -= 60;
        }
        return v;
    }

    /* ---------------------------------------------------- 重试预算（数学定次数） */

    /**
     * 整体可接受的失败概率。
     *
     * 0.1% 不是随便定的：手机端一次对话用户会感知到"卡住了"的阈值大约在这里，
     * 再低就是在为一个用户根本察觉不到的差异付延迟代价。
     */
    private static final double TARGET_FAIL = 0.001;

    /** 重试次数上限。再多，收益已经低于一次往返的成本。 */
    private static final int MAX_TRIES = 4;

    /**
     * 给一个候选算它值得试几次。
     *
     * 依据是几何衰减：若单次失败率 p，连试 k 次全失败 = p^k。
     * 要让 p^k ≤ TARGET_FAIL，解出 k = ⌈ln(target)/ln(p)⌉。
     *
     * 为什么这个式子比固定 3 次好（真机 3408 条样本实测，p=0.0208）：
     *
     *     试1次：可靠度 0.9792，延迟 1.53s
     *     试2次：可靠度 0.9996，延迟 1.56s   ← 边际收益 0.02%，成本 0.03s
     *     试3次：可靠度 0.99999，延迟 1.56s  ← 边际收益 0.0004%，最坏 +3s
     *
     * 第 3 次重试把最坏情况从 6s 拉到 9s，换来的可靠度提升是 0.0004% ——
     * 这笔交易在任何延迟敏感的场景里都是亏的。
     *
     * 但固定 2 次也不对：一个历史失败率 30% 的候选（刚被限流恢复），
     * 试 2 次还有 9% 失败，得试 4 次。所以次数必须**按候选自己的 p 算**。
     */
    static int triesFor(Pick c) {
        if (c == null) return 1;
        double p = failRate(c);
        if (p <= 0) return 1;
        if (p <= TARGET_FAIL) return 1;
        /* ln(p) 是负的，两者相除得正数 */
        double k = Math.log(TARGET_FAIL) / Math.log(p);
        int n = (int) Math.ceil(k);
        if (n < 1) n = 1;
        return Math.min(n, MAX_TRIES);
    }

    /**
     * 在一串候选里，试到第几个就够了。
     *
     * 单独看每个候选会算错：三个各自失败率 30% 的候选，按 triesFor() 每个都要试 4 次，
     * 但**跨候选**累积之后，第 2、3 个候选再试那么多次完全是浪费 ——
     * 因为候选之间是"或"的关系：全部失败的概率是 ∏p_i，不是 p^n。
     *
     * 所以这里做累积判定：从第 0 个开始乘，乘到累计失败概率 ≤ TARGET_FAIL 就停。
     * 返回的是"值得试几个候选"，最小 2（留一次兜底，避免单点）。
     *
     * @param cands 已按分数排序的候选
     */
    public static int budget(List<Pick> cands) {
        if (cands == null || cands.isEmpty()) return 0;
        if (cands.size() == 1) return 1;
        double cum = 1.0;
        for (int i = 0; i < cands.size() && i < MAX_TRIES; i++) {
            cum *= failRate(cands.get(i));
            if (cum <= TARGET_FAIL) {
                /* i 是下标，试了 i+1 个；兜底至少 2 个 */
                return Math.max(2, i + 1);
            }
        }
        return Math.min(cands.size(), MAX_TRIES);
    }

    /**
     * 直连路径的重载：候选是 {@link Config.Provider}（名字已定，只是挑账号）。
     *
     * 和 List&lt;Pick&gt; 版本同一个判据，只是失败率按 "账号/模型" 取。
     * 直连时模型名是用户点名的，不会变，所以拿 provider.name + model 找同一个 Stat。
     */
    public static int budget(List<?> cands, String model) {
        if (cands == null || cands.isEmpty()) return 0;
        if (cands.size() == 1) return 1;
        double cum = 1.0;
        for (int i = 0; i < cands.size() && i < MAX_TRIES; i++) {
            Object o = cands.get(i);
            if (!(o instanceof Config.Provider)) return Math.min(cands.size(), MAX_TRIES);
            cum *= failRateKey(((Config.Provider) o).name, model);
            if (cum <= TARGET_FAIL) return Math.max(2, i + 1);
        }
        return Math.min(cands.size(), MAX_TRIES);
    }

    /** 按 "账号/模型" 估单次失败率（直连路径用）。 */
    private static double failRateKey(String provider, String model) {
        if (provider == null || model == null) return 0.5;
        Stat s = stat(provider + "/" + model);
        int ok, fail;
        synchronized (STATS) {
            ok = s.ok;
            fail = s.fail;
        }
        int n = ok + fail;
        Stats.Seed seed = Stats.get(provider, model);
        double hist = seed == null ? 0.5 : seed.ok;
        double rate = (ok + HIST_K * hist) / (n + HIST_K);
        if (n == 0 && seed == null) return 0.5;
        return Math.max(0, Math.min(1, 1.0 - rate));
    }

    /**
     * 这个候选的单次失败率估计。
     *
     * 和 score() 用的是同一份证据（进程内计数 + 历史种子），取贝叶斯平滑后的
     * 成功率再取反。没有证据时保守地返回 0.5 —— 宁可多试一次，
     * 也不要让一个未知候选把请求打死。
     */
    private static double failRate(Pick c) {
        Stat s = stat(c.key());
        int ok, fail;
        synchronized (STATS) {
            ok = s.ok;
            fail = s.fail;
        }
        int n = ok + fail;
        Stats.Seed seed = Stats.get(c.provider.name, c.model);
        double hist = seed == null ? 0.5 : seed.ok;
        double rate = (ok + HIST_K * hist) / (n + HIST_K);
        if (n == 0 && seed == null) return 0.5;
        return Math.max(0, Math.min(1, 1.0 - rate));
    }

    /**
     * 按虚拟模型名给出候选顺序（已按分数从高到低）。
     * 不是虚拟模型就返回空列表，调用方自己走原来的 pick()。
     */
    public static List<Pick> candidates(String virtual, boolean needsTools) {
        return candidates(virtual, needsTools, null);
    }

    /**
     * @param req 用来按**请求内容**分类；为 null 时只看虚拟模型名
     */
    public static List<Pick> candidates(String virtual, boolean needsTools, Proto.Req req) {
        if (!isVirtual(virtual)) return new ArrayList<>();
        String v = virtual.toLowerCase(Locale.US);
        // 能力过滤：这两条都是"发出去也注定失败"的请求，先在本地筛掉
        boolean needsVision = Classifier.hasImage(req);
        int estTokens = Classifier.estimateTokens(req);
        int skippedVision = 0;
        int skippedVisionModel = 0;      // 其中因为"这个模型本身不吃图"被跳过的
        int skippedContext = 0;

        List<Pick> all = new ArrayList<>();
        for (Config.Provider p : Gateway.config().providers) {
            if (!p.dispatchable()) continue;
            /* 账号级总开关：用户在账号详情里明确关掉「支持图片输入」时，整个账号都不参与
               带图请求。这是用户的明确意愿（有些上游是按账号整体禁图的），优先于能力表。 */
            if (needsVision && !p.vision) {
                skippedVision++;
                continue;
            }
            if (p.contextLimit > 0 && estTokens > p.contextLimit) {
                skippedContext++;
                continue;
            }
            for (String m : p.models) {
                if ("*".equals(m) || m.isEmpty()) continue;
                if (isVirtual(m) || junk(m)) continue;
                if (p.badModels.contains(m)) continue;      // 探活判过死刑的
                // 熔断三态：OPEN 的候选本轮不进池；冷却刚过的 HALF_OPEN 只放一个请求去试探
                // 注意：这里只做只读检查，真正发起调用时再由 markProbingIfHalfOpen 打标，
                // 避免枚举列表时把未选中的半开候选全部自锁 90 秒。
                Stat st = stat(p.name + "/" + m);
                long nowMs = System.currentTimeMillis();
                synchronized (STATS) {
                    if (st.cooldownUntil > nowMs) continue;
                    if (st.throttleUntil > nowMs) continue;     // 上游刚说过"限流"，本轮别去撞
                    if (st.openCount > 0 && st.probingAt > 0 && nowMs - st.probingAt < PROBE_GRACE_MS) {
                        continue;
                    }
                }
                all.add(new Pick(p, m));
            }
        }

        /* 模型级图片过滤：账号开着「支持图片」不代表这个模型就吃图（见 modelSeesImages）。 */
        List<Pick> out = all;
        if (needsVision) {
            List<Pick> okVision = new ArrayList<>();
            for (Pick c : all) {
                if (modelSeesImages(c.provider, c.model)) okVision.add(c);
            }
            skippedVisionModel = all.size() - okVision.size();
            skippedVision += skippedVisionModel;
            if (okVision.isEmpty() && !all.isEmpty()) {
                /* 一个候选都不放会变成 404「没有可用候选」，而那比"发出去让上游判"更糟：
                   能力表是本地推断、可能不准，不能因为本地表缺一条就把整条路堵死。
                   这里退回全部候选，并留下一条明确告警。 */
                Log.w("带图请求：能力表判定 " + all.size() + " 个候选都不支持图片，"
                        + "为避免直接失败仍放出全部候选；若上游确实拒绝，请改用能看图的账号");
            } else {
                out = okVision;
            }
        }
        for (Pick c : out) c.score = score(c, needsTools);

        if (skippedVision > 0) {
            Log.i("能力过滤：带图请求跳过 " + skippedVision + " 个不支持图片的通道（账号级 "
                    + (skippedVision - skippedVisionModel) + " · 模型级 " + skippedVisionModel
                    + "，候选 " + out.size() + "）");
        }
        if (skippedContext > 0) {
            Log.i("上下文过滤：约 " + estTokens + " token，跳过 " + skippedContext
                    + " 个上下文装不下的通道（候选 " + out.size() + "）");
        }
        if (out.isEmpty() && needsVision && skippedVision > 0) {
            Log.w("带图请求没有任何支持图片的通道："
                    + "去账号详情把该账号的「支持图片」打开，或换一个能看图的账号");
        }

        // 用途偏置：虚拟模型名说了就按它，没说就看请求内容
        final String[] hints;
        if (AUTO_CODE.equals(v)) hints = CODE_HINT;
        else if (AUTO_LONG.equals(v)) hints = LONG_HINT;
        else if (req != null) {
            Classifier.Kind k = Classifier.of(req);
            hints = k.code() ? CODE_HINT : (k.longInput() ? LONG_HINT : null);
        } else {
            hints = null;
        }
        if (hints != null) {
            for (Pick c : out) if (hint(c.model, hints)) c.score += 25;
        }
        // 硬门禁：带 tools 的请求，只要有"验证过会调"的，就不让"打嘴炮"的进池子
        if (needsTools) {
            boolean anyNative = false;
            for (Pick c : out) if (ToolAbility.state(c.provider, c.model) == ToolAbility.NATIVE) anyNative = true;
            if (anyNative) {
                List<Pick> keep = new ArrayList<>();
                for (Pick c : out) if (ToolAbility.state(c.provider, c.model) != ToolAbility.BAD) keep.add(c);
                out = keep;
            }
            //还没记录的，按分数从高到低补几次后台探活（不影响这次请求的延迟）
            int fired = 0;
            for (Pick c : out) {
                if (fired >= 2) break;
                if (ToolAbility.state(c.provider, c.model) == ToolAbility.UNKNOWN) {
                    ToolAbility.probeAsync(c.provider, c.model);
                    fired++;
                }
            }
        }

        Collections.sort(out, (a, b) -> Double.compare(b.score, a.score));

        // 同分轮转：分数一样的候选不该永远压在同一个账号上（都走 WB1 容易被限流）。
        // 只在"和最高分差 5 分以内"的那一档里轮，质量优先、负载摊开。
        if (out.size() > 1) {
            double top = out.get(0).score;
            int band = 0;
            while (band < out.size() && top - out.get(band).score <= 5.0) band++;
            if (band > 1) {
                int turn = (int) (ROTATE.getAndIncrement() % band);
                if (turn > 0) {
                    List<Pick> head = new ArrayList<>(out.subList(0, band));
                    Collections.rotate(head, -turn);
                    for (int i = 0; i < band; i++) out.set(i, head.get(i));
                }
            }
        }
        // 连着重试的请求不该永远命中同一个模型：把上一次那个挪到后面去。
        // 只有一个候选时不动它（别无选择），避免把自己挪成空池。
        if (out.size() > 1) {
            String last;
            int streak;
            synchronized (LAST_KEY) {
                last = LAST_KEY.get(v);
                streak = LAST_STREAK.getOrDefault(v, 0);
            }
            if (last != null && streak >= SAME_KEY_LIMIT) {
                Router.Pick hit = null;
                for (Router.Pick c : out) {
                    if (last.equals(c.key())) {
                        hit = c;
                        break;
                    }
                }
                if (hit != null) {
                    out.remove(hit);
                    out.add(Math.min(1, out.size()), hit);   // 挪到第二位：还是好候选，但不再霸着队首
                }
            }
        }
        return out;
    }

    private static final java.util.concurrent.atomic.AtomicInteger ROTATE =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * 上一次这个虚拟模型走了哪个"账号/模型"。
     *
     * 起因：某客户端卡在重试循环里每 6 秒重发一次同样的请求，每次都命中同一个
     * Provider 的同一个模型，于是答案一模一样地刷屏。这里做一件很小的事——
     * 同一个模型**连着**被选中时，下一次把它往后挪一位，换一个账号或换一个模型试试。
     *
     * 只在"最高分那一档"里挪，所以不会为了换而换、把质量降下去。
     */
    private static final Map<String, String> LAST_KEY = new LinkedHashMap<>();

    /** 连续同模型的次数上限；到点就把队首挪走。 */
    private static final Map<String, Integer> LAST_STREAK = new LinkedHashMap<>();

    /** 连续走同一个候选，超过这个次数就换一个（虚拟模型专用）。 */
    private static final int SAME_KEY_LIMIT = 2;

    /** 记下这次选中的是谁，用于下一次同分轮换。 */
    public static void notePicked(String virtual, String key) {
        if (virtual == null || key == null) return;
        synchronized (LAST_KEY) {
            String prev = LAST_KEY.get(virtual);
            int streak = prev != null && prev.equals(key)
                    ? LAST_STREAK.getOrDefault(virtual, 0) + 1 : 1;
            LAST_KEY.put(virtual, key);
            LAST_STREAK.put(virtual, streak);
            if (streak >= SAME_KEY_LIMIT && LOG_ONCE.add(virtual)) {
                Log.i("虚拟模型 " + virtual + " 连续 " + streak + " 次都走 " + key
                        + "，接下来会换一个候选（避免同一个模型反复命中）");
            }
        }
    }

    /** 诊断用：某虚拟模型上一次走的候选。 */
    public static String lastPicked(String virtual) {
        synchronized (LAST_KEY) {
            return LAST_KEY.get(virtual);
        }
    }

    private static final java.util.Set<String> LOG_ONCE =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

    /**
     * 竞速候选：最多 n 个，且**每个来自不同账号**。
     *
     * 同账号同时打多路是封号最常见的原因，所以宁可比 n 少，也不重复账号。
     */
    /**
     * 这个"账号/模型"现在是否还该被调度（熔断/限流回避检查）。
     *
     * 为什么需要暴露：直连路径（客户端直接写具体模型名）以前**完全绕过熔断**——
     * `Server.pick()` 只看 enabled + matches，从不查这里，导致 OPEN 的 key 照样被打出去、
     * 冷却形同虚设。这个方法是给那条路径补上判据用的。
     *
     * @return true 表示可以发；false 表示正处在冷却/限流回避里
     */
    public static boolean available(String key) {
        if (key == null || key.isEmpty()) return true;
        long now = System.currentTimeMillis();
        synchronized (STATS) {
            Stat st = STATS.get(key);
            if (st == null) return true;              // 没有记录 = 从没出过问题
            if (st.cooldownUntil > now) return false;  // 熔断中
            if (st.throttleUntil > now) return false;  // 上游刚说过"限流"
            if (st.openCount > 0 && st.probingAt > 0 && now - st.probingAt < PROBE_GRACE_MS) {
                return false;                          // 已有半开试探在飞
            }
            return true;
        }
    }

    /**
     * 竞速候选：最多 n 个，每个来自不同账号。
     *
     * 这里才是 auto-race 的本义 —— **烧三份额度换最低延迟**。
     * 所以选路逻辑和普通虚拟模型不同：
     *
     *   ① 候选来自 auto / auto-code / auto-long **三个池的并集**（不是只看 auto）；
     *   ② 排序主键是**首字延迟**（latencyEwma），不是综合分 ——
     *      按综合分选等于把"竞速"的卖点抹掉（那和普通 auto 没区别）；
     *   ③ 没有延迟数据的候选（从没成功过）用综合分兜底，排在有数据的后面；
     *   ④ 仍然每个候选来自不同账号（同账号打多路是封号最常见的原因）。
     *
     * 只影响这一条选路，普通 score() 不动。
     */
    public static List<Pick> raceCandidates(int n) {
        return raceCandidates(n, null);
    }

    public static List<Pick> raceCandidates(int n, Proto.Req req) {
        boolean needsTools = req != null && !req.tools.isEmpty();
        // 三池并集 + 按 provider/model 去重（传入 req 以执行图片视觉与上下文长度过滤）
        java.util.LinkedHashMap<String, Pick> byKey = new java.util.LinkedHashMap<>();
        for (String pool : new String[]{AUTO, AUTO_CODE, AUTO_LONG}) {
            for (Pick c : candidates(pool, needsTools, req)) {
                String k = c.provider.name + "/" + c.model;
                if (!byKey.containsKey(k)) byKey.put(k, c);
            }
        }

        List<Pick> all = new ArrayList<>(byKey.values());
        // 排序：有延迟数据的按延迟升序（快的在前）；没数据的排后面，彼此按综合分降序
        all.sort((x, y) -> {
            long lx = latencyOf(x);
            long ly = latencyOf(y);
            boolean hx = lx > 0, hy = ly > 0;
            if (hx && hy) return Long.compare(lx, ly);       // 都测过 → 快的在前
            if (hx != hy) return hx ? -1 : 1;                // 测过的优先
            return Double.compare(y.score, x.score);         // 都没测过 → 综合分高的在前
        });

        List<Pick> out = new ArrayList<>();
        List<String> used = new ArrayList<>();
        for (Pick c : all) {
            if (used.contains(c.provider.name)) continue;
            used.add(c.provider.name);
            out.add(c);
            if (out.size() >= n) break;
        }
        return out;
    }

    /** 读一个候选的首字延迟（毫秒）；没数据返回 0。 */
    /**
     * 这个候选的延迟估计，给竞速排序用。
     *
     * 原来只看进程内的 {@code Stat.latencyEwma} —— 重启后是 0，
     * 于是所有候选都被当成"没有延迟数据"，竞速退化成按综合分排，
     * 而竞速的全部意义就是"谁先出字用谁"。
     *
     * 这是 4.30.0 引入 Stats 时漏掉的一条路径：score() 吃上了历史种子，
     * latencyOf() 没吃上。补法是同一套优先级 —— 历史种子优先（样本多、跨重启），
     * 进程内的 EWMA 兜底（刚发生的事更贴现在）。
     *
     * 但历史值要按样本量打折用：一个 2 笔样本算出的 1.7s 不该压过
     * 一个 137 笔算出的 5.1s。所以返回的是"打折后的估计"：
     * 样本越少，越往中性值（4 秒）靠。
     */
    private static long latencyOf(Pick c) {
        Stat st;
        synchronized (STATS) {
            st = STATS.get(c.key());
        }
        long local = st == null ? 0L : st.latencyEwma;

        Stats.Seed seed = Stats.get(c.provider.name, c.model);
        if (seed == null || seed.ms <= 0) return local;

        /* 可信度：w/(w+HIST_K)，和 score() 里的延迟项同一个折扣 */
        double trust = seed.w / (seed.w + HIST_K);
        double blended = seed.ms * trust + NEUTRAL_MS * (1 - trust);
        return Math.round(blended);
    }

    /** 没有任何可靠信息时的中性延迟（秒）。取 4s 是因为真机实测 p50 ≈ 4.1s。 */
    private static final double NEUTRAL_MS = 4000;

    /** 诊断用一行。 */
    public static String describe() {
        StringBuilder sb = new StringBuilder("虚拟模型：");
        for (String v : VIRTUALS) {
            sb.append(v).append('(').append(candidates(v, false).size()).append(") ");
        }
        return sb.toString();
    }
}
