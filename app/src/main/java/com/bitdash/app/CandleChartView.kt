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
        val ema1: Float? = null,
        val ema2: Float? = null,
        val ema3: Float? = null,
        val bollMid: Float? = null,
        val bollUp: Float? = null,
        val bollDn: Float? = null,
        val superTrend: Float? = null,
        val turtleUp: Float? = null,
        val turtleDn: Float? = null,
        val turtleExitLong: Float? = null,
        val turtleExitShort: Float? = null,
        val turtleAtr: Float? = null,
        val threeKEma: Float? = null,
        val threeKUpper: Float? = null,
        val threeKLower: Float? = null,
        val threeKStop: Float? = null,
        val threeKPos: Int? = null,
        val bollBreakMid: Float? = null,
        val bollBreakTop: Float? = null,
        val bollBreakBot: Float? = null,
        val bollBreakMa60: Float? = null,
        val bollBreakPos: Int? = null,
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
    private var ema1: FloatArray = FloatArray(0)
    private var ema2: FloatArray = FloatArray(0)
    private var ema3: FloatArray = FloatArray(0)
    private var bollResult: Indicators.BollResult? = null
    private var superTrendResult: Indicators.SuperTrendResult? = null
    private var turtleResult: Indicators.TurtleResult? = null
    private var threeKResult: Indicators.ThreeKResult? = null
    private var bollBreakResult: Indicators.BollBreakResult? = null
    private var lorentzianResult: Indicators.LorentzianResult? = null
    private var customPineResults: List<com.bitdash.app.market.PineExecutionResult> = emptyList()

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
    var showEma1 = Prefs.getShowEma1(context)
    var showEma2 = Prefs.getShowEma2(context)
    var showEma3 = Prefs.getShowEma3(context)
    var showBoll = Prefs.getShowBoll(context)
    var showSuperTrend = Prefs.getShowSuperTrend(context)
    var showTurtle = Prefs.getShowTurtle(context)
    var showThreeK = Prefs.getShowThreeK(context)
    var showBollBreak = Prefs.getShowBollBreak(context)
    var showLorentzian = Prefs.getShowLorentzian(context)
    var showFibonacci = Prefs.getShowFibonacci(context)
    var showHighLow = Prefs.getShowHighLow(context)
    var subIndicatorType = Prefs.getSubIndicator(context) // "VOL", "MACD", "RSI", "KDJ", "OFF"

    // ---------- 区间测算尺工具 (Ruler) ----------
    var isRulerMode = false
        private set
    var rulerStartIdx = -1
    var rulerStartPrice = 0.0
    var rulerEndIdx = -1
    var rulerEndPrice = 0.0

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
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val subGridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        pathEffect = DashPathEffect(floatArrayOf(3f * density, 3f * density), 0f)
    }
    private val subRefLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        pathEffect = DashPathEffect(floatArrayOf(4f * density, 3f * density), 0f)
    }
    private val subMidLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.8f * density
        pathEffect = DashPathEffect(floatArrayOf(2f * density, 2.5f * density), 0f)
    }
    private val subZoneBandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
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
    private val ema1Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.2f * density
    }
    private val ema2Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.2f * density
    }
    private val ema3Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.2f * density
    }
    private val bollUpPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.2f * density
    }
    private val bollDnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.2f * density
    }
    private val superTrendUpPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.8f * density
    }
    private val superTrendDnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.8f * density
    }
    private val superTrendFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val fibLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f * density
        pathEffect = DashPathEffect(floatArrayOf(4f * density, 3f * density), 0f)
    }
    private val fibTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFakeBoldText = true
    }
    private val fibFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val highLowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f * density
    }
    private val highLowTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFakeBoldText = true
    }
    private val rulerBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val rulerBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.2f * density
        pathEffect = DashPathEffect(floatArrayOf(4f * density, 3f * density), 0f)
    }
    private val rulerLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * density
    }
    private val rulerCardBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val rulerCardBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f * density
    }
    private val rulerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFakeBoldText = true
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
    private val threeKEmaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.4f * density
    }
    private val threeKUpperPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f * density
        pathEffect = DashPathEffect(floatArrayOf(3.5f * density, 3f * density), 0f)
    }
    private val threeKLowerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f * density
        pathEffect = DashPathEffect(floatArrayOf(3.5f * density, 3f * density), 0f)
    }
    private val threeKStopLongPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.4f * density
        pathEffect = DashPathEffect(floatArrayOf(4f * density, 3f * density), 0f)
    }
    private val threeKStopShortPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.4f * density
        pathEffect = DashPathEffect(floatArrayOf(4f * density, 3f * density), 0f)
    }
    private val threeKSignalBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val threeKSignalTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
        color = 0xFFFFFFFF.toInt()
    }
    private val bollBreakMidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.6f * density
    }
    private val bollBreakTopPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.1f * density
    }
    private val bollBreakBotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.1f * density
    }
    private val bollBreakMa60Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.1f * density
    }
    private val bollBreakFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val lorentzKernelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.8f * density
    }
    private val lorentzStopPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.2f * density
        pathEffect = DashPathEffect(floatArrayOf(4f * density, 3f * density), 0f)
    }
    private val customPineLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val customPineFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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
    private val crossPercentBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val crossPercentBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val crossPercentText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFakeBoldText = true
    }
    private val subLegendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFakeBoldText = true
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    private val linePath = Path()
    private var currentMinPrice = 0.0
    private var currentPriceRange = 1.0

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
        subGridPaint.color = (border and 0x00FFFFFF) or 0x50000000
        subRefLinePaint.color = if (isNight) 0x668B93A7.toInt() else 0x6664748B.toInt()
        subMidLinePaint.color = if (isNight) 0x338B93A7.toInt() else 0x3364748B.toInt()
        subZoneBandPaint.color = if (isNight) 0x0C8B93A7.toInt() else 0x0A1E293B.toInt()
        textPaint.color = textMuted
        timePaint.color = textMuted
        upPaint.color = Palette.up(context)
        downPaint.color = Palette.down(context)
        upStroke.color = Palette.up(context)
        downStroke.color = Palette.down(context)

        ma1Paint.color = ContextCompat.getColor(context, R.color.ma5)
        ma2Paint.color = ContextCompat.getColor(context, R.color.ma10)
        ma3Paint.color = ContextCompat.getColor(context, R.color.ma20)

        // EMA 曲线颜色：青色、紫色、橙色
        ema1Paint.color = 0xFF06B6D4.toInt()
        ema2Paint.color = 0xFFA855F7.toInt()
        ema3Paint.color = 0xFFF97316.toInt()

        bollUpPaint.color = ContextCompat.getColor(context, R.color.boll_up)
        bollDnPaint.color = ContextCompat.getColor(context, R.color.boll_dn)

        superTrendUpPaint.color = Palette.up(context)
        superTrendDnPaint.color = Palette.down(context)
        superTrendFillPaint.color = if (isNight) 0x1410B981.toInt() else 0x1010B981.toInt()

        fibLinePaint.color = if (isNight) 0x8894A3B8.toInt() else 0x8864748B.toInt()
        fibTextPaint.color = if (isNight) 0xFFCBD5E1.toInt() else 0xFF475569.toInt()
        fibFillPaint.color = if (isNight) 0x18F59E0B.toInt() else 0x1AEB7E03.toInt()

        highLowPaint.color = textMuted
        highLowTextPaint.color = textMuted

        rulerCardBgPaint.color = if (isNight) 0xF21E293B.toInt() else 0xF8FFFFFF.toInt()
        rulerCardBorderPaint.color = if (isNight) 0x6664748B.toInt() else 0x66CBD5E1.toInt()
        rulerTextPaint.color = textMain

        turtleUpPaint.color = ContextCompat.getColor(context, R.color.turtle_up)
        turtleDnPaint.color = ContextCompat.getColor(context, R.color.turtle_dn)
        turtleExitPaint.color = ContextCompat.getColor(context, R.color.turtle_exit)
        turtleFillPaint.color = ContextCompat.getColor(context, R.color.turtle_fill)

        // 3K 策略画笔颜色
        threeKEmaPaint.color = 0xFFF59E0B.toInt() // 琥珀金色趋势中枢
        threeKUpperPaint.color = if (isNight) 0x9934D399.toInt() else 0x99059669.toInt()
        threeKLowerPaint.color = if (isNight) 0x99F87171.toInt() else 0x99DC2626.toInt()
        threeKStopLongPaint.color = 0xFF10B981.toInt()
        threeKStopShortPaint.color = 0xFFEF4444.toInt()

        // 布林突破策略画笔颜色 (布林线突破+MA60过滤)
        bollBreakMidPaint.color = 0xFFF59E0B.toInt() // 橙色中轨
        bollBreakTopPaint.color = 0xFFEF4444.toInt() // 红色上轨
        bollBreakBotPaint.color = 0xFF10B981.toInt() // 绿色下轨
        bollBreakMa60Paint.color = 0xFF06B6D4.toInt() // 青色 MA60 趋势过滤均线
        bollBreakFillPaint.color = if (isNight) 0x1294A3B8.toInt() else 0x1064748B.toInt()

        // 洛伦兹分类 (Machine Learning: Lorentzian Classification)
        lorentzKernelPaint.color = 0xFF06B6D4.toInt() // 青色 NW 核回归平滑中枢线
        lorentzStopPaint.color = 0xFFEC4899.toInt()   // 粉红色 ATR 追踪止损线

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
        crossPercentBg.color = if (isNight) 0xF01E293B.toInt() else 0xF2FFFFFF.toInt()
        crossPercentBorder.strokeWidth = 1f * density

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
        crossPercentText.textSize = 10f * density * fontScale
        fibTextPaint.textSize = 9f * density * fontScale
        highLowTextPaint.textSize = 9.5f * density * fontScale
        rulerTextPaint.textSize = 10.5f * density * fontScale
        threeKSignalTextPaint.textSize = 8.5f * density * fontScale
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
        showEma1 = Prefs.getShowEma1(context)
        showEma2 = Prefs.getShowEma2(context)
        showEma3 = Prefs.getShowEma3(context)
        showBoll = Prefs.getShowBoll(context)
        showSuperTrend = Prefs.getShowSuperTrend(context)
        showTurtle = Prefs.getShowTurtle(context)
        showThreeK = Prefs.getShowThreeK(context)
        showBollBreak = Prefs.getShowBollBreak(context)
        showLorentzian = Prefs.getShowLorentzian(context)
        showFibonacci = Prefs.getShowFibonacci(context)
        showHighLow = Prefs.getShowHighLow(context)
        subIndicatorType = Prefs.getSubIndicator(context)
        computeAllIndicators()
        recomputeDimensions(width, height)
        notifyCurrentIndicatorValues()
        invalidate()
    }

    fun getCandles(): List<Candle> = candles

    /** 替换数据，保留当前缩放/平移状态 */
    fun setData(list: List<Candle>) {
        candles = list
        computeAllIndicators()
        recomputeDimensions(width, height)
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
        if (isRulerMode) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    updateRulerPoint(event.x, event.y, isStart = true)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    updateRulerPoint(event.x, event.y, isStart = false)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    return true
                }
            }
            return true
        }

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

    private fun updateRulerPoint(x: Float, y: Float, isStart: Boolean) {
        if (candles.isEmpty() || chartW <= 0f) return
        val cw = candleWidth()
        if (cw <= 0f) return
        val range = visibleRange() ?: return
        val idxFloat = (candles.size - 1 - scrollFromRight + 0.5f) - (paddingLeft + chartW - x) / cw
        val idx = kotlin.math.round(idxFloat).toInt().coerceIn(range.first, range.last)
        val p = priceAtY(y.coerceIn(paddingTop.toFloat(), paddingTop + priceH), currentMinPrice, currentPriceRange)
        if (isStart) {
            rulerStartIdx = idx
            rulerStartPrice = p
            rulerEndIdx = idx
            rulerEndPrice = p
        } else {
            rulerEndIdx = idx
            rulerEndPrice = p
        }
        invalidate()
    }

    fun toggleRulerMode(enabled: Boolean? = null) {
        isRulerMode = enabled ?: !isRulerMode
        if (!isRulerMode) {
            clearRuler()
        }
        invalidate()
    }

    fun clearRuler() {
        rulerStartIdx = -1
        rulerEndIdx = -1
        rulerStartPrice = 0.0
        rulerEndPrice = 0.0
        invalidate()
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

        val e1 = if (targetIdx < ema1.size && ema1[targetIdx] > 0f) ema1[targetIdx] else null
        val e2 = if (targetIdx < ema2.size && ema2[targetIdx] > 0f) ema2[targetIdx] else null
        val e3 = if (targetIdx < ema3.size && ema3[targetIdx] > 0f) ema3[targetIdx] else null

        val bMid = bollResult?.mid?.getOrNull(targetIdx)?.takeIf { it > 0f }
        val bUp = bollResult?.up?.getOrNull(targetIdx)?.takeIf { it > 0f }
        val bDn = bollResult?.dn?.getOrNull(targetIdx)?.takeIf { it > 0f }

        val stVal = superTrendResult?.value?.getOrNull(targetIdx)?.takeIf { it > 0f }

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

        val tkEma = threeKResult?.emaTrend?.getOrNull(targetIdx)?.takeIf { it > 0f }
        val tkUp = threeKResult?.upperDonch?.getOrNull(targetIdx)?.takeIf { it > 0f }
        val tkDn = threeKResult?.lowerDonch?.getOrNull(targetIdx)?.takeIf { it > 0f }
        val tkStop = threeKResult?.trailStop?.getOrNull(targetIdx)?.takeIf { it > 0f }
        val tkPos = threeKResult?.positions?.getOrNull(targetIdx)

        val values = IndicatorValues(
            ma1 = m1, ma2 = m2, ma3 = m3,
            ema1 = e1, ema2 = e2, ema3 = e3,
            bollMid = bMid, bollUp = bUp, bollDn = bDn,
            superTrend = stVal,
            turtleUp = tUp, turtleDn = tDn, turtleExitLong = tExL, turtleExitShort = tExS, turtleAtr = tAtr,
            threeKEma = tkEma, threeKUpper = tkUp, threeKLower = tkDn, threeKStop = tkStop, threeKPos = tkPos,
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
        val hasSubCustom = customPineResults.any { !it.overlay }
        if (subIndicatorType == "OFF" && !hasSubCustom) {
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

        // MA / EMA / BOLL / TURTLE / SUPERTREND 纳入价格轴
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
            if (showEma1 && i < ema1.size && ema1[i] > 0f) {
                if (ema1[i] < minP) minP = ema1[i].toDouble()
                if (ema1[i] > maxP) maxP = ema1[i].toDouble()
            }
            if (showEma2 && i < ema2.size && ema2[i] > 0f) {
                if (ema2[i] < minP) minP = ema2[i].toDouble()
                if (ema2[i] > maxP) maxP = ema2[i].toDouble()
            }
            if (showEma3 && i < ema3.size && ema3[i] > 0f) {
                if (ema3[i] < minP) minP = ema3[i].toDouble()
                if (ema3[i] > maxP) maxP = ema3[i].toDouble()
            }
            if (showBoll) {
                bollResult?.let { boll ->
                    if (i < boll.up.size && boll.up[i] > 0f) {
                        if (boll.up[i] > maxP) maxP = boll.up[i].toDouble()
                        if (boll.dn[i] < minP && boll.dn[i] > 0f) minP = boll.dn[i].toDouble()
                    }
                }
            }
            if (showSuperTrend) {
                superTrendResult?.let { st ->
                    if (i < st.value.size && st.value[i] > 0f) {
                        if (st.value[i] < minP) minP = st.value[i].toDouble()
                        if (st.value[i] > maxP) maxP = st.value[i].toDouble()
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
            if (showThreeK) {
                threeKResult?.let { tk ->
                    if (i < tk.upperDonch.size && tk.upperDonch[i] > 0f && tk.upperDonch[i] > maxP) maxP = tk.upperDonch[i].toDouble()
                    if (i < tk.lowerDonch.size && tk.lowerDonch[i] > 0f && tk.lowerDonch[i] < minP) minP = tk.lowerDonch[i].toDouble()
                    if (i < tk.emaTrend.size && tk.emaTrend[i] > 0f) {
                        if (tk.emaTrend[i] > maxP) maxP = tk.emaTrend[i].toDouble()
                        if (tk.emaTrend[i] < minP) minP = tk.emaTrend[i].toDouble()
                    }
                    if (i < tk.trailStop.size && tk.trailStop[i] > 0f) {
                        if (tk.trailStop[i] > maxP) maxP = tk.trailStop[i].toDouble()
                        if (tk.trailStop[i] < minP) minP = tk.trailStop[i].toDouble()
                    }
                }
            }
            if (showBollBreak) {
                bollBreakResult?.let { bb ->
                    if (i < bb.top.size && bb.top[i] > 0f && bb.top[i] > maxP) maxP = bb.top[i].toDouble()
                    if (i < bb.bottom.size && bb.bottom[i] > 0f && bb.bottom[i] < minP) minP = bb.bottom[i].toDouble()
                    if (i < bb.maFilter.size && bb.maFilter[i] > 0f) {
                        if (bb.maFilter[i] > maxP) maxP = bb.maFilter[i].toDouble()
                        if (bb.maFilter[i] < minP) minP = bb.maFilter[i].toDouble()
                    }
                }
            }
            if (showLorentzian) {
                lorentzianResult?.let { lc ->
                    if (i < lc.kernelEstimate.size && lc.kernelEstimate[i] > 0f) {
                        if (lc.kernelEstimate[i] > maxP) maxP = lc.kernelEstimate[i].toDouble()
                        if (lc.kernelEstimate[i] < minP) minP = lc.kernelEstimate[i].toDouble()
                    }
                    if (i < lc.stopLoss.size && lc.stopLoss[i] > 0f) {
                        if (lc.stopLoss[i] > maxP) maxP = lc.stopLoss[i].toDouble()
                        if (lc.stopLoss[i] < minP) minP = lc.stopLoss[i].toDouble()
                    }
                }
            }
            val avgClose = if (sub.isNotEmpty()) sub[0].close else 0.0
            for (res in customPineResults) {
                if (res.overlay) {
                    for (line in res.plotLines) {
                        if (i < line.series.size) {
                            val v = line.series[i].toDouble()
                            // 仅当数值在合理价格尺度内（与K线价格同量级）才纳入主图坐标轴范围
                            if (v > 0.0 && (avgClose <= 0.0 || (v >= avgClose * 0.15 && v <= avgClose * 6.0))) {
                                if (v > maxP) maxP = v
                                if (v < minP) minP = v
                            }
                        }
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

        currentMinPrice = minP
        currentPriceRange = priceRange

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

        // ---- 2.5 绘制斐波那契自动回调线 ----
        if (showFibonacci) {
            drawFibonacciRetracement(canvas, leftIdx, rightIdx, minP, priceRange)
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

        // ---- 3.5 绘制可视区域最高价与最低价极值标签 ----
        if (showHighLow) {
            drawHighLowMarkers(canvas, leftIdx, rightIdx, minP, priceRange)
        }

        // ---- 4. 绘制主图 MA 折线 ----
        if (showMa1) drawLineSeries(canvas, ma1, leftIdx, rightIdx, cw, minP, priceRange, ma1Paint)
        if (showMa2) drawLineSeries(canvas, ma2, leftIdx, rightIdx, cw, minP, priceRange, ma2Paint)
        if (showMa3) drawLineSeries(canvas, ma3, leftIdx, rightIdx, cw, minP, priceRange, ma3Paint)

        // ---- 4.5 绘制主图 EMA 折线 ----
        if (showEma1) drawLineSeries(canvas, ema1, leftIdx, rightIdx, cw, minP, priceRange, ema1Paint)
        if (showEma2) drawLineSeries(canvas, ema2, leftIdx, rightIdx, cw, minP, priceRange, ema2Paint)
        if (showEma3) drawLineSeries(canvas, ema3, leftIdx, rightIdx, cw, minP, priceRange, ema3Paint)

        // ---- 5. 绘制主图 BOLL 轨道线 ----
        if (showBoll) {
            bollResult?.let { boll ->
                drawLineSeries(canvas, boll.up, leftIdx, rightIdx, cw, minP, priceRange, bollUpPaint)
                drawLineSeries(canvas, boll.mid, leftIdx, rightIdx, cw, minP, priceRange, ma1Paint)
                drawLineSeries(canvas, boll.dn, leftIdx, rightIdx, cw, minP, priceRange, bollDnPaint)
            }
        }

        // ---- 5.1 绘制主图 SuperTrend 超级趋势 ----
        if (showSuperTrend) {
            drawSuperTrend(canvas, leftIdx, rightIdx, cw, minP, priceRange)
        }

        // ---- 5.2 绘制主图 TURTLE 海龟通道 ----
        if (showTurtle) {
            turtleResult?.let { t ->
                drawTurtleChannel(canvas, t, leftIdx, rightIdx, cw, minP, priceRange)
            }
        }

        // ---- 5.3 绘制 3K 趋势突破策略 (BTC 1D Trend Alpha) ----
        if (showThreeK) {
            threeKResult?.let { tk ->
                drawThreeKStrategy(canvas, tk, leftIdx, rightIdx, cw, minP, priceRange)
            }
        }

        // ---- 5.4 绘制布林突破策略 (布林线突破 + MA60 过滤) ----
        if (showBollBreak) {
            bollBreakResult?.let { bb ->
                drawBollBreakStrategy(canvas, bb, leftIdx, rightIdx, cw, minP, priceRange)
            }
        }

        // ---- 5.5 绘制洛伦兹分类机器学习策略 (Lorentzian Classification) ----
        if (showLorentzian) {
            lorentzianResult?.let { lc ->
                drawLorentzianStrategy(canvas, lc, leftIdx, rightIdx, cw, minP, priceRange)
            }
        }

        // ---- 5.6 绘制用户自定义 Pine Script v5 脚本 ----
        if (customPineResults.isNotEmpty()) {
            drawCustomPineMain(canvas, leftIdx, rightIdx, cw, minP, priceRange)
        }

        // 绘制主图指标状态栏（如 3K、布林突破、洛伦兹分类、自定义 Pine 指标）
        val targetMainIdx = if (crossIndex in leftIdx..rightIdx) crossIndex else (candles.size - 1)
        if (targetMainIdx in candles.indices && (showThreeK || showBollBreak || showLorentzian || customPineResults.any { it.overlay })) {
            drawMainIndicatorLegend(canvas, targetMainIdx)
        }

        // ---- 5.5 绘制区间测算尺 (Ruler) ----
        if (rulerStartIdx >= 0 && rulerEndIdx >= 0) {
            drawRulerOverlay(canvas, minP, priceRange)
        }

        // ---- 6. 绘制副图指标 ----
        val hasSubCustom = customPineResults.any { !it.overlay }
        if ((subIndicatorType != "OFF" || hasSubCustom) && volH > 0f) {
            // 分隔线
            canvas.drawLine(paddingLeft.toFloat(), volTop, paddingLeft + chartW, volTop, gridPaint)
            when (subIndicatorType) {
                "VOL" -> drawSubVol(canvas, leftIdx, rightIdx, cw, bodyW)
                "MACD" -> drawSubMacd(canvas, leftIdx, rightIdx, cw, bodyW)
                "RSI" -> drawSubRsi(canvas, leftIdx, rightIdx, cw)
                "KDJ" -> drawSubKdj(canvas, leftIdx, rightIdx, cw)
            }
            if (hasSubCustom) {
                drawCustomPineSub(canvas, leftIdx, rightIdx, cw)
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

            val focusPrice = priceAtY(crossY, minP, priceRange)
            val realTimePrice = candles[candles.size - 1].close
            if (realTimePrice > 0.0) {
                val pctDiff = ((focusPrice - realTimePrice) / realTimePrice) * 100.0
                drawCrosshairPercentBadge(canvas, pctDiff, x, crossY)
            }

            // 右侧价格轴高亮标签
            drawAxisTag(
                canvas, Fmt.price(focusPrice), crossY,
                crossLabelBg, crossTextPaint
            )

            // 底部时间轴高亮标签
            val timeStr = timeFmt.format(Date(candles[crossIndex].ts))
            drawTimeAxisTag(canvas, timeStr, x)
        }
    }

    private fun drawCrosshairPercentBadge(canvas: Canvas, pct: Double, x: Float, y: Float) {
        val isUp = pct >= 0.005
        val isDown = pct <= -0.005
        val text = when {
            isUp -> String.format(Locale.US, "+%.2f%%", pct)
            isDown -> String.format(Locale.US, "%.2f%%", pct)
            else -> "0.00%"
        }
        val color = when {
            isUp -> upPaint.color
            isDown -> downPaint.color
            else -> textPaint.color
        }
        crossPercentText.color = color
        crossPercentBorder.color = (color and 0x00FFFFFF) or 0x66000000

        val hPad = 6f * density
        val vPad = 3f * density
        val textW = crossPercentText.measureText(text)
        val textH = crossPercentText.descent() - crossPercentText.ascent()
        val badgeW = textW + hPad * 2f
        val badgeH = textH + vPad * 2f

        // 充足的手指避让距离（防止大拇指或食指按压时遮挡徽标）
        val fingerClearanceX = 22f * density
        val fingerClearanceY = 24f * density

        // 默认放置在十字焦点右上方，明显悬浮于手指上方
        var left = x + fingerClearanceX
        var top = y - badgeH - fingerClearanceY

        // 右侧边界避让：若超出图表右侧，翻转至焦点左侧
        if (left + badgeW > paddingLeft + chartW - 4f * density) {
            left = x - badgeW - fingerClearanceX
        }
        // 左侧边界保护
        if (left < paddingLeft + 4f * density) {
            left = paddingLeft + 4f * density
        }

        // 顶部边界避让：若超出图表顶部，翻转至焦点下方手指触摸区域以外
        if (top < paddingTop + 4f * density) {
            top = y + fingerClearanceY
        }
        // 底部边界保护（不超过主图区域）
        if (top + badgeH > paddingTop + priceH - 4f * density) {
            top = (paddingTop + priceH - badgeH - 4f * density).coerceAtLeast(paddingTop + 4f * density)
        }

        val radius = 4f * density
        val rect = RectF(left, top, left + badgeW, top + badgeH)
        canvas.drawRoundRect(rect, radius, radius, crossPercentBg)
        canvas.drawRoundRect(rect, radius, radius, crossPercentBorder)

        val textY = top + vPad - crossPercentText.ascent()
        canvas.drawText(text, left + hPad, textY, crossPercentText)
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
        // 0 轴基准线 + 右侧数值刻度
        canvas.drawLine(paddingLeft.toFloat(), zeroY, paddingLeft + chartW, zeroY, subRefLinePaint)
        val labelDy = -(textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText("0", paddingLeft + chartW + 4f * density, zeroY + labelDy, textPaint)

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
        // 70 (超买) / 50 (中轴) / 30 (超卖) 参考线
        val y30 = volTop + volH * (1f - 0.3f)
        val y50 = volTop + volH * (1f - 0.5f)
        val y70 = volTop + volH * (1f - 0.7f)

        // 30~70 常态区间背景半透明填充带
        canvas.drawRect(paddingLeft.toFloat(), y70, paddingLeft + chartW, y30, subZoneBandPaint)

        // 70 / 50 / 30 辅助虚线
        canvas.drawLine(paddingLeft.toFloat(), y70, paddingLeft + chartW, y70, subRefLinePaint)
        canvas.drawLine(paddingLeft.toFloat(), y50, paddingLeft + chartW, y50, subMidLinePaint)
        canvas.drawLine(paddingLeft.toFloat(), y30, paddingLeft + chartW, y30, subRefLinePaint)

        // 右侧数值刻度标签 (70 / 50 / 30)
        val labelDy = -(textPaint.ascent() + textPaint.descent()) / 2f
        val textX = paddingLeft + chartW + 4f * density
        canvas.drawText("70", textX, y70 + labelDy, textPaint)
        canvas.drawText("50", textX, y50 + labelDy, textPaint)
        canvas.drawText("30", textX, y30 + labelDy, textPaint)

        // 曲线
        drawSubSeries(canvas, rsi1, leftIdx, rightIdx, cw, 0.0, 100.0, rsi1Paint)
        drawSubSeries(canvas, rsi2, leftIdx, rightIdx, cw, 0.0, 100.0, rsi2Paint)
        drawSubSeries(canvas, rsi3, leftIdx, rightIdx, cw, 0.0, 100.0, rsi3Paint)
    }

    private fun drawSubKdj(canvas: Canvas, leftIdx: Int, rightIdx: Int, cw: Float) {
        val kdj = kdjResult ?: return
        // 80 (超买) / 50 (中轴) / 20 (超卖) 参考线
        val y20 = volTop + volH * (1f - 0.2f)
        val y50 = volTop + volH * (1f - 0.5f)
        val y80 = volTop + volH * (1f - 0.8f)

        // 20~80 常态区间背景半透明填充带
        canvas.drawRect(paddingLeft.toFloat(), y80, paddingLeft + chartW, y20, subZoneBandPaint)

        // 80 / 50 / 20 辅助虚线
        canvas.drawLine(paddingLeft.toFloat(), y80, paddingLeft + chartW, y80, subRefLinePaint)
        canvas.drawLine(paddingLeft.toFloat(), y50, paddingLeft + chartW, y50, subMidLinePaint)
        canvas.drawLine(paddingLeft.toFloat(), y20, paddingLeft + chartW, y20, subRefLinePaint)

        // 右侧数值刻度标签 (80 / 50 / 20)
        val labelDy = -(textPaint.ascent() + textPaint.descent()) / 2f
        val textX = paddingLeft + chartW + 4f * density
        canvas.drawText("80", textX, y80 + labelDy, textPaint)
        canvas.drawText("50", textX, y50 + labelDy, textPaint)
        canvas.drawText("20", textX, y20 + labelDy, textPaint)

        // 曲线 (安全限制在副图裁剪区域内)
        canvas.save()
        canvas.clipRect(paddingLeft.toFloat(), volTop, paddingLeft + chartW, volTop + volH)
        drawSubSeries(canvas, kdj.k, leftIdx, rightIdx, cw, 0.0, 100.0, kdjKPaint)
        drawSubSeries(canvas, kdj.d, leftIdx, rightIdx, cw, 0.0, 100.0, kdjDPaint)
        drawSubSeries(canvas, kdj.j, leftIdx, rightIdx, cw, 0.0, 100.0, kdjJPaint)
        canvas.restore()
    }

    // ---------- 副图左上角指标参数与详细数值绘制 (TradingView 风格) ----------

    private fun drawSubIndicatorLegend(canvas: Canvas, targetIdx: Int) {
        when (subIndicatorType) {
            "MACD" -> drawSubMacdLegend(canvas, targetIdx)
            "RSI" -> drawSubRsiLegend(canvas, targetIdx)
            "KDJ" -> drawSubKdjLegend(canvas, targetIdx)
            "VOL" -> drawSubVolLegend(canvas, targetIdx)
        }

        // 绘制自定义副图指标图例
        val textY = volTop + 2f * density - subLegendPaint.ascent()
        var curX = paddingLeft + 4f * density
        val maxRight = paddingLeft + chartW - 4f * density
        val spacing = 7f * density

        for (res in customPineResults) {
            if (!res.overlay) {
                curX = drawLegendItem(canvas, res.scriptName, 0xFFEAB308.toInt(), curX, textY, maxRight, spacing)
                for (line in res.plotLines) {
                    val v = line.series.getOrNull(targetIdx)
                    if (v != null && !v.isNaN()) {
                        curX = drawLegendItem(canvas, "${line.title}: ${formatSubVal(v)}", line.color, curX, textY, maxRight, spacing)
                    }
                }
            }
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

    private fun drawMainIndicatorLegend(canvas: Canvas, targetIdx: Int) {
        val textY = paddingTop + 4f * density - subLegendPaint.ascent()
        var curX = paddingLeft + 4f * density
        val maxRight = paddingLeft + chartW - 4f * density
        val spacing = 6f * density

        if (showThreeK) {
            val tk = threeKResult
            if (tk != null) {
                val pos = tk.positions.getOrNull(targetIdx) ?: 0
                val ema = tk.emaTrend.getOrNull(targetIdx)?.takeIf { it > 0f }
                val stop = tk.trailStop.getOrNull(targetIdx)?.takeIf { it > 0f }
                val hh = tk.upperDonch.getOrNull(targetIdx)?.takeIf { it > 0f }
                val ll = tk.lowerDonch.getOrNull(targetIdx)?.takeIf { it > 0f }

                curX = drawLegendItem(canvas, "3K趋势", 0xFFF59E0B.toInt(), curX, textY, maxRight, spacing)

                val posText = when (pos) {
                    1 -> "[多头持有]"
                    -1 -> "[空头持有]"
                    else -> "[空仓观望]"
                }
                val posColor = when (pos) {
                    1 -> 0xFF10B981.toInt()
                    -1 -> 0xFFEF4444.toInt()
                    else -> textPaint.color
                }
                curX = drawLegendItem(canvas, posText, posColor, curX, textY, maxRight, spacing)

                if (ema != null) {
                    curX = drawLegendItem(canvas, "EMA50: ${formatIndicatorVal(ema)}", 0xFFF59E0B.toInt(), curX, textY, maxRight, spacing)
                }
                if (stop != null) {
                    val stopCol = if (pos > 0) 0xFF10B981.toInt() else 0xFFEF4444.toInt()
                    curX = drawLegendItem(canvas, "止损: ${formatIndicatorVal(stop)}", stopCol, curX, textY, maxRight, spacing)
                } else if (hh != null && ll != null) {
                    curX = drawLegendItem(canvas, "通道: ${formatIndicatorVal(ll)}~${formatIndicatorVal(hh)}", textPaint.color, curX, textY, maxRight, spacing)
                }
            }
        }

        if (showBollBreak) {
            val bb = bollBreakResult
            if (bb != null) {
                val pos = bb.positions.getOrNull(targetIdx) ?: 0
                val top = bb.top.getOrNull(targetIdx)?.takeIf { it > 0f }
                val bot = bb.bottom.getOrNull(targetIdx)?.takeIf { it > 0f }
                val ma6 = bb.maFilter.getOrNull(targetIdx)?.takeIf { it > 0f }

                curX = drawLegendItem(canvas, "布林突破", 0xFFF59E0B.toInt(), curX, textY, maxRight, spacing)

                val posText = when (pos) {
                    1 -> "[多头持有]"
                    -1 -> "[空头持有]"
                    else -> "[空仓观望]"
                }
                val posColor = when (pos) {
                    1 -> 0xFF10B981.toInt()
                    -1 -> 0xFFEF4444.toInt()
                    else -> textPaint.color
                }
                curX = drawLegendItem(canvas, posText, posColor, curX, textY, maxRight, spacing)

                if (top != null && bot != null) {
                    curX = drawLegendItem(canvas, "轨: ${formatIndicatorVal(bot)}~${formatIndicatorVal(top)}", textPaint.color, curX, textY, maxRight, spacing)
                }
                if (ma6 != null) {
                    drawLegendItem(canvas, "MA60: ${formatIndicatorVal(ma6)}", 0xFF06B6D4.toInt(), curX, textY, maxRight, spacing)
                }
            }
        }

        if (showLorentzian) {
            val lc = lorentzianResult
            if (lc != null) {
                val pos = lc.positions.getOrNull(targetIdx) ?: 0
                val pred = lc.predictions.getOrNull(targetIdx) ?: 0f
                val kernel = lc.kernelEstimate.getOrNull(targetIdx)?.takeIf { it > 0f }
                val stop = lc.stopLoss.getOrNull(targetIdx)?.takeIf { it > 0f }

                curX = drawLegendItem(canvas, "洛伦兹ML", 0xFF06B6D4.toInt(), curX, textY, maxRight, spacing)

                val posText = when (pos) {
                    1 -> "[多头持有]"
                    -1 -> "[空头持有]"
                    else -> "[空仓观望]"
                }
                val posColor = when (pos) {
                    1 -> 0xFF10B981.toInt()
                    -1 -> 0xFFEF4444.toInt()
                    else -> textPaint.color
                }
                curX = drawLegendItem(canvas, posText, posColor, curX, textY, maxRight, spacing)

                val predText = String.format(Locale.US, "预测:%+.0f", pred)
                val predColor = if (pred >= 2f) 0xFF10B981.toInt() else if (pred <= -2f) 0xFFEF4444.toInt() else textPaint.color
                curX = drawLegendItem(canvas, predText, predColor, curX, textY, maxRight, spacing)

                if (kernel != null) {
                    curX = drawLegendItem(canvas, "核线: ${formatIndicatorVal(kernel)}", 0xFF06B6D4.toInt(), curX, textY, maxRight, spacing)
                }
                if (stop != null) {
                    drawLegendItem(canvas, "止损: ${formatIndicatorVal(stop)}", 0xFFEC4899.toInt(), curX, textY, maxRight, spacing)
                }
            }
        }

        for (res in customPineResults) {
            if (res.overlay) {
                curX = drawLegendItem(canvas, res.scriptName, 0xFFEAB308.toInt(), curX, textY, maxRight, spacing)
                for (line in res.plotLines) {
                    val v = line.series.getOrNull(targetIdx)
                    if (v != null && !v.isNaN()) {
                        curX = drawLegendItem(canvas, "${line.title}:${formatIndicatorVal(v)}", line.color, curX, textY, maxRight, spacing)
                    }
                }
            }
        }
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

    private fun drawHighLowMarkers(canvas: Canvas, leftIdx: Int, rightIdx: Int, minP: Double, range: Double) {
        if (leftIdx > rightIdx || candles.isEmpty()) return
        var maxHigh = -Double.MAX_VALUE
        var minLow = Double.MAX_VALUE
        var maxIdx = leftIdx
        var minIdx = leftIdx

        for (i in leftIdx..rightIdx) {
            val c = candles[i]
            if (c.high > maxHigh) {
                maxHigh = c.high
                maxIdx = i
            }
            if (c.low < minLow) {
                minLow = c.low
                minIdx = i
            }
        }

        if (maxHigh <= minLow) return

        // 绘制最高价标签
        val hx = xOfIndex(maxIdx.toFloat())
        val hy = yOfPrice(maxHigh, minP, range)
        val maxText = Fmt.price(maxHigh)
        val maxTextW = highLowTextPaint.measureText(maxText)
        val lineLen = 14f * density
        val arrowPad = 3f * density

        if (hx > paddingLeft + chartW * 0.65f) {
            // 靠近右侧：向左延伸 [text] ──
            val textLeft = hx - lineLen - arrowPad - maxTextW
            canvas.drawLine(hx, hy, hx - lineLen, hy, highLowPaint)
            canvas.drawText(maxText, textLeft.coerceAtLeast(paddingLeft + 4f * density), hy + 3.5f * density, highLowTextPaint)
        } else {
            // 靠近左侧：向右延伸 ── [text]
            canvas.drawLine(hx, hy, hx + lineLen, hy, highLowPaint)
            canvas.drawText(maxText, hx + lineLen + arrowPad, hy + 3.5f * density, highLowTextPaint)
        }

        // 绘制最低价标签
        val lx = xOfIndex(minIdx.toFloat())
        val ly = yOfPrice(minLow, minP, range)
        val minText = Fmt.price(minLow)
        val minTextW = highLowTextPaint.measureText(minText)

        if (lx > paddingLeft + chartW * 0.65f) {
            // 靠近右侧：向左延伸 [text] ──
            val textLeft = lx - lineLen - arrowPad - minTextW
            canvas.drawLine(lx, ly, lx - lineLen, ly, highLowPaint)
            canvas.drawText(minText, textLeft.coerceAtLeast(paddingLeft + 4f * density), ly + 3.5f * density, highLowTextPaint)
        } else {
            // 靠近左侧：向右延伸 ── [text]
            canvas.drawLine(lx, ly, lx + lineLen, ly, highLowPaint)
            canvas.drawText(minText, lx + lineLen + arrowPad, ly + 3.5f * density, highLowTextPaint)
        }
    }

    private fun drawFibonacciRetracement(canvas: Canvas, leftIdx: Int, rightIdx: Int, minP: Double, range: Double) {
        val fib = Indicators.computeFibonacci(candles, leftIdx, rightIdx) ?: return

        // 黄金口袋区间 (0.382 ~ 0.618) 填充高亮
        val l382 = fib.levels.firstOrNull { abs(it.ratio - 0.382) < 0.01 }
        val l618 = fib.levels.firstOrNull { abs(it.ratio - 0.618) < 0.01 }
        if (l382 != null && l618 != null) {
            val y382 = yOfPrice(l382.price, minP, range)
            val y618 = yOfPrice(l618.price, minP, range)
            val top = min(y382, y618).coerceIn(paddingTop.toFloat(), paddingTop + priceH)
            val bot = max(y382, y618).coerceIn(paddingTop.toFloat(), paddingTop + priceH)
            canvas.drawRect(paddingLeft.toFloat(), top, paddingLeft + chartW, bot, fibFillPaint)
        }

        // 绘制每一条斐波那契水平线及标签
        for (lvl in fib.levels) {
            val y = yOfPrice(lvl.price, minP, range)
            if (y in paddingTop.toFloat()..(paddingTop + priceH)) {
                canvas.drawLine(paddingLeft.toFloat(), y, paddingLeft + chartW, y, fibLinePaint)
                val text = "Fib ${lvl.label} ${Fmt.price(lvl.price)}"
                canvas.drawText(text, paddingLeft + 6f * density, y - 2.5f * density, fibTextPaint)
            }
        }
    }

    private fun drawSuperTrend(canvas: Canvas, leftIdx: Int, rightIdx: Int, cw: Float, minP: Double, range: Double) {
        val st = superTrendResult ?: return
        if (st.value.isEmpty()) return

        var prevX = 0f
        var prevY = 0f
        var prevTrend = 0

        for (i in leftIdx..rightIdx) {
            if (i >= st.value.size || st.value[i] <= 0f) continue
            val x = xOfIndex(i.toFloat())
            val y = yOfPrice(st.value[i].toDouble(), minP, range)
            val curTrend = st.trend[i]

            if (prevTrend != 0 && prevTrend == curTrend) {
                val paint = if (curTrend == 1) superTrendUpPaint else superTrendDnPaint
                canvas.drawLine(prevX, prevY, x, y, paint)
            }
            prevX = x
            prevY = y
            prevTrend = curTrend
        }
    }

    private fun drawRulerOverlay(canvas: Canvas, minP: Double, range: Double) {
        if (rulerStartIdx < 0 || rulerEndIdx < 0 || candles.isEmpty()) return
        val sIdx = rulerStartIdx.coerceIn(candles.indices)
        val eIdx = rulerEndIdx.coerceIn(candles.indices)
        val minIdx = min(sIdx, eIdx)
        val maxIdx = max(sIdx, eIdx)

        val x1 = xOfIndex(sIdx.toFloat())
        val x2 = xOfIndex(eIdx.toFloat())
        val y1 = yOfPrice(rulerStartPrice, minP, range)
        val y2 = yOfPrice(rulerEndPrice, minP, range)

        val rectL = min(x1, x2)
        val rectR = max(x1, x2)
        val rectT = min(y1, y2)
        val rectB = max(y1, y2)

        val deltaP = rulerEndPrice - rulerStartPrice
        val pct = if (rulerStartPrice != 0.0) (deltaP / rulerStartPrice) * 100.0 else 0.0
        val isUp = deltaP >= 0.0

        // 1. 区域半透明填充与边框
        val fillCol = if (isUp) (Palette.up(context) and 0x00FFFFFF) or 0x22000000 else (Palette.down(context) and 0x00FFFFFF) or 0x22000000
        rulerBgPaint.color = fillCol
        val borderCol = if (isUp) Palette.up(context) else Palette.down(context)
        rulerBorderPaint.color = (borderCol and 0x00FFFFFF) or 0x99000000.toInt()
        canvas.drawRect(rectL, rectT, rectR, rectB, rulerBgPaint)
        canvas.drawRect(rectL, rectT, rectR, rectB, rulerBorderPaint)

        // 2. 对角连线与端点圆
        rulerLinePaint.color = borderCol
        canvas.drawLine(x1, y1, x2, y2, rulerLinePaint)
        canvas.drawCircle(x1, y1, 4f * density, rulerLinePaint)
        canvas.drawCircle(x2, y2, 4f * density, rulerLinePaint)

        // 3. 计算区间统计数据
        val barCount = maxIdx - minIdx + 1
        var totalVol = 0.0
        for (i in minIdx..maxIdx) {
            totalVol += candles[i].vol
        }
        val startTime = candles[minIdx].ts
        val endTime = candles[maxIdx].ts
        val durationMins = (abs(endTime - startTime) / (60 * 1000)).toInt()
        val durationStr = if (durationMins >= 1440) "${durationMins / 1440}d ${(durationMins % 1440) / 60}h" else if (durationMins >= 60) "${durationMins / 60}h ${durationMins % 60}m" else "${durationMins}m"

        val line1 = "${if (isUp) "+" else ""}${Fmt.price(deltaP)} (${String.format(Locale.US, "%+.2f%%", pct)})"
        val line2 = "$barCount 根K线 ($durationStr)  |  量 ${Fmt.vol(totalVol)}"

        // 4. 绘制信息卡片
        val padH = 8f * density
        val padV = 6f * density
        val w1 = rulerTextPaint.measureText(line1)
        val w2 = rulerTextPaint.measureText(line2)
        val cardW = max(w1, w2) + padH * 2f
        val lineH = rulerTextPaint.descent() - rulerTextPaint.ascent()
        val cardH = lineH * 2f + 4f * density + padV * 2f

        var cardX = ((x1 + x2) / 2f - cardW / 2f).coerceIn(paddingLeft + 4f * density, paddingLeft + chartW - cardW - 4f * density)
        var cardY = (min(y1, y2) - cardH - 8f * density)
        if (cardY < paddingTop + 4f * density) {
            cardY = max(y1, y2) + 8f * density
        }
        if (cardY + cardH > paddingTop + priceH) {
            cardY = paddingTop + priceH - cardH - 4f * density
        }

        val cardRect = RectF(cardX, cardY, cardX + cardW, cardY + cardH)
        canvas.drawRoundRect(cardRect, 4f * density, 4f * density, rulerCardBgPaint)
        canvas.drawRoundRect(cardRect, 4f * density, 4f * density, rulerCardBorderPaint)

        rulerTextPaint.color = borderCol
        canvas.drawText(line1, cardX + padH, cardY + padV - rulerTextPaint.ascent(), rulerTextPaint)
        rulerTextPaint.color = if ((context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES) 0xFFCBD5E1.toInt() else 0xFF475569.toInt()
        canvas.drawText(line2, cardX + padH, cardY + padV + lineH + 4f * density - rulerTextPaint.ascent(), rulerTextPaint)
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

        // EMA
        val ep1 = Prefs.getEma1Period(context)
        val ep2 = Prefs.getEma2Period(context)
        val ep3 = Prefs.getEma3Period(context)
        ema1 = Indicators.computeEma(candles, ep1)
        ema2 = Indicators.computeEma(candles, ep2)
        ema3 = Indicators.computeEma(candles, ep3)

        // BOLL
        val bN = Prefs.getBollN(context)
        val bK = Prefs.getBollK(context).toDouble()
        bollResult = Indicators.computeBoll(candles, bN, bK)

        // SuperTrend
        val stAtr = Prefs.getSuperTrendAtr(context)
        val stFactor = Prefs.getSuperTrendFactor(context).toDouble()
        superTrendResult = Indicators.computeSuperTrend(candles, stAtr, stFactor)

        // TURTLE
        val tEntry = Prefs.getTurtleEntry(context)
        val tExit = Prefs.getTurtleExit(context)
        val tAtr = Prefs.getTurtleAtr(context)
        turtleResult = Indicators.computeTurtle(candles, tEntry, tExit, tAtr)

        // 3K 策略 (BTC 1D Trend Alpha)
        val tkDonch = Prefs.getThreeKDonch(context)
        val tkEma = Prefs.getThreeKEma(context)
        val tkAtr = Prefs.getThreeKAtr(context)
        val tkAtrMult = Prefs.getThreeKAtrMult(context).toDouble()
        val tkGuard = Prefs.getThreeKGuard(context).toDouble()
        threeKResult = Indicators.computeThreeK(candles, tkDonch, tkEma, tkAtr, tkAtrMult, tkGuard)

        // 布林突破策略 (布林线突破 + MA60 过滤)
        val bbN = Prefs.getBollBreakN(context)
        val bbM = Prefs.getBollBreakM(context)
        val bbP = Prefs.getBollBreakP(context).toDouble()
        val bbMaFilter = Prefs.getBollBreakMaFilter(context)
        bollBreakResult = Indicators.computeBollBreak(candles, bbN, bbM, bbP, bbMaFilter)

        // 洛伦兹分类 (Machine Learning: Lorentzian Classification)
        val lkK = Prefs.getLorentzianNeighbors(context)
        val lkMax = Prefs.getLorentzianMaxBars(context)
        val lkLook = Prefs.getLorentzianLookback(context)
        val lkWeight = Prefs.getLorentzianWeight(context).toDouble()
        lorentzianResult = Indicators.computeLorentzian(candles, lkK, lkMax, lkLook, lkWeight)

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

        // 自定义 Pine Script (v5) 脚本动态求值
        val customScripts = com.bitdash.app.market.PineScriptManager.getScripts(context).filter { it.enabled }
        customPineResults = customScripts.map { com.bitdash.app.market.PineEngine.execute(it, candles) }
    }

    private fun drawThreeKStrategy(
        canvas: Canvas,
        tk: Indicators.ThreeKResult,
        leftIdx: Int,
        rightIdx: Int,
        cw: Float,
        minP: Double,
        range: Double
    ) {
        // 1. 绘制 Donchian 40 上下突破通道
        drawLineSeries(canvas, tk.upperDonch, leftIdx, rightIdx, cw, minP, range, threeKUpperPaint)
        drawLineSeries(canvas, tk.lowerDonch, leftIdx, rightIdx, cw, minP, range, threeKLowerPaint)

        // 2. 绘制 50 EMA 趋势中枢线
        drawLineSeries(canvas, tk.emaTrend, leftIdx, rightIdx, cw, minP, range, threeKEmaPaint)

        // 3. 绘制持仓期间的动态移动追踪止损线
        for (i in leftIdx..rightIdx) {
            val stopP = if (i < tk.trailStop.size) tk.trailStop[i] else 0f
            val pos = if (i < tk.positions.size) tk.positions[i] else 0
            if (stopP > 0f && pos != 0) {
                val x = xOfIndex(i.toFloat())
                val y = yOfPrice(stopP.toDouble(), minP, range)
                val halfW = (cw * 0.45f).coerceAtLeast(2f)
                val paint = if (pos > 0) threeKStopLongPaint else threeKStopShortPaint
                canvas.drawLine(x - halfW, y, x + halfW, y, paint)
            }
        }

        // 4. 绘制买入、卖出及平仓交易信号徽标 (TradingView 风格)
        val badgePadH = 3.5f * density
        val badgePadV = 2f * density
        val arrowSize = 3.5f * density

        for (i in leftIdx..rightIdx) {
            if (i >= tk.signals.size) continue
            val sig = tk.signals[i]
            if (sig == Indicators.ThreeKSignal.NONE) continue
            val c = candles[i]
            val x = xOfIndex(i.toFloat())

            when (sig) {
                Indicators.ThreeKSignal.BUY -> {
                    // 做多入场：在最低价下方画绿色向上三角与“3K多”徽标
                    val lowY = yOfPrice(c.low, minP, range)
                    val arrowTop = lowY + 4f * density
                    val arrowBottom = arrowTop + arrowSize

                    linePath.reset()
                    linePath.moveTo(x, arrowTop)
                    linePath.lineTo(x - arrowSize, arrowBottom)
                    linePath.lineTo(x + arrowSize, arrowBottom)
                    linePath.close()
                    threeKSignalBgPaint.color = 0xFF10B981.toInt()
                    canvas.drawPath(linePath, threeKSignalBgPaint)

                    val label = "3K多"
                    val tw = threeKSignalTextPaint.measureText(label)
                    val th = threeKSignalTextPaint.descent() - threeKSignalTextPaint.ascent()
                    val badgeW = tw + badgePadH * 2f
                    val badgeH = th + badgePadV * 2f
                    val badgeL = x - badgeW / 2f
                    val badgeT = arrowBottom + 1.5f * density
                    val rect = RectF(badgeL, badgeT, badgeL + badgeW, badgeT + badgeH)
                    canvas.drawRoundRect(rect, 3f * density, 3f * density, threeKSignalBgPaint)
                    canvas.drawText(label, x, badgeT + badgePadV - threeKSignalTextPaint.ascent(), threeKSignalTextPaint)
                }
                Indicators.ThreeKSignal.SELL -> {
                    // 做空入场：在最高价上方画红色向下三角与“3K空”徽标
                    val highY = yOfPrice(c.high, minP, range)
                    val arrowBottom = highY - 4f * density
                    val arrowTop = arrowBottom - arrowSize

                    linePath.reset()
                    linePath.moveTo(x, arrowBottom)
                    linePath.lineTo(x - arrowSize, arrowTop)
                    linePath.lineTo(x + arrowSize, arrowTop)
                    linePath.close()
                    threeKSignalBgPaint.color = 0xFFEF4444.toInt()
                    canvas.drawPath(linePath, threeKSignalBgPaint)

                    val label = "3K空"
                    val tw = threeKSignalTextPaint.measureText(label)
                    val th = threeKSignalTextPaint.descent() - threeKSignalTextPaint.ascent()
                    val badgeW = tw + badgePadH * 2f
                    val badgeH = th + badgePadV * 2f
                    val badgeL = x - badgeW / 2f
                    val badgeT = arrowTop - 1.5f * density - badgeH
                    val rect = RectF(badgeL, badgeT, badgeL + badgeW, badgeT + badgeH)
                    canvas.drawRoundRect(rect, 3f * density, 3f * density, threeKSignalBgPaint)
                    canvas.drawText(label, x, badgeT + badgePadV - threeKSignalTextPaint.ascent(), threeKSignalTextPaint)
                }
                Indicators.ThreeKSignal.CLOSE_LONG -> {
                    // 平多：在最高价上方标示“平多”
                    val highY = yOfPrice(c.high, minP, range)
                    val label = "平多"
                    val tw = threeKSignalTextPaint.measureText(label)
                    val th = threeKSignalTextPaint.descent() - threeKSignalTextPaint.ascent()
                    val badgeW = tw + badgePadH * 2f
                    val badgeH = th + badgePadV * 2f
                    val badgeL = x - badgeW / 2f
                    val badgeT = highY - 6f * density - badgeH
                    threeKSignalBgPaint.color = 0xCCF59E0B.toInt()
                    val rect = RectF(badgeL, badgeT, badgeL + badgeW, badgeT + badgeH)
                    canvas.drawRoundRect(rect, 3f * density, 3f * density, threeKSignalBgPaint)
                    canvas.drawText(label, x, badgeT + badgePadV - threeKSignalTextPaint.ascent(), threeKSignalTextPaint)
                }
                Indicators.ThreeKSignal.CLOSE_SHORT -> {
                    // 平空：在最低价下方标示“平空”
                    val lowY = yOfPrice(c.low, minP, range)
                    val label = "平空"
                    val tw = threeKSignalTextPaint.measureText(label)
                    val th = threeKSignalTextPaint.descent() - threeKSignalTextPaint.ascent()
                    val badgeW = tw + badgePadH * 2f
                    val badgeH = th + badgePadV * 2f
                    val badgeL = x - badgeW / 2f
                    val badgeT = lowY + 6f * density
                    threeKSignalBgPaint.color = 0xCCF59E0B.toInt()
                    val rect = RectF(badgeL, badgeT, badgeL + badgeW, badgeT + badgeH)
                    canvas.drawRoundRect(rect, 3f * density, 3f * density, threeKSignalBgPaint)
                    canvas.drawText(label, x, badgeT + badgePadV - threeKSignalTextPaint.ascent(), threeKSignalTextPaint)
                }
                else -> {}
            }
        }
    }

    private fun drawBollBreakStrategy(
        canvas: Canvas,
        bb: Indicators.BollBreakResult,
        leftIdx: Int,
        rightIdx: Int,
        cw: Float,
        minP: Double,
        range: Double
    ) {
        // 1. 绘制布林带上下轨之间的区域半透明填充
        linePath.reset()
        var hasFill = false
        for (i in leftIdx..rightIdx) {
            if (i < bb.top.size && bb.top[i] > 0f && bb.bottom[i] > 0f) {
                val x = xOfIndex(i.toFloat())
                val yUp = yOfPrice(bb.top[i].toDouble(), minP, range)
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
                if (i < bb.bottom.size && bb.bottom[i] > 0f) {
                    val x = xOfIndex(i.toFloat())
                    val yDn = yOfPrice(bb.bottom[i].toDouble(), minP, range)
                    linePath.lineTo(x, yDn)
                }
            }
            linePath.close()
            canvas.drawPath(linePath, bollBreakFillPaint)
        }

        // 2. 绘制中轨（橙色粗线）、上轨（红色线）、下轨（绿色线）与 MA60 趋势过滤线（青色）
        drawLineSeries(canvas, bb.top, leftIdx, rightIdx, cw, minP, range, bollBreakTopPaint)
        drawLineSeries(canvas, bb.mid, leftIdx, rightIdx, cw, minP, range, bollBreakMidPaint)
        drawLineSeries(canvas, bb.bottom, leftIdx, rightIdx, cw, minP, range, bollBreakBotPaint)
        drawLineSeries(canvas, bb.maFilter, leftIdx, rightIdx, cw, minP, range, bollBreakMa60Paint)

        // 3. 绘制交易买卖与平仓信号标记
        val badgePadH = 3.5f * density
        val badgePadV = 2f * density
        val arrowSize = 3.5f * density

        for (i in leftIdx..rightIdx) {
            if (i >= bb.signals.size) continue
            val sig = bb.signals[i]
            if (sig == Indicators.BollBreakSignal.NONE) continue
            val c = candles[i]
            val x = xOfIndex(i.toFloat())

            when (sig) {
                Indicators.BollBreakSignal.BUY -> {
                    // 突破做多：K线最低价下方绿色三角与“多”徽标
                    val lowY = yOfPrice(c.low, minP, range)
                    val arrowTop = lowY + 4f * density
                    val arrowBottom = arrowTop + arrowSize

                    linePath.reset()
                    linePath.moveTo(x, arrowTop)
                    linePath.lineTo(x - arrowSize, arrowBottom)
                    linePath.lineTo(x + arrowSize, arrowBottom)
                    linePath.close()
                    threeKSignalBgPaint.color = 0xFF10B981.toInt()
                    canvas.drawPath(linePath, threeKSignalBgPaint)

                    val label = "多"
                    val tw = threeKSignalTextPaint.measureText(label)
                    val th = threeKSignalTextPaint.descent() - threeKSignalTextPaint.ascent()
                    val badgeW = tw + badgePadH * 2f
                    val badgeH = th + badgePadV * 2f
                    val badgeL = x - badgeW / 2f
                    val badgeT = arrowBottom + 1.5f * density
                    val rect = RectF(badgeL, badgeT, badgeL + badgeW, badgeT + badgeH)
                    canvas.drawRoundRect(rect, 3f * density, 3f * density, threeKSignalBgPaint)
                    canvas.drawText(label, x, badgeT + badgePadV - threeKSignalTextPaint.ascent(), threeKSignalTextPaint)
                }
                Indicators.BollBreakSignal.SELL -> {
                    // 跌破做空：K线最高价上方红色三角与“空”徽标
                    val highY = yOfPrice(c.high, minP, range)
                    val arrowBottom = highY - 4f * density
                    val arrowTop = arrowBottom - arrowSize

                    linePath.reset()
                    linePath.moveTo(x, arrowBottom)
                    linePath.lineTo(x - arrowSize, arrowTop)
                    linePath.lineTo(x + arrowSize, arrowTop)
                    linePath.close()
                    threeKSignalBgPaint.color = 0xFFEF4444.toInt()
                    canvas.drawPath(linePath, threeKSignalBgPaint)

                    val label = "空"
                    val tw = threeKSignalTextPaint.measureText(label)
                    val th = threeKSignalTextPaint.descent() - threeKSignalTextPaint.ascent()
                    val badgeW = tw + badgePadH * 2f
                    val badgeH = th + badgePadV * 2f
                    val badgeL = x - badgeW / 2f
                    val badgeT = arrowTop - 1.5f * density - badgeH
                    val rect = RectF(badgeL, badgeT, badgeL + badgeW, badgeT + badgeH)
                    canvas.drawRoundRect(rect, 3f * density, 3f * density, threeKSignalBgPaint)
                    canvas.drawText(label, x, badgeT + badgePadV - threeKSignalTextPaint.ascent(), threeKSignalTextPaint)
                }
                Indicators.BollBreakSignal.CLOSE_LONG -> {
                    // 跌破上轨平多：K线最高价上方标示“平多”
                    val highY = yOfPrice(c.high, minP, range)
                    val label = "平多"
                    val tw = threeKSignalTextPaint.measureText(label)
                    val th = threeKSignalTextPaint.descent() - threeKSignalTextPaint.ascent()
                    val badgeW = tw + badgePadH * 2f
                    val badgeH = th + badgePadV * 2f
                    val badgeL = x - badgeW / 2f
                    val badgeT = highY - 6f * density - badgeH
                    threeKSignalBgPaint.color = 0xCCF59E0B.toInt()
                    val rect = RectF(badgeL, badgeT, badgeL + badgeW, badgeT + badgeH)
                    canvas.drawRoundRect(rect, 3f * density, 3f * density, threeKSignalBgPaint)
                    canvas.drawText(label, x, badgeT + badgePadV - threeKSignalTextPaint.ascent(), threeKSignalTextPaint)
                }
                Indicators.BollBreakSignal.CLOSE_SHORT -> {
                    // 突破下轨平空：K线最低价下方标示“平空”
                    val lowY = yOfPrice(c.low, minP, range)
                    val label = "平空"
                    val tw = threeKSignalTextPaint.measureText(label)
                    val th = threeKSignalTextPaint.descent() - threeKSignalTextPaint.ascent()
                    val badgeW = tw + badgePadH * 2f
                    val badgeH = th + badgePadV * 2f
                    val badgeL = x - badgeW / 2f
                    val badgeT = lowY + 6f * density
                    threeKSignalBgPaint.color = 0xCCF59E0B.toInt()
                    val rect = RectF(badgeL, badgeT, badgeL + badgeW, badgeT + badgeH)
                    canvas.drawRoundRect(rect, 3f * density, 3f * density, threeKSignalBgPaint)
                    canvas.drawText(label, x, badgeT + badgePadV - threeKSignalTextPaint.ascent(), threeKSignalTextPaint)
                }
                else -> {}
            }
        }
    }

    private fun drawLorentzianStrategy(
        canvas: Canvas,
        lc: Indicators.LorentzianResult,
        leftIdx: Int,
        rightIdx: Int,
        cw: Float,
        minP: Double,
        range: Double
    ) {
        // 1. 绘制 Nadaraya-Watson (NW) 核回归平滑估计中枢线
        drawLineSeries(canvas, lc.kernelEstimate, leftIdx, rightIdx, cw, minP, range, lorentzKernelPaint)

        // 2. 绘制持仓期间的 ATR 动态移动止损线
        for (i in leftIdx..rightIdx) {
            val stopP = if (i < lc.stopLoss.size) lc.stopLoss[i] else 0f
            val pos = if (i < lc.positions.size) lc.positions[i] else 0
            if (stopP > 0f && pos != 0) {
                val x = xOfIndex(i.toFloat())
                val y = yOfPrice(stopP.toDouble(), minP, range)
                val halfW = (cw * 0.45f).coerceAtLeast(2f)
                canvas.drawLine(x - halfW, y, x + halfW, y, lorentzStopPaint)
            }
        }

        // 3. 绘制 ML 分类预测做多、做空及平仓信号徽标
        val badgePadH = 3.5f * density
        val badgePadV = 2f * density
        val arrowSize = 3.5f * density

        for (i in leftIdx..rightIdx) {
            if (i >= lc.signals.size) continue
            val sig = lc.signals[i]
            if (sig == Indicators.LorentzianSignal.NONE) continue
            val c = candles[i]
            val x = xOfIndex(i.toFloat())

            when (sig) {
                Indicators.LorentzianSignal.BUY -> {
                    // ML 预测做多：最低价下方画绿色向上三角与“ML多”
                    val lowY = yOfPrice(c.low, minP, range)
                    val arrowTop = lowY + 4f * density
                    val arrowBottom = arrowTop + arrowSize

                    linePath.reset()
                    linePath.moveTo(x, arrowTop)
                    linePath.lineTo(x - arrowSize, arrowBottom)
                    linePath.lineTo(x + arrowSize, arrowBottom)
                    linePath.close()
                    threeKSignalBgPaint.color = 0xFF10B981.toInt()
                    canvas.drawPath(linePath, threeKSignalBgPaint)

                    val label = "ML多"
                    val tw = threeKSignalTextPaint.measureText(label)
                    val th = threeKSignalTextPaint.descent() - threeKSignalTextPaint.ascent()
                    val badgeW = tw + badgePadH * 2f
                    val badgeH = th + badgePadV * 2f
                    val badgeL = x - badgeW / 2f
                    val badgeT = arrowBottom + 1.5f * density
                    val rect = RectF(badgeL, badgeT, badgeL + badgeW, badgeT + badgeH)
                    canvas.drawRoundRect(rect, 3f * density, 3f * density, threeKSignalBgPaint)
                    canvas.drawText(label, x, badgeT + badgePadV - threeKSignalTextPaint.ascent(), threeKSignalTextPaint)
                }
                Indicators.LorentzianSignal.SELL -> {
                    // ML 预测做空：最高价上方画红色向下三角与“ML空”
                    val highY = yOfPrice(c.high, minP, range)
                    val arrowBottom = highY - 4f * density
                    val arrowTop = arrowBottom - arrowSize

                    linePath.reset()
                    linePath.moveTo(x, arrowBottom)
                    linePath.lineTo(x - arrowSize, arrowTop)
                    linePath.lineTo(x + arrowSize, arrowTop)
                    linePath.close()
                    threeKSignalBgPaint.color = 0xFFEF4444.toInt()
                    canvas.drawPath(linePath, threeKSignalBgPaint)

                    val label = "ML空"
                    val tw = threeKSignalTextPaint.measureText(label)
                    val th = threeKSignalTextPaint.descent() - threeKSignalTextPaint.ascent()
                    val badgeW = tw + badgePadH * 2f
                    val badgeH = th + badgePadV * 2f
                    val badgeL = x - badgeW / 2f
                    val badgeT = arrowTop - 1.5f * density - badgeH
                    val rect = RectF(badgeL, badgeT, badgeL + badgeW, badgeT + badgeH)
                    canvas.drawRoundRect(rect, 3f * density, 3f * density, threeKSignalBgPaint)
                    canvas.drawText(label, x, badgeT + badgePadV - threeKSignalTextPaint.ascent(), threeKSignalTextPaint)
                }
                Indicators.LorentzianSignal.CLOSE_LONG -> {
                    // 平多：最高价上方标示“平多”
                    val highY = yOfPrice(c.high, minP, range)
                    val label = "平多"
                    val tw = threeKSignalTextPaint.measureText(label)
                    val th = threeKSignalTextPaint.descent() - threeKSignalTextPaint.ascent()
                    val badgeW = tw + badgePadH * 2f
                    val badgeH = th + badgePadV * 2f
                    val badgeL = x - badgeW / 2f
                    val badgeT = highY - 6f * density - badgeH
                    threeKSignalBgPaint.color = 0xCCF59E0B.toInt()
                    val rect = RectF(badgeL, badgeT, badgeL + badgeW, badgeT + badgeH)
                    canvas.drawRoundRect(rect, 3f * density, 3f * density, threeKSignalBgPaint)
                    canvas.drawText(label, x, badgeT + badgePadV - threeKSignalTextPaint.ascent(), threeKSignalTextPaint)
                }
                Indicators.LorentzianSignal.CLOSE_SHORT -> {
                    // 平空：最低价下方标示“平空”
                    val lowY = yOfPrice(c.low, minP, range)
                    val label = "平空"
                    val tw = threeKSignalTextPaint.measureText(label)
                    val th = threeKSignalTextPaint.descent() - threeKSignalTextPaint.ascent()
                    val badgeW = tw + badgePadH * 2f
                    val badgeH = th + badgePadV * 2f
                    val badgeL = x - badgeW / 2f
                    val badgeT = lowY + 6f * density
                    threeKSignalBgPaint.color = 0xCCF59E0B.toInt()
                    val rect = RectF(badgeL, badgeT, badgeL + badgeW, badgeT + badgeH)
                    canvas.drawRoundRect(rect, 3f * density, 3f * density, threeKSignalBgPaint)
                    canvas.drawText(label, x, badgeT + badgePadV - threeKSignalTextPaint.ascent(), threeKSignalTextPaint)
                }
                else -> {}
            }
        }
    }

    private fun drawCustomPineMain(
        canvas: Canvas,
        leftIdx: Int,
        rightIdx: Int,
        cw: Float,
        minP: Double,
        range: Double
    ) {
        val badgePadH = 3.5f * density
        val badgePadV = 2f * density
        val arrowSize = 3.5f * density

        for (res in customPineResults) {
            if (!res.overlay) continue

            // 1. 绘制 fills 填充区域
            for (fill in res.fills) {
                if (fill.line1Index in res.plotLines.indices && fill.line2Index in res.plotLines.indices) {
                    val l1 = res.plotLines[fill.line1Index].series
                    val l2 = res.plotLines[fill.line2Index].series
                    linePath.reset()
                    var hasFill = false
                    for (i in leftIdx..rightIdx) {
                        if (i < l1.size && l1[i] > 0f) {
                            val x = xOfIndex(i.toFloat())
                            val y = yOfPrice(l1[i].toDouble(), minP, range)
                            if (!hasFill) {
                                linePath.moveTo(x, y)
                                hasFill = true
                            } else {
                                linePath.lineTo(x, y)
                            }
                        }
                    }
                    if (hasFill) {
                        for (i in rightIdx downTo leftIdx) {
                            if (i < l2.size && l2[i] > 0f) {
                                val x = xOfIndex(i.toFloat())
                                val y = yOfPrice(l2[i].toDouble(), minP, range)
                                linePath.lineTo(x, y)
                            }
                        }
                        linePath.close()
                        customPineFillPaint.color = fill.color
                        canvas.drawPath(linePath, customPineFillPaint)
                    }
                }
            }

            // 2. 绘制 plotLines 曲线
            for (line in res.plotLines) {
                customPineLinePaint.color = line.color
                customPineLinePaint.strokeWidth = line.lineWidth * density
                drawLineSeries(canvas, line.series, leftIdx, rightIdx, cw, minP, range, customPineLinePaint)
            }

            // 3. 绘制 plotShapes 买卖信号徽标
            for (shape in res.plotShapes) {
                for (i in leftIdx..rightIdx) {
                    if (i < shape.indices.size && shape.indices[i]) {
                        val c = candles[i]
                        val x = xOfIndex(i.toFloat())
                        val label = shape.text.ifEmpty { if (shape.isBuy) "多" else "空" }

                        if (shape.isBuy) {
                            val lowY = yOfPrice(c.low, minP, range)
                            val arrowTop = lowY + 4f * density
                            val arrowBottom = arrowTop + arrowSize

                            linePath.reset()
                            linePath.moveTo(x, arrowTop)
                            linePath.lineTo(x - arrowSize, arrowBottom)
                            linePath.lineTo(x + arrowSize, arrowBottom)
                            linePath.close()
                            threeKSignalBgPaint.color = shape.color
                            canvas.drawPath(linePath, threeKSignalBgPaint)

                            val tw = threeKSignalTextPaint.measureText(label)
                            val th = threeKSignalTextPaint.descent() - threeKSignalTextPaint.ascent()
                            val badgeW = tw + badgePadH * 2f
                            val badgeH = th + badgePadV * 2f
                            val badgeL = x - badgeW / 2f
                            val badgeT = arrowBottom + 1.5f * density
                            val rect = RectF(badgeL, badgeT, badgeL + badgeW, badgeT + badgeH)
                            canvas.drawRoundRect(rect, 3f * density, 3f * density, threeKSignalBgPaint)
                            canvas.drawText(label, x, badgeT + badgePadV - threeKSignalTextPaint.ascent(), threeKSignalTextPaint)
                        } else {
                            val highY = yOfPrice(c.high, minP, range)
                            val arrowBottom = highY - 4f * density
                            val arrowTop = arrowBottom - arrowSize

                            linePath.reset()
                            linePath.moveTo(x, arrowBottom)
                            linePath.lineTo(x - arrowSize, arrowTop)
                            linePath.lineTo(x + arrowSize, arrowTop)
                            linePath.close()
                            threeKSignalBgPaint.color = shape.color
                            canvas.drawPath(linePath, threeKSignalBgPaint)

                            val tw = threeKSignalTextPaint.measureText(label)
                            val th = threeKSignalTextPaint.descent() - threeKSignalTextPaint.ascent()
                            val badgeW = tw + badgePadH * 2f
                            val badgeH = th + badgePadV * 2f
                            val badgeL = x - badgeW / 2f
                            val badgeT = arrowTop - 1.5f * density - badgeH
                            val rect = RectF(badgeL, badgeT, badgeL + badgeW, badgeT + badgeH)
                            canvas.drawRoundRect(rect, 3f * density, 3f * density, threeKSignalBgPaint)
                            canvas.drawText(label, x, badgeT + badgePadV - threeKSignalTextPaint.ascent(), threeKSignalTextPaint)
                        }
                    }
                }
            }
        }
    }

    private fun drawCustomPineSub(
        canvas: Canvas,
        leftIdx: Int,
        rightIdx: Int,
        cw: Float
    ) {
        for (res in customPineResults) {
            if (res.overlay) continue

            // 1. 计算当前可见区间内所有 plot 序列的最小值与最大值
            var minV = Double.MAX_VALUE
            var maxV = -Double.MAX_VALUE
            for (line in res.plotLines) {
                for (i in leftIdx..rightIdx) {
                    if (i < line.series.size) {
                        val v = line.series[i].toDouble()
                        if (!v.isNaN()) {
                            if (v < minV) minV = v
                            if (v > maxV) maxV = v
                        }
                    }
                }
            }
            if (minV == Double.MAX_VALUE) {
                minV = 0.0
                maxV = 100.0
            }
            if (maxV <= minV) maxV = minV + 1.0
            val pad = (maxV - minV) * 0.08
            val effMin = minV - pad
            val effRange = (maxV - minV + pad * 2.0).coerceAtLeast(1e-6)

            // 2. 绘制 fill 填充带
            for (fill in res.fills) {
                if (fill.line1Index in res.plotLines.indices && fill.line2Index in res.plotLines.indices) {
                    val l1 = res.plotLines[fill.line1Index].series
                    val l2 = res.plotLines[fill.line2Index].series
                    linePath.reset()
                    var hasFill = false
                    for (i in leftIdx..rightIdx) {
                        if (i < l1.size) {
                            val x = xOfIndex(i.toFloat())
                            val frac = ((l1[i] - effMin) / effRange).toFloat().coerceIn(0f, 1f)
                            val y = volTop + volH * (1f - frac)
                            if (!hasFill) {
                                linePath.moveTo(x, y)
                                hasFill = true
                            } else {
                                linePath.lineTo(x, y)
                            }
                        }
                    }
                    if (hasFill) {
                        for (i in rightIdx downTo leftIdx) {
                            if (i < l2.size) {
                                val x = xOfIndex(i.toFloat())
                                val frac = ((l2[i] - effMin) / effRange).toFloat().coerceIn(0f, 1f)
                                val y = volTop + volH * (1f - frac)
                                linePath.lineTo(x, y)
                            }
                        }
                        linePath.close()
                        customPineFillPaint.color = fill.color
                        canvas.drawPath(linePath, customPineFillPaint)
                    }
                }
            }

            // 3. 绘制 plotLines 曲线与常数参考线
            for (line in res.plotLines) {
                customPineLinePaint.color = line.color
                customPineLinePaint.strokeWidth = line.lineWidth * density
                drawSubSeries(canvas, line.series, leftIdx, rightIdx, cw, effMin, effRange, customPineLinePaint)

                // 若为常数线（例如超买 70 / 超卖 30），在右侧坐标轴画出刻度
                if (leftIdx < line.series.size && rightIdx < line.series.size) {
                    val isConstant = (line.series[leftIdx] == line.series[rightIdx]) && (line.title.contains("超") || line.title.contains("线") || line.title.contains("Level"))
                    if (isConstant) {
                        val constV = line.series[leftIdx]
                        val frac = ((constV - effMin) / effRange).toFloat().coerceIn(0f, 1f)
                        val y = volTop + volH * (1f - frac)
                        val labelDy = -(textPaint.ascent() + textPaint.descent()) / 2f
                        canvas.drawText(formatSubVal(constV), paddingLeft + chartW + 4f * density, y + labelDy, textPaint)
                    }
                }
            }
        }
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

