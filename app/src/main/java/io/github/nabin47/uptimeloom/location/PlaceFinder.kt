package io.github.nabin47.uptimeloom.location

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.Locale
import kotlin.coroutines.resume

data class FoundPlace(val lat: Double, val lng: Double, val name: String?, val address: String?)

/** Turns search text, map links and shared places into coordinates. */
object PlaceFinder {

    /** Address / place search with Android's built-in geocoder (no API key). */
    suspend fun search(context: Context, query: String, max: Int = 5): List<FoundPlace> = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent() || query.isBlank()) return@withContext emptyList()
        val geocoder = Geocoder(context, Locale.getDefault())
        val addresses: List<Address> = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                suspendCancellableCoroutine { cont ->
                    geocoder.getFromLocationName(query, max, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) { if (cont.isActive) cont.resume(addresses.toList()) }
                        override fun onError(errorMessage: String?) { if (cont.isActive) cont.resume(emptyList()) }
                    })
                }
            } else legacyGeocode(geocoder, query, max)
        }.getOrDefault(emptyList())
        addresses.filter { it.hasLatitude() && it.hasLongitude() }.map { a ->
            FoundPlace(a.latitude, a.longitude, a.featureName?.takeIf { it.isNotBlank() && it.any(Char::isLetter) }, a.getAddressLine(0))
        }
    }

    @Suppress("DEPRECATION") // the only option before Android 13
    private fun legacyGeocode(geocoder: Geocoder, query: String, max: Int): List<Address> =
        geocoder.getFromLocationName(query, max).orEmpty()

    /**
     * Finds the place in a pasted link or shared text. Returns null if it
     * can't be worked out.
     */
    suspend fun fromLinkOrText(context: Context, text: String): FoundPlace? {
        val title = MapLinks.sharedTitle(text)
        MapLinks.coordinates(text)?.let { return FoundPlace(it.lat, it.lng, title, null) }
        val url = MapLinks.findUrl(text)
        var target = url
        if (url != null && MapLinks.isShortLink(url)) target = expand(url) ?: url
        if (target != null) {
            val decoded = runCatching { URLDecoder.decode(target, "UTF-8") }.getOrDefault(target)
            MapLinks.coordinates(decoded)?.let { return FoundPlace(it.lat, it.lng, title ?: MapLinks.placeQuery(decoded), null) }
            MapLinks.placeQuery(decoded)?.let { q -> search(context, q, 1).firstOrNull()?.let { return it.copy(name = title ?: q) } }
        }
        // Last resort: look up the place name / address from the shared text.
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && MapLinks.findUrl(it) == null }.toList()
        for (q in listOf(lines.take(2).joinToString(", "), lines.firstOrNull())) {
            if (q.isNullOrBlank()) continue
            search(context, q, 1).firstOrNull()?.let { return it.copy(name = title) }
        }
        return null
    }

    /** Follows a short link's redirects to the full map URL (no page content is read). */
    private suspend fun expand(url: String): String? = withContext(Dispatchers.IO) {
        var current = url
        repeat(6) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 8_000; readTimeout = 8_000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) UptimeLoom")
            }
            try {
                val code = conn.responseCode
                val next = conn.getHeaderField("Location") ?: return@withContext current
                if (code !in 300..399) return@withContext current
                current = URL(URL(current), next).toString()
                if (MapLinks.coordinates(runCatching { URLDecoder.decode(current, "UTF-8") }.getOrDefault(current)) != null) return@withContext current
            } catch (e: Exception) {
                return@withContext if (current != url) current else null
            } finally {
                conn.disconnect()
            }
        }
        current
    }
}
