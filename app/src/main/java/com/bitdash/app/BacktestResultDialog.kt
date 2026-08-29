package com.bitdash.app

import android.app.Activity
import android.app.AlertDialog
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bitdash.app.market.BacktestResult
import com.bitdash.app.market.BacktestTrade
import com.bitdash.app.market.Fmt
import com.bitdash.app.market.Palette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 历史策略回测结果报告弹窗
 */
object BacktestResultDialog {

    fun show(activity: Activity, result: BacktestResult) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_backtest_result, null)
        val tvHeaderMeta = view.findViewById<TextView>(R.id.tvBtHeaderMeta)
        val btnClose = view.findViewById<Button>(R.id.btnBtClose)
        val tvNetProfit = view.findViewById<TextView>(R.id.tvBtNetProfit)
        val tvFinalBalance = view.findViewById<TextView>(R.id.tvBtFinalBalance)
        val tvWinRate = view.findViewById<TextView>(R.id.tvBtWinRate)
        val tvTotalTrades = view.findViewById<TextView>(R.id.tvBtTotalTrades)
        val tvProfitFactor = view.findViewById<TextView>(R.id.tvBtProfitFactor)
        val tvMaxDd = view.findViewById<TextView>(R.id.tvBtMaxDd)
        val tvSectionTitle = view.findViewById<TextView>(R.id.tvBtTradeSectionTitle)
        val rvTrades = view.findViewById<RecyclerView>(R.id.rvBtTrades)
        val tvEmpty = view.findViewById<TextView>(R.id.tvBtEmptyTrades)

        val dialog = AlertDialog.Builder(activity)
            .setView(view)
            .create()

        btnClose.setOnClickListener { dialog.dismiss() }

        tvHeaderMeta.text = "${result.strategyName} · ${result.symbol} ${result.timeframe} (${result.candleCount} 根 K 线)"

        val upColor = Palette.up(activity)
        val downColor = Palette.down(activity)

        val pnl = result.totalProfit
        val pnlPct = result.totalProfitPct
        val pnlSign = if (pnl >= 0) "+" else ""
        tvNetProfit.text = "$pnlSign$${String.format(Locale.US, "%.2f", pnl)} ($pnlSign${String.format(Locale.US, "%.2f", pnlPct)}%)"
        tvNetProfit.setTextColor(if (pnl >= 0) upColor else downColor)

        tvFinalBalance.text = "$${String.format(Locale.US, "%.2f", result.finalBalance)}"
        tvWinRate.text = "${String.format(Locale.US, "%.1f%%", result.winRate)} (${result.winTrades}/${result.lossTrades})"
        tvTotalTrades.text = "${result.totalTrades} 笔"
        tvProfitFactor.text = String.format(Locale.US, "%.2f", result.profitFactor)
        tvMaxDd.text = "${String.format(Locale.US, "%.2f", result.maxDrawdownPct)}%"

        tvSectionTitle.text = "逐笔交易明细流水 (共 ${result.trades.size} 笔)"

        if (result.trades.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            rvTrades.visibility = View.GONE
        } else {
            tvEmpty.visibility = View.GONE
            rvTrades.visibility = View.VISIBLE
            rvTrades.layoutManager = LinearLayoutManager(activity)
            rvTrades.adapter = BacktestTradeAdapter(activity, result.trades)
        }

        dialog.show()
    }

    private class BacktestTradeAdapter(
        private val activity: Activity,
        private val trades: List<BacktestTrade>
    ) : RecyclerView.Adapter<BacktestTradeAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvSide: TextView = v.findViewById(R.id.tvBtSide)
            val tvEntryExit: TextView = v.findViewById(R.id.tvBtEntryExit)
            val tvPnl: TextView = v.findViewById(R.id.tvBtPnl)
            val tvReason: TextView = v.findViewById(R.id.tvBtReason)
            val tvTime: TextView = v.findViewById(R.id.tvBtTime)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_backtest_trade, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = trades.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = trades[position]
            val upColor = Palette.up(activity)
            val downColor = Palette.down(activity)

            val isLong = item.side == "LONG"
            holder.tvSide.text = if (isLong) "多单平仓" else "空单平仓"
            holder.tvSide.setTextColor(if (isLong) downColor else upColor)

            holder.tvEntryExit.text = "$${Fmt.price(item.entryPrice)} → $${Fmt.price(item.exitPrice)}"

            val pnlSign = if (item.netPnl >= 0) "+" else ""
            holder.tvPnl.text = "$pnlSign$${String.format(Locale.US, "%.2f", item.netPnl)} ($pnlSign${String.format(Locale.US, "%.2f", item.pnlPercent)}%)"
            holder.tvPnl.setTextColor(if (item.netPnl >= 0) upColor else downColor)

            holder.tvReason.text = item.exitReason
            holder.tvTime.text = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(item.exitTime))
        }
    }
}
