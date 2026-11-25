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

    @SuppressLint("InlinedApi") // FLAG_MUTABLE is simply ignored before Android 12
    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, GeofenceReceiver::class.java).setAction(GeofenceReceiver.ACTION)
        // Must be mutable: Play services adds the event details to it.
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
    }

    /** (Re-)register every enabled area. Safe to call any time. */
    @SuppressLint("MissingPermission") // checked just below
    /**
     * True when counting happens only when the app is opened: chosen in
     * Settings, or because location isn't allowed "all the time".
     */
    suspend fun countsOnOpenOnly(context: Context): Boolean {
        Graph.init(context)
        return Graph.settings.current().trackOnOpenOnly || !hasBackgroundLocation(context)
    }

    suspend fun sync(context: Context, reason: String) {
        Graph.init(context)
        val places = Graph.db.places().enabled()
        if (Graph.settings.current().trackOnOpenOnly) {
            // The user chose "when I open the app": nothing watches in the background.
            Scheduling.setFallbackChecks(context, false)
            if (mode(context) == TrackingMode.GEOFENCING) {
                awaitTask { LocationServices.getGeofencingClient(context).removeGeofences(pendingIntent(context)) }
            }
            Graph.tracker.log("setup", "Counting when the app is opened (${places.size} area(s), $reason)")
            return
        }
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

        // Android re-reports "you're inside" for freshly added areas, but never
        // "you're already outside". If we think we're inside (or a session is
        // running), check once so a stale state can't keep a session open.
        if (Graph.db.presence().all().isNotEmpty()) {
            checkNow(context, "re-check after setup", preferLowPower = false)
        }
    }

    /**
     * One location check: where are we now, and did that change? Used by the
     * 15-minute fallback (phones without Google Play services) and when the
     * app is opened in "when I open the app" mode. Returns false if there was
     * no location fix.
     */
    suspend fun checkNow(context: Context, via: String, preferLowPower: Boolean): Boolean {
        val places = Graph.db.places().enabled()
        if (places.isEmpty() || !hasForegroundLocation(context)) return true
        val loc = Locator.current(context, preferLowPower = preferLowPower) ?: run {
            Graph.tracker.log("problem", "Location unavailable ($via)")
            return false
        }
        val now = System.currentTimeMillis()
        val inside = places.filter { p -> isInside(loc, p.lat, p.lng, p.radiusM) }.map { it.id }.toSet()
        val present = Graph.db.presence().all().map { it.placeId }.toSet()
        for (id in inside - present) Graph.tracker.onEnter(id, now, via)
        for (id in present - inside) Graph.tracker.onExit(id, now, via)
        return true
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
