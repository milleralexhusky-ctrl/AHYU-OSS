package com.cpa.gateway;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.cpa.gateway.core.Kimi;
import com.cpa.gateway.core.Log;
import com.cpa.gateway.core.Threads;

import org.json.JSONObject;

/**
 * Kimi 登录：网页登录（不再用设备码）。
 *
 * 在页面里登录后，Kimi 自己的请求会带 Authorization，或者把 token 写进网页存储；
 * 我们从这两处抓一个下来写进账号。想用 Moonshot 开放平台的 key，也可以走
 * 「OpenAI 兼容（自定义）」填 https://api.moonshot.cn/v1。
 */
public class KimiLoginActivity extends Activity {

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
        root.addView(Ui.appBar(this, back, "登录 Kimi", null));
        status = Ui.label(this, "正在打开 Kimi 登录页…");
        root.addView(status, Ui.lp(this, 8));
        LinearLayout card = Ui.column(this);
        card.setBackground(Ui.roundStroke(this, Ui.CARD, 18, Ui.STROKE));
        card.setClipToOutline(true);
        web = LoginWebView.create(this, (url, opened) -> post(() -> setStatus(opened
                ? "需要跳到其它应用完成登录，完成后回到本页即可（会自动继续）。"
                : "这个登录方式需要对应的应用，试试手机号登录。")));
        LoginWebView.fill(card, web);
        root.addView(card, Ui.fill(this, 10));
        // 抓到凭据就立刻加号（不用等轮询）
        LoginWebView.AUTH_SINK = (host, value) -> {
            if (saved || value == null || value.length() < 20 || host == null) return;
            if (!(host.contains("kimi") || host.contains("moonshot"))) return;
            String t = value.startsWith("Bearer ") ? value.substring(7).trim() : value.trim();
            Log.i("kimi 从请求头直接拿到 token（长度 " + t.length() + "）");
            saveToken(t);
        };
        setContentView(Ui.glassScaffold(this, root));
        web.loadUrl("https://www.kimi.com");
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
            try {
                web.evaluateJavascript(
                        "(function(){try{var o={};var ls=window.localStorage||{};"
                                + "for(var i=0;i<ls.length;i++){var k=ls.key(i);var v=ls.getItem(k)||'';"
                                + "if(/token|auth|jwt/i.test(k)||/eyJ/.test(v)){o[k]=v;}}"
                                + "return JSON.stringify(o);}catch(e){return '{}';}})();",
                        value -> {
                            if (saved || value == null || value.length() < 10) return;
                            try {
                                JSONObject o = new JSONObject(value);
                                java.util.Iterator<String> it = o.keys();
                                while (it.hasNext()) {
                                    String k = it.next();
                                    String v = o.optString(k, "");
                                    if (v.length() > 20 && v.startsWith("eyJ")) {
                                        Log.i("kimi 从网页存储抓到 token（" + k + "）");
                                        saveToken(v);
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
            if (value != null && value.length() > 20 && host != null
                    && (host.contains("kimi") || host.contains("moonshot"))) {
                String t = value.startsWith("Bearer ") ? value.substring(7).trim() : value.trim();
                Log.i("kimi 从请求头抓到 token（长度 " + t.length() + "）");
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
                // 存完整 cookie（含 refresh_token 之类的 httpOnly 值）：token 过期后靠它换新的
                String all = CookieManager.getInstance().getCookie("https://www.kimi.com");
                String www = CookieManager.getInstance().getCookie("https://kimi.moonshot.cn");
                acc.put("cookie", joinCookies(all, www));
                acc.put("refresh_token", findCookieValue(all, "refresh_token"));
                Log.i("kimi 已保存凭据（cookie 长度 " + (all == null ? 0 : all.length())
                        + " · refresh_token " + (findCookieValue(all, "refresh_token").isEmpty() ? "无" : "有") + "）");
                JSONObject out = Kimi.registerProvider(acc);
                Log.i("kimi 账号已添加：" + out.optString("provider"));
                post(() -> setStatus("已添加 " + out.optString("provider")
                        + "（" + out.optInt("models") + " 个模型）"));
                backHomeSoon();
            } catch (Throwable t) {
                saved = false;
                Log.w("kimi 添加失败：" + t);
                post(() -> setStatus("添加失败：" + t.getMessage()));
            }
        });
    }

    private static String joinCookies(String a, String b) {
        StringBuilder sb = new StringBuilder();
        if (a != null && !a.isEmpty()) sb.append(a);
        if (b != null && !b.isEmpty()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(b);
        }
        return sb.toString();
    }

    /** 从 cookie 串里取某个键的值（取不到返回空串）。 */
    private static String findCookieValue(String cookies, String key) {
        if (cookies == null) return "";
        for (String part : cookies.split(";")) {
            String p = part.trim();
            if (p.startsWith(key + "=")) return p.substring(key.length() + 1);
        }
        return "";
    }

    private void setStatus(final String text) {
        post(() -> status.setText(text));
    }

    private void post(Runnable r) {
        runOnUiThread(r);
    }

    /** 加号成功后自动回主页（用户不用再手动返回）。 */
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
