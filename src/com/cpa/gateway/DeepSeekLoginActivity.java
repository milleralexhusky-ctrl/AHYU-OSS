package com.cpa.gateway;

import com.cpa.gateway.core.Threads;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.webkit.WebView;

import com.cpa.gateway.core.DeepSeek;
import com.cpa.gateway.core.Log;

import org.json.JSONObject;

/**
 * DeepSeek 登录：在页面里用手机号 + 验证码登录，登录成功后自动抓取会话 token 并加号。
 * 页面外壳（标题栏、状态行、返回）由 LoginPageBase 提供。
 */
public class DeepSeekLoginActivity extends LoginPageBase {

    private WebView web;
    private EditText manual;
    private volatile boolean saved = false;
    private volatile boolean alive = true;
    private int ticks = 0;

    @Override
    protected String pageTitle() {
        return "登录 DeepSeek";
    }

    @Override
    protected String pageSubtitle() {
        return "网页版登录 · 自动抓取会话 token";
    }

    @Override
    protected void buildBody(LinearLayout root) {
        // 方向定了：安卓单机上不做"手抄 token"。
        // 就在下面这个网页里用手机号 + 验证码登录，登录成功后自动抓取并加号。
        TextView tip = Ui.label(this, "在下面这个页面里用「手机号 + 验证码」登录就行"
                + "（也可以点「使用 Apple 账号登录」）；登录完成后会自动加号，不需要粘贴任何东西。");
        tip.setLineSpacing(0f, 1.25f);
        root.addView(tip, Ui.lp(this, 10));

        // 方式二：网页里登录
        LinearLayout webCard = Ui.column(this);
        webCard.setBackground(Ui.drawablePanel(this));
        webCard.setClipToOutline(true);
        // 卡片是 28dp 圆角，文字必须留出内边距，否则会压到圆角外面去（看着像"超出"）
        LinearLayout webHead = Ui.row(this);
        int hp = Ui.dp(this, 16);
        webHead.setPadding(hp, hp, hp, Ui.dp(this, 8));
        TextView webHint = Ui.label(this, "或者在下面这个页面里登录，成功后会自动添加");
        webHint.setSingleLine(true);
        webHint.setEllipsize(android.text.TextUtils.TruncateAt.END);
        webHead.addView(webHint);
        webCard.addView(webHead);
        web = LoginWebView.create(this, (url, opened) -> {
            if (opened) setHint("已交给外部 App 打开，回到本页继续。");
        }, true);
        LoginWebView.fill(webCard, web);
        root.addView(webCard, Ui.fill(this, 8));
    }

    @Override
    protected void onReady() {
        // 抓到凭据立刻加号（网页登录成功那一刻就会带 Authorization）
        LoginWebView.AUTH_SINK = (host, value) -> {
            if (saved || value == null || value.length() < 20 || host == null) return;
            if (!host.contains("deepseek")) return;
            String t = value.startsWith("Bearer ") ? value.substring(7).trim() : value.trim();
            Log.i("deepseek 从请求头直接拿到 token（长度 " + t.length() + "）");
            save(t, true);
        };
        setHint("正在打开 chat.deepseek.com …");
        web.loadUrl("https://chat.deepseek.com/sign_in");
        startWatch();
    }

    /** 轮询网页 localStorage，登录成功后 token 会出现在那里。 */
    private void startWatch() {
        Runnable poll = new Runnable() {
            @Override
            public void run() {
                if (saved || !alive || isFinishing()) return;
                ticks++;
                // 只读 localStorage 不够：有的登录态放 sessionStorage，token 也可能只在 cookie 里
                web.evaluateJavascript(
                        "(function(){try{var o={};var ls=window.localStorage||{};"
                                + "for(var i=0;i<ls.length;i++){var k=ls.key(i);o['L:'+k]=ls.getItem(k);}"
                                + "var ss=window.sessionStorage||{};"
                                + "for(var j=0;j<ss.length;j++){var k2=ss.key(j);o['S:'+k2]=ss.getItem(k2);}"
                                + "o['C:document.cookie']=document.cookie||'';"
                                + "return JSON.stringify(o);}catch(e){return '{}';}})();",
                        value -> {
                            if (saved || !alive) return;
                            // ① 最可靠：网页自己的 API 请求里带的 Authorization（登录成功立刻就有）
                            String authHost = LoginWebView.lastAuthHost;   // 先读 host（take 会一并清掉）
                            String authValue = LoginWebView.takeAuthValue();
                            if (authValue != null && authValue.length() > 20
                                    && authHost != null && authHost.contains("deepseek")) {
                                String t = authValue.startsWith("Bearer ")
                                        ? authValue.substring(7).trim() : authValue.trim();
                                Log.i("deepseek 从请求头抓到 token（长度 " + t.length() + "）");
                                setHint("检测到已登录，正在添加账号…");
                                save(t, true);
                                return;
                            }
                            String token = tokenFrom(value);
                            if (!token.isEmpty()) {
                                setHint("检测到已登录，正在添加账号…");
                                save(token, true);
                                return;
                            }
                            String cookies = android.webkit.CookieManager.getInstance().getCookie(web.getUrl());
                            // 注意：这里**不能**把存储/cookie 内容打出来——cookie 就是会话凭证，
                            // 进 logcat 和应用内日志页等于把账号摊开给人看。只报长度和个数。
                            Log.i("deepseek 登录检查 #" + ticks + "：cookie 长度="
                                    + (cookies == null ? 0 : cookies.length())
                                    + " token=" + (token.isEmpty() ? "无" : "有")
                                    + " 存储键数=" + countKeys(String.valueOf(value)));
                            // 存储里连一个像 token 的键都没有 → 大概率网页那边还没登录成功，提前把话说清楚
                            if (ticks == 5 && token.isEmpty()) {
                                setHint("网页里可能还没登录成功：请在弹出的页面里完成登录（页面里要能看到你的账号名）。"
                                        + "如果页面里已经显示登录成功了，本页会自动加号。");
                            }
                            if (ticks >= 60) {
                                setHint("等登录超时了：返回重试一次，或在页面里重新登录一下。");
                                return;
                            }
                            if (ticks % 5 == 0) setHint("还没检测到登录（已等 " + (ticks * 3) + " 秒）…");
                            ui().postDelayed(this, 3000);
                        });
            }
        };
        ui().postDelayed(poll, 3000);
    }

    /** 从 localStorage 的转储里挑出账号 token。 */
    static String tokenFrom(String localStorageJson) {
        if (localStorageJson == null || localStorageJson.isEmpty()) return "";
        try {
            JSONObject o = new JSONObject(localStorageJson);
            java.util.Iterator<String> keys = o.keys();
            String fallback = "";
            while (keys.hasNext()) {
                String k = keys.next();
                String raw = o.optString(k, "");
                if (raw == null || raw.isEmpty()) continue;
                // DeepSeek 的 localStorage.userToken 是 JSON：{"value":"<token>",...}
                // 以前把整串 JSON 当 token 用，于是"看着添加了、其实用不了"。
                String v = unwrapToken(raw);
                if (v.length() < 30) continue;
                String lower = k.toLowerCase();
                if (lower.contains("token")) return v;
                if (fallback.isEmpty() && v.matches("[A-Za-z0-9+/=_-]{40,}")) fallback = v;
            }
            return fallback;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 拆掉包在 token 外面的一层 JSON。
     *
     * DeepSeek 网页版：`localStorage.userToken` = `{"value":"<Bearer token>", ...}`
     * （官方参考实现就是 `JSON.parse(localStorage.getItem("userToken")).value`）。
     * 有的站存成 {"token": ...} / {"accessToken": ...}，一并兼容。
     */
    static String unwrapToken(String raw) {
        String v = raw == null ? "" : raw.trim();
        if (!v.startsWith("{")) return v;
        try {
            org.json.JSONObject o = new org.json.JSONObject(v);
            for (String k : new String[]{"value", "token", "userToken", "accessToken"}) {
                String got = o.optString(k, "");
                if (got != null && !got.isEmpty()) return got.trim();
            }
        } catch (Throwable ignored) {
        }
        return v;
    }

    /** 只数一下存储里有多少个键（脱敏用，不打内容）。 */
    private static int countKeys(String json) {
        if (json == null || json.isEmpty()) return 0;
        int n = 0;
        for (int i = 0; i < json.length(); i++) {
            if (json.charAt(i) == ':') n++;
        }
        return n;
    }

    /**
     * 收尾清场。
     *
     * 两个必须做掉的东西：
     *  ① 静态回调 AUTH_SINK —— 它 lambda 捕获了本 Activity，不清就是静态泄漏
     *     （Cline / Kimi / Xai 三页都清了，这里以前漏了）；
     *  ② WebView.destroy() —— 不销毁会连带泄漏 Activity。
     * 另外把 alive 置 false，让那条 3 秒轮询线程下次醒来就退出
     * （以前只有 saved 一个条件，用户中途返回时轮询会一直跑下去）。
     */
    @Override
    protected void onDestroy() {
        alive = false;
        LoginWebView.AUTH_SINK = null;
        try {
            if (web != null) {
                web.stopLoading();
                web.loadUrl("about:blank");
                web.destroy();
                web = null;
            }
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    private void save(final String token, final boolean auto) {
        if (saved) return;
        saved = true;
        setHint("正在校验并添加…");
        Threads.io(() -> {
            try {
                JSONObject account = new JSONObject();
                account.put("token", token);
                JSONObject out = DeepSeek.registerProvider(account);
                final String who = out.optString("provider", "DeepSeek")
                        + (out.optString("email", "").isEmpty() ? "" : "（" + out.optString("email", "") + "）");
                Log.i("deepseek 账号已添加：" + who + " ·模型 " + out.optInt("models"));
                doneAndFinish("已添加 " + who);
            } catch (Exception e) {
                saved = false;
                Log.w("deepseek 添加失败：" + e);
                setStatus("添加失败：" + e.getMessage());
            }
        });
    }
}
