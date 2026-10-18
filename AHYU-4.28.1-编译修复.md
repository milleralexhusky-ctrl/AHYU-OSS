# AHYU 4.28.1 —— 4.28.0 的编译修复

versionCode 557 / versionName 4.28.1（沿用 4.28.0 的 versionCode，只修可见性）
签名 SHA-256 45a99822b5bca88892d6b58b6f758aa246ac3d481e490d85ba5db749a3a223db（与历史一致）

## 背景

4.28.0 的报告写"解压后在源码根目录直接执行 `./build.sh` 即可编译并签名生成新版"。
实际在干净环境构建时 **javac 报两个错**，工程无法编译：

```
src/com/cpa/gateway/ProviderEditor.java:389: error:
    refreshStatus() has private access in MainActivity
src/com/cpa/gateway/MainActivity.java:863: error:
    Server is not public in com.cpa.gateway.core; cannot be accessed from outside package
```

两处都是 4.28.0 **新增了跨包/跨类调用，但漏改被调方的可见性修饰符**。

## 修复内容（只有两行修饰符）

### 1. MainActivity.refreshStatus() → 包级可见

```java
// 之前
private void refreshStatus() {
// 现在
void refreshStatus() {
```

原因：`ProviderEditor.handleResult()`（同包 `com.cpa.gateway`）在导入账号后要刷新主页状态。

### 2. Server 类 → public

```java
// 之前
final class Server {
// 现在
public final class Server {
```

原因：`MainActivity`（`com.cpa.gateway` 包）要调
`com.cpa.gateway.core.Server.runAccountChores(p)`，把「一键做任务」改为进程内调用
（不再依赖网关 HTTP 端口是否运行）。`runAccountChores()` 本身已经是 `public static`，
只是**类**还是包级私有 —— 跨包就访问不到。

## 验证

| 项 | 结果 |
| --- | --- |
| 构建 | `./build.sh` 通过（aapt2 → javac → d8 → 打包 → apksigner）|
| 签名 | SHA-256 45a99822…3a223db，与历史一致 |
| 安装 | `pm install -r` 成功 |
| 版本 | versionCode 557 / versionName 4.28.0（源码内标注）|
| 服务 | 启动正常，health 返回 401（需认证，符合预期）|
| 账号 | 12 个账号全部可用，279 模型（275 可用）|
| 能力表 | 加载 3961 条（含内置补充模型）|

## 关于 4.28.0 报告的核对说明

4.28.0 的审计总体有效，其中**能力标签的"版本后缀剥离是死代码"确实是真 bug**
（旧实现在 `norm(model)` 删光连字符之后才找 `lastIndexOf('-')`，恒为 -1，
那段后缀回退逻辑从未执行过），修法也正确。

但有两点需要记录：

1. **报告称"可直接 build.sh 编译"与实际不符** —— 就是上面这两处，未经构建验证即交付。
2. **第 13 条"optInt 读 long 有 32 位截断"** 说法成立但影响被高估：
   那些字段存的是**秒级** Unix 时间戳（2026 年约 1.79e9），
   距 Integer.MAX_VALUE（2.147e9）还有余量，**2038 年 1 月才会溢出**。
   该修不紧急，与"高危"并列略夸张。
