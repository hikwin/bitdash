package com.bitdash.app.market

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 回测单笔成交记录
 */
data class BacktestTrade(
    val entryTime: Long,
    val exitTime: Long,
    val symbol: String,
    val side: String,                   // "LONG" 或 "SHORT"
    val entryPrice: Double,
    val exitPrice: Double,
    val quantity: Double,
    val grossPnl: Double,
    val netPnl: Double,
    val pnlPercent: Double,
    val exitReason: String,             // "TP1_CLOSE", "SL_CLOSE", "TRAILING_CLOSE", "SIGNAL_CLOSE"
    val initialBalance: Double,
    val finalBalance: Double
)

/**
 * 完整策略历史回测绩效统计结果
 */
data class BacktestResult(
    val symbol: String,
    val timeframe: String,
    val strategyName: String,
    val initialBalance: Double,
    val finalBalance: Double,
    val totalProfit: Double,            // 净利润金额 ($) - 严格等于所有成交 netPnl 之和
    val totalProfitPct: Double,         // 净收益率 (%) = (totalProfit / initialBalance) * 100
    val totalTrades: Int,               // 总交易单数
    val winTrades: Int,                 // 盈利单数
    val lossTrades: Int,                // 亏损单数
    val winRate: Double,                // 胜率 (%)
    val profitFactor: Double,           // 盈亏比 (Gross Profit / Gross Loss)
    val maxDrawdownPct: Double,         // 最大回撤 (%)
    val trades: List<BacktestTrade>,    // 全部交易流水
    val candleCount: Int,               // 回测 K 线根数
    val executionTimeMs: Long           // 撮合耗时 (ms)
)

/**
 * 专业策略时序回测引擎 (严格消除未来函数，全真模拟撮合与资金流对账)
 */
object StrategyBacktester {

    fun runBacktest(
        candles: List<Candle>,
        symbol: String,
        timeframe: String,
        strategyMode: String,
        pineScript: PineScript? = null,
        initialBalance: Double = 10000.0,
        riskPercent: Double = 2.0,
        leverage: Int = 3,
        antiChopEnabled: Boolean = true,
        fixedTradeAmount: Double = 0.0
    ): BacktestResult {
        val startTime = System.currentTimeMillis()
        val n = candles.size

        if (n < 10) {
            return BacktestResult(
                symbol = symbol,
                timeframe = timeframe,
                strategyName = strategyMode,
                initialBalance = initialBalance,
                finalBalance = initialBalance,
                totalProfit = 0.0,
                totalProfitPct = 0.0,
                totalTrades = 0,
                winTrades = 0,
                lossTrades = 0,
                winRate = 0.0,
                profitFactor = 0.0,
                maxDrawdownPct = 0.0,
                trades = emptyList(),
                candleCount = n,
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        }

        val commissionRate = 0.0004 // 0.04% 手续费
        val rrBreakeven = 1.0       // 1:1 R 保本
        val rrTakeProfit = 3.0     // 1:3 R 第一止盈
        val tp1Ratio = 0.50        // TP1 平 50%

        // 1. 全量时序预计算指标 (严格向后看，无未来函数)
        val threeKRes = if (strategyMode == PaperTradingEngine.STRATEGY_3K) Indicators.computeThreeK(candles) else null
        val lorentzRes = if (strategyMode == PaperTradingEngine.STRATEGY_LORENTZIAN) Indicators.computeLorentzian(candles) else null
        val emaFast = if (strategyMode == PaperTradingEngine.STRATEGY_EMA_CROSS) Indicators.computeEma(candles, 9) else null
        val emaSlow = if (strategyMode == PaperTradingEngine.STRATEGY_EMA_CROSS) Indicators.computeEma(candles, 21) else null
        val pineRes = if (strategyMode == PaperTradingEngine.STRATEGY_PINE && pineScript != null) PineEngine.execute(pineScript, candles) else null
        val atr = Indicators.computeAtr(candles, 14)

        var availableBalance = initialBalance
        var peakEquity = initialBalance
        var maxDrawdown = 0.0

        var activePos: PaperPosition? = null
        val tradeList = mutableListOf<BacktestTrade>()

        val strategyDisplayName = when (strategyMode) {
            PaperTradingEngine.STRATEGY_3K -> "3K 趋势突破 (BTC 1D Alpha)"
            PaperTradingEngine.STRATEGY_LORENTZIAN -> "洛伦兹分类 (Machine Learning)"
            PaperTradingEngine.STRATEGY_EMA_CROSS -> "双均线交叉 (EMA 9/21)"
            PaperTradingEngine.STRATEGY_PINE -> "Pine [${pineScript?.name ?: "自定义"}]"
            else -> strategyMode
        }

        // 2. 逐根 K 线时序回测仿真
        val startIdx = min(n - 1, max(5, if (strategyMode == PaperTradingEngine.STRATEGY_3K) min(20, n / 2) else 5))

        for (i in startIdx until n) {
            val c = candles[i]
            val open = c.open
            val high = c.high
            val low = c.low
            val close = c.close
            val curAtr = if (atr.isNotEmpty() && i < atr.size) atr[i].toDouble() else (close * 0.02)

            // A. 如果持有仓位，先管理当前持仓生命周期
            if (activePos != null) {
                val pos = activePos!!

                if (pos.side == "LONG") {
                    // 同一根 K 线内止损与止盈竞争判决 (严谨保守原则)
                    val slHit = low <= pos.slPrice
                    val tpHit = !pos.isTp1Filled && high >= pos.tp1Price
                    val slFirst = slHit && (!tpHit || open <= pos.slPrice || abs(open - pos.slPrice) < abs(open - pos.tp1Price))

                    if (slFirst) {
                        // 1. 先触及止损离场
                        val exitPrice = pos.slPrice
                        val gross = (exitPrice - pos.entryPrice) * pos.quantity
                        val openCommPart = pos.entryPrice * pos.quantity * commissionRate
                        val closeComm = exitPrice * pos.quantity * commissionRate
                        val net = gross - closeComm - openCommPart

                        availableBalance += (pos.margin + gross - closeComm)

                        val reason = if (pos.isBeActivated && abs(pos.slPrice - pos.entryPrice) < 1e-4) "保本平仓离场" else "触及止损线"
                        tradeList.add(BacktestTrade(
                            entryTime = pos.openTime,
                            exitTime = c.ts,
                            symbol = symbol,
                            side = "LONG",
                            entryPrice = pos.entryPrice,
                            exitPrice = exitPrice,
                            quantity = pos.quantity,
                            grossPnl = gross,
                            netPnl = net,
                            pnlPercent = ((exitPrice - pos.entryPrice) / pos.entryPrice) * 100.0 * pos.leverage,
                            exitReason = reason,
                            initialBalance = initialBalance,
                            finalBalance = availableBalance
                        ))
                        activePos = null
                    } else {
                        // 2. 保本检查
                        if (!pos.isBeActivated && high >= pos.beTriggerPrice) {
                            pos.isBeActivated = true
                            pos.slPrice = max(pos.slPrice, pos.entryPrice)
                        }

                        // 3. 第一止盈检查
                        if (tpHit) {
                            pos.isTp1Filled = true
                            val fillPrice = pos.tp1Price
                            val closeQty = pos.quantity * tp1Ratio
                            val gross = (fillPrice - pos.entryPrice) * closeQty
                            val openCommPart = pos.entryPrice * closeQty * commissionRate
                            val closeComm = fillPrice * closeQty * commissionRate
                            val net = gross - closeComm - openCommPart

                            val releasedMargin = pos.margin * tp1Ratio
                            availableBalance += (releasedMargin + gross - closeComm)
                            pos.quantity -= closeQty
                            pos.margin -= releasedMargin

                            val curTotalEquity = availableBalance + pos.margin
                            tradeList.add(BacktestTrade(
                                entryTime = pos.openTime,
                                exitTime = c.ts,
                                symbol = symbol,
                                side = "LONG",
                                entryPrice = pos.entryPrice,
                                exitPrice = fillPrice,
                                quantity = closeQty,
                                grossPnl = gross,
                                netPnl = net,
                                pnlPercent = ((fillPrice - pos.entryPrice) / pos.entryPrice) * 100.0 * pos.leverage,
                                exitReason = "达到 1:3 盈亏比目标，分批止盈 50%",
                                initialBalance = initialBalance,
                                finalBalance = curTotalEquity
                            ))
                        }

                        // 4. 动态追踪止损跟踪
                        val newTrailing = close - 2.0 * curAtr
                        if (newTrailing > pos.trailingStop) {
                            pos.trailingStop = newTrailing
                        }

                        // 5. 剩余仓位检查追踪止损或后续止损
                        if (slHit) {
                            val exitPrice = pos.slPrice
                            val gross = (exitPrice - pos.entryPrice) * pos.quantity
                            val openCommPart = pos.entryPrice * pos.quantity * commissionRate
                            val closeComm = exitPrice * pos.quantity * commissionRate
                            val net = gross - closeComm - openCommPart

                            availableBalance += (pos.margin + gross - closeComm)

                            val reason = if (pos.isBeActivated && abs(pos.slPrice - pos.entryPrice) < 1e-4) "保本平仓离场" else "触及止损线"
                            tradeList.add(BacktestTrade(
                                entryTime = pos.openTime,
                                exitTime = c.ts,
                                symbol = symbol,
                                side = "LONG",
                                entryPrice = pos.entryPrice,
                                exitPrice = exitPrice,
                                quantity = pos.quantity,
                                grossPnl = gross,
                                netPnl = net,
                                pnlPercent = ((exitPrice - pos.entryPrice) / pos.entryPrice) * 100.0 * pos.leverage,
                                exitReason = reason,
                                initialBalance = initialBalance,
                                finalBalance = availableBalance
                            ))
                            activePos = null
                        } else if (pos.isTp1Filled && pos.trailingStop > pos.entryPrice && low <= pos.trailingStop) {
                            val exitPrice = pos.trailingStop
                            val gross = (exitPrice - pos.entryPrice) * pos.quantity
                            val openCommPart = pos.entryPrice * pos.quantity * commissionRate
                            val closeComm = exitPrice * pos.quantity * commissionRate
                            val net = gross - closeComm - openCommPart

                            availableBalance += (pos.margin + gross - closeComm)

                            tradeList.add(BacktestTrade(
                                entryTime = pos.openTime,
                                exitTime = c.ts,
                                symbol = symbol,
                                side = "LONG",
                                entryPrice = pos.entryPrice,
                                exitPrice = exitPrice,
                                quantity = pos.quantity,
                                grossPnl = gross,
                                netPnl = net,
                                pnlPercent = ((exitPrice - pos.entryPrice) / pos.entryPrice) * 100.0 * pos.leverage,
                                exitReason = "触及 ATR 动态追踪止盈线",
                                initialBalance = initialBalance,
                                finalBalance = availableBalance
                            ))
                            activePos = null
                        } else {
                            // 6. 检查反向信号平仓
                            val isRev = checkReversalAtBar(i, "LONG", strategyMode, threeKRes, lorentzRes, emaFast, emaSlow, pineRes)
                            if (isRev) {
                                val exitPrice = close
                                val gross = (exitPrice - pos.entryPrice) * pos.quantity
                                val openCommPart = pos.entryPrice * pos.quantity * commissionRate
                                val closeComm = exitPrice * pos.quantity * commissionRate
                                val net = gross - closeComm - openCommPart

                                availableBalance += (pos.margin + gross - closeComm)

                                tradeList.add(BacktestTrade(
                                    entryTime = pos.openTime,
                                    exitTime = c.ts,
                                    symbol = symbol,
                                    side = "LONG",
                                    entryPrice = pos.entryPrice,
                                    exitPrice = exitPrice,
                                    quantity = pos.quantity,
                                    grossPnl = gross,
                                    netPnl = net,
                                    pnlPercent = ((exitPrice - pos.entryPrice) / pos.entryPrice) * 100.0 * pos.leverage,
                                    exitReason = "策略指标转空反转平多",
                                    initialBalance = initialBalance,
                                    finalBalance = availableBalance
                                ))
                                activePos = null
                            }
                        }
                    }

                } else if (pos.side == "SHORT") {
                    // 同一根 K 线内止损与止盈竞争判决
                    val slHit = high >= pos.slPrice
                    val tpHit = !pos.isTp1Filled && low <= pos.tp1Price
                    val slFirst = slHit && (!tpHit || open >= pos.slPrice || abs(open - pos.slPrice) < abs(open - pos.tp1Price))

                    if (slFirst) {
                        // 1. 先触及止损
                        val exitPrice = pos.slPrice
                        val gross = (pos.entryPrice - exitPrice) * pos.quantity
                        val openCommPart = pos.entryPrice * pos.quantity * commissionRate
                        val closeComm = exitPrice * pos.quantity * commissionRate
                        val net = gross - closeComm - openCommPart

                        availableBalance += (pos.margin + gross - closeComm)

                        val reason = if (pos.isBeActivated && abs(pos.slPrice - pos.entryPrice) < 1e-4) "保本平仓离场" else "触及止损线"
                        tradeList.add(BacktestTrade(
                            entryTime = pos.openTime,
                            exitTime = c.ts,
                            symbol = symbol,
                            side = "SHORT",
                            entryPrice = pos.entryPrice,
                            exitPrice = exitPrice,
                            quantity = pos.quantity,
                            grossPnl = gross,
                            netPnl = net,
                            pnlPercent = ((pos.entryPrice - exitPrice) / pos.entryPrice) * 100.0 * pos.leverage,
                            exitReason = reason,
                            initialBalance = initialBalance,
                            finalBalance = availableBalance
                        ))
                        activePos = null
                    } else {
                        // 2. 保本检查
                        if (!pos.isBeActivated && low <= pos.beTriggerPrice) {
                            pos.isBeActivated = true
                            pos.slPrice = min(pos.slPrice, pos.entryPrice)
                        }

                        // 3. 第一止盈检查
                        if (tpHit) {
                            pos.isTp1Filled = true
                            val fillPrice = pos.tp1Price
                            val closeQty = pos.quantity * tp1Ratio
                            val gross = (pos.entryPrice - fillPrice) * closeQty
                            val openCommPart = pos.entryPrice * closeQty * commissionRate
                            val closeComm = fillPrice * closeQty * commissionRate
                            val net = gross - closeComm - openCommPart

                            val releasedMargin = pos.margin * tp1Ratio
                            availableBalance += (releasedMargin + gross - closeComm)
                            pos.quantity -= closeQty
                            pos.margin -= releasedMargin

                            val curTotalEquity = availableBalance + pos.margin
                            tradeList.add(BacktestTrade(
                                entryTime = pos.openTime,
                                exitTime = c.ts,
                                symbol = symbol,
                                side = "SHORT",
                                entryPrice = pos.entryPrice,
                                exitPrice = fillPrice,
                                quantity = closeQty,
                                grossPnl = gross,
                                netPnl = net,
                                pnlPercent = ((pos.entryPrice - fillPrice) / pos.entryPrice) * 100.0 * pos.leverage,
                                exitReason = "达到 1:3 盈亏比目标，分批止盈 50%",
                                initialBalance = initialBalance,
                                finalBalance = curTotalEquity
                            ))
                        }

                        // 4. 动态追踪止损跟踪
                        val newTrailing = close + 2.0 * curAtr
                        if (pos.trailingStop == 0.0 || newTrailing < pos.trailingStop) {
                            pos.trailingStop = newTrailing
                        }

                        // 5. 剩余仓位检查止损或追踪
                        if (slHit) {
                            val exitPrice = pos.slPrice
                            val gross = (pos.entryPrice - exitPrice) * pos.quantity
                            val openCommPart = pos.entryPrice * pos.quantity * commissionRate
                            val closeComm = exitPrice * pos.quantity * commissionRate
                            val net = gross - closeComm - openCommPart

                            availableBalance += (pos.margin + gross - closeComm)

                            val reason = if (pos.isBeActivated && abs(pos.slPrice - pos.entryPrice) < 1e-4) "保本平仓离场" else "触及止损线"
                            tradeList.add(BacktestTrade(
                                entryTime = pos.openTime,
                                exitTime = c.ts,
                                symbol = symbol,
                                side = "SHORT",
                                entryPrice = pos.entryPrice,
                                exitPrice = exitPrice,
                                quantity = pos.quantity,
                                grossPnl = gross,
                                netPnl = net,
                                pnlPercent = ((pos.entryPrice - exitPrice) / pos.entryPrice) * 100.0 * pos.leverage,
                                exitReason = reason,
                                initialBalance = initialBalance,
                                finalBalance = availableBalance
                            ))
                            activePos = null
                        } else if (pos.isTp1Filled && pos.trailingStop > 0 && pos.trailingStop < pos.entryPrice && high >= pos.trailingStop) {
                            val exitPrice = pos.trailingStop
                            val gross = (pos.entryPrice - exitPrice) * pos.quantity
                            val openCommPart = pos.entryPrice * pos.quantity * commissionRate
                            val closeComm = exitPrice * pos.quantity * commissionRate
                            val net = gross - closeComm - openCommPart

                            availableBalance += (pos.margin + gross - closeComm)

                            tradeList.add(BacktestTrade(
                                entryTime = pos.openTime,
                                exitTime = c.ts,
                                symbol = symbol,
                                side = "SHORT",
                                entryPrice = pos.entryPrice,
                                exitPrice = exitPrice,
                                quantity = pos.quantity,
                                grossPnl = gross,
                                netPnl = net,
                                pnlPercent = ((pos.entryPrice - exitPrice) / pos.entryPrice) * 100.0 * pos.leverage,
                                exitReason = "触及 ATR 动态追踪止盈线",
                                initialBalance = initialBalance,
                                finalBalance = availableBalance
                            ))
                            activePos = null
                        } else {
                            // 6. 检查反向信号平仓
                            val isRev = checkReversalAtBar(i, "SHORT", strategyMode, threeKRes, lorentzRes, emaFast, emaSlow, pineRes)
                            if (isRev) {
                                val exitPrice = close
                                val gross = (pos.entryPrice - exitPrice) * pos.quantity
                                val openCommPart = pos.entryPrice * pos.quantity * commissionRate
                                val closeComm = exitPrice * pos.quantity * commissionRate
                                val net = gross - closeComm - openCommPart

                                availableBalance += (pos.margin + gross - closeComm)

                                tradeList.add(BacktestTrade(
                                    entryTime = pos.openTime,
                                    exitTime = c.ts,
                                    symbol = symbol,
                                    side = "SHORT",
                                    entryPrice = pos.entryPrice,
                                    exitPrice = exitPrice,
                                    quantity = pos.quantity,
                                    grossPnl = gross,
                                    netPnl = net,
                                    pnlPercent = ((pos.entryPrice - exitPrice) / pos.entryPrice) * 100.0 * pos.leverage,
                                    exitReason = "策略指标转多反转平空",
                                    initialBalance = initialBalance,
                                    finalBalance = availableBalance
                                ))
                                activePos = null
                            }
                        }
                    }
                }
            }

            // 当前瞬时总权益与最大回撤统计
            val currentEquity = availableBalance + (activePos?.let { pos ->
                val curDiff = if (pos.side == "LONG") close - pos.entryPrice else pos.entryPrice - close
                pos.margin + curDiff * pos.quantity
            } ?: 0.0)

            if (currentEquity > peakEquity) {
                peakEquity = currentEquity
            }
            val curDd = if (peakEquity > 0) ((peakEquity - currentEquity) / peakEquity) * 100.0 else 0.0
            if (curDd > maxDrawdown) {
                maxDrawdown = curDd
            }

            // B. 如果当前无持仓，评估开仓信号 (严格根据收盘指标开仓)
            if (activePos == null && availableBalance > 20.0) {
                // Anti-Chop 窄幅横盘过滤
                val isChop = antiChopEnabled && (curAtr / close < 0.003)
                if (!isChop) {
                    val openSide = checkEntrySignalAtBar(i, strategyMode, threeKRes, lorentzRes, emaFast, emaSlow, pineRes, pineScript)
                    if (openSide != null) {
                        val entryPrice = close
                        val slPrice: Double
                        val stopDist: Double
                        val bePrice: Double
                        val tp1Price: Double
                        val trailingInit: Double

                        if (openSide == "LONG") {
                            slPrice = entryPrice - 1.5 * curAtr
                            stopDist = max(entryPrice - slPrice, entryPrice * 0.005)
                            bePrice = entryPrice + stopDist * rrBreakeven
                            tp1Price = entryPrice + stopDist * rrTakeProfit
                            trailingInit = entryPrice - 2.0 * curAtr
                        } else {
                            slPrice = entryPrice + 1.5 * curAtr
                            stopDist = max(slPrice - entryPrice, entryPrice * 0.005)
                            bePrice = entryPrice - stopDist * rrBreakeven
                            tp1Price = entryPrice - stopDist * rrTakeProfit
                            trailingInit = entryPrice + 2.0 * curAtr
                        }

                        val notional: Double
                        val margin: Double
                        val quantity: Double

                        if (fixedTradeAmount > 0.0) {
                            val maxAvailableNotional = availableBalance * leverage * 0.95
                            val targetNotional = min(fixedTradeAmount, maxAvailableNotional)
                            quantity = targetNotional / entryPrice
                            notional = quantity * entryPrice
                            margin = notional / leverage
                        } else {
                            val riskCapital = availableBalance * (riskPercent / 100.0)
                            var q = riskCapital / stopDist
                            var nNotional = q * entryPrice
                            var mMargin = nNotional / leverage

                            if (mMargin > availableBalance * 0.9) {
                                mMargin = availableBalance * 0.9
                                q = (mMargin * leverage) / entryPrice
                                nNotional = q * entryPrice
                            }
                            quantity = q
                            notional = nNotional
                            margin = mMargin
                        }

                        if (quantity > 0) {
                            val openComm = entryPrice * quantity * commissionRate
                            availableBalance -= (margin + openComm)

                            activePos = PaperPosition(
                                symbol = symbol,
                                side = openSide,
                                quantity = quantity,
                                entryPrice = entryPrice,
                                slPrice = slPrice,
                                tp1Price = tp1Price,
                                beTriggerPrice = bePrice,
                                trailingStop = trailingInit,
                                margin = margin,
                                leverage = leverage,
                                openTime = c.ts
                            )
                        }
                    }
                }
            }
        }

        // C. 如果回测结束时还有持仓，按最后一根 K 线收盘价结算
        activePos?.let { pos ->
            val lastClose = candles.last().close
            val gross = if (pos.side == "LONG") (lastClose - pos.entryPrice) * pos.quantity else (pos.entryPrice - lastClose) * pos.quantity
            val openCommPart = pos.entryPrice * pos.quantity * commissionRate
            val closeComm = lastClose * pos.quantity * commissionRate
            val net = gross - closeComm - openCommPart
            availableBalance += (pos.margin + gross - closeComm)

            tradeList.add(BacktestTrade(
                entryTime = pos.openTime,
                exitTime = candles.last().ts,
                symbol = symbol,
                side = pos.side,
                entryPrice = pos.entryPrice,
                exitPrice = lastClose,
                quantity = pos.quantity,
                grossPnl = gross,
                netPnl = net,
                pnlPercent = ((if (pos.side == "LONG") (lastClose - pos.entryPrice) else (pos.entryPrice - lastClose)) / pos.entryPrice) * 100.0 * pos.leverage,
                exitReason = "回测区间结束按现价结算",
                initialBalance = initialBalance,
                finalBalance = availableBalance
            ))
            activePos = null
        }

        val totalProfit = tradeList.sumOf { it.netPnl }
        val totalProfitPct = if (initialBalance > 0) (totalProfit / initialBalance) * 100.0 else 0.0
        val finalBalance = initialBalance + totalProfit
        val totalTrades = tradeList.size
        val winTrades = tradeList.count { it.netPnl > 0 }
        val lossTrades = tradeList.count { it.netPnl <= 0 }
        val winRate = if (totalTrades > 0) (winTrades.toDouble() / totalTrades) * 100.0 else 0.0

        val grossProfitSum = tradeList.filter { it.netPnl > 0 }.sumOf { it.netPnl }
        val grossLossSum = tradeList.filter { it.netPnl <= 0 }.sumOf { abs(it.netPnl) }
        val profitFactor = if (grossLossSum > 0) grossProfitSum / grossLossSum else (if (grossProfitSum > 0) 99.9 else 0.0)

        return BacktestResult(
            symbol = symbol,
            timeframe = timeframe,
            strategyName = strategyDisplayName,
            initialBalance = initialBalance,
            finalBalance = finalBalance,
            totalProfit = totalProfit,
            totalProfitPct = totalProfitPct,
            totalTrades = totalTrades,
            winTrades = winTrades,
            lossTrades = lossTrades,
            winRate = winRate,
            profitFactor = profitFactor,
            maxDrawdownPct = maxDrawdown,
            trades = tradeList.reversed(),
            candleCount = n,
            executionTimeMs = System.currentTimeMillis() - startTime
        )
    }

    private fun checkEntrySignalAtBar(
        i: Int,
        strategyMode: String,
        threeK: Indicators.ThreeKResult?,
        lorentz: Indicators.LorentzianResult?,
        emaFast: FloatArray?,
        emaSlow: FloatArray?,
        pineRes: PineExecutionResult?,
        pineScript: PineScript?
    ): String? {
        when (strategyMode) {
            PaperTradingEngine.STRATEGY_3K -> {
                if (threeK != null && i < threeK.signals.size) {
                    val sig = threeK.signals[i]
                    if (sig == Indicators.ThreeKSignal.BUY) return "LONG"
                    if (sig == Indicators.ThreeKSignal.SELL) return "SHORT"
                }
            }
            PaperTradingEngine.STRATEGY_LORENTZIAN -> {
                if (lorentz != null && i < lorentz.signals.size && i < lorentz.predictions.size) {
                    val sig = lorentz.signals[i]
                    val pred = lorentz.predictions[i]
                    if (sig == Indicators.LorentzianSignal.BUY && pred >= 2f) return "LONG"
                    if (sig == Indicators.LorentzianSignal.SELL && pred <= -2f) return "SHORT"
                }
            }
            PaperTradingEngine.STRATEGY_EMA_CROSS -> {
                if (emaFast != null && emaSlow != null && i >= 1 && i < emaFast.size) {
                    val prevF = emaFast[i - 1]
                    val prevS = emaSlow[i - 1]
                    val curF = emaFast[i]
                    val curS = emaSlow[i]
                    if (prevF <= prevS && curF > curS) return "LONG"
                    if (prevF >= prevS && curF < curS) return "SHORT"
                }
            }
            PaperTradingEngine.STRATEGY_PINE -> {
                if (pineRes != null) {
                    for (sh in pineRes.plotShapes) {
                        if (i < sh.indices.size && sh.indices[i]) {
                            if (sh.isBuy) return "LONG"
                            if (pineScript?.spotOnly != true) return "SHORT"
                        }
                    }
                }
            }
        }
        return null
    }

    private fun checkReversalAtBar(
        i: Int,
        currentSide: String,
        strategyMode: String,
        threeK: Indicators.ThreeKResult?,
        lorentz: Indicators.LorentzianResult?,
        emaFast: FloatArray?,
        emaSlow: FloatArray?,
        pineRes: PineExecutionResult?
    ): Boolean {
        when (strategyMode) {
            PaperTradingEngine.STRATEGY_3K -> {
                if (threeK != null && i < threeK.signals.size) {
                    val sig = threeK.signals[i]
                    if (currentSide == "LONG" && sig == Indicators.ThreeKSignal.CLOSE_LONG) return true
                    if (currentSide == "SHORT" && sig == Indicators.ThreeKSignal.CLOSE_SHORT) return true
                }
            }
            PaperTradingEngine.STRATEGY_LORENTZIAN -> {
                if (lorentz != null && i < lorentz.signals.size) {
                    val sig = lorentz.signals[i]
                    if (currentSide == "LONG" && sig == Indicators.LorentzianSignal.CLOSE_LONG) return true
                    if (currentSide == "SHORT" && sig == Indicators.LorentzianSignal.CLOSE_SHORT) return true
                }
            }
            PaperTradingEngine.STRATEGY_EMA_CROSS -> {
                if (emaFast != null && emaSlow != null && i < emaFast.size) {
                    if (currentSide == "LONG" && emaFast[i] < emaSlow[i]) return true
                    if (currentSide == "SHORT" && emaFast[i] > emaSlow[i]) return true
                }
            }
            PaperTradingEngine.STRATEGY_PINE -> {
                if (pineRes != null) {
                    for (sh in pineRes.plotShapes) {
                        if (i < sh.indices.size && sh.indices[i]) {
                            if (currentSide == "LONG" && !sh.isBuy) return true
                            if (currentSide == "SHORT" && sh.isBuy) return true
                        }
                    }
                }
            }
        }
        return false
    }
}
