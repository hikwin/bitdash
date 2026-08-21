package com.bitdash.app

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.bitdash.app.market.Fmt
import com.bitdash.app.market.Markets
import com.bitdash.app.market.Palette
import com.bitdash.app.market.Prefs
import com.bitdash.app.market.RealtimeSession
import com.bitdash.app.market.RtEvent
import com.bitdash.app.market.RtScope
import com.bitdash.app.market.RtSub
import com.bitdash.app.market.Ticker
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections

/**
 * 自选列表页：
 * - 展示自选币种的最新价与 24h 涨跌幅（一次全量请求批量刷新）
 * - 点击进入图表页；长按拖拽排序；左滑/右滑移除自选（支持撤销）
 * - 顶部显示当前行情源，点击可切换
 * - 下拉刷新 + 按设置的间隔自动刷新
 * - 开启实时行情（设置项，范围含"自选列表"）时改由 WebSocket 推送驱动，
 *   不再受刷新间隔限制；WS 不可用或断开时自动退回轮询
 */
class MainActivity : BaseActivity() {

    private lateinit var refresh: SwipeRefreshLayout
    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var sourceLabel: TextView
    private val adapter = WatchAdapter()

    /** 进行中的刷新任务，避免自动刷新与下拉刷新叠加 */
    private var loadJob: Job? = null

    /** 当前生效的自动刷新间隔；0 表示关闭 */
    private var intervalMs = Prefs.DEFAULT_REFRESH_MS

    private val handler = Handler(Looper.getMainLooper())
    private val autoRefresh = object : Runnable {
        override fun run() {
            loadPrices(showPull = false)
            // 每次重排都读当前间隔，设置改动后无需重启页面即可生效
            if (intervalMs > 0) handler.postDelayed(this, intervalMs)
        }
    }

    // ---------- 实时行情 ----------

    /** null = 尚未创建（onCreate 后赋值） */
    private var rt: RealtimeSession? = null

    /** WS 推送攒下来的行情，按 UI 帧节流后再刷列表 */
    private val rtPending = HashMap<String, Ticker>()

    /** 节流：推送很密（每秒多条 × 多个币种），逐条 notify 会让列表一直重绘 */
    private val rtFlush = Runnable { flushRealtime() }
    private var rtFlushScheduled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        refresh = findViewById(R.id.refresh)
        recycler = findViewById(R.id.recycler)
        emptyView = findViewById(R.id.emptyView)
        sourceLabel = findViewById(R.id.tvSource)

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        recycler.setHasFixedSize(true)

        rt = RealtimeSession(
            ctx = this,
            onEvent = { ev -> if (ev is RtEvent.TickerUpdate) onRealtimeTicker(ev.ticker) },
            onState = { connected ->
                // 连上就停轮询、断开就恢复轮询，两者永远只有一个在跑
                applyRefreshStrategy()
                updateSourceLabel()
                if (!connected) loadPrices(showPull = false)
            }
        )

        // 长按拖拽排序 + 左右滑动移除动效
        setupItemTouchHelper()

        refresh.setColorSchemeColors(COLOR_BRAND)
        refresh.setOnRefreshListener { loadPrices(showPull = true) }

        findViewById<View>(R.id.btnAdd).setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }
        // 顶部源标签 → 直接打开选择行情源弹窗
        findViewById<View>(R.id.btnSource).setOnClickListener { openSourcePicker() }
        findViewById<View>(R.id.btnSettings).setOnClickListener { openSettings() }

        // 若用户开启了悬浮窗且拥有权限，自动恢复悬浮窗服务
        if (Prefs.getFloatingEnabled(this) && (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M || android.provider.Settings.canDrawOverlays(this))) {
            if (!FloatingWindowService.isRunning) {
                FloatingWindowService.start(this)
            }
        }
    }

    private fun setupItemTouchHelper() {
        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFEF4444.toInt() // 红色删除背景
            }
            private val deleteIcon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_delete)?.mutate()

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPos = viewHolder.bindingAdapterPosition
                val toPos = target.bindingAdapterPosition
                if (fromPos == RecyclerView.NO_POSITION || toPos == RecyclerView.NO_POSITION) return false
                adapter.moveItem(fromPos, toPos)
                return true
            }

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    viewHolder?.itemView?.animate()?.scaleX(1.03f)?.scaleY(1.03f)?.alpha(0.9f)?.setDuration(120)?.start()
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.animate().scaleX(1.0f).scaleY(1.0f).alpha(1.0f).setDuration(120).start()
                // 拖拽排序完成，立即持久化保存新排序
                Prefs.saveWatchlist(this@MainActivity, adapter.getCurrentSymbols())
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val pos = viewHolder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return
                val removed = adapter.removeItem(pos)
                val newWatch = adapter.getCurrentSymbols()
                Prefs.saveWatchlist(this@MainActivity, newWatch)
                // 自选变了，实时订阅要跟着退订，否则继续收无用推送
                applyRefreshStrategy()

                Snackbar.make(
                    recycler,
                    getString(R.string.removed_from_watch, removed.symbol),
                    Snackbar.LENGTH_LONG
                ).setAction(R.string.undo) {
                    adapter.insertItem(pos, removed)
                    Prefs.saveWatchlist(this@MainActivity, adapter.getCurrentSymbols())
                    applyRefreshStrategy()
                }.show()
            }

            override fun onChildDraw(
                c: Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                    val itemView = viewHolder.itemView
                    val icon = deleteIcon
                    val iconMargin = (itemView.height - (icon?.intrinsicHeight ?: 0)) / 2

                    if (dX > 0) { // 向右滑
                        c.drawRect(
                            itemView.left.toFloat(),
                            itemView.top.toFloat(),
                            itemView.left + dX,
                            itemView.bottom.toFloat(),
                            bgPaint
                        )
                        if (icon != null) {
                            val iconTop = itemView.top + iconMargin
                            val iconBottom = iconTop + icon.intrinsicHeight
                            val iconLeft = itemView.left + iconMargin
                            val iconRight = iconLeft + icon.intrinsicWidth
                            icon.setBounds(iconLeft, iconTop, iconRight, iconBottom)
                            icon.setTint(0xFFFFFFFF.toInt())
                            icon.draw(c)
                        }
                    } else if (dX < 0) { // 向左滑
                        c.drawRect(
                            itemView.right + dX,
                            itemView.top.toFloat(),
                            itemView.right.toFloat(),
                            itemView.bottom.toFloat(),
                            bgPaint
                        )
                        if (icon != null) {
                            val iconTop = itemView.top + iconMargin
                            val iconBottom = iconTop + icon.intrinsicHeight
                            val iconRight = itemView.right - iconMargin
                            val iconLeft = iconRight - icon.intrinsicWidth
                            icon.setBounds(iconLeft, iconTop, iconRight, iconBottom)
                            icon.setTint(0xFFFFFFFF.toInt())
                            icon.draw(c)
                        }
                    }

                    // 滑动时的透明度渐变动效
                    val alpha = 1f - (Math.abs(dX) / itemView.width.toFloat()).coerceIn(0f, 0.7f)
                    itemView.alpha = alpha
                }
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }
        })
        touchHelper.attachToRecyclerView(recycler)
    }

    private fun openSourcePicker() {
        SourcePicker.show(this) {
            updateSourceLabel()
            applyRefreshStrategy()
            loadPrices(showPull = true)
        }
    }

    private fun openSettings() {
        SettingsDialog.show(this) {
            updateSourceLabel()
            // 配色可能被改动，重绑全部行让新颜色生效
            adapter.notifyDataSetChanged()
            // 实时开关/间隔都可能被改动，重算取数策略后立刻刷一次
            applyRefreshStrategy()
            loadPrices(showPull = true)
        }
    }

    /**
     * 统一决定"数据从哪来"：实时可用就订阅 WS 并停掉轮询，否则回到定时轮询。
     *
     * 每次自选变化、设置变化、连接状态变化都要调，保证 WS 与轮询不会同时跑。
     */
    private fun applyRefreshStrategy() {
        val session = rt ?: return
        val watch = adapter.getCurrentSymbols().ifEmpty { Prefs.getWatchlist(this) }

        val wantRealtime = session.available(RtScope::forWatch) && watch.isNotEmpty()
        if (wantRealtime) {
            session.start(listOf(RtSub.Tickers(watch)))
        } else {
            session.stop()
        }

        // 已经连上就没必要再轮询；没连上（含正在重连）时轮询兜底
        handler.removeCallbacks(autoRefresh)
        if (wantRealtime && session.isConnected()) {
            intervalMs = 0L
            return
        }
        intervalMs = Prefs.getRefreshMs(this)
        if (intervalMs > 0) handler.postDelayed(autoRefresh, intervalMs)
    }

    override fun onResume() {
        super.onResume()
        updateSourceLabel()
        // 从搜索页返回后自选可能已变化，先按本地列表铺占位行，再拉行情
        syncRows()
        // WS 只推增量，24h 高低/成交额等首屏数据仍要靠一次 REST 快照
        loadPrices(showPull = false)
        applyRefreshStrategy()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(autoRefresh)
        // 页面不可见时断开 WS：否则后台会一直收推送，白耗流量和电
        rt?.stop()
        cancelRealtimeFlush()
    }

    // ---------- 实时推送处理 ----------

    private fun onRealtimeTicker(t: Ticker) {
        rtPending[t.symbol] = t
        if (rtFlushScheduled) return
        rtFlushScheduled = true
        handler.postDelayed(rtFlush, RT_FLUSH_MS)
    }

    private fun flushRealtime() {
        rtFlushScheduled = false
        if (rtPending.isEmpty()) return
        adapter.applyTickers(rtPending)
        rtPending.clear()
    }

    private fun cancelRealtimeFlush() {
        handler.removeCallbacks(rtFlush)
        rtFlushScheduled = false
        rtPending.clear()
    }

    /** 顶部显示当前源；自动模式下附带实际生效的源名，实时连通时加"实时"标记 */
    private fun updateSourceLabel() {
        val chosen = Prefs.getSourceId(this)
        val base = if (chosen == Markets.AUTO) {
            val actual = Markets.lastUsedId?.let { Markets.byId(it)?.displayName }
            if (actual != null) getString(R.string.source_auto_with, actual)
            else getString(R.string.source_auto)
        } else {
            Markets.byId(chosen)?.displayName ?: getString(R.string.source_auto)
        }
        sourceLabel.text = if (rt?.isConnected() == true) {
            "$base · ${getString(R.string.rt_badge)}"
        } else {
            base
        }
    }

    /** 按本地自选顺序重排列表，保留已有的行情数据 */
    private fun syncRows() {
        val watch = Prefs.getWatchlist(this)
        adapter.submit(watch.map { adapter.cached(it) ?: placeholder(it) })
    }

    private fun loadPrices(showPull: Boolean) {
        val watch = Prefs.getWatchlist(this)
        if (watch.isEmpty()) {
            adapter.submit(emptyList())
            // 空列表也要收起转圈，否则下拉后指示器一直转
            refresh.isRefreshing = false
            return
        }
        if (showPull) refresh.isRefreshing = true
        loadJob?.cancel()
        loadJob = lifecycleScope.launch(Dispatchers.Main) {
            try {
                // 一次全量请求，本地按自选过滤（比逐个请求省一个数量级）
                val map = withContext(Dispatchers.IO) {
                    val want = watch.toHashSet()
                    val m = HashMap<String, Ticker>(want.size)
                    Markets.withSource(this@MainActivity) { src ->
                        src.allTickers().forEach { if (want.contains(it.symbol)) m[it.symbol] = it }
                    }
                    m
                }
                // 保持自选顺序；该源没有的币种保留占位行而不是消失
                // 实时已连通时，慢到达的 REST 快照不能覆盖更新的推送价
                val preferCached = rt?.isConnected() == true
                adapter.submit(watch.map { s ->
                    val fresh = map[s]
                    val cached = if (preferCached) adapter.cached(s) else null
                    val item = cached ?: fresh ?: placeholder(s)
                    if (item.valid) {
                        com.bitdash.app.alert.PriceAlertManager.onPriceUpdate(this@MainActivity, item.symbol, item.last)
                    }
                    item
                })
                updateSourceLabel()
            } catch (e: Exception) {
                // 保留旧数据，仅在用户主动下拉时提示具体原因
                if (showPull) {
                    Toast.makeText(this@MainActivity, errorText(e), Toast.LENGTH_LONG).show()
                }
                syncRows()
            } finally {
                refresh.isRefreshing = false
            }
        }
    }

    private fun placeholder(symbol: String) = Ticker(symbol, 0.0, 0.0, 0.0, 0.0, 0.0)

    private fun errorText(e: Exception): String =
        e.message?.takeIf { it.isNotBlank() } ?: getString(R.string.network_error)

    // ---------- RecyclerView 适配器 ----------

    private inner class WatchAdapter : RecyclerView.Adapter<WatchAdapter.VH>() {

        private val items = ArrayList<Ticker>()

        /** 取已加载过的行情，用于重排时避免闪回占位符 */
        fun cached(symbol: String): Ticker? =
            items.firstOrNull { it.symbol == symbol && it.valid }

        fun submit(list: List<Ticker>) {
            items.clear()
            items.addAll(list)
            emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            notifyDataSetChanged()
        }

        /**
         * 用 WS 推送的最新价局部更新已有行，只 notify 真正变了的那几行。
         * 不新增/不删除行：列表构成由自选决定，推送里多出来的交易对直接忽略。
         */
        fun applyTickers(updates: Map<String, Ticker>) {
            for (i in items.indices) {
                val fresh = updates[items[i].symbol] ?: continue
                if (fresh == items[i]) continue
                items[i] = fresh
                if (fresh.valid) {
                    com.bitdash.app.alert.PriceAlertManager.onPriceUpdate(this@MainActivity, fresh.symbol, fresh.last)
                }
                notifyItemChanged(i)
            }
        }

        fun moveItem(fromPos: Int, toPos: Int) {
            if (fromPos < toPos) {
                for (i in fromPos until toPos) {
                    Collections.swap(items, i, i + 1)
                }
            } else {
                for (i in fromPos downTo toPos + 1) {
                    Collections.swap(items, i, i - 1)
                }
            }
            notifyItemMoved(fromPos, toPos)
        }

        fun removeItem(position: Int): Ticker {
            val item = items.removeAt(position)
            notifyItemRemoved(position)
            emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            return item
        }

        fun insertItem(position: Int, item: Ticker) {
            val idx = position.coerceIn(0, items.size)
            items.add(idx, item)
            notifyItemInserted(idx)
            emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }

        fun getCurrentSymbols(): List<String> = items.map { it.symbol }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_watch, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val t = items[position]
            val scale = Prefs.getFontScale(this@MainActivity)
            val isLand = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

            // 基础字号按比例放大
            val baseSymbol = if (isLand) 14f else 16f
            val baseVol = 11f
            val basePrice = if (isLand) 14f else 16f
            val baseChange = if (isLand) 11f else 13f

            holder.symbol.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseSymbol * scale)
            holder.vol.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseVol * scale)
            holder.price.setTextSize(TypedValue.COMPLEX_UNIT_SP, basePrice * scale)
            holder.change.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseChange * scale)

            // 动态等比放大涨跌幅胶囊按钮宽高
            val basePillW = if (isLand) 66f else 72f
            val basePillH = if (isLand) 24f else 30f
            val density = resources.displayMetrics.density
            val lp = holder.change.layoutParams
            lp.width = (basePillW * scale * density).toInt()
            lp.height = (basePillH * scale * density).toInt()
            holder.change.layoutParams = lp

            holder.symbol.text = t.symbol

            if (t.valid) {
                val c = Palette.byDelta(this@MainActivity, t.changePct)
                holder.vol.text = getString(R.string.item_vol, Fmt.vol(t.quoteVol24h))
                holder.price.text = Fmt.price(t.last)
                holder.price.setTextColor(c)
                holder.change.text = Fmt.pct(t.changePct)
                holder.change.setTextColor(c)
            } else {
                // 尚未拉到行情：统一显示占位符并用中性色，避免误读成 0 价
                val ph = getString(R.string.placeholder)
                holder.vol.text = ph
                holder.price.text = ph
                holder.price.setTextColor(COLOR_NEUTRAL)
                holder.change.text = ph
                holder.change.setTextColor(COLOR_NEUTRAL)
            }

            holder.itemView.setOnClickListener {
                startActivity(
                    Intent(this@MainActivity, ChartActivity::class.java)
                        .putExtra(ChartActivity.EXTRA_SYMBOL, t.symbol)
                )
            }
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val symbol: TextView = v.findViewById(R.id.tvSymbol)
            val vol: TextView = v.findViewById(R.id.tvVol)
            val price: TextView = v.findViewById(R.id.tvPrice)
            val change: TextView = v.findViewById(R.id.tvChange)
        }
    }

    companion object {
        private val COLOR_BRAND = 0xFFF0B90B.toInt()
        private val COLOR_NEUTRAL = 0xFF8B93A7.toInt()

        /**
         * 实时推送的 UI 合并窗口。
         * Gate/Binance 约每秒 1 条/币种，多个自选叠加后逐条刷新会明显掉帧；
         * 攒 250ms 再统一 notify，视觉上仍是"实时"。
         */
        private const val RT_FLUSH_MS = 250L
    }
}
