package com.cpa.gateway.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 讯飞 Loomy channel: phone + SMS login, points (free quota), and an OpenAI-compatible chat API.
 *
 * Signing follows the official desktop client: stringToSign = METHOD \n path \n query \n contentMD5
 * \n contentType \n date \n nonce \n signedHeaders \n canonicalHeaders (both trailing sections empty),
 * signature = base64(HMAC-SHA1(secret, stringToSign)), Authorization = "account <akId>:<sig>".
 */
public final class Loomy {

    private Loomy() {}

    static final String ACCOUNT_BASE = "https://account.xfinfr.com";
    static final String API_BASE = "https://loomyad.xunfei.cn";
    static final String APP_ID = "GM3LOOMY";
    static final String AK = "2thryby66wxi53sk";
    static final String SK = "zsak6eadrbawz683wf5r3m2snrwj868r";
    static final String CLIENT_VERSION = "0.9.38";
    static final int SMS_EXPIRE = 300;
    static final int SESSION_EXPIRE = 14 * 24 * 3600;

    /* -------------------------------------------------------------- account */

    public static final class Account {
        public String session = "";
        public String uid = "";
        public String nickname = "";
        public String phone = "";
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
            a.session = Proto.optString(o, "session", Proto.optString(o, "token", ""));
            a.uid = Proto.optString(o, "uid", "");
            a.nickname = Proto.optString(o, "nickname", "");
            a.phone = Proto.optString(o, "phone", "");
            Integer exp = Proto.optInt(o, "expires_at");
            a.expiresAt = exp == null ? 0L : exp.longValue();
            return a;
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("session", session);
                o.put("uid", uid.isEmpty() ? phone : uid);
                o.put("nickname", nickname);
                o.put("phone", phone);
                o.put("expires_at", expiresAt);
            } catch (Exception ignored) {
            }
            return o;
        }

        public boolean usable() {
            return !session.isEmpty();
        }
    }

    /* --------------------------------------------------------------- signing */

    static String contentMd5(String body) throws Exception {
        if (body == null || body.isEmpty()) return "";
        MessageDigest md = MessageDigest.getInstance("MD5");
        return Base64.getEncoder().encodeToString(md.digest(body.getBytes("UTF-8")));
    }

    static String httpDate() {
        SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("GMT"));
        return f.format(new Date());
    }

    /** Pure signature function so the self-test can pin it against a fixed vector. */
    static String signature(String method, String path, String body, String date, String nonce) throws Exception {
        String stringToSign = method + "\n" + path + "\n" + "\n" + contentMd5(body) + "\n"
                + "application/json" + "\n" + date + "\n" + nonce + "\n" + "\n";
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(SK.getBytes("UTF-8"), "HmacSHA1"));
        return Base64.getEncoder().encodeToString(mac.doFinal(stringToSign.getBytes("UTF-8")));
    }

    static Map<String, String> accountHeaders(String method, String path, String body) throws Exception {
        String date = httpDate();
        String nonce = UUID.randomUUID().toString();
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", "account " + AK + ":" + signature(method, path, body, date, nonce));
        h.put("Date", date);
        h.put("Nonce", nonce);
        h.put("Content-Type", "application/json");
        String md5 = contentMd5(body);
        if (!md5.isEmpty()) h.put("Content-MD5", md5);
        h.put("Accept", "application/json");
        h.put("User-Agent", "Loomy/" + CLIENT_VERSION);
        return h;
    }

    static Map<String, String> apiHeaders(String session) {
        Map<String, String> h = new HashMap<>();
        h.put("token", session);
        h.put("Authorization", "Bearer " + session);
        h.put("Content-Type", "application/json");
        h.put("Accept", "text/event-stream");
        // the model service hangs on requests without a traceparent header
        h.put("traceparent", "00-" + UUID.randomUUID().toString().replace("-", "")
                + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16) + "-01");
        h.put("loomy-version", CLIENT_VERSION);
        return h;
    }

    /* ------------------------------------------------------- account requests */

    static JSONObject envelope(JSONObject param) throws Exception {
        JSONObject base = new JSONObject();
        base.put("appid", APP_ID);
        base.put("modelid", "Web");
        base.put("version", "1.0.0");
        base.put("devid", "web");
        base.put("ua", "Loomy|Desktop|Electron|macOS");
        base.put("traceid", UUID.randomUUID().toString().replace("-", ""));
        JSONObject out = new JSONObject();
        out.put("base", base);
        out.put("param", param);
        return out;
    }

    static JSONObject accountCall(String path, JSONObject param) throws Exception {
        String body = envelope(param).toString();
        JSONObject resp = Upstreams.postJsonAllowingError(ACCOUNT_BASE + path, accountHeaders("POST", path, body), new JSONObject(body));
        JSONObject parsed = resp.optJSONObject("body");
        if (parsed == null) parsed = new JSONObject();
        String code = Proto.optString(parsed, "code", "");
        JSONObject data = parsed.optJSONObject("data");
        if (data == null) data = new JSONObject();
        if (!code.isEmpty() && !"000000".equals(code)) {
            throw new IllegalStateException("Loomy 账号接口返回 " + code + " " + Proto.optString(parsed, "message", ""));
        }
        return data;
    }

    public static String sendSmsCode(String phone) throws Exception {
        JSONObject param = new JSONObject();
        param.put("ccode", "86");
        param.put("phone", phone);
        param.put("expire", SMS_EXPIRE);
        JSONObject data = accountCall("/login/phone/sendMsgCode", param);
        String msgid = Proto.optString(data, "msgid", Proto.optString(data, "msgId", ""));
        Log.i("loomy 验证码已发送，msgid=" + (msgid.isEmpty() ? "(未返回)" : msgid));
        return msgid;
    }

    public static Account loginBySms(String phone, String code, String msgid) throws Exception {
        JSONObject param = new JSONObject();
        param.put("ccode", "86");
        param.put("phone", phone);
        param.put("mcode", code);
        if (msgid != null && !msgid.isEmpty()) param.put("msgid", msgid);
        param.put("expire", SESSION_EXPIRE);
        JSONObject data = accountCall("/login/phone/checkCode", param);
        Account acc = new Account();
        acc.session = Proto.optString(data, "session", Proto.optString(data, "token",
                Proto.optString(data, "access_token", "")));
        acc.phone = phone;
        acc.uid = Proto.optString(data, "uid", Proto.optString(data, "userid", ""));
        acc.nickname = Proto.optString(data, "nickname", Proto.optString(data, "name", ""));
        acc.expiresAt = System.currentTimeMillis() / 1000 + SESSION_EXPIRE;
        if (!acc.session.isEmpty()) {
            try {
                JSONObject param2 = new JSONObject();
                param2.put("session", acc.session);
                JSONObject info = accountCall("/userinfo/query/baseInfo", param2);
                if (acc.uid.isEmpty()) acc.uid = Proto.optString(info, "uid", Proto.optString(info, "userid", ""));
                if (acc.nickname.isEmpty()) acc.nickname = Proto.optString(info, "nickname", Proto.optString(info, "name", ""));
            } catch (Exception e) {
                Log.w("loomy 用户信息获取失败：" + e);
            }
        }
        if (!acc.usable()) throw new IllegalStateException("登录成功但没拿到 session，原始响应：" + data);
        return acc;
    }

    /* ----------------------------------------------------------- chat upstream */

    static final class Up implements Proto.Upstream {
        private final Config.Provider p;

        Up(Config.Provider p) {
            this.p = p;
        }

        @Override
        public void chat(String model, Proto.Req req, Proto.Sink sink) throws Exception {
            Account acc = Account.fromJson(p.credentials);
            if (!acc.usable()) throw new IllegalStateException("Loomy 账号未登录，请先点「登录 Loomy」");
            String base = p.baseUrl == null || p.baseUrl.isEmpty() ? API_BASE : Upstreams.trimSlash(p.baseUrl);
            String endpoint = base + "/api/v1/chat/completions";
            /* 保真模式（4.50.1）：n>1 / logprobs 的响应原样回传。 */
            if (Upstreams.rawOpenAiIfWanted(req, endpoint, apiHeaders(acc.session), model, sink)) return;
            JSONObject body = Upstreams.openAiBody(model, req, true);
            Upstreams.openAiStream(endpoint, apiHeaders(acc.session), body, sink);
        }
    }

    public static List<String> fetchModels(Config.Provider p) throws Exception {
        Account acc = Account.fromJson(p.credentials);
        if (!acc.usable()) throw new IllegalStateException("Loomy 账号未登录");
        String base = p.baseUrl == null || p.baseUrl.isEmpty() ? API_BASE : Upstreams.trimSlash(p.baseUrl);
        String body = Upstreams.getWithHeaders(base + "/api/v1/models", apiHeaders(acc.session));
        JSONObject o = new JSONObject(body);
        JSONArray arr = o.optJSONArray("data");
        if (arr == null) {
            JSONObject data = o.optJSONObject("data");
            arr = data == null ? null : data.optJSONArray("models");
        }
        List<String> out = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                Object item = arr.opt(i);
                String id;
                if (item instanceof JSONObject) {
                    JSONObject m = (JSONObject) item;
                    id = Proto.optString(m, "id", Proto.optString(m, "name", ""));
                } else {
                    id = String.valueOf(item);
                }
                if (!id.isEmpty() && !out.contains(id)) out.add(id);
            }
        }
        if (out.isEmpty()) throw new IllegalStateException("Loomy 未返回模型列表");
        return out;
    }

    /** Points balance, shown on the account card (personal points first, team balance as fallback). */
    public static double points(Config.Provider p) {
        try {
            Account acc = Account.fromJson(p.credentials);
            if (!acc.usable()) return -1;
            try {
                String rec = Upstreams.getWithHeaders(
                        base(p) + "/api/v1/points/records?pageNo=1&pageSize=20&recordType=all",
                        apiHeaders(acc.session));
                JSONObject ro = new JSONObject(rec);
                JSONObject rd = ro.optJSONObject("data");
                if (rd == null) rd = ro;
                Double personal = Proto.optDouble(rd, "balance");
                if (personal == null) personal = Proto.optDouble(rd, "dailyBalance");
                if (personal != null && personal.doubleValue() >= 0) {
                    Log.i("loomy 个人积分余额=" + personal);
                    return personal.doubleValue();
                }
            } catch (Exception e) {
                Log.w("loomy 积分记录读取失败，退回团队余额：" + e.getMessage());
            }
            String body = Upstreams.getWithHeaders(base(p) + "/api/v1/team-points/balance", apiHeaders(acc.session));
            JSONObject o = new JSONObject(body);
            JSONObject data = o.optJSONObject("data");
            if (data == null) data = o;
            Double v = Proto.optDouble(data, "currentBalance");
            if (v == null) v = Proto.optDouble(data, "balance");
            if (v == null) v = Proto.optDouble(data, "points");
            return v == null ? -1 : v.doubleValue();
        } catch (Exception e) {
            Log.w("Loomy 积分查询失败：" + e);
            return -1;
        }
    }

    /** Daily "pet work" board: points earned today and which tiers are already rewarded. */
    public static JSONObject petWork(Config.Provider p) throws Exception {
        Account acc = Account.fromJson(p.credentials);
        if (!acc.usable()) throw new IllegalStateException("Loomy 账号未登录");
        String body = Upstreams.getWithHeaders(base(p) + "/api/v1/pet-work", apiHeaders(acc.session));
        JSONObject o = new JSONObject(body);
        JSONObject data = o.optJSONObject("data");
        if (data == null) data = o;
        JSONObject status = data.optJSONObject("status");
        if (status == null) status = new JSONObject();
        JSONObject config = data.optJSONObject("config");
        JSONObject out = new JSONObject();
        out.put("code", Proto.optString(o, "code", ""));
        out.put("enabled", data.optBoolean("enabled", false));
        out.put("businessDate", Proto.optString(data, "businessDate", ""));
        out.put("earnedToday", num(status, "earnedPointsToday"));
        out.put("capped", status.optBoolean("dailyRewardCapped", false));
        out.put("rewardedTiers", status.optJSONArray("rewardedTierIds"));
        out.put("dailyMax", config == null ? 0 : num(config, "dailyMaxPoints"));
        return out;
    }

    /**
     * First-login bonus. The upstream grants the registration reward once and answers
     * alreadyProcessed:true afterwards, so calling it on every run stays safe.
     */
    public static JSONObject firstLogin(Config.Provider p) throws Exception {
        Account acc = Account.fromJson(p.credentials);
        if (!acc.usable()) throw new IllegalStateException("Loomy 账号未登录");
        JSONObject resp = Upstreams.postJsonAllowingError(base(p) + "/api/v1/points/first-login",
                apiHeaders(acc.session), new JSONObject());
        JSONObject body = resp.optJSONObject("body");
        if (body == null) body = new JSONObject();
        JSONObject data = body.optJSONObject("data");
        if (data == null) data = new JSONObject();
        JSONObject out = new JSONObject();
        out.put("http", resp.optInt("http", 0));
        out.put("code", Proto.optString(body, "code", ""));
        out.put("already", data.optBoolean("alreadyProcessed", false));
        out.put("reward", num(data, "registerReward"));
        out.put("balance", num(data, "currentBalance"));
        return out;
    }

    static double num(JSONObject o, String key) {
        Double v = Proto.optDouble(o, key);
        return v == null ? 0 : v.doubleValue();
    }

    /** The eight onboarding tasks and what each is worth; totals 10000 points. */
    static final String[][] TASKS = {
            {"first_message", "完成首次对话", "500"},
            {"pick_skill", "选择技能", "1000"},
            {"generate_ppt", "生成 PPT", "1500"},
            {"set_schedule", "设置定时任务", "1000"},
            {"install_skill", "安装技能", "1500"},
            {"configure_remote", "配置远程控制", "1000"},
            {"create_soul", "创建搭子", "1500"},
            {"share_soul", "分享搭子", "2000"}};

    /**
     * Reads the onboarding task progress. The upstream only records whether each action happened;
     * claiming without doing the action is rejected, so this reports honestly instead of farming.
     */
    public static JSONObject taskStatus(Config.Provider p) throws Exception {
        Account acc = Account.fromJson(p.credentials);
        if (!acc.usable()) throw new IllegalStateException("Loomy 账号未登录");
        String body = Upstreams.getWithHeaders(base(p) + "/api/v1/onboarding/tasks", apiHeaders(acc.session));
        JSONObject o = new JSONObject(body);
        JSONObject data = o.optJSONObject("data");
        if (data == null) data = o;
        JSONObject state = data.optJSONObject("tasks");
        if (state == null) state = data;
        int earned = 0;
        int total = 0;
        JSONArray done = new JSONArray();
        JSONArray pending = new JSONArray();
        JSONArray pendingKeys = new JSONArray();
        for (String[] t : TASKS) {
            int points = Integer.parseInt(t[2]);
            total += points;
            boolean completed = state.optBoolean(t[0], false);
            if (completed) {
                earned += points;
                done.put(t[1] + "（+" + points + "）");
            } else {
                pending.put(t[1] + "（+" + points + "）");
                pendingKeys.put(t[0]);
            }
        }
        JSONObject out = new JSONObject();
        out.put("earned", earned);
        out.put("total", total);
        out.put("doneCount", done.length());
        out.put("done", done);
        out.put("pending", pending);
        out.put("pendingKeys", pendingKeys);
        return out;
    }

    /** Claims one task. The upstream validates that the action really happened. */
    public static JSONObject claimTask(Config.Provider p, String key) throws Exception {
        Account acc = Account.fromJson(p.credentials);
        if (!acc.usable()) throw new IllegalStateException("Loomy 账号未登录");
        JSONObject payload = new JSONObject();
        payload.put("key", key);
        Map<String, String> headers = apiHeaders(acc.session);
        headers.put("Content-Type", "application/json");
        JSONObject resp = Upstreams.postJsonAllowingError(base(p) + "/api/v1/onboarding/tasks/complete",
                headers, payload);
        JSONObject body = resp.optJSONObject("body");
        if (body == null) body = new JSONObject();
        JSONObject data = body.optJSONObject("data");
        if (data == null) data = body;
        JSONObject out = new JSONObject();
        out.put("key", key);
        out.put("alreadyCompleted", data.optBoolean("alreadyCompleted", false));
        out.put("balance", data.optDouble("balance", -1));
        out.put("code", Proto.optString(body, "code", ""));
        out.put("message", Proto.optString(body, "message", Proto.optString(body, "msg", "")));
        out.put("http", resp.optInt("http", 0));
        return out;
    }

    /** Human label + reward for one task key. */
    static String[] taskMeta(String key) {
        for (String[] t : TASKS) {
            if (t[0].equals(key)) return t;
        }
        return new String[]{key, key, "0"};
    }

    /**
     * Walks the onboarding board and claims everything the server has not marked done yet.
     *
     * This is the same claim call the desktop client makes; the server answers with the new
     * balance (or alreadyCompleted), so the outcome reported here is the upstream's decision.
     */
    public static JSONObject claimPending(Config.Provider p) throws Exception {
        Account acc = Account.fromJson(p.credentials);
        if (!acc.usable()) throw new IllegalStateException("Loomy 账号未登录");
        JSONObject before = taskStatus(p);
        double balanceBefore = points(p);
        JSONArray keys = before.optJSONArray("pendingKeys");
        JSONArray claimed = new JSONArray();
        JSONArray failed = new JSONArray();
        for (int i = 0; keys != null && i < keys.length(); i++) {
            String key = keys.optString(i, "");
            if (key.isEmpty()) continue;
            String[] meta = taskMeta(key);
            JSONObject row = new JSONObject();
            row.put("key", key);
            row.put("title", meta[1]);
            row.put("reward", Integer.parseInt(meta[2]));
            try {
                JSONObject r = claimTask(p, key);
                boolean already = r.optBoolean("alreadyCompleted", false);
                boolean ok = "000000".equals(r.optString("code", ""));
                row.put("ok", ok);
                row.put("already", already);
                row.put("balance", r.optDouble("balance", -1));
                row.put("message", r.optString("message", ""));
                if (ok && !already) claimed.put(row);
                else failed.put(row);
            } catch (Exception e) {
                row.put("ok", false);
                row.put("message", Upstreams.shortenPublic(String.valueOf(e.getMessage())));
                failed.put(row);
            }
        }
        double balanceAfter = points(p);
        JSONObject out = new JSONObject();
        out.put("claimed", claimed);
        out.put("failed", failed);
        int gainedPoints = 0;
        for (int i = 0; i < claimed.length(); i++) gainedPoints += claimed.optJSONObject(i).optInt("reward", 0);
        out.put("gainedByTasks", gainedPoints);
        out.put("creditBefore", balanceBefore);
        out.put("creditAfter", balanceAfter);
        return out;
    }

    static String base(Config.Provider p) {
        return p.baseUrl == null || p.baseUrl.isEmpty() ? API_BASE : Upstreams.trimSlash(p.baseUrl);
    }

    public static JSONObject registerProvider(JSONObject accountJson) throws Exception {
        Account acc = Account.fromJson(accountJson);
        if (!acc.usable()) throw new IllegalStateException("没有 session，登录没完成");
        Config cfg = Gateway.config();
        Config.Provider target = null;
        for (Config.Provider p : cfg.providers) {
            if (!Config.TYPE_LOOMY.equals(p.type)) continue;
            Account ex = Account.fromJson(p.credentials);
            if (!ex.phone.isEmpty() && ex.phone.equals(acc.phone)) {
                target = p;
                break;
            }
        }
        boolean created = target == null;
        if (created) {
            target = new Config.Provider();
            target.type = Config.TYPE_LOOMY;
            target.id = Config.newProviderId("lo");
            String who = acc.nickname.isEmpty() ? acc.phone : acc.nickname;
            target.name = Config.nextAccountName("Loomy", target.type);
            target.baseUrl = API_BASE;
            cfg.providers.add(target);
        }
        target.credentials = acc.toJson().toString();
        target.enabled = true;
        target.lastError = "";
        target.lastErrorAt = 0;
        target.badModels.clear();
        int modelCount = target.models.size();
        try {
            List<String> models = fetchModels(target);
            target.setModels(models);
            modelCount = target.models.size();
        } catch (Exception e) {
            Log.w("拉取 Loomy 模型失败：" + e);
            if (target.models.isEmpty()) {
                for (String m : new String[]{"loomy-default"}) target.models.add(m);
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
