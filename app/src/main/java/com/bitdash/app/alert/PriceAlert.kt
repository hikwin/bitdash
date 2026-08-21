package com.bitdash.app.alert

import android.content.Context
import com.bitdash.app.market.Fmt
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class AlertType(val code: String) {
    PRICE_ABOVE("ABOVE"),
    PRICE_BELOW("BELOW"),
    VOLATILITY("VOLATILITY");

    companion object {
        fun fromCode(code: String): AlertType =
            values().firstOrNull { it.code.equals(code, ignoreCase = true) } ?: PRICE_ABOVE
    }
}

enum class VolatilityDirection(val code: String) {
    BOTH("BOTH"),
    SURGE("SURGE"),
    PLUNGE("PLUNGE");

    companion object {
        fun fromCode(code: String): VolatilityDirection =
            values().firstOrNull { it.code.equals(code, ignoreCase = true) } ?: BOTH
    }
}

data class PriceAlert(
    val id: String = UUID.randomUUID().toString(),
    val symbol: String,
    val type: AlertType,
    val targetPrice: Double = 0.0,
    val windowMinutes: Int = 5,
    val pctThreshold: Double = 3.0,
    val direction: VolatilityDirection = VolatilityDirection.BOTH,
    var enabled: Boolean = true,
    var repeat: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    var lastTriggeredTs: Long = 0L,
    var lastTriggeredPrice: Double = 0.0
) {
    fun summaryText(): String = when (type) {
        AlertType.PRICE_ABOVE -> "价格上涨突破 ≥ ${Fmt.price(targetPrice)}"
        AlertType.PRICE_BELOW -> "价格下跌跌破 ≤ ${Fmt.price(targetPrice)}"
        AlertType.VOLATILITY -> {
            val dirStr = when (direction) {
                VolatilityDirection.SURGE -> "暴涨"
                VolatilityDirection.PLUNGE -> "暴跌"
                VolatilityDirection.BOTH -> "异动"
            }
            "${windowMinutes}分钟内$dirStr ≥ ${String.format("%.1f", pctThreshold)}%"
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("symbol", symbol)
        put("type", type.code)
        put("targetPrice", targetPrice)
        put("windowMinutes", windowMinutes)
        put("pctThreshold", pctThreshold)
        put("direction", direction.code)
        put("enabled", enabled)
        put("repeat", repeat)
        put("createdAt", createdAt)
        put("lastTriggeredTs", lastTriggeredTs)
        put("lastTriggeredPrice", lastTriggeredPrice)
    }

    companion object {
        fun fromJson(json: JSONObject): PriceAlert = PriceAlert(
            id = json.optString("id", UUID.randomUUID().toString()),
            symbol = json.optString("symbol", "BTC-USDT"),
            type = AlertType.fromCode(json.optString("type")),
            targetPrice = json.optDouble("targetPrice", 0.0),
            windowMinutes = json.optInt("windowMinutes", 5),
            pctThreshold = json.optDouble("pctThreshold", 3.0),
            direction = VolatilityDirection.fromCode(json.optString("direction")),
            enabled = json.optBoolean("enabled", true),
            repeat = json.optBoolean("repeat", true),
            createdAt = json.optLong("createdAt", System.currentTimeMillis()),
            lastTriggeredTs = json.optLong("lastTriggeredTs", 0L),
            lastTriggeredPrice = json.optDouble("lastTriggeredPrice", 0.0)
        )
    }
}

object PriceAlertStore {
    private const val PREFS_NAME = "bitdash_price_alerts"
    private const val KEY_ALERTS = "alerts_json"

    fun loadAlerts(context: Context): MutableList<PriceAlert> {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = sp.getString(KEY_ALERTS, null) ?: return mutableListOf()
        val list = mutableListOf<PriceAlert>()
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                list.add(PriceAlert.fromJson(obj))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun saveAlerts(context: Context, alerts: List<PriceAlert>) {
        val array = JSONArray()
        for (a in alerts) {
            array.put(a.toJson())
        }
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit().putString(KEY_ALERTS, array.toString()).apply()
    }
}
