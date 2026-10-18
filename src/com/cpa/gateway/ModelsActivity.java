package com.cpa.gateway;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.cpa.gateway.core.Config;
import com.cpa.gateway.core.ModelCaps;
import com.cpa.gateway.core.Gateway;
import com.cpa.gateway.core.Log;
import com.cpa.gateway.core.Upstreams;

import java.util.ArrayList;
import java.util.List;

/** A clean, searchable list of every model the gateway can serve. */
public class ModelsActivity extends Activity {

    /** 每行一个 View 的列表改成了 ListView：几千个模型时内存才不会跟着涨。 */
    private ListView listView;
    private TextView emptyView;
    private ModelAdapter adapter;
    private EditText search;
    private TextView summary;

    private static final class Item {
        final String model;
        final String provider;
        final String type;
        final String vendor;
        /** 实测调不通。只做标记，不从列表里拿掉：模型是账号自己的，有几个就该显示几个。 */
        final boolean flagged;
        /** 客户端里看到的前缀：官方1 / 中科2 / 中转站简称。 */
        final String prefix;

        Item(String model, String provider, String type, String vendor, boolean flagged, String prefix) {
            this.model = model;
            this.provider = provider;
            this.type = type;
            this.vendor = vendor;
            this.flagged = flagged;
            this.prefix = prefix;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.darkSystemBars(this);

        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Color.TRANSPARENT);
        int pad = Ui.dp(this, 16);
        root.setPadding(pad, pad, pad, pad);

        TextView back = Ui.barButton(this, "‹ 返回");
        back.setOnClickListener(v -> finish());
        root.addView(Ui.appBar(this, back, "模型", null));
        summary = Ui.label(this, "");
        root.addView(summary, Ui.lp(this, 4));

        search = new EditText(this);
        search.setHint("搜索模型名，例如 hy3 / glm / gpt");
        search.setSingleLine(true);
        search.setTextColor(Ui.TEXT);
        search.setHintTextColor(Ui.MUTED);
        search.setBackground(Ui.roundStroke(this, Ui.CARD_2, 14, Ui.STROKE));
        int sp = Ui.dp(this, 14);
        search.setPadding(sp, sp, sp, sp);
        root.addView(search, Ui.lp(this, 12));

        LinearLayout buttons = Ui.row(this);
        // 分割按钮：主操作是"客户端口径全量复制"，变体放▾里
        LinearLayout copyAll = Ui.splitButton(this, "复制全部模型名",
                new String[]{"只复制模型名（去重）", "只复制能调通的"},
                () -> copyRows(items(), true),
                which -> {
                    if (which == 0) {
                        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
                        for (Item i : items()) names.add(i.model);
                        StringBuilder sb = new StringBuilder();
                        for (String n : names) sb.append(n).append('\n');
                        copy(sb.toString().trim());
                        toast("已复制 " + names.size() + " 个模型名（去重）");
                    } else {
                        List<Item> ok = new ArrayList<>();
                        for (Item i : items()) if (!i.flagged) ok.add(i);
                        copyRows(ok, true);
                    }
                });
        buttons.addView(copyAll);

        TextView add = Ui.accentChipButton(this, "＋ 添加模型");
        add.setOnClickListener(v -> addModelDialog());
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addLp.leftMargin = Ui.dp(this, 8);
        buttons.addView(add, addLp);

        root.addView(buttons, Ui.lp(this, 10));

        // 工具类操作单独一行。
        //
        // 原来四个按钮挤在一行里，各用了不同的宽度策略：分割按钮内部自带
        // weight、`添加模型`和`刷新全部`是 WRAP_CONTENT、而这个新加的
        // `重新检测全部`用了 weight=1f 去抢空间——结果 splitButton 被挤窄，
        // 右边的固定宽度按钮被顶出去，整行看起来是断的（真机截图确认）。
        //
        // 分成两行，每行内部只剩一种宽度策略：上面那行是"复制/添加"，
        // 左边是分割按钮（自带 weight 撑满）、右边是固定宽的 chip 按钮；
        // 下面这行两个都是同一种 outlineButton，各占一半，视觉上对称。
        //
        // 顺带一提：`重新检测全部`和`刷新全部`分成两个按钮而不是塞进
        // splitButton 的 ▾ 里，是因为它们**代价差很多**——刷新只是重新拉
        // 上游目录（不花额度），全量重测是实打实逐个探测（花额度、要等
        // 一两分钟）。代价不同级的操作不该藏进同一个下拉里。
        LinearLayout tools = Ui.row(this);
        TextView recheckAll = Ui.outlineButton(this, "重新检测全部");
        recheckAll.setOnClickListener(v -> recheckAllModels());
        tools.addView(recheckAll, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView refreshAll = Ui.outlineButton(this, "刷新全部");
        refreshAll.setOnClickListener(v -> refreshAll());
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rLp.leftMargin = Ui.dp(this, 8);
        tools.addView(refreshAll, rLp);

        root.addView(tools, Ui.lp(this, 8));

        // ListView + 自写 Adapter：屏幕上几行就只存在几行（原来的 ScrollView 是全量 addView）
        ListView lv = new ListView(this);
        lv.setDivider(null);
        lv.setDividerHeight(Ui.dp(this, 8));
        // 关滚动条要用三件套：setVerticalScrollBarEnabled 单独一个在部分 ROM
        // （尤其 MIUI）上仍会画出一条又粗又显眼的竖条，它不认这个开关。
        // setScrollbarFadingEnabled(false) + setScrollBarStyle(INSIDE_OVERLAY)
        // 一起设才彻底不画；下面的 XML 式属性同样兜一层。
        lv.setVerticalScrollBarEnabled(false);
        lv.setScrollbarFadingEnabled(false);
        lv.setScrollBarStyle(android.view.View.SCROLLBARS_INSIDE_OVERLAY);
        lv.setFastScrollEnabled(false);
        lv.setOverScrollMode(android.view.View.OVER_SCROLL_NEVER);
        lv.setCacheColorHint(0);
        adapter = new ModelAdapter();
        lv.setAdapter(adapter);
        listView = lv;
        root.addView(lv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        emptyView = Ui.label(this, "");
        root.addView(emptyView, Ui.lp(this, 12));
        lv.setEmptyView(emptyView);

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                render(s.toString().trim());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        // 底层强度 0.65 —— 模型列表：中等
        setContentView(Ui.glassScaffold(this, root, 0.65f));
        render("");
    }

    private List<Item> items() {
        List<Item> out = new ArrayList<>();
        for (Config.Provider p : Gateway.config().providers) {
            if (!p.enabled) continue;
            for (String m : p.models) {
                if ("*".equals(m)) continue;
                out.add(new Item(m, p.name, p.type, MainActivity.vendorOf(p),
                        p.badModels.contains(m),
                        com.cpa.gateway.core.ShortName.of(p)));
            }
        }
        return out;
    }

    private void render(String query) {
        List<Item> all = items();
        List<Item> shown = new ArrayList<>();
        String q = query == null ? "" : query.toLowerCase();
        for (Item i : all) {
            if (q.isEmpty() || i.model.toLowerCase().contains(q)) shown.add(i);
        }
        int flagged = 0;
        for (Item i : all) {
            if (i.flagged) flagged++;
        }
        summary.setText("共 " + all.size() + " 条 · 点模型名即可复制"
                + (flagged > 0 ? "\n其中 " + flagged + " 条实测调不通，已标出、不对外提供" : ""));
        if (shown.isEmpty()) {
            emptyView.setText(all.isEmpty()
                    ? "还没有模型。点上面的「＋ 添加模型」手填一个，或先添加账号再点「刷新全部」。"
                    : "没有匹配「" + query + "」的模型");
            adapter.setItems(shown);
            return;
        }
        // 预览就是"看有什么模型"：平铺，同名模型自然挨在一起
        shown.sort((x, y) -> {
            int byModel = x.model.compareToIgnoreCase(y.model);
            if (byModel != 0) return byModel;
            int byProvider = x.provider.compareToIgnoreCase(y.provider);
            return byProvider != 0 ? byProvider : x.vendor.compareToIgnoreCase(y.vendor);
        });
        adapter.setItems(shown);
    }

    /**
     * 行适配器：**复用**每一行的 View，滚动时只换内容。
     *
     * 原来是 ScrollView + 每行 addView：300 个模型就是 300 套 View（每套一个圆角面板 + 3 个 TextView + 一个按压动效对象），
     * 实测模型页 Native Heap 直接涨 15MB、首屏构建期间 UI 线程一帧都出不来。
     * 交给 ListView 的回收机制后，屏幕上几行就只存在几行。
     */
    private final class ModelAdapter extends android.widget.BaseAdapter {
        private final List<Item> data = new ArrayList<>();

        void setItems(List<Item> rows) {
            data.clear();
            data.addAll(rows);
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return data.size();
        }

        @Override
        public Object getItem(int position) {
            return data.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Holder h;
            if (convertView == null) {
                h = new Holder();
                LinearLayout row = Ui.column(ModelsActivity.this);
                row.setBackground(Ui.roundStroke(ModelsActivity.this, Ui.CARD, 14, Ui.STROKE));
                row.setPadding(Ui.dp(ModelsActivity.this, 14), Ui.dp(ModelsActivity.this, 12),
                        Ui.dp(ModelsActivity.this, 14), Ui.dp(ModelsActivity.this, 12));
                LinearLayout nameRow = Ui.row(ModelsActivity.this);
                h.name = Ui.text(ModelsActivity.this, "", 16f, Ui.TEXT, false);
                nameRow.addView(h.name, Ui.weight(ModelsActivity.this, 1f, 0));
                h.badge = Ui.pill(ModelsActivity.this, "调不通", Ui.RED, Ui.RED_SOFT);
                nameRow.addView(h.badge);
                row.addView(nameRow);
                h.sub = Ui.text(ModelsActivity.this, "", 12f, Ui.MUTED, false);
                row.addView(h.sub);
                // FlowRow 而不是 Ui.row：标签多了要能自动换行，
                // 横向 LinearLayout 放不下就被裁掉（真机上"联网"只剩一半）。
                h.tags = new FlowRow(ModelsActivity.this, Ui.dp(ModelsActivity.this, 5));
                h.tags.setVisibility(View.GONE);
                row.addView(h.tags, Ui.lp(ModelsActivity.this, 6));
                row.setClickable(true);
                Ui.pressable(row);
                h.row = row;
                row.setTag(h);
                convertView = row;
            } else {
                h = (Holder) convertView.getTag();
            }
            final Item item = data.get(position);
            h.name.setText(item.model);
            h.sub.setText(Ui.typeLabel(item.type) + " · " + item.provider);
            h.badge.setVisibility(item.flagged ? View.VISIBLE : View.GONE);
            bindCaps(h.tags, item.model);
            h.row.setOnClickListener(v -> {
                copy(item.model);
                toast("已复制 " + item.model);
            });
            return convertView;
        }
    }

    /**
     * 渲染一行能力标签。查不到就不显示——**不猜**。
     *
     * 标签来源是 res/raw/model_prices.json 里的能力字段（见 ModelCaps）。
     * 查不到的模型（尤其国内的 kimi/glm/doubao 这类）就没有这一行，
     * 比显示一串"未知"更干净。
     */
    private void bindCaps(FlowRow tags, String model) {
        tags.removeAllViews();
        ModelCaps.Caps c = ModelCaps.lookup(model);
        if (c == null || !c.any()) {
            tags.setVisibility(View.GONE);
            return;
        }
        java.util.List<String> labels = new java.util.ArrayList<String>();
        if (c.context > 0) labels.add("上下文 " + ModelCaps.shortTokens(c.context));
        if (c.maxOutput > 0) labels.add("输出 " + ModelCaps.shortTokens(c.maxOutput));
        if (c.has(ModelCaps.VISION)) labels.add("多模态");
        if (c.has(ModelCaps.TOOLS)) labels.add("工具");
        if (c.has(ModelCaps.REASONING)) labels.add("推理");
        if (c.has(ModelCaps.AUDIO)) labels.add("音频");
        if (c.has(ModelCaps.PDF)) labels.add("PDF");
        if (c.has(ModelCaps.WEB)) labels.add("联网");
        if (labels.isEmpty()) {
            tags.setVisibility(View.GONE);
            return;
        }
        for (int i = 0; i < labels.size(); i++) {
            TextView t = Ui.tag(this, labels.get(i));
            // 不设 margin：间距由 FlowRow 统一处理，否则换行后行首会多出一段空白
            tags.addView(t);
        }
        tags.setVisibility(View.VISIBLE);
    }

    private static final class Holder {
        LinearLayout row;
        TextView name;
        TextView sub;
        TextView badge;
        FlowRow tags;         // 能力标签行（自动换行）
    }

    /** 一行一个"账号 × 模型"，调不通的单独挂个标，不从列表里去掉。 */
    /** 复制一组条目，mode=true 出"账号/模型"（客户端口径）。 */
    private void copyRows(List<Item> rows, boolean withProvider) {
        StringBuilder sb = new StringBuilder();
        for (Item i : rows) {
            if (withProvider) sb.append(i.prefix).append('/').append(i.model);
            else sb.append(i.model);
            sb.append('\n');
        }
        copy(sb.toString().trim());
        toast("已复制 " + rows.size() + " 条");
    }

    /** 按账号名找回 Provider；找不到返回 null（列表可能是旧的快照）。 */
    private Config.Provider findProvider(String name) {
        if (name == null) return null;
        for (Config.Provider p : Gateway.config().providers) {
            if (name.equals(p.name)) return p;
        }
        return null;
    }

    private LinearLayout modelRow(final Item i) {
        LinearLayout row = Ui.column(this);
        row.setBackground(Ui.roundStroke(this, Ui.CARD, 14, Ui.STROKE));
        row.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        LinearLayout nameRow = Ui.row(this);
        nameRow.addView(Ui.text(this, i.model, 16f, Ui.TEXT, false), Ui.weight(this, 1f, 0));
        if (i.flagged) nameRow.addView(Ui.pill(this, "调不通", Ui.RED, Ui.RED_SOFT));
        row.addView(nameRow);
        row.addView(Ui.text(this, Ui.typeLabel(i.type) + " · " + i.provider, 12f, Ui.MUTED, false));
        if (i.flagged) {
            // 把「为什么判的」和「还剩多久失效」直接摆在行里。
            //
            // 原来的界面只有一个红标"调不通"，不给原因也不给时间：用户看到
            // 一个模型不能用了，既不知道是账号问题、限流、还是上游真删了，
            // 也不知道这个判断是不是几周前留下的陈账——只能靠"复制出来
            // 换个地方试"来猜。原因和时间都是判定当时就有的事实，没有理由
            // 只留在内存里不给人看。
            Config.Provider owner = findProvider(i.provider);
            String why = "";
            long ttl = -1L;
            if (owner != null) {
                String r = owner.badReason.get(i.model);
                if (r != null && !r.isEmpty()) why = r;
                ttl = owner.badTtl(i.model);
            }
            String line = why.isEmpty() ? "原因未记录（旧标记）" : why;
            if (ttl >= 0) {
                if (ttl <= 0) line += " · 标记已过期，下次检测会重新判定";
                else line += " · " + (ttl / 3600) + " 小时 " + ((ttl % 3600) / 60) + " 分后自动重判";
            }
            row.addView(Ui.text(this, line, 11f, Ui.MUTED, false));
        }
        row.setClickable(true);
        row.setOnClickListener(v -> {
            copy(i.model);
            toast("已复制 " + i.model);
        });
        return row;
    }

    /**
     * 挑一个账号，手填模型名加进去。
     *
     * 手填的模型会记进 provider.manualModels，之后任何一次上游刷新都不会把它抹掉
     * （模型的来源有两个：上游目录 + 用户手填，刷新只该覆盖前者）。
     */
    private void addModelDialog() {
        List<Config.Provider> targets = new ArrayList<>();
        for (Config.Provider p : Gateway.config().providers) targets.add(p);
        if (targets.isEmpty()) {
            toast("还没有账号。先在首页「添加账号」，再回来加模型。");
            return;
        }
        LinearLayout box = Ui.column(this);
        int pad = Ui.dp(this, 16);
        box.setPadding(pad, pad, pad, pad);
        box.addView(Ui.label(this, "选择要加到哪个账号"));

        final int[] chosen = new int[]{-1};
        for (int i = 0; i < targets.size(); i++) {
            final Config.Provider p = targets.get(i);
            LinearLayout row = Ui.listRow(this,
                    com.cpa.gateway.core.ShortName.of(p) + " · " + p.name,
                    Ui.typeLabel(p.type) + " · 已有 " + p.models.size() + " 个模型"
                            + (p.enabled ? "" : " · 已停用"));
            final int idx = i;
            row.setOnClickListener(v -> {
                chosen[0] = idx;
                promptModelName(name -> applyManualModel(p, name));
            });
            box.addView(row, Ui.lp(this, 8));
        }
        Ui.dialog(this, "添加模型", Ui.scroller(this, box), "取消", null, null, null).show();
    }

    /** 弹输入框要模型名。对话框 show() 是异步的，所以结果走回调，不能靠返回值。 */
    private void promptModelName(final ModelNameSink sink) {
        final android.widget.EditText input = Ui.field(this, "例如 deepseek-v4-flash-0731", "", false);
        Ui.dialog(this, "模型名", input, "取消", null, "添加", () -> {
            String t = input.getText().toString().trim();
            if (t.isEmpty()) {
                toast("模型名不能为空");
                return;
            }
            sink.got(t);
        }).show();
    }

    /** 输入框回调。 */
    private interface ModelNameSink {
        void got(String model);
    }

    private void applyManualModel(Config.Provider p, String model) {
        if (p.models.contains(model) && p.manualModels.contains(model)) {
            toast("「" + model + "」已经在这个账号里了");
            return;
        }
        if (!p.models.contains(model)) p.models.add(model);
        if (!p.manualModels.contains(model)) p.manualModels.add(model);
        p.badModels.remove(model);
        Gateway.persist();
        render(search == null ? "" : search.getText().toString().trim());
        Log.i("手动添加模型：" + p.name + " ← " + model);
        toast("已添加「" + model + "」到 " + p.name + "（刷新时不会被覆盖）");
    }

    /** 批量刷新所有启用账号的上游目录；手填的模型会被保留。 */
    /**
     * 全量重测：把每个账号目录里的模型**逐个**探一遍，用结果重建坏名单。
     *
     * 和「一键检测」的区别：那个每个账号只抽 2 个（够判断账号通不通，
     * 省额度），代价是没抽中的模型永远轮不到重判，旧标记只增不减。
     * 这一个走 com.cpa.gateway.core.Detect.start(0, 8)，0 = 全量档（见 Detect.start 的注释）。
     *
     * 代价是实打实的：一个账号 60 个模型，8 并发 × 每个最多 8 秒，
     * 最坏要等一分钟以上，而且是真的在花上游额度。所以：
     *   - 明确弹窗告诉用户大概要多久、在做什么，不搞"点了没反应"；
     *   - 不在后台偷偷跑（后台巡检仍然只抽 2 个，那是它的职责）。
     */
    private void recheckAllModels() {
        Config cfg = Gateway.config();
        int models = 0, accounts = 0;
        for (Config.Provider p : cfg.providers) {
            if (!p.enabled) continue;
            accounts++;
            models += p.models.size();
        }
        if (accounts == 0) {
            toast("还没有可用的账号");
            return;
        }
        final int fAccounts = accounts, fModels = models;
        new android.app.AlertDialog.Builder(this)
                .setTitle("重新检测全部模型")
                .setMessage("将逐个探测 " + fAccounts + " 个账号的 " + fModels + " 个模型，用来重建\"调不通\"名单。\n\n"
                        + "这会真实消耗上游额度，8 并发下最坏要一两分钟。\n"
                        + "现有标记会先作废，由这一轮结果重新判定。")
                .setNegativeButton("取消", null)
                .setPositiveButton("开始检测", (d, w) -> {
                    org.json.JSONObject started = com.cpa.gateway.core.Detect.start(0, 8);
                    if (!started.optBoolean("ok", false)) {
                        toast("已有一轮检测正在进行，等它跑完再试");
                        return;
                    }
                    toast("已开始全量检测，跑完会自动刷新列表");
                    watchRecheckDone();
                })
                .show();
    }

    /**
     * 等这一轮全量检测结束，然后刷新列表。
     *
     * 为什么要轮询而不是直接 sleep 一次：全量检测的时长取决于上游响应，
     * 没有可预测的终点。轮询 status().running 是唯一如实的做法——
     * 固定 sleep 一会要么太短（没跑完就刷新，看到的还是旧数），要么
     * 太长（跑完了白等）。3 秒一查，最多等 10 分钟兜底。
     */
    private void watchRecheckDone() {
        final android.os.Handler h = new android.os.Handler(getMainLooper());
        final long deadline = System.currentTimeMillis() + 10L * 60L * 1000L;
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                boolean running;
                try {
                    running = com.cpa.gateway.core.Detect.status().optBoolean("running", false);
                } catch (Throwable t) {
                    running = false;
                }
                if (running && System.currentTimeMillis() < deadline) {
                    h.postDelayed(this, 3000L);
                    return;
                }
                // 跑完了（或超时兜底）：重新读一遍进度数字，再刷列表
                try {
                    org.json.JSONObject st = com.cpa.gateway.core.Detect.status();
                    toast("检测结束：通过 " + st.optInt("passed", 0)
                            + " · 失败 " + st.optInt("failed", 0)
                            + " · 新标记 " + st.optInt("hidden", 0) + " 个");
                } catch (Throwable ignored) {
                }
                render(search == null ? "" : search.getText().toString().trim());
            }
        }, 3000L);
    }

    private void refreshAll() {
        toast("正在刷新…");
        com.cpa.gateway.core.Threads.io(() -> {
            int ok = 0;
            int fail = 0;
            int manual = 0;
            for (Config.Provider p : Gateway.config().providers) {
                if (!p.enabled) continue;
                try {
                    p.setModels(Upstreams.fetchModels(p));
                    ok++;
                } catch (Exception e) {
                    fail++;
                    Log.w("刷新模型失败 " + p.name + "：" + e);
                }
                manual += p.manualModels.size();
            }
            Gateway.persist();
            final int fok = ok;
            final int ffail = fail;
            final int fman = manual;
            runOnUiThread(() -> {
                render(search == null ? "" : search.getText().toString().trim());
                toast("刷新完成：" + fok + " 个成功"
                        + (ffail > 0 ? "，" + ffail + " 个失败（手填模型未受影响）" : "")
                        + (fman > 0 ? "，保留手填 " + fman + " 个" : ""));
            });
        });
    }

    private void copy(String text) {
        // 同上：统一走带自动清除的入口
        Ui.copyWithAutoClear(this, text);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
