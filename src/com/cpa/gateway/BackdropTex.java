package com.cpa.gateway;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.util.DisplayMetrics;
import android.view.View;

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
    /** 底纹就绪后要刷新的那个视图（第一个挂载的页面）。 */
    private static java.lang.ref.WeakReference<View> watcher;

    private BackdropTex() {
    }

    /** 配色模式变了（深↔浅）就作废：底纹里那层蒙版不一样，必须重画。 */
    static synchronized void invalidate() {
        Bitmap old = bitmap;
        bitmap = null;
        builtWith = null;
        cachedW = 0;
        cachedH = 0;
        if (old != null && !old.isRecycled()) {
            try {
                old.recycle();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 第一个挂载的页面：底纹从"兜底色斑"换成"真照片"时，请它重绘一次。 */
    static void watch(View v) {
        watcher = new java.lang.ref.WeakReference<>(v);
    }

    /** 照片解码完就调：在后台把整屏底纹先画好。 */
    public static void prewarm(Context c) {
        try {
            get(c);
        } catch (Throwable ignored) {
        }
    }

    private static boolean warmStarted;

    /**
     * 进程一起来就把底纹的活儿开出去（CpaApp 里调），和 Activity 启动并行。
     *
     * 这里不能等：Application 的回调不能被拖住。活儿交给后台线程，MainActivity 真正要画
     * 第一帧时的 warmSync 会去抢同一把锁——抢到了说明活儿已经干完（0ms），抢不到就是在等
     * "剩余时间"，而不是重新做一遍。
     */
    public static synchronized void startWarm(final Context c) {
        if (warmStarted) return;
        warmStarted = true;
        com.cpa.gateway.core.Threads.io(new Runnable() {
            @Override
            public void run() {
                try {
                    warmSync(c);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /** 磁盘缓存文件名带上屏幕尺寸：换了屏幕/分辨率就自然失效，不需要清理逻辑。 */
    private static java.io.File cacheFile(Context c) {
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        return new java.io.File(c.getFilesDir(),
                "backdrop-" + dm.widthPixels + "x" + dm.heightPixels + (Ui.LIGHT ? "-light" : "") + ".webp");
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

    /** 照片迟到了：底纹已经重画，请第一屏再画一帧，不然静止的界面要等下次刷新才换画。 */
    private static void notifyReady() {
        final java.lang.ref.WeakReference<View> w = watcher;
        if (w == null) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                View v = w.get();
                if (v != null) v.invalidate();
            }
        });
    }

    /** 纹理相对屏幕的缩放比（位图像素 / 屏幕像素）。 */
    static synchronized float scale(Context c) {
        if (bitmap != null && !bitmap.isRecycled() && cachedScale > 0f) return cachedScale;
        Bitmap b = get(c);
        int sw = c.getResources().getDisplayMetrics().widthPixels;
        return sw <= 0 ? 0.5f : (float) b.getWidth() / sw;
    }
}
