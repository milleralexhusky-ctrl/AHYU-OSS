# AHYU 4.21.3 —— 修好 Trae 通道（0 模型）+ 积分刷新

## 一、Trae 一直是 0 个模型（真 bug，已修）

### 根因

Trae 的 SOLO 通道**没有 OpenAI 风格的 `/models` 接口**，模型目录来自：

```
GET {AGENT_HOST}/api/ide/v1/get_detail_param?function=solo_work_lite
```

返回体里的 `config_info_list` 才是可用的 `config_name` 清单。

而原来的代码是**直接返回空列表**：

```java
if (Config.TYPE_TRAE.equals(p.type)) {
    // the SOLO channel exposes a fixed config_name catalogue rather than a listing endpoint
    return new ArrayList<>(p.models);   // p.models 从来没被填充过 → 永远 0
}
```

所以 Trae 账号登录也白登：凭据在、通道在，但没有一个模型可选。
这不是登录过期，**重新登录没用**——`registerProvider` 里那句注释写得很直白：
「不塞默认清单：拉不到就空着，别把猜测当事实」。

### 修复

新增 `Trae.fetchModels(Config.Provider)`，用 SOLO 的头部（含 `X-Ide-Version` 指纹）
调 `get_detail_param`，解析 `config_info_list`。`Upstreams` 的分发改成先调它、失败才退回旧清单。

### 实测（走本机 8321）

```
Trae 1 / Trae 2 各 46 个模型
Trae1/DeepSeek-V4-Pro  → 200 · 1.5s · "收到"（带 reasoning_content）
Trae2/kimi-k3          → 200 · "好的！请问有什么我可以帮..."
Trae1/glm-5.3          → 200 · "你好！看起来你发送了..."
Trae2/minimax-m3       → 200 · "您好！请问有什么我..."
```

模型总数 161 → 249。

## 二、积分显示（不是 bug，是查询没成功）

界面逻辑是对的，只有 `credits >= 0` 才显示：

```java
if (p.credits >= 0) stats.addView(Ui.stat(a, "积分", String.valueOf((long) p.credits)));
```

Trae 显示不出来，是因为实时查询返回 -1。**积分值本身没丢**——它缓存在配置里，
下次查询成功就回来。实测已恢复：`Trae 1 · 4762.71`。

### 两个改进

1. **失败不再抹零**：新增 `refreshCreditsQuietly()`，模型刷新时顺带查积分；
   查询失败**保留原值**，不再因为一次限流把积分覆盖成 -1。
2. **启动自动同步**：`GatewayService` 启动后延迟 3 秒、后台刷新一次积分，
   用户开网关就自动更新，不用手动点。

## 三、顺带说明：WorkBuddy 1 的 400 错误不用修

```
HTTP 400 {"code":11103,"msg":"Backend [hunyuan-stream] is not supported"}
```

这是上游服务端下线了某个模型，**网关已经自动处理**：`badModels` 里记了 14 个
失效模型，路由时跳过；`failStreak=0` 说明没有持续故障。手动去改反而会跟这个机制打架。

## 四、改动文件

- 新增 `Trae.fetchModels()` + `Upstreams` 分发
- `Server.refreshCreditsQuietly()`（模型刷新顺带更新积分，失败保留原值）
- `GatewayService.refreshCreditsInBackground()`（启动后自动同步一次）

## 五、实测证据

| 项 | 结果 |
|---|---|
| 构建 | BUILD_OK，6 步全过 |
| 签名 | SHA-256 45a99822…3a223db（与历史版本一致，可覆盖升级） |
| 包信息 | versionCode 524 / versionName 4.21.3 / com.ahyu |
| 安装 | pm install -r → Success |
| 配置保留 | 装机前后 md5 一致，账号与模型零丢失 |
| Trae 模型 | 0 → 46（两个账号各 46） |
| Trae 对话 | 4 个模型实测全部 200 |
| Trae 积分 | 恢复 4762.71 |

## 六、产物

- `/workspace/opt513/cpa-gateway.apk`（versionCode 524 / 4.21.3）
- 副本 `/sdcard/Download/cpa/AHYU-4.21.3-com.ahyu.apk`

## 七、遗留

- Trae 2 的积分仍是 -1（上游限流，两个账号并发查时后者常被拒）；
  已有"失败保留原值"保护，下次刷新会补上。
- Trae 的 46 个模型里有 `custom_model_*` 系列占位名，实际不可用，未做过滤。
