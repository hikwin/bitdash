package com.bitdash.app.market

/**
 * Gate.io 现货行情（v4 REST，公开只读）。
 *
 * 端点：
 *   GET /api/v4/spot/tickers?currency_pair=BTC_USDT
 *   GET /api/v4/spot/candlesticks?currency_pair=BTC_USDT&interval=15m&limit=N
 *
 * K 线返回的是数组形式，字段顺序为：
 *   [0]=秒级时间戳 [1]=计价币成交额 [2]=收 [3]=高 [4]=低 [5]=开 [6]=基础币成交量
 * 注意收盘价在下标 2、开盘价在下标 5，和大多数交易所的 OHLC 顺序不同。
 */
class GateSource(
    override val id: String,
    override val displayName: String,
    override val note: String,
    private val base: String = "https://api.gateio.ws"
) : MarketSource {

    override fun allTickers(): List<Ticker> {
        val arr = Http.getArray("$base/api/v4/spot/tickers")
        return arr.mapNotNullIndexed { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNullIndexed null
            parse(o)
        }
    }

    override fun ticker(symbol: String): Ticker {
        val arr = Http.getArray(
            "$base/api/v4/spot/tickers?currency_pair=${Symbols.underscore(symbol)}"
        )
        val o = arr.optJSONObject(0) ?: throw MarketException("无该交易对行情")
        return parse(o) ?: throw MarketException("无该交易对行情")
    }

    override fun candles(symbol: String, bar: Bar, limit: Int): List<Candle> {
        val url = "$base/api/v4/spot/candlesticks?currency_pair=${Symbols.underscore(symbol)}" +
            "&interval=${barOf(bar)}&limit=${limit.coerceIn(1, 1000)}"
        val arr = Http.getArray(url)
        // 已是时间升序
        return arr.mapNotNullIndexed { i ->
            val a = arr.optJSONArray(i) ?: return@mapNotNullIndexed null
            if (a.length() < 6) return@mapNotNullIndexed null
            val sec = a.longAt(0)
            if (sec <= 0L) return@mapNotNullIndexed null
            Candle(
                ts = sec * 1000L,
                open = a.numAt(5),
                high = a.numAt(3),
                low = a.numAt(4),
                close = a.numAt(2),
                vol = a.numAt(6)
            )
        }
    }

    private fun parse(o: org.json.JSONObject): Ticker? {
        val symbol = Symbols.expand(o.optString("currency_pair")) ?: return null
        val last = o.num("last")
        // Gate 只给涨跌百分比，反推开盘价
        val pct = o.num("change_percentage")
        val open = if (pct != 0.0) last / (1.0 + pct / 100.0) else last
        return Ticker(
            symbol = symbol,
            last = last,
            open24h = open,
            high24h = o.num("high_24h"),
            low24h = o.num("low_24h"),
            quoteVol24h = o.num("quote_volume")
        )
    }

    private fun barOf(bar: Bar): String = when (bar) {
        Bar.M1 -> "1m"
        Bar.M5 -> "5m"
        Bar.M15 -> "15m"
        Bar.M30 -> "30m"
        Bar.H1 -> "1h"
        Bar.H4 -> "4h"
        Bar.D1 -> "1d"
        Bar.W1 -> "7d"
        Bar.MON1 -> "30d"
    }
}
