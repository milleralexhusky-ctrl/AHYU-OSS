# AHYU 4.30.17 首请求时限随压力自适应

versionCode 600

## 先验证 10 秒这个值对不对

用 Little 定律：期望空连接数 = 连接建立率 x 等待时长。

实测建立率 0.0067/秒（25 请求/小时），所以：

    T=10s  -> 期望空连接 0.067 个
    T=60s  -> 期望空连接 0.402 个

两者都远小于容量（8~96），**说明 FIRST_REQUEST_MS 不是性能瓶颈**。
它的真实作用是防恶意/泄漏连接的兜底，不是调优参数。所以 10 秒保留。

## 但"压力高时该更快回收"这个直觉是对的

空连接占用的是**同一批**服务台。当在飞请求接近容量时，
一条空等的连接每多占 1 秒就多 1 秒的损失。

所以加 firstRequestMs()：

    pressure() >= 0.8  ->  5 秒
    否则               ->  10 秒

pressure 来自 Capacity（在飞数 / 建议容量），和 4.30.8 的容量系统同源。

## 真机验证（4.30.17）

    versionName=4.30.17
    空连接测试：连上不说话 12 秒 -> 连接被服务端关闭（nc 无输出）
    capacity 端点正常：in-flight=1 建议容量=8 压力=13%

## Server.java 数值常量最终清单

| 常量 | 原值 | 处置 |
|---|---|---|
| DIRECT_TRIES | 3 | 改为 Router.budget()（4.30.6） |
| VIRTUAL_TRIES | 3 | 改为 Router.budget()（4.30.6） |
| MAX_CONN | 64 | 改为 Capacity.suggest()（4.30.8~10） |
| MAX_PER_PEER | 24 | 改为由容量派生（4.30.12） |
| STREAM_PING_STALL_MS | 45000 | 改为 PING_PERIOD x 3 = 30s（4.30.11） |
| 看门狗扫描周期 | 30s | 改为 10s（4.30.11） |
| stalls >= 18 | 18 | 改为 UPSTREAM_IDLE_PERIODS（4.30.11，值不变） |
| KEEPALIVE_IDLE_MS | 60000 | 验证后保留（4.30.12） |
| FIRST_REQUEST_MS | 10000 | 验证后保留 + 压力自适应（4.30.17） |
| RACE_N | 3 | 验证后保留（4.30.13） |
| KEEPALIVE_MAX_REQUESTS | 200 | 未处理（取决于客户端行为） |
| MAX_BODY_BYTES | 8MB | 未处理（安全性上限，非性能参数） |
