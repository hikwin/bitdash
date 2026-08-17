package com.bitdash.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
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
import android.view.animation.DecelerateInterpolator
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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        repositionOnOrientationChange()
        floatingView?.postDelayed({
            repositionOnOrientationChange()
        }, 100)
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

    enum class DockEdge {
        NONE, LEFT, RIGHT
    }

    private var currentDockEdge = DockEdge.NONE
    private var isAnimating = false

    private var capsuleView: View? = null
    private var dockTabLeft: View? = null
    private var dockTabRight: View? = null

    private var lastKnownScreenWidth = 0
    private var lastKnownScreenHeight = 0

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density + 0.5f).toInt()

    private fun getScreenSize(): Pair<Int, Int> {
        val wm = windowManager ?: return Pair(1080, 2400)
        var w = 1080
        var h = 2400
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = wm.currentWindowMetrics
            val bounds = windowMetrics.bounds
            w = bounds.width()
            h = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val display = wm.defaultDisplay
            val point = android.graphics.Point()
            @Suppress("DEPRECATION")
            display.getRealSize(point)
            w = point.x
            h = point.y
        }
        val dm = resources.displayMetrics
        val maxDim = maxOf(w, h, dm.widthPixels, dm.heightPixels)
        val minDim = minOf(minOf(w, h), minOf(dm.widthPixels, dm.heightPixels))

        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val realW = if (isLandscape) maxDim else minDim
        val realH = if (isLandscape) minDim else maxDim
        return Pair(realW, realH)
    }

    /**
     * 当屏幕发生横竖屏旋转时，按相对屏幕百分比或贴边状态自适应重算并保持贴边
     */
    private fun repositionOnOrientationChange() {
        val p = params ?: return
        val wm = windowManager ?: return
        val (newW, newH) = getScreenSize()
        val oldW = if (lastKnownScreenWidth > 0) lastKnownScreenWidth else newH
        val oldH = if (lastKnownScreenHeight > 0) lastKnownScreenHeight else newW

        if (newW <= 0 || newH <= 0) return

        when (currentDockEdge) {
            DockEdge.LEFT -> {
                // 旋转后依然严格紧贴左侧边缘，Y轴根据屏幕高度百分比自适应
                p.x = 0
                val ratioY = if (oldH > 0) (p.y.toFloat() / oldH).coerceIn(0f, 1f) else 0.5f
                p.y = (newH * ratioY).toInt().coerceIn(dpToPx(40), newH - dpToPx(90))
            }
            DockEdge.RIGHT -> {
                // 旋转后依然严格紧贴右侧边缘，Y轴根据屏幕高度百分比自适应
                val tabW = dpToPx(14)
                p.x = newW - tabW
                val ratioY = if (oldH > 0) (p.y.toFloat() / oldH).coerceIn(0f, 1f) else 0.5f
                p.y = (newH * ratioY).toInt().coerceIn(dpToPx(40), newH - dpToPx(90))
            }
            DockEdge.NONE -> {
                // 展开状态：按旋转前在旧屏幕中的百分比坐标，自适应映射到新屏幕宽高
                val capsule = capsuleView
                val capW = capsule?.width?.takeIf { it > 0 } ?: dpToPx(70)
                val capH = capsule?.height?.takeIf { it > 0 } ?: dpToPx(90)

                val ratioX = if (oldW > 0) (p.x.toFloat() / oldW).coerceIn(0f, 1f) else 0.1f
                val ratioY = if (oldH > 0) (p.y.toFloat() / oldH).coerceIn(0f, 1f) else 0.2f

                val newX = (newW * ratioX).toInt().coerceIn(dpToPx(10), (newW - capW - dpToPx(10)).coerceAtLeast(dpToPx(10)))
                val newY = (newH * ratioY).toInt().coerceIn(dpToPx(40), (newH - capH - dpToPx(40)).coerceAtLeast(dpToPx(40)))

                p.x = newX
                p.y = newY
                Prefs.saveFloatingPosition(this, newX, newY)
            }
        }

        lastKnownScreenWidth = newW
        lastKnownScreenHeight = newH

        try {
            wm.updateViewLayout(floatingView, p)
        } catch (_: Exception) {}
    }

    private fun getDockTabView(edge: DockEdge): View? = when (edge) {
        DockEdge.LEFT -> dockTabLeft
        DockEdge.RIGHT -> dockTabRight
        DockEdge.NONE -> null
    }

    // ---------- 悬浮窗 View 与拖拽手势 ----------

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    private fun initOverlayWindow() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val inflater = LayoutInflater.from(this)
        floatingView = inflater.inflate(R.layout.layout_floating_window, null)

        capsuleView = floatingView?.findViewById(R.id.llFloatingCapsule)
        dockTabLeft = floatingView?.findViewById(R.id.dockTabLeft)
        dockTabRight = floatingView?.findViewById(R.id.dockTabRight)

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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        setupTouchListener()
        floatingView?.alpha = Prefs.getFloatingAlpha(this)

        val (initW, initH) = getScreenSize()
        lastKnownScreenWidth = initW
        lastKnownScreenHeight = initH

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
            if (isAnimating) return@setOnTouchListener true
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
                        if (currentDockEdge == DockEdge.NONE) {
                            // 完整展开状态：全屏自由拖拽
                            p.x = initialX + dx
                            p.y = initialY + dy
                            try {
                                windowManager?.updateViewLayout(floatingView, p)
                            } catch (_: Exception) {}
                        } else {
                            // 贴边状态：严格锁死贴在左右边缘，支持沿屏幕边缘上下滑动；向内拖动超过阈值则直接抽出展开
                            val (screenWidth, screenHeight) = getScreenSize()
                            when (currentDockEdge) {
                                DockEdge.LEFT -> {
                                    p.x = 0
                                    if (dx > dpToPx(24)) {
                                        animateFromDock()
                                        return@setOnTouchListener true
                                    }
                                    p.y = (initialY + dy).coerceIn(dpToPx(40), screenHeight - dpToPx(90))
                                }
                                DockEdge.RIGHT -> {
                                    val tabW = dpToPx(14)
                                    p.x = screenWidth - tabW
                                    if (dx < -dpToPx(24)) {
                                        animateFromDock()
                                        return@setOnTouchListener true
                                    }
                                    p.y = (initialY + dy).coerceIn(dpToPx(40), screenHeight - dpToPx(90))
                                }
                                DockEdge.NONE -> {}
                            }
                            try {
                                windowManager?.updateViewLayout(floatingView, p)
                            } catch (_: Exception) {}
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val (screenWidth, screenHeight) = getScreenSize()
                    lastKnownScreenWidth = screenWidth
                    lastKnownScreenHeight = screenHeight

                    if (!isDragging) {
                        // 点击事件：贴边状态点击弹出完整悬浮窗；完整状态点击唤醒主界面
                        if (currentDockEdge != DockEdge.NONE) {
                            animateFromDock()
                        } else {
                            openApp()
                        }
                    } else {
                        if (currentDockEdge == DockEdge.NONE) {
                            // 拖拽松手：判断是否靠近或超过左/右屏幕边缘
                            val capsule = capsuleView
                            val w = capsule?.width?.takeIf { it > 0 } ?: dpToPx(70)
                            val h = capsule?.height?.takeIf { it > 0 } ?: dpToPx(90)

                            val isLeftDock = (p.x + w / 2 < 0) || (p.x <= dpToPx(4)) || (p.x + w / 2 < w / 2 + dpToPx(12))
                            val isRightDock = (p.x + w / 2 > screenWidth) || (p.x + w >= screenWidth - dpToPx(4)) || (p.x + w / 2 > screenWidth - (w / 2 + dpToPx(12)))

                            if (isLeftDock || isRightDock) {
                                val edge = if (isLeftDock && isRightDock) {
                                    if (p.x < screenWidth / 2) DockEdge.LEFT else DockEdge.RIGHT
                                } else if (isLeftDock) {
                                    DockEdge.LEFT
                                } else {
                                    DockEdge.RIGHT
                                }
                                animateToDock(edge)
                            } else {
                                // 未触发贴边（包括上下边缘保护），平滑归位弹回屏幕安全显示范围
                                val safeX = p.x.coerceIn(dpToPx(10), (screenWidth - w - dpToPx(10)).coerceAtLeast(dpToPx(10)))
                                val safeY = p.y.coerceIn(dpToPx(40), (screenHeight - h - dpToPx(40)).coerceAtLeast(dpToPx(40)))
                                if (safeX != p.x || safeY != p.y) {
                                    animateSnapTo(safeX, safeY)
                                } else {
                                    Prefs.saveFloatingPosition(this, p.x, p.y)
                                }
                            }
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    /**
     * 贴边收起动画（类似收起抽屉）：平滑收缩至左/右屏幕边缘变成悬浮条
     */
    private fun animateToDock(edge: DockEdge) {
        val p = params ?: return
        val wm = windowManager ?: return
        val (screenWidth, screenHeight) = getScreenSize()
        val capsule = capsuleView ?: return
        val activeTab = getDockTabView(edge) ?: return

        if (isAnimating) return
        isAnimating = true

        val startX = p.x
        val startY = p.y

        val capsuleW = capsule.width.coerceAtLeast(dpToPx(60))

        listOf(dockTabLeft, dockTabRight).forEach {
            if (it != activeTab) it?.visibility = View.GONE
        }

        activeTab.visibility = View.VISIBLE
        activeTab.alpha = 0f

        val targetX: Int
        val targetY = startY.coerceIn(dpToPx(40), screenHeight - dpToPx(90))

        when (edge) {
            DockEdge.LEFT -> {
                targetX = 0
            }
            DockEdge.RIGHT -> {
                val tabW = dpToPx(14)
                targetX = screenWidth - tabW
            }
            DockEdge.NONE -> {
                isAnimating = false
                return
            }
        }

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { va ->
                val f = va.animatedFraction
                p.x = (startX + (targetX - startX) * f).toInt()
                p.y = (startY + (targetY - startY) * f).toInt()

                capsule.alpha = (1f - f).coerceIn(0f, 1f)
                activeTab.alpha = f.coerceIn(0f, 1f)

                when (edge) {
                    DockEdge.LEFT -> {
                        capsule.translationX = -capsuleW * f
                        activeTab.translationX = -dpToPx(14) * (1f - f)
                    }
                    DockEdge.RIGHT -> {
                        capsule.translationX = capsuleW * f
                        activeTab.translationX = dpToPx(14) * (1f - f)
                    }
                    else -> {}
                }

                try {
                    wm.updateViewLayout(floatingView, p)
                } catch (_: Exception) {}
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    capsule.visibility = View.GONE
                    capsule.translationX = 0f
                    capsule.translationY = 0f
                    capsule.alpha = 1f

                    activeTab.visibility = View.VISIBLE
                    activeTab.alpha = 1f
                    activeTab.translationX = 0f
                    activeTab.translationY = 0f

                    currentDockEdge = edge
                    p.x = targetX
                    p.y = targetY
                    try {
                        wm.updateViewLayout(floatingView, p)
                    } catch (_: Exception) {}
                    isAnimating = false
                }
            })
        }
        animator.start()
    }

    /**
     * 贴边抽出/弹出动画（类似抽出抽屉）：从左右悬浮条平滑抽出展示完整悬浮窗
     */
    private fun animateFromDock() {
        val p = params ?: return
        val wm = windowManager ?: return
        val (screenWidth, screenHeight) = getScreenSize()
        val capsule = capsuleView ?: return
        val edge = currentDockEdge
        val activeTab = getDockTabView(edge) ?: return

        if (isAnimating) return
        isAnimating = true

        val startX = p.x
        val startY = p.y

        capsule.visibility = View.VISIBLE
        capsule.alpha = 0f
        capsule.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val capsuleW = capsule.measuredWidth.coerceAtLeast(dpToPx(60))
        val capsuleH = capsule.measuredHeight.coerceAtLeast(dpToPx(80))

        val targetX: Int
        val targetY = startY.coerceIn(dpToPx(40), (screenHeight - capsuleH - dpToPx(40)).coerceAtLeast(dpToPx(40)))

        when (edge) {
            DockEdge.LEFT -> {
                targetX = dpToPx(12)
            }
            DockEdge.RIGHT -> {
                targetX = (screenWidth - capsuleW - dpToPx(12)).coerceAtLeast(dpToPx(12))
            }
            DockEdge.NONE -> {
                isAnimating = false
                return
            }
        }

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { va ->
                val f = va.animatedFraction
                p.x = (startX + (targetX - startX) * f).toInt()
                p.y = (startY + (targetY - startY) * f).toInt()

                capsule.alpha = f.coerceIn(0f, 1f)
                activeTab.alpha = (1f - f).coerceIn(0f, 1f)

                when (edge) {
                    DockEdge.LEFT -> {
                        capsule.translationX = -capsuleW * (1f - f)
                        activeTab.translationX = -dpToPx(14) * f
                    }
                    DockEdge.RIGHT -> {
                        capsule.translationX = capsuleW * (1f - f)
                        activeTab.translationX = dpToPx(14) * f
                    }
                    else -> {}
                }

                try {
                    wm.updateViewLayout(floatingView, p)
                } catch (_: Exception) {}
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    activeTab.visibility = View.GONE
                    activeTab.translationX = 0f
                    activeTab.translationY = 0f
                    activeTab.alpha = 1f

                    capsule.visibility = View.VISIBLE
                    capsule.alpha = 1f
                    capsule.translationX = 0f
                    capsule.translationY = 0f

                    currentDockEdge = DockEdge.NONE
                    p.x = targetX
                    p.y = targetY
                    try {
                        wm.updateViewLayout(floatingView, p)
                    } catch (_: Exception) {}
                    Prefs.saveFloatingPosition(this@FloatingWindowService, targetX, targetY)
                    isAnimating = false
                }
            })
        }
        animator.start()
    }

    /**
     * 平滑安全归位动画（上下边缘贴边保护或未出界超过一半时）
     */
    private fun animateSnapTo(targetX: Int, targetY: Int) {
        val p = params ?: return
        val wm = windowManager ?: return
        if (isAnimating) return
        isAnimating = true

        val startX = p.x
        val startY = p.y
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener { va ->
                val f = va.animatedFraction
                p.x = (startX + (targetX - startX) * f).toInt()
                p.y = (startY + (targetY - startY) * f).toInt()
                try {
                    wm.updateViewLayout(floatingView, p)
                } catch (_: Exception) {}
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    p.x = targetX
                    p.y = targetY
                    try {
                        wm.updateViewLayout(floatingView, p)
                    } catch (_: Exception) {}
                    Prefs.saveFloatingPosition(this@FloatingWindowService, targetX, targetY)
                    isAnimating = false
                }
            })
        }
        animator.start()
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

        if (currentDockEdge == DockEdge.NONE) {
            capsuleView?.visibility = View.VISIBLE
            dockTabLeft?.visibility = View.GONE
            dockTabRight?.visibility = View.GONE
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
