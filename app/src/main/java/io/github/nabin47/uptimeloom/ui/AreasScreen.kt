package io.github.nabin47.uptimeloom.ui

import android.content.Intent
import android.location.Address
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.nabin47.uptimeloom.data.Place
import io.github.nabin47.uptimeloom.tracking.AreaMonitor
import io.github.nabin47.uptimeloom.tracking.Locator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

@Composable
fun AreasScreen(state: UiState, vm: MainViewModel) {
    var editing by remember { mutableStateOf<Place?>(null) }
    var confirmDelete by remember { mutableStateOf<Place?>(null) }
    val inside = state.presence.map { it.placeId }.toSet()

    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("Work areas", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "Time counts while you're inside any of these. You can add as many as you need: office, other buildings, a second campus.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (state.places.isEmpty()) item {
                SectionCard {
                    Text("No work areas yet.", style = MaterialTheme.typography.bodyLarge)
                    Text("The easiest way: go to your workplace, tap \"Add area\" and use your current location.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(state.places, key = { it.id }) { p ->
                SectionCard(modifier = Modifier.clickable { editing = p }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${p.radiusM} m radius" + if (p.id in inside) " · you're here now" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (p.id in inside) Tone.good else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = p.enabled, onCheckedChange = { vm.savePlace(p.copy(enabled = it)) })
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { editing = Place(name = "", lat = 0.0, lng = 0.0, radiusM = 150) },
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("Add area") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    editing?.let { p ->
        AreaEditor(p, onDismiss = { editing = null }, onSave = { vm.savePlace(it); editing = null },
            onDelete = { confirmDelete = p; editing = null })
    }
    confirmDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${p.name}?") },
            text = { Text("Time already recorded is kept.") },
            confirmButton = { TextButton(onClick = { vm.deletePlace(p); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AreaEditor(place: Place, onDismiss: () -> Unit, onSave: (Place) -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(place.name) }
    var lat by remember { mutableStateOf(if (place.id == 0L) "" else place.lat.toString()) }
    var lng by remember { mutableStateOf(if (place.id == 0L) "" else place.lng.toString()) }
    var radius by remember { mutableStateOf(place.radiusM.toFloat()) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Address>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val latD = lat.trim().replace(',', '.').toDoubleOrNull()
    val lngD = lng.trim().replace(',', '.').toDoubleOrNull()
    val valid = name.isNotBlank() && latD != null && lngD != null && latD in -90.0..90.0 && lngD in -180.0..180.0 && !(latD == 0.0 && lngD == 0.0)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (place.id == 0L) "Add work area" else "Edit work area") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, label = { Text("Name, e.g. Office") }, singleLine = true)

                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        if (!AreaMonitor.hasForegroundLocation(context)) {
                            message = "Allow location first (Today → Finish setting up)."
                            return@OutlinedButton
                        }
                        busy = true; message = null
                        scope.launch {
                            val loc = Locator.current(context, preferLowPower = false)
                            busy = false
                            if (loc == null) message = "Couldn't get your location. Is location turned on?"
                            else {
                                lat = "%.6f".format(Locale.ROOT, loc.latitude); lng = "%.6f".format(Locale.ROOT, loc.longitude)
                                message = "Using your current location (accurate to ~${loc.accuracy.toInt()} m)."
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.MyLocation, contentDescription = null)
                    Text("  Use my current location")
                }

                if (Geocoder.isPresent()) {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        label = { Text("…or search an address") },
                        trailingIcon = {
                            IconButton(enabled = query.isNotBlank() && !busy, onClick = {
                                busy = true; message = null
                                scope.launch {
                                    results = geocode(context, query)
                                    busy = false
                                    if (results.isEmpty()) message = "No results. Try a fuller address."
                                }
                            }) { Icon(Icons.Outlined.Search, contentDescription = "Search") }
                        },
                    )
                    results.forEach { a ->
                        TextButton(onClick = {
                            lat = "%.6f".format(Locale.ROOT, a.latitude); lng = "%.6f".format(Locale.ROOT, a.longitude)
                            if (name.isBlank()) name = a.featureName ?: ""
                            results = emptyList(); message = "Location set from the address."
                        }) { Text(a.getAddressLine(0) ?: "${a.latitude}, ${a.longitude}") }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = lat, onValueChange = { lat = it }, label = { Text("Latitude") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                    OutlinedTextField(value = lng, onValueChange = { lng = it }, label = { Text("Longitude") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                }

                Text("Radius: ${radius.toInt()} m", style = MaterialTheme.typography.bodyMedium)
                Slider(value = radius, onValueChange = { radius = (it / 50f).toInt() * 50f }, valueRange = 100f..1000f, steps = 17)
                Text("150 m or more works best. Use a bigger radius for a large campus.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                if (latD != null && lngD != null) {
                    TextButton(onClick = {
                        val uri = Uri.parse("geo:$latD,$lngD?q=$latD,$lngD(${Uri.encode(name.ifBlank { "Work area" })})")
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                            .onFailure { Toast.makeText(context, "No maps app found", Toast.LENGTH_SHORT).show() }
                    }) { Text("Check on a map") }
                }
                if (busy) CircularProgressIndicator()
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(enabled = valid, onClick = {
                onSave(place.copy(name = name.trim(), lat = latD!!, lng = lngD!!, radiusM = radius.toInt()))
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (place.id != 0L) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** Address search with Android's built-in geocoder (no API key needed). */
private suspend fun geocode(context: android.content.Context, query: String): List<Address> = withContext(Dispatchers.IO) {
    val geocoder = Geocoder(context, Locale.getDefault())
    runCatching {
        if (Build.VERSION.SDK_INT >= 33) {
            suspendCancellableCoroutine { cont ->
                geocoder.getFromLocationName(query, 5, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) { if (cont.isActive) cont.resume(addresses.toList()) }
                    override fun onError(errorMessage: String?) { if (cont.isActive) cont.resume(emptyList()) }
                })
            }
        } else {
            legacyGeocode(geocoder, query)
        }
    }.getOrDefault(emptyList())
}

@Suppress("DEPRECATION") // the only option before Android 13
private fun legacyGeocode(geocoder: Geocoder, query: String): List<Address> =
    geocoder.getFromLocationName(query, 5).orEmpty()
