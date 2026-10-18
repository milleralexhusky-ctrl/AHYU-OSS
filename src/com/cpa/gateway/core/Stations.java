package com.cpa.gateway.core;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The built-in relay-station list, plus a reachability check.
 *
 * A station that stopped answering should not keep a working-looking button on screen, so each entry
 * is probed (its own /api/status) and dead ones are shown dimmed and non-clickable. Results are
 * cached, so opening the page does not hammer a dozen hosts.
 */
public final class Stations {

    private Stations() {}

    public static final String[][] LIST = {
            {"Any Router", "https://anyrouter.top"},
            {"Agent Router", "https://agentrouter.org"},
            {"ZeroCat", "https://zero.cat"},
            {"快跑API", "https://kuaipao.ai"},
            {"可萌中转", "https://api456.me"},
            {"CM-API", "https://api.chengmo.cc.cd"},
            {"维云模型", "https://vsllm.cc"},
            {"Huan API", "https://ai.huan666.de"},
            {"Token能量站", "https://factory.pub"},
            {"PM-API", "https://xn--wnup5g6so4wn.de5.net"},
            {"Pomelo", "https://api.67.si"},
            {"糯喵喵", "https://ai.yangwue.me"},
            {"hkai", "https://share.hkai25.top"},
            {"BER分", "https://ai.berf1.cn"},
            {"onomeo", "https://onomeo.com"},
            {"BlockRun", "https://blockrun.ai"}};

    static final long CACHE_SECONDS = 12 * 3600L;

    /** url -> alive. Cached in the config so the page can open instantly. */
    public static Map<String, Boolean> cached() {
        Map<String, Boolean> out = new LinkedHashMap<>();
        Config cfg = Gateway.config();
        long now = System.currentTimeMillis() / 1000;
        boolean stale = now - cfg.stationsCheckedAt > CACHE_SECONDS;
        for (String[] s : LIST) {
            String url = s[1];
            boolean known = cfg.deadStations.contains(url) || cfg.aliveStations.contains(url);
            if (stale || !known) out.put(url, null);
            else out.put(url, cfg.aliveStations.contains(url));
        }
        return out;
    }

    /** Community-maintained list of free relay stations; new entries are discovered from here. */
    static final String[] DISCOVERY_SOURCES = {
            "https://raw.githubusercontent.com/kirito8/free-newapi/main/README.md",
            "https://raw.githubusercontent.com/kirito8/free-newapi/master/README.md"};

    /** How many consecutive failed checks before a station is retired. */
    static final int FAILS_BEFORE_RETIRE = 2;

    static int failCount(String url) {
        for (String entry : Gateway.config().stationFailures) {
            String[] parts = entry.split("\\|", 2);
            if (parts.length == 2 && parts[0].equals(url)) {
                try {
                    return Integer.parseInt(parts[1]);
                } catch (Exception e) {
                    return 0;
                }
            }
        }
        return 0;
    }

    static void setFailCount(String url, int count) {
        Config cfg = Gateway.config();
        cfg.stationFailures.removeIf(e -> e.startsWith(url + "|"));
        if (count > 0) cfg.stationFailures.add(url + "|" + count);
    }

    /** Records one failed probe; only retires the station after repeated failures. */
    static void noteFailure(Config cfg, String name, String url) {
        int fails = failCount(url) + 1;
        setFailCount(url, fails);
        if (fails >= FAILS_BEFORE_RETIRE) {
            cfg.removedStations.add(url);
            Log.w("中转站连续 " + fails + " 次探活失败，移除：" + name + " " + url);
        } else {
            Log.w("中转站探活失败(" + fails + "/" + FAILS_BEFORE_RETIRE + ")，暂留观察：" + name);
        }
    }

    /** True when the phone actually has a usable connection; otherwise nothing is judged. */
    public static boolean online() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    Gateway.context().getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            android.net.Network n = cm.getActiveNetwork();
            if (n == null) return false;
            android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            return caps != null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Throwable t) {
            return true;
        }
    }

    /** Keeps alive stations, drops repeatedly dead ones, and pulls in new panels. */
    public static Map<String, Boolean> syncStations() {
        Config cfg = Gateway.config();
        if (!online()) {
            // offline says nothing about the stations: skip entirely instead of recording failures
            Log.i("当前没有网络，跳过站点探活（不会移除任何站点）");
            return new LinkedHashMap<>();
        }
        Map<String, Boolean> out = new LinkedHashMap<>();
        // 1) built-in list: alive stays, repeated failures are retired
        for (String[] st : LIST) {
            if (cfg.removedStations.contains(st[1])) continue;
            if (reachable(st[1])) {
                setFailCount(st[1], 0);
                out.put(st[1], true);
            } else {
                noteFailure(cfg, st[0], st[1]);
                if (!cfg.removedStations.contains(st[1])) out.put(st[1], true);
            }
        }
        // 2) discovered ones are re-checked the same way
        List<String> stillGood = new ArrayList<>();
        for (String entry : new ArrayList<>(cfg.discoveredStations)) {
            String[] parts = entry.split("\\|", 2);
            if (parts.length < 2) continue;
            if (reachable(parts[1])) {
                setFailCount(parts[1], 0);
                stillGood.add(entry);
                out.put(parts[1], true);
            } else {
                noteFailure(cfg, parts[0], parts[1]);
                if (!cfg.removedStations.contains(parts[1])) {
                    stillGood.add(entry);
                    out.put(parts[1], true);
                }
            }
        }
        cfg.discoveredStations.clear();
        cfg.discoveredStations.addAll(stillGood);
        // 3) look for new candidates in the community list
        int added = 0;
        for (String url : candidateStations()) {
            if (cfg.removedStations.contains(url)) continue;
            boolean known = out.containsKey(url);
            for (String[] st : LIST) if (st[1].equals(url)) known = true;
            for (String entry : cfg.discoveredStations) if (entry.endsWith("|" + url)) known = true;
            if (known) continue;
            if (isRelayPanel(url)) {
                String host = url.replaceFirst("^https?://", "").replaceAll("/.*$", "");
                cfg.discoveredStations.add(host + "|" + url);
                out.put(url, true);
                added++;
                Log.i("发现可用中转站：" + host);
            } else {
                cfg.removedStations.add(url);
            }
        }
        cfg.aliveStations.clear();
        cfg.aliveStations.addAll(out.keySet());
        cfg.deadStations.clear();
        cfg.deadStations.addAll(cfg.removedStations);
        cfg.stationsCheckedAt = System.currentTimeMillis() / 1000;
        Gateway.persistSoon();
        Log.i("站点同步完成：可用 " + out.size() + " 个，新发现 " + added + " 个，累计移除 " + cfg.removedStations.size() + " 个");
        return out;
    }

    /**
     * Pulls candidate URLs out of the community list. Any host that answers like a relay panel is
     * kept, so the format of that document does not matter.
     */
    static List<String> candidateStations() {
        List<String> out = new ArrayList<>();
        for (String src : DISCOVERY_SOURCES) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(src).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(12000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                java.io.InputStream in = conn.getInputStream();
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                in.close();
                conn.disconnect();
                String text = new String(bos.toByteArray(), "UTF-8");
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("https?://([a-zA-Z0-9.-]+\\.[a-zA-Z]{2,})").matcher(text);
                while (m.find()) {
                    String host = m.group(1).toLowerCase();
                    if (host.contains("github") || host.contains("githubusercontent")
                            || host.contains("v2ex") || host.contains("linux.do")) continue;
                    String url = "https://" + host;
                    if (!out.contains(url)) out.add(url);
                }
                Log.i("从 " + src + " 解析到 " + out.size() + " 个候选站点");
                if (!out.isEmpty()) return out;
            } catch (Exception e) {
                Log.w("清单拉取失败 " + src + "：" + e.getMessage());
            }
        }
        return out;
    }

    /** Already configured as a channel? Then it must not be offered again in the add page. */
    public static boolean alreadyAdded(String stationUrl) {
        String host = hostOf(stationUrl);
        if (host.isEmpty()) return false;
        for (Config.Provider p : Gateway.config().providers) {
            if (hostOf(p.baseUrl).equals(host)) return true;
            if (p.credentials != null && p.credentials.contains(host)) return true;
        }
        return false;
    }

    static String hostOf(String url) {
        if (url == null) return "";
        String h = url.replaceFirst("^https?://", "");
        int slash = h.indexOf('/');
        if (slash >= 0) h = h.substring(0, slash);
        return h.toLowerCase();
    }

    /** Stations worth offering: alive, and not already in the account list. */
    public static List<String[]> offerableStations() {
        List<String[]> out = new ArrayList<>();
        for (String[] st : visibleStations()) {
            if (!alreadyAdded(st[1])) out.add(st);
        }
        return out;
    }

    /** The stations to show: built-in survivors plus discovered ones. */
    public static List<String[]> visibleStations() {
        Config cfg = Gateway.config();
        List<String[]> out = new ArrayList<>();
        for (String[] st : LIST) {
            if (cfg.removedStations.contains(st[1])) continue;
            if (cfg.aliveStations.contains(st[1])) out.add(st);
        }
        for (String entry : cfg.discoveredStations) {
            String[] parts = entry.split("\\|", 2);
            if (parts.length == 2 && !cfg.removedStations.contains(parts[1])) out.add(new String[]{parts[0], parts[1]});
        }
        return out;
    }

    /** Probes every station once; returns url -> alive and remembers the outcome. */
    public static Map<String, Boolean> checkAll() {
        Map<String, Boolean> out = new LinkedHashMap<>();
        Config cfg = Gateway.config();
        List<String> alive = new ArrayList<>();
        List<String> dead = new ArrayList<>();
        for (String[] s : LIST) {
            boolean ok = reachable(s[1]);
            out.put(s[1], ok);
            if (ok) alive.add(s[1]);
            else dead.add(s[1]);
            Log.i("站点探活：" + s[0] + (ok ? " 正常" : " 不可用"));
        }
        cfg.aliveStations.clear();
        cfg.aliveStations.addAll(alive);
        cfg.deadStations.clear();
        cfg.deadStations.addAll(dead);
        cfg.stationsCheckedAt = System.currentTimeMillis() / 1000;
        Gateway.persistSoon();
        return out;
    }

    /**
     * NewAPI / OneAPI panels answer /api/status with a JSON document describing their navigation
     * modules. Official platform home pages do not, which is how they are filtered out.
     */
    static boolean isRelayPanel(String base) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(base + "/api/status").openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(6000);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            if (conn.getResponseCode() != 200) return false;
            java.io.InputStream in = conn.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            String body = new String(bos.toByteArray(), "UTF-8");
            return body.contains("HeaderNavModules") || body.contains("header_nav_modules")
                    || (body.contains("\"data\"") && body.contains("console"));
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** A station is alive if its host answers at all, even with an error page. */
    static boolean reachable(String base) {
        for (String path : new String[]{"/api/status", "/"}) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(base + path).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(5000);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                int code = conn.getResponseCode();
                if (code > 0 && code < 600) return true;
            } catch (Exception e) {
                // try the next path
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return false;
    }

    /** Kicks off a background refresh when the cache is stale. */
    public static void refreshIfStaleAsync(final Runnable whenDone) {
        boolean stale = System.currentTimeMillis() / 1000 - Gateway.config().stationsCheckedAt > CACHE_SECONDS;
        if (!stale) {
            if (whenDone != null) whenDone.run();
            return;
        }
        Threads.io(() -> {
            try {
                checkAll();
            } catch (Throwable t) {
                Log.w("站点探活失败：" + t);
            }
            if (whenDone != null) whenDone.run();
        });
    }
}
