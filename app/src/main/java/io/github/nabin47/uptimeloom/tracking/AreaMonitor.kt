package io.github.nabin47.uptimeloom.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import io.github.nabin47.uptimeloom.Graph
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

enum class TrackingMode { GEOFENCING, FALLBACK }

/**
 * Keeps Android watching the enabled work areas.
 *
 * Normal phones: Google Play services geofencing. Android itself watches the
 * areas and wakes the app only when you arrive or leave, so the app uses
 * almost no battery and needs no permanent notification.
 *
 * Phones without Google Play services (e.g. recent Huawei): a periodic check
 * every 15 minutes using the low-power network location. Coarser (times are
 * within ~15 minutes) but still light on battery.
 */
object AreaMonitor {

    fun mode(context: Context): TrackingMode =
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS)
            TrackingMode.GEOFENCING else TrackingMode.FALLBACK

    fun hasForegroundLocation(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasPreciseLocation(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasBackgroundLocation(context: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, GeofenceReceiver::class.java).setAction(GeofenceReceiver.ACTION)
        // Must be mutable: Play services adds the event details to it.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    /** (Re-)register every enabled area. Safe to call any time. */
    @SuppressLint("MissingPermission") // checked just below
    suspend fun sync(context: Context, reason: String) {
        Graph.init(context)
        val places = Graph.db.places().enabled()
        val mode = mode(context)
        Scheduling.setFallbackChecks(context, mode == TrackingMode.FALLBACK && places.isNotEmpty())
        if (mode == TrackingMode.FALLBACK) {
            Graph.tracker.log("setup", "Battery-saver mode (no Google Play services): ${places.size} area(s), $reason")
            if (places.isNotEmpty()) Scheduling.runFallbackCheckNow(context)
            return
        }
        val client = LocationServices.getGeofencingClient(context)
        val pi = pendingIntent(context)
        awaitTask { client.removeGeofences(pi) }
        if (places.isEmpty()) {
            Graph.db.presence().clear()
            return
        }
        if (!hasPreciseLocation(context)) {
            Graph.tracker.log("problem", "Location permission missing: areas are not being watched")
            return
        }
        val s = Graph.settings.current()
        val fences = places.take(100).map { p ->
            Geofence.Builder()
                .setRequestId(p.id.toString())
                .setCircularRegion(p.lat, p.lng, p.radiusM.toFloat())
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_DWELL or Geofence.GEOFENCE_TRANSITION_EXIT)
                .setLoiteringDelay((s.arriveDelayMin.coerceIn(1, 30)) * 60_000)
                .setNotificationResponsiveness(60_000)
                .build()
        }
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_DWELL)
            .addGeofences(fences)
            .build()
        val error = try {
            awaitTask { client.addGeofences(request, pi) }
        } catch (e: SecurityException) {
            e.message ?: "permission denied"
        }
        if (error == null) Graph.tracker.log("setup", "Watching ${fences.size} area(s) ($reason)")
        else Graph.tracker.log("problem", "Couldn't watch areas: $error")
    }

    /** One fallback check: where are we, and did that change? */
    suspend fun fallbackCheck(context: Context) {
        val places = Graph.db.places().enabled()
        if (places.isEmpty() || !hasForegroundLocation(context)) return
        val loc = Locator.current(context, preferLowPower = true) ?: run {
            Graph.tracker.log("problem", "Location unavailable for the 15-minute check")
            return
        }
        val now = System.currentTimeMillis()
        val inside = places.filter { p -> isInside(loc, p.lat, p.lng, p.radiusM) }.map { it.id }.toSet()
        val present = Graph.db.presence().all().map { it.placeId }.toSet()
        for (id in inside - present) Graph.tracker.onEnter(id, now, "15-min check")
        for (id in present - inside) Graph.tracker.onExit(id, now, "15-min check")
    }

    fun isInside(loc: Location, lat: Double, lng: Double, radiusM: Int): Boolean {
        val out = FloatArray(1)
        Location.distanceBetween(loc.latitude, loc.longitude, lat, lng, out)
        // Give the benefit of the doubt for a fuzzy fix, but not unlimited.
        val slack = loc.accuracy.coerceAtMost(radiusM.toFloat()).coerceAtMost(150f)
        return out[0] <= radiusM + slack
    }

    /** Waits for a Play services Task; returns null on success or an error message. */
    private suspend fun awaitTask(start: () -> com.google.android.gms.tasks.Task<Void>): String? =
        suspendCancellableCoroutine { cont ->
            start()
                .addOnSuccessListener { if (cont.isActive) cont.resume(null) }
                .addOnFailureListener { e -> if (cont.isActive) cont.resume(e.message ?: e.javaClass.simpleName) }
        }
}
