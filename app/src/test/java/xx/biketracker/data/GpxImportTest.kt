package xx.biketracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xx.biketracker.splitRouteSegments

class GpxImportTest {

    private fun gpx(body: String) =
        """<?xml version="1.0" encoding="UTF-8"?>
           <gpx version="1.1" creator="Test" xmlns="http://www.topografix.com/GPX/1/1">
           $body
           </gpx>"""

    @Test
    fun parsesPointsAndName() {
        val parsed = parseGpx(
            gpx(
                """<trk><name>Morning loop</name><trkseg>
                     <trkpt lat="50.1000000" lon="30.2000000"><ele>120.5</ele></trkpt>
                     <trkpt lat="50.1001000" lon="30.2001000"><ele>121.0</ele></trkpt>
                   </trkseg></trk>"""
            )
        )!!
        assertEquals("Morning loop", parsed.name)
        assertEquals(2, parsed.route.size)
        assertEquals(50.1, parsed.route[0].lat, 1e-9)
        assertEquals(30.2001, parsed.route[1].lon, 1e-9)
    }

    @Test
    fun eachSegmentAfterTheFirstStartsANewSegment() {
        val parsed = parseGpx(
            gpx(
                """<trk><trkseg>
                     <trkpt lat="50.0" lon="30.0"/>
                     <trkpt lat="50.001" lon="30.0"/>
                   </trkseg><trkseg>
                     <trkpt lat="50.5" lon="30.5"/>
                   </trkseg></trk>"""
            )
        )!!
        assertEquals(3, parsed.route.size)
        assertEquals(false, parsed.route[1].segmentStart)
        assertEquals(true, parsed.route[2].segmentStart) // first point of the second segment
    }

    @Test
    fun derivesSpeedFromTimestampsWithinASegment() {
        val parsed = parseGpx(
            gpx(
                """<trk><trkseg>
                     <trkpt lat="50.0000000" lon="30.0000000"><time>2026-07-20T10:00:00Z</time></trkpt>
                     <trkpt lat="50.0010000" lon="30.0000000"><time>2026-07-20T10:00:10Z</time></trkpt>
                   </trkseg></trk>"""
            )
        )!!
        assertEquals(0f, parsed.route[0].speedMps, 0f) // no previous point
        assertTrue("expected a positive derived speed", parsed.route[1].speedMps > 0f)
        assertEquals(1_784_541_600_000L, parsed.route[0].timeMillis) // 2026-07-20T10:00:00Z
    }

    @Test
    fun missingTimeLeavesSpeedAtZero() {
        val parsed = parseGpx(
            gpx(
                """<trk><trkseg>
                     <trkpt lat="50.0" lon="30.0"/>
                     <trkpt lat="50.001" lon="30.0"/>
                   </trkseg></trk>"""
            )
        )!!
        assertEquals(0f, parsed.route[1].speedMps, 0f)
        assertEquals(0L, parsed.route[1].timeMillis)
    }

    @Test
    fun aSparselyLoggedTrackStaysOneDrawableSegment() {
        // Plenty of apps log a trackpoint every few minutes. The file says nothing about a break —
        // one <trkseg>, five minutes apart, ~1.1 km each — so it is one coarse ride, not a row of
        // single points with no line to draw between them.
        val parsed = parseGpx(
            gpx(
                """<trk><trkseg>
                     <trkpt lat="50.0000000" lon="30.0000000"><time>2026-07-20T10:00:00Z</time></trkpt>
                     <trkpt lat="50.0100000" lon="30.0000000"><time>2026-07-20T10:05:00Z</time></trkpt>
                     <trkpt lat="50.0200000" lon="30.0000000"><time>2026-07-20T10:10:00Z</time></trkpt>
                   </trkseg></trk>"""
            )
        )!!
        assertFalse(parsed.route.drop(1).any { it.segmentStart })
        assertEquals(1, splitRouteSegments(parsed.route).size)
        assertTrue("expected a plotted speed", parsed.route[1].speedMps > 0f)
    }

    @Test
    fun aStepNobodyCouldHaveRiddenSplitsAndCarriesNoSpeed() {
        // Half a degree of latitude in two seconds: a glitch in the file, not a journey. Splitting
        // keeps the map from drawing a line across it, and the speed it implies is not plotted.
        val parsed = parseGpx(
            gpx(
                """<trk><trkseg>
                     <trkpt lat="50.0000000" lon="30.0000000"><time>2026-07-20T10:00:00Z</time></trkpt>
                     <trkpt lat="50.5000000" lon="30.0000000"><time>2026-07-20T10:00:02Z</time></trkpt>
                   </trkseg></trk>"""
            )
        )!!
        assertTrue(parsed.route[1].segmentStart)
        assertEquals(0f, parsed.route[1].speedMps, 0f)
        assertEquals(2, splitRouteSegments(parsed.route).size)
    }

    @Test
    fun elapsedOffsetsFollowTheTimestamps() {
        val parsed = parseGpx(
            gpx(
                """<trk><trkseg>
                     <trkpt lat="50.0000000" lon="30.0000000"><time>2026-07-20T10:00:00Z</time></trkpt>
                     <trkpt lat="50.0010000" lon="30.0000000"><time>2026-07-20T10:00:10Z</time></trkpt>
                   </trkseg><trkseg>
                     <trkpt lat="50.0020000" lon="30.0000000"><time>2026-07-20T10:00:40Z</time></trkpt>
                   </trkseg></trk>"""
            )
        )!!
        // The offset spans the whole track, the <trkseg> break included.
        assertEquals(listOf(0L, 10_000L, 40_000L), parsed.route.map { it.elapsedMillis })
    }

    @Test
    fun aBackwardTimestampNeverRewindsTheElapsedOffset() {
        val parsed = parseGpx(
            gpx(
                """<trk><trkseg>
                     <trkpt lat="50.0000000" lon="30.0000000"><time>2026-07-20T10:00:20Z</time></trkpt>
                     <trkpt lat="50.0001000" lon="30.0000000"><time>2026-07-20T10:00:10Z</time></trkpt>
                     <trkpt lat="50.0002000" lon="30.0000000"><time>2026-07-20T10:00:15Z</time></trkpt>
                   </trkseg></trk>"""
            )
        )!!
        assertEquals(listOf(0L, 0L, 5_000L), parsed.route.map { it.elapsedMillis })
        assertEquals(0f, parsed.route[1].speedMps, 0f) // no measurable step, so no speed
    }

    @Test
    fun anUntimedTrackCarriesNoElapsedTimeAndNeverSplits() {
        val parsed = parseGpx(
            gpx(
                """<trk><trkseg>
                     <trkpt lat="50.0" lon="30.0"/>
                     <trkpt lat="50.1" lon="30.0"/>
                     <trkpt lat="50.2" lon="30.0"/>
                   </trkseg></trk>"""
            )
        )!!
        assertEquals(listOf(0L, 0L, 0L), parsed.route.map { it.elapsedMillis })
        assertEquals(1, splitRouteSegments(parsed.route).size)
    }

    /** Metadata as a real exporter writes it: the file's name, then the person who made it. */
    private fun metadata(name: String?) =
        """<metadata>${name?.let { "<name>$it</name>" } ?: ""}
             <author><name>Some Exporter</name></author>
           </metadata>"""

    private val oneSegment =
        """<trkseg><trkpt lat="50.0" lon="30.0"/><trkpt lat="50.001" lon="30.0"/></trkseg>"""

    @Test
    fun theTracksOwnNameWins() {
        val parsed = parseGpx(
            gpx("${metadata("bike-2026-07-20.gpx")}<trk><name>Morning loop</name>$oneSegment</trk>")
        )!!
        assertEquals("Morning loop", parsed.name)
    }

    @Test
    fun theFileNameStandsInWhenTheTrackHasNone() {
        val parsed = parseGpx(gpx("${metadata("bike-2026-07-20.gpx")}<trk>$oneSegment</trk>"))!!
        assertEquals("bike-2026-07-20.gpx", parsed.name)
    }

    @Test
    fun theAuthorIsNeverMistakenForTheTrackName() {
        // <author><name> sits right beside the file's own name; a document-wide search for the
        // first <name> would label the ride with whoever exported it.
        val parsed = parseGpx(gpx("${metadata(null)}<trk>$oneSegment</trk>"))!!
        assertNull(parsed.name)
    }

    @Test
    fun gpxOneDotZeroKeepsItsFileNameDirectlyUnderTheRoot() {
        val parsed = parseGpx(gpx("<name>Old export</name><trk>$oneSegment</trk>"))!!
        assertEquals("Old export", parsed.name)
    }

    @Test
    fun malformedXmlReturnsNull() {
        assertNull(parseGpx("<gpx><trk><trkseg>"))
    }

    @Test
    fun aTrackWithNoPointsReturnsNull() {
        assertNull(parseGpx(gpx("<trk><name>Empty</name></trk>")))
    }

    @Test
    fun roundTripsThroughBuildGpx() {
        val trip = Trip(
            startTime = 1_700_000_000_000L,
            endTime = 1_700_000_000_000L,
            distanceMeters = 0.0,
            movingTimeMillis = 0L,
            maxSpeedMps = 0.0,
            title = "Ride & ride",
        )
        val points = listOf(
            TrackPoint(tripId = 1, lat = 50.1234567, lon = 30.7654321, time = 0, speedMps = 0f, elapsedMillis = 0),
            TrackPoint(tripId = 1, lat = 50.1240000, lon = 30.7660000, time = 0, speedMps = 0f, elapsedMillis = 1_000),
            TrackPoint(tripId = 1, lat = 50.2000000, lon = 30.8000000, time = 0, speedMps = 0f, elapsedMillis = 2_000, segmentStart = true),
        )
        val parsed = parseGpx(buildGpx(trip, points))!!
        assertEquals("Ride & ride", parsed.name)
        assertEquals(3, parsed.route.size)
        assertEquals(50.1234567, parsed.route[0].lat, 1e-9)
        assertEquals(true, parsed.route[2].segmentStart)
    }
}
