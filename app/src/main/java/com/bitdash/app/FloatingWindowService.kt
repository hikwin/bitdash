package com.bitdash.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.bitdash.app.market.FloatingFmt
import com.bitdash.app.market.Markets
import com.bitdash.app.market.Prefs
import com.bitdash.app.market.RealtimeSession
import com.bitdash.app.market.RtEvent
import com.bitdash.app.market.RtScope
import com.bitdash.app.market.Ticker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot

/**
 * 桌面悬浮窗服务（火币 App 风格）：
 * - 在屏幕任意位置以极简半透明胶囊展示 1~5 个币种的实时价格
 * - 支持全屏拖拽与记忆位置
 * - 点击调起主界面
 * - 遵守专属精炼格式化规则（≥100 整数位，1~100 保留 2 位，<1 最多 5 位，无 USDT）
 */
class FloatingWindowService : Service() {

    companion object {
        private const val CHANNEL_ID = "bitdash_floating_window_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.bitdash.app.action.START_FLOATING"
        const val ACTION_STOP = "com.bitdash.app.action.STOP_FLOATING"
        const val ACTION_RELOAD = "com.bitdash.app.action.RELOAD_FLOATING"

        @Volatile
        var isRunning = false
            private set

        @Volatile
        private var instance: FloatingWindowService? = null

        /**
         * 实时调节悬浮窗不透明度（供设置弹窗在拖动 SeekBar 时即时预览效果）
         */
        fun updateLiveAlpha(pct: Int) {
            val validPct = pct.coerceIn(Prefs.MIN_FLOATING_ALPHA, Prefs.MAX_FLOATING_ALPHA)
            val alpha = validPct / 100f
            instance?.floatingView?.alpha = alpha
        }

        fun start(ctx: Context) {
            val intent = Intent(ctx, FloatingWindowService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun stop(ctx: Context) {
            val intent = Intent(ctx, FloatingWindowService::class.java).apply {
                action = ACTION_STOP
            }
            ctx.startService(intent)
        }

        fun reload(ctx: Context) {
            if (!isRunning) return
            val intent = Intent(ctx, FloatingWindowService::class.java).apply {
                action = ACTION_RELOAD
            }
            ctx.startService(intent)
        }
    }

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var params: WindowManager.LayoutParams? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var pollJob: Job? = null
    private var rtSession: RealtimeSession? = null

    /** 当前悬浮窗绑定的币种列表及对应 TextView 缓存 */
    private val coinViews = ArrayList<CoinViewHolder>()
    private var currentSymbols: List<String> = emptyList()

    private data class CoinViewHolder(
        val rawSymbol: String,
        val cleanSymbol: String,
        val tvSymbol: TextView,
        val tvPrice: TextView
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        isRunning = true
        startForegroundNotification()
        initOverlayWindow()
        initRealtimeSession()
        reloadSymbols()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> {
                reloadSymbols()
            }
            else -> {
                reloadSymbols()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) {
            instance = null
        }
        isRunning = false
        pollJob?.cancel()
        serviceScope.cancel()
        rtSession?.stop()
        removeOverlayWindow()
    }

    // ---------- 前台服务保活通知 ----------

    private fun startForegroundNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.floating_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.floating_notification_content)
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.floating_notification_title))
            .setContentText(getString(R.string.floating_notification_content))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ---------- 悬浮窗 View 与拖拽手势 ----------

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    private fun initOverlayWindow() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val inflater = LayoutInflater.from(this)
        floatingView = inflater.inflate(R.layout.layout_floating_window, null)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val savedPos = Prefs.getFloatingPosition(this)
        val initialX = savedPos?.first ?: 30
        val initialY = savedPos?.second ?: 200

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX
            y = initialY
        }

        setupTouchListener()
        floatingView?.alpha = Prefs.getFloatingAlpha(this)

        try {
            windowManager?.addView(floatingView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchListener() {
        val root = floatingView?.findViewById<View>(R.id.flFloatingRoot) ?: return
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        root.setOnTouchListener { _, event ->
            val p = params ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = p.x
                    initialY = p.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (!isDragging && hypot((event.rawX - initialTouchX), (event.rawY - initialTouchY)) > touchSlop) {
                        isDragging = true
                    }
                    if (isDragging) {
                        p.x = initialX + dx
                        p.y = initialY + dy
                        try {
                            windowManager?.updateViewLayout(floatingView, p)
                        } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        // 点击事件：直接启动或唤醒主界面
                        openApp()
                    } else {
                        // 拖拽结束：记录位置
                        Prefs.saveFloatingPosition(this, p.x, p.y)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
    }

    private fun removeOverlayWindow() {
        try {
            if (floatingView != null && floatingView?.isAttachedToWindow == true) {
                windowManager?.removeView(floatingView)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        floatingView = null
    }

    // ---------- 币种展示视图构建 ----------

    private fun reloadSymbols() {
        val symbols = Prefs.getFloatingSymbols(this).take(Prefs.MAX_FLOATING_COINS)
        currentSymbols = symbols

        val container = floatingView?.findViewById<LinearLayout>(R.id.llCoinsContainer) ?: return
        container.removeAllViews()
        coinViews.clear()

        val inflater = LayoutInflater.from(this)
        for (sym in symbols) {
            val itemView = inflater.inflate(R.layout.item_floating_coin, container, false)
            val tvSymbol = itemView.findViewById<TextView>(R.id.tvCoinSymbol)
            val tvPrice = itemView.findViewById<TextView>(R.id.tvCoinPrice)

            val clean = FloatingFmt.cleanSymbol(sym)
            tvSymbol.text = clean
            tvPrice.text = "—"

            container.addView(itemView)
            coinViews.add(CoinViewHolder(sym, clean, tvSymbol, tvPrice))
        }

        floatingView?.alpha = Prefs.getFloatingAlpha(this)
        restartDataSync()
    }

    // ---------- 数据刷新与同步 ----------

    private fun initRealtimeSession() {
        rtSession = RealtimeSession(
            ctx = this,
            onEvent = { ev ->
                if (ev is RtEvent.TickerUpdate) {
                    onRealtimeTicker(ev.ticker)
                }
            },
            onState = { connected ->
                if (!connected) {
                    // WS 断开时退回轮询
                    startPolling()
                }
            }
        )
    }

    private fun restartDataSync() {
        pollJob?.cancel()
        val session = rtSession
        val wantRealtime = session?.available { scope -> scope == RtScope.ALL || scope == RtScope.WATCH } == true && currentSymbols.isNotEmpty()
        if (wantRealtime) {
            session?.start(listOf(com.bitdash.app.market.RtSub.Tickers(currentSymbols)))
        } else {
            session?.stop()
        }
        startPolling()
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = serviceScope.launch {
            while (isActive) {
                fetchPrices()
                val interval = Prefs.getRefreshMs(this@FloatingWindowService)
                val delayMs = if (interval > 0) interval.coerceAtLeast(3000L) else 5000L
                delay(delayMs)
            }
        }
    }

    private suspend fun fetchPrices() {
        val symbols = currentSymbols
        if (symbols.isEmpty()) return

        try {
            val tickers = withContext(Dispatchers.IO) {
                val map = HashMap<String, Ticker>()
                Markets.withSource(this@FloatingWindowService) { src ->
                    // 尝试全量批量刷新或逐个获取
                    for (sym in symbols) {
                        try {
                            map[sym] = src.ticker(sym)
                        } catch (_: Exception) {}
                    }
                }
                map
            }

            for (vh in coinViews) {
                val ticker = tickers[vh.rawSymbol]
                if (ticker != null) {
                    updateCoinPrice(vh, ticker.last)
                }
            }
        } catch (_: Exception) {
            // 网络或源切换异常静默忽略，等待下次轮询
        }
    }

    private fun onRealtimeTicker(ticker: Ticker) {
        serviceScope.launch {
            val vh = coinViews.firstOrNull { it.rawSymbol.equals(ticker.symbol, ignoreCase = true) }
            if (vh != null) {
                updateCoinPrice(vh, ticker.last)
            }
        }
    }

    private fun updateCoinPrice(vh: CoinViewHolder, price: Double) {
        val formatted = FloatingFmt.price(price)
        vh.tvPrice.text = formatted

        // 对于小于1的小数位数较多时，适当缩减字体大小，保证胶囊简洁紧凑
        if (formatted.length >= 7) {
            vh.tvPrice.textSize = 10f
        } else {
            vh.tvPrice.textSize = 11.5f
        }
    }
}
