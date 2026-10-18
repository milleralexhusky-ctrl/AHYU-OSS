# AHYU 4.21.0 —— 接入 OVHcloud 零凭证免费池

## 做了什么

按 4.20.0 接 Kilo 的同一套做法，新增一个零凭证上游通道。

## 实测证据（全部 curl 直连验证，不是照抄文档）

```
GET  https://oai.endpoints.kepler.ai.cloud.ovh.net/v1/models
     -> 200 · 23 个模型（11 个可对话，其余为 tts / whisper / 向量 / 图像 / 审核）
POST https://oai.endpoints.kepler.ai.cloud.ovh.net/v1/chat/completions
     -> 200 · 1.9s · 返回真实内容与完整 usage
```

速率限制实测：连发 5 次 -> 前 2 次 200，第 3 次起 429。
与官方「2 req/min per IP per model」一致——每个模型独立计数，
11 个可对话模型轮换使用，实际吞吐高于单看 2/min。

## 接进网关后实测（走本机 8321）

应用日志实测记录：

```
检测：探测完成 OVHcloud 免费池 · gpt-oss-120b · 通过 · 1614ms
检测：探测完成 OVHcloud 免费池 · Mistral-7B-Instruct-v0.3 · 通过 · 2545ms
检测：探测完成 OVHcloud 免费池 · Mistral-Nemo-Instruct-2407 · 通过 · 694ms
```

## 11 个可对话模型

gpt-oss-120b / gpt-oss-20b / Meta-Llama-3_3-70B-Instruct /
Qwen3.5-397B-A17B / Qwen3.8-27B / Qwen3.6-27B / Qwen3.5-9B /
Qwen2.5-VL-72B-Instruct（带视觉）/ Mistral-Small-3.2-24B-Instruct-2506 /
Mistral-Nemo-Instruct-2407 / Mistral-7B-Instruct-v0.3

## 改了哪些文件

- 新增 `src/com/cpa/gateway/core/Ovh.java`（照 Kilo 模板，132 行）
- `Config.java` 加 `TYPE_OVH`
- `Upstreams.java` 加两行分发（工厂 + 模型拉取）
- `MainActivity.java` 加 `addOvh()` 方法与「＋ 添加账号」里的入口一行

## 实测证据

| 项 | 结果 |
|---|---|
| 构建 | BUILD_OK，6 步全过 |
| 签名 | SHA-256 45a99822…3a223db，与历史版本一致（可覆盖升级） |
| 包信息 | versionCode 521 / versionName 4.21.0 / com.ahyu |
| 安装 | pm install -r -> Success |
| 配置保留 | gateway.json md5 安装前后一致，10 个渠道零丢失 |
| dex 核对 | 含 Ovh / Ovh$Up / TYPE_OVH / addOvh / 端点地址 / 3 个 lambda |
| 通道实测 | 3 个模型「通过」（1614ms / 2545ms / 694ms） |
| 网关状态 | 运行中，161 个模型 · 9 个账号可用 |

## 用法

添加账号 -> **OVHcloud 免费池**（零凭证 · 匿名层含 gpt-oss-120b / Llama-3.3-70B）
-> 点一下自动拉模型并建渠道。不需要登录、不需要 Key。

## 附带观察

- 免费层是公开匿名端点，上游随其自身政策变化；本通道无需任何凭据。
- 速率限制紧（2 req/min/model），适合当兜底或轮换池成员，不适合当主力。

## 产物

- `/workspace/opt513/cpa-gateway.apk`
  - sha256 d2e6a9cfec835e56d65f079658e3424a（前 32 位）
  - 副本 `/sdcard/Download/cpa/AHYU-4.21.0-com.ahyu.apk`
