# AHYU 4.29.9 — 归类：14018 是「积分用完」，不是限流

4.29.7 把探测原文落盘之后，第一次拿到 WorkBuddy 3 / 4 / Kilo 的**完整**上游回包，
才发现两条归类仍然不对——两者都只能靠原文才能看出来。

## 一、`code 14018` = Credits exhausted（积分/额度用完）

```
upstream HTTP429 {"error":{"data":{"code":14018,
  "msg":"Credits exhausted. Please visit the link below to purchase add-on packs and get more credits:
         https://www.codebuddy.ai/profile/usage ","requestId":"…"}}}
```

它挂着 **HTTP 429**，但 `msg` 说的是"积分用完了，去买加油包"。4.29.7 按状态码优先，
于是显示成"被限流（稍后自动恢复）"——**让人白等**（等多久都不会好，得充值或换号）。

**改法**：新增 `CREDITS_OUT` 判据（credits + exhausted/insufficient/empty/used up/out、
quota exceeded、额度/积分/余额不足…），并且：

- `classify()` 里**排在状态码判断之前** → "额度 / 余额不足（充值或换号，等不会自己好）"；
- `isTransient()` 里同样提前返回 false → 不再当成"暂时失败"，模型会被如实标出来
  （真机：`新隐藏 2` 就是 WB3 的两个样本）。

## 二、`PAID_MODEL_AUTH_REQUIRED` = 免费池点到付费模型

```
upstream HTTP401 {"error":{"code":"PAID_MODEL_AUTH_REQUIRED",
  "message":"You need to sign in to use this model.","error_type":"paid_model_auth_required"}}
```

Kilo 免费池本来就没有凭据，说它"账号要重新登录"是错的（用户根本没得登）。
**改法**：识别 `paid_model` 字样 → "该模型需要登录 / 付费账号"，并且不算暂时失败。

## 三、WorkBuddy 4 的真实原因（本轮**不改代码**，只记录）

```
upstream HTTP400 {"code":11128,"msg":"conversation id missing",
 "displayMsg":{"zh":"请求被拦截，请重新发送", …},
 "displayTips":{"zh":"这次调用没通过渠道校验，可能不是官方客户端…",
                "en":"This call failed channel verification, possibly because it was not sent from an official client."}}
```

上游**明说了**是渠道校验：请求缺 conversation id / 不像官方客户端。网关给 WorkBuddy
构造 body 时从来不写任何 conversation id（探测与真流量同一条链路），所以这条是
**渠道适配缺口**，不是检测问题——要修得按官方客户端补齐会话字段，属于另一件事。

## 四、回归与版本

`Selftest` 新增/更新：积分耗尽（14018+Credits exhausted）→ 额度不足且非暂时失败、
付费模型 → 需要登录 / 付费账号，外加 4.29.7 已有的归类与采样断言。
`versionCode 575 / versionName 4.29.9`。

真机四轮对照（同一批 12 账号）：

| 轮次 | 版本 | 结果 |
| --- | --- | --- |
| 1 | 4.29.7 | 11/13 探测落盘，"结束"行早写；WB3/4/5 显示 0/0；Kilo 401 显示"鉴权失效" |
| 2 | 4.29.7 | 同上（可复现） |
| 3 | 4.29.8 | job 24 / 提交 24；WB3 显示"被限流"（仍不对，但原文到手） |
| 4 | 4.29.9 | job 24 / 提交 24；WB3"额度不足"、WB4"上游拒绝（渠道校验）"、WB5 2/2、Kilo"需要登录 / 付费账号" |
