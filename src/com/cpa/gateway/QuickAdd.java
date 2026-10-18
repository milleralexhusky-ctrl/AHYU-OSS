package com.cpa.gateway;

import com.cpa.gateway.core.Threads;
import com.cpa.gateway.core.Config;
import com.cpa.gateway.core.Log;
import com.cpa.gateway.core.Gateway;
import com.cpa.gateway.core.Upstreams;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一键添加：把用户粘进来的一坨文本，认成"地址 + 密钥"。
 *
 * 不要求格式。能认的有：
 *   · 两行分开：        http://host/v1  ⏎  sk-xxxx
 *   · 反着来：          sk-xxxx ⏎ http://host/v1
 *   · 一行连着：        sk-xxxx@http://host/v1  ·  http://host/v1|sk-xxxx  ·  逗号、空格、制表符都行
 *   · CC / 环境变量：   export ANTHROPIC_BASE_URL=...    ANTHROPIC_AUTH_TOKEN=...
 *                       OPENAI_API_KEY=...              OPENAI_BASE_URL=...
 *   · JSON：            {"base_url":"...","api_key":"...","model":"..."}
 *   · YAML / 冒号：     base_url: ...  api_key: ...  model: ...
 *   · 带在地址里：      https://host/v1?key=sk-xxxx
 *
 * 而且**必须真的能用才会添加**：先拿这个地址+密钥去拉一次模型列表，
 * 拉得到才建账号，拉不到就把原因原样报出来。
 */
public final class QuickAdd {

    private QuickAdd() {
    }

    /** 认出的一条账号。 */
    public static final class Entry {
        public String baseUrl = "";
        public String key = "";
        public String name = "";
        public String models = "";
        public String note = "";       // 不添加的原因，或识别说明
        public String type = "openai"; // openai 或 anthropic（CC 那套是 Anthropic 系）
        public boolean usable;

        public boolean ready() {
            return !baseUrl.isEmpty() && !key.isEmpty() && note.isEmpty();
        }

        public String display() {
            String host = baseUrl.replaceFirst("^https?://", "");
            int slash = host.indexOf('/');
            if (slash > 0) host = host.substring(0, slash);
            String masked = key.length() > 10
                    ? key.substring(0, 6) + "……" + key.substring(key.length() - 4) : "……";
            return host + "  ·  " + masked;
        }
    }

    /* ---------------------------------------------------------------- 正则 */

    private static final Pattern URL = Pattern.compile("https?://[^\\s\"'<>,，、|]+");

    private static final Pattern[] KEYS = {
            Pattern.compile("(sk-[A-Za-z0-9_-]{8,})"),
            Pattern.compile("(AIza[0-9A-Za-z_-]{20,})"),
            Pattern.compile("(xai-[A-Za-z0-9_-]{16,})"),
            // 带标签的：能兜住不带 sk- 前缀的中转密钥
            Pattern.compile("(?i)(?:api[_-]?key|apikey|auth[_-]?token|access[_-]?token|"
                    + "token|secret|密钥|密码)\\s*[:=]\\s*[\"']?([A-Za-z0-9_.-]{12,})"),
    };

    private static final Pattern LABELED_URL = Pattern.compile(
            "(?i)(?:[a-z_]*base[_-]?url|baseurl|api[_-]?base|endpoint|host|地址|url)"
                    + "\\s*[:=]\\s*[\"']?(https?://[^\\s\"',，]+)");

    private static final Pattern LABELED_MODEL = Pattern.compile(
            "(?i)(?:default[_-]?model|model|模型)\\s*[:=]\\s*[\"']?([A-Za-z0-9_.:/-]{2,60})");

    private static final Pattern LABELED_NAME = Pattern.compile(
            "(?i)(?:name|label|remark|备注|名称)\\s*[:=]\\s*[\"']?([^\\s\"',，]{1,30})");

    /* ---------------------------------------------------------------- 解析 */

    public static List<Entry> parse(String raw) {
        List<Entry> out = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return out;

        // 全角标点先归一，省得用户从微信里粘过来就认不出
        String text = raw
                .replace('：', ':').replace('｜', '|').replace('，', ',')
                .replace('＝', '=').replace('　', ' ');

        Entry labeled = new Entry();
        Matcher lu = LABELED_URL.matcher(text);
        if (lu.find()) labeled.baseUrl = normalizeUrl(lu.group(1));
        for (Pattern kp : KEYS) {
            Matcher km = kp.matcher(text);
            if (km.find()) {
                labeled.key = km.group(1).trim();
                break;
            }
        }
        Matcher lm = LABELED_MODEL.matcher(text);
        if (lm.find()) labeled.models = lm.group(1).trim();
        Matcher ln = LABELED_NAME.matcher(text);
        if (ln.find()) labeled.name = ln.group(1).trim();

        // 逐个账号：以"地址"为单位配对密钥
        List<String> urls = new ArrayList<>();
        Matcher um = URL.matcher(text);
        while (um.find()) {
            String u = normalizeUrl(um.group());   // URL 这条没有捕获组，用整个匹配
            if (!u.isEmpty() && !urls.contains(u)) urls.add(u);
        }
        if (labeled.baseUrl != null && !labeled.baseUrl.isEmpty() && !urls.contains(labeled.baseUrl)) {
            urls.add(0, labeled.baseUrl);
        }

        List<String> keys = new ArrayList<>();
        for (Pattern kp : KEYS) {
            Matcher km = kp.matcher(text);
            while (km.find()) {
                String k = km.group(1).trim();
                if (!keys.contains(k)) keys.add(k);
            }
        }
        if (labeled.key != null && !labeled.key.isEmpty() && !keys.contains(labeled.key)) {
            keys.add(0, labeled.key);
        }

        if (urls.isEmpty() && keys.isEmpty()) return out;

        // CC（Claude Code）那套是 Anthropic 协议，不能当 OpenAI 中转加
        boolean anthropic = text.toLowerCase(Locale.US).contains("anthropic")
                || text.toLowerCase(Locale.US).contains("claude");

        // 地址里带着 ?key= 的，先各自成一条
        for (String u : urls) {
            String carried = keyFromUrl(u);
            if (!carried.isEmpty()) {
                Entry e = new Entry();
                e.baseUrl = stripKeyFromUrl(u);
                e.key = carried;
                out.add(e);
            }
        }
        List<String> plainUrls = new ArrayList<>();
        for (String u : urls) if (keyFromUrl(u).isEmpty()) plainUrls.add(u);

        if (plainUrls.isEmpty() && !out.isEmpty()) {
            for (Entry e : out) fillExtras(e, labeled);
            return out;
        }

        // 配对：一行一个地址、下一行一个密钥是最常见写法；数量不齐时按下标兜
        for (int i = 0; i < plainUrls.size(); i++) {
            Entry e = new Entry();
            e.baseUrl = plainUrls.get(i);
            if (i < keys.size()) e.key = keys.get(i);
            if (anthropic) e.type = Config.TYPE_ANTHROPIC;
            fillExtras(e, labeled);
            out.add(e);
        }
        // 只有一个地址、多个密钥 → 一个密钥一个账号（同地址多号很常见）
        if (plainUrls.size() == 1 && keys.size() > 1) {
            out.clear();
            for (String k : keys) {
                Entry e = new Entry();
                e.baseUrl = plainUrls.get(0);
                e.key = k;
                if (anthropic) e.type = Config.TYPE_ANTHROPIC;
                fillExtras(e, labeled);
                out.add(e);
            }
        }

        for (Entry e : out) {
            if (e.key.isEmpty()) e.note = "只认出地址，没找着密钥";
            else if (looksLikeSelf(e.baseUrl)) e.note = "这是本机网关自己的地址，加进来会绕回自己";
        }
        return out;
    }

    private static void fillExtras(Entry e, Entry labeled) {
        if (e.models.isEmpty()) e.models = labeled.models;
        if (e.name.isEmpty()) e.name = labeled.name;
    }

    /** https://host/v1/chat/completions/ → https://host/v1 */
    static String normalizeUrl(String u) {
        if (u == null) return "";
        String s = u.trim();
        while (s.endsWith("/") || s.endsWith("\\")) s = s.substring(0, s.length() - 1);
        for (String tail : new String[]{"/chat/completions", "/completions", "/messages",
                "/embeddings", "/models", "/responses"}) {
            if (s.toLowerCase(Locale.US).endsWith(tail)) {
                s = s.substring(0, s.length() - tail.length());
                break;
            }
        }
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        // 只给了域名就补 /v1；已经有版本段的原样不动
        try {
            java.net.URL parsed = new java.net.URL(s);
            String path = parsed.getPath() == null ? "" : parsed.getPath();
            if (path.isEmpty() || "/".equals(path)) s = s + "/v1";
        } catch (Exception ignored) {
        }
        return s;
    }

    private static final Pattern URL_KEY = Pattern.compile("[?&#]key=([^&#\\s]+)");

    private static String keyFromUrl(String u) {
        Matcher m = URL_KEY.matcher(u);
        return m.find() ? m.group(1) : "";
    }

    private static String stripKeyFromUrl(String u) {
        int i = u.indexOf("?key=");
        if (i < 0) i = u.indexOf("&key=");
        return i < 0 ? u : u.substring(0, i);
    }

    /** 指向本机自己的网关（会无限绕回自己）。 */
    private static boolean looksLikeSelf(String baseUrl) {
        try {
            java.net.URL u = new java.net.URL(baseUrl);
            String host = u.getHost() == null ? "" : u.getHost();
            boolean local = "127.0.0.1".equals(host) || "localhost".equals(host)
                    || "::1".equals(host) || "0.0.0.0".equals(host);
            return local && u.getPort() == Gateway.config().port;
        } catch (Exception e) {
            return false;
        }
    }

    /** 从地址里推一个账号名，例如 https://api.siliconflow.cn/v1 → siliconflow */
    static String nameFromUrl(String baseUrl) {
        try {
            java.net.URL u = new java.net.URL(baseUrl);
            String host = u.getHost() == null ? "" : u.getHost();
            String[] parts = host.split("\\.");
            String best = host;
            for (String p : parts) {
                String l = p.toLowerCase(Locale.US);
                if (l.equals("api") || l.equals("www") || l.equals("com") || l.equals("cn")
                        || l.equals("net") || l.equals("org") || l.equals("io")
                        || l.equals("ai") || l.equals("xyz")) {
                    continue;
                }
                best = p;
                break;
            }
            return best.isEmpty() ? host : best;
        } catch (Exception e) {
            return "中转";
        }
    }

    /* ------------------------------------------------------------ 界面 */

    /**
     * 一键添加对话框。主页和账号页共用同一个。
     * 贴进来 → 识别 → **验证能用** → 才写进配置。
     */
    public static void dialog(final android.app.Activity a, final Runnable onDone) {
        android.widget.LinearLayout box = Ui.column(a);
        box.addView(Ui.label(a, "把地址和密钥粘进来就行，格式不限：\n"
                + "· 两行分开，或一行连着（key@url、url|key）\n"
                + "· CC / 环境变量（ANTHROPIC_BASE_URL=… 、OPENAI_API_KEY=…）\n"
                + "· JSON、YAML、curl 命令行\n"
                + "会先验证能不能用，能用的才加。"));
        final android.widget.EditText input = Ui.field(a,
                "https://api.xxx.com/v1\nsk-xxxxxxxx", "", true);
        input.setMinLines(4);
        box.addView(input, Ui.lp(a, 12));

        Ui.dialog(a, "一键添加", Ui.scroller(a, box), "取消", null, "识别并添加", () -> {
            final String text = input.getText().toString();
            android.widget.Toast.makeText(a, "正在识别并验证…", android.widget.Toast.LENGTH_SHORT).show();
            Threads.io(() -> {
                java.util.List<Entry> entries = parse(text);
                if (entries.isEmpty()) {
                    a.runOnUiThread(() -> android.widget.Toast.makeText(a,
                            "没认出地址或密钥，再看看贴的内容", android.widget.Toast.LENGTH_LONG).show());
                    return;
                }
                int added = 0;
                StringBuilder skipped = new StringBuilder();
                for (Entry e : entries) {
                    if (!e.ready()) {
                        skipped.append("· ").append(e.note).append("\n");
                        continue;
                    }
                    verify(e);
                    if (e.usable) {
                        apply(e);
                        added++;
                        Log.i("一键添加：" + e.baseUrl + "（" + e.models.split(",").length + " 个模型）");
                    } else {
                        skipped.append("· ").append(e.baseUrl).append("\n  ").append(e.note).append("\n");
                    }
                }
                if (added > 0) {
                    Gateway.save(a);
                    Log.i("一键添加完成：新增/更新 " + added + " 个");
                }
                final int done = added;
                final String why = skipped.toString();
                a.runOnUiThread(() -> {
                    if (onDone != null) onDone.run();
                    if (done == 0) {
                        android.widget.Toast.makeText(a, "一个都没加：" + why,
                                android.widget.Toast.LENGTH_LONG).show();
                    } else if (why.isEmpty()) {
                        android.widget.Toast.makeText(a, "添加了 " + done + " 个账号",
                                android.widget.Toast.LENGTH_LONG).show();
                    } else {
                        android.widget.Toast.makeText(a, "添加了 " + done + " 个，跳过 "
                                + (entries.size() - done) + " 个", android.widget.Toast.LENGTH_LONG).show();
                    }
                    if (!why.isEmpty()) Log.w("一键添加跳过的：\n" + why);
                });
            });
        });
    }

    /* -------------------------------------------------------------- 验证 */

    /**
     * 真的去拉一次模型列表——拉得到才算"能用"。
     * 结果写回 e：能用就填 models 并置 usable；不能用就把原因写进 note。
     */
    public static void verify(Entry e) {
        if (!e.ready()) return;
        try {
            Config.Provider tmp = new Config.Provider();
            tmp.type = e.type;
            tmp.baseUrl = e.baseUrl;
            tmp.apiKey = e.key;
            List<String> models = Upstreams.fetchModels(tmp);
            if (models == null || models.isEmpty()) {
                e.note = "连上了，但一个模型都没返回";
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (String m : models) {
                if (sb.length() > 0) sb.append(',');
                sb.append(m);
            }
            e.models = sb.toString();
            e.usable = true;
        } catch (Exception ex) {
            e.note = "用不了：" + Upstreams.shortenPublic(String.valueOf(ex.getMessage()));
        }
    }

    /** 验证通过的才真的写进配置。 */
    public static Config.Provider apply(Entry e) {
        Config cfg = Gateway.config();
        Config.Provider p = null;
        for (Config.Provider q : cfg.providers) {
            if (e.baseUrl.equalsIgnoreCase(q.baseUrl) && e.key.equals(q.apiKey)) {
                p = q;
                break;
            }
        }
        if (p == null) {
            p = new Config.Provider();
            p.type = e.type;
            p.id = "qa" + Math.abs(System.currentTimeMillis() % 1000000);
            p.name = e.name.isEmpty() ? nameFromUrl(e.baseUrl) : e.name;
            p.baseUrl = e.baseUrl;
            cfg.providers.add(p);
        }
        p.apiKey = e.key;
        p.enabled = true;
        p.lastError = "";
        p.lastErrorAt = 0L;
        p.badModels.clear();
        if (!e.models.isEmpty()) {
            java.util.List<String> typed = new java.util.ArrayList<>();
            for (String m : e.models.split("[,，\\s]+")) {
                if (!m.trim().isEmpty()) typed.add(m.trim());
            }
            p.setModels(typed);
        }
        return p;
    }
}
