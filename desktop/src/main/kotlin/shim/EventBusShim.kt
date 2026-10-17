/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 */
@file:Suppress("unused")

package net.bible.android.control.event

import java.lang.reflect.Method
import java.util.concurrent.CopyOnWriteArrayList

/**
 * AndBible's event bus (greenrobot EventBus style): subscribers have onEvent(e) / onEventMainThread(e) methods,
 * or register a typed listener with [listen]. onEventMainThread handlers run on the JavaFX thread.
 */
object ABEventBus {
    private val subscribers = CopyOnWriteArrayList<Any>()
    private val listeners = CopyOnWriteArrayList<Pair<Class<*>, (Any) -> Unit>>()

    fun register(o: Any) { if (o !in subscribers) subscribers += o }
    fun unregister(o: Any) { subscribers -= o }
    fun safelyRegister(o: Any) = register(o)

    fun <T : Any> listen(cls: Class<T>, f: (T) -> Unit) {
        @Suppress("UNCHECKED_CAST")
        listeners += cls to (f as (Any) -> Unit)
    }

    private fun handlers(o: Any, event: Any): List<Method> = o.javaClass.methods.filter {
        (it.name == "onEvent" || it.name == "onEventMainThread" || it.name == "onEventAsync") &&
            it.parameterCount == 1 && it.parameterTypes[0].isInstance(event)
    }

    fun post(event: Any) {
        for ((cls, f) in listeners) if (cls.isInstance(event)) runCatching { f(event) }.onFailure { org.linbible.Log.e("Event listener failed", it) }
        for (s in subscribers) for (m in handlers(s, event)) {
            val call = { runCatching { m.invoke(s, event) }.onFailure { org.linbible.Log.e("Event handler ${m.name} failed", it) } }
            if (m.name == "onEventMainThread" && !javafx.application.Platform.isFxApplicationThread()) javafx.application.Platform.runLater { call() }
            else call()
        }
    }
}
