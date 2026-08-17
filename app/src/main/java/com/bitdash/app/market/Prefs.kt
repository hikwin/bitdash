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
    private const val KEY_UP_IS_GREEN = "up_is_green"
    private const val KEY_FONT_SCALE = "font_scale_pct"
    private const val KEY_CHART_FONT_SCALE = "chart_font_scale_pct"
    private const val KEY_RT_SCOPE = "rt_scope"
    private const val KEY_FLOATING_ENABLED = "floating_enabled"
    private const val KEY_FLOATING_SYMBOLS = "floating_symbols"
    private const val KEY_FLOATING_ALPHA = "floating_alpha_pct"
    private const val KEY_FLOATING_X = "floating_x"
    private const val KEY_FLOATING_Y = "floating_y"

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

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
