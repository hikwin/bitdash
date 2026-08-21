package com.bitdash.app.alert

import android.app.Activity
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bitdash.app.R
import com.bitdash.app.market.Fmt
import com.bitdash.app.market.Markets
import com.bitdash.app.market.Palette
import com.bitdash.app.market.Prefs
import com.bitdash.app.market.Symbols
import com.bitdash.app.market.Ticker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PriceAlertDialog {

    private val POPULAR_COINS = listOf("BTC-USDT", "ETH-USDT", "SOL-USDT", "DOGE-USDT", "BNB-USDT", "XRP-USDT", "PEPE-USDT", "AVAX-USDT", "SUI-USDT")

    fun show(activity: Activity, prefillSymbol: String? = null, onDismiss: () -> Unit = {}) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                activity.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2001)
            }
        }

        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_price_alerts, null)
        val rvAlerts = dialogView.findViewById<RecyclerView>(R.id.rvAlerts)
        val layoutEmpty = dialogView.findViewById<View>(R.id.layoutEmptyAlerts)
        val btnAdd = dialogView.findViewById<Button>(R.id.btnAddAlert)
        val btnClose = dialogView.findViewById<Button>(R.id.btnCloseAlerts)

        rvAlerts.layoutManager = LinearLayoutManager(activity)

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        fun refreshList() {
            val alerts = PriceAlertManager.getAlerts(activity)
            if (alerts.isEmpty()) {
                layoutEmpty.visibility = View.VISIBLE
                rvAlerts.visibility = View.GONE
            } else {
                layoutEmpty.visibility = View.GONE
                rvAlerts.visibility = View.VISIBLE
                rvAlerts.adapter = AlertsAdapter(activity, alerts) {
                    refreshList()
                }
            }
        }

        btnAdd.setOnClickListener {
            showAddDialog(activity, prefillSymbol) {
                refreshList()
            }
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            onDismiss()
        }

        refreshList()
        dialog.show()
    }

    fun showAddDialog(activity: Activity, prefillSymbol: String? = null, onAdded: () -> Unit) {
        val addView = LayoutInflater.from(activity).inflate(R.layout.dialog_add_price_alert, null)
        val addDialog = AlertDialog.Builder(activity)
            .setView(addView)
            .create()

        addDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val rgMode = addView.findViewById<RadioGroup>(R.id.rgAlertMode)
        val layoutTarget = addView.findViewById<View>(R.id.layoutTargetMode)
        val layoutVol = addView.findViewById<View>(R.id.layoutVolatilityMode)

        val etSymbol = addView.findViewById<EditText>(R.id.etAlertSymbol)
        val btnSelectCoin = addView.findViewById<Button>(R.id.btnSelectCoin)
        val layoutQuickCoins = addView.findViewById<LinearLayout>(R.id.layoutQuickCoins)
        val tvRefPrice = addView.findViewById<TextView>(R.id.tvCurrentRefPrice)

        // 目标价控件
        val rbTargetAbove = addView.findViewById<RadioButton>(R.id.rbTargetAbove)
        val etTargetPrice = addView.findViewById<EditText>(R.id.etTargetPrice)

        // 异动控件
        val rgVolWindow = addView.findViewById<RadioGroup>(R.id.rgVolWindow)
        val etVolThreshold = addView.findViewById<EditText>(R.id.etVolThreshold)
        val rgVolDir = addView.findViewById<RadioGroup>(R.id.rgVolDirection)

        // 声音与强提醒控件
        val rgSoundMode = addView.findViewById<RadioGroup>(R.id.rgSoundMode)
        val tvCustomSoundName = addView.findViewById<TextView>(R.id.tvCustomSoundName)
        val cbStrongAlert = addView.findViewById<CheckBox>(R.id.cbStrongAlert)
        val layoutStrongSettings = addView.findViewById<LinearLayout>(R.id.layoutStrongSettings)
        val rgStrongCount = addView.findViewById<RadioGroup>(R.id.rgStrongCount)
        val rgStrongInterval = addView.findViewById<RadioGroup>(R.id.rgStrongInterval)

        // 4大推送渠道控件
        val cbNotifySystem = addView.findViewById<CheckBox>(R.id.cbNotifySystem)
        val cbNotifyToast = addView.findViewById<CheckBox>(R.id.cbNotifyToast)
        val cbNotifyWebhook = addView.findViewById<CheckBox>(R.id.cbNotifyWebhook)
        val cbNotifyHttp = addView.findViewById<CheckBox>(R.id.cbNotifyHttp)
        val btnConfigPushChannels = addView.findViewById<Button>(R.id.btnConfigPushChannels)

        btnConfigPushChannels?.setOnClickListener {
            PushChannelSettingsDialog.show(activity)
        }

        cbNotifyWebhook?.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && PushChannelPrefs.getWebhookUrl(activity).isBlank()) {
                Toast.makeText(activity, "请先配置 Webhook 机器人地址", Toast.LENGTH_SHORT).show()
                PushChannelSettingsDialog.show(activity)
            }
        }

        cbNotifyHttp?.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && PushChannelPrefs.getHttpUrl(activity).isBlank()) {
                Toast.makeText(activity, "请先配置 HTTP 接口地址", Toast.LENGTH_SHORT).show()
                PushChannelSettingsDialog.show(activity)
            }
        }

        val cbRepeat = addView.findViewById<CheckBox>(R.id.cbAlertRepeat)
        val btnCancel = addView.findViewById<Button>(R.id.btnCancelAddAlert)
        val btnSave = addView.findViewById<Button>(R.id.btnSaveAlert)

        var selectedSoundMode = "SILENT"
        var selectedSoundUri = ""
        var selectedSoundTitle = ""

        var fetchJob: Job? = null

        // 模式切换
        rgMode.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rbModeTarget) {
                layoutTarget.visibility = View.VISIBLE
                layoutVol.visibility = View.GONE
            } else {
                layoutTarget.visibility = View.GONE
                layoutVol.visibility = View.VISIBLE
            }
        }

        // 铃声模式切换（默认无铃声/静音，勾选自定义铃声时展开选择）
        rgSoundMode.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rbSoundCustom) {
                selectedSoundMode = "CUSTOM"
                tvCustomSoundName.visibility = View.VISIBLE
                if (selectedSoundUri.isBlank()) {
                    showRingtonePickerDialog(activity, selectedSoundUri, selectedSoundTitle) { title, uri ->
                        selectedSoundTitle = title
                        selectedSoundUri = uri
                        tvCustomSoundName.text = "🎵 已选铃声: $title (点击更换)"
                    }
                }
            } else {
                selectedSoundMode = "SILENT"
                tvCustomSoundName.visibility = View.GONE
            }
        }

        tvCustomSoundName.setOnClickListener {
            showRingtonePickerDialog(activity, selectedSoundUri, selectedSoundTitle) { title, uri ->
                selectedSoundTitle = title
                selectedSoundUri = uri
                tvCustomSoundName.text = "🎵 已选铃声: $title (点击更换)"
            }
        }

        // 强提醒开关
        cbStrongAlert.setOnCheckedChangeListener { _, isChecked ->
            layoutStrongSettings.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        var currentRefPrice = 0.0

        fun updateRefPrice(sym: String, forceTargetPrice: Boolean = false) {
            fetchJob?.cancel()
            val cleanSym = sym.trim().uppercase()
            if (cleanSym.isBlank()) {
                currentRefPrice = 0.0
                tvRefPrice.visibility = View.GONE
                return
            }

            val p = PriceAlertManager.getLatestPrice(cleanSym)
            if (p != null && p > 0.0) {
                currentRefPrice = p
                tvRefPrice.visibility = View.VISIBLE
                tvRefPrice.text = "当前参考价: $${Fmt.price(currentRefPrice)}"
                if (forceTargetPrice || etTargetPrice.text.isNullOrBlank()) {
                    etTargetPrice.setText(Fmt.price(currentRefPrice).replace(",", ""))
                }
            } else {
                tvRefPrice.visibility = View.VISIBLE
                tvRefPrice.text = "正在查询最新价..."
                fetchJob = CoroutineScope(Dispatchers.Main).launch {
                    try {
                        val ticker = withContext(Dispatchers.IO) {
                            Markets.withSource(activity) { it.ticker(cleanSym) }
                        }
                        if (ticker.valid) {
                            currentRefPrice = ticker.last
                            PriceAlertManager.onPriceUpdate(activity, cleanSym, ticker.last)
                            tvRefPrice.visibility = View.VISIBLE
                            tvRefPrice.text = "当前参考价: $${Fmt.price(currentRefPrice)}"
                            if (forceTargetPrice || etTargetPrice.text.isNullOrBlank()) {
                                etTargetPrice.setText(Fmt.price(currentRefPrice).replace(",", ""))
                            }
                        } else {
                            tvRefPrice.visibility = View.GONE
                        }
                    } catch (_: Exception) {
                        tvRefPrice.visibility = View.GONE
                    }
                }
            }
        }

        // 默认币种初始化
        val initialSymbol = prefillSymbol ?: Prefs.getWatchlist(activity).firstOrNull() ?: "BTC-USDT"
        etSymbol.setText(initialSymbol)
        updateRefPrice(initialSymbol, forceTargetPrice = true)

        etSymbol.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val input = s?.toString()?.trim() ?: ""
                updateRefPrice(input, forceTargetPrice = false)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // 点击“选择 ▾”按钮打开币种搜索选择器
        btnSelectCoin?.setOnClickListener {
            showCoinPickerDialog(activity) { selectedSymbol, price ->
                etSymbol.setText(selectedSymbol)
                if (price > 0.0) {
                    currentRefPrice = price
                    tvRefPrice.visibility = View.VISIBLE
                    tvRefPrice.text = "当前参考价: $${Fmt.price(currentRefPrice)}"
                    etTargetPrice.setText(Fmt.price(currentRefPrice).replace(",", ""))
                } else {
                    updateRefPrice(selectedSymbol, forceTargetPrice = true)
                }
            }
        }

        // 快捷币种流：点击后不仅填入币种，而且自动带入最新参考价到目标价输入框
        val candidateCoins = (Prefs.getWatchlist(activity) + POPULAR_COINS).distinct()
        for (coin in candidateCoins.take(8)) {
            val chip = TextView(activity).apply {
                text = coin.substringBefore("-")
                textSize = 11f
                setPadding((8f * resources.displayMetrics.density).toInt(), (4f * resources.displayMetrics.density).toInt(), (8f * resources.displayMetrics.density).toInt(), (4f * resources.displayMetrics.density).toInt())
                setBackgroundResource(R.drawable.bg_pill)
                setTextColor(activity.getColor(R.color.text_muted))
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = (6f * resources.displayMetrics.density).toInt()
                }
                layoutParams = lp
                setOnClickListener {
                    etSymbol.setText(coin)
                    updateRefPrice(coin, forceTargetPrice = true)
                }
            }
            layoutQuickCoins.addView(chip)
        }

        // 目标价百分比快捷微调
        fun applyPriceDelta(pct: Double) {
            val base = if (currentRefPrice > 0) currentRefPrice else etTargetPrice.text.toString().toDoubleOrNull() ?: 0.0
            if (base > 0) {
                val target = base * (1.0 + pct / 100.0)
                etTargetPrice.setText(Fmt.price(target).replace(",", ""))
                if (pct > 0) {
                    rbTargetAbove.isChecked = true
                } else {
                    addView.findViewById<RadioButton>(R.id.rbTargetBelow)?.isChecked = true
                }
            }
        }

        addView.findViewById<View>(R.id.btnQuickMinus5)?.setOnClickListener { applyPriceDelta(-5.0) }
        addView.findViewById<View>(R.id.btnQuickMinus2)?.setOnClickListener { applyPriceDelta(-2.0) }
        addView.findViewById<View>(R.id.btnQuickPlus2)?.setOnClickListener { applyPriceDelta(2.0) }
        addView.findViewById<View>(R.id.btnQuickPlus5)?.setOnClickListener { applyPriceDelta(5.0) }
        addView.findViewById<View>(R.id.btnQuickPlus10)?.setOnClickListener { applyPriceDelta(10.0) }

        // 异动阈值快捷填入
        fun setVolThresh(thresh: Double) {
            etVolThreshold.setText(thresh.toString())
        }
        addView.findViewById<View>(R.id.btnVolThresh2)?.setOnClickListener { setVolThresh(2.0) }
        addView.findViewById<View>(R.id.btnVolThresh3)?.setOnClickListener { setVolThresh(3.0) }
        addView.findViewById<View>(R.id.btnVolThresh5)?.setOnClickListener { setVolThresh(5.0) }
        addView.findViewById<View>(R.id.btnVolThresh8)?.setOnClickListener { setVolThresh(8.0) }
        addView.findViewById<View>(R.id.btnVolThresh10)?.setOnClickListener { setVolThresh(10.0) }

        btnCancel.setOnClickListener {
            addDialog.dismiss()
        }

        btnSave.setOnClickListener {
            val symInput = etSymbol.text.toString().trim().uppercase()
            if (symInput.isBlank()) {
                Toast.makeText(activity, "请输入监控币种", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val isStrong = cbStrongAlert.isChecked
            val strongCount = when (rgStrongCount.checkedRadioButtonId) {
                R.id.rbStrongCount1 -> 1
                R.id.rbStrongCount3 -> 3
                R.id.rbStrongCount5 -> 5
                R.id.rbStrongCount10 -> 10
                R.id.rbStrongCountInf -> 999
                else -> 2
            }
            val strongInterval = when (rgStrongInterval.checkedRadioButtonId) {
                R.id.rbStrongInt15s -> 15
                R.id.rbStrongInt1m -> 60
                R.id.rbStrongInt2m -> 120
                else -> 30
            }

            val nSystem = cbNotifySystem?.isChecked ?: true
            val nToast = cbNotifyToast?.isChecked ?: false
            val nWebhook = cbNotifyWebhook?.isChecked ?: false
            val nHttp = cbNotifyHttp?.isChecked ?: false

            val isTarget = rgMode.checkedRadioButtonId == R.id.rbModeTarget
            if (isTarget) {
                val p = etTargetPrice.text.toString().trim().toDoubleOrNull()
                if (p == null || p <= 0.0) {
                    Toast.makeText(activity, "请输入有效的目标价格", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val isAbove = rbTargetAbove.isChecked
                val alert = PriceAlert(
                    symbol = symInput,
                    type = if (isAbove) AlertType.PRICE_ABOVE else AlertType.PRICE_BELOW,
                    targetPrice = p,
                    repeat = cbRepeat.isChecked,
                    soundMode = selectedSoundMode,
                    soundUri = selectedSoundUri,
                    soundTitle = selectedSoundTitle,
                    isStrongAlert = isStrong,
                    strongMaxCount = strongCount,
                    strongIntervalSeconds = strongInterval,
                    notifySystem = nSystem,
                    notifyToast = nToast,
                    notifyWebhook = nWebhook,
                    notifyHttp = nHttp
                )
                PriceAlertManager.addAlert(activity, alert)
                Toast.makeText(activity, "已添加目标价预警", Toast.LENGTH_SHORT).show()
            } else {
                val v = etVolThreshold.text.toString().trim().toDoubleOrNull()
                if (v == null || v <= 0.0) {
                    Toast.makeText(activity, "请输入有效的波动阈值百分比", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val winMin = when (rgVolWindow.checkedRadioButtonId) {
                    R.id.rbWin1m -> 1
                    R.id.rbWin3m -> 3
                    R.id.rbWin15m -> 15
                    R.id.rbWin30m -> 30
                    else -> 5
                }
                val dir = when (rgVolDir.checkedRadioButtonId) {
                    R.id.rbVolSurge -> VolatilityDirection.SURGE
                    R.id.rbVolPlunge -> VolatilityDirection.PLUNGE
                    else -> VolatilityDirection.BOTH
                }
                val alert = PriceAlert(
                    symbol = symInput,
                    type = AlertType.VOLATILITY,
                    windowMinutes = winMin,
                    pctThreshold = v,
                    direction = dir,
                    repeat = cbRepeat.isChecked,
                    soundMode = selectedSoundMode,
                    soundUri = selectedSoundUri,
                    soundTitle = selectedSoundTitle,
                    isStrongAlert = isStrong,
                    strongMaxCount = strongCount,
                    strongIntervalSeconds = strongInterval,
                    notifySystem = nSystem,
                    notifyToast = nToast,
                    notifyWebhook = nWebhook,
                    notifyHttp = nHttp
                )
                PriceAlertManager.addAlert(activity, alert)
                Toast.makeText(activity, "已添加异动预警", Toast.LENGTH_SHORT).show()
            }

            addDialog.dismiss()
            onAdded()
        }

        addDialog.show()
    }

    data class RingtoneItem(
        val title: String,
        val uri: String
    )

    /**
     * 手机内置铃声选择器弹窗（带独立播放试听按钮，支持自动滚动定位到已选铃声）
     */
    private fun showRingtonePickerDialog(
        activity: Activity,
        currentUri: String = "",
        currentTitle: String = "",
        onSelected: (String, String) -> Unit
    ) {
        val pickerView = LayoutInflater.from(activity).inflate(R.layout.dialog_ringtone_picker, null)
        val rvRingtones = pickerView.findViewById<RecyclerView>(R.id.rvRingtones)
        val btnCancel = pickerView.findViewById<Button>(R.id.btnCancelRingtonePicker)

        val layoutManager = LinearLayoutManager(activity)
        rvRingtones.layoutManager = layoutManager

        val dialog = AlertDialog.Builder(activity)
            .setView(pickerView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val manager = RingtoneManager(activity).apply {
            setType(RingtoneManager.TYPE_NOTIFICATION or RingtoneManager.TYPE_RINGTONE)
        }
        val cursor = manager.cursor
        val list = ArrayList<RingtoneItem>()

        while (cursor.moveToNext()) {
            val title = cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX)
            val uri = manager.getRingtoneUri(cursor.position)?.toString() ?: ""
            if (title.isNotBlank() && uri.isNotBlank()) {
                list.add(RingtoneItem(title, uri))
            }
        }

        if (list.isEmpty()) {
            Toast.makeText(activity, "未检测到手机内置铃声，将使用系统默认铃声", Toast.LENGTH_SHORT).show()
            onSelected("默认通知音", RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION).toString())
            return
        }

        // 查找已选铃声在列表中的索引
        val selectedIndex = list.indexOfFirst {
            (currentUri.isNotBlank() && it.uri == currentUri) || (currentTitle.isNotBlank() && it.title == currentTitle)
        }

        val adapter = RingtonePickerAdapter(activity, list, currentUri, currentTitle) { selectedItem ->
            dialog.dismiss()
            onSelected(selectedItem.title, selectedItem.uri)
        }
        rvRingtones.adapter = adapter

        // 自动滚动到已选铃声位置（居中偏上展示，方便快速查看）
        if (selectedIndex >= 0) {
            rvRingtones.post {
                val offset = (rvRingtones.height / 3).coerceAtLeast(0)
                layoutManager.scrollToPositionWithOffset(selectedIndex, offset)
            }
        }

        btnCancel.setOnClickListener {
            adapter.stopPlayback()
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            adapter.stopPlayback()
        }

        dialog.show()
    }

    private class RingtonePickerAdapter(
        private val activity: Activity,
        private val list: List<RingtoneItem>,
        private val currentUri: String,
        private val currentTitle: String,
        private val onSelect: (RingtoneItem) -> Unit
    ) : RecyclerView.Adapter<RingtonePickerAdapter.VH>() {

        private var playingPos: Int = -1
        private var currentPlaying: Ringtone? = null

        fun stopPlayback() {
            try {
                currentPlaying?.stop()
            } catch (_: Exception) {}
            currentPlaying = null
            val prev = playingPos
            playingPos = -1
            if (prev >= 0) notifyItemChanged(prev)
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val layoutInfo: View = v.findViewById(R.id.layoutRingtoneInfo)
            val tvTitle: TextView = v.findViewById(R.id.tvRingtoneTitle)
            val tvSub: TextView = v.findViewById(R.id.tvRingtoneSub)
            val btnPlay: TextView = v.findViewById(R.id.btnPlayRingtone)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_ringtone_picker, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = list.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = list[position]
            val isCurrentChosen = (currentUri.isNotBlank() && item.uri == currentUri) || (currentTitle.isNotBlank() && item.title == currentTitle)
            val isPlaying = position == playingPos

            if (isCurrentChosen) {
                holder.tvTitle.text = "${item.title}  ✓"
                holder.tvTitle.setTextColor(activity.getColor(R.color.brand))
            } else {
                holder.tvTitle.text = item.title
                holder.tvTitle.setTextColor(activity.getColor(R.color.text_main))
            }

            if (isPlaying) {
                holder.btnPlay.text = "⏹ 播放中"
                holder.btnPlay.setTextColor(activity.getColor(R.color.up))
                holder.tvSub.text = if (isCurrentChosen) "正在试听 (当前已选)... 点击直接确认" else "正在试听中... 点击名称可直接选择"
                holder.tvSub.setTextColor(activity.getColor(R.color.brand))
            } else {
                holder.btnPlay.text = "▶ 试听"
                holder.btnPlay.setTextColor(activity.getColor(R.color.brand))
                holder.tvSub.text = if (isCurrentChosen) "● 当前已选铃声 (点击确认或更换)" else "点击选择该铃声"
                holder.tvSub.setTextColor(activity.getColor(if (isCurrentChosen) R.color.brand else R.color.text_dim))
            }

            // 点击右侧独立播放按钮：仅控制试听播放/停止，不选择铃声！
            holder.btnPlay.setOnClickListener {
                if (playingPos == position) {
                    stopPlayback()
                } else {
                    stopPlayback()
                    try {
                        val ringtone = RingtoneManager.getRingtone(activity, Uri.parse(item.uri))
                        ringtone?.play()
                        currentPlaying = ringtone
                        playingPos = position
                        notifyDataSetChanged()
                    } catch (e: Exception) {
                        Toast.makeText(activity, "播放失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            // 点击左侧/中间非播放区域：停止播放并选择该铃声！
            holder.layoutInfo.setOnClickListener {
                stopPlayback()
                onSelect(item)
            }

            holder.itemView.setOnClickListener {
                stopPlayback()
                onSelect(item)
            }
        }
    }

    /**
     * 币种下拉搜索选择弹窗
     */
    private fun showCoinPickerDialog(activity: Activity, onCoinSelected: (String, Double) -> Unit) {
        val pickerView = LayoutInflater.from(activity).inflate(R.layout.dialog_select_coin, null)
        val etSearch = pickerView.findViewById<EditText>(R.id.etSearchCoin)
        val rvCoins = pickerView.findViewById<RecyclerView>(R.id.rvCoinPicker)
        val tvEmpty = pickerView.findViewById<TextView>(R.id.tvCoinPickerEmpty)
        val btnCancel = pickerView.findViewById<Button>(R.id.btnCancelCoinPicker)

        rvCoins.layoutManager = LinearLayoutManager(activity)

        val dialog = AlertDialog.Builder(activity)
            .setView(pickerView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        btnCancel.setOnClickListener { dialog.dismiss() }

        val allList = ArrayList<Ticker>()
        val displayList = ArrayList<Ticker>()

        // 初始装填：自选币种 + 热门币种（带当前已知缓存价）
        val initSymbols = (Prefs.getWatchlist(activity) + POPULAR_COINS).distinct()
        for (sym in initSymbols) {
            val cachedPrice = PriceAlertManager.getLatestPrice(sym) ?: 0.0
            allList.add(Ticker(sym, cachedPrice, 0.0, 0.0, 0.0, 0.0))
        }
        displayList.addAll(allList)

        val adapter = CoinPickerAdapter(activity, displayList) { selectedTicker ->
            dialog.dismiss()
            onCoinSelected(selectedTicker.symbol, selectedTicker.last)
        }
        rvCoins.adapter = adapter

        fun filter(query: String) {
            val q = query.trim().uppercase()
            displayList.clear()
            if (q.isEmpty()) {
                displayList.addAll(allList)
            } else {
                for (t in allList) {
                    if (t.symbol.uppercase().contains(q) || Symbols.compact(t.symbol).contains(q)) {
                        displayList.add(t)
                    }
                }
            }
            if (displayList.isEmpty()) {
                tvEmpty.visibility = View.VISIBLE
                rvCoins.visibility = View.GONE
            } else {
                tvEmpty.visibility = View.GONE
                rvCoins.visibility = View.VISIBLE
                adapter.notifyDataSetChanged()
            }
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                filter(s?.toString() ?: "")
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // 异步拉取当前源全部币种行情丰富列表
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val fullTickers = withContext(Dispatchers.IO) {
                    Markets.withSource(activity) { it.allTickers() }
                }
                if (fullTickers.isNotEmpty()) {
                    val map = HashMap<String, Ticker>(fullTickers.size)
                    for (t in fullTickers) {
                        map[t.symbol] = t
                        if (t.valid) {
                            PriceAlertManager.onPriceUpdate(activity, t.symbol, t.last)
                        }
                    }
                    allList.clear()
                    // 优先将自选币种排在最前，随后是其他市场币种
                    val watch = Prefs.getWatchlist(activity)
                    for (w in watch) {
                        map.remove(w)?.let { allList.add(it) }
                    }
                    allList.addAll(map.values.sortedByDescending { it.quoteVol24h })
                    filter(etSearch.text?.toString() ?: "")
                }
            } catch (_: Exception) {}
        }

        dialog.show()
    }

    private class CoinPickerAdapter(
        private val activity: Activity,
        private val list: List<Ticker>,
        private val onClick: (Ticker) -> Unit
    ) : RecyclerView.Adapter<CoinPickerAdapter.VH>() {

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvSymbol: TextView = v.findViewById(R.id.tvPickerSymbol)
            val tvBaseCoin: TextView = v.findViewById(R.id.tvPickerBaseCoin)
            val tvPrice: TextView = v.findViewById(R.id.tvPickerPrice)
            val tvChange: TextView = v.findViewById(R.id.tvPickerChange)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_coin_picker, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = list.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = list[position]
            holder.tvSymbol.text = item.symbol
            holder.tvBaseCoin.text = item.symbol.substringBefore("-")

            if (item.valid) {
                holder.tvPrice.text = "$${Fmt.price(item.last)}"
                if (item.open24h > 0) {
                    holder.tvChange.visibility = View.VISIBLE
                    holder.tvChange.text = Fmt.pct(item.changePct)
                    holder.tvChange.setTextColor(Palette.byDelta(activity, item.changePct))
                } else {
                    holder.tvChange.visibility = View.GONE
                }
            } else {
                holder.tvPrice.text = "—"
                holder.tvChange.visibility = View.GONE
            }

            holder.itemView.setOnClickListener {
                onClick(item)
            }
        }
    }

    private class AlertsAdapter(
        private val activity: Activity,
        private val list: List<PriceAlert>,
        private val onDataChanged: () -> Unit
    ) : RecyclerView.Adapter<AlertsAdapter.VH>() {

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val ivIcon: ImageView = v.findViewById(R.id.ivAlertIcon)
            val tvSymbol: TextView = v.findViewById(R.id.tvAlertSymbol)
            val tvTypeBadge: TextView = v.findViewById(R.id.tvAlertTypeBadge)
            val tvSummary: TextView = v.findViewById(R.id.tvAlertSummary)
            val tvStatus: TextView = v.findViewById(R.id.tvAlertStatus)
            val switchEnabled: SwitchCompat = v.findViewById(R.id.switchAlertEnabled)
            val btnDelete: View = v.findViewById(R.id.btnDeleteAlert)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_price_alert, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = list.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = list[position]
            holder.tvSymbol.text = item.symbol
            holder.tvSummary.text = item.summaryText()

            when (item.type) {
                AlertType.PRICE_ABOVE -> {
                    holder.tvTypeBadge.text = if (item.isStrongAlert) "⚡强提醒突破" else "目标突破"
                    holder.ivIcon.setImageResource(R.drawable.ic_trending_up)
                }
                AlertType.PRICE_BELOW -> {
                    holder.tvTypeBadge.text = if (item.isStrongAlert) "⚡强提醒跌破" else "跌破提醒"
                    holder.ivIcon.setImageResource(R.drawable.ic_trending_up)
                }
                AlertType.VOLATILITY -> {
                    holder.tvTypeBadge.text = if (item.isStrongAlert) "⚡强提醒${item.windowMinutes}m" else "${item.windowMinutes}m异动"
                    holder.ivIcon.setImageResource(R.drawable.ic_notifications)
                }
            }

            if (item.lastTriggeredTs > 0) {
                val timeStr = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(item.lastTriggeredTs))
                val strongInfo = if (item.isStrongAlert && item.strongCurrentCount > 0) " (已连提醒${item.strongCurrentCount}次)" else ""
                holder.tvStatus.text = "上次触发: $timeStr (触达价 ${Fmt.price(item.lastTriggeredPrice)})$strongInfo"
            } else {
                holder.tvStatus.text = if (item.enabled) "监控中 · 等待触发" else "已暂停监控"
            }

            holder.switchEnabled.setOnCheckedChangeListener(null)
            holder.switchEnabled.isChecked = item.enabled

            holder.switchEnabled.setOnCheckedChangeListener { _, isChecked ->
                PriceAlertManager.toggleAlert(activity, item.id, isChecked)
                onDataChanged()
            }

            holder.btnDelete.setOnClickListener {
                PriceAlertManager.deleteAlert(activity, item.id)
                onDataChanged()
                Toast.makeText(activity, "已删除该预警规则", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
