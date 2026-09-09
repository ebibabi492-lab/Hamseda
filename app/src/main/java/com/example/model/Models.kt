package com.example.model

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val genre: String,
    val audioUri: String? = null, // null for synthesized built-in tracks, or file/content URI
    val addedBy: String = "سیستم",
    val votes: Int = 0
)

data class DeviceSpeaker(
    val id: String,
    val name: String,
    val ip: String,
    val port: Int = 8990,
    val volume: Float = 1.0f,
    val isMuted: Boolean = false,
    val latencyOffsetMs: Long = 0L,
    val rttMs: Long = 0L,
    val lastSeenTimestamp: Long = System.currentTimeMillis()
)

data class SyncPlaybackState(
    val trackId: String = "",
    val trackTitle: String = "",
    val artist: String = "",
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val hostTimestamp: Long = 0L,
    val scheduledStartHostTime: Long = 0L,
    val masterVolume: Float = 1.0f
)

data class HostBeacon(
    val hostName: String,
    val ip: String,
    val port: Int,
    val currentTrackTitle: String,
    val timestamp: Long = System.currentTimeMillis()
)
