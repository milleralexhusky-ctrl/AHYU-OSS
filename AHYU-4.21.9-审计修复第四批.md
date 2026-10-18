# AHYU 4.21.9 —— 审计问题修复（第四批）

四件：LoginWebView 外部协议确认、Doubao token 脱敏、?key=、STATS 淘汰。

## ① LoginWebView 外部协议确认（本批唯一真安全边界）

### 根因

```java
Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
activity.startActivity(intent);     // 任何 scheme 都直接拉起，零确认
```

登录页（或它加载的任何第三方资源）塞一条 `intent://...` 带 extras，
就能悄悄拉起外部应用；而 `FLAG_ACTIVITY_NEW_TASK` 让它完全脱离当前任务栈。

### 修法（白名单 + 确认框）

- **白名单直接放行**：`tel` / `sms` / `smsto` / `mailto` / `geo` / `market` / `weixin`
  （这些是登录页的正常跳转，每次弹框会烦到没法用）
- **其余一律弹一次确认**，确认框显示：
  - scheme 名
  - 截断后的 URL（**砍掉 query**——登录回调 URL 里经常直接带 code/token）
  - 一句"只有你认得这个跳转时才继续"
- **弹框失败时宁可不打开**（`catch` 里回调 `onExternal(url, false)`）：
  安全边界不能因为 UI 异常而失效

### 设计取舍

没有做成"全部弹框"或"全部拒绝"：
- 全部弹框 → 点"用微信登录"这种正常操作都要确认一次，体验崩
- 全部拒绝 → weixin / tel 这类必需跳转直接不能用

## ② Doubao 二维码 token 前 8 位进日志

```java
// 改前
Log.i("doubao 二维码已取到（token " + token.substring(0, 8) + "…）");
// 改后
Log.i("doubao 二维码已取到（token 长度 " + token.length() + "）");
```

即使是前 8 位也属于凭据的一部分，与本项目其它日志的脱敏口径统一。

## ③ ?key= 传密钥

核对结论：**日志侧其实是干净的**（路径日志只记 `r.path`，不含 query，key 不会进日志）。

真正的风险在别处：URL 会进浏览器历史 / Referer / 截图 / 复制粘贴，请求头不会。

**处理方式：保留兼容 + 一次性警告。**

删掉会破坏 Gemini 官方调用方式的兼容性；而无提示地留着，用户不知道自己在用不安全姿势。
所以加一条只打一次的 WARN：

```
W CPA : 检测到用 ?key= 传密钥。这比请求头危险（URL 会进浏览器历史 / Referer / 复制粘贴），
        建议改用 Authorization: Bearer 或 x-api-key 头。此提示只打一次。
```

（用 `AtomicBoolean.compareAndSet` 保证只打一次，这个路径可能每次请求都走。）

## ④ STATS 淘汰

### 根因

`Router.STATS` 是 `LinkedHashMap`，key 是"账号/模型"，**只增不减**：

- 11 个账号 × 各自几十个模型 = 几百到上千条
- 每条 `Stat` 带 9 个字段
- 长期运行一直涨

### 修法

容量上限 2048 + **两轮淘汰**：

1. **第一轮只清"完全干净"的条目**（无熔断、无冷却、无探针、无失败计数）
2. 不够再按插入顺序（LinkedHashMap 最老的在前）继续清
3. 一次腾出 1/8（256 条），不每次插入都淘汰

**为什么分两轮**：直接清最老的会把**正在熔断中**的账号状态抹掉，
那等于让刚被熔断的账号立刻重新参与调度——比不淘汰还糟。

所有访问都经 `stat(key)` 一处，读写点集中，改动面小。

### 验证（最小复现，`/tmp/repro519/Evict.java`）

```
=== 填满后插入新条目 ===
  插入 2048 条后: 2048
  再插 500 条后: 2036（应 <= 2048）✅

=== 正在熔断/冷却的条目必须被保住 ===
  10 个熔断中的条目，淘汰后存活: 10/10（应为 10）✅

=== 批量淘汰量正确 ===
  淘汰前 2048 → 淘汰后 1793（净减 256）✅

RESULT: PASS
```

## 实测证据

| 项 | 结果 |
|---|---|
| 构建 | BUILD_OK |
| 签名 | SHA-256 45a99822…3a223db（与历史版本一致） |
| 包信息 | versionCode 530 / versionName 4.21.9 |
| 安装 | pm install -r → Success |
| 配置保留 | md5 装机前后一致 |
| 正常对话 | OVH/gpt-oss-20b → 200（没改坏） |
| ?key= 警告 | 真机日志如期打出，且兼容性保留（仍 200） |
| STATS 淘汰 | 最小复现 PASS（三场景） |

## 产物

- `/workspace/opt513/cpa-gateway.apk`（versionCode 530 / 4.21.9）
- 副本 `/sdcard/Download/cpa/AHYU-4.21.9-com.ahyu.apk`

## 遗留

- 外部协议确认框的**真机点击流程**未实测（需要真去登录页触发一次 intent:// 跳转）
- 审计剩余（影响面小，按约定往后放）：
  Limits 非原子、Secret 回退明文、流内错误缺 [DONE]、剪贴板、明文输入框、端口 hint
- **保活那条尾巴仍待真机确认**（4.21.7 的遗留）：
  重启手机或等一次真实 15 分钟周期，logcat 里找
  `保活任务：检测到网关未运行，重新拉起` 且没有 `ForegroundServiceStartNotAllowedException`
