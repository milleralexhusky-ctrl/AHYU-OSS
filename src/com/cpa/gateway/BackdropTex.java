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
 * 这里按屏幕尺寸把 {@link GlassBackground} 渲染成一张 **1/2 分辨率** 的位图：
 * 肉眼几乎看不出差别，采样和显存开销直接省一半（这是液体玻璃最划算的一刀）。
 *
 * 三次迭代的踩坑记录：
 *   1. 缓存键原来只有屏幕尺寸——照片是后台解码的，首帧先到就把"兜底色斑"缓存住了，
 *      照片解码完也不再更新（表现为背景画慢半拍、或干脆看不到画）；
 *   2. 现在缓存键带上"用的是哪张照片"（builtWith），照片一到位下一帧就重画；
 *   3. 但"下一帧"仍然要让用户先看到几十毫秒的色斑。想让**第一帧**就是画，只能不等：
 *      打开应用时同步 warmSync()——先看磁盘缓存（上次启动就画好的 1/2 屏底纹），
 *      没有就当场解码照片 + 现画一次，然后把结果写回磁盘，下次冷启动就只花读盘的钱。
 */
public final class BackdropTex {

    private static Bitmap bitmap;
    private static int cachedW;
    private static int cachedH;
    /** 这张纹理是用哪张照片画的：照片后到时要能发现并重画。 */
    private static Bitmap builtWith;
    private static float cachedScale = 0.5f;
    /** true = 现在这张底纹来自 APK 里预置的那份，内容已经是画。 */
    private static boolean seeded;
    /** 底纹就绪后要刷新的那个视图（第一个挂载的页面）。 */
    private static java.lang.ref.WeakReference<View> watcher;

    private BackdropTex() {
    }

    /** 配色模式变了（深↔浅）就作废：底纹里那层蒙版不一样，必须重画。 */
    static synchronized void invalidate() {
        Bitmap old = bitmap;
        bitmap = null;
        builtWith = null;
        seeded = false;
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
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        try {
            java.io.File f = cacheFile(c);
            if (f.isFile() && f.length() > 0) {
                Bitmap disk = android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath());
                if (disk != null) {
                    bitmap = disk;
                    cachedW = dm.widthPixels;
                    cachedH = dm.heightPixels;
                    cachedScale = dm.widthPixels <= 0 ? 0.5f : (float) disk.getWidth() / dm.widthPixels;
                    builtWith = GlassBackground.photoBitmap;
                    com.cpa.gateway.core.Log.i("底纹：磁盘缓存命中，用时 "
                            + (android.os.SystemClock.uptimeMillis() - t0) + "ms");
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
        // 再退一步：APK 里预置了一份 1/2 屏底纹（构建时用同一套渲染算好的），
        // 尺寸对得上就直接用它——连"首次安装、首次打开"都不用现画那几十毫秒。
        try {
            int wantW = Math.max(1, dm.widthPixels / 2);
            int wantH = Math.max(1, dm.heightPixels / 2);
            android.graphics.BitmapFactory.Options so = new android.graphics.BitmapFactory.Options();
            // 这张图放在 drawable-nodpi 里、并且关掉密度缩放：别让系统按 dpi 把它放大 3.25 倍
            so.inScaled = false;
            Bitmap seed = Ui.LIGHT ? null : android.graphics.BitmapFactory.decodeResource(
                    c.getResources(), com.cpa.gateway.R.drawable.backdrop_seed, so);
            if (seed != null && seed.getWidth() == wantW && seed.getHeight() == wantH) {
                bitmap = seed;
                seeded = true;
                cachedW = dm.widthPixels;
                cachedH = dm.heightPixels;
                cachedScale = (float) seed.getWidth() / Math.max(1, dm.widthPixels);
                builtWith = GlassBackground.photoBitmap;
                com.cpa.gateway.core.Log.i("底纹：取自预置底纹，用时 "
                        + (android.os.SystemClock.uptimeMillis() - t0) + "ms");
                return;
            }
        } catch (Throwable ignored) {
        }
        GlassBackground.ensurePhoto(c);
        get(c);
        com.cpa.gateway.core.Log.i("底纹：现画（无预置/无缓存），用时 "
                + (android.os.SystemClock.uptimeMillis() - t0) + "ms");
        saveCacheAsync(c);
    }

    /** 把画好的底纹写回磁盘，供下次冷启动直接读。写盘放后台，别挡首帧。 */
    private static void saveCacheAsync(final Context c) {
        final Bitmap b = bitmap;
        if (b == null || b.isRecycled()) return;
        com.cpa.gateway.core.Threads.io(new Runnable() {
            @Override
            public void run() {
                try {
                    java.io.File f = cacheFile(c);
                    java.io.FileOutputStream out = new java.io.FileOutputStream(f);
                    b.compress(android.graphics.Bitmap.CompressFormat.WEBP, 92, out);
                    out.close();
                    com.cpa.gateway.core.Log.i("底纹：已写入磁盘缓存（" + f.getName() + "）");
                } catch (Throwable t) {
                    com.cpa.gateway.core.Log.w("底纹缓存写入失败：" + t);
                }
            }
        });
    }

    /** 屏幕尺寸下的 1/2 分辨率背景。 */
    static synchronized Bitmap get(Context c) {
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        int sw = dm.widthPixels;
        int sh = dm.heightPixels;
        Bitmap photo = GlassBackground.photoBitmap;
        if (bitmap != null && !bitmap.isRecycled() && cachedW == sw && cachedH == sh
                && (builtWith == photo || seeded)) {
            return bitmap;
        }
        int bw = Math.max(1, sw / 2);
        int bh = Math.max(1, sh / 2);
        Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(b);
        canvas.scale((float) bw / sw, (float) bh / sh);
        GlassBackground bg = new GlassBackground();
        bg.setBounds(0, 0, sw, sh);
        bg.draw(canvas);
        bitmap = b;
        cachedW = sw;
        cachedH = sh;
        cachedScale = (float) bw / sw;
        boolean justGotPhoto = photo != null && builtWith != photo;
        builtWith = photo;
        if (justGotPhoto) notifyReady();
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
