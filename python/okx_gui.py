"""
python-okx GUI — PyQt6 + Plotly 现代化 OKX 交易终端
====================================================
- 深色专业交易所风格 QSS
- Plotly + QWebEngineView 交互式 K线（蜡烛图 + 成交量 + 均线）
- 公开行情 / 账户 / 资金 / 交易 五大功能页签
- 后台线程执行 API，pyqtSignal 驱动 UI，自动刷新可配置
- 配置保存/加载/写 .env，实盘下单二次确认
"""
import asyncio
import json
import math
import os
import queue
import sys
import threading
import time
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
import websockets

from PyQt6.QtWidgets import (
    QApplication, QMainWindow, QWidget, QVBoxLayout, QHBoxLayout,
    QGridLayout, QLabel, QLineEdit, QComboBox, QPushButton, QCheckBox,
    QRadioButton, QButtonGroup, QTabWidget, QTextEdit, QSplitter, QFrame,
    QMessageBox, QSizePolicy, QSpacerItem, QSpinBox, QDoubleSpinBox,
    QTableWidget, QTableWidgetItem, QHeaderView, QProgressBar, QGroupBox,
    QScrollArea,
)
from PyQt6.QtCore import Qt, QTimer, pyqtSignal
from PyQt6.QtGui import QFont, QKeySequence, QShortcut, QColor
from PyQt6.QtWebEngineWidgets import QWebEngineView

import plotly.graph_objects as go
import plotly.io as pio
from plotly.subplots import make_subplots

from okx import MarketData, Account, Funding, Trade, PublicData

# ==================== 常量 ====================
CONFIG_FILE = Path(__file__).parent / "okx_config.json"
ENV_FILE    = Path(__file__).parent / ".env"

COMMON_PAIRS = [
    "BTC-USDT", "ETH-USDT", "PAXG-USDT", "SOL-USDT", "BNB-USDT",
    "XRP-USDT", "DOGE-USDT", "ADA-USDT", "AVAX-USDT", "LINK-USDT",
    "DOT-USDT", "MATIC-USDT", "TRX-USDT", "LTC-USDT", "ATOM-USDT",
]
BAR_OPTIONS  = ["1m", "5m", "15m", "30m", "1H", "4H", "1D", "1W", "1M"]
REFRESH_OPTS = ["关闭", "5s", "10s", "15s", "30s", "60s"]
REFRESH_MAP  = {"5s": 5000, "10s": 10000, "15s": 15000,
                "30s": 30000, "60s": 60000, "关闭": 0}

# 单次 REST 请求的连接/读取超时（秒）— 国内网络到 OKX 的 TLS 握手可能很慢
REST_CONNECT_TIMEOUT = 15.0
REST_READ_TIMEOUT    = 20.0
REST_MAX_RETRIES     = 3   # 遇到 ConnectTimeout/ReadTimeout 时的最大重试次数

# ==================== 自动交易 ====================
# key: 内部 id, value: (界面标签, tooltip)
STRATEGY_LABELS = [
    ("auto",         "自动 (多指标共识)"),
    ("magic",        "隐秘枢轴 3K"),
    ("ma",           "MA 金叉/死叉"),
    ("macd",         "MACD 柱变向"),
    ("rsi",          "RSI 超买超卖反转"),
    ("kdj",          "KDJ 交叉"),
    ("bollinger",    "布林带 (Bollinger Bands)"),
    ("atr_breakout", "ATR 突破波动策略"),
    ("grid",         "网格交易 (Grid Trading)"),
    ("consensus",    "多指标共识 (严格共识)"),
]
STRATEGY_ID_BY_LABEL = {lbl: k for k, lbl in STRATEGY_LABELS}
STRATEGY_LABEL_BY_ID = {k: lbl for k, lbl in STRATEGY_LABELS}

MGN_MODES = [("isolated", "逐仓 isolated"), ("cross", "全仓 cross")]
POS_SIDES = [("long", "多 long"), ("short", "空 short"), ("net", "净 net")]
TRADE_KINDS = [("spot", "现货 SPOT"), ("swap", "合约 SWAP")]
SIZE_TYPES  = [("amount", "固定数量"), ("pct", "余额百分比")]

# ==================== 配色 ====================
C_BG        = "#0d1117"
C_BG2       = "#161b22"
C_CARD      = "#1c2333"
C_SURFACE   = "#242d3f"
C_BORDER    = "#2d3748"
C_BORDER_L  = "#3b4456"

C_PRIMARY   = "#3b82f6"
C_PRIMARY_D = "#2563eb"
C_PRIMARY_L = "#60a5fa"

# 具体颜色（红/绿），语义映射（涨=up/跌=down）在运行时决定
C_RED       = "#ef4444"
C_RED_BR    = "#f87171"
C_GREEN     = "#22c55e"
C_GREEN_BR  = "#4ade80"
# 默认："绿涨红跌"（国际惯例）；可切换为"红涨绿跌"（中国惯例）
C_UP        = C_GREEN
C_UP_BR     = C_GREEN_BR
C_DOWN      = C_RED
C_DOWN_BR   = C_RED_BR

C_TEXT      = "#e2e8f0"
C_TEXT_S    = "#94a3b8"
C_TEXT_D    = "#64748b"
C_WHITE     = "#f8fafc"
C_WARN      = "#f59e0b"

MA_COLORS   = ["#f59e0b", "#60a5fa", "#a78bfa", "#f472b6"]

# ==================== QSS ====================
def build_qss(c_up, c_up_br, c_down, c_down_br):
    return f"""
* {{ font-family: "Microsoft YaHei", "Segoe UI", sans-serif; }}

QMainWindow, QWidget {{
    background: {C_BG};
    color: {C_TEXT};
}}

QFrame#Card {{
    background: {C_BG2};
    border-radius: 10px;
}}
QFrame#HeaderBar {{
    background: {C_BG2};
    border-bottom: 1px solid {C_BORDER};
}}
QFrame#Separator {{
    background: {C_BORDER};
    max-width: 1px;
    min-width: 1px;
}}

QLabel {{ color: {C_TEXT}; background: transparent; }}
QLabel#Brand {{ color: {C_PRIMARY_L}; font-size: 16px; font-weight: bold; }}
QLabel#BrandSub {{ color: {C_TEXT}; font-size: 13px; }}
QLabel#PairName {{ color: {C_TEXT}; font-size: 13px; font-weight: bold; }}
QLabel#PriceUp       {{ color: {c_up};    font-family: Consolas; font-size: 18px; font-weight: bold; }}
QLabel#PriceDown     {{ color: {c_down};  font-family: Consolas; font-size: 18px; font-weight: bold; }}
QLabel#PriceNeutral  {{ color: {C_TEXT};  font-family: Consolas; font-size: 18px; font-weight: bold; }}
QLabel#ChangeUp      {{ color: {c_up_br};   font-family: Consolas; font-size: 12px; font-weight: bold; }}
QLabel#ChangeDown    {{ color: {c_down_br}; font-family: Consolas; font-size: 12px; font-weight: bold; }}
QLabel#ChangeNeutral {{ color: {C_TEXT_S};  font-family: Consolas; font-size: 12px; font-weight: bold; }}
QLabel#BadgeSim {{
    color: {C_GREEN_BR}; background: rgba(34,197,94,0.12);
    border: 1px solid {C_GREEN}; border-radius: 4px; padding: 2px 8px;
    font-size: 11px; font-weight: bold;
}}
QLabel#BadgeReal {{
    color: {C_RED_BR}; background: rgba(239,68,68,0.12);
    border: 1px solid {C_RED}; border-radius: 4px; padding: 2px 8px;
    font-size: 11px; font-weight: bold;
}}
QLabel#DotOn  {{ color: {C_GREEN_BR}; font-size: 14px; }}
QLabel#DotOff {{ color: {C_RED_BR};   font-size: 14px; }}
QLabel#FieldLabel {{ color: {C_TEXT_S}; font-size: 8pt; }}
QLabel#Hint {{ color: {C_TEXT_D}; font-style: italic; font-size: 9pt; }}
QLabel#Warn {{ color: {C_WARN}; font-size: 9pt; font-weight: bold; }}
QLabel#SectionTitle {{ color: {C_TEXT}; font-size: 11pt; font-weight: bold; }}
QLabel#Sep {{ color: {C_BORDER_L}; }}

QLineEdit {{
    background: {C_CARD};
    color: {C_TEXT};
    border: 1px solid {C_BORDER};
    border-radius: 6px;
    padding: 4px 8px;
    min-height: 28px;
    selection-background-color: {C_PRIMARY};
}}
QLineEdit:focus {{ border: 1px solid {C_PRIMARY}; }}
QLineEdit:disabled {{ color: {C_TEXT_D}; }}

QComboBox {{
    background: {C_CARD};
    color: {C_TEXT};
    border: 1px solid {C_BORDER};
    border-radius: 6px;
    padding: 2px 8px;
    min-height: 26px;
}}
QComboBox:focus {{ border: 1px solid {C_PRIMARY}; }}
QComboBox::drop-down {{
    subcontrol-origin: padding; subcontrol-position: center right;
    width: 22px; border: none;
}}
QComboBox::down-arrow {{
    width: 0; height: 0;
    border-left: 4px solid transparent;
    border-right: 4px solid transparent;
    border-top: 5px solid {C_TEXT_S};
    margin-right: 6px;
}}
QComboBox QAbstractItemView {{
    background: {C_CARD};
    color: {C_TEXT};
    border: 1px solid {C_BORDER_L};
    selection-background-color: {C_PRIMARY};
    selection-color: {C_WHITE};
    outline: 0;
    padding: 2px;
}}

QPushButton {{
    background: {C_SURFACE};
    color: {C_TEXT};
    border: 1px solid {C_BORDER};
    border-radius: 6px;
    padding: 8px 16px;
    font-weight: 500;
}}
QPushButton:hover  {{ background: {C_BORDER_L}; border-color: {C_BORDER_L}; }}
QPushButton:pressed {{ background: {C_BORDER}; }}
QPushButton:disabled {{ color: {C_TEXT_D}; background: {C_BG2}; }}

QPushButton#Primary {{
    background: {C_PRIMARY}; color: {C_WHITE};
    border: 1px solid {C_PRIMARY}; font-weight: bold;
}}
QPushButton#Primary:hover  {{ background: {C_PRIMARY_L}; }}
QPushButton#Primary:pressed {{ background: {C_PRIMARY_D}; }}

QPushButton#Danger {{
    background: {C_RED}; color: {C_WHITE};
    border: 1px solid {C_RED}; font-weight: bold;
}}
QPushButton#Danger:hover  {{ background: {C_RED_BR}; }}
QPushButton#Danger:pressed {{ background: {C_RED}; }}

QPushButton#Ghost {{
    background: transparent; color: {C_TEXT_S};
    border: 1px solid {C_BORDER_L};
}}
QPushButton#Ghost:hover {{ color: {C_TEXT}; border-color: {C_PRIMARY_L}; }}

QCheckBox, QRadioButton {{ color: {C_TEXT}; spacing: 6px; background: transparent; }}
QCheckBox::indicator {{
    width: 16px; height: 16px;
    background: {C_CARD};
    border: 1px solid {C_BORDER_L}; border-radius: 3px;
}}
QCheckBox::indicator:checked {{ background: {C_PRIMARY}; border-color: {C_PRIMARY}; }}
QRadioButton::indicator {{
    width: 14px; height: 14px;
    background: {C_CARD};
    border: 1px solid {C_BORDER_L}; border-radius: 7px;
}}
QRadioButton::indicator:checked {{ background: {C_PRIMARY}; border: 4px solid {C_CARD}; }}

QTabWidget::pane {{
    background: {C_BG2}; border: none; border-radius: 8px;
    top: -1px;
}}
QTabBar {{ background: transparent; }}
QTabBar::tab {{
    background: transparent; color: {C_TEXT_D};
    padding: 8px 18px; font-size: 10pt; font-weight: 500;
    border-bottom: 2px solid transparent;
}}
QTabBar::tab:hover    {{ color: {C_TEXT}; }}
QTabBar::tab:selected {{ color: {C_PRIMARY_L}; border-bottom: 2px solid {C_PRIMARY}; }}

QTextEdit {{
    background: {C_BG2};
    color: {C_TEXT};
    border: 1px solid {C_BORDER};
    border-radius: 6px;
    font-family: Consolas, "Courier New", monospace;
    font-size: 9pt;
    padding: 6px;
}}

QSplitter::handle {{ background: {C_BORDER}; }}
QSplitter::handle:vertical   {{ height: 1px; }}
QSplitter::handle:horizontal {{ width: 1px; }}

QScrollBar:vertical {{
    background: transparent; width: 8px; margin: 0;
}}
QScrollBar::handle:vertical {{
    background: {C_BORDER_L}; border-radius: 4px; min-height: 24px;
}}
QScrollBar::handle:vertical:hover {{ background: {C_PRIMARY_L}; }}
QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical {{ height: 0; }}
QScrollBar:horizontal {{
    background: transparent; height: 8px; margin: 0;
}}
QScrollBar::handle:horizontal {{
    background: {C_BORDER_L}; border-radius: 4px; min-width: 24px;
}}
QScrollBar::add-line:horizontal, QScrollBar::sub-line:horizontal {{ height: 0; }}

QMessageBox {{ background: {C_BG2}; }}
QMessageBox QLabel {{ color: {C_TEXT}; }}
"""


# ==================== 工具 ====================
def calc_ma(closes, period):
    """简单移动平均"""
    ma = [0.0] * len(closes)
    if period <= 0 or len(closes) == 0:
        return ma
    s = 0.0
    for i in range(len(closes)):
        s += closes[i]
        if i >= period:
            s -= closes[i - period]
        if i >= period - 1:
            ma[i] = s / period
    return ma


def calc_ema(values, period):
    """指数移动平均。返回长度与 values 相同，前 period-1 项用 SMA 初始化。"""
    n = len(values)
    ema = [0.0] * n
    if n == 0 or period <= 0:
        return ema
    k = 2.0 / (period + 1.0)
    # SMA 初始化
    s = 0.0
    for i in range(min(period, n)):
        s += values[i]
        if i < period - 1:
            ema[i] = 0.0  # 未定义区，主调用方按需过滤
        else:
            ema[i] = s / period
    for i in range(period, n):
        ema[i] = values[i] * k + ema[i - 1] * (1 - k)
    return ema


def calc_macd(closes, fast=12, slow=26, signal=9):
    """MACD：返回 (dif, dea, hist)，各长度=len(closes)，前若干项为 0（未定义区）"""
    n = len(closes)
    if n == 0:
        return [], [], []
    ema_fast = calc_ema(closes, fast)
    ema_slow = calc_ema(closes, slow)
    dif = [0.0] * n
    for i in range(n):
        if i >= slow - 1:
            dif[i] = ema_fast[i] - ema_slow[i]
    # DEA = EMA(DIF, signal)；从 slow-1 开始有效
    dea = [0.0] * n
    if n > slow - 1:
        # 只对有效段跑 EMA
        seg = dif[slow - 1:]
        seg_ema = calc_ema(seg, signal)
        for j, v in enumerate(seg_ema):
            dea[slow - 1 + j] = v
    hist = [0.0] * n
    for i in range(n):
        hist[i] = (dif[i] - dea[i]) * 2  # MACD 柱通常乘 2
    return dif, dea, hist


def calc_rsi(closes, period=14):
    """RSI (Wilder 平滑)：返回长度=len(closes)，前 period 项为 0（未定义）"""
    n = len(closes)
    rsi = [0.0] * n
    if n < period + 1:
        return rsi
    gain = 0.0
    loss = 0.0
    for i in range(1, period + 1):
        ch = closes[i] - closes[i - 1]
        if ch >= 0:
            gain += ch
        else:
            loss -= ch
    avg_gain = gain / period
    avg_loss = loss / period
    if avg_loss == 0:
        rsi[period] = 100.0
    else:
        rs = avg_gain / avg_loss
        rsi[period] = 100.0 - 100.0 / (1 + rs)
    for i in range(period + 1, n):
        ch = closes[i] - closes[i - 1]
        g = ch if ch > 0 else 0.0
        l = -ch if ch < 0 else 0.0
        avg_gain = (avg_gain * (period - 1) + g) / period
        avg_loss = (avg_loss * (period - 1) + l) / period
        if avg_loss == 0:
            rsi[i] = 100.0
        else:
            rs = avg_gain / avg_loss
            rsi[i] = 100.0 - 100.0 / (1 + rs)
    return rsi


def calc_kdj(highs, lows, closes, k_period=9, k_smooth=3, d_smooth=3):
    """
    KDJ：
      RSV = (close - LLV(low, N)) / (HHV(high, N) - LLV(low, N)) * 100
      K   = SMA_2/3(RSV, prev_K)   (即 K = 2/3 * prev_K + 1/3 * RSV，中式)
      D   = SMA_2/3(K, prev_D)
      J   = 3 * K - 2 * D
    返回 (K, D, J)，长度=len(closes)，前 k_period-1 项为 0
    """
    n = len(closes)
    K = [0.0] * n
    D = [0.0] * n
    J = [0.0] * n
    if n < k_period:
        return K, D, J
    prev_k = 50.0
    prev_d = 50.0
    ks = 1.0 / k_smooth
    ds = 1.0 / d_smooth
    for i in range(n):
        if i < k_period - 1:
            continue
        lo = min(lows[i - k_period + 1: i + 1])
        hi = max(highs[i - k_period + 1: i + 1])
        rng = hi - lo
        rsv = 50.0 if rng == 0 else (closes[i] - lo) / rng * 100.0
        cur_k = prev_k * (1 - ks) + rsv * ks
        cur_d = prev_d * (1 - ds) + cur_k * ds
        cur_j = 3 * cur_k - 2 * cur_d
        K[i] = cur_k; D[i] = cur_d; J[i] = cur_j
        prev_k = cur_k; prev_d = cur_d
    return K, D, J


def calc_bollinger(closes, period=20, k=2.0):
    """
    布林带：中轨 SMA(period)，上轨 = 中轨 + k * std，下轨 = 中轨 - k * std
    返回 (mid, upper, lower)，长度为 len(closes)
    """
    n = len(closes)
    mid = [0.0] * n
    upper = [0.0] * n
    lower = [0.0] * n
    if n < period or period <= 0:
        return mid, upper, lower
    for i in range(period - 1, n):
        seg = closes[i - period + 1 : i + 1]
        m = sum(seg) / float(period)
        var = sum((x - m) ** 2 for x in seg) / float(period)
        std = math.sqrt(var)
        mid[i] = m
        upper[i] = m + k * std
        lower[i] = m - k * std
    return mid, upper, lower


def calc_atr(highs, lows, closes, period=14):
    """
    真实波动幅度均幅 (ATR)
    TR = max(high - low, abs(high - prev_close), abs(low - prev_close))
    返回 length=len(closes) 的 ATR 列表
    """
    n = len(closes)
    atr = [0.0] * n
    if n < period + 1 or not highs or not lows:
        return atr
    tr = [0.0] * n
    tr[0] = highs[0] - lows[0]
    for i in range(1, n):
        h = highs[i]
        l = lows[i]
        pc = closes[i - 1]
        tr[i] = max(h - l, abs(h - pc), abs(l - pc))
    first_atr = sum(tr[1:period+1]) / float(period)
    atr[period] = first_atr
    for i in range(period + 1, n):
        atr[i] = (atr[i - 1] * (period - 1) + tr[i]) / float(period)
    return atr


# ================= 指标裁定：返回 +1(看多) / -1(看空) / 0(中性) =================

def verdict_ma(closes, short=5, long=20):
    """
    MA 均线裁定 (优化盈亏比)：
    仅在金叉突破（+1）或死叉突破（-1）时触发信号；静态缠绕或多头/空头排列不重复开仓
    """
    if len(closes) < long + 2:
        return 0
    ma_s = calc_ma(closes, short)
    ma_l = calc_ma(closes, long)
    s0, l0 = ma_s[-1], ma_l[-1]
    s1, l1 = ma_s[-2], ma_l[-2]
    if s0 == 0 or l0 == 0 or s1 == 0 or l1 == 0:
        return 0
    # 金叉 (Golden Cross)
    if s1 <= l1 and s0 > l0:
        return 1
    # 死叉 (Death Cross)
    if s1 >= l1 and s0 < l0:
        return -1
    return 0


def verdict_macd(closes):
    """
    MACD 裁定 (优化盈亏比)：
    优先识别 DIF/DEA 金叉死叉与柱状图动能加速度，无明确突破时返回 0
    """
    if len(closes) < 35:
        return 0
    dif, dea, hist = calc_macd(closes)
    if len(dif) < 3 or len(hist) < 3:
        return 0
    # DIF / DEA 金叉与死叉
    if dif[-2] <= dea[-2] and dif[-1] > dea[-1]:
        return 1
    if dif[-2] >= dea[-2] and dif[-1] < dea[-1]:
        return -1
    # 柱状图动能连续增强
    if hist[-1] > 0 and hist[-1] > hist[-2] > hist[-3]:
        return 1
    if hist[-1] < 0 and hist[-1] < hist[-2] < hist[-3]:
        return -1
    return 0


def verdict_rsi(closes, period=14, upper=70.0, lower=30.0):
    """
    RSI 裁定 (优化盈亏比)：
    - 引入 45~55 震荡中枢缓冲带（返回 0 避免无方向噪音）
    - 超卖 (<=lower) 且反弹 -> 看多 (+1)
    - 超买 (>=upper) 且回调 -> 看空 (-1)
    - 突破 55 以上且 RSI 上升 -> 动能看多 (+1)
    - 跌破 45 以下且 RSI 下降 -> 动能看空 (-1)
    """
    rsi = calc_rsi(closes, period)
    if not rsi or len(rsi) < 2 or rsi[-1] == 0:
        return 0
    v0, v1 = rsi[-1], rsi[-2]
    if v0 <= lower:
        return 1    # 超卖反弹看多
    if v0 >= upper:
        return -1   # 超买回调看空
    if 45.0 <= v0 <= 55.0:
        return 0    # 中枢震荡，观望
    if v0 > 55.0 and v0 > v1:
        return 1    # 多头动能增强
    if v0 < 45.0 and v0 < v1:
        return -1   # 空头动能增强
    return 0


def verdict_kdj(highs, lows, closes):
    """
    KDJ 裁定：
    K/D 交叉且 J 线拐头确认
    """
    K, D, J = calc_kdj(highs, lows, closes)
    if len(K) < 2 or (K[-1] == 0 and D[-1] == 0):
        return 0
    # 金叉 + J 向上
    if K[-2] <= D[-2] and K[-1] > D[-1] and J[-1] > J[-2]:
        return 1
    # 死叉 + J 向下
    if K[-2] >= D[-2] and K[-1] < D[-1] and J[-1] < J[-2]:
        return -1
    return 0


def verdict_bollinger(closes, period=20, k=2.0):
    """
    布林带裁定 (优化盈亏比)：
    - 结合中轨 (20SMA) 趋势方向
    - 中轨向上或平缓时，下轨反弹 -> 买入 (+1)
    - 中轨向下或平缓时，上轨回调 -> 卖出 (-1)
    - 避免强下跌趋势中盲目下轨接飞刀
    """
    if len(closes) < period + 3:
        return 0
    mid, upper, lower = calc_bollinger(closes, period, k)
    c0, c1 = closes[-1], closes[-2]
    u0, u1 = upper[-1], upper[-2]
    l0, l1 = lower[-1], lower[-2]
    m0, m2 = mid[-1], mid[-3]
    if l0 == 0 or u0 == 0:
        return 0
    
    mid_slope_up = (m0 >= m2)
    mid_slope_dn = (m0 <= m2)

    if (c1 <= l1 and c0 > l0) or (c0 <= l0):
        if mid_slope_up:
            return 1
    if (c1 >= u1 and c0 < u0) or (c0 >= u0):
        if mid_slope_dn:
            return -1
    return 0


def verdict_atr_breakout(highs, lows, closes, period=14, mult=1.5):
    """
    ATR 突破判定：如果价格突破 前高 + mult * ATR -> 看多 (+1)，或突破 前低 - mult * ATR -> 看空 (-1)
    """
    if len(closes) < period + 2 or not highs or not lows:
        return 0
    atr = calc_atr(highs, lows, closes, period)
    if atr[-1] == 0:
        return 0
    ma20 = calc_ma(closes, 20)
    dev = mult * atr[-1]
    c = closes[-1]
    m = ma20[-1]
    if m == 0:
        return 0
    if c > m + dev:
        return 1
    if c < m - dev:
        return -1
    return 0


def parse_ma_periods(text):
    """解析 'MA5,MA20' → [5, 20]"""
    periods = []
    for tok in (text or "").replace("，", ",").split(","):
        tok = tok.strip().upper().replace("MA", "")
        if tok.isdigit():
            n = int(tok)
            if n > 0:
                periods.append(n)
    return periods


def fmt_num(v, digits=2):
    try:
        return f"{float(v):,.{digits}f}"
    except Exception:
        return str(v)


def now_hms():
    return datetime.now().strftime("%H:%M:%S")


# ==================== 自动交易引擎 ====================
SIM_STATE_FILE      = Path(__file__).parent / "okx_sim_account.json"
ORDERS_HISTORY_FILE = Path(__file__).parent / "okx_orders_history.json"
DEFAULT_SIM_BALANCE = 1000.0


def load_orders_history():
    """从 okx_orders_history.json 加载历史订单记录"""
    if not ORDERS_HISTORY_FILE.exists():
        return []
    try:
        data = json.loads(ORDERS_HISTORY_FILE.read_text(encoding="utf-8"))
        if isinstance(data, list):
            return data
    except Exception:
        pass
    return []


def save_order_history_item(ev):
    """保存一条订单记录到 okx_orders_history.json 本地持久化文件"""
    try:
        history = load_orders_history()
        history.insert(0, ev)
        history = history[:500]  # 最多保留 500 条
        ORDERS_HISTORY_FILE.write_text(
            json.dumps(history, indent=2, ensure_ascii=False),
            encoding="utf-8"
        )
    except Exception:
        pass

# 策略下拉里额外的"系统自选"选项（内部映射到 "auto"）
STRATEGY_LABELS_UI = [("system", "系统自选 (推荐)")] + STRATEGY_LABELS


def pick_strategy_signal(strategy_id, closes, highs=None, lows=None, params=None):
    """
    根据策略 id 及可选自定义参数 params 返回交易信号：+1 买入 / -1 卖出 / 0 观望
    - system/auto/magic：基于 EMA50 大趋势过滤 + 顺势多指标共振 (极佳盈亏比)
    - consensus       ：严格共识
    - ma/macd/rsi/kdj/bollinger/atr_breakout ：单指标裁定
    - grid            ：由 AutoTrader 内置网格计算处理
    """
    params = params or {}
    sid = (strategy_id or "").lower()
    if sid == "system":
        sid = "auto"

    ma_s = int(params.get("ma_short", 5))
    ma_l = int(params.get("ma_long", 20))
    rsi_p = int(params.get("rsi_period", 14))
    rsi_u = float(params.get("rsi_upper", 70.0))
    rsi_d = float(params.get("rsi_lower", 30.0))

    if sid in ("auto", "magic", "consensus"):
        votes = [
            verdict_ma(closes, short=ma_s, long=ma_l),
            verdict_macd(closes),
            verdict_rsi(closes, period=rsi_p, upper=rsi_u, lower=rsi_d),
            verdict_bollinger(closes),
        ]
        if highs and lows:
            votes.append(verdict_kdj(highs, lows, closes))

        buy  = sum(1 for v in votes if v > 0)
        sell = sum(1 for v in votes if v < 0)

        if sid == "consensus":
            if buy == len(votes):  return 1
            if sell == len(votes): return -1
            return 0

        # 大趋势过滤器 (EMA50/MA50)
        period_trend = min(50, len(closes) - 1)
        if period_trend >= 10:
            trend_ma = calc_ma(closes, period_trend)
            macro_trend = 1 if closes[-1] > trend_ma[-1] else (-1 if closes[-1] < trend_ma[-1] else 0)
        else:
            macro_trend = 0

        # 顺势共振开仓：顺主趋势只需 2 个指标看多/看空，逆主趋势需要至少 3 个指标强共振
        if buy >= 2 and buy > sell:
            if macro_trend >= 0 or buy >= 3:
                return 1
        if sell >= 2 and sell > buy:
            if macro_trend <= 0 or sell >= 3:
                return -1
        return 0

    if sid == "ma":
        return verdict_ma(closes, short=ma_s, long=ma_l)
    if sid == "macd":
        return verdict_macd(closes)
    if sid == "rsi":
        return verdict_rsi(closes, period=rsi_p, upper=rsi_u, lower=rsi_d)
    if sid == "kdj":
        return verdict_kdj(highs, lows, closes) if (highs and lows) else 0
    if sid == "bollinger":
        return verdict_bollinger(closes)
    if sid == "atr_breakout":
        return verdict_atr_breakout(highs, lows, closes) if (highs and lows) else 0
    return 0


class SimulationAccount:
    """
    模拟账户：默认赠送 1000 USDT，可重置或自定义金额。
    记录余额、已实现盈亏、最高余额、最大回撤、连亏次数及盈亏比。
    状态持久化到 okx_sim_account.json。
    """
    def __init__(self, file_path=SIM_STATE_FILE):
        self.file             = Path(file_path)
        self.balance          = DEFAULT_SIM_BALANCE
        self.initial_balance  = DEFAULT_SIM_BALANCE
        self.peak_balance     = DEFAULT_SIM_BALANCE
        self.max_drawdown     = 0.0
        self.realized_pnl     = 0.0
        self.total_win_pnl    = 0.0
        self.total_loss_pnl   = 0.0
        self.trades           = 0
        self.wins             = 0
        self.consecutive_losses = 0
        self._lock            = threading.Lock()
        self.load()

    def load(self):
        if not self.file.exists():
            return
        try:
            d = json.loads(self.file.read_text(encoding="utf-8"))
            self.balance            = float(d.get("balance", DEFAULT_SIM_BALANCE))
            self.initial_balance    = float(d.get("initial_balance", DEFAULT_SIM_BALANCE))
            self.peak_balance       = float(d.get("peak_balance", self.balance))
            self.max_drawdown       = float(d.get("max_drawdown", 0.0))
            self.realized_pnl       = float(d.get("realized_pnl", 0.0))
            self.total_win_pnl      = float(d.get("total_win_pnl", 0.0))
            self.total_loss_pnl     = float(d.get("total_loss_pnl", 0.0))
            self.trades             = int(d.get("trades", 0))
            self.wins               = int(d.get("wins", 0))
            self.consecutive_losses = int(d.get("consecutive_losses", 0))
        except Exception:
            pass

    def save(self):
        try:
            self.file.write_text(json.dumps({
                "balance":            self.balance,
                "initial_balance":    self.initial_balance,
                "peak_balance":       self.peak_balance,
                "max_drawdown":       self.max_drawdown,
                "realized_pnl":       self.realized_pnl,
                "total_win_pnl":      self.total_win_pnl,
                "total_loss_pnl":     self.total_loss_pnl,
                "trades":             self.trades,
                "wins":               self.wins,
                "consecutive_losses": self.consecutive_losses,
            }, indent=2, ensure_ascii=False), encoding="utf-8")
        except Exception:
            pass

    def reset(self, amount=None):
        with self._lock:
            if amount is None:
                amount = self.initial_balance
            else:
                self.initial_balance = float(amount)
            self.balance            = float(amount)
            self.peak_balance       = float(amount)
            self.max_drawdown       = 0.0
            self.realized_pnl       = 0.0
            self.total_win_pnl      = 0.0
            self.total_loss_pnl     = 0.0
            self.trades             = 0
            self.wins               = 0
            self.consecutive_losses = 0
            self.save()

    def debit(self, amount):
        """扣款（开仓保证金）。返回 True 成功。"""
        with self._lock:
            if amount > self.balance:
                return False
            self.balance -= amount
            self.save()
            return True

    def credit(self, amount, realized=0.0):
        """回款（平仓收回保证金+盈亏）。"""
        with self._lock:
            self.balance      += amount
            self.realized_pnl += realized
            self.trades       += 1
            if realized > 0:
                self.wins += 1
                self.total_win_pnl += realized
                self.consecutive_losses = 0
            else:
                self.total_loss_pnl += abs(realized)
                self.consecutive_losses += 1

            if self.balance > self.peak_balance:
                self.peak_balance = self.balance
            elif self.peak_balance > 0:
                dd = (self.peak_balance - self.balance) / self.peak_balance * 100.0
                if dd > self.max_drawdown:
                    self.max_drawdown = dd
            self.save()

    @property
    def profit_factor(self):
        if self.total_loss_pnl == 0:
            return self.total_win_pnl if self.total_win_pnl > 0 else 0.0
        return self.total_win_pnl / self.total_loss_pnl


class AutoTrader(threading.Thread):
    """
    自动交易线程：按周期轮询 K 线 → 生成信号 → 开/平仓，支持止盈止损。
    - 支持现货(spot) / 合约(swap)
    - 支持模拟(不走 OKX) / 实盘
    - 支持手动停止（stop_event）
    """
    def __init__(self, window, cfg):
        super().__init__(daemon=True)
        self.window     = window
        self.cfg        = dict(cfg)
        self.stop_event = threading.Event()
        self.manual_close_event = threading.Event()   # UI 触发的手动平仓请求
        # 单币对单仓位模型
        self.position   = None       # {"side": "long|short", "size": float, "entry": float, "margin": float}
        self._last_px   = None

    # ---- 控制 ----
    def stop(self):
        self.stop_event.set()

    def request_manual_close(self):
        """UI 线程调用；主循环下一次心跳时检测并以市价平仓（跨线程只用 Event 通信）"""
        self.manual_close_event.set()

    def is_running(self):
        return self.is_alive() and not self.stop_event.is_set()

    # ---- 工具 ----
    def _log(self, msg, tag="info"):
        try:
            self.window.log_signal.emit(f"[自动交易] {msg}", tag)
        except Exception:
            pass

    def _emit_state(self):
        try:
            acct = self.window._sim_account
            self.window.auto_state_signal.emit({
                "running":      not self.stop_event.is_set() and self.is_alive(),
                "position":     dict(self.position) if self.position else None,
                "last_px":      self._last_px,
                "sim":          bool(self.cfg.get("simulate", True)),
                "balance":      acct.balance,
                "realized":     acct.realized_pnl,
                "trades":       acct.trades,
                "wins":         acct.wins,
                "max_drawdown": acct.max_drawdown,
                "profit_factor": acct.profit_factor,
                "consecutive_losses": acct.consecutive_losses,
                "initial":      acct.initial_balance,
                "inst":         self.cfg.get("inst"),
                "kind":         self.cfg.get("kind"),
                "strategy":     self.cfg.get("strategy"),
            })
        except Exception:
            pass

    def _emit_order(self, **fields):
        try:
            fields.setdefault("ts", datetime.now().strftime("%Y-%m-%d %H:%M:%S"))
            fields.setdefault("inst",     self.cfg.get("inst"))
            fields.setdefault("kind",     self.cfg.get("kind"))
            fields.setdefault("simulate", bool(self.cfg.get("simulate", True)))
            self.window.auto_order_signal.emit(fields)
        except Exception:
            pass

    def _fetch_candles(self, inst, bar, limit=200):
        try:
            api = self.window._get_market_api()
            r = api.get_candlesticks(instId=inst, bar=bar, limit=str(limit))
        except Exception as e:
            self._log(f"K线请求异常: {e}", "warn")
            return None
        if not isinstance(r, dict) or r.get("code") not in ("0", 0):
            return None
        raw = list(r.get("data") or [])
        raw.reverse()
        highs, lows, closes = [], [], []
        for it in raw:
            try:
                highs.append(float(it[2]))
                lows.append(float(it[3]))
                closes.append(float(it[4]))
            except Exception:
                continue
        return highs, lows, closes

    def _last_price(self, inst):
        try:
            r = self.window._get_market_api().get_ticker(instId=inst)
            if isinstance(r, dict) and r.get("code") in ("0", 0):
                data = r.get("data") or []
                if data:
                    return float(data[0].get("last"))
        except Exception:
            pass
        return None

    def _size_for(self, price):
        cfg = self.cfg
        try:
            v = float(cfg.get("size_value", 0.0) or 0.0)
        except Exception:
            v = 0.0
        if cfg.get("size_type") == "amount":
            return v
        if cfg.get("simulate", True):
            bal = self.window._sim_account.balance
        else:
            try:
                bal = float(cfg.get("ref_balance", 0.0) or 0.0)
            except Exception:
                bal = 0.0
        pct = v / 100.0
        lev = 1.0
        if cfg.get("kind") == "swap":
            try:
                lev = max(1.0, float(cfg.get("leverage", 1.0)))
            except Exception:
                lev = 1.0
        notional = bal * pct * lev
        if price <= 0:
            return 0.0
        return notional / price

    def _resolve_pos_side(self, open_side):
        if self.cfg.get("kind") != "swap":
            return None
        cfg_ps = self.cfg.get("pos_side", "auto")
        if cfg_ps == "auto":
            return "long" if open_side == "long" else "short"
        return cfg_ps

    def _place_real(self, side, size, closing=False, pos_side=None):
        cfg = self.cfg
        params = dict(instId=cfg["inst"], side=side, ordType="market", sz=str(size))
        if cfg.get("kind") == "spot":
            params["tdMode"] = "cash"
        else:
            params["tdMode"] = cfg.get("mgn_mode", "isolated")
            ps = pos_side if pos_side is not None else cfg.get("pos_side", "net")
            if ps and ps not in ("net", "auto"):
                params["posSide"] = ps
        try:
            r = self.window._get_trade_api().place_order(**params)
            self.window.result_signal.emit(
                f"[自动交易] {'平仓' if closing else '开仓'} {cfg['inst']} {side}", r)
            if not (isinstance(r, dict) and r.get("code") in ("0", 0)):
                self._log(f"下单失败: {r}", "err")
                return False
        except Exception as e:
            self._log(f"下单异常: {e}", "err")
            return False
        return True

    # ---- 开/平仓 ----
    def _open(self, side, price):
        cfg = self.cfg
        size = self._size_for(price)
        if size <= 0:
            self._log("计算得下单数量为 0，跳过", "warn")
            return
        notional = size * price
        if cfg.get("kind") == "swap":
            try:
                lev = max(1.0, float(cfg.get("leverage", 1.0)))
            except Exception:
                lev = 1.0
            margin = notional / lev
        else:
            margin = notional
            if side == "short":
                self._log("现货不支持做空，忽略卖出信号", "warn")
                return

        if cfg.get("simulate", True):
            if not self.window._sim_account.debit(margin):
                self._log(
                    f"模拟余额不足（需 {margin:.4f} USDT，余 "
                    f"{self.window._sim_account.balance:.4f}），自动停止",
                    "err")
                self.stop()
                return
            self.position = {
                "side": side, "size": size, "entry": price, "margin": margin,
                "peak_price": price, "trough_price": price, "breakeven_active": False
            }
            self._log(
                f"[模拟] 开仓 {side.upper()} @ {price:.6g} 数量={size:.6g} "
                f"名义={notional:.2f} 保证金={margin:.2f}", "ok")
        else:
            real_side = "buy" if side == "long" else "sell"
            ps = self._resolve_pos_side(open_side=side)
            if not self._place_real(real_side, size, closing=False, pos_side=ps):
                return
            self.position = {
                "side": side, "size": size, "entry": price, "margin": margin,
                "peak_price": price, "trough_price": price, "breakeven_active": False
            }
            self._log(
                f"[实盘] 开仓 {side.upper()} @ {price:.6g} 数量={size:.6g}", "ok")
        self._emit_order(action="OPEN", side=side, size=size, price=price,
                         margin=margin, notional=notional, pnl=None, reason="",
                         entry=price)
        self._emit_state()

    def _close(self, price, reason=""):
        pos = self.position
        if pos is None:
            return
        pnl = (price - pos["entry"]) * pos["size"] * (1 if pos["side"] == "long" else -1)
        if self.cfg.get("simulate", True):
            self.window._sim_account.credit(pos["margin"] + pnl, realized=pnl)
            tag = "ok" if pnl >= 0 else "warn"
            self._log(
                f"[模拟] 平仓 @ {price:.6g} PnL={pnl:+.2f} USDT ({reason}) "
                f"余额={self.window._sim_account.balance:.2f}", tag)
        else:
            real_side = "sell" if pos["side"] == "long" else "buy"
            ps = self._resolve_pos_side(open_side=pos["side"])
            self._place_real(real_side, pos["size"], closing=True, pos_side=ps)
            self._log(
                f"[实盘] 平仓 @ {price:.6g} 估算 PnL={pnl:+.2f} USDT ({reason})",
                "ok" if pnl >= 0 else "warn")
        self._emit_order(action="CLOSE", side=pos["side"], size=pos["size"], price=price,
                         margin=pos["margin"], notional=pos["size"] * price,
                         pnl=pnl, reason=reason, entry=pos["entry"])
        self.position = None
        self._emit_state()

    def _check_exit(self, price):
        """返回止盈/止损/保本止损/移动止损原因（str），或 None"""
        pos = self.position
        if pos is None:
            return None
        entry = pos["entry"]
        side  = pos["side"]
        try:
            tp = float(self.cfg.get("tp_pct", 0) or 0) / 100.0
            sl = float(self.cfg.get("sl_pct", 0) or 0) / 100.0
            trailing_pct = float(self.cfg.get("trailing_sl_pct", 0) or 0) / 100.0
        except Exception:
            tp = sl = trailing_pct = 0.0

        # 1. 触发固定止盈
        if tp > 0:
            if side == "long"  and price >= entry * (1 + tp): return f"止盈+{tp*100:.2f}%"
            if side == "short" and price <= entry * (1 - tp): return f"止盈+{tp*100:.2f}%"

        # 2. 保本止损 (Move-to-Breakeven)：当浮盈达到止盈目标的 50%（或浮盈达到 +0.8%）时激活
        be_trigger_pct = (tp * 0.5) if tp > 0 else 0.008
        if be_trigger_pct > 0:
            if side == "long":
                if price >= entry * (1 + be_trigger_pct):
                    pos["breakeven_active"] = True
                if pos.get("breakeven_active") and price <= entry:
                    return f"保本止损(锁定成本价@{price:.6g})"
            elif side == "short":
                if price <= entry * (1 - be_trigger_pct):
                    pos["breakeven_active"] = True
                if pos.get("breakeven_active") and price >= entry:
                    return f"保本止损(锁定成本价@{price:.6g})"

        # 3. 触发固定止损
        if sl > 0:
            if side == "long"  and price <= entry * (1 - sl): return f"止损-{sl*100:.2f}%"
            if side == "short" and price >= entry * (1 + sl): return f"止损-{sl*100:.2f}%"

        # 4. 移动止损 (Trailing Stop Loss)
        if trailing_pct > 0:
            if side == "long":
                pos["peak_price"] = max(pos.get("peak_price", entry), price)
                peak = pos["peak_price"]
                if price <= peak * (1 - trailing_pct):
                    return f"移动止损回调(最高价{peak:.6g}回调-{trailing_pct*100:.2f}%)"
            elif side == "short":
                pos["trough_price"] = min(pos.get("trough_price", entry), price)
                trough = pos["trough_price"]
                if price >= trough * (1 + trailing_pct):
                    return f"移动止损反弹(最低价{trough:.6g}反弹+{trailing_pct*100:.2f}%)"
        return None

    # ---- 主循环 ----
    def run(self):
        cfg = self.cfg
        self._log(
            f"启动: 交易对={cfg['inst']} 周期={cfg['bar']} 策略={cfg['strategy']} "
            f"品类={cfg['kind']} 模式={'模拟' if cfg.get('simulate', True) else '实盘'}",
            "ok")
        self._emit_state()
        try:
            interval = max(2, int(cfg.get("interval", 15)))
        except Exception:
            interval = 15
        loop_n = 0
        while not self.stop_event.is_set():
            loop_n += 1
            try:
                # 风控检查：最大回撤与连续亏损
                max_dd_limit = float(cfg.get("max_drawdown_limit", 0) or 0)
                max_losses_limit = int(cfg.get("max_consecutive_losses", 0) or 0)
                if cfg.get("simulate", True):
                    acct = self.window._sim_account
                    if max_dd_limit > 0 and acct.max_drawdown >= max_dd_limit:
                        self._log(f"⚠ 触及账户最大回撤风控线 ({acct.max_drawdown:.2f}% >= {max_dd_limit:.2f}%)，安全停机！", "err")
                        self.stop()
                        break
                    if max_losses_limit > 0 and acct.consecutive_losses >= max_losses_limit:
                        self._log(f"⚠ 触及最大连续亏损风控线 ({acct.consecutive_losses} >= {max_losses_limit})，安全停机！", "err")
                        self.stop()
                        break

                # 响应手动平仓
                if self.manual_close_event.is_set():
                    self.manual_close_event.clear()
                    if self.position is not None:
                        px = self._last_price(cfg["inst"]) or self._last_px
                        if px is not None:
                            self._last_px = px
                            self._close(px, "手动平仓")
                        else:
                            self._log("手动平仓失败：无法获取最新价", "err")
                    else:
                        self._log("手动平仓请求收到，但当前无持仓，忽略", "warn")

                candles = self._fetch_candles(cfg["inst"], cfg["bar"], 200)
                if not candles or not candles[2]:
                    self._log("K线数据为空，稍后重试", "warn")
                else:
                    highs, lows, closes = candles
                    price = closes[-1]
                    lp = self._last_price(cfg["inst"])
                    if lp is not None:
                        price = lp
                    self._last_px = price

                    reason = self._check_exit(price)
                    if reason:
                        self._close(price, reason)
                    else:
                        if cfg["strategy"] == "grid":
                            # 网格策略
                            g_upper = float(cfg.get("grid_upper", 0) or 0)
                            g_lower = float(cfg.get("grid_lower", 0) or 0)
                            sig = 0
                            if g_upper > g_lower > 0:
                                if price <= g_lower:
                                    sig = 1
                                elif price >= g_upper:
                                    sig = -1
                        else:
                            sig = pick_strategy_signal(
                                cfg["strategy"], closes, highs, lows, params=cfg.get("params")
                            )

                        sig_txt = "买入(+1)" if sig > 0 else ("卖出(-1)" if sig < 0 else "观望(0)")
                        pos_txt = "无持仓" if self.position is None \
                                  else f"持{self.position['side'].upper()}@{self.position['entry']:.6g}"
                        self._log(
                            f"第 {loop_n} 轮评估: 策略={cfg['strategy']} 信号={sig_txt} "
                            f"价格={price:.6g} {pos_txt}", "info")
                        if self.position is None:
                            forced_ps = (cfg.get("pos_side") if cfg.get("kind") == "swap"
                                         else None)
                            if sig > 0:
                                if forced_ps == "short":
                                    self._log("买入信号但已强制做空方向，跳过", "warn")
                                else:
                                    self._open("long", price)
                            elif sig < 0:
                                if cfg.get("kind") != "swap" or not cfg.get("allow_short", True):
                                    self._log("卖出信号但当前品类/设置不允许做空，跳过", "warn")
                                elif forced_ps == "long":
                                    self._log("卖出信号但已强制做多方向，跳过", "warn")
                                else:
                                    self._open("short", price)
                        else:
                            if cfg.get("exit_on_reverse", True):
                                if self.position["side"] == "long" and sig < 0:
                                    self._close(price, "反向信号")
                                elif self.position["side"] == "short" and sig > 0:
                                    self._close(price, "反向信号")
                self._emit_state()
            except Exception as e:
                self._log(f"循环异常: {type(e).__name__}: {e}", "err")
            for _ in range(interval):
                if self.stop_event.is_set() or self.manual_close_event.is_set():
                    break
                time.sleep(1)
        self._log("已停止", "info")
        self._emit_state()


# ==================== WebSocket 客户端（实时行情） ====================
class OkxWsClient:
    """
    OKX WebSocket 公开行情客户端。
    - 在独立守护线程中运行 asyncio 事件循环
    - 支持 tickers（实时价格，公开端点）与 candle{bar}（实时 K 线，业务端点）
    - 断线自动重连并重放订阅
    - 通过 on_ticker / on_candle 回调（可能在 WS 线程）转发消息
    """
    PUBLIC_URL_REAL = "wss://ws.okx.com:8443/ws/v5/public"
    PUBLIC_URL_SIM  = "wss://wspap.okx.com:8443/ws/v5/public?brokerId=9999"
    BUSINESS_URL_REAL = "wss://ws.okx.com:8443/ws/v5/business"
    BUSINESS_URL_SIM  = "wss://wspap.okx.com:8443/ws/v5/business?brokerId=9999"

    def __init__(self, flag="1", proxy=None):
        self.flag = flag
        self.proxy = proxy or None
        self.on_ticker = None
        self.on_candle = None
        self.on_status = None  # callback(kind, ok)
        self._loop = None
        self._thread = None
        self._running = False
        self._ws_public = None
        self._ws_biz = None
        self._sub_ticker = None   # {"channel": "tickers", "instId": ...}
        self._sub_candle = None   # {"channel": "candle1H", "instId": ...}

    # ---------- 生命周期 ----------
    def start(self):
        if self._running:
            return
        self._running = True
        self._thread = threading.Thread(target=self._run_loop, daemon=True)
        self._thread.start()

    def stop(self):
        self._running = False
        if self._loop and self._loop.is_running():
            self._loop.call_soon_threadsafe(self._loop.stop)

    def _run_loop(self):
        self._loop = asyncio.new_event_loop()
        asyncio.set_event_loop(self._loop)
        try:
            self._loop.run_until_complete(self._main())
        except Exception:
            pass

    async def _main(self):
        await asyncio.gather(
            self._maintain("public"),
            self._maintain("business"),
            return_exceptions=True,
        )

    def _get_url(self, kind):
        if kind == "public":
            return self.PUBLIC_URL_SIM if self.flag == "1" else self.PUBLIC_URL_REAL
        return self.BUSINESS_URL_SIM if self.flag == "1" else self.BUSINESS_URL_REAL

    async def _maintain(self, kind):
        while self._running:
            url = self._get_url(kind)
            try:
                kwargs = dict(ping_interval=20, ping_timeout=15,
                              close_timeout=5, open_timeout=15)
                if self.proxy:
                    kwargs["proxy"] = self.proxy
                async with websockets.connect(url, **kwargs) as ws:
                    if kind == "public":
                        self._ws_public = ws
                    else:
                        self._ws_biz = ws
                    if self.on_status:
                        try: self.on_status(kind, True)
                        except Exception: pass
                    # 重放订阅
                    sub = self._sub_ticker if kind == "public" else self._sub_candle
                    if sub is not None:
                        await ws.send(json.dumps({"op": "subscribe", "args": [sub]}))
                    async for msg in ws:
                        self._handle_message(msg)
            except Exception:
                if self.on_status:
                    try: self.on_status(kind, False)
                    except Exception: pass
                if not self._running:
                    break
                await asyncio.sleep(2.0)  # 重连前退避
            finally:
                if kind == "public": self._ws_public = None
                else: self._ws_biz = None

    def _handle_message(self, msg):
        try:
            m = json.loads(msg)
        except Exception:
            return
        if m.get("event") == "error":
            return
        arg = m.get("arg") or {}
        data = m.get("data")
        if not data:
            return
        channel = arg.get("channel", "")
        if channel == "tickers" and self.on_ticker:
            try: self.on_ticker(data[0])
            except Exception: pass
        elif channel.startswith("candle") and self.on_candle:
            try: self.on_candle(arg.get("instId"), channel, data)
            except Exception: pass

    # ---------- 订阅切换（线程安全） ----------
    def set_ticker(self, instId):
        new = {"channel": "tickers", "instId": instId} if instId else None
        old = self._sub_ticker
        self._sub_ticker = new
        if self._loop and self._loop.is_running():
            asyncio.run_coroutine_threadsafe(
                self._resub(self._ws_public, old, new), self._loop)

    def set_candle(self, instId, bar):
        new = {"channel": f"candle{bar}", "instId": instId} if (instId and bar) else None
        old = self._sub_candle
        self._sub_candle = new
        if self._loop and self._loop.is_running():
            asyncio.run_coroutine_threadsafe(
                self._resub(self._ws_biz, old, new), self._loop)

    async def _resub(self, ws, old, new):
        if ws is None:
            return
        try:
            if old and old != new:
                await ws.send(json.dumps({"op": "unsubscribe", "args": [old]}))
            if new:
                await ws.send(json.dumps({"op": "subscribe", "args": [new]}))
        except Exception:
            pass

    def set_flag(self, flag):
        """切换实盘/模拟盘：断开当前连接，重连即用新 URL"""
        if flag == self.flag:
            return
        self.flag = flag
        # 关闭现有连接触发重连
        async def _close_all():
            for ws in (self._ws_public, self._ws_biz):
                if ws is not None:
                    try: await ws.close()
                    except Exception: pass
        if self._loop and self._loop.is_running():
            asyncio.run_coroutine_threadsafe(_close_all(), self._loop)

    def set_proxy(self, proxy):
        """更新代理设置，触发重连以生效"""
        new_proxy = proxy or None
        if new_proxy == self.proxy:
            return
        self.proxy = new_proxy
        async def _close_all():
            for ws in (self._ws_public, self._ws_biz):
                if ws is not None:
                    try: await ws.close()
                    except Exception: pass
        if self._loop and self._loop.is_running():
            asyncio.run_coroutine_threadsafe(_close_all(), self._loop)


# ==================== 主窗口 ====================
class MainWindow(QMainWindow):
    log_signal    = pyqtSignal(str, str)
    result_signal = pyqtSignal(str, object)
    price_signal  = pyqtSignal(str, str, str)  # (price_str, change_str, dir "1"/"0"/"-")
    chart_signal  = pyqtSignal(object)
    conn_signal   = pyqtSignal(bool)
    ws_ticker_signal = pyqtSignal(dict)      # 实时 ticker
    ws_candle_signal = pyqtSignal(str, str, list)  # (instId, channel, data)
    verdict_signal   = pyqtSignal(object)    # 多周期评级结果
    auto_state_signal = pyqtSignal(dict)     # 自动交易状态推送
    auto_order_signal = pyqtSignal(dict)     # 自动交易订单事件

    def __init__(self):
        super().__init__()
        self.setWindowTitle("python-okx 交易终端")
        self.resize(1360, 940)
        self.setMinimumSize(1080, 760)

        # 状态
        self._is_fullscreen_chart = False
        # 涨跌配色：默认绿涨红跌（国际惯例）；可切换为红涨绿跌（中国惯例）
        self._up_is_green = True
        # 当前展示的币对/周期（用于过滤过期的异步结果与 WS 推送）
        self._cur_inst = None
        self._cur_bar  = None
        self._debounce_timer = QTimer(self)
        self._debounce_timer.setSingleShot(True)
        self._debounce_timer.timeout.connect(self._refresh_chart)
        self._refresh_timer = QTimer(self)
        self._refresh_timer.timeout.connect(self._refresh_chart)

        # 信号连接
        self.log_signal.connect(self._on_log)
        self.result_signal.connect(self._on_result)
        self.price_signal.connect(self._on_price)
        self.chart_signal.connect(self._on_chart_data)
        self.conn_signal.connect(self._on_conn)
        self.ws_ticker_signal.connect(self._on_ws_ticker)
        self.ws_candle_signal.connect(self._on_ws_candle)
        self.verdict_signal.connect(self._on_verdict_data)
        self.auto_state_signal.connect(self._on_auto_state)
        self.auto_order_signal.connect(self._on_auto_order)

        # 自动交易 & 模拟账户
        self._sim_account = SimulationAccount()
        self._auto_trader = None
        self._is_fullscreen_auto = False
        # UI 心跳：1s 刷新一次自动交易运行状态标签，避免只在 poll 间隔（默认 15s）才更新
        self._auto_status_timer = QTimer(self)
        self._auto_status_timer.setInterval(1000)
        self._auto_status_timer.timeout.connect(self._refresh_auto_status_label)

        # UI
        self._build_ui()

        # 快捷键
        self._esc_shortcut = QShortcut(QKeySequence("Escape"), self)
        self._esc_shortcut.activated.connect(self._on_escape)

        # WebSocket 客户端（实时行情）
        self._ws = OkxWsClient(flag=self._get_flag(), proxy=self._get_proxy())
        self._ws.on_ticker = lambda d: self.ws_ticker_signal.emit(d)
        self._ws.on_candle = lambda i, ch, d: self.ws_candle_signal.emit(i, ch, d)
        self._ws.on_status = self._on_ws_status
        # 用于合并 tick 频繁刷新（避免每毫秒都动 UI）
        self._last_ticker_data = None
        self._ticker_ui_timer = QTimer(self)
        self._ticker_ui_timer.setInterval(150)
        self._ticker_ui_timer.timeout.connect(self._flush_ticker_ui)

        # 启动：自动加载配置 + 500ms 自动刷新一次 K线
        self._auto_load_config()
        # 加载配置后同步代理到 WS（WS 尚未 start，直接更新属性即可）
        try:
            self._ws.proxy = self._get_proxy()
        except Exception:
            pass
        self._apply_color_scheme()  # 初始化按钮文本 & 样式
        QTimer.singleShot(500, self._refresh_chart)
        self._apply_refresh_interval()
        # 启动 WS 客户端 & 订阅初始品种/周期
        self._ws.start()
        self._ticker_ui_timer.start()
        QTimer.singleShot(300, self._sync_ws_subs)
        # 首次启动提示（若未设置代理）
        if not self._get_proxy():
            self._log(
                "提示：如首次连接较慢（15s+），可在'代理'字段填入本地代理，"
                "例如 http://127.0.0.1:7890（Clash 默认端口）",
                "info")

    def _sync_ws_subs(self):
        """根据当前 UI 上选择的 pair/bar 同步 WS 订阅"""
        inst = self.k_pair.currentText().strip().upper() or "BTC-USDT"
        bar  = self.k_bar.currentText().strip() or "1H"
        self._ws.set_ticker(inst)
        self._ws.set_candle(inst, bar)

    def closeEvent(self, event):
        try:
            if self._auto_trader is not None:
                self._auto_trader.stop()
        except Exception:
            pass
        try:
            self._ws.stop()
        except Exception:
            pass
        super().closeEvent(event)

    # ---------- UI 构建 ----------
    def _build_ui(self):
        central = QWidget()
        self.setCentralWidget(central)
        root = QVBoxLayout(central)
        root.setContentsMargins(0, 0, 0, 0)
        root.setSpacing(0)

        # HeaderBar
        self.header = self._build_header()
        root.addWidget(self.header)

        # 主体（有内边距）
        body = QWidget()
        body_lay = QVBoxLayout(body)
        body_lay.setContentsMargins(12, 10, 12, 10)
        body_lay.setSpacing(10)
        root.addWidget(body, 1)

        # ConfigBar
        self.config_bar = self._build_config_bar()
        body_lay.addWidget(self.config_bar)

        # 垂直 Splitter: Tabs | Output
        self.splitter = QSplitter(Qt.Orientation.Vertical)
        self.splitter.setHandleWidth(1)
        self.tabs = self._build_tabs()
        self.output_panel = self._build_output_panel()
        self.splitter.addWidget(self.tabs)
        self.splitter.addWidget(self.output_panel)
        self.splitter.setSizes([560, 300])
        body_lay.addWidget(self.splitter, 1)

    def _build_header(self):
        f = QFrame()
        f.setObjectName("HeaderBar")
        f.setFixedHeight(64)
        lay = QHBoxLayout(f)
        lay.setContentsMargins(20, 8, 20, 8)
        lay.setSpacing(12)

        brand = QLabel("python-okx")
        brand.setObjectName("Brand")
        sub = QLabel("交易终端")
        sub.setObjectName("BrandSub")
        lay.addWidget(brand)
        lay.addWidget(sub)
        lay.addStretch(1)

        self.hdr_pair = QLabel("BTC-USDT")
        self.hdr_pair.setObjectName("PairName")
        lay.addWidget(self.hdr_pair)
        sep1 = QLabel("│"); sep1.setObjectName("Sep")
        lay.addWidget(sep1)

        self.hdr_price = QLabel("--")
        self.hdr_price.setObjectName("PriceNeutral")
        lay.addWidget(self.hdr_price)

        self.hdr_change = QLabel("--")
        self.hdr_change.setObjectName("ChangeNeutral")
        lay.addWidget(self.hdr_change)

        sep2 = QLabel("│"); sep2.setObjectName("Sep")
        lay.addWidget(sep2)

        self.hdr_badge = QLabel("模拟盘")
        self.hdr_badge.setObjectName("BadgeSim")
        lay.addWidget(self.hdr_badge)

        self.hdr_dot = QLabel("●")
        self.hdr_dot.setObjectName("DotOff")
        self.hdr_dot.setToolTip("未连接")
        lay.addWidget(self.hdr_dot)

        return f

    def _build_config_bar(self):
        card = QFrame(); card.setObjectName("Card")
        lay = QVBoxLayout(card)
        lay.setContentsMargins(14, 10, 14, 10)
        lay.setSpacing(8)

        # 行1: API Key / Secret / Passphrase
        row1 = QHBoxLayout(); row1.setSpacing(10)

        def field(label_text, echo=False, ph=""):
            box = QVBoxLayout(); box.setSpacing(2)
            lb = QLabel(label_text); lb.setObjectName("FieldLabel")
            le = QLineEdit()
            le.setPlaceholderText(ph)
            if echo:
                le.setEchoMode(QLineEdit.EchoMode.Password)
            box.addWidget(lb); box.addWidget(le)
            wrap = QWidget(); wrap.setLayout(box)
            return wrap, le

        w1, self.in_key = field("API Key", False, "输入 API Key")
        w2, self.in_sec = field("API Secret", True, "输入 API Secret")
        w3, self.in_pass = field("Passphrase", True, "输入 Passphrase")
        row1.addWidget(w1, 1); row1.addWidget(w2, 1); row1.addWidget(w3, 1)
        lay.addLayout(row1)

        # 行1.5: 代理设置（用于内地网络访问 OKX）
        row_px = QHBoxLayout(); row_px.setSpacing(10)
        wpx, self.in_proxy = field(
            "代理 (HTTP/SOCKS，可选)", False,
            "如 http://127.0.0.1:7890 或 socks5://127.0.0.1:1080；留空表示不使用代理")
        # 从环境变量预填
        env_proxy = os.environ.get("HTTPS_PROXY") or os.environ.get("https_proxy") \
                    or os.environ.get("ALL_PROXY") or os.environ.get("all_proxy") or ""
        if env_proxy:
            self.in_proxy.setPlaceholderText(f"环境变量：{env_proxy}（留空则自动使用）")
        row_px.addWidget(wpx, 1)
        b_apply_px = QPushButton("应用代理"); b_apply_px.setObjectName("Ghost")
        b_apply_px.setToolTip("应用代理设置并重连 WebSocket")
        b_apply_px.clicked.connect(self._apply_proxy)
        row_px.addWidget(b_apply_px)
        lay.addLayout(row_px)

        # 行2: 环境 / 显示密码 / 按钮
        row2 = QHBoxLayout(); row2.setSpacing(10)

        self.rb_sim = QRadioButton("模拟盘")
        self.rb_real = QRadioButton("实盘")
        self.rb_sim.setChecked(True)
        self._env_group = QButtonGroup(self)
        self._env_group.addButton(self.rb_sim)
        self._env_group.addButton(self.rb_real)
        self.rb_sim.toggled.connect(self._on_env_changed)
        row2.addWidget(QLabel("环境:"))
        row2.addWidget(self.rb_sim)
        row2.addWidget(self.rb_real)

        self.cb_show_pwd = QCheckBox("显示密码")
        self.cb_show_pwd.toggled.connect(self._on_show_pwd)
        row2.addWidget(self.cb_show_pwd)

        # 涨跌配色切换按钮
        self.btn_color = QPushButton()
        self.btn_color.setObjectName("Ghost")
        self.btn_color.setToolTip("点击切换涨跌配色：绿涨红跌 / 红涨绿跌")
        self.btn_color.clicked.connect(self._toggle_color_scheme)
        row2.addWidget(self.btn_color)

        row2.addStretch(1)

        b_save = QPushButton("保存配置"); b_save.clicked.connect(self._save_config)
        b_load = QPushButton("加载"); b_load.clicked.connect(self._load_config)
        b_env  = QPushButton("写.env"); b_env.clicked.connect(self._write_env)
        b_test = QPushButton("测试连接"); b_test.setObjectName("Primary")
        b_test.clicked.connect(self._test_connection)
        for b in (b_save, b_load, b_env, b_test):
            row2.addWidget(b)

        lay.addLayout(row2)
        return card

    def _build_tabs(self):
        tabs = QTabWidget()
        tabs.addTab(self._build_kline_tab(),   "K线图表")
        tabs.addTab(self._build_market_tab(),  "公开行情")
        tabs.addTab(self._build_account_tab(), "账户信息")
        tabs.addTab(self._build_funding_tab(), "资金管理")
        tabs.addTab(self._build_trade_tab(),   "交易操作")
        tabs.addTab(self._build_auto_tab(),    "自动交易")
        return tabs

    def _build_kline_tab(self):
        w = QWidget()
        outer = QVBoxLayout(w); outer.setContentsMargins(10, 10, 10, 10); outer.setSpacing(10)

        # 控制卡片
        self.kline_ctrl = QFrame(); self.kline_ctrl.setObjectName("Card")
        cv = QVBoxLayout(self.kline_ctrl)
        cv.setContentsMargins(12, 10, 12, 10); cv.setSpacing(8)

        row1 = QHBoxLayout(); row1.setSpacing(10)
        row1.addWidget(QLabel("交易对:"))
        self.k_pair = QComboBox(); self.k_pair.setEditable(True); self.k_pair.addItems(COMMON_PAIRS)
        self.k_pair.setMinimumWidth(140)
        row1.addWidget(self.k_pair)

        row1.addWidget(QLabel("周期:"))
        self.k_bar = QComboBox(); self.k_bar.addItems(BAR_OPTIONS); self.k_bar.setCurrentText("1H")
        row1.addWidget(self.k_bar)

        row1.addWidget(QLabel("K线数量:"))
        self.k_limit = QLineEdit("100"); self.k_limit.setFixedWidth(60)
        row1.addWidget(self.k_limit)

        row1.addWidget(QLabel("均线:"))
        self.k_ma = QLineEdit("MA5,MA20"); self.k_ma.setFixedWidth(110)
        row1.addWidget(self.k_ma)
        row1.addStretch(1)
        cv.addLayout(row1)

        row2 = QHBoxLayout(); row2.setSpacing(10)
        self.cb_vol = QCheckBox("成交量"); self.cb_vol.setChecked(True)
        self.cb_maline = QCheckBox("均线"); self.cb_maline.setChecked(True)
        self.cb_magic = QCheckBox("隐秘枢轴"); self.cb_magic.setChecked(False)
        self.cb_magic.setToolTip("Rumers Magic Lines：R1/R2/S1/S2 枢轴 + 3K 线入场信号")
        self.k_magic_mode = QComboBox()
        self.k_magic_mode.addItems(["正宗版 (K1/K2/K3)", "简单版 (3阳/3阴 破 R1/S1)"])
        self.k_magic_mode.setCurrentIndex(0)
        self.k_magic_mode.setToolTip(
            "3K 入场信号模式：\n"
            "正宗版：K2 触及 R1/R2/S1/S2 拒绝，K3 收盘突破 K2 高/低\n"
            "简单版：连续 3 阳/3 阴 收盘越过 R1/S1")
        self.sp_magic_lookback = QSpinBox()
        self.sp_magic_lookback.setRange(0, 30)
        self.sp_magic_lookback.setValue(1)
        self.sp_magic_lookback.setSuffix(" 天")
        self.sp_magic_lookback.setFixedWidth(72)
        self.sp_magic_lookback.setToolTip(
            "R2/S2 回溯天数：\n"
            "0 = 不画 R2/S2（只画 R1/S1）\n"
            "1 = 前前一交易日 (D-2) 的高/低（默认）\n"
            "N = D-(1+N) 的高/低")
        self.cb_macd = QCheckBox("MACD"); self.cb_macd.setChecked(False)
        self.cb_macd.setToolTip("MACD (12/26/9)")
        self.cb_rsi = QCheckBox("RSI"); self.cb_rsi.setChecked(False)
        self.cb_rsi.setToolTip("RSI (14, Wilder)")
        self.cb_kdj = QCheckBox("KDJ"); self.cb_kdj.setChecked(False)
        self.cb_kdj.setToolTip("KDJ (9,3,3)")
        row2.addWidget(self.cb_vol); row2.addWidget(self.cb_maline)
        row2.addWidget(self.cb_magic); row2.addWidget(self.k_magic_mode)
        row2.addWidget(QLabel("R2/S2 回溯:")); row2.addWidget(self.sp_magic_lookback)
        row2.addWidget(self.cb_macd); row2.addWidget(self.cb_rsi); row2.addWidget(self.cb_kdj)

        row2.addWidget(QLabel("自动刷新:"))
        self.k_refresh = QComboBox(); self.k_refresh.addItems(REFRESH_OPTS); self.k_refresh.setCurrentText("关闭")
        self.k_refresh.setToolTip("WebSocket 已提供实时价格与最新 K 线，通常无需再周期性 REST 刷新")
        row2.addWidget(self.k_refresh)

        row2.addStretch(1)

        self.btn_refresh = QPushButton("刷新图表"); self.btn_refresh.setObjectName("Primary")
        self.btn_refresh.clicked.connect(self._refresh_chart)
        row2.addWidget(self.btn_refresh)

        self.btn_full = QPushButton("⛶ 全屏"); self.btn_full.setObjectName("Ghost")
        self.btn_full.clicked.connect(self._toggle_fullscreen_chart)
        row2.addWidget(self.btn_full)
        cv.addLayout(row2)

        outer.addWidget(self.kline_ctrl)

        # 图表区域
        self.web = QWebEngineView()
        self.web.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Expanding)
        # 增量更新：先加载 Plotly.js shell，一次性；之后 runJavaScript 注入数据
        self._chart_shell_loaded = False
        self._pending_fig = None
        self.web.loadFinished.connect(self._on_chart_shell_ready)
        self._install_chart_shell()

        # 右侧多空评级面板
        self.verdict_panel = self._build_verdict_panel()

        # 用水平 Splitter 组合图表 + 评级面板
        self.chart_split = QSplitter(Qt.Orientation.Horizontal)
        self.chart_split.addWidget(self.web)
        self.chart_split.addWidget(self.verdict_panel)
        self.chart_split.setStretchFactor(0, 4)
        self.chart_split.setStretchFactor(1, 1)
        self.chart_split.setSizes([900, 240])
        outer.addWidget(self.chart_split, 1)

        # 参数变化 → 防抖刷新
        self.k_pair.currentTextChanged.connect(self._schedule_refresh)
        self.k_bar.currentTextChanged.connect(self._schedule_refresh)
        self.k_limit.textChanged.connect(self._schedule_refresh)
        self.k_ma.textChanged.connect(self._schedule_refresh)
        self.cb_vol.toggled.connect(self._schedule_refresh)
        self.cb_maline.toggled.connect(self._schedule_refresh)
        self.cb_magic.toggled.connect(self._schedule_refresh)
        self.k_magic_mode.currentTextChanged.connect(self._schedule_refresh)
        self.sp_magic_lookback.valueChanged.connect(self._schedule_refresh)
        self.cb_macd.toggled.connect(self._schedule_refresh)
        self.cb_rsi.toggled.connect(self._schedule_refresh)
        self.cb_kdj.toggled.connect(self._schedule_refresh)
        self.k_refresh.currentTextChanged.connect(self._apply_refresh_interval)
        self.k_pair.currentTextChanged.connect(self._on_pair_changed)
        # pair/bar 变化 → 同步 WS 订阅
        self.k_pair.currentTextChanged.connect(lambda _: self._sync_ws_subs())
        self.k_bar.currentTextChanged.connect(lambda _: self._sync_ws_subs())

        return w

    def _build_market_tab(self):
        w = QWidget()
        v = QVBoxLayout(w); v.setContentsMargins(10, 10, 10, 10); v.setSpacing(10)

        card = QFrame(); card.setObjectName("Card")
        cv = QVBoxLayout(card); cv.setContentsMargins(14, 12, 14, 12); cv.setSpacing(10)

        top = QHBoxLayout(); top.setSpacing(10)
        top.addWidget(QLabel("交易对:"))
        self.m_pair = QComboBox(); self.m_pair.setEditable(True); self.m_pair.addItems(COMMON_PAIRS)
        self.m_pair.setMinimumWidth(160)
        top.addWidget(self.m_pair); top.addStretch(1)
        cv.addLayout(top)

        grid = QGridLayout(); grid.setSpacing(10)
        buttons = [
            ("获取行情", self._m_ticker),
            ("订单簿", self._m_orderbook),
            ("K线数据", self._m_candles),
            ("最近成交", self._m_trades),
            ("24h成交量", self._m_volume),
            ("所有Ticker", self._m_all_tickers),
        ]
        for i, (name, fn) in enumerate(buttons):
            b = QPushButton(name); b.clicked.connect(fn)
            grid.addWidget(b, i // 3, i % 3)
        cv.addLayout(grid)
        cv.addStretch(1)
        v.addWidget(card); v.addStretch(1)
        return w

    def _build_account_tab(self):
        w = QWidget()
        v = QVBoxLayout(w); v.setContentsMargins(10, 10, 10, 10); v.setSpacing(10)

        card = QFrame(); card.setObjectName("Card")
        cv = QVBoxLayout(card); cv.setContentsMargins(14, 12, 14, 12); cv.setSpacing(10)

        top = QHBoxLayout(); top.setSpacing(10)
        top.addWidget(QLabel("交易对:"))
        self.a_pair = QComboBox(); self.a_pair.setEditable(True); self.a_pair.addItems(COMMON_PAIRS)
        self.a_pair.setMinimumWidth(160)
        top.addWidget(self.a_pair)
        tip = QLabel("需要 API 凭证"); tip.setObjectName("Hint")
        top.addWidget(tip)
        top.addStretch(1)
        cv.addLayout(top)

        grid = QGridLayout(); grid.setSpacing(10)
        buttons = [
            ("账户余额",   self._a_balance),
            ("持仓信息",   self._a_positions),
            ("账户配置",   self._a_config),
            ("账户流水",   self._a_bills),
            ("最大下单量", self._a_max_size),
            ("手续费率",   self._a_fee_rates),
        ]
        for i, (n, fn) in enumerate(buttons):
            b = QPushButton(n); b.clicked.connect(fn)
            grid.addWidget(b, i // 3, i % 3)
        cv.addLayout(grid)
        cv.addStretch(1)
        v.addWidget(card); v.addStretch(1)
        return w

    def _build_funding_tab(self):
        w = QWidget()
        v = QVBoxLayout(w); v.setContentsMargins(10, 10, 10, 10); v.setSpacing(10)

        card = QFrame(); card.setObjectName("Card")
        cv = QVBoxLayout(card); cv.setContentsMargins(14, 12, 14, 12); cv.setSpacing(10)

        top = QHBoxLayout()
        tip = QLabel("需要 API 凭证"); tip.setObjectName("Hint")
        top.addWidget(tip); top.addStretch(1)
        cv.addLayout(top)

        grid = QGridLayout(); grid.setSpacing(10)
        buttons = [
            ("资金余额",  self._f_balances),
            ("充值地址",  self._f_deposit_addr),
            ("资产估值",  self._f_valuation),
            ("充值记录",  self._f_deposit_history),
            ("提币记录",  self._f_withdrawal_history),
            ("资金流水",  self._f_bills),
        ]
        for i, (n, fn) in enumerate(buttons):
            b = QPushButton(n); b.clicked.connect(fn)
            grid.addWidget(b, i // 3, i % 3)
        cv.addLayout(grid)
        cv.addStretch(1)
        v.addWidget(card); v.addStretch(1)
        return w

    def _build_trade_tab(self):
        w = QWidget()
        v = QVBoxLayout(w); v.setContentsMargins(10, 10, 10, 10); v.setSpacing(10)

        card = QFrame(); card.setObjectName("Card")
        cv = QVBoxLayout(card); cv.setContentsMargins(14, 12, 14, 12); cv.setSpacing(10)

        row1 = QHBoxLayout(); row1.setSpacing(10)
        row1.addWidget(QLabel("交易对:"))
        self.t_pair = QComboBox(); self.t_pair.setEditable(True); self.t_pair.addItems(COMMON_PAIRS)
        self.t_pair.setMinimumWidth(140)
        row1.addWidget(self.t_pair)

        row1.addWidget(QLabel("方向:"))
        self.t_side = QComboBox(); self.t_side.addItems(["buy", "sell"])
        row1.addWidget(self.t_side)

        row1.addWidget(QLabel("类型:"))
        self.t_type = QComboBox(); self.t_type.addItems(["market", "limit"])
        row1.addWidget(self.t_type)

        row1.addWidget(QLabel("数量:"))
        self.t_sz = QLineEdit(); self.t_sz.setPlaceholderText("数量"); self.t_sz.setFixedWidth(110)
        row1.addWidget(self.t_sz)

        row1.addWidget(QLabel("价格:"))
        self.t_px = QLineEdit(); self.t_px.setPlaceholderText("价格(限价)"); self.t_px.setFixedWidth(120)
        row1.addWidget(self.t_px)
        row1.addStretch(1)
        cv.addLayout(row1)

        row2 = QHBoxLayout(); row2.setSpacing(10)
        warn = QLabel("⚠ 实盘下单有二次确认，请谨慎操作"); warn.setObjectName("Warn")
        row2.addWidget(warn); row2.addStretch(1)

        b_place = QPushButton("下单"); b_place.setObjectName("Danger")
        b_place.clicked.connect(self._t_place_order)
        row2.addWidget(b_place)

        b_open = QPushButton("当前挂单"); b_open.clicked.connect(self._t_open_orders)
        b_hist = QPushButton("历史订单"); b_hist.clicked.connect(self._t_orders_history)
        b_fill = QPushButton("成交记录"); b_fill.clicked.connect(self._t_fills)
        for b in (b_open, b_hist, b_fill):
            row2.addWidget(b)
        cv.addLayout(row2)
        cv.addStretch(1)

        v.addWidget(card); v.addStretch(1)
        return w

    # ==================== 自动交易 Tab ====================
    def _build_auto_tab(self):
        # 外壳：QScrollArea → 内部 body。窗口不够高时可滚动，输入框不会挤在一起。
        root = QWidget()
        rlay = QVBoxLayout(root); rlay.setContentsMargins(0, 0, 0, 0); rlay.setSpacing(0)

        self.a_scroll = QScrollArea()
        self.a_scroll.setWidgetResizable(True)
        self.a_scroll.setFrameShape(QFrame.Shape.NoFrame)
        self.a_scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAsNeeded)
        self.a_scroll.setVerticalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAsNeeded)

        body = QWidget()
        outer = QVBoxLayout(body); outer.setContentsMargins(10, 10, 10, 10); outer.setSpacing(10)

        # ---------- 卡片 1：交易参数（表单式，每字段 标签在上/输入在下） ----------
        card1 = QFrame(); card1.setObjectName("Card")
        c1 = QVBoxLayout(card1); c1.setContentsMargins(14, 12, 14, 12); c1.setSpacing(10)

        title1_row = QHBoxLayout()
        title1 = QLabel("交易参数"); title1.setObjectName("SectionTitle")
        title1_row.addWidget(title1); title1_row.addStretch(1)
        self.a_btn_full = QPushButton("⛶ 全屏"); self.a_btn_full.setObjectName("Ghost")
        self.a_btn_full.setToolTip("全屏显示自动交易面板（Esc 退出）")
        self.a_btn_full.clicked.connect(self._toggle_fullscreen_auto)
        title1_row.addWidget(self.a_btn_full)
        c1.addLayout(title1_row)

        # 4 列网格：每一"字段"占 2 行（第1行标签、第2行控件），控件行拉伸
        grid = QGridLayout()
        grid.setHorizontalSpacing(14); grid.setVerticalSpacing(6)
        for col in range(4):
            grid.setColumnStretch(col, 1)

        def _lbl(txt):
            l = QLabel(txt); l.setObjectName("FieldLabel"); return l

        def _add(row, col, label_text, widget, min_w=120):
            widget.setMinimumWidth(min_w)
            widget.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)
            grid.addWidget(_lbl(label_text), row,     col)
            grid.addWidget(widget,           row + 1, col)

        self.a_pair = QComboBox(); self.a_pair.setEditable(True); self.a_pair.addItems(COMMON_PAIRS)
        self.a_bar  = QComboBox(); self.a_bar.addItems(BAR_OPTIONS); self.a_bar.setCurrentText("15m")
        self.a_interval = QSpinBox(); self.a_interval.setRange(2, 3600); self.a_interval.setValue(15)
        self.a_strategy = QComboBox()
        for k, lbl in STRATEGY_LABELS_UI:
            self.a_strategy.addItem(lbl, k)
        self.a_strategy.setToolTip("选择『系统自选』或指定单一指标策略")

        _add(0, 0, "交易对",       self.a_pair, 140)
        _add(0, 1, "周期",         self.a_bar)
        _add(0, 2, "轮询间隔(秒)", self.a_interval)
        _add(0, 3, "策略",         self.a_strategy, 160)

        self.a_kind = QComboBox()
        for k, lbl in TRADE_KINDS:
            self.a_kind.addItem(lbl, k)
        self.a_mgn = QComboBox()
        for k, lbl in MGN_MODES:
            self.a_mgn.addItem(lbl, k)
        self.a_pos = QComboBox()
        # 合约默认"自动"：根据策略信号方向自动传 posSide=long/short
        self.a_pos.addItem("自动 (根据信号)", "auto")
        for k, lbl in POS_SIDES:
            self.a_pos.addItem(lbl, k)
        self.a_pos.setCurrentIndex(0)
        self.a_pos.setToolTip(
            "合约 posSide：\n"
            "• 自动 (根据信号)：买信号 → long；卖信号 → short（推荐，双向持仓账户使用）\n"
            "• 多 long / 空 short：强制固定方向（同向信号才开仓）\n"
            "• 净 net：单向持仓账户使用")
        self.a_lev = QDoubleSpinBox(); self.a_lev.setRange(1.0, 125.0); self.a_lev.setValue(3.0); self.a_lev.setDecimals(1)

        _add(2, 0, "品类",       self.a_kind)
        _add(2, 1, "保证金模式", self.a_mgn)
        _add(2, 2, "持仓方向",   self.a_pos)
        _add(2, 3, "杠杆",       self.a_lev)

        self.a_size_type = QComboBox()
        for k, lbl in SIZE_TYPES:
            self.a_size_type.addItem(lbl, k)
        self.a_size_type.setCurrentIndex(self.a_size_type.findData("pct"))
        self.a_size_val = QDoubleSpinBox(); self.a_size_val.setRange(0.0, 1e9); self.a_size_val.setDecimals(6); self.a_size_val.setValue(10.0)
        self.a_size_val.setToolTip("固定数量：base 币数量（如 0.01 BTC）\n余额百分比：0~100，如 20 表示用 20% 模拟余额开仓")
        self.a_tp = QDoubleSpinBox(); self.a_tp.setRange(0.0, 1000.0); self.a_tp.setDecimals(3); self.a_tp.setValue(2.0)
        self.a_tp.setToolTip("止盈比例 (%)。推荐 2.0% 以上以保证至少 2:1 盈亏比（配合 1.0% 止损），半程自动触发保本止损")
        self.a_sl = QDoubleSpinBox(); self.a_sl.setRange(0.0, 1000.0); self.a_sl.setDecimals(3); self.a_sl.setValue(1.0)

        _add(4, 0, "下单方式",    self.a_size_type)
        _add(4, 1, "数量 / 占比", self.a_size_val)
        _add(4, 2, "止盈 %",      self.a_tp)
        _add(4, 3, "止损 %",      self.a_sl)

        self.a_trailing_sl = QDoubleSpinBox(); self.a_trailing_sl.setRange(0.0, 100.0); self.a_trailing_sl.setDecimals(2); self.a_trailing_sl.setValue(0.0)
        self.a_trailing_sl.setToolTip("移动止损：锁定已获利润。如 0.8 表示从持仓最高点/最低点回调 0.8% 触发平仓；0 表示关闭")
        self.a_max_dd = QDoubleSpinBox(); self.a_max_dd.setRange(0.0, 100.0); self.a_max_dd.setDecimals(1); self.a_max_dd.setValue(0.0)
        self.a_max_dd.setToolTip("最大回撤风控：账户回撤达到设定的 % 自动安全停机（0 表示不限制）")
        self.a_max_losses = QSpinBox(); self.a_max_losses.setRange(0, 100); self.a_max_losses.setValue(0)
        self.a_max_losses.setToolTip("连亏关停次数：连续亏损 N 笔后自动安全停机（0 表示不限制）")
        self.a_grid_lower = QDoubleSpinBox(); self.a_grid_lower.setRange(0.0, 1e9); self.a_grid_lower.setDecimals(4); self.a_grid_lower.setValue(0.0)
        self.a_grid_upper = QDoubleSpinBox(); self.a_grid_upper.setRange(0.0, 1e9); self.a_grid_upper.setDecimals(4); self.a_grid_upper.setValue(0.0)

        grid_wrap = QWidget()
        grid_lay = QHBoxLayout(grid_wrap); grid_lay.setContentsMargins(0, 0, 0, 0); grid_lay.setSpacing(4)
        self.a_grid_lower.setToolTip("下限")
        self.a_grid_upper.setToolTip("上限")
        grid_lay.addWidget(self.a_grid_lower)
        grid_lay.addWidget(QLabel("-"))
        grid_lay.addWidget(self.a_grid_upper)

        _add(6, 0, "移动止损 %",  self.a_trailing_sl)
        _add(6, 1, "最大回撤风控 %", self.a_max_dd)
        _add(6, 2, "连亏关停次数", self.a_max_losses)
        _add(6, 3, "网格下限-上限", grid_wrap)

        # 选项复选框独占一行
        opt_row = QHBoxLayout(); opt_row.setSpacing(20)
        self.a_exit_reverse = QCheckBox("反向信号平仓"); self.a_exit_reverse.setChecked(True)
        self.a_allow_short  = QCheckBox("允许做空(合约)"); self.a_allow_short.setChecked(True)
        opt_row.addWidget(self.a_exit_reverse); opt_row.addWidget(self.a_allow_short)
        hint = QLabel("提示：现货只支持做多；合约支持做多做空并使用杠杆；网格策略使用设定的下限/上限价格区间")
        hint.setObjectName("Hint")
        opt_row.addSpacing(10); opt_row.addWidget(hint); opt_row.addStretch(1)

        c1.addLayout(grid)
        c1.addLayout(opt_row)
        outer.addWidget(card1)

        # ---------- 卡片 2：模拟账户 ----------
        card2 = QFrame(); card2.setObjectName("Card")
        c2 = QVBoxLayout(card2); c2.setContentsMargins(14, 12, 14, 12); c2.setSpacing(10)

        title2 = QLabel("模拟账户 (虚拟金额，不走 OKX)"); title2.setObjectName("SectionTitle")
        c2.addWidget(title2)

        rowm = QHBoxLayout(); rowm.setSpacing(10)
        self.a_sim = QCheckBox("模拟自动交易 (不走 OKX 接口)")
        self.a_sim.setChecked(True)
        rowm.addWidget(self.a_sim)

        rowm.addSpacing(20)
        rowm.addWidget(QLabel("重置为:"))
        self.a_sim_amount = QDoubleSpinBox()
        self.a_sim_amount.setRange(1.0, 1e9); self.a_sim_amount.setDecimals(2)
        self.a_sim_amount.setValue(DEFAULT_SIM_BALANCE)
        self.a_sim_amount.setSuffix(" USDT"); self.a_sim_amount.setFixedWidth(160)
        rowm.addWidget(self.a_sim_amount)

        b_reset_def = QPushButton("重置为 $1000")
        b_reset_def.setObjectName("Ghost")
        b_reset_def.clicked.connect(lambda: self._at_reset_sim(DEFAULT_SIM_BALANCE))
        rowm.addWidget(b_reset_def)

        b_reset_custom = QPushButton("重置为自定义金额")
        b_reset_custom.setObjectName("Ghost")
        b_reset_custom.clicked.connect(lambda: self._at_reset_sim(self.a_sim_amount.value()))
        rowm.addWidget(b_reset_custom)

        rowm.addStretch(1)
        c2.addLayout(rowm)

        # 状态展示（可换行、每标签固定最小宽度）
        self.a_status_running  = QLabel("状态：● 未运行"); self.a_status_running.setObjectName("Warn")
        self.a_status_balance  = QLabel("模拟余额：--")
        self.a_status_pnl      = QLabel("已实现盈亏：--")
        self.a_status_trades   = QLabel("交易次数：--")
        self.a_status_max_dd   = QLabel("最大回撤：-- ｜ 盈亏比：--")
        self.a_status_pos      = QLabel("持仓：无")

        rowst = QGridLayout(); rowst.setHorizontalSpacing(20); rowst.setVerticalSpacing(4)
        for lbl in (self.a_status_running, self.a_status_balance, self.a_status_pnl,
                    self.a_status_trades, self.a_status_max_dd):
            lbl.setMinimumWidth(180)
        rowst.addWidget(self.a_status_running, 0, 0)
        rowst.addWidget(self.a_status_balance, 0, 1)
        rowst.addWidget(self.a_status_pnl,     0, 2)
        rowst.addWidget(self.a_status_trades,  0, 3)
        rowst.addWidget(self.a_status_max_dd,  1, 0, 1, 4)
        rowst.addWidget(self.a_status_pos,     2, 0, 1, 4)
        c2.addLayout(rowst)

        outer.addWidget(card2)

        # ---------- 卡片 3：控制按钮 ----------
        card3 = QFrame(); card3.setObjectName("Card")
        c3 = QHBoxLayout(card3); c3.setContentsMargins(14, 10, 14, 10); c3.setSpacing(10)

        warn = QLabel("⚠ 实盘模式将真实下单，请确认参数与仓位；模拟模式仅扣模拟账户金额")
        warn.setObjectName("Warn")
        c3.addWidget(warn); c3.addStretch(1)

        self.a_btn_start = QPushButton("启动自动交易"); self.a_btn_start.setObjectName("Primary")
        self.a_btn_start.clicked.connect(self._at_start)
        c3.addWidget(self.a_btn_start)

        self.a_btn_stop = QPushButton("停止自动交易"); self.a_btn_stop.setObjectName("Danger")
        self.a_btn_stop.setEnabled(False)
        self.a_btn_stop.clicked.connect(self._at_stop)
        c3.addWidget(self.a_btn_stop)

        outer.addWidget(card3)

        # ---------- 卡片 4：订单列表 ----------
        card4 = QFrame(); card4.setObjectName("Card")
        c4 = QVBoxLayout(card4); c4.setContentsMargins(14, 12, 14, 12); c4.setSpacing(8)

        ord_head = QHBoxLayout()
        ord_title = QLabel("自动交易订单"); ord_title.setObjectName("SectionTitle")
        ord_head.addWidget(ord_title)
        self.a_orders_count = QLabel("(0 条)"); self.a_orders_count.setObjectName("Hint")
        ord_head.addWidget(self.a_orders_count)
        keep_hint = QLabel("(订单为重要数据，不可清空；仅可手动平当前持仓)")
        keep_hint.setObjectName("Hint")
        ord_head.addSpacing(10); ord_head.addWidget(keep_hint)
        ord_head.addStretch(1)

        self.a_btn_clear_orders = QPushButton("清空表格")
        self.a_btn_clear_orders.setObjectName("Ghost")
        self.a_btn_clear_orders.setToolTip("清空当前显示的订单表格及本地历史记录")
        self.a_btn_clear_orders.clicked.connect(self._clear_auto_orders)
        ord_head.addWidget(self.a_btn_clear_orders)

        self.a_btn_export_csv = QPushButton("导出 CSV")
        self.a_btn_export_csv.setObjectName("Ghost")
        self.a_btn_export_csv.setToolTip("导出订单表格数据为 CSV 文件")
        self.a_btn_export_csv.clicked.connect(self._export_auto_orders_csv)
        ord_head.addWidget(self.a_btn_export_csv)

        self.a_btn_close_pos = QPushButton("手动平仓")
        self.a_btn_close_pos.setObjectName("Danger")
        self.a_btn_close_pos.setToolTip("以最新市价立即平掉当前持仓（需二次强确认）")
        self.a_btn_close_pos.clicked.connect(self._at_manual_close)
        ord_head.addWidget(self.a_btn_close_pos)
        c4.addLayout(ord_head)

        cols = ["时间", "模式", "品种", "动作", "方向",
                "数量", "价格", "开仓价", "PnL(USDT)", "备注"]
        self.a_orders_table = QTableWidget(0, len(cols))
        self.a_orders_table.setHorizontalHeaderLabels(cols)
        self.a_orders_table.horizontalHeader().setSectionResizeMode(QHeaderView.ResizeMode.Stretch)
        self.a_orders_table.verticalHeader().setVisible(False)
        self.a_orders_table.setEditTriggers(QTableWidget.EditTrigger.NoEditTriggers)
        self.a_orders_table.setSelectionBehavior(QTableWidget.SelectionBehavior.SelectRows)
        self.a_orders_table.setAlternatingRowColors(False)
        self.a_orders_table.setMinimumHeight(180)
        c4.addWidget(self.a_orders_table, 1)

        # 加载本地保存的历史订单填充表格
        self._load_saved_orders_to_table()

        outer.addWidget(card4, 1)
        outer.addStretch(0)

        self.a_scroll.setWidget(body)
        rlay.addWidget(self.a_scroll)

        self._refresh_sim_status_labels()
        return root

    # ---------- 自动交易：全屏 ----------
    def _toggle_fullscreen_auto(self):
        self._is_fullscreen_auto = not self._is_fullscreen_auto
        hidden = self._is_fullscreen_auto
        self.header.setVisible(not hidden)
        self.config_bar.setVisible(not hidden)
        self.output_panel.setVisible(not hidden)
        self.tabs.tabBar().setVisible(not hidden)
        # 确保自动交易 tab 处于当前显示
        if hidden:
            for i in range(self.tabs.count()):
                if self.tabs.tabText(i) == "自动交易":
                    self.tabs.setCurrentIndex(i); break
            self.a_btn_full.setText("⤢ 退出全屏")
        else:
            self.a_btn_full.setText("⛶ 全屏")
        self.a_btn_full.style().unpolish(self.a_btn_full)
        self.a_btn_full.style().polish(self.a_btn_full)
        # 全屏切换后立刻刷新一次运行状态，避免标签因 15s 心跳滞后而误显示"未运行"
        self._refresh_auto_status_label()
        if self._auto_trader is not None and self._auto_trader.is_running():
            self._log(
                f"自动交易{'进入' if hidden else '退出'}全屏 —— 后台仍在运行", "info")

    def _refresh_auto_status_label(self):
        """1s 心跳：只根据线程真实状态刷新"运行/未运行"标签与 Start/Stop 按钮。"""
        running = self._auto_trader is not None and self._auto_trader.is_running()
        try:
            if running:
                self.a_status_running.setText("状态：● 运行中")
                self.a_status_running.setObjectName("SectionTitle")
                self.a_btn_start.setEnabled(False)
                self.a_btn_stop.setEnabled(True)
            else:
                self.a_status_running.setText("状态：● 未运行")
                self.a_status_running.setObjectName("Warn")
                self.a_btn_start.setEnabled(True)
                self.a_btn_stop.setEnabled(False)
                # 线程已结束时回收引用，并停掉心跳定时器
                if self._auto_trader is not None and not self._auto_trader.is_alive():
                    self._auto_trader = None
                    self._auto_status_timer.stop()
            self.a_status_running.style().unpolish(self.a_status_running)
            self.a_status_running.style().polish(self.a_status_running)
        except Exception:
            pass

    # ---------- 自动交易：订单表格 ----------
    def _load_saved_orders_to_table(self):
        """主界面初始化时，从本地 okx_orders_history.json 加载已有订单历史显示到表格"""
        try:
            history = load_orders_history()
            for ev in reversed(history):
                self._on_auto_order(ev, save_to_file=False)
        except Exception as e:
            self._log(f"加载历史订单发生异常: {e}", "warn")

    def _clear_auto_orders(self):
        """清空当前显示的订单表格及本地历史文件"""
        if self.a_orders_table.rowCount() == 0:
            QMessageBox.information(self, "提示", "当前订单表格为空")
            return
        box = QMessageBox(self)
        box.setIcon(QMessageBox.Icon.Question)
        box.setWindowTitle("确认清空订单表格")
        box.setText("确认清空全部订单表格数据与本地历史记录吗？\n清空后不可撤销。")
        box.setStandardButtons(QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No)
        box.setDefaultButton(QMessageBox.StandardButton.No)
        if box.exec() == QMessageBox.StandardButton.Yes:
            self.a_orders_table.setRowCount(0)
            self.a_orders_count.setText("(0 条)")
            try:
                if ORDERS_HISTORY_FILE.exists():
                    ORDERS_HISTORY_FILE.unlink()
            except Exception:
                pass
            self._log("已清空订单表格与历史记录文件", "ok")

    def _on_auto_order(self, ev, save_to_file=True):
        try:
            if save_to_file and ev:
                save_order_history_item(ev)

            row = 0
            self.a_orders_table.insertRow(row)

            action = str(ev.get("action", ""))
            side   = str(ev.get("side", ""))
            pnl    = ev.get("pnl")
            mode   = "模拟" if ev.get("simulate", True) else "实盘"

            def _mk(text, color=None):
                it = QTableWidgetItem(str(text))
                if color is not None:
                    it.setForeground(QColor(color))
                it.setTextAlignment(Qt.AlignmentFlag.AlignCenter)
                return it

            price = ev.get("price")
            entry = ev.get("entry")

            if price is None:
                price_txt = "-"
            else:
                try:
                    price_txt = f"{float(price):.6g}"
                except Exception:
                    price_txt = str(price)

            if entry is None:
                entry_txt = "-"
            else:
                try:
                    entry_txt = f"{float(entry):.6g}"
                except Exception:
                    entry_txt = str(entry)

            size_txt = "-"
            if ev.get("size") is not None:
                try:
                    size_txt = f"{float(ev['size']):.6g}"
                except Exception:
                    size_txt = str(ev["size"])

            # PnL 文本：CLOSE 行同时展示金额与盈利率（ROE = pnl/保证金，已含杠杆）
            if pnl is None:
                pnl_txt = "-"
                pnl_color = None
            else:
                try:
                    pnl_val = float(pnl)
                    pnl_pct = 0.0
                    margin = ev.get("margin")
                    if margin:
                        pnl_pct = pnl_val / float(margin) * 100.0
                    pnl_txt = f"{pnl_val:+.2f} ({pnl_pct:+.2f}%)"
                    pnl_color = self._up_bright() if pnl_val >= 0 else self._down_bright()
                except Exception:
                    pnl_txt = str(pnl)
                    pnl_color = None

            action_color = self._up_bright() if action == "OPEN" else self._down_bright()

            cells = [
                _mk(ev.get("ts", ""),                     C_TEXT_S),
                _mk(mode),
                _mk(str(ev.get("inst", ""))),
                _mk(action, action_color),
                _mk(side.upper() if side else ""),
                _mk(size_txt),
                _mk(price_txt),
                _mk(entry_txt),
                _mk(pnl_txt, pnl_color),
                _mk(str(ev.get("reason", ""))),
            ]
            for c, it in enumerate(cells):
                self.a_orders_table.setItem(row, c, it)

            # 限制历史条数，避免无限增长
            MAX_ROWS = 500
            while self.a_orders_table.rowCount() > MAX_ROWS:
                self.a_orders_table.removeRow(self.a_orders_table.rowCount() - 1)

            self.a_orders_count.setText(f"({self.a_orders_table.rowCount()} 条)")

            # 如果订单品种与当前 K 线画板展示的品种一致，将订单 Marker 实时绘制在 Plotly K线图上
            try:
                cur_inst = self.k_pair.currentText().strip().upper()
                if ev.get("inst") == cur_inst and ev.get("price"):
                    payload = json.dumps(ev)
                    js = f"if (window.__addOrderMarker) window.__addOrderMarker({payload});"
                    self.web.page().runJavaScript(js)
            except Exception:
                pass
        except Exception as e:
            self._log(f"更新订单列表发生异常: {e}", "err")

    def _at_manual_close(self):
        """手动平掉当前持仓。订单为重要数据不可清空，只可平仓。需强确认。"""
        trader = self._auto_trader
        if trader is None or not trader.is_running():
            QMessageBox.information(self, "提示", "自动交易未运行，无仓位可平")
            return
        pos = trader.position
        if pos is None:
            QMessageBox.information(self, "提示", "当前无持仓")
            return

        lp = trader._last_px
        pnl_now = 0.0; pnl_pct = 0.0
        if lp is not None and pos.get("entry"):
            sign = 1 if pos["side"] == "long" else -1
            pnl_now = (lp - pos["entry"]) * pos["size"] * sign
            margin = pos.get("margin")
            if margin:
                pnl_pct = pnl_now / margin * 100.0

        mode_txt = "模拟" if trader.cfg.get("simulate", True) else "【实盘】"
        summary = (
            f"品种：{trader.cfg.get('inst')}\n"
            f"方向：{pos['side'].upper()}\n"
            f"数量：{pos['size']:.6g}\n"
            f"开仓价：{pos['entry']:.6g}\n"
            f"现价：{lp if lp is not None else '--'}\n"
            f"预计 PnL：{pnl_now:+.2f} USDT ({pnl_pct:+.2f}%)"
        )
        box = QMessageBox(self)
        box.setIcon(QMessageBox.Icon.Critical)
        box.setWindowTitle(f"⚠ 手动平仓强确认 · {mode_txt}")
        box.setText("即将以最新市价强制平掉当前持仓，操作不可撤销。\n"
                    "订单历史将保留一条 CLOSE 记录，无法清空。\n\n" + summary +
                    "\n\n确认平仓？")
        box.setStandardButtons(QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No)
        box.setDefaultButton(QMessageBox.StandardButton.No)
        yes_btn = box.button(QMessageBox.StandardButton.Yes)
        if yes_btn is not None:
            yes_btn.setText("确认平仓")
        no_btn = box.button(QMessageBox.StandardButton.No)
        if no_btn is not None:
            no_btn.setText("取消")
        if box.exec() != QMessageBox.StandardButton.Yes:
            self._log("已取消手动平仓", "info")
            return
        # 交由 AutoTrader 主循环安全地执行（跨线程只用事件通信）
        trader.request_manual_close()
        self._log("已请求手动平仓，等待下轮循环执行…", "warn")

    def _refresh_sim_status_labels(self):
        acct = self._sim_account
        self.a_status_balance.setText(f"模拟余额：{acct.balance:,.2f} USDT (初始 {acct.initial_balance:,.2f})")
        self.a_status_pnl.setText(f"已实现盈亏：{acct.realized_pnl:+,.2f} USDT")
        wr = (acct.wins / acct.trades * 100.0) if acct.trades else 0.0
        self.a_status_trades.setText(f"交易次数：{acct.trades}（胜 {acct.wins} / 胜率 {wr:.1f}%）")
        pf_txt = f"{acct.profit_factor:.2f}" if acct.profit_factor else "0.00"
        self.a_status_max_dd.setText(
            f"最大回撤：{acct.max_drawdown:.2f}% ｜ 盈亏比：{pf_txt} ｜ 连亏：{acct.consecutive_losses}次"
        )

    def _export_auto_orders_csv(self):
        """导出自动交易订单表格为 CSV 文件"""
        from PyQt6.QtWidgets import QFileDialog
        if self.a_orders_table.rowCount() == 0:
            QMessageBox.information(self, "提示", "暂无订单数据可导出")
            return
        default_name = f"okx_orders_{datetime.now().strftime('%Y%m%d_%H%M%S')}.csv"
        path, _ = QFileDialog.getSaveFileName(
            self, "导出订单历史", default_name, "CSV Files (*.csv)"
        )
        if not path:
            return
        try:
            import csv
            with open(path, "w", newline="", encoding="utf-8-sig") as f:
                writer = csv.writer(f)
                headers = []
                for col in range(self.a_orders_table.columnCount()):
                    headers.append(self.a_orders_table.horizontalHeaderItem(col).text())
                writer.writerow(headers)
                for row in range(self.a_orders_table.rowCount()):
                    row_data = []
                    for col in range(self.a_orders_table.columnCount()):
                        it = self.a_orders_table.item(row, col)
                        row_data.append(it.text() if it else "")
                    writer.writerow(row_data)
            self._log(f"已导出 {self.a_orders_table.rowCount()} 条订单到 {path}", "ok")
            QMessageBox.information(self, "导出成功", f"订单历史已成功导出至:\n{path}")
        except Exception as e:
            QMessageBox.critical(self, "导出失败", f"导出 CSV 异常: {e}")

    # ---------- 自动交易：按钮/信号处理 ----------
    def _at_reset_sim(self, amount):
        if self._auto_trader is not None and self._auto_trader.is_running():
            QMessageBox.warning(self, "提示", "请先停止自动交易再重置模拟账户")
            return
        try:
            amount = float(amount)
        except Exception:
            QMessageBox.warning(self, "提示", "金额无效")
            return
        if amount <= 0:
            QMessageBox.warning(self, "提示", "金额必须大于 0")
            return
        self._sim_account.reset(amount)
        self._refresh_sim_status_labels()
        self._log(f"模拟账户已重置为 {amount:,.2f} USDT", "ok")

    def _at_collect_cfg(self):
        inst = self.a_pair.currentText().strip().upper()
        if not inst:
            QMessageBox.warning(self, "提示", "请填写交易对"); return None
        kind = self.a_kind.currentData()
        if kind == "swap" and not inst.endswith("-SWAP"):
            if inst.count("-") == 1:
                inst = f"{inst}-SWAP"
        cfg = {
            "inst":                   inst,
            "bar":                    self.a_bar.currentText(),
            "interval":               int(self.a_interval.value()),
            "kind":                   kind,
            "mgn_mode":               self.a_mgn.currentData(),
            "pos_side":               self.a_pos.currentData(),
            "leverage":               float(self.a_lev.value()),
            "size_type":              self.a_size_type.currentData(),
            "size_value":             float(self.a_size_val.value()),
            "tp_pct":                 float(self.a_tp.value()),
            "sl_pct":                 float(self.a_sl.value()),
            "trailing_sl_pct":        float(self.a_trailing_sl.value()),
            "max_drawdown_limit":     float(self.a_max_dd.value()),
            "max_consecutive_losses": int(self.a_max_losses.value()),
            "grid_lower":             float(self.a_grid_lower.value()),
            "grid_upper":             float(self.a_grid_upper.value()),
            "exit_on_reverse":        self.a_exit_reverse.isChecked(),
            "allow_short":            self.a_allow_short.isChecked(),
            "strategy":               self.a_strategy.currentData(),
            "simulate":               self.a_sim.isChecked(),
            "ref_balance":            self._sim_account.balance,
        }
        return cfg

    def _at_start(self):
        if self._auto_trader is not None and self._auto_trader.is_running():
            QMessageBox.information(self, "提示", "自动交易已在运行"); return
        cfg = self._at_collect_cfg()
        if cfg is None:
            return
        if not cfg["simulate"]:
            if not self._check_credentials():
                return
            box = QMessageBox(self)
            box.setIcon(QMessageBox.Icon.Warning)
            box.setWindowTitle("实盘自动交易二次确认")
            box.setText(
                "即将启动【实盘】自动交易，所有信号会真实下单。\n"
                f"交易对: {cfg['inst']}  周期: {cfg['bar']}\n"
                f"品类: {cfg['kind']}  策略: {cfg['strategy']}\n"
                f"止盈: {cfg['tp_pct']}%  止损: {cfg['sl_pct']}%\n\n"
                "确认继续？")
            box.setStandardButtons(QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No)
            box.setDefaultButton(QMessageBox.StandardButton.No)
            if box.exec() != QMessageBox.StandardButton.Yes:
                self._log("已取消启动实盘自动交易", "info"); return

        self._auto_trader = AutoTrader(self, cfg)
        self._auto_trader.start()
        self._refresh_auto_status_label()
        if not self._auto_status_timer.isActive():
            self._auto_status_timer.start()

    def _at_stop(self):
        if self._auto_trader is None:
            return
        self._auto_trader.stop()
        self._log("已请求停止自动交易", "info")
        self.a_btn_stop.setEnabled(False)

    def _on_auto_state(self, state):
        """poll 间隔到达后的账户/持仓刷新。"""
        try:
            if state.get("sim"):
                self.a_status_balance.setText(
                    f"模拟余额：{state.get('balance', 0):,.2f} USDT "
                    f"(初始 {state.get('initial', 0):,.2f})")
                self.a_status_pnl.setText(
                    f"已实现盈亏：{state.get('realized', 0):+,.2f} USDT")
                tr = state.get("trades", 0); wn = state.get("wins", 0)
                wr = (wn / tr * 100.0) if tr else 0.0
                self.a_status_trades.setText(
                    f"交易次数：{tr}（胜 {wn} / 胜率 {wr:.1f}%）")
                dd = state.get("max_drawdown", 0.0)
                pf = state.get("profit_factor", 0.0)
                cl = state.get("consecutive_losses", 0)
                self.a_status_max_dd.setText(
                    f"最大回撤：{dd:.2f}% ｜ 盈亏比：{pf:.2f} ｜ 连亏：{cl}次"
                )
            else:
                self._refresh_sim_status_labels()

            pos = state.get("position")
            lp  = state.get("last_px")
            if pos:
                pnl_now = 0.0
                pnl_pct = 0.0
                if lp is not None and pos.get("entry"):
                    sign = 1 if pos["side"] == "long" else -1
                    pnl_now = (lp - pos["entry"]) * pos["size"] * sign
                    margin = pos.get("margin")
                    if margin:
                        pnl_pct = pnl_now / margin * 100.0
                lp_txt = "--" if lp is None else f"{lp:.6g}"
                self.a_status_pos.setText(
                    f"持仓：{pos['side'].upper()} 数量 {pos['size']:.6g} @ {pos['entry']:.6g} "
                    f"｜ 现价 {lp_txt} ｜ 浮动 PnL {pnl_now:+.2f} ({pnl_pct:+.3f}%)")
                self._live_update_open_row(lp, pnl_now, pnl_pct)
            else:
                self.a_status_pos.setText("持仓：无")
        except Exception:
            pass

    def _live_update_open_row(self, last_px, pnl_now, pnl_pct):
        """
        订单表首行若为当前持仓的 OPEN 记录，则用最新市场价与浮动盈亏刷新
        『价格』(col 6) 和『PnL(USDT)』(col 8) 两列，让用户实时看到盈利率。
        """
        try:
            if self.a_orders_table.rowCount() == 0:
                return
            action_item = self.a_orders_table.item(0, 3)
            if action_item is None or action_item.text() != "OPEN":
                return
            if last_px is not None:
                cell_price = self.a_orders_table.item(0, 6)
                if cell_price is None:
                    cell_price = QTableWidgetItem()
                    cell_price.setTextAlignment(Qt.AlignmentFlag.AlignCenter)
                    self.a_orders_table.setItem(0, 6, cell_price)
                cell_price.setText(f"{last_px:.6g}")
            # PnL 列以 "+1.23 (+0.45%)" 展示；颜色随涨跌配色
            cell_pnl = self.a_orders_table.item(0, 8)
            if cell_pnl is None:
                cell_pnl = QTableWidgetItem()
                cell_pnl.setTextAlignment(Qt.AlignmentFlag.AlignCenter)
                self.a_orders_table.setItem(0, 8, cell_pnl)
            cell_pnl.setText(f"{pnl_now:+.2f} ({pnl_pct:+.2f}%)")
            color = self._up_bright() if pnl_now >= 0 else self._down_bright()
            cell_pnl.setForeground(QColor(color))
        except Exception:
            pass

    def _build_output_panel(self):
        card = QFrame(); card.setObjectName("Card")
        lay = QVBoxLayout(card); lay.setContentsMargins(12, 10, 12, 10); lay.setSpacing(6)

        top = QHBoxLayout()
        t = QLabel("输出结果"); t.setObjectName("SectionTitle")
        top.addWidget(t)
        self.out_time = QLabel(""); self.out_time.setObjectName("Hint")
        top.addWidget(self.out_time)
        top.addStretch(1)
        clr = QPushButton("清空"); clr.setObjectName("Ghost")
        clr.clicked.connect(lambda: self.output.clear())
        top.addWidget(clr)
        lay.addLayout(top)

        self.output = QTextEdit()
        self.output.setReadOnly(True)
        lay.addWidget(self.output, 1)
        return card

    # ---------- 图表 shell ----------
    def _install_chart_shell(self):
        """一次性加载 plotly.js 与占位 div，后续用 runJavaScript 增量渲染"""
        html = f"""<!DOCTYPE html>
<html><head>
<meta charset="utf-8">
<script src="https://cdn.plot.ly/plotly-2.35.2.min.js"></script>
<style>
html,body {{ margin:0; padding:0; background:{C_BG2}; height:100%; overflow:hidden;
             font-family:Microsoft YaHei; color:{C_TEXT_S}; }}
#chart {{ width:100vw; height:100vh; }}
#hint  {{ position:fixed; top:50%; left:50%; transform:translate(-50%,-50%);
         font-size:14px; color:{C_TEXT_S}; }}
</style>
</head><body>
<div id="hint">选择交易对/周期后自动加载 K 线数据</div>
<div id="chart"></div>
<script>
window.__plotReady = false;
window.__renderChart = function(figJson, config) {{
    var hint = document.getElementById('hint');
    if (hint) hint.style.display = 'none';
    var fig = JSON.parse(figJson);
    if (window.__plotReady) {{
        Plotly.react('chart', fig.data, fig.layout, config);
    }} else {{
        Plotly.newPlot('chart', fig.data, fig.layout, config).then(function() {{
            window.__plotReady = true;
        }});
    }}
}};

// 实时增量更新最后一根蜡烛 & 成交量柱（由 WS ticker/candle 推送触发）
window.__updateLastCandle = function(u) {{
    var gd = document.getElementById('chart');
    if (!gd || !gd.data || !gd.data.length) return;
    // 找到 candlestick trace 与 volume bar trace
    var candIdx = -1, volIdx = -1;
    for (var i = 0; i < gd.data.length; i++) {{
        if (gd.data[i].type === 'candlestick' && candIdx < 0) candIdx = i;
        else if (gd.data[i].type === 'bar' && volIdx < 0 && gd.data[i].name === '成交量') volIdx = i;
    }}
    if (candIdx < 0) return;
    var d = gd.data[candIdx];
    var n = d.x.length;
    if (!n) return;
    var lastX = d.x[n - 1];
    // 时间戳一致 → 原地更新；否则追加新一根（新周期开始）
    if (lastX === u.ts) {{
        d.open[n-1] = u.o; d.high[n-1] = u.h;
        d.low[n-1]  = u.l; d.close[n-1] = u.c;
    }} else if (u.ts > lastX) {{
        d.x.push(u.ts);
        d.open.push(u.o); d.high.push(u.h);
        d.low.push(u.l);  d.close.push(u.c);
    }} else {{
        return; // 更旧的推送忽略
    }}
    // 同步成交量柱
    if (volIdx >= 0) {{
        var vd = gd.data[volIdx];
        var vn = vd.x.length;
        var color = (u.c >= u.o) ? u.up : u.dn;
        if (vd.x[vn-1] === u.ts) {{
            vd.y[vn-1] = u.v;
            if (Array.isArray(vd.marker.color)) vd.marker.color[vn-1] = color;
        }} else if (u.ts > vd.x[vn-1]) {{
            vd.x.push(u.ts); vd.y.push(u.v);
            if (Array.isArray(vd.marker.color)) vd.marker.color.push(color);
        }}
    }}
    Plotly.redraw(gd);
}};

// 订单买卖/平仓点位 Marker 标注
window.__addOrderMarker = function(ev) {{
    var gd = document.getElementById('chart');
    if (!gd || !gd.layout || !ev.ts || !ev.price) return;
    var isLong = (ev.side === 'LONG' || ev.side === 'long');
    var isOpen = (ev.action === 'OPEN');
    var color = isOpen ? (isLong ? '#22c55e' : '#ef4444') : '#f59e0b';
    var text = (isOpen ? (isLong ? '▲买入' : '▼卖出') : '✖平仓') + ' ' + (ev.price ? Number(ev.price).toFixed(2) : '');
    var ann = {{
        x: ev.ts,
        y: ev.price,
        text: text,
        showarrow: true,
        arrowhead: 2,
        ax: 0,
        ay: isLong ? 24 : -24,
        bgcolor: color,
        font: {{color: '#ffffff', size: 10, family: 'Microsoft YaHei'}},
        bordercolor: color,
        borderwidth: 1,
        borderpad: 2,
        opacity: 0.85
    }};
    var anns = gd.layout.annotations || [];
    anns.push(ann);
    Plotly.relayout('chart', {{annotations: anns}});
}};
</script>
</body></html>"""
        self.web.setHtml(html)

    def _on_chart_shell_ready(self, ok):
        self._chart_shell_loaded = bool(ok)
        if self._chart_shell_loaded and self._pending_fig is not None:
            fig_json, config = self._pending_fig
            self._pending_fig = None
            self._inject_chart(fig_json, config)

    def _inject_chart(self, fig_json, config):
        # 用 JSON.dumps 二次编码把 JSON 变成 JS 字符串字面量，避免转义问题
        js_fig_literal = json.dumps(fig_json)
        js_cfg = json.dumps(config)
        js = f"window.__renderChart({js_fig_literal}, {js_cfg});"
        self.web.page().runJavaScript(js)

    # ---------- 环境/凭证 ----------
    def _get_flag(self):
        return "1" if self.rb_sim.isChecked() else "0"

    def _get_proxy(self):
        """返回代理 URL 字符串（若 UI 留空则回退到环境变量），无则 None"""
        p = ""
        try:
            p = self.in_proxy.text().strip()
        except Exception:
            p = ""
        if not p:
            p = (os.environ.get("HTTPS_PROXY") or os.environ.get("https_proxy")
                 or os.environ.get("ALL_PROXY") or os.environ.get("all_proxy") or "")
        return p or None

    def _get_credentials(self):
        return (self.in_key.text().strip(),
                self.in_sec.text().strip(),
                self.in_pass.text().strip(),
                self._get_flag())

    def _check_credentials(self):
        k, s, p, _ = self._get_credentials()
        if not k or k == "-1":
            QMessageBox.warning(self, "提示", "请先配置 API Key"); return False
        if not s or s == "-1":
            QMessageBox.warning(self, "提示", "请先配置 API Secret"); return False
        if not p or p == "-1":
            QMessageBox.warning(self, "提示", "请先配置 Passphrase"); return False
        return True

    def _apply_client_config(self, api):
        """OKX SDK 里的 API 类继承自 httpx.Client；构造后可覆盖默认超时"""
        try:
            import httpx as _httpx
            api.timeout = _httpx.Timeout(REST_READ_TIMEOUT, connect=REST_CONNECT_TIMEOUT)
        except Exception:
            pass
        return api

    def _get_market_api(self):
        return self._apply_client_config(
            MarketData.MarketAPI(flag=self._get_flag(), proxy=self._get_proxy()))

    def _get_account_api(self):
        k, s, p, f = self._get_credentials()
        return self._apply_client_config(
            Account.AccountAPI(k, s, p, flag=f, proxy=self._get_proxy()))

    def _get_funding_api(self):
        k, s, p, f = self._get_credentials()
        return self._apply_client_config(
            Funding.FundingAPI(k, s, p, flag=f, proxy=self._get_proxy()))

    def _get_trade_api(self):
        k, s, p, f = self._get_credentials()
        return self._apply_client_config(
            Trade.TradeAPI(k, s, p, flag=f, proxy=self._get_proxy()))

    def _get_public_api(self):
        return self._apply_client_config(
            PublicData.PublicAPI(flag=self._get_flag(), proxy=self._get_proxy()))

    # ---------- 事件 / 槽 ----------
    def _on_env_changed(self):
        if self.rb_sim.isChecked():
            self.hdr_badge.setText("模拟盘")
            self.hdr_badge.setObjectName("BadgeSim")
        else:
            self.hdr_badge.setText("实盘")
            self.hdr_badge.setObjectName("BadgeReal")
        self.hdr_badge.style().unpolish(self.hdr_badge)
        self.hdr_badge.style().polish(self.hdr_badge)
        # 同步 WS 端点
        if hasattr(self, "_ws") and self._ws is not None:
            self._ws.set_flag(self._get_flag())

    def _apply_proxy(self):
        """应用代理设置：更新 WS 与后续 REST 调用"""
        proxy = self._get_proxy()
        if hasattr(self, "_ws") and self._ws is not None:
            self._ws.set_proxy(proxy)
        # REST 调用每次 _get_*_api() 都会读取 self.in_proxy，无需手动同步
        self._log(f"代理已应用：{proxy or '（未设置）'}，WebSocket 将重连", "info")

    # ---------- WebSocket 实时行情 ----------
    def _on_ws_status(self, kind, ok):
        # 后台线程调用，转到主线程
        self.conn_signal.emit(bool(ok))

    def _on_ws_ticker(self, d):
        # 只暂存最新一条，UI 层由 QTimer 节流合并刷新
        # 过滤：只接受当前币对
        try:
            if self._cur_inst is not None and d.get("instId") and d["instId"] != self._cur_inst:
                return
        except Exception:
            pass
        self._last_ticker_data = d

    def _flush_ticker_ui(self):
        d = self._last_ticker_data
        if not d:
            return
        try:
            last = float(d.get("last"))
            open24h = float(d.get("open24h", last))
            pct = (last - open24h) / open24h * 100 if open24h else 0.0
            direction = "1" if pct >= 0 else "0"
            self._on_price(f"{last:,.4f}".rstrip("0").rstrip(".") if last < 1 else f"{last:,.2f}",
                           f"{pct:+.2f}%", direction)
        except Exception:
            pass

    def _on_ws_candle(self, instId, channel, data):
        """
        实时 K 线：OKX 会推送当前未收盘的最后一根，格式与 REST 相同：
        [ts, o, h, l, c, vol, volCcy, volCcyQuote, confirm]
        通过 JS 调用 Plotly.restyle 原地更新最后一根蜡烛 & 成交量柱
        """
        if not data:
            return
        # 过滤：只处理当前展示币对；channel 形如 candle1H，要与 _cur_bar 一致
        if self._cur_inst is not None and instId != self._cur_inst:
            return
        if self._cur_bar is not None:
            expect = f"candle{self._cur_bar}"
            if channel and channel != expect:
                return
        try:
            item = data[0]
            ts_ms = int(item[0])
            ts_iso = datetime.fromtimestamp(ts_ms / 1000, tz=timezone.utc).isoformat()
            o = float(item[1]); h = float(item[2]); l = float(item[3])
            c = float(item[4]); v = float(item[5])
            payload = json.dumps({"ts": ts_iso, "o": o, "h": h, "l": l, "c": c, "v": v,
                                  "up": self._up_color(), "dn": self._down_color()})
            js = f"if (window.__updateLastCandle) window.__updateLastCandle({payload});"
            self.web.page().runJavaScript(js)
        except Exception:
            pass

    # ---------- 涨跌配色 ----------
    def _up_color(self):
        return C_GREEN if self._up_is_green else C_RED

    def _up_bright(self):
        return C_GREEN_BR if self._up_is_green else C_RED_BR

    def _down_color(self):
        return C_RED if self._up_is_green else C_GREEN

    def _down_bright(self):
        return C_RED_BR if self._up_is_green else C_GREEN_BR

    def _apply_color_scheme(self):
        """重建 QSS 并刷新所有涨跌相关控件"""
        qss = build_qss(self._up_color(), self._up_bright(),
                        self._down_color(), self._down_bright())
        app = QApplication.instance()
        if app is not None:
            app.setStyleSheet(qss)
        # 刷新按钮文本
        if hasattr(self, "btn_color"):
            label = "🟢涨/🔴跌" if self._up_is_green else "🔴涨/🟢跌"
            self.btn_color.setText(label)
        # 刷新价格标签样式（QSS 换了但 ObjectName 未变，需 unpolish/polish）
        for w in (getattr(self, "hdr_price", None),
                  getattr(self, "hdr_change", None),
                  getattr(self, "hdr_badge", None),
                  getattr(self, "hdr_dot", None)):
            if w is not None:
                w.style().unpolish(w); w.style().polish(w)

    def _toggle_color_scheme(self):
        self._up_is_green = not self._up_is_green
        self._apply_color_scheme()
        # 图表颜色也要跟着变，重新刷新
        self._refresh_chart()
        mode = "绿涨红跌" if self._up_is_green else "红涨绿跌"
        self._log(f"配色已切换：{mode}", "info")

    def _on_show_pwd(self, checked):
        mode = QLineEdit.EchoMode.Normal if checked else QLineEdit.EchoMode.Password
        self.in_sec.setEchoMode(mode)
        self.in_pass.setEchoMode(mode)

    def _on_pair_changed(self, txt):
        if txt:
            self.hdr_pair.setText(txt)

    def _on_escape(self):
        if self._is_fullscreen_chart:
            self._toggle_fullscreen_chart()
        elif self._is_fullscreen_auto:
            self._toggle_fullscreen_auto()

    def _on_log(self, msg, tag):
        color = {"ok": C_DOWN_BR, "err": C_UP_BR, "info": C_TEXT_S,
                 "warn": "#e3b341"}.get(tag, C_TEXT)
        ts = f"<span style='color:{C_TEXT_D};'>[{now_hms()}]</span>"
        line = f"{ts} <span style='color:{color};'>{msg}</span>"
        self.output.append(line)
        self.out_time.setText(now_hms())
        sb = self.output.verticalScrollBar()
        sb.setValue(sb.maximum())

    def _on_result(self, title, data):
        try:
            body = json.dumps(data, ensure_ascii=False, indent=2)
        except Exception:
            body = str(data)
        code = ""
        msg = ""
        n = 0
        if isinstance(data, dict):
            code = str(data.get("code", ""))
            msg = str(data.get("msg", ""))
            d = data.get("data")
            if isinstance(d, list):
                n = len(d)
        sep = "─" * 56
        html = (
            f"<pre style='color:{C_TEXT};font-family:Consolas;font-size:9pt;margin:6px 0;'>"
            f"<span style='color:{C_PRIMARY_L};'>{sep}</span>\n"
            f"  <b>{title}</b>\n"
            f"<span style='color:{C_PRIMARY_L};'>{sep}</span>\n"
            f"  返回码: <span style='color:{C_DOWN_BR}'>{code}</span>\n"
            f"  消息: {msg}\n"
            f"  数据: {n} 条\n"
            f"<span style='color:{C_PRIMARY_L};'>{sep}</span>\n"
            f"{body}"
            f"</pre>"
        )
        self.output.append(html)
        self.out_time.setText(now_hms())
        sb = self.output.verticalScrollBar()
        sb.setValue(sb.maximum())

    def _on_price(self, price, change, direction):
        self.hdr_price.setText(price)
        if direction == "1":
            self.hdr_price.setObjectName("PriceUp")
            self.hdr_change.setObjectName("ChangeUp")
        elif direction == "0":
            self.hdr_price.setObjectName("PriceDown")
            self.hdr_change.setObjectName("ChangeDown")
        else:
            self.hdr_price.setObjectName("PriceNeutral")
            self.hdr_change.setObjectName("ChangeNeutral")
        for w in (self.hdr_price, self.hdr_change):
            w.style().unpolish(w); w.style().polish(w)
        self.hdr_change.setText(change)

    def _on_conn(self, ok):
        if ok:
            self.hdr_dot.setObjectName("DotOn"); self.hdr_dot.setToolTip("已连接")
        else:
            self.hdr_dot.setObjectName("DotOff"); self.hdr_dot.setToolTip("未连接")
        self.hdr_dot.style().unpolish(self.hdr_dot); self.hdr_dot.style().polish(self.hdr_dot)

    # ---------- 日志封装 ----------
    def _log(self, msg, tag="info"):
        self.log_signal.emit(msg, tag)

    # ---------- 异步执行 ----------
    def _run_async(self, func, *args):
        self._log("请求中...", "info")
        threading.Thread(target=self._run_safe, args=(func, *args), daemon=True).start()

    def _run_safe(self, func, *args):
        import time as _time
        try:
            import httpx as _httpx
            _timeout_excs = (_httpx.ConnectTimeout, _httpx.ReadTimeout,
                             _httpx.ConnectError, _httpx.RemoteProtocolError)
        except Exception:
            _timeout_excs = ()

        # 心跳线程：每 10s 提醒一次"仍在请求"，让用户知道程序未卡死
        heartbeat_stop = threading.Event()
        def _heartbeat():
            elapsed = 0
            while not heartbeat_stop.wait(10):
                elapsed += 10
                self.log_signal.emit(
                    f"仍在等待 OKX 响应（已 {elapsed}s，国内网络首次连接较慢，"
                    f"配置本地代理可加速）", "info")
        hb = threading.Thread(target=_heartbeat, daemon=True)
        hb.start()

        attempts = 0
        last_exc = None
        # 重试间隔（秒）：首次失败可能只是刚建立完握手，几乎立刻重试更容易成功
        backoffs = [0.3, 1.5, 3.0]
        try:
            while attempts < REST_MAX_RETRIES:
                attempts += 1
                try:
                    func(*args)
                    return
                except _timeout_excs as e:
                    last_exc = e
                    if attempts >= REST_MAX_RETRIES:
                        break
                    delay = backoffs[min(attempts - 1, len(backoffs) - 1)]
                    self.log_signal.emit(
                        f"首次连接超时（{type(e).__name__}），{delay:g}s 后重试 "
                        f"({attempts}/{REST_MAX_RETRIES})…", "warn")
                    _time.sleep(delay)
                except Exception as e:
                    self.log_signal.emit(f"异常: {type(e).__name__}: {e}", "err")
                    self.conn_signal.emit(False)
                    return
        finally:
            heartbeat_stop.set()
        # 所有重试都失败
        self.log_signal.emit(
            f"异常: {type(last_exc).__name__}: {last_exc}（已重试 {REST_MAX_RETRIES} 次；"
            f"请在'代理'字段配置本地代理，例如 http://127.0.0.1:7890）",
            "err")
        self.conn_signal.emit(False)

    # ==================== K线 ====================
    def _schedule_refresh(self):
        self._debounce_timer.start(300)

    def _apply_refresh_interval(self):
        key = self.k_refresh.currentText()
        ms = REFRESH_MAP.get(key, 0)
        if ms <= 0:
            self._refresh_timer.stop()
        else:
            self._refresh_timer.start(ms)

    def _refresh_chart(self):
        inst  = self.k_pair.currentText().strip().upper() or "BTC-USDT"
        bar   = self.k_bar.currentText().strip() or "1H"
        # 切换币对/周期时，先把画面复位，避免旧图残留 + 让下游按新值过滤
        pair_changed = (self._cur_inst != inst) or (self._cur_bar != bar)
        self._cur_inst = inst
        self._cur_bar  = bar
        if pair_changed:
            # 清屏：投一个空 figure，防止旧币对的 K 线残留
            try:
                self.web.page().runJavaScript(
                    "if (window.Plotly && document.getElementById('chart')) "
                    "Plotly.purge('chart');")
            except Exception:
                pass
            self._last_ticker_data = None  # 丢弃旧币对最后一条 ticker
        try:
            limit = int(self.k_limit.text().strip() or "100")
            limit = max(10, min(limit, 300))
        except Exception:
            limit = 100
        show_vol = self.cb_vol.isChecked()
        show_ma  = self.cb_maline.isChecked()
        show_magic = self.cb_magic.isChecked()
        ma_txt   = self.k_ma.text().strip()
        magic_mode = "strict" if self.k_magic_mode.currentIndex() == 0 else "classic"
        magic_lookback = int(self.sp_magic_lookback.value())
        show_macd = self.cb_macd.isChecked()
        show_rsi  = self.cb_rsi.isChecked()
        show_kdj  = self.cb_kdj.isChecked()

        self._run_async(self._fetch_kline, inst, bar, str(limit),
                        show_vol, show_ma, show_magic, ma_txt, magic_mode, magic_lookback,
                        show_macd, show_rsi, show_kdj)
        # 同步刷新多周期评级面板
        try:
            self._refresh_verdict()
        except Exception:
            pass

    def _fetch_kline(self, inst, bar, limit, show_vol, show_ma, show_magic, ma_txt,
                     magic_mode="strict", magic_lookback=1,
                     show_macd=False, show_rsi=False, show_kdj=False):
        api = self._get_market_api()
        res = api.get_candlesticks(instId=inst, bar=bar, limit=limit)
        if not isinstance(res, dict) or res.get("code") not in ("0", 0):
            self.log_signal.emit(f"K线请求失败: {res}", "err")
            self.conn_signal.emit(False)
            return
        raw = list(res.get("data") or [])
        raw.reverse()

        ts, opens, highs, lows, closes, vols = [], [], [], [], [], []
        for item in raw:
            try:
                ts.append(datetime.fromtimestamp(int(item[0]) / 1000, tz=timezone.utc))
                opens.append(float(item[1]))
                highs.append(float(item[2]))
                lows.append(float(item[3]))
                closes.append(float(item[4]))
                vols.append(float(item[5]))
            except Exception:
                continue

        if not closes:
            self.log_signal.emit("K线数据为空", "err")
            return

        # Ticker → 头部价格
        try:
            tk = api.get_ticker(instId=inst)
            if isinstance(tk, dict) and tk.get("code") in ("0", 0):
                d = tk["data"][0]
                last = float(d["last"])
                open24h = float(d.get("open24h", last))
                pct = (last - open24h) / open24h * 100 if open24h else 0.0
                direction = "1" if pct >= 0 else "0"
                self.price_signal.emit(f"{last:,.2f}", f"{pct:+.2f}%", direction)
                self.conn_signal.emit(True)
        except Exception:
            pass

        self.chart_signal.emit({
            "inst": inst, "bar": bar,
            "ts": ts, "o": opens, "h": highs, "l": lows, "c": closes, "v": vols,
            "show_vol": show_vol, "show_ma": show_ma, "show_magic": show_magic,
            "ma_txt": ma_txt, "magic_mode": magic_mode, "magic_lookback": magic_lookback,
            "show_macd": show_macd, "show_rsi": show_rsi, "show_kdj": show_kdj,
        })

    # ---------- 隐秘枢轴 / Rumers Magic Lines ----------
    def _compute_magic_lines(self, ts, opens, highs, lows, closes, bar, mode="strict", lookback=1):
        """
        以 UTC 日为交易日边界，绘制 4 条枢轴线：
          R1 = 前一交易日 (D-1) 最高价
          S1 = 前一交易日 (D-1) 最低价
          R2 = 前 (1 + lookback) 交易日 (D-1-lookback) 最高价（若可得）
          S2 = 前 (1 + lookback) 交易日 (D-1-lookback) 最低价（若可得）
          lookback=0 → 不画 R2/S2；lookback=1 → D-2；lookback=N → D-(1+N)

        3K 线入场信号有两种模式：
          mode="classic":
            多头 = 当日连续 3 阳，第 3 根收盘 > R1，且第 1 根收盘 ≤ R1
            空头 = 当日连续 3 阴，第 3 根收盘 < S1，且第 1 根收盘 ≥ S1
          mode="strict":
            扫描 K2/K3 组合：
              K2 触及枢轴 L (low≤L≤high)
              K2 显示拒绝（下影线≥实体 或 反向收盘）
              K3 收盘突破 K2 高/低
            多头信号 L ∈ {S1, S2}；空头信号 L ∈ {R1, R2}

        每个交易日最多标记一多一空。
        返回: (shapes, annotations, buy_pts, sell_pts)
            buy_pts / sell_pts 元素: (x_timestamp, y_price, hover_text)
        """
        # 按 UTC 日聚合
        from collections import defaultdict
        day_bars = defaultdict(list)  # date -> [(idx, t, o, h, l, c)]
        for i, t in enumerate(ts):
            day_bars[t.date()].append((i, t, opens[i], highs[i], lows[i], closes[i]))
        sorted_days = sorted(day_bars.keys())
        if len(sorted_days) < 2:
            return [], [], [], []

        # 每一天的 OHLC 统计
        day_stats = {}
        for d in sorted_days:
            bars = day_bars[d]
            day_stats[d] = {
                "o": bars[0][2],
                "h": max(b[3] for b in bars),
                "l": min(b[4] for b in bars),
                "c": bars[-1][5],
                "start": bars[0][1],
                "end":   bars[-1][1],
            }

        shapes = []
        annotations = []
        buy_pts = []
        sell_pts = []

        try:
            lookback = max(0, int(lookback))
        except Exception:
            lookback = 1

        for i in range(1, len(sorted_days)):
            d_today  = sorted_days[i]
            d_prev   = sorted_days[i - 1]
            # R2/S2 对应 D-(1+lookback)；当 lookback==0 时不使用
            d_prev2 = None
            if lookback > 0:
                idx_prev2 = i - 1 - lookback
                if idx_prev2 >= 0:
                    d_prev2 = sorted_days[idx_prev2]
            prev = day_stats[d_prev]
            today_bars = day_bars[d_today]
            prev_bars  = day_bars[d_prev]

            day_end = day_stats[d_today]["end"]
            is_last_day = (i == len(sorted_days) - 1)

            # 计算 R1/R2/S1/S2 及其锚点 K 线
            R1 = prev["h"]
            S1 = prev["l"]
            prev_h_bar = max(prev_bars, key=lambda b: b[3])
            prev_l_bar = min(prev_bars, key=lambda b: b[4])

            R2 = S2 = None
            r2_origin = s2_origin = None
            if d_prev2 is not None:
                prev2_bars = day_bars[d_prev2]
                R2 = day_stats[d_prev2]["h"]
                S2 = day_stats[d_prev2]["l"]
                r2_origin = max(prev2_bars, key=lambda b: b[3])[1]
                s2_origin = min(prev2_bars, key=lambda b: b[4])[1]

            # 枢轴线规格：(label, level, color, dash, origin_time)
            line_specs = []
            if R1 is not None:
                line_specs.append(("R1", R1, C_RED_BR,   "dash", prev_h_bar[1]))
            if S1 is not None:
                line_specs.append(("S1", S1, C_GREEN_BR, "dash", prev_l_bar[1]))
            if R2 is not None:
                line_specs.append(("R2", R2, C_RED_BR,   "dot",  r2_origin))
            if S2 is not None:
                line_specs.append(("S2", S2, C_GREEN_BR, "dot",  s2_origin))

            # 只在最后一天绘制枢轴线（当前交易日）
            if is_last_day:
                for label, level, color, dash, origin_t in line_specs:
                    x0 = origin_t.isoformat()

                    # 找当日首个价格区间 [low, high] 覆盖此枢轴的 K 线 → 线止于此
                    end_t = day_end
                    touched = False
                    for b in today_bars:
                        if b[4] <= level <= b[3]:
                            end_t = b[1]
                            touched = True
                            break
                    x1 = end_t.isoformat()

                    shapes.append(dict(
                        type="line", xref="x", yref="y",
                        x0=x0, x1=x1, y0=level, y1=level,
                        line=dict(color=color, width=1.1, dash=dash),
                        layer="below",
                    ))
                    annotations.append(dict(
                        xref="x", yref="y",
                        x=x1, y=level,
                        text=f"{label} {level:,.2f}" + (" ✓" if touched else ""),
                        showarrow=False,
                        xanchor="left", yanchor="middle",
                        font=dict(color=color, size=10, family="Consolas"),
                        bgcolor="rgba(22,27,34,0.6)",
                    ))

            # ===== 3K 信号 =====
            support_levels = [("S1", S1), ("S2", S2)]
            support_levels = [(n, v) for n, v in support_levels if v is not None]
            resist_levels  = [("R1", R1), ("R2", R2)]
            resist_levels  = [(n, v) for n, v in resist_levels if v is not None]

            found_buy = False
            found_sell = False

            if mode == "classic":
                for j in range(2, len(today_bars)):
                    if found_buy and found_sell:
                        break
                    b0 = today_bars[j - 2]; b1 = today_bars[j - 1]; b2 = today_bars[j]
                    # b: (idx, t, o, h, l, c)
                    bull = (b0[5] > b0[2]) and (b1[5] > b1[2]) and (b2[5] > b2[2])
                    if (not found_buy and bull and R1 is not None
                            and b2[5] > R1 and b0[5] <= R1 and b2[5] <= R1 * 1.5):
                        y = min(b0[4], b1[4], b2[4]) * 0.999
                        buy_pts.append((b2[1], y, f"多头(简单) 3阳破 R1={R1:,.2f}"))
                        found_buy = True
                    bear = (b0[5] < b0[2]) and (b1[5] < b1[2]) and (b2[5] < b2[2])
                    if (not found_sell and bear and S1 is not None
                            and b2[5] < S1 and b0[5] >= S1 and b2[5] >= S1 * 0.5):
                        y = max(b0[3], b1[3], b2[3]) * 1.001
                        sell_pts.append((b2[1], y, f"空头(简单) 3阴破 S1={S1:,.2f}"))
                        found_sell = True

            else:  # mode == "strict"
                # 扫描 j 从 1 开始，K2 = today_bars[j], K3 = today_bars[j+1]
                # K1 = today_bars[0] 建立初始区间（仅参考）
                for j in range(1, len(today_bars) - 1):
                    if found_buy and found_sell:
                        break
                    k2 = today_bars[j]; k3 = today_bars[j + 1]
                    k2_o = k2[2]; k2_h = k2[3]; k2_l = k2[4]; k2_c = k2[5]
                    k3_c = k3[5]
                    body = abs(k2_c - k2_o)
                    lower_wick = min(k2_o, k2_c) - k2_l
                    upper_wick = k2_h - max(k2_o, k2_c)

                    # 多头：K2 触及 S1/S2 + 拒绝下跌 + K3 破 K2 高
                    if not found_buy:
                        for name, L in support_levels:
                            if k2_l <= L <= k2_h:
                                is_rej = (lower_wick >= body) or (k2_c > k2_o)
                                if is_rej and k3_c > k2_h:
                                    y = k2_l * 0.999
                                    buy_pts.append((
                                        k3[1], y,
                                        f"多头(K2拒绝@{name}={L:,.2f} K3破K2高={k2_h:,.2f})"
                                    ))
                                    found_buy = True
                                    break

                    # 空头：K2 触及 R1/R2 + 拒绝上涨 + K3 破 K2 低
                    if not found_sell:
                        for name, L in resist_levels:
                            if k2_l <= L <= k2_h:
                                is_rej = (upper_wick >= body) or (k2_c < k2_o)
                                if is_rej and k3_c < k2_l:
                                    y = k2_h * 1.001
                                    sell_pts.append((
                                        k3[1], y,
                                        f"空头(K2拒绝@{name}={L:,.2f} K3破K2低={k2_l:,.2f})"
                                    ))
                                    found_sell = True
                                    break

        return shapes, annotations, buy_pts, sell_pts

    def _on_chart_data(self, data):
        inst = data["inst"]; bar = data["bar"]
        # 过滤过期结果：切换币对/周期后，旧线程返回的数据不再绘制
        if self._cur_inst is not None and (inst != self._cur_inst or bar != self._cur_bar):
            return
        ts = data["ts"]; opens = data["o"]; highs = data["h"]
        lows = data["l"]; closes = data["c"]; vols = data["v"]
        show_vol = data["show_vol"]; show_ma = data["show_ma"]; ma_txt = data["ma_txt"]
        show_magic = data.get("show_magic", False)
        show_macd = data.get("show_macd", False)
        show_rsi  = data.get("show_rsi",  False)
        show_kdj  = data.get("show_kdj",  False)

        lp = closes[-1]
        first_open = opens[0] if opens else lp
        cp = (lp - first_open) / first_open * 100 if first_open else 0.0

        # 动态子图行分配：Price → Vol → MACD → RSI → KDJ
        rows_order = ["price"]
        if show_vol:  rows_order.append("vol")
        if show_macd: rows_order.append("macd")
        if show_rsi:  rows_order.append("rsi")
        if show_kdj:  rows_order.append("kdj")
        row_idx = {name: i + 1 for i, name in enumerate(rows_order)}
        n_rows = len(rows_order)

        # 高度分配：主图占大头，其它每行 0.18
        if n_rows == 1:
            row_heights = [1.0]
        else:
            extra_h = 0.18
            price_h = max(0.35, 1.0 - extra_h * (n_rows - 1))
            row_heights = [price_h] + [(1.0 - price_h) / (n_rows - 1)] * (n_rows - 1)

        if n_rows > 1:
            fig = make_subplots(rows=n_rows, cols=1, shared_xaxes=True,
                                row_heights=row_heights, vertical_spacing=0.03)
        else:
            fig = make_subplots(rows=1, cols=1)

        # 蜡烛图
        fig.add_trace(go.Candlestick(
            x=ts, open=opens, high=highs, low=lows, close=closes,
            increasing_line_color=self._up_color(),   increasing_fillcolor=self._up_color(),
            decreasing_line_color=self._down_color(), decreasing_fillcolor=self._down_color(),
            name="K线",
            hoverlabel=dict(bgcolor=C_BG2, font_color=C_TEXT),
        ), row=1, col=1)

        # 均线
        if show_ma:
            periods = parse_ma_periods(ma_txt)
            for i, p in enumerate(periods):
                ma = calc_ma(closes, p)
                # 前 p-1 项设置为 None 以避免绘制 0
                y = [v if i2 >= p - 1 else None for i2, v in enumerate(ma)]
                fig.add_trace(go.Scatter(
                    x=ts, y=y, mode="lines",
                    line=dict(color=MA_COLORS[i % len(MA_COLORS)], width=1.2),
                    name=f"MA{p}",
                ), row=1, col=1)

        # 成交量
        if show_vol:
            colors = [self._up_color() if c >= o else self._down_color() for c, o in zip(closes, opens)]
            fig.add_trace(go.Bar(
                x=ts, y=vols, marker_color=colors, name="成交量",
                hoverlabel=dict(bgcolor=C_BG2, font_color=C_TEXT),
            ), row=row_idx["vol"], col=1)

        # MACD 子图
        if show_macd:
            dif, dea, hist = calc_macd(closes)
            n = len(closes)
            valid_start = 26 - 1
            dif_y  = [dif[i]  if i >= valid_start else None for i in range(n)]
            dea_y  = [dea[i]  if i >= valid_start else None for i in range(n)]
            hist_y = [hist[i] if i >= valid_start else 0    for i in range(n)]
            hist_colors = [self._up_color() if v >= 0 else self._down_color() for v in hist_y]
            r = row_idx["macd"]
            fig.add_trace(go.Bar(
                x=ts, y=hist_y, marker_color=hist_colors, name="MACD Hist",
                hoverlabel=dict(bgcolor=C_BG2, font_color=C_TEXT),
            ), row=r, col=1)
            fig.add_trace(go.Scatter(
                x=ts, y=dif_y, mode="lines", name="DIF",
                line=dict(color="#f0b90b", width=1.2),
            ), row=r, col=1)
            fig.add_trace(go.Scatter(
                x=ts, y=dea_y, mode="lines", name="DEA",
                line=dict(color="#7c4dff", width=1.2),
            ), row=r, col=1)
            fig.update_yaxes(title_text="MACD", row=r, col=1,
                             gridcolor=C_BORDER, zerolinecolor=C_BORDER)

        # RSI 子图
        if show_rsi:
            rsi = calc_rsi(closes, 14)
            n = len(closes)
            rsi_y = [rsi[i] if i >= 14 else None for i in range(n)]
            r = row_idx["rsi"]
            fig.add_trace(go.Scatter(
                x=ts, y=rsi_y, mode="lines", name="RSI(14)",
                line=dict(color="#00bcd4", width=1.3),
            ), row=r, col=1)
            # 30 / 70 参考线
            if ts:
                for lvl, col in ((70, self._down_color()), (30, self._up_color()), (50, C_BORDER)):
                    fig.add_trace(go.Scatter(
                        x=[ts[0], ts[-1]], y=[lvl, lvl], mode="lines",
                        line=dict(color=col, width=0.8, dash="dot"),
                        showlegend=False, hoverinfo="skip",
                    ), row=r, col=1)
            fig.update_yaxes(title_text="RSI", row=r, col=1, range=[0, 100],
                             gridcolor=C_BORDER, zerolinecolor=C_BORDER)

        # KDJ 子图
        if show_kdj:
            K, D, J = calc_kdj(highs, lows, closes)
            n = len(closes)
            valid = 8  # k_period - 1
            k_y = [K[i] if i >= valid else None for i in range(n)]
            d_y = [D[i] if i >= valid else None for i in range(n)]
            j_y = [J[i] if i >= valid else None for i in range(n)]
            r = row_idx["kdj"]
            fig.add_trace(go.Scatter(
                x=ts, y=k_y, mode="lines", name="K",
                line=dict(color="#f0b90b", width=1.2),
            ), row=r, col=1)
            fig.add_trace(go.Scatter(
                x=ts, y=d_y, mode="lines", name="D",
                line=dict(color="#00bcd4", width=1.2),
            ), row=r, col=1)
            fig.add_trace(go.Scatter(
                x=ts, y=j_y, mode="lines", name="J",
                line=dict(color="#ff4d6d", width=1.2),
            ), row=r, col=1)
            fig.update_yaxes(title_text="KDJ", row=r, col=1,
                             gridcolor=C_BORDER, zerolinecolor=C_BORDER)

        # 隐秘枢轴（Rumers Magic Lines）：R1/R2/S1/S2 + 3K 线入场信号
        magic_shapes = []
        magic_annotations = []
        if show_magic and bar not in ("1W", "1M"):
            magic_mode = data.get("magic_mode", "strict")
            magic_lookback = int(data.get("magic_lookback", 1))
            magic_shapes, magic_annotations, buy_pts, sell_pts = self._compute_magic_lines(
                ts, opens, highs, lows, closes, bar, mode=magic_mode, lookback=magic_lookback)
            mode_label = "K1/K2/K3" if magic_mode == "strict" else "简单"
            if buy_pts:
                bx = [p[0] for p in buy_pts]
                by = [p[1] for p in buy_pts]
                bt = [p[2] for p in buy_pts]
                fig.add_trace(go.Scatter(
                    x=bx, y=by, mode="markers",
                    marker=dict(symbol="triangle-up", size=13,
                                color=self._up_color(),
                                line=dict(color=C_WHITE, width=1)),
                    name=f"3K多头({mode_label})",
                    text=bt,
                    hovertemplate="%{text}<br>%{x}<br>价 %{y:,.2f}<extra></extra>",
                ), row=1, col=1)
            if sell_pts:
                sx = [p[0] for p in sell_pts]
                sy = [p[1] for p in sell_pts]
                st = [p[2] for p in sell_pts]
                fig.add_trace(go.Scatter(
                    x=sx, y=sy, mode="markers",
                    marker=dict(symbol="triangle-down", size=13,
                                color=self._down_color(),
                                line=dict(color=C_WHITE, width=1)),
                    name=f"3K空头({mode_label})",
                    text=st,
                    hovertemplate="%{text}<br>%{x}<br>价 %{y:,.2f}<extra></extra>",
                ), row=1, col=1)

        title_color = self._up_color() if cp >= 0 else self._down_color()
        fig.update_layout(
            template="plotly_dark",
            paper_bgcolor=C_BG2, plot_bgcolor=C_BG2,
            title=dict(
                text=f"<span style='color:{C_TEXT}'>{inst}  {bar}  最新 </span>"
                     f"<span style='color:{title_color}'>{lp:,.2f}</span>",
                x=0.5, xanchor="center", font=dict(size=13),
            ),
            xaxis_rangeslider_visible=False,
            hovermode="x unified",
            margin=dict(l=60, r=60, t=50, b=30),
            legend=dict(orientation="h", yanchor="bottom", y=1.02, xanchor="right", x=1,
                        bgcolor="rgba(0,0,0,0)"),
            font=dict(family="Microsoft YaHei", color=C_TEXT, size=11),
            dragmode="pan",
            shapes=magic_shapes,
            annotations=magic_annotations,
        )
        fig.update_yaxes(side="right", gridcolor=C_BORDER, zerolinecolor=C_BORDER)
        fig.update_xaxes(gridcolor=C_BORDER, zerolinecolor=C_BORDER)

        config = {
            "displayModeBar": True, "displaylogo": False,
            "modeBarButtonsToRemove": ["lasso2d", "select2d"],
            "scrollZoom": True, "responsive": True,
        }
        # 用 Plotly.react 增量更新（快，避免 setHtml 引发的 CDN 重新加载与画面闪烁）
        fig_json = pio.to_json(fig)
        if self._chart_shell_loaded:
            self._inject_chart(fig_json, config)
        else:
            self._pending_fig = (fig_json, config)
        self._log(f"K线已刷新: {inst} {bar}  {len(closes)}根  最新 {lp:,.2f}  涨跌 {cp:+.2f}%", "ok")

    # ---------- 多周期评级面板 ----------
    VERDICT_INDICATORS = ["MA", "MACD", "RSI", "KDJ"]

    def _pick_verdict_tfs(self, bar):
        """根据当前选择周期挑选评级用的多周期梯队（选中项 + 向上最多 3 级）"""
        try:
            i = BAR_OPTIONS.index(bar)
        except ValueError:
            i = BAR_OPTIONS.index("1H")
        return BAR_OPTIONS[i: i + 4]

    def _build_verdict_panel(self):
        w = QWidget()
        w.setObjectName("VerdictPanel")
        w.setStyleSheet(
            f"QWidget#VerdictPanel {{ background: {C_BG2}; border-left: 1px solid {C_BORDER}; }}"
        )
        lay = QVBoxLayout(w); lay.setContentsMargins(8, 8, 8, 8); lay.setSpacing(6)

        title = QLabel("多周期评级")
        title.setStyleSheet(f"color: {C_TEXT}; font-weight: 600; font-size: 12px;")
        lay.addWidget(title)

        self.verdict_pair_label = QLabel("—")
        self.verdict_pair_label.setStyleSheet(f"color: {C_TEXT_S}; font-size: 11px;")
        lay.addWidget(self.verdict_pair_label)

        # 评级矩阵表（列数动态，随下拉周期变化）
        self._current_verdict_tfs = self._pick_verdict_tfs(self.k_bar.currentText().strip() or "1H")
        self.verdict_table = QTableWidget(len(self.VERDICT_INDICATORS), len(self._current_verdict_tfs))
        self.verdict_table.setHorizontalHeaderLabels(self._current_verdict_tfs)
        self.verdict_table.setVerticalHeaderLabels(self.VERDICT_INDICATORS)
        self.verdict_table.horizontalHeader().setSectionResizeMode(QHeaderView.ResizeMode.Stretch)
        self.verdict_table.verticalHeader().setSectionResizeMode(QHeaderView.ResizeMode.Stretch)
        self.verdict_table.setSelectionMode(QTableWidget.SelectionMode.NoSelection)
        self.verdict_table.setEditTriggers(QTableWidget.EditTrigger.NoEditTriggers)
        self.verdict_table.setFocusPolicy(Qt.FocusPolicy.NoFocus)
        self.verdict_table.setShowGrid(False)
        self.verdict_table.setFixedHeight(160)
        for r in range(len(self.VERDICT_INDICATORS)):
            for c in range(len(self._current_verdict_tfs)):
                it = QTableWidgetItem("—")
                it.setTextAlignment(Qt.AlignmentFlag.AlignCenter)
                self.verdict_table.setItem(r, c, it)
        lay.addWidget(self.verdict_table)

        # 汇总
        self.verdict_summary = QLabel("看多 0 · 看空 0 · 中性 0")
        self.verdict_summary.setStyleSheet(f"color: {C_TEXT}; font-size: 11px;")
        lay.addWidget(self.verdict_summary)

        self.verdict_prob_bar = QProgressBar()
        self.verdict_prob_bar.setRange(0, 100)
        self.verdict_prob_bar.setValue(50)
        self.verdict_prob_bar.setTextVisible(True)
        self.verdict_prob_bar.setFormat("看多概率 %p%")
        self.verdict_prob_bar.setStyleSheet(
            f"QProgressBar {{ border: 1px solid {C_BORDER}; border-radius: 3px; "
            f"background: {C_BG}; color: {C_TEXT}; text-align: center; font-size: 11px; height: 18px; }} "
            f"QProgressBar::chunk {{ background: {self._up_color()}; }}"
        )
        lay.addWidget(self.verdict_prob_bar)

        btn = QPushButton("刷新评级"); btn.setObjectName("Ghost")
        btn.clicked.connect(self._refresh_verdict)
        lay.addWidget(btn)

        lay.addStretch(1)
        return w

    def _refresh_verdict(self):
        inst = self.k_pair.currentText().strip().upper() or "BTC-USDT"
        bar  = self.k_bar.currentText().strip() or "1H"
        tfs = self._pick_verdict_tfs(bar)
        # 如列数变化，重建表头 + 单元格
        if tfs != self._current_verdict_tfs:
            self._current_verdict_tfs = tfs
            self.verdict_table.setColumnCount(len(tfs))
            self.verdict_table.setHorizontalHeaderLabels(tfs)
            for r in range(len(self.VERDICT_INDICATORS)):
                for c in range(len(tfs)):
                    if self.verdict_table.item(r, c) is None:
                        it = QTableWidgetItem("—")
                        it.setTextAlignment(Qt.AlignmentFlag.AlignCenter)
                        self.verdict_table.setItem(r, c, it)
        self.verdict_pair_label.setText(f"{inst} · 计算中…")
        self._run_async(self._fetch_verdict_data, inst, tfs)

    def _fetch_verdict_data(self, inst, tfs):
        api = self._get_market_api()
        results = {}  # tf -> {ind: verdict}
        for tf in tfs:
            try:
                res = api.get_candlesticks(instId=inst, bar=tf, limit="120")
                if not (isinstance(res, dict) and res.get("code") in ("0", 0)):
                    results[tf] = None
                    continue
                raw = list(res.get("data") or [])
                raw.reverse()
                highs, lows, closes = [], [], []
                for item in raw:
                    try:
                        highs.append(float(item[2]))
                        lows.append(float(item[3]))
                        closes.append(float(item[4]))
                    except Exception:
                        continue
                if len(closes) < 35:
                    results[tf] = None
                    continue
                results[tf] = {
                    "MA":   verdict_ma(closes),
                    "MACD": verdict_macd(closes),
                    "RSI":  verdict_rsi(closes),
                    "KDJ":  verdict_kdj(highs, lows, closes),
                }
            except Exception as e:
                self.log_signal.emit(f"评级 {tf} 计算失败: {e}", "err")
                results[tf] = None
        self.verdict_signal.emit({"inst": inst, "tfs": tfs, "results": results})

    def _on_verdict_data(self, data):
        inst = data["inst"]
        # 切换币对后旧线程回调不再更新面板
        if self._cur_inst is not None and inst != self._cur_inst:
            return
        tfs = data.get("tfs", self._current_verdict_tfs)
        results = data["results"]
        # 若期间列数已改变（用户又切了周期），忽略本次
        if tfs != self._current_verdict_tfs:
            return
        bull = bear = neutral = 0
        for c, tf in enumerate(tfs):
            tfres = results.get(tf)
            for r, ind in enumerate(self.VERDICT_INDICATORS):
                item = self.verdict_table.item(r, c)
                if item is None:
                    item = QTableWidgetItem("—")
                    item.setTextAlignment(Qt.AlignmentFlag.AlignCenter)
                    self.verdict_table.setItem(r, c, item)
                if tfres is None:
                    item.setText("—")
                    item.setForeground(Qt.GlobalColor.gray)
                    continue
                v = tfres.get(ind, 0)
                if v > 0:
                    item.setText("看多"); bull += 1
                    item.setForeground(Qt.GlobalColor.green)
                elif v < 0:
                    item.setText("看空"); bear += 1
                    item.setForeground(Qt.GlobalColor.red)
                else:
                    item.setText("中性"); neutral += 1
                    item.setForeground(Qt.GlobalColor.lightGray)
        total = bull + bear + neutral
        prob = int(round((bull + neutral * 0.5) / total * 100)) if total else 50
        self.verdict_summary.setText(f"看多 {bull} · 看空 {bear} · 中性 {neutral}")
        self.verdict_prob_bar.setValue(prob)
        color = self._up_color() if prob >= 50 else self._down_color()
        self.verdict_prob_bar.setStyleSheet(
            f"QProgressBar {{ border: 1px solid {C_BORDER}; border-radius: 3px; "
            f"background: {C_BG}; color: {C_TEXT}; text-align: center; font-size: 11px; height: 18px; }} "
            f"QProgressBar::chunk {{ background: {color}; }}"
        )
        self.verdict_pair_label.setText(f"{inst} · 已更新")

    # ---------- 全屏 ----------
    def _toggle_fullscreen_chart(self):
        self._is_fullscreen_chart = not self._is_fullscreen_chart
        hidden = self._is_fullscreen_chart
        self.header.setVisible(not hidden)
        self.config_bar.setVisible(not hidden)
        self.output_panel.setVisible(not hidden)
        self.verdict_panel.setVisible(not hidden)
        tb = self.tabs.tabBar()
        tb.setVisible(not hidden)
        if hidden:
            self.btn_full.setText("⤢ 退出全屏")
        else:
            self.btn_full.setText("⛶ 全屏")
        self.btn_full.style().unpolish(self.btn_full); self.btn_full.style().polish(self.btn_full)

    # ==================== 公开行情 ====================
    def _m_ticker(self):
        inst = self.m_pair.currentText().strip().upper()
        def job():
            r = self._get_market_api().get_ticker(instId=inst)
            self.result_signal.emit(f"{inst} 行情", r)
            self.log_signal.emit("行情获取完成", "ok")
        self._run_async(job)

    def _m_orderbook(self):
        inst = self.m_pair.currentText().strip().upper()
        def job():
            r = self._get_market_api().get_orderbook(instId=inst, sz="10")
            self.result_signal.emit(f"{inst} 订单簿", r)
            self.log_signal.emit("订单簿获取完成", "ok")
        self._run_async(job)

    def _m_candles(self):
        inst = self.m_pair.currentText().strip().upper()
        def job():
            r = self._get_market_api().get_candlesticks(instId=inst, bar="1H", limit="20")
            self.result_signal.emit(f"{inst} K线(1H×20)", r)
            self.log_signal.emit("K线数据获取完成", "ok")
        self._run_async(job)

    def _m_trades(self):
        inst = self.m_pair.currentText().strip().upper()
        def job():
            r = self._get_market_api().get_trades(instId=inst, limit="10")
            self.result_signal.emit(f"{inst} 最近成交", r)
            self.log_signal.emit("成交记录获取完成", "ok")
        self._run_async(job)

    def _m_volume(self):
        def job():
            r = self._get_market_api().get_volume()
            self.result_signal.emit("平台 24h 成交量", r)
            self.log_signal.emit("24h成交量获取完成", "ok")
        self._run_async(job)

    def _m_all_tickers(self):
        def job():
            r = self._get_market_api().get_tickers(instType="SPOT")
            self.result_signal.emit("所有现货 Ticker", r)
            self.log_signal.emit("Ticker 列表获取完成", "ok")
        self._run_async(job)

    # ==================== 账户 ====================
    def _a_balance(self):
        if not self._check_credentials(): return
        def job():
            r = self._get_account_api().get_account_balance()
            self.result_signal.emit("账户余额", r)
            self.log_signal.emit("账户余额获取完成", "ok")
            self.conn_signal.emit(True)
        self._run_async(job)

    def _a_positions(self):
        if not self._check_credentials(): return
        def job():
            r = self._get_account_api().get_positions()
            self.result_signal.emit("持仓信息", r)
            self.log_signal.emit("持仓信息获取完成", "ok")
        self._run_async(job)

    def _a_config(self):
        if not self._check_credentials(): return
        def job():
            r = self._get_account_api().get_account_config()
            self.result_signal.emit("账户配置", r)
            self.log_signal.emit("账户配置获取完成", "ok")
        self._run_async(job)

    def _a_bills(self):
        if not self._check_credentials(): return
        def job():
            r = self._get_account_api().get_account_bills()
            self.result_signal.emit("账户流水", r)
            self.log_signal.emit("账户流水获取完成", "ok")
        self._run_async(job)

    def _a_max_size(self):
        if not self._check_credentials(): return
        inst = self.a_pair.currentText().strip().upper()
        def job():
            r = self._get_account_api().get_max_order_size(instId=inst, tdMode="cash")
            self.result_signal.emit(f"{inst} 最大下单量", r)
            self.log_signal.emit("最大下单量获取完成", "ok")
        self._run_async(job)

    def _a_fee_rates(self):
        if not self._check_credentials(): return
        def job():
            r = self._get_account_api().get_fee_rates(instType="SPOT")
            self.result_signal.emit("SPOT 手续费率", r)
            self.log_signal.emit("手续费率获取完成", "ok")
        self._run_async(job)

    # ==================== 资金 ====================
    def _f_balances(self):
        if not self._check_credentials(): return
        def job():
            r = self._get_funding_api().get_balances()
            self.result_signal.emit("资金账户余额", r)
            self.log_signal.emit("资金余额获取完成", "ok")
        self._run_async(job)

    def _f_deposit_addr(self):
        if not self._check_credentials(): return
        def job():
            r = self._get_funding_api().get_deposit_address(ccy="BTC")
            self.result_signal.emit("BTC 充值地址", r)
            self.log_signal.emit("充值地址获取完成", "ok")
        self._run_async(job)

    def _f_valuation(self):
        if not self._check_credentials(): return
        def job():
            r = self._get_funding_api().get_asset_valuation()
            self.result_signal.emit("资产估值", r)
            self.log_signal.emit("资产估值获取完成", "ok")
        self._run_async(job)

    def _f_deposit_history(self):
        if not self._check_credentials(): return
        def job():
            r = self._get_funding_api().get_deposit_history()
            self.result_signal.emit("充值记录", r)
            self.log_signal.emit("充值记录获取完成", "ok")
        self._run_async(job)

    def _f_withdrawal_history(self):
        if not self._check_credentials(): return
        def job():
            r = self._get_funding_api().get_withdrawal_history()
            self.result_signal.emit("提币记录", r)
            self.log_signal.emit("提币记录获取完成", "ok")
        self._run_async(job)

    def _f_bills(self):
        if not self._check_credentials(): return
        def job():
            r = self._get_funding_api().get_bills()
            self.result_signal.emit("资金流水", r)
            self.log_signal.emit("资金流水获取完成", "ok")
        self._run_async(job)

    # ==================== 交易 ====================
    def _t_place_order(self):
        if not self._check_credentials(): return
        inst = self.t_pair.currentText().strip().upper()
        side = self.t_side.currentText()
        ord_type = self.t_type.currentText()
        sz = self.t_sz.text().strip()
        px = self.t_px.text().strip()
        if not sz:
            QMessageBox.warning(self, "提示", "请输入下单数量"); return
        if ord_type == "limit" and not px:
            QMessageBox.warning(self, "提示", "限价单请输入价格"); return

        summary = (f"交易对: {inst}\n方向: {side}\n类型: {ord_type}\n"
                   f"数量: {sz}\n价格: {px or '市价'}")
        if self._get_flag() == "0":  # 实盘
            box = QMessageBox(self)
            box.setIcon(QMessageBox.Icon.Warning)
            box.setWindowTitle("实盘下单二次确认")
            box.setText("即将向实盘提交订单，请确认参数：\n\n" + summary)
            box.setStandardButtons(QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No)
            box.setDefaultButton(QMessageBox.StandardButton.No)
            if box.exec() != QMessageBox.StandardButton.Yes:
                self._log("已取消实盘下单", "info"); return

        params = dict(instId=inst, tdMode="cash", side=side, ordType=ord_type, sz=sz)
        if ord_type == "limit":
            params["px"] = px

        def job():
            r = self._get_trade_api().place_order(**params)
            self.result_signal.emit(f"下单结果 {inst} {side} {ord_type}", r)
            code = r.get("code") if isinstance(r, dict) else None
            if code in ("0", 0):
                self.log_signal.emit("下单已提交", "ok")
            else:
                self.log_signal.emit(f"下单失败: {r.get('msg') if isinstance(r, dict) else r}", "err")
        self._run_async(job)

    def _t_open_orders(self):
        if not self._check_credentials(): return
        def job():
            r = self._get_trade_api().get_order_list()
            self.result_signal.emit("当前挂单", r)
            self.log_signal.emit("当前挂单获取完成", "ok")
        self._run_async(job)

    def _t_orders_history(self):
        if not self._check_credentials(): return
        def job():
            r = self._get_trade_api().get_orders_history(instType="SPOT")
            self.result_signal.emit("历史订单(SPOT)", r)
            self.log_signal.emit("历史订单获取完成", "ok")
        self._run_async(job)

    def _t_fills(self):
        if not self._check_credentials(): return
        def job():
            r = self._get_trade_api().get_fills()
            self.result_signal.emit("成交记录", r)
            self.log_signal.emit("成交记录获取完成", "ok")
        self._run_async(job)

    # ==================== 配置管理 ====================
    def _save_config(self):
        k, s, p, f = self._get_credentials()
        cfg = {"api_key": k, "api_secret": s, "passphrase": p, "flag": f,
               "proxy": self.in_proxy.text().strip(),
               "magic_mode": "strict" if self.k_magic_mode.currentIndex() == 0 else "classic",
               "magic_lookback": int(self.sp_magic_lookback.value()),
               "show_macd": self.cb_macd.isChecked(),
               "show_rsi":  self.cb_rsi.isChecked(),
               "show_kdj":  self.cb_kdj.isChecked()}
        try:
            with open(CONFIG_FILE, "w", encoding="utf-8") as fp:
                json.dump(cfg, fp, indent=2, ensure_ascii=False)
            self._log(f"配置已保存: {CONFIG_FILE.name}", "ok")
        except Exception as e:
            self._log(f"配置保存失败: {e}", "err")

    def _load_config(self):
        if not CONFIG_FILE.exists():
            self._log("配置文件不存在", "err"); return
        try:
            with open(CONFIG_FILE, "r", encoding="utf-8") as fp:
                c = json.load(fp)
            self.in_key.setText(c.get("api_key", ""))
            self.in_sec.setText(c.get("api_secret", ""))
            self.in_pass.setText(c.get("passphrase", ""))
            self.in_proxy.setText(c.get("proxy", ""))
            flag = c.get("flag", "1")
            self.rb_sim.setChecked(flag == "1")
            self.rb_real.setChecked(flag == "0")
            # 隐秘枢轴配置
            mm = c.get("magic_mode", "strict")
            self.k_magic_mode.setCurrentIndex(0 if mm == "strict" else 1)
            try:
                self.sp_magic_lookback.setValue(int(c.get("magic_lookback", 1)))
            except Exception:
                self.sp_magic_lookback.setValue(1)
            self.cb_macd.setChecked(bool(c.get("show_macd", False)))
            self.cb_rsi.setChecked(bool(c.get("show_rsi", False)))
            self.cb_kdj.setChecked(bool(c.get("show_kdj", False)))
            # 应用代理到 WS
            if hasattr(self, "_ws") and self._ws is not None:
                self._ws.set_proxy(self._get_proxy())
            self._log(f"已加载配置: {CONFIG_FILE.name}", "ok")
        except Exception as e:
            self._log(f"配置加载失败: {e}", "err")

    def _auto_load_config(self):
        if CONFIG_FILE.exists():
            try:
                with open(CONFIG_FILE, "r", encoding="utf-8") as fp:
                    c = json.load(fp)
                self.in_key.setText(c.get("api_key", ""))
                self.in_sec.setText(c.get("api_secret", ""))
                self.in_pass.setText(c.get("passphrase", ""))
                self.in_proxy.setText(c.get("proxy", ""))
                flag = c.get("flag", "1")
                self.rb_sim.setChecked(flag == "1")
                self.rb_real.setChecked(flag == "0")
                # 隐秘枢轴配置
                mm = c.get("magic_mode", "strict")
                self.k_magic_mode.setCurrentIndex(0 if mm == "strict" else 1)
                try:
                    self.sp_magic_lookback.setValue(int(c.get("magic_lookback", 1)))
                except Exception:
                    self.sp_magic_lookback.setValue(1)
                self.cb_macd.setChecked(bool(c.get("show_macd", False)))
                self.cb_rsi.setChecked(bool(c.get("show_rsi", False)))
                self.cb_kdj.setChecked(bool(c.get("show_kdj", False)))
            except Exception:
                pass

    def _write_env(self):
        k, s, p, f = self._get_credentials()
        content = (f"OKX_API_KEY={k}\n"
                   f"OKX_API_SECRET={s}\n"
                   f"OKX_PASSPHRASE={p}\n"
                   f"OKX_FLAG={f}\n")
        try:
            with open(ENV_FILE, "w", encoding="utf-8") as fp:
                fp.write(content)
            self._log(f".env 已写入: {ENV_FILE.name}", "ok")
        except Exception as e:
            self._log(f".env 写入失败: {e}", "err")

    def _test_connection(self):
        def job():
            self.log_signal.emit("测试连接中...", "info")
            api = self._get_market_api()
            r = api.get_ticker(instId="BTC-USDT")
            if isinstance(r, dict) and r.get("code") in ("0", 0):
                self.conn_signal.emit(True)
                self.log_signal.emit("公开接口连通", "ok")
                # 顺便测私有接口（如果配置了）
                k, s, p, _ = self._get_credentials()
                if k and s and p and k != "-1":
                    try:
                        r2 = self._get_account_api().get_account_config()
                        if isinstance(r2, dict) and r2.get("code") in ("0", 0):
                            self.log_signal.emit("私有接口连通(凭证有效)", "ok")
                        else:
                            self.log_signal.emit(f"私有接口异常: {r2}", "err")
                    except Exception as e:
                        self.log_signal.emit(f"私有接口异常: {e}", "err")
            else:
                self.conn_signal.emit(False)
                self.log_signal.emit(f"公开接口失败: {r}", "err")
        self._run_async(job)


# ==================== 入口 ====================
def main():
    app = QApplication(sys.argv)
    app.setStyleSheet(build_qss(C_UP, C_UP_BR, C_DOWN, C_DOWN_BR))
    app.setFont(QFont("Microsoft YaHei", 9))
    window = MainWindow()
    window.show()
    sys.exit(app.exec())


if __name__ == "__main__":
    main()
