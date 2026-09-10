package com.example.network

import android.content.Context
import android.util.Log
import com.example.model.HostBeacon
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors

class UdpBeaconBroadcaster(
    private val hostName: String,
    private val getPort: () -> Int,
    private val getCurrentTrackTitle: () -> String
) {
    private val TAG = "UdpBeaconBroadcaster"
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile
    private var isBroadcasting = false
    @Volatile
    private var broadcastIntervalMs = 2000L
    private var socket: DatagramSocket? = null

    fun setBroadcastInterval(intervalMs: Long) {
        broadcastIntervalMs = intervalMs.coerceAtLeast(1000L)
    }

    fun start() {
        if (isBroadcasting) return
        isBroadcasting = true
        executor.execute {
            try {
                socket = DatagramSocket().apply {
                    broadcast = true
                }
                val broadcastAddress = InetAddress.getByName("255.255.255.255")
                val port = 8989

                while (isBroadcasting) {
                    try {
                        val localIp = NetworkUtils.getLocalIpAddress()
                        val json = JSONObject().apply {
                            put("type", "HAMSEDA_BEACON")
                            put("name", hostName)
                            put("ip", localIp)
                            put("port", getPort())
                            put("track", getCurrentTrackTitle())
                            put("timestamp", System.currentTimeMillis())
                        }
                        val data = json.toString().toByteArray(Charsets.UTF_8)
                        val packet = DatagramPacket(data, data.size, broadcastAddress, port)
                        socket?.send(packet)
                    } catch (e: Exception) {
                        Log.w(TAG, "Broadcast error: ${e.message}")
                    }
                    Thread.sleep(broadcastIntervalMs)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Broadcaster socket failed: ${e.message}")
            }
        }
    }

    fun stop() {
        isBroadcasting = false
        try {
            socket?.close()
        } catch (e: Exception) {
            // ignore
        }
        socket = null
    }
}

class UdpBeaconListener(
    private val context: Context,
    private val onHostDiscovered: (HostBeacon) -> Unit
) {
    private val TAG = "UdpBeaconListener"
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile
    private var isListening = false
    private var socket: DatagramSocket? = null

    fun start() {
        if (isListening) return
        isListening = true
        NetworkUtils.acquireMulticastLock(context)
        executor.execute {
            try {
                socket = DatagramSocket(8989).apply {
                    broadcast = true
                    reuseAddress = true
                }
                val buffer = ByteArray(2048)
                while (isListening) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    try {
                        val json = JSONObject(text)
                        if (json.optString("type") == "HAMSEDA_BEACON") {
                            val ip = json.optString("ip", packet.address.hostAddress ?: "")
                            val name = json.optString("name", "میزبان هم‌صدا")
                            val port = json.optInt("port", 8990)
                            val track = json.optString("track", "نامشخص")
                            val beacon = HostBeacon(
                                hostName = name,
                                ip = ip,
                                port = port,
                                currentTrackTitle = track,
                                timestamp = System.currentTimeMillis()
                            )
                            onHostDiscovered(beacon)
                        }
                    } catch (e: Exception) {
                        // ignore malformed packets
                    }
                }
            } catch (e: Exception) {
                if (isListening) {
                    Log.e(TAG, "Listener error: ${e.message}")
                }
            }
        }
    }

    fun stop() {
        isListening = false
        try {
            socket?.close()
        } catch (e: Exception) {
            // ignore
        }
        socket = null
        NetworkUtils.releaseMulticastLock()
    }
}
