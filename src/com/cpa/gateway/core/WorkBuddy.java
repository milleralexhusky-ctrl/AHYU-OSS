package com.cpa.gateway.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Native WorkBuddy / CodeBuddy upstream (the service wb2api wraps), so the gateway no longer needs
 * a second app running on 127.0.0.1:7863.
 *
 * Protocol facts established against the live service:
 *  - POST {base}/v2/chat/completions speaks OpenAI chat-completions with a WorkBuddy header set.
 *  - The upstream REFUSES non-streaming ("code":11101) - every request is forced to stream:true and
 *    aggregated here, exactly like the reference implementation documents.
 *  - Failures can arrive as HTTP 200 with a business "code" field, so body codes are inspected.
 *  - GET {base}/v3/config returns the per-agent model catalog.
 *  - cn realm bases: www.codebuddy.cn, copilot.tencent.com. global realm base: www.workbuddy.ai.
 */
public final class WorkBuddy {

    /** Convenience accessor for diagnostics. */
    public static org.json.JSONObject fromJson2(String json) {
        try {
            return new org.json.JSONObject(json == null || json.isEmpty() ? "{}" : json);
        } catch (Exception e) {
            return new org.json.JSONObject();
        }
    }


    private WorkBuddy() {}

    public static final String REALM_CN = "cn";
    public static final String REALM_GLOBAL = "global";
    private static final long REFRESH_SKEW_SECONDS = 300L;

    /* ------------------------------------------------------------- account */

    public static final class Account {
        public String uid = "";
        public String nickname = "";
        public String realm = REALM_CN;
        public String accessToken = "";
        public String refreshToken = "";
        public long accessExpiresAt = 0L;
        public long refreshExpiresAt = 0L;
        public String deviceToken = "";
        public String domain = "";
        public String enterpriseId = "";
        public String region = "";

        public static Account fromJson(String json) {
            try {
                return fromJson(new JSONObject(json));
            } catch (Exception e) {
                return new Account();
            }
        }

        public static Account fromJson(JSONObject o) {
            Account a = new Account();
            a.uid = Proto.optString(o, "uid", "");
            a.nickname = Proto.optString(o, "nickname", "");
            a.realm = Proto.optString(o, "realm", REALM_CN);
            a.accessToken = Proto.optString(o, "access_token", Proto.optString(o, "accessToken", ""));
            a.refreshToken = Proto.optString(o, "refresh_token", Proto.optString(o, "refreshToken", ""));
            Integer ae = Proto.optInt(o, "access_expires_at");
            a.accessExpiresAt = ae == null ? 0L : ae.longValue();
            Integer re = Proto.optInt(o, "refresh_expires_at");
            a.refreshExpiresAt = re == null ? 0L : re.longValue();
            a.deviceToken = Proto.optString(o, "device_token", "");
            a.domain = Proto.optString(o, "domain", "");
            a.enterpriseId = Proto.optString(o, "enterprise_id", "");
            a.region = Proto.optString(o, "region", "");
            return a;
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("uid", uid);
                o.put("nickname", nickname);
                o.put("realm", realm);
                o.put("access_token", accessToken);
                o.put("refresh_token", refreshToken);
                o.put("access_expires_at", accessExpiresAt);
                o.put("refresh_expires_at", refreshExpiresAt);
                o.put("device_token", deviceToken);
                o.put("domain", domain);
                o.put("enterprise_id", enterpriseId);
                o.put("region", region);
            } catch (Exception ignored) {
            }
            return o;
        }

        public boolean usable() {
            return !accessToken.isEmpty() && !uid.isEmpty();
        }

        public String label() {
            String who = nickname.isEmpty() ? uid : nickname;
            if (who.length() > 8) who = who.substring(who.length() - 4);
            return realm + "/" + who;
        }
    }

    /**
     * Client identity string. The upstream serves a wider model catalogue to requests that identify
     * themselves the way the CLI does - measured on /v3/config:
     *   WorkBuddy/5.5.6 CLI/2.137.1                  -> 68 models (global)
     *   WorkBuddy/5.5.2 WorkBuddy AI/5.5.2 CLI/5.5.2 -> 68 models (cn)
     *   WorkBuddy/5.5.4 (old default)                -> 52~55 models
     */
    public static String clientUa(boolean global) {
        return global ? "WorkBuddy/5.5.6 CLI/2.137.1"
                : "WorkBuddy/5.5.2 WorkBuddy AI/5.5.2 CLI/5.5.2";
    }

    public static String clientUa(String realm) {
        return clientUa(REALM_GLOBAL.equalsIgnoreCase(realm));
    }

    public static List<String> bases(String realm) {
        List<String> out = new ArrayList<>();
        if (REALM_GLOBAL.equalsIgnoreCase(realm)) {
            out.add("https://www.workbuddy.ai");
        } else {
            out.add("https://www.codebuddy.cn");
            out.add("https://copilot.tencent.com");
        }
        return out;
    }

    /**
     * `/v3/config` 里上游自己写明的基址（data.endpoint）。
     *
     * 4.31.1 实测：global 账号的 config 写的是 **https://www.codebuddy.ai**，
     * 而我们（以及参考实现 wb2api）一直打 www.workbuddy.ai。两个域名鉴权、目录、错误码
     * 完全一致，所以它不是 11128 的原因；但官方客户端是照这个字段走的，
     * 没有理由比它更"自己猜"。这里按账号名缓存，拿不到就回落到 {@link #primaryBase}。
     */
    private static final java.util.Map<String, String> ENDPOINT_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 真基址：优先用上游 config 里的 endpoint，其次用户配置，最后按 realm 的默认值。 */
    static String endpointFor(Config.Provider p, Account acc) {
        String cached = p == null ? null : ENDPOINT_CACHE.get(p.name);
        if (cached != null && !cached.isEmpty()) return cached;
        return primaryBase(acc == null ? REALM_CN : acc.realm, p == null ? null : p.baseUrl);
    }

    /**
     * 按**指定 realm** 取基址：{@code keepCached} 为真时沿用上游 config.endpoint 的缓存
     * （realm 没变时就是正常路径），为假时忽略缓存——缓存是从"账号自己的 realm"那次
     * {@code /v3/config} 里学来的，换个区域身份就不能再信它，必须回落到该 realm 的默认基址。
     */
    static String endpointFor(Config.Provider p, Account acc, String realm, boolean keepCached) {
        String own = acc == null ? REALM_CN : acc.realm;
        if (keepCached || REALM_GLOBAL.equalsIgnoreCase(own) == REALM_GLOBAL.equalsIgnoreCase(realm)) {
            return endpointFor(p, acc);
        }
        return primaryBase(realm, p == null ? null : p.baseUrl);
    }

    static String primaryBase(String realm, String configured) {
        if (configured != null) {
            String b = Upstreams.trimSlash(configured);
            if (!b.isEmpty()) return b;
        }
        return bases(realm).get(0);
    }

    /**
     * 把 WorkBuddy 的业务码归类成"该退避"的类别。
     *
     * 上游不少限制是 **HTTP 200 + 业务码** 表达的（我们只认 data: 行，所以会走到这里）。
     * 返回 null 表示"这是真错误"，交给普通异常处理。
     */
    private static String bizKind(int code, String msg) {
        String m = msg == null ? "" : msg.toLowerCase(java.util.Locale.US);
        /* 11128 是**渠道校验**，不是限流（4.31.1 起单独归一类）。
           以前把它算进 rate，于是被上游标记的账号每次都在"退避 429"，既退避不掉、
           又让界面显示成"限流"。它换号才有用，退避没有意义。 */
        if (code == 11128 || m.contains("channel verification") || m.contains("request illegal")) {
            return "channel";
        }
        if (m.contains("rate") || m.contains("limit") || m.contains("频繁") || m.contains("too many")
                || m.contains("busy")) {
            return "rate";
        }
        /* credits 用完时上游回 HTTP429 + code 14018「Credits exhausted…purchase add-on packs」
           （4.31.1 实测，WB3/WB4 都是这条），说明是额度这类，不是限流。 */
        if (m.contains("quota") || m.contains("balance") || m.contains("额度") || m.contains("积分")
                || m.contains("credits exhausted") || code == 14018 || code == 11129) {
            return "quota";
        }
        if (m.contains("unauthor") || m.contains("expired") || m.contains("login")
                || m.contains("登录") || code == 401 || code == 403 || code == 11130) {
            return "auth";
        }
        return null;
    }

    /* -------------------------------------------------------------- headers */

    static Map<String, String> headers(Account acc, String base, String userAgent) {
        return headers(acc, acc == null ? REALM_CN : acc.realm, base, userAgent);
    }

    /**
     * 指定 realm 的出站头（{@code realm} 与 {@code acc.realm} 可以是同一个，也可以用来"按国际版身份重发一次"）。
     *
     * 为什么需要这个重载：11128 的官方解释是"这次调用没通过渠道校验，可能不是官方客户端"，
     * 而 WorkBuddy 有**两个区域体系**（codebuddy.cn 与 workbuddy.ai），两侧的渠道校验不是同一套。
     * 实测本机账号池里国际版号能过、国内版号常年 11128（见 4.31.1），但国内号在
     * **国际版身份**下能不能过从来没试过——因为以前 realm 只会来自账号本身。
     * 这里只让"身份相关"的三样东西跟着 realm 走（UA、Origin/Referer、Accept-Language），
     * 鉴权、企业、部门、设备指纹仍取账号自身的值。
     */
    static Map<String, String> headers(Account acc, String realm, String base, String userAgent) {
        Map<String, String> h = new HashMap<>();
        boolean global = REALM_GLOBAL.equalsIgnoreCase(realm);
        String origin = global ? "https://www.workbuddy.ai" : "https://www.codebuddy.cn";
        h.put("Content-Type", "application/json");
        h.put("X-CodeBuddy-Request", "1");
        h.put("Accept-Language", global ? "en-US" : "zh-CN");
        h.put("Accept", "application/json, */*");
        h.put("X-Requested-With", "XMLHttpRequest");
        h.put("Origin", origin);
        h.put("Referer", origin + "/");
        h.put("User-Agent", userAgent == null || userAgent.isEmpty() ? clientUa(global) : userAgent);
        if (!acc.accessToken.isEmpty()) h.put("Authorization", "Bearer " + acc.accessToken);
        if (!acc.uid.isEmpty()) h.put("X-User-Id", acc.uid);
        if (!acc.enterpriseId.isEmpty()) {
            h.put("X-Enterprise-Id", acc.enterpriseId);
            h.put("X-Tenant-Id", acc.enterpriseId);
        } else {
            h.put("X-No-Enterprise-Id", "1");
        }
        if (!acc.domain.isEmpty()) h.put("X-Domain", acc.domain);
        else h.put("X-No-Department-Info", "1");
        /* 官方客户端（wb2api 1.6.3 `eb2.a()`）：device_token 非空才带这个头，
           为空时**整个头都不出现**（源码 `if (Z(device_token)) return;`）。
           4.36.x 之前这里塞空串，形态与官方相反。 */
        if (acc.deviceToken != null && !acc.deviceToken.isEmpty()) {
            h.put("X-Device-Token", acc.deviceToken);
        }
        /* 4.36.5：删掉官方客户端（wb2api 1.6.3 `eb2.a()`）根本不发的 4 个声明头和
           X-Request-ID——多出来的头本身就是"非官方"特征。
           设备标识改用官方同一套派生：SHA-256("wb2a:<kind>:<uid>") 前 18 字节 base64url。
           注意 X-Session-ID 官方也是**按 uid 固定**的，不是每次随机。 */
        if (!acc.uid.isEmpty()) {
            String machine = wb2aId("machine", acc.uid);
            String session = wb2aId("session", acc.uid);
            if (!machine.isEmpty()) h.put("X-Machine-ID", machine);
            if (!session.isEmpty()) h.put("X-Session-ID", session);
        }
        return h;
    }

    /* --------------------------------------------------------- token upkeep */

    static Map<String, String> loginHeaders(String base) {
        Map<String, String> h = new HashMap<>();
        h.put("Content-Type", "application/json");
        h.put("Accept", "application/json");
        h.put("X-CodeBuddy-Request", "1");
        h.put("X-Requested-With", "XMLHttpRequest");
        h.put("Origin", base);
        h.put("Referer", base + "/");
        // 版本号对齐参考实现（workbuddy2api 的 oauth_login.py 用的是 CLI/2.148.0）：
        // 上游对版本是敏感的，2.63.2 这种旧号很可能就是"登录完成却一直 11217"的原因之一
        h.put("User-Agent", "CLI/2.148.0 CodeBuddy/2.148.0");
        h.put("Accept", "application/json, text/plain, */*");
        return h;
    }

    /**
     * Step 1 of the CLI plugin authorization flow: the upstream hands us a state id plus the URL the
     * user has to open. Verified live: POST {base}/v2/plugin/auth/state?platform=CLI.
     */
    public static JSONObject beginLogin(String realm, String base) throws Exception {
        String b = primaryBase(realm, base);
        String body = Upstreams.postJson(b + "/v2/plugin/auth/state?platform=CLI", loginHeaders(b), new JSONObject());
        JSONObject o = new JSONObject(body);
        JSONObject data = o.optJSONObject("data");
        if (data == null) throw new IllegalStateException("上游未返回授权信息：" + Upstreams.shortenPublic(body));
        JSONObject out = new JSONObject();
        out.put("state", Proto.optString(data, "state", ""));
        out.put("authUrl", Proto.optString(data, "authUrl", ""));
        out.put("base", b);
        out.put("realm", realm);
        return out;
    }

    /**
     * Step 2: poll until the user finishes logging in. Pending answers arrive as HTTP 200 with the
     * business code 11217 ("login ing..."), which is exactly the trap this upstream likes to set.
     */
    public static JSONObject pollLogin(String realm, String base, String state) throws Exception {
        String b = primaryBase(realm, base);
        String url = b + "/v2/plugin/auth/token?state=" + URLEncoder.encode(state, "UTF-8");
        Map<String, String> h = loginHeaders(b);
        // 官方在"没有身份字段"的请求上会声明这四个 X-No-*（取值是字符串 true，不是 1）
        h.put("X-No-Authorization", "true");
        h.put("X-No-User-Id", "true");
        h.put("X-No-Enterprise-Id", "true");
        h.put("X-No-Department-Info", "true");
        String body = Upstreams.getWithHeaders(url, h);
        JSONObject o = new JSONObject(body);
        int code = o.optInt("code", -1);
        JSONObject data = o.optJSONObject("data");
        if (data == null && o.opt("data") instanceof String) {
            try {
                data = new JSONObject(String.valueOf(o.opt("data")));
            } catch (Exception ignored) {
            }
        }
        Account probe = accountFromTokenResponse(data, realm);
        boolean looksDone = probe != null && !probe.accessToken.isEmpty();
        // 11217 = token 还没好；12151 = account 还没好。两个都算"还在等"，别的码要当错误抛出去
        boolean pendingCode = code == 11217 || code == 12151 || code == 0;
        if (!looksDone && !pendingCode) {
            Log.w("workbuddy 授权返回异常码 " + code + "：" + Upstreams.shortenPublic(body));
        }
        if (code != 0 && !looksDone && code != 12151) {
            Log.i("workbuddy 授权轮询 code=" + code + " msg=" + Proto.optString(o, "msg", ""));
            JSONObject out = new JSONObject();
            out.put("status", "pending");
            out.put("code", code);
            out.put("msg", Proto.optString(o, "msg", ""));
            return out;
        }
        Account acc = probe;
        if (acc == null) {
            Log.w("workbuddy 授权响应无法解析：" + Upstreams.shortenPublic(body));
            throw new IllegalStateException("授权已返回但没解析到 access_token，原始响应已写日志（已脱敏）");
        }
        Log.i("workbuddy 授权返回：uid=" + acc.uid + " realm=" + acc.realm);
        if (acc.accessToken.isEmpty()) {
            Log.w("workbuddy 授权响应没有 access_token：" + Upstreams.shortenPublic(body));
            throw new IllegalStateException("授权已返回但没解析到 access_token，原始响应已写日志（已脱敏）");
        }
        if (acc.uid.isEmpty() || acc.nickname.isEmpty()) {
            try {
                Map<String, String> ah = loginHeaders(b);
                ah.put("X-No-User-Id", "true");
                ah.put("X-No-Enterprise-Id", "true");
                ah.put("X-No-Department-Info", "true");
                if (acc != null && !acc.accessToken.isEmpty()) {
                    ah.put("Authorization", "Bearer " + acc.accessToken);
                }
                String accBody = Upstreams.getWithHeaders(b + "/v2/plugin/login/account?state=" + URLEncoder.encode(state, "UTF-8"), ah);
                JSONObject ao = new JSONObject(accBody);
                JSONObject ad = ao.optJSONObject("data");
                if (ad != null) {
                    if (acc.uid.isEmpty()) acc.uid = Proto.optString(ad, "uid", Proto.optString(ad, "userId", ""));
                    if (acc.nickname.isEmpty()) acc.nickname = Proto.optString(ad, "nickname", Proto.optString(ad, "name", Proto.optString(ad, "username", "")));
                }
            } catch (Exception ignored) {
            }
        }
        if (acc.uid.isEmpty()) acc.uid = "wb-" + Math.abs(acc.accessToken.hashCode());
        JSONObject out = new JSONObject();
        out.put("status", "done");
        out.put("account", acc.toJson());
        return out;
    }

    /** Tolerant reader for the token response: the exact key set is not documented anywhere. */
    static Account accountFromTokenResponse(JSONObject data, String realm) {
        if (data == null) return null;
        // some realms nest the credential payload one level deeper
        JSONObject nested = data.optJSONObject("token");
        if (nested == null) nested = data.optJSONObject("auth");
        if (nested != null) {
            JSONObject merged = new JSONObject();
            try {
                java.util.Iterator<String> it = data.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    merged.put(k, data.opt(k));
                }
                java.util.Iterator<String> it2 = nested.keys();
                while (it2.hasNext()) {
                    String k = it2.next();
                    if (!merged.has(k)) merged.put(k, nested.opt(k));
                }
            } catch (Exception ignored) {
            }
            data = merged;
        }
        Account acc = new Account();
        acc.realm = realm;
        acc.accessToken = firstNonEmpty(data, "access_token", "accessToken", "token", "id_token");
        acc.refreshToken = firstNonEmpty(data, "refresh_token", "refreshToken");
        acc.uid = firstNonEmpty(data, "uid", "user_id", "userId", "sub", "account_id");
        acc.nickname = firstNonEmpty(data, "nickname", "name", "username", "display_name");
        acc.domain = firstNonEmpty(data, "domain");
        acc.enterpriseId = firstNonEmpty(data, "enterprise_id", "enterpriseId");
        acc.region = firstNonEmpty(data, "region");
        acc.deviceToken = firstNonEmpty(data, "device_token", "deviceToken");
        JSONObject account = data.optJSONObject("account");
        if (account == null) account = data.optJSONObject("user");
        if (account != null) {
            if (acc.uid.isEmpty()) acc.uid = firstNonEmpty(account, "uid", "id", "user_id", "userId");
            if (acc.nickname.isEmpty()) acc.nickname = firstNonEmpty(account, "nickname", "name", "username");
        }
        Integer expiresIn = Proto.optInt(data, "expires_in");
        if (expiresIn == null) expiresIn = Proto.optInt(data, "expiresIn");
        long now = System.currentTimeMillis() / 1000;
        acc.accessExpiresAt = expiresIn == null ? now + 28L * 24 * 3600 : now + expiresIn.intValue() - 30;
        Integer refreshExpires = Proto.optInt(data, "refresh_expires_in");
        if (refreshExpires != null) acc.refreshExpiresAt = now + refreshExpires.intValue();
        // last resort: the access token itself carries the account id in "sub"
        if (acc.uid.isEmpty() && !acc.accessToken.isEmpty()) {
            try {
                String[] parts = acc.accessToken.split("\\.");
                if (parts.length >= 2) {
                    byte[] raw = android.util.Base64.decode(parts[1],
                            android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
                    JSONObject claims = new JSONObject(new String(raw, "UTF-8"));
                    acc.uid = Proto.optString(claims, "sub", "");
                    if (acc.nickname.isEmpty()) acc.nickname = Proto.optString(claims, "preferred_username", "");
                    if (acc.enterpriseId.isEmpty()) acc.enterpriseId = Proto.optString(claims, "enterprise_id", "");
                }
            } catch (Exception ignored) {
            }
        }
        return acc;
    }

    static String firstNonEmpty(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = Proto.optString(o, k, "");
            if (!v.isEmpty()) return v;
        }
        return "";
    }

    /**
     * Turns an account blob into a usable channel: create or update a workbuddy provider, pull its
     * model catalogue, persist. Used by the in-app login and by the authenticated import route.
     */
    public static JSONObject registerProvider(JSONObject accountJson) throws Exception {
        Account acc = Account.fromJson(accountJson);
        if (!acc.usable()) throw new IllegalStateException("凭据缺少 access_token 或 uid");
        Config cfg = Gateway.config();
        Config.Provider target = null;
        for (Config.Provider p : cfg.providers) {
            if (!Config.TYPE_WORKBUDDY.equals(p.type)) continue;
            Account existing = Account.fromJson(p.credentials);
            if (!existing.uid.isEmpty() && existing.uid.equals(acc.uid)) {
                target = p;
                break;
            }
        }
        boolean created = target == null;
        if (created) {
            target = new Config.Provider();
            target.type = Config.TYPE_WORKBUDDY;
            target.id = Config.newProviderId("wb");
            String who = acc.nickname;
            if (who.isEmpty() && acc.uid.length() >= 8) who = acc.uid.substring(0, 8);
            if (who.isEmpty()) who = REALM_GLOBAL.equalsIgnoreCase(acc.realm) ? "国际版" : "国内版";
            target.name = Config.nextAccountName("WorkBuddy", target.type);
            cfg.providers.add(target);
        }
        target.credentials = acc.toJson().toString();
        target.enabled = true;
        target.lastError = "";
        target.lastErrorAt = 0;
        target.badModels.clear();
        int modelCount = target.models.size();
        try {
            java.util.List<String> models = fetchModels(target);
            target.setModels(models);
            modelCount = target.models.size();
        } catch (Exception e) {
            Log.w("拉取 workbuddy 模型列表失败（不塞默认清单，避免把猜测当成事实）：" + e);
            if (target.models.isEmpty()) {
                modelCount = target.models.size();
            }
        }
        // 顺手把积分也拉一次，和模型列表一起落盘。
        //
        // 为什么在这里拉：积分原本只在 GatewayService.refreshCreditsInBackground()
        // 里刷新，而那是**网关启动后 3 秒**才跑的——刚登录完的账号要等到下次启动
        // 才会有数字（用户看到的是"有模型、没积分"）。登录路径本来就跑在后台线程
        // （WorkBuddyLoginActivity 用 Threads.io），多这一次网络调用不影响体验，
        // 却能立刻把积分显示出来。
        // 失败不抛：积分拿不到不该让登录失败，保留 -1、下次刷新再补。
        double credits = -1;
        try {
            credits = WbTasks.credits(target);
            if (credits >= 0) {
                target.credits = credits;
                target.creditsAt = System.currentTimeMillis() / 1000;
            }
        } catch (Throwable t) {
            Log.w("登录后拉取积分失败（不影响登录，下次启动会重试）：" + t);
        }

        Gateway.saveNow(Gateway.context());
        JSONObject out = new JSONObject();
        out.put("provider", target.name);
        out.put("created", created);
        out.put("models", modelCount);
        out.put("realm", acc.realm);
        out.put("credits", credits);
        return out;
    }

    /**
     * There is no token-refresh endpoint on this upstream (checked against the live service and
     * against the reference implementation, which only re-logs in when a session dies). So the
     * honest behaviour is: use the token while it lives, and say exactly what to do when it dies.
     */
    public static Account ensureFresh(Config.Provider p) throws Exception {
        // 原来是 static synchronized（类级锁）：一个账号走网络刷新 token 时，
        // 同 Provider 的其他账号全被堵住。现在按账号加锁，各刷各的。
        synchronized (FreshLocks.of(p)) {
            return ensureFreshInner(p);
        }
    }

    private static Account ensureFreshInner(Config.Provider p) throws Exception {
        Account acc = Account.fromJson(p.credentials);
        if (!acc.usable()) {
            String hint = p.credentials == null || p.credentials.isEmpty() ? "credentials 为空" : "credentials 里没有 access_token/uid";
            throw new IllegalStateException("workbuddy 账号不可用：" + hint);
        }
        long now = System.currentTimeMillis() / 1000;
        if (acc.accessExpiresAt > 0 && acc.accessExpiresAt <= now) {
            throw new IllegalStateException("workbuddy 的 access_token 已过期（上游无刷新接口），请在应用里点『登录 WorkBuddy』重新授权，一次约可用 28 天");
        }
        return acc;
    }

    /* ------------------------------------------------------------- upstream */

    /**
     * This upstream insists the first message is a system prompt: otherwise it answers
     * `code 11128 "first message is not system prompt"`, which apps show as
     * "请求已被安全策略拦截" (blocked by security policy). Clients that send no system prompt
     * (most of them) get one injected here.
     */
    static final String DEFAULT_SYSTEM = "You are a helpful AI assistant.";

    static void ensureSystemFirst(JSONObject body) {
        try {
            JSONArray msgs = body.optJSONArray("messages");
            boolean hasSystemFirst = false;
            if (msgs != null && msgs.length() > 0) {
                JSONObject first = msgs.optJSONObject(0);
                hasSystemFirst = first != null && "system".equals(Proto.optString(first, "role", ""));
            }
            if (hasSystemFirst) return;
            JSONArray fixed = new JSONArray();
            fixed.put(new JSONObject().put("role", "system").put("content", DEFAULT_SYSTEM));
            for (int i = 0; msgs != null && i < msgs.length(); i++) fixed.put(msgs.opt(i));
            body.put("messages", fixed);
            Log.i("workbuddy：客户端没给 system 提示，已自动补一条（避免 11128）");
        } catch (Exception e) {
            Log.w("补 system 提示失败：" + e);
        }
    }

    /* ------------------------------------------- 渠道校验：会话标识（4.29.10） */

    /**
     * 渠道校验的几种"会话标识"形状。shape 0 = 原样，和 4.29.9 之前完全一致。
     *
     * 真机（4.29.9 的 detect.log）拿到过 WB4 的**完整**拒绝：
     *   HTTP400 {"code":11128,"msg":"conversation id missing",
     *            "displayMsg":{"zh":"请求被拦截，请重新发送"},
     *            "displayTips":{"zh":"这次调用没通过渠道校验，可能不是官方客户端…",
     *                           "en":"This call failed channel verification, possibly because it was
     *                                 not sent from an official client."}}
     * 官方客户端每次对话都在一个会话里，请求自然带着会话标识；网关是无状态转发，
     * 从来没有这个字段。于是遇到这个错就按几种可能的字段名各试一次——谁通用谁，
     * 并把结果写进应用日志（探测时同时写 detect.log），成功的那一种可以后续固化成默认形状。
     *
     * 为什么不做"记住用那种形状、以后直接带"：另外几个 WorkBuddy 账号（WB3 / WB5）
     * 用原样就能通，给它们平白加上会话字段属于主动改变出站形状，没有必要。
     * 代价只是撞上校验的那个账号每次多一次 400 往返（实测 110ms 就回来了）。
     *
     * **4.29.10 真机 A/B 结论（别重复做）**：同一个请求形状，同 realm 的 WorkBuddy 3
     * 能过渠道校验（直接进业务层 `429 Credits exhausted`），WorkBuddy 4 过不去——
     * 形状无关、**账号/会话被标记**才是原因。两轮形状实验（5 种 body 会话字段 + 5 种 UA 身份，
     * 含最小头）全部被拒，requestId 恒定。
     *
     * 第一轮 5 种"body 会话字段"形状**全部被拒**，
     * 而且 5 次回包的 `requestId` 一模一样——说明上游这次校验根本不看我们发的 body。
     * 于是第二轮改成试**客户端身份**（UA 三段式，来自反编译出来的 wb2api 出站头构造），
     * 因为"渠道校验/不是官方客户端"这句话指向的正是身份，而不是会话字段。
     *
     * 上限：某个账号 5 种形状都被拒之后，本进程内**不再重试**（否则每探一次就是 5 次 400 往返），
     * 但仍保留"原样"那一轮——上游若改回去，原样自己就会通；标记会落盘，重启后仍生效。
     */
    /** 5 种形状都被拒过的账号；持久化到 gateway.json，重启不再从头试一遍。 */
    private static final java.util.Set<String> CONV_HOPELESS =
            java.util.Collections.newSetFromMap(
                    new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

    /* 4.36.6：这两个"已经试到底了"的标记以前只活在内存里，进程重启就清空，
       于是每次开机又对同一个号重打 10 次 400。现在落到 Config 里长期记住。
       清了也没关系——只会多试一次，不会错。 */
    private static void rememberHopeless(String name, boolean conv) {
        java.util.Set<String> set = conv ? CONV_HOPELESS : INTL_HOPELESS;
        if (!set.add(name)) return;
        try {
            Config.markHopeless(name, conv ? "conv" : "intl");
        } catch (Throwable ignored) {
        }
    }

    /**
     * 按**国际版身份**重发也被拒过的账号：不再试（标记落盘，重启后仍生效）。
     *
     * 为什么要这条"国际版重发"：
     *   - WorkBuddy 是两个区域体系（codebuddy.cn / workbuddy.ai），渠道校验实现不同；
     *     本机账号池里**国际版号能过、国内号常年 11128**（4.31.1 实测），说明 11128 未必
     *     是"这个账号被永久标记"，也可能只是"在以国内版身份调用时被拒"。
     *   - 4.31.1 试过的那些形状（缺头、UA 三段式、conversation_id、prompt_cache_key、
     *     codebuddy.ai 域名、极简头）全都在**同一个区域体系内**变化，没有一条换过区域身份。
     *   - 所以这条是"唯一没试过的那一类变量"，代价可控：只在第一次撞上渠道校验时多一次往返，
     *     被拒就整进程记住，不再重复。
     *
     * 关掉的办法：配置项 WorkBuddy 下 intl_fallback=false（见 {@link #intlFallbackFor}）。
     */
    private static final java.util.Set<String> INTL_HOPELESS =
            java.util.Collections.newSetFromMap(
                    new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

    /**
     * "试到底"标记的统一查询：内存 Set + 落盘的 Provider.hopeless 都要看。
     *
     * 4.36.6 只做了"写盘"，没有任何地方"读回"：Config.Provider.hopeless 存着，
     * 但判断仍只查内存 Set，进程一重启就又从 0 开始打 10 次 400。
     * 这里在查询时顺带灌回内存，读写两端就闭环了。
     */
    private static boolean hopeless(Config.Provider p, boolean conv) {
        if (p == null || p.name == null) return false;
        java.util.Set<String> mem = conv ? CONV_HOPELESS : INTL_HOPELESS;
        if (mem.contains(p.name)) return true;
        String kind = conv ? "conv" : "intl";
        if (p.hopeless != null && p.hopeless.contains(kind)) {
            mem.add(p.name);
            return true;
        }
        return false;
    }

    /**
     * 这个账号这次要不要给"国际版身份重发"一次机会。
     *
     * 只有国内区域账号、且没被本进程拉黑、且用户没关这个开关时才走。
     * 国际版账号自己就是 global，重发等于把同一请求打第二遍，纯浪费——所以跳过。
     */
    static boolean intlFallbackFor(Config.Provider p, Account acc) {
        if (p == null || acc == null) return false;
        if (REALM_GLOBAL.equalsIgnoreCase(acc.realm)) return false;
        if (hopeless(p, false)) return false;
        String flag = Proto.optString(p.options, "intl_fallback", "true");
        return !"false".equalsIgnoreCase(flag);
    }

    /** 4.31.1/4.36.5 实测：几种形状全被 11128 拒，且 requestId 恒定 —— 形状不是变量。
     *  置 true 后遇到渠道校验直接收手（只保留"原样"那一轮，上游若改回去原样自己会通）。 */
    private static final boolean CONV_HOPELESS_SHAPES_KNOWN = true;

    static final String[] CONV_SHAPES = {
            "原样",
            "UA=WorkBuddy AI 三段式(global)",
            "UA=WorkBuddy 三段式(cn)",
            "UA(global 三段式)+body conversationId",
            "UA(cn 三段式)+body conversationId+X-Device-Token",
    };

    /**
     * 官方三段式 UA。
     *
     * 来源：把手机上那个能跑通的 wb2api（com.wb.api1.5.0）反编译出来，它的出站头构造里
     * 明确写着（`eb2.a(ef, realm, auth, z)`）：
     *   UA = "WorkBuddy/5.5.4 " + (realm=="global" ? "WorkBuddy AI" : "WorkBuddy") + "/5.5.4 CLI/2.137.1"
     * 也就是说 **global 才带 "WorkBuddy AI"**，cn 是 "WorkBuddy" 重复三段。
     * 而本网关 `clientUa()` 是"测量目录条数"得来的经验值：global 发的是两段式
     * `WorkBuddy/5.5.6 CLI/2.137.1`——目录能刷出来，但推理请求可能过不了渠道校验。
     */
    private static String officialUa(boolean global) {
        return global ? "WorkBuddy/5.5.4 WorkBuddy AI/5.5.4 CLI/2.137.1"
                : "WorkBuddy/5.5.4 WorkBuddy/5.5.4 CLI/2.137.1";
    }

    /**
     * 官方客户端（wb2api 1.6.3 `eb2.a()` + `ln1.z()`）的设备标识派生：
     *   SHA-256("wb2a:" + kind + ":" + uid) 取前 18 字节，Base64 URL-safe 无填充。
     * kind 是 "machine"（固定）或 "session"（官方也是固定的，不随机）。
     * 与官方对不上号的头值会被上游当成"非官方客户端"，所以这里照抄算法。
     */
    private static String wb2aId(String kind, String uid) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(("wb2a:" + kind + ":" + uid).getBytes("UTF-8"));
            byte[] head = new byte[18];
            System.arraycopy(d, 0, head, 0, 18);
            return android.util.Base64.encodeToString(head,
                    android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    /** Base64 URL-safe、无填充。 */
    private static String b64url(byte[] b, int len) {
        byte[] head = new byte[len];
        System.arraycopy(b, 0, head, 0, len);
        return android.util.Base64.encodeToString(head,
                android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);
    }

    /**
     * 官方客户端的 conversation_id 派生（wb2api 1.6.3 `mn.java:793-841`）：
     *   拼串 = 每条 system 的 content + "|" ，再拼**第一条** user 的 content + "|"（最多 4000 字符）
     *   conversation_id = "d-" + Base64URL(SHA-256(拼串)[0..7])   —— 8 字节，共 11 字符
     * 没有可拼内容时官方不发这个字段，这里同样返回空串（交由调用方跳过）。
     */
    static String officialConversationId(org.json.JSONArray msgs) {
        String seed = conversationSeed(msgs);
        if (seed.isEmpty()) return "";
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(seed.getBytes("UTF-8"));
            return "d-" + b64url(d, 8);
        } catch (Throwable t) {
            return "";
        }
    }

    /** 官方 conversation_id 的哈希种子：所有 system 正文 + 第一条 user 正文。 */
    private static String conversationSeed(org.json.JSONArray msgs) {
        if (msgs == null || msgs.length() == 0) return "";
        try {
            StringBuilder sb = new StringBuilder();
            boolean gotUser = false;
            for (int i = 0; i < msgs.length(); i++) {
                JSONObject m = msgs.optJSONObject(i);
                if (m == null) continue;
                String role = Proto.optString(m, "role", "");
                if ("system".equals(role)) {
                    sb.append("system:").append(textOf(m.opt("content"))).append('|');
                } else if ("user".equals(role) && !gotUser) {
                    sb.append("user:").append(textOf(m.opt("content"))).append('|');
                    gotUser = true;
                }
                if (sb.length() > 4000) break;
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 轮次种子：最后一条 user 正文。工具循环里它不变，所以一轮只算一个 turn。 */
    private static String turnSeed(org.json.JSONArray msgs) {
        if (msgs == null || msgs.length() == 0) return "";
        try {
            for (int i = msgs.length() - 1; i >= 0; i--) {
                JSONObject m = msgs.optJSONObject(i);
                if (m == null) continue;
                if ("user".equals(Proto.optString(m, "role", ""))) {
                    String t = textOf(m.opt("content"));
                    return t.length() > 4000 ? t.substring(0, 4000) : t;
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /** 32 位小写十六进制（SHA-256 前 16 字节）。 */
    private static String hex32(String seed) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(seed.getBytes("UTF-8"));
            char[] hex = "0123456789abcdef".toCharArray();
            StringBuilder sb = new StringBuilder(32);
            for (int i = 0; i < 16; i++) {
                int v = d[i] & 0xFF;
                sb.append(hex[v >>> 4]).append(hex[v & 0x0F]);
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static String randomHex32() {
        try {
            java.util.UUID u = java.util.UUID.randomUUID();
            return (u.toString().replace("-", "")).toLowerCase(java.util.Locale.US);
        } catch (Throwable t) {
            return hex32("msg:" + System.nanoTime() + ":" + Math.random());
        }
    }

    /**
     * 2026-10-15 起，WorkBuddy 国际网关（www.codebuddy.ai / www.workbuddy.ai）要求
     * 每次聊天请求带上官方客户端的会话头：
     *
     *   X-Conversation-ID          会话级，32 位十六进制，跨轮次稳定
     *   X-Conversation-Request-ID  轮次级，工具循环/重试内稳定，新一轮变化
     *   X-Conversation-Message-ID  每次调用新值
     *   X-Request-ID               与 message id 相同
     *
     * 国际网关的渠道校验会看这组头；国内网关（copilot.tencent.com）没有这道检查。
     * 注意：HTTP 400 + 11128 "Illegal API invocation from an unapproved channel"
     * 并不只由缺头触发 —— 同一份带工具的大请求在某些模型上会被请求级风控拦住，
     * 换模型就过（见 4.50.4 记录）。这里补头是必要条件，不是充分条件。
     *
     * 值必须由内容派生：官方客户端用会话键 / 首条 user 消息派生 conversation，
     * 用当前轮最后一条 user 消息派生 request；随机值虽然也可能过校验，
     * 但会让上游用量面板把同一段对话拆成一堆碎片。
     */
    static void applyConversationHeaders(Map<String, String> h, JSONObject body) {
        if (h == null) return;
        JSONArray msgs = body == null ? null : body.optJSONArray("messages");
        String convSeed = conversationSeed(msgs);
        if (convSeed.isEmpty()) convSeed = "session:" + (msgs == null ? "" : msgs.toString());
        String conv = hex32("conv:" + convSeed);
        String turnSeed = turnSeed(msgs);
        String turn = hex32("turn:" + conv + ":" + turnSeed);
        String msg = randomHex32();
        if (!conv.isEmpty()) h.put("X-Conversation-ID", conv);
        if (!turn.isEmpty()) h.put("X-Conversation-Request-ID", turn);
        if (!msg.isEmpty()) {
            h.put("X-Conversation-Message-ID", msg);
            h.put("X-Request-ID", msg);
        }
    }

    /** content 既可能是字符串，也可能是 [{"type":"text","text":"…"}] 数组。 */
    private static String textOf(Object content) {
        if (content == null) return "";
        if (content instanceof String) return (String) content;
        if (content instanceof JSONArray) {
            StringBuilder sb = new StringBuilder();
            JSONArray a = (JSONArray) content;
            for (int i = 0; i < a.length(); i++) {
                Object it = a.opt(i);
                if (it instanceof JSONObject) {
                    String t = Proto.optString((JSONObject) it, "text", "");
                    if (!t.isEmpty()) sb.append(t);
                } else if (it != null) {
                    sb.append(String.valueOf(it));
                }
            }
            return sb.toString();
        }
        return String.valueOf(content);
    }

    /**
     * 官方客户端的 prompt_cache_key 派生（wb2api 1.6.3 `tl1.java:59-75`）：
     *   "wb2a-" + Base64URL(SHA-256("cache:" + uid + ":" + model)[0..11])
     */
    static String officialCacheKey(String uid, String model) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(("cache:" + uid + ":" + model).getBytes("UTF-8"));
            return "wb2a-" + b64url(d, 12);
        } catch (Throwable t) {
            return "";
        }
    }

    static void applyConvShape(int shape, JSONObject body, Map<String, String> headers,
                               String cid, Account acc) {
        boolean global = acc != null && REALM_GLOBAL.equalsIgnoreCase(acc.realm);
        try {
            switch (shape) {
                case 1:
                    headers.put("User-Agent", officialUa(true));
                    break;
                case 2:
                    headers.put("User-Agent", officialUa(false));
                    break;
                case 3:
                    headers.put("User-Agent", officialUa(global));
                    body.put("conversationId", cid);
                    break;
                case 4:
                    headers.put("User-Agent", officialUa(global));
                    body.put("conversationId", cid);
                    if (acc != null && !acc.deviceToken.isEmpty()) {
                        headers.put("X-Device-Token", acc.deviceToken);
                    }
                    headers.put("X-Session-ID", cid);
                    break;
                default:
                    break;
            }
        } catch (Exception ignored) {
        }
    }

    /** 这次失败是不是"渠道校验 / 会话标识"引起的？只认这几种说法，别误伤别的 400。 */
    static boolean looksLikeConvIdError(Throwable t) {
        String m = t == null ? "" : String.valueOf(t.getMessage()).toLowerCase(java.util.Locale.US);
        return m.contains("conversation id") || m.contains("channel verification")
                || m.contains("unapproved channel") || m.contains("illegal api invocation")
                || m.contains("未通过渠道校验") || m.contains("沒通過渠道校驗")
                || (m.contains("11128") && (m.contains("conversation")
                        || m.contains("channel") || m.contains("渠道")));
    }

    /**
     * 是不是"请求级风控拦截，官方让重发"的那种渠道校验。
     *
     * 和账号级 11128（conversation id missing / request illegal）区分开：
     * 账号级的重发多少次都一样，只会浪费往返；这一种上游明说 "Please send it again"，
     * 同一账号原样重发一次通常就过。
     */
    static boolean resendableChannel(Throwable t) {
        String m = t == null ? "" : String.valueOf(t.getMessage()).toLowerCase(java.util.Locale.US);
        /* 新措辞本身就是请求级风控（官方文案："请求被拦截，请重新发送"）。
           错误正文会被截到 400 字，短文案不一定保得住，所以只要看到
           unapproved channel 就允许重发；旧的账号级措辞仍不重发。 */
        return Http.softChannel(m);
    }

    /** 每次尝试都重新构造 body：重试绝不能复用被改过的对象。 */
    private static JSONObject buildBody(String model, Proto.Req req, String uid) throws Exception {
        JSONObject body = Upstreams.openAiBody(model, req, true);
        /* the upstream rejects non-streaming outright */
        body.put("stream", true);
        ensureSystemFirst(body);
        /* 诊断：在整理**之前**记录客户端发来的原始序列，用来区分
           (a) 客户端压根没把 tool 结果发回来 与 (b) 发了但被 normalizeToolPairs
           当"孤立"删掉——之前只在整理之后打过，看不出是哪一种。 */
        JSONArray wbMsgs = body.optJSONArray("messages");
        if (wbMsgs != null) Upstreams.normalizeToolPairs(wbMsgs);
        /* 4.36.7：官方客户端**每次**都会带这两个由内容派生的字段（wb2api 1.6.3
           mn.java:793 / tl1.java:59），而这里以前要么不发、要么发的是
           "wb-" + 时间戳 这种形态完全不同的值。上游若按"conversation_id 必须是
           d- 前缀的内容哈希"判断客户端真伪，形状实验再多种也过不了。 */
        try {
            if (body.opt("conversation_id") == null || Proto.optString(body, "conversation_id", "").isEmpty()) {
                String cid0 = officialConversationId(body.optJSONArray("messages"));
                if (!cid0.isEmpty()) body.put("conversation_id", cid0);
            }
            if (body.opt("prompt_cache_key") == null || Proto.optString(body, "prompt_cache_key", "").isEmpty()) {
                String ck = officialCacheKey(uid == null ? "" : uid, model);
                if (!ck.isEmpty()) body.put("prompt_cache_key", ck);
            }
        } catch (Throwable ignored) {
        }
        return body;
    }

    /** 渠道校验实验结果：进应用日志；探测请求另外写一行 detect.log。 */
    static void noteConv(Config.Provider p, Proto.Req req, int shape, String outcome, Throwable e) {
        String tail = e == null ? "" : " · " + Upstreams.shortenPublic(String.valueOf(e.getMessage()));
        Log.i("workbuddy 渠道校验：" + p.name + " · " + CONV_SHAPES[shape] + " " + outcome + tail);
        if (req != null && req.ephemeral) {
            Detect.file("# 渠道校验\t" + p.name + "\t" + CONV_SHAPES[shape] + "\t" + outcome
                    + (e == null ? "" : "\t" + Upstreams.shortenPublic(String.valueOf(e.getMessage()))));
        }
    }

    static final class Up implements Proto.Upstream {
        private final Config.Provider p;

        Up(Config.Provider p) {
            this.p = p;
        }

        @Override
        public void chat(String model, Proto.Req req, Proto.Sink sink) throws Exception {
            Account acc = ensureFresh(p);
            /* 渠道校验的两条路：先按账号自己的区域身份，再（可选）按国际版身份重发一次。 */
            final boolean tryIntl = intlFallbackFor(p, acc);
            final int passes = tryIntl ? 2 : 1;
            final int shapes = hopeless(p, true) ? 1 : CONV_SHAPES.length;
            Throttled channelFail = null;
            /* 上游偶尔回"风控拦截，请重新发送"：这种是请求级的，同一账号原样重发就可能过。
               实测 400ms 一次不够（还会被同一波风控再挡），所以最多重发 2 次、间隔递增；
               整个 chat() 有上限，避免把偶发拦截变成重试风暴。 */
            final int[] resent = {0};
            final int RESEND_MAX = 2;
            for (int pass = 0; pass < passes; pass++) {
                String realm = pass == 0 ? acc.realm : REALM_GLOBAL;
                boolean cross = pass > 0;
                String base = endpointFor(p, acc, realm, !cross);
                Map<String, String> baseHeaders = headers(acc, realm, base, null);
                String endpoint = base + "/v2/chat/completions";
                String convId = "wb-" + System.currentTimeMillis();
                for (int shape = 0; shape < shapes; shape++) {
                    JSONObject body = buildBody(model, req, acc.uid);
                    Map<String, String> hh = new HashMap<>(baseHeaders);
                    if (shape > 0) applyConvShape(shape, body, hh, convId, acc);
                    applyConversationHeaders(hh, body);
                    try {
                        send(endpoint, hh, body, sink, model, req, acc);
                        if (shape > 0 || cross) {
                            noteConv(p, req, shape, cross ? "通过（国际版身份）" : "通过", null);
                        }
                        return;
                    } catch (Throttled t) {
                        Throttled err = t;
                        /* "Illegal API invocation from an unapproved channel" 的官方提示就是
                           “请重新发送”：同一账号最多重发 2 次（间隔 500ms / 1.5s）；
                           账号被标记的旧 11128（conversation id missing / request illegal）
                           不在此列，不要浪费往返。 */
                        while (resendableChannel(err) && resent[0] < RESEND_MAX) {
                            resent[0]++;
                            noteConv(p, req, shape, "风控拦截，第 " + resent[0] + " 次重发", err);
                            try {
                                Thread.sleep(resent[0] == 1 ? 500L : 1500L);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                            try {
                                JSONObject body2 = buildBody(model, req, acc.uid);
                                Map<String, String> hh2 = new HashMap<>(baseHeaders);
                                if (shape > 0) applyConvShape(shape, body2, hh2, convId, acc);
                                applyConversationHeaders(hh2, body2);
                                send(endpoint, hh2, body2, sink, model, req, acc);
                                noteConv(p, req, shape, cross ? "重发通过（国际版身份）" : "重发通过", null);
                                return;
                            } catch (Throttled t2) {
                                err = t2;
                            }
                        }
                        if (!"channel".equals(err.kind) && !"channel_soft".equals(err.kind)) {
                            if (shape > 0 || cross) {
                                noteConv(p, req, shape, cross ? "国际版身份仍失败" : "仍失败", err);
                            }
                            throw err;
                        }
                        if ("channel_soft".equals(err.kind)) {
                            /* 请求级风控：账号本身没问题，不写"试到底"标记，也别 6 小时回避。
                               交给 Router 短暂避，下一个请求照常尝试。 */
                            channelFail = err;
                            noteConv(p, req, shape, "请求级风控仍未放行", err);
                            throw err;
                        }
                        if (!looksLikeConvIdError(err)) {
                            if (shape > 0 || cross) {
                                noteConv(p, req, shape, cross ? "国际版身份仍失败" : "仍失败", err);
                            }
                            throw err;
                        }
                        channelFail = err;
                        noteConv(p, req, shape, cross ? "国际版身份被拒" : "被拒", err);
                        /* 4.36.6：渠道校验是**账号级**判定，不随 body/UA 形状变（4.31.1 两轮
                           实验里 5 次回包 requestId 恒定就是证据）。形状已经在历史上被
                           驳倒过，就不必为了"再确认一次"每次白打 5 次 400：
                           本进程内只要记住这个号，直接跳到下一轮/下一个账号。 */
                        if (CONV_HOPELESS_SHAPES_KNOWN) {
                            if (shapes > 1) rememberHopeless(p.name, true);
                            throw err;
                        }
                    } catch (Exception e) {
                        if (shape > 0 || cross) {
                            noteConv(p, req, shape, cross ? "国际版身份仍失败" : "仍失败", e);
                        }
                        throw e;
                    }
                }
                if (shapes > 1) rememberHopeless(p.name, true);
                if (pass < passes - 1) {
                    Log.i("workbuddy 渠道校验：" + p.name + " 账号区域身份不通过，改按国际版身份重试一次");
                    if (req != null && req.ephemeral) {
                        Detect.file("# 渠道校验\t" + p.name + "\t国际版身份重发");
                    }
                } else if (passes > 1) {
                    rememberHopeless(p.name, false);
                    Log.w("workbuddy 渠道校验：" + p.name
                            + " 国际版身份也过不去，本进程内不再尝试（只按账号自己的区域身份发）");
                }
            }
            if (channelFail != null) throw channelFail;
        }
    }

    /**
     * 真正把一次请求发出去：解析流、累计工具调用、把文本/思考/工具调用交给 sink。
     *
     * 从 {@code chat()} 里拆出来（4.36.x），因为 4.36 起 {@code chat()} 要跑两轮
     * （账号自己的区域身份 → 国际版身份），把发送逻辑抽成方法才能保证"每一轮都重新构造
     * body 与头"，不会复用被改过的对象。行为与原内联版本逐行一致。
     */
    static void send(String endpoint, Map<String, String> headers, JSONObject body,
                      Proto.Sink sink, String model, Proto.Req req, Account acc) throws Exception {
        final TreeMap<Integer, JSONObject> toolAcc = new TreeMap<>();
        final Proto.Usage usage = new Proto.Usage();
        final String[] finish = {""};
        final boolean[] idReported = {false};

        Upstreams.postStream(endpoint, headers, body, line -> {
            String t = line == null ? "" : line.trim();
            if (t.isEmpty() || t.startsWith(":")) return; // heartbeat comments
            if (!t.startsWith("data:")) return;
            String data = t.substring(5).trim();
            if (data.isEmpty() || "[DONE]".equals(data)) return;
            JSONObject chunk;
            try {
                chunk = new JSONObject(data);
            } catch (Exception e) {
                return;
            }
            /* 4.36.0：把上游的 requestId 报上去。
               业务错误（尤其 11128 渠道校验）整条都挂在这个 id 上，
               以前只进日志、重启即失，排查时每次都得重新抓包。 */
            if (!idReported[0]) {
                String rid = Proto.optString(chunk, "requestId", "");
                if (rid.isEmpty()) rid = Proto.optString(chunk, "request_id", "");
                if (rid.isEmpty()) rid = Proto.optString(chunk, "id", "");
                if (!rid.isEmpty()) {
                    idReported[0] = true;
                    sink.responseId(rid);
                }
            }
            if (chunk.has("code") && !chunk.has("choices")) {
                int code = chunk.optInt("code", -1);
                if (code != 0) {
                    String msg = Proto.optString(chunk, "msg", "upstream business error");
                    JSONObject display = chunk.optJSONObject("displayMsg");
                    String zh = display != null ? Proto.optString(display, "zh", "") : "";
                    String detail = "workbuddy 拒绝：" + code + " " + msg + (zh.isEmpty() ? "" : " / " + zh);
                    /* 上游用业务码（HTTP 200）表达的限流/额度/鉴权要按退避处理，
                       否则连错 3 次就把健康账号打进熔断——那是我们自己的误伤。 */
                    String kind = bizKind(code, msg);
                    if (kind != null) {
                        /* 渠道校验不是"过一会儿再来"：上游看的是账号/会话状态，
                           所以 HTTP 码按 403（不属于限流）报，kind 仍然是 channel，
                           Router 会给固定长回避并提示换号。 */
                        int httpCode = "quota".equals(kind) ? 402
                                : ("channel".equals(kind) ? 403 : 429);
                        throw new Throttled(httpCode, Http.defaultBackoffMs(httpCode), kind, detail);
                    }
                    throw new IllegalStateException(detail);
                }
            }
            JSONObject u = chunk.optJSONObject("usage");
            if (u != null) {
                Upstreams.applyUsage(usage, u, "prompt_tokens", "completion_tokens", "cached_tokens");
                JSONObject details = u.optJSONObject("prompt_tokens_details");
                if (details != null) {
                    Integer c = Proto.optInt(details, "cached_tokens");
                    if (c != null) usage.cached = c;
                }
            }
            JSONArray choices = chunk.optJSONArray("choices");
            if (choices == null || choices.length() == 0) return;
            JSONObject c0 = choices.optJSONObject(0);
            if (c0 == null) return;
            JSONObject d = c0.optJSONObject("delta");
            if (d != null) {
                String content = Proto.optString(d, "content", "");
                if (!content.isEmpty()) sink.text(content);
                String rc = Proto.optString(d, "reasoning_content", Proto.optString(d, "reasoning", ""));
                if (!rc.isEmpty()) sink.thinking(rc);
                JSONArray tcs = d.optJSONArray("tool_calls");
                if (tcs != null && tcs.length() > 0) {
                    for (int i = 0; i < tcs.length(); i++) {
                        JSONObject tc = tcs.optJSONObject(i);
                        if (tc == null) continue;
                        Integer idxObj = Proto.optInt(tc, "index");
                        int idx = idxObj == null ? 0 : idxObj.intValue();
                        JSONObject acc2 = toolAcc.get(idx);
                        if (acc2 == null) {
                            acc2 = new JSONObject();
                            acc2.put("id", "");
                            acc2.put("name", "");
                            acc2.put("args", "");
                            toolAcc.put(idx, acc2);
                        }
                        String id = Proto.optString(tc, "id", "");
                        if (!id.isEmpty()) acc2.put("id", id);
                        JSONObject fn = tc.optJSONObject("function");
                        if (fn != null) {
                            String name = Proto.optString(fn, "name", "");
                            if (!name.isEmpty()) acc2.put("name", name);
                            String args = Proto.optString(fn, "arguments", "");
                            if (!args.isEmpty()) acc2.put("args", Proto.optString(acc2, "args", "") + args);
                        }
                    }
                }
            }
            String fr = Proto.optString(c0, "finish_reason", "");
            if (!fr.isEmpty() && !"null".equals(fr)) finish[0] = fr;
        });

        // 诊断：把即将转发给客户端的每个工具调用打出来。
        // 目的：确认"客户端收到的 tool_call id"和"它下一轮带回来的 tool_call_id"
        // 是不是同一个——对不上，结果就会被当成孤立消息丢掉。
        for (JSONObject t : toolAcc.values()) {
            String id = Proto.optString(t, "id", "");
            if (id.isEmpty()) {
                id = "call_" + Math.abs(System.nanoTime() % 1000000);
            }
            String name = Proto.optString(t, "name", "");
            if (name.isEmpty()) continue;
            sink.toolCall(id, name, Proto.optString(t, "args", "{}"));
        }
        sink.finish(Proto.canonicalStop(finish[0], !toolAcc.isEmpty()), usage);
    }

    /* ---------------------------------------------------------- model list */

    static List<String> fetchModels(Config.Provider p) throws Exception {
        Account acc = ensureFresh(p);
        String base = endpointFor(p, acc);
        String body = Upstreams.getWithHeaders(base + "/v3/config", headers(acc, base, p.apiKey));
        JSONObject o = new JSONObject(body);
        int code = o.optInt("code", 0);
        if (code != 0) throw new IllegalStateException("workbuddy /v3/config 返回 code=" + code);
        JSONObject data = o.optJSONObject("data");
        List<String> out = new ArrayList<>();
        if (data != null) {
            JSONArray agents = data.optJSONArray("agents");
            if (agents != null) {
                for (int i = 0; i < agents.length(); i++) {
                    JSONObject agent = agents.optJSONObject(i);
                    if (agent == null) continue;
                    JSONArray models = agent.optJSONArray("models");
                    if (models == null) continue;
                    for (int k = 0; k < models.length(); k++) {
                        String m = String.valueOf(models.opt(k));
                        if (!m.isEmpty() && !out.contains(m)) out.add(m);
                    }
                }
            }
            /* 记下上游自报的 endpoint（只认同 realm 的 host，防止一个坏配置把流量带到别处）。 */
            String reported = Upstreams.trimSlash(Proto.optString(data, "endpoint", ""));
            if (!reported.isEmpty()) {
                boolean sameRealm = REALM_GLOBAL.equalsIgnoreCase(acc.realm)
                        ? reported.contains("codebuddy.ai") || reported.contains("workbuddy.ai")
                        : reported.contains(".cn") || reported.contains("tencent.com");
                if (sameRealm) {
                    String prev = ENDPOINT_CACHE.put(p.name, reported);
                    if (prev == null || !prev.equals(reported)) {
                        Log.i("workbuddy 基址跟随上游 config.endpoint：" + p.name + " → " + reported);
                    }
                } else {
                    Log.w("workbuddy config.endpoint 与 realm 不匹配，忽略：" + reported);
                }
            }
            JSONArray models = data.optJSONArray("models");
            if (models != null) {
                for (int k = 0; k < models.length(); k++) {
                    Object item = models.opt(k);
                    String m = item instanceof JSONObject
                            ? Proto.optString((JSONObject) item, "id", Proto.optString((JSONObject) item, "name", ""))
                            : String.valueOf(item);
                    if (!m.isEmpty() && !out.contains(m)) out.add(m);
                }
            }
        }
        // 实测：CLI 身份换来的"更全目录"里大部分条目账号无权调用（11102），探活后几乎全被隐藏，
        // 所以这里只认真正接通的那批（data.agents[].models / data.models），不再整份扫。
        if (out.isEmpty()) throw new IllegalStateException("workbuddy 未返回模型列表");
        return out;
    }

    /** Walks every nested "models" array and merges any model name it finds. */
    static void collectAllModels(Object node, List<String> out, int depth) {
        if (node == null || depth > 6) return;
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            Object models = o.opt("models");
            if (models instanceof JSONArray) {
                JSONArray arr = (JSONArray) models;
                for (int i = 0; i < arr.length(); i++) {
                    Object item = arr.opt(i);
                    String m = null;
                    if (item instanceof JSONObject) {
                        JSONObject mo = (JSONObject) item;
                        m = Proto.optString(mo, "model_name", Proto.optString(mo, "name",
                                Proto.optString(mo, "id", "")));
                    } else if (item instanceof String) {
                        m = (String) item;
                    }
                    if (m != null && !m.isEmpty() && !out.contains(m)) out.add(m);
                }
            }
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String key = it.next();
                if ("models".equals(key)) continue;
                collectAllModels(o.opt(key), out, depth + 1);
            }
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) collectAllModels(arr.opt(i), out, depth + 1);
        }
    }
}
