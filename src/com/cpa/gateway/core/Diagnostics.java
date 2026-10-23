package com.cpa.gateway.core;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 一键诊断包（4.39.0）。
 *
 * <p>为什么做：排查问题时最耗时的不是分析，而是"让对方把版本、配置、日志、用量、
 * 自检结果分别找到并贴过来"。这些数据本来都在应用里，只是散落在不同页面/文件。
 * 这里把它们打成一个 zip，用户在设置页点一下就得到；配置里的密钥与凭据会脱敏。
 *
 * <p>刻意不包含：keystore、备份密码、GitHub 令牌、usage.tsv 原文件（只放聚合报告）。
 * rawcap 尾部可能含上游回答内容，文件里会明确标注"发送前自行检查"。
 */
public final class Diagnostics {

    private Diagnostics() {
    }

    /** 建议的文件名（不含路径）。 */
    public static String suggestedName() {
        String v = Backup.versionTag();
        if (v == null || v.isEmpty()) v = "unknown";
        v = v.replaceAll("[^0-9A-Za-z._-]", "_");
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);
        return "AHYU-diagnostics-" + v + "-" + f.format(new Date()) + ".zip";
    }

    /** 在后台线程构建 zip 字节；不做任何 UI 操作。 */
    public static byte[] build() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 * 1024);
        ZipOutputStream zip = new ZipOutputStream(bytes);
        try {
            put(zip, "README.txt", readme());
            put(zip, "summary.txt", summary());
            put(zip, "config-redacted.json", redactedConfig());
            put(zip, "logs.txt", join(Log.snapshot()));
            put(zip, "connection.log.tail.txt", tail("eta-gateway.log", 96 * 1024));
            put(zip, "rawcap.tail.txt", rawcapTail());
            put(zip, "usage-report.txt", usageReport());
            put(zip, "selftest.txt", selftest());
        } finally {
            zip.close();
        }
        return bytes.toByteArray();
    }

    /** 直接写到一个已经打开的流（SAF 选好的文件）。 */
    public static void writeTo(OutputStream out) throws Exception {
        byte[] data = build();
        out.write(data);
        out.flush();
    }

    private static void put(ZipOutputStream zip, String name, String text) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String readme() {
        return "AHYU 诊断包\n"
                + "============\n\n"
                + "包含：\n"
                + "  summary.txt              版本 / 设备 / 运行状态 / 内存\n"
                + "  config-redacted.json     脱敏后的配置（apiKey、credentials 已替换为 ***）\n"
                + "  logs.txt                 应用内存日志（最近 500 条）\n"
                + "  connection.log.tail.txt  落盘日志尾部（最近 96KB）\n"
                + "  rawcap.tail.txt          可疑响应留样尾部（最近 16KB）\n"
                + "  usage-report.txt         用量、行为画像、最近请求对账\n"
                + "  selftest.txt             内置自检结果（含流式解析回放）\n\n"
                + "隐私提醒：\n"
                + "  · config 里的密钥/凭据已脱敏，但仍含账号名与模型目录。\n"
                + "  · rawcap.tail.txt 可能包含上游返回的回答内容；发送前请自行检查。\n"
                + "  · 不包含 keystore、备份密码、GitHub 令牌。\n";
    }

    private static String summary() {
        Context ctx = Gateway.context();
        StringBuilder sb = new StringBuilder();
        sb.append("版本：").append(Backup.versionTag()).append('\n');
        sb.append("时间：").append(new Date()).append('\n');
        sb.append("设备：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" · Android ").append(Build.VERSION.RELEASE)
                .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("ABI：").append(Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?")
                .append('\n');
        Config cfg = Gateway.config();
        sb.append("网关：").append(Gateway.isRunning() ? "运行中" : "已停止")
                .append(" · 端口 ").append(cfg == null ? "?" : cfg.port)
                .append(" · 局域网 ").append(cfg != null && cfg.lanAccess ? "开" : "关")
                .append('\n');
        if (cfg != null) {
            int usable = 0;
            int models = 0;
            for (Config.Provider p : cfg.providers) {
                if (p.enabled) {
                    usable++;
                    models += Math.max(0, p.models.size() - p.badModels.size());
                }
            }
            sb.append("账号：").append(cfg.providers.size()).append("（启用 ").append(usable)
                    .append("）· 模型 ").append(models).append('\n');
            sb.append("客户端 Key：").append(cfg.apiKey == null || cfg.apiKey.isEmpty()
                    ? "未设置" : "已设置（已脱敏）").append('\n');
        }
        Runtime rt = Runtime.getRuntime();
        sb.append("Java 堆：max ").append(rt.maxMemory() / 1024 / 1024).append("MB · 已用 ")
                .append((rt.totalMemory() - rt.freeMemory()) / 1024 / 1024).append("MB\n");
        try {
            sb.append("Native 堆：").append(android.os.Debug.getNativeHeapAllocatedSize() / 1024 / 1024)
                    .append("MB\n");
        } catch (Throwable ignored) {
        }
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                sb.append("系统内存：可用 ").append(mi.availMem / 1024 / 1024)
                        .append("MB / 总 ").append(mi.totalMem / 1024 / 1024)
                        .append("MB · lowMemory=").append(mi.lowMemory).append('\n');
            }
        } catch (Throwable ignored) {
        }
        sb.append("线程数：").append(Thread.activeCount()).append('\n');
        /* 指纹对抗的健康度：出站尝试 / 混淆次数 / 拦截次数，以及最要紧的
           "已混淆却仍被拦"。最后一项非 0 就是对抗失效的确证（见 FingerprintWatch）。 */
        sb.append(FingerprintWatch.summary()).append('\n');
        return sb.toString();
    }

    private static String redactedConfig() throws Exception {
        Config cfg = Gateway.config();
        JSONObject o = new JSONObject(cfg.toJson().toString());
        o.put("apiKey", mask(Proto.optString(o, "apiKey", "")));
        JSONArray aliases = o.optJSONArray("apiKeyAliases");
        if (aliases != null) {
            for (int i = 0; i < aliases.length(); i++) aliases.put(i, "***");
        }
        JSONArray arr = o.optJSONArray("providers");
        for (int i = 0; arr != null && i < arr.length(); i++) {
            JSONObject p = arr.optJSONObject(i);
            if (p == null) continue;
            p.put("apiKey", mask(Proto.optString(p, "apiKey", "")));
            String cred = Proto.optString(p, "credentials", "");
            p.put("credentials", cred.isEmpty() ? "" : "*** (" + cred.length() + " chars)");
        }
        return o.toString(2);
    }

    private static String mask(String v) {
        if (v == null || v.isEmpty()) return "";
        return "*** (" + v.length() + " chars)";
    }

    private static String usageReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("== 用量（近 7 天）==\n");
        try {
            sb.append(Usage.line(7)).append("\n\n");
        } catch (Throwable t) {
            sb.append("读取失败：").append(t).append("\n\n");
        }
        sb.append("== 行为画像（近 7 天）==\n");
        try {
            sb.append(QualityStats.report(7)).append("\n\n");
        } catch (Throwable t) {
            sb.append("统计失败：").append(t).append("\n\n");
        }
        sb.append("== 最近请求对账 ==\n");
        try {
            sb.append(QualityStats.traceReport(30)).append('\n');
        } catch (Throwable t) {
            sb.append("读取失败：").append(t).append('\n');
        }
        return sb.toString();
    }

    private static String selftest() {
        StringBuilder sb = new StringBuilder();
        try {
            List<Selftest.Result> rows = Selftest.runAll();
            int failed = 0;
            for (Selftest.Result r : rows) {
                if (!r.ok) failed++;
                sb.append(r.ok ? "PASS\t" : "FAIL\t").append(r.name).append('\t')
                        .append(r.detail == null ? "" : r.detail).append('\n');
            }
            sb.append('\n').append(rows.size() - failed).append('/').append(rows.size())
                    .append(" 通过\n");
        } catch (Throwable t) {
            sb.append("自检运行失败：").append(t).append('\n');
        }
        return sb.toString();
    }

    private static String rawcapTail() {
        return "（可能包含上游返回的回答内容，发送前请检查）\n\n"
                + tail("rawcap.log", 16 * 1024);
    }

    /** 读文件尾部 maxBytes；文件不存在返回说明文字。 */
    private static String tail(String name, int maxBytes) {
        Context ctx = Gateway.context();
        if (ctx == null) return "（没有应用上下文）\n";
        File f = new File(ctx.getFilesDir(), name);
        if (!f.isFile()) return "（没有 " + name + "）\n";
        try {
            long len = f.length();
            long skip = Math.max(0, len - maxBytes);
            FileInputStream in = new FileInputStream(f);
            try {
                long left = skip;
                while (left > 0) {
                    long s = in.skip(left);
                    if (s <= 0) break;
                    left -= s;
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                String text = new String(out.toByteArray(), StandardCharsets.UTF_8);
                return "（显示最后 " + Math.min(len, maxBytes) + " 字节 / 共 " + len + " 字节）\n\n"
                        + text;
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            return "（读取失败：" + t + "）\n";
        }
    }

    private static String join(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append('\n');
        return sb.toString();
    }
}
