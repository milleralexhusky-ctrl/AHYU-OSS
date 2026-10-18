package com.cpa.gateway;

import android.app.Application;
import android.content.Context;

/**
 * 进程最早的回调点：主题底纹的活从"打开主页"挪到了这里。
 *
 * 原来底纹是 MainActivity.onCreate 里同步备出来的（首次要现画 58ms，全部加在首帧上）。
 * attachBaseContext 比任何 Activity 都早：先让后台线程去解码照片、读/画底纹，等
 * MainActivity 真要画第一帧时，UI 线程那次 warmSync 等的只是"剩余时间"（常常是 0），
 * 而不是从头再做一遍。
 */
public class CpaApp extends Application {

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        // 先按磁盘配置把配色/深浅模式定下来：底纹的蒙版与缓存键都跟它有关，
        // 否则浅色模式下会先按深色画一版底纹（错的那一版还会进缓存）。
        try {
            com.cpa.gateway.core.Config cfg = com.cpa.gateway.core.Config.load(base);
            Ui.applyTheme(cfg.accentTheme, cfg.lightMode);
        } catch (Throwable ignored) {
        }
        // 此刻 Application 还没 onCreate，但 Context 可用，够算色版了
        BackdropTex.startWarm(base);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        BackdropTex.startWarm(this);
        // 调试构建才挂：Activity 泄漏、帧间隔、体量（见 Diagnose）
        Diagnose.install(this);
    }
}
