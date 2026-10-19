package com.cpa.gateway.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按渠道/模型汇总**行为画像**（4.35.1）。
 *
 * 和 {@link Usage#summary} 的区别：那个算"用了多少、多快、多少钱"，
 * 这个算"靠不靠得住、工具用不用得起来"。两块分开，
 * 免得为了加画像去动已经稳定的统计逻辑。
 *
 * 数据源就是 usage.tsv 本身——不需要额外发请求，
 * 日常流量自然会把这些格子填满。
 */
public final class QualityStats {

    private QualityStats() {
    }

    /** 一个渠道或模型的画像。 */
    public static final class Row {
        String key = "";
        int total;          // 总请求数
        int ok;             // 可用
        int empty;          // 空回复
        int cut;            // 被截断
        int off;            // 答非所问
        int retry;          // 用户立刻重发
        int withTools;      // 带工具定义的请求数
        int nativeCall;     // 真吐了结构化调用
        int textCall;       // 把调用写在正文里（我们没接住）
        int hasHistory;     // 多轮工具历史

        /** 可用率：ok / 有质量标记的总数。没有标记的旧行不算分母。 */
        double usableRate() {
            int n = ok + empty + cut + off + retry;
            return n == 0 ? -1 : (double) ok / n;
        }

        /** 给了工具却不出调用的比例——这是"工具能力"最直接的口径。 */
        double toolFailRate() {
            if (withTools == 0) return -1;
            return (double) (withTools - nativeCall) / withTools;
        }
    }

    /** 按渠道（byProvider=true）或按模型聚合。days<=0 表示不限时间。 */
    public static Map<String, Row> aggregate(boolean byProvider, int days) {
        Map<String, Row> out = new LinkedHashMap<>();
        android.content.Context ctx = Gateway.context();
        if (ctx == null) return out;
        File f = new File(ctx.getFilesDir(), "usage.tsv");
        if (!f.isFile()) return out;

        long cutoff = days > 0
                ? (System.currentTimeMillis() - (long) days * 24 * 3600 * 1000) / 1000
                : 0L;

        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    String[] c = line.split("\t", -1);
                    /* 只统计有质量标记的行（第 18 列）。旧行没有这一列，
                       混进来会把"没记录"算成"不可用"。 */
                    if (c.length < 18) continue;
                    long ts;
                    try {
                        ts = Long.parseLong(c[0]);
                    } catch (Throwable bad) {
                        continue;
                    }
                    if (cutoff > 0 && ts < cutoff) continue;

                    String key = byProvider ? c[3].trim() : c[2].trim();
                    if (key.isEmpty()) continue;

                    String quality = c[17].trim();
                    if (quality.isEmpty()) continue;

                    Row r = out.get(key);
                    if (r == null) {
                        r = new Row();
                        r.key = key;
                        out.put(key, r);
                    }
                    r.total++;
                    if ("ok".equals(quality)) r.ok++;
                    else if ("empty".equals(quality)) r.empty++;
                    else if ("cut".equals(quality)) r.cut++;
                    else if ("off".equals(quality)) r.off++;
                    else if ("retry".equals(quality)) r.retry++;

                    int tools = parseInt(c[13]);
                    if (tools > 0) {
                        r.withTools++;
                        if ("native".equals(c[14].trim())) r.nativeCall++;
                        else if ("text".equals(c[14].trim())) r.textCall++;
                    }
                    if ("1".equals(c[16].trim())) r.hasHistory++;
                }
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            Log.w("行为画像统计失败：" + t);
        }
        return out;
    }

    /** 排成一屏可读的文本。 */
    public static String report(int days) {
        Map<String, Row> byP = aggregate(true, days);
        if (byP.isEmpty()) {
            return "还没有带质量标记的样本。\n\n"
                    + "从 4.35.0 起每条请求都会记一个质量档（ok/empty/cut/off/retry），\n"
                    + "正常用一会儿这里就有数据了。";
        }
        List<Row> rows = new ArrayList<>(byP.values());
        rows.sort((a, b) -> b.total - a.total);

        StringBuilder sb = new StringBuilder();
        sb.append("按渠道（").append(days > 0 ? "最近 " + days + " 天" : "全部").append("）\n");
        sb.append("渠道          样本  可用率  空回  截断  跑题  重发  工具失败\n");
        for (Row r : rows) {
            sb.append(pad(r.key, 14))
              .append(pad(String.valueOf(r.total), 6))
              .append(pad(pct(r.usableRate()), 8))
              .append(pad(String.valueOf(r.empty), 6))
              .append(pad(String.valueOf(r.cut), 6))
              .append(pad(String.valueOf(r.off), 6))
              .append(pad(String.valueOf(r.retry), 6))
              .append(pct(r.toolFailRate()))
              .append('\n');
        }

        Map<String, Row> byM = aggregate(false, days);
        List<Row> mrows = new ArrayList<>(byM.values());
        mrows.sort((a, b) -> b.total - a.total);
        if (!mrows.isEmpty()) {
            sb.append("\n按模型\n");
            for (int i = 0; i < mrows.size() && i < 12; i++) {
                Row r = mrows.get(i);
                sb.append(pad(r.key, 26))
                  .append(pad(String.valueOf(r.total), 6))
                  .append(pad(pct(r.usableRate()), 8))
                  .append("工具失败 ").append(pct(r.toolFailRate()))
                  .append('\n');
            }
        }
        sb.append("\n说明：可用率 = ok / 有质量标记的样本；工具失败 = 给了工具但没吐结构化调用。");
        return sb.toString();
    }

    private static String pct(double v) {
        if (v < 0) return "—";
        return Math.round(v * 100) + "%";
    }

    private static String pad(String s, int n) {
        StringBuilder sb = new StringBuilder(s == null ? "" : s);
        while (sb.length() < n) sb.append(' ');
        return sb.toString();
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return 0;
        }
    }
}
