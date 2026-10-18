/**
 * 色版：当前配色的一张"代表图"，给导航玻璃采样用。
 *
 * <p>为什么需要它：{@link NavGlassDrawable} 用 BitmapShader 采样背后的颜色
 * 来做折射。它要的是一张**稳定**的位图，而 {@link DynamicBackdrop} 每帧都在变——
 * 每帧重建位图会把帧预算吃光。
 *
 * <p>解法：玻璃采样的目的本来就是"看起来像透过一层磨砂看见背后的色调"，
 * 色调对了就够，不需要精确到每一帧的像素。所以这里用与动态层**同一组**
 * {@link Ui} 配色算出一张静态色版，只在配色切换时重建。
 *
 * <p>结果：导航栏的玻璃观感完全保留，但它采样到的色调始终和动态层一致，
 * 因为两者读的是同一组颜色常量。
 */
package com.cpa.gateway;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;

final class Swatch {

    /** 配色版本戳：配色一变就换对象，缓存据此判废。 */
    static final Object stamp = new Object();

    private Swatch() {}

    /** 画一张整屏大小的色版：底色 + 几个柔光团，位置固定（不需要动）。 */
    static void paint(Canvas canvas, int w, int h) {
        if (w <= 0 || h <= 0) return;

        int base = Ui.LIGHT
                ? Ui.mix(0xFFF4F6EE, Ui.ACCENT_LIGHT, 0.12f)
                : Ui.mix(Ui.ACCENT_SOFT, 0xFF10140C, 0.55f);
        canvas.drawColor(base);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float minSide = Math.min(w, h);

        /* 固定的几团柔光。位置是手选的，不是随机的——
           它只是给玻璃一个"背后有色"的依据，本身不该抢戏。 */
        soft(canvas, p, w * 0.20f, h * 0.18f, minSide * 0.55f,
                Ui.mix(Ui.ACCENT, base, 0.45f));
        soft(canvas, p, w * 0.82f, h * 0.30f, minSide * 0.48f,
                Ui.mix(Ui.ACCENT_2, base, 0.55f));
        soft(canvas, p, w * 0.45f, h * 0.88f, minSide * 0.62f,
                Ui.mix(Ui.ACCENT, base, 0.62f));

        // 纵向冷暖过渡，让上下不像同一块布
        p.setShader(new LinearGradient(0, 0, 0, h,
                Ui.LIGHT ? 0x0AFFFFFF : 0x14000000,
                Ui.LIGHT ? 0x0A000000 : 0x22FFFFFF,
                Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, p);
        p.setShader(null);
    }

    private static void soft(Canvas c, Paint p, float cx, float cy, float r, int color) {
        int inner = (color & 0x00FFFFFF) | 0x66000000;
        int outer = color & 0x00FFFFFF;
        p.setShader(new RadialGradient(cx, cy, r, inner, outer, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, p);
        p.setShader(null);
    }
}
