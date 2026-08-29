package com.bitdash.app

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bitdash.app.market.Fmt
import com.bitdash.app.market.Markets
import com.bitdash.app.market.Palette
import com.bitdash.app.market.Prefs
import com.bitdash.app.market.Ticker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import androidx.core.content.ContextCompat
import com.bitdash.app.market.AshareMarkets
import com.bitdash.app.market.RwaHttpSource
import com.bitdash.app.market.RwaMarkets
import com.bitdash.app.market.UsStockSource

/**
 * 搜索页：拉取全量行情，本地过滤交易对名。
 * 支持加密货币、美股与 A 股三大分类搜索与自选加减。
 */
class SearchActivity : BaseActivity() {

    private lateinit var etSearch: EditText
    private lateinit var emptyView: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var tabSearchCrypto: TextView
    private lateinit var tabSearchUsStock: TextView
    private lateinit var tabSearchAshare: TextView
    private val adapter = SearchAdapter()

    /** 搜索模式：0=加密货币，1=美股，2=A股 */
    private var searchMode = Prefs.TAB_CRYPTO

    /** 全量行情缓存（成交额降序） */
    private var all: List<Ticker> = emptyList()

    /** 联网联想任务 */
    private var suggestJob: Job? = null

    /** 自选集合的内存镜像 */
    private lateinit var watch: LinkedHashSet<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        searchMode = Prefs.getHomeTabMode(this)
        updateWatchSet()

        etSearch = findViewById(R.id.etSearch)
        emptyView = findViewById(R.id.emptyView)
        recycler = findViewById(R.id.recycler)
        tabSearchCrypto = findViewById(R.id.tabSearchCrypto)
        tabSearchUsStock = findViewById(R.id.tabSearchUsStock)
        tabSearchAshare = findViewById(R.id.tabSearchAshare)

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        recycler.setHasFixedSize(true)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        updateSearchTabStyles()

        tabSearchCrypto.setOnClickListener {
            switchSearchMode(Prefs.TAB_CRYPTO)
        }

        tabSearchUsStock.setOnClickListener {
            switchSearchMode(Prefs.TAB_US_STOCK)
        }

        tabSearchAshare.setOnClickListener {
            switchSearchMode(Prefs.TAB_A_SHARE)
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = applyFilter(s?.toString())
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        loadAll()
    }

    private fun switchSearchMode(mode: Int) {
        if (searchMode != mode) {
            searchMode = mode
            updateSearchTabStyles()
            updateWatchSet()
            loadAll()
        }
    }

    private fun updateWatchSet() {
        watch = when (searchMode) {
            Prefs.TAB_A_SHARE -> LinkedHashSet(Prefs.getAshareWatchlist(this))
            Prefs.TAB_US_STOCK -> LinkedHashSet(Prefs.getUsStockWatchlist(this))
            else -> LinkedHashSet(Prefs.getWatchlist(this))
        }
    }

    private fun updateSearchTabStyles() {
        tabSearchCrypto.background = null
        tabSearchCrypto.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
        tabSearchUsStock.background = null
        tabSearchUsStock.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
        tabSearchAshare.background = null
        tabSearchAshare.setTextColor(ContextCompat.getColor(this, R.color.text_dim))

        when (searchMode) {
            Prefs.TAB_A_SHARE -> {
                tabSearchAshare.setBackgroundResource(R.drawable.bg_tab_selected)
                tabSearchAshare.setTextColor(ContextCompat.getColor(this, R.color.brand))
                etSearch.hint = "搜索 A 股代码 / 名称 (如 600519, 300750...)"
            }
            Prefs.TAB_US_STOCK -> {
                tabSearchUsStock.setBackgroundResource(R.drawable.bg_tab_selected)
                tabSearchUsStock.setTextColor(ContextCompat.getColor(this, R.color.brand))
                etSearch.hint = "搜索美股代码 (如 NVDA, TSLA, AAPL...)"
            }
            else -> {
                tabSearchCrypto.setBackgroundResource(R.drawable.bg_tab_selected)
                tabSearchCrypto.setTextColor(ContextCompat.getColor(this, R.color.brand))
                etSearch.hint = "搜索加密交易对 (如 BTC, ETH, SOL...)"
            }
        }
    }

    private fun loadAll() {
        emptyView.setText(R.string.loading)
        emptyView.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.Main) {
            try {
                all = withContext(Dispatchers.IO) {
                    when (searchMode) {
                        Prefs.TAB_A_SHARE -> {
                            val ashList = (Prefs.getAshareWatchlist(this@SearchActivity) + AshareMarkets.DEFAULT_SYMBOLS).distinct()
                            AshareMarkets.fetchQuotes(this@SearchActivity, ashList).filter { it.valid }
                        }
                        Prefs.TAB_US_STOCK -> {
                            val usList = (Prefs.getUsStockWatchlist(this@SearchActivity) + UsStockSource.DEFAULT_SYMBOLS).distinct()
                            RwaMarkets.fetchQuotes(this@SearchActivity, usList).filter { it.valid }
                        }
                        else -> {
                            Markets.withSource(this@SearchActivity) { it.allTickers() }
                                .filter { it.valid }
                                .sortedByDescending { it.quoteVol24h }
                        }
                    }
                }
                applyFilter(etSearch.text?.toString())
            } catch (e: Exception) {
                emptyView.text = getString(
                    R.string.search_error,
                    e.message ?: getString(R.string.network_error)
                )
                emptyView.visibility = View.VISIBLE
                if (searchMode == Prefs.TAB_CRYPTO) {
                    Toast.makeText(this@SearchActivity, R.string.source_switch_hint, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** 过滤 + 提交 */
    private fun applyFilter(q: String?) {
        val query = (q ?: "").trim()
        val queryUpper = query.uppercase()
        val list = if (query.isEmpty()) {
            all.take(HOT_COUNT)
        } else {
            val filtered = all.filter {
                val stockName = it.displayName ?: AshareMarkets.getKnownName(it.symbol) ?: UsStockSource.getKnownName(it.symbol)
                it.symbol.uppercase().contains(queryUpper) || (!stockName.isNullOrBlank() && stockName.contains(query, ignoreCase = true))
            }.toMutableList()
            if (filtered.isEmpty()) {
                if (searchMode == Prefs.TAB_A_SHARE && query.length in 4..6 && query.all { it.isDigit() }) {
                    val candidateSym = "$query-CNY"
                    val name = AshareMarkets.getKnownName(candidateSym)
                    filtered.add(Ticker(candidateSym, 0.0, 0.0, 0.0, 0.0, 0.0, displayName = name))
                } else if (searchMode == Prefs.TAB_US_STOCK && query.length in 1..6) {
                    val candidateSym = "$queryUpper-USD"
                    val name = UsStockSource.getKnownName(candidateSym)
                    filtered.add(Ticker(candidateSym, 0.0, 0.0, 0.0, 0.0, 0.0, displayName = name))
                }
            }
            filtered
        }
        adapter.submit(list)
        recycler.scrollToPosition(0)

        if (list.isEmpty()) {
            emptyView.setText(if (all.isEmpty()) R.string.loading else R.string.no_result)
            emptyView.visibility = View.VISIBLE
        } else {
            emptyView.visibility = View.GONE
        }

        // A股联网实时联想匹配（支持中文名、拼音缩写及任意代码检索）
        suggestJob?.cancel()
        if (searchMode == Prefs.TAB_A_SHARE && query.isNotEmpty()) {
            suggestJob = lifecycleScope.launch(Dispatchers.Main) {
                try {
                    val remoteMatches = withContext(Dispatchers.IO) {
                        AshareMarkets.searchSuggest(this@SearchActivity, query)
                    }
                    if (remoteMatches.isNotEmpty() && (etSearch.text?.toString() ?: "").trim() == query) {
                        val currentSymbols = list.map { it.symbol }.toHashSet()
                        val combined = ArrayList(list)
                        for (item in remoteMatches) {
                            if (!currentSymbols.contains(item.symbol)) {
                                combined.add(item)
                                currentSymbols.add(item.symbol)
                            }
                        }
                        adapter.submit(combined)
                        emptyView.visibility = View.GONE
                    }
                } catch (_: Exception) {}
            }
        }
    }

    /** 切换自选状态并落盘 */
    private fun toggleWatch(symbol: String) {
        val added = if (watch.contains(symbol)) {
            watch.remove(symbol)
            false
        } else {
            watch.add(symbol)
            true
        }
        val ordered = if (added) {
            ArrayList<String>(watch.size).apply {
                add(symbol)
                watch.forEach { if (it != symbol) add(it) }
            }
        } else {
            ArrayList(watch)
        }
        watch = LinkedHashSet(ordered)
        when (searchMode) {
            Prefs.TAB_A_SHARE -> Prefs.saveAshareWatchlist(this, ordered)
            Prefs.TAB_US_STOCK -> Prefs.saveUsStockWatchlist(this, ordered)
            else -> Prefs.saveWatchlist(this, ordered)
        }
        Toast.makeText(
            this, if (added) R.string.add_watch else R.string.remove_watch, Toast.LENGTH_SHORT
        ).show()
    }

    override fun onDestroy() {
        // 收起软键盘，避免退出后输入法残留
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(etSearch.windowToken, 0)
        super.onDestroy()
    }

    // ---------- 适配器 ----------

    private inner class SearchAdapter : RecyclerView.Adapter<SearchAdapter.VH>() {

        private val items = ArrayList<Ticker>()

        fun submit(list: List<Ticker>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_watch, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val t = items[position]
            val c = Palette.byDelta(this@SearchActivity, t.changePct)
            val scale = Prefs.getFontScale(this@SearchActivity)
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

            val isAshareItem = searchMode == Prefs.TAB_A_SHARE || t.symbol.endsWith("-CNY") || t.symbol.matches(Regex("^[0-9]{6}.*"))
            val isUsStockItem = searchMode == Prefs.TAB_US_STOCK || t.symbol.endsWith("-USD") || UsStockSource.DEFAULT_SYMBOLS.contains(t.symbol)

            if (isAshareItem) {
                val stockName = t.displayName ?: AshareMarkets.getKnownName(t.symbol)
                if (!stockName.isNullOrBlank()) {
                    holder.symbol.text = stockName
                    val cleanCode = AshareMarkets.cleanCode(t.symbol)
                    if (t.valid) {
                        holder.vol.text = "$cleanCode · " + getString(R.string.item_vol, Fmt.vol(t.quoteVol24h))
                    } else {
                        holder.vol.text = cleanCode
                    }
                } else {
                    holder.symbol.text = t.symbol
                    if (t.valid) {
                        holder.vol.text = getString(R.string.item_vol, Fmt.vol(t.quoteVol24h))
                    } else {
                        holder.vol.text = getString(R.string.placeholder)
                    }
                }
            } else if (isUsStockItem) {
                val usName = t.displayName ?: UsStockSource.getKnownName(t.symbol)
                holder.symbol.text = t.symbol
                if (!usName.isNullOrBlank()) {
                    if (t.valid) {
                        holder.vol.text = "$usName · " + getString(R.string.item_vol, Fmt.vol(t.quoteVol24h))
                    } else {
                        holder.vol.text = usName
                    }
                } else {
                    if (t.valid) {
                        holder.vol.text = getString(R.string.item_vol, Fmt.vol(t.quoteVol24h))
                    } else {
                        holder.vol.text = getString(R.string.placeholder)
                    }
                }
            } else {
                holder.symbol.text = t.symbol
                if (t.valid) {
                    holder.vol.text = getString(R.string.item_vol, Fmt.vol(t.quoteVol24h))
                } else {
                    holder.vol.text = getString(R.string.placeholder)
                }
            }
            holder.price.text = Fmt.price(t.last)
            holder.price.setTextColor(c)
            holder.change.text = Fmt.pct(t.changePct)
            holder.change.setTextColor(c)

            // 星标指示自选状态（读内存镜像，不碰磁盘）
            holder.star.visibility = View.VISIBLE
            holder.star.setImageResource(
                if (watch.contains(t.symbol)) R.drawable.ic_star else R.drawable.ic_star_border
            )

            // 点击五角星：添加 / 移除自选
            holder.star.setOnClickListener {
                toggleWatch(t.symbol)
                val p = holder.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) notifyItemChanged(p)
            }

            // 点击列表项非五角星区域：进入该币种 K 线图表
            holder.itemView.setOnClickListener {
                startActivity(
                    Intent(this@SearchActivity, ChartActivity::class.java)
                        .putExtra(ChartActivity.EXTRA_SYMBOL, t.symbol)
                )
            }
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val symbol: TextView = v.findViewById(R.id.tvSymbol)
            val vol: TextView = v.findViewById(R.id.tvVol)
            val price: TextView = v.findViewById(R.id.tvPrice)
            val change: TextView = v.findViewById(R.id.tvChange)
            val star: ImageView = v.findViewById(R.id.tvStar)
        }
    }

    companion object {
        private const val HOT_COUNT = 80
    }
}
