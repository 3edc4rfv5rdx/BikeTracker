package xx.biketracker.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

class LocationBatchTest {

    @Test
    fun processesEveryItemInProviderOrder() {
        val output = mutableListOf<Int>()

        val count = processOrderedBatch(
            items = listOf(4, 7, 9, 12),
            shouldStop = { false },
            process = output::add,
        )

        assertEquals(4, count)
        assertEquals(listOf(4, 7, 9, 12), output)
    }

    @Test
    fun stopsBeforeTheNextItemWhenShutdownBegins() {
        val output = mutableListOf<Int>()

        val count = processOrderedBatch(
            items = listOf(1, 2, 3, 4),
            shouldStop = { output.size == 2 },
            process = output::add,
        )

        assertEquals(2, count)
        assertEquals(listOf(1, 2), output)
    }
}
