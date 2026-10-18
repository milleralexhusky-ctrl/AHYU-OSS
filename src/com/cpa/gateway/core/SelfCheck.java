package com.cpa.gateway.core;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * "真链路体检"：每个账号**真的**发一次 1-token 的对话请求，看能不能拿到答案。
 *
 * 为什么需要（和 Detect 的区别）：
 *   · `Detect` 回答的是"这个账号的模型目录里，抽到的两个模型通不通"；
 *     它跑在进程内、直接调 `Server.serve`，绕过了 HTTP、鉴权和选路。
 *   · 这个体检走的是**本地监听端口**（`http://127.0.0.1:port/v1/chat/completions`，
 *     带 API Key、标记 `X-CPA-Ephemeral`），也就是客户端真正会走的那条路：
 *     鉴权 → 路由 → 虚拟模型/前缀解析 → 上游 → 回包。
 *   今天真机上就出现过"网关进程活着、/health 也回 401，但某个账号根本出不了答案"，
 *   只看状态码是分不出来的。
 *
 * 只读性质：请求带 `_ephemeral`/`X-CPA-Ephemeral`（不计用量、不留会话），
 * max_tokens=1，不会污染统计，也不会在账号里留下对话。
 */
public final class SelfCheck {

    private SelfCheck() {
    }

    /** 一个账号的体检结果。 */
    public static final class Row {
        public final String name;
        public final String model;
        public final boolean ok;
        public final long ms;
        public final String why;

        Row(String name, String model, boolean ok, long ms, String why) {
            this.name = name;
            this.model = model;
            this.ok = ok;
            this.ms = ms;
            this.why = why == null ? "" : why;
        }
    }

    /**
     * 体检用哪个模型：优先 `auto`（走路由打分，最贴近真实使用），
     * 没有就取目录里第一个聊天模型；目录为空则返回 null（这类账号跳过，不计失败）。
     */
    static String pickModel(Config.Provider p) {
        if (p.models.contains("auto")) return "auto";
        for (String m : p.models) {
            if (m == null || m.isEmpty() || "*".equals(m)) continue;
            if (Router.nonChat(m)) continue;
            return m;
        }
        return null;
    }

    /**
     * 并发跑完所有"可派发"的账号，返回逐账号结果。
     *
     * @param perTimeoutMs 单个账号的上限（默认 12 秒；1-token 的请求正常 1~4 秒回来）
     * @param workers      并发数（4 是给上游留面子，也够快）
     */
    public static List<Row> runAll(int perTimeoutMs, int workers) {
        List<Row> out = new ArrayList<>();
        Config cfg = Gateway.config();
        if (cfg == null) return out;
        if (!Gateway.isRunning()) {
            out.add(new Row("（网关）", "-", false, 0, "网关没在运行，先启动再来体检"));
            return out;
        }
        List<Config.Provider> todo = new ArrayList<>();
        for (Config.Provider p : cfg.providers) {
            if (p.dispatchable()) todo.add(p);
        }
        final int timeout = Math.max(3000, perTimeoutMs);
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, workers), r -> {
            Thread t = new Thread(r, "cpa-selfcheck");
            t.setDaemon(true);
            return t;
        });
        List<Future<Row>> futures = new ArrayList<>();
        try {
            for (final Config.Provider p : todo) {
                futures.add(pool.submit(new Callable<Row>() {
                    @Override
                    public Row call() {
                        String model = pickModel(p);
                        if (model == null) return new Row(p.name, "-", false, 0, "目录里没有可测的聊天模型（先刷新目录）");
                        long t0 = System.currentTimeMillis();
                        String why;
                        try {
                            why = Server.probeOne(p, model, timeout, true);
                        } catch (Throwable e) {
                            why = Upstreams.shortenPublic(String.valueOf(e.getMessage() == null ? e : e.getMessage()));
                        }
                        return new Row(p.name, model, why == null,
                                System.currentTimeMillis() - t0, why);
                    }
                }));
            }
            for (Future<Row> f : futures) {
                try {
                    out.add(f.get(timeout + 5000L, TimeUnit.MILLISECONDS));
                } catch (Exception ignored) {
                    out.add(new Row("（超时）", "-", false, timeout, "整体等待超时"));
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return out;
    }

    /** 一行摘要，用于日志与导出：`体检：9/11 能出答案 ·成功 3.1s 平均`。 */
    public static String summary(List<Row> rows) {
        int ok = 0;
        long sum = 0;
        for (Row r : rows) {
            if (r.ok) {
                ok++;
                sum += r.ms;
            }
        }
        String avg = ok == 0 ? "-" : String.format(java.util.Locale.US, "%.1fs", sum / 1000.0 / ok);
        return "体检：" + ok + "/" + rows.size() + " 个账号能出答案 ·成功的平均 " + avg;
    }

    /** 打包成 JSON（给界面/脚本用）。 */
    public static String toJson(List<Row> rows) {
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            for (Row r : rows) {
                JSONObject o = new JSONObject();
                o.put("name", r.name);
                o.put("model", r.model);
                o.put("ok", r.ok);
                o.put("ms", r.ms);
                o.put("why", r.why);
                arr.put(o);
            }
            return arr.toString();
        } catch (Exception e) {
            return "[]";
        }
    }
}
