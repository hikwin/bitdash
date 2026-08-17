package com.bitdash.app

import com.bitdash.app.market.Candle
import com.bitdash.app.market.Indicators
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IndicatorsTest {

    private fun mockCandles(count: Int, startPrice: Double = 100.0): List<Candle> {
        val list = ArrayList<Candle>()
        var p = startPrice
        for (i in 0 until count) {
            val high = p + 2.0
            val low = p - 2.0
            val open = p - 0.5
            val close = p + 0.5
            val vol = 1000.0 + i * 10
            list.add(Candle(ts = 100000L + i * 60000L, open = open, high = high, low = low, close = close, vol = vol))
            p += 1.0
        }
        return list
    }

    @Test
    fun testComputeMa() {
        val candles = mockCandles(10, 10.0)
        val ma5 = Indicators.computeMa(candles, 5)
        assertEquals(10, ma5.size)
        // 0~3 应该为 0
        assertEquals(0f, ma5[0], 0.001f)
        assertEquals(0f, ma5[3], 0.001f)
        // 第 4 根 close 的均值 (10.5 + 11.5 + 12.5 + 13.5 + 14.5) / 5 = 12.5
        assertEquals(12.5f, ma5[4], 0.01f)
    }

    @Test
    fun testComputeBoll() {
        val candles = mockCandles(30, 50.0)
        val boll = Indicators.computeBoll(candles, 20, 2.0)
        assertEquals(30, boll.mid.size)
        assertEquals(30, boll.up.size)
        assertEquals(30, boll.dn.size)

        // 第 20 根起有值
        assertTrue(boll.mid[25] > 0f)
        assertTrue(boll.up[25] >= boll.mid[25])
        assertTrue(boll.dn[25] <= boll.mid[25])
    }

    @Test
    fun testComputeMacd() {
        val candles = mockCandles(40, 100.0)
        val macd = Indicators.computeMacd(candles, 12, 26, 9)
        assertEquals(40, macd.dif.size)
        assertEquals(40, macd.dea.size)
        assertEquals(40, macd.macd.size)
    }

    @Test
    fun testComputeRsi() {
        val candles = mockCandles(30, 20.0)
        val rsi6 = Indicators.computeRsi(candles, 6)
        assertEquals(30, rsi6.size)
        assertTrue(rsi6[10] in 0f..100f)
    }

    @Test
    fun testComputeKdj() {
        val candles = mockCandles(30, 30.0)
        val kdj = Indicators.computeKdj(candles, 9, 3, 3)
        assertEquals(30, kdj.k.size)
        assertEquals(30, kdj.d.size)
        assertEquals(30, kdj.j.size)
        assertTrue(kdj.k[15] in -50f..150f)
    }

    @Test
    fun testComputeTurtle() {
        val candles = mockCandles(40, 100.0)
        val turtle = Indicators.computeTurtle(candles, 20, 10, 20)
        assertEquals(40, turtle.upper.size)
        assertEquals(40, turtle.lower.size)
        assertEquals(40, turtle.exitLong.size)
        assertEquals(40, turtle.exitShort.size)
        assertEquals(40, turtle.atr.size)

        // 第 25 根上轨大于下轨，ATR 大于 0
        assertTrue(turtle.upper[25] > turtle.lower[25])
        assertTrue(turtle.atr[25] > 0f)
    }
}
