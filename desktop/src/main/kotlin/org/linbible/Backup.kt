/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 *
 * Backups in AndBible's formats (port of BackupControl, ImportDb and ExportStudyPads):
 *  - database backups (*.abdb.zip: AndBibleBackupManifest.json + db/<database files>), restored or merged per database,
 *  - StudyPad exports (an .abdb.zip with a bookmarks database containing only the chosen StudyPads),
 *  - module backups (*.abmd.zip: mods.d/ + modules/, the same layout as SWORD module zips).
 */
package org.linbible

import kotlinx.serialization.Serializable
import org.crosswire.common.util.NetUtil
import org.crosswire.jsword.book.Book
import org.crosswire.jsword.book.sword.SwordBookDriver
import org.crosswire.jsword.book.sword.SwordBookMetaData
import org.crosswire.jsword.book.sword.SwordBookPath
import org.crosswire.jsword.book.sword.SwordConstants
import java.io.File
import java.io.IOException
import java.sql.DriverManager
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object Backup {
    const val DATABASE_BACKUP_SUFFIX = ".abdb.zip"
    const val MODULE_BACKUP_SUFFIX = ".abmd.zip"
    const val MANIFEST = "AndBibleBackupManifest.json"

    /** Databases LinBible uses (workspaces, repositories and settings of AndBible are left alone). */
    val supported = listOf(DbCategory.BOOKMARKS, DbCategory.READINGPLANS, DbCategory.PROGRESS, DbCategory.MYDOCUMENTS, DbCategory.AI_SETTINGS)

    @Serializable
    data class Manifest(
        val backupType: String,
        val contains: Set<String>? = null,
        val manifestVersion: Int = 1,
        val andBibleVersion: Long = Addons.ANDBIBLE_VERSION,
    )

    private val tmpDir: File get() = File(AppDirs.cache, "backup").also { it.mkdirs() }

    // ---------------------------------------------------------------- database backup

    /** Consistent copy of a live database (like Room checkpoint + file copy). */
    private fun snapshot(c: DbCategory, target: File) {
        target.delete()
        c.db.exec("VACUUM INTO '${target.absolutePath.replace("'", "''")}'")
    }

    fun exportDatabases(target: File) {
        val files = supported.filter { File(Databases.dir, it.filename).exists() }.map { c ->
            File(tmpDir, c.filename).also { snapshot(c, it) }
        }
        val dbTypes = supported.map { if (it == DbCategory.AI_SETTINGS) "AI_SETTINGS" else it.name }.toSet()
        ZipOutputStream(target.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(json.encodeToString(Manifest.serializer(), Manifest("DB_BACKUP", dbTypes)).toByteArray())
            for (f in files) {
                zip.putNextEntry(ZipEntry("db/${f.name}"))
                f.inputStream().use { it.copyTo(zip) }
            }
        }
        files.forEach { it.delete() }
    }

    class BackupContents(val dir: File, val manifest: Manifest?, val databases: Map<DbCategory, File>) {
        fun cleanup() = dir.deleteRecursively()
        val isStudyPadExport get() = manifest?.backupType == "STUDYPAD_EXPORT"
    }

    private fun isSqlite(f: File): Boolean = f.inputStream().use { i ->
        val header = ByteArray(16); i.read(header) == 16 && String(header, Charsets.ISO_8859_1) == "SQLite format 3\u0000"
    }

    private fun sqliteVersion(f: File): Int =
        DriverManager.getConnection("jdbc:sqlite:${f.absolutePath}").use { c ->
            c.createStatement().use { st -> st.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) } }
        }

    /** Unpacks an .abdb.zip; only database files LinBible can use are returned. */
    fun open(zipFile: File): BackupContents {
        val dir = File(tmpDir, "unzip-${System.nanoTime()}").also { it.mkdirs() }
        var manifest: Manifest? = null
        ZipInputStream(zipFile.inputStream()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                val name = e.name.replace('\\', '/')
                if (e.isDirectory || name.contains("..")) continue
                if (name == MANIFEST) {
                    manifest = runCatching { json.decodeFromString(Manifest.serializer(), zin.readBytes().decodeToString()) }.getOrNull()
                    continue
                }
                if (!name.startsWith("db/")) continue
                val out = File(dir, name).also { it.parentFile.mkdirs() }
                out.outputStream().use { zin.copyTo(it) }
            }
        }
        val dbs = supported.mapNotNull { c ->
            val f = File(dir, "db/${c.filename}")
            if (f.exists() && isSqlite(f)) c to f else null
        }.toMap()
        return BackupContents(dir, manifest, dbs)
    }

    /** AndBible's bookmarksDbStats: (first label, number of labels, number of bookmarks). */
    fun bookmarkStats(f: File): Triple<String, Long, Long> =
        DriverManager.getConnection("jdbc:sqlite:${f.absolutePath}").use { c ->
            fun q(sql: String) = c.createStatement().use { st -> st.executeQuery(sql).use { if (it.next()) it.getObject(1) else null } }
            val first = q("""SELECT name from Label WHERE name NOT LIKE '\_\_%' ESCAPE '\' LIMIT 1""")?.toString() ?: "-"
            val labels = (q("SELECT count(*) from Label WHERE name NOT LIKE '\\_\\_%' ESCAPE '\\'") as Number).toLong()
            val bookmarks = (q("SELECT count(*) from BibleBookmark") as Number).toLong() + (q("SELECT count(*) from GenericBookmark") as Number).toLong()
            Triple(first, labels, bookmarks)
        }

    /** Brings a backup database file up to the current schema (newer AndBible versions are refused). */
    private fun migrate(c: DbCategory, f: File) {
        val v = sqliteVersion(f)
        if (v > c.version) throw IOException("${c.title} in this backup is from a newer AndBible version (database version $v)")
        RoomDb(c, f, withTriggers = false).apply { open() }.close()
    }

    /** Replaces the local database with the one from the backup. */
    fun restore(c: DbCategory, f: File) {
        migrate(c, f)
        Databases.close(c)
        val target = File(Databases.dir, c.filename)
        f.copyTo(target, overwrite = true)
        listOf("-journal", "-wal", "-shm").forEach { File(target.path + it).delete() }
        // afterRestore: the restored database is not tied to any sync folder
        c.db.tx {
            c.db.exec("DELETE FROM SyncStatus")
            c.db.exec("DELETE FROM SyncConfiguration")
        }
        Settings.setSyncEnabled(c, false)
        Databases.notifyChanged(c)
    }

    /** Merges rows from the backup database into the local one (AndBible's importDatabaseFile). */
    fun import(c: DbCategory, f: File) {
        migrate(c, f)
        val db = c.db
        synchronized(db) {
            db.exec("ATTACH DATABASE '${f.absolutePath.replace("'", "''")}' AS import")
            db.exec("PRAGMA foreign_keys=OFF")
            try {
                db.tx {
                    for (t in c.tables) {
                        val cols = db.query("SELECT name FROM pragma_table_info('${t.name}')") { it.getString(1) }.joinToString(",") { "`$it`" }
                        db.exec("INSERT OR IGNORE INTO ${t.name} ($cols) SELECT $cols FROM import.${t.name}")
                    }
                }
            } finally {
                db.exec("PRAGMA foreign_keys=ON")
                db.exec("DETACH DATABASE import")
            }
        }
        Databases.notifyChanged(c)
    }

    // ---------------------------------------------------------------- StudyPad export

    fun exportStudyPads(labelIds: List<String>, target: File) {
        val export = File(tmpDir, "studypads-${System.nanoTime()}.sqlite3")
        DriverManager.getConnection("jdbc:sqlite:${export.absolutePath}").use { RoomDb.createSchema(androidx.sqlite.db.SupportSQLiteDatabase(it), DbCategory.BOOKMARKS) }
        val db = DbCategory.BOOKMARKS.db
        synchronized(db) {
            db.exec("ATTACH DATABASE '${export.absolutePath.replace("'", "''")}' AS export")
            db.exec("PRAGMA foreign_keys=OFF")
            try {
                db.tx {
                    fun cols(t: String) = db.query("SELECT name FROM pragma_table_info('$t')") { it.getString(1) }
                    fun joined(t: String, prefix: String = "") = cols(t).joinToString(",") { "$prefix`$it`" }
                    for (id in labelIds) {
                        val hex = "x'${id.replace("-", "")}'"
                        db.exec("INSERT OR IGNORE INTO export.Label (${joined("Label")}) SELECT ${joined("Label")} FROM main.Label WHERE id = $hex")
                        for (kind in listOf("Bible", "Generic")) {
                            db.exec("""INSERT OR IGNORE INTO export.${kind}Bookmark (${joined("${kind}Bookmark")})
                                SELECT ${joined("${kind}Bookmark", "bb.")} FROM main.${kind}Bookmark bb
                                INNER JOIN main.${kind}BookmarkToLabel bbl ON bb.id = bbl.bookmarkId WHERE bbl.labelId = $hex""")
                            db.exec("""INSERT OR IGNORE INTO export.${kind}BookmarkNotes (${joined("${kind}BookmarkNotes")})
                                SELECT ${joined("${kind}BookmarkNotes", "bn.")} FROM main.${kind}BookmarkNotes bn
                                INNER JOIN main.${kind}BookmarkToLabel bbl ON bn.bookmarkId = bbl.bookmarkId WHERE bbl.labelId = $hex""")
                            db.exec("""INSERT OR IGNORE INTO export.${kind}BookmarkToLabel (${joined("${kind}BookmarkToLabel")})
                                SELECT ${joined("${kind}BookmarkToLabel")} FROM main.${kind}BookmarkToLabel WHERE labelId = $hex""")
                        }
                        db.exec("""INSERT OR IGNORE INTO export.StudyPadTextEntry (${joined("StudyPadTextEntry")})
                            SELECT ${joined("StudyPadTextEntry")} FROM main.StudyPadTextEntry WHERE labelId = $hex""")
                        db.exec("""INSERT OR IGNORE INTO export.StudyPadTextEntryText (${joined("StudyPadTextEntryText")})
                            SELECT ${joined("StudyPadTextEntryText", "t.")} FROM main.StudyPadTextEntryText t
                            INNER JOIN main.StudyPadTextEntry e ON t.studyPadTextEntryId = e.id WHERE e.labelId = $hex""")
                    }
                    // Primary labels of exported bookmarks might not be included
                    for (t in listOf("BibleBookmark", "GenericBookmark")) {
                        db.exec("UPDATE export.$t SET primaryLabelId = NULL WHERE primaryLabelId NOT IN (SELECT id FROM export.Label)")
                    }
                }
            } finally {
                db.exec("PRAGMA foreign_keys=ON")
                db.exec("DETACH DATABASE export")
            }
        }
        ZipOutputStream(target.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(json.encodeToString(Manifest.serializer(), Manifest("STUDYPAD_EXPORT", setOf("BOOKMARKS"))).toByteArray())
            zip.putNextEntry(ZipEntry("db/${DbCategory.BOOKMARKS.filename}"))
            export.inputStream().use { it.copyTo(zip) }
        }
        export.delete()
    }

    // ---------------------------------------------------------------- modules

    private val swordDir: File get() = SwordBookPath.getSwordDownloadDir()

    /** AndBible's module backup: conf file and data files of each book, relative to the SWORD folder. */
    fun exportModules(books: List<Book>, target: File) {
        val root = swordDir.canonicalFile
        ZipOutputStream(target.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(json.encodeToString(Manifest.serializer(), Manifest("MODULE_BACKUP", setOf("MODULES"))).toByteArray())
            val added = HashSet<String>()
            fun add(f: File) {
                val rel = f.canonicalFile.relativeTo(root).invariantSeparatorsPath
                if (rel.startsWith("..") || !added.add(rel)) return
                zip.putNextEntry(ZipEntry(rel))
                f.inputStream().use { it.copyTo(zip) }
            }
            for (b in books) {
                val md = b.bookMetaData
                val loc = md.location?.let { runCatching { File(it as java.net.URI) }.getOrNull() }
                val conf = (md as? SwordBookMetaData)?.configFile ?: File(root, "${SwordConstants.DIR_CONF}/${b.initials.lowercase()}.conf")
                if (conf.exists()) add(conf)
                if (loc != null && loc.exists()) {
                    if (loc.isDirectory) loc.walkTopDown().filter { it.isFile }.forEach(::add) else add(loc)
                }
            }
        }
    }

    /** Checks a SWORD module zip (ZipHandler.checkZipFile): returns files that would be overwritten. */
    fun checkModuleZip(zipFile: File): List<String> {
        var conf = false
        var data = false
        val existing = mutableListOf<String>()
        ZipFile(zipFile).use { z ->
            for (e in z.entries()) {
                val name = e.name.replace('\\', '/')
                if (!e.isDirectory && File(swordDir, name).exists()) existing += name
                when {
                    name.startsWith(SwordConstants.DIR_CONF + "/") && name.endsWith(SwordConstants.EXTENSION_CONF) -> conf = true
                    name.startsWith(SwordConstants.DIR_CONF + "/") -> {}
                    name.startsWith(SwordConstants.DIR_DATA + "/") -> data = true
                    name.startsWith("epub/") || name.startsWith("mysword/") || name.startsWith("mybible/") || name.startsWith("esword/") -> { conf = true; data = true }
                    name == MANIFEST -> {}
                    name == "META-INF/container.xml" -> throw IOException("This is an EPUB file")
                    else -> throw IOException("Not a module archive (unexpected file $name)")
                }
            }
        }
        if (!(conf && data)) throw IOException("Not a module archive")
        return existing
    }

    /** BackupControl.extractAndRegisterModuleArchive. */
    fun installModuleZip(zipFile: File) {
        val target = swordDir
        val confFiles = mutableListOf<File>()
        ZipInputStream(zipFile.inputStream()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                val name = e.name.replace('\\', '/')
                if (name == MANIFEST || name.contains("..")) continue
                val f = File(target, name)
                if (e.isDirectory) { f.mkdirs(); continue }
                f.parentFile.mkdirs()
                f.outputStream().use { zin.copyTo(it) }
                if (name.startsWith(SwordConstants.DIR_CONF) && name.endsWith(SwordConstants.EXTENSION_CONF)) confFiles += f
            }
        }
        val driver = SwordBookDriver.instance()
        for (conf in confFiles) {
            val md = SwordBookMetaData(conf, NetUtil.getURI(target))
            md.driver = driver
            SwordBookDriver.registerNewBook(md)
        }
        OtherFormats.registerAll()
    }
}
