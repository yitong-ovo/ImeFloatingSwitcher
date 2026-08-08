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
}
