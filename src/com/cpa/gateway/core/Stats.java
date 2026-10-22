package com.cpa.gateway.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 历史表现样本（选路自适应用）。
 *
 * 为什么要有这个类：{@link Router} 的 score **只看得见本次进程内的 Stat**。
 * 后果是每次重启网关、每次升级 APK，所有账号的成功率、延迟、成本估计全部归零，
 * 又从"先验分"重新学起 —— 一个刚从早高峰限流里恢复的账号和一个一直稳的账号，
 * 重启后完全一样分。这和"网关照记忆选路"的初衷正好相反。
 *
 * 这里做的事：启动时读一次 usage.tsv（它本来就记了 时间/接口/模型/渠道/耗时ms/成功/in/out/Key），
 * 按 `渠道/模型` 聚合成衰减加权种子，喂给 Router。**只读、不改 usage.tsv**。
 *
 * 聚合用**时间衰减加权**而不是简单平均，理由：
 *   · usage.tsv 留 30 天，30 天前的账号状态（那时可能还在免费额度期、或还没被限流）
 *     对"现在该选谁"没有信息量，简单平均会把历史包袱平均进来；
 *   · 越近越重，且老样本不清零（见 {@link #HALF_LIFE_S} 里记的那次实测）。
 */
public final class Stats {

    private Stats() {
    }

    /**
     * 时间衰减的半衰期：3 天前的样本权重减半。
     *
     * 为什么**不用**逐笔 EWMA（这是实测踩出来的）：
     * 最初写成 `e = e + 0.35*(x-e)`，在真机数据上算出
     * "WorkBuddy 1 / deepseek-v4.1-flash 有 2134 次成功 + 就 1 次失败 → 成功率 0.650"。
     * 原因不是系数太大，而是**样本数本身**：EWMA 的收敛半衰期是 ln2/ALPHA ≈ 1.6 笔，
     * 而 usage.tsv 里单个组合能攒到 2000 多笔，于是估计值永远只反映最后两笔，
     * 实际上忽略了全部历史。样本越多的组合被坑得越狠 —— 正好和常识相反。
     *
     * 时间衰减没有这个问题：一个组合无论有 5 笔还是 2000 笔，权重都按"距今多久"算，
     * 样本多只会让估计更稳，不会让估计变成"只记住最后两笔"。
     * 真机复算：上面那条从 0.650 修正到 0.999，而 gpt-6-sol（10 成功 / 54 失败，
     * 全部集中在 10-15 那天）仍然是 0.156 —— 该沉的照沉。
     */
    private static final double HALF_LIFE_S = 3.0 * 24 * 3600;

    /** 只看最近 14 天：更早的样本对"现在选谁"意义不大，还占解析时间。 */
    private static final long WINDOW_MS = 14L * 24 * 3600 * 1000;

    /** 至少要几笔样本才拿来初始化（1 笔的估计噪声比先验还大）。 */
    private static final int MIN_N = 2;

    /** 按 key（渠道/模型）聚合出的种子。 */
    public static final class Seed {
        public double ok;      // 衰减加权成功率 0..1
        public double ms;      // 衰减加权耗时（只算成功样本）
        public double cost;    // 衰减加权单次成本（美元），未知为 0
        public double w;       // 累计权重（≈"有效样本数"），用来判断上面三个估计有多可信
        public int n;          // 成功样本数

        /*
         * 下面三个是 4.32.0 新增的**内容侧**观察值，只记录、暂不参与打分。
         *
         * 为什么不直接进 score()：score() 的权重是经过多轮真机调过的，
         * 在没有对照数据之前改权重，等于用一个未验证的信号去干扰一个已验证的排序。
         * 先攒够样本，再决定要不要让它影响选路 —— 这也符合"先只读、看清了再做"的顺序。
         *
         * 注意 wContent 是"带内容信号的行数"：4.32.0 之前写的行没有这三列，
         * 它们的 outChars/toolCall 读出来是 0/false，不能算进分母，否则会低估。
         */
        public double contentRate;   // 有效内容率：outChars>0 的占比（只统计有该信号的样本）
        public double toolRate;      // 工具调用率：真吐了结构化 tool_calls 的占比
        public double truncRate;     // 截断率：stopReason=length 的占比
        public double wContent;      // 上面三个估计的有效样本权重
    }

    private static volatile Map<String, Seed> TABLE = Collections.emptyMap();
    private static volatile boolean loading;
    private static volatile boolean loaded;

    /** 已加载的 key 数（自检/日志用）。 */
    public static int size() {
        return TABLE.size();
    }

    public static boolean ready() {
        return loaded;
    }

    public static Seed get(String provider, String model) {
        if (provider == null || model == null) return null;
        return TABLE.get(provider + "/" + model);
    }

    /** 后台加载一次；重复调用安全。 */
    public static void ensureLoaded() {
        if (loaded || loading) return;
        synchronized (Stats.class) {
            if (loaded || loading) return;
            loading = true;
        }
        Threads.io(new Runnable() {
            @Override
            public void run() {
                try {
                    final android.content.Context ctx = Gateway.context();
                    if (ctx == null) return;
                    Map<String, Seed> out = load(ctx);
                    if (out.isEmpty()) {
                        /* 空表也标 loaded：没历史数据是正常状态，不该每次选路都重扫一遍文件 */
                        Log.i("历史选路样本：usage.tsv 里没有可用样本，本次沿用运行时统计");
                    } else {
                        Log.i("历史选路样本已加载：" + out.size()
                                + " 个 渠道/模型 组合（来自 usage.tsv 最近 14 天）");
                    }
                    TABLE = out;
                    loaded = true;
                } catch (Throwable t) {
                    Log.w("历史选路样本加载失败（本次退回纯运行时统计）：" + t);
                } finally {
                    synchronized (Stats.class) {
                        loading = false;
                    }
                }
            }
        });
    }

    /** 同步重载（自检/清理用量后调用）。 */
    public static void reload() {
        synchronized (Stats.class) {
            loaded = false;
            loading = false;
        }
        TABLE = Collections.emptyMap();
        ensureLoaded();
    }

    private static Map<String, Seed> load(android.content.Context ctx) throws Exception {
        File f = new File(ctx.getFilesDir(), "usage.tsv");
        if (!f.isFile()) return Collections.emptyMap();
        long cutoff = (System.currentTimeMillis() - WINDOW_MS) / 1000L;

        /*
         * 每个 key 先攒点，再统一按时间算权重。
         * 攒点而不是边读边加权：usage.tsv 是攒批追加的，多线程并发时写入顺序**不严格**，
         * 而且衰减权重的参照点要取"这批里最新的一笔"，得先看全再算。
         */
        Map<String, List<Point>> acc = new HashMap<>();
        BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
        try {
            String line;
            while ((line = in.readLine()) != null) {
                String[] c = line.split("\t", -1);
                if (c.length < 7) continue;
                long ts;
                try {
                    ts = Long.parseLong(c[0]);
                } catch (Throwable bad) {
                    continue;
                }
                if (ts < cutoff) continue;
                String model = c[2] == null ? "" : c[2].trim();
                String provider = c[3] == null ? "" : c[3].trim();
                if (model.isEmpty() || provider.isEmpty()) continue;
                if (Router.nonChat(model)) continue;

                long ms = parseLong(c[4]);
                boolean ok = "1".equals(c[5]);
                long tokIn = parseLong(c[6]);
                long tokOut = c.length > 7 ? parseLong(c[7]) : 0;

                String key = provider + "/" + model;
                List<Point> list = acc.get(key);
                if (list == null) {
                    list = new ArrayList<>();
                    acc.put(key, list);
                }
                /* 新三列（4.32.0）：输出字符数 / 工具调用 / 结束原因。
                   旧行只有 9 列，这里 hasContent=false，聚合时会跳过它们的分母。 */
                boolean hasContent = c.length >= 12;
                int outChars = hasContent ? (int) parseLong(c[9]) : 0;
                boolean toolCall = hasContent && "1".equals(c[10]);
                String stopReason = hasContent && c[11] != null ? c[11] : "";
                long tokCached = c.length >= 23 ? parseLong(c[22]) : 0;
                list.add(new Point(ts, ok, ms, tokIn, tokOut, tokCached,
                        hasContent, outChars, toolCall, stopReason));
            }
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }

        if (acc.isEmpty()) return Collections.emptyMap();

        Map<String, Seed> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<Point>> e : acc.entrySet()) {
            List<Point> pts = e.getValue();
            if (pts.size() < MIN_N) continue;
            Collections.sort(pts, (x, y) -> Long.compare(x.ts, y.ts));

            String model = modelOf(e.getKey());
            /*
             * 时间衰减加权。权重按"距今多久"算，与样本条数无关 —— 所以 5 笔的组合
             * 和 2000 笔的组合都能得到稳定估计（见 HALF_LIFE_S 的注释）。
             * 参照点取**这批样本里最新的那一笔**，不是 now：一个组合如果最后一次
             * 调用是 10 天前，它的内部权重结构不该因为我们"今天没调它"而被压平。
             */
            long newest = pts.get(pts.size() - 1).ts;
            double wOk = 0, vOk = 0;
            double wMs = 0, vMs = 0;
            double wCost = 0, vCost = 0;
            int nOk = 0;
            for (Point p : pts) {
                double age = newest - p.ts;
                double w = Math.pow(0.5, age / HALF_LIFE_S);
                /* 下限：别让很老的样本权重掉到 0（否则一个"只在 20 天前用过一次"的组合
                   会退化成无数据），也不能掉到 1e-12 那种精度幻觉里 */
                if (w < 1e-4) w = 1e-4;

                double x = p.ok ? 1.0 : 0.0;
                wOk += w;
                vOk += w * x;

                /* 延迟只统计成功的：失败请求的耗时是"报错前的等待"，不是这个账号的正常速度 */
                if (p.ok && p.ms > 0) {
                    wMs += w;
                    vMs += w * p.ms;
                }
                /* 成本种子也要按缓存价算：选路里的"成本"项若用全价，
                   一个缓存命中率高的组合会被算得更贵，反而被压低排序。 */
                double cost = Prices.costUsd(model, p.tokIn, p.tokCached, p.tokOut);
                if (cost >= 0) {
                    wCost += w;
                    vCost += w * cost;
                }
                if (p.ok) nOk++;
            }
            Seed s = new Seed();
            s.ok = wOk > 0 ? vOk / wOk : 0.5;
            s.ms = wMs > 0 ? vMs / wMs : 0;
            s.cost = wCost > 0 ? vCost / wCost : 0;
            s.w = wOk;
            s.n = nOk;
            /* 内容侧三个比率：只用带新列的行做分母。
               混进旧行会把"没记录"算成"没内容"，凭空制造出一堆 0。 */
            double cw = 0, cNonEmpty = 0, cTool = 0, cTrunc = 0;
            for (Point p : pts) {
                if (!p.hasContent) continue;
                double age = newest - p.ts;
                double w = Math.pow(0.5, age / HALF_LIFE_S);
                if (w < 1e-4) w = 1e-4;
                cw += w;
                if (p.outChars > 0) cNonEmpty += w;
                if (p.toolCall) cTool += w;
                if ("length".equals(p.stopReason)) cTrunc += w;
            }
            s.wContent = cw;
            if (cw > 0) {
                s.contentRate = cNonEmpty / cw;
                s.toolRate = cTool / cw;
                s.truncRate = cTrunc / cw;
            }
            out.put(e.getKey(), s);
        }
        return out;
    }

    /** "provider/model" → model 部分（价格按模型查，不按渠道）。 */
    private static String modelOf(String key) {
        int i = key.indexOf('/');
        return i < 0 ? key : key.substring(i + 1);
    }

    private static long parseLong(String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    private static final class Point {
        final long ts;
        final boolean ok;
        final long ms;
        final long tokIn;
        final long tokOut;
        /** 命中前缀缓存的输入 token（第 23 列，4.51.7）；旧行没有这一列为 0。 */
        final long tokCached;
        /* 4.32.0 内容信号；hasContent=false 表示这行是旧格式（没有这三列） */
        final boolean hasContent;
        final int outChars;
        final boolean toolCall;
        final String stopReason;

        Point(long ts, boolean ok, long ms, long tokIn, long tokOut, long tokCached) {
            this(ts, ok, ms, tokIn, tokOut, tokCached, false, 0, false, "");
        }

        Point(long ts, boolean ok, long ms, long tokIn, long tokOut, long tokCached,
              boolean hasContent, int outChars, boolean toolCall, String stopReason) {
            this.ts = ts;
            this.ok = ok;
            this.ms = ms;
            this.tokIn = tokIn;
            this.tokOut = tokOut;
            this.tokCached = tokCached;
            this.hasContent = hasContent;
            this.outChars = outChars;
            this.toolCall = toolCall;
            this.stopReason = stopReason == null ? "" : stopReason;
        }
    }
}
