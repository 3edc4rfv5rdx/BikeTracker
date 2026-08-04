package xx.biketracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xx.biketracker.MAX_GPX_FILE_BYTES
import xx.biketracker.MAX_IMPORTED_POINTS
import xx.biketracker.splitRouteSegments

class GpxImportTest {

    /** Every test parses from bytes, as the import does; the parser never sees a whole document. */
    private fun parse(xml: String, maxPoints: Int = MAX_IMPORTED_POINTS) =
        parseGpx(xml.byteInputStream(), maxPoints)

    private fun gpx(body: String) =
        """<?xml version="1.0" encoding="UTF-8"?>
           <gpx version="1.1" creator="Test" xmlns="http://www.topografix.com/GPX/1/1">
           $body
           </gpx>"""

    @Test
    fun parsesPointsAndName() {
        val parsed = parse(
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
        val parsed = parse(
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
        val parsed = parse(
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
        val parsed = parse(
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
        val parsed = parse(
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
        val parsed = parse(
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
        val parsed = parse(
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
        val parsed = parse(
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
        val parsed = parse(
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
        val parsed = parse(
            gpx("${metadata("bike-2026-07-20.gpx")}<trk><name>Morning loop</name>$oneSegment</trk>")
        )!!
        assertEquals("Morning loop", parsed.name)
    }

    @Test
    fun theFileNameStandsInWhenTheTrackHasNone() {
        val parsed = parse(gpx("${metadata("bike-2026-07-20.gpx")}<trk>$oneSegment</trk>"))!!
        assertEquals("bike-2026-07-20.gpx", parsed.name)
    }

    @Test
    fun theAuthorIsNeverMistakenForTheTrackName() {
        // <author><name> sits right beside the file's own name; a document-wide search for the
        // first <name> would label the ride with whoever exported it.
        val parsed = parse(gpx("${metadata(null)}<trk>$oneSegment</trk>"))!!
        assertNull(parsed.name)
    }

    @Test
    fun gpxOneDotZeroKeepsItsFileNameDirectlyUnderTheRoot() {
        val parsed = parse(gpx("<name>Old export</name><trk>$oneSegment</trk>"))!!
        assertEquals("Old export", parsed.name)
    }

    // --- Several tracks in one file ---

    private fun twoTracks(fileName: String?) = gpx(
        (fileName?.let { metadata(it) } ?: "") +
            """<trk><name>Day one</name><trkseg>
                 <trkpt lat="50.0000000" lon="30.0000000"/>
                 <trkpt lat="50.0010000" lon="30.0000000"/>
               </trkseg></trk>
               <trk><name>Day two</name><trkseg>
                 <trkpt lat="51.0000000" lon="31.0000000"/>
                 <trkpt lat="51.0010000" lon="31.0000000"/>
               </trkseg></trk>"""
    )

    @Test
    fun everyTrackInAFileIsShown() {
        // A viewer that showed one third of what was picked would be the stranger answer. The
        // <trkseg> divisions keep the days apart, so no line is drawn from one to the next.
        val parsed = parse(twoTracks(fileName = "tour.gpx"))!!

        assertEquals(4, parsed.route.size)
        assertEquals(listOf(true, false, true, false), parsed.route.map { it.segmentStart })
        assertEquals(2, splitRouteSegments(parsed.route).size)
    }

    @Test
    fun severalTracksAreNamedAfterTheFile() {
        // "Day one" names a third of what is on screen; the file names all of it.
        assertEquals("tour.gpx", parse(twoTracks(fileName = "tour.gpx"))!!.name)
    }

    @Test
    fun severalTracksWithNoFileNameAreLeftUnnamed() {
        // The map then calls it an imported track, which is at least true.
        assertNull(parse(twoTracks(fileName = null))!!.name)
    }

    @Test
    fun malformedXmlReturnsNull() {
        assertNull(parse("<gpx><trk><trkseg>"))
    }

    @Test
    fun somethingThatIsNotXmlAtAllReturnsNull() {
        // The picker hands over whole files, and the wrong one is an easy pick.
        assertNull(parse("just some text"))
        assertNull(parse("PK\u0003\u0004 something that is not a document"))
        assertNull(parse(""))
    }

    @Test
    fun xmlThatIsNotGpxReturnsNull() {
        assertNull(parse("<html><body><trkpt lat=\"50.0\" lon=\"30.0\"/></body></html>"))
        assertNull(parse("<?xml version=\"1.0\"?><rss><channel><title>Not a ride</title></channel></rss>"))
    }

    @Test
    fun aTrackWithNoPointsReturnsNull() {
        assertNull(parse(gpx("<trk><name>Empty</name></trk>")))
    }

    // --- Size and point caps ---

    @Test
    fun onlyAPlausiblySizedDocumentIsOpenedAtAll() {
        assertTrue(isImportableGpxSize(1_024L))
        assertTrue(isImportableGpxSize(MAX_GPX_FILE_BYTES))
        assertFalse(isImportableGpxSize(MAX_GPX_FILE_BYTES + 1))
        // A provider that will not say leaves it to the point cap below.
        assertTrue(isImportableGpxSize(null))
    }

    private fun trackOf(points: Int) = gpx(
        "<trk><trkseg>" +
            (0 until points).joinToString("") { "<trkpt lat=\"50.${it % 100}\" lon=\"30.0\"/>" } +
            "</trkseg></trk>"
    )

    @Test
    fun aTrackEndingExactlyOnTheCapIsWhole() {
        val parsed = parse(trackOf(20), maxPoints = 20)!!
        assertEquals(20, parsed.route.size)
        assertFalse(parsed.truncated)
    }

    @Test
    fun aTrackPastTheCapIsCutShortAndSaysSo() {
        val parsed = parse(trackOf(50), maxPoints = 20)!!
        assertEquals(20, parsed.route.size)
        assertTrue(parsed.truncated)
    }

    // --- Hardening ---

    @Test
    fun aDocumentTypeDeclarationIsRefusedOutright() {
        // Every entity a file could point us at has to be declared in a DTD, and a DTD has to be
        // in the prolog. No GPX needs one; refusing it closes the whole class of attack without
        // depending on a parser feature that some implementations do not recognise.
        assertNull(
            parse(
                """<?xml version="1.0"?>
                   <!DOCTYPE gpx [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                   <gpx xmlns="http://www.topografix.com/GPX/1/1"><trk><trkseg>
                     <trkpt lat="50.0" lon="30.0"><name>&xxe;</name></trkpt>
                   </trkseg></trk></gpx>"""
            )
        )
    }

    @Test
    fun aHarmlessDocumentTypeDeclarationIsRefusedToo() {
        // Nothing distinguishes a harmless DTD from a hostile one cheaply, and no GPX file needs
        // either, so the answer to both is the same one.
        assertNull(parse("""<!DOCTYPE gpx>${gpx("<trk>$oneSegment</trk>")}"""))
    }

    @Test
    fun theDoctypeScanReadsBytesRatherThanTrustingAnEncoding() {
        assertTrue(declaresDoctype("<!DOCTYPE gpx>".toByteArray()))
        // The same ASCII as UTF-16 sees it, either byte order.
        assertTrue(declaresDoctype("<!DOCTYPE gpx>".toByteArray(Charsets.UTF_16LE)))
        assertTrue(declaresDoctype("<!DOCTYPE gpx>".toByteArray(Charsets.UTF_16BE)))
        assertFalse(declaresDoctype("<?xml version=\"1.0\"?><gpx/>".toByteArray()))
        assertFalse(declaresDoctype(ByteArray(0)))
    }

    @Test
    fun aCoordinateNoPointCanBeAtIsSkipped() {
        // NaN and infinities pass toDoubleOrNull, and a latitude of 999 passes everything: one of
        // them reaching a distance, a map bound or a chart scale poisons every figure drawn from
        // the track. The points around them are still a track, so they are kept.
        val parsed = parse(
            gpx(
                """<trk><trkseg>
                     <trkpt lat="50.0000000" lon="30.0000000"/>
                     <trkpt lat="NaN" lon="30.0000000"/>
                     <trkpt lat="50.0010000" lon="Infinity"/>
                     <trkpt lat="999.0" lon="30.0000000"/>
                     <trkpt lat="50.0010000" lon="-190.0"/>
                     <trkpt lat="50.0020000" lon="30.0000000"/>
                   </trkseg></trk>"""
            )
        )!!

        assertEquals(listOf(50.0, 50.002), parsed.route.map { it.lat })
        assertTrue(parsed.route.all { it.lat.isFinite() && it.lon.isFinite() })
    }

    @Test
    fun aTrackOfNothingButUnusableCoordinatesIsNoTrack() {
        assertNull(
            parse(
                gpx(
                    """<trk><trkseg>
                         <trkpt lat="NaN" lon="NaN"/>
                         <trkpt lat="91.0" lon="30.0"/>
                       </trkseg></trk>"""
                )
            )
        )
    }

    @Test
    fun theSegmentFlagLandsOnTheFirstPointThatCanBeUsed() {
        // The segment starts where the track starts, not where an unusable point was written.
        val parsed = parse(
            gpx(
                """<trk><trkseg>
                     <trkpt lat="50.0" lon="30.0"/>
                   </trkseg><trkseg>
                     <trkpt lat="1e400" lon="30.0"/>
                     <trkpt lat="51.0" lon="31.0"/>
                   </trkseg></trk>"""
            )
        )!!

        assertEquals(listOf(50.0, 51.0), parsed.route.map { it.lat })
        assertEquals(listOf(true, true), parsed.route.map { it.segmentStart })
    }

    // --- Namespaces ---

    private val prefixed =
        """<?xml version="1.0" encoding="UTF-8"?>
           <g:gpx version="1.1" xmlns:g="http://www.topografix.com/GPX/1/1">
             <g:metadata><g:name>File</g:name></g:metadata>
             <g:trk><g:name>Prefixed loop</g:name><g:trkseg>
               <g:trkpt lat="50.0000000" lon="30.0000000"><g:time>2026-07-20T10:00:00Z</g:time></g:trkpt>
               <g:trkpt lat="50.0010000" lon="30.0000000"><g:time>2026-07-20T10:00:10Z</g:time></g:trkpt>
             </g:trkseg></g:trk>
           </g:gpx>"""

    @Test
    fun elementsCarryingANamespacePrefixReadTheSame() {
        // Equally valid GPX; matching qualified names literally refused this whole class of file.
        val twin = parse(
            gpx(
                """<metadata><name>File</name></metadata>
                   <trk><name>Prefixed loop</name><trkseg>
                     <trkpt lat="50.0000000" lon="30.0000000"><time>2026-07-20T10:00:00Z</time></trkpt>
                     <trkpt lat="50.0010000" lon="30.0000000"><time>2026-07-20T10:00:10Z</time></trkpt>
                   </trkseg></trk>"""
            )
        )!!
        val parsed = parse(prefixed)!!

        assertEquals(twin.name, parsed.name)
        assertEquals(twin.route.map { it.lat to it.timeMillis }, parsed.route.map { it.lat to it.timeMillis })
        assertEquals("Prefixed loop", parsed.name)
    }

    @Test
    fun aDocumentWithNoNamespaceAtAllStillReads() {
        val parsed = parse("<gpx><trk><name>Bare</name>$oneSegment</trk></gpx>")!!
        assertEquals("Bare", parsed.name)
        assertEquals(2, parsed.route.size)
    }

    @Test
    fun elementsFromAnotherNamespaceAreNotMistakenForGpx() {
        // Extensions bring their own <name>s and even their own <trkpt>-alikes; only GPX's own count.
        val parsed = parse(
            """<?xml version="1.0" encoding="UTF-8"?>
               <g:gpx version="1.1" xmlns:g="http://www.topografix.com/GPX/1/1"
                      xmlns:x="http://example.com/other">
                 <g:trk><x:name>Extension name</x:name><g:name>Real name</g:name><g:trkseg>
                   <g:trkpt lat="50.0" lon="30.0"/>
                   <x:trkpt lat="1.0" lon="2.0"/>
                   <g:trkpt lat="50.001" lon="30.0"><x:time>nonsense</x:time></g:trkpt>
                 </g:trkseg></g:trk>
               </g:gpx>"""
        )!!

        assertEquals("Real name", parsed.name)
        assertEquals(listOf(50.0, 50.001), parsed.route.map { it.lat })
        assertEquals(0L, parsed.route[1].timeMillis)
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
        val parsed = parse(buildGpx(trip, points))!!
        assertEquals("Ride & ride", parsed.name)
        assertEquals(3, parsed.route.size)
        assertEquals(50.1234567, parsed.route[0].lat, 1e-9)
        assertEquals(true, parsed.route[2].segmentStart)
    }
}
