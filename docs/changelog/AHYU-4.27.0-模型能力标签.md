# AHYU 4.27.0 —— 模型能力标签

versionCode 556 / versionName 4.27.0
签名 SHA-256 45a99822b5bca88892d6b58b6f758aa246ac3d481e490d85ba5db749a3a223db（与历史一致）

## 一、做了什么

在模型列表里显示能力标签：`上下文 1M` `输出 128K` `多模态` `工具` `推理` `音频` `PDF` `联网`。

两处生效：
- 底部导航「模型」页（全部模型）
- 账号详情页的模型列表

## 二、数据来源

res/raw/model_prices.json —— 和价格表**共用同一个文件**，数据来自 LiteLLM 的
model_prices_and_context_window.json（MIT），构建时裁剪：
只保留 mode=chat，字段压成最短名：

| 字段 | 含义 | 字段 | 含义 |
| --- | --- | --- | --- |
| i | 输入价 | o | 输出价 |
| ci | 上下文长度 | co | 最大输出 |
| v | 图片 | f | 工具调用 |
| r | 推理 | a | 音频输入 |
| p | PDF | w | 联网搜索 |

条目数 2933（原 132.7KB → 266.5KB，APK 增大约 20KB）。

## 三、匹配逻辑（ModelCaps.lookup）

按顺序尝试，命中即返回：

1. **精确匹配**（归一化：小写、只留字母数字）
2. **最长前缀匹配**（表 key 是查询名的前缀）
3. **反向前缀匹配**（查询名是表 key 的前缀）
4. **去版本后缀回退**：`deepseek-r1-0528` → 砍掉纯数字/V 号段 → `deepseek-r1`

加载时**额外存一份"去掉供应商前缀"的别名**：表里大量是 `provider/model`
（`deepseek/deepseek-r1`、`novita/deepseek/deepseek-r1-0528`），归一化后
供应商名会混进模型名，不处理的话裸模型名一个都匹配不上。

### 实测覆盖率

修复前后对比（20 个你实际在用的模型）：

| | 修复前 | 修复后 |
| --- | --- | --- |
| 命中 | 8/20 | **12/20** |

关键突破：

```
deepseek-r1-0528        上下文163K 工具
deepseek-v3-0324        上下文163K 工具
deepseek-v4.1-flash     上下文1048K 多模态 工具 推理
deepseek-r1-0528-lkeap  上下文163K 工具
```

## 四、界面

- **新增 `Ui.tag()`**：能力标签专用小胶囊。去掉圆点、11 号字、
  内边距 7/3dp、圆角 6dp。原来的 `Ui.pill`（13 号字、带圆点、全圆角）
  用在能力标签上太大，三个就撑满一行。
- **新增 `FlowRow`**：自动换行的横向容器。`LinearLayout(HORIZONTAL)`
  **不换行**，标签多了会溢出卡片被裁（真机上「联网」只剩一半）。
  `FlowRow` 按可用宽度逐行摆放，放不下就换行。

状态胶囊（「调不通」「手填」「已启用」）继续用 `Ui.pill`，**没动**。

## 五、已知局限（如实记录）

**LiteLLM 是社区维护的表，不是官方发布**，所以：

- `balanced-model`、`codewise-jump`（WorkBuddy 自定义名）**查不到**
- `space-bunny`、`aquila`（代号）**查不到**
- `summary`、`deep-model`（功能名）**查不到**
- `Doubao-Seed-Evolving`、`step-5-preview`（国产定制）**查不到**

**这些一律不显示标签，不猜、不显示"未知"。** 这是数据问题，不是代码问题。

## 六、关于"新账号自动有标签"

- **能自动**：模型名能匹配上表（现在支持 4 种匹配方式）→ 自动显示标签
- **不能自动**：表里完全没有的模型 → 永远没标签，除非补数据源

## 七、本轮清理的临时代码

| 代码 | 处理 | 理由 |
| --- | --- | --- |
| Antigravity 完整响应分段打印 | 删除 | 一次性排查"地区不合格"用，正常跑会刷屏 |
| Antigravity 档位摘要日志 | 保留 | 一行摘要，能一眼看出档位/地区问题 |
| GatewayService.wbProbeForDebug | 删除 | 为排查 WorkBuddy 接口加的一次性诊断 |
| WbTasks.probeRaw/probeParsed/probeSummaryLines | 保留 | 有用的诊断工具，不主动执行、无副作用 |
