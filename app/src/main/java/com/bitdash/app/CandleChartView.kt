package com.bitdash.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.bitdash.app.market.Candle
import com.bitdash.app.market.Fmt
import com.bitdash.app.market.Palette
import com.bitdash.app.market.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 自定义 K 线图（蜡烛 + MA5/10/20 + 成交量 + 右侧价格轴 + 底部时间轴）。
 *
 * - 涨跌配色：跟随设置，默认红涨绿跌（中国惯例），可切为绿涨红跌
 * - 手势：双指缩放（改变可见 K 线数量）、单指平移、单指点按显示十字光标
 * - 兼容 Android 7.0 (API 24)，纯 Canvas 绘制无第三方依赖
 */
class CandleChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ---------- 数据 ----------
    private var candles: List<Candle> = emptyList()
    private var ma5: FloatArray = FloatArray(0)
    private var ma10: FloatArray = FloatArray(0)
    private var ma20: FloatArray = FloatArray(0)

    // ---------- 显示状态 ----------
    private var visibleCount = 90f     // 可见蜡烛数（缩放目标）
    private var scrollFromRight = 0f   // 右端距最新的偏移（0=贴最新）

    // 十字光标（-1 表示隐藏）
    private var crossIndex = -1
    private var crossY = -1f

    // 回调：光标变化时通知宿主更新行情信息栏
    var onCrosshairChange: ((Candle?) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private var fontScale: Float = Prefs.getChartFontScale(context)

    // ---------- 尺寸（px，onSizeChanged 中计算） ----------
    private var axisW = 0f       // 右侧价格轴宽
    private var axisH = 0f       // 底部时间轴高
    private var chartW = 0f      // 绘图区宽
    private var priceH = 0f      // 价格区高
    private var volTop = 0f      // 成交量区顶部
    private var volH = 0f        // 成交量区高

    // ---------- 画笔（字号一律按 density 折算，避免真机上过小） ----------
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x2E2A3142.toInt(); strokeWidth = 1f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8B93A7.toInt(); textSize = 10f * density * fontScale
    }
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8B93A7.toInt(); textSize = 10f * density * fontScale
        textAlign = Paint.Align.CENTER
    }
    private val upPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.up(context); style = Paint.Style.FILL
    }
    private val downPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.down(context); style = Paint.Style.FILL
    }
    private val upStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.up(context); style = Paint.Style.STROKE; strokeWidth = 1f * density
    }
    private val downStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.down(context); style = Paint.Style.STROKE; strokeWidth = 1f * density
    }
    private val ma5Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF59E0B.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.2f * density
    }
    private val ma10Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF60A5FA.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.2f * density
    }
    private val ma20Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFA78BFA.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.2f * density
    }
    private val lastLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF0B90B.toInt(); style = Paint.Style.STROKE; strokeWidth = 1f * density
        pathEffect = DashPathEffect(floatArrayOf(5f * density, 4f * density), 0f)
    }
    private val tagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF0B90B.toInt()
    }
    private val tagTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF0B0E14.toInt(); textSize = 10f * density * fontScale; isFakeBoldText = true
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAA8B93A7.toInt(); style = Paint.Style.STROKE; strokeWidth = 1f
        pathEffect = DashPathEffect(floatArrayOf(3f * density, 3f * density), 0f)
    }
    private val crossLabelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2A3142.toInt()
    }
    private val crossTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF1F5F9.toInt(); textSize = 10f * density * fontScale
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF5A6478.toInt(); textSize = 14f * density * fontScale; textAlign = Paint.Align.CENTER
    }

    private val maPath = Path()

    // 时间格式（按 K 线周期选择）
    var timePattern = "MM-dd HH:mm"
        set(v) { field = v; timeFmt = SimpleDateFormat(v, Locale.getDefault()); invalidate() }
    private var timeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    // ---------- 公开 API ----------

    /** 用户在设置里改了图表字体缩放后调用，更新字号并重新计算布局 */
    fun applyFontScale(scale: Float = Prefs.getChartFontScale(context)) {
        fontScale = scale
        textPaint.textSize = 10f * density * fontScale
        timePaint.textSize = 10f * density * fontScale
        tagTextPaint.textSize = 10f * density * fontScale
        crossTextPaint.textSize = 10f * density * fontScale
        emptyPaint.textSize = 14f * density * fontScale
        recomputeDimensions(width, height)
        invalidate()
    }

    /** 替换数据，保留当前缩放/平移状态 */
    fun setData(list: List<Candle>) {
        candles = list
        computeMa()
        clampScroll()
        if (crossIndex >= 0) {
            if (crossIndex < list.size) {
                onCrosshairChange?.invoke(list[crossIndex])
            } else {
                crossIndex = -1
                onCrosshairChange?.invoke(null)
            }
        }
        invalidate()
    }

    fun isEmptyData(): Boolean = candles.isEmpty()

    /** 用户在设置里改了涨跌配色后调用，重新取色并重绘 */
    fun applyPalette() {
        val up = Palette.up(context)
        val down = Palette.down(context)
        upPaint.color = up
        upStroke.color = up
        downPaint.color = down
        downStroke.color = down
        invalidate()
    }

    /** 切换周期时调用：回到最新一根并复位光标 */
    fun resetView() {
        scrollFromRight = 0f
        crossIndex = -1
        crossY = -1f
        onCrosshairChange?.invoke(null)
        invalidate()
    }

    // ---------- 手势 ----------

    private var lastX = 0f
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
                // 因子 > 1 → 放大（更少可见）；< 1 → 缩小（更多可见）
                visibleCount = (visibleCount / detector.scaleFactor)
                    .coerceIn(MIN_VISIBLE.toFloat(), candles.size.coerceAtLeast(MIN_VISIBLE).toFloat())
                // 围绕焦点缩放：让焦点处的蜡烛尽量保持不动
                val focusRatio = ((detector.focusX - paddingLeft) / chartW).coerceIn(0f, 1f)
                // 焦点距右端的蜡烛数在缩放前后保持一致
                scrollFromRight += (old - visibleCount) * (1f - focusRatio)
                clampScroll()
                invalidate()
                return true
            }
        })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastX = event.x
                downX = event.x
                downY = event.y
                scaling = false
                dragging = false
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                scaling = true
                hideCrosshair()
            }
            MotionEvent.ACTION_MOVE -> {
                if (scaleDetector.isInProgress) {
                    scaling = true
                } else if (!scaling) {
                    if (!dragging &&
                        Math.abs(event.x - downX) > touchSlop &&
                        Math.abs(event.x - downX) > Math.abs(event.y - downY)
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
            MotionEvent.ACTION_UP -> {
                // 只有"没有缩放、没有拖动"的点按才切换十字光标
                if (!scaling && !dragging) {
                    if (crossIndex < 0) {
                        updateCrosshair(event.x, event.y)
                    } else {
                        hideCrosshair()
                        invalidate()
                    }
                }
                scaling = false
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> {
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
            onCrosshairChange?.invoke(null)
        }
    }

    private fun updateCrosshair(x: Float, y: Float) {
        val range = visibleRange() ?: return
        val n = range.last - range.first + 1
        val cw = chartW / n
        if (cw <= 0f) return
        val idx = range.first + ((x - paddingLeft) / cw).toInt()
        crossIndex = idx.coerceIn(range.first, range.last)
        crossY = y.coerceIn(paddingTop.toFloat(), paddingTop + priceH)
        onCrosshairChange?.invoke(candles.getOrNull(crossIndex))
        invalidate()
    }

    // ---------- 布局与绘制 ----------

    private fun recomputeDimensions(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        axisW = 58f * density * fontScale
        axisH = 16f * density * fontScale
        chartW = (w - paddingLeft - paddingRight - axisW).coerceAtLeast(10f)
        val body = (h - paddingTop - paddingBottom - axisH).coerceAtLeast(10f)
        priceH = body * 0.76f
        volH = body * 0.24f
        volTop = paddingTop + priceH
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeDimensions(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val range = visibleRange()
        if (range == null) {
            canvas.drawText(
                "暂无数据", width / 2f,
                height / 2f - (emptyPaint.ascent() + emptyPaint.descent()) / 2f, emptyPaint
            )
            return
        }

        val leftIdx = range.first
        val rightIdx = range.last
        val n = rightIdx - leftIdx + 1
        val sub = candles.subList(leftIdx, rightIdx + 1)

        // ---- 价格范围（含已定义的 MA 值）----
        var minP = Double.MAX_VALUE
        var maxP = -Double.MAX_VALUE
        for (c in sub) {
            if (c.low < minP) minP = c.low
            if (c.high > maxP) maxP = c.high
        }
        for (i in leftIdx..rightIdx) {
            for (arr in arrayOf(ma5, ma10, ma20)) {
                val v = if (i < arr.size) arr[i].toDouble() else 0.0
                // MA 未定义区填的是 0，必须排除，否则价格轴会被拉到 0
                if (v > 0.0) {
                    if (v < minP) minP = v
                    if (v > maxP) maxP = v
                }
            }
        }
        if (minP == Double.MAX_VALUE) { minP = 0.0; maxP = 1.0 }
        if (maxP <= minP) maxP = minP + Math.max(Math.abs(minP) * 1e-4, 1e-8)
        val padP = (maxP - minP) * 0.08
        minP -= padP
        maxP += padP
        val priceRange = (maxP - minP).coerceAtLeast(1e-12)

        // ---- 成交量范围 ----
        var maxV = 0.0
        for (c in sub) if (c.vol > maxV) maxV = c.vol
        if (maxV <= 0.0) maxV = 1.0

        // ---- 分隔线（价格区 / 成交量区）----
        canvas.drawLine(paddingLeft.toFloat(), volTop, paddingLeft + chartW, volTop, gridPaint)

        // ---- 网格 + 价格刻度（右轴）----
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

        val cw = chartW / n
        val bodyW = (cw * 0.7f).coerceAtLeast(1f)

        // ---- 成交量柱 ----
        for (i in sub.indices) {
            val c = sub[i]
            val x = paddingLeft + cw * i + cw / 2f
            val up = c.close >= c.open
            val hFrac = (c.vol / maxV).toFloat().coerceIn(0f, 1f)
            val vh = volH * 0.92f * hFrac
            canvas.drawRect(
                x - bodyW / 2f, volTop + volH - vh, x + bodyW / 2f, volTop + volH,
                if (up) upPaint else downPaint
            )
        }

        // ---- 蜡烛 ----
        for (i in sub.indices) {
            val c = sub[i]
            val x = paddingLeft + cw * i + cw / 2f
            val up = c.close >= c.open

            // 影线
            canvas.drawLine(
                x, yOfPrice(c.high, minP, priceRange), x, yOfPrice(c.low, minP, priceRange),
                if (up) upStroke else downStroke
            )

            // 实体（等价开收时至少留 1px 高，保证可见）
            val yOpen = yOfPrice(c.open, minP, priceRange)
            val yClose = yOfPrice(c.close, minP, priceRange)
            val top = Math.min(yOpen, yClose)
            val bot = Math.max(yOpen, yClose).coerceAtLeast(top + 1f)
            canvas.drawRect(
                x - bodyW / 2f, top, x + bodyW / 2f, bot, if (up) upPaint else downPaint
            )
        }

        // ---- MA 折线 ----
        drawMa(canvas, ma5, leftIdx, rightIdx, cw, minP, priceRange, ma5Paint)
        drawMa(canvas, ma10, leftIdx, rightIdx, cw, minP, priceRange, ma10Paint)
        drawMa(canvas, ma20, leftIdx, rightIdx, cw, minP, priceRange, ma20Paint)

        // ---- 最新价虚线 + 右轴标签 ----
        val lastClose = candles[candles.size - 1].close
        val yLast = yOfPrice(lastClose, minP, priceRange)
        if (yLast >= paddingTop && yLast <= paddingTop + priceH) {
            canvas.drawLine(paddingLeft.toFloat(), yLast, paddingLeft + chartW, yLast, lastLinePaint)
            drawAxisTag(canvas, Fmt.price(lastClose), yLast, tagPaint, tagTextPaint)
        }

        // ---- 时间轴 ----
        val timeDy = paddingTop + priceH + volH + axisH - 3f * density
        for (t in 0 until TIME_TICKS) {
            val i = if (TIME_TICKS == 1) 0 else (n - 1) * t / (TIME_TICKS - 1)
            val cx = (paddingLeft + cw * i + cw / 2f)
                .coerceIn(paddingLeft + 24f * density, paddingLeft + chartW - 24f * density)
            canvas.drawText(timeFmt.format(Date(sub[i].ts)), cx, timeDy, timePaint)
        }

        // ---- 十字光标 ----
        if (crossIndex in leftIdx..rightIdx && crossY >= 0f) {
            val x = paddingLeft + cw * (crossIndex - leftIdx) + cw / 2f
            canvas.drawLine(x, paddingTop.toFloat(), x, volTop + volH, crossPaint)
            canvas.drawLine(paddingLeft.toFloat(), crossY, paddingLeft + chartW, crossY, crossPaint)
            drawAxisTag(
                canvas, Fmt.price(priceAtY(crossY, minP, priceRange)), crossY,
                crossLabelBg, crossTextPaint
            )
        }
    }

    /** 在右侧价格轴上绘制一枚价格标签 */
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

    private fun drawMa(
        canvas: Canvas, ma: FloatArray,
        leftIdx: Int, rightIdx: Int, cw: Float,
        minP: Double, range: Double, paint: Paint
    ) {
        maPath.reset()
        var started = false
        for (i in leftIdx..rightIdx) {
            val v = if (i < ma.size) ma[i] else 0f
            if (v <= 0f) {
                // 未定义区：断开折线
                started = false
                continue
            }
            val x = paddingLeft + cw * (i - leftIdx) + cw / 2f
            val y = yOfPrice(v.toDouble(), minP, range)
            if (started) maPath.lineTo(x, y) else { maPath.moveTo(x, y); started = true }
        }
        if (!maPath.isEmpty) canvas.drawPath(maPath, paint)
    }

    // ---------- 工具 ----------

    /** 当前可见的蜡烛下标闭区间；无数据或未布局时返回 null */
    private fun visibleRange(): IntRange? {
        if (candles.isEmpty() || chartW <= 0f) return null
        val n = visibleCount.toInt().coerceIn(1, candles.size)
        val right = (candles.size - 1 - scrollFromRight.toInt())
            .coerceIn(n - 1, candles.size - 1)
        return (right - n + 1)..right
    }

    private fun candleWidth(): Float =
        if (chartW > 0f && visibleCount > 0f) chartW / visibleCount else 0f

    private fun clampScroll() {
        val maxScroll = (candles.size - visibleCount.toInt()).coerceAtLeast(0)
        scrollFromRight = scrollFromRight.coerceIn(0f, maxScroll.toFloat())
    }

    private fun yOfPrice(p: Double, minP: Double, range: Double): Float =
        paddingTop + priceH * (1f - ((p - minP) / range).toFloat())

    private fun priceAtY(y: Float, minP: Double, range: Double): Double {
        if (priceH <= 0f) return minP
        val frac = 1f - (y - paddingTop) / priceH
        return minP + range * frac.toDouble()
    }

    /**
     * 滑动窗口计算 MA5/10/20。
     * 未定义区（数据不足 period 根）保持 0，绘制与价格轴计算时按 0 过滤。
     */
    private fun computeMa() {
        val n = candles.size
        ma5 = FloatArray(n); ma10 = FloatArray(n); ma20 = FloatArray(n)
        var s5 = 0.0; var s10 = 0.0; var s20 = 0.0
        for (i in 0 until n) {
            val c = candles[i].close
            s5 += c; s10 += c; s20 += c
            if (i >= 5) s5 -= candles[i - 5].close
            if (i >= 10) s10 -= candles[i - 10].close
            if (i >= 20) s20 -= candles[i - 20].close
            if (i >= 4) ma5[i] = (s5 / 5.0).toFloat()
            if (i >= 9) ma10[i] = (s10 / 10.0).toFloat()
            if (i >= 19) ma20[i] = (s20 / 20.0).toFloat()
        }
    }

    companion object {
        private const val MIN_VISIBLE = 20
        private const val GRID_LINES = 5
        private const val TIME_TICKS = 4
    }
}
