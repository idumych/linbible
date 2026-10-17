package org.linbible

import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Creates every older AndBible schema version that has an exported Room schema, upgrades it with AndBible's
 * migrations and checks that the result matches a freshly created database (what Room's validation checks).
 */
class MigrationTest {
    private val dir = Files.createTempDirectory("linbible-migrations").toFile()

    private fun describe(c: Connection): String = buildString {
        val tables = c.createStatement().executeQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata' ORDER BY name"
        ).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        for (t in tables) {
            appendLine("TABLE $t")
            c.createStatement().executeQuery("PRAGMA table_info(`$t`)").use { rs ->
                val cols = buildList {
                    while (rs.next()) add("  ${rs.getString("name")} ${rs.getString("type")} notnull=${rs.getInt("notnull")} " +
                        "pk=${rs.getInt("pk")}")
                }
                cols.sorted().forEach(::appendLine)
            }
            c.createStatement().executeQuery("PRAGMA foreign_key_list(`$t`)").use { rs ->
                val fks = buildList {
                    while (rs.next()) add("  FK ${rs.getString("from")} -> ${rs.getString("table")}.${rs.getString("to")} " +
                        "${rs.getString("on_update")} ${rs.getString("on_delete")}")
                }
                fks.sorted().forEach(::appendLine)
            }
        }
        // Indices by table and columns (some old migrations keep earlier index names, as on Android)
        val indices = c.createStatement().executeQuery(
            "SELECT name, tbl_name FROM sqlite_master WHERE type = 'index' AND name NOT LIKE 'sqlite_%'"
        ).use { rs -> buildList { while (rs.next()) add(rs.getString(1) to rs.getString(2)) } }
        indices.map { (name, table) ->
            val cols = c.createStatement().executeQuery("PRAGMA index_info(`$name`)").use { rs ->
                buildList { while (rs.next()) add(rs.getString("name")) }
            }
            "index on $table(${cols.joinToString()})"
        }.sorted().forEach(::appendLine)
        c.createStatement().executeQuery("SELECT name FROM sqlite_master WHERE type = 'view' ORDER BY name")
            .use { rs -> while (rs.next()) appendLine("view ${rs.getString(1)}") }
    }

    private fun versions(c: DbCategory): List<Int> {
        val d = File("../upstream/app/schemas/${c.schema}")
        return d.listFiles()!!.map { it.name.removeSuffix(".json").toInt() }.sorted()
    }

    private val failures = mutableListOf<String>()

    @Test
    fun migrationsReachLatestSchema() {
        for (c in DbCategory.entries) {
            val fresh = File(dir, "fresh-${c.filename}")
            DriverManager.getConnection("jdbc:sqlite:$fresh").use { RoomDb.createSchema(SupportSQLiteDatabase(it), c) }
            val expected = DriverManager.getConnection("jdbc:sqlite:$fresh").use(::describe)
            for (v in versions(c).filter { it < c.version }) {
                val f = File(dir, "v$v-${c.filename}")
                DriverManager.getConnection("jdbc:sqlite:$f").use { RoomDb.createSchema(SupportSQLiteDatabase(it), c, v) }
                val db = RoomDb(c, f, withTriggers = false).apply { open() }
                var actual = describe(db.connection)
                // AndBible's AI settings schema 17 added a column that later versions dropped without a migration;
                // Android devices upgraded from it keep the unused column as well.
                if (c == DbCategory.AI_SETTINGS && v == 17) actual = actual.replace("  builtInPromptCategories TEXT notnull=0 pk=0\n", "")
                db.close()
                if (expected != actual) {
                    File(dir, "expected-${c.name}.txt").writeText(expected)
                    File(dir, "actual-${c.name}-v$v.txt").writeText(actual)
                    println("DIFF ${c.name} v$v: ${dir}/expected-${c.name}.txt ${dir}/actual-${c.name}-v$v.txt")
                    failures += "${c.name} v$v"
                    continue
                }
                println("${c.name}: v$v -> v${c.version} OK")
            }
        }
        assertEquals(emptyList(), failures)
    }
}
