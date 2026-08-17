# BitDash (加密货币实时行情看板与桌面悬浮窗)

BitDash 是一款轻量、现代且高效的 Android 原生加密货币行情看板应用。支持多主流交易所实时行情接入、专业级 K 线图表分析，以及极简风格的桌面悬浮窗实时监控。

---

## 🌟 核心功能特性

- 📈 **多源实时行情**：支持 Binance、OKX、CoinGecko、Gate.io、Bybit 等多家主流交易所行情实时获取与毫秒级 WebSocket 推送。
- 📊 **专业 K 线图表**：自研轻量级高性能蜡烛图（CandleChartView），支持 1m / 5m / 15m / 1h / 4h / 1d 多周期切换、MA 均线指标、成交量柱状图与十字光标触控查价。
- 💱 **法币汇率换算**：支持实时将行情价格折算为 USD、CNY、EUR、JPY、GBP 等多种法币。
- 🔄 **全向屏幕旋转**：内置 0° 竖屏、90° 横屏、180° 反向竖屏、270° 反向横屏及重力感应自适应等多种旋转锁定模式。
- 🪟 **极简桌面悬浮窗**：火币 App 风格的半透明悬浮胶囊，无需打开应用即可随时在手机桌面、游戏或浏览其他应用时掌握自选币种最新价格。

---

## 📌 桌面悬浮窗极简显示规则

为确保悬浮窗在手机屏幕上占用最小视野、不遮挡其他应用内容，悬浮窗采用了专用的**极简数字与名称格式化规则**：

| 规则维度 | 处理逻辑 | 示例说明 |
| :--- | :--- | :--- |
| **代币名称精简** | 去除交易对后缀（如 `-USDT`、`/USDT` 等），仅保留核心代币符号 | `BTC-USDT` ➔ **`BTC`**<br>`DOGE/USDT` ➔ **`DOGE`** |
| **大额币种 (≥ $100)** | 价格四舍五入**仅显示整数位**，极致精炼 | $96,420.35 ➔ **`96420`**<br>$2,730.80 ➔ **`2730`** |
| **中额币种 ($10 ~ $100)** | 保留 **2 位小数** | $25.503 ➔ **`25.50`**<br>$18.2 ➔ **`18.20`** |
| **小额币种 ($1 ~ $10)** | 保留 **4 位小数** | $2.45601 ➔ **`2.4560`** |
| **微额币种 (< $1)** | 最多保留 **5 位小数**，并自动去除末尾无效的 0 | $0.070580 ➔ **`0.07058`**<br>$0.000120 ➔ **`0.00012`** |

### 悬浮窗交互设计
1. **全屏自由拖拽**：按住悬浮窗即可在屏幕内任意拖拽，松手后自动记忆位置。
2. **点击快速回跳**：点击悬浮窗任意位置可直接唤起 BitDash 主界面。
3. **透明度实时预览调节**：支持在设置弹窗中滑动调节 20% ~ 100% 不透明度，且悬浮窗会实时生效预览，所见即所得。
4. **币种勾选与拖拽排序**：支持自选 1 ~ 10 个展示币种，在设置列表中可通过**长按整行**或**按住右侧手柄**自由调整显示顺序。

---

## 📦 项目结构

```text
app/src/main/
├── java/com/bitdash/app/
│   ├── BaseActivity.kt          # 统一屏幕旋转与生命周期基类
│   ├── MainActivity.kt          # 主行情列表看板与实时刷新
│   ├── ChartActivity.kt         # 币种详情与 K 线图表交互页面
│   ├── SearchActivity.kt        # 币种搜索与添加自选
│   ├── CandleChartView.kt       # 自定义硬件加速 K 线图表控件
│   ├── FloatingWindowService.kt # 桌面悬浮窗前台服务与 WindowManager 渲染
│   ├── SettingsDialog.kt        # 偏好设置、悬浮窗配置与行情源切换弹窗
│   └── market/                  # 行情数据层 (WebSocket/REST API/Prefs/格式化)
│       ├── FloatingFmt.kt       # 悬浮窗极简格式化工具类
│       ├── Markets.kt           # 聚合行情数据源调度
│       ├── Prefs.kt             # SharedPreferences 本地持久化配置
│       └── ...
├── res/
│   ├── layout/                  # 界面布局 (含主界面、K线图、悬浮窗胶囊等)
│   ├── values/                  # 字符串、颜色主题、调色板
│   └── drawable/                # 矢量图标与 UI 背景资源
└── AndroidManifest.xml          # 权限与组件声明
```

---

## 🛠️ 技术栈与编译要求

- **语言**: Kotlin (JVM 17 Target)
- **编译环境**: Android SDK Compile 34 / Min 26 (Android 8.0+) / Target 34
- **架构与视图**: AndroidX, ViewBinding, Kotlin Coroutines, Flow, Material Design 3
- **网络与通信**: OkHttp / WebSocket / JSON
- **系统特性**: WindowManager Overlay (`SYSTEM_ALERT_WINDOW`), Foreground Service

---

## 🚀 编译与构建

```bash
# 检查与清理构建
./gradlew clean

# 编译生成 Debug APK
./gradlew assembleDebug
```
