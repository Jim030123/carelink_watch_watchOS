package com.example.carelink.presentation

import android.content.Context
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

    /**
     * ⭐ 仅创建 Track，不创建 Stream 对象，以符合 Unified Plan
     */
    private fun createLocalAudioTrack() {
        if (factory == null) return
        
        Log.d("RTC", "Creating Local Audio Track...")
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        }
        
        val audioSource = factory!!.createAudioSource(audioConstraints)
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

    fun startWebRtcCall() {
        Log.e("RTC", "🚀 Starting Call (Unified Plan)...")
        
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isMicrophoneMute = false
        audioManager.isSpeakerphoneOn = true

        createLocalAudioTrack()

        // ⭐ 必须明确指定 UnifiedPlan
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

        // ⭐ 核心修复：使用 addTrack 并指定 Stream Label，不再使用 addStream
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
