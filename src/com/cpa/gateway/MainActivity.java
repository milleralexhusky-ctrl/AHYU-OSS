package com.cpa.gateway;

import com.cpa.gateway.core.Threads;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.cpa.gateway.core.Config;
import com.cpa.gateway.core.Detect;
import com.cpa.gateway.core.Gateway;
import com.cpa.gateway.core.Log;
import com.cpa.gateway.core.Upstreams;

import java.util.ArrayList;
import java.util.List;

/**
 * Home screen. Deliberately one page: status, the two one-tap actions, where clients connect, the
 * account list and the three entry points. Everything else lives behind a tap.
 */
public class MainActivity extends Activity {

    private static final int REQ_OAUTH_LOGIN = 9003;
    /** Set by other screens that want the add-account chooser to open immediately. */
    public static final String EXTRA_ADD_ACCOUNT = "add_account";
    /**
     * 打开"添加账号"流程时，直接落到某个厂商的登录步骤（而不是先弹通用选择列表）。
     *
     * 为什么需要：账号列表页的厂商分组里点「＋ 添加」时，原实现走
     * {@link #vendorLoginIntent}，而那条路对 WorkBuddy 是**直接从已有账号里取一个
     * realm 就进登录页**——默认 "cn"，且完全没有区域选择。于是国际版用户在
     * WorkBuddy 分组里点「＋ 添加」永远只能进国内版（真机反馈确认过）。
     * 现在这种情形改为跳回本页、直接弹区域选择对话框。
     */
    public static final String EXTRA_ADD_VENDOR = "add_vendor";

    private TextView statusPill;
    private TextView countsText;
    private TextView alertRow;
    private TextView accountButtonLabel;
    private TextView accountButtonHint;
    private TextView primaryButton;
    private TextView addressText;
    private TextView keyText;
    private android.app.Dialog currentDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 启动打点：把冷启动拆成几段打到日志里，免得优化靠猜
        long t0 = android.os.SystemClock.uptimeMillis();
        // 顺序有讲究：先 init 读到磁盘配置，darkSystemBars 里的 applyTheme 才能拿到真实的
        // 主题色与深浅模式（否则浅色模式下会按深色画一版底纹，还写进缓存）。
        Gateway.init(this);
        long t1 = android.os.SystemClock.uptimeMillis();
        Ui.darkSystemBars(this);
        installCrashLogger();
        buildUi();
        long t2 = android.os.SystemClock.uptimeMillis();
        try {
            refreshStatus();
            refreshCreditsAsync(this, this::refreshStatus);
        } catch (Throwable t) {
            Log.e("刷新界面失败：" + t);
        }
        long t3 = android.os.SystemClock.uptimeMillis();
        Log.i("启动打点：配置 " + (t1 - t0) + "ms · 主题+底纹+建界面 " + (t2 - t1)
                + "ms · 状态刷新 " + (t3 - t2) + "ms");
        askNotificationPermission();
        maybeOpenAddChooser(getIntent());
    }

    /** 上次应用的配色，用来发现"在设置页换了主题"。 */
    private String appliedTheme;

    @Override
    protected void onResume() {
        super.onResume();
        // 外观（主题色 + 深浅模式）是在设置页里改的：回到主页时发现变了就重建一次
        // （已创建的 drawable 与配色常量不会自己换）
        String theme = (Gateway.config().accentTheme == null ? "spring" : Gateway.config().accentTheme)
                + (Gateway.config().lightMode ? "|light" : "|dark");
        if (appliedTheme == null) {
            appliedTheme = theme;
        } else if (!appliedTheme.equals(theme)) {
            appliedTheme = theme;
            Log.i("外观已切换，重建界面：" + theme);
            recreate();
            return;
        }
        syncFromDisk();
        refreshStatus();
        startStatusPolling();
    }

    /*
     * 状态轮询：页面可见期间每 2 秒对齐一次真实状态。
     *
     * 为什么需要它：refreshStatus() 原本只有 8 个**事件驱动**的调用点（打开页面、
     * 点按钮、延时 700ms、从子页返回……）。但网关状态会在这期间自己变：
     *   · 保活 JobService 在后台把它拉起来（用户什么也没点，界面还写着「启动网关」）
     *   · 看门狗判定假活后重启
     *   · 用户在通知栏/磁贴里点了停止
     * 界面于是长期显示一个过时的状态 —— 上一版"第一次按启动无效"就是它最直接的后果：
     * 界面写着「启动网关」，真实状态已经是运行中。
     *
     * 成本可控的前提是 refreshStatus() 自带指纹比对（状态没变就直接 return，
     * 不拼字符串、不动 View），所以 2 秒一次的实际开销接近零。
     * 只在 onResume→onPause 之间跑，退到后台立刻停，不耗电。
     */
    private static final long STATUS_POLL_MS = 2000L;
    private final android.os.Handler pollHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean polling;

    private final Runnable pollTick = new Runnable() {
        @Override
        public void run() {
            if (!polling) return;
            try {
                refreshStatus();
            } catch (Throwable t) {
                Log.w("状态轮询失败（不影响使用）：" + t);
            }
            if (polling) pollHandler.postDelayed(this, STATUS_POLL_MS);
        }
    };

    private void startStatusPolling() {
        if (polling) return;
        polling = true;
        pollHandler.postDelayed(pollTick, STATUS_POLL_MS);
    }

    private void stopStatusPolling() {
        polling = false;
        pollHandler.removeCallbacks(pollTick);
    }

    @Override
    protected void onPause() {
        stopStatusPolling();
        super.onPause();
    }

    /** The file is the source of truth: never show a stale, emptier config than what is on disk. */
    private long lastConfigStamp = -1;

    private void syncFromDisk() {
        try {
            // 磁盘没动过就不读：原来每次回到界面都同步读 34KB 的 gateway.json 再解析一遍
            java.io.File f = new java.io.File(getFilesDir(), "gateway.json");
            long stamp = f.lastModified() * 31 + f.length();
            if (stamp == lastConfigStamp) return;
            lastConfigStamp = stamp;
            int before = Gateway.config().providers.size();
            Gateway.reload(this);
            int after = Gateway.config().providers.size();
            if (before != after) Log.i("界面同步磁盘：账号 " + before + " → " + after);
        } catch (Throwable t) {
            Log.w("界面同步磁盘失败：" + t);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        maybeOpenAddChooser(intent);
    }

    private void maybeOpenAddChooser(Intent intent) {
        if (intent == null) return;
        final String vendor = intent.getStringExtra(EXTRA_ADD_VENDOR);
        boolean plain = intent.getBooleanExtra(EXTRA_ADD_ACCOUNT, false);
        if (!plain && (vendor == null || vendor.isEmpty())) return;
        intent.removeExtra(EXTRA_ADD_ACCOUNT);
        intent.removeExtra(EXTRA_ADD_VENDOR);
        if (vendor != null && !vendor.isEmpty()) {
            // 厂商分组里点「＋ 添加」：直接落到该厂商的登录步骤。
            // WorkBuddy 会弹区域选择（国内版 / 国际版），其余厂商各自的登录页。
            new android.os.Handler(getMainLooper()).postDelayed(() -> {
                if (V_WORKBUDDY.equals(vendor)) {
                    // loginWorkBuddy 内部会弹区域选择，并在选中后 startActivityForResult
                    // 到 WorkBuddyLoginActivity。后者登录成功会自行注册账号
                    // （WorkBuddy.registerProvider），所以这里不需要额外的结果处理。
                    loginWorkBuddy(null);
                } else {
                    Intent login = vendorLoginIntent(this, vendor,
                            accountsOfVendor(vendor, Gateway.config().providers));
                    if (login != null) {
                        startActivityForResult(login, ProviderEditor.REQ_WB_LOGIN);
                    } else {
                        addAccountDialog();
                    }
                }
            }, 250);
            return;
        }
        new android.os.Handler(getMainLooper()).postDelayed(this::addAccountDialog, 250);
    }



    /* ----------------------------------------------------------------- layout */

    private void buildUi() {
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Color.TRANSPARENT);
        root.setPadding(Ui.dp(this, 18), Ui.dp(this, 22), Ui.dp(this, 18), Ui.dp(this, 14));

        // 主标题也收进导航层玻璃栏，和子页面统一
        LinearLayout titleRow = Ui.row(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setBackground(Ui.navGlass(this, 22));
        titleRow.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
        LinearLayout titleCol = Ui.column(this);
        titleCol.addView(Ui.artisticTitle(this, "CLIProxy"));
        titleRow.addView(titleCol, Ui.weight(this, 1f, 0));
        statusPill = Ui.pill(this, "已停止", Ui.RED, Ui.RED_SOFT);
        titleRow.addView(statusPill);
        root.addView(titleRow);

        LinearLayout statusInner = Ui.column(this);
        countsText = Ui.text(this, "", 13f, Ui.MUTED, false);
        countsText.setGravity(Gravity.LEFT);
        statusInner.addView(countsText, Ui.lp(this, 2));

        // 账号出问题的醒目提示：以前只有 countsText 里"暂不可用 N"几个字，藏在一行小字里
        alertRow = Ui.text(this, "", 13f, Ui.TEXT, true);
        alertRow.setVisibility(android.view.View.GONE);
        alertRow.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        alertRow.setBackground(Ui.round(this, Ui.RED_SOFT, 14));
        alertRow.setOnClickListener(v -> startActivity(new Intent(this, UnusableActivity.class)));
        statusInner.addView(alertRow, Ui.lp(this, 8));
        primaryButton = Ui.primaryButton(this, "启动网关");
        primaryButton.setOnClickListener(v -> toggleGateway());
        statusInner.addView(primaryButton, Ui.lp(this, 14));
        LinearLayout quick = Ui.row(this);
        TextView tasks = Ui.accentChipButton(this, "一键做任务");
        tasks.setOnClickListener(v -> runDailyTasks());
        TextView check = Ui.outlineButton(this, "一键接入检测");
        check.setOnClickListener(v -> oneKeySetup());
        LinearLayout.LayoutParams q1 = Ui.weight(this, 1f, 0);
        q1.rightMargin = Ui.dp(this, 8);
        quick.addView(tasks, q1);
        quick.addView(check, Ui.weight(this, 1f, 0));
        statusInner.addView(quick, Ui.lp(this, 10));
        root.addView(Ui.card(this, statusInner), Ui.lp(this, 16));

        LinearLayout conn = Ui.column(this);
        LinearLayout addrRow = Ui.row(this);
        addressText = Ui.mono(this, "", 13f);
        addrRow.addView(addressText, Ui.weight(this, 1f, 0));
        TextView copyAddr = Ui.ghostButton(this, "复制");
        copyAddr.setOnClickListener(v -> {
            copyText("http://127.0.0.1:" + Gateway.config().port + "/v1");
            toast("已复制地址");
        });
        addrRow.addView(copyAddr);
        conn.addView(addrRow);
        LinearLayout keyRow = Ui.row(this);
        keyText = Ui.mono(this, "", 13f);
        keyRow.addView(keyText, Ui.weight(this, 1f, 0));
        TextView copyKey = Ui.ghostButton(this, "复制");
        copyKey.setOnClickListener(v -> {
            copyText(Gateway.config().apiKey);
            toast("已复制密钥");
        });
        keyRow.addView(copyKey);
        conn.addView(keyRow, Ui.lp(this, 10));
        root.addView(Ui.card(this, conn), Ui.lp(this, 12));

        LinearLayout accHeader = Ui.row(this);
        accHeader.addView(Ui.sectionTitle(this, "账号"), Ui.weight(this, 1f, 0));
        TextView quickAdd = Ui.barButton(this, "一键添加");
        Ui.a11y(quickAdd, "一键添加账号：粘贴地址和密钥自动识别");
        quickAdd.setOnClickListener(v -> com.cpa.gateway.QuickAdd.dialog(this, this::recreate));
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        qlp.rightMargin = Ui.dp(this, 8);
        accHeader.addView(quickAdd, qlp);
        TextView add = Ui.accentChipButton(this, "＋ 添加账号");
        add.setOnClickListener(v -> addAccountDialog());
        accHeader.addView(add);
        root.addView(accHeader, Ui.lp(this, 18));

        LinearLayout accountButton = Ui.row(this);
        accountButton.setBackground(GlassDrawable.neutral(Ui.dp(this, 20), true));
        int abPad = Ui.dp(this, 16);
        accountButton.setPadding(abPad, abPad, abPad, abPad);
        LinearLayout abCol = Ui.column(this);
        accountButtonLabel = Ui.text(this, "查看全部账号", 15f, Ui.TEXT, true);
        abCol.addView(accountButtonLabel);
        accountButtonHint = Ui.text(this, "", 12f, Ui.MUTED, false);
        abCol.addView(accountButtonHint, Ui.lp(this, 3));
        accountButton.addView(abCol, Ui.weight(this, 1f, 0));
        TextView chevron = Ui.text(this, "›", 22f, Ui.MUTED, false);
        Ui.a11y(chevron, "查看全部账号");
        accountButton.addView(chevron);
        accountButton.setClickable(true);
        Ui.pressable(accountButton);
        accountButton.setOnClickListener(v -> startActivity(new Intent(this, AccountsActivity.class)));
        root.addView(accountButton, Ui.lp(this, 8));
        root.addView(new android.view.View(this), Ui.fill(this, 0));

        // 底部导航栏：也是导航层玻璃，按钮用栏内样式（不在玻璃里再套玻璃）
        LinearLayout bottom = Ui.row(this);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setBackground(Ui.navGlass(this, 22));
        int bp = Ui.dp(this, 6);
        bottom.setPadding(bp, bp, bp, bp);
        TextView models = Ui.barButton(this, "模型");
        models.setOnClickListener(v -> startActivity(new Intent(this, ModelsActivity.class)));
        TextView logs = Ui.barButton(this, "日志");
        logs.setOnClickListener(v -> startActivity(new Intent(this, LogActivity.class)));
        TextView settings = Ui.barButton(this, "设置");
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        LinearLayout.LayoutParams b1 = Ui.weight(this, 1f, 0);
        b1.rightMargin = Ui.dp(this, 8);
        LinearLayout.LayoutParams b2 = Ui.weight(this, 1f, 0);
        b2.rightMargin = Ui.dp(this, 8);
        bottom.addView(models, b1);
        bottom.addView(logs, b2);
        bottom.addView(settings, Ui.weight(this, 1f, 0));
        root.addView(bottom, Ui.lp(this, 12));

        // 底层强度 1.00 —— 主界面：空间多、文字少，底层是主角
        setContentView(Ui.glassScaffold(this, root, 1.00f));
    }

    /* ------------------------------------------------------------------ state */

    // 包级可见：ProviderEditor 导入账号后要刷新主页状态（4.28.0 引入的调用）
    /*
     * ------------------------------------------------------------ 状态刷新的增量判据
     *
     * refreshStatus() 有 8 个调用点，全部跑在主线程。它每次都：
     *   1. 遍历全部账号跑 unusableReason()
     *   2. 无条件打一条 Log.i（日志有 I/O 成本）
     *   3. 无条件 setText —— 即使内容没变，也会触发 requestLayout/invalidate
     *
     * 绝大多数时候状态根本没变。所以加一道指纹比对：状态没变就直接返回。
     * 指纹用 long 而不是 String —— 拼字符串本身就是要避免的开销之一。
     * 各项用素数乘法混合（标准 hash combine），冲突概率足够低。
     */

    /** 上一次刷新时的状态指纹。 */
    private long lastStatusPrint;

    void refreshStatus() {
        Config cfg = Gateway.config();
        boolean running = Gateway.isRunning();

        /*
         * 指纹覆盖所有会影响界面文字的量。注意各 provider 的关键字段都要在，
         * 否则会出现"新增了账号但界面不刷新"。
         */
        long print = 17;
        print = print * 31 + (running ? 1 : 0);
        print = print * 31 + cfg.port;
        print = print * 31 + (cfg.apiKey == null ? 0 : cfg.apiKey.hashCode());
        print = print * 31 + (cfg.lanAccess ? 1 : 0);
        /*
         * usable 缓存 unusableReason(p) 的结果。
         *
         * 这个判断两个地方都要用（算指纹、算计数），而它内部可能要拼字符串。
         * 原先会算两遍；这里一次算完存数组，两处复用。
         */
        final int nProviders = cfg.providers.size();
        final boolean[] usableArr = new boolean[nProviders];
        print = print * 31 + nProviders;
        for (int i = 0; i < nProviders; i++) {
            Config.Provider p = cfg.providers.get(i);
            usableArr[i] = unusableReason(p) == null;
            print = print * 31 + (p.enabled ? 1 : 0);
            print = print * 31 + p.models.size();
            print = print * 31 + p.badModels.size();
            print = print * 31 + (usableArr[i] ? 1 : 0);
        }
        if (print == lastStatusPrint) {
            return;
        }
        lastStatusPrint = print;

        if (statusPill != null) {
            statusPill.setText("●  " + (running ? "运行中" : "已停止"));
            statusPill.setTextColor(running ? Ui.GREEN : Ui.RED);
            statusPill.setBackground(Ui.round(this, running ? Ui.GREEN_SOFT : Ui.RED_SOFT, 999));
        }
        if (primaryButton != null) primaryButton.setText(running ? "停止网关" : "启动网关");
        if (addressText != null) addressText.setText("http://127.0.0.1:" + cfg.port + "/v1");
        if (keyText != null) {
            String k = cfg.apiKey;
            keyText.setText(k.isEmpty() ? "（未设置密钥）"
                    : (k.length() > 10 ? k.substring(0, 6) + "••••••" + k.substring(k.length() - 4) : k));
        }
        if (countsText != null) {
            int usable = 0;
            int parked = 0;
            int modelsAll = 0;      // 对外提供的模型（跑不通的不算 + 虚拟模型），和客户端拉到的一致
            int modelsUsable = 0;   // 其中此刻真正能调的
            for (int i = 0; i < usableArr.length; i++) {
                Config.Provider p = cfg.providers.get(i);
                if (p.enabled) modelsAll += p.models.size() - p.badModels.size();
                if (usableArr[i]) {
                    usable++;
                    modelsUsable += p.models.size() - p.badModels.size();
                } else {
                    parked++;
                }
            }
            if (alertRow != null) {
                if (parked > 0) {
                    alertRow.setVisibility(android.view.View.VISIBLE);
                    alertRow.setText("⚠  " + parked + " 个账号需要处理（登录过期 / 上游调不通） · 点这里");
                } else {
                    alertRow.setVisibility(android.view.View.GONE);
                }
            }
            modelsAll += com.cpa.gateway.core.Router.virtuals().length;   // auto / auto-code / auto-long
            Log.i("界面看到：配置里 " + cfg.providers.size() + " 个账号（可用 " + usable
                    + "，暂不可用 " + parked + "）·模型 " + modelsAll + "（可用 " + modelsUsable + "）");
            // 客户端按"账号/模型"逐条拿到，这里口径保持一致
            countsText.setText(modelsAll + " 个模型 · " + usable + " 个账号可用"
                    + (cfg.lanAccess ? "  ·  局域网可访问" : ""));
            if (accountButtonLabel != null) accountButtonLabel.setText("查看全部账号（" + cfg.providers.size() + "）");
            if (accountButtonHint != null) {
                accountButtonHint.setText(parked == 0
                        ? usable + " 个可用"
                        : usable + " 个可用 · " + parked + " 个暂不可用");
            }
        }
    }


    private static volatile long lastCreditsAt = 0L;

    /**
     * Pulls each account's own credit balance in the background (throttled). Accounts whose upstream
     * reports nothing keep -1 and simply show no number, instead of inheriting someone else's total.
     */
    static void refreshCreditsAsync(final android.app.Activity a, final Runnable then) {
        long now = System.currentTimeMillis();
        if (now - lastCreditsAt < 5 * 60 * 1000L) {
            if (then != null) then.run();
            return;
        }
        lastCreditsAt = now;
        Threads.io(() -> {
            try {
                // 回环调用走统一 Http 层（原来手写 HttpURLConnection，每次建连）
                java.util.Map<String, String> h = new java.util.HashMap<>();
                h.put("Authorization", "Bearer " + Gateway.config().apiKey);
                com.cpa.gateway.core.Http.call("POST",
                        "http://127.0.0.1:" + Gateway.config().port + "/v1/credits/refresh",
                        h, "{}".getBytes("UTF-8"), "application/json", 8000, 60000, false);
                Log.i("各账号积分已刷新");
            } catch (Exception e) {
                Log.w("刷新积分失败：" + e);
            }
            if (then != null) a.runOnUiThread(then);
        });
    }

    /** Non-null reason means the account cannot serve right now and lives on its own screen. */
    public static String unusableReason(Config.Provider p) {
        if (!p.enabled) return "已停用";
        long now = System.currentTimeMillis() / 1000;
        if (p.lastErrorAt > 0 && now - p.lastErrorAt < 30 * 60 && p.lastError != null && !p.lastError.isEmpty()) {
            /* 按失败大类给出"该做什么"，而不是让用户自己读上游英文原文。
               分类逻辑在 Http.classifyFailure，与 Throttled.kind 共用一套判断，
               避免"退避一层说 auth、界面一层说 network"这种前后不一致。 */
            com.cpa.gateway.core.Http.FailureKind kind =
                    com.cpa.gateway.core.Http.classifyFailure(-1,
                            new IllegalStateException(p.lastError));
            String advice;
            switch (kind) {
                case AUTH:   advice = "凭证失效，请重新登录"; break;
                case QUOTA:  advice = "额度耗尽，等恢复或换号"; break;
                case RATE:   advice = "被限流，稍后自动恢复"; break;
                case NETWORK: advice = "网络不通，账号本身没问题"; break;
                case UPSTREAM: advice = "上游故障，稍后再试"; break;
                case REQUEST: advice = "请求被拒，检查模型名/参数"; break;
                default: advice = "最近调用失败"; break;
            }
            String reason = p.lastError;
            if (reason.length() > 100) reason = reason.substring(0, 100) + "…";
            return advice + "（" + reason + "）";
        }
        if (!p.models.isEmpty() && p.badModels.size() >= p.models.size()) {
            return "全部 " + p.models.size() + " 个模型都调不通";
        }
        return null;
    }

    /**
     * 凭证快要过期时的提前提醒（非空 = 建议现在就去重新登录）。
     *
     * 为什么需要：上游 access_token 有的约 28 天、有的 1 小时且**没有刷新接口**，
     * 到期后的表现是一连串调用失败 —— 用户往往是在"客户端突然报错"时才发现的。
     * 这里从 credentials blob 里把各家自己存的 expires_at 读出来，提前 3 天提示，
     * 让到期变成一件"提前知道、顺手处理"的事。
     *
     * 只做提醒、不判定账号不可用：还有 3 天时账号是完全能用的，
     * 放进"暂不可用"列表反而是误报。
     */
    public static String credentialExpiringSoon(Config.Provider p) {
        try {
            long exp = credentialExpiresAt(p);
            if (exp <= 0) return null;
            long now = System.currentTimeMillis() / 1000;
            long left = exp - now;
            if (left <= 0) return "登录已过期，请重新登录";
            if (left <= 3L * 24 * 3600) {
                return "登录还有约 " + (left / 3600) + " 小时过期，建议重新登录";
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 从 provider.credentials 里取出 access_token 的到期时间（epoch 秒）；取不到返回 0。
     *
     * 各家把凭据放在同一个 credentials JSON 字符串里，字段名不统一（expires_at 是主流），
     * JWT 类凭据（Codex/Cline 等）则要从 payload 的 exp 里读。两种都试一遍。
     */
    static long credentialExpiresAt(Config.Provider p) {
        if (p == null || p.credentials == null || p.credentials.isEmpty()) return 0L;
        String raw = p.credentials;
        try {
            org.json.JSONObject o = new org.json.JSONObject(raw);
            /* 1) 显式 expires_at / expiresAt（秒或毫秒都兼容） */
            long v = 0L;
            if (o.has("expires_at")) v = o.optLong("expires_at", 0L);
            else if (o.has("expiresAt")) v = o.optLong("expiresAt", 0L);
            if (v > 0) return v > 10_000_000_000L ? v / 1000 : v;
            /* 2) 嵌套结构：有些类型把账号数组放在 accounts / tokens 里 */
            for (String key : new String[]{"access_token", "token", "accessToken", "id_token"}) {
                String tok = o.optString(key, "");
                long fromJwt = jwtExpSeconds(tok);
                if (fromJwt > 0) return fromJwt;
            }
            for (String arr : new String[]{"accounts", "tokens"}) {
                org.json.JSONArray a = o.optJSONArray(arr);
                if (a == null || a.length() == 0) continue;
                org.json.JSONObject first = a.optJSONObject(0);
                if (first == null) continue;
                if (first.has("expires_at")) {
                    long e = first.optLong("expires_at", 0L);
                    if (e > 0) return e > 10_000_000_000L ? e / 1000 : e;
                }
                long fromJwt = jwtExpSeconds(first.optString("access_token", ""));
                if (fromJwt > 0) return fromJwt;
            }
        } catch (Throwable ignored) {
            /* 不是 JSON（老格式的裸 token）：试一次 JWT */
        }
        return jwtExpSeconds(raw);
    }

    /** 从 JWT 的 payload 里读 exp（秒）；不是 JWT 或读不出就返回 0。 */
    static long jwtExpSeconds(String token) {
        if (token == null) return 0L;
        String t = token.trim();
        int p1 = t.indexOf('.');
        if (p1 <= 0) return 0L;
        int p2 = t.indexOf('.', p1 + 1);
        if (p2 <= p1) return 0L;
        try {
            byte[] payload = android.util.Base64.decode(
                    t.substring(p1 + 1, p2), android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP);
            org.json.JSONObject claims = new org.json.JSONObject(new String(payload, "UTF-8"));
            /* exp 可能是整数，也可能是浮点（部分实现带小数秒） */
            long exp = claims.optLong("exp", 0L);
            if (exp == 0L) {
                double d = claims.optDouble("exp", 0d);
                exp = (long) d;
            }
            return exp;
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /** Groups channels by vendor so the same vendor's accounts sit next to each other. */
    /** The accounts shown under a vendor card; one source of truth for card and detail page. */
    public static List<Config.Provider> accountsOfVendor(String vendor, List<Config.Provider> all) {
        List<Config.Provider> out = new ArrayList<>();
        java.util.LinkedHashMap<String, List<Config.Provider>> groups = groupByVendor(all);
        List<Config.Provider> direct = groups.get(vendor);
        if (direct != null) return direct;
        for (java.util.Map.Entry<String, List<Config.Provider>> e : groups.entrySet()) {
            if (e.getKey().equalsIgnoreCase(vendor)) return e.getValue();
        }
        // fallback: the caller may have passed a host instead of the display name
        String host = hostOf(vendor);
        for (Config.Provider p : all) {
            if (!host.isEmpty() && host.equalsIgnoreCase(hostOf(p.baseUrl))) out.add(p);
            else if (p.name != null && vendor != null && p.name.contains(vendor)) out.add(p);
        }
        return out;
    }

    /** Host of a channel's base URL, used to decide whether a relay deserves its own group. */
    static String hostOf(String baseUrl) {
        if (baseUrl != null && !baseUrl.contains("/") && baseUrl.contains(".")) return baseUrl.toLowerCase();
        if (baseUrl == null || baseUrl.isEmpty()) return "";
        String h = baseUrl.replaceFirst("^https?://", "");
        int slash = h.indexOf('/');
        if (slash >= 0) h = h.substring(0, slash);
        return h.toLowerCase();
    }

    /**
     * A relay host with several channels gets a vendor group of its own (like Tencent / ByteDance do);
     * a host with a single channel is simply folded into the generic relay bucket.
     */
    static String dynamicVendor(Config.Provider p, java.util.Map<String, Integer> hostCounts) {
        String base = vendorOf(p);
        if (!"OpenAI 兼容中转".equals(base) && !"Anthropic 兼容".equals(base)) return base;
        String host = hostOf(p.baseUrl);
        if (host.isEmpty()) return base;
        Integer n = hostCounts.get(host);
        if (n != null && n >= 2) {
            String site = "";
            try {
                org.json.JSONObject c = new org.json.JSONObject(
                        p.credentials == null || p.credentials.isEmpty() ? "{}" : p.credentials);
                site = com.cpa.gateway.core.Proto.optString(c, "site_name", "");
            } catch (Exception ignored) {
            }
            return site.isEmpty() ? "中转站 · " + host : site;
        }
        return base;
    }

    public static java.util.LinkedHashMap<String, List<Config.Provider>> groupByVendor(List<Config.Provider> providers) {
        java.util.TreeMap<String, List<Config.Provider>> buckets = new java.util.TreeMap<>(MainActivity::compareVendors);
        java.util.Map<String, Integer> hostCounts = new java.util.HashMap<>();
        for (Config.Provider p : providers) {
            String h = hostOf(p.baseUrl);
            if (h.isEmpty()) continue;
            Integer c = hostCounts.get(h);
            hostCounts.put(h, c == null ? 1 : c + 1);
        }
        for (Config.Provider p : providers) {
            String key = dynamicVendor(p, hostCounts);
            List<Config.Provider> list = buckets.get(key);
            if (list == null) {
                list = new ArrayList<>();
                buckets.put(key, list);
            }
            list.add(p);
        }
        java.util.LinkedHashMap<String, List<Config.Provider>> out = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, List<Config.Provider>> e : buckets.entrySet()) {
            e.getValue().sort((x, y) -> {
                String nx = x.name == null ? "" : x.name;
                String ny = y.name == null ? "" : y.name;
                return nx.compareToIgnoreCase(ny);
            });
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /* Vendor labels live here once: grouping and the per-group add flow must agree on them. */
    static final String V_WORKBUDDY = "\u817e\u8baf WorkBuddy / CodeBuddy";
    static final String V_TRAE = "\u5b57\u8282 Trae";
    static final String V_LOOMY = "\u8baf\u98de Loomy";
    static final String V_KIMI = "Kimi Code \u8d26\u53f7";
    static final String V_DEEPSEEK = "DeepSeek";
    static final String V_DOUBAO = "豆包 Doubao";
    static final String V_KILO = "Kilo 免费池";
    static final String V_OVH = "OVHcloud 免费池";
    static final String V_ANTIGRAVITY = "Google Antigravity";
    static final String V_XAI = "xAI Grok";
    static final String V_CLINE = "Cline";
    static final String V_CODEX = "ChatGPT\uff08Codex\uff09";
    static final String V_CLAUDE = "Claude";
    static final String V_GEMINI = "Google Gemini\uff08API Key\uff09";
    static final String V_ANTHROPIC = "Anthropic \u517c\u5bb9";
    static final String V_OPENAI = "OpenAI \u517c\u5bb9\u4e2d\u8f6c";

    /** Vendor bucket: relays pointing at a vendor's own host count as that vendor. */
    static String vendorOf(Config.Provider p) {
        String name = (p.name == null ? "" : p.name).toLowerCase();
        String base = (p.baseUrl == null ? "" : p.baseUrl).toLowerCase();
        boolean codebuddy = name.contains("workbuddy") || name.contains("codebuddy")
                || base.contains("codebuddy") || base.contains("workbuddy") || base.contains(":7863");
        if (Config.TYPE_WORKBUDDY.equals(p.type) || codebuddy) return V_WORKBUDDY;
        if (Config.TYPE_TRAE.equals(p.type) || name.contains("trae")) return V_TRAE;
        if (Config.TYPE_LOOMY.equals(p.type) || name.contains("loomy")) return V_LOOMY;
        if (Config.TYPE_KIMI.equals(p.type) || name.contains("kimi code")) return V_KIMI;
        if (Config.TYPE_DEEPSEEK.equals(p.type)
                || (name.contains("deepseek") && base.contains("chat.deepseek.com"))) return V_DEEPSEEK;
        if (Config.TYPE_DOUBAO.equals(p.type) || name.contains("doubao") || name.contains("豆包")) return V_DOUBAO;
        if (Config.TYPE_KILO.equals(p.type)) return V_KILO;
        if (Config.TYPE_OVH.equals(p.type)) return V_OVH;
        if (Config.TYPE_ANTIGRAVITY.equals(p.type) || name.contains("antigravity")) return V_ANTIGRAVITY;
        if (Config.TYPE_XAI.equals(p.type)) return V_XAI;
        if (Config.TYPE_CLINE.equals(p.type)) return V_CLINE;
        if (Config.TYPE_CODEX.equals(p.type)) return V_CODEX;
        if (Config.TYPE_CLAUDE.equals(p.type)) return V_CLAUDE;
        if (Config.TYPE_GEMINI.equals(p.type)) return V_GEMINI;
        if (Config.TYPE_ANTHROPIC.equals(p.type)) return V_ANTHROPIC;
        return V_OPENAI;
    }

    /** Domestic (needs no VPN) vs overseas: shown as separate groups everywhere. */
    static boolean isDomestic(String type, String name) {
        String t = type == null ? "" : type;
        String n = name == null ? "" : name.toLowerCase();
        if (Config.TYPE_WORKBUDDY.equals(t) || Config.TYPE_TRAE.equals(t)
                || Config.TYPE_LOOMY.equals(t) || Config.TYPE_KIMI.equals(t)
                || Config.TYPE_DEEPSEEK.equals(t) || Config.TYPE_DOUBAO.equals(t)) return true;
        if (Config.TYPE_ANTIGRAVITY.equals(t) || Config.TYPE_CLAUDE.equals(t)
                || Config.TYPE_CODEX.equals(t)) return false;
        for (String key : new String[]{"siliconflow", "moonshot", "bigmodel", "dashscope", "volces", "xiaomimimo",
                "minimax", "spark", "qianfan", "sensenova", "longcat", "modelscope",
                "silicon", "zhipu", "kimi", "qwen", "tencent", "baidu", "aliyun"}) {
            if (n.contains(key)) return true;
        }
        return false;
    }

    /** Small muted group heading used inside the pickers. */
    private void subHeader(LinearLayout list, String text) {
        LinearLayout row = Ui.column(this);
        row.addView(Ui.text(this, text, 12.5f, Ui.MUTED, true));
        LinearLayout.LayoutParams lp = Ui.lp(this, 12);
        lp.topMargin = Ui.dp(this, 6);
        list.addView(row, lp);
    }

    static int vendorRank(String vendor) {
        // the generic relay bucket always sits last: named sites and vendors come first
        if ("OpenAI 兼容中转".equals(vendor) || "Anthropic 兼容".equals(vendor)) return 60;
        if (vendor != null && vendor.startsWith("中转站 · ")) return 50;
        String[] order = {V_WORKBUDDY, V_TRAE, V_LOOMY, V_KIMI, V_DEEPSEEK, V_DOUBAO,
                V_KILO, V_OVH, V_ANTIGRAVITY, V_XAI, V_CLINE,
                "ChatGPT（Codex）", "Claude", "Google Gemini（API Key）",
                "OpenAI 兼容中转", "Anthropic 兼容"};
        for (int i = 0; i < order.length; i++) {
            if (order[i].equals(vendor)) return i;
        }
        return order.length;
    }

    static int compareVendors(String a, String b) {
        int ra = vendorRank(a);
        int rb = vendorRank(b);
        if (ra != rb) return Integer.compare(ra, rb);
        return a.compareToIgnoreCase(b);
    }

    private static int groupRank(String name) {
        if (name != null && name.startsWith("中转站 · ")) return 50;
        return vendorRank(name);
    }

    static android.view.View groupGap(android.app.Activity a) {
        android.view.View v = new android.view.View(a);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 14)));
        return v;
    }

    /** Small vendor heading with how many accounts sit under it. */
    static android.view.View groupHeader(android.app.Activity a, String vendor, int count) {
        LinearLayout row = Ui.row(a);
        row.addView(Ui.sectionTitle(a, vendor), Ui.weight(a, 1f, 0));
        row.addView(Ui.pill(a, count + " 个", Ui.MUTED, Ui.GLASS));
        row.setLayoutParams(Ui.lp(a, 2));
        return row;
    }

    static View accountRow(final android.app.Activity a, final Config.Provider p) {
        LinearLayout row = Ui.row(a);
        row.setBackground(GlassDrawable.panel(Ui.dp(a, 22)));
        int pad = Ui.dp(a, 14);
        row.setPadding(pad, pad, pad, pad);

        TextView avatar = Ui.avatar(a, p.type);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(Ui.dp(a, 42), Ui.dp(a, 42));
        alp.rightMargin = Ui.dp(a, 12);
        row.addView(avatar, alp);

        LinearLayout info = Ui.column(a);
        info.addView(Ui.oneLineName(a, displayName(p)));
        TextView sub = Ui.label(a, Ui.typeLabel(p.type) + " · " + sourceLabel(p));
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(sub, Ui.lp(a, 3));
        row.addView(info, Ui.weight(a, 1f, 0));

        LinearLayout stats = Ui.row(a);
        stats.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        stats.addView(Ui.stat(a, "个模型", String.valueOf(p.models.size())));
        if (p.credits >= 0) {
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sp.leftMargin = Ui.dp(a, 12);
            stats.addView(Ui.stat(a, "积分", String.valueOf((long) p.credits)), sp);
        }
        row.addView(stats);

        row.setClickable(true);
        Ui.pressable(row);
        row.setOnClickListener(v -> {
            Intent intent = new Intent(a, AccountDetailActivity.class);
            intent.putExtra(AccountDetailActivity.EXTRA_PROVIDER_ID, p.id);
            a.startActivity(intent);
        });
        return row;
    }

    /* -------------------------------------------------------------- one-click */

    /** Talks to our own gateway. Keeps the network details out of the UI code. */
    private static org.json.JSONObject request(String method, String path, String body) throws Exception {
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(
                "http://127.0.0.1:" + Gateway.config().port + path).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(8000);
        conn.setReadTimeout("GET".equals(method) ? 20000 : 240000);
        conn.setRequestProperty("Authorization", "Bearer " + Gateway.config().apiKey);
        if (body != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            byte[] payload = body.getBytes("UTF-8");
            conn.setFixedLengthStreamingMode(payload.length);
            conn.getOutputStream().write(payload);
        }
        int code = conn.getResponseCode();
        java.io.InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while (in != null && (n = in.read(buf)) > 0) bos.write(buf, 0, n);
        String text = new String(bos.toByteArray(), "UTF-8");
        if (code != 200) throw new IllegalStateException("HTTP " + code + " " + Upstreams.shortenPublic(text));
        return new org.json.JSONObject(text);
    }

    private static String secs(long ms) {
        return String.format(java.util.Locale.US, "%.1fs", ms / 1000.0);
    }

    /**
     * One-tap check, v2. The sweep runs inside the gateway and this screen only polls it: every
     * account is refreshed and probed in parallel, each probe carries its own timeout, and results
     * appear as they land. The old version waited on one synchronous call per account and could
     * stall for minutes on the first dead channel, which is what made it useless at this scale.
     */
    private void oneKeySetup() {
        if (!Gateway.isRunning()) startGateway();
        LinearLayout box = Ui.column(this);
        final TextView head = Ui.label(this, "正在启动检测…");
        final TextView body = Ui.mono(this, "", 12.5f);
        box.addView(head);
        box.addView(body, Ui.lp(this, 10));
        final android.app.Dialog dlg = Ui.dialog(this, "一键接入并检测", Ui.scroller(this, box),
                null, null, "关闭", null);
        currentDialog = dlg;
        final java.util.concurrent.atomic.AtomicBoolean alive = new java.util.concurrent.atomic.AtomicBoolean(true);
        dlg.setOnDismissListener(d -> {
            alive.set(false);
            Threads.io(() -> {
                try {
                    Detect.stop();
                } catch (Throwable ignored) {
                }
            });
        });
        Threads.io(() -> {
            try {
                // 直接调 Detect，不走 HTTP 的 /v1/debug/*：
                // 那些端点在正式包里是关着的（Debug.ON=false），所以以前点一次就是 404。
                // perAccount 3→2：实测 2 个模型就足以判断这个账号通不通，
                // 每多探一个都是实打实的等待（探测虽有 8 秒上限，但正常也要 1~4 秒）。
                // 并发 8 是 Detect.start 允许的上限，已是最大。
                org.json.JSONObject started = Detect.start(2, 8);
                final boolean mine = started.optBoolean("ok", false);
                if (!mine) {
                    // 后台巡检（每 6 小时一次）正在跑，用户这一下没能启动新的一轮。
                    // 如实说明"你在看的是后台那一轮"，而不是让弹窗停在一句
                    // 含糊的"已有一轮检测在进行中"——那样看起来就像点了没反应。
                    post(head, "后台巡检正在进行，下面是它的进度（不用重复点）");
                }
                String last = "";
                // do/while 保证**至少渲染一次**再判断是否结束。
                // 原来先取状态、再 break，若检测恰好在那一刻结束（或用户点的时候
                // 后台那轮刚好跑完），循环一次都不进，弹窗永远是空的——真机反馈确认过。
                do {
                    org.json.JSONObject st = Detect.status();
                    String render = renderDetect(st);
                    if (!render.equals(last)) {
                        last = render;
                        int nl = render.indexOf('\n');
                        final String h = nl < 0 ? render : render.substring(0, nl);
                        final String b = nl < 0 ? "" : render.substring(nl + 1);
                        runOnUiThread(() -> {
                            head.setText(h);
                            body.setText(b);
                        });
                    }
                    if (!st.optBoolean("running", false)) break;
                    Thread.sleep(700);
                } while (alive.get());
            } catch (Throwable t) {
                post(head, "检测中断：" + t.getMessage());
            }
        });
    }

    /** Upstream failures arrive as JSON blobs; keep the sentence a human can act on. */
    private static String tidyReason(String why) {
        if (why == null) return "";
        String w = why.trim();
        int m = w.indexOf("\"message\":\"");
        if (m >= 0) {
            int st = m + 11;
            int en = w.indexOf('"', st);
            if (en > st) w = w.substring(st, en);
        }
        if (w.length() > 120) w = w.substring(0, 120) + "\u2026";
        return w;
    }

    /** Detect snapshot -> text. First line is the header, the rest is the per-account list. */
    private static String renderDetect(org.json.JSONObject st) {
        boolean running = st.optBoolean("running", false);
        boolean cancelled = st.optBoolean("cancelled", false);
        int total = st.optInt("total");
        int done = st.optInt("done");
        StringBuilder sb = new StringBuilder();
        if (running) {
            String phase = "probe".equals(st.optString("phase")) ? "实测通道" : "刷新模型目录";
            sb.append("检测中 ·").append(phase).append(" ·").append(done).append("/").append(total)
                    .append(" ·已用 ").append(secs(st.optLong("elapsedMs")));
            String cur = st.optString("current", "");
            if (!cur.isEmpty()) sb.append("\n现在：").append(cur);
        } else {
            sb.append(cancelled ? "已停止" : "完成")
                    .append(" ·用时 ").append(secs(st.optLong("elapsedMs")))
                    .append(" ·实测通过 ").append(st.optInt("passed"))
                    .append(" /失败 ").append(st.optInt("failed"));
            if (st.optInt("hidden") > 0) {
                sb.append("\n新增标记 ").append(st.optInt("hidden")).append("个「目录里有但调不通」的模型（只是标记，不会从列表里拿掉）");
            }
        }
        if (!running && st.optInt("perAccount", 0) > 0) {
            sb.append("\n每账号只抽 ").append(st.optInt("perAccount"))
                    .append(" 个模型当样本（生图 / 生视频这类非聊天模型已跳过），不代表该账号全部模型");
        }
        org.json.JSONArray rows = st.optJSONArray("accounts");
        for (int i = 0; rows != null && i < rows.length(); i++) {
            org.json.JSONObject r = rows.optJSONObject(i);
            if (r == null) continue;
            if ("waiting".equals(r.optString("refresh"))) continue;
            sb.append('\n');
            if ("fail".equals(r.optString("refresh"))) {
                sb.append("❌ ").append(r.optString("name")).append(" ·目录刷新失败：")
                        .append(r.optString("refreshError"));
                continue;
            }
            if (r.optBoolean("catalogOnly")) {
                sb.append("\n⏸ ").append(r.optString("name"))
                        .append(" · 仅目录可用（推理被上游拒绝，已跳过刷新与探测；在账号详情里可关闭）");
                continue;
            }
            int ok = r.optInt("ok");
            int fail = r.optInt("fail");
            org.json.JSONArray probes = r.optJSONArray("probes");
            long best = -1;
            for (int k = 0; probes != null && k < probes.length(); k++) {
                org.json.JSONObject pr = probes.optJSONObject(k);
                if (pr == null || !pr.optBoolean("ok")) continue;
                long ms = pr.optLong("ms");
                if (best < 0 || ms < best) best = ms;
            }
            boolean blind = (ok + fail) == 0;
            sb.append(blind ? "⚠️ " : (fail == 0 ? "✅ " : (ok > 0 ? "⚠️ " : "❌ ")))
                    .append(r.optString("name")).append(" ·").append(ok).append('/').append(ok + fail)
                    .append("可用 ·目录 ").append(r.optInt("models")).append("个");
            if (blind) sb.append("（本轮一个模型都没测到：目录为空或被刷新覆盖，别把它当成可用）");
            if (best >= 0) sb.append(" ·最快 ").append(best).append("ms");
            for (int k = 0; probes != null && k < probes.length(); k++) {
                org.json.JSONObject pr = probes.optJSONObject(k);
                if (pr == null || pr.optBoolean("ok")) continue;
                // 先给一句归类（超时 / 鉴权失效 / 额度不足 / 该模型上游不支持 …），细节跟在后面
                String kind = pr.optString("kind", "");
                String detail = tidyReason(pr.optString("why"));
                if (detail.length() > 120) detail = detail.substring(0, 120) + "…";
                sb.append("\n   ✗ ").append(pr.optString("model")).append("：")
                        .append(kind.isEmpty() ? detail : (kind + "（" + detail + "）"));
            }
        }
        return sb.toString();
    }

    /** Channels whose daily chores this app can actually run. */
    private static boolean doesChores(Config.Provider p) {
        return Config.TYPE_WORKBUDDY.equals(p.type) || Config.TYPE_TRAE.equals(p.type)
                || Config.TYPE_LOOMY.equals(p.type);
    }

    /**
     * One-tap chores, v2. Only the accounts that really have chores are walked, one request per
     * account so the panel updates as each finishes. Relay / API-key channels are no longer printed
     * one row each - they collapse into a single line (tap through to the account list for detail).
     */
    private void runDailyTasks() {
        final List<Config.Provider> todo = new ArrayList<>();
        final List<Config.Provider> others = new ArrayList<>();
        for (Config.Provider p : Gateway.config().providers) {
            if (p.enabled && doesChores(p)) todo.add(p);
            else others.add(p);
        }
        LinearLayout box = Ui.column(this);
        final TextView head = Ui.label(this, "准备中…");
        final TextView body = Ui.mono(this, "", 12.5f);
        box.addView(head);
        box.addView(body, Ui.lp(this, 10));
        final android.app.Dialog dlg = Ui.dialog(this, "一键做任务", Ui.scroller(this, box),
                null, null, "关闭", null);
        currentDialog = dlg;
        final java.util.concurrent.atomic.AtomicBoolean alive = new java.util.concurrent.atomic.AtomicBoolean(true);
        dlg.setOnDismissListener(d -> alive.set(false));
        final String tail = others.isEmpty() ? ""
                : "\n——另外 " + others.size() + " 个通道（转发 / API Key）没有账号任务，已收起";
        head.setText("正在做…（0/" + todo.size() + "）· 任务一个个来，慢了请等它自己走完");
        if (todo.isEmpty()) {
            head.setText("没有可自动做任务的账号");
            body.setText(others.isEmpty() ? "" : skipDetail(others));
            return;
        }
        // 原来一个账号一个账号串行做：N 个账号 × 每次几十秒 = 几分钟。
        // 现在 3 路并行（每个账号的任务还是走自己上游，不互相抢；3 路是给上游留面子）。
        final int total = todo.size();
        final Object lock = new Object();
        final StringBuilder log = new StringBuilder();
        final double[] gained = {0};
        final int[] withTasks = {0};
        final java.util.concurrent.atomic.AtomicInteger done =
                new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.Semaphore sem = new java.util.concurrent.Semaphore(3);
        final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(total);
        for (int i = 0; i < total; i++) {
            final com.cpa.gateway.core.Config.Provider p = todo.get(i);
            Threads.io(() -> {
                try {
                    sem.acquire();
                } catch (InterruptedException ie) {
                    latch.countDown();
                    return;
                }
                try {
                    if (!alive.get()) return;
                    // 先报"轮到谁了"：任务本身慢（WorkBuddy 要走签到/接任务/交任务/领奖），
                    // 原来只在某个账号**完成后**才更新一次，所以看起来永远停在"准备中…"。
                    post(head, "正在做…（" + done.get() + "/" + total + "）· 正在执行：" + p.name);
                    StringBuilder one = new StringBuilder();
                    try {
                        // 直接在进程内执行该账号的每日例行任务（不经过 HTTP /v1/tasks/run 异步端点）：
                        // 1) 之前 /v1/tasks/run 改成异步只回 {"ok":true,"started":true}，导致 resp.optJSONArray("ran")
                        //    永远为 null，弹窗正文一片空白、积分恒为 +0；
                        // 2) 直接进程内调用还不依赖网关是否已启动，网关停止时点「一键做任务」也能正常执行。
                        org.json.JSONObject row = com.cpa.gateway.core.Server.runAccountChores(p);
                        if (row != null && row.length() > 0) {
                            String rendered = renderChores(row);
                            if (!rendered.isEmpty()) one.append(rendered);
                            synchronized (lock) {
                                gained[0] += row.optDouble("gained", 0);
                                withTasks[0]++;
                            }
                        }
                    } catch (Throwable t) {
                        one.append("【").append(p.name).append("】执行失败：")
                                .append(t.getMessage()).append("\n\n");
                    }
                    synchronized (lock) {
                        log.append(one);
                    }
                    int d = done.incrementAndGet();
                    post(head, "正在做…（" + d + "/" + total + "）· " + p.name + " 完成");
                    final String shown;
                    synchronized (lock) {
                        shown = log.toString();
                    }
                    runOnUiThread(() -> body.setText(shown));
                } finally {
                    sem.release();
                    latch.countDown();
                }
            });
        }
        Threads.io(() -> {
            try {
                latch.await();
            } catch (InterruptedException ignored) {
            }
            boolean finished = alive.get();
            final double g;
            final int w;
            synchronized (lock) {
                g = gained[0];
                w = withTasks[0];
            }
            // 显示**实际跑过的账号数**（total），不是"有任务可做"的账号数（w）。
            //
            // 原来用 w：某天所有任务都已完成时 w=0，于是显示"完成 · 0 个账号"，
            // 用户看到的第一反应是"一个账号都没识别到"（真机截图确认过）。
            // 实际上 10 个账号都跑了，只是今天没有可领的东西。
            // 有任务可做的数量仍然体现——它就在下面每行的明细里。
            post(head, String.format(java.util.Locale.US, "%s ·%d个账号 ·积分 %+.0f",
                    finished ? "完成" : "已停止", total, g));
            final String shown;
            synchronized (lock) {
                shown = log + tail;
            }
            runOnUiThread(() -> body.setText(shown));
            runOnUiThread(MainActivity.this::refreshStatus);
        });
    }

    private static String skipDetail(List<Config.Provider> others) {
        StringBuilder sb = new StringBuilder();
        for (Config.Provider p : others) {
            sb.append("· ").append(p.name).append("：没有账号任务（转发 / API Key 通道）\n");
        }
        return sb.toString().trim();
    }

    /**
     * 一个账号的例行结果。只显示真正发生了的事：
     * 没做成的任务、本来就没有任务的账号、已经签过的状态，都不占版面。
     */
    private static String renderChores(org.json.JSONObject r) {
        String name = r.optString("provider");
        if (r.has("error")) {
            return "【" + name + "】出错：" + r.optString("error") + "\n\n";
        }

        // 这个账号没有任何可执行动作（纯转发 / API Key 通道）→ 整条不显示
        boolean hasCheckin = r.has("checkin") || r.has("checkinError");
        org.json.JSONArray done = r.optJSONArray("done");
        org.json.JSONArray claimed = r.optJSONArray("claimed");
        boolean hasTasks = (done != null && done.length() > 0)
                || (claimed != null && claimed.length() > 0);
        if (!hasCheckin && !hasTasks && !r.has("creditsBefore")) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("【").append(name).append("】");

        // 签到：签过就只说一句
        org.json.JSONObject ci = r.optJSONObject("checkin");
        if (ci != null) {
            String msg = ci.optString("msg", "");
            // 优先用数据层的结构化字段判定，别去匹配上游文案——
            // 文案随时会被腾讯改，而且 code=10001 本身就同时代表
            // "今天已签到"（国内版 active=true）和"该区域没有签到活动"
            // （国际版 active=false）两种完全相反的情况。
            boolean unsupported = ci.optBoolean("unsupported", false);
            boolean doneToday = !unsupported
                    && (ci.optBoolean("ok") || ci.optBoolean("already", false)
                        || msg.contains("已签到") || msg.contains("已签"));
            if (unsupported) {
                // 国际版：上游没开签到活动（实测 active=false，daily-checkin 返回
                // "签到活动未开启或已过期"）。这里如实说明，不再静默抹掉——
                // 原来是把整条清空、用户只看到账号名后面空空如也，不知道发生了什么。
                sb.append("本区域暂无签到活动");
            } else if (doneToday) {
                sb.append("已签到");
                int streak = ci.optInt("streakDays", 0);
                if (streak > 0) sb.append("（连登 ").append(streak).append(" 天）");
            } else if (msg.contains("未开启") || msg.contains("过期")) {
                // 兜底：数据层没给出 unsupported、但上游文案明说活动不存在
                sb.append("本区域暂无签到活动");
            } else {
                sb.append("签到失败").append(msg.isEmpty() ? "" : "：" + msg);
            }
        } else if (r.has("checkinError")) {
            sb.append("签到失败");
        }

        // 任务：只列真正完成的，失败/不存在的略过
        if (hasTasks) {
            java.util.List<String> doneNames = new java.util.ArrayList<>();
            if (done != null) {
                for (int i = 0; i < done.length(); i++) {
                    org.json.JSONObject o = done.optJSONObject(i);
                    if (o == null) continue;
                    if (o.has("skipped")) continue;          // 已完成/不存在的不重复列
                    if (o.has("error")) continue;
                    int rep = o.optInt("reported", 0);
                    if (rep > 0) doneNames.add(taskLabel(o.optString("task")) + "×" + rep);
                }
            }
            java.util.List<String> gotList = new java.util.ArrayList<>();
            if (claimed != null) {
                for (int i = 0; i < claimed.length(); i++) {
                    org.json.JSONObject o = claimed.optJSONObject(i);
                    if (o == null) continue;
                    if (o.optInt("code_resp", -1) != 0) continue;
                    if (o.optBoolean("already", false)) continue;
                    double cr = o.optDouble("credit", 0);
                    if (cr > 0) gotList.add(taskLabel(o.optString("code")) + " +" + (long) cr);
                }
            }
            if (!doneNames.isEmpty() || !gotList.isEmpty()) {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '】') sb.append(" · ");
                if (!doneNames.isEmpty()) sb.append("完成 ").append(String.join("、", doneNames));
                if (!gotList.isEmpty()) {
                    if (!doneNames.isEmpty()) sb.append("，");
                    sb.append("领奖 ").append(String.join("、", gotList));
                }
            }
        }

        // WorkBuddy 连登兑换 / 抽奖 / 猫猫旅行 与 Loomy 宠物打工补充显示
        org.json.JSONObject streakObj = r.optJSONObject("streak");
        if (streakObj != null) {
            org.json.JSONArray redeemed = streakObj.optJSONArray("redeemed");
            org.json.JSONArray drawn = streakObj.optJSONArray("drawn");
            int redCount = redeemed == null ? 0 : redeemed.length();
            int drawCount = drawn == null ? 0 : drawn.length();
            if (redCount > 0 || drawCount > 0) {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '】') sb.append(" · ");
                if (redCount > 0) sb.append("连登兑换 ").append(redCount).append(" 档");
                if (drawCount > 0) {
                    if (redCount > 0) sb.append("、");
                    sb.append("抽奖 ").append(drawCount).append(" 次");
                }
            }
        }
        org.json.JSONObject travelObj = r.optJSONObject("travel");
        if (travelObj != null && !travelObj.has("skipped")) {
            if (travelObj.optInt("claim", -1) == 0) {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '】') sb.append(" · ");
                sb.append("旅行领奖");
            } else if (travelObj.optInt("depart", -1) == 0) {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '】') sb.append(" · ");
                sb.append("已派出猫猫旅行");
            }
        }
        org.json.JSONObject loomyDaily = r.optJSONObject("daily");
        if (loomyDaily != null) {
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '】') sb.append(" · ");
            sb.append("已执行每日打工");
        }

        // 积分变化：只有真的变了才显示
        double before = r.optDouble("creditsBefore", -1);
        double after = r.optDouble("creditsAfter", -1);
        double gained = r.optDouble("gained", 0);
        if (after >= 0) {
            sb.append("\n积分：").append((long) before).append(" → ").append((long) after);
            if (gained != 0) {
                sb.append("（").append(String.format(java.util.Locale.US, "%+.0f", gained)).append("）");
            }
        }

        // 什么都没发生时，这一条不显示
        String body = sb.toString();
        if (body.endsWith("】")) return "";
        return body + "\n\n";
    }

    /** 任务编码 → 中文短名。 */
    private static String taskLabel(String code) {
        if (code == null) return "";
        switch (code) {
            case "chat_5": return "聊天5次";
            case "Model_chat_GLM5.2": return "体验GLM-5.2";
            case "first_buddy": return "领养Buddy";
            case "RichMeow_Chat": return "桌面对话";
            default: return code;
        }
    }

    private void post(final TextView target, final String text) {
        runOnUiThread(() -> target.setText(text));
    }

    /* -------------------------------------------------------------- accounts */

    /** 添加账号 = four tappable entries; each one opens its own list. */
    private void addAccountDialog() {
        LinearLayout list = Ui.column(this);
        list.addView(entryCard("用账号登录", "WorkBuddy · Trae · Loomy · Kimi · Antigravity · Claude · ChatGPT",
                this::showAccountLogins), Ui.lp(this, 8));
        list.addView(entryCard("用 API 密钥", "OpenAI 兼容 · Claude · Gemini",
                this::showApiKeys), Ui.lp(this, 8));
        list.addView(entryCard("自定义中转站", "填网址 → 应用内浏览器登录 → 自动保存会话",
                () -> startActivityForResult(new Intent(this, WebLoginActivity.class), REQ_OAUTH_LOGIN)),
                Ui.lp(this, 8));
        list.addView(entryCard("导入", "从文件导入以前导出的账号",
                this::pickFile), Ui.lp(this, 8));
        currentDialog = Ui.dialog(this, "添加账号", Ui.scroller(this, list), "取消", null, null, null);
    }

    /** One tap-through entry in the first level. */
    private android.view.View entryCard(String title, String hint, final Runnable action) {
        LinearLayout row = Ui.row(this);
        row.setBackground(GlassDrawable.panel(Ui.dp(this, 20)));
        int pad = Ui.dp(this, 14);
        row.setPadding(pad, pad, pad, pad);
        LinearLayout info = Ui.column(this);
        info.addView(Ui.text(this, title, 16f, Ui.TEXT, true));
        TextView sub = Ui.label(this, hint);
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(sub, Ui.lp(this, 3));
        row.addView(info, Ui.weight(this, 1f, 0));
        row.addView(Ui.text(this, "›", 20f, Ui.MUTED, false));
        row.setClickable(true);
        Ui.pressable(row);
        row.setOnClickListener(v -> {
            if (currentDialog != null) currentDialog.dismiss();
            action.run();
        });
        return row;
    }

    /** Kilo 免费池：不需要登录/Key，点一下就把免费模型接进来。 */
    private void addKilo() {
        toast("正在接入 Kilo 免费池…");
        com.cpa.gateway.core.Threads.io(() -> {
            try {
                java.util.List<String> models = com.cpa.gateway.core.Kilo.fetchModels();
                org.json.JSONObject out = com.cpa.gateway.core.Kilo.registerProvider(models);
                com.cpa.gateway.core.Log.i("kilo 免费池已接入：" + out.optInt("models") + " 个模型");
                runOnUiThread(() -> {
                    toast("已接入 Kilo 免费池（" + out.optInt("models") + " 个模型）");
                    refreshStatus();
                });
            } catch (Throwable t) {
                com.cpa.gateway.core.Log.w("kilo 接入失败：" + t);
                runOnUiThread(() -> toast("接入失败：" + t.getMessage()));
            }
        });
    }

    /** OVHcloud 免费池：零凭证，点一下就把匿名层模型接进来。 */
    private void addOvh() {
        toast("正在接入 OVHcloud 免费池…");
        com.cpa.gateway.core.Threads.io(() -> {
            try {
                java.util.List<String> models = com.cpa.gateway.core.Ovh.fetchModels();
                org.json.JSONObject out = com.cpa.gateway.core.Ovh.registerProvider(models);
                com.cpa.gateway.core.Log.i("ovh 免费池已接入：" + out.optInt("models") + " 个模型");
                runOnUiThread(() -> {
                    toast("已接入 OVHcloud 免费池（" + out.optInt("models") + " 个模型）");
                    refreshStatus();
                });
            } catch (Throwable t) {
                com.cpa.gateway.core.Log.w("ovh 接入失败：" + t);
                runOnUiThread(() -> toast("接入失败：" + t.getMessage()));
            }
        });
    }

    /** Level two: the official-account logins. */
    private void showAccountLogins() {
        LinearLayout list = Ui.column(this);
        subHeader(list, "国内");
        // 描述里点明两个区域：原来只写"微信登录 · 每日签到送积分"（国内版的说法），
        // 国际版用户看到这行根本不知道还能选 workbuddy.ai，会以为没有国际版入口。
        // 点击后 loginWorkBuddy(null) 会弹区域选择（国内版 / 国际版）。
        choiceRow(list, "workbuddy", "WorkBuddy 账号", "国内版 codebuddy.cn · 国际版 workbuddy.ai（点击后选区域）",
                () -> loginWorkBuddy(null));
        choiceRow(list, "trae", "字节 Trae 账号", "浏览器登录 · SOLO 通道", () -> startActivityForResult(
                new Intent(this, TraeLoginActivity.class), REQ_OAUTH_LOGIN));
        choiceRow(list, "loomy", "讯飞 Loomy 账号", "手机号 + 验证码，积分制", () -> startActivityForResult(
                new Intent(this, LoomyLoginActivity.class), REQ_OAUTH_LOGIN));
        choiceRow(list, "kimi", "Kimi 账号", "网页登录 · 免费额度", () -> startActivityForResult(
                new Intent(this, KimiLoginActivity.class), REQ_OAUTH_LOGIN));
        choiceRow(list, "kilo", "Kilo 免费池", "零凭证 · 一键接入公开免费模型（不用登录）", this::addKilo);
        choiceRow(list, "ovh", "OVHcloud 免费池", "零凭证 · 匿名层含 gpt-oss-120b / Llama-3.3-70B（不用登录）", this::addOvh);
        choiceRow(list, "deepseek", "DeepSeek 账号", "网页登录 · 免费用网页聊天额度", () -> startActivityForResult(
                new Intent(this, DeepSeekLoginActivity.class), REQ_OAUTH_LOGIN));
        choiceRow(list, "doubao", "豆包 Doubao 账号", "网页登录 · 免费对话额度（不支持工具调用）", () -> startActivityForResult(
                new Intent(this, DoubaoLoginActivity.class), REQ_OAUTH_LOGIN));
        subHeader(list, "国外");
        choiceRow(list, "antigravity", "Google Antigravity", "Google 账号登录，免费 Gemini 额度",
                () -> startActivityForResult(new Intent(this, AntigravityLoginActivity.class), REQ_OAUTH_LOGIN));
        choiceRow(list, "xai", "Grok 账号（xAI）", "网页登录 · 在 grok.com 登录",
                () -> startActivityForResult(new Intent(this, XaiLoginActivity.class), REQ_OAUTH_LOGIN));
        choiceRow(list, "cline", "Cline 账号", "网页登录 · 免费档模型", () -> startActivityForResult(
                new Intent(this, ClineLoginActivity.class), REQ_OAUTH_LOGIN));
        choiceRow(list, "claude", "Claude 账号", "claude.ai 登录，Pro / Max 直接当 API 用", this::loginClaude);
        choiceRow(list, "codex", "ChatGPT 账号", "登录 ChatGPT，使用 GPT 系列", this::loginCodex);
        currentDialog = Ui.dialog(this, "用账号登录", Ui.scroller(this, list), "返回",
                this::addAccountDialog, null, null, this::addAccountDialog);
    }

    /** Level two: API-key providers. */
    private void showApiKeys() {
        LinearLayout list = Ui.column(this);
        choiceRow(list, "openai", "OpenAI 兼容中转", "OpenRouter / DeepSeek / 硅基流动 等 16 家预置",
                this::addOpenAiCompatible);
        choiceRow(list, "anthropic", "Claude（API Key）", "Anthropic 官方或中转的 Key", () -> {
            Config.Provider p = new Config.Provider();
            p.type = Config.TYPE_ANTHROPIC;
            p.name = "Claude";
            p.baseUrl = "https://api.anthropic.com";
            ProviderEditor.show(this, p, true, this::refreshStatus);
        });
        choiceRow(list, "gemini", "Google Gemini（API Key）", "Google AI Studio 的 Key", () -> {
            Config.Provider p = new Config.Provider();
            p.type = Config.TYPE_GEMINI;
            p.name = "Gemini";
            p.baseUrl = "https://generativelanguage.googleapis.com";
            ProviderEditor.show(this, p, true, this::refreshStatus);
        });
        currentDialog = Ui.dialog(this, "用 API 密钥", Ui.scroller(this, list), "返回",
                this::addAccountDialog, null, null, this::addAccountDialog);
    }

    private void choiceRow(LinearLayout list, String type, String title, String desc, final Runnable action) {
        LinearLayout row = Ui.row(this);
        row.setBackground(GlassDrawable.panel(Ui.dp(this, 18)));
        int pad = Ui.dp(this, 12);
        row.setPadding(pad, pad, pad, pad);
        TextView avatar = Ui.avatar(this, type);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36));
        alp.rightMargin = Ui.dp(this, 12);
        row.addView(avatar, alp);
        LinearLayout info = Ui.column(this);
        info.addView(Ui.text(this, title, 14.5f, Ui.TEXT, true));
        TextView sub = Ui.label(this, desc);
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(sub, Ui.lp(this, 3));
        row.addView(info, Ui.weight(this, 1f, 0));
        row.addView(Ui.text(this, "›", 18f, Ui.MUTED, false));
        row.setClickable(true);
        Ui.pressable(row);
        row.setOnClickListener(v -> {
            if (currentDialog != null) currentDialog.dismiss();
            action.run();
        });
        list.addView(row, Ui.lp(this, 8));
    }

    private void loginClaude() {
        Intent intent = new Intent(this, OAuthLoginActivity.class);
        intent.putExtra(OAuthLoginActivity.EXTRA_KIND, OAuthLoginActivity.KIND_CLAUDE);
        startActivityForResult(intent, REQ_OAUTH_LOGIN);
    }

    private void loginCodex() {
        Intent intent = new Intent(this, OAuthLoginActivity.class);
        intent.putExtra(OAuthLoginActivity.EXTRA_KIND, OAuthLoginActivity.KIND_CODEX);
        startActivityForResult(intent, REQ_OAUTH_LOGIN);
    }

    private void addOpenAiCompatible() {
        Config.Provider p = new Config.Provider();
        p.type = Config.TYPE_OPENAI;
        p.name = "OpenAI 兼容";
        choosePreset(p);
    }

    /**
     * The add flow that matches one vendor group. Token accounts open their own login screen; the
     * generic relay bucket has no panel of its own, so it goes to the custom-relay page.
     * Returns null when the caller should fall back to the generic chooser.
     */
    static Intent vendorLoginIntent(android.content.Context c, String vendor,
                                    java.util.List<Config.Provider> accounts) {
        if (vendor == null) return null;
        if (V_WORKBUDDY.equals(vendor)) {
            String realm = "cn";
            for (Config.Provider p : accounts) {
                try {
                    String r = com.cpa.gateway.core.WorkBuddy.Account.fromJson(p.credentials).realm;
                    if (r != null && !r.isEmpty()) {
                        realm = r;
                        break;
                    }
                } catch (Throwable ignored) {
                }
            }
            return new Intent(c, WorkBuddyLoginActivity.class)
                    .putExtra(WorkBuddyLoginActivity.EXTRA_REALM, realm);
        }
        if (V_TRAE.equals(vendor)) return new Intent(c, TraeLoginActivity.class);
        if (V_LOOMY.equals(vendor)) return new Intent(c, LoomyLoginActivity.class);
        if (V_KIMI.equals(vendor)) return new Intent(c, KimiLoginActivity.class);
        if (V_DEEPSEEK.equals(vendor)) return new Intent(c, DeepSeekLoginActivity.class);
        if (V_DOUBAO.equals(vendor)) return new Intent(c, DoubaoLoginActivity.class);
        if (V_ANTIGRAVITY.equals(vendor)) return new Intent(c, AntigravityLoginActivity.class);
        if (V_XAI.equals(vendor)) return new Intent(c, XaiLoginActivity.class);
        if (V_CLINE.equals(vendor)) return new Intent(c, ClineLoginActivity.class);
        if (V_CLAUDE.equals(vendor)) {
            return new Intent(c, OAuthLoginActivity.class)
                    .putExtra(OAuthLoginActivity.EXTRA_KIND, OAuthLoginActivity.KIND_CLAUDE);
        }
        if (V_CODEX.equals(vendor)) {
            return new Intent(c, OAuthLoginActivity.class)
                    .putExtra(OAuthLoginActivity.EXTRA_KIND, OAuthLoginActivity.KIND_CODEX);
        }
        return null;
    }

    /** Buckets that hold arbitrary API endpoints (no console panel behind them). */
    static boolean isGenericRelayVendor(String vendor) {
        return V_OPENAI.equals(vendor) || V_ANTHROPIC.equals(vendor);
    }

    /** Opens the document picker directly (the caller only needed a file). */
    private Intent pickFile() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, ProviderEditor.REQ_FILE);
        } catch (Exception e) {
            toast("没有可用的文件选择器");
        }
        return null;
    }

    private void choosePreset(final Config.Provider p) {
        final String[] names = {
                "OpenRouter", "DeepSeek", "硅基流动 SiliconFlow", "月之暗面 Kimi",
                "智谱 GLM", "通义千问 DashScope", "火山方舟 Doubao（控制台 API Key）", "MiniMax",
                "小米 MiMo",
                "xAI Grok", "Groq", "Mistral", "Cerebras", "Together",
                "讯飞星火", "百度千帆", "Google AI Studio", "Cloudflare Workers AI",
                "NVIDIA NIM", "Cohere", "HuggingFace", "GitHub Models",
                "SambaNova", "Fireworks", "DeepInfra", "Nebius AI Studio",
                "商汤日日新 SenseNova", "美团 LongCat", "魔搭 ModelScope",
                "阶跃星辰 StepFun", "Pollinations（免注册）", "Venice AI",
                "MiniMax 国际版（M2 免费期）",
                "自建 / 其它中转"};
        final String[] bases = {
                "https://openrouter.ai/api/v1", "https://api.deepseek.com/v1",
                "https://api.siliconflow.cn/v1", "https://api.moonshot.cn/v1",
                "https://open.bigmodel.cn/api/paas/v4", "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "https://ark.cn-beijing.volces.com/api/v3", "https://api.minimax.chat/v1",
                "https://api.xiaomimimo.com/v1",
                "https://api.x.ai/v1", "https://api.groq.com/openai/v1",
                "https://api.mistral.ai/v1", "https://api.cerebras.ai/v1",
                "https://api.together.xyz/v1", "https://spark-api-open.xf-yun.com/v1",
                "https://qianfan.baidubce.com/v2",
                "https://generativelanguage.googleapis.com/v1beta/openai",
                "https://api.cloudflare.com/client/v4/accounts/<账号ID>/ai/v1",
                "https://integrate.api.nvidia.com/v1", "https://api.cohere.ai/compatibility/v1",
                "https://router.huggingface.co/v1", "https://models.inference.ai.azure.com",
                "https://api.sambanova.ai/v1", "https://api.fireworks.ai/inference/v1",
                "https://api.deepinfra.com/v1/openai", "https://api.studio.nebius.ai/v1",
                "https://api.sensenova.cn/compatible-mode/v1",
                "https://api.longcat.chat/openai/v1",
                "https://api-inference.modelscope.cn/v1",
                "https://api.stepfun.com/v1",
                "https://text.pollinations.ai/openai",
                "https://api.venice.ai/api/v1",
                "https://api.minimax.io/v1",
                ""};
        LinearLayout list = Ui.column(this);
        boolean domesticHeaderDone = false;
        boolean overseasHeaderDone = false;
        for (int i = 0; i < names.length; i++) {
            final int which = i;
            boolean domestic = isDomestic(Config.TYPE_OPENAI, names[i]);
            if (domestic && !domesticHeaderDone) {
                subHeader(list, "国内");
                domesticHeaderDone = true;
            } else if (!domestic && !overseasHeaderDone) {
                subHeader(list, "国外");
                overseasHeaderDone = true;
            }
            LinearLayout row = Ui.listRow(this, names[i], bases[i].isEmpty() ? "自己填服务地址" : bases[i]);
            row.setOnClickListener(v -> {
                if (currentDialog != null) currentDialog.dismiss();
                p.name = names[which];
                p.baseUrl = bases[which];
                ProviderEditor.show(this, p, true, this::refreshStatus);
            });
            list.addView(row, Ui.lp(this, 8));
        }
        currentDialog = Ui.dialog(this, "选择服务商", Ui.scroller(this, list), "取消", null, null, null);
    }

    private void loginWorkBuddy(final Config.Provider p) {
        final String[] realms = {"国内版（codebuddy.cn）", "国际版（workbuddy.ai）"};
        final String[] realmKeys = {"cn", "global"};
        LinearLayout list = Ui.column(this);
        for (int i = 0; i < realms.length; i++) {
            final String key = realmKeys[i];
            LinearLayout row = Ui.listRow(this, realms[i], "");
            row.setOnClickListener(v -> {
                if (currentDialog != null) currentDialog.dismiss();
                try {
                    Intent intent = new Intent(this, WorkBuddyLoginActivity.class);
                    intent.putExtra(WorkBuddyLoginActivity.EXTRA_REALM, key);
                    startActivityForResult(intent, ProviderEditor.REQ_WB_LOGIN);
                } catch (Throwable t) {
                    toast("打开登录页失败：" + t);
                    Log.e("login launch failed: " + t);
                }
            });
            list.addView(row, Ui.lp(this, 8));
        }
        currentDialog = Ui.dialog(this, "选择 WorkBuddy 区域", Ui.scroller(this, list), "取消", null, null, null);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (ProviderEditor.handleResult(this, requestCode, resultCode, data)) {
            refreshStatus();
            return;
        }
        if (requestCode == REQ_OAUTH_LOGIN) {
            refreshStatus();
        }
    }

    /** Entry point used by the account detail page. */
    static void editProvider(Activity a, Config.Provider p, Runnable onDone) {
        ProviderEditor.show(a, p, false, onDone);
    }

    public static String displayName(Config.Provider p) {
        if (p.name == null) return "";
        for (String prefix : new String[]{"WorkBuddy-", "Claude-", "ChatGPT-", "OpenAI-"}) {
            if (p.name.startsWith(prefix)) return p.name.substring(prefix.length());
        }
        return p.name;
    }

    public static String sourceLabel(Config.Provider p) {
        try {
            if (Config.TYPE_WORKBUDDY.equals(p.type)) {
                org.json.JSONObject o = new org.json.JSONObject(p.credentials.isEmpty() ? "{}" : p.credentials);
                String realm = o.optString("realm", "cn");
                return "global".equalsIgnoreCase(realm) ? "国际版 · workbuddy.ai" : "国内版 · codebuddy.cn";
            }
            if (Config.TYPE_CODEX.equals(p.type)) return "Codex 通道";
            if (Config.TYPE_CLAUDE.equals(p.type)) return "claude.ai 账号";
            if (p.baseUrl == null || p.baseUrl.isEmpty()) return "未设置地址";
            return p.baseUrl.replaceFirst("^https?://", "").replaceFirst("/.*$", "");
        } catch (Exception e) {
            return "已配置";
        }
    }

    /* ------------------------------------------------------------------- misc */

    private void startGateway() {
        Intent svc = new Intent(this, GatewayService.class).setAction(GatewayService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc);
        else startService(svc);
    }

    private void toggleGateway() {
        /*
         * 以「界面上写的字」为准判断用户意图，而不是以此刻的 isRunning() 为准。
         *
         * 真机上的事故：打开 App 后界面很快刷成「启动网关」（那时网关确实没跑），
         * 但 keepAlive 的 JobService 会在几秒后**自动**把网关拉起来（它的条件就是
         * cfg.keepAlive && !Gateway.isRunning()）。此时界面还写着「启动网关」，
         * 用户按下去，里面的 isRunning() 已经是 true —— 于是走进停止分支：
         * 网关被关掉、按钮文案变成「启动网关」，用户看到的却是"第一次启动无效，
         * 再点一次才好"。第二次之所以"成功"，只是因为被第一次关掉了，从头再来一遍。
         *
         * 现在：按钮写着「启动网关/正在启动」就执行启动（已经在跑就什么都不做，
         * 并把状态刷成真值）；写着「停止网关/正在停止」才执行停止。用户的意图
         * 由他自己看到的那行字表达，不能被后台悄悄变化的状态翻转。
         */
        /*
         * 4.30.19 这里靠"读按钮上的中文字 + 比对实时状态"，因为那时没有可靠的状态源：
         * 保活任务会绕过界面把网关拉起来，isRunning() 和按钮文案谁新谁旧说不清。
         *
         * 4.30.22 起有 GatewayState 了，意图可以直接从状态推出：
         * 只要不是"在服务/正在启动"，这一下就是启动意图。不再需要读文案，
         * 也不会因为后台抢跑而把用户的意思翻转成相反的操作。
         */
        final boolean uiSaysRunning = Gateway.state().isAlive();
        final boolean actuallyRunning = Gateway.isRunning();

        if (uiSaysRunning == actuallyRunning) {
            /*
             * 只发动作，不抢先报"已停止/已启动"。
             *
             * 原来这里在 startService() 之后立刻 toast("已启动")——那只是"请求发出去了"，
             * 网关可能 bind 失败、可能被 ROM 拦掉、也可能几秒后才真正就绪。
             * 用户先看到"已启动"、再看到状态还是「已停止」，比什么都不提示更糟。
             * 现在等 700ms 让状态落定，按**真实结果**说话。
             */
            final boolean wantStop = actuallyRunning;
            if (wantStop) {
                Ui.setLoading(primaryButton, true, "正在停止…");
                startService(new Intent(this, GatewayService.class).setAction(GatewayService.ACTION_STOP));
            } else {
                Ui.setLoading(primaryButton, true, "正在启动…");
                startGateway();
            }
            primaryButton.postDelayed(() -> {
                Ui.setLoading(primaryButton, false, null);
                refreshStatus();
                final boolean now = Gateway.isRunning();
                if (wantStop) {
                    if (now) toast("停止未生效，网关仍在运行");
                    else toast("已停止");
                } else {
                    if (now) toast("已启动");
                    else toast("启动未生效，请重试");
                }
            }, 700);
            return;
        }

        /*
         * 界面认知与真实状态不一致（后台替用户把事做了，或做完又反悔了）。
         * 这种情况不执行任何破坏性动作，只把界面刷成真值 —— 用户想启动而它已经在跑，
         * 那他要的结果已经达成了；用户想停止而它已经停了，同理。
         */
        refreshStatus();
        if (uiSaysRunning) toast("网关当前未在运行");
        else toast("网关已在运行");
    }

    private void copyText(String text) {
        // 走统一个入口：60 秒后自动清除，避免密钥长期留在剪贴板（及剪贴板历史）里
        Ui.copyWithAutoClear(this, text);
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
    }

    /** Surfaces crashes into the in-app log instead of only killing the process. */
    private void installCrashLogger() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            Log.e("崩溃：" + throwable + " @ " + thread.getName());
            try {
                java.io.File f = new java.io.File(getFilesDir(), "crash.log");
                /* 轮转：崩溃日志只追加、原来能无限长。超过 256KB 就把旧的改名留一份，
                   免得一个反复崩的夜晚把手机存储写满（也方便"这次崩"和"上次崩"分开看）。 */
                if (f.length() > 256 * 1024) {
                    java.io.File old = new java.io.File(getFilesDir(), "crash.log.old");
                    if (old.exists()) old.delete();
                    f.renameTo(old);
                }
                java.io.FileOutputStream out = new java.io.FileOutputStream(f, true);
                out.write((new java.util.Date() + "\n" + android.util.Log.getStackTraceString(throwable) + "\n\n").getBytes("UTF-8"));
                out.close();
            } catch (Throwable ignored) {
            }
            if (previous != null) previous.uncaughtException(thread, throwable);
        });
    }

    private void toast(String msg) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        stopStatusPolling();   /* 轮询持有本 Activity：销毁时务必摘掉，否则 postDelayed 会让它泄漏 */
        // 旋转/关闭时把对话框收掉：否则 WindowLeaked，后台线程还会往已销毁的 View 写字
        try {
            if (currentDialog != null) {
                currentDialog.dismiss();
                currentDialog = null;
            }
        } catch (Throwable ignored) {
        }
        // 主动释放 ProviderEditor 的静态引用。
        // 那三个字段（EditText / Dialog / Activity）是静态的，会一直持有本 Activity；
        // 以前唯一的释放点是编辑器自己的 dismissListener，Activity 非正常销毁时不保证触发。
        // 放在这里，是"宿主销毁"这一侧的唯一可靠时机。
        try {
            ProviderEditor.release();
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }
}
