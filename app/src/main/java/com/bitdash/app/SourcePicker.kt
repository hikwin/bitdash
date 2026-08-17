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
import com.bitdash.app.market.Markets
import com.bitdash.app.market.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 行情源选择弹窗。
 *
 * 展示"自动选择"加全部候选源，并在后台并发探测每个源的连通性与延迟，
 * 让用户能直观看出哪些源在当前网络下可用（内地网络尤其需要）。
 */
object SourcePicker {

    /**
     * @param onPicked 用户选定并关闭弹窗后回调（选择已写入 [Prefs]）
     */
    fun show(activity: Activity, onPicked: () -> Unit) {
        val owner = activity as? LifecycleOwner ?: return

        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_source, null)
        val recycler = view.findViewById<RecyclerView>(R.id.recyclerSource)
        recycler.layoutManager = LinearLayoutManager(activity)

        val rows = buildRows(activity)
        val adapter = SourceAdapter(activity, rows)
        recycler.adapter = adapter

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.source_title)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        adapter.onPick = { row ->
            Prefs.saveSourceId(activity, row.id)
            dialog.dismiss()
            onPicked()
        }

        dialog.show()

        // 后台逐个探测延迟（串行，避免同时打开 7 条 TLS 连接拖慢彼此）
        owner.lifecycleScope.launch(Dispatchers.Main) {
            Markets.SOURCES.forEachIndexed { _, src ->
                val ms = withContext(Dispatchers.IO) { Markets.probe(src) }
                val idx = rows.indexOfFirst { it.id == src.id }
                if (idx >= 0) {
                    rows[idx].latencyMs = ms
                    rows[idx].probed = true
                    adapter.notifyItemChanged(idx)
                }
            }
        }
    }

    private fun buildRows(activity: Activity): MutableList<Row> {
        val current = Prefs.getSourceId(activity)
        val rows = ArrayList<Row>()
        rows.add(
            Row(
                id = Markets.AUTO,
                title = activity.getString(R.string.source_auto),
                note = activity.getString(R.string.source_auto_note),
                selected = current == Markets.AUTO,
                probed = true          // 自动项不需要探测
            )
        )
        Markets.SOURCES.forEach {
            rows.add(Row(it.id, it.displayName, it.note, current == it.id))
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

            // 自动项不显示探测结果
            if (r.id == Markets.AUTO) {
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
                    // 延迟分档着色，让用户一眼看出优劣
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
