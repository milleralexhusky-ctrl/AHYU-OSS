# AHYU 4.29.5 — 点名了一个账号没有的模型时，别再发出去

## 一、起因

用户报"gpt6.1 用不了，重连失败两次"。网关用量表里正好有两条对应的记录：

```
01:09:03 | /v1/chat/completions | gpt-6.1 | 渠道=WorkBuddy 3 | 462ms | 成功=1
01:09:03 | /v1/chat/completions | gpt-6.1 | 渠道=WorkBuddy 3 | 110ms | 成功=0
```

`gpt-6.1` **在模型池里根本不存在**（WorkBuddy 提供的是 `gpt-6-sol` / `gpt-6-luna` /
`gpt-5.6-terra` / `gpt-5.6-luna` / `gpt-5.5` / `gpt-5.4`）。但请求还是被原样发给了
WorkBuddy 3，上游回：

```
400 {"code":11102,"msg":"model [gpt-6.1] service info not found",
     "displayMsg":{"zh":"当前模型不可用，请切换模型"}}
```

而这个失败发生在**流式响应头已经写出去之后** —— 客户端拿到的是一个没有任何内容就结束的流，
和真正的网络断开长得一模一样，于是显示"重连失败"。真正的原因（模型名不对）
在客户端那侧完全看不出来。

## 二、根因

`Server.pinnedThenFallbacks()` 里，点名的账号是**无条件**进候选的：

```java
List<Config.Provider> out = new ArrayList<>();
out.add(pinned);              // ← 从没检查过 pinned 到底有没有这个模型
```

这条路的语义是"点名那个账号排第一"（优先而非锁定）。但"优先"的前提是它**有**这个模型；
它没有的时候，把它排第一就等于保证这次请求失败一次。

## 三、改法

```java
boolean pinnedHas = bare != null && !bare.isEmpty() && pinned.matches(bare);
if (bare == null || bare.isEmpty() || pinnedHas) out.add(pinned);
```

带 `*` 通配的账号不受影响（`matches()` 认通配），那种账号本来就接受任意模型名。
如果点名的账号没有、别的账号也没有，`pickAll()` 返回空 —— 调用方会在写出响应头**之前**
回一个干净的 404。顺带把这条 404 的文案从"陈述事实"改成"告诉你怎么办"：

```
没有账号提供模型 WB3/gpt-6.1（模型名可能拼错，或本地模型目录已过期）；
可在应用内点「刷新模型目录」后重试，或改用 auto
```

## 四、验证（真机，同一把客户端 Key）

```
修复前  WB3/gpt-6.1  流式 : HTTP 200，462ms，流里只有一句 upstream_error —— 客户端显示"重连失败"
修复后  WB3/gpt-6.1  流式 : HTTP 404，13ms，{"error":{"message":"没有账号提供模型 WB3/gpt-6.1 …"}}
修复后  gpt-6.1      流式 : HTTP 404，13ms，同上
回归    WB3/gpt-6-sol    : HTTP 200，content="可用"   ← 正常的点名不受影响
```

两侧证据：

- **网关日志**：`点名的账号 WorkBuddy 3 没有模型 gpt-6.1（它的模型目录里没有这一条），
  本次不把它算作候选` + `模型 gpt-6.1 没有任何账号提供：直接回 404，不向上游发起注定失败的请求`；
- **用量表**：两条 404 **没有**产生任何新记录（行数只 +1，就是那条正常的回归请求），
  即零上游往返。

## 五、给用户的结论

`gpt6.1` / `gpt-6.1` 这个模型不存在，所以用不了。WorkBuddy 的 GPT 系列是：

```
WB3/gpt-6-sol   WB4/gpt-6-sol     WB3/gpt-6-luna  WB4/gpt-6-luna
WB3/gpt-5.6-terra  WB3/gpt-5.6-luna  WB3/gpt-5.5  WB3/gpt-5.4（WB4 同）
```

`WB3/gpt-6-sol` 实测正常（纯文本与带工具调用都是 200，工具参数、`finish_reason: tool_calls`、
usage、`[DONE]` 全部正确）。名字不确定时用 `auto`，它会自己挑能用的。
