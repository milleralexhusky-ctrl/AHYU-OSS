package com.cpa.gateway.core;

/**
 * 下拉刷新的手势判定（4.51.6）：一个**不引用任何 View** 的纯状态机。
 *
 * <h3>为什么要单独抽出来</h3>
 *
 * 这是最会**静默坏掉**的那一类代码：判定写错了不报错、不抛异常，
 * 只是"手指划半天什么都不发生"。而它天然要靠真机 + 手指才试得出来 ——
 * 这台设备一旦锁屏，{@code input swipe} 注入的触摸全都进了锁屏界面，
 * 根本到不了应用，"没反应"于是有两种可能（手势写错了 / 触摸没进来），
 * 在真机上分不清。
 *
 * <p>所以把"往哪个方向、够不够远、该不该接管"这套判断抽成一个纯函数式的状态机，
 * 由 {@link Selftest} 直接喂事件序列断言每个分支。真机验证只能证明"这次能用"，
 * 这里能证明"按住再松手不会误触发""能上滚时不会抢手势"这些分支确实是对的。
 *
 * <h3>动作常量为什么等于 MotionEvent 的值</h3>
 * 这里刻意**不 import android.view.MotionEvent**（core 包保持干净、自检也不依赖
 * UI），但常量取值与 {@code MotionEvent.ACTION_*} 完全一致：
 * DOWN=0、UP=1、MOVE=2、CANCEL=3。调用方直接把 {@code ev.getActionMasked()}
 * 传进来即可，不需要映射表 —— 少一张表就少一处会写错的地方。
 * 下面有一组编译期断言钉住这四个值。
 */
public final class PullGesture {

    /** 与 {@code MotionEvent.ACTION_DOWN} 同值。 */
    public static final int DOWN = 0;
    /** 与 {@code MotionEvent.ACTION_UP} 同值。 */
    public static final int UP = 1;
    /** 与 {@code MotionEvent.ACTION_MOVE} 同值。 */
    public static final int MOVE = 2;
    /** 与 {@code MotionEvent.ACTION_CANCEL} 同值。 */
    public static final int CANCEL = 3;

    /* 动作常量必须与 MotionEvent 对齐 —— 对齐靠的是上面那四行写着同样的数字，
       所以这里放一组断言：万一有人改了值，自检立刻会红，而不是等到真机上
       "下拉没反应"才发现。 */
    static {
        if (DOWN != 0 || UP != 1 || MOVE != 2 || CANCEL != 3) {
            throw new AssertionError("PullGesture 的动作常量与 MotionEvent 不一致");
        }
    }

    /* ---------------------------------------------------------- 判定结果 */

    /** 什么都不做（调用方照常放行给子控件）。 */
    public static final int NONE = 0;
    /** 从这里开始接管这次手势（{@code onInterceptTouchEvent} 返回 true）。 */
    public static final int START = 1;
    /** 正在下拉，按位移更新指示器。 */
    public static final int DRAG = 2;
    /** 松手且拉够了 → 触发刷新。 */
    public static final int FIRE = 3;
    /** 松手但没拉够（或取消）→ 平滑收回。 */
    public static final int SETTLE = 4;

    private final int slop;
    private final float trigger;

    private float anchorY;
    private float rawDy;
    private boolean active;
    private boolean down;

    /**
     * @param slop      触摸判定阈值（px），一般传 {@code ViewConfiguration.getScaledTouchSlop()}
     * @param triggerPx 触发刷新的位移阈值（px）
     */
    public PullGesture(int slop, float triggerPx) {
        this.slop = Math.max(0, slop);
        this.trigger = Math.max(1f, triggerPx);
    }

    /** 手指实际位移（px，非负），供视觉阻尼使用。 */
    public float rawDy() {
        return rawDy;
    }

    /** 是否已经接管这次手势。 */
    public boolean active() {
        return active;
    }

    /**
     * 喂一个事件。
     *
     * @param action      {@link #DOWN} / {@link #MOVE} / {@link #UP} / {@link #CANCEL}
     * @param y           事件在容器坐标系里的 y
     * @param canScrollUp 此刻内容是否还能往上滚（能则本次手势让给它）
     * @return {@link #NONE} / {@link #START} / {@link #DRAG} / {@link #FIRE} / {@link #SETTLE}
     */
    public int on(int action, float y, boolean canScrollUp) {
        switch (action) {
            case DOWN:
                anchorY = y;
                rawDy = 0f;
                active = false;
                down = true;
                return NONE;
            case MOVE: {
                if (!down) return NONE;           // 没有 DOWN 的野 MOVE 不启动
                float d = y - anchorY;
                if (active) {
                    rawDy = Math.max(0f, d);      // 反向拖回不能变负，否则阻尼曲线算出异常值
                    return DRAG;
                }
                /* 没拉过 slop 之前一律不接管：否则每次点击、每次横向滑动
                   都会被当成下拉的开头，按钮点不动。 */
                if (d <= slop) return NONE;
                /* 内容还能往上滚就先让给它 —— 用户是在滚列表，不是下拉刷新。 */
                if (canScrollUp) return NONE;
                active = true;
                rawDy = d;
                return START;
            }
            case UP:
            case CANCEL: {
                down = false;
                if (!active) return NONE;
                active = false;
                /* 取消（父级抢走、来电等）不算触发 —— 用户没松手表达意愿。 */
                if (action != UP) return SETTLE;
                return rawDy >= trigger ? FIRE : SETTLE;
            }
            default:
                return NONE;
        }
    }
}
