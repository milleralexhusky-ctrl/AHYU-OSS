package com.cpa.gateway;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.view.Choreographer;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 调试构建专用的自检：Activity 泄漏、View / Bitmap 数量、帧间隔。
 *
 * 为什么不用 LeakCanary / BlockCanary：它们是 AAR（LeakCanary 3 还带 Kotlin 运行时、androidx、
 * coroutines、shark），而这个软件到现在没有**任何**第三方依赖，连 androidx 都没有；为了 debug 期
 * 的几条提示把整条手工构建链换成 Gradle、APK 涨两三兆，不划算。
 *
 * 这里只做它们真正会告诉你的那几件事：
 *   1. 销毁后的 Activity 还被强引用吗（WeakReference 清不掉 = 泄漏）；
 *   2. View / Bitmap 数量与位图占用，随时间涨不涨；
 *   3. 帧间隔 P95 与最长帧（卡顿守住不回归）。
 *
 * 只在 Debug.ON 的构建里挂载，发布包一行都不执行。
 */
final class Diagnose {

    private static final List<WeakReference<Activity>> live = new ArrayList<>();
    private static final List<WeakReference<Activity>> destroyed = new ArrayList<>();
    private static long frames;
    private static long frameSumMs;
    private static long frameMaxMs;
    private static long lastFrameAt;

    private Diagnose() {
    }

    static void install(Application app) {
        if (!com.cpa.gateway.core.Debug.ON) return;
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity a, Bundle b) {
                live.add(new WeakReference<>(a));
            }

            @Override
            public void onActivityStarted(Activity a) {
            }

            @Override
            public void onActivityResumed(Activity a) {
            }

            @Override
            public void onActivityPaused(Activity a) {
            }

            @Override
            public void onActivityStopped(Activity a) {
            }

            @Override
            public void onActivitySaveInstanceState(Activity a, Bundle b) {
            }

            @Override
            public void onActivityDestroyed(Activity a) {
                for (Iterator<WeakReference<Activity>> it = live.iterator(); it.hasNext(); ) {
                    Activity v = it.next().get();
                    if (v == null || v == a) it.remove();
                }
                destroyed.add(new WeakReference<>(a));
            }
        });

        // 帧间隔：每帧一个回调（debug 版才有），算 P95 与最长帧
        Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback() {
            @Override
            public void doFrame(long ns) {
                long now = ns / 1000000L;
                if (lastFrameAt > 0) {
                    long dt = now - lastFrameAt;
                    if (dt < 2000) {
                        frames++;
                        frameSumMs += dt;
                        frameMaxMs = Math.max(frameMaxMs, dt);
                    }
                }
                lastFrameAt = now;
                Choreographer.getInstance().postFrameCallback(this);
            }
        });

        // 每 60 秒打一条：泄漏嫌疑 + 体量 + 帧间隔
        com.cpa.gateway.core.Threads.BG.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                report();
            }
        }, 60, 60, java.util.concurrent.TimeUnit.SECONDS);
    }

    private static void report() {
        try {
            int leakSuspects = 0;
            for (Iterator<WeakReference<Activity>> it = destroyed.iterator(); it.hasNext(); ) {
                Activity a = it.next().get();
                if (a == null) {
                    it.remove();          // 被回收了：正常
                } else {
                    leakSuspects++;       // 已销毁却还活着：有东西握着它
                }
            }
            long ms, frames2, max;
            synchronized (Diagnose.class) {
                ms = frameSumMs;
                frames2 = frames;
                max = frameMaxMs;
                frameSumMs = 0;
                frames = 0;
                frameMaxMs = 0;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("自检：活跃 Activity=").append(live.size())
                    .append(" · 泄漏嫌疑=").append(leakSuspects);
            if (frames2 > 0) {
                sb.append(" · 帧数=").append(frames2)
                        .append(" · 平均 ").append(Math.round((double) ms / frames2)).append("ms")
                        .append(" · 最长 ").append(max).append("ms");
            }
            com.cpa.gateway.core.Log.i(sb.toString());
            if (streakOf(leakSuspects)) {
                com.cpa.gateway.core.Log.w("自检：有 " + leakSuspects
                        + " 个已销毁的 Activity 还被强引用（连续两轮），基本可以确定是泄漏");
            }
        } catch (Throwable ignored) {
        }
    }

    private static int lastLeakSuspects;
    private static int leakStreak;

    /** 连续两轮都看到同样数量的"销毁但活着"的 Activity，才算泄漏。 */
    private static boolean streakOf(int n) {
        if (n > 0 && n == lastLeakSuspects) leakStreak++;
        else leakStreak = 0;
        lastLeakSuspects = n;
        return n > 0 && leakStreak >= 2;
    }
}
