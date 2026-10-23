package com.cpa.gateway.core;

/**
 * 「这家服务商在国内还是国外」——决定它被归到哪个分组、要不要挂梯子。
 *
 * <h2>为什么单独抽出来</h2>
 *
 * 原来这段判据内联在 {@code MainActivity.isDomestic} 里，而 UI 包（{@code com.cpa.gateway}）
 * 的自检是跑不到的 —— 于是一个**纯字符串匹配的错**（关键词表漏项）能一直躺着没人发现，
 * 直到有人在"选择服务商"里看到"智谱 GLM"被归进"国外"。
 *
 * 抽屉化之后，{@link Selftest} 里那条「预置服务商分组」能逐个核一遍预置清单，
 * 增删厂商时忘了补关键词会立刻变红，而不是等用户看见。
 *
 * <h2>口径</h2>
 *
 * 判据只有一条：**从这台设备（国内）访问它，要不要挂梯子**。
 * 所以"MiniMax 国际版"属于国外 —— 虽然厂商是国内的，但走的是境外站点和出口。
 * 这个例外必须在关键词匹配之前判断，否则名字里的国内厂商关键词会把它误判成国内。
 *
 * <p>关键词表是**故意冗余**的：英文名、中文名都收。预置清单里
 * "智谱 GLM""讯飞星火""小米 MiMo"这些未必带英文关键词，只认英文会漏。
 */
public final class Region {

    private Region() {
    }

    /** 按账号类型直接判定的那几种（类型本身就确定了归属）。 */
    public static boolean isDomestic(String type, String name) {
        String t = type == null ? "" : type;
        String n = name == null ? "" : name.toLowerCase(java.util.Locale.US);

        /* 类型优先：这些渠道的归属不取决于名字，取决于它连的是哪家。 */
        if (Config.TYPE_WORKBUDDY.equals(t) || Config.TYPE_TRAE.equals(t)
                || Config.TYPE_LOOMY.equals(t) || Config.TYPE_KIMI.equals(t)
                || Config.TYPE_DEEPSEEK.equals(t) || Config.TYPE_DOUBAO.equals(t)) return true;
        if (Config.TYPE_ANTIGRAVITY.equals(t) || Config.TYPE_CLAUDE.equals(t)
                || Config.TYPE_CODEX.equals(t)) return false;

        /* 「国际版」是同一家的海外站点，走境外出口。必须放在关键词匹配之前 ——
           "MiniMax 国际版"含 "minimax"，否则会被误判成国内。 */
        if (n.contains("国际")) return false;

        for (String key : KEYS) {
            if (n.contains(key)) return true;
        }
        return false;
    }

    /**
     * 关键词表。**增删预置服务商时请对照预置清单逐个核一遍** ——
     * 这里漏一个，那个厂商就会在界面上被归到错误的分组。
     * （4.55.0 修掉的就是这类漏项：deepseek / zhipu / volces / xiaomimimo /
     * spark / qianfan / stepfun / doubao / 火山 / 小米 / 百度 / 讯飞 当时都不在表里。）
     */
    private static final String[] KEYS = {
            /* 英文名 / 域名片段 */
            "siliconflow", "moonshot", "bigmodel", "dashscope", "volces",
            "xiaomimimo", "minimax", "spark", "qianfan", "sensenova", "longcat",
            "modelscope", "stepfun", "deepseek", "doubao", "hunyuan",
            "silicon", "zhipu", "kimi", "qwen", "tencent", "baidu", "aliyun",
            /* 中文名：预置清单里有的只写中文，不认这些会漏 */
            "智谱", "讯飞", "小米", "百度", "火山", "通义", "月之暗面", "商汤",
            "美团", "阶跃", "魔搭", "硅基",
    };

    /** 供自检枚举，避免测试里再抄一份关键词表。 */
    static String[] keysForTest() {
        return KEYS.clone();
    }
}
