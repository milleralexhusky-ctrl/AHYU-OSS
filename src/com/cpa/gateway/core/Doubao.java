package com.cpa.gateway.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 豆包（Doubao）账号通道：用你自己 App / 网页登录的会话，把免费对话额度接到网关上。
 *
 * 认证就是站点 cookie（sessionid + sid_guard 等），对话端点是 www.doubao.com 的
 * /samantha/chat/completion：请求体是字节自家的 sentEvent 结构（messages[].content_type=2001 +
 * content={"text":…}、completion_option、evaluate_option、两个 local id），响应是 SSE，帧内
 * event_data.message.content_type 决定这段是什么内容：
 *
 *   2001 / 2003 / 10000  正文（content.text）
 *   2008                 深度思考分片：content.think 进思考、content.text 进正文
 *   10040                思考标题
 *   2002                 推荐问题（忽略）
 *
 * 实测（真实账号，2026-10-10）：一次问答 9 帧、正文落在 2001、返回内容正确。
 * 注意：豆包客户端模型不支持 function calling，所以这条通道只适合对话/写作。
 */
public final class Doubao {

    static final String BASE = "https://www.doubao.com";
    static final String CHAT = "/samantha/chat/completion";
    static final String PC_VERSION = "2.1.7";
    static final String RUNTIME_VERSION = "3.5.4";
    static final String VERSION_CODE = "20800";
    static final String CHROME = "131.0.0.0";

    public static final String MODEL_CHAT = "doubao";
    public static final String MODEL_THINK = "doubao-think";
    public static final String[] MODELS = {MODEL_CHAT, MODEL_THINK};

    private Doubao() {}

    /* ---------------------------------------------------------- 抖音扫码登录 */

    /*
     * 流程（来自 doubao2api/doubao2api/qr_login.py，已用 curl 逐段验证过）：
     *   ① GET https://www.doubao.com/                     → 收集 ttwid 等 cookie
     *   ② GET /passport/safe/csrf_token/?aid=497858       → passport_csrf_token
     *   ③ GET /passport/web/get_qrcode/?next=…&aid=…      → data.token + data.qrcode(base64 PNG)
     *      轮询 GET /passport/web/check_qrconnect/?next=…&token=…&aid=…
     *        data.status: new → scanned → confirmed（带 redirect_url）
     *   ④ 跟随 redirect_url → 拿到 sessionid，凑成完整 cookie
     */

    private static final String QR_AID = "497858";
    private static final String QR_HOME = "https://www.doubao.com";
    private static final String QR_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /** 申请一张二维码：返回 { token, csrf, cookies, png }。 */
    public static JSONObject qrStart() throws Exception {
        java.util.LinkedHashMap<String, String> jar = new java.util.LinkedHashMap<>();
        httpGet(QR_HOME + "/", jar, null);
        String csrf = jar.containsKey("passport_csrf_token") ? jar.get("passport_csrf_token") : "";
        if (csrf.isEmpty()) {
            String b = httpGet(QR_HOME + "/passport/safe/csrf_token/?aid=" + QR_AID, jar, null);
            csrf = pick(b, "passport_csrf_token");
            if (csrf.isEmpty()) csrf = jar.containsKey("passport_csrf_token") ? jar.get("passport_csrf_token") : "";
        }
        java.util.LinkedHashMap<String, String> h = new java.util.LinkedHashMap<>();
        if (!csrf.isEmpty()) h.put("x-tt-passport-csrf-token", csrf);
        String body = httpGet(QR_HOME + "/passport/web/get_qrcode/?next=" + urlEnc(QR_HOME)
                + "&aid=" + QR_AID, jar, h);
        JSONObject o = new JSONObject(body);
        JSONObject d = o.optJSONObject("data");
        if (d == null) throw new IllegalStateException("豆包没给二维码：" + Upstreams.shortenPublic(body));
        String token = d.optString("token", "");
        if (token.isEmpty()) throw new IllegalStateException("二维码响应里没有 token：" + Upstreams.shortenPublic(body));
        String png = d.optString("qrcode", d.optString("qrcode_url", ""));
        if (png.startsWith("data:")) png = png.substring(png.indexOf(',') + 1);
        JSONObject out = new JSONObject();
        out.put("token", token);
        out.put("csrf", csrf);
        out.put("cookies", joinCookies(jar));
        out.put("png", png);
        // 只记长度，不打内容——即使是前 8 位也属于凭据的一部分。
        // 与本项目其它日志的脱敏口径保持一致（deepseek / kimi 等也都不打 token 片段）。
        Log.i("doubao 二维码已取到（token 长度 " + (token == null ? 0 : token.length()) + "）");
        return out;
    }

    /** 轮询扫码状态：返回 { status, redirect }（status: new/scanned/confirmed）。 */
    public static JSONObject qrPoll(String token, String csrf, String cookies) throws Exception {
        java.util.LinkedHashMap<String, String> jar = parseCookies(cookies);
        java.util.LinkedHashMap<String, String> h = new java.util.LinkedHashMap<>();
        if (csrf != null && !csrf.isEmpty()) h.put("x-tt-passport-csrf-token", csrf);
        String body = httpGet(QR_HOME + "/passport/web/check_qrconnect/?next=" + urlEnc(QR_HOME)
                + "&token=" + urlEnc(token) + "&aid=" + QR_AID, jar, h);
        JSONObject d = new JSONObject(body).optJSONObject("data");
        JSONObject out = new JSONObject();
        out.put("status", d == null ? "" : d.optString("status", ""));
        out.put("redirect", d == null ? "" : d.optString("redirect_url", ""));
        out.put("cookies", joinCookies(jar));
        out.put("description", d == null ? "" : d.optString("description", ""));
        return out;
    }

    /** 确认完成后跟随 redirect，拿到带 sessionid 的完整 cookie。 */
    public static String qrFinish(String redirectUrl, String cookies) throws Exception {
        if (redirectUrl == null || redirectUrl.isEmpty()) return cookies;
        java.util.LinkedHashMap<String, String> jar = parseCookies(cookies);
        httpGet(redirectUrl, jar, null);
        String full = joinCookies(jar);
        Log.i("doubao 扫码完成：sessionid=" + (jar.containsKey("sessionid") ? "有" : "无"));
        return full;
    }

    private static String joinCookies(java.util.LinkedHashMap<String, String> jar) {
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : jar.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private static java.util.LinkedHashMap<String, String> parseCookies(String cookies) {
        java.util.LinkedHashMap<String, String> jar = new java.util.LinkedHashMap<>();
        if (cookies == null) return jar;
        for (String part : cookies.split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            jar.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
        }
        return jar;
    }

    /** 手动跟随跳转的 GET（要收全每一跳的 Set-Cookie）。 */
    private static String httpGet(String url, java.util.LinkedHashMap<String, String> jar,
                                  java.util.LinkedHashMap<String, String> extra) throws Exception {
        String cur = url;
        for (int hop = 0; hop < 6; hop++) {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(cur).openConnection();
            c.setRequestMethod("GET");
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", QR_UA);
            c.setRequestProperty("Accept", "*/*");
            String ck = joinCookies(jar);
            if (!ck.isEmpty()) c.setRequestProperty("Cookie", ck);
            if (extra != null) {
                for (java.util.Map.Entry<String, String> e : extra.entrySet()) {
                    c.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            int code = c.getResponseCode();
            java.util.List<String> setCookies = c.getHeaderFields().get("Set-Cookie");
            if (setCookies != null) {
                for (String sc : setCookies) {
                    int semi = sc.indexOf(';');
                    String kv = semi > 0 ? sc.substring(0, semi) : sc;
                    int eq = kv.indexOf('=');
                    if (eq > 0) jar.put(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
                }
            }
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                if (loc != null && !loc.isEmpty()) {
                    cur = loc.startsWith("http") ? loc : new java.net.URL(new java.net.URL(cur), loc).toString();
                    continue;
                }
            }
            java.io.InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            StringBuilder sb = new StringBuilder();
            if (in != null) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
                in.close();
            }
            return sb.toString();
        }
        return "";
    }

    private static String pick(String body, String key) {
        if (body == null) return "";
        int i = body.indexOf("\"" + key + "\"");
        if (i < 0) return "";
        int colon = body.indexOf(':', i);
        if (colon < 0) return "";
        int q1 = body.indexOf('"', colon + 1);
        if (q1 < 0) return "";
        int q2 = body.indexOf('"', q1 + 1);
        return q2 > q1 ? body.substring(q1 + 1, q2) : "";
    }

    private static String urlEnc(String s) {
        try {
            return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    public static final class Account {
        public String cookie = "";
        public String name = "";
        public String uid = "";

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
            a.cookie = Proto.optString(o, "cookie", "");
            a.name = Proto.optString(o, "name", Proto.optString(o, "nickname", ""));
            a.uid = Proto.optString(o, "uid", "");
            return a;
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("cookie", cookie);
                o.put("name", name);
                o.put("uid", uid);
            } catch (Exception ignored) {
            }
            return o;
        }

        public boolean usable() {
            return cookie != null && cookie.contains("sessionid=");
        }
    }

    static String cookieValue(String cookie, String key) {
        if (cookie == null) return "";
        for (String part : cookie.split(";")) {
            String s = part.trim();
            if (s.startsWith(key + "=")) return s.substring(key.length() + 1);
        }
        return "";
    }

    static Map<String, String> headers(Account acc, boolean stream) {
        Map<String, String> h = new HashMap<>();
        h.put("Cookie", acc.cookie == null ? "" : acc.cookie.trim().replace("\n", "").replace("\r", ""));
        h.put("Accept", stream ? "text/event-stream" : "application/json");
        h.put("Content-Type", "application/json");
        h.put("Agw-Js-Conv", "str");
        h.put("Origin", BASE);
        h.put("Referer", BASE + "/chat");
        h.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/" + CHROME + " Safari/537.36");
        String csrf = cookieValue(acc.cookie, "passport_csrf_token");
        if (!csrf.isEmpty()) h.put("x-tt-passport-csrf-token", csrf);
        return h;
    }

    static String query() {
        String did = UUID.randomUUID().toString();
        StringBuilder sb = new StringBuilder();
        String[][] kv = {
                {"aid", "582478"}, {"real_aid", "582478"},
                {"device_id", did}, {"tea_uuid", did}, {"web_id", did},
                {"device_platform", "web"}, {"language", "zh"}, {"region", "CN"}, {"sys_region", "CN"},
                {"pkg_type", "release_version"}, {"version_code", VERSION_CODE},
                {"pc_version", PC_VERSION}, {"client_platform", "pc_client"},
                {"runtime", "web"}, {"runtime_version", RUNTIME_VERSION},
                {"samantha_web", "1"}, {"use-olympus-account", "1"},
        };
        try {
            for (int i = 0; i < kv.length; i++) {
                if (i > 0) sb.append('&');
                sb.append(kv[i][0]).append('=').append(URLEncoder.encode(kv[i][1], "UTF-8"));
            }
        } catch (Exception ignored) {
        }
        return sb.toString();
    }

    static String flatten(Proto.Req req) {
        StringBuilder sb = new StringBuilder();
        int users = 0;
        for (Proto.Msg m : req.messages) if ("user".equals(m.role)) users++;
        String sys = req.systemText();
        if (users == 1 && sys.isEmpty()) {
            for (Proto.Msg m : req.messages) {
                if ("user".equals(m.role)) return m.textBuffer().toString();
            }
        }
        if (!sys.isEmpty()) sb.append("[系统指令]\n").append(sys).append("\n\n");
        for (Proto.Msg m : req.messages) {
            String text = m.textBuffer().toString();
            if (text.isEmpty()) continue;
            String label = "user".equals(m.role) ? "用户" : ("assistant".equals(m.role) ? "助手" : "系统");
            sb.append(label).append("：").append(text).append("\n");
        }
        return sb.toString().trim();
    }

    static String sentEvent(String text, boolean deepThink) throws Exception {
        JSONObject content = new JSONObject();
        content.put("text", text);
        JSONObject message = new JSONObject();
        message.put("content", content.toString());
        message.put("content_type", 2001);
        message.put("attachments", new JSONArray());
        message.put("references", new JSONArray());
        JSONArray messages = new JSONArray();
        messages.put(message);

        JSONObject option = new JSONObject();
        option.put("is_regen", false);
        option.put("with_suggest", false);
        option.put("need_create_conversation", true);
        option.put("launch_stage", 1);
        option.put("is_replace", false);
        option.put("is_delete", false);
        option.put("is_ai_playground", false);
        option.put("memory_type", 2);
        option.put("message_from", 0);
        option.put("use_deep_think", deepThink);
        option.put("use_auto_cot", false);
        option.put("resend_for_regen", false);
        option.put("enable_commerce_credit", false);

        JSONObject evaluate = new JSONObject();
        evaluate.put("web_ab_params", "");

        JSONObject out = new JSONObject();
        out.put("messages", messages);
        out.put("completion_option", option);
        out.put("evaluate_option", evaluate);
        out.put("local_conversation_id", UUID.randomUUID().toString());
        out.put("local_message_id", UUID.randomUUID().toString());
        return out.toString();
    }

    public static JSONObject check(Account acc) throws Exception {
        Map<String, String> h = headers(acc, false);
        h.put("Accept", "application/json");
        JSONObject resp = new JSONObject(Upstreams.getWithHeaders(
                "https://api-normal.doubao.com/passport/account/info/v2/?aid=482431", h));
        JSONObject data = resp.optJSONObject("data");
        if (data == null) throw new IllegalStateException("豆包账号校验失败：" + Upstreams.shortenPublic(resp.toString()));
        JSONObject out = new JSONObject();
        out.put("name", Proto.optString(data, "name", Proto.optString(data, "nickname", "")));
        out.put("uid", Proto.optString(data, "uid", Proto.optString(data, "user_id", "")));
        out.put("avatar", Proto.optString(data, "avatar_url", ""));
        return out;
    }

    public static final class Up implements Proto.Upstream {
        private final Config.Provider p;

        Up(Config.Provider p) {
            this.p = p;
        }

        @Override
        public void chat(String model, Proto.Req req, Proto.Sink sink) throws Exception {
            Account acc = Account.fromJson(p.credentials);
            if (!acc.usable()) throw new IllegalStateException("豆包账号未登录，请先点「登录豆包」");
            boolean deepThink = model != null && model.toLowerCase().contains("think");
            String text = flatten(req);
            final Proto.Usage usage = new Proto.Usage();
            final boolean[] done = {false};
            final String[] failure = {""};
            Map<String, String> h = headers(acc, true);
            String url = BASE + CHAT + "?" + query();
            Upstreams.postStream(url, h, new JSONObject(sentEvent(text, deepThink)), line -> {
                String trimmed = line == null ? "" : line.trim();
                if (trimmed.isEmpty()) return;
                if (!trimmed.startsWith("data:")) {
                    if (trimmed.startsWith("event: gateway-error")) failure[0] = "gateway-error";
                    return;
                }
                JSONObject frame;
                try {
                    frame = new JSONObject(trimmed.substring(5).trim());
                } catch (Exception e) {
                    return;
                }
                Object ed = frame.opt("event_data");
                if (ed instanceof String) {
                    try {
                        ed = new JSONObject((String) ed);
                    } catch (Exception e) {
                        return;
                    }
                }
                if (!(ed instanceof JSONObject)) return;
                Object msg = ((JSONObject) ed).opt("message");
                if (msg instanceof String) {
                    try {
                        msg = new JSONObject((String) msg);
                    } catch (Exception e) {
                        return;
                    }
                }
                if (!(msg instanceof JSONObject)) return;
                JSONObject m = (JSONObject) msg;
                Integer ctObj = Proto.optInt(m, "content_type");
                int ct = ctObj == null ? 0 : ctObj.intValue();
                JSONObject c = null;
                Object raw = m.opt("content");
                if (raw instanceof String) {
                    try {
                        c = new JSONObject((String) raw);
                    } catch (Exception ignored) {
                    }
                } else if (raw instanceof JSONObject) {
                    c = (JSONObject) raw;
                }
                String body = "";
                String think = "";
                if (c != null) {
                    if (ct == 2001 || ct == 2003 || ct == 10000) body = Proto.optString(c, "text", "");
                    else if (ct == 2008) {
                        think = Proto.optString(c, "think", "");
                        body = Proto.optString(c, "text", "");
                    } else if (ct == 10040) {
                        think = Proto.optString(c, "think", Proto.optString(c, "title",
                                Proto.optString(c, "finish_title", "")));
                    }
                }
                if (!think.isEmpty()) sink.thinking(think);
                if (!body.isEmpty()) {
                    sink.text(body);
                    usage.output += Math.max(1, body.length() / 2);
                    if ("gateway-error".equals(failure[0])) failure[0] = "";
                }
                Integer status = Proto.optInt((JSONObject) ed, "status");
                if (ct == 2003 || (status != null && status.intValue() == 2)) {
                    if (!done[0]) {
                        done[0] = true;
                        sink.finish("stop", usage);
                    }
                }
            });
            if (!done[0]) {
                if (!failure[0].isEmpty()) throw new IllegalStateException("豆包上游报错：" + failure[0]);
                // 一个字都没吐（风控/上游静默的常见形态）：抛出去让上层换号重试，
                // 而不是把"空回答"交给用户——在客户端看起来那就是"AI 坏了"。
                if (usage.output <= 0 && usage.input <= 0) {
                    throw new IllegalStateException("豆包没有返回任何内容（可能是风控或上游静默）");
                }
                sink.finish("stop", usage);
            }
        }
    }

    public static List<String> fetchModels(Config.Provider p) {
        List<String> out = new ArrayList<>();
        for (String m : MODELS) out.add(m);
        return out;
    }

    public static JSONObject registerProvider(JSONObject accountJson) throws Exception {
        Account acc = Account.fromJson(accountJson);
        if (!acc.usable()) throw new IllegalStateException("没有 sessionid，cookie 不完整");
        JSONObject info;
        try {
            info = check(acc);
        } catch (Exception e) {
            throw new IllegalStateException("cookie 校验失败：" + Upstreams.shortenPublic(String.valueOf(e.getMessage())));
        }
        if (acc.name.isEmpty()) acc.name = Proto.optString(info, "name", "");
        if (acc.uid.isEmpty()) acc.uid = Proto.optString(info, "uid", "");

        Config cfg = Gateway.config();
        Config.Provider target = null;
        String sid = cookieValue(acc.cookie, "sessionid");
        for (Config.Provider p : cfg.providers) {
            if (!Config.TYPE_DOUBAO.equals(p.type)) continue;
            Account ex = Account.fromJson(p.credentials);
            if (!sid.isEmpty() && sid.equals(cookieValue(ex.cookie, "sessionid"))) {
                target = p;
                break;
            }
        }
        boolean created = target == null;
        if (created) {
            target = new Config.Provider();
            target.type = Config.TYPE_DOUBAO;
            target.id = Config.newProviderId("db");
            target.name = Config.nextAccountName("Doubao", target.type);
            target.baseUrl = BASE;
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
        out.put("who", acc.name);
        out.put("models", target.models.size());
        return out;
    }
}
