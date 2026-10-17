package org.linbible

import org.crosswire.jsword.passage.Verse
import org.crosswire.jsword.versification.BibleBook
import org.crosswire.jsword.versification.system.Versifications
import kotlin.test.Test

class OrdinalCheck {
    @Test
    fun printOrdinals() {
        val kjv = Versifications.instance().getVersification("KJV")
        for (o in listOf(33095, 33096, 14758)) println("KJVA $o = ${Verse(KJVA, o).name} -> KJV ${Verse(KJVA, o).toV11n(kjv).ordinal}")
        println("KJV John 3:16 = ${Verse(kjv, BibleBook.JOHN, 3, 16).ordinal}, KJVA = ${Verse(KJVA, BibleBook.JOHN, 3, 16).ordinal}")
    }
}
