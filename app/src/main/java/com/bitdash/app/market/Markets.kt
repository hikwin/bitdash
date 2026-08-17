package com.bitdash.app.market

import android.content.Context

/**
 * 行情源注册表与调度器。
 *
 * 两种工作模式（用户在设置里切换，[Prefs] 持久化）：
 * - AUTO（默认）：按 [SOURCES] 顺序逐个尝试，第一个成功的源被记忆下来优先使用；
 *   当它连续失败时自动切到下一个。这样内地网络无需用户手动配置就能用。
 * - 指定源：只用用户选定的那一个，失败就报错（便于用户明确知道某源不通）。
 *
 * 顺序依据在本机的实测结果：Gate、MEXC、HTX、KuCoin 在内地网络通常可直连，
 * 故排在前面；OKX 币种最全但内地常需代理；api.binance.com 会返回 451（地区限制），
 * 因此改用其公开只读镜像 data-api.binance.vision。
 */
object Markets {

    /** 自动模式的标识，与真实源 id 区分 */
    const val AUTO = "auto"

    /**
     * 可选源列表，顺序即自动模式的尝试优先级。
     * id 一经发布不要修改，否则用户已保存的选择会失效。
     */
    val SOURCES: List<MarketSource> = listOf(
        GateSource(
            id = "gate",
            displayName = "Gate.io",
            note = "内地网络通常可直连"
        ),
        BinanceLikeSource(
            id = "mexc",
            displayName = "MEXC",
            note = "内地网络通常可直连",
            base = "https://api.mexc.com",
            weekKey = "1W",
            monthKey = "1M"
        ),
        HuobiSource(
            id = "huobi",
            displayName = "HTX（火币）",
            note = "内地网络通常可直连"
        ),
        KucoinSource(
            id = "kucoin",
            displayName = "KuCoin",
            note = "备用源，多数网络可用"
        ),
        BinanceLikeSource(
            id = "binance",
            displayName = "Binance 行情镜像",
            note = "data-api.binance.vision 公开只读节点",
            base = "https://data-api.binance.vision",
            weekKey = "1w",
            monthKey = "1M"
        ),
        OkxSource(
            id = "okx",
            displayName = "OKX",
            note = "币种最全，内地可能需要代理",
            domain = "https://www.okx.com"
        ),
        OkxSource(
            id = "okx_aws",
            displayName = "OKX AWS 节点",
            note = "OKX 备用节点",
            domain = "https://aws.okx.com"
        )
    )

    fun byId(id: String?): MarketSource? = SOURCES.firstOrNull { it.id == id }

    /**
     * 当前实际生效的源 id；无法确定时返回 null。
     *
     * WebSocket 层需要它来选协议：自动模式下用户选的是 "auto"，
     * 真正在用哪家要看 REST 最近一次成功的结果（[lastUsedId]），
     * 冷启动还没取过数时退回磁盘上记住的 [Prefs.getPreferredId]。
     */
    fun activeSourceId(ctx: Context): String? {
        val chosen = Prefs.getSourceId(ctx)
        if (chosen != AUTO) return chosen
        return lastUsedId ?: Prefs.getPreferredId(ctx)
    }

    /** 自动模式下当前优先使用的源 id；null 表示还未探测出可用源 */
    @Volatile
    private var preferredId: String? = null

    /** preferredId 是否已从磁盘载入过（进程内只需读一次） */
    @Volatile
    private var preferredLoaded = false

    /** 最近一次实际取数成功的源，用于界面上显示"当前源" */
    @Volatile
    var lastUsedId: String? = null
        private set

    /**
     * 按当前设置执行一次取数，自动模式下会依次故障转移。
     *
     * @param block 对某个源执行的具体请求
     * @throws MarketException 所有候选源都失败时抛出，消息汇总首个源的失败原因
     */
    fun <T> withSource(ctx: Context, block: (MarketSource) -> T): T {
        val chosen = Prefs.getSourceId(ctx)

        // 指定源：不做转移，让用户看到真实错误
        if (chosen != AUTO) {
            val src = byId(chosen)
                ?: throw MarketException("行情源已失效，请在设置中重新选择")
            val r = block(src)
            lastUsedId = src.id
            return r
        }

        // 上次成功的源持久化在磁盘，冷启动直接命中，避免又从被墙的源开始试
        if (!preferredLoaded) {
            preferredId = Prefs.getPreferredId(ctx)
            preferredLoaded = true
        }

        // 自动模式：优先试上次成功的源，再按注册顺序兜底
        val pid = preferredId
        val ordered = ArrayList<MarketSource>(SOURCES.size)
        pid?.let { byId(it)?.let { s -> ordered.add(s) } }
        SOURCES.forEach { if (it.id != pid) ordered.add(it) }

        var firstError: Exception? = null
        for (src in ordered) {
            try {
                val r = block(src)
                if (src.id != pid) {
                    // 只在真的换源时落盘，避免每次请求都写 SharedPreferences
                    preferredId = src.id
                    Prefs.savePreferredId(ctx, src.id)
                }
                lastUsedId = src.id
                return r
            } catch (e: Exception) {
                if (firstError == null) firstError = e
                // 换下一个源继续尝试
            }
        }
        throw MarketException(
            "所有行情源均不可用：${firstError?.message ?: "网络异常"}", firstError
        )
    }

    /** 探测所有源的可用性，返回 id → 延迟毫秒（失败为 null）。用于设置页展示 */
    fun probe(source: MarketSource): Long? {
        val t0 = System.currentTimeMillis()
        return try {
            source.ticker("BTC-USDT")
            System.currentTimeMillis() - t0
        } catch (_: Exception) {
            null
        }
    }

    /** 用户手动切源时清空自动模式的记忆，避免继续粘在旧源上 */
    fun resetPreferred() {
        preferredId = null
        preferredLoaded = true
        lastUsedId = null
    }
}
