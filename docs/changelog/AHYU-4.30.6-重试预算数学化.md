# AHYU 4.30.6 重试次数从魔数改为闭式解

versionCode 589

## 盘点：这个软件的组成

93 个文件 / 34627 行。按重要性：

| 层 | 文件 | 行数 |
|---|---|---|
| 网关核心 | Server | 3748 |
| | Router | 819 |
| | Upstreams | 1320 |
| | 各家协议 WorkBuddy/Trae/Loomy/Antigravity/DeepSeek/Doubao/Xai/Kimi/Cline/Kilo/Ovh | ~5000 |
| | Gateway/Http/Proto/Usage/Prices/ModelCaps/Stats/Detect | ~2500 |
| 账号配置 | Config 974、Secret 345、Backup 280、各 Login ~2600 | ~4000 |
| 界面 | MainActivity 1650、Ui 1276、Settings 642、Models 592、Accounts 349、Log 317 | ~6000 |
| 美学层 | GlassDrawable 353、NavGlass 226、Jelly 318、FlowRow 88、BackdropTex 222、Perf 176 | ~1400 |
| 后台 | GatewayService 638、KeepAliveJobService 140、WbTasks 485、WbTaskRunner 389 | ~1700 |

## 数学化程度盘点

**已经数学化的**
- 选路 `Router.score`：衰减加权成功率 + 贝叶斯平滑 + 可信度折扣（4.30.0）
- 视觉 `Ui`：4dp 模数与 1.125 字阶（4.30.1）
- 阴影 `GlassDrawable`：高斯反解 + 圆弧外扩（4.30.4）
- 不透明度 `Ui.ALPHA_*`：几何级数六档（4.30.5）
- 帧预算 `Perf`：EWMA + 滞回控制器（4.30.3）
- 弹簧 `Jelly`：阻尼振子（本来就对）

**还是魔数的**（按收益排序）

| 常量 | 值 | 位置 | 问题 |
|---|---|---|---|
| DIRECT_TRIES / VIRTUAL_TRIES | 3 | Server | 本轮已修 |
| MAX_CONN | 64 | Server:134 | 吞吐上限，凭经验 |
| MAX_PER_PEER | 24 | Server:378 | 单客户端占额 |
| STREAM_PING_STALL_MS | 45000 | Server:332 | 假死判定 |
| KEEPALIVE_IDLE_MS | 60000 | Server:364 | 连接复用 |
| FIRST_REQUEST_MS | 10000 | Server:375 | 空连接回收 |
| RACE_N | 3 | Server:2877 | 竞速路数 |
| Classifier 阈值 | 4 个 | Classifier | 请求分类 |

## 本轮：重试次数的闭式解

### 为什么 3 是错的

真机 3408 条样本，单候选失败率 p = 0.0208。重试是几何衰减：

    试1次：全失败 0.020833  可靠度 0.9792  延迟 1.53s
    试2次：全失败 0.000434  可靠度 0.9996  延迟 1.56s   ← 边际 +0.02%
    试3次：全失败 0.000009  可靠度 0.99999 延迟 1.56s   ← 边际 +0.0004%，最坏 +3s

**第 3 次重试把最坏情况从 6s 拉到 9s，换来的可靠度提升是 0.0004%。**
在任何延迟敏感场景里这笔交易都是亏的。

### 但固定 2 次也不对

三个失败率都是 30% 的候选，跨候选累积 0.3³ = 2.7% —— 远没到底。
而候选之间是"或"关系，全部失败 = ∏pᵢ，不是 pⁿ。

### 实现

`Router.budget(cands)`：从第 0 个开始累乘失败概率，
乘到 ≤ 0.001 就停，返回"值得试几个"，下限 2（留兜底），上限 4。

`Router.triesFor(pick)`：单候选版本，`k = ⌈ln(0.001)/ln(p)⌉`。

失败率估计和 score() 共用同一份证据（进程内 Stat + 历史 Stats 种子），
贝叶斯平滑后取反；无证据时保守返回 0.5。

### 替换

`Server` 里 5 处 `Math.min(DIRECT_TRIES, ...)` / `Math.min(VIRTUAL_TRIES, ...)`
→ `Router.budget(cands)` / `Router.budget(cands, model)`。
（直连路径的 cands 是 `List<Provider>`，所以加了一个按 model 重载的重载。）

### 验证

4.30.6 装机后真机日志：

    虚拟模型 auto 选路：WorkBuddy 2/deepseek-v4.1-flash(144) → WorkBuddy 1/deepseek-v4.1-flash(141)

**只列 2 个候选**（原来是固定 3 个）——两个高可靠候选的累积失败已低于 0.1%，
第三个候选不再出现在预算里。真实请求正常返回。

## 下一步（按吞吐收益排序）

1. `MAX_CONN=64`：用 Little 定律 L = λW 从真实耗时反推
2. `KEEPALIVE_IDLE_MS=60000`：从连接复用率的实测分布定
3. `STREAM_PING_STALL_MS=45000`：从流式心跳间隔的统计定
4. `Classifier` 阈值：用请求内容的实际分布寻阈值
