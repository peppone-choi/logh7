plugins { kotlin("jvm") }
dependencies {
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
}

// Keep the reviewed CSV authoritative; no copied command table in Kotlin.
tasks.processResources {
    from(rootProject.projectDir.resolve("../docs/manual/data")) {
        include("strategy-commands.csv")
        into("strategy")
    }
}
