package com.cpa.gateway;

import com.cpa.gateway.core.Threads;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.webkit.WebView;

import com.cpa.gateway.core.Antigravity;
import com.cpa.gateway.core.Log;
import com.cpa.gateway.core.OAuth;

import java.util.UUID;

/** Google Antigravity 登录：走 OAuth，回调由本机端口接住。 */
public class AntigravityLoginActivity extends LoginPageBase {

    private WebView web;
    private volatile String authUrl = "";

    @Override
    protected String pageTitle() {
        return "登录 Google 账号";
    }

    @Override
    protected String pageSubtitle() {
        return "Antigravity · 免费 Gemini 额度";
    }

    @Override
    protected void buildBody(LinearLayout root) {
        // 原来自建了一个"转圈 + 文字"的状态行，但 LoginPageBase 已在标题栏下方
        // 建了同一个东西（statusCard）。两处并存 -> 页面上出现两个状态条，
        // 其中基类那个的文字一直是空串（本类从不调 setStatus），就变成
        // "一个圆在转、永远不停、也没有文字"。现在统一走基类的 setStatus。

        TextView browser = Ui.outlineButton(this, "用系统浏览器打开授权页");
        browser.setOnClickListener(v -> openBrowser());
        root.addView(browser, Ui.lp(this, 8));

        LinearLayout webCard = Ui.column(this);
        webCard.setBackground(Ui.drawablePanel(this));
        webCard.setClipToOutline(true);
        web = LoginWebView.create(this, (url, opened) -> post(() ->
                setStatus(opened
                        ? "需要跳到其它 App 完成授权，完成后回到本页即可。"
                        : "这一步需要对应的 App，可点上面的「浏览器打开」。", true)));
        LoginWebView.fill(webCard, web);
        root.addView(webCard, Ui.fill(this, 14));
    }

    @Override
    protected void onReady() {
        Threads.io(this::run);
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
            String state = UUID.randomUUID().toString();
            /* PKCE：code 即使被本机别的应用截走，没有 verifier 也换不了 token。 */
            OAuth.Pkce pkce = new OAuth.Pkce();
            authUrl = Antigravity.authorizeUrl(state, pkce.challenge);
            Log.i("antigravity 授权开始：" + authUrl);
            post(() -> {
                setStatus("请在下方的页面里选择 Google 账号并授权。", true);
                web.loadUrl(authUrl);
            });
            String code = OAuth.waitForCode("Antigravity", Antigravity.CALLBACK_PORT, state, 10 * 60 * 1000L);
            post(() -> setStatus("正在换取凭据…", true));
            Antigravity.Account acc = Antigravity.exchangeCode(code, pkce.verifier);
            post(() -> setStatus("正在开通/查询 Antigravity 项目…", true));
            org.json.JSONObject registered = Antigravity.registerProvider(acc.toJson());
            Log.i("antigravity 登录完成：" + registered.optString("provider")
                    + " project=" + registered.optString("project"));
            post(() -> {
                Intent out = new Intent();
                out.putExtra("credentials", acc.toJson().toString());
                setResult(RESULT_OK, out);
                doneAndFinish("账号已加入，点账号详情可拉取模型");
            });
        } catch (final Exception e) {
            Log.w("antigravity 登录失败：" + e);
            post(() -> {
                setStatus("登录失败：" + e.getMessage(), false);
                toast("登录失败：" + e.getMessage());
            });
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
        OAuth.cancel(Antigravity.CALLBACK_PORT);
        super.onDestroy();
    }
}
