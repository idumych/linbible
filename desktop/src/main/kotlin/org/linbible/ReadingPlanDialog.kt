/*
 * LinBible - desktop port of AndBible: Bible Study (https://github.com/AndBible/and-bible)
 * Licensed under the GNU General Public License v3 or later.
 *
 * Reading plan window: AndBible's DailyReading, DailyReadingList and ReadingPlanSelectorList in one window.
 */
package org.linbible

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.DatePicker
import javafx.scene.control.Dialog
import javafx.scene.control.Hyperlink
import javafx.scene.control.Label as FxLabel
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.control.ScrollPane
import javafx.scene.control.TableColumn
import javafx.scene.control.TableRow
import javafx.scene.control.TableView
import javafx.scene.control.Tooltip
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.stage.Stage
import org.crosswire.jsword.book.sword.SwordBook
import java.time.ZoneId
import java.util.Date

class ReadingPlanDialog private constructor(private val app: MainApp) {
    private val stage = Stage()
    private val root = BorderPane()
    private var day = 0
    private val refresh: () -> Unit = { javafx.application.Platform.runLater { if (stage.isShowing) showCurrent() } }

    init {
        stage.title = "Reading Plan"
        stage.initOwner(app.stage)
        root.padding = Insets(12.0)
        stage.scene = Scene(root, 720.0, 560.0).also { styleLike(app, it) }
        ReadingPlans.listeners += refresh
        stage.setOnHidden { ReadingPlans.listeners -= refresh; instance = null }
    }

    private fun showCurrent() {
        val code = ReadingPlans.currentPlanCode
        if (code == null || !ReadingPlans.currentPlanExists) showSelector() else showDay(code, if (day > 0) day else ReadingPlans.currentDay(code))
    }

    // ---------------------------------------------------------------- plan selector

    private fun showSelector() {
        stage.title = "Choose a Reading Plan"
        val list = ListView<ReadingPlanInfo>()
        list.items.setAll(ReadingPlans.plans())
        list.setCellFactory {
            object : ListCell<ReadingPlanInfo>() {
                override fun updateItem(item: ReadingPlanInfo?, empty: Boolean) {
                    super.updateItem(item, empty)
                    if (item == null || empty) { graphic = null; text = null; return }
                    val name = FxLabel(item.name).apply { style = "-fx-font-weight: bold" }
                    val started = item.startDate?.let { " · started ${java.text.DateFormat.getDateInstance().format(it)}" } ?: ""
                    val desc = FxLabel("${item.numberOfDays} days$started\n${item.description}").apply {
                        isWrapText = true; maxWidth = 480.0; opacity = 0.8
                    }
                    text = null
                    graphic = VBox(2.0, name, desc)
                }
            }
        }
        list.selectionModel.select(list.items.indexOfFirst { it.code == ReadingPlans.currentPlanCode }.coerceAtLeast(0))
        val start = Button("Start / continue").apply {
            isDefaultButton = true
            setOnAction { list.selectionModel.selectedItem?.let { start(it) } }
        }
        list.setOnMouseClicked { if (it.clickCount == 2) list.selectionModel.selectedItem?.let(::start) }
        val reset = Button("Reset progress").apply {
            setOnAction {
                val p = list.selectionModel.selectedItem ?: return@setOnAction
                if (app.ask("Reset plan", "Reset all progress of \"${p.name}\"?")) {
                    ReadingPlans.reset(p.code); list.items.setAll(ReadingPlans.plans())
                }
            }
        }
        val folder = Hyperlink("Own plans: ${ReadingPlans.userPlanDir}").apply {
            tooltip = Tooltip("Put reading plan .properties files here (same format as AndBible's jsword/readingplan folder)")
            setOnAction { ReadingPlans.userPlanDir.mkdirs(); app.openBrowser(ReadingPlans.userPlanDir.toURI().toString()) }
        }
        root.top = null
        root.center = list
        root.bottom = VBox(8.0, folder, HBox(8.0, start, reset)).apply { padding = Insets(8.0, 0.0, 0.0, 0.0) }
    }

    private fun start(p: ReadingPlanInfo) {
        ReadingPlans.startPlan(p.code, p.startDate ?: ReadingPlans.truncatedDate())
        day = 0
        showDay(p.code, ReadingPlans.currentDay(p.code))
    }

    // ---------------------------------------------------------------- one day

    private fun showDay(code: String, d: Int) {
        val info = ReadingPlans.info(code)
        day = d.coerceIn(1, info.numberOfDays.coerceAtLeast(1))
        val reading = ReadingPlans.reading(code, day)
        val current = ReadingPlans.currentDay(code)
        stage.title = "Reading Plan – ${info.name}"

        val title = FxLabel(info.name).apply { style = "-fx-font-size: 1.3em; -fx-font-weight: bold" }
        val status = when {
            info.isDateBased -> ""
            day < current -> " · done"
            day == current -> " · current day"
            else -> " · ahead"
        }
        val sub = FxLabel("${reading.dayDesc} of ${info.numberOfDays}${reading.dateString.let { if (it.isEmpty()) "" else " · $it" }}$status")
        val prev = Button("◀").apply { tooltip = Tooltip("Previous day"); isDisable = day <= 1; setOnAction { showDay(code, day - 1) } }
        val next = Button("▶").apply { tooltip = Tooltip("Next day"); isDisable = day >= info.numberOfDays; setOnAction { showDay(code, day + 1) } }
        val today = Button("Today").apply { setOnAction { showDay(code, ReadingPlans.currentDay(code)) } }
        root.top = VBox(4.0, HBox(8.0, VBox(2.0, title, sub), Region().also { HBox.setHgrow(it, Priority.ALWAYS) }, prev, today, next)
            .apply { alignment = Pos.CENTER_LEFT })
            .apply { padding = Insets(0.0, 0.0, 10.0, 0.0) }

        val rows = VBox(6.0)
        reading.keys.forEachIndexed { i, key ->
            val no = i + 1
            val check = CheckBox().apply {
                isSelected = ReadingPlans.isRead(code, day, no)
                isDisable = !info.isDateBased && day < current
                setOnAction { ReadingPlans.setRead(code, day, no, isSelected); updateDone() }
            }
            val link = Hyperlink(key.name).apply {
                style = "-fx-font-size: 1.15em"
                setOnAction {
                    ReadingPlans.setRead(code, day, no); check.isSelected = true; updateDone()
                    app.openPassage(key)
                }
            }
            val speak = Button("🔊").apply {
                tooltip = Tooltip("Read aloud")
                setOnAction { speak(key); ReadingPlans.setRead(code, day, no); check.isSelected = true; updateDone() }
            }
            rows.children += HBox(8.0, check, link, Region().also { HBox.setHgrow(it, Priority.ALWAYS) }, speak).apply { alignment = Pos.CENTER_LEFT }
        }
        if (reading.keys.isEmpty()) rows.children += FxLabel("No readings scheduled for this day.")

        doneButton = Button("Done").apply {
            isDefaultButton = true
            tooltip = Tooltip("Mark this day as read and go to the next one")
            setOnAction { done(code, force = false) }
        }
        val speakAll = Button("Read aloud").apply {
            isDisable = reading.keys.isEmpty()
            setOnAction { app.speak(reading.keys.joinToString("\n\n") { k -> textOf(k) }); ReadingPlans.setAllRead(code, day); showDay(code, day) }
        }
        val fullPlan = Button("Full plan…").apply { setOnAction { showFullPlan(code) } }
        val startDate = Button("Start date…").apply {
            isDisable = info.isDateBased
            setOnAction { chooseStartDate(code) }
        }
        val choose = Button("Change plan…").apply { setOnAction { day = 0; showSelector() } }
        root.center = ScrollPane(rows).apply { isFitToWidth = true; style = "-fx-background-color: transparent" }
        val spacer = Region().also { HBox.setHgrow(it, Priority.ALWAYS) }
        root.bottom = HBox(8.0, doneButton, speakAll, spacer, fullPlan, startDate, choose).apply { padding = Insets(10.0, 0.0, 0.0, 0.0) }
        updateDone()
    }

    private var doneButton: Button? = null

    private fun updateDone() {
        val code = ReadingPlans.currentPlanCode ?: return
        val allRead = ReadingPlans.isAllRead(code, day)
        doneButton?.text = if (allRead) "Done" else "All read – done"
    }

    private fun done(code: String, force: Boolean) {
        val allRead = ReadingPlans.isAllRead(code, day)
        val current = ReadingPlans.currentDay(code)
        val doForce = force || !allRead || day != current
        if (doForce && day > current && !app.ask("Reading plan", "Day $day is ahead of the plan (current day $current). Mark it done anyway?")) return
        val lastDay = ReadingPlans.info(code).numberOfDays == day
        val next = ReadingPlans.done(code, day, doForce)
        when {
            lastDay && day >= current -> { app.info("Reading plan", "Congratulations, you finished the plan!"); day = 0; showSelector() }
            next == -1 -> { app.toast("You are up to date with your reading plan"); showDay(code, ReadingPlans.currentDay(code)) }
            else -> showDay(code, next)
        }
    }

    private fun bible(): SwordBook? = (app.activeWindow?.page as? Page.Bible)?.book ?: Sword.bibles.firstOrNull()

    private fun textOf(key: org.crosswire.jsword.passage.Key): String {
        val b = bible() ?: return ""
        val p = key as? org.crosswire.jsword.passage.Passage ?: return ""
        return runCatching { Content.plainText(b, p.toV11n(b.versification)) }.getOrDefault("")
    }

    private fun speak(key: org.crosswire.jsword.passage.Key) = app.speak(textOf(key))

    private fun chooseStartDate(code: String) {
        val start = ReadingPlans.startDate(code) ?: Date()
        val picker = DatePicker(start.toInstant().atZone(ZoneId.systemDefault()).toLocalDate())
        val dlg = Dialog<ButtonType>().apply {
            initOwner(stage); title = "Start date"; headerText = "When did you start this plan?"
            dialogPane.content = picker
            dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)
        }
        if (dlg.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return
        val d = Date.from(picker.value.atStartOfDay(ZoneId.systemDefault()).toInstant())
        ReadingPlans.startPlan(code, d)
        showDay(code, day)
    }

    // ---------------------------------------------------------------- full plan list

    private fun showFullPlan(code: String) {
        val info = ReadingPlans.info(code)
        val current = ReadingPlans.currentDay(code)
        val table = TableView<DaysReading>()
        fun col(title: String, width: Double, f: (DaysReading) -> String) = TableColumn<DaysReading, String>(title).apply {
            prefWidth = width
            setCellValueFactory { javafx.beans.property.SimpleStringProperty(f(it.value)) }
        }
        table.columns.addAll(
            col("", 30.0) { if (ReadingPlans.isAllRead(code, it.day)) "✓" else "" },
            col("Day", 50.0) { it.day.toString() },
            col("Date", 110.0) { it.dateString },
            col("Readings", 330.0) { it.readingsDesc },
        )
        table.items.setAll(ReadingPlans.readingList(code))
        table.setRowFactory {
            object : TableRow<DaysReading>() {
                override fun updateItem(item: DaysReading?, empty: Boolean) {
                    super.updateItem(item, empty)
                    style = if (item != null && !info.isDateBased && item.day == current) "-fx-font-weight: bold" else ""
                }
            }.apply { setOnMouseClicked { e -> if (e.clickCount == 2 && item != null) showDay(code, item.day) } }
        }
        table.scrollTo((current - 3).coerceAtLeast(0))
        table.selectionModel.select(current - 1)
        stage.title = "Reading Plan – ${info.name}"
        root.top = FxLabel("${info.name} – double-click a day to open it").apply { padding = Insets(0.0, 0.0, 8.0, 0.0) }
        root.center = table
        root.bottom = HBox(8.0, Button("Back").apply { setOnAction { showDay(code, day) } }).apply { padding = Insets(10.0, 0.0, 0.0, 0.0) }
    }

    companion object {
        private var instance: ReadingPlanDialog? = null

        fun show(app: MainApp) {
            val d = instance ?: ReadingPlanDialog(app).also { instance = it }
            d.day = 0
            d.showCurrent()
            d.stage.show()
            d.stage.toFront()
        }
    }
}
