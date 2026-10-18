package com.cpa.gateway.core;

/** Official home pages per vendor, used for top-ups and account management. */
public final class VendorHome {

    private VendorHome() {}

    /** Returns the official site for a channel, or "" when there is none worth opening. */
    public static String of(Config.Provider p) {
        if (p == null) return "";
        String type = p.type == null ? "" : p.type;
        if (Config.TYPE_WORKBUDDY.equals(type)) {
            String realm = Proto.optString(WorkBuddy.fromJson2(p.credentials), "realm", WorkBuddy.REALM_CN);
            return WorkBuddy.REALM_GLOBAL.equalsIgnoreCase(realm)
                    ? "https://www.workbuddy.ai" : "https://www.codebuddy.cn";
        }
        if (Config.TYPE_TRAE.equals(type)) return "https://www.trae.cn";
        if (Config.TYPE_LOOMY.equals(type)) return "https://loomy.xunfei.cn";
        if (Config.TYPE_KIMI.equals(type)) return "https://www.kimi.com/code";
        if (Config.TYPE_DEEPSEEK.equals(type)) return "https://chat.deepseek.com";
        if (Config.TYPE_DOUBAO.equals(type)) return "https://www.doubao.com";
        if (Config.TYPE_ANTIGRAVITY.equals(type)) return "https://antigravity.google";
        if (Config.TYPE_CLAUDE.equals(type)) return "https://claude.ai";
        if (Config.TYPE_CODEX.equals(type)) return "https://chatgpt.com";
        if (Config.TYPE_XAI.equals(type)) return "https://grok.com";
        if (Config.TYPE_CLINE.equals(type)) return "https://app.cline.bot";
        if (Config.TYPE_KILO.equals(type)) return "https://kilo.ai";
        if (Config.TYPE_OVH.equals(type)) return "https://endpoints.ai.cloud.ovh.net";
        if (Config.TYPE_GEMINI.equals(type)) return "https://aistudio.google.com/apikey";
        if (Config.TYPE_ANTHROPIC.equals(type)) return "https://console.anthropic.com";
        // relays and API platforms: the configured base is their home
        String base = p.baseUrl == null ? "" : p.baseUrl;
        if (base.isEmpty()) return "";
        return base.replaceAll("/v1.*$", "").replaceAll("/api.*$", "");
    }

    /** Label shown next to the button. */
    public static String label(Config.Provider p) {
        String url = of(p);
        if (url.isEmpty()) return "官网";
        return url.replaceFirst("^https?://", "").replaceAll("/.*$", "");
    }
}
