package com.example.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.util.Log
import com.example.model.HostBeacon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * High-reliability Network Discovery Module using Android NSD (Network Service Discovery / mDNS)
 * along with Wi-Fi Direct (P2P) and UDP Beacon fallback for discovery of nearby devices.
 */
class NearbyDiscoveryManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val TAG = "NearbyDiscoveryManager"
    private val SERVICE_TYPE = "_hamseda._tcp."

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val p2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var p2pChannel: WifiP2pManager.Channel? = null

    // Deduplicated list of discovered hosts
    private val hostMap = ConcurrentHashMap<String, HostBeacon>()
    private val _discoveredHosts = MutableStateFlow<List<HostBeacon>>(emptyList())
    val discoveredHosts: StateFlow<List<HostBeacon>> = _discoveredHosts.asStateFlow()

    // Wi-Fi Direct peers
    private val _wifiDirectPeers = MutableStateFlow<List<WifiP2pDevice>>(emptyList())
    val wifiDirectPeers: StateFlow<List<WifiP2pDevice>> = _wifiDirectPeers.asStateFlow()

    // NSD listeners
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    // UDP discovery fallback
    private var udpBroadcaster: UdpBeaconBroadcaster? = null
    private var udpListener: UdpBeaconListener? = null

    init {
        try {
            if (p2pManager != null) {
                p2pChannel = p2pManager.initialize(context, context.mainLooper, null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Wi-Fi Direct initialize warning: ${e.message}")
        }
    }

    // ================= HOST REGISTRATION =================

    fun startHostPublishing(
        hostName: String,
        port: Int,
        getCurrentTrackTitle: () -> String
    ) {
        stopHostPublishing()

        // 1. Publish via NSD (mDNS/DNS-SD)
        try {
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "HamSeda_${Build.MODEL.replace(" ", "_")}"
                serviceType = SERVICE_TYPE
                setPort(port)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    setAttribute("hostName", hostName)
                    setAttribute("app", "HamSeda")
                }
            }

            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                    Log.d(TAG, "NSD Service registered: ${serviceInfo.serviceName}")
                }

                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.e(TAG, "NSD Registration failed: errorCode $errorCode")
                }

                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                    Log.d(TAG, "NSD Service unregistered: ${serviceInfo.serviceName}")
                }

                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.e(TAG, "NSD Unregistration failed: errorCode $errorCode")
                }
            }

            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e(TAG, "NSD registerService error: ${e.message}", e)
        }

        // 2. Publish via UDP Beacon as complementary fallback
        udpBroadcaster = UdpBeaconBroadcaster(
            hostName = hostName,
            getPort = { port },
            getCurrentTrackTitle = getCurrentTrackTitle
        )
        if (isBatterySaverActive) {
            udpBroadcaster?.setBroadcastInterval(4500L)
        }
        udpBroadcaster?.start()
    }

    private var isBatterySaverActive = false

    fun setBatterySaverMode(enabled: Boolean) {
        isBatterySaverActive = enabled
        udpBroadcaster?.setBroadcastInterval(if (enabled) 4500L else 2000L)
    }

    fun stopHostPublishing() {
        try {
            registrationListener?.let { listener ->
                nsdManager?.unregisterService(listener)
            }
        } catch (e: Exception) {
            // ignore
        }
        registrationListener = null

        udpBroadcaster?.stop()
        udpBroadcaster = null
    }

    // ================= SPEAKER DISCOVERY =================

    fun startDiscovery(onHostFound: ((HostBeacon) -> Unit)? = null) {
        stopDiscovery()
        hostMap.clear()
        _discoveredHosts.value = emptyList()

        // 1. Start NSD Discovery
        try {
            discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) {
                    Log.d(TAG, "NSD Discovery started for $regType")
                }

                override fun onServiceFound(service: NsdServiceInfo) {
                    Log.d(TAG, "NSD Service found: ${service.serviceName}")
                    if (service.serviceType.contains("hamseda")) {
                        try {
                            nsdManager?.resolveService(service, object : NsdManager.ResolveListener {
                                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                                    Log.w(TAG, "NSD Resolve failed for ${serviceInfo.serviceName}: $errorCode")
                                }

                                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                                    val hostAddress: InetAddress? = serviceInfo.host
                                    val ip = hostAddress?.hostAddress ?: return
                                    val port = serviceInfo.port
                                    val hostDisplayName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                        serviceInfo.attributes["hostName"]?.let { String(it, Charsets.UTF_8) }
                                            ?: serviceInfo.serviceName
                                    } else {
                                        serviceInfo.serviceName
                                    }

                                    val beacon = HostBeacon(
                                        hostName = hostDisplayName,
                                        ip = ip,
                                        port = port,
                                        currentTrackTitle = "در حال پخش با هم‌صدا",
                                        timestamp = System.currentTimeMillis(),
                                        discoveryType = "سرویس NSD (mDNS)"
                                    )
                                    addDiscoveredHost(beacon, onHostFound)
                                }
                            })
                        } catch (e: Exception) {
                            Log.w(TAG, "ResolveService invocation failed: ${e.message}")
                        }
                    }
                }

                override fun onServiceLost(service: NsdServiceInfo) {
                    Log.d(TAG, "NSD Service lost: ${service.serviceName}")
                }

                override fun onDiscoveryStopped(serviceType: String) {
                    Log.d(TAG, "NSD Discovery stopped")
                }

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.e(TAG, "NSD Start discovery failed: $errorCode")
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.e(TAG, "NSD Stop discovery failed: $errorCode")
                }
            }

            nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start NSD discovery: ${e.message}", e)
        }

        // 2. Start UDP Beacon Listener
        udpListener = UdpBeaconListener(context) { beacon ->
            addDiscoveredHost(beacon, onHostFound)
        }
        udpListener?.start()

        // 3. Initiate Wi-Fi Direct Peer Discovery
        discoverWifiDirectPeers()
    }

    private fun addDiscoveredHost(beacon: HostBeacon, onHostFound: ((HostBeacon) -> Unit)? = null) {
        val key = "${beacon.ip}:${beacon.port}"
        val existing = hostMap[key]
        if (existing == null || System.currentTimeMillis() - existing.timestamp > 3000L) {
            hostMap[key] = beacon
            scope.launch(Dispatchers.Main) {
                _discoveredHosts.value = hostMap.values.sortedByDescending { it.timestamp }
                onHostFound?.invoke(beacon)
            }
        }
    }

    fun discoverWifiDirectPeers() {
        try {
            p2pChannel?.let { channel ->
                p2pManager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.d(TAG, "Wi-Fi Direct discoverPeers started")
                        try {
                            p2pManager?.requestPeers(channel) { peers: WifiP2pDeviceList? ->
                                val list = peers?.deviceList?.toList() ?: emptyList()
                                _wifiDirectPeers.value = list
                            }
                        } catch (e: Exception) {
                            // ignore
                        }
                    }

                    override fun onFailure(reasonCode: Int) {
                        Log.w(TAG, "Wi-Fi Direct discoverPeers failed with reason: $reasonCode")
                    }
                })
            }
        } catch (e: Exception) {
            Log.w(TAG, "discoverWifiDirectPeers error: ${e.message}")
        }
    }

    fun stopDiscovery() {
        try {
            discoveryListener?.let { listener ->
                nsdManager?.stopServiceDiscovery(listener)
            }
        } catch (e: Exception) {
            // ignore
        }
        discoveryListener = null

        udpListener?.stop()
        udpListener = null
    }

    fun release() {
        stopHostPublishing()
        stopDiscovery()
    }
}
