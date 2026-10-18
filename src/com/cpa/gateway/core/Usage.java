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

    /** 一行：时间戳(秒) \t 接口 \t 模型 \t 渠道 \t 耗时ms \t 成功(1/0) \t 输入tok \t 输出tok \t Key标签 */
    private static final String FILE = "usage.tsv";
    private static final long KEEP_MS = 30L * 24 * 3600 * 1000;

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
        if (!isInference(api)) return;
        String line = (System.currentTimeMillis() / 1000) + "\t" + clean(api) + "\t" + clean(model)
                + "\t" + clean(provider) + "\t" + ms + "\t" + (ok ? 1 : 0)
                + "\t" + Math.max(0, tokIn) + "\t" + Math.max(0, tokOut)
                + "\t" + clean(keyLabel);
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

        public double avgMs() {
            return requests == 0 ? 0 : (double) ms / requests;
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
                    add(s.total, ms, ok, tokIn, tokOut);
                    add(s.byApi, c[1], ms, ok, tokIn, tokOut);
                    if (!c[2].isEmpty()) add(s.byModel, c[2], ms, ok, tokIn, tokOut);
                    if (!c[3].isEmpty()) add(s.byProvider, c[3], ms, ok, tokIn, tokOut);
                    if (c.length >= 9 && !c[8].isEmpty()) add(s.byKey, c[8], ms, ok, tokIn, tokOut);
                    add(s.byDay, day.format(new java.util.Date(ts * 1000L)), ms, ok, tokIn, tokOut);
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
            double c = Prices.costUsd(e.getKey(), e.getValue().tokIn, e.getValue().tokOut);
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
                    if (tokIn + tokOut <= 0) continue;
                    String key = day.format(new java.util.Date(ts * 1000L));
                    java.util.LinkedHashMap<String, long[]> byModel = acc.get(key);
                    if (byModel == null) {
                        byModel = new java.util.LinkedHashMap<>();
                        acc.put(key, byModel);
                    }
                    long[] pair = byModel.get(model);
                    if (pair == null) {
                        pair = new long[2];
                        byModel.put(model, pair);
                    }
                    pair[0] += tokIn;
                    pair[1] += tokOut;
                }
            } finally {
                in.close();
            }
            for (Map.Entry<String, java.util.LinkedHashMap<String, long[]>> e : acc.entrySet()) {
                double cost = 0;
                boolean any = false;
                for (Map.Entry<String, long[]> m : e.getValue().entrySet()) {
                    double c = Prices.costUsd(m.getKey(), m.getValue()[0], m.getValue()[1]);
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

    private static void add(Map<String, Stat> map, String key, int ms, boolean ok, int tokIn, int tokOut) {
        Stat st = map.get(key);
        if (st == null) {
            st = new Stat();
            map.put(key, st);
        }
        add(st, ms, ok, tokIn, tokOut);
    }

    private static void add(Stat st, int ms, boolean ok, int tokIn, int tokOut) {
        st.requests++;
        if (ok) st.ok++;
        st.ms += ms;
        st.tokIn += tokIn;
        st.tokOut += tokOut;
    }
}
