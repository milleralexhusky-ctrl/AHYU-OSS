# AHYU 4.33.1–4.33.4：原始响应留样 + Trae 工具调用链修复

versionCode 615–618。

## 缘起

4.33.0 把日志落盘后，第一次看到 Trae 通道自己打的警告：

```
[WARN] trae 本轮没有产生任何内容，原始流可能换了结构
```

但这句话只有结论，看不到它到底回了什么。于是加"原始响应留样"，
目标就是那句话说的：**照着它的结构来**。

## 4.33.1 原始响应留样（RawCap）

新增 `RawCap.note()`，只在可疑请求上留样（空回复 / 正文像调用 / 上游报错），
正常请求不留，避免一天几十 MB。单条最多 8KB，写 `files/rawcap.log`，
超 2MB 重置。

Trae 通道额外留**原始 SSE 帧**（`LAST_TRACE` ThreadLocal），
因为它的错误是放在 `event:error` 帧里的，不抓帧就永远看不到。

## 留样立刻给出了答案：三个连续的真实错误

改完之后同一个测试请求连续暴露了三个上游错误，每个都写清了要求：

**第一个（4.33.1 留样捕获）**

```
The parameter `messages.tool_calls.type` specified in the request are not valid:
invalid value: ``, value must be `function`
```

→ `tool_calls` 里的 `type` 没写，上游按空值校验，整个请求被拒（4027）。

**第二个（4.33.2 修完后）**

```
An assistant message with 'tool_calls' must be followed by tool messages
responding to each 'tool_call_id'. Unexpected tool_call_id:
```

→ Trae 的 `message()` 从不写 `tool_call_id`，tool 消息无法和调用配对。

**第三个（4.33.3 修完后仍然报同一个错）**

根因更深：`Fronts.parse` 里

```java
if ("tool".equals(role) && cm.parts.isEmpty()) {
    cm.add(Proto.Part.toolResult(...));
}
```

只在 `parts` 为空时才补 `toolResult`。**content 非空时（最正常的情况）
`tool_call_id` 直接丢了**。这是所有渠道共有的 bug，只是别家容忍了它。

## 改动

| 版本 | 改动 |
|---|---|
| 4.33.1 | 新增 `RawCap`，可疑请求留样；Trae 额外留原始 SSE 帧 |
| 4.33.2 | Trae `tool_calls` 补 `type: "function"` |
| 4.33.3 | Trae tool 消息补 `tool_call_id` / `name` |
| 4.33.4 | `Fronts.parse` 的 tool 消息无条件保留 `tool_call_id`（**所有渠道受益**） |

## 验证（真机 / com.ahyu）

| 场景 | 修复前 | 修复后 |
|---|---|---|
| Trae 带工具历史 | 空回复、prompt_tokens=0 | 正常回答 |
| Trae 带 tools + 工具历史 | 空回复 | 返回结构化 tool_calls |
| WB1 带 tools + 工具历史 | 正常 | 正常（未受影响） |

## 这一轮的方法论意义

之前的做法是"猜—试—再猜"，几轮下来把 Trae 判成了"渠道不支持工具"。
留样上线后，**上游自己把要求一条条写了出来**，三个错误十五分钟内定位并修完。

这正是"先观测、再照着结构改"的价值：不靠外部资料，不靠猜，
让被测对象自己交代它的契约。
