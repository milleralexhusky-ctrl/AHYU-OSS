# AHYU 4.41.0：入口工具结果 id + 渠道延迟 p95

versionCode 634。

## 一、入口解析回放（自检 25 项）

新增用例覆盖三个入口对**工具结果 id** 的保留：

| 入口 | 断言 |
| --- | --- |
| OpenAI | `tool` 消息 content 非空时，`tool_call_id` 仍在 |
| Anthropic | `tool_result` 的 `tool_use_id` 仍在 |
| Gemini | `functionResponse.id` 仍在 |

前两条是 4.33.4 修过的真实问题（content 非空时 id 被丢，上游报
`Unexpected tool_call_id: 空`）。第三条是这次检查时发现的：

```java
// 旧代码：functionResponse 固定写空 id
cm.add(Proto.Part.toolResult("", name, response));
```

Gemini 新版会带 `functionResponse.id` / `functionCall.id`，现在能读就保留；
没有时仍沿用本地生成的 id，不影响老上游。

## 二、渠道延迟 p95

行为画像（日志页「画像」）新增一列 p95：

```
渠道          样本  p95    可用率  空回  截断  跑题  重发  工具失败
WorkBuddy 1   120   1.8s   98%     0     0     1     1     2%
```

- 只统计有质量标记的样本（与可用率同一分母，避免探活/旧记录混入）；
- 按模型那张表也加了 p95；
- 数据来自 usage.tsv 第 5 列（耗时 ms），报告生成后随 Row 释放。

用途：之前只能看"成功率"，看不出"这个渠道成功但很慢"；现在可以按 p95 做
选路/用户侧解释（例如某个中转站成功率 99% 但 p95 12 秒）。

## 验证

- 构建通过；真机启动自检 **25/25**。
- p95 在现有 usage.tsv 上正常出数（无样本显示 `—`）。
- 签名不变，可覆盖升级。

## 未做

- p95 目前只展示，不参与 Router 打分；是否把 p95 纳入选路需要先看一段时间的
  真实分布，避免又引入一个需要调的权重。
- `Responses` 入口的 canonical 回放还没做（它的转换在 Server 里，不在 Fronts）。
