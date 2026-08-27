package com.bitdash.app.market

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 自定义 Pine Script (v5) 实体模型与持久化管理器
 */
data class PineScript(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var code: String,
    var enabled: Boolean = true,
    var overlay: Boolean = true,      // true: 绘制在主图, false: 绘制在副图
    var isStrategy: Boolean = false,  // true: strategy, false: indicator
    var spotOnly: Boolean = false     // true: 现货模式 (仅做多，不展示空单与做空开仓)
)

/** 单个 plot 曲线数据 */
data class PinePlotLine(
    val title: String,
    val series: FloatArray,
    val color: Int,
    val lineWidth: Float = 1.2f
)

/** 单个 plotshape 标记数据 (买卖信号徽标) */
data class PinePlotShape(
    val title: String,
    val isBuy: Boolean,        // true = 买入(绿色向上), false = 卖出(红色向下)
    val text: String,
    val color: Int,
    val indices: BooleanArray  // 各 K 线是否触发
)

/** 单个 fill 填充带 */
data class PineFill(
    val title: String,
    val line1Index: Int,
    val line2Index: Int,
    val color: Int
)

/** 脚本时序执行结果 */
data class PineExecutionResult(
    val scriptId: String,
    val scriptName: String,
    val overlay: Boolean,
    val isStrategy: Boolean,
    val plotLines: List<PinePlotLine> = emptyList(),
    val plotShapes: List<PinePlotShape> = emptyList(),
    val fills: List<PineFill> = emptyList(),
    val success: Boolean = true,
    val errorMessage: String? = null
)

object PineScriptManager {

    private const val PREFS_NAME = "bitdash_pine_scripts"
    private const val KEY_SCRIPTS_JSON = "custom_pine_scripts_v5"

    /** 获取所有用户脚本列表 (若为空则初始化预设模板) */
    fun getScripts(context: Context): MutableList<PineScript> {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = sp.getString(KEY_SCRIPTS_JSON, null)
        if (jsonStr.isNullOrEmpty()) {
            val defaults = getDefaultTemplates()
            saveScripts(context, defaults)
            return defaults
        }

        val list = mutableListOf<PineScript>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.optString("id", UUID.randomUUID().toString())
                if (id == "tpl_boll_break") continue // 移除旧版布林带突破模板
                list.add(
                    PineScript(
                        id = id,
                        name = obj.optString("name", "未命名指标"),
                        code = obj.optString("code", ""),
                        enabled = obj.optBoolean("enabled", false),
                        overlay = obj.optBoolean("overlay", true),
                        isStrategy = obj.optBoolean("isStrategy", false),
                        spotOnly = obj.optBoolean("spotOnly", false)
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    /** 保存脚本列表 */
    fun saveScripts(context: Context, list: List<PineScript>) {
        val array = JSONArray()
        for (item in list) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("name", item.name)
                put("code", item.code)
                put("enabled", item.enabled)
                put("overlay", item.overlay)
                put("isStrategy", item.isStrategy)
                put("spotOnly", item.spotOnly)
            }
            array.put(obj)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SCRIPTS_JSON, array.toString())
            .apply()
    }

    /** 保存或更新单个脚本 */
    fun saveOrUpdate(context: Context, script: PineScript) {
        val list = getScripts(context)
        val idx = list.indexOfFirst { it.id == script.id }
        if (idx >= 0) {
            list[idx] = script
        } else {
            list.add(0, script)
        }
        saveScripts(context, list)
    }

    /** 切换脚本启用状态 */
    fun toggleEnabled(context: Context, scriptId: String, enabled: Boolean) {
        val list = getScripts(context)
        val item = list.find { it.id == scriptId }
        if (item != null) {
            item.enabled = enabled
            saveScripts(context, list)
        }
    }

    /** 删除指定脚本 */
    fun deleteScript(context: Context, scriptId: String) {
        val list = getScripts(context)
        list.removeAll { it.id == scriptId }
        saveScripts(context, list)
    }

    /** 内置经典 Pine Script v5 模板 */
    fun getDefaultTemplates(): MutableList<PineScript> {
        return mutableListOf(
            PineScript(
                id = "tpl_ema_cross",
                name = "双均线交叉 (EMA 9/21)",
                enabled = false,
                overlay = true,
                isStrategy = true,
                code = """
//@version=5
strategy("双均线交叉策略", overlay=true)

fast_len = input.int(9, "快线周期")
slow_len = input.int(21, "慢线周期")

ema_fast = ta.ema(close, fast_len)
ema_slow = ta.ema(close, slow_len)

long_signal = ta.crossover(ema_fast, ema_slow)
short_signal = ta.crossunder(ema_fast, ema_slow)

plot(ema_fast, "EMA Fast", color=color.yellow, linewidth=2)
plot(ema_slow, "EMA Slow", color=color.purple, linewidth=2)

plotshape(long_signal, "金叉做多", shape.triangleup, location.belowbar, color.green, text="做多")
plotshape(short_signal, "死叉做空", shape.triangledown, location.abovebar, color.red, text="做空")
""".trimIndent()
            ),
            PineScript(
                id = "tpl_rsi_cross",
                name = "RSI 超买超卖指标",
                enabled = false,
                overlay = false,
                isStrategy = false,
                code = """
//@version=5
indicator("RSI 超买超卖", overlay=false)

rsi_len = input.int(14, "RSI 周期")
rsi_val = ta.rsi(close, rsi_len)

plot(rsi_val, "RSI", color=color.purple, linewidth=2)
plot(70, "超买线", color=color.red, linewidth=1)
plot(30, "超卖线", color=color.green, linewidth=1)
""".trimIndent()
            )
        )
    }
}
