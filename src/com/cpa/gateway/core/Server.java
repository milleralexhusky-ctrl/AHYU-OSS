package com.cpa.gateway.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal dependency-free HTTP/1.1 server that exposes the three protocol surfaces
 * (OpenAI, Anthropic, Gemini) over one set of upstream channels.
 */
// public：MainActivity（不同包）要调 runAccountChores()，4.28.0 引入调用时漏改类可见性
public final class Server {

    private final Config config;

    /**
     * 配置指纹：账号、模型、坏模型变了它就变。
     *
     * /health 和 /v1/models 的 JSON 完全由配置决定，之前每个请求都重新拼一遍
     * （实测 /v1/models 6.8ms、/health 4.6ms，其中大头就是拼 JSON 与遍历模型）。
     * 配置没动就直接把上一次的字符串发出去。
     */
    private long configStamp() {
        long h = 17;
        for (Config.Provider p : config.providers) {
            h = h * 31 + p.name.hashCode();
            h = h * 31 + p.type.hashCode();
            h = h * 31 + (p.enabled ? 1 : 0);
            /* catalogOnly 会改变 /v1/models 的内容（该账号的模型是否对外出现），
               不进指纹的话，用户切换"仅目录可用"后缓存不会失效。 */
            h = h * 31 + (p.catalogOnly ? 1 : 0);
            h = h * 31 + p.models.hashCode();
            h = h * 31 + p.badModels.hashCode();
        }
        return h;
    }

    // 两个接口各存各的 stamp：共用一个的话，配置变更后先被访问的那个把 stamp 刷新掉，
    // 后一个看到 stamp 相同就直接吐自己那份旧 JSON（新加的模型会一直看不到）。
    /** 请求体上限（8MB）：够大模型的长上下文，又不至于被一个 Content-Length 打爆内存。 */
    private static final int MAX_BODY_BYTES = 8 * 1024 * 1024;

    private volatile long cacheHealthStamp = Long.MIN_VALUE;
    private volatile long cacheModelsStamp = Long.MIN_VALUE;
    private volatile String cacheHealthJson;
    private volatile String cacheModelsJson;
    private ServerSocket socket;
    /** IPv6 回环监听（::1）：客户端写 localhost 时经常会先解析到它。失败不影响主监听。 */
    private volatile ServerSocket socket6;
    private volatile boolean running;
    private volatile Thread acceptThread;
    private volatile Thread acceptThread6;
    private final AtomicInteger rr = new AtomicInteger();

    Server(Config config) {
        this.config = config;
    }

    int port() {
        return socket == null ? 0 : socket.getLocalPort();
    }

    boolean isRunning() {
        return running;
    }

    /**
     * 进程内健康快照：监听没关、accept 线程还活着、连接池没被占满。
     *
     * 看门狗原来每 45 秒连一次自己的 socket 来判断"还活着吗"——那是要建连接、起线程、
     * 走一遍完整 HTTP 的。绝大多数轮次其实只需要看这几个字段：真正的"假活"（accept
     * 线程死了、监听被关、池子占满）在这里就能看穿，只有少数轮次才值得真连一次做端到端确认。
     */
    boolean healthy() {
        if (!running) return false;
        ServerSocket sock = socket;
        if (sock == null || sock.isClosed() || !sock.isBound()) return false;
        Thread t = acceptThread;
        if (t == null || !t.isAlive()) return false;
        /*
         * 原来拿 MAX_CONN 当健康判据，但 MAX_CONN 是个静态上限：
         * 轻载时"活跃 10 条 < 64"永远为真，健康检查形同虚设；
         * 重载时又可能把"已经接近容量"误判成健康。
         * 现在用 Capacity.suggest() 算出的**当前**容量，判据跟着负载走。
         */
        return connPool.getActiveCount() < Capacity.suggest();
    }

    void start() throws Exception {
        String host = config.lanAccess ? "0.0.0.0" : "127.0.0.1";
        /*
         * bind 失败时必须把半成品清掉。
         *
         * 原来 socket 先被赋值、再 bind；一旦 bind 抛异常（端口被占用、权限不足），
         * 这个**未绑定**的 ServerSocket 就留在字段上，而 running 还是 false。
         * 于是 port() 返回 -1、healthy() 因为 !running 返回 false —— 看门狗把这次
         * 启动记成失败，客户端照旧不停重连，日志里只剩一条 bind 异常。
         * 现在：局部变量先建好、绑好，全部成功之后才发布到字段上。
         */
        ServerSocket sock = new ServerSocket();
        try {
            sock.setReuseAddress(true);
            sock.bind(new InetSocketAddress(host, config.port), 128);
        } catch (Exception e) {
            try {
                sock.close();
            } catch (Exception ignored) {
            }
            throw e;
        }
        /* 本机模式下同时监听 ::1：有的客户端把 localhost 解析成 IPv6，
           只绑 127.0.0.1 会表现为"地址没错但连不上"。局域网模式不绑 ::，
           避免与 0.0.0.0 的 dual-stack 冲突。 */
        ServerSocket sock6 = null;
        if (!config.lanAccess) {
            try {
                ServerSocket s6 = new ServerSocket();
                s6.setReuseAddress(true);
                s6.bind(new InetSocketAddress(java.net.InetAddress.getByName("::1"),
                        config.port), 128);
                sock6 = s6;
            } catch (Throwable t) {
                Log.i("IPv6 回环未启用（不影响 127.0.0.1）：" + t.getMessage());
            }
        }

        socket = sock;
        socket6 = sock6;
        running = true;
        Log.i("gateway listening on " + host + ":" + config.port
                + (sock6 == null ? "" : " + [::1]")
                + (config.lanAccess ? " (LAN)" : " (localhost only)"));
        ensureStreamWatchdog();
        Thread accept = new Thread(() -> acceptLoop(sock), "cpa-accept");
        accept.setDaemon(true);
        acceptThread = accept;
        accept.start();
        if (sock6 != null) {
            final ServerSocket s6 = sock6;
            Thread accept6 = new Thread(() -> acceptLoop(s6), "cpa-accept6");
            accept6.setDaemon(true);
            acceptThread6 = accept6;
            accept6.start();
        }
    }

    void stop() {
        running = false;
        try {
            connPool.shutdownNow();
        } catch (Throwable ignored) {
        }
        ServerSocket sock = socket;
        socket = null;
        try {
            if (sock != null) sock.close();
        } catch (Exception ignored) {
        }
        ServerSocket sock6 = socket6;
        socket6 = null;
        try {
            if (sock6 != null) sock6.close();
        } catch (Exception ignored) {
        }
        /*
         * 有界地等 accept 线程退场（最多 500ms）。
         *
         * 不等的话，"先停后起"会留下一段两个 cpa-accept 线程并存的窗口：老线程刚被
         * close() 唤醒、还在 catch 里，新线程已经在同一个端口上 accept。两边都能跑，
         * 表面上没毛病，但连接会被谁抢到不确定，出问题时极难复现。
         *
         * 用 join 而不是 sleep：线程真的走掉了才继续；万一它卡在别处（理论上不该有），
         * 500ms 后照样往下走，不会把 stop() 拖成阻塞接口。
         */
        Thread t = acceptThread;
        acceptThread = null;
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            if (t.isAlive()) Log.w("accept 线程未在 500ms 内退出（旧监听已关闭，不影响新实例）");
        }
        Thread t6 = acceptThread6;
        acceptThread6 = null;
        if (t6 != null && t6 != Thread.currentThread()) {
            try {
                t6.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        /* 关闭所有已建立连接：只关监听的话，旧 keep-alive 连接还能继续用旧配置发请求。 */
        for (Socket s : new java.util.ArrayList<>(ACTIVE_SOCKS)) {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }
        ACTIVE_SOCKS.clear();
        Log.i("gateway stopped");
    }

    /**
     * 连接处理池。
     *
     * 原来是"每来一个连接就开一条线程"，而且 keep-alive 空闲连接最长占线程 5 分钟——
     * 客户端一多（浏览器/编辑器各开几条）线程就堆起来，最后表现成"用着用着就断了"。
     * 这里给个上限：池子按需涨到 MAX_CONN，超了就直接回 503，而不是把机器拖死。
     *
     * 上限回到 64：手机端的真实并发就这么点，128 只是让"泄漏连接的客户端"多占一倍
     * 线程；空闲 30 秒回收，够用了。
     */
    private static final int MAX_CONN = 64;

    private final java.util.concurrent.ThreadPoolExecutor connPool =
            new java.util.concurrent.ThreadPoolExecutor(
                    4, MAX_CONN, 30L, java.util.concurrent.TimeUnit.SECONDS,
                    new java.util.concurrent.SynchronousQueue<>(),
                    r -> {
                        Thread t = new Thread(r, "cpa-conn");
                        t.setDaemon(true);
                        return t;
                    });

    private void acceptLoop(ServerSocket sock) {
        while (running) {
            /* 监听由参数传入：IPv4 与 IPv6 回环各一条 accept 线程，共用同一个连接池。
               stop() 关闭对应 socket 后 accept() 抛异常，这里按 running=false 正常退场。 */
            if (sock == null || sock.isClosed()) return;
            try {
                final Socket s = sock.accept();
                try {
                    connPool.execute(() -> handle(s));
                } catch (java.util.concurrent.RejectedExecutionException full) {
                    Log.w("连接数已到上限 " + MAX_CONN + "，拒绝一个连接（客户端 "
                            + s.getInetAddress() + "）· " + Capacity.state());
                    try {
                        s.getOutputStream().write(("HTTP/1.1 503 Service Unavailable\r\n"
                                + "Content-Length: 0\r\nConnection: close\r\n\r\n")
                                .getBytes(StandardCharsets.UTF_8));
                        s.getOutputStream().flush();
                    } catch (Throwable ignored) {
                    }
                    try {
                        s.close();
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable e) {
                /* 停下来之后 socket.close() 会让 accept() 抛 SocketException，那是正常退场。 */
                if (!running) return;
                Log.w("accept failed: " + e);
                try {
                    Thread.sleep(120);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
        }
    }

    /** 统计的「成功」指这次请求向客户端交付了正常响应，而不是「代码没有抛异常」。 */
    /**
     * 工具补偿（4.32.0）：把写在正文里的工具调用换成真正的 tool_call。
     *
     * 解决的问题：弱模型（和一部分中转站的降级实现）不会吐结构化 tool_calls，
     * 而是输出 `{"name":"x","arguments":{…}}` 或者 `<tool_call>…</tool_call>` 这样的正文。
     * 客户端拿到这种文本没法执行 —— 以前只能判成"打嘴炮"、换模型重来，
     * 模型那份意图白费，用户那边表现为"这个模型不支持工具"。
     *
     * 判据刻意保守（换错的代价比漏换大得多）：
     *   · 请求必须真的声明了 tools（否则正文里出现 JSON 是正常回答，不能碰）；
     *   · 模型没有吐出任何结构化 tool_call（否则它是正常的，不要覆盖）；
     *   · 提取到的名字必须在客户端声明的工具名里；
     *   · 正文很短（< 2000 字符）：一封长信里出现一个 JSON 多半不是调用意图。
     *
     * 成功替换时会把正文清空 —— 否则客户端会同时收到"一段解释 + 一个调用"，
     * 而那段解释里往往还带着那段 JSON，重复且容易让模型自问自答。
     */
    private static void compensateToolCall(Proto.Req req, Fronts.Collector col) {
        if (req == null || req.tools == null || req.tools.isEmpty()) return;
        if (col == null || col.sawToolCall()) return;
        String body = col.seenText();
        if (body == null || body.isEmpty() || body.length() > 2000) return;
        if (!ToolAbility.looksLikeToolCall(body)) return;

        java.util.Set<String> allowed = new java.util.LinkedHashSet<>();
        for (Proto.Tool t : req.tools) {
            if (t != null && t.name != null && !t.name.isEmpty()) allowed.add(t.name);
        }
        if (allowed.isEmpty()) return;

        ToolCallText.Hit hit = ToolCallText.extract(body, allowed);
        if (hit == null) return;

        String args = hit.args == null || hit.args.isEmpty() ? "{}" : hit.args;
        String id = "call_" + Math.abs(System.nanoTime() % 1000000);
        col.toolCall(id, hit.name, args);
        col.replaceText("");
        /* 结束原因改成 tool_calls：客户端据此决定继续循环，
           留成 stop 会让它以为这轮结束了。 */
        if (col.stop == null || "stop".equals(col.stop)) col.stop = "tool_calls";
        Log.i("工具补偿：正文里的调用已转换为 tool_call（" + hit.name + "）");
    }

    /**
     * 判定这次上游响应属于哪种形态（4.33.0）。
     *
     * 为什么必须单独记：在旧数据里，"模型返回了结构化调用"、"模型把调用写进正文"、
     * "模型什么都没说"三者全都表现为「工具=0」。而这三件事的含义完全相反——
     * 第一种是我们接住了，第二种是需要解析，第三种是上游真没给。
     */
    /** 本次请求的工具名清单，逗号分隔（只记名字，不含 schema）。 */
    private static String toolNameList(Proto.Req req) {
        if (req == null || req.tools == null || req.tools.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Proto.Tool t : req.tools) {
            if (t == null || t.name == null || t.name.isEmpty()) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(t.name);
            if (sb.length() > 300) break;      // 别让超长清单撑爆一行
        }
        return sb.toString();
    }

    /** 最后一条用户消息的正文（判"答非所问"时和回答对照）。 */
    private static String lastUserText(Proto.Req req) {
        if (req == null || req.messages == null) return "";
        for (int i = req.messages.size() - 1; i >= 0; i--) {
            Proto.Msg m = req.messages.get(i);
            if (m == null || m.parts == null) continue;
            if (!"user".equals(m.role)) continue;
            StringBuilder sb = new StringBuilder();
            for (Proto.Part p : m.parts) {
                if (p != null && Proto.Part.TEXT.equals(p.type) && p.text != null) sb.append(p.text);
            }
            if (sb.length() > 0) return sb.toString();
        }
        return "";
    }

    /** 历史里是否已经含工具调用或工具结果（多轮工具循环的第 2 轮及以后）。 */
    private static boolean hasToolHistory(Proto.Req req) {
        if (req == null || req.messages == null) return false;
        for (Proto.Msg m : req.messages) {
            if (m == null || m.parts == null) continue;
            for (Proto.Part part : m.parts) {
                if (part == null) continue;
                if (Proto.Part.TOOL_CALL.equals(part.type)
                        || Proto.Part.TOOL_RESULT.equals(part.type)) return true;
            }
        }
        return false;
    }

    private static String shapeOf(Conn c) {
        if (c == null) return "";
        if (c.responseShape != null && !c.responseShape.isEmpty()) return c.responseShape;
        if (c.responseFailed) return "error";
        if (c.sawToolCall) return "native";
        if (c.outChars <= 0) return "empty";
        /* 正文里有调用标记但我们没转出来 —— 这就是"上游用了另一种语法"。
           直接复用 ToolAbility 的识别器（它认 <tool_call> / <invoke> / function_call 等）。 */
        if (c.seenTextHint != null && ToolAbility.looksLikeToolCall(c.seenTextHint)) return "text";
        return "plain";
    }

    static boolean usageSuccess(boolean routeOk, boolean hadProvider, boolean responseFailed) {
        return routeOk && hadProvider && !responseFailed;
    }

    /**
     * 把竞速胜者的观测字段搬到连接对象上。
     *
     * 三路 racer 以前共用同一个 Conn，并且各自无锁地 `+=`/赋值：outChars 会丢更新，
     * seenTextHint 会被交错拼接，渠道/模型/token/responseId 取决于最后写入者——
     * 一条竞速记录可能拿着输家的渠道名去污染质量统计。现在每路用自己的 Conn，
     * 只把胜者的观测合并回来。
     */
    private static void copyObs(Conn from, Conn to) {
        if (from == null || to == null) return;
        to.apiProvider = from.apiProvider;
        to.apiModel = from.apiModel;
        to.toolsSent = from.toolsSent;
        to.hasToolHistory = from.hasToolHistory;
        to.toolNames = from.toolNames;
        to.askedHint = from.askedHint;
        to.clientRetried = from.clientRetried;
        to.conversationId = from.conversationId;
        to.outChars = from.outChars;
        to.seenTextHint = from.seenTextHint;
        to.sawToolCall = from.sawToolCall;
        to.stopReason = from.stopReason;
        to.tokIn = from.tokIn;
        to.tokOut = from.tokOut;
        to.parseOutcome = from.parseOutcome;
        to.responseShape = from.responseShape;
        to.upstreamResponseId = from.upstreamResponseId;
        to.errorKind = from.errorKind;
    }

    /** 当前响应报告了错误，不影响后续同一 keep-alive 连接上的请求。 */
    private static void markResponseFailed(OutputStream out) {
        Conn c = connFor(out);
        if (c != null) c.responseFailed = true;
    }

    /* ----------------------------------------------------------- http plumbing */

    /** HTTP/1.1 chunked stream: lets a streaming response end without closing the socket. */
    private static final class ChunkedStream extends OutputStream {
        private final OutputStream out;
        private final java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        private boolean finished;

        ChunkedStream(OutputStream out) {
            this.out = out;
        }

        /* 同一把锁保护 buf 与 finished：竞速主线程可能在 180 秒超时后 finish()，
           而胜者 racer 仍在写。没有这个保护，终止块之后会出现新 chunk，
           或两个线程交叉写 chunk 头/body，客户端的帧解析直接坏掉。 */
        @Override
        public synchronized void write(int b) {
            if (finished) return;
            buf.write(b);
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            if (finished) return;
            buf.write(b, off, len);
        }

        @Override
        public synchronized void flush() throws java.io.IOException {
            if (finished) return;
            flushChunk(false);
        }

        private synchronized void flushChunk(boolean last) throws java.io.IOException {
            int n = buf.size();
            try {
                if (n > 0) {
                    // 直接把缓冲写进 socket：原来每次都 toByteArray() 复制一份（长回答
                    // 下来是几百到上千个短命数组，纯粹喂给 GC）
                    out.write((Integer.toHexString(n) + "\r\n").getBytes(StandardCharsets.UTF_8));
                    buf.writeTo(out);
                    out.write("\r\n".getBytes(StandardCharsets.UTF_8));
                    buf.reset();
                }
                if (last) {
                    out.write("0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                }
            } catch (java.io.IOException e) {
                // 写客户端失败 = 客户端不在了（断网 / 取消 / 切后台），**不是上游的问题**。
                // 转成 ClientGone，上层据此不记失败、不进熔断。
                throw new ClientGone("写客户端失败：" + e.getMessage());
            }
            if (last) {
                finished = true;
            }
            out.flush();
        }

        synchronized void finish() throws java.io.IOException {
            if (!finished) flushChunk(true);
        }

        @Override
        public synchronized void close() throws java.io.IOException {
            finish();
        }
    }

    /** Counts bytes written so a stalled upstream can be detected without touching the writers. */
    /**
     * 计数写端。**不要**继承 FilterOutputStream：某些 Android 版本的
     * FilterOutputStream.write(byte[],off,len) 会按字节转发，21KB 响应会变成
     * 两万次小写入（实测 241ms）；直接委托底层 write 后是 0–2ms。
     */
    private static final class CountingStream extends OutputStream {
        private final OutputStream out;
        volatile long count;

        CountingStream(OutputStream out) {
            this.out = out;
        }

        @Override
        public void write(int b) throws java.io.IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws java.io.IOException {
            out.write(b, off, len);
            count += len;
        }

        @Override
        public void flush() throws java.io.IOException {
            out.flush();
        }

        @Override
        public void close() throws java.io.IOException {
            out.close();
        }
    }

    /** One client connection. Kept alive across requests unless the client (or SSE) says otherwise. */
    private static final class Conn {
        final OutputStream out;
        final Socket sock;
        CountingStream counter;
        ChunkedStream chunked;
        /** 本次响应是否走 chunked 编码（写"流内错误"时要按分帧写，否则会写坏流）。 */
        volatile boolean chunkedEncoding;
        volatile boolean keepAlive = true;
        volatile boolean sse;
        /* 这一次请求的归属：用量统计用（serve/pick 里填，handle 完成后取走并清空）。 */
        volatile String apiModel = "";
        /** 这次请求用的客户端 Key（脱敏后的标签），只用于用量统计。 */
        volatile String apiKeyLabel = "";
        volatile String apiProvider = "";
        /** 当前请求已向客户端报告失败；即使 HTTP/SSE 层没抛异常也不能记作成功。 */
        volatile boolean responseFailed;
        volatile int tokIn;
        volatile int tokOut;
        /* 4.32.0 内容信号：三样都不依赖上游返回 usage，全从 Sink 回调里数出来。
           为什么需要它们见 Usage 的列说明——前九列没有一列能反映"回答内容行不行"。 */
        /** 本次响应流给客户端的正文字符数（增量累加）。 */
        volatile int outChars;
        /** 本次有没有真吐出结构化 tool_calls。 */
        volatile boolean sawToolCall;
        /** 上游给的结束原因（stop / length / tool_calls …）；空 = 不知道。 */
        volatile String stopReason = "";
        /* 4.33.0 观测上下文：把"发了什么 → 回了什么形态 → 怎么处理的"串起来。
           没有这三样，"模型会不会调工具"就只能靠猜（见 Usage.Obs 的说明）。 */
        /** 本次请求带了多少个工具定义。 */
        volatile int toolsSent;
        /** 对话历史里是否已含工具调用／工具结果。 */
        volatile boolean hasToolHistory;
        /** 我们自己的解析结果：none / ok / fail / fixed / dropped。 */
        volatile String parseOutcome = "none";
        /** 上游响应形态：native / text / empty / error / plain。 */
        volatile String responseShape = "";
        /** 正文前若干字符，仅供判定"是不是把调用写进了正文"。截断保存，不占内存。 */
        volatile String seenTextHint = "";
        /* 4.35.0 结果质量：请求侧留一份正文样本（判跑题用），
           以及客户端是否立刻重发过（最真实的"用户觉得不行"信号）。 */
        volatile String askedHint = "";
        volatile boolean clientRetried;
        /* 4.36.0 可追溯标识：
           responseId 是上游给的 requestId（和上游对账的唯一键）；
           conversationId 是我们自己的会话标识，用来把同一轮多跳串起来。 */
        volatile String upstreamResponseId = "";
        volatile String conversationId = "";
        /** 本次工具名清单（逗号分隔）。只记名字：完整 schema 太长会撑爆用量文件。 */
        volatile String toolNames = "";
        /** 本次错误的类别（4.36.1）。catch 里和 writeError 里都可能写。 */
        volatile String errorKind = "";
        /** 最近一次心跳真的写出去了的时间；写被阻塞时它就不动了。 */
        volatile long lastPing = System.currentTimeMillis();
        /**
         * 最近一次**上游真的给了内容**的时间。
         *
         * 不能拿 CountingStream 的字节数判断"上游静默"：那个计数连我们自己的
         * `: ping` 也算，于是每轮都"有变化"，stalls 永远不会增长，180 秒超时形同虚设。
         */
        volatile long lastUpstreamAt = System.currentTimeMillis();
        java.util.concurrent.ScheduledFuture<?> pinger;

        Conn(OutputStream out, Socket sock) {
            this.out = out;
            this.sock = sock;
        }

        /** Ends a chunked stream but leaves the socket open for the next request. */
        void endStream() {
            stopPing();
            try {
                if (chunked != null) chunked.finish();
            } catch (Exception ignored) {
            }
            chunked = null;
            sse = false;
        }

        void stopPing() {
            if (pinger != null) {
                pinger.cancel(false);
                pinger = null;
            }
        }
    }

    /** 心跳周期：每这么多毫秒写一次 ": ping"。阈值和扫描周期都由它派生。 */
    private static final long PING_PERIOD_MS = 10_000L;

    /**
     * 僵尸流看门狗：心跳写不出去的长连接，别让它把心跳线程一起拖死。
     *
     * 心跳线程是共享的（Threads.PING 只有 **2** 条）：客户端假死／切网／进程被杀后，
     * 一次 ": ping" 写会永久阻塞在 socket 上，白占一条线程；**只要 2 条僵尸流同时存在，
     * 所有正常流就都没有心跳了**，用户看到的是"流突然不动了"，然后被客户端判超时。
     *
     * 阈值原来写死 45 秒。用泊松模型算过：判定延迟 D = 阈值 S + 扫描周期 P，
     * 僵尸流按 λ 到达，同时在飞的僵尸数服从 Poisson(λD/60)；
     * "僵尸数 ≥ 池大小"就全灭。
     *
     *     设 λ = 0.5 个/分钟（切网、后台被杀这类事件的量级）：
     *         S=45 P=30 → D=75 → 池2 全灭 5.86 次/小时
     *         S=30 P=15 → D=45 → 池2 全灭 2.47 次/小时
     *         S=30 P=10 → D=40 → 池2 全灭 2.24 次/小时
     *
     * 所以缩短判定延迟的收益是**线性以上**的。但阈值不能乱缩：正常流的 ping
     * 每 10 秒必写一次（见 ping 任务：无论 idle 与否都 write），所以 S 取
     * 3 个周期 = 30 秒，允许丢 2 次仍不误杀。
     *
     * 扫描周期同步缩到 10 秒：它只遍历 CONNS（通常个位数），开销可以忽略，
     * 而 D 每少 10 秒，全灭概率就少一截。
     */
    private static final long STREAM_PING_STALL_MS = PING_PERIOD_MS * 3;

    /**
     * 上游多久不出字就判定"它不会出了"，主动结束这条流。
     *
     * 原来写死 stalls >= 18（18 个 ping 周期 = 180 秒）。用真机 3361 条真实
     * 请求的耗时分布验过这个值：
     *     p99    = 72.8s
     *     p99.9  = 147.9s
     *     >180s  = 1 条（0.03%）
     *
     * 也就是说 180 秒刚好在 p99.9 之上一点点 —— 能容忍推理模型思考两分多钟，
     * 又能把真正卡死的连接在 3 分钟内清掉。这个值**不需要改**，
     * 但应该从 PING_PERIOD_MS 派生而不是写 18，否则改 ping 周期时它会静默失效。
     */
    private static final int UPSTREAM_IDLE_PERIODS = 18;
    private static final java.util.concurrent.atomic.AtomicBoolean STREAM_WATCHDOG =
            new java.util.concurrent.atomic.AtomicBoolean();

    private static void ensureStreamWatchdog() {
        if (!STREAM_WATCHDOG.compareAndSet(false, true)) return;
        Threads.BG.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            for (Conn c : CONNS.values()) {
                if (!c.sse) continue;
                if (now - c.lastPing < STREAM_PING_STALL_MS) continue;
                Log.w("流式连接超过 " + (STREAM_PING_STALL_MS / 1000)
                        + " 秒没能写出心跳，判定客户端已断，关闭这条流");
                c.stopPing();
                try {
                    c.sock.close();
                } catch (Throwable ignored) {
                }
            }
        }, 10, 10, java.util.concurrent.TimeUnit.SECONDS);
    }

    private static final ThreadLocal<Conn> CURRENT = new ThreadLocal<>();

    /**
     * 连接空闲回收时间。
     *
     * 这个数字同时决定响应头里的 Keep-Alive: timeout=，两边必须同源：原来是头写
     * timeout=300 而这里 90 秒就回收，客户端于是把连接在池里留 300 秒、复用一条
     * 服务端早已半关的 socket，表现为随机 connection reset / 莫名其妙的失败重试。
     * 现在统一 60 秒：回收更积极，泄漏的连接最多占线程 1 分钟。
     *
     * 60 秒这个值用真机数据验过，**结论是不要动**。3360 个真实请求间隔
     * （p50=7s、p90=50s、p99=1675s）算 keep-alive 复用率：
     *
     *     T=10s → 62.6%      T=60s  → 91.3%
     *     T=30s → 84.3%      T=120s → 94.6%   T=300s → 97.1%
     *
     * 60 秒之后边际收益开始明显递减（+7.0% → +3.3% → +2.5%），
     * 而空转线程的期望数一直很小（T=60s 时 0.38 条，相对 64 的池可以忽略）。
     *
     * 试过用"建连成本 × 新建率 + 线程成本 × 空转数"求最优 T，但那两个权重
     * 都是拍出来的，算出的"最优 T=5s"没有可信度 —— **用拍的权重求出的最优值，
     * 和直接拍一个值没有区别**。所以保持 60 秒，把这条检验记录留在这里，
     * 免得以后有人以为它是随便写的又想改。
     */
    private static final int KEEPALIVE_IDLE_MS = 60000;
    /** 一条连接最多复用多少次（和响应头 max= 同源）。 */
    private static final int KEEPALIVE_MAX_REQUESTS = 200;

    /**
     * 连接建立后等第一个请求的时间。
     *
     * 原来直接沿用 keep-alive 的空闲回收时间（60 秒）：一个"连上就不说话"的客户端会把线程占满 60 秒。
     * 一小时压测里撞上了——出现 1841 次"连接数已到上限"、线程涨到 94、Native Heap 涨到 58MB，
     * 全是被撑住的空连接。首请求只给 10 秒，回收快得多。
     */
    private static final int FIRST_REQUEST_MS = 10_000;

    /*
     * 上面这个值用 Little 定律验过：期望空连接数 = 建立率 x 等待时长。
     * 实测建立率 0.0067/秒（25 请求/小时），所以 T=10s 时期望只有 0.067 个空连接，
     * T=60s 也才 0.402 个 —— 两者都远小于容量，**说明它不是性能瓶颈**。
     * 它的真实作用是防恶意/泄漏连接的兜底，不是调优参数，所以 10 秒不动。
     */

    /** 压力高（在飞数 / 建议容量 超过这个比例）时，首请求时限减半。 */
    private static final double FIRST_REQ_TIGHT_PRESSURE = 0.8;

    /**
     * 首请求等待时限，随压力自适应。
     *
     * 直觉：空连接占用的是**同一批**服务台。当在飞请求已经接近容量时，
     * 一条空等的连接每多占 1 秒就多 1 秒的损失。所以压力高时减半，
     * 压力低时保留宽松值（避免把正常客户端的慢启动误杀）。
     */
    private static int firstRequestMs() {
        return Capacity.pressure() >= FIRST_REQ_TIGHT_PRESSURE
                ? FIRST_REQUEST_MS / 2
                : FIRST_REQUEST_MS;
    }

    /**
     * 同一个客户端地址最多占多少条连接。
     *
     * 目的不是"限流"，而是**防止一个泄漏连接的客户端把整池吃光**，
     * 剩下的留给正常客户端。所以它本质上是"总容量里允许单个客户端占的份额"。
     *
     * 原来写死 24。但总容量 MAX_CONN=64 时 24 是 37.5%；
     * 如果以后按 Capacity 把总容量降到 8，24 就变成 300% —— 上限失去意义。
     * 所以改成由总容量派生，并留一个绝对下限（浏览器/编辑器并发几条是常态，
     * 低于 8 会把正常客户端误伤）。
     */
    private static final double PEER_SHARE = 0.375;   /* 24/64，保持原来的份额 */
    private static final int PEER_FLOOR = 8;

    private static int maxPerPeer(String peerKey) {
        /* 回环地址不算"一个客户端"：本机上所有应用（编辑器、浏览器、桌面壳、
           自动化脚本）都从 127.0.0.1 / ::1 进来，共用一个 peer key。
           按普通客户端配额只给 8 条，两个应用各开几条 keep-alive 就互相挤掉了。
           本机流量是用户自己的，给到总容量减去给局域网预留的那一份（56/64），
           仍然保留上限，防止某个泄漏连接的本地应用把整池吃光。 */
        if (isLoopbackPeer(peerKey)) return Math.max(PEER_FLOOR, MAX_CONN - PEER_FLOOR);
        int cap = Capacity.suggest();
        int v = (int) Math.ceil(cap * PEER_SHARE);
        return Math.max(PEER_FLOOR, Math.min(v, Math.max(PEER_FLOOR, cap)));
    }

    /** 这个 peer key 是不是本机回环（IPv4 / IPv6 / IPv4-mapped）。 */
    static boolean isLoopbackPeer(String peerKey) {
        if (peerKey == null) return false;
        String s = peerKey.startsWith("/") ? peerKey.substring(1) : peerKey;
        return s.startsWith("127.") || "::1".equals(s) || "0:0:0:0:0:0:0:1".equals(s)
                || s.startsWith("::ffff:127.") || "localhost".equalsIgnoreCase(s);
    }

    /**
     * 连接表：按响应流反查它属于哪条连接。
     *
     * 原来 Conn 只放在处理线程的 ThreadLocal 里。可请求并不总在同一个线程上收尾——
     * 竞速时 DeferredWriter 是在 RACE_POOL 线程里提交响应头的，那一刻 CURRENT.get()
     * 是 null，于是：SSE 状态没登记（连接层不知道这是流）、心跳没起（长思考的客户端
     * 看不到 ping，自己判超时）、流内错误走了 HTTP 错误分支（往已提交的 SSE 流里写
     * 状态行，直接把流写坏）。现在按 OutputStream 查表，跨线程也能拿到同一条连接。
     */
    private static final java.util.concurrent.ConcurrentHashMap<OutputStream, Conn> CONNS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 已建立的客户端连接。stop() 要能主动关掉它们：
     * 只关监听 socket 的话，keep-alive 连接还能继续发请求，
     * 而且用的是 Server 构造时捕获的旧 Config（关局域网/换 Key 后旧策略仍然生效）。
     */
    private final java.util.Set<Socket> ACTIVE_SOCKS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 这条响应流属于哪条连接：先查表（跨线程可靠），退化到当前线程。 */
    private static Conn connFor(OutputStream out) {
        if (out != null) {
            Conn c = CONNS.get(out);
            if (c != null) return c;
        }
        return CURRENT.get();
    }

    static final class Req {
        String method = "GET";
        String path = "/";
        String query = "";
        boolean keepAlive = true;
        final Map<String, String> headers = new HashMap<>();
        String body = "";
        /** 原始请求体字节：multipart / 音频这类不能按 UTF-8 文本转发。 */
        byte[] bodyBytes;

        String header(String name) {
            String v = headers.get(name.toLowerCase(Locale.US));
            return v == null ? "" : v;
        }
    }

    /** 单行上限：请求行/头/分块长度都走这里。 */
    private static final int MAX_LINE_BYTES = 64 * 1024;
    /** 整个请求头的上限：防止"很多条 64KB 头"把内存堆满。 */
    private static final int MAX_HEADER_BYTES = 256 * 1024;
    private static final int MAX_HEADER_COUNT = 256;

    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') bos.write(c);
            /* 以前超限就 break 返回前半行，剩下的字节留在流里，
               下一条 readLine 会把它当成新的请求行 —— keep-alive 连接从此错位。
               超长行直接抛异常，让这条连接关闭，不留下解析歧义。 */
            if (bos.size() > MAX_LINE_BYTES) {
                throw new java.io.IOException("HTTP line too long (> " + MAX_LINE_BYTES + " bytes)");
            }
        }
        if (c == -1 && bos.size() == 0) return null;
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    static Req readRequest(InputStream raw) throws Exception {
        // 不能每次请求都新包一层 BufferedInputStream：内层缓冲会预读超过本次请求
        // 的字节，而这层包装随着本次调用结束就被丢掉，预读到的那部分（下一条
        // 流水线请求、或者同一 TCP 段里跟过来的下一请求）就凭空消失了。
        // 复用外层那个长生命周期的缓冲，预读字节才能留给下一次 readRequest。
        BufferedInputStream in = raw instanceof BufferedInputStream
                ? (BufferedInputStream) raw
                : new BufferedInputStream(raw, 8192);
        String line = readLine(in);
        if (line == null) return null;
        Req r = new Req();
        String[] parts = line.split(" ");
        if (parts.length >= 2) {
            r.method = parts[0].toUpperCase(Locale.US);
            String target = parts[1];
            int q = target.indexOf('?');
            if (q >= 0) {
                r.path = target.substring(0, q);
                r.query = target.substring(q + 1);
            } else {
                r.path = target;
            }
        }
        String h;
        int headerBytes = 0;
        int headerCount = 0;
        boolean headerEnd = false;
        while ((h = readLine(in)) != null) {
            if (h.isEmpty()) {
                headerEnd = true;
                break;
            }
            headerCount++;
            headerBytes += h.length();
            /* 单行有上限，但"很多条合法长度"的头仍然能堆满内存；总量也要挡。 */
            if (headerCount > MAX_HEADER_COUNT || headerBytes > MAX_HEADER_BYTES) {
                throw new java.io.IOException("request headers too large ("
                        + headerCount + " headers, " + headerBytes + " bytes)");
            }
            int c = h.indexOf(':');
            if (c > 0) {
                String name = h.substring(0, c).trim().toLowerCase(Locale.US);
                String value = h.substring(c + 1).trim();
                /* 重复的 Content-Length / Transfer-Encoding 是请求走私的经典入口；
                   同一个值也不接受，直接拒绝。 */
                if (("content-length".equals(name) || "transfer-encoding".equals(name))
                        && r.headers.containsKey(name)) {
                    throw new java.io.IOException("duplicate " + name + " header");
                }
                r.headers.put(name, value);
            }
        }
        /* 头区没有以空行结束就 EOF：这是一条半截请求，不能当成完整请求执行。 */
        if (!headerEnd) throw new java.io.IOException("truncated request headers");
        String conn = r.headers.get("connection");
        String version = parts.length >= 3 ? parts[2] : "HTTP/1.1";
        if (conn != null && conn.toLowerCase(java.util.Locale.US).contains("close")) r.keepAlive = false;
        else if (version.startsWith("HTTP/1.0")) r.keepAlive = false;
        String te = r.headers.get("transfer-encoding");
        String cl = r.headers.get("content-length");
        /* TE 与 CL 同时出现是请求走私的典型形态；本地网关也不接受。 */
        if (te != null && cl != null) {
            throw new java.io.IOException("both Transfer-Encoding and Content-Length present");
        }
        boolean chunked = false;
        if (te != null) {
            for (String tok : te.split(",")) {
                String t = tok.trim().toLowerCase(Locale.US);
                if (t.isEmpty()) continue;
                /* 只支持 chunked；"gzip, chunked" 这类我们不会解 gzip，不能假装看得懂。 */
                if (!"chunked".equals(t)) {
                    throw new java.io.IOException("unsupported Transfer-Encoding: " + te);
                }
                chunked = true;
            }
            if (!chunked) throw new java.io.IOException("empty Transfer-Encoding");
        }
        if (chunked) {
            /* 必须按字节累积、最后一次性解码：每个 chunk 单独 new String(..., UTF_8)
               会在多字节字符正好被 chunk 边界切开时各自产生 U+FFFD，
               中文 prompt 被静默破坏（客户端完全看不出，模型只是"变笨"）。 */
            java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
            while (true) {
                String sizeLine = readLine(in);
                if (sizeLine == null) break;
                int semi = sizeLine.indexOf(';');
                if (semi >= 0) sizeLine = sizeLine.substring(0, semi);
                int size;
                try {
                    size = Integer.parseInt(sizeLine.trim(), 16);
                } catch (Exception e) {
                    /* 以前这里 break：把半截 chunk 当成完整请求返回，剩下的字节
                       留在流里被当成下一条请求 —— keep-alive 连接从此错位。 */
                    throw new java.io.IOException("invalid chunk size: " + sizeLine);
                }
                if (size < 0) throw new java.io.IOException("negative chunk size: " + size);
                if (size == 0) {
                    /* 0 块之后是 trailer，必须读到空行为止；只读一行会把剩下的
                       trailer 留成"下一个请求的请求行"，keep-alive 连接从此错位。 */
                    String trailer;
                    while ((trailer = readLine(in)) != null && !trailer.isEmpty()) {
                        // 忽略 trailer 内容，但要把它们从流里读干净
                    }
                    break;
                }
                // 单块上限：chunk 头里的 size 是客户端说了算的，
                // 直接 new byte[size] 能被一个 "ffffffff" 头打爆内存（Content-Length 那条已限，
                // 但 chunked 这条路绕过了它）。
                if (size > MAX_BODY_BYTES) {
                    Log.w("chunk 过大（" + size + " > " + MAX_BODY_BYTES + "），已拒绝");
                    throw new java.io.IOException("chunk too large: " + size);
                }
                // 累计上限：单块再小，无限发也能把内存撑爆；两边都要挡。
                if (body.size() + size > MAX_BODY_BYTES) {
                    Log.w("chunked 请求体累计过大（已收 " + body.size() + "），已拒绝");
                    throw new java.io.IOException("chunked payload too large: " + body.size());
                }
                byte[] buf = new byte[size];
                int read = 0;
                while (read < size) {
                    int n = in.read(buf, read, size - read);
                    if (n < 0) throw new java.io.IOException("truncated chunk body");
                    read += n;
                }
                body.write(buf, 0, read);
                String crlf = readLine(in);
                if (crlf == null) throw new java.io.IOException("truncated chunk terminator");
                if (!crlf.isEmpty()) throw new java.io.IOException("malformed chunk terminator");
            }
            byte[] all = body.toByteArray();
            r.bodyBytes = all;
            r.body = new String(all, StandardCharsets.UTF_8);
        } else {
            int len = 0;
            if (cl != null && !cl.trim().isEmpty()) {
                String v = cl.trim();
                /* 只接受纯十进制非负整数：负数/带符号/非数字都不猜，直接拒绝。
                   以前 parse 失败按 0 处理，body 字节留在流里会被当成下一请求。 */
                if (!v.matches("\\d+")) {
                    throw new java.io.IOException("invalid Content-Length: " + cl);
                }
                try {
                    len = Integer.parseInt(v);
                } catch (Exception e) {
                    throw new java.io.IOException("Content-Length out of range: " + cl);
                }
            }
            // 上限：以前按 Content-Length 直接 new byte[len]，开了局域网就是一个 OOM 攻击面
            // （本机模式无所谓，但 lanAccess 是用户随时可能打开的开关）。超限直接拒绝这次请求。
            if (len > MAX_BODY_BYTES) {
                Log.w("请求体过大（" + len + " > " + MAX_BODY_BYTES + "），已拒绝");
                throw new java.io.IOException("payload too large: " + len);
            }
            if (len > 0) {
                byte[] buf = new byte[len];
                int read = 0;
                while (read < len) {
                    int n = in.read(buf, read, len - read);
                    if (n < 0) break;
                    read += n;
                }
                r.bodyBytes = java.util.Arrays.copyOf(buf, read);
                r.body = new String(buf, 0, read, StandardCharsets.UTF_8);
            }
        }
        return r;
    }

    /** 每个客户端地址当前占用的连接数。 */
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>
            PER_PEER = new java.util.concurrent.ConcurrentHashMap<>();

    private static String peerKeyOf(String peer) {
        int at = peer.indexOf('/');
        return at > 0 ? peer.substring(at + 1) : peer;
    }

    private void handle(Socket s) {
        /* Capacity 按**请求**记账（不是按连接）：keep-alive 空闲连接不占服务台，
           否则一条空闲 60 秒的连接会被当成"一个跑了 60 秒的请求"，
           把排队论算出来的容量和压力值全部带偏。 */
        Conn conn = null;
        String peer = String.valueOf(s.getInetAddress());
        final String peerKey = peerKeyOf(peer);
        java.util.concurrent.atomic.AtomicInteger counter =
                PER_PEER.computeIfAbsent(peerKey, k -> new java.util.concurrent.atomic.AtomicInteger());
        int live = counter.incrementAndGet();
        int perPeer = maxPerPeer(peerKey);
        if (live > perPeer) {
            counter.decrementAndGet();
            /* 计数归零就把表项删掉：LAN 模式下每个来源 IP 留一条，
               IPv6 客户端可以换地址，长期下来这张表会无限增长。 */
            PER_PEER.computeIfPresent(peerKey, (k, v) -> v.get() <= 0 ? null : v);
            Log.w("同一客户端连接过多（" + live + " > " + perPeer + "）：" + peer + "，拒绝一条");
            try {
                s.getOutputStream().write(("HTTP/1.1 503 Service Unavailable\r\n"
                        + "Content-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                s.getOutputStream().flush();
            } catch (Throwable ignored) {
            }
            try {
                s.close();
            } catch (Throwable ignored) {
            }
            return;
        }
        try {
            ACTIVE_SOCKS.add(s);
            s.setTcpNoDelay(true);
            s.setKeepAlive(true);
            s.setSoTimeout(firstRequestMs());   // 只等 10 秒：空连接别占着线程
            BufferedInputStream in = new BufferedInputStream(s.getInputStream(), 8192);
            CountingStream counting = new CountingStream(s.getOutputStream());
            OutputStream out = counting;
            conn = new Conn(out, s);
            conn.counter = counting;
            CURRENT.set(conn);
            CONNS.put(out, conn);
            int served = 0;
            while (served < KEEPALIVE_MAX_REQUESTS && running) {
                // 性能定位用：把"读请求"和"处理+写响应"分开计时（只在 debug 构建里打日志）
                long netT0 = android.os.SystemClock.uptimeMillis();
                Req r = readRequest(in);
                if (r == null) break;
                /* Realtime：HTTP Upgrade 后整条连接交给 WebSocket 循环，不能再按
                   普通 keep-alive 请求处理。 */
                if (r.header("upgrade").toLowerCase(Locale.US).contains("websocket")) {
                    if (!"/v1/realtime".equals(r.path)) {
                        writeError(out, r.path, 404, "unknown websocket route: " + r.path);
                        break;
                    }
                    String wsUsed = usedKey(r);
                    boolean wsHasKey = config.apiKey != null && !config.apiKey.isEmpty();
                    if ((config.lanAccess && !wsHasKey) || (wsHasKey && wsUsed.isEmpty())) {
                        Log.w("Realtime 鉴权失败：客户端 Key 与服务端不一致");
                        writeError(out, r.path, 401, "invalid api key");
                        break;
                    }
                    if (browserOriginBlocked(r)) {
                        Log.w("拒绝浏览器跨源 Realtime 连接（未设置 API Key）："
                                + r.header("origin"));
                        writeError(out, r.path, 403,
                                "cross-origin browser access requires an api key");
                        break;
                    }
                    try {
                        Realtime.handle(s, in, out, r, config);
                    } catch (Throwable t) {
                        Log.w("Realtime 连接异常：" + t);
                    }
                    conn.keepAlive = false;
                    break;
                }
                long netT1 = android.os.SystemClock.uptimeMillis();
                served++;
                /* 第 KEEPALIVE_MAX_REQUESTS 条响应就明确告诉客户端 close：
                   只靠 Keep-Alive: max 的话，忽略这个头的客户端会复用一条
                   即将被关掉的连接，下一条请求就 Connection reset。 */
                conn.keepAlive = r.keepAlive && served < KEEPALIVE_MAX_REQUESTS;
                long started = System.currentTimeMillis();
                // 探活回环不算"真实流量"：原来探活打自己的请求也会刷新这个时间戳，
                // 导致 verifyModelsAsync 的让路循环把自己当流量、越让越慢（N 个模型被拉长 N×4 秒）
                if (!r.path.startsWith("/v1/debug") && r.header("x-cpa-ephemeral").isEmpty()) {
                    lastClientRequestAt = started;
                    // 有真流量了：如果之前空闲时把 CPU 锁还回去了，这里立刻拿回来
                    try {
                        com.cpa.gateway.GatewayService.poke();
                    } catch (Throwable ignored) {
                    }
                }
                boolean reqOk = true;
                String errKind = ErrorKind.NONE;
                /* 请求级进出账：inflight 是"正在处理的请求数"，
                   生命周期 = 这一次 route 的耗时（流式请求算到最后一个字节）。 */
                Capacity.enter();
                try {
                    route(r, out, s);
                } catch (Throwable routeError) {
                    reqOk = false;
                    conn.responseFailed = true;
                    /* 4.36.1：记下"为什么失败"。混成一种的话，
                       就没法知道该重试、该换号、还是该改代码。 */
                    errKind = ErrorKind.of(routeError);
                    Log.w("request failed (" + r.method + " " + r.path + "): " + routeError);
                    /* InvalidRequest 自己带着 400 语义（客户端发错了），
                       以前这里一律 500，客户端会把"请求不合规"当成网关故障去重试。 */
                    int status = routeError instanceof InvalidRequest
                            ? ((InvalidRequest) routeError).code : 500;
                    failResponse(out, r.path, status, String.valueOf(routeError.getMessage()));
                    if (!conn.sse) conn.keepAlive = false;
                } finally {
                    Capacity.leave();
                }
                long took = System.currentTimeMillis() - started;
                /* 容量模型只吃真实流量：后台探活（x-cpa-ephemeral）会带 90 秒超时，
                   把它算进去会让"请求很慢"的结论失真，进而把建议容量带偏。 */
                if (r.header("x-cpa-ephemeral").isEmpty() && !r.path.startsWith("/v1/debug")) {
                    Capacity.record(started, took);
                }
                if (Debug.ON) {
                    long netT2 = android.os.SystemClock.uptimeMillis();
                    Log.i("请求分解：" + r.method + " " + r.path
                            + " · 读请求 " + (netT1 - netT0) + "ms"
                            + " · 处理+写 " + (netT2 - netT1) + "ms");
                }
                // 用量统计：只有真流量（探活回环带 x-cpa-ephemeral、debug 请求都不算）
                if (r.header("x-cpa-ephemeral").isEmpty() && !r.path.startsWith("/v1/debug")) {
                    // 没走到上游的推理请求（比如"虚拟模型现在没有可用候选"）在用户眼里就是失败，
                    // HTTP 层 200 不算数
                    boolean usageOk = usageSuccess(reqOk, !conn.apiProvider.isEmpty(), conn.responseFailed);
                    Usage.Obs obs = new Usage.Obs();
                    obs.source = r.header("x-cpa-ephemeral").isEmpty() ? "user" : "probe";
                    obs.tools = conn.toolsSent;
                    obs.hasToolHistory = conn.hasToolHistory;
                    obs.parse = conn.parseOutcome == null ? "" : conn.parseOutcome;
                    obs.shape = shapeOf(conn);
                    /* 4.35.0 结果质量：让日常真实流量自己变成样本。
                       只判结构上确定的东西（空/截断/跑题/用户重发），不碰语义。 */
                    obs.quality = Quality.judge(
                            conn.seenTextHint,
                            conn.askedHint,
                            conn.stopReason,
                            conn.clientRetried, conn.sawToolCall);
                    obs.responseId = conn.upstreamResponseId;
                    obs.conversationId = conn.conversationId;
                    /* 没报错但结果为空 —— 这也是一种失败，单独归类 */
                    obs.toolNames = conn.toolNames;
                    obs.errorKind = !ErrorKind.NONE.equals(errKind)
                            ? errKind
                            : (conn.errorKind != null && !conn.errorKind.isEmpty()
                                    ? conn.errorKind
                                    : ("empty".equals(obs.quality) ? ErrorKind.EMPTY : ""));
                    /* 4.33.1 原始响应留样：只在可疑请求上留（空回复、正文像调用、报错）。
                       目的不是统计，而是拿到"上游到底回了什么"的真实样本 ——
                       要照着它的结构做适配，就得先看清结构。 */
                    if (!"probe".equals(obs.source)) {
                        String why = null;
                        if ("empty".equals(obs.shape)) why = "empty";
                        else if ("text".equals(obs.shape)) why = "looks-text";
                        else if ("error".equals(obs.shape)) why = "upstream-error";
                        if (why != null) {
                            RawCap.note(why,
                                    r.path + " | " + conn.apiProvider + " | " + conn.apiModel
                                            + " | " + took + "ms | tools=" + conn.toolsSent
                                            + " | hist=" + (conn.hasToolHistory ? 1 : 0),
                                    "outChars=" + conn.outChars
                                            + " stop=" + conn.stopReason
                                            + "\nbody: " + (conn.seenTextHint == null ? "" : conn.seenTextHint));
                        }
                    }
                    Usage.record(r.path, conn.apiModel, conn.apiProvider, took,
                            usageOk, conn.tokIn, conn.tokOut, conn.apiKeyLabel,
                            conn.outChars, conn.sawToolCall, conn.stopReason, obs);
                }
                conn.apiModel = "";
                conn.apiProvider = "";
                conn.responseFailed = false;
                conn.tokIn = 0;
                conn.tokOut = 0;
                conn.outChars = 0;
                conn.sawToolCall = false;
                conn.stopReason = "";
                conn.toolsSent = 0;
                conn.hasToolHistory = false;
                conn.parseOutcome = "none";
                conn.responseShape = "";
                conn.seenTextHint = "";
                conn.askedHint = "";
                conn.clientRetried = false;
                conn.upstreamResponseId = "";
                conn.conversationId = "";
                conn.toolNames = "";
                conn.errorKind = "";
                conn.apiKeyLabel = "";
                if (took > 1500 || r.path.startsWith("/v1/")) {
                    Log.i(r.method + " " + r.path + " 用时 " + took + "ms");
                }
                if (conn.sse) {
                    conn.endStream();
                    Log.i("流式响应结束（客户端 " + peer + "，用时 " + took + "ms）");
                    if (!conn.keepAlive) break;
                }
                if (!conn.keepAlive) break;
                s.setSoTimeout(KEEPALIVE_IDLE_MS);
            }
        } catch (java.net.SocketTimeoutException idle) {
            Log.i("空闲连接已回收（" + (KEEPALIVE_IDLE_MS / 1000) + " 秒无请求）");
        } catch (java.io.IOException io) {
            // 客户端自己走的，和"服务端出错"要分开记，否则排查时分不清断在哪一段
            if (conn != null && conn.sse) {
                Log.w("客户端中断了流式连接（地址 " + peer + "）：" + io.getMessage());
            } else {
                Log.w("连接被对端关闭（地址 " + peer + "）：" + io.getMessage());
            }
        } catch (Throwable t) {
            Log.w("connection error: " + t);
        } finally {
            if (conn != null) conn.stopPing();
            if (conn != null && conn.sse) Log.i("流式连接结束（客户端地址 " + peer + "）");
            if (conn != null) CONNS.remove(conn.out);
            CURRENT.remove();
            try {
                if (conn != null) conn.out.flush();
            } catch (Exception ignored) {
            }
            try {
                s.close();
            } catch (Exception ignored) {
            }
            ACTIVE_SOCKS.remove(s);
            java.util.concurrent.atomic.AtomicInteger c = PER_PEER.get(peerKey);
            if (c != null) {
                c.decrementAndGet();
                /* 归零即删。用 computeIfPresent 而不是 remove(key, c)：
                   并发新连接可能刚把计数加回 1，这时不能把表项删掉。 */
                PER_PEER.computeIfPresent(peerKey, (k, v) -> v.get() <= 0 ? null : v);
            }
        }
    }

    /** 上一次"客户端 Key 不对"的告警时间，用来限流（每客户端每秒重试一次会刷爆日志）。 */
    private static volatile long lastAuthWarnMs = 0L;
    /** 上一次"拒绝浏览器跨源"的告警时间，同样避免刷屏。 */
    private static volatile long lastCorsWarnMs = 0L;

    /**
     * 没设 API Key 时，这个请求是不是来自"外部网页"。
     *
     * 只看 Origin：原生客户端一般不发 Origin；浏览器发的是页面自己的源。
     * 回环来源（面板、本机网页）放行；非 http(s) 的自定义 scheme 也放行，
     * 因为远程网页无法伪造它。
     */
    private boolean browserOriginBlocked(Req r) {
        if (config.apiKey != null && !config.apiKey.isEmpty()) return false;
        return isForeignBrowserOrigin(r.header("origin"));
    }

    /** 纯函数，方便自检。origin 为空或非 http(s) 视为不是"外部网页"。 */
    static boolean isForeignBrowserOrigin(String origin) {
        if (origin == null) return false;
        String o = origin.trim();
        if (o.isEmpty()) return false;
        /* sandbox iframe / data: / file: 页面会发 Origin: null；
           远程恶意页面正是用这个绕过"只拦 http(s) 外部源"。没有 Key 时按外部源处理。 */
        if ("null".equalsIgnoreCase(o)) return true;
        String lower = o.toLowerCase(Locale.US);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false;
        try {
            java.net.URI u = new java.net.URI(o);
            String host = u.getHost();
            if (host == null || host.isEmpty()) return true;
            host = host.toLowerCase(Locale.US);
            if (host.startsWith("[") && host.endsWith("]")) {
                host = host.substring(1, host.length() - 1);
            }
            if ("localhost".equals(host) || "localhost.".equals(host)
                    || host.endsWith(".localhost")   // RFC 6761：*.localhost 都指向回环
                    || "::1".equals(host) || "0:0:0:0:0:0:0:1".equals(host)) {
                return false;
            }
            /* 必须是完整的 127/8 地址，不能只看前缀：
               127.0.0.1.evil.com 也以 "127." 开头，但它不是回环。 */
            if (host.matches("127(\\.\\d{1,3}){1,3}")) return false;
            if (host.matches("::ffff:127(\\.\\d{1,3}){1,3}")) return false;
            return true;
        } catch (Exception e) {
            return true;
        }
    }

    private void route(Req r, OutputStream out, Socket s) throws Exception {
        String path = r.path;
        // 这里原来每个请求先打一条"开始"日志，结束时再打一条"用时"——两条都写，
        // 而"用时"那条已经带了 method + path。压测 500 个请求就是 1000 次 logd 调用，
        // 纯开销，所以只留结果那条。
        if ("OPTIONS".equals(r.method)) {
            byte[] body = new byte[0];
            writeRaw(out, 204, "text/plain", body, true);
            return;
        }
        String usedKey = usedKey(r);
        boolean hasKey = config.apiKey != null && !config.apiKey.isEmpty();
        // 局域网模式下必须设 Key：否则同一 Wi-Fi 下任何人都能白用（原来是"没 Key 就全放行"）
        if (config.lanAccess && !hasKey) {
            Log.w("局域网已开启但没有设置访问 Key：拒绝请求（请先在设置里设一个 Key）");
            writeError(out, path, 401, "lan access requires an api key");
            return;
        }
        if (hasKey && usedKey.isEmpty()) {
            /* 客户端拿错了 Key（换了机器 / 重装应用后配置被重置 / 复制时少一位）时
               原来是**完全静默**的：客户端只表现为不停重连，日志里只有一行"用时 3ms"。
               今天真机就是这样排查了半小时——所以这里必须留一句人话，并限流避免刷屏。 */
            long now = System.currentTimeMillis();
            if (now - lastAuthWarnMs > 60000L) {
                lastAuthWarnMs = now;
                Log.w("客户端 Key 与服务端不一致（401）：请把设置页「客户端密钥」重新复制到客户端"
                        + "（客户端 Key=" + maskKey(usedKey) + "）");
            }
            writeError(out, path, 401, "invalid api key");
            return;
        }
        /* 浏览器跨源防护（4.50.3）。
         *
         * 本机网关默认可以没有 API Key。浏览器里任何一个网页都能 fetch
         * http://127.0.0.1:8321，而响应头又允许 CORS *：没有 Key 时，
         * 一个恶意网页就能直接消耗用户的账号额度（DNS rebinding 也能绕过同源）。
         *
         * 规则：没设 Key 时，来源是 http(s) 且不是回环地址的浏览器请求一律拒绝；
         * 原生客户端通常不带 Origin，不受影响；面板/本机网页同源或回环，放行。
         * 设了 Key 之后正常鉴权已经覆盖，不再额外拦。
         */
        if (browserOriginBlocked(r)) {
            long now = System.currentTimeMillis();
            if (now - lastCorsWarnMs > 60000L) {
                lastCorsWarnMs = now;
                Log.w("拒绝浏览器跨源访问（未设置 API Key）：Origin=" + r.header("origin")
                        + " · 如需从其它网页调用，请先在设置里设置客户端密钥");
            }
            writeError(out, path, 403,
                    "cross-origin browser access requires an API key; set one in AHYU settings");
            return;
        }
        Conn selfConn = CURRENT.get();
        if (selfConn != null) selfConn.apiKeyLabel = maskKey(usedKey);
        // 发布包里把调试面整块关掉（见 Debug）：一条短路，不用给下面 19 处路由各包一层 if
        // 恢复成"调试包才有 /v1/debug/*"：之前为让检测在正式包可用开了 detect 例外，
        // 但那等于把 19 条调试/烧额度接口全留在正式包里。检测现在直接调 Detect（进程内），不需要这条路。
        if (!Debug.ON && path.startsWith("/v1/debug")) {
            writeError(out, path, 404, "not found");
            return;
        }
        if ("/health".equals(path) || "/".equals(path)) {
            long stamp = configStamp();
            String cached = cacheHealthStamp == stamp ? cacheHealthJson : null;
            if (cached == null) {
                JSONObject o = new JSONObject();
                o.put("status", "ok");
                o.put("service", "CLIProxy-Android");
                o.put("app", "AHYU");
                o.put("port", config.port);
                JSONArray ps = new JSONArray();
                for (Config.Provider p : config.providers) {
                    if (!p.dispatchable()) continue;
                    JSONObject po = new JSONObject();
                    po.put("name", p.name);
                    po.put("type", p.type);
                    po.put("models", new JSONArray(p.models));
                    ps.put(po);
                }
                o.put("providers", ps);
                cached = o.toString(2);
                cacheHealthJson = cached;
                cacheHealthStamp = stamp;
            }
            /* 连接数/容量是实时变化的，不能进缓存；在缓存的基础 JSON 上补两个字段。 */
            JSONObject live = new JSONObject(cached);
            live.put("connections", CONNS.size());
            live.put("capacity", Capacity.state());
            live.put("ipv6_loopback", socket6 != null);
            writeJson(out, 200, live.toString());
            return;
        }
        if (path.startsWith("/v1/models/") && "GET".equals(r.method)) {
            String id = URLDecoder.decode(path.substring("/v1/models/".length()), "UTF-8");
            if (!allModels().contains(id)) {
                writeError(out, path, 404, "model not found: " + id);
                return;
            }
            JSONObject m = new JSONObject();
            m.put("id", id);
            m.put("object", "model");
            m.put("created", System.currentTimeMillis() / 1000);
            m.put("owned_by", "ahyu");
            writeJson(out, 200, m.toString());
            return;
        }
        if ("/panel".equals(path) || "/panel/".equals(path)) {
            writeRaw(out, 200, "text/html; charset=utf-8", panelHtml().getBytes(StandardCharsets.UTF_8), false);
            return;
        }
        /*
         * 只读诊断：容量与排队状态。
         *
         * 为什么单独开一个端点而不是塞进 /health：
         * /health 是被客户端高频轮询的，塞进去等于每次轮询都跑一遍 Erlang-C；
         * 这个算的是**诊断**，人偶尔看一眼，慢一点没关系。
         */
        if ("/v1/capacity".equals(path) && "GET".equals(r.method)) {
            org.json.JSONObject o = new org.json.JSONObject();
            try {
                o.put("active_conn", connPool.getActiveCount());
                o.put("static_max", MAX_CONN);
                o.put("suggested", Capacity.suggest());
                o.put("pressure", Math.round(Capacity.pressure() * 100.0) / 100.0);
                o.put("detail", Capacity.state());
            } catch (Throwable t) {
                o.put("error", String.valueOf(t));
            }
            writeJson(out, 200, o.toString());
            return;
        }
        if ("/v1/models".equals(path) && "GET".equals(r.method)) {
            long stamp = configStamp();
            String cached = cacheModelsStamp == stamp ? cacheModelsJson : null;
            if (cached == null) {
                Log.i("models 缓存重建：stamp " + cacheModelsStamp + " → " + stamp);
                cached = modelsOpenAi().toString();
                cacheModelsJson = cached;
                cacheModelsStamp = stamp;
            }
            writeJson(out, 200, cached);
            return;
        }
        if ("/v1/oauth/start".equals(path) && "POST".equals(r.method)) {
            JSONObject body = new JSONObject(r.body.isEmpty() ? "{}" : r.body);
            JSONObject start = WorkBuddy.beginLogin(Proto.optString(body, "realm", WorkBuddy.REALM_CN), Proto.optString(body, "base", ""));
            writeJson(out, 200, start.toString(2));
            return;
        }
        if ("/v1/oauth/status".equals(path)) {
            String state = queryParam(r, "state");
            if (state.isEmpty()) {
                writeError(out, path, 400, "missing state");
                return;
            }
            JSONObject res = WorkBuddy.pollLogin(queryParam(r, "realm"), queryParam(r, "base"), state);
            writeJson(out, 200, res.toString(2));
            return;
        }
        if ("/v1/debug/capture".equals(path)) {
            // 请求体抓取开关与回读。用于对比"网关发出去的请求"和"官方客户端发的请求"。
            //   GET  /v1/debug/capture          -> 回读最近若干条
            //   POST /v1/debug/capture {"on":true|false}
            JSONObject body = new JSONObject(r.body.isEmpty() ? "{}" : r.body);
            if ("POST".equals(r.method) && body.has("on")) {
                Upstreams.captureOn(body.optBoolean("on", false));
                writeJson(out, 200, "{\"capture\":" + Upstreams.captureEnabled() + "}");
                return;
            }
            java.util.List<String> items = Upstreams.capturedSnapshot();
            JSONArray arr = new JSONArray();
            for (String it : items) arr.put(it);
            JSONObject res = new JSONObject();
            res.put("capture", Upstreams.captureEnabled());
            res.put("count", items.size());
            res.put("items", arr);
            writeJson(out, 200, res.toString(2));
            return;
        }
        if ("/v1/debug/raw-stream".equals(path) && "POST".equals(r.method)) {
            JSONObject body = new JSONObject(r.body.isEmpty() ? "{}" : r.body);
            String model = Proto.optString(body, "model", "");
            String prompt = Proto.optString(body, "prompt", "只回复：ok");
            Integer lines = Proto.optInt(body, "lines");
            Config.Provider provider = pick(model);
            if (provider == null) {
                writeError(out, path, 404, "no provider for model " + model);
                return;
            }
            String upstreamModel = upstreamModel(provider, model);
            Proto.Req req = new Proto.Req();
            req.model = model;
            Proto.Msg msg = new Proto.Msg("user");
            msg.add(Proto.Part.text(prompt));
            req.messages.add(msg);
            req.maxTokens = 32;
            Upstreams.startCapture(lines == null ? 40 : lines.intValue());
            JSONObject result = new JSONObject();
            try {
                Upstreams.create(provider).chat(upstreamModel, req, new Proto.Sink() {
                    @Override
                    public void text(String delta) {
                    }

                    @Override
                    public void thinking(String delta) {
                    }

                    @Override
                    public void toolCall(String id, String name, String argsJson) {
                    }

                    @Override
                    public void finish(String stopReason, Proto.Usage usage) {
                    }
                });
                result.put("ok", true);
            } catch (Throwable t2) {
                result.put("error", String.valueOf(t2));
            }
            JSONArray raw = new JSONArray();
            for (String line : Upstreams.stopCapture()) raw.put(line);
            result.put("raw", raw);
            result.put("provider", provider.name);
            result.put("upstreamModel", upstreamModel);
            writeJson(out, 200, result.toString(2));
            return;
        }
        if ("/v1/debug/wb-models".equals(path) && "POST".equals(r.method)) {
            JSONObject body = new JSONObject(r.body.isEmpty() ? "{}" : r.body);
            String wantName = Proto.optString(body, "provider", "");
            JSONArray trials = new JSONArray();
            for (Config.Provider p2 : config.providers) {
                if (!Config.TYPE_WORKBUDDY.equals(p2.type)) continue;
                if (!wantName.isEmpty() && !wantName.equals(p2.name)) continue;
                Config.Provider p = p2;
                JSONObject marked = WorkBuddy.fromJson2(p.credentials);
                String token = Proto.optString(marked, "access_token", "");
                String uid = Proto.optString(marked, "uid", "");
                String domain = Proto.optString(marked, "domain", "");
                String realm = Proto.optString(WorkBuddy.fromJson2(p.credentials), "realm", WorkBuddy.REALM_CN);
                String base = p.baseUrl == null || p.baseUrl.isEmpty()
                        ? (WorkBuddy.REALM_GLOBAL.equalsIgnoreCase(realm) ? "https://www.workbuddy.ai" : "https://www.codebuddy.cn")
                        : p.baseUrl;
                String[] uas = {
                        "WorkBuddy/5.5.4",
                        "WorkBuddy/5.5.6 CLI/2.137.1",
                        "WorkBuddy/5.5.2 WorkBuddy AI/5.5.2 CLI/5.5.2",
                        "WorkBuddy/5.5.6",
                        "WorkBuddy AI/5.5.2 CLI/5.5.2"};
                String[] paths = {"/v3/config", "/v3/agents", "/v3/model/list", "/v2/plugin/models",
                        "/v3/llm/models", "/v3/config?platform=cli", "/v3/cli/config", "/v3/agent/models"};
                for (String ua : uas) {
                    for (String pp : paths) {
                        JSONObject t = new JSONObject();
                        t.put("provider", p.name);
                        t.put("ua", ua);
                        t.put("path", pp);
                        Map<String, String> headers = new java.util.HashMap<>();
                        headers.put("Authorization", "Bearer " + token);
                        if (!uid.isEmpty()) headers.put("X-User-Id", uid);
                        if (!domain.isEmpty()) headers.put("X-Domain", domain);
                        else headers.put("X-No-Enterprise-Id", "1");
                        headers.put("Accept-Language", "zh-CN");
                        headers.put("X-Requested-With", "XMLHttpRequest");
                        headers.put("Origin", base);
                        headers.put("Referer", base + "/");
                        headers.put("User-Agent", ua);
                        try {
                            String raw = Upstreams.getWithHeaders(base + pp, headers);
                            org.json.JSONObject parsed = new org.json.JSONObject(raw);
                            java.util.List<String> found = new java.util.ArrayList<>();
                            collectModels(parsed, found, 0);
                            t.put("count", found.size());
                            t.put("sample", found.size() > 6 ? found.subList(0, 6).toString() : found.toString());
                        } catch (Exception e) {
                            t.put("error", Upstreams.shortenPublic(String.valueOf(e.getMessage())));
                        }
                        trials.put(t);
                    }
                }
            }
            JSONObject wrap = new JSONObject();
            wrap.put("trials", trials);
            writeJson(out, 200, wrap.toString(2));
            return;
        }
        if ("/v1/debug/antigravity".equals(path) && "POST".equals(r.method)) {
            JSONArray list = new JSONArray();
            for (Config.Provider p2 : config.providers) {
                if (!Config.TYPE_ANTIGRAVITY.equals(p2.type)) continue;
                JSONObject o = new JSONObject();
                o.put("name", p2.name);
                o.put("storedProject", Antigravity.Account.fromJson(p2.credentials).projectId);
                o.put("storedTier", Antigravity.Account.fromJson(p2.credentials).tier);
                o.put("storedEmail", Antigravity.Account.fromJson(p2.credentials).email);
                o.put("hasRefresh", !Antigravity.Account.fromJson(p2.credentials).refreshToken.isEmpty());
                try {
                    Antigravity.Account acc = Antigravity.ensureFresh(p2);
                    JSONObject metadata = new JSONObject();
                    metadata.put("ideType", "ANTIGRAVITY");
                    JSONObject lb = new JSONObject();
                    lb.put("metadata", metadata);
                    o.put("loadCodeAssist", cap(Upstreams.postJson(
                            Antigravity.API_BASE + "/v1internal:loadCodeAssist",
                            Antigravity.headers(acc, false), lb), 900));
                    JSONObject meta2 = new JSONObject();
                    meta2.put("ide_type", "ANTIGRAVITY");
                    meta2.put("ide_version", "2.9.1");
                    meta2.put("ide_name", "antigravity");
                    JSONObject ob = new JSONObject();
                    ob.put("tier_id", acc.tier.isEmpty() ? "free-tier" : acc.tier);
                    ob.put("metadata", meta2);
                    try {
                        o.put("onboardUser", cap(Upstreams.postJson(
                                Antigravity.DAILY_API_BASE + "/v1internal:onboardUser",
                                Antigravity.headers(acc, false), ob), 700));
                    } catch (Exception e) {
                        o.put("onboardUserError", String.valueOf(e));
                    }
                    JSONObject contents = new JSONArray()
                            .put(new JSONObject().put("role", "user")
                                    .put("parts", new JSONArray().put(new JSONObject().put("text", "hi"))))
                            .getJSONObject(0);
                    JSONObject inner = new JSONObject();
                    inner.put("contents", new JSONArray().put(contents));
                    JSONObject env = new JSONObject();
                    env.put("project", acc.projectId);
                    env.put("model", "gemini-2.5-flash");
                    env.put("userAgent", "antigravity");
                    env.put("requestType", "agent");
                    env.put("requestId", "probe-1");
                    env.put("request", inner);
                    try {
                        o.put("generateContent", cap(Upstreams.postJson(
                                Antigravity.API_BASE + "/v1internal:generateContent",
                                Antigravity.headers(acc, false), env), 500));
                    } catch (Exception e) {
                        o.put("generateError", String.valueOf(e));
                    }
                } catch (Exception e) {
                    o.put("error", String.valueOf(e));
                }
                list.put(o);
            }
            JSONObject wrap = new JSONObject();
            wrap.put("accounts", list);
            writeJson(out, 200, wrap.toString(2));
            return;
        }
        if ("/v1/credits/refresh".equals(path) && "POST".equals(r.method)) {
            JSONArray rows = new JSONArray();
            for (Config.Provider p : config.providers) {
                if (!p.dispatchable()) continue;
                JSONObject row = new JSONObject();
                row.put("provider", p.name);
                try {
                    double value = -1;
                    if (Config.TYPE_WORKBUDDY.equals(p.type)) {
                        value = WbTasks.credits(p);
                    } else if (Config.TYPE_TRAE.equals(p.type)) {
                        value = Trae.credits(p);
                    } else if (Config.TYPE_LOOMY.equals(p.type)) {
                        value = Loomy.points(p);
                    }
                    if (value >= 0) {
                        p.credits = value;
                        p.creditsAt = System.currentTimeMillis() / 1000;
                    }
                    row.put("credits", value);
                } catch (Exception e) {
                    row.put("error", Upstreams.shortenPublic(String.valueOf(e.getMessage())));
                }
                rows.put(row);
            }
            Gateway.persistSoon();
            JSONObject wrap = new JSONObject();
            wrap.put("accounts", rows);
            writeJson(out, 200, wrap.toString(2));
            return;
        }
        if ("/v1/debug/credits".equals(path) && "POST".equals(r.method)) {
            JSONObject body = new JSONObject(r.body.isEmpty() ? "{}" : r.body);
            String name = Proto.optString(body, "provider", "");
            JSONObject result = new JSONObject();
            for (Config.Provider p : config.providers) {
                if (!name.isEmpty() && !name.equals(p.name)) continue;
                if (!Config.TYPE_TRAE.equals(p.type)) continue;
                try {
                    Trae.Account acc = Trae.ensureFresh(p);
                    JSONObject raw = new JSONObject(Upstreams.postJson(
                            Trae.UG_HOST + Trae.EP_ENT_USAGE_WEB, Trae.ugHeaders(acc), new JSONObject()));
                    result.put(p.name, raw);
                } catch (Exception e) {
                    result.put(p.name, "错误：" + e);
                }
            }
            writeJson(out, 200, result.toString(2));
            return;
        }
        if ("/v1/debug/stations-sync".equals(path)) {
            java.util.Map<String, Boolean> res = Stations.syncStations();
            JSONArray arr3 = new JSONArray();
            for (String[] st : Stations.visibleStations()) {
                JSONObject o = new JSONObject();
                o.put("name", st[0]);
                o.put("url", st[1]);
                arr3.put(o);
            }
            JSONObject wrap3 = new JSONObject();
            wrap3.put("alive", res.size());
            wrap3.put("removed", Gateway.config().removedStations.size());
            wrap3.put("stations", arr3);
            writeJson(out, 200, wrap3.toString(2));
            return;
        }
        if ("/v1/debug/stations".equals(path)) {
            Map<String, Boolean> res = Stations.checkAll();
            JSONArray arr2 = new JSONArray();
            for (String[] st : Stations.LIST) {
                JSONObject o = new JSONObject();
                o.put("name", st[0]);
                o.put("url", st[1]);
                o.put("alive", Boolean.TRUE.equals(res.get(st[1])));
                arr2.put(o);
            }
            JSONObject wrap2 = new JSONObject();
            wrap2.put("stations", arr2);
            writeJson(out, 200, wrap2.toString(2));
            return;
        }
        if ("/v1/debug/relay-watch".equals(path)) {
            JSONArray arr = new JSONArray();
            for (RelayWatch.Result res : RelayWatch.runOnce()) {
                JSONObject o = new JSONObject();
                o.put("provider", res.provider);
                o.put("state", res.state);
                o.put("streak", res.streak);
                arr.put(o);
            }
            JSONObject wrap = new JSONObject();
            wrap.put("checked", arr);
            writeJson(out, 200, wrap.toString(2));
            return;
        }
        if ("/v1/debug/test-provider".equals(path) && "POST".equals(r.method)) {
            /* 调试包专用：临时加/删一个 OpenAI 兼容账号，用来测非聊天转发。
               名字必须以 debug- 开头，避免误删真实账号。 */
            try {
                JSONObject o = new JSONObject(r.body.isEmpty() ? "{}" : r.body);
                String name = Proto.optString(o, "name", "debug-fake");
                if (!name.startsWith("debug-")) {
                    writeError(out, path, 400, "测试账号名必须以 debug- 开头");
                    return;
                }
                for (int i = config.providers.size() - 1; i >= 0; i--) {
                    if (name.equals(config.providers.get(i).name)) config.providers.remove(i);
                }
                if (!"remove".equals(Proto.optString(o, "action", "add"))) {
                    Config.Provider p = new Config.Provider();
                    p.id = "debug-" + System.currentTimeMillis();
                    p.name = name;
                    p.type = Proto.optString(o, "type", Config.TYPE_OPENAI);
                    p.baseUrl = Proto.optString(o, "baseUrl", "");
                    p.apiKey = Proto.optString(o, "apiKey", "");
                    JSONArray models = o.optJSONArray("models");
                    if (models != null) {
                        for (int i = 0; i < models.length(); i++) {
                            String m = models.optString(i, "");
                            if (!m.isEmpty()) p.models.add(m);
                        }
                    }
                    config.providers.add(p);
                }
                config.save(Gateway.context(), true);
                JSONObject res = new JSONObject();
                res.put("ok", true);
                res.put("providers", config.providers.size());
                writeJson(out, 200, res.toString());
            } catch (Throwable t) {
                writeError(out, path, 400, String.valueOf(t.getMessage()));
            }
            return;
        }
        if ("/v1/debug/diagnostics".equals(path)) {
            /* 调试包专用：直接下载诊断 zip，方便用 curl 取回，也方便测试。 */
            byte[] zip = Diagnostics.build();
            writeRaw(out, 200, "application/zip", zip, false);
            return;
        }
        if ("/v1/debug/selftest".equals(path)) {
            JSONObject out3 = new JSONObject();
            try {
                String body = "{\"base\":{\"appid\":\"GM3LOOMY\"},\"param\":{\"phone\":\"00000000000\"}}";
                String md5 = Loomy.contentMd5(body);
                String sig = Loomy.signature("POST", "/login/phone/sendMsgCode", body,
                        "Thu, 09 Oct 2026 12:00:00 GMT", "fixed-nonce-1234");
                JSONObject sign = new JSONObject();
                sign.put("contentMD5", md5);
                sign.put("signature", sig);
                /* 固定向量：原值对应的是早期的一版 body，后来 body 改过但期望值没跟着改，
                   这个调试自检就一直是 pass=false，看起来像签名算法坏了。
                   4.37.0 用当前 body 重新算过（HMAC-SHA1 结果与离线计算一致）。 */
                sign.put("expectedContentMD5", "uCpafUH19O0kzitGHuTHSg==");
                sign.put("expectedSignature", "EMv0UJy/fAYcWkVsDMugUj4t4a4=");
                sign.put("pass", md5.equals("uCpafUH19O0kzitGHuTHSg==")
                        && sig.equals("EMv0UJy/fAYcWkVsDMugUj4t4a4="));
                out3.put("loomySigning", sign);
                String machine = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
                String device = "123456789012345";
                JSONObject trae = new JSONObject();
                trae.put("machineTraceId", Trae.machineTraceId(machine, device));
                trae.put("expectedTraceId", "f123456789012345");
                trae.put("tracePass", Trae.machineTraceId(machine, device).equals("f123456789012345"));
                trae.put("loginUrlHasCallback", Trae.buildLoginUrl(machine, device).contains("auth_callback_url=http%3A%2F%2F127.0.0.1%3A51120%2Fauthorize"));
                trae.put("loginUrlHasFunction", Trae.buildLoginUrl(machine, device).contains("client_id=en1oxy7wnw8j9n"));
                out3.put("traeLogin", trae);
                String agUrl = com.cpa.gateway.core.Antigravity.authorizeUrl("test-state");
                JSONObject ag = new JSONObject();
                ag.put("hasClientId", agUrl.contains("client_id=1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com"));
                ag.put("hasOffline", agUrl.contains("access_type=offline"));
                ag.put("hasConsent", agUrl.contains("prompt=consent"));
                ag.put("hasCallback", agUrl.contains("redirect_uri=http%3A%2F%2Flocalhost%3A51121%2Foauth-callback"));
                ag.put("hasCloudScope", agUrl.contains("cloud-platform"));
                out3.put("antigravityAuth", ag);
                JSONArray groups = new JSONArray();
                for (java.util.Map.Entry<String, java.util.List<Config.Provider>> e
                        : com.cpa.gateway.MainActivity.groupByVendor(config.providers).entrySet()) {
                    JSONObject g = new JSONObject();
                    g.put("vendor", e.getKey());
                    JSONArray members = new JSONArray();
                    for (Config.Provider p : e.getValue()) members.put(p.name);
                    g.put("accounts", members);
                    g.put("detailCount", com.cpa.gateway.MainActivity
                            .accountsOfVendor(e.getKey(), config.providers).size());
                    groups.put(g);
                }
                out3.put("vendorGroups", groups);
                try {
                    Kimi.DeviceCode kd = Kimi.deviceAuthorize();
                    JSONObject kk = new JSONObject();
                    kk.put("userCode", kd.userCode);
                    kk.put("verificationUri", kd.verificationUriComplete);
                    kk.put("interval", kd.interval);
                    kk.put("expiresIn", kd.expiresIn);
                    kk.put("ok", !kd.userCode.isEmpty());
                    out3.put("kimiDevice", kk);
                } catch (Exception e) {
                    out3.put("kimiDeviceError", String.valueOf(e));
                }
                JSONArray modelGroups = new JSONArray();
                for (java.util.Map.Entry<String, java.util.List<Config.Provider>> e
                        : com.cpa.gateway.MainActivity.groupByVendor(config.providers).entrySet()) {
                    int count = 0;
                    JSONArray accounts = new JSONArray();
                    for (Config.Provider p : e.getValue()) {
                        if (!p.dispatchable()) continue;
                        int n = 0;
                        for (String m : p.models) if (!"*".equals(m)) n++;
                        count += n;
                        accounts.put(p.name + "(" + n + ")");
                    }
                    if (accounts.length() == 0) continue;
                    JSONObject g = new JSONObject();
                    g.put("vendor", e.getKey());
                    g.put("models", count);
                    g.put("accounts", accounts);
                    modelGroups.put(g);
                }
                out3.put("modelGroups", modelGroups);
            } catch (Exception e) {
                out3.put("error", String.valueOf(e));
            }
            writeJson(out, 200, out3.toString(2));
            return;
        }

        if ("/v1/tasks/run".equals(path) && "POST".equals(r.method)) {
            final JSONObject body = new JSONObject(r.body.isEmpty() ? "{}" : r.body);
            final String only = Proto.optString(body, "provider", "");
            final boolean sync = body.optBoolean("sync", false);
            final String runKey = only.isEmpty() ? "*" : only;
            if (!TASKS_RUNNING.add(runKey)) {
                writeJson(out, 200, "{\"ok\":false,\"error\":\"already running\"}");
                return;
            }
            if (sync) {
                try {
                    JSONArray ran = runTasksInner(only);
                    JSONObject res = new JSONObject();
                    res.put("ok", true);
                    res.put("ran", ran);
                    writeJson(out, 200, res.toString());
                } finally {
                    TASKS_RUNNING.remove(runKey);
                }
                return;
            }
            writeJson(out, 200, "{\"ok\":true,\"started\":true}");
            Threads.io(() -> {
                try {
                    runChoresSync(only);
                    Log.i("一键做任务完成：" + runKey);
                } catch (Throwable t) {
                    Log.w("一键做任务失败（" + runKey + "）：" + t);
                } finally {
                    TASKS_RUNNING.remove(runKey);
                }
            });
            return;
        }
        if ("/v1/doubao/import".equals(path) && "POST".equals(r.method)) {
            JSONObject body = new JSONObject(r.body.isEmpty() ? "{}" : r.body);
            JSONObject account = body.optJSONObject("account");
            if (account == null) account = body;
            writeJson(out, 200, Doubao.registerProvider(account).toString(2));
            return;
        }
        if ("/v1/deepseek/import".equals(path) && "POST".equals(r.method)) {
            JSONObject body = new JSONObject(r.body.isEmpty() ? "{}" : r.body);
            JSONObject account = body.optJSONObject("account");
            if (account == null) account = body;
            writeJson(out, 200, DeepSeek.registerProvider(account).toString(2));
            return;
        }
        if ("/v1/oauth/import".equals(path) && "POST".equals(r.method)) {
            JSONObject body = new JSONObject(r.body.isEmpty() ? "{}" : r.body);
            JSONObject account = body.optJSONObject("account");
            if (account == null) account = body;
            JSONObject res = WorkBuddy.registerProvider(account);
            writeJson(out, 200, res.toString(2));
            return;
        }
        if ("/v1/debug/wb-claim".equals(path) && "POST".equals(r.method)) {
            JSONObject body = new JSONObject(r.body.isEmpty() ? "{}" : r.body);
            String only = Proto.optString(body, "provider", "");
            String code = Proto.optString(body, "code", "");
            JSONArray res = new JSONArray();
            for (Config.Provider p : config.providers) {
                if (!Config.TYPE_WORKBUDDY.equals(p.type)) continue;
                if (!only.isEmpty() && !only.equals(p.name)) continue;
                JSONObject row = new JSONObject();
                row.put("provider", p.name);
                try {
                    row.put("claim", WbTaskRunner.claim(p, code));
                } catch (Exception e) {
                    row.put("error", String.valueOf(e.getMessage()));
                }
                res.put(row);
            }
            JSONObject dbg = new JSONObject();
            dbg.put("result", res);
            writeJson(out, 200, dbg.toString(2));
            return;
        }

        if ("/v1/debug/wb-extra".equals(path) && "POST".equals(r.method)) {
            JSONObject body = new JSONObject(r.body.isEmpty() ? "{}" : r.body);
            String only = Proto.optString(body, "provider", "");
            String action = Proto.optString(body, "action", "status");
            JSONArray res = new JSONArray();
            for (Config.Provider p : config.providers) {
                if (!Config.TYPE_WORKBUDDY.equals(p.type)) continue;
                if (!only.isEmpty() && !only.equals(p.name)) continue;
                JSONObject row = new JSONObject();
                row.put("provider", p.name);
                try {
                    switch (action) {
                        case "streak":  row.put("data", WbTaskRunner.streak(p)); break;
                        case "lottery": row.put("data", WbTaskRunner.lotterySummary(p)); break;
                        case "draw":    row.put("data", WbTaskRunner.lotteryDraw(p)); break;
                        case "buddy":   row.put("data", WbTaskRunner.buddyInfo(p)); break;
                        case "travel":  row.put("data", WbTaskRunner.travelStatus(p)); break;
                        case "depart":  row.put("data", WbTaskRunner.travelDepart(p, body.optJSONObject("body"))); break;
                        case "tclaim":  row.put("data", WbTaskRunner.travelClaim(p)); break;
                        case "redeem":  row.put("data", WbTaskRunner.redeem(p, body.opt("tier"))); break;
                        default:        row.put("data", WbTaskRunner.streak(p));
                    }
                } catch (Exception e) {
                    row.put("error", String.valueOf(e.getMessage()));
                }
                res.put(row);
            }
            JSONObject dbg = new JSONObject();
            dbg.put("result", res);
            writeJson(out, 200, dbg.toString(2));
            return;
        }

        if ("/v1/debug/fallback".equals(path)) {
            Proto.Req fake = new Proto.Req();
            fake.model = "auto";
            fake.stream = "1".equals(queryParam(r, "stream"));
            writeFallback(out, r, fake, "调试：手动触发");
            return;
        }

        if ("/v1/debug/persona".equals(path) && "POST".equals(r.method)) {
            JSONObject body = new JSONObject(r.body.isEmpty() ? "{}" : r.body);
            JSONArray raws = body.optJSONArray("raws");
            JSONArray rows = new JSONArray();
            if (raws != null) {
                for (int i = 0; i < raws.length(); i++) {
                    JSONObject row = new JSONObject();
                    String raw = raws.optString(i, "");
                    row.put("raw", raw);
                    row.put("out", Persona.strip(raw));
                    rows.put(row);
                }
            }
            JSONObject dbg = new JSONObject();
            dbg.put("result", rows);
            writeJson(out, 200, dbg.toString(2));
            return;
        }

        if ("/v1/debug/toolfix".equals(path) && "POST".equals(r.method)) {
            JSONObject body = new JSONObject(r.body.isEmpty() ? "{}" : r.body);
            JSONArray raws = body.optJSONArray("raws");
            JSONArray rows = new JSONArray();
            if (raws != null) {
                for (int i = 0; i < raws.length(); i++) {
                    JSONObject row = new JSONObject();
                    String raw = raws.optString(i, "");
                    row.put("raw", raw);
                    String fixed = ToolFix.repairArgs(raw);
                    row.put("fixed", fixed == null ? JSONObject.NULL : fixed);
                    row.put("ok", fixed != null);
                    rows.put(row);
                }
            }
            JSONObject dbg = new JSONObject();
            dbg.put("result", rows);
            writeJson(out, 200, dbg.toString(2));
            return;
        }

        if ("/v1/debug/quick-parse".equals(path) && "POST".equals(r.method)) {
            JSONObject body = new JSONObject(r.body.isEmpty() ? "{}" : r.body);
            boolean doVerify = body.optBoolean("verify", false);
            JSONArray arr = new JSONArray();
            for (com.cpa.gateway.QuickAdd.Entry e : com.cpa.gateway.QuickAdd.parse(
                    Proto.optString(body, "text", ""))) {
                if (doVerify && e.ready()) com.cpa.gateway.QuickAdd.verify(e);
                JSONObject row = new JSONObject();
                row.put("baseUrl", e.baseUrl);
                row.put("key", e.key);
                row.put("name", e.name);
                row.put("models", e.models);
                row.put("note", e.note);
                row.put("usable", e.usable);
                arr.put(row);
            }
            JSONObject dbg = new JSONObject();
            dbg.put("count", arr.length());
            dbg.put("result", arr);
            writeJson(out, 200, dbg.toString(2));
            return;
        }

        if ("/v1/debug/wb-tasks".equals(path) && "POST".equals(r.method)) {
            JSONObject body = new JSONObject(r.body.isEmpty() ? "{}" : r.body);
            String only = Proto.optString(body, "provider", "");
            JSONArray all = new JSONArray();
            for (Config.Provider p : config.providers) {
                if (!Config.TYPE_WORKBUDDY.equals(p.type)) continue;
                if (!only.isEmpty() && !only.equals(p.name)) continue;
                try {
                    all.put(new JSONObject().put("provider", p.name)
                            .put("tasks", new JSONArray(WbTaskRunner.listTasks(p).toString())));
                } catch (Exception e) {
                    all.put(new JSONObject().put("provider", p.name).put("error", String.valueOf(e.getMessage())));
                }
            }
            JSONObject dbg = new JSONObject();
            dbg.put("result", all);
            writeJson(out, 200, dbg.toString(2));
            return;
        }

        if ("/v1/debug/detect/start".equals(path) && "POST".equals(r.method)) {
            JSONObject ds = new JSONObject(r.body.isEmpty() ? "{}" : r.body);
            writeJson(out, 200, Detect.start(ds.optInt("perAccount", 3), ds.optInt("concurrency", 6)).toString(2));
            return;
        }
        if ("/v1/debug/detect/status".equals(path) && "GET".equals(r.method)) {
            writeJson(out, 200, Detect.status().toString());
            return;
        }
        if ("/v1/debug/detect/stop".equals(path) && "POST".equals(r.method)) {
            writeJson(out, 200, Detect.stop().toString());
            return;
        }
        if ("/v1/models/refresh".equals(path) && "POST".equals(r.method)) {
            handleModelsRefresh(out);
            // fire-and-forget: one minimal request per listed model, failures get parked
            verifyModelsAsync();
            return;
        }
        if (("/v1beta/models".equals(path) || "/v1beta/models/".equals(path)) && "GET".equals(r.method)) {
            writeJson(out, 200, modelsGemini().toString());
            return;
        }
        /* Azure OpenAI 客户端：路径是 /openai/deployments/<deployment>/chat/completions，
           鉴权头通常是 api-key。这里改写成标准路径并补 model。 */
        if (path.startsWith("/openai/deployments/") && "POST".equals(r.method)) {
            String rest = path.substring("/openai/deployments/".length());
            /* deployment 可能带斜杠（我们的模型名是 WB/xxx），所以从**末尾**匹配
               /chat/completions 或 /completions，而不是按第一个斜杠切。 */
            String tail = null;
            int cut = -1;
            if (rest.endsWith("/chat/completions")) {
                cut = rest.length() - "/chat/completions".length();
                tail = "chat/completions";
            } else if (rest.endsWith("/completions")) {
                cut = rest.length() - "/completions".length();
                tail = "completions";
            }
            if (cut > 0) {
                String deployment = URLDecoder.decode(rest.substring(0, cut), "UTF-8");
                JSONObject body;
                try {
                    body = new JSONObject(r.body.isEmpty() ? "{}" : r.body);
                } catch (Exception e) {
                    writeError(out, path, 400, "invalid JSON body");
                    return;
                }
                if (!body.has("model") && !deployment.isEmpty()) body.put("model", deployment);
                r.body = body.toString();
                if ("chat/completions".equals(tail)) {
                    r.path = "/v1/chat/completions";
                    handleOpenAiChat(r, out);
                    return;
                }
                if ("completions".equals(tail)) {
                    r.path = "/v1/completions";
                    handleLegacyCompletions(r, out);
                    return;
                }
            }
        }
        if ("/v1/chat/completions".equals(path) && "POST".equals(r.method)) {
            handleOpenAiChat(r, out);
            return;
        }
        /* OpenAI 旧版 Completions：很多老客户端/插件还在用，不能只支持 chat。 */
        if ("/v1/completions".equals(path) && "POST".equals(r.method)) {
            handleLegacyCompletions(r, out);
            return;
        }
        /* Ollama 原生协议：本地生态的默认入口。 */
        if ("/api/tags".equals(path) && "GET".equals(r.method)) {
            writeJson(out, 200, ollamaTags().toString());
            return;
        }
        if ("/api/version".equals(path) && "GET".equals(r.method)) {
            JSONObject v = new JSONObject();
            v.put("version", "0.6.0");
            writeJson(out, 200, v.toString());
            return;
        }
        if ("/api/ps".equals(path) && "GET".equals(r.method)) {
            JSONObject v = new JSONObject();
            v.put("models", new JSONArray());
            writeJson(out, 200, v.toString());
            return;
        }
        if ("/api/show".equals(path) && "POST".equals(r.method)) {
            JSONObject v = new JSONObject();
            JSONObject details = new JSONObject();
            details.put("format", "ahyu");
            details.put("family", "ahyu");
            details.put("families", new JSONArray().put("ahyu"));
            details.put("parameter_size", "");
            details.put("quantization_level", "");
            v.put("details", details);
            v.put("model_info", new JSONObject());
            v.put("modelfile", "");
            v.put("parameters", "");
            v.put("template", "");
            writeJson(out, 200, v.toString());
            return;
        }
        if ("/api/chat".equals(path) && "POST".equals(r.method)) {
            handleOllama(r, out, false);
            return;
        }
        if ("/api/generate".equals(path) && "POST".equals(r.method)) {
            handleOllama(r, out, true);
            return;
        }
        if (("/api/embed".equals(path) || "/api/embeddings".equals(path))
                && "POST".equals(r.method)) {
            handleOllamaEmbed(r, out, "/api/embeddings".equals(path));
            return;
        }
        if (path.startsWith("/api/pull") || path.startsWith("/api/create")
                || path.startsWith("/api/copy") || path.startsWith("/api/delete")
                || path.startsWith("/api/blobs") || path.startsWith("/api/push")) {
            handleOllamaManagement(r, out);
            return;
        }
        /* 非聊天 OpenAI 端点：原样转发给兼容上游，不做 canonical 转换。 */
        if ("/v1/embeddings".equals(path) && "POST".equals(r.method)) {
            proxyOpenAi(r, out, "embeddings", false);
            return;
        }
        /* Cohere / Jina 形状的向量化与重排（很多中转同时提供 OpenAI 兼容入口） */
        if ("/v1/embed".equals(path) && "POST".equals(r.method)) {
            proxyOpenAi(r, out, "embed", false);
            return;
        }
        if ("/v1/rerank".equals(path) && "POST".equals(r.method)) {
            proxyOpenAi(r, out, "rerank", false);
            return;
        }
        if ("/v1/moderations".equals(path) && "POST".equals(r.method)) {
            proxyOpenAi(r, out, "moderations", false);
            return;
        }
        if ("/v1/images/generations".equals(path) && "POST".equals(r.method)) {
            proxyOpenAi(r, out, "images/generations", false);
            return;
        }
        if ("/v1/audio/transcriptions".equals(path) && "POST".equals(r.method)) {
            proxyOpenAi(r, out, "audio/transcriptions", false);
            return;
        }
        if ("/v1/audio/translations".equals(path) && "POST".equals(r.method)) {
            proxyOpenAi(r, out, "audio/translations", false);
            return;
        }
        if ("/v1/audio/speech".equals(path) && "POST".equals(r.method)) {
            proxyOpenAi(r, out, "audio/speech", true);
            return;
        }
        if ("/v1/messages".equals(path) && "POST".equals(r.method)) {
            handleAnthropic(r, out);
            return;
        }
        /* Anthropic 客户端（Claude Code 等）在长对话里会主动调这个接口做"发之前先数一下"，
           不做的话它拿到 404 就可能直接放弃或按最大长度截断。见 handleCountTokens。 */
        if ("/v1/messages/count_tokens".equals(path) && "POST".equals(r.method)) {
            handleCountTokens(r, out);
            return;
        }
        if ("/v1/responses".equals(path) && "POST".equals(r.method)) {
            handleResponses(r, out);
            return;
        }
        /* Assistants / Threads：有状态接口，单独一套存储与执行。 */
        if (path.equals("/v1/assistants")
                || path.startsWith("/v1/assistants/")
                || path.equals("/v1/threads")
                || path.startsWith("/v1/threads/")) {
            handleAssistants(r, out);
            return;
        }
        if (path.startsWith("/v1beta/models/") && "POST".equals(r.method)) {
            String rest = URLDecoder.decode(path.substring("/v1beta/models/".length()), "UTF-8");
            int colon = rest.indexOf(':');
            String model = colon > 0 ? rest.substring(0, colon) : rest;
            String verb = colon > 0 ? rest.substring(colon + 1) : "generateContent";
            if ("embedContent".equals(verb) || "batchEmbedContents".equals(verb)) {
                handleGeminiEmbed(r, out, model, verb);
                return;
            }
            if ("countTokens".equals(verb)) {
                /* Gemini 客户端会先数 token 再决定怎么切上下文；404 会让它直接截断。 */
                try {
                    JSONObject body = new JSONObject(r.body.isEmpty() ? "{}" : r.body);
                    Proto.Req req = Fronts.GeminiFront.parse(model, body);
                    JSONObject o = new JSONObject();
                    o.put("totalTokens", Classifier.estimateTokens(req));
                    writeJson(out, 200, o.toString());
                } catch (Throwable t) {
                    writeError(out, path, 400, "invalid countTokens body: " + t.getMessage());
                }
                return;
            }
            boolean stream = verb.startsWith("streamGenerateContent");
            handleGemini(r, out, model, stream);
            return;
        }
        writeError(out, path, 404, "unknown route: " + path);
    }

    /* --------------------------------------------------------------- account chores */

    private static JSONObject skipRow(Config.Provider p, String why) {
        JSONObject row = new JSONObject();
        try {
            row.put("provider", p.name);
            row.put("type", p.type);
            row.put("why", why);
        } catch (Exception ignored) {
        }
        return row;
    }

    /** Why this channel has no daily chores of its own. */
    private static String whyNoChores(Config.Provider p) {
        if (Config.TYPE_CODEX.equals(p.type) || Config.TYPE_CLAUDE.equals(p.type)) {
            return "\u8be5\u7c7b\u8d26\u53f7\u6ca1\u6709\u7b7e\u5230\u6216\u6210\u957f\u4efb\u52a1";
        }
        if (Config.TYPE_ANTIGRAVITY.equals(p.type)) {
            return "Antigravity \u989d\u5ea6\u968f\u8d26\u53f7\u914d\u989d\uff0c\u6ca1\u6709\u7b7e\u5230";
        }
        if (Config.TYPE_KIMI.equals(p.type)) {
            return "Kimi \u6e20\u9053\u6682\u672a\u63a5\u7b7e\u5230";
        }
        return "\u8f6c\u53d1 / API Key \u901a\u9053\uff1a\u6ca1\u6709\u8d26\u53f7\u4efb\u52a1";
    }

    /**
     * Loomy: first-login bonus, then claim every onboarding task the server has not marked done.
     * Earlier versions only listed the pending ones and told the user to go do them by hand; the
     * claim endpoint is the same one the desktop client uses, so the gateway does it here.
     */
    private static JSONObject loomyChores(Config.Provider p) {
        JSONObject row = new JSONObject();
        try {
            row.put("provider", p.name);
            row.put("type", p.type);
            double before = Loomy.points(p);
            row.put("creditsBefore", before);
            try {
                row.put("firstLogin", Loomy.firstLogin(p));
            } catch (Exception e) {
                row.put("firstLoginError", Upstreams.shortenPublic(String.valueOf(e.getMessage())));
            }
            try {
                row.put("batch", Loomy.claimPending(p));
            } catch (Exception e) {
                row.put("error", Upstreams.shortenPublic(String.valueOf(e.getMessage())));
            }
            JSONObject after = Loomy.taskStatus(p);
            row.put("tasksTotal", after.optInt("total"));
            row.put("tasksEarned", after.optInt("earned"));
            row.put("tasksDone", after.optInt("doneCount"));
            row.put("doneList", after.optJSONArray("done"));
            row.put("pendingList", after.optJSONArray("pending"));
            try {
                row.put("daily", Loomy.petWork(p));
            } catch (Exception e) {
                row.put("dailyError", Upstreams.shortenPublic(String.valueOf(e.getMessage())));
            }
            double after2 = Loomy.points(p);
            row.put("creditsAfter", after2);
            row.put("gained", after2 >= 0 && before >= 0 ? after2 - before : 0);
            if (after2 >= 0) {
                p.credits = after2;
                p.creditsAt = System.currentTimeMillis() / 1000;
            }
            Gateway.persistSoon();
        } catch (Exception e) {
            try {
                row.put("error", Upstreams.shortenPublic(String.valueOf(e.getMessage())));
            } catch (Exception ignored) {
            }
        }
        return row;
    }

    /** Trae: daily check-in plus the resulting credit balance. */
    private static JSONObject traeChores(Config.Provider p) {
        JSONObject row = new JSONObject();
        try {
            row.put("provider", p.name);
            row.put("type", p.type);
            double before = Trae.credits(p);
            row.put("creditsBefore", before);
            row.put("checkin", Trae.checkin(p));
            double after = Trae.credits(p);
            row.put("creditsAfter", after);
            row.put("gained", after >= 0 && before >= 0 ? after - before : 0);
            p.credits = after;
            p.creditsAt = System.currentTimeMillis() / 1000;
            Gateway.persistSoon();
        } catch (Exception e) {
            try {
                row.put("error", Upstreams.shortenPublic(String.valueOf(e.getMessage())));
            } catch (Exception ignored) {
            }
        }
        return row;
    }

    private static String queryParam(Req r, String name) {
        if (r.query == null || r.query.isEmpty()) return "";
        for (String kv : r.query.split("&")) {
            int eq = kv.indexOf('=');
            if (eq <= 0) continue;
            if (name.equals(kv.substring(0, eq))) {
                try {
                    return URLDecoder.decode(kv.substring(eq + 1), "UTF-8");
                } catch (Exception e) {
                    return kv.substring(eq + 1);
                }
            }
        }
        return "";
    }

    /**
     * 这次请求用的是哪个 Key（匹配上就返回它，否则空串）。
     *
     * 单独的 usedKey 而不是 boolean：用量统计要按客户端 Key 分组（多设备/多客户端时看谁用了多少）。
     * 返回的是明文，写进用量前会 maskKey 脱敏。
     */
    /** ?key= 警告只打一次，避免刷屏（这个路径可能每次请求都走）。 */
    private final java.util.concurrent.atomic.AtomicBoolean queryKeyWarned =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private String usedKey(Req r) {
        if (config.apiKey == null || config.apiKey.isEmpty()) return "";
        // 说明：局域网模式下"没设 Key"由调用处直接拒绝（见 route 里的 keyRequired）
        String auth = r.header("authorization");
        String token = "";
        if (auth.toLowerCase(Locale.US).startsWith("bearer ")) token = auth.substring(7).trim();
        if (token.isEmpty()) token = r.header("x-api-key").trim();
        if (token.isEmpty()) token = r.header("x-goog-api-key").trim();
        /* Azure OpenAI 客户端用 api-key 头。 */
        if (token.isEmpty()) token = r.header("api-key").trim();
        if (token.isEmpty() && !r.query.isEmpty()) {
            /*
             * ?key= 形式：Gemini 官方就这么传，本机回环下保留（删掉会破坏兼容）。
             *
             * 风险大小取决于监听范围，所以判据不是"能不能用"，而是"会不会被别人看见"：
             *   · 只监听 127.0.0.1：URL 不出网卡，浏览器历史 / Referer 都限于本机自己，
             *     风险可接受，保兼容优先。
             *   · 开了局域网（0.0.0.0）：同一个 key 会走 Wi-Fi，出现在对端设备的 URL 记录、
             *     代理日志、抓包里。这时"只警告不拒绝"等于把风险放到用户看不见的地方，
             *     所以直接拒绝，让问题在配置那一刻暴露。
             *
             * 路径日志只记 r.path、不含 query，key 本身不会进日志。
             */
            for (String kv : r.query.split("&")) {
                int eq = kv.indexOf('=');
                if (eq > 0 && "key".equals(kv.substring(0, eq))) {
                    token = URLDecoder.decode(kv.substring(eq + 1));
                    if (config.lanAccess) {
                        if (queryKeyWarned.compareAndSet(false, true)) {
                            Log.w("局域网模式下拒绝用 ?key= 传密钥（URL 会经 Wi-Fi 外传，可能落进"
                                    + "对端浏览器历史 / 代理日志 / 抓包）。请改用 Authorization: Bearer "
                                    + "或 x-api-key 头。此提示只打一次。");
                        }
                        return "";
                    }
                    if (queryKeyWarned.compareAndSet(false, true)) {
                        Log.w("检测到用 ?key= 传密钥。本机回环下仍接受，但比请求头危险（URL 会进浏览器"
                                + "历史 / Referer / 复制粘贴），建议改用 Authorization: Bearer 或 "
                                + "x-api-key 头。此提示只打一次。");
                    }
                }
            }
        }
        return config.acceptsKey(token) ? token : "";
    }

    /** 脱敏：只留头尾几位，别的用圆点。空串给个"默认"。 */
    static String maskKey(String key) {
        if (key == null || key.isEmpty()) return key == null ? "" : "（未设 Key）";
        String k = key.trim();
        if (k.length() <= 10) return k;
        return k.substring(0, 6) + "…" + k.substring(k.length() - 4);
    }

    /* --------------------------------------------------------------- response io */

    private static void writeRaw(OutputStream out, int status, String contentType, byte[] body, boolean noBody) throws Exception {
        if (status >= 400) markResponseFailed(out);
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(status).append(' ').append(statusText(status)).append("\r\n");
        h.append("Content-Type: ").append(contentType).append("\r\n");
        h.append("Content-Length: ").append(body.length).append("\r\n");
        h.append("Access-Control-Allow-Origin: *\r\n");
        h.append("Access-Control-Allow-Headers: *\r\n");
        h.append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n");
        Conn conn = connFor(out);
        boolean keep = conn != null && conn.keepAlive;
        h.append("Connection: ").append(keep ? "keep-alive" : "close").append("\r\n");
        if (keep) {
            // 声明值必须等于服务端真实回收时间，别让客户端留一条已经半关的连接
            h.append("Keep-Alive: timeout=").append(KEEPALIVE_IDLE_MS / 1000)
                    .append(", max=").append(KEEPALIVE_MAX_REQUESTS).append("\r\n");
        }
        h.append("\r\n");
        try {
            out.write(h.toString().getBytes(StandardCharsets.UTF_8));
            if (!noBody && body.length > 0) out.write(body);
            out.flush();
        } catch (java.io.IOException io) {
            /* 写客户端失败 = 客户端不在了，不是上游/账号的问题。
               转成 ClientGone，避免被记成渠道失败、再换个号重发一次完整 completion。 */
            throw new ClientGone("写客户端失败：" + io.getMessage());
        }
    }

    private static void writeJson(OutputStream out, int status, String body) throws Exception {
        writeRaw(out, status, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8), false);
    }

    /**
     * 延迟提交的 SSE 写端。
     *
     * 自动换号最难的一点：流式响应一旦把 200 和响应头写出去，就换不了号了。
     * 所以这里先**只往内存里写**，等上游真的吐出回答内容了才落盘提交；
     * 在那之前失败 → 缓冲区直接丢掉，换个账号重来，客户端完全无感。
     */
    private static final class DeferredWriter extends java.io.Writer {
        private final OutputStream out;
        private final StringBuilder buf = new StringBuilder();
        private BufferedWriter real;
        private boolean committed;

        // 竞速用：这一路的身份，以及共享的胜负状态
        String racerId;
        RaceRace race;
        ChunkedStream chunked;

        DeferredWriter(OutputStream out) {
            this.out = out;
        }

        boolean committed() {
            return committed;
        }

        /** 上游开始出内容了：写响应头 + 吐缓冲，之后直写。 */
        void commit() throws Exception {
            if (committed) return;
            // 竞速已收尾（赢家跑完 / 超时）：迟到的这路不能再写响应头，
            // 否则同一个 socket 上会出现第二个 HTTP 响应，客户端解析直接崩。
            if (race != null && race.closed) return;
            SseOut opened = openSse(out);
            real = opened.writer;
            this.chunked = opened.chunked;
            Conn cc = connFor(out);
            if (cc != null) cc.chunkedEncoding = opened.chunked != null;
            if (race != null && racerId != null && racerId.equals(race.winnerId.get())) {
                race.winnerChunked = opened.chunked;   // 收尾交给竞速协调者
            }
            real.write(buf.toString());
            real.flush();
            buf.setLength(0);
            committed = true;
        }

        @Override
        public void write(char[] cbuf, int off, int len) throws java.io.IOException {
            if (committed) {
                real.write(cbuf, off, len);
                return;
            }
            // 竞速：别人已经赢了 → 立刻抛出去，把这条上游连接掐掉（省额度）
            if (race != null && race.winnerId.get() != null
                    && !race.winnerId.get().equals(racerId)) {
                throw new RaceLost(racerId);
            }
            buf.append(cbuf, off, len);
            // 只出现"角色"那种前导事件不算数，等真的来内容了才提交
            if (buf.indexOf("\"content\"") >= 0 || buf.indexOf("content_block_delta") >= 0
                    || buf.indexOf("\"tool_calls\"") >= 0 || buf.length() > 8192) {
                // 竞速：谁先出内容谁赢；没抢到就直接认输退出
                if (race != null && !race.tryWin(racerId)) {
                    throw new RaceLost(racerId);
                }
                try {
                    commit();
                } catch (Exception e) {
                    throw new java.io.IOException(e);
                }
            }
        }

        @Override
        public void flush() throws java.io.IOException {
            if (committed) real.flush();
        }

        @Override
        public void close() throws java.io.IOException {
            if (committed) real.close();
        }
    }

    /** SSE 的两件东西：写端 + chunked 流（收尾要它写 0\r\n\r\n）。 */
    private static final class SseOut {
        final BufferedWriter writer;
        final ChunkedStream chunked;

        SseOut(BufferedWriter writer, ChunkedStream chunked) {
            this.writer = writer;
            this.chunked = chunked;
        }
    }

    private static BufferedWriter startSse(OutputStream out) throws Exception {
        SseOut sseOpened = openSse(out);
        Conn sseConn = connFor(out);
        if (sseConn != null) sseConn.chunkedEncoding = sseOpened.chunked != null;
        return sseOpened.writer;
    }

    /** Ollama 的 NDJSON 流：没有 SSE 的 `data:` 前缀，也不需要心跳注释。 */
    private static BufferedWriter startNdjson(OutputStream out) throws Exception {
        String head = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: application/x-ndjson; charset=utf-8\r\n"
                + "Cache-Control: no-cache\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Transfer-Encoding: chunked\r\n"
                + "Connection: keep-alive\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.flush();
        Conn c = connFor(out);
        ChunkedStream chunked = new ChunkedStream(out);
        BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(chunked, StandardCharsets.UTF_8), 8192);
        if (c != null) {
            c.chunked = chunked;
            c.chunkedEncoding = true;
            c.sse = false;
        }
        return writer;
    }

    private static SseOut openSse(OutputStream out) throws Exception {
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 200 OK\r\n");
        h.append("Content-Type: text/event-stream; charset=utf-8\r\n");
        h.append("Cache-Control: no-cache\r\n");
        h.append("Access-Control-Allow-Origin: *\r\n");
        h.append("Access-Control-Allow-Headers: *\r\n");
        h.append("Transfer-Encoding: chunked\r\n");
        h.append("Connection: keep-alive\r\n\r\n");
        out.write(h.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
        Conn c = connFor(out);
        ChunkedStream chunked = new ChunkedStream(out);
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(chunked, StandardCharsets.UTF_8), 8192);
        if (c != null) {
            c.chunked = chunked;
            c.sse = true;
            /* keep-alive 连接可能已经空闲很久；从这一刻重新计时，
               否则新流刚开始就会被旧时间戳判成"上游静默 180 秒"。 */
            c.lastUpstreamAt = System.currentTimeMillis();
            /* 看门狗用 lastPing 判"这条流超过 30 秒没写出心跳"。它只在连接构造时
               初始化过一次；一条 keep-alive 连接超过 30 秒后再开流，第一次扫描
               会在首个 10 秒 ping 之前关掉健康的新流。开流时同步重置。 */
            c.lastPing = System.currentTimeMillis();
            try {
                synchronized (writer) {
                    writer.write(": ready\n\n");
                    writer.flush();
                }
            } catch (Throwable ignored) {
            }
            // 原来每条流 new 一个 Timer（1 线程/流），并发一高全是空转线程；
            // 现在走共享 2 线程调度器，心跳逻辑不变
            final java.util.concurrent.ScheduledFuture<?>[] self =
                    new java.util.concurrent.ScheduledFuture<?>[1];
            Runnable ping = new Runnable() {
                private void stop() {
                    java.util.concurrent.ScheduledFuture<?> f = self[0];
                    if (f != null) f.cancel(false);
                }

                @Override
                public void run() {
                    try {
                        /* 判据只看"上游最后一次给内容"的时间，不看我们写了多少字节。
                           旧实现用 client 方向的计数，ping 自己也会让它变化，
                           结果 stalls 永远是 0：既不会发超时，也不会在真正静默时 ping。 */
                        long silentMs = System.currentTimeMillis() - c.lastUpstreamAt;
                        synchronized (writer) {
                            if (silentMs >= (long) UPSTREAM_IDLE_PERIODS * PING_PERIOD_MS) {
                                Log.w("上游 " + (UPSTREAM_IDLE_PERIODS * PING_PERIOD_MS / 1000)
                                        + " 秒没有出字，已通知客户端（upstream_timeout）");
                                c.responseFailed = true;
                                writer.write("data: {\"error\":{\"message\":\"上游超过 "
                                        + (UPSTREAM_IDLE_PERIODS * PING_PERIOD_MS / 1000)
                                        + " 秒没有返回内容，请重试\",\"type\":\"upstream_timeout\",\"code\":\"upstream_timeout\"}}\n\n");
                                writer.write("data: [DONE]\n\n");
                                writer.flush();
                                stop();
                                return;
                            }
                            // 心跳本身不是上游活动，只用来防止客户端/中间层空闲断开
                            writer.write(": ping\n\n");
                            writer.flush();
                            // 真写出去了才记时间戳：写被阻塞时它不动，看门狗据此判定僵尸流
                            c.lastPing = System.currentTimeMillis();
                        }
                    } catch (Throwable e) {
                        stop();
                    }
                }
            };
            /*
             * 周期用 PING_PERIOD_MS，和 STREAM_PING_STALL_MS 同源。
             * 这两个数字必须派生自同一个常量：阈值是"几个周期"的语义，
             * 写成两个独立的字面量时，改一个就会让另一个的语义悄悄失效
             * （这正是原来 45 秒 / 10 秒 / 30 秒三个数字互不相干的问题）。
             */
            self[0] = Threads.PING.scheduleAtFixedRate(ping,
                    PING_PERIOD_MS, PING_PERIOD_MS,
                    java.util.concurrent.TimeUnit.MILLISECONDS);
            c.pinger = self[0];
        }
        return new SseOut(writer, chunked);
    }

    private static String statusText(int status) {
        switch (status) {
            case 200: return "OK";
            case 201: return "Created";
            case 202: return "Accepted";
            case 204: return "No Content";
            case 206: return "Partial Content";
            case 301: return "Moved Permanently";
            case 302: return "Found";
            case 304: return "Not Modified";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 408: return "Request Timeout";
            case 409: return "Conflict";
            case 413: return "Payload Too Large";
            case 415: return "Unsupported Media Type";
            case 422: return "Unprocessable Entity";
            case 429: return "Too Many Requests";
            case 500: return "Internal Server Error";
            case 501: return "Not Implemented";
            case 502: return "Bad Gateway";
            case 503: return "Service Unavailable";
            case 504: return "Gateway Timeout";
            default: return "Status";
        }
    }

    private void writeError(OutputStream out, String path, int status, String message) throws Exception {
        writeError(out, path, status, message, null);
    }

    /**
     * 带失败类别的错误响应。
     *
     * 为什么要多带一个字段：客户端拿到的错误以前只有 message + type=api_error，
     * 于是"渠道校验被拒（要换号）""额度用完（要充值）""被限流（等一会儿）"在客户端看来
     * 完全一样 —— 都是"重连失败"。上游明明给了不同的判定，我们在最后一公里把它抹平了。
     * 现在把 kind（rate\/quota\/auth\/channel\/local…）一并放出来，
     * 客户端和用户都能据此决定"等、换、还是充值"。
     */
    private void writeError(OutputStream out, String path, int status, String message, String kind)
            throws Exception {
        /* 4.36.1：这类错误是"正常返回一个错误响应"，不抛异常，
           所以 catch 里拿不到。在这里记一笔，才能归类到 usage.tsv。
           400/404 这类多半是**请求侧**问题 —— 换号更没用。 */
        Conn cc = connFor(out);
        if (cc != null && (cc.errorKind == null || cc.errorKind.isEmpty() || ErrorKind.OTHER.equals(cc.errorKind))) {
            if (kind != null && !kind.isEmpty()) {
                cc.errorKind = kind;
            } else if (status == 400 || status == 404 || status == 422) {
                cc.errorKind = ErrorKind.BADREQ;
            } else if (status == 401 || status == 403) {
                cc.errorKind = ErrorKind.AUTH;
            } else if (status == 429) {
                cc.errorKind = ErrorKind.RATE;
            } else if (status >= 500) {
                cc.errorKind = ErrorKind.OTHER;
            }
        }
        JSONObject o = new JSONObject();
        if (path.startsWith("/api/")) {
            /* Ollama 的错误体是 {"error":"..."}，不是 OpenAI 的嵌套结构。 */
            o.put("error", message);
        } else if (path.startsWith("/v1/messages")) {
            JSONObject e = new JSONObject();
            e.put("type", "api_error");
            e.put("message", message);
            o.put("type", "error");
            o.put("error", e);
        } else if (path.startsWith("/v1beta")) {
            JSONObject e = new JSONObject();
            e.put("code", status);
            e.put("message", message);
            e.put("status", status == 401 ? "UNAUTHENTICATED" : "INVALID_ARGUMENT");
            o.put("error", e);
        } else {
            JSONObject e = new JSONObject();
            e.put("message", message);
            /* 错误类别也反映到 type/code：客户端用 OpenAI SDK 时，
               "模型坏了"和"账号坏了"至少能在结构上分开，不必去读中文文案。 */
            String type = "api_error";
            String code = "error";
            if (status == 401) {
                type = "authentication_error";
                code = "invalid_api_key";
            } else if ("model".equals(kind)) {
                type = "model_error";
                code = "model_error";
            } else if ("badreq".equals(kind)) {
                type = "invalid_request_error";
                code = "invalid_request";
            }
            e.put("type", type);
            e.put("code", code);
            if (kind != null && !kind.isEmpty()) e.put("kind", kind);
            o.put("error", e);
        }
        writeJson(out, status, o.toString());
    }

    /* ------------------------------------------------------------------- models */

    /**
     * 对外公布的模型清单。
     *
     * 每个账号目录里有什么就列什么：不同账号之间出现同名模型是正常的，
     * 不当作错误、不去重合并、也不因为探活失败而隐藏——账号有几个模型这里就有几个。
     * 名字保持上游给的原始写法（路由本身大小写不敏感）。
     */
    private String shortLabel(Config.Provider target) {
        return ShortName.of(config.providers, target);
    }

    /** 给界面用的同一个口径。 */
    public static String shortLabelOf(Config.Provider target) {
        return ShortName.of(Gateway.config().providers, target);
    }

    /** 按短标签找渠道（客户端发过来的就是它）。 */
    private Config.Provider byShortLabel(String prefix) {
        if (prefix == null || prefix.isEmpty()) return null;
        for (Config.Provider p : config.providers) {
            if (p.dispatchable() && shortLabel(p).equalsIgnoreCase(prefix)) return p;
        }
        return null;
    }

    private List<String> allModels() {
        List<String> list = new ArrayList<>();
        int nonChatSkipped = 0;
        // 虚拟模型排最前：客户端写一个名字就能自动选路+自动换号
        for (String v : Router.virtuals()) list.add(v);
        for (Config.Provider p : config.providers) {
            if (!p.dispatchable()) continue;
            for (String m : p.models) {
                if ("*".equals(m)) continue;
                // 同名不代表同一个模型：每个账号自己那一份是独立的、可单独调用的，
                // 所以按"账号/模型"逐条列出，不做任何合并。
                // 但实测跑不通的不对外提供，免得别人拿去调不通。
                if (p.badModels.contains(m)) continue;
                /* 生图 / 生视频 / 补全 / 重写这类**不是聊天模型**的条目不要再列出去。
                   实测它们选中后必然失败（"Backend [maas] is not supported"、
                   "该模型暂未开放"），客户端把它们列给用户选，等于制造"好多模型用不了"。
                   注意只影响对外列表：手动点名（前缀/裸名）仍然可用，
                   所以真想试的人不会被挡住。 */
                if (Router.nonChat(m)) {
                    nonChatSkipped++;
                    continue;
                }
                list.add(shortLabel(p) + "/" + m);
            }
        }
        if (nonChatSkipped > 0) {
            Log.i("模型列表：已隐藏 " + nonChatSkipped + " 条非聊天模型（生图/生视频/补全类，选了也调不通）");
        }
        return list;
    }

    /** Re-reads every provider's model catalogue (used by the UI button and by scripts). */
    /**
     * 刷新模型时顺带更新该账号的积分。
     *
     * 为什么要合并：模型拉取与积分查询都要打同一个上游，Trae 这类限流紧的通道
     * 并发时必有一个失败（表现为积分被写成 -1）。串行放在模型拉取之后，
     * 并在失败时保留原值而不是覆盖成 -1。
     */
    private void refreshCreditsQuietly(Config.Provider p, JSONObject row) {
        if (!p.dispatchable()) return;
        double value = -1;
        try {
            if (Config.TYPE_WORKBUDDY.equals(p.type)) {
                value = WbTasks.credits(p);
            } else if (Config.TYPE_TRAE.equals(p.type)) {
                value = Trae.credits(p);
            } else if (Config.TYPE_LOOMY.equals(p.type)) {
                value = Loomy.points(p);
            } else {
                return;   // 该类型没有积分概念，不动既有值
            }
        } catch (Throwable t) {
            Log.w("积分刷新失败（保留原值）：" + p.name + " · " + t);
            return;
        }
        if (value >= 0) {
            p.credits = value;
            p.creditsAt = System.currentTimeMillis() / 1000;
            try { row.put("credits", value); } catch (Exception ignored) {
            }
        }
        // value < 0 时不覆盖：宁可留上一次的数值，也不要因为一次限流把积分抹成 -1
    }

    private void handleModelsRefresh(OutputStream out) throws Exception {
        JSONArray results = new JSONArray();
        for (Config.Provider p : config.providers) {
            if (!p.dispatchable()) continue;
            JSONObject row = new JSONObject();
            row.put("provider", p.name);
            row.put("type", p.type);
            try {
                List<String> models = Upstreams.fetchModels(p);
                p.setModels(models);
                row.put("ok", true);
                row.put("count", p.models.size());
                row.put("models", new JSONArray(models));
                // 顺带更新积分：合并成一次刷新，避免与 /v1/credits/refresh 并发打同一上游
                refreshCreditsQuietly(p, row);
            } catch (Exception e) {
                row.put("ok", false);
                row.put("error", String.valueOf(e.getMessage()));
            }
            results.put(row);
        }
        Gateway.persistSoon();
        JSONObject o = new JSONObject();
        o.put("refreshed", results);
        writeJson(out, 200, o.toString(2));
    }

    /** Pulls every plausible model name out of a config-style payload. */
    static void collectModels(Object node, java.util.List<String> out, int depth) {
        if (node == null || depth > 6) return;
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            Object models = o.opt("models");
            if (models instanceof JSONArray) {
                JSONArray arr = (JSONArray) models;
                for (int i = 0; i < arr.length(); i++) {
                    Object item = arr.opt(i);
                    String id = null;
                    if (item instanceof JSONObject) {
                        JSONObject m = (JSONObject) item;
                        id = Proto.optString(m, "model_name", Proto.optString(m, "name", Proto.optString(m, "id", "")));
                    } else if (item instanceof String) {
                        id = (String) item;
                    }
                    if (id != null && !id.isEmpty() && !out.contains(id)) out.add(id);
                }
            }
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) collectModels(o.opt(it.next()), out, depth + 1);
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) collectModels(arr.opt(i), out, depth + 1);
        }
    }

    /**
     * Reports a failure without corrupting an already-started stream. Writing an HTTP status line
     * into a live SSE body is what makes clients report "reconnect failed", so once streaming has
     * begun the error travels as an SSE event instead.
     */
    void failResponse(OutputStream out, String path, String message) {
        failResponse(out, path, 500, message);
    }

    void failResponse(OutputStream out, String path, int status, String message) {
        String safe = message == null ? "unknown error"
                : message.replace("\\", " ").replace("\"", "'").replace("\n", " ").replace("\r", " ");
        Conn c = connFor(out);
        if (c != null && c.sse) {
            c.responseFailed = true;
            try {
                String payload = "{\"error\":{\"message\":\"" + safe + "\",\"type\":\"upstream_error\",\"code\":\"error\"}}";
                byte[] bytes = ("data: " + payload + "\n\n").getBytes(StandardCharsets.UTF_8);
                // 流是 chunked 编码的，裸写会被客户端判成"分帧损坏"。
                // 包一层 chunk 头，再把 [DONE] 与结束块补上（严格等 DONE 的客户端才不会当成被截断）。
                if (c.chunkedEncoding) {
                    out.write((Integer.toHexString(bytes.length) + "\r\n").getBytes(StandardCharsets.UTF_8));
                    out.write(bytes);
                    out.write("\r\n".getBytes(StandardCharsets.UTF_8));
                    byte[] done = "data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8);
                    out.write((Integer.toHexString(done.length) + "\r\n").getBytes(StandardCharsets.UTF_8));
                    out.write(done);
                    out.write("\r\n".getBytes(StandardCharsets.UTF_8));
                    out.write("0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                } else {
                    // 非 chunked 分支同样要补 [DONE]：以前这里只写了错误 payload，
                    // 严格等 DONE 的客户端会挂到超时。
                    out.write(bytes);
                    out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                }
                out.flush();
                Log.w("已在流内回报错误：" + safe);
            } catch (Throwable ignored) {
            }
            return;
        }
        try {
            writeError(out, path, status, safe);
        } catch (Throwable ignored) {
        }
    }

    private static String cap(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    private JSONObject modelsOpenAi() throws Exception {
        JSONObject out = new JSONObject();
        out.put("object", "list");
        JSONArray data = new JSONArray();
        for (String m : allModels()) {
            JSONObject o = new JSONObject();
            o.put("id", m);
            o.put("object", "model");
            o.put("created", System.currentTimeMillis() / 1000);
            o.put("owned_by", ownerOf(m));
            // 能力字段是**标准 OpenAI /v1/models 之外**的扩展。
            // 加它们的原因：客户端（代鱼、各类 IDE 插件）拿到模型列表后
            // 想显示"上下文 1M / 多模态 / 工具"，但标准格式里没有这些信息，
            // 于是只能显示"上下文未知"。
            //
            // 用业界常见命名（context_length / max_output_tokens / capabilities），
            // 客户端若按常见规范解析就能直接显示；不认识的客户端会忽略未知字段，
            // 不影响兼容性。
            //
            // 查不到能力的模型**不加这些字段**（不写 0、不写 null）——
            // 让"没有信息"和"信息为 0"区分开。
            String bare = m;
            int slash = m.indexOf('/');
            if (slash > 0) bare = m.substring(slash + 1);
            ModelCaps.Caps caps = ModelCaps.lookup(bare);
            if (caps != null && caps.any()) {
                if (caps.context > 0) o.put("context_length", caps.context);
                if (caps.maxOutput > 0) o.put("max_output_tokens", caps.maxOutput);
                JSONObject cp = new JSONObject();
                cp.put("vision", caps.has(ModelCaps.VISION));
                cp.put("tools", caps.has(ModelCaps.TOOLS));
                cp.put("reasoning", caps.has(ModelCaps.REASONING));
                cp.put("audio", caps.has(ModelCaps.AUDIO));
                cp.put("pdf", caps.has(ModelCaps.PDF));
                cp.put("web", caps.has(ModelCaps.WEB));
                o.put("capabilities", cp);
            }
            data.put(o);
        }
        out.put("data", data);
        return out;
    }

    private JSONObject modelsGemini() throws Exception {
        JSONObject out = new JSONObject();
        JSONArray arr = new JSONArray();
        for (String m : allModels()) {
            JSONObject o = new JSONObject();
            o.put("name", "models/" + m);
            o.put("displayName", m);
            o.put("description", ownerOf(m));
            JSONArray methods = new JSONArray();
            methods.put("generateContent");
            methods.put("streamGenerateContent");
            o.put("supportedGenerationMethods", methods);
            arr.put(o);
        }
        out.put("models", arr);
        return out;
    }

    private String ownerOf(String model) {
        // "账号/模型" 直接就是归属；裸名字再按目录找
        int slash = model == null ? -1 : model.indexOf('/');
        if (slash > 0) {
            String prefix = model.substring(0, slash);
            Config.Provider p = byShortLabel(prefix);
            if (p != null) return shortLabel(p);
            for (Config.Provider q : config.providers) {
                if (q.enabled && (q.name.equalsIgnoreCase(prefix) || q.id.equalsIgnoreCase(prefix))) {
                    return q.name;
                }
            }
            return prefix;
        }
        for (Config.Provider p : config.providers) {
            if (p.dispatchable() && p.matches(model)) return p.name;
        }
        return "unknown";
    }

    /** Resolves a client model id to a provider; "provider/model" pins the channel explicitly. */
    private Config.Provider pick(String model) {
        if (model == null) model = "";
        int slash = model.indexOf('/');
        if (slash > 0) {
            String prefix = model.substring(0, slash);
            Config.Provider byShort = byShortLabel(prefix);
            if (byShort != null) return byShort;
            for (Config.Provider p : config.providers) {
                if (p.dispatchable() && (p.name.equalsIgnoreCase(prefix) || p.id.equalsIgnoreCase(prefix))) return p;
            }
        }
        List<Config.Provider> matches = new ArrayList<>();
        for (Config.Provider p : config.providers) {
            if (p.dispatchable() && p.matches(model)) matches.add(p);
        }
        if (matches.isEmpty()) {
            for (Config.Provider p : config.providers) {
                /* 必须用 dispatchable()：catalogOnly 的语义是"只出现在目录里、
                   不参与任何派发"，这里只查 enabled 会让 models=["*"] 的
                   仅目录账号仍然被选中。 */
                if (p.dispatchable()) {
                    for (String m : p.models) if ("*".equals(m)) matches.add(p);
                }
            }
        }
        if (matches.isEmpty()) return null;

        // 熔断/限流回避检查 —— 这条路径以前**完全绕过**熔断：
        // 只按 enabled + matches 轮询挑一个就发，于是 OPEN 的 key 照样打出去、
        // 冷却形同虚设（客户端直接写具体模型名就能绕开所有保护）。
        // 现在优先挑"可用"的；但**必须保底**：如果全都在冷却里，
        // 仍然照原样返回一个，否则整个服务会变成 404，比偶尔撞一次熔断更糟。
        List<Config.Provider> usable = new ArrayList<>();
        for (Config.Provider p : matches) {
            if (Router.available(p.name + "/" + upstreamModel(p, model))) usable.add(p);
        }
        if (!usable.isEmpty()) matches = usable;
        else Log.w("直连路径：模型 " + model + " 的所有候选都在冷却/限流回避中，仍按原样放行一个");

        int i = Math.abs(rr.getAndIncrement() % matches.size());
        return matches.get(i);
    }

    /**
     * 直连路径的**多候选**版本：按模型名给出所有可用账号，已按分数从高到低排序。
     *
     * 为什么需要：以前 pick() 只返回一个，而它内部是**轮询**挑的
     * （`rr.getAndIncrement() % matches.size()`）。同名模型散在多个账号上时
     * （实测 step-5-preview / kimi-k2.6 / glm-5.3 各覆盖 6 个账号，hy3 覆盖 10 个），
     * 轮询意味着"这次轮到谁就是谁"——一个慢的、刚报过错的账号照样会被选中。
     *
     * 虚拟模型（auto 等）走 Router.candidates 是按分数选的，直连这条路却没有。
     * 客户端把 276 条按模型名去重成 141 个之后，调用的正是裸名，所以这条路径的手感
     * 直接决定了去重后的使用体验。
     *
     * 熔断/限流回避的过滤保留（与 pick() 一致）：不可用的排最后，而不是直接丢掉 ——
     * 全在冷却时仍要能发出去，否则整个服务变 404。
     */
    private List<Config.Provider> pickAll(String model) {
        if (model == null) model = "";
        /* 带前缀的"点名"路由。
         *
         * 曾经这里返回**单候选**，理由是"用户点名了就照办"。但实测发现这条路把自动换号
         * 整个废掉了：/v1/models 对外给出的名字**只有**带前缀的形式（见 allModels），
         * 也就是说客户端手里根本没有裸名可选 —— 用户从列表里选一个模型，
         * 得到的恰恰是"永远只用那一个账号"的那种名字。
         *
         * 真实故障：某账号被上游限流（429），点名请求直接失败；换成裸名立刻换号成功。
         * 于是用户看到的现象是"好多模型都用不了"，而其实同一模型在别的账号上活得好好的。
         *
         * 现在的语义是**优先而非锁定**：点名那个账号排第一（正常时行为和以前完全一样），
         * 它失败了再按分数用同名模型的其它账号兜底。既保留了"我想先用这个号"的表达能力，
         * 又不会因为一个号被限流就报错。 */
        int slash = model.indexOf('/');
        if (slash > 0) {
            String prefix = model.substring(0, slash);
            Config.Provider pinned = byShortLabel(prefix);
            if (pinned == null) {
                for (Config.Provider p : config.providers) {
                    if (p.dispatchable() && (p.name.equalsIgnoreCase(prefix) || p.id.equalsIgnoreCase(prefix))) {
                        pinned = p;
                        break;
                    }
                }
            }
            if (pinned != null) return pinnedThenFallbacks(pinned, model.substring(slash + 1));
            /* 前缀不认识（客户端把模型名本身当成了前缀）：当作裸名继续走下面的常规路径，
               比直接返回空（404）更宽容。 */
        }
        List<Config.Provider> matches = new ArrayList<>();
        for (Config.Provider p : config.providers) {
            if (p.dispatchable() && p.matches(model)) matches.add(p);
        }
        if (matches.isEmpty()) {
            for (Config.Provider p : config.providers) {
                /* 必须用 dispatchable()：catalogOnly 的语义是"只出现在目录里、
                   不参与任何派发"，这里只查 enabled 会让 models=["*"] 的
                   仅目录账号仍然被选中。 */
                if (p.dispatchable()) {
                    for (String m : p.models) if ("*".equals(m)) matches.add(p);
                }
            }
        }
        if (matches.isEmpty()) return matches;

        // 按分数排序（与虚拟模型同一套打分）
        List<Router.Pick> scored = new ArrayList<>();
        for (Config.Provider p : matches) {
            Router.Pick c = Router.newPick(p, upstreamModel(p, model));
            c.score = Router.scorePublic(c, false);
            scored.add(c);
        }
        // 可用性优先：不可用的沉底，但**不丢弃**（全冷却时仍要能发）
        scored.sort((x, y) -> {
            boolean ax = Router.available(x.key()), ay = Router.available(y.key());
            if (ax != ay) return ax ? -1 : 1;
            return Double.compare(y.score, x.score);
        });
        List<Config.Provider> out = new ArrayList<>();
        for (Router.Pick c : scored) out.add(c.provider);
        return out;
    }

    /**
     * 模型名找不到任何提供者时的错误文案。
     *
     * 原来只有一句 "no provider configured for model: X" —— 对"模型名拼错"和
     * "本地目录过期"这两种完全可自解的情况，它都只是陈述事实，没告诉用户下一步做什么。
     * 实测用户看到客户端报"重连失败"时，第一反应是网关坏了，而不是"我这个模型名不对"。
     */
    private static String unknownModelMessage(String model) {
        return "没有账号提供模型 " + model + "（模型名可能拼错，或本地模型目录已过期）；"
                + "可在应用内点「刷新模型目录」后重试，或改用 auto";
    }

    /**
     * 点名了一个账号，但同名模型在别的账号上也有：把点名的排第一，其余按分数跟在后面。
     *
     * 排序与虚拟模型、裸名路径用的是同一套打分（{@link Router#scorePublic}），
     * 所以"兜底候选"的顺序和用户什么都不点名时是一致的 —— 不会出现"点名兜底"反而
     * 挑到一个更差的账号。不可用的沉底但不丢弃（全在冷却时仍要能发出去）。
     */
    private List<Config.Provider> pinnedThenFallbacks(Config.Provider pinned, String bare) {
        List<Config.Provider> out = new ArrayList<>();
        /* 点名的账号**真的有**这个模型，才让它排第一。
         *
         * 原来这里是无条件 out.add(pinned)。于是客户端发 "WB3/gpt-6.1"（模型名打错、
         * 或者本地模型目录过期）时，网关会把 "gpt-6.1" 原样发给 WB3 —— 上游必然回
         * 400 "model [gpt-6.1] service info not found"。而这个失败发生在**流式响应头
         * 已经写出去之后**，客户端只能看到一个没有任何内容就结束了的流，
         * 表现就是"重连失败"，和真正的网络断开长得一模一样，根本看不出是模型名的问题。
         *
         * 现在：点名的账号没这个模型就不进候选。如果谁都没有，pickAll 返回空，
         * 调用方会在写出响应头**之前**回一个干净的 404。
         *
         * 带 "*" 通配的账号不受影响（matches 认通配），那种账号本来就接受任意模型名。 */
        boolean pinnedHas = bare != null && !bare.isEmpty() && pinned.matches(bare);
        if (bare == null || bare.isEmpty() || pinnedHas) out.add(pinned);
        if (bare == null || bare.isEmpty()) return out;
        if (!pinnedHas) {
            Log.w("点名的账号 " + pinned.name + " 没有模型 " + bare
                    + "（它的模型目录里没有这一条），本次不把它算作候选");
        }
        List<Config.Provider> others = new ArrayList<>();
        for (Config.Provider p : config.providers) {
            if (p == pinned || !p.dispatchable()) continue;
            if (p.matches(bare)) others.add(p);
        }
        if (others.isEmpty()) {
            if (out.isEmpty()) {
                Log.w("模型 " + bare + " 没有任何账号提供：直接回 404，不向上游发起注定失败的请求");
            }
            return out;
        }
        List<Router.Pick> scored = new ArrayList<>();
        for (Config.Provider p : others) {
            Router.Pick c = Router.newPick(p, bare);
            c.score = Router.scorePublic(c, false);
            scored.add(c);
        }
        scored.sort((x, y) -> {
            boolean ax = Router.available(x.key()), ay = Router.available(y.key());
            if (ax != ay) return ax ? -1 : 1;
            return Double.compare(y.score, x.score);
        });
        for (Router.Pick c : scored) out.add(c.provider);
        Log.i("点名 " + pinned.name + " 的 " + bare + "：另有 " + others.size()
                + " 个账号也有这个模型，已作为失败时的兜底候选");
        return out;
    }

    /**
     * 把限流情况写成人话，给账号详情页显示。
     *
     * 刻意区分"上游给了确切时刻"和"只知道大概等多久"：
     * 前者可以直接约到几点，后者只能说"约 N 分钟后" —— 把估算说成确切的，
     * 用户到点发现还没好，就不信这个提示了。
     */
    static String throttleNote(long resetAtMs, long retryAfterMs, String kind) {
        boolean quota = "quota".equals(kind);
        String head = quota ? "额度/计费受限" : "被上游限流";
        String tail = quota ? "（可换账号或等额度恢复）" : "（换账号/换模型可继续用）";
        if (resetAtMs > 0) {
            java.text.SimpleDateFormat f =
                    new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US);
            return head + "，上游称 " + f.format(new java.util.Date(resetAtMs)) + " 恢复" + tail;
        }
        long sec = Math.max(1L, retryAfterMs / 1000L);
        if (sec >= 3600) return head + "，约 " + (sec / 3600) + " 小时后恢复" + tail;
        if (sec >= 60) return head + "，约 " + (sec / 60) + " 分钟后恢复" + tail;
        return head + "，约 " + sec + " 秒后恢复" + tail;
    }

    /** 一次失败是不是"请求级风控"（channel_soft，换模型往往就过）。 */
    private static boolean isSoftChannel(Throwable e) {
        return e instanceof Throttled && "channel_soft".equals(((Throttled) e).kind);
    }

    /** 直连路径一次尝试几个候选（首次 + 重试）。 */
    private static final int DIRECT_TRIES = 3;

    /** 直连与非 OpenAI 协议路径的结果反馈：同步更新 Router 熔断器与首字延迟。 */
    private static void recordDirectOutcome(Config.Provider p, String um, boolean ephemeral, long t0, Exception err) {
        if (ephemeral || p == null || um == null || um.isEmpty()) return;
        String key = p.name + "/" + um;
        if (err == null) {
            Router.recordOk(key, Math.max(1L, System.currentTimeMillis() - t0));
        } else {
            FailScope scope = FailScope.of(err);
            /* 坏模型 / 坏请求只作废半开试探，不记账号失败：
               连续 3 次坏工具参数就把健康账号打进 60s-600s 冷却，
               正是 4.50.2 要修掉的"坏模型误伤账号"。 */
            if (scope.modelScoped() || scope == FailScope.REQUEST
                    || err instanceof ClientGone || err instanceof RaceLost) {
                Router.abortProbe(key);
            } else if (!(err instanceof Throttled)) {
                Router.recordFail(key, String.valueOf(err.getMessage()));
            }
        }
    }

    /**
     * 非流式路径的最终失败：模型/请求问题用明确的 4xx/404 回给客户端，
     * 不要走顶层 500，更不要伪装成"上游故障"。
     *
     * @return true 表示已经写出响应；false 表示调用方按原逻辑继续抛
     */
    private boolean writeScopedFailure(OutputStream out, Req r, Throwable last) throws Exception {
        if (last == null) return false;
        FailScope scope = FailScope.of(last);
        if (!scope.modelScoped() && scope != FailScope.REQUEST) return false;
        int code = scope == FailScope.MODEL_ACCESS ? 404 : 400;
        if (last instanceof InvalidRequest) code = ((InvalidRequest) last).code;
        writeError(out, r.path, code, String.valueOf(last.getMessage()), scope.kind());
        return true;
    }

    private String upstreamModel(Config.Provider p, String model) {
        int slash = model == null ? -1 : model.indexOf('/');
        if (slash > 0) {
            String prefix = model.substring(0, slash);
            if (shortLabel(p).equalsIgnoreCase(prefix)
                    || p.name.equalsIgnoreCase(prefix) || p.id.equalsIgnoreCase(prefix)) {
                return model.substring(slash + 1);
            }
            /* 前缀指向的是**另一个**账号（点名的那个被限流、正在换号兜底）。
               这种情况必须同样把前缀剥掉：上游收到 "WB1/kimi-k3-1" 只会回
               400 "model ... service info not found"，换号就算发生了也一样失败。
               实测就是这么翻车的：日志里"非流式可换号，重试下一个候选"打出来了，
               但第二个账号收到的是带前缀的名字，于是整条请求仍然报错。

               判断依据是"这个前缀能解析成某个已知账号"（byShortLabel），
               而不是随便见到斜杠就切 —— 正常的模型名里带斜杠不该被误伤。 */
            if (com.cpa.gateway.core.ShortName.isKnownPrefix(config.providers, prefix)) {
                return model.substring(slash + 1);
            }
        }
        return model;
    }

    /** 「这一批候选被上游挡住」的判定结果。 */
    static final class ConfirmedBlock {
        /** 需要挡住的时长（毫秒）；0 表示**不要挡**。 */
        long until;
        /** 挡住的原因是不是"渠道校验拒绝"（不是限流，等也没用）。 */
        boolean channel;
    }

    /**
     * 全部候选是不是都被上游**明确判定**过了 —— 是的话就别再去撞。
     *
     * 为什么值得单独做：上游写了确切恢复时刻时（WorkBuddy 的 code 6004 就是这样，正文里
     * 带"将在 2026-10-16 05:00:11 UTC+8 重置"），在到点之前的每一次重试都是注定 429 的
     * 往返。实测某次两个候选都被限流时，客户端每 ~10 秒重试一次，每次都要撞满两个账号
     * （各 ~0.8 秒），持续几个小时 —— 上游一次也没成功过，纯粹是白跑。
     *
     * **只认"上游明说的时刻"**，不认我们自己估的退避（见 {@link Router.Stat#throttleResetAt}）：
     * 按估的值拒绝，可能把一个其实已经恢复的账号挡在门外，而"全冷却时仍要能发"原本是
     * 刻意的设计。上游给了确切时刻就不一样了 —— 那时按它拒绝只是省掉一次必输的往返。
     *
     * 渠道校验（channel）是特例：它的"回避"是我们自己给的长暂避（上游并不给时刻），
     * 所以这里单独认 —— 但只在这批候选**全都**是 channel 时才挡，任何一个还能发就放行。
     */
    ConfirmedBlock confirmedBlock(List<Config.Provider> cands, String model, int tries) {
        ConfirmedBlock out = new ConfirmedBlock();
        /* 必须看**全部**可派发候选，不能只看 tries（2-4）：tries 只是本轮预算，
           后面可能还有完全可用的账号，按前几个就宣布"全被限流"会误伤。 */
        int n = cands == null ? 0 : cands.size();
        if (n == 0) return out;
        long latest = 0L;
        boolean allChannel = true;
        for (int i = 0; i < n; i++) {
            Config.Provider p = cands.get(i);
            String um = upstreamModel(p, model);
            String key = p.name + "/" + um;
            long at = Router.confirmedThrottleUntil(key);
            if (!Router.isChannelBlocked(key)) allChannel = false;
            if (at <= 0L) {
                /* 有一个能用（或只是我们自己估的退避）：不挡，照常发。 */
                if (!allChannel) return out;
                latest = Math.max(latest, Router.throttleUntil(key));
                continue;
            }
            if (at > latest) latest = at;
        }
        if (latest <= System.currentTimeMillis()) return out;
        out.until = latest;
        out.channel = allChannel;
        return out;
    }

    /**
     * 全部候选都被上游**明确判定**后的快速失败。
     *
     * 以前一律当"限流"写 429 + "预计 MM-dd HH:mm 恢复"，但其中有一类根本不是限流：
     * **渠道校验拒绝**（WorkBuddy 的 11128，"这次调用没通过渠道校验"）——上游看的是
     * 账号/会话是否被标记，等多久都不会自己好。把它说成"预计几点恢复"是错的，
     * 用户会照着等，而正确答案是"换号或重新登录"。
     * 这里按类别分开写：channel → 403 + 明确指引；其余仍按限流处理。
     */
    private void writeAllThrottled(OutputStream out, String path, String model,
                                   long until, int tries, boolean channel) throws Exception {
        if (channel) {
            Log.w("请求 " + model + "：候选账号都被上游渠道校验拒绝，本次不向上游发起请求"
                    + "（账号与国际版身份都试过，需要换号或重新登录）");
            writeError(out, path, 403,
                    "该模型的候选账号都被上游渠道校验拒绝（上游要求官方客户端；"
                            + "已按国际版身份重发过仍被拒）；"
                            + "请换一个账号或重新登录该账号，等下去不会自己恢复", "channel");
            return;
        }
        String ts = "";
        try {
            ts = new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
                    .format(new java.util.Date(until));
        } catch (Throwable ignored) {
        }
        Log.w("请求 " + model + "：全部 " + tries + " 个候选都被上游确认限流到 " + ts
                + "，本次不向上游发起请求（省掉 " + tries + " 次注定 429 的往返）");
        writeError(out, path, 429, "该模型的候选账号都被上游限流，预计 " + ts
                + " 恢复，本次未向上游发起请求；可改用 auto 或其它模型", "rate");
    }

    /* ------------------------------------------------------- model liveness */

    private static final java.util.concurrent.atomic.AtomicBoolean PROBING =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /** Set on every client-facing request so background probing can step aside. */
    static volatile long lastClientRequestAt = 0L;

    /** 最近一次"真流量"请求的时间戳（毫秒）。服务端用它判断空闲、决定要不要把 CPU 锁还回去。 */
    public static long lastClientRequestMs() {
        return lastClientRequestAt;
    }

    /** 当前活跃连接数（连接池里正在跑的线程数）。 */
    int activeCount() {
        return connPool.getActiveCount();
    }


    /** 正在跑的"一键做任务"（key = 渠道名或 *）：防止重复上报。 */
    private static final java.util.Set<String> TASKS_RUNNING =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    /** 供进程内 UI（一键做任务）直接调用：不依赖网关 HTTP 端口是否运行。 */
    public static JSONObject runAccountChores(Config.Provider p) {
        JSONObject row = new JSONObject();
        if (p == null) return row;
        try {
            if (Config.TYPE_LOOMY.equals(p.type)) {
                return loomyChores(p);
            } else if (Config.TYPE_TRAE.equals(p.type)) {
                return traeChores(p);
            } else if (Config.TYPE_WORKBUDDY.equals(p.type)) {
                return WbTaskChores.run(p);
            }
            row.put("provider", p.name);
            row.put("skipped", true);
        } catch (Exception e) {
            try {
                row.put("provider", p.name);
                row.put("error", String.valueOf(e.getMessage()));
            } catch (Exception ignored) {
            }
        }
        return row;
    }

    /** 真正跑任务（后台线程里执行）。 */
    private static void runChoresSync(String only) {
        Server srv = Gateway.currentServer();
        if (srv == null) {
            for (Config.Provider p : new ArrayList<>(Gateway.config().providers)) {
                if (only != null && !only.isEmpty() && !only.equals(p.name)) continue;
                if (!p.dispatchable()) continue;
                runAccountChores(p);
            }
            Gateway.persistSoon();
            return;
        }
        srv.runTasksInner(only == null ? "" : only);
    }

    /** 逐个账号跑每日任务（原 tasks/run 里的那段逻辑，原样搬进来）。 */
    private JSONArray runTasksInner(String only) {
        JSONArray ran = new JSONArray();
        try {
            for (Config.Provider p : new ArrayList<>(config.providers)) {
                if (!only.isEmpty() && !only.equals(p.name)) continue;
                if (!p.dispatchable()) continue;
                if (Config.TYPE_LOOMY.equals(p.type)
                        || Config.TYPE_TRAE.equals(p.type)
                        || Config.TYPE_WORKBUDDY.equals(p.type)) {
                    ran.put(runAccountChores(p));
                }
            }
            Log.i("一键做任务结果：" + Upstreams.shortenPublic(ran.toString()));
        } catch (Throwable t) {
            Log.w("一键做任务异常：" + t);
        }
        Gateway.persistSoon();
        return ran;
    }

    /** Background sweep: every listed model gets one minimal request, failures are parked. */
    static void verifyModelsAsync() {
        // 检查与置位必须是原子的：原来 `if (probing) return; probing = true;` 之间
        // 两个线程能同时挤进去，全量探活跑两遍（双倍烧额度）。
        if (!PROBING.compareAndSet(false, true)) return;
        boolean probing = true;
        // 外层走共享 IO 池；内层探活用常驻 SWEEP 池（原来每次现建一个 2 线程池）
        Threads.io(() -> {
            long started = System.currentTimeMillis();
            java.util.concurrent.ExecutorService pool = Threads.SWEEP;
            final java.util.concurrent.atomic.AtomicInteger checked = new java.util.concurrent.atomic.AtomicInteger();
            final java.util.concurrent.atomic.AtomicInteger failed = new java.util.concurrent.atomic.AtomicInteger();
            java.util.List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            try {
                for (final Config.Provider p : Gateway.config().providers) {
                    if (!p.dispatchable()) continue;
                    for (final String m : new ArrayList<>(p.models)) {
                        if ("*".equals(m)) continue;
                        // 让路检查放在**提交之前**：以前放在任务体里，池子会被一堆"睡着的任务"占满，
                        // 检测/其它巡检任务就再也排不进来（这也是检测卡死的帮凶之一）。
                        while (System.currentTimeMillis() - lastClientRequestAt < 4000) {
                            try {
                                Thread.sleep(1500);
                            } catch (InterruptedException ie) {
                                return;
                            }
                        }
                        futures.add(pool.submit(() -> {
                            checked.incrementAndGet();
                            // 后台巡检必须用 ephemeral：否则每探一个模型都记一条用量、
                            // 还会被自己的"让路"逻辑当成真实流量，N 个模型把自己拖慢 N×4 秒。
                            // 走进程内 probeDirect，不再占本机 HTTP 连接线程：
                            // 以前连接池满时自己的 503 会被当成"模型坏了"。
                            String reason = probeDirect(p, m);
                            synchronized (p.badModels) {
                                if (reason == null) {
                                    p.badModels.remove(m);
                                } else if (!p.badModels.contains(m)) {
                                    p.badModels.add(m);
                                    failed.incrementAndGet();
                                }
                            }
                        }));
                    }
                }
                for (java.util.concurrent.Future<?> f : futures) {
                    try {
                        f.get();
                    } catch (Exception ignored) {
                    }
                }
            } finally {
                // SWEEP 是常驻共享池，不 shutdown
                Gateway.persistSoon();
                PROBING.set(false);
                Log.i("模型探活完成：检查 " + checked.get() + " 个，不可用 " + failed.get()
                        + " 个，用时 " + (System.currentTimeMillis() - started) + "ms");
            }
        });
    }

    /** null means usable; otherwise a short human reason. */
    static String probeOne(Config.Provider p, String model) {
        return probeOne(p, model, 60000, false);
    }

    /** Probes must not leave conversations behind in the user's own account. */
    static String probeOne(Config.Provider p, String model, int readTimeoutMs) {
        return probeOne(p, model, readTimeoutMs, false);
    }

    /** Same probe with an explicit read timeout: the one-tap check cannot afford 60s per model. */
    static String probeOne(Config.Provider p, String model, int readTimeoutMs, boolean ephemeral) {
        try {
            JSONObject body = new JSONObject();
            body.put("model", p.name + "/" + model);
            JSONArray msgs = new JSONArray();
            msgs.put(new JSONObject().put("role", "user").put("content", "hi"));
            body.put("messages", msgs);
            body.put("max_tokens", 1);
            if (ephemeral) body.put("_ephemeral", true);
            Config cfg = Gateway.config();
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            headers.put("Authorization", "Bearer " + cfg.apiKey);
            // 探活标记：网关认出这是回环探测，不计入"真实流量"（探活让路逻辑用）
            if (ephemeral) headers.put("X-CPA-Ephemeral", "1");
            byte[] payload = body.toString().getBytes("UTF-8");
            Http.Resp resp = Http.call("POST",
                    "http://127.0.0.1:" + cfg.port + "/v1/chat/completions",
                    headers, payload, "application/json", 5000, readTimeoutMs, false);
            String text = resp.body;
            if (resp.code >= 400) {
                String reason = shortenReason(text == null ? "" : text);
                /* 空响应体不能返回空串：调用方把"非 null"当成模型坏了，
                   空串会写进 badModels 且界面上看不到原因。 */
                return reason.trim().isEmpty()
                        ? ("HTTP " + resp.code + "（无响应体）") : reason;
            }
            if (text.contains("\"error\"")) return shortenReason(text);
            return null;
        } catch (Exception e) {
            return Upstreams.shortenPublic(String.valueOf(e.getMessage()));
        }
    }

    /**
     * 进程内最小探测：直接走 serve()，不经过本机 HTTP。
     *
     * 旧实现是往 127.0.0.1:port 发一个真请求，于是每测一个模型就占一条服务端连接线程；
     * 检测/后台巡检几十上百个模型时把连接池占满，外面的人（包括界面自己）就发不进来了。
     * 现在探测走内部路径，一条连接线程都不占。
     */
    static String probeDirect(Config.Provider p, String model) {
        Server srv = Gateway.currentServer();
        if (srv == null) return "网关未运行";
        try {
            Proto.Req req = new Proto.Req();
            req.model = model;
            Proto.Msg m = new Proto.Msg("user");
            m.add(Proto.Part.text("hi"));
            req.messages.add(m);
            req.maxTokens = 1;
            req.ephemeral = true;   // 探活：不留痕迹、不计用量
            Proto.Sink sink = new Proto.Sink() {
                @Override
                public void text(String delta) {
                }

                @Override
                public void thinking(String delta) {
                }

                @Override
                public void toolCall(String id, String name, String argsJson) {
                }

                @Override
                public void finish(String stopReason, Proto.Usage usage) {
                }
            };
            srv.serve(model, p, model, req, sink);
            return null;
        } catch (Throwable t) {
            return Upstreams.shortenPublic(String.valueOf(t.getMessage() == null ? t : t.getMessage()));
        }
    }

    static String shortenReason(String text) {
        String t = text.replace('\n', ' ');
        return t.length() > 160 ? t.substring(0, 160) : t;
    }

    /* ------------------------------------------------------------- upstream run */

    /**
     * Runs one request against the provider that owns the model. A failure is remembered for the
     * "unusable accounts" screen; a success clears that memory (and un-hides that model), so a
     * channel that recovers reappears by itself.
     */
    private void serve(String requested, Config.Provider p, String upstreamModel, Proto.Req req, Proto.Sink sink) throws Exception {
        serve(requested, p, upstreamModel, req, sink, CURRENT.get());
    }

    /**
     * serve 的第二形态：显式传入这条请求属于哪条连接。
     *
     * 竞速（auto-race）里 serve 跑在 RACE_POOL 线程上，那里的 ThreadLocal 从来没被 set 过，
     * 于是 `cur == null`：**渠道名、模型名、token 用量全都回填不进去**，
     * 用量里所有竞速请求都记成"失败 + 0 token"。现在由发起竞速的线程把 Conn 传进来。
     */
    private void serve(String requested, Config.Provider p, String upstreamModel, Proto.Req req,
                       Proto.Sink sink, Conn cur) throws Exception {
        // 本地限额预检 + 并发槽（对标 cctapi 的 PassQuotaCheck / TryAcquireDeploymentSlot）。
        // 两者都 fail 成"限流"：换下一个候选即可，不该记失败、更不该把账号打进熔断。
        final int estTokens = Classifier.estimateTokens(req);
        final String blocked = Limits.explainBlocked(p, estTokens);
        if (blocked != null) {
            Log.w("本地限额跳过 " + p.name + "：" + blocked);
            /* 带上 LocalReason：这条是我们自己拦下来的，不是上游拒的。
               下游（Detect）据此判断"不该给账号扣坏标记"，不必再读中文文案。 */
            throw new Throttled(429, blocked.contains("今日") ? 3_600_000L : 60_000L, 0L,
                    "local", LocalReason.QUOTA, blocked);
        }
        final Limits.Slot slot = Limits.acquire(p);
        if (slot == null) {
            Log.w("并发已满，跳过 " + p.name + "（上限 " + p.maxConcurrent + "）");
            throw new Throttled(429, 5_000L, 0L, "local", LocalReason.BUSY, "并发已满");
        }
        final String routeKey = (p == null ? "" : p.name)
                + (upstreamModel == null || upstreamModel.isEmpty() ? "" : "/" + upstreamModel);
        if (!req.ephemeral && !routeKey.isEmpty()) {
            Router.markProbingIfHalfOpen(routeKey);
        }
        try {
        if (cur != null) {
            cur.apiProvider = p == null ? "" : p.name;
            cur.apiModel = upstreamModel == null ? "" : upstreamModel;
            /* 4.33.0 观测：这次带了多少工具、历史里有没有工具调用。
               有了它们才能区分"模型不想调"和"我们没给"。 */
            cur.toolsSent = req == null || req.tools == null ? 0 : req.tools.size();
            cur.hasToolHistory = hasToolHistory(req);
            /* 4.35.0 留一份请求正文（判跑题用）；客户端重发次数也从这里取 */
            cur.toolNames = toolNameList(req);
            cur.askedHint = lastUserText(req);
            /* 所有推理入口都在这里记一次（虚拟模型入口已提前记过，note() 会复用），
               直连模型的质量标签才拿得到 retry。 */
            cur.clientRetried = Repeats.note(req) > 1;
            /* 会话标识：同一段对话的多跳请求共用一个。
               用「首条用户消息 + 消息数」做指纹——同一轮工具循环里，
               消息数会递增，所以不能只用内容；直接用内容指纹会误把
               "用户重复问同一句"合并成一轮。 */
            cur.conversationId = Conversation.id(req);
        }
        // 包一层 Sink：上游报的 token 用量顺手记进 Conn，用量统计的后两列（tokIn/tokOut）才有数。
        // 所有回调原样转发，连工具画像用的 sawToolCall 也转发，行为与之前完全一致。
        final Proto.Sink inner = sink;
        Proto.Sink tap = new Proto.Sink() {
            @Override
            public void text(String delta) {
                /* 数正文字符：上游普遍不返回 usage（中转站尤其），tokOut 长期是 0；
                   而 text(delta) 是逐字过来的，自己数不依赖上游。 */
                if (delta != null && cur != null) {
                    cur.lastUpstreamAt = System.currentTimeMillis();
                    cur.outChars += delta.length();
                    /* 留一份正文样本：用来判定"上游是不是把工具调用写进了正文"。
                       只留前 2000 字符，够识别标记，不会把内存吃满。 */
                    String hint = cur.seenTextHint;
                    if (hint != null && hint.length() < 2000) {
                        cur.seenTextHint = hint + delta;
                    }
                }
                inner.text(delta);
            }

            @Override
            public void thinking(String delta) {
                if (cur != null) cur.lastUpstreamAt = System.currentTimeMillis();
                inner.thinking(delta);
            }

            @Override
            public void toolCall(String id, String name, String argsJson) {
                if (cur != null) {
                    cur.lastUpstreamAt = System.currentTimeMillis();
                    cur.sawToolCall = true;
                }
                inner.toolCall(id, name, argsJson);
            }

            @Override
            public void finish(String stopReason, Proto.Usage usage) {
                // 用显式传入的 cur，不能用 CURRENT.get()：
                // 竞速路径下 serve 跑在 RACE_POOL 线程上，那里的 ThreadLocal 从来没被 set 过，
                // CURRENT.get() 恒为 null —— token 用量永远记 0。
                Conn c = cur;
                if (c != null) {
                    c.lastUpstreamAt = System.currentTimeMillis();
                    /* 结束原因以前收到就丢：于是 length（被 max_tokens 截断）和 stop（正常结束）
                       在用量数据里完全一样，截断彻底不可见。现在记下来。 */
                    c.stopReason = stopReason == null ? "" : stopReason;
                    if (usage != null) {
                        c.tokIn = usage.input;
                        c.tokOut = usage.output;
                    }
                }
                inner.finish(stopReason, usage);
            }

            @Override
            public boolean sawToolCall() {
                boolean innerSaw = inner.sawToolCall();
                if (innerSaw && cur != null) cur.sawToolCall = true;
                return innerSaw;
            }

            @Override
            public void responseId(String id) {
                if (id != null && !id.isEmpty() && cur != null) cur.upstreamResponseId = id;
                inner.responseId(id);
            }

            /* 响应保真（4.50.0）：原样透传的分片也要穿过这层记账包装，
               否则 Streamer/Collector 永远收不到 raw 回调，客户端会拿到空响应。 */
            @Override
            public void rawChunk(JSONObject chunk) {
                if (cur != null) {
                    cur.lastUpstreamAt = System.currentTimeMillis();
                    JSONArray choices = chunk.optJSONArray("choices");
                    for (int i = 0; choices != null && i < choices.length(); i++) {
                        JSONObject c = choices.optJSONObject(i);
                        JSONObject d = c == null ? null : c.optJSONObject("delta");
                        if (d == null) continue;
                        String text = Proto.optString(d, "content", "");
                        if (!text.isEmpty()) {
                            cur.outChars += text.length();
                            /* 质量判定看的是"正文有没有跑题/为空"，第一个 choice 就够。
                               n>1 时把各 choice 拼在一起反而会误判。 */
                            Integer idx = Proto.optInt(c, "index");
                            if (idx == null || idx.intValue() == 0) {
                                String hint = cur.seenTextHint;
                                if (hint != null && hint.length() < 2000) {
                                    cur.seenTextHint = hint + text;
                                }
                            }
                        }
                        JSONArray tc = d.optJSONArray("tool_calls");
                        if (tc != null && tc.length() > 0) cur.sawToolCall = true;
                    }
                }
                inner.rawChunk(chunk);
            }

            @Override
            public void rawBody(JSONObject body) {
                if (cur != null) {
                    cur.lastUpstreamAt = System.currentTimeMillis();
                    JSONArray choices = body.optJSONArray("choices");
                    for (int i = 0; choices != null && i < choices.length(); i++) {
                        JSONObject c = choices.optJSONObject(i);
                        JSONObject m = c == null ? null : c.optJSONObject("message");
                        if (m == null) continue;
                        String text = Proto.optString(m, "content", "");
                        if (!text.isEmpty()) {
                            cur.outChars += text.length();
                            Integer idx = Proto.optInt(c, "index");
                            if (idx == null || idx.intValue() == 0) {
                                String hint = cur.seenTextHint;
                                if (hint != null && hint.length() < 2000) {
                                    cur.seenTextHint = hint + text;
                                }
                            }
                        }
                        JSONArray tc = m.optJSONArray("tool_calls");
                        if (tc != null && tc.length() > 0) cur.sawToolCall = true;
                    }
                }
                inner.rawBody(body);
            }

            @Override
            public void rawDone() {
                inner.rawDone();
            }
        };
        try {
            Upstreams.create(p).chat(upstreamModel, req, tap);
            // 记账：预检只是"允许发"，真正的额度占用在成功之后（原版 RecordUsage 的时机）
            long usedTokens = estTokens;
            Conn counted = cur;   // 同上：竞速线程里 CURRENT.get() 恒 null
            if (counted != null && (counted.tokIn + counted.tokOut) > 0) {
                usedTokens = counted.tokIn + counted.tokOut;
            }
            Limits.record(p, usedTokens);
            // 工具画像：直接从这次真实流量里学，不用额外探活
            if (!req.tools.isEmpty()) {
                if (sink.sawToolCall()) {
                    ToolAbility.note(p, upstreamModel, true);
                } else if (ToolAbility.looksLikeToolCall(sink.seenText())) {
                    ToolAbility.note(p, upstreamModel, false);
                }
            }
            boolean dirty = false;
            if (p.lastError != null && !p.lastError.isEmpty()) {
                p.lastError = "";
                p.lastErrorAt = 0;
                dirty = true;
            }
            if (p.badModels.remove(upstreamModel)) dirty = true;
            /* 成功一次就把限流标记清掉：上游恢复了就不该继续显示"限流中"。 */
            if (p.throttleUntil > 0 || (p.throttleNote != null && !p.throttleNote.isEmpty())) {
                p.throttleUntil = 0L;
                p.throttleNote = "";
                dirty = true;
            }
            if (dirty) Gateway.persistSoon();
        } catch (RaceLost lost) {
            // 竞速输了不是失败：不记错误、不进冷却，原样抛给上层
            throw lost;
        } catch (ClientGone gone) {
            // 客户端不在了：与上游无关，**不写 lastError**（否则账号页会显示一个假故障）
            Log.i("客户端已断开，本次不计上游失败");
            throw gone;
        } catch (Throttled t) {
            // 所有上游请求都从这个出口出去：限流/额度耗尽在这里记一次，
            // 虚拟模型、直连、竞速三条路径全都被覆盖到。
            // 必须直接用入参构造的 routeKey（p.name + "/" + upstreamModel），
            // 不能读 CURRENT.get()——竞速（RACE_POOL）与内部探活（PROBE_TIMERS）线程的
            // ThreadLocal 恒为 null，旧代码会退回只写 p.name，导致限流回避对候选池完全失效。
            String key = !routeKey.isEmpty() ? routeKey
                    : (cur != null && !cur.apiProvider.isEmpty() ? cur.apiProvider + "/" + cur.apiModel
                    : (p == null ? "" : p.name));
            Router.recordThrottled(key, t.retryAfterMs, t.kind, t.resetAtMs > 0, t.resetAtMs);
            /* 把"限流到什么时候"写到账号上，供界面显示。
               只写这两个**纯展示**字段，不碰 lastError / enabled，
               所以不会把账号误判成"不可用"——那正是当初刻意不记 lastError 的原因。 */
            if (p != null) {
                long untilSec = (System.currentTimeMillis() + Math.max(0L, t.retryAfterMs)) / 1000L;
                if (p.throttleUntil < untilSec) p.throttleUntil = untilSec;
                p.throttleNote = throttleNote(t.resetAtMs, t.retryAfterMs, t.kind);
                Gateway.persistSoon();
            }
            throw t;
        } catch (Exception e) {
            String why = Upstreams.shortenPublic(String.valueOf(e.getMessage()));
            /* 失败作用域（4.50.2）：坏模型 / 坏请求不能写成"这个账号坏了"。
               以前这里对所有非暂时性异常都写 p.lastError，于是模型吐一次坏工具参数，
               账号详情里就挂上"最近调用失败"，看起来像号挂了。 */
            FailScope scope = FailScope.of(e);
            if (scope == FailScope.MODEL_BAD && p != null && upstreamModel != null) {
                /* 模型行为问题记进工具画像：以后带工具的请求会绕开它，
                   而不是把同模型的其它账号逐个打一遍。 */
                ToolAbility.note(p, upstreamModel, false);
            }
            if (scope == FailScope.MODEL_BAD || scope == FailScope.MODEL_ACCESS
                    || scope == FailScope.REQUEST) {
                Log.w("失败属于" + (scope == FailScope.REQUEST ? "请求" : "模型")
                        + "问题，不标记账号不可用：" + (p == null ? "?" : p.name) + " · " + why);
                throw e;
            }
            // 暂时性失败（上游限流 / 超时 / 网络抖动）**不写 lastError**。
            //
            // 为什么：unusableReason() 会把"最近 30 分钟有过 lastError"的账号标成
            // "不可用"，而免费池账号（OVHcloud / Kilo 这类）的上游限流本来就是波动的。
            // 记一次 429 就让账号挂 30 分钟"不可用"，下次成功又清掉——
            // 表现出来就是"账号一会好一会坏"，用户以为坏了。
            // 真机实测：同一批 12 账号连跑三轮，失败数 2 / 0 / 1，变动的全是免费池的 429。
            /* 传异常对象而不是只看文本：Throttled 上挂着 LocalReason 时直接判定，
               不必再靠中文字面量猜"这是不是我们自己造成的"。 */
            if (scope == FailScope.TRANSIENT || Detect.isTransient(e) || Detect.isTransient(why)) {
                Log.i("上游暂时失败，不标记账号不可用：" + p.name + " · " + why);
            } else {
                p.lastError = why;
                p.lastErrorAt = System.currentTimeMillis() / 1000;
                Gateway.persistSoon();
            }
            throw e;
        }
        } finally {
            slot.release();
        }
    }

    /* ----------------------------------------------------------------- handlers */

    /** Ollama `/api/tags`：把对外模型清单按它的形状列出来。 */
    private JSONObject ollamaTags() {
        JSONObject out = new JSONObject();
        JSONArray arr = new JSONArray();
        String now = java.time.Instant.now().toString();
        try {
            for (String m : allModels()) {
                JSONObject x = new JSONObject();
                x.put("name", m);
                x.put("model", m);
                x.put("modified_at", now);
                x.put("size", 0);
                x.put("digest", "");
                JSONObject d = new JSONObject();
                d.put("format", "ahyu");
                d.put("family", "ahyu");
                d.put("families", new JSONArray().put("ahyu"));
                d.put("parameter_size", "");
                d.put("quantization_level", "");
                x.put("details", d);
                arr.put(x);
            }
            out.put("models", arr);
        } catch (Throwable t) {
            try {
                out.put("error", String.valueOf(t.getMessage()));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /**
     * Ollama `/api/chat` 与 `/api/generate`。
     *
     * 客户端只会说 NDJSON；这里用 {@link Fronts.OllamaFront} 翻译成 canonical 请求，
     * 选路/换号/限额沿用同一套，输出再翻译回 Ollama 形状。
     */
    private void handleOllama(Req r, OutputStream out, boolean generate) throws Exception {
        JSONObject body;
        try {
            body = new JSONObject(r.body.isEmpty() ? "{}" : r.body);
        } catch (Exception e) {
            writeError(out, r.path, 400, "invalid JSON body");
            return;
        }
        JSONObject chatBody = body;
        if (generate) {
            chatBody = new JSONObject();
            chatBody.put("model", Proto.optString(body, "model", ""));
            chatBody.put("stream", body.optBoolean("stream", true));
            JSONArray msgs = new JSONArray();
            String sys = Proto.optString(body, "system", "");
            if (!sys.isEmpty()) {
                msgs.put(new JSONObject().put("role", "system").put("content", sys));
            }
            JSONObject um = new JSONObject();
            um.put("role", "user");
            um.put("content", Proto.optString(body, "prompt", ""));
            JSONArray images = body.optJSONArray("images");
            if (images != null) um.put("images", images);
            msgs.put(um);
            chatBody.put("messages", msgs);
            if (body.has("options")) chatBody.put("options", body.opt("options"));
        }
        Proto.Req req = Fronts.OllamaFront.parse(chatBody);
        RequestCheck.check(req);
        if (Router.isVirtual(req.model)) {
            String real = resolveVirtual(req.model, req);
            if (real.isEmpty()) {
                writeError(out, r.path, 503, "虚拟模型 " + req.model + " 暂时没有可用候选");
                return;
            }
            req.model = real;
        }
        List<Config.Provider> cands = pickAll(req.model);
        if (cands.isEmpty()) {
            writeError(out, r.path, 404, unknownModelMessage(req.model));
            return;
        }
        int tries = Router.budget(cands, req.model);
        ConfirmedBlock block = confirmedBlock(cands, req.model, tries);
        if (block.until > 0L) {
            writeAllThrottled(out, r.path, req.model, block.until, tries, block.channel);
            return;
        }

        if (req.stream) {
            Config.Provider p = cands.get(0);
            String um = upstreamModel(p, req.model);
            BufferedWriter w = startNdjson(out);
            Fronts.OllamaStreamer streamer = new Fronts.OllamaStreamer(w, req.model, generate);
            long t0 = System.currentTimeMillis();
            try {
                serve(req.model, p, um, req, streamer);
                recordDirectOutcome(p, um, req.ephemeral, t0, null);
            } catch (Exception e) {
                recordDirectOutcome(p, um, req.ephemeral, t0, e);
                markResponseFailed(out);
                Log.w("ollama 上游错误（" + p.name + "）：" + e.getMessage());
                streamer.error(String.valueOf(e.getMessage()));
            }
            try {
                w.flush();
            } catch (Exception ignored) {
            }
            Conn c = connFor(out);
            if (c != null) c.endStream();
            return;
        }

        Exception last = null;
        for (int i = 0; i < tries; i++) {
            Config.Provider p = cands.get(i);
            String um = upstreamModel(p, req.model);
            Fronts.Collector c = new Fronts.Collector();
            long t0 = System.currentTimeMillis();
            try {
                serve(req.model, p, um, req, c);
                recordDirectOutcome(p, um, req.ephemeral, t0, null);
                writeJson(out, 200, generate
                        ? Fronts.OllamaFront.renderGenerate(req.model, c).toString()
                        : Fronts.OllamaFront.render(req.model, c).toString());
                return;
            } catch (Exception e) {
                recordDirectOutcome(p, um, req.ephemeral, t0, e);
                last = e;
                if (e instanceof ClientGone) return;
                if (!(e instanceof Throttled)) {
                    Log.w("ollama 第 " + (i + 1) + " 个候选失败（" + p.name + "）：" + e.getMessage());
                }
                if (FailScope.of(e).stopDirect()) break;
            }
        }
        markResponseFailed(out);
        if (!writeScopedFailure(out, r, last)) {
            writeError(out, r.path, 502, last == null ? "没有可用候选" : String.valueOf(last.getMessage()));
        }
    }

    private static boolean openAiCompatible(Config.Provider p) {
        return Config.TYPE_OPENAI.equals(p.type) || Config.TYPE_KILO.equals(p.type)
                || Config.TYPE_OVH.equals(p.type);
    }

    /** 非聊天端点的候选：优先按 model 匹配，匹配不到就用所有 OpenAI 兼容账号。 */
    private List<Config.Provider> nonChatCandidates(String model) {
        List<Config.Provider> out = new ArrayList<>();
        if (model != null && !model.isEmpty()) {
            for (Config.Provider p : pickAll(model)) {
                if (openAiCompatible(p)) out.add(p);
            }
        }
        if (out.isEmpty()) {
            for (Config.Provider p : config.providers) {
                if (p.dispatchable() && openAiCompatible(p)) out.add(p);
            }
        }
        return out;
    }

    private static void applyProxyAuth(Map<String, String> headers, Config.Provider p) {
        if (headers == null || p == null || p.apiKey == null || p.apiKey.isEmpty()) return;
        if (Config.TYPE_ANTHROPIC.equals(p.type)) {
            headers.put("x-api-key", p.apiKey);
            headers.put("anthropic-version", "2023-06-01");
        } else {
            headers.put("Authorization", "Bearer " + p.apiKey);
        }
    }

    /**
     * 非聊天 OpenAI 端点原样转发：embeddings / moderations / images / audio。
     *
     * canonical 请求模型只描述聊天，硬套会把 embedding 数组、multipart、二进制音频
     * 弄坏；这些端点直接选一个 OpenAI 兼容账号，把原始字节和 Content-Type 转发过去，
     * 再把上游状态码/响应体原样带回。
     */
    private void proxyOpenAi(Req r, OutputStream out, String tail, boolean binary) throws Exception {
        String contentType = r.header("content-type");
        if (contentType.isEmpty()) contentType = "application/json";
        String model = "";
        if (contentType.toLowerCase(Locale.US).contains("json") && !r.body.isEmpty()) {
            try {
                model = Proto.optString(new JSONObject(r.body), "model", "");
            } catch (Exception ignored) {
            }
        }
        List<Config.Provider> cands = nonChatCandidates(model);
        if (cands.isEmpty()) {
            writeError(out, r.path, 404, "没有可用于该接口的 OpenAI 兼容账号");
            return;
        }
        byte[] payload = r.bodyBytes != null ? r.bodyBytes : r.body.getBytes(StandardCharsets.UTF_8);
        Http.Resp lastText = null;
        Http.Bin lastBin = null;
        int tries = Math.min(3, cands.size());
        for (int i = 0; i < tries; i++) {
            Config.Provider p = cands.get(i);
            try {
                String url = Upstreams.url(Upstreams.openAiBase(p.baseUrl), tail);
                Map<String, String> headers = new HashMap<>();
                applyProxyAuth(headers, p);
                if (binary) {
                    Http.Bin b = Http.callBinary("POST", url, headers, payload, contentType,
                            15000, 180000);
                    if (b.code >= 200 && b.code < 300) {
                        writeRaw(out, b.code, b.contentType == null
                                ? "application/octet-stream" : b.contentType, b.body, false);
                        return;
                    }
                    lastBin = b;
                } else {
                    Http.Resp resp = Http.call("POST", url, headers, payload, contentType,
                            15000, 180000, false);
                    if (resp.code >= 200 && resp.code < 300) {
                        writeRaw(out, resp.code, "application/json",
                                resp.body.getBytes(StandardCharsets.UTF_8), false);
                        return;
                    }
                    lastText = resp;
                }
            } catch (Throwable t) {
                Log.w("非聊天转发失败（" + p.name + "）：" + t);
            }
        }
        if (binary && lastBin != null) {
            writeRaw(out, lastBin.code, lastBin.contentType == null
                    ? "application/octet-stream" : lastBin.contentType, lastBin.body, false);
            return;
        }
        if (lastText != null) {
            writeRaw(out, lastText.code, "application/json",
                    lastText.body.getBytes(StandardCharsets.UTF_8), false);
            return;
        }
        writeError(out, r.path, 502, "上游没有可用响应");
    }

    /**
     * Ollama 模型管理：只有配置了本机 Ollama 账号（baseUrl 含 :11434 或名称含 ollama）
     * 才转发；否则明确 501，而不是假装管理了远程模型库。
     */
    private void handleOllamaManagement(Req r, OutputStream out) throws Exception {
        Config.Provider target = null;
        for (Config.Provider p : config.providers) {
            if (!p.dispatchable()) continue;
            String base = (p.baseUrl == null ? "" : p.baseUrl).toLowerCase(Locale.US);
            String name = (p.name == null ? "" : p.name).toLowerCase(Locale.US);
            if (base.contains(":11434") || name.contains("ollama")) {
                target = p;
                break;
            }
        }
        if (target == null) {
            writeError(out, r.path, 501,
                    "没有配置本机 Ollama 账号；模型管理只代理到本机 Ollama（:11434）");
            return;
        }
        try {
            String base = Upstreams.trimSlash(target.baseUrl);
            if (base.endsWith("/v1")) base = base.substring(0, base.length() - 3);
            String url = base + r.path;
            if (!r.query.isEmpty()) url = url + "?" + r.query;
            Map<String, String> headers = new HashMap<>();
            if (target.apiKey != null && !target.apiKey.isEmpty()) {
                headers.put("Authorization", "Bearer " + target.apiKey);
            }
            String ct = r.header("content-type");
            if (ct.isEmpty()) ct = "application/json";
            byte[] payload = r.bodyBytes != null ? r.bodyBytes
                    : r.body.getBytes(StandardCharsets.UTF_8);
            Http.Resp resp = Http.call(r.method, url, headers, payload, ct, 15000, 300000, false);
            String outType = r.path.startsWith("/api/pull") || r.path.startsWith("/api/push")
                    ? "application/x-ndjson" : "application/json";
            writeRaw(out, resp.code, outType,
                    resp.body.getBytes(StandardCharsets.UTF_8), false);
        } catch (Throwable t) {
            writeError(out, r.path, 502, "Ollama 管理转发失败：" + t.getMessage());
        }
    }

    /** Ollama `/api/embed` 与旧版 `/api/embeddings`。 */
    private void handleOllamaEmbed(Req r, OutputStream out, boolean legacy) throws Exception {
        JSONObject body;
        try {
            body = new JSONObject(r.body.isEmpty() ? "{}" : r.body);
        } catch (Exception e) {
            writeError(out, r.path, 400, "invalid JSON body");
            return;
        }
        String model = Proto.optString(body, "model", "");
        Object input = legacy ? body.opt("prompt") : body.opt("input");
        if (input == null) {
            writeError(out, r.path, 400, "missing " + (legacy ? "prompt" : "input"));
            return;
        }
        JSONObject up = new JSONObject();
        up.put("model", model);
        up.put("input", input);
        byte[] payload = up.toString().getBytes(StandardCharsets.UTF_8);
        List<Config.Provider> cands = nonChatCandidates(model);
        if (cands.isEmpty()) {
            writeError(out, r.path, 404, "没有可用于 embeddings 的 OpenAI 兼容账号");
            return;
        }
        Exception last = null;
        int tries = Math.min(3, cands.size());
        for (int i = 0; i < tries; i++) {
            Config.Provider p = cands.get(i);
            try {
                String url = Upstreams.url(Upstreams.openAiBase(p.baseUrl), "embeddings");
                Map<String, String> headers = new HashMap<>();
                applyProxyAuth(headers, p);
                Http.Resp resp = Http.call("POST", url, headers, payload,
                        "application/json", 15000, 120000, false);
                if (resp.code >= 200 && resp.code < 300) {
                    JSONObject o = new JSONObject(resp.body);
                    JSONArray data = o.optJSONArray("data");
                    JSONArray embs = new JSONArray();
                    if (data != null) {
                        for (int k = 0; k < data.length(); k++) {
                            JSONObject row = data.optJSONObject(k);
                            if (row != null && row.opt("embedding") != null) {
                                embs.put(row.opt("embedding"));
                            }
                        }
                    }
                    JSONObject result = new JSONObject();
                    result.put("model", model);
                    if (legacy) {
                        result.put("embedding", embs.length() > 0 ? embs.opt(0) : new JSONArray());
                    } else {
                        result.put("embeddings", embs);
                    }
                    writeJson(out, 200, result.toString());
                    return;
                }
                last = new IllegalStateException("HTTP " + resp.code);
            } catch (Exception e) {
                last = e;
            }
        }
        writeError(out, r.path, 502, last == null ? "上游没有可用响应" : String.valueOf(last.getMessage()));
    }

    /** Gemini 的 embedContent / batchEmbedContents：原样转发给 Gemini 账号。 */
    private void handleGeminiEmbed(Req r, OutputStream out, String model, String verb) throws Exception {
        List<Config.Provider> cands = new ArrayList<>();
        for (Config.Provider p : config.providers) {
            if (!p.dispatchable() || !Config.TYPE_GEMINI.equals(p.type)) continue;
            if (p.matches(model) || p.models.contains("*")) cands.add(p);
        }
        if (cands.isEmpty()) {
            for (Config.Provider p : config.providers) {
                if (p.dispatchable() && Config.TYPE_GEMINI.equals(p.type)) cands.add(p);
            }
        }
        if (cands.isEmpty()) {
            writeError(out, r.path, 404, "没有可用于 embeddings 的 Gemini 账号");
            return;
        }
        byte[] payload = r.bodyBytes != null ? r.bodyBytes : r.body.getBytes(StandardCharsets.UTF_8);
        Exception last = null;
        int tries = Math.min(3, cands.size());
        for (int i = 0; i < tries; i++) {
            Config.Provider p = cands.get(i);
            try {
                String url = Upstreams.url(p.baseUrl,
                        "v1beta/models/" + model + ":" + verb);
                Map<String, String> headers = new HashMap<>();
                if (p.apiKey != null && !p.apiKey.isEmpty()) {
                    headers.put("x-goog-api-key", p.apiKey);
                    url = url + (url.contains("?") ? "&" : "?") + "key="
                            + java.net.URLEncoder.encode(p.apiKey, "UTF-8");
                }
                Http.Resp resp = Http.call("POST", url, headers, payload,
                        "application/json", 15000, 120000, false);
                if (resp.code >= 200 && resp.code < 300) {
                    writeRaw(out, resp.code, "application/json",
                            resp.body.getBytes(StandardCharsets.UTF_8), false);
                    return;
                }
                last = new IllegalStateException("HTTP " + resp.code);
            } catch (Exception e) {
                last = e;
            }
        }
        writeError(out, r.path, 502, last == null ? "上游没有可用响应" : String.valueOf(last.getMessage()));
    }

    /**
     * OpenAI 旧版 `/v1/completions`。
     *
     * 很多老插件/CLI 还在用它；只支持 chat 会让它们直接不可用。
     * 把 prompt 当成一条 user 消息走同一条 canonical 选路，输出再转回 text_completion。
     */
    private void handleLegacyCompletions(Req r, OutputStream out) throws Exception {
        JSONObject body;
        try {
            body = new JSONObject(r.body.isEmpty() ? "{}" : r.body);
        } catch (Exception e) {
            writeError(out, r.path, 400, "invalid JSON body");
            return;
        }
        Proto.Req req = new Proto.Req();
        req.model = Proto.optString(body, "model", "");
        req.stream = body.optBoolean("stream", false);
        req.protocol = "openai";
        req.maxTokens = Proto.optInt(body, "max_tokens");
        req.temperature = Proto.optDouble(body, "temperature");
        req.topP = Proto.optDouble(body, "top_p");
        req.topK = Proto.optInt(body, "top_k");
        Object stop = body.opt("stop");
        if (stop instanceof String) req.stop.add((String) stop);
        else if (stop instanceof JSONArray) {
            JSONArray a = (JSONArray) stop;
            for (int i = 0; i < a.length(); i++) req.stop.add(String.valueOf(a.opt(i)));
        }
        Object prompt = body.opt("prompt");
        StringBuilder sb = new StringBuilder();
        if (prompt instanceof String) sb.append((String) prompt);
        else if (prompt instanceof JSONArray) {
            JSONArray a = (JSONArray) prompt;
            for (int i = 0; i < a.length(); i++) {
                if (i > 0) sb.append('\n');
                sb.append(a.optString(i, ""));
            }
        }
        Proto.Msg m = new Proto.Msg("user");
        m.add(Proto.Part.text(sb.toString()));
        req.messages.add(m);
        RequestCheck.check(req);

        if (Router.isVirtual(req.model)) {
            String real = resolveVirtual(req.model, req);
            if (real.isEmpty()) {
                writeError(out, r.path, 503, "虚拟模型 " + req.model + " 暂时没有可用候选");
                return;
            }
            req.model = real;
        }
        List<Config.Provider> cands = pickAll(req.model);
        if (cands.isEmpty()) {
            writeError(out, r.path, 404, unknownModelMessage(req.model));
            return;
        }
        int tries = Router.budget(cands, req.model);
        ConfirmedBlock block = confirmedBlock(cands, req.model, tries);
        if (block.until > 0L) {
            writeAllThrottled(out, r.path, req.model, block.until, tries, block.channel);
            return;
        }

        if (req.stream) {
            Config.Provider p = cands.get(0);
            String um = upstreamModel(p, req.model);
            BufferedWriter w = startSse(out);
            LegacyStreamer streamer = new LegacyStreamer(w, req.model);
            long t0 = System.currentTimeMillis();
            try {
                serve(req.model, p, um, req, streamer);
                recordDirectOutcome(p, um, req.ephemeral, t0, null);
            } catch (Exception e) {
                recordDirectOutcome(p, um, req.ephemeral, t0, e);
                markResponseFailed(out);
                Log.w("legacy completions 上游错误（" + p.name + "）：" + e.getMessage());
                streamer.error(String.valueOf(e.getMessage()));
            }
            try {
                w.flush();
            } catch (Exception ignored) {
            }
            return;
        }

        Exception last = null;
        for (int i = 0; i < tries; i++) {
            Config.Provider p = cands.get(i);
            String um = upstreamModel(p, req.model);
            Fronts.Collector c = new Fronts.Collector();
            long t0 = System.currentTimeMillis();
            try {
                serve(req.model, p, um, req, c);
                recordDirectOutcome(p, um, req.ephemeral, t0, null);
                writeJson(out, 200, renderLegacyCompletion(req.model, c).toString());
                return;
            } catch (Exception e) {
                recordDirectOutcome(p, um, req.ephemeral, t0, e);
                last = e;
                if (e instanceof ClientGone) return;
                if (!(e instanceof Throttled)) {
                    Log.w("completions 第 " + (i + 1) + " 个候选失败（" + p.name + "）：" + e.getMessage());
                }
                if (FailScope.of(e).stopDirect()) break;
            }
        }
        markResponseFailed(out);
        if (!writeScopedFailure(out, r, last)) {
            writeError(out, r.path, 502, last == null ? "没有可用候选" : String.valueOf(last.getMessage()));
        }
    }

    private static JSONObject renderLegacyCompletion(String model, Fronts.Collector c) throws Exception {
        JSONObject out = new JSONObject();
        out.put("id", "cmpl-" + java.util.UUID.randomUUID().toString().replace("-", ""));
        out.put("object", "text_completion");
        out.put("created", System.currentTimeMillis() / 1000);
        out.put("model", model);
        JSONArray choices = new JSONArray();
        JSONObject ch = new JSONObject();
        ch.put("text", c.text == null ? "" : c.text.toString());
        ch.put("index", 0);
        ch.put("logprobs", JSONObject.NULL);
        ch.put("finish_reason", "length".equals(c.stop) ? "length" : "stop");
        choices.put(ch);
        out.put("choices", choices);
        JSONObject usage = new JSONObject();
        usage.put("prompt_tokens", c.usage.input);
        usage.put("completion_tokens", c.usage.output);
        usage.put("total_tokens", c.usage.input + c.usage.output);
        out.put("usage", usage);
        return out;
    }

    /** OpenAI legacy 流式：`data: {...}\n\n` + `[DONE]`。 */
    private static final class LegacyStreamer implements Proto.Sink {
        private final BufferedWriter w;
        private final String model;
        private final String id = "cmpl-" + java.util.UUID.randomUUID().toString().replace("-", "");
        private final long created = System.currentTimeMillis() / 1000;
        private boolean done;

        LegacyStreamer(BufferedWriter w, String model) {
            this.w = w;
            this.model = model;
        }

        @Override
        public void text(String delta) {
            if (delta == null || delta.isEmpty()) return;
            chunk(delta, null);
        }

        @Override
        public void thinking(String delta) {
        }

        @Override
        public void toolCall(String id, String name, String argsJson) {
        }

        @Override
        public void finish(String stopReason, Proto.Usage usage) {
            if (done) return;
            done = true;
            chunk("", "length".equals(stopReason) ? "length" : "stop");
            try {
                w.write("data: [DONE]\n\n");
                w.flush();
            } catch (Throwable ignored) {
            }
        }

        void error(String message) {
            if (done) return;
            done = true;
            try {
                JSONObject e = new JSONObject();
                e.put("error", new JSONObject().put("message", message).put("type", "upstream_error"));
                w.write("data: ");
                w.write(e.toString());
                w.write("\n\ndata: [DONE]\n\n");
                w.flush();
            } catch (Throwable ignored) {
            }
        }

        private void chunk(String text, String finishReason) {
            try {
                JSONObject o = new JSONObject();
                o.put("id", id);
                o.put("object", "text_completion");
                o.put("created", created);
                o.put("model", model);
                JSONObject ch = new JSONObject();
                ch.put("text", text);
                ch.put("index", 0);
                ch.put("logprobs", JSONObject.NULL);
                ch.put("finish_reason", finishReason == null ? JSONObject.NULL : finishReason);
                o.put("choices", new JSONArray().put(ch));
                w.write("data: ");
                w.write(o.toString());
                w.write("\n\n");
                w.flush();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Assistants / Threads 路由（4.45.0）。
     *
     * 只做同步 run：创建后立即执行并返回 completed/failed；状态落在
     * {@link Assistants} 的 assistants.json。工具调用（requires_action）暂不支持，
     * 会以 failed + last_error 明确告知，而不是假装成功。
     */
    private void handleAssistants(Req r, OutputStream out) throws Exception {
        String path = r.path;
        String method = r.method;
        try {
            if ("/v1/assistants".equals(path)) {
                if ("GET".equals(method)) {
                    writeJson(out, 200, Assistants.listObject(Assistants.listAssistants()).toString());
                    return;
                }
                if ("POST".equals(method)) {
                    writeJson(out, 200, Assistants.createAssistant(new JSONObject(
                            r.body.isEmpty() ? "{}" : r.body)).toString());
                    return;
                }
            }
            if (path.startsWith("/v1/assistants/")) {
                String id = URLDecoder.decode(path.substring("/v1/assistants/".length()), "UTF-8");
                if ("GET".equals(method)) {
                    writeJson(out, 200, Assistants.getAssistant(id).toString());
                    return;
                }
                if ("DELETE".equals(method)) {
                    Assistants.deleteAssistant(id);
                    JSONObject o = new JSONObject();
                    o.put("id", id);
                    o.put("object", "assistant.deleted");
                    o.put("deleted", true);
                    writeJson(out, 200, o.toString());
                    return;
                }
            }
            if ("/v1/threads".equals(path) && "POST".equals(method)) {
                writeJson(out, 200, Assistants.createThread(new JSONObject(
                        r.body.isEmpty() ? "{}" : r.body)).toString());
                return;
            }
            if ("/v1/threads/runs".equals(path) && "POST".equals(method)) {
                JSONObject body = new JSONObject(r.body.isEmpty() ? "{}" : r.body);
                JSONObject thread = Assistants.createThread(
                        body.optJSONObject("thread") == null ? new JSONObject()
                                : body.optJSONObject("thread"));
                String tid = thread.getString("id");
                JSONArray msgs = body.optJSONObject("thread") == null ? null
                        : body.optJSONObject("thread").optJSONArray("messages");
                if (msgs != null) {
                    for (int i = 0; i < msgs.length(); i++) {
                        JSONObject m = msgs.optJSONObject(i);
                        if (m != null) Assistants.addMessage(tid, m);
                    }
                }
                JSONObject run = executeAssistantRun(tid, body);
                writeAssistantsRun(out, body, run);
                return;
            }
            if (path.startsWith("/v1/threads/")) {
                String rest = path.substring("/v1/threads/".length());
                int slash = rest.indexOf('/');
                String tid = URLDecoder.decode(slash < 0 ? rest : rest.substring(0, slash), "UTF-8");
                String tail = slash < 0 ? "" : rest.substring(slash + 1);
                if (tail.isEmpty()) {
                    if ("GET".equals(method)) {
                        writeJson(out, 200, Assistants.getThread(tid).toString());
                        return;
                    }
                    if ("DELETE".equals(method)) {
                        Assistants.deleteThread(tid);
                        JSONObject o = new JSONObject();
                        o.put("id", tid);
                        o.put("object", "thread.deleted");
                        o.put("deleted", true);
                        writeJson(out, 200, o.toString());
                        return;
                    }
                }
                if ("messages".equals(tail)) {
                    if ("GET".equals(method)) {
                        int limit = parseIntOr(queryParam(r, "limit"), 20);
                        JSONArray data = Assistants.listMessages(tid, limit,
                                queryParam(r, "order"));
                        writeJson(out, 200, Assistants.listObject(data).toString());
                        return;
                    }
                    if ("POST".equals(method)) {
                        JSONObject body = new JSONObject(r.body.isEmpty() ? "{}" : r.body);
                        writeJson(out, 200, Assistants.addMessage(tid, body).toString());
                        return;
                    }
                }
                if (tail.startsWith("messages/") && "GET".equals(method)) {
                    String mid = URLDecoder.decode(tail.substring("messages/".length()), "UTF-8");
                    writeJson(out, 200, Assistants.getMessage(tid, mid).toString());
                    return;
                }
                if ("runs".equals(tail)) {
                    if ("GET".equals(method)) {
                        writeJson(out, 200, Assistants.listObject(
                                Assistants.listRuns(tid)).toString());
                        return;
                    }
                    if ("POST".equals(method)) {
                        JSONObject body = new JSONObject(r.body.isEmpty() ? "{}" : r.body);
                        JSONObject run = executeAssistantRun(tid, body);
                        writeAssistantsRun(out, body, run);
                        return;
                    }
                }
                if (tail.startsWith("runs/")) {
                    String[] parts = tail.substring("runs/".length()).split("/");
                    String rid = URLDecoder.decode(parts[0], "UTF-8");
                    if (parts.length == 1 && "GET".equals(method)) {
                        writeJson(out, 200, Assistants.getRun(tid, rid).toString());
                        return;
                    }
                    if (parts.length == 2 && "cancel".equals(parts[1])
                            && "POST".equals(method)) {
                        JSONObject run = Assistants.getRun(tid, rid);
                        if (!"completed".equals(run.optString("status"))
                                && !"failed".equals(run.optString("status"))) {
                            run.put("status", "cancelled");
                            run.put("cancelled_at", System.currentTimeMillis() / 1000);
                            Assistants.updateRun(tid, run);
                        }
                        writeJson(out, 200, run.toString());
                        return;
                    }
                    if (parts.length == 2 && "steps".equals(parts[1])
                            && "GET".equals(method)) {
                        JSONArray steps = Assistants.listSteps(tid, rid);
                        writeJson(out, 200, Assistants.listObject(steps).toString());
                        return;
                    }
                    if (parts.length == 2 && "submit_tool_outputs".equals(parts[1])
                            && "POST".equals(method)) {
                        JSONObject body = new JSONObject(r.body.isEmpty() ? "{}" : r.body);
                        JSONObject run = Assistants.getRun(tid, rid);
                        if (!"requires_action".equals(run.optString("status"))) {
                            writeError(out, path, 400, "run 当前不在 requires_action 状态");
                            return;
                        }
                        JSONArray outputs = body.optJSONArray("tool_outputs");
                        if (outputs == null || outputs.length() == 0) {
                            writeError(out, path, 400, "缺少 tool_outputs");
                            return;
                        }
                        for (int i = 0; i < outputs.length(); i++) {
                            JSONObject o = outputs.optJSONObject(i);
                            if (o == null) continue;
                            Assistants.addToolOutput(tid,
                                    Proto.optString(o, "tool_call_id", ""),
                                    Proto.optString(o, "output", ""));
                        }
                        run.put("status", "in_progress");
                        run.put("required_action", JSONObject.NULL);
                        Assistants.updateRun(tid, run);
                        JSONObject assistant = null;
                        try {
                            String aid = run.optString("assistant_id", "");
                            if (!aid.isEmpty()) assistant = Assistants.getAssistant(aid);
                        } catch (Throwable ignored) {
                        }
                        try {
                            run = runAssistantChat(tid, run, assistant);
                        } catch (Throwable t) {
                            run.put("status", "failed");
                            run.put("failed_at", System.currentTimeMillis() / 1000);
                            JSONObject err = new JSONObject();
                            err.put("code", "server_error");
                            err.put("message", String.valueOf(t.getMessage()));
                            run.put("last_error", err);
                            Assistants.updateRun(tid, run);
                        }
                        writeAssistantsRun(out, body, run);
                        return;
                    }
                }
            }
            writeError(out, path, 404, "unknown assistants route: " + path);
        } catch (Assistants.NotFound nf) {
            writeError(out, path, 404, nf.getMessage());
        }
    }

    private static int parseIntOr(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    /** 同步执行一次 run，返回最终的 run 对象。 */
    private JSONObject executeAssistantRun(String threadId, JSONObject body) throws Exception {
        JSONObject run = Assistants.createRun(threadId, body);
        String runId = run.getString("id");
        String assistantId = run.optString("assistant_id", "");
        JSONObject assistant = null;
        try {
            if (!assistantId.isEmpty()) assistant = Assistants.getAssistant(assistantId);
        } catch (Throwable ignored) {
        }

        JSONArray additional = body.optJSONArray("additional_messages");
        if (additional != null) {
            for (int i = 0; i < additional.length(); i++) {
                JSONObject m = additional.optJSONObject(i);
                if (m != null) Assistants.addMessage(threadId, m);
            }
        }

        String model = run.optString("model", "");
        if (model.isEmpty() && assistant != null) model = assistant.optString("model", "");
        if (model.isEmpty()) model = "auto";
        String instructions = run.optString("instructions", "");
        if (instructions.isEmpty() && assistant != null) {
            instructions = assistant.optString("instructions", "");
        }
        JSONArray tools = run.optJSONArray("tools");
        if ((tools == null || tools.length() == 0) && assistant != null) {
            tools = assistant.optJSONArray("tools");
        }

        run.put("model", model);
        if (!instructions.isEmpty()) run.put("instructions", instructions);
        if (tools != null) run.put("tools", tools);
        run.put("status", "in_progress");
        run.put("started_at", System.currentTimeMillis() / 1000);
        Assistants.updateRun(threadId, run);

        try {
            return runAssistantChat(threadId, run, assistant);
        } catch (Throwable t) {
            run.put("status", "failed");
            run.put("failed_at", System.currentTimeMillis() / 1000);
            JSONObject err = new JSONObject();
            err.put("code", "server_error");
            err.put("message", String.valueOf(t.getMessage()));
            run.put("last_error", err);
            Assistants.updateRun(threadId, run);
            return run;
        }
    }

    /**
     * 跑一次 chat：正常文本 → completed；模型要求工具调用 → requires_action。
     * submit_tool_outputs 之后也走这里，所以工具闭环共用同一段逻辑。
     */
    private JSONObject runAssistantChat(String threadId, JSONObject run, JSONObject assistant)
            throws Exception {
        String runId = run.optString("id", "");
        String assistantId = run.optString("assistant_id", "");
        String model = run.optString("model", "");
        if (model.isEmpty() && assistant != null) model = assistant.optString("model", "");
        if (model.isEmpty()) model = "auto";
        String instructions = run.optString("instructions", "");
        if (instructions.isEmpty() && assistant != null) {
            instructions = assistant.optString("instructions", "");
        }
        JSONArray tools = run.optJSONArray("tools");
        if ((tools == null || tools.length() == 0) && assistant != null) {
            tools = assistant.optJSONArray("tools");
        }
        run.put("model", model);
        if (!instructions.isEmpty()) run.put("instructions", instructions);
        if (tools != null) run.put("tools", tools);

        JSONArray messages = new JSONArray();
        if (!instructions.isEmpty()) {
            messages.put(new JSONObject().put("role", "system").put("content", instructions));
        }
        JSONArray history = Assistants.modelMessages(threadId);
        for (int i = 0; i < history.length(); i++) messages.put(history.opt(i));
        if (messages.length() == 0) {
            messages.put(new JSONObject().put("role", "user").put("content", ""));
        }
        JSONObject chat = new JSONObject();
        chat.put("model", model);
        chat.put("messages", messages);
        chat.put("stream", false);
        if (tools != null && tools.length() > 0) chat.put("tools", tools);

        Map<String, String> headers = new HashMap<>();
        if (config.apiKey != null && !config.apiKey.isEmpty()) {
            headers.put("Authorization", "Bearer " + config.apiKey);
        }
        Http.Resp resp = Http.call("POST",
                "http://127.0.0.1:" + config.port + "/v1/chat/completions",
                headers, chat.toString().getBytes(StandardCharsets.UTF_8),
                "application/json", 10000, 120000, false);
        if (resp.code < 200 || resp.code >= 300) {
            throw new IllegalStateException("chat HTTP " + resp.code + ": " + resp.body);
        }
        JSONObject reply = new JSONObject(resp.body);
        JSONArray choices = reply.optJSONArray("choices");
        JSONObject msg = choices != null && choices.length() > 0
                ? choices.optJSONObject(0).optJSONObject("message") : null;
        if (msg == null) throw new IllegalStateException("chat 响应里没有 message");

        JSONArray toolCalls = msg.optJSONArray("tool_calls");
        if (toolCalls != null && toolCalls.length() > 0) {
            Assistants.addAssistantToolCalls(threadId, toolCalls);
            JSONObject required = new JSONObject();
            required.put("type", "submit_tool_outputs");
            required.put("submit_tool_outputs",
                    new JSONObject().put("tool_calls", toolCalls));
            run.put("status", "requires_action");
            run.put("required_action", required);
            run.put("expires_at", System.currentTimeMillis() / 1000 + 600);
            Assistants.updateRun(threadId, run);
            JSONObject details = new JSONObject();
            details.put("type", "tool_calls");
            details.put("tool_calls", toolCalls);
            Assistants.addStep(threadId, runId, assistantId, "tool_calls", details, null);
            return run;
        }

        String text = msg.optString("content", "");
        JSONObject stored = Assistants.addAssistantMessage(threadId, text, runId, assistantId);
        run.put("status", "completed");
        run.put("required_action", JSONObject.NULL);
        run.put("completed_at", System.currentTimeMillis() / 1000);
        JSONObject usage = reply.optJSONObject("usage");
        if (usage != null) {
            JSONObject u = new JSONObject();
            u.put("prompt_tokens", usage.optInt("prompt_tokens", 0));
            u.put("completion_tokens", usage.optInt("completion_tokens", 0));
            u.put("total_tokens", usage.optInt("total_tokens", 0));
            run.put("usage", u);
        }
        Assistants.updateRun(threadId, run);
        JSONObject details = new JSONObject();
        details.put("type", "message_creation");
        details.put("message_creation", new JSONObject().put("message_id", stored.optString("id")));
        Proto.Usage pu = new Proto.Usage();
        if (usage != null) {
            pu.input = usage.optInt("prompt_tokens", 0);
            pu.output = usage.optInt("completion_tokens", 0);
        }
        Assistants.addStep(threadId, runId, assistantId, "message_creation", details, pu);
        return run;
    }

    private void writeAssistantsRun(OutputStream out, JSONObject body, JSONObject run) throws Exception {
        if (body.optBoolean("stream", false)) {
            BufferedWriter w = startSse(out);
            String status = run.optString("status");
            String event = "failed".equals(status) ? "thread.run.failed"
                    : "requires_action".equals(status) ? "thread.run.requires_action"
                    : "thread.run.completed";
            w.write("event: thread.run.created\ndata: " + run.toString() + "\n\n");
            w.write("event: thread.run.in_progress\ndata: " + run.toString() + "\n\n");
            w.write("event: " + event + "\ndata: " + run.toString() + "\n\n");
            w.write("event: done\ndata: [DONE]\n\n");
            w.flush();
            return;
        }
        writeJson(out, 200, run.toString());
    }

    /** 客户端要不要音频输出（Realtime 之外的 Chat Completions audio 模态）。 */
    private static boolean wantsAudioOutput(JSONObject body) {
        JSONArray modal = body.optJSONArray("modalities");
        if (modal != null) {
            for (int i = 0; i < modal.length(); i++) {
                if ("audio".equalsIgnoreCase(String.valueOf(modal.opt(i)))) return true;
            }
        }
        /* 有 audio 对象就是音频输出（format=text 的转写场景也走原样回传，
           对 canonical 没有损失，但没必要为它多开一条路径）。 */
        return body.has("audio");
    }

    private void handleOpenAiChat(Req r, OutputStream out) throws Exception {
        JSONObject body;
        try {
            body = new JSONObject(r.body.isEmpty() ? "{}" : r.body);
        } catch (Exception e) {
            writeError(out, r.path, 400, "invalid JSON body");
            return;
        }
        Proto.Req req = Fronts.OpenAiFront.parse(body);
        RequestCheck.check(req);
        req.ephemeral = body.optBoolean("_ephemeral", false);
        /* 响应保真（4.50.0）：只有"canonical 表达不了"的请求才走原样透传。
         *  · n>1        —— 多候选会被 canonical 合并成一个 choice
         *  · logprobs   —— token 概率在重组正文后无法对应
         *  · 音频输出   —— canonical 只认文本
         * 带工具的请求不走：工具参数修复 / 坏调用换号是网关的安全网，
         * 而这几类响应字段和工具调用同时出现的场景极少。 */
        boolean wantLogprobs = body.optBoolean("logprobs", false)
                || body.has("top_logprobs");
        boolean wantRaw = wantLogprobs
                || body.optInt("n", 1) > 1
                || wantsAudioOutput(body);
        req.rawResponse = wantRaw && req.tools.isEmpty()
                && !Router.isVirtual(req.model);
        if (wantRaw && !req.tools.isEmpty()) {
            /* 带工具时不走 raw（工具参数修复/换号优先），但也不能把这些字段
               原样发给上游：n>1 会按多份计费却只回一个 choice，logprobs 则会被
               canonical 静默丢掉。统一去掉并留一条可见日志。 */
            boolean stripped = false;
            for (String k : new String[]{"n", "logprobs", "top_logprobs", "audio", "modalities"}) {
                if (req.extraBody.has(k)) {
                    req.extraBody.remove(k);
                    stripped = true;
                }
            }
            if (stripped) {
                Log.w("工具请求暂不支持 n/logprobs/音频保真，已按 n=1、无 logprobs 转发："
                        + req.model);
            }
        }
        if (Router.isVirtual(req.model)) {
            if (Router.AUTO_RACE.equalsIgnoreCase(req.model) && req.stream && req.tools.isEmpty()) {
                serveRace(r, out, req);
                return;
            }
            if (Router.AUTO_RACE.equalsIgnoreCase(req.model)) {
                Log.i("auto-race 这次走普通选路（" + (req.stream ? "带工具，要确定性" : "非流式") + "）");
            }
            serveVirtualOpenAi(r, out, req);
            return;
        }
        // 多候选 + failover：同名模型散在多个账号时，按分数挑最好的；
        // 前一个失败（且还没写出任何内容）就换下一个，不再"轮询到谁就是谁"。
        List<Config.Provider> cands = pickAll(req.model);
        if (cands.isEmpty()) {
            writeError(out, r.path, 404, unknownModelMessage(req.model));
            return;
        }
        /* 试几个候选由累积失败概率决定，不再固定 3 次（见 Router.budget） */
        int tries = Router.budget(cands, req.model);

        /* 全部候选都被上游**明确告知**要等到某个时刻时，别去撞墙了。
           详见 confirmedBlock 的说明。 */
        ConfirmedBlock block = confirmedBlock(cands, req.model, tries);
        if (block.until > 0L) {
            writeAllThrottled(out, r.path, req.model, block.until, tries, block.channel);
            return;
        }
        if (req.stream) {
            BufferedWriter w = startSse(out);
            /* 要 logprobs 时不能去"自报家门"：删字会让 token 与概率错位。
               其余保真请求（n>1 / 音频）照常清理正文。 */
            Fronts.OpenAiFront.Streamer streamer =
                    new Fronts.OpenAiFront.Streamer(w, req.model, !wantLogprobs);
            Exception last = null;
            for (int i = 0; i < tries; i++) {
                /* 换号前清掉上一个候选压着的 role/音频分片，别把两个上游的流拼在一起。 */
                if (i > 0) streamer.resetAttempt();
                Config.Provider p = cands.get(i);
                String um = upstreamModel(p, req.model);
                long t0 = System.currentTimeMillis();
                try {
                    serve(req.model, p, um, req, streamer);
                    recordDirectOutcome(p, um, req.ephemeral, t0, null);
                    if (req.rawResponse && !streamer.rawSeen()) {
                        Log.w("保真请求被渠道降级为 canonical（该渠道不支持原样透传）："
                                + p.name + "/" + um);
                    }
                    last = null;
                    break;
                } catch (Exception e) {
                    recordDirectOutcome(p, um, req.ephemeral, t0, e);
                    last = e;
                    if (e instanceof ClientGone) return;
                    if (!(e instanceof Throttled)) {
                        Log.w("直连第 " + (i + 1) + " 个候选失败（" + p.name + "）：" + e.getMessage());
                    }
                    // 已经吐过内容就不能换号：客户端正在按这个流解析，
                    // 中途接上另一个上游的回复会拼出前后不一致的答案。
                    if (streamer.committed()) {
                        Log.w("流已开始输出，无法换号，直接报错");
                        break;
                    }
                    /* 纯调用（这里就是点名模型）：模型行为坏了或请求不合法，
                       换同模型的其它账号也不会好，别逐个把账号打成失败。 */
                    if (FailScope.of(e).stopDirect()) {
                        Log.w("纯调用失败属于模型/请求问题，停止换号重试：" + req.model);
                        break;
                    }
                    if (i + 1 < tries) Log.i("流尚未输出，换下一个候选重试：" + req.model);
                }
            }
            if (last != null) {
                markResponseFailed(out);
                Log.w("upstream error（" + tries + " 个候选都失败）: " + last.getMessage());
                FailScope sc = FailScope.of(last);
                Conn cc = connFor(out);
                if (cc != null) cc.errorKind = sc.kind();
                JSONObject err = new JSONObject();
                err.put("error", new JSONObject()
                        .put("message", String.valueOf(last.getMessage()))
                        .put("type", sc.clientType())
                        .put("code", sc.kind()));
                try {
                    w.write("data: ");
                    w.write(err.toString());
                    w.write("\n\n");
                    // OpenAI 协议的流以 data: [DONE] 结束。错误路径也必须补上——
                    // 否则严格等 DONE 的客户端（官方 SDK 就是）会一直等到超时，
                    // 用户体感是"卡住"，而这恰恰是最需要立刻反馈的时刻。
                    w.write("data: [DONE]\n\n");
                    w.flush();
                } catch (Exception ignored) {
                }
            }
            try {
                w.flush();
            } catch (Exception ignored) {
            }
        } else {
            // 非流式全程可 failover：Collector 是纯内存收集，失败前没写过任何东西
            Exception last = null;
            for (int i = 0; i < tries; i++) {
                Config.Provider p = cands.get(i);
                String um = upstreamModel(p, req.model);
                Fronts.Collector c = new Fronts.Collector();
                long t0 = System.currentTimeMillis();
                try {
                    serve(req.model, p, um, req, c);
                    recordDirectOutcome(p, um, req.ephemeral, t0, null);
                    if (req.rawResponse && c.rawBody == null) {
                        Log.w("保真请求被渠道降级为 canonical（该渠道不支持原样透传）："
                                + p.name + "/" + um);
                    }
                    /* 保真模式拿到的是上游完整响应体：多 choice / logprobs 都在里面，
                       不再用 canonical 重新拼。上游不支持保真时 rawBody 为 null，
                       照旧走 render()，行为与以前完全一致。 */
                    writeJson(out, 200, req.rawResponse && c.rawBody != null
                            ? c.rawBody.toString()
                            : Fronts.OpenAiFront.render(req.model, c));
                    return;
                } catch (Exception e) {
                    recordDirectOutcome(p, um, req.ephemeral, t0, e);
                    last = e;
                    /* 写响应时客户端断开：不能换号重发（会白烧一次完整 completion）。 */
                    if (e instanceof ClientGone) return;
                    if (!(e instanceof Throttled)) {
                        Log.w("直连第 " + (i + 1) + " 个候选失败（" + p.name + "）：" + e.getMessage());
                    }
                    if (FailScope.of(e).stopDirect()) {
                        Log.w("纯调用失败属于模型/请求问题，停止换号重试：" + req.model);
                        break;
                    }
                    if (i + 1 < tries) Log.i("非流式可换号，重试下一个候选：" + req.model);
                }
            }
            if (!writeScopedFailure(out, r, last)) {
                throw last == null ? new IllegalStateException("没有可用候选") : last;
            }
            return;
        }
    }

    /* ------------------------------------------------------------- 竞速 */

    /** 一次竞速里几路一起跑。 */
    private static final int RACE_N = 3;

    /**
     * 全局闸门：同时最多几场竞速。
     *
     * 一场竞速占 RACE_N（3）条线程。以前闸门是 16 场、线程池只有 8 条：
     * 第 3 场起 racer 就在无界队列里排队，早就不是"同时起跑"了；更糟的是
     * 客户端 180 秒超时后仍在队列里的任务照常执行，白打一次上游。
     * 现在闸门与线程池按 1:3 对齐，池里没有排队任务。
     */
    private static final int RACE_GATE_MAX = 4;

    private static final java.util.concurrent.Semaphore RACE_GATE =
            new java.util.concurrent.Semaphore(RACE_GATE_MAX);

    private static final java.util.concurrent.ExecutorService RACE_POOL =
            java.util.concurrent.Executors.newFixedThreadPool(RACE_GATE_MAX * RACE_N, r -> {
                Thread t = new Thread(r, "cpa-race");
                t.setDaemon(true);
                return t;
            });

    /** 一次竞速的共享胜负状态。 */
    private static final class RaceRace {
        final java.util.concurrent.atomic.AtomicReference<String> winnerId =
                new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.CountDownLatch winnerDone =
                new java.util.concurrent.CountDownLatch(1);
        /** 判决闩：tryWin 成功就放下，主线程不再 sleep(20) 忙等 */
        final java.util.concurrent.CountDownLatch decided =
                new java.util.concurrent.CountDownLatch(1);
        volatile ChunkedStream winnerChunked;
        volatile long decidedAt;
        /** 主线程收尾后置 true：迟到的 racer 看到它就不再提交任何分帧/响应头。 */
        volatile boolean closed;

        boolean tryWin(String id) {
            if (winnerId.compareAndSet(null, id)) {
                decidedAt = System.currentTimeMillis();
                decided.countDown();
                return true;
            }
            return false;
        }
    }

    /** 最近一次竞速的结果，给界面看。 */
    static volatile String lastRaceReport = "";

    /**
     * auto-race：同一请求几路并发，第一个吐出内容的胜出，其余立刻掐掉。
     *
     * 只做流式；带工具或非流式会被退回普通选路（工具要确定性）。
     * 每路来自不同账号——同账号并行是风控的重灾区。
     */
    private void serveRace(Req r, OutputStream out, Proto.Req req) throws Exception {
        if (!RACE_GATE.tryAcquire()) {
            Log.w("竞速闸门已满（" + RACE_GATE_MAX + " 场在跑），这次退回普通选路");
            serveVirtualOpenAi(r, out, req);
            return;
        }
        try {
            List<Router.Pick> cands = Router.raceCandidates(RACE_N, req);
            if (cands.size() < 2) {
                Log.i("auto-race：只有 " + cands.size() + " 个不同账号可选，退回普通选路");
                serveVirtualOpenAi(r, out, req);
                return;
            }

            final RaceRace race = new RaceRace();
            final long t0 = System.currentTimeMillis();
            final java.util.concurrent.CountDownLatch all = new java.util.concurrent.CountDownLatch(cands.size());
            final Conn raceConn = CURRENT.get();
            final Object raceBucket = RaceCancels.beginRace();
            StringBuilder roster = new StringBuilder();
            for (Router.Pick c : cands) {
                roster.append(c.key()).append(' ');
                final String id = c.key();
                final Config.Provider prov = c.provider;
                final String model = c.model;
                RACE_POOL.execute(() -> {
                    /* 已经决出胜负（或整场已收尾）时，排队晚到的 racer 直接退出：
                       不要再打上游，也不要再建响应头。all 必须减，否则主线程会白等。 */
                    if (race.closed || race.winnerId.get() != null) {
                        all.countDown();
                        return;
                    }
                    RaceCancels.Handle handle = RaceCancels.bind(raceBucket, id);
                    DeferredWriter dw = new DeferredWriter(out);
                    dw.racerId = id;
                    dw.race = race;
                    BufferedWriter w = new BufferedWriter(dw, 8192);
                    Fronts.OpenAiFront.Streamer streamer = new Fronts.OpenAiFront.Streamer(w, req.model);
                    /* 每路一份独立观测对象：三路共写一个 Conn 会丢更新/串数据。
                       胜者确定后再由 copyObs() 合并回 raceConn。 */
                    final Conn obs = new Conn(out, null);
                    try {
                        // 显式把 Conn 传进 serve：竞速线程的 ThreadLocal 是空的，
                        // 不传的话渠道名/模型名/token 全回填不进用量（整个竞速都记成失败）
                        serve(req.model, prov, model, req, streamer, obs);
                        try {
                            w.flush();
                        } catch (Exception ignored) {
                        }
                        if (!dw.committed() && race.tryWin(id)) {
                            dw.commit();            // 没内容但也没别人赢，就它了
                        }
                    } catch (RaceLost lost) {
                        // 输了不算失败；但要把可能打上的半开试探标记放掉（否则半开自锁）
                        Router.abortProbe(id);
                    } catch (Exception e) {
                        // 赢家可能在提交响应头前就抛异常；这也不是成功。
                        if (id.equals(race.winnerId.get())) {
                            markResponseFailed(out);
                        }
                        if (handle.cancelled) {
                            // winner 决出后被掐线的输家：不算失败，不进冷却
                            Router.abortProbe(id);
                        } else if (e instanceof ClientGone) {
                            // 客户端断开：与上游无关，不记失败
                            Router.abortProbe(id);
                        } else if (e instanceof InvalidRequest) {
                            // 4xx 多半是请求本身的问题，换哪个通道都一样 → 不记失败
                            Log.w("竞速一路被上游拒绝（请求问题，不记失败）：" + id + " · " + e.getMessage());
                            Router.abortProbe(id);
                        } else if (!(e instanceof Throttled)) {
                            Router.recordFail(id, String.valueOf(e.getMessage()));
                            Log.w("竞速一路失败（" + id + "）：" + e.getMessage());
                        }
                    } finally {
                        RaceCancels.unbind(raceBucket, id);
                        all.countDown();
                        if (id.equals(race.winnerId.get())) {
                            copyObs(obs, raceConn);
                            race.winnerDone.countDown();
                        }
                    }
                });
            }

            // 等第一个胜出（或全部结束）：tryWin 成功会放下 decided，有判决立刻醒
            long deadline = System.currentTimeMillis() + 180_000L;
            while (race.winnerId.get() == null && all.getCount() > 0
                    && System.currentTimeMillis() < deadline) {
                race.decided.await(200, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
            String winner = race.winnerId.get();
            if (winner == null) {
                // 超时无胜出：同样要先关掉提交闸门，否则迟到的 racer 还能交出第二个响应头。
                // 与胜出路径保持一致的收尾顺序：先 cancel 掉还在跑的路，再置 closed，最后才写 fallback。
                race.closed = true;
                RaceCancels.cancelAll(raceBucket);
                RaceCancels.endRace(raceBucket);
                writeFallback(out, r, req, "auto-race：" + cands.size() + " 路都没跑出结果");
                return;
            }
            // 真取消：掐掉输家的上游连接——省额度，也把 RACE_POOL 线程还回来
            RaceCancels.cancelLosers(raceBucket, winner);
            Log.i("竞速：" + cands.size() + " 路进（" + roster.toString().trim() + "）· "
                    + winner + " 胜出 · " + (race.decidedAt - t0) + "ms");

            // 胜出的那一路把流吐完再放行，否则连接会被提前关掉。
            // 超时后标记收尾：迟到的 racer 不再提交响应头（见 DeferredWriter.commit）。
            boolean winnerFinished = race.winnerDone.await(180, java.util.concurrent.TimeUnit.SECONDS);
            race.closed = true;
            if (!winnerFinished) {
                markResponseFailed(out);
                Log.w("竞速：胜出方 180 秒未收尾，按超时处理（迟到者不再写响应）");
            }
            // 关键：chunked 流是那一路所在的线程建的，收尾必须在这里补上，
            // 否则客户端永远等不到结束标记（表现为"服务端 2 秒完事，客户端等 90 秒"）
            if (race.winnerChunked != null) {
                try {
                    race.winnerChunked.finish();
                    out.flush();
                } catch (Exception ignored) {
                }
            }
            RaceCancels.endRace(raceBucket);   // 丢掉整场句柄，防止漏 unbind 的输家留痕
            long total = System.currentTimeMillis() - t0;
            // 胜出仅表示最先出内容，不等于已经把完整响应成功交付给客户端。
            if (raceConn == null || !raceConn.responseFailed) Router.recordOk(winner, total);
            lastRaceReport = cands.size() + " 路竞速 · " + winner + " 胜出 · "
                    + (total / 1000.0) + "s";
            Log.i("竞速结束：" + lastRaceReport);
        } finally {
            RACE_GATE.release();
        }
    }

    /** 最多试几个候选。 */
    private static final int VIRTUAL_TRIES = 3;

    /**
     * 虚拟模型：按分数挑最好的账号×模型，失败自动换下一个。
     * 流式走 DeferredWriter —— 首 token 之前失败就干净地换，客户端看不到换号。
     */
    private void serveVirtualOpenAi(Req r, OutputStream out, Proto.Req req) throws Exception {
        // 卡死重试检测：同一段请求在短时间内被反复重发，直接停下并说明，
        // 不要再转发（转发只会让模型把同样的答案再吐一遍，白烧额度）。
        String repeated = Repeats.check(req);
        if (repeated != null) {
            Log.w("检测到重复请求（第 " + repeated + " 次，内容完全相同），已停止转发 → "
                    + req.model);
            writeError(out, r.path, 429,
                    "检测到客户端在重复发送完全相同的请求（这是第 " + repeated
                            + " 次）。网关已停止转发，以免白烧额度、并防止模型重复同一段回答。"
                            + "请检查客户端是否卡在重试循环里。");
            return;
        }
        Classifier.Kind kind = Classifier.of(req);
        Log.i("请求分类：" + kind.label() + "（" + kind.why + "）"
                + (req.tools.isEmpty() ? "" : " · 带 " + req.tools.size() + " 个工具"));
        List<Router.Pick> cands = Router.candidates(req.model, !req.tools.isEmpty(), req);
        if (cands.isEmpty()) {
            writeError(out, r.path, 404, "虚拟模型 " + req.model + " 现在没有可用候选");
            return;
        }
        /* 虚拟模型同样按累积失败概率决定试几个（见 Router.budget） */
        int tries = Router.budget(cands);
        StringBuilder picked = new StringBuilder();
        for (int i = 0; i < tries; i++) {
            picked.append(i > 0 ? " → " : "").append(cands.get(i).key())
                    .append("(").append(Math.round(cands.get(i).score)).append(")");
        }
        Log.i("虚拟模型 " + req.model + " 选路：" + picked);

        if (!req.stream) {
            Exception last = null;
            /* 轮换模式：坏模型只跳过这个模型，别把同模型的其它账号逐个打一遍；
               坏请求直接结束整轮（换模型也没用）。 */
            java.util.Set<String> skipModels = new java.util.HashSet<>();
            int attempts = 0;
            int softExtra = 0;
            for (int i = 0; i < cands.size() && attempts < tries; i++) {
                Router.Pick c = cands.get(i);
                if (skipModels.contains(c.model)) continue;
                attempts++;
                long t0 = System.currentTimeMillis();
                try {
                    Fronts.Collector col = new Fronts.Collector();
                    serve(req.model, c.provider, c.model, req, col);
                    /* 4.32.0 工具补偿：模型把调用写在正文里、没吐结构化 tool_calls 时，
                       在这里把它捞成真正的调用，再交给客户端。
                       只做非流式：Collector 的正文还在内存里、还没写给客户端，
                       换错了也能退回去；流式已经出网，撤不回来，不做。 */
                    compensateToolCall(req, col);
                    Router.recordOk(c.key(), System.currentTimeMillis() - t0);
                    Router.notePicked(req.model, c.key());
                        writeJson(out, 200, Fronts.OpenAiFront.render(req.model, col));
                    return;
                } catch (Exception e) {
                    last = e;
                    FailScope sc = FailScope.of(e);
                    if (e instanceof ClientGone) {
                        Log.i("候选调用期间客户端断开，不记失败：" + c.key());
                        Router.abortProbe(c.key());
                    } else if (e instanceof InvalidRequest
                            || sc.modelScoped() || sc == FailScope.REQUEST) {
                        // 请求/模型问题：**不能**对每个候选各记一次失败，
                        // 否则一次坏请求/坏模型会把整组账号打残。
                        Log.w("候选被拒绝（模型/请求问题，不记账号失败）：" + c.key()
                                + " · " + e.getMessage());
                        Router.abortProbe(c.key());
                    } else if (!(e instanceof Throttled)) {
                        // 限流/额度耗尽的记录在 serve() 里做过，这里只保证"不记失败"
                        Router.recordFail(c.key(), String.valueOf(e.getMessage()));
                    }
                    Log.w("虚拟模型 " + req.model + " 第 " + (i + 1) + " 个候选失败（"
                            + c.key() + "）：" + e.getMessage());
                    /* 请求级风控是"这个模型/这条请求"的组合判定：同一份 body 换
                       glm/kimi 往往就过。它不该占本轮有限的重试预算，最多多给 3 个候选。 */
                    if (isSoftChannel(e) && softExtra < 3) {
                        softExtra++;
                        tries = Math.min(cands.size(), tries + 1);
                        Log.w("请求级风控，不占本轮预算，继续换模型（预算 → " + tries + "）");
                    }
                    if (sc.stopRotation()) {
                        last = e;
                        break;
                    }
                    if (sc.skipModelInRotation()) {
                        skipModels.add(c.model);
                        Log.w("模型问题，本轮跳过 " + c.model + "，换其它模型："
                                + Upstreams.shortenPublic(String.valueOf(e.getMessage())));
                    }
                }
            }
            /* 请求本身不合法时不装成"兜底回答"：换个模型也过不了，直接告诉客户端。 */
            if (last != null && FailScope.of(last) == FailScope.REQUEST) {
                writeScopedFailure(out, r, last);
                return;
            }
            writeFallback(out, r, req, "虚拟模型 " + req.model + " 所有候选都失败："
                    + (last == null ? "" : last.getMessage()));
            return;
        }

        Exception last = null;
        java.util.Set<String> skipModels = new java.util.HashSet<>();
        int attempts = 0;
        int softExtra = 0;
        for (int i = 0; i < cands.size() && attempts < tries; i++) {
            Router.Pick c = cands.get(i);
            if (skipModels.contains(c.model)) continue;
            attempts++;
            DeferredWriter dw = new DeferredWriter(out);
            BufferedWriter w = new BufferedWriter(dw, 8192);
            Fronts.OpenAiFront.Streamer streamer = new Fronts.OpenAiFront.Streamer(w, req.model);
            long t0 = System.currentTimeMillis();
            try {
                serve(req.model, c.provider, c.model, req, streamer);
                try {
                    w.flush();                       // 把最后一段推进 DeferredWriter
                } catch (Exception ignored) {
                }
                if (!dw.committed()) dw.commit();    // 正常结束但内容太少，也得把响应发出去
                try {
                    dw.flush();
                } catch (Exception ignored) {
                }
                Router.recordOk(c.key(), System.currentTimeMillis() - t0);
                Router.notePicked(req.model, c.key());
                      // 客户端拿到结果了，说明不是卡死
                return;
            } catch (Exception e) {
                last = e;
                FailScope sc = FailScope.of(e);
                if (e instanceof ClientGone) {
                    Log.i("候选调用期间客户端断开，不记失败：" + c.key());
                    Router.abortProbe(c.key());
                } else if (e instanceof InvalidRequest
                        || sc.modelScoped() || sc == FailScope.REQUEST) {
                    Log.w("候选被拒绝（模型/请求问题，不记账号失败）：" + c.key()
                            + " · " + e.getMessage());
                    Router.abortProbe(c.key());
                } else if (!(e instanceof Throttled)) {
                    Router.recordFail(c.key(), String.valueOf(e.getMessage()));
                }
                if (dw.committed()) {
                    markResponseFailed(out);
                    // 已经吐出去一部分了，换不了号，只能把错误当事件发出去
                    Log.w("虚拟模型 " + req.model + " 中途失败（" + c.key() + "），已提交无法换号："
                            + e.getMessage());
                    try {
                        JSONObject err = new JSONObject();
                        err.put("error", new JSONObject()
                                .put("message", String.valueOf(e.getMessage()))
                                .put("type", sc.clientType()));
                        dw.write("data: " + err + "\n\n");
                        dw.flush();
                    } catch (Exception ignored) {
                    }
                    return;
                }
                if (sc.stopRotation()) {
                    last = e;
                    break;
                }
                if (sc.skipModelInRotation()) {
                    skipModels.add(c.model);
                    Log.w("模型问题，本轮跳过 " + c.model + "，换其它模型："
                            + Upstreams.shortenPublic(String.valueOf(e.getMessage())));
                }
                Log.w("虚拟模型 " + req.model + " 第 " + (i + 1) + " 个候选失败（"
                        + c.key() + "），未提交，换下一个：" + e.getMessage());
                if (isSoftChannel(e) && softExtra < 3) {
                    softExtra++;
                    tries = Math.min(cands.size(), tries + 1);
                    Log.w("请求级风控，不占本轮预算，继续换模型（预算 → " + tries + "）");
                }
            }
        }
        if (last != null && FailScope.of(last) == FailScope.REQUEST) {
            writeScopedFailure(out, r, last);
            return;
        }
        // 一个都没成，而且什么都还没写出去 → 正常回错误
        writeFallback(out, r, req, "虚拟模型 " + req.model + " 所有候选都失败："
                + (last == null ? "" : last.getMessage()));
    }

    /** 上游全挂时说的话。说清楚是系统提示，别让客户端以为这是模型回答。 */
    private static final String FALLBACK_TEXT =
            "⚠️ 上游账号暂时都不通，这次没能拿到回答，请稍后重试。（网关提示，不是模型输出）";

    /**
     * 兜底：上游全挂了也返回一个合法回答，而不是让客户端报错。
     *
     * 代价是客户端分不清"这是兜底"和"这是回答"——所以文案里写明是网关提示，
     * 真实原因记进日志，界面上也能看到。
     */
    private void writeFallback(OutputStream out, Req r, Proto.Req req, String realError) {
        markResponseFailed(out);
        Log.w("虚拟模型 " + (req == null ? "?" : req.model) + " 上游全挂，返回兜底话术。真实原因："
                + Upstreams.shortenPublic(realError));
        try {
            if (req != null && req.stream) {
                BufferedWriter w = startSse(out);
                Fronts.OpenAiFront.Streamer s = new Fronts.OpenAiFront.Streamer(w, req.model);
                s.text(FALLBACK_TEXT);
                s.finish("stop", null);
                w.flush();
            } else {
                Fronts.Collector c = new Fronts.Collector();
                c.text(FALLBACK_TEXT);
                c.finish("stop", null);
                writeJson(out, 200, Fronts.OpenAiFront.render(req == null ? "auto" : req.model, c));
            }
        } catch (Exception e) {
            try {
                writeError(out, r == null ? "/v1/chat/completions" : r.path, 502, realError);
            } catch (Exception ignored) {
            }
        }
    }


    /**
     * 虚拟模型（auto / auto-code / auto-long / auto-race）在**非 OpenAI 协议**上的解析。
     *
     * 以前只有 /v1/chat/completions 接了选路，Anthropic / Responses / Gemini 传 auto 直接 404——
     * 用户那边看到的是"同样的模型名，换个接口就不存在了"。这里统一挑一个候选，
     * 让四套接口对虚拟模型的行为一致（流式细节仍交给各自的 Front）。
     */
    private String resolveVirtual(String model, Proto.Req req) {
        if (!Router.isVirtual(model)) return model;
        Proto.Req r = req == null ? new Proto.Req() : req;
        List<Router.Pick> cands = Router.candidates(model, !r.tools.isEmpty(), r);
        if (cands == null || cands.isEmpty()) return "";
        Router.Pick c = cands.get(0);
        Router.notePicked(model, c.key());
        // 这里不能 recordOk：请求还没发出去，记一次成功会顺手清掉该账号的熔断状态，
        // 等于"传个 auto 就能把正在熔断的账号洗白"。真实成败由 serve() 之后的路径记录。
        Log.i("虚拟模型 " + model + " 在非 OpenAI 协议上解析为：" + c.key());
        return c.provider.name + "/" + c.model;
    }

    /**
     * `POST /v1/messages/count_tokens` —— 数 token，不真的调用上游。
     *
     * 为什么值得单独做：Anthropic 官方客户端（含 Claude Code 这类 CLI）在长对话、
     * 自动压缩上下文、以及发之前预检时都会调它。没有这个端点就是 404，
     * 客户端的常见反应是要么直接放弃这轮，要么退化成"按最大上下文硬截断"——
     * 表现就是"明明模型装得下，却被截了"。
     *
     * 口径与官方对齐的地方：
     *   · 响应是 `{"input_tokens": N}`；
     *   · 只算输入，不算输出（我们也不知道客户端会要多少输出）；
     *   · 传了未知模型也照样算——官方对无法识别的模型同样会回一个估算值，
     *     而不是报错，因为调用方要的是"够不够装下"这个判断。
     *
     * 与官方**必然有偏差**的地方（这点必须说清楚，别让人以为这是精确值）：
     *   手机端跑不起真正的分词器，这里用的是 Classifier.estimateTokens 那套粗估
     *   （ASCII 4 字符≈1 token、非 ASCII 1 字符≈1 token、图片每张 1200）。
     *   它和 Anthropic 官方的分词结果会有几个百分点的差异，所以：
     *     · 拿它做"能不能装进上下文"的判断是安全的（本来就是保守估大）；
     *     · 拿它做计费对账是不可靠的。
     *   响应里额外带一个 `"estimated": true` 明示这一点，官方没有这个字段，
     *   客户端会忽略它，但人看日志时能立刻分辨。
     */
    private void handleCountTokens(Req r, OutputStream out) throws Exception {
        JSONObject body;
        try {
            body = new JSONObject(r.body.isEmpty() ? "{}" : r.body);
        } catch (Exception e) {
            writeError(out, r.path, 400, "invalid JSON body");
            return;
        }
        int tokens;
        try {
            Proto.Req req = Fronts.AnthropicFront.parse(body);
            RequestCheck.check(req);
            tokens = Classifier.estimateTokens(req);
        } catch (Throwable t) {
            /* 解析失败时不要把整个调用打回去：这个端点的语义是"给个估算"，
               客户端只是想决定怎么切分，拿一个偏保守的数字比拿 400 有用。
               用原始 body 长度兜底，同样按 ASCII 4:1 的粗估。 */
            Log.w("count_tokens 解析失败，退回按原始长度估算：" + t);
            String raw = r.body == null ? "" : r.body;
            long ascii = 0, wide = 0;
            for (int i = 0; i < raw.length(); i++) {
                if (raw.charAt(i) < 128) ascii++; else wide++;
            }
            tokens = (int) Math.min(ascii / 4 + wide, Integer.MAX_VALUE);
        }
        JSONObject o = new JSONObject();
        o.put("input_tokens", tokens);
        o.put("estimated", true);
        byte[] bytes = o.toString().getBytes("UTF-8");
        writeRaw(out, 200, "application/json", bytes, false);
    }

    private void handleAnthropic(Req r, OutputStream out) throws Exception {
        JSONObject body;
        try {
            body = new JSONObject(r.body.isEmpty() ? "{}" : r.body);
        } catch (Exception e) {
            writeError(out, r.path, 400, "invalid JSON body");
            return;
        }
        Proto.Req req = Fronts.AnthropicFront.parse(body);
        /* Claude Code 把 1M 上下文、细粒度工具流、interleaved thinking 这些能力
           都写在 anthropic-beta 里。网关出站也是 Anthropic 时原样带上，
           否则同一个地址、同一个 Key，走网关会比直连少能力。 */
        req.beta = r.header("anthropic-beta");
        /* 4.34.0 说"五处前端入口全接上"，实际这里漏了：Claude Code 走的正是
           /v1/messages，缺 id 的工具调用会一路发到上游，再被记成渠道失败。
           补在解析之后、选路之前：坏请求不占候选、不进熔断。 */
        RequestCheck.check(req);
        if (Router.isVirtual(req.model)) {
            String real = resolveVirtual(req.model, req);
            if (real.isEmpty()) {
                writeError(out, r.path, 503, "虚拟模型 " + req.model + " 暂时没有可用候选");
                return;
            }
            req.model = real;
        }
        List<Config.Provider> cands = pickAll(req.model);
        if (cands.isEmpty()) {
            writeError(out, r.path, 404, unknownModelMessage(req.model));
            return;
        }
        /* 试几个候选由累积失败概率决定，不再固定 3 次（见 Router.budget） */
        int tries = Router.budget(cands, req.model);

        /* 全部候选都被上游**明确告知**要等到某个时刻时，别去撞墙了。
           详见 confirmedBlock 的说明。 */
        ConfirmedBlock block = confirmedBlock(cands, req.model, tries);
        if (block.until > 0L) {
            writeAllThrottled(out, r.path, req.model, block.until, tries, block.channel);
            return;
        }
        if (req.stream) {
            // 注意：Anthropic 协议**流式不能 failover** —— 它的 `streamer.start()`
            // 会在 serve 之前就写出 message_start 事件（协议要求事件序列以它开头），
            // 那一刻就已经"提交"了。换个上游接上去会拼出两个 message_start。
            // 这是协议的结构性限制，不是这里偷懒；非流式那条已接 failover。
            Config.Provider p = cands.get(0);
            String um = upstreamModel(p, req.model);
            BufferedWriter w = startSse(out);
            Fronts.AnthropicFront.Streamer streamer = new Fronts.AnthropicFront.Streamer(w, req.model);
            streamer.start();
            long t0 = System.currentTimeMillis();
            try {
                serve(req.model, p, um, req, streamer);
                recordDirectOutcome(p, um, req.ephemeral, t0, null);
            } catch (Exception e) {
                recordDirectOutcome(p, um, req.ephemeral, t0, e);
                markResponseFailed(out);
                Log.w("upstream error (" + p.name + "): " + e.getMessage());
                JSONObject ev = new JSONObject();
                ev.put("type", "error");
                ev.put("error", new JSONObject().put("type", "api_error").put("message", String.valueOf(e.getMessage())));
                Fronts.sseEvent(w, "error", ev.toString());
            }
            try {
                w.flush();
            } catch (Exception ignored) {
            }
        } else {
            // 非流式全程可 failover（Collector 纯内存，失败前没写过东西）
            Exception last = null;
            for (int i = 0; i < tries; i++) {
                Config.Provider p = cands.get(i);
                String um = upstreamModel(p, req.model);
                Fronts.Collector c = new Fronts.Collector();
                long t0 = System.currentTimeMillis();
                try {
                    serve(req.model, p, um, req, c);
                    recordDirectOutcome(p, um, req.ephemeral, t0, null);
                    writeJson(out, 200, Fronts.AnthropicFront.render(req.model, c));
                    return;
                } catch (Exception e) {
                    recordDirectOutcome(p, um, req.ephemeral, t0, e);
                    last = e;
                    if (e instanceof ClientGone) return;
                    if (!(e instanceof Throttled)) {
                        Log.w("Anthropic 第 " + (i + 1) + " 个候选失败（" + p.name + "）：" + e.getMessage());
                    }
                    if (FailScope.of(e).stopDirect()) break;
                }
            }
            if (!writeScopedFailure(out, r, last)) {
                throw last == null ? new IllegalStateException("没有可用候选") : last;
            }
        }
    }

    private void handleGemini(Req r, OutputStream out, String model, boolean stream) throws Exception {
        JSONObject body;
        try {
            body = new JSONObject(r.body.isEmpty() ? "{}" : r.body);
        } catch (Exception e) {
            writeError(out, r.path, 400, "invalid JSON body");
            return;
        }
        Proto.Req req = Fronts.GeminiFront.parse(model, body);
        RequestCheck.check(req);
        // 与 Anthropic / Responses 对齐：虚拟模型先解析成具体候选，别让 auto 在 Gemini 面上 404
        String effective = model;
        if (Router.isVirtual(model)) {
            String real = resolveVirtual(model, req);
            if (real.isEmpty()) {
                writeError(out, r.path, 503, "虚拟模型 " + model + " 暂时没有可用候选");
                return;
            }
            effective = real;
        }
        List<Config.Provider> cands = pickAll(effective);
        if (cands.isEmpty()) {
            writeError(out, r.path, 404, unknownModelMessage(model));
            return;
        }
        /* 试几个候选由累积失败概率决定，不再固定 3 次（见 Router.budget） */
        int tries = Router.budget(cands, effective);

        /* 全部候选都被上游**明确告知**要等到某个时刻时，别去撞墙了。
           详见 confirmedBlock 的说明。 */
        ConfirmedBlock block = confirmedBlock(cands, effective, tries);
        if (block.until > 0L) {
            writeAllThrottled(out, r.path, effective, block.until, tries, block.channel);
            return;
        }
        if (stream) {
            // Gemini 流式同样无法安全换号：SSE 一旦开始就无法回退（同 Anthropic 的理由）。
            // 非流式那条已接 failover。
            Config.Provider p = cands.get(0);
            // 必须用 effective 而不是 model：
            // upstreamModel 对不含 "/" 的名字原样返回，用 model 的话 "auto" 会一路送到上游。
            // 对 WorkBuddy 这类"把 model 塞进 JSON body"的上游它被容忍了（所以没暴露），
            // 但原生 Gemini 上游是**拼 URL** 的（Upstreams: v1beta/models/<model>:streamGenerateContent），
            // models/auto:streamGenerateContent 直接 404 —— Gemini 前端 + 原生 Gemini 上游 + 虚拟模型会全灭。
            String um = upstreamModel(p, effective);
            BufferedWriter w = startSse(out);
            Fronts.GeminiFront.Streamer streamer = new Fronts.GeminiFront.Streamer(w, model);
            long t0 = System.currentTimeMillis();
            try {
                serve(req.model, p, um, req, streamer);
                recordDirectOutcome(p, um, req.ephemeral, t0, null);
            } catch (Exception e) {
                recordDirectOutcome(p, um, req.ephemeral, t0, e);
                markResponseFailed(out);
                Log.w("upstream error (" + p.name + "): " + e.getMessage());
                JSONObject err = new JSONObject();
                err.put("error", new JSONObject().put("code", 500).put("message", String.valueOf(e.getMessage())).put("status", "INTERNAL"));
                Fronts.sse(w, err.toString());
            }
            try {
                w.flush();
            } catch (Exception ignored) {
            }
        } else {
            Exception last = null;
            for (int i = 0; i < tries; i++) {
                Config.Provider p = cands.get(i);
                String um = upstreamModel(p, effective);
                Fronts.Collector c = new Fronts.Collector();
                long t0 = System.currentTimeMillis();
                try {
                    serve(req.model, p, um, req, c);
                    recordDirectOutcome(p, um, req.ephemeral, t0, null);
                    writeJson(out, 200, Fronts.GeminiFront.render(model, c));
                    return;
                } catch (Exception e) {
                    recordDirectOutcome(p, um, req.ephemeral, t0, e);
                    last = e;
                    if (e instanceof ClientGone) return;
                    if (!(e instanceof Throttled)) {
                        Log.w("Gemini 第 " + (i + 1) + " 个候选失败（" + p.name + "）：" + e.getMessage());
                    }
                    if (FailScope.of(e).stopDirect()) break;
                }
            }
            if (!writeScopedFailure(out, r, last)) {
                throw last == null ? new IllegalStateException("没有可用候选") : last;
            }
        }
    }

    /* ------------------------------------------------- OpenAI /v1/responses shim */

    /* ---- Responses 输入转换的辅助（4.32.2） ---- */

    /**
     * 把攒着的并行 function_call 落成一条 assistant 消息。
     *
     * Responses 里并行工具调用就是几个相邻的 function_call 项，没有共同的父消息；
     * Chat Completions 要求它们属于**同一条** assistant 消息的 tool_calls 数组，
     * 且后面紧跟每个调用对应的 role=tool 消息。顺序错了上游会直接拒绝。
     */
    private static void flushCalls(List<JSONObject> msgs, JSONArray calls) throws Exception {
        if (calls == null || calls.length() == 0) return;
        JSONObject m = new JSONObject();
        m.put("role", "assistant");
        m.put("content", "");
        m.put("tool_calls", calls);
        msgs.add(m);
    }

    /**
     * content 可能是字符串，也可能是 part 数组（input_text / output_text / text）。
     * Chat Completions 的 content 要字符串，所以把各 part 的 text 拼起来。
     */
    private static Object plainContent(Object content) {
        if (content == null) return null;
        if (content instanceof String) return content;
        if (content instanceof JSONArray) {
            JSONArray arr = (JSONArray) content;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
                Object p = arr.opt(i);
                if (p instanceof JSONObject) {
                    JSONObject part = (JSONObject) p;
                    String t = Proto.optString(part, "text", "");
                    if (t.isEmpty()) t = Proto.optString(part, "content", "");
                    if (!t.isEmpty()) {
                        if (sb.length() > 0) sb.append('\n');
                        sb.append(t);
                    }
                } else if (p != null) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(String.valueOf(p));
                }
            }
            return sb.toString();
        }
        return String.valueOf(content);
    }

    /** function_call_output 的 output 也可能是字符串或数组，统一成字符串。 */
    private static String outputText(Object output) {
        Object v = plainContent(output);
        return v == null ? "" : String.valueOf(v);
    }

    private void handleResponses(Req r, OutputStream out) throws Exception {
        JSONObject body;
        try {
            body = new JSONObject(r.body.isEmpty() ? "{}" : r.body);
        } catch (Exception e) {
            writeError(out, r.path, 400, "invalid JSON body");
            return;
        }
        String askedModel = Proto.optString(body, "model", "");
        JSONObject chat = new JSONObject();
        chat.put("model", askedModel);
        chat.put("stream", body.optBoolean("stream", false));
        Integer mt = Proto.optInt(body, "max_output_tokens");
        if (mt != null) chat.put("max_tokens", mt.intValue());
        /* Responses 也有标准的 temperature / top_p；top_k 是非标准但常见的扩展。
           以前这里只转 model/stream/max_tokens，采样参数被整个丢掉。 */
        if (body.has("temperature")) chat.put("temperature", body.opt("temperature"));
        if (body.has("top_p")) chat.put("top_p", body.opt("top_p"));
        if (body.has("top_k")) chat.put("top_k", body.opt("top_k"));
        List<JSONObject> msgs = new ArrayList<>();
        String instructions = Proto.optString(body, "instructions", "");
        if (!instructions.isEmpty()) {
            msgs.add(new JSONObject().put("role", "system").put("content", instructions));
        }
        /*
         * input → messages 转换（4.32.2 重写）。
         *
         * Responses 的 input 是一串“item”，类型比 Chat Completions 的 message 丰富得多：
         *   {type:message, role:developer|user|assistant, content:[{type:input_text|output_text,text}]}
         *   {type:function_call, call_id, name, arguments}
         *   {type:function_call_output, call_id, output}
         *
         * 旧实现只认 function_call_output，其余一律当 message 处理，带来三个问题：
         *  1) developer 角色原样透传 —— Chat Completions 不认这个角色，上游报错。
         *  2) function_call 项没有 role 也没有 content，被当成 {role:user, content:null}。
         *  3) function_call_output（role=tool）前面没有配对的 assistant 工具调用，
         *     上游必然拒绝。客户端把拒绝当“完成”，界面上表现为“用时 1 秒、无任何内容”。
         *
         * 现在按 Chat Completions 的形状重建：
         *   · developer → system
         *   · 连续的 function_call 合并进**同一条** assistant 消息的 tool_calls
         *   · content 是数组时把 input_text/output_text 的 text 拼起来
         */
        Object input = body.opt("input");
        if (input instanceof String) {
            msgs.add(new JSONObject().put("role", "user").put("content", input));
        } else if (input instanceof JSONArray) {
            JSONArray arr = (JSONArray) input;
            JSONArray pendingCalls = null;

            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item == null) {
                    flushCalls(msgs, pendingCalls);
                    pendingCalls = null;
                    msgs.add(new JSONObject().put("role", "user")
                            .put("content", String.valueOf(arr.opt(i))));
                    continue;
                }
                String type = Proto.optString(item, "type", "message");

                if ("function_call".equals(type)) {
                    if (pendingCalls == null) pendingCalls = new JSONArray();
                    JSONObject fn = new JSONObject();
                    fn.put("name", Proto.optString(item, "name", ""));
                    fn.put("arguments", Proto.optString(item, "arguments", "{}"));
                    JSONObject call = new JSONObject();
                    call.put("id", Proto.optString(item, "call_id", ""));
                    call.put("type", "function");
                    call.put("function", fn);
                    pendingCalls.put(call);
                    continue;
                }

                flushCalls(msgs, pendingCalls);
                pendingCalls = null;

                if ("function_call_output".equals(type)) {
                    msgs.add(new JSONObject().put("role", "tool")
                            .put("tool_call_id", Proto.optString(item, "call_id", ""))
                            .put("content", outputText(item.opt("output"))));
                    continue;
                }

                String role = Proto.optString(item, "role", "user");
                if ("developer".equals(role)) role = "system";
                Object content = plainContent(item.opt("content"));
                if (content == null || "".equals(content)) {
                    content = Proto.optString(item, "text", "");
                }
                msgs.add(new JSONObject().put("role", role).put("content", content));
            }
            flushCalls(msgs, pendingCalls);
        }
        JSONArray chatMsgs = new JSONArray();
        for (JSONObject m : msgs) chatMsgs.put(m);
        chat.put("messages", chatMsgs);
        /* 工具：Responses 与 Chat Completions 的 schema 不一样，必须转。
         *
         * Responses 原生格式（工具名和参数在**顶层**）：
         *   {"type":"function","name":"bash","description":"…","parameters":{…}}
         * Chat Completions 格式（多一层 function）：
         *   {"type":"function","function":{"name":"bash","description":"…","parameters":{…}}}
         *
         * 以前这里只做 `converted.put(t)` 原样透传 —— 遇到 Responses 原生格式时，
         * 上游收到的工具没有 function 字段，等价于"什么都没定义"，
         * 模型于是回答"我没有可用的 bash 工具"。两种格式都得认。 */
        JSONArray toolArr = body.optJSONArray("tools");
        if (toolArr != null) {
            JSONArray converted = new JSONArray();
            for (int i = 0; i < toolArr.length(); i++) {
                JSONObject t = toolArr.optJSONObject(i);
                if (t == null) continue;
                String type = Proto.optString(t, "type", "function");
                if (!"function".equals(type)) continue;
                JSONObject fn = t.optJSONObject("function");
                if (fn != null) {
                    converted.put(t);                       /* 已经是 Chat 格式 */
                    continue;
                }
                /* 顶层写法 → 包进 function */
                String tname = Proto.optString(t, "name", "");
                if (tname.isEmpty()) continue;
                JSONObject wrapped = new JSONObject();
                wrapped.put("name", tname);
                String desc = Proto.optString(t, "description", "");
                if (!desc.isEmpty()) wrapped.put("description", desc);
                JSONObject params = t.optJSONObject("parameters");
                wrapped.put("parameters", params == null
                        ? new JSONObject().put("type", "object").put("properties", new JSONObject())
                        : params);
                JSONObject outTool = new JSONObject();
                outTool.put("type", "function");
                outTool.put("function", wrapped);
                converted.put(outTool);
            }
            if (converted.length() > 0) chat.put("tools", converted);
        }
        Proto.Req req = Fronts.OpenAiFront.parse(chat);
        RequestCheck.check(req);
        if (Router.isVirtual(req.model)) {
            String real = resolveVirtual(req.model, req);
            if (real.isEmpty()) {
                writeError(out, r.path, 503, "虚拟模型 " + req.model + " 暂时没有可用候选");
                return;
            }
            req.model = real;
        }
        List<Config.Provider> cands = pickAll(req.model);
        if (cands.isEmpty()) {
            writeError(out, r.path, 404, unknownModelMessage(req.model));
            return;
        }
        /* 试几个候选由累积失败概率决定，不再固定 3 次（见 Router.budget） */
        int tries = Router.budget(cands, req.model);

        /* 全部候选都被上游**明确告知**要等到某个时刻时，别去撞墙了。
           详见 confirmedBlock 的说明。 */
        ConfirmedBlock block = confirmedBlock(cands, req.model, tries);
        if (block.until > 0L) {
            writeAllThrottled(out, r.path, req.model, block.until, tries, block.channel);
            return;
        }
        Config.Provider p = cands.get(0);
        String um = upstreamModel(p, req.model);
        Proto.Upstream up = Upstreams.create(p);
        String respId = "resp_" + Long.toHexString(System.currentTimeMillis());
        if (req.stream) {
            BufferedWriter w = startSse(out);
            final boolean[] created = {false};
            Fronts.Collector c = new Fronts.Collector();
            Proto.Sink sink = new Proto.Sink() {
                private int seq = 0;
                /*
                 * output_index 分配：正文消息固定占 0，每个 function_call 从 1 递增。
                 *
                 * 以前全部硬编码 0 —— 那样客户端会把 function_call 当成"消息的第 N 个
                 * content part"，而不是一个并列的 output item。Responses 协议里
                 * output_index 才是 item 的标识，必须唯一。
                 */
                private int nextIndex = 1;
                private final java.util.List<String> callIds = new java.util.ArrayList<>();
                private final java.util.List<String> callNames = new java.util.ArrayList<>();
                private final java.util.List<String> callArgs = new java.util.ArrayList<>();

                private void event(String type, JSONObject payload) throws Exception {
                    payload.put("type", type);
                    payload.put("sequence_number", seq++);
                    Fronts.sseEvent(w, type, payload.toString());
                }

                private void ensureCreated() throws Exception {
                    if (created[0]) return;
                    created[0] = true;
                    JSONObject resp = new JSONObject();
                    resp.put("id", respId);
                    resp.put("object", "response");
                    resp.put("status", "in_progress");
                    resp.put("model", req.model);
                    JSONObject ev = new JSONObject();
                    ev.put("response", resp);
                    ev.put("output_index", 0);
                    event("response.created", ev);
                    JSONObject item = new JSONObject();
                    item.put("id", "msg_" + respId);
                    item.put("type", "message");
                    item.put("role", "assistant");
                    item.put("status", "in_progress");
                    item.put("content", new JSONArray());
                    JSONObject added = new JSONObject();
                    added.put("output_index", 0);
                    added.put("item", item);
                    event("response.output_item.added", added);
                    JSONObject part = new JSONObject();
                    part.put("type", "output_text");
                    part.put("text", "");
                    part.put("annotations", new JSONArray());
                    JSONObject cp = new JSONObject();
                    cp.put("output_index", 0);
                    cp.put("content_index", 0);
                    cp.put("part", part);
                    event("response.content_part.added", cp);
                }

                @Override
                public void text(String delta) {
                    try {
                        ensureCreated();
                        c.text(delta);
                        JSONObject ev = new JSONObject();
                        ev.put("item_id", "msg_" + respId);
                        ev.put("output_index", 0);
                        ev.put("content_index", 0);
                        ev.put("delta", delta);
                        event("response.output_text.delta", ev);
                    } catch (Exception ignored) {
                    }
                }

                @Override
                public void thinking(String delta) {
                    try {
                        ensureCreated();
                        c.thinking(delta);
                    } catch (Exception ignored) {
                    }
                }

                /* 工具调用的完整事件序列（4.32.1）。
                   以前这个方法只有 c.toolCall(...) 一行，塞进 Collector 就完事，
                   一个事件都不发给客户端 —— 走 /v1/responses 流式时工具调用被静默丢弃。 */
                @Override
                public void toolCall(String callId, String name, String argsJson) {
                    try {
                        c.toolCall(callId, name, argsJson);
                        Proto.Part part = null;
                        for (int i = c.toolCalls.size() - 1; i >= 0; i--) {
                            Proto.Part cand = c.toolCalls.get(i);
                            if (cand != null && name != null && name.equals(cand.toolName)) {
                                part = cand;
                                break;
                            }
                        }
                        if (part == null) return;
                        String fixedArgs = part.argsJson == null ? "{}" : part.argsJson;
                        String id = callId == null || callId.isEmpty() ? "fc_" + seq : callId;
                        int idx = nextIndex++;
                        callIds.add(id);
                        callNames.add(name == null ? "" : name);
                        callArgs.add(fixedArgs);

                        ensureCreated();

                        JSONObject item = new JSONObject();
                        item.put("id", id);
                        item.put("type", "function_call");
                        item.put("status", "in_progress");
                        item.put("call_id", id);
                        item.put("name", name == null ? "" : name);
                        item.put("arguments", "");
                        JSONObject added = new JSONObject();
                        added.put("output_index", idx);
                        added.put("item", item);
                        event("response.output_item.added", added);

                        JSONObject d = new JSONObject();
                        d.put("item_id", id);
                        d.put("output_index", idx);
                        d.put("delta", fixedArgs);
                        event("response.function_call_arguments.delta", d);

                        JSONObject dn = new JSONObject();
                        dn.put("item_id", id);
                        dn.put("output_index", idx);
                        dn.put("arguments", fixedArgs);
                        event("response.function_call_arguments.done", dn);

                        JSONObject doneItem = new JSONObject();
                        doneItem.put("id", id);
                        doneItem.put("type", "function_call");
                        doneItem.put("status", "completed");
                        doneItem.put("call_id", id);
                        doneItem.put("name", name == null ? "" : name);
                        doneItem.put("arguments", fixedArgs);
                        JSONObject doneEv = new JSONObject();
                        doneEv.put("output_index", idx);
                        doneEv.put("item", doneItem);
                        event("response.output_item.done", doneEv);
                    } catch (Exception ignored) {
                    }
                }

                @Override
                public void finish(String stopReason, Proto.Usage u) {
                    try {
                        ensureCreated();
                        c.finish(stopReason, u);
                        JSONObject done = new JSONObject();
                        done.put("item_id", "msg_" + respId);
                        done.put("output_index", 0);
                        done.put("content_index", 0);
                        done.put("text", c.text.toString());
                        event("response.output_text.done", done);

                        JSONObject msgItem = new JSONObject();
                        msgItem.put("id", "msg_" + respId);
                        msgItem.put("type", "message");
                        msgItem.put("role", "assistant");
                        msgItem.put("status", "completed");
                        JSONArray mc = new JSONArray();
                        if (c.text.length() > 0) {
                            mc.put(new JSONObject().put("type", "output_text")
                                    .put("text", c.text.toString())
                                    .put("annotations", new JSONArray()));
                        }
                        msgItem.put("content", mc);
                        JSONObject msgDone = new JSONObject();
                        msgDone.put("output_index", 0);
                        msgDone.put("item", msgItem);
                        event("response.output_item.done", msgDone);

                        /* completed 里必须带完整 output：忽略增量、只看最终对象的
                           客户端（不少 SDK 的兜底路径）否则完全看不到工具调用。 */
                        JSONArray output = new JSONArray();
                        output.put(msgItem);
                        for (int i = 0; i < callIds.size(); i++) {
                            JSONObject fc = new JSONObject();
                            fc.put("id", callIds.get(i));
                            fc.put("type", "function_call");
                            fc.put("status", "completed");
                            fc.put("call_id", callIds.get(i));
                            fc.put("name", callNames.get(i));
                            fc.put("arguments", callArgs.get(i));
                            output.put(fc);
                        }

                        JSONObject resp = new JSONObject();
                        resp.put("id", respId);
                        resp.put("object", "response");
                        resp.put("status", "completed");
                        resp.put("model", req.model);
                        resp.put("output", output);
                        JSONObject umeta = new JSONObject();
                        umeta.put("input_tokens", c.usage.input);
                        umeta.put("output_tokens", c.usage.output);
                        umeta.put("total_tokens", c.usage.total());
                        resp.put("usage", umeta);
                        JSONObject finalEv = new JSONObject();
                        finalEv.put("response", resp);
                        event("response.completed", finalEv);
                    } catch (Exception ignored) {
                    }
                }
            };
            long t0 = System.currentTimeMillis();
            try {
                serve(req.model, p, um, req, sink);
                recordDirectOutcome(p, um, req.ephemeral, t0, null);
            } catch (Exception e) {
                recordDirectOutcome(p, um, req.ephemeral, t0, e);
                markResponseFailed(out);
                Log.w("upstream error (" + p.name + "): " + e.getMessage());
                JSONObject err = new JSONObject();
                err.put("type", "error");
                err.put("message", String.valueOf(e.getMessage()));
                Fronts.sseEvent(w, "error", err.toString());
            }
            try {
                w.flush();
            } catch (Exception ignored) {
            }
        } else {
            // 非流式接 failover（流式不接：SSE 一旦写出就无法回退，同 Anthropic/Gemini）。
            Fronts.Collector c = null;
            Exception last = null;
            for (int i = 0; i < tries; i++) {
                Config.Provider cp = cands.get(i);
                String cum = upstreamModel(cp, req.model);
                Fronts.Collector cc = new Fronts.Collector();
                long t0 = System.currentTimeMillis();
                try {
                    serve(req.model, cp, cum, req, cc);
                    recordDirectOutcome(cp, cum, req.ephemeral, t0, null);
                    c = cc;
                    last = null;
                    break;
                } catch (Exception e) {
                    recordDirectOutcome(cp, cum, req.ephemeral, t0, e);
                    last = e;
                    if (e instanceof ClientGone) return;
                    if (!(e instanceof Throttled)) {
                        Log.w("Responses 第 " + (i + 1) + " 个候选失败（" + cp.name + "）：" + e.getMessage());
                    }
                    if (FailScope.of(e).stopDirect()) break;
                }
            }
            if (last != null) {
                if (!writeScopedFailure(out, r, last)) throw last;
                return;
            }
            JSONObject out2 = new JSONObject();
            out2.put("id", respId);
            out2.put("object", "response");
            out2.put("created_at", System.currentTimeMillis() / 1000);
            out2.put("status", "completed");
            out2.put("model", req.model);
            JSONObject msg = new JSONObject();
            msg.put("id", "msg_" + respId);
            msg.put("type", "message");
            msg.put("role", "assistant");
            msg.put("status", "completed");
            JSONArray content = new JSONArray();
            if (c.text.length() > 0) {
                content.put(new JSONObject().put("type", "output_text").put("text", c.text.toString()).put("annotations", new JSONArray()));
            }
            msg.put("content", content);
            JSONArray output = new JSONArray();
            output.put(msg);
            for (Proto.Part t : c.toolCalls) {
                output.put(new JSONObject()
                        .put("type", "function_call")
                        .put("call_id", t.toolCallId)
                        .put("name", t.toolName)
                        .put("arguments", t.argsJson));
            }
            out2.put("output", output);
            JSONObject umeta = new JSONObject();
            umeta.put("input_tokens", c.usage.input);
            umeta.put("output_tokens", c.usage.output);
            umeta.put("total_tokens", c.usage.total());
            out2.put("usage", umeta);
            writeJson(out, 200, out2.toString());
        }
    }

    /* -------------------------------------------------------------------- panel */

    private String panelHtml() {
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html><head><meta charset=\"utf-8\">");
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
        sb.append("<title>CLIProxy</title><style>");
        sb.append("body{font-family:system-ui,-apple-system,sans-serif;background:#0f1115;color:#e6e6e6;margin:0;padding:20px}");
        sb.append("h1{font-size:18px}code{background:#1b1f27;padding:2px 6px;border-radius:4px;word-break:break-all}");
        sb.append("table{border-collapse:collapse;width:100%;margin-top:12px;font-size:14px}");
        sb.append("th,td{border-bottom:1px solid #262b35;padding:8px;text-align:left}");
        sb.append("th{color:#9aa4b2;font-weight:500}.tag{color:#7cc4ff}</style></head><body>");
        sb.append("<h1>CLIProxy</h1>");
        sb.append("<p>端口 <code>").append(config.port).append("</code> · 监听 <code>")
                .append(config.lanAccess ? "0.0.0.0" : "127.0.0.1").append("</code></p>");
        sb.append("<p>OpenAI: <code>/v1/chat/completions</code> · Anthropic: <code>/v1/messages</code> · Gemini: <code>/v1beta/models/{model}:generateContent</code></p>");
        sb.append("<table><tr><th>供应商</th><th>类型</th><th>模型</th></tr>");
        for (Config.Provider p : config.providers) {
            sb.append("<tr><td>").append(esc(p.name)).append(p.enabled ? "" : " (停用)").append("</td><td class=\"tag\">")
                    .append(esc(p.type)).append("</td><td>").append(esc(String.valueOf(p.models))).append("</td></tr>");
        }
        sb.append("</table></body></html>");
        return sb.toString();
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

}
