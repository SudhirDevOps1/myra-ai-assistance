package com.example.ai

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.sqrt

class AudioEngine(context: Context) {
    private val context = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.createAttributionContext("microphone")
    } else {
        context
    }

    private companion object {
        const val TAG = "AudioEngine"
        const val MIC_SAMPLE_RATE = 16000
        const val SPEAKER_SAMPLE_RATE = 24000
        const val CHUNK_SIZE = 1024
    }

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    private var isRecording = false
    private var isPlaying = false
    private var isMuted = false
    private var isSpeaking = false

    private val playbackQueue = LinkedBlockingQueue<ByteArray>()
    private var engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var recordingJob: Job? = null
    private var playbackJob: Job? = null

    // Callbacks to communicate with UI / Client
    private val _onMicData = MutableSharedFlow<ByteArray>(extraBufferCapacity = 100)
    val onMicData: SharedFlow<ByteArray> = _onMicData

    private val _onSpeakingStarted = MutableSharedFlow<Boolean>(extraBufferCapacity = 5)
    val onSpeakingStarted: SharedFlow<Boolean> = _onSpeakingStarted

    private val _amplitude = MutableSharedFlow<Float>(extraBufferCapacity = 5)
    val amplitude: SharedFlow<Float> = _amplitude

    @SuppressLint("MissingPermission")
    fun startRecording() {
        if (isRecording) return
        isRecording = true

        val minBufSize = AudioRecord.getMinBufferSize(
            MIC_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = Math.max(minBufSize, CHUNK_SIZE * 4)

        try {
            audioRecord = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                AudioRecord.Builder()
                    .setContext(context)
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(MIC_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build())
                    .setBufferSizeInBytes(bufferSize)
                    .build()
            } else {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    MIC_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
            }

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord could not be initialized")
                return
            }

            audioRecord?.startRecording()
            startRecordingLoop()
        } catch (e: SecurityException) {
            Log.e(TAG, "Record audio privilege missing: ${e.message}")
            isRecording = false
        } catch (e: Exception) {
            Log.e(TAG, "Error starting AudioRecord: ${e.message}")
            isRecording = false
        }
    }

    private fun startRecordingLoop() {
        recordingJob = engineScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(CHUNK_SIZE)
            while (isRecording && isActive) {
                val record = audioRecord ?: break
                val readBytes = record.read(buffer, 0, CHUNK_SIZE)
                if (readBytes > 0) {
                    val actualChunk = if (readBytes < CHUNK_SIZE) buffer.copyOf(readBytes) else buffer
                    
                    // Supress sending mic data if speaking (rudimentary echo suppression as requested)
                    // Or if manually muted
                    if (!isSpeaking && !isMuted) {
                        // Compute mic amplitude for viz when listening
                        computeAndNotifyAmplitude(actualChunk, readBytes)
                        _onMicData.emit(actualChunk.clone())
                    } else {
                        // Still update amplitude to 0 or very small
                        _amplitude.emit(0.01f)
                    }
                }
            }
        }
    }

    fun startPlayback() {
        if (isPlaying) return
        isPlaying = true

        val minBufSize = AudioTrack.getMinBufferSize(
            SPEAKER_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        try {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val format = AudioFormat.Builder()
                .setSampleRate(SPEAKER_SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            audioTrack = AudioTrack(
                attributes,
                format,
                minBufSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )

            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack state not initialized")
                return
            }

            audioTrack?.play()
            startPlaybackLoop()
        } catch (e: Exception) {
            Log.e(TAG, "Error creating AudioTrack: ${e.message}", e)
            isPlaying = false
        }
    }

    private fun startPlaybackLoop() {
        playbackJob = engineScope.launch(Dispatchers.IO) {
            while (isPlaying && isActive) {
                val chunk = playbackQueue.poll()
                if (chunk == null) {
                    if (isSpeaking) {
                        isSpeaking = false
                        _onSpeakingStarted.emit(false)
                    }
                    delay(50)
                    continue
                }

                if (!isSpeaking) {
                    isSpeaking = true
                    _onSpeakingStarted.emit(true)
                }
                    
                // Playback audio chunk to AudioTrack
                audioTrack?.write(chunk, 0, chunk.size)
                
                // Compute amplitude for visualization when playing
                computeAndNotifyAmplitude(chunk, chunk.size)
            }
        }
    }

    fun queueAudio(data: ByteArray) {
        playbackQueue.offer(data)
    }

    fun clearPlaybackQueue() {
        playbackQueue.clear()
        if (isSpeaking) {
            isSpeaking = false
            engineScope.launch {
                _onSpeakingStarted.emit(false)
            }
        }
        audioTrack?.flush()
    }

    private fun computeAndNotifyAmplitude(bytes: ByteArray, size: Int) {
        var sum = 0.0
        // Since encoding is 16-bit PCM, each sample is 2 bytes (little-endian)
        val numSamples = size / 2
        if (numSamples <= 0) return

        for (i in 0 until numSamples) {
            val low = bytes[2 * i].toInt()
            val high = bytes[2 * i + 1].toInt()
            val sample = ((high shl 8) or (low and 0xff)).toShort()
            sum += sample * sample
        }
        val rms = sqrt(sum / numSamples).toFloat()
        // Normalize RMS range (roughly max short value 32767 down to 0-1)
        val norm = (rms / 32767f).coerceIn(0f, 1f)
        engineScope.launch {
            _amplitude.emit(norm)
        }
    }

    fun setMuted(muted: Boolean) {
        this.isMuted = muted
    }

    fun isMuted(): Boolean {
        return isMuted
    }

    fun isSpeaking(): Boolean {
        return isSpeaking
    }

    fun stopRecording() {
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            // Ignore
        }
        audioRecord = null
    }

    fun stopPlayback() {
        isPlaying = false
        playbackJob?.cancel()
        playbackJob = null
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            // Ignore
        }
        audioTrack = null
        isSpeaking = false
    }

    fun release() {
        stopRecording()
        stopPlayback()
        engineScope.cancel()
        engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }
}
