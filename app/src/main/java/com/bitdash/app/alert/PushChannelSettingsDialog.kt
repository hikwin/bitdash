package com.bitdash.app.alert

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.bitdash.app.R

object PushChannelSettingsDialog {

    fun show(activity: Activity, onSaved: (() -> Unit)? = null) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_push_channels, null)
        val dialog = AlertDialog.Builder(activity)
            .setView(view)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // Webhook 控件
        val rgWebhookPlatform = view.findViewById<RadioGroup>(R.id.rgWebhookPlatform)
        val etWebhookUrl = view.findViewById<EditText>(R.id.etWebhookUrl)
        val layoutDingSecret = view.findViewById<LinearLayout>(R.id.layoutDingSecret)
        val etWebhookSecret = view.findViewById<EditText>(R.id.etWebhookSecret)
        val layoutGenericTemplate = view.findViewById<LinearLayout>(R.id.layoutGenericTemplate)
        val etWebhookTemplate = view.findViewById<EditText>(R.id.etWebhookTemplate)
        val btnTestWebhook = view.findViewById<Button>(R.id.btnTestWebhook)
        val tvWebhookTestResult = view.findViewById<TextView>(R.id.tvWebhookTestResult)

        // HTTP 控件
        val rgHttpMethod = view.findViewById<RadioGroup>(R.id.rgHttpMethod)
        val etHttpUrl = view.findViewById<EditText>(R.id.etHttpUrl)
        val etHttpHeaders = view.findViewById<EditText>(R.id.etHttpHeaders)
        val layoutHttpBody = view.findViewById<LinearLayout>(R.id.layoutHttpBody)
        val etHttpBody = view.findViewById<EditText>(R.id.etHttpBody)
        val btnTestHttp = view.findViewById<Button>(R.id.btnTestHttp)
        val tvHttpTestResult = view.findViewById<TextView>(R.id.tvHttpTestResult)

        val btnCancel = view.findViewById<Button>(R.id.btnCancelPushConfig)
        val btnCancelBottom = view.findViewById<Button>(R.id.btnCancelPushSettings)
        val btnSave = view.findViewById<Button>(R.id.btnSavePushSettings)

        // 回填现有配置
        val currentPlatform = PushChannelPrefs.getWebhookPlatform(activity)
        when (currentPlatform) {
            WebhookPlatform.DINGTALK -> view.findViewById<RadioButton>(R.id.rbPlatDingTalk)?.isChecked = true
            WebhookPlatform.FEISHU -> view.findViewById<RadioButton>(R.id.rbPlatFeishu)?.isChecked = true
            WebhookPlatform.WECOM -> view.findViewById<RadioButton>(R.id.rbPlatWecom)?.isChecked = true
            WebhookPlatform.GENERIC -> view.findViewById<RadioButton>(R.id.rbPlatGeneric)?.isChecked = true
        }

        etWebhookUrl.setText(PushChannelPrefs.getWebhookUrl(activity))
        etWebhookSecret.setText(PushChannelPrefs.getWebhookSecret(activity))
        etWebhookTemplate.setText(PushChannelPrefs.getWebhookTemplate(activity))

        fun updateWebhookPlatformUI(platform: WebhookPlatform) {
            when (platform) {
                WebhookPlatform.DINGTALK -> {
                    layoutDingSecret.visibility = View.VISIBLE
                    layoutGenericTemplate.visibility = View.GONE
                    if (etWebhookUrl.text.isNullOrBlank()) {
                        etWebhookUrl.hint = "https://oapi.dingtalk.com/robot/send?access_token=xxx"
                    }
                }
                WebhookPlatform.FEISHU -> {
                    layoutDingSecret.visibility = View.GONE
                    layoutGenericTemplate.visibility = View.GONE
                    if (etWebhookUrl.text.isNullOrBlank()) {
                        etWebhookUrl.hint = "https://open.feishu.cn/open-apis/bot/v2/hook/xxx"
                    }
                }
                WebhookPlatform.WECOM -> {
                    layoutDingSecret.visibility = View.GONE
                    layoutGenericTemplate.visibility = View.GONE
                    if (etWebhookUrl.text.isNullOrBlank()) {
                        etWebhookUrl.hint = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=xxx"
                    }
                }
                WebhookPlatform.GENERIC -> {
                    layoutDingSecret.visibility = View.GONE
                    layoutGenericTemplate.visibility = View.VISIBLE
                    if (etWebhookUrl.text.isNullOrBlank()) {
                        etWebhookUrl.hint = "https://your-custom-webhook-url.com/api"
                    }
                }
            }
        }

        updateWebhookPlatformUI(currentPlatform)

        rgWebhookPlatform.setOnCheckedChangeListener { _, checkedId ->
            val plat = when (checkedId) {
                R.id.rbPlatFeishu -> WebhookPlatform.FEISHU
                R.id.rbPlatWecom -> WebhookPlatform.WECOM
                R.id.rbPlatGeneric -> WebhookPlatform.GENERIC
                else -> WebhookPlatform.DINGTALK
            }
            updateWebhookPlatformUI(plat)
        }

        // 回填 HTTP 配置
        val currentMethod = PushChannelPrefs.getHttpMethod(activity)
        if (currentMethod.equals("GET", ignoreCase = true)) {
            view.findViewById<RadioButton>(R.id.rbHttpGET)?.isChecked = true
            layoutHttpBody.visibility = View.GONE
        } else {
            view.findViewById<RadioButton>(R.id.rbHttpPOST)?.isChecked = true
            layoutHttpBody.visibility = View.VISIBLE
        }

        etHttpUrl.setText(PushChannelPrefs.getHttpUrl(activity))
        etHttpHeaders.setText(PushChannelPrefs.getHttpHeaders(activity))
        etHttpBody.setText(PushChannelPrefs.getHttpBody(activity))

        rgHttpMethod.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rbHttpGET) {
                layoutHttpBody.visibility = View.GONE
            } else {
                layoutHttpBody.visibility = View.VISIBLE
            }
        }

        // 临时保存并在内存中生效供测试
        fun saveCurrentInputs() {
            val selectedPlat = when (rgWebhookPlatform.checkedRadioButtonId) {
                R.id.rbPlatFeishu -> WebhookPlatform.FEISHU
                R.id.rbPlatWecom -> WebhookPlatform.WECOM
                R.id.rbPlatGeneric -> WebhookPlatform.GENERIC
                else -> WebhookPlatform.DINGTALK
            }
            PushChannelPrefs.setWebhookPlatform(activity, selectedPlat)
            PushChannelPrefs.setWebhookUrl(activity, etWebhookUrl.text.toString())
            PushChannelPrefs.setWebhookSecret(activity, etWebhookSecret.text.toString())
            PushChannelPrefs.setWebhookTemplate(activity, etWebhookTemplate.text.toString())

            val selectedMethod = if (rgHttpMethod.checkedRadioButtonId == R.id.rbHttpGET) "GET" else "POST"
            PushChannelPrefs.setHttpMethod(activity, selectedMethod)
            PushChannelPrefs.setHttpUrl(activity, etHttpUrl.text.toString())
            PushChannelPrefs.setHttpHeaders(activity, etHttpHeaders.text.toString())
            PushChannelPrefs.setHttpBody(activity, etHttpBody.text.toString())
        }

        // 测试 Webhook
        btnTestWebhook.setOnClickListener {
            val url = etWebhookUrl.text.toString().trim()
            if (url.isBlank()) {
                Toast.makeText(activity, "请先输入 Webhook URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            saveCurrentInputs()
            tvWebhookTestResult.text = "正在发送测试消息..."
            tvWebhookTestResult.setTextColor(activity.getColor(R.color.brand))

            PushSender.sendWebhook(
                context = activity,
                symbol = "BTC-USDT",
                title = "【测试】行情预警测试消息",
                message = "这是一条来自 BitDash 的 Webhook 测试消息，通道配置正常！",
                price = 68888.88
            ) { success, resp ->
                tvWebhookTestResult.text = resp
                tvWebhookTestResult.setTextColor(activity.getColor(if (success) R.color.up else R.color.down))
                Toast.makeText(activity, if (success) "Webhook 测试成功！" else "Webhook 发送失败，请检查 URL", Toast.LENGTH_SHORT).show()
            }
        }

        // 测试 HTTP
        btnTestHttp.setOnClickListener {
            val url = etHttpUrl.text.toString().trim()
            if (url.isBlank()) {
                Toast.makeText(activity, "请先输入 HTTP 接口 URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            saveCurrentInputs()
            tvHttpTestResult.text = "正在请求 HTTP 接口..."
            tvHttpTestResult.setTextColor(activity.getColor(R.color.brand))

            PushSender.sendHttp(
                context = activity,
                symbol = "BTC-USDT",
                title = "【测试】HTTP 预警接口测试",
                message = "这是一条来自 BitDash 的 HTTP 接口测试请求",
                price = 68888.88
            ) { success, resp ->
                tvHttpTestResult.text = resp
                tvHttpTestResult.setTextColor(activity.getColor(if (success) R.color.up else R.color.down))
                Toast.makeText(activity, if (success) "HTTP 接口请求成功！" else "HTTP 请求失败，请检查参数", Toast.LENGTH_SHORT).show()
            }
        }

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnCancelBottom.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            saveCurrentInputs()
            Toast.makeText(activity, "推送渠道配置已保存", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            onSaved?.invoke()
        }

        dialog.show()
    }
}
