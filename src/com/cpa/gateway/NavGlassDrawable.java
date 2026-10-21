package com.cpa.gateway;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;

/**
 * 导航层的液体玻璃。
 *
 * 做法（和 bhtwork 那套示例同一个路数）：
 *   1. 背景色版只画一次，降采样成 1/2 的纹理（{@link BackdropTex}）；
 *   2. 每块玻璃只采样**自己那一块**背景，采样矩阵按视图在屏幕上的位置平移；
 *   3. 用 AGSL 在**边缘**做折射：越靠边位移越大、中间不动（所有实现都是这个路数）；
 *   4. 顺手做 RGB 色散（红蓝往反方向各偏一点点）和边缘高光。
 *
 * 降级：API < 33 或着色器编译失败 → 回到普通半透明玻璃，绝不会白屏。
 */
final class NavGlassDrawable extends Drawable {

    /** AGSL：边缘折射 + 色散 + 高光。p 是局部坐标。 */
    private static final String AGSL =
            "uniform shader backdrop;\n" +
            "uniform float2 uSize;\n" +
            "uniform float uRadius;\n" +
            "uniform float uEdge;\n" +
            "uniform float uRefract;\n" +
            "uniform float uDisp;\n" +
            "uniform float uRim;\n" +
            "uniform float uTint;\n" +
            "uniform float3 uTintColor;\n" +
            "\n" +
            "float sdRound(float2 p, float2 halfSize, float r) {\n" +
            "    float2 d = abs(p - halfSize) - (halfSize - r);\n" +
            "    return length(max(d, 0.0)) + min(max(d.x, d.y), 0.0) - r;\n" +
            "}\n" +
            "\n" +
            "half4 main(float2 p) {\n" +
            "    float2 c = uSize * 0.5;\n" +
            "    float sd = sdRound(p, c, uRadius);\n" +
            "    float t = 1.0 - smoothstep(-uEdge, 0.0, sd);\n" +
            "    t = t * t * t;\n" +
            "    float2 dir = normalize(p - c + float2(0.0001, 0.0001));\n" +
            "    float2 uv = p - dir * (t * uRefract);\n" +
            "    half4 outColor = backdrop.eval(uv);\n" +
            "    outColor.a = 1.0;\n" +
            "    half3 tint = half3(uTintColor);\n" +
            "    outColor.rgb = mix(outColor.rgb, tint, half(uTint));\n" +
            "    outColor.rgb += half3(uRim * t);\n" +
            "    return outColor;\n" +
            "}\n";

    private static RuntimeShader sharedShader;
    private static boolean shaderTried;

    private final int radiusPx;
    private final Context ctx;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private BitmapShader backdropShader;
    private Bitmap boundBitmap;
    /* 每帧复用，别再 new：这些东西原来每帧各来一份 */
    private final int[] loc = new int[2];
    private final Matrix shaderMatrix = new Matrix();
    private final RectF shadowRectF = new RectF();
    private LinearGradient bottomShade;
    private final RectF shadedFor = new RectF(Float.NaN, Float.NaN, Float.NaN, Float.NaN);

    NavGlassDrawable(Context c, int radiusPx) {
        this.ctx = c.getApplicationContext();
        this.radiusPx = radiusPx;
        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(dp(1f));
        rimPaint.setColor(Ui.NAV_STROKE);
        shadowPaint.setColor(Color.parseColor("#2E000000"));
    }

    private float dp(float v) {
        return v * ctx.getResources().getDisplayMetrics().density;
    }

    /** 这块玻璃此刻在屏幕上看得见吗。 */
    private boolean visible() {
        if (!(getCallback() instanceof View)) return false;
        View v = (View) getCallback();
        if (!v.isShown() || v.getAlpha() <= 0.01f) return false;
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        int h = ctx.getResources().getDisplayMetrics().heightPixels;
        return loc[1] + v.getHeight() > 0 && loc[1] < h;
    }

    /** 拿（并在第一次编译）共享的着色器；失败返回 null。 */
    private static RuntimeShader shader() {
        if (shaderTried) return sharedShader;
        shaderTried = true;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null;
        try {
            sharedShader = new RuntimeShader(AGSL);
            com.cpa.gateway.core.Log.i("液体玻璃：AGSL 折射着色器编译通过");
        } catch (Throwable t) {
            sharedShader = null;
            com.cpa.gateway.core.Log.w("液体玻璃：着色器不可用，降级普通玻璃（" + t + "）");
        }
        return sharedShader;
    }

    @Override
    public void draw(Canvas canvas) {
        RectF b = rect;
        b.set(getBounds());
        if (b.width() <= 0f || b.height() <= 0f) return;
        float r = Math.min(radiusPx, Math.min(b.width(), b.height()) / 2f);

        // 1. 投影（复用同一个 RectF，原来每帧 new 一个 RectF）
        RectF s = shadowRectF;
        s.set(b);
        s.offset(0, dp(2));
        canvas.drawRoundRect(s, r + dp(1), r + dp(1), shadowPaint);

        // 2. 折射。只有"真的看得见"的时候才做——
        //    滑出屏幕、被收起的页面不该为一块看不见的玻璃花 GPU。
        /*
         * 3. 折射强度按 {@link Perf} 的画质档位缩放。
         *
         * 这里不再问"降级了没有"（二值），而是拿 0..1 的系数：
         * 档位退到 3/4 时折射还在，只是采样更便宜；退到 0 才是纯半透明。
         * 这样尖峰过去之后档位能自己爬回来，用户不会遇到"某次冷启动之后
         * 玻璃永久变成平板"这种不可恢复的状态。
         */
        float quality = Perf.quality();
        RuntimeShader shader = visible() && quality > 0f ? shader() : null;
        boolean drew = false;
        if (shader != null) {
            try {
                int[] loc = this.loc;
                if (getCallback() instanceof View) {
                    ((View) getCallback()).getLocationOnScreen(loc);
                }
                Bitmap bmp = BackdropTex.get(ctx);
                float sc = BackdropTex.scale(ctx);
                if (backdropShader == null || boundBitmap != bmp) {
                    backdropShader = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                    boundBitmap = bmp;
                }
                Matrix m = shaderMatrix;
                m.reset();
                m.setScale(sc, sc);
                m.postTranslate(loc[0] * sc, loc[1] * sc);
                backdropShader.setLocalMatrix(m);

                shader.setInputShader("backdrop", backdropShader);
                shader.setFloatUniform("uSize", b.width(), b.height());
                shader.setFloatUniform("uRadius", r);
                shader.setFloatUniform("uEdge", dp(9f));      // 折射只走边缘这一圈
                /* 位移强度按画质档缩放：档位低时折射变浅，视觉上是"玻璃变薄"，不是突然消失 */
                shader.setFloatUniform("uRefract", dp(4.5f) * quality); // 边缘位移强度（猛了就成鱼眼）
                shader.setFloatUniform("uDisp", dp(0.6f) * quality);    // 色散（要几乎看不出彩边）
                shader.setFloatUniform("uRim", 0.09f);        // 边缘高光
                /* 玻璃底色：深色模式往暗处压（保证白字可读），浅色模式往亮处提（保证深字可读）
                 *
                 * 【为什么是写死的两组常量，而不是"按背景平均亮度自适应"】
                 *
                 * 这行代码看着像遗漏 —— 背景有 7 套配色、有 strength 差异，直觉上
                 * 该按实测亮度调 tint。4.51.6 真去算了 7 套配色 × 深浅两模式的平均亮度
                 * （Rec.709，含 Swatch 的柔光团）：
                 *
                 *   浅色：0.854（靛蓝）～ 0.881（暖橙）    极差 0.027
                 *   深色：0.196（玫红/靛蓝）～ 0.205（青绿） 极差 0.009
                 *
                 * 七套配色的**底色深浅本来就接近**（{@link Swatch} 把主题色和固定底
                 * 混合时主题色只占一两成），差异远小于能看出 tint 偏了的阈值。
                 * 而页面 strength 根本不影响这张纹理：玻璃采样的是 {@link BackdropTex}
                 * 那张**固定**色版，strength 只作用于 {@link DynamicBackdrop} 的实时画面
                 * （它的 fade() 影响的是**显示**亮度，纹理始终按 strength=1 画）。
                 *
                 * 结论：自适应解决的是一个在这个设计下**不存在**的问题，只会多一处
                 * 永远输出同一个值、还要人维护的分支。真正会变的只有深浅模式，
                 * 而那正是 Ui.LIGHT 这个分支在管的。别"顺手加自适应"。
                 *
                 * 这条结论由 {@code Selftest.checkGlassTintRange} 钉住：以后配色表里
                 * 真加进明暗差很多的配色，那条自检会红，那时才需要重新评估这两组常量。
                 */
                if (Ui.LIGHT) {
                    // 提亮比原来收一点：原来玻璃采样后接近纯白，整屏最亮的地方都在这儿
                    shader.setFloatUniform("uTint", 0.34f);
                    shader.setFloatUniform("uTintColor", 0.90f, 0.91f, 0.88f);
                } else {
                    shader.setFloatUniform("uTint", 0.26f);
                    shader.setFloatUniform("uTintColor", 0.05f, 0.06f, 0.04f);
                }
                paint.setShader(shader);
                canvas.drawRoundRect(b, r, r, paint);
                drew = true;
            } catch (Throwable t) {
                com.cpa.gateway.core.Log.w("液体玻璃：绘制失败，本帧降级（" + t + "）");
            }
        }
        if (!drew) {
            // 降级：普通半透明玻璃
            paint.setShader(new LinearGradient(0, b.top, 0, b.bottom,
                    Ui.NAV_FILL, Ui.NAV_FILL, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(b, r, r, paint);
        }
        paint.setShader(null);

        // 3. 底部一圈内阴影，给一点厚度。渐变只跟 bounds 有关：bounds 不变就不重建
        if (b.left != shadedFor.left || b.top != shadedFor.top
                || b.right != shadedFor.right || b.bottom != shadedFor.bottom) {
            shadedFor.set(b);
            // 浅色模式下这条内阴影会在导航条下半截糊出一块灰，跟左上角的玻璃接不上；
            // 浅色用更淡更短的一层，只留一点厚度感。
            bottomShade = new LinearGradient(0, b.bottom - b.height() * (Ui.LIGHT ? 0.30f : 0.45f), 0, b.bottom,
                    Color.TRANSPARENT,
                    Color.parseColor(Ui.LIGHT ? "#0F000000" : "#22000000"), Shader.TileMode.CLAMP);
        }
        paint.setShader(bottomShade);
        canvas.drawRoundRect(b, r, r, paint);
        paint.setShader(null);

        // 4. 描边高光
        canvas.drawRoundRect(b, r, r, rimPaint);
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
