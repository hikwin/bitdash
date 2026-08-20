package com.bitdash.app.market

import android.content.Context
import android.content.pm.ActivityInfo
import org.json.JSONArray

/**
 * 本地设置持久化（SharedPreferences）。
 *
 * 自选列表统一以 "BASE-QUOTE" 存储，因此切换行情源后自选依然有效。
 */
object Prefs {

    private const val FILE = "bitdash_prefs"
    private const val KEY_WATCH = "watchlist"
    private const val KEY_SOURCE = "source_id"
    private const val KEY_PREFERRED = "preferred_id"
    private const val KEY_REFRESH_MS = "refresh_ms"
    private const val KEY_ORIENTATION = "orientation"
    private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_UP_IS_GREEN = "up_is_green"
    private const val KEY_FONT_SCALE = "font_scale_pct"
    private const val KEY_CHART_FONT_SCALE = "chart_font_scale_pct"
    private const val KEY_RT_SCOPE = "rt_scope"
    private const val KEY_FLOATING_ENABLED = "floating_enabled"
    private const val KEY_FLOATING_SYMBOLS = "floating_symbols"
    private const val KEY_FLOATING_ALPHA = "floating_alpha_pct"
    private const val KEY_FLOATING_X = "floating_x"
    private const val KEY_FLOATING_Y = "floating_y"

    /** 主题模式：0=夜间模式（默认），1=日间模式（亮色），2=跟随系统 */
    const val THEME_DARK = 0
    const val THEME_LIGHT = 1
    const val THEME_SYSTEM = 2

    // ---------- 外观主题（日间/夜间） ----------

    fun getThemeMode(ctx: Context): Int =
        sp(ctx).getInt(KEY_THEME_MODE, THEME_DARK)

    fun saveThemeMode(ctx: Context, mode: Int) {
        sp(ctx).edit().putInt(KEY_THEME_MODE, mode).apply()
    }

    /** 应用全局日间/夜间主题设置 */
    fun applyTheme(mode: Int) {
        val nightMode = when (mode) {
            THEME_LIGHT -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            THEME_SYSTEM -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
        }
        if (androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode() != nightMode) {
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(nightMode)
        }
    }

    /** 默认悬浮窗透明度百分比 (85%) */
    const val DEFAULT_FLOATING_ALPHA = 85
    const val MIN_FLOATING_ALPHA = 20
    const val MAX_FLOATING_ALPHA = 100

    /** 悬浮窗最大币种数量 */
    const val MAX_FLOATING_COINS = 10

    /** 默认自动刷新间隔，对齐 python REFRESH_MAP 的 10s 档 */
    const val DEFAULT_REFRESH_MS = 10_000L
    /** 默认列表字体缩放百分比 (100%) */
    const val DEFAULT_FONT_SCALE = 100
    const val MIN_FONT_SCALE = 100
    const val MAX_FONT_SCALE = 190

    /** 默认图表字体缩放百分比 (100%) */
    const val DEFAULT_CHART_FONT_SCALE = 100
    const val MIN_CHART_FONT_SCALE = 100
    const val MAX_CHART_FONT_SCALE = 150

    // ---------- 列表项字体缩放 ----------

    /** 返回字体缩放百分比（100% ~ 190%） */
    fun getFontScalePct(ctx: Context): Int =
        sp(ctx).getInt(KEY_FONT_SCALE, DEFAULT_FONT_SCALE).coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)

    /** 返回字体缩放倍率（1.0f ~ 1.9f） */
    fun getFontScale(ctx: Context): Float =
        getFontScalePct(ctx) / 100f

    fun saveFontScalePct(ctx: Context, pct: Int) {
        val safePct = pct.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
        sp(ctx).edit().putInt(KEY_FONT_SCALE, safePct).apply()
    }

    // ---------- 图表字体缩放 ----------

    /** 返回图表字体缩放百分比（100% ~ 150%） */
    fun getChartFontScalePct(ctx: Context): Int =
        sp(ctx).getInt(KEY_CHART_FONT_SCALE, DEFAULT_CHART_FONT_SCALE).coerceIn(MIN_CHART_FONT_SCALE, MAX_CHART_FONT_SCALE)

    /** 返回图表字体缩放倍率（1.0f ~ 1.5f） */
    fun getChartFontScale(ctx: Context): Float =
        getChartFontScalePct(ctx) / 100f

    fun saveChartFontScalePct(ctx: Context, pct: Int) {
        val safePct = pct.coerceIn(MIN_CHART_FONT_SCALE, MAX_CHART_FONT_SCALE)
        sp(ctx).edit().putInt(KEY_CHART_FONT_SCALE, safePct).apply()
    }

    // ---------- 涨跌配色 ----------

    /** true = 绿涨红跌（欧美惯例）；false = 红涨绿跌（中国惯例，默认） */
    fun getUpIsGreen(ctx: Context): Boolean =
        sp(ctx).getBoolean(KEY_UP_IS_GREEN, false)

    fun saveUpIsGreen(ctx: Context, upIsGreen: Boolean) {
        sp(ctx).edit().putBoolean(KEY_UP_IS_GREEN, upIsGreen).apply()
        Palette.invalidate()
    }

    // ---------- 自动刷新间隔 ----------

    /** 自动刷新间隔毫秒；返回 0 表示关闭自动刷新（只在下拉时手动刷新） */
    fun getRefreshMs(ctx: Context): Long =
        sp(ctx).getLong(KEY_REFRESH_MS, DEFAULT_REFRESH_MS)

    fun saveRefreshMs(ctx: Context, ms: Long) {
        sp(ctx).edit().putLong(KEY_REFRESH_MS, ms).apply()
    }

    // ---------- WebSocket 实时行情 ----------

    /**
     * 实时行情的生效范围；默认 [RtScope.OFF]（保持原有轮询行为）。
     *
     * 开启后对应页面不再受 [getRefreshMs] 的间隔限制，改由交易所推送驱动；
     * 但当前源不支持 WS 或连接断开时，页面会自动退回按间隔轮询。
     */
    fun getRtScope(ctx: Context): RtScope =
        RtScope.fromKey(sp(ctx).getString(KEY_RT_SCOPE, RtScope.OFF.key))

    fun saveRtScope(ctx: Context, scope: RtScope) {
        sp(ctx).edit().putString(KEY_RT_SCOPE, scope.key).apply()
    }

    // ---------- 屏幕方向 ----------
    /**
     * 存的是 [ActivityInfo] 的 SCREEN_ORIENTATION_* 常量值。
     * 存框架常量而非自定义枚举，可以直接交给 setRequestedOrientation。
     */
    fun getOrientation(ctx: Context): Int =
        sp(ctx).getInt(KEY_ORIENTATION, DEFAULT_ORIENTATION)

    fun saveOrientation(ctx: Context, value: Int) {
        sp(ctx).edit().putInt(KEY_ORIENTATION, value).apply()
    }

    /** 默认竖屏 */
    val DEFAULT_ORIENTATION = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

    // ---------- 屏幕常亮 ----------
    fun getKeepScreenOn(ctx: Context): Boolean =
        sp(ctx).getBoolean(KEY_KEEP_SCREEN_ON, false)

    fun saveKeepScreenOn(ctx: Context, enabled: Boolean) {
        sp(ctx).edit().putBoolean(KEY_KEEP_SCREEN_ON, enabled).apply()
    }

    // ---------- 行情源 ----------

    /** 当前选择的源 id；默认 [Markets.AUTO]（自动选择可用源） */
    fun getSourceId(ctx: Context): String =
        sp(ctx).getString(KEY_SOURCE, Markets.AUTO) ?: Markets.AUTO

    fun saveSourceId(ctx: Context, id: String) {
        sp(ctx).edit().putString(KEY_SOURCE, id).apply()
        Markets.resetPreferred()
    }

    /**
     * 自动模式下上次探测成功的源。
     * 必须持久化：否则每次冷启动都要从列表第一个开始重试，
     * 在首选源被墙的网络上会先卡满一个连接超时才切换。
     */
    fun getPreferredId(ctx: Context): String? =
        sp(ctx).getString(KEY_PREFERRED, null)

    fun savePreferredId(ctx: Context, id: String?) {
        sp(ctx).edit().apply {
            if (id == null) remove(KEY_PREFERRED) else putString(KEY_PREFERRED, id)
        }.apply()
    }

    // ---------- 自选列表 ----------

    /** 读取自选；首次启动返回预置主流币，数据损坏时返回空列表 */
    fun getWatchlist(ctx: Context): MutableList<String> {
        val raw = sp(ctx).getString(KEY_WATCH, null) ?: return defaultWatchlist()

        val out = ArrayList<String>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val s = arr.optString(i)
                // 去重，并兼容历史上可能写入过的其它分隔格式
                val norm = Symbols.expand(s) ?: continue
                if (!out.contains(norm)) out.add(norm)
            }
        } catch (_: Exception) {
            // 数据损坏：返回空列表让用户重新添加。
            // 这里不能回退到默认值，否则用户主动删空的自选会复活。
        }
        return out
    }

    fun saveWatchlist(ctx: Context, list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        sp(ctx).edit().putString(KEY_WATCH, arr.toString()).apply()
    }

    /** 参考 python COMMON_PAIRS：首启预置主流币 */
    private fun defaultWatchlist(): MutableList<String> = mutableListOf(
        "BTC-USDT", "ETH-USDT", "SOL-USDT", "BNB-USDT", "XRP-USDT", "DOGE-USDT"
    )

    // ---------- 悬浮窗 ----------

    /** 悬浮窗是否开启 */
    fun getFloatingEnabled(ctx: Context): Boolean =
        sp(ctx).getBoolean(KEY_FLOATING_ENABLED, false)

    fun saveFloatingEnabled(ctx: Context, enabled: Boolean) {
        sp(ctx).edit().putBoolean(KEY_FLOATING_ENABLED, enabled).apply()
    }

    /** 读取悬浮窗展示币种（1~10个） */
    fun getFloatingSymbols(ctx: Context): MutableList<String> {
        val raw = sp(ctx).getString(KEY_FLOATING_SYMBOLS, null)
        if (raw != null) {
            val out = ArrayList<String>()
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i)
                    val norm = Symbols.expand(s) ?: continue
                    if (!out.contains(norm)) out.add(norm)
                }
            } catch (_: Exception) {}
            if (out.isNotEmpty()) {
                return out.take(MAX_FLOATING_COINS).toMutableList()
            }
        }
        // 默认取自选列表的前 3 个币种，若自选为空则默认 BTC/ETH/DOGE
        val watch = getWatchlist(ctx)
        val defaultList = if (watch.isNotEmpty()) watch.take(3) else listOf("BTC-USDT", "ETH-USDT", "DOGE-USDT")
        return defaultList.toMutableList()
    }

    fun saveFloatingSymbols(ctx: Context, list: List<String>) {
        val safeList = list.take(MAX_FLOATING_COINS)
        val arr = JSONArray()
        safeList.forEach { arr.put(it) }
        sp(ctx).edit().putString(KEY_FLOATING_SYMBOLS, arr.toString()).apply()
    }

    /** 返回悬浮窗不透明度百分比 (20% ~ 100%) */
    fun getFloatingAlphaPct(ctx: Context): Int =
        sp(ctx).getInt(KEY_FLOATING_ALPHA, DEFAULT_FLOATING_ALPHA).coerceIn(MIN_FLOATING_ALPHA, MAX_FLOATING_ALPHA)

    /** 返回悬浮窗不透明度浮点数值 (0.2f ~ 1.0f) */
    fun getFloatingAlpha(ctx: Context): Float =
        getFloatingAlphaPct(ctx) / 100f

    fun saveFloatingAlphaPct(ctx: Context, pct: Int) {
        val safePct = pct.coerceIn(MIN_FLOATING_ALPHA, MAX_FLOATING_ALPHA)
        sp(ctx).edit().putInt(KEY_FLOATING_ALPHA, safePct).apply()
    }

    /** 悬浮窗记忆坐标 (X, Y) */
    fun getFloatingPosition(ctx: Context): Pair<Int, Int>? {
        val p = sp(ctx)
        if (!p.contains(KEY_FLOATING_X) || !p.contains(KEY_FLOATING_Y)) return null
        return Pair(p.getInt(KEY_FLOATING_X, 0), p.getInt(KEY_FLOATING_Y, 100))
    }

    fun saveFloatingPosition(ctx: Context, x: Int, y: Int) {
        sp(ctx).edit().putInt(KEY_FLOATING_X, x).putInt(KEY_FLOATING_Y, y).apply()
    }

    // ---------- 技术指标配置与开关 ----------

    private const val KEY_SHOW_MA1 = "ind_show_ma1"
    private const val KEY_SHOW_MA2 = "ind_show_ma2"
    private const val KEY_SHOW_MA3 = "ind_show_ma3"
    private const val KEY_SHOW_BOLL = "ind_show_boll"
    private const val KEY_SHOW_TURTLE = "ind_show_turtle"
    private const val KEY_SUB_INDICATOR = "ind_sub_type"

    private const val KEY_MA1_PERIOD = "ind_ma1_period"
    private const val KEY_MA2_PERIOD = "ind_ma2_period"
    private const val KEY_MA3_PERIOD = "ind_ma3_period"

    private const val KEY_BOLL_N = "ind_boll_n"
    private const val KEY_BOLL_K = "ind_boll_k"

    private const val KEY_TURTLE_ENTRY = "ind_turtle_entry"
    private const val KEY_TURTLE_EXIT = "ind_turtle_exit"
    private const val KEY_TURTLE_ATR = "ind_turtle_atr"

    private const val KEY_MACD_FAST = "ind_macd_fast"
    private const val KEY_MACD_SLOW = "ind_macd_slow"
    private const val KEY_MACD_SIGNAL = "ind_macd_signal"

    private const val KEY_RSI1_PERIOD = "ind_rsi1_period"
    private const val KEY_RSI2_PERIOD = "ind_rsi2_period"
    private const val KEY_RSI3_PERIOD = "ind_rsi3_period"

    private const val KEY_KDJ_N = "ind_kdj_n"
    private const val KEY_KDJ_M1 = "ind_kdj_m1"
    private const val KEY_KDJ_M2 = "ind_kdj_m2"

    // 默认值
    const val DEFAULT_MA1 = 5
    const val DEFAULT_MA2 = 10
    const val DEFAULT_MA3 = 20

    const val DEFAULT_BOLL_N = 20
    const val DEFAULT_BOLL_K = 2.0f

    const val DEFAULT_TURTLE_ENTRY = 20
    const val DEFAULT_TURTLE_EXIT = 10
    const val DEFAULT_TURTLE_ATR = 20

    const val DEFAULT_MACD_FAST = 12
    const val DEFAULT_MACD_SLOW = 26
    const val DEFAULT_MACD_SIGNAL = 9

    const val DEFAULT_RSI1 = 6
    const val DEFAULT_RSI2 = 12
    const val DEFAULT_RSI3 = 24

    const val DEFAULT_KDJ_N = 9
    const val DEFAULT_KDJ_M1 = 3
    const val DEFAULT_KDJ_M2 = 3

    fun getShowMa1(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_SHOW_MA1, true)
    fun setShowMa1(ctx: Context, show: Boolean) = sp(ctx).edit().putBoolean(KEY_SHOW_MA1, show).apply()

    fun getShowMa2(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_SHOW_MA2, true)
    fun setShowMa2(ctx: Context, show: Boolean) = sp(ctx).edit().putBoolean(KEY_SHOW_MA2, show).apply()

    fun getShowMa3(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_SHOW_MA3, true)
    fun setShowMa3(ctx: Context, show: Boolean) = sp(ctx).edit().putBoolean(KEY_SHOW_MA3, show).apply()

    fun getShowBoll(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_SHOW_BOLL, false)
    fun setShowBoll(ctx: Context, show: Boolean) = sp(ctx).edit().putBoolean(KEY_SHOW_BOLL, show).apply()

    fun getShowTurtle(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_SHOW_TURTLE, false)
    fun setShowTurtle(ctx: Context, show: Boolean) = sp(ctx).edit().putBoolean(KEY_SHOW_TURTLE, show).apply()

    /** 当前副图指标："VOL", "MACD", "RSI", "KDJ", "OFF" */
    fun getSubIndicator(ctx: Context): String = sp(ctx).getString(KEY_SUB_INDICATOR, "VOL") ?: "VOL"
    fun setSubIndicator(ctx: Context, type: String) = sp(ctx).edit().putString(KEY_SUB_INDICATOR, type).apply()

    // 周期参数 Getter / Setter
    fun getMa1Period(ctx: Context): Int = sp(ctx).getInt(KEY_MA1_PERIOD, DEFAULT_MA1)
    fun getMa2Period(ctx: Context): Int = sp(ctx).getInt(KEY_MA2_PERIOD, DEFAULT_MA2)
    fun getMa3Period(ctx: Context): Int = sp(ctx).getInt(KEY_MA3_PERIOD, DEFAULT_MA3)

    fun getBollN(ctx: Context): Int = sp(ctx).getInt(KEY_BOLL_N, DEFAULT_BOLL_N)
    fun getBollK(ctx: Context): Float = sp(ctx).getFloat(KEY_BOLL_K, DEFAULT_BOLL_K)

    fun getTurtleEntry(ctx: Context): Int = sp(ctx).getInt(KEY_TURTLE_ENTRY, DEFAULT_TURTLE_ENTRY)
    fun getTurtleExit(ctx: Context): Int = sp(ctx).getInt(KEY_TURTLE_EXIT, DEFAULT_TURTLE_EXIT)
    fun getTurtleAtr(ctx: Context): Int = sp(ctx).getInt(KEY_TURTLE_ATR, DEFAULT_TURTLE_ATR)

    fun getMacdFast(ctx: Context): Int = sp(ctx).getInt(KEY_MACD_FAST, DEFAULT_MACD_FAST)
    fun getMacdSlow(ctx: Context): Int = sp(ctx).getInt(KEY_MACD_SLOW, DEFAULT_MACD_SLOW)
    fun getMacdSignal(ctx: Context): Int = sp(ctx).getInt(KEY_MACD_SIGNAL, DEFAULT_MACD_SIGNAL)

    fun getRsi1Period(ctx: Context): Int = sp(ctx).getInt(KEY_RSI1_PERIOD, DEFAULT_RSI1)
    fun getRsi2Period(ctx: Context): Int = sp(ctx).getInt(KEY_RSI2_PERIOD, DEFAULT_RSI2)
    fun getRsi3Period(ctx: Context): Int = sp(ctx).getInt(KEY_RSI3_PERIOD, DEFAULT_RSI3)

    fun getKdjN(ctx: Context): Int = sp(ctx).getInt(KEY_KDJ_N, DEFAULT_KDJ_N)
    fun getKdjM1(ctx: Context): Int = sp(ctx).getInt(KEY_KDJ_M1, DEFAULT_KDJ_M1)
    fun getKdjM2(ctx: Context): Int = sp(ctx).getInt(KEY_KDJ_M2, DEFAULT_KDJ_M2)

    fun saveIndicatorParams(
        ctx: Context,
        ma1: Int, ma2: Int, ma3: Int,
        bollN: Int, bollK: Float,
        turtleEntry: Int, turtleExit: Int, turtleAtr: Int,
        macdFast: Int, macdSlow: Int, macdSig: Int,
        rsi1: Int, rsi2: Int, rsi3: Int,
        kdjN: Int, kdjM1: Int, kdjM2: Int
    ) {
        sp(ctx).edit()
            .putInt(KEY_MA1_PERIOD, ma1.coerceIn(1, 200))
            .putInt(KEY_MA2_PERIOD, ma2.coerceIn(1, 200))
            .putInt(KEY_MA3_PERIOD, ma3.coerceIn(1, 200))
            .putInt(KEY_BOLL_N, bollN.coerceIn(2, 100))
            .putFloat(KEY_BOLL_K, bollK.coerceIn(0.5f, 10.0f))
            .putInt(KEY_TURTLE_ENTRY, turtleEntry.coerceIn(2, 200))
            .putInt(KEY_TURTLE_EXIT, turtleExit.coerceIn(2, 200))
            .putInt(KEY_TURTLE_ATR, turtleAtr.coerceIn(2, 100))
            .putInt(KEY_MACD_FAST, macdFast.coerceIn(1, 100))
            .putInt(KEY_MACD_SLOW, macdSlow.coerceIn(1, 100))
            .putInt(KEY_MACD_SIGNAL, macdSig.coerceIn(1, 100))
            .putInt(KEY_RSI1_PERIOD, rsi1.coerceIn(1, 100))
            .putInt(KEY_RSI2_PERIOD, rsi2.coerceIn(1, 100))
            .putInt(KEY_RSI3_PERIOD, rsi3.coerceIn(1, 100))
            .putInt(KEY_KDJ_N, kdjN.coerceIn(1, 100))
            .putInt(KEY_KDJ_M1, kdjM1.coerceIn(1, 100))
            .putInt(KEY_KDJ_M2, kdjM2.coerceIn(1, 100))
            .apply()
    }

    fun resetIndicatorParams(ctx: Context) {
        saveIndicatorParams(
            ctx,
            DEFAULT_MA1, DEFAULT_MA2, DEFAULT_MA3,
            DEFAULT_BOLL_N, DEFAULT_BOLL_K,
            DEFAULT_TURTLE_ENTRY, DEFAULT_TURTLE_EXIT, DEFAULT_TURTLE_ATR,
            DEFAULT_MACD_FAST, DEFAULT_MACD_SLOW, DEFAULT_MACD_SIGNAL,
            DEFAULT_RSI1, DEFAULT_RSI2, DEFAULT_RSI3,
            DEFAULT_KDJ_N, DEFAULT_KDJ_M1, DEFAULT_KDJ_M2
        )
    }

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
