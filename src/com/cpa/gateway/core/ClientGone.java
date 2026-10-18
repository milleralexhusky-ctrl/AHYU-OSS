package com.cpa.gateway.core;

import java.io.IOException;

/**
 * 客户端已经不在了（连接断开 / 主动取消）。
 *
 * 为什么必须和"上游失败"分开：移动网络闪断、用户点了停止、App 切后台，
 * 这些都会让**写响应**这一步抛 IOException（Broken pipe / Connection reset）。
 * 以前它和上游错误走同一个 catch，被记进 streak —— 连闪三次就把一个完全健康的
 * 账号打进 60 秒→10 分钟的熔断。
 *
 * 这类异常的正确语义是"这次没人接收结果了"，与上游表现无关：
 * 不记失败、不进冷却，也不该影响任何账号的健康判定。
 */
final class ClientGone extends IOException {

    ClientGone(String message) {
        super(message);
    }
}
