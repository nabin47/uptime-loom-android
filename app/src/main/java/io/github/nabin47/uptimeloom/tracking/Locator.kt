package io.github.nabin47.uptimeloom.tracking

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/** One-off location fixes (setting up an area, fallback checks). */
object Locator {

    @SuppressLint("MissingPermission") // callers check permission first
    suspend fun current(context: Context, preferLowPower: Boolean): Location? = withTimeoutOrNull(30_000) {
        if (!AreaMonitor.hasForegroundLocation(context)) return@withTimeoutOrNull null
        if (AreaMonitor.mode(context) == TrackingMode.GEOFENCING) {
            val client = LocationServices.getFusedLocationProviderClient(context)
            val cts = CancellationTokenSource()
            val priority = if (preferLowPower) Priority.PRIORITY_BALANCED_POWER_ACCURACY else Priority.PRIORITY_HIGH_ACCURACY
            suspendCancellableCoroutine<Location?> { cont ->
                cont.invokeOnCancellation { cts.cancel() }
                client.getCurrentLocation(priority, cts.token)
                    .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                    .addOnFailureListener { if (cont.isActive) cont.resume(null) }
            }
        } else {
            platformFix(context, preferLowPower)
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION") // requestSingleUpdate is the only option before Android 11
    private suspend fun platformFix(context: Context, preferLowPower: Boolean): Location? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = buildList {
            if (preferLowPower) { add(LocationManager.NETWORK_PROVIDER); add(LocationManager.GPS_PROVIDER) }
            else { add(LocationManager.GPS_PROVIDER); add(LocationManager.NETWORK_PROVIDER) }
        }.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        // A recent cached fix is good enough and costs nothing.
        val recent = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < 5 * 60_000 }
            .minByOrNull { it.accuracy }
        if (recent != null) return recent
        val provider = providers.firstOrNull() ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return suspendCancellableCoroutine<Location?> { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                val executor = Executors.newSingleThreadExecutor()
                lm.getCurrentLocation(provider, signal, executor) { loc ->
                    executor.shutdown()
                    if (cont.isActive) cont.resume(loc)
                }
            }
        }
        return suspendCancellableCoroutine<Location?> { cont ->
            val listener = object : android.location.LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (cont.isActive) cont.resume(location)
                }
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            cont.invokeOnCancellation { lm.removeUpdates(listener) }
            lm.requestSingleUpdate(provider, listener, android.os.Looper.getMainLooper())
        }
    }
}
