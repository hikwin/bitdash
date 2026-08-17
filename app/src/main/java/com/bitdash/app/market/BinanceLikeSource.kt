package com.bitdash.app.market

/**
 * Binance 风格接口的适配器（Binance 数据镜像站 / MEXC 共用一套 REST 规范）。
 *
 * 端点：
 *   GET /api/v3/ticker/24hr            全量或单个 24h 行情
 *   GET /api/v3/klines?interval=...    K 线
 *
 * 交易对本地格式为无分隔的 BTCUSDT，需要和内部的 BTC-USDT 互转；
 * 由于 "BTCUSDT" 无法唯一切分（如 BTC/USDT 还是 BTCU/SDT），这里用已知计价币后缀表来切。
 *
 * 说明：Binance 主站 api.binance.com 对部分地区返回 HTTP 451，
 * 因此默认走 data-api.binance.vision 公共行情镜像（只读、无需鉴权、实测可直连）。
 */
class BinanceLikeSource(
    override val id: String,
    override val displayName: String,
    override val note: String,
    private val base: String,
    /** 月线参数名：Binance 用 "1M"，MEXC 也用 "1M"；周线 Binance 用 "1w"，MEXC 用 "1W" */
    private val weekKey: String = "1w",
    private val monthKey: String = "1M"
) : MarketSource {

    override fun allTickers(): List<Ticker> {
        val arr = Http.getArray("$base/api/v3/ticker/24hr")
        return arr.mapNotNullIndexed { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNullIndexed null
            parse(o)
        }
    }

    override fun ticker(symbol: String): Ticker {
        val o = Http.getObject("$base/api/v3/ticker/24hr?symbol=${Symbols.compact(symbol)}")
        return parse(o) ?: throw MarketException("无该交易对行情")
    }

    override fun candles(symbol: String, bar: Bar, limit: Int): List<Candle> {
        val url = "$base/api/v3/klines?symbol=${Symbols.compact(symbol)}" +
            "&interval=${barOf(bar)}&limit=${limit.coerceIn(1, 1000)}"
        val arr = Http.getArray(url)
        // 已是时间升序
        return arr.mapNotNullIndexed { i ->
            val a = arr.optJSONArray(i) ?: return@mapNotNullIndexed null
            if (a.length() < 6) return@mapNotNullIndexed null
            val ts = a.longAt(0)
            if (ts <= 0L) return@mapNotNullIndexed null
            Candle(ts, a.numAt(1), a.numAt(2), a.numAt(3), a.numAt(4), a.numAt(5))
        }
    }

    private fun parse(o: org.json.JSONObject): Ticker? {
        val raw = o.optString("symbol")
        if (raw.isEmpty()) return null
        val symbol = Symbols.expand(raw) ?: return null
        val last = o.num("lastPrice")
        // 用 lastPrice - priceChange 反推开盘价：openPrice 字段部分源缺失
        val open = o.num("openPrice").takeIf { it > 0.0 } ?: (last - o.num("priceChange"))
        return Ticker(
            symbol = symbol,
            last = last,
            open24h = open,
            high24h = o.num("highPrice"),
            low24h = o.num("lowPrice"),
            quoteVol24h = o.num("quoteVolume")
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
        Bar.W1 -> weekKey
        Bar.MON1 -> monthKey
    }
}
