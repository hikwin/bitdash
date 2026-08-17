package com.bitdash.app

import com.bitdash.app.market.FloatingFmt
import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingFmtTest {

    @Test
    fun testCleanSymbol() {
        assertEquals("BTC", FloatingFmt.cleanSymbol("BTC-USDT"))
        assertEquals("ETH", FloatingFmt.cleanSymbol("ETH-USDT"))
        assertEquals("DOGE", FloatingFmt.cleanSymbol("DOGE/USDT"))
        assertEquals("PEPE", FloatingFmt.cleanSymbol("PEPEUSDT"))
        assertEquals("PAXG", FloatingFmt.cleanSymbol("PAXG-USDT"))
        assertEquals("SOL", FloatingFmt.cleanSymbol("sol-usdt"))
        assertEquals("BTC", FloatingFmt.cleanSymbol("BTC"))
    }

    @Test
    fun testPriceOver100() {
        // 大于等于100：仅显示整数位
        assertEquals("96420", FloatingFmt.price(96420.0))
        assertEquals("96421", FloatingFmt.price(96420.5))
        assertEquals("2730", FloatingFmt.price(2730.49))
        assertEquals("2950", FloatingFmt.price(2950.0))
        assertEquals("100", FloatingFmt.price(100.0))
        assertEquals("100", FloatingFmt.price(100.49))
    }

    @Test
    fun testPrice10To100() {
        // 大于10小于100：保留2位小数
        assertEquals("99.99", FloatingFmt.price(99.99))
        assertEquals("19.85", FloatingFmt.price(19.85))
        assertEquals("19.80", FloatingFmt.price(19.80))
        assertEquals("10.00", FloatingFmt.price(10.0))
        assertEquals("25.50", FloatingFmt.price(25.5))
    }

    @Test
    fun testPrice1To10() {
        // 1 ~ 10：显示4位小数
        assertEquals("2.4560", FloatingFmt.price(2.456))
        assertEquals("1.0000", FloatingFmt.price(1.0))
        assertEquals("9.8765", FloatingFmt.price(9.87654))
        assertEquals("3.1415", FloatingFmt.price(3.1415))
    }

    @Test
    fun testPriceUnder1() {
        // 小于1（0的个数 < 5）：最多显示小数点后5位
        assertEquals("0.07058", FloatingFmt.price(0.07058))
        assertEquals("0.07058", FloatingFmt.price(0.070581))
        assertEquals("0.5", FloatingFmt.price(0.5))
        assertEquals("0.00012", FloatingFmt.price(0.00012))
        assertEquals("0.00001", FloatingFmt.price(0.00001))
    }

    @Test
    fun testMicroPriceWithZeros() {
        // 小数点后等于或多于5个0（特低价格币种）：显示 0.0(N)XX
        assertEquals("0.0(5)25", FloatingFmt.price(0.0000025))
        assertEquals("0.0(5)8", FloatingFmt.price(0.000008))
        assertEquals("0.0(7)25", FloatingFmt.price(0.000000025))
        assertEquals("0.0(6)1234", FloatingFmt.price(0.00000012345))
        assertEquals("0.0(6)7", FloatingFmt.price(0.0000007))
        assertEquals("0.0(10)1", FloatingFmt.price(0.00000000001))
    }
}
