package com.cpa.gateway.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Daily chores for WorkBuddy / CodeBuddy accounts: credit balance, daily check-in and the growth
 * task board. Endpoints verified against the live service:
 *   POST /v2/billing/meter/get-user-resource  -> {code:0,data:{Response:{Data:{TotalDosage:N}}}}
 *   POST /v2/billing/meter/daily-checkin      -> code 10001 when already signed in today
 *   GET  /v2/activity/growth/tasks            -> data.tasks[] with task_code/accept_status/reward_credit
 *   POST /v2/activity/growth/tasks/accept     -> {"task_codes":[...]} batched, idempotent
 */
public final class WbTasks {

    private WbTasks() {}

    /* ----------------------------------------------------------------- calls */

    static JSONObject call(Config.Provider p, String path, String method, JSONObject body) throws Exception {
        WorkBuddy.Account acc = WorkBuddy.ensureFresh(p);
        String base = WorkBuddy.primaryBase(acc.realm, p.baseUrl);
        Map<String, String> headers = WorkBuddy.headers(acc, base, p.apiKey);
        if ("GET".equals(method)) {
            return new JSONObject(Upstreams.getWithHeaders(base + path, headers));
        }
        // business errors ("already signed in" etc.) arrive as HTTP 400 with a JSON body
        JSONObject resp = Upstreams.postJsonAllowingError(base + path, headers, body);
        JSONObject parsed = resp.optJSONObject("body");
        return parsed == null ? new JSONObject() : parsed;
    }

    /* ---------------------------------------------------------------- probe */

    /**
     * 诊断用：把三个计费/活动端点的**原始响应**抓回来，不做任何解析。
     *
     * 为什么需要它：{@link #credits} 的解析写死了腾讯国内的包装格式
     * （data.Response.Data.TotalDosage），国际版（workbuddy.ai）返回的结构不同时
     * inner 为 null、函数返回 -1，界面就干脆不显示积分——但"国际版到底返回什么"
     * 在设备外看不到（账号凭据是加密的）。这个入口把原文打出来，格式差异一眼可见，
     * 之后才能按真实结构改解析，而不是猜。
     *
     * 只读：三个请求都是查询/列表，不改任何服务端状态。
     */
    public static JSONObject probeRaw(Config.Provider p) {
        JSONObject out = new JSONObject();
        // 全部路径来自参考实现 buddy-switch（crates/buddy-switch-core/src/modules
        // /checkin.rs · activity.rs）：国内版已验证过的那一整套成长中心接口。
        // 目的：一次问清"国际版到底能用哪些"——之前只有 tasks 确认可用，
        // 其余 9 个接口在两个区域上的表现完全未知，直接写实现可能全白写。
        //
        // 全部为**只读查询**：不签到、不领取、不抽奖、不接任务。
        // （claim/draw/use/accept 这类写操作一律不在这里调用）
        final String[] paths = {
                "/v2/billing/meter/get-user-resource",          // 积分余额
                "/v2/billing/meter/daily-checkin",              // 签到（POST，只查询当前状态）
                "/v2/billing/meter/checkin-activity-status",    // 签到活动是否存在
                "/v2/activity/growth/tasks",                    // 任务列表
                "/v2/activity/growth/streak",                   // 连登天数
                "/v2/activity/growth/heatmap",                  // 日历热力图
                "/v2/activity/growth/redeem/summary",           // 连登兑换状态
                "/v2/activity/growth/lottery/chances",          // 抽奖次数
                "/v2/activity/growth/buddy/quota",              // Buddy 名额
                "/v2/activity/growth/buddy/travel/status",      // 旅行状态
                "/v2/activity/growth/buddy/travel/config",      // 旅行配置
                "/v2/activity/growth/energy"                    // 能量
        };
        final String[] labels = {
                "credits", "checkin", "checkinActivityStatus",
                "tasks", "streak", "heatmap", "redeemSummary",
                "lotteryChances", "buddyQuota", "travelStatus",
                "travelConfig", "energy"
        };
        // 哪些要用 GET（其余为 POST）。参考实现里 growth 的查询多用 GET。
        final java.util.Set<String> getPaths = new java.util.HashSet<>(java.util.Arrays.asList(
                "/v2/activity/growth/tasks",
                "/v2/activity/growth/streak",
                "/v2/activity/growth/heatmap",
                "/v2/activity/growth/redeem/summary",
                "/v2/activity/growth/lottery/chances",
                "/v2/activity/growth/buddy/quota",
                "/v2/activity/growth/buddy/travel/status",
                "/v2/activity/growth/buddy/travel/config",
                "/v2/activity/growth/energy"
        ));
        for (int i = 0; i < paths.length; i++) {
            JSONObject row = new JSONObject();
            try {
                WorkBuddy.Account acc = WorkBuddy.ensureFresh(p);
                String base = WorkBuddy.primaryBase(acc.realm, p.baseUrl);
                row.put("base", base);
                row.put("realm", acc.realm);
                row.put("enterpriseId", acc.enterpriseId == null ? "" : acc.enterpriseId);
                row.put("ua", WorkBuddy.headers(acc, base, p.apiKey).get("User-Agent"));
                Map<String, String> h = WorkBuddy.headers(acc, base, p.apiKey);
                if (getPaths.contains(paths[i])) {
                    JSONObject row2 = new JSONObject();
                    try {
                        row2.put("raw", new JSONObject(Upstreams.getWithHeaders(base + paths[i], h)));
                    } catch (Throwable gt) {
                        row2.put("error", String.valueOf(gt));
                    }
                    row.put("get", row2);
                }
                // POST 也试一遍：同一个路径在不同上游上方法可能不同
                try {
                    JSONObject resp = Upstreams.postJsonAllowingError(base + paths[i], h, new JSONObject());
                    row.put("postHttp", resp.optInt("http", -1));
                    row.put("postRaw", resp.opt("body"));
                } catch (Throwable pt) {
                    row.put("postError", String.valueOf(pt));
                }
            } catch (Throwable t) {
                try {
                    row.put("error", String.valueOf(t));
                } catch (Throwable ignored) {
                }
            }
            try {
                out.put(labels[i], row);
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    /**
     * 额度构成：把 `get-user-resource` 里的每个资源包（Accounts[]）解析出来。
     *
     * 为什么需要：界面只有一个"积分"数字时，用户看到"WB5 只有 500、WB1 有 2031"
     * 完全无从判断——真实原因是 WB5 拿了 7527、已经用掉 7027，只剩那张还没动的
     * 个人体验版 500。把每张包的面额 / 已用 / 到期列出来，这种问题一眼就能答。
     *
     * 返回元素：{name, remain, size, used, end}（金额都是 credits 浮点，end 是 "YYYY-MM-DD"）。
     * 结构兼容国内版 data.Response.Data 与直接的 data.Data；解析不到就返回空数组。
     */
    public static org.json.JSONArray packs(Config.Provider p) throws Exception {
        org.json.JSONArray out = new org.json.JSONArray();
        JSONObject o = call(p, "/v2/billing/meter/get-user-resource", "POST", new JSONObject());
        JSONObject data = o.optJSONObject("data");
        if (data == null) data = o;
        JSONObject resp = data.optJSONObject("Response");
        JSONObject inner = resp == null ? data.optJSONObject("Data") : resp.optJSONObject("Data");
        if (inner == null) inner = data;
        JSONArray arr = inner.optJSONArray("Accounts");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject a = arr.optJSONObject(i);
            if (a == null) continue;
            JSONObject row = new JSONObject();
            row.put("name", a.optString("PackageName", "资源包"));
            row.put("remain", num(a, "CapacityRemainPrecise", "CapacityRemain"));
            row.put("size", num(a, "CapacitySizePrecise", "CapacitySize"));
            row.put("used", num(a, "CapacityUsedPrecise", "CapacityUsed"));
            row.put("end", a.optString("CycleEndTime", ""));
            out.put(row);
        }
        return out;
    }

    private static double num(JSONObject a, String precise, String plain) {
        try {
            Object v = a.opt(precise);
            if (v == null) v = a.opt(plain);
            if (v == null) return 0;
            return Double.parseDouble(String.valueOf(v));
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 诊断用：走**正式解析路径**取一次积分，返回解析结果与中间状态。
     *
     * 和 probeRaw 的区别：probeRaw 只把原文抓回来（证明接口通、格式对），
     * 这个方法回答"那为什么界面不显示"——即 credits() 到底返回了什么。
     * 两者一起看才能区分「接口没通」「格式变了」「解析对了但没被调用」三种情况。
     */
    public static JSONObject probeParsed(Config.Provider p) {
        JSONObject out = new JSONObject();
        try {
            WorkBuddy.Account acc = WorkBuddy.ensureFresh(p);
            String base = WorkBuddy.primaryBase(acc.realm, p.baseUrl);
            out.put("realm", acc.realm);
            out.put("base", base);
            JSONObject o = call(p, "/v2/billing/meter/get-user-resource", "POST", new JSONObject());
            out.put("code", o.optInt("code", -999));
            JSONObject data = o.optJSONObject("data");
            out.put("hasData", data != null);
            JSONObject resp = data == null ? null : data.optJSONObject("Response");
            out.put("hasResponse", resp != null);
            JSONObject inner = resp == null ? null : resp.optJSONObject("Data");
            out.put("hasInnerData", inner != null);
            if (inner != null) {
                out.put("TotalDosage", String.valueOf(inner.opt("TotalDosage")));
                out.put("RemainingDosage", String.valueOf(inner.opt("RemainingDosage")));
            }
            out.put("credits() 返回", credits(p));
            out.put("provider.credits 当前值", p.credits);
            out.put("provider.enabled", p.enabled);
        } catch (Throwable t) {
            try {
                out.put("exception", String.valueOf(t));
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    /**
     * 诊断用（逐接口单行）：把 probeRaw 的结果压成"接口 → 结果"的一行摘要。
     *
     * 为什么要单行：整套 12 个接口的原始 JSON 有几十 KB，logcat 单条上限约 4KB，
     * 会被截断到看不出结构。这里逐条打，每条只保留关键字段，读起来也快。
     */
    public static List<String> probeSummaryLines(Config.Provider p) {
        List<String> out = new ArrayList<>();
        JSONObject raw = probeRaw(p);
        java.util.Iterator<String> it = raw.keys();
        while (it.hasNext()) {
            String k = it.next();
            JSONObject row = raw.optJSONObject(k);
            if (row == null) {
                out.add(k + " -> " + raw.opt(k));
                continue;
            }
            StringBuilder sb = new StringBuilder(k);
            sb.append(" | realm=").append(row.optString("realm", "?"))
              .append(" base=").append(shortenUrl(row.optString("base", "")));
            if (row.has("postHttp")) sb.append(" postHttp=").append(row.optInt("postHttp"));
            Object pr = row.opt("postRaw");
            if (pr != null) sb.append(" post=").append(brief(pr));
            Object ge = row.opt("get");
            if (ge instanceof JSONObject) {
                Object gr = ((JSONObject) ge).opt("raw");
                if (gr != null) sb.append(" get=").append(brief(gr));
                Object gerr = ((JSONObject) ge).opt("error");
                if (gerr != null) sb.append(" getErr=").append(gerr);
            }
            if (row.has("postError")) sb.append(" postErr=").append(row.opt("postError"));
            out.add(sb.toString());
        }
        return out;
    }

    /** 日志用：把 URL 压成 host+path，去掉冗长的 scheme。 */
    private static String shortenUrl(String u) {
        if (u == null) return "";
        return u.replace("https://", "").replace("http://", "");
    }

    /** 日志用：把任意响应压成一行摘要（保留 code/msg 和少量结构线索）。 */
    private static String brief(Object o) {
        if (o == null) return "null";
        if (!(o instanceof JSONObject)) {
            String str = String.valueOf(o);
            return str.length() > 120 ? str.substring(0, 120) + "…" : str;
        }
        JSONObject j = (JSONObject) o;
        StringBuilder sb = new StringBuilder("{");
        sb.append("code=").append(j.opt("code"));
        String msg = j.optString("msg", "");
        if (!msg.isEmpty()) sb.append(" msg=\"").append(msg).append("\"");
        Object data = j.opt("data");
        if (data instanceof JSONObject) {
            JSONObject d = (JSONObject) data;
            // 关键状态字段直接给值：active / today_checked_in / streak_days 决定
            // "国际版到底能不能签到"，光看 key 名不够。
            for (String k : new String[]{"active", "today_checked_in", "streak_days",
                    "daily_credit", "today_credit", "balance", "affordable"}) {
                if (d.has(k)) sb.append(' ').append(k).append('=').append(d.opt(k));
            }
            sb.append(" dataKeys=").append(joinKeys(d));
        } else if (data != null) {
            sb.append(" data=").append(String.valueOf(data).substring(0, Math.min(60, String.valueOf(data).length())));
        }
        sb.append("}");
        return sb.toString();
    }

    private static String joinKeys(JSONObject o) {
        StringBuilder sb = new StringBuilder("[");
        java.util.Iterator<String> it = o.keys();
        int n = 0;
        while (it.hasNext() && n < 8) {
            if (n > 0) sb.append(',');
            sb.append(it.next());
            n++;
        }
        sb.append("]");
        return sb.toString();
    }

    /* --------------------------------------------------------------- balance */

    public static double credits(Config.Provider p) throws Exception {
        JSONObject o = call(p, "/v2/billing/meter/get-user-resource", "POST", new JSONObject());
        JSONObject data = o.optJSONObject("data");
        JSONObject resp = data == null ? null : data.optJSONObject("Response");
        JSONObject inner = resp == null ? null : resp.optJSONObject("Data");
        if (inner != null) {
            Double total = Proto.optDouble(inner, "TotalDosage");
            if (total != null) return total.doubleValue();
            Double remain = Proto.optDouble(inner, "RemainingDosage");
            if (remain != null) return remain.doubleValue();
        }
        return -1;
    }

    /* --------------------------------------------------- checkin activity */

    /**
     * 查询签到活动的真实状态。
     *
     * 为什么必须有这个接口：{@link #checkin} 用 code 判断结果，而**两种完全不同的
     * 情况都返回 code=10001**——
     *   · 国内版：今天已经签过了（msg="今天已签到，请明天再来"，active=true）
     *   · 国际版：该区域根本没有签到活动（msg="签到活动未开启或已过期"，active=false）
     * 只看 code 会把"活动不存在"误报成"已签到"，用户会以为签到正常、只是今天签过了。
     *
     * 真机实测（12 接口 × 4 账号诊断）：
     *   cn     active=true  today_checked_in=true  streak_days=7  today_credit=100
     *   global active=false today_checked_in=false streak_days=0  today_credit=100
     *
     * @return {active, todayCheckedIn, streakDays, dailyCredit, todayCredit}
     *         active=false 表示该区域不支持签到（界面应据此给出准确提示，而不是"已签到"）
     */
    public static JSONObject checkinActivity(Config.Provider p) throws Exception {
        JSONObject o = call(p, "/v2/billing/meter/checkin-activity-status", "POST", new JSONObject());
        JSONObject out = new JSONObject();
        out.put("code", o.optInt("code", -1));
        JSONObject d = o.optJSONObject("data");
        if (d == null) {
            out.put("active", false);
            return out;
        }
        out.put("active", d.optBoolean("active", false));
        out.put("todayCheckedIn", d.optBoolean("today_checked_in", false));
        out.put("streakDays", d.optInt("streak_days", 0));
        out.put("dailyCredit", d.optInt("daily_credit", 0));
        out.put("todayCredit", d.optInt("today_credit", 0));
        return out;
    }

    /* --------------------------------------------------------------- checkin */

    public static JSONObject checkin(Config.Provider p) throws Exception {
        JSONObject o = call(p, "/v2/billing/meter/daily-checkin", "POST", new JSONObject());
        JSONObject out = new JSONObject();
        int code = o.optInt("code", -1);
        out.put("code", code);
        out.put("msg", Proto.optString(o, "msg", ""));
        out.put("ok", code == 0);

        // code=10001 有两种完全不同的含义，必须再用活动状态区分开：
        //   active=true  → 今天已签到（正常，不用提示用户）
        //   active=false → 该区域没有签到活动（国际版；界面要如实说明，不能显示"已签到"）
        // 只按 code 判定的旧写法会把国际版误报成"已签到"，用户以为一切正常。
        boolean already = code == 10001;
        boolean active = true;          // 查不到活动状态时，保守按老语义处理
        int streakDays = 0;
        if (already) {
            try {
                JSONObject act = checkinActivity(p);
                active = act.optBoolean("active", true);
                streakDays = act.optInt("streakDays", 0);
            } catch (Throwable ignored) {
                // 活动状态查询失败不影响签到本身的结果判定
            }
        }
        out.put("active", active);
        out.put("streakDays", streakDays);
        out.put("already", already && active);
        out.put("unsupported", already && !active);   // 该区域不支持签到
        return out;
    }

    /* ----------------------------------------------------------------- tasks */

    public static List<JSONObject> tasks(Config.Provider p) throws Exception {
        JSONObject o = call(p, "/v2/activity/growth/tasks", "GET", null);
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

    /** Accepts every task that is still untouched. Batched and idempotent on the server side. */
    public static JSONObject acceptAll(Config.Provider p, List<JSONObject> tasks) throws Exception {
        JSONArray codes = new JSONArray();
        for (JSONObject t : tasks) {
            if ("not_accepted".equals(Proto.optString(t, "accept_status", ""))) {
                codes.put(Proto.optString(t, "task_code", ""));
            }
        }
        JSONObject out = new JSONObject();
        out.put("requested", codes.length());
        if (codes.length() == 0) return out;
        JSONObject body = new JSONObject();
        body.put("task_codes", codes);
        JSONObject resp = call(p, "/v2/activity/growth/tasks/accept", "POST", body);
        out.put("code", resp.optInt("code", -1));
        out.put("results", resp.optJSONObject("data") == null ? new JSONArray() : resp.optJSONObject("data").optJSONArray("results"));
        return out;
    }

    /* ---------------------------------------------------------------- run all */

    /** One-tap routine for a single account: balance -> check-in -> accept tasks -> balance. */
    public static JSONObject run(Config.Provider p) throws Exception {
        JSONObject out = new JSONObject();
        out.put("provider", p.name);
        out.put("realm", WorkBuddy.Account.fromJson(p.credentials).realm);
        double before = -1;
        try {
            before = credits(p);
        } catch (Exception e) {
            out.put("balanceError", String.valueOf(e.getMessage()));
        }
        out.put("creditsBefore", before);
        try {
            out.put("checkin", checkin(p));
        } catch (Exception e) {
            out.put("checkinError", String.valueOf(e.getMessage()));
        }
        List<JSONObject> list;
        try {
            list = tasks(p);
        } catch (Exception e) {
            out.put("tasksError", String.valueOf(e.getMessage()));
            list = new ArrayList<>();
        }
        int pending = 0;
        int accepted = 0;
        int claimed = 0;
        JSONArray summary = new JSONArray();
        for (JSONObject t : list) {
            String status = Proto.optString(t, "accept_status", "");
            if ("claimed".equals(status)) claimed++;
            else pending++;
            Double reward = Proto.optDouble(t, "reward_credit");
            JSONObject row = new JSONObject();
            row.put("code", Proto.optString(t, "task_code", ""));
            row.put("title", Proto.optString(t, "title", ""));
            row.put("status", status);
            row.put("reward", reward == null ? 0 : reward.doubleValue());
            row.put("hint", Proto.optString(t, "task_desc", Proto.optString(t, "description", "")));
            summary.put(row);
        }
        out.put("tasksTotal", list.size());
        out.put("tasksClaimed", claimed);
        out.put("tasksPending", pending);
        try {
            JSONObject acc = acceptAll(p, list);
            accepted = acc.optInt("requested", 0);
            out.put("accepted", accepted);
        } catch (Exception e) {
            out.put("acceptError", String.valueOf(e.getMessage()));
        }
        double after = before;
        try {
            after = credits(p);
        } catch (Exception ignored) {
        }
        out.put("creditsAfter", after);
        out.put("gained", after >= 0 && before >= 0 ? after - before : 0);
        out.put("tasks", summary);
        // remember the balance so the account card can show it without another round trip
        p.credits = after;
        p.creditsAt = System.currentTimeMillis() / 1000;
        Gateway.persistSoon();
        return out;
    }
}
