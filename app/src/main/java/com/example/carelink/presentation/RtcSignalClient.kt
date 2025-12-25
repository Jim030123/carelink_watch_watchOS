package com.example.carelink.presentation

import android.util.Log
import okhttp3.*

class RtcSignalClient {

    private val client = OkHttpClient()
    // ⚠️ 请将此处替换为你的实际服务器地址
    private val request = Request.Builder()
        .url("wss://your-server-address.com/ws") 
        .build()

    private var webSocket: WebSocket? = null

    fun connect() {
        if (webSocket != null) return
        webSocket = client.newWebSocket(request, socketListener)
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

        Log.e("RTC", "Sending FALL_ALERT JSON: $json")
        val sent = webSocket?.send(json) ?: false
        if (!sent) {
            Log.e("RTC", "Failed to send - WebSocket might be disconnected. Retrying connect...")
            connect()
        }
    }

    private val socketListener = object : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            Log.d("RTC", "WebSocket Connected Successfully")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d("RTC", "Received message: $text")
        }

        override fun onFailure(ws: WebSocket, t: Throwable, r: Response?) {
            Log.e("RTC", "WebSocket Error: ${t.message}")
            // 失败后尝试重新连接
            webSocket = null
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d("RTC", "WebSocket Closing: $reason")
            this@RtcSignalClient.webSocket = null
        }
    }
}
