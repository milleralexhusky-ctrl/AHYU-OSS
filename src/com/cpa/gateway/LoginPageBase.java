package com.cpa.gateway;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.content.res.ColorStateList;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.cpa.gateway.core.Gateway;

/**
 * 所有"添加账号"页面共用的外壳。
 *
 * 以前每个登录页各自重复 30~40 行同样的东西：返回按钮、标题栏、玻璃脚手架、
 * Handler、状态提示、toast。这里收敛成一处，子类只写自己特有的部分。
 */
public abstract class LoginPageBase extends Activity {

    protected LinearLayout root;
    private TextView statusView;
    private ProgressBar statusSpinner;
    private LinearLayout statusCard;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    /** 页面标题，例如"登录 DeepSeek"。 */
    protected abstract String pageTitle();

    /** 标题下方的小字说明。 */
    protected abstract String pageSubtitle();

    /** 构建标题栏以下的内容。 */
    protected abstract void buildBody(LinearLayout root);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.darkSystemBars(this);
        Gateway.init(this);

        root = Ui.column(this);
        root.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        root.setPadding(Ui.dp(this, 16), Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 16));

        TextView back = Ui.barButton(this, "‹ 返回");
        back.setOnClickListener(v -> finish());
        root.addView(Ui.appBar(this, back, pageTitle(), pageSubtitle()));

        // 状态条放在标题栏正下方：它是"当前进展"，最该被一眼看到
        LinearLayout statusRow = Ui.row(this);
        statusSpinner = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        statusSpinner.setIndeterminate(true);
        try {
            statusSpinner.setIndeterminateTintList(ColorStateList.valueOf(Ui.ACCENT));
        } catch (Throwable ignored) {
        }
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(Ui.dp(this, 18), Ui.dp(this, 18));
        sp.rightMargin = Ui.dp(this, 10);
        statusRow.addView(statusSpinner, sp);
        statusView = Ui.label(this, "");
        statusRow.addView(statusView, Ui.weight(this, 1f, 0));
        statusCard = Ui.card(this, statusRow);
        root.addView(statusCard, Ui.lp(this, 14));

        buildBody(root);
        // 底层强度 0.50 —— 登录页基类：表单
        setContentView(Ui.glassScaffold(this, root, 0.50f));
        // 按用户要求放开截屏：登录页也不再禁。
        // 这里原本是为了 token / 二维码不进"最近任务"缩略图，
        // 但代价是用户没法截图反馈登录问题——实际使用中后者更要紧。
        // 想改回：把这行换回 Ui.secure(this)。
        // Ui.secure(this);
        onReady();
    }

    /** 页面搭好后的初始化（子类可覆写）。 */
    protected void onReady() {
    }

    /* ------------------------------------------------------------ 公共工具 */

    /** 更新状态条文字。 */
    protected void setStatus(final String text) {
        setStatus(text, false);
    }

    /**
     * 更新状态条。busy=true 时显示转圈，表示正在等待上游。
     * 传空串则整条隐藏。
     */
    protected void setStatus(final String text, final boolean busy) {
        uiHandler.post(() -> {
            if (statusView == null) return;
            boolean empty = text == null || text.isEmpty();
            if (statusCard != null) statusCard.setVisibility(empty ? TextView.GONE : TextView.VISIBLE);
            if (statusSpinner != null) statusSpinner.setVisibility(busy ? TextView.VISIBLE : TextView.GONE);
            if (!empty) statusView.setText(text);
        });
    }

    /** 只改文字，不改变可见性。 */
    protected void setHint(final String text) {
        setStatus(text, false);
    }

    protected void toast(final String text) {
        uiHandler.post(() -> Toast.makeText(this, text, Toast.LENGTH_LONG).show());
    }

    /** 回到主界面。 */
    protected void doneAndFinish(String message) {
        uiHandler.post(() -> {
            if (message != null && !message.isEmpty()) {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            }
            finish();
        });
    }

    protected Handler ui() {
        return uiHandler;
    }

    /** 在主线程执行。 */
    protected void post(Runnable r) {
        uiHandler.post(r);
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
