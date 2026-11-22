package io.github.nabin47.uptimeloom.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapLinksTest {
    private fun assertCoords(text: String, lat: Double, lng: Double) {
        val c = MapLinks.coordinates(text) ?: error("no coordinates in $text")
        assertEquals(lat, c.lat, 1e-6); assertEquals(lng, c.lng, 1e-6)
    }

    @Test fun placeLinkPrefersExactPlaceOverMapCentre() =
        assertCoords("https://www.google.com/maps/place/BUET/@23.7264,90.3900,17z/data=!3m1!4b1!4m6!3m5!1s0x0:0x0!8m2!3d23.7265123!4d90.3925456", 23.7265123, 90.3925456)

    @Test fun mapCentre() = assertCoords("https://www.google.com/maps/@23.8103,90.4125,15z", 23.8103, 90.4125)
    @Test fun queryParam() = assertCoords("https://maps.google.com/?q=23.81,90.41", 23.81, 90.41)
    @Test fun encodedComma() = assertCoords("https://www.google.com/maps/search/?api=1&query=23.81%2C90.41", 23.81, 90.41)
    @Test fun geoUri() = assertCoords("geo:51.5007,-0.1246?z=17", 51.5007, -0.1246)
    @Test fun plainText() = assertCoords(" 40.7484, -73.9857 ", 40.7484, -73.9857)

    @Test fun sharedTextWithShortLink() {
        val text = "Bangladesh University of Engineering and Technology\nPolashi, Dhaka 1205\nhttps://maps.app.goo.gl/AbCd123"
        val url = MapLinks.findUrl(text)!!
        assertTrue(MapLinks.isShortLink(url))
        assertNull(MapLinks.coordinates(text))
        assertEquals("Bangladesh University of Engineering and Technology", MapLinks.sharedTitle(text))
    }

    @Test fun placeNameFromLinkWithoutCoordinates() {
        assertEquals("Dhaka University", MapLinks.placeQuery("https://maps.google.com/?q=Dhaka+University&ftid=0x1:0x2"))
        assertEquals("Shahbag, Dhaka", MapLinks.placeQuery("https://www.google.com/maps/place/Shahbag,+Dhaka/data=!4m2"))
    }

    @Test fun rejectsNonsense() {
        assertNull(MapLinks.coordinates("hello world"))
        assertNull(MapLinks.coordinates("123.0, 500.0"))
    }
}
