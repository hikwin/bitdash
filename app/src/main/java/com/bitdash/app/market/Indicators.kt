package com.bitdash.app.market

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 专业 K 线技术指标计算工具类。
 *
 * 包含：
 * - MA (移动平均线)
 * - BOLL (布林带：中轨、上轨、下轨)
 * - MACD (指数平滑异同移动平均线：DIF, DEA, MACD 柱)
 * - RSI (相对强弱指标)
 * - KDJ (随机指标：K, D, J)
 */
object Indicators {

    data class BollResult(
        val mid: FloatArray,
        val up: FloatArray,
        val dn: FloatArray
    )

    data class MacdResult(
        val dif: FloatArray,
        val dea: FloatArray,
        val macd: FloatArray
    )

    data class KdjResult(
        val k: FloatArray,
        val d: FloatArray,
        val j: FloatArray
    )

    data class TurtleResult(
        val upper: FloatArray,     // 进场上轨（唐奇安通道上轨）
        val lower: FloatArray,     // 进场下轨（唐奇安通道下轨）
        val exitLong: FloatArray,  // 多头平仓下轨（如 10 日最低）
        val exitShort: FloatArray, // 空头平仓上轨（如 10 日最高）
        val atr: FloatArray        // 海龟真实波幅 N 值
    )

    data class SuperTrendResult(
        val trend: IntArray,       // 1 = 多头/上升趋势, -1 = 空头/下降趋势
        val value: FloatArray,     // 当前超级趋势线数值
        val upperBand: FloatArray, // 上轨阻力
        val lowerBand: FloatArray  // 下轨支撑
    )

    enum class ThreeKSignal {
        NONE,
        BUY,         // 做多入场
        SELL,        // 做空入场
        CLOSE_LONG,  // 平多离场
        CLOSE_SHORT  // 平空离场
    }

    data class ThreeKResult(
        val emaTrend: FloatArray,        // EMA 趋势中枢线 (如 50 EMA)
        val upperDonch: FloatArray,      // 唐奇安突破上轨 (如 40 周期高点)
        val lowerDonch: FloatArray,      // 唐奇安突破下轨 (如 40 周期低点)
        val trailStop: FloatArray,       // 当前生效的动态追踪止损线
        val positions: IntArray,         // 1 = 持多, -1 = 持空, 0 = 空仓
        val signals: Array<ThreeKSignal> // 交易信号
    )

    enum class BollBreakSignal {
        NONE,
        BUY,         // 突破上轨做多
        SELL,        // 跌破下轨做空
        CLOSE_LONG,  // 跌破上轨平多
        CLOSE_SHORT  // 突破下轨平空
    }

    data class BollBreakResult(
        val mid: FloatArray,             // 布林中轨 SMA(N)
        val top: FloatArray,             // 布林上轨
        val bottom: FloatArray,          // 布林下轨
        val maFilter: FloatArray,        // 趋势过滤均线 SMA(MA_LEN)
        val positions: IntArray,         // 1 = 持多, -1 = 持空, 0 = 空仓
        val signals: Array<BollBreakSignal> // 交易信号
    )

    enum class LorentzianSignal {
        NONE,
        BUY,         // 洛伦兹 ML 预测做多入场
        SELL,        // 洛伦兹 ML 预测做空入场
        CLOSE_LONG,  // 平多
        CLOSE_SHORT  // 平空
    }

    data class LorentzianResult(
        val kernelEstimate: FloatArray, // Nadaraya-Watson 核回归平滑估计中枢线
        val predictions: FloatArray,    // ML 分类预测得分 (-8 ~ +8)
        val stopLoss: FloatArray,       // 动态 ATR 止损线
        val positions: IntArray,        // 1 = 持多, -1 = 持空, 0 = 空仓
        val signals: Array<LorentzianSignal> // 交易信号
    )

    data class FibLevel(
        val ratio: Double,
        val label: String,
        val price: Double
    )

    data class FibResult(
        val highPrice: Double,
        val lowPrice: Double,
        val isUpTrend: Boolean,
        val levels: List<FibLevel>
    )

    /**
     * 计算单条简单移动平均线 (SMA)
     * 未定义区域（数据不足 period 根）填充 0f
     */
    fun computeMa(candles: List<Candle>, period: Int): FloatArray {
        val n = candles.size
        val out = FloatArray(n)
        if (n == 0 || period <= 0) return out

        var sum = 0.0
        for (i in 0 until n) {
            sum += candles[i].close
            if (i >= period) {
                sum -= candles[i - period].close
            }
            if (i >= period - 1) {
                out[i] = (sum / period).toFloat()
            }
        }
        return out
    }

    /**
     * 计算布林带 (BOLL)
     * - MID = N 日移动平均线
     * - UP = MID + K * 标准差
     * - DN = MID - K * 标准差
     */
    fun computeBoll(candles: List<Candle>, nPeriod: Int = 20, kMultiplier: Double = 2.0): BollResult {
        val size = candles.size
        val mid = FloatArray(size)
        val up = FloatArray(size)
        val dn = FloatArray(size)
        if (size == 0 || nPeriod <= 0) return BollResult(mid, up, dn)

        val ma = computeMa(candles, nPeriod)
        for (i in (nPeriod - 1) until size) {
            val avg = ma[i].toDouble()
            var sumSqDiff = 0.0
            for (j in (i - nPeriod + 1)..i) {
                val diff = candles[j].close - avg
                sumSqDiff += diff * diff
            }
            val stdDev = sqrt(sumSqDiff / nPeriod)
            mid[i] = avg.toFloat()
            up[i] = (avg + kMultiplier * stdDev).toFloat()
            dn[i] = (avg - kMultiplier * stdDev).toFloat()
        }
        return BollResult(mid, up, dn)
    }

    /**
     * 计算 MACD
     * - EMA(fast), EMA(slow)
     * - DIF = EMA(fast) - EMA(slow)
     * - DEA = EMA(DIF, signal)
     * - MACD = (DIF - DEA) * 2
     */
    fun computeMacd(
        candles: List<Candle>,
        fastPeriod: Int = 12,
        slowPeriod: Int = 26,
        signalPeriod: Int = 9
    ): MacdResult {
        val n = candles.size
        val dif = FloatArray(n)
        val dea = FloatArray(n)
        val macd = FloatArray(n)
        if (n == 0) return MacdResult(dif, dea, macd)

        val fastEma = FloatArray(n)
        val slowEma = FloatArray(n)

        val fastAlpha = 2.0 / (fastPeriod + 1)
        val slowAlpha = 2.0 / (slowPeriod + 1)
        val signalAlpha = 2.0 / (signalPeriod + 1)

        var curFast = candles[0].close
        var curSlow = candles[0].close
        fastEma[0] = curFast.toFloat()
        slowEma[0] = curSlow.toFloat()
        dif[0] = (curFast - curSlow).toFloat()

        for (i in 1 until n) {
            val c = candles[i].close
            curFast = curFast + fastAlpha * (c - curFast)
            curSlow = curSlow + slowAlpha * (c - curSlow)
            fastEma[i] = curFast.toFloat()
            slowEma[i] = curSlow.toFloat()
            dif[i] = (curFast - curSlow).toFloat()
        }

        var curDea = dif[0].toDouble()
        dea[0] = curDea.toFloat()
        macd[0] = ((dif[0] - dea[0]) * 2f)

        for (i in 1 until n) {
            curDea = curDea + signalAlpha * (dif[i] - curDea)
            dea[i] = curDea.toFloat()
            macd[i] = ((dif[i] - dea[i]) * 2f)
        }

        return MacdResult(dif, dea, macd)
    }

    /**
     * 计算 RSI (相对强弱指标)
     * 常用 6, 12, 24
     */
    fun computeRsi(candles: List<Candle>, period: Int = 14): FloatArray {
        val n = candles.size
        val out = FloatArray(n)
        if (n <= 1 || period <= 0) return out

        var sumGain = 0.0
        var sumLoss = 0.0

        for (i in 1..min(period, n - 1)) {
            val change = candles[i].close - candles[i - 1].close
            if (change > 0) sumGain += change else sumLoss += -change
        }

        if (n > period) {
            var avgGain = sumGain / period
            var avgLoss = sumLoss / period
            out[period] = if (avgLoss == 0.0) 100f else (100.0 - (100.0 / (1.0 + avgGain / avgLoss))).toFloat()

            for (i in (period + 1) until n) {
                val change = candles[i].close - candles[i - 1].close
                val gain = if (change > 0) change else 0.0
                val loss = if (change < 0) -change else 0.0
                avgGain = (avgGain * (period - 1) + gain) / period
                avgLoss = (avgLoss * (period - 1) + loss) / period
                out[i] = if (avgLoss == 0.0) 100f else (100.0 - (100.0 / (1.0 + avgGain / avgLoss))).toFloat()
            }
        }
        return out
    }

    /**
     * 计算 KDJ (随机指标)
     * - RSV = (Close - Low_N) / (High_N - Low_N) * 100
     * - K = (M1 - 1)/M1 * K_prev + 1/M1 * RSV
     * - D = (M2 - 1)/M2 * D_prev + 1/M2 * K
     * - J = 3*K - 2*D
     */
    fun computeKdj(
        candles: List<Candle>,
        nPeriod: Int = 9,
        m1: Int = 3,
        m2: Int = 3
    ): KdjResult {
        val n = candles.size
        val kArr = FloatArray(n)
        val dArr = FloatArray(n)
        val jArr = FloatArray(n)
        if (n == 0) return KdjResult(kArr, dArr, jArr)

        var lastK = 50.0
        var lastD = 50.0

        for (i in 0 until n) {
            val start = max(0, i - nPeriod + 1)
            var lowest = Double.MAX_VALUE
            var highest = -Double.MAX_VALUE
            for (idx in start..i) {
                if (candles[idx].low < lowest) lowest = candles[idx].low
                if (candles[idx].high > highest) highest = candles[idx].high
            }

            val rsv = if (highest > lowest) {
                (candles[i].close - lowest) / (highest - lowest) * 100.0
            } else {
                50.0
            }

            val curK = (m1 - 1.0) / m1 * lastK + 1.0 / m1 * rsv
            val curD = (m2 - 1.0) / m2 * lastD + 1.0 / m2 * curK
            val curJ = 3.0 * curK - 2.0 * curD

            kArr[i] = curK.toFloat()
            dArr[i] = curD.toFloat()
            jArr[i] = curJ.toFloat()

            lastK = curK
            lastD = curD
        }

        return KdjResult(kArr, dArr, jArr)
    }

    /**
     * 计算海龟交易法（唐奇安通道 + 离场线 + ATR / N 值）
     * - entryPeriod: 进场通道周期（默认 20）
     * - exitPeriod: 离场通道周期（默认 10）
     * - atrPeriod: ATR 波动率平滑周期（默认 20）
     */
    fun computeTurtle(
        candles: List<Candle>,
        entryPeriod: Int = 20,
        exitPeriod: Int = 10,
        atrPeriod: Int = 20
    ): TurtleResult {
        val n = candles.size
        val upper = FloatArray(n)
        val lower = FloatArray(n)
        val exitLong = FloatArray(n)
        val exitShort = FloatArray(n)
        val atr = FloatArray(n)
        if (n == 0) return TurtleResult(upper, lower, exitLong, exitShort, atr)

        // 1. 唐奇安进场与离场通道计算（基于历史极值）
        for (i in 0 until n) {
            // 进场上轨与下轨 (过去 entryPeriod 根蜡烛极值)
            val entryStart = max(0, i - entryPeriod)
            val entryEnd = max(0, i - 1)
            var maxHigh = -Double.MAX_VALUE
            var minLow = Double.MAX_VALUE
            for (idx in entryStart..entryEnd) {
                if (candles[idx].high > maxHigh) maxHigh = candles[idx].high
                if (candles[idx].low < minLow) minLow = candles[idx].low
            }
            if (i >= entryPeriod && maxHigh > -Double.MAX_VALUE && minLow < Double.MAX_VALUE) {
                upper[i] = maxHigh.toFloat()
                lower[i] = minLow.toFloat()
            }

            // 离场上轨与下轨 (过去 exitPeriod 根蜡烛极值)
            val exitStart = max(0, i - exitPeriod)
            val exitEnd = max(0, i - 1)
            var exitMaxHigh = -Double.MAX_VALUE
            var exitMinLow = Double.MAX_VALUE
            for (idx in exitStart..exitEnd) {
                if (candles[idx].high > exitMaxHigh) exitMaxHigh = candles[idx].high
                if (candles[idx].low < exitMinLow) exitMinLow = candles[idx].low
            }
            if (i >= exitPeriod && exitMaxHigh > -Double.MAX_VALUE && exitMinLow < Double.MAX_VALUE) {
                exitShort[i] = exitMaxHigh.toFloat()
                exitLong[i] = exitMinLow.toFloat()
            }
        }

        // 2. 海龟 ATR (N 值) 指数平滑计算
        var curN = 0.0
        for (i in 0 until n) {
            val c = candles[i]
            val tr = if (i == 0) {
                c.high - c.low
            } else {
                val prevClose = candles[i - 1].close
                max(c.high - c.low, max(abs(c.high - prevClose), abs(c.low - prevClose)))
            }
            if (i < atrPeriod) {
                curN = if (i == 0) tr else (curN * i + tr) / (i + 1)
            } else {
                curN = (curN * (atrPeriod - 1) + tr) / atrPeriod
            }
            atr[i] = curN.toFloat()
        }

        return TurtleResult(upper, lower, exitLong, exitShort, atr)
    }

    /**
     * 计算指数移动平均线 (EMA)
     * EMA_t = Price_t * alpha + EMA_{t-1} * (1 - alpha), 其中 alpha = 2 / (period + 1)
     */
    fun computeEma(candles: List<Candle>, period: Int): FloatArray {
        val n = candles.size
        val out = FloatArray(n)
        if (n == 0 || period <= 0) return out

        val alpha = 2.0 / (period + 1.0)
        var ema = 0.0

        for (i in 0 until n) {
            val close = candles[i].close
            if (i == 0) {
                ema = close
            } else {
                ema = close * alpha + ema * (1.0 - alpha)
            }
            if (i >= period - 1) {
                out[i] = ema.toFloat()
            }
        }
        return out
    }

    /**
     * 计算 SuperTrend（超级趋势指标）
     * - atrPeriod: ATR 周期（默认 10）
     * - factor: ATR 乘数因子（默认 3.0）
     */
    fun computeSuperTrend(
        candles: List<Candle>,
        atrPeriod: Int = 10,
        factor: Double = 3.0
    ): SuperTrendResult {
        val n = candles.size
        val trend = IntArray(n)
        val value = FloatArray(n)
        val upperBand = FloatArray(n)
        val lowerBand = FloatArray(n)
        if (n == 0) return SuperTrendResult(trend, value, upperBand, lowerBand)

        // 1. 计算 ATR
        val atr = FloatArray(n)
        var curAtr = 0.0
        for (i in 0 until n) {
            val c = candles[i]
            val tr = if (i == 0) {
                c.high - c.low
            } else {
                val prevClose = candles[i - 1].close
                max(c.high - c.low, max(abs(c.high - prevClose), abs(c.low - prevClose)))
            }
            curAtr = if (i == 0) tr else (curAtr * (atrPeriod - 1) + tr) / atrPeriod
            atr[i] = curAtr.toFloat()
        }

        // 2. 迭代计算 SuperTrend
        var prevFinalUpper = 0.0
        var prevFinalLower = 0.0
        var prevTrend = 1 // 1 = 多头, -1 = 空头

        for (i in 0 until n) {
            val c = candles[i]
            val hl2 = (c.high + c.low) / 2.0
            val curAtrVal = atr[i].toDouble()

            val basicUpper = hl2 + factor * curAtrVal
            val basicLower = hl2 - factor * curAtrVal

            var finalUpper = basicUpper
            var finalLower = basicLower

            if (i > 0) {
                val prevClose = candles[i - 1].close
                finalUpper = if (basicUpper < prevFinalUpper || prevClose > prevFinalUpper) basicUpper else prevFinalUpper
                finalLower = if (basicLower > prevFinalLower || prevClose < prevFinalLower) basicLower else prevFinalLower
            }

            val curTrend: Int
            if (i == 0) {
                curTrend = 1
            } else {
                curTrend = when {
                    prevTrend == 1 && c.close < prevFinalLower -> -1
                    prevTrend == -1 && c.close > prevFinalUpper -> 1
                    else -> prevTrend
                }
            }

            trend[i] = curTrend
            upperBand[i] = finalUpper.toFloat()
            lowerBand[i] = finalLower.toFloat()
            value[i] = (if (curTrend == 1) finalLower else finalUpper).toFloat()

            prevFinalUpper = finalUpper
            prevFinalLower = finalLower
            prevTrend = curTrend
        }

        return SuperTrendResult(trend, value, upperBand, lowerBand)
    }

    /**
     * 计算自动斐波那契回调波段 (Auto Fibonacci Retracement)
     */
    fun computeFibonacci(candles: List<Candle>, startIndex: Int, endIndex: Int): FibResult? {
        val s = startIndex.coerceAtLeast(0)
        val e = endIndex.coerceAtMost(candles.size - 1)
        if (s >= e || candles.isEmpty()) return null

        var maxHigh = -Double.MAX_VALUE
        var minLow = Double.MAX_VALUE
        var highIdx = s
        var lowIdx = s

        for (i in s..e) {
            val c = candles[i]
            if (c.high > maxHigh) {
                maxHigh = c.high
                highIdx = i
            }
            if (c.low < minLow) {
                minLow = c.low
                lowIdx = i
            }
        }

        if (maxHigh <= minLow) return null

        val isUpTrend = lowIdx <= highIdx // 先出现低点后出现高点
        val diff = maxHigh - minLow

        val ratios = listOf(
            0.0 to "0.0%",
            0.236 to "23.6%",
            0.382 to "38.2%",
            0.500 to "50.0%",
            0.618 to "61.8%",
            0.786 to "78.6%",
            1.000 to "100.0%"
        )

        val levels = ratios.map { (r, name) ->
            val p = if (isUpTrend) {
                maxHigh - diff * r
            } else {
                minLow + diff * r
            }
            FibLevel(r, name, p)
        }

        return FibResult(maxHigh, minLow, isUpTrend, levels)
    }

    /**
     * 计算真实波幅均值 ATR (Wilder's RMA 算法，对齐 TradingView ta.atr)
     */
    fun computeAtr(candles: List<Candle>, period: Int = 14): FloatArray {
        val n = candles.size
        val atr = FloatArray(n)
        if (n == 0 || period <= 0) return atr
        var curAtr = 0.0
        for (i in 0 until n) {
            val c = candles[i]
            val tr = if (i == 0) {
                c.high - c.low
            } else {
                val prevClose = candles[i - 1].close
                max(c.high - c.low, max(abs(c.high - prevClose), abs(c.low - prevClose)))
            }
            curAtr = if (i == 0) tr else (curAtr * (period - 1) + tr) / period
            atr[i] = curAtr.toFloat()
        }
        return atr
    }

    /**
     * 计算 3K 趋势突破策略 (BTC 1D Trend Alpha)
     * - donchLen: 唐奇安通道周期 (默认 40)
     * - trendLen: EMA 趋势中枢周期 (默认 50)
     * - atrLen: ATR 周期 (默认 14)
     * - atrMult: ATR 移动止损倍数 (默认 2.0)
     * - guardPct: 偏离 EMA 止损比例 (默认 0.05 即 5%)
     */
    fun computeThreeK(
        candles: List<Candle>,
        donchLen: Int = 40,
        trendLen: Int = 50,
        atrLen: Int = 14,
        atrMult: Double = 2.0,
        guardPct: Double = 0.05
    ): ThreeKResult {
        val n = candles.size
        val emaTrend = computeEma(candles, trendLen)
        val upperDonch = FloatArray(n)
        val lowerDonch = FloatArray(n)
        val trailStop = FloatArray(n)
        val positions = IntArray(n)
        val signals = Array(n) { ThreeKSignal.NONE }

        if (n == 0) return ThreeKResult(emaTrend, upperDonch, lowerDonch, trailStop, positions, signals)

        // 1. 计算 ATR (Wilder's RMA 算法，对齐 TradingView ta.atr)
        val atr = computeAtr(candles, atrLen)

        // 2. 计算唐奇安通道 (基于 high[1] / low[1])
        for (i in 0 until n) {
            if (i >= donchLen) {
                var maxH = -Double.MAX_VALUE
                var minL = Double.MAX_VALUE
                val start = i - donchLen
                val end = i - 1
                for (j in start..end) {
                    if (candles[j].high > maxH) maxH = candles[j].high
                    if (candles[j].low < minL) minL = candles[j].low
                }
                upperDonch[i] = maxH.toFloat()
                lowerDonch[i] = minL.toFloat()
            }
        }

        // 3. 计算 RSI(14)
        val rsi14 = computeRsi(candles, 14)

        // 4. 模拟策略执行与信号计算
        var curPos = 0 // 0=无仓位, 1=持多, -1=持空

        for (i in 0 until n) {
            val c = candles[i]
            val close = c.close
            val ema = emaTrend[i].toDouble()
            val hh = upperDonch[i].toDouble()
            val ll = lowerDonch[i].toDouble()
            val curAtrVal = atr[i].toDouble()
            val curRsi = rsi14[i].toDouble()

            // 计算过去 10 根最高/最低
            val start10 = max(0, i - 9)
            var highest10 = -Double.MAX_VALUE
            var lowest10 = Double.MAX_VALUE
            for (j in start10..i) {
                if (candles[j].high > highest10) highest10 = candles[j].high
                if (candles[j].low < lowest10) lowest10 = candles[j].low
            }

            val trailStopLong = highest10 - curAtrVal * atrMult
            val trailStopShort = lowest10 + curAtrVal * atrMult

            val isReady = i >= max(donchLen, max(trendLen, 14)) && hh > 0 && ll > 0 && ema > 0

            var sig = ThreeKSignal.NONE

            if (isReady) {
                val longEntry = close > hh && close > ema && curRsi > 45.0
                val shortEntry = close < ll && close < ema && curRsi < 55.0

                if (curPos == 0) {
                    if (longEntry) {
                        curPos = 1
                        sig = ThreeKSignal.BUY
                    } else if (shortEntry) {
                        curPos = -1
                        sig = ThreeKSignal.SELL
                    }
                } else if (curPos > 0) {
                    if (close < trailStopLong || close < ema * (1.0 - guardPct)) {
                        curPos = 0
                        sig = ThreeKSignal.CLOSE_LONG
                    }
                } else if (curPos < 0) {
                    if (close > trailStopShort || close > ema * (1.0 + guardPct)) {
                        curPos = 0
                        sig = ThreeKSignal.CLOSE_SHORT
                    }
                }
            }

            positions[i] = curPos
            signals[i] = sig

            if (curPos == 1) {
                trailStop[i] = trailStopLong.toFloat()
            } else if (curPos == -1) {
                trailStop[i] = trailStopShort.toFloat()
            } else {
                trailStop[i] = 0f
            }
        }

        return ThreeKResult(emaTrend, upperDonch, lowerDonch, trailStop, positions, signals)
    }

    /**
     * 计算布林突破策略 (布林线突破策略 + MA60 趋势过滤)
     * - nPeriod: 中轨 SMA 周期 (默认 26)
     * - mPeriod: 标准差回溯周期 (默认 26)
     * - pMultiplier: 带宽倍数 P (默认 1.0)
     * - maFilterLen: 趋势过滤均线周期 (默认 60)
     */
    fun computeBollBreak(
        candles: List<Candle>,
        nPeriod: Int = 26,
        mPeriod: Int = 26,
        pMultiplier: Double = 1.0,
        maFilterLen: Int = 60
    ): BollBreakResult {
        val size = candles.size
        val mid = computeMa(candles, nPeriod)
        val top = FloatArray(size)
        val bottom = FloatArray(size)
        val maFilter = computeMa(candles, maFilterLen)
        val positions = IntArray(size)
        val signals = Array(size) { BollBreakSignal.NONE }

        if (size == 0) return BollBreakResult(mid, top, bottom, maFilter, positions, signals)

        // 1. 计算布林带上轨与下轨
        for (i in (max(nPeriod, mPeriod) - 1) until size) {
            val avg = mid[i].toDouble()
            var sumSqDiff = 0.0
            for (j in (i - mPeriod + 1)..i) {
                val diff = candles[j].close - avg
                sumSqDiff += diff * diff
            }
            val stdDev = sqrt(sumSqDiff / mPeriod)
            top[i] = (avg + pMultiplier * stdDev).toFloat()
            bottom[i] = (avg - pMultiplier * stdDev).toFloat()
        }

        // 2. 模拟策略执行与信号计算
        var curPos = 0 // 0=无仓位, 1=持多, -1=持空
        val warmup = max(max(nPeriod, mPeriod), maFilterLen)

        for (i in 1 until size) {
            if (i < warmup) {
                positions[i] = 0
                continue
            }

            val c = candles[i].close
            val prevC = candles[i - 1].close
            val curTop = top[i].toDouble()
            val prevTop = top[i - 1].toDouble()
            val curBot = bottom[i].toDouble()
            val prevBot = bottom[i - 1].toDouble()
            val curMa6 = maFilter[i].toDouble()

            if (curTop <= 0 || curBot <= 0 || curMa6 <= 0 || prevTop <= 0 || prevBot <= 0) {
                positions[i] = curPos
                continue
            }

            // crossover / crossunder 判断
            val crossOverTop = c > curTop && prevC <= prevTop
            val crossUnderTop = c < curTop && prevC >= prevTop
            val crossUnderBot = c < curBot && prevC >= prevBot
            val crossOverBot = c > curBot && prevC <= prevBot

            val longEntry = crossOverTop && c > curMa6
            val longExit = crossUnderTop
            val shortEntry = crossUnderBot && c < curMa6
            val shortExit = crossOverBot

            var sig = BollBreakSignal.NONE

            if (longEntry) {
                curPos = 1
                sig = BollBreakSignal.BUY
            } else if (longExit && curPos > 0) {
                curPos = 0
                sig = BollBreakSignal.CLOSE_LONG
            } else if (shortEntry) {
                curPos = -1
                sig = BollBreakSignal.SELL
            } else if (shortExit && curPos < 0) {
                curPos = 0
                sig = BollBreakSignal.CLOSE_SHORT
            }

            positions[i] = curPos
            signals[i] = sig
        }

        return BollBreakResult(mid, top, bottom, maFilter, positions, signals)
    }

    /**
     * 洛伦兹分类 (Machine Learning: Lorentzian Classification)
     * - neighborsCount: 近邻数 k (默认 8)
     * - maxBarsBack: 历史最大训练回溯窗口 (默认 1000)
     * - kernelLookback: Nadaraya-Watson 核回归回溯窗口 h (默认 8)
     * - kernelWeight: 核回归相对权重 r (默认 8.0)
     */
    fun computeLorentzian(
        candles: List<Candle>,
        neighborsCount: Int = 8,
        maxBarsBack: Int = 1000,
        kernelLookback: Int = 8,
        kernelWeight: Double = 8.0
    ): LorentzianResult {
        val size = candles.size
        val kernelEst = FloatArray(size)
        val predictions = FloatArray(size)
        val stopLoss = FloatArray(size)
        val positions = IntArray(size)
        val signals = Array(size) { LorentzianSignal.NONE }

        if (size < 30) return LorentzianResult(kernelEst, predictions, stopLoss, positions, signals)

        // 1. 特征工程 (Feature Engineering): 提取 5 组技术特征并归一化至 0~100
        val rsi14 = computeRsi(candles, 14)
        val rsi9 = computeRsi(candles, 9)

        // WaveTrend (10, 11)
        val wt = FloatArray(size)
        val hlc3 = DoubleArray(size) { i -> (candles[i].high + candles[i].low + candles[i].close) / 3.0 }
        val alphaEsa = 2.0 / (10.0 + 1.0)
        var esa = hlc3[0]
        var dEsa = 0.0
        val ci = DoubleArray(size)
        for (i in 0 until size) {
            esa = esa + alphaEsa * (hlc3[i] - esa)
            dEsa = dEsa + alphaEsa * (abs(hlc3[i] - esa) - dEsa)
            ci[i] = if (dEsa > 1e-8) (hlc3[i] - esa) / (0.015 * dEsa) else 0.0
        }
        val alphaWt = 2.0 / (11.0 + 1.0)
        var wtVal = ci[0]
        for (i in 0 until size) {
            wtVal = wtVal + alphaWt * (ci[i] - wtVal)
            wt[i] = ((wtVal + 100.0) / 2.0).toFloat().coerceIn(0f, 100f)
        }

        // CCI (20)
        val cci = FloatArray(size)
        for (i in 19 until size) {
            var sum = 0.0
            for (j in (i - 19)..i) sum += candles[j].close
            val mean = sum / 20.0
            var sumDev = 0.0
            for (j in (i - 19)..i) sumDev += abs(candles[j].close - mean)
            val meanDev = sumDev / 20.0
            val cciVal = if (meanDev > 1e-8) (candles[i].close - mean) / (0.015 * meanDev) else 0.0
            cci[i] = ((cciVal + 200.0) / 4.0).toFloat().coerceIn(0f, 100f)
        }

        // 简易 ADX (14) 代理特征
        val atr14 = computeAtr(candles, 14)
        val adxFeat = FloatArray(size)
        for (i in 14 until size) {
            val highDiff = candles[i].high - candles[i - 1].high
            val lowDiff = candles[i - 1].low - candles[i].low
            val plusDm = if (highDiff > lowDiff && highDiff > 0) highDiff else 0.0
            val minusDm = if (lowDiff > highDiff && lowDiff > 0) lowDiff else 0.0
            val diDiff = abs(plusDm - minusDm)
            val diSum = (plusDm + minusDm).coerceAtLeast(1e-8)
            adxFeat[i] = ((diDiff / diSum) * 100.0).toFloat().coerceIn(0f, 100f)
        }

        // 2. 训练标签生成 (未来 4 根 K 线的价格变化)
        val yTrain = IntArray(size)
        for (i in 0 until size - 4) {
            val cFuture = candles[i + 4].close
            val cNow = candles[i].close
            yTrain[i] = if (cFuture > cNow) 1 else if (cFuture < cNow) -1 else 0
        }

        // 3. 洛伦兹距离计算与 k-NN 分类预测
        val k = neighborsCount.coerceIn(1, 30)
        val maxBack = maxBarsBack.coerceIn(50, 2000)

        // 邻居结构辅助数组
        val topDist = DoubleArray(k)
        val topLabels = IntArray(k)

        for (i in 30 until size) {
            val f1 = rsi14[i]
            val f2 = wt[i]
            val f3 = cci[i]
            val f4 = adxFeat[i]
            val f5 = rsi9[i]

            val startLook = max(20, i - maxBack)
            val endLook = i - 4 // 只与已知未来真实标签的历史样本比较

            var found = 0
            for (j in startLook until endLook) {
                // 洛伦兹距离求和
                val d = ln(1.0 + abs(f1 - rsi14[j])) +
                        ln(1.0 + abs(f2 - wt[j])) +
                        ln(1.0 + abs(f3 - cci[j])) +
                        ln(1.0 + abs(f4 - adxFeat[j])) +
                        ln(1.0 + abs(f5 - rsi9[j]))

                val label = yTrain[j]

                if (found < k) {
                    topDist[found] = d
                    topLabels[found] = label
                    found++
                } else {
                    // 找到当前 top k 中最大距离并替换
                    var maxIdx = 0
                    var maxD = topDist[0]
                    for (m in 1 until k) {
                        if (topDist[m] > maxD) {
                            maxD = topDist[m]
                            maxIdx = m
                        }
                    }
                    if (d < maxD) {
                        topDist[maxIdx] = d
                        topLabels[maxIdx] = label
                    }
                }
            }

            var sumPred = 0
            for (m in 0 until found) {
                sumPred += topLabels[m]
            }
            predictions[i] = sumPred.toFloat()
        }

        // 4. Nadaraya-Watson (NW) 核回归平滑估计 (Rational Quadratic Kernel)
        val h = kernelLookback.coerceAtLeast(3)
        val r = kernelWeight.coerceAtLeast(0.5)
        val kernelLen = 25
        val kernelCoeffs = DoubleArray(kernelLen)
        val hSq = (h * h).toDouble()
        for (d in 0 until kernelLen) {
            val weight = (1.0 + (d * d) / (2.0 * r * hSq)).pow(-r)
            kernelCoeffs[d] = weight
        }

        for (i in 0 until size) {
            var sumVal = 0.0
            var wSum = 0.0
            for (d in 0 until kernelLen) {
                val idx = i - d
                if (idx >= 0) {
                    val w = kernelCoeffs[d]
                    sumVal += candles[idx].close * w
                    wSum += w
                }
            }
            kernelEst[i] = if (wSum > 0) (sumVal / wSum).toFloat() else candles[i].close.toFloat()
        }

        // 5. 状态机演进与交易信号
        var curPos = 0
        var barsHeld = 0
        var currentStop = 0.0

        for (i in 30 until size) {
            val c = candles[i].close
            val pred = predictions[i]
            val prevKernel = if (i > 0) kernelEst[i - 1] else kernelEst[i]
            val curKernel = kernelEst[i]
            val isBullishKernel = curKernel >= prevKernel
            val isBearishKernel = curKernel <= prevKernel

            val atr = if (i < atr14.size && atr14[i] > 0f) atr14[i].toDouble() else c * 0.02

            // 多头入场条件：预测分 >= 2 且核回归平滑向上
            val longEntry = pred >= 2f && isBullishKernel && curPos <= 0
            // 空头入场条件：预测分 <= -2 且核回归平滑向下
            val shortEntry = pred <= -2f && isBearishKernel && curPos >= 0

            var sig = LorentzianSignal.NONE

            if (curPos > 0) {
                barsHeld++
                // 持多离场条件：持满 4 根 K 线、或者跌破动态 ATR 止损、或者核回归转向看跌
                if (c < currentStop || isBearishKernel || barsHeld >= 4 || shortEntry) {
                    curPos = 0
                    sig = LorentzianSignal.CLOSE_LONG
                }
            } else if (curPos < 0) {
                barsHeld++
                // 持空离场条件：持满 4 根 K 线、或者升破动态 ATR 止损、或者核回归转向看涨
                if (c > currentStop || isBullishKernel || barsHeld >= 4 || longEntry) {
                    curPos = 0
                    sig = LorentzianSignal.CLOSE_SHORT
                }
            }

            if (curPos == 0) {
                if (longEntry) {
                    curPos = 1
                    barsHeld = 0
                    currentStop = c - 1.5 * atr
                    sig = LorentzianSignal.BUY
                } else if (shortEntry) {
                    curPos = -1
                    barsHeld = 0
                    currentStop = c + 1.5 * atr
                    sig = LorentzianSignal.SELL
                }
            }

            positions[i] = curPos
            signals[i] = sig
            stopLoss[i] = if (curPos != 0) currentStop.toFloat() else 0f
        }

        return LorentzianResult(kernelEst, predictions, stopLoss, positions, signals)
    }
}
