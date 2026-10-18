# AHYU 4.20.0 —— 接上 Kilo 零凭证免费池

你给的 `dsh-our-free-model` 里"车道一"，我**实测通过并接进网关**了。

## 我自己 curl 验证的（不是照抄文档）

```
GET  https://api.kilo.ai/api/gateway/models
     → 200 · 486,975 字节 · isFree:true 的 **16 个**
POST https://api.kilo.ai/api/gateway/chat/completions
     → 200 · 11.4s · {"model":"nvidia/nemotron-3-ultra-550b-a55b:free","content":"好的，没问题。"}
流式   → 与文档一致：`: KILO PROCESSING` 注释行当心跳，data: 行是标准 chunk
```

免费模型里能看到 `kilo-auto/free`（自动轮换）、`stepfun/step-5-preview-free`、
`deepseek/deepseek-v4.1-flash`、`moonshotai/kimi-k3` 等。

## 接进网关后实测（走我们自己的 8321）

```
POST /v1/chat/completions  model=kilo-auto/free
→ 200 · 10.5s · {"message":{"content":"收","reasoning_content":"用户想要一个三字回复…"}}
```

## 用法

添加账号 → **Kilo 免费池**（一行新入口：*零凭证 · 一键接入公开免费模型（不用登录）*）
→ 点一下，自动拉免费模型清单并写成渠道。**不需要登录、不需要 Key**。

## 两点如实说明

1. 这是**公开免费池**，上游自己声明 prompt 可能被记录用于改进服务——**别发敏感内容**；
2. 免费清单随上游变（今天 16 个，明天可能不同），我们每次"重新拉取模型"都会跟着更新。

另外那条"车道二"（OpenCode Zen 匿名通道）我**没接**：它要伪造工具指纹四件套 + 地区门 + 会话配额，
而且用的是仓库里自带的**池化公共凭证**——风险和工作量都不划算。要是你特别想要，我再单独做。

## 产物

- `/workspace/opt512/cpa-gateway.apk`（versionCode 520 / 4.20.0）
  - sha256 `54ecd44cb6c29dab81149103e1adc35c7d802ebb5398da8cff217115899775b8`
  - 副本 `/sdcard/Download/cpa/AHYU-4.20.0-com.ahyu.apk`
