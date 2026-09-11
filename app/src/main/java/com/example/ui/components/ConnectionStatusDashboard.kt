package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeviceHub
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.DeviceSpeaker
import com.example.ui.PersianFormatters
import kotlin.math.abs

/**
 * Connection Status Dashboard:
 * Displays connected devices with icons representing device type, Wi-Fi signal strength
 * (calculated from ping/RTT and heartbeat latency), and individual sync offsets for each client.
 */
@Composable
fun ConnectionStatusDashboard(
    speakers: List<DeviceSpeaker>,
    modifier: Modifier = Modifier,
    title: String = "داشبورد وضعیت اتصال و همگام‌سازی",
    isHostView: Boolean = true
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("connection_status_dashboard"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Dashboard Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.NetworkCheck,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = title,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "پایش زنده سیگنال شبکه و آفست صوتی دستگاه‌ها",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (speakers.isNotEmpty()) Color(0xFF10B981).copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (speakers.isNotEmpty()) Color(0xFF10B981) else Color.Gray)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${PersianFormatters.toPersianDigits(speakers.size.toString())} دستگاه فعال",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (speakers.isNotEmpty()) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (speakers.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                        .padding(vertical = 20.dp, horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Devices,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "هیچ دستگاه بلندگویی متصل نیست. در انتظار اتصال کلاینت‌ها...",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    speakers.forEachIndexed { index, speaker ->
                        DeviceStatusRow(speaker = speaker, index = index)
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceStatusRow(
    speaker: DeviceSpeaker,
    index: Int
) {
    // Determine Signal Quality based on RTT
    // < 20ms: عالی (Strong), 20-60ms: خوب (Good), 61-120ms: متوسط (Fair), > 120ms: ضعیف (Poor)
    val rtt = speaker.rttMs
    val (signalLabel, signalColor, signalBars) = when {
        rtt <= 0L -> Triple("سیگنال عالی", Color(0xFF10B981), 4)
        rtt < 25L -> Triple("سیگنال عالی", Color(0xFF10B981), 4)
        rtt < 65L -> Triple("سیگنال خوب", Color(0xFF3B82F6), 3)
        rtt < 130L -> Triple("سیگنال متوسط", Color(0xFFF59E0B), 2)
        else -> Triple("سیگنال ضعیف", Color(0xFFEF4444), 1)
    }

    // Determine device icon
    val deviceIcon: ImageVector = when {
        speaker.name.contains("بلندگو", ignoreCase = true) || speaker.name.contains("Speaker", ignoreCase = true) -> Icons.Default.Speaker
        else -> Icons.Default.PhoneAndroid
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("device_status_row_${speaker.id}")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Top row: Device Icon, Name, IP, Signal Strength indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = deviceIcon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column {
                        Text(
                            text = speaker.name,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                        Text(
                            text = "آی‌پی: ${speaker.ip}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Signal Strength Visual Indicator
                Column(horizontalAlignment = Alignment.End) {
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        for (bar in 1..4) {
                            val barHeight = (bar * 3.5).dp
                            val isFilled = bar <= signalBars
                            Box(
                                modifier = Modifier
                                    .width(3.5.dp)
                                    .height(barHeight)
                                    .clip(RoundedCornerShape(1.dp))
                                    .background(if (isFilled) signalColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f))
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "$signalLabel (${PersianFormatters.toPersianDigits(if (rtt > 0) rtt.toString() else "۱۰")} ms)",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = signalColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Bottom row: Individual Sync Offset & Latency metrics
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Sync Offset badge
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Sync,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    val offset = speaker.latencyOffsetMs
                    val offsetText = when {
                        offset > 0 -> "+${PersianFormatters.toPersianDigits(offset.toString())} ms"
                        offset < 0 -> "${PersianFormatters.toPersianDigits(offset.toString())} ms"
                        else -> "۰ ms (دقیق)"
                    }
                    Text(
                        text = "آفست همگام‌سازی: $offsetText",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (offset != 0L) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface
                    )
                }

                // Volume status indicator
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (speaker.isMuted) "بی‌صدا" else "ولوم: ${PersianFormatters.toPersianDigits((speaker.volume * 100).toInt().toString())}٪",
                        fontSize = 11.sp,
                        color = if (speaker.isMuted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
