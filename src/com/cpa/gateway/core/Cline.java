package com.cpa.gateway.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Cline 渠道。
 *
 * 登录复刻 Cline CLI 的 WorkOS 设备码流程（协议来自 cline2api 的 auth.go / config.go）：
 *   ① POST https://api.workos.com/user_management/authorize/device  {client_id}
 *      → device_code / user_code / verification_uri_complete（expires_in 300，interval 5）
 *   ② 用户在 https://authkit.cline.bot/device?user_code=XXXX-XXXX 确认
 *   ③ POST https://api.workos.com/user_management/authenticate
 *      form: grant_type=urn:ietf:params:oauth:grant-type:device_code&device_code=…&client_id=…
 *      → WorkOS 的 access_token / refresh_token
 *   ④ POST https://api.cline.bot/api/v1/auth/register  {accessToken, refreshToken} → Cline 自己的 token
 *   ⑤ 续期 POST /auth/refresh {refreshToken, grantType:"refresh_token"}
 *
 * 上游调用要点：Authorization 的 Bearer **必须带 `workos:` 前缀**（裸 token 一律 401）；
 * 非流式响应外面包着 {"data":{…},"success":true}，要拆一层；指纹头按当前版本带。
 */
public final class Cline {

    public static final String WORKOS = "https://api.workos.com";
    public static final String CLIENT_ID = "client_01K3A541FN8TA3EPPHTD2325AR";
    public static final String BASE = "https://api.cline.bot/api/v1";
    public static final String UA = "Cline/3.0.65";
    public static final String CORE_VERSION = "0.0.86";
    private static final String WORKOS_PREFIX = "workos:";

    private Cline() {
    }

    /* ------------------------------------------------------------ device flow */

    public static JSONObject deviceStart() throws Exception {
        Map<String, String> h = new HashMap<>();
        h.put("Content-Type", "application/x-www-form-urlencoded");
        byte[] body = ("client_id=" + CLIENT_ID).getBytes("UTF-8");
        Http.Resp r = Http.call("POST", WORKOS + "/user_management/authorize/device", h, body,
                "application/x-www-form-urlencoded", 10000, 20000, false);
        if (r.code >= 400) throw new IllegalStateException("WorkOS " + r.code + " " + Upstreams.shortenPublic(r.body));
        return new JSONObject(r.body);
    }

    /** 轮询换 WorkOS token；未确认时返回 null。 */
    public static JSONObject devicePoll(String deviceCode) throws Exception {
        Map<String, String> h = new HashMap<>();
        h.put("Content-Type", "application/x-www-form-urlencoded");
        String form = "grant_type=" + enc("urn:ietf:params:oauth:grant-type:device_code")
                + "&device_code=" + enc(deviceCode) + "&client_id=" + enc(CLIENT_ID);
        Http.Resp r = Http.call("POST", WORKOS + "/user_management/authenticate", h,
                form.getBytes("UTF-8"), "application/x-www-form-urlencoded", 10000, 20000, false);
        if (r.code >= 400) {
            // authorization_pending / slow_down 这类属于"还没好"，不算错
            String b = r.body == null ? "" : r.body;
            if (b.contains("authorization_pending") || b.contains("slow_down")) return null;
            throw new IllegalStateException("WorkOS " + r.code + " " + Upstreams.shortenPublic(b));
        }
        return new JSONObject(r.body);
    }

    /** 用 WorkOS token 换 Cline 自己的 token。 */
    public static JSONObject register(String workosAccess, String workosRefresh) throws Exception {
        JSONObject body = new JSONObject();
        body.put("accessToken", workosAccess);
        body.put("refreshToken", workosRefresh);
        Map<String, String> h = new HashMap<>();
        h.put("Content-Type", "application/json");
        h.put("Accept", "application/json");
        h.put("User-Agent", UA);
        h.put("X-CORE-VERSION", CORE_VERSION);
        Http.Resp r = Http.call("POST", BASE + "/auth/register", h,
                body.toString().getBytes("UTF-8"), "application/json", 10000, 30000, false);
        if (r.code >= 400) throw new IllegalStateException("register " + r.code + " " + Upstreams.shortenPublic(r.body));
        JSONObject o = new JSONObject(r.body);
        JSONObject d = o.optJSONObject("data");
        return d == null ? o : d;
    }

    public static JSONObject refresh(String refreshToken) throws Exception {
        JSONObject body = new JSONObject();
        body.put("refreshToken", refreshToken);
        body.put("grantType", "refresh_token");
        Map<String, String> h = new HashMap<>();
        h.put("Content-Type", "application/json");
        h.put("Accept", "application/json");
        h.put("User-Agent", UA);
        h.put("X-CORE-VERSION", CORE_VERSION);
        Http.Resp r = Http.call("POST", BASE + "/auth/refresh", h,
                body.toString().getBytes("UTF-8"), "application/json", 10000, 30000, false);
        if (r.code >= 400) throw new IllegalStateException("refresh " + r.code + " " + Upstreams.shortenPublic(r.body));
        JSONObject o = new JSONObject(r.body);
        JSONObject d = o.optJSONObject("data");
        return d == null ? o : d;
    }

    /**
     * 用 refresh token 换一份新的 access，并回存到 provider.credentials。
     *
     * 为什么需要：Cline 这边以前定义了 refresh() 却**从来没接过**——token 一失效，
     * 请求就一路 401 出去，账号只能靠用户重新登录。这里把续期接上。
     *
     * 返回新的 Account；refresh 为空或续期失败时返回原 Account（让上层照旧报错，
     * 不把"续期失败"伪装成成功）。
     */
    public static Account ensureFresh(Config.Provider p) {
        synchronized (FreshLocks.forKey(p == null ? "cline" : p.name)) {
            return ensureFreshInner(p);
        }
    }

    private static Account ensureFreshInner(Config.Provider p) {
        Account acc = Account.fromJson(p.credentials);
        if (acc.refresh == null || acc.refresh.isEmpty()) return acc;   // 没有续期凭据，只能等重新登录
        String key = p.id == null ? p.name : p.id;
        long now = System.currentTimeMillis();
        Long last = LAST_REFRESH.get(key);
        // 冷却：同一条通道 60 秒内只续一次，避免上游持续 401 时打风暴
        if (last != null && now - last.longValue() < REFRESH_COOLDOWN_MS) return acc;
        LAST_REFRESH.put(key, now);
        try {
            JSONObject d = refresh(acc.refresh);
            String na = Proto.optString(d, "accessToken", "");
            if (na.isEmpty()) na = Proto.optString(d, "access_token", "");
            String nr = Proto.optString(d, "refreshToken", "");
            if (nr.isEmpty()) nr = Proto.optString(d, "refresh_token", "");
            if (na.isEmpty()) {
                Log.w("cline 续期没拿到新 access");
                LAST_REFRESH.remove(key);
                return acc;
            }
            Account fresh = new Account();
            fresh.access = na;
            fresh.refresh = nr.isEmpty() ? acc.refresh : nr;
            fresh.email = acc.email;
            p.credentials = fresh.toJson().toString();
            Gateway.persistSoon();
            Log.i("cline token 已续期并回存");
            return fresh;
        } catch (Throwable t) {
            Log.w("cline 续期失败：" + t);
            LAST_REFRESH.remove(key);
            return acc;
        }
    }

    /** 续期冷却（毫秒）。 */
    private static final long REFRESH_COOLDOWN_MS = 60_000L;
    private static final java.util.Map<String, Long> LAST_REFRESH =
            new java.util.concurrent.ConcurrentHashMap<>();

    /* ---------------------------------------------------------------- creds */

    public static final class Account {
        public String access = "";
        public String refresh = "";
        public String email = "";

        public boolean usable() {
            return access != null && !access.isEmpty();
        }

        public static Account fromJson(String json) {
            Account a = new Account();
            if (json == null || json.isEmpty()) return a;
            try {
                JSONObject o = new JSONObject(json);
                a.access = Proto.optString(o, "access", "");
                a.refresh = Proto.optString(o, "refresh", "");
                a.email = Proto.optString(o, "email", "");
            } catch (Throwable ignored) {
            }
            return a;
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("access", access);
                o.put("refresh", refresh);
                o.put("email", email);
            } catch (Throwable ignored) {
            }
            return o;
        }
    }

    static String withWorkos(String token) {
        if (token == null) return "";
        String t = token.trim();
        if (t.toLowerCase().startsWith(WORKOS_PREFIX)) return t;
        return WORKOS_PREFIX + t;
    }

    static Map<String, String> headers(Account acc, boolean stream) {
        Map<String, String> h = new HashMap<>();
        h.put("Content-Type", "application/json");
        h.put("Accept", stream ? "text/event-stream" : "application/json");
        h.put("User-Agent", UA);
        h.put("X-CORE-VERSION", CORE_VERSION);
        h.put("Authorization", "Bearer " + withWorkos(acc.access));
        return h;
    }

    /* -------------------------------------------------------------- upstream */

    public static final class Up implements Proto.Upstream {
        private final Config.Provider p;

        public Up(Config.Provider p) {
            this.p = p;
        }

        @Override
        public void chat(String model, Proto.Req req, Proto.Sink sink) throws Exception {
            Account acc = Account.fromJson(p.credentials);
            JSONObject body = Upstreams.openAiBody(model, req, false);
            Http.Resp r = Http.call("POST", BASE + "/chat/completions", headers(acc, false),
                    body.toString().getBytes("UTF-8"), "application/json", 10000, 120000, false);
            // 401/403 = 凭证可能刚好过期：续期一次再重试（只重试一次，不循环）
            if (r.code == 401 || r.code == 403) {
                Account fresh = ensureFresh(p);
                if (fresh.usable() && !fresh.access.equals(acc.access)) {
                    Log.i("cline 遇 " + r.code + "，续期后重试一次");
                    r = Http.call("POST", BASE + "/chat/completions", headers(fresh, false),
                            body.toString().getBytes("UTF-8"), "application/json", 10000, 120000, false);
                }
            }
            if (r.code >= 400) throw Http.upstreamError(r.code, r.body);
            JSONObject o = new JSONObject(r.body);
            // 外层包着 {"data":{…},"success":true}
            JSONObject inner = o.optJSONObject("data");
            if (inner != null) o = inner;
            Proto.Usage usage = new Proto.Usage();
            Upstreams.applyUsage(usage, o.optJSONObject("usage"),
                    "prompt_tokens", "completion_tokens", "cached_tokens");
            String finishReason = "stop";
            boolean anyTool = false;
            try {
                JSONArray ch = o.optJSONArray("choices");
                if (ch != null && ch.length() > 0) {
                    JSONObject c0 = ch.optJSONObject(0);
                    if (c0 != null) {
                        String fr = Proto.optString(c0, "finish_reason", "");
                        if (!fr.isEmpty() && !"null".equals(fr)) finishReason = fr;
                        JSONObject msg = c0.optJSONObject("message");
                        if (msg != null) {
                            String rc = Proto.optString(msg, "reasoning_content",
                                    Proto.optString(msg, "reasoning", ""));
                            if (!rc.isEmpty()) sink.thinking(rc);
                            String text = Proto.optString(msg, "content", "");
                            if (!text.isEmpty()) sink.text(text);
                            JSONArray tcs = msg.optJSONArray("tool_calls");
                            for (int i = 0; tcs != null && i < tcs.length(); i++) {
                                JSONObject tc = tcs.optJSONObject(i);
                                if (tc == null) continue;
                                String id = Proto.optString(tc, "id",
                                        "call_" + Math.abs(System.nanoTime() % 1000000));
                                JSONObject fn = tc.optJSONObject("function");
                                String name = fn == null ? "" : Proto.optString(fn, "name", "");
                                String rawArgs = fn == null ? "{}" : Proto.optString(fn, "arguments", "{}");
                                String fixed = ToolFix.repairArgs(rawArgs);
                                if (fixed == null) fixed = rawArgs;
                                if (name.isEmpty()) name = ToolFix.nameFromArgs(fixed);
                                anyTool = true;
                                sink.toolCall(id, name, fixed);
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            sink.finish(Proto.canonicalStop(finishReason, anyTool), usage);
        }
    }

    public static List<String> fetchModels() {
        List<String> out = new ArrayList<>();
        try {
            Map<String, String> h = new HashMap<>();
            h.put("Accept", "application/json");
            h.put("User-Agent", UA);
            h.put("X-CORE-VERSION", CORE_VERSION);
            Http.Resp r = Http.call("GET", BASE + "/ai/cline/recommended-models", h, null,
                    "application/json", 8000, 20000, false);
            if (r.code >= 400) return out;
            JSONObject o = new JSONObject(r.body);
            JSONArray arr = o.optJSONArray("data");
            if (arr == null) arr = o.optJSONArray("models");
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject m = arr.optJSONObject(i);
                if (m == null) continue;
                String id = Proto.optString(m, "id", Proto.optString(m, "model", ""));
                if (!id.isEmpty() && !out.contains(id)) out.add(id);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 把登录结果写成一个通道。 */
    public static JSONObject registerProvider(Account acc, List<String> models) throws Exception {
        if (!acc.usable()) throw new IllegalStateException("没有 access token，登录没完成");
        Config cfg = Gateway.config();
        Config.Provider target = null;
        for (Config.Provider p : cfg.providers) {
            if (!Config.TYPE_CLINE.equals(p.type)) continue;
            Account ex = Account.fromJson(p.credentials);
            if (!acc.refresh.isEmpty() && acc.refresh.equals(ex.refresh)) {
                target = p;
                break;
            }
        }
        boolean created = target == null;
        if (created) {
            target = new Config.Provider();
            target.type = Config.TYPE_CLINE;
            target.id = Config.newProviderId("cl");
            target.name = Config.nextAccountName("Cline", target.type);
            target.baseUrl = BASE;
            cfg.providers.add(target);
        }
        target.credentials = acc.toJson().toString();
        target.enabled = true;
        target.lastError = "";
        target.lastErrorAt = 0;
        int before = target.models.size();
        if (models != null && !models.isEmpty()) target.setModels(models);
        Gateway.saveNow(Gateway.context());
        JSONObject out = new JSONObject();
        out.put("provider", target.name);
        out.put("models", target.models.size());
        out.put("added", Math.max(0, target.models.size() - before));
        out.put("created", created);
        return out;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }
}
