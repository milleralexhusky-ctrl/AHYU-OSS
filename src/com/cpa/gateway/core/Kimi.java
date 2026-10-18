package com.cpa.gateway.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Kimi Code channel: device-code login (no callback server needed) plus the coding API, which
 * speaks the Claude message shape.
 *
 * Verified against the live service: POST auth.kimi.com/api/oauth/device_authorization with only
 * client_id returns {device_code, user_code, verification_uri, interval, expires_in}; sending a
 * scope is rejected with invalid_scope.
 */
public final class Kimi {

    private Kimi() {}

    static final String AUTH_HOST = "https://auth.kimi.com";
    static final String EP_DEVICE = "/api/oauth/device_authorization";
    static final String EP_TOKEN = "/api/oauth/token";
    static final String CLIENT_ID = "17e5f671-d194-4dfb-9706-5516cb48c098";
    static final String DEFAULT_API_BASE = "https://api.kimi.com/coding";

    public static final class DeviceCode {
        public String deviceCode = "";
        public String userCode = "";
        public String verificationUri = "";
        public String verificationUriComplete = "";
        public int interval = 5;
        public int expiresIn = 1800;
    }

    public static final class Account {
        public String accessToken = "";
        public String refreshToken = "";
        public long expiresAt = 0L;
        public String email = "";

        public static Account fromJson(String json) {
            try {
                return fromJson(new JSONObject(json));
            } catch (Exception e) {
                return new Account();
            }
        }

        public static Account fromJson(JSONObject o) {
            Account a = new Account();
            if (o == null) return a;
            a.accessToken = Proto.optString(o, "access_token", "");
            a.refreshToken = Proto.optString(o, "refresh_token", "");
            Integer exp = Proto.optInt(o, "expires_at");
            a.expiresAt = exp == null ? 0L : exp.longValue();
            a.email = Proto.optString(o, "email", "");
            return a;
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("access_token", accessToken);
                o.put("refresh_token", refreshToken);
                o.put("expires_at", expiresAt);
                o.put("email", email);
                o.put("uid", email.isEmpty() ? "kimi" : email);
            } catch (Exception ignored) {
            }
            return o;
        }

        public boolean usable() {
            return !accessToken.isEmpty();
        }
    }

    private static Map<String, String> formHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("Content-Type", "application/x-www-form-urlencoded");
        h.put("Accept", "application/json");
        h.put("User-Agent", "kimi-cli");
        return h;
    }

    /** Step 1: ask for a code the user types on kimi.com. */
    public static DeviceCode deviceAuthorize() throws Exception {
        String form = "client_id=" + enc(CLIENT_ID);
        String body = Upstreams.postForm(AUTH_HOST + EP_DEVICE, formHeaders(), form);
        JSONObject o = new JSONObject(body);
        DeviceCode d = new DeviceCode();
        d.deviceCode = Proto.optString(o, "device_code", "");
        d.userCode = Proto.optString(o, "user_code", "");
        d.verificationUri = Proto.optString(o, "verification_uri", "https://www.kimi.com/code/authorize_device");
        d.verificationUriComplete = Proto.optString(o, "verification_uri_complete", d.verificationUri);
        Integer interval = Proto.optInt(o, "interval");
        d.interval = interval == null ? 5 : Math.max(2, interval.intValue());
        Integer expires = Proto.optInt(o, "expires_in");
        d.expiresIn = expires == null ? 1800 : expires.intValue();
        if (d.deviceCode.isEmpty() || d.userCode.isEmpty()) {
            throw new IllegalStateException("Kimi 没有返回设备码：" + Upstreams.shortenPublic(body));
        }
        Log.i("kimi 设备码已获取：" + d.userCode);
        return d;
    }

    /** Step 2: poll until the user approves. Returns null while still pending. */
    public static Account pollToken(DeviceCode d) throws Exception {
        String form = "client_id=" + enc(CLIENT_ID)
                + "&device_code=" + enc(d.deviceCode)
                + "&grant_type=" + enc("urn:ietf:params:oauth:grant-type:device_code");
        String body = Upstreams.postFormAllowingError(AUTH_HOST + EP_TOKEN, formHeaders(), form);
        JSONObject o = new JSONObject(body);
        if (o.has("error")) {
            String err = Proto.optString(o, "error", "");
            if ("authorization_pending".equals(err) || "slow_down".equals(err)) return null;
            throw new IllegalStateException("Kimi 授权失败：" + err + " " + Proto.optString(o, "error_description", ""));
        }
        Account a = new Account();
        a.accessToken = Proto.optString(o, "access_token", "");
        a.refreshToken = Proto.optString(o, "refresh_token", "");
        Integer expires = Proto.optInt(o, "expires_in");
        a.expiresAt = System.currentTimeMillis() / 1000 + (expires == null ? 3600 : expires.intValue()) - 60;
        if (!a.usable()) throw new IllegalStateException("Kimi 没有返回 access_token：" + Upstreams.shortenPublic(body));
        return a;
    }

    private static Map<String, Long> LAST_REFRESH = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long REFRESH_COOLDOWN_SECONDS = 120L;

    /** Single refresh entry point with a cooldown, same discipline as the other channels. */
    public static Account ensureFresh(Config.Provider p) throws Exception {
        // 原来是 static synchronized（类级锁）：一个账号走网络刷新 token 时，
        // 同 Provider 的其他账号全被堵住。现在按账号加锁，各刷各的。
        synchronized (FreshLocks.forKey(p.name)) {
            return ensureFreshInner(p);
        }
    }

    private static Account ensureFreshInner(Config.Provider p) throws Exception {
        Account acc = Account.fromJson(p.credentials);
        if (!acc.usable()) throw new IllegalStateException("Kimi 账号未登录，请先点「Kimi 账号」");
        long now = System.currentTimeMillis() / 1000;
        if (acc.expiresAt > 0 && now + 300 < acc.expiresAt) return acc;
        String key = p.id == null ? p.name : p.id;
        Long last = LAST_REFRESH.get(key);
        if (last != null && now - last.longValue() < REFRESH_COOLDOWN_SECONDS) return acc;
        if (acc.refreshToken.isEmpty()) throw new IllegalStateException("Kimi 凭据已过期且没有 refresh_token，请重新登录");
        LAST_REFRESH.put(key, now);
        String form = "client_id=" + enc(CLIENT_ID)
                + "&grant_type=refresh_token"
                + "&refresh_token=" + enc(acc.refreshToken);
        String body = Upstreams.postForm(AUTH_HOST + EP_TOKEN, formHeaders(), form);
        JSONObject o = new JSONObject(body);
        if (o.has("error")) {
            LAST_REFRESH.remove(key);
            throw new IllegalStateException("Kimi 刷新失败：" + Proto.optString(o, "error", ""));
        }
        Account fresh = Account.fromJson(acc.toJson());
        String token = Proto.optString(o, "access_token", "");
        if (!token.isEmpty()) fresh.accessToken = token;
        String rotated = Proto.optString(o, "refresh_token", "");
        if (!rotated.isEmpty()) fresh.refreshToken = rotated;
        Integer expires = Proto.optInt(o, "expires_in");
        fresh.expiresAt = now + (expires == null ? 3600 : expires.intValue()) - 60;
        if (fresh.expiresAt <= now + 60) fresh.expiresAt = now + 3600;
        p.credentials = fresh.toJson().toString();
        Gateway.persistSoon();
        Log.i("kimi token 已刷新并回存");
        return fresh;
    }

    static String baseOf(Config.Provider p) {
        return p.baseUrl == null || p.baseUrl.isEmpty() ? DEFAULT_API_BASE : Upstreams.trimSlash(p.baseUrl);
    }

    static Map<String, String> headers(Account acc) {
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", "Bearer " + acc.accessToken);
        h.put("Content-Type", "application/json");
        h.put("Accept", "text/event-stream");
        h.put("anthropic-beta", "oauth-2025-04-20");
        h.put("User-Agent", "kimi-cli");
        return h;
    }

    static final class Up implements Proto.Upstream {
        private final Config.Provider p;

        Up(Config.Provider p) {
            this.p = p;
        }

        @Override
        public void chat(String model, Proto.Req req, Proto.Sink sink) throws Exception {
            Account acc = ensureFresh(p);
            JSONObject body = Upstreams.anthropicBody(model, req);
            Upstreams.anthropicStream(baseOf(p) + "/v1/messages", headers(acc), body, sink);
        }
    }

    public static List<String> fetchModels(Config.Provider p) throws Exception {
        Account acc = ensureFresh(p);
        List<String> out = new ArrayList<>();
        for (String path : new String[]{"/v1/models", "/models"}) {
            try {
                String body = Upstreams.getWithHeaders(baseOf(p) + path, headers(acc));
                JSONArray arr = new JSONObject(body).optJSONArray("data");
                for (int i = 0; arr != null && i < arr.length(); i++) {
                    Object item = arr.opt(i);
                    String id = item instanceof JSONObject ? Proto.optString((JSONObject) item, "id", "")
                            : String.valueOf(item);
                    if (!id.isEmpty() && !out.contains(id)) out.add(id);
                }
                if (!out.isEmpty()) return out;
            } catch (Exception e) {
                Log.w("kimi 取模型失败 " + path + "：" + e.getMessage());
            }
        }
        return out;
    }

    public static JSONObject registerProvider(JSONObject accountJson) throws Exception {
        Account acc = Account.fromJson(accountJson);
        if (!acc.usable()) throw new IllegalStateException("没有 access_token，登录没完成");
        Config cfg = Gateway.config();
        Config.Provider target = null;
        for (Config.Provider p : cfg.providers) {
            if (!Config.TYPE_KIMI.equals(p.type)) continue;
            Account ex = Account.fromJson(p.credentials);
            if (!acc.refreshToken.isEmpty() && acc.refreshToken.equals(ex.refreshToken)) {
                target = p;
                break;
            }
        }
        boolean created = target == null;
        if (created) {
            target = new Config.Provider();
            target.type = Config.TYPE_KIMI;
            target.id = Config.newProviderId("km");
            target.name = Config.nextAccountName("Kimi", target.type);
            target.baseUrl = DEFAULT_API_BASE;
            cfg.providers.add(target);
        }
        target.credentials = acc.toJson().toString();
        target.enabled = true;
        target.lastError = "";
        target.lastErrorAt = 0;
        int modelCount = target.models.size();
        try {
            List<String> models = fetchModels(target);
            if (!models.isEmpty()) {
                target.setModels(models);
                modelCount = target.models.size();
            }
        } catch (Exception e) {
            Log.w("拉取 Kimi 模型失败：" + e);
        }
        if (target.models.isEmpty()) {
            for (String m : new String[]{"kimi-k2-turbo-preview", "kimi-k2-0905-preview", "kimi-latest"}) {
                target.models.add(m);
            }
            modelCount = target.models.size();
        }
        Gateway.saveNow(Gateway.context());
        JSONObject out = new JSONObject();
        out.put("provider", target.name);
        out.put("created", created);
        out.put("models", modelCount);
        return out;
    }

    static String enc(String v) {
        try {
            return java.net.URLEncoder.encode(v, "UTF-8");
        } catch (Exception e) {
            return v;
        }
    }
}
