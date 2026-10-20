package com.cpa.gateway;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.cpa.gateway.core.Config;
import com.cpa.gateway.core.Gateway;

import java.util.ArrayList;
import java.util.List;

/** Home for the accounts that cannot serve right now, so the main list stays clean. */
public class UnusableActivity extends Activity {

    /** 从重新登录页回来后重建列表，避免还显示旧状态。 */
    private boolean dirty;

    @Override
    protected void onResume() {
        super.onResume();
        if (dirty) {
            dirty = false;
            recreate();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.darkSystemBars(this);
        Gateway.init(this);

        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Color.TRANSPARENT);
        root.setPadding(Ui.dp(this, 16), Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 16));

        TextView back = Ui.barButton(this, "‹ 返回");
        back.setOnClickListener(v -> finish());
        root.addView(Ui.appBar(this, back, "暂不可用的账号", null));

        List<Config.Provider> bad = new ArrayList<>();
        for (Config.Provider p : Gateway.config().providers) {
            if (MainActivity.unusableReason(p) != null) bad.add(p);
        }

        LinearLayout list = Ui.column(this);
        if (bad.isEmpty()) {
            list.addView(Ui.label(this, "现在没有不可用的账号。"));
        }
        for (Config.Provider p : bad) {
            LinearLayout row = Ui.row(this);
            row.setBackground(GlassDrawable.panel(Ui.dp(this, 20)));
            int pad = Ui.dp(this, 14);
            row.setPadding(pad, pad, pad, pad);
            TextView avatar = Ui.avatar(this, p.type);
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(Ui.dp(this, 38), Ui.dp(this, 38));
            alp.rightMargin = Ui.dp(this, 12);
            row.addView(avatar, alp);
            LinearLayout info = Ui.column(this);
            info.addView(Ui.oneLineName(this, p.name));
            final String reason = MainActivity.unusableReason(p);
            TextView why = Ui.label(this, reason);
            why.setSingleLine(false);
            info.addView(why, Ui.lp(this, 3));
            row.addView(info, Ui.weight(this, 1f, 0));

            /* 直接给下一步动作：复制原因给支持 / 凭证类问题直接重新登录。
               以前只能点进详情页再自己找，处理路径太长。 */
            LinearLayout ops = Ui.column(this);
            TextView copy = Ui.ghostButton(this, "复制");
            copy.setOnClickListener(v -> copyToClipboard(p.name + "\n" + reason));
            ops.addView(copy);
            if (looksLikeLoginProblem(reason)) {
                final Intent login = MainActivity.vendorLoginIntent(this,
                        MainActivity.vendorOf(p),
                        MainActivity.accountsOfVendor(MainActivity.vendorOf(p),
                                Gateway.config().providers));
                if (login != null) {
                    TextView relogin = Ui.accentChipButton(this, "重登");
                    relogin.setOnClickListener(v -> {
                        dirty = true;
                        try {
                            startActivity(login);
                        } catch (Throwable t) {
                            android.widget.Toast.makeText(this, "打不开登录页：" + t.getMessage(),
                                    android.widget.Toast.LENGTH_LONG).show();
                        }
                    });
                    ops.addView(relogin, Ui.lp(this, 6));
                }
            }
            row.addView(ops);

            LinearLayout.LayoutParams params = Ui.lp(this, 8);
            row.setClickable(true);
            Ui.pressable(row);
            row.setOnClickListener(v -> {
                Intent intent = new Intent(this, AccountDetailActivity.class);
                intent.putExtra(AccountDetailActivity.EXTRA_PROVIDER_ID, p.id);
                startActivity(intent);
            });
            list.addView(row, params);
        }
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(list);
        root.addView(sv, Ui.fill(this, 14));

        root.addView(Ui.label(this, "这些账号被移到本页是因为：已停用、最近调用失败、或全部模型都调不通。在详情页里「启用」或重新登录即可回到首页。"), Ui.lp(this, 8));
        // 底层强度 0.65 —— 不可用模型：列表
        setContentView(Ui.glassScaffold(this, root, 0.65f));
    }

    private static boolean looksLikeLoginProblem(String reason) {
        if (reason == null) return false;
        return reason.contains("凭证") || reason.contains("登录") || reason.contains("过期")
                || reason.contains("认证") || reason.contains("401");
    }

    private void copyToClipboard(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm == null) return;
            cm.setPrimaryClip(ClipData.newPlainText("AHYU", text));
            android.widget.Toast.makeText(this, "已复制原因", android.widget.Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            android.widget.Toast.makeText(this, "复制失败：" + t.getMessage(),
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }
}
