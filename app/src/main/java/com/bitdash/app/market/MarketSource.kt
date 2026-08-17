package com.bitdash.app.market

/**
 * 行情源统一抽象。
 *
 * 设计要点：
 * - 全 App 内部统一用 "BASE-QUOTE"（如 BTC-USDT）作为交易对标识，各源在自己的
 *   适配器里翻译成本地格式（BTCUSDT / BTC_USDT / btcusdt …），这样自选列表可以跨源复用。
 * - 周期同样统一用 OKX 风格的 [Bar] 枚举，由各适配器映射到自家参数名。
 * - 所有方法都是阻塞式网络调用，必须在 IO 线程执行。
 */
interface MarketSource {

    /** 源标识，持久化用（不可随意改动，改了会让用户已保存的选择失效） */
    val id: String

    /** 界面展示名 */
    val displayName: String

    /** 一句话说明，用于源选择弹窗的副标题 */
    val note: String

    /** 全量现货行情（用于搜索页与自选批量刷新） */
    fun allTickers(): List<Ticker>

    /** 单个交易对行情 */
    fun ticker(symbol: String): Ticker

    /** K 线，返回按时间升序（最旧在前） */
    fun candles(symbol: String, bar: Bar, limit: Int): List<Candle>
}

/**
 * 统一行情快照。
 * @param symbol 统一格式 BASE-QUOTE
 * @param quoteVol24h 24h 成交额（以计价货币计，如 USDT）。部分源只提供成交量，适配器负责换算。
 */
data class Ticker(
    val symbol: String,
    val last: Double,
    val open24h: Double,
    val high24h: Double,
    val low24h: Double,
    val quoteVol24h: Double
) {
    /** 24h 涨跌幅百分比（正=涨 负=跌） */
    val changePct: Double
        get() = if (open24h > 0.0) (last - open24h) / open24h * 100.0 else 0.0

    /** 数据是否可用；无效时 UI 显示占位符而不是 0 */
    val valid: Boolean
        get() = last > 0.0
}

/** 单根 K 线；[ts] 为该根的开始时间（毫秒），[vol] 为基础货币成交量 */
data class Candle(
    val ts: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val vol: Double
)

/**
 * K 线周期。取值对齐 python okx_gui.py 的 BAR_OPTIONS。
 * @param key 持久化与跨源映射用的稳定键
 */
enum class Bar(val key: String) {
    M1("1m"),
    M5("5m"),
    M15("15m"),
    M30("30m"),
    H1("1H"),
    H4("4H"),
    D1("1D"),
    W1("1W"),
    MON1("1M");

    /** 该周期一根 K 线的毫秒跨度（月线按 30 天近似，仅用于估算补齐范围） */
    val millis: Long
        get() = when (this) {
            M1 -> 60_000L
            M5 -> 5 * 60_000L
            M15 -> 15 * 60_000L
            M30 -> 30 * 60_000L
            H1 -> 60 * 60_000L
            H4 -> 4 * 60 * 60_000L
            D1 -> 24 * 60 * 60_000L
            W1 -> 7 * 24 * 60 * 60_000L
            MON1 -> 30L * 24 * 60 * 60_000L
        }

    companion object {
        fun fromKey(key: String?): Bar = entries.firstOrNull { it.key == key } ?: M15
    }
}

/** 行情源抛出的可读异常；UI 直接展示 [message] */
class MarketException(message: String, cause: Throwable? = null) : Exception(message, cause)
