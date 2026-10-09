plugins { kotlin("jvm") }
dependencies {
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
}

// Use the reviewed manual CSVs as the single source of static seed definitions.
tasks.processResources {
    from(rootProject.projectDir.resolve("../docs/manual/data")) {
        include("org-posts.csv", "initial-card-holders.csv")
        into("worldseed")
    }
}
