package com.bitdash.app.market

/**
 * WebSocket 实时行情的公共模型。
 *
 * 与 REST 层（[MarketSource]）的关系：
 * - REST 负责"取快照"：进页面的首屏、K 线历史、下拉刷新，仍然全部走 REST。
 * - WS 只负责"追增量"：连上之后由交易所推送最新价与最新一根 K 线，
 *   页面据此停掉轮询；一旦掉线立刻回退到原有的定时轮询，不会出现无数据状态。
 *
 * 交易对与周期沿用 App 内部统一格式（BASE-QUOTE / [Bar]），由各协议适配器翻译。
 */

/** 实时行情的生效范围（用户在设置里选，[Prefs] 持久化） */
enum class RtScope(val key: String) {
    /** 关闭，全部走定时轮询 */
    OFF("off"),
    /** 图表页 + 自选列表都用实时推送 */
    ALL("all"),
    /** 只有图表页用实时推送 */
    CHART("chart"),
    /** 只有自选列表用实时推送 */
    WATCH("watch");

    /** 自选列表是否启用实时 */
    val forWatch: Boolean get() = this == ALL || this == WATCH

    /** 图表页是否启用实时 */
    val forChart: Boolean get() = this == ALL || this == CHART

    companion object {
        fun fromKey(key: String?): RtScope = entries.firstOrNull { it.key == key } ?: OFF
    }
}

/** 一个订阅需求。同一页面可以同时有多个（图表页 = 1 个 ticker + 1 个 kline） */
sealed class RtSub {

    /** 批量最新价（自选列表 / 图表页顶部行情栏） */
    data class Tickers(val symbols: List<String>) : RtSub()

    /** 单个交易对的实时 K 线 */
    data class Kline(val symbol: String, val bar: Bar) : RtSub()
}

/** 推送事件，由 [RealtimeSession] 切回主线程后回调给页面 */
sealed class RtEvent {

    data class TickerUpdate(val ticker: Ticker) : RtEvent()

    /**
     * 最新一根 K 线的增量。
     * @param closed 该根是否已收盘；true 表示下一次推送会是新的一根
     */
    data class KlineUpdate(
        val symbol: String,
        val bar: Bar,
        val candle: Candle,
        val closed: Boolean
    ) : RtEvent()
}

/** 协议适配器解析消息时的输出通道 */
interface RtOut {
    /** 回发一帧文本（心跳应答、二次订阅等） */
    fun send(frame: String)
    /** 抛出一个解析好的行情事件 */
    fun emit(event: RtEvent)
}

/**
 * 单个交易所的 WebSocket 协议适配器。
 *
 * 实现者只关心"报文长什么样"，连接、重连、心跳定时、gzip 解压、线程切换
 * 统一由 [RealtimeSession] 处理。所有方法都在主线程调用，不要做阻塞操作。
 */
interface RtProtocol {

    /**
     * 该订阅走哪个 WS 地址；返回 null 表示本协议不支持这种订阅。
     *
     * 允许不同订阅落在不同地址上（OKX 的 K 线必须走 /ws/v5/business，
     * 与 tickers 的 /ws/v5/public 是两条独立连接）。
     */
    fun urlFor(sub: RtSub): String?

    /** 连接建立（或重连）后要发出的订阅报文 */
    fun subscribeFrames(sub: RtSub): List<String>

    /** 取消订阅报文；协议不支持退订时返回空列表（由重建连接兜底） */
    fun unsubscribeFrames(sub: RtSub): List<String>

    /**
     * 客户端主动心跳报文；返回 null 表示不需要应用层心跳
     * （靠 WS 协议层 ping/pong 或服务端主动 ping 即可）。
     */
    fun pingFrame(): String? = null

    /** [pingFrame] 的发送间隔 */
    val pingIntervalMs: Long get() = 20_000L

    /** 推送帧是否为 gzip 压缩的二进制（HTX 是这样） */
    val gzipped: Boolean get() = false

    /** 解析一条已解压的文本消息 */
    fun handle(text: String, out: RtOut)
}
