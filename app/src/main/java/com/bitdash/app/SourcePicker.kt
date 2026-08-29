package com.bitdash.app

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bitdash.app.market.AshareMarkets
import com.bitdash.app.market.Markets
import com.bitdash.app.market.MarketSource
import com.bitdash.app.market.Prefs
import com.bitdash.app.market.RwaMarkets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 行情源选择弹窗 (支持加密货币行情源、RWA / 美股行情源与 A 股行情源独立选择与记忆)。
 */
object SourcePicker {

    fun show(activity: Activity, onPicked: () -> Unit) {
        showCrypto(activity, onPicked)
    }

    fun showCrypto(activity: Activity, onPicked: () -> Unit) {
        showInternal(activity, mode = Prefs.TAB_CRYPTO, onPicked)
    }

    fun showRwa(activity: Activity, onPicked: () -> Unit) {
        showInternal(activity, mode = Prefs.TAB_US_STOCK, onPicked)
    }

    fun showAshare(activity: Activity, onPicked: () -> Unit) {
        showInternal(activity, mode = Prefs.TAB_A_SHARE, onPicked)
    }

    private fun showInternal(activity: Activity, mode: Int, onPicked: () -> Unit) {
        val owner = activity as? LifecycleOwner ?: return

        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_source, null)
        val recycler = view.findViewById<RecyclerView>(R.id.recyclerSource)
        recycler.layoutManager = LinearLayoutManager(activity)

        val rows = buildRows(activity, mode)
        val adapter = SourceAdapter(activity, rows)
        recycler.adapter = adapter

        val titleStr = when (mode) {
            Prefs.TAB_A_SHARE -> "选择 A 股行情源"
            Prefs.TAB_US_STOCK -> "选择 RWA / 美股行情源"
            else -> activity.getString(R.string.source_title)
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(titleStr)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        adapter.onPick = { row ->
            when (mode) {
                Prefs.TAB_A_SHARE -> Prefs.saveAshareSourceId(activity, row.id)
                Prefs.TAB_US_STOCK -> Prefs.saveRwaSourceId(activity, row.id)
                else -> Prefs.saveSourceId(activity, row.id)
            }
            dialog.dismiss()
            onPicked()
        }

        dialog.show()

        // 后台逐个探测延迟
        owner.lifecycleScope.launch(Dispatchers.Main) {
            val sources = when (mode) {
                Prefs.TAB_A_SHARE -> AshareMarkets.SOURCES
                Prefs.TAB_US_STOCK -> RwaMarkets.SOURCES
                else -> Markets.SOURCES.filter { it.id != "us_stock" }
            }
            sources.forEach { src ->
                val ms = withContext(Dispatchers.IO) {
                    when (mode) {
                        Prefs.TAB_A_SHARE -> AshareMarkets.probe(src)
                        Prefs.TAB_US_STOCK -> RwaMarkets.probe(src)
                        else -> Markets.probe(src)
                    }
                }
                val idx = rows.indexOfFirst { it.id == src.id }
                if (idx >= 0) {
                    rows[idx].latencyMs = ms
                    rows[idx].probed = true
                    adapter.notifyItemChanged(idx)
                }
            }
        }
    }

    private fun buildRows(activity: Activity, mode: Int): MutableList<Row> {
        val rows = ArrayList<Row>()
        when (mode) {
            Prefs.TAB_A_SHARE -> {
                val current = Prefs.getAshareSourceId(activity)
                rows.add(
                    Row(
                        id = AshareMarkets.AUTO,
                        title = "自动优选 (新浪 A 股直连)",
                        note = "自动尝试并记忆最佳 A 股行情节点",
                        selected = current == AshareMarkets.AUTO,
                        probed = true
                    )
                )
                AshareMarkets.SOURCES.forEach {
                    rows.add(Row(it.id, it.displayName, it.note, current == it.id))
                }
            }
            Prefs.TAB_US_STOCK -> {
                val current = Prefs.getRwaSourceId(activity)
                rows.add(
                    Row(
                        id = RwaMarkets.AUTO,
                        title = "自动优选 (RWA 全球节点)",
                        note = "自动尝试并记忆最佳 RWA/美股行情节点",
                        selected = current == RwaMarkets.AUTO,
                        probed = true
                    )
                )
                RwaMarkets.SOURCES.forEach {
                    rows.add(Row(it.id, it.displayName, it.note, current == it.id))
                }
            }
            else -> {
                val current = Prefs.getSourceId(activity)
                rows.add(
                    Row(
                        id = Markets.AUTO,
                        title = activity.getString(R.string.source_auto),
                        note = activity.getString(R.string.source_auto_note),
                        selected = current == Markets.AUTO,
                        probed = true
                    )
                )
                Markets.SOURCES.filter { it.id != "us_stock" }.forEach {
                    rows.add(Row(it.id, it.displayName, it.note, current == it.id))
                }
            }
        }
        return rows
    }

    class Row(
        val id: String,
        val title: String,
        val note: String,
        val selected: Boolean,
        var probed: Boolean = false,
        var latencyMs: Long? = null
    )

    private class SourceAdapter(
        private val activity: Activity,
        private val rows: List<Row>
    ) : RecyclerView.Adapter<SourceAdapter.VH>() {

        var onPick: ((Row) -> Unit)? = null

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_source, parent, false))

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val r = rows[position]
            holder.title.text = r.title
            holder.note.text = r.note
            holder.check.visibility = if (r.selected) View.VISIBLE else View.INVISIBLE

            if (r.id == Markets.AUTO || r.id == RwaMarkets.AUTO || r.id == AshareMarkets.AUTO) {
                holder.status.text = ""
            } else if (!r.probed) {
                holder.status.text = activity.getString(R.string.source_probing)
                holder.status.setTextColor(COLOR_DIM)
            } else {
                val ms = r.latencyMs
                if (ms == null) {
                    holder.status.text = activity.getString(R.string.source_unreachable)
                    holder.status.setTextColor(COLOR_DOWN)
                } else {
                    holder.status.text = activity.getString(R.string.source_latency, ms)
                    holder.status.setTextColor(if (ms < 1500) COLOR_OK else COLOR_WARN)
                }
            }

            holder.itemView.setOnClickListener { onPick?.invoke(r) }
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.tvTitle)
            val note: TextView = v.findViewById(R.id.tvNote)
            val status: TextView = v.findViewById(R.id.tvStatus)
            val check: ImageView = v.findViewById(R.id.ivCheck)
        }

        companion object {
            private val COLOR_OK = 0xFF22C55E.toInt()
            private val COLOR_WARN = 0xFFF59E0B.toInt()
            private val COLOR_DOWN = 0xFFEF4444.toInt()
            private val COLOR_DIM = 0xFF5A6478.toInt()
        }
    }
}
