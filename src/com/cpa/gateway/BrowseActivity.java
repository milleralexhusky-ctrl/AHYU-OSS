package com.cpa.gateway;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.cpa.gateway.core.Gateway;

/** Plain in-app browser, used to open a vendor's official site (top-ups, key pages). */
public class BrowseActivity extends Activity {

    public static final String EXTRA_URL = "url";

    /** 提成字段（原来是 onCreate 里的局部变量），这样 onDestroy 才够得着去销毁它。 */
    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.darkSystemBars(this);
        Gateway.init(this);
        String url = getIntent() == null ? "" : getIntent().getStringExtra(EXTRA_URL);
        if (url == null || url.isEmpty()) {
            finish();
            return;
        }
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Color.TRANSPARENT);
        root.setPadding(Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 12));

        TextView back = Ui.barButton(this, "‹ 返回");
        back.setOnClickListener(v -> finish());
        LinearLayout header = Ui.appBar(this, back,
                url.replaceFirst("^https?://", "").replaceAll("/.*$", ""), "软件内打开");
        TextView addToken = Ui.barButton(this, "＋令牌");
        addToken.setOnClickListener(v -> TokenAdd.show(this, url));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.rightMargin = Ui.dp(this, 8);
        header.addView(addToken, tlp);
        TextView external = Ui.ghostButton(this, "外部打开");
        external.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                Toast.makeText(this, "打不开外部浏览器：" + e, Toast.LENGTH_SHORT).show();
            }
        });
        header.addView(external);
        root.addView(header);

        LinearLayout card = Ui.column(this);
        card.setBackground(Ui.drawablePanel(this));
        card.setClipToOutline(true);
        web = LoginWebView.create(this, (u, opened) -> {
        }, false);
        LoginWebView.fill(card, web);
        root.addView(card, Ui.fill(this, 12));
        // 底层强度 0.50 —— 浏览：内容为主
        setContentView(Ui.glassScaffold(this, root, 0.50f));
        web.loadUrl(url);
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

    /** Two-way chooser used everywhere a vendor site can be opened. */
    static void openChoice(final android.app.Activity host, final String url) {
        if (url == null || url.isEmpty()) {
            Toast.makeText(host, "这个账号没有可打开的官网", Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout box = Ui.column(host);
        LinearLayout inApp = Ui.listRow(host, "软件内打开", "在当前应用里浏览，看完返回就行");
        inApp.setOnClickListener(v -> {
            host.startActivity(new Intent(host, BrowseActivity.class).putExtra(EXTRA_URL, url));
        });
        box.addView(inApp, Ui.lp(host, 8));
        LinearLayout outApp = Ui.listRow(host, "用外部浏览器打开", "交给 Chrome / 系统浏览器，方便登录和支付");
        outApp.setOnClickListener(v -> {
            try {
                host.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                Toast.makeText(host, "打不开外部浏览器：" + e, Toast.LENGTH_SHORT).show();
            }
        });
        box.addView(outApp, Ui.lp(host, 8));
        Ui.dialog(host, url.replaceFirst("^https?://", "").replaceAll("/.*$", ""),
                Ui.scroller(host, box), "取消", null, null, null);
    }
}
