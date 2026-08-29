package com.bitdash.app.market

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import kotlin.math.max

/**
 * 美股代币与美股现货实时行情与 K 线数据源。
 *
 * 数据基于公开全球金融 REST 接口 (Yahoo Finance & 公开镜像)，涵盖：
 * NVDA (英伟达), TSLA (特斯拉), AAPL (苹果), MSFT (微软), COIN (Coinbase),
 * MSTR (微策略), AMD, GOOGL, AMZN, META, PLTR, SPY, QQQ, BITO, IBIT 等全部美股与代币化资产。
 */
object UsStockSource : MarketSource {

    override val id: String = "us_stock"
    override val displayName: String = "美股代币 / 美股行情"
    override val note: String = "涵盖英伟达、特斯拉、苹果、微策等美股代币与现货"

    /** 热门美股代币与大宗商品默认列表 */
    val DEFAULT_SYMBOLS = listOf(
        "NVDA-USD",
        "TSLA-USD",
        "AAPL-USD",
        "MSFT-USD",
        "COIN-USD",
        "MSTR-USD",
        "GLD-USD",
        "SLV-USD",
        "USO-USD",
        "AMD-USD",
        "GOOGL-USD",
        "AMZN-USD",
        "META-USD",
        "PLTR-USD",
        "SPY-USD",
        "QQQ-USD",
        "IBIT-USD",
        "BITO-USD",
        "MARA-USD",
        "RIOT-USD",
        "CLSK-USD",
        "SMCI-USD",
        "ARM-USD",
        "AVGO-USD",
        "INTC-USD",
        "BABA-USD",
        "FUTU-USD",
        "TIGR-USD",
        "NFLX-USD",
        "DIS-USD"
    )

    val KNOWN_NAMES: Map<String, String> = mapOf(
        // 核心科技巨头 (Magnificent 7 & Tech)
        "AAPL" to "苹果",
        "NVDA" to "英伟达",
        "TSLA" to "特斯拉",
        "MSFT" to "微软",
        "GOOGL" to "谷歌A类",
        "GOOG" to "谷歌C类",
        "AMZN" to "亚马逊",
        "META" to "Meta (脸书)",
        "NFLX" to "奈飞",
        "AMD" to "超威半导体",
        "INTC" to "英特尔",
        "QCOM" to "高通",
        "AVGO" to "博通",
        "TXN" to "德州仪器",
        "MU" to "美光科技",
        "ASML" to "阿斯麦",
        "TSM" to "台积电",
        "ARM" to "安谋",
        "SMCI" to "超微电脑",
        "ORCL" to "甲骨文",
        "CRM" to "赛富时",
        "ADBE" to "奥多比",
        "CSCO" to "思科",
        "IBM" to "IBM",
        "NOW" to "ServiceNow",
        "INTU" to "财捷",
        "PLTR" to "Palantir (帕兰提尔)",
        "PANW" to "派拓网络",
        "CRWD" to "CrowdStrike",
        "SNOW" to "Snowflake",
        "UBER" to "优步",
        "ABNB" to "爱彼迎",
        "RBLX" to "Roblox",
        "DIS" to "迪士尼",
        "BA" to "波音",

        // 加密资产概念 / RWA (Crypto Concept & Mining)
        "COIN" to "Coinbase",
        "MSTR" to "微策略 (MicroStrategy)",
        "MARA" to "Marathon Digital",
        "RIOT" to "Riot Platforms",
        "CLSK" to "CleanSpark",
        "HUT" to "Hut 8",
        "BITF" to "Bitfarms",
        "CAN" to "嘉楠科技",
        "IBIT" to "贝莱德比特币现货ETF",
        "BITO" to "ProShares比特币期货ETF",
        "FBTC" to "富达比特币ETF",
        "ARKB" to "ARK比特币ETF",
        "ETHE" to "灰度以太坊信托",
        "GBTC" to "灰度比特币信托",

        // 知名指数 & 热门 ETF (Major Indices & ETFs)
        "SPY" to "标普500 ETF",
        "QQQ" to "纳指100 ETF",
        "DIA" to "道琼斯工业指数 ETF",
        "IWM" to "罗素2000小盘股 ETF",
        "TLT" to "20年+美债 ETF",
        "TQQQ" to "纳指三倍做多 ETF",
        "SQQQ" to "纳指三倍做空 ETF",
        "SOXL" to "半导体三倍做多 ETF",
        "SOXS" to "半导体三倍做空 ETF",
        "GLD" to "黄金 ETF",
        "SLV" to "白银 ETF",
        "USO" to "原油 ETF",
        "VXX" to "波动率恐慌指数 ETF",

        // 传统巨头 / 消费 / 医药 / 金融 (Blue Chips & Consumers)
        "BRK.A" to "伯克希尔A",
        "BRK.B" to "伯克希尔B",
        "BRK" to "伯克希尔哈撒韦",
        "JPM" to "摩根大通",
        "GS" to "高盛",
        "MS" to "摩根士丹利",
        "BAC" to "美国银行",
        "C" to "花旗集团",
        "V" to "维萨 (Visa)",
        "MA" to "万事达 (Mastercard)",
        "PYPL" to "PayPal",
        "WMT" to "沃尔玛",
        "COST" to "开市客 (Costco)",
        "PG" to "宝洁",
        "KO" to "可口可乐",
        "PEP" to "百事可乐",
        "MCD" to "麦当劳",
        "SBUX" to "星巴克",
        "NKE" to "耐克",
        "LLY" to "礼来制药",
        "NVO" to "诺和诺德",
        "JNJ" to "强生",
        "PFE" to "辉瑞制药",
        "UNH" to "联合健康",
        "ABBV" to "艾伯维",
        "MRK" to "默沙东",
        "T" to "AT&T",
        "VZ" to "威瑞森",

        // 热门中概股 (Chinese Concept Stocks)
        "BABA" to "阿里巴巴",
        "PDD" to "拼多多",
        "JD" to "京东",
        "BIDU" to "百度",
        "NTES" to "网易",
        "BILI" to "哔哩哔哩",
        "NIO" to "蔚来汽车",
        "XPEV" to "小鹏汽车",
        "LI" to "理想汽车",
        "TCOM" to "携程网",
        "ZTO" to "中通快递",
        "FUTU" to "富途控股",
        "TIGR" to "老虎证券",
        "YUMC" to "百胜中国",
        "BEKE" to "贝壳",
        "TAL" to "好未来",
        "EDU" to "新东方",
        "VIPS" to "唯品会",
        "IQ" to "爱奇艺",
        "HUYA" to "虎牙",
        "DOYU" to "斗鱼",
        "KC" to "金山云",
        "WB" to "微博",
        "MNSO" to "名创优品",
        "LKNCY" to "瑞幸咖啡"
    )

    private val NAME_CACHE = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun cacheName(symbol: String, name: String) {
        if (name.isBlank()) return
        val clean = cleanTicker(symbol)
        NAME_CACHE[clean] = name
        NAME_CACHE[symbol] = name
    }

    fun getKnownName(symbol: String): String? {
        val clean = cleanTicker(symbol)
        return NAME_CACHE[clean] ?: NAME_CACHE[symbol] ?: KNOWN_NAMES[clean] ?: KNOWN_NAMES[symbol]
    }

    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    fun cleanTicker(symbol: String): String {
        return symbol.replace("-USD", "")
            .replace("-USDT", "")
            .replace("-USDC", "")
            .trim()
            .uppercase()
    }

    override fun allTickers(): List<Ticker> {
        return fetchQuotes(DEFAULT_SYMBOLS)
    }

    /** 批量拉取多个美股行情 */
    fun fetchQuotes(symbols: List<String>): List<Ticker> {
        if (symbols.isEmpty()) return emptyList()
        val cleanList = symbols.map { cleanTicker(it) }.distinct()
        val symParam = cleanList.joinToString(",")

        val urls = listOf(
            "https://query1.finance.yahoo.com/v7/finance/quote?symbols=$symParam",
            "https://query2.finance.yahoo.com/v7/finance/quote?symbols=$symParam"
        )

        for (u in urls) {
            try {
                val jsonStr = httpGet(u)
                val root = JSONObject(jsonStr)
                val quoteResp = root.optJSONObject("quoteResponse") ?: continue
                val resArr = quoteResp.optJSONArray("result") ?: continue

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
        }

        // 备用单币查询聚合
        return cleanList.mapNotNull { tickerSafe(it) }
    }

    override fun ticker(symbol: String): Ticker {
        return tickerSafe(symbol) ?: throw MarketException("无法获取美股 $symbol 实时行情")
    }

    fun tickerSafe(symbol: String): Ticker? {
        val clean = cleanTicker(symbol)
        val urls = listOf(
            "https://query1.finance.yahoo.com/v8/finance/chart/$clean?interval=1d&range=1d",
            "https://query2.finance.yahoo.com/v8/finance/chart/$clean?interval=1d&range=1d"
        )

        for (u in urls) {
            try {
                val jsonStr = httpGet(u)
                val root = JSONObject(jsonStr)
                val chart = root.optJSONObject("chart") ?: continue
                val resArr = chart.optJSONArray("result") ?: continue
                val res = resArr.optJSONObject(0) ?: continue
                val meta = res.optJSONObject("meta") ?: continue

                val price = meta.optDouble("regularMarketPrice", 0.0)
                val prevClose = meta.optDouble("chartPreviousClose", price)
                val high = meta.optDouble("regularMarketDayHigh", price)
                val low = meta.optDouble("regularMarketDayLow", price)
                val vol = meta.optDouble("regularMarketVolume", 0.0)

                return Ticker(
                    symbol = "$clean-USD",
                    last = price,
                    open24h = prevClose,
                    high24h = high,
                    low24h = low,
                    quoteVol24h = price * vol
                )
            } catch (_: Exception) {
            }
        }
        return null
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

        val urls = listOf(
            "https://query1.finance.yahoo.com/v8/finance/chart/$clean?interval=$interval&range=$range",
            "https://query2.finance.yahoo.com/v8/finance/chart/$clean?interval=$interval&range=$range"
        )

        for (u in urls) {
            try {
                val jsonStr = httpGet(u)
                val root = JSONObject(jsonStr)
                val chart = root.optJSONObject("chart") ?: continue
                val resArr = chart.optJSONArray("result") ?: continue
                val res = resArr.optJSONObject(0) ?: continue

                val tsArr = res.optJSONArray("timestamp") ?: continue
                val indicators = res.optJSONObject("indicators") ?: continue
                val quoteArr = indicators.optJSONArray("quote") ?: continue
                val quoteObj = quoteArr.optJSONObject(0) ?: continue

                val opens = quoteObj.optJSONArray("open") ?: continue
                val highs = quoteObj.optJSONArray("high") ?: continue
                val lows = quoteObj.optJSONArray("low") ?: continue
                val closes = quoteObj.optJSONArray("close") ?: continue
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
        }

        try {
            return SinaRwaSource.candles(symbol, bar, limit)
        } catch (_: Exception) {
        }

        throw MarketException("获取美股 $symbol K 线数据失败")
    }

    private fun httpGet(urlStr: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 12000
        conn.requestMethod = "GET"
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept", "application/json, text/plain, */*")
        conn.setRequestProperty("Accept-Encoding", "gzip")

        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw MarketException("美股数据源响应异常 (HTTP $code)")
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
