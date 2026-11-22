package io.github.nabin47.uptimeloom.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nabin47.uptimeloom.data.Place
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

enum class Tab(val label: String, val icon: ImageVector) {
    Today("Today", Icons.Outlined.Home),
    Stats("Stats", Icons.Outlined.BarChart),
    Log("Log", Icons.AutoMirrored.Outlined.List),
    Areas("Areas", Icons.Outlined.Place),
    Settings("Settings", Icons.Outlined.Settings),
}

@Composable
fun AppRoot(state: UiState, vm: MainViewModel) {
    var tab by rememberSaveable { mutableStateOf(Tab.Today) }
    var areaDraft by remember { mutableStateOf<AreaDraft?>(null) }
    val newArea = { shared: String? -> AreaDraft(Place(name = "", lat = 0.0, lng = 0.0, radiusM = 150), shared) }

    // A place shared from Google Maps (or any app): open it as a new work area.
    val shared by vm.sharedText.collectAsStateWithLifecycle()
    LaunchedEffect(shared) {
        shared?.let { tab = Tab.Areas; areaDraft = newArea(it); vm.consumeShared() }
    }

    areaDraft?.let { draft ->
        AreaEditorScreen(
            draft = draft,
            onClose = { areaDraft = null },
            onSave = { vm.savePlace(it); areaDraft = null },
            onDelete = { vm.deletePlace(it); areaDraft = null },
        )
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            if (!state.loaded) return@Box
            when (tab) {
                Tab.Today -> TodayScreen(state, vm, openAreas = { tab = Tab.Areas; if (state.places.isEmpty()) areaDraft = newArea(null) })
                Tab.Stats -> StatsScreen(state, vm)
                Tab.Log -> LogScreen(state, vm)
                Tab.Areas -> AreasScreen(state, vm, onEdit = { p -> areaDraft = if (p == null) newArea(null) else AreaDraft(p) })
                Tab.Settings -> SettingsScreen(state, vm)
            }
        }
    }
}
