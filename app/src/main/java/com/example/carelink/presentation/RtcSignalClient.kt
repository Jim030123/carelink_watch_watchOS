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
    private var currentCallId: String? = null

    private val client: OkHttpClient = createUnsafeOkHttpClient()
    private val request = Request.Builder().url("ws://10.27.201.100:25101").build()
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
                currentCallId = "call_${System.currentTimeMillis()}"
                val json = JSONObject().apply {
                    put("type", "start_call")
                    put("to", targetClientId)
                    put("callId", currentCallId)
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

    /**
     * ⭐ 加固后的挂断逻辑，确保 end_call 指令成功送达
     */
    fun hangup() {
        if (webSocket == null) {
            Log.e("RTC", "Hangup aborted: WebSocket is NULL")
            closeInternal()
            return
        }

        Log.d("RTC", ">>> Executing Hangup. Target: $targetClientId, CallId: $currentCallId")
        
        val json = JSONObject().apply {
            put("type", "end_call")
            put("to", targetClientId)
            // 即便 currentCallId 为空也要发，传个占位符，服务器会转发给 targetClientId
            put("callId", currentCallId ?: "pending_${System.currentTimeMillis()}")
            put("from", myClientId)
        }
        
        val sent = webSocket?.send(json.toString()) ?: false
        Log.e("RTC", ">>> end_call signal sent: $sent")

        closeInternal()
    }

    private fun closeInternal() {
        Log.d("RTC", "Cleaning up WebRTC resources...")
        try {
            localAudioTrack?.setEnabled(false)
            localAudioTrack?.dispose()
            localAudioTrack = null

            audioSource?.dispose()
            audioSource = null

            peerConnection?.dispose()
            peerConnection = null
            
            currentCallId = null

            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.isMicrophoneMute = false
            audioManager.isSpeakerphoneOn = false
            
            Log.d("RTC", "Microphone and WebRTC released successfully")
        } catch (e: Exception) {
            Log.e("RTC", "Error during closeInternal", e)
        }
    }

    fun sendFallAlert(userId: String) {
        val json = JSONObject().apply {
            put("type", "FALL_ALERT")
            put("userId", userId)
            put("timestamp", System.currentTimeMillis())
        }
        webSocket?.send(json.toString())
    }

    private val socketListener = object : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            Log.d("RTC", "✅ WS Connected")
            webSocket = ws
            joinRoom()
        }

        override fun onMessage(ws: WebSocket, text: String) {
            try {
                val json = JSONObject(text)
                when (json.optString("type")) {
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
                        Log.d("RTC", "Remote ended call, broadcasting reset")
                        val intent = Intent("ACTION_FALL_ALERT_RESET")
                        intent.setPackage(context.packageName)
                        context.sendBroadcast(intent)
                        closeInternal()
                    }
                }
            } catch (e: Exception) {}
        }

        override fun onFailure(ws: WebSocket, t: Throwable, r: Response?) {
            Log.e("RTC", "❌ WS Failure", t)
            webSocket = null
            closeInternal()
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
