package com.example.carelink.presentation

import android.util.Log
import okhttp3.*
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.*

class RtcSignalClient {

    // 🛠️ 为本地调试创建不安全的 OkHttpClient (绕过 SSL 证书检查)
    private val unsafeClient: OkHttpClient = try {
        val trustAllCerts = arrayOf<TrustManager>(
            object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
        )
        val sslContext = SSLContext.getInstance("SSL")
        sslContext.init(null, trustAllCerts, SecureRandom())
        
        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true } // 允许所有域名
            .build()
    } catch (e: Exception) {
        OkHttpClient()
    }

    // ⚠️ 请确认你的服务器地址和协议 (本地建议先用 ws:// 测试)
    private val request = Request.Builder()
        .url("ws://192.168.32.100:25101") // 尝试改成 ws 而不是 wss
        .build()

    private var webSocket: WebSocket? = null

    fun connect() {
        if (webSocket != null) return
        Log.d("RTC", "Connecting to ${request.url}")
        webSocket = unsafeClient.newWebSocket(request, socketListener)
    }

    fun sendFallAlert(userId: String) {
        val json = """
            {
              "type": "FALL_ALERT",
              "userId": "$userId",
              "timestamp": ${System.currentTimeMillis()},
              "severity": "HIGH"
            }
        """.trimIndent()

        Log.e("RTC", "Attempting to send: $json")
        val sent = webSocket?.send(json) ?: false
        if (!sent) {
            Log.e("RTC", "Send FAILED. WebSocket state: $webSocket")
            // 如果没连接上，尝试重连
            connect()
        } else {
            Log.d("RTC", "Sent successfully")
        }
    }

    private val socketListener = object : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            Log.d("RTC", "✅ WebSocket Connected Successfully")
            webSocket = ws
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d("RTC", "📩 Received: $text")
        }

        override fun onFailure(ws: WebSocket, t: Throwable, r: Response?) {
            Log.e("RTC", "❌ WebSocket Failure: ${t.message}")
            t.printStackTrace() // 打印完整堆栈以诊断原因
            webSocket = null
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d("RTC", "🔌 WebSocket Closing: $reason")
            this@RtcSignalClient.webSocket = null
        }
    }
}
