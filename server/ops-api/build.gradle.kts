plugins { kotlin("jvm") }

tasks.processResources {
    from(rootProject.file("../tools/l10n")) {
        include("editor.html", "editor.js", "editor-ui.js")
        into("admin/text")
    }
}
dependencies {
implementation(project(":engine"))
implementation("io.ktor:ktor-server-core-jvm:3.6.0")
implementation("io.ktor:ktor-server-netty-jvm:3.6.0")
}
