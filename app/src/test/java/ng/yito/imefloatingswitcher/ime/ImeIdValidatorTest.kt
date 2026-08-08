package ng.yito.imefloatingswitcher.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeIdValidatorTest {
    @Test
    fun acceptsNormalInputMethodId() {
        assertTrue(ImeIdValidator.isValid("com.example.ime/com.example.ime.Service"))
    }

    @Test
    fun rejectsWhitespaceAndMalformedIds() {
        assertFalse(ImeIdValidator.isValid("com.example.ime"))
        assertFalse(ImeIdValidator.isValid("com.example.ime/"))
        assertFalse(ImeIdValidator.isValid("com.example.ime/foo bar"))
        assertFalse(ImeIdValidator.isValid("/service"))
        assertFalse(ImeIdValidator.isValid(null))
    }
}
