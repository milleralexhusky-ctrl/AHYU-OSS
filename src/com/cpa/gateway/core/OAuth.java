package com.cpa.gateway.core;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.security.MessageDigest;
import java.security.SecureRandom;

/** Shared pieces of the loopback OAuth flows (PKCE codes and a one-shot callback listener). */
public final class OAuth {

    private OAuth() {}

    public static final class Pkce {
        public final String verifier;
        public final String challenge;

        public Pkce() {
            this.verifier = randomUrlSafe(64);
            this.challenge = s256(verifier);
        }

        private static String randomUrlSafe(int len) {
            String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~";
            StringBuilder sb = new StringBuilder();
            // PKCE verifier 是安全随机数：原来 java.util.Random 可预测，换 SecureRandom
            SecureRandom r = new SecureRandom();
            for (int i = 0; i < len; i++) sb.append(alphabet.charAt(r.nextInt(alphabet.length())));
            return sb.toString();
        }

        private static String s256(String verifier) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] digest = md.digest(verifier.getBytes("US-ASCII"));
                return android.util.Base64.encodeToString(digest,
                        android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /**
     * Serves the provider's redirect target once and returns the "code" query parameter.
     * Every browser hit gets a tiny page saying the login finished.
     */
    /** Listeners keyed by port: one login screen must never close another screen's socket. */
    private static final java.util.Map<Integer, ServerSocket> ACTIVE =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<Integer> CANCELLED =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<Integer, Boolean>());

    /** Releases one loopback port (called when that login screen goes away). */
    public static void cancel(int port) {
        ServerSocket s = ACTIVE.remove(port);
        CANCELLED.add(port);
        if (s != null) {
            try {
                s.close();
                Log.i("已释放登录回调端口 " + port);
            } catch (Exception ignored) {
            }
        }
    }

    public static void cancelActive() {
        for (Integer p : new java.util.ArrayList<>(ACTIVE.keySet())) cancel(p.intValue());
    }

    /** True once, if this port was taken over by a newer login attempt. */
    public static boolean consumeCancelled(int port) {
        return CANCELLED.remove(port);
    }

    public static String waitForCode(String providerLabel, int port, String expectedState, long timeoutMs) throws Exception {
        ServerSocket server = bind(providerLabel, port);
        ACTIVE.put(port, server);
        try {
            server.setSoTimeout((int) Math.min(timeoutMs, Integer.MAX_VALUE));
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                final Socket client = server.accept();
                String requestLine;
                BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), "UTF-8"));
                requestLine = in.readLine();
                String path = "/";
                if (requestLine != null && requestLine.startsWith("GET ")) {
                    int http = requestLine.indexOf(" HTTP/");
                    path = requestLine.substring(4, http > 0 ? http : requestLine.length());
                }
                String code = queryParam(path, "code");
                String state = queryParam(path, "state");
                String error = queryParam(path, "error");
                String errorDesc = queryParam(path, "error_description");
                boolean ok = error == null && code != null && !code.isEmpty();
                String title = ok ? "登录完成" : "登录未完成";
                String detail = ok ? "可以回到 CLIProxyAPI 应用了。" : ("原因：" + (error == null ? "没有拿到授权码" : error + " " + (errorDesc == null ? "" : errorDesc)));
                String body = "<html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                        + "</head><body style=\"font-family:system-ui,sans-serif;background:#0E1116;color:#E9EEF6;padding:28px\">"
                        + "<h2>" + providerLabel + " " + title + "</h2><p style=\"color:#8B95A7\">" + detail + "</p></body></html>";
                OutputStream out = client.getOutputStream();
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "
                        + body.getBytes("UTF-8").length + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
                out.write(body.getBytes("UTF-8"));
                out.flush();
                client.close();

                if (error != null) throw new IllegalStateException("授权被拒绝：" + error + " " + (errorDesc == null ? "" : errorDesc));
                if (code != null && !code.isEmpty()) {
                    if (expectedState != null && state != null && !expectedState.equals(state)) {
                        Log.w(providerLabel + " 回调 state 不匹配，已忽略");
                        continue;
                    }
                    return code;
                }
            }
            throw new IllegalStateException(providerLabel + " 登录超时，请重试");
        } finally {
            try {
                server.close();
            } catch (Exception ignored) {
            }
            if (ACTIVE.get(port) == server) ACTIVE.remove(port);
        }
    }

    /** A previous attempt can leave the port bound until its timeout, so take it back deliberately. */
    private static ServerSocket bind(String providerLabel, int port) throws Exception {
        try {
            return new ServerSocket(port);
        } catch (Exception first) {
            Log.w(providerLabel + " 回调端口 " + port + " 被占用，先释放旧监听：" + first.getMessage());
            cancel(port);
            Thread.sleep(300);
            return new ServerSocket(port);
        }
    }

    /**
     * Waits for a raw callback (path + query) instead of an OAuth "code": Trae hands the credential
     * back as refreshToken / userInfo / userJwt query parameters.
     */
    public static String waitForRawQuery(String providerLabel, int port, long timeoutMs) throws Exception {
        ServerSocket server = bind(providerLabel, port);
        ACTIVE.put(port, server);
        try {
            server.setSoTimeout((int) Math.min(timeoutMs, Integer.MAX_VALUE));
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                Socket client = server.accept();
                BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), "UTF-8"));
                String requestLine = in.readLine();
                String target = "";
                if (requestLine != null && requestLine.startsWith("GET ")) {
                    int http = requestLine.indexOf(" HTTP/");
                    target = requestLine.substring(4, http > 0 ? http : requestLine.length());
                }
                String body = "<html><head><meta charset=\"utf-8\"></head><body style=\"font-family:sans-serif;background:#0E1116;color:#E9EEF6;padding:28px\">"
                        + "<h2>" + providerLabel + " 登录完成</h2><p style=\"color:#8B95A7\">可以回到 CLIProxyAPI 应用了。</p></body></html>";
                java.io.OutputStream out = client.getOutputStream();
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "
                        + body.getBytes("UTF-8").length + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
                out.write(body.getBytes("UTF-8"));
                out.flush();
                client.close();
                if (target.contains("refreshToken") || target.contains("userJwt") || target.contains("userInfo")) {
                    return target;
                }
                Log.i(providerLabel + " 收到不含凭据的回调，继续等待：" + target);
            }
            throw new IllegalStateException(providerLabel + " 登录超时，请重试");
        } finally {
            try {
                server.close();
            } catch (Exception ignored) {
            }
            if (ACTIVE.get(port) == null || ACTIVE.get(port).isClosed()) ACTIVE.remove(port);
        }
    }

    public static String queryParam(String pathOrUrl, String name) {
        int q = pathOrUrl == null ? -1 : pathOrUrl.indexOf('?');
        if (q < 0) return null;
        for (String kv : pathOrUrl.substring(q + 1).split("&")) {
            int eq = kv.indexOf('=');
            if (eq <= 0) continue;
            try {
                if (name.equals(URLDecoder.decode(kv.substring(0, eq), "UTF-8"))) {
                    return URLDecoder.decode(kv.substring(eq + 1), "UTF-8");
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }
}
