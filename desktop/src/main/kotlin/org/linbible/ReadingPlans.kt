/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 *
 * Reading plans: port of AndBible's ReadingPlanTextFileDao, ReadingPlanControl, ReadingStatus and
 * ReadingPlanRepository. Plan progress lives in AndBible's readingplans.sqlite3 (synced like on Android).
 */
package org.linbible

import kotlinx.serialization.Serializable
import org.crosswire.jsword.passage.Key
import org.crosswire.jsword.passage.OsisParser
import org.crosswire.jsword.passage.PassageKeyFactory
import org.crosswire.jsword.versification.Versification
import org.crosswire.jsword.versification.system.SystemKJV
import org.crosswire.jsword.versification.system.SystemNRSVA
import org.crosswire.jsword.versification.system.Versifications
import java.io.File
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.Properties
import kotlin.math.roundToLong

class ReadingPlanInfo(
    val code: String,
    val name: String,
    val description: String,
    val numberOfDays: Int,
    val versification: Versification,
    val isDateBased: Boolean,
) {
    val startDate: Date? get() = ReadingPlans.startDate(code)
}

class DaysReading(val day: Int, readings: String?, val plan: ReadingPlanInfo) {
    /** reading date for date-based plans */
    var readingDate: Date? = null
        private set
    val keys: List<Key>

    init {
        val list = mutableListOf<Key>()
        if (!readings.isNullOrEmpty()) {
            val parts = if (readings.contains(';')) {
                readingDate = parsePlanDate(readings.substringBefore(';'))
                readings.substringAfter(';').split(",").filter { it.isNotEmpty() }
            } else readings.split(",").filter { it.isNotEmpty() }
            parts.forEach { list.add(passageKey(plan.versification, it)) }
        }
        keys = list
    }

    val dayDesc get() = "Day $day"

    val dateString: String get() {
        val fmt = SimpleDateFormat.getDateInstance()
        readingDate?.let { return fmt.format(it) }
        val start = plan.startDate ?: return ""
        val cal = Calendar.getInstance().apply { time = start; add(Calendar.DAY_OF_MONTH, day - 1) }
        return fmt.format(cal.time)
    }

    val readingsDesc get() = keys.joinToString(", ") { it.name }

    private fun parsePlanDate(s: String): Date {
        val year = Calendar.getInstance().get(Calendar.YEAR)
        return try {
            SimpleDateFormat("MMM-d/yyyy", Locale.US).parse("$s/$year")
        } catch (e: ParseException) {
            SimpleDateFormat("MMM-d/yyyy", Locale.getDefault()).parse("$s/$year")
        }
    }

    companion object {
        /** PassageReader: OSIS reference first, then a normal reference. */
        fun passageKey(v11n: Versification, passage: String): Key {
            val p = passage.trim()
            val key = runCatching { OsisParser().parseOsisRef(v11n, p) }.getOrNull()
                ?: runCatching { PassageKeyFactory.instance().getKey(v11n, p) }.getOrNull()
            return key ?: PassageKeyFactory.instance().createEmptyKeyList(v11n)
        }
    }
}

object ReadingPlans {
    private const val VERSIFICATION = "Versification"

    /** Plans bundled with AndBible, with their translated names (strings.xml). */
    private val bundled = linkedMapOf(
        "y1ntpspr" to ("NT, Psalms & Proverbs in a Year" to
            "Courtesy of HEARTLIGHT Internet Magazine, http://www.heartlight.org, permission granted"),
        "y1ot1nt1_chronological" to ("Chronological 1-Year through Bible" to
            "Plan based on chronological events in the Bible. The chronological time-line is approximate."),
        "y1ot1nt1_OTandNT" to ("1-Year OT+NT Simultaneously" to
            "Read through the Bible in 1 year, with chapters in the OT and NT every day."),
        "y1ot1nt1_OTthenNT" to ("1-Year through Bible" to
            "Read through the Bible in 1 year, starting in Genesis and finishing in Revelation. The OT is first, then the NT."),
        "y1ot1nt2_mcheyne" to ("M'Cheyne 1-Year 4-chapter/day" to
            "This plan is based on the M’Cheyne reading system, featuring four different readings for use in both family " +
            "and personal devotions. Each day has two passages from the Old Testament, one from the New Testament, and one " +
            "from either the Psalms or the Gospels. In one year, you read the Old Testament once and the New Testament and " +
            "Psalms twice."),
        "y1ot6nt4_profHorner" to ("Prof. Horner 10-chapter/day Reading Plan" to "Courtesy of Prof. Horner, permission granted"),
        "y2ot1ntps2" to ("2-Year Through Bible, NT+PS twice" to
            "Read through the Bible in 2 years, reading the NT and Psalms twice. Courtesy of Wholesome Words, " +
            "http://www.wholesomewords.org/, permission granted"),
    )

    /** User plans, like AndBible's jsword/readingplan folder. */
    val userPlanDir: File get() = File(AppDirs.data, "readingplan")

    private class PlanFile(val props: Properties, val text: String, val addon: Addons.ReadingPlanAddon?)
    private val cache = HashMap<String, Pair<PlanFile, ReadingPlanInfo>>()

    val db get() = DbCategory.READINGPLANS.db

    fun planCodes(): List<String> {
        val codes = LinkedHashSet(bundled.keys)
        userPlanDir.list()?.filter { it.endsWith(".properties") }?.forEach { codes += it.removeSuffix(".properties") }
        codes += Addons.readingPlans.keys
        return codes.toList()
    }

    fun plans(): List<ReadingPlanInfo> = planCodes().mapNotNull { runCatching { info(it) }.getOrNull() }

    private fun planText(code: String): Pair<String, Addons.ReadingPlanAddon?> {
        val userFile = File(userPlanDir, "$code.properties")
        if (userFile.exists()) return userFile.readText() to null
        Addons.readingPlans[code]?.let { a -> if (a.file.exists()) return a.file.readText() to a }
        val res = ReadingPlans::class.java.getResourceAsStream("/readingplan/$code.properties")
            ?: error("Reading plan $code not found")
        return res.bufferedReader().readText() to null
    }

    @Synchronized
    private fun load(code: String): Pair<PlanFile, ReadingPlanInfo> = cache.getOrPut(code) {
        val (text, addon) = planText(code)
        val props = Properties().apply { load(text.reader()) }
        val days = props.keys.mapNotNull { (it as String).toIntOrNull() }.maxOrNull() ?: 0
        val v11nName = addon?.book?.getProperty(VERSIFICATION) as String? ?: props.getProperty(VERSIFICATION, SystemKJV.V11N_NAME)
        val v11n = runCatching { Versifications.instance().getVersification(v11nName) }.getOrNull()
            ?: Versifications.instance().getVersification(SystemNRSVA.V11N_NAME)
        val dateBased = addon?.isDateBased ?: (props["1"]?.toString()?.contains(Regex("^([a-z]|[A-Z]){3}-([0-9]{1,2});")) == true)
        // name and description from the first comment lines (ReadingPlanTextFileDao.getNameAndDescFromProperties)
        var name: String? = null
        var desc: String? = null
        if (addon != null) {
            name = addon.book.name
            desc = addon.book.getProperty("ShortPromo") as String?
        } else {
            var lineCount = 0
            text.lineSequence().take(5).filter { it.startsWith("#") }.forEach { line ->
                val l = line.trim().replaceFirst(Regex("^(\\s*#*\\s*)"), "").trim()
                if (lineCount++ == 0) name = l else desc = "${desc ?: ""} $l ".trim()
            }
        }
        bundled[code]?.let { (n, d) -> name = n; desc = d }
        PlanFile(props, text, addon) to ReadingPlanInfo(code, name ?: code, desc ?: "", days, v11n, dateBased)
    }

    fun info(code: String): ReadingPlanInfo = load(code).second

    fun reading(code: String, day: Int): DaysReading = load(code).let { (f, info) -> DaysReading(day, f.props.getProperty(day.toString()), info) }

    fun readingList(code: String): List<DaysReading> = (1..info(code).numberOfDays).map { reading(code, it) }

    // ---------------------------------------------------------------- current plan

    var currentPlanCode: String?
        get() = Settings.state.readingPlan?.takeIf { it.isNotEmpty() }
        set(v) { Settings.state.readingPlan = v; Settings.save() }

    val currentPlanExists get() = currentPlanCode?.let { runCatching { info(it) }.isSuccess } == true

    fun currentDay(code: String): Int {
        val info = info(code)
        return if (info.isDateBased) {
            val today = truncatedDate()
            readingList(code).find { it.readingDate == today }?.day ?: 1
        } else maxOf(planRow(code)?.currentDay ?: 0, 1)
    }

    private fun setCurrentDay(code: String, day: Int) {
        if (info(code).isDateBased) return
        val row = planRow(code)
        if (row == null) insertPlan(code, truncatedDate().time, day)
        else db.update("UPDATE ReadingPlan SET planCurrentDay = ? WHERE id = ?", day, idToBytes(row.id))
    }

    fun startPlan(code: String, date: Date = truncatedDate()) {
        currentPlanCode = code
        val row = planRow(code)
        if (row == null) insertPlan(code, date.time, 1)
        else db.update("UPDATE ReadingPlan SET planStartDate = ? WHERE id = ?", date.time, idToBytes(row.id))
    }

    fun startDate(code: String): Date? = planRow(code)?.startDate?.let(::Date)

    fun reset(code: String) {
        if (code == currentPlanCode) currentPlanCode = null
        db.tx {
            db.update("DELETE FROM ReadingPlanStatus WHERE planCode = ?", code)
            db.update("DELETE FROM ReadingPlan WHERE planCode = ?", code)
        }
    }

    private class PlanRow(val id: String, val startDate: Long, val currentDay: Int)

    private fun planRow(code: String): PlanRow? =
        db.query("SELECT id, planStartDate, planCurrentDay FROM ReadingPlan WHERE planCode = ?", code) {
            PlanRow(bytesToId(it.getBytes(1))!!, it.getLong(2), it.getInt(3))
        }.firstOrNull()

    private fun insertPlan(code: String, start: Long, day: Int) {
        db.update("INSERT INTO ReadingPlan (planCode, planStartDate, planCurrentDay, id) VALUES (?, ?, ?, ?)",
            code, start, day, idToBytes(newId()))
    }

    // ---------------------------------------------------------------- read status (ReadingStatus JSON format)

    @Serializable private data class ChapterRead(val readingNumber: Int, var isRead: Boolean = false)
    @Serializable private data class StatusJson(val chapterReadArray: ArrayList<ChapterRead>)

    /** Read flags for one day's readings; days before the current day count as read (HistoricReadingStatus). */
    fun isRead(code: String, day: Int, readingNo: Int): Boolean {
        if (!info(code).isDateBased && day < currentDay(code)) return true
        return status(code, day).chapterReadArray.find { it.readingNumber == readingNo }?.isRead ?: false
    }

    fun isAllRead(code: String, day: Int): Boolean = (1..reading(code, day).keys.size).all { isRead(code, day, it) }

    private fun status(code: String, day: Int): StatusJson =
        db.query("SELECT readingStatus FROM ReadingPlanStatus WHERE planCode = ? AND planDay = ?", code, day) { it.getString(1) }
            .firstOrNull()?.let { runCatching { json.decodeFromString(StatusJson.serializer(), it) }.getOrNull() }
            ?: StatusJson(ArrayList())

    fun setRead(code: String, day: Int, readingNo: Int, read: Boolean = true) {
        val s = status(code, day)
        val existing = s.chapterReadArray.find { it.readingNumber == readingNo }
        if (existing == null) s.chapterReadArray += ChapterRead(readingNo, read) else existing.isRead = read
        s.chapterReadArray.sortBy { it.readingNumber }
        val text = json.encodeToString(StatusJson.serializer(), s)
        db.update("""INSERT INTO ReadingPlanStatus (planCode, planDay, readingStatus, id) VALUES (?, ?, ?, ?)
            ON CONFLICT(planCode, planDay) DO UPDATE SET readingStatus = excluded.readingStatus""",
            code, day, text, idToBytes(newId()))
    }

    fun setAllRead(code: String, day: Int) = (1..reading(code, day).keys.size).forEach { setRead(code, day, it) }

    private fun deleteOldStatuses(code: String, day: Int) {
        if (!info(code).isDateBased) db.update("DELETE FROM ReadingPlanStatus WHERE planCode = ? AND planDay < ?", code, day)
    }

    private fun dueDay(code: String): Long {
        val start = startDate(code) ?: return 0
        val diff = (truncatedDate().time - start.time) / (1000.0 * 60 * 60 * 24)
        return diff.roundToLong() + 1
    }

    /** Port of ReadingPlanControl.done: marks [day] complete; returns the next day to show, or -1 when up to date. */
    fun done(code: String, day: Int, force: Boolean): Int {
        val info = info(code)
        var next = -1
        if (force) {
            setCurrentDay(code, day)
            setAllRead(code, day)
        }
        if (currentDay(code) == day) {
            deleteOldStatuses(code, day)
            if (info.numberOfDays == day) {
                reset(code)
                return -1
            }
            var nextDay = day + 1
            setCurrentDay(code, nextDay)
            if (reading(code, nextDay).keys.isEmpty()) nextDay = done(code, nextDay, force)
            next = nextDay
        } else if (info.numberOfDays > day) {
            next = day + 1
        }
        if (next != -1 && dueDay(code) < next) next = -1
        return next
    }

    fun truncatedDate(): Date = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.time

    /** Listeners (the reading plan window) refresh when sync changed plan data. */
    val listeners = mutableListOf<() -> Unit>()
    fun externallyChanged() = listeners.toList().forEach { it() }

    init { Databases.onChanged(DbCategory.READINGPLANS) { externallyChanged() } }
}
