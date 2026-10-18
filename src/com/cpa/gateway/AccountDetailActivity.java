package com.cpa.gateway;

import com.cpa.gateway.core.Threads;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.cpa.gateway.core.Config;
import com.cpa.gateway.core.Gateway;
import com.cpa.gateway.core.Log;
import com.cpa.gateway.core.Upstreams;
import com.cpa.gateway.core.WorkBuddy;

/** Per-account page: what it is, how many models, the model list, and the destructive actions. */
public class AccountDetailActivity extends Activity {

    public static final String EXTRA_PROVIDER_ID = "provider_id";

    private Config.Provider provider;
    private LinearLayout modelBox;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.darkSystemBars(this);
        Gateway.init(this);
        String id = getIntent().getStringExtra(EXTRA_PROVIDER_ID);
        for (Config.Provider p : Gateway.config().providers) {
            if (p.id != null && p.id.equals(id)) provider = p;
        }
        if (provider == null) {
            finish();
            return;
        }

        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Color.TRANSPARENT);
        root.setPadding(Ui.dp(this, 16), Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 16));

        TextView back = Ui.barButton(this, "‹ 返回");
        back.setOnClickListener(v -> finish());
        LinearLayout header = Ui.appBar(this, back, displayName(), null);
        TextView edit = Ui.barButton(this, "编辑");
        edit.setOnClickListener(v -> editor());
        header.addView(edit);
        root.addView(header);

        LinearLayout info = Ui.row(this);
        info.addView(Ui.stat(this, "个模型", String.valueOf(provider.models.size())));
        if (provider.credits >= 0) {
            LinearLayout.LayoutParams c1 = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            c1.leftMargin = Ui.dp(this, 8);
            info.addView(Ui.stat(this, "积分", String.valueOf((long) provider.credits)), c1);
        }
        LinearLayout.LayoutParams c2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        c2.leftMargin = Ui.dp(this, 8);
        info.addView(Ui.pill(this, !provider.enabled ? "已停用" : (provider.catalogOnly ? "仅目录" : "已启用"),
                !provider.enabled ? Ui.RED : (provider.catalogOnly ? Ui.ACCENT : Ui.GREEN),
                !provider.enabled ? Ui.RED_SOFT : (provider.catalogOnly ? Ui.ACCENT_SOFT : Ui.GREEN_SOFT)), c2);
        root.addView(info, Ui.lp(this, 16));

        String site = com.cpa.gateway.core.VendorHome.of(provider);
        if (!site.isEmpty()) {
            TextView openSite = Ui.outlineButton(this, "打开官网（充值 / 管理）");
            openSite.setOnClickListener(v -> BrowseActivity.openChoice(this, com.cpa.gateway.core.VendorHome.of(provider)));
            root.addView(openSite, Ui.lp(this, 12));
        }
        TextView meta = Ui.label(this, Ui.typeLabel(provider.type) + " · " + source());
        /* 限流提示：这是用户最需要、却以前完全看不到的信息。
           被限流的账号 lastError 是空的（限流刻意不记 lastError），
           用户点进来一切正常，只能反复试，还以为"模型坏了"。
           注意措辞是"限流中"而不是"不可用"——账号是好的，只是暂时别去撞。 */
        if (provider.throttleUntil > System.currentTimeMillis() / 1000L
                && provider.throttleNote != null && !provider.throttleNote.isEmpty()) {
            LinearLayout.LayoutParams wlp = Ui.lp(this, 10);
            TextView warn = Ui.label(this, "⏳ " + provider.throttleNote);
            warn.setTextColor(0xFFFFB454);
            warn.setTextSize(12.5f);
            root.addView(warn, wlp);
        } else if (provider.throttleUntil > 0 && provider.throttleUntil <= System.currentTimeMillis() / 1000L) {
            /* 到点了但还没成功过一次：不显示"限流中"（时间已过），也不主动清——
               真正清掉由"成功一次"负责，避免上游提前恢复时误报。 */
        }
        TextView balanceHint = Ui.label(this, "");
        balanceHint.setTextColor(0xFF7FD1A0);
        balanceHint.setTextSize(12.5f);
        balanceHint.setSingleLine(false);
        root.addView(balanceHint, Ui.lp(this, 8));
        fillBalanceHint(balanceHint);
        root.addView(meta, Ui.lp(this, 12));
        if (provider.baseUrl != null && !provider.baseUrl.isEmpty()) {
            TextView where = Ui.label(this, "地址：" + provider.baseUrl);
            where.setSingleLine(false);
            root.addView(where, Ui.lp(this, 4));
        }
        String identity = identityOf(provider);
        if (!identity.isEmpty()) {
            TextView who = Ui.label(this, "身份：" + identity);
            who.setSingleLine(false);
            root.addView(who, Ui.lp(this, 4));
        }

        LinearLayout actions = Ui.row(this);
        TextView refresh = Ui.outlineButton(this, "重新拉取模型");
        refresh.setOnClickListener(v -> refreshModels());
        LinearLayout.LayoutParams a1 = Ui.weight(this, 1f, 0);
        a1.rightMargin = Ui.dp(this, 8);
        actions.addView(refresh, a1);

        TextView addModel = Ui.outlineButton(this, "＋ 加模型");
        addModel.setOnClickListener(v -> addModelDialog());
        LinearLayout.LayoutParams a2 = Ui.weight(this, 1f, 0);
        a2.rightMargin = Ui.dp(this, 8);
        actions.addView(addModel, a2);
        TextView toggle = Ui.outlineButton(this, provider.enabled ? "停用" : "启用");
        toggle.setOnClickListener(v -> {
            provider.enabled = !provider.enabled;
            Gateway.save(this);
            toast(provider.enabled ? "已启用" : "已停用");
            recreate();
        });
        actions.addView(toggle, Ui.weight(this, 1f, 0));
        root.addView(actions, Ui.lp(this, 16));

        /* 仅目录可用：上游只给目录、推理一律拒（例如 WorkBuddy global 的渠道校验）。
           打开后这个账号不参与派发、不进客户端模型列表、检测也不再探它。 */
        android.widget.Switch catOnly = Ui.toggle(this,
                "仅目录可用（推理被上游拒；不派发、不探测）", provider.catalogOnly);
        catOnly.setOnCheckedChangeListener((btn, on) -> {
            provider.catalogOnly = on;
            Gateway.save(this);
            toast(on ? "已设为仅目录：不再派发/探测" : "已恢复派发");
            recreate();
        });
        root.addView(catOnly, Ui.lp(this, 6));

        /* 额度构成：一个"积分"数字看不出问题（比如"为什么这个号只有 500"），
           这里把上游账单里的每张资源包列出来：剩余 / 面额 / 本包已用 / 周期结束日。 */
        if (Config.TYPE_WORKBUDDY.equals(provider.type)) {
            final TextView packsView = Ui.mono(this, "额度构成：点下面按钮向上游查询", 11.5f);
            packsView.setSingleLine(false);
            LinearLayout packCard = Ui.column(this);
            packCard.addView(Ui.label(this, "额度构成（上游账单）"));
            packCard.addView(packsView, Ui.lp(this, 8));
            TextView loadPacks = Ui.outlineButton(this, "查询额度明细");
            loadPacks.setOnClickListener(v -> loadPacks(packsView));
            packCard.addView(loadPacks, Ui.lp(this, 10));
            root.addView(Ui.card(this, packCard), Ui.lp(this, 14));
        }

        int badCount = 0;
        for (String m : provider.models) {
            if (provider.badModels.contains(m)) badCount++;
        }
        root.addView(Ui.sectionTitle(this, "模型 " + provider.models.size() + " 个（点名称复制）"
                + (badCount > 0 ? " ·其中 " + badCount + " 个调不通" : "")), Ui.lp(this, 20));
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        modelBox = Ui.column(this);
        sv.addView(modelBox);
        root.addView(sv, Ui.fill(this, 10));
        renderModels();
        // 底层强度 0.50 —— 账号详情：字段较多
        setContentView(Ui.glassScaffold(this, root, 0.50f));
    }

    /** 账号显示名，去掉 "WorkBuddy-" 这类冗余前缀。 */
    private String displayName() {
        String n = provider.name == null ? "" : provider.name;
        for (String prefix : new String[]{"WorkBuddy-", "Claude-", "ChatGPT-", "OpenAI-"}) {
            if (n.startsWith(prefix)) n = n.substring(prefix.length());
        }
        return n;
    }

    /**
     * 额度构成里最该被看见的一行：**还有余额但推理仍报"耗尽"**。
     *
     * 4.31.1 实测：同一批 global 账号推理统一回 `14018 Credits exhausted`，可
     * `get-user-resource` 明明显示某张包还剩 100 credits（另一张用完了）。也就是说
     * "耗尽"指的是**当前被计费的那张包**，不是整个账号。用户只看得到"额度不足"这句话，
     * 自然会去充值 —— 而正确答案往往是"换号"或"等那张没动过的包生效"。
     *
     * 只读：查询接口，不签到、不领奖。查不到（非 WorkBuddy \/ 凭证过期 \/ 断网）就返回空串，
     * 不抛异常 —— 这行是锦上添花，不该把账号页拖崩。
     */
    private void fillBalanceHint(TextView view) {
        view.setVisibility(android.view.View.GONE);
        if (!com.cpa.gateway.core.Config.TYPE_WORKBUDDY.equals(provider.type)) return;
        Threads.io(() -> {
            String hint = "";
            try {
                org.json.JSONObject b = com.cpa.gateway.core.WbTasks.balance(provider);
                if (b.optBoolean("ok") && b.optDouble("total", 0) > 0) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("上游仍显示有 ").append(Math.round(b.optDouble("total", 0)))
                            .append(" credits 可用");
                    org.json.JSONObject soon = b.optJSONObject("soonest");
                    if (soon != null) {
                        String end = soon.optString("end", "");
                        sb.append("（先到期：").append(soon.optString("name", "资源包"))
                                .append(" 剩 ").append(Math.round(soon.optDouble("remain", 0)));
                        if (end.length() >= 10) sb.append("，到 ").append(end.substring(0, 10));
                        sb.append('）');
                    }
                    /* 只有当它正在被上游拒的时候，这句才是"反直觉"的、值得提醒的 */
                    if (provider.throttleUntil > System.currentTimeMillis() / 1000L
                            && provider.throttleNote != null && provider.throttleNote.contains("额度")) {
                        sb.append("；但推理仍被判「额度耗尽」——通常是被计费的那张包用完了，")
                                .append("换号比充值更快见效");
                    }
                    hint = sb.toString();
                }
            } catch (Throwable ignored) {
            }
            final String text = hint;
            if (text.isEmpty()) return;
            runOnUiThread(() -> {
                view.setText("💰 " + text);
                view.setVisibility(android.view.View.VISIBLE);
            });
        });
    }

    /** 从凭据里挑一个能认出"这是哪个账号"的字段。 */
    /**
     * 拉一次额度构成并渲染成几行文本。
     *
     * 只读：`get-user-resource` 是查询接口，不改服务端状态。
     * 排序：还有剩余的排前面、快到期的小字标注——这张表就是用来回答"该先花哪个号"的。
     */
    private void loadPacks(TextView view) {
        view.setText("正在向上游查询…");
        Threads.io(() -> {
            String text;
            try {
                org.json.JSONArray packs = com.cpa.gateway.core.WbTasks.packs(provider);
                java.util.List<org.json.JSONObject> list = new java.util.ArrayList<>();
                double total = 0;
                for (int i = 0; i < packs.length(); i++) {
                    org.json.JSONObject r = packs.optJSONObject(i);
                    if (r == null) continue;
                    list.add(r);
                    total += r.optDouble("remain", 0);
                }
                list.sort((a, b) -> Double.compare(b.optDouble("remain", 0), a.optDouble("remain", 0)));
                StringBuilder sb = new StringBuilder();
                sb.append("合计剩余 ").append(Math.round(total)).append(" credits · 共 ")
                        .append(list.size()).append(" 个包\n");
                int shown = 0;
                for (org.json.JSONObject r : list) {
                    double remain = r.optDouble("remain", 0);
                    if (remain <= 0 && shown >= 8) continue;   // 用完的包最多列 8 条，免得刷屏
                    sb.append(remain > 0 ? "● " : "○ ")
                            .append(r.optString("name", "资源包"))
                            .append("  剩 ").append(Math.round(remain)).append('/')
                            .append(Math.round(r.optDouble("size", 0)));
                    double used = r.optDouble("used", 0);
                    if (used > 0) sb.append("（已用 ").append(Math.round(used)).append('）');
                    String end = r.optString("end", "");
                    if (end.length() >= 10) sb.append("  到 ").append(end.substring(0, 10));
                    sb.append('\n');
                    shown++;
                }
                if (shown == 0) sb.append("（上游没有返回资源包明细）");
                text = sb.toString();
            } catch (Throwable e) {
                text = "查询失败：" + e.getMessage() + "\n（非 WorkBuddy 账号 / 凭据过期 / 网络不通都会这样）";
            }
            final String out = text;
            runOnUiThread(() -> view.setText(out));
        });
    }

    static String identityOf(Config.Provider provider) {
        try {
            org.json.JSONObject c = new org.json.JSONObject(
                    provider.credentials == null || provider.credentials.isEmpty() ? "{}" : provider.credentials);
            for (String key : new String[]{"nickname", "phone", "email", "ScreenName", "uid"}) {
                String v = com.cpa.gateway.core.Proto.optString(c, key, "");
                if (!v.isEmpty()) return v;
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /** 账号来源：WorkBuddy 分国内/国际版，其余直接显示服务地址。 */
    private String source() {
        try {
            if (Config.TYPE_WORKBUDDY.equals(provider.type)) {
                org.json.JSONObject c = new org.json.JSONObject(
                        provider.credentials == null || provider.credentials.isEmpty() ? "{}" : provider.credentials);
                return WorkBuddy.REALM_GLOBAL.equalsIgnoreCase(com.cpa.gateway.core.Proto.optString(
                        c, WorkBuddyLoginActivity.EXTRA_REALM, WorkBuddy.REALM_CN))
                        ? "国际版 · workbuddy.ai" : "国内版 · codebuddy.cn";
            }
            return provider.baseUrl == null || provider.baseUrl.isEmpty() ? "已配置" : provider.baseUrl;
        } catch (Exception ignored) {
            return "已配置";
        }
    }

    /** 这个账号自己的模型清单：一个不少全列出来，调不通的单独标一下。 */
    private void renderModels() {
        modelBox.removeAllViews();
        if (provider.models.isEmpty()) {
            modelBox.addView(Ui.label(this, "还没有模型，点上面的「重新拉取模型」，或用「＋ 加模型」手填一个"));
            return;
        }
        // 能调的排前面，跑不通的垫底
        java.util.List<String> ordered = new java.util.ArrayList<>(provider.models);
        java.util.Collections.sort(ordered, (a, b) -> {
            boolean ba = provider.badModels.contains(a);
            boolean bb = provider.badModels.contains(b);
            if (ba != bb) return ba ? 1 : -1;
            return a.compareToIgnoreCase(b);
        });
        for (String m : ordered) {
            final String model = m;
            boolean bad = provider.badModels.contains(m);
            // 外层改成纵向两段容器：第一行"模型名 + 状态胶囊"，第二行能力标签。
            // 原来只有一行横向，模型名用 weight(1f) 占满，标签根本没地方放。
            LinearLayout card = Ui.column(this);
            card.setBackground(Ui.roundStroke(this, Ui.CARD, 14, Ui.STROKE));
            card.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));

            LinearLayout row = Ui.row(this);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.addView(Ui.text(this, model, 14f, Ui.TEXT, false), Ui.weight(this, 1f, 0));
            // 标出哪些是手填的：它们不会被上游刷新覆盖，也可以长按删掉
            if (provider.manualModels.contains(model)) {
                row.addView(Ui.pill(this, "手填", Ui.ACCENT_LIGHT, Ui.ACCENT_SOFT));
            }
            if (bad) row.addView(Ui.pill(this, "调不通", Ui.RED, Ui.RED_SOFT));
            card.addView(row);

            // 能力标签：查不到就整行不显示（不猜、不显示"未知"）
            java.util.List<String> caps = capsOf(model);
            if (!caps.isEmpty()) {
                FlowRow tagRow = new FlowRow(this, Ui.dp(this, 5));
                for (int i = 0; i < caps.size(); i++) {
                    tagRow.addView(Ui.tag(this, caps.get(i)));
                }
                card.addView(tagRow, Ui.lp(this, 6));
            }

            row.setClickable(true);
            row.setOnClickListener(v -> {
                Ui.copyWithAutoClear(this, model);
                toast("已复制 " + model);
            });
            if (provider.manualModels.contains(model)) {
                row.setOnLongClickListener(v -> {
                    Ui.dialog(this, "删掉手填的「" + model + "」？",
                            Ui.label(this, "只从这个账号的清单里移除，不影响上游。"),
                            "取消", null, "删除", () -> {
                                provider.manualModels.remove(model);
                                provider.models.remove(model);
                                provider.badModels.remove(model);
                                Gateway.persist();
                                renderModels();
                                toast("已移除 " + model);
                            }).show();
                    return true;
                });
            }
            modelBox.addView(card, Ui.lp(this, 8));
        }
    }

    /** 这个模型的能力标签；查不到返回空表（调用方据此不显示标签行）。 */
    private java.util.List<String> capsOf(String model) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        try {
            com.cpa.gateway.core.ModelCaps.Caps c = com.cpa.gateway.core.ModelCaps.lookup(model);
            if (c == null || !c.any()) return out;
            if (c.context > 0) out.add("上下文 " + com.cpa.gateway.core.ModelCaps.shortTokens(c.context));
            if (c.maxOutput > 0) out.add("输出 " + com.cpa.gateway.core.ModelCaps.shortTokens(c.maxOutput));
            if (c.has(com.cpa.gateway.core.ModelCaps.VISION)) out.add("多模态");
            if (c.has(com.cpa.gateway.core.ModelCaps.TOOLS)) out.add("工具");
            if (c.has(com.cpa.gateway.core.ModelCaps.REASONING)) out.add("推理");
            if (c.has(com.cpa.gateway.core.ModelCaps.AUDIO)) out.add("音频");
            if (c.has(com.cpa.gateway.core.ModelCaps.PDF)) out.add("PDF");
            if (c.has(com.cpa.gateway.core.ModelCaps.WEB)) out.add("联网");
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * 手填一个模型名加到这个账号。
     *
     * 手填的会记进 manualModels：上游刷新整表覆盖 models，不单独记一份，
     * 手加的模型下一次拉取就没了（这就是"加了模型过一会儿又不见了"）。
     */
    private void addModelDialog() {
        final android.widget.EditText input = Ui.field(this, "例如 deepseek-v4-flash-0731", "", false);
        Ui.dialog(this, "给「" + provider.name + "」加模型", input, "取消", null, "添加", () -> {
            String t = input.getText().toString().trim();
            if (t.isEmpty()) {
                toast("模型名不能为空");
                return;
            }
            if (provider.models.contains(t) && provider.manualModels.contains(t)) {
                toast("已经有这个模型了");
                return;
            }
            if (!provider.models.contains(t)) provider.models.add(t);
            if (!provider.manualModels.contains(t)) provider.manualModels.add(t);
            provider.badModels.remove(t);
            Gateway.persist();
            renderModels();
            Log.i("手动添加模型：" + provider.name + " ← " + t);
            toast("已添加（刷新时不会被覆盖）");
        }).show();
    }

    private void refreshModels() {
        toast("正在拉取…");
        Threads.io(() -> {
            try {
                java.util.List<String> models = Upstreams.fetchModels(provider);
                provider.setModels(models);
                Gateway.persist();
                runOnUiThread(() -> {
                    renderModels();
                    toast("拿到 " + models.size() + " 个模型");
                });
            } catch (Exception e) {
                Log.w("拉取模型失败：" + e);
                runOnUiThread(() -> toast("失败：" + e.getMessage()));
            }
        });
    }

    /** Destructive action lives behind 编辑, not on the list. */
    private void editor() {
        MainActivity.editProvider(this, provider, () -> finish());
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    static void unusedWorkBuddyImportGuard() {
        // keeps the WorkBuddy helper referenced for future account import entry points
        WorkBuddy.REALM_CN.length();
    }
}
