package com.bitdash.app.market

import java.util.Locale

/**
 * 价格 / 涨跌幅 / 成交额格式化。
 * 全部显式传 Locale.US，避免部分区域用逗号做小数点导致数字看起来错位。
 */
object Fmt {

    private val LOCALE = Locale.US

    /** 按价格量级自适应小数位：大额保留 2 位，小额保留更多有效位 */
    fun price(v: Double): String = when {
        v.isNaN() || v.isInfinite() -> "—"
        v >= 100.0 -> String.format(LOCALE, "%,.2f", v)
        v >= 1.0 -> String.format(LOCALE, "%.4f", v).trimZeros()
        v >= 0.01 -> String.format(LOCALE, "%.5f", v).trimZeros()
        v >= 0.0001 -> String.format(LOCALE, "%.6f", v).trimZeros()
        v > 0.0 -> String.format(LOCALE, "%.8f", v).trimZeros()
        else -> "0"
    }

    fun pct(v: Double): String =
        if (v.isNaN() || v.isInfinite()) "—" else String.format(LOCALE, "%+.2f%%", v)

    /** 成交额：大数缩写为 K / M / B */
    fun vol(v: Double): String = when {
        v.isNaN() || v.isInfinite() -> "—"
        v >= 1e9 -> String.format(LOCALE, "%.2fB", v / 1e9)
        v >= 1e6 -> String.format(LOCALE, "%.2fM", v / 1e6)
        v >= 1e3 -> String.format(LOCALE, "%.2fK", v / 1e3)
        else -> String.format(LOCALE, "%.2f", v)
    }

    /** 去掉小数末尾多余的 0（含只剩小数点的情况） */
    private fun String.trimZeros(): String {
        if (!contains('.')) return this
        return trimEnd('0').trimEnd('.')
    }
}
