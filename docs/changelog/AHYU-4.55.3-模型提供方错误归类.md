# 4.55.3 — 11133「模型提供方错误」不再中止整轮换模型

## 起因

排查 Dsh / Claude Code「这边好了那边用不了」的过程中，在网关日志里抓到一类
被误分类的上游失败：

```
W CPA: 失败属于请求问题，不标记账号不可用：WorkBuddy 1 · upstream HTTP 400
  {"code":11133,"msg":"the request parameters were rejected by the model provider",
   "extError":{"code":"1210",...,"type":"invalid_request_error","StatusCode":400},
   "displayMsg":{"zh":"模型提供方错误，请稍后重试"}}
W CPA: 纯调用失败属于模型/请求问题，停止换号重试：WorkBuddy 1/glm-5.1
```

注意它是 **HTTP 400 + `invalid_request_error`** 的形状，于是被
`FailScope` 的 `InvalidRequest` 分支吞成 `REQUEST`。而 `REQUEST` 的语义是
"换号换模型都没用，直接告诉客户端"——后果：

- **点名模式**：第一个候选撞上它就 `stopDirect()` 直接失败，
  哪怕同账号的别的账号/别的模型完全可用；
- **auto 轮换模式**：`stopRotation()` **中止整轮选路**——
  一个上游坏模型陪葬整个候选池，本来能用的候选一个都轮不到。

## 改法

`FailScope.of()` 在 `InvalidRequest` 分支**之前**加一条判定，把这类话术
归到 `MODEL_BAD`（跳过该模型、继续换别的模型）：

```java
if (msg.contains("rejected by the model provider")
        || msg.contains("模型提供方错误") || msg.contains("模型无法处理")
        || (msg.contains("11133") && msg.contains("model"))) {
    return MODEL_BAD;
}
```

排在 `InvalidRequest` 之前是关键：它就是以 400 + invalid_request_error 的
形状进来的，晚一步就被吞成 `REQUEST`。

## 真机证据（支持「换模型能过」）

同一账号、同一份最小请求体，只换模型名：

| 模型 | 结果 |
| --- | --- |
| `WorkBuddy 1/hy3` | 可用 ✓ |
| `WorkBuddy 1/glm-5.1` | 失败 |
| `WorkBuddy 1/kimi-k3.1-pro` | 失败 |

即"坏的是模型这一维，不是账号"——正是 `MODEL_BAD` 的定义。
（补充：当时 glm-5.1 上同时有额度耗尽 14018 在竞争，所以"失败"的具体码
在 11133 与 14018 之间摇摆；但**换模型就变**这一点稳定成立。）

## 验证

- 真机自检 **59/59**（新增断言：11133 → MODEL_BAD、
  `skipModelInRotation()` 为真、`stopRotation()` 为假）。
- `auto` 请求真机出词正常（`成功 ✓ OK`）。

## 未做 / 边界（重要）

- **没有端到端复现"修复前中止整轮"的原始场景**。原因是复现需要
  "首个候选恰好只回 11133、且后续候选正常"，而当时两个 WorkBuddy 账号在
  多个模型上都有额度竞争（14018），11133 被 14018 抢先，构造不出来。
  所以本版是**代码逻辑推断 + 单元断言**，不是真机端到端复现——
  如实记录，别把它当成"实测确认"。
- 归类仍以**上游错误文本**为判据（和 11128 渠道校验同样的做法）。
  上游改文案就会失效；真失效的表现是整轮又被中止，日志里
  「停止换号重试 / 中止整轮」再次出现。
- 只动 WorkBuddy 语义相关的归类，不改选路、不改重试预算。

## 产物

- `AHYU-4.55.3.apk`（versionCode 669）
