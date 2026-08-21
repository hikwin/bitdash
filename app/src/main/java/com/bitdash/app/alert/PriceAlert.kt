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
    var soundMode: String = "SILENT", // "SILENT", "CUSTOM"
    var soundUri: String = "",
    var soundTitle: String = "",
    var isStrongAlert: Boolean = false, // 是否开启强提醒
    var strongMaxCount: Int = 2, // 强提醒连续提醒次数（默认2次）
    var strongIntervalSeconds: Int = 30, // 强提醒连续提醒间隔（秒）
    var strongCurrentCount: Int = 0, // 当前轮次已连续提醒次数
    var notifySystem: Boolean = true, // 1. 系统通知栏（默认勾选）
    var notifyToast: Boolean = false, // 2. 屏幕 Toast 提示
    var notifyWebhook: Boolean = false, // 3. 自定义 Webhook (钉钉/飞书等)
    var notifyHttp: Boolean = false, // 4. HTTP 自定义接口
    val createdAt: Long = System.currentTimeMillis(),
    var lastTriggeredTs: Long = 0L,
    var lastTriggeredPrice: Double = 0.0
) {
    fun summaryText(): String = buildString {
        when (type) {
            AlertType.PRICE_ABOVE -> append("价格上涨突破 ≥ ${Fmt.price(targetPrice)}")
            AlertType.PRICE_BELOW -> append("价格下跌跌破 ≤ ${Fmt.price(targetPrice)}")
            AlertType.VOLATILITY -> {
                val dirStr = when (direction) {
                    VolatilityDirection.SURGE -> "暴涨"
                    VolatilityDirection.PLUNGE -> "暴跌"
                    VolatilityDirection.BOTH -> "异动"
                }
                append("${windowMinutes}分钟内$dirStr ≥ ${String.format("%.1f", pctThreshold)}%")
            }
        }
        if (isStrongAlert) {
            val countStr = if (strongMaxCount >= 999) "持续无限次" else "连续${strongMaxCount}次"
            append(" · ⚡强提醒(${countStr})")
        }
        if (soundMode == "CUSTOM" && soundTitle.isNotBlank()) {
            append(" · 🎵${soundTitle}")
        }
        val channels = mutableListOf<String>()
        if (notifySystem) channels.add("通知")
        if (notifyToast) channels.add("Toast")
        if (notifyWebhook) channels.add("Webhook")
        if (notifyHttp) channels.add("HTTP")
        if (channels.isNotEmpty()) {
            append(" · 渠道: [${channels.joinToString("+")}]")
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
        put("soundMode", soundMode)
        put("soundUri", soundUri)
        put("soundTitle", soundTitle)
        put("isStrongAlert", isStrongAlert)
        put("strongMaxCount", strongMaxCount)
        put("strongIntervalSeconds", strongIntervalSeconds)
        put("strongCurrentCount", strongCurrentCount)
        put("notifySystem", notifySystem)
        put("notifyToast", notifyToast)
        put("notifyWebhook", notifyWebhook)
        put("notifyHttp", notifyHttp)
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
            soundMode = json.optString("soundMode", "SILENT"),
            soundUri = json.optString("soundUri", ""),
            soundTitle = json.optString("soundTitle", ""),
            isStrongAlert = json.optBoolean("isStrongAlert", false),
            strongMaxCount = json.optInt("strongMaxCount", 2),
            strongIntervalSeconds = json.optInt("strongIntervalSeconds", 30),
            strongCurrentCount = json.optInt("strongCurrentCount", 0),
            notifySystem = json.optBoolean("notifySystem", true),
            notifyToast = json.optBoolean("notifyToast", false),
            notifyWebhook = json.optBoolean("notifyWebhook", false),
            notifyHttp = json.optBoolean("notifyHttp", false),
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
