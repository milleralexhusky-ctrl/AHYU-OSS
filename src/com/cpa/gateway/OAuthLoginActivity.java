package com.cpa.gateway;

import com.cpa.gateway.core.Threads;
import android.app.Activity;
import android.graphics.Color;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.webkit.WebView;

import com.cpa.gateway.core.Claude;
import com.cpa.gateway.core.Codex;
import com.cpa.gateway.core.Gateway;
import com.cpa.gateway.core.Log;
import com.cpa.gateway.core.OAuth;

import org.json.JSONObject;

import java.util.UUID;

/**
 * Authorization that stays inside the app: the provider's login page runs in an embedded WebView,
 * the loopback callback is caught on the port the client is registered with, and the code is
 * exchanged for credentials that become a channel straight away.
 */
public class OAuthLoginActivity extends Activity {

    public static final String EXTRA_KIND = "kind";
    public static final String KIND_CLAUDE = "claude";
    public static final String KIND_CODEX = "codex";

    private TextView status;
    private ProgressBar spinner;
    private WebView web;
    private LinearLayout webCard;
    private String authUrl = "";
    private String kind = KIND_CLAUDE;
    private volatile boolean checkNow;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.darkSystemBars(this);
        kind = getIntent().getStringExtra(EXTRA_KIND);
        if (kind == null || kind.isEmpty()) kind = KIND_CLAUDE;

        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Color.TRANSPARENT);
        root.setPadding(Ui.dp(this, 16), Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 16));

        /* header */
        TextView back = Ui.barButton(this, "‹ 返回");
        back.setOnClickListener(v -> finish());
        LinearLayout header = Ui.appBar(this, back, title(), kindHint());
        TextView browser = Ui.barButton(this, "浏览器打开");
        browser.setOnClickListener(v -> openBrowser());
        header.addView(browser);
        root.addView(header);

        /* status strip */
        LinearLayout statusRow = Ui.row(this);
        spinner = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        spinner.setIndeterminate(true);
        try {
            spinner.setIndeterminateTintList(ColorStateList.valueOf(Ui.ACCENT));
        } catch (Throwable ignored) {
        }
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(Ui.dp(this, 18), Ui.dp(this, 18));
        sp.rightMargin = Ui.dp(this, 10);
        statusRow.addView(spinner, sp);
        status = Ui.label(this, "正在准备授权页…");
        statusRow.addView(status, Ui.weight(this, 1f, 0));
        root.addView(Ui.card(this, statusRow), Ui.lp(this, 14));

        /* the page itself, in a rounded frame */
        webCard = Ui.column(this);
        webCard.setBackground(Ui.roundStroke(this, Ui.CARD, 18, Ui.STROKE));
        webCard.setClipToOutline(true);
        web = LoginWebView.create(this, (url, opened) -> status.setText(opened
                ? "这一步需要跳转到其它 App 完成，完成后本页会自动继续。"
                : "这个登录方式需要对应的 App，可点右上角「浏览器打开」。"));
        LoginWebView.fill(webCard, web);
        root.addView(webCard, Ui.fill(this, 14));

        /* bottom actions */
        LinearLayout bottom = Ui.row(this);
        TextView rescue = Ui.outlineButton(this, "改用系统浏览器");
        rescue.setOnClickListener(v -> openBrowser());
        TextView check = Ui.primaryButton(this, "我已完成登录");
        check.setOnClickListener(v -> {
            checkNow = true;
            setStatus("正在检查登录状态…", true);
        });
        LinearLayout.LayoutParams b1 = Ui.weight(this, 1f, 0);
        b1.rightMargin = Ui.dp(this, 8);
        bottom.addView(rescue, b1);
        bottom.addView(check, Ui.weight(this, 1f, 0));
        root.addView(bottom, Ui.lp(this, 14));

        // 底层强度 0.50 —— 登录：表单
        setContentView(Ui.glassScaffold(this, root, 0.50f));
        Threads.io(this::run);
    }

    private String title() {
        return KIND_CODEX.equals(kind) ? "登录 ChatGPT" : "登录 Claude";
    }

    private String kindHint() {
        return KIND_CODEX.equals(kind) ? "ChatGPT 账号 · Codex 通道" : "claude.ai 账号 · Pro / Max";
    }

    private void setStatus(final String text, final boolean busy) {
        handler.post(() -> {
            status.setText(text);
            spinner.setVisibility(busy ? View.VISIBLE : View.INVISIBLE);
        });
    }

    private void openBrowser() {
        if (authUrl.isEmpty()) {
            toast("还没准备好，稍等一下");
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            setStatus("已在浏览器打开授权页，完成后本页会自动继续。", true);
        } catch (Exception e) {
            toast("打不开浏览器：" + e);
        }
    }

    private void run() {
        try {
            String code;
            JSONObject accountJson;
            if (KIND_CODEX.equals(kind)) {
                com.cpa.gateway.core.OAuth.Pkce pkce = new com.cpa.gateway.core.OAuth.Pkce();
                String state = UUID.randomUUID().toString();
                authUrl = Codex.authorizeUrl(pkce, state);
                Log.i("codex 授权开始(应用内)：" + authUrl);
                showPage();
                code = OAuth.waitForCode("ChatGPT", Codex.CALLBACK_PORT, state, 10 * 60 * 1000L);
                setStatus("正在换取凭据…", true);
                Codex.Account acc = Codex.exchangeCode(code, pkce);
                accountJson = acc.toJson();
                Gateway.init(getApplicationContext());
                JSONObject reg = Codex.registerProvider(accountJson);
                Log.i("codex 登录成功：" + reg.optString("provider") + "（" + reg.optInt("models") + " 个模型）");
            } else {
                OAuth.Pkce pkce = new OAuth.Pkce();
                String state = UUID.randomUUID().toString();
                authUrl = Claude.authorizeUrl(pkce, state);
                Log.i("claude 授权开始(应用内)：" + authUrl);
                showPage();
                code = OAuth.waitForCode("Claude", Claude.CALLBACK_PORT, state, 10 * 60 * 1000L);
                setStatus("正在换取凭据…", true);
                Claude.Account acc = Claude.exchangeCode(code, state, pkce);
                accountJson = acc.toJson();
                Gateway.init(getApplicationContext());
                JSONObject reg = Claude.registerProvider(accountJson);
                Log.i("claude 登录成功：" + reg.optString("provider") + "（" + reg.optInt("models") + " 个模型）");
            }
            finishWith(accountJson.toString(), null);
        } catch (final Exception e) {
            Log.w("登录失败：" + e);
            String msg = String.valueOf(e.getMessage());
            if (msg != null && msg.contains("Socket closed")) {
                // a newer login attempt took over this port; leave quietly instead of alarming the user
                Log.i("本次登录已被更新的尝试取代，静默退出");
                finishWith(null, null);
                return;
            }
            finishWith(null, msg);
        }
    }

    private void showPage() {
        handler.post(() -> {
            setStatus("请在下方的页面里完成登录，成功后会自动返回", true);
            try {
                web.loadUrl(authUrl);
            } catch (Exception e) {
                toast("页面加载失败：" + e);
            }
            web.postDelayed(() -> Log.i("登录页 WebView 尺寸 " + web.getWidth() + "x" + web.getHeight()
                    + "，可见=" + (web.getVisibility() == View.VISIBLE && web.getHeight() > 0)), 1500);
        });
    }

    @Override
    protected void onDestroy() {
        // 销毁 WebView：不销毁会连带泄漏整个 Activity（它的 WebViewCore 持有 Activity 引用）
        try {
            if (web != null) {
                web.stopLoading();
                web.loadUrl("about:blank");
                web.destroy();
                web = null;
            }
        } catch (Throwable ignored) {
        }

        OAuth.cancel(KIND_CODEX.equals(kind) ? Codex.CALLBACK_PORT : Claude.CALLBACK_PORT);
        super.onDestroy();
    }

    private void toast(String msg) {
        handler.post(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    private void finishWith(final String credentials, final String error) {
        handler.post(() -> {
            Intent out = new Intent();
            if (credentials != null) out.putExtra("credentials", credentials);
            if (error != null) out.putExtra("error", error);
            setResult(credentials != null ? RESULT_OK : RESULT_CANCELED, out);
            if (error != null) Toast.makeText(this, "登录失败：" + error, Toast.LENGTH_LONG).show();
            else if (credentials != null) Toast.makeText(this, "账号已加入", Toast.LENGTH_LONG).show();
            finish();
        });
    }
}
