package com.bitdash.app.alert

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.widget.Toast
import com.bitdash.app.market.Fmt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

enum class WebhookPlatform(val code: String, val title: String) {
    DINGTALK("DINGTALK", "钉钉机器人"),
    FEISHU("FEISHU", "飞书机器人"),
    WECOM("WECOM", "企业微信"),
    GENERIC("GENERIC", "通用 Webhook");

    companion object {
        fun fromCode(code: String): WebhookPlatform =
            values().firstOrNull { it.code.equals(code, ignoreCase = true) } ?: DINGTALK
    }
}

object PushChannelPrefs {
    private const val PREFS_NAME = "bitdash_push_channels"

    private const val KEY_WEBHOOK_URL = "webhook_url"
    private const val KEY_WEBHOOK_PLATFORM = "webhook_platform"
    private const val KEY_WEBHOOK_SECRET = "webhook_secret"
    private const val KEY_WEBHOOK_TEMPLATE = "webhook_custom_template"

    private const val KEY_HTTP_URL = "http_url"
    private const val KEY_HTTP_METHOD = "http_method"
    private const val KEY_HTTP_HEADERS = "http_headers"
    private const val KEY_HTTP_BODY = "http_body"

    fun getWebhookUrl(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_WEBHOOK_URL, "") ?: ""

    fun setWebhookUrl(context: Context, url: String) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_WEBHOOK_URL, url.trim()).apply()

    fun getWebhookPlatform(context: Context): WebhookPlatform =
        WebhookPlatform.fromCode(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_WEBHOOK_PLATFORM, "DINGTALK") ?: "DINGTALK")

    fun setWebhookPlatform(context: Context, platform: WebhookPlatform) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_WEBHOOK_PLATFORM, platform.code).apply()

    fun getWebhookSecret(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_WEBHOOK_SECRET, "") ?: ""

    fun setWebhookSecret(context: Context, secret: String) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_WEBHOOK_SECRET, secret.trim()).apply()

    fun getWebhookTemplate(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_WEBHOOK_TEMPLATE, "{\"msg\":\"{title}\\n{message}\\n现价: {price}\\n时间: {time}\"}") ?: ""

    fun setWebhookTemplate(context: Context, template: String) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_WEBHOOK_TEMPLATE, template).apply()

    fun getHttpUrl(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_HTTP_URL, "") ?: ""

    fun setHttpUrl(context: Context, url: String) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_HTTP_URL, url.trim()).apply()

    fun getHttpMethod(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_HTTP_METHOD, "POST") ?: "POST"

    fun setHttpMethod(context: Context, method: String) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_HTTP_METHOD, method.uppercase()).apply()

    fun getHttpHeaders(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_HTTP_HEADERS, "Content-Type: application/json") ?: "Content-Type: application/json"

    fun setHttpHeaders(context: Context, headers: String) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_HTTP_HEADERS, headers).apply()

    fun getHttpBody(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_HTTP_BODY, "{\"symbol\":\"{symbol}\",\"price\":{price},\"title\":\"{title}\",\"message\":\"{message}\",\"time\":\"{time}\"}") ?: ""

    fun setHttpBody(context: Context, body: String) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_HTTP_BODY, body).apply()
}

object PushSender {

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 发送 Webhook 消息（钉钉/飞书/企业微信/通用）
     */
    fun sendWebhook(
        context: Context,
        symbol: String,
        title: String,
        message: String,
        price: Double,
        callback: ((Boolean, String) -> Unit)? = null
    ) {
        val rawUrl = PushChannelPrefs.getWebhookUrl(context)
        if (rawUrl.isBlank()) {
            callback?.invoke(false, "未配置 Webhook 地址")
            return
        }

        val platform = PushChannelPrefs.getWebhookPlatform(context)
        val secret = PushChannelPrefs.getWebhookSecret(context)
        val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val priceStr = Fmt.price(price)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                var finalUrl = rawUrl
                val jsonPayload: String

                when (platform) {
                    WebhookPlatform.DINGTALK -> {
                        if (secret.isNotBlank()) {
                            val timestamp = System.currentTimeMillis()
                            val stringToSign = "$timestamp\n$secret"
                            val mac = Mac.getInstance("HmacSHA256")
                            mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
                            val signData = mac.doFinal(stringToSign.toByteArray(Charsets.UTF_8))
                            val sign = URLEncoder.encode(Base64.encodeToString(signData, Base64.NO_WRAP), "UTF-8")
                            val sep = if (finalUrl.contains("?")) "&" else "?"
                            finalUrl = "$finalUrl${sep}timestamp=$timestamp&sign=$sign"
                        }
                        val content = "【BitDash 价格预警】\n$title\n$message\n币种: $symbol\n当前价格: $$priceStr\n时间: $timeStr"
                        jsonPayload = JSONObject().apply {
                            put("msgtype", "text")
                            put("text", JSONObject().apply {
                                put("content", content)
                            })
                        }.toString()
                    }
                    WebhookPlatform.FEISHU -> {
                        val content = "【BitDash 价格预警】\n$title\n$message\n币种: $symbol\n当前价格: $$priceStr\n时间: $timeStr"
                        jsonPayload = JSONObject().apply {
                            put("msg_type", "text")
                            put("content", JSONObject().apply {
                                put("text", content)
                            })
                        }.toString()
                    }
                    WebhookPlatform.WECOM -> {
                        val content = "【BitDash 价格预警】\n$title\n$message\n币种: $symbol\n当前价格: $$priceStr\n时间: $timeStr"
                        jsonPayload = JSONObject().apply {
                            put("msgtype", "text")
                            put("text", JSONObject().apply {
                                put("content", content)
                            })
                        }.toString()
                    }
                    WebhookPlatform.GENERIC -> {
                        val template = PushChannelPrefs.getWebhookTemplate(context)
                        jsonPayload = template
                            .replace("{symbol}", symbol)
                            .replace("{title}", title)
                            .replace("{message}", message)
                            .replace("{price}", priceStr)
                            .replace("{time}", timeStr)
                    }
                }

                val body = jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder()
                    .url(finalUrl)
                    .post(body)
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val respBody = response.body?.string() ?: ""
                    val isSuccess = response.isSuccessful
                    withContext(Dispatchers.Main) {
                        callback?.invoke(isSuccess, if (isSuccess) "发送成功: $respBody" else "请求失败(${response.code}): $respBody")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    callback?.invoke(false, "错误: ${e.message}")
                }
            }
        }
    }

    /**
     * 发送自定义 HTTP 接口（GET 或 POST）
     */
    fun sendHttp(
        context: Context,
        symbol: String,
        title: String,
        message: String,
        price: Double,
        callback: ((Boolean, String) -> Unit)? = null
    ) {
        val rawUrl = PushChannelPrefs.getHttpUrl(context)
        if (rawUrl.isBlank()) {
            callback?.invoke(false, "未配置 HTTP 接口地址")
            return
        }

        val method = PushChannelPrefs.getHttpMethod(context)
        val rawHeaders = PushChannelPrefs.getHttpHeaders(context)
        val rawBody = PushChannelPrefs.getHttpBody(context)
        val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val priceStr = Fmt.price(price)

        val finalUrl = rawUrl
            .replace("{symbol}", URLEncoder.encode(symbol, "UTF-8"))
            .replace("{title}", URLEncoder.encode(title, "UTF-8"))
            .replace("{message}", URLEncoder.encode(message, "UTF-8"))
            .replace("{price}", priceStr)
            .replace("{time}", URLEncoder.encode(timeStr, "UTF-8"))

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val reqBuilder = Request.Builder().url(finalUrl)

                // 解析请求头
                rawHeaders.lineSequence().forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.contains(":")) {
                        val k = trimmed.substringBefore(":").trim()
                        val v = trimmed.substringAfter(":").trim()
                        if (k.isNotBlank() && v.isNotBlank()) {
                            reqBuilder.addHeader(k, v)
                        }
                    }
                }

                if (method.equals("POST", ignoreCase = true)) {
                    val finalBodyStr = rawBody
                        .replace("{symbol}", symbol)
                        .replace("{title}", title)
                        .replace("{message}", message)
                        .replace("{price}", priceStr)
                        .replace("{time}", timeStr)
                    val body = finalBodyStr.toRequestBody("application/json; charset=utf-8".toMediaType())
                    reqBuilder.post(body)
                } else {
                    reqBuilder.get()
                }

                httpClient.newCall(reqBuilder.build()).execute().use { response ->
                    val respBody = response.body?.string() ?: ""
                    val isSuccess = response.isSuccessful
                    withContext(Dispatchers.Main) {
                        callback?.invoke(isSuccess, if (isSuccess) "请求成功(${response.code}): $respBody" else "请求失败(${response.code}): $respBody")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    callback?.invoke(false, "请求异常: ${e.message}")
                }
            }
        }
    }

    /**
     * 发送应用内 Toast 强提醒
     */
    fun sendToast(context: Context, title: String, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context.applicationContext, "🔔 $title\n$message", Toast.LENGTH_LONG).show()
        }
    }
}
