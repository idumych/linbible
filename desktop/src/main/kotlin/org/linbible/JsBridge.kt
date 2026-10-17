/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 *
 * Implementation of the `window.android` object that BibleView calls (see bibleview-js
 * composables/android.ts). Ported from AndBible's BibleJavascriptInterface.kt.
 * JavaFX invokes these methods on the FX application thread.
 */
@file:Suppress("unused", "UNUSED_PARAMETER")

package org.linbible

import javafx.application.Platform
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.crosswire.jsword.book.BookCategory
import org.crosswire.jsword.book.sword.SwordBook
import org.crosswire.jsword.passage.VerseRange
import org.crosswire.jsword.versification.BookName

class JsBridge(private val w: BibleWindow) {
    private val app get() = w.app

    private fun later(f: () -> Unit) = Platform.runLater { runCatching(f).onFailure { Log.e("Bridge action failed", it) } }

    private fun selection(bookInitials: String, start: Int, end: Int, osisRef: String? = null) =
        BibleWindow.Selection(Sword.book(bookInitials), osisRef, start, if (end < 0) start else end)

    private fun verseRange(bookInitials: String, start: Int, end: Int): Pair<SwordBook, VerseRange>? {
        val book = Sword.book(bookInitials) as? SwordBook ?: return null
        if (book.bookCategory != BookCategory.BIBLE) return null
        return book to selection(bookInitials, start, end).verseRange!!
    }

    // --- extra method of the desktop port: replaces window.location.assign() navigation
    fun openLink(url: String) = later { app.links.open(url, w) }

    fun console(loggerName: String, message: String) {
        when (loggerName) {
            "error" -> Log.e("[js] $message")
            "warn" -> Log.w("[js] $message")
            else -> Log.d("[js] $message")
        }
    }

    // --- lifecycle & scrolling
    fun setClientReady() = w.setClientReady()
    fun scrolledToOrdinal(key: String, ordinal: Int) = w.scrolledToOrdinal(key, ordinal)
    fun requestMoreToBeginning(callId: Int) = w.requestMore(callId, toEnd = false)
    fun requestMoreToEnd(callId: Int) = w.requestMore(callId, toEnd = true)
    fun goToNextChapter() = later { app.navigateChapter(w, +1) }
    fun goToPreviousChapter() = later { app.navigateChapter(w, -1) }
    fun setLimitAmbiguousModalSize(value: Boolean) {}
    fun selectionCleared() {}
    fun reportInputFocus(newValue: Boolean) { w.editing = newValue }
    fun setEditing(enabled: Boolean) { w.editing = enabled }
    fun reportModalState(value: Boolean) {}
    fun onKeyDown(key: String) {}
    fun saveState(newState: String) {}
    fun querySelection(bookmarkId: String?, value: Boolean) {}

    // --- references
    fun parseRef(callId: Int, s: String) {
        val v11n = (w.page.book as? SwordBook)?.versification ?: KJVA
        val p = Page.parsePassage(v11n, s)
        w.response(callId, jsString(p?.osisRef))
    }

    fun refChooserDialog(callId: Int) = later {
        val v = app.askReference("Insert reference")
        val name = v?.let {
            synchronized(BookName::class.java) {
                val old = BookName.isFullBookName()
                BookName.setFullBookName(false)
                try { it.name } finally { BookName.setFullBookName(old) }
            }
        } ?: ""
        w.response(callId, jsString(name))
    }

    fun getActiveLanguages(): String =
        Sword.bibles.map { it.language.code }.distinct().joinToString(",", "[", "]") { jsString(it) }

    // --- bookmarks
    fun addBookmark(bookInitials: String, startOrdinal: Int, endOrdinal: Int, addNote: Boolean) =
        later { w.makeBookmark(selection(bookInitials, startOrdinal, endOrdinal), wholeVerse = true, openNotes = addNote) }

    fun addGenericBookmark(bookInitials: String, osisRef: String, startOrdinal: Int, endOrdinal: Int, addNote: Boolean) =
        later { w.makeBookmark(selection(bookInitials, startOrdinal, endOrdinal, osisRef), wholeVerse = true, openNotes = addNote) }

    fun addParagraphBreakBookmark(bookInitials: String, startOrdinal: Int, endOrdinal: Int) = later {
        val (book, range) = verseRange(bookInitials, startOrdinal, endOrdinal) ?: return@later
        Bookmarks.addBibleBookmark(book, range, null, null, true, listOf(PARAGRAPH_BREAK_LABEL_ID))
    }

    fun addGenericParagraphBreakBookmark(bookInitials: String, osisRef: String, startOrdinal: Int, endOrdinal: Int) = later {
        val book = Sword.book(bookInitials) ?: return@later
        Bookmarks.addGenericBookmark(book, osisRef, startOrdinal..maxOf(startOrdinal, endOrdinal), null, null, true, listOf(PARAGRAPH_BREAK_LABEL_ID))
    }

    fun createWholePageBookmark(bookInitials: String, bookKey: String) = later {
        val book = Sword.book(bookInitials) ?: return@later
        val labels = Settings.state.autoAssignLabels
        val b = Bookmarks.addGenericBookmark(book, bookKey, null, null, null, true, labels)
        if (labels.isEmpty()) w.emit("bookmark_clicked", jsString(b.id), "{openLabels: true, openNotes: false}")
    }

    fun saveBookmarkNote(bookmarkId: String, note: String?) = Bookmarks.setNote(bookmarkId, note)
    fun saveGenericBookmarkNote(bookmarkId: String, note: String?) = Bookmarks.setNote(bookmarkId, note)
    fun removeBookmark(bookmarkId: String) = later { Bookmarks.delete(listOf(bookmarkId)) }
    fun removeGenericBookmark(bookmarkId: String) = later { Bookmarks.delete(listOf(bookmarkId)) }
    fun assignLabels(bookmarkId: String) = later { app.showAssignLabels(bookmarkId) }
    fun genericAssignLabels(bookmarkId: String) = later { app.showAssignLabels(bookmarkId) }
    fun removeBookmarkLabel(bookmarkId: String, labelId: String) = later { Bookmarks.removeLabel(bookmarkId, labelId) }
    fun removeGenericBookmarkLabel(bookmarkId: String, labelId: String) = later { Bookmarks.removeLabel(bookmarkId, labelId) }
    fun setAsPrimaryLabel(bookmarkId: String, labelId: String) = later { Bookmarks.setPrimaryLabel(bookmarkId, labelId) }
    fun setAsPrimaryLabelGeneric(bookmarkId: String, labelId: String) = later { Bookmarks.setPrimaryLabel(bookmarkId, labelId) }

    fun toggleBookmarkLabel(bookmarkId: String, labelId: String) = later {
        Bookmarks.toggleLabel(bookmarkId, labelId)
        Settings.addRecentLabel(labelId)
        app.updateAllConfigs()
    }

    fun toggleGenericBookmarkLabel(bookmarkId: String, labelId: String) = toggleBookmarkLabel(bookmarkId, labelId)

    fun setBookmarkWholeVerse(bookmarkId: String, value: Boolean) = later {
        Bookmarks.update(bookmarkId) { b, _ -> b?.wholeVerse = value }
    }

    fun setGenericBookmarkWholeVerse(bookmarkId: String, value: Boolean) = later {
        Bookmarks.update(bookmarkId) { _, g -> g?.wholeVerse = value }
    }

    fun setBookmarkCustomIcon(bookmarkId: String, value: String?) = later {
        Bookmarks.update(bookmarkId) { b, g -> b?.customIcon = value; g?.customIcon = value }
    }

    fun setGenericBookmarkCustomIcon(bookmarkId: String, value: String?) = setBookmarkCustomIcon(bookmarkId, value)

    fun setBookmarkEditAction(bookmarkId: String, value: String) = later {
        val action = runCatching { json.decodeFromString(EditAction.serializer(), value) }.getOrNull()
        Bookmarks.update(bookmarkId) { b, g -> b?.editAction = action; g?.editAction = action }
    }

    fun shareBookmarkVerse(bookmarkId: String) = later {
        val b = Bookmarks.bibleBookmark(bookmarkId) ?: return@later
        val book = (Sword.book(b.bookInitials) as? SwordBook) ?: Sword.bibles.firstOrNull() ?: return@later
        app.copyVerses(book, b.verseRange.toV11n(book.versification))
    }

    // --- StudyPads
    fun openStudyPad(labelId: String, bookmarkId: String) = later { app.openStudyPad(labelId, bookmarkId) }

    fun createNewStudyPadEntry(labelId: String, entryType: String, afterEntryId: String) = later {
        val order = when (entryType) {
            "bookmark" -> Bookmarks.bookmarkToLabels(afterEntryId, false).find { it.labelId == labelId }?.orderNumber
            "generic-bookmark" -> Bookmarks.bookmarkToLabels(afterEntryId, true).find { it.labelId == labelId }?.orderNumber
            "journal" -> Bookmarks.studyPadEntry(afterEntryId)?.orderNumber
            else -> -1
        } ?: -1
        Bookmarks.createStudyPadEntry(labelId, order)
    }

    fun deleteStudyPadEntry(studyPadId: String) = later { Bookmarks.deleteStudyPadEntry(studyPadId) }

    fun updateOrderNumber(labelId: String, data: String) = later {
        val o = json.parseToJsonElement(data).jsonObject
        fun pairs(k: String) = o[k]?.jsonArray?.map {
            val e = it.jsonObject
            e["first"]!!.jsonPrimitive.content to e["second"]!!.jsonPrimitive.intOrNull!!
        } ?: emptyList()
        Bookmarks.updateOrderNumbers(labelId, pairs("bookmarks"), pairs("genericBookmarks"), pairs("studyPadTextItems"))
    }

    fun setStudyPadCursor(labelId: String, orderNumber: Int) {
        Settings.state.studyPadCursors = Settings.state.studyPadCursors + (labelId to orderNumber)
    }

    fun updateStudyPadTextEntry(data: String) = later {
        val o = json.parseToJsonElement(data).jsonObject
        val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@later
        val existing = Bookmarks.studyPadEntry(id) ?: return@later
        Bookmarks.updateStudyPadEntry(existing.copy(
            orderNumber = o["orderNumber"]?.jsonPrimitive?.intOrNull ?: existing.orderNumber,
            indentLevel = o["indentLevel"]?.jsonPrimitive?.intOrNull ?: existing.indentLevel,
            text = o["text"]?.jsonPrimitive?.contentOrNull ?: existing.text,
        ))
    }

    fun updateStudyPadTextEntryText(id: String, text: String) = later { Bookmarks.updateStudyPadEntryText(id, text) }

    private fun updateBtl(data: String, generic: Boolean) {
        val o = json.parseToJsonElement(data).jsonObject
        val bookmarkId = o["bookmarkId"]!!.jsonPrimitive.content
        val labelId = o["labelId"]!!.jsonPrimitive.content
        val old = Bookmarks.bookmarkToLabels(bookmarkId, generic).find { it.labelId == labelId } ?: BookmarkToLabel(bookmarkId, labelId)
        Bookmarks.updateBookmarkToLabel(old.copy(
            orderNumber = o["orderNumber"]?.jsonPrimitive?.intOrNull ?: old.orderNumber,
            indentLevel = o["indentLevel"]?.jsonPrimitive?.intOrNull ?: old.indentLevel,
            expandContent = o["expandContent"]?.jsonPrimitive?.booleanOrNull ?: old.expandContent,
        ), generic)
    }

    fun updateBookmarkToLabel(data: String) = later { updateBtl(data, false) }
    fun updateGenericBookmarkToLabel(data: String) = later { updateBtl(data, true) }

    // --- verse actions
    fun copyVerse(bookInitials: String, startOrdinal: Int, endOrdinal: Int) = later {
        val (book, range) = verseRange(bookInitials, startOrdinal, endOrdinal) ?: return@later
        app.copyVerses(book, range)
    }

    fun shareVerse(bookInitials: String, startOrdinal: Int, endOrdinal: Int) = copyVerse(bookInitials, startOrdinal, endOrdinal)

    fun compare(bookInitials: String, verseOrdinal: Int, endOrdinal: Int) = later {
        val (_, range) = verseRange(bookInitials, verseOrdinal, endOrdinal) ?: return@later
        app.openCompare(range)
    }

    fun toggleCompareDocument(documentId: String) = later {
        val s = Settings.state
        s.hideCompareDocuments = if (documentId in s.hideCompareDocuments) s.hideCompareDocuments - documentId else s.hideCompareDocuments + documentId
        app.updateAllConfigs()
    }

    fun openMyNotes(v11n: String, ordinal: Int) = later {
        val vs = Page.v11n(v11n)
        app.openMyNotes(vs.decodeOrdinal(ordinal))
    }

    fun speak(bookInitials: String, v11n: String, startOrdinal: Int, endOrdinal: Int) = later {
        val (book, range) = verseRange(bookInitials, startOrdinal, endOrdinal) ?: return@later
        app.speak(Content.plainText(book, range))
    }

    fun speakGeneric(bookInitials: String, osisRef: String, startOrdinal: Int, endOrdinal: Int) = later {
        val book = Sword.book(bookInitials) ?: return@later
        val key = runCatching { book.getKey(osisRef) }.getOrNull() ?: return@later
        app.speak(Content.textWithinOrdinals(book, key, startOrdinal..maxOf(startOrdinal, endOrdinal)))
    }

    fun openExternalLink(link: String) = later {
        if (link.startsWith("http://") || link.startsWith("https://")) app.openBrowser(link) else app.links.open(link, w)
    }

    fun openDownloads() = later { app.showDownloads() }
    fun toast(text: String) = later { app.toast(text) }
    fun helpDialog(content: String, title: String?) = later { app.info(title ?: "Help", content) }
    fun helpBookmarks() = later { app.openBrowser("https://docs.andbible.org/en/latest/bookmarks.html") }
    fun showHelpDialog(scopeKey: String) = later { app.openBrowser("https://docs.andbible.org/en/latest/") }
    fun shareHtml(html: String) = later { app.copyHtmlToClipboard(html) }

    // --- AndBible features not (yet) available on the desktop
    private fun unsupported(what: String) = later { app.toast("$what is not available in LinBible yet") }

    fun openEpubLink(bookInitials: String, toKey: String, toId: String) = unsupported("EPUB")
    fun memorize(bookInitials: String, verseOrdinal: Int, endOrdinal: Int) = unsupported("Memorize mode")
    fun markAsMemorized(bookInitials: String, startOrdinal: Int, endOrdinal: Int) {}
    fun addMemorizationTarget(bookInitials: String, startOrdinal: Int, endOrdinal: Int) {}
    fun unmarkMemorized(bookInitials: String, startOrdinal: Int, endOrdinal: Int) {}
    fun removeMemorizationTarget(bookInitials: String, startOrdinal: Int, endOrdinal: Int) {}
    fun speakMemorizationLoop(bookInitials: String, v11n: String, startOrdinal: Int, endOrdinal: Int) {}
    fun openReadingProgress(tab: Int) = unsupported("Reading progress")
    fun openReadingProgressSettings() = unsupported("Reading progress")
    fun recordChapterRead(bookInitials: String, startOrdinal: Int, chapter: Int, source: String?) {}
    fun openChapterReadHistory(bookInitials: String, startOrdinal: Int, chapter: Int) = unsupported("Reading history")
    fun setReadingProgressSettings(json: String) {}
    fun llmAction(bookInitials: String, startOrdinal: Int, endOrdinal: Int, text: String) = unsupported("AI assistant")
    fun llmActionGeneric(bookInitials: String, osisRef: String, startOrdinal: Int, endOrdinal: Int, text: String) = unsupported("AI assistant")
    fun noteEditorLlmAction(contextJson: String) = unsupported("AI assistant")
    fun getMyDocumentPageRawContent(callId: Int, bookInitials: String, pageKey: String) = w.response(callId, "null")
    fun saveMyDocumentPageContent(bookInitials: String, pageId: String, content: String, title: String?) {}
    fun reloadMyDocumentPage(bookInitials: String) {}
    fun regenerateMyDocumentPage(pageId: String) {}
    fun deleteMyDocumentPage(pageId: String) {}
    fun shareMyDocumentContent(bookInitials: String, pageKey: String) {}
    fun copyMyDocumentContent(bookInitials: String, pageKey: String) {}
    fun openPromptEditor(promptId: String) {}
    fun openAiDocPage(documentInitials: String, pageKey: String) {}
    fun openAiDocPageChooser(markersJson: String) {}
}
