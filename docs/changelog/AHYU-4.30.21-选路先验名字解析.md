# AHYU 4.30.21 — 选路先验从「型号清单」改为「名字解析」

版本：4.30.21 / versionCode 604
日期：2026-10-18

## 为什么动这里

按"每一处'凭什么这么选'都该有可解释判据"扫了一遍全仓，结论是：

**核心决策层其实已经数学化得很彻底。** `Router.score()` 有贝叶斯平滑
`(ok + HIST_K*hist)/(n + HIST_K)`、延迟可信度折扣 `w/(w+HIST_K)`、成本对数项
`1/(1+cost*400)`；`Server` 里那些阈值常量都附了实测依据（3361 条样本的 p99.9、
keep-alive 复用率曲线）。`Upstreams`(248 if) / `Fronts`(100 if) / 各厂商类的
大量 if 是**协议适配**，不该数学化——硬套公式只会更难维护。`Selftest`(88 if/0 math)
是**断言**，本来就该是 if。

唯一一处真正的"凭直觉的判据"是 `Router.prior()`：一张手写的模型型号字符串表。

## 缺陷（真机数据实测）

```java
private static double prior(String model) {
    for (String h : new String[]{"glm-5", "deepseek-v4", "claude", "gpt-5", "kimi-k",
            "gemini-2.5", "gemini-3", "minimax-m", "hy3", "qwen3"}) {
        if (m.contains(h)) { v += 12; break; }
    }
    if (m.contains("preview") || m.contains("-exp")) v -= 8;
    return v;
}
```

拿当前 379 个真实模型统计：

| 项 | 数量 | 占比 |
|---|---|---|
| 命中清单（+12） | 149 | 39% |
| **未命中（先验 0）** | **230** | **60%** |
| 被扣分（-8） | 27 | 7% |

- 清单漏了 `glm-4.6`、`deepseek-v3-1`、`hunyuan-3b` 这些**正在用**的正式模型；
- 却收了 `glm-5` —— 说明它是某个时点手抄的，新模型上线没人回来补；
- `flash` 和 `pro` 同等对待（都给 +12），不区档次；
- `explore_sub_agent_v2` 这类非对话模型会被 `-exp`/版本推断打成 -8。

**这类过时清单不会报错**，只会让新模型永远默默排在最后——和之前修过的
"检测归类"是同一类问题：错误不发声。

## 改法：从名字里解析有序量，不依赖型号清单

四条信号都是名字里**本来就有**的信息，新模型一上线自动适用：

1. **版本号**：正则抠 `v(\d+(\.\d+)?)`，取最大值。用**饱和曲线**而非线性
   （`ver<=0 ? 0.5 : 1 - 1/(1+(ver-2.5)*1.1)`），因为"v9 比 v8 好"在真实能力上递减。
2. **尺寸档**：`ultra/opus/pro/max/large` = +1，`flash/lite/mini/small/nano/turbo` = -1。
3. **实验标记**：`preview/-exp/beta/alpha/nightly/-rc/dev` → 扣 `PRIOR_MAX*0.45`。
4. **别名与非对话模型**：`auto/default/completion/subagent/explore/file-search/
   browser-use/computer-use/embed/rerank/whisper/tts/summary` → 先验 0。

```java
v = verScore * PRIOR_MAX * 0.55      // 约 0 .. 6.6
  + sizeTierOf(m) * PRIOR_MAX * 0.22 // 约 -2.6 / 0 / +2.6
  - (isExperimental(m) ? PRIOR_MAX*0.45 : 0);
// 夹到 [-PRIOR_MAX, PRIOR_MAX]
```

`PRIOR_MAX = 12`，比一次成功率的满额（30）小，**保证真实数据攒起来后能压过先验**。

注意 `prior()` 里先把 `_` 统一换成 `-`，所以 `isAlias` 一律用连字符形式匹配
（`sub-agent` 而非 `sub_agent`）——这一条踩过一次坑。

## 新旧对比（同一批真实模型名）

| 模型 | 旧 | 新 | 说明 |
|---|---|---|---|
| `deepseek-v4.1-flash` | +12.0 | +1.6 | 旧不区档次，新区分 flash |
| `deepseek-v4-pro` | +12.0 | +6.7 | pro 档次拉开 |
| `glm-4.6` | **0.0** | +3.3 | 旧表漏收，新自动识别 |
| `glm-5.3` | +12.0 | +3.3 | 旧表硬编码，新按版本推 |
| `deepseek-v3-1` | **0.0** | +2.3 | 旧表漏收 |
| `hunyuan-3b` | **0.0** | +3.3 | 旧表漏收 |
| `step-5-preview` | -8.0 | -2.1 | 惩罚过重，已收敛 |
| `explore_sub_agent_v2` | **-8.0** | 0.0 | 非对话模型，正确归零 |
| `auto` / `summary` | 0.0 | 0.0 | 别名，不给先验 |

整体：**非零先验从 39% → 87%**（余下 13% 是别名与非对话模型，本来就该是 0）。

## 真机验证

| 项目 | 结果 |
|---|---|
| 编译 | javac 零错误，BUILD_OK |
| 安装 | 4.30.21 / 604 覆盖成功 |
| `/health` | 200 |
| `auto` 路由实跑 | 选中 `deepseek-v4.1-flash` @ `WorkBuddy 2`（usage.tsv 记录）|
| 顺序 stop→start 三轮 | 每轮 health=200 |
| 点名三种模型 | `auto` / `deepseek-v4.1-flash` / `glm-5.3` 均正常返回 |

## 教训

判据写成"清单"就会过期，过期不说话。**能从数据本身推导的，就不要抄成表。**
版本号、尺寸档、实验标记这三样在模型名里一直存在，是稳定信号；
而"哪几个型号好"是易变判断——把易变的抄进代码，注定要烂在里面。
