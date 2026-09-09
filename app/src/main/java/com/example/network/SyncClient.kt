package com.example.network

import android.util.Log
import com.example.model.DeviceSpeaker
import com.example.model.SyncPlaybackState
import com.example.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SyncClient(
    private val hostIp: String,
    private val hostPort: Int = 8990
) {
    private val TAG = "SyncClient"
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    private val baseUrl = "http://$hostIp:$hostPort"
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // Smooth clock offset: HostTime = System.currentTimeMillis() + clockOffsetMs
    @Volatile
    var clockOffsetMs: Long = 0L
        private set

    @Volatile
    var roundTripDelayMs: Long = 0L
        private set

    suspend fun synchronizeClock(): Boolean = withContext(Dispatchers.IO) {
        var minRtt = Long.MAX_VALUE
        var bestOffset = 0L
        var successCount = 0

        // Run 5 NTP ping-pong rounds to filter out jitter and get rock-solid sync
        for (i in 0 until 5) {
            try {
                val t0 = System.currentTimeMillis()
                val request = Request.Builder()
                    .url("$baseUrl/api/time?t0=$t0")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        val t3 = System.currentTimeMillis()
                        val json = JSONObject(body)
                        val t1 = json.optLong("t1")
                        val t2 = json.optLong("t2")

                        val rtt = (t3 - t0) - (t2 - t1)
                        val offset = ((t1 - t0) + (t2 - t3)) / 2

                        if (rtt < minRtt) {
                            minRtt = rtt
                            bestOffset = offset
                        }
                        successCount++
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Clock sync probe failed: ${e.message}")
            }
            kotlinx.coroutines.delay(40)
        }

        if (successCount > 0) {
            clockOffsetMs = bestOffset
            roundTripDelayMs = if (minRtt == Long.MAX_VALUE) 0L else minRtt
            Log.d(TAG, "Clock synchronized! Offset: ${bestOffset}ms, RTT: ${roundTripDelayMs}ms")
            true
        } else {
            false
        }
    }

    fun getEstimatedHostTime(): Long {
        return System.currentTimeMillis() + clockOffsetMs
    }

    suspend fun fetchState(): SyncPlaybackState? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/state")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@withContext null
                    val json = JSONObject(body)
                    return@withContext SyncPlaybackState(
                        trackId = json.optString("trackId"),
                        trackTitle = json.optString("trackTitle"),
                        artist = json.optString("artist"),
                        isPlaying = json.optBoolean("isPlaying"),
                        positionMs = json.optLong("positionMs"),
                        durationMs = json.optLong("durationMs"),
                        hostTimestamp = json.optLong("hostTimestamp"),
                        scheduledStartHostTime = json.optLong("scheduledStartHostTime"),
                        masterVolume = json.optDouble("masterVolume", 1.0).toFloat()
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchState error: ${e.message}")
        }
        null
    }

    suspend fun sendHeartbeat(speaker: DeviceSpeaker): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("id", speaker.id)
                put("name", speaker.name)
                put("volume", speaker.volume.toDouble())
                put("latency", speaker.latencyOffsetMs)
                put("rtt", roundTripDelayMs)
            }
            val request = Request.Builder()
                .url("$baseUrl/api/heartbeat")
                .post(json.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    suspend fun sendControlAction(action: String, value: String? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("action", action)
                if (value != null) put("value", value)
            }
            val request = Request.Builder()
                .url("$baseUrl/api/control")
                .post(json.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.e(TAG, "sendControlAction error: ${e.message}")
            false
        }
    }

    suspend fun fetchPlaylist(): List<Track> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/playlist")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@withContext emptyList()
                    val json = JSONObject(body)
                    val array = json.optJSONArray("playlist") ?: return@withContext emptyList()
                    val list = mutableListOf<Track>()
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        list.add(
                            Track(
                                id = obj.optString("id"),
                                title = obj.optString("title"),
                                artist = obj.optString("artist"),
                                durationMs = obj.optLong("durationMs"),
                                genre = obj.optString("genre"),
                                addedBy = obj.optString("addedBy"),
                                votes = obj.optInt("votes")
                            )
                        )
                    }
                    return@withContext list
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchPlaylist error: ${e.message}")
        }
        emptyList()
    }

    suspend fun addTrackToPlaylist(track: Track): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("id", track.id)
                put("title", track.title)
                put("artist", track.artist)
                put("durationMs", track.durationMs)
                put("genre", track.genre)
                put("addedBy", track.addedBy)
            }
            val request = Request.Builder()
                .url("$baseUrl/api/playlist/add")
                .post(json.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    fun getStreamUrl(): String {
        return "$baseUrl/api/stream"
    }
}
