package com.bitdash.app.alert

import android.app.Activity
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bitdash.app.R
import com.bitdash.app.market.Fmt
import com.bitdash.app.market.Prefs
import com.bitdash.app.market.Symbols
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PriceAlertDialog {

    private val POPULAR_COINS = listOf("BTC-USDT", "ETH-USDT", "SOL-USDT", "DOGE-USDT", "BNB-USDT", "XRP-USDT")

    fun show(activity: Activity, prefillSymbol: String? = null, onDismiss: () -> Unit = {}) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                activity.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2001)
            }
        }

        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_price_alerts, null)
        val rvAlerts = dialogView.findViewById<RecyclerView>(R.id.rvAlerts)
        val layoutEmpty = dialogView.findViewById<View>(R.id.layoutEmptyAlerts)
        val btnAdd = dialogView.findViewById<Button>(R.id.btnAddAlert)
        val btnClose = dialogView.findViewById<Button>(R.id.btnCloseAlerts)

        rvAlerts.layoutManager = LinearLayoutManager(activity)

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        fun refreshList() {
            val alerts = PriceAlertManager.getAlerts(activity)
            if (alerts.isEmpty()) {
                layoutEmpty.visibility = View.VISIBLE
                rvAlerts.visibility = View.GONE
            } else {
                layoutEmpty.visibility = View.GONE
                rvAlerts.visibility = View.VISIBLE
                rvAlerts.adapter = AlertsAdapter(activity, alerts) {
                    refreshList()
                }
            }
        }

        btnAdd.setOnClickListener {
            showAddDialog(activity, prefillSymbol) {
                refreshList()
            }
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            onDismiss()
        }

        refreshList()
        dialog.show()
    }

    fun showAddDialog(activity: Activity, prefillSymbol: String? = null, onAdded: () -> Unit) {
        val addView = LayoutInflater.from(activity).inflate(R.layout.dialog_add_price_alert, null)
        val addDialog = AlertDialog.Builder(activity)
            .setView(addView)
            .create()

        addDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val rgMode = addView.findViewById<RadioGroup>(R.id.rgAlertMode)
        val layoutTarget = addView.findViewById<View>(R.id.layoutTargetMode)
        val layoutVol = addView.findViewById<View>(R.id.layoutVolatilityMode)

        val etSymbol = addView.findViewById<EditText>(R.id.etAlertSymbol)
        val layoutQuickCoins = addView.findViewById<LinearLayout>(R.id.layoutQuickCoins)
        val tvRefPrice = addView.findViewById<TextView>(R.id.tvCurrentRefPrice)

        // 目标价控件
        val rbTargetAbove = addView.findViewById<RadioButton>(R.id.rbTargetAbove)
        val etTargetPrice = addView.findViewById<EditText>(R.id.etTargetPrice)

        // 异动控件
        val rgVolWindow = addView.findViewById<RadioGroup>(R.id.rgVolWindow)
        val etVolThreshold = addView.findViewById<EditText>(R.id.etVolThreshold)
        val rgVolDir = addView.findViewById<RadioGroup>(R.id.rgVolDirection)

        val cbRepeat = addView.findViewById<CheckBox>(R.id.cbAlertRepeat)
        val btnCancel = addView.findViewById<Button>(R.id.btnCancelAddAlert)
        val btnSave = addView.findViewById<Button>(R.id.btnSaveAlert)

        // 模式切换
        rgMode.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rbModeTarget) {
                layoutTarget.visibility = View.VISIBLE
                layoutVol.visibility = View.GONE
            } else {
                layoutTarget.visibility = View.GONE
                layoutVol.visibility = View.VISIBLE
            }
        }

        // 默认币种
        val initialSymbol = prefillSymbol ?: Prefs.getWatchlist(activity).firstOrNull() ?: "BTC-USDT"
        etSymbol.setText(initialSymbol)

        var currentRefPrice = 0.0

        fun updateRefPrice(sym: String) {
            val p = PriceAlertManager.getLatestPrice(sym)
            if (p != null && p > 0.0) {
                currentRefPrice = p
                tvRefPrice.visibility = View.VISIBLE
                tvRefPrice.text = "当前参考价: $${Fmt.price(currentRefPrice)}"
                if (etTargetPrice.text.isNullOrBlank()) {
                    etTargetPrice.setText(Fmt.price(currentRefPrice).replace(",", ""))
                }
            } else {
                currentRefPrice = 0.0
                tvRefPrice.visibility = View.GONE
            }
        }

        updateRefPrice(initialSymbol)

        etSymbol.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val input = s?.toString()?.trim() ?: ""
                updateRefPrice(input)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // 快捷币种流
        val candidateCoins = (Prefs.getWatchlist(activity) + POPULAR_COINS).distinct()
        for (coin in candidateCoins.take(8)) {
            val chip = TextView(activity).apply {
                text = coin.substringBefore("-")
                textSize = 11f
                setPadding((8f * resources.displayMetrics.density).toInt(), (4f * resources.displayMetrics.density).toInt(), (8f * resources.displayMetrics.density).toInt(), (4f * resources.displayMetrics.density).toInt())
                setBackgroundResource(R.drawable.bg_pill)
                setTextColor(activity.getColor(R.color.text_muted))
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = (6f * resources.displayMetrics.density).toInt()
                }
                layoutParams = lp
                setOnClickListener {
                    etSymbol.setText(coin)
                }
            }
            layoutQuickCoins.addView(chip)
        }

        // 目标价百分比快捷微调
        fun applyPriceDelta(pct: Double) {
            val base = if (currentRefPrice > 0) currentRefPrice else etTargetPrice.text.toString().toDoubleOrNull() ?: 0.0
            if (base > 0) {
                val target = base * (1.0 + pct / 100.0)
                etTargetPrice.setText(Fmt.price(target).replace(",", ""))
                if (pct > 0) {
                    rbTargetAbove.isChecked = true
                } else {
                    addView.findViewById<RadioButton>(R.id.rbTargetBelow)?.isChecked = true
                }
            }
        }

        addView.findViewById<View>(R.id.btnQuickMinus5)?.setOnClickListener { applyPriceDelta(-5.0) }
        addView.findViewById<View>(R.id.btnQuickMinus2)?.setOnClickListener { applyPriceDelta(-2.0) }
        addView.findViewById<View>(R.id.btnQuickPlus2)?.setOnClickListener { applyPriceDelta(2.0) }
        addView.findViewById<View>(R.id.btnQuickPlus5)?.setOnClickListener { applyPriceDelta(5.0) }
        addView.findViewById<View>(R.id.btnQuickPlus10)?.setOnClickListener { applyPriceDelta(10.0) }

        // 异动阈值快捷填入
        fun setVolThresh(thresh: Double) {
            etVolThreshold.setText(thresh.toString())
        }
        addView.findViewById<View>(R.id.btnVolThresh2)?.setOnClickListener { setVolThresh(2.0) }
        addView.findViewById<View>(R.id.btnVolThresh3)?.setOnClickListener { setVolThresh(3.0) }
        addView.findViewById<View>(R.id.btnVolThresh5)?.setOnClickListener { setVolThresh(5.0) }
        addView.findViewById<View>(R.id.btnVolThresh8)?.setOnClickListener { setVolThresh(8.0) }
        addView.findViewById<View>(R.id.btnVolThresh10)?.setOnClickListener { setVolThresh(10.0) }

        btnCancel.setOnClickListener {
            addDialog.dismiss()
        }

        btnSave.setOnClickListener {
            val symInput = etSymbol.text.toString().trim().uppercase()
            if (symInput.isBlank()) {
                Toast.makeText(activity, "请输入监控币种", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val isTarget = rgMode.checkedRadioButtonId == R.id.rbModeTarget
            if (isTarget) {
                val p = etTargetPrice.text.toString().trim().toDoubleOrNull()
                if (p == null || p <= 0.0) {
                    Toast.makeText(activity, "请输入有效的目标价格", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val isAbove = rbTargetAbove.isChecked
                val alert = PriceAlert(
                    symbol = symInput,
                    type = if (isAbove) AlertType.PRICE_ABOVE else AlertType.PRICE_BELOW,
                    targetPrice = p,
                    repeat = cbRepeat.isChecked
                )
                PriceAlertManager.addAlert(activity, alert)
                Toast.makeText(activity, "已添加目标价预警", Toast.LENGTH_SHORT).show()
            } else {
                val v = etVolThreshold.text.toString().trim().toDoubleOrNull()
                if (v == null || v <= 0.0) {
                    Toast.makeText(activity, "请输入有效的波动阈值百分比", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val winMin = when (rgVolWindow.checkedRadioButtonId) {
                    R.id.rbWin1m -> 1
                    R.id.rbWin3m -> 3
                    R.id.rbWin15m -> 15
                    R.id.rbWin30m -> 30
                    else -> 5
                }
                val dir = when (rgVolDir.checkedRadioButtonId) {
                    R.id.rbVolSurge -> VolatilityDirection.SURGE
                    R.id.rbVolPlunge -> VolatilityDirection.PLUNGE
                    else -> VolatilityDirection.BOTH
                }
                val alert = PriceAlert(
                    symbol = symInput,
                    type = AlertType.VOLATILITY,
                    windowMinutes = winMin,
                    pctThreshold = v,
                    direction = dir,
                    repeat = cbRepeat.isChecked
                )
                PriceAlertManager.addAlert(activity, alert)
                Toast.makeText(activity, "已添加异动预警", Toast.LENGTH_SHORT).show()
            }

            addDialog.dismiss()
            onAdded()
        }

        addDialog.show()
    }

    private class AlertsAdapter(
        private val activity: Activity,
        private val list: List<PriceAlert>,
        private val onDataChanged: () -> Unit
    ) : RecyclerView.Adapter<AlertsAdapter.VH>() {

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val ivIcon: ImageView = v.findViewById(R.id.ivAlertIcon)
            val tvSymbol: TextView = v.findViewById(R.id.tvAlertSymbol)
            val tvTypeBadge: TextView = v.findViewById(R.id.tvAlertTypeBadge)
            val tvSummary: TextView = v.findViewById(R.id.tvAlertSummary)
            val tvStatus: TextView = v.findViewById(R.id.tvAlertStatus)
            val switchEnabled: SwitchCompat = v.findViewById(R.id.switchAlertEnabled)
            val btnDelete: View = v.findViewById(R.id.btnDeleteAlert)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_price_alert, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = list.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = list[position]
            holder.tvSymbol.text = item.symbol
            holder.tvSummary.text = item.summaryText()

            when (item.type) {
                AlertType.PRICE_ABOVE -> {
                    holder.tvTypeBadge.text = "目标突破"
                    holder.ivIcon.setImageResource(R.drawable.ic_trending_up)
                }
                AlertType.PRICE_BELOW -> {
                    holder.tvTypeBadge.text = "跌破提醒"
                    holder.ivIcon.setImageResource(R.drawable.ic_trending_up)
                }
                AlertType.VOLATILITY -> {
                    holder.tvTypeBadge.text = "${item.windowMinutes}m异动"
                    holder.ivIcon.setImageResource(R.drawable.ic_notifications)
                }
            }

            if (item.lastTriggeredTs > 0) {
                val timeStr = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(item.lastTriggeredTs))
                holder.tvStatus.text = "上次触发: $timeStr (触达价 ${Fmt.price(item.lastTriggeredPrice)})"
            } else {
                holder.tvStatus.text = if (item.enabled) "监控中 · 等待触发" else "已暂停监控"
            }

            holder.switchEnabled.setOnCheckedChangeListener(null)
            holder.switchEnabled.isChecked = item.enabled

            holder.switchEnabled.setOnCheckedChangeListener { _, isChecked ->
                PriceAlertManager.toggleAlert(activity, item.id, isChecked)
                onDataChanged()
            }

            holder.btnDelete.setOnClickListener {
                PriceAlertManager.deleteAlert(activity, item.id)
                onDataChanged()
                Toast.makeText(activity, "已删除该预警规则", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
