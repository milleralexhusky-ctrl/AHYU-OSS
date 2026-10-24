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

## 未做 / 边界

- **上游是否真按 `DeepSeek Harness` 拦截,未证实**。若社区报告的是别的串
  （比如 `DeepSeek` 加别的后缀）,这枚混淆命中不到,但也不会造成任何破坏。
- **FingerprintWatch 不会为这枚报警**。它的判据是"同账号中性请求能过、
  已混淆请求仍被内容风控拦"——需要我们有能跑 WorkBuddy 的账号去造对照。
  这枚指纹我们没有账号,监测也就无从谈起。真失效的表现仍是用户报 400。
- 只在 WorkBuddy 转发层生效（`WorkBuddy.deFingerprint` 调用面不变）,
  别的渠道不碰——`deepseek` 在别的渠道是正常模型名,动它是降质。

## 产物

- `AHYU-4.55.1.apk`（versionCode 667）
