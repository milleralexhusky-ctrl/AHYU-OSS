package com.cpa.gateway;

import android.app.Activity;
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
            TextView why = Ui.label(this, MainActivity.unusableReason(p));
            why.setSingleLine(false);
            info.addView(why, Ui.lp(this, 3));
            row.addView(info, Ui.weight(this, 1f, 0));
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
        setContentView(Ui.glassScaffold(this, root));
    }
}
