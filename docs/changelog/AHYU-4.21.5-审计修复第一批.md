# AHYU 4.21.5 —— 4.21.4 审计问题修复（第一批）

按审计报告的建议，先修小改、高确定性的几条。

## 已修（6 条，逐条对源码核过）

### 1. [中] Server.java:2478 —— writeFallback 分支漏了 race.closed

审计原判：胜出路径加了 `race.closed`，超时无胜出分支没加，迟到的 racer 照样能交第二个响应头。

修法：该分支补上闸门，并与胜出路径保持一致的收尾顺序：

```java
if (winner == null) {
    race.closed = true;              // 先关提交闸门
    RaceCancels.cancelAll(raceBucket);  // 再掐还在跑的路
    RaceCancels.endRace(raceBucket);
    writeFallback(out, r, req, "auto-race：...");
    return;
}
```

顺序有讲究：先 `closed` 再 `cancelAll`，保证迟到的提交被 `DeferredWriter.commit()` 挡掉。

### 2. [中] Server.java:2673 —— resolveVirtual 的 phantom recordOk

审计原判：请求没跑就 `recordOk`，等于传个 `auto` 就能把正在熔断的账号洗白。

修法：删掉那次 `recordOk`。虚拟模型只做"选路"，真实成败由 `serve()` 之后的路径记录。

### 3. [中] Server.java:2730 —— handleGemini 漏接 resolveVirtual

审计原判：Anthropic / Responses 都接了，Gemini 传 auto 还是 404。

修法：`handleGemini` 加入与另两套一致的解析，失败给 503 而不是 404。

**实测**（本机 8321）：
```
POST /v1beta/models/auto:generateContent  → 200，正常返回内容（修复前 404）
POST /v1/messages      model=auto          → 200，解析为 WorkBuddy 1/hy3-x
POST /v1/responses     model=auto          → 200，解析为 WorkBuddy 1/deepseek-v4.1-flash
```
三套协议对 `auto` 的行为现已一致。

### 4. [中] DeepSeekLoginActivity.java:68 —— AUTH_SINK 只设不清

审计原判：Cline/Kimi/Xai 三页都清了，唯独 DeepSeek 没清，静态泄漏。

修法：照 Cline 补 `onDestroy()`——清 `AUTH_SINK`、销毁 WebView、置 `alive=false`。
顺带修了同一处一个更隐蔽的问题：轮询只用 `saved` 作停机条件，
用户中途返回时 `saved` 仍为 false，那条 3 秒轮询会一直跑下去；现在加了 `alive`。

### 5. [低] LoginWebView.java:35 —— lastAuthValue 静态存明文

审计原判：observed 列表脱敏了，这个字段漏了。

修法：保留字段（4 个消费方都要用完整明文提 token），但：
- 新增 `takeAuthValue()`：**取出即清空**，明文只在内存活到被取走；
- 加 10 分钟 TTL，超时自动丢弃；
- 四个消费方（DeepSeek / Kimi / Xai / Cline）全部改用 `takeAuthValue()`。

已核验：全局不再有直读 `lastAuthValue` 的地方。

### 6. [中] Kilo.java / Ovh.java —— 继承 openAiStream 的 200+错误体误分类

审计原判：免费池最常见的死法是"200 里包着额度/限流错误体"，3 次就进熔断误杀。

修法：新增 `throttleKindFromBody()` 分类 + `raiseUpstreamBodyError()`。
错误体命中额度类关键词（insufficient_quota / quota exceeded / budget / 额度 / 余额不足 …）
或限流类（rate limit / too many requests / concurrency / 限流 …）时，
抛 `Throttled`（走 `recordThrottled`，只退避、不计失败），而不是 `IllegalStateException`。
其它错误体保持原行为——该记失败还是记失败，不掩盖真故障。

## 顺带修的（审计提到的 RaceCancels.java:64）

**`endRace` 后迟到的 `bind` 会重建 bucket 且永不删除。**

修法：新增 `DEAD` 集合记录已结束的批次：
- `endRace` 把 bucket 记入 `DEAD`（带 256 上限的清理，正常远达不到）；
- `bind` 发现批次已死就只返回一次性句柄，不建表、不进 MAP。

同时新增 `cancelAll()`（超时无胜出时用：没有赢家，全都要收）。

## 实测证据

| 项 | 结果 |
|---|---|
| 构建 | BUILD_OK，6 步全过 |
| 签名 | SHA-256 45a99822…3a223db（与历史版本一致） |
| 包信息 | versionCode 526 / versionName 4.21.5 |
| 安装 | pm install -r → Success |
| 配置保留 | md5 装机前后一致 |
| 三协议 auto | Gemini / Anthropic / Responses 全部 200 |
| 基础对话 | OVH/gpt-oss-20b 正常返回 |

## 产物

- `/workspace/opt513/cpa-gateway.apk`（versionCode 526 / 4.21.5）
- 副本 `/sdcard/Download/cpa/AHYU-4.21.5-com.ahyu.apk`

## 未修（下一批）

审计列为高/中优先级但本轮未动的：

- **[高] BootReceiver / KeepAliveJobService** —— Android 12+ `startForegroundService` 被拒、
  异常被吃掉，开机自启与保活静默失效（两个文件未动过）
- **[中] chunked 请求体无上限** —— Content-Length 已限 8MB，chunked 分支的 `new byte[size]` 仍可控
- **[中] 其余 8 项** —— WebView 不 destroy（DeepSeek 已修，其余 7 处仍在）、
  LoginWebView 外部协议无确认拉起、providers 无锁迭代、电池白名单后台起 Activity、
  ProviderEditor 静态 Activity、WbTaskChores 无重入 guard、Cline refresh 未接
- **[低]** 流内错误缺 [DONE]、?key= 传密钥、STATS 无淘汰、Limits 非原子、
  Doubao 二维码 token 前 8 位进日志、Secret 回退明文、剪贴板/明文输入框/端口 hint
- **存疑** save() 的 400ms 常量、19 Activity 锁竖屏是掩盖而非修好旋转问题
