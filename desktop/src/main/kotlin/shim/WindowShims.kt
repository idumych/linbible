/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 *
 * AndBible's window API (WindowControl, WindowRepository, Window, PageManager) as used by its AI agent and tools,
 * on top of LinBible's windows. All calls must happen on the JavaFX thread (AndBible uses Dispatchers.Main).
 */
@file:Suppress("unused")

package net.bible.android.control.page.window

import net.bible.android.database.IdType
import org.crosswire.jsword.book.Book
import org.crosswire.jsword.passage.Key
import org.linbible.BibleWindow
import org.linbible.MainApp
import org.linbible.Page

class WindowLayout {
    enum class WindowState { VISIBLE, MINIMISED, MAXIMISED, CLOSED }
}

class CurrentPage(private val page: Page) {
    val currentDocument: Book? get() = page.book
    val key: Key? get() = when (page) {
        is Page.Bible -> org.crosswire.jsword.passage.VerseRange(page.verse.versification, page.verse, page.verse)
        is Page.Commentary -> page.verse
        is Page.Keyed -> page.key
        else -> null
    }
}

class PageManager(private val w: BibleWindow) {
    val currentPage: CurrentPage get() = CurrentPage(w.page)
    fun setCurrentDocument(book: Book) { app.pageFor(book, null)?.let { app.navigate(w, it) } }
    fun setCurrentDocumentAndKey(book: Book, key: Key) { app.pageFor(book, key)?.let { app.navigate(w, it) } }
}

private val app: MainApp get() = MainApp.instance ?: error("LinBible is not running")

class Window(val bibleWindow: BibleWindow) {
    val id: IdType get() = bibleWindow.windowId
    val windowState: WindowLayout.WindowState get() = when {
        bibleWindow !in app.windows -> WindowLayout.WindowState.CLOSED
        bibleWindow in app.minimised -> WindowLayout.WindowState.MINIMISED
        else -> WindowLayout.WindowState.VISIBLE
    }
    val isMinimised get() = windowState == WindowLayout.WindowState.MINIMISED
    val isPinMode get() = false
    val isLinksWindow get() = app.isLinksWindow(bibleWindow)
    val isSynchronised get() = bibleWindow.synchronized
    val pageManager get() = PageManager(bibleWindow)
    val displayId get() = app.windows.indexOf(bibleWindow) + 1
    override fun equals(other: Any?) = other is Window && other.bibleWindow === bibleWindow
    override fun hashCode() = System.identityHashCode(bibleWindow)
}

class WindowRepository {
    /** One workspace in LinBible; a fixed id keeps AI sessions apart from AndBible's workspaces. */
    val id: IdType = IdType("00000000-0000-0000-11b1-b1e000000001")
    val name: String get() = "LinBible"
    val activeWindow: Window get() = Window(app.activeWindow ?: app.windows.first())
    val windowList: List<Window> get() = app.windows.map { Window(it) }
    fun getWindow(id: IdType): Window? = app.windows.firstOrNull { it.windowId == id }?.let { Window(it) }
}

class WindowControl {
    val windowRepository = WindowRepository()
    val activeWindow: Window get() = windowRepository.activeWindow
    val activeWindowPageManager: PageManager get() = activeWindow.pageManager

    fun addNewWindow(document: Book, key: Key): Window {
        val page = app.pageFor(document, key) ?: error("Cannot show ${document.initials}")
        return Window(app.addWindow(page))
    }

    fun addNewWindow(sourceWindow: Window? = null): Window = Window(app.addWindow(sourceWindow?.bibleWindow?.page ?: Page.defaultBible()))

    fun minimiseWindow(window: Window, force: Boolean = false) = app.minimiseWindow(window.bibleWindow)
    fun restoreWindow(window: Window) = app.restoreWindow(window.bibleWindow)
    fun closeWindow(window: Window) = app.closeWindow(window.bibleWindow)
    fun isWindowRemovable(window: Window): Boolean = app.isLinksWindow(window.bibleWindow) || app.windows.count { !app.isLinksWindow(it) } > 1
    fun isWindowMinimizable(window: Window): Boolean = !window.isMinimised && app.windows.count { it !in app.minimised } > 1

    companion object {
        val instance by lazy { WindowControl() }
    }
}
