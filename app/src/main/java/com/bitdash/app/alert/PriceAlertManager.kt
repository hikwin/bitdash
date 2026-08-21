package com.bitdash.app.alert

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bitdash.app.ChartActivity
import com.bitdash.app.R
import com.bitdash.app.market.Fmt
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

object PriceAlertManager {

    private const val CHANNEL_ID_DEFAULT = "bitdash_price_alerts_v2"
    private const val CHANNEL_ID_SILENT = "bitdash_price_alerts_silent"
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
            if (enabled) {
                list[idx].strongCurrentCount = 0
            }
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
                        if (alert.isStrongAlert) {
                            val intervalMs = (alert.strongIntervalSeconds * 1000L).coerceAtLeast(10_000L)
                            if (ts - alert.lastTriggeredTs >= intervalMs && alert.strongCurrentCount < alert.strongMaxCount) {
                                alert.strongCurrentCount++
                                val countHeader = if (alert.strongMaxCount >= 999) "【⚡强提醒 连续提醒】" else "【⚡强提醒 第${alert.strongCurrentCount}/${alert.strongMaxCount}次】"
                                dispatchAlert(
                                    context = context,
                                    symbol = symbol,
                                    title = "$countHeader 🚀 目标价上涨突破: $symbol",
                                    message = "现价: ${Fmt.price(currentPrice)} 已突破目标价: ${Fmt.price(alert.targetPrice)}",
                                    price = currentPrice,
                                    notificationId = alert.id.hashCode(),
                                    alert = alert
                                )
                                alert.lastTriggeredTs = ts
                                alert.lastTriggeredPrice = currentPrice
                                if (!alert.repeat && alert.strongCurrentCount >= alert.strongMaxCount) {
                                    alert.enabled = false
                                }
                                needSave = true
                            }
                        } else {
                            if (ts - alert.lastTriggeredTs >= DEFAULT_COOLDOWN_MS) {
                                dispatchAlert(
                                    context = context,
                                    symbol = symbol,
                                    title = "🚀 目标价上涨突破: $symbol",
                                    message = "现价: ${Fmt.price(currentPrice)} 已突破目标价: ${Fmt.price(alert.targetPrice)}",
                                    price = currentPrice,
                                    notificationId = alert.id.hashCode(),
                                    alert = alert
                                )
                                alert.lastTriggeredTs = ts
                                alert.lastTriggeredPrice = currentPrice
                                if (!alert.repeat) alert.enabled = false
                                needSave = true
                            }
                        }
                    } else {
                        // 价格回落至目标价以下，重置强提醒计数
                        if (alert.strongCurrentCount > 0) {
                            alert.strongCurrentCount = 0
                            needSave = true
                        }
                    }
                }
                AlertType.PRICE_BELOW -> {
                    if (currentPrice <= alert.targetPrice) {
                        if (alert.isStrongAlert) {
                            val intervalMs = (alert.strongIntervalSeconds * 1000L).coerceAtLeast(10_000L)
                            if (ts - alert.lastTriggeredTs >= intervalMs && alert.strongCurrentCount < alert.strongMaxCount) {
                                alert.strongCurrentCount++
                                val countHeader = if (alert.strongMaxCount >= 999) "【⚡强提醒 连续提醒】" else "【⚡强提醒 第${alert.strongCurrentCount}/${alert.strongMaxCount}次】"
                                dispatchAlert(
                                    context = context,
                                    symbol = symbol,
                                    title = "$countHeader 🔻 目标价下跌跌破: $symbol",
                                    message = "现价: ${Fmt.price(currentPrice)} 已跌破目标价: ${Fmt.price(alert.targetPrice)}",
                                    price = currentPrice,
                                    notificationId = alert.id.hashCode(),
                                    alert = alert
                                )
                                alert.lastTriggeredTs = ts
                                alert.lastTriggeredPrice = currentPrice
                                if (!alert.repeat && alert.strongCurrentCount >= alert.strongMaxCount) {
                                    alert.enabled = false
                                }
                                needSave = true
                            }
                        } else {
                            if (ts - alert.lastTriggeredTs >= DEFAULT_COOLDOWN_MS) {
                                dispatchAlert(
                                    context = context,
                                    symbol = symbol,
                                    title = "🔻 目标价下跌跌破: $symbol",
                                    message = "现价: ${Fmt.price(currentPrice)} 已跌破目标价: ${Fmt.price(alert.targetPrice)}",
                                    price = currentPrice,
                                    notificationId = alert.id.hashCode(),
                                    alert = alert
                                )
                                alert.lastTriggeredTs = ts
                                alert.lastTriggeredPrice = currentPrice
                                if (!alert.repeat) alert.enabled = false
                                needSave = true
                            }
                        }
                    } else {
                        // 价格反弹至目标价以上，重置强提醒计数
                        if (alert.strongCurrentCount > 0) {
                            alert.strongCurrentCount = 0
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

                        if (isTriggered) {
                            if (alert.isStrongAlert) {
                                val intervalMs = (alert.strongIntervalSeconds * 1000L).coerceAtLeast(10_000L)
                                if (ts - alert.lastTriggeredTs >= intervalMs && alert.strongCurrentCount < alert.strongMaxCount) {
                                    alert.strongCurrentCount++
                                    val countHeader = if (alert.strongMaxCount >= 999) "【⚡强提醒】" else "【⚡强提醒 第${alert.strongCurrentCount}/${alert.strongMaxCount}次】"
                                    val dirIcon = if (pct >= 0) "📈" else "📉"
                                    val dirDesc = if (pct >= 0) "急涨暴涨" else "急跌暴跌"
                                    dispatchAlert(
                                        context = context,
                                        symbol = symbol,
                                        title = "$countHeader $dirIcon $symbol ${alert.windowMinutes}分钟$dirDesc ${String.format("%+.2f%%", pct)}",
                                        message = "基准价: ${Fmt.price(oldPoint.price)} ➔ 现价: ${Fmt.price(currentPrice)}（阈值: ±${alert.pctThreshold}%）",
                                        price = currentPrice,
                                        notificationId = alert.id.hashCode(),
                                        alert = alert
                                    )
                                    alert.lastTriggeredTs = ts
                                    alert.lastTriggeredPrice = currentPrice
                                    if (!alert.repeat && alert.strongCurrentCount >= alert.strongMaxCount) {
                                        alert.enabled = false
                                    }
                                    needSave = true
                                }
                            } else {
                                val cooldown = (alert.windowMinutes * 60 * 1000L).coerceAtLeast(3 * 60 * 1000L)
                                if (ts - alert.lastTriggeredTs >= cooldown) {
                                    val dirIcon = if (pct >= 0) "⚡📈" else "⚡📉"
                                    val dirDesc = if (pct >= 0) "急涨暴涨" else "急跌暴跌"
                                    dispatchAlert(
                                        context = context,
                                        symbol = symbol,
                                        title = "$dirIcon $symbol ${alert.windowMinutes}分钟$dirDesc ${String.format("%+.2f%%", pct)}",
                                        message = "基准价: ${Fmt.price(oldPoint.price)} ➔ 现价: ${Fmt.price(currentPrice)}（阈值: ±${alert.pctThreshold}%）",
                                        price = currentPrice,
                                        notificationId = alert.id.hashCode(),
                                        alert = alert
                                    )
                                    alert.lastTriggeredTs = ts
                                    alert.lastTriggeredPrice = currentPrice
                                    if (!alert.repeat) alert.enabled = false
                                    needSave = true
                                }
                            }
                        } else {
                            if (alert.strongCurrentCount > 0) {
                                alert.strongCurrentCount = 0
                                needSave = true
                            }
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
     * 四大通道统一调度分发（系统通知、Toast、Webhook、HTTP接口）
     */
    private fun dispatchAlert(
        context: Context,
        symbol: String,
        title: String,
        message: String,
        price: Double,
        notificationId: Int,
        alert: PriceAlert
    ) {
        // 1. 系统状态栏通知
        if (alert.notifySystem) {
            sendNotification(context, symbol, title, message, notificationId, alert)
        }

        // 2. 屏幕 Toast 提示
        if (alert.notifyToast) {
            PushSender.sendToast(context, title, message)
        }

        // 3. 自定义 Webhook 机器人 (钉钉/飞书/企微/通用)
        if (alert.notifyWebhook) {
            PushSender.sendWebhook(context, symbol, title, message, price)
        }

        // 4. 自定义 HTTP 接口 (GET/POST)
        if (alert.notifyHttp) {
            PushSender.sendHttp(context, symbol, title, message, price)
        }
    }

    /**
     * 发送系统通知栏通知并播放对应铃声
     */
    private fun sendNotification(
        context: Context,
        symbol: String,
        title: String,
        message: String,
        notificationId: Int,
        alert: PriceAlert
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val isSilent = alert.soundMode == "SILENT"
        val channelId = if (isSilent) CHANNEL_ID_SILENT else CHANNEL_ID_DEFAULT

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(channelId) == null) {
                if (isSilent) {
                    val silentChannel = NotificationChannel(
                        CHANNEL_ID_SILENT,
                        "行情预警 (静音)",
                        NotificationManager.IMPORTANCE_DEFAULT
                    ).apply {
                        description = "数字货币价格到达目标价及异动提醒 (无铃声)"
                        setSound(null, null)
                        enableVibration(true)
                    }
                    nm.createNotificationChannel(silentChannel)
                } else {
                    val defaultChannel = NotificationChannel(
                        CHANNEL_ID_DEFAULT,
                        "行情预警 (带铃声)",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "数字货币价格到达目标价及异动提醒"
                        enableLights(true)
                        enableVibration(true)
                    }
                    nm.createNotificationChannel(defaultChannel)
                }
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

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_trending_up)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        if (isSilent) {
            builder.setSilent(true)
        } else if (alert.soundMode == "CUSTOM" && alert.soundUri.isNotBlank()) {
            try {
                val uri = Uri.parse(alert.soundUri)
                builder.setSound(uri)
                // 确保自定义铃声在应用前台或后台均能立即发声
                val ringtone = RingtoneManager.getRingtone(context, uri)
                ringtone?.play()
            } catch (_: Exception) {}
        } else {
            builder.setDefaults(NotificationCompat.DEFAULT_ALL)
        }

        try {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }
}
