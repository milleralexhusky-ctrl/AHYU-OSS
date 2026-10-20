# AHYU 4.39.0：流式回放测试 + 一键诊断包

versionCode 632。这一版不加渠道，补的是**质量保障**和**排查效率**。

## 一、流式解析回放测试

以前改协议只能"再发一次真实请求看看"：烧额度、不可重复、失败时也说不清是
哪一层错了。现在 `Selftest` 里新增一条：

```
流式解析回放（OpenAI/Anthropic/Gemini）
```

做法：

- 在 `127.0.0.1` 上起一个只服务一次的假上游（`ServerSocket`），返回固定的 SSE 帧；
- 把这段流交给**真实的解析函数**：`Upstreams.openAiStream`、
  `Upstreams.anthropicStream`、`Upstreams.GeminiUp.chat`；
- 断言正文拼接、工具名/参数、`responseId`、结束原因、输入/输出 token 全部正确。

覆盖的帧：

| 协议 | 内容 |
| --- | --- |
| OpenAI | 两段正文 + 分片 `tool_calls` + `finish_reason=tool_calls` + usage |
| Anthropic | `message_start.message.id` + `text_delta` + `message_delta` + usage |
| Gemini | 两个 `responseId` 块 + 正文 + `finishReason=STOP` + usage |

不碰外网，不依赖账号，启动自检里直接跑。自检从 22 项增加到 **23 项**。

## 二、一键诊断包

设置 → 数据 →「导出诊断包（排查用）」，选一个位置后生成 zip，并直接拉起系统分享。
包内：

```
README.txt               里面有什么、隐私提醒
summary.txt              版本 / 设备 / Android / 网关状态 / 账号与模型数 / 内存 / 线程
config-redacted.json     脱敏配置（apiKey、credentials、Key 别名都替换为 ***）
logs.txt                 内存日志最近 500 条
connection.log.tail.txt  落盘日志最后 96KB
rawcap.tail.txt          可疑响应留样最后 16KB（可能含回答内容，有显式提醒）
usage-report.txt         用量、行为画像、最近请求对账
selftest.txt             23 项自检结果
```

脱敏在 `Diagnostics.redactedConfig()` 里做：只保留长度信息（`*** (42 chars)`），
不写任何密钥/凭据明文；不包含 keystore、备份密码、GitHub 令牌。
`rawcap` 尾部可能含上游回答内容，README 里写明了"发送前自行检查"。

调试包还提供 `GET /v1/debug/diagnostics`，可以直接用 curl 取回 zip
（发布包返回 404），方便自动化排查。

## 验证

- 构建通过；启动自检 **23/23**（新增回放项在真机通过）。
- 诊断包在设置页可导出并分享；配置字段已脱敏。
- 签名不变，可覆盖升级 4.38.x。

## 未做

- 回放目前只覆盖三种流式解析器；`Fronts` 的入口解析、`Server` 的 HTTP/chunked
  解析还没有回放用例。下一步可以把请求侧也做成"固定字节 → 断言 canonical 请求"。
- 诊断包不包含 `usage.tsv` 原文件（只放聚合报告），避免把完整请求特征带出去。
