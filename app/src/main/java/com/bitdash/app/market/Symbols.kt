package com.bitdash.app.market

/**
 * 交易对格式互转。
 *
 * App 内部统一用 "BASE-QUOTE"（BTC-USDT），各源本地格式各异：
 *   OKX     BTC-USDT
 *   Binance BTCUSDT      （无分隔）
 *   MEXC    BTCUSDT      （无分隔）
 *   Gate    BTC_USDT
 *   KuCoin  BTC-USDT
 *   Huobi   btcusdt      （无分隔且小写）
 *
 * 难点是把无分隔格式还原回 BASE/QUOTE。"BTCUSDT" 理论上有多种切法，
 * 这里按已知计价币后缀由长到短匹配，能覆盖现货市场的绝大多数交易对。
 */
object Symbols {

    /**
     * 已知计价币，按长度降序排列以便优先匹配长后缀。
     * 顺序很重要：必须先试 USDT 再试 USDT 的子串，否则 BTCUSDT 会被切成 BTCUSD/T。
     */
    private val QUOTES = listOf(
        // 稳定币与法币（长的在前）
        "USDT", "USDC", "TUSD", "BUSD", "FDUSD", "USDD", "DAI",
        "TRY", "EUR", "GBP", "BRL", "JPY", "AUD", "ZAR", "ARS", "MXN", "IDRT", "NGN", "RUB", "UAH",
        // 主流币计价
        "BTC", "ETH", "BNB", "SOL", "TRX", "XRP", "DOGE", "DOT", "ADA", "OKB", "HT", "KCS", "GT",
        "USD"
    ).sortedByDescending { it.length }

    /** 内部格式 → 无分隔大写（Binance / MEXC） */
    fun compact(symbol: String): String = symbol.replace("-", "").uppercase()

    /** 内部格式 → 无分隔小写（Huobi） */
    fun lower(symbol: String): String = compact(symbol).lowercase()

    /** 内部格式 → 下划线分隔（Gate） */
    fun underscore(symbol: String): String = symbol.replace("-", "_").uppercase()

    /** 内部格式 → 连字符分隔（OKX / KuCoin），即原样规范化 */
    fun hyphen(symbol: String): String = symbol.uppercase()

    /**
     * 无分隔格式 → 内部格式。无法识别计价币时返回 null（调用方跳过该交易对）。
     * 输入可以是任意大小写，也允许已经带 - 或 _。
     */
    fun expand(raw: String): String? {
        if (raw.isEmpty()) return null
        val s = raw.uppercase()

        // 已带分隔符：直接规范化
        if (s.contains('-')) return s
        if (s.contains('_')) return s.replace('_', '-')

        for (q in QUOTES) {
            if (s.length > q.length && s.endsWith(q)) {
                return s.substring(0, s.length - q.length) + "-" + q
            }
        }
        return null
    }

    /** 取计价币，用于筛选（如只看 USDT 交易对） */
    fun quoteOf(symbol: String): String = symbol.substringAfterLast('-', "")

    /** 取基础币 */
    fun baseOf(symbol: String): String = symbol.substringBeforeLast('-', symbol)
}
