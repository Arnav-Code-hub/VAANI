package com.bithead.shelter.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.ripple
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bithead.shelter.ui.theme.*
import java.util.Locale

data class EvidencePlaybackState(
    val evidenceId: Long? = null,
    val isPreparing: Boolean = false,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L
)

/** Big center action button with a soft breathing/pulsing halo behind it. */
@Composable
fun PulseTriggerButton(
    isEmergency: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = if (isEmergency) ShelterDanger else ShelterSafe
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isEmergency) 1100 else 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseValue"
    )

    Box(modifier = modifier.size(220.dp), contentAlignment = Alignment.Center) {
        // Expanding ring
        Box(
            modifier = Modifier
                .size(150.dp + (60.dp * pulse))
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.16f * (1f - pulse)))
        )
        Box(
            modifier = Modifier
                .size(150.dp + (30.dp * pulse))
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.22f * (1f - pulse)))
        )
        // Core button
        Box(
            modifier = Modifier
                .size(150.dp)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(accent, accent.copy(alpha = 0.75f))))
                .border(3.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                .clickableNoRipple(onClick),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = if (isEmergency) Icons.Filled.Warning else Icons.Filled.Security,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(38.dp)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (isEmergency) "STOP &\nSEAL" else "ACTIVATE",
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 15.sp,
                    lineHeight = 17.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

// Small helper so the circular button gets a clean, circle-bounded ripple.
@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return this.clickable(
        interactionSource = interactionSource,
        indication = ripple(bounded = true, color = Color.White),
        onClick = onClick
    )
}

/** Animated "listening" waveform — a few bars bouncing at slightly different speeds. */
@Composable
fun ListeningBars(active: Boolean, color: Color, modifier: Modifier = Modifier) {
    val bars = listOf(520, 380, 620, 440, 500)
    Row(modifier = modifier, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        bars.forEachIndexed { i, duration ->
            val height = if (active) {
                val transition = rememberInfiniteTransition(label = "bar$i")
                val animatedHeight by transition.animateFloat(
                    initialValue = 6f,
                    targetValue = 26f,
                    animationSpec = infiniteRepeatable(tween(duration, easing = LinearEasing), RepeatMode.Reverse),
                    label = "barHeight$i"
                )
                animatedHeight
            } else 6f
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(height.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
            )
        }
    }
}

/** Horizontal threat-score meter, color-graded from safe green to danger red. */
@Composable
fun ThreatMeter(label: String, score: Int, modifier: Modifier = Modifier) {
    val color = when {
        score >= 70 -> ShelterDanger
        score >= 35 -> ShelterAmber
        else -> ShelterSafe
    }
    Column(modifier = modifier) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("THREAT AI", style = MaterialTheme.typography.labelLarge, color = ShelterTextDim)
            Text("$score/100", style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { (score / 100f).coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = color,
            trackColor = ShelterOutline,
        )
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A single sealed-evidence entry in the vault. */
@Composable
fun EvidenceCard(
    index: Long,
    timestamp: String,
    threatLabel: String,
    threatScore: Int,
    lat: Double?,
    lng: Double?,
    chainHash: String,
    previousHash: String?,
    summary: String,
    playback: EvidencePlaybackState,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onSeek: (Long) -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier
) {
    val severity = when {
        threatScore >= 70 -> ShelterDanger
        threatScore >= 35 -> ShelterAmber
        else -> ShelterSafe
    }
    val isActive = playback.evidenceId == index
    var draggedPosition by remember(index) { mutableFloatStateOf(0f) }
    var isDragging by remember(index) { mutableStateOf(false) }
    LaunchedEffect(isActive, playback.positionMs) {
        if (isActive && !isDragging) draggedPosition = playback.positionMs.toFloat()
    }
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = ShelterSurface),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(severity.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("$threatScore", color = severity, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Evidence #$index", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(timestamp, style = MaterialTheme.typography.bodyMedium, color = ShelterTextDim)
                }
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text(threatLabel, fontSize = 11.sp) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = severity.copy(alpha = 0.15f),
                        labelColor = severity,
                        disabledContainerColor = severity.copy(alpha = 0.15f),
                        disabledLabelColor = severity
                    ),
                    border = null
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.LocationOn, contentDescription = null, tint = ShelterTextDim, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    text = if (lat != null && lng != null) "${lat.toString().take(7)}, ${lng.toString().take(7)}" else "GPS unavailable",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ShelterTextDim
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = ShelterBlueSoft, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "${chainHash.take(14)}… ← ${previousHash?.take(10) ?: "GENESIS"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ShelterBlueSoft
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = ShelterTextDim,
                lineHeight = 16.sp
            )

            if (isActive) {
                Spacer(Modifier.height(12.dp))
                val duration = playback.durationMs.coerceAtLeast(1L)
                Slider(
                    value = draggedPosition.coerceIn(0f, duration.toFloat()),
                    onValueChange = {
                        isDragging = true
                        draggedPosition = it
                    },
                    onValueChangeFinished = {
                        isDragging = false
                        if (playback.durationMs > 0L) onSeek(draggedPosition.toLong())
                    },
                    valueRange = 0f..duration.toFloat(),
                    enabled = !playback.isPreparing && playback.durationMs > 0L
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatPlaybackTime(if (isDragging) draggedPosition.toLong() else playback.positionMs), style = MaterialTheme.typography.labelSmall, color = ShelterTextDim)
                    Text(formatPlaybackTime(playback.durationMs), style = MaterialTheme.typography.labelSmall, color = ShelterTextDim)
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = onPlayPause,
                    enabled = !playback.isPreparing,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = ShelterSurfaceRaised, contentColor = MaterialTheme.colorScheme.onSurface)
                ) {
                    if (isActive && playback.isPreparing) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        val completed = isActive && playback.durationMs > 0L && playback.positionMs >= playback.durationMs
                        Icon(
                            when {
                                isActive && playback.isPlaying -> Icons.Filled.Pause
                                completed -> Icons.Filled.Replay
                                else -> Icons.Filled.PlayArrow
                            },
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(when {
                        isActive && playback.isPreparing -> "Opening…"
                        isActive && playback.isPlaying -> "Pause"
                        isActive && playback.durationMs > 0L && playback.positionMs >= playback.durationMs -> "Replay"
                        isActive -> "Resume"
                        else -> "Play"
                    })
                }
                if (isActive) {
                    IconButton(onClick = onStop) {
                        Icon(Icons.Filled.Stop, contentDescription = "Stop playback")
                    }
                }
                OutlinedButton(
                    onClick = onExport,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Export chain of custody")
                }
            }
        }
    }
}

private fun formatPlaybackTime(milliseconds: Long): String {
    val totalSeconds = (milliseconds.coerceAtLeast(0L) / 1_000L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%d:%02d".format(Locale.getDefault(), minutes, seconds)
}

/** Small pill row used for the safeword display + edit affordance. */
@Composable
fun SafewordRow(safeword: String, onEditClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(ShelterSurface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("SAFEWORD", style = MaterialTheme.typography.labelLarge, color = ShelterTextDim)
            Text("\"$safeword\"", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        IconButton(onClick = onEditClick) {
            Icon(Icons.Filled.Edit, contentDescription = "Change safeword", tint = ShelterBlueSoft)
        }
    }
}

/** Dialog for changing the safeword. */
@Composable
fun SafewordDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ShelterSurface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = ShelterTextDim,
        title = { Text("Set custom safeword") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text("e.g. PINEAPPLE") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    focusedBorderColor = ShelterBlue,
                    cursorColor = ShelterBlue,
                )
            )
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onSave(text) }) { Text("Save", color = ShelterBlue) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = null, tint = ShelterTextDim, modifier = Modifier.size(16.dp)) }
        }
    )
}
