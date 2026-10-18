package com.cpa.gateway;

import com.cpa.gateway.core.Threads;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.cpa.gateway.core.Doubao;
import com.cpa.gateway.core.Log;

import org.json.JSONObject;

/** 豆包登录：在页面里登录（会跳抖音 App 授权），成功后自动抓取会话 cookie 并加号。 */
public class DoubaoLoginActivity extends LoginPageBase {

    private WebView web;
    private volatile boolean pageAlive = true;
    private volatile boolean saved = false;
    private int ticks = 0;

    @Override
    protected String pageTitle() {
        return "登录豆包";
    }

    @Override
    protected String pageSubtitle() {
        return "网页登录 · 自动抓取会话 cookie";
    }

    @Override
    protected void buildBody(LinearLayout root) {
        // 方向定了：不做"手抄 cookie"（安卓单机上一个都不该让用户抄）。
        // 就给一句话说清怎么做：在这个页面里点「抖音登录」，会跳抖音 App，同意后自动回来。
        TextView tip = Ui.label(this, "在下面这个页面里直接登录就行：点「抖音登录」会跳到抖音 App，"
                + "点同意后自动回到这里；登录完成会自动加号，不需要粘贴任何东西。");
        tip.setLineSpacing(0f, 1.25f);
        root.addView(tip, Ui.lp(this, 10));

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
        // 直接挂到 root：套一层 Ui.card 会丢掉 weight=1，网页区会塌成一条
        root.addView(webCard, Ui.fill(this, 8));
    }

    @Override
    protected void onReady() {
        setHint("正在打开 www.doubao.com …");
        web.loadUrl("https://www.doubao.com/chat/");
        startWatch();
    }

    /** Cookie 只能从 CookieManager 读，且要等页面稳定之后才会有。 */
    private void startWatch() {
        Runnable poll = new Runnable() {
            @Override
            public void run() {
                if (saved || isFinishing()) return;
                ticks++;
                String cookie = CookieManager.getInstance().getCookie("https://www.doubao.com");
                if (cookie != null && cookie.contains("sessionid=")) {
                    setHint("检测到已登录，正在添加账号…");
                    save(cookie, true);
                    return;
                }
                Log.i("豆包登录检查 #" + ticks + "：cookie 长度=" + (cookie == null ? 0 : cookie.length()));
                if (ticks >= 60) {
                    setHint("等登录超时了：返回重试一次，或在页面里重新登录一下。");
                    return;
                }
                if (ticks % 5 == 0) setHint("还没检测到登录（已等 " + (ticks * 3) + " 秒）…");
                ui().postDelayed(this, 3000);
            }
        };
        ui().postDelayed(poll, 3000);
    }

    private void save(final String cookie, final boolean auto) {
        if (saved) return;
        saved = true;
        setHint("正在校验并添加…");
        Threads.io(() -> {
            try {
                JSONObject account = new JSONObject();
                account.put("cookie", cookie);
                JSONObject out = Doubao.registerProvider(account);
                final String who = out.optString("provider", "Doubao")
                        + (out.optString("who", "").isEmpty() ? "" : "（" + out.optString("who", "") + "）");
                Log.i("豆包账号已添加：" + who + " ·模型 " + out.optInt("models"));
                doneAndFinish("已添加 " + who);
            } catch (Exception e) {
                saved = false;
                Log.w("豆包添加失败：" + e);
                setStatus("添加失败：" + e.getMessage());
            }
        });
    }

    /** 销毁 WebView：不销毁会连带泄漏整个 Activity。 */
    @Override
    protected void onDestroy() {
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
}
