package com.cpa.gateway.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 请求自检（4.34.0）：在**发出去之前**把不合规的地方拦下来。
 *
 * 为什么需要它：Trae 那三个错误（tool_calls 缺 type、tool 消息缺 tool_call_id、
 * content 非空时 id 被丢）全都是**我们自己发错了**，但一直以为是渠道的问题。
 * 因为错误是在上游才爆的，而各家校验严格程度不同：
 * Trae 直接拒绝（空回复），WorkBuddy 宽容地容忍了。于是同一份坏请求，
 * 在一个渠道上表现为"这渠道不行"，在另一个渠道上表现为"一切正常"——
 * 排查方向被彻底带偏。
 *
 * 现在：入口处先自检，不合规就报 InvalidRequest（上层不会记渠道失败），
 * 并且把为什么不合法写清楚。这样"是请求错了"和"是渠道坏了"能立刻分开。
 *
 * 判据只取**协议硬性要求**，不掺个人偏好——宁可漏报，不要误伤。
 */
final class RequestCheck {

    private RequestCheck() {
    }

    /**
     * 检查一条已解析的请求。不合规抛 InvalidRequest。
     *
     * 检查项（都是真实踩过的坑）：
     *   1. assistant 的 tool_call 必须有非空 id —— 没有 id，后面没法配对返回值
     *   2. 每个 toolResult 的 toolCallId 必须能找到对应的 tool_call —— 否则上游报
     *      "Unexpected tool_call_id"
     *   3. toolResult 必须有非空 toolCallId —— 否则上游报空 id
     *   4. role 必须是协议认识的四个之一
     */
    static void check(Proto.Req req) throws InvalidRequest {
        if (req == null) return;
        List<String> problems = new ArrayList<>();

        /* 先收集所有 assistant 声明的调用 id */
        java.util.Set<String> declared = new java.util.LinkedHashSet<>();
        for (Proto.Msg m : req.messages) {
            if (m == null || m.parts == null) continue;
            for (Proto.Part p : m.parts) {
                if (p == null) continue;
                if (Proto.Part.TOOL_CALL.equals(p.type)) {
                    if (p.toolCallId == null || p.toolCallId.isEmpty()) {
                        problems.add("tool_call 缺少 id（工具 " + safe(p.toolName) + "）");
                    } else {
                        declared.add(p.toolCallId);
                    }
                    if (p.toolName == null || p.toolName.isEmpty()) {
                        problems.add("tool_call 缺少函数名");
                    }
                }
            }
        }

        /* 再检查每个工具结果能不能对上 */
        for (Proto.Msg m : req.messages) {
            if (m == null || m.parts == null) continue;
            if (m.role != null && !legalRole(m.role)) {
                problems.add("role 不合法：" + m.role);
            }
            for (Proto.Part p : m.parts) {
                if (p == null) continue;
                if (Proto.Part.TOOL_RESULT.equals(p.type)) {
                    if (p.toolCallId == null || p.toolCallId.isEmpty()) {
                        problems.add("工具结果缺少 tool_call_id（上游会报 Unexpected tool_call_id: 空）");
                    } else if (!declared.contains(p.toolCallId)) {
                        problems.add("工具结果的 tool_call_id=" + p.toolCallId
                                + " 在历史里找不到对应调用");
                    }
                }
            }
        }

        if (problems.isEmpty()) return;
        StringBuilder sb = new StringBuilder("请求不合规（我们自己这侧，不是渠道问题）：");
        for (int i = 0; i < problems.size() && i < 6; i++) {
            sb.append("\n  · ").append(problems.get(i));
        }
        if (problems.size() > 6) sb.append("\n  · 另有 ").append(problems.size() - 6).append(" 处");
        throw new InvalidRequest(400, sb.toString());
    }

    private static boolean legalRole(String role) {
        return "system".equals(role) || "user".equals(role)
                || "assistant".equals(role) || "tool".equals(role)
                || "developer".equals(role);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
