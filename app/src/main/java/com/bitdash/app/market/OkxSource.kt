package com.bitdash.app.market

/**
 * OKX 现货行情。接口参考 python/okx_gui.py。
 *
 * 交易对格式与 App 内部统一格式一致（BTC-USDT），无需翻译。
 * [domain] 可切换主节点 / AWS 节点（对应 python okx_scalping_gui.py 的 DOMAIN_OPTIONS）。
 */
class OkxSource(
    override val id: String,
    override val displayName: String,
    override val note: String,
    private val domain: String
) : MarketSource {

    override fun allTickers(): List<Ticker> {
        val arr = data("$domain/api/v5/market/tickers?instType=SPOT")
        return arr.mapNotNullIndexed { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNullIndexed null
            val symbol = o.optString("instId")
            if (symbol.isEmpty()) null else Ticker(
                symbol = symbol,
                last = o.num("last"),
                open24h = o.num("open24h"),
                high24h = o.num("high24h"),
                low24h = o.num("low24h"),
                quoteVol24h = o.num("volCcy24h")
            )
        }
    }

    override fun ticker(symbol: String): Ticker {
        val arr = data("$domain/api/v5/market/ticker?instId=${enc(symbol)}")
        val o = arr.optJSONObject(0) ?: throw MarketException("无该交易对行情")
        return Ticker(
            symbol = o.optString("instId").ifEmpty { symbol },
            last = o.num("last"),
            open24h = o.num("open24h"),
            high24h = o.num("high24h"),
            low24h = o.num("low24h"),
            quoteVol24h = o.num("volCcy24h")
        )
    }

    override fun candles(symbol: String, bar: Bar, limit: Int): List<Candle> {
        // OKX 单次上限 300；长周期想凑够 MA20 需要再走 history-candles 往前补
        val first = page("/api/v5/market/candles", symbol, bar, minOf(limit, 300), null)
        if (first.size >= limit) return first

        val merged = ArrayList<Candle>(limit)
        merged.addAll(first)
        var guard = 0
        while (merged.size < limit && guard++ < 4) {
            val oldest = merged.firstOrNull()?.ts ?: break
            val more = try {
                page("/api/v5/market/history-candles", symbol, bar, minOf(limit - merged.size, 100), oldest)
            } catch (_: Exception) {
                break   // 历史接口失败时用已有数据渲染即可
            }
            if (more.isEmpty()) break
            merged.addAll(0, more)
        }
        return merged
    }

    /** @param after 只取早于该毫秒时间戳的数据（OKX 分页语义） */
    private fun page(path: String, symbol: String, bar: Bar, limit: Int, after: Long?): List<Candle> {
        val sb = StringBuilder(domain).append(path)
            .append("?instId=").append(enc(symbol))
            .append("&bar=").append(enc(barOf(bar)))
            .append("&limit=").append(limit)
        if (after != null) sb.append("&after=").append(after)

        val arr = data(sb.toString())
        // OKX 最新在前 → 反转为升序
        val out = ArrayList<Candle>(arr.length())
        for (i in arr.length() - 1 downTo 0) {
            val a = arr.optJSONArray(i) ?: continue
            if (a.length() < 6) continue
            val ts = a.longAt(0)
            if (ts <= 0L) continue
            out.add(
                Candle(ts, a.numAt(1), a.numAt(2), a.numAt(3), a.numAt(4), a.numAt(5))
            )
        }
        return out
    }

    /** 校验 code 并返回 data 数组 */
    private fun data(url: String): org.json.JSONArray {
        val jo = Http.getObject(url)
        val code = jo.optString("code", "-1")
        if (code != "0") {
            throw MarketException("OKX: ${jo.optString("msg").ifEmpty { "错误 $code" }}")
        }
        return jo.optJSONArray("data") ?: org.json.JSONArray()
    }

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    private fun barOf(bar: Bar): String = bar.key   // OKX 就是本项目的基准格式
}
