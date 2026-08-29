package com.bitdash.app

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.bitdash.app.market.PaperTradingPrefs
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 回测参数与日期间隔配置弹窗 (带用户偏好持久化记忆功能)
 */
object BacktestSetupDialog {

    fun show(
        activity: Activity,
        symbol: String,
        timeframe: String,
        strategyDisplayName: String,
        onConfirm: (startTimeMs: Long?, endTimeMs: Long?, initialCapital: Double, fixedTradeAmount: Double) -> Unit
    ) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_backtest_setup, null)
        val tvMeta = view.findViewById<TextView>(R.id.tvSetupStrategyMeta)
        val btnCancel = view.findViewById<Button>(R.id.btnCancelSetup)
        val spnDateRange = view.findViewById<Spinner>(R.id.spnDateRange)
        val layoutCustom = view.findViewById<LinearLayout>(R.id.layoutCustomDates)
        val btnPickStart = view.findViewById<Button>(R.id.btnPickStartDate)
        val btnPickEnd = view.findViewById<Button>(R.id.btnPickEndDate)
        val etCapital = view.findViewById<EditText>(R.id.etInitialCapital)
        val etFixed = view.findViewById<EditText>(R.id.etFixedTradeAmount)
        val btnExecute = view.findViewById<Button>(R.id.btnExecuteBacktest)

        tvMeta.text = "标的: $symbol · 周期: $timeframe · 策略: $strategyDisplayName"

        val dialog = AlertDialog.Builder(activity)
            .setView(view)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }

        // 1. 读取上次记忆的回测设置
        val savedRangeIdx = PaperTradingPrefs.getBacktestRangeIndex(activity).coerceIn(0, 6)
        val savedStartMs = PaperTradingPrefs.getBacktestStartMs(activity)
        val savedEndMs = PaperTradingPrefs.getBacktestEndMs(activity)
        val savedCapital = PaperTradingPrefs.getBacktestCapital(activity)
        val savedFixedAmount = PaperTradingPrefs.getBacktestFixedAmount(activity)

        // 2. 初始化输入框与日期
        val capStr = if (savedCapital % 1.0 == 0.0) savedCapital.toLong().toString() else savedCapital.toString()
        etCapital.setText(capStr)

        val fixedStr = if (savedFixedAmount % 1.0 == 0.0) savedFixedAmount.toLong().toString() else savedFixedAmount.toString()
        etFixed.setText(fixedStr)

        val rangeOptions = listOf(
            "全部可用历史 K 线 (最大深度)",
            "最近 24 小时 (1 天)",
            "最近 3 天",
            "最近 7 天 (1 周)",
            "最近 30 天 (1 个月)",
            "最近 90 天 (3 个月)",
            "自定义起止日期..."
        )
        val rangeAdapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, rangeOptions)
        spnDateRange.adapter = rangeAdapter
        spnDateRange.setSelection(savedRangeIdx)

        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        var selectedStartDate = Date(savedStartMs)
        var selectedEndDate = Date(savedEndMs)

        btnPickStart.text = sdf.format(selectedStartDate)
        btnPickEnd.text = sdf.format(selectedEndDate)
        layoutCustom.visibility = if (savedRangeIdx == 6) View.VISIBLE else View.GONE

        btnPickStart.setOnClickListener {
            val c = Calendar.getInstance().apply { time = selectedStartDate }
            DatePickerDialog(activity, { _, year, month, day ->
                val newCal = Calendar.getInstance().apply {
                    set(year, month, day, 0, 0, 0)
                }
                selectedStartDate = newCal.time
                btnPickStart.text = sdf.format(selectedStartDate)
            }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
        }

        btnPickEnd.setOnClickListener {
            val c = Calendar.getInstance().apply { time = selectedEndDate }
            DatePickerDialog(activity, { _, year, month, day ->
                val newCal = Calendar.getInstance().apply {
                    set(year, month, day, 23, 59, 59)
                }
                selectedEndDate = newCal.time
                btnPickEnd.text = sdf.format(selectedEndDate)
            }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
        }

        spnDateRange.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                layoutCustom.visibility = if (position == 6) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnExecute.setOnClickListener {
            val capital = etCapital.text.toString().toDoubleOrNull() ?: 10000.0
            if (capital <= 0) {
                Toast.makeText(activity, "请输入有效的初始资金", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val fixedAmount = etFixed.text.toString().toDoubleOrNull() ?: 0.0
            val chosenRangeIdx = spnDateRange.selectedItemPosition

            val now = System.currentTimeMillis()
            var startTime: Long? = null
            var endTime: Long? = null

            when (chosenRangeIdx) {
                0 -> { // 全部
                    startTime = null
                    endTime = null
                }
                1 -> { // 24小时
                    startTime = now - 24 * 60 * 60 * 1000L
                    endTime = now
                }
                2 -> { // 3天
                    startTime = now - 3 * 24 * 60 * 60 * 1000L
                    endTime = now
                }
                3 -> { // 7天
                    startTime = now - 7 * 24 * 60 * 60 * 1000L
                    endTime = now
                }
                4 -> { // 30天
                    startTime = now - 30 * 24 * 60 * 60 * 1000L
                    endTime = now
                }
                5 -> { // 90天
                    startTime = now - 90 * 24 * 60 * 60 * 1000L
                    endTime = now
                }
                6 -> { // 自定义
                    startTime = selectedStartDate.time
                    endTime = selectedEndDate.time + 24 * 60 * 60 * 1000L - 1 // 包含结束当天的整天
                    if (startTime > endTime) {
                        Toast.makeText(activity, "开始日期不能晚于结束日期", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                }
            }

            // 3. 持久化保存本次回测设置，供下次记忆复用
            PaperTradingPrefs.setBacktestRangeIndex(activity, chosenRangeIdx)
            PaperTradingPrefs.setBacktestStartMs(activity, selectedStartDate.time)
            PaperTradingPrefs.setBacktestEndMs(activity, selectedEndDate.time)
            PaperTradingPrefs.setBacktestCapital(activity, capital)
            PaperTradingPrefs.setBacktestFixedAmount(activity, fixedAmount)

            dialog.dismiss()
            onConfirm(startTime, endTime, capital, fixedAmount)
        }

        dialog.show()
    }
}
