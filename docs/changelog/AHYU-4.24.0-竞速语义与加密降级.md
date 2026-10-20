# AHYU 4.24.0 —— auto-race 语义、Secret 降级策略、剪贴板验证

按给定的三个方向实现。

## 一、auto-race 按最初定义重写选路

### 现状（确认的三个偏差）

```java
public static List<Pick> raceCandidates(int n) {
    List<Pick> all = candidates(AUTO, false);   // ← 只取 auto 单池
    ...                                          // ← 按 score() 排序，不看延迟
}
```

1. **只取 `AUTO` 一个池** —— 不含 `auto-code` / `auto-long`；
2. **按综合分排序**，不看延迟；
3. **`latencyEwma` 是死字段**：全工程只有两处 —— 声明（Router:89）和写入（Router:215），
   **从没被读过**。存了延迟却不用它排序。

### 新实现（按最初定义：烧三份额度换最低延迟）

```java
public static List<Pick> raceCandidates(int n) {
    // ① 三池并集（auto / auto-code / auto-long），按 provider/model 去重
    for (String pool : new String[]{AUTO, AUTO_CODE, AUTO_LONG}) {
        for (Pick c : candidates(pool, false)) { ... 去重放入 ... }
    }
    // ② 排序主键换成首字延迟
    all.sort((x, y) -> {
        long lx = latencyOf(x), ly = latencyOf(y);
        boolean hx = lx > 0, hy = ly > 0;
        if (hx && hy) return Long.compare(lx, ly);   // 都测过 → 快的在前
        if (hx != hy) return hx ? -1 : 1;            // 测过的优先
        return Double.compare(y.score, x.score);     // 都没测过 → 综合分兜底
    });
    // ③ 仍然每个候选来自不同账号
    ...
}
```

**普通 `score()` 完全没动** —— 只改这一条选路。

### 回归

```
auto-race: 200
```

## 二、Secret 回退明文：定为「降级可用，但显性 + 自愈」

### 策略

不走 fail-closed。理由：自用场景下 Keystore 抽风就把用户锁在自己网关外面，
比明文躺一阵更糟；而且明文也是躺在应用沙盒里。

保留回退兜底，补齐三样：

| 要求 | 实现 |
|---|---|
| **持久警告**（不是一行日志） | 设置页「服务」卡片顶部红色提示（见下） |
| **每次保存都重试加密** | 本来就是（`Config.save` 两个落盘点都调 `seal`） |
| **Keystore 恢复后自动迁回密文** | `seal(JSONObject)` 每次落盘都会重新尝试加密内存里的明文值 —— **自愈是天然的**，这轮把它显性化 |

### 关键发现：`lastSealFailed` 以前"只设不读"

```java
Secret.java:23   public static volatile boolean lastSealFailed = false;
Secret.java:48   lastSealFailed = true;      // 设置
// 全工程没有任何地方读它
```

所以审计说的"静默降级"**完全成立** —— 标志设了，但用户永远看不到。

### 实现

**① `seal(JSONObject)` 每次落盘如实统计剩余明文数并写进标志：**

```java
int left = countPlaintext(root);
lastSealFailed = left > 0;
if (left > 0) {
    Log.e("配置加密仍未恢复：还有 " + left + " 个敏感字段以明文落盘"
            + "（Keystore 恢复后下次保存会自动迁回密文）");
}
```

**② 新增 `Secret.migratePlaintextIfPossible(root)`** —— 主动把已躺在磁盘上的明文迁回密文，
返回仍处于明文的字段数（0 = 全部已加密）。

**③ 设置页持久警告：**

```java
if (Secret.lastSealFailed) {
    TextView warn = Ui.label(this,
        "⚠ 配置加密当前不可用：账号凭据与密钥正以明文保存在应用目录里。"
        + "文件仍可正常使用，Keystore 恢复后下次保存会自动转回密文。");
    warn.setTextColor(0xFFFF6B6B);
    ...
}
```

文案明确说明"仍可正常使用"（用户不会以为坏了）和"会自动转回密文"（知道有救）。

## 三、剪贴板验证：逻辑已证，真机两用例未做成

### 你要的两个用例

1. 复制 key → 等满 60 秒 → 去别处粘贴，应粘不出来
2. 复制 key 后 60 秒内去别处复制普通文本 → 等过 60 秒 → 那段文本应还在

**第二个才是 label 设计的价值所在 —— 这点完全正确，光测第一个等于没测核心逻辑。**

### 逻辑验证（最小复现，`/tmp/repro_clip/ClipLogic.java`）

忠实复刻 `Ui.copyWithAutoClear()` 里的 label 比对：

```
用例①：复制 key，期间没人动剪贴板      → 判定清除: true（应清）✅
用例②：复制 key 后用户复制了别的内容    → 判定清除: false（不该误清）✅
边界：别的内容也用 "cpa" 作 label      → 判定清除: true（会误清，已知边界）
边界：剪贴板已被清空                   → 判定清除: false ✅
RESULT: PASS
```

**复现暴露了一个我该说明的边界**：若别的内容也用 `"cpa"` 作 label，会被误清。
实际影响很小（label 是应用自定义的），但如实记录。

### 真机两用例没做成（原因）

环境里**没有可用的剪贴板读写手段**：

- `cmd clipboard` → `No shell command implementation`
- `dumpsys clipboard` → 无输出
- 自己写 `app_process` 小工具读剪贴板 → **被系统 Killed**
  （拿不到 system context，权限限制）

Android 10+ 对后台读剪贴板有硬限制，这条路在当前设备上走不通。

**所以：标签比对逻辑已用最小复现证过，但"60 秒后真的粘不出来 / 别人内容真的还在"
这两条真机行为，本轮没有取得证据。** 需要用带 GUI 的辅助工具（或有输入法配合）才能验。

## 实测证据

| 项 | 结果 |
|---|---|
| 构建 | 分步构建（aapt2 compile/link → javac → d8 → 打包 → 签名） |
| 签名 | SHA-256 45a99822…3a223db（与历史版本一致） |
| 包信息 | versionCode 535 / versionName 4.24.0 |
| 安装 | pm install -r → Success |
| 配置保留 | md5 装机前后一致 |
| 三协议回归 | OpenAI 200 / Gemini 200 / Anthropic 200 |
| **auto-race 回归** | **200**（新选路可用） |
| 剪贴板逻辑 | 最小复现 PASS（四场景） |

## 产物

- `/workspace/opt513/cpa-gateway.apk`（versionCode 535 / 4.24.0）
- 副本 `/sdcard/Download/cpa/AHYU-4.24.0-com.ahyu.apk`

## 遗留

- **剪贴板真机两用例**：缺工具，未验（逻辑已证）
- **`migratePlaintextIfPossible` 还没接入调用点**：
  目前自愈靠 `seal(JSONObject)` 的自然重试（每次保存都会尝试加密），
  该方法作为显式入口已就绪，但还没在启动流程里调用一次
- auto-race 的**实际延迟排序效果**未做对比实测
  （需要多次调用积累 latencyEwma 才能看出差别）
