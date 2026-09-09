package com.example.network

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.audio.SynthesizedMusicLibrary
import com.example.model.DeviceSpeaker
import com.example.model.SyncPlaybackState
import com.example.model.Track
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class AudioHttpServer(
    private val context: Context,
    private val port: Int = 8990,
    private val getCurrentState: () -> SyncPlaybackState,
    private val getCurrentTrack: () -> Track?,
    private val getPlaylist: () -> List<Track>,
    private val onControlAction: (action: String, value: String?) -> Unit,
    private val onAddTrackToPlaylist: (Track) -> Unit,
    private val onSpeakerHeartbeat: (DeviceSpeaker) -> Unit
) {
    private val TAG = "AudioHttpServer"
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var isRunning = false
    private val threadPool = Executors.newCachedThreadPool()

    val registeredSpeakers = ConcurrentHashMap<String, DeviceSpeaker>()

    fun start() {
        if (isRunning) return
        isRunning = true
        threadPool.execute {
            try {
                serverSocket = ServerSocket(port)
                Log.d(TAG, "AudioHttpServer started on port $port")
                while (isRunning) {
                    val clientSocket = serverSocket?.accept() ?: break
                    threadPool.execute {
                        handleClient(clientSocket)
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "Server error: ${e.message}", e)
                }
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // ignore
        }
        serverSocket = null
    }

    private fun handleClient(socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val out = BufferedOutputStream(socket.getOutputStream())

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0].uppercase()
            val uri = parts[1]

            // Read headers
            val headers = mutableMapOf<String, String>()
            var line = reader.readLine()
            var contentLength = 0
            while (!line.isNullOrEmpty()) {
                val colonIdx = line.indexOf(':')
                if (colonIdx > 0) {
                    val key = line.substring(0, colonIdx).trim().lowercase()
                    val value = line.substring(colonIdx + 1).trim()
                    headers[key] = value
                    if (key == "content-length") {
                        contentLength = value.toIntOrNull() ?: 0
                    }
                }
                line = reader.readLine()
            }

            // Read body if POST
            var body = ""
            if (contentLength > 0) {
                val bodyChars = CharArray(contentLength)
                var readTotal = 0
                while (readTotal < contentLength) {
                    val count = reader.read(bodyChars, readTotal, contentLength - readTotal)
                    if (count < 0) break
                    readTotal += count
                }
                body = String(bodyChars, 0, readTotal)
            }

            val clientIp = socket.inetAddress.hostAddress ?: "Unknown"

            // Route handling
            when {
                uri.startsWith("/api/time") -> {
                    val t0 = uri.substringAfter("t0=", "0").toLongOrNull() ?: 0L
                    val t1 = System.currentTimeMillis()
                    val t2 = System.currentTimeMillis()
                    val json = JSONObject().apply {
                        put("t0", t0)
                        put("t1", t1)
                        put("t2", t2)
                    }
                    sendJsonResponse(out, 200, json.toString())
                }

                uri.startsWith("/api/state") -> {
                    val state = getCurrentState()
                    val json = JSONObject().apply {
                        put("trackId", state.trackId)
                        put("trackTitle", state.trackTitle)
                        put("artist", state.artist)
                        put("isPlaying", state.isPlaying)
                        put("positionMs", state.positionMs)
                        put("durationMs", state.durationMs)
                        put("hostTimestamp", System.currentTimeMillis())
                        put("scheduledStartHostTime", state.scheduledStartHostTime)
                        put("masterVolume", state.masterVolume.toDouble())
                    }
                    sendJsonResponse(out, 200, json.toString())
                }

                uri.startsWith("/api/playlist/add") && method == "POST" -> {
                    try {
                        val obj = JSONObject(body)
                        val newTrack = Track(
                            id = obj.optString("id", "track_${System.currentTimeMillis()}"),
                            title = obj.optString("title", "آهنگ ارسالی کاربر"),
                            artist = obj.optString("artist", "مهمان"),
                            durationMs = obj.optLong("durationMs", 30000L),
                            genre = obj.optString("genre", "اشتراکی"),
                            addedBy = obj.optString("addedBy", "مهمان (${clientIp})"),
                            votes = 1
                        )
                        onAddTrackToPlaylist(newTrack)
                        sendJsonResponse(out, 200, """{"status":"ok"}""")
                    } catch (e: Exception) {
                        sendJsonResponse(out, 400, """{"error":"${e.message}"}""")
                    }
                }

                uri.startsWith("/api/playlist") -> {
                    val list = getPlaylist()
                    val array = JSONArray()
                    list.forEach { t ->
                        array.put(JSONObject().apply {
                            put("id", t.id)
                            put("title", t.title)
                            put("artist", t.artist)
                            put("durationMs", t.durationMs)
                            put("genre", t.genre)
                            put("addedBy", t.addedBy)
                            put("votes", t.votes)
                        })
                    }
                    val json = JSONObject().apply {
                        put("playlist", array)
                    }
                    sendJsonResponse(out, 200, json.toString())
                }

                uri.startsWith("/api/control") && method == "POST" -> {
                    try {
                        val obj = JSONObject(body)
                        val action = obj.optString("action")
                        val value = obj.optString("value", null)
                        onControlAction(action, value)
                        sendJsonResponse(out, 200, """{"status":"ok"}""")
                    } catch (e: Exception) {
                        sendJsonResponse(out, 400, """{"error":"${e.message}"}""")
                    }
                }

                uri.startsWith("/api/register_speaker") || uri.startsWith("/api/heartbeat") -> {
                    try {
                        val obj = if (body.isNotEmpty()) JSONObject(body) else JSONObject()
                        val speakerId = obj.optString("id", clientIp)
                        val speakerName = obj.optString("name", "بلندگو ($clientIp)")
                        val volume = obj.optDouble("volume", 1.0).toFloat()
                        val latency = obj.optLong("latency", 0L)
                        val rtt = obj.optLong("rtt", 0L)

                        val speaker = DeviceSpeaker(
                            id = speakerId,
                            name = speakerName,
                            ip = clientIp,
                            port = port,
                            volume = volume,
                            latencyOffsetMs = latency,
                            rttMs = rtt,
                            lastSeenTimestamp = System.currentTimeMillis()
                        )
                        registeredSpeakers[speakerId] = speaker
                        onSpeakerHeartbeat(speaker)
                        sendJsonResponse(out, 200, """{"status":"ok"}""")
                    } catch (e: Exception) {
                        sendJsonResponse(out, 400, """{"error":"${e.message}"}""")
                    }
                }

                uri.startsWith("/api/stream") -> {
                    serveAudioStream(out, headers)
                }

                else -> {
                    sendJsonResponse(out, 404, """{"error":"Not Found"}""")
                }
            }
        } catch (e: Exception) {
            // socket closed or error
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun serveAudioStream(out: BufferedOutputStream, headers: Map<String, String>) {
        val currentTrack = getCurrentTrack()
        val audioBytes: ByteArray = if (currentTrack?.audioUri != null) {
            try {
                val uri = Uri.parse(currentTrack.audioUri)
                val inputStream = context.contentResolver.openInputStream(uri)
                inputStream?.readBytes() ?: SynthesizedMusicLibrary.getWavDataForTrack(currentTrack.id)
            } catch (e: Exception) {
                SynthesizedMusicLibrary.getWavDataForTrack(currentTrack.id)
            }
        } else {
            val trackId = currentTrack?.id ?: "synth_neon_sunset"
            SynthesizedMusicLibrary.getWavDataForTrack(trackId)
        }

        val totalLength = audioBytes.size.toLong()
        val rangeHeader = headers["range"]

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val range = rangeHeader.removePrefix("bytes=").trim()
            val dashIdx = range.indexOf('-')
            var start = 0L
            var end = totalLength - 1L
            if (dashIdx != -1) {
                val startStr = range.substring(0, dashIdx)
                val endStr = range.substring(dashIdx + 1)
                if (startStr.isNotEmpty()) start = startStr.toLongOrNull() ?: 0L
                if (endStr.isNotEmpty()) end = endStr.toLongOrNull() ?: (totalLength - 1L)
            }
            if (end >= totalLength) end = totalLength - 1L
            val contentLength = end - start + 1L

            val headerStr = "HTTP/1.1 206 Partial Content\r\n" +
                    "Content-Type: audio/wav\r\n" +
                    "Accept-Ranges: bytes\r\n" +
                    "Content-Range: bytes $start-$end/$totalLength\r\n" +
                    "Content-Length: $contentLength\r\n" +
                    "Connection: close\r\n\r\n"

            out.write(headerStr.toByteArray(Charsets.UTF_8))
            out.write(audioBytes, start.toInt(), contentLength.toInt())
            out.flush()
        } else {
            val headerStr = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: audio/wav\r\n" +
                    "Accept-Ranges: bytes\r\n" +
                    "Content-Length: $totalLength\r\n" +
                    "Connection: close\r\n\r\n"

            out.write(headerStr.toByteArray(Charsets.UTF_8))
            out.write(audioBytes)
            out.flush()
        }
    }

    private fun sendJsonResponse(out: BufferedOutputStream, code: Int, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val statusText = if (code == 200) "OK" else if (code == 400) "Bad Request" else "Not Found"
        val header = "HTTP/1.1 $code $statusText\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }
}
