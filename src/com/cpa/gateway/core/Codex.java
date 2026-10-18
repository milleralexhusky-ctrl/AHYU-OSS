package com.cpa.gateway.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ChatGPT (Codex) channel: OAuth login with PKCE plus a Responses-API upstream.
 *
 * Constants mirror the reference CLI implementation: public client id, loopback redirect on
 * 127.0.0.1:1455, and the ChatGPT backend endpoint. Everything here is unverified against a real
 * ChatGPT account until the user logs in once - the request/SSE handling itself is exercised in
 * loopback mode (base URL pointing at our own /v1/responses).
 */
public final class Codex {

    private Codex() {}

    public static final String AUTH_URL = "https://auth.openai.com/oauth/authorize";
    public static final String TOKEN_URL = "https://auth.openai.com/oauth/token";
    public static final String CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann";
    public static final String REDIRECT_URI = "http://localhost:1455/auth/callback";
    public static final String DEFAULT_BASE = "https://chatgpt.com/backend-api/codex";
    public static final int CALLBACK_PORT = 1455;
    private static final String USER_AGENT = "codex-tui/0.154.0 (Mac OS 26.5.2; arm64) iTerm.app/3.6.11 (codex-tui; 0.154.0)";
    private static final String ORIGINATOR = "codex-tui";

    /* -------------------------------------------------------------- account */

    public static final class Account {
        public String accessToken = "";
        public String refreshToken = "";
        public String idToken = "";
        public String accountId = "";
        public String email = "";
        public String planType = "";
        public long expiresAt = 0L;

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
            a.idToken = Proto.optString(o, "id_token", "");
            a.accountId = Proto.optString(o, "account_id", "");
            a.email = Proto.optString(o, "email", "");
            a.planType = Proto.optString(o, "plan_type", "");
            Integer exp = Proto.optInt(o, "expires_at");
            a.expiresAt = exp == null ? 0L : exp.longValue();
            if (a.accountId.isEmpty() && !a.idToken.isEmpty()) a.accountId = accountIdFromIdToken(a.idToken);
            if (a.email.isEmpty() && !a.idToken.isEmpty()) a.email = emailFromIdToken(a.idToken);
            return a;
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("access_token", accessToken);
                o.put("refresh_token", refreshToken);
                o.put("id_token", idToken);
                o.put("account_id", accountId);
                o.put("email", email);
                o.put("plan_type", planType);
                o.put("expires_at", expiresAt);
                o.put("uid", accountId.isEmpty() ? email : accountId);
            } catch (Exception ignored) {
            }
            return o;
        }

        public boolean usable() {
            return !accessToken.isEmpty();
        }
    }

    /** id_token carries the ChatGPT account id under the "https://api.openai.com/auth" claim. */
    static String accountIdFromIdToken(String idToken) {
        try {
            JSONObject claims = jwtClaims(idToken);
            JSONObject auth = claims.optJSONObject("https://api.openai.com/auth");
            if (auth != null) {
                String id = Proto.optString(auth, "chatgpt_account_id", "");
                if (!id.isEmpty()) return id;
            }
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    static String emailFromIdToken(String idToken) {
        try {
            JSONObject claims = jwtClaims(idToken);
            String mail = Proto.optString(claims, "email", "");
            if (!mail.isEmpty()) return mail;
            return Proto.optString(claims, "preferred_username", "");
        } catch (Exception e) {
            return "";
        }
    }

    static String planTypeFromIdToken(String idToken) {
        try {
            JSONObject auth = jwtClaims(idToken).optJSONObject("https://api.openai.com/auth");
            return auth == null ? "" : Proto.optString(auth, "chatgpt_plan_type", "");
        } catch (Exception e) {
            return "";
        }
    }

    static JSONObject jwtClaims(String jwt) throws Exception {
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) throw new IllegalArgumentException("bad jwt");
        byte[] raw = android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
        return new JSONObject(new String(raw, "UTF-8"));
    }

    /* ------------------------------------------------------------------ pkce */

    public static String authorizeUrl(OAuth.Pkce pkce, String state) throws Exception {
        return AUTH_URL
                + "?client_id=" + URLEncoder.encode(CLIENT_ID, "UTF-8")
                + "&response_type=code"
                + "&redirect_uri=" + URLEncoder.encode(REDIRECT_URI, "UTF-8")
                + "&scope=" + URLEncoder.encode("openid email profile offline_access", "UTF-8")
                + "&state=" + URLEncoder.encode(state, "UTF-8")
                + "&code_challenge=" + URLEncoder.encode(pkce.challenge, "UTF-8")
                + "&code_challenge_method=S256"
                + "&prompt=login"
                + "&id_token_add_organizations=true"
                + "&codex_cli_simplified_flow=true";
    }

    public static Account exchangeCode(String code, OAuth.Pkce pkce) throws Exception {
        String form = "grant_type=authorization_code"
                + "&client_id=" + URLEncoder.encode(CLIENT_ID, "UTF-8")
                + "&code=" + URLEncoder.encode(code, "UTF-8")
                + "&redirect_uri=" + URLEncoder.encode(REDIRECT_URI, "UTF-8")
                + "&code_verifier=" + URLEncoder.encode(pkce.verifier, "UTF-8");
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/x-www-form-urlencoded");
        headers.put("Accept", "application/json");
        return parseTokenResponse(Upstreams.postForm(TOKEN_URL, headers, form));
    }

    static Account parseTokenResponse(String body) throws Exception {
        JSONObject o = new JSONObject(body);
        if (o.has("error")) {
            throw new IllegalStateException("OpenAI 返回错误：" + Proto.optString(o, "error", "")
                    + " " + Proto.optString(o, "error_description", ""));
        }
        Account a = new Account();
        a.accessToken = Proto.optString(o, "access_token", "");
        a.refreshToken = Proto.optString(o, "refresh_token", "");
        a.idToken = Proto.optString(o, "id_token", "");
        a.accountId = Proto.optString(o, "account_id", "");
        a.email = Proto.optString(o, "email", "");
        a.planType = Proto.optString(o, "plan_type", "");
        Integer expiresIn = Proto.optInt(o, "expires_in");
        a.expiresAt = System.currentTimeMillis() / 1000 + (expiresIn == null ? 3600 : expiresIn.intValue()) - 60;
        if (a.accountId.isEmpty()) a.accountId = accountIdFromIdToken(a.idToken);
        if (a.email.isEmpty()) a.email = emailFromIdToken(a.idToken);
        if (a.planType.isEmpty()) a.planType = planTypeFromIdToken(a.idToken);
        if (!a.usable()) throw new IllegalStateException("没拿到 access_token，原始响应：" + Upstreams.shortenPublic(body));
        return a;
    }

    /** Same single-entry rule as WorkBuddy: refresh only when close to expiry, then persist. */
    public static Account ensureFresh(Config.Provider p) throws Exception {
        // 原来是 static synchronized（类级锁）：一个账号走网络刷新 token 时，
        // 同 Provider 的其他账号全被堵住。现在按账号加锁，各刷各的。
        synchronized (FreshLocks.forKey(p.name)) {
            return ensureFreshInner(p);
        }
    }

    private static Account ensureFreshInner(Config.Provider p) throws Exception {
        Account acc = Account.fromJson(p.credentials);
        if (!acc.usable()) throw new IllegalStateException("ChatGPT 账号未登录，请先点「登录 ChatGPT」");
        long now = System.currentTimeMillis() / 1000;
        if (acc.expiresAt == 0 || now + 300 < acc.expiresAt) return acc;
        if (acc.refreshToken.isEmpty()) throw new IllegalStateException("ChatGPT 凭据已过期且没有 refresh_token，请重新登录");
        Log.i("codex token 临期，刷新账号");
        Account fresh;
        try {
            String form = "client_id=" + URLEncoder.encode(CLIENT_ID, "UTF-8")
                    + "&grant_type=refresh_token"
                    + "&refresh_token=" + URLEncoder.encode(acc.refreshToken, "UTF-8")
                    + "&scope=" + URLEncoder.encode("openid profile email", "UTF-8");
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "application/x-www-form-urlencoded");
            headers.put("Accept", "application/json");
            fresh = parseTokenResponse(Upstreams.postForm(TOKEN_URL, headers, form));
        } catch (Exception e) {
            throw new IllegalStateException("ChatGPT 凭据刷新失败，请重新登录：" + e.getMessage());
        }
        if (fresh.refreshToken.isEmpty()) fresh.refreshToken = acc.refreshToken;
        p.credentials = fresh.toJson().toString();
        Gateway.persistSoon();
        Log.i("codex token 已刷新并回存");
        return fresh;
    }

    /* -------------------------------------------------------------- upstream */

    static Map<String, String> headers(Account acc, String base) {
        Map<String, String> h = new HashMap<>();
        h.put("Content-Type", "application/json");
        h.put("Accept", "text/event-stream");
        h.put("Authorization", "Bearer " + acc.accessToken);
        h.put("User-Agent", USER_AGENT);
        h.put("Originator", ORIGINATOR);
        h.put("OpenAI-Beta", "responses=experimental");
        h.put("session_id", UUID.randomUUID().toString());
        h.put("version", "0.154.0");
        if (!acc.accountId.isEmpty()) h.put("chatgpt-account-id", acc.accountId);
        return h;
    }

    static String baseOf(Config.Provider p) {
        String b = Upstreams.trimSlash(p.baseUrl);
        return b.isEmpty() ? DEFAULT_BASE : b;
    }

    static JSONObject buildBody(String model, Proto.Req req) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("stream", true);
        body.put("store", false);
        if (!req.systemText().isEmpty()) body.put("instructions", req.systemText());
        JSONArray input = new JSONArray();
        for (Proto.Msg m : req.messages) {
            if ("tool".equals(m.role)) {
                for (Proto.Part part : m.parts) {
                    if (!Proto.Part.TOOL_RESULT.equals(part.type)) continue;
                    input.put(new JSONObject()
                            .put("type", "function_call_output")
                            .put("call_id", part.toolCallId == null ? "" : part.toolCallId)
                            .put("output", part.text == null ? "" : part.text));
                }
                continue;
            }
            JSONArray content = new JSONArray();
            for (Proto.Part part : m.parts) {
                if (Proto.Part.TEXT.equals(part.type)) {
                    content.put(new JSONObject()
                            .put("type", "assistant".equals(m.role) ? "output_text" : "input_text")
                            .put("text", part.text == null ? "" : part.text));
                } else if (Proto.Part.IMAGE.equals(part.type)) {
                    String url = Upstreams.imageUrl(part);
                    if (url != null) {
                        content.put(new JSONObject().put("type", "input_image").put("image_url", url));
                    }
                } else if (Proto.Part.TOOL_CALL.equals(part.type)) {
                    input.put(new JSONObject()
                            .put("type", "function_call")
                            .put("call_id", part.toolCallId == null ? "" : part.toolCallId)
                            .put("name", part.toolName == null ? "" : part.toolName)
                            .put("arguments", part.argsJson == null ? "{}" : part.argsJson));
                }
            }
            if (content.length() > 0) {
                input.put(new JSONObject().put("type", "message").put("role", m.role).put("content", content));
            }
        }
        body.put("input", input);
        if (req.maxTokens != null) body.put("max_output_tokens", req.maxTokens.intValue());
        if (req.temperature != null) body.put("temperature", req.temperature.doubleValue());
        if (req.topP != null) body.put("top_p", req.topP.doubleValue());
        JSONObject reasoning = new JSONObject();
        reasoning.put("effort", "medium");
        reasoning.put("summary", "auto");
        body.put("reasoning", reasoning);
        body.put("include", new JSONArray().put("reasoning.encrypted_content"));
        if (!req.tools.isEmpty()) {
            JSONArray tools = new JSONArray();
            for (Proto.Tool t : req.tools) {
                tools.put(new JSONObject()
                        .put("type", "function")
                        .put("name", t.name)
                        .put("description", t.description == null ? "" : t.description)
                        .put("parameters", t.schema == null ? new JSONObject().put("type", "object") : t.schema)
                        .put("strict", false));
            }
            body.put("tools", tools);
            body.put("tool_choice", "auto");
            body.put("parallel_tool_calls", true);
        }
        return body;
    }

    static final class Up implements Proto.Upstream {
        private final Config.Provider p;

        Up(Config.Provider p) {
            this.p = p;
        }

        @Override
        public void chat(String model, Proto.Req req, Proto.Sink sink) throws Exception {
            Account acc = ensureFresh(p);
            String base = baseOf(p);
            JSONObject body = buildBody(model, req);
            final Proto.Usage usage = new Proto.Usage();
            final String[] finish = {""};
            final Map<String, String[]> calls = new java.util.TreeMap<>();
            final StringBuilder args = new StringBuilder();
            final String[] currentCall = {""};

            Upstreams.postStream(Upstreams.trimSlash(base) + "/responses", headers(acc, base), body, line -> {
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
                String type = Proto.optString(ev, "type", "");
                if (type.endsWith("output_text.delta")) {
                    String d = Proto.optString(ev, "delta", "");
                    if (!d.isEmpty()) sink.text(d);
                } else if (type.contains("reasoning") && type.endsWith(".delta")) {
                    String d = Proto.optString(ev, "delta", Proto.optString(ev, "text", ""));
                    if (!d.isEmpty()) sink.thinking(d);
                } else if (type.endsWith("output_item.added")) {
                    JSONObject item = ev.optJSONObject("item");
                    if (item != null && "function_call".equals(Proto.optString(item, "type", ""))) {
                        String callId = Proto.optString(item, "call_id", Proto.optString(item, "id", ""));
                        String itemId = Proto.optString(item, "id", callId);
                        String key = !itemId.isEmpty() ? itemId : callId;
                        calls.put(key, new String[]{callId.isEmpty() ? key : callId, Proto.optString(item, "name", ""), ""});
                        currentCall[0] = key;
                        args.setLength(0);
                    }
                } else if (type.endsWith("function_call_arguments.delta")) {
                    String delta = Proto.optString(ev, "delta", "");
                    String key = Proto.optString(ev, "item_id", Proto.optString(ev, "call_id", currentCall[0]));
                    String[] entry = calls.get(key);
                    if (entry != null) {
                        entry[2] = (entry[2] == null ? "" : entry[2]) + delta;
                    } else {
                        args.append(delta);
                    }
                } else if (type.endsWith("output_item.done")) {
                    JSONObject item = ev.optJSONObject("item");
                    if (item != null && "function_call".equals(Proto.optString(item, "type", ""))) {
                        String callId = Proto.optString(item, "call_id", Proto.optString(item, "id", ""));
                        String itemId = Proto.optString(item, "id", callId);
                        String key = !itemId.isEmpty() ? itemId : callId;
                        String[] prev = calls.get(key);
                        String name = Proto.optString(item, "name", prev == null ? "" : prev[1]);
                        String fallbackArgs = (prev != null && prev[2] != null && !prev[2].isEmpty())
                                ? prev[2] : args.toString();
                        String a = Proto.optString(item, "arguments", fallbackArgs);
                        if (a.isEmpty()) a = fallbackArgs;
                        calls.put(key, new String[]{callId.isEmpty() ? key : callId, name, a});
                        currentCall[0] = "";
                    }
                } else if (type.endsWith("response.completed") || type.endsWith("response.done")) {
                    JSONObject resp = ev.optJSONObject("response");
                    if (resp != null) {
                        JSONObject u = resp.optJSONObject("usage");
                        if (u != null) {
                            Upstreams.applyUsage(usage, u, "input_tokens", "output_tokens", "cached_tokens");
                            JSONObject details = u.optJSONObject("input_tokens_details");
                            if (details != null) {
                                Integer c = Proto.optInt(details, "cached_tokens");
                                if (c != null) usage.cached = c;
                            }
                        }
                        String status = Proto.optString(resp, "status", "");
                        if (!status.isEmpty()) finish[0] = status;
                    }
                } else if (type.endsWith("response.failed") || "error".equals(type)) {
                    JSONObject resp = ev.optJSONObject("response");
                    JSONObject err = resp != null ? resp.optJSONObject("error") : ev.optJSONObject("error");
                    String msg = err != null ? Proto.optString(err, "message", err.toString()) : Upstreams.shortenPublic(ev.toString());
                    String kind = Upstreams.throttleKindFromBody(msg);
                    if (kind != null) {
                        int code = "quota".equals(kind) ? 402 : 429;
                        throw new Throttled(code, Http.defaultBackoffMs(code), kind, "ChatGPT 限流/额度：" + msg);
                    }
                    throw new IllegalStateException("ChatGPT 返回错误：" + msg);
                }
            });

            for (String[] c : calls.values()) {
                String raw = (c[2] == null || c[2].isEmpty()) ? "{}" : c[2];
                String fixed = ToolFix.repairArgs(raw);
                if (fixed == null) fixed = raw;
                String name = (c[1] == null || c[1].isEmpty()) ? ToolFix.nameFromArgs(fixed) : c[1];
                sink.toolCall(c[0], name, fixed);
            }
            sink.finish(Proto.canonicalStop(finish[0], !calls.isEmpty()), usage);
        }
    }

    /** Creates or updates the ChatGPT channel for this account. */
    public static JSONObject registerProvider(JSONObject accountJson) throws Exception {
        Account acc = Account.fromJson(accountJson);
        if (!acc.usable()) throw new IllegalStateException("没有 access_token，登录没完成");
        Config cfg = Gateway.config();
        Config.Provider target = null;
        for (Config.Provider p : cfg.providers) {
            if (!Config.TYPE_CODEX.equals(p.type)) continue;
            Account ex = Account.fromJson(p.credentials);
            if (!acc.accountId.isEmpty() && acc.accountId.equals(ex.accountId)) {
                target = p;
                break;
            }
        }
        boolean created = target == null;
        if (created) {
            target = new Config.Provider();
            target.type = Config.TYPE_CODEX;
            target.id = Config.newProviderId("cx");
            String who = acc.email.isEmpty()
                    ? (acc.accountId.length() > 6 ? acc.accountId.substring(0, 6) : acc.accountId)
                    : acc.email;
            target.name = Config.nextAccountName("ChatGPT", target.type);
            target.baseUrl = DEFAULT_BASE;
            cfg.providers.add(target);
        }
        target.credentials = acc.toJson().toString();
        target.enabled = true;
        if (target.models.isEmpty()) target.models.addAll(fetchModels(target));
        Gateway.saveNow(Gateway.context());
        JSONObject out = new JSONObject();
        out.put("provider", target.name);
        out.put("created", created);
        out.put("models", target.models.size());
        out.put("plan", acc.planType);
        return out;
    }

    /* ----------------------------------------------------------- model list */

    /**
     * Codex 的模型清单——**这里是写死的，而且是刻意的**。
     *
     * 为什么不从上游拉：ChatGPT 的 Codex 后端只暴露一个端点
     * （{@code /backend-api/codex/responses}，见 DEFAULT_BASE），
     * 没有"列举可用模型"的接口。官方 Codex CLI 用的也是**内置清单 +
     * 按用户套餐过滤**，不是服务端下发的。
     *
     * 所以这份清单要跟着 Codex CLI 的版本走：GPT 出新模型时，
     * 需要来这里补名字（对照 codex-tui 的模型表）。它漏项的表现是
     * "客户端里看不到新模型"，而不是报错。
     *
     * 另一个绕不开的点：清单里的名字**不代表你的账号一定能用**——
     * 能不能用取决于套餐（Plus / Pro / Team）。网关不在这里做过滤，
     * 让请求打到上游、由上游回答，这样"套餐升级后立刻可用"，
     * 不需要我们预判。
     */
    public static List<String> fetchModels(Config.Provider p) {
        List<String> out = new ArrayList<>();
        for (String m : new String[]{"gpt-5.1-codex", "gpt-5.1-codex-mini", "gpt-5.1", "gpt-5-codex", "gpt-5", "codex-mini-latest"}) {
            out.add(m);
        }
        return out;
    }
}
