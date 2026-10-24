# 4.55.2 — Dsh 默认模型名 404：厂商命名空间与 [1m] 后缀

## 起因

用户发来的 Dsh 截图：测试 Anthropic 协议报

```json
{"type":"error","error":{"type":"not_found_error",
 "message":"No handler for POST /v1/v1/messages","request_id":"req_..."}}
```

「还是不行」——指的是想让 Dsh 走 AHYU 的 Anthropic 入口，一直失败。

## 排查：错误不是 AHYU 报的，但病根在我们这

三步定位：

1. **措辞比对**：`No handler for POST` 这个串在 AHYU 全部 git 历史里
   （`git log -S`）从未出现过；AHYU 的 404 形状是
   `{"error":{"type":"api_error","message":"..."}}`，没有 `request_id`。
   截图里的 `not_found_error` + `req_` 是 **Dsh 自己转发层的错误形状**
   ——那是 Dsh 打不通上游后自己吐的。
2. **路径归一没坏**：本机实测 `POST /v1/v1/messages` 与 `/v1/messages`
   行为一致（`normalizePath` 的重复前缀容错在），4.53.1 的那条老路是通的。
   Dsh 错误里的路径只是它自己拼出来的样子，不是病因。
3. **拿截图的 exact body 复现，真凶现形**：

   ```
   model = "anthropic/claude-opus-4-6[1m]"
   → AHYU 404：没有账号提供模型 anthropic/claude-opus-4-6[1m]
   ```

   两个入口（OpenAI / Anthropic）、包括点名 kimi / deepseek / glm 在内，
   只要模型名用 Dsh 默认这个形状，全部死在这一步。
   **也就是说 AHYU 的 Anthropic 入口对 Dsh 用户从来就没通过**——
   与风控无关，与 WorkBuddy 今晚的新规也无关。

## 根因：`compatModel` 把两种 slash 混为一谈

4.53.3 加模型名兼容时立了条红线：**带前缀的点名（`账号/模型`）绝不改写**，
用户真挂了同名 Claude 中转时抢走它等于废掉他的账号。实现是

```java
if (slash > 0) return model;   // 见名字里有斜杠就当点名
```

但 `slash > 0` 其实是两种完全不同的形状：

| 形状 | 例子 | 正确处置 |
| --- | --- | --- |
| 真点名本地账号 | `WB1/claude-sonnet-4-5` | 照点名走（红线） |
| **聚合器厂商命名空间** | `anthropic/claude-opus-4-6[1m]`（Dsh/OpenRouter 默认发这种） | 不是点名，本地根本没有叫 `anthropic` 的账号 |

旧逻辑对第二种也放行原样路由 → 没有任何渠道提供它 → 404。
雪上加霜的是 `[1m]`：Claude Code 系的**上下文模式后缀**，
不是模型名的一部分，带着它连剥前缀后的名字都查不到。

## 修复

`Server.compatModel`：

1. **斜杠前缀先查事实再定性**——新增 `isVendorNamespace`：前缀对得上
   短标签 / 渠道名 / 渠道 id（复用 `pick` / `byShortLabel` 的同一套口径），
   就是真点名，红线不动；对不上（`anthropic`、`openrouter` 这类），
   是命名空间，剥掉继续走 Anthropic 形状判断。
   为什么不用关键词名单：名单永远缺新面孔，而「本地有没有这个账号」
   是可判定的事实。误判方向也不对称，所以必须核事实而不是猜。
2. **剥 `[...]` 后缀**（在 `claude-opus-4-6[1m]` 这种形态上，
   后缀遮蔽了 `low.startsWith("claude-")` 的判断）。
3. 剥完之后一切照旧：本地目录真有同名模型 → 照旧；没有 → 落到 `auto`
   虚拟模型（自动选路、可换号换模型）。

自检第 98 项「Claude 模型名兼容」加厚：Dsh 实弹形状必须落虚拟模型、
`claude-*[1m]` 无斜杠形态也要映射、`WorkBuddy 1/claude-*` 点名红线
原断言保持不动。（项数不变，仍 59。）

## 验证（真机 4.55.2，2026-10-25）

Shizuku 开了，装进真机跑完整流程：

- 自检 **59/59** 全绿（首跑即绿，见下一节）。
- 修复前必 404 的 exact body，现在正常出词：

  ```
  POST /v1/messages  model=anthropic/claude-opus-4-6[1m]
  → {"type":"message", "model":"auto", "content":[{"text":"收到"}]}   200

  POST /v1/chat/completions  同模型 → "OK"   200

  回归：hy3 点名正常；compatModel 的自检断言逐条过。
  ```

- 剩最后一步没替用户点：Dsh 界面里那发「发送请求」要用户在 Dsh 里亲景点
  （它的 18888 代理只对它自己的 UI 测试开放，curl 连不上）。
  AHYU 侧行为已钉死，理论上那发必过——Dsh 的报错本来就来自我们这条 404。

## 真机自检首跑抓到的第二个 bug（也是我自己写的）

第一次装机自检 **58/59 红一项**：点名红线断言炸了
（`WorkBuddy 1/claude-sonnet-4-5` 被改写成 auto），第二次跑全绿。
两次只差 200 毫秒 —— **冷启动竞态**：首跑时 `config.providers` 还没加载完，
空表里当然"没有 WorkBuddy 1 这个账号"，`isVendorNamespace` 把事实核查做成了
"查不到就当命名空间"，恰好踩穿自己写的那条红线。

修法两处，都是"不可知时保守"：

- `isVendorNamespace`：providers 为空 = 事实不可知 → 返回 false（当点名，不改写）。
  不对称代价想清楚了：把点名当命名空间抢的是用户的账号（不可接受）；
  把命名空间当点名只是这一次 404，下次请求配置就绪就正常（可接受）。
- 自检对应断言：空表时**跳过 Dsh 那条**而不是报错（4.52.2 立的同一课：
  竞态报成缺陷 = 假红，假警报的代价是真失效时没人信）。

这个项目第三次被冷启动竞态咬（4.52.2 是价格表绊线，这次是我自己的新代码），
说明「读 config.providers 的静态断言」默认都该问一句：跑的时候表加载了吗。

## 与 4.55.1 的关系

纯属巧合的相邻事故：4.55.1 是给「DeepSeek Harness 风控」做预防性混淆，
本版修的是 Dsh **另一条完全不同的失败**（模型名路由 404）。
上一版补记里说"真实触发条件多半是 Dsh 完整提示词的形状组合"——
现在有了更朴素的解释：Dsh 用户报的"400/不行"里，相当一部分
其实是这种模型名 404，跟内容风控无关。

## 未做 / 边界

- **Dsh 界面里那发「发送请求」还没替用户点**：AHYU 侧已从 404 翻成 200
  （上面真机验证），但 Dsh 自己的端到端要用户在它界面里亲景点一下确认。
- OpenRouter 式 `vendor/model` 前缀今后都走同一判据：
  本地没这个账号 = 命名空间，剥掉按裸名兼容；有 = 点名，不动。
- `[1m]` 后缀目前只剥不"兑现"：落到 auto 之后，1M 上下文的能力
  取决于选到的模型自己（hy 系有长上下文的会被选上）。真要按
  `[1m]` 加"必须 ≥1M 上下文"的选路约束，是另一个功能，没在本次动。

## 产物

- `AHYU-4.55.2.apk`（versionCode 668）
