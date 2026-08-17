package com.bitdash.app.market

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * 各交易所 WebSocket 协议适配。
 *
 * 只接入推送为**纯 JSON 文本**（HTX 额外套一层 gzip）的四家：Gate / OKX / Binance / HTX。
 * 另外两家没接的原因：
 * - MEXC 已把行情推送整体迁到 Protobuf 二进制，旧 JSON 频道服务端直接返回 Blocked；
 * - KuCoin 连接前必须先 POST /api/v1/bullet-public 换 24h 有效期的 token，
 *   且 24h 涨跌数据要订阅 /market/snapshot 而非 /market/ticker。
 * 这两家在实时模式下由 [Realtimes.protocolFor] 返回 null，页面自动回退到定时轮询。
 */
object Realtimes {

    /** 该行情源是否支持实时推送 */
    fun supports(sourceId: String?): Boolean = protocolFor(sourceId) != null

    /** 取该源的协议适配器；返回 null 表示不支持，调用方须回退到轮询 */
    fun protocolFor(sourceId: String?): RtProtocol? = when (sourceId) {
        "gate" -> GateRt
        "huobi" -> HuobiRt
        "binance" -> BinanceRt
        "okx" -> OkxRt
        "okx_aws" -> OkxAwsRt
        else -> null
    }
}

/** 自增报文 id，各协议共用 */
private val seq = AtomicInteger(1)

// ==================== Gate.io ====================

/**
 * Gate.io 现货 v4：wss://api.gateio.ws/ws/v4/
 *
 * 一条连接可同时承载 spot.tickers 与 spot.candlesticks。
 * 坑点：
 * - candlesticks 的 payload 顺序是 [周期, 交易对]，与直觉相反；
 * - K 线里 v = 计价币成交额、a = 基础币成交量，命名和字面意思相反；
 * - ticker 不给开盘价，只给 change_percentage，需要反推（与 [GateSource] 一致）。
 */
private object GateRt : RtProtocol {

    private const val URL = "wss://api.gateio.ws/ws/v4/"

    override fun urlFor(sub: RtSub): String = URL

    override fun subscribeFrames(sub: RtSub): List<String> = listOf(frame(sub, "subscribe"))

    override fun unsubscribeFrames(sub: RtSub): List<String> = listOf(frame(sub, "unsubscribe"))

    private fun frame(sub: RtSub, event: String): String {
        val o = JSONObject()
        o.put("time", System.currentTimeMillis() / 1000L)
        o.put("event", event)
        when (sub) {
            is RtSub.Tickers -> {
                o.put("channel", "spot.tickers")
                o.put("payload", JSONArray().apply {
                    sub.symbols.forEach { put(Symbols.underscore(it)) }
                })
            }
            is RtSub.Kline -> {
                o.put("channel", "spot.candlesticks")
                // 顺序固定为 [周期, 交易对]
                o.put("payload", JSONArray().apply {
                    put(barKey(sub.bar))
                    put(Symbols.underscore(sub.symbol))
                })
            }
        }
        return o.toString()
    }

    override fun pingFrame(): String =
        JSONObject().put("time", System.currentTimeMillis() / 1000L)
            .put("channel", "spot.ping").toString()

    override val pingIntervalMs: Long = 10_000L

    override fun handle(text: String, out: RtOut) {
        val m = text.asJsonObject() ?: return
        if (m.optString("event") != "update") return   // subscribe 应答 / pong 都忽略
        when (m.optString("channel")) {
            "spot.tickers" -> m.optJSONObject("result")?.let { parseTicker(it, out) }
            "spot.candlesticks" -> m.optJSONObject("result")?.let { parseKline(it, out) }
        }
    }

    private fun parseTicker(r: JSONObject, out: RtOut) {
        val symbol = Symbols.expand(r.optString("currency_pair")) ?: return
        val last = r.num("last")
        if (last <= 0.0) return
        val pct = r.num("change_percentage")
        out.emit(
            RtEvent.TickerUpdate(
                Ticker(
                    symbol = symbol,
                    last = last,
                    open24h = if (pct != 0.0) last / (1.0 + pct / 100.0) else last,
                    high24h = r.num("high_24h"),
                    low24h = r.num("low_24h"),
                    quoteVol24h = r.num("quote_volume")
                )
            )
        )
    }

    private fun parseKline(r: JSONObject, out: RtOut) {
        // n = "15m_BTC_USDT"：周期与交易对都编码在这里
        val name = r.optString("n")
        val sepIdx = name.indexOf('_')
        if (sepIdx <= 0) return
        val bar = barOfKey(name.substring(0, sepIdx)) ?: return
        val symbol = Symbols.expand(name.substring(sepIdx + 1)) ?: return

        val sec = r.optString("t").toLongOrNull() ?: return
        if (sec <= 0L) return
        out.emit(
            RtEvent.KlineUpdate(
                symbol = symbol,
                bar = bar,
                candle = Candle(
                    ts = sec * 1000L,
                    open = r.num("o"),
                    high = r.num("h"),
                    low = r.num("l"),
                    close = r.num("c"),
                    vol = r.num("a")   // a = 基础币成交量
                ),
                closed = r.optBoolean("w", false)
            )
        )
    }

    /** 与 [GateSource] 的 REST 周期名保持一致 */
    private fun barKey(bar: Bar): String = when (bar) {
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

    private fun barOfKey(key: String): Bar? = Bar.entries.firstOrNull { barKey(it) == key }
}

// ==================== OKX ====================

/**
 * OKX v5。注意 **K 线与 ticker 不在同一个端点**：
 *   tickers      → /ws/v5/public
 *   candle{bar}  → /ws/v5/business   （2023-06-20 起迁移）
 * 因此图表页在 OKX 下会开两条连接，由 [RealtimeSession] 按 url 自动分组。
 *
 * 心跳是裸字符串 "ping"（不是 JSON），且 30 秒无任何数据下发就会被断开。
 */
private open class OkxRtBase(private val host: String) : RtProtocol {

    override fun urlFor(sub: RtSub): String = when (sub) {
        is RtSub.Tickers -> "wss://$host:8443/ws/v5/public"
        is RtSub.Kline -> "wss://$host:8443/ws/v5/business"
    }

    override fun subscribeFrames(sub: RtSub): List<String> = listOf(frame(sub, "subscribe"))

    override fun unsubscribeFrames(sub: RtSub): List<String> = listOf(frame(sub, "unsubscribe"))

    private fun frame(sub: RtSub, op: String): String {
        val args = JSONArray()
        when (sub) {
            is RtSub.Tickers -> sub.symbols.forEach {
                args.put(JSONObject().put("channel", "tickers").put("instId", Symbols.hyphen(it)))
            }
            is RtSub.Kline -> args.put(
                JSONObject()
                    .put("channel", "candle${sub.bar.key}")
                    .put("instId", Symbols.hyphen(sub.symbol))
            )
        }
        return JSONObject().put("id", seq.getAndIncrement().toString())
            .put("op", op).put("args", args).toString()
    }

    /** 裸字符串，服务端回同样裸的 "pong" */
    override fun pingFrame(): String = "ping"

    override val pingIntervalMs: Long = 15_000L

    override fun handle(text: String, out: RtOut) {
        if (text == "pong") return
        val m = text.asJsonObject() ?: return
        if (m.optString("event").isNotEmpty()) return   // subscribe / error 应答
        val arg = m.optJSONObject("arg") ?: return
        val data = m.optJSONArray("data") ?: return
        val channel = arg.optString("channel")

        if (channel == "tickers") {
            for (i in 0 until data.length()) {
                val o = data.optJSONObject(i) ?: continue
                val symbol = o.optString("instId")
                if (symbol.isEmpty()) continue
                val last = o.num("last")
                if (last <= 0.0) continue
                out.emit(
                    RtEvent.TickerUpdate(
                        Ticker(
                            symbol = symbol,
                            last = last,
                            open24h = o.num("open24h"),
                            high24h = o.num("high24h"),
                            low24h = o.num("low24h"),
                            quoteVol24h = o.num("volCcy24h")
                        )
                    )
                )
            }
            return
        }

        if (!channel.startsWith("candle")) return
        val bar = Bar.entries.firstOrNull { it.key == channel.removePrefix("candle") } ?: return
        val symbol = arg.optString("instId")
        if (symbol.isEmpty()) return
        for (i in 0 until data.length()) {
            val a = data.optJSONArray(i) ?: continue
            if (a.length() < 6) continue
            val ts = a.longAt(0)
            if (ts <= 0L) continue
            out.emit(
                RtEvent.KlineUpdate(
                    symbol = symbol,
                    bar = bar,
                    candle = Candle(ts, a.numAt(1), a.numAt(2), a.numAt(3), a.numAt(4), a.numAt(5)),
                    // [8] confirm：0 未收盘 / 1 已收盘
                    closed = a.length() > 8 && a.optString(8) == "1"
                )
            )
        }
    }
}

private object OkxRt : OkxRtBase("ws.okx.com")
private object OkxAwsRt : OkxRtBase("wsaws.okx.com")

// ==================== Binance 行情镜像 ====================

/**
 * Binance 公开行情镜像的 WS 端点：wss://data-stream.binance.vision
 * （与 REST 的 data-api.binance.vision 对应，只读、无需鉴权，主站 WS 同样会被地区限制）。
 *
 * 连 /stream 后用 SUBSCRIBE 动态增删流，推送外面套一层 {"stream":…,"data":…}。
 * 心跳走 WS 协议层 ping/pong，OkHttp 自动应答，无需应用层报文。
 */
private object BinanceRt : RtProtocol {

    private const val URL = "wss://data-stream.binance.vision:443/stream"

    override fun urlFor(sub: RtSub): String = URL

    override fun subscribeFrames(sub: RtSub): List<String> = listOf(frame(sub, "SUBSCRIBE"))

    override fun unsubscribeFrames(sub: RtSub): List<String> = listOf(frame(sub, "UNSUBSCRIBE"))

    private fun frame(sub: RtSub, method: String): String {
        val params = JSONArray()
        when (sub) {
            // 流名里的交易对必须小写
            is RtSub.Tickers -> sub.symbols.forEach { params.put("${Symbols.lower(it)}@ticker") }
            is RtSub.Kline -> params.put("${Symbols.lower(sub.symbol)}@kline_${barKey(sub.bar)}")
        }
        return JSONObject().put("method", method).put("params", params)
            .put("id", seq.getAndIncrement()).toString()
    }

    override fun handle(text: String, out: RtOut) {
        val m = text.asJsonObject() ?: return
        // 组合流外层包装；直连单流时没有这层，两种都兼容
        val d = m.optJSONObject("data") ?: m
        when (d.optString("e")) {
            "24hrTicker" -> parseTicker(d, out)
            "kline" -> parseKline(d, out)
        }
    }

    private fun parseTicker(d: JSONObject, out: RtOut) {
        val symbol = Symbols.expand(d.optString("s")) ?: return
        val last = d.num("c")
        if (last <= 0.0) return
        out.emit(
            RtEvent.TickerUpdate(
                Ticker(
                    symbol = symbol,
                    last = last,
                    open24h = d.num("o"),
                    high24h = d.num("h"),
                    low24h = d.num("l"),
                    quoteVol24h = d.num("q")
                )
            )
        )
    }

    private fun parseKline(d: JSONObject, out: RtOut) {
        val symbol = Symbols.expand(d.optString("s")) ?: return
        val k = d.optJSONObject("k") ?: return
        val bar = barOfKey(k.optString("i")) ?: return
        val ts = k.optLong("t", 0L)
        if (ts <= 0L) return
        out.emit(
            RtEvent.KlineUpdate(
                symbol = symbol,
                bar = bar,
                candle = Candle(
                    ts = ts,
                    open = k.num("o"),
                    high = k.num("h"),
                    low = k.num("l"),
                    close = k.num("c"),
                    vol = k.num("v")   // v = 基础币成交量
                ),
                closed = k.optBoolean("x", false)
            )
        )
    }

    /** 与镜像站 REST 的 interval 一致：周线小写 1w，月线大写 1M */
    private fun barKey(bar: Bar): String = when (bar) {
        Bar.M1 -> "1m"
        Bar.M5 -> "5m"
        Bar.M15 -> "15m"
        Bar.M30 -> "30m"
        Bar.H1 -> "1h"
        Bar.H4 -> "4h"
        Bar.D1 -> "1d"
        Bar.W1 -> "1w"
        Bar.MON1 -> "1M"
    }

    /** 注意大小写敏感："1m" 是分钟，"1M" 是月 */
    private fun barOfKey(key: String): Bar? = Bar.entries.firstOrNull { barKey(it) == key }
}

// ==================== HTX（火币） ====================

/**
 * HTX：wss://api.huobi.pro/ws
 *
 * 两个特殊点：
 * - **所有下行帧都是 gzip 压缩的二进制**，解压后才是 JSON（[gzipped] = true）；
 * - 心跳是**服务端主动发** {"ping":N}，客户端必须回 {"pong":N} 且数值一致；
 *   实测间隔 1~3 秒不定，所以只能收到就回，不能自己定时发。
 *
 * 字段语义同 [HuobiSource]：close = 最新价，vol = 计价币成交额，amount = 基础币成交量。
 */
private object HuobiRt : RtProtocol {

    private const val URL = "wss://api.huobi.pro/ws"

    override val gzipped: Boolean = true

    override fun urlFor(sub: RtSub): String = URL

    override fun subscribeFrames(sub: RtSub): List<String> = topics(sub).map {
        JSONObject().put("sub", it).put("id", seq.getAndIncrement().toString()).toString()
    }

    override fun unsubscribeFrames(sub: RtSub): List<String> = topics(sub).map {
        JSONObject().put("unsub", it).put("id", seq.getAndIncrement().toString()).toString()
    }

    /** HTX 无批量订阅语法，多个交易对拆成多帧 */
    private fun topics(sub: RtSub): List<String> = when (sub) {
        is RtSub.Tickers -> sub.symbols.map { "market.${Symbols.lower(it)}.detail" }
        is RtSub.Kline -> listOf("market.${Symbols.lower(sub.symbol)}.kline.${barKey(sub.bar)}")
    }

    /** 服务端主动 ping，不需要客户端定时心跳 */
    override fun pingFrame(): String? = null

    override fun handle(text: String, out: RtOut) {
        val m = text.asJsonObject() ?: return

        // 心跳：必须原值回 pong，否则会被断开
        if (m.has("ping")) {
            out.send(JSONObject().put("pong", m.optLong("ping")).toString())
            return
        }

        val ch = m.optString("ch")
        if (ch.isEmpty()) return   // subscribe 应答
        val tick = m.optJSONObject("tick") ?: return

        // ch 形如 market.btcusdt.detail 或 market.btcusdt.kline.15min
        val parts = ch.split('.')
        if (parts.size < 3) return
        val symbol = Symbols.expand(parts[1]) ?: return

        if (parts[2] == "detail") {
            val last = tick.num("close")
            if (last <= 0.0) return
            out.emit(
                RtEvent.TickerUpdate(
                    Ticker(
                        symbol = symbol,
                        last = last,
                        open24h = tick.num("open"),
                        high24h = tick.num("high"),
                        low24h = tick.num("low"),
                        quoteVol24h = tick.num("vol")
                    )
                )
            )
            return
        }

        if (parts[2] != "kline" || parts.size < 4) return
        val bar = barOfKey(parts[3]) ?: return
        val sec = tick.optLong("id", 0L)
        if (sec <= 0L) return
        out.emit(
            RtEvent.KlineUpdate(
                symbol = symbol,
                bar = bar,
                candle = Candle(
                    ts = sec * 1000L,
                    open = tick.num("open"),
                    high = tick.num("high"),
                    low = tick.num("low"),
                    close = tick.num("close"),
                    vol = tick.num("amount")   // amount = 基础币成交量
                ),
                // HTX K 线推送不带收盘标记，一律按未收盘处理（下一根 ts 变化时自然追加）
                closed = false
            )
        )
    }

    private fun barKey(bar: Bar): String = when (bar) {
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

    private fun barOfKey(key: String): Bar? = Bar.entries.firstOrNull { barKey(it) == key }
}

// ==================== 共用小工具 ====================

/** 宽松解析：非 JSON（如 OKX 的裸 "pong"）返回 null 而不是抛异常 */
private fun String.asJsonObject(): JSONObject? {
    val s = trim()
    if (s.isEmpty() || s[0] != '{') return null
    return try {
        JSONObject(s)
    } catch (_: Exception) {
        null
    }
}
