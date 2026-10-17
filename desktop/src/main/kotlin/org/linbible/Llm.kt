/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 */
package org.linbible

import java.io.File

object Llm {
    /** AndBible's CommonUtils.settings.llmConfigured: any AI provider is set up. */
    val configured: Boolean get() = runCatching {
        net.bible.service.db.DatabaseContainer.instance.aiSettingsDb.llmProviderConfigDao().getCount() > 0
    }.getOrDefault(false)

    /** A prompt pack (CSV) installed from a file is offered as an add-on (AndBible's CsvPromptBook). */
    fun installPromptPack(f: File) {
        Addons.reload()
    }
}
