package com.bithead.shelter.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.location.Location
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
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.bithead.shelter.R
import com.bithead.shelter.ai.IncidentSummary
import com.bithead.shelter.data.Evidence
import com.bithead.shelter.ui.components.EvidenceCard
import com.bithead.shelter.ui.components.EvidencePlaybackState
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

private val tabs = listOf("Spaces", "Vault", "Community", "Support", "Settings")
private val tabIcons = listOf(Icons.Outlined.Dashboard, Icons.Outlined.FolderSpecial, Icons.Outlined.NearMe, Icons.AutoMirrored.Outlined.HelpOutline, Icons.Outlined.Tune)
private val disguisePasscodes = setOf("##", "vaani", "911").also {
    check(it.size == 3 && it.all(String::isNotBlank))
}

internal data class MapDangerZone(
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double,
    val level: String,
    val title: String,
    val detail: String
)

internal val sampleDangerZones = listOf(
    MapDangerZone(28.6421, 77.2194, 340.0, "high", "Harassment reports", "8 sample reports in the past 30 days"),
    MapDangerZone(28.6228, 77.2087, 270.0, "medium", "Poorly lit stretch", "5 sample reports in the past 30 days"),
    MapDangerZone(28.6356, 77.2315, 230.0, "medium", "Isolated route", "3 sample reports in the past 30 days")
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
        return sampleDangerZones
    }

    fun shifted(northMeters: Double, eastMeters: Double, radius: Double, level: String, title: String, detail: String): MapDangerZone {
        val lat = (latitude + northMeters / 111_320.0).coerceIn(-89.999, 89.999)
        val longitudeScale = (111_320.0 * abs(cos(Math.toRadians(latitude)))).coerceAtLeast(1.0)
        val rawLongitude = longitude + eastMeters / longitudeScale
        val lng = ((rawLongitude + 540.0) % 360.0) - 180.0
        return MapDangerZone(lat, lng, radius, level, title, detail)
    }

    return listOf(
        shifted(120.0, 160.0, 340.0, "high", "Nearby incident cluster", "8 adaptive sample reports in this map area"),
        shifted(-420.0, -250.0, 270.0, "medium", "Poorly lit stretch", "5 adaptive sample reports in this map area"),
        shifted(480.0, -380.0, 230.0, "medium", "Isolated route", "3 adaptive sample reports in this map area")
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
    onExport: (Evidence) -> Unit, onDeleteEvidence: (Evidence) -> Unit,
    onVerifyChain: () -> Unit, onUnlockVault: () -> Unit,
    listening: Boolean, listeningActive: Boolean, onListeningChange: (Boolean) -> Unit,
    gestureWakeMode: Boolean, gestureWindowOpening: Boolean, gestureWindowSecondsRemaining: Int,
    onGestureWakeModeChange: (Boolean) -> Unit, onLockVault: () -> Unit, onVaultHidden: () -> Unit,
    disguiseEnabled: Boolean, onDisguiseEnabledChange: (Boolean) -> Unit,
    onRefreshMapLocation: () -> Unit
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var disguised by rememberSaveable { mutableStateOf(disguiseEnabled) }
    var blackout by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var decoyDelay by rememberSaveable { mutableIntStateOf(0) }
    var decoyCall by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(disguiseEnabled) { disguised = disguiseEnabled }
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
        when { blackout -> blackout = false; disguised -> Unit; else -> tab = 0 }
    }
    if (blackout) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            TextButton(onClick = { blackout = false }, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()) {
                Text("Return to vault", color = Color.Gray)
            }
        }
        return
    }
    Scaffold(
        containerColor = ShelterInk,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Row(Modifier.statusBarsPadding().fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (disguised) {
                    Column(
                        Modifier.weight(1f).pointerInput(Unit) {
                            detectTapGestures(onPress = {
                                if (withTimeoutOrNull(2_000L) { tryAwaitRelease() } == null) unlockDisguise()
                            })
                        }
                    ) {
                        Text("PERSONAL", style = MaterialTheme.typography.labelMedium, color = ShelterTextDim)
                        Text("All Notes", style = MaterialTheme.typography.titleLarge)
                    }
                } else {
                    IconButton(onClick = disguise) { Icon(Icons.Outlined.Calculate, "Show notes disguise") }
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text("VAANI", style = MaterialTheme.typography.labelMedium, color = ShelterTextDim)
                        Text(listOf("Spaces / Home", "Evidence / Vault", "Community Routes", "Support Network", "Discreet Settings")[tab], style = MaterialTheme.typography.titleLarge)
                    }
                    Image(painterResource(R.drawable.vaani_mark), "VAANI", Modifier.size(44.dp).clip(CircleShape))
                }
            }
        },
        bottomBar = {
            if (!disguised) NavigationBar(containerColor = ShelterInk, tonalElevation = 0.dp) {
                tabs.forEachIndexed { i, title ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(tabIcons[i], title) },
                        label = { Text(title, fontSize = 10.sp, maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = ShelterSafeSoft, selectedIconColor = ShelterSafe, selectedTextColor = ShelterSafe))
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            if (disguised) NotesScreen(unlockDisguise)
            else when (tab) {
                0 -> Dashboard(isEmergency, isSealing, safeword, evidence.size, listening, listeningActive, gestureWakeMode,
                    gestureWindowOpening, gestureWindowSecondsRemaining, onListeningChange, onEditSafeword, onTrigger,
                    { tab = it }, disguise, { message = it }, decoyDelay, { decoyDelay = it })
                1 -> Vault(isEmergency, isSealing, threatLabel, threatScore, lat, lng, evidence, vaultUnlocked, biometricAvailable,
                    onTrigger, onUnlockVault, onVerifyChain, playbackState, onPlaybackToggle, onPlaybackStop,
                    onPlaybackSeek, onExport, onDeleteEvidence, { blackout = true })
                2 -> Community(mapLat, mapLng, mapAccuracyMeters, mapLocationTimestamp, onRefreshMapLocation) { message = it }
                3 -> Support(disguise)
                4 -> Settings(safeword, listening, onListeningChange, gestureWakeMode, onGestureWakeModeChange, onEditSafeword, disguiseEnabled, onDisguiseEnabledChange, disguise, { message = it })
            }
        }
    }
    message?.let { text ->
        AlertDialog(onDismissRequest = { message = null }, title = { Text("VAANI") }, text = { Text(text) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("Got it") } })
    }
    if (decoyCall) AlertDialog(onDismissRequest = { decoyCall = false }, icon = { Icon(Icons.Outlined.Phone, null) },
        title = { Text("Incoming call · Home") }, text = { Text("Simulated call screen. No phone connection or audio.") },
        confirmButton = { TextButton(onClick = { decoyCall = false }) { Text("End call") } })
}

@Composable
private fun Panel(modifier: Modifier = Modifier, color: Color = ShelterSurface, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(color).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
private fun Heading(text: String) { Text(text, style = MaterialTheme.typography.titleMedium) }

@Composable
private fun Caption(text: String) { Text(text, style = MaterialTheme.typography.bodyMedium, color = ShelterTextDim) }

@Composable
private fun Badge(text: String) {
    Text(text, Modifier.clip(RoundedCornerShape(8.dp)).background(ShelterSafeSoft).padding(horizontal = 9.dp, vertical = 5.dp),
        color = ShelterSafe, style = MaterialTheme.typography.labelMedium)
}

@Composable
private fun Action(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = true, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (primary) ShelterSafe else ShelterSurfaceRaised,
            contentColor = if (primary) Color.White else MaterialTheme.colorScheme.onSurface)) {
        Icon(icon, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(text)
    }
}

@Composable
private fun Dashboard(emergency: Boolean, isSealing: Boolean, safeword: String, count: Int, listening: Boolean, listeningActive: Boolean, gestureWakeMode: Boolean,
    gestureWindowOpening: Boolean, gestureWindowSecondsRemaining: Int,
    onListen: (Boolean) -> Unit, onEdit: () -> Unit, onTrigger: () -> Unit, navigate: (Int) -> Unit,
    disguise: () -> Unit, info: (String) -> Unit, decoyDelay: Int, scheduleDecoy: (Int) -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(emergency, isSealing) {
        if (emergency || isSealing) confirm = false
    }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { Panel { Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Circle, null, Modifier.size(10.dp), ShelterSafe); Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) { Heading(if (isSealing) "Sealing evidence" else if (emergency) "Recording in progress" else if (listeningActive && gestureWakeMode) "Safeword listening • ${gestureWindowSecondsRemaining}s" else if (gestureWindowOpening) "Opening microphone…" else if (listening) "Safeword armed" else "Safeword paused"); Caption("Evidence stays on this device") }
            Badge("Local")
        } } }
        item { Panel(color = ShelterSurfaceRaised) {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.Mic, null, tint = ShelterSafe); Spacer(Modifier.width(10.dp)); Text("ACOUSTIC SAFEWORD\nPROTOCOL", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge); Badge(if (!listening) "Paused" else if (gestureWindowOpening) "Opening" else if (gestureWakeMode && listeningActive) "Listening ${gestureWindowSecondsRemaining}s" else if (gestureWakeMode) "Gesture armed" else "Continuous Active") }
            ListeningBars(listening && (!gestureWakeMode || listeningActive), ShelterSafe, Modifier.align(Alignment.CenterHorizontally).height(32.dp))
            Caption(if (gestureWakeMode) "Shake/jerk phone to open a 12s listening window while this screen is open" else "Listens while app is foregrounded")
            Surface(onClick = onEdit, shape = RoundedCornerShape(12.dp), color = Color.White) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Caption("Trigger phrase"); Heading("“$safeword”") }; Icon(Icons.Outlined.EditNote, "Edit safeword")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.GraphicEq, null); Text("Safeword Protection", Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.bodyMedium); Switch(listening, onListen, enabled = !emergency && !isSealing) }
        } }
        item { Panel(color = Color(0xFF31312D)) {
            Icon(Icons.Outlined.VerifiedUser, null, Modifier.align(Alignment.CenterHorizontally).size(40.dp), ShelterSafeSoft)
            Text(if (isSealing) "Encrypting and sealing evidence…" else if (emergency) "Your recording is running" else "Your quiet safety shield", Modifier.align(Alignment.CenterHorizontally), style = MaterialTheme.typography.headlineSmall, color = Color.White)
            Text("Capture audio locally, then seal it with encryption and a tamper-evident record.", color = Color(0xFFC1C8C3), style = MaterialTheme.typography.bodyMedium)
            when {
                isSealing -> Action("Sealing evidence…", Icons.Outlined.Lock, {}, enabled = false)
                emergency -> Action("Stop & seal evidence", Icons.Outlined.Shield, onTrigger)
                else -> Action("Engage Silent Shield", Icons.Outlined.Shield, { confirm = true })
            }
            Text("Local capture • does not dispatch help", color = Color(0xFFC1C8C3), style = MaterialTheme.typography.labelMedium)
        } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Panel(Modifier.weight(1f)) { Icon(Icons.Outlined.Contacts, null, tint = ShelterSafe); Heading("Relay Ring"); Caption("Mom, Kabir, Aarti"); Badge("Sample contacts"); Action("View ring", Icons.Outlined.People, { info("Sample relay ring: Mom, Kabir, Aarti. Contact delivery is not connected; no alerts are sent.") }, primary = false) }
            Panel(Modifier.weight(1f)) { Icon(Icons.Outlined.Lock, null, tint = ShelterSafe); Heading("Secure Vault"); Caption("$count sealed recordings"); Badge("On device"); Action("Open vault", Icons.Outlined.FolderSpecial, { navigate(1) }, primary = false) }
        } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Panel(Modifier.weight(1f)) { Icon(Icons.Outlined.Phone, null, tint = ShelterSafe); Heading("Decoy Call"); Caption(if (decoyDelay > 0) "Scheduled in ${decoyDelay}s" else "Simulated incoming screen");
                Row { TextButton(onClick = { scheduleDecoy(15) }) { Text("15s") }; TextButton(onClick = { scheduleDecoy(30) }) { Text("30s") } } }
            Panel(Modifier.weight(1f)) { Icon(Icons.Outlined.HealthAndSafety, null, tint = ShelterSafe); Heading("Safe Haven"); Caption("Explore community routes"); Action("Wayfinder", Icons.Outlined.NearMe, { navigate(2) }, primary = false) }
        } }
        item { Panel { Heading("Community outposts"); Caption("Preview nearby support locations and route information."); Badge("Sample map • not live"); Action("Explore routes", Icons.Outlined.Map, { navigate(2) }, primary = false) } }
        item { Action("Lock to Notes", Icons.Outlined.VisibilityOff, disguise, primary = false) }
    }
    if (confirm && !emergency && !isSealing) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Start local recording?") }, text = { Text("Audio is recorded on this device. Stop and seal it in the vault. No emergency services or contacts will be notified.") },
        confirmButton = { TextButton(onClick = { confirm = false; onTrigger() }) { Text("Start recording") } }, dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
}

@Composable
private fun Vault(emergency: Boolean, isSealing: Boolean, label: String, score: Int, lat: Double?, lng: Double?, evidence: List<Evidence>, unlocked: Boolean,
    biometric: Boolean, trigger: () -> Unit, unlock: () -> Unit, verify: () -> Unit,
    playback: EvidencePlaybackState, togglePlayback: (Evidence) -> Unit, stopPlayback: () -> Unit,
    seekPlayback: (Evidence, Long) -> Unit, export: (Evidence) -> Unit,
    deleteEvidence: (Evidence) -> Unit, blackout: () -> Unit) {
    val dateFormat = remember { SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()) }
    var audioTab by rememberSaveable { mutableIntStateOf(0) }
    var pendingDeletion by remember { mutableStateOf<Evidence?>(null) }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { Panel { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.Lock, null, tint = ShelterSafe); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Heading("Stealth Evidence Vault"); Caption("On-device • AES-256-GCM") }; Badge(if (unlocked) "Unlocked" else "Locked") } } }
        item { Panel(color = ShelterSurfaceRaised) {
            Heading(if (isSealing) "SEALING EVIDENCE" else if (emergency) "MIC RECORDING" else "LOCAL CAPTURE")
            ListeningBars(emergency, ShelterSafe, Modifier.align(Alignment.CenterHorizontally).height(40.dp))
            Text(if (isSealing) "Encrypting and committing…" else if (emergency) "Recording…" else "Ready when you are", Modifier.align(Alignment.CenterHorizontally), style = MaterialTheme.typography.headlineSmall)
            Caption(if (isSealing) "New recordings are blocked until sealing finishes" else if (emergency) "Auto-seals after 5 minutes" else "Start recording from Spaces")
            TabRow(selectedTabIndex = audioTab, containerColor = ShelterSurfaceRaised) {
                listOf("Audio", "Location").forEachIndexed { i, title -> Tab(audioTab == i, onClick = { audioTab = i }, text = { Text(title) }) }
            }
            if (audioTab == 0) ThreatMeter(label, score) else Caption(if (lat != null && lng != null) "Last sealed location: $lat, $lng" else "A location fix is requested when evidence is sealed.")
        } }
        item { Action("Blackout display", Icons.Outlined.VisibilityOff, blackout, primary = false) }
        if (emergency) item { Action("Stop & seal vault", Icons.Outlined.Lock, trigger) }
        if (isSealing) item { Action("Sealing evidence…", Icons.Outlined.Lock, {}, enabled = false) }
        item { Panel { Heading("Offline first storage"); Caption("Sealed recordings stay on this device. Unlock to play or export a chain-of-custody record. No cloud or peer sync is connected.") } }
        item { Heading("ENCRYPTED EVIDENCE LOG") }
        if (!unlocked) item { Panel(color = Color.White) {
            Icon(Icons.Outlined.Fingerprint, null, Modifier.size(36.dp), ShelterSafe)
            Heading("Your evidence is private")
            Caption(if (biometric) "Authenticate to view sealed recordings." else "Set up a device screen lock or biometrics to access the vault.")
            Button(onClick = unlock, enabled = biometric, modifier = Modifier.fillMaxWidth()) { Text("Unlock vault") }
        } } else {
            item { Action("Verify chain integrity", Icons.Outlined.VerifiedUser, verify) }
            if (evidence.isEmpty()) item { Panel { Heading("No sealed recordings yet"); Caption("Record from Spaces, then stop and seal to create your first entry.") } }
            items(evidence, key = { it.id }) { entry ->
                EvidenceCard(
                    index = entry.id,
                    timestamp = dateFormat.format(Date(entry.createdAt)),
                    threatLabel = entry.threatLabel,
                    threatScore = entry.threatScore,
                    lat = entry.latitude,
                    lng = entry.longitude,
                    chainHash = entry.sha256,
                    previousHash = entry.previousHash,
                    summary = IncidentSummary.describe(entry),
                    playback = playback,
                    onPlayPause = { togglePlayback(entry) },
                    onStop = stopPlayback,
                    onSeek = { seekPlayback(entry, it) },
                    onExport = { export(entry) },
                    deletionPending = entry.deletedFileHash != null,
                    onDelete = { pendingDeletion = entry }
                )
            }
        }
    }
    pendingDeletion?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDeletion = null },
            title = { Text(if (entry.deletedFileHash == null) "Delete recording?" else "Retry deletion?") },
            text = { Text("The audio will be permanently removed from this device. Its hash-only deletion record stays in the chain, so later recordings can still be verified.") },
            confirmButton = {
                TextButton(onClick = { pendingDeletion = null; deleteEvidence(entry) }) {
                    Text("Delete permanently", color = ShelterDanger)
                }
            },
            dismissButton = { TextButton(onClick = { pendingDeletion = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun NotesScreen(unlock: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("vaani_notes", 0) }
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("All Notes") }
    var note by rememberSaveable { mutableStateOf(prefs.getString("memo", "") ?: "") }
    var editor by remember { mutableStateOf(false) }
    val groceries = listOf("Cold-pressed olive oil & balsamic vinegar", "Organic unsweetened almond milk", "Artisan sourdough boule (sliced)", "Fresh mint leaves & baby spinach", "Loose leaf chamomile tea")
    val checked = remember { mutableStateListOf(*Array(5) { prefs.getBoolean("grocery_$it", it < 2) }) }
    fun matches(title: String, group: String) = (category == "All Notes" || category == group) && title.contains(query, true)
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { OutlinedTextField(query, { value ->
            if (value.trim().lowercase(Locale.ROOT) in disguisePasscodes) {
                query = ""
                unlock()
            } else query = value
        }, Modifier.fillMaxWidth(), placeholder = { Text("Search memos, recipes, lists…") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, shape = RoundedCornerShape(16.dp), singleLine = true) }
        item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("All Notes", "Groceries", "Reading", "Work Sync").forEach { label -> FilterChip(category == label, { category = label }, label = { Text(label) }) } } }
        if (matches("Weekly Grocery & Market", "Groceries")) item { Panel {
            Heading("Weekly Grocery & Market"); Caption("Items for Saturday brunch prep & weekly staples from the corner market.")
            groceries.forEachIndexed { i, text -> Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked[i], { checked[i] = it; prefs.edit().putBoolean("grocery_$i", it).apply() }); Text(text, style = MaterialTheme.typography.bodyMedium, textDecoration = if (checked[i]) TextDecoration.LineThrough else null) } }
            Caption("${checked.count { it }} of 5 done  •  Groceries")
        } }
        if (matches("Reading Journal", "Reading")) item { Panel { Icon(Icons.AutoMirrored.Outlined.MenuBook, null, Modifier.size(32.dp), ShelterSafe); Heading("Reading Journal"); Caption("‘In Praise of Shadows’ & essays on quiet architecture, domestic rituals…"); Badge("Personal • 3 recommendations") } }
        if (matches("Tuesday Sync: Platform Review", "Work Sync")) item { Panel { Heading("Tuesday Sync: Platform Review"); Caption("Action items from product design alignment and quarterly review:"); Text("• Standardize typography across the app.\n\n• Review the memo input experience.\n\n• Finalize this week's deliverables."); Caption("Updated by Sarah • Work Sync") } }
        if (matches("Roasted Butternut Soup", "Groceries")) item { Panel { Heading("Roasted Butternut Soup"); Caption("Caramelize with nutmeg, brown butter, shallots, and crisp sage…"); Badge("Kitchen • 45 mins") } }
        if (note.isNotBlank() && matches(note, "All Notes")) item { Panel { Heading("My memo"); Text(note); TextButton(onClick = { editor = true }) { Text("Edit memo") } } }
        item { Action("Write a memo", Icons.Outlined.EditNote, { editor = true }) }
    }
    if (editor) AlertDialog(onDismissRequest = { editor = false }, title = { Text("My memo") }, text = { OutlinedTextField(note, { note = it }, minLines = 4, label = { Text("Note") }) },
        confirmButton = { TextButton(onClick = { prefs.edit().putString("memo", note).apply(); editor = false }) { Text("Save") } })
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
        if (!mapReady && mapError == null) mapError = "Map did not finish loading. Tap Retry to reload it."
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
                        mapError = "Map page could not load. Tap Retry to try again."
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
            loadUrl("https://appassets.androidplatform.net/assets/leaflet_map.html")
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
                Modifier.fillMaxSize().background(Color(0xFFE4EBDF)).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (mapError == null) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Loading community map…")
                } else {
                    Text(mapError ?: "Map unavailable")
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = {
                        mapError = null
                        mapReady = false
                        mapLoadAttempt++
                    }) { Text("Retry map") }
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
    var factor by rememberSaveable { mutableStateOf("Dark stretch") }
    var reports by rememberSaveable { mutableIntStateOf(0) }
    var recenterRequest by rememberSaveable { mutableIntStateOf(0) }
    val context = LocalContext.current
    val zones = remember(latitude, longitude) { adaptiveDangerZones(latitude, longitude) }
    val nearbyZone = remember(latitude, longitude, zones) { nearbyDangerZone(latitude, longitude, zones) }
    LaunchedEffect(Unit) { refreshLocation() }
    LaunchedEffect(nearbyZone?.title) {
        nearbyZone?.let { info("Caution: you are inside the sample ${it.title.lowercase()} zone. Check local conditions and choose a well-lit route.") }
    }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) { Badge("Leaflet • adaptive sample incident history"); Spacer(Modifier.weight(1f)); Icon(Icons.Outlined.LocationOn, "Current location", tint = ShelterSafe) } }
        if (nearbyZone != null) item { Panel(color = Color(0xFFFFDAD6)) { Heading("Caution near ${nearbyZone.title.lowercase()}"); Caption("Your current location overlaps this sample risk area. Use the map to check a well-lit alternative.") } }
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
            OutlinedButton(onClick = refreshLocation, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Refresh") }
            OutlinedButton(onClick = {
                if (latitude != null && longitude != null) recenterRequest++ else refreshLocation()
            }, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.MyLocation, null); Spacer(Modifier.width(6.dp)); Text("Recenter") }
        } }
        item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("All Signals", "Well-Lit Streets", "Patrol Points").forEach { label -> FilterChip(filter == label, { filter = label }, label = { Text(label) }) } } }
        item { Panel { Heading("Safe Corridor Preview"); Badge("Adaptive sample data"); Panel(color = Color.White) { Heading("Suggested: Nearby Well-Lit Corridor"); Caption("Leaflet route overlay near the current map area • no live safety score"); Action("Open device maps", Icons.Outlined.NearMe, { openLink(context, "geo:0,0?q=nearby+police+station") }) }; Caption("Map tiles require internet access. Route availability and local conditions must still be checked.") } }
        item { Heading("COMMUNITY OBSERVATIONS") }
        item { Panel { Heading("Night transit shuttle"); Caption("Sample • Gate 4 station, regular service"); HorizontalDivider(); Heading("Street lamp repaired"); Caption("Sample • Neighborhood maintenance update") } }
        item { Panel {
            Heading("Report environmental factor"); Caption("Try a local demo report. Nothing is published or sent.")
            listOf("Dark stretch", "Isolated alley", "Harassment hotspot", "Stray dogs").chunked(2).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { text -> FilterChip(factor == text, { factor = text }, label = { Text(text, fontSize = 12.sp) }) } } }
            Action("Save demo signal", Icons.Outlined.AddLocationAlt, { reports++; info("Demo signal saved for this session: $factor. It has not been shared.") })
            Caption("$reports local demo signals this session")
        } }
        item { Panel { Heading("Find a nearby support point"); Caption("Search your map app for a hospital or police station."); Action("Find hospitals", Icons.Outlined.LocalHospital, { openLink(context, "geo:0,0?q=nearby+hospital") }, primary = false) } }
    }
}

private fun openLink(context: android.content.Context, uri: String) {
    try { context.startActivity(Intent(if (uri.startsWith("tel:")) Intent.ACTION_DIAL else Intent.ACTION_VIEW, Uri.parse(uri))) }
    catch (_: android.content.ActivityNotFoundException) { Toast.makeText(context, "No app available to open this action", Toast.LENGTH_SHORT).show() }
}

@Composable
private fun Support(disguise: () -> Unit) {
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf("") }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { Panel { Badge("SAFE SANCTUARY • VAANI CARE"); Heading("You deserve support."); Caption("Choose a public support resource. Calls open your dialer so you remain in control.") } }
        item { Heading("EMERGENCY & SUPPORT • INDIA") }
        item { Panel(color = Color.White) { Icon(Icons.Outlined.HealthAndSafety, null, tint = ShelterSafe); Heading("Emergency assistance"); Caption("India's emergency response number for police, fire and medical emergencies."); Action("Dial 112", Icons.Outlined.Phone, { openLink(context, "tel:112") }); TextButton(onClick = { openLink(context, "https://112.gov.in/") }) { Text("Official service information") } } }
        item { Panel { Heading("Legal Aid & Assistance"); Caption("National Legal Services Authority (NALSA)"); Badge("Public helpline"); Action("Dial 15100", Icons.Outlined.Gavel, { openLink(context, "tel:15100") }, primary = false); TextButton(onClick = { openLink(context, "https://nalsa.gov.in/womens-assistance/") }) { Text("Legal assistance resources") } } }
        item { Panel { Heading("Cyber Safety & Harassment"); Caption("Use the National Cyber Crime Reporting Portal to report cybercrime. 1930 is the financial cyber fraud helpline."); Action("Open reporting portal", Icons.Outlined.Security, { openLink(context, "https://cybercrime.gov.in/") }, primary = false); TextButton(onClick = { openLink(context, "tel:1930") }) { Text("Dial 1930 • financial fraud") } } }
        item { Heading("SUPPORT GUIDES") }
        listOf("Finding legal assistance" to "Explore NALSA's official assistance page for available services and eligibility.", "Digital privacy" to "Review app permissions and linked devices in your device settings. The official cybercrime portal provides reporting resources.", "Finding local care" to "Use device maps to search for nearby hospitals. In an emergency in India, open the dialer for 112.").forEach { (title, text) ->
            item { Panel(color = Color.White) { TextButton(onClick = { expanded = if (expanded == title) "" else title }) { Text(title, Modifier.weight(1f)); Icon(if (expanded == title) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null) }; if (expanded == title) Caption(text) } }
        }
        item { Action("Quick exit to notes", Icons.Outlined.VisibilityOff, disguise, primary = false) }
    }
}

@Composable
private fun Settings(safeword: String, listening: Boolean, onListen: (Boolean) -> Unit,
    gestureWakeMode: Boolean, onGestureWakeModeChange: (Boolean) -> Unit, edit: () -> Unit,
    disguiseEnabled: Boolean, onDisguiseEnabledChange: (Boolean) -> Unit, disguise: () -> Unit, info: (String) -> Unit) {
    val context = LocalContext.current
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { Panel { Heading("Protection preferences"); Caption("Local capture • notes disguise"); Badge(if (listening) "Listening" else "Paused") } }
        item { Heading("ACOUSTIC TRIGGER") }
        item { Panel(color = Color.White) {
            Caption("Current safeword phrase")
            Heading("“$safeword”")
            Action("Edit safeword", Icons.Outlined.Edit, edit, primary = false)
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Safeword Protection", Modifier.weight(1f)); Switch(listening, onListen) }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (gestureWakeMode) "Stealth Mode (Gesture-Wake 12s Window)" else "Continuous Listening", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(gestureWakeMode, onGestureWakeModeChange)
            }
            Caption("Stealth mode keeps the mic off until a physical jerk is detected. Gesture detection works while VAANI or Notes is open in the foreground.")
            Caption("Offline recognition depends on your device.")
        } }
        item { Heading("DEVICE & VAULT SECURITY") }
        item { Panel { Heading("Biometric vault lock"); Caption("Uses your device fingerprint, face, or screen lock. The vault locks when the app leaves the foreground."); Action("Device security settings", Icons.Outlined.Fingerprint, { context.startActivity(Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)) }, primary = false) } }
        item { Panel { Heading("Hardware & background triggers"); Caption("Power-button triggers, face-down detection, Bluetooth relay, and background monitoring are not connected in this prototype."); Badge("Preview only"); Action("View planned triggers", Icons.Outlined.Sensors, { info("Planned: hardware gestures, low-battery save, nighttime checks and Bluetooth relay. These features are not active and cannot dispatch help.") }, primary = false) } }
        item { Heading("DECOY APP PERSONA") }
        item { Panel(color = Color.White) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Disguise as Notes App", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Switch(disguiseEnabled, onDisguiseEnabledChange)
            }
            Caption(if (disguiseEnabled) "The launcher appears as Notes and future launches open All Notes." else "The launcher appears as VAANI and opens the safety dashboard.")
            Action("Lock to Notes now", Icons.Outlined.VisibilityOff, disguise, primary = false)
        } }
        item { Heading("LANGUAGE & DISPATCH") }
        item { Panel { Heading("English interface"); Caption("Speech recognition uses your device language. Translated screens and SMS dispatch are not connected.") } }
        item { Action("Test interface quietly", Icons.Outlined.CheckCircleOutline, { info("Interface check complete. No recording was started and no contacts were notified. To test real recording, use Engage Silent Shield on Spaces.") }) }
    }
}
