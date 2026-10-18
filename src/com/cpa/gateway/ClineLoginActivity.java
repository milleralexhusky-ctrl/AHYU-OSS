package com.cpa.gateway;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.cpa.gateway.core.Cline;
import com.cpa.gateway.core.Log;
import com.cpa.gateway.core.Threads;

import java.util.List;

/**
 * Cline 登录：**直接给你一个网页**（不搞设备码那套）。
 *
 * 在页面里用 GitHub / 邮箱登录都行；登录成功后 Cline 自己的 API 请求会带
 * `Authorization: Bearer workos:…`，我们从请求头里直接抓下来写进账号——
 * 不用抄、不用扫码、不用先去别处登录。
 */
public class ClineLoginActivity extends Activity {

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
        root.addView(Ui.appBar(this, back, "登录 Cline", null));

        status = Ui.label(this, "正在打开 Cline 登录页…");
        root.addView(status, Ui.lp(this, 8));

        LinearLayout webCard = Ui.column(this);
        webCard.setBackground(Ui.roundStroke(this, Ui.CARD, 18, Ui.STROKE));
        webCard.setClipToOutline(true);
        web = LoginWebView.create(this, (url, opened) -> post(() -> setStatus(opened
                ? "需要跳到其它应用完成授权，完成后回到本页即可（这里会自动继续）。"
                : "这个登录方式需要对应的应用，可以试试其它方式。")));
        LoginWebView.fill(webCard, web);
        root.addView(webCard, Ui.fill(this, 10));

        LoginWebView.AUTH_SINK = (host, value) -> {
            if (saved || value == null || value.length() < 20 || host == null) return;
            if (!host.contains("cline")) return;
            String t = value.startsWith("Bearer ") ? value.substring(7).trim() : value.trim();
            Log.i("cline 从请求头直接拿到凭据（长度 " + t.length() + "）");
            saveCredential(t);
        };
        setContentView(Ui.glassScaffold(this, root));
        web.loadUrl("https://app.cline.bot/login");
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
            // 双保险之一：网页存储里如果放了 token（含 workos / JWT 特征）也直接拿来用
            try {
                web.evaluateJavascript(
                        "(function(){try{var o={};var ls=window.localStorage||{};"
                                + "for(var i=0;i<ls.length;i++){var k=ls.key(i);var v=ls.getItem(k)||'';"
                                + "if(/token|auth|jwt|workos/i.test(k)||/workos:|eyJ/.test(v)){o[k]=v;}}"
                                + "return JSON.stringify(o);}catch(e){return '{}';}})();",
                        value -> {
                            if (saved || value == null || value.length() < 10) return;
                            try {
                                org.json.JSONObject o = new org.json.JSONObject(value);
                                java.util.Iterator<String> it = o.keys();
                                while (it.hasNext()) {
                                    String k = it.next();
                                    String v = o.optString(k, "");
                                    if (v.length() > 20 && (v.contains("workos:") || v.startsWith("eyJ"))) {
                                        String access = v.startsWith("Bearer ") ? v.substring(7) : v;
                                        Log.i("cline 从网页存储抓到凭据（" + k + "）");
                                        saveCredential(access);
                                        return;
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                        });
            } catch (Throwable ignored) {
            }
            String host = LoginWebView.lastAuthHost;   // 先读 host（take 会一并清掉）
            String value = LoginWebView.takeAuthValue();
            if (value == null || value.length() < 20 || host == null || !host.contains("cline")) {
                continue;
            }
            String access = value.startsWith("Bearer ") ? value.substring(7).trim() : value.trim();
            Log.i("cline 从请求头抓到凭据（长度 " + access.length() + "）");
            saveCredential(access);
            return;
        }
        if (!saved) post(() -> setStatus("还没检测到登录：请在上面的页面里完成登录（GitHub / 邮箱都行）。"));
    }

    /** 拿到凭据就写账号（两个来源都会调它）。 */
    private void saveCredential(final String access) {
        if (saved || access == null || access.length() < 20) return;
        saved = true;
        post(() -> setStatus("检测到已登录，正在添加账号…"));
        Threads.io(() -> {
            try {
                Cline.Account acc = new Cline.Account();
                acc.access = access;
                List<String> models = Cline.fetchModels();
                org.json.JSONObject out = Cline.registerProvider(acc, models);
                Log.i("cline 账号已添加：" + out.optString("provider") + " ·模型 " + out.optInt("models"));
                post(() -> setStatus("已添加 " + out.optString("provider")
                        + "（" + out.optInt("models") + " 个模型）"));
                backHomeSoon();
            } catch (Throwable t) {
                saved = false;
                Log.w("cline 添加失败：" + t);
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
