package com.example.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.components.AppManualDialog
import com.example.ui.screens.HostScreen
import com.example.ui.screens.RemoteControlScreen
import com.example.ui.screens.SharedPlaylistScreen
import com.example.ui.screens.SpeakerScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HamSedaApp(
    viewModel: MainViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showManualDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        state.message?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearMessage()
        }
    }

    // Force Right-to-Left (RTL) layout for Persian language
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                HamSedaTopBar(
                    currentMode = state.mode,
                    localIp = state.localIp,
                    isBatterySaverEnabled = state.isBatterySaverEnabled,
                    isLowBattery = state.isSystemLowBattery,
                    batteryPercent = state.batteryPercent,
                    isCharging = state.isBatteryCharging,
                    onModeSelected = { mode -> viewModel.setAppMode(mode) },
                    onToggleBatterySaver = { viewModel.toggleBatterySaver() },
                    onOpenManual = { showManualDialog = true }
                )
            },
            bottomBar = {
                HamSedaBottomNav(
                    currentTab = state.currentTab,
                    onTabSelected = { tab -> viewModel.setTab(tab) },
                    playlistSize = state.playlist.size
                )
            },
            snackbarHost = {
                SnackbarHost(hostState = snackbarHostState) { data ->
                    Snackbar(
                        snackbarData = data,
                        containerColor = MaterialTheme.colorScheme.inverseSurface,
                        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                AnimatedContent(
                    targetState = state.currentTab,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "tab_switch"
                ) { targetTab ->
                    when (targetTab) {
                        NavigationTab.PLAYER -> {
                            if (state.mode == AppMode.HOST) {
                                HostScreen(
                                    state = state,
                                    onTogglePlayPause = { viewModel.togglePlayPause() },
                                    onNextTrack = { viewModel.nextTrack() },
                                    onPrevTrack = { viewModel.previousTrack() },
                                    onSeek = { pos -> viewModel.seekTo(pos) },
                                    onMasterVolumeChange = { vol -> viewModel.setMasterVolume(vol) },
                                    onSpeakerVolumeChange = { id, vol -> viewModel.setSpeakerVolume(id, vol) },
                                    onToggleSpeakerMute = { id -> viewModel.toggleSpeakerMute(id) },
                                    onToggleLiveMic = { viewModel.toggleLiveMic() },
                                    onToggleBatterySaver = { viewModel.toggleBatterySaver() },
                                    onApproveRequest = { id -> viewModel.approveConnectionRequest(id) },
                                    onRejectRequest = { id -> viewModel.rejectConnectionRequest(id) },
                                    onApproveAllRequests = { viewModel.approveAllConnectionRequests() },
                                    onBlockDevice = { id -> viewModel.blockDevice(id) },
                                    onUnblockDevice = { id -> viewModel.unblockDevice(id) },
                                    onDisconnectSpeaker = { id -> viewModel.disconnectSpeaker(id) },
                                    onToggleApprovalRequired = { req -> viewModel.setHostApprovalRequired(req) },
                                    onOpenManual = { showManualDialog = true }
                                )
                            } else {
                                SpeakerScreen(
                                    state = state,
                                    onConnectToHost = { host -> viewModel.connectToHost(host) },
                                    onManualConnect = { ip -> viewModel.connectToHostManual(ip) },
                                    onDisconnect = { viewModel.disconnectSpeaker() },
                                    onManualOffsetChange = { offset -> viewModel.setManualLatencyOffset(offset) },
                                    onSpeakerVolumeChange = { vol -> viewModel.setLocalSpeakerVolume(vol) },
                                    onRefreshDiscovery = { viewModel.refreshDiscovery() },
                                    onToggleBatterySaver = { viewModel.toggleBatterySaver() },
                                    onOpenManual = { showManualDialog = true }
                                )
                            }
                        }

                        NavigationTab.PLAYLIST -> {
                            SharedPlaylistScreen(
                                state = state,
                                onTrackSelect = { track -> viewModel.playTrack(track) },
                                onUpvote = { id -> viewModel.upvoteTrack(id) },
                                onDeleteTrack = { id -> viewModel.removeTrackFromPlaylist(id) },
                                onAddTrack = { track -> viewModel.addTrackToPlaylist(track) },
                                onAddLocalFileTrack = { title, artist, uri, dur ->
                                    viewModel.addLocalFileTrack(title, artist, uri, dur)
                                },
                                onSearchStorageQueryChange = { q -> viewModel.setStorageSearchQuery(q) },
                                onFolderChange = { f -> viewModel.setStorageFolderFilter(f) },
                                onToggleFileSelection = { id -> viewModel.toggleStorageFileSelection(id) },
                                onSelectAllFiles = { ids -> viewModel.selectAllStorageFiles(ids) },
                                onClearFileSelection = { viewModel.clearStorageFileSelection() },
                                onAddSingleStorageFile = { file -> viewModel.addSingleStorageFileToPlaylist(file) },
                                onAddSelectedStorageFiles = { viewModel.addSelectedStorageFilesToPlaylist() },
                                onRefreshStorageScan = { viewModel.scanStorageAudio() }
                            )
                        }

                        NavigationTab.REMOTE -> {
                            RemoteControlScreen(
                                state = state,
                                onTogglePlayPause = { viewModel.togglePlayPause() },
                                onNextTrack = { viewModel.nextTrack() },
                                onPrevTrack = { viewModel.previousTrack() },
                                onSeek = { pos -> viewModel.seekTo(pos) },
                                onMasterVolumeChange = { vol -> viewModel.setMasterVolume(vol) },
                                onSpeakerVolumeChange = { id, vol -> viewModel.setSpeakerVolume(id, vol) },
                                onToggleSpeakerMute = { id -> viewModel.toggleSpeakerMute(id) }
                            )
                        }

                        NavigationTab.SPEAKERS -> {
                            // Can be accessed via Host or Remote tab
                        }
                    }
                }
            }

            if (showManualDialog) {
                AppManualDialog(onDismiss = { showManualDialog = false })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HamSedaTopBar(
    currentMode: AppMode,
    localIp: String,
    isBatterySaverEnabled: Boolean,
    isLowBattery: Boolean,
    batteryPercent: Int,
    isCharging: Boolean,
    onModeSelected: (AppMode) -> Unit,
    onToggleBatterySaver: () -> Unit,
    onOpenManual: () -> Unit = {}
) {
    TopAppBar(
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // App Logo & Persian Title
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "هم‌صدا",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                // Right side: Battery Saver Quick Toggle, Manual button & Mode Selector Pill
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // In-App Manual button
                    IconButton(
                        onClick = onOpenManual,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("btn_topbar_manual")
                    ) {
                        Icon(
                            imageVector = Icons.Default.HelpOutline,
                            contentDescription = "راهنمای جامع نرم‌افزار هم‌صدا",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    // Battery Saver quick badge
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (isBatterySaverEnabled) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
                        } else if (isLowBattery && !isCharging) {
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        },
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable { onToggleBatterySaver() }
                            .testTag("top_bar_battery_pill")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = when {
                                    isCharging -> Icons.Default.BatteryChargingFull
                                    isLowBattery -> Icons.Default.BatteryAlert
                                    else -> Icons.Default.BatteryFull
                                },
                                contentDescription = "باتری",
                                tint = when {
                                    isBatterySaverEnabled -> MaterialTheme.colorScheme.primary
                                    isLowBattery && !isCharging -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "${PersianFormatters.toPersianDigits(batteryPercent.toString())}٪",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = when {
                                    isBatterySaverEnabled -> MaterialTheme.colorScheme.onPrimaryContainer
                                    isLowBattery && !isCharging -> MaterialTheme.colorScheme.onErrorContainer
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                            if (isBatterySaverEnabled) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary)
                                )
                            }
                        }
                    }

                    // Minimalist Mode Selector Pill (میزبان vs بلندگو)
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.padding(end = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ModePillItem(
                                title = "میزبان",
                                isSelected = currentMode == AppMode.HOST,
                                onClick = { onModeSelected(AppMode.HOST) },
                                testTag = "pill_mode_host"
                            )
                            ModePillItem(
                                title = "بلندگو",
                                isSelected = currentMode == AppMode.SPEAKER,
                                onClick = { onModeSelected(AppMode.SPEAKER) },
                                testTag = "pill_mode_speaker"
                            )
                        }
                    }
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background
        )
    )
}

@Composable
private fun ModePillItem(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (isSelected) MaterialTheme.colorScheme.primary
                else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun HamSedaBottomNav(
    currentTab: NavigationTab,
    onTabSelected: (NavigationTab) -> Unit,
    playlistSize: Int
) {
    NavigationBar(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        NavigationBarItem(
            selected = currentTab == NavigationTab.PLAYER,
            onClick = { onTabSelected(NavigationTab.PLAYER) },
            icon = {
                Icon(
                    imageVector = Icons.Default.PlayCircle,
                    contentDescription = "پخش‌کننده"
                )
            },
            label = { Text(text = "پخش همزمان", fontSize = 11.sp) },
            colors = NavigationBarItemDefaults.colors(
                indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                selectedIconColor = MaterialTheme.colorScheme.primary
            ),
            modifier = Modifier.testTag("nav_tab_player")
        )

        NavigationBarItem(
            selected = currentTab == NavigationTab.PLAYLIST,
            onClick = { onTabSelected(NavigationTab.PLAYLIST) },
            icon = {
                BadgedBox(
                    badge = {
                        if (playlistSize > 0) {
                            Badge {
                                Text(text = PersianFormatters.toPersianDigits(playlistSize.toString()))
                            }
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.QueueMusic,
                        contentDescription = "پلی‌لیست مشترک"
                    )
                }
            },
            label = { Text(text = "پلی‌لیست مشترک", fontSize = 11.sp) },
            colors = NavigationBarItemDefaults.colors(
                indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                selectedIconColor = MaterialTheme.colorScheme.primary
            ),
            modifier = Modifier.testTag("nav_tab_playlist")
        )

        NavigationBarItem(
            selected = currentTab == NavigationTab.REMOTE,
            onClick = { onTabSelected(NavigationTab.REMOTE) },
            icon = {
                Icon(
                    imageVector = Icons.Default.SettingsRemote,
                    contentDescription = "کنترل از راه دور"
                )
            },
            label = { Text(text = "کنترل از راه دور", fontSize = 11.sp) },
            colors = NavigationBarItemDefaults.colors(
                indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                selectedIconColor = MaterialTheme.colorScheme.primary
            ),
            modifier = Modifier.testTag("nav_tab_remote")
        )
    }
}
