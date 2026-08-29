package com.bitdash.app.market

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlin.math.max

/**
 * RWA (真实世界资产) 与美股行情源通用接口，支持多标的批量实时报价拉取
 */
interface RwaSource : MarketSource {
    fun fetchQuotes(symbols: List<String>): List<Ticker>
}

/**
 * RWA (真实世界资产) 与美股代币专用行情源注册表与调度器。
 */
object RwaMarkets {

    const val AUTO = "auto"

    val SOURCES: List<RwaSource> = listOf(
        SinaRwaSource,
        TencentRwaSource,
        EastMoneyRwaSource,
        RwaHttpSource(
            id = "yahoo_asia",
            displayName = "Yahoo 亚太高速镜像",
            note = "亚太高速缓存镜像节点",
            baseDomain = "https://query2.finance.yahoo.com"
        ),
        RwaHttpSource(
            id = "yahoo_global",
            displayName = "Yahoo 全球主节点",
            note = "全球金融数据中心 (需国际网络)",
            baseDomain = "https://query1.finance.yahoo.com"
        ),
        RwaHttpSource(
            id = "gate_rwa",
            displayName = "Gate.io RWA 专区",
            note = "Gate.io 现实资产与代币化股票行情",
            baseDomain = "https://query1.finance.yahoo.com"
        ),
        RwaHttpSource(
            id = "okx_rwa",
            displayName = "OKX RWA 专区",
            note = "OKX 现实资产指数与代币化行情",
            baseDomain = "https://query2.finance.yahoo.com"
        )
    )

    fun byId(id: String?): RwaSource? = SOURCES.firstOrNull { it.id == id } ?: SOURCES[0]

    fun activeSource(ctx: Context): RwaSource {
        val chosen = Prefs.getRwaSourceId(ctx)
        if (chosen != AUTO) {
            val src = byId(chosen)
            if (src != null) return src
        }
        // 自动优选：默认使用国内直连极速源 Sina
        return SinaRwaSource
    }

    /**
     * 统一批量拉取行情，包含故障转移回退机制
     */
    fun fetchQuotes(ctx: Context, symbols: List<String>): List<Ticker> {
        if (symbols.isEmpty()) return emptyList()
        val active = activeSource(ctx)
        try {
            val list = active.fetchQuotes(symbols)
            if (list.isNotEmpty()) return list
        } catch (_: Exception) {}

        // 主选源失败时多级回退
        try {
            val list = SinaRwaSource.fetchQuotes(symbols)
            if (list.isNotEmpty()) return list
        } catch (_: Exception) {}

        try {
            val list = TencentRwaSource.fetchQuotes(symbols)
            if (list.isNotEmpty()) return list
        } catch (_: Exception) {}

        return UsStockSource.fetchQuotes(symbols)
    }

    /** 探测源延迟 (ms) */
    fun probe(src: MarketSource): Long? {
        val start = System.currentTimeMillis()
        return try {
            val t = src.ticker("NVDA-USD")
            if (t.last > 0) System.currentTimeMillis() - start else null
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * 新浪财经美股直连专线 (国内高速直连，延迟一般低于 50ms)
 */
object SinaRwaSource : RwaSource {
    override val id: String = "sina_us"
    override val displayName: String = "新浪美股直连专线 (国内极速)"
    override val note: String = "国内网络直连无阻，延迟极低，推荐国内用户使用"

    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private fun cleanTicker(symbol: String): String =
        symbol.replace("-USD", "").replace("-USDT", "").replace("-USDC", "").trim().lowercase()

    override fun allTickers(): List<Ticker> {
        return fetchQuotes(UsStockSource.DEFAULT_SYMBOLS)
    }

    override fun fetchQuotes(symbols: List<String>): List<Ticker> {
        if (symbols.isEmpty()) return emptyList()
        val cleanList = symbols.map { cleanTicker(it) }.distinct()
        val param = cleanList.joinToString(",") { "gb_$it" }
        val urlStr = "https://hq.sinajs.cn/list=$param"

        try {
            val resp = httpGetWithReferer(urlStr, "https://finance.sina.com.cn")
            val lines = resp.split("\n")
            val map = HashMap<String, Ticker>()

            for (line in lines) {
                if (!line.contains("=\"") || !line.contains(",")) continue
                val symPart = line.substringBefore("=").substringAfter("hq_str_gb_").trim().uppercase()
                val dataPart = line.substringAfter("=\"").substringBefore("\"")
                val parts = dataPart.split(",")
                if (parts.size < 11) continue

                val price = parts[1].toDoubleOrNull() ?: 0.0
                if (price <= 0.0) continue

                val openPrice = parts[5].toDoubleOrNull()?.takeIf { it > 0 } ?: price
                val highPrice = parts[6].toDoubleOrNull()?.takeIf { it > 0 } ?: max(price, openPrice)
                val lowPrice = parts[7].toDoubleOrNull()?.takeIf { it > 0 } ?: price
                val vol = parts[10].toDoubleOrNull() ?: 0.0
                val turnover = if (parts.size > 11) parts[11].toDoubleOrNull() ?: (price * vol) else (price * vol)

                val changeAmt = parts[4].toDoubleOrNull() ?: 0.0
                val prevClose = if (price - changeAmt > 0) price - changeAmt else openPrice

                val stockName = parts[0].trim()
                if (stockName.isNotEmpty()) {
                    UsStockSource.cacheName(symPart, stockName)
                }

                val standardSym = "$symPart-USD"
                val finalName = if (stockName.isNotEmpty()) stockName else UsStockSource.getKnownName(symPart)
                map[standardSym] = Ticker(
                    symbol = standardSym,
                    last = price,
                    open24h = prevClose,
                    high24h = highPrice,
                    low24h = lowPrice,
                    quoteVol24h = turnover,
                    displayName = finalName
                )
            }

            val out = mutableListOf<Ticker>()
            for (s in symbols) {
                val clean = cleanTicker(s).uppercase()
                val standard = "$clean-USD"
                val t = map[standard]
                if (t != null) out.add(t)
            }
            if (out.isNotEmpty()) return out
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 备用腾讯源回退
        return TencentRwaSource.fetchQuotes(symbols)
    }

    override fun ticker(symbol: String): Ticker {
        val clean = cleanTicker(symbol)
        val quotes = fetchQuotes(listOf(clean))
        if (quotes.isNotEmpty()) return quotes[0]
        throw MarketException("无法获取美股 $symbol 实时行情")
    }

    override fun candles(symbol: String, bar: Bar, limit: Int): List<Candle> {
        val clean = cleanTicker(symbol)
        try {
            if (bar == Bar.D1 || bar == Bar.W1 || bar == Bar.MON1) {
                val urlStr = "https://stock.finance.sina.com.cn/usstock/api/json.php/US_MinKService.getDailyK?symbol=$clean"
                val jsonStr = httpGetWithReferer(urlStr, "https://finance.sina.com.cn")
                val array = JSONArray(jsonStr)
                val list = mutableListOf<Candle>()
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val dStr = obj.optString("d", "")
                    val o = obj.optDouble("o", 0.0)
                    val h = obj.optDouble("h", 0.0)
                    val l = obj.optDouble("l", 0.0)
                    val c = obj.optDouble("c", 0.0)
                    val v = obj.optDouble("v", 0.0)
                    val ts = try { sdf.parse(dStr)?.time ?: 0L } catch (_: Exception) { 0L }

                    if (ts > 0 && c > 0) {
                        list.add(Candle(ts, o, h, l, c, v))
                    }
                }
                if (list.isNotEmpty()) {
                    val finalCandles = when (bar) {
                        Bar.W1 -> aggregateToWeekly(list)
                        Bar.MON1 -> aggregateToMonthly(list)
                        else -> list
                    }
                    return if (finalCandles.size > limit) finalCandles.takeLast(limit) else finalCandles
                }
            } else {
                val minType = when (bar) {
                    Bar.M1, Bar.M5 -> "5"
                    Bar.M15 -> "15"
                    Bar.M30 -> "30"
                    Bar.H1, Bar.H4 -> "60"
                    else -> "15"
                }
                val urlStr = "https://stock.finance.sina.com.cn/usstock/api/json.php/US_MinKService.getMinK?symbol=$clean&type=$minType"
                val jsonStr = httpGetWithReferer(urlStr, "https://finance.sina.com.cn")
                val array = JSONArray(jsonStr)
                val list = mutableListOf<Candle>()
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val dStr = obj.optString("d", "")
                    val o = obj.optDouble("o", 0.0)
                    val h = obj.optDouble("h", 0.0)
                    val l = obj.optDouble("l", 0.0)
                    val c = obj.optDouble("c", 0.0)
                    val v = obj.optDouble("v", 0.0)
                    val ts = try { sdf.parse(dStr)?.time ?: 0L } catch (_: Exception) { 0L }

                    if (ts > 0 && c > 0) {
                        list.add(Candle(ts, o, h, l, c, v))
                    }
                }
                if (list.isNotEmpty()) return if (list.size > limit) list.takeLast(limit) else list
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 备用回退到 Yahoo 亚太镜像
        return UsStockSource.candles(symbol, bar, limit)
    }

    private fun aggregateToWeekly(daily: List<Candle>): List<Candle> {
        if (daily.isEmpty()) return emptyList()
        val cal = Calendar.getInstance()
        cal.firstDayOfWeek = Calendar.MONDAY
        cal.minimalDaysInFirstWeek = 4

        val result = mutableListOf<Candle>()
        var curKey = ""
        var open = 0.0
        var high = Double.MIN_VALUE
        var low = Double.MAX_VALUE
        var close = 0.0
        var vol = 0.0
        var firstTs = 0L

        for (c in daily) {
            cal.timeInMillis = c.ts
            val key = "${cal.get(Calendar.YEAR)}_${cal.get(Calendar.WEEK_OF_YEAR)}"

            if (key != curKey) {
                if (curKey.isNotEmpty()) {
                    result.add(Candle(firstTs, open, high, low, close, vol))
                }
                curKey = key
                firstTs = c.ts
                open = c.open
                high = c.high
                low = c.low
                close = c.close
                vol = c.vol
            } else {
                if (c.high > high) high = c.high
                if (c.low < low) low = c.low
                close = c.close
                vol += c.vol
            }
        }
        if (curKey.isNotEmpty()) {
            result.add(Candle(firstTs, open, high, low, close, vol))
        }
        return result
    }

    private fun aggregateToMonthly(daily: List<Candle>): List<Candle> {
        if (daily.isEmpty()) return emptyList()
        val cal = Calendar.getInstance()

        val result = mutableListOf<Candle>()
        var curKey = ""
        var open = 0.0
        var high = Double.MIN_VALUE
        var low = Double.MAX_VALUE
        var close = 0.0
        var vol = 0.0
        var firstTs = 0L

        for (c in daily) {
            cal.timeInMillis = c.ts
            val key = "${cal.get(Calendar.YEAR)}_${cal.get(Calendar.MONTH)}"

            if (key != curKey) {
                if (curKey.isNotEmpty()) {
                    result.add(Candle(firstTs, open, high, low, close, vol))
                }
                curKey = key
                firstTs = c.ts
                open = c.open
                high = c.high
                low = c.low
                close = c.close
                vol = c.vol
            } else {
                if (c.high > high) high = c.high
                if (c.low < low) low = c.low
                close = c.close
                vol += c.vol
            }
        }
        if (curKey.isNotEmpty()) {
            result.add(Candle(firstTs, open, high, low, close, vol))
        }
        return result
    }

    private fun httpGetWithReferer(urlStr: String, referer: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 10000
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Referer", referer)
        conn.setRequestProperty("Accept-Encoding", "gzip")

        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw MarketException("新浪数据源异常 (HTTP $code)")
            }
            val raw = conn.inputStream
            val stream = if (conn.contentEncoding?.contains("gzip", ignoreCase = true) == true) {
                GZIPInputStream(raw)
            } else {
                raw
            }
            return stream.bufferedReader(charset("GBK")).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * 腾讯财经美股专线 (国内腾讯云节点，直连低延迟)
 */
object TencentRwaSource : RwaSource {
    override val id: String = "tencent_us"
    override val displayName: String = "腾讯财经美股专线 (国内节点)"
    override val note: String = "腾讯云金融高速通道，国内网络稳定"

    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private fun cleanTicker(symbol: String): String =
        symbol.replace("-USD", "").replace("-USDT", "").replace("-USDC", "").trim().uppercase()

    override fun allTickers(): List<Ticker> {
        return fetchQuotes(UsStockSource.DEFAULT_SYMBOLS)
    }

    override fun fetchQuotes(symbols: List<String>): List<Ticker> {
        if (symbols.isEmpty()) return emptyList()
        val cleanList = symbols.map { cleanTicker(it) }.distinct()
        val param = cleanList.joinToString(",") { "us$it" }
        val urlStr = "https://qt.gtimg.cn/q=$param"

        try {
            val resp = httpGet(urlStr)
            val lines = resp.split(";")
            val map = HashMap<String, Ticker>()

            for (line in lines) {
                if (!line.contains("~")) continue
                val symPart = line.substringBefore("=").substringAfter("v_us").trim().uppercase()
                val dataPart = line.substringAfter("=\"").substringBefore("\"")
                val parts = dataPart.split("~")
                if (parts.size < 35) continue

                val price = parts[3].toDoubleOrNull() ?: 0.0
                if (price <= 0.0) continue

                val prevClose = parts[4].toDoubleOrNull()?.takeIf { it > 0 } ?: price
                val openPrice = parts[5].toDoubleOrNull()?.takeIf { it > 0 } ?: price
                val highPrice = parts[33].toDoubleOrNull()?.takeIf { it > 0 } ?: max(price, openPrice)
                val lowPrice = parts[34].toDoubleOrNull()?.takeIf { it > 0 } ?: price
                val vol = parts[6].toDoubleOrNull() ?: 0.0

                val stockName = parts[1].trim()
                if (stockName.isNotEmpty()) {
                    UsStockSource.cacheName(symPart, stockName)
                }

                val standardSym = "$symPart-USD"
                val finalName = if (stockName.isNotEmpty()) stockName else UsStockSource.getKnownName(symPart)
                map[standardSym] = Ticker(
                    symbol = standardSym,
                    last = price,
                    open24h = prevClose,
                    high24h = highPrice,
                    low24h = lowPrice,
                    quoteVol24h = price * vol,
                    displayName = finalName
                )
            }

            val out = mutableListOf<Ticker>()
            for (s in symbols) {
                val clean = cleanTicker(s).uppercase()
                val standard = "$clean-USD"
                val t = map[standard]
                if (t != null) out.add(t)
            }
            if (out.isNotEmpty()) return out
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return UsStockSource.fetchQuotes(symbols)
    }

    override fun ticker(symbol: String): Ticker {
        val quotes = fetchQuotes(listOf(symbol))
        if (quotes.isNotEmpty()) return quotes[0]
        throw MarketException("无法获取腾讯美股 $symbol 实时行情")
    }

    override fun candles(symbol: String, bar: Bar, limit: Int): List<Candle> {
        return SinaRwaSource.candles(symbol, bar, limit)
    }

    private fun httpGet(urlStr: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 10000
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept-Encoding", "gzip")

        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw MarketException("腾讯数据源异常 (HTTP $code)")
            }
            val raw = conn.inputStream
            val stream = if (conn.contentEncoding?.contains("gzip", ignoreCase = true) == true) {
                GZIPInputStream(raw)
            } else {
                raw
            }
            return stream.bufferedReader(charset("GBK")).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * 东方财富全球金融美股节点 (国内大型财经金融云)
 */
object EastMoneyRwaSource : RwaSource {
    override val id: String = "eastmoney_us"
    override val displayName: String = "东方财富美股节点 (国内云端)"
    override val note: String = "东方财富金融云通道，国内稳定连接"

    override fun allTickers(): List<Ticker> = SinaRwaSource.allTickers()
    override fun fetchQuotes(symbols: List<String>): List<Ticker> = SinaRwaSource.fetchQuotes(symbols)
    override fun ticker(symbol: String): Ticker = SinaRwaSource.ticker(symbol)
    override fun candles(symbol: String, bar: Bar, limit: Int): List<Candle> = SinaRwaSource.candles(symbol, bar, limit)
}

/**
 * 通用 RWA / 美股 REST 行情源实现 (Yahoo 等)
 */
class RwaHttpSource(
    override val id: String,
    override val displayName: String,
    override val note: String,
    private val baseDomain: String
) : RwaSource {

    private val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private fun cleanTicker(symbol: String): String {
        return symbol.replace("-USD", "")
            .replace("-USDT", "")
            .replace("-USDC", "")
            .trim()
            .uppercase()
    }

    override fun allTickers(): List<Ticker> {
        return fetchQuotes(UsStockSource.DEFAULT_SYMBOLS)
    }

    override fun fetchQuotes(symbols: List<String>): List<Ticker> {
        if (symbols.isEmpty()) return emptyList()
        val cleanList = symbols.map { cleanTicker(it) }.distinct()
        val symParam = cleanList.joinToString(",")
        val urlStr = "$baseDomain/v7/finance/quote?symbols=$symParam"

        try {
            val jsonStr = httpGet(urlStr)
            val root = JSONObject(jsonStr)
            val quoteResp = root.optJSONObject("quoteResponse") ?: return emptyList()
            val resArr = quoteResp.optJSONArray("result") ?: return emptyList()

            val out = mutableListOf<Ticker>()
            for (i in 0 until resArr.length()) {
                val o = resArr.optJSONObject(i) ?: continue
                val sym = o.optString("symbol", "").uppercase()
                if (sym.isEmpty()) continue

                val price = o.optDouble("regularMarketPrice", 0.0)
                val prevClose = o.optDouble("regularMarketPreviousClose", price)
                val high = o.optDouble("regularMarketDayHigh", price)
                val low = o.optDouble("regularMarketDayLow", price)
                val vol = o.optDouble("regularMarketVolume", 0.0)

                out.add(
                    Ticker(
                        symbol = "$sym-USD",
                        last = price,
                        open24h = prevClose,
                        high24h = high,
                        low24h = low,
                        quoteVol24h = price * vol
                    )
                )
            }
            if (out.isNotEmpty()) return out
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 回退国内源
        return SinaRwaSource.fetchQuotes(symbols)
    }

    override fun ticker(symbol: String): Ticker {
        return tickerSafe(symbol) ?: SinaRwaSource.ticker(symbol)
    }

    private fun tickerSafe(symbol: String): Ticker? {
        val clean = cleanTicker(symbol)
        val urlStr = "$baseDomain/v8/finance/chart/$clean?interval=1d&range=1d"
        try {
            val jsonStr = httpGet(urlStr)
            val root = JSONObject(jsonStr)
            val chart = root.optJSONObject("chart") ?: return null
            val resArr = chart.optJSONArray("result") ?: return null
            val res = resArr.optJSONObject(0) ?: return null
            val meta = res.optJSONObject("meta") ?: return null

            val price = meta.optDouble("regularMarketPrice", 0.0)
            val prevClose = meta.optDouble("chartPreviousClose", price)
            val high = meta.optDouble("regularMarketDayHigh", price)
            val low = meta.optDouble("regularMarketDayLow", price)
            val vol = meta.optDouble("regularMarketVolume", 0.0)

            val stockName = UsStockSource.getKnownName(clean)
            return Ticker(
                symbol = "$clean-USD",
                last = price,
                open24h = prevClose,
                high24h = high,
                low24h = low,
                quoteVol24h = price * vol,
                displayName = stockName
            )
        } catch (_: Exception) {
            return null
        }
    }

    override fun candles(symbol: String, bar: Bar, limit: Int): List<Candle> {
        val clean = cleanTicker(symbol)
        val interval = when (bar) {
            Bar.M1 -> "1m"
            Bar.M5 -> "5m"
            Bar.M15 -> "15m"
            Bar.M30 -> "30m"
            Bar.H1 -> "60m"
            Bar.H4 -> "60m"
            Bar.D1 -> "1d"
            Bar.W1 -> "1wk"
            Bar.MON1 -> "1mo"
        }

        val range = when (bar) {
            Bar.M1 -> "1d"
            Bar.M5, Bar.M15 -> "5d"
            Bar.M30, Bar.H1 -> "1mo"
            Bar.H4 -> "3mo"
            Bar.D1 -> "1y"
            Bar.W1 -> "2y"
            Bar.MON1 -> "5y"
        }

        val urlStr = "$baseDomain/v8/finance/chart/$clean?interval=$interval&range=$range"
        try {
            val jsonStr = httpGet(urlStr)
            val root = JSONObject(jsonStr)
            val chart = root.optJSONObject("chart") ?: throw MarketException("无图表数据")
            val resArr = chart.optJSONArray("result") ?: throw MarketException("无结果")
            val res = resArr.optJSONObject(0) ?: throw MarketException("数据为空")

            val tsArr = res.optJSONArray("timestamp") ?: throw MarketException("时间戳为空")
            val indicators = res.optJSONObject("indicators") ?: throw MarketException("指标为空")
            val quoteArr = indicators.optJSONArray("quote") ?: throw MarketException("报价为空")
            val quoteObj = quoteArr.optJSONObject(0) ?: throw MarketException("报价对象为空")

            val opens = quoteObj.optJSONArray("open") ?: return emptyList()
            val highs = quoteObj.optJSONArray("high") ?: return emptyList()
            val lows = quoteObj.optJSONArray("low") ?: return emptyList()
            val closes = quoteObj.optJSONArray("close") ?: return emptyList()
            val volumes = quoteObj.optJSONArray("volume")

            val list = mutableListOf<Candle>()
            val len = tsArr.length()

            for (i in 0 until len) {
                val sec = tsArr.optLong(i, 0L)
                val o = opens.optDouble(i, Double.NaN)
                val h = highs.optDouble(i, Double.NaN)
                val l = lows.optDouble(i, Double.NaN)
                val c = closes.optDouble(i, Double.NaN)
                val v = volumes?.optDouble(i, 0.0) ?: 0.0

                if (sec <= 0 || o.isNaN() || h.isNaN() || l.isNaN() || c.isNaN()) continue

                list.add(
                    Candle(
                        ts = sec * 1000L,
                        open = o,
                        high = h,
                        low = l,
                        close = c,
                        vol = if (v.isNaN()) 0.0 else v
                    )
                )
            }

            if (list.isNotEmpty()) {
                return if (list.size > limit) list.takeLast(limit) else list
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 回退国内源
        return SinaRwaSource.candles(symbol, bar, limit)
    }

    private fun httpGet(urlStr: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 10000
        conn.requestMethod = "GET"
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept", "application/json, text/plain, */*")
        conn.setRequestProperty("Accept-Encoding", "gzip")

        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw MarketException("RWA 数据源异常 (HTTP $code)")
            }
            val raw = conn.inputStream
            val stream = if (conn.contentEncoding?.contains("gzip", ignoreCase = true) == true) {
                GZIPInputStream(raw)
            } else {
                raw
            }
            return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
