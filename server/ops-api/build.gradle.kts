plugins { kotlin("jvm") }

tasks.processResources {
    from(rootProject.file("../tools/l10n")) {
        include("editor.html", "editor.js", "editor-ui.js")
        into("admin/text")
    }
}

tasks.register<JavaExec>("runProgress") {
    classpath=sourceSets["main"].runtimeClasspath
    mainClass.set("org.logh7.ops.ProgressDashboardKt")
    args(providers.gradleProperty("progressPort").orElse("48093").get(),rootProject.file("../docs/IMPLEMENTATION-CHECKLIST.md").absolutePath)
}
dependencies {
implementation(project(":engine"))
implementation("io.ktor:ktor-server-core-jvm:3.6.0")
implementation("io.ktor:ktor-server-netty-jvm:3.6.0")
}
