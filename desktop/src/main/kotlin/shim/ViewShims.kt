/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 *
 * The dialogs AndBible's AI agent shows (permission requests, questions), as JavaFX dialogs.
 */
@file:Suppress("unused")

package net.bible.android.view.activity.base

import android.app.Activity
import android.content.Context
import javafx.application.Platform
import javafx.scene.control.Alert
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import kotlinx.coroutines.suspendCancellableCoroutine
import net.bible.android.activity.R
import kotlin.coroutines.resume

object CurrentActivityHolder {
    /** The desktop app is always "in the foreground". */
    val currentActivity: Activity? get() = Activity()
}

object Dialogs {
    /** Owner window for dialogs (set by LinBible's main window). */
    var owner: javafx.stage.Window? = null

    private suspend fun <T> onFx(block: (resume: (T) -> Unit) -> Unit): T = suspendCancellableCoroutine { cont ->
        Platform.runLater { block { if (cont.isActive) cont.resume(it) } }
    }

    suspend fun simpleQuestion(context: Context, message: String? = null, title: String? = context.getString(R.string.are_you_sure)): Boolean =
        onFx { resume ->
            val ok = ButtonType(context.getString(R.string.okay), ButtonBar.ButtonData.OK_DONE)
            val cancel = ButtonType(context.getString(R.string.cancel), ButtonBar.ButtonData.CANCEL_CLOSE)
            val r = Alert(Alert.AlertType.CONFIRMATION, message ?: "", ok, cancel).apply {
                owner?.let { initOwner(it) }; this.title = title; headerText = null
            }.showAndWait().orElse(cancel)
            resume(r == ok)
        }

    enum class AgentPermissionResult { ALLOW, ALLOW_FOR_SESSION, ALLOW_ALL_SESSION, ALLOW_ALWAYS, DENY }

    suspend fun agentPermissionDialog(context: Context, toolDisplayName: String, toolDescription: String, actionDescription: String? = null): AgentPermissionResult =
        onFx { resume ->
            val message = if (actionDescription != null) context.getString(R.string.agent_permission_message_with_action, actionDescription)
                else context.getString(R.string.agent_permission_message, toolDisplayName, toolDescription)
            val buttons = linkedMapOf(
                ButtonType(context.getString(R.string.permission_allow_once), ButtonBar.ButtonData.OK_DONE) to AgentPermissionResult.ALLOW,
                ButtonType(context.getString(R.string.permission_allow_for_session), ButtonBar.ButtonData.OTHER) to AgentPermissionResult.ALLOW_FOR_SESSION,
                ButtonType(context.getString(R.string.permission_allow_all_session), ButtonBar.ButtonData.OTHER) to AgentPermissionResult.ALLOW_ALL_SESSION,
                ButtonType(context.getString(R.string.permission_allow_always, toolDisplayName), ButtonBar.ButtonData.OTHER) to AgentPermissionResult.ALLOW_ALWAYS,
                ButtonType(context.getString(R.string.permission_deny), ButtonBar.ButtonData.CANCEL_CLOSE) to AgentPermissionResult.DENY,
            )
            val alert = Alert(Alert.AlertType.CONFIRMATION, message, *buttons.keys.toTypedArray()).apply {
                owner?.let { initOwner(it) }; title = context.getString(R.string.agent_permission_title); headerText = null
                dialogPane.minWidth = 640.0
            }
            val r = alert.showAndWait().orElse(null)
            resume(buttons[r] ?: AgentPermissionResult.DENY)
        }
}
