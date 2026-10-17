/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 */
package org.linbible

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.crosswire.jsword.book.Books
import java.io.File
import java.net.InetAddress
import java.net.URLConnection
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * Serves the bundled BibleView frontend to the WebViews over loopback HTTP.
 *
 * ES modules cannot be loaded from file:// URLs, so (like AndBible's WebViewAssetLoader) we
 * serve them from a local origin. The module entry script tag is neutralised in index.html so
 * that the page only boots once the `window.android` bridge object has been injected.
 */
object AssetServer {
    private lateinit var server: HttpServer
    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    private val mimeTypes = mapOf(
        "html" to "text/html; charset=utf-8",
        "js" to "text/javascript; charset=utf-8",
        "mjs" to "text/javascript; charset=utf-8",
        "css" to "text/css; charset=utf-8",
        "json" to "application/json",
        "svg" to "image/svg+xml",
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "woff" to "font/woff",
        "woff2" to "font/woff2",
        "ttf" to "font/ttf",
        "otf" to "font/otf",
    )

    private val moduleScript = Regex("""<script type="module" crossorigin src="([^"]+)"></script>""")

    fun start() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.executor = Executors.newFixedThreadPool(4) { r -> Thread(r, "asset-server").apply { isDaemon = true } }
        server.createContext("/") { ex ->
            try {
                var path = ex.requestURI.path.trimStart('/')
                if (path.isEmpty()) path = "index.html"
                if (path.contains("..")) {
                    ex.sendResponseHeaders(403, -1); return@createContext
                }
                if (serveModuleAsset(ex, path)) return@createContext
                val res = AssetServer::class.java.getResourceAsStream("/web/$path")
                if (res == null) {
                    Log.d("404 $path")
                    ex.sendResponseHeaders(404, -1); return@createContext
                }
                var bytes = res.use { it.readBytes() }
                if (path == "index.html") {
                    bytes = moduleScript.replace(String(bytes)) {
                        """<script>window.__bibleViewEntry = "${it.groupValues[1]}";</script>"""
                    }.toByteArray()
                }
                val ext = path.substringAfterLast('.', "")
                ex.responseHeaders.add("Content-Type", mimeTypes[ext] ?: "application/octet-stream")
                ex.responseHeaders.add("Cache-Control", "no-cache")
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            } catch (e: Exception) {
                Log.e("Asset server error", e)
            } finally {
                ex.close()
            }
        }
        server.start()
        Log.i("Asset server running at $baseUrl")
    }

    fun stop() = runCatching { server.stop(0) }

    private fun send(ex: HttpExchange, bytes: ByteArray, type: String) {
        ex.responseHeaders.add("Content-Type", type)
        ex.responseHeaders.add("Cache-Control", "no-cache")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun sendFile(ex: HttpExchange, f: File?): Boolean {
        if (f == null || !f.isFile || !f.canRead()) { ex.sendResponseHeaders(404, -1); return true }
        val ext = f.extension.lowercase()
        send(ex, f.readBytes(), mimeTypes[ext] ?: URLConnection.guessContentTypeFromName(f.name) ?: "application/octet-stream")
        return true
    }

    /** Add-on and module resources (AndBible BibleView's asset path handlers). Returns true if handled. */
    private fun serveModuleAsset(ex: HttpExchange, path: String): Boolean {
        val prefix = path.substringBefore('/') + "/"
        val rest = path.removePrefix(prefix)
        fun split() = rest.split("/", limit = 2).takeIf { it.size == 2 }
        return when (prefix) {
            "fonts/" -> {
                val (module, resource) = split() ?: return sendFile(ex, null)
                val book = Books.installed().getBook(module) ?: return sendFile(ex, null)
                if (resource == "fonts.css") {
                    val fonts = Addons.fontsByModule[book.initials] ?: return sendFile(ex, null)
                    val css = fonts.joinToString("") { "@font-face {\nfont-family: '${it.name}';\nsrc: url('${it.path}') format('truetype');\n}\n" }
                    send(ex, css.toByteArray(), "text/css; charset=utf-8"); true
                } else sendFile(ex, File(File(book.bookMetaData.location), resource))
            }
            "module-style/" -> {
                if (rest.startsWith("epub/")) return Epub.serveStyle(ex, rest.removePrefix("epub/")) { b, t -> send(ex, b, t) }
                val (module, resource) = split() ?: return sendFile(ex, null)
                val book = Books.installed().getBook(module) ?: return sendFile(ex, null)
                val styleFile = book.bookMetaData.getProperty("AndBibleCSS") as String? ?: return sendFile(ex, null)
                var f = File(File(book.bookMetaData.location), styleFile)
                if (resource != "style.css") f = File(f.parent, resource)
                sendFile(ex, f)
            }
            "features/" -> {
                val (module, resource) = split() ?: return sendFile(ex, null)
                val book = Books.installed().getBook(module) ?: return sendFile(ex, null)
                val f = File(File(book.bookMetaData.location), resource)
                sendFile(ex, f.takeIf { Addons.verifySignature(it, File(it.path + ".sign")) })
            }
            "background/" -> sendFile(ex, Addons.backgroundImages[rest.trim('/')]?.file)
            "epub/" -> sendFile(ex, Epub.resource(rest))
            else -> false
        }
    }
}
