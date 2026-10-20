package com.cpa.gateway.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * xAI（Grok）账号通道：设备码登录（RFC 8628）+ refresh_token 刷新 + 转发到 grok-cli 的推理端点。
 *
 * 参数是在本机实测过的（2026-10-12，直接 curl auth.x.ai 拿到了 device_code）：
 *   device code : POST https://auth.x.ai/oauth2/device/code
 *   token       : POST https://auth.x.ai/oauth2/token
 *   client_id   : b1a00492-073a-47ea-816f-4c329264a828（grok-cli 的公开 client）
 *   scope       : openid profile email offline_access grok-cli:access api:access
 *   返回的 verification_uri 是 https://accounts.x.ai/oauth2/device（用它返回的，别写死）
 *
 * 推理不走 api.x.ai，而是 grok-cli 的代理端点，且要带身份头（下面 HEADERS 那几条）。
 * 那几条头里的版本号/标识是按公开资料填的默认值，如果上游拒绝，改这两个常量即可。
 */
public final class Xai {

    static final String AUTH_HOST = "https://auth.x.ai";
    static final String EP_DEVICE = "/oauth2/device/code";
    static final String EP_TOKEN = "/oauth2/token";
    static final String CLIENT_ID = "b1a00492-073a-47ea-816f-4c329264a828";
    static final String SCOPES = "openid profile email offline_access grok-cli:access api:access";
    static final String DEFAULT_API_BASE = "https://cli-chat-proxy.grok.com/v1";

    /**
     * grok-cli 的身份头。数值是在本机实测出来的（2026-10-12）：
     * 带这套头打 cli-chat-proxy 会返回
     *   {"error":"Invalid or expired credentials (auth_kind=bearer,
     *     x_xai_token_auth=xai-grok-cli, upstream=PermissionDenied, reason=no auth context)"}
     * —— 说明头被上游识别并透传了，401 只是因为 token 是假的。
     * x-grok-client-version 是强制校验头（缺失/过低会 426），实际客户端版本从
     * ~/.grok/version.json 读，这里跟着官方 grok-cli 的版本走。
     */
    /**
     * 版本号取"较新"的值：代理那边的校验是**下限**（min gate），写旧了会被判版本过旧
     * （协议文档原话："a shipped constant works only until ..."）。实测见过的取值有
     * 0.2.93 / 0.2.99 / 0.2.101 / 1.0.4 / 1.0.13，这里取观察到的最新值。
     * 真要长期稳定，靠账号凭据里的 client_version 覆盖（见 clientVersion()）。
     */
    static final String CLIENT_VERSION = "1.0.13";
    static final String CLIENT_IDENTIFIER = "grok-shell";
    /** 这个头是"客户端身份标识"而不是 token：固定字符串，实测被上游回显。 */
    static final String TOKEN_AUTH_MARK = "xai-grok-cli";


    /* ------------------------------------------------------- web (sso cookie) */

    /**
     * 网页版调用（用 grok.com 的 `sso` cookie，不是 CLI 的 device flow）。
     *
     * 协议来自 grok2api_new（Tomiya233）的 `app/services/grok_client.py`：
     *   POST https://grok.com/rest/app-chat/conversations/new
     *   Cookie: sso=<token>
     *   body: {temporary, modelName, message, modelMode, ...}
     *   响应是逐行的 JSON（每行一个 {"result":{...}}），文本片段在 result.token /
     *   result.response.modelResponse.message 里。
     *
     * 说明：这条路**没有在真机上验证过**（手上没有可用的 sso）；所以默认仍然走 CLI 那条。
     */
    public static String webChat(String ssoToken, String modelName, String modelMode, String prompt) throws Exception {
        String cookie = ssoToken == null ? "" : ssoToken.trim();
        if (cookie.isEmpty()) throw new IllegalStateException("没有 sso token");
        if (!cookie.startsWith("sso=")) cookie = "sso=" + cookie;

        String m = (modelName == null || modelName.isEmpty()) ? "grok-4.1-thinking" : modelName;
        String mode = (modelMode == null || modelMode.isEmpty()) ? "MODEL_MODE_AUTO" : modelMode;

        JSONObject body = new JSONObject();
        body.put("temporary", true);
        body.put("modelName", m);
        body.put("message", prompt == null ? "" : prompt);
        body.put("fileAttachments", new JSONArray());
        body.put("imageAttachments", new JSONArray());
        body.put("disableSearch", false);
        body.put("enableImageGeneration", false);
        body.put("returnImageBytes", false);
        body.put("returnRawGrokInXaiRequest", false);
        body.put("enableImageStreaming", false);
        body.put("imageGenerationCount", 0);
        body.put("forceConcise", false);
        body.put("toolOverrides", new JSONObject());
        body.put("enableSideBySide", true);
        body.put("sendFinalMetadata", true);
        body.put("isReasoning", false);
        body.put("webpageUrls", new JSONArray());
        body.put("disableTextFollowUps", true);
        body.put("responseMetadata", new JSONObject());
        body.put("disableMemory", true);
        body.put("forceSideBySide", false);
        body.put("modelMode", mode);
        body.put("isAsyncChat", false);
        body.put("disableSelfHarmShortCircuit", false);

        Map<String, String> h = new java.util.HashMap<>();
        h.put("Content-Type", "application/json");
        h.put("Accept", "*/*");
        h.put("Origin", "https://grok.com");
        h.put("Referer", "https://grok.com/");
        h.put("Cookie", cookie);
        h.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        h.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");

        Http.Resp resp = Http.call("POST", "https://grok.com/rest/app-chat/conversations/new",
                h, body.toString().getBytes("UTF-8"), "application/json", 15000, 60000, false);
        if (resp.code >= 400) {
            throw Http.upstreamError(resp.code, resp.body);
        }
        // 逐行 JSON：把能拿到的文本都拼起来
        StringBuilder out = new StringBuilder();
        String best = "";
        for (String line : resp.body.split("\n")) {
            String t = line.trim();
            if (t.isEmpty() || !t.startsWith("{")) continue;
            try {
                JSONObject j = new JSONObject(t);
                JSONObject r = j.optJSONObject("result");
                if (r == null) continue;
                String tok = r.optString("token", "");
                if (!tok.isEmpty()) out.append(tok);
                JSONObject mr = r.optJSONObject("modelResponse");
                if (mr != null) {
                    String msg = mr.optString("message", "");
                    if (!msg.isEmpty()) best = msg;
                }
                JSONObject rp = r.optJSONObject("response");
                if (rp != null) {
                    JSONObject m2 = rp.optJSONObject("modelResponse");
                    if (m2 != null) {
                        String msg2 = m2.optString("message", "");
                        if (!msg2.isEmpty()) best = msg2;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        if (!best.isEmpty()) return best;
        if (out.length() > 0) return out.toString();
        throw new IllegalStateException("响应里没有找到文本：" + Upstreams.shortenPublic(resp.body));
    }

    /** 拉不到模型列表时的兜底（客户端至少能选）。 */
    static final String[] FALLBACK_MODELS = {"grok-4", "grok-3", "grok-code-fast-1"};

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
        public long expiresAt;
        public String email = "";

        public boolean usable() {
            return accessToken != null && !accessToken.isEmpty();
        }

        public static Account fromJson(String json) {
            if (json == null || json.isEmpty()) return new Account();
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
            a.expiresAt = (long) o.optDouble("expires_at", 0);
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
            } catch (Exception ignored) {
            }
            return o;
        }
    }

    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static Map<String, String> formHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("Content-Type", "application/x-www-form-urlencoded");
        h.put("Accept", "application/json");
        return h;
    }

    /** 第一步：要一个让用户去网页上输入的码。 */
    public static DeviceCode deviceAuthorize() throws Exception {
        String form = "client_id=" + enc(CLIENT_ID) + "&scope=" + enc(SCOPES);
        String body = Upstreams.postForm(AUTH_HOST + EP_DEVICE, formHeaders(), form);
        JSONObject o = new JSONObject(body);
        DeviceCode d = new DeviceCode();
        d.deviceCode = Proto.optString(o, "device_code", "");
        d.userCode = Proto.optString(o, "user_code", "");
        d.verificationUri = Proto.optString(o, "verification_uri", "https://accounts.x.ai/oauth2/device");
        d.verificationUriComplete = Proto.optString(o, "verification_uri_complete", d.verificationUri);
        Integer interval = Proto.optInt(o, "interval");
        d.interval = interval == null ? 5 : Math.max(2, interval.intValue());
        Integer expires = Proto.optInt(o, "expires_in");
        d.expiresIn = expires == null ? 1800 : expires.intValue();
        if (d.deviceCode.isEmpty() || d.userCode.isEmpty()) {
            throw new IllegalStateException("xAI 没有返回设备码：" + Upstreams.shortenPublic(body));
        }
        Log.i("xAI 设备码已获取：" + d.userCode);
        return d;
    }

    /** 第二步：轮询；用户还没确认时返回 null。 */
    public static Account pollToken(DeviceCode d) throws Exception {
        String form = "client_id=" + enc(CLIENT_ID)
                + "&device_code=" + enc(d.deviceCode)
                + "&grant_type=" + enc("urn:ietf:params:oauth:grant-type:device_code");
        String body = Upstreams.postFormAllowingError(AUTH_HOST + EP_TOKEN, formHeaders(), form);
        JSONObject o = new JSONObject(body);
        if (o.has("error")) {
            String err = Proto.optString(o, "error", "");
            // authorization_pending=还没确认；slow_down=要放慢（调用方按返回的 interval 处理）
            if ("authorization_pending".equals(err) || "slow_down".equals(err)) return null;
            throw new IllegalStateException("xAI 授权失败：" + err + " "
                    + Proto.optString(o, "error_description", ""));
        }
        Account a = new Account();
        a.accessToken = Proto.optString(o, "access_token", "");
        a.refreshToken = Proto.optString(o, "refresh_token", "");
        Integer expires = Proto.optInt(o, "expires_in");
        a.expiresAt = System.currentTimeMillis() / 1000 + (expires == null ? 3600 : expires.intValue()) - 60;
        if (!a.usable()) throw new IllegalStateException("xAI 没有返回 access_token："
                + Upstreams.shortenPublic(body));
        return a;
    }

    private static final Map<String, Long> LAST_REFRESH = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long REFRESH_COOLDOWN_SECONDS = 120L;

    /** 单一刷新入口（按账号加锁，各刷各的）。 */
    public static Account ensureFresh(Config.Provider p) throws Exception {
        synchronized (FreshLocks.of(p)) {
            return ensureFreshInner(p);
        }
    }

    private static Account ensureFreshInner(Config.Provider p) throws Exception {
        Account acc = Account.fromJson(p.credentials);
        if (!acc.usable()) throw new IllegalStateException("Grok 账号未登录，请先点「Grok 账号」");
        long now = System.currentTimeMillis() / 1000;
        if (acc.expiresAt > 0 && now + 300 < acc.expiresAt) return acc;
        String key = p.id == null ? p.name : p.id;
        Long last = LAST_REFRESH.get(key);
        if (last != null && now - last.longValue() < REFRESH_COOLDOWN_SECONDS) return acc;
        if (acc.refreshToken.isEmpty()) {
            throw new IllegalStateException("Grok 凭据已过期且没有 refresh_token，请重新登录");
        }
        LAST_REFRESH.put(key, now);
        String form = "client_id=" + enc(CLIENT_ID)
                + "&grant_type=refresh_token"
                + "&refresh_token=" + enc(acc.refreshToken);
        String body = Upstreams.postForm(AUTH_HOST + EP_TOKEN, formHeaders(), form);
        JSONObject o = new JSONObject(body);
        if (o.has("error")) {
            LAST_REFRESH.remove(key);
            throw new IllegalStateException("Grok 刷新失败：" + Proto.optString(o, "error", ""));
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
        Log.i("xAI token 已刷新并回存");
        return fresh;
    }

    /** grok-cli 代理端点要求的头。 */
    /**
     * 客户端版本号：这个头是强制校验的，而且官方版本一直在变（已见过 0.2.93 / 0.2.99 / 0.2.101 / 1.0.4 …）。
     * 所以优先读账号凭据里的 client_version（可以在配置里覆盖），没写才用内置默认值。
     */
    static String clientVersion(Config.Provider p) {
        try {
            org.json.JSONObject c = new org.json.JSONObject(
                    p.credentials == null || p.credentials.isEmpty() ? "{}" : p.credentials);
            String v = Proto.optString(c, "client_version", "");
            if (v != null && !v.isEmpty()) return v;
        } catch (Throwable ignored) {
        }
        return CLIENT_VERSION;
    }

    private static Map<String, String> headers(Config.Provider p, Account acc) {
        String ver = clientVersion(p);
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", "Bearer " + acc.accessToken);
        h.put("X-XAI-Token-Auth", TOKEN_AUTH_MARK);
        h.put("x-grok-client-version", ver);
        h.put("x-grok-client-identifier", CLIENT_IDENTIFIER);
        h.put("User-Agent", CLIENT_IDENTIFIER + "/" + ver);
        h.put("Accept", "text/event-stream, application/json");
        return h;
    }

    static String baseOf(Config.Provider p) {
        String b = p.baseUrl == null ? "" : p.baseUrl.trim();
        return b.isEmpty() ? DEFAULT_API_BASE : b;
    }

    /** 拉取 xAI / Grok 模型清单；代理端点不支持 /models 时平稳退回内置列表。 */
    public static List<String> fetchModels(Config.Provider p) {
        List<String> out = new ArrayList<>();
        try {
            Account acc = ensureFresh(p);
            String base = Upstreams.trimSlash(baseOf(p));
            String body = Upstreams.get(base + "/models", headers(p, acc));
            JSONObject o = new JSONObject(body);
            JSONArray data = o.optJSONArray("data");
            if (data == null) data = o.optJSONArray("models");
            for (int i = 0; data != null && i < data.length(); i++) {
                Object item = data.opt(i);
                String id = item instanceof JSONObject
                        ? Proto.optString((JSONObject) item, "id", Proto.optString((JSONObject) item, "name", ""))
                        : String.valueOf(item);
                if (id != null && !id.isEmpty() && !"null".equals(id) && !out.contains(id)) out.add(id);
            }
        } catch (Throwable t) {
            Log.w("xAI 模型目录拉取失败，退回默认清单：" + t);
        }
        if (out.isEmpty()) {
            for (String m : p.models) {
                if (m != null && !m.isEmpty() && !"*".equals(m) && !out.contains(m)) out.add(m);
            }
        }
        if (out.isEmpty()) {
            for (String m : FALLBACK_MODELS) out.add(m);
        }
        return out;
    }

    /** 登录成功后把账号写进配置（同 refresh_token 视为同一账号，更新而不重复添加）。 */
    public static JSONObject registerProvider(JSONObject accountJson) throws Exception {
        Account acc = Account.fromJson(accountJson);
        if (!acc.usable()) throw new IllegalStateException("没有 access_token，登录没完成");
        Config cfg = Gateway.config();
        Config.Provider target = null;
        for (Config.Provider p : cfg.providers) {
            if (!Config.TYPE_XAI.equals(p.type)) continue;
            Account ex = Account.fromJson(p.credentials);
            if (!acc.refreshToken.isEmpty() && acc.refreshToken.equals(ex.refreshToken)) {
                target = p;
                break;
            }
        }
        boolean created = target == null;
        if (created) {
            target = new Config.Provider();
            target.type = Config.TYPE_XAI;
            target.id = Config.newProviderId("xg");
            target.name = Config.nextAccountName("Grok", target.type);
            target.baseUrl = DEFAULT_API_BASE;
            cfg.providers.add(target);
        }
        target.credentials = acc.toJson().toString();
        target.enabled = true;
        target.lastError = "";
        target.lastErrorAt = 0;
        int modelCount = target.models.size();
        try {
            List<String> models = Upstreams.fetchModels(target);
            if (!models.isEmpty()) {
                target.setModels(models);
                modelCount = models.size();
            }
        } catch (Throwable t) {
            Log.w("Grok 模型列表拉取失败，用兜底列表：" + t);
        }
        if (target.models.isEmpty()) {
            List<String> fb = new ArrayList<>();
            for (String m : FALLBACK_MODELS) fb.add(m);
            target.setModels(fb);
            modelCount = fb.size();
        }
        Gateway.save(Gateway.context());
        JSONObject out = new JSONObject();
        out.put("provider", target.name);
        out.put("models", modelCount);
        return out;
    }

    static final class Up implements Proto.Upstream {
        private final Config.Provider p;

        Up(Config.Provider p) {
            this.p = p;
        }

        @Override
        public void chat(String model, Proto.Req req, Proto.Sink sink) throws Exception {
            // 先看是不是"网页版账号"（粘贴的是 grok.com 的 sso cookie）：
            // 那种账号不能用 CLI 的 device flow 去刷新，直接走 grok.com 的网页接口。
            Account raw = Account.fromJson(p.credentials);
            if (raw.accessToken != null && raw.accessToken.trim().startsWith("sso")) {
                StringBuilder prompt = new StringBuilder();
                for (Proto.Msg m : req.messages) {
                    for (Proto.Part pt : m.parts) {
                        if (Proto.Part.TEXT.equals(pt.type) && pt.text != null) {
                            prompt.append(pt.text).append('\n');
                        }
                    }
                }
                String text = webChat(raw.accessToken, model, null, prompt.toString().trim());
                sink.text(text);
                sink.finish("stop", new Proto.Usage());
                return;
            }
            Account acc = ensureFresh(p);
            String endpoint = Upstreams.url(baseOf(p), "chat/completions");
            /* 保真模式（4.50.1）：n>1 / logprobs 的响应原样回传。 */
            if (Upstreams.rawOpenAiIfWanted(req, endpoint, headers(p, acc), model, sink)) return;
            JSONObject body = Upstreams.openAiBody(model, req, true);
            Upstreams.openAiStream(endpoint, headers(p, acc), body, sink);
        }
    }

    private Xai() {
    }
}
