plugins {
    kotlin("jvm") version "2.2.10"
    kotlin("plugin.serialization") version "2.2.10"
    application
}

group = "org.linbible"
version = "0.1.0"

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
    runtimeOnly("org.slf4j:slf4j-simple:1.7.36")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("org.linbible.MainAppKt")
    applicationName = "linbible"
}

// The BibleView Vue.js frontend is built by scripts/build-frontend.sh into ../build-frontend/dist
val frontendDist = file("../build-frontend/dist")
tasks.processResources {
    from(frontendDist) { into("web") }
    inputs.dir(frontendDist).optional()
}
