package com.bitdash.app.market

import android.content.Context

/**
 * 涨跌配色的唯一取色入口。
 *
 * 两种惯例（参考 python okx_gui 的 _up_color/_down_color）：
 * - 红涨绿跌：中国大陆惯例，本 App 默认
 * - 绿涨红跌：欧美惯例
 *
 * 注意：这里只管「涨/跌」语义。表示成功/失败的绿红（例如选源弹窗的连通性）
 * 不属于涨跌语义，绝不能跟着这里翻转。
 */
object Palette {

    /** 红 */
    private const val RED = 0xFFEF4444.toInt()

    /** 绿 */
    private const val GREEN = 0xFF22C55E.toInt()

    /**
     * 内存缓存，避免在 onBindViewHolder / onDraw 这类高频路径上读 SharedPreferences。
     * null 表示还没从磁盘载入过。
     */
    @Volatile
    private var upIsGreenCache: Boolean? = null

    /** 涨是否用绿色；false 为默认的红涨绿跌 */
    fun upIsGreen(ctx: Context): Boolean {
        upIsGreenCache?.let { return it }
        val v = Prefs.getUpIsGreen(ctx)
        upIsGreenCache = v
        return v
    }

    fun up(ctx: Context): Int = if (upIsGreen(ctx)) GREEN else RED

    fun down(ctx: Context): Int = if (upIsGreen(ctx)) RED else GREEN

    /** 按涨跌方向取色；[delta] >= 0 视为涨 */
    fun byDelta(ctx: Context, delta: Double): Int =
        if (delta >= 0) up(ctx) else down(ctx)

    /** 用户改配色后调用，让缓存失效 */
    fun invalidate() {
        upIsGreenCache = null
    }
}
