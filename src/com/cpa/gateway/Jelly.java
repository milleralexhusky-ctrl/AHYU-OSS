package com.cpa.gateway;

import android.annotation.SuppressLint;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.Choreographer;

/**
 * 果冻手感：按压缩放变形（squash & stretch）+ 弹簧回弹 + 拉向手指。
 *
 * 和上一版的区别：上一版是"三段补间串起来"（Overshoot 插值 + 两段手工抖动），
 * 手感是定死的、不能打断。这里是**真的阻尼弹簧**，逐帧积分：
 *
 *     a = -k·(x - target) - 2ζ√k·v
 *
 * ζ（dampingRatio）就是"弹几下"的开关：
 *     ζ ≥ 1   不弹（临界/过阻尼）
 *     ζ 0.4~0.6  最果冻，松手后晃 2~3 下收敛   ← 本文件用的区间
 *     ζ < 0.2  晃得停不下来
 *
 * 性能：只动 scaleX/scaleY/translation，不触发布局；静止时停掉帧回调，零开销。
 */
final class Jelly {

    private Jelly() {
    }

    /* ------------------------------------------------------------- 手感参数 */

    /** 按下时鼓多大 / 扁多少（横向涨、纵向缩，体积感守恒）。 */
    private static final float SQUASH_X = 1.035f;
    private static final float SQUASH_Y = 0.945f;

    /** 按下去：硬一点、不弹，干脆利落。 */
    private static final float PRESS_K = 1800f;
    private static final float PRESS_ZETA = 0.85f;

    /** 松手回弹：这才是果冻，ζ 落在 0.4~0.6 会自然晃 2~3 下。 */
    private static final float RELEASE_K = 780f;
    private static final float RELEASE_ZETA = 0.50f;

    /** 跟手阻尼：手指拖多远，按钮跟多少。 */
    private static final float FOLLOW_X = 0.30f;
    private static final float FOLLOW_Y = 0.18f;

    /*
     * 关于"按下抬升"（原 LIFT_DP = 12dp）：已移除，不再动 Z 轴。
     *
     * 原来按下时 v.setTranslationZ(最高 12dp)，配合 Ui 给主按钮的 setElevation(2dp)，
     * Z 轴到 14dp，系统就会在控件周围渲染投影。而按钮背景是**半透明玻璃**
     * （GlassDrawable.liquid 用 #46FFFFFF 这类带 alpha 的填充），投影会透过本体显出来，
     * 看起来就是"旁边多了一个透明的东西把按钮陷进去"，体感全失。
     *
     * 半透明材质 + 系统投影本身就是矛盾的：真实玻璃的投影极淡。
     * 现在投影只由 GlassDrawable 自己画的那层负责（shadowRect.offset(0, dp(2))），
     * 它跟着按钮一起缩放、画在同一个 canvas 上，视觉自洽，也没有 Z 轴的穿帮。
     *
     * 代价（已知并接受）：投影不再随按压变化。按压反馈由 squash 缩放、
     * 跟手位移和 PRESS_DIM（现 0.04）的透明度变暗承担——这三样都还在。
     */

    /**
     * 跟手位移的上限（dp）。
     *
     * 以前 dx/dy 无上限：手指横拖 1000px，按钮就跟 300px，直接飞出控件。
     * 更糟的是 follow 还乘进 scaleX/scaleY（见 SCALE_FOLLOW_*），
     * **实测横拖仅 167px 就把 scaleY 压成负数——按钮镜像翻转**（负 scale 就是翻转）。
     *
     * 18dp → 8dp：原来的 18dp（含橡皮筋极限约 74px）刚好够跨过卡片的 18dp padding，
     * 按钮能被拖到卡片边缘上，和卡片背景的圆角（28dp）错位重叠，
     * 露出一块圆角对不上的矩形叠加区——用户反馈的"隔层"就是这么来的
     * （正常操作看不到，只有刻意把按钮拖到卡片边缘才发现）。
     *
     * 收到 8dp 后极限位移约 33px < 卡片 padding 的 49px，按钮永远到不了边界，
     * 隔层无从暴露；而 8dp 的跟手反馈（约 22px）仍然清晰可见，果冻手感不受影响。
     */
    private static final float FOLLOW_LIMIT_DP = 8f;

    /**
     * 跟手位移对缩放的耦合系数。
     *
     * 原值是 0.04 / 0.02，而 follow 最大能到几百，于是 scaleX 涨到 13 倍、
     * scaleY 掉到 -5 倍。现在（a）位移本身有上限，（b）这里的系数只作用在
     * **已归一化到 [-1,1] 的位移**上，两者相乘后再夹一层安全范围，
     * 保证 scale 永远落在 [0.7, 1.35] 这种"看得出跟手、不可能翻转"的区间。
     */
    private static final float SCALE_FOLLOW_X = 0.10f;
    private static final float SCALE_FOLLOW_Y = 0.05f;

    /** scale 的硬安全范围：无论参数怎么调，都不允许接近 0 或超过 1.5。 */
    private static final float SCALE_MIN = 0.70f;
    private static final float SCALE_MAX = 1.35f;

    /** 固定子步长：不管设备跑 60 还是 120 帧，积分都稳。 */
    private static final float SUBSTEP = 1f / 240f;

    /**
     * 按压时的整体透明度降幅。
     *
     * 原值 0.12。在**大面积半透明卡片**上副作用过大：卡片背景本身只有 ~28% 不透明，
     * 而 GlassDrawable 内部是 5 层半透明叠加（base + dark + hi + bottom + rim），
     * 每层都被整体 alpha 再缩放一次，叠加后"看得见的底"明显变淡——
     * 真机表现就是按住「查看全部账号」那行时卡片几乎"化掉"、只剩文字飘着。
     *
     * 降到 0.04：小按钮仍有一点变暗反馈，大卡片不再穿透。
     * 按压反馈的主力本来就该是缩放（SQUASH_X/Y 的 1.035 / 0.945）和跟手位移，
     * 变暗只是辅助——辅助不该盖过主反馈。
     */
    private static final float PRESS_DIM = 0.04f;

    /* ------------------------------------------------------------- 弹簧本体 */

    /** 一维阻尼弹簧。 */
    static final class Spring {
        float value;
        float velocity;
        float target;
        float stiffness = RELEASE_K;
        float zeta = RELEASE_ZETA;

        Spring(float initial) {
            value = initial;
            target = initial;
        }

        void snap(float v) {
            value = v;
            target = v;
            velocity = 0f;
        }

        void to(float t, float k, float z) {
            target = t;
            stiffness = k;
            zeta = z;
        }

        /** 推进 dtMs 毫秒；返回 true 表示还在动。 */
        boolean tick(long dtMs) {
            float dt = dtMs / 1000f;
            if (dt <= 0f) return true;
            if (dt > 0.05f) dt = 0.05f;                 // 掉帧时别炸
            int steps = Math.max(1, (int) Math.ceil(dt / SUBSTEP));
            float h = dt / steps;
            float c = 2f * zeta * (float) Math.sqrt(stiffness);
            for (int i = 0; i < steps; i++) {
                float a = -stiffness * (value - target) - c * velocity;
                velocity += a * h;
                value += velocity * h;
            }
            return Math.abs(value - target) > 5e-4f || Math.abs(velocity) > 5e-3f;
        }
    }

    /* --------------------------------------------------------------- 挂载 */

    /**
     * 位移夹取 + 橡皮筋衰减：越拉越拉不动。
     *
     * 直接硬夹到上限会"撞墙"（手感断裂）；这里按经典橡皮筋公式：
     * 超过上限后，超出部分按 1/(1+超出量/上限) 衰减，永远收敛到 1.5 倍上限，
     * 既拉不飞、也一直有反馈。
     *
     * @param rawDp   原始位移（dp）
     * @param limitDp 线性区上限（dp）
     */
    private static float rubber(float rawDp, float limitDp) {
        float a = Math.abs(rawDp);
        if (a <= limitDp) return rawDp;
        float over = a - limitDp;
        float softened = limitDp + (limitDp * over) / (limitDp + over);
        return rawDp < 0 ? -softened : softened;
    }

    /** 把跟手位移归一化到 [-1,1]，供缩放耦合使用。 */
    private static float norm(float dp, float limitDp) {
        float n = dp / (limitDp * 1.5f);
        if (n > 1f) n = 1f;
        if (n < -1f) n = -1f;
        return n;
    }

    @SuppressLint("ClickableViewAccessibility")
    static void attach(final View v) {
        if (v == null) return;

        final float density = v.getResources().getDisplayMetrics().density;
        final Spring press = new Spring(0f);          // 0=原状 1=按到底
        final float[] anchor = new float[2];          // 按下的位置
        final float[] follow = new float[2];          // 当前跟手位移
        final Spring fx = new Spring(0f);
        final Spring fy = new Spring(0f);
        final boolean[] animating = new boolean[]{false};
        // 手指是否还按着。静止判定必须看它，否则"按住不动"会被误判成"该收尾了"：
        // press 的目标是 1，弹簧 0.2~0.3 秒就收敛到 1，tick() 返回 false →
        // 走静止分支 press.snap(0f) 瞬时归零 → 按钮在手指还压着时硬弹回原状，
        // 而且松手时 press 已经是 0、回弹果冻也没了。普通长按就会撞到。
        final boolean[] held = new boolean[]{false};

        final Choreographer choreographer = Choreographer.getInstance();
        final Choreographer.FrameCallback[] frame = new Choreographer.FrameCallback[1];
        final long[] last = new long[]{0L};

        frame[0] = new Choreographer.FrameCallback() {
            @Override
            public void doFrame(long nowNs) {
                long now = nowNs / 1000000L;
                long dt = last[0] == 0L ? 16L : Math.max(1L, Math.min(48L, now - last[0]));
                last[0] = now;
                if (last[0] != 0L) Perf.frame(dt);   // 动画期间才统计，静止不算

                boolean alive = press.tick(dt);
                alive |= fx.tick(dt);
                alive |= fy.tick(dt);
                follow[0] = fx.value;
                follow[1] = fy.value;

                float p = press.value;
                // 跟手位移按"归一化后再乘小系数"参与缩放，最后硬夹在安全范围内。
                // 原来直接 follow[0]*0.04 是无上限的：横拖 167px 就能把 scaleY 压到负数
                // （负 scale = 镜像翻转），这正是"拉太过会翻过来"的成因。
                float fn = norm(follow[0], FOLLOW_LIMIT_DP * density);
                float sx = 1f + (SQUASH_X - 1f) * p + fn * SCALE_FOLLOW_X;
                float sy = 1f - (1f - SQUASH_Y) * p - fn * SCALE_FOLLOW_Y;
                if (sx < SCALE_MIN) sx = SCALE_MIN;
                if (sx > SCALE_MAX) sx = SCALE_MAX;
                if (sy < SCALE_MIN) sy = SCALE_MIN;
                if (sy > SCALE_MAX) sy = SCALE_MAX;
                v.setScaleX(sx);
                v.setScaleY(sy);
                v.setTranslationX(follow[0]);
                v.setTranslationY(follow[1]);
                v.setAlpha(1f - PRESS_DIM * p);

                if (alive) {
                    choreographer.postFrameCallback(this);
                } else if (held[0]) {
                    // 弹簧收敛了，但手指还按着 —— **不能**归零。
                    // 此时的收敛是"到达目标后停住"（press 已停在 1，scale/alpha 是按压姿态），
                    // 归零就等于在用户还按着的时候把按钮硬弹回去。
                    // 正确做法：停掉帧回调省电，但保留当前姿态；后续 MOVE 会重新 kick。
                    animating[0] = false;
                    last[0] = 0L;
                } else {
                    // 收干净，停在精确的静止姿态
                    press.snap(0f);
                    fx.snap(0f);
                    fy.snap(0f);
                    v.setScaleX(1f);
                    v.setScaleY(1f);
                    v.setTranslationX(0f);
                    v.setTranslationY(0f);
                    v.setAlpha(1f);
                    animating[0] = false;
                    last[0] = 0L;
                }
            }
        };

        final Runnable kick = () -> {
            if (animating[0]) return;
            animating[0] = true;
            last[0] = 0L;
            choreographer.postFrameCallback(frame[0]);
        };

        v.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    anchor[0] = event.getRawX();
                    anchor[1] = event.getRawY();
                    // 仍然不关父容器的 clipChildren —— 原因变了。
                    //
                    // 旧原因：一放开裁剪，GlassDrawable 自画的阴影（比本体大一圈、
                    // 下移 2dp）就露出来，看着像按钮钻进了"隔层"。当时的选择是
                    // 宁可不让控件拖出边界，也不让那圈阴影露出来。
                    //
                    // 4.30.22 起阴影被裁进本体范围（见 GlassDrawable.draw），
                    // 外露的根源没了。所以这里维持不放开裁剪，纯粹是为了
                    // "不让被拖动的控件画到相邻卡片之上/之下"——那是真的层级错乱，
                    // 与阴影无关。控件被裁在卡片内仍是更正常的行为。
                    held[0] = true;
                    press.to(1f, PRESS_K, PRESS_ZETA);
                    kick.run();
                    try {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    } catch (Throwable ignored) {
                    }
                    break;
                }
                case MotionEvent.ACTION_MOVE: {
                    // 原始位移 → 橡皮筋衰减（越拉越拉不动，且永远收敛）
                    float dx = rubber((event.getRawX() - anchor[0]) * FOLLOW_X,
                            FOLLOW_LIMIT_DP * density);
                    float dy = rubber((event.getRawY() - anchor[1]) * FOLLOW_Y,
                            FOLLOW_LIMIT_DP * density);
                    fx.to(dx, 260f, 0.85f);
                    fy.to(dy, 260f, 0.85f);
                    kick.run();
                    break;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    held[0] = false;
                    press.to(0f, RELEASE_K, RELEASE_ZETA);
                    fx.to(0f, RELEASE_K, RELEASE_ZETA);
                    fy.to(0f, RELEASE_K, RELEASE_ZETA);
                    kick.run();
                    break;
                }
                default:
                    break;
            }
            return false;   // 不拦截，点击照常
        });
    }
}
