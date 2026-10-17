plugins {
    kotlin("jvm") version "2.2.10"
    kotlin("plugin.serialization") version "2.2.10"
    application
}

group = "org.linbible"
version = "0.2.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":jsword"))
    implementation("org.jdom:jdom2:2.0.6.1")
    implementation("jaxen:jaxen:1.2.0")
    implementation("org.apache.commons:commons-text:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.slf4j:slf4j-api:1.7.36")
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
    // Libraries used by AndBible's AI code (compiled into LinBible from the upstream sources)
    implementation("org.json:json:20250517")
    implementation("com.squareup.okhttp3:okhttp:5.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-javafx:1.10.2")
    implementation("org.yaml:snakeyaml:2.2")
    implementation("org.commonmark:commonmark:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.24.0")
    implementation(kotlin("reflect"))
    implementation("javax.inject:javax.inject:1")
    runtimeOnly("org.slf4j:slf4j-simple:1.7.36")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // parameter names and real default methods are needed by the Room replacement (MiniRoom.kt)
        javaParameters.set(true)
        freeCompilerArgs.add("-jvm-default=enable")
    }
}

// Unmodified upstream sources compiled into LinBible (database migrations and the id type), so the
// SQLite databases stay identical to AndBible's (backups and cloud sync are interchangeable).
val upstreamSrc = file("../upstream/app/src/main/java/net/bible/android/database")
sourceSets.main {
    kotlin.srcDir(layout.buildDirectory.dir("upstream-src"))
}
val copyUpstreamSources by tasks.registering(Copy::class) {
    from(upstreamSrc) {
        include("IdType.kt")
        include("migrations/Utilities.kt", "migrations/BookmarkMigrations.kt", "migrations/ReadingPlansMigrations.kt",
            "migrations/ProgressMigrations.kt", "migrations/AiSettingsMigrations.kt", "migrations/MyDocumentMigrations.kt",
            "migrations/WorkspacesMigrations.kt")
    }
    into(layout.buildDirectory.dir("upstream-src/net/bible/android/database"))
}
// MyBible, MySword and e-Sword document backends
val copyUpstreamBackends by tasks.registering(Copy::class) {
    from("../upstream/app/src/main/java/net/bible/service/sword") {
        include("SqliteSwordDriver.kt", "mybible/MyBibleBook.kt", "mybible/MyBibleBookMap.kt", "mysword/MySwordBook.kt",
            "mysword/MySwordBookMap.kt", "esword/ESwordBook.kt", "esword/ESwordBookMap.kt")
    }
    into(layout.buildDirectory.dir("upstream-src/net/bible/service/sword"))
}
// AndBible's AI code and AI documents, compiled unmodified (files that need desktop changes are in src/main/kotlin)
val aiVerbatim = listOf(
    "service/llm/AgentPromptEntities.kt", "service/llm/ApiRequestModels.kt", "service/llm/ApiResponseModels.kt",
    "service/llm/LlmApiAdapter.kt", "service/llm/LlmRetryPolicy.kt", "service/llm/LlmCostTracking.kt", "service/llm/LlmUtils.kt",
    "service/llm/LlmProcessingService.kt", "service/llm/DynamicModelService.kt", "service/llm/BuiltInPrompts.kt",
    "service/llm/PromptCsvUtils.kt",
    "service/llm/tools/Tool.kt", "service/llm/tools/ToolResult.kt", "service/llm/tools/YamlUtils.kt",
    "service/llm/tools/AiDocumentFilter.kt",
    "service/llm/agent/AgentEvent.kt", "service/llm/agent/AgentLogEntities.kt", "service/llm/agent/AgentPermissions.kt",
    "service/llm/agent/RawLlmLog.kt",
    "service/common/AiSettings.kt", "service/sword/OsisToPlainText.kt",
    "android/database/mydocument/MyDocumentEntities.kt", "android/database/mydocument/MyDocumentDao.kt",
    "service/sword/mydocument/MyDocumentBackend.kt", "service/sword/mydocument/MarkdownToXhtml.kt",
    "android/database/bookmarks/BookmarkEntities.kt", "android/database/bookmarks/BookmarksDao.kt",
    "android/database/bookmarks/SpeakSettings.kt", "android/control/bookmark/StudyPadSearchModels.kt",
    "service/llm/agent/AgentContext.kt", "service/llm/tools/ToolUtils.kt", "service/llm/PromptRepository.kt",
    "service/llm/tools/ToolRegistry.kt", "service/llm/agent/AgentExecutor.kt", "service/llm/agent/AgentSessionManager.kt",
    "service/llm/tools/read/GetWindowsTool.kt", "service/llm/tools/write/CreateWindowTool.kt",
    "service/llm/tools/write/ManageWindowTool.kt", "service/llm/tools/write/SetWindowDocumentTool.kt",
    "service/sword/mydocument/MyDocumentBookManager.kt",
    // tools (window tools and getCommentaries are adapted in src/main/kotlin)
    "service/llm/tools/read/GetVerseContentTool.kt", "service/llm/tools/read/SearchBibleTool.kt",
    "service/llm/tools/read/SearchByStrongsNumberTool.kt", "service/llm/tools/read/GetDictionaryEntryTool.kt",
    "service/llm/tools/read/GetBookmarksForVerseTool.kt", "service/llm/tools/read/GetBookmarksWithLabelTool.kt",
    "service/llm/tools/read/GetAllLabelsTool.kt", "service/llm/tools/read/GetStudyPadContentTool.kt",
    "service/llm/tools/read/SearchStudyPadsTool.kt", "service/llm/tools/read/GetInstalledDocumentsTool.kt",
    "service/llm/tools/read/GetMyDocumentsTool.kt", "service/llm/tools/read/GetMyDocumentPagesTool.kt",
    "service/llm/tools/read/GetGenBookKeysTool.kt", "service/llm/tools/read/GetGenBookContentTool.kt",
    "service/llm/tools/write/CreateBookmarkTool.kt", "service/llm/tools/write/AddBookmarkNoteTool.kt",
    "service/llm/tools/write/UpdateBookmarkNoteTool.kt", "service/llm/tools/write/CreateLabelTool.kt",
    "service/llm/tools/write/AddLabelToBookmarkTool.kt", "service/llm/tools/write/DeleteBookmarkTool.kt",
    "service/llm/tools/write/DeleteLabelTool.kt", "service/llm/tools/write/RemoveLabelFromBookmarkTool.kt",
    "service/llm/tools/write/AddStudyPadEntryTool.kt", "service/llm/tools/write/UpdateStudyPadTextEntryTool.kt",
    "service/llm/tools/write/CreateStudyPadTool.kt", "service/llm/tools/write/CreateMyDocumentTool.kt",
    "service/llm/tools/write/AddMyDocumentPageTool.kt", "service/llm/tools/write/EditMyDocumentPageTool.kt",
    "service/llm/tools/write/DeleteMyDocumentPageTool.kt", "service/llm/tools/write/SetDocumentTitleTool.kt",
    "service/llm/tools/write/FinishWithStudyPadTool.kt", "service/llm/tools/write/FinishWithMyDocumentPageTool.kt",
    "service/llm/tools/write/FinishWithoutDocumentTool.kt",
)
val copyUpstreamAi by tasks.registering(Copy::class) {
    from("../upstream/app/src/main/java/net/bible") { include(aiVerbatim) }
    into(layout.buildDirectory.dir("upstream-src/net/bible"))
}

// AndBible's string resources as an R class (ids into a string table) for the compiled upstream code
val generateAndroidR by tasks.registering {
    val sources = listOf(file("../upstream/app/src/main/res/values/strings.xml"), file("../upstream/app/src/main/res/values/untranslated_strings.xml"))
    val outDir = layout.buildDirectory.dir("generated/android-r")
    inputs.files(sources)
    outputs.dir(outDir)
    doLast {
        val names = mutableListOf<String>()
        val texts = mutableListOf<String>()
        val dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        for (src in sources) {
            val doc = dbf.newDocumentBuilder().parse(src)
            val nodes = doc.getElementsByTagName("string")
            for (i in 0 until nodes.length) {
                val e = nodes.item(i) as org.w3c.dom.Element
                val name = e.getAttribute("name")
                if (name in names) continue
                names += name
                texts += e.textContent
            }
        }
        val dir = outDir.get().asFile
        val kt = File(dir, "kotlin/net/bible/android/activity/R.kt").apply { parentFile.mkdirs() }
        kt.writeText(buildString {
            appendLine("// Generated from AndBible's res/values/strings.xml")
            appendLine("package net.bible.android.activity")
            appendLine("object R {")
            appendLine("    object string {")
            names.forEachIndexed { i, n -> appendLine("        const val $n = $i") }
            appendLine("    }")
            appendLine("    object raw {")
            appendLine("        const val llm_agent_system_prompt = 0")
            appendLine("        const val llm_text_transformation_system_prompt = 1")
            appendLine("    }")
            appendLine("}")
        })
        val res = File(dir, "resources/android-strings.txt").apply { parentFile.mkdirs() }
        // one string per line, raw Android escaping kept (decoded at runtime)
        res.writeText(texts.joinToString("\n") { it.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "") })
    }
}
sourceSets.main {
    kotlin.srcDir(layout.buildDirectory.dir("generated/android-r/kotlin"))
    resources.srcDir(layout.buildDirectory.dir("generated/android-r/resources"))
}
tasks.processResources {
    dependsOn(generateAndroidR)
    from("../upstream/app/src/main/res/raw") {
        include("llm_agent_system_prompt.md", "llm_text_transformation_system_prompt.md")
        into("raw")
    }
}
tasks.compileKotlin { dependsOn(copyUpstreamSources, copyUpstreamBackends, copyUpstreamAi, generateAndroidR) }

application {
    mainClass.set("org.linbible.MainAppKt")
    applicationName = "linbible"
}

// The BibleView Vue.js frontend is built by scripts/build-frontend.sh into ../build-frontend/dist
val frontendDist = file("../build-frontend/dist")
tasks.processResources {
    from(frontendDist) { into("web") }
    inputs.dir(frontendDist).optional()
    // Room schema definitions, used to create databases identical to AndBible's
    from("../upstream/app/schemas") { into("schemas") }
    // AndBible's bundled reading plans
    from("../upstream/app/src/main/assets/readingplan") { into("readingplan") }
    // public key that signs AndBible feature add-ons
    from("../upstream/app/src/main/res/raw/publickey.pem")
}

tasks.test { useJUnitPlatform() }
