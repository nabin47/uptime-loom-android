package io.github.nabin47.uptimeloom.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent
import io.github.nabin47.uptimeloom.Graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Runs [block] off the main thread while keeping the receiver alive. */
private fun BroadcastReceiver.runAsync(context: Context, block: suspend () -> Unit) {
    val pending = goAsync()
    Graph.init(context)
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        try { block() } finally { pending.finish() }
    }
}

/** Play services tells us we arrived in (dwelled) or left a work area. */
class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        runAsync(context) {
            val tracker = Graph.tracker
            if (event.hasError()) {
                val msg = GeofenceStatusCodes.getStatusCodeString(event.errorCode)
                tracker.log("problem", "Area monitoring paused: $msg (is location turned on?)")
                // Location was switched off. Keep what we knew: areas are re-added
                // (and the position re-checked) the next time they're set up.
                return@runAsync
            }
            val when_ = event.triggeringLocation?.time?.takeIf { it > 0 } ?: System.currentTimeMillis()
            val arriveDelayMs = Graph.settings.current().arriveDelayMin * 60_000L
            val ids = event.triggeringGeofences.orEmpty().mapNotNull { it.requestId.toLongOrNull() }
            for (id in ids) {
                when (event.geofenceTransition) {
                    // "Dwell" fires after you've been inside for the arrive delay,
                    // so you actually arrived that much earlier.
                    Geofence.GEOFENCE_TRANSITION_DWELL -> tracker.onEnter(id, when_ - arriveDelayMs, "arrived")
                    Geofence.GEOFENCE_TRANSITION_EXIT -> tracker.onExit(id, when_, "left")
                }
            }
        }
    }

    companion object {
        const val ACTION = "io.github.nabin47.uptimeloom.GEOFENCE"
    }
}

/** Geofences don't survive a restart or an app update: re-add them. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        runAsync(context) {
            if (action == Intent.ACTION_BOOT_COMPLETED) {
                val bootTime = System.currentTimeMillis() - SystemClock.elapsedRealtime()
                Graph.tracker.onBoot(bootTime)
            }
            AreaMonitor.sync(context, reason = if (action == Intent.ACTION_BOOT_COMPLETED) "after restart" else "after update")
        }
    }
}
