package com.bitdash.app.market

import java.util.Locale

/**
 * 悬浮窗专属的价格与币种格式化工具。
 *
 * 需求规则（力求悬浮窗极简）：
 * 1. 币种不带 USDT，只显示纯代币名称（如 DOGE、BTC）；
 * 2. 价格 >= 100 美元（如比特币、以太坊、黄金/PAXG 等）：只显示整数位（如 96420、2730）；
 * 3. 10 <= 价格 < 100 美元：保留 2 位小数（如 19.85）；
 * 4. 1 <= 价格 < 10 美元：显示 4 位小数（如 2.4560）；
 * 5. 价格 < 1 美元：最多显示小数点后 5 位（如 0.07058）。
 */
object FloatingFmt {

    private val LOCALE = Locale.US

    /**
     * 清洗币种名称，去除 -USDT、/USDT 等后缀，只保留纯币种名。
     * 例如："BTC-USDT" -> "BTC", "DOGE/USDT" -> "DOGE", "ETH" -> "ETH"
     */
    fun cleanSymbol(symbol: String): String {
        var s = symbol.trim()
        if (s.contains("-")) {
            s = s.substringBefore("-")
        } else if (s.contains("/")) {
            s = s.substringBefore("/")
        } else if (s.endsWith("USDT", ignoreCase = true) && s.length > 4) {
            s = s.substring(0, s.length - 4)
        }
        return s.uppercase(LOCALE)
    }

    /**
     * 按悬浮窗规则格式化价格：
     * - >= 100: 整数位（四舍五入）
     * - 10 ~ 100: 保留 2 位小数
     * - 1 ~ 10: 显示 4 位小数
     * - < 1: 最多 5 位小数（去除尾部多余的 0）
     */
    fun price(v: Double): String = when {
        v.isNaN() || v.isInfinite() -> "—"
        v >= 100.0 -> Math.round(v).toString()
        v >= 10.0 -> String.format(LOCALE, "%.2f", v)
        v >= 1.0 -> String.format(LOCALE, "%.4f", v)
        v > 0.0 -> String.format(LOCALE, "%.5f", v).trimZeros()
        else -> "0"
    }

    /**
     * 去除小数点后多余的末尾 0
     */
    private fun String.trimZeros(): String {
        if (!contains('.')) return this
        val trimmed = trimEnd('0').trimEnd('.')
        return if (trimmed.isEmpty()) "0" else trimmed
    }
}
