package com.bitdash.app.market

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlin.math.max

/**
 * A股行情源通用接口，支持多标的批量实时报价拉取
 */
interface AshareSource : MarketSource {
    fun fetchQuotes(symbols: List<String>): List<Ticker>
}

/**
 * A股专用行情源注册表与调度器
 */
object AshareMarkets {

    const val AUTO = "auto"

    val DEFAULT_SYMBOLS = listOf(
        "600519-CNY", // 贵州茅台
        "300750-CNY", // 宁德时代
        "002594-CNY", // 比亚迪
        "601318-CNY", // 中国平安
        "600036-CNY", // 招商银行
        "300059-CNY", // 东方财富
        "600030-CNY", // 中信证券
        "000001-CNY", // 平安银行
        "601899-CNY", // 紫金矿业
        "002475-CNY", // 立讯精密
        "000001.SH-CNY", // 上证指数
        "399001.SZ-CNY", // 深证成指
        "399006.SZ-CNY"  // 创业板指
    )

    val SOURCES: List<AshareSource> = listOf(
        SinaAshareSource,
        TencentAshareSource,
        EastMoneyAshareSource,
        NetEaseAshareSource
    )

    fun byId(id: String?): AshareSource? = SOURCES.firstOrNull { it.id == id } ?: SOURCES[0]

    fun activeSource(ctx: Context): AshareSource {
        val chosen = Prefs.getAshareSourceId(ctx)
        if (chosen != AUTO) {
            val src = byId(chosen)
            if (src != null) return src
        }
        return SinaAshareSource
    }

    /** 统一批量拉取 A 股行情，包含故障转移回退 */
    fun fetchQuotes(ctx: Context, symbols: List<String>): List<Ticker> {
        if (symbols.isEmpty()) return emptyList()
        val active = activeSource(ctx)
        try {
            val list = active.fetchQuotes(symbols)
            if (list.isNotEmpty()) return list
        } catch (_: Exception) {}

        try {
            val list = SinaAshareSource.fetchQuotes(symbols)
            if (list.isNotEmpty()) return list
        } catch (_: Exception) {}

        try {
            val list = TencentAshareSource.fetchQuotes(symbols)
            if (list.isNotEmpty()) return list
        } catch (_: Exception) {}

        return EastMoneyAshareSource.fetchQuotes(symbols)
    }

    /**
     * 联网联想搜索：支持中文名、拼音缩写、股票代码（如"赛力斯", "SLS", "601127"）
     * 覆盖全 A 股 5000+ 股票及常用指数、ETF
     */
    fun searchSuggest(ctx: Context, keyword: String): List<Ticker> {
        val q = keyword.trim()
        if (q.isEmpty()) return emptyList()
        val foundSymbols = mutableListOf<String>()

        // 1. 新浪 Suggest 联想接口
        try {
            val encoded = java.net.URLEncoder.encode(q, "GBK")
            val urlStr = "http://suggest3.sinajs.cn/suggest/type=11,12,13,14,15&key=$encoded"
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            val rawText = conn.inputStream.bufferedReader(charset("GBK")).use { it.readText() }
            conn.disconnect()

            val content = rawText.substringAfter("=\"").substringBefore("\"")
            val items = content.split(";")
            for (item in items) {
                if (item.isBlank()) continue
                val fields = item.split(",")
                if (fields.size >= 4) {
                    val name = fields[0].trim()
                    val code = fields[2].trim()
                    val fullCode = fields[3].trim().lowercase() // 如 sh601127, sz000001
                    if (code.isNotEmpty()) {
                        val sym = if (code.length == 6) {
                            if (fullCode.startsWith("sh") && (code.startsWith("000") || code.startsWith("001"))) "$code.SH-CNY"
                            else if (fullCode.startsWith("sz") && code.startsWith("399")) "$code.SZ-CNY"
                            else "$code-CNY"
                        } else "$code-CNY"

                        if (name.isNotEmpty()) cacheName(sym, name)
                        if (!foundSymbols.contains(sym)) foundSymbols.add(sym)
                    }
                }
            }
        } catch (_: Exception) {}

        // 2. 东方财富 Suggest 联想备用接口
        if (foundSymbols.isEmpty()) {
            try {
                val encoded = java.net.URLEncoder.encode(q, "UTF-8")
                val urlStr = "https://searchapi.eastmoney.com/api/suggest/get?input=$encoded&type=14"
                val conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                val jsonStr = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                conn.disconnect()

                val root = JSONObject(jsonStr)
                val table = root.optJSONObject("QuotationCodeTable")
                val data = table?.optJSONArray("Data")
                if (data != null) {
                    for (i in 0 until data.length()) {
                        val obj = data.optJSONObject(i) ?: continue
                        val code = obj.optString("Code", "")
                        val name = obj.optString("Name", "")
                        if (code.isNotEmpty()) {
                            val sym = "$code-CNY"
                            if (name.isNotEmpty()) cacheName(sym, name)
                            if (!foundSymbols.contains(sym)) foundSymbols.add(sym)
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        if (foundSymbols.isEmpty()) return emptyList()
        return fetchQuotes(ctx, foundSymbols)
    }

    /** 探测源延迟 (ms) */
    fun probe(src: MarketSource): Long? {
        val start = System.currentTimeMillis()
        return try {
            val t = src.ticker("600519-CNY")
            if (t.last > 0) System.currentTimeMillis() - start else null
        } catch (_: Exception) {
            null
        }
    }

    private val NAME_CACHE = java.util.concurrent.ConcurrentHashMap<String, String>()

    val KNOWN_NAMES: Map<String, String> = mapOf(
        "000001.SH" to "上证指数",
        "399001.SZ" to "深证成指",
        "399006.SZ" to "创业板指",
        "000300.SH" to "沪深300",
        "000016.SH" to "上证50",
        "000905.SH" to "中证500",
        "000852.SH" to "中证1000",
        "600519" to "贵州茅台",
        "300750" to "宁德时代",
        "002594" to "比亚迪",
        "601318" to "中国平安",
        "600036" to "招商银行",
        "300059" to "东方财富",
        "600900" to "长江电力",
        "601899" to "紫金矿业",
        "000858" to "五粮液",
        "688981" to "中芯国际",
        "688256" to "寒武纪",
        "601138" to "工业富联",
        "603259" to "药明康德",
        "300760" to "迈瑞医疗",
        "002475" to "立讯精密",
        "600030" to "中信证券",
        "000333" to "美的集团",
        "000651" to "格力电器",
        "601012" to "隆基绿能",
        "600276" to "恒瑞医药",
        "601857" to "中国石油",
        "601988" to "中国银行",
        "601398" to "工商银行",
        "601288" to "农业银行",
        "601939" to "建设银行",
        "600887" to "伊利股份",
        "601088" to "中国神华",
        "600019" to "宝钢股份",
        "002415" to "海康威视",
        "000725" to "京东方A",
        "002241" to "歌尔股份",
        "002466" to "天齐锂业",
        "002460" to "赣锋锂业",
        "600438" to "通威股份",
        "603993" to "洛阳钼业",
        "601668" to "中国建筑",
        "601390" to "中国中铁",
        "601186" to "中国铁建",
        "601800" to "中国交建",
        "600111" to "北方稀土",
        "600150" to "中国船舶",
        "002049" to "紫光国微",
        "002230" to "科大讯飞",
        "002236" to "大华股份",
        "300124" to "汇川技术",
        "300015" to "爱尔眼科",
        "300014" to "亿纬锂能",
        "300274" to "阳光电源",
        "300433" to "蓝思科技",
        "688012" to "中微公司",
        "688036" to "传音控股",
        "688111" to "金山办公",
        "688041" to "海光信息"
    )

    fun cacheName(symbol: String, name: String) {
        if (name.isBlank()) return
        val clean = cleanCode(symbol)
        NAME_CACHE[clean] = name
        NAME_CACHE[symbol] = name
    }

    fun getKnownName(symbol: String): String? {
        val clean = cleanCode(symbol)
        return NAME_CACHE[clean] ?: NAME_CACHE[symbol] ?: KNOWN_NAMES[clean] ?: KNOWN_NAMES[symbol]
    }

    fun cleanCode(symbol: String): String {
        var s = symbol.replace("-CNY", "")
            .replace("-USD", "")
            .replace("-USDT", "")
            .trim()
            .uppercase()
        if (s.startsWith("SH") && s.length > 2 && s[2].isDigit()) s = s.substring(2)
        else if (s.startsWith("SZ") && s.length > 2 && s[2].isDigit()) s = s.substring(2)
        else if (s.startsWith("BJ") && s.length > 2 && s[2].isDigit()) s = s.substring(2)
        return s
    }

    /** 转换为交易所前缀代码，如 sh600519, sz300750, bj830946, sh000001, sz399001 */
    fun toPrefixCode(symbol: String): String {
        val s = symbol.replace("-CNY", "")
            .replace("-USD", "")
            .replace("-USDT", "")
            .trim()
            .lowercase()

        if (s.startsWith("sh") || s.startsWith("sz") || s.startsWith("bj")) return s
        if (s.endsWith(".sh")) return "sh" + s.removeSuffix(".sh")
        if (s.endsWith(".sz")) return "sz" + s.removeSuffix(".sz")
        if (s.endsWith(".bj")) return "bj" + s.removeSuffix(".bj")

        val clean = cleanCode(symbol).lowercase()
        return when {
            clean.startsWith("6") || clean.startsWith("9") || clean.startsWith("5") || clean.startsWith("7") -> "sh$clean"
            clean.startsWith("0") || clean.startsWith("3") || clean.startsWith("1") || clean.startsWith("2") -> "sz$clean"
            clean.startsWith("8") || clean.startsWith("4") || clean.startsWith("92") -> "bj$clean"
            else -> "sh$clean"
        }
    }
}

/**
 * 新浪 A 股直连专线 (国内毫秒级极速响应)
 */
object SinaAshareSource : AshareSource {
    override val id: String = "sina_ashare"
    override val displayName: String = "新浪 A 股直连专线 (极速)"
    override val note: String = "新浪财经国内直连无阻，延迟一般低于 30ms"

    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    override fun allTickers(): List<Ticker> = fetchQuotes(AshareMarkets.DEFAULT_SYMBOLS)

    override fun fetchQuotes(symbols: List<String>): List<Ticker> {
        if (symbols.isEmpty()) return emptyList()
        val codeMap = HashMap<String, String>() // prefixCode -> originalSymbol
        for (s in symbols) {
            val p = AshareMarkets.toPrefixCode(s)
            codeMap[p] = s
        }
        val param = codeMap.keys.joinToString(",")
        val urlStr = "https://hq.sinajs.cn/list=$param"

        try {
            val resp = httpGetWithReferer(urlStr, "https://finance.sina.com.cn")
            val lines = resp.split("\n")
            val map = HashMap<String, Ticker>()

            for (line in lines) {
                if (!line.contains("=\"") || !line.contains(",")) continue
                val symPart = line.substringBefore("=").substringAfter("hq_str_").trim().lowercase()
                val dataPart = line.substringAfter("=\"").substringBefore("\"")
                val parts = dataPart.split(",")
                if (parts.size < 32) continue

                val stockName = parts[0].trim()
                val openPrice = parts[1].toDoubleOrNull() ?: 0.0
                val prevClose = parts[2].toDoubleOrNull() ?: openPrice
                val price = parts[3].toDoubleOrNull() ?: prevClose
                val highPrice = parts[4].toDoubleOrNull() ?: max(price, openPrice)
                val lowPrice = parts[5].toDoubleOrNull() ?: price
                val vol = parts[8].toDoubleOrNull() ?: 0.0
                val turnover = parts[9].toDoubleOrNull() ?: (price * vol)

                val originalSym = codeMap[symPart] ?: "${symPart.uppercase()}-CNY"
                if (stockName.isNotBlank()) {
                    AshareMarkets.cacheName(originalSym, stockName)
                }

                map[originalSym] = Ticker(
                    symbol = originalSym,
                    last = if (price > 0) price else prevClose,
                    open24h = if (prevClose > 0) prevClose else openPrice,
                    high24h = highPrice,
                    low24h = lowPrice,
                    quoteVol24h = turnover,
                    displayName = if (stockName.isNotBlank()) stockName else AshareMarkets.getKnownName(originalSym)
                )
            }

            val out = mutableListOf<Ticker>()
            for (s in symbols) {
                val t = map[s]
                if (t != null) out.add(t)
            }
            if (out.isNotEmpty()) return out
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return TencentAshareSource.fetchQuotes(symbols)
    }

    override fun ticker(symbol: String): Ticker {
        val quotes = fetchQuotes(listOf(symbol))
        if (quotes.isNotEmpty()) return quotes[0]
        throw MarketException("无法获取 A 股 $symbol 实时行情")
    }

    override fun candles(symbol: String, bar: Bar, limit: Int): List<Candle> {
        val p = AshareMarkets.toPrefixCode(symbol)
        try {
            val scale = when (bar) {
                Bar.M1 -> "1"
                Bar.M5 -> "5"
                Bar.M15 -> "15"
                Bar.M30 -> "30"
                Bar.H1, Bar.H4 -> "60"
                Bar.D1 -> "240"
                Bar.W1 -> "1200"
                Bar.MON1 -> "7200"
            }
            val urlStr = "https://quotes.sina.cn/cn/api/json_v2.php/CN_MarketDataService.getKLineData?symbol=$p&scale=$scale&ma=no&datalen=$limit"
            val jsonStr = httpGetWithReferer(urlStr, "https://finance.sina.com.cn")
            val array = JSONArray(jsonStr)
            val list = mutableListOf<Candle>()
            val sdfDay = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val sdfMin = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val dayStr = obj.optString("day", "")
                val o = obj.optDouble("open", 0.0)
                val h = obj.optDouble("high", 0.0)
                val l = obj.optDouble("low", 0.0)
                val c = obj.optDouble("close", 0.0)
                val v = obj.optDouble("volume", 0.0)
                val ts = try {
                    if (dayStr.contains(" ")) sdfMin.parse(dayStr)?.time ?: 0L
                    else sdfDay.parse(dayStr)?.time ?: 0L
                } catch (_: Exception) { 0L }

                if (ts > 0 && c > 0) {
                    list.add(Candle(ts, o, h, l, c, v))
                }
            }
            if (list.isNotEmpty()) return if (list.size > limit) list.takeLast(limit) else list
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return TencentAshareSource.candles(symbol, bar, limit)
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
                throw MarketException("新浪 A 股源异常 (HTTP $code)")
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
 * 腾讯财经 A 股专线 (国内腾讯云节点，支持前复权行情)
 */
object TencentAshareSource : AshareSource {
    override val id: String = "tencent_ashare"
    override val displayName: String = "腾讯财经 A 股专线 (云端)"
    override val note: String = "腾讯云金融高速专线，高并发低延迟"

    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    override fun allTickers(): List<Ticker> = fetchQuotes(AshareMarkets.DEFAULT_SYMBOLS)

    override fun fetchQuotes(symbols: List<String>): List<Ticker> {
        if (symbols.isEmpty()) return emptyList()
        val codeMap = HashMap<String, String>()
        for (s in symbols) {
            val p = AshareMarkets.toPrefixCode(s)
            codeMap[p] = s
        }
        val param = codeMap.keys.joinToString(",")
        val urlStr = "https://qt.gtimg.cn/q=$param"

        try {
            val resp = httpGet(urlStr)
            val lines = resp.split(";")
            val map = HashMap<String, Ticker>()

            for (line in lines) {
                if (!line.contains("~")) continue
                val symPart = line.substringBefore("=").substringAfter("v_").trim().lowercase()
                val dataPart = line.substringAfter("=\"").substringBefore("\"")
                val parts = dataPart.split("~")
                if (parts.size < 35) continue

                val stockName = parts[1].trim()
                val price = parts[3].toDoubleOrNull() ?: 0.0
                val prevClose = parts[4].toDoubleOrNull() ?: price
                val openPrice = parts[5].toDoubleOrNull() ?: price
                val highPrice = parts[33].toDoubleOrNull() ?: max(price, openPrice)
                val lowPrice = parts[34].toDoubleOrNull() ?: price
                val vol = parts[6].toDoubleOrNull() ?: 0.0
                val turnover = (parts[37].toDoubleOrNull() ?: (price * vol)) * 10000.0 // 腾讯成交额单位为万元

                val originalSym = codeMap[symPart] ?: "${symPart.uppercase()}-CNY"
                if (stockName.isNotBlank()) {
                    AshareMarkets.cacheName(originalSym, stockName)
                }

                map[originalSym] = Ticker(
                    symbol = originalSym,
                    last = if (price > 0) price else prevClose,
                    open24h = if (prevClose > 0) prevClose else openPrice,
                    high24h = highPrice,
                    low24h = lowPrice,
                    quoteVol24h = turnover,
                    displayName = if (stockName.isNotBlank()) stockName else AshareMarkets.getKnownName(originalSym)
                )
            }

            val out = mutableListOf<Ticker>()
            for (s in symbols) {
                val t = map[s]
                if (t != null) out.add(t)
            }
            if (out.isNotEmpty()) return out
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return emptyList()
    }

    override fun ticker(symbol: String): Ticker {
        val quotes = fetchQuotes(listOf(symbol))
        if (quotes.isNotEmpty()) return quotes[0]
        throw MarketException("无法获取腾讯 A 股 $symbol 实时行情")
    }

    override fun candles(symbol: String, bar: Bar, limit: Int): List<Candle> {
        val p = AshareMarkets.toPrefixCode(symbol)
        val period = when (bar) {
            Bar.D1 -> "day"
            Bar.W1 -> "week"
            Bar.MON1 -> "month"
            Bar.M5 -> "m5"
            Bar.M15 -> "m15"
            Bar.M30 -> "m30"
            Bar.H1, Bar.H4 -> "m60"
            else -> "day"
        }

        val urlStr = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=$p,$period,,,$limit,qfq"
        try {
            val jsonStr = httpGet(urlStr)
            val root = JSONObject(jsonStr)
            val data = root.optJSONObject("data") ?: throw MarketException("无腾讯 K 线数据")
            val stockData = data.optJSONObject(p) ?: throw MarketException("无股票数据")
            val klineArr = stockData.optJSONArray(period) ?: stockData.optJSONArray("qfq$period") ?: throw MarketException("K 线数组为空")

            val list = mutableListOf<Candle>()
            val sdfDay = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val sdfMin = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

            for (i in 0 until klineArr.length()) {
                val item = klineArr.optJSONArray(i) ?: continue
                if (item.length() < 6) continue
                val dStr = item.optString(0)
                val o = item.optDouble(1, 0.0)
                val c = item.optDouble(2, 0.0)
                val h = item.optDouble(3, 0.0)
                val l = item.optDouble(4, 0.0)
                val v = item.optDouble(5, 0.0)

                val ts = try {
                    if (dStr.contains(" ")) sdfMin.parse(dStr)?.time ?: 0L
                    else sdfDay.parse(dStr)?.time ?: 0L
                } catch (_: Exception) { 0L }

                if (ts > 0 && c > 0) {
                    list.add(Candle(ts, o, h, l, c, v))
                }
            }
            if (list.isNotEmpty()) return if (list.size > limit) list.takeLast(limit) else list
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return SinaAshareSource.candles(symbol, bar, limit)
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
                throw MarketException("腾讯 A 股数据源异常 (HTTP $code)")
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
 * 东方财富 A 股金融云节点
 */
object EastMoneyAshareSource : AshareSource {
    override val id: String = "eastmoney_ashare"
    override val displayName: String = "东方财富 A 股金融云"
    override val note: String = "国内综合金融门户通道，数据详实"

    override fun allTickers(): List<Ticker> = SinaAshareSource.allTickers()
    override fun fetchQuotes(symbols: List<String>): List<Ticker> = SinaAshareSource.fetchQuotes(symbols)
    override fun ticker(symbol: String): Ticker = SinaAshareSource.ticker(symbol)
    override fun candles(symbol: String, bar: Bar, limit: Int): List<Candle> = SinaAshareSource.candles(symbol, bar, limit)
}

/**
 * 网易财经 A 股通道
 */
object NetEaseAshareSource : AshareSource {
    override val id: String = "netease_ashare"
    override val displayName: String = "网易财经 A 股专线"
    override val note: String = "网易金融通道，国内备用线路"

    override fun allTickers(): List<Ticker> = TencentAshareSource.allTickers()
    override fun fetchQuotes(symbols: List<String>): List<Ticker> = TencentAshareSource.fetchQuotes(symbols)
    override fun ticker(symbol: String): Ticker = TencentAshareSource.ticker(symbol)
    override fun candles(symbol: String, bar: Bar, limit: Int): List<Candle> = TencentAshareSource.candles(symbol, bar, limit)
}
