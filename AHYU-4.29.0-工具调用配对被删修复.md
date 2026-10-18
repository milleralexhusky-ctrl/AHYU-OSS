# AHYU 4.29.0 —— 工具调用配对被误删（"降智"/死循环的真正根因）

versionCode 567 / versionName 4.29.0
签名 SHA-256 45a99822b5bca88892d6b58b6f758aa246ac3d481e490d85ba5db749a3a223db

## 现象

用 WorkBuddy 通道时模型表现异常：
- 反复"我要调工具了"却看不到任何工具返回
- 同一个调用一遍遍重复，历史消息无限膨胀
- 表现为"逻辑变弱、复杂指令执行不了"（用户描述为"降智"）
- 用户只说了两个字（"自检"），客户端连续跑了 26 次、5 分钟

**同类其他模型正常，只有 WorkBuddy 通道出问题。**

## 真正根因

客户端（代鱼）执行完工具后，返回的 `tool` 消息里
**`tool_call_id` 是空字符串**：

```
assistant.tool_calls: id = "call_00_GIgyc4IOVqiyhalxdCPE7812"
                           "call_01_nMeA3p7FErYbNupcSOie9537"
tool.tool_call_id:        ""                      ← 全空
```

结果是**按调用顺序、紧挨着**放在对应 assistant 后面的，但 id 是空的。

`Upstreams.normalizeToolPairs()` 见到空 id 就直接判"孤立"并删除：

```java
if (!id.isEmpty()) { ...往前找匹配... }   // id 为空时连找都不找
if (!matched) msgs.remove(i);              // → 直接删
```

### 用整理前后的序列对比拿到铁证

在 `normalizeToolPairs` **前后各打一次消息序列形状**（s=system u=user
A=assistant t=tool，(n)=该条带几个 tool_calls）：

```
整理前：s,u,A(4),t,t,t,t,A(4),t,t,t,t,A(4),t,t,t,t,A(5),t,t,t,t,t,...
整理后：s,u,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A,A
                ↑ 客户端发来的所有 tool 结果被整批删光
```

**客户端把结果发得完完整整**，是网关把它们删了。

## 走过的三条错路（都失败）

| 尝试 | 结果 | 原因 |
| --- | --- | --- |
| 删孤立 tool（原版） | 反复调用 | 模型看不到结果，以为没执行 |
| 补占位结果 | 没有回复 | 用错 `JSONArray.put(int,Object)`——它是**替换**不是插入，把后面消息覆盖了 |
| 不删，原样透传 | 400 断连 | 上游 `{"code":11148,"tool calls and tool results do not match"}` |

**三条路都坏，因为真正的问题是"id 对不上"——补和删都治不了。**

## 修法：按顺序回填空 tool_call_id

在 `normalizeToolPairs` 开头新增"第 0 遍"：把**原本为空**的 `tool_call_id`
按顺序回填成对应 assistant 的 `tool_calls[i].id`。

```java
for (每个 assistant 且带 tool_calls) {
    int next = 0;
    for (它后面的消息) {
        if (assistant) break;                    // 进入下一轮
        if (不是 tool) continue;                 // user 等跳过
        if (tool_call_id 非空) continue;          // 已有 id，不动
        t.put("tool_call_id", calls[next++].id); // 按顺序回填
    }
}
```

**只回填空的，不覆盖客户端已给的 id。**

同时在 Linux 侧用 Python 模拟验证过：3 个空 id 全部正确回填成
`call_00_*` / `call_01_*` / `call_02_*`。

### 为什么"按顺序"是对的

客户端的 tool 结果是**紧挨着、按调用顺序**排列的（`A(n)` 后面紧跟 n 个 `t`），
顺序信息本身就足以还原配对关系。

## 验证结果

| 指标 | 修复前 | 修复后 |
| --- | --- | --- |
| `11148`（id 不匹配） | 反复报 | **0 次** |
| 回填次数 | — | 42 次（全部成功）|
| 用户反馈 | 一直重复 | **修好了** |

## 遗留的问题（不是本网关能修的）

**客户端（代鱼）应该自己填好 `tool_call_id`。** 现在靠网关按顺序回填来兜底，
只要客户端坚持"结果顺序与调用顺序一致"，兜底就是可靠的；但如果哪天它把
顺序打乱，回填就会配错。

**建议在客户端侧也修**：执行完工具后，把 `tool_call_id` 填成对应
`tool_calls[i].id` 的原值。

## 这次排查的教训（写给自己）

1. **不要在"被处理过的数据"上做判断。** 我最初只在 `normalizeToolPairs`
   **之后**打序列，看到"没有 t"就断言"客户端没发结果"，方向完全错了。
   把断点挪到**处理之前**，一眼就看到真相。
2. **改了之后要立刻验证自己的改动是否按预期生效**（整理前后序列对比），
   而不是只看"有没有报错"。
3. `org.json` 的 `JSONArray.put(int, Object)` 是**替换指定下标**，不是插入。
   需要插入时必须重建数组。
