package com.bitdash.app.market

/**
 * HTX（火币）现货行情（公开只读）。
 *
 * 端点：
 *   GET /market/tickers                                 全量（data[]）
 *   GET /market/detail/merged?symbol=btcusdt             单个聚合行情（tick{}）
 *   GET /market/history/kline?symbol=&period=&size=      K 线（data[]，最新在前）
 *
 * 字段语义：open/close/high/low 为命名字段（无顺序歧义），
 * amount = 基础币成交量，vol = 计价币成交额，符号为小写无分隔（btcusdt）。
 */
class HuobiSource(
    override val id: String,
    override val displayName: String,
    override val note: String,
    private val base: String = "https://api.huobi.pro"
) : MarketSource {

    override fun allTickers(): List<Ticker> {
        val arr = dataArray("$base/market/tickers")
        return arr.mapNotNullIndexed { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNullIndexed null
            val symbol = Symbols.expand(o.optString("symbol")) ?: return@mapNotNullIndexed null
            Ticker(
                symbol = symbol,
                last = o.num("close"),
                open24h = o.num("open"),
                high24h = o.num("high"),
                low24h = o.num("low"),
                quoteVol24h = o.num("vol")
            )
        }
    }

    override fun ticker(symbol: String): Ticker {
        val jo = Http.getObject("$base/market/detail/merged?symbol=${Symbols.lower(symbol)}")
        checkStatus(jo)
        val t = jo.optJSONObject("tick") ?: throw MarketException("无该交易对行情")
        return Ticker(
            symbol = symbol,
            last = t.num("close"),
            open24h = t.num("open"),
            high24h = t.num("high"),
            low24h = t.num("low"),
            quoteVol24h = t.num("vol")
        )
    }

    override fun candles(symbol: String, bar: Bar, limit: Int): List<Candle> {
        val url = "$base/market/history/kline?symbol=${Symbols.lower(symbol)}" +
            "&period=${barOf(bar)}&size=${limit.coerceIn(1, 2000)}"
        val arr = dataArray(url)
        val out = ArrayList<Candle>(arr.length())
        // 最新在前 → 反转为升序
        for (i in arr.length() - 1 downTo 0) {
            val o = arr.optJSONObject(i) ?: continue
            val sec = o.optLong("id", 0L)
            if (sec <= 0L) continue
            out.add(
                Candle(
                    ts = sec * 1000L,
                    open = o.num("open"),
                    high = o.num("high"),
                    low = o.num("low"),
                    close = o.num("close"),
                    vol = o.num("amount")
                )
            )
        }
        return out
    }

    private fun dataArray(url: String): org.json.JSONArray {
        val jo = Http.getObject(url)
        checkStatus(jo)
        return jo.optJSONArray("data") ?: org.json.JSONArray()
    }

    private fun checkStatus(jo: org.json.JSONObject) {
        val status = jo.optString("status")
        if (status.isNotEmpty() && status != "ok") {
            throw MarketException("HTX: ${jo.optString("err-msg").ifEmpty { status }}")
        }
    }

    private fun barOf(bar: Bar): String = when (bar) {
        Bar.M1 -> "1min"
        Bar.M5 -> "5min"
        Bar.M15 -> "15min"
        Bar.M30 -> "30min"
        Bar.H1 -> "60min"
        Bar.H4 -> "4hour"
        Bar.D1 -> "1day"
        Bar.W1 -> "1week"
        Bar.MON1 -> "1mon"
    }
}
