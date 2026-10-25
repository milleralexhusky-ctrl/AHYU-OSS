# 4.55.4 — Kiro 流事件解析器（渠道第一步）

## 起因

用户提供一份 2api 类 GitHub 仓库清单当参考，问 AHYU（网关1）还缺什么。
逐条对照后，清单里对 AHYU 有价值的只有一处：**Kiro（Amazon Q Developer /
AWS CodeWhisperer）**——官方免费额度、给 Claude 模型，与现有 Kilo/OVH
「零凭证免费池」同类，不是逆向号池。用户决定先做出来看效果。

## 本版范围：只做解析器（第一步）

Kiro 渠道的**最大技术风险点是上游流的解析**（协议与 AHYU 现有任何渠道都不同），
所以先把它独立做出来并钉死，再往上搭渠道。好处是这一步不依赖任何凭据、
可完全自测；即使最终不做 Kiro，这个解析器对将来的 AWS 系渠道也有复用价值。

### 关键发现：不是 SSE，但也不必解二进制帧

上游回 `application/vnd.amazon.eventstream`（AWS EventStream）。原以为是
二进制帧协议（prelude + headers + payload + CRC），要写一套帧解析。
读参考实现后发现：**payload 里的 JSON 是连续可见 UTF-8，中间结构字节不含
`{"content":` 这类起始串**，所以按文本扫起始串 + 括号配对找结尾就够
（`jwadow/kiro-gateway` 的 `AwsEventStreamParser` 也是这么做的，其 tests
里没有任何 CRC/prelude 相关用例）。工作量因此从"重"降到"中"。

### 授权说明（重要）

参考实现 `jwadow/kiro-gateway` 是 **AGPL-3.0**。AHYU 公库不能带 AGPL 代码
（会传染许可），所以只借它的**协议知识**（HTTP 形状、字段名、事件类型），
`KiroStream.java` 是独立写的 Java 实现，代码未抄。

### `KiroStream` 处理的四件事

| 机制 | 为什么必须处理 |
| --- | --- |
| **累计增量** | 上游 `content` 给的是**累计全文**（逐帧变长、且会重发同一帧）。照抄会重影；不做增量每次重发整段。实现：与上一次比较，只吐新增；完全相同丢弃 |
| **工具分片** | `{"name":…}` 起头、`{"input":…}` 续片、`{"stop":true}` 收尾。少一步参数就残缺 |
| **半帧拼接** | 网络切片会把一个 JSON 切成两半。攒 buffer、括号配平后才取走 |
| **字符串内花括号** | `{"content":"他说 {\"a\":1}"}` 里的花括号不参与层级，否则提前收尾切出坏 JSON |

## 验证

- 构建通过，**真机自检 60/60**（新增第 60 项）。
- 自检覆盖 7 组边界：累计增量、工具分片（名/id/参数三样都核）、半帧、
  字符串内花括号、坏事件不毁流、一帧多事件、usage 不混进正文。
- 对齐参考实现 `tests/unit/test_parsers.py` 的断言形态交叉核过
  （`find_matching_brace` 的嵌套/转义/未完成三种用例、去重、usage 值）。

## 未做 / 边界

- **渠道本体未做**：refreshToken 续期、`generateAssistantResponse` 请求构造、
  事件→`Proto.Sink` 映射、登录页、接线，是第二步。**所以这一版用户还看不到、
  也用不上 Kiro**，只是把最难的一块地基打好并验证。
- **没有真机上流验证**：没有 Kiro 的 refreshToken，跑不了真实上游流。
  解析器行为靠自检 + 参考实现测试形态对齐，不是真流实测——如实记录。
- 事件类型里 `context_usage` / `followupPrompt` 当前静默丢弃
  （网关不需要），将来要显示上下文占用再接。

## 产物

- `AHYU-4.55.4.apk`（versionCode 670）
