package com.bitdash.app

import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.bitdash.app.market.Bar
import com.bitdash.app.market.Candle
import com.bitdash.app.market.Fmt
import com.bitdash.app.market.Markets
import com.bitdash.app.market.Palette
import com.bitdash.app.market.Prefs
import com.bitdash.app.market.RealtimeSession
import com.bitdash.app.market.RtEvent
import com.bitdash.app.market.RtScope
import com.bitdash.app.market.RtSub
import com.bitdash.app.market.Ticker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 图表页：
 * - 顶部展示最新价、24h 涨跌、24h 高低与成交额
 * - 周期切换：1m/5m/15m/30m/1H/4H/1D/1W/1M（对齐 python BAR_OPTIONS）
 * - 自定义蜡烛图（缩放/平移/十字光标）
 * - 按设置的间隔自动刷新（默认 10 秒，可在设置里改或关闭）
 * - 开启实时行情（设置项，范围含"图表页"）时改由 WebSocket 推送驱动：
 *   K 线历史仍走一次 REST，之后由推送更新最后一根；断开则自动退回轮询
 */
class ChartActivity : BaseActivity() {

    private lateinit var symbolView: TextView
    private lateinit var price: TextView
    private lateinit var change: TextView
    private lateinit var high: TextView
    private lateinit var low: TextView
    private lateinit var vol: TextView
    private var ohlcv: TextView? = null
    private lateinit var loading: TextView
    private lateinit var chart: CandleChartView

    // 指标控制 View
    private var tvMa1: TextView? = null
    private var tvMa2: TextView? = null
    private var tvMa3: TextView? = null
    private var tvBoll: TextView? = null
    private var tvTurtle: TextView? = null
    private var tvVolInd: TextView? = null
    private var tvMacd: TextView? = null
    private var tvRsi: TextView? = null
    private var tvKdj: TextView? = null
    private var btnIndicatorSettings: ImageButton? = null

    private var symbol: String = DEFAULT_SYMBOL
    private var currentBar = Bar.M15

    /** 进行中的请求：切换周期时取消，避免旧结果覆盖新图 */
    private var candleJob: Job? = null
    private var tickerJob: Job? = null

    /** 当前生效的自动刷新间隔；0 表示关闭 */
    private var intervalMs = Prefs.DEFAULT_REFRESH_MS

    private val handler = Handler(Looper.getMainLooper())
    private val autoRefresh = object : Runnable {
        override fun run() {
            refreshAll(showLoading = false)
            if (intervalMs > 0) handler.postDelayed(this, intervalMs)
        }
    }

    // ---------- 实时行情 ----------

    private var rt: RealtimeSession? = null

    /**
     * REST 拉到的 K 线序列，推送在它上面做增量合并。
     * 单独存一份而不是从 [CandleChartView] 反查：图表只负责渲染，
     * 而合并逻辑（改最后一根 / 追加新一根）需要一份可写的有序数据。
     */
    private val candles = ArrayList<Candle>()

    /** 推送节流：1m 周期下每秒可能来数条，攒一帧再重绘 */
    private var pendingCandle: Candle? = null
    private var pendingTicker: Ticker? = null
    private val rtFlush = Runnable { flushRealtime() }
    private var rtFlushScheduled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chart)

        symbol = intent.getStringExtra(EXTRA_SYMBOL)?.takeIf { it.isNotBlank() } ?: DEFAULT_SYMBOL

        // 切换屏幕方向会重建 Activity，恢复用户选中的周期而不是退回默认值
        savedInstanceState?.getString(STATE_BAR)?.let { name ->
            Bar.values().firstOrNull { it.name == name }?.let { currentBar = it }
        }

        symbolView = findViewById(R.id.tvSymbol)
        price = findViewById(R.id.tvPrice)
        change = findViewById(R.id.tvChange)
        high = findViewById(R.id.tvHigh)
        low = findViewById(R.id.tvLow)
        vol = findViewById(R.id.tvVol)
        ohlcv = findViewById(R.id.tvOhlcv)
        loading = findViewById(R.id.tvLoading)
        chart = findViewById(R.id.chart)

        // 绑定指标控制栏 View
        tvMa1 = findViewById(R.id.tvMa1)
        tvMa2 = findViewById(R.id.tvMa2)
        tvMa3 = findViewById(R.id.tvMa3)
        tvBoll = findViewById(R.id.tvBoll)
        tvTurtle = findViewById(R.id.tvTurtle)
        tvVolInd = findViewById(R.id.tvVolInd)
        tvMacd = findViewById(R.id.tvMacd)
        tvRsi = findViewById(R.id.tvRsi)
        tvKdj = findViewById(R.id.tvKdj)
        btnIndicatorSettings = findViewById(R.id.btnIndicatorSettings)

        symbolView.text = symbol

        applyFontScale()
        initIndicatorsUI()

        rt = RealtimeSession(
            ctx = this,
            onEvent = { ev -> onRealtimeEvent(ev) },
            onState = { connected ->
                applyRefreshStrategy()
                // 断线时立刻补一次 REST，避免图表停在断开前的时刻
                if (!connected) refreshAll(showLoading = false)
            }
        )

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnChartSettings).setOnClickListener {
            SettingsDialog.show(this) {
                applyFontScale()
                chart.applyPalette()
                applyRefreshStrategy()
                refreshAll(showLoading = false)
            }
        }

        // 十字光标回调 → 顶部覆盖显示该根 K 线的 OHLCV；隐藏时恢复指标栏
        chart.onCrosshairChange = { c ->
            if (c == null) {
                ohlcv?.visibility = View.INVISIBLE
            } else {
                ohlcv?.visibility = View.VISIBLE
                ohlcv?.text = buildString {
                    append(timeLabel(c.ts))
                    append("  开 ").append(Fmt.price(c.open))
                    append("  高 ").append(Fmt.price(c.high))
                    append("  低 ").append(Fmt.price(c.low))
                    append("  收 ").append(Fmt.price(c.close))
                    append("  量 ").append(Fmt.vol(c.vol))
                }
                ohlcv?.setTextColor(Palette.byDelta(this, c.close - c.open))
            }
        }

        // 周期切换
        val rg = findViewById<RadioGroup>(R.id.rgTimeframe)
        rg.setOnCheckedChangeListener { _, checkedId ->
            val bar = BAR_OF_ID[checkedId] ?: return@setOnCheckedChangeListener
            if (bar == currentBar) return@setOnCheckedChangeListener
            currentBar = bar
            chart.timePattern = timePattern(bar)
            // 换周期等于换数据集，缩放/平移状态必须复位
            chart.resetView()
            // 丢掉旧周期的推送残留，并把订阅切到新周期
            discardRealtimeCandles()
            applyRefreshStrategy()
            loadCandles(showLoading = true)
        }

        chart.timePattern = timePattern(currentBar)
        rg.check(ID_OF_BAR.getValue(currentBar))
    }

    override fun onResume() {
        super.onResume()
        applyFontScale()
        chart.applyPalette()
        refreshAll(showLoading = chart.isEmptyData())
        applyRefreshStrategy()
    }

    private fun applyFontScale() {
        val scale = Prefs.getChartFontScale(this)
        val isLand = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        val baseSymbol = if (isLand) 13f else 17f
        val basePrice = if (isLand) 15f else 22f
        val baseChange = if (isLand) 11f else 14f
        val baseStat = if (isLand) 10f else 12f
        val baseTf = 11f
        val baseMa = if (isLand) 10f else 11f
        val baseOhlcv = if (isLand) 10f else 11f
        val baseLoading = 14f

        symbolView.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseSymbol * scale)
        price.setTextSize(TypedValue.COMPLEX_UNIT_SP, basePrice * scale)
        change.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseChange * scale)
        high.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseStat * scale)
        low.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseStat * scale)
        vol.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseStat * scale)

        // 周期按钮字号按比例放大
        val rg = findViewById<RadioGroup>(R.id.rgTimeframe)
        if (rg != null) {
            for (i in 0 until rg.childCount) {
                (rg.getChildAt(i) as? RadioButton)?.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseTf * scale)
            }
        }

        tvMa1?.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseMa * scale)
        tvMa2?.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseMa * scale)
        tvMa3?.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseMa * scale)
        tvBoll?.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseMa * scale)
        tvTurtle?.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseMa * scale)
        tvVolInd?.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseMa * scale)
        tvMacd?.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseMa * scale)
        tvRsi?.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseMa * scale)
        tvKdj?.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseMa * scale)
        ohlcv?.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseOhlcv * scale)
        loading.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseLoading * scale)

        chart.applyFontScale(scale)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(autoRefresh)
        // 页面不可见就断开，避免后台持续收推送
        rt?.stop()
        cancelRealtimeFlush()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_BAR, currentBar.name)
    }

    /**
     * 统一决定"数据从哪来"：实时可用就订阅 WS 并停掉轮询，否则回到定时轮询。
     * 切周期、改设置、连接状态变化时都要调。
     */
    private fun applyRefreshStrategy() {
        val session = rt ?: return

        val wantRealtime = session.available(RtScope::forChart)
        if (wantRealtime) {
            // 顶部行情栏用 tickers，图表用 kline；OKX 下这会落在两条连接上
            session.start(
                listOf(
                    RtSub.Tickers(listOf(symbol)),
                    RtSub.Kline(symbol, currentBar)
                )
            )
        } else {
            session.stop()
        }

        handler.removeCallbacks(autoRefresh)
        if (wantRealtime && session.isConnected()) {
            intervalMs = 0L
            return
        }
        intervalMs = Prefs.getRefreshMs(this)
        if (intervalMs > 0) handler.postDelayed(autoRefresh, intervalMs)
    }

    // ---------- 实时推送处理 ----------

    private fun onRealtimeEvent(ev: RtEvent) {
        when (ev) {
            is RtEvent.TickerUpdate -> {
                if (ev.ticker.symbol != symbol) return
                pendingTicker = ev.ticker
            }
            is RtEvent.KlineUpdate -> {
                // 切周期后可能还收到在途的旧周期推送，必须丢掉
                if (ev.symbol != symbol || ev.bar != currentBar) return
                pendingCandle = ev.candle
            }
        }
        if (rtFlushScheduled) return
        rtFlushScheduled = true
        handler.postDelayed(rtFlush, RT_FLUSH_MS)
    }

    private fun flushRealtime() {
        rtFlushScheduled = false

        pendingTicker?.let { renderTicker(it) }
        pendingTicker = null

        pendingCandle?.let { c ->
            if (mergeCandle(c)) {
                chart.setData(ArrayList(candles))
                loading.visibility = View.GONE
            }
        }
        pendingCandle = null
    }

    /**
     * 把推送的最新一根并入 [candles]。
     *
     * @return 是否真的改动了数据（false 时不必重绘）
     */
    private fun mergeCandle(c: Candle): Boolean {
        // 首屏 REST 还没回来：孤零零一根画出来没意义，等 REST 把历史填好
        val lastTs = candles.lastOrNull()?.ts ?: return false

        return when {
            // 同一根：更新（推送一定比 REST 快照新）
            c.ts == lastTs -> {
                if (candles[candles.lastIndex] == c) return false
                candles[candles.lastIndex] = c
                true
            }
            // 新的一根：追加并保持长度上限
            c.ts > lastTs -> {
                candles.add(c)
                while (candles.size > CANDLE_LIMIT) candles.removeAt(0)
                true
            }
            // 早于最后一根：迟到的重复推送，忽略
            else -> false
        }
    }

    /** 切周期时清掉推送态；新周期的历史由 REST 重新填 */
    private fun discardRealtimeCandles() {
        candles.clear()
        pendingCandle = null
    }

    private fun cancelRealtimeFlush() {
        handler.removeCallbacks(rtFlush)
        rtFlushScheduled = false
        pendingCandle = null
        pendingTicker = null
    }

    private fun refreshAll(showLoading: Boolean) {
        loadTicker()
        loadCandles(showLoading)
    }

    private fun renderTicker(t: Ticker) {
        val c = Palette.byDelta(this, t.changePct)
        price.text = Fmt.price(t.last)
        price.setTextColor(c)
        change.text = Fmt.pct(t.changePct)
        change.setTextColor(c)
        high.text = getString(R.string.stat_high, Fmt.price(t.high24h))
        low.text = getString(R.string.stat_low, Fmt.price(t.low24h))
        vol.text = getString(R.string.stat_vol, Fmt.vol(t.quoteVol24h))
    }

    private fun loadTicker() {
        tickerJob?.cancel()
        tickerJob = lifecycleScope.launch(Dispatchers.Main) {
            try {
                val t = withContext(Dispatchers.IO) {
                    Markets.withSource(this@ChartActivity) { it.ticker(symbol) }
                }
                // 推送已接管时不要用较旧的 REST 快照把价格拽回去
                if (rt?.isConnected() == true && pendingTicker != null) return@launch
                renderTicker(t)
            } catch (_: Exception) {
                // 静默：图表页轮询失败不打扰用户，下次轮询自动重试
            }
        }
    }

    private fun loadCandles(showLoading: Boolean) {
        candleJob?.cancel()
        val bar = currentBar
        if (showLoading) {
            loading.setText(R.string.loading)
            loading.visibility = View.VISIBLE
        }
        candleJob = lifecycleScope.launch(Dispatchers.Main) {
            try {
                val list = withContext(Dispatchers.IO) {
                    Markets.withSource(this@ChartActivity) { it.candles(symbol, bar, CANDLE_LIMIT) }
                }
                // 双保险：协程取消之外再比对一次周期，避免旧结果覆盖新图
                if (bar != currentBar) return@launch

                // REST 快照是增量合并的基线；已靠推送前进过的部分不能被它拽回去
                val lastRt = candles.lastOrNull()
                candles.clear()
                candles.addAll(list)
                // 推送已经跑到更新的一根时把它补回来，避免画面回退一格
                if (lastRt != null && list.isNotEmpty()) mergeCandle(lastRt)

                chart.setData(ArrayList(candles))
                if (candles.isEmpty()) {
                    loading.setText(R.string.no_data)
                    loading.visibility = View.VISIBLE
                } else {
                    loading.visibility = View.GONE
                }
            } catch (e: Exception) {
                if (bar != currentBar) return@launch
                if (chart.isEmptyData()) {
                    // 图上没有任何可看的数据时才提示，否则保留旧图静默重试
                    loading.text = e.message ?: getString(R.string.network_error)
                    loading.visibility = View.VISIBLE
                    if (showLoading) {
                        Toast.makeText(
                            this@ChartActivity, R.string.source_switch_hint, Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    loading.visibility = View.GONE
                }
            }
        }
    }

    private fun initIndicatorsUI() {
        updateIndicatorTabsUI()

        tvMa1?.setOnClickListener {
            val next = !Prefs.getShowMa1(this)
            Prefs.setShowMa1(this, next)
            updateIndicatorTabsUI()
            chart.refreshIndicatorToggles()
        }

        tvMa2?.setOnClickListener {
            val next = !Prefs.getShowMa2(this)
            Prefs.setShowMa2(this, next)
            updateIndicatorTabsUI()
            chart.refreshIndicatorToggles()
        }

        tvMa3?.setOnClickListener {
            val next = !Prefs.getShowMa3(this)
            Prefs.setShowMa3(this, next)
            updateIndicatorTabsUI()
            chart.refreshIndicatorToggles()
        }

        tvBoll?.setOnClickListener {
            val next = !Prefs.getShowBoll(this)
            Prefs.setShowBoll(this, next)
            updateIndicatorTabsUI()
            chart.refreshIndicatorToggles()
        }

        tvTurtle?.setOnClickListener {
            val next = !Prefs.getShowTurtle(this)
            Prefs.setShowTurtle(this, next)
            updateIndicatorTabsUI()
            chart.refreshIndicatorToggles()
        }

        tvVolInd?.setOnClickListener {
            val current = Prefs.getSubIndicator(this)
            val next = if (current == "VOL") "OFF" else "VOL"
            Prefs.setSubIndicator(this, next)
            updateIndicatorTabsUI()
            chart.refreshIndicatorToggles()
        }

        tvMacd?.setOnClickListener {
            val current = Prefs.getSubIndicator(this)
            val next = if (current == "MACD") "OFF" else "MACD"
            Prefs.setSubIndicator(this, next)
            updateIndicatorTabsUI()
            chart.refreshIndicatorToggles()
        }

        tvRsi?.setOnClickListener {
            val current = Prefs.getSubIndicator(this)
            val next = if (current == "RSI") "OFF" else "RSI"
            Prefs.setSubIndicator(this, next)
            updateIndicatorTabsUI()
            chart.refreshIndicatorToggles()
        }

        tvKdj?.setOnClickListener {
            val current = Prefs.getSubIndicator(this)
            val next = if (current == "KDJ") "OFF" else "KDJ"
            Prefs.setSubIndicator(this, next)
            updateIndicatorTabsUI()
            chart.refreshIndicatorToggles()
        }

        btnIndicatorSettings?.setOnClickListener {
            IndicatorSettingsDialog.show(this) {
                updateIndicatorTabsUI()
                chart.refreshIndicatorToggles()
            }
        }
    }

    private fun updateIndicatorTabsUI() {
        tvMa1?.text = "MA${Prefs.getMa1Period(this)}"
        tvMa2?.text = "MA${Prefs.getMa2Period(this)}"
        tvMa3?.text = "MA${Prefs.getMa3Period(this)}"

        tvMa1?.alpha = if (Prefs.getShowMa1(this)) 1.0f else 0.30f
        tvMa2?.alpha = if (Prefs.getShowMa2(this)) 1.0f else 0.30f
        tvMa3?.alpha = if (Prefs.getShowMa3(this)) 1.0f else 0.30f
        tvBoll?.alpha = if (Prefs.getShowBoll(this)) 1.0f else 0.30f
        tvTurtle?.alpha = if (Prefs.getShowTurtle(this)) 1.0f else 0.30f

        val sub = Prefs.getSubIndicator(this)
        tvVolInd?.alpha = if (sub == "VOL") 1.0f else 0.30f
        tvMacd?.alpha = if (sub == "MACD") 1.0f else 0.30f
        tvRsi?.alpha = if (sub == "RSI") 1.0f else 0.30f
        tvKdj?.alpha = if (sub == "KDJ") 1.0f else 0.30f
    }

    private fun timeLabel(ts: Long): String =
        SimpleDateFormat(timePattern(currentBar), Locale.getDefault()).format(Date(ts))

    companion object {
        const val EXTRA_SYMBOL = "symbol"

        private const val STATE_BAR = "bar"
        private const val DEFAULT_SYMBOL = "BTC-USDT"
        private const val CANDLE_LIMIT = 300

        /**
         * 实时推送的 UI 合并窗口。
         * 1m 周期下 K 线与最新价每秒都可能来数条，逐条 setData 会不停重算 MA 并重绘。
         */
        private const val RT_FLUSH_MS = 250L

        /** RadioButton id → 周期 */
        private val BAR_OF_ID = mapOf(
            R.id.tf1m to Bar.M1, R.id.tf5m to Bar.M5, R.id.tf15m to Bar.M15,
            R.id.tf30m to Bar.M30, R.id.tf1h to Bar.H1, R.id.tf4h to Bar.H4,
            R.id.tf1d to Bar.D1, R.id.tf1w to Bar.W1, R.id.tf1M to Bar.MON1
        )
        private val ID_OF_BAR = BAR_OF_ID.entries.associate { (id, bar) -> bar to id }

        /** 周期 → 时间轴/详情栏的时间格式 */
        private fun timePattern(bar: Bar): String = when (bar) {
            Bar.M1, Bar.M5, Bar.M15, Bar.M30, Bar.H1, Bar.H4 -> "MM-dd HH:mm"
            Bar.D1, Bar.W1 -> "yyyy-MM-dd"
            Bar.MON1 -> "yyyy-MM"
        }
    }
}
