package com.cpa.gateway.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * WorkBuddy 账号的每日例行：签到 → 接任务 → 真正完成任务 → 领奖 → 结算。
 *
 * 与 WbTasks 的区别：WbTasks 只做到"接取"，这里会把任务真正做完并领奖。
 *
 * 可自动完成的任务（判据为行为事件上报）：
 *   chat_5               和 AI 聊天 5 次
 *   Model_chat_GLM5.2    体验 GLM-5.2 模型
 *   first_buddy          领取一只 Buddy
 *
 * 其余任务需要真人在客户端里操作（例如在微信里关注公众号、点开某个界面），
 * 这些任务会被归入 skipped，不混进已完成列表。
 */
public final class WbTaskChores {

    private WbTaskChores() {}

    /**
     * 正在跑的账号（按 provider id 隔离）。
     *
     * 为什么需要：外层 /v1/tasks/run 的 TASKS_RUNNING 是按"传入的 provider 名"做 key 的，
     * 而 runTasksInner 实际是**遍历所有账号**（only 只是过滤条件，不是限定）。
     * 于是 "只跑 A" 和 "全跑一次" 用的是两个不同的 key、能同时进来，
     * 而"全跑"那次也会处理 A —— 同一个账号被并发跑两遍，
     * 在上游看来就是行为重复上报（刷量）。
     *
     * 这里把保护下沉到账号级：同一个 provider 同时在跑时，后来者直接返回 alreadyRunning，
     * 不再重复发请求。
     */
    private static final java.util.Set<String> RUNNING =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 可以被程序完成的任务编码。 */
    static final java.util.Set<String> AUTO_DOABLE = new java.util.HashSet<>(java.util.Arrays.asList(
            "chat_5",
            "Model_chat_GLM5.2",
            "first_buddy"
    ));

    public static JSONObject run(Config.Provider p) throws Exception {
        JSONObject out = new JSONObject();
        out.put("provider", p.name);
        out.put("type", p.type);

        // 账号级重入保护：同一账号已在跑就别再发一遍（见 RUNNING 的注释）
        final String key = p.id == null || p.id.isEmpty() ? p.name : p.id;
        if (!RUNNING.add(key)) {
            Log.w("WbTaskChores：账号 " + p.name + " 已在跑任务，跳过本次");
            out.put("skipped", "already running");
            return out;
        }
        try {
            return runLocked(p, out);
        } finally {
            RUNNING.remove(key);
        }
    }

    /** 真正干活的部分；重入保护在 run() 里。 */
    private static JSONObject runLocked(Config.Provider p, JSONObject out) throws Exception {

        double before = -1;
        try {
            before = WbTasks.credits(p);
        } catch (Exception e) {
            out.put("balanceError", String.valueOf(e.getMessage()));
        }
        out.put("creditsBefore", before);

        // 1. 签到
        try {
            out.put("checkin", WbTasks.checkin(p));
        } catch (Exception e) {
            out.put("checkinError", String.valueOf(e.getMessage()));
        }

        // 2. 先把所有未接任务接掉
        List<JSONObject> tasks;
        try {
            tasks = WbTaskRunner.listTasks(p);
        } catch (Exception e) {
            out.put("tasksError", String.valueOf(e.getMessage()));
            tasks = new ArrayList<>();
        }
        try {
            JSONArray codes = new JSONArray();
            for (JSONObject t : tasks) {
                if ("not_accepted".equals(Proto.optString(t, "accept_status", ""))) {
                    codes.put(Proto.optString(t, "task_code", ""));
                }
            }
            if (codes.length() > 0) {
                WbTaskRunner.accept(p, codes);
            }
            out.put("accepted", codes.length());
        } catch (Exception e) {
            out.put("acceptError", String.valueOf(e.getMessage()));
        }

        // 3. 真正完成任务
        JSONArray doneArr = new JSONArray();
        JSONArray failArr = new JSONArray();

        try { doneArr.put(WbTaskRunner.doChat5(p)); } catch (Exception e) { failArr.put(row("chat_5", e)); }
        try { doneArr.put(WbTaskRunner.doModelChat(p)); } catch (Exception e) { failArr.put(row("Model_chat_GLM5.2", e)); }
        try { doneArr.put(WbTaskRunner.doFirstBuddy(p)); } catch (Exception e) { failArr.put(row("first_buddy", e)); }

        out.put("done", doneArr);
        if (failArr.length() > 0) out.put("failed", failArr);

        // 4. 领奖：完成且未领的都领掉（completed 或进度达标）
        JSONArray claimed = new JSONArray();
        int round = 0;
        while (round < 3) {
            round++;
            boolean any = false;
            for (String code : AUTO_DOABLE) {
                try {
                    JSONObject st = WbTaskRunner.taskStatus(p, code);
                    if (st == null) continue;
                    String ast = Proto.optString(st, "accept_status", "");
                    if ("claimed".equals(ast)) continue;
                    JSONObject prog = st.optJSONObject("progress");
                    int cur = prog == null ? 0 : prog.optInt("current", 0);
                    int target = prog == null ? 0 : prog.optInt("target", 0);
                    boolean ready = "completed".equals(ast) || (target > 0 && cur >= target);
                    if (!ready) continue;
                    JSONObject r = WbTaskRunner.claim(p, code);
                    JSONObject d = r.optJSONObject("data");
                    JSONObject row = new JSONObject();
                    row.put("code", code);
                    row.put("code_resp", r.optInt("code", -1));
                    row.put("msg", Proto.optString(r, "msg", ""));
                    if (d != null) {
                        row.put("already", d.optBoolean("already_claimed", false));
                        row.put("credit", d.optDouble("credit", 0));
                        row.put("energy", d.optDouble("energy", 0));
                    }
                    claimed.put(row);
                    any = true;
                    Thread.sleep(1200);
                } catch (Exception ignored) {
                }
            }
            if (!any) break;
        }
        out.put("claimed", claimed);

        // 4.5 连登管家：兑换已解锁档位 + 抽完抽奖次数
        try {
            out.put("streak", runStreakBonus(p));
        } catch (Exception e) {
            out.put("streakError", String.valueOf(e.getMessage()));
        }

        // 4.6 猫猫旅行：状态机走一趟（领奖 / 派出）
        try {
            out.put("travel", runTravel(p));
        } catch (Exception e) {
            out.put("travelError", String.valueOf(e.getMessage()));
        }

        // 5. 结算
        double after = before;
        try {
            after = WbTasks.credits(p);
        } catch (Exception ignored) {
        }
        out.put("creditsAfter", after);
        out.put("gained", (after >= 0 && before >= 0) ? after - before : 0);

        p.credits = after;
        p.creditsAt = System.currentTimeMillis() / 1000;
        Gateway.persistSoon();
        return out;
    }

    private static JSONObject row(String code, Exception e) throws Exception {
        JSONObject o = new JSONObject();
        o.put("task", code);
        o.put("error", String.valueOf(e.getMessage()));
        return o;
    }

    /* ----------------------------------------------------- 连登 / 旅行 */

    /** 连登管家：兑换所有已解锁（非 locked / 非 claimed）的档位，然后抽完抽奖次数。 */
    private static JSONObject runStreakBonus(Config.Provider p) throws Exception {
        JSONObject out = new JSONObject();
        JSONObject st = WbTaskRunner.streak(p);
        JSONObject data = st.optJSONObject("data");
        JSONObject red = data == null ? null : data.optJSONObject("redemption_status");

        JSONArray redeemed = new JSONArray();
        if (red != null) {
            JSONArray tiers = red.optJSONArray("tiers");
            for (int i = 0; tiers != null && i < tiers.length(); i++) {
                JSONObject t = tiers.optJSONObject(i);
                if (t == null) continue;
                String tier = Proto.optString(t, "tier", "");
                if (tier.isEmpty()) continue;
                String status = Proto.optString(red, "tier_" + tier + "_status", "locked");
                if (!"unlocked".equals(status) && !"available".equals(status)) continue;
                try {
                    JSONObject r = WbTaskRunner.redeem(p, tier);
                    JSONObject row = new JSONObject();
                    row.put("tier", tier);
                    row.put("code", r.optInt("code", -1));
                    JSONObject d = r.optJSONObject("data");
                    if (d != null) {
                        row.put("credit", d.optDouble("credit", 0));
                        row.put("energy", d.optDouble("energy", 0));
                        row.put("chances", d.optDouble("chances", 0));
                    }
                    redeemed.put(row);
                    Thread.sleep(1000);
                } catch (Exception ignored) {
                }
            }
        }
        out.put("redeemed", redeemed);

        // 抽奖：有次数就抽完
        JSONArray drawn = new JSONArray();
        try {
            JSONObject sum = WbTaskRunner.lotterySummary(p);
            JSONObject sd = sum.optJSONObject("data");
            int chances = sd == null ? 0 : sd.optInt("chances", 0);
            if (chances > 0) {
                for (int i = 0; i < Math.min(chances, 20); i++) {
                    JSONObject r = WbTaskRunner.lotteryDraw(p);
                    if (r.optInt("code", -1) != 0) break;
                    drawn.put(r.optJSONObject("data"));
                    Thread.sleep(1200);
                }
            }
            out.put("chances", chances);
        } catch (Exception ignored) {
        }
        out.put("drawn", drawn);
        return out;
    }

    /** 猫猫旅行：idle 就派出，arrived 就领奖。 */
    private static JSONObject runTravel(Config.Provider p) throws Exception {
        JSONObject out = new JSONObject();
        JSONObject st = WbTaskRunner.travelStatus(p);
        JSONObject d = st.optJSONObject("data");
        if (d == null) { out.put("skipped", "无状态"); return out; }

        String state = Proto.optString(d, "state", "");
        out.put("state", state);
        if ("arrived".equals(state)) {
            JSONObject body = new JSONObject();
            body.put("record_id", d.optLong("record_id", 0));
            JSONObject r = WbTaskRunner.travelClaim(p, body);
            out.put("claim", r.optInt("code", -1));
            JSONObject rd = r.optJSONObject("data");
            if (rd != null) out.put("credit", rd.optDouble("reward_credit", rd.optDouble("credit", 0)));
        } else if ("idle".equals(state) && !d.optBoolean("daily_limit_reached", false)) {
            JSONObject r = WbTaskRunner.travelDepart(p);
            out.put("depart", r.optInt("code", -1));
            JSONObject rd = r.optJSONObject("data");
            if (rd != null) out.put("arrive", rd.optLong("arrive_at", 0));
        } else {
            out.put("skipped", "状态无需动作");
        }
        return out;
    }
}
