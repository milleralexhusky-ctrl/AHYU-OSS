# AHYU 4.30.0 选路自适应（历史样本注入）

versionCode 583 / versionName 4.30.0

## 起因

Router.score() 只看得到**本进程内**的 Stat。每次重启网关、每次升级 APK，
所有账号的成功率/延迟/成本估计全部归零，从先验分重新学起 ——
一个刚从限流里恢复的账号和一个一直稳的账号，重启后同样分。

而 usage.tsv 里本来就躺着「时间/接口/模型/渠道/耗时ms/成功/in/out/Key」，
一直没被选路用过。

## 改法

新增 `core/Stats.java`：启动时后台读一次 usage.tsv（最近 14 天、≥2 笔样本），
按 `渠道/模型` 聚合出三个种子喂给 Router：
- 衰减加权成功率
- 衰减加权耗时（只算成功样本）
- 衰减加权单次成本（Prices × 实际 token）

Router.score() 相应改动：
- 成功率用贝叶斯平滑把「进程内计数」和「历史种子」合成：
  `r̂ = (ok + HIST_K·hist) / (n + HIST_K)`，HIST_K=4
- 延迟项 ±6 分封顶，并且按 `trust = w/(w+HIST_K)` 打折（样本少的不算数）
- 成本项 -3..+2 分（价格表查得到时才有意义）

Gateway 启动时在 Prices/ModelCaps 之后加载。

## 关键教训：逐笔 EWMA 是错的（实测）

最初写的是 `e = e + 0.35*(x-e)`。用真机 usage.tsv（5878 行）复算发现：

    WorkBuddy 1 / deepseek-v4.1-flash —— 2134 次成功 + 1 次失败（且是最后一笔）
    逐笔 EWMA(0.35) → 成功率 0.650

原因不是系数太大，是**样本数本身**：EWMA 收敛半衰期 ln2/α ≈ 1.6 笔，
单个组合攒到 2000 多笔后，估计值永远只反映最后两笔，等于忽略了全部历史。
样本越多被坑越狠，和常识相反。

改成**时间衰减加权**（半衰期 3 天，权重下限 1e-4）：

| 组合 | n | 真实 | 逐笔EWMA | 时间衰减 |
|---|---|---|---|---|
| WorkBuddy 1/deepseek-v4.1-flash | 2135 | 1.000 | 0.650 | **0.999** |
| WorkBuddy 4/gpt-6-sol | 64 | 0.156 | 0.000 | **0.156** |
| Loomy 2/DeepSeek-V4.1-Flash | 524 | 1.000 | 1.000 | 1.000 |
| Trae 2/deepseek-v4.1-flash | 7 | 0.857 | 0.850 | 0.825 |

时间衰减没有这个问题：权重按「距今多久」算，与样本条数无关。
参照点取「这批里最新的一笔」而不是 now —— 一个 10 天没被调的组合，
内部权重结构不该因为我们今天没调它被压平。

## 第二个教训：延迟项必须按样本量打折

首轮复算里，`Gemini/gemini-3.8-flash`（**2 笔**样本，2477ms）排到了第一，
压过 `WorkBuddy 1/deepseek-v4.1-flash`（2135 笔，0.999 成功率）。
2 笔算出来的「1726ms」不比 137 笔算出来的「5118ms」更可信。

所以 Stats.Seed 暴露 `w`（累计权重≈有效样本数），Router 用
`trust = w/(w+HIST_K)` 打折：5 笔 → 0.55，64 笔 → 0.94，200 笔 → 0.98。
修完后榜首变成 65/65、1726ms 的 WorkBuddy 2，合理。

## 验证（真机 4.30.0）

    23:26:45.863 I CPA : 历史选路样本已加载：34 个 渠道/模型 组合（来自 usage.tsv 最近 14 天）
    23:26:45.874 I CPA : 自检：全部 19 项通过
    23:27:35.978 I CPA : 虚拟模型 auto 选路：
        WorkBuddy 2/deepseek-v4.1-flash(144) → WorkBuddy 1/deepseek-v4.1-flash(141) → WorkBuddy 1/kimi-k3-1(141)

实际请求返回正常（"收到"），usage.tsv 追加
`... deepseek-v4.1-flash WorkBuddy 2 1750 1 17 1`。

分数 144 / 141 / 141 与离线 Python 复算逐位一致。

## 没做的

- UCB 探索项（bonus = c·√(ln N/n)）：Stat 里没有「被选次数」的全局 N，
  要做需要先补计数，本轮不加。
- softmax 温度采样：同分档已有 rotate 轮转，够用。
- 语义缓存、hedged request、token bucket：属于后续批次。
