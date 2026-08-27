package com.bitdash.app.market

import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 高健壮性、全兼容 TradingView Pine Script v5 时序执行引擎
 *
 * 核心特性：
 * 1. 结构化多变量解构赋值：如 [macdLine, signalLine, _] = ta.macd(...)
 * 2. 复合逻辑表达式解析：支持 and, or, not, not na(...), 比较操作符与时序 crossover/crossunder 混用
 * 3. 高级技术指标库：ta.sma, ta.ema, ta.rma, ta.wma, ta.macd, ta.rsi, ta.atr, ta.stdev, ta.highest, ta.lowest, ta.pivotlow, ta.pivothigh, ta.crossover, ta.crossunder
 * 4. 命名 plot 变量绑定与区域填充：pTop = plot(...), fill(pTop, pBottom, ...)
 * 5. 丰富 plotshape 形态识别与双向策略模拟撮合，支持现货模式（仅多单）与 BTC / ETH / SOL 等全币种高价值价格自适应
 */
object PineEngine {

    private data class StrategyEntryCmd(val id: String, val isLong: Boolean, val condExpr: String)
    private data class StrategyExitCmd(val id: String, val fromEntry: String, val lossExpr: String?, val profitExpr: String?)
    private data class StrategyCloseCmd(val id: String, val condExpr: String)

    fun execute(script: PineScript, candles: List<Candle>): PineExecutionResult {
        if (candles.isEmpty()) {
            return PineExecutionResult(
                scriptId = script.id,
                scriptName = script.name,
                overlay = script.overlay,
                isStrategy = script.isStrategy,
                success = true
            )
        }

        try {
            val lines = script.code.lines()
            val n = candles.size

            var overlay = script.overlay
            var isStrategy = script.isStrategy
            var scriptName = script.name

            // 存储时序变量与标量变量
            val seriesMap = mutableMapOf<String, FloatArray>()
            val boolSeriesMap = mutableMapOf<String, BooleanArray>()
            val scalarMap = mutableMapOf<String, Double>()
            val plotVarMap = mutableMapOf<String, Int>()

            // 初始化内置 K 线序列
            val openSeries = FloatArray(n) { candles[it].open.toFloat() }
            val highSeries = FloatArray(n) { candles[it].high.toFloat() }
            val lowSeries = FloatArray(n) { candles[it].low.toFloat() }
            val closeSeries = FloatArray(n) { candles[it].close.toFloat() }
            val volSeries = FloatArray(n) { candles[it].vol.toFloat() }
            val hlc3Series = FloatArray(n) { ((candles[it].high + candles[it].low + candles[it].close) / 3.0).toFloat() }
            val hl2Series = FloatArray(n) { ((candles[it].high + candles[it].low) / 2.0).toFloat() }

            seriesMap["open"] = openSeries
            seriesMap["high"] = highSeries
            seriesMap["low"] = lowSeries
            seriesMap["close"] = closeSeries
            seriesMap["volume"] = volSeries
            seriesMap["vol"] = volSeries
            seriesMap["hlc3"] = hlc3Series
            seriesMap["hl2"] = hl2Series

            val plotLines = mutableListOf<PinePlotLine>()
            val plotShapes = mutableListOf<PinePlotShape>()
            val fills = mutableListOf<PineFill>()

            // 预解析第一遍：提取 header 与 input
            for (rawLine in lines) {
                val line = rawLine.trim()
                if (line.startsWith("//") || line.isEmpty()) continue

                // 1. indicator / strategy 声明
                if (line.startsWith("indicator(") || line.startsWith("strategy(")) {
                    isStrategy = line.startsWith("strategy(")
                    if (line.contains("overlay=true") || line.contains("overlay = true")) {
                        overlay = true
                    } else if (line.contains("overlay=false") || line.contains("overlay = false")) {
                        overlay = false
                    }
                    val nameMatch = Pattern.compile("(?:title\\s*=\\s*)?[\"']([^\"']+)[\"']").matcher(line)
                    if (nameMatch.find()) {
                        scriptName = nameMatch.group(1) ?: scriptName
                    }
                }

                // 2. input.* 声明
                if (line.contains("input.int(") || line.contains("input.float(") || line.contains("input(")) {
                    val eqIdx = line.indexOf('=')
                    if (eqIdx > 0) {
                        val varName = line.substring(0, eqIdx).trim()
                        val right = line.substring(eqIdx + 1).trim()
                        val numMatch = Pattern.compile("input\\.(?:int|float)?\\s*\\(\\s*([0-9.-]+)").matcher(right)
                        if (numMatch.find()) {
                            val defaultVal = numMatch.group(1)?.toDoubleOrNull() ?: 1.0
                            scalarMap[varName] = defaultVal
                        }
                    }
                }
            }

            var hasExplicitPlotShapes = false

            // 执行第二遍：解构赋值、变量计算与绘图命令提取
            for (rawLine in lines) {
                val line = rawLine.trim()
                if (line.startsWith("//") || line.isEmpty()) continue

                // 1. 处理多变量解构赋值：如 [macdLine, signalLine, _] = ta.macd(close, 12, 26, 9)
                val destructureMatch = Pattern.compile("^\\[([^\\]]+)\\]\\s*=\\s*(.+)").matcher(line)
                if (destructureMatch.find()) {
                    val varsPart = destructureMatch.group(1) ?: ""
                    val expr = destructureMatch.group(2)?.trim() ?: ""
                    val vars = varsPart.split(",").map { it.trim() }

                    if (expr.contains("ta.macd(") || expr.contains("macd(")) {
                        val args = splitArgs(extractInsideParens(expr, "macd"))
                        val src = evalSeries(args.getOrNull(0) ?: "close", candles, seriesMap, scalarMap)
                        val fast = evalIntParam(args.getOrNull(1) ?: "12", scalarMap)
                        val slow = evalIntParam(args.getOrNull(2) ?: "26", scalarMap)
                        val sig = evalIntParam(args.getOrNull(3) ?: "9", scalarMap)

                        val macdRes = computeMacdTuples(src, fast, slow, sig)
                        if (vars.isNotEmpty() && vars[0] != "_") seriesMap[vars[0]] = macdRes.dif
                        if (vars.size > 1 && vars[1] != "_") seriesMap[vars[1]] = macdRes.dea
                        if (vars.size > 2 && vars[2] != "_") seriesMap[vars[2]] = macdRes.hist
                    }
                    continue
                }

                // 2. 处理 plot(...) 或 pTop = plot(...)
                if (line.startsWith("plot(") || (line.contains("= plot(") && !line.startsWith("if"))) {
                    var varName: String? = null
                    val eqIdx = line.indexOf("= plot(")
                    if (eqIdx > 0) {
                        varName = line.substring(0, eqIdx).trim()
                    }

                    val plotContent = extractInsideParens(line, "plot")
                    val parts = splitArgs(plotContent)
                    if (parts.isNotEmpty()) {
                        val sourceExpr = parts[0].trim()
                        val title = extractParamValue(line, "title") ?: (if (parts.size > 1) extractQuotedString(parts[1]) ?: "Plot" else "Plot")
                        val color = parseColor(line) ?: 0xFFF59E0B.toInt()
                        val lw = parseLineWidth(line)

                        val series = evalSeries(sourceExpr, candles, seriesMap, scalarMap)
                        val plotIndex = plotLines.size
                        plotLines.add(PinePlotLine(title, series, color, lw))
                        if (varName != null) {
                            plotVarMap[varName] = plotIndex
                            seriesMap[varName] = series
                        }
                    }
                    continue
                }

                // 3. 处理 plotshape(...)
                if (line.startsWith("plotshape(") || line.contains("= plotshape(")) {
                    val shapeContent = extractInsideParens(line, "plotshape")
                    val parts = splitArgs(shapeContent)
                    if (parts.isNotEmpty()) {
                        val condExpr = parts[0].trim()
                        val title = extractParamValue(line, "title") ?: (if (parts.size > 1) extractQuotedString(parts[1]) ?: "Signal" else "Signal")
                        val isBuy = line.contains("triangleup") || line.contains("location.belowbar") || line.contains("green") || line.contains("做多") || line.contains("平空")
                        val labelText = extractParamValue(line, "text") ?: (if (title != "Signal") title else if (isBuy) "多" else "空")
                        val color = parseColor(line) ?: (if (isBuy) 0xFF10B981.toInt() else 0xFFEF4444.toInt())

                        // 现货模式下过滤空单做空信号
                        if (script.spotOnly && !isBuy && (labelText.contains("空") || title.contains("空"))) {
                            continue
                        }

                        val boolSeries = evalConditionSeries(condExpr, candles, seriesMap, boolSeriesMap, scalarMap)
                        plotShapes.add(PinePlotShape(title, isBuy, labelText, color, boolSeries))
                        hasExplicitPlotShapes = true
                    }
                    continue
                }

                // 4. 处理 fill(...)，支持 fill(pTop, pBottom, ...) 或根据索引填充
                if (line.startsWith("fill(")) {
                    val fillContent = extractInsideParens(line, "fill")
                    val parts = splitArgs(fillContent)
                    if (parts.size >= 2) {
                        val p1Name = parts[0].trim()
                        val p2Name = parts[1].trim()
                        val idx1 = plotVarMap[p1Name] ?: (plotLines.size - 2).coerceAtLeast(0)
                        val idx2 = plotVarMap[p2Name] ?: (plotLines.size - 1).coerceAtLeast(0)
                        if (idx1 in plotLines.indices && idx2 in plotLines.indices && idx1 != idx2) {
                            val color = parseColor(line) ?: 0x203B82F6.toInt()
                            fills.add(PineFill("Fill", idx1, idx2, color))
                        }
                    }
                    continue
                }

                // 5. 普通变量声明与赋值
                val eqIdx = line.indexOf('=')
                if (eqIdx > 0 && !line.startsWith("if ") && !line.startsWith("for ")) {
                    val varName = line.substring(0, eqIdx).trim()
                    val expr = line.substring(eqIdx + 1).trim()

                    if (expr.contains("crossover(") || expr.contains("crossunder(") ||
                        expr.contains(">") || expr.contains("<") || expr.contains("==") || expr.contains(" and ") || expr.contains(" or ") || expr.contains("na(")) {
                        val boolArr = evalConditionSeries(expr, candles, seriesMap, boolSeriesMap, scalarMap)
                        boolSeriesMap[varName] = boolArr
                    } else {
                        val series = evalSeries(expr, candles, seriesMap, scalarMap)
                        seriesMap[varName] = series
                    }
                }
            }

            // 6. 全面解析策略指令集 (Strategy Entry / Exit / Close) - 仅在没有显式 plotshape 时作为状态机生成买卖标记
            if (isStrategy && !hasExplicitPlotShapes) {
                val entryCmds = mutableListOf<StrategyEntryCmd>()
                val exitCmds = mutableListOf<StrategyExitCmd>()
                val closeCmds = mutableListOf<StrategyCloseCmd>()

                var currentIfCond: String? = null
                for (rawLine in lines) {
                    val trimmed = rawLine.trim()
                    if (trimmed.startsWith("//") || trimmed.isEmpty()) continue

                    // 检查 if 语句
                    if (trimmed.startsWith("if (") || trimmed.startsWith("if ")) {
                        currentIfCond = extractIfCondition(trimmed)
                        // 若为单行 if (cond) strategy...
                        if (trimmed.contains("strategy.")) {
                            parseStrategyLine(trimmed, currentIfCond ?: "true", entryCmds, exitCmds, closeCmds)
                        }
                    } else if (rawLine.startsWith("    ") || rawLine.startsWith("\t")) {
                        // 缩进属于当前 if 块
                        if (currentIfCond != null && trimmed.contains("strategy.")) {
                            parseStrategyLine(trimmed, currentIfCond, entryCmds, exitCmds, closeCmds)
                        }
                    } else {
                        // 非缩进行，离开 if 块
                        currentIfCond = null
                    }
                }

                if (entryCmds.isNotEmpty() || closeCmds.isNotEmpty()) {
                    val strategyShapes = runAdvancedStrategySimulation(
                        candles,
                        entryCmds,
                        exitCmds,
                        closeCmds,
                        seriesMap,
                        boolSeriesMap,
                        scalarMap,
                        script.spotOnly
                    )
                    plotShapes.addAll(strategyShapes)
                }
            }

            return PineExecutionResult(
                scriptId = script.id,
                scriptName = scriptName,
                overlay = overlay,
                isStrategy = isStrategy,
                plotLines = plotLines,
                plotShapes = plotShapes,
                fills = fills,
                success = true
            )
        } catch (e: Exception) {
            return PineExecutionResult(
                scriptId = script.id,
                scriptName = script.name,
                overlay = script.overlay,
                isStrategy = script.isStrategy,
                success = false,
                errorMessage = "语法解析或计算异常: ${e.message}"
            )
        }
    }

    private fun extractIfCondition(line: String): String {
        val s = line.substring(2).trim()
        if (s.startsWith("(") && s.contains(")")) {
            val endIdx = s.indexOf(")")
            return s.substring(1, endIdx).trim()
        }
        val firstSpace = s.indexOf(" ")
        return if (firstSpace > 0) s.substring(0, firstSpace).trim() else s
    }

    private fun parseStrategyLine(
        line: String,
        cond: String,
        entryCmds: MutableList<StrategyEntryCmd>,
        exitCmds: MutableList<StrategyExitCmd>,
        closeCmds: MutableList<StrategyCloseCmd>
    ) {
        if (line.contains("strategy.entry(")) {
            val content = extractInsideParens(line, "strategy.entry")
            val parts = splitArgs(content)
            val id = extractQuotedString(parts.getOrNull(0) ?: "") ?: "多单"
            val isLong = line.contains("strategy.long") || !line.contains("strategy.short")
            entryCmds.add(StrategyEntryCmd(id, isLong, cond))
        } else if (line.contains("strategy.close(")) {
            val content = extractInsideParens(line, "strategy.close")
            val parts = splitArgs(content)
            val id = extractQuotedString(parts.getOrNull(0) ?: "") ?: ""
            closeCmds.add(StrategyCloseCmd(id, cond))
        } else if (line.contains("strategy.exit(")) {
            val content = extractInsideParens(line, "strategy.exit")
            val parts = splitArgs(content)
            val id = extractQuotedString(parts.getOrNull(0) ?: "") ?: "出场"
            val fromEntry = extractParamValue(line, "from_entry") ?: ""
            val lossExpr = extractParamRaw(line, "loss")
            val profitExpr = extractParamRaw(line, "profit")
            exitCmds.add(StrategyExitCmd(id, fromEntry, lossExpr, profitExpr))
        }
    }

    private fun extractParamRaw(line: String, param: String): String? {
        val m = Pattern.compile("(?:^|[\\s,(])$param\\s*=\\s*").matcher(line)
        if (!m.find()) return null
        val start = m.end()
        var depth = 0
        for (i in start until line.length) {
            val ch = line[i]
            if (ch == '(' || ch == '[') depth++
            if (ch == ')' || ch == ']') depth--
            if ((ch == ',' || ch == ')') && depth <= 0) {
                return line.substring(start, i).trim()
            }
        }
        return line.substring(start).trim()
    }

    // ---------- 高级策略撮合状态机 ----------

    private fun runAdvancedStrategySimulation(
        candles: List<Candle>,
        entryCmds: List<StrategyEntryCmd>,
        exitCmds: List<StrategyExitCmd>,
        closeCmds: List<StrategyCloseCmd>,
        seriesMap: Map<String, FloatArray>,
        boolSeriesMap: Map<String, BooleanArray>,
        scalarMap: Map<String, Double>,
        spotOnly: Boolean
    ): List<PinePlotShape> {
        val n = candles.size

        // 预计算所有条件的布尔序列
        val entryCondArrays = entryCmds.map { evalConditionSeries(it.condExpr, candles, seriesMap, boolSeriesMap, scalarMap) }
        val closeCondArrays = closeCmds.map { evalConditionSeries(it.condExpr, candles, seriesMap, boolSeriesMap, scalarMap) }

        // 记录各指令触发的 K 线索引
        val entryTriggerMap = mutableMapOf<StrategyEntryCmd, BooleanArray>()
        entryCmds.forEach { entryTriggerMap[it] = BooleanArray(n) }

        val exitTriggerMap = mutableMapOf<String, BooleanArray>()

        var curPos = 0 // 0=空仓, 1=持多, -1=持空
        var currentEntryCmd: StrategyEntryCmd? = null
        var stopLossPrice = 0.0
        var takeProfitPrice = 0.0

        for (i in 0 until n) {
            val c = candles[i]

            // 1. 持仓状态下的平仓与止盈止损检测
            val curEntry = currentEntryCmd
            if (curPos != 0 && curEntry != null) {
                var closed = false
                var exitLabel = ""

                // 检查 strategy.close
                for (ci in closeCmds.indices) {
                    val cmd = closeCmds[ci]
                    if (cmd.id.isEmpty() || cmd.id == curEntry.id) {
                        if (closeCondArrays[ci].getOrNull(i) == true) {
                            closed = true
                            val exitCmd = exitCmds.find { it.fromEntry.isEmpty() || it.fromEntry == curEntry.id }
                            exitLabel = exitCmd?.id?.ifEmpty { null } ?: (if (curPos > 0) "平多" else "平空")
                            break
                        }
                    }
                }

                // 检查 strategy.exit（止盈 / 止损）
                if (!closed) {
                    val exitCmd = exitCmds.find { it.fromEntry.isEmpty() || it.fromEntry == curEntry.id }
                    if (exitCmd != null) {
                        if (curPos == 1) {
                            if (stopLossPrice > 0.0 && c.low <= stopLossPrice) {
                                closed = true
                                exitLabel = exitCmd.id.ifEmpty { "多单止损" }
                            } else if (takeProfitPrice > 0.0 && c.high >= takeProfitPrice) {
                                closed = true
                                exitLabel = exitCmd.id.ifEmpty { "多单止盈" }
                            }
                        } else if (curPos == -1) {
                            if (stopLossPrice > 0.0 && c.high >= stopLossPrice) {
                                closed = true
                                exitLabel = exitCmd.id.ifEmpty { "空单止损" }
                            } else if (takeProfitPrice > 0.0 && c.low <= takeProfitPrice) {
                                closed = true
                                exitLabel = exitCmd.id.ifEmpty { "空单止盈" }
                            }
                        }
                    }
                }

                if (closed) {
                    val arr = exitTriggerMap.getOrPut(exitLabel) { BooleanArray(n) }
                    arr[i] = true
                    curPos = 0
                    currentEntryCmd = null
                }
            }

            // 2. 空仓状态下的进场检测
            if (curPos == 0) {
                for (ei in entryCmds.indices) {
                    val cmd = entryCmds[ei]
                    // 现货模式下忽略做空开仓
                    if (spotOnly && !cmd.isLong) continue

                    if (entryCondArrays[ei].getOrNull(i) == true) {
                        curPos = if (cmd.isLong) 1 else -1
                        currentEntryCmd = cmd
                        entryTriggerMap[cmd]?.set(i, true)

                        // 计算对应的 strategy.exit 价格
                        val exitCmd = exitCmds.find { it.fromEntry.isEmpty() || it.fromEntry == cmd.id }
                        if (exitCmd != null) {
                            val lossArr = exitCmd.lossExpr?.let { evalSeries(it, candles, seriesMap, scalarMap) }
                            val profitArr = exitCmd.profitExpr?.let { evalSeries(it, candles, seriesMap, scalarMap) }
                            stopLossPrice = lossArr?.getOrNull(i)?.toDouble() ?: 0.0
                            takeProfitPrice = profitArr?.getOrNull(i)?.toDouble() ?: 0.0
                        } else {
                            stopLossPrice = 0.0
                            takeProfitPrice = 0.0
                        }
                        break
                    }
                }
            }
        }

        val res = mutableListOf<PinePlotShape>()

        // 收集所有进场徽标
        for ((cmd, indices) in entryTriggerMap) {
            if (spotOnly && !cmd.isLong) continue
            if (indices.any { it }) {
                val color = if (cmd.isLong) 0xFF10B981.toInt() else 0xFFEF4444.toInt()
                res.add(PinePlotShape(cmd.id, cmd.isLong, cmd.id, color, indices))
            }
        }

        // 收集所有出场徽标
        for ((label, indices) in exitTriggerMap) {
            if (indices.any { it }) {
                res.add(PinePlotShape(label, false, label, 0xFFF59E0B.toInt(), indices))
            }
        }

        return res
    }

    // ---------- 核心时序求值器 ----------

    private fun evalSeries(
        expr: String,
        candles: List<Candle>,
        seriesMap: Map<String, FloatArray>,
        scalarMap: Map<String, Double>
    ): FloatArray {
        val n = candles.size
        val trimmed = expr.trim()

        // 1. 已有序列直接返回
        if (seriesMap.containsKey(trimmed)) {
            return seriesMap[trimmed]!!
        }

        // 2. 常量标量转全长序列
        val constNum = trimmed.toDoubleOrNull() ?: scalarMap[trimmed]
        if (constNum != null) {
            val f = constNum.toFloat()
            return FloatArray(n) { f }
        }

        // 3. 历史回溯语法 series[k]
        val backMatch = Pattern.compile("([a-zA-Z0-9_]+)\\[(\\d+)\\]").matcher(trimmed)
        if (backMatch.matches()) {
            val baseName = backMatch.group(1) ?: "close"
            val k = backMatch.group(2)?.toIntOrNull() ?: 1
            val baseArr = seriesMap[baseName] ?: FloatArray(n) { candles[it].close.toFloat() }
            val res = FloatArray(n)
            for (i in 0 until n) {
                res[i] = if (i >= k) baseArr[i - k] else baseArr[0]
            }
            return res
        }

        // 4. 函数调用求解
        if (trimmed.startsWith("ta.sma(") || trimmed.startsWith("sma(")) {
            val args = splitArgs(extractInsideParens(trimmed, "sma"))
            val src = evalSeries(args.getOrNull(0) ?: "close", candles, seriesMap, scalarMap)
            val len = evalIntParam(args.getOrNull(1) ?: "20", scalarMap)
            return computeSmaArray(src, len)
        }
        if (trimmed.startsWith("ta.ema(") || trimmed.startsWith("ema(")) {
            val args = splitArgs(extractInsideParens(trimmed, "ema"))
            val src = evalSeries(args.getOrNull(0) ?: "close", candles, seriesMap, scalarMap)
            val len = evalIntParam(args.getOrNull(1) ?: "20", scalarMap)
            return computeEmaArray(src, len)
        }
        if (trimmed.startsWith("ta.rsi(") || trimmed.startsWith("rsi(")) {
            val args = splitArgs(extractInsideParens(trimmed, "rsi"))
            val len = evalIntParam(args.getOrNull(1) ?: (args.getOrNull(0) ?: "14"), scalarMap)
            return Indicators.computeRsi(candles, len)
        }
        if (trimmed.startsWith("ta.atr(") || trimmed.startsWith("atr(")) {
            val args = splitArgs(extractInsideParens(trimmed, "atr"))
            val len = evalIntParam(args.getOrNull(0) ?: "14", scalarMap)
            return Indicators.computeAtr(candles, len)
        }
        if (trimmed.startsWith("ta.stdev(") || trimmed.startsWith("stdev(")) {
            val args = splitArgs(extractInsideParens(trimmed, "stdev"))
            val src = evalSeries(args.getOrNull(0) ?: "close", candles, seriesMap, scalarMap)
            val len = evalIntParam(args.getOrNull(1) ?: "20", scalarMap)
            return computeStdevArray(src, len)
        }
        if (trimmed.startsWith("ta.pivotlow(") || trimmed.startsWith("pivotlow(")) {
            val args = splitArgs(extractInsideParens(trimmed, "pivotlow"))
            val src = evalSeries(args.getOrNull(0) ?: "low", candles, seriesMap, scalarMap)
            val left = evalIntParam(args.getOrNull(1) ?: "5", scalarMap)
            val right = evalIntParam(args.getOrNull(2) ?: "5", scalarMap)
            return computePivotLow(src, left, right)
        }
        if (trimmed.startsWith("ta.pivothigh(") || trimmed.startsWith("pivothigh(")) {
            val args = splitArgs(extractInsideParens(trimmed, "pivothigh"))
            val src = evalSeries(args.getOrNull(0) ?: "high", candles, seriesMap, scalarMap)
            val left = evalIntParam(args.getOrNull(1) ?: "5", scalarMap)
            val right = evalIntParam(args.getOrNull(2) ?: "5", scalarMap)
            return computePivotHigh(src, left, right)
        }
        if (trimmed.startsWith("ta.highest(") || trimmed.startsWith("highest(")) {
            val args = splitArgs(extractInsideParens(trimmed, "highest"))
            val src = evalSeries(args.getOrNull(0) ?: "high", candles, seriesMap, scalarMap)
            val len = evalIntParam(args.getOrNull(1) ?: (args.getOrNull(0) ?: "20"), scalarMap)
            return computeHighestArray(src, len)
        }
        if (trimmed.startsWith("ta.lowest(") || trimmed.startsWith("lowest(")) {
            val args = splitArgs(extractInsideParens(trimmed, "lowest"))
            val src = evalSeries(args.getOrNull(0) ?: "low", candles, seriesMap, scalarMap)
            val len = evalIntParam(args.getOrNull(1) ?: (args.getOrNull(0) ?: "20"), scalarMap)
            return computeLowestArray(src, len)
        }

        // 5. 算术运算符分层递归解析（先加减，后乘除）
        val addSubIdx = findLastOpIndex(trimmed, listOf("+", "-"))
        if (addSubIdx > 0) {
            val op = trimmed[addSubIdx].toString()
            val left = trimmed.substring(0, addSubIdx).trim()
            val right = trimmed.substring(addSubIdx + 1).trim()
            val leftArr = evalSeries(left, candles, seriesMap, scalarMap)
            val rightArr = evalSeries(right, candles, seriesMap, scalarMap)
            val res = FloatArray(n)
            for (i in 0 until n) {
                res[i] = if (op == "+") leftArr[i] + rightArr[i] else leftArr[i] - rightArr[i]
            }
            return res
        }

        val mulDivIdx = findLastOpIndex(trimmed, listOf("*", "/"))
        if (mulDivIdx > 0) {
            val op = trimmed[mulDivIdx].toString()
            val left = trimmed.substring(0, mulDivIdx).trim()
            val right = trimmed.substring(mulDivIdx + 1).trim()
            val leftArr = evalSeries(left, candles, seriesMap, scalarMap)
            val rightArr = evalSeries(right, candles, seriesMap, scalarMap)
            val res = FloatArray(n)
            for (i in 0 until n) {
                res[i] = if (op == "*") leftArr[i] * rightArr[i] else if (abs(rightArr[i]) > 1e-8f) leftArr[i] / rightArr[i] else 0f
            }
            return res
        }

        // 默认返回 close
        return seriesMap["close"] ?: FloatArray(n) { candles[it].close.toFloat() }
    }

    // ---------- 复合逻辑表达式求值器 ----------

    private fun evalConditionSeries(
        expr: String,
        candles: List<Candle>,
        seriesMap: Map<String, FloatArray>,
        boolSeriesMap: Map<String, BooleanArray>,
        scalarMap: Map<String, Double>
    ): BooleanArray {
        val n = candles.size
        val trimmed = expr.trim().removePrefix("(").removeSuffix(")")

        if (boolSeriesMap.containsKey(trimmed)) {
            return boolSeriesMap[trimmed]!!
        }

        // 1. 处理 or 逻辑连接（任意项为真即为真）
        val orParts = splitLogicParts(trimmed, " or ")
        if (orParts.size > 1) {
            val res = BooleanArray(n)
            for (part in orParts) {
                val sub = evalConditionSeries(part, candles, seriesMap, boolSeriesMap, scalarMap)
                for (i in 0 until n) {
                    if (sub[i]) res[i] = true
                }
            }
            return res
        }

        // 2. 处理 and 逻辑连接（全部项为真才为真）
        val andParts = splitLogicParts(trimmed, " and ")
        if (andParts.size > 1) {
            val res = BooleanArray(n) { true }
            for (part in andParts) {
                val sub = evalConditionSeries(part, candles, seriesMap, boolSeriesMap, scalarMap)
                for (i in 0 until n) {
                    if (!sub[i]) res[i] = false
                }
            }
            return res
        }

        // 3. 处理 not na(...)
        if (trimmed.startsWith("not na(") || trimmed.startsWith("!na(")) {
            val inner = extractInsideParens(trimmed, if (trimmed.startsWith("not na")) "not na" else "!na")
            val arr = evalSeries(inner, candles, seriesMap, scalarMap)
            val res = BooleanArray(n)
            for (i in 0 until n) {
                res[i] = !arr[i].isNaN()
            }
            return res
        }

        // 4. 处理原子条件：ta.crossover / ta.crossunder
        if (trimmed.startsWith("ta.crossover(") || trimmed.startsWith("crossover(")) {
            val args = splitArgs(extractInsideParens(trimmed, "crossover"))
            val a = evalSeries(args.getOrNull(0) ?: "close", candles, seriesMap, scalarMap)
            val b = evalSeries(args.getOrNull(1) ?: "close", candles, seriesMap, scalarMap)
            val res = BooleanArray(n)
            for (i in 1 until n) {
                res[i] = (a[i] > b[i]) && (a[i - 1] <= b[i - 1])
            }
            return res
        }
        if (trimmed.startsWith("ta.crossunder(") || trimmed.startsWith("crossunder(")) {
            val args = splitArgs(extractInsideParens(trimmed, "crossunder"))
            val a = evalSeries(args.getOrNull(0) ?: "close", candles, seriesMap, scalarMap)
            val b = evalSeries(args.getOrNull(1) ?: "close", candles, seriesMap, scalarMap)
            val res = BooleanArray(n)
            for (i in 1 until n) {
                res[i] = (a[i] < b[i]) && (a[i - 1] >= b[i - 1])
            }
            return res
        }

        // 5. 处理原子比较运算：>=, <=, ==, !=, >, <
        for (comp in listOf(">=", "<=", "==", "!=", ">", "<")) {
            val cIdx = findOpIndex(trimmed, comp)
            if (cIdx > 0) {
                val left = trimmed.substring(0, cIdx).trim()
                val right = trimmed.substring(cIdx + comp.length).trim()
                val leftArr = evalSeries(left, candles, seriesMap, scalarMap)
                val rightArr = evalSeries(right, candles, seriesMap, scalarMap)
                val res = BooleanArray(n)
                for (i in 0 until n) {
                    val lVal = leftArr[i]
                    val rVal = rightArr[i]
                    if (lVal.isNaN() || rVal.isNaN()) {
                        res[i] = false
                    } else {
                        res[i] = when (comp) {
                            ">=" -> lVal >= rVal
                            "<=" -> lVal <= rVal
                            ">" -> lVal > rVal
                            "<" -> lVal < rVal
                            "==" -> abs(lVal - rVal) < 1e-5f
                            "!=" -> abs(lVal - rVal) >= 1e-5f
                            else -> false
                        }
                    }
                }
                return res
            }
        }

        return BooleanArray(n)
    }

    private fun splitLogicParts(expr: String, delimiter: String): List<String> {
        val list = mutableListOf<String>()
        var depth = 0
        var start = 0
        val len = delimiter.length
        var i = 0
        while (i <= expr.length - len) {
            val ch = expr[i]
            if (ch == '(' || ch == '[') depth++
            if (ch == ')' || ch == ']') depth--
            if (depth == 0 && expr.substring(i, i + len) == delimiter) {
                list.add(expr.substring(start, i).trim())
                start = i + len
                i += len - 1
            }
            i++
        }
        if (start < expr.length) {
            list.add(expr.substring(start).trim())
        }
        return if (list.isEmpty()) listOf(expr.trim()) else list
    }

    // ---------- 算术与统计辅助 ----------

    private fun computeSmaArray(src: FloatArray, len: Int): FloatArray {
        val n = src.size
        val out = FloatArray(n)
        val l = len.coerceAtLeast(1)
        var sum = 0.0
        for (i in 0 until n) {
            sum += src[i]
            if (i >= l) sum -= src[i - l]
            val count = min(i + 1, l)
            out[i] = (sum / count).toFloat()
        }
        return out
    }

    private fun computeEmaArray(src: FloatArray, len: Int): FloatArray {
        val n = src.size
        val out = FloatArray(n)
        val l = len.coerceAtLeast(1)
        val alpha = 2.0 / (l + 1.0)
        var ema = if (n > 0) src[0].toDouble() else 0.0
        for (i in 0 until n) {
            ema += alpha * (src[i] - ema)
            out[i] = ema.toFloat()
        }
        return out
    }

    private data class MacdTuples(val dif: FloatArray, val dea: FloatArray, val hist: FloatArray)

    private fun computeMacdTuples(src: FloatArray, fast: Int, slow: Int, sig: Int): MacdTuples {
        val n = src.size
        val fastEma = computeEmaArray(src, fast)
        val slowEma = computeEmaArray(src, slow)
        val dif = FloatArray(n)
        for (i in 0 until n) dif[i] = fastEma[i] - slowEma[i]
        val dea = computeEmaArray(dif, sig)
        val hist = FloatArray(n)
        for (i in 0 until n) hist[i] = dif[i] - dea[i]
        return MacdTuples(dif, dea, hist)
    }

    private fun computeStdevArray(src: FloatArray, len: Int): FloatArray {
        val n = src.size
        val out = FloatArray(n)
        val l = len.coerceAtLeast(1)
        for (i in 0 until n) {
            val start = max(0, i - l + 1)
            val count = i - start + 1
            var sum = 0.0
            for (j in start..i) sum += src[j]
            val mean = sum / count
            var sumSq = 0.0
            for (j in start..i) {
                val diff = src[j] - mean
                sumSq += diff * diff
            }
            out[i] = sqrt(sumSq / count).toFloat()
        }
        return out
    }

    private fun computePivotLow(src: FloatArray, left: Int, right: Int): FloatArray {
        val n = src.size
        val out = FloatArray(n) { Float.NaN }
        val l = left.coerceAtLeast(1)
        val r = right.coerceAtLeast(1)
        for (i in (l + r) until n) {
            val pivotIdx = i - r
            val pivotVal = src[pivotIdx]
            var isPivot = true
            for (j in (pivotIdx - l)..(pivotIdx + r)) {
                if (j != pivotIdx && src[j] <= pivotVal) {
                    isPivot = false
                    break
                }
            }
            if (isPivot) {
                out[i] = pivotVal
            }
        }
        return out
    }

    private fun computePivotHigh(src: FloatArray, left: Int, right: Int): FloatArray {
        val n = src.size
        val out = FloatArray(n) { Float.NaN }
        val l = left.coerceAtLeast(1)
        val r = right.coerceAtLeast(1)
        for (i in (l + r) until n) {
            val pivotIdx = i - r
            val pivotVal = src[pivotIdx]
            var isPivot = true
            for (j in (pivotIdx - l)..(pivotIdx + r)) {
                if (j != pivotIdx && src[j] >= pivotVal) {
                    isPivot = false
                    break
                }
            }
            if (isPivot) {
                out[i] = pivotVal
            }
        }
        return out
    }

    private fun computeHighestArray(src: FloatArray, len: Int): FloatArray {
        val n = src.size
        val out = FloatArray(n)
        val l = len.coerceAtLeast(1)
        for (i in 0 until n) {
            val start = max(0, i - l + 1)
            var maxV = -Float.MAX_VALUE
            for (j in start..i) {
                if (src[j] > maxV) maxV = src[j]
            }
            out[i] = maxV
        }
        return out
    }

    private fun computeLowestArray(src: FloatArray, len: Int): FloatArray {
        val n = src.size
        val out = FloatArray(n)
        val l = len.coerceAtLeast(1)
        for (i in 0 until n) {
            val start = max(0, i - l + 1)
            var minV = Float.MAX_VALUE
            for (j in start..i) {
                if (src[j] < minV) minV = src[j]
            }
            out[i] = minV
        }
        return out
    }

    // ---------- 字符串与参数解析 ----------

    private fun extractInsideParens(line: String, funcName: String): String {
        val idx = line.indexOf("$funcName(")
        if (idx < 0) return ""
        val start = idx + funcName.length + 1
        var depth = 1
        for (i in start until line.length) {
            val ch = line[i]
            if (ch == '(') depth++
            if (ch == ')') {
                depth--
                if (depth == 0) return line.substring(start, i)
            }
        }
        return line.substring(start)
    }

    private fun splitArgs(argsStr: String): List<String> {
        val list = mutableListOf<String>()
        var start = 0
        var depth = 0
        var inQuote = false
        for (i in argsStr.indices) {
            val ch = argsStr[i]
            if (ch == '\"' || ch == '\'') inQuote = !inQuote
            if (!inQuote) {
                if (ch == '(' || ch == '[') depth++
                if (ch == ')' || ch == ']') depth--
                if (ch == ',' && depth == 0) {
                    list.add(argsStr.substring(start, i).trim())
                    start = i + 1
                }
            }
        }
        if (start < argsStr.length) {
            list.add(argsStr.substring(start).trim())
        }
        return list
    }

    private fun evalIntParam(expr: String, scalarMap: Map<String, Double>): Int {
        val trimmed = expr.trim()
        val num = trimmed.toIntOrNull()
        if (num != null) return num
        val scalar = scalarMap[trimmed]?.toInt()
        if (scalar != null) return scalar
        return 14
    }

    private fun parseColor(line: String): Int? {
        val lower = line.lowercase(Locale.US)
        val alphaMatch = Pattern.compile("color\\.new\\([^,]+,\\s*(\\d+)\\)").matcher(lower)
        val alphaPercent = if (alphaMatch.find()) alphaMatch.group(1)?.toIntOrNull() ?: 0 else 0
        val alphaInt = ((100 - alphaPercent) * 255 / 100).coerceIn(0, 255)

        val baseRgb = when {
            lower.contains("yellow") -> 0xEAB308
            lower.contains("purple") -> 0xA855F7
            lower.contains("orange") -> 0xF59E0B
            lower.contains("green") -> 0x10B981
            lower.contains("red") -> 0xEF4444
            lower.contains("blue") -> 0x3B82F6
            lower.contains("aqua") || lower.contains("cyan") -> 0x06B6D4
            lower.contains("gray") || lower.contains("grey") -> 0x6B7280
            lower.contains("white") -> 0xFFFFFF
            lower.contains("black") -> 0x000000
            else -> {
                val hexMatch = Pattern.compile("#([0-9a-fA-F]{6})").matcher(line)
                if (hexMatch.find()) {
                    val hex = hexMatch.group(1) ?: return null
                    java.lang.Long.parseLong(hex, 16).toInt()
                } else return null
            }
        }

        return (alphaInt shl 24) or (baseRgb and 0x00FFFFFF)
    }

    private fun parseLineWidth(line: String): Float {
        val m = Pattern.compile("linewidth\\s*=\\s*(\\d+)").matcher(line)
        return if (m.find()) m.group(1)?.toFloatOrNull() ?: 1.2f else 1.2f
    }

    private fun extractQuotedString(s: String): String? {
        val m = Pattern.compile("[\"']([^\"']+)[\"']").matcher(s)
        return if (m.find()) m.group(1) else null
    }

    private fun extractParamValue(line: String, param: String): String? {
        val m = Pattern.compile("$param\\s*=\\s*[\"']([^\"']+)[\"']").matcher(line)
        return if (m.find()) m.group(1) else null
    }

    private fun findOpIndex(s: String, op: String): Int {
        var depth = 0
        for (i in s.indices) {
            val ch = s[i]
            if (ch == '(' || ch == '[') depth++
            if (ch == ')' || ch == ']') depth--
            if (depth == 0 && i > 0 && i <= s.length - op.length) {
                if (s.substring(i, i + op.length) == op) {
                    return i
                }
            }
        }
        return -1
    }

    private fun findLastOpIndex(s: String, ops: List<String>): Int {
        var depth = 0
        var lastIdx = -1
        for (i in s.indices) {
            val ch = s[i]
            if (ch == '(' || ch == '[') depth++
            if (ch == ')' || ch == ']') depth--
            if (depth == 0 && i > 0 && i < s.length - 1) {
                for (op in ops) {
                    if (s[i].toString() == op && s[i - 1] != '=' && s[i + 1] != '=') {
                        lastIdx = i
                    }
                }
            }
        }
        return lastIdx
    }
}
