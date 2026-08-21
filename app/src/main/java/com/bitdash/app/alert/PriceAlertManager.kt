package com.bitdash.app.alert

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bitdash.app.ChartActivity
import com.bitdash.app.R
import com.bitdash.app.market.Fmt
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

object PriceAlertManager {

    private const val CHANNEL_ID = "bitdash_price_alerts"
    private const val CHANNEL_NAME = "行情预警与异动提醒"
    private const val DEFAULT_COOLDOWN_MS = 5 * 60 * 1000L // 默认冷却 5 分钟

    data class PricePoint(val ts: Long, val price: Double)

    // 各币种的历史价格时间序列（保存最近 60 分钟价格快照）
    private val historyMap = ConcurrentHashMap<String, ArrayDeque<PricePoint>>()

    // 各币种最新实时价格缓存
    private val latestPriceMap = ConcurrentHashMap<String, Double>()

    private var alertsCache: MutableList<PriceAlert>? = null

    fun getLatestPrice(symbol: String): Double? {
        val norm = symbol.trim().uppercase()
        return latestPriceMap[norm]
            ?: latestPriceMap[com.bitdash.app.market.Symbols.hyphen(norm)]
            ?: latestPriceMap[com.bitdash.app.market.Symbols.compact(norm)]
    }

    @Synchronized
    fun getAlerts(context: Context): List<PriceAlert> {
        if (alertsCache == null) {
            alertsCache = PriceAlertStore.loadAlerts(context)
        }
        return alertsCache!!.toList()
    }

    @Synchronized
    fun addAlert(context: Context, alert: PriceAlert) {
        val list = (alertsCache ?: PriceAlertStore.loadAlerts(context)).toMutableList()
        list.add(0, alert)
        alertsCache = list
        PriceAlertStore.saveAlerts(context, list)
    }

    @Synchronized
    fun updateAlert(context: Context, alert: PriceAlert) {
        val list = (alertsCache ?: PriceAlertStore.loadAlerts(context)).toMutableList()
        val idx = list.indexOfFirst { it.id == alert.id }
        if (idx >= 0) {
            list[idx] = alert
            alertsCache = list
            PriceAlertStore.saveAlerts(context, list)
        }
    }

    @Synchronized
    fun toggleAlert(context: Context, alertId: String, enabled: Boolean) {
        val list = (alertsCache ?: PriceAlertStore.loadAlerts(context)).toMutableList()
        val idx = list.indexOfFirst { it.id == alertId }
        if (idx >= 0) {
            list[idx].enabled = enabled
            alertsCache = list
            PriceAlertStore.saveAlerts(context, list)
        }
    }

    @Synchronized
    fun deleteAlert(context: Context, alertId: String) {
        val list = (alertsCache ?: PriceAlertStore.loadAlerts(context)).toMutableList()
        if (list.removeAll { it.id == alertId }) {
            alertsCache = list
            PriceAlertStore.saveAlerts(context, list)
        }
    }

    fun getActiveCount(context: Context): Int =
        getAlerts(context).count { it.enabled }

    /**
     * 行情价格更新入口：毫秒级评估目标价与异动预警
     */
    fun onPriceUpdate(context: Context, symbol: String, currentPrice: Double, ts: Long = System.currentTimeMillis()) {
        if (currentPrice <= 0.0 || symbol.isBlank()) return

        latestPriceMap[symbol.trim().uppercase()] = currentPrice

        // 1. 记录历史价格滑动窗口
        val queue = historyMap.getOrPut(symbol) { ArrayDeque() }
        synchronized(queue) {
            queue.addLast(PricePoint(ts, currentPrice))
            val cutoff = ts - 65 * 60 * 1000L // 保留 65 分钟内
            while (queue.isNotEmpty() && queue.first().ts < cutoff) {
                queue.removeFirst()
            }
        }

        // 2. 获取针对该币种的启用预警
        val alerts = getAlerts(context)
        val activeForSymbol = alerts.filter { it.enabled && it.symbol.equals(symbol, ignoreCase = true) }
        if (activeForSymbol.isEmpty()) return

        var needSave = false

        for (alert in activeForSymbol) {
            when (alert.type) {
                AlertType.PRICE_ABOVE -> {
                    if (currentPrice >= alert.targetPrice) {
                        val cooldown = DEFAULT_COOLDOWN_MS
                        if (ts - alert.lastTriggeredTs >= cooldown) {
                            sendNotification(
                                context = context,
                                symbol = symbol,
                                title = "🚀 目标价上涨突破: $symbol",
                                message = "现价: ${Fmt.price(currentPrice)} 已突破目标价: ${Fmt.price(alert.targetPrice)}",
                                notificationId = alert.id.hashCode()
                            )
                            alert.lastTriggeredTs = ts
                            alert.lastTriggeredPrice = currentPrice
                            if (!alert.repeat) alert.enabled = false
                            needSave = true
                        }
                    }
                }
                AlertType.PRICE_BELOW -> {
                    if (currentPrice <= alert.targetPrice) {
                        val cooldown = DEFAULT_COOLDOWN_MS
                        if (ts - alert.lastTriggeredTs >= cooldown) {
                            sendNotification(
                                context = context,
                                symbol = symbol,
                                title = "🔻 目标价下跌跌破: $symbol",
                                message = "现价: ${Fmt.price(currentPrice)} 已跌破目标价: ${Fmt.price(alert.targetPrice)}",
                                notificationId = alert.id.hashCode()
                            )
                            alert.lastTriggeredTs = ts
                            alert.lastTriggeredPrice = currentPrice
                            if (!alert.repeat) alert.enabled = false
                            needSave = true
                        }
                    }
                }
                AlertType.VOLATILITY -> {
                    val windowMs = alert.windowMinutes * 60 * 1000L
                    val targetTs = ts - windowMs
                    val oldPoint = synchronized(queue) {
                        queue.firstOrNull { it.ts >= targetTs } ?: queue.firstOrNull()
                    }
                    if (oldPoint != null && oldPoint.price > 0.0 && (ts - oldPoint.ts) >= (windowMs * 0.4)) {
                        val pct = ((currentPrice - oldPoint.price) / oldPoint.price) * 100.0
                        val isTriggered = when (alert.direction) {
                            VolatilityDirection.SURGE -> pct >= alert.pctThreshold
                            VolatilityDirection.PLUNGE -> pct <= -alert.pctThreshold
                            VolatilityDirection.BOTH -> abs(pct) >= alert.pctThreshold
                        }
                        val cooldown = (alert.windowMinutes * 60 * 1000L).coerceAtLeast(3 * 60 * 1000L)
                        if (isTriggered && (ts - alert.lastTriggeredTs >= cooldown)) {
                            val dirIcon = if (pct >= 0) "⚡📈" else "⚡📉"
                            val dirDesc = if (pct >= 0) "急涨暴涨" else "急跌暴跌"
                            sendNotification(
                                context = context,
                                symbol = symbol,
                                title = "$dirIcon $symbol ${alert.windowMinutes}分钟$dirDesc ${String.format("%+.2f%%", pct)}",
                                message = "基准价: ${Fmt.price(oldPoint.price)} ➔ 现价: ${Fmt.price(currentPrice)}（阈值: ±${alert.pctThreshold}%）",
                                notificationId = alert.id.hashCode()
                            )
                            alert.lastTriggeredTs = ts
                            alert.lastTriggeredPrice = currentPrice
                            if (!alert.repeat) alert.enabled = false
                            needSave = true
                        }
                    }
                }
            }
        }

        if (needSave) {
            PriceAlertStore.saveAlerts(context, alerts)
        }
    }

    /**
     * 发送系统通知栏通知
     */
    private fun sendNotification(
        context: Context,
        symbol: String,
        title: String,
        message: String,
        notificationId: Int
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "数字货币价格到达目标价及快速异动波动提醒"
                    enableLights(true)
                    enableVibration(true)
                }
                nm.createNotificationChannel(channel)
            }
        }

        val intent = Intent(context, ChartActivity::class.java).apply {
            putExtra(ChartActivity.EXTRA_SYMBOL, symbol)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_trending_up)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }
}
