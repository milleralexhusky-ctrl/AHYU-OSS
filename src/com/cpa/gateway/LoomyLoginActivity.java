package com.cpa.gateway;

import com.cpa.gateway.core.Threads;
import android.content.Intent;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.cpa.gateway.core.Log;
import com.cpa.gateway.core.Loomy;

/** 讯飞 Loomy：手机号 + 短信验证码，整页搞定。 */
public class LoomyLoginActivity extends LoginPageBase {

    private EditText phone;
    private EditText code;
    private TextView sendButton;
    private TextView loginButton;
    private volatile String msgid = "";

    @Override
    protected String pageTitle() {
        return "登录讯飞 Loomy";
    }

    @Override
    protected String pageSubtitle() {
        return "手机号 + 验证码 · 有免费积分";
    }

    @Override
    protected void buildBody(LinearLayout root) {
        LinearLayout card = Ui.column(this);
        card.addView(Ui.label(this, "手机号"));
        phone = Ui.field(this, "11 位手机号", "", false);
        phone.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        card.addView(phone, Ui.lp(this, 6));
        sendButton = Ui.outlineButton(this, "发送验证码");
        sendButton.setOnClickListener(v -> sendCode());
        card.addView(sendButton, Ui.lp(this, 12));
        card.addView(Ui.label(this, "验证码"), Ui.lp(this, 14));
        code = Ui.field(this, "短信里的验证码", "", false);
        code.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        card.addView(code, Ui.lp(this, 6));
        loginButton = Ui.primaryButton(this, "登录并添加账号");
        loginButton.setOnClickListener(v -> doLogin());
        card.addView(loginButton, Ui.lp(this, 16));
        root.addView(Ui.card(this, card), Ui.lp(this, 16));
        root.addView(Ui.label(this, "登录成功后会自动建通道并拉取模型列表；讯飞账号的额度是积分制。"), Ui.lp(this, 8));
    }

    @Override
    protected void onReady() {
        setHint("先填手机号点「发送验证码」");
    }

    private void sendCode() {
        final String p = phone.getText().toString().trim();
        if (p.length() < 11) {
            toast("手机号看起来不对");
            return;
        }
        sendButton.setEnabled(false);
        setStatus("正在发送验证码…");
        Threads.io(() -> {
            try {
                msgid = Loomy.sendSmsCode(p);
                setStatus("验证码已发送，请查看短信后填入");
            } catch (Exception e) {
                Log.w("loomy 发送验证码失败：" + e);
                setStatus("发送失败：" + e.getMessage());
            }
            post(() -> sendButton.setEnabled(true));
        });
    }

    private void doLogin() {
        final String p = phone.getText().toString().trim();
        final String c = code.getText().toString().trim();
        if (p.isEmpty() || c.isEmpty()) {
            toast("手机号和验证码都要填");
            return;
        }
        loginButton.setEnabled(false);
        setStatus("正在登录…");
        Threads.io(() -> {
            try {
                Loomy.Account acc = Loomy.loginBySms(p, c, msgid);
                org.json.JSONObject registered = Loomy.registerProvider(acc.toJson());
                Log.i("loomy 登录成功：" + registered.optString("provider") + "（" + registered.optInt("models") + " 个模型）");
                post(() -> {
                    Intent out = new Intent();
                    out.putExtra("credentials", acc.toJson().toString());
                    setResult(RESULT_OK, out);
                    doneAndFinish("Loomy 账号已加入");
                });
            } catch (Exception e) {
                Log.w("loomy 登录失败：" + e);
                setStatus("登录失败：" + e.getMessage());
                post(() -> loginButton.setEnabled(true));
            }
        });
    }
}
