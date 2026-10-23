package com.cpa.gateway;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.cpa.gateway.core.Config;
import com.cpa.gateway.core.Log;
import com.cpa.gateway.core.Gateway;

import java.util.ArrayList;
import java.util.List;

/**
 * Accounts, two levels: one card per vendor first, then that vendor's accounts. A long flat list of
 * every account gets unreadable once there are a dozen of them.
 */
public class AccountsActivity extends Activity {

    public static final String EXTRA_VENDOR = "vendor";
    private static final int REQ_ADD_ACCOUNT = 9101;

    private LinearLayout list;
    private String vendor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.darkSystemBars(this);
        Gateway.init(this);
        try {
            Gateway.reload(this);
        } catch (Throwable ignored) {
        }
        vendor = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_VENDOR);
        scope = getIntent() == null ? 0 : getIntent().getIntExtra("scope", 0);
        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            Gateway.reload(this);
        } catch (Throwable ignored) {
        }
        if (list != null) build();
    }

    private void toast(String msg) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show();
    }

    @Override
    public void onBackPressed() {
        if (vendor != null) {
            showVendorList();
            return;
        }
        super.onBackPressed();
    }

    /** 0 全部 · 1 只看可用 · 2 只看暂不可用。 */
    private int scope;

    private void showVendorList() {
        vendor = null;
        getIntent().removeExtra(EXTRA_VENDOR);
        build();
    }

    private void build() {
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Color.TRANSPARENT);
        root.setPadding(Ui.dp(this, 16), Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 16));

        TextView back = Ui.barButton(this, "‹ 返回");
        back.setOnClickListener(v -> {
            if (vendor != null) showVendorList();
            else finish();
        });
        LinearLayout header = Ui.appBar(this, back, vendor == null ? "账号" : vendor, null);
        TextView home = Ui.barButton(this, "官网");
        home.setOnClickListener(v -> showVendorHomes());
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.rightMargin = Ui.dp(this, 8);
        header.addView(home, hlp);
        TextView quick = Ui.barButton(this, "一键添加");
        Ui.a11y(quick, "一键添加账号：粘贴地址和密钥自动识别");
        quick.setOnClickListener(v -> quickAdd());
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        qlp.rightMargin = Ui.dp(this, 8);
        header.addView(quick, qlp);
        TextView add = Ui.accentChipButton(this, "＋ 添加");
        Ui.a11y(add, "添加账号");
        add.setOnClickListener(v -> addInsideGroup());
        header.addView(add);
        root.addView(header);

        // 分段筛选：账号多了以后，"只看能用的 / 只看有问题的"比翻半天有用
        root.addView(Ui.segmented(this, new String[]{"全部", "可用", "有问题的"},
                scope, idx -> {
                    getIntent().putExtra("scope", idx);
                    recreate();
                }), Ui.lp(this, 12));

        list = Ui.column(this);
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(android.view.View.OVER_SCROLL_NEVER);
        sv.addView(list);
        root.addView(sv, Ui.fill(this, 14));
        // 底层强度 0.65 —— 账号列表：中等
        setContentView(Ui.glassScaffold(this, root, 0.65f));
        if (vendor == null) renderVendors();
        else renderVendorAccounts();
        MainActivity.refreshCreditsAsync(this, this::refreshCurrent);
    }

    /** Picks among the vendors you actually have, then opens their official site. */
    private void showVendorHomes() {
        LinearLayout box = Ui.column(this);
        java.util.LinkedHashMap<String, Config.Provider> seen = new java.util.LinkedHashMap<>();
        for (Config.Provider p : Gateway.config().providers) {
            String key = MainActivity.vendorOf(p);
            if (!seen.containsKey(key)) seen.put(key, p);
        }
        if (seen.isEmpty()) {
            box.addView(Ui.label(this, "还没有账号。"));
        }
        for (java.util.Map.Entry<String, Config.Provider> e : seen.entrySet()) {
            final Config.Provider p = e.getValue();
            String url = com.cpa.gateway.core.VendorHome.of(p);
            boolean domestic = MainActivity.isDomestic(p.type, e.getKey());
            String site = url.isEmpty() ? "没有可打开的官网"
                    : url.replaceFirst("^https?://", "").replaceAll("/.*$", "");
            LinearLayout row = Ui.listRow(this, e.getKey(),
                    (domestic ? "国内 · " : "国外 · ") + site);
            row.setOnClickListener(v -> BrowseActivity.openChoice(this, com.cpa.gateway.core.VendorHome.of(p)));
            box.addView(row, Ui.lp(this, 8));
        }
        Ui.dialog(this, "打开官网（充值 / 管理）", Ui.scroller(this, box), "取消", null, null, null);
    }

    private void refreshCurrent() {
        if (vendor == null) renderVendors();
        else renderVendorAccounts();
    }

    private List<Config.Provider> usable() {
        List<Config.Provider> out = new ArrayList<>();
        for (Config.Provider p : Gateway.config().providers) {
            if (MainActivity.unusableReason(p) == null) out.add(p);
        }
        return out;
    }

    private List<Config.Provider> parked() {
        List<Config.Provider> out = new ArrayList<>();
        for (Config.Provider p : Gateway.config().providers) {
            if (MainActivity.unusableReason(p) != null) out.add(p);
        }
        return out;
    }

    /** Level one: one button per vendor. */
    private void renderVendors() {
        list.removeAllViews();
        boolean onlyParked = scope == 2;
        List<Config.Provider> pool = scope == 0
                ? new ArrayList<>(Gateway.config().providers)
                : (onlyParked ? parked() : usable());
        if (pool.isEmpty()) {
            list.addView(Ui.label(this, onlyParked
                    ? "没有暂不可用的账号，都正常。"
                    : "现在没有可用账号，点右上角「＋ 添加」。"));
        }
        for (java.util.Map.Entry<String, List<Config.Provider>> group
                : MainActivity.groupByVendor(pool).entrySet()) {
            list.addView(vendorCard(group.getKey(), group.getValue()), Ui.lp(this, 8));
        }

        if (scope != 0) return;          // 筛了就看这一类，不再挂底部那张总卡

        List<Config.Provider> parked = parked();
        LinearLayout foot = Ui.row(this);
        foot.setBackground(GlassDrawable.panel(Ui.dp(this, 18)));
        int pad = Ui.dp(this, 14);
        foot.setPadding(pad, pad, pad, pad);
        LinearLayout col = Ui.column(this);
        col.addView(Ui.text(this, parked.size() + " 个账号暂不可用", 14f, Ui.TEXT, true));
        col.addView(Ui.label(this, parked.isEmpty() ? "没有不可用的账号" : "点这里看原因"), Ui.lp(this, 2));
        foot.addView(col, Ui.weight(this, 1f, 0));
        if (!parked.isEmpty()) {
            foot.addView(Ui.text(this, "›", 20f, Ui.MUTED, false));
            foot.setClickable(true);
            Ui.pressable(foot);
            foot.setOnClickListener(v -> startActivity(new Intent(this, UnusableActivity.class)));
        }
        list.addView(foot, Ui.lp(this, 18));
    }

    /** One vendor = one button, with how many accounts and how much quota sits behind it. */
    private android.view.View vendorCard(String vendorName, List<Config.Provider> accounts) {
        LinearLayout row = Ui.row(this);
        row.setBackground(GlassDrawable.panel(Ui.dp(this, 20)));
        int pad = Ui.dp(this, 14);
        row.setPadding(pad, pad, pad, pad);

        TextView avatar = Ui.avatar(this, accounts.get(0).type);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42));
        alp.rightMargin = Ui.dp(this, 12);
        row.addView(avatar, alp);

        LinearLayout info = Ui.column(this);
        info.addView(Ui.text(this, vendorName, 15.5f, Ui.TEXT, true));
        StringBuilder sub = new StringBuilder(accounts.size() + " 个账号");
        int models = 0;
        double credits = -1;
        for (Config.Provider p : accounts) {
            models += Math.max(0, p.models.size() - p.badModels.size());
            if (p.credits >= 0) credits = (credits < 0 ? 0 : credits) + p.credits;
        }
        sub.append(" · ").append(models).append(" 个模型");
        /* 积分这个数字**只说一半会误导**（4.53.2）。
         *
         * 真机实测：账号卡片写着"积分 500"，可每个模型发出去都回
         * `14018 额度已用尽`。原因是余额读的是资源包的 `CapacityRemain`，
         * 而真正决定能不能用的是同一个包的 `CycleCapacityRemain`（那个是 0）。
         * 用户看到"还有 500"，第一反应是"网关坏了"，而不是"该换号了"。
         *
         * 所以：额度在**正被上游拒绝**时，不再单独显示那个数字，
         * 而是说清"显示有、但实际被拒"。数字与事实不矛盾时才照常显示。 */
        long nowSecC = System.currentTimeMillis() / 1000L;
        boolean creditsRejected = false;
        for (Config.Provider p : accounts) {
            if (p.throttleUntil > nowSecC && p.throttleNote != null
                    && p.throttleNote.contains("额度")) {
                creditsRejected = true;
                break;
            }
        }
        if (credits >= 0 && creditsRejected) {
            sub.append(" · 积分 ").append((long) credits).append("（上游仍判额度耗尽）");
        } else if (credits >= 0) {
            sub.append(" · 积分 ").append((long) credits);
        }
        /* 限流提示：以前这里看不到任何原因，用户只知道"模型用不了"。
           只统计"还没到恢复时刻"的，过期的自然不计。 */
        long nowSec = System.currentTimeMillis() / 1000L;
        int throttled = 0;
        int blocked = 0;
        for (Config.Provider p : accounts) {
            if (p.throttleUntil > nowSec) throttled++;
            /* 凭据解不开 / 上游限制：这两种账号在池子里是死的，必须让用户看见原因，
               否则表现就是"随机某个模型用不了"，根本猜不到要去重新登录。 */
            if (p.credentialsUnreadable || p.restricted()) blocked++;
        }
        TextView subView = Ui.label(this, sub.toString());
        if (throttled > 0) {
            sub.append(" · ⏳ ").append(throttled).append(" 个限流中（可换号）");
            subView = Ui.label(this, sub.toString());
            subView.setTextColor(0xFFFFB454);
        }
        if (blocked > 0) {
            sub.append(" · ⛔ ").append(blocked).append(" 个不可用（凭据解不开或被上游限制）");
            subView = Ui.label(this, sub.toString());
            subView.setTextColor(0xFFFF6B6B);
        }
        info.addView(subView, Ui.lp(this, 3));
        row.addView(info, Ui.weight(this, 1f, 0));
        row.addView(Ui.text(this, "›", 20f, Ui.MUTED, false));
        row.setClickable(true);
        Ui.pressable(row);
        row.setOnClickListener(v -> {
            vendor = vendorName;
            getIntent().putExtra(EXTRA_VENDOR, vendorName);
            build();
        });
        return row;
    }

    /**
     * "+ 添加" inside a group adds another account of *that* group:
     * named relay site -> that site's token page; token vendor -> its own login screen; the generic
     * relay bucket -> the custom-relay page (its entries are plain API endpoints, no panel to open).
     */
    /** 一键添加：对话框和逻辑都在 QuickAdd 里，主页用的是同一个。 */
    private void quickAdd() {
        com.cpa.gateway.QuickAdd.dialog(this, this::build);
    }

    private void addInsideGroup() {
        if (vendor == null) {
            openChooser();
            return;
        }
        String site = relaySiteOfCurrentGroup();
        if (site != null) {
            Intent browse = new Intent(this, BrowseActivity.class);
            browse.putExtra(BrowseActivity.EXTRA_URL, site + "/console/token");
            startActivity(browse);
            return;
        }
        java.util.List<Config.Provider> accounts =
                MainActivity.accountsOfVendor(vendor, Gateway.config().providers);
        // WorkBuddy 特判：不能在分组里直接进登录页。
        //
        // vendorLoginIntent 那条路对 WorkBuddy 是"从已有账号取一个 realm 就进登录页"
        // （默认 cn，且没有区域选择），于是国际版用户在 WorkBuddy 分组里点「＋ 添加」
        // 永远只能进国内版——真机反馈确认过。
        // 这里改为跳回主界面、直接弹区域选择（国内版 / 国际版）。
        if (MainActivity.V_WORKBUDDY.equals(vendor)) {
            Intent chooser = new Intent(this, MainActivity.class);
            chooser.putExtra(MainActivity.EXTRA_ADD_VENDOR, vendor);
            chooser.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(chooser);
            return;
        }
        Intent login = MainActivity.vendorLoginIntent(this, vendor, accounts);
        if (login != null) {
            startActivityForResult(login, REQ_ADD_ACCOUNT);
            return;
        }
        if (MainActivity.isGenericRelayVendor(vendor)) {
            startActivityForResult(new Intent(this, WebLoginActivity.class), REQ_ADD_ACCOUNT);
            return;
        }
        openChooser();
    }

    private void openChooser() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra(MainActivity.EXTRA_ADD_ACCOUNT, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(intent);
    }

    /**
     * Origin of the site behind the current group, but only for a *named* relay group: the generic
     * bucket holds arbitrary API endpoints (e.g. text.pollinations.ai), where "/console/token" is
     * not a console page at all.
     */
    private String relaySiteOfCurrentGroup() {
        if (vendor == null) return null;
        if (MainActivity.isGenericRelayVendor(vendor)) return null;
        for (Config.Provider p : MainActivity.accountsOfVendor(vendor, Gateway.config().providers)) {
            if (Config.TYPE_OPENAI.equals(p.type) || Config.TYPE_ANTHROPIC.equals(p.type)
                    || Config.TYPE_GEMINI.equals(p.type)) {
                String host = MainActivity.hostOf(p.baseUrl);
                if (!host.isEmpty()) return "https://" + host;
            }
        }
        return null;
    }

    /** Level two: the accounts of one vendor. */
    private void renderVendorAccounts() {
        list.removeAllViews();
        List<Config.Provider> pool = scope == 0
                ? new ArrayList<>(Gateway.config().providers)
                : (scope == 2 ? parked() : usable());
        List<Config.Provider> members = MainActivity.accountsOfVendor(vendor, pool);
        if (members.isEmpty()) {
            list.addView(Ui.label(this, scope == 2
                    ? "这个厂家没有暂不可用的账号。" : "这个厂家现在没有可用账号。"));
            return;
        }
        for (Config.Provider p : members) {
            list.addView(MainActivity.accountRow(this, p), Ui.lp(this, 8));
        }
    }
}
