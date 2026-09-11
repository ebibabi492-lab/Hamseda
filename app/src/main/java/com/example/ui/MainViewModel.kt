package com.example.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioSyncEngine
import com.example.audio.LiveMicBroadcaster
import com.example.audio.LiveMicReceiver
import com.example.audio.SynthesizedMusicLibrary
import com.example.data.AppDatabase
import com.example.data.StorageAudioScanner
import com.example.data.TrackEntity
import com.example.model.ConnectionRequest
import com.example.model.DeviceSpeaker
import com.example.model.HostBeacon
import com.example.model.StorageAudioFile
import com.example.model.SyncPlaybackState
import com.example.model.Track
import com.example.network.AudioHttpServer
import com.example.network.NearbyDiscoveryManager
import com.example.network.NetworkUtils
import com.example.network.SpeakerHeartbeatResult
import com.example.network.SyncClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class AppMode {
    HOST,    // میزبان و فرستنده صدا
    SPEAKER  // بلندگوی همراه و گیرنده
}

enum class NavigationTab {
    PLAYER,      // پخش‌کننده و هماهنگ‌سازی
    PLAYLIST,    // پلی‌لیست مشترک
    REMOTE,      // کنترل از راه دور
    SPEAKERS     // مدیریت بلندگوها
}

enum class SpeakerConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    WAITING_APPROVAL,
    REJECTED,
    SYNCING_CLOCK,
    CONNECTED,
    ERROR
}

data class UiState(
    val mode: AppMode = AppMode.HOST,
    val currentTab: NavigationTab = NavigationTab.PLAYER,
    val localIp: String = "127.0.0.1",
    val hostPort: Int = 8990,
    val isHostServerRunning: Boolean = false,

    // Host & Playback
    val currentTrack: Track = SynthesizedMusicLibrary.builtInTracks.first(),
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = SynthesizedMusicLibrary.builtInTracks.first().durationMs,
    val masterVolume: Float = 0.9f,

    // Live Microphone (Voice broadcast from Host to Speakers)
    val isLiveMicBroadcasting: Boolean = false,
    val isLiveMicReceiving: Boolean = false,
    val liveMicLevel: Float = 0f,

    // Connected speakers (on Host)
    val connectedSpeakers: List<DeviceSpeaker> = emptyList(),

    // Device Access Control & Host Approval
    val isHostApprovalRequired: Boolean = true,
    val pendingConnectionRequests: List<ConnectionRequest> = emptyList(),
    val approvedDeviceIds: Set<String> = emptySet(),
    val blockedDeviceIds: Set<String> = emptySet(),

    // Discovered hosts (on Speaker)
    val discoveredHosts: List<HostBeacon> = emptyList(),
    val wifiDirectPeersCount: Int = 0,
    val speakerStatus: SpeakerConnectionStatus = SpeakerConnectionStatus.DISCONNECTED,
    val connectedHost: HostBeacon? = null,
    val speakerVolume: Float = 1.0f,
    val manualLatencyOffsetMs: Long = 0L,
    val syncDriftMs: Long = 0L,
    val clockRttMs: Long = 0L,

    // Shared Playlist
    val playlist: List<Track> = emptyList(),

    // Battery Saver & Network Sync Optimization
    val isBatterySaverEnabled: Boolean = false,
    val isSystemLowBattery: Boolean = false,
    val batteryPercent: Int = 100,
    val isBatteryCharging: Boolean = false,
    val userExplicitBatterySaverPreference: Boolean = false,
    val syncIntervalMs: Long = 400L,

    // Internal Storage File Manager & Search
    val storageAudioFiles: List<StorageAudioFile> = emptyList(),
    val isStorageScanning: Boolean = false,
    val storageSearchQuery: String = "",
    val selectedStorageFolder: String = "همه",
    val selectedStorageFileIds: Set<Long> = emptySet(),

    // Status / Toast message in Persian
    val message: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "MainViewModel"
    private val db = AppDatabase.getDatabase(application)
    private val playlistDao = db.playlistDao()

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val syncEngine = AudioSyncEngine(application, viewModelScope)
    private val nearbyDiscoveryManager = NearbyDiscoveryManager(application, viewModelScope)
    private val liveMicBroadcaster = LiveMicBroadcaster(application, viewModelScope) {
        _uiState.value.connectedSpeakers.map { it.ip }
    }
    private val liveMicReceiver = LiveMicReceiver(
        context = application,
        scope = viewModelScope,
        getVolume = { _uiState.value.speakerVolume },
        onLiveMicStatusChanged = { isActive ->
            _uiState.update { it.copy(isLiveMicReceiving = isActive) }
            if (isActive) {
                syncEngine.localPlayer.duckForLiveMic()
            } else {
                syncEngine.localPlayer.restoreAfterLiveMic()
            }
        }
    )

    // Host network services
    private var httpServer: AudioHttpServer? = null
    private var activeSyncClient: SyncClient? = null

    private var positionTickerJob: Job? = null
    private var speakerPlaylistSyncJob: Job? = null
    private var playlistVersion: Long = 1L
    private var lastSyncedPlaylistVersion: Long = -1L

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateBatteryStatus(intent)
        }
    }

    init {
        val ip = NetworkUtils.getLocalIpAddress()
        _uiState.update { it.copy(localIp = ip) }

        // Initialize playlist in database
        viewModelScope.launch(Dispatchers.IO) {
            val existing = playlistDao.getAllTracksSnapshot()
            if (existing.isEmpty()) {
                val initialEntities = SynthesizedMusicLibrary.builtInTracks.mapIndexed { index, track ->
                    TrackEntity(
                        id = track.id,
                        title = track.title,
                        artist = track.artist,
                        durationMs = track.durationMs,
                        genre = track.genre,
                        audioUri = track.audioUri,
                        addedBy = track.addedBy,
                        votes = track.votes,
                        orderIndex = index
                    )
                }
                playlistDao.insertAll(initialEntities)
            }

            // Collect playlist changes
            playlistDao.getAllTracks().collect { entities ->
                val tracks = entities.map { e ->
                    Track(
                        id = e.id,
                        title = e.title,
                        artist = e.artist,
                        durationMs = e.durationMs,
                        genre = e.genre,
                        audioUri = e.audioUri,
                        addedBy = e.addedBy,
                        votes = e.votes
                    )
                }
                playlistVersion++
                if (_uiState.value.mode == AppMode.HOST) {
                    _uiState.update { state ->
                        val cur = if (tracks.any { it.id == state.currentTrack.id }) state.currentTrack else tracks.firstOrNull() ?: state.currentTrack
                        state.copy(playlist = tracks, currentTrack = cur)
                    }
                }
            }
        }

        // Collect discovered hosts from NSD & Wi-Fi Direct
        viewModelScope.launch {
            nearbyDiscoveryManager.discoveredHosts.collect { hosts ->
                _uiState.update { it.copy(discoveredHosts = hosts) }
            }
        }

        // Collect Wi-Fi Direct peers
        viewModelScope.launch {
            nearbyDiscoveryManager.wifiDirectPeers.collect { peers ->
                _uiState.update { it.copy(wifiDirectPeersCount = peers.size) }
            }
        }

        // Collect live mic status & RMS level
        viewModelScope.launch {
            liveMicBroadcaster.isBroadcasting.collect { isBroadcasting ->
                _uiState.update { it.copy(isLiveMicBroadcasting = isBroadcasting) }
            }
        }
        viewModelScope.launch {
            liveMicBroadcaster.micLevel.collect { level ->
                _uiState.update { it.copy(liveMicLevel = level) }
            }
        }

        // Single unified sync state update from AudioSyncEngine
        syncEngine.onStateUpdated = { state ->
            _uiState.update { current ->
                current.copy(
                    isPlaying = state.isPlaying,
                    currentPositionMs = state.positionMs,
                    durationMs = state.durationMs,
                    isLiveMicReceiving = state.isLiveMicActive || liveMicReceiver.isReceiving.value,
                    currentTrack = current.currentTrack.copy(
                        id = state.trackId,
                        title = state.trackTitle,
                        artist = state.artist
                    )
                )
            }

            if (_uiState.value.mode == AppMode.SPEAKER && state.playlistVersion > 0 && state.playlistVersion != lastSyncedPlaylistVersion) {
                lastSyncedPlaylistVersion = state.playlistVersion
                activeSyncClient?.let { client ->
                    viewModelScope.launch(Dispatchers.IO) {
                        refreshSpeakerPlaylist(client)
                    }
                }
            }
        }

        // Start in Host mode by default
        startHostMode()

        // Listen for sync drift from syncEngine
        viewModelScope.launch {
            syncEngine.syncDriftMs.collect { drift ->
                _uiState.update { it.copy(syncDriftMs = drift) }
            }
        }

        // Listen for dynamic sync interval from syncEngine
        viewModelScope.launch {
            syncEngine.currentSyncIntervalMs.collect { interval ->
                _uiState.update { it.copy(syncIntervalMs = interval) }
            }
        }

        // Monitor battery level and charging state
        try {
            val batteryFilter = IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            }
            val stickyBatteryIntent = application.registerReceiver(batteryReceiver, batteryFilter)
            updateBatteryStatus(stickyBatteryIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register battery receiver: ${e.message}")
        }

        // Initialize internal storage audio files scan
        scanStorageAudio()
    }

    private fun updateBatteryStatus(intent: Intent?) {
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) (level * 100) / scale else 100
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        val powerManager = getApplication<Application>().getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isSystemPowerSave = powerManager?.isPowerSaveMode == true
        val isLowBattery = percent <= 20 || isSystemPowerSave

        _uiState.update { current ->
            val shouldAutoEnable = !current.userExplicitBatterySaverPreference && isLowBattery && !isCharging
            val effectiveEnabled = if (current.userExplicitBatterySaverPreference) {
                current.isBatterySaverEnabled
            } else {
                shouldAutoEnable || current.isBatterySaverEnabled
            }

            current.copy(
                batteryPercent = percent,
                isBatteryCharging = isCharging,
                isSystemLowBattery = isLowBattery,
                isBatterySaverEnabled = effectiveEnabled
            )
        }

        applyBatterySaverToEngines()
    }

    fun toggleBatterySaver() {
        _uiState.update { current ->
            val newEnabled = !current.isBatterySaverEnabled
            current.copy(
                isBatterySaverEnabled = newEnabled,
                userExplicitBatterySaverPreference = true,
                message = if (newEnabled)
                    "حالت بهینه‌سازی باتری فعال شد: فرکانس شبکه کاهش یافت (هماهنگی پایدار)"
                else
                    "حالت بهینه‌سازی باتری غیرفعال شد: فرکانس شبکه به حالت عادی بازگشت"
            )
        }
        applyBatterySaverToEngines()
    }

    private fun applyBatterySaverToEngines() {
        val isSaver = _uiState.value.isBatterySaverEnabled
        syncEngine.isBatterySaverActive = isSaver
        nearbyDiscoveryManager.setBatterySaverMode(isSaver)
    }

    fun setAppMode(mode: AppMode) {
        if (_uiState.value.mode == mode) return
        _uiState.update { it.copy(mode = mode, message = if (mode == AppMode.HOST) "به حالت میزبان تغییر یافت" else "به حالت بلندگو تغییر یافت") }

        if (mode == AppMode.HOST) {
            stopSpeakerMode()
            startHostMode()
        } else {
            stopHostMode()
            startSpeakerMode()
        }
    }

    fun setTab(tab: NavigationTab) {
        _uiState.update { it.copy(currentTab = tab) }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    // ================= HOST LOGIC =================

    private fun startHostMode() {
        val ip = NetworkUtils.getLocalIpAddress()
        val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
        val hostName = "میزبان هم‌صدا ($deviceModel)"

        httpServer = AudioHttpServer(
            context = getApplication(),
            port = 8990,
            getCurrentState = {
                SyncPlaybackState(
                    trackId = _uiState.value.currentTrack.id,
                    trackTitle = _uiState.value.currentTrack.title,
                    artist = _uiState.value.currentTrack.artist,
                    isPlaying = _uiState.value.isPlaying,
                    positionMs = syncEngine.localPlayer.getCurrentPosition(),
                    durationMs = _uiState.value.durationMs,
                    hostTimestamp = System.currentTimeMillis(),
                    scheduledStartHostTime = 0L,
                    masterVolume = _uiState.value.masterVolume,
                    isLiveMicActive = _uiState.value.isLiveMicBroadcasting,
                    playlistVersion = playlistVersion
                )
            },
            getCurrentTrack = { _uiState.value.currentTrack },
            getPlaylist = { _uiState.value.playlist },
            onControlAction = { action, value ->
                handleRemoteControl(action, value)
            },
            onAddTrackToPlaylist = { track ->
                addTrackToPlaylist(track)
            },
            onDeleteTrackFromPlaylist = { trackId ->
                removeTrackFromPlaylist(trackId)
            },
            onUpvoteTrackInPlaylist = { trackId ->
                upvoteTrack(trackId)
            },
            onSpeakerHeartbeat = { speaker ->
                updateSpeakerFromHeartbeat(speaker)
            },
            isApprovalRequired = { _uiState.value.isHostApprovalRequired },
            isDeviceApproved = { deviceId, ip ->
                !_uiState.value.isHostApprovalRequired ||
                        _uiState.value.approvedDeviceIds.contains(deviceId) ||
                        _uiState.value.approvedDeviceIds.contains(ip)
            },
            isDeviceBlocked = { deviceId, ip ->
                _uiState.value.blockedDeviceIds.contains(deviceId) ||
                        _uiState.value.blockedDeviceIds.contains(ip)
            },
            onConnectionRequested = { request ->
                handleConnectionRequest(request)
            }
        )
        httpServer?.start()

        // Publish service using NSD (mDNS) and UDP Beacon fallback
        nearbyDiscoveryManager.startHostPublishing(
            hostName = hostName,
            port = 8990,
            getCurrentTrackTitle = { _uiState.value.currentTrack.title }
        )

        _uiState.update {
            it.copy(
                localIp = ip,
                isHostServerRunning = true,
                message = "سرویس میزبان و کشف شبکه NSD فعال شد ($ip:8990)"
            )
        }

        startPositionTicker()
    }

    private fun stopHostMode() {
        liveMicBroadcaster.stopBroadcasting()
        nearbyDiscoveryManager.stopHostPublishing()
        httpServer?.stop()
        httpServer = null
        syncEngine.localPlayer.pause()
        _uiState.update { it.copy(isHostServerRunning = false, isPlaying = false, isLiveMicBroadcasting = false) }
    }

    fun togglePlayPause() {
        if (_uiState.value.mode == AppMode.HOST) {
            if (_uiState.value.isPlaying) {
                syncEngine.localPlayer.pause()
                _uiState.update { it.copy(isPlaying = false) }
            } else {
                playCurrentTrackOnHost(startPositionMs = _uiState.value.currentPositionMs)
            }
        } else {
            // Speaker sends remote control to host
            viewModelScope.launch {
                val action = if (_uiState.value.isPlaying) "pause" else "play"
                activeSyncClient?.sendControlAction(action)
            }
        }
    }

    fun playTrack(track: Track) {
        _uiState.update { it.copy(currentTrack = track, durationMs = track.durationMs, currentPositionMs = 0L) }
        if (_uiState.value.mode == AppMode.HOST) {
            playCurrentTrackOnHost(startPositionMs = 0L)
        } else {
            viewModelScope.launch {
                activeSyncClient?.sendControlAction("select_track", track.id)
            }
        }
    }

    private fun playCurrentTrackOnHost(startPositionMs: Long) {
        val track = _uiState.value.currentTrack
        if (track.audioUri != null) {
            syncEngine.localPlayer.prepareAndPlayUri(track.id, Uri.parse(track.audioUri), startPositionMs)
        } else {
            val wav = SynthesizedMusicLibrary.getWavDataForTrack(track.id)
            syncEngine.localPlayer.prepareAndPlayWavBytes(track.id, wav, startPositionMs)
        }
        syncEngine.localPlayer.setVolume(_uiState.value.masterVolume)
        _uiState.update { it.copy(isPlaying = true) }

        syncEngine.localPlayer.onCompletionListener = {
            nextTrack()
        }
    }

    fun nextTrack() {
        val list = _uiState.value.playlist
        if (list.isEmpty()) return
        val currentIndex = list.indexOfFirst { it.id == _uiState.value.currentTrack.id }
        val nextIndex = if (currentIndex != -1 && currentIndex + 1 < list.size) currentIndex + 1 else 0
        playTrack(list[nextIndex])
    }

    fun previousTrack() {
        val list = _uiState.value.playlist
        if (list.isEmpty()) return
        val currentIndex = list.indexOfFirst { it.id == _uiState.value.currentTrack.id }
        val prevIndex = if (currentIndex > 0) currentIndex - 1 else list.size - 1
        playTrack(list[prevIndex])
    }

    fun seekTo(positionMs: Long) {
        _uiState.update { it.copy(currentPositionMs = positionMs) }
        if (_uiState.value.mode == AppMode.HOST) {
            syncEngine.localPlayer.seekTo(positionMs)
        } else {
            viewModelScope.launch {
                activeSyncClient?.sendControlAction("seek", positionMs.toString())
            }
        }
    }

    fun setMasterVolume(vol: Float) {
        val clamped = vol.coerceIn(0f, 1f)
        _uiState.update { it.copy(masterVolume = clamped) }
        if (_uiState.value.mode == AppMode.HOST) {
            syncEngine.localPlayer.setVolume(clamped)
        } else {
            viewModelScope.launch {
                activeSyncClient?.sendControlAction("master_volume", clamped.toString())
            }
        }
    }

    private fun handleRemoteControl(action: String, value: String?) {
        viewModelScope.launch(Dispatchers.Main) {
            when (action) {
                "play" -> playCurrentTrackOnHost(_uiState.value.currentPositionMs)
                "pause" -> {
                    syncEngine.localPlayer.pause()
                    _uiState.update { it.copy(isPlaying = false) }
                }
                "next" -> nextTrack()
                "prev" -> previousTrack()
                "seek" -> {
                    val pos = value?.toLongOrNull() ?: 0L
                    seekTo(pos)
                }
                "master_volume" -> {
                    val vol = value?.toFloatOrNull() ?: 1.0f
                    setMasterVolume(vol)
                }
                "select_track" -> {
                    val trackId = value
                    val track = _uiState.value.playlist.find { it.id == trackId }
                    if (track != null) playTrack(track)
                }
                "speaker_volume" -> {
                    val parts = value?.split(":")
                    if (parts != null && parts.size >= 2) {
                        val spkId = parts[0]
                        val vol = parts[1].toFloatOrNull() ?: 1.0f
                        setSpeakerVolume(spkId, vol)
                    }
                }
                "speaker_mute" -> {
                    if (value != null) {
                        toggleSpeakerMute(value)
                    }
                }
            }
        }
    }

    private fun updateSpeakerFromHeartbeat(speaker: DeviceSpeaker) {
        _uiState.update { state ->
            val list = state.connectedSpeakers.toMutableList()
            val idx = list.indexOfFirst { it.id == speaker.id }
            if (idx >= 0) {
                list[idx] = speaker
            } else {
                list.add(speaker)
            }
            state.copy(connectedSpeakers = list)
        }
    }

    fun setSpeakerVolume(speakerId: String, volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        _uiState.update { state ->
            val updated = state.connectedSpeakers.map {
                if (it.id == speakerId) it.copy(volume = clamped) else it
            }
            state.copy(connectedSpeakers = updated)
        }

        // 1. If in Host mode, send volume command directly to the target speaker's HTTP endpoint
        if (_uiState.value.mode == AppMode.HOST) {
            val targetSpeaker = _uiState.value.connectedSpeakers.find { it.id == speakerId }
            if (targetSpeaker != null) {
                // Update registered speaker model in host HTTP server
                httpServer?.registeredSpeakers?.get(speakerId)?.let {
                    httpServer?.registeredSpeakers?.put(speakerId, it.copy(volume = clamped))
                }
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        val client = OkHttpClient.Builder()
                            .connectTimeout(1, TimeUnit.SECONDS)
                            .readTimeout(1, TimeUnit.SECONDS)
                            .build()
                        val json = JSONObject().apply {
                            put("action", "set_volume")
                            put("value", clamped.toString())
                        }
                        val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                        val req = Request.Builder()
                            .url("http://${targetSpeaker.ip}:${targetSpeaker.port}/api/control")
                            .post(body)
                            .build()
                        client.newCall(req).execute().close()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to send volume to speaker $speakerId: ${e.message}")
                    }
                }
            }
        } else {
            // 2. If called from remote control on speaker/client side
            viewModelScope.launch(Dispatchers.IO) {
                activeSyncClient?.sendControlAction("speaker_volume", "${speakerId}:${clamped}")
            }
        }
    }

    fun toggleSpeakerMute(speakerId: String) {
        var isNowMuted = false
        _uiState.update { state ->
            val updated = state.connectedSpeakers.map {
                if (it.id == speakerId) {
                    isNowMuted = !it.isMuted
                    it.copy(isMuted = isNowMuted)
                } else it
            }
            state.copy(connectedSpeakers = updated)
        }

        // If in Host mode, propagate mute to speaker
        if (_uiState.value.mode == AppMode.HOST) {
            val targetSpeaker = _uiState.value.connectedSpeakers.find { it.id == speakerId }
            if (targetSpeaker != null) {
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        val effectiveVol = if (isNowMuted) 0f else targetSpeaker.volume
                        val client = OkHttpClient.Builder()
                            .connectTimeout(1, TimeUnit.SECONDS)
                            .readTimeout(1, TimeUnit.SECONDS)
                            .build()
                        val json = JSONObject().apply {
                            put("action", "set_volume")
                            put("value", effectiveVol.toString())
                        }
                        val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                        val req = Request.Builder()
                            .url("http://${targetSpeaker.ip}:${targetSpeaker.port}/api/control")
                            .post(body)
                            .build()
                        client.newCall(req).execute().close()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to send mute to speaker $speakerId: ${e.message}")
                    }
                }
            }
        } else {
            viewModelScope.launch(Dispatchers.IO) {
                activeSyncClient?.sendControlAction("speaker_mute", speakerId)
            }
        }
    }

    // ================= DEVICE ACCESS & HOST APPROVAL =================

    fun handleConnectionRequest(request: ConnectionRequest) {
        viewModelScope.launch(Dispatchers.Main) {
            _uiState.update { current ->
                if (current.blockedDeviceIds.contains(request.id) || current.blockedDeviceIds.contains(request.ip)) {
                    current
                } else if (current.approvedDeviceIds.contains(request.id) || current.approvedDeviceIds.contains(request.ip)) {
                    current
                } else {
                    val existing = current.pendingConnectionRequests.filterNot { it.id == request.id || it.ip == request.ip }
                    current.copy(
                        pendingConnectionRequests = existing + request,
                        message = "درخواست اتصال دستگاه جدید: «${request.deviceName}»"
                    )
                }
            }
        }
    }

    fun approveConnectionRequest(requestId: String) {
        _uiState.update { current ->
            val req = current.pendingConnectionRequests.find { it.id == requestId }
            val updatedPending = current.pendingConnectionRequests.filterNot { it.id == requestId }
            val updatedApproved = if (req != null) {
                current.approvedDeviceIds + req.id + req.ip
            } else {
                current.approvedDeviceIds + requestId
            }
            current.copy(
                pendingConnectionRequests = updatedPending,
                approvedDeviceIds = updatedApproved,
                message = "اتصال دستگاه «${req?.deviceName ?: requestId}» تایید شد"
            )
        }
    }

    fun approveAllConnectionRequests() {
        _uiState.update { current ->
            val newApproved = current.approvedDeviceIds.toMutableSet()
            current.pendingConnectionRequests.forEach { req ->
                newApproved.add(req.id)
                newApproved.add(req.ip)
            }
            current.copy(
                pendingConnectionRequests = emptyList(),
                approvedDeviceIds = newApproved,
                message = "همه درخواست‌های اتصال دستگاه‌ها تایید شدند"
            )
        }
    }

    fun rejectConnectionRequest(requestId: String) {
        _uiState.update { current ->
            val req = current.pendingConnectionRequests.find { it.id == requestId }
            val updatedPending = current.pendingConnectionRequests.filterNot { it.id == requestId }
            current.copy(
                pendingConnectionRequests = updatedPending,
                message = "درخواست اتصال دستگاه «${req?.deviceName ?: requestId}» رد شد"
            )
        }
    }

    fun blockDevice(deviceId: String) {
        _uiState.update { current ->
            val updatedPending = current.pendingConnectionRequests.filterNot { it.id == deviceId }
            val updatedApproved = current.approvedDeviceIds - deviceId
            val updatedSpeakers = current.connectedSpeakers.filterNot { it.id == deviceId || it.ip == deviceId }
            httpServer?.registeredSpeakers?.remove(deviceId)
            current.copy(
                pendingConnectionRequests = updatedPending,
                approvedDeviceIds = updatedApproved,
                blockedDeviceIds = current.blockedDeviceIds + deviceId,
                connectedSpeakers = updatedSpeakers,
                message = "دستگاه مسدود شد و ارتباط آن قطع گردید"
            )
        }
    }

    fun unblockDevice(deviceId: String) {
        _uiState.update { current ->
            current.copy(
                blockedDeviceIds = current.blockedDeviceIds - deviceId,
                message = "مسدودسازی دستگاه برداشته شد"
            )
        }
    }

    fun disconnectSpeaker(speakerId: String) {
        _uiState.update { current ->
            val updatedApproved = current.approvedDeviceIds - speakerId
            val updatedSpeakers = current.connectedSpeakers.filterNot { it.id == speakerId }
            httpServer?.registeredSpeakers?.remove(speakerId)
            current.copy(
                approvedDeviceIds = updatedApproved,
                connectedSpeakers = updatedSpeakers,
                message = "بلندگو قطع شد"
            )
        }
    }

    fun setHostApprovalRequired(required: Boolean) {
        _uiState.update {
            it.copy(
                isHostApprovalRequired = required,
                message = if (required) "تأیید دستی میزبان برای اتصال دستگاه‌ها فعال شد" else "اتصال خودکار دستگاه‌ها فعال شد"
            )
        }
    }

    private fun startPositionTicker() {
        positionTickerJob?.cancel()
        positionTickerJob = viewModelScope.launch {
            while (isActive) {
                if (_uiState.value.mode == AppMode.HOST && _uiState.value.isPlaying) {
                    val pos = syncEngine.localPlayer.getCurrentPosition()
                    val dur = syncEngine.localPlayer.getDuration()
                    _uiState.update {
                        it.copy(
                            currentPositionMs = pos,
                            durationMs = if (dur > 0) dur else it.durationMs
                        )
                    }
                }
                delay(300)
            }
        }
    }

    // ================= SPEAKER LOGIC =================

    private fun startSpeakerMode() {
        _uiState.update {
            it.copy(
                speakerStatus = SpeakerConnectionStatus.DISCONNECTED,
                discoveredHosts = emptyList(),
                message = "در حال کشف میزبان‌های مجاور با سرویس NSD و Wi-Fi..."
            )
        }

        // Start NSD & Wi-Fi Direct discovery
        nearbyDiscoveryManager.startDiscovery()

        // Start listening for real-time host microphone broadcasts
        liveMicReceiver.startListening()
    }

    private fun stopSpeakerMode() {
        speakerPlaylistSyncJob?.cancel()
        speakerPlaylistSyncJob = null
        nearbyDiscoveryManager.stopDiscovery()
        liveMicReceiver.stopListening()
        syncEngine.stopSpeakerSync()
        activeSyncClient = null
        _uiState.update {
            it.copy(
                speakerStatus = SpeakerConnectionStatus.DISCONNECTED,
                connectedHost = null,
                isLiveMicReceiving = false
            )
        }
    }

    fun refreshDiscovery() {
        if (_uiState.value.mode == AppMode.SPEAKER) {
            nearbyDiscoveryManager.stopDiscovery()
            nearbyDiscoveryManager.startDiscovery()
            _uiState.update { it.copy(message = "پویش مجدد شبکه برای کشف میزبان‌ها...") }
        }
    }

    fun connectToHost(host: HostBeacon) {
        viewModelScope.launch {
            val myIp = NetworkUtils.getLocalIpAddress()
            val deviceId = "device_${Build.MANUFACTURER}_${Build.MODEL}_${myIp}".replace(" ", "_")
            val deviceName = "بلندگوی ${Build.MODEL}"

            _uiState.update {
                it.copy(
                    speakerStatus = SpeakerConnectionStatus.CONNECTING,
                    connectedHost = host,
                    message = "در حال ارسال درخواست اتصال به ${host.hostName}..."
                )
            }

            val client = SyncClient(host.ip, host.port, deviceId)
            activeSyncClient = client

            // Send registration / heartbeat and verify approval
            val regSpeaker = DeviceSpeaker(
                id = deviceId,
                name = deviceName,
                ip = myIp,
                port = 8990,
                volume = _uiState.value.speakerVolume,
                latencyOffsetMs = _uiState.value.manualLatencyOffsetMs
            )

            val initialResult = client.registerOrHeartbeat(regSpeaker)

            when (initialResult) {
                SpeakerHeartbeatResult.REJECTED -> {
                    _uiState.update {
                        it.copy(
                            speakerStatus = SpeakerConnectionStatus.REJECTED,
                            message = "درخواست اتصال توسط میزبان رد شد یا دستگاه مسدود است."
                        )
                    }
                    return@launch
                }
                SpeakerHeartbeatResult.PENDING -> {
                    _uiState.update {
                        it.copy(
                            speakerStatus = SpeakerConnectionStatus.WAITING_APPROVAL,
                            message = "درخواست اتصال ارسال شد؛ در انتظار تایید توسط میزبان..."
                        )
                    }

                    // Poll while waiting for host approval
                    var isApproved = false
                    while (activeSyncClient == client && _uiState.value.speakerStatus == SpeakerConnectionStatus.WAITING_APPROVAL) {
                        delay(1500)
                        val pollResult = client.registerOrHeartbeat(regSpeaker)
                        if (pollResult == SpeakerHeartbeatResult.APPROVED) {
                            isApproved = true
                            break
                        } else if (pollResult == SpeakerHeartbeatResult.REJECTED) {
                            _uiState.update {
                                it.copy(
                                    speakerStatus = SpeakerConnectionStatus.REJECTED,
                                    message = "درخواست اتصال توسط میزبان رد شد."
                                )
                            }
                            return@launch
                        }
                    }

                    if (!isApproved) {
                        return@launch
                    }
                }
                SpeakerHeartbeatResult.APPROVED, SpeakerHeartbeatResult.FAILED -> {
                    // Proceed to clock sync
                }
            }

            _uiState.update { it.copy(speakerStatus = SpeakerConnectionStatus.SYNCING_CLOCK) }
            val syncSuccess = client.synchronizeClock()

            if (syncSuccess) {
                _uiState.update {
                    it.copy(
                        speakerStatus = SpeakerConnectionStatus.CONNECTED,
                        clockRttMs = client.roundTripDelayMs,
                        message = "اتصال و همگام‌سازی صدا با تأیید میزبان برقرار شد!"
                    )
                }

                // Register with Host final heartbeat
                client.sendHeartbeat(regSpeaker)

                // Start audio sync loop (notifies updates through syncEngine.onStateUpdated)
                syncEngine.startSpeakerSync(client)

                // Start real-time shared playlist sync loop
                startSpeakerPlaylistSyncLoop(client)
            } else {
                _uiState.update {
                    it.copy(
                        speakerStatus = SpeakerConnectionStatus.ERROR,
                        message = "خطا در اتصال به میزبان. لطفا بررسی کنید هر دو دستگاه به یک وای‌فای وصل باشند."
                    )
                }
            }
        }
    }

    fun connectToHostManual(ip: String, port: Int = 8990) {
        val beacon = HostBeacon(
            hostName = "میزبان ($ip)",
            ip = ip.trim(),
            port = port,
            currentTrackTitle = "در حال همگام‌سازی",
            discoveryType = "آدرس دستی"
        )
        connectToHost(beacon)
    }

    fun disconnectSpeaker() {
        stopSpeakerMode()
        startSpeakerMode()
    }

    fun setManualLatencyOffset(offsetMs: Long) {
        syncEngine.setManualOffset(offsetMs)
        _uiState.update { it.copy(manualLatencyOffsetMs = offsetMs) }
        viewModelScope.launch {
            activeSyncClient?.let { client ->
                client.sendHeartbeat(
                    DeviceSpeaker(
                        id = NetworkUtils.getLocalIpAddress(),
                        name = "بلندگوی ${Build.MODEL}",
                        ip = NetworkUtils.getLocalIpAddress(),
                        port = 8990,
                        volume = _uiState.value.speakerVolume,
                        latencyOffsetMs = offsetMs
                    )
                )
            }
        }
    }

    fun setLocalSpeakerVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        syncEngine.localPlayer.setVolume(clamped)
        _uiState.update { it.copy(speakerVolume = clamped) }
    }

    // ================= LIVE MICROPHONE CONTROLS =================

    fun startLiveMic(): Boolean {
        val success = liveMicBroadcaster.startBroadcasting()
        if (success) {
            _uiState.update {
                it.copy(
                    isLiveMicBroadcasting = true,
                    message = "پخش زنده میکروفن فعال شد! صدای شما روی تمام بلندگوها پخش می‌شود."
                )
            }
            if (_uiState.value.isPlaying) {
                syncEngine.localPlayer.duckForLiveMic()
            }
        } else {
            _uiState.update {
                it.copy(message = "خطا در اتصال به میکروفن. لطفا مجوز ضبط صدا را بررسی کنید.")
            }
        }
        return success
    }

    fun stopLiveMic() {
        liveMicBroadcaster.stopBroadcasting()
        _uiState.update {
            it.copy(
                isLiveMicBroadcasting = false,
                liveMicLevel = 0f,
                message = "پخش زنده میکروفن متوقف شد"
            )
        }
        syncEngine.localPlayer.restoreAfterLiveMic()
    }

    fun toggleLiveMic(): Boolean {
        return if (_uiState.value.isLiveMicBroadcasting) {
            stopLiveMic()
            false
        } else {
            startLiveMic()
        }
    }

    fun refreshNearbyDiscovery() {
        if (_uiState.value.mode == AppMode.SPEAKER) {
            nearbyDiscoveryManager.startDiscovery()
            nearbyDiscoveryManager.discoverWifiDirectPeers()
            _uiState.update { it.copy(message = "در حال پویش مجدد سرویس‌های NSD و Wi-Fi...") }
        }
    }

    // ================= SHARED PLAYLIST LOGIC & REAL-TIME SYNC =================

    private fun startSpeakerPlaylistSyncLoop(client: SyncClient) {
        speakerPlaylistSyncJob?.cancel()
        speakerPlaylistSyncJob = viewModelScope.launch(Dispatchers.IO) {
            // Initial fetch immediately upon connection
            refreshSpeakerPlaylist(client)

            // Periodic sync polling to ensure all clients remain synchronized
            while (isActive && activeSyncClient == client && _uiState.value.mode == AppMode.SPEAKER) {
                delay(1500)
                try {
                    refreshSpeakerPlaylist(client)
                } catch (e: Exception) {
                    Log.w(TAG, "Speaker playlist periodic sync error: ${e.message}")
                }
            }
        }
    }

    private suspend fun refreshSpeakerPlaylist(client: SyncClient) {
        try {
            val hostPlaylist = client.fetchPlaylist()
            if (hostPlaylist.isNotEmpty()) {
                _uiState.update { current ->
                    val curTrack = if (hostPlaylist.any { it.id == current.currentTrack.id }) {
                        current.currentTrack
                    } else {
                        hostPlaylist.firstOrNull() ?: current.currentTrack
                    }
                    current.copy(playlist = hostPlaylist, currentTrack = curTrack)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "refreshSpeakerPlaylist error: ${e.message}")
        }
    }

    fun addTrackToPlaylist(track: Track) {
        if (_uiState.value.mode == AppMode.HOST) {
            viewModelScope.launch(Dispatchers.IO) {
                val count = playlistDao.getAllTracksSnapshot().size
                playlistDao.insertTrack(
                    TrackEntity(
                        id = track.id,
                        title = track.title,
                        artist = track.artist,
                        durationMs = track.durationMs,
                        genre = track.genre,
                        audioUri = track.audioUri,
                        addedBy = track.addedBy,
                        votes = track.votes,
                        orderIndex = count
                    )
                )
                _uiState.update { it.copy(message = "قطعه «${track.title}» به پلی‌لیست مشترک اضافه شد") }
            }
        } else {
            // Speaker adds track to Host and instantly synchronizes
            viewModelScope.launch(Dispatchers.IO) {
                val success = activeSyncClient?.addTrackToPlaylist(track) ?: false
                if (success) {
                    activeSyncClient?.let { refreshSpeakerPlaylist(it) }
                    _uiState.update { it.copy(message = "قطعه «${track.title}» به صورت بی‌درنگ به پلی‌لیست مشترک اضافه شد") }
                } else {
                    _uiState.update { it.copy(message = "خطا در ارسال قطعه به میزبان (بررسی وضعیت اتصال)") }
                }
            }
        }
    }

    fun upvoteTrack(trackId: String) {
        if (_uiState.value.mode == AppMode.HOST) {
            viewModelScope.launch(Dispatchers.IO) {
                playlistDao.upvoteTrack(trackId)
            }
        } else {
            viewModelScope.launch(Dispatchers.IO) {
                val success = activeSyncClient?.upvoteTrack(trackId) ?: false
                if (success) {
                    activeSyncClient?.let { refreshSpeakerPlaylist(it) }
                }
            }
        }
    }

    fun removeTrackFromPlaylist(trackId: String) {
        if (_uiState.value.mode == AppMode.HOST) {
            viewModelScope.launch(Dispatchers.IO) {
                playlistDao.deleteTrack(trackId)
                _uiState.update { it.copy(message = "قطعه از پلی‌لیست مشترک حذف شد") }
            }
        } else {
            // Speaker removes track from Host and instantly synchronizes
            viewModelScope.launch(Dispatchers.IO) {
                val success = activeSyncClient?.removeTrackFromPlaylist(trackId) ?: false
                if (success) {
                    activeSyncClient?.let { refreshSpeakerPlaylist(it) }
                    _uiState.update { it.copy(message = "قطعه از پلی‌لیست مشترک حذف گردید") }
                } else {
                    _uiState.update { it.copy(message = "خطا در حذف قطعه از میزبان") }
                }
            }
        }
    }

    fun addLocalFileTrack(title: String, artist: String, uri: Uri, durationMs: Long) {
        val newTrack = Track(
            id = "custom_${System.currentTimeMillis()}",
            title = title,
            artist = artist,
            durationMs = if (durationMs > 0) durationMs else 180000L,
            genre = "موسیقی محلی",
            audioUri = uri.toString(),
            addedBy = "حافظه دستگاه",
            votes = 1
        )
        addTrackToPlaylist(newTrack)
    }

    // ================= STORAGE FILE MANAGER & SEARCH =================

    fun scanStorageAudio() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isStorageScanning = true) }
            val files = StorageAudioScanner.queryInternalStorageAudio(getApplication())
            _uiState.update {
                it.copy(
                    storageAudioFiles = files,
                    isStorageScanning = false
                )
            }
        }
    }

    fun setStorageSearchQuery(query: String) {
        _uiState.update { it.copy(storageSearchQuery = query) }
    }

    fun setStorageFolderFilter(folder: String) {
        _uiState.update { it.copy(selectedStorageFolder = folder) }
    }

    fun toggleStorageFileSelection(fileId: Long) {
        _uiState.update { current ->
            val set = current.selectedStorageFileIds.toMutableSet()
            if (set.contains(fileId)) {
                set.remove(fileId)
            } else {
                set.add(fileId)
            }
            current.copy(selectedStorageFileIds = set)
        }
    }

    fun selectAllStorageFiles(fileIds: List<Long>) {
        _uiState.update { it.copy(selectedStorageFileIds = fileIds.toSet()) }
    }

    fun clearStorageFileSelection() {
        _uiState.update { it.copy(selectedStorageFileIds = emptySet()) }
    }

    fun addSingleStorageFileToPlaylist(file: StorageAudioFile) {
        val track = file.toTrack()
        addTrackToPlaylist(track)
        _uiState.update {
            it.copy(message = "آهنگ «${track.title}» به پلی‌لیست افزوده شد")
        }
    }

    fun addSelectedStorageFilesToPlaylist() {
        val selectedIds = _uiState.value.selectedStorageFileIds
        if (selectedIds.isEmpty()) return

        val filesToAdd = _uiState.value.storageAudioFiles.filter { selectedIds.contains(it.id) }
        viewModelScope.launch(Dispatchers.IO) {
            filesToAdd.forEach { file ->
                val track = file.toTrack()
                playlistDao.insertTrack(
                    TrackEntity(
                        id = track.id,
                        title = track.title,
                        artist = track.artist,
                        durationMs = track.durationMs,
                        genre = track.genre,
                        audioUri = track.audioUri,
                        addedBy = track.addedBy,
                        votes = track.votes
                    )
                )
            }
            _uiState.update {
                it.copy(
                    selectedStorageFileIds = emptySet(),
                    message = "${PersianFormatters.toPersianDigits(filesToAdd.size.toString())} آهنگ به پلی‌لیست افزوده شد"
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            getApplication<Application>().unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {
            // ignore
        }
        stopHostMode()
        stopSpeakerMode()
        nearbyDiscoveryManager.release()
        liveMicBroadcaster.stopBroadcasting()
        liveMicReceiver.stopListening()
        syncEngine.release()
    }
}
