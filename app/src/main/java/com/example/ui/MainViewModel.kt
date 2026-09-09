package com.example.ui

import android.app.Application
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioSyncEngine
import com.example.audio.SynthesizedMusicLibrary
import com.example.data.AppDatabase
import com.example.data.TrackEntity
import com.example.model.DeviceSpeaker
import com.example.model.HostBeacon
import com.example.model.SyncPlaybackState
import com.example.model.Track
import com.example.network.AudioHttpServer
import com.example.network.NetworkUtils
import com.example.network.SyncClient
import com.example.network.UdpBeaconBroadcaster
import com.example.network.UdpBeaconListener
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

    // Connected speakers (on Host)
    val connectedSpeakers: List<DeviceSpeaker> = emptyList(),

    // Discovered hosts (on Speaker)
    val discoveredHosts: List<HostBeacon> = emptyList(),
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

    // Host network services
    private var httpServer: AudioHttpServer? = null
    private var beaconBroadcaster: UdpBeaconBroadcaster? = null

    // Speaker network services
    private var beaconListener: UdpBeaconListener? = null
    private var activeSyncClient: SyncClient? = null

    private var positionTickerJob: Job? = null
    private var speakerPollJob: Job? = null

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
                    masterVolume = _uiState.value.masterVolume
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

        beaconBroadcaster = UdpBeaconBroadcaster(
            hostName = hostName,
            getPort = { 8990 },
            getCurrentTrackTitle = { _uiState.value.currentTrack.title }
        )
        beaconBroadcaster?.start()

        _uiState.update {
            it.copy(
                localIp = ip,
                isHostServerRunning = true,
                message = "سرور میزبان روی $ip:8990 فعال شد"
            )
        }

        startPositionTicker()
    }

    private fun stopHostMode() {
        beaconBroadcaster?.stop()
        beaconBroadcaster = null
        httpServer?.stop()
        httpServer = null
        syncEngine.localPlayer.pause()
        _uiState.update { it.copy(isHostServerRunning = false, isPlaying = false) }
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
                message = "در حال اسکن شبکه وای‌فای برای یافتن میزبان..."
            )
        }

        beaconListener = UdpBeaconListener(getApplication()) { beacon ->
            viewModelScope.launch(Dispatchers.Main) {
                _uiState.update { state ->
                    val list = state.discoveredHosts.toMutableList()
                    val idx = list.indexOfFirst { it.ip == beacon.ip && it.port == beacon.port }
                    if (idx >= 0) {
                        list[idx] = beacon
                    } else {
                        list.add(beacon)
                    }
                    state.copy(discoveredHosts = list)
                }
            }
        }
        beaconListener?.start()
    }

    private fun stopSpeakerMode() {
        beaconListener?.stop()
        beaconListener = null
        speakerPollJob?.cancel()
        speakerPollJob = null
        syncEngine.stopSpeakerSync()
        activeSyncClient = null
        _uiState.update {
            it.copy(
                speakerStatus = SpeakerConnectionStatus.DISCONNECTED,
                connectedHost = null
            )
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

                // Start audio sync loop
                syncEngine.startSpeakerSync(client)
                startSpeakerPolling(client)
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
            currentTrackTitle = "در حال همگام‌سازی"
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

    private fun startSpeakerPolling(client: SyncClient) {
        speakerPollJob?.cancel()
        speakerPollJob = viewModelScope.launch {
            while (isActive) {
                try {
                    val state = client.fetchState()
                    if (state != null) {
                        _uiState.update {
                            it.copy(
                                isPlaying = state.isPlaying,
                                currentPositionMs = state.positionMs,
                                durationMs = state.durationMs,
                                currentTrack = it.currentTrack.copy(
                                    id = state.trackId,
                                    title = state.trackTitle,
                                    artist = state.artist
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Speaker poll error: ${e.message}")
                }
                delay(400)
            }
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
        syncEngine.release()
    }
}
