package com.bitdash.app.market

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * 页面级的实时行情会话。
 *
 * 一个 Activity 持有一个 Session，生命周期与页面一致（onStart 开、onStop 停）。
 * 内部把订阅按 WS 地址分组，每组一条连接——OKX 的 tickers 与 candle 分属两个端点，
 * 会自然变成两条；Gate / Binance / HTX 则一条连接承载全部订阅。
 *
 * 线程模型：
 * - 所有公开方法必须在主线程调用；
 * - OkHttp 的回调在它自己的线程，这里统一 post 回主线程后再解析和分发，
 *   因此 [RtProtocol] 实现与 [onEvent] 回调都只会在主线程执行，页面可以直接改 UI。
 *
 * 与轮询的关系：本类只负责推送。掉线时通过 [onState] 通知页面（connected = false），
 * 由页面自己恢复定时轮询，避免出现"连不上就没数据"。
 */
class RealtimeSession(
    private val ctx: Context,
    /** 行情事件（主线程） */
    private val onEvent: (RtEvent) -> Unit,
    /** 连接状态变化（主线程）：true = 至少有一条连接在正常收数据 */
    private val onState: (Boolean) -> Unit
) {

    private val main = Handler(Looper.getMainLooper())

    /** 当前生效的协议；null 表示当前源不支持实时（调用方应回退轮询） */
    private var protocol: RtProtocol? = null

    /** 建立本次连接时所用的源 id，用于发现用户中途换源 */
    private var boundSourceId: String? = null

    /** 期望的订阅集合；断线重连后按它重放 */
    private var subs: List<RtSub> = emptyList()

    /** url → 连接。按地址分组，同址订阅复用一条连接 */
    private val conns = HashMap<String, Conn>()

    /** 对外汇报过的状态，避免重复回调 */
    private var reportedConnected = false

    // ---------- 对外 API ----------

    /**
     * 当前设置下本页面能否用实时推送。
     * @param scopeCheck 传入 [RtScope.forWatch] 或 [RtScope.forChart]
     */
    fun available(scopeCheck: (RtScope) -> Boolean): Boolean {
        if (!scopeCheck(Prefs.getRtScope(ctx))) return false
        return Realtimes.supports(Markets.activeSourceId(ctx))
    }

    /**
     * 启动或更新订阅。可反复调用（自选增删、切换周期都走这里）：
     * 订阅内容没变时不做任何事，变了则只重建受影响的连接。
     */
    fun start(newSubs: List<RtSub>) {
        val sourceId = Markets.activeSourceId(ctx)
        val proto = Realtimes.protocolFor(sourceId)
        if (proto == null) {
            stop()
            return
        }

        // 换源意味着换协议与地址，旧连接全部作废
        if (boundSourceId != sourceId) {
            closeAll()
            boundSourceId = sourceId
            protocol = proto
        }

        if (subs == newSubs && conns.isNotEmpty()) return
        subs = newSubs
        reconcile()
    }

    /** 停止全部连接；页面 onStop / 用户关闭实时时调用 */
    fun stop() {
        subs = emptyList()
        closeAll()
        report(false)
    }

    /** 是否至少有一条连接已就绪 */
    fun isConnected(): Boolean = conns.values.any { it.opened }

    // ---------- 连接编排 ----------

    /** 按 url 把订阅重新分组，关掉不再需要的连接、建立新的 */
    private fun reconcile() {
        val proto = protocol ?: return
        val wanted = HashMap<String, MutableList<RtSub>>()
        for (s in subs) {
            val url = proto.urlFor(s) ?: continue   // 协议不支持这种订阅：跳过，由轮询兜底
            wanted.getOrPut(url) { ArrayList() }.add(s)
        }

        // 关掉已不需要的地址
        conns.keys.toList().forEach { url ->
            if (!wanted.containsKey(url)) conns.remove(url)?.close()
        }

        wanted.forEach { (url, list) ->
            val existing = conns[url]
            if (existing == null) {
                conns[url] = Conn(url, list, proto).also { it.connect() }
            } else {
                existing.updateSubs(list)
            }
        }
    }

    private fun closeAll() {
        conns.values.forEach { it.close() }
        conns.clear()
    }

    private fun report(connected: Boolean) {
        if (reportedConnected == connected) return
        reportedConnected = connected
        onState(connected)
    }

    /** 任一连接状态变化后重算总状态 */
    private fun refreshState() {
        report(conns.values.any { it.opened })
    }

    // ---------- 单条连接 ----------

    private inner class Conn(
        private val url: String,
        private var mySubs: List<RtSub>,
        private val proto: RtProtocol
    ) : RtOut {

        private var ws: WebSocket? = null
        private var closed = false
        private var retry = 0

        /** 已收到 onOpen 且未掉线 */
        var opened = false
            private set

        private val pingTask = object : Runnable {
            override fun run() {
                val frame = proto.pingFrame() ?: return
                if (closed) return
                ws?.send(frame)
                main.postDelayed(this, proto.pingIntervalMs)
            }
        }

        private val reconnectTask = Runnable { if (!closed) connect() }

        fun connect() {
            if (closed) return
            ws?.cancel()
            val req = Request.Builder().url(url).build()
            ws = client.newWebSocket(req, Listener())
        }

        fun updateSubs(newSubs: List<RtSub>) {
            val added = newSubs.filter { it !in mySubs }
            val removed = mySubs.filter { it !in newSubs }
            mySubs = newSubs
            val socket = ws ?: return
            if (!opened) return   // 还没连上，onOpen 时会按最新 mySubs 全量订阅
            removed.forEach { s -> proto.unsubscribeFrames(s).forEach { socket.send(it) } }
            added.forEach { s -> proto.subscribeFrames(s).forEach { socket.send(it) } }
        }

        fun close() {
            closed = true
            opened = false
            main.removeCallbacks(pingTask)
            main.removeCallbacks(reconnectTask)
            // 用 cancel 而非 close：不必等服务端回 close 帧，页面切走时要立刻释放
            ws?.cancel()
            ws = null
        }

        // ---------- RtOut ----------

        override fun send(frame: String) {
            ws?.send(frame)
        }

        override fun emit(event: RtEvent) {
            if (!closed) onEvent(event)
        }

        // ---------- OkHttp 回调（非主线程） ----------

        private inner class Listener : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                main.post {
                    if (closed || ws !== webSocket) return@post
                    opened = true
                    retry = 0
                    mySubs.forEach { s -> proto.subscribeFrames(s).forEach { webSocket.send(it) } }
                    if (proto.pingFrame() != null) {
                        main.removeCallbacks(pingTask)
                        main.postDelayed(pingTask, proto.pingIntervalMs)
                    }
                    refreshState()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                main.post {
                    if (closed || ws !== webSocket) return@post
                    dispatch(text)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // HTX 的下行帧是 gzip 二进制；解压放在 OkHttp 线程做，避免占用主线程
                val text = if (proto.gzipped) gunzip(bytes) else bytes.utf8()
                if (text == null) return
                main.post {
                    if (closed || ws !== webSocket) return@post
                    dispatch(text)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                main.post { if (ws === webSocket) scheduleReconnect() }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                main.post { if (ws === webSocket) scheduleReconnect() }
            }
        }

        private fun dispatch(text: String) {
            try {
                proto.handle(text, this)
            } catch (_: Exception) {
                // 单条报文解析失败不能拖垮连接，丢弃即可
            }
        }

        /** 指数退避重连：2s → 4s → 8s …… 上限 30s */
        private fun scheduleReconnect() {
            if (closed) return
            opened = false
            main.removeCallbacks(pingTask)
            refreshState()

            val delay = (RECONNECT_BASE_MS shl retry.coerceAtMost(4)).coerceAtMost(RECONNECT_MAX_MS)
            retry++
            main.removeCallbacks(reconnectTask)
            main.postDelayed(reconnectTask, delay)
        }
    }

    companion object {

        private const val RECONNECT_BASE_MS = 2_000L
        private const val RECONNECT_MAX_MS = 30_000L

        /**
         * 全进程共用一个 OkHttpClient（连接池与线程池复用）。
         *
         * pingInterval 让 OkHttp 自动发 WS 协议层 ping：Binance 靠它保活，
         * 同时也能让 OKX/Gate 更快发现半开连接（对端不回 pong 会触发 onFailure）。
         */
        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }

        private fun gunzip(bytes: ByteString): String? = try {
            GZIPInputStream(ByteArrayInputStream(bytes.toByteArray()))
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Exception) {
            null
        }
    }
}
