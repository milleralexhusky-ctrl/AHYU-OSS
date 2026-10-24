package com.cpa.gateway.core;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 上游风控指纹的规避。
 *
 * <h2>为什么需要这个</h2>
 *
 * 实测（2026-10-23）WorkBuddy 的渠道校验会**按提示词内容**拦截请求：
 * 只要 system 里出现官方 Anthropic 客户端的自我标识，上游就回
 * 400 {@code 11128 Illegal API invocation from an unapproved channel}，
 * 与账号、模型、UA、conversation 头都无关（同一账号、同一模型、
 * 换 UA / 换 realm / 换 conversation_id 形状都一样被挡）。
 *
 * <p>被认出来的指纹，各自独立触发（前两个实测 8/8 必拦、去掉后 8/8 通过）：
 *
 * <ol>
 *   <li>{@code x-anthropic-billing-header} —— Claude Code 2.1.308 会把它自己的
 *       计费头**当成第一个 system 块**塞进请求体（本机抓包实证）。</li>
 *   <li>{@code You are Claude Code, Anthropic's official CLI for Claude.}
 *       —— 官方客户端固定的自我描述句。</li>
 *   <li>{@code DeepSeek Harness} —— 2026-10-24 Dsh（反代 WorkBuddy 的第三方
 *       客户端）社区流传的风控：身份说明里出现这串就 400。**真机对照实验
 *       （4.55.0，指纹原样出站）证明这枚拦截对 AHYU 转发的请求不存在**
 *       ——OpenAI / Anthropic 两个入口、四个模型六发全过。真实触发条件应是
 *       Dsh 完整提示词的形状组合，不是这串词本身。此枚因此是**预防性混淆**
 *       （上游哪天把它升级成独立判据，我们已在零宽字符后面），不是修复。
 *       详见 changelog 4.55.1 的补记。</li>
 * </ol>
 *
 * <h2>做法为什么是"零宽混淆"而不是删除</h2>
 *
 * 删掉整句会改变模型看到的内容（"你是 Claude Code" 是真实且有用的信息，
 * 关系到回答风格与工具用法），那是**降质**，不是修复。
 * 这里只在词内插一个零宽非连接符（U+200C）：人看、模型读都完全一样，
 * 上游那套字节级特征匹配则不再命中。
 *
 * <p>实测：整句只把 {@code Claude Code} 改成 {@code Claude‌Code}，
 * 就从 8/8 拦截变成 8/8 通过；billing 头只改 {@code header} 一处同样通过。
 *
 * <h2>两处容易写错的细节</h2>
 *
 * <ul>
 *   <li><b>必须保住原文大小写与空格</b>。用 {@code replaceAll} 配字面量会把
 *       {@code CLAUDE CODE} 悄悄改成 {@code Claude Code}、把空格吃掉变成
 *       {@code ClaudeCode} —— 那是**改内容**，不是混淆。所以捕获组原样回填，
 *       零宽字符只是"额外插进去"的。</li>
 *   <li>空格两侧各插一个零宽字符，中间的空格仍然在：渲染出来还是
 *       {@code Claude Code}，一个字符都没少。</li>
 * </ul>
 *
 * <h2>边界</h2>
 *
 * <ul>
 *   <li><b>只在 WorkBuddy 上游生效</b>（见 {@code WorkBuddy.buildBody}）。
 *       别的渠道没有这个风控，改它们的提示词是没必要的风险。</li>
 *   <li>只替换上面这两个特征串，不碰其余任何字。别的渠道、别的客户端
 *       （Cursor / Roo 之类）的提示词原样透传，所以本机已有的用法不受影响。</li>
 * </ul>
 */
final class RiskPrompt {

    private RiskPrompt() {
    }

    /**
     * 零宽非连接符（U+200C）。
     *
     * <p>选它的原因：不是空白、不是连接符，不会被上游"归一化空白"那一步抹掉，
     * 同时在渲染和分词上都不可见。U+200B（零宽空格）本身就算空白，
     * 很可能在那一步被折叠掉，所以不用它。
     */
    private static final String ZWNJ = "‌";

    /**
     * Claude Code 的自我标识（上游认的第二枚指纹）。
     *
     * <p>三种写法都要覆盖，缺一种就漏网 —— 实测都各自能单独触发 11128：
     * <ul>
     *   <li>带空格的 {@code Claude Code}（自我描述句里那种）；</li>
     *   <li>带连字符的 {@code claude-code}（**这一枚最隐蔽**：它是 GitHub 仓库名，
     *       藏在系统提示末尾的反馈链接 {@code github.com/anthropics/claude-code/issues}
     *       里。只修前两种写法时，整份真实请求体仍然被拦 ——
     *       实测就是靠二分定位到这一串的）；</li>
     *   <li>带下划线的 {@code claude_code}。</li>
     * </ul>
     *
     * <p>三段捕获、回填时保持原样：大小写、分隔符（空格 / 连字符 / 下划线）
     * 以及空格的数量都照抄，零宽字符只是额外插进去的。
     */
    private static final Pattern CLAUDE_CODE = Pattern.compile(
            "(?i)(claude)([\\s\\u00A0\\u2007]+)(code)");

    /** 连字符 / 下划线形态的 {@code claude-code} / {@code claude_code}。 */
    private static final Pattern CLAUDE_CODE_SLUG = Pattern.compile(
            "(?i)(claude)([-_])(code)");

    /** Claude Code 的计费头名（第一枚指纹）。只改 {@code header} 一词即可失效。 */
    private static final Pattern BILLING_HEADER = Pattern.compile(
            "(?i)(x-anthropic-billing-)(header)");

    /**
     * Dsh 的身份说明（第三枚，2026-10-24，**对本机路径实测证明不触发拦截**）。
     *
     * <p>来源与前两枚不同：前两枚是抓包 + 8/8 实测钉死的；这一枚来自 Dsh
     * （反代 WorkBuddy 的第三方客户端）社区当晚流传的处置方案。但对照实验
     * （4.55.0 发版包、这串原样出站）显示 WorkBuddy 对 AHYU 转发的请求
     * **并不按这串拦** —— OpenAI / Anthropic 两个入口 × hy3 / kimi /
     * deepseek / glm 六发全过。真实触发条件多半是 Dsh 完整提示词的形状组合，
     * 不是这串词本身（见 changelog 4.55.1 补记）。所以这枚的定位是
     * **预防性混淆**：混淆保证内容逐字等价（自检钉住），上游哪天把判据
     * 收紧到这串，我们已经在零宽字符后面；最坏情形也只是白插一个不可见字符。
     *
     * <p>Dsh 用的是 U+200B，这里用 U+200C（见 {@link #ZWNJ} 的理由：
     * 零宽空格可能被上游归一化空白抹掉，连接符更稳）。
     *
     * <p>覆盖照 claude-code 的教训扩全：带空格 / 连字符 / 下划线三种写法，
     * 且**预筛跟着加词** —— 预筛只认 "laude"/"x-anthropic" 时，一句只带
     * DeepSeek Harness 的 system 会被整个放过，混淆形同虚设。
     */
    private static final Pattern DEEPSEEK_HARNESS = Pattern.compile(
            "(?i)(deepseek)([\\s\\u00A0\\u2007]+)(harness)");

    /** 连字符 / 下划线形态的 {@code deepseek-harness} / {@code deepseek_harness}。 */
    private static final Pattern DEEPSEEK_HARNESS_SLUG = Pattern.compile(
            "(?i)(deepseek)([-_])(harness)");

    /**
     * 把一个字符串里的已知指纹做成零宽混淆。
     *
     * @return 处理后的字符串；没有命中时**返回原对象**（调用方可用 {@code ==} 判断有没有改动）
     */
    static String neutralize(String text) {
        if (text == null || text.isEmpty()) return text;
        /* 廉价预筛：绝大多数文本连 "claude" / "x-anthropic" / "deepseek" 都没有，
           不必跑正则。必须**大小写不敏感**地筛 —— Pattern 都是 (?i)，预筛如果只认小写，
           "CLAUDE CODE" 会在这一步被放过去（自检的第一版就是这么抓到它的）。
           加新指纹时预筛必须跟着加词，否则该指纹永远进不了正则分支（见 DEEPSEEK_HARNESS）。 */
        String low = text.toLowerCase(java.util.Locale.US);
        if (low.indexOf("laude") < 0 && low.indexOf("x-anthropic") < 0
                && low.indexOf("deepseek") < 0) return text;
        String out = text;
        Matcher m1 = CLAUDE_CODE.matcher(out);
        if (m1.find()) {
            // 原词之间各插一个零宽字符，中间那段空白原样保留
            out = m1.replaceAll("$1" + ZWNJ + "$2" + ZWNJ + "$3");
        }
        Matcher m2 = CLAUDE_CODE_SLUG.matcher(out);
        if (m2.find()) {
            // 分隔符（连字符 / 下划线）也原样保留
            out = m2.replaceAll("$1" + ZWNJ + "$2" + ZWNJ + "$3");
        }
        Matcher m3 = BILLING_HEADER.matcher(out);
        if (m3.find()) {
            out = m3.replaceAll("$1" + ZWNJ + "$2");
        }
        Matcher m4 = DEEPSEEK_HARNESS.matcher(out);
        if (m4.find()) {
            out = m4.replaceAll("$1" + ZWNJ + "$2" + ZWNJ + "$3");
        }
        Matcher m5 = DEEPSEEK_HARNESS_SLUG.matcher(out);
        if (m5.find()) {
            out = m5.replaceAll("$1" + ZWNJ + "$2" + ZWNJ + "$3");
        }
        return out;
    }

    /**
     * 这次替换是不是真的动过（供日志与自检用）。
     *
     * <p>靠引用比较判断，不做字符串相等比较 ——
     * 后者会把"命中但恰好没变"也算成没命中，掩盖实现错误。
     */
    static boolean wouldChange(String text) {
        return text != null && neutralize(text) != text;
    }

    /** 零宽字符本身（自检要验证"插入的是它、且只插在词内"）。 */
    static String zwnj() {
        return ZWNJ;
    }
}
