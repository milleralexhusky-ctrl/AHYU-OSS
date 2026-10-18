package com.cpa.gateway;

import com.cpa.gateway.core.Threads;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.cpa.gateway.core.Config;
import com.cpa.gateway.core.Gateway;
import com.cpa.gateway.core.Log;
import com.cpa.gateway.core.Proto;
import com.cpa.gateway.core.Upstreams;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Relay-station login: type a URL, sign in inside the in-app browser, then the session (cookies and
 * any token found in local storage) is captured and kept as a channel.
 */
public class WebLoginActivity extends Activity {

    private final Handler autoOpen = new Handler(Looper.getMainLooper());
    private volatile boolean autoSaveStarted = false;
    private volatile boolean saved = false;
    private LinearLayout chipsRow;
    private TextView addTokenButton;
    private EditText urlField;
    private TextView status;
    private WebView web;
    private String currentUrl = "";
    private String capturedStorage = "";
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.darkSystemBars(this);
        Gateway.init(this);
        // start clean, then keep whatever session the user creates on this site
        LoginWebView.clearSession(this);
        LoginWebView.startObserving();

        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Color.TRANSPARENT);
        root.setPadding(Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 12));

        TextView back = Ui.barButton(this, "‹ 返回");
        back.setOnClickListener(v -> finish());
        LinearLayout header = Ui.appBar(this, back, "自定义中转站", "网页登录 · 自动保存会话");
        addTokenButton = Ui.barButton(this, "＋");
        Ui.a11y(addTokenButton, "添加令牌");
        addTokenButton.setTextSize(18f);
        addTokenButton.setVisibility(android.view.View.INVISIBLE);
        addTokenButton.setOnClickListener(v -> showAddTokenDialog());
        header.addView(addTokenButton);
        root.addView(header);

        // 1) 网址输入在最上面：键盘弹起时也看得见
        LinearLayout card = Ui.column(this);
        urlField = Ui.field(this, "粘贴中转站网址，或从下面挑一个", "", false);
        urlField.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
        urlField.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_GO);
        urlField.setOnEditorActionListener((v, actionId, event) -> {
            loadSite();
            return true;
        });
        urlField.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s2, int a2, int b2, int c2) {
            }

            @Override
            public void onTextChanged(CharSequence s2, int a2, int b2, int c2) {
            }

            @Override
            public void afterTextChanged(android.text.Editable e) {
                String text = e.toString().trim();
                autoOpen.removeCallbacksAndMessages(null);
                if (text.length() < 6 || !text.contains(".")) return;
                autoOpen.postDelayed(() -> {
                    String normalized = normalize(text);
                    if (!normalized.equals(currentUrl)) openStation(normalized);
                }, 900);
            }
        });
        card.addView(urlField);
        status = Ui.label(this, "登录成功会自动保存并回到主页");
        card.addView(status, Ui.lp(this, 6));
        root.addView(Ui.card(this, card), Ui.lp(this, 8));

        // 2) 网页占中间的大块空间
        LinearLayout webCard = Ui.column(this);
        webCard.setBackground(Ui.drawablePanel(this));
        webCard.setClipToOutline(true);
        web = LoginWebView.create(this, (url, opened) -> {
            if (opened) status.setText("已交给外部 App 打开，回到本页继续。");
        }, false);
        LoginWebView.fill(webCard, web);
        // 网页区已用 weight 占满剩余空间；设死高度会顶出屏幕，而本页不可滚动
        root.addView(webCard, Ui.fill(this, 8));

        // 3) 常用中转站沉到最下面，高度压到刚好一行
        LinearLayout quick = Ui.column(this);
        quick.addView(Ui.label(this, "常用中转站（点一下直接打开）"));
        android.widget.HorizontalScrollView scroller = new android.widget.HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setClipToPadding(false);
        scroller.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 2));
        chipsRow = Ui.row(this);
        chipsRow.setPadding(0, 0, 0, 0);
        scroller.addView(chipsRow);
        quick.addView(scroller, Ui.lp(this, 6));
        root.addView(Ui.card(this, quick), Ui.lp(this, 8));

        setContentView(Ui.glassScaffold(this, root));
        // 同上：按用户要求放开截屏（想禁回去就把下面这行取消注释）。
        // Ui.secure(this);
        renderChips();
        com.cpa.gateway.core.Stations.refreshIfStaleAsync(() -> handler.post(this::renderChips));
    }

    /**
     * Only reachable stations are listed: a dead one simply disappears instead of sitting there
     * greyed out. If a station comes back on a later check it reappears by itself.
     */
    private void renderChips() {
        if (chipsRow == null) return;
        chipsRow.removeAllViews();
        int shown = 0;
        int hidden = 0;
        for (final String[] station : com.cpa.gateway.core.Stations.offerableStations()) {
            TextView chip = Ui.accentChipButton(this, station[0]);
            chip.setOnClickListener(v -> openStation(station[1]));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.rightMargin = Ui.dp(this, 8);
            chipsRow.addView(chip, clp);
            shown++;
        }
        if (shown == 0) {
            chipsRow.addView(Ui.label(this, "可用的常用站点都已经添加过了，直接粘贴新网址即可"));
        } else if (hidden > 0) {
            Log.i("常用中转站：显示 " + shown + " 个，已自动隐藏 " + hidden + " 个失效站点");
        }
    }

    /** Turns whatever was pasted into a usable https URL. */
    static String normalize(String raw) {
        String s2 = raw == null ? "" : raw.trim().replace("\n", "").replace("\r", "").replace(" ", "");
        while (s2.startsWith("https://https://")) s2 = s2.substring("https://".length());
        while (s2.startsWith("http://http://")) s2 = s2.substring("http://".length());
        if (!s2.startsWith("http")) s2 = "https://" + s2;
        return s2;
    }

    /** Chip tap: fill the field and open the page right away, no second button needed. */
    private void openStation(String url) {
        urlField.setText(url);
        currentUrl = url;
        if (addTokenButton != null) addTokenButton.setVisibility(android.view.View.VISIBLE);
        status.setText("正在打开 " + url + " …");
        warnIfCleartext(url);
        resetAutoSave();      // 跟 loadSite() 一样：上一个站点的 saved/autoSave 必须清掉，
                              // 否则新站点的登录轮询第一行就 return，登了也白登
        web.loadUrl(url);
        startAutoSave();      // chips and auto-open must watch for the login as well
        visitPlaygroundSoon();
    }

    /** Manual token entry, shared with the plain browser activity. */
    private void showAddTokenDialog() {
        if (currentUrl == null || currentUrl.isEmpty()) {
            toast("先打开一个网站");
            return;
        }
        TokenAdd.show(this, currentUrl);
    }

    /** Channels already pointing at the same host. */
    static java.util.List<Config.Provider> channelsOfHost(String host) {
        java.util.List<Config.Provider> out = new java.util.ArrayList<>();
        if (host == null || host.isEmpty()) return out;
        for (Config.Provider p : Gateway.config().providers) {
            String h = com.cpa.gateway.MainActivity.hostOf(p.baseUrl);
            if (host.equalsIgnoreCase(h)) out.add(p);
        }
        return out;
    }

    /** Name of the channel already using this key, or null. */
    static String duplicateOf(String key) {
        String normalized = key == null ? "" : key.trim();
        if (normalized.isEmpty()) return null;
        for (Config.Provider p : Gateway.config().providers) {
            if (p.apiKey != null && !p.apiKey.isEmpty() && p.apiKey.equals(normalized)) return p.name;
            if (p.credentials != null && p.credentials.contains("\"" + normalized + "\"")) return p.name;
        }
        return null;
    }

    /** Host of the page currently open in the browser. */
    private String currentHost() {
        if (currentUrl == null || currentUrl.isEmpty()) return "";
        String h = currentUrl.replaceFirst("^https?://", "");
        int slash = h.indexOf('/');
        if (slash >= 0) h = h.substring(0, slash);
        return h.toLowerCase();
    }

    /** One key = one channel; the model list is fetched in the background. */
    private String saveTokenChannel(final String key, final String label) {
        final String[] created = new String[]{""};
        Threads.io(() -> {
            try {
                String origin = currentUrl.replaceFirst("^https?://", "https://");
                int slash = origin.indexOf('/', origin.indexOf("://") + 3);
                if (slash > 0) origin = origin.substring(0, slash);
                String host = origin.replaceFirst("^https?://", "");
                String base = currentUrl.contains("/v1") ? currentUrl.substring(0, currentUrl.indexOf("/v1") + 3) : origin;
                Config.Provider p = new Config.Provider();
                p.type = Config.TYPE_OPENAI;
                p.id = "tok" + Math.abs(System.nanoTime() % 1000000);
                String site = siteName(origin, CookieManager.getInstance().getCookie(currentUrl));
                String prefix = site.isEmpty() ? host : site;
                p.name = prefix + (label.isEmpty() ? "" : " · " + label);
                if (nameTaken(Gateway.config().providers, p.name)) {
                    p.name = p.name + " " + Math.abs(System.nanoTime() % 100);
                }
                p.baseUrl = base;
                p.apiKey = key.startsWith("sk-") ? key : "sk-" + key;
                org.json.JSONObject c = new org.json.JSONObject();
                c.put("token", p.apiKey);
                c.put("panel", "manual");
                if (!site.isEmpty()) c.put("site_name", site);
                c.put("site_host", host);
                p.credentials = c.toString();
                p.enabled = true;
                Gateway.config().providers.add(p);
                int models = 0;
                try {
                    java.util.List<String> list = Upstreams.fetchModels(p);
                    p.setModels(list);
                    models = p.models.size();
                } catch (Exception e) {
                    Log.w("令牌通道拉模型失败：" + e);
                }
                Gateway.persist();
                final int count = models;
                final String who = p.name;
                handler.post(() -> Toast.makeText(this,
                        count > 0 ? ("已添加 " + who + "（" + count + " 个模型）")
                                : ("已添加 " + who + "，但没拉到模型（密钥或地址可能不对）"),
                        Toast.LENGTH_LONG).show());
                Log.i("手动令牌已保存：" + who + " 模型=" + count);
            } catch (Exception e) {
                Log.w("保存令牌失败：" + e);
                handler.post(() -> status.setText("保存令牌失败：" + e.getMessage()));
            }
        });
        // 名字在子线程里生成，这里同步算一份用于"查看"跳转（同规则：站点名优先）
        try {
            String origin = currentUrl.replaceFirst("^https?://", "https://");
            int slash = origin.indexOf('/', origin.indexOf("://") + 3);
            if (slash > 0) origin = origin.substring(0, slash);
            String host = origin.replaceFirst("^https?://", "");
            String site = siteName(origin, CookieManager.getInstance().getCookie(currentUrl));
            created[0] = site.isEmpty() ? host : site;
        } catch (Exception e) {
            created[0] = currentUrl;
        }
        return created[0];
    }

    private void resetAutoSave() {
        autoSaveStarted = false;
        saved = false;
        autoOpen.removeCallbacksAndMessages(null);
    }

    private void loadSite() {
        String typed = urlField.getText().toString().trim();
        if (typed.isEmpty() || "https://".equals(typed) || "http://".equals(typed)) {
            toast("先填一个网址");
            return;
        }
        String raw = normalize(typed);
        if (addTokenButton != null) addTokenButton.setVisibility(android.view.View.VISIBLE);
        currentUrl = raw;
        warnIfCleartext(raw);
        web.loadUrl(raw);
        resetAutoSave();
        status.setText("在这个页面里登录，登录成功会自动保存。");
        startAutoSave();
        visitPlaygroundSoon();
    }

    /**
     * 非回环的明文 http 现在会被系统拦掉（见 res/xml/network_security_config.xml：
     * 4.29.1 起默认禁止明文流量，只放行 127.0.0.1 / localhost）。
     *
     * 为什么需要这个提示：用户粘贴一个 http:// 的站点时，原始行为是"能登就登"，
     * 收紧之后会变成 WebView 里一句 net::ERR_CLEARTEXT_NOT_PERMITTED —— 对用户
     * 完全不可读，只会以为"这个站坏了"。这里提前用一句人话说清"是安全设置拦的、
     * 该怎么办"，把不可解的错误变成可操作的提示。
     *
     * 刻意**只提示、不拦截**，仍然继续加载：
     * 一来 WebView 是否遵守 networkSecurityConfig 在不同 ROM 上有差异（遵守是
     * Android 7+ 的官方行为，但没必要把可用性赌在这一点上）；
     * 二来万一哪天配置改回去了，这里也不会变成一道假的硬墙。
     * 也就是说：能加载就正常用，加载不了也已经先看到原因。
     */
    private void warnIfCleartext(String url) {
        if (url == null) return;
        String lower = url.toLowerCase(java.util.Locale.US);
        if (!lower.startsWith("http://")) return;
        String host = lower.substring("http://".length());
        int cut = host.indexOf('/');
        if (cut >= 0) host = host.substring(0, cut);
        int colon = host.indexOf(':');
        if (colon >= 0) host = host.substring(0, colon);
        if (host.equals("127.0.0.1") || host.equals("localhost") || host.equals("::1") || host.equals("[::1]")) {
            return;                       // 本机回环是白名单，照常
        }
        String msg = "注意：这是明文 http 站点，已被安全设置拦截（只放行 https 和本机地址）。"
                + "请改用 https:// 开头的地址。";
        toast(msg);
        if (status != null) status.setText(msg);
        Log.w("明文 http 站点被拦截：" + host + "（改用 https 或本机地址）");
    }

    /** Watches for a finished login and saves by itself; the user does not have to press anything. */
    private void startAutoSave() {
        if (autoSaveStarted) return;
        autoSaveStarted = true;
        saved = false;
        status.setText(status.getText() + "\n（已登录会自动保存；也可以点这行手动保存）");
        status.setOnClickListener(v -> {
            if (currentUrl != null && !currentUrl.isEmpty()) {
                web.evaluateJavascript(
                        "(function(){try{return JSON.stringify(window.localStorage||{});}catch(e){return '{}';}})();",
                        value -> {
                            capturedStorage = value == null ? "" : value;
                            status.setText("正在手动保存…");
                            writeProvider(currentUrl, true);
                        });
            }
        });
        final int[] ticks = {0};
        Runnable poll = new Runnable() {
            @Override
            public void run() {
                if (saved || isFinishing()) return;
                ticks[0]++;
                String live = web.getUrl();
                if (live != null && !live.isEmpty() && !"about:blank".equals(live)) currentUrl = live;
                web.evaluateJavascript(
                        "(function(){try{return JSON.stringify(window.localStorage||{});}catch(e){return '{}';}})();",
                        value -> {
                            capturedStorage = value == null ? "" : value;
                            String token = tokenFromStorage(capturedStorage);
                            String cookies = CookieManager.getInstance().getCookie(currentUrl);
                            int cookieLen = cookies == null ? 0 : cookies.length();
                            Log.i("中转站自动保存检查 #" + ticks[0] + "：地址=" + currentUrl
                                    + " cookie长度=" + cookieLen + " token=" + (token.isEmpty() ? "无" : "有"));
                            boolean session = cookieLen > 0;
                            if (session && (!token.isEmpty() || ticks[0] >= 6)) {
                                status.setText("检测到已登录，正在自动保存…");
                                writeProvider(currentUrl, false);
                            } else if (ticks[0] < 60) {
                                autoOpen.postDelayed(this, 3000);
                            } else {
                                status.setText("等待登录超时。可点这行手动保存，或返回重试。");
                            }
                        });
            }
        };
        autoOpen.postDelayed(poll, 3000);
    }

    private void saveStation() {
        if (web.getUrl() != null) currentUrl = web.getUrl();
        if (currentUrl == null || currentUrl.isEmpty()) {
            toast("先打开一个网址");
            return;
        }
        status.setText("正在读取会话…");
        final String pageUrl = currentUrl;
        web.evaluateJavascript(
                "(function(){try{return JSON.stringify(window.localStorage||{});}catch(e){return '{}';}})();",
                value -> {
                    capturedStorage = value == null ? "" : value;
                    writeProvider(pageUrl, true);
                });
    }

    private void writeProvider(final String pageUrl, final boolean fromButton) {
        // the evaluateJavascript callback runs on the UI thread: all network work must move off it
        Threads.io(() -> writeProviderWork(pageUrl, fromButton));
    }

    private void writeProviderWork(String pageUrl, boolean fromButton) {
        try {
            String cookies = CookieManager.getInstance().getCookie(pageUrl);
            JSONObject creds = new JSONObject();
            creds.put("cookies", cookies == null ? "" : cookies);
            creds.put("login_url", pageUrl);
            String host = Uri.parse(pageUrl).getHost();
            String origin = Uri.parse(pageUrl).getScheme() + "://" + host;
            String base = pageUrl.contains("/v1") ? pageUrl.substring(0, pageUrl.indexOf("/v1") + 3) : origin;
            String localToken = tokenFromStorage(capturedStorage);

            // every token becomes its own channel, so per-group model sets are preserved
            java.util.List<PanelToken> tokens = new java.util.ArrayList<>();
            try {
                tokens = fetchPanelTokens(base, cookies);
            } catch (Exception e) {
                Log.w("面板取令牌失败：" + e);
            }
            java.util.List<PanelToken> usable = new java.util.ArrayList<>();
            for (PanelToken t : tokens) {
                if (t.status == 1) usable.add(t);
            }
            if (usable.isEmpty()) usable.addAll(tokens);
            if (usable.isEmpty()) {
                // generic path: whatever token the site itself used, on whatever API base it used
                java.util.List<String> trafficTokens = skTokensFromTraffic();
                java.util.List<String> trafficBases = basesFromTraffic();
                if (trafficTokens.isEmpty()) {
                    Log.w("流量里只有面板凭证（JWT），没有 sk- 密钥：这个面板不暴露明文密钥");
                }
                for (String token : trafficTokens) {
                    for (String b : trafficBases.isEmpty()
                            ? java.util.Collections.singletonList(base) : trafficBases) {
                        PanelToken t = new PanelToken();
                        t.key = token;
                        t.name = "流量捕获";
                        t.group = b.replaceFirst("^https?://", "").replaceAll("/.*$", "");
                        usable.add(t);
                        if (usable.size() >= 4) break;
                    }
                    if (usable.size() >= 4) break;
                }
                if (!usable.isEmpty()) Log.i("从网页流量推断出 " + usable.size() + " 个可用令牌");
            }
            if (usable.isEmpty()) {
                String scraped = "";
                try {
                    scraped = scrapeKey("", cookies, origin);
                } catch (Exception e) {
                    Log.w("页面抓密钥失败：" + e);
                }
                if (!scraped.isEmpty()) {
                    PanelToken t = new PanelToken();
                    t.key = scraped;
                    t.name = "网页密钥";
                    usable.add(t);
                } else if (looksLikeApiKey(localToken)) {
                    PanelToken t = new PanelToken();
                    t.key = localToken;
                    t.name = "localStorage";
                    usable.add(t);
                }
            }

            int created = 0;
            int models = 0;
            String lastName = "";
            java.util.List<Config.Provider> existing = Gateway.config().providers;
            for (PanelToken t : usable) {
                if (created >= 8) break;    // keep the list manageable
                Config.Provider p = new Config.Provider();
                p.type = Config.TYPE_OPENAI;
                p.id = "web" + Math.abs(System.nanoTime() % 1000000);
                String label = (t.group == null || t.group.isEmpty()) ? "默认分组" : t.group;
                String site = siteName(origin, cookies);
                String prefix = site.isEmpty() ? (host == null || host.isEmpty() ? "中转站" : host) : site;
                p.name = prefix + " · " + (t.name.isEmpty() ? label : t.name + "（" + label + "）");
                if (nameTaken(existing, p.name)) continue;
                p.baseUrl = base;
                p.apiKey = t.key;
                JSONObject c2 = new JSONObject(creds.toString());
                c2.put("token", t.key);
                c2.put("panel", "newapi");
                c2.put("panel_group", label);
                if (!site.isEmpty()) c2.put("site_name", site);
                c2.put("site_host", host == null ? "" : host);
                p.credentials = c2.toString();
                p.enabled = true;
                existing.add(p);
                created++;
                lastName = p.name;
                try {
                    java.util.List<String> list = Upstreams.fetchModels(p);
                    p.setModels(list);
                    models += p.models.size();
                    Log.i("通道 " + p.name + "：拉到 " + list.size() + " 个模型");
                } catch (Exception e) {
                    Log.w("通道 " + p.name + " 拉模型失败：" + e);
                }
            }
            Gateway.persist();
            final int createdCount = created;
            final int modelCount = models;
            final String first = lastName;
            saved = true;
            Log.i("中转站保存完成：新建 " + createdCount + " 条通道，合计 " + modelCount + " 个模型");
            handler.post(() -> {
                if (createdCount == 0) {
                    status.setText("这个面板不提供明文密钥（列表里是打码的）。\n"
                            + "请到控制台「令牌」页新建一个令牌，复制 sk- 开头的密钥，"
                            + "回到账号详情粘贴即可。");
                    Toast.makeText(this, "请在控制台复制 sk- 密钥后手动粘贴", Toast.LENGTH_LONG).show();
                    web.loadUrl(currentUrl.replaceFirst("^https?://", "https://")
                            .replaceAll("/(sign-in|dashboard|playground).*$", "/console/token"));
                    return;
                }
                Toast.makeText(this, "已保存 " + createdCount + " 条通道（共 " + modelCount + " 个模型）",
                        Toast.LENGTH_LONG).show();
                Intent home = new Intent(this, MainActivity.class);
                home.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(home);
                finish();
            });
        } catch (Exception e) {
            Log.w("保存中转站失败：" + e);
            handler.post(() -> status.setText("保存失败：" + e.getMessage()));
        }
    }

    static boolean nameTaken(java.util.List<Config.Provider> list, String name) {
        for (Config.Provider p : list) {
            if (p.name != null && p.name.equals(name)) return true;
        }
        return false;
    }

    static JSONObject parseSelf(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(raw);
            if (!o.optBoolean("success", true) && o.optJSONObject("data") == null) return null;
            return o.optJSONObject("data");
        } catch (Exception e) {
            return null;
        }
    }

    /** The panel keeps its JWT under one of these names. */
    static String jwtFromStorage(String storageJson) {
        String t = tokenFromStorage(storageJson);
        return t != null && t.length() > 40 ? t : "";
    }

    /** Any Bearer value seen in the site's own traffic. */
    static String bearerFromTraffic() {
        for (String t : tokensFromTraffic()) {
            if (t.length() > 40) return t;
        }
        return "";
    }

    /**
     * Panels mask the token key in their API list, so the only place the real sk- key shows up is the
     * site's own playground, which calls the API with it. Open that page automatically so the traffic
     * observer can pick the key up.
     */
    private void visitPlaygroundSoon() {
        // 原来 for 循环里第一次就 return，只试了第一个路径；
        // 现在逐个试（每个隔 12 秒），密钥抓到（saved）就停
        final String[] paths = {"/console/chat", "/chat", "/console/playground"};
        final int[] idx = {0};
        Runnable tryNext = new Runnable() {
            @Override
            public void run() {
                if (saved || isFinishing()) return;
                if (idx[0] >= paths.length) return;
                String origin = currentUrl == null ? "" : currentUrl.replaceFirst("^https?://", "https://");
                int slash = origin.indexOf('/', origin.indexOf("://") + 3);
                if (slash > 0) origin = origin.substring(0, slash);
                if (origin.isEmpty()) return;
                Log.i("尝试打开面板聊天页以捕获密钥：" + origin + paths[idx[0]]);
                web.loadUrl(origin + paths[idx[0]]);
                idx[0]++;
                autoOpen.postDelayed(this, 12000);
            }
        };
        autoOpen.postDelayed(tryNext, 8000);
    }

    /**
     * Pulls EVERY token of the account, not just the first: a panel often has several tokens bound to
     * different groups, and each group exposes its own model list.
     */
    /** One token from a NewAPI/OneAPI panel. Tokens carry the group that decides usable models. */
    static final class PanelToken {
        String name = "";
        String key = "";
        String group = "";
        int status = 0;
    }

    java.util.List<PanelToken> fetchPanelTokens(String base, String cookies) throws Exception {
        java.util.List<PanelToken> out = new java.util.ArrayList<>();
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/json");
        headers.put("User-Agent", "Mozilla/5.0");
        if (cookies != null && !cookies.isEmpty()) headers.put("Cookie", cookies);
        String selfRaw = Upstreams.getWithHeaders(base + "/api/user/self", headers);
        JSONObject selfData = parseSelf(selfRaw);
        if (selfData == null) {
            // newer NewAPI forks ignore cookies and want the JWT from the login response
            String jwt = jwtFromStorage(capturedStorage);
            if (jwt.isEmpty()) jwt = bearerFromTraffic();
            if (!jwt.isEmpty()) {
                headers.put("Authorization", "Bearer " + jwt);
                Log.i("面板改用 Bearer JWT 读取");
                selfRaw = Upstreams.getWithHeaders(base + "/api/user/self", headers);
                selfData = parseSelf(selfRaw);
            }
        }
        if (selfData == null) return out;
        String uid = String.valueOf(selfData.optInt("id", 0));
        if ("0".equals(uid)) return out;
        headers.put("New-Api-User", uid);
        JSONObject list = new JSONObject(Upstreams.getWithHeaders(base + "/api/token/?p=1&size=100", headers));
        JSONObject listData = list.optJSONObject("data");
        org.json.JSONArray items = listData == null ? null : listData.optJSONArray("items");
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String key = Proto.optString(item, "key", "");
            if (key.isEmpty()) continue;
            PanelToken t = new PanelToken();
            t.key = key.startsWith("sk-") ? key : "sk-" + key;
            t.name = Proto.optString(item, "name", "");
            t.group = Proto.optString(item, "group", "");
            t.status = item.optInt("status", 0);
            out.add(t);
        }
        Log.i("面板读取到 " + out.size() + " 个令牌（含分组信息）");
        return out;
    }

    /**
     * NewAPI / OneAPI relay panels: the session cookie is not the API credential. Their own API
     * exposes the account and the generated tokens, and the client authenticates with the sk- key.
     * Both endpoints require the New-Api-User header carrying the numeric user id.
     */
    static String fetchPanelKey(String base, String cookies) throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/json");
        headers.put("User-Agent", "Mozilla/5.0");
        if (cookies != null && !cookies.isEmpty()) headers.put("Cookie", cookies);
        String selfRaw = Upstreams.getWithHeaders(base + "/api/user/self", headers);
        if (selfRaw == null || selfRaw.trim().isEmpty()) return "";
        JSONObject self = new JSONObject(selfRaw);
        JSONObject selfData = self.optJSONObject("data");
        if (selfData == null) return "";
        String uid = String.valueOf(selfData.optInt("id", 0));
        if ("0".equals(uid)) return "";
        headers.put("New-Api-User", uid);
        String listRaw = Upstreams.getWithHeaders(base + "/api/token/?p=1&size=100", headers);
        if (listRaw == null || listRaw.trim().isEmpty()) return "";
        JSONObject list = new JSONObject(listRaw);
        JSONObject listData = list.optJSONObject("data");
        org.json.JSONArray items = listData == null ? null : listData.optJSONArray("items");
        if (items == null) return "";
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String key = Proto.optString(item, "key", "");
            if (!key.isEmpty()) {
                Log.i("面板 token：" + Proto.optString(item, "name", "(无名)") + " status=" + item.optInt("status"));
                return key.startsWith("sk-") ? key : "sk-" + key;
            }
        }
        return "";
    }

    /** Tokens seen in the site's own requests (Authorization / api-key headers or ?key=). */
    /** API keys are sk- prefixed; a JWT is only good for reading the panel, never for calling it. */
    static boolean looksLikeApiKey(String t) {
        return t != null && t.startsWith("sk-");
    }

    static java.util.List<String> skTokensFromTraffic() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String t : tokensFromTraffic()) {
            if (looksLikeApiKey(t) && !out.contains(t)) out.add(t);
        }
        return out;
    }

    static java.util.List<String> tokensFromTraffic() {
        java.util.List<String> out = new java.util.ArrayList<>();
        synchronized (LoginWebView.observed) {
            for (String[] rec : LoginWebView.observed) {
                String headers = rec[1] == null ? "" : rec[1];
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("(?i)(?:authorization|api-?key|x-key|token)\\s*:\\s*(?:Bearer\\s*)?([A-Za-z0-9._\\-]{12,})")
                        .matcher(headers);
                while (m.find()) {
                    String t = m.group(1);
                    if (!out.contains(t)) out.add(t);
                }
                java.util.regex.Matcher q = java.util.regex.Pattern
                        .compile("[?&](?:key|api_key|token)=([A-Za-z0-9._\\-]{12,})").matcher(rec[0]);
                while (q.find()) {
                    String t = q.group(1);
                    if (!out.contains(t)) out.add(t);
                }
            }
        }
        Log.i("从网页流量里解析到 " + out.size() + " 个候选令牌");
        return out;
    }

    /** API bases seen in traffic: any /v1 style endpoint tells us where the API lives. */
    static java.util.List<String> basesFromTraffic() {
        java.util.List<String> out = new java.util.ArrayList<>();
        synchronized (LoginWebView.observed) {
            for (String[] rec : LoginWebView.observed) {
                String url = rec[0];
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("(https?://[^/]+/(?:v1|api/v1|openai/v1))").matcher(url);
                if (m.find()) {
                    String b = m.group(1);
                    if (!out.contains(b)) out.add(b);
                }
            }
        }
        return out;
    }

    /** The site's own name (NewAPI exposes system_name); used for naming channels. */
    static String siteName(String origin, String cookies) {
        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("Accept", "application/json");
            headers.put("User-Agent", "Mozilla/5.0");
            if (cookies != null && !cookies.isEmpty()) headers.put("Cookie", cookies);
            JSONObject o = new JSONObject(Upstreams.getWithHeaders(origin + "/api/status", headers));
            JSONObject data = o.optJSONObject("data");
            if (data != null) {
                String name = Proto.optString(data, "system_name", "");
                if (!name.isEmpty()) {
                    Log.i("站点名字：" + name);
                    return name;
                }
            }
        } catch (Exception e) {
            Log.w("读取站点名字失败：" + e);
        }
        return "";
    }

    /** Last resort: look for an sk- token on the console page itself. */
    static String scrapeKey(String ignored, String cookies, String origin) {
        for (String path : new String[]{"/console/token", "/token", "/console"}) {
            try {
                Map<String, String> headers = new HashMap<>();
                headers.put("Accept", "text/html");
                headers.put("User-Agent", "Mozilla/5.0");
                if (cookies != null && !cookies.isEmpty()) headers.put("Cookie", cookies);
                String html = Upstreams.getWithHeaders(origin + path, headers);
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("sk-[A-Za-z0-9_-]{16,}").matcher(html);
                if (m.find()) return m.group();
            } catch (Exception ignored2) {
            }
        }
        return "";
    }

    /** Common keys relay panels use to park their API token in local storage. */
    static String tokenFromStorage(String json) {
        if (json == null || json.isEmpty()) return "";
        try {
            String cleaned = json;
            if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length() > 1) {
                cleaned = cleaned.substring(1, cleaned.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
            }
            JSONObject o = new JSONObject(cleaned);
            String[] keys = {"token", "apiKey", "api_key", "key", "access_token", "accessToken", "Authorization"};
            for (String k : keys) {
                String v = o.optString(k, "");
                if (!v.isEmpty()) return v.startsWith("Bearer ") ? v.substring(7) : v;
            }
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                String lower = k.toLowerCase();
                if (lower.contains("token") || lower.contains("apikey") || lower.contains("api_key")) {
                    String v = o.optString(k, "");
                    if (!v.isEmpty()) return v.startsWith("Bearer ") ? v.substring(7) : v;
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
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

        // 页面没了就别再跑延时任务（否则 Handler 持有 Activity，还往已销毁的 View 写字）
        try {
            autoOpen.removeCallbacksAndMessages(null);
            handler.removeCallbacksAndMessages(null);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }
}
