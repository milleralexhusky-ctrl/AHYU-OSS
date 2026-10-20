package com.cpa.gateway.core;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 原始响应留样（4.33.1）。
 *
 * 为什么需要它：`shape` 只能告诉我们"上游回的是结构化调用 / 正文文本 / 空"，
 * 但看不到**它到底回了什么**。而 Trae 那条日志说得很明白——
 * 「本轮没有产生任何内容，原始流可能换了结构」。结构变了，光靠分类看不出变在哪，
 * 得把原始帧留下来对照。
 *
 * 这正是"照着它的结构来"的前提：先有真实样本，才谈得上逆推和适配。
 *
 * 策略（保守，宁缺毋滥）：
 *   · 只在**可疑**的请求上留样：响应为空、正文里像有调用、或上游报错。
 *     正常请求不留，否则一天几十 MB。
 *   · 单条最多 8KB，超出截断并标注。
 *   · 全部写进一个文件，超过 2MB 直接重置（只留一份，不做多天滚动）。
 *   · 内容脱敏：不写 Authorization、key 之类的头（这里只存响应体，不含请求头）。
 */
final class RawCap {

    private static final long MAX_PER_ENTRY = 8 * 1024;
    private static final Object LOCK = new Object();

    private RawCap() {
    }

    /**
     * 留一条样本。
     *
     * @param why   为什么要留（empty / looks-text / upstream-error）
     * @param meta  一行元信息（接口 渠道 模型 延迟 …）
     * @param body  原始响应（可截断）
     */
    static void note(String why, String meta, String body) {
        if (why == null || why.isEmpty()) return;
        String payload = body == null ? "" : body;
        boolean cut = payload.length() > MAX_PER_ENTRY;
        if (cut) payload = payload.substring(0, (int) MAX_PER_ENTRY);

        StringBuilder sb = new StringBuilder();
        sb.append("=== ").append(why).append(" @ ")
          .append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                  .format(new java.util.Date()))
          .append(" ===\n");
        if (meta != null && !meta.isEmpty()) sb.append("meta: ").append(meta).append('\n');
        if (cut) sb.append("(原始内容超长，已截断到 ").append(MAX_PER_ENTRY).append(" 字节)\n");
        sb.append(payload).append("\n\n");

        synchronized (LOCK) {
            android.content.Context ctx = Gateway.context();
            if (ctx == null) return;
            File f = new File(ctx.getFilesDir(), "rawcap.log");
            try {
                /* 超过 2MB 就重置（只留一份，不滚动），避免长期占盘 */
                if (f.isFile() && f.length() > 2L * 1024 * 1024) {
                    if (!f.delete()) { /* 删不掉就继续追加 */ }
                }
                FileOutputStream out = new FileOutputStream(f, true);
                try {
                    out.write(sb.toString().getBytes("UTF-8"));
                } finally {
                    out.close();
                }
            } catch (Throwable t) {
                /* 留样失败绝不能影响请求本身 */
            }
        }
    }

    /** 给界面/自检用：读出最近 N 字节。 */
    static String tail(int maxBytes) {
        synchronized (LOCK) {
            android.content.Context ctx = Gateway.context();
            if (ctx == null) return "";
            File f = new File(ctx.getFilesDir(), "rawcap.log");
            if (!f.isFile()) return "";
            try {
                long len = f.length();
                long skip = Math.max(0, len - maxBytes);
                java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r");
                try {
                    raf.seek(skip);
                    byte[] buf = new byte[(int) Math.min(maxBytes, len - skip)];
                    raf.readFully(buf);
                    return new String(buf, "UTF-8");
                } finally {
                    raf.close();
                }
            } catch (Throwable t) {
                return "";
            }
        }
    }
}
