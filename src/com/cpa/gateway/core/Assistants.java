package com.cpa.gateway.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * OpenAI Assistants / Threads 的最小兼容层（4.45.0）。
 *
 * <p>为什么做：Assistants 客户端不是"一个模型调用"，而是一组有状态的
 * assistants / threads / messages / runs / steps。只支持 chat 的话，
 * 这类 SDK 一上来就 404。这里把状态存在应用私有目录的 `assistants.json`，
 * run 执行时再走本机 `/v1/chat/completions`，复用现有的选路、换号、限额。
 *
 * <p>边界：只实现**同步 run**（创建后立即执行并返回 completed/failed），
 * 不做后台排队、不做 vector store、不做 code interpreter / file search。
 * 对绝大多数"把 assistant 当有记忆的 chat"的客户端够用。
 */
public final class Assistants {

    private static final String FILE = "assistants.json";
    private static final Object LOCK = new Object();
    private static JSONObject root;

    private Assistants() {
    }

    /** 找不到对象时抛这个，上层回 404。 */
    public static final class NotFound extends Exception {
        public NotFound(String message) {
            super(message);
        }
    }

    private static long now() {
        return System.currentTimeMillis() / 1000;
    }

    private static String id(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    private static JSONObject load() {
        if (root != null) return root;
        File f = file();
        if (f.isFile() && f.length() > 0) {
            try {
                byte[] data = new byte[(int) f.length()];
                FileInputStream in = new FileInputStream(f);
                try {
                    int off = 0;
                    while (off < data.length) {
                        int n = in.read(data, off, data.length - off);
                        if (n < 0) break;
                        off += n;
                    }
                } finally {
                    in.close();
                }
                root = new JSONObject(new String(data, StandardCharsets.UTF_8));
            } catch (Throwable t) {
                Log.w("assistants.json 读取失败，重新开始：" + t);
            }
        }
        if (root == null) root = new JSONObject();
        try {
            if (root.optJSONObject("assistants") == null) root.put("assistants", new JSONObject());
            if (root.optJSONObject("threads") == null) root.put("threads", new JSONObject());
        } catch (Exception ignored) {
        }
        return root;
    }

    private static File file() {
        return new File(Gateway.context().getFilesDir(), FILE);
    }

    private static void save() {
        try {
            JSONObject r = load();
            File f = file();
            File tmp = new File(f.getParentFile(), FILE + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(r.toString(2).getBytes(StandardCharsets.UTF_8));
                out.flush();
            } finally {
                out.close();
            }
            if (!tmp.renameTo(f)) {
                // Android 上同一目录 rename 通常成功；失败就退回复制
                tmp.delete();
            }
        } catch (Throwable t) {
            Log.w("assistants.json 写入失败：" + t);
        }
    }

    /* --------------------------------------------------------------- assistants */

    public static JSONObject createAssistant(JSONObject body) throws Exception {
        synchronized (LOCK) {
            JSONObject a = new JSONObject();
            a.put("id", id("asst_"));
            a.put("object", "assistant");
            a.put("created_at", now());
            a.put("name", body.optString("name", ""));
            a.put("description", body.opt("description"));
            a.put("model", body.optString("model", ""));
            a.put("instructions", body.optString("instructions", ""));
            a.put("tools", body.optJSONArray("tools") == null ? new JSONArray() : body.optJSONArray("tools"));
            a.put("metadata", body.optJSONObject("metadata") == null ? new JSONObject() : body.optJSONObject("metadata"));
            load().optJSONObject("assistants").put(a.getString("id"), a);
            save();
            return a;
        }
    }

    public static JSONArray listAssistants() {
        synchronized (LOCK) {
            return values(load().optJSONObject("assistants"));
        }
    }

    public static JSONObject getAssistant(String assistantId) throws NotFound {
        synchronized (LOCK) {
            JSONObject a = load().optJSONObject("assistants").optJSONObject(assistantId);
            if (a == null) throw new NotFound("assistant not found: " + assistantId);
            return a;
        }
    }

    public static void deleteAssistant(String assistantId) throws NotFound {
        synchronized (LOCK) {
            JSONObject all = load().optJSONObject("assistants");
            if (all.optJSONObject(assistantId) == null) {
                throw new NotFound("assistant not found: " + assistantId);
            }
            all.remove(assistantId);
            save();
        }
    }

    /* ------------------------------------------------------------------ threads */

    public static JSONObject createThread(JSONObject body) throws Exception {
        synchronized (LOCK) {
            JSONObject t = new JSONObject();
            t.put("id", id("thread_"));
            t.put("object", "thread");
            t.put("created_at", now());
            t.put("metadata", body != null && body.optJSONObject("metadata") != null
                    ? body.optJSONObject("metadata") : new JSONObject());
            JSONObject entry = new JSONObject();
            entry.put("thread", t);
            entry.put("messages", new JSONArray());
            entry.put("runs", new JSONArray());
            entry.put("steps", new JSONArray());
            load().optJSONObject("threads").put(t.getString("id"), entry);
            save();
            return t;
        }
    }

    public static JSONObject getThread(String threadId) throws NotFound {
        synchronized (LOCK) {
            return entry(threadId).optJSONObject("thread");
        }
    }

    public static void deleteThread(String threadId) throws NotFound {
        synchronized (LOCK) {
            JSONObject all = load().optJSONObject("threads");
            if (all.optJSONObject(threadId) == null) throw new NotFound("thread not found: " + threadId);
            all.remove(threadId);
            save();
        }
    }

    /* ----------------------------------------------------------------- messages */

    public static JSONObject addMessage(String threadId, JSONObject body) throws Exception {
        synchronized (LOCK) {
            JSONObject e = entry(threadId);
            JSONObject m = new JSONObject();
            m.put("id", id("msg_"));
            m.put("object", "thread.message");
            m.put("created_at", now());
            m.put("thread_id", threadId);
            m.put("role", body.optString("role", "user"));
            m.put("content", normalizeContent(body.opt("content")));
            m.put("assistant_id", body.opt("assistant_id"));
            m.put("run_id", body.opt("run_id"));
            m.put("attachments", body.optJSONArray("attachments") == null
                    ? new JSONArray() : body.optJSONArray("attachments"));
            m.put("metadata", body.optJSONObject("metadata") == null
                    ? new JSONObject() : body.optJSONObject("metadata"));
            JSONArray list = e.optJSONArray("messages");
            list.put(m);
            trim(list, 200);
            save();
            return m;
        }
    }

    /** 执行完 run 后写回的 assistant 消息（不经过 HTTP 入口）。 */
    public static JSONObject addAssistantMessage(String threadId, String text, String runId,
                                                 String assistantId) throws Exception {
        synchronized (LOCK) {
            JSONObject e = entry(threadId);
            JSONObject m = new JSONObject();
            m.put("id", id("msg_"));
            m.put("object", "thread.message");
            m.put("created_at", now());
            m.put("thread_id", threadId);
            m.put("role", "assistant");
            m.put("content", normalizeContent(text));
            m.put("assistant_id", assistantId);
            m.put("run_id", runId);
            m.put("attachments", new JSONArray());
            m.put("metadata", new JSONObject());
            JSONArray list = e.optJSONArray("messages");
            list.put(m);
            trim(list, 200);
            save();
            return m;
        }
    }

    public static JSONArray listMessages(String threadId, int limit, String order) throws NotFound {
        synchronized (LOCK) {
            JSONArray list = entry(threadId).optJSONArray("messages");
            JSONArray out = new JSONArray();
            if ("asc".equalsIgnoreCase(order)) {
                for (int i = 0; i < list.length() && out.length() < limit; i++) out.put(list.opt(i));
            } else {
                for (int i = list.length() - 1; i >= 0 && out.length() < limit; i--) out.put(list.opt(i));
            }
            return out;
        }
    }

    public static JSONObject getMessage(String threadId, String messageId) throws NotFound {
        synchronized (LOCK) {
            JSONArray list = entry(threadId).optJSONArray("messages");
            for (int i = 0; i < list.length(); i++) {
                JSONObject m = list.optJSONObject(i);
                if (m != null && messageId.equals(m.optString("id"))) return m;
            }
            throw new NotFound("message not found: " + messageId);
        }
    }

    /** 取给模型用的消息（role + 纯文本），按时间正序。 */
    public static JSONArray modelMessages(String threadId) throws NotFound {
        synchronized (LOCK) {
            JSONArray list = entry(threadId).optJSONArray("messages");
            JSONArray out = new JSONArray();
            for (int i = 0; i < list.length(); i++) {
                JSONObject m = list.optJSONObject(i);
                if (m == null) continue;
                String text = messageText(m);
                if (text.isEmpty()) continue;
                JSONObject row = new JSONObject();
                try {
                    row.put("role", m.optString("role", "user"));
                    row.put("content", text);
                    out.put(row);
                } catch (Exception ignored) {
                }
            }
            return out;
        }
    }

    private static String messageText(JSONObject m) {
        JSONArray content = m.optJSONArray("content");
        if (content == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            JSONObject c = content.optJSONObject(i);
            if (c == null) continue;
            JSONObject text = c.optJSONObject("text");
            if (text != null) {
                String v = text.optString("value", "");
                if (!v.isEmpty()) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(v);
                }
            }
        }
        return sb.toString();
    }

    private static JSONArray normalizeContent(Object content) {
        JSONArray out = new JSONArray();
        try {
            if (content instanceof String) {
                out.put(new JSONObject().put("type", "text")
                        .put("text", new JSONObject().put("value", content).put("annotations", new JSONArray())));
            } else if (content instanceof JSONArray) {
                JSONArray a = (JSONArray) content;
                for (int i = 0; i < a.length(); i++) {
                    Object item = a.opt(i);
                    if (item instanceof String) {
                        out.put(new JSONObject().put("type", "text")
                                .put("text", new JSONObject().put("value", item).put("annotations", new JSONArray())));
                    } else if (item instanceof JSONObject) {
                        JSONObject o = (JSONObject) item;
                        if (o.optJSONObject("text") == null && o.has("text")) {
                            o.put("text", new JSONObject().put("value", o.optString("text", ""))
                                    .put("annotations", new JSONArray()));
                        }
                        out.put(o);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /* --------------------------------------------------------------------- runs */

    public static JSONObject createRun(String threadId, JSONObject body) throws Exception {
        synchronized (LOCK) {
            JSONObject e = entry(threadId);
            JSONObject r = new JSONObject();
            r.put("id", id("run_"));
            r.put("object", "thread.run");
            r.put("created_at", now());
            r.put("thread_id", threadId);
            r.put("assistant_id", body.optString("assistant_id", ""));
            r.put("status", "queued");
            r.put("required_action", JSONObject.NULL);
            r.put("last_error", JSONObject.NULL);
            r.put("model", body.optString("model", ""));
            r.put("instructions", body.optString("instructions", ""));
            r.put("tools", body.optJSONArray("tools") == null ? new JSONArray() : body.optJSONArray("tools"));
            r.put("metadata", body.optJSONObject("metadata") == null
                    ? new JSONObject() : body.optJSONObject("metadata"));
            e.optJSONArray("runs").put(r);
            trim(e.optJSONArray("runs"), 50);
            save();
            return r;
        }
    }

    public static void updateRun(String threadId, JSONObject run) throws Exception {
        synchronized (LOCK) {
            JSONObject e = entry(threadId);
            JSONArray runs = e.optJSONArray("runs");
            for (int i = 0; i < runs.length(); i++) {
                JSONObject old = runs.optJSONObject(i);
                if (old != null && run.optString("id").equals(old.optString("id"))) {
                    runs.put(i, run);
                    save();
                    return;
                }
            }
            runs.put(run);
            save();
        }
    }

    public static JSONObject getRun(String threadId, String runId) throws NotFound {
        synchronized (LOCK) {
            JSONArray runs = entry(threadId).optJSONArray("runs");
            for (int i = 0; i < runs.length(); i++) {
                JSONObject r = runs.optJSONObject(i);
                if (r != null && runId.equals(r.optString("id"))) return r;
            }
            throw new NotFound("run not found: " + runId);
        }
    }

    public static JSONArray listRuns(String threadId) throws NotFound {
        synchronized (LOCK) {
            JSONArray in = entry(threadId).optJSONArray("runs");
            JSONArray out = new JSONArray();
            for (int i = in.length() - 1; i >= 0; i--) out.put(in.opt(i));
            return out;
        }
    }

    public static JSONObject addStep(String threadId, String runId, String assistantId,
                                     String type, JSONObject details, Proto.Usage usage) throws Exception {
        synchronized (LOCK) {
            JSONObject e = entry(threadId);
            JSONObject s = new JSONObject();
            s.put("id", id("step_"));
            s.put("object", "thread.run.step");
            s.put("created_at", now());
            s.put("run_id", runId);
            s.put("assistant_id", assistantId);
            s.put("thread_id", threadId);
            s.put("type", type);
            s.put("status", "completed");
            s.put("step_details", details == null ? new JSONObject() : details);
            if (usage != null) {
                JSONObject u = new JSONObject();
                u.put("prompt_tokens", usage.input);
                u.put("completion_tokens", usage.output);
                u.put("total_tokens", usage.input + usage.output);
                s.put("usage", u);
            }
            e.optJSONArray("steps").put(s);
            trim(e.optJSONArray("steps"), 200);
            save();
            return s;
        }
    }

    public static JSONArray listSteps(String threadId, String runId) throws NotFound {
        synchronized (LOCK) {
            JSONArray in = entry(threadId).optJSONArray("steps");
            JSONArray out = new JSONArray();
            for (int i = 0; i < in.length(); i++) {
                JSONObject s = in.optJSONObject(i);
                if (s != null && runId.equals(s.optString("run_id"))) out.put(s);
            }
            return out;
        }
    }

    /* ------------------------------------------------------------------ helpers */

    private static JSONObject entry(String threadId) throws NotFound {
        JSONObject e = load().optJSONObject("threads").optJSONObject(threadId);
        if (e == null) throw new NotFound("thread not found: " + threadId);
        return e;
    }

    private static JSONArray values(JSONObject map) {
        JSONArray out = new JSONArray();
        if (map == null) return out;
        java.util.Iterator<String> it = map.keys();
        while (it.hasNext()) {
            JSONObject v = map.optJSONObject(it.next());
            if (v != null) out.put(v);
        }
        return out;
    }

    private static void trim(JSONArray arr, int max) {
        try {
            while (arr.length() > max) arr.remove(0);
        } catch (Exception ignored) {
        }
    }

    /** 列表响应外壳。 */
    public static JSONObject listObject(JSONArray data) throws Exception {
        JSONObject o = new JSONObject();
        o.put("object", "list");
        o.put("data", data);
        o.put("first_id", data.length() > 0 && data.optJSONObject(0) != null
                ? data.optJSONObject(0).optString("id") : JSONObject.NULL);
        o.put("last_id", data.length() > 0 && data.optJSONObject(data.length() - 1) != null
                ? data.optJSONObject(data.length() - 1).optString("id") : JSONObject.NULL);
        o.put("has_more", false);
        return o;
    }
}
