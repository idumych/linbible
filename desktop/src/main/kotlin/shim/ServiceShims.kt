/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 *
 * Stand-ins for AndBible service objects (CommonUtils, SwordContentFacade, SwordDocumentFacade) used by its compiled
 * AI and document code, implemented with LinBible's own functionality.
 */
@file:Suppress("unused")

package net.bible.service.common

import kotlinx.serialization.json.Json
import org.jdom2.input.SAXBuilder
import org.jdom2.xpath.XPathFactory
import org.linbible.Databases
import org.linbible.Secrets
import org.linbible.Settings
import java.util.concurrent.ArrayBlockingQueue

/** String-set and simple settings (AndBible's SettingsDatabase), kept in LinBible's state.json. */
class SimpleSettings {
    fun getStringSet(key: String): Set<String> = Settings.state.extra["set:$key"]?.split("\u0001")?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
    fun setStringSet(key: String, value: Set<String>) { Settings.state.extra = Settings.state.extra + ("set:$key" to value.joinToString("\u0001")); Settings.save() }
    fun getString(key: String, default: String? = null): String? = Settings.state.extra["str:$key"] ?: default
    fun setString(key: String, value: String?) {
        Settings.state.extra = if (value == null) Settings.state.extra - "str:$key" else Settings.state.extra + ("str:$key" to value); Settings.save()
    }
    fun getBoolean(key: String, default: Boolean): Boolean = Settings.state.extra["bool:$key"]?.toBoolean() ?: default
    fun setBoolean(key: String, value: Boolean) { Settings.state.extra = Settings.state.extra + ("bool:$key" to value.toString()); Settings.save() }
    fun getLong(key: String, default: Long): Long = Settings.state.extra["long:$key"]?.toLongOrNull() ?: default
    fun setLong(key: String, value: Long) { Settings.state.extra = Settings.state.extra + ("long:$key" to value.toString()); Settings.save() }
    fun getInt(key: String, default: Int): Int = Settings.state.extra["int:$key"]?.toIntOrNull() ?: default
    fun setInt(key: String, value: Int) { Settings.state.extra = Settings.state.extra + ("int:$key" to value.toString()); Settings.save() }
    fun removeString(key: String) = setString(key, null)
}

/**
 * SharedPreferences-like store. AndBible keeps LLM API keys here ("llm_api_key_<id>"); LinBible puts them into the
 * desktop keyring (or the private fallback file), like the sync password.
 */
class SecretPreferences {
    fun getString(key: String, default: String?): String? = Secrets.load("pref:$key") ?: default
    fun edit() = Editor()
    inner class Editor {
        private val ops = mutableListOf<() -> Unit>()
        fun putString(key: String, value: String?): Editor { ops += { if (value == null) Secrets.clear("pref:$key") else Secrets.store("pref:$key", value) }; return this }
        fun remove(key: String): Editor { ops += { Secrets.clear("pref:$key") }; return this }
        fun apply() = ops.forEach { it() }
        fun commit(): Boolean { apply(); return true }
    }
}

/** AndBible's Dagger component: inject() fills @Inject windowControl / linkControl / bookmarkControl fields. */
class ActivityComponent {
    fun inject(target: Any) {
        val c = net.bible.android.BibleApplication.application.applicationComponent
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            for (f in cls.declaredFields) {
                val value: Any? = when (f.name) {
                    "windowControl" -> c.windowControl()
                    "linkControl" -> c.linkControl()
                    "bookmarkControl" -> c.bookmarkControl()
                    else -> null
                }
                if (value != null) { f.isAccessible = true; f.set(target, value) }
            }
            cls = cls.superclass
        }
    }
}

object CommonUtils {
    fun buildActivityComponent() = ActivityComponent()
    val aiSettings: AiSettings get() = AiSettings
    val settings = SimpleSettings()
    val realSharedPreferences = SecretPreferences()
    val deviceIdentifier: String get() = Databases.deviceId
    val isDebugMode: Boolean get() = System.getenv("LINBIBLE_DEBUG") != null
    val isDiscrete = false
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    const val applicationVersionNumber = org.linbible.Addons.ANDBIBLE_VERSION
}

private val builders = ArrayBlockingQueue<SAXBuilder>(32)
fun <R> useSaxBuilder(block: (it: SAXBuilder) -> R): R {
    val builder = builders.poll() ?: SAXBuilder().also { it.setFeature("http://xml.org/sax/features/external-general-entities", false) }
    val rv = block(builder)
    builders.offer(builder)
    return rv
}

private val xPathInstances = ArrayBlockingQueue<XPathFactory>(32)
fun <R> useXPathInstance(block: (it: XPathFactory) -> R): R {
    val xp = xPathInstances.poll() ?: XPathFactory.instance()
    val rv = block(xp)
    xPathInstances.offer(xp)
    return rv
}

/** AndBible add-on prompt packs (AndBibleAddons.providedPromptPacks) from LinBible's Addons. */
class ProvidedPromptPack(val moduleName: String, val file: java.io.File)
class ReloadAddonsEvent

object AndBibleAddons {
    val providedPromptPacks: List<ProvidedPromptPack> get() =
        org.linbible.Addons.promptPacks.map { ProvidedPromptPack(it.moduleName, it.file) } +
            // prompt packs installed from CSV files (Library → Install from file)
            (java.io.File(org.linbible.OtherFormats.modulesDir, "prompts").listFiles()?.filter { it.extension.lowercase() == "csv" }
                ?.map { ProvidedPromptPack(it.nameWithoutExtension, it) } ?: emptyList())
}
