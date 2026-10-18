package com.cpa.gateway;

import com.cpa.gateway.core.Threads;
import android.content.Intent;
import android.net.Uri;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.webkit.WebView;

import com.cpa.gateway.core.Log;
import com.cpa.gateway.core.OAuth;
import com.cpa.gateway.core.Trae;
import com.cpa.gateway.core.Upstreams;

/** 字节 Trae 登录：走 OAuth，回调由本机端口接住。 */
public class TraeLoginActivity extends LoginPageBase {

    private WebView web;
    private volatile String authUrl = "";
    private String machineId = "";
    private String deviceId = "";

    @Override
    protected String pageTitle() {
        return "登录字节 Trae";
    }

    @Override
    protected String pageSubtitle() {
        return "SOLO 通道 · 每日签到送积分";
    }

    @Override
    protected void buildBody(LinearLayout root) {
        // 这里原来自建了一个"转圈 + 文字"的状态条，但 LoginPageBase 已经在标题栏
        // 下方建了同一个东西（statusCard）。结果页面上出现**两个状态条**：
        // 基类那个的 statusView 初始为空串、而本类从不调 setStatus()，
        // 于是它永远只有转圈、没有文字、也不会消失——就是用户看到的
        // "两个圆，一个提示、一个一直在加载"。
        // 现在统一用基类的 setStatus(text, busy)，与 Loomy/Kimi/DeepSeek/Doubao 一致。

        TextView browser = Ui.outlineButton(this, "用系统浏览器打开授权页");
        browser.setOnClickListener(v -> openBrowser());
        root.addView(browser, Ui.lp(this, 8));

        LinearLayout webCard = Ui.column(this);
        webCard.setBackground(Ui.drawablePanel(this));
        webCard.setClipToOutline(true);
        web = LoginWebView.create(this, (url, opened) -> post(() ->
                setStatus(opened
                        ? "需要跳到其它 App 完成授权，完成后回到本页即可。"
                        : "这个步骤需要对应的 App，可点上面的「浏览器打开」。", true)));
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
            machineId = Trae.randomHex(32);
            deviceId = Trae.randomDeviceId();
            authUrl = Trae.buildLoginUrl(machineId, deviceId);
            Log.i("trae 授权开始：" + authUrl);
            post(() -> {
                setStatus("请在下方的页面里完成登录，成功后会自动返回。", true);
                web.loadUrl(authUrl);
            });
            String raw = OAuth.waitForRawQuery("Trae", Trae.CALLBACK_PORT, 10 * 60 * 1000L);
            Log.i("trae 收到回调：" + Upstreams.shortenPublic(raw));
            post(() -> setStatus("正在换取凭据…", true));
            Trae.Account acc = Trae.parseCallback(raw);
            acc.machineId = machineId;
            acc.deviceId = deviceId;
            org.json.JSONObject registered = Trae.registerProvider(acc.toJson());
            Log.i("trae 登录成功：" + registered.optString("provider") + "（" + registered.optInt("models") + " 个模型）");
            post(() -> {
                Intent out = new Intent();
                out.putExtra("credentials", acc.toJson().toString());
                setResult(RESULT_OK, out);
                doneAndFinish("Trae 账号已加入");
            });
        } catch (final Exception e) {
            Log.w("trae 登录失败：" + e);
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
        OAuth.cancel(Trae.CALLBACK_PORT);
        super.onDestroy();
    }
}
