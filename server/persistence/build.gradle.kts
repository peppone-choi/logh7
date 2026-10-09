plugins { kotlin("jvm") }
dependencies {
implementation(project(":engine"))
testImplementation(project(":gateway"))
testImplementation(project(":protocol"))
testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
}
tasks.test { systemProperty("logh7.test.runtimeClasspath", sourceSets["test"].runtimeClasspath.asPath) }
