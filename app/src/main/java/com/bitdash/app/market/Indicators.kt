package com.bitdash.app.market

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
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
}
