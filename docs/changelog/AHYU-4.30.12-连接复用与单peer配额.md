# AHYU 4.30.12 keep-alive 与单 peer 配额

versionCode 595

## MAX_PER_PEER：24 → 由容量派生

`MAX_PER_PEER = 24` 的目的不是限流，而是**防止一个泄漏连接的客户端把整池吃光**，
剩下的留给正常客户端。所以它本质上是"总容量里允许单个客户端占的份额"。

24 / 64 = **37.5%**。问题在于总容量一旦不再固定为 64（见 Capacity），
24 这个绝对值就失去意义：容量降到 8 时，24 是 300%，上限形同虚设。

改成：

    PEER_SHARE = 0.375            保持原份额
    PEER_FLOOR = 8                绝对下限（浏览器/编辑器并发几条是常态）
    maxPerPeer() = clamp(ceil(cap × 0.375), 8, cap)

真机验证（4.30.12）：容量 8 时 perPeer=8；8 并发后容量涨到 19，
perPeer 跟着变成 ceil(19×0.375)=8。

## KEEPALIVE_IDLE_MS = 60s：验过了，不改

用 3360 个真实请求间隔（p50=7s、p90=50s、p99=1675s）算 keep-alive 复用率：

| T | 复用率 | 边际收益 |
|---|---|---|
| 10s | 62.6% | — |
| 30s | 84.3% | +21.7% |
| **60s** | **91.3%** | **+7.0%** |
| 120s | 94.6% | +3.3% |
| 300s | 97.1% | +2.5% |

60 秒已经在边际收益明显递减的位置，而空转线程的期望数一直很小
（T=60s 时 0.38 条，相对 64 的池可忽略）。

### 一个被否掉的尝试

试过用 `建连成本 × 新建率 + 线程成本 × 空转数` 求最优 T，算出"最优 T=5s"。
但**那两个权重（建连 10、线程 1）都是拍的** ——
用拍的权重求出的最优值，和直接拍一个值没有区别，而且更有欺骗性
（看起来是算出来的）。所以放弃，保留 60 秒，并在注释里写明这是验证过不要动的值。

## 真机验证

    versionName=4.30.12
    空闲：  suggested=8   pressure=13%
    8并发后：suggested=19  峰值=6  pressure=5%

## 至此，Server 里所有数值常量都已处理

| 常量 | 原值 | 处置 |
|---|---|---|
| DIRECT_TRIES | 3 | → Router.budget()（4.30.6） |
| VIRTUAL_TRIES | 3 | → Router.budget()（4.30.6） |
| MAX_CONN | 64 | → Capacity.suggest()（4.30.8~10） |
| MAX_PER_PEER | 24 | → 由容量派生（4.30.12） |
| STREAM_PING_STALL_MS | 45000 | → PING_PERIOD×3（4.30.11） |
| 扫描周期 | 30s | → 10s（4.30.11） |
| stalls >= 18 | 18 | → UPSTREAM_IDLE_PERIODS（4.30.11，值不变） |
| KEEPALIVE_IDLE_MS | 60000 | 验证后保留（4.30.12） |
| KEEPALIVE_MAX_REQUESTS | 200 | 未处理 |
| FIRST_REQUEST_MS | 10000 | 未处理 |
| RACE_N | 3 | 未处理 |
| MAX_BODY_BYTES | 8MB | 未处理 |
