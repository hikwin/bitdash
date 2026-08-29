# BitDash (v1.6)

BitDash 是一款轻量、现代且高性能的 Android 原生全市场金融行情终端与量化交易辅助应用。涵盖**加密货币、A股、美股及大宗商品（黄金/原油/白银）**，支持毫秒级实时报价、自研硬件加速 K 线、量化策略脚本回测与自动化交易、多通道预警以及极简桌面悬浮窗。

---

## 🌟 核心功能

### 1. 🌐 全资产市场覆盖
- **加密货币**：直连主流交易所（Binance、OKX、Gate、Huobi 等），支持 WebSocket 实时秒级推送。
- **A股专线**：直连国内金融数据中心，支持沪深北全标的、大盘指数、分时及日/周/月 K 线。
- **美股与大宗商品**：覆盖美股巨头、中概股与热门大宗商品 ETF（黄金 GLD/IAU、白银 SLV、原油 USO/BNO 等），提供公司名称解析与智能日/周/月 K 线。

### 2. 📊 专业 K 线与指标系统
- **轻量硬件加速图表**：支持 1m ~ 1M 多周期无缝缩放拖拽。
- **主流技术指标库**：MA、EMA、BOLL、SuperTrend、Fibonacci、TURTLE（海龟交易法）、VOL、MACD、RSI、KDJ、Lorentzian 分类等。
- **区间测算尺**：双指长按快速测量任意两点间价差、涨跌幅、K 线数及时间跨度。

### 3. 🤖 量化脚本与自动化交易
- **自定义 PineScript 脚本**：支持策略编写、指标叠加与快速回测（胜率/收益率/最大回撤）。
- **自动化策略交易**：支持信号触发、止盈止损自动管理与模拟实盘交易执行。

### 4. 🔔 价格预警与多通道通知
- **价格与异动监控**：支持目标价突破/跌破及短期急涨急跌异动预警。
- **4 大推送通道**：系统状态栏通知、应用内 Toast 气泡、Webhook 机器人（钉钉/企业微信/飞书）、自定义 HTTP 接口（GET/POST）。

### 5. 🪟 极简桌面悬浮窗
- **胶囊悬浮窗**：桌面常驻盯盘，支持大额整数精炼与微额 `0.0(N)XX` 紧凑计数。
- **贴边收纳与自适应**：支持抽屉式贴边折叠、20%~100% 透明度调节与边缘防遮挡。

---

## 📦 项目代码架构

```text
app/src/main/
├── java/com/bitdash/app/
│   ├── BaseActivity.kt              # 基础 Activity (统一屏幕方向与主题)
│   ├── MainActivity.kt              # 行情看板主界面 (自选/美股/A股切换与刷新)
│   ├── ChartActivity.kt             # 标的详情页 (K线交互/13大指标/测算尺)
│   ├── SearchActivity.kt            # 全局搜索与自选管理 (支持拼音/代码/中文名检索)
│   ├── AutoTradingActivity.kt       # 自动化量化策略实盘/模拟交易监控
│   ├── CandleChartView.kt           # 自研轻量级硬件加速蜡烛图控件
│   ├── FloatingWindowService.kt     # 桌面悬浮窗前台服务与贴边抽屉渲染
│   ├── BacktestSetupDialog.kt       # 策略回测参数配置弹窗
│   ├── BacktestResultDialog.kt      # 策略回测绩效报告弹窗 (收益率/最大回撤/胜率)
│   ├── ScriptManagerDialog.kt       # PineScript 策略脚本管理与代码编辑器
│   ├── IndicatorSettingsDialog.kt   # 13大指标参数自定义设置弹窗
│   ├── SettingsDialog.kt            # 用户偏好/行情源/字体缩放与通用设置
│   ├── SourcePicker.kt              # 行情数据源优选与延迟探测器
│   ├── alert/                       # 价格预警与异动监控子系统
│   │   ├── PriceAlert.kt            # 预警数据模型与序列化
│   │   ├── PriceAlertManager.kt     # 毫秒级价格计算与多通道分发引擎
│   │   ├── PriceAlertStore.kt       # 预警持久化存储
│   │   ├── PriceAlertDialog.kt      # 预警列表与新增/编辑弹窗
│   │   ├── PushChannelConfig.kt     # Webhook/HTTP 渠道配置与异步网络分发
│   │   └── PushChannelSettingsDialog.kt # 推送通道与机器人配置管理
│   └── market/                      # 多市场数据层与策略引擎
│       ├── AshareMarkets.kt         # A股直连行情、分时与周/月K线
│       ├── RwaMarkets.kt            # 美股/大宗商品专线与智能日K聚合
│       ├── UsStockSource.kt         # 美股字典与公共数据接口
│       ├── Markets.kt               # 加密货币多交易所统一调度聚合
│       ├── RealtimeSession.kt       # WebSocket 极速实时流会话
│       ├── Indicators.kt            # 13大技术指标与 Lorentzian 算法库
│       ├── PineScriptManager.kt     # PineScript 策略解析执行引擎
│       ├── StrategyBacktester.kt    # 历史 K 线量化策略回测引擎
│       ├── PaperTradingEngine.kt    # 模拟与自动化实盘订单撮合引擎
│       ├── FloatingFmt.kt           # 悬浮窗紧凑数字与零计数格式化
│       ├── Fmt.kt                   # 基础价格与大数格式化
│       ├── Symbols.kt               # 交易对与标的代码标准化
│       └── Prefs.kt                 # SharedPreferences 持久化管理
├── res/                             # 界面布局 (含横竖屏适配)、调色板、矢量图标
└── AndroidManifest.xml              # 权限、前台服务与组件声明
```

---

## 🛠️ 技术栈与规范

- **开发语言**: Kotlin (JVM 17 Target)
- **编译配置**: Android Gradle Plugin 8.3+, Gradle 8.4+, JDK 17
- **系统要求**: Android 7.0+ (Min SDK 24 / Target SDK 34)
- **核心组件**: AndroidX, ViewBinding, Kotlin Coroutines, OkHttp 4, Material Design 3
- **包体优化**: R8 深度优化与资源混淆压缩 (Release APK 仅约 2.0 MB)

---

## 🚀 编译构建

```powershell
# 1. (可选) 指定本地 JDK 17 环境变量
# $env:JAVA_HOME = "<你的JDK-17安装路径>"

# 2. 编译 Release 正式版 APK
.\gradlew.bat assembleRelease

# 3. 编译 Debug 测试版 APK
.\gradlew.bat assembleDebug
```
