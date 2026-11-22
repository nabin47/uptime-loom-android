package io.github.nabin47.uptimeloom.location

import java.net.URLDecoder

data class LatLng(val lat: Double, val lng: Double)

/**
 * Understands what people paste or share from map apps: Google Maps links
 * (full or short), geo: links, and plain "lat, lng" text. Pure Kotlin.
 */
object MapLinks {
    private const val NUM = "(-?\\d{1,3}(?:\\.\\d+)?)"

    // Most precise first: "!3d..!4d.." is the place itself, "@lat,lng" is the map centre.
    private val patterns = listOf(
        Regex("!3d$NUM!4d$NUM"),
        Regex("[?&](?:q|query|ll|center|destination|daddr|sll)=(?:loc:)?$NUM(?:,|%2C)\\s*(?:\\+)?$NUM"),
        Regex("@$NUM,$NUM"),
        Regex("geo:$NUM,$NUM"),
        Regex("^\\s*$NUM\\s*,\\s*$NUM\\s*$"),
    )

    fun findUrl(text: String): String? =
        Regex("(https?://[^\\s<>\"]+|geo:[^\\s<>\"]+)").find(text)?.value?.trimEnd('.', ',', ')')

    /** Coordinates written in the text or link, if any. */
    fun coordinates(text: String): LatLng? {
        for (p in patterns) {
            val m = p.find(text) ?: continue
            val lat = m.groupValues[1].toDoubleOrNull() ?: continue
            val lng = m.groupValues[2].toDoubleOrNull() ?: continue
            if (lat in -90.0..90.0 && lng in -180.0..180.0 && !(lat == 0.0 && lng == 0.0)) return LatLng(lat, lng)
        }
        return null
    }

    /** Short links (maps.app.goo.gl) must be opened to see where they point. */
    fun isShortLink(url: String): Boolean =
        Regex("^https?://(maps\\.app\\.goo\\.gl|goo\\.gl/maps|g\\.co/kgs)/", RegexOption.IGNORE_CASE).containsMatchIn(url)

    /** A place name or address in the link, to look up when there are no coordinates. */
    fun placeQuery(url: String): String? {
        Regex("[?&](?:q|query|destination|daddr)=([^&#]+)").find(url)?.let { m ->
            val q = decode(m.groupValues[1])
            if (coordinates(q) == null && q.isNotBlank()) return q
        }
        Regex("/maps/place/([^/@?#]+)").find(url)?.let { return decode(it.groupValues[1]).ifBlank { null } }
        Regex("/maps/search/([^/@?#]+)").find(url)?.let { return decode(it.groupValues[1]).ifBlank { null } }
        return null
    }

    /** Text shared from Google Maps usually starts with the place name. */
    fun sharedTitle(text: String): String? =
        text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && findUrl(it) == null }

    private fun decode(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s).replace('+', ' ').trim()
}
