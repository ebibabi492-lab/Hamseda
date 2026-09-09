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

    fun setManualOffset(offset: Long) {
        _manualOffsetMs.value = offset.coerceIn(-300L, 300L)
    }

    fun startSpeakerSync(syncClient: SyncClient) {
        stopSpeakerSync()
        activeSyncClient = syncClient

        syncJob = scope.launch(Dispatchers.IO) {
            // Initial precision clock sync
            syncClient.synchronizeClock()

            while (isActive) {
                try {
                    val hostState = syncClient.fetchState()
                    if (hostState != null) {
                        applySpeakerSync(hostState, syncClient)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Speaker sync loop error: ${e.message}")
                }
                delay(350)
            }
        }
    }

    fun stopSpeakerSync() {
        syncJob?.cancel()
        syncJob = null
        activeSyncClient = null
        localPlayer.pause()
        _syncDriftMs.value = 0L
    }

    private fun applySpeakerSync(state: SyncPlaybackState, client: SyncClient) {
        val estimatedHostNow = client.getEstimatedHostTime()

        // Check if track changed
        if (state.trackId.isNotEmpty() && state.trackId != lastLoadedTrackId) {
            lastLoadedTrackId = state.trackId
            val streamUrl = client.getStreamUrl()
            localPlayer.prepareAndPlayStream(streamUrl, state.positionMs)
            return
        }

        if (!state.isPlaying) {
            if (localPlayer.isPlaying()) {
                localPlayer.pause()
            }
            if (abs(localPlayer.getCurrentPosition() - state.positionMs) > 100) {
                localPlayer.seekTo(state.positionMs)
            }
            _syncDriftMs.value = 0L
            return
        }

        // Host is playing
        val elapsedSinceHostSnapshot = (estimatedHostNow - state.hostTimestamp).coerceAtLeast(0L)
        val expectedPosition = state.positionMs + elapsedSinceHostSnapshot + _manualOffsetMs.value
        val actualPosition = localPlayer.getCurrentPosition()

        val drift = actualPosition - expectedPosition
        _syncDriftMs.value = drift

        if (!localPlayer.isPlaying()) {
            localPlayer.seekTo(expectedPosition.coerceAtLeast(0L))
            localPlayer.play()
            return
        }

        val absDrift = abs(drift)
        when {
            // Very close (<15ms): perfect sync, normal speed
            absDrift < 15 -> {
                localPlayer.adjustPlaybackRate(1.0f)
            }
            // Slight drift (15ms - 80ms): micro speed-tune to seamlessly glide into phase
            absDrift in 15..80 -> {
                if (drift > 0) {
                    // We are slightly ahead, slow down slightly
                    localPlayer.adjustPlaybackRate(0.97f)
                } else {
                    // We are slightly behind, speed up slightly
                    localPlayer.adjustPlaybackRate(1.03f)
                }
            }
            // Moderate drift (80ms - 180ms)
            absDrift in 81..180 -> {
                if (drift > 0) {
                    localPlayer.adjustPlaybackRate(0.94f)
                } else {
                    localPlayer.adjustPlaybackRate(1.06f)
                }
            }
            // Significant drift (> 180ms): direct seek to re-align
            else -> {
                localPlayer.seekTo(expectedPosition.coerceAtLeast(0L))
                localPlayer.adjustPlaybackRate(1.0f)
            }
        }
    }

    fun release() {
        stopSpeakerSync()
        localPlayer.release()
    }
}
