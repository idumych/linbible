/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 *
 * Reading progress (chapters read, reading cycles) and memorization (memorized verses and targets).
 * Port of AndBible's ProgressControl and ReadingProgressSettings on AndBible's progress.sqlite3.
 */
package org.linbible

import kotlinx.serialization.Serializable
import org.crosswire.jsword.passage.Verse
import org.crosswire.jsword.passage.VerseRange
import org.crosswire.jsword.versification.BibleBook
import org.crosswire.jsword.versification.Versification
import org.crosswire.jsword.versification.system.Versifications
import java.util.Calendar

/** Serializable bundle of the reading progress settings, exchanged with the BibleView frontend. */
@Serializable
data class ReadingProgressSettingsBundle(
    val autoMarkMemorized: Boolean = true,
    val memorizeTypeFullWords: Boolean = false,
    val memorizeWordVisibility: String = "light",
    val memorizeErrorHeatmap: Boolean = true,
    val memorizeScrambleHideUsed: Boolean = false,
    val memorizeIncludeReference: Boolean = true,
)

/** Change notifications sent to the open BibleView windows (AndBible's ABEventBus events). */
interface ProgressListener {
    fun memorizationChanged(addedMemorized: List<Int>, removedMemorized: List<Int>, addedTargets: List<Int>, removedTargets: List<Int>) {}
    fun chapterReadStatusChanged(kjvBookOrdinal: Int, chapter: Int, count: Int) {}
    fun activeCycleChanged() {}
    fun settingsChanged() {}
}

object Progress {
    private val db get() = DbCategory.PROGRESS.db
    private val listeners = mutableListOf<ProgressListener>()
    fun addListener(l: ProgressListener) { listeners += l }
    fun removeListener(l: ProgressListener) { listeners -= l }
    private fun notify(f: ProgressListener.() -> Unit) = listeners.toList().forEach { runCatching { it.f() }.onFailure { e -> Log.e("listener", e) } }

    init { Databases.onChanged(DbCategory.PROGRESS) { javafx.application.Platform.runLater { externallyChanged() } } }

    /** Called after sync or restore changed the database. */
    fun externallyChanged() {
        notify { activeCycleChanged() }
        notify { settingsChanged() }
        notify { memorizationChanged(emptyList(), emptyList(), emptyList(), emptyList()) }
    }

    // ---------------------------------------------------------------- settings (GlobalReadingProgressSettings row)

    private const val SETTINGS_ID = "b2000000-0000-0000-0000-000000000001"

    private class SettingsRow(
        val autoTrackReading: Boolean, val autoMarkMemorized: Boolean, val memorizeTypeFullWords: Boolean,
        val memorizeWordVisibility: String, val memorizeErrorHeatmap: Boolean, val memorizeScrambleHideUsed: Boolean,
        val memorizeIncludeReference: Boolean, val activeCycle: Int,
    )

    private fun settings(): SettingsRow = db.query("""SELECT autoTrackReading, autoMarkMemorized, memorizeTypeFullWords,
            memorizeWordVisibility, memorizeErrorHeatmap, memorizeScrambleHideUsed, memorizeIncludeReference, activeCycle
            FROM GlobalReadingProgressSettings LIMIT 1""") {
        SettingsRow(it.getInt(1) == 1, it.getInt(2) == 1, it.getInt(3) == 1, it.getString(4) ?: "light", it.getInt(5) == 1,
            it.getInt(6) == 1, it.getInt(7) == 1, it.getInt(8))
    }.firstOrNull() ?: SettingsRow(false, true, false, "light", true, false, true, 0)

    private fun saveSettings(s: SettingsRow) {
        db.update("""INSERT OR REPLACE INTO GlobalReadingProgressSettings (id, autoTrackReading, autoMarkMemorized,
            memorizeTypeFullWords, memorizeWordVisibility, memorizeErrorHeatmap, memorizeScrambleHideUsed,
            memorizeIncludeReference, activeCycle) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            idToBytes(SETTINGS_ID), s.autoTrackReading, s.autoMarkMemorized, s.memorizeTypeFullWords, s.memorizeWordVisibility,
            s.memorizeErrorHeatmap, s.memorizeScrambleHideUsed, s.memorizeIncludeReference, s.activeCycle)
    }

    var autoTrackReading: Boolean
        get() = settings().autoTrackReading
        set(v) { val s = settings(); saveSettings(SettingsRow(v, s.autoMarkMemorized, s.memorizeTypeFullWords,
            s.memorizeWordVisibility, s.memorizeErrorHeatmap, s.memorizeScrambleHideUsed, s.memorizeIncludeReference, s.activeCycle))
            notify { settingsChanged() } }

    private var activeCycleSetting: Int
        get() = settings().activeCycle
        set(v) { val s = settings(); saveSettings(SettingsRow(s.autoTrackReading, s.autoMarkMemorized, s.memorizeTypeFullWords,
            s.memorizeWordVisibility, s.memorizeErrorHeatmap, s.memorizeScrambleHideUsed, s.memorizeIncludeReference, v)) }

    fun settingsBundle(): ReadingProgressSettingsBundle = settings().let {
        ReadingProgressSettingsBundle(it.autoMarkMemorized, it.memorizeTypeFullWords, it.memorizeWordVisibility,
            it.memorizeErrorHeatmap, it.memorizeScrambleHideUsed, it.memorizeIncludeReference)
    }

    fun settingsBundleJson(): String = json.encodeToString(ReadingProgressSettingsBundle.serializer(), settingsBundle())

    fun setSettingsBundle(jsonText: String) {
        val b = json.decodeFromString(ReadingProgressSettingsBundle.serializer(), jsonText)
        val s = settings()
        saveSettings(SettingsRow(s.autoTrackReading, b.autoMarkMemorized, b.memorizeTypeFullWords, b.memorizeWordVisibility,
            b.memorizeErrorHeatmap, b.memorizeScrambleHideUsed, b.memorizeIncludeReference, s.activeCycle))
        notify { settingsChanged() }
    }

    // ---------------------------------------------------------------- reading history

    private fun kjvBook(v11n: Versification, book: BibleBook): BibleBook = Verse(v11n, book, 1, 1).toV11n(KJVA).book

    fun latestCycle(): Int = db.query("SELECT COALESCE(MAX(cycle), 1) FROM ChapterReadHistory") { it.getInt(1) }.first()

    fun currentCycle(): Int = activeCycleSetting.takeIf { it > 0 } ?: latestCycle()

    fun setActiveCycle(cycle: Int) { activeCycleSetting = cycle; notify { activeCycleChanged() } }

    fun startNewCycle(): Int = (latestCycle() + 1).also { setActiveCycle(it) }

    fun recordChapterRead(v11n: Versification, book: BibleBook, chapter: Int, bookInitials: String, source: String) {
        val kb = kjvBook(v11n, book)
        val cycle = currentCycle()
        db.update("""INSERT INTO ChapterReadHistory (id, kjvBookOrdinal, chapter, cycle, readAt, bookInitials, source)
            VALUES (?, ?, ?, ?, ?, ?, ?)""", idToBytes(newId()), kb.ordinal, chapter, cycle, System.currentTimeMillis(),
            bookInitials, source.takeIf { it in setOf("MANUAL", "AUTO_SCROLL", "AUTO_TTS") } ?: "MANUAL")
        val count = chapterReadCount(kb, chapter, cycle)
        notify { chapterReadStatusChanged(kb.ordinal, chapter, count) }
    }

    fun chapterReadCount(kjvBook: BibleBook, chapter: Int, cycle: Int = currentCycle()): Int =
        db.query("SELECT COUNT(*) FROM ChapterReadHistory WHERE kjvBookOrdinal = ? AND chapter = ? AND cycle = ?",
            kjvBook.ordinal, chapter, cycle) { it.getInt(1) }.first()

    fun chapterReadCount(v11n: Versification, book: BibleBook, chapter: Int): Int = chapterReadCount(kjvBook(v11n, book), chapter)

    class ReadEntry(val id: String, val kjvBookOrdinal: Int, val chapter: Int, val readAt: Long, val bookInitials: String, val source: String) {
        val book: BibleBook get() = BibleBook.entries[kjvBookOrdinal]
    }

    private fun entries(where: String, vararg args: Any?): List<ReadEntry> =
        db.query("SELECT id, kjvBookOrdinal, chapter, readAt, bookInitials, source FROM ChapterReadHistory WHERE $where ORDER BY readAt DESC", *args) {
            ReadEntry(bytesToId(it.getBytes(1))!!, it.getInt(2), it.getInt(3), it.getLong(4), it.getString(5) ?: "", it.getString(6) ?: "MANUAL")
        }

    fun historyForChapter(book: BibleBook, chapter: Int, cycle: Int = currentCycle()) =
        entries("kjvBookOrdinal = ? AND chapter = ? AND cycle = ?", book.ordinal, chapter, cycle)
    fun historyForBook(book: BibleBook, cycle: Int = currentCycle()) = entries("kjvBookOrdinal = ? AND cycle = ?", book.ordinal, cycle)
    fun historyForDay(dayStart: Long, cycle: Int = currentCycle()): List<ReadEntry> {
        val end = Calendar.getInstance().apply { timeInMillis = dayStart; add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis
        return entries("readAt >= ? AND readAt < ? AND cycle = ?", dayStart, end, cycle)
    }

    fun deleteEntries(list: List<ReadEntry>, cycle: Int = currentCycle()) {
        if (list.isEmpty()) return
        db.tx { list.forEach { db.update("DELETE FROM ChapterReadHistory WHERE id = ?", idToBytes(it.id)) } }
        list.distinctBy { it.kjvBookOrdinal to it.chapter }.forEach { e ->
            val count = chapterReadCount(e.book, e.chapter, cycle)
            notify { chapterReadStatusChanged(e.kjvBookOrdinal, e.chapter, count) }
        }
    }

    fun chapterCountsForBook(book: BibleBook, cycle: Int = currentCycle()): Map<Int, Int> =
        db.query("SELECT chapter, COUNT(*) FROM ChapterReadHistory WHERE kjvBookOrdinal = ? AND cycle = ? GROUP BY chapter",
            book.ordinal, cycle) { it.getInt(1) to it.getInt(2) }.toMap()

    fun totalReadChapters(cycle: Int = currentCycle()): Int =
        db.query("SELECT COUNT(DISTINCT (kjvBookOrdinal || ',' || chapter)) FROM ChapterReadHistory WHERE cycle = ?", cycle) { it.getInt(1) }.first()

    fun distinctReadDays(cycle: Int = currentCycle()): Int {
        val cal = Calendar.getInstance()
        return db.query("SELECT readAt FROM ChapterReadHistory WHERE cycle = ?", cycle) { it.getLong(1) }
            .mapTo(HashSet()) { localDayStart(it, cal) }.size
    }

    /** Scripture books (AndBible's Scripture.isScripture: in the KJV versification, not an introduction). */
    val scriptureBooks: List<BibleBook> by lazy {
        val kjv = Versifications.instance().getVersification("KJV")
        val intros = setOf(BibleBook.INTRO_BIBLE, BibleBook.INTRO_OT, BibleBook.INTRO_NT)
        (0 until KJVA.bookCount).map { KJVA.getBook(it) }.filter { kjv.containsBook(it) && it !in intros }
    }

    val totalBibleChapters: Int by lazy { scriptureBooks.sumOf { KJVA.getLastChapter(it) } }

    /** Book -> fraction of distinct chapters read. */
    fun bookReadingProgress(cycle: Int = currentCycle()): Map<BibleBook, Float> {
        val counts = db.query("SELECT kjvBookOrdinal, COUNT(DISTINCT chapter) FROM ChapterReadHistory WHERE cycle = ? GROUP BY kjvBookOrdinal",
            cycle) { it.getInt(1) to it.getInt(2) }.toMap()
        return scriptureBooks.mapNotNull { b -> counts[b.ordinal]?.let { b to it.toFloat() / KJVA.getLastChapter(b) } }.toMap()
    }

    fun readingCalendar(startMs: Long, endMs: Long, cycle: Int = currentCycle()): Map<Long, Int> =
        bucket(db.query("SELECT readAt FROM ChapterReadHistory WHERE readAt >= ? AND readAt <= ? AND cycle = ?", startMs, endMs, cycle) { it.getLong(1) })

    fun memorizationCalendar(startMs: Long, endMs: Long): Map<Long, Int> =
        bucket(db.query("SELECT memorizedAt FROM MemorizedVerse WHERE memorizedAt >= ? AND memorizedAt <= ?", startMs, endMs) { it.getLong(1) })

    fun localDayStart(ts: Long, cal: Calendar = Calendar.getInstance()): Long {
        cal.timeInMillis = ts
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun bucket(ts: List<Long>): Map<Long, Int> {
        val cal = Calendar.getInstance()
        return ts.groupingBy { localDayStart(it, cal) }.eachCount()
    }

    // ---------------------------------------------------------------- memorization

    private fun kjv(range: VerseRange): VerseRange = range.toV11n(KJVA)

    fun markMemorized(range: VerseRange) {
        val k = kjv(range)
        val added = mutableListOf<Int>()
        db.tx {
            for (o in k.start.ordinal..k.end.ordinal) {
                val exists = db.query("SELECT 1 FROM MemorizedVerse WHERE kjvOrdinal = ?", o) { 1 }.isNotEmpty()
                if (!exists) {
                    db.update("INSERT INTO MemorizedVerse (id, kjvOrdinal, memorizedAt) VALUES (?, ?, ?)", idToBytes(newId()), o, System.currentTimeMillis())
                    added += o
                }
            }
        }
        if (added.isNotEmpty()) notify { memorizationChanged(added, emptyList(), emptyList(), emptyList()) }
    }

    fun unmarkMemorized(range: VerseRange) {
        val k = kjv(range)
        db.update("DELETE FROM MemorizedVerse WHERE kjvOrdinal >= ? AND kjvOrdinal <= ?", k.start.ordinal, k.end.ordinal)
        notify { memorizationChanged(emptyList(), (k.start.ordinal..k.end.ordinal).toList(), emptyList(), emptyList()) }
    }

    fun memorizedOrdinalsIn(startKjv: Int, endKjv: Int): List<Int> =
        db.query("SELECT kjvOrdinal FROM MemorizedVerse WHERE kjvOrdinal >= ? AND kjvOrdinal <= ? ORDER BY kjvOrdinal", startKjv, endKjv) { it.getInt(1) }

    fun countMemorizedIn(startKjv: Int, endKjv: Int): Int =
        db.query("SELECT COUNT(*) FROM MemorizedVerse WHERE kjvOrdinal >= ? AND kjvOrdinal <= ?", startKjv, endKjv) { it.getInt(1) }.first()

    fun totalMemorizedVerses(): Int = db.query("SELECT COUNT(*) FROM MemorizedVerse") { it.getInt(1) }.first()

    class Target(val id: String, val start: Int, val end: Int, val createdAt: Long) {
        val range: VerseRange get() = VerseRange(KJVA, Verse(KJVA, start), Verse(KJVA, end))
        val verseCount get() = end - start + 1
    }

    fun targets(): List<Target> = db.query("SELECT id, kjvOrdinalStart, kjvOrdinalEnd, createdAt FROM MemorizationTarget ORDER BY createdAt DESC") {
        Target(bytesToId(it.getBytes(1))!!, it.getInt(2), it.getInt(3), it.getLong(4))
    }

    private fun targetsOverlapping(s: Int, e: Int) = targets().filter { it.start <= e && it.end >= s }

    fun targetOrdinalsIn(startKjv: Int, endKjv: Int): List<Int> =
        targetsOverlapping(startKjv, endKjv).flatMap { maxOf(it.start, startKjv)..minOf(it.end, endKjv) }

    private fun insertTarget(s: Int, e: Int) = db.update(
        "INSERT INTO MemorizationTarget (id, kjvOrdinalStart, kjvOrdinalEnd, createdAt) VALUES (?, ?, ?, ?)",
        idToBytes(newId()), s, e, System.currentTimeMillis())

    fun addTarget(range: VerseRange) {
        val k = kjv(range)
        insertTarget(k.start.ordinal, k.end.ordinal)
        notify { memorizationChanged(emptyList(), emptyList(), (k.start.ordinal..k.end.ordinal).toList(), emptyList()) }
    }

    fun addTargetIfNeeded(range: VerseRange) {
        val k = kjv(range)
        if (targets().none { it.start == k.start.ordinal && it.end == k.end.ordinal }) addTarget(range)
    }

    fun removeTarget(t: Target) {
        db.update("DELETE FROM MemorizationTarget WHERE id = ?", idToBytes(t.id))
        notify { memorizationChanged(emptyList(), emptyList(), emptyList(), (t.start..t.end).toList()) }
    }

    /** Removes [range] from overlapping targets, splitting partially covered ones (computeRangeDifference). */
    fun removeTargetRange(range: VerseRange) {
        val k = kjv(range)
        val rs = k.start.ordinal
        val re = k.end.ordinal
        val overlapping = targetsOverlapping(rs, re)
        if (overlapping.isEmpty()) return
        val removed = mutableListOf<Int>()
        db.tx {
            for (t in overlapping) {
                db.update("DELETE FROM MemorizationTarget WHERE id = ?", idToBytes(t.id))
                removed += maxOf(t.start, rs)..minOf(t.end, re)
                if (t.start < rs) insertTarget(t.start, rs - 1)
                if (t.end > re) insertTarget(re + 1, t.end)
            }
        }
        if (removed.isNotEmpty()) notify { memorizationChanged(emptyList(), emptyList(), emptyList(), removed) }
    }

    /** (memorized verses inside targets, total target verses) */
    fun targetProgress(): Pair<Int, Int> {
        val ts = targets()
        return ts.sumOf { countMemorizedIn(it.start, it.end) } to ts.sumOf { it.verseCount }
    }

    class MemorizedRange(val range: VerseRange, val latestAt: Long)

    /** Consecutive memorized verses grouped into ranges, newest first. */
    fun memorizedRanges(): List<MemorizedRange> {
        val all = db.query("SELECT kjvOrdinal, memorizedAt FROM MemorizedVerse ORDER BY kjvOrdinal") { it.getInt(1) to it.getLong(2) }
        if (all.isEmpty()) return emptyList()
        val result = mutableListOf<MemorizedRange>()
        var start = all[0].first; var end = start; var latest = all[0].second
        for ((o, at) in all.drop(1)) {
            if (o == end + 1) { end = o; latest = maxOf(latest, at) }
            else {
                result += MemorizedRange(VerseRange(KJVA, Verse(KJVA, start), Verse(KJVA, end)), latest)
                start = o; end = o; latest = at
            }
        }
        result += MemorizedRange(VerseRange(KJVA, Verse(KJVA, start), Verse(KJVA, end)), latest)
        return result.sortedByDescending { it.latestAt }
    }

    fun bookMemorizationProgress(): Map<BibleBook, Float> = scriptureBooks.mapNotNull { b ->
        val s = Verse(KJVA, b, 1, 1).ordinal
        val lc = KJVA.getLastChapter(b)
        val e = Verse(KJVA, b, lc, KJVA.getLastVerse(b, lc)).ordinal
        val m = countMemorizedIn(s, e)
        if (m > 0) b to m.toFloat() / (e - s + 1) else null
    }.toMap()
}
