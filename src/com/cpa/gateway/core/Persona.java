package com.cpa.gateway.core;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "无违和感"：把模型的自报家门去掉。
 *
 * 客户端填的是一个虚拟模型名（auto / auto-race），背后可能换了七八个不同厂商的模型。
 * 如果每个都开口"我是 DeepSeek"、"作为一个 AI 语言模型"，用户立刻就穿帮了。
 *
 * 只处理**第一次输出**——模型基本都在开头自报家门，这样不增加首字延迟，
 * 也不用把整段答案缓存下来再切（那会让首字变慢）。
 */
final class Persona {

    private Persona() {
    }

    /** 每个响应一个，只对第一次输出动手。 */
    static final class Stripper {
        private boolean done;

        String clean(String delta) {
            if (done || delta == null || delta.isEmpty()) return delta;
            done = true;
            String out = strip(delta);
            if (out != null && !out.equals(delta)) {
                Log.i("去自报家门：" + shortBefore(delta) + " → " + shortBefore(out));
            }
            return out == null ? delta : out;
        }

        private static String shortBefore(String s) {
            String t = s.replace('\n', ' ').trim();
            return t.length() > 24 ? t.substring(0, 24) + "…" : t;
        }
    }

    /** 厂商/模型名。 */
    private static final String VENDORS =
            "DeepSeek|Deepseek|深度求索|Claude|Anthropic|GPT-?[0-9A-Za-z\\.]*|ChatGPT|OpenAI|"
                    + "Gemini|Google|Kimi|月之暗面|豆包|Doubao|字节|Trae|"
                    + "通义千问|通义|Qwen|阿里|文心一言|文心|百度|混元|腾讯|Hunyuan|"
                    + "GLM|智谱|MiniMax|讯飞|Loomy|WorkBuddy|CodeBuddy";

    private static final Pattern[] INTROS = {
            // 我是 DeepSeek，由深度求索开发…
            Pattern.compile("(?i)^\\s*(我是|我就是|我是由|我叫)\\s*[^，。！\\n]{0,16}?"
                    + "(" + VENDORS + ")[^。！\\n]{0,50}[。！，,、:：\\n]\\s*"),
            // 作为一个人工智能/语言模型…
            Pattern.compile("(?i)^\\s*(作为一个|作为)\\s*(人工智能|AI|大语言模型|语言模型|大模型|智能助手|助手)"
                    + "[^。！\\n]{0,40}[。！，,\\n]\\s*"),
            // As an AI language model, I… —— 只吃到逗号，别把后面的回答也带走
            Pattern.compile("(?i)^\\s*(As an AI( language model)?|I am (a|an) (large )?language model)"
                    + "\\s*[,，]\\s*"),
            // 后面紧跟的自报家门（不完全在开头也算）
            Pattern.compile("(?i)^\\s*(我是)?\\s*(由)?(" + VENDORS + ")\\s*(公司)?\\s*(开发|训练|推出|研发)的"
                    + "[^。！\\n]{0,40}[。！，,\\n]\\s*"),
    };

    /**
     * 去掉开头的自报家门。
     *
     * @return 处理后的文本（没匹配就原样返回）
     */
    static String strip(String s) {
        if (s == null || s.isEmpty()) return s;
        String out = s;
        for (int round = 0; round < 2; round++) {        // 可能连着两句
            boolean changed = false;
            for (Pattern p : INTROS) {
                Matcher m = p.matcher(out);
                if (m.find() && m.start() == 0) {
                    out = out.substring(m.end());
                    changed = true;
                    break;
                }
            }
            if (!changed) break;
        }
        // 整段都是自报家门 → 保留原文。宁可有点违和感，也不能给客户端一个空回答
        if (out.trim().isEmpty() && !s.trim().isEmpty()) {
            Log.i("去自报家门：整段都是自我介绍，保留原文（否则回答会变成空的）");
            return s;
        }
        return out;
    }
}
