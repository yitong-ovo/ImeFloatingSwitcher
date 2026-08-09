package ng.yito.imefloatingswitcher.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppSettingsTest {
    @Test
    fun returnsRememberedSubtypeForConfiguredInputMethod() {
        val settings = AppSettings(
            primaryImeId = "primary",
            secondaryImeId = "secondary",
            primaryImeSubtypeHash = 101,
            secondaryImeSubtypeHash = 202
        )

        assertEquals(101, settings.rememberedSubtypeHash("primary"))
        assertEquals(202, settings.rememberedSubtypeHash("secondary"))
        assertNull(settings.rememberedSubtypeHash("other"))
    }

    @Test
    fun keepsThreeIndependentOverlayButtonSettings() {
        val settings = AppSettings(
            overlayButtonCount = 3,
            overlayButtons = listOf(
                OverlayButtonSettings(sizeDp = 36, positionX = 10),
                OverlayButtonSettings(sizeDp = 48, positionX = 20),
                OverlayButtonSettings(sizeDp = 64, positionX = 30)
            )
        )

        assertEquals(36, settings.overlayButton(0).sizeDp)
        assertEquals(20, settings.overlayButton(1).positionX)
        assertEquals(64, settings.overlayButton(2).sizeDp)
    }
}
