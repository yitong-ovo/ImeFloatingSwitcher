package ng.yito.imefloatingswitcher.parser

import ng.yito.imefloatingswitcher.model.ImeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextImeDumpParserTest {
    private val parser = TextImeDumpParser()

    @Test
    fun parsesAospShownDump() {
        val snapshot = parser.parse(
            """
            mInputShown=true
            mImeWindowVis=0x3
            mCurMethodId=com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME
            mShowRequested=true
            """.trimIndent()
        )
        assertEquals(ImeStatus.SHOWN, snapshot.status)
        assertEquals(true, snapshot.inputShown)
        assertEquals(3, snapshot.imeWindowVisibility)
        assertEquals("com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME", snapshot.currentImeIdFromDump)
        assertEquals(true, snapshot.showRequested)
    }

    @Test
    fun explicitFalseWinsOverVisibleWindowFlag() {
        val snapshot = parser.parse("mInputShown=false\nmImeWindowVis=0x3")
        assertEquals(ImeStatus.HIDDEN, snapshot.status)
        assertEquals(false, snapshot.inputShown)
    }

    @Test
    fun parsesDecimalAndHexVisibility() {
        assertEquals(ImeStatus.SHOWN, parser.parse("mImeWindowVis=3").status)
        assertEquals(ImeStatus.SHOWN, parser.parse("mImeWindowVis=0x2").status)
        assertEquals(ImeStatus.HIDDEN, parser.parse("mImeWindowVis=0").status)
    }

    @Test
    fun missingFieldsIsUnknownNotHidden() {
        val snapshot = parser.parse("mShowRequested=true\notherField=1")
        assertEquals(ImeStatus.UNKNOWN, snapshot.status)
        assertNull(snapshot.inputShown)
        assertEquals("VISIBILITY_FIELDS_MISSING", snapshot.errorCode)
    }

    @Test
    fun preferredUserSectionPreventsReadingAnotherUser() {
        val snapshot = TextImeDumpParser(preferredUserId = 10).parse(
            """
            User #0
              mInputShown=false
            User #10
              mInputShown=true
              mCurId=package/service
            """.trimIndent()
        )
        assertEquals(ImeStatus.SHOWN, snapshot.status)
        assertEquals("package/service", snapshot.currentImeIdFromDump)
    }

    @Test
    fun conflictingExplicitValuesAreReportedAndLastWins() {
        val snapshot = parser.parse("mInputShown=false\nmInputShown=true")
        assertEquals(ImeStatus.SHOWN, snapshot.status)
        assertEquals("CONFLICTING_INPUT_SHOWN", snapshot.errorCode)
    }
}
