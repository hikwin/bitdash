package com.bitdash.app.market

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 模拟交易持仓实体
 */
data class PaperPosition(
    val symbol: String,
    val side: String,                   // "LONG" 或 "SHORT"
    var quantity: Double,               // 持仓数量
    val entryPrice: Double,             // 开仓均价
    var slPrice: Double,                // 当前有效止损价
    var tp1Price: Double,               // 第一止盈目标价 (1:3 R)
    var beTriggerPrice: Double,         // 保本触发价 (1:1 R)
    var trailingStop: Double,           // 动态追踪止损线
    var margin: Double,                 // 占用保证金
    val leverage: Int,                  // 杠杆倍数
    val openTime: Long = System.currentTimeMillis(),
    var isBeActivated: Boolean = false,
    var isTp1Filled: Boolean = false,
    var unrealizedPnl: Double = 0.0,
    var pnlPct: Double = 0.0
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("symbol", symbol)
        put("side", side)
        put("quantity", quantity)
        put("entryPrice", entryPrice)
        put("slPrice", slPrice)
        put("tp1Price", tp1Price)
        put("beTriggerPrice", beTriggerPrice)
        put("trailingStop", trailingStop)
        put("margin", margin)
        put("leverage", leverage)
        put("openTime", openTime)
        put("isBeActivated", isBeActivated)
        put("isTp1Filled", isTp1Filled)
        put("unrealizedPnl", unrealizedPnl)
        put("pnlPct", pnlPct)
    }

    companion object {
        fun fromJson(obj: JSONObject): PaperPosition {
            return PaperPosition(
                symbol = obj.optString("symbol", "BTCUSDT"),
                side = obj.optString("side", "LONG"),
                quantity = obj.optDouble("quantity", 0.0),
                entryPrice = obj.optDouble("entryPrice", 0.0),
                slPrice = obj.optDouble("slPrice", 0.0),
                tp1Price = obj.optDouble("tp1Price", 0.0),
                beTriggerPrice = obj.optDouble("beTriggerPrice", 0.0),
                trailingStop = obj.optDouble("trailingStop", 0.0),
                margin = obj.optDouble("margin", 0.0),
                leverage = obj.optInt("leverage", 3),
                openTime = obj.optLong("openTime", System.currentTimeMillis()),
                isBeActivated = obj.optBoolean("isBeActivated", false),
                isTp1Filled = obj.optBoolean("isTp1Filled", false),
                unrealizedPnl = obj.optDouble("unrealizedPnl", 0.0),
                pnlPct = obj.optDouble("pnlPct", 0.0)
            )
        }
    }
}

/**
 * 历史成交记录
 */
data class PaperTradeRecord(
    val timestamp: Long,
    val symbol: String,
    val action: String,                 // "OPEN_LONG", "OPEN_SHORT", "TP1_CLOSE", "SL_CLOSE", "BE_CLOSE", "TRAILING_CLOSE", "MANUAL_CLOSE", "SIGNAL_CLOSE"
    val price: Double,
    val quantity: Double,
    val realizedPnl: Double,
    val pnlPercent: Double,
    val note: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("timestamp", timestamp)
        put("symbol", symbol)
        put("action", action)
        put("price", price)
        put("quantity", quantity)
        put("realizedPnl", realizedPnl)
        put("pnlPercent", pnlPercent)
        put("note", note)
    }

    companion object {
        fun fromJson(obj: JSONObject): PaperTradeRecord {
            return PaperTradeRecord(
                timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                symbol = obj.optString("symbol", "BTCUSDT"),
                action = obj.optString("action", "CLOSE"),
                price = obj.optDouble("price", 0.0),
                quantity = obj.optDouble("quantity", 0.0),
                realizedPnl = obj.optDouble("realizedPnl", 0.0),
                pnlPercent = obj.optDouble("pnlPercent", 0.0),
                note = obj.optString("note", "")
            )
        }
    }
}

/**
 * 模拟交易持久化管理器
 */
object PaperTradingPrefs {

    private const val PREFS_NAME = "bitdash_paper_trading"

    const val DEFAULT_INITIAL_BALANCE = 10000.0

    private const val KEY_BALANCE = "paper_balance"
    private const val KEY_AUTO_TRADING_ENABLED = "auto_trading_enabled"
    private const val KEY_STRATEGY_MODE = "strategy_mode" // "3K", "LORENTZIAN", "EMA_CROSS", "PINE"
    private const val KEY_SELECTED_PINE_ID = "selected_pine_id"
    private const val KEY_SYMBOL = "selected_symbol"
    private const val KEY_TIMEFRAME = "selected_timeframe"
    private const val KEY_RISK_PERCENT = "risk_percent"
    private const val KEY_LEVERAGE = "leverage"
    private const val KEY_ANTI_CHOP = "anti_chop"
    private const val KEY_POSITION = "active_position_json"
    private const val KEY_TRADE_HISTORY = "trade_history_json"
    private const val KEY_LOGS = "trading_logs_json"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getBalance(ctx: Context): Double = sp(ctx).getString(KEY_BALANCE, DEFAULT_INITIAL_BALANCE.toString())?.toDoubleOrNull() ?: DEFAULT_INITIAL_BALANCE

    fun setBalance(ctx: Context, balance: Double) {
        sp(ctx).edit().putString(KEY_BALANCE, balance.toString()).apply()
    }

    fun isAutoTradingEnabled(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_AUTO_TRADING_ENABLED, false)

    fun setAutoTradingEnabled(ctx: Context, enabled: Boolean) {
        sp(ctx).edit().putBoolean(KEY_AUTO_TRADING_ENABLED, enabled).apply()
    }

    fun getStrategyMode(ctx: Context): String = sp(ctx).getString(KEY_STRATEGY_MODE, "3K") ?: "3K"

    fun setStrategyMode(ctx: Context, mode: String) {
        sp(ctx).edit().putString(KEY_STRATEGY_MODE, mode).apply()
    }

    fun getSelectedPineId(ctx: Context): String = sp(ctx).getString(KEY_SELECTED_PINE_ID, "") ?: ""

    fun setSelectedPineId(ctx: Context, id: String) {
        sp(ctx).edit().putString(KEY_SELECTED_PINE_ID, id).apply()
    }

    fun getSymbol(ctx: Context): String = sp(ctx).getString(KEY_SYMBOL, "BTCUSDT") ?: "BTCUSDT"

    fun setSymbol(ctx: Context, symbol: String) {
        sp(ctx).edit().putString(KEY_SYMBOL, symbol).apply()
    }

    fun getTimeframe(ctx: Context): String = sp(ctx).getString(KEY_TIMEFRAME, "15m") ?: "15m"

    fun setTimeframe(ctx: Context, tf: String) {
        sp(ctx).edit().putString(KEY_TIMEFRAME, tf).apply()
    }

    fun getRiskPercent(ctx: Context): Double = sp(ctx).getString(KEY_RISK_PERCENT, "2.0")?.toDoubleOrNull() ?: 2.0

    fun setRiskPercent(ctx: Context, risk: Double) {
        sp(ctx).edit().putString(KEY_RISK_PERCENT, risk.toString()).apply()
    }

    fun getLeverage(ctx: Context): Int = sp(ctx).getInt(KEY_LEVERAGE, 3)

    fun setLeverage(ctx: Context, lev: Int) {
        sp(ctx).edit().putInt(KEY_LEVERAGE, lev).apply()
    }

    fun isAntiChopEnabled(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_ANTI_CHOP, true)

    fun setAntiChopEnabled(ctx: Context, enabled: Boolean) {
        sp(ctx).edit().putBoolean(KEY_ANTI_CHOP, enabled).apply()
    }

    fun getPosition(ctx: Context): PaperPosition? {
        val str = sp(ctx).getString(KEY_POSITION, null) ?: return null
        return try {
            PaperPosition.fromJson(JSONObject(str))
        } catch (e: Exception) {
            null
        }
    }

    fun setPosition(ctx: Context, pos: PaperPosition?) {
        if (pos == null) {
            sp(ctx).edit().remove(KEY_POSITION).apply()
        } else {
            sp(ctx).edit().putString(KEY_POSITION, pos.toJson().toString()).apply()
        }
    }

    fun getTradeHistory(ctx: Context): MutableList<PaperTradeRecord> {
        val str = sp(ctx).getString(KEY_TRADE_HISTORY, null) ?: return mutableListOf()
        val list = mutableListOf<PaperTradeRecord>()
        try {
            val array = JSONArray(str)
            for (i in 0 until array.length()) {
                list.add(PaperTradeRecord.fromJson(array.getJSONObject(i)))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun saveTradeHistory(ctx: Context, list: List<PaperTradeRecord>) {
        val array = JSONArray()
        // 最多保留最新 200 条
        val takeList = if (list.size > 200) list.takeLast(200) else list
        for (item in takeList) {
            array.put(item.toJson())
        }
        sp(ctx).edit().putString(KEY_TRADE_HISTORY, array.toString()).apply()
    }

    fun getLogs(ctx: Context): MutableList<String> {
        val str = sp(ctx).getString(KEY_LOGS, null) ?: return mutableListOf()
        val list = mutableListOf<String>()
        try {
            val array = JSONArray(str)
            for (i in 0 until array.length()) {
                list.add(array.getString(i))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun saveLogs(ctx: Context, logs: List<String>) {
        val array = JSONArray()
        val takeList = if (logs.size > 200) logs.takeLast(200) else logs
        for (line in takeList) {
            array.put(line)
        }
        sp(ctx).edit().putString(KEY_LOGS, array.toString()).apply()
    }

    // ================= 回测设置记忆功能 =================
    private const val KEY_BACKTEST_RANGE_INDEX = "backtest_range_index"
    private const val KEY_BACKTEST_START_MS = "backtest_start_ms"
    private const val KEY_BACKTEST_END_MS = "backtest_end_ms"
    private const val KEY_BACKTEST_CAPITAL = "backtest_initial_capital"
    private const val KEY_BACKTEST_FIXED_AMOUNT = "backtest_fixed_trade_amount"

    fun getBacktestRangeIndex(ctx: Context): Int = sp(ctx).getInt(KEY_BACKTEST_RANGE_INDEX, 0)
    fun setBacktestRangeIndex(ctx: Context, idx: Int) = sp(ctx).edit().putInt(KEY_BACKTEST_RANGE_INDEX, idx).apply()

    fun getBacktestStartMs(ctx: Context): Long = sp(ctx).getLong(KEY_BACKTEST_START_MS, System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L)
    fun setBacktestStartMs(ctx: Context, ms: Long) = sp(ctx).edit().putLong(KEY_BACKTEST_START_MS, ms).apply()

    fun getBacktestEndMs(ctx: Context): Long = sp(ctx).getLong(KEY_BACKTEST_END_MS, System.currentTimeMillis())
    fun setBacktestEndMs(ctx: Context, ms: Long) = sp(ctx).edit().putLong(KEY_BACKTEST_END_MS, ms).apply()

    fun getBacktestCapital(ctx: Context): Double = sp(ctx).getString(KEY_BACKTEST_CAPITAL, "10000")?.toDoubleOrNull() ?: 10000.0
    fun setBacktestCapital(ctx: Context, capital: Double) = sp(ctx).edit().putString(KEY_BACKTEST_CAPITAL, capital.toString()).apply()

    fun getBacktestFixedAmount(ctx: Context): Double = sp(ctx).getString(KEY_BACKTEST_FIXED_AMOUNT, "0")?.toDoubleOrNull() ?: 0.0
    fun setBacktestFixedAmount(ctx: Context, amount: Double) = sp(ctx).edit().putString(KEY_BACKTEST_FIXED_AMOUNT, amount.toString()).apply()

    /**
     * 重置所有模拟资金、清空持仓与记录
     */
    fun resetAll(ctx: Context, initialBalance: Double = DEFAULT_INITIAL_BALANCE) {
        setBalance(ctx, initialBalance)
        setPosition(ctx, null)
        saveTradeHistory(ctx, emptyList())
        saveLogs(ctx, listOf("🔄 模拟账户已重置，初始本金: $initialBalance USDT"))
    }
}
