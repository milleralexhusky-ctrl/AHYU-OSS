package com.cpa.gateway;

import com.cpa.gateway.core.Threads;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.cpa.gateway.core.Config;
import com.cpa.gateway.core.Gateway;
import com.cpa.gateway.core.Log;
import com.cpa.gateway.core.Upstreams;

import java.util.List;

/** Everything configuration-ish, off the home screen so the home screen can stay one page. */
public class SettingsActivity extends Activity {

    private EditText portInput;
    private EditText keyInput;
    /** 密钥是否处于遮蔽状态（默认遮蔽）。 */
    private boolean keyMasked = true;
    private Switch lanSwitch;
    private Switch autostartSwitch;
    private Switch keepAliveSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.darkSystemBars(this);
        Gateway.init(this);
        Config cfg = Gateway.config();

        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Color.TRANSPARENT);
        root.setPadding(Ui.dp(this, 16), Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 16));

        TextView back = Ui.barButton(this, "‹ 返回");
        back.setOnClickListener(v -> finish());
        root.addView(Ui.appBar(this, back, "设置", null));

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout body = Ui.column(this);
        body.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 10));
        sv.addView(body);

        LinearLayout serverCard = Ui.column(this);
        serverCard.addView(Ui.sectionTitle(this, "服务"));

        // 加密降级警告：必须**持久可见**，不能只打一行日志。
        // 策略是"降级可用但显性 + 自愈"——文件仍然能用（不会把用户锁在自己网关外面），
        // 但用户有权知道敏感字段此刻是明文躺在磁盘上；Keystore 恢复后下次保存会自动迁回密文。
        if (com.cpa.gateway.core.Secret.lastSealFailed) {
            TextView warn = Ui.label(this,
                    "⚠ 配置加密当前不可用：账号凭据与密钥正以明文保存在应用目录里。"
                            + "文件仍可正常使用，Keystore 恢复后下次保存会自动转回密文。");
            warn.setTextColor(0xFFFF6B6B);
            warn.setTextSize(12.5f);
            serverCard.addView(warn, Ui.lp(this, 10));
        }

        serverCard.addView(Ui.label(this, "监听端口"), Ui.lp(this, 12));
        portInput = Ui.field(this, "8321", String.valueOf(cfg.port), false);
        portInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        serverCard.addView(portInput);
        serverCard.addView(Ui.label(this, "客户端密钥"), Ui.lp(this, 12));
        /* 设置页**不再**把当前 Key 回填进输入框。
           原因（真机）：以前这里是 cfg.apiKey 直接塞进去，而 apiKey 的默认值是
           构造时随机生成的 "sk-cpa-local-xxxxxx"，第一次保存就落盘，于是这个
           "随机默认值"被当成用户设过的 Key 一直读回来——用户把输入框清空、点保存，
           下次进来又被填满，看起来"清都清不空"，而且客户端不带 Key 一律 401。
           现在：没 Key 就显示空框（占位文字说明可以不填），有 Key 才回填。
           清空并保存 = 真正关闭本机校验（局域网模式仍强制要求，见 Server）。 */
        keyInput = Ui.field(this, "留空＝本机不校验（局域网访问必须填）", cfg.apiKey, false);
        // 密钥默认遮蔽：以前是明文显示，设置页一打开（或截屏、被旁人扫一眼）就暴露了。
        // 同时给一个「显示」按钮 —— 否则用户没法核对到底填对了没有。
        applyKeyMask(true);
        LinearLayout keyRow = Ui.row(this);
        keyRow.addView(keyInput, Ui.weight(this, 1f, 0));
        TextView showKey = Ui.ghostButton(this, "显示");
        showKey.setOnClickListener(v -> {
            keyMasked = !keyMasked;
            applyKeyMask(keyMasked);
            showKey.setText(keyMasked ? "显示" : "隐藏");
        });
        LinearLayout.LayoutParams sklp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sklp.leftMargin = Ui.dp(this, 8);
        keyRow.addView(showKey, sklp);
        TextView copyKey = Ui.ghostButton(this, "复制");
        copyKey.setOnClickListener(v -> {
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText(
                        "AHYU Key", Gateway.config().apiKey));
                toast("已复制，粘贴到客户端的 API Key 里");
            } catch (Throwable e) {
                toast("复制失败：" + e.getMessage());
            }
        });
        LinearLayout.LayoutParams cklp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cklp.leftMargin = Ui.dp(this, 8);
        keyRow.addView(copyKey, cklp);
        serverCard.addView(keyRow);
        lanSwitch = Ui.toggle(this, "允许局域网访问", cfg.lanAccess);
        serverCard.addView(lanSwitch, Ui.lp(this, 14));
        autostartSwitch = Ui.toggle(this, "开机自动启动", cfg.autostart);
        serverCard.addView(autostartSwitch, Ui.lp(this, 8));
        keepAliveSwitch = Ui.toggle(this, "被系统杀掉后自动拉起", cfg.keepAlive);
        serverCard.addView(keepAliveSwitch, Ui.lp(this, 8));
        TextView save = Ui.primaryButton(this, "保存并应用");
        save.setOnClickListener(v -> save());
        serverCard.addView(save, Ui.lp(this, 16));
        body.addView(Ui.card(this, serverCard));

        LinearLayout bgCard = Ui.column(this);
        bgCard.addView(Ui.sectionTitle(this, "后台存活（重要）"));
        bgCard.addView(Ui.label(this, "系统默认禁止应用自行启动后台服务。不开这一步，网关被回收后不会自动回来。"), Ui.lp(this, 10));
        TextView autostart = Ui.primaryButton(this, "① 打开系统「自启动」设置");
        autostart.setOnClickListener(v -> openAutostartSettings());
        bgCard.addView(autostart, Ui.lp(this, 14));
        TextView battery = Ui.outlineButton(this, "② 申请忽略电池优化");
        battery.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                toast("请到系统设置里允许后台运行");
            }
        });
        bgCard.addView(battery, Ui.lp(this, 10));
        body.addView(Ui.card(this, bgCard), Ui.lp(this, 14));

        // 磁贴：HyperOS 上必须用户手动拖进面板，API 33+ 可以弹系统确认框引导一次。
        // 用反射调 requestAddTileService：不同 SDK 小版本的签名细节不一致，反射失败就退回"手动添加"提示。
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            LinearLayout tileCard = Ui.column(this);
            tileCard.addView(Ui.sectionTitle(this, "快捷开关"));
            tileCard.addView(Ui.label(this, "把「CLIProxy 网关」加到下拉快捷面板，一键启停"), Ui.lp(this, 8));
            TextView addTile = Ui.outlineButton(this, "添加到快捷设置");
            addTile.setOnClickListener(v -> {
                try {
                    java.lang.reflect.Method m = android.app.Activity.class.getMethod("requestAddTileService",
                            android.content.ComponentName.class, CharSequence.class,
                            android.graphics.drawable.Icon.class, java.util.concurrent.Executor.class,
                            java.util.function.Consumer.class);
                    m.invoke(this,
                            new android.content.ComponentName(this, GatewayTileService.class),
                            "CLIProxy 网关",
                            android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_notify),
                            java.util.concurrent.Executors.newSingleThreadExecutor(),
                            (java.util.function.Consumer<Integer>) r -> Log.i("磁贴添加结果：" + r));
                    toast("已请求系统添加磁贴");
                } catch (Throwable t) {
                    Log.w("自动添加磁贴失败：" + t);
                    toast("这台 ROM 不支持自动添加，请到快捷面板手动拖入");
                }
            });
            tileCard.addView(addTile, Ui.lp(this, 10));
            body.addView(Ui.card(this, tileCard), Ui.lp(this, 14));
        }

        LinearLayout dataCard = Ui.column(this);
        dataCard.addView(Ui.sectionTitle(this, "数据"));
        TextView refresh = Ui.outlineButton(this, "重新拉取全部账号的模型列表");
        refresh.setOnClickListener(v -> refreshModels());
        dataCard.addView(refresh, Ui.lp(this, 12));
        TextView logs = Ui.outlineButton(this, "查看日志");
        logs.setOnClickListener(v -> startActivity(new Intent(this, LogActivity.class)));
        dataCard.addView(logs, Ui.lp(this, 10));

        /* 换机迁移：allowBackup 是关的（配置里有账号 token，不能让系统云备份顺走），
           所以这里补一个"用户自己带密码"的加密导出，否则换手机等于全部重来。 */
        dataCard.addView(Ui.label(this, "备份与恢复：导出的文件用你自己的密码加密，"
                + "可以直接发给自己；没有密码谁也解不开（包括本应用）"), Ui.lp(this, 14));
        TextView selftestBtn = Ui.outlineButton(this, "自检：请求有没有被削薄");
        selftestBtn.setOnClickListener(v -> showSelftest());
        dataCard.addView(selftestBtn, Ui.lp(this, 12));

        TextView checkBtn = Ui.outlineButton(this, "体检：每个账号能不能真的出答案（走本地端口）");
        checkBtn.setOnClickListener(v -> runSelfCheck());
        dataCard.addView(checkBtn, Ui.lp(this, 12));

        /* 排查支持：把版本/配置/日志/用量/自检打成一个 zip，避免用户到处找文件。 */
        TextView diagBtn = Ui.outlineButton(this, "导出诊断包（排查用）");
        diagBtn.setOnClickListener(v -> startDiagnostics());
        dataCard.addView(diagBtn, Ui.lp(this, 10));
        dataCard.addView(Ui.label(this, "包含脱敏配置、日志、用量、自检结果；"
                + "rawcap 尾部可能含回答内容，发送前请自行检查。"), Ui.lp(this, 6));

        TextView updateBtn = Ui.outlineButton(this, "检查更新（GitHub Release）");
        updateBtn.setOnClickListener(v -> checkUpdate());
        dataCard.addView(updateBtn, Ui.lp(this, 10));

        TextView exportBtn = Ui.outlineButton(this, "导出加密备份");
        exportBtn.setOnClickListener(v -> startExport());
        dataCard.addView(exportBtn, Ui.lp(this, 10));
        TextView importBtn = Ui.outlineButton(this, "从备份恢复…");
        importBtn.setOnClickListener(v -> startImport());
        dataCard.addView(importBtn, Ui.lp(this, 10));
        body.addView(Ui.card(this, dataCard), Ui.lp(this, 14));

        // 外观：7 套配色一直躺在 Ui.THEMES 里，界面上却没有任何入口（只能改配置文件）。
        LinearLayout themeCard = Ui.column(this);
        themeCard.addView(Ui.sectionTitle(this, "外观"));
        themeCard.addView(Ui.label(this, "主题色：切完立即生效，其它页面下次进入时同步"), Ui.lp(this, 10));
        LinearLayout dots = Ui.row(this);
        final String current = Gateway.config().accentTheme == null ? "spring" : Gateway.config().accentTheme;
        for (final String[] t : Ui.THEMES) {
            TextView dot = new TextView(this);
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            g.setColor(Color.parseColor(t[2]));
            if (t[0].equals(current)) g.setStroke(Ui.dp(this, 2), Ui.TEXT);
            dot.setBackground(g);
            dot.setClickable(true);
            Ui.a11y(dot, "主题 " + t[1]);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(Ui.dp(this, 34), Ui.dp(this, 34));
            dlp.rightMargin = Ui.dp(this, 12);
            dot.setOnClickListener(v -> {
                Gateway.config().accentTheme = t[0];
                Gateway.save(this);
                Ui.applyTheme(t[0]);
                android.widget.Toast.makeText(this, "已切换到「" + t[1] + "」",
                        android.widget.Toast.LENGTH_SHORT).show();
                recreate();
            });
            dots.addView(dot, dlp);
        }
        themeCard.addView(dots, Ui.lp(this, 4));

        themeCard.addView(Ui.label(this, "当前：" + Ui.themeLabel(current)), Ui.lp(this, 10));
        android.widget.Switch lightSwitch = Ui.toggle(this, "浅色模式（白底深字）", cfg.lightMode);
        lightSwitch.setOnCheckedChangeListener((btn, on) -> {
            Gateway.config().lightMode = on;
            Gateway.save(this);
            Ui.applyTheme(Gateway.config().accentTheme);
            recreate();
        });
        themeCard.addView(lightSwitch, Ui.lp(this, 10));
        body.addView(Ui.card(this, themeCard), Ui.lp(this, 14));

        TextView versionLine = Ui.label(this, "版本 " + com.cpa.gateway.core.Backup.versionTag()
                + "（侧载分发，没有更新推送；换新版时这个号会变）");
        body.addView(versionLine, Ui.lp(this, 18));

        root.addView(sv, Ui.fill(this, 0));
        // 底层强度 0.32 —— 设置：密集文字，底层几乎只剩色调
        setContentView(Ui.glassScaffold(this, root, 0.32f));
        // 刻意**不**调 Ui.secure()：
        // 设置页只有端口、主题、自启动开关这类东西，没有账号凭据，
        // 禁截屏是误伤——用户想截图反馈问题（或记录配置）时会被直接挡住。
        // 真正需要禁的是登录页与自定义中转站页（里面有 token / 二维码）：
        // 见 LoginPageBase 和 WebLoginActivity。
    }

    /**
     * 真链路体检：每个可派发账号真的发一次 1-token 请求（走本地端口、带 Key、标 ephemeral）。
     *
     * 为什么放在设置页：它是"排查用"的动作，不是日常操作；但比只看 /health 有用得多——
     * 进程活着、端口在听、鉴权也通，账号却可能压根出不了答案。
     */
    private void runSelfCheck() {
        final TextView head = Ui.label(this, "正在体检…（每个账号一次 1-token 请求，最多 15 秒）");
        final TextView bodyText = Ui.mono(this, "", 12f);
        LinearLayout box = Ui.column(this);
        box.addView(head);
        box.addView(bodyText, Ui.lp(this, 10));
        final android.app.Dialog dlg = Ui.dialog(this, "真链路体检", Ui.scroller(this, box),
                null, null, "关闭", null);
        Threads.io(() -> {
            java.util.List<com.cpa.gateway.core.SelfCheck.Row> rows =
                    com.cpa.gateway.core.SelfCheck.runAll(15000, 3);
            StringBuilder sb = new StringBuilder();
            for (com.cpa.gateway.core.SelfCheck.Row r : rows) {
                sb.append(r.ok ? "✅ " : "❌ ").append(r.name)
                        .append(" · ").append(r.model).append(" · ").append(r.ms).append("ms");
                if (!r.ok && !r.why.isEmpty()) sb.append("\n     ").append(r.why);
                sb.append('\n');
            }
            String summary = com.cpa.gateway.core.SelfCheck.summary(rows);
            sb.append('\n').append(summary);
            com.cpa.gateway.core.Log.i(summary);
            final String text = sb.toString();
            runOnUiThread(() -> {
                head.setText("体检完成");
                bodyText.setText(text);
            });
        });
    }

    /** 切换密钥输入框的遮蔽状态。 */
    private void applyKeyMask(boolean masked) {
        if (keyInput == null) return;
        try {
            if (masked) {
                keyInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                        | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
            } else {
                keyInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                        | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
            }
            // 切换 inputType 后光标会跳到开头，挪回末尾
            keyInput.setSelection(keyInput.getText().length());
        } catch (Throwable ignored) {
        }
    }

    private void save() {
        Config cfg = Gateway.config();
        try {
            int port = Integer.parseInt(portInput.getText().toString().trim());
            if (port <= 0 || port > 65535) throw new NumberFormatException();
            cfg.port = port;
        } catch (Exception e) {
            toast("端口不合法");
            return;
        }
        String newKey = keyInput.getText().toString().trim();
        if (!newKey.isEmpty() && cfg.apiKey != null && !cfg.apiKey.isEmpty() && !newKey.equals(cfg.apiKey)) {
            if (!cfg.apiKeyAliases.contains(cfg.apiKey)) {
                cfg.apiKeyAliases.add(cfg.apiKey);
            }
        }
        boolean keyCleared = newKey.isEmpty() && cfg.keyRequired();
        cfg.apiKey = newKey;
        cfg.lanAccess = lanSwitch.isChecked();
        cfg.autostart = autostartSwitch.isChecked();
        cfg.keepAlive = keepAliveSwitch.isChecked();
        if (keyCleared && cfg.lanAccess) {
            /* 局域网模式下没有 Key 会被 Server 直接 401 拒绝整个请求（那是有意的），
               这里先把话说清楚，不要让用户以为"保存成功了但客户端连不上"是 bug。 */
            toast("局域网访问已开启，必须填写客户端密钥，否则所有请求都会被拒绝");
        } else if (keyCleared) {
            toast("已清空客户端密钥：本机回环不再校验，客户端不填 Key 也能连");
        }
        Gateway.save(this);
        if (cfg.keepAlive) KeepAliveJobService.schedule(this);
        else KeepAliveJobService.cancel(this);
        // 重启顺序很重要：旧实现的 stop 与 start 几乎同时发出，
        // START 处理完之后旧实例的 onDestroy()（内部会 Gateway.stop()）才执行，
        // 把刚拉起来的新 server 又杀掉了——表现就是"已保存，但网关其实死了"。
        // 现在先停、等一下、确认停了再起。
        if (Gateway.isRunning()) {
            android.content.Context app = getApplicationContext();
            Intent stop = new Intent(app, GatewayService.class).setAction(GatewayService.ACTION_STOP);
            try {
                app.startService(stop);
            } catch (Throwable ignored) {
            }
            toast("已保存，正在重启网关…");
            Threads.io(() -> {
                for (int i = 0; i < 40; i++) {
                    if (!Gateway.isRunning()) break;
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
                // 等旧实例**真的**销毁，而不是拍一个 400ms 的常量。
                // 以前这里硬睡 400ms，重负载下旧实例的 onDestroy 拖过 400ms，
                // 旧实例就不会被回收干净（连点两次保存还会互相交错）。
                // 现在轮询 GatewayService 的实例信号（onDestroy 第一行会把它置 null），
                // 每 50ms 看一次，最多等 5 秒；超时也继续往下走（不能永远卡在重启上）。
                for (int i = 0; i < 100; i++) {
                    if (!GatewayService.hasInstance()) break;
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
                Intent svc = new Intent(app, GatewayService.class).setAction(GatewayService.ACTION_START);
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(svc);
                    else app.startService(svc);
                    Log.i("设置已应用：网关已重启");
                } catch (Throwable t) {
                    Log.w("重启网关失败：" + t);
                }
            });
            return;
        }
        toast("已保存");
    }

    private void refreshModels() {
        toast("正在拉取…");
        Threads.io(() -> {
            int ok = 0;
            int fail = 0;
            for (Config.Provider p : Gateway.config().providers) {
                if (!p.enabled) continue;
                try {
                    List<String> models = Upstreams.fetchModels(p);
                    p.setModels(models);
                    ok++;
                } catch (Exception e) {
                    fail++;
                    Log.w("拉取模型失败 " + p.name + "：" + e);
                }
            }
            Gateway.persist();
            final int okc = ok;
            final int failc = fail;
            runOnUiThread(() -> toast("完成：" + okc + " 个成功" + (failc > 0 ? "，" + failc + " 个失败（看日志）" : "")));
        });
    }

    private void openAutostartSettings() {
        String[][] targets = {
                {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
                {"com.miui.securitycenter", "com.miui.powercenter.PowerSettings"},
                {"com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"},
                {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
                {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"}};
        for (String[] t : targets) {
            try {
                Intent intent = new Intent();
                intent.setComponent(new android.content.ComponentName(t[0], t[1]));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                toast("请允许「自启动 / 后台运行」");
                return;
            } catch (Throwable ignored) {
            }
        }
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            toast("请到系统设置 → 应用管理 → 本应用 里允许自启动");
        }
    }

    private void toast(String msg) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show();
    }

    /* ============================================================ 备份 / 恢复 */

    /** 导出流程：先要密码（两次确认）→ 选保存位置 → 后台加密写盘。 */
    private void startExport() {
        final EditText pwd = Ui.field(this, "备份密码", "", true);
        pwd.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        final EditText again = Ui.field(this, "再输一次", "", true);
        again.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        final TextView hint = Ui.label(this, "密码只用来加密这个文件，忘了谁都解不开（本应用也不保存它）");
        hint.setTextSize(12f);

        new android.app.AlertDialog.Builder(this)
                .setTitle("导出加密备份")
                .setView(wrapDialog(pwd, again, hint))
                .setNegativeButton("取消", null)
                .setPositiveButton("下一步", (d, w) -> {
                    String p1 = pwd.getText().toString();
                    String p2 = again.getText().toString();
                    if (p1.isEmpty()) {
                        toast("请先设置密码");
                        return;
                    }
                    if (!p1.equals(p2)) {
                        toast("两次输入的密码不一致");
                        return;
                    }
                    String warn = com.cpa.gateway.core.Backup.passwordHint(p1);
                    if (warn != null) toast(warn);      // 只提示，不拦截
                    pickExportTarget(p1);
                })
                .show();
    }

    private static final int REQ_CREATE = 9001;
    private static final int REQ_OPEN = 9002;
    private static final int REQ_DIAG = 9003;

    private void pickExportTarget(String password) {
        pendingPassword = password;
        try {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/json");
            i.putExtra(Intent.EXTRA_TITLE, com.cpa.gateway.core.Backup.suggestName());
            startActivityForResult(i, REQ_CREATE);
        } catch (Throwable t) {
            /* 没有文件选择器的 ROM：退回私有目录，并告诉用户文件在哪 */
            exportToPrivateDir(password);
        }
    }

    /** ROM 不支持 SAF 时的退路：写到应用私有目录，用户可通过"分享"带走。 */
    private void exportToPrivateDir(String password) {
        Threads.io(() -> {
            try {
                java.io.File dir = new java.io.File(getExternalFilesDir(null) != null
                        ? getExternalFilesDir(null) : getFilesDir(), "backup");
                if (!dir.exists()) dir.mkdirs();
                java.io.File target = new java.io.File(dir, com.cpa.gateway.core.Backup.suggestName());
                com.cpa.gateway.core.Backup.exportToFile(target,
                        Gateway.config().exportJson(), password);
                runOnUiThread(() -> toast("已导出到：" + target.getAbsolutePath()));
            } catch (Throwable t) {
                Log.w("导出备份失败：" + t);
                runOnUiThread(() -> toast("导出失败：" + t.getMessage()));
            }
        });
    }

    private String pendingPassword = "";

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (result != android.app.Activity.RESULT_OK) return;
        Uri uri = data == null ? null : data.getData();
        if (uri == null) return;
        if (req == REQ_CREATE) {
            final String pwd = pendingPassword;
            pendingPassword = "";
            Threads.io(() -> {
                try {
                    android.content.ContentResolver cr = getContentResolver();
                    java.io.OutputStream os = cr.openOutputStream(uri, "wt");
                    if (os == null) throw new IllegalStateException("无法写入所选位置");
                    byte[] body = com.cpa.gateway.core.Backup
                            .export(Gateway.config().exportJson(), pwd)
                            .getBytes("UTF-8");
                    os.write(body);
                    os.flush();
                    os.close();
                    runOnUiThread(() -> toast("备份已导出（" + body.length + " 字节）"));
                } catch (Throwable t) {
                    Log.w("导出备份失败：" + t);
                    runOnUiThread(() -> toast("导出失败：" + t.getMessage()));
                }
            });
        } else if (req == REQ_DIAG) {
            Threads.io(() -> {
                try {
                    java.io.OutputStream os = getContentResolver().openOutputStream(uri, "wt");
                    if (os == null) throw new IllegalStateException("无法写入所选位置");
                    try {
                        com.cpa.gateway.core.Diagnostics.writeTo(os);
                    } finally {
                        os.close();
                    }
                    runOnUiThread(() -> {
                        toast("诊断包已导出");
                        /* 导出后直接拉起系统分享：用户不用再去文件管理器里找。 */
                        try {
                            Intent send = new Intent(Intent.ACTION_SEND);
                            send.setType("application/zip");
                            send.putExtra(Intent.EXTRA_STREAM, uri);
                            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            startActivity(Intent.createChooser(send, "发送诊断包"));
                        } catch (Throwable ignored) {
                        }
                    });
                } catch (Throwable t) {
                    Log.w("导出诊断包失败：" + t);
                    runOnUiThread(() -> toast("导出诊断包失败：" + t.getMessage()));
                }
            });
        } else if (req == REQ_OPEN) {
            askImportPassword(uri);
        }
    }

    /**
     * 检查公库最新 Release。只提示并跳转，不做静默安装。
     *
     * 走 GitHub 公开 API，用户点一次才发一次请求；失败只 toast，不打扰。
     */
    private void checkUpdate() {
        toast("正在检查最新版本…");
        Threads.io(() -> {
            try {
                java.util.Map<String, String> headers = new java.util.HashMap<>();
                headers.put("Accept", "application/vnd.github+json");
                com.cpa.gateway.core.Http.Resp resp = com.cpa.gateway.core.Http.call(
                        "GET",
                        "https://api.github.com/repos/milleralexhusky-ctrl/AHYU-OSS/releases/latest",
                        headers, null, null, 10000, 15000, false);
                org.json.JSONObject o = new org.json.JSONObject(resp.body);
                final String tag = o.optString("tag_name", "");
                final String url = o.optString("html_url", "");
                String notes = o.optString("body", "");
                if (tag.isEmpty()) throw new IllegalStateException("GitHub 返回里没有版本号");
                String cur = com.cpa.gateway.core.Backup.versionTag();
                boolean newer = compareVersion(tag, cur) > 0;
                String title = newer
                        ? "发现新版本 " + tag + "（当前 " + cur + "）"
                        : "已是最新版本（当前 " + cur + "）";
                if (notes.length() > 4000) notes = notes.substring(0, 4000) + "…";
                final String msg = notes.isEmpty() ? "这个 Release 没有写说明。" : notes;
                runOnUiThread(() -> {
                    android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this)
                            .setTitle(title)
                            .setMessage(msg)
                            .setNegativeButton("关闭", null);
                    if (!url.isEmpty()) {
                        b.setPositiveButton("打开发布页", (d, w) -> {
                            try {
                                startActivity(new Intent(Intent.ACTION_VIEW,
                                        android.net.Uri.parse(url)));
                            } catch (Throwable ignored) {
                            }
                        });
                    }
                    b.show();
                });
            } catch (Throwable t) {
                runOnUiThread(() -> toast("检查更新失败：" + t.getMessage()));
            }
        });
    }

    private static int compareVersion(String a, String b) {
        String[] x = versionParts(a);
        String[] y = versionParts(b);
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int vx = i < x.length ? parseIntOr0(x[i]) : 0;
            int vy = i < y.length ? parseIntOr0(y[i]) : 0;
            if (vx != vy) return vx < vy ? -1 : 1;
        }
        return 0;
    }

    private static String[] versionParts(String v) {
        String s = v == null ? "" : v.trim();
        while (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        if (s.isEmpty()) return new String[0];
        return s.split("[^0-9]+");
    }

    private static int parseIntOr0(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 选一个位置，导出脱敏后的诊断 zip。 */
    private void startDiagnostics() {
        try {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/zip");
            i.putExtra(Intent.EXTRA_TITLE, com.cpa.gateway.core.Diagnostics.suggestedName());
            startActivityForResult(i, REQ_DIAG);
        } catch (Throwable t) {
            toast("这台 ROM 没有文件选择器，无法导出诊断包");
        }
    }

    private void startImport() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_OPEN);
        } catch (Throwable t) {
            toast("这台 ROM 没有文件选择器，请用系统文件管理器打开备份文件");
        }
    }

    /** 恢复会覆盖现有账号，必须二次确认：先输密码，再确认覆盖。 */
    private void askImportPassword(Uri uri) {
        final EditText pwd = Ui.field(this, "备份密码", "", true);
        pwd.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new android.app.AlertDialog.Builder(this)
                .setTitle("从备份恢复")
                .setMessage("恢复会替换当前的账号与配置。当前配置会先另存为 gateway.json.bak。")
                .setView(wrapDialog(pwd))
                .setNegativeButton("取消", null)
                .setPositiveButton("确认恢复", (d, w) -> doImport(uri, pwd.getText().toString()))
                .show();
    }

    private void doImport(Uri uri, String password) {
        Threads.io(() -> {
            try {
                java.io.InputStream in = getContentResolver().openInputStream(uri);
                if (in == null) throw new IllegalStateException("无法读取所选文件");
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                int total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    /* 先读后检查没有意义：超大文件已经把内存吃掉了。边读边限。 */
                    if (total > 8 * 1024 * 1024) {
                        throw new IllegalStateException("备份文件超过 8MB，拒绝加载");
                    }
                    bos.write(buf, 0, n);
                }
                in.close();
                org.json.JSONObject restored = com.cpa.gateway.core.Backup
                        .importFrom(new String(bos.toByteArray(), "UTF-8"), password);
                runOnUiThread(() -> confirmOverwrite(restored));
            } catch (com.cpa.gateway.core.Backup.BadBackupException b) {
                runOnUiThread(() -> toast(b.getMessage()));
            } catch (Throwable t) {
                Log.w("读取备份失败：" + t);
                runOnUiThread(() -> toast("读取失败：" + t.getMessage()));
            }
        });
    }

    /** 密码对了之后，真正落盘前再确认一次（覆盖不可撤销）。 */
    private void confirmOverwrite(org.json.JSONObject restored) {
        int providers = 0;
        try {
            org.json.JSONArray arr = restored.optJSONArray("providers");
            providers = arr == null ? 0 : arr.length();
        } catch (Throwable ignored) {
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("备份已解开")
                .setMessage("包含 " + providers + " 个账号/通道。\n\n恢复后将替换当前配置并重启网关。确定继续吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("覆盖并重启", (d, w) -> applyRestored(restored))
                .show();
    }

    private void applyRestored(org.json.JSONObject restored) {
        Threads.io(() -> {
            try {
                Gateway.replaceConfigFromBackup(this, restored);
                runOnUiThread(() -> {
                    toast("已恢复，网关正在用新配置重启");
                    recreate();
                });
            } catch (Throwable t) {
                Log.w("应用备份失败：" + t);
                runOnUiThread(() -> toast("恢复失败：" + t.getMessage()));
            }
        });
    }

    /**
     * 自检结果。
     *
     * 为什么要给用户看：这几类问题（参数没透传、图片被丢、思维链被降级）**不会报错**，
     * 表现只是"同一个模型走网关好像变笨了"，靠自己猜很难定位。
     * 把这些断言摊开给人看，至少能明确排除"是我们这边把请求弄丢了一部分"。
     */
    private void showSelftest() {
        java.util.List<com.cpa.gateway.core.Selftest.Result> results =
                com.cpa.gateway.core.Selftest.runAll();
        LinearLayout box = Ui.column(this);
        int pass = 0;
        for (com.cpa.gateway.core.Selftest.Result r : results) {
            if (r.ok) pass++;
            TextView line = Ui.text(this,
                    (r.ok ? "✅ " : "❌ ") + r.name + (r.ok ? "" : "\n      " + r.detail),
                    12.5f, r.ok ? Ui.MUTED : 0xFFFF6B6B, false);
            box.addView(line, Ui.lp(this, 8));
        }
        LinearLayout.LayoutParams hp = Ui.lp(this, 4);
        TextView head = Ui.text(this, pass + " / " + results.size() + " 项通过", 15f, Ui.TEXT, true);
        box.addView(head, hp);
        box.addView(Ui.label(this, "检查的是：客户端发来的请求，网关转发给上游时有没有少字段。"
                + "全部通过说明不存在「请求被削薄」这一类问题；有失败请把结果发我。"), Ui.lp(this, 10));
        Ui.dialog(this, "自检", Ui.scroller(this, box), "关闭", null, null, null);
    }

    /** 把几个控件塞进一个竖排容器给对话框当内容（AlertDialog 只接受一个 View）。 */
    private ViewGroup wrapDialog(android.view.View... views) {
        LinearLayout box = Ui.column(this);
        int pad = Ui.dp(this, 18);
        box.setPadding(pad, Ui.dp(this, 6), pad, 0);
        for (android.view.View v : views) box.addView(v, Ui.lp(this, 10));
        return box;
    }

}
