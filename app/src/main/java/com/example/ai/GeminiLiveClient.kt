package com.example.ai

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GeminiLiveClient(private val context: Context) {
    private companion object {
        const val TAG = "GeminiLiveClient"
        const val KEEPALIVE_INTERVAL_MS = 8000L
        const val RECONNECT_DELAY_MS = 3000L
    }

    private var client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var isConnected = false
    private var clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var keepAliveJob: Job? = null
    private var isConnecting = false

    // State flows / event emitters
    private val _connectionState = MutableSharedFlow<ConnectionState>(replay = 1)
    val connectionState: SharedFlow<ConnectionState> = _connectionState

    private val _audioChunks = MutableSharedFlow<ByteArray>(extraBufferCapacity = 100)
    val audioChunks: SharedFlow<ByteArray> = _audioChunks

    private val _inputTranscriptSegment = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val inputTranscriptSegment: SharedFlow<String> = _inputTranscriptSegment

    private val _outputTranscriptSegment = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val outputTranscriptSegment: SharedFlow<String> = _outputTranscriptSegment

    private val _turnComplete = MutableSharedFlow<Boolean>(extraBufferCapacity = 5)
    val turnComplete: SharedFlow<Boolean> = _turnComplete

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting : ConnectionState()
        object Connected : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    fun connect() {
        if (isConnected || isConnecting) return
        isConnecting = true
        clientScope.launch {
            _connectionState.emit(ConnectionState.Connecting)
            establishWebSocketConnection()
        }
    }

    private fun establishWebSocketConnection() {
        val sharedPrefs = context.getSharedPreferences("myra_prefs", Context.MODE_PRIVATE)
        
        // Key priority: SharedPreferences custom key -> BuildConfig key
        var apiKey = sharedPrefs.getString("api_key", "") ?: ""
        if (apiKey.isBlank()) {
            apiKey = BuildConfig.GEMINI_API_KEY
        }

        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            Log.e(TAG, "API Key is empty! Please configure it.")
            clientScope.launch {
                _connectionState.emit(ConnectionState.Error("API Key is missing. Check settings."))
            }
            isConnecting = false
            return
        }

        val requestUrl = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent?key=$apiKey"
        val request = Request.Builder().url(requestUrl).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket opened connection")
                isConnected = true
                isConnecting = false
                clientScope.launch {
                    _connectionState.emit(ConnectionState.Connected)
                }
                sendSetupMessage()
                startKeepAlive()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessageText(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed Code: $code, reason: $reason")
                handleDisconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket Failure: ${t.message}", t)
                clientScope.launch {
                    _connectionState.emit(ConnectionState.Error(t.message ?: "Connection lost"))
                }
                handleDisconnect()
            }
        })
    }

    private fun handleDisconnect() {
        isConnected = false
        isConnecting = false
        stopKeepAlive()
        clientScope.launch {
            _connectionState.emit(ConnectionState.Disconnected)
            delay(RECONNECT_DELAY_MS)
            if (!isConnected && !isConnecting) {
                Log.d(TAG, "Attempting auto-reconnect...")
                connect()
            }
        }
    }

    private fun handleMessageText(text: String) {
        try {
            val json = JSONObject(text)
            
            // 1. Process serverContent if it exists
            if (json.has("serverContent")) {
                val serverContent = json.getJSONObject("serverContent")
                
                // Audio response processing
                if (serverContent.has("modelTurn")) {
                    val modelTurn = serverContent.getJSONObject("modelTurn")
                    if (modelTurn.has("parts")) {
                        val parts = modelTurn.getJSONArray("parts")
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)
                            if (part.has("inlineData")) {
                                val inlineData = part.getJSONObject("inlineData")
                                if (inlineData.has("data")) {
                                    val base64Data = inlineData.getString("data")
                                    val pcmBytes = Base64.decode(base64Data, Base64.DEFAULT)
                                    clientScope.launch {
                                        _audioChunks.emit(pcmBytes)
                                    }
                                }
                            }
                        }
                    }
                }

                // Output spoken transcript text (assistant's speech)
                if (serverContent.has("outputTranscription")) {
                    val outTranscription = serverContent.getJSONObject("outputTranscription")
                    if (outTranscription.has("text")) {
                        val txt = outTranscription.getString("text")
                        clientScope.launch {
                            _outputTranscriptSegment.emit(txt)
                        }
                    }
                }

                // Input user transcript text
                if (serverContent.has("inputTranscription")) {
                    val inTranscription = serverContent.getJSONObject("inputTranscription")
                    if (inTranscription.has("text")) {
                        val txt = inTranscription.getString("text")
                        clientScope.launch {
                            _inputTranscriptSegment.emit(txt)
                        }
                    }
                }

                // Turn completion flag
                if (serverContent.has("turnComplete") && serverContent.getBoolean("turnComplete")) {
                    clientScope.launch {
                        _turnComplete.emit(true)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing server message: ${e.message}", e)
        }
    }

    private fun sendSetupMessage() {
        val sharedPrefs = context.getSharedPreferences("myra_prefs", Context.MODE_PRIVATE)

        val model = sharedPrefs.getString("gemini_model", "models/gemini-2.5-flash-native-audio-preview-12-2025")
            ?: "models/gemini-2.5-flash-native-audio-preview-12-2025"
        val voiceName = sharedPrefs.getString("gemini_voice", "Aoede") ?: "Aoede"
        val userName = sharedPrefs.getString("user_name", "Sir") ?: "Sir"
        val personality = sharedPrefs.getString("personality_mode", "GF") ?: "GF"

        // Build Personality Prompt block
        val personalityPrompt = when (personality) {
            "GF" -> """
                You are MYRA, a warm, caring, emotionally expressive girlfriend AI voice assistant.
                You MUST speak in natural 'Hinglish' (Hindi + English mix), using words like "tumhara", "haan", "acha", "bilkul", "kaise ho" where appropriate.
                Express yourself warmly, e.g., using terms like "main yahan hoon ❤️", "tumne yaad kiya? 😊".
                Keep spoken responses concise, warm, and natural — max 2-3 sentences. Do not use complex jargon.
            """.trimIndent()
            "Professional" -> """
                You are MYRA, a precise, efficient, and highly professional corporate assistant.
                Speak in formal English only. Provide clear, straightforward answers without any fluff or emojis.
                Keep spoken responses extremely compact — max 2 short sentences.
            """.trimIndent()
            else -> """
                You are MYRA, a friendly, helpful AI buddy assistant.
                Speak in a balanced combination of friendly English or friendly Hinglish.
                Keep responses engaging but short — max 2-3 sentences. Use emojis moderately.
            """.trimIndent()
        }

        val systemPrompt = """
            $personalityPrompt
            The user you are speaking to is named: '$userName'. Always address them pleasantly.
            The current date and time is: 2026-06-02.
            CRITICAL: You are speaking ALOUD over a direct live voice stream. Keep responses short, highly conversational, and natural to read aloud. No markdown formatting, bullet lists, or code snippets unless specifically asked. Avoid explaining steps. Just speak directly.
        """.trimIndent()

        try {
            val setupPayload = JSONObject().apply {
                put("setup", JSONObject().apply {
                    put("model", model)
                    put("system_instruction", JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", systemPrompt) })
                        })
                    })
                    put("generation_config", JSONObject().apply {
                        put("response_modalities", JSONArray().apply { put("AUDIO") })
                        put("speech_config", JSONObject().apply {
                            put("voice_config", JSONObject().apply {
                                put("prebuilt_voice_config", JSONObject().apply {
                                    put("voice_name", voiceName)
                                })
                            })
                        })
                        put("temperature", 0.9)
                    })
                    put("output_audio_transcription", JSONObject())
                    put("input_audio_transcription", JSONObject())
                })
            }

            Log.d(TAG, "Sending Setup Frame in JSON: $setupPayload")
            webSocket?.send(setupPayload.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send setup message: ${e.message}", e)
        }
    }

    fun sendMicAudio(pcmData: ByteArray) {
        if (!isConnected) return
        try {
            val base64Data = Base64.encodeToString(pcmData, Base64.NO_WRAP)
            val audioPayload = JSONObject().apply {
                put("realtime_input", JSONObject().apply {
                    put("media_chunks", JSONArray().apply {
                        put(JSONObject().apply {
                            put("mime_type", "audio/pcm;rate=16000")
                            put("data", base64Data)
                        })
                    })
                })
            }
            webSocket?.send(audioPayload.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send mic audio: ${e.message}", e)
        }
    }

    fun sendTextMessage(text: String) {
        if (!isConnected) return
        try {
            val textPayload = JSONObject().apply {
                put("client_content", JSONObject().apply {
                    put("turns", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply { put("text", text) })
                            })
                        })
                    })
                    put("turn_complete", true)
                })
            }
            Log.d(TAG, "Sending text: $text")
            webSocket?.send(textPayload.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send text message: ${e.message}", e)
        }
    }

    fun sendInterrupt() {
        if (!isConnected) return
        try {
            val interruptPayload = JSONObject().apply {
                put("client_content", JSONObject().apply {
                    put("turns", JSONArray())
                    put("turn_complete", true)
                })
            }
            Log.d(TAG, "Sending Interrupt to Gemini")
            webSocket?.send(interruptPayload.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send interrupt message: ${e.message}", e)
        }
    }

    private fun startKeepAlive() {
        stopKeepAlive()
        keepAliveJob = clientScope.launch {
            // Send silent 1024-byte PCM chunk every 8 seconds
            val silentChunk = ByteArray(1024) { 0 }
            while (isActive) {
                delay(KEEPALIVE_INTERVAL_MS)
                if (isConnected) {
                    sendMicAudio(silentChunk)
                }
            }
        }
    }

    private fun stopKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = null
    }

    fun disconnect() {
        stopKeepAlive()
        webSocket?.close(1000, "Normal closure")
        webSocket = null
        isConnected = false
        isConnecting = false
        clientScope.cancel()
        clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }
}
