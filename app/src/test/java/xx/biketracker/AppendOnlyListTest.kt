package xx.biketracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The list the tracker hands the map on every fix. What it has to get right is that a snapshot is
 * a value: the ride goes on growing behind it, and nothing of that may reach a snapshot already
 * given out — the UI reads them on another thread.
 */
class AppendOnlyListTest {

    private fun filled(count: Int, chunkSize: Int = 4) =
        AppendOnlyList<Int>(chunkSize).apply { repeat(count) { add(it) } }

    @Test
    fun aSnapshotReadsBackEverythingAddedInOrder() {
        // Spans several chunks and ends mid-chunk, so both sides of the indexing are exercised.
        assertEquals((0..9).toList(), filled(10).snapshot())
        assertEquals(emptyList<Int>(), filled(0).snapshot())
        assertEquals((0..7).toList(), filled(8).snapshot()) // exactly two full chunks
    }

    @Test
    fun sizeMatchesWhatWasAdded() {
        assertEquals(0, filled(0).size)
        assertEquals(8, filled(8).size)
        assertEquals(9, filled(9).size)
    }

    @Test
    fun aSnapshotIsUnaffectedByLaterAppends() {
        val list = filled(6)
        val taken = list.snapshot()
        repeat(6) { list.add(100 + it) }

        assertEquals((0..5).toList(), taken)
        assertEquals(12, list.snapshot().size)
    }

    @Test
    fun aSnapshotIsUnaffectedByAClear() {
        val list = filled(6)
        val taken = list.snapshot()
        list.clear()

        assertEquals((0..5).toList(), taken)
        assertEquals(0, list.size)
        assertEquals(emptyList<Int>(), list.snapshot())
    }

    @Test
    fun aSnapshotIsAPlainListToEveryoneElse() {
        val taken = filled(10).snapshot()
        assertEquals((0..9).toList(), taken)
        assertTrue(taken == (0..9).toList()) // equal to any list holding the same items
        assertEquals(listOf(3, 4, 5), taken.subList(3, 6))
        assertEquals(7, taken.indexOf(7))
        assertEquals(9, taken.last())
    }

    @Test
    fun readingOutsideASnapshotFails() {
        val taken = filled(5)
        assertThrows(IndexOutOfBoundsException::class.java) { taken.snapshot()[5] }
        assertThrows(IndexOutOfBoundsException::class.java) { taken.snapshot()[-1] }
    }

    @Test
    fun concatReadsThroughToBothSides() {
        val joined = concat(filled(6).snapshot(), listOf(100, 101))
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 100, 101), joined)
        assertEquals(8, joined.size)
        assertThrows(IndexOutOfBoundsException::class.java) { joined[8] }
        assertEquals(emptyList<Int>(), concat(emptyList<Int>(), emptyList()))
    }
}
