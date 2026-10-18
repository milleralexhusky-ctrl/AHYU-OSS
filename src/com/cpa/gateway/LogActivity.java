package com.cpa.gateway;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.cpa.gateway.core.Gateway;
import com.cpa.gateway.core.Log;

/** Live, filterable view of the gateway log, wearing the same glass as the rest of the app. */
public class LogActivity extends Activity {

    private TextView view;
    private ScrollView scroll;
    private EditText filter;
    private TextView usageLine;
    private int level;          // 0 全部 · 1 只看问题(WARN/ERROR) · 2 只看错误
    private final Handler handler = new Handler(Looper.getMainLooper());
    /** 自己的日志监听，销毁时只注销自己这一份。 */
    private Log.Listener logListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.darkSystemBars(this);
        level = getIntent() == null ? 0 : getIntent().getIntExtra("level", 0);

        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Color.TRANSPARENT);
        root.setPadding(Ui.dp(this, 16), Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 16));

        TextView back = Ui.barButton(this, "‹ 返回");
        back.setOnClickListener(v -> finish());
        LinearLayout header = Ui.appBar(this, back, "日志", null);
        TextView clear = Ui.barButton(this, "清空");
        clear.setOnClickListener(v -> {
            Log.clear();
            render();
        });
        header.addView(clear);
        TextView exportBtn = Ui.barButton(this, "导出");
        exportBtn.setOnClickListener(v -> exportLogSnapshot());
        header.addView(exportBtn);
        root.addView(header);

        // 用量：一行摘要常驻在这里，点"用量"看明细（之前这块完全空白）
        usageLine = Ui.label(this, com.cpa.gateway.core.Usage.line(1));
        usageLine.setSingleLine(true);
        usageLine.setEllipsize(android.text.TextUtils.TruncateAt.END);
        root.addView(usageLine, Ui.lp(this, 10));

        LinearLayout actions = Ui.row(this);
        actions.addView(Ui.label(this, "点右上角清空，输入关键词可过滤"), Ui.weight(this, 1f, 0));
        TextView usage = Ui.ghostButton(this, "用量");
        usage.setOnClickListener(v -> usageDialog());
        actions.addView(usage);
        TextView probe = Ui.ghostButton(this, "探活");
        probe.setOnClickListener(v -> {
            Log.i("运行中=" + Gateway.isRunning() + " 端口=" + Gateway.runningPort());
            render();
        });
        actions.addView(probe);
        root.addView(actions, Ui.lp(this, 12));

        // 分段选择器：按等级筛，比让用户自己想关键词输什么好用
        root.addView(Ui.segmented(this, new String[]{"全部", "问题", "错误"},
                level, idx -> {
                    // 选中态要活过 recreate，所以从 intent 带过去
                    getIntent().putExtra("level", idx);
                    recreate();
                }), Ui.lp(this, 12));

        filter = Ui.field(this, "过滤关键词，例如 workbuddy / error", "", false);
        filter.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                render();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        root.addView(filter, Ui.lp(this, 12));

        LinearLayout panel = Ui.column(this);
        panel.setBackground(GlassDrawable.panel(Ui.dp(this, 24)));
        panel.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));
        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        view = new TextView(this);
        /* 长按复制整段：想贴给别人看时比截图强，也比先导出文件快。
           复制的是"当前过滤条件筛出来的这些行"，和屏幕上看到的一致。 */
        view.setOnLongClickListener(v -> {
            try {
                CharSequence txt = view.getText();
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null && txt != null) {
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("AHYU 日志", txt));
                    android.widget.Toast.makeText(this, "已复制 " + txt.length() + " 字",
                            android.widget.Toast.LENGTH_SHORT).show();
                    return true;
                }
            } catch (Throwable e) {
                com.cpa.gateway.core.Log.w("复制日志失败：" + e);
            }
            return false;
        });
        view.setTextSize(11.5f);
        view.setTypeface(Typeface.MONOSPACE);
        scroll.addView(view);
        panel.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(panel, Ui.fill(this, 12));

        logListener = line -> handler.post(this::render);
        Log.addListener(logListener);
        // 底层强度 0.32 —— 日志：密集等宽文字，必须最淡
        setContentView(Ui.glassScaffold(this, root, 0.32f));
        render();
    }

    /**
     * 把当前日志（内存里那 500 行）连同用量摘要写成一个 txt，方便贴给别人看。
     *
     * 为什么要这个：`core/Log` 只是内存环形缓冲、进程一重启就没了，而排查
     * "刚才点检测发生了什么"往往要事后回看——今天就是这样，翻不出日志只能靠截图。
     */
    private void exportLogSnapshot() {
        com.cpa.gateway.core.Threads.io(() -> {
            String path = "";
            try {
                java.io.File dir = getExternalFilesDir(null) != null
                        ? new java.io.File(getExternalFilesDir(null), "logs")
                        : new java.io.File(getFilesDir(), "logs");
                if (!dir.exists()) dir.mkdirs();
                java.text.SimpleDateFormat f =
                        new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US);
                java.io.File target = new java.io.File(dir,
                        "AHYU-日志-" + com.cpa.gateway.core.Backup.versionTag() + "-"
                                + f.format(new java.util.Date()) + ".txt");
                StringBuilder sb = new StringBuilder();
                sb.append("# 版本 ").append(com.cpa.gateway.core.Backup.versionTag()).append("\n");
                sb.append("# 用量 ").append(com.cpa.gateway.core.Usage.line(1)).append("\n");
                sb.append("# 导出于 ").append(new java.util.Date()).append("\n\n");
                for (String line : Log.snapshot()) sb.append(line).append("\n");
                java.io.FileOutputStream out = new java.io.FileOutputStream(target);
                out.write(sb.toString().getBytes("UTF-8"));
                out.close();
                path = target.getAbsolutePath();
            } catch (Throwable e) {
                path = "";
                Log.w("导出日志失败：" + e);
            }
            final String p = path;
            runOnUiThread(() -> android.widget.Toast.makeText(this,
                    p.isEmpty() ? "导出失败（看日志里的 WARN）" : "已导出：\n" + p,
                    android.widget.Toast.LENGTH_LONG).show());
        });
    }

    @Override
    protected void onDestroy() {
        Log.removeListener(logListener);
        super.onDestroy();
    }

    private void render() {
        if (usageLine != null) usageLine.setText("今日 " + com.cpa.gateway.core.Usage.line(1));
        String q = filter == null ? "" : filter.getText().toString().trim().toLowerCase();
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        int shown = 0;
        for (String line : Log.snapshot()) {
            if (!q.isEmpty() && !line.toLowerCase().contains(q)) continue;
            if (level == 1 && !line.contains("[WARN]") && !line.contains("[ERROR]")) continue;
            if (level == 2 && !line.contains("[ERROR]")) continue;
            int start = sb.length();
            sb.append(line).append('\n');
            int color = Ui.MUTED;
            if (line.contains("[ERROR]")) color = Ui.RED;
            else if (line.contains("[WARN]")) color = Ui.ACCENT_2;
            else color = Ui.MUTED;
            sb.setSpan(new android.text.style.ForegroundColorSpan(color), start, sb.length(),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            shown++;
        }
        if (shown == 0) sb.append(q.isEmpty() ? "（还没有日志）" : "没有匹配「" + q + "」的日志");
        view.setText(sb);
        scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    /** 用量明细：近 7 天，按接口 / 渠道 / 模型 / 天各列前几名。 */
    private void usageDialog() {
        if (usageLine != null) usageLine.setText("今日 " + com.cpa.gateway.core.Usage.line(1));
        com.cpa.gateway.core.Usage.Summary s = com.cpa.gateway.core.Usage.summary(7);
        LinearLayout box = Ui.column(this);
        box.addView(Ui.label(this, "近 7 天：" + com.cpa.gateway.core.Usage.line(7)));
        // 每天一根柱子：自绘（见 UsageChart），颜色跟着主题走
        UsageChart chart = new UsageChart(this, com.cpa.gateway.core.Usage.daily(7));
        box.addView(chart, Ui.lp(this, 10));

        /* 成本：以前只有"按模型"那一行带 ≈$，想看"这周一共花了多少 / 哪天最费"得自己加。
           这里补一个总计 + 逐日两行，都复用原来那套"查不到就明说"的写法（不做 0 假象）。 */
        costSection(box);
        java.util.List<String> slow = com.cpa.gateway.core.Usage.slowest(7, 5);
        if (!slow.isEmpty()) {
            box.addView(Ui.sectionTitle(this, "最慢的 5 次"), Ui.lp(this, 14));
            for (String row : slow) {
                box.addView(Ui.text(this, row, 12f, Ui.MUTED, false), Ui.lp(this, 6));
            }
        }
        usageSection(box, "按接口", s.byApi, false);
        usageSection(box, "按渠道", s.byProvider, false);
        usageSection(box, "按模型", s.byModel, true);
        usageSection(box, "按天", s.byDay, false);
        usageSection(box, "按 Key（哪个客户端）", s.byKey, false);
        box.addView(Ui.label(this, "只有「按模型」能给出成本：价格是按模型定的，"
                + "接口/渠道/Key 这些分组里 token 已经混了多个模型，查不到单价。"), Ui.lp(this, 12));
        Ui.dialog(this, "用量", Ui.scroller(this, box), "关闭", null, null, null);
    }

    /**
     * 成本区块：近 7 天总计 + 逐日明细。
     *
     * 两个刻意的做法：
     *   · 总计在标题里，不埋在最后一行 —— 这是用户打开"用量"最想看的那个数字；
     *   · 逐日只列"查得到价格"的天：价格表对没收录的模型返回 -1，
     *     那种天如果显示 $0.00000，用户会以为"这天没花钱"，而实际是"这天花了但算不出来"。
     *     所以查不到的天直接不出现在列表里，并在末尾说明有几天统计不到。
     */
    private void costSection(LinearLayout box) {
        com.cpa.gateway.core.Usage.Summary s = com.cpa.gateway.core.Usage.summary(7);
        java.util.LinkedHashMap<String, Double> byDay = com.cpa.gateway.core.Usage.dailyCost(7);
        if (s.total.requests == 0 && byDay.isEmpty()) return;

        box.addView(Ui.sectionTitle(this, "成本（近 7 天，估算）"), Ui.lp(this, 14));
        if (s.costUsd >= 0) {
            box.addView(Ui.text(this, "合计 ≈$" + com.cpa.gateway.core.Usage.usd(s.costUsd),
                    15f, Ui.TEXT, true), Ui.lp(this, 8));
        } else {
            box.addView(Ui.text(this, "合计：价格表里查不到这些模型，无法估算",
                    13f, Ui.MUTED, false), Ui.lp(this, 8));
        }

        /* 逐日：按金额从大到小排，看"哪天最费"比看时间顺序更有用 */
        if (!byDay.isEmpty()) {
            java.util.List<java.util.Map.Entry<String, Double>> list =
                    new java.util.ArrayList<>(byDay.entrySet());
            list.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
            StringBuilder sb = new StringBuilder();
            for (java.util.Map.Entry<String, Double> e : list) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(e.getKey()).append("   ≈$")
                        .append(com.cpa.gateway.core.Usage.usd(e.getValue()));
            }
            box.addView(Ui.text(this, sb.toString(), 12.5f, Ui.MUTED, false), Ui.lp(this, 8));
        }

        int daysWithTraffic = s.byDay.size();
        int daysPriced = byDay.size();
        if (daysWithTraffic > daysPriced) {
            box.addView(Ui.text(this, "（近 7 天里有 " + (daysWithTraffic - daysPriced)
                    + " 天含价格表未收录的模型，未计入）", 11.5f, Ui.MUTED, false), Ui.lp(this, 6));
        }
        if (!com.cpa.gateway.core.Prices.ready()) {
            box.addView(Ui.text(this, "（价格表尚未载入，稍后再看）", 11.5f, Ui.MUTED, false), Ui.lp(this, 6));
        }
        box.addView(Ui.label(this, "按 LiteLLM 公开价格估算，含 ≈ 符号的都只是量级参考，以账单为准。"),
                Ui.lp(this, 6));
    }

    private void usageSection(LinearLayout box, String title,
                              java.util.Map<String, com.cpa.gateway.core.Usage.Stat> map) {
        usageSection(box, title, map, false);
    }

    private void usageSection(LinearLayout box, String title,
                              java.util.Map<String, com.cpa.gateway.core.Usage.Stat> map,
                              boolean withCost) {
        if (map == null || map.isEmpty()) return;
        box.addView(Ui.sectionTitle(this, title), Ui.lp(this, 14));
        java.util.List<java.util.Map.Entry<String, com.cpa.gateway.core.Usage.Stat>> list =
                new java.util.ArrayList<>(map.entrySet());
        list.sort((a, b) -> Long.compare(b.getValue().requests, a.getValue().requests));
        int shown = 0;
        for (java.util.Map.Entry<String, com.cpa.gateway.core.Usage.Stat> e : list) {
            if (shown++ >= 6) break;
            com.cpa.gateway.core.Usage.Stat st = e.getValue();
            StringBuilder line = new StringBuilder();
            line.append(e.getKey()).append(" · ").append(st.requests).append(" 次");
            if (st.ok < st.requests) line.append("（失败 ").append(st.requests - st.ok).append("）");
            line.append(" · 平均 ").append(Math.round(st.avgMs())).append("ms");
            if (st.tokIn + st.tokOut > 0) {
                line.append(" · ").append(st.tokIn).append("/").append(st.tokOut).append(" tok");
            }
            if (withCost) {
                double c = com.cpa.gateway.core.Prices.costUsd(e.getKey(), st.tokIn, st.tokOut);
                line.append(c >= 0 ? " · ≈$" + com.cpa.gateway.core.Usage.usd(c) : " · 成本未知");
            }
            box.addView(Ui.text(this, line.toString(), 13f, Ui.MUTED, false), Ui.lp(this, 6));
        }
    }
}
