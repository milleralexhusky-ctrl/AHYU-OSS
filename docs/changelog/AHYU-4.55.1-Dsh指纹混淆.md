# 4.55.1 — DeepSeek Harness 指纹混淆（照社区方案，未实测）

## 起因

2026-10-24 晚，Dsh（反代 WorkBuddy 的第三方客户端）侧流传一条风控更新：
WorkBuddy 当晚新加了一轮针对 Dsh 的内容检查，system 身份说明里出现
`DeepSeek Harness` 就回 400，导致大批 Dsh 用户报错。社区给出的处置是：

> 只在 WorkBuddy 请求转发层，将系统消息身份说明里的 `DeepSeek Harness`
> 改成 `DeepSeek` + 零宽字符 + ` Harness`。

这和 AHYU 4.53.3 已有的 `RiskPrompt` 是**同一套机制**：上游按提示词里的
固定字节串拦截，客户端在转发层用零宽字符打断匹配，内容对模型完全等价。

AHYU 现有三枚指纹里没有这枚（`Claude Code` / `claude-code` slug /
`x-anthropic-billing-header`），所以 AHYU 喂给 WorkBuddy 的请求里只要
带这串身份说明,同样会撞新风控。

## 改动

### `RiskPrompt` 加第三枚指纹

新增两个 Pattern（与 `Claude Code` 严格同构）：

```java
DEEPSEEK_HARNESS       = (?i)(deepseek)([\s  ]+)(harness)
DEEPSEEK_HARNESS_SLUG  = (?i)(deepseek)([-_])(harness)
```

覆盖带空格 / 连字符 / 下划线三种写法，回填时大小写、分隔符、空白数量
全部原样保留，只在词两侧各插一个 U+200C。

**用 U+200C 而不是社区给的 U+200B**：零宽空格（200B）本身算空白，
可能被上游"归一化空白"那一步直接抹掉，混淆当场失效；
零宽非连接符（200C）不是空白、不会被折叠。这是 4.53.3 选它时就写下的理由,
这枚照抄。

### 预筛必须跟着加词（这次最容易错的地方）

`neutralize()` 开头有个廉价预筛：文本里连 `laude` / `x-anthropic` 都没有就直接
原样返回，不跑正则。**加新指纹时忘了往预筛加词，该指纹就永远进不了正则分支**
——而且不报错、不显形，只表现为"混淆形同虚设"。预筛补 `deepseek`：

```java
if (low.indexOf("laude") < 0 && low.indexOf("x-anthropic") < 0
        && low.indexOf("deepseek") < 0) return text;
```

自检专门加了一条**预筛回归**断言：一句完全不含 claude / x-anthropic、
只靠 deepseek 触发的小写 Dsh 句，必须被混淆。这条正是为了把"忘加预筛词"
这个坑钉死——它是本次改动里唯一"做错了也不会有任何外部症状"的点。

## 验证

- 编译级单跑（`javac` + 直接调 `neutralize`）：

  | 输入 | 结果 |
  | --- | --- |
  | `You are DeepSeek Harness, an interactive agent…` | 已混淆，去零宽后逐字还原 |
  | `Identity: deepseek harness v2`（无 claude 字样） | 已混淆（预筛生效） |
  | `deepseek-harness` / `deepseek_harness` / `DEEPSEEK-HARNESS` | 全部混淆，大小写与分隔符保留 |
  | `The deepseek model is cheap. Buckle up your harness.` | **不变**（不误伤） |
  | `You are a helpful assistant. Write clean code.` | 不变 |

- 自检扩展 `checkRiskPrompt`（第 97 项内部）：新句混淆生效、去零宽等价、
  slug 三形态、预筛回归、单独出现不误伤。**项数仍是 59**，没有新增 `add()` 行，
  只是把已有这项的断言做厚。

## 这枚和前两枚的关键区别：没实测

必须如实记下：`Claude Code` 那两枚是 4.53.3 抓包 + 真机 8/8 钉死的——
有 WorkBuddy 账号，能复现"带指纹必拦、去指纹必过"。

这一枚**本机无法验证**：没有 Dsh，也没有能在 WorkBuddy 上复现该指纹的账号。
判据形状与前两枚一致（社区方案也指向同一个匹配串），但那是**推断 + 社区报告**，
不是实测结论。

为什么仍值得做：混淆保证内容逐字等价（自检钉住了），所以最坏情形是
"上游其实不看这串、我们白插了一个模型不可见的字符"——零危害；
而最好情形是替 Dsh 用户省掉一次 400。零下行 + 有上行,就动手。

## 补记（2026-10-25 凌晨）：真机把前提测穿了 —— 这枚拦截对我们不存在

发版后发现本机装着 Dsh（WorkBuddy 账号已登录、127.0.0.1:18888 本地代理），
于是立刻做了对照实验。用的正是**发版前的 4.55.0**——它不认识第三枚指纹，
`DeepSeek Harness` 会原样出站，相当于天然对照组：

| 实验 | 入口 | 模型 | 结果 |
| --- | --- | --- | --- |
| 中性对照 | OpenAI | hy3 | pass |
| system 带 `You are DeepSeek Harness…` **原文** | OpenAI | hy3 | **pass** |
| 同上 | OpenAI | kimi-k3.1-pro / deepseek-v4-pro / glm-5.2 | 全部 pass |
| Dsh 完整风格身份句（带工具描述） | **Anthropic** | hy3 | **pass** |

**结论：WorkBuddy 当晚的风控并不按「system 里有 DeepSeek Harness 这串」拦我们转发的请求。**
六发全过，一发 400 都没有。

这说明社区方案的真实触发条件比「这一串词」更复杂——多半是 Dsh **完整提示词的
形状组合**（全句 + 它自带的工具定义 + 可能的头/UA 差异），单拎出那串词并不命中。
对 AHYU 的含义：

- 这枚混淆**不是修复**（我们的路本来就不被拦），只是**预防**——
  上游哪天把这枚串升级成独立判据，我们已经站在零宽字符后面了；
- 混淆保证内容逐字等价（自检钉死），留着零危害，**代码保留，不回滚**；
- 上面「未做/边界」里"上游是否真按这串拦截，未证实"一条，现在有了答案：**对我们不**。

教训（比这条指纹本身值钱）：**可信社区方案 ≠ 已验证事实**。前两枚指纹是
抓包 8/8 钉死的，这枚只凭截图转述就发了版；如果本机没有恰好装着 Dsh，
这条错误前提会一直躺在文档里。以后照外部方案做"修复"时，先花十分钟
构造对照组把前提测一遍，再决定是"修复"还是"预防"——措辞完全不同。

## 未做 / 边界

- ~~**上游是否真按 `DeepSeek Harness` 拦截,未证实**~~ —— **已测穿：对我们不**。
  见上面的补记，4.55.0 裸指纹六发全过。
- **FingerprintWatch 不会为这枚报警**。它的判据是"同账号中性请求能过、
  已混淆请求仍被内容风控拦"——而现在对照实验证明**中性请求和带这枚指纹的
  请求行为一致**（都过），造不出"已混淆仍被拦"的对照，监测对它天然无从触发。
  这不是缺陷：这枚本来就不拦我们，没有失效可监测。真失效的表现仍是用户报 400。
- 只在 WorkBuddy 转发层生效（`WorkBuddy.deFingerprint` 调用面不变）,
  别的渠道不碰——`deepseek` 在别的渠道是正常模型名,动它是降质。

## 产物

- `AHYU-4.55.1.apk`（versionCode 667）
