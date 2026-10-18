package com.cpa.gateway.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OVHcloud AI Endpoints 匿名免费层（零凭证的公开车道）。
 *
 * 已用 curl 实测（不是照抄文档）：
 *   GET  https://oai.endpoints.kepler.ai.cloud.ovh.net/v1/models         -> 200，23 个模型
 *   POST https://oai.endpoints.kepler.ai.cloud.ovh.net/v1/chat/completions -> 200，1.9s，带完整 usage
 * 不需要任何凭证、不需要注册。
 *
 * 速率限制实测：连发 5 次 -> 前 2 次 200，第 3 次起 429。
 * 与官方"2 req/min per IP per model"一致——每个模型独立计数，
 * 所以 11 个可对话模型轮换使用，实际吞吐比单看 2/min 高。
 */
public final class Ovh {

    public static final String TYPE = "ovh";
    public static final String BASE = "https://oai.endpoints.kepler.ai.cloud.ovh.net/v1";
    private static final String UA = "AHYU/4.21.0";

    private Ovh() {
    }

    /** 拉模型清单（排除 tts / 语音识别 / 向量 / 图像 / 审核类，只留可对话的）。 */
    public static List<String> fetchModels() {
        List<String> out = new ArrayList<>();
        try {
            Map<String, String> h = new HashMap<>();
            h.put("Accept", "application/json");
            h.put("User-Agent", UA);
            Http.Resp r = Http.call("GET", BASE + "/models", h, null, "application/json",
                    8000, 20000, false);
            if (r.code >= 400) {
                Log.w("ovh 模型清单失败：HTTP " + r.code);
                return out;
            }
            JSONObject o = new JSONObject(r.body);
            JSONArray arr = o.optJSONArray("data");
            if (arr == null) arr = o.optJSONArray("models");
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject m = arr.optJSONObject(i);
                if (m == null) continue;
                String id = Proto.optString(m, "id", "");
                if (id.isEmpty()) continue;
                if (!isChatModel(id)) continue;
                if (!out.contains(id)) out.add(id);
            }
            Log.i("ovh 可对话模型 " + out.size() + " 个");
        } catch (Throwable t) {
            Log.w("ovh 模型清单异常：" + t);
        }
        return out;
    }

    /** 只要对话模型：剔掉语音、向量、图像、审核这几类。 */
    private static boolean isChatModel(String id) {
        String s = id.toLowerCase();
        String[] skip = {"tts", "whisper", "stable-diffusion", "bge-", "embedding", "guard"};
        for (String k : skip) {
            if (s.contains(k)) return false;
        }
        return true;
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
            JSONObject body = Upstreams.openAiBody(model, req, true);
            Upstreams.openAiStream(Upstreams.url(baseOf(p), "chat/completions"),
                    headers(), body, sink);
        }
    }

    private static String baseOf(Config.Provider p) {
        String b = Upstreams.trimSlash(p.baseUrl);
        return b == null || b.isEmpty() ? BASE : b;
    }

    /** 把 OVH 免费池注册成一个渠道（不需要账号，只有一个渠道）。 */
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
            target.id = "ovh";
            target.name = "OVHcloud 免费池";
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
