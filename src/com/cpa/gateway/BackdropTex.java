package com.cpa.gateway;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.util.DisplayMetrics;

/**
 * 背景纹理：打开应用的第一帧就要是那张画。
 *
 * 玻璃要"折射背后的东西"，就得先把背景变成一张能采样的纹理。
 * 把当前配色渲染成一张 **1/2 分辨率** 的位图，供导航玻璃采样：
 * 肉眼几乎看不出差别，采样和显存开销直接省一半（这是液体玻璃最划算的一刀）。
 *
 * 4.30.22 起不再有任何图片：底纹由 {@link Swatch} 按当前配色现算。
 * 于是"照片解码迟到导致背景慢半拍"这一类问题整体消失——
 * 没有异步素材，就不会有素材迟到。缓存键只剩配色版本号。
 */
public final class BackdropTex {

    private static Bitmap bitmap;
    /** 画这张缓存时用的配色戳；戳变了才重画。 */
    private static Object builtWith;
    private static int cachedW;
    private static int cachedH;
    /** 纹理相对屏幕的缩放（固定 1/2，留作采样矩阵用）。 */
    private static float cachedScale = 0.5f;

    private BackdropTex() {
    }

    /** 配色模式变了（深↔浅）就作废：底纹里那层蒙版不一样，必须重画。 */
    static synchronized void invalidate() {
        drop();
    }

    /**
     * 界面隐藏或内存吃紧时释放底纹位图（4.38.0）。
     *
     * 这张图是 1/2 屏的 ARGB_8888（1280×2772 上约 3.5MB），而 AHYU 大多数时间
     * 是后台网关，Activity 处于 STOPPED、根本不需要它。以前它从进程启动一直留到
     * 进程结束；现在 UI 一隐藏就还给系统，下次打开由 {@link #warmSync} 重画（约 60ms）。
     *
     * 只回收像素：NavGlassDrawable 下次 draw() 会调用 get() 并发现 bitmap 换了，
     * 自动重建 BitmapShader；Shader 对旧 bitmap 的引用随 GC 消失。
     */
    static synchronized boolean release() {
        return drop();
    }

    private static boolean drop() {
        Bitmap old = bitmap;
        bitmap = null;
        builtWith = null;
        cachedW = 0;
        cachedH = 0;
        cachedScale = 0.5f;
        if (old != null && !old.isRecycled()) {
            try {
                old.recycle();
            } catch (Throwable ignored) {
            }
        }
        return old != null;
    }

    /**
     * 同步预热：在 UI 线程把底纹备好（内存 → 磁盘 → 现画），保证第一帧就是画。
     * 只在"这个进程还没画过底纹"时花钱；同一进程里后续页面是内存命中，零成本。
     */
    static synchronized void warmSync(Context c) {
        if (bitmap != null && !bitmap.isRecycled()) return;
        long t0 = android.os.SystemClock.uptimeMillis();
        get(c);
        com.cpa.gateway.core.Log.i("底纹：按配色现画，用时 "
                + (android.os.SystemClock.uptimeMillis() - t0) + "ms");
    }

    static synchronized Bitmap get(Context c) {
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        int sw = dm.widthPixels;
        int sh = dm.heightPixels;
        if (bitmap != null && !bitmap.isRecycled() && cachedW == sw && cachedH == sh
                && builtWith == Swatch.stamp) {
            return bitmap;
        }
        int bw = Math.max(1, sw / 2);
        int bh = Math.max(1, sh / 2);
        Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(b);
        canvas.scale((float) bw / sw, (float) bh / sh);
        Swatch.paint(canvas, sw, sh);
        bitmap = b;
        cachedW = sw;
        cachedH = sh;
        cachedScale = (float) bw / sw;
        builtWith = Swatch.stamp;
        return bitmap;
    }

    /** 纹理相对屏幕的缩放比（位图像素 / 屏幕像素）。 */
    static synchronized float scale(Context c) {
        if (bitmap != null && !bitmap.isRecycled() && cachedScale > 0f) return cachedScale;
        Bitmap b = get(c);
        int sw = c.getResources().getDisplayMetrics().widthPixels;
        return sw <= 0 ? 0.5f : (float) b.getWidth() / sw;
    }
}
