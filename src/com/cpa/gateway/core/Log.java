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

    private static void add(String level, String msg) {
        String line = FMT.get().format(new Date()) + " [" + level + "] " + msg;
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
