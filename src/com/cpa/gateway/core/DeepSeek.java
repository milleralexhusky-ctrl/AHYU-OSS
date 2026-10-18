package com.cpa.gateway.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DeepSeek account channel: the free chat behind the user's own app/web login.
 *
 * The web API is POST /api/v0/chat/completion and it is guarded by a proof-of-work header. The
 * client asks for a challenge (POST /api/v0/chat/create_pow_challenge), then finds a nonce whose
 * DeepSeekHashV1(prefix + nonce) equals the challenge; prefix is salt + "_" + expire_at + "_".
 * DeepSeekHashV1 is SHA3-256 with the first Keccak-f[1600] round skipped, so Keccak lives here
 * instead of using MessageDigest.
 *
 * Verified against the live service with a real account:
 *  - a challenge requested with target_path /api/v0/chat/completion is accepted for both
 *    /api/v0/chat_session/create and /api/v0/chat/completion
 *  - the stream uses "data:" frames whose "v" is either a plain string delta ("+"、"2"、"FINISHED")
 *    or {response:{fragments:[{type:"RESPONSE"|"THINK", content}]|[]}}
 *  - POST /api/v0/chat_session/delete cleans the session up again, so a gateway request does not
 *    leave a conversation behind in the user's chat list
 */
public final class DeepSeek {

    public static final String API = "https://chat.deepseek.com";
    static final String APP_VERSION = "2.0.0";
    public static final String[] MODELS = {"deepseek-chat", "deepseek-reasoner"};
    private static final String CHALLENGE_PATH = "/api/v0/chat/create_pow_challenge";
    private static final String COMPLETION_PATH = "/api/v0/chat/completion";

    private DeepSeek() {}

    /* ------------------------------------------------------------------ account */

    public static final class Account {
        public String token = "";
        public String uid = "";
        public String email = "";
        public String phone = "";

        public static Account fromJson(String json) {
            try {
                return fromJson(new JSONObject(json == null || json.isEmpty() ? "{}" : json));
            } catch (Exception e) {
                return new Account();
            }
        }

        public static Account fromJson(JSONObject o) {
            Account a = new Account();
            if (o == null) return a;
            a.token = Proto.optString(o, "token", Proto.optString(o, "user_token",
                    Proto.optString(o, "userToken", "")));
            a.uid = Proto.optString(o, "uid", Proto.optString(o, "id", ""));
            a.email = Proto.optString(o, "email", "");
            a.phone = Proto.optString(o, "phone", Proto.optString(o, "mobile_number", ""));
            return a;
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("token", token);
                o.put("uid", uid);
                o.put("email", email);
                o.put("phone", phone);
            } catch (Exception ignored) {
            }
            return o;
        }

        public boolean usable() {
            return token != null && !token.isEmpty();
        }
    }

    /* ------------------------------------------------------------------- keccak */

    private static final long MASK = 0xFFFFFFFFFFFFFFFFL;
    private static final long[] RC = {
            0x0000000000000001L, 0x0000000000008082L, 0x800000000000808AL, 0x8000000080008000L,
            0x000000000000808BL, 0x0000000080000001L, 0x8000000080008081L, 0x8000000000008009L,
            0x000000000000008AL, 0x0000000000000088L, 0x0000000080008009L, 0x000000008000000AL,
            0x000000008000808BL, 0x800000000000008BL, 0x8000000000008089L, 0x8000000000008003L,
            0x8000000000008002L, 0x8000000000000080L, 0x000000000000800AL, 0x800000008000000AL,
            0x8000000080008081L, 0x8000000000008080L, 0x0000000080000001L, 0x8000000080008008L};
    private static final int[] PI = {0, 6, 12, 18, 24, 3, 9, 10, 16, 22, 1, 7, 13, 19, 20,
            4, 5, 11, 17, 23, 2, 8, 14, 15, 21};
    private static final int[] RHO = {0, 44, 43, 21, 14, 28, 20, 3, 45, 61, 1, 6, 25, 8, 18,
            27, 36, 10, 15, 56, 62, 55, 39, 41, 2};

    private static long rotl(long v, int k) {
        return (v << k) | (v >>> (64 - k));
    }

    /** Keccak-f[1600] with rounds 1..23 - round 0 is what DeepSeekHashV1 skips. */
    private static void permute(long[] s) {
        long[] a = new long[25];
        long[] b = new long[25];
        long[] c = new long[5];
        long[] d = new long[5];
        System.arraycopy(s, 0, a, 0, 25);
        for (int round = 1; round < 24; round++) {
            for (int i = 0; i < 5; i++) c[i] = a[i] ^ a[i + 5] ^ a[i + 10] ^ a[i + 15] ^ a[i + 20];
            for (int i = 0; i < 5; i++) d[i] = c[(i + 4) % 5] ^ rotl(c[(i + 1) % 5], 1);
            for (int i = 0; i < 5; i++) {
                for (int j = 0; j < 25; j += 5) a[i + j] ^= d[i];
            }
            for (int i = 0; i < 25; i++) b[i] = rotl(a[PI[i]], RHO[i]);
            for (int j = 0; j < 5; j++) {
                for (int i = 0; i < 5; i++) {
                    a[j * 5 + i] = b[j * 5 + i] ^ ((~b[j * 5 + (i + 1) % 5]) & b[j * 5 + (i + 2) % 5]);
                }
            }
            a[0] ^= RC[round];
        }
        System.arraycopy(a, 0, s, 0, 25);
    }

    /** DeepSeekHashV1 over a byte string (sha3-256 rate, 0x06/0x80 padding, 23 rounds). */
    static byte[] hash(byte[] data) {
        final int rate = 136;
        long[] s = new long[25];
        int off = 0;
        while (off + rate <= data.length) {
            absorb(s, data, off);
            permute(s);
            off += rate;
        }
        byte[] tail = new byte[rate];
        int rem = data.length - off;
        System.arraycopy(data, off, tail, 0, rem);
        tail[rem] = 0x06;
        tail[rate - 1] |= (byte) 0x80;
        absorb(s, tail, 0);
        permute(s);
        byte[] out = new byte[32];
        for (int i = 0; i < 4; i++) {
            long lane = s[i];
            for (int k = 0; k < 8; k++) out[i * 8 + k] = (byte) ((lane >>> (8 * k)) & 0xFF);
        }
        return out;
    }

    /** XOR one rate-sized block into the state. */
    private static void absorb(long[] s, byte[] block, int off) {
        for (int i = 0; i < 17; i++) {
            long lane = 0;
            for (int k = 0; k < 8; k++) lane |= ((long) (block[off + i * 8 + k] & 0xFF)) << (8 * k);
            s[i] ^= lane;
        }
    }

    private static byte[] hexToBytes(String hex) {
        int n = hex.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static boolean same(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) return false;
        }
        return true;
    }

    /** Finds the nonce; returns -1 when the difficulty window is exhausted. */
    static long solve(String salt, long expireAt, String challengeHex, int difficulty) {
        byte[] prefix = (salt + "_" + expireAt + "_").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] target = hexToBytes(challengeHex);
        int limit = difficulty <= 0 ? 1000000 : difficulty;
        for (int nonce = 0; nonce < limit; nonce++) {
            byte[] msg = new byte[prefix.length + 12];
            System.arraycopy(prefix, 0, msg, 0, prefix.length);
            byte[] num = Integer.toString(nonce).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            System.arraycopy(num, 0, msg, prefix.length, num.length);
            byte[] trimmed = new byte[prefix.length + num.length];
            System.arraycopy(msg, 0, trimmed, 0, trimmed.length);
            if (same(hash(trimmed), target)) return nonce;
        }
        return -1;
    }

    /* ------------------------------------------------------------------- headers */

    static Map<String, String> headers(String token) {
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", "Bearer " + token);
        h.put("Content-Type", "application/json");
        h.put("Accept", "text/event-stream, application/json");
        h.put("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
        h.put("x-app-version", APP_VERSION);
        h.put("x-client-platform", "web");
        h.put("x-client-version", APP_VERSION);
        return h;
    }

    /* ----------------------------------------------------------------------- pow */

    /**
     * Challenge -> nonce -> base64 payload for X-DS-PoW-Response.
     *
     * The answer is single-use (a second request with the same one comes back empty), so this always
     * asks for a fresh challenge; solving costs well under a second in Java.
     */
    public static String powHeader(String token, String targetPath) throws Exception {
        JSONObject body = new JSONObject();
        body.put("target_path", COMPLETION_PATH);
        Map<String, String> h = headers(token);
        h.put("Accept", "application/json");
        JSONObject resp = new JSONObject(Upstreams.postJson(API + CHALLENGE_PATH, h, body));
        JSONObject data = resp.optJSONObject("data");
        JSONObject biz = data == null ? null : data.optJSONObject("biz_data");
        JSONObject ch = biz == null ? null : biz.optJSONObject("challenge");
        if (ch == null) throw new IllegalStateException("DeepSeek 未返回 PoW 挑战：" + Upstreams.shortenPublic(resp.toString()));
        String algorithm = Proto.optString(ch, "algorithm", "DeepSeekHashV1");
        String challenge = Proto.optString(ch, "challenge", "");
        String salt = Proto.optString(ch, "salt", "");
        String signature = Proto.optString(ch, "signature", "");
        Double expireObj = Proto.optDouble(ch, "expire_at");
        long expireAt = expireObj == null ? 0L : expireObj.longValue();
        Integer diffObj = Proto.optInt(ch, "difficulty");
        int difficulty = diffObj == null ? 144000 : diffObj.intValue();
        long t0 = System.currentTimeMillis();
        long nonce = solve(salt, expireAt, challenge, difficulty);
        if (nonce < 0) throw new IllegalStateException("DeepSeek PoW 求解失败（难度 " + difficulty + "）");
        JSONObject out = new JSONObject();
        out.put("algorithm", algorithm);
        out.put("challenge", challenge);
        out.put("salt", salt);
        out.put("answer", nonce);
        out.put("signature", signature);
        out.put("target_path", COMPLETION_PATH);
        String b64 = Base64.getEncoder().encodeToString(out.toString().getBytes("UTF-8"));
        Log.i("deepseek PoW：难度 " + difficulty + "，nonce=" + nonce + "，用时 "
                + (System.currentTimeMillis() - t0) + "ms");
        return b64;
    }

    /* --------------------------------------------------------------------- calls */

    /** Account probe: also brings back the uid/email used for the account card. */
    public static JSONObject check(String token) throws Exception {
        Map<String, String> h = headers(token);
        h.put("Accept", "application/json");
        JSONObject resp = new JSONObject(Upstreams.getWithHeaders(API + "/api/v0/users/current", h));
        JSONObject data = resp.optJSONObject("data");
        JSONObject biz = data == null ? null : data.optJSONObject("biz_data");
        if (biz == null) throw new IllegalStateException("DeepSeek 账号校验失败：" + Upstreams.shortenPublic(resp.toString()));
        JSONObject out = new JSONObject();
        out.put("uid", Proto.optString(biz, "id", ""));
        out.put("email", Proto.optString(biz, "email", ""));
        out.put("phone", Proto.optString(biz, "mobile_number", ""));
        return out;
    }

    static String createSession(String token, String pow) throws Exception {
        Map<String, String> h = headers(token);
        h.put("X-DS-PoW-Response", pow);
        h.put("Accept", "application/json");
        JSONObject resp = new JSONObject(Upstreams.postJson(
                API + "/api/v0/chat_session/create", h, new JSONObject()));
        JSONObject data = resp.optJSONObject("data");
        JSONObject biz = data == null ? null : data.optJSONObject("biz_data");
        JSONObject session = biz == null ? null : biz.optJSONObject("chat_session");
        String id = session == null ? "" : Proto.optString(session, "id", "");
        if (id.isEmpty()) throw new IllegalStateException("DeepSeek 会话创建失败：" + Upstreams.shortenPublic(resp.toString()));
        return id;
    }

    static void deleteSession(String token, String pow, String sessionId) {
        try {
            Map<String, String> h = headers(token);
            h.put("X-DS-PoW-Response", pow);
            h.put("Accept", "application/json");
            JSONObject body = new JSONObject();
            body.put("chat_session_id", sessionId);
            Upstreams.postJson(API + "/api/v0/chat_session/delete", h, body);
        } catch (Exception e) {
            Log.w("deepseek 会话清理失败（不影响回答）：" + e);
        }
    }

    /** Canonical messages -> the single "prompt" string the web endpoint takes. */
    static String flatten(Proto.Req req) {
        StringBuilder sb = new StringBuilder();
        String sys = req.systemText();
        if (!sys.isEmpty()) sb.append("[系统指令]\n").append(sys).append("\n\n");
        int userCount = 0;
        for (Proto.Msg m : req.messages) {
            if ("user".equals(m.role)) userCount++;
        }
        for (Proto.Msg m : req.messages) {
            String text = m.textBuffer().toString();
            if (text.isEmpty()) continue;
            if (userCount == 1 && req.systemText().isEmpty() && "user".equals(m.role)) return text;
            String label = "user".equals(m.role) ? "用户" : ("assistant".equals(m.role) ? "助手" : "系统");
            sb.append(label).append("：").append(text).append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * Session used by the next request. Liveness probes ask for an ephemeral request, and they share
     * one long-lived session that is only deleted when it goes stale - otherwise every model check
     * would leave another conversation in the user's own chat list.
     */
    private static final java.util.Map<String, String> SHARED_SESSION_IDS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, Long> SHARED_SESSION_ATS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long SHARED_SESSION_TTL = 3L * 60L * 1000L;

    private static String sharedKeyOf(Account acc) {
        if (acc == null) return "default";
        if (acc.uid != null && !acc.uid.isEmpty()) return acc.uid;
        if (acc.token != null && acc.token.length() > 8) return acc.token.substring(acc.token.length() - 8);
        return "default";
    }

    private static String sessionFor(Account acc, String pow, boolean ephemeral) throws Exception {
        // 非探活：每次建独立会话，不碰共享状态，不加锁（原来类级锁把建会话的网络也串行了）
        if (!ephemeral) return createSession(acc.token, pow);
        // 探活按账号各自共用一个会话：多账号并发探测不会互相串 sessionId
        String skey = sharedKeyOf(acc);
        synchronized (FreshLocks.forKey("deepseek-shared-" + skey)) {
            long now = System.currentTimeMillis();
            String sid = SHARED_SESSION_IDS.get(skey);
            Long sat = SHARED_SESSION_ATS.get(skey);
            if (sid == null || sid.isEmpty() || sat == null || now - sat.longValue() > SHARED_SESSION_TTL) {
                sid = createSession(acc.token, pow);
                SHARED_SESSION_IDS.put(skey, sid);
                SHARED_SESSION_ATS.put(skey, Long.valueOf(now));
            }
            return sid;
        }
    }

    /** The native upstream: one gateway request = one DeepSeek session, cleaned up afterwards. */
    public static final class Up implements Proto.Upstream {
        private final Config.Provider p;

        Up(Config.Provider p) {
            this.p = p;
        }

        @Override
        public void chat(String model, Proto.Req req, Proto.Sink sink) throws Exception {
            Account acc = Account.fromJson(p.credentials);
            if (!acc.usable()) throw new IllegalStateException("DeepSeek 账号未登录，请先点「登录 DeepSeek」");
            final boolean thinking = model != null && model.toLowerCase().contains("reasoner");
            String lastError = "";
            final boolean ephemeral = req.ephemeral;
            for (int attempt = 0; attempt < 2; attempt++) {
                String pow = powHeader(acc.token, COMPLETION_PATH);
                String sessionId = sessionFor(acc, pow, ephemeral);
                final Proto.Usage usage = new Proto.Usage();
                final boolean[] done = {false};
                final boolean[] gotContent = {false};
                final String[] failure = {""};
                try {
                    JSONObject body = new JSONObject();
                    body.put("chat_session_id", sessionId);
                    body.put("parent_message_id", JSONObject.NULL);
                    body.put("prompt", flatten(req));
                    body.put("ref_file_ids", new JSONArray());
                    body.put("thinking_enabled", thinking);
                    body.put("search_enabled", false);
                    Map<String, String> h = headers(acc.token);
                    h.put("X-DS-PoW-Response", pow);
                    Upstreams.postStream(API + COMPLETION_PATH, h, body, line -> {
                        String trimmed = line == null ? "" : line.trim();
                        if (trimmed.startsWith("{")) {
                            // no SSE at all: the service answered with its error envelope
                            try {
                                JSONObject err = new JSONObject(trimmed);
                                failure[0] = Proto.optString(err, "msg",
                                        Proto.optString(err, "message", trimmed));
                            } catch (Exception ignored) {
                                failure[0] = trimmed;
                            }
                            return;
                        }
                        JSONObject chunk = Upstreams.sseData(trimmed);
                        if (chunk == null) return;
                        Object v = chunk.opt("v");
                        if (v instanceof String) {
                            String str = (String) v;
                            if ("FINISHED".equals(str) || "COMPLETE".equals(str)) {
                                if (!done[0]) {
                                    done[0] = true;
                                    sink.finish("stop", usage);
                                }
                            } else if (!"WIP".equals(str)) {
                                gotContent[0] = true;
                                sink.text(str);
                            }
                            return;
                        }
                        if (v instanceof JSONObject) {
                            JSONObject resp = ((JSONObject) v).optJSONObject("response");
                            if (resp == null) return;
                            JSONArray fragments = resp.optJSONArray("fragments");
                            for (int i = 0; fragments != null && i < fragments.length(); i++) {
                                JSONObject f = fragments.optJSONObject(i);
                                if (f == null) continue;
                                String content = Proto.optString(f, "content", "");
                                if (content.isEmpty()) continue;
                                String type = Proto.optString(f, "type", "");
                                if ("THINK".equals(type)) {
                                    gotContent[0] = true;
                                    sink.thinking(content);
                                } else if ("RESPONSE".equals(type)) {
                                    gotContent[0] = true;
                                    sink.text(content);
                                }
                            }
                            Integer used = Proto.optInt(resp, "accumulated_token_usage");
                            if (used != null && used.intValue() > 0) usage.output = used.intValue();
                            if ("FINISHED".equals(Proto.optString(resp, "status", "")) && !done[0]) {
                                done[0] = true;
                                sink.finish("stop", usage);
                            }
                        }
                    });
                } finally {
                    if (!ephemeral) deleteSession(acc.token, pow, sessionId);
                    else maybeRetireSharedSession(acc, pow);
                }
                if (gotContent[0]) {
                    if (!done[0]) sink.finish("stop", usage);
                    return;
                }
                lastError = failure[0].isEmpty() ? "上游没有返回内容" : failure[0];
                Log.w("deepseek 第 " + (attempt + 1) + " 次没有内容，重试：" + lastError);
            }
            String kind = Upstreams.throttleKindFromBody(lastError);
            if (kind != null) {
                int code = "quota".equals(kind) ? 402 : 429;
                throw new Throttled(code, Http.defaultBackoffMs(code), kind, "DeepSeek 限流/繁忙：" + lastError);
            }
            throw new IllegalStateException("DeepSeek 没有返回内容：" + lastError);
        }
    }

    /** Drops the shared probe session once it is old; the gateway is idle at that point. */
    private static void maybeRetireSharedSession(Account acc, String pow) {
        String skey = sharedKeyOf(acc);
        String id;
        synchronized (FreshLocks.forKey("deepseek-shared-" + skey)) {
            String sid = SHARED_SESSION_IDS.get(skey);
            Long sat = SHARED_SESSION_ATS.get(skey);
            if (sid == null || sid.isEmpty() || sat == null) return;
            if (System.currentTimeMillis() - sat.longValue() < SHARED_SESSION_TTL) return;
            id = sid;
            SHARED_SESSION_IDS.remove(skey);
            SHARED_SESSION_ATS.remove(skey);
        }
        deleteSession(acc.token, pow, id);
        Log.i("deepseek 复用会话到期，已清理");
    }

    /* -------------------------------------------------------------------- models */

    public static List<String> fetchModels(Config.Provider p) {
        List<String> out = new ArrayList<>();
        for (String m : MODELS) out.add(m);
        return out;
    }

    /** Adds or refreshes an account channel from a token (import path + in-app login). */
    public static JSONObject registerProvider(JSONObject accountJson) throws Exception {
        Account acc = Account.fromJson(accountJson);
        if (!acc.usable()) throw new IllegalStateException("没有 token，登录没完成");
        JSONObject info;
        try {
            info = check(acc.token);
        } catch (Exception e) {
            throw new IllegalStateException("token 校验失败：" + Upstreams.shortenPublic(String.valueOf(e.getMessage())));
        }
        if (acc.uid.isEmpty()) acc.uid = Proto.optString(info, "uid", "");
        if (acc.email.isEmpty()) acc.email = Proto.optString(info, "email", "");
        if (acc.phone.isEmpty()) acc.phone = Proto.optString(info, "phone", "");

        Config cfg = Gateway.config();
        Config.Provider target = null;
        for (Config.Provider p : cfg.providers) {
            if (!Config.TYPE_DEEPSEEK.equals(p.type)) continue;
            Account ex = Account.fromJson(p.credentials);
            if (!ex.uid.isEmpty() && ex.uid.equals(acc.uid)) {
                target = p;
                break;
            }
        }
        boolean created = target == null;
        if (created) {
            target = new Config.Provider();
            target.type = Config.TYPE_DEEPSEEK;
            target.id = Config.newProviderId("ds");
            target.name = Config.nextAccountName("DeepSeek", target.type);
            target.baseUrl = API;
            cfg.providers.add(target);
        }
        target.credentials = acc.toJson().toString();
        target.enabled = true;
        target.lastError = "";
        target.lastErrorAt = 0L;
        target.badModels.clear();
        target.setModels(fetchModels(target));
        Gateway.saveNow(Gateway.context());
        JSONObject out = new JSONObject();
        out.put("provider", target.name);
        out.put("created", created);
        out.put("email", acc.email);
        out.put("models", target.models.size());
        return out;
    }
}
