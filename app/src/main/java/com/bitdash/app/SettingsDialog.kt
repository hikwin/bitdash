package com.bitdash.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bitdash.app.market.FloatingFmt
import com.bitdash.app.market.Markets
import com.bitdash.app.market.Palette
import com.bitdash.app.market.Prefs
import com.bitdash.app.market.RtScope
import com.bitdash.app.market.Realtimes
import com.bitdash.app.market.Symbols

/**
 * 设置弹窗：行情源 / 涨跌配色 / 列表字体大小 / 自动刷新间隔 / 实时行情 / 屏幕方向。
 *
 * 各项都存在 [Prefs] 里，重启后保留。
 */
object SettingsDialog {

    /** 可选刷新间隔（含"关闭"、3秒、5秒、10秒、15秒、30秒、60秒） */
    private val REFRESH_OPTIONS = longArrayOf(0L, 3_000L, 5_000L, 10_000L, 15_000L, 30_000L, 60_000L)

    /** 实时行情的生效范围选项，顺序即弹窗里的展示顺序 */
    private val RT_OPTIONS = arrayOf(RtScope.OFF, RtScope.ALL, RtScope.CHART, RtScope.WATCH)

    /**
     * 四个方向。用 ActivityInfo 的固定常量而非 USER/SENSOR 系列，
     * 保证"设置了就锁死"，不会被系统传感器改回去。
     *
     * 注意 REVERSE_* 需要 API 9+，全部满足 minSdk 24。
     */
    private val ORIENTATIONS = intArrayOf(
        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,           // 0°
        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,          // 90°
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,   // 180°
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,  // 270°
        ActivityInfo.SCREEN_ORIENTATION_SENSOR              // 跟随传感器
    )

    /**
     * @param onChanged 任一设置变更后回调，调用方据此刷新界面/重启轮询
     */
    fun show(activity: Activity, onChanged: () -> Unit) {
        val items = arrayOf(
            activity.getString(R.string.settings_theme, themeLabel(activity, Prefs.getThemeMode(activity))),
            activity.getString(R.string.settings_source, currentSourceName(activity)),
            activity.getString(R.string.settings_palette, paletteLabel(activity, Prefs.getUpIsGreen(activity))),
            activity.getString(R.string.settings_font_scale, fontScaleLabel(activity, Prefs.getFontScalePct(activity))),
            activity.getString(R.string.settings_chart_font_scale, fontScaleLabel(activity, Prefs.getChartFontScalePct(activity))),
            activity.getString(R.string.settings_refresh, refreshLabel(activity, Prefs.getRefreshMs(activity))),
            activity.getString(R.string.settings_realtime, rtLabel(activity, Prefs.getRtScope(activity))),
            activity.getString(R.string.settings_orientation, orientationLabel(activity, Prefs.getOrientation(activity))),
            activity.getString(R.string.settings_floating, floatingLabel(activity))
        )

        AlertDialog.Builder(activity)
            .setTitle(R.string.settings_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showThemePicker(activity, onChanged)
                    1 -> SourcePicker.show(activity) { onChanged() }
                    2 -> showPalettePicker(activity, onChanged)
                    3 -> showFontScalePicker(activity, onChanged)
                    4 -> showChartFontScalePicker(activity, onChanged)
                    5 -> showRefreshPicker(activity, onChanged)
                    6 -> showRealtimePicker(activity, onChanged)
                    7 -> showOrientationPicker(activity, onChanged)
                    8 -> showFloatingSettingsPicker(activity, onChanged)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------- 外观主题（日间/夜间模式） ----------

    private fun showThemePicker(activity: Activity, onChanged: () -> Unit) {
        val current = Prefs.getThemeMode(activity)
        val options = intArrayOf(Prefs.THEME_DARK, Prefs.THEME_LIGHT, Prefs.THEME_SYSTEM)
        val labels = options.map { themeLabel(activity, it) }.toTypedArray()
        val checked = options.indexOf(current).coerceAtLeast(0)

        AlertDialog.Builder(activity)
            .setTitle(R.string.settings_theme_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val selected = options[which]
                Prefs.saveThemeMode(activity, selected)
                Prefs.applyTheme(selected)
                dialog.dismiss()
                onChanged()
                activity.recreate()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun themeLabel(activity: Activity, mode: Int): String = when (mode) {
        Prefs.THEME_LIGHT -> activity.getString(R.string.theme_light)
        Prefs.THEME_SYSTEM -> activity.getString(R.string.theme_system)
        else -> activity.getString(R.string.theme_dark)
    }

    // ---------- 涨跌配色 ----------

    private fun showPalettePicker(activity: Activity, onChanged: () -> Unit) {
        val current = Prefs.getUpIsGreen(activity)
        val options = arrayOf(false, true)
        val labels = options.map { paletteLabel(activity, it) }.toTypedArray()
        val checked = if (current) 1 else 0

        AlertDialog.Builder(activity)
            .setTitle(R.string.settings_palette_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                Prefs.saveUpIsGreen(activity, options[which])
                dialog.dismiss()
                onChanged()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun paletteLabel(activity: Activity, upIsGreen: Boolean): String =
        if (upIsGreen) activity.getString(R.string.palette_green_up)
        else activity.getString(R.string.palette_red_up)

    // ---------- 列表字体大小 ----------

    private fun showFontScalePicker(activity: Activity, onChanged: () -> Unit) {
        val current = Prefs.getFontScalePct(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_font_scale, null)

        val tvValue = view.findViewById<TextView>(R.id.tvFontScaleValue)
        val tvHint = view.findViewById<TextView>(R.id.tvFontScaleHint)
        val seek = view.findViewById<SeekBar>(R.id.seekFontScale)
        val tvMin = view.findViewById<TextView>(R.id.tvFontScaleMin)
        val tvMax = view.findViewById<TextView>(R.id.tvFontScaleMax)

        tvHint.setText(R.string.font_scale_range)
        tvMin.text = "${Prefs.MIN_FONT_SCALE}%"
        tvMax.text = "${Prefs.MAX_FONT_SCALE}%"
        seek.max = Prefs.MAX_FONT_SCALE - Prefs.MIN_FONT_SCALE

        fun updateValue(pct: Int) {
            tvValue.text = fontScaleLabel(activity, pct)
        }

        val initialProgress = (current - Prefs.MIN_FONT_SCALE).coerceIn(0, seek.max)
        seek.progress = initialProgress
        updateValue(Prefs.MIN_FONT_SCALE + initialProgress)

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateValue(Prefs.MIN_FONT_SCALE + progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        AlertDialog.Builder(activity)
            .setTitle(R.string.settings_font_scale_title)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                val selectedPct = Prefs.MIN_FONT_SCALE + seek.progress
                Prefs.saveFontScalePct(activity, selectedPct)
                dialog.dismiss()
                onChanged()
            }
            .setNeutralButton(R.string.font_scale_reset) { dialog, _ ->
                Prefs.saveFontScalePct(activity, Prefs.DEFAULT_FONT_SCALE)
                dialog.dismiss()
                onChanged()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------- 图表字体大小 ----------

    private fun showChartFontScalePicker(activity: Activity, onChanged: () -> Unit) {
        val current = Prefs.getChartFontScalePct(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_font_scale, null)

        val tvValue = view.findViewById<TextView>(R.id.tvFontScaleValue)
        val tvHint = view.findViewById<TextView>(R.id.tvFontScaleHint)
        val seek = view.findViewById<SeekBar>(R.id.seekFontScale)
        val tvMin = view.findViewById<TextView>(R.id.tvFontScaleMin)
        val tvMax = view.findViewById<TextView>(R.id.tvFontScaleMax)

        tvHint.setText(R.string.chart_font_scale_range)
        tvMin.text = "${Prefs.MIN_CHART_FONT_SCALE}%"
        tvMax.text = "${Prefs.MAX_CHART_FONT_SCALE}%"
        seek.max = Prefs.MAX_CHART_FONT_SCALE - Prefs.MIN_CHART_FONT_SCALE

        fun updateValue(pct: Int) {
            tvValue.text = fontScaleLabel(activity, pct)
        }

        val initialProgress = (current - Prefs.MIN_CHART_FONT_SCALE).coerceIn(0, seek.max)
        seek.progress = initialProgress
        updateValue(Prefs.MIN_CHART_FONT_SCALE + initialProgress)

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateValue(Prefs.MIN_CHART_FONT_SCALE + progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        AlertDialog.Builder(activity)
            .setTitle(R.string.settings_chart_font_scale_title)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                val selectedPct = Prefs.MIN_CHART_FONT_SCALE + seek.progress
                Prefs.saveChartFontScalePct(activity, selectedPct)
                dialog.dismiss()
                onChanged()
            }
            .setNeutralButton(R.string.font_scale_reset) { dialog, _ ->
                Prefs.saveChartFontScalePct(activity, Prefs.DEFAULT_CHART_FONT_SCALE)
                dialog.dismiss()
                onChanged()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun fontScaleLabel(activity: Activity, pct: Int): String =
        activity.getString(R.string.font_scale_percent, pct)

    // ---------- 刷新间隔 ----------

    private fun showRefreshPicker(activity: Activity, onChanged: () -> Unit) {
        val current = Prefs.getRefreshMs(activity)
        val labels = REFRESH_OPTIONS.map { refreshLabel(activity, it) }.toTypedArray()
        var checked = REFRESH_OPTIONS.indexOfFirst { it == current }
        if (checked < 0) checked = REFRESH_OPTIONS.indexOfFirst { it == Prefs.DEFAULT_REFRESH_MS }

        AlertDialog.Builder(activity)
            .setTitle(R.string.settings_refresh_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                Prefs.saveRefreshMs(activity, REFRESH_OPTIONS[which])
                dialog.dismiss()
                onChanged()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun refreshLabel(activity: Activity, ms: Long): String =
        if (ms <= 0L) activity.getString(R.string.refresh_off)
        else activity.getString(R.string.refresh_seconds, ms / 1000L)

    // ---------- 实时行情（WebSocket） ----------

    /**
     * 实时行情范围选择。
     *
     * 不能用 [AlertDialog.Builder.setSingleChoiceItems] + setMessage 的组合：
     * AlertController 只在 message 为空时才把选项列表挂进视图树，两者同时设置
     * 会导致选项被静默丢弃、弹窗退化成纯文字且无法选择。所以这里用自定义布局，
     * 把说明文案和单选组放在同一个 View 里。
     */
    private fun showRealtimePicker(activity: Activity, onChanged: () -> Unit) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_realtime, null)
        val group = view.findViewById<RadioGroup>(R.id.rgRealtime)
        val tvSupport = view.findViewById<TextView>(R.id.tvRtSupport)

        // 选项 id 与 RtScope 的映射，两个方向都要用
        val ids = intArrayOf(R.id.rbRtOff, R.id.rbRtAll, R.id.rbRtChart, R.id.rbRtWatch)

        group.check(ids[RT_OPTIONS.indexOfFirst { it == Prefs.getRtScope(activity) }.coerceAtLeast(0)])

        // 当前源支持情况直接摊开显示，省得用户选完才发现不生效
        val active = Markets.activeSourceId(activity)
        val sourceName = Markets.byId(active)?.displayName
            ?: activity.getString(R.string.source_auto)
        val supported = Realtimes.supports(active)
        tvSupport.visibility = View.VISIBLE
        tvSupport.text = activity.getString(
            if (supported) R.string.rt_supported_by else R.string.rt_unsupported_by,
            sourceName
        )
        tvSupport.setTextColor(
            ContextCompat.getColor(activity, if (supported) R.color.brand else R.color.text_dim)
        )

        AlertDialog.Builder(activity)
            .setTitle(R.string.settings_realtime_title)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                val idx = ids.indexOfFirst { it == group.checkedRadioButtonId }
                if (idx >= 0) {
                    val scope = RT_OPTIONS[idx]
                    Prefs.saveRtScope(activity, scope)
                    // 选了实时但当前源没有 WS 支持：明确提示，否则用户会以为坏了
                    if (scope != RtScope.OFF && !supported) {
                        Toast.makeText(
                            activity,
                            activity.getString(R.string.rt_unsupported, sourceName),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                dialog.dismiss()
                onChanged()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 设置列表里的短标签 */
    private fun rtLabel(activity: Activity, scope: RtScope): String = activity.getString(
        when (scope) {
            RtScope.OFF -> R.string.rt_off
            RtScope.ALL -> R.string.rt_all
            RtScope.CHART -> R.string.rt_chart
            RtScope.WATCH -> R.string.rt_watch
        }
    )

    /** 单选弹窗里的完整描述见 layout/dialog_realtime.xml，此处只需列表短标签 */

    // ---------- 屏幕方向 ----------

    private fun showOrientationPicker(activity: Activity, onChanged: () -> Unit) {
        val current = Prefs.getOrientation(activity)
        val labels = ORIENTATIONS.map { orientationLabel(activity, it) }.toTypedArray()
        var checked = ORIENTATIONS.indexOfFirst { it == current }
        if (checked < 0) checked = 0

        AlertDialog.Builder(activity)
            .setTitle(R.string.settings_orientation_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val newOrientation = ORIENTATIONS[which]
                Prefs.saveOrientation(activity, newOrientation)
                activity.requestedOrientation = newOrientation
                dialog.dismiss()
                // 方向变化会重建 Activity，回调里不要再依赖当前实例的视图
                onChanged()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun orientationLabel(activity: Activity, value: Int): String = activity.getString(
        when (value) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE -> R.string.orientation_90
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT -> R.string.orientation_180
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE -> R.string.orientation_270
            ActivityInfo.SCREEN_ORIENTATION_SENSOR -> R.string.orientation_auto
            else -> R.string.orientation_0
        }
    )

    // ---------- 行情源当前值 ----------

    private fun currentSourceName(activity: Activity): String {
        val chosen = Prefs.getSourceId(activity)
        if (chosen != Markets.AUTO) {
            return Markets.byId(chosen)?.displayName ?: activity.getString(R.string.source_auto)
        }
        val actual = Markets.lastUsedId?.let { Markets.byId(it)?.displayName }
        return if (actual != null) activity.getString(R.string.source_auto_with, actual)
        else activity.getString(R.string.source_auto)
    }

    // ---------- 桌面悬浮窗 ----------

    private val POPULAR_CANDIDATES = listOf(
        "BTC-USDT", "ETH-USDT", "SOL-USDT", "BNB-USDT", "XRP-USDT",
        "DOGE-USDT", "PEPE-USDT", "PAXG-USDT", "ADA-USDT", "AVAX-USDT",
        "SUI-USDT", "TRX-USDT", "LINK-USDT", "NEAR-USDT", "LTC-USDT",
        "SHIB-USDT", "DOT-USDT", "UNI-USDT", "APT-USDT", "FIL-USDT",
        "ATOM-USDT", "BCH-USDT", "ETC-USDT", "OP-USDT", "ARB-USDT"
    )

    private fun showFloatingSettingsPicker(activity: Activity, onChanged: () -> Unit) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_floating_settings, null)
        val swFloating = view.findViewById<SwitchCompat>(R.id.swFloating)
        val llSwitchRow = view.findViewById<View>(R.id.llSwitchRow)
        val tvSelectedCount = view.findViewById<TextView>(R.id.tvSelectedCount)
        val rvCoins = view.findViewById<RecyclerView>(R.id.rvCoins)
        val seekAlpha = view.findViewById<SeekBar>(R.id.seekAlpha)
        val tvAlphaValue = view.findViewById<TextView>(R.id.tvAlphaValue)
        val tvAlphaMin = view.findViewById<TextView>(R.id.tvAlphaMin)
        val tvAlphaMax = view.findViewById<TextView>(R.id.tvAlphaMax)

        tvAlphaMin.text = "${Prefs.MIN_FLOATING_ALPHA}%"
        tvAlphaMax.text = "${Prefs.MAX_FLOATING_ALPHA}%"
        seekAlpha.max = Prefs.MAX_FLOATING_ALPHA - Prefs.MIN_FLOATING_ALPHA

        val originalAlphaPct = Prefs.getFloatingAlphaPct(activity)
        val initialProgress = (originalAlphaPct - Prefs.MIN_FLOATING_ALPHA).coerceIn(0, seekAlpha.max)
        seekAlpha.progress = initialProgress
        tvAlphaValue.text = "${originalAlphaPct}%"

        // 滑动透明度滑块时，实时对正在运行的悬浮窗进行即时效果预览
        seekAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val pct = Prefs.MIN_FLOATING_ALPHA + progress
                tvAlphaValue.text = "${pct}%"
                if (FloatingWindowService.isRunning) {
                    FloatingWindowService.updateLiveAlpha(pct)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        var isEnabled = Prefs.getFloatingEnabled(activity)
        swFloating.isChecked = isEnabled

        fun checkOverlayPermission(): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(activity)
            } else {
                true
            }
        }

        fun requestOverlayPermission() {
            AlertDialog.Builder(activity)
                .setTitle(R.string.floating_permission_title)
                .setMessage(R.string.floating_permission_msg)
                .setPositiveButton(R.string.floating_permission_go) { _, _ ->
                    try {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${activity.packageName}")
                        )
                        activity.startActivity(intent)
                    } catch (_: Exception) {
                        Toast.makeText(activity, "无法打开悬浮窗设置，请手动开启权限", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        llSwitchRow.setOnClickListener {
            val target = !swFloating.isChecked
            if (target) {
                if (!checkOverlayPermission()) {
                    requestOverlayPermission()
                    return@setOnClickListener
                }
                isEnabled = true
                swFloating.isChecked = true
            } else {
                isEnabled = false
                swFloating.isChecked = false
            }
        }

        // 整理币种候选池：已保存的悬浮窗币种保持既有排序置顶，然后是自选币，最后是预置热门币
        val savedFloating = Prefs.getFloatingSymbols(activity)
        val watchlist = Prefs.getWatchlist(activity)
        val candidateSymbols = ArrayList<String>()

        savedFloating.forEach {
            val norm = Symbols.expand(it) ?: it
            if (!candidateSymbols.contains(norm)) candidateSymbols.add(norm)
        }
        watchlist.forEach {
            val norm = Symbols.expand(it) ?: it
            if (!candidateSymbols.contains(norm)) candidateSymbols.add(norm)
        }
        POPULAR_CANDIDATES.forEach {
            val norm = Symbols.expand(it) ?: it
            if (!candidateSymbols.contains(norm)) candidateSymbols.add(norm)
        }

        val selectedSymbols = LinkedHashSet(savedFloating.take(Prefs.MAX_FLOATING_COINS))
        if (selectedSymbols.isEmpty() && candidateSymbols.isNotEmpty()) {
            selectedSymbols.addAll(candidateSymbols.take(3))
        }

        fun updateCounter() {
            tvSelectedCount.text = activity.getString(R.string.floating_selected_count, selectedSymbols.size)
        }
        updateCounter()

        class CoinSelectAdapter : RecyclerView.Adapter<CoinSelectAdapter.VH>() {
            var itemTouchHelper: ItemTouchHelper? = null

            inner class VH(v: View) : RecyclerView.ViewHolder(v) {
                val row: View = v.findViewById(R.id.llSelectRow)
                val cb: CheckBox = v.findViewById(R.id.cbSelect)
                val tvClean: TextView = v.findViewById(R.id.tvSelectCleanSymbol)
                val tvFull: TextView = v.findViewById(R.id.tvSelectFullSymbol)
                val tvBadge: TextView = v.findViewById(R.id.tvSelectBadge)
                val ivDragHandle: ImageView = v.findViewById(R.id.ivDragHandle)
            }

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_floating_symbol_select, parent, false)
                return VH(v)
            }

            override fun getItemCount(): Int = candidateSymbols.size

            @SuppressLint("ClickableViewAccessibility")
            override fun onBindViewHolder(holder: VH, position: Int) {
                val sym = candidateSymbols[position]
                val clean = FloatingFmt.cleanSymbol(sym)
                val isWatch = watchlist.contains(sym)
                val isChecked = selectedSymbols.contains(sym)

                holder.tvClean.text = clean
                holder.tvFull.text = sym
                holder.cb.isChecked = isChecked

                if (isWatch) {
                    holder.tvBadge.visibility = View.VISIBLE
                    holder.tvBadge.setText(R.string.floating_in_watchlist)
                } else {
                    holder.tvBadge.visibility = View.GONE
                }

                holder.row.setOnClickListener {
                    if (selectedSymbols.contains(sym)) {
                        if (selectedSymbols.size <= 1) {
                            Toast.makeText(activity, R.string.floating_limit_min, Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        selectedSymbols.remove(sym)
                        holder.cb.isChecked = false
                    } else {
                        if (selectedSymbols.size >= Prefs.MAX_FLOATING_COINS) {
                            Toast.makeText(activity, R.string.floating_limit_max, Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        selectedSymbols.add(sym)
                        holder.cb.isChecked = true
                    }
                    updateCounter()
                }

                // 触摸右侧拖拽手柄时立即触发拖拽排序
                holder.ivDragHandle.setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        itemTouchHelper?.startDrag(holder)
                    }
                    false
                }
            }
        }

        val adapter = CoinSelectAdapter()

        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPos = viewHolder.bindingAdapterPosition
                val toPos = target.bindingAdapterPosition
                if (fromPos == RecyclerView.NO_POSITION || toPos == RecyclerView.NO_POSITION) return false
                val item = candidateSymbols.removeAt(fromPos)
                candidateSymbols.add(toPos, item)
                adapter.notifyItemMoved(fromPos, toPos)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun isLongPressDragEnabled(): Boolean = true

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    viewHolder?.itemView?.animate()?.scaleX(1.02f)?.scaleY(1.02f)?.alpha(0.88f)?.setDuration(120)?.start()
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.animate().scaleX(1.0f).scaleY(1.0f).alpha(1.0f).setDuration(120).start()
            }
        })

        adapter.itemTouchHelper = touchHelper
        rvCoins.layoutManager = LinearLayoutManager(activity)
        rvCoins.adapter = adapter
        touchHelper.attachToRecyclerView(rvCoins)

        var isConfirmed = false

        AlertDialog.Builder(activity)
            .setTitle(R.string.settings_floating_title)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                isConfirmed = true

                // 保存透明度
                val selectedAlphaPct = Prefs.MIN_FLOATING_ALPHA + seekAlpha.progress
                Prefs.saveFloatingAlphaPct(activity, selectedAlphaPct)
                if (FloatingWindowService.isRunning) {
                    FloatingWindowService.updateLiveAlpha(selectedAlphaPct)
                }

                // 保存币种列表（保留拖拽排序后的最新顺序，最多 MAX_FLOATING_COINS 个）
                val saveList = candidateSymbols.filter { selectedSymbols.contains(it) }.take(Prefs.MAX_FLOATING_COINS)
                if (saveList.isNotEmpty()) {
                    Prefs.saveFloatingSymbols(activity, saveList)
                }

                // 保存开关状态
                val wasEnabled = Prefs.getFloatingEnabled(activity)
                if (isEnabled && checkOverlayPermission()) {
                    Prefs.saveFloatingEnabled(activity, true)
                    FloatingWindowService.start(activity)
                    FloatingWindowService.reload(activity)
                } else {
                    Prefs.saveFloatingEnabled(activity, false)
                    if (wasEnabled) {
                        FloatingWindowService.stop(activity)
                    }
                }

                dialog.dismiss()
                onChanged()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener {
                if (!isConfirmed && FloatingWindowService.isRunning) {
                    // 若未点击确定即关闭弹窗，恢复原本的不透明度
                    FloatingWindowService.updateLiveAlpha(originalAlphaPct)
                }
            }
            .show()
    }

    private fun floatingLabel(activity: Activity): String {
        val enabled = Prefs.getFloatingEnabled(activity)
        val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(activity)
        } else {
            true
        }
        return if (enabled && hasPermission) {
            activity.getString(R.string.floating_on, Prefs.getFloatingSymbols(activity).size)
        } else {
            activity.getString(R.string.floating_off)
        }
    }
}
