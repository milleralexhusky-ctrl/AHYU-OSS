/**
 * 程序化魔法阵（Procedural Magic Circle）：由网关运行状态驱动的极坐标群作用图形。
 *
 * <p><b>它是什么。</b>不是一个"形状"，而是一个群作用的结果：
 *
 * <pre>
 *   M(t) = ∪ R(ω_i·t + φ_i) · L_i
 *   L_i  = ∪_{k=0}^{n_i-1} R(2πk/n_i) · U_i
 * </pre>
 *
 * 每层有一个基础单元 U_i，被旋转矩阵复制 n_i 份，形成一圈离散的、可数的结构；
 * 整层再以自己的角速度和初相旋转。这是"有限轨道艺术"，
 * 不是随机噪声——随机只用于参数扰动，对称群负责魔法阵感。
 *
 * <p><b>为什么必须是单元复制。</b>曾经试过"整条闭合曲线加正弦调制"，
 * 结果出来像流动的薄纱：连续、糊、数不出结构。魔法阵的观感来自
 * **离散的 n 重对称**——你能一眼数出这一圈有 12 道刻痕。
 * 所以每个单元必须是真的独立笔画，不是一条曲线的采样点。
 *
 * <p><b>不重复策略。</b>
 * 层内对称用**整数** n_i（保证对称数得出来），
 * 层间角速度比取**无理数**（φ : √2 : √3，任意两层的比值都无公周期），
 * 层间初相用**黄金角步进** φ_i = i·π(3-√5)（任意两层的相位差都不重复）。
 * 三者分工明确：整数管对称，无理比管长期不回位。
 * 之前把两者混在一起调，结果对称被搅碎成纹理，这是走过的弯路。
 *
 * <p><b>为什么不攒垃圾。</b>没有记忆：不记事件、不存历史、不做随机游走。
 * 此刻的样子只由此刻的状态（并发/密度/耗时）和启动时刻决定，
 * 所以内存恒定，也不需要"回放"。
 *
 * <p><b>为什么不拖慢滑动。</b>骨架只在**屏幕尺寸变化**时重建一次；
 * 所有旋转、缩放、位移都是 canvas 矩阵——GPU 的免费操作。
 * Path 重建是 CPU 的昂贵操作，每帧重建就等于每帧重算几万个点，那是掉帧的根源。
 */
package com.cpa.gateway;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

import com.cpa.gateway.core.Activity;

final class DynamicBackdrop extends View {

    /** 重绘间隔（毫秒）。8 帧/秒：慢的东西不需要 60 帧。 */
    private static final long FRAME_MS = 125L;

    /** 掉帧时退到的间隔。宁可背景慢一点，也不能和列表滑动抢帧。 */
    private static final long FRAME_SLOW_MS = 200L;

    /** 黄金角。任意两层用它做初相，相位差永不成整数比。 */
    private static final double GOLDEN_ANGLE = Math.PI * (3 - Math.sqrt(5));

    /*
     * 层角速度的无理比基数。
     * 取 φ、√2、√3 三个互不通约的数轮换，任意两层的 ω 比值都不会是有理数，
     * 所以整个阵**永不回到曾经的状态**——这是长期不重复的直接来源。
     */
    private static final double[] OMEGA_BASE = {
            (1 + Math.sqrt(5)) / 2,   // φ
            Math.sqrt(2),
            Math.sqrt(3)
    };

    /**
     * 基础角速度。
     *
     * 这是"感觉很快"和"演变很慢"能同时成立的关键：
     * 几何（M）十几秒才动一格，但**旋转每帧都在走**。
     * 眼睛捕捉到的是每帧都有细微变化，主观上就是"一直在动、很跟手"；
     * 而形状本身慢到几乎察觉不到在变，不抢注意力。
     */
    /*
     * 角速度按"转一圈要多久"反算，不凭感觉给数。
     *
     * 公式：ω = 2π / 一圈毫秒数。层 i 的实际角速度还要乘 OMEGA_BASE[i]，
     * 所以基准取 2π/(一圈ms) / φ，让最快那层正好是目标速度。
     *
     * 这里定成 TEN_MINUTES：转一整圈要十分钟。
     * 换算下来约 0.6 度/秒——盯着看几乎静止，
     * 但过一分钟回来看，能觉出它挪了位置。
     *
     * 之前给 0.00006 时其实是 5.6 度/秒，一点都不慢，
     * 难怪反复觉得快。凭"数值看着小"调参是错的，必须按圈速算。
     */
    private static final long TURN_MS = 10L * 60 * 1000;
    private static final double OMEGA_SCALE =
            (2 * Math.PI / TURN_MS) / ((1 + Math.sqrt(5)) / 2);

    /** 最多几层。层数由状态决定，1..MAX_LAYERS 平滑生长。 */
    private static final int MAX_LAYERS = 8;

    /** 每层最多几重对称。实际 n_i 会按层不同，这里是上限。 */
    private static final int MAX_SEGMENTS = 96;

    /** 模乘阵的取点数上限。N 越大多边形越圆，但弦数也越多。 */
    private static final int DRIFT_N = 240;

    /**
     * 最短弦的阈值（弦长平方）。
     *
     * 单位圆里最长弦是直径，平方为 4。这里取 0.06，约等于长度 0.245，
     * 也就是只保留长度超过圆周 1/25 的弦。太短的弦对包络没贡献，
     * 只是把画面填成灰——砍掉之后省三成绘制量，形态不变。
     */
    private static final float MIN_CHORD2 = 0.06f;

    /**
     * 黄金角散点的个数。
     *
     * 这些点按 r ∝ √n、θ ∝ n·137.5077° 排布，会长出双向交织的
     * 斐波那契螺旋——也就是向日葵花盘的排布。
     *
     * 它和弦网是两种完全不同的数学来源，叠加起来才有"多维度"的感觉：
     *   弦网：中心密、边缘疏
     *   散点：中心疏、边缘密
     * 两者疏密互补，画面上任何区域都有东西，不会一块空一块挤。
     *
     * 240 个在 738px 宽的屏幕上，最密处间距约 45px：
     * 看得清每颗点，又足以认出螺旋。
     */
    private static final int SUNFLOWER_N = 240;

    /**
     * 每颗星点的直径（dp）。
     *
     * 物理尺寸恒定，不随屏幕分辨率变化。
     * 0.5dp 在 480dpi 的屏上约 2.8 物理像素，光晕再摊开一点。
     *
     * 这里连着错过两次：1.6dp 算出来是 9 像素，乘光晕后近 20 像素，
     * 整屏像落了灰；按单位圆比例定（0.0032）则在高分辨率屏上到 40 像素。
     * 定尺寸只能按 dp，而且要按"屏幕上几颗像素"反推，不能凭感觉给数。
     */
    private static final float DOT_DP = 0.5f;


    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();

    /** 骨架：每层一条 Path（该层所有 orbit 已焊在里面）。尺寸变化时重建。 */
    private Path[] layerPath;

    /** 黄金角散点的 Path：所有点焊在一条 Path 里，一次 drawPath 画完。 */
    private Path dotsPath;
    private int builtW;
    private int builtH;
    /** 上次建骨架时用的 M（避免每帧重建）。 */
    private int builtM = -1;

    /**
     * 当前形态参数 M（模乘阵的乘数），浮点连续值。
     *
     * 它是**连续**的：负载改变它的目标，它慢慢追过去，
     * 所以形态是长出来的，不是跳过去的。
     * 真正建图时取整数部分，跨过一个整数才重建一次几何。
     */
    private double complex = 3.0;

    private final long t0 = SystemClock.elapsedRealtime();

    /** 这一页该有多强的存在感（0..1）。 */
    private float strength = 1f;
    private float shown = 0f;

    /** 平滑后的运行状态。只保留当前值，不保留历史。 */
    private double sLoad, sThroughput, sLatency;

    private long lastFrameAt;
    private int lateStreak;
    private boolean running;

    DynamicBackdrop(Context c) {
        super(c);
        setWillNotDraw(false);
        setLayerType(LAYER_TYPE_HARDWARE, null);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeCap(Paint.Cap.ROUND);
        /*
         * 极细线关抗锯齿。线宽到 1 像素附近时，抗锯齿把它摊成
         * "1 像素实心 + 两侧半透明灰边"，视觉宽度变 2 像素且发灰发脏。
         * 关掉之后锐利地占满那 1 像素，才是真的"细"。
         */
        linePaint.setAntiAlias(false);

        /*
         * 加法混合：魔法阵"发光"的一半来源在这里，不在粗线上。
         *
         * 普通 alpha 叠加是"压"——越叠越平，密集处反而发灰。
         * 加法是"点上去会变亮"：线条交叉处自然积累成亮核，
         * 星芒的尖角、层与层的重叠处自己就发出光来，
         * 不需要额外画一圈很粗的透明线去假装辉光（那只会变厚）。
         */
        linePaint.setBlendMode(android.graphics.BlendMode.PLUS);
        glowPaint.setBlendMode(android.graphics.BlendMode.PLUS);
    }

    /** 施法进度 0..1：线条从外环向内环逐步描出。 */
    private float appear = 0f;

    void strength(float s) {
        this.strength = Math.max(0f, Math.min(1f, s));
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        start();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE) start(); else stop();
    }

    private void start() {
        if (running) return;
        running = true;
        lastFrameAt = SystemClock.elapsedRealtime();
        postDelayed(tick, FRAME_MS);
    }

    private void stop() {
        running = false;
        removeCallbacks(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            long now = SystemClock.elapsedRealtime();
            long gap = now - lastFrameAt;
            lastFrameAt = now;

            /*
             * 帧预算：上一帧实际间隔远超预期，说明主线程在忙（多半是列表滑动）。
             * 连续两次就主动放慢，把帧让给别人——背景慢下来没人看得见，
             * 滑动掉帧一眼就看得出来。
             */
            if (gap > FRAME_MS * 2) lateStreak++;
            else if (lateStreak > 0) lateStreak--;
            long next = lateStreak >= 2 ? FRAME_SLOW_MS : FRAME_MS;

            // 性能吃紧时停在当前帧：阵静止时仍是完整图案，只是不转
            if (!Perf.degraded()) invalidate();
            postDelayed(this, next);
        }
    };

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        if (shown != strength) {
            float d = strength - shown;
            shown += Math.abs(d) < 0.01f ? d : d * 0.18f;
        }

        int base = Ui.LIGHT
                ? Ui.mix(0xFFF7F8F2, Ui.ACCENT_LIGHT, 0.10f)
                : Ui.mix(0xFF0A0D07, Ui.ACCENT_SOFT, 0.32f);
        canvas.drawColor(fade(base, 1f));

        if (shown <= 0.01f) return;

        Activity.Snapshot a = Activity.snapshot();
        sLoad += (a.load - sLoad) * 0.10;
        sThroughput += (a.throughput - sThroughput) * 0.06;
        sLatency += (a.latency - sLatency) * 0.08;

        ensureSkeleton(w, h);
        if (layerPath == null) return;

        /*
         * ===== 层数由状态决定（连续，不跳变）=====
         *
         * 并发占大头——"同时有多少事在办"是最直接的复杂信号；
         * 密度次之；耗时最少（慢不等于复杂，慢只是慢）。
         */
        double busy = sLoad * 0.50 + sThroughput * 0.38 + sLatency * 0.12;
        busy = Math.min(1.0, Math.max(0.0, busy));

        /*
         * 层数**固定三层**，不再随负载增减。
         *
         * 之前层数跟着负载走（1..8 层），空闲时只有一层。
         * 但单一层就是一条完整的闭合轮廓——看起来就是"一个形状"，
         * 而且那个形状恰好左右对称。
         *
         * 三层常驻才有三条轮廓互相切割，谁也数不出完整边界。
         * 负载不再改层数，改为改每层的 M（曲线族的形态）。
         */
        final int full = 3;
        final float frac = 0f;

        /*
         * ===== 形态演变：极慢漂移，不是换图 =====
         *
         * M 决定模乘阵的形态（M 越大越缠绕）。它不能跳——
         * 跳一次就是"换了一个图形"，那是改变不是演变。
         *
         * 所以走一条极慢的连续曲线：M 大约 DRIFT_MS 才跨过 1 个整数刻度。
         * 每次 M 加 1，弦网只偏折一点点，肉眼看不出"换了一张图"。
         * 负载只调整这条曲线的**目标值**，实际位置慢慢追过去，
         * 所以突发流量不会让形态突然崩开，而是缓缓化开。
         */
        double targetM = 3.0 + (DRIFT_N - 4) * busy;   // 负载决定目标形态
        complex += (targetM - complex) * 0.02;         // 缓慢追赶

        /* M 是整数，但用"离散步进"重建：只有跨过 1 才需要重建一次几何。 */
        int wantM = (int) Math.floor(complex);
        if (layerPath == null || wantM != builtM) {
            builtM = wantM;
            layerPath = null;
        }

        /*
         * 尺寸：直径约屏幕短边的 2.5 倍，阵心推到屏幕外上方。
         *
         * 为什么推到屏幕外：看完整张阵，一眼就知道它是什么，看完就没了。
         * 只看到一块边角时，屏幕里永远是长弧和斜弦的局部，
         * 你永远在猜屏幕外那段接向哪里——这才耐看。
         */
        /*
         * 半径必须大于**屏幕对角线的一半**，否则阵的边界会出现在屏幕里。
         *
         * 看到边界就有"错面感"——像一张纸盖在另一张纸上，没盖齐。
         * 1280×2772 的屏，对角线一半约 1520；这里取短边 ×2.2 ≈ 2816，
         * 边界远在视野之外，屏幕里永远只是阵的内部。
         */
        float diag = (float) Math.sqrt((double) w * w + (double) h * h);
        float spread = (float) (0.96 + 0.10 * busy);

        /*
         * 半径取对角线的一半再多一点（1.15 倍）：
         *   - 小于对角线一半，边角会露白，出现"错面感"
         *   - 大太多，屏幕里只剩阵极靠中心的一小片，
         *     弦在那个尺度上近似平行，就变成了方格纸
         * 1.15 是"边界在屏幕外、但看到的仍是有弯曲的中段"的那个值。
         */
        /*
         * 1.6 倍对角线半长：屏幕四角最远处刚好落在 1.00R 上，
         * 也就是弦的分布范围边缘，画面满而不空；
         * 再大就往外，那里已经没线了。
         */
        float R = diag * 0.5f * 1.60f * spread;

        /*
         * 阵心：横向保持居中，纵向推到屏幕上方外侧约 1/4 屏高。
         * 这样屏幕里看到的是阵的下半部，弧线由左上斜穿到右下，
         * 全貌永远在视野之外。
         */
        /*
         * 阵心：屏幕横向居中、纵向靠上三分之一。
         *
         * 之前推到屏幕外上方 0.26h，结果屏幕里只剩阵的最内圈——
         * 弦在那个尺度上几乎是平行直线，整体看着像方格纸，
         * 包络曲率跑到了视野之外。拉回屏内之后，
         * 屏幕能看到阵的中段，弯曲和收拢都还在画面里。
         */
        /*
         * 阵心推到屏幕上方两倍多屏高的位置。
         *
         * 为什么必须这么远：模乘阵在**原点附近必然左右对称**，
         * 那是几何本性，跟 M、层数、速度都无关。
         * 之前阵心只推到屏幕外 0.12h，屏幕中心距阵心才 0.19R，
         * 几乎正对着原点——画出来就是左右两瓣，怎么调都是一团"屁股"。
         *
         * 推到 1.20h 之后，屏幕中心距阵心 1.89R，四角 1.16R～2.71R，
         * 屏幕看到的是阵的外围环带：两道大弧之间的区域，
         * 没有任何以原点为中心的对称结构。
         */
        /*
         * 阵心在屏幕上方 0.35 屏高处——这是"避开对称"和"看得见东西"的折中。
         *
         * 推 1.20h 时屏幕落到阵的外面去了：模乘阵的弦都集中在原点附近，
         * 半径超过某个范围就没有线，整片全空。
         * 而完全居中（0h）时，屏幕正对着原点，必然出现左右两瓣。
         *
         * 0.35h 配合 1.6 倍半径：屏幕中心距阵心 0.40R、四角 0.31R..1.00R，
         * 既全屏有弦，又不在圆心正上方。
         */
        float originY = -h * 0.35f;

        long el = SystemClock.elapsedRealtime() - t0;

        /* 施法：约 2.2 秒内从外环画到内环，之后保持完成态。
           这是"出现动画"，不是循环——画完就一直是完整的阵。 */
        if (appear < 1f) {
            appear = Math.min(1f, el / 2200f);
        }

        int arcColor = Ui.LIGHT
                ? Ui.mix(Ui.ACCENT, 0xFF2C3320, 0.55f)
                : Ui.mix(Ui.ACCENT, 0xFFE8F0DC, 0.72f);

        /*
         * 裁剪到视图内。
         *
         * 阵放大到超过屏幕后，弦的端点会落在屏幕外。如果不裁剪，
         * 画出来的一段弦会在边界处被隐式切断，看起来像屏幕边上
         * 一根孤零零的竖线——它其实只是一根斜弦的末端。
         * 显式裁剪之后，边界处的线是干净地画出去的，不再有断头。
         */
        int save = canvas.save();
        canvas.clipRect(0, 0, w, h);
        canvas.translate(w * 0.5f, h * 0.5f + originY);

        for (int i = 0; i < full; i++) {
            drawOrbitRing(canvas, i, R, el, arcColor, 1f);
        }
        // 生长中的半层：以 frac 比例的透明度淡入，实现连续变密
        if (full < MAX_LAYERS && frac > 0.02f) {
            drawOrbitRing(canvas, full, R, el, arcColor, frac);
        }

        drawSunflower(canvas, R * 0.98f, el, arcColor);

        canvas.restoreToCount(save);
    }

    /**
     * 画第 i 层：整层绕极点转 ω_i·t + φ_i，层内 n_i 个单元已完成复制。
     *
     * @param fadeIn 0..1，用于连续生长时的淡入
     */
    /**
     * 画黄金角散点。
     *
     * <p>方向与弦网相反，速度极慢。两者交错旋转时，
     * 点的位置相对弦一直在移动，会不断"穿过"弦的缝隙——
     * 这是深度感的来源：看起来像两层不同距离的东西在重叠。
     *
     * <p>恒定亮度、不闪烁。闪烁会让它显得廉价，
     * 而且真闪起来就是"噪声"，不是"星星"。
     */
    private void drawSunflower(Canvas c, float R, long elapsed, int color) {
        if (dotsPath == null) return;

        /*
         * 与弦网**反向，而且明显更快**。
         *
         * 两者速度必须分开：矩阵慢才显得沉、不抢注意力；
         * 方块快才显得活、像浮在空中的星尘。
         * 一起调慢就成了一潭死水，一起调快就互相打。
         *
         * 快的方块不断"穿过"慢的弦的缝隙，相对运动一直在变——
         * 这才是纵深感真正的来源，光靠亮度差做不出来。
         * 速度比取无理数（√5），所以两者永不同步。
         */
        /*
         * 星点速度：矩阵的**两倍**，反方向。
         *
         * 不给独立常数，直接由 TURN_MS 推出来——
         * 这样以后调矩阵快慢，星点会自动跟着保持"两倍"这个关系，
         * 不会像刚才那样矩阵慢了、星点却还按旧速度跑，显得突兀。
         *
         * 反方向是为了让两者始终交错：快的那层不断穿过慢的那层的缝隙，
         * 这是纵深感真正的来源。
         */
        double dotOmega = (2 * Math.PI / (TURN_MS / 2.0)) / ((1 + Math.sqrt(5)) / 2);
        double angle = -elapsed * dotOmega;

        int save = c.save();
        c.rotate((float) Math.toDegrees(angle % (2 * Math.PI)), 0, 0);
        c.scale(R, R);

        /*
         * 散点分两层画：先一层放大的淡光晕，再一层实心点。
         *
         * 只画实心点会显得"脏"——像屏幕上落了灰。
         * 加一层晕之后，每颗点都有一点弥散的光，
         * 在暗底上才像星，而不是像素块。
         * 两次都是同一条 Path，只是缩放不同，成本没有翻倍。
         */
        linePaint.setStyle(Paint.Style.FILL);
        linePaint.setColor(color);

        // 光晕：放大 1.5 倍、很淡，只做一点弥散
        int save2 = c.save();
        c.scale(1.5f, 1.5f);
        linePaint.setAlpha((int) (22 * shown));
        c.drawPath(dotsPath, linePaint);
        c.restoreToCount(save2);

        // 实心点：亮点本身，约 1.6 像素
        linePaint.setAlpha((int) (64 * shown));
        c.drawPath(dotsPath, linePaint);

        linePaint.setStyle(Paint.Style.STROKE);

        c.restoreToCount(save);
    }

    private void drawOrbitRing(Canvas c, int i, float R, long elapsed,
                               int color, float fadeIn) {
        if (layerPath == null || i >= layerPath.length || layerPath[i] == null) return;

        // ω_i：无理比轮换，任意两层都不通约
        double omega = OMEGA_BASE[i % OMEGA_BASE.length] * OMEGA_SCALE * (i % 2 == 0 ? 1 : -1);

        // φ_i：黄金角步进，任意两层的相位差都不重复
        double phi = i * GOLDEN_ANGLE;

        double angle = omega * elapsed + phi;

        /*
         * 缩放：层与层之间用无理比拉开间距（0.78 的幂），
         * 但不用太大——层要能看出是"一圈套一圈"，
         * 拉太开会散成几个独立的环，失去整体的阵感。
         */
        float scale = (float) Math.pow(0.78, i);

        /*
         * 亮度：两层各自不同。
         *
         * 外层（i=0，大而疏）给中等亮度，内层（i=1，小而密）更亮一点。
         * 眼睛先看到内层的密织，再读到外层的大轮廓，有层次；
         * 两层一样亮就会糊成一片灰。
         */
        float ti = i / 2f;
        float alpha = (0.46f - 0.10f * ti) * shown * fadeIn;
        if (alpha <= 0.004f) return;

        int save = c.save();
        c.rotate((float) Math.toDegrees(angle), 0, 0);
        c.scale(scale * R, scale * R);

        // 辉光：只给内层一点点。辉光是"厚"的来源，多了就糊成一片
        if (i == 1) {
            glowPaint.setColor(color);
            glowPaint.setAlpha((int) (alpha * 26));
            glowPaint.setStrokeWidth(dp(1.0f) / (scale * R));
            c.drawPath(layerPath[i], glowPaint);
        }

        linePaint.setColor(color);
        linePaint.setAlpha((int) (alpha * 235));
        linePaint.setStrokeWidth(dp(0.32f) / (scale * R));
        c.drawPath(layerPath[i], linePaint);

        c.restoreToCount(save);
    }

    /**
     * 建骨架：每层一条 Path，里面已经焊好该层 n_i 个 orbit。
     *
     * <p>半径统一按 1 建，实际大小由 canvas.scale 决定——
     * 这样屏幕尺寸和状态变化都不会触发重建。
     */
    private void ensureSkeleton(int w, int h) {
        if (layerPath != null && builtW == w && builtH == h) return;
        builtW = w;
        builtH = h;

        Path[] ps = new Path[MAX_LAYERS];
        for (int i = 0; i < MAX_LAYERS; i++) {
            ps[i] = buildLayer(i);
        }
        layerPath = ps;
        /*
         * 散点按"屏幕短边的一半"这个固定基准建，不用会随负载变化的 R。
         * 否则负载一变，R 就变，点的物理尺寸也跟着变——那就成了会缩放的星尘。
         */
        dotsPath = buildSunflower(Math.min(w, h) * 0.5f);
    }

    /**
     * 建第 i 层：一层模乘连线阵（Modular Multiplication Circle）。
     *
     * <p><b>为什么是它。</b>规则只有一行：
     *
     * <pre>
     *   圆周 N 等分 → 点 k 连到点 (k·M) mod N
     * </pre>
     *
     * 但画出的弦族会**自发**长出心形线、肾形线、花瓣。
     * 这不是我画出来的形状——是弦族的包络线，
     * 就像阳光照在杯子内壁聚出的心形光斑。
     * 单看任何一条弦都毫无意义，密起来才有形状，
     * 这正是"魔法阵"感的来源：结构是涌现的，不是拼上去的。
     *
     * <p><b>M 决定形态。</b>M=2 出心形，M 增大越来越碎，
     * 逼近 N/2 时最杂乱。所以 M 由负载驱动：
     * 空闲时干净，忙时复杂——复杂的是形态本身，不只是多少。
     *
     * <p><b>N 不能太大。</b>实测：N 到 600 以上包络就开始糊，
     * 各方向疏密差被平均掉，心形线看不出是条线了。
     * 真正的华丽在"密到刚好认得出形状"，不是无限堆叠。
     */
    /**
     * 黄金角散点阵：向日葵花盘的排布规则。
     *
     * <p>规则只有两行：
     *
     * <pre>
     *   r     = √(n / N)        ← 半径按根号增长，所以中心疏、外围密
     *   theta = n · 137.5077°   ← 每点转黄金角，永不回到相同方向
     * </pre>
     *
     * 大量点撒下去后，会**自发**长出顺时针与逆时针两套交织的螺旋，
     * 分支数严格是斐波那契数列。这套规则没有任何"螺旋"的指令，
     * 螺旋是从无理数旋转里涌现出来的——这正是它耐看的原因。
     *
     * <p>用极小的方块而不是圆：方块用两条线就能写完，
     * 240 个点全都焊在一条 Path 里，一次 drawPath 画完，成本可忽略。
     * 每个方块边长 DOT_SIZE，在屏幕上约 1.5 像素，看起来就是一颗亮星。
     */
    private Path buildSunflower(float backendRadius) {
        Path p = new Path();
        /*
         * 点的半边长。**必须按 dp 定，不能按单位圆比例定。**
         *
         * 这里踩过一个坑：之前用固定的比例值（0.0032），
         * 在 1280px 宽的屏幕上算出来是 40 像素的点，糊成一片。
         * 因为阵的半径 R 随屏幕分辨率线性变化，
         * 比例值一乘上去，同一份代码在不同手机上大小差好几倍。
         *
         * 改成"每颗点固定 1.6dp"：先在屏幕空间定尺寸，
         * 再除回单位圆的比例（/ R），物理大小就恒定了。
         */
        final float DOT = dp(DOT_DP) / backendRadius;

        for (int n = 0; n < SUNFLOWER_N; n++) {
            float r = (float) Math.sqrt(n / (double) SUNFLOWER_N);
            double th = n * GOLDEN_ANGLE;

            float cx = (float) (r * Math.cos(th));
            float cy = (float) (r * Math.sin(th));

            p.addRect(cx - DOT, cy - DOT, cx + DOT, cy + DOT, Path.Direction.CW);
        }
        return p;
    }

    private Path buildLayer(int i) {
        Path p = new Path();

        /*
         * 两层，不同规模。
         *
         * 原来三层 N=360/240/160，每帧要画 760 根弦，是卡顿的主因。
         * 降到两层 N=240/150 之后约 380 根，包络形态几乎不变——
         * 因为包络线是宏结构，不需要那么高的多边形精度就能长出来。
         */
        /*
         * 三层，N 各不相同。
         *
         * N 不同不只是"疏密不同"：同一个 M 在不同 N 下，
         * k·M mod N 的折叠方式不一样，包络曲线也不同。
         * 三层 N 拉开，三条轮廓形状就有本质差别。
         */
        final int[] N_TABLE = {DRIFT_N, 200, 150};
        int n = N_TABLE[i % N_TABLE.length];

        /*
         * M 从 complex 取，每层再错开一点，形成纵深。
         * M 越大越缠绕：M=3 接近心形，M 接近 n/2 时最碎。
         */
        /*
         * 两层的 M 必须拉开，不能只差几个数。
         *
         * 心形包络是模乘阵在 M 偏小时必然长出来的形状，
         * 而且左右完美对称——这正是它看起来像某个具体物体的原因。
         * 两层 M 接近时，两个心形几乎重合，对称一点没破，看完还是心形。
         *
         * 把第二层的 M 推到接近 n/3 的位置，它的包络是另一种完全不同的
         * 多瓣曲线；两种曲线反向旋转、互相切割，对称被打散，
         * 剩下的是一片交织的网——有结构，但没有可指认的形状。
         */
        /*
         * 三层的 M 要分处三个完全不同的区段。
         *
         * 模乘阵在 M 小（≈3）、M 中（≈n/4）、M 大（≈n/2）时，
         * 生成的是**三种不同的曲线族**：单瓣、多瓣、密集缠绕。
         * 三条轮廓形状不同，叠在一起才会互相切割、谁也数不出完整边界。
         *
         * 如果三层 M 只差几个数，它们会画出几乎相同的轮廓，
         * 叠起来还是那个形状——这就是之前怎么调都像同一个东西的原因。
         *
         * 负载（busy）只在各自区段内轻微移动，不跨越区段，
         * 保证"三种形状并存"这个结构永远成立。
         */
        int mLo = 3;
        int mHi = Math.max(4, n / 2);
        int m;
        if (i == 0) {
            m = mLo + (int) (Math.round(complex) * 0.6);            // 小 M：单瓣曲线
        } else if (i == 1) {
            m = n / 4 + (int) Math.round(complex * 0.5);            // 中 M：多瓣曲线
        } else {
            m = n / 2 - (int) Math.round(complex * 0.8);            // 大 M：密集缠绕
        }
        if (m < mLo) m = mLo;
        if (m > mHi) m = mHi;

        /*
         * 只画长弦。
         *
         * 模乘阵的包络由长弦之间的相互切割形成，短弦只是把它填灰。
         * 跳过长度小于 CIRCLE_MIN_CHORD 的弦，能省掉三四成绘制量，
         * 而形态一点不丢——这是"降弦数不掉观感"的关键。
         */
        for (int k = 0; k < n; k++) {
            int j = (int) ((long) k * m % n);
            if (j == k) continue;

            double a1 = 2 * Math.PI * k / n;
            double a2 = 2 * Math.PI * j / n;

            float x1 = (float) Math.cos(a1);
            float y1 = (float) Math.sin(a1);
            float x2 = (float) Math.cos(a2);
            float y2 = (float) Math.sin(a2);

            // 弦长平方，单位圆内最大为 4
            float dx = x1 - x2;
            float dy = y1 - y2;
            if (dx * dx + dy * dy < MIN_CHORD2) continue;

            p.moveTo(x1, y1);
            p.lineTo(x2, y2);
        }
        return p;
    }

    /** 按当前强度淡出：暗色往底色靠，浅色往白靠。 */
    private int fade(int color, float extra) {
        float k = Math.max(0f, Math.min(1f, shown * extra));
        if (k >= 0.999f) return color;
        int under = Ui.LIGHT ? 0xFFFAFAF6 : 0xFF0C0F08;
        return Ui.mix(under, color, k);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
