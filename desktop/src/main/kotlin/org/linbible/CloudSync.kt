/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 *
 * Cloud sync with Nextcloud (WebDAV), compatible with AndBible's device sync: same folders, same gzipped SQLite patch
 * files and the same LogEntry based change tracking. Port of AndBible's CloudSync, SyncUtilities and
 * NextCloudAdapter. (AndBible's Google Drive adapter needs Google's Android sign-in and is not available.)
 */
package org.linbible

import org.w3c.dom.Element
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.DriverManager
import java.text.SimpleDateFormat
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.xml.parsers.DocumentBuilderFactory

const val INITIAL_BACKUP_FILENAME = "initial.sqlite3.gz"
const val NEXTCLOUD_SECRET_FILE_NAME_KEY = "nextCloudSecretFile"
/** AndBible's application id; sync folders are named after it so that LinBible and AndBible share them. */
const val ANDBIBLE_PACKAGE = "net.bible.android.activity"

class CloudFile(val id: String, val name: String, val size: Long, val createdTime: Long, val parentId: String, val isFolder: Boolean = false)

/** Nextcloud / ownCloud over WebDAV (NextCloudAdapter). File ids are paths relative to the user's files root. */
class WebDavAdapter(serverUrl: String, private val user: String, password: String, folderPath: String?) {
    private val server = serverUrl.trim().trimEnd('/').let { if (it.startsWith("http")) it else "https://$it" }
    private val davRoot = "$server/remote.php/dav"
    private val filesRoot = "$davRoot/files/${enc(user)}"
    private val auth = "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build()

    private val baseFolder: String? = folderPath?.trim()?.trim('/')?.replace(Regex("/+"), "/")?.takeIf { it.isNotBlank() }?.let { "/$it" }
    private var baseFolderCreated = false

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
    private fun url(path: String) = filesRoot + path.split("/").joinToString("/") { enc(it) }

    private fun request(method: String, path: String, body: HttpRequest.BodyPublisher = HttpRequest.BodyPublishers.noBody(),
                        headers: Map<String, String> = emptyMap(), fullUrl: String? = null): HttpRequest {
        val b = HttpRequest.newBuilder(URI(fullUrl ?: url(path))).timeout(Duration.ofMinutes(2)).header("Authorization", auth).method(method, body)
        headers.forEach { (k, v) -> b.header(k, v) }
        return b.build()
    }

    private fun <T> send(r: HttpRequest, handler: HttpResponse.BodyHandler<T>): HttpResponse<T> =
        try { http.send(r, handler) } catch (e: InterruptedException) { throw IOException(e) }

    private fun check(r: HttpResponse<*>, what: String, vararg ok: Int) {
        val code = r.statusCode()
        if (code in 200..299 || code in ok) return
        if (code == 404) throw FileNotFoundException("Nextcloud: not found ($what, HTTP 404)")
        if (code == 401) throw IOException("Nextcloud: wrong user name or password (HTTP 401)")
        throw IOException("Nextcloud operation failed: $what (HTTP $code)")
    }

    private fun baseFolderId(): String? {
        val b = baseFolder ?: return null
        if (!baseFolderCreated) { mkcol(b); baseFolderCreated = true }
        return b
    }

    /** Throws if the server can not be reached or the credentials are wrong. */
    fun verify() { propfind("/", 0) }

    private val propfindBody = """<?xml version="1.0"?>
        <d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">
          <d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/><d:getcontenttype/><nc:creation_time/><oc:size/></d:prop>
        </d:propfind>""".trimIndent()

    private fun propfind(path: String, depth: Int): List<CloudFile> {
        val r = send(request("PROPFIND", path, HttpRequest.BodyPublishers.ofString(propfindBody),
            mapOf("Depth" to depth.toString(), "Content-Type" to "application/xml")), HttpResponse.BodyHandlers.ofString())
        check(r, "PROPFIND $path")
        return parseMultistatus(r.body())
    }

    private fun parseMultistatus(xml: String): List<CloudFile> {
        val dbf = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true; setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        val doc = dbf.newDocumentBuilder().parse(xml.byteInputStream())
        val responses = doc.getElementsByTagNameNS("DAV:", "response")
        val prefix = URI(filesRoot).rawPath.trimEnd('/')
        val result = mutableListOf<CloudFile>()
        for (i in 0 until responses.length) {
            val resp = responses.item(i) as Element
            val href = resp.getElementsByTagNameNS("DAV:", "href").item(0)?.textContent ?: continue
            val rawPath = URI(href.trim()).rawPath.let { if (it.startsWith(prefix)) it.removePrefix(prefix) else it }
            val path = URLDecoder.decode(rawPath.replace("+", "%2B"), Charsets.UTF_8).trimEnd('/').ifEmpty { "/" }
            fun prop(ns: String, name: String) = resp.getElementsByTagNameNS(ns, name).item(0)?.textContent?.trim()
            val isFolder = resp.getElementsByTagNameNS("DAV:", "collection").length > 0
            val size = (prop("DAV:", "getcontentlength") ?: prop("http://owncloud.org/ns", "size"))?.toLongOrNull() ?: 0
            val modified = prop("DAV:", "getlastmodified")?.let {
                runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
            } ?: 0
            val created = prop("http://nextcloud.org/ns", "creation_time")?.toLongOrNull()?.takeIf { it > 0 }?.times(1000) ?: modified
            result += CloudFile(path, path.substringAfterLast('/'), size, created, path.substringBeforeLast('/').ifEmpty { "/" }, isFolder)
        }
        return result
    }

    fun get(id: String): CloudFile = propfind(id, 0).first()

    fun listFiles(parentsIds: List<String>? = null, name: String? = null, folders: Boolean? = null, createdTimeAtLeast: Long? = null): List<CloudFile> {
        val parents = parentsIds ?: listOf(baseFolderId() ?: "/")
        var files = parents.flatMap { parent ->
            val list = if (createdTimeAtLeast != null) search(parent, createdTimeAtLeast) else propfind(parent, 1)
            list.filterNot { it.id.trimEnd('/') == parent.trimEnd('/') }
        }
        if (name != null) files = files.filter { it.name == name }
        if (folders != null) files = files.filter { it.isFolder == folders }
        return files
    }

    /** Recently modified files below [folder] (NextCloudSearchMethod). */
    private fun search(folder: String, modifiedAtLeast: Long): List<CloudFile> {
        val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(modifiedAtLeast))
        val scope = "/files/$user/${folder.trim('/')}".replace(Regex("/+"), "/")
        val body = """<?xml version="1.0"?>
            <d:searchrequest xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">
              <d:basicsearch>
                <d:select><d:prop><oc:fileid/><d:displayname/><oc:size/><d:getcontenttype/><d:getlastmodified/><d:resourcetype/><nc:creation_time/></d:prop></d:select>
                <d:from><d:scope><d:href>${escapeXml(scope)}</d:href><d:depth>infinity</d:depth></d:scope></d:from>
                <d:where><d:gt><d:prop><d:getlastmodified/></d:prop><d:literal>$date</d:literal></d:gt></d:where>
                <d:orderby><d:order><d:prop><d:getlastmodified/></d:prop><d:ascending/></d:order></d:orderby>
              </d:basicsearch>
            </d:searchrequest>""".trimIndent()
        val r = send(request("SEARCH", "", HttpRequest.BodyPublishers.ofString(body), mapOf("Content-Type" to "text/xml"), fullUrl = davRoot),
            HttpResponse.BodyHandlers.ofString())
        check(r, "SEARCH $folder")
        return parseMultistatus(r.body())
    }

    private fun escapeXml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun getFolders(parentId: String) = listFiles(listOf(parentId), folders = true)

    fun download(id: String, target: File) {
        val r = send(request("GET", id), HttpResponse.BodyHandlers.ofFile(target.toPath()))
        check(r, "GET $id")
    }

    /** MKCOL that creates missing parents (CreateFolderRemoteOperation with createFullPath). */
    private fun mkcol(path: String) {
        var current = ""
        for (part in path.trim('/').split("/").filter { it.isNotEmpty() }) {
            current += "/$part"
            val r = send(request("MKCOL", current), HttpResponse.BodyHandlers.discarding())
            check(r, "MKCOL $current", 405) // 405: already exists
        }
    }

    fun createNewFolder(name: String, parentId: String? = null): CloudFile {
        val parent = parentId ?: baseFolderId() ?: ""
        val path = "$parent/$name"
        mkcol(path)
        return CloudFile(path, name, 0, System.currentTimeMillis(), parent, true)
    }

    fun upload(name: String, file: File, parentId: String): CloudFile {
        val path = "$parentId/$name"
        val seconds = System.currentTimeMillis() / 1000
        val r = send(request("PUT", path, HttpRequest.BodyPublishers.ofFile(file.toPath()),
            mapOf("Content-Type" to "application/gzip", "X-OC-MTime" to seconds.toString(), "X-OC-CTime" to seconds.toString())),
            HttpResponse.BodyHandlers.discarding())
        check(r, "PUT $path")
        return CloudFile(path, name, file.length(), seconds * 1000, parentId)
    }

    fun delete(id: String) {
        val r = send(request("DELETE", id), HttpResponse.BodyHandlers.discarding())
        check(r, "DELETE $id", 404)
    }

    fun isSyncFolderKnown(db: RoomDb, id: String): Boolean {
        val secret = db.getString(NEXTCLOUD_SECRET_FILE_NAME_KEY) ?: return false
        return try { get("$id/$secret"); true } catch (e: FileNotFoundException) { db.removeConfig(NEXTCLOUD_SECRET_FILE_NAME_KEY); false }
    }

    fun makeSyncFolderKnown(db: RoomDb, id: String) {
        val secret = "device-known-${Databases.deviceId}-${UUID.randomUUID()}"
        val tmp = File.createTempFile("ng-secret", null)
        upload(secret, tmp, id)
        tmp.delete()
        db.setConfig(NEXTCLOUD_SECRET_FILE_NAME_KEY, secret)
    }
}

/** Passwords and API keys live in the desktop keyring (libsecret's secret-tool) when available, else in a private file. */
object Secrets {
    private val fallback: File get() = File(AppDirs.config, "secrets.properties")

    /** LINBIBLE_NO_KEYRING=1 keeps secrets in the private file (used by tests). */
    private val useKeyring = System.getenv("LINBIBLE_NO_KEYRING") == null

    private fun secretTool(vararg args: String, input: String? = null): String? = if (!useKeyring) null else runCatching {
        val p = ProcessBuilder("secret-tool", *args).redirectErrorStream(false).start()
        if (input != null) p.outputStream.use { it.write(input.toByteArray()) } else p.outputStream.close()
        val out = p.inputStream.bufferedReader().readText()
        if (p.waitFor() == 0) out else null
    }.getOrNull()

    @Synchronized
    private fun fileEntries(): java.util.Properties = java.util.Properties().apply {
        if (fallback.exists()) fallback.inputStream().use { load(it) }
    }

    @Synchronized
    private fun saveFile(p: java.util.Properties) {
        fallback.parentFile.mkdirs()
        fallback.outputStream().use { p.store(it, "LinBible secrets (no desktop keyring available)") }
        runCatching { java.nio.file.Files.setPosixFilePermissions(fallback.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) }
    }

    fun store(account: String, password: String) {
        if (secretTool("store", "--label=LinBible ($account)", "service", "linbible", "account", account, input = password) != null) {
            clearFile(account); return
        }
        saveFile(fileEntries().apply { setProperty(account, Base64.getEncoder().encodeToString(password.toByteArray())) })
    }

    fun load(account: String): String? =
        secretTool("lookup", "service", "linbible", "account", account)?.takeIf { it.isNotEmpty() }
            ?: fileEntries().getProperty(account)?.let { String(Base64.getDecoder().decode(it)) }

    private fun clearFile(account: String) {
        val p = fileEntries()
        if (p.remove(account) != null) saveFile(p)
    }

    fun clear(account: String) { secretTool("clear", "service", "linbible", "account", account); clearFile(account) }
}

object CloudSync {
    /** Databases that can be synced (AndBible hides reading plan sync; LinBible does not use workspaces). */
    val syncable = listOf(DbCategory.BOOKMARKS, DbCategory.PROGRESS, DbCategory.MYDOCUMENTS, DbCategory.AI_SETTINGS)

    private var adapter: WebDavAdapter? = null
    val signedIn get() = adapter != null

    /** Asks the user a question on the UI thread: (title, message, options) -> chosen option index or null. */
    var ask: (String, String, List<String>) -> Int? = { _, _, _ -> null }
    val statusListeners = mutableListOf<(running: Boolean, message: String?) -> Unit>()
    private fun status(running: Boolean, msg: String? = null) = statusListeners.toList().forEach { runCatching { it(running, msg) } }

    fun configured(): Boolean = Settings.state.syncServerUrl != null && Settings.state.syncUsername != null

    fun signIn(): Boolean {
        val s = Settings.state
        val url = s.syncServerUrl ?: return false
        val user = s.syncUsername ?: return false
        val password = Secrets.load("$user@$url") ?: return false
        val a = WebDavAdapter(url, user, password, s.syncFolderPath)
        a.verify()
        adapter = a
        return true
    }

    fun signOut() {
        adapter = null
        for (c in DbCategory.entries) {
            if (!File(Databases.dir, c.filename).exists()) continue
            Settings.setSyncEnabled(c, false)
            c.db.exec("DELETE FROM SyncStatus")
            c.db.exec("DELETE FROM SyncConfiguration")
        }
    }

    private class CancelStartedSync : Exception()
    private class PatchFilesSkipped : Exception()
    private class IncompatiblePatchVersion : Exception()

    private fun folderName(c: DbCategory) = "$ANDBIBLE_PACKAGE-sync-${c.name.lowercase()}"

    private val running = java.util.concurrent.atomic.AtomicBoolean(false)
    val isRunning get() = running.get()

    fun hasChanges(): Boolean = syncable.any { c ->
        Settings.isSyncEnabled(c) && c.db.let { db ->
            val last = db.getLong(LAST_PATCH_WRITTEN_KEY) ?: 0
            db.query("SELECT COUNT(*) FROM LogEntry WHERE sourceDevice=? AND lastUpdated > ?", Databases.deviceId, last) { it.getLong(1) }.first() > 0
        }
    }

    /** One synchronization round for all enabled databases (CloudSync.synchronize). Runs on the calling thread. */
    fun synchronize(): Boolean {
        if (!running.compareAndSet(false, true)) return false
        try {
            if (!signedIn && !signIn()) return false
            status(true, "Synchronizing…")
            val start = System.currentTimeMillis()
            var error: String? = null
            for (c in syncable) {
                if (!Settings.isSyncEnabled(c)) continue
                val db = c.db
                if (db.getLong("disabledForVersion") == c.version.toLong()) continue
                try {
                    initializeSync(c)
                } catch (e: CancelStartedSync) {
                    Log.i("Sync cancelled for $c"); continue
                } catch (e: IOException) {
                    Log.e("Sync: network problem with $c", e); error = e.message; continue
                }
                try {
                    createAndUploadNewPatch(c)
                } catch (e: IOException) {
                    Log.e("Uploading patch for $c failed", e); error = e.message; continue
                }
                try {
                    try { downloadAndApplyNewPatches(c) } catch (e: PatchFilesSkipped) {
                        Log.i("Patch files skipped, retrying from the start")
                        c.db.setConfig(LAST_SYNCHRONIZED_KEY, 0L)
                        downloadAndApplyNewPatches(c)
                    }
                } catch (e: IncompatiblePatchVersion) {
                    error = "${c.title} were synchronized by a newer AndBible version; update LinBible to keep syncing them."
                    c.db.setConfig("disabledForVersion", c.version.toLong())
                } catch (e: IOException) {
                    Log.e("Downloading patches for $c failed", e); error = e.message
                }
            }
            Settings.state.lastSynchronized = System.currentTimeMillis(); Settings.save()
            Log.i("Synchronization complete in ${(System.currentTimeMillis() - start) / 1000.0} s")
            status(false, error)
            return error == null
        } catch (e: Exception) {
            Log.e("Synchronization failed", e)
            status(false, e.message ?: e.toString())
            return false
        } finally {
            running.set(false)
        }
    }

    private fun initializeSync(c: DbCategory) {
        val a = adapter!!
        val db = c.db
        val name = folderName(c)
        var syncFolderId = db.getString(SYNC_FOLDER_FILE_ID_KEY)
        if (syncFolderId != null && !a.isSyncFolderKnown(db, syncFolderId)) {
            syncFolderId = null
            db.removeConfig(SYNC_FOLDER_FILE_ID_KEY)
            db.removeConfig(SYNC_DEVICE_FOLDER_FILE_ID_KEY)
        }
        if (syncFolderId != null) return

        val existing = a.listFiles(name = name).firstOrNull { it.isFolder }?.id
        val fetch = if (existing != null) {
            when (ask("Cloud sync", "${c.title} have already been synchronized from another device. Do you want to fetch and " +
                "restore them from the cloud (replacing those on this computer), or start over with the data on this computer " +
                "(replacing the data in the cloud)?", listOf("Fetch from cloud", "Replace cloud data", "Don't sync"))) {
                0 -> true
                1 -> false
                else -> { Settings.setSyncEnabled(c, false); throw CancelStartedSync() }
            }
        } else false

        if (fetch) {
            db.setConfig(SYNC_FOLDER_FILE_ID_KEY, existing!!)
            a.makeSyncFolderKnown(db, existing)
            createDeviceFolder(c, existing)
            fetchAndRestoreInitial(c)
        } else {
            if (existing != null) a.delete(existing)
            val folder = a.createNewFolder(name)
            a.makeSyncFolderKnown(c.db, folder.id)
            c.db.setConfig(SYNC_FOLDER_FILE_ID_KEY, folder.id)
            createDeviceFolder(c, folder.id)
            createAndUploadInitial(c)
        }
    }

    private fun createDeviceFolder(c: DbCategory, syncFolderId: String) {
        val f = adapter!!.createNewFolder(Databases.deviceId, syncFolderId)
        c.db.setConfig(SYNC_DEVICE_FOLDER_FILE_ID_KEY, f.id)
    }

    private fun tmpFile(): File = File.createTempFile("sync-", ".tmp", AppDirs.cache.also { it.mkdirs() })

    private fun gzip(src: File, dst: File) = GZIPOutputStream(dst.outputStream()).use { out -> src.inputStream().use { it.copyTo(out) } }
    private fun gunzip(src: File, dst: File) = GZIPInputStream(src.inputStream()).use { input -> dst.outputStream().use { input.copyTo(it) } }

    private fun addStatus(db: RoomDb, device: String, num: Long, size: Long, date: Long) =
        db.update("INSERT OR REPLACE INTO SyncStatus (sourceDevice, patchNumber, sizeBytes, appliedDate) VALUES (?, ?, ?, ?)", device, num, size, date)

    private fun createAndUploadInitial(c: DbCategory) {
        val db = c.db
        db.exec("DELETE FROM LogEntry")
        db.exec("DELETE FROM SyncStatus")
        val copy = tmpFile().also { it.delete() }
        db.exec("VACUUM INTO '${copy.absolutePath.replace("'", "''")}'")
        val gz = tmpFile()
        gzip(copy, gz); copy.delete()
        val result = adapter!!.upload(INITIAL_BACKUP_FILENAME, gz, db.getString(SYNC_FOLDER_FILE_ID_KEY)!!)
        addStatus(db, Databases.deviceId, 0, result.size, result.createdTime)
        gz.delete()
    }

    private fun fetchAndRestoreInitial(c: DbCategory) {
        val db = c.db
        val syncFolder = db.getString(SYNC_FOLDER_FILE_ID_KEY)!!
        val deviceFolder = db.getString(SYNC_DEVICE_FOLDER_FILE_ID_KEY)!!
        val secret = db.getString(NEXTCLOUD_SECRET_FILE_NAME_KEY)
        val initial = adapter!!.listFiles(listOf(syncFolder), name = INITIAL_BACKUP_FILENAME).first()
        val gz = tmpFile()
        adapter!!.download(initial.id, gz)
        val file = tmpFile()
        gunzip(gz, file); gz.delete()
        val version = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
            conn.createStatement().use { st -> st.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) } }
        }
        if (version > c.version) {
            file.delete()
            Settings.setSyncEnabled(c, false)
            throw IOException("${c.title} in the cloud were synchronized by a newer AndBible version; update LinBible to sync them.")
        }
        Databases.close(c)
        file.copyTo(File(Databases.dir, c.filename), overwrite = true)
        File(Databases.dir, c.filename + "-journal").delete()
        file.delete()
        val newDb = c.db // reopens: migrates and recreates triggers with this device's id
        newDb.setConfig(SYNC_FOLDER_FILE_ID_KEY, syncFolder)
        newDb.setConfig(SYNC_DEVICE_FOLDER_FILE_ID_KEY, deviceFolder)
        newDb.setConfig(LAST_PATCH_WRITTEN_KEY, System.currentTimeMillis())
        secret?.let { newDb.setConfig(NEXTCLOUD_SECRET_FILE_NAME_KEY, it) }
        addStatus(newDb, Databases.deviceId, 0, initial.size, initial.createdTime)
        Databases.notifyChanged(c)
    }

    // ---------------------------------------------------------------- patches (SyncUtilities)

    private fun columns(db: RoomDb, table: String, schema: String = "main"): List<String> =
        db.query("SELECT name FROM pragma_table_info('$table', '$schema')") { it.getString(1) }

    private fun createPatch(c: DbCategory): File? {
        val db = c.db
        val lastPatchWritten = db.getLong(LAST_PATCH_WRITTEN_KEY) ?: 0
        val count = db.query("SELECT COUNT(*) FROM LogEntry WHERE sourceDevice=? AND lastUpdated > ?", Databases.deviceId, lastPatchWritten) { it.getLong(1) }.first()
        if (count == 0L) return null
        val patchFile = tmpFile().also { it.delete() }
        RoomDb(c, patchFile, withTriggers = false).apply { open() }.close()
        synchronized(db) {
            db.exec("ATTACH DATABASE '${patchFile.absolutePath.replace("'", "''")}' AS patch")
            db.exec("PRAGMA patch.foreign_keys=OFF")
            try {
                db.tx {
                    for (t in c.tables) {
                        val cols = columns(db, t.name, "patch").joinToString(",") { "`$it`" }
                        val where = if (t.idField2 == null) t.idField1 else "(${t.idField1},${t.idField2})"
                        val select = if (t.idField2 == null) "pe.entityId1" else "pe.entityId1,pe.entityId2"
                        db.exec("""INSERT INTO patch.${t.name} ($cols) SELECT $cols FROM ${t.name} WHERE $where IN
                            (SELECT $select FROM LogEntry pe WHERE tableName = '${t.name}' AND type = 'UPSERT' AND lastUpdated > $lastPatchWritten)""")
                        db.exec("INSERT INTO patch.LogEntry SELECT * FROM LogEntry WHERE tableName = '${t.name}' AND lastUpdated > $lastPatchWritten")
                    }
                }
            } finally {
                db.exec("PRAGMA patch.foreign_keys=ON")
                db.exec("DETACH DATABASE patch")
            }
        }
        val gz = tmpFile()
        gzip(patchFile, gz)
        patchFile.delete()
        return gz
    }

    private fun applyPatch(c: DbCategory, gzipped: File) {
        val patchFile = tmpFile()
        gunzip(gzipped, patchFile)
        RoomDb(c, patchFile, withTriggers = false).apply { open() }.close() // migrate older patches
        val db = c.db
        synchronized(db) {
            db.exec("ATTACH DATABASE '${patchFile.absolutePath.replace("'", "''")}' AS patch")
            db.exec("PRAGMA foreign_keys=OFF")
            try {
                db.tx {
                    for (t in c.tables) {
                        db.setConfig(TRIGGERS_DISABLED_KEY, true)
                        readPatchData(db, t)
                        db.setConfig(TRIGGERS_DISABLED_KEY, false)
                    }
                }
            } finally {
                db.exec("PRAGMA foreign_keys=ON")
                db.exec("DETACH DATABASE patch")
            }
        }
        patchFile.delete()
    }

    private fun readPatchData(db: RoomDb, t: DbCategory.Table) {
        val table = t.name
        val colList = columns(db, table)
        val cols = colList.joinToString(",") { "`$it`" }
        val setValues = colList.filterNot { it == t.idField1 || it == t.idField2 }.joinToString(",\n") { "`$it`=excluded.`$it`" }
        val idFields = if (t.idField2 == null) t.idField1 else "(${t.idField1},${t.idField2})"
        val select = if (t.idField2 == null) "pe.entityId1" else "pe.entityId1,pe.entityId2"
        fun where(type: String? = null): String {
            val typeStr = if (type == null) "" else "AND pe.type = '$type'"
            return """(SELECT $select FROM patch.LogEntry pe
                  OUTER LEFT JOIN LogEntry me
                  ON pe.entityId1 = me.entityId1 AND pe.entityId2 = me.entityId2 AND pe.tableName = me.tableName
                  WHERE pe.tableName = '$table' $typeStr AND (me.lastUpdated IS NULL OR pe.lastUpdated > me.lastUpdated))"""
        }
        db.exec("""INSERT INTO $table ($cols) SELECT $cols FROM patch.$table WHERE $idFields IN ${where("UPSERT")}
            ON CONFLICT DO ${if (setValues.isEmpty()) "NOTHING" else "UPDATE SET $setValues"};""")
        db.exec("DELETE FROM $table WHERE rowId in (SELECT rowid FROM pragma_foreign_key_check('$table'));")
        db.exec("DELETE FROM $table WHERE $idFields IN ${where("DELETE")}")
        db.exec("INSERT OR REPLACE INTO LogEntry SELECT * FROM patch.LogEntry pe WHERE pe.tableName = '$table' AND ($select) IN ${where()}")
    }

    private val patchPattern = Regex("""(\d+)\.((\d+)\.)?sqlite3\.gz""")
    private fun patchNumber(name: String) = patchPattern.find(name)!!.groupValues[1].toLong()
    private fun versionNumber(name: String) = patchPattern.find(name)?.groups?.get(3)?.value?.toLong() ?: 1

    private fun lastPatchNum(db: RoomDb, device: String): Long? =
        db.query("SELECT patchNumber FROM SyncStatus WHERE sourceDevice=? ORDER BY patchNumber DESC LIMIT 1", device) { it.getLong(1) }.firstOrNull()

    private fun createAndUploadNewPatch(c: DbCategory) {
        val file = createPatch(c) ?: return
        val db = c.db
        val deviceFolder = db.getString(SYNC_DEVICE_FOLDER_FILE_ID_KEY)!!
        val count = (lastPatchNum(db, Databases.deviceId) ?: 0) + 1
        val name = "$count.${c.version}.sqlite3.gz"
        val lastWritten = System.currentTimeMillis()
        try {
            val result = adapter!!.upload(name, file, deviceFolder)
            Log.i("Uploaded ${c.name} $name, ${file.length()} bytes")
            db.setConfig(LAST_PATCH_WRITTEN_KEY, lastWritten)
            addStatus(db, Databases.deviceId, count, file.length(), result.createdTime)
        } finally {
            file.delete()
        }
    }

    private fun downloadAndApplyNewPatches(c: DbCategory) {
        val a = adapter!!
        val db = c.db
        val lastSynchronized = db.getLong(LAST_SYNCHRONIZED_KEY) ?: 0
        val syncFolder = db.getString(SYNC_FOLDER_FILE_ID_KEY)!!
        db.setConfig(LAST_SYNCHRONIZED_KEY, System.currentTimeMillis())
        val deviceFolders = a.getFolders(syncFolder)
        if (deviceFolders.isEmpty()) return
        val results = a.listFiles(deviceFolders.map { it.id }, createdTimeAtLeast = lastSynchronized)
            .filter { !it.isFolder && patchPattern.matches(it.name) }.sortedBy { it.createdTime }
        val loaded = deviceFolders.associate { it.id to (it.name to (lastPatchNum(db, it.name) ?: 0)) }

        class Patch(val file: CloudFile, val folder: String)
        val patches = results.mapNotNull { f ->
            val (folderName, loadedCount) = loaded[f.parentId] ?: return@mapNotNull null
            val num = patchNumber(f.name)
            if (versionNumber(f.name) > c.version) {
                db.setConfig(LAST_SYNCHRONIZED_KEY, lastSynchronized)
                throw IncompatiblePatchVersion()
            }
            val existing = db.query("SELECT 1 FROM SyncStatus WHERE sourceDevice=? AND patchNumber=?", folderName, num) { 1 }.isNotEmpty()
            if (!existing && num > loadedCount) Patch(f, folderName) else null
        }.sortedBy { it.file.createdTime }
        if (patches.isEmpty()) return

        for ((_, value) in loaded) {
            val (folderName, loadedCount) = value
            val first = patches.firstOrNull { it.folder == folderName } ?: continue
            if (patchNumber(first.file.name) > loadedCount + 1) throw PatchFilesSkipped()
        }
        Log.i("Applying ${patches.size} patch(es) to ${c.name}")
        val downloaded = patches.map { p -> tmpFile().also { a.download(p.file.id, it) } }
        try {
            downloaded.forEach { applyPatch(c, it) }
        } finally {
            downloaded.forEach { it.delete() }
        }
        patches.forEach { addStatus(db, it.folder, patchNumber(it.file.name), it.file.size, it.file.createdTime) }
        Databases.notifyChanged(c)
    }

    fun lastSynchronized(c: DbCategory): Long? = if (!Settings.isSyncEnabled(c)) null else c.db.getLong(LAST_SYNCHRONIZED_KEY)

    fun bytesUsed(): Long = syncable.filter { File(Databases.dir, it.filename).exists() }.sumOf { c ->
        c.db.query("SELECT COALESCE(SUM(sizeBytes), 0) FROM SyncStatus") { it.getLong(1) }.first()
    }

    /** Runs [f] on the UI thread and waits for its result (for questions asked during sync). */
    fun <T> onUi(f: () -> T): T {
        if (javafx.application.Platform.isFxApplicationThread()) return f()
        val fut = CompletableFuture<T>()
        javafx.application.Platform.runLater { try { fut.complete(f()) } catch (e: Throwable) { fut.completeExceptionally(e) } }
        return fut.get()
    }
}
