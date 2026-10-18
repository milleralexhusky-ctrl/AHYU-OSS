package com.cpa.gateway;

import android.os.SystemClock;

/**
 * 性能看门狗：用**帧间隔的统计量**决定要不要削掉最贵的那层视觉。
 *
 * 这是一个反馈控制器。输入是动画期间的真实帧间隔，输出是一个 0..1 的画质档位
 * （折射强度系数），中间是一个滞回比较器 + 衰减积分。
 *
 * 为什么原来的判据不够用（这是这次重写的全部理由）：
 * 原来写的是"连续 12 帧 ≥ 22ms 就降级"，而 degrade **没有回退路径**，
 * 一旦降级就永远是 flat。两个后果：
 *
 *   ① 误判不可恢复。冷启动、切页面、一键检测同时开跑的那几秒，
 *      帧间隔必然是长的 —— 但那是"负载尖峰"，不是"这台机器吃不动"。
 *      按原判据，一次冷启动就能把这个 App 永久降成无折射，而 GPU 实测
 *      （90th percentile 3ms）明明绰绰有余。
 *
 *   ② 二值太粗。用户手上一按 2.75 密度的 1280x2772 屏，玻璃折射是渐变的观感，
 *      "有/没有"之间其实还有中间态可以退守。
 *
 * 现在的做法：
 *
 *    每帧算一个"吃力程度" cost ∈ [0,2]：
 *        dt ≤ 16.7ms → 0        （满 60fps，不吃力）
 *        dt ≥ 33.3ms → 1        （掉到 30fps 以下，明显吃力）
 *        中间线性插值           （16.7~33.3ms 之间按比例）
 *
 *    积分成 level ∈ [0,1]（EWMA，越近越重）：
 *        level ← level + α·(cost − level)
 *
 *    滞回：level ≥ HIGH 且持续 N 帧 → 降一档；level ≤ LOW 且持续 N 帧 → 升一档。
 *    α 取 0.08，大约 25 帧（0.4 秒）才走完一半行程 ——
 *    这样"偶尔一帧 40ms"只让 level 微微抬起来，"持续 40ms"才会撞上 HIGH。
 *
 *    档位 steps 是**可退的**：降下去之后只要持续流畅就会自己爬回来，
 *    所以尖峰导致的降级会在尖峰过去后自愈，不需要用户重启。
 *
 * 阈值取 16.7 / 33.3 是有依据的：60fps 一帧 16.67ms，30fps 一帧 33.3ms，
 * 这两个数字是设备刷新率决定的物理量，不是拍的。
 */
final class Perf {

    private Perf() {
    }

    /** 一帧的时间预算（60fps）。 */
    private static final double FRAME_BUDGET_MS = 16.7;

    /** 掉到 30fps 以下就算明显吃力。 */
    private static final double SLOW_MS = 33.3;

    /** level 的 EWMA 系数：0.08 → 半衰期约 8 帧，走完一半行程约 25 帧（0.4s）。 */
    private static final double ALPHA = 0.08;

    /**
     * 吃力程度超过这个值就准备降档。
     *
     * 0.80 对应稳态帧间隔约 30ms（33fps）—— 离线枚举过：
     *   24ms(42fps)→cost 0.44，26ms→0.56，28ms→0.68，30ms→0.80，33ms→0.98
     * 第一版把 HIGH 定成 0.55（约 26ms）太激进了：26ms 只是 38fps，
     * 远远没到"需要关掉折射"的程度，却会让档位一路降到 0。
     */
    private static final double HIGH = 0.80;

    /**
     * 低于这个值就准备升档。和 HIGH 拉开 0.45 才有滞回 ——
     * 死区太窄（比如 0.80/0.75）会让档位在边界上来回抖，那比不降级更糟。
     * 0.35 对应约 22ms（45fps），也正好是旧版 SLOW_FRAME_MS 的位置。
     */
    private static final double LOW = 0.35;

    /** 连续这么多帧满足条件才真动档位（防单帧尖峰）。 */
    private static final int CONFIRM_FRAMES = 10;

    /** 画质档：1=全折射，0=纯半透明。步长 1/4，最多退到 0。 */
    private static final int STEPS = 4;

    private static double level;
    private static int steps = STEPS;
    private static int highStreak;
    private static int lowStreak;
    private static int frames;
    private static long lastChangeAt;

    private static volatile float quality = 1f;

    /** 折射强度系数 0..1（{@link NavGlassDrawable} 按它缩放着色器开销）。 */
    static float quality() {
        return quality;
    }

    /** 是否已经退到最低档（等同旧版的 degraded()）。 */
    static boolean degraded() {
        return steps <= 0;
    }

    /** 动画的每一帧都会进来喂一次帧间隔。 */
    static void frame(long dtMs) {
        frames++;
        /*
         * 吃力程度：16.7ms 以下记 0，33.3ms 以上记 1，中间线性。
         * 上限夹到 1：一帧 200ms（比如 GC 或切页面）不该等于"连续 6 帧吃力"，
         * 那是另一类问题，交给 CONFIRM_FRAMES 的计数去过滤。
         */
        double cost;
        if (dtMs <= FRAME_BUDGET_MS) {
            cost = 0;
        } else if (dtMs >= SLOW_MS) {
            cost = 1;
        } else {
            cost = (dtMs - FRAME_BUDGET_MS) / (SLOW_MS - FRAME_BUDGET_MS);
        }

        level += ALPHA * (cost - level);

        if (level >= HIGH) {
            highStreak++;
            lowStreak = 0;
        } else if (level <= LOW) {
            lowStreak++;
            highStreak = 0;
        } else {
            highStreak = 0;
            lowStreak = 0;
        }

        if (highStreak >= CONFIRM_FRAMES && steps > 0) {
            steps--;
            highStreak = 0;
            apply("持续吃力（level=" + fmt(level) + "）降一档");
        } else if (lowStreak >= CONFIRM_FRAMES && steps < STEPS) {
            steps++;
            lowStreak = 0;
            apply("持续流畅（level=" + fmt(level) + "）升一档");
        }
    }

    private static void apply(String why) {
        quality = (float) steps / STEPS;
        long now = SystemClock.uptimeMillis();
        /* 日志节流：档位抖动时别把日志刷满（30 秒内只报一次） */
        if (now - lastChangeAt > 30_000L) {
            lastChangeAt = now;
            if (steps <= 0) {
                com.cpa.gateway.core.Log.w("性能看门狗：" + why
                        + " → 折射已关闭（纯半透明）");
            } else {
                com.cpa.gateway.core.Log.i("性能看门狗：" + why
                        + " → 折射 " + steps + "/" + STEPS);
            }
        }
    }

    private static String fmt(double v) {
        return String.valueOf(Math.round(v * 100.0) / 100.0);
    }

    /** 供日志／诊断用的一行状态。 */
    static String state() {
        return "quality=" + fmt(quality) + " steps=" + steps + "/" + STEPS
                + " level=" + fmt(level) + " frames=" + frames;
    }

    /** 手动复位（设置页调参用）。 */
    static void reset() {
        level = 0;
        steps = STEPS;
        highStreak = 0;
        lowStreak = 0;
        frames = 0;
        quality = 1f;
        lastChangeAt = SystemClock.uptimeMillis();
    }
}
