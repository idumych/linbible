/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 */
@file:Suppress("unused")

package net.bible.android.control.versification

import org.crosswire.jsword.passage.RangedPassage
import org.crosswire.jsword.passage.RestrictionType
import org.crosswire.jsword.passage.VerseRange

val RangedPassage.toVerseRange: VerseRange get() = getRangeAt(0, RestrictionType.NONE)
