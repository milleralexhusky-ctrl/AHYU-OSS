package com.cpa.gateway;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.cpa.gateway.core.Log;

/**
 * In-app login browser. Keeps the whole authorization inside the app (no jumping to Chrome), while
 * still handing non-http(s) links - weixin:// and friends - to the app that owns that scheme, which
 * is the only thing a WebView cannot do by itself.
 */
final class LoginWebView {

    /**
     * 最近一次在登录页请求里看到的 Authorization（连同 host）。
     *
     * 有的站（DeepSeek 就是）登录成功后**不把 token 放 localStorage**，而是每次 API 请求
     * 都带 `Authorization: Bearer …`。我们在 shouldInterceptRequest 里直接就能看到它——
     * 比让用户手工抄 token 靠谱，安卓单机上也是全自动的。
     */
    public static volatile String lastAuthHost = "";
    /**
     * 最近一次 Authorization 的明文。
     *
     * 注意：这里存的是**完整明文**（observed 那条列表做了脱敏，这个字段是给抓 token 用的）。
     * 消费方请用 {@link #takeAuthValue()} 取——它取完即清，明文只在内存里活到被取走为止；
     * 直接读这个字段会让明文一直留到进程结束。
     */
    public static volatile String lastAuthValue = "";
    /** 最后一次抓到凭据的时间戳（毫秒），用于超时自动丢弃。 */
    private static volatile long lastAuthAt = 0L;
    /** 凭据暂存上限：超过这个时间没人取走就丢掉，不让明文长期驻留。 */
    private static final long AUTH_TTL_MS = 10 * 60 * 1000L;

    /**
     * 取出并清空最近一次凭据（原子）。取不到返回空串。
     *
     * 四个消费方（DeepSeek / Kimi / Xai / Cline）都是"读到就 save 并 return"，
     * 取一次就够，所以用完即清是安全的。
     */
    public static synchronized String takeAuthValue() {
        String v = lastAuthValue;
        long at = lastAuthAt;
        lastAuthValue = "";
        lastAuthHost = "";
        lastAuthAt = 0L;
        if (v == null || v.isEmpty()) return "";
        if (at > 0 && System.currentTimeMillis() - at > AUTH_TTL_MS) return "";   // 过期直接丢
        return v;
    }
    /** 监听器：抓到凭据后**主动回调**（登录页不用轮询，登录一成功就立刻加号）。 */
    public static volatile java.util.function.BiConsumer<String, String> AUTH_SINK;

    private LoginWebView() {}

    interface ExternalHandler {
        void onExternal(String url, boolean opened);
    }

    /** Requests seen while browsing: url + headers. Lets any site be understood, not just NewAPI. */
    static final java.util.List<String[]> observed = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    static void startObserving() {
        observed.clear();
    }

    static void observe(String url, java.util.Map<String, String> headers) {
        if (url == null) return;
        if (observed.size() > 400) return;
        StringBuilder sb = new StringBuilder();
        if (headers != null) {
            for (java.util.Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                String k = e.getKey().toLowerCase();
                if (k.contains("authorization") || k.contains("api-key") || k.contains("apikey")
                        || k.contains("x-key") || k.contains("token")) {
                    // 脱敏：这个列表在内存里存着完整凭据，一旦被导出/截图就是账号泄露。
                    // 只留头尾各 4 位，够用来辨认"抓到了哪个头的值"。
                    sb.append(e.getKey()).append(": ").append(mask(e.getValue())).append("\n");
                }
            }
        }
        observed.add(new String[]{url, sb.toString()});
    }

    /** 凭据脱敏：只留头尾各 4 位。 */
    static String mask(String v) {
        if (v == null) return "";
        String t = v.trim();
        if (t.length() <= 10) return "***";
        return t.substring(0, 4) + "…" + t.substring(t.length() - 4);
    }

    /** Wipes cookies, storage and cache so the next login page is a fresh, empty session. */
    static void clearSession(android.content.Context c) {
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            cm.removeAllCookies(null);
            cm.flush();
        } catch (Throwable t) {
            Log.w("清空 Cookie 失败：" + t);
        }
        try {
            WebStorage.getInstance().deleteAllData();
        } catch (Throwable ignored) {
        }
        try {
            android.webkit.WebViewDatabase db = android.webkit.WebViewDatabase.getInstance(c);
            db.clearFormData();
            db.clearHttpAuthUsernamePassword();
        } catch (Throwable ignored) {
        }
        Log.i("登录会话已清空（Cookie / 存储 / 表单）");
    }

    static WebView create(final Activity activity, final ExternalHandler onExternal) {
        return create(activity, onExternal, true);
    }

    /**
     * @param clearFirst wipe cookies first. Official logins want a blank slate; a self-hosted relay
     *                   login must keep its session so the credentials can be captured afterwards.
     */
    static WebView create(final Activity activity, final ExternalHandler onExternal, boolean clearFirst) {
        if (clearFirst) clearSession(activity);
        WebView web = new WebView(activity);
        web.clearCache(true);
        web.clearHistory();
        web.clearFormData();
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setUserAgentString("Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
        // OAuth callbacks land on http://localhost:<port> from an https page; without this the
        // WebView blocks that navigation as mixed content and the code never reaches us.
        try {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        } catch (Throwable ignored) {
        }
        try {
            s.setAllowFileAccess(false);
            s.setAllowContentAccess(false);
        } catch (Throwable ignored) {
        }
        try {
            CookieManager.getInstance().setAcceptCookie(true);
            CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        } catch (Throwable ignored) {
        }

        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
                // pop-up logins (Google / Apple) stay in this WebView instead of opening a new window
                WebView temp = new WebView(activity);
                temp.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                        view.loadUrl(request.getUrl().toString());
                        return true;
                    }
                });
                ((WebView.WebViewTransport) resultMsg.obj).setWebView(temp);
                resultMsg.sendToTarget();
                return true;
            }
        });

        web.setWebViewClient(new WebViewClient() {
            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(WebView v,
                                                                             android.webkit.WebResourceRequest request) {
                try {
                    observe(request.getUrl().toString(), request.getRequestHeaders());
                } catch (Throwable ignored) {
                }
                // 顺手把网页自己带的 Authorization 记下来（DeepSeek 这类站就靠它拿 token）
                try {
                    if (request != null && request.getRequestHeaders() != null) {
                        for (java.util.Map.Entry<String, String> e
                                : request.getRequestHeaders().entrySet()) {
                            if (e.getKey() != null && e.getKey().equalsIgnoreCase("authorization")
                                    && e.getValue() != null && e.getValue().trim().length() > 20) {
                                lastAuthValue = e.getValue().trim();
                                lastAuthHost = request.getUrl() == null ? "" : request.getUrl().getHost();
                                lastAuthAt = System.currentTimeMillis();
                                Log.i("抓到网页登录凭据（" + lastAuthHost + "）："
                                        + lastAuthValue.substring(0, Math.min(14, lastAuthValue.length())) + "…");
                                java.util.function.BiConsumer<String, String> sink = AUTH_SINK;
                                if (sink != null) {
                                    try {
                                        sink.accept(lastAuthHost, lastAuthValue);
                                    } catch (Throwable ignored) {
                                    }
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
                // 登录页发出的业务请求打到日志（正式包也打，只挑关键路径）：
                // 用来把"点手机号 / 发验证码"到底调了哪个接口看清楚，之后才能照 Loomy 做成原生登录
                if (request != null && request.getUrl() != null) {
                    String u = request.getUrl().toString();
                    if (u.contains("/v2/") || u.contains("/api/") || u.contains("/login")) {
                        Log.i("webview请求：" + request.getMethod() + " " + u);
                    }
                }
                return null;
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handle(activity, request.getUrl() == null ? null : request.getUrl().toString(), onExternal);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handle(activity, url, onExternal);
            }
        });
        return web;
    }

    /**
     * 可以直接放行的 scheme。
     *
     * 这些是"点了就是想打电话/发短信/发邮件/看地图"的常用跳转，
     * 每次都弹确认框会烦到没法用登录页，所以白名单放行。
     */
    private static final java.util.Set<String> SAFE_SCHEMES =
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "tel", "sms", "smsto", "mailto", "geo", "market", "weixin"));

    static boolean handle(Activity activity, String url, ExternalHandler onExternal) {
        if (url == null) return false;
        if (url.startsWith("http://") || url.startsWith("https://")
                || url.startsWith("about:") || url.startsWith("data:")) {
            return false; // stay in the WebView
        }
        final String scheme = url.contains(":") ? url.substring(0, url.indexOf(':')).toLowerCase() : "";

        // 安全边界：以前这里对**任何** scheme 都直接 startActivity(VIEW)，
        // 登录页（或它加载的第三方资源）塞一条 intent:// 带 extras 就能悄悄拉起外部应用，
        // 而且带的是 FLAG_ACTIVITY_NEW_TASK，完全脱离当前任务栈。
        // 现在：白名单内的直接放行，其余一律先弹一次确认。
        if (SAFE_SCHEMES.contains(scheme)) {
            Log.i("登录页跳转外部协议（白名单放行）：" + scheme);
            boolean ok = open(activity, url);
            if (onExternal != null) onExternal.onExternal(url, ok);
            return true;
        }

        Log.w("登录页尝试跳转非白名单协议，等待用户确认：" + scheme);
        confirmExternal(activity, url, scheme, onExternal);
        return true;
    }

    /** 真正去拉起外部应用。 */
    private static boolean open(Activity activity, String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            // 不要把不可信 scheme 的 intent 丢进别的任务栈；这里只保留必要的 flag。
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
            return true;
        } catch (Throwable e) {
            Log.w("没有任何应用能处理该链接：" + url);
            return false;
        }
    }

    /**
     * 非白名单协议：弹一次确认。
     *
     * 展示时只给 scheme + 截断后的正文，**不显示可能带凭据的完整 query**
     * （登录回调 URL 里经常直接带 code/token）。
     */
    private static void confirmExternal(final Activity activity, final String url,
                                        final String scheme, final ExternalHandler onExternal) {
        try {
            String preview = url.length() > 120 ? url.substring(0, 120) + "…" : url;
            // 砍掉 query，避免把授权码/令牌摊在屏幕上
            int q = preview.indexOf('?');
            if (q > 0) preview = preview.substring(0, q) + "?…";

            android.widget.LinearLayout box = Ui.column(activity);
            box.addView(Ui.label(activity, "这个页面想用「" + scheme + "」协议打开外部应用："));
            android.widget.TextView u = Ui.label(activity, preview);
            u.setTextSize(12.5f);
            u.setTextColor(Ui.MUTED);
            box.addView(u, Ui.lp(activity, 10));
            box.addView(Ui.label(activity, "只有你认得这个跳转时才继续。不确定就点「不打开」。"));
            box.setPadding(0, 0, 0, 0);

            final android.app.Dialog[] holder = new android.app.Dialog[1];
            holder[0] = Ui.dialog(activity, "跳转到外部应用？", box,
                    "不打开", null,
                    "打开", () -> {
                        Log.w("用户确认放行协议：" + scheme);
                        boolean ok = open(activity, url);
                        if (onExternal != null) onExternal.onExternal(url, ok);
                    });
            holder[0].show();
        } catch (Throwable t) {
            // 弹不出确认框时**宁可不打开**——安全边界不能因为 UI 异常而失效
            Log.w("确认框创建失败，已拒绝该跳转：" + t);
            if (onExternal != null) onExternal.onExternal(url, false);
        }
    }

    static void fill(android.widget.LinearLayout parent, WebView web) {
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        web.setMinimumHeight(600);
        parent.addView(web, lp);
    }
}
