package com.cpa.gateway.core;

import java.util.List;

/**
 * 模型 id 前面的短标签。
 *
 * 客户端里只该看见一个短前缀，不该是账号全名（"WorkBuddy 1/auto" 这种太长、还带着软件名）。
 *   官方账号 → 各自的简称：WB / Trae / Loomy / Kimi / DS / 豆包 / Claude / GPT / AG / Gemini
 *   中科大模型 → 中科
 *   中转站    → 名字第一段的简称
 * 同一简称有多个账号时才跟序号：WB1、WB2、中科1…
 */
public final class ShortName {

    private ShortName() {
    }

    /** 官方第一方账号（不是中转站）。 */
    public static boolean isOfficial(String type) {
        return Config.TYPE_WORKBUDDY.equals(type) || Config.TYPE_TRAE.equals(type)
                || Config.TYPE_LOOMY.equals(type) || Config.TYPE_KIMI.equals(type)
                || Config.TYPE_DEEPSEEK.equals(type) || Config.TYPE_DOUBAO.equals(type)
                || Config.TYPE_CLAUDE.equals(type) || Config.TYPE_CODEX.equals(type)
                || Config.TYPE_ANTIGRAVITY.equals(type) || Config.TYPE_XAI.equals(type)
                || Config.TYPE_CLINE.equals(type) || Config.TYPE_KILO.equals(type)
                || Config.TYPE_OVH.equals(type);
    }

    /** 不带序号的基础标签。官方账号用各自平台的简称。 */
    public static String base(Config.Provider p) {
        if (p == null) return "中转";
        String t = p.type == null ? "" : p.type;
        if (Config.TYPE_WORKBUDDY.equals(t)) return "WB";
        if (Config.TYPE_TRAE.equals(t)) return "Trae";
        if (Config.TYPE_LOOMY.equals(t)) return "Loomy";
        if (Config.TYPE_KIMI.equals(t)) return "Kimi";
        if (Config.TYPE_DEEPSEEK.equals(t)) return "DS";
        if (Config.TYPE_DOUBAO.equals(t)) return "豆包";
        if (Config.TYPE_CODEX.equals(t)) return "GPT";
        if (Config.TYPE_CLAUDE.equals(t) || Config.TYPE_ANTHROPIC.equals(t)) return "Claude";
        if (Config.TYPE_ANTIGRAVITY.equals(t)) return "AG";
        if (Config.TYPE_XAI.equals(t)) return "Grok";
        if (Config.TYPE_CLINE.equals(t)) return "Cline";
        if (Config.TYPE_KILO.equals(t)) return "Kilo";
        if (Config.TYPE_OVH.equals(t)) return "OVH";
        if (Config.TYPE_GEMINI.equals(t)) return "Gemini";
        String n = p.name == null ? "" : p.name.trim();
        for (String sep : new String[]{" ", "（", "(", "·", "／", "/"}) {
            int i = n.indexOf(sep);
            if (i > 0) {
                n = n.substring(0, i);
                break;
            }
        }
        n = n.trim();
        if (n.isEmpty()) n = "中转";
        // 中文名取前两字（"中科大模型" → 中科 · "硅基流动" → 硅基）；
        // 拉丁名整段留着（"Pollinations" 截成 "Po" 没法看）
        if (n.length() > 2 && n.codePointAt(0) > 0x2E80) n = n.substring(0, 2);
        return n.length() > 12 ? n.substring(0, 12) : n;
    }

    /**
     * 账号的短标签。
     *
     * **优先用落盘的稳定标签**（{@link Config.Provider#label}）：这是客户端模型名前缀的
     * 唯一来源，必须跨"停用/启用/新增账号"保持不变。见 label 字段上的说明。
     *
     * 只有拿不到稳定标签时才回退到"按当前启用顺序现算"——那是老行为，
     * 会随启用状态漂移，仅作为兜底（例如配置还没被 {@link #ensureLabels} 处理过）。
     */
    public static String of(List<Config.Provider> all, Config.Provider target) {
        if (target == null) return "中转";
        if (target.label != null && !target.label.isEmpty()) return target.label;
        if (all == null) return base(target);
        String b = base(target);
        int idx = 0;
        int total = 0;
        for (Config.Provider p : all) {
            if (!p.enabled || !base(p).equals(b)) continue;
            total++;
            if (p == target) idx = total;
        }
        if (idx == 0) return b;                  // 目标自己停用
        return total > 1 ? b + idx : b;
    }

    /** 用当前配置算。 */
    public static String of(Config.Provider target) {
        return of(Gateway.config().providers, target);
    }

    /**
     * 这个前缀是不是某个已知账号（标签 / 全名 / id 任一命中）。
     *
     * 抽成静态方法是为了可测：客户端写的 `WB1/kimi-k3-1` 在**换号兜底**时必须能识别出
     * "WB1 是个账号前缀、要剥掉"，否则第二个账号会收到带前缀的模型名、上游直接 400。
     * 这个判断以前散在 Server 的两处，自检没法覆盖；现在集中在这里。
     */
    public static boolean isKnownPrefix(List<Config.Provider> all, String prefix) {
        if (all == null || prefix == null || prefix.isEmpty()) return false;
        for (Config.Provider p : all) {
            if (p == null) continue;
            if (p.label != null && p.label.equalsIgnoreCase(prefix)) return true;
            if (p.name != null && p.name.equalsIgnoreCase(prefix)) return true;
            if (p.id != null && p.id.equalsIgnoreCase(prefix)) return true;
            if (base(p).equalsIgnoreCase(prefix)) return true;
        }
        return false;
    }

    /**
     * 给还没有稳定标签的账号补上，并返回"是否有改动"（调用方据此决定要不要落盘）。
     *
     * 首次运行（升级上来的老配置）时，按**当时的顺序**分配，结果与旧行为完全一致 ——
     * 也就是用户客户端里已经写着的那些名字照旧有效，不需要改任何客户端配置。
     * 从这一刻起标签被固化，之后停用/启用/新增都不会让已有账号改名。
     *
     * 规则：
     *   · 同一基础标签下只有一个账号 → 就用基础标签（"DS"、"Kilo"），不加序号；
     *   · 有多个 → "DS1"、"DS2"…按配置顺序；
     *   · 已经带标签的账号**一律不动**，新分配的会避开已占用的名字，
     *     所以"删掉 DS1 再加一个"不会撞名（新账号拿 DS3 之类）。
     */
    public static boolean ensureLabels(List<Config.Provider> all) {
        if (all == null || all.isEmpty()) return false;
        java.util.LinkedHashMap<String, java.util.List<Config.Provider>> groups =
                new java.util.LinkedHashMap<>();
        for (Config.Provider p : all) {
            if (p == null) continue;
            String b = base(p);
            java.util.List<Config.Provider> list = groups.get(b);
            if (list == null) {
                list = new java.util.ArrayList<>();
                groups.put(b, list);
            }
            list.add(p);
        }
        boolean changed = false;
        for (java.util.Map.Entry<String, java.util.List<Config.Provider>> e : groups.entrySet()) {
            String b = e.getKey();
            java.util.List<Config.Provider> ps = e.getValue();
            java.util.Set<String> used = new java.util.HashSet<>();
            for (Config.Provider p : ps) {
                if (p.label != null && !p.label.isEmpty()) used.add(p.label.toLowerCase(java.util.Locale.US));
            }
            /* 关键：首次分配要**按"启用的账号"编号**，与旧行为逐字一致 ——
               用户客户端里已经写着的名字（WB1/kimi-k3-1 之类）必须照旧有效。
               如果图省事按"全部账号"编号，配置里只要有一个被停用的账号排在前面，
               启用中的账号就会整体错位一号，等于把用户的客户端配置全改坏。
               所以：先按启用顺序发号，再把剩下的（停用的）补上不冲突的名字。 */
            java.util.List<Config.Provider> enabled = new java.util.ArrayList<>();
            for (Config.Provider p : ps) if (p.enabled) enabled.add(p);
            int totalEnabled = enabled.size();
            for (int i = 0; i < enabled.size(); i++) {
                Config.Provider p = enabled.get(i);
                if (p.label != null && !p.label.isEmpty()) continue;   // 已固化，不动
                String cand = totalEnabled > 1 ? b + (i + 1) : b;
                int bump = i + 1;
                while (used.contains(cand.toLowerCase(java.util.Locale.US))) {
                    bump++;
                    cand = totalEnabled > 1 || bump > 1 ? b + bump : b;
                }
                p.label = cand;
                used.add(cand.toLowerCase(java.util.Locale.US));
                changed = true;
            }
            /* 停用的账号：给一个唯一但不冲突的名字占位，等它被启用时就有稳定标签可用。
               （停用期间它的标签本来就解析不到，客户端不会引用到它。） */
            for (Config.Provider p : ps) {
                if (p.label != null && !p.label.isEmpty()) continue;
                String cand = b;
                int bump = 1;
                while (used.contains(cand.toLowerCase(java.util.Locale.US))) {
                    bump++;
                    cand = b + bump;
                }
                p.label = cand;
                used.add(cand.toLowerCase(java.util.Locale.US));
                changed = true;
            }
        }
        return changed;
    }
}
