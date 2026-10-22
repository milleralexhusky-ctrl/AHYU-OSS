package com.cpa.gateway.core;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 自动更新（4.52.0）：多源查版本 → 后台下载 → 校验签名 → 一键安装。
 *
 * <h3>为什么不能真正"无感"</h3>
 *
 * 安卓从 8.0 起不允许普通应用静默安装 APK —— 必须有系统确认框，除非
 * 应用有 root、或者是系统签名。这两样本项目都不具备（也不该去要）。
 *
 * <p>能做的是把"无感"推到系统允许的极限：
 * <ul>
 *   <li><b>检查与下载完全无感</b>：跟随看门狗每天查一次，发现新版就悄悄
 *       下到私有目录。用户不需要知道这一步发生过。</li>
 *   <li><b>安装只需点一下</b>：走系统的 {@code PackageInstaller}，弹一次
 *       "是否安装"确认。比"自己去发布页找 APK、下载、再到文件管理器里点安装"
 *       少好几个来回，而且不需要 FileProvider（不引任何依赖，保住纯 Java 零依赖）。</li>
 * </ul>
 *
 * <p>那一次确认本身其实是**安全机制**而不是麻烦：它保证没有任何东西能在
 * 用户不知情时装应用。绕过它才是危险的做法。
 *
 * <h3>多更新源：查版本和查 API 不是一回事</h3>
 *
 * 2026-10-22 用 curl 逐项实测（这是本文件这些常量为什么长这样的唯一依据）：
 *
 * <table border="1">
 *   <tr><th>目标</th><th>直连</th><th>ghfast.top</th><th>ghproxy.net</th></tr>
 *   <tr><td>{@code api.github.com}</td><td>200</td><td><b>403</b></td><td><b>403</b></td></tr>
 *   <tr><td>{@code raw.githubusercontent.com}</td><td>200</td><td>200</td><td>200</td></tr>
 *   <tr><td>release 资产（APK）</td><td>302→200</td><td>200</td><td>200</td></tr>
 * </table>
 *
 * <p>所以**查版本不能走 GitHub API** —— 镜像不代理 API，而本机的网络现状是
 * "国际站经常需要代理"（见 memory：本机 5G、无 VPN、v2rayNG 手动开）。
 * 用 API 的话，没开代理的用户自动更新会**整条静默失效**，而且不报错。
 *
 * <p>改成读仓库里的 {@code version.properties}（版本号唯一来源，本来就该读它）：
 * 三个源都通，一个不通换下一个。Release 说明改成**只在用户主动点"检查更新"时**
 * 才去 API 取一次 —— 那是前台行为，取不到就显示"无说明"，不影响能不能更新。
 *
 * <h3>三道安全闸</h3>
 *
 * <ol>
 *   <li><b>只升不降</b>：包里读出的 versionCode 必须**大于**当前，防止被诱导装回有漏洞的旧版。</li>
 *   <li><b>签名必须一致</b>：下载完校验 APK 签名与当前应用相同（证书 SHA-256 比对）。
 *       不做这一步的话，下载源一旦被劫持就等于给攻击者开后门 ——
 *       这是整个功能里最要紧的一条。三个源下到的同一版 APK sha256 完全一致
 *       （实测 {@code 16af1da1…}），说明镜像本身不改文件，风险在"源被替换"，
 *       而这正是签名比对要拦的。</li>
 *   <li><b>只从既定源下</b>：不接受外部传入的 URL（防止被当成任意下载器）。</li>
 * </ol>
 */
public final class Updater {

    private Updater() {
    }

    /* ------------------------------------------------------------ 更新源 */

    /** 公库的 GitHub 全名。 */
    private static final String REPO = "milleralexhusky-ctrl/AHYU-OSS";

    /** 默认分支（实测 {@code default_branch=main}）。 */
    private static final String BRANCH = "main";

    /**
     * 查版本用的文件：仓库里的 version.properties。
     *
     * <p>必须是这个、而不是 GitHub API —— 原因见类注释里的那张实测表
     * （API 经镜像是 403，raw 经镜像是 200）。
     */
    private static final String VERSION_FILE_URL =
            "https://raw.githubusercontent.com/" + REPO + "/" + BRANCH + "/version.properties";

    /**
     * 下载镜像前缀：{@code 前缀 + 原始 URL}，顺序即优先级。
     *
     * 直连排第一（少一跳、最快），镜像作兜底。
     */
    private static final String[] MIRRORS = {
            "",                                    // 直连
            "https://ghfast.top/",
            "https://ghproxy.net/",
    };

    /** 查版本：每个源给短超时，失败就换下一个，全是同步的。 */
    private static final int CONNECT_MS = 10000;
    private static final int READ_MS = 15000;
    /** 下载 APK 的读超时：1MB 的包在慢网下也要一会儿。 */
    private static final int DL_READ_MS = 60000;

    /** APK 体积上限：现在的包约 1MB，超过 20MB 一定不是我们的文件。 */
    static final long MAX_APK_BYTES = 20L * 1024 * 1024;

    /** 资产下载地址是发布流程的固定形状（tag = v+版本名，文件 = AHYU-<版本名>.apk）。 */
    static String assetUrl(String version) {
        return "https://github.com/" + REPO + "/releases/download/v" + version
                + "/AHYU-" + version + ".apk";
    }

    static String releasePage(String version) {
        return "https://github.com/" + REPO + "/releases/tag/v" + version;
    }

    /* ------------------------------------------------------------ 结果模型 */

    /** 一次"检查更新"的结果。 */
    public static final class Info {
        /** 新版本号（"4.52.0"）；没查到或已是最新时为空。 */
        public String version = "";
        /** 仓库里声明的 versionCode（真实递增号，不是从版本名推算的）。 */
        public int versionCode;
        /** Release 说明；只在用户主动检查时才会去取，取不到为空。 */
        public String notes = "";
        /** 发布页地址。 */
        public String pageUrl = "";
        /** APK 资产的原始下载地址（未加镜像前缀）。 */
        public String assetUrl = "";
        /** 比当前新才为 true。 */
        public boolean newer;
        /** 出错原因；成功时为空。 */
        public String error = "";
        /** 实际用到的源（空 = 直连），写日志用。 */
        public String source = "";

        public boolean ok() {
            return error.isEmpty() && !version.isEmpty();
        }
    }

    /**
     * 解析 version.properties 的文本，填 version / versionCode。
     *
     * <p>手写解析而不是引 Properties：那是 {@code java.util}，本可以用，
     * 但项目里配置一律走手写解析（{@code Config} 同款风格），跟着走。
     * 认不出任何一行时返回 false，调用方按"这个源坏了，换下一个"处理。
     */
    static boolean parseVersionProps(String text, Info out) {
        if (text == null) return false;
        boolean any = false;
        for (String raw : text.split("\n")) {
            String s = raw.trim();
            if (s.isEmpty() || s.startsWith("#")) continue;
            int eq = s.indexOf('=');
            if (eq <= 0) continue;
            String k = s.substring(0, eq).trim();
            String v = stripV(s.substring(eq + 1).trim());
            if ("version.code".equals(k)) {
                int c = parseIntOr0(v);
                if (c > 0) {
                    out.versionCode = c;
                    any = true;
                }
            } else if ("version.name".equals(k)) {
                if (!v.isEmpty()) {
                    out.version = v;
                    any = true;
                }
            }
        }
        return any;
    }

    /** 当前应用版本名。 */
    public static String currentVersion() {
        return Backup.versionTag();
    }

    /** 当前应用 versionCode；取不到返回 -1。 */
    public static int currentVersionCode() {
        return currentVersionCode(Gateway.context());
    }

    /**
     * 按**传入的 Context** 取 versionCode；取不到返回 -1。
     *
     * <p>为什么不直接用 {@code Gateway.context()}：保活任务在网关没运行时也会跑，
     * 那时 Gateway 还没 init，全局 context 是 null —— 于是版本比较会退化，
     * 检查照样发出去、只是判断可能不对（而且不报错）。所以从调用方把
     * 一个一定活着的 Context 传进来。
     */
    public static int currentVersionCode(android.content.Context c) {
        try {
            if (c == null) return -1;
            return c.getPackageManager()
                    .getPackageInfo(c.getPackageName(), 0).versionCode;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 按传入 Context 取版本名；取不到返回空串。 */
    public static String currentVersion(android.content.Context c) {
        try {
            if (c == null) return "";
            return String.valueOf(c.getPackageManager()
                    .getPackageInfo(c.getPackageName(), 0).versionName);
        } catch (Throwable t) {
            return "";
        }
    }

    private static String stripV(String v) {
        String s = v == null ? "" : v.trim();
        while (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        return s;
    }

    private static String[] versionParts(String v) {
        String s = stripV(v);
        if (s.isEmpty()) return new String[0];
        return s.split("[^0-9]+");
    }

    private static int parseIntOr0(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 版本名按段比大小（4.51.10 > 4.51.9）。 */
    public static int compareVersion(String a, String b) {
        String[] x = versionParts(a);
        String[] y = versionParts(b);
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int vx = i < x.length ? parseIntOr0(x[i]) : 0;
            int vy = i < y.length ? parseIntOr0(y[i]) : 0;
            if (vx != vy) return vx < vy ? -1 : 1;
        }
        return 0;
    }

    /**
     * 该不该提示升级 —— **只升不降**。
     *
     * <p>抽成纯函数是为了能在自检里直接喂数字断言。写反成 {@code >=} 的话
     * 每次检查都会认为"有新版"，于是每天下一次同一个包、弹一次同一个通知，
     * 而日志里没有任何错误。
     */
    static boolean shouldOffer(int theirs, int mine) {
        return theirs > 0 && mine > 0 && theirs > mine;
    }

    /* --------------------------------------------------------- 查最新版本 */

    /**
     * 查最新版。**同步**、必须在后台线程调用。
     *
     * <p>逐个源试（直连优先，镜像兜底），全失败才返回 error。
     * 调用方据此决定是"静默等下次"还是"告诉用户"。
     */
    public static Info check() {
        return check(Gateway.context());
    }

    /** 按传入 Context 查（理由见 {@link #currentVersionCode(android.content.Context)}）。 */
    public static Info check(android.content.Context ctx) {
        Info out = new Info();
        List<String> tried = new ArrayList<>();
        for (String prefix : MIRRORS) {
            String url = prefix + VERSION_FILE_URL;
            tried.add(prefix.isEmpty() ? "直连" : prefix);
            try {
                java.util.Map<String, String> h = new java.util.HashMap<>();
                h.put("User-Agent", "AHYU-Updater");
                Http.Resp resp = Http.call("GET", url, h, null, null,
                        CONNECT_MS, READ_MS, false);
                if (resp.code != 200) continue;
                if (!parseVersionProps(resp.body, out)) continue;
                out.source = prefix.isEmpty() ? "直连" : prefix;
                out.pageUrl = releasePage(out.version);
                out.assetUrl = assetUrl(out.version);
                int cur = currentVersionCode(ctx);
                if (cur > 0 && out.versionCode > 0) {
                    out.newer = shouldOffer(out.versionCode, cur);
                } else {
                    /* 拿不到可比的国家码时退回按版本名比，宁可提示也不静默不提示
                       （提示错了还有下载后的 versionCode 校验兜底，不会真装上旧包）。 */
                    out.newer = compareVersion(out.version, currentVersion(ctx)) > 0;
                }
                return out;
            } catch (Throwable t) {
                Log.w("更新检查失败（" + tried.get(tried.size() - 1) + "）：" + t);
            }
        }
        out.version = "";
        out.error = "所有更新源都没返回版本号（试过：" + String.join("、", tried) + "）";
        return out;
    }

    /**
     * 取 Release 说明。**只在用户主动点"检查更新"时调**，后台自动检查不调。
     *
     * <p>走 GitHub API，而 API 经镜像是 403（见类注释的实测表）—— 所以这里
     * 注定只在直连/开代理时拿得到。拿不到就返回空串，界面上显示"无说明"，
     * 更新照做：说明是锦上添花，版本号和包才是必需的。
     */
    public static String fetchNotes(String version) {
        try {
            java.util.Map<String, String> h = new java.util.HashMap<>();
            h.put("Accept", "application/vnd.github+json");
            h.put("User-Agent", "AHYU-Updater");
            Http.Resp resp = Http.call("GET",
                    "https://api.github.com/repos/" + REPO + "/releases/tags/v" + version,
                    h, null, null, CONNECT_MS, READ_MS, false);
            if (resp.code != 200) return "";
            String body = new JSONObject(resp.body).optString("body", "");
            return body.length() > 4000 ? body.substring(0, 4000) + "…" : body;
        } catch (Throwable t) {
            return "";
        }
    }

    /* ------------------------------------------------------------- 下载 */

    /** 下载好的文件放哪：应用私有目录，不需要任何存储权限。 */
    public static File apkFile(android.content.Context c) {
        File dir = new File(c.getFilesDir(), "update");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "AHYU-update.apk");
    }

    /**
     * 下载并校验。**同步**、必须在后台线程调用。
     *
     * @return 下载好的文件；失败返回 null（原因写日志）
     */
    public static File download(android.content.Context c, Info info) {
        if (c == null || info == null || info.assetUrl == null || info.assetUrl.isEmpty()) {
            lastDownloadError = "参数不足（没有下载地址）";
            return null;
        }
        lastDownloadError = "";
        File target = apkFile(c);
        for (String prefix : MIRRORS) {
            String url = prefix + info.assetUrl;
            String src = prefix.isEmpty() ? "直连" : prefix;
            try {
                if (fetchTo(url, target)) {
                    String why = verify(c, target, info);
                    if (why == null) {
                        Log.i("更新包已下载并校验通过：" + info.version + "（源：" + src + "）");
                        return target;
                    }
                    /* 校验失败**不再换源重试**：要么是源给错了文件，要么是更糟的情况。
                       继续试只会重复下载，直接放弃并记明确日志。 */
                    Log.w("更新包校验失败，已放弃：" + why);
                    lastDownloadError = why;
                    target.delete();
                    return null;
                }
            } catch (Throwable t) {
                Log.w("更新下载失败（" + src + "）：" + t);
            }
        }
        if (lastDownloadError.isEmpty()) lastDownloadError = "所有源都没能下载（见日志）";
        return null;
    }

    /**
     * 最近一次 {@link #download} 失败的原因（成功时为空）。
     *
     * <p>界面上要说"为什么装不上"。以前只有日志里有 —— 而用户不会去看日志，
     * 他只看到点了没反应。这一条同时被 {@code /v1/debug/update-check} 读出来，
     * 好让"签名读不到"这类只有真机能发现的问题一眼可见。
     */
    private static volatile String lastDownloadError = "";

    public static String lastDownloadError() {
        return lastDownloadError;
    }

    /** 单个 URL 下到文件；成功返回 true。 */
    private static boolean fetchTo(String url, File target) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        boolean ok = false;
        try {
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(CONNECT_MS);
            conn.setReadTimeout(DL_READ_MS);
            conn.setRequestProperty("User-Agent", "AHYU-Updater");
            int code = conn.getResponseCode();
            if (code != 200) {
                /* 3xx 单独写出来：GitHub 的下载地址是 302 → 真文件，
                   没跟到跳转时报 200/302 的区别能一眼看出是重定向没跟。 */
                Log.w("更新下载 HTTP " + code + (code >= 300 && code < 400 ? "（未跟随到 200）" : "")
                        + "：" + shorten(url));
                return false;
            }
            long total = conn.getContentLengthLong();
            if (total > MAX_APK_BYTES) {
                Log.w("更新包异常大（" + total + " 字节），拒绝下载");
                return false;
            }
            InputStream in = conn.getInputStream();
            FileOutputStream out = new FileOutputStream(target);
            try {
                byte[] buf = new byte[16384];
                long got = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    got += n;
                    if (got > MAX_APK_BYTES) throw new java.io.IOException("下载超限");
                    out.write(buf, 0, n);
                }
                out.flush();
                try {
                    out.getFD().sync();
                } catch (Exception ignored) {
                }
                ok = got > 0;
            } finally {
                try {
                    out.close();
                } catch (Exception ignored) {
                }
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
            return ok;
        } finally {
            if (!ok) {
                try {
                    conn.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /* ------------------------------------------------------------- 校验 */

    /**
     * 校验下载到的 APK。返回 null = 通过；否则是写日志/提示的原因。
     *
     * <p>两条：
     * <ol>
     *   <li><b>签名证书 SHA-256 必须与当前应用一致</b>。这是防"下载源被劫持 /
     *       仓库被替换"的最后一道闸 —— 没这条的话，攻击者只要控制镜像
     *       就能让所有用户装上他的包。</li>
     *   <li>包里的 versionCode 必须<b>大于</b>当前（防止装了旧版或同一个包）。</li>
     * </ol>
     */
    private static String verify(android.content.Context c, File apk, Info info) {
        try {
            android.content.pm.PackageManager pm = c.getPackageManager();
            android.content.pm.PackageInfo mine = readPackage(pm, c.getPackageName(), true);
            android.content.pm.PackageInfo theirs = readPackage(pm, apk.getAbsolutePath(), false);
            if (theirs == null) return "下载的文件不是有效的 APK";
            String a = signatureSha256(mine);
            String b = signatureSha256(theirs);
            if (a.isEmpty() || b.isEmpty()) {
                return "签名读取失败（" + (a.isEmpty() ? "本机" : "")
                        + (a.isEmpty() && b.isEmpty() ? " / " : "")
                        + (b.isEmpty() ? "下载包" : "") + "）";
            }
            if (!a.equals(b)) {
                return "签名不匹配（本机 " + short8(a) + " / 下载包 " + short8(b)
                        + "）—— 该文件可能来自其它来源，已拒绝安装";
            }
            int mineCode = mine.versionCode;
            int theirsCode = theirs.versionCode;
            if (!shouldOffer(theirsCode, mineCode)) {
                return "下载包的 versionCode（" + theirsCode + "）没有高于当前（"
                        + mineCode + "），拒绝安装";
            }
            if (info != null && info.versionCode > 0 && theirsCode != info.versionCode) {
                Log.w("注意：下载包的 versionCode（" + theirsCode
                        + "）与更新源声明的（" + info.versionCode + "）不一致，以包内为准");
            }
            return null;
        } catch (Throwable t) {
            return "校验异常：" + t;
        }
    }

    /**
     * 读包信息并**带上签名**。
     *
     * <p>背景（这是本次实现里最容易写错、而且写错了完全不会报错的地方）：
     * 我们的包只有 v2/v3 签名，没有 v1（{@code apksigner verify -v} 实测
     * {@code Verified using v1 scheme (JAR signing): false}）。而
     * {@code GET_SIGNATURES} 在 API 28+ 被标记为废弃，文档建议改用
     * {@code GET_SIGNING_CERTIFICATES} + {@code signingInfo} —— 对 v2/v3-only 的包，
     * 老 flag 在某些实现上会返回空签名数组。
     *
     * <p><b>本机实测（Android 16 / API 36）</b>：两条路都能读到，且指纹完全相同
     * （{@code sigViaOldApi == sigViaNewApi == 45a99822…}；见
     * {@code /v1/debug/update-check} 的 {@code rawSignaturesLen=1}）。
     * 所以"老 API 一定读不到"是个**未经验证的假说**，不是事实依据。
     *
     * <p>既然两条路本机都通，为什么仍然用新的那条：旧 API 已被官方标注废弃，
     * 且它的行为在 v2/v3-only 的包上**没有跨版本保证**（本机能读到不代表每个 ROM 都能）。
     * 走新 API 是规范做法，回退分支是给"新 flag 反而读不到"的极端情况兜底 ——
     * 代价是几行代码，收益是这条路在别人的设备上不会因为一个已知会被移除的
     * 旧接口而失效。两边都读不到时返回 null，由调用方按"读不到"**拒绝安装**，
     * 绝不按"匹配"放过。
     *
     * @param byPackage true = 按包名读已安装的应用；false = 按文件路径读归档包
     */
    private static android.content.pm.PackageInfo readPackage(
            android.content.pm.PackageManager pm, String what, boolean byPackage) {
        final int OLD = android.content.pm.PackageManager.GET_SIGNATURES;
        /* 0x00000080：GET_SIGNING_CERTIFICATES（API 28 起）。这里写常量而不是引用
           符号，是为了让这段在任何 SDK 版本下都能编译；API 28 以下不会走到这里。 */
        final int NEW = 0x00000080;
        int flags = android.os.Build.VERSION.SDK_INT >= 28 ? NEW : OLD;
        try {
            android.content.pm.PackageInfo pi = byPackage
                    ? pm.getPackageInfo(what, flags)
                    : pm.getPackageArchiveInfo(what, flags);
            if (pi != null && pi.signatures != null && pi.signatures.length > 0) return pi;
            if (pi != null && pi.signingInfo != null) return pi;
            /* 新 flag 读不到时退回旧 flag —— 极端 ROM 上的兜底，不是拿来掩盖逻辑错误的。 */
            if (flags != OLD) {
                return byPackage ? pm.getPackageInfo(what, OLD)
                        : pm.getPackageArchiveInfo(what, OLD);
            }
            return pi;
        } catch (Throwable t) {
            Log.w("读取包签名失败（" + what + "）：" + t);
            return null;
        }
    }

    /**
     * 取签名证书指纹（SHA-256，十六进制小写）；取不到返回空串。
     *
     * <p>优先 {@code signingInfo}（API 28+，规范路径），退回
     * {@code signatures}（老路径 / 新 flag 读不到时）。两边都空就是空串 —— 调用方按"读不到"
     * 拒绝安装，而不是按"匹配"放过。
     */
    static String signatureSha256(android.content.pm.PackageInfo pi) {
        try {
            if (pi == null) return "";
            android.content.pm.Signature s = null;
            if (pi.signingInfo != null) {
                android.content.pm.Signature[] arr = pi.signingInfo.hasMultipleSigners()
                        ? pi.signingInfo.getApkContentsSigners()
                        : pi.signingInfo.getSigningCertificateHistory();
                if (arr != null && arr.length > 0) s = arr[0];
            }
            if (s == null && pi.signatures != null && pi.signatures.length > 0) {
                s = pi.signatures[0];
            }
            if (s == null) return "";
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.toByteArray());
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format(java.util.Locale.US, "%02x", x));
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 调试包专用：把"查版本 / 下载 / 校验"三步的真实结果一次性报出来。
     *
     * <p>存在的理由是这条链路**不能靠读代码确认**：我们的包只有 v2/v3 签名
     * （{@code apksigner verify -v} 实测 {@code Verified using v1 scheme: false}），
     * 而 {@code GET_SIGNATURES} 在 API 28+ 读这种包会返回空签名数组。
     * 也就是说，如果签名读取写错了，功能会稳定地报"签名读取失败"然后**自动更新整个失效**，
     * 而且日志里只有一行看起来像网络问题的东西。所以必须在这台真机上问一次：
     * 老 API 读到什么、新 API 读到什么、下载后校验到哪一步停。
     *
     * <p>注意它下载的是**当前已发布的那个同版本包**：必然在"版本必须更高"被拒，
     * 而**只有签名先比对通过才会走到那一步** —— 所以看到"versionCode 没有高于当前"
     * 就等于证明了下载 + 签名校验整条链路是通的。下完即删，不留残留。
     */
    static JSONObject report(android.content.Context c) {
        JSONObject o = new JSONObject();
        try {
            o.put("currentVersion", currentVersion(c));
            o.put("currentVersionCode", currentVersionCode(c));
            /* 两条签名读取路径分别报出来，对比才知道为什么必须用新的那条 */
            try {
                android.content.pm.PackageManager pm = c.getPackageManager();
                String viaOld = signatureSha256(pm.getPackageInfo(c.getPackageName(),
                        android.content.pm.PackageManager.GET_SIGNATURES));
                String viaNew = signatureSha256(readPackage(pm, c.getPackageName(), true));
                o.put("sigViaOldApi", viaOld.isEmpty() ? "(空)" : viaOld);
                o.put("sigViaNewApi", viaNew.isEmpty() ? "(空)" : viaNew);
                /* 本机签名应与 apksigner 报的证书一致（发布密钥 45a99822…）；
                   不一致就说明读到的不是证书而是别的什么，比对会失真。 */
                o.put("sigMatchesKnownKey", viaNew.startsWith("45a99822"));
            } catch (Throwable t) {
                o.put("sigError", String.valueOf(t));
            }
            Info info = check(c);
            JSONObject inf = new JSONObject();
            inf.put("version", info.version);
            inf.put("versionCode", info.versionCode);
            inf.put("newer", info.newer);
            inf.put("source", info.source);
            inf.put("error", info.error);
            inf.put("assetUrl", info.assetUrl);
            o.put("check", inf);

            /* 下载 + 校验。
               挑哪个版本下：**有新版就下新版**（那样能真正验证"版本更高 → 校验通过"，
               是完整链路）；没有新版才退而测当前版 —— 那种情况下必然在"版本必须更高"
               被拒，但**只有签名先比对通过才会走到那一步**，所以仍然验证了签名读取。
               这里不用 download()：它在校验失败时会把文件删掉，而我们要看的正是
               "它在哪一步、因为什么停下来"。所以分两步走，最后自己删 —— 不留残留。 */
            Info dl = new Info();
            boolean haveNew = info.ok() && info.newer;
            dl.version = haveNew ? info.version : currentVersion(c);
            dl.versionCode = haveNew ? info.versionCode : currentVersionCode(c);
            dl.assetUrl = haveNew ? info.assetUrl : assetUrl(dl.version);
            o.put("testedVersion", dl.version);
            java.io.File apk = apkFile(c);
            boolean fetched = false;
            for (String prefix : MIRRORS) {
                try {
                    if (fetchTo(prefix + dl.assetUrl, apk)) {
                        fetched = true;
                        o.put("downloadSource", prefix.isEmpty() ? "直连" : prefix);
                        break;
                    }
                } catch (Throwable t) {
                    o.put("downloadError", String.valueOf(t));
                }
            }
            o.put("downloaded", fetched);
            if (fetched) {
                o.put("downloadedBytes", apk.length());
                /* 校验结果原样报出来（null = 通过）。同版本包必然因"版本没有更高"被拒，
                   那恰好证明签名比对已经过了。 */
                String why = verify(c, apk, dl);
                o.put("verify", why == null ? "通过" : why);
                /* 两边指纹分别报出来：都为空说明是"读不到"，不是"不匹配" */
                try {
                    android.content.pm.PackageManager pm = c.getPackageManager();
                    String theirs = signatureSha256(readPackage(pm, apk.getAbsolutePath(), false));
                    o.put("sigDownloadedApk", theirs.isEmpty() ? "(空)" : theirs);
                    /* 老 API 读**归档包**（v2/v3-only）的对照值。
                       本机 Android 16 实测：读得到、且与新 API 指纹相同
                       （sigViaOldApiOnArchive == sigViaNewApi）。仍然保留这一项是因为
                       别的 ROM/版本上未必如此 —— 它是"签名比对到底为什么可信"的证据。 */
                    android.content.pm.PackageInfo raw = pm.getPackageArchiveInfo(
                            apk.getAbsolutePath(),
                            android.content.pm.PackageManager.GET_SIGNATURES);
                    String rawSig = signatureSha256(raw);
                    o.put("sigViaOldApiOnArchive", rawSig.isEmpty() ? "(空)" : rawSig);
                    o.put("rawSignaturesLen", raw == null || raw.signatures == null
                            ? 0 : raw.signatures.length);
                } catch (Throwable t) {
                    o.put("sigDownloadedApk", "异常 " + t);
                }
                apk.delete();
                o.put("cleaned", !apk.isFile());
            }
            o.put("canRequestPackageInstalls",
                    c.getPackageManager().canRequestPackageInstalls());
        } catch (Throwable t) {
            try {
                o.put("error", String.valueOf(t));
            } catch (Throwable ignored) {
            }
        }
        return o;
    }

    private static String short8(String s) {
        return s.length() <= 8 ? s : s.substring(0, 8);
    }

    private static String shorten(String s) {
        return s == null || s.length() <= 120 ? s : s.substring(0, 120) + "…";
    }

    /* ------------------------------------------------------------- 安装 */

    /** 交给系统的安装会话用的回调 Intent 动作（结果由 InstallResultReceiver 收）。 */
    public static final String ACTION_INSTALL_RESULT = "com.cpa.gateway.UPDATE_INSTALLED";

    /**
     * 触发系统安装。
     *
     * <p>走 {@code PackageInstaller}（API 21+ 的系统组件），**不引任何依赖、
     * 不需要 FileProvider**：把 APK 的字节直接流进安装会话，由系统弹确认框。
     * 原来设想的 {@code ACTION_VIEW} + file:// 那套在 Android 7+ 会直接
     * {@code FileUriExposedException}（项目 minSdk 26，所以只能走这条）。
     *
     * <p>**调用前会再校验一次签名**。这一步不是重复劳动：下载可能是几天前完成的
     * （通知一直挂着没点），期间那个文件可能被别的东西改动。装之前再看一眼
     * 只花几毫秒，漏掉却是把设备交给别人。
     *
     * @return null = 已成功发起（系统框会弹）；否则是给用户看的原因
     */
    public static String install(android.content.Context c, File apk) {
        if (c == null || apk == null || !apk.isFile()) return "更新包不存在（可能已清理，重新检查更新即可）";
        try {
            String why = verify(c, apk, null);
            if (why != null) {
                Log.w("安装前校验未通过：" + why);
                apk.delete();
                return "校验未通过，已删除下载的安装包：" + why;
            }
            /* 用户没允许"安装未知应用"时，系统会直接拒。先检查并引导，
               否则用户点"立即更新"什么都不发生，会以为功能坏了。 */
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
                    && !c.getPackageManager().canRequestPackageInstalls()) {
                return NEED_PERMISSION;
            }
            android.content.pm.PackageInstaller pi =
                    c.getPackageManager().getPackageInstaller();
            android.content.pm.PackageInstaller.SessionParams p =
                    new android.content.pm.PackageInstaller.SessionParams(
                            android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            int id = pi.createSession(p);
            android.content.pm.PackageInstaller.Session session = pi.openSession(id);
            try {
                java.io.OutputStream out = session.openWrite("ahyu", 0, apk.length());
                java.io.FileInputStream in = new java.io.FileInputStream(apk);
                try {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    session.fsync(out);
                } finally {
                    try {
                        in.close();
                    } catch (Exception ignored) {
                    }
                    try {
                        out.close();
                    } catch (Exception ignored) {
                    }
                }
                android.content.Intent cb = new android.content.Intent(c,
                        com.cpa.gateway.InstallResultReceiver.class)
                        .setAction(ACTION_INSTALL_RESULT);
                int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    flags |= android.app.PendingIntent.FLAG_IMMUTABLE;
                }
                session.commit(android.app.PendingIntent.getBroadcast(c, 0, cb, flags)
                        .getIntentSender());
            } finally {
                try {
                    session.close();
                } catch (Exception ignored) {
                }
            }
            Log.i("已发起安装：" + apk.getName() + "（系统会弹确认框）");
            return null;
        } catch (Throwable t) {
            Log.w("发起安装失败：" + t);
            return String.valueOf(t.getMessage());
        }
    }

    /** install() 的哨兵返回值：需要先拿到"安装未知应用"授权。 */
    public static final String NEED_PERMISSION = "NEED_PERMISSION";

    /**
     * 拿到"安装未知应用"的授权页（Android 8+）。
     *
     * <p>只能由界面层调（要 startActivity）。
     */
    public static android.content.Intent installPermissionIntent(android.content.Context c) {
        android.content.Intent it = new android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
        it.setData(android.net.Uri.parse("package:" + c.getPackageName()));
        return it;
    }

    /** 清掉下载残留（安装完成后调用；失败也不影响）。 */
    public static void cleanup(android.content.Context c) {
        try {
            File f = apkFile(c);
            if (f.isFile() && f.delete()) Log.i("已清理下载的安装包");
        } catch (Throwable ignored) {
        }
    }

    /* --------------------------------------------------------- 自动检查 */

    /** 自动检查的间隔：一天一次。 */
    public static final long AUTO_INTERVAL_S = 24L * 3600;

    /** 一次只跑一个检查（否则慢网下载会跨多个保活周期叠出并发下载）。 */
    private static volatile boolean inFlight;

    /**
     * 已下载文件是不是就是本次要的那个版本。
     *
     * <p>判据取**文件里的 versionName**，不取进程内的"上次通知过哪个版本"：
     * 进程被系统回收后那个字段就没了，用它的结果是"每天重新下载同一个 1MB 包"，
     * 而用户看不出任何区别（他还没点安装而已）。读文件本身才是可靠的。
     *
     * <p>命中之后仍会走一次 {@link #verify}：文件躺了几天，可能已经被人改动过。
     */
    static boolean alreadyDownloaded(android.content.Context c, File apk, String version) {
        try {
            if (c == null || apk == null || !apk.isFile() || version == null) return false;
            android.content.pm.PackageInfo pi = c.getPackageManager()
                    .getPackageArchiveInfo(apk.getAbsolutePath(), 0);
            return pi != null && version.equals(String.valueOf(pi.versionName));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 后台自动检查（跟随保活任务调用，由调用方按天限频）。
     *
     * <p>发现新版 → 下载 → 校验通过 → 交 {@code onReady} 发通知。
     * 全程静默：下载时不打扰，只有"可以装了"才发通知（这是用户唯一需要知道的事）。
     * 失败只写日志，等明天。
     *
     * @param onReady 下载并校验成功后回调（后台线程），参数是版本信息
     */
    public static void checkInBackground(final android.content.Context c,
                                         final ReadyListener onReady) {
        if (c == null || inFlight) return;
        inFlight = true;
        Threads.io(() -> {
            try {
                Info info = check(c);
                if (!info.ok() || !info.newer) {
                    if (!info.ok()) Log.i("自动更新检查：" + info.error);
                    else {
                        Log.i("自动更新检查：已是最新（" + info.version + "，源 "
                                + info.source + "）");
                        /* 已经和最新包同版本或更新，却还留着下载文件 → 清掉。
                           升级成功后这个文件不会再被任何人删，不清就永远占着。 */
                        cleanup(c);
                    }
                    return;
                }
                File apk = apkFile(c);
                boolean reuse = alreadyDownloaded(c, apk, info.version);
                if (!reuse) {
                    File got = download(c, info);
                    if (got == null) return;
                } else if (verify(c, apk, info) != null) {
                    /* 版本对得上却校验不过：文件被动过 / 坏了。删掉重来一次，
                       而不是把"装不上"留给用户在通知里去撞。 */
                    Log.w("已存在的更新包校验不通过，删除后重新下载");
                    apk.delete();
                    if (download(c, info) == null) return;
                }
                Log.i("新版本 " + info.version + " 已就绪并通过校验，等待用户确认安装");
                if (onReady != null) onReady.onReady(info);
            } catch (Throwable t) {
                Log.w("自动更新检查异常：" + t);
            } finally {
                inFlight = false;
            }
        });
    }

    /** 后台检查就绪回调（在后台线程；发通知请自行处理线程）。 */
    public interface ReadyListener {
        void onReady(Info info);
    }

    /**
     * 用户主动检查：走后台线程 + 取 Release 说明。
     *
     * <p>和后台检查的差别只有两点：说明一定要去取一次（前台行为，值得等），
     * 失败一定要告诉用户（后台静默，这里不能装作没事）。
     */
    public static void checkManual(final android.content.Context c, final Callback cb) {
        Threads.io(() -> {
            Info info = check(c);
            File ready = null;
            if (info.ok() && info.newer) {
                info.notes = fetchNotes(info.version);
                File apk = apkFile(c);
                /* 之前自动下载过的话，直接给出去，用户点一下就能装，不必再下一遍。 */
                if (alreadyDownloaded(c, apk, info.version)) ready = apk;
            }
            if (cb != null) cb.onResult(info, ready);
        });
    }

    /** 检查结果回调（都在后台线程；UI 操作请自行 runOnUiThread）。 */
    public interface Callback {
        /**
         * @param info 版本信息（失败时 info.error 非空）
         * @param apk  已下载并校验通过的安装包；null = 还没有可装的东西
         */
        void onResult(Info info, File apk);
    }
}
