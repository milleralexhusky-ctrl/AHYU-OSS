package com.cpa.gateway.core;

/**
 * 上游"成功"返回了（HTTP 200 / 正常结束），但里面没有任何正文、思考或工具调用。
 *
 * 为什么要单独一类：以前这种流被当成成功，客户端拿到一条空回答（或只有
 * finish/[DONE] 的空壳），看起来就是"网关不回话"。它不是账号问题，也不是
 * 请求问题，换一个候选往往就有内容 —— 按 TRANSIENT 处理，允许换号/换模型。
 */
final class EmptyUpstream extends java.io.IOException {
    EmptyUpstream(String message) {
        super(message);
    }
}
