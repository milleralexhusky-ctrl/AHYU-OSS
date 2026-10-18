package com.cpa.gateway.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * WorkBuddy 成长任务执行器。
 *
 * 与官方客户端一致地"做任务"：先 accept，再通过行为事件上报累计进度，最后 claim 领奖。
 * 接口常量集中在本类顶部，上游改版时只改这里；每个任务单独一个方法，互不影响。
 *
 * 端点（实测确认）：
 *   chat 域 (copilot.tencent.com)：growth / tasks / buddy / streak / chat/completions
 *   billing 域 (www.codebuddy.cn)：/v2/report
 */
public final class WbTaskRunner {

    private WbTaskRunner() {}

    /* ------------------------------------------------------------ 接口常量 */

    static final String CHAT_BASE = "https://copilot.tencent.com";
    static final String BILL_BASE = "https://www.codebuddy.cn";
    /** 领奖走 Web 域，与上报/接取不同；这是领奖能成功的关键。 */
    static final String WEB_BASE  = "https://www.workbuddy.cn";

    static final String PATH_LIST_TASKS      = "/v2/activity/growth/tasks";
    static final String PATH_ACCEPT_TASKS    = "/v2/activity/growth/tasks/accept";
    /**
     * 领奖：任务码在路径里（不是 body），且必须走 Web 域。
     * POST {WEB_BASE}/activity/growth/tasks/<task_code>/claim
     */
    static final String PATH_CLAIM_REWARD    = "/activity/growth/tasks/%s/claim";
    static final String PATH_BUDDY_INFO      = "/activity/growth/buddy/info";
    static final String PATH_BUDDY_FIRST     = "/activity/growth/buddy/first";
    static final String PATH_BUDDY_AGREEMENT = "/activity/growth/buddy/agreement";
    static final String PATH_STREAK          = "/activity/growth/streak";
    static final String PATH_REDEEM          = "/activity/growth/redeem";
    static final String PATH_LOTTERY_SUMMARY = "/activity/growth/lottery/summary";
    static final String PATH_LOTTERY_DRAW    = "/activity/growth/lottery/draw";
    /** 旅行目的地：古镇客栈（4 个地点收益/时长相同，无最优解）。 */
    static final int TRAVEL_LOCATION = 4;

    static final String PATH_TRAVEL_STATUS   = "/activity/growth/buddy/travel/status";
    static final String PATH_TRAVEL_DEPART   = "/activity/growth/buddy/travel/depart";
    static final String PATH_TRAVEL_CLAIM    = "/activity/growth/buddy/travel/claim";
    static final String PATH_REPORT          = "/v2/report";
    static final String PATH_CHAT            = "/v2/chat/completions";

    /** 客户端标识，上游按此识别为官方 CLI。 */
    static final String CLIENT_UA = "CLI/2.63.2 CodeBuddy/2.63.2";

    /** 上报间隔（秒），匹配上游限速口径。 */
    private static final double REPORT_GAP = 1.05;

    /* ------------------------------------------------------- 基础请求封装 */

    static JSONObject chatCall(Config.Provider p, String path, String method, JSONObject body) throws Exception {
        WorkBuddy.Account acc = WorkBuddy.ensureFresh(p);
        String base = WorkBuddy.primaryBase(acc.realm, p.baseUrl);
        Map<String, String> headers = WorkBuddy.headers(acc, base, CLIENT_UA);
        if ("GET".equals(method)) {
            return new JSONObject(Upstreams.getWithHeaders(base + path, headers));
        }
        JSONObject resp = Upstreams.postJsonAllowingError(base + path, headers, body);
        JSONObject parsed = resp.optJSONObject("body");
        return parsed == null ? new JSONObject() : parsed;
    }

    private static String billBase(WorkBuddy.Account acc) {
        return (acc != null && WorkBuddy.REALM_GLOBAL.equalsIgnoreCase(acc.realm))
                ? "https://www.workbuddy.ai" : BILL_BASE;
    }

    private static String webBase(WorkBuddy.Account acc) {
        return (acc != null && WorkBuddy.REALM_GLOBAL.equalsIgnoreCase(acc.realm))
                ? "https://www.workbuddy.ai" : WEB_BASE;
    }

    static JSONObject billCall(Config.Provider p, String path, JSONArray body) throws Exception {
        WorkBuddy.Account acc = WorkBuddy.ensureFresh(p);
        String base = billBase(acc);
        Map<String, String> headers = WorkBuddy.headers(acc, base, CLIENT_UA);
        JSONObject resp = Upstreams.postRawAllowingError(base + path, headers, body.toString());
        JSONObject parsed = resp.optJSONObject("body");
        return parsed == null ? new JSONObject() : parsed;
    }

    /* ---------------------------------------------------------- 任务读写 */

    public static List<JSONObject> listTasks(Config.Provider p) throws Exception {
        JSONObject o = chatCall(p, PATH_LIST_TASKS, "GET", null);
        List<JSONObject> out = new ArrayList<>();
        JSONObject data = o.optJSONObject("data");
        JSONArray arr = data == null ? null : data.optJSONArray("tasks");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject t = arr.optJSONObject(i);
                if (t != null) out.add(t);
            }
        }
        return out;
    }

    public static JSONObject taskStatus(Config.Provider p, String code) throws Exception {
        for (JSONObject t : listTasks(p)) {
            if (code.equals(Proto.optString(t, "task_code", ""))) return t;
        }
        return null;
    }

    static JSONObject accept(Config.Provider p, JSONArray codes) throws Exception {
        JSONObject body = new JSONObject();
        body.put("task_codes", codes);
        return chatCall(p, PATH_ACCEPT_TASKS, "POST", body);
    }

    /**
     * 领取任务奖励。任务码拼在路径里，无 body，必须走 Web 域并带 web 客户端标识。
     */
    public static JSONObject claim(Config.Provider p, String code) throws Exception {
        WorkBuddy.Account acc = WorkBuddy.ensureFresh(p);
        String base = webBase(acc);
        Map<String, String> h = WorkBuddy.headers(acc, base, CLIENT_UA);
        h.put("x-client-platform", "web");
        h.put("Origin", base);
        h.put("Referer", base + "/");
        String path = String.format(PATH_CLAIM_REWARD, code);
        JSONObject resp = Upstreams.postJsonAllowingError(base + path, h, new JSONObject());
        JSONObject parsed = resp.optJSONObject("body");
        return parsed == null ? new JSONObject() : parsed;
    }

    /* ------------------------------------------------------- 行为事件上报 */

    /**
     * 构造 chat_request_send 事件。字段形状必须与官方客户端一致，
     * 特别是 userId；缺失时服务端返回 200 但静默丢弃。
     */
    static JSONObject chatEvent(WorkBuddy.Account acc, String modelId, String modelName) throws Exception {
        long now = System.currentTimeMillis();
        String cid = "task-" + now;
        JSONObject e = new JSONObject();
        e.put("eventCode", "chat_request_send");
        e.put("timestamp", now);
        e.put("reportDelay", 0);
        e.put("mode", "craft");
        e.put("conversationId", cid);
        e.put("requestId", cid);
        e.put("inputLength", 12);
        e.put("requestModelId", modelId);
        e.put("requestModelName", modelName);
        e.put("isPlan", false);
        e.put("isAutoExecuteTerminal", false);
        e.put("isAutoModify", false);
        e.put("codebaseEnable", false);
        e.put("maxToken", 0);
        e.put("maxSteps", 0);
        e.put("temperature", 0);
        e.put("maxRetries", 0);
        e.put("mentionContexts", new JSONArray());
        e.put("knowledgeId", new JSONArray());
        e.put("knowledgeName", new JSONArray());
        e.put("codebaseId", "");
        e.put("mentionContextCount", 0);
        e.put("command", "");
        e.put("expertId", "");
        e.put("recommendId", "");
        e.put("skillId", "");
        e.put("skillCount", 0);
        e.put("totalCount", 0);
        e.put("fileUri", "");
        e.put("presentAt", now);
        e.put("traceId", "");
        e.put("rootRequestId", cid);
        e.put("parentConversationId", cid);
        e.put("agentName", "default");
        e.put("agentType", "conversation");
        e.put("userId", acc.uid);
        return e;
    }

    /** 上报 count 条 chat_request_send，返回成功条数。 */
    static int reportActivity(Config.Provider p, int count, String modelId, String modelName) throws Exception {
        WorkBuddy.Account acc = WorkBuddy.ensureFresh(p);
        int ok = 0;
        for (int i = 0; i < count; i++) {
            JSONArray arr = new JSONArray();
            arr.put(chatEvent(acc, modelId, modelName));
            try {
                JSONObject r = billCall(p, PATH_REPORT, arr);
                if (r.optInt("code", -1) == 0) ok++;
            } catch (Exception ignored) {
            }
            if (i < count - 1) Thread.sleep((long) (REPORT_GAP * 1000));
        }
        return ok;
    }

    /* ------------------------------------------------------------ 任务动作 */

    /** chat_5：和 AI 聊天 5 次。按当前进度补足上报。 */
    public static JSONObject doChat5(Config.Provider p) throws Exception {
        return fillProgress(p, "chat_5", 5, "deepseek-v4-flash", "DeepSeek V4 Flash");
    }

    /** Model_chat_GLM5.2：真实对话一次 + 上报，触发进度。 */
    public static JSONObject doModelChat(Config.Provider p) throws Exception {
        JSONObject out = new JSONObject();
        out.put("task", "Model_chat_GLM5.2");
        JSONObject st = taskStatus(p, "Model_chat_GLM5.2");
        if (st == null) { out.put("skipped", "任务不存在"); return out; }
        if (isDone(st)) { out.put("skipped", "已完成"); return out; }

        JSONArray codes = new JSONArray();
        codes.put("Model_chat_GLM5.2");
        try { accept(p, codes); } catch (Exception ignored) {}
        Thread.sleep(1000);

        // 真实对话一次
        try {
            WorkBuddy.Account acc = WorkBuddy.ensureFresh(p);
            String base = WorkBuddy.primaryBase(acc.realm, p.baseUrl);
            Map<String, String> h = WorkBuddy.headers(acc, base, CLIENT_UA);
            h.put("Accept", "text/event-stream");
            JSONObject body = new JSONObject();
            body.put("model", "glm-5.2");
            JSONArray msgs = new JSONArray();
            JSONObject m = new JSONObject();
            m.put("role", "user");
            m.put("content", "hi");
            msgs.put(m);
            body.put("messages", msgs);
            body.put("stream", true);
            body.put("max_tokens", 32);
            Upstreams.postJson(base + PATH_CHAT, h, body);
            out.put("chat", "ok");
        } catch (Exception e) {
            out.put("chatError", String.valueOf(e.getMessage()));
        }

        int ok = reportActivity(p, 1, "glm-5.2", "GLM-5.2");
        out.put("reported", ok);
        Thread.sleep(1000);
        out.put("progress", progressOf(taskStatus(p, "Model_chat_GLM5.2")));
        return out;
    }

    /** first_buddy：上报一次 → 同意协议 → 领取首只 Buddy。 */
    public static JSONObject doFirstBuddy(Config.Provider p) throws Exception {
        JSONObject out = new JSONObject();
        out.put("task", "first_buddy");
        JSONObject st = taskStatus(p, "first_buddy");
        if (st == null) { out.put("skipped", "任务不存在"); return out; }
        if ("claimed".equals(Proto.optString(st, "accept_status", ""))) {
            out.put("skipped", "已领取");
            return out;
        }

        int ok = reportActivity(p, 1, "deepseek-v4-flash", "DeepSeek V4 Flash");
        out.put("reported", ok);
        Thread.sleep(1500);
        try {
            JSONObject ag = new JSONObject();
            ag.put("agree", true);
            chatCall(p, PATH_BUDDY_AGREEMENT, "POST", ag);
            JSONObject bf = chatCall(p, PATH_BUDDY_FIRST, "POST", new JSONObject());
            JSONObject d = bf.optJSONObject("data");
            out.put("msg", Proto.optString(bf, "msg", ""));
            if (d != null) {
                out.put("credit", d.optDouble("credit", 0));
                out.put("energy", d.optDouble("energy", 0));
            }
        } catch (Exception e) {
            out.put("buddyError", String.valueOf(e.getMessage()));
        }
        return out;
    }

    /* ------------------------------------------------------------- 工具 */

    /** 按 progress.current/target 补足上报次数，返回执行摘要。 */
    private static JSONObject fillProgress(Config.Provider p, String code, int defaultTarget,
                                           String modelId, String modelName) throws Exception {
        JSONObject out = new JSONObject();
        out.put("task", code);
        JSONObject st = taskStatus(p, code);
        if (st == null) { out.put("skipped", "任务不存在"); return out; }
        if (isDone(st)) { out.put("skipped", "已完成"); return out; }

        JSONObject prog = st.optJSONObject("progress");
        int cur = prog == null ? 0 : prog.optInt("current", 0);
        int target = prog == null ? defaultTarget : prog.optInt("target", defaultTarget);
        int need = Math.max(0, target - cur);
        if (need <= 0) { out.put("skipped", "进度已满"); return out; }

        JSONArray codes = new JSONArray();
        codes.put(code);
        try { accept(p, codes); } catch (Exception ignored) {}

        int ok = reportActivity(p, need, modelId, modelName);
        out.put("reported", ok);
        out.put("need", need);
        Thread.sleep(1000);
        out.put("progress", progressOf(taskStatus(p, code)));
        return out;
    }

    private static boolean isDone(JSONObject st) {
        String ast = Proto.optString(st, "accept_status", "");
        if ("claimed".equals(ast)) return true;
        JSONObject prog = st.optJSONObject("progress");
        if (prog != null) {
            int cur = prog.optInt("current", 0);
            int target = prog.optInt("target", 0);
            if (target > 0 && cur >= target) return true;
        }
        return false;
    }

    private static String progressOf(JSONObject st) {
        if (st == null) return "?";
        JSONObject prog = st.optJSONObject("progress");
        if (prog == null) return "无进度";
        return prog.optInt("current", 0) + "/" + prog.optInt("target", 0)
                + " (" + Proto.optString(st, "accept_status", "?") + ")";
    }

    /* -------------------------------------------------- 连登 / 抽奖 / 旅行 */

    /** 连登天数与档位状态。 */
    public static JSONObject streak(Config.Provider p) throws Exception {
        return chatCall(p, PATH_STREAK, "GET", null);
    }

    /** 兑换已解锁的连登档位。tier 为档位号。 */
    public static JSONObject redeem(Config.Provider p, Object tier) throws Exception {
        JSONObject body = new JSONObject();
        body.put("tier", tier);
        body.put("client_token", java.util.UUID.randomUUID().toString());
        return chatCall(p, PATH_REDEEM, "POST", body);
    }

    /** 查询剩余抽奖次数。 */
    public static JSONObject lotterySummary(Config.Provider p) throws Exception {
        return chatCall(p, PATH_LOTTERY_SUMMARY, "GET", null);
    }

    /** 抽一次奖。 */
    public static JSONObject lotteryDraw(Config.Provider p) throws Exception {
        JSONObject body = new JSONObject();
        body.put("client_token", java.util.UUID.randomUUID().toString());
        return chatCall(p, PATH_LOTTERY_DRAW, "POST", body);
    }

    /** 查询猫档案。 */
    public static JSONObject buddyInfo(Config.Provider p) throws Exception {
        return chatCall(p, PATH_BUDDY_INFO, "GET", null);
    }

    /** 猫猫旅行：当前状态。 */
    public static JSONObject travelStatus(Config.Provider p) throws Exception {
        return chatCall(p, PATH_TRAVEL_STATUS, "GET", null);
    }

    /** 猫猫旅行：派出去。body 可为 null（不带参数）。 */
    public static JSONObject travelDepart(Config.Provider p, JSONObject body) throws Exception {
        return chatCall(p, PATH_TRAVEL_DEPART, "POST", body == null ? new JSONObject() : body);
    }

    /** 猫猫旅行：到站领奖。body 可带 record_id。 */
    public static JSONObject travelClaim(Config.Provider p, JSONObject body) throws Exception {
        return chatCall(p, PATH_TRAVEL_CLAIM, "POST", body == null ? new JSONObject() : body);
    }

    public static JSONObject travelDepart(Config.Provider p) throws Exception {
        JSONObject body = new JSONObject();
        body.put("location_id", TRAVEL_LOCATION);
        return travelDepart(p, body);
    }

    public static JSONObject travelClaim(Config.Provider p) throws Exception {
        return travelClaim(p, null);
    }
}
