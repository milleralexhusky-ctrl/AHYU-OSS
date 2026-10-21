package com.cpa.gateway.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Claude channel backed by a claude.ai account (Pro / Max) instead of an API key.
 *
 * Flow mirrors the official CLI: PKCE authorize on claude.ai, loopback callback on 127.0.0.1:54545,
 * JSON (not form) token exchange against platform.claude.com with Axios-shaped headers, then
 * ordinary /v1/messages calls carrying the OAuth bearer plus anthropic-beta: oauth-2025-04-20.
 */
public final class Claude {

    private Claude() {}

    public static final String AUTH_URL = "https://claude.ai/oauth/authorize";
    public static final String TOKEN_URL = "https://platform.claude.com/v1/oauth/token";
    public static final String CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e";
    public static final String REDIRECT_URI = "http://localhost:54545/callback";
    public static final int CALLBACK_PORT = 54545;
    public static final String SCOPE = "user:profile user:inference user:sessions:claude_code user:mcp_servers user:file_upload";
    public static final String DEFAULT_BASE = "https://api.anthropic.com";
    private static final String USER_AGENT = "claude-cli/2.1.280 (external, cli)";

    public static final class Account {
        public String accessToken = "";
        public String refreshToken = "";
        public long expiresAt = 0L;
        public String accountId = "";
        public String email = "";
        public String orgId = "";
        public String orgName = "";

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
            a.accountId = Proto.optString(o, "account_id", "");
            a.email = Proto.optString(o, "email", "");
            a.orgId = Proto.optString(o, "org_id", "");
            a.orgName = Proto.optString(o, "org_name", "");
            return a;
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("access_token", accessToken);
                o.put("refresh_token", refreshToken);
                o.put("expires_at", expiresAt);
                o.put("account_id", accountId);
                o.put("email", email);
                o.put("org_id", orgId);
                o.put("org_name", orgName);
                o.put("uid", accountId.isEmpty() ? email : accountId);
            } catch (Exception ignored) {
            }
            return o;
        }

        public boolean usable() {
            return !accessToken.isEmpty();
        }
    }

    public static String authorizeUrl(OAuth.Pkce pkce, String state) throws Exception {
        return AUTH_URL
                + "?code=true"
                + "&client_id=" + URLEncoder.encode(CLIENT_ID, "UTF-8")
                + "&response_type=code"
                + "&redirect_uri=" + URLEncoder.encode(REDIRECT_URI, "UTF-8")
                + "&scope=" + URLEncoder.encode(SCOPE, "UTF-8")
                + "&code_challenge=" + URLEncoder.encode(pkce.challenge, "UTF-8")
                + "&code_challenge_method=S256"
                + "&state=" + URLEncoder.encode(state, "UTF-8");
    }

    static Map<String, String> axiosHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("Accept", "application/json, text/plain, */*");
        h.put("Content-Type", "application/json");
        h.put("User-Agent", "axios/1.15.2");
        h.put("Accept-Encoding", "gzip, compress, deflate, br");
        h.put("Connection", "close");
        return h;
    }

    public static Account exchangeCode(String code, String state, OAuth.Pkce pkce) throws Exception {
        // key order matters: it reproduces what the official CLI puts on the wire
        JSONObject body = new JSONObject();
        body.put("grant_type", "authorization_code");
        body.put("code", code);
        body.put("redirect_uri", REDIRECT_URI);
        body.put("client_id", CLIENT_ID);
        body.put("code_verifier", pkce.verifier);
        body.put("state", state);
        String resp = Upstreams.postJson(TOKEN_URL, axiosHeaders(), body);
        return parseTokenResponse(resp);
    }

    static Account parseTokenResponse(String body) throws Exception {
        JSONObject o = new JSONObject(body);
        if (o.has("error")) {
            throw new IllegalStateException("Claude 返回错误：" + Proto.optString(o, "error", "")
                    + " " + Proto.optString(o, "error_description", ""));
        }
        Account a = new Account();
        a.accessToken = Proto.optString(o, "access_token", "");
        a.refreshToken = Proto.optString(o, "refresh_token", "");
        Integer expiresIn = Proto.optInt(o, "expires_in");
        a.expiresAt = System.currentTimeMillis() / 1000 + (expiresIn == null ? 3600 : expiresIn.intValue()) - 60;
        JSONObject account = o.optJSONObject("account");
        if (account != null) {
            a.accountId = Proto.optString(account, "uuid", "");
            a.email = Proto.optString(account, "email_address", Proto.optString(account, "email", ""));
        }
        JSONObject org = o.optJSONObject("organization");
        if (org != null) {
            a.orgId = Proto.optString(org, "uuid", "");
            a.orgName = Proto.optString(org, "name", "");
        }
        if (!a.usable()) throw new IllegalStateException("没拿到 access_token，原始响应：" + Upstreams.shortenPublic(body));
        return a;
    }

    public static Account ensureFresh(Config.Provider p) throws Exception {
        // 原来是 static synchronized（类级锁）：一个账号走网络刷新 token 时，
        // 同 Provider 的其他账号全被堵住。现在按账号加锁，各刷各的。
        synchronized (FreshLocks.of(p)) {
            return ensureFreshInner(p);
        }
    }

    private static Account ensureFreshInner(Config.Provider p) throws Exception {
        Account acc = Account.fromJson(p.credentials);
        if (!acc.usable()) throw new IllegalStateException("Claude 账号未登录，请先点「登录 Claude」");
        long now = System.currentTimeMillis() / 1000;
        if (acc.expiresAt == 0 || now + 300 < acc.expiresAt) return acc;
        if (acc.refreshToken.isEmpty()) throw new IllegalStateException("Claude 凭据过期且没有 refresh_token，请重新登录");
        Log.i("claude token 临期，刷新中");
        JSONObject body = new JSONObject();
        body.put("client_id", CLIENT_ID);
        body.put("grant_type", "refresh_token");
        body.put("refresh_token", acc.refreshToken);
        body.put("scope", SCOPE);
        Account fresh;
        try {
            fresh = parseTokenResponse(Upstreams.postJson(TOKEN_URL, axiosHeaders(), body));
        } catch (Exception e) {
            throw new IllegalStateException("Claude 凭据刷新失败，请重新登录：" + e.getMessage());
        }
        if (fresh.refreshToken.isEmpty()) fresh.refreshToken = acc.refreshToken;
        if (fresh.accountId.isEmpty()) fresh.accountId = acc.accountId;
        if (fresh.email.isEmpty()) fresh.email = acc.email;
        if (fresh.orgId.isEmpty()) fresh.orgId = acc.orgId;
        p.credentials = fresh.toJson().toString();
        Gateway.persistSoon();
        Log.i("claude token 已刷新并回存");
        return fresh;
    }

    static Map<String, String> headers(Account acc) {
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", "Bearer " + acc.accessToken);
        h.put("anthropic-version", "2023-06-01");
        h.put("anthropic-beta", "oauth-2025-04-20");
        h.put("x-app", "cli");
        h.put("User-Agent", USER_AGENT);
        h.put("Accept", "application/json, text/event-stream");
        return h;
    }

    static String baseOf(Config.Provider p) {
        String b = Upstreams.trimSlash(p.baseUrl);
        return b.isEmpty() ? DEFAULT_BASE : b;
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
            Upstreams.anthropicStream(Upstreams.anthropicMessagesUrl(baseOf(p)), headers(acc), body, sink);
        }
    }

    public static List<String> fetchModels(Config.Provider p) throws Exception {
        Account acc = ensureFresh(p);
        String body = Upstreams.getWithHeaders(Upstreams.url(baseOf(p), "v1/models?limit=100"), headers(acc));
        JSONObject o = new JSONObject(body);
        JSONArray data = o.optJSONArray("data");
        List<String> out = new ArrayList<>();
        if (data != null) {
            for (int i = 0; i < data.length(); i++) {
                JSONObject m = data.optJSONObject(i);
                if (m == null) continue;
                String id = Proto.optString(m, "id", "");
                if (!id.isEmpty() && !out.contains(id)) out.add(id);
            }
        }
        if (out.isEmpty()) {
            for (String m : new String[]{"claude-sonnet-5-5", "claude-opus-5-5", "claude-haiku-4-5"}) out.add(m);
        }
        return out;
    }

    public static JSONObject registerProvider(JSONObject accountJson) throws Exception {
        Account acc = Account.fromJson(accountJson);
        if (!acc.usable()) throw new IllegalStateException("没有 access_token，登录没完成");
        Config cfg = Gateway.config();
        Config.Provider target = null;
        for (Config.Provider p : cfg.providers) {
            if (!Config.TYPE_CLAUDE.equals(p.type)) continue;
            Account ex = Account.fromJson(p.credentials);
            if (!acc.accountId.isEmpty() && acc.accountId.equals(ex.accountId)) {
                target = p;
                break;
            }
        }
        boolean created = target == null;
        if (created) {
            target = new Config.Provider();
            target.type = Config.TYPE_CLAUDE;
            target.id = Config.newProviderId("cd");
            String who = acc.email.isEmpty() ? (acc.accountId.length() > 8 ? acc.accountId.substring(0, 8) : acc.accountId) : acc.email;
            target.name = Config.nextAccountName("Claude", target.type);
            target.baseUrl = DEFAULT_BASE;
            cfg.providers.add(target);
        }
        target.credentials = acc.toJson().toString();
        target.enabled = true;
        int modelCount = target.models.size();
        try {
            List<String> models = fetchModels(target);
            target.setModels(models);
            modelCount = target.models.size();
        } catch (Exception e) {
            Log.w("拉取 Claude 模型失败，用默认清单：" + e);
            if (target.models.isEmpty()) {
                for (String m : new String[]{"claude-sonnet-5-5", "claude-opus-5-5", "claude-haiku-4-5"}) target.models.add(m);
                modelCount = target.models.size();
            }
        }
        Gateway.saveNow(Gateway.context());
        JSONObject out = new JSONObject();
        out.put("provider", target.name);
        out.put("created", created);
        out.put("models", modelCount);
        return out;
    }
}
