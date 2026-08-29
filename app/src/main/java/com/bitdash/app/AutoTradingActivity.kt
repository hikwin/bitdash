package com.bitdash.app

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bitdash.app.market.Bar
import com.bitdash.app.market.Candle
import com.bitdash.app.market.Fmt
import com.bitdash.app.market.Markets
import com.bitdash.app.market.Palette
import com.bitdash.app.market.PaperTradeRecord
import com.bitdash.app.market.PaperTradingEngine
import com.bitdash.app.market.PaperTradingPrefs
import com.bitdash.app.market.PineScript
import com.bitdash.app.market.PineScriptManager
import com.bitdash.app.market.Prefs
import com.bitdash.app.market.Symbols
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 自动化量化模拟交易专属页面 (Paper Trading Activity)
 * 支持 3K 趋势突破、洛伦兹分类、双均线交叉以及自定义 Pine Script 策略
 */
class AutoTradingActivity : BaseActivity() {

    companion object {
        const val EXTRA_SYMBOL = "extra_symbol"
        const val EXTRA_TIMEFRAME = "extra_timeframe"

        fun start(context: Context, symbol: String? = null, timeframe: String? = null) {
            val intent = Intent(context, AutoTradingActivity::class.java).apply {
                if (!symbol.isNullOrEmpty()) putExtra(EXTRA_SYMBOL, symbol)
                if (!timeframe.isNullOrEmpty()) putExtra(EXTRA_TIMEFRAME, timeframe)
            }
            context.startActivity(intent)
        }
    }

    private lateinit var engine: PaperTradingEngine

    private lateinit var btnBack: ImageButton
    private lateinit var btnSelectSymbol: LinearLayout
    private lateinit var tvSelectedSymbol: TextView
    private lateinit var btnResetFunds: LinearLayout

    private lateinit var tvEngineStatus: TextView
    private lateinit var tvStrategyStatusTag: TextView
    private lateinit var tvLivePrice: TextView
    private lateinit var btnToggleAutoTrading: Button

    private lateinit var tvTotalEquity: TextView
    private lateinit var tvAvailableBalance: TextView
    private lateinit var tvRealizedPnl: TextView
    private lateinit var tvWinRate: TextView
    private lateinit var tvTotalTrades: TextView

    private lateinit var spnStrategy: Spinner
    private lateinit var layoutPineSelector: LinearLayout
    private lateinit var spnPineScript: Spinner
    private lateinit var spnLeverage: Spinner
    private lateinit var spnRisk: Spinner
    private lateinit var cbAntiChop: CheckBox
    private lateinit var btnRunBacktest: Button

    private lateinit var tvPosSideTag: TextView
    private lateinit var tvNoPosition: TextView
    private lateinit var layoutActivePos: LinearLayout
    private lateinit var tvPosUnrealizedPnl: TextView
    private lateinit var tvPosEntryAndQty: TextView
    private lateinit var tvPosSl: TextView
    private lateinit var tvPosTp1: TextView
    private lateinit var btnManualBe: Button
    private lateinit var btnManualClose: Button

    private lateinit var tabLogs: TextView
    private lateinit var tabHistory: TextView
    private lateinit var btnClearLogsOrHistory: Button
    private lateinit var scrollLogs: ScrollView
    private lateinit var tvLogsContent: TextView
    private lateinit var rvTradeHistory: RecyclerView

    private val tradeAdapter = TradeHistoryAdapter()
    private var isLogsTabSelected = true

    private var currentSymbol = "BTC-USDT"
    private var currentTimeframe = "15m"
    private var lastCandles: List<Candle> = emptyList()
    private var lastPrice = 0.0

    private var marketJob: Job? = null
    private var pineScriptsList: List<PineScript> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auto_trading)

        engine = PaperTradingEngine(this)

        intent.getStringExtra(EXTRA_SYMBOL)?.let {
            if (it.isNotEmpty()) currentSymbol = it
        } ?: run {
            currentSymbol = PaperTradingPrefs.getSymbol(this)
        }

        intent.getStringExtra(EXTRA_TIMEFRAME)?.let {
            if (it.isNotEmpty()) currentTimeframe = it
        } ?: run {
            currentTimeframe = PaperTradingPrefs.getTimeframe(this)
        }

        initViews()
        setupListeners()
        setupSpinners()

        engine.onStateChanged = {
            runOnUiThread { updateUI() }
        }

        updateUI()
        startMarketDataLoop()
    }

    private fun initViews() {
        btnBack = findViewById(R.id.btnBack)
        btnSelectSymbol = findViewById(R.id.btnSelectSymbol)
        tvSelectedSymbol = findViewById(R.id.tvSelectedSymbol)
        btnResetFunds = findViewById(R.id.btnResetFunds)

        tvEngineStatus = findViewById(R.id.tvEngineStatus)
        tvStrategyStatusTag = findViewById(R.id.tvStrategyStatusTag)
        tvLivePrice = findViewById(R.id.tvLivePrice)
        btnToggleAutoTrading = findViewById(R.id.btnToggleAutoTrading)

        tvTotalEquity = findViewById(R.id.tvTotalEquity)
        tvAvailableBalance = findViewById(R.id.tvAvailableBalance)
        tvRealizedPnl = findViewById(R.id.tvRealizedPnl)
        tvWinRate = findViewById(R.id.tvWinRate)
        tvTotalTrades = findViewById(R.id.tvTotalTrades)

        spnStrategy = findViewById(R.id.spnStrategy)
        layoutPineSelector = findViewById(R.id.layoutPineSelector)
        spnPineScript = findViewById(R.id.spnPineScript)
        spnLeverage = findViewById(R.id.spnLeverage)
        spnRisk = findViewById(R.id.spnRisk)
        cbAntiChop = findViewById(R.id.cbAntiChop)

        tvPosSideTag = findViewById(R.id.tvPosSideTag)
        tvNoPosition = findViewById(R.id.tvNoPosition)
        layoutActivePos = findViewById(R.id.layoutActivePos)
        tvPosUnrealizedPnl = findViewById(R.id.tvPosUnrealizedPnl)
        tvPosEntryAndQty = findViewById(R.id.tvPosEntryAndQty)
        tvPosSl = findViewById(R.id.tvPosSl)
        tvPosTp1 = findViewById(R.id.tvPosTp1)
        btnManualBe = findViewById(R.id.btnManualBe)
        btnManualClose = findViewById(R.id.btnManualClose)

        tabLogs = findViewById(R.id.tabLogs)
        tabHistory = findViewById(R.id.tabHistory)
        btnClearLogsOrHistory = findViewById(R.id.btnClearLogsOrHistory)
        scrollLogs = findViewById(R.id.scrollLogs)
        tvLogsContent = findViewById(R.id.tvLogsContent)
        rvTradeHistory = findViewById(R.id.rvTradeHistory)

        rvTradeHistory.layoutManager = LinearLayoutManager(this)
        rvTradeHistory.adapter = tradeAdapter

        tvSelectedSymbol.text = "$currentSymbol $currentTimeframe"
    }

    private fun setupListeners() {
        btnBack.setOnClickListener { finish() }

        btnSelectSymbol.setOnClickListener { showSymbolAndTfDialog() }

        btnResetFunds.setOnClickListener { showResetFundsConfirmDialog() }

        btnToggleAutoTrading.setOnClickListener {
            val newState = !engine.isAutoTrading
            engine.setAutoTrading(newState)
            updateUI()
        }

        btnManualBe.setOnClickListener {
            engine.manualMoveToBreakeven()
            Toast.makeText(this, "已将止损移至开仓保本价", Toast.LENGTH_SHORT).show()
        }

        btnManualClose.setOnClickListener {
            if (lastPrice > 0) {
                engine.manualMarketClose(lastPrice)
                Toast.makeText(this, "已市价全平当前持仓", Toast.LENGTH_SHORT).show()
            }
        }

        tabLogs.setOnClickListener {
            isLogsTabSelected = true
            updateTabs()
        }

        tabHistory.setOnClickListener {
            isLogsTabSelected = false
            updateTabs()
        }

        btnClearLogsOrHistory.setOnClickListener {
            if (isLogsTabSelected) {
                AlertDialog.Builder(this)
                    .setTitle("清空实时运行日志")
                    .setMessage("确定要清空当前所有实时运行日志吗？")
                    .setPositiveButton("确定清空") { _, _ ->
                        engine.clearLogs()
                        tvLogsContent.text = "暂无日志"
                        Toast.makeText(this, "实时运行日志已清空", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            } else {
                AlertDialog.Builder(this)
                    .setTitle("清空历史成交记录")
                    .setMessage("确定要清空全部模拟成交历史记录吗？")
                    .setPositiveButton("确定清空") { _, _ ->
                        engine.clearTradeHistory()
                        tradeAdapter.submitList(emptyList())
                        updateUI()
                        Toast.makeText(this, "历史成交记录已清空", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }

        cbAntiChop.isChecked = engine.antiChopEnabled
        cbAntiChop.setOnCheckedChangeListener { _, isChecked ->
            engine.antiChopEnabled = isChecked
            PaperTradingPrefs.setAntiChopEnabled(this, isChecked)
        }

        btnRunBacktest = findViewById(R.id.btnRunBacktest)
        btnRunBacktest.setOnClickListener {
            runBacktestAction()
        }
    }

    private fun runBacktestAction() {
        val stratName = when (engine.strategyMode) {
            PaperTradingEngine.STRATEGY_3K -> "3K 趋势突破"
            PaperTradingEngine.STRATEGY_LORENTZIAN -> "洛伦兹 ML 分类"
            PaperTradingEngine.STRATEGY_EMA_CROSS -> "双均线交叉 (EMA 9/21)"
            PaperTradingEngine.STRATEGY_PINE -> {
                val script = pineScriptsList.find { it.id == engine.selectedPineId }
                "Pine: ${script?.name ?: "自定义"}"
            }
            else -> engine.strategyMode
        }

        val normSymbol = Symbols.expand(currentSymbol) ?: currentSymbol

        BacktestSetupDialog.show(
            activity = this,
            symbol = normSymbol,
            timeframe = currentTimeframe,
            strategyDisplayName = stratName
        ) { startTimeMs, endTimeMs, initialCapital, fixedTradeAmount ->
            Toast.makeText(this, "正在从默认行情源拉取 K 线并执行深度回测...", Toast.LENGTH_SHORT).show()
            btnRunBacktest.isEnabled = false
            btnRunBacktest.text = "⏳ 回测运算中..."

            lifecycleScope.launch(Dispatchers.IO) {
                val bar = Bar.fromKey(currentTimeframe)
                var fetchedCandles = try {
                    Markets.withSource(this@AutoTradingActivity) { src ->
                        src.candles(normSymbol, bar, 1000)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    emptyList()
                }

                if (fetchedCandles.isEmpty() && lastCandles.isNotEmpty()) {
                    fetchedCandles = lastCandles
                }

                if (fetchedCandles.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        btnRunBacktest.isEnabled = true
                        btnRunBacktest.text = "⚡ 策略历史回测与日期间隔分析"
                        Toast.makeText(this@AutoTradingActivity, "未能从当前行情源拉取到 K 线，请检查网络或切换行情源", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                lastCandles = fetchedCandles

                // 根据起止时间戳过滤 K 线
                var candlesToTest = fetchedCandles
                if (startTimeMs != null && endTimeMs != null) {
                    val filtered = fetchedCandles.filter { it.ts in startTimeMs..endTimeMs }
                    if (filtered.size >= 10) {
                        candlesToTest = filtered
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@AutoTradingActivity, "所选区间 K 线较少，已自动使用全部 ${fetchedCandles.size} 根 K 线回测", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else if (startTimeMs != null) {
                    val filtered = fetchedCandles.filter { it.ts >= startTimeMs }
                    if (filtered.size >= 10) {
                        candlesToTest = filtered
                    }
                }

                val selectedPine = if (engine.strategyMode == PaperTradingEngine.STRATEGY_PINE) {
                    pineScriptsList.find { it.id == engine.selectedPineId } ?: pineScriptsList.firstOrNull()
                } else null

                val result = com.bitdash.app.market.StrategyBacktester.runBacktest(
                    candles = candlesToTest,
                    symbol = normSymbol,
                    timeframe = currentTimeframe,
                    strategyMode = engine.strategyMode,
                    pineScript = selectedPine,
                    initialBalance = initialCapital,
                    riskPercent = engine.riskPercent,
                    leverage = engine.leverage,
                    antiChopEnabled = engine.antiChopEnabled,
                    fixedTradeAmount = fixedTradeAmount
                )

                withContext(Dispatchers.Main) {
                    btnRunBacktest.isEnabled = true
                    btnRunBacktest.text = "⚡ 策略历史回测与日期间隔分析"
                    BacktestResultDialog.show(this@AutoTradingActivity, result)
                }
            }
        }
    }

    private fun setupSpinners() {
        // 1. 策略选择器
        val strategyOptions = listOf(
            "3K 趋势突破策略 (BTC 1D Alpha)",
            "洛伦兹分类 (Machine Learning)",
            "双均线交叉 (EMA 9/21)",
            "自定义 Pine Script 策略"
        )
        val strategyAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, strategyOptions)
        spnStrategy.adapter = strategyAdapter

        val initialStrategyIdx = when (engine.strategyMode) {
            PaperTradingEngine.STRATEGY_3K -> 0
            PaperTradingEngine.STRATEGY_LORENTZIAN -> 1
            PaperTradingEngine.STRATEGY_EMA_CROSS -> 2
            PaperTradingEngine.STRATEGY_PINE -> 3
            else -> 0
        }
        spnStrategy.setSelection(initialStrategyIdx)

        spnStrategy.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val mode = when (position) {
                    0 -> PaperTradingEngine.STRATEGY_3K
                    1 -> PaperTradingEngine.STRATEGY_LORENTZIAN
                    2 -> PaperTradingEngine.STRATEGY_EMA_CROSS
                    3 -> PaperTradingEngine.STRATEGY_PINE
                    else -> PaperTradingEngine.STRATEGY_3K
                }
                layoutPineSelector.visibility = if (mode == PaperTradingEngine.STRATEGY_PINE) View.VISIBLE else View.GONE
                engine.updateSettings(mode, engine.selectedPineId, engine.riskPercent, engine.leverage, engine.antiChopEnabled)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 2. Pine 脚本下拉选择器
        pineScriptsList = PineScriptManager.getScripts(this)
        val pineNames = pineScriptsList.map { it.name }
        val pineAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, pineNames)
        spnPineScript.adapter = pineAdapter

        val pineIdx = pineScriptsList.indexOfFirst { it.id == engine.selectedPineId }
        if (pineIdx >= 0) spnPineScript.setSelection(pineIdx)

        spnPineScript.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position in pineScriptsList.indices) {
                    val scriptId = pineScriptsList[position].id
                    engine.updateSettings(engine.strategyMode, scriptId, engine.riskPercent, engine.leverage, engine.antiChopEnabled)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 3. 杠杆倍数
        val leverageOptions = listOf("1X", "2X", "3X", "5X", "10X", "20X")
        val levAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, leverageOptions)
        spnLeverage.adapter = levAdapter
        val curLevStr = "${engine.leverage}X"
        val levIdx = leverageOptions.indexOf(curLevStr)
        if (levIdx >= 0) spnLeverage.setSelection(levIdx)

        spnLeverage.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val lev = leverageOptions[position].replace("X", "").toIntOrNull() ?: 3
                engine.updateSettings(engine.strategyMode, engine.selectedPineId, engine.riskPercent, lev, engine.antiChopEnabled)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 4. 风险比例
        val riskOptions = listOf("1.0%", "2.0%", "3.0%", "5.0%", "10.0%")
        val riskAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, riskOptions)
        spnRisk.adapter = riskAdapter
        val curRiskStr = String.format(Locale.US, "%.1f%%", engine.riskPercent)
        val riskIdx = riskOptions.indexOf(curRiskStr)
        if (riskIdx >= 0) spnRisk.setSelection(riskIdx)

        spnRisk.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val risk = riskOptions[position].replace("%", "").toDoubleOrNull() ?: 2.0
                engine.updateSettings(engine.strategyMode, engine.selectedPineId, risk, engine.leverage, engine.antiChopEnabled)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun updateTabs() {
        if (isLogsTabSelected) {
            tabLogs.setTextColor(ContextCompat.getColor(this, R.color.brand))
            tabHistory.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
            scrollLogs.visibility = View.VISIBLE
            rvTradeHistory.visibility = View.GONE
        } else {
            tabLogs.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
            tabHistory.setTextColor(ContextCompat.getColor(this, R.color.brand))
            scrollLogs.visibility = View.GONE
            rvTradeHistory.visibility = View.VISIBLE
            tradeAdapter.submitList(engine.tradeHistory)
        }
    }

    private fun updateUI() {
        val upColor = Palette.up(this)
        val downColor = Palette.down(this)

        // 1. 顶部引擎开关与状态
        if (engine.isAutoTrading) {
            tvEngineStatus.text = getString(R.string.auto_trading_running)
            tvEngineStatus.setTextColor(downColor)
            btnToggleAutoTrading.text = getString(R.string.auto_trading_stop)
            btnToggleAutoTrading.setBackgroundResource(R.drawable.bg_pill)
        } else {
            tvEngineStatus.text = getString(R.string.auto_trading_stopped)
            tvEngineStatus.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
            btnToggleAutoTrading.text = getString(R.string.auto_trading_start)
            btnToggleAutoTrading.setBackgroundResource(R.drawable.bg_btn_primary)
        }

        val stratTag = when (engine.strategyMode) {
            PaperTradingEngine.STRATEGY_3K -> "3K 趋势突破"
            PaperTradingEngine.STRATEGY_LORENTZIAN -> "洛伦兹 ML 分类"
            PaperTradingEngine.STRATEGY_EMA_CROSS -> "双均线交叉"
            PaperTradingEngine.STRATEGY_PINE -> {
                val script = pineScriptsList.find { it.id == engine.selectedPineId }
                "Pine: ${script?.name ?: "自定义"}"
            }
            else -> "3K 趋势突破"
        }
        tvStrategyStatusTag.text = "· $stratTag"

        if (lastPrice > 0) {
            tvLivePrice.text = "$currentSymbol: $${Fmt.price(lastPrice)}"
        }

        // 2. 资产看板
        tvTotalEquity.text = String.format(Locale.US, "%.2f", engine.totalEquity)
        tvAvailableBalance.text = String.format(Locale.US, "%.2f", engine.balance)

        val pnl = engine.totalRealizedPnl
        val pnlPct = if (engine.balance > 0) (pnl / PaperTradingPrefs.DEFAULT_INITIAL_BALANCE) * 100.0 else 0.0
        val pnlSign = if (pnl >= 0) "+" else ""
        tvRealizedPnl.text = "$pnlSign$${String.format(Locale.US, "%.2f", pnl)} ($pnlSign${String.format(Locale.US, "%.2f", pnlPct)}%)"
        tvRealizedPnl.setTextColor(if (pnl >= 0) upColor else downColor)

        tvWinRate.text = String.format(Locale.US, "%.1f%%", engine.winRate)
        tvTotalTrades.text = "${engine.tradeHistory.size} 笔"

        // 3. 持仓信息
        val pos = engine.position
        if (pos == null) {
            tvPosSideTag.visibility = View.GONE
            tvNoPosition.visibility = View.VISIBLE
            layoutActivePos.visibility = View.GONE
        } else {
            tvPosSideTag.visibility = View.VISIBLE
            tvNoPosition.visibility = View.GONE
            layoutActivePos.visibility = View.VISIBLE

            val isLong = pos.side == "LONG"
            tvPosSideTag.text = if (isLong) "🟢 多仓 (${pos.leverage}X)" else "🔴 空仓 (${pos.leverage}X)"
            tvPosSideTag.setTextColor(if (isLong) downColor else upColor)

            val uPnl = pos.unrealizedPnl
            val uPct = pos.pnlPct
            val uSign = if (uPnl >= 0) "+" else ""
            tvPosUnrealizedPnl.text = "$uSign$${String.format(Locale.US, "%.2f", uPnl)} ($uSign${String.format(Locale.US, "%.2f", uPct)}%)"
            tvPosUnrealizedPnl.setTextColor(if (uPnl >= 0) upColor else downColor)

            tvPosEntryAndQty.text = "$${Fmt.price(pos.entryPrice)} / ${String.format(Locale.US, "%.4f", pos.quantity)}"
            tvPosSl.text = "$${Fmt.price(pos.slPrice)}"
            tvPosTp1.text = "$${Fmt.price(pos.tp1Price)}"
        }

        // 4. 日志内容
        val logsText = engine.logs.joinToString("\n")
        tvLogsContent.text = if (logsText.isEmpty()) "暂无日志" else logsText

        if (!isLogsTabSelected) {
            tradeAdapter.submitList(engine.tradeHistory)
        }
    }

    private fun startMarketDataLoop() {
        marketJob?.cancel()
        marketJob = lifecycleScope.launch(Dispatchers.IO) {
            val bar = Bar.fromKey(currentTimeframe)
            while (isActive) {
                try {
                    val candles = Markets.withSource(this@AutoTradingActivity) { src ->
                        src.candles(currentSymbol, bar, 150)
                    }
                    if (candles.isNotEmpty()) {
                        lastCandles = candles
                        lastPrice = candles.last().close

                        withContext(Dispatchers.Main) {
                            engine.evaluateAndExecute(currentSymbol, lastPrice, candles)
                            updateUI()
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                delay(3000) // 每 3 秒拉取一次最新 K 线与报价
            }
        }
    }

    private fun showSymbolAndTfDialog() {
        val watchList = Prefs.getWatchlist(this)
        val symbols = if (watchList.isNotEmpty()) {
            val list = mutableListOf<String>()
            if (!watchList.contains(currentSymbol)) {
                list.add(currentSymbol)
            }
            list.addAll(watchList)
            list.distinct()
        } else {
            listOf("BTC-USDT", "ETH-USDT", "SOL-USDT", "BNB-USDT", "DOGE-USDT", "XRP-USDT", "AVAX-USDT", "ADA-USDT")
        }
        val timeframes = listOf("1m", "5m", "15m", "1H", "4H", "1D")

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }

        val tvSymLabel = TextView(this).apply {
            text = "选择交易对"
            setTextColor(ContextCompat.getColor(this@AutoTradingActivity, R.color.text_main))
            textSize = 13f
            setPadding(0, 0, 0, 8)
        }
        val spSym = Spinner(this).apply {
            adapter = ArrayAdapter(this@AutoTradingActivity, android.R.layout.simple_spinner_dropdown_item, symbols)
            val idx = symbols.indexOf(currentSymbol)
            if (idx >= 0) setSelection(idx)
        }

        val tvTfLabel = TextView(this).apply {
            text = "选择 K 线周期"
            setTextColor(ContextCompat.getColor(this@AutoTradingActivity, R.color.text_main))
            textSize = 13f
            setPadding(0, 24, 0, 8)
        }
        val spTf = Spinner(this).apply {
            adapter = ArrayAdapter(this@AutoTradingActivity, android.R.layout.simple_spinner_dropdown_item, timeframes)
            val idx = timeframes.indexOf(currentTimeframe)
            if (idx >= 0) setSelection(idx)
        }

        layout.addView(tvSymLabel)
        layout.addView(spSym)
        layout.addView(tvTfLabel)
        layout.addView(spTf)

        AlertDialog.Builder(this)
            .setTitle("选择自动化交易标的与周期")
            .setView(layout)
            .setPositiveButton("确定") { _, _ ->
                currentSymbol = symbols[spSym.selectedItemPosition]
                currentTimeframe = timeframes[spTf.selectedItemPosition]
                tvSelectedSymbol.text = "$currentSymbol $currentTimeframe"
                PaperTradingPrefs.setSymbol(this, currentSymbol)
                PaperTradingPrefs.setTimeframe(this, currentTimeframe)
                startMarketDataLoop()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showResetFundsConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.auto_trading_reset_confirm_title))
            .setMessage(getString(R.string.auto_trading_reset_confirm_msg))
            .setPositiveButton("确认重置") { _, _ ->
                engine.resetAccount(PaperTradingPrefs.DEFAULT_INITIAL_BALANCE)
                updateUI()
                Toast.makeText(this, getString(R.string.auto_trading_reset_done), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        marketJob?.cancel()
    }

    /**
     * 历史成交 RecyclerView Adapter
     */
    inner class TradeHistoryAdapter : RecyclerView.Adapter<TradeHistoryAdapter.VH>() {

        private var list: List<PaperTradeRecord> = emptyList()

        fun submitList(newList: List<PaperTradeRecord>) {
            list = ArrayList(newList)
            notifyDataSetChanged()
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvAction: TextView = v.findViewById(R.id.tvTradeAction)
            val tvSymbol: TextView = v.findViewById(R.id.tvTradeSymbol)
            val tvPnl: TextView = v.findViewById(R.id.tvTradePnl)
            val tvPriceQty: TextView = v.findViewById(R.id.tvTradePriceQty)
            val tvTime: TextView = v.findViewById(R.id.tvTradeTime)
            val tvNote: TextView = v.findViewById(R.id.tvTradeNote)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_trade_record, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = list.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = list[position]
            val upColor = Palette.up(this@AutoTradingActivity)
            val downColor = Palette.down(this@AutoTradingActivity)

            val actionTag = when (item.action) {
                "OPEN_LONG" -> "开多"
                "OPEN_SHORT" -> "开空"
                "TP1_CLOSE" -> "止盈 50%"
                "SL_CLOSE" -> "止损平仓"
                "BE_CLOSE" -> "保本平仓"
                "TRAILING_CLOSE" -> "移动止盈"
                "SIGNAL_CLOSE" -> "反转平仓"
                "MANUAL_CLOSE" -> "手动平仓"
                else -> item.action
            }
            holder.tvAction.text = actionTag
            holder.tvSymbol.text = item.symbol

            val isClose = item.action != "OPEN_LONG" && item.action != "OPEN_SHORT"
            if (isClose) {
                val pnlSign = if (item.realizedPnl >= 0) "+" else ""
                holder.tvPnl.text = "$pnlSign$${String.format(Locale.US, "%.2f", item.realizedPnl)} ($pnlSign${String.format(Locale.US, "%.2f", item.pnlPercent)}%)"
                holder.tvPnl.setTextColor(if (item.realizedPnl >= 0) upColor else downColor)
                holder.tvPnl.visibility = View.VISIBLE
            } else {
                holder.tvPnl.visibility = View.GONE
            }

            holder.tvPriceQty.text = "成交价: $${Fmt.price(item.price)} · 数量: ${String.format(Locale.US, "%.4f", item.quantity)}"
            holder.tvTime.text = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(item.timestamp))
            holder.tvNote.text = item.note
        }
    }
}
