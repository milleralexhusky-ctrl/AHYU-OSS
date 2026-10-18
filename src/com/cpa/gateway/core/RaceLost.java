package com.cpa.gateway.core;

import java.io.IOException;

/**
 * 竞速里"这一路输了"的信号。
 *
 * 用异常来取消：{@link Upstreams#postStream} 的读循环里会调用 sink，从 sink 抛出来
 * 就能立刻跳出循环，finally 里 conn.disconnect() 把这条上游连接断掉。
 *
 * 单独一个类型是为了让上层能区分：**输了不等于失败**，
 * 不能因此给这个账号记错误、更不能把它打进冷却。
 */
final class RaceLost extends IOException {

    RaceLost(String racer) {
        super("race lost: " + racer);
    }
}
