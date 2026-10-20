package com.cpa.gateway.core;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Tiny in-memory log ring shared by the service, the server and the UI. */
public final class Log {

    public interface Listener {
        void onLog(String line);
    }

    private static final int MAX = 500;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    // SimpleDateFormat 不是线程安全的（请求线程、探活线程、UI 线程都会调 Log）；
    // 原来 static 共享一个，并发一高时间戳会串。ThreadLocal 每个线程一份，无锁。
    private static final ThreadLocal<SimpleDateFormat> FMT = new ThreadLocal<SimpleDateFormat>() {
        @Override
        protected SimpleDateFormat initialValue() {
            return new SimpleDateFormat("HH:mm:ss", Locale.US);
        }
    };
    /**
     * 观察者列表（可以是多个）。
     *
     * 原来只有一个 listener 字段：日志页 onDestroy 置 null，会把另一个还在显示的
     * 页面（旋转重建、多实例并存）的监听一起清掉，日志就不再刷新。改成注册/注销。
     */
    private static final java.util.concurrent.CopyOnWriteArrayList<Listener> LISTENERS =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    private Log() {}

    public static void setListener(Listener l) {
        LISTENERS.clear();
        if (l != null) LISTENERS.add(l);
    }

    public static void addListener(Listener l) {
        if (l != null && !LISTENERS.contains(l)) LISTENERS.add(l);
    }

    public static void removeListener(Listener l) {
        if (l != null) LISTENERS.remove(l);
    }

    public static void i(String msg) {
        add("INFO", msg);
    }

    public static void w(String msg) {
        add("WARN", msg);
    }

    public static void e(String msg) {
        add("ERROR", msg);
    }

    private static final String TAG = "CPA";

    /* ---------------------------------------------------------- 落盘（4.33.0）
     *
     * 为什么必须落盘：以前日志只有内存里 500 条环形缓冲，重启就清空。
     * 等到要查"刚才那几次请求到底回了什么"时，现场早就没了——
     * 今天想把 21:24 那次失败翻出来，日志里一条都没有。
     *
     * 做法：攒批追加写到 files/eta-gateway.log，每 64 行或 5 秒落一次盘，
     * 单文件超过 4MB 滚成 .1（只留一份旧的）。金额和写盘都在 IO 线程外，
     * 只在必要时同步，避免拖慢请求。
     */
    private static final Object DISK_LOCK = new Object();
    private static final java.util.List<String> DISK_BUF = new java.util.ArrayList<>();
    private static long lastFlush;
    private static final long MAX_BYTES = 4L * 1024 * 1024;

    private static void toDisk(String line) {
        synchronized (DISK_LOCK) {
            DISK_BUF.add(line);
            long now = System.currentTimeMillis();
            if (DISK_BUF.size() < 64 && now - lastFlush < 5000) return;
            flushLocked();
        }
    }

    /** 立刻把缓冲写盘（退出、崩溃前、读日志前调用）。 */
    public static void flush() {
        synchronized (DISK_LOCK) {
            flushLocked();
        }
    }

    private static void flushLocked() {
        if (DISK_BUF.isEmpty()) return;
        android.content.Context ctx = Gateway.context();
        if (ctx == null) return;          // 应用还没起来，先攒着
        java.io.File f = new java.io.File(ctx.getFilesDir(), "eta-gateway.log");
        try {
            /* 超限滚动：把当前文件改名成 .1，覆盖旧的 .1 */
            if (f.isFile() && f.length() > MAX_BYTES) {
                java.io.File old = new java.io.File(ctx.getFilesDir(), "eta-gateway.log.1");
                if (old.isFile() && !old.delete()) { /* 删不掉就放弃滚动，别丢日志 */ }
                if (!f.renameTo(old)) { /* 改名失败也一样：继续追加，不丢 */ }
            }
            java.io.FileOutputStream out = new java.io.FileOutputStream(f, true);
            try {
                for (String l : DISK_BUF) out.write((l + "\n").getBytes("UTF-8"));
            } finally {
                out.close();
            }
            lastFlush = System.currentTimeMillis();
            DISK_BUF.clear();
        } catch (Throwable t) {
            /* 写盘失败：保留缓冲，等下次；只保留最近 500 行，避免磁盘长期故障吃满内存 */
            while (DISK_BUF.size() > 500) DISK_BUF.remove(0);
        }
    }

    /* ------------------------------------------------------- 日志脱敏（4.50.3）
     *
     * 登录回调、上游 token 响应、错误体都可能带凭据；日志会落盘并被打进诊断包。
     * 在**入口**统一脱敏，比逐个调用点记得"别打印"可靠。
     * 只处理明确的凭据形状，不动普通错误码（例如 {"code":14018} 保留）。
     */
    private static final java.util.regex.Pattern[] SECRET_PATTERNS = {
            /* URL / query 里的 OAuth 参数 */
            java.util.regex.Pattern.compile(
                    "(?i)([?&](?:code|state|refreshToken|userJwt|userInfo|access_token|refresh_token)=)[^&\\s]+"),
            /* JSON / 表单里的 token 字段 */
            java.util.regex.Pattern.compile(
                    "(?i)(\"?(?:access_token|refresh_token|id_token|refreshToken|userJwt|userInfo|authorization|x-api-key|x-goog-api-key|api[_-]?key|state)\"?\\s*[:=]\\s*\"?)([^\"\\s,&}]+)"),
            /* Bearer / JWT */
            java.util.regex.Pattern.compile("(?i)(Bearer\\s+)[A-Za-z0-9._~+/-]{8,}=*"),
            java.util.regex.Pattern.compile(
                    "eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"),
            /* 常见厂商 key 形状 */
            java.util.regex.Pattern.compile("(?i)(GOCSPX-|ghp_|github_pat_|sk-|AIza)[A-Za-z0-9._-]{8,}"),
    };

    /** 对一行日志做凭据脱敏。包可见，方便自检。 */
    static String scrub(String msg) {
        if (msg == null || msg.isEmpty()) return msg;
        String out = msg;
        out = SECRET_PATTERNS[0].matcher(out).replaceAll("$1***");
        out = SECRET_PATTERNS[1].matcher(out).replaceAll("$1***");
        out = SECRET_PATTERNS[2].matcher(out).replaceAll("$1***");
        out = SECRET_PATTERNS[3].matcher(out).replaceAll("jwt:***");
        out = SECRET_PATTERNS[4].matcher(out).replaceAll("$1***");
        return out;
    }

    private static void add(String level, String msg) {
        msg = scrub(msg);
        String line = FMT.get().format(new Date()) + " [" + level + "] " + msg;
        toDisk(line);
        try {
            if ("ERROR".equals(level)) android.util.Log.e(TAG, msg);
            else if ("WARN".equals(level)) android.util.Log.w(TAG, msg);
            else android.util.Log.i(TAG, msg);
        } catch (Throwable ignored) {
        }
        synchronized (LINES) {
            LINES.addLast(line);
            while (LINES.size() > MAX) LINES.removeFirst();
        }
        for (Listener l : LISTENERS) {
            try {
                l.onLog(line);
            } catch (Throwable ignored) {
            }
        }
    }

    public static List<String> snapshot() {
        synchronized (LINES) {
            return new ArrayList<>(LINES);
        }
    }

    public static void clear() {
        synchronized (LINES) {
            LINES.clear();
        }
    }
}
