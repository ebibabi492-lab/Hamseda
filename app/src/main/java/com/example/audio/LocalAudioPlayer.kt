package com.example.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream

class LocalAudioPlayer(private val context: Context) {
    private val TAG = "LocalAudioPlayer"
    private var mediaPlayer: MediaPlayer? = null

    var onCompletionListener: (() -> Unit)? = null
    var onErrorListener: ((String) -> Unit)? = null

    private var currentSourceKey: String = ""
    private var isPrepared = false
    private var currentSpeed: Float = 1.0f
    private var userVolume: Float = 1.0f
    private var isDuckedForLiveMic: Boolean = false

    fun duckForLiveMic() {
        isDuckedForLiveMic = true
        applyEffectiveVolume()
    }

    fun restoreAfterLiveMic() {
        isDuckedForLiveMic = false
        applyEffectiveVolume()
    }

    private fun applyEffectiveVolume() {
        try {
            val effective = if (isDuckedForLiveMic) (userVolume * 0.15f) else userVolume
            val clamped = effective.coerceIn(0f, 1f)
            mediaPlayer?.setVolume(clamped, clamped)
        } catch (e: Exception) {
            Log.w(TAG, "applyEffectiveVolume error: ${e.message}")
        }
    }

    fun setVolume(volume: Float) {
        userVolume = volume.coerceIn(0f, 1f)
        applyEffectiveVolume()
    }

    fun adjustPlaybackRate(speed: Float) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && isPrepared && mediaPlayer?.isPlaying == true) {
                val clampedSpeed = speed.coerceIn(0.92f, 1.08f)
                // Critical Fix: ONLY update playbackParams if speed genuinely changed by at least 0.015f.
                // Re-assigning playbackParams continuously flushes the AudioTrack audio resampler buffer,
                // which causes choppy, stuttering audio ("بریده بریده شدن صدا") especially at high volumes.
                if (kotlin.math.abs(currentSpeed - clampedSpeed) >= 0.015f) {
                    currentSpeed = clampedSpeed
                    val params = mediaPlayer?.playbackParams ?: PlaybackParams()
                    params.speed = clampedSpeed
                    mediaPlayer?.playbackParams = params
                }
            }
        } catch (e: Exception) {
            // Speed adjustment might not be supported on all streams
        }
    }

    fun prepareAndPlayWavBytes(sourceKey: String, wavBytes: ByteArray, startPositionMs: Long = 0L) {
        try {
            release()
            currentSourceKey = sourceKey

            // Write to a temporary cached WAV file
            val tempFile = File(context.cacheDir, "hamseda_temp_play.wav")
            FileOutputStream(tempFile).use { fos ->
                fos.write(wavBytes)
            }

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(tempFile.absolutePath)
                setOnPreparedListener { mp ->
                    isPrepared = true
                    if (startPositionMs > 0) {
                        mp.seekTo(startPositionMs.toInt())
                    }
                    mp.start()
                }
                setOnCompletionListener {
                    onCompletionListener?.invoke()
                }
                setOnErrorListener { _, what, extra ->
                    onErrorListener?.invoke("MediaPlayer error: what=$what, extra=$extra")
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "prepareAndPlayWavBytes error: ${e.message}", e)
            onErrorListener?.invoke("خطا در بارگذاری صدا: ${e.message}")
        }
    }

    fun prepareAndPlayUri(sourceKey: String, uri: Uri, startPositionMs: Long = 0L) {
        try {
            release()
            currentSourceKey = sourceKey
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(context, uri)
                setOnPreparedListener { mp ->
                    isPrepared = true
                    if (startPositionMs > 0) {
                        mp.seekTo(startPositionMs.toInt())
                    }
                    mp.start()
                }
                setOnCompletionListener {
                    onCompletionListener?.invoke()
                }
                setOnErrorListener { _, what, extra ->
                    onErrorListener?.invoke("MediaPlayer uri error: what=$what, extra=$extra")
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "prepareAndPlayUri error: ${e.message}", e)
            onErrorListener?.invoke("خطا در پخش فایل صوتی: ${e.message}")
        }
    }

    fun prepareAndPlayStream(streamUrl: String, startPositionMs: Long = 0L) {
        try {
            release()
            currentSourceKey = streamUrl
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(streamUrl)
                setOnPreparedListener { mp ->
                    isPrepared = true
                    if (startPositionMs > 0) {
                        mp.seekTo(startPositionMs.toInt())
                    }
                    mp.start()
                }
                setOnCompletionListener {
                    onCompletionListener?.invoke()
                }
                setOnErrorListener { _, what, extra ->
                    onErrorListener?.invoke("Stream error: what=$what, extra=$extra")
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "prepareAndPlayStream error: ${e.message}", e)
            onErrorListener?.invoke("خطا در اتصال به جریان صدا: ${e.message}")
        }
    }

    fun play() {
        try {
            if (isPrepared && mediaPlayer?.isPlaying == false) {
                mediaPlayer?.start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "play() error: ${e.message}")
        }
    }

    fun pause() {
        try {
            if (isPrepared && mediaPlayer?.isPlaying == true) {
                mediaPlayer?.pause()
            }
        } catch (e: Exception) {
            Log.w(TAG, "pause() error: ${e.message}")
        }
    }

    fun seekTo(positionMs: Long) {
        try {
            if (isPrepared) {
                mediaPlayer?.seekTo(positionMs.toInt())
            }
        } catch (e: Exception) {
            Log.w(TAG, "seekTo() error: ${e.message}")
        }
    }

    fun getCurrentPosition(): Long {
        return try {
            if (isPrepared) (mediaPlayer?.currentPosition ?: 0).toLong() else 0L
        } catch (e: Exception) {
            0L
        }
    }

    fun getDuration(): Long {
        return try {
            if (isPrepared) (mediaPlayer?.duration ?: 0).toLong() else 0L
        } catch (e: Exception) {
            0L
        }
    }

    fun isPlaying(): Boolean {
        return try {
            mediaPlayer?.isPlaying == true
        } catch (e: Exception) {
            false
        }
    }

    fun release() {
        isPrepared = false
        try {
            mediaPlayer?.stop()
        } catch (e: Exception) {
            // ignore
        }
        try {
            mediaPlayer?.release()
        } catch (e: Exception) {
            // ignore
        }
        mediaPlayer = null
    }
}
