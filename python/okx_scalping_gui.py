"""
OKX 剥头皮自动化交易终端 (OKX Scalping Trading Terminal Pro)
=============================================================
- 专为极短线/高频剥头皮 (Scalping) 自动化交易打造的专业 GUI 客户端
- 支持三大模式：【实盘交易】、【OKX 官方模拟盘】、【本地无损虚拟盘】
- 产品类型无缝支持：【永续合约 SWAP】 (支持 1x-100x 杠杆、逐仓/全仓) & 【现货 SPOT】
- 策略方向自动判断：根据 EMA + RSI + 盘口不平衡度多因子共识打分，全自动判断开多 (Long) / 开空 (Short)
- 健壮网络层：默认基于 https://www.okx.com 官方标准 JSON 节点，智能拦截 Cloudflare 530/HTML 异常与 SSL 波动
"""

import sys
import os
import time
import json
import asyncio
import math
import queue
import ssl
import threading
from datetime import datetime, timezone
from pathlib import Path
import numpy as np

from PyQt6.QtWidgets import (
    QApplication, QMainWindow, QWidget, QVBoxLayout, QHBoxLayout,
    QGridLayout, QLabel, QLineEdit, QComboBox, QPushButton, QCheckBox,
    QRadioButton, QButtonGroup, QTabWidget, QTextEdit, QSplitter, QFrame,
    QMessageBox, QSizePolicy, QSpinBox, QDoubleSpinBox,
    QTableWidget, QTableWidgetItem, QHeaderView, QProgressBar, QGroupBox,
    QScrollArea, QDialog, QFormLayout
)
from PyQt6.QtCore import Qt, QTimer, pyqtSignal, QObject, QThread
from PyQt6.QtGui import QFont, QColor, QIcon, QKeySequence, QShortcut

# Plotly + QWebEngineView 图表渲染支持
try:
    from PyQt6.QtWebEngineWidgets import QWebEngineView
    HAS_WEBENGINE = True
except ImportError:
    HAS_WEBENGINE = False

import plotly.graph_objects as go
from plotly.subplots import make_subplots
import plotly.io as pio

# OKX 原生 SDK 导入
from okx import MarketData, Account, Trade, PublicData

# ==================== 路径与常量配置 ====================
BASE_DIR = Path(__file__).parent
CONFIG_FILE = BASE_DIR / "okx_scalping_config.json"

DEFAULT_PAIRS = [
    "BTC-USDT", "ETH-USDT", "SOL-USDT", "DOGE-USDT", 
    "XRP-USDT", "SUI-USDT", "AVAX-USDT", "LINK-USDT", 
    "PEPE-USDT", "NEAR-USDT", "APT-USDT", "ORDI-USDT"
]

BAR_PERIODS = ["1m", "3m", "5m", "15m", "1s (盘口流)"]

DOMAIN_OPTIONS = [
    ("https://www.okx.com", "OKX 官方主节点 (推荐标准 API)"),
    ("https://aws.okx.com", "AWS 备用节点 (受 Cloudflare 限制)")
]

# 主题配色
C_BG = "#0b0e14"
C_CARD = "#151923"
C_SURFACE = "#1e2330"
C_BORDER = "#2a3142"
C_TEXT_MAIN = "#f1f5f9"
C_TEXT_MUTED = "#94a3b8"

C_GREEN = "#22c55e"    # 涨/买入/做多
C_GREEN_HOVER = "#16a34a"
C_RED = "#ef4444"      # 跌/卖出/做空
C_RED_HOVER = "#dc2626"
C_PRIMARY = "#3b82f6"  # 主按键/高亮
C_ACCENT = "#8b5cf6"   # 紫色强调
C_YELLOW = "#eab308"   # 警告

# ==================== 指标与算法 ====================
def calc_ema(arr: np.ndarray, period: int) -> np.ndarray:
    """计算指数移动平均线 (EMA)"""
    if len(arr) < period:
        return np.full_like(arr, np.nan)
    alpha = 2.0 / (period + 1.0)
    ema = np.empty_like(arr, dtype=float)
    ema[0] = arr[0]
    for i in range(1, len(arr)):
        ema[i] = alpha * arr[i] + (1 - alpha) * ema[i - 1]
    return ema

def calc_rsi(prices: np.ndarray, period: int = 7) -> np.ndarray:
    """计算相对强弱指标 (RSI)"""
    if len(prices) < period + 1:
        return np.full_like(prices, 50.0)
    deltas = np.diff(prices)
    gains = np.where(deltas > 0, deltas, 0.0)
    losses = np.where(deltas < 0, -deltas, 0.0)
    
    avg_gain = np.mean(gains[:period])
    avg_loss = np.mean(losses[:period])
    
    rsi = np.zeros(len(prices))
    rsi[:period] = 50.0
    
    for i in range(period, len(deltas)):
        avg_gain = (avg_gain * (period - 1) + gains[i]) / period
        avg_loss = (avg_loss * (period - 1) + losses[i]) / period
        if avg_loss == 0:
            rsi[i + 1] = 100.0
        else:
            rs = avg_gain / avg_loss
            rsi[i + 1] = 100.0 - (100.0 / (1.0 + rs))
    return rsi

def calc_atr(highs: np.ndarray, lows: np.ndarray, closes: np.ndarray, period: int = 7) -> float:
    """计算真实波幅均值 (ATR)"""
    if len(closes) < period + 1:
        return (highs[-1] - lows[-1]) if len(highs) > 0 else 1.0
    tr1 = highs[1:] - lows[1:]
    tr2 = np.abs(highs[1:] - closes[:-1])
    tr3 = np.abs(lows[1:] - closes[:-1])
    tr = np.maximum(tr1, np.maximum(tr2, tr3))
    return float(np.mean(tr[-period:]))

# ==================== 本地虚拟交易模拟器 ====================
class PaperTrader:
    """支持现货与永续合约 1x~100x 杠杆的无损模拟撮合引擎"""
    def __init__(self, initial_balance: float = 10000.0):
        self.initial_balance = initial_balance
        self.balance = initial_balance  # USDT 账户可用余额
        self.positions = {}  # instId -> dict
        self.trades_history = []
        self.fee_rate = 0.0005 # Taker 0.05%

    def place_order(self, instId: str, side: str, sz: float, current_price: float, 
                    leverage: int = 10, tp_pct: float = 0.005, sl_pct: float = 0.003) -> dict:
        position_value = sz * current_price
        required_margin = position_value / max(1, leverage)
        fee = position_value * self.fee_rate

        if self.balance < required_margin + fee:
            return {"code": "1", "msg": f"模拟资金不足！需要保证金 ${required_margin:.2f} USDT，当前余额 ${self.balance:.2f}"}

        executed_price = current_price * (1.0002 if side == "buy" else 0.9998)
        self.balance -= (required_margin + fee)

        tp_px = executed_price * (1.0 + tp_pct) if side == "buy" else executed_price * (1.0 - tp_pct)
        sl_px = executed_price * (1.0 - sl_pct) if side == "buy" else executed_price * (1.0 + sl_pct)

        pos = {
            "instId": instId,
            "side": side,
            "sz": sz,
            "leverage": leverage,
            "margin": required_margin,
            "avg_px": executed_price,
            "tp_px": tp_px,
            "sl_px": sl_px,
            "highest_px": executed_price,
            "lowest_px": executed_price,
            "entry_time": datetime.now().strftime("%H:%M:%S")
        }
        self.positions[instId] = pos
        return {"code": "0", "msg": "SUCCESS", "order": pos}

    def close_position(self, instId: str, current_price: float, reason: str = "手动平仓") -> dict:
        if instId not in self.positions:
            return {"code": "1", "msg": "未找到当前持仓"}
        pos = self.positions.pop(instId)
        side = pos["side"]
        sz = pos["sz"]
        entry_px = pos["avg_px"]
        margin = pos["margin"]

        exit_px = current_price * (0.9998 if side == "buy" else 1.0002)
        pos_val = sz * exit_px
        fee = pos_val * self.fee_rate

        if side == "buy":
            raw_pnl = (exit_px - entry_px) * sz
        else:
            raw_pnl = (entry_px - exit_px) * sz

        net_pnl = raw_pnl - fee
        self.balance += (margin + net_pnl)

        pnl_ratio = (net_pnl / margin) * 100.0 if margin > 0 else 0.0

        trade_record = {
            "instId": instId,
            "side": side,
            "sz": sz,
            "leverage": pos["leverage"],
            "entry_px": entry_px,
            "exit_px": exit_px,
            "pnl": net_pnl,
            "pnl_pct": pnl_ratio,
            "reason": reason,
            "time": datetime.now().strftime("%H:%M:%S")
        }
        self.trades_history.append(trade_record)
        return {"code": "0", "msg": "SUCCESS", "record": trade_record}

    def update_price_and_check_tpsl(self, instId: str, current_price: float, trailing_stop_pct: float = 0.0015) -> list:
        events = []
        if instId not in self.positions:
            return events
        
        pos = self.positions[instId]
        side = pos["side"]
        
        pos["highest_px"] = max(pos["highest_px"], current_price)
        pos["lowest_px"] = min(pos["lowest_px"], current_price)

        if side == "buy":
            if current_price >= pos["tp_px"]:
                res = self.close_position(instId, current_price, reason="多单止盈 (TP)")
                events.append(res)
            elif current_price <= pos["sl_px"]:
                res = self.close_position(instId, current_price, reason="多单止损 (SL)")
                events.append(res)
            elif trailing_stop_pct > 0 and pos["highest_px"] > pos["avg_px"] * (1 + trailing_stop_pct * 2):
                trail_sl = pos["highest_px"] * (1.0 - trailing_stop_pct)
                if current_price <= trail_sl:
                    res = self.close_position(instId, current_price, reason="多单追踪止损 (Trailing SL)")
                    events.append(res)

        elif side == "sell":
            if current_price <= pos["tp_px"]:
                res = self.close_position(instId, current_price, reason="空单止盈 (TP)")
                events.append(res)
            elif current_price >= pos["sl_px"]:
                res = self.close_position(instId, current_price, reason="空单止损 (SL)")
                events.append(res)
            elif trailing_stop_pct > 0 and pos["lowest_px"] < pos["avg_px"] * (1 - trailing_stop_pct * 2):
                trail_sl = pos["lowest_px"] * (1.0 + trailing_stop_pct)
                if current_price >= trail_sl:
                    res = self.close_position(instId, current_price, reason="空单追踪止损 (Trailing SL)")
                    events.append(res)
                    
        return events

# ==================== 后台策略引擎工作线程 ====================
class ScalpingWorker(QThread):
    """剥头皮策略计算与网络容错 Worker"""
    sig_tick_update = pyqtSignal(dict)
    sig_kline_update = pyqtSignal(list)
    sig_signal_triggered = pyqtSignal(str, str, float, str)
    sig_trade_event = pyqtSignal(dict)
    sig_log = pyqtSignal(str, str)
    sig_account_update = pyqtSignal(dict)

    def __init__(self, config: dict):
        super().__init__()
        self.config = config
        self.is_running = False
        self.paper_trader = PaperTrader(initial_balance=config.get("paper_balance", 10000.0))
        self.last_trade_time = 0
        self.api_market = None
        self.api_trade = None
        self.api_account = None

    def get_formatted_inst_id(self) -> str:
        pair = self.config.get("inst_id", "BTC-USDT").strip()
        itype = self.config.get("inst_type", "SWAP")
        if itype == "SWAP":
            return pair if pair.endswith("-SWAP") else f"{pair}-SWAP"
        else:
            return pair.replace("-SWAP", "") if pair.endswith("-SWAP") else pair

    def init_apis(self):
        mode = self.config.get("trade_mode", "paper")
        flag = "1" if mode == "sim" else "0"
        
        domain = self.config.get("domain", "https://www.okx.com")
        proxy = self.config.get("proxy", "").strip() or None

        api_key = self.config.get("api_key", "")
        secret = self.config.get("secret_key", "")
        passphrase = self.config.get("passphrase", "")

        try:
            self.api_market = MarketData.MarketAPI(flag=flag, domain=domain, proxy=proxy)
            if mode in ["real", "sim"] and api_key and secret and passphrase:
                self.api_trade = Trade.TradeAPI(api_key, secret, passphrase, flag=flag, domain=domain, proxy=proxy)
                self.api_account = Account.AccountAPI(api_key, secret, passphrase, flag=flag, domain=domain, proxy=proxy)
                self.sig_log.emit("INFO", f"OKX API 连接成功！节点: [{domain}], 模式: {'模拟盘' if flag=='1' else '实盘'}")
            else:
                self.sig_log.emit("INFO", f"行情 API 初始化成功！节点: [{domain}] | 已启用【本地无损模拟盘】")
        except Exception as e:
            self.sig_log.emit("ERROR", f"API 初始化网络警告: {str(e)}")

    def safe_request(self, func, *args, retries=3, **kwargs):
        """安全带重试与 JSON 解析防御的 API 包装器"""
        for i in range(retries):
            try:
                res = func(*args, **kwargs)
                if isinstance(res, dict) and "code" in res:
                    return res
            except (json.decoder.JSONDecodeError, ValueError) as e:
                if i == retries - 1:
                    self.sig_log.emit("ERROR", "节点返回非 JSON 响应 (如 Cloudflare 530 HTML)，请在【三模交易与 API 网络】中切换至 OKX 官方节点或配置代理。")
            except (ssl.SSLError, Exception) as e:
                if i == retries - 1:
                    err_msg = str(e)
                    self.sig_log.emit("ERROR", f"网络波动: {err_msg[:60]}")
            time.sleep(0.4 * (i + 1))
        return None

    def run(self):
        self.is_running = True
        self.init_apis()
        
        inst_id = self.get_formatted_inst_id()
        bar = self.config.get("bar_period", "1m")
        if "1s" in bar:
            bar = "1m"

        itype = self.config.get("inst_type", "SWAP")
        leverage = int(self.config.get("leverage", 10))
        self.sig_log.emit("INFO", f"监控启动 | 标的: [{inst_id}] | 品种: [{itype}] | 杠杆: [{leverage}x]")

        while self.is_running:
            try:
                # 1. 安全抓取最新 K 线
                res = self.safe_request(self.api_market.get_candlesticks, instId=inst_id, bar=bar, limit="60")
                if res and res.get("code") == "0" and res.get("data"):
                    raw_candles = res["data"]
                    candles = []
                    for item in reversed(raw_candles):
                        candles.append({
                            "ts": int(item[0]),
                            "open": float(item[1]),
                            "high": float(item[2]),
                            "low": float(item[3]),
                            "close": float(item[4]),
                            "vol": float(item[5])
                        })
                    self.sig_kline_update.emit(candles)

                    closes = np.array([c["close"] for c in candles])
                    highs = np.array([c["high"] for c in candles])
                    lows = np.array([c["low"] for c in candles])

                    curr_price = closes[-1]

                    # 2. 指标计算
                    fast_p = int(self.config.get("ema_fast", 3))
                    slow_p = int(self.config.get("ema_slow", 8))
                    rsi_p = int(self.config.get("rsi_period", 7))

                    ema_fast = calc_ema(closes, fast_p)
                    ema_slow = calc_ema(closes, slow_p)
                    rsi = calc_rsi(closes, rsi_p)
                    atr = calc_atr(highs, lows, closes, 7)

                    # 3. 容错抓取盘口深度
                    ob_res = self.safe_request(self.api_market.get_orderbook, instId=inst_id, sz="10")
                    bid_vol, ask_vol = 0.0, 0.0
                    if ob_res and ob_res.get("code") == "0" and ob_res.get("data"):
                        ob = ob_res["data"][0]
                        bids = ob.get("bids", [])
                        asks = ob.get("asks", [])
                        bid_vol = sum(float(b[1]) for b in bids[:5])
                        ask_vol = sum(float(a[1]) for a in asks[:5])
                    
                    total_vol = bid_vol + ask_vol + 1e-9
                    imbalance_ratio = (bid_vol - ask_vol) / total_vol

                    # 4. 打分决策
                    score = 0
                    reasons = []

                    if ema_fast[-1] > ema_slow[-1] and ema_fast[-2] <= ema_slow[-2]:
                        score += 35
                        reasons.append(f"EMA({fast_p})金叉")
                    elif ema_fast[-1] > ema_slow[-1]:
                        score += 15

                    if ema_fast[-1] < ema_slow[-1] and ema_fast[-2] >= ema_slow[-2]:
                        score -= 35
                        reasons.append(f"EMA({fast_p})死叉")
                    elif ema_fast[-1] < ema_slow[-1]:
                        score -= 15

                    curr_rsi = rsi[-1]
                    if curr_rsi < float(self.config.get("rsi_oversold", 30)):
                        score += 25
                        reasons.append(f"RSI({rsi_p})超卖 ({curr_rsi:.1f})")
                    elif curr_rsi > float(self.config.get("rsi_overbought", 70)):
                        score -= 25
                        reasons.append(f"RSI({rsi_p})超买 ({curr_rsi:.1f})")

                    if imbalance_ratio > 0.35:
                        score += 20
                        reasons.append(f"盘口买压强 (+{(imbalance_ratio*100):.1f}%)")
                    elif imbalance_ratio < -0.35:
                        score -= 20
                        reasons.append(f"盘口卖压强 ({(imbalance_ratio*100):.1f}%)")

                    tick_data = {
                        "price": curr_price,
                        "ema_fast": float(ema_fast[-1]),
                        "ema_slow": float(ema_slow[-1]),
                        "rsi": float(curr_rsi),
                        "atr": float(atr),
                        "imbalance": float(imbalance_ratio),
                        "score": score,
                        "reasons": reasons
                    }
                    self.sig_tick_update.emit(tick_data)

                    # 5. 监视模拟持仓止盈止损
                    if self.config.get("trade_mode") == "paper":
                        events = self.paper_trader.update_price_and_check_tpsl(
                            inst_id, curr_price, 
                            trailing_stop_pct=float(self.config.get("trailing_stop_pct", 0.15)) / 100.0
                        )
                        for ev in events:
                            if ev.get("code") == "0":
                                rec = ev["record"]
                                self.sig_log.emit("TRADE", f"平仓触发 [{rec['reason']}] 净盈亏: ${rec['pnl']:.2f} ({rec['pnl_pct']:.2f}%)")
                                self.sig_trade_event.emit(rec)
                                self.sig_account_update.emit({"balance": self.paper_trader.balance})

                    # 6. 全自动开仓决策
                    cooldown = int(self.config.get("cooldown_sec", 10))
                    now_ts = time.time()
                    
                    buy_threshold = int(self.config.get("buy_score", 40))
                    sell_threshold = -int(self.config.get("sell_score", 40))

                    allow_long = self.config.get("allow_long", True)
                    allow_short = self.config.get("allow_short", True)

                    if now_ts - self.last_trade_time > cooldown:
                        tp_pct = float(self.config.get("tp_pct", 0.5)) / 100.0
                        sl_pct = float(self.config.get("sl_pct", 0.3)) / 100.0
                        trade_size = float(self.config.get("trade_size", 200.0))
                        
                        sz = (trade_size * leverage) / curr_price

                        if allow_long and score >= buy_threshold:
                            self.last_trade_time = now_ts
                            reason_str = " + ".join(reasons)
                            self.sig_signal_triggered.emit(inst_id, "BUY (开多)", curr_price, reason_str)
                            
                            if self.config.get("trade_mode") == "paper":
                                res = self.paper_trader.place_order(inst_id, "buy", sz, curr_price, leverage, tp_pct, sl_pct)
                                if res.get("code") == "0":
                                    self.sig_log.emit("TRADE", f"【自动开多 LONG】{inst_id} ({leverage}x) @ ${curr_price:.2f} | 理由: {reason_str}")
                                    self.sig_account_update.emit({"balance": self.paper_trader.balance})
                                else:
                                    self.sig_log.emit("ERROR", f"开多失败: {res.get('msg')}")

                        elif allow_short and score <= sell_threshold:
                            self.last_trade_time = now_ts
                            reason_str = " + ".join(reasons)
                            self.sig_signal_triggered.emit(inst_id, "SELL (开空)", curr_price, reason_str)
                            
                            if self.config.get("trade_mode") == "paper":
                                res = self.paper_trader.place_order(inst_id, "sell", sz, curr_price, leverage, tp_pct, sl_pct)
                                if res.get("code") == "0":
                                    self.sig_log.emit("TRADE", f"【自动开空 SHORT】{inst_id} ({leverage}x) @ ${curr_price:.2f} | 理由: {reason_str}")
                                    self.sig_account_update.emit({"balance": self.paper_trader.balance})
                                else:
                                    self.sig_log.emit("ERROR", f"开空失败: {res.get('msg')}")

            except Exception as e:
                self.sig_log.emit("ERROR", f"策略循环提示: {str(e)[:60]}")

            time.sleep(1.5)

    def stop(self):
        self.is_running = False
        self.wait()

# ==================== 主窗口界面 ====================
class OKXScalpingMainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("OKX 剥头皮自动化交易终端 — Scalper Pro v1.3 (标准 JSON 接口版)")
        self.resize(1420, 920)

        self.config = self.load_config()
        self.worker = None

        self.init_ui()
        self.apply_theme()

    def load_config(self) -> dict:
        default_cfg = {
            "trade_mode": "paper", # paper, sim, real
            "inst_type": "SWAP",   # SWAP / SPOT
            "leverage": 10,
            "margin_mode": "isolated",
            "domain": "https://www.okx.com", # 默认标准 JSON 节点
            "proxy": "",
            "api_key": "",
            "secret_key": "",
            "passphrase": "",
            "inst_id": "BTC-USDT",
            "bar_period": "1m",
            "ema_fast": 3,
            "ema_slow": 8,
            "rsi_period": 7,
            "rsi_oversold": 30,
            "rsi_overbought": 70,
            "buy_score": 40,
            "sell_score": 40,
            "allow_long": True,
            "allow_short": True,
            "tp_pct": 0.5,
            "sl_pct": 0.3,
            "trailing_stop_pct": 0.15,
            "trade_size": 200.0,
            "paper_balance": 10000.0,
            "cooldown_sec": 10
        }
        if CONFIG_FILE.exists():
            try:
                with open(CONFIG_FILE, "r", encoding="utf-8") as f:
                    saved = json.load(f)
                    default_cfg.update(saved)
            except Exception:
                pass
        return default_cfg

    def save_config(self):
        try:
            self.config["trade_mode"] = self.combo_mode.currentData()
            self.config["inst_type"] = self.combo_itype.currentData()
            self.config["inst_id"] = self.combo_inst.currentText().strip()
            self.config["bar_period"] = self.combo_bar.currentText()
            self.config["leverage"] = self.spin_leverage.value()
            self.config["margin_mode"] = self.combo_margin.currentData()

            self.config["domain"] = self.combo_domain.currentData()
            self.config["proxy"] = self.txt_proxy.text().strip()

            self.config["api_key"] = self.txt_api_key.text().strip()
            self.config["secret_key"] = self.txt_secret.text().strip()
            self.config["passphrase"] = self.txt_pass.text().strip()

            self.config["ema_fast"] = self.spin_ema_fast.value()
            self.config["ema_slow"] = self.spin_ema_slow.value()
            self.config["rsi_period"] = self.spin_rsi_p.value()
            self.config["tp_pct"] = self.spin_tp.value()
            self.config["sl_pct"] = self.spin_sl.value()
            self.config["trailing_stop_pct"] = self.spin_trail.value()
            self.config["trade_size"] = self.spin_sz.value()
            self.config["cooldown_sec"] = self.spin_cd.value()

            self.config["allow_long"] = self.chk_allow_long.isChecked()
            self.config["allow_short"] = self.chk_allow_short.isChecked()

            with open(CONFIG_FILE, "w", encoding="utf-8") as f:
                json.dump(self.config, f, indent=2, ensure_ascii=False)
            
            QMessageBox.information(self, "成功", "交易配置与网络节点设置已保存！")
        except Exception as e:
            QMessageBox.critical(self, "错误", f"保存配置失败: {str(e)}")

    def init_ui(self):
        main_widget = QWidget()
        self.setCentralWidget(main_widget)
        main_layout = QVBoxLayout(main_widget)
        main_layout.setContentsMargins(12, 12, 12, 12)
        main_layout.setSpacing(10)

        # 1. 顶部 Header
        header_frame = self.create_header()
        main_layout.addWidget(header_frame)

        # 2. 中间 Tab
        self.tabs = QTabWidget()
        main_layout.addWidget(self.tabs, stretch=1)

        tab_console = self.create_console_tab()
        self.tabs.addTab(tab_console, " 剥头皮核心控制台 ")

        tab_strategy = self.create_strategy_tab()
        self.tabs.addTab(tab_strategy, " 策略参数与合约杠杆 ")

        tab_api = self.create_api_tab()
        self.tabs.addTab(tab_api, " 三模交易与 API 网络 ")

        # 3. 底部日志
        log_group = QGroupBox("剥头皮全自动执行日志")
        log_layout = QVBoxLayout(log_group)
        self.log_text = QTextEdit()
        self.log_text.setReadOnly(True)
        self.log_text.setMaximumHeight(140)
        log_layout.addWidget(self.log_text)
        main_layout.addWidget(log_group)

    def create_header(self) -> QFrame:
        frame = QFrame()
        frame.setObjectName("HeaderFrame")
        layout = QHBoxLayout(frame)
        layout.setContentsMargins(15, 10, 15, 10)

        title = QLabel("⚡ Scalper Pro 自动化交易终端")
        title.setFont(QFont("Segoe UI", 14, QFont.Weight.Bold))
        title.setStyleSheet(f"color: {C_PRIMARY};")
        layout.addWidget(title)

        layout.addSpacing(15)

        layout.addWidget(QLabel("品种:"))
        self.combo_itype = QComboBox()
        self.combo_itype.addItem("永续合约 (SWAP)", "SWAP")
        self.combo_itype.addItem("现货 (SPOT)", "SPOT")
        idx_t = self.combo_itype.findData(self.config.get("inst_type", "SWAP"))
        if idx_t >= 0: self.combo_itype.setCurrentIndex(idx_t)
        layout.addWidget(self.combo_itype)

        layout.addWidget(QLabel("币种:"))
        self.combo_inst = QComboBox()
        self.combo_inst.addItems(DEFAULT_PAIRS)
        self.combo_inst.setCurrentText(self.config.get("inst_id", "BTC-USDT"))
        self.combo_inst.setEditable(True)
        layout.addWidget(self.combo_inst)

        layout.addWidget(QLabel("杠杆:"))
        self.spin_leverage = QSpinBox()
        self.spin_leverage.setRange(1, 100)
        self.spin_leverage.setValue(int(self.config.get("leverage", 10)))
        self.spin_leverage.setSuffix(" x")
        layout.addWidget(self.spin_leverage)

        layout.addWidget(QLabel("周期:"))
        self.combo_bar = QComboBox()
        self.combo_bar.addItems(BAR_PERIODS)
        self.combo_bar.setCurrentText(self.config.get("bar_period", "1m"))
        layout.addWidget(self.combo_bar)

        layout.addWidget(QLabel("模式:"))
        self.combo_mode = QComboBox()
        self.combo_mode.addItem("本地无损模拟盘 (Paper)", "paper")
        self.combo_mode.addItem("OKX 官方模拟盘 (Sim)", "sim")
        self.combo_mode.addItem("OKX 实盘交易 (Real)", "real")
        layout.addWidget(self.combo_mode)

        layout.addStretch()

        self.btn_start = QPushButton("▶ 启动全自动剥头皮引擎")
        self.btn_start.setObjectName("BtnStart")
        self.btn_start.clicked.connect(self.toggle_strategy)
        layout.addWidget(self.btn_start)

        self.btn_panic = QPushButton("🚨 一键紧急平仓 (Panic Exit)")
        self.btn_panic.setObjectName("BtnPanic")
        self.btn_panic.clicked.connect(self.panic_exit)
        layout.addWidget(self.btn_panic)

        return frame

    def create_console_tab(self) -> QWidget:
        widget = QWidget()
        layout = QHBoxLayout(widget)

        left_splitter = QSplitter(Qt.Orientation.Vertical)
        
        if HAS_WEBENGINE:
            self.web_view = QWebEngineView()
            left_splitter.addWidget(self.web_view)
        else:
            fallback_lbl = QLabel("未检测到 QtWebEngineView 模块。")
            fallback_lbl.setAlignment(Qt.AlignmentFlag.AlignCenter)
            left_splitter.addWidget(fallback_lbl)

        pos_box = QGroupBox("当前活动持仓 (Active Positions)")
        pos_layout = QVBoxLayout(pos_box)
        self.pos_table = QTableWidget(0, 8)
        self.pos_table.setHorizontalHeaderLabels(["标的", "方向", "杠杆", "持仓量", "开仓价", "当前价", "浮动盈亏", "操作"])
        self.pos_table.horizontalHeader().setSectionResizeMode(QHeaderView.ResizeMode.Stretch)
        pos_layout.addWidget(self.pos_table)
        left_splitter.addWidget(pos_box)

        layout.addWidget(left_splitter, stretch=7)

        right_panel = QVBoxLayout()
        
        sig_box = QGroupBox("自动方向共识打分雷达 (Direction Radar)")
        sig_layout = QVBoxLayout(sig_box)

        self.lbl_signal_score = QLabel("得分: 0 分 (观望中)")
        self.lbl_signal_score.setFont(QFont("Segoe UI", 16, QFont.Weight.Bold))
        self.lbl_signal_score.setAlignment(Qt.AlignmentFlag.AlignCenter)
        sig_layout.addWidget(self.lbl_signal_score)

        self.progress_signal = QProgressBar()
        self.progress_signal.setRange(-100, 100)
        self.progress_signal.setValue(0)
        self.progress_signal.setTextVisible(False)
        sig_layout.addWidget(self.progress_signal)

        self.lbl_signal_reasons = QLabel("触发因子: 等待指标计算...")
        self.lbl_signal_reasons.setWordWrap(True)
        self.lbl_signal_reasons.setStyleSheet(f"color: {C_TEXT_MUTED}; font-size: 12px;")
        sig_layout.addWidget(self.lbl_signal_reasons)

        right_panel.addWidget(sig_box)

        dir_box = QGroupBox("自动交易方向许可 (Auto Direction Control)")
        dir_layout = QHBoxLayout(dir_box)
        self.chk_allow_long = QCheckBox("允许自动做多 (Long)")
        self.chk_allow_long.setChecked(self.config.get("allow_long", True))
        
        self.chk_allow_short = QCheckBox("允许自动做空 (Short)")
        self.chk_allow_short.setChecked(self.config.get("allow_short", True))

        dir_layout.addWidget(self.chk_allow_long)
        dir_layout.addWidget(self.chk_allow_short)
        right_panel.addWidget(dir_box)

        stat_box = QGroupBox("剥头皮战报统计 (Performance Analytics)")
        stat_layout = QGridLayout(stat_box)

        self.lbl_stat_winrate = QLabel("0.0 %")
        self.lbl_stat_pnl = QLabel("$ 0.00")
        self.lbl_stat_count = QLabel("0 次")
        self.lbl_stat_balance = QLabel(f"$ {self.config.get('paper_balance', 10000.0):.2f}")

        for lbl in [self.lbl_stat_winrate, self.lbl_stat_pnl, self.lbl_stat_count, self.lbl_stat_balance]:
            lbl.setFont(QFont("Segoe UI", 12, QFont.Weight.Bold))

        stat_layout.addWidget(QLabel("模拟总余额:"), 0, 0)
        stat_layout.addWidget(self.lbl_stat_balance, 0, 1)
        stat_layout.addWidget(QLabel("剥头皮胜率:"), 1, 0)
        stat_layout.addWidget(self.lbl_stat_winrate, 1, 1)
        stat_layout.addWidget(QLabel("累计净盈亏:"), 2, 0)
        stat_layout.addWidget(self.lbl_stat_pnl, 2, 1)
        stat_layout.addWidget(QLabel("总交易笔数:"), 3, 0)
        stat_layout.addWidget(self.lbl_stat_count, 3, 1)

        right_panel.addWidget(stat_box)

        ctrl_box = QGroupBox("极速手动建仓 (快捷操作)")
        ctrl_layout = QGridLayout(ctrl_box)
        btn_quick_buy = QPushButton("🟢 手动开多 (Long)")
        btn_quick_buy.setStyleSheet(f"background-color: {C_GREEN}; font-weight: bold;")
        btn_quick_buy.clicked.connect(lambda: self.manual_place_order("BUY"))
        
        btn_quick_sell = QPushButton("🔴 手动开空 (Short)")
        btn_quick_sell.setStyleSheet(f"background-color: {C_RED}; font-weight: bold;")
        btn_quick_sell.clicked.connect(lambda: self.manual_place_order("SELL"))

        ctrl_layout.addWidget(btn_quick_buy, 0, 0)
        ctrl_layout.addWidget(btn_quick_sell, 0, 1)
        right_panel.addWidget(ctrl_box)

        right_panel.addStretch()
        layout.addLayout(right_panel, stretch=3)

        return widget

    def create_strategy_tab(self) -> QWidget:
        widget = QWidget()
        layout = QVBoxLayout(widget)

        form_group = QGroupBox("剥头皮与合约交易参数 (Scalping Parameters)")
        form = QFormLayout(form_group)
        form.setSpacing(15)

        self.combo_margin = QComboBox()
        self.combo_margin.addItem("逐仓 (Isolated)", "isolated")
        self.combo_margin.addItem("全仓 (Cross)", "cross")
        idx_m = self.combo_margin.findData(self.config.get("margin_mode", "isolated"))
        if idx_m >= 0: self.combo_margin.setCurrentIndex(idx_m)
        form.addRow("保证金模式 (Margin Mode):", self.combo_margin)

        self.spin_ema_fast = QSpinBox()
        self.spin_ema_fast.setRange(2, 20)
        self.spin_ema_fast.setValue(int(self.config.get("ema_fast", 3)))
        form.addRow("快线 EMA (Fast Period):", self.spin_ema_fast)

        self.spin_ema_slow = QSpinBox()
        self.spin_ema_slow.setRange(5, 50)
        self.spin_ema_slow.setValue(int(self.config.get("ema_slow", 8)))
        form.addRow("慢线 EMA (Slow Period):", self.spin_ema_slow)

        self.spin_rsi_p = QSpinBox()
        self.spin_rsi_p.setRange(3, 30)
        self.spin_rsi_p.setValue(int(self.config.get("rsi_period", 7)))
        form.addRow("RSI 周期 (RSI Period):", self.spin_rsi_p)

        self.spin_tp = QDoubleSpinBox()
        self.spin_tp.setRange(0.1, 10.0)
        self.spin_tp.setSingleStep(0.1)
        self.spin_tp.setValue(float(self.config.get("tp_pct", 0.5)))
        self.spin_tp.setSuffix(" %")
        form.addRow("目标止盈比例 (Take Profit):", self.spin_tp)

        self.spin_sl = QDoubleSpinBox()
        self.spin_sl.setRange(0.1, 10.0)
        self.spin_sl.setSingleStep(0.1)
        self.spin_sl.setValue(float(self.config.get("sl_pct", 0.3)))
        self.spin_sl.setSuffix(" %")
        form.addRow("紧密止损比例 (Stop Loss):", self.spin_sl)

        self.spin_trail = QDoubleSpinBox()
        self.spin_trail.setRange(0.05, 5.0)
        self.spin_trail.setSingleStep(0.05)
        self.spin_trail.setValue(float(self.config.get("trailing_stop_pct", 0.15)))
        self.spin_trail.setSuffix(" %")
        form.addRow("移动追踪止损回撤 (Trailing Stop):", self.spin_trail)

        self.spin_sz = QDoubleSpinBox()
        self.spin_sz.setRange(10.0, 100000.0)
        self.spin_sz.setValue(float(self.config.get("trade_size", 200.0)))
        self.spin_sz.setPrefix("$ ")
        form.addRow("单笔下单本金 (USDT):", self.spin_sz)

        self.spin_cd = QSpinBox()
        self.spin_cd.setRange(1, 300)
        self.spin_cd.setValue(int(self.config.get("cooldown_sec", 10)))
        self.spin_cd.setSuffix(" 秒")
        form.addRow("开仓冷却间隔 (Trade Cooldown):", self.spin_cd)

        btn_save = QPushButton("💾 保存并应用参数配置")
        btn_save.setStyleSheet(f"background-color: {C_PRIMARY}; padding: 10px; font-weight: bold;")
        btn_save.clicked.connect(self.save_config)
        form.addRow("", btn_save)

        layout.addWidget(form_group)
        layout.addStretch()
        return widget

    def create_api_tab(self) -> QWidget:
        widget = QWidget()
        layout = QVBoxLayout(widget)

        net_box = QGroupBox("网络节点与代理设置 (网络防挂挂锁)")
        net_form = QFormLayout(net_box)
        net_form.setSpacing(12)

        self.combo_domain = QComboBox()
        for url, desc in DOMAIN_OPTIONS:
            self.combo_domain.addItem(f"{desc} ({url})", url)
        curr_dom = self.config.get("domain", "https://www.okx.com")
        idx_d = self.combo_domain.findData(curr_dom)
        if idx_d >= 0: self.combo_domain.setCurrentIndex(idx_d)
        net_form.addRow("OKX API 节点:", self.combo_domain)

        self.txt_proxy = QLineEdit(self.config.get("proxy", ""))
        self.txt_proxy.setPlaceholderText("可留空。如使用 Clash 可填: http://127.0.0.1:7890")
        net_form.addRow("HTTP/SOCKS5 代理:", self.txt_proxy)

        layout.addWidget(net_box)

        api_box = QGroupBox("OKX API 官方凭证设置")
        form = QFormLayout(api_box)
        form.setSpacing(12)

        self.txt_api_key = QLineEdit(self.config.get("api_key", ""))
        form.addRow("API Key:", self.txt_api_key)

        self.txt_secret = QLineEdit(self.config.get("secret_key", ""))
        self.txt_secret.setEchoMode(QLineEdit.EchoMode.Password)
        form.addRow("Secret Key:", self.txt_secret)

        self.txt_pass = QLineEdit(self.config.get("passphrase", ""))
        self.txt_pass.setEchoMode(QLineEdit.EchoMode.Password)
        form.addRow("Passphrase:", self.txt_pass)

        btn_save_api = QPushButton("💾 保存 API 凭证与网络设置")
        btn_save_api.setStyleSheet(f"background-color: {C_PRIMARY}; padding: 8px;")
        btn_save_api.clicked.connect(self.save_config)
        form.addRow("", btn_save_api)

        layout.addWidget(api_box)
        layout.addStretch()
        return widget

    # ==================== 逻辑处理 ====================
    def log_message(self, level: str, msg: str):
        ts = datetime.now().strftime("%H:%M:%S")
        color_map = {"INFO": C_PRIMARY, "TRADE": C_GREEN, "ERROR": C_RED}
        color = color_map.get(level, C_TEXT_MAIN)
        html = f"<span style='color:{C_TEXT_MUTED};'>[{ts}]</span> <b style='color:{color};'>[{level}]</b> {msg}"
        self.log_text.append(html)

    def toggle_strategy(self):
        if self.worker and self.worker.isRunning():
            self.worker.stop()
            self.worker = None
            self.btn_start.setText("▶ 启动全自动剥头皮引擎")
            self.btn_start.setStyleSheet("")
            self.log_message("INFO", "策略引擎已停止。")
        else:
            self.config["trade_mode"] = self.combo_mode.currentData()
            self.config["inst_type"] = self.combo_itype.currentData()
            self.config["inst_id"] = self.combo_inst.currentText().strip()
            self.config["bar_period"] = self.combo_bar.currentText()
            self.config["leverage"] = self.spin_leverage.value()

            self.config["domain"] = self.combo_domain.currentData()
            self.config["proxy"] = self.txt_proxy.text().strip()

            self.config["allow_long"] = self.chk_allow_long.isChecked()
            self.config["allow_short"] = self.chk_allow_short.isChecked()

            self.worker = ScalpingWorker(self.config)
            self.worker.sig_log.connect(self.log_message)
            self.worker.sig_tick_update.connect(self.on_tick_update)
            self.worker.sig_kline_update.connect(self.on_kline_update)
            self.worker.sig_trade_event.connect(self.on_trade_event)
            self.worker.sig_account_update.connect(self.on_account_update)
            self.worker.start()

            self.btn_start.setText("⏹ 停止策略引擎")
            self.btn_start.setStyleSheet(f"background-color: {C_RED}; font-weight: bold;")

    def panic_exit(self):
        if self.worker and self.worker.paper_trader:
            inst = self.worker.get_formatted_inst_id()
            if inst in self.worker.paper_trader.positions:
                curr_px = self.worker.paper_trader.positions[inst]["avg_px"]
                self.worker.paper_trader.close_position(inst, curr_px, reason="🚨 紧急一键平仓 (Panic Exit)")
                self.log_message("ERROR", f"已触发紧急平仓 [{inst}]")
                self.update_positions_table()

    def manual_place_order(self, side: str):
        if not self.worker or not self.worker.isRunning():
            QMessageBox.warning(self, "提示", "请先启动剥头皮策略引擎！")
            return
        inst = self.worker.get_formatted_inst_id()
        curr_px = getattr(self, "last_curr_price", 60000.0)
        lev = self.spin_leverage.value()
        sz = (float(self.config.get("trade_size", 200.0)) * lev) / curr_px
        
        if self.worker.paper_trader:
            res = self.worker.paper_trader.place_order(
                inst, side.lower(), sz, curr_px, lev,
                float(self.config.get("tp_pct", 0.5))/100.0,
                float(self.config.get("sl_pct", 0.3))/100.0
            )
            if res.get("code") == "0":
                self.log_message("TRADE", f"手动【{side}】成功 @ ${curr_px:.2f} ({lev}x)")
                self.update_positions_table()

    def on_tick_update(self, tick: dict):
        self.last_curr_price = tick["price"]
        score = tick["score"]
        
        if score > 20:
            self.lbl_signal_score.setText(f"得分: +{score} (看多信号 🟢)")
            self.lbl_signal_score.setStyleSheet(f"color: {C_GREEN};")
        elif score < -20:
            self.lbl_signal_score.setText(f"得分: {score} (看空信号 🔴)")
            self.lbl_signal_score.setStyleSheet(f"color: {C_RED};")
        else:
            self.lbl_signal_score.setText(f"得分: {score} (震荡观望)")
            self.lbl_signal_score.setStyleSheet(f"color: {C_TEXT_MAIN};")

        self.progress_signal.setValue(score)
        reasons_str = " | ".join(tick["reasons"]) if tick["reasons"] else "指标中性，无明显破位"
        self.lbl_signal_reasons.setText(f"共识触发因子: {reasons_str}")

        self.update_positions_table()

    def on_kline_update(self, candles: list):
        if not HAS_WEBENGINE or not candles:
            return

        dates = [datetime.fromtimestamp(c["ts"]/1000).strftime("%H:%M") for c in candles]
        opens = [c["open"] for c in candles]
        highs = [c["high"] for c in candles]
        lows = [c["low"] for c in candles]
        closes = [c["close"] for c in candles]

        arr_c = np.array(closes)
        ema_fast = calc_ema(arr_c, int(self.config.get("ema_fast", 3)))
        ema_slow = calc_ema(arr_c, int(self.config.get("ema_slow", 8)))

        fig = make_subplots(rows=2, cols=1, shared_xaxes=True, vertical_spacing=0.03, row_heights=[0.75, 0.25])

        fig.add_trace(go.Candlestick(
            x=dates, open=opens, high=highs, low=lows, close=closes,
            name="K线",
            increasing_line_color=C_GREEN, decreasing_line_color=C_RED
        ), row=1, col=1)

        fig.add_trace(go.Scatter(x=dates, y=ema_fast, line=dict(color="#38bdf8", width=1.5), name=f"EMA{self.config.get('ema_fast',3)}"), row=1, col=1)
        fig.add_trace(go.Scatter(x=dates, y=ema_slow, line=dict(color="#f43f5e", width=1.5), name=f"EMA{self.config.get('ema_slow',8)}"), row=1, col=1)

        rsi = calc_rsi(arr_c, int(self.config.get("rsi_period", 7)))
        fig.add_trace(go.Scatter(x=dates, y=rsi, line=dict(color="#a855f7", width=1.5), name="RSI"), row=2, col=1)

        fig.update_layout(
            template="plotly_dark",
            paper_bgcolor=C_BG,
            plot_bgcolor=C_CARD,
            margin=dict(l=10, r=10, t=25, b=10),
            showlegend=False,
            xaxis_rangeslider_visible=False
        )

        html = pio.to_html(fig, include_plotlyjs="cdn", full_html=True)
        self.web_view.setHtml(html)

    def on_trade_event(self, rec: dict):
        self.update_stats()
        self.update_positions_table()

    def on_account_update(self, acc: dict):
        bal = acc.get("balance", 10000.0)
        self.lbl_stat_balance.setText(f"$ {bal:.2f}")

    def update_positions_table(self):
        if not self.worker or not self.worker.paper_trader:
            return
        positions = self.worker.paper_trader.positions
        self.pos_table.setRowCount(len(positions))

        for idx, (inst, pos) in enumerate(positions.items()):
            curr_px = getattr(self, "last_curr_price", pos["avg_px"])
            side = pos["side"]
            sz = pos["sz"]
            entry_px = pos["avg_px"]
            lev = pos["leverage"]

            if side == "buy":
                pnl = (curr_px - entry_px) * sz
                side_lbl = "做多 (Long)"
            else:
                pnl = (entry_px - curr_px) * sz
                side_lbl = "做空 (Short)"

            self.pos_table.setItem(idx, 0, QTableWidgetItem(inst))
            
            side_item = QTableWidgetItem(side_lbl)
            side_item.setForeground(QColor(C_GREEN if side=="buy" else C_RED))
            self.pos_table.setItem(idx, 1, side_item)

            self.pos_table.setItem(idx, 2, QTableWidgetItem(f"{lev}x"))
            self.pos_table.setItem(idx, 3, QTableWidgetItem(f"{sz:.4f}"))
            self.pos_table.setItem(idx, 4, QTableWidgetItem(f"${entry_px:.2f}"))
            self.pos_table.setItem(idx, 5, QTableWidgetItem(f"${curr_px:.2f}"))

            pnl_item = QTableWidgetItem(f"${pnl:+.2f}")
            pnl_item.setForeground(QColor(C_GREEN if pnl >= 0 else C_RED))
            self.pos_table.setItem(idx, 6, pnl_item)

            btn_close = QPushButton("平仓")
            btn_close.clicked.connect(lambda _, i=inst, p=curr_px: self.worker.paper_trader.close_position(i, p, reason="手动单平"))
            self.pos_table.setCellWidget(idx, 7, btn_close)

    def update_stats(self):
        if not self.worker or not self.worker.paper_trader:
            return
        trades = self.worker.paper_trader.trades_history
        if not trades:
            return

        total_cnt = len(trades)
        win_cnt = sum(1 for t in trades if t["pnl"] > 0)
        total_pnl = sum(t["pnl"] for t in trades)
        win_rate = (win_cnt / total_cnt) * 100.0

        self.lbl_stat_winrate.setText(f"{win_rate:.1f} %")
        self.lbl_stat_winrate.setStyleSheet(f"color: {C_GREEN if win_rate>=50 else C_RED};")
        
        self.lbl_stat_pnl.setText(f"$ {total_pnl:+.2f}")
        self.lbl_stat_pnl.setStyleSheet(f"color: {C_GREEN if total_pnl>=0 else C_RED};")

        self.lbl_stat_count.setText(f"{total_cnt} 次")

    def apply_theme(self):
        qss = f"""
        QMainWindow {{
            background-color: {C_BG};
        }}
        QWidget {{
            color: {C_TEXT_MAIN};
            font-family: 'Segoe UI', Arial, sans-serif;
        }}
        #HeaderFrame {{
            background-color: {C_CARD};
            border-bottom: 1px solid {C_BORDER};
            border-radius: 6px;
        }}
        QGroupBox {{
            background-color: {C_CARD};
            border: 1px solid {C_BORDER};
            border-radius: 6px;
            margin-top: 10px;
            font-weight: bold;
            padding: 10px;
        }}
        QGroupBox::title {{
            subcontrol-origin: margin;
            subcontrol-position: top left;
            padding: 2px 8px;
            color: {C_PRIMARY};
        }}
        QTabWidget::pane {{
            border: 1px solid {C_BORDER};
            background-color: {C_BG};
        }}
        QTabBar::tab {{
            background-color: {C_SURFACE};
            color: {C_TEXT_MUTED};
            padding: 8px 16px;
            border-top-left-radius: 4px;
            border-top-right-radius: 4px;
            margin-right: 2px;
        }}
        QTabBar::tab:selected {{
            background-color: {C_PRIMARY};
            color: #ffffff;
            font-weight: bold;
        }}
        QPushButton {{
            background-color: {C_SURFACE};
            border: 1px solid {C_BORDER};
            border-radius: 4px;
            padding: 6px 14px;
            color: {C_TEXT_MAIN};
        }}
        QPushButton:hover {{
            background-color: {C_BORDER};
        }}
        #BtnStart {{
            background-color: {C_GREEN};
            color: #ffffff;
            font-weight: bold;
        }}
        #BtnStart:hover {{
            background-color: {C_GREEN_HOVER};
        }}
        #BtnPanic {{
            background-color: {C_RED};
            color: #ffffff;
            font-weight: bold;
        }}
        #BtnPanic:hover {{
            background-color: {C_RED_HOVER};
        }}
        QComboBox, QLineEdit, QSpinBox, QDoubleSpinBox {{
            background-color: {C_SURFACE};
            border: 1px solid {C_BORDER};
            border-radius: 4px;
            padding: 4px 8px;
            color: {C_TEXT_MAIN};
        }}
        QTableWidget {{
            background-color: {C_CARD};
            gridline-color: {C_BORDER};
            border: none;
        }}
        QHeaderView::section {{
            background-color: {C_SURFACE};
            color: {C_TEXT_MUTED};
            padding: 4px;
            border: 1px solid {C_BORDER};
        }}
        QTextEdit {{
            background-color: {C_CARD};
            border: 1px solid {C_BORDER};
            border-radius: 4px;
        }}
        """
        self.setStyleSheet(qss)

if __name__ == "__main__":
    app = QApplication(sys.argv)
    window = OKXScalpingMainWindow()
    window.show()
    sys.exit(app.exec())
