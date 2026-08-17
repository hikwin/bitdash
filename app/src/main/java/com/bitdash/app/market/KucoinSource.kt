package com.bitdash.app.market

/**
 * KuCoin 现货行情（v1 REST，公开只读）。
 *
 * 端点：
 *   GET /api/v1/market/allTickers                        全量（data.ticker[]）
 *   GET /api/v1/market/stats?symbol=BTC-USDT             单个 24h 统计
 *   GET /api/v1/market/candles?symbol=BTC-USDT&type=...  K 线
 *
 * K 线字段顺序：[0]=秒级时间戳 [1]=开 [2]=收 [3]=高 [4]=低 [5]=成交量 [6]=成交额，
 * 且返回为时间倒序（最新在前），需要反转。
 */
class KucoinSource(
    override val id: String,
    override val displayName: String,
    override val note: String,
    private val base: String = "https://api.kucoin.com"
) : MarketSource {

    override fun allTickers(): List<Ticker> {
        val arr = body("$base/api/v1/market/allTickers").optJSONArray("ticker")
            ?: throw MarketException("响应缺少行情列表")
        return arr.mapNotNullIndexed { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNullIndexed null
            val symbol = Symbols.expand(o.optString("symbol")) ?: return@mapNotNullIndexed null
            val last = o.num("last")
            // 全量接口直接带 open 字段；缺失时用涨跌率（小数，0.0123 = +1.23%）反推
            val rate = o.num("changeRate")
            Ticker(
                symbol = symbol,
                last = last,
                open24h = o.num("open").takeIf { it > 0.0 }
                    ?: if (rate != -1.0) last / (1.0 + rate) else last,
                high24h = o.num("high"),
                low24h = o.num("low"),
                quoteVol24h = o.num("volValue")
            )
        }
    }

    override fun ticker(symbol: String): Ticker {
        val o = body("$base/api/v1/market/stats?symbol=${Symbols.hyphen(symbol)}")
        val last = o.num("last")
        val rate = o.num("changeRate")
        return Ticker(
            symbol = Symbols.expand(o.optString("symbol")) ?: symbol,
            last = last,
            open24h = if (rate != -1.0) last / (1.0 + rate) else last,
            high24h = o.num("high"),
            low24h = o.num("low"),
            quoteVol24h = o.num("volValue")
        )
    }

    override fun candles(symbol: String, bar: Bar, limit: Int): List<Candle> {
        // KuCoin 不支持 limit，用 startAt/endAt 按周期跨度圈定范围
        val endSec = System.currentTimeMillis() / 1000L
        val startSec = endSec - (bar.millis / 1000L) * (limit + 2L)
        val url = "$base/api/v1/market/candles?symbol=${Symbols.hyphen(symbol)}" +
            "&type=${barOf(bar)}&startAt=$startSec&endAt=$endSec"

        val arr = bodyArray(url)
        val out = ArrayList<Candle>(arr.length())
        // 最新在前 → 反转为升序
        for (i in arr.length() - 1 downTo 0) {
            val a = arr.optJSONArray(i) ?: continue
            if (a.length() < 6) continue
            val sec = a.longAt(0)
            if (sec <= 0L) continue
            out.add(
                Candle(
                    ts = sec * 1000L,
                    open = a.numAt(1),
                    high = a.numAt(3),
                    low = a.numAt(4),
                    close = a.numAt(2),
                    vol = a.numAt(5)
                )
            )
        }
        return out
    }

    /** 校验 code 并取出 data 对象 */
    private fun body(url: String): org.json.JSONObject {
        val jo = Http.getObject(url)
        checkCode(jo)
        return jo.optJSONObject("data") ?: throw MarketException("响应缺少数据")
    }

    /** 校验 code 并取出 data 数组 */
    private fun bodyArray(url: String): org.json.JSONArray {
        val jo = Http.getObject(url)
        checkCode(jo)
        return jo.optJSONArray("data") ?: org.json.JSONArray()
    }

    private fun checkCode(jo: org.json.JSONObject) {
        val code = jo.optString("code", "")
        if (code != "200000") {
            throw MarketException("KuCoin: ${jo.optString("msg").ifEmpty { "错误 $code" }}")
        }
    }

    private fun barOf(bar: Bar): String = when (bar) {
        Bar.M1 -> "1min"
        Bar.M5 -> "5min"
        Bar.M15 -> "15min"
        Bar.M30 -> "30min"
        Bar.H1 -> "1hour"
        Bar.H4 -> "4hour"
        Bar.D1 -> "1day"
        Bar.W1 -> "1week"
        Bar.MON1 -> "1month"
    }
}
