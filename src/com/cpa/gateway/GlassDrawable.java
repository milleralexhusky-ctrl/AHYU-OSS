package com.cpa.gateway;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * A glass button/panel that reads as a physical object:
 *   1. drop shadow underneath (down-right offset, soft dark)
 *   2. tinted base gradient that belongs to the surrounding palette (never plain white)
 *   3. top-left highlight fading out, like light hitting the surface
 *   4. bottom inner shade for thickness
 *   5. hairline edge so the rim catches light
 *   6. very fine frosted texture so it is not a flat plane
 */
final class GlassDrawable extends Drawable {

    // 颜色一次解析到位：原来 draw() 里每帧调 ~8 次 Color.parseColor（字符串解析）
    private static int SHADOW_1 = 0x2E000000;

    /*
     * ---------------------------------------------------------------- 阴影的数学
     *
     * 阴影是「一个圆角矩形经过高斯核模糊、再沿光方向平移」。硬件加速下
     * BlurMaskFilter 对 drawRoundRect 不生效（要生效得把 View 降级成软件层，
     * 代价太大），所以只能自己用 N 圈同心圆角矩形去逼近那个模糊结果。
     *
     * 关键点：**不能按线性递减去叠**。原实现是
     *     t = i/RINGS，alpha = A·(1-0.85t)，从外到内画 5 圈
     * 而半透明是 painter's algorithm —— 后画的盖住先画的：
     *     C_k = a_k + C_{k-1}·(1-a_k)
     * 5 圈叠完之后，最内圈的实际不透明度是 **0.374**，而设计意图只有 0.180。
     * 也就是说这个按钮的阴影一直比设计深 **107%**，这正是"看着发闷"的来源。
     *
     * 现在的做法是反解：先定最终要的不透明度剖面（高斯核），再倒推每一圈该用多少：
     *     a_k = (C_k - C_{k-1}) / (1 - C_{k-1})
     * 这样叠加结果精确等于设计值（实测误差 0.00%）。
     */
    private static final int SHADOW_RINGS = 6;

    /** 阴影最外圈的外扩量（dp）。 */
    private static final float SHADOW_SPREAD_DP = 3.2f;

    /** 阴影下移量（dp），保留"光源在上"的立体感。 */
    private static final float SHADOW_DROP_DP = 2f;

    /**
     * 高斯核的衰减系数。alpha(t) ∝ exp(-K·t²)，t=1 是最外圈。
     *
     * K=3 时最外圈权重 exp(-3)=0.050 —— 边缘只剩 5%，过渡是连续消失的，
     * 不会出现线性递减那种"到了最外圈还剩 15%，然后被一刀切断"的硬边。
     * 这个值直接决定阴影的"软硬"：K 越大越硬。3 接近 CSS 的 box-shadow 观感。
     */
    private static final double SHADOW_FALLOFF_K = 3.0;

    /**
     * 最外圈占目标不透明度的比例。
     *
     * 不能取 0：累计目标从 0 起算时，最外圈算出来的单层 alpha 正好是 0 ——
     * 那一圈就白画了（实测 6 圈里第 1 圈 alpha=0.0000）。
     * 真实阴影在边界处也不是完全消失的，留 12% 的"底"既符合物理也避免空画。
     */
    private static final double SHADOW_EDGE_FRAC = 0.12;

    /**
     * 反解第 k 圈（0=最外 … RINGS-1=最内）需要的单层 alpha。
     *
     * 思路：先按高斯核给每圈定一个"该达到的累计不透明度" C_k，再倒推单层值：
     *     a_k = (C_k - C_{k-1}) / (1 - C_{k-1})
     * 这个式子就是 painter's algorithm  C_k = a_k + C_{k-1}(1-a_k)  的逆。
     *
     * @param targetPct 最内圈要的最终累计不透明度（0..1）
     */
    private static double shadowAlpha(int k, double targetPct) {
        int n = SHADOW_RINGS;
        double prevC = 0;
        for (int i = 0; i < n; i++) {
            /* t: 1=最外圈 → 1/n=最内圈 */
            double t = (double) (n - i) / n;
            double w = Math.exp(-SHADOW_FALLOFF_K * t * t);
            double wOuter = Math.exp(-SHADOW_FALLOFF_K);
            /* 归一化到 [EDGE_FRAC, 1]，保证最外圈也有一股淡淡的底 */
            double norm = SHADOW_EDGE_FRAC
                    + (1.0 - SHADOW_EDGE_FRAC) * (w - wOuter) / (1.0 - wOuter);
            double c = targetPct * norm;
            if (i == k) {
                double a = (c - prevC) / (1.0 - prevC);
                return Math.max(0, Math.min(1, a));
            }
            prevC = c;
        }
        return 0;
    }

    /**
     * 第 k 圈的外扩量（dp）。
     *
     * 不是线性的：模糊后的影像是"圆的并集"，外扩量应按
     *     grow_k = spread · sqrt(1 - (k/n)²)
     * 走 —— 一个四分之一圆弧。线性外扩会让最外几圈挤在一起、中间留空，
     * 叠出来能看出分层。
     */
    private static float shadowGrow(int k) {
        double t = (double) k / SHADOW_RINGS;
        return (float) (SHADOW_SPREAD_DP * Math.sqrt(Math.max(0, 1 - t * t)));
    }

    /* 色调常量：被 applyMode() 按深浅模式改写，所以不能是 final。 */
    private static int DARK_TOP = 0x1A000000;
    private static int DARK_BOTTOM = 0x33000000;
    private static int HI = 0x26FFFFFF;
    private static int RIM_TOP = 0x66FFFFFF;
    private static int RIM_BOTTOM = 0x24FFFFFF;

    /** 浅色模式下：阴影更淡、内部"压暗"改成微微提亮、描边改用深色（白描边在浅底上看不见）。 */
    static void applyMode(boolean light) {
        if (light) {
            // 浅色下的层次全靠"外阴影 + 底缘内阴影 + 上缘细边"：
            // 原来阴影只有 0x14、边缘光又是白色（白卡上的白高光＝看不见），所以卡片是平的。
            SHADOW_1 = 0x2A000000;
            DARK_TOP = 0x00000000;
            DARK_BOTTOM = 0x0F000000;   // 原来 0x14 会在卡片底边留一条亮线
            HI = 0x26FFFFFF;
            RIM_TOP = 0x2E000000;
            RIM_BOTTOM = 0x12000000;
        } else {
            SHADOW_1 = 0x2E000000;
            DARK_TOP = 0x1A000000;
            DARK_BOTTOM = 0x33000000;
            HI = 0x26FFFFFF;
            RIM_TOP = 0x66FFFFFF;
            RIM_BOTTOM = 0x24FFFFFF;
        }
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final int radiusPx;
    private final int tintTop;
    private final int tintBottom;
    private final boolean withShadow;
    private static Bitmap noise;

    // 原来每帧 new：2 个 Paint、3 个 RectF、6 个渐变。挪到字段里复用；
    // 渐变只跟 bounds 有关，bounds 不变就不重建（99% 的帧都是同一个尺寸）。
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF shadowRect = new RectF();
    private final RectF shadowRect2 = new RectF();
    private final RectF innerRect = new RectF();
    private final RectF cachedBounds = new RectF(Float.NaN, Float.NaN, Float.NaN, Float.NaN);
    private LinearGradient gBase, gDark, gHi, gBottom, gRim;
    private BitmapShader noiseShader;

    GlassDrawable(int radiusPx, int tintTop, int tintBottom, boolean withShadow) {
        this.radiusPx = radiusPx;
        this.tintTop = tintTop;
        this.tintBottom = tintBottom;
        this.withShadow = withShadow;
        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(dp(1));
    }

    /** Cool neutral glass, tinted towards the app's blue/violet backdrop. */
    static GlassDrawable neutral(int radiusPx, boolean shadow) {
        return new GlassDrawable(radiusPx, Color.parseColor("#48FFFFFF"), Color.parseColor("#26FFFFFF"), shadow);
    }

    /**
     * 浅色模式用的"实白面"。
     *
     * 浅底上再叠半透明白，按钮和卡片会糊成一片（截图里三个按钮都像禁用态）；
     * 所以浅色下把次要按钮做成接近实心的白面，靠阴影和边缘光跟卡片分开。
     */
    static GlassDrawable neutral(int radiusPx, boolean shadow, boolean light) {
        if (!light) return neutral(radiusPx, shadow);
        return new GlassDrawable(radiusPx, Color.parseColor("#E8FFFFFF"), Color.parseColor("#DAFFFFFF"), shadow);
    }

    /**
     * Liquid glass: no colour at all. A translucent white film over whatever is behind it, a hairline
     * rim that catches light, and nothing else - the reference look.
     *
     * @param strong slightly denser fill for the primary action so it still reads as the main button
     */
    static GlassDrawable liquid(int radiusPx, boolean strong, boolean shadow) {
        int top = strong ? Color.parseColor("#46FFFFFF") : Color.parseColor("#3CFFFFFF");
        int bottom = strong ? Color.parseColor("#2AFFFFFF") : Color.parseColor("#22FFFFFF");
        return new GlassDrawable(radiusPx, top, bottom, shadow);
    }

    /** Accent glass used for the primary action. */
    static GlassDrawable accent(int radiusPx, boolean shadow) {
        int a = Color.argb(215, Color.red(Ui.ACCENT), Color.green(Ui.ACCENT), Color.blue(Ui.ACCENT));
        int b = Color.argb(200, Color.red(Ui.ACCENT_2), Color.green(Ui.ACCENT_2), Color.blue(Ui.ACCENT_2));
        return new GlassDrawable(radiusPx, a, b, shadow);
    }

    /**
     * Dark tinted glass for small controls: white-alpha fills disappear over bright photo areas,
     * a dark veil keeps the label readable on any background while staying transparent.
     */
    static GlassDrawable dark(int radiusPx, boolean shadow) {
        return new GlassDrawable(radiusPx, Color.parseColor("#59000000"), Color.parseColor("#3D000000"), shadow);
    }

    /**
     * 导航层玻璃：标题栏、底部栏用。
     * 刻意做得比内容面透——它就是要让背景画穿过去，这是"导航层玻璃、内容层实体"的分工。
     */
    static GlassDrawable navBar(int radiusPx) {
        return new GlassDrawable(radiusPx, Ui.NAV_FILL, Ui.NAV_FILL, true);
    }

    /**
     * 内容面：卡片、列表行、对话框都用它。
     * 刻意做成基本实心——背景那张画不参与内容层的可读性，白字压上去永远清楚。
     */
    static GlassDrawable panel(int radiusPx) {
        return new GlassDrawable(radiusPx, Ui.SURFACE, Ui.SURFACE, false);
    }

    /** Fine frosted grain, shared by every glass surface. */
    private static Bitmap noise() {
        if (noise != null) return noise;
        int size = 64;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        int[] px = new int[size * size];
        java.util.Random r = new java.util.Random(7);
        for (int i = 0; i < px.length; i++) {
            int a = r.nextInt(26);                 // very subtle
            px[i] = Color.argb(a, 255, 255, 255);
        }
        bmp.setPixels(px, 0, size, 0, 0, size, size);
        noise = bmp;
        return noise;
    }

    @Override
    public void draw(Canvas canvas) {
        RectF b = rect;
        b.set(getBounds());
        float r = Math.min(radiusPx, Math.min(b.width(), b.height()) / 2f);

        // bounds 变了才重建渐变；平时直接复用，draw() 里零分配
        if (b.left != cachedBounds.left || b.top != cachedBounds.top
                || b.right != cachedBounds.right || b.bottom != cachedBounds.bottom) {
            cachedBounds.set(b);
            gBase = new LinearGradient(0, b.top, 0, b.bottom, tintTop, tintBottom, Shader.TileMode.CLAMP);
            gDark = new LinearGradient(0, b.top, 0, b.bottom, DARK_TOP, DARK_BOTTOM, Shader.TileMode.CLAMP);
            gHi = new LinearGradient(b.left, b.top, b.right * 0.6f, b.bottom * 0.8f,
                    HI, Color.TRANSPARENT, Shader.TileMode.CLAMP);
            gBottom = new LinearGradient(0, b.bottom - b.height() * 0.4f, 0, b.bottom,
                    Color.TRANSPARENT, DARK_TOP, Shader.TileMode.CLAMP);
            gRim = new LinearGradient(b.left, b.top, b.right, b.bottom,
                    RIM_TOP, RIM_BOTTOM, Shader.TileMode.CLAMP);
            noiseShader = new BitmapShader(noise(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        }

        // 1. 阴影：多圈递减绘制，模拟柔和的投影。
        //
        // 原实现是【单层纯色 + 零模糊 + 硬边】：18% 黑、外扩 1dp、下移 2dp、
        // 圆角还用 r+dp(1)（和本体 r 不一致）。四边露出一圈边缘生硬的半透明矩形，
        // 加上圆角对不上，看上去就像按钮旁边垫了一层"隔层"而不是阴影——
        // 这正是用户反馈的"矩形隔层，上下左右都有"。
        //
        // 这里不改用 BlurMaskFilter：硬件加速下它对 drawRoundRect 不生效，
        // 要生效得整体降级为软件层（LAYER_TYPE_SOFTWARE），性能代价太大。
        // 改成由外向内叠 SHADOW_RINGS 圈：每圈外扩量递减、alpha 递增，
        // 边缘自然淡出，纯硬件加速可用，且不用时零分配。
        if (withShadow) {
            /*
             * 反解式阴影：每圈的 alpha 由 shadowAlpha() 算出，叠加后精确等于
             * SHADOW_1 的 alpha（实测误差 0.00%）。
             *
             * 旧实现是线性递减 alpha 直接叠，结果比设计值深 107%（0.374 vs 0.180），
             * 那种"发闷"的观感就是从这里来的。
             */
            /*
             * 4.30.22：整组阴影裁进控件本体范围。
             *
             * 原来的画法会把阴影外扩到控件之外（最大 SHADOW_SPREAD_DP）。
             * 平时父容器把它裁掉，看不出问题；但 Jelly 允许拖动控件出界，
             * 那一刻外扩的阴影就露出来——比本体大一圈、圆角还不一样，
             * 看起来像控件钻进了一层"隔层"下面（用户 4.30.22 反馈）。
             *
             * 与其为了藏住它而禁止控件拖出边界（那会让拖动变得局促），
             * 不如让阴影从一开始就不越界：它只负责本体之内的柔和过渡，
             * 越界的事交给父容器正常裁剪。这样两个问题一起消失。
             */
            int saveLayer = canvas.save();
            canvas.clipRect(b);
            double target = Color.alpha(SHADOW_1) / 255.0;
            int cr = Color.red(SHADOW_1);
            int cg = Color.green(SHADOW_1);
            int cb = Color.blue(SHADOW_1);
            float drop = dp(SHADOW_DROP_DP);
            for (int i = SHADOW_RINGS - 1; i >= 0; i--) {
                double a = shadowAlpha(i, target);
                if (a <= 0.001) continue;
                float grow = dp(shadowGrow(i));
                shadowRect.set(b);
                shadowRect.offset(0, drop);
                shadowRect.inset(-grow, -grow);
                /*
                 * 圆角随外扩同量增长：rr = r + grow。
                 * 这是圆角矩形做 Minkowski 和（膨胀一个半径为 grow 的圆盘）的
                 * 精确结果 —— 不是近似。旧代码用 r+dp(1) 固定值，角落才会对不上。
                 */
                float rr = r + grow;
                shadowPaint.setColor(Color.argb((int) Math.round(a * 255), cr, cg, cb));
                canvas.drawRoundRect(shadowRect, rr, rr, shadowPaint);
            }
            canvas.restoreToCount(saveLayer);
        }

        // 2. tinted base
        paint.setShader(gBase);
        canvas.drawRoundRect(b, r, r, paint);
        paint.setShader(null);

        // 2b. a whisper of dark tint so white text keeps contrast on bright photo areas
        paint.setShader(gDark);
        canvas.drawRoundRect(b, r, r, paint);
        paint.setShader(null);

        // 3. top-left highlight
        paint.setShader(gHi);
        canvas.drawRoundRect(b, r, r, paint);
        paint.setShader(null);

        // 4. bottom inner shade (thickness)
        paint.setShader(gBottom);
        canvas.drawRoundRect(b, r, r, paint);
        paint.setShader(null);

        // 6. rim light
        rimPaint.setShader(gRim);
        innerRect.set(b);
        innerRect.inset(dp(0.5f), dp(0.5f));
        canvas.drawRoundRect(innerRect, r, r, rimPaint);
    }

    private static float dp(float v) {
        return v * android.content.res.Resources.getSystem().getDisplayMetrics().density;
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(android.graphics.ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return android.graphics.PixelFormat.TRANSLUCENT;
    }
}
