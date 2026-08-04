package xx.biketracker.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSettingsBoundsTest {

    @Test
    fun corruptPersistedNumbersUseTheSameBoundsAsSetters() {
        assertEquals(0, clampRiderWeightKg(Int.MIN_VALUE))
        assertEquals(300, clampRiderWeightKg(Int.MAX_VALUE))
        assertEquals(1, clampAutoPauseSpeedKmh(Int.MIN_VALUE))
        assertEquals(20, clampAutoPauseSpeedKmh(Int.MAX_VALUE))
        assertEquals(1, clampAutoPauseHoldSec(Int.MIN_VALUE))
        assertEquals(120, clampAutoPauseHoldSec(Int.MAX_VALUE))
        assertEquals(0, clampAutoSaveMin(Int.MIN_VALUE))
        assertEquals(120, clampAutoSaveMin(Int.MAX_VALUE))
    }
}
