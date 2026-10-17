/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 */
@file:Suppress("unused")

package net.bible.android.common

import org.crosswire.jsword.passage.Passage
import org.crosswire.jsword.passage.Verse
import org.crosswire.jsword.passage.VerseRange
import org.crosswire.jsword.versification.Versification
import org.linbible.toV11n as lbToV11n

/** AndBible's VerseExtensions, delegating to LinBible's versification conversion. */
fun Verse.toV11n(v11n: Versification): Verse = this.lbToV11n(v11n)
fun VerseRange.toV11n(v11n: Versification?): VerseRange = this.lbToV11n(v11n)
fun Passage.toV11n(v11n: Versification): Passage = org.crosswire.jsword.versification.VersificationConverter().convert(this, v11n)
