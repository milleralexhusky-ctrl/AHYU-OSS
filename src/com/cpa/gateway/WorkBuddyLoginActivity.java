package com.cpa.gateway;

import com.cpa.gateway.core.Threads;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.webkit.WebView;

import com.cpa.gateway.core.Gateway;
import com.cpa.gateway.core.Log;
import com.cpa.gateway.core.WorkBuddy;

import org.json.JSONObject;

/**
 * WorkBuddy 授权：登录页在 App 内跑，整个尝试绑定到一个服务端 state 上轮询，
 * 因此用户最终在哪儿完成登录都无所谓（页面会跳去微信，WebView 跟不过去）。
 */
public class WorkBuddyLoginActivity extends LoginPageBase {

    public static final String EXTRA_REALM = "realm";
    public static final String RESULT_CREDENTIALS = "credentials";

    private WebView web;
    private String realm = WorkBuddy.REALM_CN;
    private String base = "";
    private volatile String authUrl = "";
    private volatile boolean finished;
    private volatile boolean checkNow;

    @Override
    protected String pageTitle() {
        return "登录 WorkBuddy";
    }

    @Override
    protected String pageSubtitle() {
        return WorkBuddy.REALM_GLOBAL.equals(realm) ? "国际版 · workbuddy.ai" : "国内版 · codebuddy.cn";
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        realm = getIntent().getStringExtra(EXTRA_REALM);
        if (realm == null || realm.isEmpty()) realm = WorkBuddy.REALM_CN;
        base = getIntent().getStringExtra("base");
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void buildBody(LinearLayout root) {
        LinearLayout webCard = Ui.column(this);
        webCard.setBackground(Ui.roundStroke(this, Ui.CARD, 18, Ui.STROKE));
        webCard.setClipToOutline(true);
        web = LoginWebView.create(this, (url, opened) -> post(() -> setStatus(opened
                ? "需要跳转到微信完成授权，完成后回到本页即可，这里会自动检测。"
                : "这个登录方式需要对应的 App，可点下面的按钮换浏览器。", true)));
        LoginWebView.fill(webCard, web);
        root.addView(webCard, Ui.fill(this, 14));

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
    }

    @Override
    protected void onReady() {
        Threads.io(this::begin);
    }

    private void openBrowser() {
        if (authUrl.isEmpty()) {
            toast("还没拿到授权地址，稍等一下");
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            setStatus("已在浏览器打开授权页，完成后本页会自动继续。", true);
        } catch (Exception e) {
            toast("打不开浏览器：" + e);
        }
    }

    private void begin() {
        try {
            final JSONObject start = WorkBuddy.beginLogin(realm, base);
            final String state = start.optString("state", "");
            authUrl = start.optString("authUrl", "");
            Log.i("workbuddy 授权已开始 state=" + state + " url=" + authUrl);
            post(() -> {
                setStatus("请在下方页面完成登录，微信授权完成后回到本页即可", true);
                if (!authUrl.isEmpty()) web.loadUrl(authUrl);
            });
            long deadline = System.currentTimeMillis() + 10 * 60 * 1000L;
            while (System.currentTimeMillis() < deadline && !finished && !isFinishing()) {
                Thread.sleep(checkNow ? 300 : 3000);
                checkNow = false;
                JSONObject res = WorkBuddy.pollLogin(realm, base, state);
                if ("done".equals(res.optString("status", "pending"))) {
                    JSONObject account = res.optJSONObject("account");
                    if (account != null) {
                        Gateway.init(getApplicationContext());
                        try {
                            JSONObject registered = WorkBuddy.registerProvider(account);
                            Log.i("workbuddy 授权成功，已写入通道 " + registered.optString("provider")
                                    + "（" + registered.optInt("models") + " 个模型）");
                        } catch (Exception e) {
                            Log.w("写入通道失败（凭据仍会回填）：" + e);
                        }
                        finishWith(account.toString(), null);
                        return;
                    }
                } else {
                    setStatus("等待完成登录…（code=" + res.optInt("code", -1) + "）", true);
                }
            }
            if (!finished) finishWith(null, "登录超时（10 分钟），请重试");
        } catch (final Exception e) {
            Log.w("workbuddy 授权失败：" + e);
            finishWith(null, String.valueOf(e.getMessage()));
        }
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

        LoginWebView.clearSession(this);
        finished = true;
        com.cpa.gateway.core.OAuth.cancelActive();
        super.onDestroy();
    }

    private void finishWith(final String credentials, final String error) {
        if (finished && credentials == null) return;
        finished = true;
        Log.i("workbuddy 授权结束：" + (credentials != null ? "成功" : "失败 " + error));
        post(() -> {
            Intent out = new Intent();
            if (credentials != null) out.putExtra(RESULT_CREDENTIALS, credentials);
            if (error != null) out.putExtra("error", error);
            setResult(credentials != null ? RESULT_OK : RESULT_CANCELED, out);
            if (credentials != null) {
                android.widget.Toast.makeText(this, "授权成功，账号已加入", android.widget.Toast.LENGTH_LONG).show();
            }
            finish();
        });
    }
}
