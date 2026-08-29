package com.bitdash.app.market

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * 模拟盘自动化交易引擎 (Paper Trading & Execution Engine)
 * 包含风险控制、头寸计算、持仓生命周期状态机与多策略评估
 */
class PaperTradingEngine(private val context: Context) {

    companion object {
        const val STRATEGY_3K = "3K"
        const val STRATEGY_LORENTZIAN = "LORENTZIAN"
        const val STRATEGY_EMA_CROSS = "EMA_CROSS"
        const val STRATEGY_PINE = "PINE"
    }

    var balance: Double = PaperTradingPrefs.getBalance(context)
        private set

    var position: PaperPosition? = PaperTradingPrefs.getPosition(context)
        private set

    var isAutoTrading: Boolean = PaperTradingPrefs.isAutoTradingEnabled(context)
        private set

    var strategyMode: String = PaperTradingPrefs.getStrategyMode(context)
    var selectedPineId: String = PaperTradingPrefs.getSelectedPineId(context)
    var riskPercent: Double = PaperTradingPrefs.getRiskPercent(context)
    var leverage: Int = PaperTradingPrefs.getLeverage(context)
    var antiChopEnabled: Boolean = PaperTradingPrefs.isAntiChopEnabled(context)

    val tradeHistory: MutableList<PaperTradeRecord> = PaperTradingPrefs.getTradeHistory(context)
    val logs: MutableList<String> = PaperTradingPrefs.getLogs(context)

    var onStateChanged: (() -> Unit)? = null

    val commissionRate = 0.0004 // 0.04% 模拟手续费
    val rrBreakeven = 1.0       // 1:1 R 保本
    val rrTakeProfit = 3.0     // 1:3 R 第一止盈
    val tp1Ratio = 0.50        // 止盈 50% 仓位

    init {
        if (logs.isEmpty()) {
            log("🤖 模拟量化交易引擎已初始化，默认资金: 10,000 USDT")
        }
    }

    fun setAutoTrading(enabled: Boolean) {
        isAutoTrading = enabled
        PaperTradingPrefs.setAutoTradingEnabled(context, enabled)
        if (enabled) {
            log("▶️ 自动化模拟交易已【启动】，开始持续监听 K 线买卖信号...")
        } else {
            log("⏸️ 自动化模拟交易已【停止】")
        }
        notifyChange()
    }

    fun updateSettings(mode: String, pineId: String, risk: Double, lev: Int, antiChop: Boolean) {
        strategyMode = mode
        selectedPineId = pineId
        riskPercent = risk
        leverage = lev
        antiChopEnabled = antiChop

        PaperTradingPrefs.setStrategyMode(context, mode)
        PaperTradingPrefs.setSelectedPineId(context, pineId)
        PaperTradingPrefs.setRiskPercent(context, risk)
        PaperTradingPrefs.setLeverage(context, lev)
        PaperTradingPrefs.setAntiChopEnabled(context, antiChop)
        notifyChange()
    }

    fun log(msg: String) {
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = "[$timeStr] $msg"
        logs.add(0, entry)
        if (logs.size > 200) logs.removeAt(logs.size - 1)
        PaperTradingPrefs.saveLogs(context, logs)
        notifyChange()
    }

    fun clearLogs() {
        logs.clear()
        PaperTradingPrefs.saveLogs(context, logs)
        notifyChange()
    }

    fun clearTradeHistory() {
        tradeHistory.clear()
        PaperTradingPrefs.saveTradeHistory(context, tradeHistory)
        notifyChange()
    }

    private fun notifyChange() {
        onStateChanged?.invoke()
    }

    val totalEquity: Double
        get() = balance + (position?.margin ?: 0.0) + (position?.unrealizedPnl ?: 0.0)

    val totalRealizedPnl: Double
        get() = tradeHistory.sumOf { it.realizedPnl }

    val winRate: Double
        get() {
            val closed = tradeHistory.filter { it.action != "OPEN_LONG" && it.action != "OPEN_SHORT" }
            if (closed.isEmpty()) return 0.0
            val wins = closed.count { it.realizedPnl > 0 }
            return (wins.toDouble() / closed.size) * 100.0
        }

    /**
     * 根据最新行情价格刷新浮动盈亏
     */
    fun updateMarketPrice(currentPrice: Double) {
        val pos = position ?: return
        val diff = if (pos.side == "LONG") {
            currentPrice - pos.entryPrice
        } else {
            pos.entryPrice - currentPrice
        }
        pos.unrealizedPnl = diff * pos.quantity
        pos.pnlPct = (diff / max(pos.entryPrice, 1e-6)) * 100.0 * pos.leverage
        PaperTradingPrefs.setPosition(context, pos)
        notifyChange()
    }

    /**
     * 核心评估与撮合函数：传入最新 K 线与现价
     */
    fun evaluateAndExecute(symbol: String, currentPrice: Double, candles: List<Candle>) {
        if (candles.size < 20 || currentPrice <= 0) return

        updateMarketPrice(currentPrice)

        // 1. 如果有持仓，管理当前持仓 (止损、保本、分批止盈、移动止盈、反向平仓)
        position?.let { pos ->
            manageActivePosition(pos, symbol, currentPrice, candles)
        }

        // 2. 如果开启了自动交易且当前无持仓，进行入场开仓评估
        if (isAutoTrading && position == null) {
            evaluateNewEntry(symbol, currentPrice, candles)
        }
    }

    private fun manageActivePosition(pos: PaperPosition, symbol: String, currentPrice: Double, candles: List<Candle>) {
        val curAtr = calculateAtr(candles)

        if (pos.side == "LONG") {
            // 1. 检查触发保本 (1.0 R)
            if (!pos.isBeActivated && currentPrice >= pos.beTriggerPrice) {
                pos.isBeActivated = true
                pos.slPrice = max(pos.slPrice, pos.entryPrice)
                log("🛡️ 【保本触发】价格达 1.0R ($currentPrice)，多单止损上移至开仓均价 $${pos.entryPrice}")
            }

            // 2. 检查第一止盈 (3.0 R，平仓 50%)
            if (!pos.isTp1Filled && currentPrice >= pos.tp1Price) {
                pos.isTp1Filled = true
                val closeQty = pos.quantity * tp1Ratio
                val grossPnl = (currentPrice - pos.entryPrice) * closeQty
                val closeComm = currentPrice * closeQty * commissionRate
                val pnl = grossPnl - closeComm
                val releasedMargin = pos.margin * tp1Ratio
                balance += (releasedMargin + pnl)
                pos.quantity -= closeQty
                pos.margin -= releasedMargin

                tradeHistory.add(0, PaperTradeRecord(
                    timestamp = System.currentTimeMillis(),
                    symbol = symbol,
                    action = "TP1_CLOSE",
                    price = currentPrice,
                    quantity = closeQty,
                    realizedPnl = pnl,
                    pnlPercent = ((currentPrice - pos.entryPrice) / pos.entryPrice) * 100.0 * pos.leverage,
                    note = "达到 1:3 盈亏比目标，分批止盈 50%"
                ))
                PaperTradingPrefs.saveTradeHistory(context, tradeHistory)
                PaperTradingPrefs.setBalance(context, balance)
                log("🎯 【第一止盈 TP1】突破 $currentPrice，平多 50%，锁定盈利 +$${String.format(Locale.US, "%.2f", pnl)}")
            }

            // 3. 动态移动止损跟踪
            val newTrailing = currentPrice - 2.0 * curAtr
            if (newTrailing > pos.trailingStop) {
                pos.trailingStop = newTrailing
            }

            // 4. 触及止损
            if (currentPrice <= pos.slPrice) {
                val reason = if (pos.isBeActivated && abs(pos.slPrice - pos.entryPrice) < 1e-4) "保本平仓离场" else "触及止损线"
                closeFullPosition(currentPrice, "SL_CLOSE", reason)
                return
            }

            // 5. 触及已生效的移动止损
            if (pos.isTp1Filled && pos.trailingStop > pos.entryPrice && currentPrice <= pos.trailingStop) {
                closeFullPosition(currentPrice, "TRAILING_CLOSE", "触及 ATR 动态追踪止盈线 ($${String.format(Locale.US, "%.2f", pos.trailingStop)})")
                return
            }

            // 6. 检查策略反转信号平仓
            val revSignal = checkReversalSignal(candles, pos.side)
            if (revSignal) {
                closeFullPosition(currentPrice, "SIGNAL_CLOSE", "策略指标转空反转平多")
                return
            }

        } else if (pos.side == "SHORT") {
            // 1. 检查触发保本 (1.0 R)
            if (!pos.isBeActivated && currentPrice <= pos.beTriggerPrice) {
                pos.isBeActivated = true
                pos.slPrice = kotlin.math.min(pos.slPrice, pos.entryPrice)
                log("🛡️ 【保本触发】价格跌至 1.0R ($currentPrice)，空单止损下移至开仓均价 $${pos.entryPrice}")
            }

            // 2. 检查第一止盈 (3.0 R，平仓 50%)
            if (!pos.isTp1Filled && currentPrice <= pos.tp1Price) {
                pos.isTp1Filled = true
                val closeQty = pos.quantity * tp1Ratio
                val grossPnl = (pos.entryPrice - currentPrice) * closeQty
                val closeComm = currentPrice * closeQty * commissionRate
                val pnl = grossPnl - closeComm
                val releasedMargin = pos.margin * tp1Ratio
                balance += (releasedMargin + pnl)
                pos.quantity -= closeQty
                pos.margin -= releasedMargin

                tradeHistory.add(0, PaperTradeRecord(
                    timestamp = System.currentTimeMillis(),
                    symbol = symbol,
                    action = "TP1_CLOSE",
                    price = currentPrice,
                    quantity = closeQty,
                    realizedPnl = pnl,
                    pnlPercent = ((pos.entryPrice - currentPrice) / pos.entryPrice) * 100.0 * pos.leverage,
                    note = "达到 1:3 盈亏比目标，分批止盈 50%"
                ))
                PaperTradingPrefs.saveTradeHistory(context, tradeHistory)
                PaperTradingPrefs.setBalance(context, balance)
                log("🎯 【第一止盈 TP1】跌至 $currentPrice，平空 50%，锁定盈利 +$${String.format(Locale.US, "%.2f", pnl)}")
            }

            // 3. 动态移动止损跟踪
            val newTrailing = currentPrice + 2.0 * curAtr
            if (pos.trailingStop == 0.0 || newTrailing < pos.trailingStop) {
                pos.trailingStop = newTrailing
            }

            // 4. 触及止损
            if (currentPrice >= pos.slPrice) {
                val reason = if (pos.isBeActivated && abs(pos.slPrice - pos.entryPrice) < 1e-4) "保本平仓离场" else "触及止损线"
                closeFullPosition(currentPrice, "SL_CLOSE", reason)
                return
            }

            // 5. 触及移动止损
            if (pos.isTp1Filled && pos.trailingStop > 0 && pos.trailingStop < pos.entryPrice && currentPrice >= pos.trailingStop) {
                closeFullPosition(currentPrice, "TRAILING_CLOSE", "触及 ATR 动态追踪止盈线 ($${String.format(Locale.US, "%.2f", pos.trailingStop)})")
                return
            }

            // 6. 检查策略反转信号平仓
            val revSignal = checkReversalSignal(candles, pos.side)
            if (revSignal) {
                closeFullPosition(currentPrice, "SIGNAL_CLOSE", "策略指标转多反转平空")
                return
            }
        }

        PaperTradingPrefs.setPosition(context, pos)
    }

    private fun evaluateNewEntry(symbol: String, currentPrice: Double, candles: List<Candle>) {
        if (balance <= 10.0) {
            log("⚠️ 模拟账户可用余额不足，请点击【重置资金】")
            return
        }

        val curAtr = calculateAtr(candles)

        // 判定开仓信号
        var openSide: String? = null
        var strategyDesc = ""

        when (strategyMode) {
            STRATEGY_3K -> {
                val result = Indicators.computeThreeK(candles)
                val lastSig = if (result.signals.isNotEmpty()) result.signals.last() else Indicators.ThreeKSignal.NONE
                if (lastSig == Indicators.ThreeKSignal.BUY) {
                    openSide = "LONG"
                    strategyDesc = "3K 趋势突破多头"
                } else if (lastSig == Indicators.ThreeKSignal.SELL) {
                    openSide = "SHORT"
                    strategyDesc = "3K 趋势突破空头"
                }
            }
            STRATEGY_LORENTZIAN -> {
                val result = Indicators.computeLorentzian(candles)
                val lastSig = if (result.signals.isNotEmpty()) result.signals.last() else Indicators.LorentzianSignal.NONE
                val pred = if (result.predictions.isNotEmpty()) result.predictions.last() else 0f
                if (lastSig == Indicators.LorentzianSignal.BUY && pred >= 2f) {
                    openSide = "LONG"
                    strategyDesc = "洛伦兹 ML 多 (得分 ${String.format(Locale.US, "%.0f", pred)})"
                } else if (lastSig == Indicators.LorentzianSignal.SELL && pred <= -2f) {
                    openSide = "SHORT"
                    strategyDesc = "洛伦兹 ML 空 (得分 ${String.format(Locale.US, "%.0f", pred)})"
                }
            }
            STRATEGY_EMA_CROSS -> {
                val emaFast = Indicators.computeEma(candles, 9)
                val emaSlow = Indicators.computeEma(candles, 21)
                val n = candles.size
                if (n >= 2 && emaFast[n - 1] > 0 && emaSlow[n - 1] > 0) {
                    val prevFast = emaFast[n - 2]
                    val prevSlow = emaSlow[n - 2]
                    val currFast = emaFast[n - 1]
                    val currSlow = emaSlow[n - 1]

                    if (prevFast <= prevSlow && currFast > currSlow) {
                        openSide = "LONG"
                        strategyDesc = "双均线金叉 (EMA 9/21)"
                    } else if (prevFast >= prevSlow && currFast < currSlow) {
                        openSide = "SHORT"
                        strategyDesc = "双均线死叉 (EMA 9/21)"
                    }
                }
            }
            STRATEGY_PINE -> {
                val scripts = PineScriptManager.getScripts(context)
                val script = scripts.find { it.id == selectedPineId } ?: scripts.firstOrNull { it.enabled }
                if (script != null) {
                    val execRes = PineEngine.execute(script, candles)
                    val shapes = execRes.plotShapes
                    val n = candles.size
                    for (sh in shapes) {
                        if (sh.indices.isNotEmpty() && sh.indices[n - 1]) {
                            if (sh.isBuy) {
                                openSide = "LONG"
                                strategyDesc = "Pine [${script.name}] 做多信号"
                            } else if (!script.spotOnly) {
                                openSide = "SHORT"
                                strategyDesc = "Pine [${script.name}] 做空信号"
                            }
                            break
                        }
                    }
                }
            }
        }

        if (openSide == null) return

        // 计算止损与目标位
        val slPrice: Double
        val stopDist: Double
        val bePrice: Double
        val tp1Price: Double
        val trailingInit: Double

        if (openSide == "LONG") {
            slPrice = currentPrice - 1.5 * curAtr
            stopDist = max(currentPrice - slPrice, currentPrice * 0.005)
            bePrice = currentPrice + stopDist * rrBreakeven
            tp1Price = currentPrice + stopDist * rrTakeProfit
            trailingInit = currentPrice - 2.0 * curAtr
        } else {
            slPrice = currentPrice + 1.5 * curAtr
            stopDist = max(slPrice - currentPrice, currentPrice * 0.005)
            bePrice = currentPrice - stopDist * rrBreakeven
            tp1Price = currentPrice - stopDist * rrTakeProfit
            trailingInit = currentPrice + 2.0 * curAtr
        }

        // 头寸大小计算: PositionSize = RiskCapital / StopDistance
        val riskCapital = balance * (riskPercent / 100.0)
        var quantity = riskCapital / stopDist
        var notional = quantity * currentPrice
        var margin = notional / leverage

        if (margin > balance * 0.9) {
            margin = balance * 0.9
            quantity = (margin * leverage) / currentPrice
        }

        if (quantity <= 0) return

        // 扣除保证金与开仓手续费
        val comm = currentPrice * quantity * commissionRate
        balance -= (margin + comm)

        val newPos = PaperPosition(
            symbol = symbol,
            side = openSide,
            quantity = quantity,
            entryPrice = currentPrice,
            slPrice = slPrice,
            tp1Price = tp1Price,
            beTriggerPrice = bePrice,
            trailingStop = trailingInit,
            margin = margin,
            leverage = leverage
        )

        position = newPos
        PaperTradingPrefs.setPosition(context, newPos)
        PaperTradingPrefs.setBalance(context, balance)

        tradeHistory.add(0, PaperTradeRecord(
            timestamp = System.currentTimeMillis(),
            symbol = symbol,
            action = "OPEN_$openSide",
            price = currentPrice,
            quantity = quantity,
            realizedPnl = -comm,
            pnlPercent = 0.0,
            note = "$strategyDesc | 止损: $${String.format(Locale.US, "%.2f", slPrice)}, TP1: $${String.format(Locale.US, "%.2f", tp1Price)}"
        ))
        PaperTradingPrefs.saveTradeHistory(context, tradeHistory)

        val icon = if (openSide == "LONG") "🟢" else "🔴"
        log("$icon 【建立 $openSide 模拟仓位】$strategyDesc | 开仓价: $${String.format(Locale.US, "%.2f", currentPrice)} | 数量: ${String.format(Locale.US, "%.4f", quantity)} | 止损: $${String.format(Locale.US, "%.2f", slPrice)} | 目标: $${String.format(Locale.US, "%.2f", tp1Price)}")
        notifyChange()
    }

    private fun checkReversalSignal(candles: List<Candle>, currentSide: String): Boolean {
        when (strategyMode) {
            STRATEGY_3K -> {
                val res = Indicators.computeThreeK(candles)
                val last = if (res.signals.isNotEmpty()) res.signals.last() else Indicators.ThreeKSignal.NONE
                return (currentSide == "LONG" && last == Indicators.ThreeKSignal.CLOSE_LONG) ||
                        (currentSide == "SHORT" && last == Indicators.ThreeKSignal.CLOSE_SHORT)
            }
            STRATEGY_LORENTZIAN -> {
                val res = Indicators.computeLorentzian(candles)
                val last = if (res.signals.isNotEmpty()) res.signals.last() else Indicators.LorentzianSignal.NONE
                return (currentSide == "LONG" && last == Indicators.LorentzianSignal.CLOSE_LONG) ||
                        (currentSide == "SHORT" && last == Indicators.LorentzianSignal.CLOSE_SHORT)
            }
            STRATEGY_EMA_CROSS -> {
                val emaFast = Indicators.computeEma(candles, 9)
                val emaSlow = Indicators.computeEma(candles, 21)
                val n = candles.size
                if (n >= 2 && emaFast[n - 1] > 0 && emaSlow[n - 1] > 0) {
                    if (currentSide == "LONG" && emaFast[n - 1] < emaSlow[n - 1]) return true
                    if (currentSide == "SHORT" && emaFast[n - 1] > emaSlow[n - 1]) return true
                }
            }
            STRATEGY_PINE -> {
                val scripts = PineScriptManager.getScripts(context)
                val script = scripts.find { it.id == selectedPineId } ?: scripts.firstOrNull { it.enabled }
                if (script != null) {
                    val execRes = PineEngine.execute(script, candles)
                    val shapes = execRes.plotShapes
                    val n = candles.size
                    for (sh in shapes) {
                        if (sh.indices.isNotEmpty() && sh.indices[n - 1]) {
                            if (currentSide == "LONG" && !sh.isBuy) return true
                            if (currentSide == "SHORT" && sh.isBuy) return true
                        }
                    }
                }
            }
        }
        return false
    }

    private fun closeFullPosition(currentPrice: Double, actionType: String, reason: String) {
        val pos = position ?: return

        val diff = if (pos.side == "LONG") {
            currentPrice - pos.entryPrice
        } else {
            pos.entryPrice - currentPrice
        }
        val grossPnl = diff * pos.quantity
        val comm = currentPrice * pos.quantity * commissionRate
        val netPnl = grossPnl - comm
        val pnlPct = (diff / max(pos.entryPrice, 1e-6)) * 100.0 * pos.leverage

        balance += (pos.margin + netPnl)
        position = null

        PaperTradingPrefs.setPosition(context, null)
        PaperTradingPrefs.setBalance(context, balance)

        tradeHistory.add(0, PaperTradeRecord(
            timestamp = System.currentTimeMillis(),
            symbol = pos.symbol,
            action = actionType,
            price = currentPrice,
            quantity = pos.quantity,
            realizedPnl = netPnl,
            pnlPercent = pnlPct,
            note = reason
        ))
        PaperTradingPrefs.saveTradeHistory(context, tradeHistory)

        val pnlTag = if (netPnl >= 0) "+$${String.format(Locale.US, "%.2f", netPnl)}" else "-$${String.format(Locale.US, "%.2f", abs(netPnl))}"
        val icon = if (netPnl >= 0) "✅" else "❌"
        log("$icon 【平仓完成】${pos.side} 全部平仓 @ $${String.format(Locale.US, "%.2f", currentPrice)} | 净盈亏: $pnlTag (${String.format(Locale.US, "%+.2f", pnlPct)}%) | 原因: $reason")
        notifyChange()
    }

    fun manualMarketClose(currentPrice: Double) {
        position?.let {
            closeFullPosition(currentPrice, "MANUAL_CLOSE", "用户手动市价全平")
        }
    }

    fun manualMoveToBreakeven() {
        val pos = position ?: return
        pos.isBeActivated = true
        pos.slPrice = pos.entryPrice
        PaperTradingPrefs.setPosition(context, pos)
        log("🛡️ 【手动保本】止损已手动拉平至开仓均价 $${String.format(Locale.US, "%.2f", pos.entryPrice)}")
        notifyChange()
    }

    fun resetAccount(initialBalance: Double = PaperTradingPrefs.DEFAULT_INITIAL_BALANCE) {
        balance = initialBalance
        position = null
        tradeHistory.clear()
        logs.clear()
        PaperTradingPrefs.resetAll(context, initialBalance)
        log("🔄 模拟账户已重置，初始本金: $${String.format(Locale.US, "%.2f", initialBalance)} USDT")
        notifyChange()
    }

    private fun calculateAtr(candles: List<Candle>): Double {
        if (candles.size < 15) return (candles.lastOrNull()?.close ?: 100.0) * 0.02
        val atrArr = Indicators.computeAtr(candles, 14)
        return if (atrArr.isNotEmpty()) atrArr.last().toDouble() else (candles.last().close * 0.02)
    }
}
