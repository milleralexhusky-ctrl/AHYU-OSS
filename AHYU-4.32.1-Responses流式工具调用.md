# AHYU 4.32.1：/v1/responses 流式工具调用修复

版本 4.32.1 / versionCode 611。

## 现象

用户反馈：在客户端（Codex/DSH 一类，走 Responses 协议）里，DeepSeek 模型回答很糟糕，
界面上出现一大段 `<| | DSML | | calls><| | DSML | | invoke name="bash">…` 的原始标记。

## 排查结论

**不是模型的问题，也不是 Trae 上游的问题，是我们自己的转译层有两个 bug。**

排查时先怀疑 Trae 通道，但用同一模型、同一工具直连 `/v1/chat/completions` 测试，
返回完全正常的结构化 tool_calls。对比 `usage.tsv` 发现差异只在接口：

| 接口 | 记录 |
|---|---|
| /v1/chat/completions | 工具=1、结束=tool_calls |
| /v1/responses | 工具=0、结束=stop、字符=1080 |

## 两个根因

### 一、流式 sink 的 toolCall() 一个事件都不发

`Server.java` 的 `handleResponses` 流式分支里，`toolCall()` 原始实现只有一行：

```java
public void toolCall(String callId, String name, String argsJson) {
    c.toolCall(callId, name, argsJson);   // 只进 Collector
}
```

而 `text()` 是逐字发 `response.output_text.delta` 的。于是工具调用被塞进内存后
再无动作，客户端在流式 Responses 下**永远收不到 function_call**。
客户端拿不到可执行调用，只能把模型吐出的原始标记当文本渲染 —— 这就是界面上那堆 DSML。

对比：非流式分支是对的，它把 `c.toolCalls` 写成 `function_call` 放进 `output`。
所以这个 bug 只在流式出现，而之前所有测试走的都是 chat 接口，一直没暴露。

### 二、工具定义没转换，上游收到的是"没有工具"

```java
if ("function".equals(Proto.optString(t, "type", "function"))) {
    converted.put(t);   // 原样透传
}
```

Responses 原生格式工具名在顶层：
`{"type":"function","name":"bash","parameters":{…}}`
Chat Completions 格式多一层：
`{"type":"function","function":{"name":"bash","parameters":{…}}}`

原样透传 Responses 格式时，上游收到的工具没有 `function` 字段，等于没定义工具，
模型于是回答"我没有可用的 bash 工具"。

## 改动

### 1. 流式 toolCall() 补全事件序列

按 Responses 协议发四个事件：
`output_item.added`(type=function_call) → `function_call_arguments.delta`
→ `function_call_arguments.done` → `output_item.done`

上游给的是完整参数（不是分片），一次发完整 delta，协议允许。

参数统一从 `Collector` 取修好的那份（`ToolFix` 修不好会丢弃），保证两边一致。

### 2. output_index 改成计数器

以前全部硬编码 0。正文消息固定占 0，每个 function_call 从 1 递增。
协议里 `output_index` 是 item 标识，必须唯一，否则客户端会把调用当成
"消息的第 N 个 content part"。

### 3. response.completed 补上 output 数组

以前 `resp` 只有 id/object/status/model/usage，没有 `output`。
忽略增量事件、只看最终对象的客户端（不少 SDK 的兜底路径）因此完全看不到工具调用。
现在把正文消息项和所有 function_call 都列进去。

同时补发正文 item 的 `output_item.done`，否则客户端一直认为它 in_progress。

### 4. 工具定义两种格式都认

顶层写法包进 `function`，已嵌套的原样保留。

## 验证（真机 4.32.1 / 611，com.ahyu 端口 8321）

| 场景 | 结果 |
|---|---|
| 流式 Responses + Responses 原生工具格式 | 事件序列完整，completed.output 含 message 和 function_call 两项，index 0/1 |
| 流式 Responses 不带工具 | 6 类事件正常，无多余事件 |
| 非流式 Responses 带工具 | output 含 function_call，arguments 正确 |
| Chat Completions 带工具（回归） | 结构化 tool_calls，finish_reason=tool_calls |
| Chat Completions 纯文本（回归） | 正常 |

`usage.tsv` 新信号同步正确：纯工具调用记 `字符=0 工具=1 结束=tool_calls`，
带解释的记 `字符=9 工具=1`。

## 未做

- DSML 原始标记本身没有专门解析。它只在"上游把特殊 token 漏成正文"时出现，
  修好工具调用链路后不应再触发；如果仍出现，说明上游确实没走结构化调用，
  届时再考虑用 ToolCallText 兜底。
- 流式只支持上游一次给全参数。若将来遇到上游分片给参数，需要改成缓冲累加。
