package ng.yito.imefloatingswitcher.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class InputMethodSubtypeLabelResolverTest {
    @Test
    fun mapsGboardChineseAndEnglishWithoutSubtypeHashes() {
        assertEquals(
            ImeSubtypeLabel("中", "中文"),
            subtypeLanguageLabel("zh-CN", "zh_CN", "简体中文")
        )
        assertEquals(
            ImeSubtypeLabel("EN", "英文"),
            subtypeLanguageLabel("en-Latn-US", "", "Alphabet")
        )
    }
}
