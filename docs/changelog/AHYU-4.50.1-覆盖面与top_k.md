# AHYU 4.50.1：保真透传覆盖更多渠道 + top_k 跨协议

versionCode 644。

4.50.0 的「未做」里有两项不是做不了，而是当时按风险收益先划了边界。这一版把它们做了。

## 一、保真透传扩大到 xAI / Kilo / OVH / Loomy

4.50.0 只接了通用 OpenAI 兼容渠道（自定义接口）。这几个渠道其实和它走同一套
`openAiBody` + `openAiStream`，响应也是标准 OpenAI SSE，接进来没有额外假设：

| 渠道 | 上游形状 | 本次 |
| --- | --- | --- |
| xAI（CLI 账号） | OpenAI Chat SSE | 保真透传 |
| Kilo 免费池 | OpenAI Chat SSE | 保真透传 |
| OVHcloud 免费池 | OpenAI Chat SSE | 保真透传 |
| Loomy | OpenAI Chat SSE | 保真透传 |

抽了一个共用入口 `Upstreams.rawOpenAiIfWanted(...)`，四个渠道各加一行判断，
避免五份重复逻辑以后改漏。

**没有接的，以及原因**：

- DeepSeek / 豆包：上游是自家私有协议（`prompt` / `event_data`），不是 OpenAI，
  也没有 `n` / `logprobs` 的等价语义；
- Kimi / Claude：上游是 Anthropic Messages，本来就没有 logprobs；
- Cline：非流式且响应外面包了一层 `{"data":{…}}`，要接得先拆信封，
  而它的模型列表里没有会用到 `n` / `logprobs` 的模型；
- WorkBuddy / Trae / Antigravity / Codex：私有协议或渠道校验链路，保持原样。

## 二、top_k 跨协议等价翻译

三套协议里名字不同、语义相同：

| 入口 / 出口 | 字段 |
| --- | --- |
| OpenAI（兼容） | `top_k` |
| Anthropic | `top_k` |
| Gemini | `generationConfig.topK` |

以前只有同协议时原样透传，跨协议直接丢。现在收进 canonical `Proto.Req.topK`：

- OpenAI / Anthropic / Gemini 同协议都保留；
- Anthropic ↔ Gemini 互译；
- 客户端显式发 `top_k` 时，可以翻译到 Anthropic / Gemini；
- **不往 OpenAI 上游跨协议硬塞**：OpenAI 官方接口不认 `top_k`，硬塞会把本来正常的
  请求变成 400。只有客户端自己用 OpenAI 入口发 `top_k` 时才原样发出。

## 三、验证

- 自检 **29/29**（新增「top_k 跨协议等价翻译」）。
- 假上游分别以 `type=openai`、`type=kilo`、`type=ovh` 注册，`n=2 + logprobs`
  的流式/非流式请求都能拿到两个 choice、logprobs、usage，且 `model` 改写为请求名；
  普通请求仍走 canonical。
- `top_k` 四向断言：OpenAI→Anthropic/Gemini、Anthropic→Gemini、Gemini→Anthropic
  都有值；Anthropic/Gemini→OpenAI 不出现 `top_k`。
- 真机 release 覆盖安装（644），3 个真实账号普通请求正常。

## 四、仍未做

- **带工具的 logprobs 请求**：仍不开启保真。工具参数是分片到达的，网关要做
  修复/坏调用换号，就必须先缓存并重写；这与"原样透传"本质冲突。真要做，
  得先设计"只校验不重写、校验失败再回退 canonical"的两段式路径，收益很小。
- **Realtime 音频 / vector stores / file search / code interpreter**：这些不是
  网关能"补"出来的——上游没有对应的实时音频或向量存储能力，硬做只是空壳。
- 跨协议 `n` / `candidateCount` 不翻译：canonical 只保留一个 choice，
  翻译过去只会多烧 token 再把结果丢掉。
