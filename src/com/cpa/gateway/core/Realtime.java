package com.cpa.gateway.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * OpenAI Realtime（WebSocket）最小兼容层（4.46.0）。
 *
 * <p>范围：**文本**会话。客户端连接 `/v1/realtime` 后，可以发
 * `session.update`、`conversation.item.create`、`response.create`，
 * 网关把这些内容拼成一次 chat 请求（走本机 `/v1/chat/completions`，复用选路），
 * 再按 Realtime 事件回 `response.text.delta` / `response.completed`。
 *
 * <p>不做音频：`input_audio_buffer.append` 会被忽略，`session.modalities`
 * 固定回 `["text"]`。需要语音的客户端会明确看到会话只支持文本，而不是静默坏掉。
 */
public final class Realtime {

    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_FRAME = 16 * 1024 * 1024;

    private Realtime() {
    }

    public static void handle(Socket socket, InputStream in, OutputStream out,
                              Server.Req req, Config config) throws Exception {
        String key = req.header("sec-websocket-key");
        if (key.isEmpty()) {
            writeHttp(out, 400, "missing Sec-WebSocket-Key");
            return;
        }
        String accept = java.util.Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1")
                        .digest((key + WS_GUID).getBytes(StandardCharsets.UTF_8)));
        String head = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.flush();

        String sessionId = "sess_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String model = query(req.query, "model");
        if (model.isEmpty()) model = "auto";
        String instructions = "";
        List<JSONObject> messages = new ArrayList<>();

        JSONObject session = new JSONObject();
        session.put("id", sessionId);
        session.put("object", "realtime.session");
        session.put("model", model);
        session.put("modalities", new JSONArray().put("text"));
        session.put("instructions", instructions);
        session.put("voice", JSONObject.NULL);
        session.put("input_audio_format", JSONObject.NULL);
        session.put("output_audio_format", JSONObject.NULL);
        send(out, "session.created", new JSONObject().put("session", session));

        /* 升级完成后不再用 HTTP 的 60 秒空闲超时：Realtime 客户端可以长时间不发帧。
           服务端每 30 秒 ping 一次，180 秒收不到任何帧才判定断开。 */
        try {
            socket.setSoTimeout(0);
        } catch (Throwable ignored) {
        }
        final long[] lastClient = {System.currentTimeMillis()};
        final boolean[] closed = {false};
        final ByteArrayOutputStream fragments = new ByteArrayOutputStream();
        Thread pinger = new Thread(() -> {
            try {
                while (!closed[0]) {
                    Thread.sleep(30000);
                    if (closed[0]) return;
                    if (System.currentTimeMillis() - lastClient[0] > 180000) {
                        sendClose(out, new byte[]{0x03, (byte) 0xE8});
                        try {
                            socket.close();
                        } catch (Throwable ignored) {
                        }
                        return;
                    }
                    writeFrame(out, 0x9, new byte[0]);
                }
            } catch (InterruptedException ignored) {
            } catch (Throwable ignored) {
            }
        }, "cpa-ws-ping");
        pinger.setDaemon(true);
        pinger.start();

        int fragOpcode = -1;
        try {
            while (true) {
                Frame f = readFrame(in);
                if (f == null) return;
                lastClient[0] = System.currentTimeMillis();
                if (f.opcode == 0x8) {                 // close
                    sendClose(out, f.payload);
                    return;
                }
                if (f.opcode == 0x9) {                 // ping
                    writeFrame(out, 0xA, f.payload);
                    continue;
                }
                if (f.opcode == 0xA) continue;          // pong
                byte[] payload = null;
                if (f.opcode == 0x1) {
                    if (f.fin) {
                        payload = f.payload;
                    } else {
                        fragOpcode = 0x1;
                        fragments.reset();
                        fragments.write(f.payload);
                    }
                } else if (f.opcode == 0x0) {
                    if (fragOpcode == 0x1) {
                        fragments.write(f.payload);
                        if (f.fin) {
                            payload = fragments.toByteArray();
                            fragments.reset();
                            fragOpcode = -1;
                        }
                    }
                }
                if (payload == null) continue;          // 还没收完 / 非文本
                JSONObject ev;
                try {
                    ev = new JSONObject(new String(payload, StandardCharsets.UTF_8));
                } catch (Throwable t) {
                    sendError(out, "invalid JSON: " + t.getMessage());
                    continue;
                }
                String type = Proto.optString(ev, "type", "");
            if ("session.update".equals(type)) {
                JSONObject s = ev.optJSONObject("session");
                if (s != null) {
                    String m = Proto.optString(s, "model", "");
                    if (!m.isEmpty()) model = m;
                    instructions = Proto.optString(s, "instructions", instructions);
                    session.put("model", model);
                    session.put("instructions", instructions);
                }
                send(out, "session.updated", new JSONObject().put("session", session));
            } else if ("conversation.item.create".equals(type)) {
                JSONObject item = ev.optJSONObject("item");
                if (item != null) {
                    String role = Proto.optString(item, "role", "user");
                    String text = itemText(item);
                    JSONObject m = new JSONObject();
                    m.put("role", role);
                    m.put("content", text);
                    messages.add(m);
                    item.put("id", Proto.optString(item, "id", "item_" + UUID.randomUUID()
                            .toString().replace("-", "").substring(0, 12)));
                    item.put("status", "completed");
                    send(out, "conversation.item.created", new JSONObject().put("item", item));
                }
            } else if ("input_audio_buffer.append".equals(type)) {
                /* 不支持音频输入：不缓存、不转写。会话已经声明 modalities=["text"]。 */
            } else if ("input_audio_buffer.commit".equals(type)) {
                send(out, "input_audio_buffer.committed", new JSONObject());
            } else if ("input_audio_buffer.clear".equals(type)) {
                send(out, "input_audio_buffer.cleared", new JSONObject());
            } else if ("response.create".equals(type)) {
                respond(out, config, model, instructions, messages);
            } else if ("response.cancel".equals(type)) {
                send(out, "response.cancelled", new JSONObject()
                        .put("response_id", Proto.optString(ev, "response_id", "")));
            } else {
                sendError(out, "unsupported event type: " + type);
            }
            }
        } finally {
            closed[0] = true;
            pinger.interrupt();
        }
    }

    private static void respond(OutputStream out, Config config, String model,
                                String instructions, List<JSONObject> messages) {
        String responseId = "resp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String itemId = "item_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try {
            JSONArray msgs = new JSONArray();
            if (instructions != null && !instructions.isEmpty()) {
                msgs.put(new JSONObject().put("role", "system").put("content", instructions));
            }
            for (JSONObject m : messages) msgs.put(m);
            JSONObject chat = new JSONObject();
            chat.put("model", model);
            chat.put("messages", msgs);
            chat.put("stream", false);
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
            String text = msg == null ? "" : Proto.optString(msg, "content", "");

            JSONObject created = new JSONObject();
            created.put("id", responseId);
            created.put("object", "realtime.response");
            created.put("status", "in_progress");
            created.put("status_details", JSONObject.NULL);
            created.put("output", new JSONArray());
            created.put("usage", JSONObject.NULL);
            send(out, "response.created", new JSONObject().put("response", created));

            JSONObject item = new JSONObject();
            item.put("id", itemId);
            item.put("type", "message");
            item.put("status", "in_progress");
            item.put("role", "assistant");
            item.put("content", new JSONArray());
            send(out, "response.output_item.added", new JSONObject()
                    .put("response_id", responseId)
                    .put("output_index", 0)
                    .put("item", item));

            JSONObject part = new JSONObject();
            part.put("type", "text");
            part.put("text", "");
            send(out, "response.content_part.added", new JSONObject()
                    .put("response_id", responseId)
                    .put("item_id", itemId)
                    .put("output_index", 0)
                    .put("content_index", 0)
                    .put("part", part));

            if (!text.isEmpty()) {
                send(out, "response.text.delta", new JSONObject()
                        .put("response_id", responseId)
                        .put("item_id", itemId)
                        .put("output_index", 0)
                        .put("content_index", 0)
                        .put("delta", text));
                send(out, "response.text.done", new JSONObject()
                        .put("response_id", responseId)
                        .put("item_id", itemId)
                        .put("output_index", 0)
                        .put("content_index", 0)
                        .put("text", text));
                messages.add(new JSONObject().put("role", "assistant").put("content", text));
            }

            JSONObject donePart = new JSONObject().put("type", "text").put("text", text);
            JSONObject doneItem = new JSONObject();
            doneItem.put("id", itemId);
            doneItem.put("type", "message");
            doneItem.put("status", "completed");
            doneItem.put("role", "assistant");
            doneItem.put("content", new JSONArray().put(donePart));
            send(out, "response.output_item.done", new JSONObject()
                    .put("response_id", responseId)
                    .put("output_index", 0)
                    .put("item", doneItem));

            JSONObject completed = new JSONObject();
            completed.put("id", responseId);
            completed.put("object", "realtime.response");
            completed.put("status", "completed");
            completed.put("status_details", JSONObject.NULL);
            completed.put("output", new JSONArray().put(doneItem));
            JSONObject usage = reply.optJSONObject("usage");
            if (usage != null) {
                JSONObject u = new JSONObject();
                u.put("total_tokens", usage.optInt("total_tokens", 0));
                u.put("input_tokens", usage.optInt("prompt_tokens", 0));
                u.put("output_tokens", usage.optInt("completion_tokens", 0));
                completed.put("usage", u);
            } else {
                completed.put("usage", JSONObject.NULL);
            }
            send(out, "response.completed", new JSONObject().put("response", completed));
        } catch (Throwable t) {
            sendError(out, String.valueOf(t.getMessage()));
        }
    }

    private static String itemText(JSONObject item) {
        Object content = item.opt("content");
        StringBuilder sb = new StringBuilder();
        if (content instanceof String) return (String) content;
        if (content instanceof JSONArray) {
            JSONArray arr = (JSONArray) content;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p = arr.optJSONObject(i);
                if (p == null) continue;
                String t = Proto.optString(p, "text", "");
                if (t.isEmpty()) {
                    JSONObject tx = p.optJSONObject("text");
                    if (tx != null) t = Proto.optString(tx, "value", "");
                }
                if (!t.isEmpty()) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(t);
                }
            }
        }
        return sb.toString();
    }

    /* --------------------------------------------------------------- websocket */

    private static final class Frame {
        boolean fin;
        int opcode;
        byte[] payload;
    }

    private static Frame readFrame(InputStream in) throws Exception {
        int b0 = in.read();
        if (b0 < 0) return null;
        int b1 = in.read();
        if (b1 < 0) return null;
        Frame f = new Frame();
        f.fin = (b0 & 0x80) != 0;
        f.opcode = b0 & 0x0F;
        boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7F;
        if (len == 126) {
            len = ((long) (in.read() & 0xFF) << 8) | (in.read() & 0xFF);
        } else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) len = (len << 8) | (in.read() & 0xFF);
        }
        if (len < 0 || len > MAX_FRAME) throw new IllegalStateException("frame too large");
        byte[] mask = new byte[4];
        if (masked) {
            for (int i = 0; i < 4; i++) mask[i] = (byte) in.read();
        }
        f.payload = new byte[(int) len];
        int off = 0;
        while (off < len) {
            int n = in.read(f.payload, off, (int) len - off);
            if (n < 0) return null;
            off += n;
        }
        if (masked) {
            for (int i = 0; i < f.payload.length; i++) {
                f.payload[i] ^= mask[i & 3];
            }
        }
        return f;
    }

    private static synchronized void writeFrame(OutputStream out, int opcode, byte[] payload)
            throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        buf.write(0x80 | opcode);
        int len = payload == null ? 0 : payload.length;
        if (len <= 125) {
            buf.write(len);
        } else if (len <= 65535) {
            buf.write(126);
            buf.write((len >> 8) & 0xFF);
            buf.write(len & 0xFF);
        } else {
            buf.write(127);
            for (int i = 7; i >= 0; i--) buf.write((int) (((long) len >> (8 * i)) & 0xFF));
        }
        if (len > 0) buf.write(payload);
        out.write(buf.toByteArray());
        out.flush();
    }

    private static void send(OutputStream out, String type, JSONObject extra) {
        try {
            JSONObject o = new JSONObject();
            o.put("type", type);
            o.put("event_id", "event_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
            if (extra != null) {
                java.util.Iterator<String> it = extra.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    o.put(k, extra.opt(k));
                }
            }
            writeFrame(out, 0x1, o.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
        }
    }

    private static void sendError(OutputStream out, String message) {
        try {
            JSONObject err = new JSONObject();
            err.put("type", "server_error");
            err.put("message", message == null ? "unknown error" : message);
            send(out, "error", new JSONObject().put("error", err));
        } catch (Throwable ignored) {
        }
    }

    private static void sendClose(OutputStream out, byte[] payload) {
        try {
            writeFrame(out, 0x8, payload == null ? new byte[0] : payload);
        } catch (Throwable ignored) {
        }
    }

    private static void writeHttp(OutputStream out, int status, String message) {
        try {
            String body = "{\"error\":\"" + message + "\"}";
            String head = "HTTP/1.1 " + status + " Bad Request\r\n"
                    + "Content-Type: application/json\r\n"
                    + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length
                    + "\r\nConnection: close\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.UTF_8));
            out.write(body.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Throwable ignored) {
        }
    }

    private static String query(String raw, String name) {
        if (raw == null || raw.isEmpty()) return "";
        for (String kv : raw.split("&")) {
            int eq = kv.indexOf('=');
            if (eq <= 0) continue;
            if (!name.equals(kv.substring(0, eq))) continue;
            try {
                return java.net.URLDecoder.decode(kv.substring(eq + 1), "UTF-8");
            } catch (Exception e) {
                return kv.substring(eq + 1);
            }
        }
        return "";
    }
}
