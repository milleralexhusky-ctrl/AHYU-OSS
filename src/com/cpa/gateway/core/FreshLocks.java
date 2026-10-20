package com.cpa.gateway.core;

/**
 * token 刷新的按账号锁。
 *
 * 原来各 Provider 的 ensureFresh 是 {@code static synchronized}（类级锁）：
 * 一个账号刷新 token 要走一次网络（几百毫秒到几秒），这段时间同 Provider 的
 * 其他账号全被堵在门外，高峰期就是排队延迟。
 *
 * 现在按账号（p.name 唯一）加锁：各账号各刷各的，互不干扰。
 * 同一账号的并发刷新还是串行的——这正是要的（防重复刷新）。
 */
final class FreshLocks {

    private static final java.util.concurrent.ConcurrentHashMap<String, Object> LOCKS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 按 key 取锁对象；同一个 key 永远拿到同一个对象。 */
    static Object forKey(String key) {
        if (key == null) key = "";
        return LOCKS.computeIfAbsent(key, k -> new Object());
    }

    /**
     * 按账号取锁。优先用不可变的 `p.id`，不要用可改、可能重名的 `p.name`。
     *
     * 两个配置项同名（备份导入、重命名过程中）时，按 name 会拿到两把不同的锁，
     * 同一份 refresh_token 被并发刷新；轮换型 refresh_token 直接失效。
     */
    static Object of(Config.Provider p) {
        if (p == null) return forKey("");
        String id = p.id == null ? "" : p.id.trim();
        String key = id.isEmpty() ? (p.name == null ? "" : p.name) : id;
        String type = p.type == null ? "" : p.type;
        return forKey(type + ":" + key);
    }

    private FreshLocks() {
    }
}
