/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 */
@file:Suppress("unused")

package net.bible.android.view.activity.page

import net.bible.android.database.IdType
import net.bible.service.llm.agent.NoteEditorEntityType
import org.crosswire.jsword.book.Book
import org.crosswire.jsword.book.Books

/** A text/verse selection in a Bible view (BibleView.kt), the input of AI prompts. */
class Selection(
    val bookInitials: String?,
    val startOrdinal: Int,
    val startOffset: Int?,
    val endOrdinal: Int,
    val endOffset: Int?,
    val bookmarks: List<IdType>,
    val notes: String? = null,
    val text: String = "",
    val osisRef: String? = null,
    val noteEditorEntityType: NoteEditorEntityType? = null,
    val noteEditorEntityId: String? = null,
    val noteEditorContent: String? = null,
    val noteEditorContentType: String? = null,
) {
    constructor(bookInitials: String, startOrdinal: Int, endOrdinal: Int?, text: String = "") :
        this(bookInitials, startOrdinal, 0, endOrdinal ?: startOrdinal, null, emptyList(), text = text)
    constructor(bookInitials: String, osisRef: String, startOrdinal: Int, endOrdinal: Int?, text: String = "") :
        this(bookInitials, startOrdinal, 0, endOrdinal ?: startOrdinal, null, emptyList(), text = text, osisRef = osisRef)

    val hasRange get() = startOffset != null && endOffset != null
    val book: Book? get() = Books.installed().getBook(bookInitials)
}
