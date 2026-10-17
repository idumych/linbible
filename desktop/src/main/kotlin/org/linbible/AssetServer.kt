/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 */
package org.linbible

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
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
}
