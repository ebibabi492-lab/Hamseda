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
    val masterVolume: Float = 1.0f,
    val isLiveMicActive: Boolean = false,
    val playlistVersion: Long = 0L
)

data class HostBeacon(
    val hostName: String,
    val ip: String,
    val port: Int,
    val currentTrackTitle: String,
    val timestamp: Long = System.currentTimeMillis(),
    val discoveryType: String = "وای‌فای محلی"
)

data class StorageAudioFile(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val uriString: String,
    val folderName: String,
    val fileName: String,
    val mimeType: String = "audio/mpeg"
) {
    fun toTrack(): Track {
        return Track(
            id = "storage_${id}_${System.currentTimeMillis()}",
            title = title.ifBlank { fileName.substringBeforeLast('.') },
            artist = if (artist.isBlank() || artist == "<unknown>") "حافظه دستگاه" else artist,
            durationMs = if (durationMs > 0) durationMs else 180000L,
            genre = folderName.ifBlank { "موسیقی گوشی" },
            audioUri = uriString,
            addedBy = "حافظه گوشی",
            votes = 1
        )
    }
}

data class ConnectionRequest(
    val id: String,
    val deviceName: String,
    val ip: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class AuthorizedDevice(
    val id: String,
    val deviceName: String,
    val ip: String,
    val approvedAt: Long = System.currentTimeMillis()
)
