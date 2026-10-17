/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 */
package org.linbible

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** Text display settings; serialized into BibleView's `config` (see bibleview-js config.ts). */
@Serializable
data class DisplaySettings(
    var fontSize: Int = 18,
    var fontFamily: String = "sans-serif",
    var lineSpacing: Int = 13,
    var strongsMode: Int = 0, // 0 hidden, 1 inline, 2 links
    var showMorphology: Boolean = false,
    var showFootNotes: Boolean = true,
    var showFootNotesInline: Boolean = false,
    var showXrefs: Boolean = true,
    var expandXrefs: Boolean = false,
    var showRedLetters: Boolean = true,
    var showSectionTitles: Boolean = true,
    var showVerseNumbers: Boolean = true,
    var showVersePerLine: Boolean = false,
    var showBookmarks: Boolean = true,
    var showMyNotes: Boolean = true,
    var justifyText: Boolean = false,
    var hyphenation: Boolean = true,
    var maxWidthMm: Int = 170,
    var marginLeftMm: Int = 2,
    var marginRightMm: Int = 2,
    var dayBackground: Int = -1,
    var dayTextColor: Int = -16777216,
    var nightBackground: Int = -15592942, // #121212
    var nightTextColor: Int = -1842205,   // #E3E3E3
    var bookmarksHideLabels: List<String> = emptyList(),
) {
    fun toConfigJson(): JsonObject = buildJsonObject {
        put("fontSize", fontSize)
        put("fontFamily", fontFamily)
        put("lineSpacing", lineSpacing)
        put("strongsMode", strongsMode)
        put("showMorphology", showMorphology)
        put("showFootNotes", showFootNotes)
        put("showFootNotesInline", showFootNotesInline)
        put("showXrefs", showXrefs)
        put("expandXrefs", expandXrefs)
        put("showRedLetters", showRedLetters)
        put("showSectionTitles", showSectionTitles)
        put("showVerseNumbers", showVerseNumbers)
        put("showVersePerLine", showVersePerLine)
        put("showBookmarks", showBookmarks)
        put("showMyNotes", showMyNotes)
        put("justifyText", justifyText)
        put("hyphenation", hyphenation)
        put("showAnnotations", true)
        put("showNonCanonical", true)
        put("makeNonCanonicalItalic", true)
        put("showStrongsSeparately", false)
        put("topMargin", 0)
        put("infiniteScroll", true)
        put("showPageNumber", false)
        put("showReadingProgress", false)
        put("showMarkAsReadButton", false)
        put("showMemorizationIndicators", false)
        put("autoTrackReading", false)
        put("showPageButtons", false)
        strs("bookmarksHideLabels", bookmarksHideLabels)
        strs("bookmarksAssignLabels", emptyList())
        put("marginSize", buildJsonObject {
            put("marginLeft", marginLeftMm)
            put("marginRight", marginRightMm)
            put("maxWidth", maxWidthMm)
        })
        put("colors", buildJsonObject {
            put("dayBackground", dayBackground)
            put("dayNoise", 0)
            put("dayTextColor", dayTextColor)
            put("nightBackground", nightBackground)
            put("nightNoise", 0)
            put("nightTextColor", nightTextColor)
            str("dayBackgroundImage", null)
            str("nightBackgroundImage", null)
            put("dayBackgroundImageOpacity", 100)
            put("nightBackgroundImageOpacity", 100)
        })
    }
}

/** Persisted description of one reading window. */
@Serializable
data class WindowState(
    val category: String = "BIBLE",
    val bookInitials: String? = null,
    /** OSIS reference (Bible / commentary verse) or key name (dictionary / general book). */
    val key: String? = null,
    val synchronized: Boolean = true,
    val weight: Double = 1.0,
)

@Serializable
data class AppState(
    var display: DisplaySettings = DisplaySettings(),
    var nightMode: Boolean = false,
    var followSystemTheme: Boolean = true,
    var windows: List<WindowState> = listOf(WindowState()),
    var activeWindow: Int = 0,
    var windowWidth: Double = 1100.0,
    var windowHeight: Double = 800.0,
    var maximized: Boolean = false,
    var recentLabels: List<String> = emptyList(),
    var autoAssignLabels: List<String> = emptyList(),
    var studyPadCursors: Map<String, Int> = emptyMap(),
    var hideCompareDocuments: List<String> = emptyList(),
    var lastStrongsGreek: String? = null,
    var lastStrongsHebrew: String? = null,
    var verticalSplit: Boolean = false,
    var uiScale: Double? = null,
)

object Settings {
    private val file = File(AppDirs.config, "state.json")
    var state = AppState()

    fun load() {
        if (file.exists()) {
            state = runCatching { json.decodeFromString(AppState.serializer(), file.readText()) }
                .onFailure { Log.e("Could not read settings", it) }
                .getOrDefault(AppState())
        }
    }

    fun save() {
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(prettyJson.encodeToString(AppState.serializer(), state))
            tmp.renameTo(file)
        }.onFailure { Log.e("Could not save settings", it) }
    }

    fun addRecentLabel(id: String) {
        state.recentLabels = (listOf(id) + state.recentLabels.filter { it != id }).take(8)
    }
}
