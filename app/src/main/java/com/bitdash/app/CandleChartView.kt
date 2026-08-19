package com.bitdash.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.content.ContextCompat
import com.bitdash.app.market.Candle
import com.bitdash.app.market.Fmt
import com.bitdash.app.market.Indicators
import com.bitdash.app.market.Palette
import com.bitdash.app.market.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 自定义专业级 K 线图（蜡烛 + MA/BOLL 主图 + VOL/MACD/RSI/KDJ 副图 + 右侧价格轴 + 底部时间轴）。
 */
class CandleChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class IndicatorValues(
        val ma1: Float? = null,
        val ma2: Float? = null,
        val ma3: Float? = null,
        val bollMid: Float? = null,
        val bollUp: Float? = null,
        val bollDn: Float? = null,
        val turtleUp: Float? = null,
        val turtleDn: Float? = null,
        val turtleExitLong: Float? = null,
        val turtleExitShort: Float? = null,
        val turtleAtr: Float? = null,
        val vol: Double? = null,
        val dif: Float? = null,
        val dea: Float? = null,
        val macd: Float? = null,
        val rsi1: Float? = null,
        val rsi2: Float? = null,
        val rsi3: Float? = null,
        val k: Float? = null,
        val d: Float? = null,
        val j: Float? = null
    )

    // ---------- 数据 ----------
    private var candles: List<Candle> = emptyList()

    // 主图指标数据
    private var ma1: FloatArray = FloatArray(0)
    private var ma2: FloatArray = FloatArray(0)
    private var ma3: FloatArray = FloatArray(0)
    private var bollResult: Indicators.BollResult? = null
    private var turtleResult: Indicators.TurtleResult? = null

    // 副图指标数据
    private var macdResult: Indicators.MacdResult? = null
    private var rsi1: FloatArray = FloatArray(0)
    private var rsi2: FloatArray = FloatArray(0)
    private var rsi3: FloatArray = FloatArray(0)
    private var kdjResult: Indicators.KdjResult? = null

    // ---------- 开关与类型配置 ----------
    var showMa1 = Prefs.getShowMa1(context)
    var showMa2 = Prefs.getShowMa2(context)
    var showMa3 = Prefs.getShowMa3(context)
    var showBoll = Prefs.getShowBoll(context)
    var showTurtle = Prefs.getShowTurtle(context)
    var subIndicatorType = Prefs.getSubIndicator(context) // "VOL", "MACD", "RSI", "KDJ", "OFF"

    // ---------- 显示状态 ----------
    private var visibleCount = 90f     // 可见蜡烛数
    private var scrollFromRight = 0f   // 右端偏移

    // 十字光标（-1 表示隐藏）
    private var crossIndex = -1
    private var crossY = -1f
    private var isTrackingCrosshair = false

    // 回调
    var onCrosshairChange: ((Candle?) -> Unit)? = null
    var onIndicatorsChange: ((IndicatorValues) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private var fontScale: Float = Prefs.getChartFontScale(context)

    // ---------- 尺寸 ----------
    private var axisW = 0f
    private var axisH = 0f
    private var chartW = 0f
    private var priceH = 0f
    private var volTop = 0f
    private var volH = 0f

    // ---------- 画笔 ----------
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1f
    }
    private val subGridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1f
        pathEffect = DashPathEffect(floatArrayOf(3f * density, 3f * density), 0f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val upPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val downPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val upStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f * density
    }
    private val downStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f * density
    }
    private val ma1Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.2f * density
    }
    private val ma2Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.2f * density
    }
    private val ma3Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.2f * density
    }
    private val bollUpPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.2f * density
    }
    private val bollDnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.2f * density
    }
    private val turtleUpPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.2f * density
    }
    private val turtleDnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.2f * density
    }
    private val turtleExitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f * density
        pathEffect = DashPathEffect(floatArrayOf(3f * density, 3f * density), 0f)
    }
    private val turtleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val difPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.2f * density
    }
    private val deaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.2f * density
    }
    private val rsi1Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.2f * density
    }
    private val rsi2Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.2f * density
    }
    private val rsi3Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.2f * density
    }
    private val kdjKPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.2f * density
    }
    private val kdjDPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.2f * density
    }
    private val kdjJPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.2f * density
    }
    private val lastLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f * density
        pathEffect = DashPathEffect(floatArrayOf(5f * density, 4f * density), 0f)
    }
    private val tagPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tagTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFakeBoldText = true
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(4f * density, 3f * density), 0f)
    }
    private val crossDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFF0B90B.toInt()
    }
    private val crossDotStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFFFFFFF.toInt()
    }
    private val crossLabelBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val crossTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFakeBoldText = true
    }
    private val subLegendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFakeBoldText = true
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    private val linePath = Path()

    // 时间格式
    var timePattern = "MM-dd HH:mm"
        set(v) { field = v; timeFmt = SimpleDateFormat(v, Locale.getDefault()); invalidate() }
    private var timeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    init {
        subLegendPaint.textSize = 9.5f * density * fontScale
        updateThemeColors()
    }

    /** 主题或配色切换时刷新画笔颜色 */
    fun updateThemeColors() {
        val border = ContextCompat.getColor(context, R.color.border)
        val brand = ContextCompat.getColor(context, R.color.brand)
        val textMain = ContextCompat.getColor(context, R.color.text_main)
        val textMuted = ContextCompat.getColor(context, R.color.text_muted)
        val textDim = ContextCompat.getColor(context, R.color.text_dim)
        val isNight = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        gridPaint.color = (border and 0x00FFFFFF) or 0x40000000
        subGridPaint.color = (border and 0x00FFFFFF) or 0x30000000
        textPaint.color = textMuted
        timePaint.color = textMuted
        upPaint.color = Palette.up(context)
        downPaint.color = Palette.down(context)
        upStroke.color = Palette.up(context)
        downStroke.color = Palette.down(context)

        ma1Paint.color = ContextCompat.getColor(context, R.color.ma5)
        ma2Paint.color = ContextCompat.getColor(context, R.color.ma10)
        ma3Paint.color = ContextCompat.getColor(context, R.color.ma20)

        bollUpPaint.color = ContextCompat.getColor(context, R.color.boll_up)
        bollDnPaint.color = ContextCompat.getColor(context, R.color.boll_dn)

        turtleUpPaint.color = ContextCompat.getColor(context, R.color.turtle_up)
        turtleDnPaint.color = ContextCompat.getColor(context, R.color.turtle_dn)
        turtleExitPaint.color = ContextCompat.getColor(context, R.color.turtle_exit)
        turtleFillPaint.color = ContextCompat.getColor(context, R.color.turtle_fill)

        difPaint.color = textMain
        deaPaint.color = ContextCompat.getColor(context, R.color.ma5)

        rsi1Paint.color = ContextCompat.getColor(context, R.color.ma5)
        rsi2Paint.color = ContextCompat.getColor(context, R.color.ma10)
        rsi3Paint.color = ContextCompat.getColor(context, R.color.ma20)

        kdjKPaint.color = ContextCompat.getColor(context, R.color.ma5)
        kdjDPaint.color = ContextCompat.getColor(context, R.color.ma10)
        kdjJPaint.color = ContextCompat.getColor(context, R.color.boll_up)

        lastLinePaint.color = brand
        tagPaint.color = brand
        tagTextPaint.color = ContextCompat.getColor(context, R.color.black)

        // 十字光标高对比度高亮（暗色下明亮浅白蓝，亮色下深灰，绝不与背景混淆）
        crossPaint.color = if (isNight) 0xFFCBD5E1.toInt() else 0xFF475569.toInt()
        crossPaint.strokeWidth = 1.2f * density
        crossDotStroke.strokeWidth = 1.5f * density
        crossLabelBg.color = if (isNight) 0xFF334155.toInt() else 0xFF1E293B.toInt()
        crossTextPaint.color = 0xFFFFFFFF.toInt()

        emptyPaint.color = textDim
        invalidate()
    }

    // ---------- 公开 API ----------

    fun applyFontScale(scale: Float = Prefs.getChartFontScale(context)) {
        fontScale = scale
        textPaint.textSize = 10f * density * fontScale
        timePaint.textSize = 10f * density * fontScale
        tagTextPaint.textSize = 10f * density * fontScale
        crossTextPaint.textSize = 10f * density * fontScale
        subLegendPaint.textSize = 9.5f * density * fontScale
        emptyPaint.textSize = 14f * density * fontScale
        updateThemeColors()
        recomputeDimensions(width, height)
        invalidate()
    }

    /** 刷新指标开关并重绘 */
    fun refreshIndicatorToggles() {
        showMa1 = Prefs.getShowMa1(context)
        showMa2 = Prefs.getShowMa2(context)
        showMa3 = Prefs.getShowMa3(context)
        showBoll = Prefs.getShowBoll(context)
        showTurtle = Prefs.getShowTurtle(context)
        subIndicatorType = Prefs.getSubIndicator(context)
        computeAllIndicators()
        notifyCurrentIndicatorValues()
        invalidate()
    }

    /** 替换数据，保留当前缩放/平移状态 */
    fun setData(list: List<Candle>) {
        candles = list
        computeAllIndicators()
        clampScroll()
        if (crossIndex >= 0) {
            if (crossIndex < list.size) {
                onCrosshairChange?.invoke(list[crossIndex])
            } else {
                crossIndex = -1
                onCrosshairChange?.invoke(null)
            }
        }
        notifyCurrentIndicatorValues()
        invalidate()
    }

    fun isEmptyData(): Boolean = candles.isEmpty()

    fun applyPalette() {
        val up = Palette.up(context)
        val down = Palette.down(context)
        upPaint.color = up
        upStroke.color = up
        downPaint.color = down
        downStroke.color = down
        invalidate()
    }

    fun resetView() {
        scrollFromRight = 0f
        crossIndex = -1
        crossY = -1f
        isTrackingCrosshair = false
        onCrosshairChange?.invoke(null)
        notifyCurrentIndicatorValues()
        invalidate()
    }

    // ---------- 手势 ----------

    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var scaling = false
    private var dragging = false
    private val touchSlop = 8f * density

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (candles.isEmpty() || chartW <= 0f) return true
                val old = visibleCount
                visibleCount = (visibleCount / detector.scaleFactor)
                    .coerceIn(MIN_VISIBLE.toFloat(), candles.size.coerceAtLeast(MIN_VISIBLE).toFloat())
                val focusRatio = ((detector.focusX - paddingLeft) / chartW).coerceIn(0f, 1f)
                scrollFromRight += (old - visibleCount) * (1f - focusRatio)
                clampScroll()
                invalidate()
                return true
            }
        })

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onLongPress(e: MotionEvent) {
                isTrackingCrosshair = true
                parent?.requestDisallowInterceptTouchEvent(true)
                updateCrosshair(e.x, e.y)
                try {
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                } catch (_: Exception) {}
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (crossIndex < 0) {
                    updateCrosshair(e.x, e.y)
                } else {
                    hideCrosshair()
                    invalidate()
                }
                return true
            }
        }
    )

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastX = event.x
                lastY = event.y
                downX = event.x
                downY = event.y
                scaling = false
                dragging = false
                if (crossIndex >= 0) {
                    isTrackingCrosshair = true
                    updateCrosshair(event.x, event.y)
                }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                scaling = true
                isTrackingCrosshair = false
                hideCrosshair()
            }
            MotionEvent.ACTION_MOVE -> {
                if (scaleDetector.isInProgress) {
                    scaling = true
                    isTrackingCrosshair = false
                } else if (isTrackingCrosshair) {
                    // 长按或十字线开启后滑动：跟随手指精确移动十字光标
                    updateCrosshair(event.x, event.y)
                } else if (!scaling) {
                    if (!dragging &&
                        abs(event.x - downX) > touchSlop &&
                        abs(event.x - downX) > abs(event.y - downY)
                    ) {
                        dragging = true
                    }
                    if (dragging) {
                        val dx = event.x - lastX
                        lastX = event.x
                        val candleW = candleWidth()
                        if (candleW > 0f) {
                            scrollFromRight += dx / candleW
                            clampScroll()
                            hideCrosshair()
                            invalidate()
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isTrackingCrosshair = false
                scaling = false
                dragging = false
            }
        }
        return true
    }

    private fun hideCrosshair() {
        if (crossIndex >= 0) {
            crossIndex = -1
            crossY = -1f
            isTrackingCrosshair = false
            onCrosshairChange?.invoke(null)
            notifyCurrentIndicatorValues()
        }
    }

    private fun updateCrosshair(x: Float, y: Float) {
        if (candles.isEmpty() || chartW <= 0f) return
        val cw = candleWidth()
        if (cw <= 0f) return
        val range = visibleRange() ?: return
        val idxFloat = (candles.size - 1 - scrollFromRight + 0.5f) - (paddingLeft + chartW - x) / cw
        val idx = kotlin.math.round(idxFloat).toInt().coerceIn(range.first, range.last)
        crossIndex = idx.coerceIn(0, candles.size - 1)
        crossY = y.coerceIn(paddingTop.toFloat(), paddingTop + priceH)
        onCrosshairChange?.invoke(candles.getOrNull(crossIndex))
        notifyCurrentIndicatorValues(crossIndex)
        invalidate()
    }

    private fun notifyCurrentIndicatorValues(targetIdx: Int = if (crossIndex >= 0) crossIndex else (candles.size - 1)) {
        if (candles.isEmpty() || targetIdx !in candles.indices) return
        val c = candles[targetIdx]
        val m1 = if (targetIdx < ma1.size && ma1[targetIdx] > 0f) ma1[targetIdx] else null
        val m2 = if (targetIdx < ma2.size && ma2[targetIdx] > 0f) ma2[targetIdx] else null
        val m3 = if (targetIdx < ma3.size && ma3[targetIdx] > 0f) ma3[targetIdx] else null

        val bMid = bollResult?.mid?.getOrNull(targetIdx)?.takeIf { it > 0f }
        val bUp = bollResult?.up?.getOrNull(targetIdx)?.takeIf { it > 0f }
        val bDn = bollResult?.dn?.getOrNull(targetIdx)?.takeIf { it > 0f }

        val dif = macdResult?.dif?.getOrNull(targetIdx)
        val dea = macdResult?.dea?.getOrNull(targetIdx)
        val macd = macdResult?.macd?.getOrNull(targetIdx)

        val r1 = rsi1.getOrNull(targetIdx)?.takeIf { it > 0f }
        val r2 = rsi2.getOrNull(targetIdx)?.takeIf { it > 0f }
        val r3 = rsi3.getOrNull(targetIdx)?.takeIf { it > 0f }

        val k = kdjResult?.k?.getOrNull(targetIdx)
        val d = kdjResult?.d?.getOrNull(targetIdx)
        val j = kdjResult?.j?.getOrNull(targetIdx)

        val tUp = turtleResult?.upper?.getOrNull(targetIdx)?.takeIf { it > 0f }
        val tDn = turtleResult?.lower?.getOrNull(targetIdx)?.takeIf { it > 0f }
        val tExL = turtleResult?.exitLong?.getOrNull(targetIdx)?.takeIf { it > 0f }
        val tExS = turtleResult?.exitShort?.getOrNull(targetIdx)?.takeIf { it > 0f }
        val tAtr = turtleResult?.atr?.getOrNull(targetIdx)?.takeIf { it > 0f }

        val values = IndicatorValues(
            ma1 = m1, ma2 = m2, ma3 = m3,
            bollMid = bMid, bollUp = bUp, bollDn = bDn,
            turtleUp = tUp, turtleDn = tDn, turtleExitLong = tExL, turtleExitShort = tExS, turtleAtr = tAtr,
            vol = c.vol,
            dif = dif, dea = dea, macd = macd,
            rsi1 = r1, rsi2 = r2, rsi3 = r3,
            k = k, d = d, j = j
        )
        onIndicatorsChange?.invoke(values)
    }

    // ---------- 布局与绘制 ----------

    private fun recomputeDimensions(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        axisW = 58f * density * fontScale
        axisH = 16f * density * fontScale
        chartW = (w - paddingLeft - paddingRight - axisW).coerceAtLeast(10f)
        val body = (h - paddingTop - paddingBottom - axisH).coerceAtLeast(10f)
        if (subIndicatorType == "OFF") {
            priceH = body
            volH = 0f
            volTop = paddingTop + priceH
        } else {
            priceH = body * 0.74f
            volH = body * 0.26f
            volTop = paddingTop + priceH
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeDimensions(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val range = visibleRange() ?: return

        val leftIdx = range.first
        val rightIdx = range.last
        val sub = candles.subList(leftIdx, rightIdx + 1)

        // ---- 1. 计算主图价格范围 ----
        var minP = Double.MAX_VALUE
        var maxP = -Double.MAX_VALUE
        for (c in sub) {
            if (c.low < minP) minP = c.low
            if (c.high > maxP) maxP = c.high
        }

        // MA / BOLL / TURTLE 纳入价格轴
        for (i in leftIdx..rightIdx) {
            if (showMa1 && i < ma1.size && ma1[i] > 0f) {
                if (ma1[i] < minP) minP = ma1[i].toDouble()
                if (ma1[i] > maxP) maxP = ma1[i].toDouble()
            }
            if (showMa2 && i < ma2.size && ma2[i] > 0f) {
                if (ma2[i] < minP) minP = ma2[i].toDouble()
                if (ma2[i] > maxP) maxP = ma2[i].toDouble()
            }
            if (showMa3 && i < ma3.size && ma3[i] > 0f) {
                if (ma3[i] < minP) minP = ma3[i].toDouble()
                if (ma3[i] > maxP) maxP = ma3[i].toDouble()
            }
            if (showBoll) {
                bollResult?.let { boll ->
                    if (i < boll.up.size && boll.up[i] > 0f) {
                        if (boll.up[i] > maxP) maxP = boll.up[i].toDouble()
                        if (boll.dn[i] < minP && boll.dn[i] > 0f) minP = boll.dn[i].toDouble()
                    }
                }
            }
            if (showTurtle) {
                turtleResult?.let { t ->
                    if (i < t.upper.size && t.upper[i] > 0f) {
                        if (t.upper[i] > maxP) maxP = t.upper[i].toDouble()
                        if (t.lower[i] < minP && t.lower[i] > 0f) minP = t.lower[i].toDouble()
                    }
                }
            }
        }

        if (minP == Double.MAX_VALUE) { minP = 0.0; maxP = 1.0 }
        if (maxP <= minP) maxP = minP + max(abs(minP) * 1e-4, 1e-8)
        val padP = (maxP - minP) * 0.08
        minP -= padP
        maxP += padP
        val priceRange = (maxP - minP).coerceAtLeast(1e-12)

        // ---- 2. 绘制主图网格与价格刻度 ----
        val labelDy = -(textPaint.ascent() + textPaint.descent()) / 2f
        for (g in 0..GRID_LINES) {
            val frac = g.toFloat() / GRID_LINES
            val y = paddingTop + priceH * (1f - frac)
            canvas.drawLine(paddingLeft.toFloat(), y, paddingLeft + chartW, y, gridPaint)
            val p = minP + (maxP - minP) * frac
            canvas.drawText(
                Fmt.price(p), paddingLeft + chartW + 4f * density, y + labelDy, textPaint
            )
        }

        val cw = candleWidth()
        val bodyW = (cw * 0.7f).coerceAtLeast(1f)

        // ---- 3. 绘制主图蜡烛 ----
        for (i in leftIdx..rightIdx) {
            val c = candles[i]
            val x = xOfIndex(i.toFloat())
            val up = c.close >= c.open

            // 影线
            canvas.drawLine(
                x, yOfPrice(c.high, minP, priceRange), x, yOfPrice(c.low, minP, priceRange),
                if (up) upStroke else downStroke
            )

            // 实体
            val yOpen = yOfPrice(c.open, minP, priceRange)
            val yClose = yOfPrice(c.close, minP, priceRange)
            val top = min(yOpen, yClose)
            val bot = max(yOpen, yClose).coerceAtLeast(top + 1f)
            canvas.drawRect(
                x - bodyW / 2f, top, x + bodyW / 2f, bot, if (up) upPaint else downPaint
            )
        }

        // ---- 4. 绘制主图 MA 折线 ----
        if (showMa1) drawLineSeries(canvas, ma1, leftIdx, rightIdx, cw, minP, priceRange, ma1Paint)
        if (showMa2) drawLineSeries(canvas, ma2, leftIdx, rightIdx, cw, minP, priceRange, ma2Paint)
        if (showMa3) drawLineSeries(canvas, ma3, leftIdx, rightIdx, cw, minP, priceRange, ma3Paint)

        // ---- 5. 绘制主图 BOLL 轨道线 ----
        if (showBoll) {
            bollResult?.let { boll ->
                drawLineSeries(canvas, boll.up, leftIdx, rightIdx, cw, minP, priceRange, bollUpPaint)
                drawLineSeries(canvas, boll.mid, leftIdx, rightIdx, cw, minP, priceRange, ma1Paint)
                drawLineSeries(canvas, boll.dn, leftIdx, rightIdx, cw, minP, priceRange, bollDnPaint)
            }
        }

        // ---- 5.2 绘制主图 TURTLE 海龟通道 ----
        if (showTurtle) {
            turtleResult?.let { t ->
                drawTurtleChannel(canvas, t, leftIdx, rightIdx, cw, minP, priceRange)
            }
        }

        // ---- 6. 绘制副图指标 ----
        if (subIndicatorType != "OFF" && volH > 0f) {
            // 分隔线
            canvas.drawLine(paddingLeft.toFloat(), volTop, paddingLeft + chartW, volTop, gridPaint)
            when (subIndicatorType) {
                "VOL" -> drawSubVol(canvas, leftIdx, rightIdx, cw, bodyW)
                "MACD" -> drawSubMacd(canvas, leftIdx, rightIdx, cw, bodyW)
                "RSI" -> drawSubRsi(canvas, leftIdx, rightIdx, cw)
                "KDJ" -> drawSubKdj(canvas, leftIdx, rightIdx, cw)
            }
            // 绘制副图左上角指标参数及数值详情（十字线选中时为十字线所在K线，否则为最新K线）
            val targetIdx = if (crossIndex in leftIdx..rightIdx) crossIndex else (candles.size - 1)
            if (targetIdx in candles.indices) {
                drawSubIndicatorLegend(canvas, targetIdx)
            }
        }

        // ---- 7. 最新价虚线 + 右轴标签 ----
        val lastClose = candles[candles.size - 1].close
        val yLast = yOfPrice(lastClose, minP, priceRange)
        if (yLast >= paddingTop && yLast <= paddingTop + priceH) {
            canvas.drawLine(paddingLeft.toFloat(), yLast, paddingLeft + chartW, yLast, lastLinePaint)
            drawAxisTag(canvas, Fmt.price(lastClose), yLast, tagPaint, tagTextPaint)
        }

        // ---- 8. 时间轴 ----
        val timeDy = paddingTop + priceH + volH + axisH - 3f * density
        if (leftIdx < rightIdx) {
            for (t in 0 until TIME_TICKS) {
                val idx = leftIdx + (rightIdx - leftIdx) * t / (TIME_TICKS - 1)
                if (idx in candles.indices) {
                    val cx = xOfIndex(idx.toFloat())
                        .coerceIn(paddingLeft + 24f * density, paddingLeft + chartW - 24f * density)
                    canvas.drawText(timeFmt.format(Date(candles[idx].ts)), cx, timeDy, timePaint)
                }
            }
        }

        // ---- 9. 十字光标 ----
        if (crossIndex in leftIdx..rightIdx && crossY >= 0f) {
            val x = xOfIndex(crossIndex.toFloat())
            val bottomY = if (subIndicatorType != "OFF" && volH > 0f) volTop + volH else paddingTop + priceH
            canvas.drawLine(x, paddingTop.toFloat(), x, bottomY, crossPaint)
            canvas.drawLine(paddingLeft.toFloat(), crossY, paddingLeft + chartW, crossY, crossPaint)

            // 焦点圆点（金色实心 + 高亮外圈）
            canvas.drawCircle(x, crossY, 3.5f * density, crossDotPaint)
            canvas.drawCircle(x, crossY, 3.5f * density, crossDotStroke)

            // 右侧价格轴高亮标签
            drawAxisTag(
                canvas, Fmt.price(priceAtY(crossY, minP, priceRange)), crossY,
                crossLabelBg, crossTextPaint
            )

            // 底部时间轴高亮标签
            val timeStr = timeFmt.format(Date(candles[crossIndex].ts))
            drawTimeAxisTag(canvas, timeStr, x)
        }
    }

    private fun drawTimeAxisTag(canvas: Canvas, text: String, x: Float) {
        val tagW = crossTextPaint.measureText(text) + 8f * density
        val tagH = (crossTextPaint.descent() - crossTextPaint.ascent()) + 4f * density
        val top = paddingTop + priceH + volH + 2f * density
        val left = (x - tagW / 2f).coerceIn(paddingLeft.toFloat(), paddingLeft + chartW - tagW)
        canvas.drawRoundRect(
            RectF(left, top, left + tagW, top + tagH),
            3f * density, 3f * density, crossLabelBg
        )
        canvas.drawText(
            text, left + 4f * density,
            top + tagH / 2f - (crossTextPaint.ascent() + crossTextPaint.descent()) / 2f,
            crossTextPaint
        )
    }

    // ---------- 副图绘制实现 ----------

    private fun drawSubVol(canvas: Canvas, leftIdx: Int, rightIdx: Int, cw: Float, bodyW: Float) {
        var maxV = 0.0
        for (i in leftIdx..rightIdx) {
            val v = candles[i].vol
            if (v > maxV) maxV = v
        }
        if (maxV <= 0.0) maxV = 1.0

        for (i in leftIdx..rightIdx) {
            val c = candles[i]
            val x = xOfIndex(i.toFloat())
            val up = c.close >= c.open
            val hFrac = (c.vol / maxV).toFloat().coerceIn(0f, 1f)
            val vh = volH * 0.92f * hFrac
            canvas.drawRect(
                x - bodyW / 2f, volTop + volH - vh, x + bodyW / 2f, volTop + volH,
                if (up) upPaint else downPaint
            )
        }
        val labelDy = -(textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(Fmt.vol(maxV), paddingLeft + chartW + 4f * density, volTop + 10f * density + labelDy, textPaint)
    }

    private fun drawSubMacd(canvas: Canvas, leftIdx: Int, rightIdx: Int, cw: Float, bodyW: Float) {
        val macd = macdResult ?: return
        var maxAbs = 0.0001
        for (i in leftIdx..rightIdx) {
            if (i < macd.dif.size) {
                maxAbs = max(maxAbs, abs(macd.dif[i].toDouble()))
                maxAbs = max(maxAbs, abs(macd.dea[i].toDouble()))
                maxAbs = max(maxAbs, abs(macd.macd[i].toDouble()))
            }
        }
        val zeroY = volTop + volH / 2f
        // 0 轴基准线
        canvas.drawLine(paddingLeft.toFloat(), zeroY, paddingLeft + chartW, zeroY, subGridPaint)

        val halfH = volH * 0.45f
        for (i in leftIdx..rightIdx) {
            if (i < macd.macd.size) {
                val x = xOfIndex(i.toFloat())
                val v = macd.macd[i]
                val bh = (abs(v) / maxAbs).toFloat() * halfH
                if (v >= 0f) {
                    canvas.drawRect(x - bodyW / 2f, zeroY - bh, x + bodyW / 2f, zeroY, upPaint)
                } else {
                    canvas.drawRect(x - bodyW / 2f, zeroY, x + bodyW / 2f, zeroY + bh, downPaint)
                }
            }
        }

        // DIF & DEA 折线
        drawSubSeries(canvas, macd.dif, leftIdx, rightIdx, cw, -maxAbs, maxAbs * 2.0, difPaint)
        drawSubSeries(canvas, macd.dea, leftIdx, rightIdx, cw, -maxAbs, maxAbs * 2.0, deaPaint)
    }

    private fun drawSubRsi(canvas: Canvas, leftIdx: Int, rightIdx: Int, cw: Float) {
        // 30 / 70 参考线
        val y30 = volTop + volH * (1f - 0.3f)
        val y70 = volTop + volH * (1f - 0.7f)
        canvas.drawLine(paddingLeft.toFloat(), y30, paddingLeft + chartW, y30, subGridPaint)
        canvas.drawLine(paddingLeft.toFloat(), y70, paddingLeft + chartW, y70, subGridPaint)

        val labelDy = -(textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText("70", paddingLeft + chartW + 4f * density, y70 + labelDy, textPaint)
        canvas.drawText("30", paddingLeft + chartW + 4f * density, y30 + labelDy, textPaint)

        drawSubSeries(canvas, rsi1, leftIdx, rightIdx, cw, 0.0, 100.0, rsi1Paint)
        drawSubSeries(canvas, rsi2, leftIdx, rightIdx, cw, 0.0, 100.0, rsi2Paint)
        drawSubSeries(canvas, rsi3, leftIdx, rightIdx, cw, 0.0, 100.0, rsi3Paint)
    }

    private fun drawSubKdj(canvas: Canvas, leftIdx: Int, rightIdx: Int, cw: Float) {
        val kdj = kdjResult ?: return
        val y20 = volTop + volH * (1f - 0.2f)
        val y80 = volTop + volH * (1f - 0.8f)
        canvas.drawLine(paddingLeft.toFloat(), y20, paddingLeft + chartW, y20, subGridPaint)
        canvas.drawLine(paddingLeft.toFloat(), y80, paddingLeft + chartW, y80, subGridPaint)

        val labelDy = -(textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText("80", paddingLeft + chartW + 4f * density, y80 + labelDy, textPaint)
        canvas.drawText("20", paddingLeft + chartW + 4f * density, y20 + labelDy, textPaint)

        drawSubSeries(canvas, kdj.k, leftIdx, rightIdx, cw, 0.0, 100.0, kdjKPaint)
        drawSubSeries(canvas, kdj.d, leftIdx, rightIdx, cw, 0.0, 100.0, kdjDPaint)
        drawSubSeries(canvas, kdj.j, leftIdx, rightIdx, cw, 0.0, 100.0, kdjJPaint)
    }

    // ---------- 副图左上角指标参数与详细数值绘制 (TradingView 风格) ----------

    private fun drawSubIndicatorLegend(canvas: Canvas, targetIdx: Int) {
        when (subIndicatorType) {
            "MACD" -> drawSubMacdLegend(canvas, targetIdx)
            "RSI" -> drawSubRsiLegend(canvas, targetIdx)
            "KDJ" -> drawSubKdjLegend(canvas, targetIdx)
            "VOL" -> drawSubVolLegend(canvas, targetIdx)
        }
    }

    private fun drawLegendItem(
        canvas: Canvas,
        text: String,
        color: Int,
        curX: Float,
        textY: Float,
        maxRight: Float,
        spacing: Float
    ): Float {
        subLegendPaint.color = color
        val w = subLegendPaint.measureText(text)
        if (curX + w > maxRight) return curX
        canvas.drawText(text, curX, textY, subLegendPaint)
        return curX + w + spacing
    }

    private fun drawSubMacdLegend(canvas: Canvas, targetIdx: Int) {
        val macd = macdResult ?: return
        val mFast = Prefs.getMacdFast(context)
        val mSlow = Prefs.getMacdSlow(context)
        val mSig = Prefs.getMacdSignal(context)

        val difVal = macd.dif.getOrNull(targetIdx)
        val deaVal = macd.dea.getOrNull(targetIdx)
        val macdVal = macd.macd.getOrNull(targetIdx)

        val textY = volTop + 2f * density - subLegendPaint.ascent()
        var curX = paddingLeft + 4f * density
        val maxRight = paddingLeft + chartW - 4f * density
        val spacing = 7f * density

        curX = drawLegendItem(canvas, "MACD($mFast,$mSlow,$mSig)", textPaint.color, curX, textY, maxRight, spacing)
        if (difVal != null) {
            curX = drawLegendItem(canvas, "DIF: ${formatIndicatorVal(difVal)}", difPaint.color, curX, textY, maxRight, spacing)
        }
        if (deaVal != null) {
            curX = drawLegendItem(canvas, "DEA: ${formatIndicatorVal(deaVal)}", deaPaint.color, curX, textY, maxRight, spacing)
        }
        if (macdVal != null) {
            val color = if (macdVal >= 0f) upPaint.color else downPaint.color
            drawLegendItem(canvas, "MACD: ${formatIndicatorVal(macdVal)}", color, curX, textY, maxRight, spacing)
        }
    }

    private fun drawSubRsiLegend(canvas: Canvas, targetIdx: Int) {
        val r1Period = Prefs.getRsi1Period(context)
        val r2Period = Prefs.getRsi2Period(context)
        val r3Period = Prefs.getRsi3Period(context)

        val r1 = rsi1.getOrNull(targetIdx)?.takeIf { it > 0f }
        val r2 = rsi2.getOrNull(targetIdx)?.takeIf { it > 0f }
        val r3 = rsi3.getOrNull(targetIdx)?.takeIf { it > 0f }

        val textY = volTop + 2f * density - subLegendPaint.ascent()
        var curX = paddingLeft + 4f * density
        val maxRight = paddingLeft + chartW - 4f * density
        val spacing = 7f * density

        curX = drawLegendItem(canvas, "RSI($r1Period,$r2Period,$r3Period)", textPaint.color, curX, textY, maxRight, spacing)
        if (r1 != null) {
            curX = drawLegendItem(canvas, "RSI1: ${formatSubVal(r1)}", rsi1Paint.color, curX, textY, maxRight, spacing)
        }
        if (r2 != null) {
            curX = drawLegendItem(canvas, "RSI2: ${formatSubVal(r2)}", rsi2Paint.color, curX, textY, maxRight, spacing)
        }
        if (r3 != null) {
            drawLegendItem(canvas, "RSI3: ${formatSubVal(r3)}", rsi3Paint.color, curX, textY, maxRight, spacing)
        }
    }

    private fun drawSubKdjLegend(canvas: Canvas, targetIdx: Int) {
        val kdj = kdjResult ?: return
        val kN = Prefs.getKdjN(context)
        val kM1 = Prefs.getKdjM1(context)
        val kM2 = Prefs.getKdjM2(context)

        val k = kdj.k.getOrNull(targetIdx)
        val d = kdj.d.getOrNull(targetIdx)
        val j = kdj.j.getOrNull(targetIdx)

        val textY = volTop + 2f * density - subLegendPaint.ascent()
        var curX = paddingLeft + 4f * density
        val maxRight = paddingLeft + chartW - 4f * density
        val spacing = 7f * density

        curX = drawLegendItem(canvas, "KDJ($kN,$kM1,$kM2)", textPaint.color, curX, textY, maxRight, spacing)
        if (k != null) {
            curX = drawLegendItem(canvas, "K: ${formatSubVal(k)}", kdjKPaint.color, curX, textY, maxRight, spacing)
        }
        if (d != null) {
            curX = drawLegendItem(canvas, "D: ${formatSubVal(d)}", kdjDPaint.color, curX, textY, maxRight, spacing)
        }
        if (j != null) {
            drawLegendItem(canvas, "J: ${formatSubVal(j)}", kdjJPaint.color, curX, textY, maxRight, spacing)
        }
    }

    private fun drawSubVolLegend(canvas: Canvas, targetIdx: Int) {
        val c = candles.getOrNull(targetIdx) ?: return
        val textY = volTop + 2f * density - subLegendPaint.ascent()
        var curX = paddingLeft + 4f * density
        val maxRight = paddingLeft + chartW - 4f * density
        val spacing = 7f * density

        curX = drawLegendItem(canvas, "VOL", textPaint.color, curX, textY, maxRight, spacing)
        val color = if (c.close >= c.open) upPaint.color else downPaint.color
        drawLegendItem(canvas, "VOL: ${Fmt.vol(c.vol)}", color, curX, textY, maxRight, spacing)
    }

    private fun formatIndicatorVal(v: Float?): String {
        if (v == null || v.isNaN()) return "—"
        val absV = abs(v)
        return when {
            absV >= 1000f -> String.format(Locale.US, "%,.2f", v)
            absV >= 1f -> String.format(Locale.US, "%.2f", v)
            absV >= 0.01f -> String.format(Locale.US, "%.4f", v)
            absV >= 0.0001f -> String.format(Locale.US, "%.6f", v)
            absV > 0f -> String.format(Locale.US, "%.8f", v)
            else -> "0.00"
        }
    }

    private fun formatSubVal(v: Float?): String {
        if (v == null || v.isNaN()) return "—"
        return String.format(Locale.US, "%.2f", v)
    }

    private fun drawSubSeries(
        canvas: Canvas, data: FloatArray,
        leftIdx: Int, rightIdx: Int, cw: Float,
        minV: Double, range: Double, paint: Paint
    ) {
        linePath.reset()
        var started = false
        for (i in leftIdx..rightIdx) {
            if (i >= data.size) continue
            val v = data[i]
            val x = xOfIndex(i.toFloat())
            val frac = ((v - minV) / range).toFloat().coerceIn(0f, 1f)
            val y = volTop + volH * (1f - frac)
            if (started) linePath.lineTo(x, y) else { linePath.moveTo(x, y); started = true }
        }
        if (!linePath.isEmpty) canvas.drawPath(linePath, paint)
    }

    private fun drawLineSeries(
        canvas: Canvas, data: FloatArray,
        leftIdx: Int, rightIdx: Int, cw: Float,
        minP: Double, range: Double, paint: Paint
    ) {
        linePath.reset()
        var started = false
        for (i in leftIdx..rightIdx) {
            val v = if (i < data.size) data[i] else 0f
            if (v <= 0f) {
                started = false
                continue
            }
            val x = xOfIndex(i.toFloat())
            val y = yOfPrice(v.toDouble(), minP, range)
            if (started) linePath.lineTo(x, y) else { linePath.moveTo(x, y); started = true }
        }
        if (!linePath.isEmpty) canvas.drawPath(linePath, paint)
    }

    private fun drawTurtleChannel(
        canvas: Canvas, t: Indicators.TurtleResult,
        leftIdx: Int, rightIdx: Int, cw: Float,
        minP: Double, range: Double
    ) {
        // 绘制通道背景填充
        linePath.reset()
        var hasFill = false
        for (i in leftIdx..rightIdx) {
            if (i < t.upper.size && t.upper[i] > 0f && t.lower[i] > 0f) {
                val x = xOfIndex(i.toFloat())
                val yUp = yOfPrice(t.upper[i].toDouble(), minP, range)
                if (!hasFill) {
                    linePath.moveTo(x, yUp)
                    hasFill = true
                } else {
                    linePath.lineTo(x, yUp)
                }
            }
        }
        if (hasFill) {
            for (i in rightIdx downTo leftIdx) {
                if (i < t.lower.size && t.lower[i] > 0f) {
                    val x = xOfIndex(i.toFloat())
                    val yDn = yOfPrice(t.lower[i].toDouble(), minP, range)
                    linePath.lineTo(x, yDn)
                }
            }
            linePath.close()
            canvas.drawPath(linePath, turtleFillPaint)
        }

        // 绘制进场上轨（绿）、进场下轨（红）与多头离场线（黄虚线）
        drawLineSeries(canvas, t.upper, leftIdx, rightIdx, cw, minP, range, turtleUpPaint)
        drawLineSeries(canvas, t.lower, leftIdx, rightIdx, cw, minP, range, turtleDnPaint)
        drawLineSeries(canvas, t.exitLong, leftIdx, rightIdx, cw, minP, range, turtleExitPaint)
    }

    private fun drawAxisTag(canvas: Canvas, text: String, y: Float, bg: Paint, fg: Paint) {
        val half = (fg.descent() - fg.ascent()) / 2f + 2f * density
        val top = (y - half).coerceIn(paddingTop.toFloat(), paddingTop + priceH - half * 2f)
        val left = paddingLeft + chartW + 2f * density
        val w = fg.measureText(text) + 8f * density
        canvas.drawRoundRect(
            RectF(left, top, (left + w).coerceAtMost(width - paddingRight.toFloat()), top + half * 2f),
            3f * density, 3f * density, bg
        )
        canvas.drawText(text, left + 4f * density, top + half - (fg.ascent() + fg.descent()) / 2f, fg)
    }

    // ---------- 工具与指标重算 ----------

    private fun computeAllIndicators() {
        if (candles.isEmpty()) return
        // MA
        val p1 = Prefs.getMa1Period(context)
        val p2 = Prefs.getMa2Period(context)
        val p3 = Prefs.getMa3Period(context)
        ma1 = Indicators.computeMa(candles, p1)
        ma2 = Indicators.computeMa(candles, p2)
        ma3 = Indicators.computeMa(candles, p3)

        // BOLL
        val bN = Prefs.getBollN(context)
        val bK = Prefs.getBollK(context).toDouble()
        bollResult = Indicators.computeBoll(candles, bN, bK)

        // TURTLE
        val tEntry = Prefs.getTurtleEntry(context)
        val tExit = Prefs.getTurtleExit(context)
        val tAtr = Prefs.getTurtleAtr(context)
        turtleResult = Indicators.computeTurtle(candles, tEntry, tExit, tAtr)

        // MACD
        val mFast = Prefs.getMacdFast(context)
        val mSlow = Prefs.getMacdSlow(context)
        val mSig = Prefs.getMacdSignal(context)
        macdResult = Indicators.computeMacd(candles, mFast, mSlow, mSig)

        // RSI
        val r1 = Prefs.getRsi1Period(context)
        val r2 = Prefs.getRsi2Period(context)
        val r3 = Prefs.getRsi3Period(context)
        rsi1 = Indicators.computeRsi(candles, r1)
        rsi2 = Indicators.computeRsi(candles, r2)
        rsi3 = Indicators.computeRsi(candles, r3)

        // KDJ
        val kN = Prefs.getKdjN(context)
        val kM1 = Prefs.getKdjM1(context)
        val kM2 = Prefs.getKdjM2(context)
        kdjResult = Indicators.computeKdj(candles, kN, kM1, kM2)
    }

    private fun xOfIndex(i: Float): Float {
        val cw = candleWidth()
        return paddingLeft + chartW - (candles.size - 1 - i - scrollFromRight + 0.5f) * cw
    }

    private fun visibleRange(): IntRange? {
        if (candles.isEmpty() || chartW <= 0f) return null
        val cw = candleWidth()
        if (cw <= 0f) return null
        val leftFloat = candles.size - 1 - visibleCount - scrollFromRight + 0.5f
        val rightFloat = candles.size - 1 - scrollFromRight + 0.5f
        val minIdx = (kotlin.math.floor(leftFloat).toInt() - 1).coerceIn(0, candles.size - 1)
        val maxIdx = (kotlin.math.ceil(rightFloat).toInt() + 1).coerceIn(0, candles.size - 1)
        if (minIdx > maxIdx) return null
        return minIdx..maxIdx
    }

    private fun candleWidth(): Float =
        if (chartW > 0f && visibleCount > 0f) chartW / visibleCount else 0f

    private fun clampScroll() {
        val maxScroll = (candles.size - visibleCount.toInt()).coerceAtLeast(0)
        val minScroll = -(visibleCount * MAX_RIGHT_OFFSET_RATIO)
        scrollFromRight = scrollFromRight.coerceIn(minScroll, maxScroll.toFloat())
    }

    private fun yOfPrice(p: Double, minP: Double, range: Double): Float =
        paddingTop + priceH * (1f - ((p - minP) / range).toFloat())

    private fun priceAtY(y: Float, minP: Double, range: Double): Double {
        if (priceH <= 0f) return minP
        val frac = 1f - (y - paddingTop) / priceH
        return minP + range * frac.toDouble()
    }

    companion object {
        private const val MIN_VISIBLE = 20
        private const val GRID_LINES = 5
        private const val TIME_TICKS = 4
        private const val MAX_RIGHT_OFFSET_RATIO = 0.25f // 向左滑动时右侧最大留白比例（25% 图表宽度）
    }
}

