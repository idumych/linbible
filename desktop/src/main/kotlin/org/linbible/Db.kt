/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 *
 * SQLite databases in exactly AndBible's (Room) format: created from AndBible's exported Room schemas, upgraded with
 * AndBible's own migrations, with the same LogEntry change-tracking triggers. This keeps backups and cloud sync
 * interchangeable with AndBible on Android.
 */
package org.linbible

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.bible.android.database.IdType
import net.bible.android.database.migrations.aiSettingsMigrations
import net.bible.android.database.migrations.bookmarkMigrations
import net.bible.android.database.migrations.myDocumentMigrations
import net.bible.android.database.migrations.progressMigrations
import net.bible.android.database.migrations.readingPlanMigrations
import net.bible.android.database.migrations.workspacesMigrations
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.UUID

const val TRIGGERS_DISABLED_KEY = "triggersDisabled"
const val LAST_PATCH_WRITTEN_KEY = "lastPatchWritten"
const val LAST_SYNCHRONIZED_KEY = "lastSynchronized"
const val SYNC_FOLDER_FILE_ID_KEY = "syncId"
const val SYNC_DEVICE_FOLDER_FILE_ID_KEY = "deviceFolderId"

/** Mirrors AndBible's SyncableDatabaseDefinition. */
enum class DbCategory(
    val filename: String,
    val schema: String,
    val version: Int,
    val migrations: () -> Array<out Migration>,
    val tables: List<Table>,
    val title: String,
) {
    BOOKMARKS("bookmarks.sqlite3", "net.bible.android.database.BookmarkDatabase", 12, { bookmarkMigrations }, listOf(
        Table("Label"), Table("BibleBookmark"), Table("BibleBookmarkNotes", "bookmarkId"),
        Table("BibleBookmarkToLabel", "bookmarkId", "labelId"), Table("GenericBookmark"),
        Table("GenericBookmarkNotes", "bookmarkId"), Table("GenericBookmarkToLabel", "bookmarkId", "labelId"),
        Table("StudyPadTextEntry"), Table("StudyPadTextEntryText", "studyPadTextEntryId"),
    ), "Bookmarks, labels and StudyPads"),
    WORKSPACES("workspaces.sqlite3", "net.bible.android.database.WorkspaceDatabase", 24, { workspacesMigrations }, listOf(
        Table("Workspace"), Table("Window"), Table("PageManager", "windowId"),
        Table("WorkspaceLabelOverride", "workspaceId", "labelId"), Table("GlobalTextDisplaySettings"),
    ), "Workspaces"),
    READINGPLANS("readingplans.sqlite3", "net.bible.android.database.ReadingPlanDatabase", 1, { readingPlanMigrations }, listOf(
        Table("ReadingPlan"), Table("ReadingPlanStatus"),
    ), "Reading plans"),
    MYDOCUMENTS("mydocuments.sqlite3", "net.bible.android.database.mydocument.MyDocumentDatabase", 4, { myDocumentMigrations }, listOf(
        Table("MyDocument"), Table("MyDocumentPage"), Table("MyDocumentPageContent", "pageId"), Table("AiPageCacheEntry", "pageId"),
    ), "AI documents"),
    AI_SETTINGS("ai_settings.sqlite3", "net.bible.android.database.AiSettingsDatabase", 23, { aiSettingsMigrations }, listOf(
        Table("LlmProviderConfig"), Table("LlmConfiguredModel"), Table("AgentPrompt"), Table("GlobalAiSettings"),
        Table("LlmUsageRecord"), Table("PromptCategory"), Table("BuiltinPromptOverride"),
    ), "AI settings and prompts"),
    PROGRESS("progress.sqlite3", "net.bible.android.database.progress.ProgressDatabase", 9, { progressMigrations }, listOf(
        Table("MemorizedVerse"), Table("ChapterReadHistory"), Table("MemorizationTarget"), Table("GlobalReadingProgressSettings"),
    ), "Reading progress and memorized verses");

    class Table(val name: String, val idField1: String = "id", val idField2: String? = null)

    val db: RoomDb get() = Databases.get(this)
}

object Databases {
    val dir: File get() = File(AppDirs.data, "databases").also { it.mkdirs() }
    private val open = HashMap<DbCategory, RoomDb>()

    @Synchronized
    fun get(c: DbCategory): RoomDb = open.getOrPut(c) { RoomDb(c, File(dir, c.filename)).apply { open() } }

    @Synchronized
    fun closeAll() { open.values.forEach { it.close() }; open.clear() }

    /** Closes one database, e.g. before its file is replaced; the next access reopens it. */
    @Synchronized
    fun close(c: DbCategory) { open.remove(c)?.close() }

    /** Closes and reopens a database whose file has been replaced (restore, initial sync download). */
    @Synchronized
    fun reopen(c: DbCategory): RoomDb {
        open.remove(c)?.close()
        return get(c)
    }

    private val changeListeners = HashMap<DbCategory, MutableList<() -> Unit>>()

    /** [f] is called after the database of [c] was changed from outside (sync, restore, import). */
    fun onChanged(c: DbCategory, f: () -> Unit) { synchronized(changeListeners) { changeListeners.getOrPut(c) { mutableListOf() } += f } }

    fun notifyChanged(c: DbCategory) {
        val ls = synchronized(changeListeners) { changeListeners[c]?.toList() ?: emptyList() }
        ls.forEach { runCatching(it).onFailure { e -> Log.e("Change listener for $c failed", e) } }
    }

    val deviceId: String by lazy {
        val f = File(AppDirs.config, "device-id")
        f.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
            ?: UUID.randomUUID().toString().also { f.parentFile.mkdirs(); f.writeText(it) }
    }
}

fun idToBytes(id: String?): ByteArray? = id?.takeIf { it.isNotEmpty() }?.let { IdType.fromString(it).toByteArray() }
fun bytesToId(b: ByteArray?): String? = IdType.fromByteArray(b)?.toString()?.takeIf { it.isNotEmpty() }

class RoomDb(val category: DbCategory, val file: File, private val withTriggers: Boolean = true) {
    lateinit var connection: Connection
        private set
    val support get() = SupportSQLiteDatabase(connection)

    fun open() {
        connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
        exec("PRAGMA journal_mode = TRUNCATE")
        exec("PRAGMA foreign_keys = ON")
        val version = support.version
        when {
            version == 0 && !hasTable(category.tables.first().name) -> createSchema(this.support, category)
            version > category.version -> {
                connection.close()
                throw IllegalStateException("${file.name} was created by a newer AndBible (database version $version, " +
                    "LinBible supports ${category.version})")
            }
            version < category.version -> migrate(version)
        }
        if (withTriggers) { dropTriggers(); createTriggers() }
    }

    fun close() = runCatching { connection.close() }

    private fun migrate(from: Int) {
        Log.i("Migrating ${file.name} from version $from to ${category.version}")
        val all = category.migrations()
        var v = from
        exec("PRAGMA foreign_keys = OFF")
        tx {
            while (v < category.version) {
                // Like Room: take the migration that gets furthest from the current version.
                val m = all.filter { it.startVersion == v && it.endVersion <= category.version }.maxByOrNull { it.endVersion }
                    ?: throw IllegalStateException("No migration for ${file.name} from version $v")
                m.migrate(support)
                v = m.endVersion
            }
            support.version = category.version
            exec("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '${identityHash(category)}')")
        }
        exec("PRAGMA foreign_keys = ON")
    }

    fun hasTable(name: String): Boolean =
        query("SELECT count(*) FROM sqlite_master WHERE type='table' AND name=?", name) { it.getInt(1) }.first() > 0

    // ---------------------------------------------------------------- sync triggers (from AndBible SyncUtilities)

    fun createTriggers() {
        val deviceId = Databases.deviceId
        val timeStampFunc = "CAST(UNIXEPOCH('subsec') * 1000 AS INTEGER)"
        val whenCondition = "WHEN (SELECT count(*) FROM SyncConfiguration WHERE keyName='$TRIGGERS_DISABLED_KEY' AND booleanValue = 1 LIMIT 1) = 0"
        for (t in category.tables) {
            fun where(prefix: String) = if (t.idField2 == null) "entityId1 = $prefix.${t.idField1}"
                else "entityId1 = $prefix.${t.idField1} AND entityId2 = $prefix.${t.idField2}"
            fun insert(prefix: String) = if (t.idField2 == null) "$prefix.${t.idField1},''"
                else "$prefix.${t.idField1},$prefix.${t.idField2}"
            exec("""CREATE TRIGGER IF NOT EXISTS ${t.name}_inserts AFTER INSERT ON ${t.name} $whenCondition
                BEGIN DELETE FROM LogEntry WHERE ${where("NEW")} AND tableName = '${t.name}';
                INSERT INTO LogEntry VALUES ('${t.name}', ${insert("NEW")}, 'UPSERT', $timeStampFunc, '$deviceId');
                END;""")
            exec("""CREATE TRIGGER IF NOT EXISTS ${t.name}_updates AFTER UPDATE ON ${t.name} $whenCondition
                BEGIN DELETE FROM LogEntry WHERE ${where("OLD")} AND tableName = '${t.name}';
                INSERT INTO LogEntry VALUES ('${t.name}', ${insert("OLD")}, 'UPSERT', $timeStampFunc, '$deviceId');
                END;""")
            exec("""CREATE TRIGGER IF NOT EXISTS ${t.name}_deletes AFTER DELETE ON ${t.name} $whenCondition
                BEGIN DELETE FROM LogEntry WHERE ${where("OLD")} AND tableName = '${t.name}';
                INSERT INTO LogEntry VALUES ('${t.name}', ${insert("OLD")}, 'DELETE', $timeStampFunc, '$deviceId');
                END;""")
        }
    }

    fun dropTriggers() {
        for (t in category.tables) {
            exec("DROP TRIGGER IF EXISTS ${t.name}_inserts")
            exec("DROP TRIGGER IF EXISTS ${t.name}_updates")
            exec("DROP TRIGGER IF EXISTS ${t.name}_deletes")
        }
    }

    // ---------------------------------------------------------------- helpers

    fun exec(sql: String) { connection.createStatement().use { it.execute(sql) } }

    fun update(sql: String, vararg args: Any?): Int = synchronized(this) {
        connection.prepareStatement(sql).use { st -> bind(st, args); st.executeUpdate() }
    }

    fun <T> query(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> = synchronized(this) {
        connection.prepareStatement(sql).use { st ->
            bind(st, args)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
        }
    }

    private fun bind(st: PreparedStatement, args: Array<out Any?>) = args.forEachIndexed { i, a ->
        when (a) {
            is Boolean -> st.setInt(i + 1, if (a) 1 else 0)
            else -> st.setObject(i + 1, a)
        }
    }

    private var txDepth = 0
    fun <T> tx(block: () -> T): T = synchronized(this) {
        if (txDepth > 0) return block()
        connection.autoCommit = false
        txDepth++
        try {
            val r = block()
            connection.commit()
            r
        } catch (e: Throwable) {
            connection.rollback()
            throw e
        } finally {
            txDepth--
            connection.autoCommit = true
        }
    }

    // SyncConfiguration (AndBible SyncDao)
    fun getString(key: String): String? = query("SELECT stringValue FROM SyncConfiguration WHERE keyName = ?", key) { it.getString(1) }.firstOrNull()
    fun getLong(key: String): Long? = query("SELECT longValue FROM SyncConfiguration WHERE keyName = ?", key) { r -> r.getLong(1).takeIf { !r.wasNull() } }.firstOrNull()
    fun setConfig(key: String, value: Long) { update("INSERT OR REPLACE INTO SyncConfiguration (keyName, longValue) VALUES (?, ?)", key, value) }
    fun setConfig(key: String, value: String) { update("INSERT OR REPLACE INTO SyncConfiguration (keyName, stringValue) VALUES (?, ?)", key, value) }
    fun setConfig(key: String, value: Boolean) { update("INSERT OR REPLACE INTO SyncConfiguration (keyName, booleanValue) VALUES (?, ?)", key, value) }
    fun removeConfig(key: String) { update("DELETE FROM SyncConfiguration WHERE keyName = ?", key) }

    companion object {
        private val schemaCache = HashMap<Pair<DbCategory, Int>, JsonObject>()

        fun schemaJson(c: DbCategory, version: Int = c.version): JsonObject = schemaCache.getOrPut(c to version) {
            val text = RoomDb::class.java.getResourceAsStream("/schemas/${c.schema}/$version.json")
                ?.bufferedReader()?.readText() ?: error("Missing Room schema for ${c.schema} v$version")
            json.parseToJsonElement(text).jsonObject["database"]!!.jsonObject
        }

        fun identityHash(c: DbCategory): String = schemaJson(c)["identityHash"]!!.jsonPrimitive.content

        /** Creates all tables, indices and views like Room's generated onCreate. */
        fun createSchema(db: SupportSQLiteDatabase, c: DbCategory, version: Int = c.version) {
            val s = schemaJson(c, version)
            check(s["version"]!!.jsonPrimitive.int == version)
            for (e in s["entities"]!!.jsonArray) {
                val o = e.jsonObject
                val name = o["tableName"]!!.jsonPrimitive.content
                db.execSQL(o["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", name))
                o["indices"]?.jsonArray?.forEach { i ->
                    db.execSQL(i.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", name))
                }
            }
            s["views"]?.jsonArray?.forEach { v ->
                val o = v.jsonObject
                db.execSQL(o["createSql"]!!.jsonPrimitive.content.replace("\${VIEW_NAME}", o["viewName"]!!.jsonPrimitive.content))
            }
            s["setupQueries"]!!.jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
            db.version = version
        }
    }
}

/**
 * Keeps in-memory model objects and their SQLite rows in step: [write] compares the rows of the current model with
 * the rows last written and issues only the needed upserts and deletes, so the sync triggers log exactly the
 * entities that changed.
 */
class TableSpec(val name: String, val keys: List<String>, val cols: List<String>, val idCols: Set<String> = emptySet()) {
    fun read(db: RoomDb): List<Map<String, Any?>> =
        db.query("SELECT ${cols.joinToString(",") { "`$it`" }} FROM `$name`") { rs ->
            cols.associateWith { c ->
                if (c in idCols) bytesToId(rs.getBytes(c)) else rs.getObject(c)
            }
        }
}

class RowSync(private val db: RoomDb, private val specs: List<TableSpec>) {
    /** table -> key -> row */
    private var snapshot: Map<String, Map<List<Any?>, List<Any?>>> = emptyMap()

    private fun index(spec: TableSpec, rows: List<Map<String, Any?>>): Map<List<Any?>, List<Any?>> =
        rows.associate { r -> spec.keys.map { r[it] } to spec.cols.map { normalize(r[it]) } }

    private fun normalize(v: Any?): Any? = when (v) {
        is Boolean -> if (v) 1L else 0L
        is Int -> v.toLong()
        is Short -> v.toLong()
        else -> v
    }

    /** Records [rows] (as read from the database) as the current persisted state. */
    fun reset(rows: Map<String, List<Map<String, Any?>>>) {
        snapshot = specs.associate { it.name to index(it, rows[it.name] ?: emptyList()) }
    }

    fun write(rows: Map<String, List<Map<String, Any?>>>) {
        val next = specs.associate { it.name to index(it, rows[it.name] ?: emptyList()) }
        db.tx {
            // children first for deletes, parents first for upserts (specs are given parent-first)
            for (spec in specs.asReversed()) {
                val old = snapshot[spec.name] ?: emptyMap()
                val new = next[spec.name]!!
                for (k in old.keys - new.keys) {
                    db.update("DELETE FROM `${spec.name}` WHERE ${spec.keys.joinToString(" AND ") { "`$it` = ?" }}",
                        *k.mapIndexed { i, v -> arg(spec, spec.keys[i], v) }.toTypedArray())
                }
            }
            for (spec in specs) {
                val old = snapshot[spec.name] ?: emptyMap()
                val new = next[spec.name]!!
                val cols = spec.cols.joinToString(",") { "`$it`" }
                val qs = spec.cols.joinToString(",") { "?" }
                val set = spec.cols.filter { it !in spec.keys }.joinToString(",") { "`$it`=excluded.`$it`" }
                val conflict = spec.keys.joinToString(",") { "`$it`" }
                val sql = "INSERT INTO `${spec.name}` ($cols) VALUES ($qs) ON CONFLICT($conflict) DO " +
                    if (set.isEmpty()) "NOTHING" else "UPDATE SET $set"
                for ((k, row) in new) {
                    if (old[k] == row) continue
                    db.update(sql, *row.mapIndexed { i, v -> arg(spec, spec.cols[i], v) }.toTypedArray())
                }
            }
        }
        snapshot = next
    }

    private fun arg(spec: TableSpec, col: String, v: Any?): Any? = if (col in spec.idCols) idToBytes(v as String?) else v
}
