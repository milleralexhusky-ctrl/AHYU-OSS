package com.cpa.gateway;

import com.cpa.gateway.core.Threads;
import android.app.Activity;
import android.content.Intent;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.Switch;
import android.widget.Toast;

import com.cpa.gateway.core.Config;
import com.cpa.gateway.core.Gateway;
import com.cpa.gateway.core.Log;
import com.cpa.gateway.core.Upstreams;
import com.cpa.gateway.core.WorkBuddy;

import org.json.JSONObject;

/** The account editor, shared by the home list and the account detail page. */
final class ProviderEditor {

    static final int REQ_FILE = 9101;
    static final int REQ_WB_LOGIN = 9102;

    private static EditText pendingCreds;
    private static android.app.Dialog dialog;
    private static Activity host;

    private ProviderEditor() {}

    /**
     * 清掉静态引用。
     *
     * 为什么必须做：这三个字段（EditText / Dialog / Activity）都会间接持有 Activity 的 Context，
     * 而它们是**静态**的——只要不清，被持有的 Activity 就永远不会被回收（野引用泄漏）。
     *
     * 以前这里只把字段置 null，既没摘 dismissListener，也没有任何 Activity 在 onDestroy 里
     * 主动调用它，唯一的触发点是对话框自己的 dismiss —— Activity 非正常销毁时不一定触发。
     *
     * 现在：① 摘掉监听器再 dismiss（避免 dismiss 里回头再调 release 造成重入）；
     *       ② 三个字段全部置 null；
     *       ③ 由调用方 Activity 的 onDestroy 主动调（见 MainActivity / AccountsActivity）。
     *
     * 注意：跳 startActivityForResult 去授权页时 Activity **不会** onDestroy，
     * 所以"去登录页→回来填凭据"这条流程不受影响（pendingCreds 在回来时仍然有效）。
     */
    static synchronized void release() {
        android.app.Dialog d = dialog;
        dialog = null;                 // 先置空，避免 dismiss 触发监听器时又进来
        if (d != null) {
            try {
                d.setOnDismissListener(null);
            } catch (Throwable ignored) {
            }
            try {
                if (d.isShowing()) d.dismiss();
            } catch (Throwable ignored) {
            }
        }
        pendingCreds = null;
        host = null;
    }

    static void show(final Activity a, final Config.Provider p, final boolean isNew, final Runnable onDone) {
        host = a;
        LinearLayout box = Ui.column(a);
        int pad = Ui.dp(a, 16);
        box.setPadding(pad, pad, pad, pad);

        final EditText name = Ui.field(a, "名称", p.name, false);
        box.addView(Ui.label(a, "名称"));
        box.addView(name, Ui.lp(a, 6));

        final Spinner type = new Spinner(a);
        // 下拉里列出**全部** 12 种类型。
        //
        // 原来只有 4 项（workbuddy/openai/anthropic/gemini），而 Spinner 默认落在第 0 项。
        // 编辑一个 codex/claude/loomy/... 账号时，选中循环找不到匹配项就保持第 0 项，
        // 一按「保存」就把 type 静默改写成 workbuddy —— 账号类型被改、上游调用全错，
        // 而界面上完全看不出来。所以列表必须覆盖 Config 里真实存在的每一种类型。
        final java.util.List<String> ORDER = new java.util.ArrayList<>();
        for (String t : new String[]{Config.TYPE_OPENAI, Config.TYPE_ANTHROPIC, Config.TYPE_GEMINI,
                Config.TYPE_WORKBUDDY, Config.TYPE_CODEX, Config.TYPE_CLAUDE, Config.TYPE_LOOMY,
                Config.TYPE_TRAE, Config.TYPE_ANTIGRAVITY, Config.TYPE_KIMI,
                Config.TYPE_DEEPSEEK, Config.TYPE_DOUBAO, Config.TYPE_XAI,
                Config.TYPE_CLINE, Config.TYPE_KILO, Config.TYPE_OVH}) {
            if (!ORDER.contains(t)) ORDER.add(t);
        }
        // 账号当前的类型若不在已知清单里（旧版本或手改的文件），也补进去，绝不让它被挤到第 0 项
        if (p.type != null && !p.type.isEmpty() && !ORDER.contains(p.type)) ORDER.add(p.type);

        String[] labels = new String[ORDER.size()];
        for (int i = 0; i < ORDER.size(); i++) labels[i] = Ui.typeLabel(ORDER.get(i)) + "|" + ORDER.get(i);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(a,
                android.R.layout.simple_spinner_dropdown_item, labels);
        type.setAdapter(adapter);
        int initial = 0;
        for (int i = 0; i < adapter.getCount(); i++) {
            if (adapter.getItem(i).endsWith("|" + p.type)) {
                initial = i;
                break;
            }
        }
        type.setSelection(initial);
        final int initialSelection = initial;
        box.addView(Ui.label(a, "类型"), Ui.lp(a, 12));
        box.addView(type);

        final EditText baseUrl = Ui.field(a, "https://api.example.com/v1", p.baseUrl, false);
        box.addView(Ui.label(a, "服务地址（根地址、/v1、/v1/messages 都行；"
                + "Claude Code 文档给的那个地址可直接粘贴；WorkBuddy / ChatGPT 账号可留空）"), Ui.lp(a, 12));
        box.addView(baseUrl);

        final EditText apiKey = Ui.field(a, "服务商给的 Key（填过的也当 User-Agent 用）", p.apiKey, false);
        box.addView(Ui.label(a, "密钥（控制台里的 API Key）"), Ui.lp(a, 12));
        box.addView(apiKey);

        final EditText creds = Ui.field(a, "账号凭据 JSON（登录后自动写入，一般不用手填）", p.credentials, true);
        box.addView(Ui.label(a, "账号凭据"), Ui.lp(a, 12));
        box.addView(creds);

        // 打开编辑框时的清单，用来区分"用户手填的"和"上游目录里的"
        final java.util.List<String> beforeModels = new java.util.ArrayList<>(p.models);
        final java.util.List<String> prevManual = new java.util.ArrayList<>(p.manualModels);
        // 这一次点「自动获取」拉回来的模型。它们来自上游，保存时不能算成手填，
        // 否则上游以后把这些模型下架了，我们还会当成"用户指定的"一直留着。
        final java.util.Set<String> fetchedNow = new java.util.HashSet<>();
        final EditText models = Ui.field(a, "模型名，逗号分隔；可点下面按钮自动获取", join(p.models), true);
        box.addView(Ui.label(a, "模型"), Ui.lp(a, 12));
        box.addView(models);

        final Switch enabled = Ui.toggle(a, "启用", p.enabled);
        box.addView(enabled, Ui.lp(a, 12));

        // 限额与能力（0 ＝ 不限制）。
        // 这些值由 core/Limits 在每次真正发上游之前预检：超了就换下一个账号，
        // 而不是把请求发出去撞上游的 429。图片能力与上下文上限是硬过滤。
        box.addView(Ui.label(a, "限额与能力（0 ＝ 不限制）"), Ui.lp(a, 18));
        box.addView(Ui.label(a, "现在：" + com.cpa.gateway.core.Limits.describe(p)), Ui.lp(a, 4));
        final EditText rpm = numField(a, box, "每分钟请求数", p.rpm);
        final EditText rpd = numField(a, box, "每天请求数", p.rpd);
        final EditText conc = numField(a, box, "同时最多几个请求", p.maxConcurrent);
        final EditText tpm = numField(a, box, "每分钟 token", p.tpm);
        final EditText tpd = numField(a, box, "每天 token", p.tpd);
        final EditText ctx = numField(a, box, "上下文 token 上限（超过就不发给它）", p.contextLimit);
        final Switch vision = Ui.toggle(a, "支持图片输入", p.vision);
        box.addView(vision, Ui.lp(a, 12));

        android.widget.TextView fetch = Ui.outlineButton(a, "自动获取模型列表");
        fetch.setOnClickListener(v -> {
            final String t = String.valueOf(type.getSelectedItem()).split("\\|")[1];
            final String b = baseUrl.getText().toString().trim();
            final String k = apiKey.getText().toString().trim();
            final String c = creds.getText().toString().trim();
            fetch.setEnabled(false);
            Threads.io(() -> {
                String got = null;
                String why = null;
                try {
                    Config.Provider tmp = new Config.Provider();
                    tmp.type = t;
                    tmp.baseUrl = b;
                    tmp.apiKey = k;
                    tmp.credentials = c;
                    got = join(Upstreams.fetchModels(tmp));
                } catch (Exception e) {
                    why = String.valueOf(e.getMessage());
                }
                final String ok = got;
                final String err = why;
                a.runOnUiThread(() -> {
                    fetch.setEnabled(true);
                    if (ok != null) {
                        // 合并而不是覆盖：手打在框里的模型不能被拉取结果冲掉
                        java.util.List<String> merged = new java.util.ArrayList<>();
                        for (String m : models.getText().toString().split("[,，\\s]+")) {
                            String keep = m.trim();
                            if (!keep.isEmpty() && !merged.contains(keep)) merged.add(keep);
                        }
                        int kept = merged.size();
                        for (String m : ok.split("[,，\\s]+")) {
                            String add = m.trim();
                            if (add.isEmpty()) continue;
                            fetchedNow.add(add);
                            if (!merged.contains(add)) merged.add(add);
                        }
                        models.setText(join(merged));
                        if (kept > 0) {
                            android.widget.Toast.makeText(a,
                                    "拉到 " + fetchedNow.size() + " 个模型，"
                                            + "原有 " + kept + " 个已保留（点「保存」生效）",
                                    android.widget.Toast.LENGTH_LONG).show();
                        }
                        toast(a, "拿到 " + ok.split(",").length + " 个模型");
                    } else {
                        toast(a, "获取失败：" + err);
                    }
                });
            });
        });
        box.addView(fetch, Ui.lp(a, 14));

        if (Config.TYPE_WORKBUDDY.equals(p.type)) {
            android.widget.TextView login = Ui.outlineButton(a, "重新登录 WorkBuddy（微信）");
            login.setOnClickListener(v -> {
                pendingCreds = creds;
                try {
                    Intent intent = new Intent(a, WorkBuddyLoginActivity.class);
                    intent.putExtra(WorkBuddyLoginActivity.EXTRA_REALM,
                            WorkBuddy.REALM_GLOBAL.equals(WorkBuddy.Account.fromJson(p.credentials).realm)
                                    ? WorkBuddy.REALM_GLOBAL : WorkBuddy.REALM_CN);
                    a.startActivityForResult(intent, REQ_WB_LOGIN);
                } catch (Throwable t) {
                    toast(a, "打开登录页失败：" + t);
                }
            });
            box.addView(login, Ui.lp(a, 10));
        }
        android.widget.TextView importFile = Ui.outlineButton(a, "从文件导入凭据");
        importFile.setOnClickListener(v -> {
            pendingCreds = creds;
            try {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                a.startActivityForResult(intent, REQ_FILE);
            } catch (Exception e) {
                toast(a, "没有可用的文件选择器");
            }
        });
        box.addView(importFile, Ui.lp(a, 10));

        if (!isNew) {
            android.widget.TextView remove = Ui.outlineButton(a, "删除这个账号");
            remove.setTextColor(Ui.RED);
            remove.setOnClickListener(v -> Ui.dialog(a, "删除账号「" + p.name + "」？",
                    Ui.label(a, "只删掉网关里的配置，不会动你手机上的原始账号。"),
                    "取消", null, "删除", () -> {
                        int removed = 0;
                        java.util.List<Config.Provider> list = Gateway.config().providers;
                        for (int i = list.size() - 1; i >= 0; i--) {
                            Config.Provider cand = list.get(i);
                            boolean sameId = cand.id != null && !cand.id.isEmpty() && cand.id.equals(p.id);
                            boolean sameName = cand.name != null && cand.name.equals(p.name) && cand.type.equals(p.type);
                            if (sameId || sameName) {
                                list.remove(i);
                                removed++;
                            }
                        }
                        Gateway.save(a, true);
                        // verify against what is actually on disk, so a silent no-op cannot hide
                        int left = Gateway.reload(a).providers.size();
                        Log.i("删除账号 " + p.name + "：移除 " + removed + " 条，剩余 " + left + " 个账号");
                        if (removed == 0) {
                            android.widget.Toast.makeText(a, "没找到这个账号，可能已经被删过了", android.widget.Toast.LENGTH_LONG).show();
                        }
                        if (dialog != null) dialog.dismiss();
                        if (onDone != null) onDone.run();
                    }));
            box.addView(remove, Ui.lp(a, 18));
        }

        dialog = Ui.dialog(a, isNew ? "添加账号" : "编辑账号", Ui.scroller(a, box), "取消", null, "保存", () -> {
            p.name = name.getText().toString().trim();
            String pickedType = String.valueOf(type.getSelectedItem());
            int bar = pickedType.lastIndexOf('|');
            pickedType = bar >= 0 ? pickedType.substring(bar + 1) : p.type;
            // 只有用户真的动了下拉框才改类型。原来是无条件按当前选项覆盖，
            // 一旦下拉里没有账号原本的类型，保存就等于把类型改错。
            if (type.getSelectedItemPosition() != initialSelection && !pickedType.equals(p.type)) {
                Log.i("账号 \"" + p.name + "\" 类型已改：" + p.type + " → " + pickedType);
                p.type = pickedType;
            }
            if (p.name.isEmpty()) p.name = Ui.typeLabel(p.type);
            p.baseUrl = baseUrl.getText().toString().trim();
            if (cleartextBlocked(p.baseUrl)) {
                /* 不拦住保存，只在此时把话说清楚。
                 *
                 * network_security_config 只给回环开了明文口子，别的 http:// 会在请求时被系统
                 * 直接掐掉，而 Http.call 抛出来的只是一句笼统的 IOException——用户看到的是
                 * "连不上"，根本猜不到是安全策略拦的。保存这一步是唯一能让原因可见的时机。 */
                toast(a, "注意：该地址是明文 HTTP，系统只允许 http://127.0.0.1 与 http://localhost，"
                        + "保存后请求会被拒绝。请改用 https://");
            }
            p.apiKey = apiKey.getText().toString().trim();
            p.credentials = creds.getText().toString().trim();
            p.enabled = enabled.isChecked();
            p.rpm = intOf(rpm);
            p.rpd = intOf(rpd);
            p.maxConcurrent = intOf(conc);
            p.tpm = intOf(tpm);
            p.tpd = intOf(tpd);
            p.contextLimit = intOf(ctx);
            p.vision = vision.isChecked();
            Log.i("账号 \"" + p.name + "\" 限额：rpm=" + p.rpm + " rpd=" + p.rpd
                    + " 并发=" + p.maxConcurrent + " tpm=" + p.tpm + " tpd=" + p.tpd
                    + " 上下文=" + p.contextLimit + " 图片=" + p.vision);
            java.util.List<String> typed = new java.util.ArrayList<>();
            for (String m : models.getText().toString().split("[,，\\s]+")) {
                String t = m.trim();
                if (!t.isEmpty() && !typed.contains(t)) typed.add(t);
            }
            p.models.clear();
            p.models.addAll(typed);
            // 记住哪些是手填的：上游每次刷新整表覆盖，不单独记就会被抹掉。
            p.manualModels.clear();
            for (String m : typed) {
                if (fetchedNow.contains(m)) continue;          // 上游拉来的，归上游管
                if (!beforeModels.contains(m) || prevManual.contains(m)) p.manualModels.add(m);
            }
            if (!p.manualModels.isEmpty()) {
                Log.i("账号 \"" + p.name + "\" 手动模型 " + p.manualModels.size() + " 个，刷新时不会被覆盖");
            }
            if (p.id == null || p.id.isEmpty()) p.id = "p" + System.currentTimeMillis();
            if (isNew) {
                Config.Provider dup = sameAccountAs(p);
                if (dup != null) {
                    dup.name = p.name;
                    dup.baseUrl = p.baseUrl;
                    dup.apiKey = p.apiKey;
                    dup.enabled = p.enabled;
                    dup.credentials = p.credentials;
                    dup.rpm = p.rpm;
                    dup.rpd = p.rpd;
                    dup.tpm = p.tpm;
                    dup.tpd = p.tpd;
                    dup.maxConcurrent = p.maxConcurrent;
                    dup.vision = p.vision;
                    dup.contextLimit = p.contextLimit;
                    if (!p.models.isEmpty()) {
                        dup.models.clear();
                        dup.models.addAll(p.models);
                        dup.manualModels.clear();
                        dup.manualModels.addAll(p.manualModels);
                    }
                    toast(a, "同一账号已存在，已就地更新");
                } else {
                    Gateway.config().providers.add(p);
                }
            }
            Gateway.save(a);
            if (onDone != null) onDone.run();
        });
        // 编辑账号对话框里有凭据字段，禁截屏（只对这一类加）
        if (dialog != null) Ui.secureDialog(dialog);
        try {
            dialog.setOnDismissListener(d -> release());   // 关闭即清静态引用
        } catch (Throwable ignored) {
        }
    }

    static boolean handleResult(Activity a, int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_WB_LOGIN) {
            if (data != null) {
                String credsJson = data.getStringExtra(WorkBuddyLoginActivity.RESULT_CREDENTIALS);
                String err = data.getStringExtra("error");
                if (credsJson != null) {
                    if (pendingCreds != null) pendingCreds.setText(credsJson);
                    toast(a, "授权成功，凭据已填入，记得保存");
                } else if (err != null) {
                    toast(a, "授权未完成：" + err);
                }
            }
            return true;
        }
        if (requestCode == REQ_FILE) {
            if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return true;
            try {
                java.io.InputStream in = a.getContentResolver().openInputStream(data.getData());
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                in.close();
                String text = new String(bos.toByteArray(), "UTF-8").trim();
                if (text.isEmpty()) {
                    toast(a, "文件是空的");
                    return true;
                }
                if (pendingCreds != null) {
                    pendingCreds.setText(text);
                    toast(a, "已导入凭据，点保存生效");
                    return true;
                }
                // 主页「添加账号 → 导入」直接走这里（此时本来就没打开 ProviderEditor）：
                // WorkBuddy.registerProvider 内部会发起网络请求拉取模型清单，必须在后台线程跑，
                // 否则在 UI 线程直接调会触发 NetworkOnMainThreadException 崩溃。
                toast(a, "正在导入账号并拉取模型…");
                final String rawJson = text;
                Threads.io(() -> {
                    try {
                        JSONObject res = WorkBuddy.registerProvider(new JSONObject(rawJson));
                        a.runOnUiThread(() -> {
                            toast(a, "已加入账号：" + res.optString("provider")
                                    + "（" + res.optInt("models") + " 个模型）");
                            if (a instanceof MainActivity) ((MainActivity) a).refreshStatus();
                        });
                    } catch (Throwable t) {
                        a.runOnUiThread(() -> toast(a, "导入失败：" + t.getMessage()));
                    }
                });
            } catch (Exception e) {
                toast(a, "导入失败：" + e);
            }
            return true;
        }
        return false;
    }

    private static Config.Provider sameAccountAs(Config.Provider candidate) {
        try {
            String uid = new JSONObject(candidate.credentials.isEmpty() ? "{}" : candidate.credentials).optString("uid", "");
            if (uid.isEmpty()) return null;
            for (Config.Provider q : Gateway.config().providers) {
                if (q == candidate || !q.type.equals(candidate.type)) continue;
                String quid = new JSONObject(q.credentials.isEmpty() ? "{}" : q.credentials).optString("uid", "");
                if (uid.equals(quid)) return q;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 数字输入框：只能敲数字，空或非法一律当 0（＝ 不限制）。 */
    private static EditText numField(Activity a, LinearLayout box, String label, int value) {
        EditText f = Ui.field(a, "0 = 不限制", String.valueOf(Math.max(0, value)), false);
        f.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        box.addView(Ui.label(a, label), Ui.lp(a, 12));
        box.addView(f);
        return f;
    }

    /** 读数字框：非数字、空、负数统统按 0（不限制）处理，不让手滑把人拦死。 */
    private static int intOf(EditText f) {
        try {
            String s = f.getText().toString().replaceAll("[^0-9]", "");
            if (s.isEmpty()) return 0;
            return (int) Math.min(Long.parseLong(s), 1_000_000_000L);
        } catch (Exception e) {
            return 0;
        }
    }

    private static String join(java.util.List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    /**
     * 该 baseUrl 是否是一段注定被系统拦掉的明文地址。
     *
     * 判据与 res/xml/network_security_config.xml 保持一致：只有 127.0.0.1 / localhost / ::1
     * 允许 cleartext，其余一律要求 TLS。空串（用内置默认地址）不算。
     */
    static boolean cleartextBlocked(String url) {
        if (url == null) return false;
        String u = url.trim().toLowerCase(java.util.Locale.US);
        if (u.isEmpty() || u.startsWith("https://")) return false;
        if (!u.startsWith("http://")) return false;
        String rest = u.substring("http://".length());
        int slash = rest.indexOf('/');
        String host = slash >= 0 ? rest.substring(0, slash) : rest;
        int at = host.lastIndexOf('@');
        if (at >= 0) host = host.substring(at + 1);          // 去掉 user:pass@
        int colon = host.lastIndexOf(':');
        if (colon >= 0 && host.indexOf(']') < 0) host = host.substring(0, colon);
        host = host.replace("[", "").replace("]", "");
        return !("127.0.0.1".equals(host) || "localhost".equals(host) || "::1".equals(host));
    }

    private static void toast(Activity a, String msg) {
        Toast.makeText(a, msg, Toast.LENGTH_SHORT).show();
    }
}
