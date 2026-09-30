package com.bithead.shelter.ui

import com.bithead.shelter.i18n.AppLanguage
import com.bithead.shelter.i18n.mediaTypeLabel
import com.bithead.shelter.i18n.plural
import com.bithead.shelter.i18n.str

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.BitmapFactory
import android.location.Location
import android.media.RingtoneManager
import android.net.Uri
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.bithead.shelter.R
import com.bithead.shelter.ai.IncidentSummary
import com.bithead.shelter.ai.OfflineIndicTranslator
import com.bithead.shelter.data.Evidence
import com.bithead.shelter.data.TrustedContact
import com.bithead.shelter.emergency.CachedLocation
import com.bithead.shelter.emergency.ContactAlertStatus
import com.bithead.shelter.emergency.PocketTriggerMode
import com.bithead.shelter.security.AppDisguiseManager
import com.bithead.shelter.ui.components.EvidenceCard
import com.bithead.shelter.ui.components.EvidencePlaybackState
import com.bithead.shelter.ui.components.EvidenceMediaPreviewState
import com.bithead.shelter.ui.components.ListeningBars
import com.bithead.shelter.ui.components.ThreatMeter
import com.bithead.shelter.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos

private fun tabs() = listOf(str(R.string.tab_armed), str(R.string.vault), str(R.string.routes), str(R.string.support), str(R.string.settings))
private val tabIcons = listOf(Icons.Outlined.Dashboard, Icons.Outlined.FolderSpecial, Icons.Outlined.NearMe, Icons.AutoMirrored.Outlined.HelpOutline, Icons.Outlined.Tune)
internal data class MapDangerZone(
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double,
    val level: String,
    val title: String,
    val detail: String
)

internal fun sampleDangerZones() = listOf(
    MapDangerZone(28.6421, 77.2194, 340.0, "high", str(R.string.harassment_reports), str(R.string.n_8_sample_reports_in_the_past_30_days)),
    MapDangerZone(28.6228, 77.2087, 270.0, "medium", str(R.string.poorly_lit_stretch), str(R.string.n_5_sample_reports_in_the_past_30_days)),
    MapDangerZone(28.6356, 77.2315, 230.0, "medium", str(R.string.isolated_route), str(R.string.n_3_sample_reports_in_the_past_30_days))
)

internal fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val result = FloatArray(1)
    Location.distanceBetween(lat1, lng1, lat2, lng2, result)
    return result[0].toDouble()
}

private fun adaptiveDangerZones(latitude: Double?, longitude: Double?): List<MapDangerZone> {
    if (latitude == null || longitude == null ||
        !latitude.isFinite() || !longitude.isFinite() ||
        distanceMeters(latitude, longitude, 28.6335, 77.2199) <= 50_000.0) {
        return sampleDangerZones()
    }

    fun shifted(northMeters: Double, eastMeters: Double, radius: Double, level: String, title: String, detail: String): MapDangerZone {
        val lat = (latitude + northMeters / 111_320.0).coerceIn(-89.999, 89.999)
        val longitudeScale = (111_320.0 * abs(cos(Math.toRadians(latitude)))).coerceAtLeast(1.0)
        val rawLongitude = longitude + eastMeters / longitudeScale
        val lng = ((rawLongitude + 540.0) % 360.0) - 180.0
        return MapDangerZone(lat, lng, radius, level, title, detail)
    }

    return listOf(
        shifted(120.0, 160.0, 340.0, "high", str(R.string.nearby_incident_cluster), str(R.string.n_8_adaptive_sample_reports_in_this_map_area)),
        shifted(-420.0, -250.0, 270.0, "medium", str(R.string.poorly_lit_stretch), str(R.string.n_5_adaptive_sample_reports_in_this_map_area)),
        shifted(480.0, -380.0, 230.0, "medium", str(R.string.isolated_route), str(R.string.n_3_adaptive_sample_reports_in_this_map_area))
    )
}

private fun dangerZonesJson(zones: List<MapDangerZone>) = JSONArray().apply {
    check(zones.all { it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 && it.radiusMeters > 0 })
    zones.forEach { zone ->
        put(JSONObject()
            .put("lat", zone.latitude)
            .put("lng", zone.longitude)
            .put("radius", zone.radiusMeters)
            .put("level", zone.level)
            .put("title", zone.title)
            .put("detail", zone.detail))
    }
}.toString()

@Composable
fun VaaniApp(
    isEmergency: Boolean, isSealing: Boolean, safeword: String, threatLabel: String, threatScore: Int,
    lat: Double?, lng: Double?, evidence: List<Evidence>, vaultUnlocked: Boolean,
    mapLat: Double?, mapLng: Double?, mapAccuracyMeters: Float?, mapLocationTimestamp: Long?,
    biometricAvailable: Boolean, snackbarHostState: SnackbarHostState,
    onTrigger: () -> Unit, onEditSafeword: () -> Unit,
    playbackState: EvidencePlaybackState, onPlaybackToggle: (Evidence) -> Unit,
    onPlaybackStop: () -> Unit, onPlaybackSeek: (Evidence, Long) -> Unit,
    mediaPreviewState: EvidenceMediaPreviewState, onOpenMedia: (Evidence) -> Unit,
    onCloseMedia: () -> Unit,
    onExport: (Evidence) -> Unit, onDeleteEvidence: (Evidence) -> Unit,
    onVerifyChain: () -> Unit, onUnlockVault: () -> Unit,
    listening: Boolean, listeningActive: Boolean, onListeningChange: (Boolean) -> Unit,
    speechLanguage: String, onSpeechLanguageChange: (String) -> Unit,
    pocketEnabled: Boolean, pocketState: String, pocketDetail: String, pocketMode: PocketTriggerMode,
    onPocketProtectionChange: (Boolean) -> Unit, onPocketModeChange: (PocketTriggerMode) -> Unit,
    gestureWakeMode: Boolean, gestureWindowOpening: Boolean, gestureWindowSecondsRemaining: Int,
    onGestureWakeModeChange: (Boolean) -> Unit, onLockVault: () -> Unit, onVaultHidden: () -> Unit,
    disguiseEnabled: Boolean, onDisguiseEnabledChange: (Boolean) -> Unit,
    onRefreshMapLocation: () -> Unit,
    trustedContacts: List<TrustedContact>, smsStatuses: List<ContactAlertStatus>,
    locationTracking: Boolean, cachedLocation: CachedLocation?,
    onAddContact: (String, String) -> Unit, onRemoveContact: (String) -> Unit,
    onContactLanguageChange: (String, String) -> Unit,
    onPickContact: () -> Unit, onLocationTrackingChange: (Boolean) -> Unit,
    onRequestSmsPermission: () -> Unit, onImportMedia: () -> Unit,
    pendingEvidenceDir: java.io.File, activeIncidentId: String?, onMediaCaptured: (java.io.File) -> Unit,
    recordingCapped: Boolean, onTamperDemo: (() -> Unit)?,
    appLanguage: String, onAppLanguageChange: (String) -> Unit,
    onDisguiseShown: (Boolean) -> Unit
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var disguised by rememberSaveable { mutableStateOf(disguiseEnabled) }
    var blackout by remember { mutableStateOf(false) }
    var pocketCoverDismissed by rememberSaveable { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var decoyDelay by rememberSaveable { mutableIntStateOf(0) }
    var decoyCall by rememberSaveable { mutableStateOf(false) }
    var showCamera by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(disguiseEnabled) { disguised = disguiseEnabled }
    // Entering the Notes disguise closes the camera so it cannot reappear on unlock,
    // and tells the activity so Recent apps shows "Notes" too.
    LaunchedEffect(disguised) {
        if (disguised) showCamera = false
        onDisguiseShown(disguised)
    }
    LaunchedEffect(pocketState) {
        if (pocketState == "ARMED" || pocketState == "LISTENING" || pocketState == "RECORDING" || pocketState == "SEALING") {
            if (!pocketCoverDismissed) {
                onLockVault()
                blackout = true
            }
        } else if (pocketState == "DISARMED") {
            pocketCoverDismissed = false
        }
    }
    LaunchedEffect(tab, disguised, blackout, vaultUnlocked) {
        if (tab != 1 || disguised || blackout || !vaultUnlocked) onVaultHidden()
    }
    LaunchedEffect(decoyDelay) {
        if (decoyDelay > 0) {
            delay(decoyDelay * 1000L)
            decoyDelay = 0
            decoyCall = true
        }
    }
    val disguise = { onLockVault(); disguised = true }
    val unlockDisguise = { disguised = false }
    BackHandler(blackout || disguised || tab != 0) {
        when {
            blackout && pocketEnabled -> Unit
            blackout -> blackout = false
            disguised -> Unit
            else -> tab = 0
        }
    }
    if (blackout) {
        Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(pocketState) {
            detectTapGestures(onPress = {
                if (withTimeoutOrNull(2_000L) { tryAwaitRelease() } == null) {
                    if (pocketEnabled) {
                        pocketCoverDismissed = true
                        blackout = false
                        disguised = true
                    } else blackout = false
                }
            })
        }) {
        }
        return
    }
    if (showCamera && !disguised) {
        EvidenceCamera(
            pendingDir = pendingEvidenceDir,
            incidentId = activeIncidentId,
            onPhoto = { showCamera = false; onMediaCaptured(it) },
            onVideo = { showCamera = false; onMediaCaptured(it) },
            onClose = { showCamera = false }
        )
        return
    }
    Scaffold(
        containerColor = ShelterInk,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { Column(Modifier.background(ShelterInk)) {
            Row(Modifier.statusBarsPadding().fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (disguised) {
                    Column(
                        Modifier.weight(1f).pointerInput(Unit) {
                            detectTapGestures(onPress = {
                                if (withTimeoutOrNull(2_000L) { tryAwaitRelease() } == null) unlockDisguise()
                            })
                        }
                    ) {
                        Text(str(R.string.personal), style = MaterialTheme.typography.labelMedium, color = ShelterTextDim)
                        Text(str(R.string.all_notes), style = MaterialTheme.typography.titleLarge)
                    }
                } else {
                    Box(Modifier.size(44.dp).clip(Square).background(ShelterSurface).border(1.dp, ShelterOutline, Square)
                        .clickable(onClickLabel = str(R.string.show_notes_disguise), onClick = disguise), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Calculate, str(R.string.show_notes_disguise), tint = ShelterBone)
                    }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text("VAANI", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = ShelterSafeInk)
                        Text(listOf(str(R.string.home), str(R.string.vault), str(R.string.safety_map), str(R.string.crisis_rights), str(R.string.settings))[tab], style = MaterialTheme.typography.titleLarge, color = ShelterBone)
                    }
                    OutlinedButton(onClick = { onLockVault(); blackout = true }, shape = Square, contentPadding = PaddingValues(horizontal = 10.dp),
                        border = BorderStroke(1.dp, ShelterOutline), modifier = Modifier.height(40.dp)) {
                        Icon(Icons.Outlined.VisibilityOff, null, Modifier.size(16.dp), tint = ShelterBone)
                        Spacer(Modifier.width(6.dp))
                        Text(str(R.string.blackout), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = ShelterBone)
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { tab = 4 }, shape = Square,
                        contentPadding = PaddingValues(horizontal = 8.dp), border = BorderStroke(1.dp, ShelterOutline), modifier = Modifier.height(40.dp)) {
                        Text(appLanguage.takeUnless { it == AppLanguage.SYSTEM }?.uppercase(Locale.ROOT) ?: "LANG",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = ShelterBone)
                    }
                    Spacer(Modifier.width(8.dp))
                    Image(painterResource(R.drawable.vaani_logo), "VAANI", Modifier.size(40.dp).clip(Square).border(1.dp, ShelterOutline, Square))
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(ShelterBorder))
            }
        },
        bottomBar = {
            if (!disguised) Column(Modifier.background(ShelterInk).navigationBarsPadding()) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(ShelterBorder))
                Row(Modifier.fillMaxWidth().height(64.dp)) {
                    tabs().forEachIndexed { i, title ->
                        val selected = tab == i
                        Column(
                            Modifier.weight(1f).fillMaxHeight().background(if (selected) ShelterSafe else ShelterInk)
                                .clickable(onClickLabel = title) { tab = i },
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
                        ) {
                            Icon(tabIcons[i], title, tint = if (selected) ShelterOnGold else ShelterTextDim, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.height(4.dp))
                            Text(title.uppercase(Locale.ROOT), maxLines = 1,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium),
                                color = if (selected) ShelterOnGold else ShelterTextDim)
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            if (disguised) NotesScreen(unlockDisguise)
            else when (tab) {
                0 -> Dashboard(isEmergency, isSealing, safeword, evidence.size, listening, listeningActive, gestureWakeMode,
                    gestureWindowOpening, gestureWindowSecondsRemaining, onListeningChange, onEditSafeword, onTrigger,
                    { tab = it }, disguise, decoyDelay, { if (it == 0) decoyCall = true else decoyDelay = it }, trustedContacts.size,
                    { showCamera = true }, recordingCapped)
                1 -> Vault(isEmergency, isSealing, threatLabel, threatScore, lat, lng, evidence, vaultUnlocked, biometricAvailable,
                    onTrigger, onUnlockVault, onVerifyChain, playbackState, onPlaybackToggle, onPlaybackStop,
                    onPlaybackSeek, onOpenMedia, onExport, onDeleteEvidence,
                    { blackout = true }, { showCamera = true }, onImportMedia, recordingCapped, onTamperDemo, appLanguage)
                2 -> Community(mapLat, mapLng, mapAccuracyMeters, mapLocationTimestamp, onRefreshMapLocation) { message = it }
                3 -> Support(disguise)
                4 -> Settings(safeword, listening, onListeningChange, gestureWakeMode, onGestureWakeModeChange, onEditSafeword, disguiseEnabled, onDisguiseEnabledChange, disguise, { message = it }, trustedContacts, smsStatuses, locationTracking, cachedLocation, onAddContact, onRemoveContact, onContactLanguageChange, onPickContact, onLocationTrackingChange, onRequestSmsPermission, onImportMedia, { showCamera = true }, speechLanguage, onSpeechLanguageChange, pocketEnabled, pocketState, pocketDetail, pocketMode, onPocketProtectionChange, onPocketModeChange, appLanguage, onAppLanguageChange)
            }
        }
    }
    message?.let { text ->
        AlertDialog(onDismissRequest = { message = null }, title = { Text("VAANI") }, text = { Text(text) },
            confirmButton = { TextButton(onClick = { message = null }) { Text(str(R.string.got_it)) } })
    }
    if (decoyCall) {
        val context = LocalContext.current
        DisposableEffect(Unit) {
            val ringtone = runCatching {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                RingtoneManager.getRingtone(context, uri).also { it.play() }
            }.getOrNull()
            onDispose { ringtone?.stop() }
        }
        AlertDialog(onDismissRequest = { decoyCall = false }, icon = { Icon(Icons.Outlined.Phone, null) },
            title = { Text(str(R.string.incoming_call_home)) }, text = { Text(str(R.string.local_decoy_call_it_does_not_place_a_real_ca)) },
            confirmButton = { TextButton(onClick = { decoyCall = false }) { Text(str(R.string.end_call)) } })
    }
    if (mediaPreviewState.evidenceId != null) MediaPreview(mediaPreviewState, onCloseMedia)
}

private val Square = RoundedCornerShape(4.dp)

/** Level-1 structural panel: flat fill, 1px partition border, 4px corners. */
@Composable
private fun Panel(modifier: Modifier = Modifier, color: Color = ShelterSurface, borderColor: Color = ShelterBorder,
    content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(Square).background(color).border(1.dp, borderColor, Square).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
private fun PreviewActionPanel(title: String, detail: String, icon: ImageVector, onOpen: () -> Unit) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f)) { Heading(title) }
            Badge(str(R.string.preview), BadgeTone.Info)
        }
        Caption(detail)
        Action(str(R.string.open_preview), Icons.AutoMirrored.Outlined.OpenInNew, onOpen, primary = false)
        Text(str(R.string.preview_no_alert_was_sent), color = ShelterSafeInk, style = MaterialTheme.typography.labelMedium)
    }
}

/** Uppercase architectural heading, per the Bauhaus type spec. */
@Composable
private fun Heading(text: String) { Text(text.uppercase(Locale.ROOT), style = MaterialTheme.typography.titleMedium, color = ShelterBone) }

@Composable
private fun Caption(text: String) { Text(text, style = MaterialTheme.typography.bodyMedium, color = ShelterTextDim) }

/** Uppercase mono section label with a short structural underline. */
@Composable
private fun SectionLabel(text: String, trailing: String? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(text.uppercase(Locale.ROOT), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = ShelterBone)
            Box(Modifier.padding(top = 4.dp).width(40.dp).height(1.dp).background(ShelterOutline))
        }
        trailing?.let { Text(it.uppercase(Locale.ROOT), style = MaterialTheme.typography.labelSmall, color = ShelterTextDim) }
    }
}

private enum class BadgeTone { Armed, Critical, Info }

/** Compact status pill: the only fully rounded shape in the system. */
@Composable
private fun Badge(text: String, tone: BadgeTone = BadgeTone.Armed) {
    val (fill, edge, ink) = when (tone) {
        BadgeTone.Armed -> Triple(ShelterSafeSoft, ShelterSafe.copy(alpha = 0.5f), ShelterSafeInk)
        BadgeTone.Critical -> Triple(ShelterDangerSoft, ShelterDanger.copy(alpha = 0.6f), ShelterDangerInk)
        BadgeTone.Info -> Triple(ShelterBlueTint, ShelterBlue.copy(alpha = 0.5f), ShelterBlueSoft)
    }
    Text(text.uppercase(Locale.ROOT),
        Modifier.clip(CircleShape).background(fill).border(1.dp, edge, CircleShape).padding(horizontal = 10.dp, vertical = 4.dp),
        color = ink, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium), maxLines = 1)
}

/** Square icon tile used at the head of panels. */
@Composable
private fun IconTile(icon: ImageVector, tint: Color = ShelterSafeInk, fill: Color = ShelterSurfaceRaised) {
    Box(Modifier.size(40.dp).clip(Square).background(fill).border(1.dp, ShelterOutline, Square), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/** Primary = solid industrial gold; secondary = tactical neutral; danger = crimson. */
@Composable
private fun Action(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = true,
    enabled: Boolean = true, danger: Boolean = false) {
    val container = when { danger -> ShelterDanger; primary -> ShelterSafe; else -> ShelterSurfaceRaised }
    val content = when { danger -> ShelterBone; primary -> ShelterOnGold; else -> ShelterBone }
    Button(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = 52.dp), shape = Square,
        border = if (primary || danger) null else BorderStroke(1.dp, ShelterBorder),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content,
            disabledContainerColor = ShelterSurfaceRaised, disabledContentColor = ShelterTextDim)) {
        Icon(icon, null, Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
        Text(text.uppercase(Locale.ROOT), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun Dashboard(emergency: Boolean, isSealing: Boolean, safeword: String, count: Int, listening: Boolean, listeningActive: Boolean, gestureWakeMode: Boolean,
    gestureWindowOpening: Boolean, gestureWindowSecondsRemaining: Int,
    onListen: (Boolean) -> Unit, onEdit: () -> Unit, onTrigger: () -> Unit, navigate: (Int) -> Unit,
    disguise: () -> Unit, decoyDelay: Int, scheduleDecoy: (Int) -> Unit,
    trustedContactCount: Int, openCamera: () -> Unit, recordingCapped: Boolean) {
    val listenState = when {
        !listening -> str(R.string.paused)
        gestureWindowOpening -> str(R.string.opening)
        gestureWakeMode && listeningActive -> str(R.string.listening_seconds, gestureWindowSecondsRemaining)
        gestureWakeMode -> str(R.string.gesture_armed)
        else -> str(R.string.continuous)
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Safeword: gold header, inset trigger box, arm switch.
        item { Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Mic, null, tint = ShelterSafeInk, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(str(R.string.safeword), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = ShelterSafeInk)
                Badge(listenState, if (listening) BadgeTone.Armed else BadgeTone.Info)
            }
            Surface(onClick = onEdit, shape = Square, color = ShelterInk, border = BorderStroke(1.dp, ShelterBorder)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(str(R.string.covert_safeword_trigger), style = MaterialTheme.typography.labelMedium, color = ShelterTextDim)
                        Text("“$safeword”", style = MaterialTheme.typography.titleLarge, color = ShelterBone)
                    }
                    Box(Modifier.size(40.dp).clip(Square).background(ShelterSurfaceRaised).border(1.dp, ShelterOutline, Square), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.EditNote, str(R.string.edit_safeword), tint = ShelterBone)
                    }
                }
            }
            ListeningBars(emergency, ShelterSafeInk, Modifier.align(Alignment.CenterHorizontally).height(32.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(str(R.string.safeword_armed), style = MaterialTheme.typography.bodyLarge, color = ShelterBone)
                    Caption(if (gestureWakeMode) str(R.string.jerk_the_phone_to_open_a_12s_listening_windo) else str(R.string.listens_while_the_app_is_open))
                }
                Switch(listening, onListen, enabled = !emergency && !isSealing)
            }
        } }
        // Hero: action-led, one solid gold command.
        item { Panel(color = ShelterSurface, borderColor = if (emergency) ShelterDanger else ShelterBorder) {
            Box(Modifier.align(Alignment.CenterHorizontally)) {
                IconTile(if (emergency) Icons.Outlined.Mic else Icons.Outlined.Shield, if (emergency) ShelterDangerInk else ShelterSafeInk, ShelterInk)
            }
            Text(
                when { isSealing -> str(R.string.sealing_evidence); emergency -> str(R.string.recording_evidence); else -> str(R.string.hold_2s_for_emergency_alert) },
                Modifier.fillMaxWidth(), style = MaterialTheme.typography.headlineSmall, color = ShelterBone,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Text(
                when {
                    isSealing -> str(R.string.encrypting_and_adding_the_recording_to_the_t)
                    emergency -> if (recordingCapped) str(R.string.hands_free_recording_sealed_every_30_seconds) else str(R.string.recording_until_you_stop_every_30_seconds_is)
                    else -> str(R.string.sends_your_last_known_location_by_sms_to_tru)
                },
                Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium, color = ShelterTextDim,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            when {
                isSealing -> Action(str(R.string.sealing_evidence_2), Icons.Outlined.Lock, {}, enabled = false)
                emergency -> Action(str(R.string.stop_seal_evidence), Icons.Outlined.Lock, onTrigger, danger = true)
                else -> HoldToAlert(onTrigger)
            }
            Text(if (emergency) str(R.string.audio_stays_on_device) else str(R.string.press_hold_confirmation_required),
                Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelSmall, color = ShelterTextDim,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        } }
        // Nearby help: honest shortcuts into the map and support directory.
        item { Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.LocalPolice, null, tint = ShelterSafeInk, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) { Heading(str(R.string.nearby_police_ngos)) }
                Badge(str(R.string.preview), BadgeTone.Info)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniTile(str(R.string.police_station), str(R.string.open_in_device_maps), ShelterDanger, Modifier.weight(1f)) { navigate(2) }
                MiniTile(str(R.string.ngo_shelter), str(R.string.crisis_directory), ShelterBlue, Modifier.weight(1f)) { navigate(3) }
            }
        } }
        item { Row(Modifier.height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GridCard(Icons.Outlined.Sms, if (trustedContactCount > 0) str(R.string.active) else str(R.string.setup), if (trustedContactCount > 0) BadgeTone.Armed else BadgeTone.Critical,
                str(R.string.sms_live_location), if (trustedContactCount == 0) str(R.string.no_trusted_contacts_yet) else plural(trustedContactCount, R.string.trusted_contacts_one, R.string.trusted_contacts_other),
                Modifier.weight(1f)) { Action(str(R.string.contacts), Icons.Outlined.People, { navigate(4) }, primary = false) }
            GridCard(Icons.Outlined.Videocam, str(R.string.encrypted), BadgeTone.Info, str(R.string.camera_vault), str(R.string.sealed_aes, plural(count, R.string.sealed_items_one, R.string.sealed_items_other)),
                Modifier.weight(1f)) { Action(str(R.string.open), Icons.Outlined.PhotoCamera, openCamera, danger = true) }
        } }
        item { Row(Modifier.height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GridCard(Icons.Outlined.PhoneInTalk, if (decoyDelay > 0) str(R.string.seconds_short, decoyDelay) else str(R.string.ready), BadgeTone.Armed, str(R.string.decoy_call),
                str(R.string.local_ringtone_no_real_call), Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0 to str(R.string.now), 15 to "+15s", 30 to "+30s").forEach { (seconds, label) ->
                        OutlinedButton(onClick = { scheduleDecoy(seconds) }, Modifier.weight(1f).height(44.dp), shape = Square,
                            contentPadding = PaddingValues(horizontal = 2.dp), border = BorderStroke(1.dp, ShelterBorder)) {
                            Text(label.uppercase(Locale.ROOT), style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = ShelterBone, maxLines = 1)
                        }
                    }
                }
            }
            GridCard(Icons.Outlined.HealthAndSafety, str(R.string.sample), BadgeTone.Info, str(R.string.safe_haven), str(R.string.well_lit_routes_on_the_sample_map),
                Modifier.weight(1f)) { Action(str(R.string.wayfinder), Icons.Outlined.NearMe, { navigate(2) }, primary = false) }
        } }
        item {
            Surface(onClick = disguise, shape = Square, color = ShelterSurface, border = BorderStroke(1.dp, ShelterBorder)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.VisibilityOff, null, tint = ShelterTextDim)
                    Spacer(Modifier.width(12.dp))
                    Text(str(R.string.lock_to_notes_tap_to_disguise), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = ShelterTextDim)
                }
            }
        }
    }
}

@Composable
private fun MiniTile(title: String, detail: String, marker: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier, shape = Square, color = ShelterInk, border = BorderStroke(1.dp, ShelterBorder)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(marker))
                Spacer(Modifier.width(8.dp))
                Text(title.uppercase(Locale.ROOT), style = MaterialTheme.typography.titleSmall, color = ShelterBone, maxLines = 1)
            }
            Text(detail, style = MaterialTheme.typography.labelMedium, color = ShelterTextDim)
        }
    }
}

@Composable
private fun GridCard(icon: ImageVector, badge: String, tone: BadgeTone, title: String, detail: String, modifier: Modifier = Modifier,
    action: @Composable ColumnScope.() -> Unit) {
    Panel(modifier.fillMaxHeight()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = ShelterBone, modifier = Modifier.size(26.dp))
            Spacer(Modifier.weight(1f))
            Badge(badge, tone)
        }
        Heading(title)
        Text(detail, style = MaterialTheme.typography.labelMedium, color = ShelterTextDim)
        Spacer(Modifier.weight(1f))
        action()
    }
}

@Composable
private fun HoldToAlert(onComplete: () -> Unit) {
    var holding by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).pointerInput(onComplete) {
            detectTapGestures(onPress = {
                holding = true
                val releasedBeforeTwoSeconds = withTimeoutOrNull(2_000L) { tryAwaitRelease() }
                if (releasedBeforeTwoSeconds == null) onComplete()
                holding = false
            })
        },
        // Press state: the gold command dims, then fires after a full 2s hold.
        color = if (holding) ShelterSafe.copy(alpha = 0.7f) else ShelterSafe,
        shape = Square
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text(if (holding) str(R.string.keep_holding) else str(R.string.hold_alert_record), color = ShelterOnGold,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
        }
    }
}

@Composable
private fun Vault(emergency: Boolean, isSealing: Boolean, label: String, score: Int, lat: Double?, lng: Double?, evidence: List<Evidence>, unlocked: Boolean,
    biometric: Boolean, trigger: () -> Unit, unlock: () -> Unit, verify: () -> Unit,
    playback: EvidencePlaybackState, togglePlayback: (Evidence) -> Unit, stopPlayback: () -> Unit,
    seekPlayback: (Evidence, Long) -> Unit, openMedia: (Evidence) -> Unit,
    export: (Evidence) -> Unit,
    deleteEvidence: (Evidence) -> Unit, blackout: () -> Unit, openCamera: () -> Unit,
    importMedia: () -> Unit, recordingCapped: Boolean, onTamperDemo: (() -> Unit)?, appLanguage: String) {
    val context = LocalContext.current
    var translatedSummaries by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    var translationError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(evidence, appLanguage, unlocked) {
        translatedSummaries = emptyMap()
        translationError = null
        if (unlocked && appLanguage in setOf(AppLanguage.BENGALI, AppLanguage.MARATHI, AppLanguage.TAMIL)) {
            runCatching {
                OfflineIndicTranslator.translateBatch(context, evidence.map(IncidentSummary::describe), appLanguage)
            }.onSuccess { translations ->
                translatedSummaries = evidence.mapIndexed { index, entry -> entry.id to translations[index] }.toMap()
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                translationError = "Offline translation unavailable; summaries remain in English."
            }
        }
    }
    val dateFormat = remember { SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()) }
    var audioTab by rememberSaveable { mutableIntStateOf(0) }
    var pendingDeletion by remember { mutableStateOf<Evidence?>(null) }
    val incidents = remember(evidence) { evidence.groupBy { it.incidentId ?: "legacy-${it.id}" }.entries.toList() }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (translationError != null) item { Caption(checkNotNull(translationError)) }
        item { Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(Icons.Outlined.Lock)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Heading(str(R.string.stealth_evidence_vault))
                    Text(str(R.string.on_device_aes_256_gcm), style = MaterialTheme.typography.labelMedium, color = ShelterTextDim)
                }
                Badge(if (unlocked) str(R.string.unlocked) else str(R.string.locked), if (unlocked) BadgeTone.Armed else BadgeTone.Info)
            }
        } }
        // Capture panel: status header, inset waveform, three tactical tiles.
        item { Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(if (emergency) ShelterDangerInk else if (isSealing) ShelterSafeInk else ShelterCarbon))
                Spacer(Modifier.width(10.dp))
                Text(if (isSealing) str(R.string.sealing_evidence) else if (emergency) str(R.string.recording_active) else str(R.string.local_capture_ready), Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = ShelterBone)
            }
            Box(Modifier.height(1.dp).fillMaxWidth().background(ShelterBorder))
            Column(Modifier.fillMaxWidth().clip(Square).background(ShelterInk).border(1.dp, ShelterBorder, Square).padding(vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ListeningBars(emergency, if (emergency) ShelterBone else ShelterCarbon, Modifier.height(36.dp))
                Text(if (isSealing) str(R.string.encrypting) else if (emergency) str(R.string.recording) else str(R.string.standby), style = MaterialTheme.typography.headlineSmall, color = ShelterBone)
                Text(if (isSealing) str(R.string.new_recordings_wait_until_sealing_finishes) else if (emergency && recordingCapped) str(R.string.hands_free_stops_after_5_minutes) else if (emergency) str(R.string.records_until_you_stop_sealed_every_30s) else str(R.string.start_recording_from_the_armed_tab),
                    style = MaterialTheme.typography.labelMedium, color = ShelterTextDim)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeTile(str(R.string.audio_loop), Icons.Outlined.GraphicEq, audioTab == 0, Modifier.weight(1f)) { audioTab = 0 }
                ModeTile(str(R.string.camera), Icons.Outlined.PhotoCamera, false, Modifier.weight(1f)) { openCamera() }
                ModeTile(str(R.string.telemetry), Icons.Outlined.Speed, audioTab == 1, Modifier.weight(1f)) { audioTab = 1 }
            }
            if (audioTab == 0) ThreatMeter(label, score) else Caption(if (lat != null && lng != null) str(R.string.last_sealed_location, "$lat, $lng") else str(R.string.a_location_fix_is_requested_when_evidence_is))
            if (audioTab == 0) Caption(str(R.string.on_device_yamnet_sound_model_scores_screams))
        } }
        // Emergency SOS block: gold keyline, crimson command.
        item { Panel(borderColor = ShelterSafe) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Shield, null, tint = ShelterSafeInk)
                Spacer(Modifier.width(10.dp))
                Text(str(R.string.emergency_sos), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = ShelterBone)
            }
            when {
                isSealing -> Action(str(R.string.sealing_evidence_2), Icons.Outlined.Lock, {}, enabled = false)
                emergency -> Action(str(R.string.stop_seal_vault), Icons.Outlined.Lock, trigger, danger = true)
                else -> HoldToAlertCrimson(trigger)
            }
            Caption(str(R.string.alerts_trusted_contacts_by_sms_and_records_e))
        } }
        item { SectionLabel(str(R.string.quick_actions)) }
        item { Row(Modifier.height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickAction(str(R.string.capture_evidence), str(R.string.private_camera), Icons.Outlined.PhotoCamera, Modifier.weight(1f), openCamera)
            QuickAction(str(R.string.import_label), str(R.string.gallery_original_kept), Icons.Outlined.FileOpen, Modifier.weight(1f), importMedia)
        } }
        item { Row(Modifier.height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickAction(str(R.string.blackout_2), str(R.string.screen_off_look), Icons.Outlined.VisibilityOff, Modifier.weight(1f), blackout)
            QuickAction(str(R.string.seal_check), str(R.string.verify_the_chain), Icons.Outlined.VerifiedUser, Modifier.weight(1f)) { if (unlocked) verify() else unlock() }
        } }
        item { SectionLabel(str(R.string.encrypted_evidence_log), trailing = str(R.string.offline_no_cloud)) }
        if (!unlocked) item { Panel {
            IconTile(Icons.Outlined.Fingerprint)
            Heading(str(R.string.your_evidence_is_private))
            Caption(if (biometric) str(R.string.authenticate_to_view_sealed_recordings) else str(R.string.set_up_a_device_screen_lock_or_biometrics_to))
            Action(str(R.string.unlock_vault), Icons.Outlined.Fingerprint, unlock, enabled = biometric)
        } } else {
            item { Action(str(R.string.verify_chain_integrity), Icons.Outlined.VerifiedUser, verify) }
            if (onTamperDemo != null) item {
                OutlinedButton(onClick = onTamperDemo, modifier = Modifier.fillMaxWidth().height(48.dp), shape = Square,
                    border = BorderStroke(1.dp, ShelterDanger)) {
                    Icon(Icons.Outlined.BugReport, null, tint = ShelterDangerInk); Spacer(Modifier.width(8.dp))
                    Text(str(R.string.tamper_test_demo_build), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = ShelterDangerInk)
                }
            }
            if (evidence.isEmpty()) item { Panel { Heading(str(R.string.no_sealed_recordings_yet)); Caption(str(R.string.hold_the_alert_button_on_the_armed_tab_then)) } }
            items(incidents, key = { it.key }) { incident ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(str(R.string.incident_id, incident.key.takeLast(8)), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = ShelterSafeInk)
                            Caption(plural(incident.value.size, R.string.sealed_items_one, R.string.sealed_items_other))
                        }
                        Badge(str(R.string.hash_chained))
                    }
                    incident.value.forEach { entry -> EvidenceCard(
                    index = entry.id,
                    timestamp = dateFormat.format(Date(entry.createdAt)),
                    threatLabel = IncidentSummary.displayLabel(entry.threatLabel),
                    threatScore = entry.threatScore,
                    lat = entry.latitude,
                    lng = entry.longitude,
                    chainHash = entry.sha256,
                    previousHash = entry.previousHash,
                    summary = translatedSummaries[entry.id] ?: IncidentSummary.describe(entry),
                    mediaType = entry.mediaType,
                    playback = playback,
                    onPlayPause = { togglePlayback(entry) },
                    onStop = stopPlayback,
                    onSeek = { seekPlayback(entry, it) },
                    onOpenMedia = { openMedia(entry) },
                    onExport = { export(entry) },
                    deletionPending = entry.deletedFileHash != null,
                    onDelete = { pendingDeletion = entry }
                    ) }
                }
            }
        }
    }
    pendingDeletion?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDeletion = null },
            title = { Text(if (entry.deletedFileHash == null) str(R.string.delete_evidence) else str(R.string.retry_deletion)) },
            text = { Text(str(R.string.delete_evidence_body, mediaTypeLabel(entry.mediaType))) },
            confirmButton = {
                TextButton(onClick = { pendingDeletion = null; deleteEvidence(entry) }) {
                    Text(str(R.string.delete_permanently), color = ShelterDangerInk)
                }
            },
            dismissButton = { TextButton(onClick = { pendingDeletion = null }) { Text(str(R.string.cancel)) } }
        )
    }
}

@Composable
private fun MediaPreview(state: EvidenceMediaPreviewState, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = ShelterSurface,
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Heading(if (state.mediaType.equals("VIDEO", true)) str(R.string.video_evidence) else str(R.string.image_evidence))
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, str(R.string.close_preview)) }
                }
                when {
                    state.isPreparing -> Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    state.filePath == null -> Caption(str(R.string.preview_unavailable))
                    state.mediaType.equals("VIDEO", true) -> AndroidView(
                        factory = { context ->
                            VideoView(context).apply {
                                val controller = MediaController(context)
                                controller.setAnchorView(this)
                                setMediaController(controller)
                                setVideoPath(state.filePath)
                                setOnPreparedListener { start() }
                            }
                        },
                        update = { view -> if (!view.isPlaying) view.start() },
                        modifier = Modifier.fillMaxWidth().height(420.dp)
                    )
                    else -> {
                        val bitmap = remember(state.filePath) { BitmapFactory.decodeFile(state.filePath)?.asImageBitmap() }
                        if (bitmap == null) Caption(str(R.string.this_image_could_not_be_decoded)) else Image(
                            bitmap = bitmap,
                            contentDescription = str(R.string.decrypted_evidence_image),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp)
                        )
                    }
                }
                Caption(str(R.string.temporary_decrypted_preview_removed_when_thi))
            }
        }
    }
}

@Composable
private fun NotesScreen(unlock: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("vaani_notes", 0) }
    var query by rememberSaveable { mutableStateOf("") }
    var category by remember { mutableStateOf(str(R.string.all_notes)) }
    var note by rememberSaveable { mutableStateOf(prefs.getString("memo", "") ?: "") }
    var editor by remember { mutableStateOf(false) }
    val groceries = listOf(str(R.string.cold_pressed_olive_oil_balsamic_vinegar), str(R.string.organic_unsweetened_almond_milk), str(R.string.artisan_sourdough_boule_sliced), str(R.string.fresh_mint_leaves_baby_spinach), str(R.string.loose_leaf_chamomile_tea))
    val checked = remember { mutableStateListOf(*Array(5) { prefs.getBoolean("grocery_$it", it < 2) }) }
    fun matches(title: String, group: String) = (category == str(R.string.all_notes) || category == group) && title.contains(query, true)
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { OutlinedTextField(query, { value ->
            if (AppDisguiseManager.matchesUnlockCode(context, value)) {
                query = ""
                unlock()
            } else query = value
        }, Modifier.fillMaxWidth(), placeholder = { Text(str(R.string.search_memos_recipes_lists)) }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, shape = RoundedCornerShape(16.dp), singleLine = true) }
        item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(str(R.string.all_notes), str(R.string.groceries), str(R.string.reading), str(R.string.work_sync)).forEach { label -> FilterChip(category == label, { category = label }, label = { Text(label) }) } } }
        if (matches(str(R.string.weekly_grocery_market), str(R.string.groceries))) item { Panel {
            Heading(str(R.string.weekly_grocery_market)); Caption(str(R.string.items_for_saturday_brunch_prep_weekly_staple))
            groceries.forEachIndexed { i, text -> Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked[i], { checked[i] = it; prefs.edit().putBoolean("grocery_$i", it).apply() }); Text(text, style = MaterialTheme.typography.bodyMedium, textDecoration = if (checked[i]) TextDecoration.LineThrough else null) } }
            Caption(str(R.string.notes_done_count, checked.count { it }, str(R.string.groceries)))
        } }
        if (matches(str(R.string.reading_journal), str(R.string.reading))) item { Panel { Icon(Icons.AutoMirrored.Outlined.MenuBook, null, Modifier.size(32.dp), ShelterSafe); Heading(str(R.string.reading_journal)); Caption(str(R.string.in_praise_of_shadows_essays_on_quiet_archite)); Badge(str(R.string.personal_3_recommendations)) } }
        if (matches(str(R.string.tuesday_sync_platform_review), str(R.string.work_sync))) item { Panel { Heading(str(R.string.tuesday_sync_platform_review)); Caption(str(R.string.action_items_from_product_design_alignment_a)); Text(str(R.string.standardize_typography_across_the_app_review)); Caption(str(R.string.updated_by_sarah_work_sync)) } }
        if (matches(str(R.string.roasted_butternut_soup), str(R.string.groceries))) item { Panel { Heading(str(R.string.roasted_butternut_soup)); Caption(str(R.string.caramelize_with_nutmeg_brown_butter_shallots)); Badge(str(R.string.kitchen_45_mins)) } }
        if (note.isNotBlank() && matches(note, str(R.string.all_notes))) item { Panel { Heading(str(R.string.my_memo)); Text(note); TextButton(onClick = { editor = true }) { Text(str(R.string.edit_memo)) } } }
        item { Action(str(R.string.write_a_memo), Icons.Outlined.EditNote, { editor = true }) }
    }
    if (editor) AlertDialog(onDismissRequest = { editor = false }, title = { Text(str(R.string.my_memo)) }, text = { OutlinedTextField(note, { note = it }, minLines = 4, label = { Text(str(R.string.note)) }) },
        confirmButton = { TextButton(onClick = { prefs.edit().putString("memo", note).apply(); editor = false }) { Text(str(R.string.save)) } })
}

private fun nearbyDangerZone(latitude: Double?, longitude: Double?, zones: List<MapDangerZone>): MapDangerZone? {
    if (latitude == null || longitude == null) return null
    return zones
        .minByOrNull { distanceMeters(latitude, longitude, it.latitude, it.longitude) }
        ?.takeIf { distanceMeters(latitude, longitude, it.latitude, it.longitude) <= it.radiusMeters }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LeafletMap(
    filter: String,
    latitude: Double?,
    longitude: Double?,
    accuracyMeters: Float?,
    locationTimestamp: Long?,
    zones: List<MapDangerZone>,
    recenterRequest: Int,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var mapError by remember { mutableStateOf<String?>(null) }
    var mapReady by remember { mutableStateOf(false) }
    var mapLoadAttempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(mapLoadAttempt) {
        delay(8_000L)
        if (!mapReady && mapError == null) mapError = str(R.string.map_did_not_finish_loading_tap_retry_to_relo)
    }
    val checkMapSize: (WebView) -> Unit = { view ->
        if (view.width > 0 && view.height > 0) {
            view.evaluateJavascript(
                "window.invalidateMapSize && window.invalidateMapSize(); Boolean(window.isMapReady && window.isMapReady());"
            ) { result ->
                if (result == "true" || result == "\"true\"") {
                    mapReady = true
                    mapError = null
                }
            }
        }
    }
    val webView = remember(context, mapLoadAttempt) {
        WebView(context).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.domStorageEnabled = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.userAgentString = "${settings.userAgentString} VAANI-Android/1.0"
            addJavascriptInterface(object {
                @JavascriptInterface
                fun onMapReady() {
                    (context as? android.app.Activity)?.runOnUiThread {
                        mapReady = true
                        mapError = null
                    } ?: run {
                        mapReady = true
                        mapError = null
                    }
                }
            }, "AndroidBridge")
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                        Log.e("VaaniMap", "${message.message()} (${message.sourceId()}:${message.lineNumber()})")
                    }
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (request.url.scheme != "https" || request.url.host != "appassets.androidplatform.net") return null
                    val path = request.url.path.orEmpty()
                    val asset = path.removePrefix("/assets/")
                    if (!path.startsWith("/assets/") || asset.contains("..") ||
                        (asset != "leaflet_map.html" && !asset.startsWith("leaflet/"))) {
                        return WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(byteArrayOf()))
                    }
                    val mime = when {
                        asset.endsWith(".html") -> "text/html"
                        asset.endsWith(".css") -> "text/css"
                        asset.endsWith(".js") -> "text/javascript"
                        asset.endsWith(".png") -> "image/png"
                        else -> "text/plain"
                    }
                    return try {
                        WebResourceResponse(mime, if (mime.startsWith("text/")) "UTF-8" else null, context.assets.open(asset))
                    } catch (_: IOException) {
                        WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(byteArrayOf()))
                    }
                }

                override fun onPageFinished(view: WebView, url: String) {
                    (view.tag as? String)?.let { view.evaluateJavascript(it, null) }
                    view.post { checkMapSize(view) }
                    view.postDelayed({ checkMapSize(view) }, 500L)
                    view.postDelayed({ checkMapSize(view) }, 1500L)
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) {
                        mapReady = false
                        mapError = str(R.string.map_page_could_not_load_tap_retry_to_try_aga)
                    }
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
            }
            addOnLayoutChangeListener { view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                if (right > left && bottom > top) {
                    if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                        view.post { checkMapSize(view as WebView) }
                    }
                }
            }
            loadUrl("https://appassets.androidplatform.net/assets/leaflet_map.html?lang=" +
                if (AppLanguage.isHindi(context)) "hi" else "en")
        }
    }
    val validLocation = latitude?.isFinite() == true && longitude?.isFinite() == true
    val zonesJson = remember(zones) { dangerZonesJson(zones) }
    val script = remember(filter, latitude, longitude, accuracyMeters, locationTimestamp, zonesJson, recenterRequest) {
        val lat = if (validLocation) latitude.toString() else "null"
        val lng = if (validLocation) longitude.toString() else "null"
        val accuracy = accuracyMeters?.takeIf { it.isFinite() && it > 0f }?.toString() ?: "null"
        val timestamp = locationTimestamp?.takeIf { it > 0L }?.toString() ?: "null"
        buildString {
            append("window.applyNativeState && window.applyNativeState(")
            append(JSONObject.quote(filter)).append(", ").append(lat).append(", ").append(lng)
            append(", ").append(accuracy).append(", ").append(timestamp).append(", ").append(zonesJson).append(");")
            append("window.requestRecenter && window.requestRecenter(").append(recenterRequest).append(");")
        }
    }
    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
        }
    }
    Box(modifier) {
        AndroidView(
            factory = { webView },
            update = { view ->
                if (view.tag != script) {
                    view.tag = script
                    view.evaluateJavascript(script, null)
                }
                view.post { checkMapSize(view) }
            },
            modifier = Modifier.fillMaxSize()
        )
        if (!mapReady) {
            Column(
                Modifier.fillMaxSize().background(ShelterSurface).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (mapError == null) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(str(R.string.loading_community_map))
                } else {
                    Text(mapError ?: str(R.string.map_unavailable))
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = {
                        mapError = null
                        mapReady = false
                        mapLoadAttempt++
                    }) { Text(str(R.string.retry_map)) }
                }
            }
        }
    }
}

@Composable
private fun Community(
    latitude: Double?,
    longitude: Double?,
    accuracyMeters: Float?,
    locationTimestamp: Long?,
    refreshLocation: () -> Unit,
    info: (String) -> Unit
) {
    var filter by rememberSaveable { mutableStateOf("All Signals") }
    var factor by remember { mutableStateOf(str(R.string.dark_stretch)) }
    var reports by rememberSaveable { mutableIntStateOf(0) }
    var recenterRequest by rememberSaveable { mutableIntStateOf(0) }
    val context = LocalContext.current
    val zones = remember(latitude, longitude) { adaptiveDangerZones(latitude, longitude) }
    val nearbyZone = remember(latitude, longitude, zones) { nearbyDangerZone(latitude, longitude, zones) }
    LaunchedEffect(Unit) { refreshLocation() }
    LaunchedEffect(nearbyZone?.title) {
        nearbyZone?.let { info(str(R.string.caution_inside_zone, it.title.lowercase())) }
    }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) { Badge(str(R.string.leaflet_adaptive_sample_incident_history)); Spacer(Modifier.weight(1f)); Icon(Icons.Outlined.LocationOn, str(R.string.current_location), tint = ShelterSafe) } }
        if (nearbyZone != null) item { Panel(color = ShelterDangerSoft, borderColor = ShelterDanger) { Heading(str(R.string.caution_near, nearbyZone.title.lowercase())); Caption(str(R.string.your_current_location_overlaps_this_sample_r)) } }
        item {
            LeafletMap(
                filter,
                latitude,
                longitude,
                accuracyMeters,
                locationTimestamp,
                zones,
                recenterRequest,
                Modifier.fillMaxWidth().height(330.dp).clip(RoundedCornerShape(20.dp))
            )
        }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = refreshLocation, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.Refresh, null); Spacer(Modifier.width(6.dp)); Text(str(R.string.refresh)) }
            OutlinedButton(onClick = {
                if (latitude != null && longitude != null) recenterRequest++ else refreshLocation()
            }, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.MyLocation, null); Spacer(Modifier.width(6.dp)); Text(str(R.string.recenter)) }
        } }
        item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("All Signals", "Well-Lit Streets", "Patrol Points").forEach { label -> FilterChip(filter == label, { filter = label }, label = { Text(mapFilterLabel(label)) }) } } }
        item { Panel { Heading(str(R.string.safe_corridor_preview)); Badge(str(R.string.adaptive_sample_data)); Panel(color = ShelterSurfaceRaised) { Heading(str(R.string.suggested_nearby_well_lit_corridor)); Caption(str(R.string.leaflet_route_overlay_near_the_current_map_a)); Action(str(R.string.open_device_maps), Icons.Outlined.NearMe, { openLink(context, "geo:0,0?q=nearby+police+station") }) }; Caption(str(R.string.map_tiles_require_internet_access_route_avai)) } }
        item { SectionLabel(str(R.string.community_observations)) }
        item { Panel { Heading(str(R.string.night_transit_shuttle)); Caption(str(R.string.sample_gate_4_station_regular_service)) } }
        item { Panel {
            Heading(str(R.string.report_environmental_factor)); Caption(str(R.string.try_a_local_demo_report_nothing_is_published))
            listOf(str(R.string.dark_stretch), str(R.string.isolated_alley), str(R.string.harassment_hotspot), str(R.string.stray_dogs)).chunked(2).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { text -> FilterChip(factor == text, { factor = text }, label = { Text(text, fontSize = 12.sp) }) } } }
            Action(str(R.string.save_demo_signal), Icons.Outlined.AddLocationAlt, { reports++; info(str(R.string.demo_signal_saved, factor)) })
            Caption(str(R.string.demo_signals_count, reports))
        } }
        item { PreviewActionPanel(str(R.string.nearby_police_stations), str(R.string.preview_nearby_public_stations_this_does_not), Icons.Outlined.LocalPolice) { info(str(R.string.preview_no_alert_was_sent_opening_nearby_pol)); openLink(context, "geo:0,0?q=nearby+police+station") } }
        item { PreviewActionPanel(str(R.string.nearby_ngos), str(R.string.preview_local_support_organizations_no_organ), Icons.Outlined.VolunteerActivism) { info(str(R.string.preview_no_alert_was_sent_connect_an_approve)) } }
        item { PreviewActionPanel(str(R.string.peer_support), str(R.string.preview_consenting_peer_support_options_no_p), Icons.Outlined.Groups) { info(str(R.string.preview_no_alert_was_sent_peer_support_requi)) } }
        item { PreviewActionPanel(str(R.string.nearby_shelters), str(R.string.preview_local_shelters_and_public_resources), Icons.Outlined.HomeWork) { info(str(R.string.preview_no_alert_was_sent_opening_this_previ)) } }
    }
}

private fun openLink(context: android.content.Context, uri: String) {
    try { context.startActivity(Intent(if (uri.startsWith("tel:")) Intent.ACTION_DIAL else Intent.ACTION_VIEW, Uri.parse(uri))) }
    catch (_: android.content.ActivityNotFoundException) { Toast.makeText(context, str(R.string.no_app_available_to_open_this_action), Toast.LENGTH_SHORT).show() }
}

@Composable
private fun Support(disguise: () -> Unit) {
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf("") }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { Panel { Badge(str(R.string.vaani_care_confidential)); Heading(str(R.string.support_when_you_need_it)); Caption(str(R.string.choose_a_public_support_resource_calls_open)) } }
        item { SectionLabel(str(R.string.emergency_support_india)) }
        item { Panel(color = ShelterSurfaceRaised) { Icon(Icons.Outlined.HealthAndSafety, null, tint = ShelterSafe); Heading(str(R.string.emergency_assistance)); Caption(str(R.string.india_s_emergency_response_number_for_police)); Action(str(R.string.dial_112), Icons.Outlined.Phone, { openLink(context, "tel:112") }); TextButton(onClick = { openLink(context, "https://112.gov.in/") }) { Text(str(R.string.official_service_information)) } } }
        item { Panel { Heading(str(R.string.legal_aid_assistance)); Caption(str(R.string.national_legal_services_authority_nalsa)); Badge(str(R.string.public_helpline)); Action(str(R.string.dial_15100), Icons.Outlined.Gavel, { openLink(context, "tel:15100") }, primary = false); TextButton(onClick = { openLink(context, "https://nalsa.gov.in/womens-assistance/") }) { Text(str(R.string.legal_assistance_resources)) } } }
        item { Panel { Heading(str(R.string.cyber_safety_harassment)); Caption(str(R.string.use_the_national_cyber_crime_reporting_porta)); Action(str(R.string.open_reporting_portal), Icons.Outlined.Security, { openLink(context, "https://cybercrime.gov.in/") }, primary = false); TextButton(onClick = { openLink(context, "tel:1930") }) { Text(str(R.string.dial_1930_financial_fraud)) } } }
        item { SectionLabel(str(R.string.support_guides)) }
        listOf(str(R.string.finding_legal_assistance) to str(R.string.explore_nalsa_s_official_assistance_page_for), str(R.string.digital_privacy) to str(R.string.review_app_permissions_and_linked_devices_in), str(R.string.finding_local_care) to str(R.string.use_device_maps_to_search_for_nearby_hospita)).forEach { (title, text) ->
            item { Panel(color = ShelterSurfaceRaised) { TextButton(onClick = { expanded = if (expanded == title) "" else title }) { Text(title, Modifier.weight(1f)); Icon(if (expanded == title) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null) }; if (expanded == title) Caption(text) } }
        }
        item { Action(str(R.string.quick_exit_to_notes), Icons.Outlined.VisibilityOff, disguise, primary = false) }
    }
}

@Composable
private fun Settings(safeword: String, listening: Boolean, onListen: (Boolean) -> Unit,
    gestureWakeMode: Boolean, onGestureWakeModeChange: (Boolean) -> Unit, edit: () -> Unit,
    disguiseEnabled: Boolean, onDisguiseEnabledChange: (Boolean) -> Unit, disguise: () -> Unit, info: (String) -> Unit,
    trustedContacts: List<TrustedContact>, smsStatuses: List<ContactAlertStatus>, locationTracking: Boolean,
    cachedLocation: CachedLocation?, onAddContact: (String, String) -> Unit, onRemoveContact: (String) -> Unit,
    onContactLanguageChange: (String, String) -> Unit,
    onPickContact: () -> Unit, onLocationTrackingChange: (Boolean) -> Unit,
    onRequestSmsPermission: () -> Unit, onImportMedia: () -> Unit, onCaptureMedia: () -> Unit,
    speechLanguage: String, onSpeechLanguageChange: (String) -> Unit,
    pocketEnabled: Boolean, pocketState: String, pocketDetail: String, pocketMode: PocketTriggerMode,
    onPocketProtectionChange: (Boolean) -> Unit, onPocketModeChange: (PocketTriggerMode) -> Unit,
    appLanguage: String, onAppLanguageChange: (String) -> Unit) {
    val context = LocalContext.current
    var contactName by rememberSaveable { mutableStateOf("") }
    var contactNumber by rememberSaveable { mutableStateOf("") }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { Panel { Heading(str(R.string.protection_preferences)); Caption(str(R.string.local_capture_notes_disguise)); Badge(if (listening) str(R.string.listening) else str(R.string.paused)) } }
        item { SectionLabel(str(R.string.acoustic_trigger)) }
        item { Panel(color = ShelterSurfaceRaised) {
            Caption(str(R.string.current_safeword_phrase))
            Heading("“$safeword”")
            Action(str(R.string.edit_safeword), Icons.Outlined.Edit, edit, primary = false)
            Row(verticalAlignment = Alignment.CenterVertically) { Text(str(R.string.safeword_armed), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge); Switch(listening, onListen) }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(str(R.string.gesture_wake_12s_window), style = MaterialTheme.typography.bodyLarge)
                    Caption(if (gestureWakeMode) str(R.string.mode_stealth_mic_opens_after_a_jerk) else str(R.string.mode_continuous_listening))
                }
                Switch(gestureWakeMode, onGestureWakeModeChange)
            }
            Caption(str(R.string.stealth_mode_keeps_the_mic_off_until_a_physi))
            Caption(str(R.string.offline_recognition_depends_on_your_device))
        } }
        item { SectionLabel(str(R.string.device_vault_security)) }
        item { Panel { Heading(str(R.string.pocket_protection)); Caption(str(R.string.foreground_service_jerk_sensor_experimental)); Row(verticalAlignment = Alignment.CenterVertically) { Text(str(R.string.arm_while_screen_is_off), Modifier.weight(1f)); Switch(pocketEnabled, onPocketProtectionChange) }; Badge(when { pocketEnabled && pocketState == "DISARMED" -> str(R.string.protection_interrupted); pocketState == "ARMED" -> str(R.string.armed); pocketState == "ERROR" -> str(R.string.protection_interrupted); pocketState == "RECORDING" -> str(R.string.recording_2); pocketState == "SEALING" -> str(R.string.sealing); else -> str(R.string.disarmed) }); Caption(pocketDetail); Spacer(Modifier.height(8.dp)); Caption(str(R.string.the_service_must_be_started_while_vaani_is_o)); Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) { FilterChip(pocketMode == PocketTriggerMode.INSTANT_RECORD, { onPocketModeChange(PocketTriggerMode.INSTANT_RECORD) }, label = { Text(str(R.string.instant_record)) }); FilterChip(pocketMode == PocketTriggerMode.SAFEWORD_WINDOW, { onPocketModeChange(PocketTriggerMode.SAFEWORD_WINDOW) }, label = { Text(str(R.string.safeword_window)) }) } } }
        item { Panel { Heading(str(R.string.biometric_vault_lock)); Caption(str(R.string.uses_your_device_fingerprint_face_or_screen)); Action(str(R.string.device_security_settings), Icons.Outlined.Fingerprint, { context.startActivity(Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)) }, primary = false) } }
        item { Panel { Heading(str(R.string.location_protection)); Caption(str(R.string.keeps_a_timestamped_last_known_location_read)); Row(verticalAlignment = Alignment.CenterVertically) { Text(str(R.string.track_while_armed), Modifier.weight(1f)); Switch(locationTracking, onLocationTrackingChange) }; Caption(if (cachedLocation == null) str(R.string.no_location_fix_cached_yet) else str(R.string.last_fix, java.text.DateFormat.getDateTimeInstance().format(Date(cachedLocation.timestampMillis)))) } }
        item { Panel { Heading(str(R.string.trusted_contacts)); Caption(str(R.string.emergency_sms_sends_only_to_contacts_you_add)); trustedContacts.forEach { contact -> Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(contact.name); Caption(contact.number) }; TextButton(onClick = { onRemoveContact(contact.number) }) { Text(str(R.string.remove)) } } }; OutlinedTextField(contactName, { contactName = it }, Modifier.fillMaxWidth(), label = { Text(str(R.string.name)) }, singleLine = true); OutlinedTextField(contactNumber, { contactNumber = it }, Modifier.fillMaxWidth(), label = { Text(str(R.string.phone_number)) }, singleLine = true); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { onAddContact(contactName, contactNumber); contactName = ""; contactNumber = "" }) { Text(str(R.string.add)) }; OutlinedButton(onClick = onPickContact) { Text(str(R.string.pick_contact)) } }; OutlinedButton(onClick = onRequestSmsPermission, modifier = Modifier.fillMaxWidth()) { Text(str(R.string.allow_emergency_sms)) }; smsStatuses.forEach { status -> Caption(str(R.string.sms_alert_status, status.name, status.status)); status.receiptStatus?.let { receipt -> Caption(str(R.string.sms_receipt_status, status.receiptEvidenceId ?: 0L, receipt)) } } } }
        if (trustedContacts.isNotEmpty()) item { Panel {
            Heading("Contact language preferences")
            Caption("Preferences are saved per contact. Emergency and evidence SMS remain in English until their translations receive human review.")
            trustedContacts.forEach { contact ->
                Text(contact.name, style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("en" to "EN", "hi" to "HI", "bn" to "BN", "mr" to "MR", "ta" to "TA").forEach { (tag, label) ->
                        FilterChip(contact.smsLanguage == tag,
                            { onContactLanguageChange(contact.number, tag) }, label = { Text(label) })
                    }
                }
            }
        } }
        item { Panel { Heading(str(R.string.camera_evidence)); Caption(str(R.string.capture_private_photos_or_silent_videos_up_t)); Action(str(R.string.open_private_camera), Icons.Outlined.PhotoCamera, onCaptureMedia); Action(str(R.string.import_image_or_video), Icons.Outlined.FileOpen, onImportMedia, primary = false) } }
        item { SectionLabel(str(R.string.decoy_app_persona)) }
        item { Panel(color = ShelterSurfaceRaised) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(str(R.string.disguise_as_notes_app), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Switch(disguiseEnabled, onDisguiseEnabledChange)
            }
            Caption(if (disguiseEnabled) str(R.string.the_launcher_appears_as_notes_and_future_lau) else str(R.string.the_launcher_appears_as_vaani_and_opens_the))
            Action(str(R.string.lock_to_notes_now), Icons.Outlined.VisibilityOff, disguise, primary = false)
            HorizontalDivider(color = ShelterBorder)
            // Secret code typed into the Notes search box to return to VAANI.
            var codeSet by remember { mutableStateOf(AppDisguiseManager.hasUnlockCode(context)) }
            var newCode by rememberSaveable { mutableStateOf("") }
            var codeError by remember { mutableStateOf<String?>(null) }
            Text(str(R.string.notes_unlock_code), style = MaterialTheme.typography.labelMedium, color = ShelterTextDim)
            Caption(if (codeSet) str(R.string.set_type_it_into_the_notes_search_box_to_ret) else
                str(R.string.not_set_only_a_2_second_press_on_all_notes_u))
            OutlinedTextField(newCode, { newCode = it; codeError = null }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(if (codeSet) str(R.string.new_code) else str(R.string.choose_a_code_4_characters)) },
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                isError = codeError != null, supportingText = codeError?.let { error -> { Text(error) } })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Action(if (codeSet) str(R.string.change_code) else str(R.string.save_code), Icons.Outlined.Password, {
                    runCatching { AppDisguiseManager.setUnlockCode(context, newCode) }
                        .onSuccess { codeSet = true; newCode = ""; info(str(R.string.notes_unlock_code_saved_type_it_into_the_not)) }
                        .onFailure { codeError = it.message }
                }, Modifier.weight(1f))
                if (codeSet) Action(str(R.string.remove), Icons.Outlined.Delete, {
                    AppDisguiseManager.clearUnlockCode(context); codeSet = false
                }, Modifier.weight(1f), primary = false)
            }
        } }
        item { SectionLabel(str(R.string.language_dispatch)) }
        item { Panel {
            Heading(str(R.string.app_language))
            Caption(str(R.string.app_language_caption))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(AppLanguage.ENGLISH to "English", AppLanguage.HINDI to "हिन्दी",
                    AppLanguage.BENGALI to "বাংলা", AppLanguage.MARATHI to "मराठी",
                    AppLanguage.TAMIL to "தமிழ்", AppLanguage.SYSTEM to str(R.string.device_default)).forEach { (tag, label) ->
                    FilterChip(appLanguage == tag, { onAppLanguageChange(tag) }, label = { Text(label) })
                }
            }
        } }
        item { Panel { Heading(str(R.string.recognition_language)); Caption(str(R.string.used_for_safeword_recognition_on_the_next_li)); Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            listOf("system" to str(R.string.device_default), "en-IN" to str(R.string.english_india),
                "hi-IN" to str(R.string.hindi_india), "bn-IN" to "বাংলা", "mr-IN" to "मराठी",
                "ta-IN" to "தமிழ்").forEach { (tag, label) ->
                FilterChip(selected = speechLanguage == tag, onClick = { onSpeechLanguageChange(tag) }, label = { Text(label) })
            }
        } } }
        item { Panel { Heading(str(R.string.emergency_dispatch)); Caption(str(R.string.trusted_contact_sms_is_active_when_configure)); Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(8.dp).clip(CircleShape).background(ShelterAmber)); Spacer(Modifier.width(8.dp)); Text(str(R.string.preview_action)) }; TextButton(onClick = { info(str(R.string.preview_no_police_or_ngo_alert_was_sent)) }) { Text(str(R.string.about_preview_actions)) } } }
        item { Action(str(R.string.test_interface_quietly), Icons.Outlined.CheckCircleOutline, { info(str(R.string.interface_check_complete_no_recording_was_st)) }) }
    }
}


@Composable
private fun ModeTile(label: String, icon: ImageVector, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier.height(72.dp), shape = Square,
        color = if (selected) ShelterSafe else ShelterSurfaceRaised, border = BorderStroke(1.dp, if (selected) ShelterSafe else ShelterBorder)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, null, tint = if (selected) ShelterOnGold else ShelterBone, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(6.dp))
            Text(label.uppercase(Locale.ROOT), style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = if (selected) ShelterOnGold else ShelterBone, maxLines = 1)
        }
    }
}

@Composable
private fun QuickAction(title: String, detail: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier.fillMaxHeight(), shape = Square, color = ShelterSurface, border = BorderStroke(1.dp, ShelterBorder)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(Square).background(ShelterBlue), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = ShelterBone, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title.uppercase(Locale.ROOT), style = MaterialTheme.typography.titleSmall, color = ShelterBone)
                Text(detail.uppercase(Locale.ROOT), style = MaterialTheme.typography.labelSmall, color = ShelterTextDim)
            }
        }
    }
}

/** Crimson SOS command with the same deliberate 2-second hold. */
@Composable
private fun HoldToAlertCrimson(onComplete: () -> Unit) {
    var holding by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).pointerInput(onComplete) {
            detectTapGestures(onPress = {
                holding = true
                if (withTimeoutOrNull(2_000L) { tryAwaitRelease() } == null) onComplete()
                holding = false
            })
        },
        color = if (holding) ShelterDanger.copy(alpha = 0.7f) else ShelterDanger, shape = Square
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(Square).background(ShelterInk), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Shield, null, tint = ShelterSafeInk, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (holding) str(R.string.keep_holding) else str(R.string.hold_2s_for_emergency_alert), style = MaterialTheme.typography.titleSmall, color = ShelterBone)
                Text(str(R.string.sms_trusted_contacts_record_evidence), style = MaterialTheme.typography.labelSmall, color = ShelterBone.copy(alpha = 0.8f))
            }
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = ShelterBone)
        }
    }
}

/** Map filter ids stay English because the Leaflet page matches on them; only labels are localized. */
private fun mapFilterLabel(id: String): String = when (id) {
    "All Signals" -> str(R.string.all_signals)
    "Well-Lit Streets" -> str(R.string.well_lit_streets)
    "Patrol Points" -> str(R.string.patrol_points)
    else -> id
}
