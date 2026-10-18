package com.cpa.gateway;

import com.cpa.gateway.core.Threads;
import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.cpa.gateway.core.Config;
import com.cpa.gateway.core.Gateway;
import com.cpa.gateway.core.Log;
import com.cpa.gateway.core.Upstreams;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Adding an API key by hand: one key = one account. Shared by the relay login page and the plain
 * in-app browser, so "add a token" is reachable exactly where the key is shown.
 */
public final class TokenAdd {

    private TokenAdd() {}

    public static List<Config.Provider> channelsOfHost(String host) {
        List<Config.Provider> out = new ArrayList<>();
        if (host == null || host.isEmpty()) return out;
        for (Config.Provider p : Gateway.config().providers) {
            if (host.equalsIgnoreCase(MainActivity.hostOf(p.baseUrl))) out.add(p);
        }
        return out;
    }

    /** Name of the channel already using this key, or null when it is new. */
    static String duplicateOf(String key) {
        String k = key == null ? "" : key.trim();
        if (k.isEmpty()) return null;
        for (Config.Provider p : Gateway.config().providers) {
            if (p.apiKey != null && !p.apiKey.isEmpty() && p.apiKey.equals(k)) return p.name;
            if (p.credentials != null && p.credentials.contains("\"" + k + "\"")) return p.name;
        }
        return null;
    }

    static String hostOfUrl(String url) {
        if (url == null) return "";
        String h = url.replaceFirst("^https?://", "");
        int slash = h.indexOf('/');
        if (slash >= 0) h = h.substring(0, slash);
        return h.toLowerCase();
    }

    static String originOfUrl(String url) {
        String ref = url == null ? "" : url;
        String origin = ref.replaceFirst("^https?://", "https://");
        int slash = origin.indexOf('/', origin.indexOf("://") + 3);
        if (slash > 0) origin = origin.substring(0, slash);
        return origin;
    }

    /**
     * The add panel: input first, the two actions side by side, and what the site already has listed
     * underneath (only when there is something), so the main action stays prominent.
     */
    public static void show(final Activity host, final String pageUrl) {
        if (pageUrl == null || pageUrl.isEmpty()) {
            Toast.makeText(host, "先打开一个网站", Toast.LENGTH_SHORT).show();
            return;
        }
        final String siteHost = hostOfUrl(pageUrl);
        LinearLayout box = Ui.column(host);
        box.addView(Ui.label(host, "粘贴控制台里的 sk- 密钥；存一条就多一个账号，可连续存。"));

        final EditText keyField = Ui.field(host, "sk-…（粘贴密钥）", "", false);
        box.addView(keyField, Ui.lp(host, 8));
        final EditText labelField = Ui.field(host, "备注（可留空）", "", false);
        box.addView(labelField, Ui.lp(host, 6));

        final LinearLayout actions = Ui.row(host);
        final TextView saveMore = Ui.primaryButton(host, "保存并继续添加");
        final TextView viewAdded = Ui.outlineButton(host, "查看已添加");
        LinearLayout.LayoutParams a1 = Ui.weight(host, 1f, 0);
        a1.rightMargin = Ui.dp(host, 8);
        actions.addView(saveMore, a1);
        actions.addView(viewAdded, Ui.weight(host, 1f, 0));
        box.addView(actions, Ui.lp(host, 10));

        final TextView counter = Ui.label(host, "本次还没有添加");
        box.addView(counter, Ui.lp(host, 8));

        final LinearLayout existingBox = Ui.column(host);
        box.addView(existingBox, Ui.lp(host, 6));
        renderExisting(host, existingBox, siteHost);

        final android.app.Dialog dialog = Ui.dialog(host, "添加令牌", Ui.scroller(host, box),
                null, null, null, null);
        // 这个对话框里有用户粘贴的密钥，禁截屏（只对这一类加，别影响别的弹窗）
        Ui.secureDialog(dialog);
        final int[] count = {0};
        saveMore.setOnClickListener(v -> {
            String key = keyField.getText().toString().trim();
            if (key.isEmpty()) {
                Toast.makeText(host, "先粘贴一个密钥", Toast.LENGTH_SHORT).show();
                return;
            }
            String dup = duplicateOf(key);
            if (dup != null) {
                Toast.makeText(host, "这个密钥已经添加过了（" + dup + "），已自动跳过", Toast.LENGTH_LONG).show();
                return;
            }
            save(host, pageUrl, key, labelField.getText().toString().trim());
            count[0]++;
            counter.setText("本次已添加 " + count[0] + " 条");
            viewAdded.setText("查看已添加（" + count[0] + "）");
            keyField.setText("");
            labelField.setText("");
            // 面板里同步刷新"已有"清单，避免重复
            host.getWindow().getDecorView().postDelayed(
                    () -> renderExisting(host, existingBox, siteHost), 1200);
        });
        viewAdded.setOnClickListener(v -> {
            Intent list = new Intent(host, AccountsActivity.class);
            list.putExtra(AccountsActivity.EXTRA_VENDOR, siteHost);
            host.startActivity(list);
        });
    }

    private static void renderExisting(Activity host, LinearLayout box, String siteHost) {
        box.removeAllViews();
        List<Config.Provider> mine = channelsOfHost(siteHost);
        if (mine.isEmpty()) return;
        box.addView(Ui.text(host, "这个站点已有 " + mine.size() + " 条", 12.5f, Ui.MUTED, true));
        for (Config.Provider p : mine) {
            String name = p.name.replace(siteHost, "").replace("·", "").trim();
            if (name.isEmpty()) name = p.name;
            box.addView(Ui.label(host, "· " + name + "（" + Math.max(0, p.models.size() - p.badModels.size())
                    + " 个模型）"));
        }
    }

    /** Creates the channel and fetches its models off the main thread. Returns the site name. */
    public static String save(final Activity host, final String pageUrl, final String key, final String label) {
        final String origin = originOfUrl(pageUrl);
        final String hostName = hostOfUrl(pageUrl);
        final String base = pageUrl.contains("/v1") ? pageUrl.substring(0, pageUrl.indexOf("/v1") + 3) : origin;
        final Handler ui = new Handler(Looper.getMainLooper());
        Threads.io(() -> {
            try {
                Config.Provider p = new Config.Provider();
                p.type = Config.TYPE_OPENAI;
                p.id = "tok" + Math.abs(System.nanoTime() % 1000000);
                String site = "";
                try {
                    site = WebLoginActivity.siteName(origin, null);
                } catch (Throwable ignored) {
                }
                String prefix = site.isEmpty() ? hostName : site;
                p.name = prefix + (label.isEmpty() ? "" : " · " + label);
                if (nameTaken(p.name)) p.name = p.name + " " + Math.abs(System.nanoTime() % 100);
                p.baseUrl = base;
                p.apiKey = key.startsWith("sk-") ? key : "sk-" + key;
                JSONObject c = new JSONObject();
                c.put("token", p.apiKey);
                c.put("panel", "manual");
                if (!site.isEmpty()) c.put("site_name", site);
                c.put("site_host", hostName);
                p.credentials = c.toString();
                p.enabled = true;
                Gateway.config().providers.add(p);
                int models = 0;
                try {
                    List<String> list = Upstreams.fetchModels(p);
                    p.setModels(list);
                    models = p.models.size();
                } catch (Exception e) {
                    Log.w("令牌通道拉模型失败：" + e);
                }
                Gateway.persist();
                final int count = models;
                final String who = p.name;
                Log.i("手动令牌已保存：" + who + " 模型=" + count);
                ui.post(() -> Toast.makeText(host,
                        count > 0 ? ("已添加 " + who + "（" + count + " 个模型）")
                                : ("已添加 " + who + "，但没拉到模型（密钥或地址可能不对）"),
                        Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                Log.w("保存令牌失败：" + e);
                ui.post(() -> Toast.makeText(host, "保存失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
        return hostName;
    }

    private static boolean nameTaken(String name) {
        for (Config.Provider p : Gateway.config().providers) {
            if (p.name != null && p.name.equals(name)) return true;
        }
        return false;
    }
}
