package com.cpa.gateway.core;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;

/** Persisted gateway configuration: one small JSON file, no third-party dependencies. */
public final class Config {

    /**
     * 生成一个账号 id。
     *
     * 原来各通道自己写 `"xx" + Math.abs(System.currentTimeMillis() % 100000)` ——
     * 毫秒对 100000 取模，**周期恰好 100 秒**，也就是每 100 秒同一个前缀就可能撞号。
     * 撞号不只是"看起来重了"：WbTaskChores 的账号级重入 guard 用 id 做 key，
     * 两个不同账号 id 相同会被误判成 already running，签到/任务奖励静默丢失。
     * 另外老写法里 Claude 和 Cline 都用 "cl" 前缀，跨类型也会撞。
     *
     * 现在改用「前缀 + 单调递增序列 + 时间戳后 4 位」：
     *   - 同一进程内序列号保证不重复；
     *   - 进程重启后时间戳变化，也几乎不会和旧 id 撞（且旧 id 已存在配置里，新账号才需要它）。
     */
    private static final java.util.concurrent.atomic.AtomicLong ID_SEQ =
            new java.util.concurrent.atomic.AtomicLong(0);

    public static String newProviderId(String prefix) {
        long n = ID_SEQ.incrementAndGet();
        long tail = System.currentTimeMillis() % 10000L;
        return prefix + Long.toString(tail, 36) + Long.toString(n, 36);
    }

    public static final String TYPE_OPENAI = "openai";
    /** Kilo 公共免费池（零凭证，OpenAI 兼容）。 */
    public static final String TYPE_KILO = "kilo";
    public static final String TYPE_OVH = "ovh";
    public static final String TYPE_ANTHROPIC = "anthropic";
    public static final String TYPE_GEMINI = "gemini";
    public static final String TYPE_WORKBUDDY = "workbuddy";
    public static final String TYPE_CODEX = "codex";
    public static final String TYPE_CLAUDE = "claude";
    public static final String TYPE_LOOMY = "loomy";
    public static final String TYPE_TRAE = "trae";
    public static final String TYPE_ANTIGRAVITY = "antigravity";
    public static final String TYPE_KIMI = "kimi";
    public static final String TYPE_DEEPSEEK = "deepseek";
    public static final String TYPE_DOUBAO = "doubao";
    public static final String TYPE_XAI = "xai";
    /** Cline（WorkOS 设备码登录）。 */
    public static final String TYPE_CLINE = "cline";

    public int port = 8321;
    public boolean lanAccess = false;
    public boolean autostart = false;
    public boolean keepAlive = true;
    /**
     * @deprecated 4.30.22 起由 {@link GatewayState} 的单一状态源取代，
     *             不再参与任何判断。字段保留只为读旧配置/旧备份时不丢数据，
     *             新代码不要再写它。历史上它承担过"用户主动停止"的语义，
     *             但会和"网关仍在运行"组合出矛盾状态（4.30.19 真发生过）。
     */
    @Deprecated
    public boolean userStopped = false;

    /** 浅色模式：整套配色换一套值（见 Ui.applyTheme）。 */
    public boolean lightMode = false;
    /** Accent theme key, see Ui.applyTheme. */
    public String accentTheme = "spring";
    /** Built-in relay station reachability, so the page can grey out the dead ones. */
    public final List<String> aliveStations = new ArrayList<>();
    /** Relay stations found automatically and proven reachable: ["name|url", …]. */
    public final List<String> discoveredStations = new ArrayList<>();
    /** Stations that failed for good: never offered again. */
    public final List<String> removedStations = new ArrayList<>();
    /** "url|count": one network hiccup must not retire a working station. */
    public final List<String> stationFailures = new ArrayList<>();
    public final List<String> deadStations = new ArrayList<>();
    public long stationsCheckedAt = 0L;
    /** Unix seconds of the last daily relay health check. */
    public long lastRelayCheck = 0L;
    /** Periodically re-probe every account's models in the background. */
    public boolean autoDetect = true;
    /** Unix seconds of the last automatic model sweep. */
    public long lastAutoDetect = 0L;
    /** Minimum seconds between two automatic sweeps. */
    public long autoDetectInterval = 21600L;

    /**
     * 本机回环访问 Key。**默认空串＝不校验**。
     *
     * 以前这里是 `"sk-cpa-local-" + 时间戳低 24 位`，本意是"开箱有个 Key 更安全"，
     * 实际造成两个都不想要的后果：
     *   1. 构造时就生成，第一次保存就落进 gateway.json，于是这个"随机默认值"被当成
     *      用户**自己设过**的 Key 一直被读回来——用户在设置页清空再保存也没用，
     *      因为下次启动 fromJson 又从文件里读到它（真机实测：清完仍是 sk-cpa-local-xxxxxx）。
     *   2. 用户本来就不想用 Key（本机回环、只给自己用），却被 401 挡在外面，
     *      日志表现成"客户端 Key 与服务端不一致"，看着像客户端配错了。
     *
     * 现在的语义（与 {@link #acceptsKey} 和 Server.route 对应）：
     *   · 空串 → 本机模式下不校验，客户端不填 Key 也能直连；
     *   · 局域网模式（lanAccess）仍**强制**要求 Key，没有就 401 拒绝整个请求——
     *     那条判断在 Server 里，不依赖这个默认值。
     */
    public String apiKey = "";
    /**
     * 本次加载时发现磁盘上的 apiKey 解不开（Keystore 重建 / 换机 / 换签名）。
     * 不落盘：只用于告诉 {@link Gateway#reload()} 不要拿这个值覆盖内存里的旧 Key。
     */
    public boolean apiKeyUnreadable = false;
    /** Older client keys stay valid, so changing the key never locks a working client out. */
    public final List<String> apiKeyAliases = new ArrayList<>();
    /**
     * 账号列表：**并发安全**的列表。
     *
     * 以前是裸 ArrayList：UI 线程 reload() 改它，连接线程同时在遍历它，
     * 撞上就是 ConcurrentModificationException（那一次请求直接失败）。
     * 现在用写时复制：遍历永远拿到稳定快照，改动是全量替换。
     */
    public final List<Provider> providers = new java.util.concurrent.CopyOnWriteArrayList<>();

    public static final class Provider {
        public String id = "";
        /**
         * 账号的**稳定**短标签（WB1 / Trae2 / 中科1…），是客户端模型名前缀的唯一来源。
         *
         * 为什么必须存下来，不能每次算：短标签原来是"在当前**启用**的同类型账号里排第几"，
         * 于是停用一个账号会把后面的全部重新编号 ——
         *   WB1停用 → WorkBuddy2 从 WB2 变成 WB1、WB4 直接消失。
         * 客户端里存的是 "WB2/kimi-k3-1" 这种字符串，用户只是想省点额度先停一个号，
         * 结果配置**静默指向了另一个账号**，或者干脆失效。没有任何提示，
         * 排查起来会以为是"模型坏了"。
         *
         * 现在：首次加载时按当时的顺序把标签定下来并落盘，此后只增不改。
         * 账号停用/启用/新增都不再让已有标签漂移。
         */
        public String label = "";
        public String name = "";
        public String type = TYPE_OPENAI;
        public String baseUrl = "";
        public String apiKey = "";
        /** Older keys stay valid so a client that still has the previous key keeps working. */
        public final List<String> apiKeyAliases = new ArrayList<>();
        public boolean enabled = true;
        /**
         * 只有目录、不能推理（"仅目录可用"）。
         *
         * 为什么需要：上游有些账号**目录刷得出来、推理一律被拒**——今天真机上的
         * WorkBuddy global 账号就是（`11128` 渠道校验，同 realm 另一个号能过，
         * 属于账号/会话被标记，网关改请求形状无效）。这种号留在正常池子里只有一个后果：
         * 每轮检测白跑两个 15 秒探测、客户端选到它就必然失败。
         *
         * 语义：catalogOnly 的账号**不参与任何派发与探测**（客户端模型列表里也不再出现），
         * 但账号本身、凭据、模型目录都留着，想手动看一眼或重新启用随时可以。
         */
        public boolean catalogOnly = false;

        /**
         * 凭据在**本次运行内解不开**（Keystore 重建 / 换机导入 / 密文损坏）。
         *
         * 为什么不落盘：这是"当前这台机器的 Keystore 打不开这个密文"，不是账号自身的
         * 属性。一旦 Keystore 恢复（或用户重新登录），同一个账号就正常了 ——
         * 写成持久标记反而会让它一直不可用。
         *
         * 语义：本次运行内退出派发与探活，否则它会在池子里显示"可用"，
         * 客户端选到它必然失败（凭据是空的），表现成随机的"用不了"。
         */
        public transient boolean credentialsUnreadable = false;

        /** 能不能真正派发推理请求。目录-only / 受限 / 凭据读不出来的账号都不进池子。 */
        public boolean dispatchable() {
            return enabled && !catalogOnly && !restricted() && !credentialsUnreadable;
        }

        /** 账号页要显示的原因；没有就返回空串。 */
        public String blockedReason() {
            if (credentialsUnreadable) return "凭据解不开：需要重新登录该账号（或恢复 Keystore）";
            if (restricted()) {
                String note = restrictedNote();
                return note == null || note.isEmpty() ? "上游限制：暂时不可用" : note;
            }
            return "";
        }

        /**
         * 上游明确说"账号存在异常行为，已被限制使用"（WorkBuddy 11140）。
         *
         * 这不是限流，等一会儿不会好；继续拿几十个模型去试探只会让上游看到
         * 更多"异常活动"。所以整个账号直接退出派发/探活，直到用户申诉/换号，
         * 或标记自然过期（{@link #markRestricted} 写入的时间）。
         */
        public boolean restricted() {
            try {
                return options.optLong("restricted_until", 0L) > System.currentTimeMillis();
            } catch (Throwable t) {
                return false;
            }
        }

        public String restrictedNote() {
            try {
                return Proto.optString(options, "restricted_note", "");
            } catch (Throwable t) {
                return "";
            }
        }

        /** 标记账号被上游限制；{@code ms} 之后自动允许再试一次。 */
        public void markRestricted(long ms, String note) {
            try {
                options.put("restricted_until", System.currentTimeMillis() + Math.max(60_000L, ms));
                options.put("restricted_note", note == null ? "" : note);
                throttleNote = note == null ? "" : note;
            } catch (Throwable ignored) {
            }
        }

        /**
         * 用户编辑/重新登录账号时清掉"不可用"标记，让下一次请求重新验证。
         *
         * 同时清 credentialsUnreadable：用户在这里重新填了 Key / 重新登录，就等于
         * 明确表示"这个账号现在应该是好的"；留着旧标记会让它一直不参与派发。
         */
        public void clearRestricted() {
            try {
                options.remove("restricted_until");
                options.remove("restricted_note");
                if (throttleNote != null && throttleNote.contains("限制")) throttleNote = "";
                credentialsUnreadable = false;
            } catch (Throwable ignored) {
            }
        }
        public final List<String> models = new java.util.concurrent.CopyOnWriteArrayList<>();
        /**
         * 用户手填的模型名。
         *
         * models 是"这个账号现在能提供哪些模型"的总表，但它的来源有两个：上游
         * /v1/models 拉回来的目录，和用户在编辑框里手打的。上游刷新时是整表覆盖，
         * 所以手打的那几个必须单独存一份，刷新后并回来 —— 否则"加了模型过一会儿
         * 就没了"，因为下一次自动巡检、下一次拉取、甚至只是点一次编辑保存，
         * 都会用上游目录把手写的内容盖掉。
         */
        /**
         * 账号级的布尔开关（键 → true/false 字符串）。
         *
         * 目前只有一个：
         *   - `intl_fallback`：WorkBuddy 国内区域账号撞上渠道校验（11128）后，
         *     要不要再按**国际版身份**（UA \/ Origin \/ Accept-Language \/ workbuddy.ai）
         *     重发一次。缺省 true；设成 "false" 就完全不试。
         *
         * 为什么不硬编码在代码里：这是"行为形状"上的主动变更，不同账号池表现不同，
         * 用户得能在不改代码的前提下关掉（重建配置时这个字段会跟着 options 走）。
         */
        public final JSONObject options = new JSONObject();

        public final List<String> manualModels = new java.util.concurrent.CopyOnWriteArrayList<>();
        /**
         * manualModels 是否已经确定过。
         *
         * 老配置没有手动模型这个概念，升级后第一次刷新时才看得出哪些是手填的
         * （判据：本地有、这次上游没返回）。确定之后就固定下来，
         * 免得每次刷新都把上游"已经删掉的模型"又当成手填的留下。
         */
        public boolean manualKnown = false;

        /** Token-type providers (WorkBuddy) keep their account blob here as JSON. */
        public String credentials = "";
        /** Last known credit balance, refreshed by the daily-task routine. */
        public double credits = -1;
        /**
         * 每分钟/每天的请求数与 token 数上限，0＝不限制。
         *
         * 这四个是**本地预检**：超了就不再往上游发，省一次注定 429 的往返，
         * 也让账号不会因为"明知超了还猛打"被上游加重处罚。
         */
        public int rpm = 0;
        public int rpd = 0;
        public int tpm = 0;
        public int tpd = 0;
        /** 同一账号同时在跑的上游请求数上限，0＝不限制。同账号并发是风控重灾区。 */
        public int maxConcurrent = 0;
        /** 是否支持图片输入。默认 true；已知不吃图的账号关掉，纯文本请求不受影响。 */
        public boolean vision = true;
        /** 上下文 token 上限，0＝不限制；估算超过就跳过这个通道，不白跑一次上游。 */
        public int contextLimit = 0;
        /** 当日已用：请求数、token 数，以及它属于哪一天（跨进程重启保留）。 */
        public int dayRequests = 0;
        public long dayTokens = 0L;
        public int quotaDay = 0;
        /** Models the upstream lists but refuses to serve (probe-verified). */
        public final List<String> badModels = new java.util.concurrent.CopyOnWriteArrayList<>();
        /**
         * 「什么时候被判坏的」：模型名 → 标记时间（epoch 秒）。
         *
         * 为什么要单独记：badModels 以前是个**只增不减**的名单——只有被
         * 探测到并成功返回的模型才会从里面移除，而每一轮检测每个账号只抽
         * 2 个模型（见 Detect.start），于是没被抽中的旧标记会一直躺在名单
         * 里，越滚越多，最后界面上的「坏」跟上游的真实情况完全对不上。
         *
         * 加了时间戳之后，旧标记可以按 {@link #BAD_TTL_SECONDS} 自行过期，
         * 不需要用户手动清、也不需要跑一轮全量重测。
         */
        public final java.util.Map<String, Long> badSince = new java.util.concurrent.ConcurrentHashMap<>();
        /** 「为什么被判坏的」：模型名 → 最后一次探测失败的原因（人话，来自 Detect.classify）。 */
        public final java.util.Map<String, String> badReason = new java.util.concurrent.ConcurrentHashMap<>();

        /**
         * 坏标记的最长存活时间（秒）。
         *
         * 6 小时 = 后台巡检的一个周期。含义是：**一个坏标记最多活到下一轮
         * 巡检，如果下一轮没再确认它坏，就不再算数**。这样既不会让一个
         * 偶发失败（上游抖一下、限流一下）把模型永久拉黑，也不会因为
         * 抽样没抽中就永久留着假账。
         *
         * 零证据的旧标记（升级上来的、没有时间戳的）不受本时限保护，
         * 在 {@link #expireStaleBad()} 里一次性放出。
         */
        public static final long BAD_TTL_SECONDS = 6L * 3600L;

        /**
         * 记一笔「这个模型坏了」，同时记下**时间和原因**。
         *
         * @return true＝这是新标记；false＝之前就已经是坏的（只刷新了时间和原因）
         */
        public boolean markBad(String model, String reason) {
            if (model == null || model.isEmpty()) return false;
            boolean fresh = !badModels.contains(model);
            if (fresh) badModels.add(model);
            badSince.put(model, System.currentTimeMillis() / 1000L);
            if (reason != null && !reason.isEmpty()) badReason.put(model, reason);
            return fresh;
        }

        /**
         * 撤掉一个模型的所有坏标记（探测通过、或用户手动加回来时调）。
         *
         * @return true＝本来确实有标记，这次清掉了；false＝本来就没标记
         */
        public boolean clearBad(String model) {
            if (model == null || model.isEmpty()) return false;
            badReason.remove(model);
            Long was = badSince.remove(model);
            boolean had = badModels.remove(model) || was != null;
            return had;
        }

        /**
         * 旧标记过期：把标记超过 {@link #BAD_TTL_SECONDS} 的模型从坏名单里放出来。
         *
         * 只在**加载完配置之后、以及每轮检测开始之前**调一次，不做成每读一次
         * 列表就现算——那样的话「界面上有几个坏的」这个数字会随时间自己变，
         * 用户看两次看到的数不一样，反而更没法信任。
         *
         * 没有时间戳的旧数据（从 4.29.0 及更早版本升级上来的）按「未知」处理：
         * 不直接删（可能是真的坏），但也不留原样，而是**当成刚过期的处理**——
         * 这些标记本来就没有证据支撑，留着是假账。
         *
         * @return 放出来的模型个数
         */
        public int expireStaleBad() {
            long now = System.currentTimeMillis() / 1000L;
            java.util.List<String> release = new java.util.ArrayList<>();
            for (String m : new java.util.ArrayList<>(badModels)) {
                Long since = badSince.get(m);
                if (since == null) {
                    release.add(m);
                } else if (now - since.longValue() > BAD_TTL_SECONDS) {
                    release.add(m);
                }
            }
            for (String m : release) clearBad(m);
            return release.size();
        }

        /** 「这个模型的坏标记还剩多少秒过期」；没有标记返回 -1，旧数据返回 0（已过期）。 */
        public long badTtl(String model) {
            if (!badModels.contains(model)) return -1L;
            Long since = badSince.get(model);
            if (since == null) return 0L;
            long left = BAD_TTL_SECONDS - (System.currentTimeMillis() / 1000L - since.longValue());
            return left < 0 ? 0L : left;
        }
        /** 实测能正确调用工具的模型（工具池）。 */
        public final List<String> toolNative = new ArrayList<>();
        /** 实测"打嘴炮"的模型：把 tool_call 写在正文里，不吐结构化调用。 */
        public final List<String> toolBad = new ArrayList<>();
        /**
         * 工具调用画像的**计数**（4.32.0）：模型 → [native 次数, bad 次数]。
         *
         * 为什么要它：toolNative / toolBad 是布尔名单，note() 是**覆盖式**写入 ——
         * 一次成功就把一个坏模型从 toolBad 移除、挪进 toolNative。偶发一次蒙对，
         * 一个系统性打嘴炮的模型就被洗白了，之后它会被优先选去干工具活。
         *
         * 有了计数就能看比例而不仅是"最近一次"：3 次 bad + 1 次 native 仍然该按 bad 算。
         * 两个 List 保留不动（旧配置、界面都在用），这里只是多一份证据。
         */
        public final Map<String, int[]> toolStats = new LinkedHashMap<>();
        /** Last failure reason, for the "unusable accounts" screen. */
        public String lastError = "";
        /**
         * 渠道校验已试到底的两类标记（"conv"=5 种形状都试过，"intl"=国际版身份也试过）。
         *
         * 为什么落盘：这两个标记原来只在 WorkBuddy 的内存 Set 里，进程重启就清空 ——
         * 于是每次开机、每次升级后又对同一个号重打最多 10 次 400，客户端看到的是
         * "卡一秒、报错、再卡一秒、再报错"。上游若改回去，客户端的"原样"第一轮
         * 仍然会去试，所以记住它不会让我们错过恢复。
         */
        /* 并发集合：请求线程读、刷新线程写；LinkedHashSet 并发读写会抛
           ConcurrentModificationException。 */
        public final java.util.Set<String> hopeless =
                java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
        /**
         * 限流状态：上游说什么时候恢复（epoch 秒），0 = 当前不在限流中。
         *
         * 为什么不复用 lastError：限流被**刻意**排除在 lastError 之外（见 Server 里的说明），
         * 否则一次 429 就会把健康账号挂上 30 分钟"不可用"、下次成功又清掉，
         * 用户看到的是"账号一会好一会坏"。但完全不记录也有代价 ——
         * 用户只看到"这个模型用不了"，点进账号详情一切正常，只能反复试。
         *
         * 所以单独一个字段：**只用来显示**"限流中，几点恢复"，不参与"是否可用"的判定。
         */
        public long throttleUntil = 0L;
        /** 给人看的一句限流说明（含恢复时刻），空 = 正常。 */
        public String throttleNote = "";
        /** Consecutive daily health-check failures; used to retire dead relay stations. */
        public int failStreak = 0;
        public long lastErrorAt = 0L;
        public long creditsAt = 0;

        /**
         * 用上游拉回来的目录替换 models，并把手动模型并回末尾。
         * 所有刷新路径都必须走这里，不要再直接 clear()+addAll()。
         */
        public void setModels(java.util.Collection<String> fetched) {
            if (!manualKnown) {
                // 升级后第一次刷新：本地有、上游没返回的，就是用户手加的，认下来
                manualKnown = true;
                if (fetched != null) {
                    for (String m : models) {
                        if (m != null && !m.trim().isEmpty() && !fetched.contains(m)
                                && !manualModels.contains(m)) {
                            manualModels.add(m);
                        }
                    }
                }
                if (!manualModels.isEmpty()) {
                    Log.i("认出 " + manualModels.size() + " 个手填模型，之后刷新会保留："
                            + manualModels);
                }
            }
            models.clear();
            if (fetched != null) {
                for (String m : fetched) {
                    if (m != null && !m.trim().isEmpty() && !models.contains(m.trim())) models.add(m.trim());
                }
            }
            addManualBack();
        }

        /** 把手动模型并回来（去重、保持顺序）。 */
        public void addManualBack() {
            for (String m : new java.util.LinkedHashSet<>(manualModels)) {
                if (m != null && !m.trim().isEmpty() && !models.contains(m)) models.add(m);
            }
        }

        /** "*" means "accept every model name" for pure pass-through channels. */
        public boolean matches(String model) {
            for (String m : models) {
                if ("*".equals(m)) return true;
                if (m.equalsIgnoreCase(model)) return true;
                if (m.endsWith("*") && model.toLowerCase().startsWith(m.substring(0, m.length() - 1).toLowerCase())) return true;
            }
            return false;
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                if (label != null && !label.isEmpty()) o.put("label", label);
                o.put("name", name);
                o.put("type", type);
                o.put("baseUrl", baseUrl);
                o.put("apiKey", apiKey);
            JSONArray aliases = new JSONArray();
            for (String alias : apiKeyAliases) aliases.put(alias);
            o.put("apiKeyAliases", aliases);
                o.put("enabled", enabled);
            o.put("catalogOnly", catalogOnly);
                /* 账号级开关（目前只有 intl_fallback）。空对象不写，保持老配置形状不变。 */
                if (options.length() > 0) o.put("options", options);
                o.put("credentials", credentials);
                o.put("credits", credits);
                o.put("creditsAt", creditsAt);
                o.put("rpm", rpm);
                o.put("rpd", rpd);
                o.put("tpm", tpm);
                o.put("tpd", tpd);
                o.put("maxConcurrent", maxConcurrent);
                o.put("vision", vision);
                o.put("contextLimit", contextLimit);
                o.put("dayRequests", dayRequests);
                o.put("dayTokens", dayTokens);
                o.put("quotaDay", quotaDay);
                JSONArray bad = new JSONArray();
                for (String m : new java.util.LinkedHashSet<>(badModels)) bad.put(m);
                o.put("badModels", bad);
                // 坏标记的时间与原因：和 badModels 一一对应，但单独存。
                // 为什么不合并成一个对象数组：老版本的 reader 会把对象读成
                // 空串，等于把用户的坏名单整个丢掉。分开存＝老版本读新配置
                // 仍然能拿到名字，只是丢时间（这是我们能接受的降级）。
                if (!hopeless.isEmpty()) {
                    JSONArray hp = new JSONArray();
                    for (String v : hopeless) if (v != null && !v.isEmpty()) hp.put(v);
                    o.put("hopeless", hp);
                }
                if (!badSince.isEmpty()) {
                    JSONObject bs = new JSONObject();
                    for (java.util.Map.Entry<String, Long> e : badSince.entrySet()) {
                        if (e.getKey() == null || e.getValue() == null) continue;
                        try {
                            bs.put(e.getKey(), e.getValue().longValue());
                        } catch (Exception ignored) {
                        }
                    }
                    if (bs.length() > 0) o.put("badSince", bs);
                }
                if (!badReason.isEmpty()) {
                    JSONObject br = new JSONObject();
                    for (java.util.Map.Entry<String, String> e : badReason.entrySet()) {
                        if (e.getKey() == null || e.getValue() == null) continue;
                        try {
                            br.put(e.getKey(), e.getValue());
                        } catch (Exception ignored) {
                        }
                    }
                    if (br.length() > 0) o.put("badReason", br);
                }
                if (!manualModels.isEmpty()) {
                    JSONArray mm = new JSONArray();
                    for (String m : new java.util.LinkedHashSet<>(manualModels)) mm.put(m);
                    o.put("manualModels", mm);
                }
                // 写的时候也去重：进程里可能同时存在多份 Config，
                // 只靠读的时候去重会越积越多
                JSONArray tn = new JSONArray();
                for (String m : new java.util.LinkedHashSet<>(toolNative)) tn.put(m);
                o.put("toolNative", tn);
                JSONArray tb = new JSONArray();
                for (String m : new java.util.LinkedHashSet<>(toolBad)) tb.put(m);
                o.put("toolBad", tb);
                if (!toolStats.isEmpty()) {
                    JSONObject ts = new JSONObject();
                    for (Map.Entry<String, int[]> e : toolStats.entrySet()) {
                        int[] v = e.getValue();
                        ts.put(e.getKey(), new JSONArray().put(v[0]).put(v[1]));
                    }
                    o.put("toolStats", ts);
                }
                o.put("lastError", lastError == null ? "" : lastError);
                if (throttleUntil > 0) o.put("throttleUntil", throttleUntil);
                if (throttleNote != null && !throttleNote.isEmpty()) o.put("throttleNote", throttleNote);
                o.put("failStreak", failStreak);
                o.put("lastErrorAt", lastErrorAt);
                o.put("models", new JSONArray(models));
            } catch (Exception ignored) {
            }
            return o;
        }

        public static Provider fromJson(JSONObject o) {
            Provider p = new Provider();
            p.id = Proto.optString(o, "id", "");
            p.label = Proto.optString(o, "label", "");
            p.name = Proto.optString(o, "name", "");
            p.type = Proto.optString(o, "type", TYPE_OPENAI);
            p.baseUrl = Proto.optString(o, "baseUrl", "");
            p.apiKey = Proto.optString(o, "apiKey", "");
            p.enabled = o.optBoolean("enabled", true);
        p.catalogOnly = o.optBoolean("catalogOnly", false);
            JSONObject opts = o.optJSONObject("options");
            if (opts != null) {
                java.util.Iterator<String> oit = opts.keys();
                while (oit.hasNext()) {
                    String ok = oit.next();
                    try {
                        p.options.put(ok, opts.opt(ok));
                    } catch (Exception ignored) {
                    }
                }
            }
            p.credentials = Proto.optString(o, "credentials", "");
            Double cr = Proto.optDouble(o, "credits");
            p.credits = cr == null ? -1 : cr.doubleValue();
            JSONArray tn0 = o.optJSONArray("toolNative");
            if (tn0 != null) {
                for (int i = 0; i < tn0.length(); i++) {
                    String m = tn0.optString(i, "");
                    if (!m.isEmpty() && !p.toolNative.contains(m)) p.toolNative.add(m);
                }
            }
            JSONArray tb0 = o.optJSONArray("toolBad");
            if (tb0 != null) {
                for (int i = 0; i < tb0.length(); i++) {
                    String m = tb0.optString(i, "");
                    if (!m.isEmpty() && !p.toolBad.contains(m)) p.toolBad.add(m);
                }
            }
            JSONObject ts0 = o.optJSONObject("toolStats");
            if (ts0 != null) {
                for (java.util.Iterator<String> it = ts0.keys(); it.hasNext(); ) {
                    String k = it.next();
                    JSONArray v = ts0.optJSONArray(k);
                    if (v == null || v.length() < 2) continue;
                    p.toolStats.put(k, new int[]{v.optInt(0, 0), v.optInt(1, 0)});
                }
            }
            JSONArray hp = o.optJSONArray("hopeless");
            if (hp != null) {
                for (int i = 0; i < hp.length(); i++) {
                    String v = hp.optString(i, "");
                    if (!v.isEmpty()) p.hopeless.add(v);
                }
            }
            JSONArray bad = o.optJSONArray("badModels");
            if (bad != null) {
                for (int i = 0; i < bad.length(); i++) {
                    String m = bad.optString(i, "");
                    if (!m.isEmpty() && !p.badModels.contains(m)) p.badModels.add(m);
                }
            }
            JSONObject bs0 = o.optJSONObject("badSince");
            if (bs0 != null) {
                java.util.Iterator<String> it = bs0.keys();
                while (it.hasNext()) {
                    String m = it.next();
                    if (m == null || m.isEmpty()) continue;
                    p.badSince.put(m, bs0.optLong(m, 0L));
                }
            }
            JSONObject br0 = o.optJSONObject("badReason");
            if (br0 != null) {
                java.util.Iterator<String> it = br0.keys();
                while (it.hasNext()) {
                    String m = it.next();
                    if (m == null || m.isEmpty()) continue;
                    String r = br0.optString(m, "");
                    if (!r.isEmpty()) p.badReason.put(m, r);
                }
            }
            // 加载完成后立刻让旧标记过期：**在写盘之前**做，这样界面第一次
            // 拿到配置时看到的就已经是干净数，不用等一轮检测。
            p.expireStaleBad();
            p.lastError = Proto.optString(o, "lastError", "");
            Long tu = Proto.optLong(o, "throttleUntil");
            p.throttleUntil = tu == null ? 0L : tu.longValue();
            p.throttleNote = Proto.optString(o, "throttleNote", "");
            Integer fs = Proto.optInt(o, "failStreak");
            p.failStreak = fs == null ? 0 : fs.intValue();
            Long lea = Proto.optLong(o, "lastErrorAt");
            p.lastErrorAt = lea == null ? 0L : lea.longValue();
            Long ca = Proto.optLong(o, "creditsAt");
            p.creditsAt = ca == null ? 0L : ca.longValue();
            Integer rpmv = Proto.optInt(o, "rpm");
            p.rpm = rpmv == null ? 0 : rpmv.intValue();
            Integer rpdv = Proto.optInt(o, "rpd");
            p.rpd = rpdv == null ? 0 : rpdv.intValue();
            Integer tpmv = Proto.optInt(o, "tpm");
            p.tpm = tpmv == null ? 0 : tpmv.intValue();
            Integer tpdv = Proto.optInt(o, "tpd");
            p.tpd = tpdv == null ? 0 : tpdv.intValue();
            Integer mcv = Proto.optInt(o, "maxConcurrent");
            p.maxConcurrent = mcv == null ? 0 : mcv.intValue();
            p.vision = o.optBoolean("vision", true);
            Integer clv = Proto.optInt(o, "contextLimit");
            p.contextLimit = clv == null ? 0 : clv.intValue();
            Integer drv = Proto.optInt(o, "dayRequests");
            p.dayRequests = drv == null ? 0 : drv.intValue();
            Long dtv = Proto.optLong(o, "dayTokens");
            p.dayTokens = dtv == null ? 0L : dtv.longValue();
            Integer qdv = Proto.optInt(o, "quotaDay");
            p.quotaDay = qdv == null ? 0 : qdv.intValue();
            JSONArray arr = o.optJSONArray("models");
            if (arr != null) for (int i = 0; i < arr.length(); i++) p.models.add(String.valueOf(arr.opt(i)));
            JSONArray mm = o.optJSONArray("manualModels");
            if (mm != null) {
                for (int i = 0; i < mm.length(); i++) p.manualModels.add(String.valueOf(mm.opt(i)));
                p.manualKnown = true;
            } else {
                // 老配置：先不定论，等第一次刷新看哪些是上游不认识的
                p.manualKnown = false;
            }
            if (p.id.isEmpty()) p.id = "p" + System.currentTimeMillis();
            if (p.name.isEmpty()) p.name = p.type;
            return p;
        }
    }

    /**
     * 导出用：内存里的**明文**配置 JSON。
     *
     * 这里必须用 toJson()，不能用 {@link Secret#seal(JSONObject)} ——
     * Secret 用的是本机 Android Keystore 密钥，**换机/重装后必然解不开**，
     * 而备份的唯一用途恰恰是换机迁移。4.37.0 之前的实现就是先 seal 再交给
     * Backup 用密码加密，结果导入后所有账号凭据和网关 Key 都变成空串，
     * 而且没有任何报错。
     *
     * 跨设备的保护由 {@link Backup} 的「用户密码 + PBKDF2 + AES-GCM」负责；
     * 导出文件不会以明文落到磁盘（见 Backup.export）。
     */
    public JSONObject exportJson() {
        return toJson();
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("port", port);
        o.put("accentTheme", accentTheme == null ? "spring" : accentTheme);
        o.put("lastRelayCheck", lastRelayCheck);
        o.put("autoDetect", autoDetect);
        o.put("lastAutoDetect", lastAutoDetect);
        o.put("autoDetectInterval", autoDetectInterval);
        JSONArray a = new JSONArray();
        for (String u : aliveStations) a.put(u);
        o.put("aliveStations", a);
        JSONArray d = new JSONArray();
        for (String u : deadStations) d.put(u);
        o.put("deadStations", d);
        o.put("stationsCheckedAt", stationsCheckedAt);
        JSONArray disc = new JSONArray();
        for (String u : discoveredStations) disc.put(u);
        o.put("discoveredStations", disc);
        JSONArray rem = new JSONArray();
        for (String u : removedStations) rem.put(u);
        o.put("removedStations", rem);
        JSONArray fails = new JSONArray();
        for (String u : stationFailures) fails.put(u);
        o.put("stationFailures", fails);
            o.put("lanAccess", lanAccess);
            o.put("autostart", autostart);
            o.put("keepAlive", keepAlive);
            o.put("userStopped", userStopped);
        o.put("lightMode", lightMode);
            o.put("apiKey", apiKey);
            JSONArray aliases = new JSONArray();
            for (String alias : apiKeyAliases) {
                if (alias != null && !alias.isEmpty()) aliases.put(alias);
            }
            o.put("apiKeyAliases", aliases);
            JSONArray arr = new JSONArray();
            for (Provider p : providers) arr.put(p.toJson());
            o.put("providers", arr);
        } catch (Exception ignored) {
        }
        return o;
    }

    public static Config fromJson(JSONObject o) {
        Config c = new Config();
        c.port = o.optInt("port", 8321);
        c.lanAccess = o.optBoolean("lanAccess", false);
        c.autostart = o.optBoolean("autostart", false);
        c.keepAlive = o.optBoolean("keepAlive", true);
        c.userStopped = o.optBoolean("userStopped", false);
        c.lightMode = o.optBoolean("lightMode", false);
        c.accentTheme = Proto.optString(o, "accentTheme", "spring");
        Long lrc = Proto.optLong(o, "lastRelayCheck");
        c.lastRelayCheck = lrc == null ? 0L : lrc.longValue();
        c.autoDetect = o.optBoolean("autoDetect", true);
        Long lad = Proto.optLong(o, "lastAutoDetect");
        c.lastAutoDetect = lad == null ? 0L : lad.longValue();
        Long adi = Proto.optLong(o, "autoDetectInterval");
        c.autoDetectInterval = adi == null ? 21600L : Math.max(600L, adi.longValue());
        JSONArray al = o.optJSONArray("aliveStations");
        for (int i = 0; al != null && i < al.length(); i++) {
            String u = al.optString(i, "");
            if (!u.isEmpty()) c.aliveStations.add(u);
        }
        JSONArray dd = o.optJSONArray("deadStations");
        for (int i = 0; dd != null && i < dd.length(); i++) {
            String u = dd.optString(i, "");
            if (!u.isEmpty()) c.deadStations.add(u);
        }
        JSONArray disc = o.optJSONArray("discoveredStations");
        for (int i = 0; disc != null && i < disc.length(); i++) {
            String u = disc.optString(i, "");
            if (!u.isEmpty()) c.discoveredStations.add(u);
        }
        JSONArray rem = o.optJSONArray("removedStations");
        for (int i = 0; rem != null && i < rem.length(); i++) {
            String u = rem.optString(i, "");
            if (!u.isEmpty()) c.removedStations.add(u);
        }
        JSONArray fails = o.optJSONArray("stationFailures");
        for (int i = 0; fails != null && i < fails.length(); i++) {
            String u = fails.optString(i, "");
            if (!u.isEmpty()) c.stationFailures.add(u);
        }
        Long sca = Proto.optLong(o, "stationsCheckedAt");
        c.stationsCheckedAt = sca == null ? 0L : sca.longValue();
        /* 空 Key 要**照实保留**，不能"空了就退回默认随机值"。
           今天真机就是这么坏的：配置里 apiKey 变成空串之后，每次进程启动都会
           生成一个新的默认 Key（sk-cpa-local-xxxx），于是客户端拿旧 Key 一律 401、
           表现为不停重连，而每次重启 Key 还不一样，根本对不上。
           现在的语义：
             · 文件里 apiKey 是空串 → 就是"不校验本机 Key"（局域网模式仍强制要求 Key，见 Server.route）；
             · 文件里没有 apiKey 字段（老配置）→ 才用默认值生成一个。 */
        if (o.has("apiKey")) {
            String stored = Proto.optString(o, "apiKey", "");
            /* 密文解不开时（Keystore 重建 \/ 换机恢复），以前会拿到空串 ——
               而空串在这里表示"用户主动清空了 Key、不校验"。于是一次解密失败会被
               当成用户的意图**写回磁盘**，Key 就真的永久丢了，而且客户端从此不再有鉴权。
               现在：解不开就保留内存里已有的值，并在日志里明确说一句"这个字段没读出来"。 */
            if (Secret.isSealed(stored) && Secret.open(stored).isEmpty()) {
                Log.w("配置里的客户端 Key 解不开（Keystore 可能已重建），本次沿用内存中的值，"
                        + "没有把它当成已清空写回磁盘");
            } else {
                c.apiKey = stored;
            }
        }
        JSONArray aliases = o.optJSONArray("apiKeyAliases");
        if (aliases != null) {
            for (int i = 0; i < aliases.length(); i++) {
                String alias = aliases.optString(i, "");
                if (!alias.isEmpty() && !c.apiKeyAliases.contains(alias)) c.apiKeyAliases.add(alias);
            }
        }
        JSONArray arr = o.optJSONArray("providers");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject po = arr.optJSONObject(i);
                if (po != null) c.providers.add(Provider.fromJson(po));
            }
        }
        return c;
    }

    /* --------------------------------------------------------------- storage */

    public static File file(Context ctx) {
        File dir = ctx.getFilesDir();
        if (dir == null) dir = ctx.getDir("files", Context.MODE_PRIVATE);
        return new File(dir, "gateway.json");
    }

    /** 记住某账号"渠道校验已试到底"（conv / intl），并择机落盘（合并写，不占请求线程）。 */
    public static void markHopeless(String providerName, String kind) {
        try {
            Config c = Gateway.config();
            if (c == null || providerName == null) return;
            Config.Provider target = null;
            for (Config.Provider p : c.providers) {
                if (providerName.equals(p.name)) { target = p; break; }
            }
            if (target == null) return;
            if (!target.hopeless.add(kind)) return;
            Gateway.persistSoon();
        } catch (Throwable ignored) {
        }
    }

    public static Config load(Context ctx) {
        File f = file(ctx);
        if (!f.exists()) {
            Config c = new Config();
            // 全新安装不预置任何通道：以前这里会塞一个指向 wb2api(:7863) 的转发口，
            // 那个软件一关通道就是死的，会误导用户。
            c.save(ctx);
            return c;
        }
        try {
            byte[] buf = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            // 单次 read() 可能短读（尤其配置大了以后），会误报"配置损坏"。
            // 循环读满，短了才当错。
            int n = 0;
            while (n < buf.length) {
                int got = in.read(buf, n, buf.length - n);
                if (got < 0) break;
                n += got;
            }
            in.close();
            if (n <= 0) throw new IllegalStateException("配置文件是空的");
            JSONObject raw = new JSONObject(new String(buf, 0, n, "UTF-8"));
            /* 必须在 Secret.open(raw) 之前判断：open() 会就地把它换成空串，
               之后就再也分不清"本来就没有 Key"和"Key 解不开"了。 */
            String rawKey = Proto.optString(raw, "apiKey", "");
            boolean keyLost = Secret.isSealed(rawKey) && Secret.open(rawKey).isEmpty();
            /* 账号凭据也可能解不开（Keystore 重建/换机/临时不可用）。必须在 open()
               之前判断：open() 会把解不开的值就地换成空串，之后再保存就会把
               所有 refresh_token 永久写成空。 */
            JSONObject opened = Secret.open(new JSONObject(raw.toString()));
            /* 解不开的密文原样保留，避免后续保存把 refresh_token 覆盖成空串。 */
            Secret.restoreUnreadable(raw, opened);
            Config loaded = fromJson(opened);
            /* 凭据解不开的账号必须退出派发：它的 credentials 在本次运行里是空的，
               留在池子里就是"显示可用、一选就失败"。标记只在内存里（见
               Provider.credentialsUnreadable），Keystore 恢复或重新登录后自然消失。 */
            markUnreadableProviders(raw, opened, loaded);
            if (keyLost) {
                /* 绝不能把"解不开"当成"用户清空了 Key"（旧行为：本机模式下直接不校验）。
                   这里生成一把新 Key 并立刻落盘（用当前 Keystore 加密），
                   鉴权保持开启；用户到设置页重新复制一次给客户端即可。 */
                loaded.apiKey = "sk-cpa-" + java.util.UUID.randomUUID().toString().replace("-", "");
                loaded.apiKeyUnreadable = true;
                Log.e("客户端 Key 解不开（Keystore 重建或换机），已生成新 Key；"
                        + "请到设置页把「客户端密钥」重新复制给客户端");
                try {
                    /* 不可读的账号密文已由 restoreUnreadable 原样保留，保存不会丢数据。 */
                    loaded.save(ctx);
                } catch (Throwable t) {
                    Log.w("新 Key 落盘失败（本次运行仍生效）：" + t);
                }
            }
            if (loaded.providers.isEmpty()) {
                loaded = recoverFromSnapshot(ctx, loaded);
            }
            return loaded;
        } catch (Exception e) {
            Log.w("config load failed: " + e);
            try {
                java.io.File bad = new java.io.File(f.getParentFile(), "gateway.json.corrupt");
                java.io.FileInputStream in = new java.io.FileInputStream(f);
                java.io.FileOutputStream out = new java.io.FileOutputStream(bad);
                byte[] tmp = new byte[8192];
                int r;
                while ((r = in.read(tmp)) > 0) out.write(tmp, 0, r);
                in.close();
                out.close();
                Log.w("原文件已另存为 gateway.json.corrupt（没有被覆盖）");
            } catch (Exception ignored) {
            }
            throw new IllegalStateException("读取配置失败，已保留原文件：" + e);
        }
    }

    private static int countProvidersOnDisk(File f) {
        try {
            byte[] buf = readAll(f);
            if (buf.length == 0) return 0;
            JSONArray arr = new JSONObject(new String(buf, "UTF-8")).optJSONArray("providers");
            return arr == null ? 0 : arr.length();
        } catch (Exception e) {
            return 0;
        }
    }

    private static void copyFile(File from, File to) {
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(from);
            java.io.FileOutputStream out = new java.io.FileOutputStream(to);
            byte[] tmp = new byte[8192];
            int r;
            while ((r = in.read(tmp)) > 0) out.write(tmp, 0, r);
            in.close();
            out.close();
        } catch (Exception ignored) {
        }
    }

    private static void trimBackups(File dir, int keep) {
        try {
            File[] files = dir.listFiles();
            if (files == null) return;
            java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (int i = keep; i < files.length; i++) files[i].delete();
        } catch (Exception ignored) {
        }
    }

    /**
     * 这个配置到底要不要校验客户端 Key。
     *
     * 只有"设了 Key 或还有旧 Key 别名"才算要校验；两者都空就是不校验（本机模式）。
     * 单独抽出来，是为了让"设置页的空输入框不该被自动填回一个 Key"这件事有个
     * 唯一判据，而不是各处去比字符串。
     */
    public boolean keyRequired() {
        if (apiKey != null && !apiKey.isEmpty()) return true;
        for (String a : apiKeyAliases) {
            if (a != null && !a.isEmpty()) return true;
        }
        return false;
    }

    /** Keys the gateway accepts: the current one plus any kept aliases. */
    public boolean acceptsKey(String candidate) {
        if (candidate == null || candidate.isEmpty()) return false;
        boolean ok = false;
        if (apiKey != null && !apiKey.isEmpty() && constantTimeEquals(candidate, apiKey)) ok = true;
        for (String alias : apiKeyAliases) {
            if (alias != null && !alias.isEmpty() && constantTimeEquals(candidate, alias)) ok = true;
        }
        return ok;
    }

    /**
     * 定长时间比较访问 Key，避免 {@link String#equals} 的提前返回把"猜中前几位"变成
     * 可测量的时间差（局域网模式下的旁路信道）。
     *
     * 做法：长度不同也走完整循环（用异或累积差值，不 break），最后再叠加长度差。
     * 这样比较耗时只与两个串长度的较大值有关，与"前多少位相同"无关。
     * 密码学上这不是 MAC 校验，对本地网关足够：它挡的是统计时间侧信道，不是暴力破解
     * （暴力破解由 Key 本身的长度承担）。
     */
    private static boolean constantTimeEquals(String a, String b) {
        byte[] x = a.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] y = b.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int diff = x.length ^ y.length;
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            byte cx = i < x.length ? x[i] : 0;
            byte cy = i < y.length ? y[i] : 0;
            diff |= (cx ^ cy);
        }
        return diff == 0;
    }

    /**
     * 落盘串行锁：save()（用户显式保存，走 UI 线程）和 saveQuick()
     * （合并写，走后台线程）原来可能同时写同一个文件，JSON 会写花；
     * 现在串行，谁也别插队。
     */
    private final Object saveLock = new Object();

    public void save(Context ctx) {
        save(ctx, false);
    }

    /**
     * 热路径快速保存：只写 gateway.json，不做 .bak / 时间戳备份轮转。
     * 给 Gateway.persistSoon() 用的（token 刷新、探活收尾这类高频调用）；
     * 备份轮转是"防用户手滑"的安全网，只在用户显式保存（save()）时做。
     * 空配置保护保留：内存里没账号但磁盘上有，就拒绝写入。
     */
    public void saveQuick(Context ctx) {
        synchronized (saveLock) {
            try {
                File f = file(ctx);
                File parent = f.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                if (f.exists() && f.length() > 0 && providers.isEmpty()
                        && countProvidersOnDisk(f) > 0) {
                    Log.w("拒绝写入空配置（磁盘上还有账号），本次快速保存已跳过");
                    return;
                }
                writeAtomically(f, Secret.seal(toJson()).toString(2).getBytes("UTF-8"));
                snapshotAccounts(f);
            } catch (Exception e) {
                Log.w("config saveQuick failed: " + e);
            }
        }
    }

    /** 账号快照文件名（跟 gateway.json 放一起）。 */
    private static final String SNAPSHOT = "gateway.accounts.json";

    /** 读整个文件。包级可见：Gateway 导入备份时要先给旧配置留一份，见 replaceConfigFromBackup。 */
    static byte[] readAllPublic(java.io.File f) throws Exception {
        return readAll(f);
    }

    private static byte[] readAll(java.io.File f) throws Exception {
        byte[] buf = new byte[(int) f.length()];
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            int n = 0;
            while (n < buf.length) {
                int got = in.read(buf, n, buf.length - n);
                if (got < 0) break;
                n += got;
            }
            if (n <= 0) return new byte[0];
            if (n == buf.length) return buf;
            byte[] out = new byte[n];
            System.arraycopy(buf, 0, out, 0, n);
            return out;
        } finally {
            in.close();
        }
    }

    /**
     * 账号快照：防"配置被清空 / 被覆盖"——这两种事故在真机上都发生过。
     *
     * 规则很简单：保存成功后，把整份配置复制一份成 gateway.accounts.json，
     * **只在账号数没有变少时更新**（免得一次空保存把快照也冲掉）。
     */
    private static void snapshotAccounts(java.io.File live) {
        try {
            JSONObject cur = Secret.open(new JSONObject(new String(readAll(live), "UTF-8")));
            JSONArray arr = cur.optJSONArray("providers");
            int now = arr == null ? 0 : arr.length();
            if (now <= 0) return;
            java.io.File snap = new java.io.File(live.getParentFile(), SNAPSHOT);
            int prev = 0;
            if (snap.isFile() && snap.length() > 0) {
                try {
                    JSONObject old = Secret.open(new JSONObject(new String(readAll(snap), "UTF-8")));
                    JSONArray oa = old.optJSONArray("providers");
                    prev = oa == null ? 0 : oa.length();
                } catch (Throwable ignored) {
                }
            }
            if (now < prev) return;
            writeAtomically(snap, readAll(live));
        } catch (Throwable t) {
            Log.w("账号快照失败：" + t);
        }
    }

    /**
     * 把"凭据在本机解不开"的账号标出来，让它们退出派发与探活。
     *
     * 为什么值得单做：解密失败以前只是几条 WARN，账号照旧留在池子里显示可用。
     * 客户端选中它时凭据是空串，上游必然拒绝 —— 用户看到的是"随机某个模型用不了"，
     * 而真正的原因（Keystore 打不开这个密文）藏在日志里。
     *
     * 判定用的是**原始 JSON**（raw）：opened 里解不开的字段已被换成空串或保留密文，
     * 两者都看不出"是不是刚解失败的"。raw 与 opened 同一顺序，按下标配对。
     */
    private static void markUnreadableProviders(JSONObject raw, JSONObject opened, Config loaded) {
        try {
            JSONArray rp = raw.optJSONArray("providers");
            JSONArray op = opened.optJSONArray("providers");
            if (rp == null || loaded == null) return;
            int marked = 0;
            for (int i = 0; i < rp.length() && i < loaded.providers.size(); i++) {
                JSONObject r = rp.optJSONObject(i);
                if (r == null) continue;
                Config.Provider p = loaded.providers.get(i);
                boolean bad = false;
                String where = "";
                if (unreadableSealedField(r, "apiKey")) {
                    bad = true;
                    where = "apiKey";
                }
                if (unreadableSealedField(r, "credentials")) {
                    bad = true;
                    where = where.isEmpty() ? "credentials" : where + "+credentials";
                }
                if (bad) {
                    p.credentialsUnreadable = true;
                    marked++;
                    Log.e("账号「" + p.name + "」的 " + where
                            + " 在本机解不开，本次运行不参与派发与探活；"
                            + "请重新登录该账号（原密文已保留，未被覆盖）");
                }
            }
            if (marked > 0) {
                Log.w("共 " + marked + " 个账号因凭据解不开而暂时退出（重启或重新登录后恢复）");
            }
        } catch (Throwable t) {
            Log.w("标记解不开凭据时出错（不影响加载）：" + t);
        }
    }

    /** 这个字段是不是"存在且是密文、但解不开"。 */
    private static boolean unreadableSealedField(JSONObject o, String field) {
        try {
            if (o == null || !o.has(field)) return false;
            String v = Proto.optString(o, field, "");
            return Secret.isSealed(v) && Secret.open(v).isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 当前配置没有账号时，尝试用快照恢复（并写回 gateway.json）。 */
    private static Config recoverFromSnapshot(Context ctx, Config current) {
        try {
            java.io.File snap = new java.io.File(ctx.getFilesDir(), SNAPSHOT);
            if (!snap.isFile() || snap.length() <= 0) return current;
            JSONObject o = Secret.open(new JSONObject(new String(readAll(snap), "UTF-8")));
            JSONArray arr = o.optJSONArray("providers");
            if (arr == null || arr.length() == 0) return current;
            Config restored = fromJson(o);
            if (restored.providers.isEmpty()) return current;
            Log.w("当前配置里没有账号，已从账号快照恢复 " + restored.providers.size() + " 个账号");
            try {
                restored.save(ctx);
            } catch (Throwable ignored) {
            }
            return restored;
        } catch (Throwable t) {
            Log.w("账号快照恢复失败：" + t);
            return current;
        }
    }

    /**
     * 原子落盘：先写临时文件再 rename。原来直接覆盖写，进程在中途被杀
     * （低内存手机常见）会留下半截 JSON，下次启动配置就读不出来了。
     */
    private static void writeAtomically(java.io.File target, byte[] data) throws Exception {
        java.io.File parent = target.getParentFile();
        java.io.File tmp = new java.io.File(parent, target.getName() + ".tmp");
        java.io.FileOutputStream out = new java.io.FileOutputStream(tmp);
        try {
            out.write(data);
            out.flush();
            try {
                out.getFD().sync();
            } catch (Exception ignored) {
            }
        } finally {
            out.close();
        }
        if (!tmp.renameTo(target)) {
            // rename 失败（极少）：退化成直接覆盖，总比丢配置强
            java.io.FileOutputStream direct = new java.io.FileOutputStream(target);
            try {
                direct.write(data);
            } finally {
                direct.close();
            }
            tmp.delete();
        }
    }

    /**
     * Writes the configuration with two safety nets: the previous file is always copied aside first,
     * and an empty provider list never silently replaces a non-empty one (that is how accounts were
     * lost once already).
     */
    public void save(Context ctx, boolean allowEmptyProviders) {
        synchronized (saveLock) {
        try {
            File f = file(ctx);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            if (f.exists() && f.length() > 0) {
                int onDisk = countProvidersOnDisk(f);
                if (providers.isEmpty() && onDisk > 0 && !allowEmptyProviders) {
                    Log.w("拒绝写入空配置（磁盘上还有 " + onDisk + " 个账号），本次保存已跳过");
                    return;
                }
                // 只留一份 gateway.json.bak：原来每次保存还往 backup/ 里塞一份带时间戳的，
                // 轮转 8 份——token 一刷新就写盘，这个目录几个月能堆出几十兆没人看。
                copyFile(f, new java.io.File(parent, "gateway.json.bak"));
            }
            writeAtomically(f, Secret.seal(toJson()).toString(2).getBytes("UTF-8"));
            snapshotAccounts(f);
        } catch (Exception e) {
            Log.w("config save failed: " + e);
        }
        }
    }

    /**
     * A ready-to-run CodeBuddy channel: the on-device wb2api gateway already speaks OpenAI on
     * 127.0.0.1:7863, so it can be carried over as-is without re-implementing its login flow.
     */
    /**
     * Auto name for a freshly added account: "Trae 1", "WorkBuddy 2" ... instead of dumping the
     * phone number or e-mail address into the list. The identity itself stays in the credentials and
     * is shown on the account detail page.
     */
    public static String nextAccountName(String label, String type) {
        Config cfg = Gateway.config();
        int count = 0;
        for (Config.Provider p : cfg.providers) if (type != null && type.equals(p.type)) count++;
        int index = count + 1;
        String candidate;
        do {
            candidate = label + " " + index++;
            boolean taken = false;
            for (Config.Provider p : cfg.providers) {
                if (p.name != null && p.name.equalsIgnoreCase(candidate)) {
                    taken = true;
                    break;
                }
            }
            if (!taken) return candidate;
        } while (index < 1000);
        return label + " " + System.currentTimeMillis() % 1000;
    }

}
