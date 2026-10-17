package org.linbible

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * JavaFX's GTK backend only honours an integer GDK_SCALE, so on KDE/GNOME with (fractional) display scaling the
 * app comes up tiny. Work out the desktop's scale before the toolkit starts and pass it as glass.gtk.uiScale.
 *
 * Order: LINBIBLE_SCALE env > explicit -Dglass.gtk.uiScale > setting in state.json > GDK_SCALE*GDK_DPI_SCALE >
 * Xft.dpi (KDE/XWayland, GNOME text scaling, most X11 WMs) > GNOME scaling-factor > KDE kwinrc Scale.
 */
object HiDpi {
    var detected: Double = 1.0
        private set
    var applied: Double = 1.0
        private set
    var source: String = "default"
        private set

    fun apply() {
        detected = detect()
        val override = System.getenv("LINBIBLE_SCALE")?.toDoubleOrNull()?.takeIf { it in 0.5..6.0 }
        val explicit = System.getProperty("glass.gtk.uiScale")
        when {
            override != null -> { applied = override; source = "LINBIBLE_SCALE" }
            explicit != null -> { source = "glass.gtk.uiScale"; applied = explicit.removeSuffix("%").toDoubleOrNull()
                ?.let { if (explicit.endsWith("%")) it / 100 else it } ?: 1.0; return }
            Settings.state.uiScale != null -> { applied = Settings.state.uiScale!!; source = "settings" }
            else -> applied = detected
        }
        System.setProperty("glass.gtk.uiScale", "%.2f".format(java.util.Locale.ROOT, applied))
        Log.i("UI scale ${"%.2f".format(applied)} ($source; detected ${"%.2f".format(detected)})")
    }

    private fun round(v: Double) = ((v * 20).roundToInt() / 20.0).coerceIn(1.0, 6.0)

    private fun detect(): Double {
        System.getenv("GDK_SCALE")?.toDoubleOrNull()?.let { s ->
            val dpiScale = System.getenv("GDK_DPI_SCALE")?.toDoubleOrNull() ?: 1.0
            source = "GDK_SCALE"; return round(s * dpiScale)
        }
        xftDpi()?.let { if (it > 96) { source = "Xft.dpi"; return round(it / 96.0) } }
        run("gsettings", "get", "org.gnome.desktop.interface", "scaling-factor")
            ?.substringAfterLast(' ')?.trim()?.toIntOrNull()?.let { if (it > 1) { source = "gsettings"; return it.toDouble() } }
        kwinScale()?.let { if (it > 1) { source = "kwinrc"; return round(it) } }
        return 1.0
    }

    private fun xftDpi(): Double? =
        run("xrdb", "-query")?.lineSequence()
            ?.firstOrNull { it.startsWith("Xft.dpi:") }
            ?.substringAfter(':')?.trim()?.toDoubleOrNull()

    private fun kwinScale(): Double? {
        val cfg = System.getenv("XDG_CONFIG_HOME")?.let(::File) ?: File(System.getProperty("user.home"), ".config")
        val kwinrc = File(cfg, "kwinrc").takeIf { it.isFile } ?: return null
        return kwinrc.readLines().firstOrNull { it.startsWith("Scale=") }?.substringAfter('=')?.toDoubleOrNull()
    }

    private fun run(vararg cmd: String): String? = runCatching {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        if (!p.waitFor(2, TimeUnit.SECONDS)) { p.destroy(); return null }
        if (p.exitValue() != 0) null else p.inputStream.bufferedReader().readText()
    }.getOrNull()
}
