# AHYU 4.29.6 — 用量表的「成功」列不再是乐观值

## 一、起因

排查"gpt6.1 用不了"时，用量表里同一秒的两条记录对不上：

```
01:09:03 | gpt-6.1 | 渠道=WorkBuddy 3 | 462ms  | 成功=1
01:09:03 | gpt-6.1 | 渠道=WorkBuddy 3 | 110ms  | 成功=0
```

这两条其实**都是失败的**（上游 400 `model [gpt-6.1] service info not found`，
客户端连报两次"重连失败"）。也就是说这张表的"成功"列并不等于"客户端拿到了答案"。

## 二、根因：成功判据只看"代码有没有抛异常"

`handle()` 里原来的算法是：

```java
boolean reqOk = true;
try { route(r, out, s); }
catch (Throwable routeError) { reqOk = false; ... }   // ← 只有这里才会置 false
...
boolean usageOk = reqOk && !conn.apiProvider.isEmpty();
```

问题在于**流式请求的失败根本不走异常路径**。流式一旦写出响应头（HTTP 200 +
`Transfer-Encoding: chunked`）就无法再用状态码报错，只能把错误当 SSE 事件写进去：

| 场景 | HTTP | 客户端看到 | 旧判据 |
| --- | --- | --- | --- |
| 全部候选失败（流已提交） | 200 | `data: {"error":…}` + `[DONE]` | 成功 ✗ |
| 虚拟模型全部候选失败 → 兜底话术 | 200 | 一段固定的告警文本 | 成功 ✗ |
| 上游 180 秒没出字（心跳看门狗） | 200 | `upstream_timeout` 事件 | 成功 ✗ |
| 竞速胜出方中途失败 | 200 | 流内 `error` 事件 | 成功 ✗ |
| Anthropic / Gemini / Responses 流内 error 事件 | 200 | 各自协议的错误事件 | 成功 ✗ |
| 非流式 4xx（走异常） | 4xx | JSON 错误 | 失败 ✓ |

只有最后一行是对的。前面几行全都记成"成功"，于是界面上的成功率、平均耗时、
"哪个渠道在跑"都偏乐观——而这张表正是用来判断"该不该换账号"的。

## 三、改法

加一个**"本次响应已向客户端报告失败"**的标记，跟着连接走（`Conn.responseFailed`），
在每个"错误已经发出去"的出口打上：

- `writeRaw(status >= 400)` —— 覆盖所有直接写 JSON 错误的路径；
- 流式全部候选失败后写 `data: {"error":…}` 的那一处；
- `writeFallback()` 返回兜底话术时（HTTP 200，但不是真答案）；
- 心跳看门狗的 `upstream_timeout`；
- `failResponse()` 的流内错误分支；
- 竞速胜出方已提交后失败 / 胜出方 180 秒未收尾；
- Anthropic / Gemini / Responses 三个协议各自的流内 `error` 事件。

最终判据收敛成一行**可测的纯函数**：

```java
static boolean usageSuccess(boolean routeOk, boolean hadProvider, boolean responseFailed) {
    return routeOk && hadProvider && !responseFailed;
}
```

两处容易做错、特意保留的地方：

1. **同一连接上的失败不能带到下一次请求**：keep-alive 连接里一次失败接着一次成功是常态，
   所以每轮请求结束前把标记复位。
2. **中途失败但换号成功的不算失败**：failover 只在"还没写出去"时发生，那时标记还没被打上，
   所以正常交付的请求仍然是成功——这正是 failover 的意义。

另外 `Router.recordOk(winner, total)`（竞速）也一并加了条件：**胜出只代表"最先出内容"**，
不代表完整响应成功交付。一个答到一半断掉的流，不该把那个账号洗白成健康。

## 四、验证

**内置自检 16 项**（新增「用量成功只认正常交付的响应」），真机通过。断言四条边界：
流内出错不算成功、处理抛异常不算成功、没找到上游不算成功、正常响应必须算成功。

**真机前后对比**（同一设备、同一 Key、同类失败流）：

```
修复前  01:09:03 | gpt-6.1  | WorkBuddy 3 |  462ms | 成功=1   ← 上游 400，客户端报错
修复后  01:38:51 | gpt-6-sol| WorkBuddy 4 | 1330ms | 成功=0   ← 同类失败流，已正确记失败
```

同批次正常完成的请求仍是 `成功=1`（`… | gpt-6-sol | WorkBuddy 4 | 86458ms | 1 | 300614 | 105`），
没有把成功误判成失败。

## 五、残余（没动）

- **客户端主动断开**（`ClientGone`）仍记成功：上游确实把内容写完了，只是客户端先走了，
  算不算成功是口径问题，不是错。需要的话可以单独加一列。
- 竞速的"胜出但未收尾"现在会记失败，但它**没有**被记进 `Router` 的成功率——两边口径
  目前一致（都不算成功），以后若要区分"最快"和"完整"，得再加一个维度。
