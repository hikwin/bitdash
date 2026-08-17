package com.bitdash.app.market

import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * 各行情源共用的极简 HTTP/JSON 客户端。
 *
 * 之所以不引第三方库：APK 体积敏感，且这里只需要 GET + JSON。
 * 关键兼容点：Android 7.0 (API 24) 默认不启用 TLS 1.2 之外的现代加密套件，
 * 部分交易所（实测 aws.okx.com）会因此握手失败，所以显式装一个开启 TLS 1.2 的 SocketFactory。
 */
object Http {

    // 连接超时刻意压得比较短：自动模式最坏要串行试 7 个源，
    // 若每个都等 12s，被墙的网络上会白屏一分多钟。握手能成的源通常 2s 内就连上了。
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val UA = "BitDash/1.0 (Android)"

    /** 单次 GET 并解析为 JSON 对象 */
    fun getObject(url: String): JSONObject {
        val body = getString(url)
        try {
            return JSONObject(body)
        } catch (e: Exception) {
            throw MarketException("响应格式异常", e)
        }
    }

    /** 单次 GET 并解析为 JSON 数组 */
    fun getArray(url: String): JSONArray {
        val body = getString(url)
        try {
            return JSONArray(body)
        } catch (e: Exception) {
            throw MarketException("响应格式异常", e)
        }
    }

    /** 单次 GET，返回响应体文本 */
    fun getString(url: String): String {
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw MarketException("地址无效", e)
        }

        try {
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Accept-Encoding", "gzip")
            conn.setRequestProperty("User-Agent", UA)

            // Android 7.x 上补齐 TLS 1.2，避免与只支持现代套件的站点握手失败
            if (conn is HttpsURLConnection && Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) {
                tls12Factory?.let { conn.sslSocketFactory = it }
            }

            val code = try {
                conn.responseCode
            } catch (e: Exception) {
                throw MarketException(friendlyNetworkError(e), e)
            }

            if (code != HttpURLConnection.HTTP_OK) {
                // 交易所常用 451/403 表示地区限制，这类错误换源才有意义，提示要说清楚
                throw MarketException(
                    when (code) {
                        451, 403 -> "该源在当前网络受限（HTTP $code）"
                        429 -> "请求过于频繁，请稍后重试"
                        in 500..599 -> "源服务异常（HTTP $code）"
                        else -> "请求失败（HTTP $code）"
                    }
                )
            }

            val body = try {
                readBody(conn)
            } catch (e: Exception) {
                throw MarketException(friendlyNetworkError(e), e)
            }

            if (body.isEmpty()) throw MarketException("响应为空")
            // 被运营商/网关拦截时通常返回 HTML
            val c = body[0]
            if (c != '{' && c != '[') throw MarketException("响应被拦截或非 JSON")
            return body
        } finally {
            try {
                conn.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun readBody(conn: HttpURLConnection): String {
        val raw: InputStream = conn.inputStream
        val stream = if (conn.contentEncoding?.contains("gzip", ignoreCase = true) == true) {
            GZIPInputStream(raw)
        } else {
            raw
        }
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    /** 把底层异常翻译成用户能看懂的短句 */
    private fun friendlyNetworkError(e: Exception): String {
        val name = e.javaClass.simpleName
        return when {
            name.contains("UnknownHost") -> "无法解析域名（DNS 失败）"
            name.contains("SSL") -> "安全连接失败（可能被拦截）"
            name.contains("SocketTimeout") || name.contains("ConnectTimeout") -> "连接超时"
            e is IOException -> "网络不可达"
            else -> "网络异常"
        }
    }

    /**
     * 开启 TLS 1.2 的 SocketFactory（仅 API 24/25 需要）。
     * 构造失败时返回 null，调用方回退到系统默认行为。
     */
    private val tls12Factory: SSLSocketFactory? by lazy {
        try {
            val ctx = SSLContext.getInstance("TLSv1.2")
            ctx.init(null, null, null)
            Tls12SocketFactory(ctx.socketFactory)
        } catch (_: Exception) {
            null
        }
    }

    /** 强制在每个新建 socket 上打开 TLSv1.2 协议 */
    private class Tls12SocketFactory(private val delegate: SSLSocketFactory) : SSLSocketFactory() {

        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

        override fun createSocket(s: java.net.Socket?, host: String?, port: Int, autoClose: Boolean) =
            patch(delegate.createSocket(s, host, port, autoClose))

        override fun createSocket(host: String?, port: Int) =
            patch(delegate.createSocket(host, port))

        override fun createSocket(
            host: String?, port: Int, localHost: java.net.InetAddress?, localPort: Int
        ) = patch(delegate.createSocket(host, port, localHost, localPort))

        override fun createSocket(host: java.net.InetAddress?, port: Int) =
            patch(delegate.createSocket(host, port))

        override fun createSocket(
            address: java.net.InetAddress?, port: Int,
            localAddress: java.net.InetAddress?, localPort: Int
        ) = patch(delegate.createSocket(address, port, localAddress, localPort))

        private fun patch(socket: java.net.Socket?): java.net.Socket? {
            if (socket is SSLSocket) {
                try {
                    socket.enabledProtocols = arrayOf("TLSv1.2", "TLSv1.1", "TLSv1")
                } catch (_: Exception) {
                }
            }
            return socket
        }
    }
}

/** JSONArray 便捷遍历 */
internal inline fun <T> JSONArray.mapNotNullIndexed(block: (Int) -> T?): List<T> {
    val out = ArrayList<T>(length())
    for (i in 0 until length()) {
        block(i)?.let { out.add(it) }
    }
    return out
}

/** 从 JSON 里稳妥地取数字：交易所普遍用字符串装数字，个别字段又是原生 number */
internal fun JSONObject.num(key: String): Double {
    if (!has(key) || isNull(key)) return 0.0
    return optString(key).toDoubleOrNull() ?: optDouble(key, 0.0).let { if (it.isNaN()) 0.0 else it }
}

internal fun JSONArray.numAt(index: Int): Double {
    if (index >= length() || isNull(index)) return 0.0
    return optString(index).toDoubleOrNull() ?: optDouble(index, 0.0).let { if (it.isNaN()) 0.0 else it }
}

internal fun JSONArray.longAt(index: Int): Long {
    if (index >= length() || isNull(index)) return 0L
    return optString(index).toLongOrNull() ?: optLong(index, 0L)
}
