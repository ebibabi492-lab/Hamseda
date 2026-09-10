package com.example.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import com.example.network.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Real-time low-latency live microphone streaming over Wi-Fi.
 * Host captures voice through microphone and streams uncompressed 16kHz PCM audio
 * via UDP to all connected speakers with sub-50ms latency.
 */
object LiveMicConstants {
    const val SAMPLE_RATE = 16000
    const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
    const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
    const val AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT
    const val CHUNK_SIZE_BYTES = 640 // 20ms per packet (320 16-bit samples)
    const val UDP_MIC_PORT = 8992
    const val MAGIC_HEADER = 0x48534D43 // "HSMC"
}

class LiveMicBroadcaster(
    private val context: Context,
    private val scope: CoroutineScope,
    private val getTargetIps: () -> List<String>
) {
    private val TAG = "LiveMicBroadcaster"

    private val _isBroadcasting = MutableStateFlow(false)
    val isBroadcasting: StateFlow<Boolean> = _isBroadcasting.asStateFlow()

    private val _micLevel = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _micLevel.asStateFlow()

    private var broadcastJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var udpSocket: DatagramSocket? = null

    @SuppressLint("MissingPermission")
    fun startBroadcasting(): Boolean {
        if (_isBroadcasting.value) return true

        val minBufferSize = AudioRecord.getMinBufferSize(
            LiveMicConstants.SAMPLE_RATE,
            LiveMicConstants.CHANNEL_IN,
            LiveMicConstants.AUDIO_ENCODING
        )
        val bufferSize = (minBufferSize * 2).coerceAtLeast(LiveMicConstants.CHUNK_SIZE_BYTES * 4)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                LiveMicConstants.SAMPLE_RATE,
                LiveMicConstants.CHANNEL_IN,
                LiveMicConstants.AUDIO_ENCODING,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                audioRecord?.release()
                audioRecord = null
                return false
            }

            udpSocket = DatagramSocket().apply {
                broadcast = true
            }

            audioRecord?.startRecording()
            _isBroadcasting.value = true

            broadcastJob = scope.launch(Dispatchers.IO) {
                val pcmBuffer = ByteArray(LiveMicConstants.CHUNK_SIZE_BYTES)
                val packetBuffer = ByteBuffer.allocate(8 + LiveMicConstants.CHUNK_SIZE_BYTES).order(ByteOrder.BIG_ENDIAN)
                val broadcastAddr = InetAddress.getByName("255.255.255.255")
                var seq = 0

                while (isActive && _isBroadcasting.value) {
                    val readBytes = audioRecord?.read(pcmBuffer, 0, pcmBuffer.size) ?: -1
                    if (readBytes > 0) {
                        // Compute live RMS mic level for UI meter
                        var sum = 0.0
                        for (i in 0 until readBytes step 2) {
                            val sample = (pcmBuffer[i].toInt() and 0xFF) or (pcmBuffer[i + 1].toInt() shl 8)
                            val shortSample = sample.toShort()
                            sum += (shortSample * shortSample)
                        }
                        val rms = sqrt(sum / (readBytes / 2))
                        val normalizedLevel = (rms / 8000.0).toFloat().coerceIn(0f, 1f)
                        _micLevel.value = normalizedLevel

                        // Prepare UDP packet
                        packetBuffer.clear()
                        packetBuffer.putInt(LiveMicConstants.MAGIC_HEADER)
                        packetBuffer.putInt(seq++)
                        packetBuffer.put(pcmBuffer, 0, readBytes)

                        val data = packetBuffer.array()
                        val packetLength = 8 + readBytes

                        // 1. Broadcast to local network
                        try {
                            val bcastPacket = DatagramPacket(data, packetLength, broadcastAddr, LiveMicConstants.UDP_MIC_PORT)
                            udpSocket?.send(bcastPacket)
                        } catch (e: Exception) {
                            // ignore broadcast send error
                        }

                        // 2. Also unicast directly to all known connected speakers for 100% reliability
                        val targets = getTargetIps()
                        for (ip in targets) {
                            try {
                                val targetAddr = InetAddress.getByName(ip)
                                val directPacket = DatagramPacket(data, packetLength, targetAddr, LiveMicConstants.UDP_MIC_PORT)
                                udpSocket?.send(directPacket)
                            } catch (e: Exception) {
                                // ignore individual unicast send errors
                            }
                        }
                    }
                }
            }

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error starting live mic broadcast: ${e.message}", e)
            stopBroadcasting()
            return false
        }
    }

    fun stopBroadcasting() {
        _isBroadcasting.value = false
        _micLevel.value = 0f
        broadcastJob?.cancel()
        broadcastJob = null

        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            // ignore
        }
        try {
            audioRecord?.release()
        } catch (e: Exception) {
            // ignore
        }
        audioRecord = null

        try {
            udpSocket?.close()
        } catch (e: Exception) {
            // ignore
        }
        udpSocket = null
    }
}

class LiveMicReceiver(
    private val context: Context,
    private val scope: CoroutineScope,
    private val getVolume: () -> Float,
    private val onLiveMicStatusChanged: (isActive: Boolean) -> Unit
) {
    private val TAG = "LiveMicReceiver"

    private val _isReceiving = MutableStateFlow(false)
    val isReceiving: StateFlow<Boolean> = _isReceiving.asStateFlow()

    private var receiveJob: Job? = null
    private var udpSocket: DatagramSocket? = null
    private var audioTrack: AudioTrack? = null
    private var lastPacketTime = 0L

    fun startListening() {
        if (receiveJob != null) return

        NetworkUtils.acquireMulticastLock(context)

        val minBufferSize = AudioTrack.getMinBufferSize(
            LiveMicConstants.SAMPLE_RATE,
            LiveMicConstants.CHANNEL_OUT,
            LiveMicConstants.AUDIO_ENCODING
        )
        val bufferSize = (minBufferSize * 3).coerceAtLeast(LiveMicConstants.CHUNK_SIZE_BYTES * 6)

        try {
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(LiveMicConstants.AUDIO_ENCODING)
                        .setSampleRate(LiveMicConstants.SAMPLE_RATE)
                        .setChannelMask(LiveMicConstants.CHANNEL_OUT)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()

            udpSocket = DatagramSocket(LiveMicConstants.UDP_MIC_PORT).apply {
                broadcast = true
                reuseAddress = true
                receiveBufferSize = 64 * 1024
            }

            receiveJob = scope.launch(Dispatchers.IO) {
                val buffer = ByteArray(1024)
                val packet = DatagramPacket(buffer, buffer.size)

                // Launch silence watchdog coroutine
                launch {
                    while (isActive) {
                        kotlinx.coroutines.delay(400)
                        if (_isReceiving.value && System.currentTimeMillis() - lastPacketTime > 900L) {
                            _isReceiving.value = false
                            onLiveMicStatusChanged(false)
                        }
                    }
                }

                while (isActive) {
                    try {
                        udpSocket?.receive(packet)
                        val length = packet.length
                        if (length >= 8) {
                            val byteBuffer = ByteBuffer.wrap(packet.data, 0, length).order(ByteOrder.BIG_ENDIAN)
                            val magic = byteBuffer.int
                            if (magic == LiveMicConstants.MAGIC_HEADER) {
                                val seq = byteBuffer.int
                                val pcmLen = length - 8
                                val pcmBytes = ByteArray(pcmLen)
                                byteBuffer.get(pcmBytes)

                                lastPacketTime = System.currentTimeMillis()
                                if (!_isReceiving.value) {
                                    _isReceiving.value = true
                                    onLiveMicStatusChanged(true)
                                }

                                // Apply speaker volume scaling directly to 16-bit PCM samples
                                val vol = getVolume().coerceIn(0f, 1f)
                                for (i in 0 until pcmLen - 1 step 2) {
                                    val sample = (pcmBytes[i].toInt() and 0xFF) or (pcmBytes[i + 1].toInt() shl 8)
                                    val shortSample = sample.toShort()
                                    val scaled = (shortSample * vol).toInt().coerceIn(-32768, 32767).toShort()
                                    pcmBytes[i] = (scaled.toInt() and 0xFF).toByte()
                                    pcmBytes[i + 1] = ((scaled.toInt() shr 8) and 0xFF).toByte()
                                }

                                audioTrack?.write(pcmBytes, 0, pcmLen)
                            }
                        }
                    } catch (e: Exception) {
                        if (isActive) {
                            Log.w(TAG, "Live mic receive loop warning: ${e.message}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start LiveMicReceiver: ${e.message}", e)
            stopListening()
        }
    }

    fun stopListening() {
        receiveJob?.cancel()
        receiveJob = null
        _isReceiving.value = false
        onLiveMicStatusChanged(false)

        try {
            audioTrack?.stop()
        } catch (e: Exception) {
            // ignore
        }
        try {
            audioTrack?.release()
        } catch (e: Exception) {
            // ignore
        }
        audioTrack = null

        try {
            udpSocket?.close()
        } catch (e: Exception) {
            // ignore
        }
        udpSocket = null

        NetworkUtils.releaseMulticastLock()
    }
}
