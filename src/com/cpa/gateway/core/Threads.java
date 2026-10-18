package com.cpa.gateway.core;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 全应用共享的线程池。
 *
 * 原来散落在 20+ 个文件里的 new Thread()：每次登录、每次探活、每次工具能力探测
 * 都现起线程，用完就扔。线程创建本身不贵，但高频 + 无上限在手机上就是抖动和 FD 压力。
 * 现在这几处：
 *   IO —— 后台网络/磁盘活（登录流程、一键检测、做任务、工具能力探测……），按需涨、闲时回收；
 *   BG —— 单线程串行（配置合并写这类"别并发"的小活）；
 *   PING —— SSE 心跳专用 2 线程（心跳里有 socket 写，不能卡 BG）；
 *   SWEEP —— 探活固定 8 并发。
 *
 * 请求热路径（Server 的 connPool、竞速 RACE_POOL）保持独立，不跟后台活抢线程。
 */
public final class Threads {

    private Threads() {
    }

    /**
     * 检测用的"刷目录"小池：只干这一件事（2 个线程）。
     *
     * 之前它是借 IO 池的线程，而 IO 池又被这个那个占着——实测里它明明在检测开始时就提交了，
     * 却在检测结束那一刻才被真正执行，用户看到的就是"刷新模型目录 · 0/2 · 已用 14.7s"。
     * 给它自己的线程，谁也别等谁。
     */
    public static final ExecutorService CATALOG = new java.util.concurrent.ThreadPoolExecutor(
            1, 6, 30L, java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.SynchronousQueue<Runnable>(),
            named("cpa-catalog"),
            new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy()) {{
        allowCoreThreadTimeOut(true);
    }};

    /**
     * 探测专用池（8 线程）。
     *
     * 检测/巡检的探测**不再经过本机 HTTP**（那条路要占服务端的连接线程：几十个模型一起测时，
     * 连接池被自己人占满，外部请求就排队——真机上撞到过"检测进行时监听队列积压、外部请求收不到响应"）。
     * 现在探测直接调用内部路径，跑在这个池里，互不干扰。
     */
    /*
     * 队列必须是 SynchronousQueue（不排队），**不能**用 LinkedBlockingQueue。
     *
     * 这是 ThreadPoolExecutor 的经典陷阱：它的扩展顺序是
     *   核心线程满 → 任务进队列 → 队列满 → 才创建新线程（直到 maxPoolSize）
     * 而 LinkedBlockingQueue 是无界的，"队列满"永远不会发生，
     * 于是 maxPoolSize=8 **永远用不上、实际只有 2 个线程在跑**。
     *
     * 真机实测代价（12 账号 × 2 模型 = 24 个探测）：日志显示探测是两条线程
     * 各自串行推进的（"探测完成"紧接着"探测开始"），整轮 27 秒，
     * 而所有探测耗时之和只有 52.9 秒——按并发 8 算墙钟该是 6.6 秒。
     * 那 20 秒的差就是被"只有 2 个线程"吃掉的。
     *
     * SynchronousQueue 不缓冲：没有空闲线程就立刻新建（直到 8），
     * 配合 keepAlive 闲置回收，行为和 Executors.newCachedThreadPool 一致。
     */
    public static final ExecutorService PROBE = new java.util.concurrent.ThreadPoolExecutor(
            2, 8, 30L, java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.SynchronousQueue<Runnable>(),
            named("cpa-probe")) {{
        allowCoreThreadTimeOut(true);
    }};

    /** IO 池当前活跃线程数（诊断用）。 */
    public static int ioActive() {
        try {
            return ((java.util.concurrent.ThreadPoolExecutor) IO).getActiveCount();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** SWEEP 池当前活跃线程数（诊断用）。 */
    public static int sweepActive() {
        try {
            return ((java.util.concurrent.ThreadPoolExecutor) SWEEP).getActiveCount();
        } catch (Throwable t) {
            return -1;
        }
    }

    private static ThreadFactory named(final String prefix) {
        return new ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, prefix + "-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
    }

    /**
     * 后台 IO 池：替代各处的 new Thread()，峰值 16 线程、闲 30 秒回收（连核心线程一起）。
     *
     * 队列是 SynchronousQueue（不排队）：池没满就新建线程，满了就走拒绝策略。
     * 拒绝策略是 CallerRunsPolicy —— 由**提交线程**自己跑这次任务。
     * 这是刻意的：以前是 SynchronousQueue + AbortPolicy，超过并发就直接抛
     * RejectedExecutionException，任务**静默丢失**（登录、写盘这类活悄悄没了）。
     * 现在最坏情况只是退化成同步执行（变慢），但不会丢。
     *
     * 注意调用面：Threads.io() 也被 UI 线程直接调用，所以"池满"时那一次会落到
     * UI 线程上同步跑。IO 池的上限因此不能定得太小，改动这里要一并评估这个耦合。
     */
    public static final ExecutorService IO = selfReleasing(2, 16, 30L, "cpa-io");

    /** 单线程后台：配置合并写、串行小任务。 */
    public static final ScheduledExecutorService BG = scheduled(1, "cpa-bg");

    /**
     * SSE 心跳专用：4 线程定时池。
     * 原来每条流 new 一个 Timer（1 线程/流），并发一高全是空转线程。
     * 不跟 BG 共用单线程：心跳里有 socket 写（慢客户端会阻塞），卡住 BG
     * 会拖累配置合并写这类串行小活。
     * 曾经从 2 提到 4，是因为"客户端不读数据时一次心跳写会永久阻塞在 socket 上"，
     * 两条僵尸流就能把池占满。现在有僵尸流看门狗兜底（45 秒写不出去就关掉那条流），
     * 池子回到 2；而且这个池空闲时会自己把线程还回系统，不再常驻。
     */
    public static final ScheduledExecutorService PING = scheduled(2, "cpa-ping");

    /**
     * 探活/扫模型：不再单独养一个池，直接复用 IO 池，用信号量把并发压在 4。
     *
     * 原来是一个固定 4 线程的常驻池——为了"探活别把机器打满"而存在；但 4 条常驻线程
     * 是为了一个几小时才跑一次的任务养着的，不划算。现在 IO 池干活、信号量限流，
     * 行为一样（并发 ≤ 4），后台少一个池。
     */
    /**
     * 巡检/检测专用池：**自己的固定线程池**，不再"借 IO 池的线程 + 信号量限流"。
     *
     * 原来的 Capped(IO, slots=4) 有两个致命问题，真机上已经撞上：
     *  1. 每个提交进来的任务要先**占住一个 IO 线程**再去抢许可 —— 70 个模型的后台巡检
     *     会把 IO 池（最大 16 线程）整池占满，于是所有别的 IO 活儿全部排队；
     *  2. 池里的任务如果再往同一个池提交并等结果，就会把自己的许可等死（检测就是这么卡住的：
     *     启动后 done 停在 2/8、current 一直是空，任务压根没线程可用）。
     *
     * 现在它就是一个普通固定池（8 线程），谁都借不走、也不会自己把自己锁住。
     */
    public static final ExecutorService SWEEP = new java.util.concurrent.ThreadPoolExecutor(
            2, 8, 30L, java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.SynchronousQueue<Runnable>(),
            named("cpa-sweep"),
            new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy()) {{
        allowCoreThreadTimeOut(true);
    }};

    /** 把并发上限压在信号量上的执行器：submit 出去的任务先抢位再跑。 */
    private static final class Capped extends java.util.concurrent.AbstractExecutorService {
        private final ExecutorService delegate;
        private final java.util.concurrent.Semaphore slots;

        Capped(ExecutorService delegate, java.util.concurrent.Semaphore slots) {
            this.delegate = delegate;
            this.slots = slots;
        }

        @Override
        public void execute(final Runnable r) {
            delegate.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        slots.acquire();
                    } catch (InterruptedException e) {
                        return;
                    }
                    try {
                        r.run();
                    } finally {
                        slots.release();
                    }
                }
            });
        }

        @Override
        public void shutdown() {
        }

        @Override
        public java.util.List<Runnable> shutdownNow() {
            return java.util.Collections.emptyList();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return false;
        }
    }

    /**
     * 会自己还线程的池：空闲超过 keepAlive 秒，连核心线程都回收。
     *
     * 手机上"闲着但占着"的线程最不值：每条都要占栈和内核对象，而我们的后台活
     * （登录、探活、写盘）都是几个小时一次。默认的 ThreadPoolExecutor 会把
     * 核心线程一直养着，这里显式让它们闲时退出、要用时再建。
     */
    private static ThreadPoolExecutor selfReleasing(int core, int max, long keepAliveSec, String name) {
        ThreadPoolExecutor p = new ThreadPoolExecutor(
                core, max, keepAliveSec, TimeUnit.SECONDS,
                // 同上：无界队列会让 max 永远用不上（见 PROBE 的注释）。
                new java.util.concurrent.SynchronousQueue<Runnable>(),
                named(name),
                // SynchronousQueue 在满的时候会拒绝，而这里**绝不能丢任务**
                // （原来的无界队列就是为了不丢才改的）。CallerRunsPolicy 让被拒的任务
                // 由提交线程自己执行：线程够就异步跑，全忙时退化为同步跑——变慢，但不丢。
                new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        p.allowCoreThreadTimeOut(true);
        return p;
    }

    /** 同上，用于定时池（BG / PING）。 */
    private static ScheduledExecutorService scheduled(int core, String name) {
        java.util.concurrent.ScheduledThreadPoolExecutor p =
                new java.util.concurrent.ScheduledThreadPoolExecutor(core, named(name));
        p.setKeepAliveTime(30, TimeUnit.SECONDS);
        p.allowCoreThreadTimeOut(true);
        return p;
    }

    /** 往后台 IO 池扔一个任务（替代 new Thread(r).start()）。 */
    public static void io(Runnable r) {
        IO.execute(r);
    }
}
