package com.cpa.gateway;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.cpa.gateway.core.Log;
import com.cpa.gateway.core.Threads;
import com.cpa.gateway.core.Xai;

import org.json.JSONObject;

/**
 * Grok（xAI）登录：网页登录（不再用设备码）。
 *
 * 在 grok.com 登录后，它的请求会带 Authorization，同时浏览器里会有 `sso` 这条 cookie——
 * 两个来源随便抓到哪一个都能用（sso 那条我们已经在 Xai.webChat 里实现好了）。
 */
public class XaiLoginActivity extends Activity {

    private WebView web;
    private TextView status;
    private volatile boolean alive = true;
    private volatile boolean saved = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.darkSystemBars(this);
        LinearLayout root = Ui.column(this);
        int pad = Ui.dp(this, 16);
        root.setPadding(pad, pad, pad, pad);
        TextView back = Ui.barButton(this, "‹ 返回");
        back.setOnClickListener(v -> finish());
        root.addView(Ui.appBar(this, back, "登录 Grok", null));
        status = Ui.label(this, "正在打开 grok.com…");
        root.addView(status, Ui.lp(this, 8));
        LinearLayout card = Ui.column(this);
        card.setBackground(Ui.roundStroke(this, Ui.CARD, 18, Ui.STROKE));
        card.setClipToOutline(true);
        web = LoginWebView.create(this, (url, opened) -> post(() -> setStatus(opened
                ? "需要跳到其它应用完成登录，完成后回到本页即可（会自动继续）。"
                : "这个登录方式需要对应的应用，试试邮箱 / Google 登录。")));
        LoginWebView.fill(card, web);
        root.addView(card, Ui.fill(this, 10));
        LoginWebView.AUTH_SINK = (host, value) -> {
            if (saved || value == null || value.length() < 20 || host == null) return;
            if (!(host.contains("grok") || host.contains("x.ai"))) return;
            String t = value.startsWith("Bearer ") ? value.substring(7).trim() : value.trim();
            Log.i("xai 从请求头直接拿到凭据（长度 " + t.length() + "）");
            saveToken(t);
        };
        // 底层强度 0.50 —— 登录：表单
        setContentView(Ui.glassScaffold(this, root, 0.50f));
        web.loadUrl("https://grok.com");
        Threads.io(this::watch);
    }

    private void watch() {
        long deadline = System.currentTimeMillis() + 8 * 60 * 1000L;
        while (alive && !saved && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(2500);
            } catch (InterruptedException ie) {
                return;
            }
            // ① sso cookie（grok.com）
            try {
                String ck = CookieManager.getInstance().getCookie("https://grok.com");
                if (ck != null) {
                    for (String part : ck.split(";")) {
                        String p = part.trim();
                        if (p.startsWith("sso=") && p.length() > 40) {
                            Log.i("xai 从 cookie 抓到 sso（长度 " + p.length() + "）");
                            saveToken(p);
                            return;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            // ② 请求头里的 Authorization
            String host = LoginWebView.lastAuthHost;   // 先读 host（take 会一并清掉）
            String value = LoginWebView.takeAuthValue();
            if (value != null && value.length() > 20 && host != null
                    && (host.contains("grok") || host.contains("x.ai"))) {
                String t = value.startsWith("Bearer ") ? value.substring(7).trim() : value.trim();
                Log.i("xai 从请求头抓到凭据（长度 " + t.length() + "）");
                saveToken(t);
                return;
            }
        }
    }

    private void saveToken(final String token) {
        if (saved || token == null || token.length() < 20) return;
        saved = true;
        post(() -> setStatus("检测到已登录，正在添加账号…"));
        Threads.io(() -> {
            try {
                JSONObject acc = new JSONObject();
                acc.put("access_token", token);
                JSONObject out = Xai.registerProvider(acc);
                Log.i("xai 账号已添加：" + out.optString("provider"));
                post(() -> setStatus("已添加 " + out.optString("provider")
                        + "（" + out.optInt("models") + " 个模型）"));
                backHomeSoon();
            } catch (Throwable t) {
                saved = false;
                Log.w("xai 添加失败：" + t);
                post(() -> setStatus("添加失败：" + t.getMessage()));
            }
        });
    }


    /** 加号成功后自动回主页。 */
    private void backHomeSoon() {
        post(() -> new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            try {
                android.content.Intent it = new android.content.Intent(this,
                        com.cpa.gateway.MainActivity.class);
                it.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(it);
            } catch (Throwable ignored) {
            }
            finish();
        }, 1200));
    }

    private void setStatus(final String text) {
        post(() -> status.setText(text));
    }

    private void post(Runnable r) {
        runOnUiThread(r);
    }

    @Override
    protected void onDestroy() {
        alive = false;
        LoginWebView.AUTH_SINK = null;   // 别把监听器留在静态字段里（否则泄漏整个 Activity）
        try {
            if (web != null) {
                web.stopLoading();
                web.loadUrl("about:blank");
                web.destroy();          // 不销毁 WebView 会连带泄漏 Activity
                web = null;
            }
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }
}
