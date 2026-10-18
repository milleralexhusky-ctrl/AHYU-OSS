package com.cpa.gateway.core;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 加密配置导出 / 导入（换机迁移用）。
 *
 * 为什么要有：应用刻意关掉了 allowBackup（配置里有账号 token，不能让系统云备份顺走），
 * 代价就是换手机时所有账号、Key、供应商都得重来一遍 —— 对攒了十几个账号的用户
 * 这是最劝退的一步。这里提供一个**用户自己带密码**的加密导出：文件随便放、
 * 发给自己也行，没有密码谁也解不开，包括我。
 *
 * 与 Secret.java 的区别（别混用）：
 *   · Secret 用 Android Keystore 的密钥，**只在本机、本签名下有效**，卸载即失效 —— 这正是
 *     它不能用来做迁移的原因：搬到新手机必然解不开。所以导出用「用户密码 + PBKDF2」，
 *     密钥由密码算出来，跨设备可复现。
 *   · 文件里是完整的 gateway.json（含密码学保护后的敏感字段），不带明文。
 *
 * 文件格式（外层是可读 JSON，便于用户/工具确认"这是一个 AHYU 备份"）：
 * {
 *   "ahyuBackup": 1,                 格式版本
 *   "createdAt": 1730000000,         导出时间（epoch 秒）
 *   "app": "AHYU", "version": "4.29.0",
 *   "kdf": "PBKDF2WithHmacSHA256",
 *   "iterations": 200000,            明文保存，便于以后提高强度时仍能解开旧文件
 *   "salt": "<base64>", "iv": "<base64>",
 *   "cipher": "AES/GCM/NoPadding",
 *   "payload": "<base64 密文>"        gateway.json 的密文
 * }
 *
 * 安全取舍（都已刻意选择，改动前先读）：
 *   · PBKDF2-HMAC-SHA256，20 万次迭代：手机上约 200~400ms，暴力破解成本足够高，
 *     又不至于让"导出/导入"变成一次卡顿。迭代数写进文件，将来可只增不减。
 *   · AES-256-GCM：带认证标签，密文被改一个字节就会解密失败，不是"静默出乱码"。
 *   · 每次导出随机 salt + iv（SecureRandom），同一份配置导出两次密文不同，
 *     防止"密文相同 ⇒ 配置没变"这种信息泄漏。
 *   · 密码强度不设下限校验：这是本地工具，用户给自己的数据上锁，长度由他自己权衡；
 *     低强度时界面会提示，但不阻止（阻止只会让人用更差的办法存明文）。
 */
public final class Backup {

    /** 外层格式版本。payload 内部结构变了才需要动它，解密方据此兼容。 */
    public static final int FORMAT = 1;
    private static final String APP_TAG = "AHYU";
    private static final String KDF = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 200_000;
    private static final int KEY_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;          // GCM 推荐 96 bit
    private static final int GCM_TAG_BITS = 128;

    /** 解密失败的统一异常：密码错 与 文件损坏 不区分，避免当成密码验证器。 */
    public static class BadBackupException extends Exception {
        public BadBackupException(String msg) {
            super(msg);
        }
    }

    private Backup() {
    }

    /**
     * 把配置 JSON 加密成备份文本。
     *
     * @param config   当前配置（由 Config.toJson 一类的出口产出，敏感字段已由 Secret 处理过）
     * @param password 用户密码（调用方负责非空校验）
     * @return 备份文件内容（UTF-8 文本，JSON）
     */
    public static String export(JSONObject config, String password) throws Exception {
        if (config == null) throw new IllegalArgumentException("配置为空");
        if (password == null || password.isEmpty()) throw new IllegalArgumentException("密码为空");

        byte[] salt = random(SALT_BYTES);
        byte[] iv = random(IV_BYTES);
        byte[] plain = config.toString(2).getBytes(StandardCharsets.UTF_8);
        byte[] cipher = encrypt(plain, password, salt, iv);

        JSONObject out = new JSONObject();
        out.put("ahyuBackup", FORMAT);
        out.put("createdAt", System.currentTimeMillis() / 1000);
        out.put("app", APP_TAG);
        out.put("version", "4.29.0");
        out.put("kdf", KDF);
        out.put("iterations", ITERATIONS);
        out.put("cipher", "AES/GCM/NoPadding");
        out.put("salt", android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP));
        out.put("iv", android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP));
        out.put("payload", android.util.Base64.encodeToString(cipher, android.util.Base64.NO_WRAP));
        return out.toString(2);
    }

    /**
     * 解密备份文本，返回配置 JSON。
     *
     * @throws BadBackupException 密码不对、文件被改、或根本不是本应用的备份
     */
    public static JSONObject importFrom(String text, String password) throws BadBackupException {
        if (text == null || text.trim().isEmpty()) throw new BadBackupException("备份内容为空");
        if (password == null || password.isEmpty()) throw new BadBackupException("请输入密码");
        JSONObject o;
        try {
            o = new JSONObject(text);
        } catch (Throwable t) {
            throw new BadBackupException("不是有效的备份文件（JSON 解析失败）");
        }
        int ver = o.optInt("ahyuBackup", 0);
        if (ver <= 0) throw new BadBackupException("不是 AHYU 的备份文件");
        if (ver > FORMAT) throw new BadBackupException("备份来自更新的版本（格式 v" + ver + "），请先升级应用");
        try {
            int iter = o.optInt("iterations", ITERATIONS);
            /* 迭代数来自**不可信文件**，必须夹上界。
               PBKDF2 的耗时与迭代数成正比，而这个值原本只被 Math.max(1, …) 夹了下界：
               一个把 iterations 写成 20 亿的（伪造/损坏）备份，导入时会卡到用户以为死机。
               这里直接拒绝而不是静默夹到上限 —— 静默夹会把"参数异常"伪装成"密码不正确"，
               反而更难排查。上限定在 200 万的 10 倍余量，够未来版本提高强度。 */
            if (iter < 1000 || iter > 2_000_000) {
                throw new BadBackupException("备份文件的加密参数异常（iterations=" + iter + "），已拒绝加载");
            }
            byte[] salt = android.util.Base64.decode(o.getString("salt"), android.util.Base64.NO_WRAP);
            byte[] iv = android.util.Base64.decode(o.getString("iv"), android.util.Base64.NO_WRAP);
            byte[] ct = android.util.Base64.decode(o.getString("payload"), android.util.Base64.NO_WRAP);
            byte[] plain = decrypt(ct, password, salt, iv, iter);
            return new JSONObject(new String(plain, StandardCharsets.UTF_8));
        } catch (BadBackupException b) {
            throw b;
        } catch (Throwable t) {
            /* 密码错 / 密文被改 / 字段缺失，全部归为同一句话：
               不要细分"密码错"和"文件坏"，否则这个接口就成了密码验证器。 */
            throw new BadBackupException("密码不正确，或备份文件已损坏");
        }
    }

    /* ------------------------------------------------------------------ 文件封装 */

    /**
     * 导出到指定文件。
     *
     * 用「临时文件 + rename」而不是直接写目标：导出过程中被杀（内存压力、用户切走）时，
     * 不会留下一个"看起来存在、其实截断"的备份，后者比没有备份更危险 ——
     * 用户会以为已经备份好了，到换机那天才发现解不开。
     */
    public static void exportToFile(File target, JSONObject config, String password) throws Exception {
        String text = export(config, password);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        File tmp = new File(parent, target.getName() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();     // 落盘再 rename，避免掉电后只剩空文件
        } finally {
            out.close();
        }
        if (!tmp.renameTo(target)) {
            out = new FileOutputStream(target);
            try {
                out.write(text.getBytes(StandardCharsets.UTF_8));
                out.flush();
            } finally {
                out.close();
            }
            tmp.delete();
        }
    }

    /** 从文件读入并解密。 */
    public static JSONObject importFromFile(File src, String password) throws Exception {
        byte[] buf = readAll(src);
        if (buf.length == 0) throw new BadBackupException("备份文件为空");
        if (buf.length > 8 * 1024 * 1024) throw new BadBackupException("备份文件异常大，拒绝加载");
        return importFrom(new String(buf, StandardCharsets.UTF_8), password);
    }

    /** 建议的文件名：带时间戳，多次导出不互相覆盖。 */
    public static String suggestName() {
        java.text.SimpleDateFormat f =
                new java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US);
        /* 侧载分发没有更新提示，把版本号写进文件名——用户比对文件名就知道手上是哪一版。
           取不到版本就退回不带版本的老格式，绝不让文件名生成失败影响导出。 */
        String ver = versionTag();
        return "AHYU-备份-" + (ver.isEmpty() ? "" : ver + "-")
                + f.format(new java.util.Date()) + ".ahyu";
    }

    /** 当前应用版本（"4.29.11"）；取不到返回空串。 */
    public static String versionTag() {
        try {
            android.content.Context ctx = Gateway.context();
            if (ctx == null) return "";
            return String.valueOf(ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).versionName);
        } catch (Throwable t) {
            return "";
        }
    }

    /* -------------------------------------------------------------------- 加解密 */

    private static byte[] encrypt(byte[] plain, String password, byte[] salt, byte[] iv) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, derive(password, salt, ITERATIONS), new GCMParameterSpec(GCM_TAG_BITS, iv));
        return c.doFinal(plain);
    }

    private static byte[] decrypt(byte[] ct, String password, byte[] salt, byte[] iv, int iterations)
            throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, derive(password, salt, iterations), new GCMParameterSpec(GCM_TAG_BITS, iv));
        return c.doFinal(ct);       // 认证失败会抛 AEADBadTagException
    }

    /** PBKDF2 派生密钥。迭代数来自文件，所以旧备份永远能解开。 */
    private static SecretKey derive(String password, byte[] salt, int iterations) throws Exception {
        SecretKeyFactory f = SecretKeyFactory.getInstance(KDF);
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt,
                Math.max(1, iterations), KEY_BITS);
        try {
            return new SecretKeySpec(f.generateSecret(spec).getEncoded(), "AES");
        } finally {
            spec.clearPassword();
        }
    }

    private static byte[] random(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b;
    }

    private static byte[] readAll(File f) throws Exception {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } finally {
            in.close();
        }
    }

    /**
     * 密码强度提示（只提示、不拦截）。
     *
     * 返回 null 表示"够用"；否则是一句给人看的建议。
     * 判定很宽松：长度 ≥ 8 就放过 —— 本地工具里，能拦住人的从来不是规则，
     * 而是"让他愿意用密码而不是把明文丢进微信收藏"。
     */
    public static String passwordHint(String password) {
        if (password == null || password.isEmpty()) return "请设置一个密码";
        if (password.length() < 8) return "密码偏短，建议 8 位以上（尤其是要发到聊天软件里的话）";
        boolean digit = false, alpha = false, other = false;
        for (int i = 0; i < password.length(); i++) {
            char ch = password.charAt(i);
            if (ch >= '0' && ch <= '9') digit = true;
            else if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')) alpha = true;
            else other = true;
        }
        int kinds = (digit ? 1 : 0) + (alpha ? 1 : 0) + (other ? 1 : 0);
        if (password.length() < 12 && kinds < 2) return "建议混合字母与数字，或加长到 12 位以上";
        return null;
    }
}
