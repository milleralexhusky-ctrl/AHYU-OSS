package com.cpa.gateway.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 字节 Trae（SOLO 通道）：浏览器登录 + OpenAI 风格对话 + 每日签到积分。
 *
 * 协议要点（均来自实测整理，改动需重新验证）：
 *  - 回调只回 127.0.0.1:51120/authorize，回调里带 refreshToken / userInfo / userJwt；
 *  - device_id 必须是纯数字、machine_id 是 64 位 hex，否则签到被 9074 拒绝；
 *  - ExchangeToken 每次轮换 refreshToken，刷新后必须立刻回存；
 *  - 对话必须 stream:true + function=solo_work_lite，content 要包成 [{type:text,text}] 数组。
 */
public final class Trae {

    private Trae() {}

    static final String AGENT_HOST = "https://trae-api-cn.mchost.guru";
    static final String UG_HOST = "https://api.trae.cn";
    static final String OAUTH_HOST = "https://api.trae.com.cn";
    static final String CONSOLE_HOST = "https://www.trae.cn";
    static final String CLIENT_ID = "en1oxy7wnw8j9n";
    static final String APP_ID = "6eefa01c-1036-4c7e-9ca5-d891f63bfcd8";
    static final String FUNCTION = "solo_work_lite";
    static final String EP_CHAT = "/api/agent/v3/llm_utils_chat";
    static final String EP_EXCHANGE = "/cloudide/api/v3/trae/oauth/ExchangeToken";
    static final String EP_USER_INFO = "/cloudide/api/v3/trae/GetUserInfo";
    static final String EP_CHECKIN_STATUS = "/trae/api/v2/ug/checkin_credits/status";
    static final String EP_CHECKIN_CLAIM = "/trae/api/v2/ug/checkin_credits/claim";
    static final String EP_ENT_USAGE_WEB = "/trae/api/v2/pay/web_user_ent_usage";
    public static final int CALLBACK_PORT = 51120;
    static final String CALLBACK_PATH = "/authorize";

    // client fingerprint - the upstream gates model availability on X-Ide-Version
    static final String IDE_VERSION = "0.1.52";
    static final String IDE_VERSION_CODE = "20260811";
    static final String PLUGIN_VERSION = "2.3.62834";
    static final String DEVICE_BRAND = "83DG";
    static final String OS_VERSION = "Windows 11 Pro";

    private static final SecureRandom RANDOM = new SecureRandom();

    /* -------------------------------------------------------------- identity */

    public static String randomHex(int bytes) {
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        for (byte b : buf) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /** Official clients use a plain numeric device id; hex values get the check-in rejected (9074). */
    public static String randomDeviceId() {
        long v = Math.abs(RANDOM.nextLong()) % 900_000_000_000_000L + 100_000_000_000_000L;
        return String.format("%015d", v);
    }

    public static String machineTraceId(String machineId, String deviceId) {
        String joined = machineId + deviceId;
        if (joined.length() >= 16) return joined.substring(joined.length() - 16);
        StringBuilder sb = new StringBuilder();
        for (int i = joined.length(); i < 16; i++) sb.append('0');
        return sb.append(joined).toString();
    }

    /** Authorization page URL. Base params are sorted; the tail keeps the client's own order. */
    public static String buildLoginUrl(String machineId, String deviceId) {
        TreeMap<String, String> base = new TreeMap<>();
        base.put("auth_from", "solo");
        base.put("auth_type", "local");
        base.put("client_id", CLIENT_ID);
        base.put("login_channel", "native_ide");
        base.put("login_version", "1");
        base.put("plugin_version", PLUGIN_VERSION);
        base.put("redirect", "0");
        StringBuilder sb = new StringBuilder(CONSOLE_HOST).append("/authorization?");
        boolean first = true;
        for (Map.Entry<String, String> e : base.entrySet()) {
            if (!first) sb.append('&');
            first = false;
            sb.append(e.getKey()).append('=').append(enc(e.getValue()));
        }
        LinkedHashMap<String, String> tail = new LinkedHashMap<>();
        tail.put("login_trace_id", machineTraceId(machineId, deviceId));
        tail.put("auth_callback_url", "http://127.0.0.1:" + CALLBACK_PORT + CALLBACK_PATH);
        tail.put("machine_id", machineId);
        tail.put("device_id", deviceId);
        tail.put("x_device_id", deviceId);
        tail.put("x_machine_id", machineId);
        tail.put("x_device_brand", "PC");
        tail.put("x_device_type", "PC");
        tail.put("x_os_version", "1.0");
        tail.put("x_app_version", PLUGIN_VERSION);
        tail.put("x_app_type", "stable");
        for (Map.Entry<String, String> e : tail.entrySet()) {
            sb.append('&').append(e.getKey()).append('=').append(enc(e.getValue()));
        }
        return sb.toString();
    }

    static String enc(String v) {
        try {
            return URLEncoder.encode(v, "UTF-8");
        } catch (Exception e) {
            return v;
        }
    }

    /* --------------------------------------------------------------- account */

    public static final class Account {
        public String accessToken = "";
        public String refreshToken = "";
        public String uid = "";
        public String nickname = "";
        public String enterpriseId = "";
        public long expiresAt = 0L;
        public String machineId = "";
        public String deviceId = "";

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
            a.uid = Proto.optString(o, "uid", "");
            a.nickname = Proto.optString(o, "nickname", "");
            a.enterpriseId = Proto.optString(o, "enterprise_id", "");
            Integer exp = Proto.optInt(o, "expires_at");
            a.expiresAt = exp == null ? 0L : exp.longValue();
            a.machineId = Proto.optString(o, "machine_id", "");
            a.deviceId = Proto.optString(o, "device_id", "");
            return a;
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("access_token", accessToken);
                o.put("refresh_token", refreshToken);
                o.put("uid", uid);
                o.put("nickname", nickname);
                o.put("enterprise_id", enterpriseId);
                o.put("expires_at", expiresAt);
                o.put("machine_id", machineId);
                o.put("device_id", deviceId);
            } catch (Exception ignored) {
            }
            return o;
        }

        public boolean usable() {
            return !accessToken.isEmpty();
        }
    }

    /** Pulls the credential fields out of the loopback callback. */
    public static Account parseCallback(String rawUrl) throws Exception {
        String query = rawUrl.contains("?") ? rawUrl.substring(rawUrl.indexOf('?') + 1) : rawUrl;
        Map<String, String> params = new HashMap<>();
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String k = dec(pair.substring(0, eq));
            String v = dec(pair.substring(eq + 1).replace("+", "%20"));
            params.put(k, v);
        }
        Account acc = new Account();
        acc.refreshToken = params.getOrDefault("refreshToken", "");
        JSONObject userInfo = jsonParam(params.get("userInfo"));
        JSONObject userJwt = jsonParam(params.get("userJwt"));
        if (acc.refreshToken.isEmpty() && userJwt != null) {
            acc.refreshToken = Proto.optString(userJwt, "RefreshToken", "");
        }
        if (acc.refreshToken.isEmpty()) {
            if (userJwt == null) throw new IllegalStateException("回调里既没有 refreshToken 也没有 userJwt");
            acc.accessToken = Proto.optString(userJwt, "Token", "");
            Integer exp = Proto.optInt(userJwt, "TokenExpireAt");
            if (exp != null && exp > 0) acc.expiresAt = exp > 100000000000L ? exp / 1000 : exp;
        }
        if (userInfo != null) {
            acc.uid = Proto.optString(userInfo, "UserID", "");
            acc.nickname = Proto.optString(userInfo, "ScreenName", "");
            acc.enterpriseId = Proto.optString(userInfo, "TenantID", "");
        }
        if (acc.uid.isEmpty()) acc.uid = params.getOrDefault("user_id", "");
        return acc;
    }

    static JSONObject jsonParam(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        try {
            return new JSONObject(raw);
        } catch (Exception ignored) {
        }
        try {
            return new JSONObject(dec(raw));
        } catch (Exception e) {
            return null;
        }
    }

    static String dec(String v) {
        try {
            return java.net.URLDecoder.decode(v, "UTF-8");
        } catch (Exception e) {
            return v;
        }
    }

    /* --------------------------------------------------------------- headers */

    static Map<String, String> soloHeaders(Account a, boolean stream) {
        Map<String, String> h = new HashMap<>();
        h.put("Content-Type", "application/json");
        h.put("Accept", stream ? "text/event-stream" : "application/json");
        h.put("User-Agent", "Trae/" + IDE_VERSION);
        h.put("Authorization", "Cloud-IDE-JWT " + a.accessToken);
        h.put("X-Cloudide-Token", a.accessToken);
        h.put("X-Ide-Token", a.accessToken);
        if (!a.uid.isEmpty()) h.put("X-Uid", a.uid);
        h.put("X-App-Id", APP_ID);
        h.put("X-App-Version", "default");
        h.put("X-Ide-Version", IDE_VERSION);
        h.put("X-Ide-Version-Code", IDE_VERSION_CODE);
        h.put("X-App-Version-Code", IDE_VERSION_CODE);
        h.put("X-Ide-Version-Type", "stable");
        h.put("X-Device-Type", "windows");
        h.put("X-OS-Version", OS_VERSION);
        h.put("X-Device-Brand", DEVICE_BRAND);
        h.put("Request-Traffic-Type", "prod");
        if (!a.machineId.isEmpty()) h.put("X-Machine-Id", a.machineId);
        if (!a.deviceId.isEmpty()) h.put("x-device-id", a.deviceId);
        return h;
    }

    static Map<String, String> ugHeaders(Account a) {
        Map<String, String> h = new HashMap<>();
        h.put("Content-Type", "application/json");
        h.put("Accept", "application/json");
        h.put("User-Agent", "Trae/" + IDE_VERSION);
        h.put("Authorization", "Cloud-IDE-JWT " + a.accessToken);
        h.put("X-User-Region", "CN");
        if (!a.uid.isEmpty()) h.put("X-Uid", a.uid);
        h.put("x-device-brand", DEVICE_BRAND);
        h.put("x-device-type", "windows");
        h.put("x-os-version", OS_VERSION);
        h.put("x-app-version", IDE_VERSION);
        if (!a.deviceId.isEmpty()) h.put("x-device-id", a.deviceId);
        return h;
    }

    static Map<String, String> oauthHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("Content-Type", "application/json");
        h.put("Accept", "application/json");
        h.put("User-Agent", "Trae/" + IDE_VERSION);
        return h;
    }

    /* ------------------------------------------------------------ token upkeep */

    static Account exchangeToken(Account acc) throws Exception {
        if (acc.refreshToken.isEmpty()) throw new IllegalStateException("缺少 refreshToken，需要重新登录");
        JSONObject body = new JSONObject();
        body.put("ClientID", CLIENT_ID);
        body.put("RefreshToken", acc.refreshToken);
        body.put("ClientSecret", "-");
        body.put("UserID", "");
        JSONObject resp = new JSONObject(Upstreams.postJson(
                (acc.accessToken.isEmpty() ? OAUTH_HOST : OAUTH_HOST) + EP_EXCHANGE, oauthHeaders(), body));
        JSONObject result = resp.optJSONObject("Result");
        if (result == null) throw new IllegalStateException("ExchangeToken 响应无法解析：" + Upstreams.shortenPublic(resp.toString()));
        Account out = Account.fromJson(acc.toJson());
        out.accessToken = Proto.optString(result, "Token", "");
        String rotated = Proto.optString(result, "RefreshToken", "");
        if (!rotated.isEmpty()) out.refreshToken = rotated;
        Integer expireAt = Proto.optInt(result, "TokenExpireAt");
        Integer duration = Proto.optInt(result, "TokenExpireDuration");
        long now = System.currentTimeMillis() / 1000;
        if (expireAt != null && expireAt > 0) {
            out.expiresAt = expireAt > 100000000000L ? expireAt / 1000 : expireAt;
        } else if (duration != null && duration > 0) {
            out.expiresAt = now + duration;
        } else {
            out.expiresAt = now + 12 * 3600;
        }
        if (out.accessToken.isEmpty()) throw new IllegalStateException("ExchangeToken 未返回 Token，需要重新登录");
        return out;
    }

    /**
     * Single refresh entry point with a hard cooldown.
     *
     * The upstream rotates refresh_token on every exchange, so refreshing per request is both slow
     * (the user sees "waiting to connect") and dangerous (rapid rotation can invalidate the account).
     */
    private static final java.util.Map<String, Long> LAST_REFRESH = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long REFRESH_COOLDOWN_SECONDS = 120L;

    /**
     * 拉取 SOLO 池的模型目录。
     *
     * 上游不提供 OpenAI 风格的 /models，模型目录来自：
     *   GET {AGENT_HOST}/api/ide/v1/get_detail_param?function=solo_work_lite
     * 返回体里的 config_info_list 就是可用的 config_name 清单
     * （对话时作为 config_name 下发；不在该清单里的名字会被 4001 拒绝）。
     *
     * 实测：未认证时该端点返回 {"code":1001,"config_info_list":[],...}，
     * 说明端点本身存在、结构就是这个——认证通过后清单会填满。
     */
    public static List<String> fetchModels(Config.Provider p) {
        List<String> out = new ArrayList<>();
        try {
            Account a = ensureFresh(p);
            Map<String, String> h = soloHeaders(a, false);
            Http.Resp r = Http.call("GET",
                    AGENT_HOST + "/api/ide/v1/get_detail_param?function=" + FUNCTION,
                    h, null, "application/json", 8000, 20000, false);
            if (r.code >= 400) {
                Log.w("trae 模型清单失败：HTTP " + r.code);
                return out;
            }
            JSONObject o = new JSONObject(r.body);
            int code = o.optInt("code", 0);
            if (code != 0) {
                Log.w("trae 模型清单业务码 " + code + "：" + Proto.optString(o, "message", ""));
                return out;
            }
            JSONArray arr = o.optJSONArray("config_info_list");
            for (int i = 0; arr != null && i < arr.length(); i++) {
                Object it = arr.opt(i);
                String name = "";
                if (it instanceof JSONObject) {
                    JSONObject m = (JSONObject) it;
                    name = Proto.optString(m, "config_name", "");
                    if (name.isEmpty()) name = Proto.optString(m, "configName", "");
                    if (name.isEmpty()) name = Proto.optString(m, "name", "");
                    if (name.isEmpty()) name = Proto.optString(m, "id", "");
                } else if (it != null) {
                    name = String.valueOf(it);
                }
                if (!name.isEmpty() && !out.contains(name)) out.add(name);
            }
            Log.i("trae 模型 " + out.size() + " 个");
        } catch (Throwable t) {
            Log.w("trae 模型清单异常：" + t);
        }
        return out;
    }

    public static Account ensureFresh(Config.Provider p) throws Exception {
        // 原来是 static synchronized（类级锁）：一个账号走网络刷新 token 时，
        // 同 Provider 的其他账号全被堵住。现在按账号加锁，各刷各的。
        synchronized (FreshLocks.forKey(p.name)) {
            return ensureFreshInner(p);
        }
    }

    private static Account ensureFreshInner(Config.Provider p) throws Exception {
        Account acc = Account.fromJson(p.credentials);
        if (!acc.usable() && acc.refreshToken.isEmpty()) throw new IllegalStateException("Trae 账号未登录，请先点「登录 Trae」");
        long now = System.currentTimeMillis() / 1000;
        if (acc.usable() && acc.expiresAt > 0 && now + 300 < acc.expiresAt) return acc;
        String key = p.id == null ? p.name : p.id;
        Long last = LAST_REFRESH.get(key);
        if (last != null && now - last.longValue() < REFRESH_COOLDOWN_SECONDS) {
            if (acc.usable()) return acc;
            throw new IllegalStateException("凭据刚刷新过，请稍后再试");
        }
        LAST_REFRESH.put(key, now);
        Log.i("trae token 临期，刷新中");
        Account fresh;
        try {
            fresh = exchangeToken(acc);
        } catch (Exception e) {
            LAST_REFRESH.remove(key);
            throw e;
        }
        // never let a bad expiry value turn into a refresh loop
        if (fresh.expiresAt <= now + 60) fresh.expiresAt = now + 3600;
        p.credentials = fresh.toJson().toString();
        Gateway.persistSoon();
        Log.i("trae token 已刷新并回存，有效期至 " + fresh.expiresAt);
        return fresh;
    }

    public static Account completeProfile(Account acc) {
        try {
            JSONObject body = new JSONObject();
            body.put("ReqSource", "IDE");
            body.put("UserID", acc.uid);
            JSONObject resp = new JSONObject(Upstreams.postJson(OAUTH_HOST + EP_USER_INFO, oauthHeaders(), body));
            JSONObject result = resp.optJSONObject("Result");
            if (result != null) {
                Account out = Account.fromJson(acc.toJson());
                if (out.uid.isEmpty()) out.uid = Proto.optString(result, "UserID", Proto.optString(result, "UserId", ""));
                if (out.nickname.isEmpty()) out.nickname = Proto.optString(result, "ScreenName", Proto.optString(result, "NickName", ""));
                if (out.enterpriseId.isEmpty()) out.enterpriseId = Proto.optString(result, "TenantID", "");
                return out;
            }
        } catch (Exception e) {
            Log.w("trae 用户信息获取失败：" + e);
        }
        return acc;
    }

    /* -------------------------------------------------------------- chat body */

    static JSONObject buildBody(String model, Proto.Req req) throws Exception {
        String configName = model == null || model.isEmpty() ? "default" : model;
        JSONObject body = new JSONObject();
        body.put("stream", true);
        body.put("function", FUNCTION);
        body.put("config_name", configName);
        body.put("model", configName);
        JSONArray messages = new JSONArray();
        if (!req.systemText().isEmpty()) {
            messages.put(message("system", req.systemText(), null));
        }
        for (Proto.Msg m : req.messages) {
            StringBuilder text = new StringBuilder();
            JSONArray toolCalls = new JSONArray();
            for (Proto.Part part : m.parts) {
                if (Proto.Part.TEXT.equals(part.type)) text.append(part.text == null ? "" : part.text);
                else if (Proto.Part.TOOL_RESULT.equals(part.type)) text.append(part.text == null ? "" : part.text);
                else if (Proto.Part.TOOL_CALL.equals(part.type)) {
                    JSONObject fn = new JSONObject();
                    fn.put("name", part.toolName == null ? "" : part.toolName);
                    fn.put("arguments", part.argsJson == null ? "{}" : part.argsJson);
                    JSONObject call = new JSONObject();
                    call.put("id", part.toolCallId == null ? "" : part.toolCallId);
                    call.put("function_call", fn);
                    toolCalls.put(call);
                }
            }
            messages.put(message(m.role, text.toString(), toolCalls.length() > 0 ? toolCalls : null));
        }
        body.put("messages", messages);
        if (req.temperature != null) body.put("temperature", req.temperature.doubleValue());
        if (req.topP != null) body.put("top_p", req.topP.doubleValue());
        if (req.maxTokens != null) body.put("max_tokens", req.maxTokens.intValue());
        if (!req.tools.isEmpty()) {
            JSONArray tools = new JSONArray();
            for (Proto.Tool t : req.tools) {
                JSONObject fn = new JSONObject();
                fn.put("name", t.name);
                fn.put("description", t.description == null ? "" : t.description);
                // upstream wants the schema as a JSON string
                fn.put("parameters", (t.schema == null ? new JSONObject().put("type", "object") : t.schema).toString());
                tools.put(new JSONObject().put("type", "function").put("function", fn));
            }
            body.put("tools", tools);
            body.put("tool_choice", "auto");
        }
        return body;
    }

    static JSONObject message(String role, String text, JSONArray toolCalls) throws Exception {
        JSONObject m = new JSONObject();
        m.put("role", role);
        JSONArray content = new JSONArray();
        content.put(new JSONObject().put("type", "text").put("text", text == null ? "" : text));
        m.put("content", content);
        if (toolCalls != null) m.put("tool_calls", toolCalls);
        return m;
    }

    static final class Up implements Proto.Upstream {
        private final Config.Provider p;

        Up(Config.Provider p) {
            this.p = p;
        }

        @Override
        public void chat(String model, Proto.Req req, Proto.Sink sink) throws Exception {
            Account acc = ensureFresh(p);
            JSONObject body = buildBody(model, req);
            final boolean[] sawAnything = {false};
            Proto.Sink probe = new Proto.Sink() {
                @Override
                public void text(String delta) {
                    sawAnything[0] = true;
                    sink.text(delta);
                }

                @Override
                public void thinking(String delta) {
                    sawAnything[0] = true;
                    sink.thinking(delta);
                }

                @Override
                public void toolCall(String id, String name, String argsJson) {
                    sawAnything[0] = true;
                    sink.toolCall(id, name, argsJson);
                }

                @Override
                public void finish(String stopReason, Proto.Usage usage) {
                    sink.finish(stopReason, usage);
                }
            };
            traeStream(AGENT_HOST + EP_CHAT, soloHeaders(acc, true), body, probe);
            if (!sawAnything[0]) Log.w("trae 本轮没有产生任何内容，原始流可能换了结构");
        }
    }

    /**
     * Trae does not speak OpenAI chunks: it emits named SSE events.
     *   event:output      data:{"response":"…","reasoning_content":"…","tool_calls":[…]}
     *   event:token_usage data:{"prompt_tokens":…,"completion_tokens":…}
     *   event:done        data:{"finish_reason":"stop"}
     */
    static void traeStream(String endpoint, Map<String, String> headers, JSONObject body, Proto.Sink sink) throws Exception {
        final Proto.Usage usage = new Proto.Usage();
        final String[] finish = {""};
        final TreeMap<Integer, JSONObject> toolAcc = new TreeMap<>();
        final String[] event = {""};

        Upstreams.postStream(endpoint, headers, body, line -> {
            String t = line == null ? "" : line.trim();
            if (t.isEmpty() || t.startsWith(":")) return;
            if (t.startsWith("event:")) {
                event[0] = t.substring(6).trim();
                return;
            }
            if (!t.startsWith("data:")) return;
            String data = t.substring(5).trim();
            if (data.isEmpty() || "[DONE]".equals(data)) return;
            JSONObject ev;
            try {
                ev = new JSONObject(data);
            } catch (Exception e) {
                return;
            }
            String name = event[0];
            if ("output".equals(name) || name.isEmpty()) {
                String text = Proto.optString(ev, "response", "");
                if (!text.isEmpty()) sink.text(text);
                String think = Proto.optString(ev, "reasoning_content", "");
                if (!think.isEmpty()) sink.thinking(think);
                JSONArray calls = ev.optJSONArray("tool_calls");
                if (calls != null) {
                    for (int i = 0; i < calls.length(); i++) {
                        JSONObject call = calls.optJSONObject(i);
                        if (call == null) continue;
                        Integer idxObj = Proto.optInt(call, "index");
                        int idx = idxObj == null ? i : idxObj.intValue();
                        JSONObject acc = toolAcc.get(idx);
                        if (acc == null) {
                            acc = new JSONObject();
                            acc.put("id", "");
                            acc.put("name", "");
                            acc.put("args", "");
                            toolAcc.put(idx, acc);
                        }
                        String id = Proto.optString(call, "id", "");
                        if (!id.isEmpty()) acc.put("id", id);
                        JSONObject fn = call.optJSONObject("function_call");
                        if (fn == null) fn = call.optJSONObject("function");
                        if (fn != null) {
                            String fname = Proto.optString(fn, "name", "");
                            if (!fname.isEmpty()) acc.put("name", fname);
                            String args = Proto.optString(fn, "arguments",
                                    Proto.optString(fn, "partial_arguments", ""));
                            if (!args.isEmpty()) acc.put("args", Proto.optString(acc, "args", "") + args);
                        }
                    }
                }
            } else if ("token_usage".equals(name)) {
                Upstreams.applyUsage(usage, ev, "prompt_tokens", "completion_tokens", "cache_read_input_tokens");
            } else if ("done".equals(name)) {
                String fr = Proto.optString(ev, "finish_reason", "");
                if (!fr.isEmpty()) finish[0] = fr;
            }
        });

        for (JSONObject acc : toolAcc.values()) {
            String id = Proto.optString(acc, "id", "");
            if (id.isEmpty()) id = "call_" + Math.abs(System.nanoTime() % 1000000);
            String fname = Proto.optString(acc, "name", "");
            if (fname.isEmpty()) continue;
            sink.toolCall(id, fname, Proto.optString(acc, "args", "{}"));
        }
        sink.finish(Proto.canonicalStop(finish[0], !toolAcc.isEmpty()), usage);
    }

    /* --------------------------------------------------------- check-in & fund */

    static JSONObject ugCall(Account acc, String path, String method) throws Exception {
        if ("GET".equals(method)) {
            return new JSONObject(Upstreams.getWithHeaders(UG_HOST + path, ugHeaders(acc)));
        }
        return new JSONObject(Upstreams.postJson(UG_HOST + path, ugHeaders(acc), new JSONObject()));
    }

    /** Daily check-in; the upstream answers "already" in its business code rather than an error. */
    public static JSONObject checkin(Config.Provider p) throws Exception {
        Account acc = ensureFresh(p);
        JSONObject out = new JSONObject();
        JSONObject status = ugCall(acc, EP_CHECKIN_STATUS, "POST");
        out.put("statusRaw", Upstreams.shortenPublic(status.toString()));
        boolean already = containsString(status.toString(), "已签到") || containsString(status.toString(), "already");
        if (already) {
            out.put("already", true);
            out.put("ok", false);
            return out;
        }
        JSONObject claim = ugCall(acc, EP_CHECKIN_CLAIM, "POST");
        out.put("claimRaw", Upstreams.shortenPublic(claim.toString()));
        out.put("ok", true);
        return out;
    }

    static boolean containsString(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase().contains(needle.toLowerCase());
    }

    /** Remaining SOLO credits: usage_summary.total_amount, else the sum of the pack limits. */
    public static double credits(Config.Provider p) throws Exception {
        Account acc = ensureFresh(p);
        JSONObject o = ugCall(acc, EP_ENT_USAGE_WEB, "POST");
        Double fromWeb = extractCredits(o);
        if (fromWeb != null) return fromWeb.doubleValue();
        // some accounts only expose the IDE-side view of the same data
        try {
            JSONObject alt = ugCall(acc, EP_ENT_USAGE, "POST");
            Double fromIde = extractCredits(alt);
            if (fromIde != null) return fromIde.doubleValue();
            Log.i("trae 两个额度接口都没有数字，账号可能确实没有额度包");
        } catch (Exception e) {
            Log.w("trae ide 侧额度查询失败：" + e.getMessage());
        }
        return -1;
    }

    static final String EP_ENT_USAGE = "/trae/api/v2/pay/ide_user_ent_usage";

    /** Pulls a remaining-credit number out of either shape, or null when the account has none. */
    static Double extractCredits(JSONObject o) {
        if (o == null) return null;
        JSONObject summary = o.optJSONObject("usage_summary");
        if (summary != null) {
            Double total = Proto.optDouble(summary, "total_amount");
            if (total != null && total.doubleValue() > 0) {
                // the client shows what is LEFT, not the total: total - consumed
                Double consumed = Proto.optDouble(summary, "consumed_amount");
                double left = total.doubleValue() - (consumed == null ? 0 : consumed.doubleValue());
                return left > 0 ? left : 0;
            }
        }
        double sum = 0;
        boolean any = false;
        JSONArray packs = o.optJSONArray("user_entitlement_pack_list");
        for (int i = 0; packs != null && i < packs.length(); i++) {
            JSONObject pack = packs.optJSONObject(i);
            if (pack == null) continue;
            JSONObject base = pack.optJSONObject("entitlement_base_info");
            JSONObject quota = base == null ? null : base.optJSONObject("quota");
            Double limit = quota == null ? null : Proto.optDouble(quota, "credits_limit");
            if (limit != null) {
                sum += limit.doubleValue();
                any = true;
            }
        }
        if (any) return sum;
        double walked = findRemaining(o);
        return walked >= 0 ? walked : null;
    }

    /** Walks the response for a plausible remaining-credits number. */
    static double findRemaining(Object node) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            for (String key : new String[]{"remain", "remaining", "available", "left", "balance", "credits"}) {
                Double v = Proto.optDouble(o, key);
                if (v != null && v.doubleValue() >= 0) return v.doubleValue();
            }
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                double v = findRemaining(o.opt(it.next()));
                if (v >= 0) return v;
            }
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                double v = findRemaining(arr.opt(i));
                if (v >= 0) return v;
            }
        }
        return -1;
    }

    public static JSONObject registerProvider(JSONObject accountJson) throws Exception {
        Account acc = Account.fromJson(accountJson);
        if (!acc.usable()) {
            acc = exchangeToken(acc);
        }
        acc = completeProfile(acc);
        Config cfg = Gateway.config();
        Config.Provider target = null;
        for (Config.Provider p : cfg.providers) {
            if (!Config.TYPE_TRAE.equals(p.type)) continue;
            Account ex = Account.fromJson(p.credentials);
            if (!acc.uid.isEmpty() && acc.uid.equals(ex.uid)) {
                target = p;
                break;
            }
        }
        boolean created = target == null;
        if (created) {
            target = new Config.Provider();
            target.type = Config.TYPE_TRAE;
            target.id = Config.newProviderId("tr");
            String who = acc.nickname.isEmpty() ? acc.uid : acc.nickname;
            target.name = Config.nextAccountName("Trae", target.type);
            target.baseUrl = AGENT_HOST;
            cfg.providers.add(target);
        }
        if (target.credentials != null && !target.credentials.isEmpty()) {
            Account old = Account.fromJson(target.credentials);
            if (acc.machineId.isEmpty()) acc.machineId = old.machineId;
            if (acc.deviceId.isEmpty()) acc.deviceId = old.deviceId;
        }
        if (acc.machineId.isEmpty()) acc.machineId = randomHex(32);
        if (acc.deviceId.isEmpty()) acc.deviceId = randomDeviceId();
        target.credentials = acc.toJson().toString();
        target.enabled = true;
        target.lastError = "";
        target.lastErrorAt = 0;
        target.badModels.clear();
        int modelCount = target.models.size();
        if (target.models.isEmpty()) {
            // 不塞默认清单：拉不到就空着，别把猜测当事实
            modelCount = target.models.size();
        }
        Gateway.saveNow(Gateway.context());
        JSONObject out = new JSONObject();
        out.put("provider", target.name);
        out.put("created", created);
        out.put("models", modelCount);
        return out;
    }
}
