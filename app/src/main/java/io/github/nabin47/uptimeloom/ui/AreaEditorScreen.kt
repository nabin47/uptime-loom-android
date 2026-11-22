package io.github.nabin47.uptimeloom.ui

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nabin47.uptimeloom.data.Place
import io.github.nabin47.uptimeloom.location.CameraRequest
import io.github.nabin47.uptimeloom.location.FoundPlace
import io.github.nabin47.uptimeloom.location.MapLinks
import io.github.nabin47.uptimeloom.location.MapPicker
import io.github.nabin47.uptimeloom.location.PlaceFinder
import io.github.nabin47.uptimeloom.tracking.AreaMonitor
import io.github.nabin47.uptimeloom.tracking.Locator
import kotlinx.coroutines.launch
import java.util.Locale

/** What the editor opens with: an existing area, or a new one (maybe from a shared link). */
data class AreaDraft(val place: Place, val sharedText: String? = null) {
    val isNew get() = place.id == 0L
}

private const val STREET_ZOOM = 16.5

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AreaEditorScreen(draft: AreaDraft, onClose: () -> Unit, onSave: (Place) -> Unit, onDelete: (Place) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val snackbar = remember { SnackbarHostState() }
    val place = draft.place

    var name by remember { mutableStateOf(place.name) }
    var radius by remember { mutableIntStateOf(place.radiusM) }
    var center by remember { mutableStateOf(if (draft.isNew) null else place.lat to place.lng) }
    var request by remember {
        mutableStateOf(if (draft.isNew) CameraRequest(23.8, 90.4, 2.0) else CameraRequest(place.lat, place.lng, STREET_ZOOM))
    }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<FoundPlace>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var mapFailed by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf("") }
    var latText by remember { mutableStateOf("") }
    var lngText by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }

    fun goTo(lat: Double, lng: Double, label: String? = null) {
        request = CameraRequest(lat, lng, STREET_ZOOM)
        center = lat to lng
        if (name.isBlank() && !label.isNullOrBlank()) name = label.take(40)
    }
    fun say(text: String) { scope.launch { snackbar.showSnackbar(text) } }

    fun locateMe() {
        busy = true
        scope.launch {
            val loc = Locator.current(context, preferLowPower = false)
            busy = false
            if (loc == null) say("Couldn't get your location. Is location turned on?")
            else goTo(loc.latitude, loc.longitude)
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (AreaMonitor.hasForegroundLocation(context)) locateMe() else say("Location permission is needed for \"my location\".")
    }
    fun myLocation() {
        if (AreaMonitor.hasForegroundLocation(context)) locateMe()
        else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    fun useLinkOrText(text: String) {
        if (text.isBlank()) return
        busy = true; keyboard?.hide()
        scope.launch {
            val found = PlaceFinder.fromLinkOrText(context, text)
            busy = false
            if (found == null) say("Couldn't find a place in that link. Try searching instead.")
            else { goTo(found.lat, found.lng, found.name); more = false; link = "" }
        }
    }
    fun search() {
        if (query.isBlank()) return
        busy = true; keyboard?.hide()
        scope.launch {
            // A pasted link or coordinates in the search box work too.
            if (MapLinks.findUrl(query) != null || MapLinks.coordinates(query) != null) {
                busy = false; useLinkOrText(query); query = ""; return@launch
            }
            results = PlaceFinder.search(context, query)
            busy = false
            if (results.isEmpty()) say("No places found. Try adding the city.")
        }
    }

    // Start somewhere useful: a shared place, or where you are now.
    LaunchedEffect(Unit) {
        when {
            draft.sharedText != null -> useLinkOrText(draft.sharedText)
            draft.isNew && AreaMonitor.hasForegroundLocation(context) -> locateMe()
        }
    }

    BackHandler { if (results.isNotEmpty()) results = emptyList() else onClose() }

    val valid = name.isNotBlank() && center != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (draft.isNew) "New work area" else "Edit work area") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    TextButton(enabled = valid, onClick = {
                        val (lat, lng) = center ?: return@TextButton
                        onSave(place.copy(name = name.trim(), lat = lat, lng = lng, radiusM = radius))
                    }) { Text("Save") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            // ------------------------------------------------------------ map --
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (!mapFailed) {
                    MapPicker(
                        modifier = Modifier.fillMaxSize(),
                        request = request,
                        radiusM = radius,
                        accent = MaterialTheme.colorScheme.primary,
                        // Zoomed out to the whole world isn't a choice yet; street level is.
                        onCenter = { lat, lng, zoom -> if (zoom >= 12 || center != null) center = lat to lng },
                        onLoadFailed = { mapFailed = true },
                    )
                    // Fixed pin: the map moves underneath it.
                    Icon(
                        Icons.Filled.Place, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.Center).size(44.dp).offset(y = (-20).dp),
                    )
                } else {
                    Column(
                        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(32.dp),
                        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("The map couldn't load", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Check your internet connection. You can still search, use your location, or paste a link.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        center?.let { (lat, lng) ->
                            Text("Selected: %.5f, %.5f".format(Locale.ROOT, lat, lng), style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(top = 12.dp))
                        }
                    }
                }

                // Floating search
                Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp)) {
                    Surface(shape = RoundedCornerShape(28.dp), shadowElevation = 6.dp, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        TextField(
                            value = query, onValueChange = { query = it }, singleLine = true,
                            placeholder = { Text("Search a place or paste a link") },
                            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                            trailingIcon = {
                                when {
                                    busy -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                    query.isNotEmpty() -> IconButton(onClick = { query = ""; results = emptyList() }) {
                                        Icon(Icons.Outlined.Close, contentDescription = "Clear")
                                    }
                                }
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { search() }),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (results.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(20.dp), shadowElevation = 6.dp,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.padding(top = 8.dp),
                        ) {
                            Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                                results.forEachIndexed { i, r ->
                                    if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                                    Column(
                                        Modifier.fillMaxWidth().clickable {
                                            goTo(r.lat, r.lng, r.name); results = emptyList(); query = ""
                                        }.padding(horizontal = 20.dp, vertical = 12.dp),
                                    ) {
                                        Text(r.name ?: r.address ?: "Result", style = MaterialTheme.typography.bodyLarge,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (r.name != null && r.address != null) {
                                            Text(r.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                SmallFloatingActionButton(
                    onClick = { myLocation() },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) { Icon(Icons.Outlined.MyLocation, contentDescription = "My location") }
            }

            // ---------------------------------------------------------- panel --
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 16.dp).navigationBarsPadding(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (center == null) {
                        Text("Search for your workplace, use your location, or zoom in and move the map so the pin is on it.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedTextField(
                        value = name, onValueChange = { name = it.take(40) }, singleLine = true,
                        label = { Text("Name") }, placeholder = { Text("e.g. Office") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Radius", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Text("$radius m", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        Slider(
                            value = radius.toFloat(), onValueChange = { radius = ((it / 50f).toInt() * 50).coerceIn(100, 1000) },
                            valueRange = 100f..1000f, steps = 17,
                        )
                        Text("150 m or more works best. Use more for a large campus.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    Row(
                        Modifier.fillMaxWidth().clickable { more = !more }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Google Maps link or coordinates", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Icon(if (more) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
                    }
                    AnimatedVisibility(more) {
                        MoreOptions(
                            link = link, onLink = { link = it },
                            onPaste = { text -> link = text },
                            onUseLink = { useLinkOrText(link) },
                            latText = latText, lngText = lngText,
                            onLat = { latText = it }, onLng = { lngText = it },
                            onUseCoordinates = {
                                val lat = latText.trim().replace(',', '.').toDoubleOrNull()
                                val lng = lngText.trim().replace(',', '.').toDoubleOrNull()
                                if (lat == null || lng == null || lat !in -90.0..90.0 || lng !in -180.0..180.0) say("Enter a valid latitude and longitude.")
                                else { goTo(lat, lng); more = false; keyboard?.hide() }
                            },
                        )
                    }
                    if (!draft.isNew) {
                        TextButton(onClick = { confirmDelete = true }) {
                            Text("Delete this area", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${place.name}?") },
            text = { Text("Time already recorded is kept.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete(place) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MoreOptions(
    link: String, onLink: (String) -> Unit, onPaste: (String) -> Unit, onUseLink: () -> Unit,
    latText: String, lngText: String, onLat: (String) -> Unit, onLng: (String) -> Unit, onUseCoordinates: () -> Unit,
) {
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Tip: in Google Maps, open the place, tap Share and choose Uptime Loom.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = link, onValueChange = onLink, singleLine = true, label = { Text("Google Maps link") },
            trailingIcon = {
                IconButton(onClick = { clipboard.getText()?.text?.let(onPaste) }) { Icon(Icons.Outlined.ContentPaste, contentDescription = "Paste") }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onUseLink() }),
            modifier = Modifier.fillMaxWidth(),
        )
        FilledTonalButton(onClick = onUseLink, enabled = link.isNotBlank()) { Text("Use this link") }
        Spacer(Modifier.size(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = latText, onValueChange = onLat, singleLine = true, label = { Text("Latitude") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
            OutlinedTextField(value = lngText, onValueChange = onLng, singleLine = true, label = { Text("Longitude") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
        }
        Button(onClick = onUseCoordinates, enabled = latText.isNotBlank() && lngText.isNotBlank()) { Text("Go to coordinates") }
    }
}

