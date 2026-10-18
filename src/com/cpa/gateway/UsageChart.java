package com.cpa.gateway;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 迷你柱状图：近 N 天请求量。
 *
 * 自绘而不是引图表库：一是风格要跟着主题走（柱子直接用 Ui.ACCENT，切主题就换色），
 * 二是这个软件到现在为止**没有任何第三方依赖**（连 androidx 都没有），为了几根柱子
 * 把 AAR、资源合并、Kotlin 运行时引进来不划算。
 */
final class UsageChart extends android.view.View {

    private final List<String> labels = new ArrayList<>();
    private final List<Long> values = new ArrayList<>();
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    /**
     * 复用的矩形。以前在 onDraw 的循环里 `new RectF(...)`，每帧每根柱子一个 ——
     * 滚动或面板刷新时就是持续的小对象分配，GC 一抖就是掉帧。
     * RectF 本来就是可变对象，一个字段够用。
     */
    private final RectF rect = new RectF();
    private long max = 1;

    UsageChart(Context c, LinkedHashMap<String, Long> data) {
        super(c);
        for (Map.Entry<String, Long> e : data.entrySet()) {
            labels.add(e.getKey());
            values.add(e.getValue() == null ? 0L : e.getValue());
            max = Math.max(max, values.get(values.size() - 1));
        }
        label.setColor(Ui.MUTED);
        label.setTextSize(Ui.dp(c, 10));
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), Ui.dp(getContext(), 118));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        int n = values.size();
        if (n == 0 || w <= 0) return;
        Context c = getContext();
        bar.setColor(Ui.ACCENT);
        label.setColor(Ui.MUTED);

        float slot = (float) w / n;
        float barW = slot * 0.52f;
        float bottom = h - Ui.dp(c, 18);
        float top = Ui.dp(c, 14);
        label.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < n; i++) {
            float cx = slot * i + slot / 2f;
            long v = values.get(i);
            float bh = max <= 0 ? 0 : (bottom - top) * ((float) v / max);
            rect.set(cx - barW / 2f, bottom - bh, cx + barW / 2f, bottom);
            float radius = Ui.dp(c, 4);
            canvas.drawRoundRect(rect, radius, radius, bar);
            canvas.drawText(String.valueOf(v), cx, bottom - bh - Ui.dp(c, 4), label);
            canvas.drawText(labels.get(i), cx, h - Ui.dp(c, 4), label);
        }
    }
}
