package com.merrimentlabs.ducky

import com.merrimentlabs.ducky.ui.Palette
import com.merrimentlabs.ducky.ui.PRESET_SEEDS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ThemeDefaultsTest {
    @Test
    fun `high contrast is off unless asked for`() {
        for (seed in PRESET_SEEDS) {
            for (mono in listOf(false, true)) {
                // ColorScheme has no value equality; its toString lists every role's color.
                assertEquals(
                    Palette.light(seed, mono, highContrast = false).toString(),
                    Palette.light(seed, mono).toString(),
                )
                assertEquals(
                    Palette.dark(seed, mono, highContrast = false).toString(),
                    Palette.dark(seed, mono).toString(),
                )
            }
        }
        // And the flag really is a different scheme, so the equality above means something.
        assertNotEquals(Palette.light(PRESET_SEEDS[0], false, true).background, Palette.light(PRESET_SEEDS[0], false).background)
        assertNotEquals(Palette.dark(PRESET_SEEDS[0], false, true).background, Palette.dark(PRESET_SEEDS[0], false).background)
    }
}
