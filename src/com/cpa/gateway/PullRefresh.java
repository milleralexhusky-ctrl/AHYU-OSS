package com.cpa.gateway;

import android.content.Context;
import android.view.MotionEvent;

import com.cpa.gateway.core.PullGesture;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.LinearLayout;

/**
 * 下拉刷新容器（4.51.6）：给**本身不滚动**的页面挂一个下拉手势。
 *
 * <h3>为什么用 onInterceptTouchEvent 而不是 setOnTouchListener</h3>
 *
 * 第一版是在根布局上挂 {@code setOnTouchListener}，结果**完全不生效**：手指按在
 * 卡片/按钮上时，DOWN 会被那个子控件消费掉，根布局的监听器根本收不到，
 * 后面的 MOVE/UP 自然也不会送来。真机实测下拉毫无反应，而且不报任何错 ——
 * 典型"静默失效"。
 *
 * 改成在**父容器**上拦截才是对的：
 * <ul>
 *   <li>DOWN 仍然先派发给子控件 → 按钮点击、卡片按压照常；</li>
 *   <li>等到手指往下拖过 touch slop 再 {@code onInterceptTouchEvent} 返回 true，
 *       从这里开始事件归本容器，**框架会自动给子控件补一个 ACTION_CANCEL** ——
 *       子控件（比如 {@link Jelly} 的按压态）能正确收尾，不会卡在按下的样子；</li>
 *   <li>只认"往下拖且内容已经到顶"这一个方向，上滑和横向不受影响。</li>
 * </ul>
 *
 * <h3>判定逻辑为什么抽成 {@link PullGesture}</h3>
 * 因为这是**最会静默坏掉**的那一类代码：手势判断错了不会报错、不会有异常，
 * 只会"什么也不发生"。而它又必须在真机上、用手指才能试出来 —— 这台设备一旦锁屏，
 * 注入的触摸根本到不了应用（全都进了锁屏界面），自动验证就断了。
 *
 * <p>所以把"往哪个方向、够不够远、该不该接管"这套判断抽成一个**不碰 View 的纯状态机**，
 * 由 {@code Selftest} 直接喂事件序列断言。真机验证只能证明"这次能用"，
 * 状态机断言能证明"按住再松手不会误触发""能上滚时不会抢手势"这些分支确实是对的。
 */
final class PullRefresh extends LinearLayout {

    /** 拉过这个距离松手才算数（dp），按**手指实际位移**算，不是阻尼后的视觉位移。 */
    private static final float TRIGGER_DP = 64f;
    /** 视觉位移的收敛上限（dp）：再拉也不动更多。 */
    private static final float MAX_DP = 96f;
    /** 刷新中指示器停留的位置（dp）。 */
    private static final float HOLD_DP = 28f;

    interface OnRefresh {
        /**
         * 执行刷新。
         *
         * @param done 刷完必须调用它 —— 指示器的收起由容器负责，
         *             页面只管什么时候算刷完
         */
        void onRefresh(Runnable done);
    }

    /* -------------------------------------------------------------- 视图 */

    private final float density;
    private final View indicator;
    private final PullGesture gesture;
    private OnRefresh onRefresh;
    private boolean refreshing;

    PullRefresh(Context c, View indicator) {
        super(c);
        this.indicator = indicator;
        this.density = c.getResources().getDisplayMetrics().density;
        this.gesture = new PullGesture(ViewConfiguration.get(c).getScaledTouchSlop(),
                TRIGGER_DP * density);
        setOrientation(VERTICAL);
    }

    void setOnRefresh(OnRefresh r) {
        this.onRefresh = r;
    }

    /** 本容器**不**在 DOWN 时就抢：先让子控件拿到，拖过阈值才拦截。 */
    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (onRefresh == null || refreshing) return false;
        int d = gesture.on(ev.getActionMasked(), ev.getY(), canScrollVertically(-1));
        if (d == PullGesture.START) return true;
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        int a = ev.getActionMasked();
        int d = gesture.on(a, ev.getY(), canScrollVertically(-1));
        switch (d) {
            case PullGesture.DRAG:
                applyIndicator(visual(gesture.rawDy()));
                return true;
            case PullGesture.FIRE:
                startRefresh();
                return true;
            case PullGesture.SETTLE:
                settle();
                return true;
            default:
                /* DOWN 之后本容器已经接管（active），即使这一帧判定是 NONE
                   也要吃掉事件，否则会漏给下层。 */
                return gesture.active() || a == MotionEvent.ACTION_DOWN;
        }
    }

    /**
     * 手指位移 → 视觉位移。
     *
     * <p>指数阻尼：位移越大增量越小，永远收敛到 {@code MAX_DP}。
     * 直接线性跟手会让人觉得"拉不到头"，阻尼之后才有"有东西拽着"的手感
     * （和 {@link Jelly} 的橡皮筋是同一个路子）。
     */
    private float visual(float rawPx) {
        float raw = rawPx / density;
        if (raw <= 0f) return 0f;
        return MAX_DP * (1f - (float) Math.exp(-raw / MAX_DP)) * density;
    }

    private void applyIndicator(float d) {
        if (indicator == null) return;
        float trigger = TRIGGER_DP * density;
        indicator.setVisibility(View.VISIBLE);
        indicator.setTranslationY(d * 0.5f);
        indicator.setAlpha(Math.min(1f, d / trigger));
        if (indicator instanceof android.widget.TextView) {
            ((android.widget.TextView) indicator)
                    .setText(gesture.rawDy() >= trigger ? "↑ 松手刷新" : "↓ 下拉刷新");
        }
    }

    private void settle() {
        if (indicator == null) return;
        indicator.animate().translationY(0f).alpha(0f).setDuration(180L)
                .withEndAction(() -> indicator.setVisibility(View.GONE)).start();
    }

    private void startRefresh() {
        if (refreshing) return;
        refreshing = true;
        final float hold = HOLD_DP * density;
        if (indicator != null) {
            indicator.setVisibility(View.VISIBLE);
            if (indicator instanceof android.widget.TextView) {
                ((android.widget.TextView) indicator).setText("正在刷新…");
            }
            indicator.animate().translationY(hold).alpha(1f).setDuration(120L).start();
        }
        onRefresh.onRefresh(() -> {
            refreshing = false;
            if (indicator != null) {
                indicator.animate().translationY(0f).alpha(0f).setDuration(220L)
                        .withEndAction(() -> indicator.setVisibility(View.GONE)).start();
            }
        });
    }
}
