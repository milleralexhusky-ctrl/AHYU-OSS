package com.cpa.gateway;

import android.app.Application;
import android.app.Application.ActivityLifecycleCallbacks;
import android.content.ComponentCallbacks2;
import android.content.Context;

/**
 * 进程最早的回调点：只做"必须最早做"的事。
 *
 * 配色要先定下来（底纹的蒙版与缓存键都跟它有关），否则浅色模式下会先按深色画一版。
 *
 * 4.38.0 起**不再**在这里预热底纹位图：AHYU 大多数时间是后台网关（开机自启、
 * 保活拉起），进程启动时根本没有界面，那张 1/2 屏的位图（约 3.5MB）会一直
 * 白白占着。现在改为界面第一次 onCreate 时由 {@link Ui#darkSystemBars} 的
 * warmSync 画；界面隐藏时在 {@link #onTrimMemory} 里释放。
 */
public class CpaApp extends Application {

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        try {
            com.cpa.gateway.core.Config cfg = com.cpa.gateway.core.Config.load(base);
            Ui.applyTheme(cfg.accentTheme, cfg.lightMode);
        } catch (Throwable ignored) {
        }
    }

    /** 当前处于 STARTED 的 Activity 数；归零 = 整个界面不可见了。 */
    private int startedActivities;

    @Override
    public void onCreate() {
        super.onCreate();
        /* 用 Activity 生命周期判断"整个 UI 是否隐藏"，不依赖 onTrimMemory(UI_HIDDEN)：
           后者在部分 ROM / 有前台服务的进程里不一定会按时送达。 */
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(android.app.Activity activity, android.os.Bundle b) {
            }

            @Override
            public void onActivityStarted(android.app.Activity activity) {
                startedActivities++;
            }

            @Override
            public void onActivityResumed(android.app.Activity activity) {
            }

            @Override
            public void onActivityPaused(android.app.Activity activity) {
            }

            @Override
            public void onActivityStopped(android.app.Activity activity) {
                if (startedActivities > 0) startedActivities--;
                /* 0 表示没有任何页面可见（回桌面 / 切到别的应用）。
                   页面之间跳转时旧页先 stop、新页已 start，计数不会归零。 */
                if (startedActivities == 0 && BackdropTex.release()) {
                    com.cpa.gateway.core.Log.i("内存回收：界面已隐藏，释放底纹位图");
                }
            }

            @Override
            public void onActivitySaveInstanceState(android.app.Activity activity, android.os.Bundle b) {
            }

            @Override
            public void onActivityDestroyed(android.app.Activity activity) {
            }
        });
        // 调试构建才挂：Activity 泄漏、帧间隔、体量（见 Diagnose）
        Diagnose.install(this);
    }

    /**
     * 内存压力下也释放可以重画的底纹位图。
     *
     * 只释放位图，不动配置/模型/价格表这些网关运行必需的数据；下次打开界面
     * 会重新画一张（约 60ms），功能与观感不变。
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        /* 只在界面不可见时释放。RUNNING_CRITICAL(15) 是"可见但内存吃紧"：
           这时回收底纹，下一帧 NavGlassDrawable 又立刻 get() 重画，
           只会制造"释放 → 重画"的抖动，实际省不下来。 */
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
                && startedActivities == 0 && BackdropTex.release()) {
            com.cpa.gateway.core.Log.i("内存回收：level=" + level + "，释放底纹位图");
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        if (startedActivities == 0) BackdropTex.release();
    }
}
