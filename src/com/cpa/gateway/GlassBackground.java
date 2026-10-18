package com.cpa.gateway;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * Window backdrop. Primary look is the user's artwork: centre-cropped, pre-blurred and darkened so
 * translucent glass cards stay readable on top of it. If the artwork is missing it falls back to the
 * procedural colour blobs.
 */
public final class GlassBackground extends Drawable {

    private final Paint base = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint orb = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint photo = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Matrix matrix = new Matrix();

    /** Decoded once at startup. */
    public static Bitmap photoBitmap;

    /**
     * 同步确保照片已经解码——首帧要用它。
     *
     * 打开应用时 UI 线程会调一次（几十毫秒，之后一直是内存命中）；后台任务也调它，
     * 所以带锁，谁先到谁解码。
     */
    public static synchronized void ensurePhoto(android.content.Context c) {
        Bitmap cur = photoBitmap;
        if (cur != null && !cur.isRecycled()) return;
        try {
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inSampleSize = 2;
            photoBitmap = android.graphics.BitmapFactory.decodeResource(
                    c.getResources(), com.cpa.gateway.R.drawable.app_backdrop, opts);
        } catch (Throwable t) {
            com.cpa.gateway.core.Log.w("背景画解码失败：" + t);
        }
    }

    /**
     * 给整屏当底用的版本：直接铺那张 1/2 分辨率的缓存纹理。
     * 玻璃采样的也是同一张，所以"看到的"和"折射到的"永远一致；
     * 而且整屏那一次绘制从"重画照片+叠色"变成一次半分辨率位图拷贝。
     */
    public static final class Cached extends Drawable {
        private final Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final android.graphics.RectF dst = new android.graphics.RectF();

        @Override
        public void draw(Canvas canvas) {
            android.graphics.Rect b = getBounds();
            if (b.width() <= 0 || b.height() <= 0) return;
            Bitmap bmp = BackdropTex.get(ctxRef);
            if (bmp == null || bmp.isRecycled()) return;
            dst.set(b);
            canvas.drawBitmap(bmp, null, dst, p);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.OPAQUE;
        }
    }

    /** Cached 需要 Context；由 Ui 在挂载时塞进来。 */
    static android.content.Context ctxRef;

    @Override
    public void draw(Canvas canvas) {
        int w = getBounds().width();
        int h = getBounds().height();
        Bitmap bmp = photoBitmap;
        if (bmp != null && !bmp.isRecycled()) {
            float scale = Math.max((float) w / bmp.getWidth(), (float) h / bmp.getHeight());
            float dw = bmp.getWidth() * scale;
            float dh = bmp.getHeight() * scale;
            matrix.reset();
            matrix.postScale(scale, scale);
            matrix.postTranslate((w - dw) / 2f, (h - dh) / 2f);
            canvas.drawBitmap(bmp, matrix, photo);
            base.setShader(null);
            if (!Ui.LIGHT) {
                // 深色：照片压暗一点，白字才压得住
                base.setColor(Color.argb(34, 8, 12, 8));
                canvas.drawRect(0, 0, w, h, base);
                return;
            }
            // 浅色：原来盖一层 alpha=78 的纯白，等于把照片洗没了，整屏只剩白。
            // 现在改成竖向渐变 —— 顶部用主题色淡淡染一层（跟强调色同色系），底部靠近白，
            // 照片的明暗还在，卡片（半透明白）压上去才有层次。
            // 提亮整体收一档：原来底部接近 #F6F8F3，整屏最亮处落在背景上，看着晃眼
            int wash = Ui.mix(Ui.ACCENT, 0xFFFFFFFF, 0.62f);
            base.setShader(new LinearGradient(0, 0, 0, h,
                    Color.argb(176, Color.red(wash), Color.green(wash), Color.blue(wash)),
                    Color.argb(226, 232, 235, 228),
                    Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, base);
            return;
        }
        base.setShader(new LinearGradient(0, 0, 0, h,
                Color.parseColor("#10150C"), Color.parseColor("#070A05"), Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, base);
        blob(canvas, w * 0.20f, h * 0.10f, w * 0.85f, Color.parseColor("#6E7A4B"), 150);
        blob(canvas, w * 0.95f, h * 0.32f, w * 0.75f, Color.parseColor("#C9A227"), 90);
        blob(canvas, w * 0.55f, h * 0.55f, w * 1.00f, Color.parseColor("#9BAE78"), 70);
        blob(canvas, w * 0.10f, h * 0.78f, w * 0.80f, Color.parseColor("#DCE9D7"), 60);
    }

    private void blob(Canvas canvas, float cx, float cy, float radius, int color, int alpha) {
        int c = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
        orb.setShader(new RadialGradient(cx, cy, radius, c, Color.TRANSPARENT, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, radius, orb);
    }

    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(android.graphics.ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return android.graphics.PixelFormat.OPAQUE;
    }
}
