package com.example.audio

import android.content.Context
import android.util.Log
import com.example.model.SyncPlaybackState
import com.example.model.Track
import com.example.network.SyncClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

class AudioSyncEngine(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val TAG = "AudioSyncEngine"

    val localPlayer = LocalAudioPlayer(context)

    // Manual latency calibration offset set by user on this speaker (-200ms to +200ms)
    private val _manualOffsetMs = MutableStateFlow(0L)
    val manualOffsetMs: StateFlow<Long> = _manualOffsetMs.asStateFlow()

    // Current measured synchronization error (drift in ms) for UI feedback
    private val _syncDriftMs = MutableStateFlow(0L)
    val syncDriftMs: StateFlow<Long> = _syncDriftMs.asStateFlow()

    private var syncJob: Job? = null
    private var activeSyncClient: SyncClient? = null
    private var lastLoadedTrackId: String? = null
    private var smoothedDrift: Double = 0.0
    private var isFirstDrift = true
    private var largeDriftConsecutiveCount = 0

    var onStateUpdated: ((SyncPlaybackState) -> Unit)? = null

    fun setManualOffset(offset: Long) {
        _manualOffsetMs.value = offset.coerceIn(-300L, 300L)
    }

    fun startSpeakerSync(syncClient: SyncClient) {
        stopSpeakerSync()
        activeSyncClient = syncClient
        isFirstDrift = true
        smoothedDrift = 0.0
        largeDriftConsecutiveCount = 0

        syncJob = scope.launch(Dispatchers.IO) {
            // Initial precision clock sync
            syncClient.synchronizeClock()

            while (isActive) {
                try {
                    val hostState = syncClient.fetchState()
                    if (hostState != null) {
                        applySpeakerSync(hostState, syncClient)
                        onStateUpdated?.invoke(hostState)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Speaker sync loop error: ${e.message}")
                }
                delay(400)
            }
        }
    }

    fun stopSpeakerSync() {
        syncJob?.cancel()
        syncJob = null
        activeSyncClient = null
        localPlayer.pause()
        _syncDriftMs.value = 0L
        isFirstDrift = true
        smoothedDrift = 0.0
        largeDriftConsecutiveCount = 0
    }

    private fun applySpeakerSync(state: SyncPlaybackState, client: SyncClient) {
        val estimatedHostNow = client.getEstimatedHostTime()

        // Duck music if live microphone broadcast from host is active
        if (state.isLiveMicActive) {
            localPlayer.duckForLiveMic()
        } else {
            localPlayer.restoreAfterLiveMic()
        }

        // Check if track changed
        if (state.trackId.isNotEmpty() && state.trackId != lastLoadedTrackId) {
            lastLoadedTrackId = state.trackId
            val streamUrl = client.getStreamUrl()
            localPlayer.prepareAndPlayStream(streamUrl, state.positionMs)
            isFirstDrift = true
            smoothedDrift = 0.0
            largeDriftConsecutiveCount = 0
            return
        }

        if (!state.isPlaying) {
            if (localPlayer.isPlaying()) {
                localPlayer.pause()
            }
            if (abs(localPlayer.getCurrentPosition() - state.positionMs) > 150) {
                localPlayer.seekTo(state.positionMs)
            }
            _syncDriftMs.value = 0L
            isFirstDrift = true
            return
        }

        // Host is playing
        val elapsedSinceHostSnapshot = (estimatedHostNow - state.hostTimestamp).coerceAtLeast(0L)
        val expectedPosition = state.positionMs + elapsedSinceHostSnapshot + _manualOffsetMs.value
        val actualPosition = localPlayer.getCurrentPosition()

        val rawDrift = actualPosition - expectedPosition

        // Apply Exponential Moving Average (EMA) low-pass filter to reject measurement jitter
        if (isFirstDrift) {
            smoothedDrift = rawDrift.toDouble()
            isFirstDrift = false
        } else {
            smoothedDrift = (0.70 * smoothedDrift) + (0.30 * rawDrift)
        }

        val drift = smoothedDrift.toLong()
        _syncDriftMs.value = drift

        if (!localPlayer.isPlaying()) {
            localPlayer.seekTo(expectedPosition.coerceAtLeast(0L))
            localPlayer.play()
            return
        }

        val absDrift = abs(drift)
        when {
            // Perfect sync deadband (< 40ms): 1.0f speed, zero buffer resets
            absDrift < 40 -> {
                largeDriftConsecutiveCount = 0
                localPlayer.adjustPlaybackRate(1.0f)
            }
            // Gentle micro speed glide (40ms - 120ms): 0.98x / 1.02x
            absDrift in 40..120 -> {
                largeDriftConsecutiveCount = 0
                if (drift > 0) {
                    localPlayer.adjustPlaybackRate(0.98f)
                } else {
                    localPlayer.adjustPlaybackRate(1.02f)
                }
            }
            // Moderate drift (121ms - 300ms): 0.95x / 1.05x
            absDrift in 121..300 -> {
                largeDriftConsecutiveCount = 0
                if (drift > 0) {
                    localPlayer.adjustPlaybackRate(0.95f)
                } else {
                    localPlayer.adjustPlaybackRate(1.05f)
                }
            }
            // Noticeable drift (301ms - 1200ms): 0.92x / 1.08x
            absDrift in 301..1200 -> {
                largeDriftConsecutiveCount = 0
                if (drift > 0) {
                    localPlayer.adjustPlaybackRate(0.92f)
                } else {
                    localPlayer.adjustPlaybackRate(1.08f)
                }
            }
            // Extreme drift (> 1200ms): only hard seek after 3 consecutive cycles
            else -> {
                largeDriftConsecutiveCount++
                if (largeDriftConsecutiveCount >= 3) {
                    localPlayer.seekTo(expectedPosition.coerceAtLeast(0L))
                    localPlayer.adjustPlaybackRate(1.0f)
                    isFirstDrift = true
                    largeDriftConsecutiveCount = 0
                }
            }
        }
    }

    fun release() {
        stopSpeakerSync()
        localPlayer.release()
    }
}
