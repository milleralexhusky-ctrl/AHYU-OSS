package com.cpa.gateway.core;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * 模型价格表（估算成本用）。
 *
 * 数据来自 LiteLLM 的 model_prices_and_context_window.json（MIT），构建时裁剪：
 * 只留聊天模型、去掉云厂商区域重复项、同一模型只留一条（官方来源优先），
 * 1.5MB → 约 130KB（1684 条）打进 APK 的 res/raw/model_prices.json，不联网。
 *
 * 匹配三级：
 *   1. 归一化精确命中（去掉 - _ . / 等符号、全小写）；
 *   2. 前缀命中（表里有 "deepseek-v4" 而模型叫 "DeepSeek-V4.1-Flash"）；
 *   3. 家族中位数兜底（表里给出 deepseek/kimi/glm/qwen/claude/gpt… 的中位价）——
 *      UI 上会带个"≈"，因为这是同量级估算，不是账单。
 *
 * 价格字段里的 0 表示"没收录"，不是免费，所以这类条目在裁剪时就被丢掉了。
 */
public final class Prices {

    /** 归一化模型名 → [输入单价, 输出单价]（美元/token）。构建完成后原子替换引用，避免并发读写冲突。 */
    private static volatile Map<String, double[]> TABLE = java.util.Collections.emptyMap();
    /** 家族名（deepseek/kimi/…）→ [输入单价, 输出单价] 中位数。 */
    private static volatile Map<String, double[]> FAMILY = java.util.Collections.emptyMap();

    private static final String[] FAMILIES = {
            "deepseek", "kimi", "glm", "qwen", "gemini", "claude", "grok", "minimax",
            "doubao", "hunyuan", "ernie", "mistral", "llama", "nova", "gpt"
    };

    private static volatile boolean loaded;
    private static volatile boolean loading;
    private static volatile int count;

    private Prices() {
    }

    /** 后台加载一次；没加载完时查询返回 null（UI 显示"—"）。 */
    public static void ensureLoaded() {
        if (loaded || loading) return;
        loading = true;
        Threads.io(() -> {
            try {
                android.content.Context c = Gateway.context();
                if (c == null) {
                    loading = false;
                    return;
                }
                InputStream in = c.getResources().openRawResource(
                        com.cpa.gateway.R.raw.model_prices);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                in.close();
                JSONObject root = new JSONObject(new String(bos.toByteArray(), "UTF-8"));
                Map<String, double[]> builtTable = new HashMap<>();
                Map<String, double[]> builtFamily = new HashMap<>();
                java.util.Iterator<String> it = root.keys();
                while (it.hasNext()) {
                    String key = it.next();
                    JSONObject o = root.optJSONObject(key);
                    if (o == null) continue;
                    if ("_families".equals(key)) {
                        java.util.Iterator<String> fi = o.keys();
                        while (fi.hasNext()) {
                            String fam = fi.next();
                            JSONObject f = o.optJSONObject(fam);
                            if (f == null) continue;
                            builtFamily.put(fam, new double[]{f.optDouble("i", 0), f.optDouble("o", 0)});
                        }
                        continue;
                    }
                    double i = o.optDouble("i", 0);
                    double out = o.optDouble("o", 0);
                    if (i <= 0 && out <= 0) continue;
                    double[] pair = new double[]{i, out};
                    builtTable.put(norm(key), pair);
                    int slash = key.lastIndexOf('/');
                    if (slash >= 0 && slash < key.length() - 1) {
                        String tail = norm(key.substring(slash + 1));
                        if (!tail.isEmpty() && !builtTable.containsKey(tail)) builtTable.put(tail, pair);
                    }
                }
                TABLE = builtTable;
                FAMILY = builtFamily;
                count = builtTable.size();
                loaded = true;
                Log.i("价格表：加载 " + count + " 条模型价格 · " + builtFamily.size() + " 个家族兜底价");
            } catch (Throwable t) {
                Log.w("价格表加载失败：" + t);
            } finally {
                loading = false;
            }
        });
    }

    /** 归一化：小写、去掉一切非字母数字（deepseek/deepseek-chat → deepseekdeepseekchat）。 */
    private static String norm(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = Character.toLowerCase(s.charAt(i));
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) sb.append(c);
        }
        return sb.toString();
    }

    private static String familyOf(String n) {
        for (String f : FAMILIES) {
            if (n.contains(f)) return f;
        }
        return null;
    }

    /**
     * 查一个模型的价格。
     *
     * @return [输入单价, 输出单价]（美元/token）；查不到返回 null
     */
    public static double[] lookup(String model) {
        if (!loaded) {
            ensureLoaded();
            return null;
        }
        if (model == null || model.isEmpty()) return null;
        int slash = model.lastIndexOf('/');
        String raw = slash >= 0 ? model.substring(slash + 1) : model;
        int colon = raw.indexOf(':');
        if (colon > 0) raw = raw.substring(0, colon);
        String n = norm(raw);
        if (n.isEmpty()) return null;
        double[] exact = TABLE.get(n);
        if (exact != null) return exact;
        String best = null;
        for (String k : TABLE.keySet()) {
            if (k.length() >= 3 && n.startsWith(k) && (best == null || k.length() > best.length())) {
                best = k;
            }
        }
        if (best != null) return TABLE.get(best);
        String fam = familyOf(n);
        return fam == null ? null : FAMILY.get(fam);
    }

    /** 这次请求的估算成本（美元）；-1 表示未知。 */
    public static double costUsd(String model, long tokIn, long tokOut) {
        double[] p = lookup(model);
        if (p == null) return -1;
        return tokIn * p[0] + tokOut * p[1];
    }

    /** 价格表是否已就绪（UI 用来决定要不要显示"—"）。 */
    public static boolean ready() {
        return loaded;
    }

    /** 加载了多少条（日志/诊断用）。 */
    public static int size() {
        return count;
    }
}
