package com.cpa.gateway.core;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 配置里敏感字段的加解密（Android Keystore，AES-256-GCM）。
 *
 * 只加密三个字段：顶层 apiKey、providers[].apiKey、providers[].credentials（账号 token blob）。
 * 整个文件加密会让人没法手改配置、出事也不好救；这样处理后，配置文件仍然是可读 JSON，
 * 只有敏感值变成 `enc:v1:<base64(iv)>:<base64(ct)>`。
 *
 * 兼容性：读到明文就照原样用（老配置不用迁移），下次保存时自动转成密文。
 * 密钥存在 Android Keystore 里（TEE/StrongBox），**卸载应用或换签名后失效**——
 * 那时这些字段解不开，会退回空值，需要重新登录账号 / 重新填 Key（这是设计如此，不是 bug）。
 */
public final class Secret {

    private static final String PREFIX = "enc:v1:";
    private static final String ALIAS = "gateway_master";

    /** 最近一次落盘是否出现过"加密失败、退回明文"（设置页/日志页可以据此提醒用户）。 */
    public static volatile boolean lastSealFailed = false;

    private Secret() {
    }

    /** 值是否已经是密文。 */
    public static boolean isSealed(String v) {
        return v != null && v.startsWith(PREFIX);
    }

    /** 加密一个值；失败就保持明文（宁可露一点，也不要因为加密失败把配置写坏）。 */
    public static String seal(String plain) {
        if (plain == null || plain.isEmpty() || isSealed(plain)) return plain;
        try {
            javax.crypto.SecretKey key = key();
            javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            c.init(javax.crypto.Cipher.ENCRYPT_MODE, key);
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(plain.getBytes("UTF-8"));
            return PREFIX + b64(iv) + ":" + b64(ct);
        } catch (Throwable t) {
            // 加密失败时**不能装作没事**：以前只是打一行日志，用户以为配置是加密的，
            // 实际磁盘上是明文。这里把失败明确记成 ERROR 级并保留明文（可用性优先），
            // 同时在应用内日志页可见。
            Log.e("配置加密失败：敏感字段将以明文落盘（" + t + "）");
            lastSealFailed = true;
            return plain;
        }
    }

    /** 解密一个值；明文照原样返回，解不开就返回空串（并记日志）。 */
    public static String open(String stored) {
        if (!isSealed(stored)) return stored;
        try {
            String body = stored.substring(PREFIX.length());
            int cut = body.indexOf(':');
            if (cut <= 0) return "";
            byte[] iv = unb64(body.substring(0, cut));
            byte[] ct = unb64(body.substring(cut + 1));
            javax.crypto.Cipher d = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            d.init(javax.crypto.Cipher.DECRYPT_MODE, key(),
                    new javax.crypto.spec.GCMParameterSpec(128, iv));
            return new String(d.doFinal(ct), "UTF-8");
        } catch (Throwable t) {
            Log.w("配置解密失败（该字段退回空值，需要重新登录 / 重填 Key）：" + t);
            return "";
        }
    }

    /** 落盘前：把 JSON 里的敏感字段换成密文。 */
    public static JSONObject seal(JSONObject root) {
        if (root == null) return null;
        try {
            if (root.has("apiKey")) root.put("apiKey", seal(Proto.optString(root, "apiKey", "")));
            JSONArray aliases = root.optJSONArray("apiKeyAliases");
            if (aliases != null) {
                for (int i = 0; i < aliases.length(); i++) {
                    String a = aliases.optString(i, "");
                    if (!a.isEmpty()) aliases.put(i, seal(a));
                }
            }
            JSONArray arr = root.optJSONArray("providers");
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject p = arr.optJSONObject(i);
                if (p == null) continue;
                if (p.has("apiKey")) p.put("apiKey", seal(Proto.optString(p, "apiKey", "")));
                if (p.has("credentials")) {
                    p.put("credentials", seal(Proto.optString(p, "credentials", "")));
                }
            }
            // 每次落盘都如实统计"还剩几个敏感字段是明文"。
            // 这一次的 seal() 调用本身就带着重试语义：Keystore 一恢复，
            // 上一轮退回明文的值就会在这一次被真正加密——**自愈是天然的**，
            // 这里只是把结果**显性化**出去（以前 lastSealFailed 只设不读，用户看不到任何提示）。
            int left = countPlaintext(root);
            lastSealFailed = left > 0;
            if (left > 0) {
                Log.e("配置加密仍未恢复：还有 " + left + " 个敏感字段以明文落盘"
                        + "（Keystore 恢复后下次保存会自动迁回密文）");
            }
        } catch (Throwable t) {
            Log.w("配置加密出错（本次落盘可能仍有明文）：" + t);
            lastSealFailed = true;
        }
        return root;
    }

    /** 统计还有几个敏感字段是明文（只读，不改内容）。 */
    private static int countPlaintext(JSONObject root) {
        int n = 0;
        try {
            if (isPlainValue(root, "apiKey")) n++;
            JSONArray arr = root.optJSONArray("providers");
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject p = arr.optJSONObject(i);
                if (p == null) continue;
                if (isPlainValue(p, "apiKey")) n++;
                if (isPlainValue(p, "credentials")) n++;
            }
        } catch (Throwable ignored) {
        }
        return n;
    }

    private static boolean isPlainValue(JSONObject o, String field) {
        if (o == null || !o.has(field)) return false;
        String v = Proto.optString(o, field, "");
        return !v.isEmpty() && !isSealed(v);
    }

    /**
     * 备份导入前检查：有没有"本机密钥解不开"的密文。
     *
     * 4.37.0 之前的备份会把本机 Keystore 密的字段直接装进密码加密的 payload，
     * 换机后这些值必然解不开。旧代码把解不开当成空串继续导入，结果是
     * 账号看起来都恢复了、其实全没有凭据。这里在导入前先说清楚，
     * 返回第一个解不开的字段名（null 表示都能解/本来就没有密文）。
     */
    public static String unreadableSealed(JSONObject root) {
        if (root == null) return null;
        try {
            String top = unreadable(root, "apiKey");
            if (top != null) return "apiKey";
            JSONArray arr = root.optJSONArray("providers");
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject p = arr.optJSONObject(i);
                if (p == null) continue;
                if (unreadable(p, "apiKey") != null) {
                    return "providers[" + i + "].apiKey";
                }
                if (unreadable(p, "credentials") != null) {
                    return "providers[" + i + "].credentials";
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String unreadable(JSONObject o, String field) {
        if (o == null || !o.has(field)) return null;
        String v = Proto.optString(o, field, "");
        if (!isSealed(v)) return null;
        return open(v).isEmpty() ? field : null;
    }

    /** 读盘后：把密文换回明文。 */
    public static JSONObject open(JSONObject root) {
        if (root == null) return null;
        try {
            if (root.has("apiKey")) root.put("apiKey", open(Proto.optString(root, "apiKey", "")));
            JSONArray aliases = root.optJSONArray("apiKeyAliases");
            if (aliases != null) {
                for (int i = 0; i < aliases.length(); i++) {
                    String a = aliases.optString(i, "");
                    if (!a.isEmpty()) aliases.put(i, open(a));
                }
            }
            JSONArray arr = root.optJSONArray("providers");
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject p = arr.optJSONObject(i);
                if (p == null) continue;
                if (p.has("apiKey")) p.put("apiKey", open(Proto.optString(p, "apiKey", "")));
                if (p.has("credentials")) {
                    p.put("credentials", open(Proto.optString(p, "credentials", "")));
                }
            }
        } catch (Throwable t) {
            Log.w("配置解密出错： " + t);
        }
        return root;
    }

    /**
     * 检查当前配置里**还有没有明文敏感字段**，并尝试就地加密（自愈）。
     *
     * 背景：seal() 在 Keystore 抽风时会退回明文写入（可用性优先，见那里的注释）。
     * 但"降级"必须是**显性且会自己好**的，不能静默躺着：
     *   - 显性：返回值给设置页显示持久警告（lastSealFailed 以前只设不读，等于没告警）；
     *   - 自愈：Keystore 恢复后，下一次调用本方法就把明文迁回密文。
     *
     * 本方法只处理"已经躺在磁盘上的明文"，不改任何业务字段的值。
     *
     * @return 仍然处于明文的敏感字段个数（0 表示全部已加密）
     */
    public static int migratePlaintextIfPossible(JSONObject root) {
        if (root == null) return 0;
        int plainLeft = 0;
        try {
            plainLeft += migrateField(root, "apiKey");
            JSONArray arr = root.optJSONArray("providers");
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject p = arr.optJSONObject(i);
                if (p == null) continue;
                plainLeft += migrateField(p, "apiKey");
                plainLeft += migrateField(p, "credentials");
            }
        } catch (Throwable t) {
            Log.w("明文迁移检查失败：" + t);
        }
        return plainLeft;
    }

    /** 单个字段：是明文就试着加密；加密成功就写回，失败则计入"仍是明文"。 */
    private static int migrateField(JSONObject o, String field) {
        if (o == null || !o.has(field)) return 0;
        String v = Proto.optString(o, field, "");
        if (v.isEmpty() || isSealed(v)) return 0;       // 空值或已是密文 → 无需处理
        String sealed = seal(v);
        if (isSealed(sealed)) {
            try {
                o.put(field, sealed);
                return 0;                                // 加密成功，已迁走
            } catch (Throwable t) {
                Log.w("明文迁移写回失败：" + t);
            }
        }
        return 1;                                        // 仍是明文（Keystore 还没好）
    }

    /* ------------------------------------------------------- 启动自愈入口 */

    /**
     * 启动时的自愈入口：把磁盘上还躺着的明文敏感字段迁回密文。
     *
     * 为什么必须由启动流程主动调一次：
     * {@link #seal(JSONObject)} 的重试只发生在**保存时**。一个装好之后
     * 再也不打开设置页的用户，Keystore 就算已经恢复，明文也会一直躺到
     * 下一次保存为止——可能永远不触发。启动是天然的检查点。
     *
     * 三条约束（本方法自己保证，调用方不必额外判断）：
     *   1. **不依赖 Config 内存对象**：直接读磁盘文件，所以"Config 还没加载完"
     *      这种时序问题不会让它读到半成品。文件不存在／读不出／解析失败
     *      → 直接返回 {@link #MIGRATE_NOT_READY}，**不碰 lastSealFailed**，
     *      避免把一次"未就绪"误记成一次真实的加密失败。
     *   2. **不写业务字段的值**：只把明文换成密文，其它内容原样保留。
     *   3. **失败一定显性**：真的读到明文且迁不动（Keystore 没好）时，
     *      才置 {@link #lastSealFailed}，并打 ERROR 级日志。
     *
     * 调用方必须放在后台线程（Keystore 首次生成密钥可能慢）。
     *
     * @param configFile 配置文件（通常是 getFilesDir()/gateway.json）
     * @return 迁移后仍是明文的字段数；0 = 全部已加密；
     *         {@link #MIGRATE_NOT_READY}（-1）= 文件不存在或读不出，本次未取得结论
     */
    public static int migratePlaintextOnStartup(java.io.File configFile) {
        if (configFile == null || !configFile.exists()) {
            Log.i("启动自愈：配置文件不存在（全新安装），无需迁移");
            return MIGRATE_NOT_READY;
        }
        JSONObject root;
        try {
            byte[] buf = new byte[(int) configFile.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(configFile);
            int n = 0;
            while (n < buf.length) {
                int got = in.read(buf, n, buf.length - n);
                if (got < 0) break;
                n += got;
            }
            in.close();
            if (n <= 0) {
                Log.w("启动自愈：配置文件为空，本次跳过（不记失败）");
                return MIGRATE_NOT_READY;
            }
            root = new JSONObject(new String(buf, 0, n, "UTF-8"));
        } catch (Throwable t) {
            // 读不出/解析不了：这是"未就绪"，不是"加密失败"。
            // 故意不置 lastSealFailed —— 否则一次时序问题会污染这个字段，
            // 让它从"没人看"变成"不可信"，两头都白修。
            Log.w("启动自愈：配置读取失败，本次跳过（不记失败）：" + t);
            return MIGRATE_NOT_READY;
        }

        // 到这一步才算"Config 已就绪"，可以给出真实结论
        int plainBefore = countPlaintext(root);
        if (plainBefore == 0) {
            Log.i("启动自愈：敏感字段已全部为密文，无需迁移");
            lastSealFailed = false;
            return 0;
        }

        Log.w("启动自愈：发现 " + plainBefore + " 个明文敏感字段，尝试迁回密文…");
        int left = migratePlaintextIfPossible(root);
        if (left == 0) {
            // 全部迁成功 → 只有"真的写过盘"才算数，否则下次启动白做一遍
            try {
                writeSealed(configFile, root);
                lastSealFailed = false;
                Log.i("启动自愈：明文已全部迁回密文并落盘");
            } catch (Throwable t) {
                // 迁移成功但写盘失败：内存里是密文、磁盘上还是明文。
                // 结果等价于没迁，如实置位，下次启动再来。
                lastSealFailed = true;
                Log.e("启动自愈：迁移成功但写盘失败，明文仍在磁盘上：" + t);
            }
        } else {
            lastSealFailed = true;
            Log.e("启动自愈：Keystore 仍不可用，还有 " + left + " 个字段以明文保存"
                    + "（不影响使用，恢复后下次启动/保存会自动迁回密文）");
        }
        return left;
    }

    /** 把（已加密的）JSON 原子写回配置文件。 */
    private static void writeSealed(java.io.File f, JSONObject root) throws Exception {
        byte[] data = root.toString(2).getBytes("UTF-8");
        java.io.File tmp = new java.io.File(f.getParentFile(), f.getName() + ".migrate.tmp");
        java.io.FileOutputStream out = new java.io.FileOutputStream(tmp);
        try {
            out.write(data);
            out.flush();
            out.getFD().sync();
        } finally {
            out.close();
        }
        if (!tmp.renameTo(f)) {
            // rename 失败（跨设备/被占）时退回直接写，保证明文不会因迁移而丢
            java.io.FileOutputStream direct = new java.io.FileOutputStream(f);
            try {
                direct.write(data);
                direct.flush();
            } finally {
                direct.close();
            }
            tmp.delete();
        }
    }

    /** 启动迁移的"未取得结论"返回值：文件不存在或读不出，本次不算失败。 */
    public static final int MIGRATE_NOT_READY = -1;

    /* ------------------------------------------------------------ keystore */

    private static javax.crypto.SecretKey key() throws Exception {
        java.security.KeyStore ks = java.security.KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        java.security.Key existing = ks.getKey(ALIAS, null);
        if (existing instanceof javax.crypto.SecretKey) return (javax.crypto.SecretKey) existing;
        javax.crypto.KeyGenerator kg = javax.crypto.KeyGenerator.getInstance(
                android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new android.security.keystore.KeyGenParameterSpec.Builder(ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT
                        | android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return kg.generateKey();
    }

    private static String b64(byte[] data) {
        return android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP);
    }

    private static byte[] unb64(String s) {
        return android.util.Base64.decode(s, android.util.Base64.NO_WRAP);
    }
}
