package com.cpa.gateway.core;

import java.io.IOException;

/**
 * 上游返回 4xx，且不是限流/额度/凭证问题——多半是**客户端请求本身**有问题。
 *
 * 为什么单独一类：400 这类错误换哪个账号都一样（参数不合法、模型名不存在、上下文超限），
 * 按"这个通道坏了"记失败会：
 *   ① 把健康账号打进熔断；
 *   ② 虚拟模型的 failover 会对每个候选各记一次失败，**一次坏请求就打残整组**。
 *
 * 上层据此区别对待：不记 failstreak，但仍把错误如实返回给客户端。
 */
final class InvalidRequest extends IOException {

    final int code;

    InvalidRequest(int code, String message) {
        super(message);
        this.code = code;
    }
}
