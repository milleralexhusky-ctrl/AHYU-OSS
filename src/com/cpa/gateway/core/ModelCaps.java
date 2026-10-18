package com.cpa.gateway.core;

import org.json.JSONObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 模型能力表：上下文长度、最大输出、是否支持图片/工具/推理/音频/PDF/联网。
 *
 * 数据源与 {@link Prices} 共用同一个文件（res/raw/model_prices.json），
 * 来自 LiteLLM 的 model_prices_and_context_window.json（MIT），同时内置补充了
 * WorkBuddy 自定义模型别名（balanced-model / deep-model / summary / codewise-jump /
 * space-bunny / aquila）、腾讯混元（hy3 / hy3-x / hunyuan-t1 / hunyuan-turbos）、
 * 豆包网页版（doubao / doubao-think / doubao-seed-evolving）、阶跃星辰（step-5-preview /
 * step-3.5-flash）、讯飞 Loomy、Kimi for Coding、Codex 及免费池模型能力定义。
 *
 * 字段定义：
 *   ci 上下文长度                      co 最大输出
 *   v  图片  f  工具  r  推理  a  音频  p  PDF  w  联网
 */
public final class ModelCaps {

    public static final int VISION = 1;
    public static final int TOOLS = 1 << 1;
    public static final int REASONING = 1 << 2;
    public static final int AUDIO = 1 << 3;
    public static final int PDF = 1 << 4;
    public static final int WEB = 1 << 5;

    /** 一条能力记录。 */
    public static final class Caps {
        public int context;
        public int maxOutput;
        public int flags;

        public Caps() {
        }

        public Caps(int context, int maxOutput, int flags) {
            this.context = context;
            this.maxOutput = maxOutput;
            this.flags = flags;
        }

        public boolean has(int f) {
            return (flags & f) != 0;
        }

        public boolean any() {
            return context > 0 || maxOutput > 0 || flags != 0;
        }
    }

    /** 构建完成后一次性原子发布，避免并发遍历时触发 ConcurrentModificationException。 */
    private static volatile Map<String, Caps> TABLE = Collections.emptyMap();
    private static volatile boolean loaded;
    private static volatile boolean loading;
    private static volatile int count;

    private ModelCaps() {
    }

    /**
     * 预热入口（由 Gateway.init 在主线程调用）：后台异步加载，不阻塞应用冷启动。
     */
    public static void ensureLoaded() {
        if (loaded || loading) return;
        Threads.io(ModelCaps::loadSync);
    }

    /**
     * 同步加载（若已在后台加载则等待完成）：供 lookup() 在首次查询时兜底。
     */
    private static synchronized void loadSync() {
        if (loaded) return;
        loading = true;
        try {
            Map<String, Caps> built = new HashMap<>(4096);
            // 先装入内置补充表（覆盖 LiteLLM 未收录的自定义别名与国产模型）
            registerBuiltins(built);

            android.content.Context ctx = Gateway.context();
            java.io.InputStream in = null;
            if (ctx != null) {
                in = ctx.getResources().openRawResource(com.cpa.gateway.R.raw.model_prices);
            }
            if (in == null) {
                Log.w("模型能力表：拿不到 res/raw/model_prices.json（ctx=" + (ctx != null) + "），仅使用内置补充表");
                TABLE = built;
                count = built.size();
                loaded = true;
                return;
            }
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            JSONObject root = new JSONObject(new String(bos.toByteArray(), "UTF-8"));
            java.util.Iterator<String> it = root.keys();
            while (it.hasNext()) {
                String key = it.next();
                if ("_families".equals(key)) continue;
                JSONObject o = root.optJSONObject(key);
                if (o == null) continue;
                Caps c = new Caps();
                c.context = o.optInt("ci", 0);
                c.maxOutput = o.optInt("co", 0);
                int f = 0;
                if (o.optInt("v", 0) == 1) f |= VISION;
                if (o.optInt("f", 0) == 1) f |= TOOLS;
                if (o.optInt("r", 0) == 1) f |= REASONING;
                if (o.optInt("a", 0) == 1) f |= AUDIO;
                if (o.optInt("p", 0) == 1) f |= PDF;
                if (o.optInt("w", 0) == 1) f |= WEB;
                c.flags = f;
                if (!c.any()) continue;
                built.put(norm(key), c);
                // 再按"去掉供应商前缀"存一份别名（如 deepseek/deepseek-r1-0528 -> deepseekr10528）
                int slash = key.lastIndexOf('/');
                if (slash >= 0 && slash < key.length() - 1) {
                    String tail = norm(key.substring(slash + 1));
                    if (!tail.isEmpty() && !built.containsKey(tail)) built.put(tail, c);
                }
            }
            // 重新确保高优先级的渠道专属内置项（如豆包网页版不支持工具、WorkBuddy 专属别名）不被误覆盖
            registerChannelOverrides(built);
            TABLE = built;
            count = built.size();
            loaded = true;
            Log.i("模型能力表：加载 " + count + " 条（含内置补充模型）");
        } catch (Throwable t) {
            Log.w("模型能力表加载失败：" + t);
        } finally {
            loading = false;
        }
    }

    /**
     * 补充 LiteLLM 表未收录的国产模型、渠道专属别名与免费池模型。
     */
    private static void registerBuiltins(Map<String, Caps> m) {
        // 网关虚拟模型与平台 auto 路由
        put(m, "auto", 200000, 64000, VISION | TOOLS | REASONING);
        put(m, "auto-code", 200000, 64000, VISION | TOOLS | REASONING);
        put(m, "auto-long", 1000000, 64000, VISION | TOOLS | REASONING);
        put(m, "auto-race", 200000, 64000, VISION | TOOLS | REASONING);

        // WorkBuddy / CodeBuddy 专属模型别名与腾讯混元系列
        put(m, "balanced-model", 131072, 16384, TOOLS);
        put(m, "deep-model", 131072, 65536, TOOLS | REASONING);
        put(m, "summary", 131072, 8192, 0);
        put(m, "codewise-jump", 65536, 8192, 0);
        put(m, "space-bunny", 131072, 16384, TOOLS);
        put(m, "aquila", 131072, 16384, TOOLS);
        put(m, "hy3", 262144, 262144, TOOLS | REASONING);
        put(m, "hy3-x", 262144, 262144, TOOLS | REASONING);
        put(m, "hunyuan-t1", 262144, 65536, TOOLS | REASONING);
        put(m, "hunyuan-turbos", 262144, 32768, VISION | TOOLS);
        put(m, "hunyuan-large", 262144, 32768, TOOLS);
        put(m, "hunyuan-standard", 262144, 16384, TOOLS);

        // 阶跃星辰 StepFun 系列
        put(m, "step-5-preview", 262144, 65536, VISION | TOOLS | REASONING);
        put(m, "step-5-preview-free", 262144, 65536, VISION | TOOLS | REASONING);
        put(m, "step-3.5-flash", 262144, 65536, VISION | TOOLS | REASONING);
        put(m, "step-2-16k", 16384, 4096, TOOLS);

        // Kimi / 智谱 / DeepSeek / MiniMax / 讯飞 Loomy 补充
        put(m, "kimi-for-coding", 262144, 65536, VISION | TOOLS | REASONING);
        put(m, "kimi-latest", 131072, 16384, VISION | TOOLS | WEB);
        put(m, "kimi-k2", 262144, 65536, TOOLS | REASONING);
        put(m, "kimi-k2.5", 262144, 65536, VISION | TOOLS | REASONING);
        put(m, "kimi-k2.6", 262144, 65536, VISION | TOOLS | REASONING);
        put(m, "kimi-k3", 262144, 65536, VISION | TOOLS | REASONING);
        put(m, "glm-5", 202752, 131072, VISION | TOOLS | REASONING);
        put(m, "glm-5.2", 262144, 262144, VISION | TOOLS | REASONING);
        put(m, "glm-5.3", 1048576, 1048576, VISION | TOOLS | REASONING);
        put(m, "glm-5.3-flash", 262144, 131072, VISION | TOOLS | REASONING);
        put(m, "deepseek-chat", 131072, 16384, TOOLS);
        put(m, "deepseek-reasoner", 131072, 65536, TOOLS | REASONING);
        put(m, "deepseek-v4", 131072, 65536, VISION | TOOLS | REASONING);
        put(m, "deepseek-v4-pro", 262144, 131072, VISION | TOOLS | REASONING);
        put(m, "deepseek-v4-flash", 131072, 65536, VISION | TOOLS);
        put(m, "deepseek-v4.1-flash", 131072, 65536, VISION | TOOLS | REASONING);
        put(m, "minimax-m2", 204800, 131072, TOOLS | REASONING);
        put(m, "minimax-m3", 1000000, 131072, VISION | TOOLS | REASONING);
        put(m, "loomy-default", 131072, 16384, TOOLS | REASONING);

        // 豆包 / 字节系列
        put(m, "doubao-1.5-pro", 256000, 32768, VISION | TOOLS);
        put(m, "doubao-1.5-thinking-pro", 256000, 64000, VISION | TOOLS | REASONING);

        // 海外与免费池补充（Codex / Grok / Kilo）
        put(m, "codex-mini-latest", 200000, 100000, VISION | TOOLS | REASONING);
        put(m, "gpt-5-codex", 400000, 128000, VISION | TOOLS | REASONING);
        put(m, "gpt-5.1-codex", 400000, 128000, VISION | TOOLS | REASONING);
        put(m, "gpt-5.1-codex-mini", 200000, 100000, VISION | TOOLS | REASONING);
        put(m, "grok-4.1-thinking", 262144, 65536, VISION | TOOLS | REASONING | WEB);
        put(m, "kilo-auto", 131072, 16384, TOOLS);
        put(m, "kilo-auto/free", 131072, 16384, TOOLS);
    }

    /**
     * 渠道专属修正：避免 LiteLLM 里的通用前缀（如 fireworks/auto 或 doubao-seed-2.0-pro）
     * 把本工程特定通道模型的能力标错（例如豆包网页版通道不支持工具调用）。
     */
    private static void registerChannelOverrides(Map<String, Caps> m) {
        put(m, "auto", 200000, 64000, VISION | TOOLS | REASONING);
        put(m, "auto-code", 200000, 64000, VISION | TOOLS | REASONING);
        put(m, "auto-long", 1000000, 64000, VISION | TOOLS | REASONING);
        put(m, "auto-race", 200000, 64000, VISION | TOOLS | REASONING);
        // 豆包网页版三模型：支持识图与联网，不支持结构化 function calling
        put(m, "doubao", 131072, 16384, VISION | WEB);
        put(m, "doubao-think", 131072, 32768, VISION | REASONING | WEB);
        put(m, "doubao-seed-evolving", 256000, 64000, VISION | REASONING | WEB);
        put(m, "grok-4.1-thinking", 262144, 65536, VISION | TOOLS | REASONING | WEB);
    }

    private static void put(Map<String, Caps> m, String key, int ci, int co, int flags) {
        m.put(norm(key), new Caps(ci, co, flags));
    }

    /**
     * 查一个模型的能力；查不到返回 null（调用方据此不显示标签）。
     *
     * 匹配顺序：
     *   1. 完整模型名查询（含去 `:free` 标签与按 `-` 逐段去后缀精确匹配）；
     *   2. 若带 `provider/` 前缀（如 `WB1/deepseek-r1-0528` 或 `stepfun/step-3.5-flash:free`），
     *      剥掉前缀后再查一次。
     */
    public static Caps lookup(String model) {
        if (!loaded) loadSync();
        if (model == null || model.isEmpty()) return null;
        Map<String, Caps> tab = TABLE;
        if (tab.isEmpty()) return null;

        Caps hit = lookupSingle(tab, model);
        if (hit != null) return hit;

        int slash = model.lastIndexOf('/');
        if (slash >= 0 && slash < model.length() - 1) {
            return lookupSingle(tab, model.substring(slash + 1));
        }
        return null;
    }

    private static Caps lookupSingle(Map<String, Caps> tab, String rawModel) {
        if (rawModel == null) return null;
        String s = rawModel.trim().toLowerCase(Locale.US);
        int colon = s.indexOf(':');
        if (colon > 0) s = s.substring(0, colon);
        String n = norm(s);
        if (n.isEmpty()) return null;

        // 1. 精确命中
        Caps c = tab.get(n);
        if (c != null) return c;

        // 2. 在原始带 '-' 的模型名上逐段砍去版本/日期/渠道后缀，优先寻找精确主干命中
        //    （旧实现错在 norm(model) 之后找 '-'，因为 norm 已删光 '-'，导致后缀回退 100% 死代码）
        String t = s;
        while (true) {
            int cut = t.lastIndexOf('-');
            if (cut <= 0) break;
            String seg = t.substring(cut + 1);
            if (!isRemovableSuffix(seg)) break;
            t = t.substring(0, cut);
            String nt = norm(t);
            if (nt.length() < 3) break;
            Caps exactStem = tab.get(nt);
            if (exactStem != null) return exactStem;
        }

        // 3. 最长前缀命中（如 glm-5.3-flash-preview -> glm53flash）
        String best = null;
        for (String k : tab.keySet()) {
            if (k.length() >= 4 && n.startsWith(k) && (best == null || k.length() > best.length())) {
                best = k;
            }
        }
        if (best != null) return tab.get(best);

        // 4. 反向前缀命中（如 gemini-3-flash -> gemini-3-flash-preview）
        for (String k : tab.keySet()) {
            if (n.length() >= 4 && k.startsWith(n)) return tab.get(k);
        }

        // 5. 逐段砍尾巴后再做最长前缀兜底
        t = s;
        while (true) {
            int cut = t.lastIndexOf('-');
            if (cut <= 0) break;
            String seg = t.substring(cut + 1);
            if (!isRemovableSuffix(seg)) break;
            t = t.substring(0, cut);
            String nt = norm(t);
            if (nt.length() < 4) break;
            String bp = null;
            for (String k : tab.keySet()) {
                if (k.length() >= 4 && nt.startsWith(k) && (bp == null || k.length() > bp.length())) {
                    bp = k;
                }
            }
            if (bp != null) return tab.get(bp);
        }
        return null;
    }

    /** 判断某一段尾巴是否属于可剥离的版本号、日期或渠道修饰词。 */
    private static boolean isRemovableSuffix(String seg) {
        if (seg == null || seg.isEmpty()) return false;
        if (seg.matches("[0-9]+[a-z0-9.]*|v[0-9]+[a-z0-9.]*")) return true;
        switch (seg) {
            case "free":
            case "preview":
            case "latest":
            case "exp":
            case "beta":
            case "lkeap":
            case "online":
            case "search":
            case "instruct":
            case "chat":
            case "it":
                return true;
            default:
                return false;
        }
    }

    /** 归一化：小写、只留字母数字（与 Prices 同一套规则）。 */
    private static String norm(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = Character.toLowerCase(s.charAt(i));
            if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')) sb.append(ch);
        }
        return sb.toString();
    }

    public static int size() {
        return count;
    }

    /** token 数压成短标签：1048576 -> "1M"，131072 -> "128K"。 */
    public static String shortTokens(int tokens) {
        if (tokens <= 0) return "";
        if (tokens >= 1000000) {
            double m = tokens / 1000000.0;
            return (m == Math.floor(m) ? String.valueOf((long) m)
                    : String.format(java.util.Locale.US, "%.1f", m)) + "M";
        }
        if (tokens >= 1024) {
            return Math.round(tokens / 1024.0) + "K";
        }
        return String.valueOf(tokens);
    }
}
