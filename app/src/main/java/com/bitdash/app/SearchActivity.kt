package com.bitdash.app

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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 搜索页：拉取全量行情，本地过滤交易对名。
 * 点击行切换自选状态（星标指示），默认按成交额降序展示热门币。
 */
class SearchActivity : BaseActivity() {

    private lateinit var etSearch: EditText
    private lateinit var emptyView: TextView
    private lateinit var recycler: RecyclerView
    private val adapter = SearchAdapter()

    /** 全量行情缓存（成交额降序） */
    private var all: List<Ticker> = emptyList()

    /** 自选集合的内存镜像：避免在 onBindViewHolder 里读 SharedPreferences */
    private lateinit var watch: LinkedHashSet<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        watch = LinkedHashSet(Prefs.getWatchlist(this))

        etSearch = findViewById(R.id.etSearch)
        emptyView = findViewById(R.id.emptyView)
        recycler = findViewById(R.id.recycler)

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        recycler.setHasFixedSize(true)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = applyFilter(s?.toString())
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        loadAll()
    }

    private fun loadAll() {
        emptyView.setText(R.string.loading)
        emptyView.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.Main) {
            try {
                all = withContext(Dispatchers.IO) {
                    Markets.withSource(this@SearchActivity) { it.allTickers() }
                        .filter { it.valid }
                        .sortedByDescending { it.quoteVol24h }
                }
                applyFilter(etSearch.text?.toString())
            } catch (e: Exception) {
                // 失败时说明具体原因，并提示可以换源
                emptyView.text = getString(
                    R.string.search_error,
                    e.message ?: getString(R.string.network_error)
                )
                emptyView.visibility = View.VISIBLE
                Toast.makeText(this@SearchActivity, R.string.source_switch_hint, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 过滤 + 提交（无关键词时只显示成交额前 [HOT_COUNT] 的热门币） */
    private fun applyFilter(q: String?) {
        // 用户可能输入 "btc" 或 "btc-usdt"，统一大写后做子串匹配
        val query = (q ?: "").trim().uppercase()
        val list = if (query.isEmpty()) {
            all.take(HOT_COUNT)
        } else {
            all.filter { it.symbol.contains(query) }
        }
        adapter.submit(list)
        recycler.scrollToPosition(0)

        if (list.isEmpty()) {
            emptyView.setText(if (all.isEmpty()) R.string.loading else R.string.no_result)
            emptyView.visibility = View.VISIBLE
        } else {
            emptyView.visibility = View.GONE
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
        // 新增的排在前面，符合"最近添加优先"的直觉
        val ordered = if (added) {
            ArrayList<String>(watch.size).apply {
                add(symbol)
                watch.forEach { if (it != symbol) add(it) }
            }
        } else {
            ArrayList(watch)
        }
        watch = LinkedHashSet(ordered)
        Prefs.saveWatchlist(this, ordered)
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

            holder.symbol.text = t.symbol
            holder.vol.text = getString(R.string.item_vol, Fmt.vol(t.quoteVol24h))
            holder.price.text = Fmt.price(t.last)
            holder.price.setTextColor(c)
            holder.change.text = Fmt.pct(t.changePct)
            holder.change.setTextColor(c)

            // 星标指示自选状态（读内存镜像，不碰磁盘）
            holder.star.visibility = View.VISIBLE
            holder.star.setImageResource(
                if (watch.contains(t.symbol)) R.drawable.ic_star else R.drawable.ic_star_border
            )

            holder.itemView.setOnClickListener {
                toggleWatch(t.symbol)
                // 用 bindingAdapterPosition 而非闭包捕获的 position，避免列表变动后刷错行
                val p = holder.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) notifyItemChanged(p)
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
