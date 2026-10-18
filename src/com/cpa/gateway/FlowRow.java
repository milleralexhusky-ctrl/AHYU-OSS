package com.cpa.gateway;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/**
 * 会自动换行的横向容器。
 *
 * 为什么需要它：标签行用的是 LinearLayout(HORIZONTAL)，而 LinearLayout
 * **不换行**——标签一多（比如 gpt-5.5 有上下文/输出/多模态/工具/推理/PDF/联网
 * 七个）就会溢出卡片右边缘被裁掉，真机截图里"联网"只剩一半。
 *
 * 这里按可用宽度逐行摆放：放不下就换到下一行。只在 onMeasure/onLayout 里
 * 做一次遍历，没有额外嵌套 View，对列表滚动性能没有影响。
 */
final class FlowRow extends ViewGroup {

    private final int hGap;
    private final int vGap;

    FlowRow(Context c, int hGapPx) {
        super(c);
        this.hGap = hGapPx;
        this.vGap = hGapPx;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int avail = widthMode == MeasureSpec.UNSPECIFIED ? Integer.MAX_VALUE : widthSize;

        int x = 0;                 // 当前行已用宽度
        int lineH = 0;             // 当前行高度
        int totalH = 0;            // 总高度
        int maxW = 0;

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == View.GONE) continue;
            measureChild(child, widthMeasureSpec, heightMeasureSpec);
            int cw = child.getMeasuredWidth();
            int ch = child.getMeasuredHeight();
            int need = (x == 0 ? cw : x + hGap + cw);
            if (need > avail && x > 0) {
                // 换行（加上行间距 vGap）
                maxW = Math.max(maxW, x);
                totalH += lineH + vGap;
                x = cw;
                lineH = ch;
            } else {
                x = need;
                lineH = Math.max(lineH, ch);
            }
        }
        totalH += lineH;
        maxW = Math.max(maxW, x);

        int finalW = widthMode == MeasureSpec.UNSPECIFIED ? maxW : widthSize;
        setMeasuredDimension(finalW, totalH);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int avail = r - l;
        int x = 0;
        int y = 0;
        int lineH = 0;

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == View.GONE) continue;
            int cw = child.getMeasuredWidth();
            int ch = child.getMeasuredHeight();
            int need = (x == 0 ? cw : x + hGap + cw);
            if (need > avail && x > 0) {
                y += lineH + vGap;
                x = 0;
                lineH = 0;
            }
            if (x > 0) x += hGap;
            child.layout(x, y, x + cw, y + ch);
            x += cw;
            lineH = Math.max(lineH, ch);
        }
    }
}
