package com.bitdash.app

import android.app.Activity
import android.app.AlertDialog
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.bitdash.app.market.Candle
import com.bitdash.app.market.PineEngine
import com.bitdash.app.market.PineScript
import com.bitdash.app.market.PineScriptManager
import java.util.UUID

/**
 * 自定义 Pine Script (v5) 脚本管理器与编辑器弹窗
 */
object ScriptManagerDialog {

    fun show(activity: Activity, candles: List<Candle>, onSavedOrToggled: () -> Unit) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_script_manager, null)
        val layoutList = view.findViewById<LinearLayout>(R.id.layoutScriptList)
        val btnAdd = view.findViewById<Button>(R.id.btnAddNewScript)
        val btnImport = view.findViewById<Button>(R.id.btnImportTemplate)
        val btnClose = view.findViewById<Button>(R.id.btnCloseDialog)

        val dialog = AlertDialog.Builder(activity)
            .setView(view)
            .create()

        fun refreshList() {
            layoutList.removeAllViews()
            val scripts = PineScriptManager.getScripts(activity)

            if (scripts.isEmpty()) {
                val emptyTv = TextView(activity).apply {
                    text = "暂无自定义脚本，可点击右上角「+ 新建脚本」或下方「恢复预置模板」"
                    setTextColor(0xFF94A3B8.toInt())
                    textSize = 12f
                    setPadding(16, 32, 16, 32)
                }
                layoutList.addView(emptyTv)
                return
            }

            for (s in scripts) {
                val itemView = LayoutInflater.from(activity).inflate(R.layout.item_script_manage, layoutList, false)
                val cb = itemView.findViewById<CheckBox>(R.id.cbScriptEnable)
                val tvName = itemView.findViewById<TextView>(R.id.tvScriptName)
                val tvType = itemView.findViewById<TextView>(R.id.tvScriptType)
                val btnEdit = itemView.findViewById<ImageView>(R.id.btnEditScript)
                val btnDel = itemView.findViewById<ImageView>(R.id.btnDeleteScript)

                cb.isChecked = s.enabled
                tvName.text = s.name
                val spotTag = if (s.spotOnly) " · 现货模式" else ""
                val typeTag = (if (s.isStrategy) "策略" else "指标") + " · " + (if (s.overlay) "主图叠加" else "副图独立") + spotTag + " (Pine v5)"
                tvType.text = typeTag

                cb.setOnCheckedChangeListener { _, isChecked ->
                    PineScriptManager.toggleEnabled(activity, s.id, isChecked)
                    onSavedOrToggled()
                }

                btnEdit.setOnClickListener {
                    showEditDialog(activity, s, candles) {
                        refreshList()
                        onSavedOrToggled()
                    }
                }

                btnDel.setOnClickListener {
                    AlertDialog.Builder(activity)
                        .setTitle("确认删除")
                        .setMessage("确定要删除脚本「${s.name}」吗？")
                        .setPositiveButton("删除") { _, _ ->
                            PineScriptManager.deleteScript(activity, s.id)
                            refreshList()
                            onSavedOrToggled()
                            Toast.makeText(activity, "已删除脚本", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }

                layoutList.addView(itemView)
            }
        }

        btnAdd.setOnClickListener {
            val newScript = PineScript(
                id = UUID.randomUUID().toString(),
                name = "新建指标",
                code = """
//@version=5
indicator("新建指标", overlay=true)

len = input.int(14, "周期")
val = ta.sma(close, len)

plot(val, "SMA", color=color.yellow, linewidth=2)
""".trimIndent(),
                enabled = true,
                overlay = true,
                isStrategy = false,
                spotOnly = false
            )
            showEditDialog(activity, newScript, candles) {
                refreshList()
                onSavedOrToggled()
            }
        }

        btnImport.setOnClickListener {
            val defaults = PineScriptManager.getDefaultTemplates()
            val current = PineScriptManager.getScripts(activity)
            for (d in defaults) {
                if (current.none { it.id == d.id }) {
                    current.add(d)
                }
            }
            PineScriptManager.saveScripts(activity, current)
            refreshList()
            onSavedOrToggled()
            Toast.makeText(activity, "已恢复常用预置模板", Toast.LENGTH_SHORT).show()
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        refreshList()
        dialog.show()
    }

    private fun showEditDialog(
        activity: Activity,
        script: PineScript,
        candles: List<Candle>,
        onSaved: () -> Unit
    ) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_script_edit, null)
        val tvTitle = view.findViewById<TextView>(R.id.tvEditTitle)
        val etName = view.findViewById<EditText>(R.id.etScriptName)
        val etCode = view.findViewById<EditText>(R.id.etScriptCode)
        val switchSpot = view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchSpotOnly)
        val tvStatus = view.findViewById<TextView>(R.id.tvTestStatus)
        val btnTemplate = view.findViewById<Button>(R.id.btnQuickTemplate)
        val btnTest = view.findViewById<Button>(R.id.btnTestRun)
        val btnCancel = view.findViewById<Button>(R.id.btnCancelEdit)
        val btnSave = view.findViewById<Button>(R.id.btnSaveScript)

        tvTitle.text = if (script.name.isEmpty()) "新建 Pine Script (v5)" else "编辑「${script.name}」"
        etName.setText(script.name)
        etCode.setText(script.code)
        switchSpot.isChecked = script.spotOnly

        val editDialog = AlertDialog.Builder(activity)
            .setView(view)
            .create()

        btnTemplate.setOnClickListener {
            val templates = PineScriptManager.getDefaultTemplates()
            val names = templates.map { it.name }.toTypedArray()
            AlertDialog.Builder(activity)
                .setTitle("选择预置模板覆盖")
                .setItems(names) { _, which ->
                    val chosen = templates[which]
                    etName.setText(chosen.name)
                    etCode.setText(chosen.code)
                    switchSpot.isChecked = chosen.spotOnly
                    Toast.makeText(activity, "已加载模板: ${chosen.name}", Toast.LENGTH_SHORT).show()
                }
                .show()
        }

        btnTest.setOnClickListener {
            val code = etCode.text.toString().trim()
            val currentName = etName.text.toString().trim()

            // 检查代码中的策略/指标名称是否与输入框中的脚本名称一致
            val codeTitle = extractTitleFromCode(code)
            if (!codeTitle.isNullOrEmpty() && codeTitle != currentName) {
                AlertDialog.Builder(activity)
                    .setTitle("同步脚本名称")
                    .setMessage("检测到代码中的策略/指标名称为「$codeTitle」，与当前脚本名称「$currentName」不一致。\n\n是否将脚本名称自动更新为「$codeTitle」？")
                    .setPositiveButton("是") { _, _ ->
                        etName.setText(codeTitle)
                        tvTitle.text = "编辑「$codeTitle」"
                        Toast.makeText(activity, "脚本名称已更新为「$codeTitle」", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("保持原样", null)
                    .show()
            }

            val temp = script.copy(
                name = etName.text.toString().trim().ifEmpty { codeTitle ?: "测试脚本" },
                code = code,
                spotOnly = switchSpot.isChecked
            )
            val result = PineEngine.execute(temp, candles)
            if (result.success) {
                val linesCount = result.plotLines.size
                val shapesCount = result.plotShapes.size
                val fillsCount = result.fills.size
                val mode = if (result.overlay) "主图叠加" else "副图独立"
                val type = if (result.isStrategy) "策略" else "指标"
                val spotDesc = if (switchSpot.isChecked) " | 现货模式(仅多单，不兼容所有脚本)" else " | 双向多空"
                tvStatus.text = "✓ 语法检测通过！模式: $type ($mode$spotDesc) | 曲线: ${linesCount}条 | 信号: ${shapesCount}个 | 区域: ${fillsCount}个"
                tvStatus.setTextColor(0xFF10B981.toInt())
            } else {
                tvStatus.text = "✗ 检测失败: ${result.errorMessage}"
                tvStatus.setTextColor(0xFFEF4444.toInt())
            }
        }

        btnCancel.setOnClickListener {
            editDialog.dismiss()
        }

        btnSave.setOnClickListener {
            val name = etName.text.toString().trim().ifEmpty { "自定义指标" }
            val code = etCode.text.toString().trim()

            if (code.isEmpty()) {
                Toast.makeText(activity, "脚本代码不能为空", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val isStrategy = code.contains("strategy(")
            val overlay = !code.contains("overlay=false") && !code.contains("overlay = false")

            script.name = name
            script.code = code
            script.isStrategy = isStrategy
            script.overlay = overlay
            script.spotOnly = switchSpot.isChecked

            PineScriptManager.saveOrUpdate(activity, script)
            editDialog.dismiss()
            onSaved()
            Toast.makeText(activity, "脚本已保存并生效", Toast.LENGTH_SHORT).show()
        }

        editDialog.show()
    }

    private fun extractTitleFromCode(code: String): String? {
        for (line in code.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("//") || trimmed.isEmpty()) continue
            if (trimmed.startsWith("strategy(") || trimmed.startsWith("indicator(")) {
                val nameMatch = java.util.regex.Pattern.compile("(?:title\\s*=\\s*)?[\"']([^\"']+)[\"']").matcher(trimmed)
                if (nameMatch.find()) {
                    return nameMatch.group(1)
                }
            }
        }
        return null
    }
}
