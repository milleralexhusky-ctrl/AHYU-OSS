package com.cpa.gateway.core;

/**
 * 网关的运行状态——**唯一**的权威表述。
 *
 * ------------------------------------------------------------------
 * 为什么需要它
 *
 * 在 4.30.18–4.30.20 三轮修复里，"网关到底在不在跑"这件事先后由这些东西各自回答：
 *   · Gateway.server != null
 *   · Server.running
 *   · Server.socket 是否绑定
 *   · GatewayService.instance != null
 *   · Config.keepAlive
 *   · Config.userStopped
 *   · 主界面按钮的文案
 *   · 通知栏的文案
 *   · 快捷磁贴的 STATE
 *
 * 十个量、二十多处判断，分散在十一个文件。每加一个维度就要在所有地方对齐一次，
 * 而任何一处漏掉就变成"状态对不上"——三次事故都是这么来的：
 *
 *   4.30.18 客户端不停重连：旧实例的 onDestroy 把新实例刚绑好的监听关掉
 *   4.30.19 第一次按启动无效：保活任务抢先拉起，界面还写着「启动网关」，
 *           用户一按就走了停止分支
 *   4.30.20 界面长期显示过时状态：refreshStatus 只有事件驱动，没有周期性对齐
 *
 * 问题不在某一次实现，而在"多个变量各自表达同一件事"这个结构本身。
 *
 * ------------------------------------------------------------------
 * 用法约定
 *
 *   · 只有 {@link Gateway} 能改它（持有写方法），其它地方一律只读。
 *   · 想判断"要不要自动拉起"就问 {@link #shouldAutoStart()}，不要自己拼
 *     keepAlive && !userStopped && !isRunning()。
 *   · 想判断"用户看到什么"就问 {@link #label()}，不要用字符串 contains 反推。
 */
public enum GatewayState {

    /**
     * 用户主动停掉的。保活任务**不许**把它拉起来。
     *
     * 这一条以前靠 Config.userStopped 这个第三方布尔表达，能组出
     * userStopped=true && isRunning()=true 这种不可能的组合（4.30.19 真发生过）。
     */
    STOPPED_BY_USER,

    /**
     * 没在跑，但不是用户停的（进程刚起、意外被杀、启动失败后放弃）。
     * 保活任务**应该**把它拉起来。
     */
    STOPPED_IDLE,

    /** 正在绑端口／初始化。 */
    STARTING,

    /** 在对外服务。 */
    RUNNING,

    /** 上一次启动失败了，等用户重试或下一轮保活。 */
    FAILED;

    /** 对外是否可服务。 */
    public boolean isServing() {
        return this == RUNNING;
    }

    /** 界面上的状态点用：是否在跑（含正在启动）。 */
    public boolean isAlive() {
        return this == RUNNING || this == STARTING;
    }

    /**
     * 保活任务是否应该自动拉起。
     *
     * 以前散在 KeepAliveJobService 里写成三个条件的与：
     * {@code cfg.keepAlive && !cfg.userStopped && !Gateway.isRunning()}。
     * 那三个条件里有两个是在描述"状态"，现在归到这里，调用方只剩
     * "用户偏好 ∧ 状态允许"两件事。
     */
    public boolean shouldAutoStart() {
        return this == STOPPED_IDLE || this == FAILED;
    }

    /** 给界面看的一句话（指示灯、磁贴、通知都用同一个词）。 */
    public String label() {
        switch (this) {
            case RUNNING:
                return "运行中";
            case STARTING:
                return "正在启动";
            case FAILED:
                return "启动失败";
            case STOPPED_BY_USER:
                return "已停止";
            case STOPPED_IDLE:
            default:
                return "未运行";
        }
    }
}
