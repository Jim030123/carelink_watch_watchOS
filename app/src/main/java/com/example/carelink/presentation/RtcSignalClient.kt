package com.example.carelink.presentation

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.util.Log
import okhttp3.*
import org.json.JSONObject
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.*

class RtcSignalClient(private val context: Context) {

    private var peerConnection: PeerConnection? = null
    private var factory: PeerConnectionFactory? = null
    private var localAudioTrack: AudioTrack? = null
    private var audioSource: AudioSource? = null

    private val client: OkHttpClient = createUnsafeOkHttpClient()
    private val request = Request.Builder().url("ws://192.168.32.100:25101").build()
    private var webSocket: WebSocket? = null
    
    private val myClientId = "Watch-003"
    private val targetClientId = "CG-003"
    private val myRole = "cr"

    init {
        initPeerConnectionFactory()
    }

    private fun initPeerConnectionFactory() {
        Log.d("RTC", "Initializing PeerConnectionFactory...")
        try {
            val options = PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(options)

            val audioDeviceModule = JavaAudioDeviceModule.builder(context)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .createAudioDeviceModule()

            factory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(audioDeviceModule)
                .createPeerConnectionFactory()
        } catch (e: Exception) {
            Log.e("RTC", "Factory Init Error", e)
        }
    }

    private fun createLocalAudioTrack() {
        if (factory == null) return
        
        Log.d("RTC", "Creating Local Audio Track...")
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        }
        
        audioSource = factory!!.createAudioSource(audioConstraints)
        localAudioTrack = factory!!.createAudioTrack("ARDAMS0", audioSource)
        localAudioTrack?.setEnabled(true)
    }

    fun connect() {
        if (webSocket != null) return
        webSocket = client.newWebSocket(request, socketListener)
    }

    private fun joinRoom() {
        val json = JSONObject().apply {
            put("type", "join")
            put("clientId", myClientId) 
            put("role", myRole)
        }
        webSocket?.send(json.toString())
    }

    fun sendFallAlert(userId: String) {
        val json = JSONObject().apply {
            put("type", "FALL_ALERT")
            put("userId", userId)
            put("timestamp", System.currentTimeMillis())
        }
        webSocket?.send(json.toString())
    }

    /**
     * 🛑 结束通话并释放资源
     * @param sendSignal 是否向服务器发送挂断指令。如果是收到对方挂断后执行清理，则设为 false。
     */
    fun endCall(sendSignal: Boolean = true) {
        Log.e("RTC", "Shutting down RTC (sendSignal=$sendSignal)...")
        try {
            // 1. 根据需要发送 "end_call" 指令
            if (sendSignal) {
                val json = JSONObject().apply {
                    put("type", "end_call")
                    put("to", targetClientId)
                }
                webSocket?.send(json.toString())
            }

            // 2. 释放硬件和软件资源
            peerConnection?.close()
            peerConnection?.dispose()
            peerConnection = null

            localAudioTrack?.setEnabled(false)
            localAudioTrack?.dispose()
            localAudioTrack = null
            
            audioSource?.dispose()
            audioSource = null

            // 3. 恢复音频系统状态
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.isMicrophoneMute = false 
            audioManager.isSpeakerphoneOn = false
            audioManager.mode = AudioManager.MODE_NORMAL

            Log.d("RTC", "RTC Resources released.")
        } catch (e: Exception) {
            Log.e("RTC", "Error during endCall", e)
        }
    }

    fun startWebRtcCall() {
        Log.e("RTC", "🚀 Starting Call (Unified Plan)...")
        
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isMicrophoneMute = false
        audioManager.isSpeakerphoneOn = true

        createLocalAudioTrack()

        val rtcConfig = PeerConnection.RTCConfiguration(
            listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        ).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        peerConnection = factory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                val json = JSONObject().apply {
                    put("type", "candidate")
                    put("to", targetClientId)
                    put("candidate", JSONObject().apply {
                        put("candidate", candidate.sdp)
                        put("sdpMid", candidate.sdpMid)
                        put("sdpMLineIndex", candidate.sdpMLineIndex)
                    })
                }
                webSocket?.send(json.toString())
            }
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                Log.d("RTC", "ICE State: $newState")
            }
            override fun onDataChannel(p0: DataChannel?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddStream(p0: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onTrack(p0: RtpTransceiver?) {}
        })

        localAudioTrack?.let {
            peerConnection?.addTrack(it, listOf("ARDAMS"))
        }

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                peerConnection?.setLocalDescription(this, desc)
                val json = JSONObject().apply {
                    put("type", "start_call")
                    put("to", targetClientId)
                    put("callId", "call_${System.currentTimeMillis()}")
                    put("offer", JSONObject().apply {
                        put("sdp", desc.description)
                        put("type", "offer")
                    })
                    put("meta", JSONObject().apply { put("name", "Watch User") })
                }
                webSocket?.send(json.toString())
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {}
        }, constraints)
    }

    private val socketListener = object : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            Log.d("RTC", "✅ WS Connected")
            webSocket = ws
            joinRoom()
        }
        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val json = JSONObject(text)
                when (val type = json.optString("type")) {
                    "answer" -> {
                        val answerData = json.getJSONObject("answer")
                        val sdp = answerData.getString("sdp")
                        peerConnection?.setRemoteDescription(object : SdpObserver {
                            override fun onSetSuccess() { Log.d("RTC", "Remote Answer Set") }
                            override fun onSetFailure(p0: String?) {}
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onCreateFailure(p0: String?) {}
                        }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
                    }
                    "candidate" -> {
                        val cand = json.getJSONObject("candidate")
                        val candidate = IceCandidate(
                            cand.getString("sdpMid"),
                            cand.getInt("sdpMLineIndex"),
                            cand.getString("candidate")
                        )
                        peerConnection?.addIceCandidate(candidate)
                    }
                    "end_call", "reject_call" -> {
                        Log.e("RTC", "Received $type from remote. Disposing call...")
                        // 🚀 核心逻辑：通知 Service 进行全局重置
                        val intent = Intent("ACTION_FALL_ALERT_RESET")
                        intent.setPackage(context.packageName)
                        context.sendBroadcast(intent)
                    }
                }
            } catch (e: Exception) {}
        }
        override fun onFailure(ws: WebSocket, t: Throwable, r: Response?) {
            Log.e("RTC", "❌ WS Failure", t)
            webSocket = null
        }
    }

    private fun createUnsafeOkHttpClient(): OkHttpClient {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("SSL")
        sslContext.init(null, trustAllCerts, SecureRandom())
        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }
}
