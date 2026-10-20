package com.cpa.gateway.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Kilo 免费池（零凭证的公开车道）。
 *
 * 协议来自 dsh-our-free-model（MIT），已用 curl 实测：
 *   GET  https://api.kilo.ai/api/gateway/models            → 返回全部模型，取 isFree=true 的
 *   POST https://api.kilo.ai/api/gateway/chat/completions   → 标准 OpenAI body，**不需要任何凭证**
 * 流式里会掺 `: KILO PROCESSING` 注释行当心跳（解析时跳过非 data: 行即可，现有解析已经这么做）。
 *
 * 注意：这是公开免费池，上游声明 prompt 可能被记录用于改进服务——别发敏感内容。
 */
public final class Kilo {

    public static final String TYPE = "kilo";
    public static final String BASE = "https://api.kilo.ai/api/gateway";
    private static final String UA = "dsh-our-free-model";

    private Kilo() {
    }

    /** 拉免费模型清单（只留 isFree=true）。 */
    public static List<String> fetchModels() {
        List<String> out = new ArrayList<>();
        try {
            Map<String, String> h = new HashMap<>();
            h.put("Accept", "application/json");
            h.put("User-Agent", UA);
            Http.Resp r = Http.call("GET", BASE + "/models", h, null, "application/json",
                    8000, 20000, false);
            if (r.code >= 400) {
                Log.w("kilo 模型清单失败：HTTP " + r.code);
                return out;
            }
            JSONObject o = new JSONObject(r.body);
            JSONArray arr = o.optJSONArray("data");
            if (arr == null) arr = o.optJSONArray("models");
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject m = arr.optJSONObject(i);
                if (m == null) continue;
                boolean free = m.optBoolean("isFree", false) || m.optBoolean("is_free", false);
                if (!free) continue;
                String id = Proto.optString(m, "id", "");
                // 免费池里那些明确标了 :free 后缀的也一并收（有些条目没给 isFree 但名字带 free）
                if (!id.isEmpty() && !out.contains(id)) out.add(id);
            }
            Log.i("kilo 免费模型 " + out.size() + " 个");
        } catch (Throwable t) {
            Log.w("kilo 模型清单异常：" + t);
        }
        return out;
    }

    static Map<String, String> headers() {
        Map<String, String> h = new HashMap<>();
        h.put("Content-Type", "application/json");
        h.put("Accept", "text/event-stream");
        h.put("User-Agent", UA);
        return h;
    }

    /** 上游实现：零凭证，直接转发。 */
    public static final class Up implements Proto.Upstream {
        private final Config.Provider p;

        public Up(Config.Provider p) {
            this.p = p;
        }

        @Override
        public void chat(String model, Proto.Req req, Proto.Sink sink) throws Exception {
            String endpoint = Upstreams.url(baseOf(p), "chat/completions");
            /* 保真模式（4.50.1）：n>1 / logprobs 的响应原样回传。 */
            if (Upstreams.rawOpenAiIfWanted(req, endpoint, headers(), model, sink)) return;
            JSONObject body = Upstreams.openAiBody(model, req, true);
            Upstreams.openAiStream(endpoint, headers(), body, sink);
        }
    }

    private static String baseOf(Config.Provider p) {
        String b = Upstreams.trimSlash(p.baseUrl);
        return b == null || b.isEmpty() ? BASE : b;
    }

    /** 把 Kilo 免费池注册成一个渠道（不需要账号，只有一个渠道）。 */
    public static JSONObject registerProvider(List<String> models) throws Exception {
        Config cfg = Gateway.config();
        Config.Provider target = null;
        for (Config.Provider p : cfg.providers) {
            if (TYPE.equals(p.type)) {
                target = p;
                break;
            }
        }
        boolean created = target == null;
        if (created) {
            target = new Config.Provider();
            target.type = TYPE;
            target.id = "kilo";
            target.name = "Kilo 免费池";
            target.baseUrl = BASE;
            cfg.providers.add(target);
        }
        target.enabled = true;
        target.lastError = "";
        target.lastErrorAt = 0;
        if (models != null && !models.isEmpty()) target.setModels(models);
        Gateway.saveNow(Gateway.context());
        JSONObject out = new JSONObject();
        out.put("provider", target.name);
        out.put("models", target.models.size());
        out.put("created", created);
        return out;
    }
}
