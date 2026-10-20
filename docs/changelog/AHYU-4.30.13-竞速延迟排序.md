# AHYU 4.30.13 竞速：latencyOf 补上历史样本

versionCode 596

## 发现的漏洞

4.30.0 引入 `Stats`（从 usage.tsv 读历史样本）时，只把它接进了 `score()`。
`Router.latencyOf()` —— 竞速排序用的那个函数 —— **没接上**：

    private static long latencyOf(Pick c) {
        Stat st = STATS.get(c.key());
        return st == null ? 0L : st.latencyEwma;
    }

而 `latencyEwma` 是进程内统计，**重启后归零**。结果是：

    重启 → 所有候选 latencyOf 都是 0
        → 排序退化成"都没有延迟数据，按综合分降序"
        → 竞速变成"挑综合分最高的 3 个并发跑"

而竞速的全部意义就是"谁先出字用谁"。排序不看延迟，胜率和浪费都会变差。

## 改法

和 score() 用同一套优先级：**历史种子优先，进程内 EWMA 兜底**。

但历史值要按样本量打折 —— 这点和 4.30.0 踩过的坑是同一个：

    2 笔样本算出的 1700ms，不该压过 137 笔算出的 5100ms

    trust = w / (w + HIST_K)        HIST_K = 4
    blended = seed.ms × trust + 4000 × (1 - trust)

4000ms 是中性值，取自真机实测 p50 ≈ 4.1s。

效果：

| 样本 w | 实测 ms | trust | 混合后 ms |
|---|---|---|---|
| 0.5 | 1700 | 0.11 | 3744 |
| 2 | 1700 | 0.33 | 3233 |
| 4 | 1700 | 0.50 | 2850 |
| 10 | 5100 | 0.71 | 4786 |
| 137 | 5100 | 0.97 | 5069 |

2 笔的 1700ms 被拉回 3233ms（不再冤枉地排第一），
137 笔的 5100ms 基本不受影响。

## RACE_N = 3：验过了，是对的

用真机 3290 条成功请求的延迟分布做蒙特卡洛（每档 3000 次抽样，
取 n 个独立样本的 min）：

| 路数 | 期望最小延迟 | 相对 1 路 | 多烧的上游调用 |
|---|---|---|---|
| 1 | 8.62s | 100% | 0 |
| 2 | 3.83s | 44.4% | 1 |
| **3** | **2.94s** | **34.1%** | **2** |
| 4 | 2.62s | 30.3% | 3 |
| 5 | 2.36s | 27.4% | 4 |

3 路之后每多一路收益不到 4 个百分点，而要多付出一次上游调用。
**3 是拐点，这个值不需要改。**

另外竞速只在显式点名 `auto-race` 时才触发（不是默认行为），
所以不会静默烧用户的额度 —— 这个设计也是对的。

## 真机验证（4.30.13）

    竞速：3 路进（WorkBuddy 2/deepseek-v4.1-flash  DeepSeek 1/deepseek-chat
                   WorkBuddy 1/deepseek-v4.1-flash）
            · WorkBuddy 1/deepseek-v4.1-flash 胜出 · 1309ms
    竞速结束：3 路竞速 · WorkBuddy 1/deepseek-v4.1-flash 胜出 · 1.68s

## 剩下的

- `FIRST_REQUEST_MS = 10000`：等首个请求的时限，还没分析
- `KEEPALIVE_MAX_REQUESTS = 200`：连接复用次数上限，还没分析
- `MAX_BODY_BYTES = 8MB`：请求体上限，属于安全性而非性能
- `Classifier` 的 4 个分类阈值：还没分析
