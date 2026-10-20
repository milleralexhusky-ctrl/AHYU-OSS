package com.cpa.gateway.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Google Antigravity channel: a Google account gives free Gemini quota through the Cloud Code
 * companion API.
 *
 * Protocol notes (from the reference implementation, verified there against the live service):
 *  - OAuth: accounts.google.com/o/oauth2/v2/auth with access_type=offline + prompt=consent, callback
 *    http://localhost:51121/oauth-callback, then a form-encoded exchange with the client secret.
 *  - Project: POST /v1internal:loadCodeAssist with metadata.ideType=ANTIGRAVITY returns the tier and
 *    the cloudaicompanionProject id used by every generation call.
 *  - Generation: POST /v1internal:streamGenerateContent with an envelope
 *    {project, model, userAgent:"antigravity", requestType, requestId, request:{contents,...}};
 *    tool schemas must be sent as parametersJsonSchema and contents roles must alternate strictly.
 *  - Credentials: exactly one refresh entry point, only near expiry, and the rotated refresh_token
 *    must be persisted immediately.
 */
public final class Antigravity {

    private Antigravity() {}

    static final String AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth";
    static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
    static final String API_BASE = "https://cloudcode-pa.googleapis.com";

    /**
     * 待探测的模型名清单。
     *
     * 原来这个列表是**写死的 5 个**（gemini-3-pro-preview / gemini-3.8-flash / gemini-3-flash /
     * gemini-2.5-pro / gemini-2.5-flash），并不是从谷歌拉的——所以"只有 5 个模型"
     * 是本地清单短，不是上游只给 5 个。
     * 这组名字覆盖 Antigravity 各代 Gemini 的常见命名，用来实测哪些真的可用。
     */
    static final String[] PROBE_MODELS = {
            "gemini-3-pro-preview", "gemini-3-pro", "gemini-3.8-flash", "gemini-3-flash",
            "gemini-3-flash-preview", "gemini-3.5-flash", "gemini-3.1-pro-preview",
            "gemini-2.5-pro", "gemini-2.5-flash", "gemini-2.5-flash-lite",
            "gemini-2.0-flash", "gemini-exp-1206", "gemini-2.5-pro-preview-06-05"
    };
    static final String DAILY_API_BASE = "https://daily-cloudcode-pa.googleapis.com";
    static final String USERINFO = "https://www.googleapis.com/oauth2/v2/userinfo?alt=json";
    static final String CLIENT_ID = "1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com";
    /**
     * OAuth Client Secret。
     *
     * 私库构建时由 `build.sh` 从 `.private/signing.env` 注入到
     * {@link BuildSecrets}；公库构建没有这个值，会退回下面的占位提示。
     * 之前这里写死占位串，token 交换必然 `invalid_client`，
     * 用户看到的就是"Google 账号登录不进去"。
     */
    static final String CLIENT_SECRET_PLACEHOLDER = "在此填入你自己的 OAuth Client Secret";

    static String clientSecret() {
        String s = BuildSecrets.ANTIGRAVITY_CLIENT_SECRET;
        return s == null || s.isEmpty() ? CLIENT_SECRET_PLACEHOLDER : s;
    }
    static final String[] SCOPES = {
            "https://www.googleapis.com/auth/cloud-platform",
            "https://www.googleapis.com/auth/userinfo.email",
            "https://www.googleapis.com/auth/userinfo.profile",
            "https://www.googleapis.com/auth/cclog",
            "https://www.googleapis.com/auth/experimentsandconfigs"};
    public static final int CALLBACK_PORT = 51121;
    static final String CALLBACK_PATH = "/oauth-callback";
    public static final String USER_AGENT = "antigravity/hub/2.9.1 darwin/arm64";

    /* --------------------------------------------------------------- account */

    public static final class Account {
        public String accessToken = "";
        public String refreshToken = "";
        public long expiresAt = 0L;
        public String email = "";
        public String projectId = "";
        public String tier = "";
        /**
         * 上次向谷歌确认"当前档位"的时间（秒）。
         *
         * 为什么要记这个：原来 ensureProject() 里有一句
         *     if (!acc.projectId.isEmpty()) return acc;
         * 也就是**只要解析过一次，就永远不再问谷歌**。
         * 而订阅档位是会变的（充值 AI Pro、升级 Ultra、到期降级都会变），
         * 于是充值之后网关仍按旧档位（往往就是 free-tier）请求，
         * 额度一封顶就持续 429 RESOURCE_EXHAUSTED——真机上就是这个现象。
         */
        public long tierCheckedAt = 0L;

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
            a.projectId = Proto.optString(o, "project_id", "");
            a.tier = Proto.optString(o, "tier", "");
            Integer tca = Proto.optInt(o, "tier_checked_at");
            a.tierCheckedAt = tca == null ? 0L : tca.longValue();
            return a;
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("access_token", accessToken);
                o.put("refresh_token", refreshToken);
                o.put("expires_at", expiresAt);
                o.put("email", email);
                o.put("project_id", projectId);
                o.put("tier", tier);
                o.put("tier_checked_at", tierCheckedAt);
                o.put("uid", email.isEmpty() ? "antigravity" : email);
            } catch (Exception ignored) {
            }
            return o;
        }

        public boolean usable() {
            return !accessToken.isEmpty();
        }
    }

    static String redirectUri() {
        return "http://localhost:" + CALLBACK_PORT + CALLBACK_PATH;
    }

    public static String authorizeUrl(String state) throws Exception {
        return authorizeUrl(state, null);
    }

    /**
     * @param codeChallenge PKCE S256 challenge；null 表示不使用 PKCE。
     *
     * 为什么要加：client_secret 随 APK 分发，单靠 secret + 固定回环端口，
     * 本机另一个应用抢先占用 51121 就能截走 authorization code 并兑换 token。
     * PKCE 的 verifier 只存在于本次登录的内存里，截到 code 也换不了。
     */
    public static String authorizeUrl(String state, String codeChallenge) throws Exception {
        StringBuilder sb = new StringBuilder(AUTH_ENDPOINT).append("?");
        sb.append("access_type=offline");
        sb.append("&client_id=").append(URLEncoder.encode(CLIENT_ID, "UTF-8"));
        sb.append("&prompt=consent");
        sb.append("&redirect_uri=").append(URLEncoder.encode(redirectUri(), "UTF-8"));
        sb.append("&response_type=code");
        sb.append("&scope=").append(URLEncoder.encode(String.join(" ", SCOPES), "UTF-8"));
        sb.append("&state=").append(URLEncoder.encode(state, "UTF-8"));
        if (codeChallenge != null && !codeChallenge.isEmpty()) {
            sb.append("&code_challenge=").append(URLEncoder.encode(codeChallenge, "UTF-8"));
            sb.append("&code_challenge_method=S256");
        }
        return sb.toString();
    }

    public static Account exchangeCode(String code) throws Exception {
        return exchangeCode(code, null);
    }

    public static Account exchangeCode(String code, String codeVerifier) throws Exception {
        String form = "code=" + URLEncoder.encode(code, "UTF-8")
                + "&client_id=" + URLEncoder.encode(CLIENT_ID, "UTF-8")
                + "&client_secret=" + URLEncoder.encode(clientSecret(), "UTF-8")
                + "&redirect_uri=" + URLEncoder.encode(redirectUri(), "UTF-8")
                + "&grant_type=authorization_code";
        if (codeVerifier != null && !codeVerifier.isEmpty()) {
            form += "&code_verifier=" + URLEncoder.encode(codeVerifier, "UTF-8");
        }
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/x-www-form-urlencoded");
        headers.put("Accept", "application/json");
        return parseToken(Upstreams.postForm(TOKEN_ENDPOINT, headers, form));
    }

    static Account parseToken(String body) throws Exception {
        JSONObject o = new JSONObject(body);
        if (o.has("error")) {
            throw new IllegalStateException("Google 返回错误：" + Proto.optString(o, "error", "")
                    + " " + Proto.optString(o, "error_description", ""));
        }
        Account a = new Account();
        a.accessToken = Proto.optString(o, "access_token", "");
        a.refreshToken = Proto.optString(o, "refresh_token", "");
        Integer expiresIn = Proto.optInt(o, "expires_in");
        a.expiresAt = System.currentTimeMillis() / 1000 + (expiresIn == null ? 3600 : expiresIn.intValue()) - 60;
        if (!a.usable()) throw new IllegalStateException("没拿到 access_token：" + Upstreams.shortenPublic(body));
        return a;
    }

    /** Single refresh entry point; Google rotates nothing here but the expiry must be refreshed. */
    public static Account ensureFresh(Config.Provider p) throws Exception {
        // 原来是 static synchronized（类级锁）：一个账号走网络刷新 token 时，
        // 同 Provider 的其他账号全被堵住。现在按账号加锁，各刷各的。
        synchronized (FreshLocks.of(p)) {
            return ensureFreshInner(p);
        }
    }

    private static Account ensureFreshInner(Config.Provider p) throws Exception {
        Account acc = Account.fromJson(p.credentials);
        if (!acc.usable()) throw new IllegalStateException("Antigravity 账号未登录，请先点「登录 Google 账号」");
        long now = System.currentTimeMillis() / 1000;
        if (acc.expiresAt > 0 && now + 300 < acc.expiresAt) return acc;
        if (acc.refreshToken.isEmpty()) throw new IllegalStateException("凭据已过期且没有 refresh_token，请重新登录");
        Log.i("antigravity token 临期，刷新中");
        String form = "client_id=" + URLEncoder.encode(CLIENT_ID, "UTF-8")
                + "&client_secret=" + URLEncoder.encode(clientSecret(), "UTF-8")
                + "&refresh_token=" + URLEncoder.encode(acc.refreshToken, "UTF-8")
                + "&grant_type=refresh_token";
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/x-www-form-urlencoded");
        headers.put("Accept", "application/json");
        Account fresh = parseToken(Upstreams.postForm(TOKEN_ENDPOINT, headers, form));
        fresh.refreshToken = acc.refreshToken;
        fresh.email = acc.email;
        fresh.projectId = acc.projectId;
        fresh.tier = acc.tier;
        // tierCheckedAt 必须一起带过来：token 刷新很频繁（接近过期就刷），
        // 漏了它会让 TTL 每次都归零 → 变成"每请求都去问谷歌要档位"，
        // 那是把刚才省下来的调用又加倍打回去了。
        fresh.tierCheckedAt = acc.tierCheckedAt;
        p.credentials = fresh.toJson().toString();
        Gateway.persistSoon();
        Log.i("antigravity token 已刷新并回存");
        return fresh;
    }

    static Map<String, String> headers(Account acc, boolean stream) {
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", "Bearer " + acc.accessToken);
        h.put("Content-Type", "application/json");
        h.put("Accept", stream ? "text/event-stream" : "application/json");
        h.put("User-Agent", USER_AGENT);
        return h;
    }

    /* ---------------------------------------------------------------- project */

    /** Resolves the companion project id once per account and remembers it. */
    /** 隔多久重新向谷歌确认一次档位（秒）。1 天一次：够快感知充值/升级，又不至于每请求都打。 */
    private static final long TIER_TTL_SECONDS = 24 * 60 * 60L;

    public static Account ensureProject(Config.Provider p) throws Exception {
        Account acc = ensureFresh(p);
        // 原来这里是 `if (!acc.projectId.isEmpty()) return acc;`——**永久缓存**。
        // 后果：订阅发生变化（充值 AI Pro、升级 Ultra、到期降级）之后，
        // 网关仍然拿着旧的 tier 去请求，额度按旧档位封顶，用户看到的就是
        // "我明明充了会员却一直 429 RESOURCE_EXHAUSTED"，而且重启也不会好。
        // 现在改成按 TTL 过期：有 project 但超过一天没确认过，就重新问一次谷歌。
        long nowSec = System.currentTimeMillis() / 1000L;
        boolean fresh = !acc.projectId.isEmpty()
                && acc.tierCheckedAt > 0
                && nowSec - acc.tierCheckedAt < TIER_TTL_SECONDS;
        if (fresh) return acc;
        Log.i("antigravity 重新确认档位（上次 "
                + (acc.tierCheckedAt == 0 ? "从未" : ((nowSec - acc.tierCheckedAt) + " 秒前")) + "）");
        JSONObject metadata = new JSONObject();
        metadata.put("ideType", "ANTIGRAVITY");
        JSONObject body = new JSONObject();
        body.put("metadata", metadata);
        JSONObject resp = new JSONObject(Upstreams.postJson(API_BASE + "/v1internal:loadCodeAssist",
                headers(acc, false), body));
        // 把谷歌的原始答复打进日志：tier / allowedTiers / ineligibleTiers / project 全在里面。
        // 没有这一行就只能猜"是没额度还是接口问错了"——而这两件事的修法完全不同。
        // 分段打关键字段：整段响应会被 logcat 单行长度截断，反过来什么都看不见。
        try {
            JSONObject curTier = resp.optJSONObject("currentTier");
            StringBuilder tb = new StringBuilder();
            JSONArray at = resp.optJSONArray("allowedTiers");
            for (int i = 0; at != null && i < at.length(); i++) {
                JSONObject t = at.optJSONObject(i);
                if (t != null) tb.append(tb.length() > 0 ? "," : "").append(Proto.optString(t, "id", "?"));
            }
            StringBuilder ib = new StringBuilder();
            JSONArray it = resp.optJSONArray("ineligibleTiers");
            for (int i = 0; it != null && i < it.length(); i++) {
                JSONObject t = it.optJSONObject(i);
                if (t == null) continue;
                ib.append(ib.length() > 0 ? " | " : "")
                  .append(Proto.optString(t, "id", "?"))
                  .append(":").append(Proto.optString(t, "reasonMessage", ""));
            }
            Log.i("antigravity 档位：" + (curTier == null ? "(无 currentTier)" : Proto.optString(curTier, "id", "?"))
                    + " · 可用档位=[" + tb + "]"
                    + " · 不合格档位=[" + ib + "]"
                    + " · 自定义项目=" + resp.optBoolean("userDefinedCloudaicompanionProject", false)
                    + " · 响应字段=" + resp.keys().next());
            // 曾经在这里把完整响应分段打印（排查"地区不合格"时用的），
            // 现在撤掉：正常启动会刷出好几条长日志，而上面那行摘要
            // （档位 / 可用档位 / 不合格档位）已经够定位问题了。
        } catch (Throwable t2) {
            Log.w("antigravity 档位解析失败：" + t2);
        }
        String project = extractProject(resp);
        String tier = "";
        JSONArray tiers = resp.optJSONArray("allowedTiers");
        if (tiers != null) {
            for (int i = 0; i < tiers.length(); i++) {
                JSONObject t = tiers.optJSONObject(i);
                if (t == null) continue;
                if (t.optBoolean("isDefault", false)) {
                    tier = Proto.optString(t, "id", "");
                    break;
                }
            }
        }
        JSONObject current = resp.optJSONObject("currentTier");
        if (tier.isEmpty() && current != null) tier = Proto.optString(current, "id", "");
        if (tier.isEmpty()) tier = "free-tier";
        if (project.isEmpty()) {
            // no companion project yet: provision one through onboardUser (daily control plane)
            project = onboardUser(acc, tier);
        }
        acc.projectId = project;
        acc.tier = tier;
        acc.tierCheckedAt = System.currentTimeMillis() / 1000L;
        if (acc.email.isEmpty()) {
            try {
                JSONObject me = new JSONObject(Upstreams.getWithHeaders(USERINFO, headers(acc, false)));
                acc.email = Proto.optString(me, "email", "");
            } catch (Exception e) {
                Log.w("antigravity 用户信息获取失败：" + e);
            }
        }
        p.credentials = acc.toJson().toString();
        Gateway.persistSoon();
        Log.i("antigravity project=" + (project.isEmpty() ? "(空)" : project) + " tier=" + tier);
        if (project.isEmpty()) {
            // report what Google actually said instead of guessing "not activated"
            String reason = "";
            JSONArray ineligible = resp.optJSONArray("ineligibleTiers");
            for (int i = 0; ineligible != null && i < ineligible.length(); i++) {
                JSONObject bad = ineligible.optJSONObject(i);
                if (bad == null) continue;
                String msg = Proto.optString(bad, "reasonMessage", "");
                if (!msg.isEmpty()) {
                    reason = msg;
                    break;
                }
            }
            if (reason.isEmpty() && tiers != null) {
                for (int i = 0; i < tiers.length(); i++) {
                    JSONObject t = tiers.optJSONObject(i);
                    if (t != null && t.optBoolean("userDefinedCloudaicompanionProject", false)) {
                        reason = "该等级需要你自己在 Google Cloud 里指定项目（userDefinedCloudaicompanionProject）";
                        break;
                    }
                }
            }
            throw new IllegalStateException("这个 Google 账号拿不到 Antigravity 项目（tier=" + tier + "）"
                    + (reason.isEmpty() ? "" : "：" + reason));
        }
        return acc;
    }

    static String extractProject(JSONObject node) {
        if (node == null) return "";
        for (String key : new String[]{"cloudaicompanionProject", "projectId", "project"}) {
            Object v = node.opt(key);
            if (v instanceof JSONObject) {
                String id = Proto.optString((JSONObject) v, "id", "");
                if (!id.isEmpty()) return id;
            } else if (v instanceof String && !((String) v).isEmpty()) {
                return (String) v;
            }
        }
        JSONObject response = node.optJSONObject("response");
        if (response != null) return extractProject(response);
        return "";
    }

    /**
     * 后台探测：逐个试候选模型名，能通的留下、不通的移除。
     *
     * 为什么要探测而不是等清单：Antigravity 协议里没有列举模型的端点，
     * 官方客户端用的是内置清单，我们只能自己试。
     * 探测跑在后台 IO 池里，不阻塞登录；每个模型用最小请求（1 token）探活。
     * 失败的**不拉黑**（可能是暂时的地区/额度问题），只是从"可用清单"里去掉，
     * 所以不会污染账号状态。
     */
    private static void scheduleModelProbe(final Config.Provider target) {
        try {
            Threads.io(() -> {
                java.util.List<String> alive = new java.util.ArrayList<>();
                for (String m : PROBE_MODELS) {
                    try {
                        Proto.Req req = new Proto.Req();
                        req.model = m;
                        Proto.Msg msg = new Proto.Msg("user");
                        msg.add(Proto.Part.text("hi"));
                        req.messages.add(msg);
                        req.maxTokens = 1;
                        req.ephemeral = true;          // 探活：不留痕迹、不计用量
                        final boolean[] ok = {false};
                        Proto.Sink sink = new Proto.Sink() {
                            @Override public void text(String delta) { ok[0] = true; }
                            @Override public void thinking(String delta) { ok[0] = true; }
                            @Override public void toolCall(String id, String name, String argsJson) { }
                            @Override public void finish(String stopReason, Proto.Usage usage) { }
                        };
                        new Up(target).chat(m, req, sink);
                        if (ok[0]) {
                            alive.add(m);
                            Log.i("antigravity 探测通过：" + m);
                        }
                    } catch (Throwable t) {
                        Log.i("antigravity 探测不通：" + m + " · " + Upstreams.shorten(String.valueOf(t)));
                    }
                }
                if (alive.isEmpty()) {
                    // 一个都不通（常见于地区不合格、或额度耗尽）：**保持原样**，
                    // 不要把清单清空——清空会连"以后恢复了自动可用"的机会都没了。
                    Log.w("antigravity 探测没有任何模型通过，保留候选清单不动");
                    return;
                }
                synchronized (target.models) {
                    target.models.clear();
                    target.models.addAll(alive);
                }
                Gateway.persistSoon();
                Log.i("antigravity 模型清单已收敛为 " + alive.size() + " 个：" + alive);
            });
        } catch (Throwable t) {
            Log.w("antigravity 探测调度失败：" + t);
        }
    }

    /** Onboards the account so it gets a companion project id. Polls until the operation is done. */
    static String onboardUser(Account acc, String tier) {
        JSONObject metadata = new JSONObject();
        try {
            metadata.put("ide_type", "ANTIGRAVITY");
            metadata.put("ide_version", "2.9.1");
            metadata.put("ide_name", "antigravity");
            JSONObject body = new JSONObject();
            body.put("tier_id", tier.isEmpty() ? "free-tier" : tier);
            body.put("metadata", metadata);
            for (int attempt = 1; attempt <= 5; attempt++) {
                JSONObject resp = new JSONObject(Upstreams.postJson(
                        DAILY_API_BASE + "/v1internal:onboardUser", headers(acc, false), body));
                String project = extractProject(resp);
                if (!project.isEmpty()) {
                    Log.i("antigravity onboardUser 成功，project=" + project);
                    return project;
                }
                if (resp.optBoolean("done", false)) break;
                Thread.sleep(2000);
            }
        } catch (Exception e) {
            Log.w("antigravity onboardUser 失败：" + e);
        }
        return "";
    }

    /* -------------------------------------------------------------- chat body */

    static JSONObject buildBody(String model, Proto.Req req, Account acc) throws Exception {
        JSONObject inner = new JSONObject();
        JSONArray contents = new JSONArray();
        String lastRole = "";
        StringBuilder firstUserText = new StringBuilder();
        for (Proto.Msg m : req.messages) {
            String role = "assistant".equals(m.role) ? "model" : "user";
            JSONArray parts = new JSONArray();
            for (Proto.Part part : m.parts) {
                if (Proto.Part.TEXT.equals(part.type)) {
                    parts.put(new JSONObject().put("text", part.text == null ? "" : part.text));
                } else if (Proto.Part.THINKING.equals(part.type)) {
                    parts.put(new JSONObject().put("text", part.text == null ? "" : part.text).put("thought", true));
                } else if (Proto.Part.TOOL_CALL.equals(part.type)) {
                    Object args;
                    try {
                        args = new JSONObject(part.argsJson == null ? "{}" : part.argsJson);
                    } catch (Exception e) {
                        args = new JSONObject();
                    }
                    parts.put(new JSONObject().put("functionCall", new JSONObject()
                            .put("id", part.toolCallId == null ? "" : part.toolCallId)
                            .put("name", part.toolName == null ? "" : part.toolName)
                            .put("args", args)));
                } else if (Proto.Part.TOOL_RESULT.equals(part.type)) {
                    JSONObject response = new JSONObject();
                    // the upstream requires response.result to be a string
                    response.put("result", part.text == null ? "" : part.text);
                    parts.put(new JSONObject().put("functionResponse", new JSONObject()
                            .put("id", part.toolCallId == null ? "" : part.toolCallId)
                            .put("name", part.toolName == null || part.toolName.isEmpty() ? "tool" : part.toolName)
                            .put("response", response)));
                } else if (Proto.Part.IMAGE.equals(part.type)) {
                    if (part.mimeType != null && part.mimeType.startsWith("url:")) continue;
                    parts.put(new JSONObject().put("inlineData", new JSONObject()
                            .put("mimeType", part.mimeType == null || part.mimeType.isEmpty() ? "image/png" : part.mimeType)
                            .put("data", part.dataBase64 == null ? "" : part.dataBase64)));
                }
            }
            if (parts.length() == 0) continue;
            // roles must alternate strictly: merge consecutive same-role turns into one content
            if (role.equals(lastRole) && contents.length() > 0) {
                JSONObject prev = contents.optJSONObject(contents.length() - 1);
                JSONArray prevParts = prev == null ? null : prev.optJSONArray("parts");
                if (prevParts != null) {
                    for (int i = 0; i < parts.length(); i++) prevParts.put(parts.opt(i));
                    continue;
                }
            }
            if ("user".equals(role) && firstUserText.length() == 0) {
                for (int i = 0; i < parts.length(); i++) {
                    JSONObject p = parts.optJSONObject(i);
                    if (p != null && p.has("text")) firstUserText.append(p.optString("text", ""));
                }
            }
            contents.put(new JSONObject().put("role", role).put("parts", parts));
            lastRole = role;
        }
        inner.put("contents", contents);
        if (!req.systemText().isEmpty()) {
            inner.put("systemInstruction", new JSONObject().put("parts",
                    new JSONArray().put(new JSONObject().put("text", req.systemText()))));
        }
        JSONArray safety = new JSONArray();
        for (String category : new String[]{"HARM_CATEGORY_HARASSMENT", "HARM_CATEGORY_HATE_SPEECH",
                "HARM_CATEGORY_SEXUALLY_EXPLICIT", "HARM_CATEGORY_DANGEROUS_CONTENT"}) {
            safety.put(new JSONObject().put("category", category).put("threshold", "OFF"));
        }
        safety.put(new JSONObject().put("category", "HARM_CATEGORY_CIVIC_INTEGRITY").put("threshold", "BLOCK_NONE"));
        inner.put("safetySettings", safety);
        if (!req.tools.isEmpty()) {
            JSONArray declarations = new JSONArray();
            for (Proto.Tool t : req.tools) {
                JSONObject d = new JSONObject();
                d.put("name", t.name);
                d.put("description", t.description == null ? "" : t.description);
                // this upstream wants parametersJsonSchema, with unsupported keywords stripped
                d.put("parametersJsonSchema", sanitizeSchema(t.schema));
                declarations.put(d);
            }
            inner.put("tools", new JSONArray().put(new JSONObject().put("functionDeclarations", declarations)));
        }
        JSONObject gen = new JSONObject();
        if (req.temperature != null) gen.put("temperature", req.temperature.doubleValue());
        if (req.topP != null) gen.put("topP", req.topP.doubleValue());
        if (req.maxTokens != null) gen.put("maxOutputTokens", req.maxTokens.intValue());
        if (gen.length() > 0) inner.put("generationConfig", gen);
        String seed = firstUserText.toString();
        inner.put("sessionId", sha256Hex(seed.isEmpty() ? model : seed).substring(0, 32));

        JSONObject env = new JSONObject();
        env.put("project", acc.projectId);
        env.put("model", model);
        env.put("userAgent", "antigravity");
        env.put("requestType", "agent");
        env.put("requestId", "req-" + Long.toHexString(System.nanoTime()));
        env.put("request", inner);
        return env;
    }

    static JSONObject sanitizeSchema(JSONObject schema) {
        try {
            if (schema == null) return new JSONObject().put("type", "object");
            JSONObject copy = new JSONObject(schema.toString());
            for (String bad : new String[]{"const", "$ref", "$schema", "default"}) copy.remove(bad);
            return copy;
        } catch (Exception e) {
            JSONObject fallback = new JSONObject();
            try {
                fallback.put("type", "object");
            } catch (Exception ignored) {
            }
            return fallback;
        }
    }

    static String sha256Hex(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Long.toHexString(s.hashCode());
        }
    }

    static final class Up implements Proto.Upstream {
        private final Config.Provider p;

        Up(Config.Provider p) {
            this.p = p;
        }

        @Override
        public void chat(String model, Proto.Req req, Proto.Sink sink) throws Exception {
            Account acc = ensureProject(p);
            JSONObject body = buildBody(model, req, acc);
            final Proto.Usage usage = new Proto.Usage();
            final String[] finish = {""};
            final List<String[]> calls = new ArrayList<>();

            Upstreams.postStream(API_BASE + "/v1internal:streamGenerateContent?alt=sse", headers(acc, true), body, line -> {
                String t = line == null ? "" : line.trim();
                if (t.isEmpty() || t.startsWith(":")) return;
                if (!t.startsWith("data:")) return;
                String data = t.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) return;
                JSONObject ev;
                try {
                    ev = new JSONObject(data);
                } catch (Exception e) {
                    return;
                }
                // the companion API wraps Gemini payloads in a "response" field
                JSONObject payload = ev.optJSONObject("response");
                if (payload == null) payload = ev;
                JSONObject um = payload.optJSONObject("usageMetadata");
                if (um != null) {
                    Upstreams.applyUsage(usage, um, "promptTokenCount", "candidatesTokenCount", "cachedContentTokenCount");
                }
                JSONArray candidates = payload.optJSONArray("candidates");
                if (candidates == null || candidates.length() == 0) return;
                JSONObject c0 = candidates.optJSONObject(0);
                if (c0 == null) return;
                JSONObject content = c0.optJSONObject("content");
                if (content != null) {
                    JSONArray parts = content.optJSONArray("parts");
                    for (int i = 0; parts != null && i < parts.length(); i++) {
                        JSONObject part = parts.optJSONObject(i);
                        if (part == null) continue;
                        JSONObject fc = part.optJSONObject("functionCall");
                        if (fc != null) {
                            JSONObject args = fc.optJSONObject("args");
                            String id = Proto.optString(fc, "id", "call_" + calls.size());
                            calls.add(new String[]{id, Proto.optString(fc, "name", ""), args == null ? "{}" : args.toString()});
                            continue;
                        }
                        String text = Proto.optString(part, "text", "");
                        if (text.isEmpty()) continue;
                        if (part.optBoolean("thought", false)) sink.thinking(text);
                        else sink.text(text);
                    }
                }
                String fr = Proto.optString(c0, "finishReason", "");
                if (!fr.isEmpty() && !"FINISH_REASON_UNSPECIFIED".equals(fr)) finish[0] = fr;
            });

            for (String[] c : calls) sink.toolCall(c[0], c[1], c[2]);
            sink.finish(Proto.canonicalStop(finish[0], !calls.isEmpty()), usage);
        }
    }

    public static JSONObject registerProvider(JSONObject accountJson) throws Exception {
        Account acc = Account.fromJson(accountJson);
        if (!acc.usable()) throw new IllegalStateException("没有 access_token，登录没完成");
        // resolve project + email first: that is also the identity used for de-duplication
        Config.Provider probe = new Config.Provider();
        probe.type = Config.TYPE_ANTIGRAVITY;
        probe.name = "probe";
        probe.credentials = acc.toJson().toString();
        try {
            acc = ensureProject(probe);
        } catch (Exception e) {
            Log.w("antigravity 项目解析失败（仍会保存账号）：" + e);
        }
        Config cfg = Gateway.config();
        Config.Provider target = null;
        for (Config.Provider p : cfg.providers) {
            if (!Config.TYPE_ANTIGRAVITY.equals(p.type)) continue;
            Account ex = Account.fromJson(p.credentials);
            if (!acc.email.isEmpty() && acc.email.equals(ex.email)) {
                target = p;
                break;
            }
        }
        boolean created = target == null;
        if (created) {
            target = new Config.Provider();
            target.type = Config.TYPE_ANTIGRAVITY;
            target.id = Config.newProviderId("ag");
            target.name = Config.nextAccountName("Antigravity", target.type);
            target.baseUrl = API_BASE;
            cfg.providers.add(target);
        }
        target.credentials = acc.toJson().toString();
        target.enabled = true;
        target.lastError = "";
        target.lastErrorAt = 0;
        target.badModels.clear();
        if (target.models.isEmpty()) {
            // 先把候选清单放进去：立刻就有东西能用，不阻塞登录流程。
            for (String m : PROBE_MODELS) {
                target.models.add(m);
            }
            // 再放到后台逐个试：Antigravity 的协议里**没有"列模型"端点**
            // （只有 OAuth / loadCodeAssist / streamGenerateContent 三个），
            // 所以清单只能靠试出来。试的时候要打真请求，一次几秒 × 13 个，
            // 放同步会把登录卡住，所以丢后台；试完自动更新模型列表。
            scheduleModelProbe(target);
        }
        Gateway.saveNow(Gateway.context());
        JSONObject out = new JSONObject();
        out.put("provider", target.name);
        out.put("created", created);
        out.put("models", target.models.size());
        out.put("project", acc.projectId);
        return out;
    }
}
