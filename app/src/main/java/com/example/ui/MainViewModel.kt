package com.example.ui

import android.app.Application
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioSyncEngine
import com.example.audio.LiveMicBroadcaster
import com.example.audio.LiveMicReceiver
import com.example.audio.SynthesizedMusicLibrary
import com.example.data.AppDatabase
import com.example.data.TrackEntity
import com.example.model.DeviceSpeaker
import com.example.model.HostBeacon
import com.example.model.SyncPlaybackState
import com.example.model.Track
import com.example.network.AudioHttpServer
import com.example.network.NearbyDiscoveryManager
import com.example.network.NetworkUtils
import com.example.network.SyncClient
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
                _uiState.update { state ->
                    val cur = if (tracks.any { it.id == state.currentTrack.id }) state.currentTrack else tracks.firstOrNull() ?: state.currentTrack
                    state.copy(playlist = tracks, currentTrack = cur)
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
        }

        // Start in Host mode by default
        startHostMode()

        // Listen for sync drift from syncEngine
        viewModelScope.launch {
            syncEngine.syncDriftMs.collect { drift ->
                _uiState.update { it.copy(syncDriftMs = drift) }
            }
        }
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
                    isLiveMicActive = _uiState.value.isLiveMicBroadcasting
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
            onSpeakerHeartbeat = { speaker ->
                updateSpeakerFromHeartbeat(speaker)
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
        _uiState.update { state ->
            val updated = state.connectedSpeakers.map {
                if (it.id == speakerId) it.copy(volume = volume) else it
            }
            state.copy(connectedSpeakers = updated)
        }
    }

    fun toggleSpeakerMute(speakerId: String) {
        _uiState.update { state ->
            val updated = state.connectedSpeakers.map {
                if (it.id == speakerId) it.copy(isMuted = !it.isMuted) else it
            }
            state.copy(connectedSpeakers = updated)
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
            _uiState.update {
                it.copy(
                    speakerStatus = SpeakerConnectionStatus.CONNECTING,
                    connectedHost = host,
                    message = "در حال اتصال به ${host.hostName}..."
                )
            }

            val client = SyncClient(host.ip, host.port)
            activeSyncClient = client

            _uiState.update { it.copy(speakerStatus = SpeakerConnectionStatus.SYNCING_CLOCK) }
            val syncSuccess = client.synchronizeClock()

            if (syncSuccess) {
                _uiState.update {
                    it.copy(
                        speakerStatus = SpeakerConnectionStatus.CONNECTED,
                        clockRttMs = client.roundTripDelayMs,
                        message = "اتصال و همگام‌سازی صدا با موفقیت برقرار شد!"
                    )
                }

                // Register with Host
                val deviceName = "بلندگوی ${Build.MODEL}"
                client.sendHeartbeat(
                    DeviceSpeaker(
                        id = NetworkUtils.getLocalIpAddress(),
                        name = deviceName,
                        ip = NetworkUtils.getLocalIpAddress(),
                        port = 8990,
                        volume = _uiState.value.speakerVolume,
                        latencyOffsetMs = _uiState.value.manualLatencyOffsetMs
                    )
                )

                // Start audio sync loop (notifies updates through syncEngine.onStateUpdated)
                syncEngine.startSpeakerSync(client)
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

    // ================= SHARED PLAYLIST LOGIC =================

    fun addTrackToPlaylist(track: Track) {
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

        // If speaker, notify host
        if (_uiState.value.mode == AppMode.SPEAKER) {
            viewModelScope.launch {
                activeSyncClient?.addTrackToPlaylist(track)
            }
        }
    }

    fun upvoteTrack(trackId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            playlistDao.upvoteTrack(trackId)
        }
    }

    fun removeTrackFromPlaylist(trackId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            playlistDao.deleteTrack(trackId)
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

    override fun onCleared() {
        super.onCleared()
        stopHostMode()
        stopSpeakerMode()
        nearbyDiscoveryManager.release()
        liveMicBroadcaster.stopBroadcasting()
        liveMicReceiver.stopListening()
        syncEngine.release()
    }
}
