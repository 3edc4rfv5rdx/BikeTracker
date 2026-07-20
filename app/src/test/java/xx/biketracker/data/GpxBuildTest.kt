package xx.biketracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class GpxBuildTest {

    private fun trip(startTime: Long = 1_000_000L, title: String? = null, note: String? = null) =
        Trip(
            startTime = startTime,
            endTime = startTime,
            distanceMeters = 0.0,
            movingTimeMillis = 0L,
            maxSpeedMps = 0.0,
            title = title,
            note = note,
        )

    private fun tp(
        time: Long = 0L,
        altitude: Double? = null,
        segmentStart: Boolean = false,
        elapsedMillis: Long? = null,
    ) = TrackPoint(
        tripId = 1,
        lat = 50.1234567,
        lon = 30.7654321,
        time = time,
        speedMps = 5f,
        altitudeMeters = altitude,
        segmentStart = segmentStart,
        elapsedMillis = elapsedMillis,
    )

    private fun String.count(sub: String): Int = split(sub).size - 1

    private fun isoUtc(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(millis))

    private fun times(gpx: String): List<String> =
        Regex("<time>(.*?)</time>").findAll(gpx).map { it.groupValues[1] }.toList()

    @Test
    fun escapesXmlSpecialsInTitleAndNote() {
        val gpx = buildGpx(trip(title = "A & B <x>", note = "q > \"y\" & z"), listOf(tp()))
        assertTrue(gpx.contains("<name>A &amp; B &lt;x&gt;</name>"))
        assertTrue(gpx.contains("<desc>q &gt; \"y\" &amp; z</desc>"))
        assertFalse(gpx.contains("<x>")) // the raw angle brackets must not survive
    }

    @Test
    fun omitsEleAndTimeWhenAbsent() {
        val gpx = buildGpx(trip(), listOf(tp(time = 0, altitude = null, elapsedMillis = null)))
        assertFalse(gpx.contains("<ele>"))
        assertFalse(gpx.contains("<time>"))
        assertEquals(1, gpx.count("<trkpt"))
    }

    @Test
    fun emptyTrackHasNoSegmentsOrPoints() {
        val gpx = buildGpx(trip(), emptyList())
        assertTrue(gpx.contains("<trk>"))
        assertEquals(0, gpx.count("<trkseg>"))
        assertEquals(0, gpx.count("<trkpt"))
    }

    @Test
    fun singlePointTrackHasOneClosedSegment() {
        val gpx = buildGpx(trip(), listOf(tp(elapsedMillis = 0)))
        assertEquals(1, gpx.count("<trkseg>"))
        assertEquals(1, gpx.count("</trkseg>"))
        assertEquals(1, gpx.count("<trkpt"))
    }

    @Test
    fun legacyWallGapStartsANewSegment() {
        // No elapsed metadata, so a > 10 s wall gap is a boundary via the legacy heuristic.
        val gpx = buildGpx(
            trip(),
            listOf(
                tp(time = 1_000, elapsedMillis = null),
                tp(time = 2_000, elapsedMillis = null),
                tp(time = 30_000, elapsedMillis = null),
            ),
        )
        assertEquals(2, gpx.count("<trkseg>"))
    }

    @Test
    fun explicitShortPauseStartsANewSegment() {
        // A flagged boundary splits even when the wall gap is tiny.
        val gpx = buildGpx(
            trip(),
            listOf(
                tp(time = 1_000, elapsedMillis = 0),
                tp(time = 2_000, elapsedMillis = 1_000),
                tp(time = 2_500, elapsedMillis = 1_500, segmentStart = true),
            ),
        )
        assertEquals(2, gpx.count("<trkseg>"))
    }

    @Test
    fun pointTimesAreMonotonicDespiteABackwardWallClock() {
        // Wall time jumps around, but elapsed metadata gives a monotonic exported time.
        val start = 1_600_000_000_000L
        val gpx = buildGpx(
            trip(startTime = start),
            listOf(
                tp(time = 9_000, elapsedMillis = 0),
                tp(time = 3_000, elapsedMillis = 1_000),
                tp(time = 20_000, elapsedMillis = 2_000),
            ),
        )
        val stamps = times(gpx)
        assertEquals(3, stamps.size)
        assertEquals(stamps, stamps.sorted()) // never runs backward
        assertEquals(isoUtc(start), stamps[0])
        assertEquals(isoUtc(start + 2_000), stamps[2])
    }

    @Test
    fun legacyRowsFallBackToTheRecordedWallTime() {
        val gpx = buildGpx(trip(), listOf(tp(time = 1_700_000_000_000L, elapsedMillis = null)))
        assertEquals(listOf(isoUtc(1_700_000_000_000L)), times(gpx))
    }
}
