package com.cpa.gateway.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用量统计：每次"真流量"请求记一行，滚动 30 天。
 *
 * 之前这块完全空白——想回答"今天用了多少次、哪个渠道在跑、平均多慢"只能一行行翻日志。
 * 参考项目里用量/额度是标配，这里按手机端的方式做得很轻：
 *   · 行式 TSV（不引数据库、不加依赖），后台追加写；
 *   · 只记推理接口（chat / messages / responses / gemini generateContent），
 *     模型列表、健康检查、debug 请求都不算，免得把数字灌水；
 *   · 读取时才扫文件，不常驻内存；超过 30 天自动裁掉。
 */
public final class Usage {

    /**
     * 一行：时间戳(秒) \t 接口 \t 模型 \t 渠道 \t 耗时ms \t 成功(1/0) \t 输入tok \t 输出tok \t Key标签
     *       \t 输出字符数 \t 工具调用(1/0) \t 结束原因
     *
     * 后三列是 4.32.0 加的，原因是：前九列里没有一列能回答"这次回答**内容上**行不行"。
     * 那个「成功」只反映传输层（路由没抛异常、有渠道、HTTP 没报错），
     * 模型回一句"抱歉我不能"、输出被 max_tokens 截成半句话、返回空串 —— 全都记成功。
     *
     * 为什么选这三样：
     *   · 输出字符数 —— 上游普遍不返回 usage（中转站尤其），tokOut 长期是 0；
     *     但 Sink 的 text(delta) 是逐字过来的，自己数字符不依赖上游。
     *   · 工具调用   —— sawToolCall() 本来就在观察，只是没落到数据里。
     *   · 结束原因   —— stopReason 在 tap 里收到就丢了，于是 length（截断）和
     *     stop（正常）在数据里没有任何区别，截断彻底不可见。
     *
     * 三样都是**零成本**：全部来自已有的回调点，不额外请求、不改协议、不改上游。
     *
     * 4.33.0 起又在末尾追加了 10 列观测上下文（见 {@link Obs} 与 record()），
     * 4.51.7 再加 1 列缓存命中 token，现在一共 23 列。
     * 读取端一律按长度判断，缺列当 0/空处理——旧行不能因为
     * "没有新列"就被算成失败或零值。**新增列只能追加在末尾**：
     * 中间插一列会打乱后面所有列的索引，而旧行还在磁盘上躺着。
     */
    private static final String FILE = "usage.tsv";
    private static final long KEEP_MS = 30L * 24 * 3600 * 1000;

    /**
     * 一次请求的观测上下文（4.33.0）。
     *
     * 这不是"再多记几个数字"，而是补上**从请求到响应**的完整链路观测：
     * 我们发了什么 → 上游回了什么形态 → 我们怎么处理的。
     * 缺任一环，"模型的工具调用逻辑"就只能靠猜。
     */
    public static final class Obs {
        /** 来源：user=真实用户流量，probe=探活，detect=检测，unknown=不知道。 */
        public String source = "unknown";
        /** 这次请求带了多少个工具定义（0=没带）。 */
        public int tools;
        /**
         * 上游响应的形态：
         *   native  —— 结构化 tool_calls（正常）
         *   text    —— 工具调用写在正文里（DSML / <tool_call> / fn(...)）
         *   empty   —— 什么都没吐
         *   error   —— 上游报错
         *   plain   —— 纯文本回答，本来就没打算调工具
         */
        public String shape = "";
        /**
         * 我们的解析结果：
         *   none    —— 无需解析（上游给的就是结构化调用，或本来没调用）
         *   ok      —— 从正文里成功提取并转成了 tool_call
         *   fail    —— 看起来是调用但没提取出来
         *   fixed   —— 参数坏，ToolFix 修好了
         *   dropped —— 参数坏到修不了，丢弃
         */
        public String parse = "";
        /** 对话历史里是否已含工具调用/工具结果（多轮工具循环的第 2 轮及以后）。 */
        public boolean hasToolHistory;
        /**
         * 结果质量（4.35.0）：ok / empty / cut / off / retry。
         *
         * 这是唯一一列**判断回答本身行不行**的：前面几列都只描述传输和形态。
         * 判据全部是结构性的（空、截断、跑题、用户重发），不碰语义——
         * 因为"答得好不好"需要品味，写不进代码，判错比不判更糟。
         */
        public String quality = "";
        /**
         * 上游返回的响应 ID（4.36.0）。
         *
         * 和上游对账的唯一键。Trae 报 11128 那次，就是靠"五次回包的 requestId
         * 一模一样"断定上游不看我们发的东西——但当时这个 ID 只在日志里，
         * 重启就没了。现在落盘。
         */
        public String responseId = "";
        /** 会话标识：把同一轮的多跳请求串起来（多轮工具循环尤其需要）。 */
        public String conversationId = "";
        /**
         * 错误归类（4.36.1）：rate / quota / auth / channel / badreq /
         * timeout / net / empty / other。空表示没出错。
         *
         * 和 quality 的区别：quality 说"结果能不能用"，
         * errorKind 说"为什么不能用"。两者的正确应对完全不同——
         * 限流等一会儿就好，额度耗尽换号，请求非法换号更没用。
         */
        public String errorKind = "";
        /**
         * 工具名清单（4.36.1），逗号分隔。
         *
         * 以前只记工具个数。数量对上不代表定义对上——
         * 某个特定工具名或参数 schema 可能正是上游拒绝的原因。
         * 只记名字不记完整 schema：schema 太长，落盘会把用量文件撑爆。
         */
        public String toolNames = "";
        /**
         * 命中**前缀缓存**的输入 token 数（4.51.7）。
         *
         * 为什么值得单开一列：命中缓存的输入 token 单价低得多（Anthropic 约 1/10、
         * OpenAI 约 1/2），混在 tokIn 里按全价算会让"成本 ≈$"明显偏高。
         * 更要紧的是——**这是会话亲和（4.51.5）唯一的成效指标**：
         * 亲和存在的意义就是让同一段对话复用同一个上游、保住 prompt cache，
         * 而"缓存命中率涨没涨"只有这个数能回答。
         *
         * 为什么放在 Obs 而不是当一个独立参数：`record()` 已经有 12 个参数、
         * 还有两个只传前几个的旧签名。再加一个位置参数会变成 13 个，
         * 调用点全要改、且以后每加一个观测项都要再来一遍。
         * 附着在 Obs 上是因为它**本来就是观测数据**（不影响任何判断），
         * 语义也对：老调用点不传就是 0，与"上游没报缓存"完全等价，
         * 不需要区分"没数据"和"真的是 0"——这两个在成本计算里是同一个意思。
         */
        public int cached;

        public static final Obs EMPTY = new Obs();
    }

    private static final Object LOCK = new Object();
    private static final List<String> pending = new ArrayList<>();
    private static long lastWrite;
    private static long lastTrim;

    private Usage() {
    }

    /** 只有推理接口算"用量"。 */
    static boolean isInference(String api) {
        if (api == null || api.isEmpty()) return false;
        return api.contains("/chat/completions")
                || api.endsWith("/messages")
                || api.endsWith("/responses")
                || api.contains("generateContent");
    }

    /** 记一次请求：网关线程上只做入队，落盘在后台攒批。 */
    public static void record(String api, String model, String provider, long ms,
                              boolean ok, int tokIn, int tokOut, String keyLabel) {
        record(api, model, provider, ms, ok, tokIn, tokOut, keyLabel, 0, false, "");
    }

    /** 带内容信号的版本（4.32.0）。旧调用签名仍然有效，等价于「这三个信号未知」。 */
    public static void record(String api, String model, String provider, long ms,
                              boolean ok, int tokIn, int tokOut, String keyLabel,
                              int outChars, boolean toolCall, String stopReason) {
        record(api, model, provider, ms, ok, tokIn, tokOut, keyLabel,
                outChars, toolCall, stopReason, Obs.EMPTY);
    }

    /**
     * 完整版本（4.33.0）：再带上「这次请求的观测上下文」。
     *
     * 为什么要加这四组数据（这是想弄清"模型的工具调用逻辑"必须补的观测缺口）：
     *
     *   来源 source    —— 这条是真实用户流量、探活、还是检测？以前全靠"渠道名为空"
     *                     猜，今天 22 条全是 2~22ms 的本地即拒探活，却和真实失败混在一起。
     *   工具请求 tools —— 这次请求带没带工具、带几个。没有它就无法区分
     *                     "模型不想调工具"和"我们根本没给它工具"。
     *   响应形态 shape —— 结构化 tool_calls / 正文里的文本标记 / 空 / 报错。
     *                     这三者在旧数据里全都表现为"没调工具"。
     *   解析结果 parse —— 我们自己有没有接住（ToolFix 修复、ToolCallText 提取）。
     *                     解析失败和模型没调是两回事，混在一起永远查不清。
     */
    public static void record(String api, String model, String provider, long ms,
                              boolean ok, int tokIn, int tokOut, String keyLabel,
                              int outChars, boolean toolCall, String stopReason,
                              Obs obs) {
        if (!isInference(api)) return;
        Obs o = obs == null ? Obs.EMPTY : obs;
        String line = (System.currentTimeMillis() / 1000) + "\t" + clean(api) + "\t" + clean(model)
                + "\t" + clean(provider) + "\t" + ms + "\t" + (ok ? 1 : 0)
                + "\t" + Math.max(0, tokIn) + "\t" + Math.max(0, tokOut)
                + "\t" + clean(keyLabel)
                + "\t" + Math.max(0, outChars)
                + "\t" + (toolCall ? 1 : 0)
                + "\t" + clean(stopReason)
                /* 第 13 列起：观测上下文（4.33.0） */
                + "\t" + clean(o.source)
                + "\t" + o.tools
                + "\t" + clean(o.shape)
                + "\t" + clean(o.parse)
                + "\t" + (o.hasToolHistory ? 1 : 0)
                + "\t" + clean(o.quality)
                + "\t" + clean(o.responseId)
                + "\t" + clean(o.conversationId)
                + "\t" + clean(o.errorKind)
                + "\t" + clean(o.toolNames)
                /* 第 23 列：命中前缀缓存的输入 token（4.51.7）。
                   追加在**末尾**是刻意的：所有读取端都按 `c.length` 判断列数、
                   缺列当 0/空处理（见文件头的说明），所以旧行不会因为"没有这一列"
                   被读成失败或零值。插在中间会打乱 13~22 列的全部索引。 */
                + "\t" + Math.max(0, o.cached);
        synchronized (LOCK) {
            pending.add(line);
            if (pending.size() >= 20 || System.currentTimeMillis() - lastWrite > 60000) {
                writeLocked(pending.size() >= 20);
            }
        }
    }

    private static String clean(String s) {
        if (s == null) return "";
        return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }

    /** 攒批落盘；force=false 时保持静默（没到条件就不写）。 */
    private static void writeLocked(boolean force) {
        if (pending.isEmpty()) return;
        android.content.Context ctx = Gateway.context();
        if (ctx == null) return;
        try {
            File f = new File(ctx.getFilesDir(), FILE);
            FileOutputStream out = new FileOutputStream(f, true);
            try {
                for (String l : pending) out.write((l + "\n").getBytes("UTF-8"));
            } finally {
                out.close();
            }
            lastWrite = System.currentTimeMillis();
            pending.clear();
        } catch (Throwable t) {
            // 写盘失败以前是直接 clear()：这一批用量就**永久丢了**（用户看到的统计会缺一块）。
            // 现在保留在缓冲里、下次一起写；只保留最近 500 行，避免磁盘长期故障时把内存撑起来。
            Log.w("用量写入失败（保留待重试，缓冲 " + pending.size() + " 行）：" + t);
            while (pending.size() > 500) pending.remove(0);
        }
        if (force && System.currentTimeMillis() - lastTrim > 6 * 3600 * 1000L) trim(ctx);
    }

    /** 30 天前的行删掉（攒批间隙做，不占请求路径）。 */
    private static void trim(android.content.Context ctx) {
        lastTrim = System.currentTimeMillis();
        try {
            File f = new File(ctx.getFilesDir(), FILE);
            if (!f.isFile()) return;
            long cutoff = (System.currentTimeMillis() - KEEP_MS) / 1000;
            List<String> keep = new ArrayList<>();
            BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    int tab = line.indexOf('\t');
                    if (tab <= 0) continue;
                    try {
                        if (Long.parseLong(line.substring(0, tab)) >= cutoff) keep.add(line);
                    } catch (Throwable ignored) {
                    }
                }
            } finally {
                in.close();
            }
            FileOutputStream out = new FileOutputStream(f, false);
            try {
                for (String l : keep) out.write((l + "\n").getBytes("UTF-8"));
            } finally {
                out.close();
            }
            Log.i("用量：裁剪到 " + keep.size() + " 行（30 天窗口）");
        } catch (Throwable t) {
            Log.w("用量裁剪失败：" + t);
        }
    }

    /** 一组统计。 */
    public static final class Stat {
        public long requests, ok, ms, tokIn, tokOut;
        /** 其中命中前缀缓存的输入 token（4.51.7）。旧数据没有这一列，读出来是 0。 */
        public long tokCached;
        /**
         * **只统计带缓存列的那些行**的输入 token（4.51.7），用来给命中率做分母。
         *
         * <p>为什么要单独攒一份：升级之后 usage.tsv 里会**长期混着**两种行 ——
         * 4.51.7 之前写的行根本没有第 23 列（读出来 cached=0），之后写的才有。
         * 直接拿 {@link #tokIn} 当分母，会把那批"没有这个信息"的历史行全算成
         * "输入了但没命中"，于是命中率被系统性压低（实测：升级当天 175M 历史 token
         * 对几百个新 token，显示出来就是 0%）。
         *
         * <p>这正是 {@code Stats} 处理内容信号时踩过并写明的那条：
         * "旧行不能算进分母，否则会低估"。这里沿用同一条纪律。
         */
        public long tokInWithCacheInfo;

        public double avgMs() {
            return requests == 0 ? 0 : (double) ms / requests;
        }

        /**
         * 缓存命中率（命中 token / 输入 token）；没有输入 token 时返回 -1。
         *
         * <p>返回 -1 而不是 0 是刻意的：没有输入 token 的样本（旧数据、上游不报 usage）
         * 和"有输入但一次都没命中"是两件事，混成 0 会让界面显示一个假的"命中率 0%"。
         */
        public double cacheHitRate() {
            return tokInWithCacheInfo <= 0 ? -1 : (double) tokCached / tokInWithCacheInfo;
        }
    }

    public static final class Summary {
        public final Stat total = new Stat();
        /** 按客户端 Key 分组：多设备/多客户端时看谁用了多少。 */
        public final Map<String, Stat> byKey = new LinkedHashMap<>();
        /** 估算成本（美元）；-1 = 价格表里查不到，别显示 0 假装免费。 */
        public double costUsd = -1;
        public final Map<String, Stat> byApi = new LinkedHashMap<>();
        public final Map<String, Stat> byModel = new LinkedHashMap<>();
        public final Map<String, Stat> byProvider = new LinkedHashMap<>();
        public final Map<String, Stat> byDay = new LinkedHashMap<>();
    }

    /** 读最近 days 天的用量。只在 UI 打开时调用，所以直接扫文件。 */
    public static Summary summary(int days) {
        Summary s = new Summary();
        android.content.Context ctx = Gateway.context();
        if (ctx == null) return s;
        synchronized (LOCK) {
            writeLocked(true);
        }
        long cutoff = (System.currentTimeMillis() - (long) days * 24 * 3600 * 1000) / 1000;
        try {
            File f = new File(ctx.getFilesDir(), FILE);
            if (!f.isFile()) return s;
            java.text.SimpleDateFormat day = new java.text.SimpleDateFormat("MM-dd", java.util.Locale.US);
            BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    String[] c = line.split("\t");
                    if (c.length < 8) continue;
                    long ts;
                    try {
                        ts = Long.parseLong(c[0]);
                    } catch (Throwable ignored) {
                        continue;
                    }
                    if (ts < cutoff) continue;
                    int ms = parseInt(c[4]);
                    boolean ok = "1".equals(c[5]);
                    int tokIn = parseInt(c[6]);
                    int tokOut = parseInt(c[7]);
                    /* 第 23 列（4.51.7）：命中前缀缓存的输入 token。
                       旧行没有这一列，按长度判断当 0 处理——不能因为"没有新列"就当成
                       "没命中"，那会把历史全算成 0% 命中率。 */
                    boolean hasCacheInfo = c.length >= 23;
                    int tokCached = hasCacheInfo ? parseInt(c[22]) : 0;
                    add(s.total, ms, ok, tokIn, tokOut, tokCached, hasCacheInfo);
                    add(s.byApi, c[1], ms, ok, tokIn, tokOut, tokCached, hasCacheInfo);
                    if (!c[2].isEmpty()) {
                        add(s.byModel, c[2], ms, ok, tokIn, tokOut, tokCached, hasCacheInfo);
                    }
                    if (!c[3].isEmpty()) {
                        add(s.byProvider, c[3], ms, ok, tokIn, tokOut, tokCached, hasCacheInfo);
                    }
                    if (c.length >= 9 && !c[8].isEmpty()) {
                        add(s.byKey, c[8], ms, ok, tokIn, tokOut, tokCached, hasCacheInfo);
                    }
                    add(s.byDay, day.format(new java.util.Date(ts * 1000L)), ms, ok,
                            tokIn, tokOut, tokCached, hasCacheInfo);
                }
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            Log.w("用量读取失败：" + t);
        }
        // 估算成本：按模型维度累加（token 已经按模型聚合好了）
        //
        // 注意这里是**唯一能算成本的地方**：价格是"按模型"定的，而 byApi / byProvider /
        // byDay / byKey 这些分组里的 token 已经混了多个模型，事后拿分组名去查价格表
        // 必然查不到（"按渠道 +8 次"该用什么单价？）。所以每个分组要单独按模型拆一遍。
        s.costUsd = costOf(s.byModel);
        return s;
    }

    /**
     * 一组统计里的估算成本（美元）；-1 = 全都查不到价格，别显示 0 假装免费。
     *
     * 传入的 map 必须**按模型分组**才有意义 —— 见上面 summary() 里的说明。
     * 单独抽出来是为了让调用方一眼看出这条约束，而不是自己 foreach 一遍 token 数。
     */
    public static double costOf(Map<String, Stat> byModel) {
        if (byModel == null || byModel.isEmpty()) return -1;
        double cost = 0;
        boolean any = false;
        for (Map.Entry<String, Stat> e : byModel.entrySet()) {
            double c = Prices.costUsd(e.getKey(), e.getValue().tokIn,
                    e.getValue().tokCached, e.getValue().tokOut);
            if (c >= 0) {
                cost += c;
                any = true;
            }
        }
        return any ? cost : -1;
    }

    /**
     * 逐日成本（给图表/列表用），只返回价格表查得到的那些天。
     *
     * 实现上只能按「天 × 模型」拆：byDay 里的 token 混了模型，所以这里重新扫一遍
     * 原始行。之所以不去 cache 一份二维表，是因为这个方法只在用量弹窗打开时调一次，
     * 一次扫文件比维护缓存一致性的代价低。
     *
     * 返回按天（MM-dd）升序，缺失的天不补 0 —— 调用方通常要的是"花了钱的那几天"，
     * 补 0 会让图表里出现一排没有意义的空柱。
     */
    public static java.util.LinkedHashMap<String, Double> dailyCost(int days) {
        java.util.LinkedHashMap<String, Double> out = new java.util.LinkedHashMap<>();
        android.content.Context ctx = Gateway.context();
        if (ctx == null) return out;
        synchronized (LOCK) {
            writeLocked(true);
        }
        long cutoff = (System.currentTimeMillis() - (long) days * 24 * 3600 * 1000) / 1000;
        try {
            File f = new File(ctx.getFilesDir(), FILE);
            if (!f.isFile()) return out;
            java.text.SimpleDateFormat day = new java.text.SimpleDateFormat("MM-dd", java.util.Locale.US);
            /* 天 → (模型 → [tokIn, tokOut])，临时聚合完就丢，不常驻 */
            java.util.LinkedHashMap<String, java.util.LinkedHashMap<String, long[]>> acc =
                    new java.util.LinkedHashMap<>();
            BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    String[] c = line.split("\t", -1);
                    if (c.length < 8) continue;
                    long ts;
                    try {
                        ts = Long.parseLong(c[0]);
                    } catch (Exception bad) {
                        continue;
                    }
                    if (ts < cutoff) continue;
                    String model = c[2];
                    if (model == null || model.isEmpty()) continue;
                    long tokIn = parseLong(c[6]), tokOut = parseLong(c[7]);
                    long tokCached = c.length >= 23 ? parseLong(c[22]) : 0;
                    if (tokIn + tokOut <= 0) continue;
                    String key = day.format(new java.util.Date(ts * 1000L));
                    java.util.LinkedHashMap<String, long[]> byModel = acc.get(key);
                    if (byModel == null) {
                        byModel = new java.util.LinkedHashMap<>();
                        acc.put(key, byModel);
                    }
                    long[] pair = byModel.get(model);
                    if (pair == null) {
                        pair = new long[3];        // [输入, 输出, 命中缓存]
                        byModel.put(model, pair);
                    }
                    pair[0] += tokIn;
                    pair[1] += tokOut;
                    pair[2] += tokCached;
                }
            } finally {
                in.close();
            }
            for (Map.Entry<String, java.util.LinkedHashMap<String, long[]>> e : acc.entrySet()) {
                double cost = 0;
                boolean any = false;
                for (Map.Entry<String, long[]> m : e.getValue().entrySet()) {
                    double c = Prices.costUsd(m.getKey(), m.getValue()[0],
                            m.getValue()[2], m.getValue()[1]);
                    if (c >= 0) {
                        cost += c;
                        any = true;
                    }
                }
                if (any) out.put(e.getKey(), cost);
            }
        } catch (Throwable t) {
            Log.w("逐日成本统计失败：" + t);
        }
        return out;
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return 0L;
        }
    }

    /** 近 days 天逐日的请求数（含没有请求的那几天，按 0 补齐），给柱状图用。 */
    public static java.util.LinkedHashMap<String, Long> daily(int days) {
        Summary s = summary(days);
        java.util.LinkedHashMap<String, Long> out = new java.util.LinkedHashMap<>();
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("MM-dd", java.util.Locale.US);
        long now = System.currentTimeMillis();
        for (int i = days - 1; i >= 0; i--) {
            String key = fmt.format(new java.util.Date(now - (long) i * 24 * 3600 * 1000));
            out.put(key, 0L);
        }
        for (java.util.Map.Entry<String, Stat> e : s.byDay.entrySet()) {
            if (out.containsKey(e.getKey())) out.put(e.getKey(), e.getValue().requests);
        }
        return out;
    }

    /** 最慢的 n 次请求（近 days 天），一行一条，给排障看。 */
    public static java.util.List<String> slowest(int days, int n) {
        java.util.List<String> out = new java.util.ArrayList<>();
        android.content.Context ctx = Gateway.context();
        if (ctx == null) return out;
        synchronized (LOCK) {
            writeLocked(true);
        }
        long cutoff = (System.currentTimeMillis() - (long) days * 24 * 3600 * 1000) / 1000;
        java.util.List<long[]> ms = new java.util.ArrayList<>();
        java.util.List<String> rows = new java.util.ArrayList<>();
        try {
            java.io.File f = new java.io.File(ctx.getFilesDir(), FILE);
            if (!f.isFile()) return out;
            BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    String[] c = line.split("\t");
                    if (c.length < 8) continue;
                    long ts;
                    try {
                        ts = Long.parseLong(c[0]);
                    } catch (Throwable ignored) {
                        continue;
                    }
                    if (ts < cutoff) continue;
                    int took = parseInt(c[4]);
                    ms.add(new long[]{took, ms.size()});
                    rows.add(line);
                }
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            Log.w("用量读取失败：" + t);
            return out;
        }
        ms.sort((a, b) -> Long.compare(b[0], a[0]));
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US);
        for (int i = 0; i < ms.size() && out.size() < n; i++) {
            String line = rows.get((int) ms.get(i)[1]);
            String[] c = line.split("\t");
            StringBuilder sb = new StringBuilder();
            sb.append(fmt.format(new java.util.Date(Long.parseLong(c[0]) * 1000L))).append(" · ");
            sb.append(c[2].isEmpty() ? c[1] : c[2]).append(" · ");
            sb.append(c[3].isEmpty() ? "-" : c[3]).append(" · ");
            sb.append(ms.get(i)[0]).append("ms");
            if ("0".equals(c[5])) sb.append(" · 失败");
            out.add(sb.toString());
        }
        return out;
    }

    /** 一行摘要，给 UI 与日志用。 */
    public static String line(int days) {
        Summary s = summary(days);
        Stat t = s.total;
        if (t.requests == 0) return "暂无用量记录";
        StringBuilder sb = new StringBuilder();
        sb.append(t.requests).append(" 次");
        if (t.ok < t.requests) sb.append("（失败 ").append(t.requests - t.ok).append("）");
        sb.append(" · 平均 ").append(Math.round(t.avgMs())).append("ms");
        if (t.tokIn + t.tokOut > 0) {
            sb.append(" · ").append(fmt(t.tokIn)).append("/").append(fmt(t.tokOut)).append(" tokens");
        }
        /* 缓存命中率：只在真的有命中时才显示。一次都没命中时不显示"缓存 0%"——
           那多半是"上游没报这个字段"，而不是"真的没命中"，显示出来会误导。 */
        double hit = t.cacheHitRate();
        if (hit > 0) sb.append(" · 缓存 ").append(Math.round(hit * 100)).append("%");
        if (s.costUsd >= 0) {
            sb.append(" · 成本 ≈$").append(usd(s.costUsd));
        }
        return sb.toString();
    }

    /** 美元金额，小额度多给几位小数（1e-4 与 1e-6 都要看得见）。 */
    public static String usd(double v) {
        if (v >= 1) return String.format(java.util.Locale.US, "%.2f", v);
        if (v >= 0.01) return String.format(java.util.Locale.US, "%.3f", v);
        return String.format(java.util.Locale.US, "%.5f", v);
    }

    private static String fmt(long n) {
        if (n >= 1000000) return (n / 1000000) + "M";
        if (n >= 1000) return (n / 1000) + "k";
        return String.valueOf(n);
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static void add(Map<String, Stat> map, String key, int ms, boolean ok,
                            int tokIn, int tokOut, int tokCached, boolean hasCacheInfo) {
        Stat st = map.get(key);
        if (st == null) {
            st = new Stat();
            map.put(key, st);
        }
        add(st, ms, ok, tokIn, tokOut, tokCached, hasCacheInfo);
    }

    private static void add(Stat st, int ms, boolean ok, int tokIn, int tokOut,
                            int tokCached, boolean hasCacheInfo) {
        st.requests++;
        if (ok) st.ok++;
        st.ms += ms;
        st.tokIn += tokIn;
        st.tokOut += tokOut;
        st.tokCached += tokCached;
        /* 只有"确实带缓存列"的行才进命中率的分母 */
        if (hasCacheInfo) st.tokInWithCacheInfo += tokIn;
    }
}
